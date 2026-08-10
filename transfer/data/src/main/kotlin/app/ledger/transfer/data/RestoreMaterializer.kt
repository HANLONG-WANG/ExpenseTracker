@file:Suppress(
    "LongMethod",
    "LongParameterList",
    "LoopWithTooManyJumpStatements",
    "MagicNumber",
    "NestedBlockDepth",
    "ReturnCount",
    "TooGenericExceptionCaught",
)

package app.ledger.transfer.data

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.security.LedgerTink
import app.ledger.core.security.RecoveryPassword
import app.ledger.core.security.RecoveryPasswordKeyWrapper
import app.ledger.core.security.RecoveryWrappedKeyMaterialCodec
import app.ledger.core.security.SecretBytes
import app.ledger.finance.domain.Hash256
import app.ledger.transfer.domain.BackupFormatContract
import app.ledger.transfer.domain.BackupManifestObject
import app.ledger.transfer.domain.BackupObjectKind
import app.ledger.transfer.domain.BackupRepositoryId
import app.ledger.transfer.domain.BackupSnapshotId
import app.ledger.transfer.domain.BackupSnapshotManifest
import app.ledger.transfer.domain.RestoreFailure
import app.ledger.transfer.domain.RestoreState
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

sealed interface EncryptedRestoreSource {
    data class ManagedRepository(
        val storage: BackupRepositoryStorage,
        val repositoryId: BackupRepositoryId,
        val snapshotId: BackupSnapshotId,
    ) : EncryptedRestoreSource

    data class PortableFile(val reopen: () -> InputStream) : EncryptedRestoreSource
}

data class RestoreProgress(
    val state: RestoreState,
    val completedBytes: Long,
    val totalBytes: Long?,
) {
    init {
        require(completedBytes >= 0L)
        require(totalBytes == null || completedBytes <= totalBytes)
    }
}

fun interface RestoreProgressObserver {
    fun onProgress(progress: RestoreProgress)
}

data class RestoreMaterializationResult(
    val bookId: StableId,
    val repositoryId: BackupRepositoryId,
    val snapshotId: BackupSnapshotId,
    val databaseSchemaVersion: Int?,
    val logicalBytes: Long,
    val restoredEntries: Long,
    val includesVault: Boolean,
    val targetDirectory: File,
)

/** A scoped temporary directory. Final ledger publication is deliberately owned by finance:data. */
class DirectoryRestoreTarget(
    root: File,
    operationId: StableId,
) {
    val directory: File = File(root.canonicalFile, "restore-${operationId.hex()}").canonicalFile
    private val allowedRoot = root.canonicalFile

    init {
        require(directory.parentFile == allowedRoot)
    }

    fun begin() {
        deleteRecursivelyScoped()
        require(directory.mkdirs())
    }

    fun appendDatabase(): OutputStream {
        val databaseDirectory = File(directory, "database")
        require(databaseDirectory.isDirectory || databaseDirectory.mkdirs())
        return FileOutputStream(File(databaseDirectory, "ledger.db"), true)
    }

    fun open(logicalName: String): OutputStream {
        require(SAFE_LOGICAL_NAME.matches(logicalName) && !logicalName.startsWith('/') && ".." !in logicalName.split('/'))
        val target = File(directory, logicalName).canonicalFile
        require(target.toPath().startsWith(directory.toPath()))
        requireNotNull(target.parentFile).let { require(it.isDirectory || it.mkdirs()) }
        return FileOutputStream(target, false)
    }

    fun abort() = deleteRecursivelyScoped()

    private fun deleteRecursivelyScoped() {
        if (!directory.exists()) return
        require(directory.parentFile == allowedRoot && directory.name.startsWith("restore-") && directory != allowedRoot)
        directory.walkBottomUp().forEach { file -> check(file.delete()) { "restore temporary cleanup failed" } }
    }

    private companion object {
        val SAFE_LOGICAL_NAME = Regex("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*")
    }
}

