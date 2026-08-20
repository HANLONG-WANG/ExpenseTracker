package app.ledger.core.files

import android.content.Context
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.common.StableIdSource
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.database.LedgerDatabase
import app.ledger.core.security.CryptographicRandomSource
import app.ledger.core.security.DeviceLedgerKeys
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.finance.application.AttachmentImportReceipt
import app.ledger.finance.application.AttachmentImportRequest
import app.ledger.finance.application.BookAttachmentObjectPort
import app.ledger.finance.domain.AttachmentId
import kotlinx.coroutines.CancellationException
import java.time.Clock

/** Fail-closed per-operation bridge from SAF input to the encrypted private object store. */
class SecureBookAttachmentObjectPort(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
    private val stableIds: StableIdSource,
    private val clock: Clock,
    private val random: CryptographicRandomSource,
) : BookAttachmentObjectPort {
    private val applicationContext = context.applicationContext

    override suspend fun import(bookId: StableId, request: AttachmentImportRequest): DomainResult<AttachmentImportReceipt> = try {
        withStore(bookId) { it.import(request) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DomainResult.Failure(AttachmentInfrastructureError.IO_FAILURE)
    }

    override suspend fun discardUncommitted(bookId: StableId, attachmentId: AttachmentId): DomainResult<Unit> = try {
        withStore(bookId) { it.discardUncommittedAttachment(attachmentId) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DomainResult.Failure(AttachmentInfrastructureError.IO_FAILURE)
    }

    /** Returns reusable attachment identities only; encrypted content remains unopened. */
    suspend fun activeMetadata(bookId: StableId): DomainResult<List<AttachmentMetadata>> = try {
        DomainResult.Success(withStore(bookId) { it.activeMetadata() })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DomainResult.Failure(AttachmentInfrastructureError.IO_FAILURE)
    }

    /** Keeps decrypted access scoped to the visible attachment flow and never persists plaintext. */
    suspend fun openSession(bookId: StableId): SecureBookAttachmentSession {
        val keys = keyProvider.open(bookId)
        var database: LedgerDatabase? = null
        try {
            database = keys.databaseDek.useBytes { EncryptedDatabaseFactory.openPrimary(applicationContext, it) }
            val store = EncryptedAttachmentObjectStore(applicationContext, bookId, keys, database, stableIds, clock, random)
            return SecureBookAttachmentSession(applicationContext, keys, database, store)
        } catch (error: Exception) {
            database?.close()
            keys.close()
            throw error
        }
    }

    private suspend fun <T> withStore(bookId: StableId, block: suspend (EncryptedAttachmentObjectStore) -> T): T = keyProvider.open(bookId).use { keys ->
        val database = keys.databaseDek.useBytes { EncryptedDatabaseFactory.openPrimary(applicationContext, it) }
        try {
            block(EncryptedAttachmentObjectStore(applicationContext, bookId, keys, database, stableIds, clock, random))
        } finally {
            database.close()
        }
    }
}

class SecureBookAttachmentSession internal constructor(
    context: Context,
    private val keys: DeviceLedgerKeys,
    private val database: LedgerDatabase,
    private val store: EncryptedAttachmentObjectStore,
) : AutoCloseable {
    private val providerRuntime = SecureAttachmentProviderRuntime(android.os.SystemClock::elapsedRealtime)
    val imageLoader: SecureAttachmentImageLoader = SecureAttachmentImageLoader(context, store)
    private val externalOpen = SecureAttachmentExternalOpen(context, providerRuntime)
    private var closed = false

    init {
        providerRuntime.onBookReady(store, imageLoader)
        try {
            SecureAttachmentProviderProcess.install(providerRuntime)
        } catch (error: Exception) {
            providerRuntime.close()
            imageLoader.close()
            throw error
        }
    }

    fun metadata(attachmentId: AttachmentId): AttachmentMetadata? = store.metadata(attachmentId)

    suspend fun rename(attachmentId: AttachmentId, displayName: String): DomainResult<AttachmentMetadata> =
        store.rename(attachmentId, displayName)

    fun externalOpenConfirmation(attachmentId: AttachmentId): ExternalOpenConfirmation? =
        externalOpen.beginConfirmation(attachmentId)

    override fun close() {
        if (closed) return
        closed = true
        SecureAttachmentProviderProcess.uninstall(providerRuntime)
        providerRuntime.close()
        imageLoader.close()
        database.close()
        keys.close()
    }
}
