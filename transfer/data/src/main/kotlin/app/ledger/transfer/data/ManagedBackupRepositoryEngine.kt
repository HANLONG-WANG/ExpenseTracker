@file:Suppress(
    "LongMethod",
    "LongParameterList",
    "MagicNumber",
    "MaxLineLength",
    "NestedBlockDepth",
    "LoopWithTooManyJumpStatements",
    "MayBeConst",
    "ReturnCount",
    "SwallowedException",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
)

package app.ledger.transfer.data

import android.system.ErrnoException
import android.system.OsConstants
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.common.StableIdSource
import app.ledger.core.security.LedgerTink
import app.ledger.core.security.SecretBytes
import app.ledger.finance.domain.BookCommitId
import app.ledger.finance.domain.Hash256
import app.ledger.finance.domain.LocalRevision
import app.ledger.transfer.domain.BackupCreationResult
import app.ledger.transfer.domain.BackupFailure
import app.ledger.transfer.domain.BackupFormatContract
import app.ledger.transfer.domain.BackupManifestObject
import app.ledger.transfer.domain.BackupObject
import app.ledger.transfer.domain.BackupObjectId
import app.ledger.transfer.domain.BackupObjectKind
import app.ledger.transfer.domain.BackupPhase
import app.ledger.transfer.domain.BackupProgress
import app.ledger.transfer.domain.BackupProgressObserver
import app.ledger.transfer.domain.BackupRepositoryHeader
import app.ledger.transfer.domain.BackupRepositoryId
import app.ledger.transfer.domain.BackupRepositoryKind
import app.ledger.transfer.domain.BackupRetentionPolicy
import app.ledger.transfer.domain.BackupSnapshot
import app.ledger.transfer.domain.BackupSnapshotId
import app.ledger.transfer.domain.BackupSnapshotManifest
import app.ledger.transfer.domain.BackupSnapshotState
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant

data class ReopenableBackupSource(
    val logicalName: String,
    val kind: BackupObjectKind,
    val size: Long,
    val hash: Hash256,
    val open: () -> InputStream,
) {
    init {
        require(logicalName.matches(Regex("[A-Za-z0-9_./-]{1,180}")))
        require(size >= 0L)
    }
}

data class ManagedBackupInput(
    val bookId: StableId,
    val repositoryId: BackupRepositoryId,
    val repositoryKind: BackupRepositoryKind,
    val repositoryHandleId: StableId,
    val snapshotId: BackupSnapshotId,
    val headCommitId: BookCommitId,
    val localRevision: LocalRevision,
    val createdAt: Instant,
    val applicationVersion: String,
    val databaseSchemaVersion: Int,
    val databaseSnapshot: ReopenableBackupSource,
    val settings: List<ReopenableBackupSource>,
    val attachments: List<ReopenableBackupSource>,
    val portableKeyMaterial: ReopenableBackupSource,
    val vaultRecoveryEnvelope: ReopenableBackupSource?,
) {
    init {
        require(databaseSnapshot.kind == BackupObjectKind.DATABASE_CHUNK)
        require(settings.all { it.kind == BackupObjectKind.SETTINGS })
        require(attachments.all { it.kind == BackupObjectKind.ATTACHMENT })
        require(portableKeyMaterial.kind == BackupObjectKind.KEY_ENVELOPE)
        require(vaultRecoveryEnvelope == null || vaultRecoveryEnvelope.kind == BackupObjectKind.VAULT_ENVELOPE)
        require(applicationVersion.isNotBlank())
        require(databaseSchemaVersion > 0)
    }
}

