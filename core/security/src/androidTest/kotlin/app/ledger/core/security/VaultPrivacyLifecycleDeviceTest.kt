package app.ledger.core.security

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.WindowManager
import androidx.biometric.BiometricPrompt
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.ledger.core.common.StableId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class VaultPrivacyLifecycleDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context
    private lateinit var keys: AndroidKeystoreKeys
    private lateinit var envelopes: SecurityEnvelopeStore
    private lateinit var device: DeviceKeyHierarchy

    @Before
    fun prepare() {
        context = ApplicationProvider.getApplicationContext()
        clearPin()
        assertFalse(shell("locksettings set-pin $PIN").contains("Error", true))
        keys = AndroidKeystoreKeys(context)
        envelopes = SecurityEnvelopeStore(context)
        device = DeviceKeyHierarchy(keys, envelopes)
        device.destroyLocal(BOOK)
        VaultBackupEnvelopeStore(context).delete(BOOK)
        assertTrue(waitForSecurity(true))
    }

    @After
    fun cleanup() {
        device.destroyLocal(BOOK)
        VaultBackupEnvelopeStore(context).delete(BOOK)
        clearPin()
    }

    @Test
    fun independentActionsUseFreshCryptoObjectsAndBackgroundClearsEveryExposure() {
        var elapsed = 1_000L
        val exposure = VaultExposureRegistry { elapsed }
        val appLock = AppLockController(AppLockSettings(true, AppLockTimeout.Immediately), { elapsed }) {
            exposure.onApplicationLocked()
        }
        val vault = VaultKeyHierarchy(keys, envelopes, exposure)
        val provisioning = vault.beginProvisioning(BOOK)
        val editor = provisioning.use { it.completeForEditing(authenticate(it.cryptoObject)) }
        val encrypted = editor.use {
            it.encryptFields(
                BOOK,
                CARD,
                1,
                VaultPlaintextFields(
                    holderName = null,
                    primaryNumber = SecretBytes.copyOf(PAN),
                    expiry = null,
                    securityCode = SecretBytes.copyOf(CVC),
                    customFields = null,
                ),
            )
        }
        val panCiphertext = requireNotNull(encrypted.primaryNumber)
        val codeCiphertext = requireNotNull(encrypted.securityCode)
        assertTrue(appLock.currentState() == AppLockState.Locked)
        appLock.authenticationSucceeded()
        assertTrue(appLock.currentState() == AppLockState.Unlocked)

        val panRequest = vault.beginReveal(BOOK, CARD, VaultFieldType.PAN, 1, VaultAction.REVEAL_PAN, panCiphertext)
        val panCrypto = panRequest.cryptoObject
        val pan = panRequest.use { it.complete(authenticate(panCrypto)) }
        pan.useBytes { assertTrue(it.contentEquals(PAN)) }

        val rejectedReuse = vault.beginReveal(BOOK, CARD, VaultFieldType.PAN, 1, VaultAction.COPY_PAN, panCiphertext)
        assertTrue(runCatching { rejectedReuse.use { it.complete(panCrypto) } }.isFailure)

        val copyRequest = vault.beginReveal(BOOK, CARD, VaultFieldType.PAN, 1, VaultAction.COPY_PAN, panCiphertext)
        assertTrue(copyRequest.cryptoObject !== panCrypto)
        val copied = copyRequest.use { it.complete(authenticate(it.cryptoObject)) }
        copied.useBytes { assertTrue(it.contentEquals(PAN)) }

        val codeRequest = vault.beginReveal(BOOK, CARD, VaultFieldType.SECURITY_CODE, 1, VaultAction.REVEAL_SECURITY_CODE, codeCiphertext)
        assertTrue(codeRequest.cryptoObject !== panCrypto)
        val code = codeRequest.use { it.complete(authenticate(it.cryptoObject)) }
        code.useBytes { assertTrue(it.contentEquals(CVC)) }
        assertEquals(3, exposure.activeCount())
        assertTrue(appLock.currentState() == AppLockState.Unlocked)

        elapsed += 30_000L
        exposure.clearExpired()
        assertEquals(0, exposure.activeCount())
        val again = vault.beginReveal(BOOK, CARD, VaultFieldType.PAN, 1, VaultAction.REVEAL_PAN, panCiphertext)
            .use { it.complete(authenticate(it.cryptoObject)) }
        assertEquals(1, exposure.activeCount())
        appLock.forceLock()
        assertTrue(appLock.currentState() == AppLockState.Locked)
        assertEquals(0, exposure.activeCount())
        assertTrue(runCatching { again.useBytes(ByteArray::size) }.isFailure)
    }

    @Test
    fun recoveryWrappedVaultDekIsReboundToFreshDeviceAuthenticationKek() {
        val passwordChars = "P32-Recovery-Password".toCharArray()
        val password = RecoveryPassword.copyOf(passwordChars)
        passwordChars.fill('\u0000')
        val originalKey = LedgerTink.generateAeadKeyset()
        val associatedData = SecurityAssociatedData.vaultField(BOOK, CARD, VaultFieldType.PAN, 1)
        val ciphertext = originalKey.useBytes { LedgerTink.aead(it).encrypt(PAN, associatedData) }
        VaultBackupEnvelopeStore(context).configure(BOOK, originalKey, password, Argon2idParameters.minimum())
        originalKey.close()

        device.destroyLocal(BOOK)
        val recovered = VaultBackupEnvelopeStore(context).openWithRecoveryPassword(BOOK, password)
        val vault = VaultKeyHierarchy(keys, envelopes, VaultExposureRegistry(SystemClock::elapsedRealtime))
        val restore = vault.beginRestore(BOOK, recovered)
        restore.use { it.complete(authenticate(it.cryptoObject)) }
        assertTrue(vault.isProvisioned(BOOK))
        val reveal = vault.beginReveal(
            BOOK,
            CARD,
            VaultFieldType.PAN,
            1,
            VaultAction.REVEAL_PAN,
            VaultFieldCiphertext(ciphertext),
        )
        reveal.use { request ->
            request.complete(authenticate(request.cryptoObject)).use { plaintext ->
                plaintext.useBytes { assertTrue(it.contentEquals(PAN)) }
            }
        }
        password.close()
        associatedData.fill(0)
        ciphertext.fill(0)
    }

    @Test
    fun clipboardIsMarkedSensitiveAndClearsOnTimerAndBackground() {
        val registry = VaultExposureRegistry(SystemClock::elapsedRealtime)
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        ActivityScenario.launch(SecurityPromptTestActivity::class.java).use { scenario ->
            VaultClipboardController(context, clearDelayMillis = 250L).use { controller ->
                scenario.onActivity {
                    val first = registry.register(PAN)
                    controller.copyPrimaryNumber(first)
                    first.close()
                    assertNotNull(clipboard.primaryClip)
                    assertTrue(clipboard.primaryClipDescription?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true)
                }
                SystemClock.sleep(500L)
                scenario.onActivity {
                    assertTrue(clipboard.primaryClip == null || clipboard.primaryClip!!.itemCount == 0)
                    val second = registry.register(PAN)
                    controller.copyPrimaryNumber(second)
                    second.close()
                    controller.onApplicationBackgrounded()
                    assertTrue(clipboard.primaryClip == null || clipboard.primaryClip!!.itemCount == 0)
                }
            }
        }
    }

    @Test
    fun vaultAndBackgroundPrivacyAlwaysApplyFlagSecure() {
        ActivityScenario.launch(SecurityPromptTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val controller = AndroidScreenPrivacyController(activity)
                controller.apply(ScreenPrivacyPolicy(vaultVisible = true))
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                controller.apply(ScreenPrivacyPolicy(obscureRecentTasks = true, applicationInBackground = true))
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                controller.apply(ScreenPrivacyPolicy(obscureRecentTasks = false))
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0)
            }
        }
    }

    private fun authenticate(cryptoObject: BiometricPrompt.CryptoObject): BiometricPrompt.CryptoObject {
        val result = AtomicReference<BiometricAuthenticationResult>()
        val completed = CountDownLatch(1)
        ActivityScenario.launch(SecurityPromptTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                AndroidBiometricPromptGateway(activity, activity.mainExecutor).authenticate(
                    cryptoObject,
                    BiometricPromptText("Authorize vault action", "P32 device evidence"),
                ) { value ->
                    result.set(value)
                    if (value !is BiometricAuthenticationResult.FailedAttempt) completed.countDown()
                }
            }
            SystemClock.sleep(1_000L)
            shell("input text $PIN")
            shell("input keyevent KEYCODE_ENTER")
            assertTrue(completed.await(20L, TimeUnit.SECONDS))
        }
        return (result.get() as BiometricAuthenticationResult.Success).cryptoObject
    }

    private fun clearPin() {
        shell("locksettings clear --old $PIN")
    }
    private fun waitForSecurity(expected: Boolean): Boolean {
        repeat(30) {
            val secure = AndroidKeystoreKeys(context).deviceSecurityCapability() != DeviceSecurityCapability.MISSING_DEVICE_CREDENTIAL
            if (secure == expected) return true
            SystemClock.sleep(100L)
        }
        return false
    }
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).let { descriptor ->
        descriptor.readText().also { descriptor.close() }.trim()
    }
    private fun ParcelFileDescriptor.readText(): String = FileInputStream(fileDescriptor).bufferedReader().use { it.readText() }

    private companion object {
        const val PIN = "2468"
        val BOOK: StableId = StableId.fromUuid(UUID(0L, 0x9320L))
        val CARD: StableId = StableId.fromUuid(UUID(0L, 0x9321L))
        val PAN: ByteArray = "4111111111111111".toByteArray()
        val CVC: ByteArray = "123".toByteArray()
    }
}