/** Streams authenticated objects/ZIP64 entries into a private restore directory and hashes as it writes. */
class RestoreMaterializer(
    private val passwordWrapper: RecoveryPasswordKeyWrapper = RecoveryPasswordKeyWrapper(),
) {
    fun materialize(
        source: EncryptedRestoreSource,
        password: RecoveryPassword,
        target: DirectoryRestoreTarget,
        expectedBookId: StableId? = null,
        cancelled: () -> Boolean = { false },
        progress: RestoreProgressObserver = RestoreProgressObserver { },
    ): DomainResult<RestoreMaterializationResult> = when (source) {
        is EncryptedRestoreSource.ManagedRepository -> materializeManaged(source, password, target, expectedBookId, cancelled, progress)
        is EncryptedRestoreSource.PortableFile -> materializePortable(source, password, target, expectedBookId, cancelled, progress)
    }

    private fun materializeManaged(
        source: EncryptedRestoreSource.ManagedRepository,
        password: RecoveryPassword,
        target: DirectoryRestoreTarget,
        expectedBookId: StableId?,
        cancelled: () -> Boolean,
        progress: RestoreProgressObserver,
    ): DomainResult<RestoreMaterializationResult> = try {
        progress.onProgress(RestoreProgress(RestoreState.READING_SOURCE, 0, null))
        val header = source.storage.open(BackupStorageArea.ROOT, "repository-header.header").use { input ->
            BackupManifestCodec.decodeHeader(input.readBounded(MAX_HEADER_BYTES))
        }
        if (header.repositoryId != source.repositoryId) return DomainResult.Failure(RestoreFailure.CorruptHeader)
        progress.onProgress(RestoreProgress(RestoreState.AUTHENTICATING_PASSWORD, 0, null))
        val associatedData = recoveryAssociatedData(expectedBookId, source.repositoryId)
        val key = try {
            passwordWrapper.unwrap(
                password,
                RecoveryWrappedKeyMaterialCodec.decode(header.recoveryKeyEnvelope),
                associatedData,
            )
        } finally {
            associatedData.fill(0)
        }
        key.use { repositoryKey ->
            val manifest = BackupRepositoryInspector().readManifest(
                source.storage,
                source.repositoryId,
                source.snapshotId,
                repositoryKey,
            )
            if (expectedBookId != null && manifest.bookId != expectedBookId) {
                return DomainResult.Failure(RestoreFailure.BookMismatch)
            }
            target.begin()
            try {
                val primitive = repositoryKey.useBytes(LedgerTink::streamingAead)
                var completed = 0L
                var entries = 0L
                manifest.objects.sortedBy(BackupManifestObject::ordinal).forEach { item ->
                    checkCancelled(cancelled)
                    val ad = objectAssociatedData(source.repositoryId, item.id)
                    try {
                        primitive.newDecryptingStream(
                            source.storage.open(BackupStorageArea.OBJECTS, item.storageName),
                            ad,
                        ).use { plaintext ->
                            val output = if (item.kind == BackupObjectKind.DATABASE_CHUNK) {
                                target.appendDatabase()
                            } else {
                                target.open(item.logicalName)
                            }
                            output.use { copyAndVerify(plaintext, it, item.plaintextSize, item.plaintextHash, cancelled) }
                        }
                    } finally {
                        ad.fill(0)
                    }
                    completed = Math.addExact(completed, item.plaintextSize)
                    entries = Math.addExact(entries, 1L)
                    progress.onProgress(RestoreProgress(RestoreState.VERIFYING_OBJECTS, completed, manifest.logicalBytes))
                }
                require(completed == manifest.logicalBytes)
                DomainResult.Success(manifest.toResult(completed, entries, target.directory))
            } catch (error: Exception) {
                target.abort()
                throw error
            }
        }
    } catch (_: app.ledger.core.security.SecurityException.RecoveryAuthenticationFailed) {
        DomainResult.Failure(RestoreFailure.WrongPassword)
    } catch (_: RestoreCancelledException) {
        target.abort()
        DomainResult.Failure(RestoreFailure.Cancelled)
    } catch (_: SecurityException) {
        target.abort()
        DomainResult.Failure(RestoreFailure.PermissionRevoked)
    } catch (error: IOException) {
        target.abort()
        DomainResult.Failure(if (error.isNoSpace()) RestoreFailure.InsufficientSpace else RestoreFailure.CorruptObject)
    } catch (_: Exception) {
        target.abort()
        DomainResult.Failure(RestoreFailure.CorruptObject)
    }

    private fun materializePortable(
        source: EncryptedRestoreSource.PortableFile,
        password: RecoveryPassword,
        target: DirectoryRestoreTarget,
        expectedBookId: StableId?,
        cancelled: () -> Boolean,
        progress: RestoreProgressObserver,
    ): DomainResult<RestoreMaterializationResult> = try {
        progress.onProgress(RestoreProgress(RestoreState.READING_SOURCE, 0, null))
        val header = source.reopen().use { PortableBackupVerifier(passwordWrapper).readHeader(it) }
        if (expectedBookId != null && header.bookId != expectedBookId) return DomainResult.Failure(RestoreFailure.BookMismatch)
        progress.onProgress(RestoreProgress(RestoreState.AUTHENTICATING_PASSWORD, 0, null))
        val recoveryAd = recoveryAssociatedData(header.bookId, header.repositoryId)
        val key = try {
            passwordWrapper.unwrap(
                password,
                RecoveryWrappedKeyMaterialCodec.decode(header.recoveryEnvelope),
                recoveryAd,
            )
        } finally {
            recoveryAd.fill(0)
        }
        key.use { repositoryKey ->
            source.reopen().use { raw ->
                val input = DataInputStream(BufferedInputStream(raw, BackupFormatContract.COPY_BUFFER_BYTES))
                skipPortableHeader(input)
                val portableAd = portableAssociatedData(header.bookId, header.repositoryId, header.snapshotId)
                try {
                    val primitive = repositoryKey.useBytes(LedgerTink::streamingAead)
                    primitive.newDecryptingStream(input, portableAd).use { decrypted ->
                        val zip = ZipArchiveInputStream(BufferedInputStream(decrypted, BackupFormatContract.COPY_BUFFER_BYTES))
                        val manifestEntry = zip.nextEntry ?: throw EOFException()
                        require(manifestEntry.name == PORTABLE_MANIFEST_ENTRY)
                        val expected = readPortableManifest(zip)
                        val total = expected.values.fold(0L) { sum, entry -> Math.addExact(sum, entry.size) }
                        target.begin()
                        try {
                            var completed = 0L
                            var count = 0L
                            while (true) {
                                checkCancelled(cancelled)
                                val entry = zip.nextEntry ?: break
                                val contract = expected.remove(entry.name) ?: error("unexpected portable entry")
                                target.open(entry.name).use { output ->
                                    copyAndVerify(zip, output, contract.size, contract.hash, cancelled)
                                }
                                completed = Math.addExact(completed, contract.size)
                                count = Math.addExact(count, 1L)
                                progress.onProgress(RestoreProgress(RestoreState.VERIFYING_OBJECTS, completed, total))
                            }
                            require(expected.isEmpty() && completed == total)
                            DomainResult.Success(
                                RestoreMaterializationResult(
                                    header.bookId,
                                    header.repositoryId,
                                    header.snapshotId,
                                    null,
                                    completed,
                                    count,
                                    File(target.directory, "keys/vault-recovery.envelope").isFile,
                                    target.directory,
                                ),
                            )
                        } catch (error: Exception) {
                            target.abort()
                            throw error
                        }
                    }
                } finally {
                    portableAd.fill(0)
                }
            }
        }
    } catch (_: app.ledger.core.security.SecurityException.RecoveryAuthenticationFailed) {
        DomainResult.Failure(RestoreFailure.WrongPassword)
    } catch (_: RestoreCancelledException) {
        target.abort()
        DomainResult.Failure(RestoreFailure.Cancelled)
    } catch (_: SecurityException) {
        target.abort()
        DomainResult.Failure(RestoreFailure.PermissionRevoked)
    } catch (error: IOException) {
        target.abort()
        DomainResult.Failure(if (error.isNoSpace()) RestoreFailure.InsufficientSpace else RestoreFailure.CorruptObject)
    } catch (_: Exception) {
        target.abort()
        DomainResult.Failure(RestoreFailure.CorruptObject)
    }

    private fun readPortableManifest(source: InputStream): MutableMap<String, ExpectedPortableEntry> {
        val input = DataInputStream(source)
        require(input.readInt() == PORTABLE_MANIFEST_MAGIC)
        require(input.readInt() == BackupFormatContract.PORTABLE_SCHEMA_VERSION)
        require(input.readBoolean() && input.readBoolean() && input.readBoolean())
        input.readBoolean()
        val count = input.readInt()
        require(count in 1..MAX_PORTABLE_ENTRIES)
        return LinkedHashMap<String, ExpectedPortableEntry>(count.coerceAtMost(16_384)).apply {
            repeat(count) {
                val name = input.readUTF()
                require(name !in this && SAFE_PORTABLE_ENTRY.matches(name) && ".." !in name.split('/'))
                BackupObjectKind.entries[input.readInt()]
                val size = input.readLong()
                require(size >= 0L)
                val hash = Hash256.fromBytes(ByteArray(Hash256.BYTE_COUNT).also(input::readFully)).required()
                put(name, ExpectedPortableEntry(size, hash))
            }
        }
    }

    private fun copyAndVerify(
        source: InputStream,
        target: OutputStream,
        expectedSize: Long,
        expectedHash: Hash256,
        cancelled: () -> Boolean,
    ) = RestoreObjectStreamVerifier.copyAndVerify(source, target, expectedSize, expectedHash) {
        checkCancelled(cancelled)
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw RestoreCancelledException()
    }

    private fun skipPortableHeader(input: DataInputStream) {
        require(ByteArray(PORTABLE_MAGIC.size).also(input::readFully).contentEquals(PORTABLE_MAGIC))
        require(input.readInt() == BackupFormatContract.PORTABLE_SCHEMA_VERSION)
        input.skipExactly((StableId.BYTE_COUNT * 3).toLong())
        val envelopeSize = input.readInt()
        require(envelopeSize in 1..MAX_ENVELOPE_BYTES)
        input.skipExactly(envelopeSize.toLong())
    }

    private data class ExpectedPortableEntry(val size: Long, val hash: Hash256)
    private class RestoreCancelledException : IOException()

    private companion object {
        const val MAX_HEADER_BYTES = 1024 * 1024 + 128
        const val MAX_ENVELOPE_BYTES = 1024 * 1024
        const val MAX_PORTABLE_ENTRIES = 2_000_000
        const val PORTABLE_MANIFEST_MAGIC = 0x4c504d46
        const val PORTABLE_MANIFEST_ENTRY = "manifest/manifest.bin"
        val PORTABLE_MAGIC = byteArrayOf(0x4c, 0x45, 0x44, 0x47, 0x45, 0x52, 0x42, 0x4b)
        val SAFE_PORTABLE_ENTRY = Regex("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*")
    }
}

