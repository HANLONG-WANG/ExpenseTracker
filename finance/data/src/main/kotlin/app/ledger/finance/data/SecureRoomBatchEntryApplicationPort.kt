@file:Suppress("LongMethod", "LongParameterList", "TooManyFunctions")

package app.ledger.finance.data

import android.content.Context
import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.database.LedgerDatabase
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.finance.application.BatchAuditView
import app.ledger.finance.application.BatchEntryApplicationPort
import app.ledger.finance.application.BatchEntryCommitResult
import app.ledger.finance.application.BatchEntryField
import app.ledger.finance.application.BatchEntryRowWriteRequest
import app.ledger.finance.application.BatchEntrySubmitRequest
import app.ledger.finance.application.BatchUndoRequest
import app.ledger.finance.application.BatchValidationIssue
import app.ledger.finance.application.BatchValidationReport
import app.ledger.finance.application.BatchValidationSeverity
import app.ledger.finance.application.DefaultFinancialMutationCoordinator
import app.ledger.finance.application.FinanceDataError
import app.ledger.finance.application.FinancialPlanningPort
import app.ledger.finance.application.FinancialPlanningSnapshotRepository
import app.ledger.finance.application.LedgerWriteGate
import app.ledger.finance.application.ReferenceDataManagementPort
import app.ledger.finance.domain.BatchFinancialCommand
import app.ledger.finance.domain.CanonicalFinancialHash
import app.ledger.finance.domain.CommandReceipt
import app.ledger.finance.domain.DeterministicFinancialPlanner
import app.ledger.finance.domain.DomainViolation
import app.ledger.finance.domain.FinancialCommand
import app.ledger.finance.domain.Hash256
import app.ledger.finance.domain.MoveTransactionToTrashCommand
import app.ledger.finance.domain.PlanningSnapshot
import app.ledger.finance.domain.TransactionId
import app.ledger.finance.domain.TransactionRevisionId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.time.Duration