class ManagedBackupRepositoryEngine(private val stableIds: StableIdSource) {
    suspend fun create(
        input: ManagedBackupInput,
        storage: BackupRepositoryStorage,
        catalog: BackupCatalogPort,
        repositoryKey: SecretBytes,
        recoveryEnvelope: ByteArray,
        retention: BackupRetentionPolicy,
        cancelled: () -> Boolean = { false },
        progress: BackupProgressObserver = BackupProgressObserver { },
    ): DomainResult<BackupCreationResult> {
        var publishedResult: BackupCreationResult? = null
        try {
            checkCancelled(cancelled)
            catalog.ensureRepository(input.repositoryId, input.repositoryKind, input.repositoryHandleId, input.createdAt)
            val existingHeader = if (storage.exists(BackupStorageArea.ROOT, REPOSITORY_HEADER)) {
                storage.open(BackupStorageArea.ROOT, REPOSITORY_HEADER).use { source ->
                    BackupManifestCodec.decodeHeader(source.readBytesBounded(MAX_ENVELOPE_BYTES + 128))
                }.also { require(it.repositoryId == input.repositoryId) }
            } else {
                null
            }
            val header = BackupRepositoryHeader(
                input.repositoryId,
                BackupFormatContract.REPOSITORY_SCHEMA_VERSION,
                existingHeader?.createdAt ?: input.createdAt,
                recoveryEnvelope.copyOf(),
            )
            if (existingHeader == null || !existingHeader.recoveryKeyEnvelope.contentEquals(recoveryEnvelope)) {
                storage.writeAtomically(BackupStorageArea.ROOT, REPOSITORY_HEADER) { output ->
                    output.write(BackupManifestCodec.encodeHeader(header))
                }
            }
            val initialSnapshot = BackupSnapshot(
                input.snapshotId,
                input.repositoryId,
                input.headCommitId,
                input.localRevision,
                input.createdAt,
                BackupSnapshotState.PREPARING,
                null,
                emptyList(),
            )
            progress.onProgress(BackupProgress(BackupPhase.DATABASE_SNAPSHOT, 0L, input.totalLogicalBytes(), 0L))

            val primitive = repositoryKey.useBytes(LedgerTink::streamingAead)
            val objects = mutableListOf<BackupManifestObject>()
            var logicalBytes = 0L
            var physicalBytes = 0L
            var ordinal = 0L

            input.databaseSnapshot.open().use { database ->
                var chunkIndex = 0L
                while (true) {
                    checkCancelled(cancelled)
                    val bytes = database.readChunk(BackupFormatContract.DATABASE_CHUNK_BYTES)
                    if (bytes.isEmpty()) break
                    val hash = bytes.sha256()
                    val source = ReopenableBackupSource(
                        "database/chunk-${chunkIndex.toString().padStart(8, '0')}",
                        BackupObjectKind.DATABASE_CHUNK,
                        bytes.size.toLong(),
                        hash,
                    ) { ByteArrayInputStream(bytes) }
                    val stored = storeSource(input, source, ordinal, storage, catalog, primitive, cancelled)
                    objects += stored.manifest
                    logicalBytes = Math.addExact(logicalBytes, source.size)
                    physicalBytes = Math.addExact(physicalBytes, stored.physicalIncrement)
                    ordinal = Math.addExact(ordinal, 1L)
                    chunkIndex = Math.addExact(chunkIndex, 1L)
                    progress.onProgress(BackupProgress(BackupPhase.OBJECT_PROCESSING, logicalBytes, input.totalLogicalBytes(), ordinal))
                    bytes.fill(0)
                }
            }
            require(logicalBytes == input.databaseSnapshot.size) { "database snapshot changed while reading" }

            val remaining = input.settings + input.attachments + input.portableKeyMaterial + listOfNotNull(input.vaultRecoveryEnvelope)
            for (source in remaining) {
                checkCancelled(cancelled)
                val stored = storeSource(input, source, ordinal, storage, catalog, primitive, cancelled)
                objects += stored.manifest
                logicalBytes = Math.addExact(logicalBytes, source.size)
                physicalBytes = Math.addExact(physicalBytes, stored.physicalIncrement)
                ordinal = Math.addExact(ordinal, 1L)
                progress.onProgress(BackupProgress(BackupPhase.WRITING_OR_UPLOADING, logicalBytes, input.totalLogicalBytes(), ordinal))
            }

            val manifest = BackupSnapshotManifest(
                BackupFormatContract.MANIFEST_SCHEMA_VERSION,
                input.snapshotId,
                input.repositoryId,
                input.bookId,
                input.localRevision,
                input.createdAt,
                input.applicationVersion,
                input.databaseSchemaVersion,
                includesSettings = true,
                includesAttachments = true,
                includesHistory = true,
                includesVault = input.vaultRecoveryEnvelope != null,
                logicalBytes = logicalBytes,
                physicalIncrementBytes = physicalBytes,
                objects = objects,
            )
            progress.onProgress(BackupProgress(BackupPhase.VERIFYING, logicalBytes, logicalBytes, ordinal))
            objects.forEach { verifyObject(input.repositoryId, it, storage, primitive, cancelled) }

            val manifestBytes = BackupManifestCodec.encode(manifest)
            val manifestHash = manifestBytes.sha256()
            val manifestName = input.snapshotId.manifestName()
            progress.onProgress(BackupProgress(BackupPhase.PUBLISHING_MANIFEST, logicalBytes, logicalBytes, ordinal))
            storage.writeAtomically(BackupStorageArea.SNAPSHOTS, manifestName) { output ->
                output.write(MANIFEST_CONTAINER_MAGIC)
                output.writeInt(recoveryEnvelope.size)
                output.write(recoveryEnvelope)
                primitive.newEncryptingStream(output, manifestAssociatedData(input.repositoryId, input.snapshotId)).use { encrypted ->
                    encrypted.write(manifestBytes)
                }
            }
            val verifiedManifest = readManifest(storage, input.repositoryId, input.snapshotId, primitive)
            check(verifiedManifest == manifest) { "published manifest verification mismatch" }
            catalog.publishSnapshot(initialSnapshot, manifestHash, objects.map(BackupManifestObject::id))
            manifestBytes.fill(0)
            publishedResult = BackupCreationResult(input.snapshotId, manifestHash, logicalBytes, physicalBytes, ordinal, input.createdAt)

            runCatching { progress.onProgress(BackupProgress(BackupPhase.RETENTION, logicalBytes, logicalBytes, ordinal)) }
            runCatching { enforceRetention(input, storage, catalog, retention) }
            runCatching { collectInterruptedObjects(input.repositoryId, storage, catalog) }
            runCatching { progress.onProgress(BackupProgress(BackupPhase.COMPLETE, logicalBytes, logicalBytes, ordinal)) }
            return DomainResult.Success(requireNotNull(publishedResult))
        } catch (cancelledError: CancellationException) {
            publishedResult?.let { return DomainResult.Success(it) }
            abandon(input, storage, catalog)
            throw cancelledError
        } catch (_: BackupCancelledException) {
            publishedResult?.let { return DomainResult.Success(it) }
            abandon(input, storage, catalog)
            return DomainResult.Failure(BackupFailure.Cancelled)
        } catch (error: SecurityException) {
            publishedResult?.let { return DomainResult.Success(it) }
            abandon(input, storage, catalog)
            return DomainResult.Failure(BackupFailure.PermissionRevoked)
        } catch (error: IOException) {
            publishedResult?.let { return DomainResult.Success(it) }
            abandon(input, storage, catalog)
            return DomainResult.Failure(if (error.isStorageFull()) BackupFailure.InsufficientSpace else BackupFailure.RepositoryUnavailable)
        } catch (_: Exception) {
            publishedResult?.let { return DomainResult.Success(it) }
            abandon(input, storage, catalog)
            return DomainResult.Failure(BackupFailure.CorruptObject)
        }
    }