/** Shared bounded copier so scale evidence exercises the exact production hash/size loop. */
internal object RestoreObjectStreamVerifier {
    fun copyAndVerify(
        source: InputStream,
        target: OutputStream,
        expectedSize: Long,
        expectedHash: Hash256,
        checkpoint: () -> Unit = {},
    ): Long {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BackupFormatContract.COPY_BUFFER_BYTES)
        var copied = 0L
        try {
            while (true) {
                checkpoint()
                val count = source.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                copied = Math.addExact(copied, count.toLong())
                require(copied <= expectedSize)
                digest.update(buffer, 0, count)
                target.write(buffer, 0, count)
            }
            require(copied == expectedSize && digest.digest().contentEquals(expectedHash.bytes))
            return copied
        } finally {
            buffer.fill(0)
        }
    }
}

private fun BackupSnapshotManifest.toResult(
    bytes: Long,
    entries: Long,
    target: File,
) = RestoreMaterializationResult(
    bookId,
    repositoryId,
    snapshotId,
    databaseSchemaVersion,
    bytes,
    entries,
    includesVault,
    target,
)

private fun recoveryAssociatedData(bookId: StableId?, repositoryId: BackupRepositoryId): ByteArray {
    requireNotNull(bookId) { "book identity is required before password authentication" }
    return "ledger-backup-recovery-v1\u0000".toByteArray(Charsets.US_ASCII) + bookId.bytes + repositoryId.value.bytes
}

