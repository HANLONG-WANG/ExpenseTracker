@file:Suppress(
    "LongParameterList",
    "LoopWithTooManyJumpStatements",
    "MagicNumber",
    "NestedBlockDepth",
    "ReturnCount",
    "TooManyFunctions",
)

package app.ledger.core.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.system.ErrnoException
import android.system.OsConstants
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.common.StableIdSource
import app.ledger.core.database.LedgerDatabase
import app.ledger.core.security.CryptographicRandomSource
import app.ledger.core.security.DeviceLedgerKeys
import app.ledger.core.security.LedgerTink
import app.ledger.core.security.PlatformCryptographicRandomSource
import app.ledger.core.security.SecurityAssociatedData
import app.ledger.finance.application.AttachmentImportReceipt
import app.ledger.finance.application.AttachmentImportRequest
import app.ledger.finance.application.AttachmentObjectPort
import app.ledger.finance.domain.AttachmentId
import app.ledger.finance.domain.BlobId
import app.ledger.finance.domain.Hash256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

class EncryptedAttachmentObjectStore(
    context: Context,
    private val bookId: StableId,
    private val keys: DeviceLedgerKeys,
    database: LedgerDatabase,
    private val stableIdSource: StableIdSource,
    private val clock: Clock,
    private val cryptographicRandom: CryptographicRandomSource = PlatformCryptographicRandomSource,
) : AttachmentObjectPort,
    AttachmentContentReader {
    private val catalog = AttachmentDatabaseCatalog(database)
    private val storage = AttachmentPrivateStorage(context.applicationContext, bookId, cryptographicRandom)
    private val importMutex = Mutex()

    override suspend fun import(request: AttachmentImportRequest): DomainResult<AttachmentImportReceipt> = import(request) { }

    suspend fun import(
        request: AttachmentImportRequest,
        onProgress: (AttachmentImportProgress) -> Unit,
    ): DomainResult<AttachmentImportReceipt> = importMutex.withLock {
        try {
            DomainResult.Success(importOrThrow(request, onProgress))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: FileNotFoundException) {
            DomainResult.Failure(AttachmentInfrastructureError.SOURCE_UNAVAILABLE)
        } catch (error: IOException) {
            DomainResult.Failure(if (error.isStorageExhausted()) AttachmentInfrastructureError.STORAGE_EXHAUSTED else AttachmentInfrastructureError.IO_FAILURE)
        } catch (_: app.ledger.core.security.SecurityException) {
            DomainResult.Failure(AttachmentInfrastructureError.DECRYPTION_FAILED)
        } catch (_: RuntimeException) {
            DomainResult.Failure(AttachmentInfrastructureError.DATABASE_FAILURE)
        }
    }

    override suspend fun removeUnreferenced(blobId: BlobId): DomainResult<Unit> = withContext(Dispatchers.IO) {
        val candidate = catalog.eligibleGarbage(clock.instant()).singleOrNull { it.blobId == blobId }
            ?: return@withContext DomainResult.Failure(AttachmentInfrastructureError.STILL_REFERENCED)
        if (catalog.deleteBlobMetadataIfUnreferenced(candidate, clock.instant())) {
            storage.deleteObjectFamily(candidate.storageName)
            DomainResult.Success(Unit)
        } else {
            DomainResult.Failure(AttachmentInfrastructureError.STILL_REFERENCED)
        }
    }

    suspend fun discardUncommittedAttachment(
        attachmentId: AttachmentId,
        eligibleAfter: Instant = clock.instant(),
    ): DomainResult<Unit> = withContext(Dispatchers.IO) {
        try {
            catalog.discardUnreferencedAttachment(attachmentId, eligibleAfter)
            DomainResult.Success(Unit)
        } catch (_: IllegalStateException) {
            DomainResult.Failure(AttachmentInfrastructureError.STILL_REFERENCED)
        }
    }

    suspend fun runGarbageCollection(now: Instant = clock.instant()): AttachmentGarbageCollectionResult = withContext(Dispatchers.IO) {
        var deleted = 0
        var retained = 0
        catalog.eligibleGarbage(now).forEach { blob ->
            if (catalog.deleteBlobMetadataIfUnreferenced(blob, now)) {
                storage.deleteObjectFamily(blob.storageName)
                deleted = Math.addExact(deleted, 1)
            } else {
                retained = Math.addExact(retained, 1)
            }
        }
        AttachmentGarbageCollectionResult(deleted, retained)
    }

    suspend fun recoverInterruptedImports(): AttachmentRecoveryResult = withContext(Dispatchers.IO) {
        val staged = storage.clearStaging()
        val referenced = catalog.referencedStorageNames()
        val orphaned = storage.deleteUnreferencedObjects(referenced)
        AttachmentRecoveryResult(staged, orphaned)
    }

    suspend fun rename(attachmentId: AttachmentId, requestedName: String): DomainResult<AttachmentMetadata> = withContext(Dispatchers.IO) {
        val displayName = AttachmentMetadataPolicy.sanitizeDisplayName(requestedName)
        if (!catalog.rename(attachmentId, displayName)) {
            DomainResult.Failure(AttachmentInfrastructureError.NOT_FOUND)
        } else {
            val stored = catalog.attachment(attachmentId)
                ?: return@withContext DomainResult.Failure(AttachmentInfrastructureError.NOT_FOUND)
            DomainResult.Success(stored.metadata())
        }
    }

    suspend fun generateEncryptedThumbnail(attachmentId: AttachmentId): DomainResult<Boolean> = withContext(Dispatchers.IO) {
        val stored = catalog.attachment(attachmentId)
            ?: return@withContext DomainResult.Failure(AttachmentInfrastructureError.NOT_FOUND)
        if (storage.thumbnail(stored.storageName).isFile) return@withContext DomainResult.Success(true)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openOriginal(attachmentId).use { decrypted -> BitmapFactory.decodeStream(decrypted.plaintext, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext DomainResult.Success(false)
        val sample = thumbnailSample(bounds.outWidth, bounds.outHeight)
        val bitmap = openOriginal(attachmentId).use { decrypted ->
            BitmapFactory.decodeStream(
                decrypted.plaintext,
                null,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } ?: return@withContext DomainResult.Success(false)
        val plaintext = ByteArrayOutputStream().use { output ->
            try {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, THUMBNAIL_QUALITY, output))
                output.toByteArray()
            } finally {
                bitmap.recycle()
            }
        }
        val staging = storage.newStagingFile()
        val keyAssociatedData = SecurityAssociatedData.attachmentKey(bookId, stored.blobId.value, stored.encryptionVersion)
        val thumbnailAssociatedData = SecurityAssociatedData.attachmentThumbnail(bookId, stored.blobId.value, stored.encryptionVersion)
        try {
            val primitive = keys.unwrapAttachmentDataKey(stored.wrappedDataKey, keyAssociatedData)
            FileOutputStream(staging).use { fileOutput ->
                primitive.newEncryptingStream(FlushOnlyOutputStream(fileOutput), thumbnailAssociatedData).use { encrypted ->
                    encrypted.write(plaintext)
                }
                fileOutput.flush()
                fileOutput.fd.sync()
            }
            storage.moveIntoThumbnailStore(staging, stored.storageName)
            DomainResult.Success(true)
        } catch (_: IOException) {
            DomainResult.Failure(AttachmentInfrastructureError.IO_FAILURE)
        } catch (_: app.ledger.core.security.SecurityException) {
            DomainResult.Failure(AttachmentInfrastructureError.DECRYPTION_FAILED)
        } finally {
            plaintext.fill(0)
            keyAssociatedData.fill(0)
            thumbnailAssociatedData.fill(0)
            staging.takeIf(File::exists)?.let(storage::deleteRequired)
        }
    }

    override fun metadata(attachmentId: AttachmentId): AttachmentMetadata? = catalog.attachment(attachmentId)?.metadata()

    fun activeMetadata(): List<AttachmentMetadata> = catalog.activeAttachments().map { it.metadata() }

    override fun openOriginal(attachmentId: AttachmentId): DecryptedAttachment = openEncryptedVariant(
        attachmentId,
        AttachmentContentVariant.ORIGINAL,
    )

    override fun openThumbnail(attachmentId: AttachmentId): DecryptedAttachment? {
        val stored = catalog.attachment(attachmentId) ?: return null
        if (!storage.thumbnail(stored.storageName).isFile) return null
        return openStored(stored, AttachmentContentVariant.THUMBNAIL)
    }

    internal fun storedObject(attachmentId: AttachmentId): StoredAttachmentObject? = catalog.attachment(attachmentId)

    internal fun privateStorage(): AttachmentPrivateStorage = storage

    private suspend fun importOrThrow(
        request: AttachmentImportRequest,
        onProgress: (AttachmentImportProgress) -> Unit,
    ): AttachmentImportReceipt = withContext(Dispatchers.IO) {
        val blobId = BlobId(stableIdSource.nextStableId())
        val attachmentId = AttachmentId(stableIdSource.nextStableId())
        val importedAt = clock.instant()
        val encryptionVersion = CURRENT_ENCRYPTION_VERSION
        val displayName = AttachmentMetadataPolicy.sanitizeDisplayName(request.displayName)
        val mimeType = AttachmentMetadataPolicy.normalizeMimeType(request.mimeType)
        val extension = AttachmentMetadataPolicy.normalizeExtension(request.extension, displayName)
        val storageName = storage.nextStorageName()
        val staging = storage.newStagingFile()
        val dataKey = keys.createAttachmentDataKey()
        val keyAssociatedData = SecurityAssociatedData.attachmentKey(bookId, blobId.value, encryptionVersion)
        val contentAssociatedData = SecurityAssociatedData.attachmentContent(bookId, blobId.value, encryptionVersion)
        var finalObject: File? = null
        var databaseCommitted = false
        try {
            val wrappedDataKey = keys.wrapAttachmentDataKey(dataKey, keyAssociatedData)
            val digestAndSize = request.content.openStream().use { source ->
                dataKey.useBytes(LedgerTink::streamingAead).let { primitive ->
                    encryptAndHash(source, staging, primitive, contentAssociatedData, request.declaredSize, onProgress)
                }
            }
            val existing = catalog.findBlob(digestAndSize.hash, digestAndSize.size)
            val stored = if (existing != null) {
                storage.deleteRequired(staging)
                check(storage.objectFile(existing.storageName).isFile) { "database references a missing encrypted object" }
                catalog.createAttachmentForExisting(existing, attachmentId, displayName, importedAt)
            } else {
                finalObject = storage.moveIntoObjectStore(staging, storageName)
                catalog.createNew(
                    blobId,
                    attachmentId,
                    storageName,
                    digestAndSize.hash,
                    digestAndSize.size,
                    mimeType,
                    extension,
                    wrappedDataKey,
                    encryptionVersion,
                    displayName,
                    importedAt,
                )
            }
            databaseCommitted = true
            AttachmentImportReceipt(stored.attachmentId, stored.blobId, stored.plaintextSize, stored.plaintextHash)
        } finally {
            dataKey.close()
            keyAssociatedData.fill(0)
            contentAssociatedData.fill(0)
            if (staging.exists()) storage.deleteRequired(staging)
            if (!databaseCommitted) finalObject?.takeIf(File::exists)?.let(storage::deleteRequired)
        }
    }

    private suspend fun encryptAndHash(
        source: InputStream,
        staging: File,
        primitive: com.google.crypto.tink.StreamingAead,
        associatedData: ByteArray,
        declaredSize: Long?,
        onProgress: (AttachmentImportProgress) -> Unit,
    ): DigestAndSize {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        FileOutputStream(staging).use { fileOutput ->
            primitive.newEncryptingStream(FlushOnlyOutputStream(fileOutput), associatedData).use { encrypted ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    encrypted.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    total = Math.addExact(total, count.toLong())
                    onProgress(AttachmentImportProgress(total, declaredSize))
                }
                buffer.fill(0)
            }
            fileOutput.flush()
            fileOutput.fd.sync()
        }
        val hashBytes = digest.digest()
        return DigestAndSize(Hash256.fromBytes(hashBytes).successValue(), total).also { hashBytes.fill(0) }
    }

    private fun openEncryptedVariant(
        attachmentId: AttachmentId,
        variant: AttachmentContentVariant,
    ): DecryptedAttachment {
        val stored = catalog.attachment(attachmentId) ?: throw FileNotFoundException("attachment is unavailable")
        return openStored(stored, variant)
    }

    private fun openStored(stored: StoredAttachmentObject, variant: AttachmentContentVariant): DecryptedAttachment {
        val source = when (variant) {
            AttachmentContentVariant.ORIGINAL -> storage.objectFile(stored.storageName)
            AttachmentContentVariant.THUMBNAIL -> storage.thumbnail(stored.storageName)
        }
        if (!source.isFile) throw FileNotFoundException("encrypted attachment object is unavailable")
        val keyAssociatedData = SecurityAssociatedData.attachmentKey(bookId, stored.blobId.value, stored.encryptionVersion)
        val contentAssociatedData = when (variant) {
            AttachmentContentVariant.ORIGINAL -> SecurityAssociatedData.attachmentContent(
                bookId,
                stored.blobId.value,
                stored.encryptionVersion,
            )
            AttachmentContentVariant.THUMBNAIL -> SecurityAssociatedData.attachmentThumbnail(
                bookId,
                stored.blobId.value,
                stored.encryptionVersion,
            )
        }
        return try {
            val primitive = keys.unwrapAttachmentDataKey(stored.wrappedDataKey, keyAssociatedData)
            val plaintext = primitive.newDecryptingStream(source.inputStream(), contentAssociatedData)
            DecryptedAttachment(stored.metadata(), plaintext)
        } finally {
            keyAssociatedData.fill(0)
            contentAssociatedData.fill(0)
        }
    }

    private data class DigestAndSize(val hash: Hash256, val size: Long)

    private fun thumbnailSample(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > THUMBNAIL_MAX_EDGE_PIXELS || height / sample > THUMBNAIL_MAX_EDGE_PIXELS) {
            sample = Math.multiplyExact(sample, 2)
        }
        return sample
    }

    private companion object {
        const val CURRENT_ENCRYPTION_VERSION = 1
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val THUMBNAIL_MAX_EDGE_PIXELS = 256
        const val THUMBNAIL_QUALITY = 100
    }
}

