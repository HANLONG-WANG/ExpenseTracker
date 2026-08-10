@file:Suppress("LongMethod", "MagicNumber")

package app.ledger.transfer.data

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.security.Argon2idParameters
import app.ledger.core.security.LedgerTink
import app.ledger.core.security.RecoveryPassword
import app.ledger.core.security.RecoveryPasswordKeyWrapper
import app.ledger.core.security.RecoveryWrappedKeyMaterialCodec
import app.ledger.finance.domain.Hash256
import app.ledger.transfer.domain.BackupObjectKind
import app.ledger.transfer.domain.BackupRepositoryId
import app.ledger.transfer.domain.BackupSnapshotId
import app.ledger.transfer.domain.RestoreFailure
import app.ledger.transfer.domain.RestoreState
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

class RestoreMaterializerTest {
    @TempDir lateinit var temporary: File

    @Test
    fun `portable restore streams database settings attachment and key material with authenticated hashes`() {
        val fixture = portableFixture(12 * 1024 * 1024)
        val progress = mutableListOf<RestoreProgress>()
        val password = password(PASSWORD)
        val result = RestoreMaterializer().materialize(
            EncryptedRestoreSource.PortableFile { ByteArrayInputStream(fixture.container) },
            password,
            DirectoryRestoreTarget(temporary, id(99)),
            BOOK,
            progress = RestoreProgressObserver(progress::add),
        )
        password.close()
        fixture.key.close()

        assertTrue(result is DomainResult.Success, result.toString())
        val restored = (result as DomainResult.Success).value
        assertEquals(fixture.database.size.toLong() + fixture.settings.size + fixture.attachment.size + fixture.keys.size, restored.logicalBytes)
        assertArrayEquals(fixture.database, restored.targetDirectory.resolve("database/ledger.db").readBytes())
        assertArrayEquals(fixture.settings, restored.targetDirectory.resolve("settings/app.pb").readBytes())
        assertArrayEquals(fixture.attachment, restored.targetDirectory.resolve("attachments/receipt.object").readBytes())
        assertArrayEquals(fixture.keys, restored.targetDirectory.resolve("keys/portable-key-material.envelope").readBytes())
        assertTrue(progress.any { it.state == RestoreState.AUTHENTICATING_PASSWORD })
        assertEquals(restored.logicalBytes, progress.last().completedBytes)
    }

    @Test
    fun `wrong password corruption and cancellation remove all temporary plaintext`() {
        val fixture = portableFixture(2 * 1024 * 1024)
        val target = DirectoryRestoreTarget(temporary, id(98))
        val wrong = password("Wrong-password-P31")
        val wrongResult = RestoreMaterializer().materialize(
            EncryptedRestoreSource.PortableFile { ByteArrayInputStream(fixture.container) },
            wrong,
            target,
            BOOK,
        )
        wrong.close()
        assertEquals(RestoreFailure.WrongPassword, (wrongResult as DomainResult.Failure).error)
        assertFalse(target.directory.exists())

        val corruptBytes = fixture.container.copyOf().also { bytes -> bytes[bytes.lastIndex - 40] = (bytes[bytes.lastIndex - 40].toInt() xor 0x55).toByte() }
        val correct = password(PASSWORD)
        val corrupt = RestoreMaterializer().materialize(
            EncryptedRestoreSource.PortableFile { ByteArrayInputStream(corruptBytes) },
            correct,
            target,
            BOOK,
        )
        correct.close()
        assertEquals(RestoreFailure.CorruptObject, (corrupt as DomainResult.Failure).error)
        assertFalse(target.directory.exists())

        var checks = 0
        val cancelledPassword = password(PASSWORD)
        val cancelled = RestoreMaterializer().materialize(
            EncryptedRestoreSource.PortableFile { ByteArrayInputStream(fixture.container) },
            cancelledPassword,
            target,
            BOOK,
            cancelled = { ++checks > 4 },
        )
        cancelledPassword.close()
        fixture.key.close()
        assertEquals(RestoreFailure.Cancelled, (cancelled as DomainResult.Failure).error)
        assertFalse(target.directory.exists())
    }