private fun objectAssociatedData(
    repositoryId: BackupRepositoryId,
    objectId: app.ledger.transfer.domain.BackupObjectId,
): ByteArray = "ledger-backup-object-v1\u0000".toByteArray(Charsets.US_ASCII) +
    repositoryId.value.bytes + objectId.value.bytes

private fun portableAssociatedData(
    bookId: StableId,
    repositoryId: BackupRepositoryId,
    snapshotId: BackupSnapshotId,
): ByteArray = "ledger-portable-backup-v1\u0000".toByteArray(Charsets.US_ASCII) +
    bookId.bytes + repositoryId.value.bytes + snapshotId.value.bytes

private fun InputStream.readBounded(maximum: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(BackupFormatContract.COPY_BUFFER_BYTES)
    var total = 0
    try {
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total = Math.addExact(total, count)
            require(total <= maximum)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    } finally {
        buffer.fill(0)
    }
}

private fun InputStream.skipExactly(bytes: Long) {
    var remaining = bytes
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
        } else if (read() < 0) {
            throw EOFException()
        } else {
            remaining--
        }
    }
}

private fun IOException.isNoSpace(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current.message?.contains("space", ignoreCase = true) == true) return true
        current = current.cause
    }
    return false
}

private fun StableId.hex(): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
private fun <T> DomainResult<T>.required(): T = (this as DomainResult.Success).value
