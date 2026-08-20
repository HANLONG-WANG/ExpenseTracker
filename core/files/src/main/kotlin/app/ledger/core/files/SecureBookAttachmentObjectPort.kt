package app.ledger.core.files

import android.content.Context
import android.content.Intent
import android.os.SystemClock
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

    /** Owns decrypted-reader capability only while an attachment destination is active. */
    fun openSession(bookId: StableId): SecureAttachmentSession {
        val keys = keyProvider.open(bookId)
        val database = try {
            keys.databaseDek.useBytes { EncryptedDatabaseFactory.openPrimary(applicationContext, it) }
        } catch (error: Exception) {
            keys.close()
            throw error
        }
        return try {
            val store = EncryptedAttachmentObjectStore(
                applicationContext,
                bookId,
                keys,
                database,
                stableIds,
                clock,
                random,
            )
            SecureAttachmentSession(applicationContext, keys, database, store)
        } catch (error: Exception) {
            database.close()
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

class SecureAttachmentSession internal constructor(
    context: Context,
    private val keys: DeviceLedgerKeys,
    private val database: LedgerDatabase,
    private val store: EncryptedAttachmentObjectStore,
) : AutoCloseable {
    val imageLoader = SecureAttachmentImageLoader(context, store)
    private val providerRuntime = SecureAttachmentProviderRuntime(SystemClock::elapsedRealtime)
    private val externalOpen = SecureAttachmentExternalOpen(context, providerRuntime)
    private var closed = false

    init {
        providerRuntime.onBookReady(store, imageLoader)
        SecureAttachmentProviderProcess.install(providerRuntime)
    }

    fun metadata(attachmentId: AttachmentId): AttachmentMetadata? = store.metadata(attachmentId)

    suspend fun rename(attachmentId: AttachmentId, requestedName: String): DomainResult<AttachmentMetadata> =
        store.rename(attachmentId, requestedName)

    fun externalOpenIntent(attachmentId: AttachmentId): Intent? =
        externalOpen.beginConfirmation(attachmentId)?.authorize()?.intent

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
