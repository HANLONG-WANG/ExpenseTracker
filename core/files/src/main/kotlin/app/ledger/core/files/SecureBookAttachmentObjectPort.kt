package app.ledger.core.files

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.common.StableIdSource
import app.ledger.core.security.CryptographicRandomSource
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.core.security.DeviceLedgerKeys
import app.ledger.core.security.LedgerAccessMode
import app.ledger.core.security.LedgerDatabaseOperationAccess
import app.ledger.core.security.SecurityAssociatedData
import app.ledger.finance.application.AttachmentImportReceipt
import app.ledger.finance.application.AttachmentImportRequest
import app.ledger.finance.application.BookAttachmentObjectPort
import app.ledger.finance.domain.AttachmentId
import kotlinx.coroutines.CancellationException
import java.io.FileNotFoundException
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/** Fail-closed per-operation bridge from SAF input to the encrypted private object store. */
class SecureBookAttachmentObjectPort(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
    private val stableIds: StableIdSource,
    private val clock: Clock,
    private val random: CryptographicRandomSource,
    private val databaseAccess: LedgerDatabaseOperationAccess,
) : BookAttachmentObjectPort {
    private val applicationContext = context.applicationContext

    override suspend fun import(bookId: StableId, request: AttachmentImportRequest): DomainResult<AttachmentImportReceipt> = try {
        withStore(bookId, LedgerAccessMode.WRITE) { it.import(request) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DomainResult.Failure(AttachmentInfrastructureError.IO_FAILURE)
    }

    override suspend fun discardUncommitted(bookId: StableId, attachmentId: AttachmentId): DomainResult<Unit> = try {
        withStore(bookId, LedgerAccessMode.WRITE) { it.discardUncommittedAttachment(attachmentId) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DomainResult.Failure(AttachmentInfrastructureError.IO_FAILURE)
    }

    /** Owns decrypted-reader capability only while an attachment destination is active. */
    suspend fun openSession(bookId: StableId): SecureAttachmentSession {
        val keys = keyProvider.open(bookId)
        return try {
            val reader = databaseAccess.withCurrentDatabase(bookId, LedgerAccessMode.READ) { database ->
                val store = EncryptedAttachmentObjectStore(
                    applicationContext,
                    bookId,
                    keys,
                    database,
                    stableIds,
                    clock,
                    random,
                )
                SnapshotAttachmentContentReader(bookId, keys, store.privateStorage(), store.activeStoredObjects())
            }
            SecureAttachmentSession(applicationContext, keys, reader) { attachmentId, requestedName ->
                val result = withStore(bookId, LedgerAccessMode.WRITE) { it.rename(attachmentId, requestedName) }
                if (result is DomainResult.Success) reader.replaceMetadata(result.value)
                result
            }
        } catch (error: Exception) {
            keys.close()
            throw error
        }
    }

    private suspend fun <T> withStore(
        bookId: StableId,
        mode: LedgerAccessMode,
        block: suspend (EncryptedAttachmentObjectStore) -> T,
    ): T = keyProvider.open(bookId).use { keys ->
        databaseAccess.withCurrentDatabase(bookId, mode) { database ->
            block(EncryptedAttachmentObjectStore(applicationContext, bookId, keys, database, stableIds, clock, random))
        }
    }
}

class SecureAttachmentSession internal constructor(
    context: Context,
    private val keys: DeviceLedgerKeys,
    private val reader: SnapshotAttachmentContentReader,
    private val renameAction: suspend (AttachmentId, String) -> DomainResult<AttachmentMetadata>,
) : AutoCloseable {
    val imageLoader = SecureAttachmentImageLoader(context, reader)
    private val providerRuntime = SecureAttachmentProviderRuntime(SystemClock::elapsedRealtime)
    private val externalOpen = SecureAttachmentExternalOpen(context, providerRuntime)
    private var closed = false

    init {
        providerRuntime.onBookReady(reader, imageLoader)
        SecureAttachmentProviderProcess.install(providerRuntime)
    }

    fun metadata(attachmentId: AttachmentId): AttachmentMetadata? = reader.metadata(attachmentId)

    suspend fun rename(attachmentId: AttachmentId, requestedName: String): DomainResult<AttachmentMetadata> = renameAction(attachmentId, requestedName)

    fun externalOpenIntent(attachmentId: AttachmentId): Intent? = externalOpen.beginConfirmation(attachmentId)?.authorize()?.intent

    override fun close() {
        if (closed) return
        closed = true
        SecureAttachmentProviderProcess.uninstall(providerRuntime)
        providerRuntime.close()
        imageLoader.close()
        reader.close()
        keys.close()
    }
}

internal class SnapshotAttachmentContentReader(
    private val bookId: StableId,
    private val keys: DeviceLedgerKeys,
    private val storage: AttachmentPrivateStorage,
    objects: List<StoredAttachmentObject>,
) : AttachmentContentReader,
    AutoCloseable {
    private val objects = ConcurrentHashMap(objects.associateBy(StoredAttachmentObject::attachmentId))

    override fun metadata(attachmentId: AttachmentId): AttachmentMetadata? = objects[attachmentId]?.toMetadata()

    internal fun replaceMetadata(metadata: AttachmentMetadata) {
        objects.computeIfPresent(metadata.attachmentId) { _, stored ->
            stored.copy(displayName = metadata.displayName, importedAt = metadata.importedAt)
        }
    }

    override fun openOriginal(attachmentId: AttachmentId): DecryptedAttachment = open(attachmentId, AttachmentContentVariant.ORIGINAL)

    override fun openThumbnail(attachmentId: AttachmentId): DecryptedAttachment? {
        val stored = objects[attachmentId]
        return if (stored != null && storage.thumbnail(stored.storageName).isFile) {
            open(stored, AttachmentContentVariant.THUMBNAIL)
        } else {
            null
        }
    }

    override fun close() {
        objects.values.forEach { it.wrappedDataKey.fill(0) }
        objects.clear()
    }

    private fun open(attachmentId: AttachmentId, variant: AttachmentContentVariant): DecryptedAttachment = open(
        objects[attachmentId] ?: throw FileNotFoundException("attachment is unavailable"),
        variant,
    )

    private fun open(stored: StoredAttachmentObject, variant: AttachmentContentVariant): DecryptedAttachment {
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
            DecryptedAttachment(
                stored.toMetadata(),
                primitive.newDecryptingStream(source.inputStream(), contentAssociatedData),
            )
        } finally {
            keyAssociatedData.fill(0)
            contentAssociatedData.fill(0)
        }
    }

    private fun StoredAttachmentObject.toMetadata(): AttachmentMetadata = AttachmentMetadata(
        attachmentId,
        displayName,
        mimeType,
        extension,
        plaintextSize,
        importedAt,
    )
}