    @Test
    fun `twenty gibibyte simulated restore stream completes with a fixed bounded buffer`() {
        val logicalBytes = 20L * 1024 * 1024 * 1024
        val input = ZeroInputStream(logicalBytes)
        val output = CountingOutputStream()
        val expected = Hash256.fromBytes(
            "6cb118a8f8b3c19385874297e291dcbcdf3a9837ba1ca7b00ace2491adbff551"
                .chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
        ).success()

        val copied = RestoreObjectStreamVerifier.copyAndVerify(input, output, logicalBytes, expected)

        assertEquals(logicalBytes, copied)
        assertEquals(logicalBytes, output.bytes)
        assertTrue(output.maximumWrite <= app.ledger.transfer.domain.BackupFormatContract.COPY_BUFFER_BYTES)
        assertTrue(logicalBytes > Int.MAX_VALUE)
    }

    private fun portableFixture(databaseSize: Int): Fixture {
        val key = LedgerTink.generateStreamingAeadKeyset()
        val password = password(PASSWORD)
        val envelope = RecoveryPasswordKeyWrapper(secureRandom = deterministicRandom()).wrap(
            password,
            key,
            Argon2idParameters.minimum(),
            recoveryAd(),
        ).let(RecoveryWrappedKeyMaterialCodec::encode)
        password.close()
        val database = ByteArray(databaseSize) { ((it * 31) xor 0x5a).toByte() }
        val settings = "设置-日本語-en-P31".repeat(1_000).toByteArray()
        val attachment = "附件-данные".repeat(2_000).toByteArray()
        val keys = ByteArray(96) { (it * 7).toByte() }
        val input = PortableBackupInput(
            BOOK,
            REPOSITORY,
            SNAPSHOT,
            envelope,
            database.source("database/ledger.db", BackupObjectKind.DATABASE_CHUNK),
            listOf(settings.source("settings/app.pb", BackupObjectKind.SETTINGS)),
            listOf(attachment.source("attachments/receipt.object", BackupObjectKind.ATTACHMENT)),
            keys.source("keys/portable-key-material.envelope", BackupObjectKind.KEY_ENVELOPE),
            null,
        )
        val output = ByteArrayOutputStream()
        assertTrue(PortableBackupWriter().write(input, key, output) is DomainResult.Success)
        return Fixture(output.toByteArray(), key, database, settings, attachment, keys)
    }

    private fun ByteArray.source(name: String, kind: BackupObjectKind): ReopenableBackupSource {
        val bytes = copyOf()
        return ReopenableBackupSource(
            name,
            kind,
            size.toLong(),
            Hash256.fromBytes(MessageDigest.getInstance("SHA-256").digest(bytes)).success(),
        ) { ByteArrayInputStream(bytes) }
    }

    private data class Fixture(
        val container: ByteArray,
        val key: app.ledger.core.security.SecretBytes,
        val database: ByteArray,
        val settings: ByteArray,
        val attachment: ByteArray,
        val keys: ByteArray,
    )

    private fun password(value: String) = RecoveryPassword.copyOf(value.toCharArray())
    private fun recoveryAd() = "ledger-backup-recovery-v1\u0000".toByteArray(Charsets.US_ASCII) + BOOK.bytes + REPOSITORY.value.bytes
    private fun deterministicRandom() = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(ByteArray(32) { it.toByte() }) }
    private fun id(value: Long) = StableId.fromUuid(UUID(31, value))
    private fun <T> DomainResult<T>.success(): T = (this as DomainResult.Success).value

    private class ZeroInputStream(private var remaining: Long) : InputStream() {
        override fun read(): Int = if (remaining-- > 0) 0 else -1
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining == 0L) return -1
            val count = minOf(remaining, length.toLong()).toInt()
            java.util.Arrays.fill(buffer, offset, offset + count, 0)
            remaining -= count
            return count
        }
    }

    private class CountingOutputStream : OutputStream() {
        var bytes = 0L
        var maximumWrite = 0
        override fun write(value: Int) {
            bytes = Math.addExact(bytes, 1L)
        }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            require(offset >= 0 && length >= 0 && offset + length <= buffer.size)
            bytes = Math.addExact(bytes, length.toLong())
            maximumWrite = maxOf(maximumWrite, length)
        }
    }

    private companion object {
        const val PASSWORD = "Correct-restore-password-P31"
        val BOOK = StableId.fromUuid(UUID(31, 1))
        val REPOSITORY = BackupRepositoryId(StableId.fromUuid(UUID(31, 2)))
        val SNAPSHOT = BackupSnapshotId(StableId.fromUuid(UUID(31, 3)))
    }
}
