@file:Suppress(
    "LongMethod",
    "LoopWithTooManyJumpStatements",
    "MagicNumber",
    "MaxLineLength",
    "NestedBlockDepth",
    "ReturnCount",
    "TooGenericExceptionCaught",
)

package app.ledger.transfer.data

import android.system.ErrnoException
import android.system.OsConstants
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.security.LedgerTink
import app.ledger.core.security.RecoveryPassword
import app.ledger.core.security.RecoveryPasswordKeyWrapper
import app.ledger.core.security.RecoveryWrappedKeyMaterialCodec
import app.ledger.core.security.SecretBytes
import app.ledger.finance.domain.Hash256
import app.ledger.transfer.domain.BackupFailure
import app.ledger.transfer.domain.BackupFormatContract
import app.ledger.transfer.domain.BackupObjectKind
import app.ledger.transfer.domain.BackupPhase
import app.ledger.transfer.domain.BackupProgress
import app.ledger.transfer.domain.BackupRepositoryId
import app.ledger.transfer.domain.BackupSnapshotId
import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

data class PortableBackupInput(
    val bookId: StableId,
    val repositoryId: BackupRepositoryId,
    val snapshotId: BackupSnapshotId,
    val recoveryEnvelope: ByteArray,
    val database: ReopenableBackupSource,
    val settings: List<ReopenableBackupSource>,
    val attachments: List<ReopenableBackupSource>,
    val portableKeyMaterial: ReopenableBackupSource,
    val vaultRecoveryEnvelope: ReopenableBackupSource?,
) {
    init {
        require(database.kind == BackupObjectKind.DATABASE_CHUNK)
        require(recoveryEnvelope.isNotEmpty())
        require(settings.all { it.kind == BackupObjectKind.SETTINGS })
        require(attachments.all { it.kind == BackupObjectKind.ATTACHMENT })
        require(portableKeyMaterial.kind == BackupObjectKind.KEY_ENVELOPE)
        require(vaultRecoveryEnvelope == null || vaultRecoveryEnvelope.kind == BackupObjectKind.VAULT_ENVELOPE)
    }

    fun sources(): List<ReopenableBackupSource> = listOf(database) + settings + attachments + portableKeyMaterial + listOfNotNull(vaultRecoveryEnvelope)
}

data class PortableBackupResult(val entries: Long, val plaintextBytes: Long)

data class PortableBackupHeader(
    val version: Int,
    val bookId: StableId,
    val repositoryId: BackupRepositoryId,
    val snapshotId: BackupSnapshotId,
    val recoveryEnvelope: ByteArray,
)

/** ZIP64 archive under one Tink Streaming AEAD stream. Only the version/key envelope header is outside the ciphertext. */
class PortableBackupWriter {
    fun write(
        input: PortableBackupInput,
        repositoryKey: SecretBytes,
        destination: OutputStream,
        cancelled: () -> Boolean = { false },
        progress: (BackupProgress) -> Unit = {},
    ): DomainResult<PortableBackupResult> = try {
        val totalBytes = input.sources().fold(0L) { total, source -> Math.addExact(total, source.size) }
        progress(BackupProgress(BackupPhase.DATABASE_SNAPSHOT, 0L, totalBytes, 0L))
        val data = DataOutputStream(BufferedOutputStream(destination, BackupFormatContract.COPY_BUFFER_BYTES))
        data.write(PORTABLE_MAGIC)
        data.writeInt(BackupFormatContract.PORTABLE_SCHEMA_VERSION)
        data.write(input.bookId.bytes)
        data.write(input.repositoryId.value.bytes)
        data.write(input.snapshotId.value.bytes)
        data.writeInt(input.recoveryEnvelope.size)
        data.write(input.recoveryEnvelope)
        data.flush()
        val primitive = repositoryKey.useBytes(LedgerTink::streamingAead)
        val associatedData = portableAssociatedData(input.bookId, input.repositoryId, input.snapshotId)
        try {
            primitive.newEncryptingStream(data, associatedData).use { encrypted ->
                ZipArchiveOutputStream(BufferedOutputStream(encrypted, BackupFormatContract.COPY_BUFFER_BYTES)).use { zip ->
                    zip.setUseZip64(Zip64Mode.Always)
                    writeManifest(zip, input)
                    var completedBytes = 0L
                    input.sources().forEachIndexed { index, source ->
                        checkCancelled(cancelled)
                        val name = entryName(index, source)
                        val entry = ZipArchiveEntry(name).apply { size = source.size }
                        zip.putArchiveEntry(entry)
                        source.open().use { opened -> copyExactly(opened, zip, source.size, cancelled) }
                        zip.closeArchiveEntry()
                        completedBytes = Math.addExact(completedBytes, source.size)
                        progress(
                            BackupProgress(
                                if (completedBytes == totalBytes) BackupPhase.VERIFYING else BackupPhase.WRITING_OR_UPLOADING,
                                completedBytes,
                                totalBytes,
                                index.toLong() + 1L,
                            ),
                        )
                    }
                    zip.finish()
                }
            }
        } finally {
            associatedData.fill(0)
        }
        DomainResult.Success(
            PortableBackupResult(
                Math.addExact(input.sources().size.toLong(), 1L),
                input.sources().fold(0L) { total, source -> Math.addExact(total, source.size) },
            ),
        )
    } catch (_: PortableCancelledException) {
        DomainResult.Failure(BackupFailure.Cancelled)
    } catch (error: IOException) {
        DomainResult.Failure(if (error.isPortableStorageFull()) BackupFailure.InsufficientSpace else BackupFailure.RepositoryUnavailable)
    } catch (_: Exception) {
        DomainResult.Failure(BackupFailure.CorruptObject)
    }

