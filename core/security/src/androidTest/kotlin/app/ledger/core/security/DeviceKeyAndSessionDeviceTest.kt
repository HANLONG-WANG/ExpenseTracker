package app.ledger.core.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyProperties
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.common.StableId
import app.ledger.core.database.EncryptedDatabaseFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DeviceKeyAndSessionDeviceTest {
    private lateinit var context: Context
    private lateinit var keystore: AndroidKeystoreKeys
    private lateinit var hierarchy: DeviceKeyHierarchy

    @Before
    fun prepare() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        keystore = AndroidKeystoreKeys(context)
        hierarchy = DeviceKeyHierarchy(keystore, SecurityEnvelopeStore(context))
        hierarchy.destroyLocal(BOOK_ID)
    }

    @After
    fun cleanUp() {
        hierarchy.destroyLocal(BOOK_ID)
        context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
    }

    @Test
    fun deviceLedgerKeysOpenEncryptedDatabaseWithoutUserAuthenticationAndReopen() = runBlocking {
        hierarchy.initialize(BOOK_ID)
        val suffix = SecurityEnvelopeStore.aliasSuffix(BOOK_ID)
        val policy = keystore.deviceLedgerKekPolicy(suffix)
        assertFalse(policy.userAuthenticationRequired)

        hierarchy.open(BOOK_ID).use { keys ->
            val associatedData = SecurityAssociatedData.attachmentKey(BOOK_ID, stableId(2), 1)
            val dataKey = keys.createAttachmentDataKey()
            val wrapped = keys.wrapAttachmentDataKey(dataKey, associatedData)
            val attachmentPrimitive = keys.unwrapAttachmentDataKey(wrapped, associatedData)
            assertTrue(wrapped.isNotEmpty())
            assertTrue(attachmentPrimitive.toString().isNotEmpty())
            dataKey.close()
            associatedData.fill(0)
        }

        val registry = VaultExposureRegistry { 0L }
        val manager = BookSessionManager(
            BOOK_ID,
            hierarchy,
            SqlCipherBookDatabaseResourceFactory(context),
            registry,
        )
        manager.initialize()
        manager.unlockUi()
        assertEquals(BookSessionState.Ready(BOOK_ID, 1), manager.state.value)
        manager.lockUi()
        assertEquals(BookSessionState.Locked, manager.state.value)

        val lease = manager.acquireHeadlessLease(stableId(3), HeadlessLeaseCapability.BACKUP_READ)
        assertEquals(BookSessionState.Locked, manager.state.value)
        assertEquals(1, manager.activeHeadlessLeaseCount())
        lease.release()

        manager.unlockUi()
        assertEquals(BookSessionState.Ready(BOOK_ID, 2), manager.state.value)
        manager.close()
    }

    @Test
    fun deletingDeviceKekRequiresRecoveryAndNeverRegeneratesOverExistingEnvelope() = runBlocking {
        hierarchy.initialize(BOOK_ID)
        val suffix = SecurityEnvelopeStore.aliasSuffix(BOOK_ID)
        keystore.deleteDeviceLedgerKek(suffix)

        assertThrows(SecurityException.KeyUnavailable::class.java) { hierarchy.initialize(BOOK_ID) }
        val manager = BookSessionManager(
            BOOK_ID,
            hierarchy,
            SqlCipherBookDatabaseResourceFactory(context),
            VaultExposureRegistry { 0L },
        )
        manager.initialize()
        assertEquals(
            BookSessionState.RecoveryRequired(RecoveryDiagnosticCode.KEY_UNAVAILABLE),
            manager.state.value,
        )
    }

    @Test
    fun vaultKeyPolicyIsPerActionAndRetainsTheCredentialFallbackAcrossEnrollmentChanges() {
        if (keystore.deviceSecurityCapability() == DeviceSecurityCapability.MISSING_DEVICE_CREDENTIAL) {
            assertThrows(SecurityException.DeviceSecurityUnavailable::class.java) {
                keystore.ensureVaultAuthenticationKek(SecurityEnvelopeStore.aliasSuffix(BOOK_ID))
            }
            return
        }

        val suffix = SecurityEnvelopeStore.aliasSuffix(BOOK_ID)
        keystore.ensureVaultAuthenticationKek(suffix)
        val policy = keystore.vaultAuthenticationKekPolicy(suffix)
        assertTrue(policy.userAuthenticationRequired)
        assertEquals(0, policy.authenticationValiditySeconds)
        assertFalse(policy.invalidatedByBiometricEnrollment)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            assertTrue(policy.authenticationType and KeyProperties.AUTH_DEVICE_CREDENTIAL != 0)
            assertTrue(policy.authenticationType and KeyProperties.AUTH_BIOMETRIC_STRONG != 0)
        }
    }

    private fun stableId(index: Long): StableId = StableId.fromUuid(UUID(0L, index))

    private companion object {
        val BOOK_ID: StableId = StableId.fromUuid(UUID(0L, 0x9001L))
    }
}