    private fun storeSource(
        input: ManagedBackupInput,
        source: ReopenableBackupSource,
        ordinal: Long,
        storage: BackupRepositoryStorage,
        catalog: BackupCatalogPort,
        primitive: com.google.crypto.tink.StreamingAead,
        cancelled: () -> Boolean,
    ): StoredSource {
        val existing = catalog.findObject(input.repositoryId, source.hash, source.size, source.kind)
        var wroteObject = false
        val selected = if (existing != null && storage.exists(BackupStorageArea.OBJECTS, existing.storageName)) {
            verifyExistingSource(input.repositoryId, existing, source, storage, primitive, cancelled)
            existing
        } else {
            val requested = BackupObject(
                BackupObjectId(stableIds.nextStableId()),
                input.repositoryId,
                source.hash,
                source.size,
                source.kind,
                input.createdAt,
            )
            val recorded = catalog.recordObject(requested)
            val associatedData = objectAssociatedData(input.repositoryId, recorded.value.id)
            try {
                wroteObject = true
                storage.writeAtomically(BackupStorageArea.OBJECTS, recorded.storageName) { output ->
                    primitive.newEncryptingStream(output, associatedData).use { encrypted ->
                        source.open().use { opened -> copyExactly(opened, encrypted, source.size, cancelled) }
                    }
                }
            } finally {
                associatedData.fill(0)
            }
            verifyExistingSource(input.repositoryId, recorded, source, storage, primitive, cancelled)
            recorded
        }
        return StoredSource(
            BackupManifestObject(
                selected.value.id,
                source.kind,
                selected.storageName,
                source.logicalName,
                source.hash,
                source.size,
                ordinal,
            ),
            if (wroteObject) storage.size(BackupStorageArea.OBJECTS, selected.storageName) ?: 0L else 0L,
        )
    }

