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
import app.ledger.transfer.domain.BackupFailure
import app.ledger.transfer.domain.BackupObjectKind
import app.ledger.transfer.domain.BackupRepositoryId
import app.ledger.transfer.domain.BackupSnapshotId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

class PortableBackupContainerTest {
    @Test
    fun zip64StreamingAeadRoundTripPreservesNonAsciiAndNeverNeedsWholeSource() {
        val key = LedgerTink.generateStreamingAeadKeyset()
        val password = password("Strong-recovery-30")
        val envelope = RecoveryPasswordKeyWrapper(secureRandom = deterministicRandom()).wrap(
            password,
            key,
            Argon2idParameters.minimum(),
            recoveryAd(),
        ).let(RecoveryWrappedKeyMaterialCodec::encode)
        password.close()
        val database = generatedSource("database/ledger.db", BackupObjectKind.DATABASE_CHUNK, 9L * 1024 * 1024, 7)
        val receipt = "加密附件-日本語-данные".repeat(4_000).toByteArray()
        val input = PortableBackupInput(
            BOOK, REPOSITORY, SNAPSHOT, envelope, database,
            listOf("设置-v1".toByteArray().source("settings/app.pb", BackupObjectKind.SETTINGS)),
            listOf(receipt.source("attachments/receipt.object", BackupObjectKind.ATTACHMENT)),
            byteArrayOf(3, 1, 4, 1, 5).source("keys/material", BackupObjectKind.KEY_ENVELOPE),
            null,
        )
        val output = ByteArrayOutputStream()
        val written = PortableBackupWriter().write(input, key, output)
        assertTrue(written is DomainResult.Success)
        assertTrue(output.size() > 0)

        val verifyingPassword = password("Strong-recovery-30")
        val verified = PortableBackupVerifier().verify({ ByteArrayInputStream(output.toByteArray()) }, verifyingPassword)
        verifyingPassword.close()
        key.close()
        assertTrue(verified is DomainResult.Success)
        assertEquals(input.sources().sumOf(ReopenableBackupSource::size), (verified as DomainResult.Success).value.plaintextBytes)
    }

    @Test
    fun wrongPasswordCorruptionAndCancellationFailClosed() {
        val key = LedgerTink.generateStreamingAeadKeyset()
        val password = password("Correct-recovery-30")
        val envelope = RecoveryPasswordKeyWrapper(secureRandom = deterministicRandom()).wrap(
            password,
            key,
            Argon2idParameters.minimum(),
            recoveryAd(),
        ).let(RecoveryWrappedKeyMaterialCodec::encode)
        password.close()
        val input = PortableBackupInput(
            BOOK, REPOSITORY, SNAPSHOT, envelope,
            generatedSource("database/ledger.db", BackupObjectKind.DATABASE_CHUNK, 512 * 1024L, 11),
            emptyList(), emptyList(),
            byteArrayOf(1, 2, 3).source("keys/material", BackupObjectKind.KEY_ENVELOPE), null,
        )
        val output = ByteArrayOutputStream()
        assertTrue(PortableBackupWriter().write(input, key, output) is DomainResult.Success)
        key.close()

        val wrong = password("Wrong-recovery-300")
        assertEquals(
            BackupFailure.InvalidRecoveryPassword,
            (PortableBackupVerifier().verify({ ByteArrayInputStream(output.toByteArray()) }, wrong) as DomainResult.Failure).error,
        )
        wrong.close()
        val corrupt = output.toByteArray().also { it[it.lastIndex - 20] = (it[it.lastIndex - 20].toInt() xor 0x44).toByte() }
        val correct = password("Correct-recovery-30")
        assertEquals(
            BackupFailure.CorruptObject,
            (PortableBackupVerifier().verify({ ByteArrayInputStream(corrupt) }, correct) as DomainResult.Failure).error,
        )
        correct.close()
        val cancelled = password("Correct-recovery-30")
        assertEquals(
            BackupFailure.Cancelled,
            (PortableBackupVerifier().verify({ ByteArrayInputStream(output.toByteArray()) }, cancelled) { true } as DomainResult.Failure).error,
        )
        cancelled.close()
    }

    @Test
    fun storageExhaustionKeepsTypedInsufficientSpaceFailure() {
        val key = LedgerTink.generateStreamingAeadKeyset()
        val input = PortableBackupInput(
            BOOK,
            REPOSITORY,
            SNAPSHOT,
            byteArrayOf(1),
            byteArrayOf(1, 2, 3).source("database/ledger.db", BackupObjectKind.DATABASE_CHUNK),
            emptyList(),
            emptyList(),
            byteArrayOf(4).source("keys/material", BackupObjectKind.KEY_ENVELOPE),
            null,
        )
        try {
            val full = object : OutputStream() {
                override fun write(value: Int) = throw IOException("No space left on device")
                override fun write(value: ByteArray, offset: Int, length: Int) = throw IOException("No space left on device")
            }
            val result = PortableBackupWriter().write(input, key, full)
            assertEquals(BackupFailure.InsufficientSpace, (result as DomainResult.Failure).error)
        } finally {
            key.close()
        }
    }

    private fun generatedSource(name: String, kind: BackupObjectKind, size: Long, seed: Int): ReopenableBackupSource {
        val hash = MessageDigest.getInstance("SHA-256")
        GeneratedInputStream(size, seed).use { source -> source.copyTo(java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), hash)) }
        return ReopenableBackupSource(name, kind, size, Hash256.fromBytes(hash.digest()).portableSuccess()) {
            GeneratedInputStream(size, seed)
        }
    }

    private fun ByteArray.source(name: String, kind: BackupObjectKind): ReopenableBackupSource {
        val stored = copyOf()
        return ReopenableBackupSource(name, kind, size.toLong(), Hash256.fromBytes(MessageDigest.getInstance("SHA-256").digest(this)).portableSuccess()) {
            ByteArrayInputStream(stored)
        }
    }

    private fun password(value: String): RecoveryPassword = RecoveryPassword.copyOf(value.toCharArray())
    private fun recoveryAd() = "ledger-backup-recovery-v1\u0000".toByteArray(Charsets.US_ASCII) + BOOK.bytes + REPOSITORY.value.bytes
    private fun deterministicRandom() = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(ByteArray(32) { it.toByte() }) }

    private companion object {
        val BOOK: StableId = StableId.fromUuid(UUID(30, 10))
        val REPOSITORY = BackupRepositoryId(StableId.fromUuid(UUID(30, 11)))
        val SNAPSHOT = BackupSnapshotId(StableId.fromUuid(UUID(30, 12)))
    }
}

private class GeneratedInputStream(private val total: Long, private val seed: Int) : java.io.InputStream() {
    private var position = 0L
    override fun read(): Int = if (position >= total) -1 else (((position++ * 31L) + seed) and 0xff).toInt()
    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (position >= total) return -1
        val count = minOf(length.toLong(), total - position).toInt()
        repeat(count) { index -> target[offset + index] = (((position + index) * 31L + seed) and 0xff).toByte() }
        position += count
        return count
    }
}

private fun <T> DomainResult<T>.portableSuccess(): T = (this as DomainResult.Success).value
