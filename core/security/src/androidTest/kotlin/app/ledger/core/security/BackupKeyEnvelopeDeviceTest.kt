package app.ledger.core.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.common.StableId
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BackupKeyEnvelopeDeviceTest {
    private lateinit var context: Context
    private lateinit var keys: DeviceKeyHierarchy

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        keys = DeviceKeyHierarchy(AndroidKeystoreKeys(context), SecurityEnvelopeStore(context))
        keys.destroyLocal(BOOK)
        keys.initialize(BOOK)
    }

    @After
    fun tearDown() {
        BackupKeyEnvelopeStore(context, keys).delete(REPOSITORY_A)
        BackupKeyEnvelopeStore(context, keys).delete(REPOSITORY_B)
        keys.destroyLocal(BOOK)
    }

    @Test
    fun repositoryKeyHasIndependentArgon2idSaltAndDeviceBackgroundEnvelope() {
        val chars = "P30-Recovery-Password-123".toCharArray()
        val password = RecoveryPassword.copyOf(chars)
        chars.fill('\u0000')
        val store = BackupKeyEnvelopeStore(context, keys)
        try {
            val first = store.configure(BOOK, REPOSITORY_A, password, Argon2idParameters.minimum())
            val second = store.configure(BOOK, REPOSITORY_B, password, Argon2idParameters.minimum())
            val firstSalt = RecoveryWrappedKeyMaterialCodec.decode(first.recoveryEnvelope).salt
            val secondSalt = RecoveryWrappedKeyMaterialCodec.decode(second.recoveryEnvelope).salt
            assertNotEquals(firstSalt.toList(), secondSalt.toList())
            store.openForAutomaticBackup(BOOK, REPOSITORY_A).use { automatic ->
                store.openWithRecoveryPassword(BOOK, REPOSITORY_A, password).use { recovered ->
                    val same = automatic.useBytes { left -> recovered.useBytes { right -> left.contentEquals(right) } }
                    assertTrue(same)
                }
            }
            val wrongChars = "P30-Wrong-Password-999".toCharArray()
            val wrong = RecoveryPassword.copyOf(wrongChars)
            wrongChars.fill('\u0000')
            wrong.use {
                assertTrue(runCatching { store.openWithRecoveryPassword(BOOK, REPOSITORY_A, it).close() }.isFailure)
            }
        } finally {
            password.close()
        }
    }

    @Test
    fun automaticVaultBackupReturnsOnlyRecoveryWrappedCiphertext() {
        val vaultKeyBytes = "PAN-4111111111111111-CVC-123".toByteArray()
        val vaultKey = SecretBytes.copyOf(vaultKeyBytes)
        val chars = "P30-Vault-Recovery-456".toCharArray()
        val password = RecoveryPassword.copyOf(chars)
        chars.fill('\u0000')
        try {
            val store = VaultBackupEnvelopeStore(context)
            val encoded = store.configure(BOOK, vaultKey, password, Argon2idParameters.minimum())
            val background = requireNotNull(store.readForAutomaticBackup(BOOK))
            assertTrue(encoded.contentEquals(background))
            assertFalse(background.contains(vaultKeyBytes))
            RecoveryWrappedKeyMaterialCodec.decode(background)
        } finally {
            vaultKeyBytes.fill(0)
            vaultKey.close()
            password.close()
        }
    }

    private fun ByteArray.contains(needle: ByteArray): Boolean = indices.any { start ->
        start + needle.size <= size && needle.indices.all { offset -> this[start + offset] == needle[offset] }
    }

    private companion object {
        val BOOK = StableId.fromUuid(UUID(30, 700))
        val REPOSITORY_A = StableId.fromUuid(UUID(30, 701))
        val REPOSITORY_B = StableId.fromUuid(UUID(30, 702))
    }
}