    private fun verifyExistingSource(
        repositoryId: BackupRepositoryId,
        stored: BackupCatalogObject,
        source: ReopenableBackupSource,
        storage: BackupRepositoryStorage,
        primitive: com.google.crypto.tink.StreamingAead,
        cancelled: () -> Boolean,
    ) {
        val associatedData = objectAssociatedData(repositoryId, stored.value.id)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            primitive.newDecryptingStream(storage.open(BackupStorageArea.OBJECTS, stored.storageName), associatedData).use { plaintext ->
                val buffer = ByteArray(BackupFormatContract.COPY_BUFFER_BYTES)
                while (true) {
                    checkCancelled(cancelled)
                    val count = plaintext.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    digest.update(buffer, 0, count)
                    size = Math.addExact(size, count.toLong())
                }
                buffer.fill(0)
            }
            val hash = Hash256.fromBytes(digest.digest()).required()
            require(size == source.size && hash == source.hash) { "backup object checksum mismatch" }
        } finally {
            associatedData.fill(0)
        }
    }

    private fun verifyObject(
        repositoryId: BackupRepositoryId,
        value: BackupManifestObject,
        storage: BackupRepositoryStorage,
        primitive: com.google.crypto.tink.StreamingAead,
        cancelled: () -> Boolean,
    ) = verifyExistingSource(
        repositoryId,
        BackupCatalogObject(
            BackupObject(value.id, repositoryId, value.plaintextHash, value.plaintextSize, value.kind, Instant.EPOCH),
            value.storageName,
        ),
        ReopenableBackupSource(value.logicalName, value.kind, value.plaintextSize, value.plaintextHash) { ByteArrayInputStream(ByteArray(0)) },
        storage,
        primitive,
        cancelled,
    )

    private fun readManifest(
        storage: BackupRepositoryStorage,
        repositoryId: BackupRepositoryId,
        snapshotId: BackupSnapshotId,
        primitive: com.google.crypto.tink.StreamingAead,
    ): BackupSnapshotManifest = storage.open(BackupStorageArea.SNAPSHOTS, snapshotId.manifestName()).use { source ->
        require(source.readExact(MANIFEST_CONTAINER_MAGIC.size).contentEquals(MANIFEST_CONTAINER_MAGIC))
        val envelopeSize = source.readInt()
        require(envelopeSize in 1..MAX_ENVELOPE_BYTES)
        source.readExact(envelopeSize).fill(0)
        primitive.newDecryptingStream(source, manifestAssociatedData(repositoryId, snapshotId)).use { encrypted ->
            BackupManifestCodec.decode(encrypted.readBytesBounded(MAX_MANIFEST_BYTES))
        }
    }

    private fun enforceRetention(
        input: ManagedBackupInput,
        storage: BackupRepositoryStorage,
        catalog: BackupCatalogPort,
        policy: BackupRetentionPolicy,
    ) {
        val complete = catalog.completeSnapshots(input.repositoryId)
        val cutoff = policy.maximumAgeDays?.let { input.createdAt.minusSeconds(Math.multiplyExact(it.toLong(), SECONDS_PER_DAY)) }
        val delete = complete.filterIndexed { index, snapshot -> index >= policy.maximumSnapshots || cutoff?.let { snapshot.createdAt < it } == true }
        delete.forEach { snapshot ->
            check(storage.delete(BackupStorageArea.SNAPSHOTS, snapshot.id.manifestName()))
            catalog.deleteSnapshot(snapshot.id).forEach { storageName ->
                if (catalog.deleteUnreferencedObject(input.repositoryId, storageName)) {
                    check(storage.delete(BackupStorageArea.OBJECTS, storageName))
                }
            }
        }
    }

    private fun abandon(input: ManagedBackupInput, storage: BackupRepositoryStorage, catalog: BackupCatalogPort) {
        runCatching { storage.delete(BackupStorageArea.SNAPSHOTS, input.snapshotId.manifestName()) }
        runCatching { collectInterruptedObjects(input.repositoryId, storage, catalog) }
    }

    private fun collectInterruptedObjects(
        repositoryId: BackupRepositoryId,
        storage: BackupRepositoryStorage,
        catalog: BackupCatalogPort,
    ) {
        catalog.unreferencedObjects(repositoryId).forEach { storageName ->
            if (catalog.deleteUnreferencedObject(repositoryId, storageName)) storage.delete(BackupStorageArea.OBJECTS, storageName)
        }
        storage.names(BackupStorageArea.OBJECTS)
            .filter { it.startsWith('.') && (it.endsWith(".partial") || it.endsWith(".previous")) }
            .forEach { storage.delete(BackupStorageArea.OBJECTS, it) }
    }

    private fun copyExactly(source: InputStream, destination: OutputStream, expected: Long, cancelled: () -> Boolean) {
        val buffer = ByteArray(BackupFormatContract.COPY_BUFFER_BYTES)
        var copied = 0L
        try {
            while (true) {
                checkCancelled(cancelled)
                val count = source.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                destination.write(buffer, 0, count)
                copied = Math.addExact(copied, count.toLong())
                require(copied <= expected) { "backup source exceeded declared size" }
            }
            require(copied == expected) { "backup source shorter than declared size" }
        } finally {
            buffer.fill(0)
        }
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw BackupCancelledException()
    }

    private fun ManagedBackupInput.totalLogicalBytes(): Long = (settings + attachments + portableKeyMaterial + listOfNotNull(vaultRecoveryEnvelope)).fold(databaseSnapshot.size) { total, item ->
        Math.addExact(total, item.size)
    }

    private data class StoredSource(val manifest: BackupManifestObject, val physicalIncrement: Long)
    private class BackupCancelledException : IOException()

    private companion object {
        val REPOSITORY_HEADER = "repository-header.header"
        val MANIFEST_CONTAINER_MAGIC = byteArrayOf(0x4c, 0x42, 0x53, 0x4d)
        const val MAX_ENVELOPE_BYTES = 1024 * 1024
        const val MAX_MANIFEST_BYTES = 256 * 1024 * 1024
        const val SECONDS_PER_DAY = 86_400L
    }
}