interface AttachmentContentReader {
    fun metadata(attachmentId: AttachmentId): AttachmentMetadata?

    fun openOriginal(attachmentId: AttachmentId): DecryptedAttachment

    fun openThumbnail(attachmentId: AttachmentId): DecryptedAttachment?
}

class DecryptedAttachment internal constructor(
    val metadata: AttachmentMetadata,
    val plaintext: InputStream,
) : AutoCloseable {
    override fun close() = plaintext.close()

    override fun toString(): String = "DecryptedAttachment(metadata=redacted,plaintext=redacted)"
}

enum class AttachmentContentVariant { ORIGINAL, THUMBNAIL }

data class AttachmentGarbageCollectionResult(val deletedObjects: Int, val retainedObjects: Int)

data class AttachmentRecoveryResult(val deletedStagingFiles: Int, val deletedOrphanObjects: Int)

internal class AttachmentPrivateStorage(
    context: Context,
    bookId: StableId,
    private val cryptographicRandom: CryptographicRandomSource,
) {
    private val root = File(context.noBackupFilesDir, "attachment_objects/${bookId.toUuid()}")
    private val stagingDirectory = File(root, "staging")
    private val objectDirectory = File(root, "objects")

    init {
        require(stagingDirectory.mkdirs() || stagingDirectory.isDirectory)
        require(objectDirectory.mkdirs() || objectDirectory.isDirectory)
    }

    fun newStagingFile(): File = nextUnique(stagingDirectory, ".part").also { file ->
        check(file.createNewFile()) { "failed to reserve attachment staging object" }
    }

    fun nextStorageName(): String = randomHex() + ".blob"

    fun objectFile(storageName: String): File {
        require(STORAGE_NAME.matches(storageName))
        return File(objectDirectory, storageName)
    }

    fun thumbnail(storageName: String): File = File(objectDirectory, storageName.removeSuffix(".blob") + ".thumb")

    fun moveIntoObjectStore(staging: File, storageName: String): File {
        val target = objectFile(storageName)
        check(!target.exists()) { "attachment storage-name collision" }
        try {
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (error: AtomicMoveNotSupportedException) {
            throw IOException("atomic attachment move is unavailable", error)
        }
        return target
    }

    fun moveIntoThumbnailStore(staging: File, storageName: String): File {
        val target = thumbnail(storageName)
        if (target.isFile) {
            deleteRequired(staging)
            return target
        }
        try {
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (error: AtomicMoveNotSupportedException) {
            throw IOException("atomic attachment thumbnail move is unavailable", error)
        }
        return target
    }

    fun clearStaging(): Int = stagingDirectory.listFiles().orEmpty().count { file ->
        file.isFile && deleteRequired(file).let { true }
    }

    fun deleteUnreferencedObjects(referencedStorageNames: Set<String>): Int = objectDirectory.listFiles().orEmpty()
        .filter(File::isFile)
        .filter { file ->
            val originalName = when {
                STORAGE_NAME.matches(file.name) -> file.name
                THUMBNAIL_NAME.matches(file.name) -> file.name.removeSuffix(".thumb") + ".blob"
                else -> null
            }
            originalName == null || originalName !in referencedStorageNames
        }
        .count { file -> deleteRequired(file).let { true } }

    fun deleteObjectFamily(storageName: String) {
        thumbnail(storageName).takeIf(File::exists)?.let(::deleteRequired)
        objectFile(storageName).takeIf(File::exists)?.let(::deleteRequired)
    }

    fun deleteRequired(file: File) {
        check(!file.exists() || file.delete()) { "encrypted attachment object could not be deleted" }
    }

    private fun nextUnique(directory: File, suffix: String): File {
        repeat(MAXIMUM_NAME_ATTEMPTS) {
            val candidate = File(directory, randomHex() + suffix)
            if (!candidate.exists()) return candidate
        }
        error("unable to allocate an opaque attachment name")
    }

    private fun randomHex(): String {
        val bytes = ByteArray(RANDOM_NAME_BYTES).also(cryptographicRandom::nextBytes)
        return buildString(RANDOM_NAME_BYTES * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
        }.also { bytes.fill(0) }
    }

    private companion object {
        const val RANDOM_NAME_BYTES = 16
        const val MAXIMUM_NAME_ATTEMPTS = 16
        const val HEX = "0123456789abcdef"
        val STORAGE_NAME = Regex("[0-9a-f]{32}\\.blob")
        val THUMBNAIL_NAME = Regex("[0-9a-f]{32}\\.thumb")
    }
}

private fun StoredAttachmentObject.metadata(): AttachmentMetadata = AttachmentMetadata(
    attachmentId,
    displayName,
    mimeType,
    extension,
    plaintextSize,
    importedAt,
)

private fun IOException.isStorageExhausted(): Boolean {
    var candidate: Throwable? = this
    while (candidate != null) {
        if (candidate is ErrnoException && candidate.errno == OsConstants.ENOSPC) return true
        candidate = candidate.cause
    }
    return false
}

private fun <T> DomainResult<T>.successValue(): T = when (this) {
    is DomainResult.Success -> value
    is DomainResult.Failure -> error("invalid cryptographic hash")
}

private class FlushOnlyOutputStream(destination: OutputStream) : FilterOutputStream(destination) {
    override fun close() = flush()
}