    private fun writeManifest(zip: ZipArchiveOutputStream, input: PortableBackupInput) {
        zip.putArchiveEntry(ZipArchiveEntry(MANIFEST_ENTRY))
        val output = DataOutputStream(zip)
        output.writeInt(MANIFEST_MAGIC)
        output.writeInt(BackupFormatContract.PORTABLE_SCHEMA_VERSION)
        output.writeBoolean(true)
        output.writeBoolean(true)
        output.writeBoolean(true)
        output.writeBoolean(input.vaultRecoveryEnvelope != null)
        val sources = input.sources()
        output.writeInt(sources.size)
        sources.forEachIndexed { index, source ->
            output.writeUTF(entryName(index, source))
            output.writeInt(source.kind.ordinal)
            output.writeLong(source.size)
            output.write(source.hash.bytes)
        }
        output.flush()
        zip.closeArchiveEntry()
    }

    private fun entryName(index: Int, source: ReopenableBackupSource): String = when (source.kind) {
        BackupObjectKind.DATABASE_CHUNK -> "database/ledger.db"
        BackupObjectKind.ATTACHMENT -> "attachments/${index.toString().padStart(8, '0')}.object"
        BackupObjectKind.SETTINGS -> "settings/${index.toString().padStart(8, '0')}.settings"
        BackupObjectKind.KEY_ENVELOPE -> "keys/portable-key-material.envelope"
        BackupObjectKind.VAULT_ENVELOPE -> "keys/vault-recovery.envelope"
    }

    private fun copyExactly(source: InputStream, output: OutputStream, expected: Long, cancelled: () -> Boolean) {
        val buffer = ByteArray(BackupFormatContract.COPY_BUFFER_BYTES)
        var copied = 0L
        try {
            while (true) {
                checkCancelled(cancelled)
                val count = source.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                output.write(buffer, 0, count)
                copied = Math.addExact(copied, count.toLong())
                require(copied <= expected)
            }
            require(copied == expected)
        } finally {
            buffer.fill(0)
        }
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw PortableCancelledException()
    }

    private class PortableCancelledException : IOException()
}

/** Streaming verifier used before a portable file is reported as successful or offered for restore. */
class PortableBackupVerifier(private val wrapper: RecoveryPasswordKeyWrapper = RecoveryPasswordKeyWrapper()) {
    fun readHeader(source: InputStream): PortableBackupHeader {
        val input = DataInputStream(BufferedInputStream(source, BackupFormatContract.COPY_BUFFER_BYTES))
        val magic = ByteArray(PORTABLE_MAGIC.size).also(input::readFully)
        require(magic.contentEquals(PORTABLE_MAGIC))
        val version = input.readInt()
        require(version == BackupFormatContract.PORTABLE_SCHEMA_VERSION)
        val bookId = input.id()
        val repositoryId = BackupRepositoryId(input.id())
        val snapshotId = BackupSnapshotId(input.id())
        val envelopeSize = input.readInt()
        require(envelopeSize in 1..MAX_ENVELOPE_BYTES)
        return PortableBackupHeader(version, bookId, repositoryId, snapshotId, ByteArray(envelopeSize).also(input::readFully))
    }

    fun verify(
        reopen: () -> InputStream,
        password: RecoveryPassword,
        cancelled: () -> Boolean = { false },
    ): DomainResult<PortableBackupResult> = try {
        val header = reopen().use(::readHeader)
        val recoveryAd = recoveryAssociatedData(header.bookId, header.repositoryId)
        val repositoryKey = try {
            wrapper.unwrap(password, RecoveryWrappedKeyMaterialCodec.decode(header.recoveryEnvelope), recoveryAd)
        } finally {
            recoveryAd.fill(0)
        }
        repositoryKey.use { key ->
            reopen().use { raw ->
                val input = DataInputStream(BufferedInputStream(raw, BackupFormatContract.COPY_BUFFER_BYTES))
                skipHeader(input)
                val primitive = key.useBytes(LedgerTink::streamingAead)
                val portableAd = portableAssociatedData(header.bookId, header.repositoryId, header.snapshotId)
                try {
                    primitive.newDecryptingStream(input, portableAd).use { decrypted -> verifyZip(decrypted, cancelled) }
                } finally {
                    portableAd.fill(0)
                }
            }
        }
    } catch (_: app.ledger.core.security.SecurityException.RecoveryAuthenticationFailed) {
        DomainResult.Failure(BackupFailure.InvalidRecoveryPassword)
    } catch (_: Exception) {
        DomainResult.Failure(BackupFailure.CorruptObject)
    }

