package app.ledger.core.files

import android.content.Context
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.common.StableIdSource
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.security.CryptographicRandomSource
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

    private suspend fun <T> withStore(bookId: StableId, block: suspend (EncryptedAttachmentObjectStore) -> T): T = keyProvider.open(bookId).use { keys ->
        val database = keys.databaseDek.useBytes { EncryptedDatabaseFactory.openPrimary(applicationContext, it) }
        try {
            block(EncryptedAttachmentObjectStore(applicationContext, bookId, keys, database, stableIds, clock, random))
        } finally {
            database.close()
        }
    }
}
