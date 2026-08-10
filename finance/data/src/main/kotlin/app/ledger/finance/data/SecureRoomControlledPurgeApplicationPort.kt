@file:Suppress("MagicNumber", "TooGenericExceptionCaught")

package app.ledger.finance.data

import android.content.Context
import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.database.LedgerDatabase
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.finance.application.ControlledPurgeApplicationPort
import app.ledger.finance.application.ControlledPurgeRequest
import app.ledger.finance.application.ControlledPurgeResult
import app.ledger.finance.application.DefaultFinancialMutationCoordinator
import app.ledger.finance.application.DefaultLedgerWriteGate
import app.ledger.finance.application.FinanceDataError
import app.ledger.finance.application.FinancialPlanningPort
import app.ledger.finance.application.FinancialPlanningSnapshotRepository
import app.ledger.finance.application.JournalPurgeAssessment
import app.ledger.finance.application.LedgerWriteGate
import app.ledger.finance.application.PurgeIneligibilityReason
import app.ledger.finance.domain.BookCommitId
import app.ledger.finance.domain.CanonicalFinancialHash
import app.ledger.finance.domain.DeterministicFinancialPlanner
import app.ledger.finance.domain.DomainViolation
import app.ledger.finance.domain.EntityType
import app.ledger.finance.domain.FinancialCommand
import app.ledger.finance.domain.FinancialCommandType
import app.ledger.finance.domain.Hash256
import app.ledger.finance.domain.PlanningSnapshot
import app.ledger.finance.domain.PurgeEligibility
import app.ledger.finance.domain.PurgeTransactionCommand
import app.ledger.finance.domain.TransactionId
import app.ledger.finance.domain.TransactionRevisionId
import java.time.Instant