private fun InputStream.readChunk(maximum: Int): ByteArray {
    val buffer = ByteArray(maximum)
    var offset = 0
    while (offset < maximum) {
        val count = read(buffer, offset, maximum - offset)
        if (count < 0) break
        if (count == 0) continue
        offset += count
    }
    return if (offset == 0) ByteArray(0) else buffer.copyOf(offset).also { buffer.fill(0) }
}

private fun ByteArray.sha256(): Hash256 = Hash256.fromBytes(MessageDigest.getInstance("SHA-256").digest(this)).required()

private fun OutputStream.writeInt(value: Int) {
    write(byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()))
}

private fun InputStream.readInt(): Int {
    val bytes = readExact(Int.SIZE_BYTES)
    return ((bytes[0].toInt() and 0xff) shl 24) or ((bytes[1].toInt() and 0xff) shl 16) or
        ((bytes[2].toInt() and 0xff) shl 8) or (bytes[3].toInt() and 0xff)
}

private fun InputStream.readExact(size: Int): ByteArray = ByteArray(size).also { output ->
    var offset = 0
    while (offset < size) {
        val count = read(output, offset, size - offset)
        if (count < 0) throw EOFException()
        if (count > 0) offset += count
    }
}

private fun InputStream.readBytesBounded(maximum: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(BackupFormatContract.COPY_BUFFER_BYTES)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (count == 0) continue
        total = Math.addExact(total, count)
        require(total <= maximum)
        output.write(buffer, 0, count)
    }
    buffer.fill(0)
    return output.toByteArray()
}

private fun objectAssociatedData(repositoryId: BackupRepositoryId, objectId: BackupObjectId): ByteArray = "ledger-backup-object-v1\u0000".toByteArray(Charsets.US_ASCII) + repositoryId.value.bytes + objectId.value.bytes

private fun manifestAssociatedData(repositoryId: BackupRepositoryId, snapshotId: BackupSnapshotId): ByteArray = "ledger-backup-manifest-v1\u0000".toByteArray(Charsets.US_ASCII) + repositoryId.value.bytes + snapshotId.value.bytes

private fun BackupSnapshotId.manifestName(): String = value.bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) } + ".manifest"

private fun IOException.isStorageFull(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is ErrnoException && current.errno == OsConstants.ENOSPC) return true
        current = current.cause
    }
    return message?.contains("space", ignoreCase = true) == true
}

private fun <T> DomainResult<T>.required(): T = (this as? DomainResult.Success)?.value ?: error("invalid backup value")