/** SQLCipher P24 adapter. A batch is planned once and persisted by one coordinator transaction. */
public class SecureRoomBatchEntryApplicationPort(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
    referenceDataPort: ReferenceDataManagementPort,
    private val failureInjector: FinancialCommitFailureInjector = FinancialCommitFailureInjector.NONE,
) : BatchEntryApplicationPort {
    private val applicationContext = context.applicationContext
    private val gate: LedgerWriteGate = BatchLedgerWriteGate()
    private val ordinary = SecureRoomOrdinaryTransactionEntryPort(context, keyProvider, referenceDataPort)
    private val refunds = SecureRoomRefundApplicationPort(context, keyProvider, referenceDataPort)
    private val mapper = RoomReferenceFinancialSnapshotMapper()

    override suspend fun validate(request: BatchEntrySubmitRequest): DomainResult<BatchValidationReport> = withDatabaseResult(request.bookId) { database ->
        DomainResult.Success(validateInside(database, request).first)
    }

    override suspend fun submit(request: BatchEntrySubmitRequest): DomainResult<BatchEntryCommitResult> = withDatabaseResult(request.bookId) { database ->
        val (report, prepared) = validateInside(database, request)
        if (!report.canCommit) return@withDatabaseResult DomainResult.Failure(DomainViolation.InvalidField("batch.errors"))
        if (report.warnings.isNotEmpty() && !request.warningsConfirmed) {
            return@withDatabaseResult DomainResult.Failure(DomainViolation.InvalidField("batch.warnings"))
        }
        val parent = batchCommand(request, prepared)
        val snapshot = rootSnapshot(prepared)
        val sideEffect = FinancialCommitSideEffect { connection, plan ->
            prepared.forEach { it.sideEffect.apply(connection, plan) }
        }
        val afterSideEffect = FinancialCommitSideEffect { connection, plan ->
            prepared.forEach { it.afterFinancialWriteSideEffect.apply(connection, plan) }
        }
        val repository = RoomFinancialCommitRepository(
            database,
            failureInjector = failureInjector,
            sideEffect = sideEffect,
            afterFinancialWriteSideEffect = afterSideEffect,
        )
        when (val result = coordinate(repository, parent, snapshot)) {
            is DomainResult.Success -> DomainResult.Success(BatchEntryCommitResult(result.value, request.rows.map(BatchEntryRowWriteRequest::transactionId)))
            is DomainResult.Failure -> result
        }
    }

    override suspend fun audit(bookId: StableId, batchCommandId: CommandId): DomainResult<BatchAuditView?> = withDatabaseResult(bookId) { database ->
        database.readLedger { db ->
            val header = db.queryOne(
                "SELECT bc.uid,bc.created_at FROM command_receipt cr JOIN book_commit bc ON bc.id=cr.commit_id " +
                    "WHERE cr.command_uid=? AND cr.command_type=?",
                arrayOf(batchCommandId.stableId.bytes, app.ledger.finance.domain.FinancialCommandType.BATCH_MUTATION.ordinal),
            ) { it.stableId("uid") to it.long("created_at").toStoredInstant() }
                ?: return@readLedger DomainResult.Success(null)
            val transactions = db.queryList(
                "SELECT bt.uid,bt.lifecycle_state FROM business_transaction bt JOIN book_commit bc ON bc.id=bt.created_commit_id WHERE bc.uid=? ORDER BY bt.id",
                arrayOf(header.first.bytes),
            ) { it.stableId("uid") to it.int("lifecycle_state") }
            if (transactions.isEmpty()) abort(FinanceDataError.CorruptData)
            DomainResult.Success(
                BatchAuditView(
                    batchCommandId,
                    header.first,
                    header.second,
                    transactions.map { it.first },
                    transactions.all { it.second == app.ledger.finance.domain.TransactionLifecycleState.TRASHED.ordinal },
                ),
            )
        }
    }

    override suspend fun undo(request: BatchUndoRequest): DomainResult<CommandReceipt> = withDatabaseResult(request.bookId) { database ->
        val audit = when (val audited = auditInside(database, request.originalBatchCommandId)) {
            null -> return@withDatabaseResult DomainResult.Failure(DomainViolation.InvalidField("batch.audit"))
            else -> audited
        }
        if (audit.fullyReversed || audit.transactionIds.toSet() != request.rows.map { it.transactionId }.toSet()) {
            return@withDatabaseResult DomainResult.Failure(DomainViolation.InvalidField("batch.undo"))
        }
        val sources = database.readLedger { db ->
            request.rows.map { row ->
                mapper.load(
                    db,
                    row.transactionId,
                    row.revisionId,
                    request.commitId,
                    row.factIds,
                    emptyList(),
                    request.createdAt,
                    request.deviceInstanceId,
                )
            }
        }
        val children = sources.mapIndexed { index, source ->
            val row = request.rows[index]
            val draft = MoveTransactionToTrashCommand(
                CommandId(derivedId(request.commandId.stableId, "undo:$index")),
                source.revision.id,
                zeroHash(),
                source.revision.transactionId,
                request.createdAt.plus(BATCH_UNDO_RETENTION),
                row.dependencyResolutions,
            )
            draft.copy(payloadHash = CanonicalFinancialHash.command(draft))
        }
        val draft = BatchFinancialCommand(request.commandId, zeroHash(), children)
        val command = draft.copy(payloadHash = CanonicalFinancialHash.command(draft))
        val first = sources.first().snapshot
        val root = PlanningSnapshot(
            first.book,
            null,
            null,
            emptyList(),
            emptySet(),
            emptyList(),
            null,
            emptyList(),
            batchSnapshots = sources.map(ReferenceEditSource::snapshot),
        )
        val repository = RoomFinancialCommitRepository(database)
        coordinate(repository, command, root)
    }

    private fun validateInside(
        database: LedgerDatabase,
        request: BatchEntrySubmitRequest,
    ): Pair<BatchValidationReport, List<PreparedFinancialMutation>> {
        val issues = mutableListOf<BatchValidationIssue>()
        val allStableIds = mutableSetOf<StableId>()
        val prepared = request.rows.mapNotNull { row ->
            val requestIds = when (row) {
                is BatchEntryRowWriteRequest.Ordinary ->
                    row.request.ids.factIds + row.request.ids.fxRateSnapshotIds +
                        listOf(row.request.ids.commandId, row.request.ids.transactionId, row.request.ids.revisionId)
                is BatchEntryRowWriteRequest.Refund ->
                    row.request.ids.factIds + row.request.ids.fxRateSnapshotIds +
                        listOf(row.request.ids.commandId.stableId, row.request.ids.transactionId, row.request.ids.revisionId)
            }
            if (requestIds.any { !allStableIds.add(it) }) {
                issues += BatchValidationIssue(row.rowId, BatchEntryField.BATCH, "DUPLICATE_ROW_IDENTITY", BatchValidationSeverity.ERROR)
                return@mapNotNull null
            }
            try {
                val item = when (row) {
                    is BatchEntryRowWriteRequest.Ordinary -> ordinary.prepare(database, row.request)
                    is BatchEntryRowWriteRequest.Refund -> refunds.prepare(database, row.request)
                }
                when (DeterministicFinancialPlanner.plan(item.command, item.snapshot)) {
                    is DomainResult.Success -> item
                    is DomainResult.Failure -> {
                        issues += BatchValidationIssue(row.rowId, BatchEntryField.BATCH, "ROW_DOMAIN_INVALID", BatchValidationSeverity.ERROR)
                        null
                    }
                }
            } catch (_: FinancialPersistenceAbort) {
                issues += BatchValidationIssue(row.rowId, BatchEntryField.BATCH, "ROW_REFERENCE_INVALID", BatchValidationSeverity.ERROR)
                null
            }
        }
        request.rows.filterIsInstance<BatchEntryRowWriteRequest.Refund>()
            .filter { it.request.allowExcessOverride }
            .forEach { issues += BatchValidationIssue(it.rowId, BatchEntryField.REFUND_RELATION, "EXCESS_REFUND_OVERRIDE", BatchValidationSeverity.WARNING) }
        if (prepared.size == request.rows.size) {
            val parent = batchCommand(request, prepared)
            when (DeterministicFinancialPlanner.plan(parent, rootSnapshot(prepared))) {
                is DomainResult.Failure -> issues += BatchValidationIssue(null, BatchEntryField.BATCH, "BATCH_DOMAIN_INVALID", BatchValidationSeverity.ERROR)
                is DomainResult.Success -> Unit
            }
        }
        return BatchValidationReport(issues) to prepared
    }

    private fun batchCommand(request: BatchEntrySubmitRequest, prepared: List<PreparedFinancialMutation>): BatchFinancialCommand {
        val draft = BatchFinancialCommand(request.commandId, zeroHash(), prepared.map(PreparedFinancialMutation::command))
        return draft.copy(payloadHash = CanonicalFinancialHash.command(draft))
    }

    private fun rootSnapshot(prepared: List<PreparedFinancialMutation>): PlanningSnapshot {
        val first = prepared.first().snapshot
        require(prepared.all { it.snapshot.book == first.book })
        return PlanningSnapshot(
            first.book,
            null,
            null,
            emptyList(),
            emptySet(),
            emptyList(),
            null,
            emptyList(),
            batchSnapshots = prepared.map(PreparedFinancialMutation::snapshot),
        )
    }

    private suspend fun coordinate(
        repository: RoomFinancialCommitRepository,
        command: FinancialCommand,
        snapshot: PlanningSnapshot,
    ): DomainResult<CommandReceipt> = DefaultFinancialMutationCoordinator(
        gate,
        repository,
        object : FinancialPlanningSnapshotRepository {
            override suspend fun load(command: FinancialCommand): DomainResult<PlanningSnapshot> = DomainResult.Success(snapshot)
        },
        FinancialPlanningPort(DeterministicFinancialPlanner::plan),
        repository,
    ).execute(command)

    private fun auditInside(database: LedgerDatabase, commandId: CommandId): BatchAuditView? = database.readLedger { db ->
        val header = db.queryOne(
            "SELECT bc.uid,bc.created_at FROM command_receipt cr JOIN book_commit bc ON bc.id=cr.commit_id WHERE cr.command_uid=? AND cr.command_type=?",
            arrayOf(commandId.stableId.bytes, app.ledger.finance.domain.FinancialCommandType.BATCH_MUTATION.ordinal),
        ) { it.stableId("uid") to it.long("created_at").toStoredInstant() } ?: return@readLedger null
        val rows = db.queryList(
            "SELECT bt.uid,bt.lifecycle_state FROM business_transaction bt JOIN book_commit bc ON bc.id=bt.created_commit_id WHERE bc.uid=? ORDER BY bt.id",
            arrayOf(header.first.bytes),
        ) { it.stableId("uid") to it.int("lifecycle_state") }
        if (rows.isEmpty()) abort(FinanceDataError.CorruptData)
        BatchAuditView(commandId, header.first, header.second, rows.map { it.first }, rows.all { it.second == 1 })
    }

    private suspend fun <T> withDatabaseResult(
        bookId: StableId,
        block: suspend (LedgerDatabase) -> DomainResult<T>,
    ): DomainResult<T> = try {
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

    private fun zeroHash(): Hash256 = Hash256.fromBytes(ByteArray(HASH_BYTE_COUNT)).valueOrAbort()

    private fun derivedId(seed: StableId, label: String): StableId = StableId.fromBytes(
        MessageDigest.getInstance("SHA-256").digest(seed.bytes + label.toByteArray()).copyOf(StableId.BYTE_COUNT),
    ).valueOrAbort()

    private companion object {
        const val HASH_BYTE_COUNT = 32
        val BATCH_UNDO_RETENTION: Duration = Duration.ofDays(30)
    }
}

private fun android.database.Cursor.int(name: String): Int = getInt(getColumnIndexOrThrow(name))

private fun android.database.Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))

private class BatchLedgerWriteGate : LedgerWriteGate {
    private val mutex = Mutex()
    override suspend fun <T> execute(block: suspend () -> T): T = mutex.withLock { block() }
}
