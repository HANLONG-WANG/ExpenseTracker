package app.ledger.core.security

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricPrompt
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.ledger.core.common.StableId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
class VaultAuthenticationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context
    private lateinit var keystore: AndroidKeystoreKeys
    private lateinit var envelopeStore: SecurityEnvelopeStore
    private lateinit var deviceHierarchy: DeviceKeyHierarchy

    @Before
    fun prepare() {
        context = ApplicationProvider.getApplicationContext()
        clearTestPin()
        keystore = AndroidKeystoreKeys(context)
        envelopeStore = SecurityEnvelopeStore(context)
        deviceHierarchy = DeviceKeyHierarchy(keystore, envelopeStore)
        deviceHierarchy.destroyLocal(BOOK_ID)
    }

    @After
    fun cleanUp() {
        deviceHierarchy.destroyLocal(BOOK_ID)
        clearTestPin()
    }

    @Test
    fun vaultProvisionAndExportEachRequireARealAuthenticatedCryptoObject() {
        assertEquals(DeviceSecurityCapability.MISSING_DEVICE_CREDENTIAL, keystore.deviceSecurityCapability())
        val setResult = shell("locksettings set-pin $TEST_PIN")
        assertFalse("locksettings failed: $setResult", setResult.contains("Error", ignoreCase = true))
        assertTrue(waitForDeviceSecure())
        assertEquals(DeviceSecurityCapability.DEVICE_CREDENTIAL_ONLY, keystore.deviceSecurityCapability())

        val exposures = VaultExposureRegistry(SystemClock::elapsedRealtime)
        val vault = VaultKeyHierarchy(keystore, envelopeStore, exposures)
        val provisioning = vault.beginProvisioning(BOOK_ID)
        val keyPolicy = keystore.vaultAuthenticationKekPolicy(SecurityEnvelopeStore.aliasSuffix(BOOK_ID))
        assertTrue(keyPolicy.userAuthenticationRequired)
        assertEquals(0, keyPolicy.authenticationValiditySeconds)
        assertFalse(keyPolicy.invalidatedByBiometricEnrollment)
        assertTrue(keyPolicy.authenticationType and KeyProperties.AUTH_DEVICE_CREDENTIAL != 0)
        assertTrue(keyPolicy.authenticationType and KeyProperties.AUTH_BIOMETRIC_STRONG != 0)
        assertEquals(
            BiometricErrorCode.DEVICE_SECURITY_CHANGED,
            BiometricErrorClassifier.classify(BiometricPrompt.ERROR_NO_BIOMETRICS),
        )
        provisioning.use {
            it.complete(authenticate(it.cryptoObject))
        }
        assertTrue(vault.isProvisioned(BOOK_ID))

        val export = vault.beginRecoveryExport(BOOK_ID)
        val recovered = export.use { it.complete(authenticate(it.cryptoObject)) }
        recovered.use { keyset ->
            keyset.useBytes { serialized ->
                val primitive = LedgerTink.aead(serialized)
                val associatedData = SecurityAssociatedData.vaultField(BOOK_ID, CARD_ID, VaultFieldType.PAN, 1)
                val ciphertext = primitive.encrypt(TEST_SECRET, associatedData)
                assertTrue(primitive.decrypt(ciphertext, associatedData).contentEquals(TEST_SECRET))
                associatedData.fill(0)
            }
        }

        val clearResult = shell("locksettings clear --old $TEST_PIN")
        assertFalse("locksettings clear failed: $clearResult", clearResult.contains("Error", ignoreCase = true))
        assertTrue(waitForDeviceInsecure())
        assertThrows(SecurityException.DeviceSecurityUnavailable::class.java) {
            vault.beginRecoveryExport(BOOK_ID)
        }
    }

    private fun authenticate(cryptoObject: BiometricPrompt.CryptoObject): BiometricPrompt.CryptoObject {
        val result = AtomicReference<BiometricAuthenticationResult>()
        val completed = CountDownLatch(1)
        ActivityScenario.launch(SecurityPromptTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                AndroidBiometricPromptGateway(activity, activity.mainExecutor).authenticate(
                    cryptoObject,
                    BiometricPromptText("Authorize vault action", "Device test"),
                ) { authenticationResult ->
                    result.set(authenticationResult)
                    if (authenticationResult !is BiometricAuthenticationResult.FailedAttempt) completed.countDown()
                }
            }
            SystemClock.sleep(PROMPT_SETTLE_MILLIS)
            shell("input text $TEST_PIN")
            shell("input keyevent KEYCODE_ENTER")
            assertTrue("authentication callback timed out", completed.await(AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }
        val authentication = result.get()
        assertTrue("authentication did not succeed: $authentication", authentication is BiometricAuthenticationResult.Success)
        return (authentication as BiometricAuthenticationResult.Success).cryptoObject
    }

    private fun clearTestPin() {
        shell("locksettings clear --old $TEST_PIN")
    }

    private fun waitForDeviceSecure(): Boolean = waitForCapability { it != DeviceSecurityCapability.MISSING_DEVICE_CREDENTIAL }

    private fun waitForDeviceInsecure(): Boolean = waitForCapability { it == DeviceSecurityCapability.MISSING_DEVICE_CREDENTIAL }

    private fun waitForCapability(predicate: (DeviceSecurityCapability) -> Boolean): Boolean {
        repeat(CAPABILITY_POLL_ATTEMPTS) {
            if (predicate(AndroidKeystoreKeys(context).deviceSecurityCapability())) return true
            SystemClock.sleep(CAPABILITY_POLL_MILLIS)
        }
        return false
    }

    private fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return descriptor.readText().also { descriptor.close() }.trim()
    }

    private fun ParcelFileDescriptor.readText(): String = FileInputStream(fileDescriptor).bufferedReader().use { it.readText() }

    private companion object {
        const val TEST_PIN = "2468"
        const val PROMPT_SETTLE_MILLIS = 1_000L
        const val AUTH_TIMEOUT_SECONDS = 20L
        const val CAPABILITY_POLL_ATTEMPTS = 20
        const val CAPABILITY_POLL_MILLIS = 100L
        val BOOK_ID: StableId = StableId.fromUuid(UUID(0L, 0x9002L))
        val CARD_ID: StableId = StableId.fromUuid(UUID(0L, 0x9003L))
        val TEST_SECRET: ByteArray = "4111111111111111".toByteArray()
    }
}