/** P31 entry: the UI can request purge only through the shared financial coordinator. */
class SecureRoomControlledPurgeApplicationPort(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
    private val writeGate: LedgerWriteGate = DefaultLedgerWriteGate(),
    private val failureInjector: FinancialCommitFailureInjector = FinancialCommitFailureInjector.NONE,
) : ControlledPurgeApplicationPort {
    private val applicationContext = context.applicationContext
    private val journal = SecureRoomJournalApplicationPort(applicationContext, keyProvider)
    private val mapper = RoomReferenceFinancialSnapshotMapper()

    override suspend fun assess(
        bookId: StableId,
        transactionId: StableId,
        now: Instant,
    ): DomainResult<JournalPurgeAssessment> = journal.assessPurge(bookId, transactionId, now).withoutMaintenanceMarker()

    override suspend fun purge(request: ControlledPurgeRequest): DomainResult<ControlledPurgeResult> {
        return withDatabase(request.bookId) { database ->
            val repository = RoomFinancialCommitRepository(database, failureInjector)
            when (val existing = repository.find(CommandId(request.commandId))) {
                is DomainResult.Failure -> return@withDatabase DomainResult.Failure(existing.error)
                is DomainResult.Success -> existing.value?.let { receipt ->
                    val samePurge = receipt.commandType == FinancialCommandType.PURGE_TRANSACTION &&
                        receipt.commitId == BookCommitId(request.purgeCommitId) &&
                        receipt.primaryEntityId?.type == EntityType.TRANSACTION &&
                        receipt.primaryEntityId?.stableId == request.transactionId
                    return@withDatabase if (samePurge) {
                        DomainResult.Success(ControlledPurgeResult(receipt, 0, 0))
                    } else {
                        DomainResult.Failure(DomainViolation.DuplicateCommandPayloadMismatch)
                    }
                }
            }
            val assessment = when (val value = assess(request.bookId, request.transactionId, request.evaluatedAt)) {
                is DomainResult.Success -> value.value
                is DomainResult.Failure -> return@withDatabase DomainResult.Failure(value.error)
            }
            if (!assessment.canPurgeNow) {
                return@withDatabase DomainResult.Failure(FinanceDataError.MaintenanceRequired)
            }
            val counts = database.readLedger { connection ->
                val transactionId = connection.queryOne(
                    "SELECT id FROM business_transaction WHERE uid=?",
                    arrayOf(request.transactionId.bytes),
                ) { it.getLong(0) } ?: abort(FinanceDataError.CorruptData)
                val attachmentCount = connection.queryOne(
                    "SELECT COUNT(DISTINCT tra.attachment_id) FROM transaction_revision tr " +
                        "JOIN transaction_revision_attachment tra ON tra.revision_id=tr.id WHERE tr.transaction_id=?",
                    arrayOf(transactionId),
                ) { it.getInt(0) } ?: 0
                val blobCount = connection.queryOne(
                    "SELECT COUNT(DISTINCT a.blob_id) FROM transaction_revision tr JOIN transaction_revision_attachment tra " +
                        "ON tra.revision_id=tr.id JOIN attachment a ON a.id=tra.attachment_id WHERE tr.transaction_id=?",
                    arrayOf(transactionId),
                ) { it.getInt(0) } ?: 0
                attachmentCount to blobCount
            }
            val snapshot = database.readLedger { connection ->
                mapper.loadForPurge(
                    connection,
                    request.transactionId,
                    request.purgeCommitId,
                    request.evaluatedAt,
                    request.deviceInstanceId,
                )
            }
            val command = PurgeTransactionCommand(
                CommandId(request.commandId),
                TransactionRevisionId(request.expectedRevisionId),
                Hash256.sha256(ByteArray(0)),
                TransactionId(request.transactionId),
                PurgeEligibility(
                    TransactionId(request.transactionId),
                    requireNotNull(snapshot.currentTransaction).lifecycleState,
                    assessment.purgeAfter ?: return@withDatabase DomainResult.Failure(FinanceDataError.MaintenanceRequired),
                    request.evaluatedAt,
                    accountCurrencyNetZero = true,
                    baseCurrencyNetZero = true,
                    effectsNetZero = true,
                    dependenciesClosed = true,
                    referencedByOperation = false,
                    attachmentsReadByBackup = false,
                ),
            ).let { it.copy(payloadHash = CanonicalFinancialHash.command(it)) }
            val result = DefaultFinancialMutationCoordinator(
                writeGate,
                repository,
                object : FinancialPlanningSnapshotRepository {
                    override suspend fun load(command: FinancialCommand): DomainResult<PlanningSnapshot> = DomainResult.Success(snapshot)
                },
                FinancialPlanningPort(DeterministicFinancialPlanner::plan),
                repository,
            ).execute(command)
            when (result) {
                is DomainResult.Success -> DomainResult.Success(ControlledPurgeResult(result.value, counts.first, counts.second))
                is DomainResult.Failure -> result
            }
        }
    }

    private fun DomainResult<JournalPurgeAssessment>.withoutMaintenanceMarker(): DomainResult<JournalPurgeAssessment> = when (this) {
        is DomainResult.Failure -> this
        is DomainResult.Success -> DomainResult.Success(
            value.copy(reasons = value.reasons - PurgeIneligibilityReason.PHYSICAL_PURGE_REQUIRES_MAINTENANCE),
        )
    }

    private suspend fun <T> withDatabase(bookId: StableId, block: suspend (LedgerDatabase) -> DomainResult<T>): DomainResult<T> = try {
        keyProvider.open(bookId).use { keys ->
            val database = keys.databaseDek.useBytes { EncryptedDatabaseFactory.openPrimary(applicationContext, it) }
            try {
                block(database)
            } finally {
                database.close()
            }
        }
    } catch (abort: FinancialPersistenceAbort) {
        DomainResult.Failure(abort.domainError)
    } catch (_: Exception) {
        DomainResult.Failure(FinanceDataError.DatabaseUnavailable)
    }
}