    private fun verifyZip(source: InputStream, cancelled: () -> Boolean): DomainResult<PortableBackupResult> {
        val zip = ZipArchiveInputStream(BufferedInputStream(source, BackupFormatContract.COPY_BUFFER_BYTES))
        val expected = linkedMapOf<String, ExpectedEntry>()
        var entries = 0L
        var bytes = 0L
        while (true) {
            if (cancelled()) return DomainResult.Failure(BackupFailure.Cancelled)
            val entry: ZipArchiveEntry = zip.nextEntry ?: break
            entries = Math.addExact(entries, 1L)
            if (entry.name == MANIFEST_ENTRY) {
                readManifest(zip, expected)
            } else {
                val contract = expected.remove(entry.name) ?: return DomainResult.Failure(BackupFailure.CorruptManifest)
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                val buffer = ByteArray(BackupFormatContract.COPY_BUFFER_BYTES)
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    digest.update(buffer, 0, count)
                    size = Math.addExact(size, count.toLong())
                }
                buffer.fill(0)
                if (size != contract.size || !digest.digest().contentEquals(contract.hash.bytes)) {
                    return DomainResult.Failure(BackupFailure.CorruptObject)
                }
                bytes = Math.addExact(bytes, size)
            }
        }
        return if (expected.isEmpty() && entries > 1L) {
            DomainResult.Success(PortableBackupResult(entries, bytes))
        } else {
            DomainResult.Failure(BackupFailure.CorruptManifest)
        }
    }

    private fun readManifest(input: InputStream, expected: MutableMap<String, ExpectedEntry>) {
        val data = DataInputStream(input)
        require(data.readInt() == MANIFEST_MAGIC)
        require(data.readInt() == BackupFormatContract.PORTABLE_SCHEMA_VERSION)
        require(data.readBoolean() && data.readBoolean() && data.readBoolean())
        data.readBoolean()
        val count = data.readInt()
        require(count in 1..MAX_ENTRIES)
        repeat(count) {
            val name = data.readUTF()
            require(name !in expected)
            BackupObjectKind.entries[data.readInt()]
            val size = data.readLong()
            require(size >= 0L)
            val hash = Hash256.fromBytes(ByteArray(Hash256.BYTE_COUNT).also(data::readFully)).required()
            expected[name] = ExpectedEntry(size, hash)
        }
    }

    private fun skipHeader(input: DataInputStream) {
        val magic = ByteArray(PORTABLE_MAGIC.size).also(input::readFully)
        require(magic.contentEquals(PORTABLE_MAGIC))
        require(input.readInt() == BackupFormatContract.PORTABLE_SCHEMA_VERSION)
        input.skipExactly((StableId.BYTE_COUNT * 3).toLong())
        val envelopeSize = input.readInt()
        require(envelopeSize in 1..MAX_ENVELOPE_BYTES)
        input.skipExactly(envelopeSize.toLong())
    }

    private fun DataInputStream.id(): StableId = StableId.fromBytes(ByteArray(StableId.BYTE_COUNT).also(::readFully)).required()

    private fun InputStream.skipExactly(bytes: Long) {
        var remaining = bytes
        while (remaining > 0L) {
            val skipped = skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else if (read() < 0) {
                throw EOFException()
            } else {
                remaining--
            }
        }
    }

    private data class ExpectedEntry(val size: Long, val hash: Hash256)
}

private val PORTABLE_MAGIC = byteArrayOf(0x4c, 0x45, 0x44, 0x47, 0x45, 0x52, 0x42, 0x4b)
private const val MANIFEST_ENTRY = "manifest/manifest.bin"
private const val MANIFEST_MAGIC = 0x4c504d46
private const val MAX_ENVELOPE_BYTES = 1024 * 1024
private const val MAX_ENTRIES = 2_000_000

private fun portableAssociatedData(bookId: StableId, repositoryId: BackupRepositoryId, snapshotId: BackupSnapshotId): ByteArray = "ledger-portable-backup-v1\u0000".toByteArray(Charsets.US_ASCII) + bookId.bytes + repositoryId.value.bytes + snapshotId.value.bytes

private fun recoveryAssociatedData(bookId: StableId, repositoryId: BackupRepositoryId): ByteArray = "ledger-backup-recovery-v1\u0000".toByteArray(Charsets.US_ASCII) + bookId.bytes + repositoryId.value.bytes

private fun IOException.isPortableStorageFull(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is ErrnoException && current.errno == OsConstants.ENOSPC) return true
        current = current.cause
    }
    return message?.contains("space", ignoreCase = true) == true
}

private fun <T> DomainResult<T>.required(): T = (this as? DomainResult.Success)?.value ?: error("invalid portable backup value")
