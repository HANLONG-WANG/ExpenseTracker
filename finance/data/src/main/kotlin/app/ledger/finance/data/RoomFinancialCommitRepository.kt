package app.ledger.finance.data

import android.database.sqlite.SQLiteFullException
import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.database.DatabaseIntegrityAudit
import app.ledger.core.database.LedgerDatabase
import app.ledger.finance.application.AtomicFinancialCommitRepository
import app.ledger.finance.application.CommandReceiptRepository
import app.ledger.finance.application.FinanceDataError
import app.ledger.finance.domain.BatchFinancialCommand
import app.ledger.finance.domain.CommandReceipt
import app.ledger.finance.domain.CommitKind
import app.ledger.finance.domain.ConfigureBudgetMonthCommand
import app.ledger.finance.domain.EditTransactionCommand
import app.ledger.finance.domain.EntityType
import app.ledger.finance.domain.FinancialCommand
import app.ledger.finance.domain.FinancialCommandType
import app.ledger.finance.domain.FinancialMutationPlan
import app.ledger.finance.domain.MoveTransactionToTrashCommand
import app.ledger.finance.domain.PurgeTransactionCommand
import app.ledger.finance.domain.RecordBudgetAdjustmentCommand
import app.ledger.finance.domain.RecordBudgetAdjustmentsCommand
import app.ledger.finance.domain.RecordGoalMovementCommand
import app.ledger.finance.domain.RecordTransactionCommand
import app.ledger.finance.domain.RestoreHistoricalRevisionCommand
import app.ledger.finance.domain.RestoreTransactionCommand
import app.ledger.finance.domain.SaveBudgetTemplateCommand
import app.ledger.finance.domain.StableEntityReference
import app.ledger.finance.domain.TransactionId
import java.time.ZoneId

enum class FinancialCommitPhase {
    AFTER_COMMIT_HEADER,
    AFTER_IMMUTABLE_FACTS,
    AFTER_PROJECTIONS,
    BEFORE_BOOK_ADVANCE,
    BEFORE_RECEIPT,
}

fun interface FinancialCommitFailureInjector {
    fun checkpoint(phase: FinancialCommitPhase)

    companion object {
        val NONE: FinancialCommitFailureInjector = FinancialCommitFailureInjector { }
    }
}

/** Reference-data changes that must share the exact financial commit transaction. */
fun interface FinancialCommitSideEffect {
    fun apply(connection: androidx.sqlite.db.SupportSQLiteDatabase, plan: FinancialMutationPlan)

    companion object {
        val NONE: FinancialCommitSideEffect = FinancialCommitSideEffect { _, _ -> }
    }
}

class RoomFinancialCommitRepository(
    private val database: LedgerDatabase,
    private val failureInjector: FinancialCommitFailureInjector = FinancialCommitFailureInjector.NONE,
    private val sideEffect: FinancialCommitSideEffect = FinancialCommitSideEffect.NONE,
) : AtomicFinancialCommitRepository,
    CommandReceiptRepository {
    private val writer = RoomFinancialPlanWriter()
    private val projections = RoomProjectionEngine()

    override suspend fun find(commandId: CommandId): DomainResult<CommandReceipt?> = protect {
        database.readLedger { connection ->
            DomainResult.Success(connection.commandReceipt(commandId)?.toDomain())
        }
    }

    @Suppress("MagicNumber") // Positional bind indexes mirror the six-column SQL statement above them.
    override suspend fun commit(
        command: FinancialCommand,
        plan: FinancialMutationPlan,
    ): DomainResult<CommandReceipt> {
        if (command is PurgeTransactionCommand || plan.commit.kind == CommitKind.PURGE) {
            return DomainResult.Failure(FinanceDataError.MaintenanceRequired)
        }
        return protect {
            database.inLedgerTransaction { connection ->
                connection.commandReceipt(command.commandId)?.let { stored ->
                    val receipt = stored.toDomain()
                    if (receipt.payloadHash == command.payloadHash && receipt.commandType == command.commandType) {
                        return@inLedgerTransaction DomainResult.Success(receipt)
                    }
                    abort(app.ledger.finance.domain.DomainViolation.DuplicateCommandPayloadMismatch)
                }
                val book = connection.queryOne(
                    "SELECT head_commit_id, local_revision, valuation_revision, rule_set_version, state, default_zone_id " +
                        "FROM book WHERE id = 1",
                ) { cursor ->
                    BookWriteState(
                        headCommitId = cursor.nullableLong("head_commit_id"),
                        localRevision = cursor.getLong(cursor.getColumnIndexOrThrow("local_revision")),
                        valuationRevision = cursor.getLong(cursor.getColumnIndexOrThrow("valuation_revision")),
                        ruleSetVersion = cursor.getInt(cursor.getColumnIndexOrThrow("rule_set_version")),
                        state = cursor.getInt(cursor.getColumnIndexOrThrow("state")),
                        defaultZoneId = cursor.getString(cursor.getColumnIndexOrThrow("default_zone_id")),
                    )
                } ?: abort(FinanceDataError.CorruptData)
                verifyCommitPreconditions(connection, command, plan, book)
                writer.write(connection, plan, failureInjector::checkpoint, sideEffect::apply)
                val projectionDate = plan.commit.createdAt
                    .atZone(ZoneId.of(book.defaultZoneId))
                    .toLocalDate()
                    .toStorageInt()
                projections.rebuildAll(
                    connection,
                    plan.targetLocalRevision.value,
                    book.valuationRevision,
                    projectionDate,
                )
                failureInjector.checkpoint(FinancialCommitPhase.AFTER_PROJECTIONS)
                verifyNewState(connection, plan, book.valuationRevision)
                failureInjector.checkpoint(FinancialCommitPhase.BEFORE_BOOK_ADVANCE)
                val updateCount = connection.compileStatement(
                    "UPDATE book SET head_commit_id = ?, local_revision = ?, " +
                        "first_financial_commit_at = COALESCE(first_financial_commit_at, ?) " +
                        "WHERE id = 1 AND local_revision = ? AND head_commit_id = ? AND state = 0",
                ).apply {
                    bindLong(1, connection.commitId(plan.commit.id))
                    bindLong(2, plan.targetLocalRevision.value)
                    if (plan.journalBundles.isEmpty()) bindNull(3) else bindLong(3, plan.commit.createdAt.toStorageEpochMillis())
                    bindLong(4, book.localRevision)
                    bindLong(5, book.headCommitId ?: abort(FinanceDataError.CorruptData))
                }.executeUpdateDelete()
                if (updateCount != 1) abort(app.ledger.finance.domain.DomainViolation.StaleExpectedRevision)
                failureInjector.checkpoint(FinancialCommitPhase.BEFORE_RECEIPT)
                val primary = primaryEntity(plan)
                connection.execSQL(
                    "INSERT INTO command_receipt(command_uid, command_type, payload_hash, commit_id, primary_entity_uid, executed_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(
                        command.commandId.stableId.bytes,
                        command.commandType.ordinal,
                        command.payloadHash.bytes,
                        connection.commitId(plan.commit.id),
                        primary?.stableId?.bytes,
                        plan.commit.createdAt.toStorageEpochMillis(),
                    ),
                )
                val receipt = connection.commandReceipt(command.commandId)?.toDomain()
                    ?: abort(FinanceDataError.CorruptData)
                DomainResult.Success(receipt)
            }
        }
    }

    private fun verifyCommitPreconditions(
        connection: androidx.sqlite.db.SupportSQLiteDatabase,
        command: FinancialCommand,
        plan: FinancialMutationPlan,
        book: BookWriteState,
    ) {
        if (book.state != 0 || book.localRevision == Long.MAX_VALUE || book.ruleSetVersion != plan.ruleSetVersion.value) {
            abort(FinanceDataError.MaintenanceRequired)
        }
        if (
            plan.commandId != command.commandId ||
            plan.expectedRevisionId != command.expectedRevisionId ||
            plan.commit.parentIds.size != 1
        ) {
            abort(FinanceDataError.CorruptData)
        }
        if (
            plan.targetLocalRevision.value != book.localRevision + 1L ||
            book.headCommitId == null ||
            connection.commitId(plan.commit.parentIds.single()) != book.headCommitId
        ) {
            abort(app.ledger.finance.domain.DomainViolation.StaleExpectedRevision)
        }
        val target = command.transactionIdOrNull()
        if (command.expectedRevisionId != null) {
            val actual = target?.let { transactionId ->
                connection.queryOne(
                    "SELECT tr.uid FROM business_transaction bt JOIN transaction_revision tr ON tr.id = bt.current_revision_id WHERE bt.uid = ?",
                    arrayOf(transactionId.value.bytes),
                ) { cursor -> cursor.stableId("uid") }
            }
            if (actual != command.expectedRevisionId?.value) {
                abort(app.ledger.finance.domain.DomainViolation.StaleExpectedRevision)
            }
        }
        if (command is BatchFinancialCommand) {
            command.commands.forEach { child ->
                val targetId = child.transactionIdOrNull()
                    ?: abort(FinanceDataError.CorruptData)
                val expected = child.expectedRevisionId
                    ?: abort(FinanceDataError.CorruptData)
                val actual = connection.queryOne(
                    "SELECT tr.uid FROM business_transaction bt JOIN transaction_revision tr " +
                        "ON tr.id = bt.current_revision_id WHERE bt.uid = ?",
                    arrayOf(targetId.value.bytes),
                ) { cursor -> cursor.stableId("uid") }
                if (actual != expected.value) {
                    abort(app.ledger.finance.domain.DomainViolation.StaleExpectedRevision)
                }
            }
        }
        if (command is ConfigureBudgetMonthCommand) {
            val actual = connection.queryOne(
                "SELECT r.uid FROM budget_month m LEFT JOIN budget_month_revision r ON r.id=m.current_revision_id WHERE m.uid=?",
                arrayOf(command.mutation.month.id.value.bytes),
            ) { cursor -> cursor.nullableStableId("uid") }
            if (actual != command.mutation.expectedRevisionId?.value) {
                abort(app.ledger.finance.domain.DomainViolation.StaleExpectedRevision)
            }
        }
        if (command is SaveBudgetTemplateCommand) {
            val actual = connection.queryOne(
                "SELECT r.uid FROM budget_template t LEFT JOIN budget_template_revision r ON r.id=t.current_revision_id WHERE t.uid=?",
                arrayOf(command.mutation.template.id.value.bytes),
            ) { cursor -> cursor.nullableStableId("uid") }
            if (actual != command.mutation.expectedRevisionId?.value) {
                abort(app.ledger.finance.domain.DomainViolation.StaleExpectedRevision)
            }
        }
        if (command is RecordGoalMovementCommand) {
            val actual = connection.queryOne(
                "SELECT row_version FROM goal WHERE uid=? AND status=?",
                arrayOf(command.movement.goalId.value.bytes, app.ledger.finance.domain.GoalStatus.ACTIVE.ordinal),
            ) { cursor -> cursor.getLong(0) }
            if (actual != command.expectedGoalRowVersion.value) {
                abort(app.ledger.finance.domain.DomainViolation.StaleExpectedRevision)
            }
        }
    }

    private fun verifyNewState(
        connection: androidx.sqlite.db.SupportSQLiteDatabase,
        plan: FinancialMutationPlan,
        valuationRevision: Long,
    ) {
        val commitId = connection.commitId(plan.commit.id)
        val unbalanced = connection.queryOne(
            "SELECT COUNT(*) FROM journal_entry WHERE created_commit_id = ? AND base_debit_total_minor <> base_credit_total_minor",
            arrayOf(commitId),
        ) { it.getLong(0) } ?: 0L
        val invalidSubtype = connection.queryOne(
            "SELECT COUNT(*) FROM current_transaction_subtype_audit WHERE has_matching_detail = 0",
        ) { it.getLong(0) } ?: 0L
        val mismatches = projections.mismatchedFamilies(
            connection,
            plan.targetLocalRevision.value,
            valuationRevision,
        )
        if (unbalanced != 0L || invalidSubtype != 0L) {
            abort(app.ledger.finance.domain.DomainViolation.Invariant("INV-001"))
        }
        if (mismatches.isNotEmpty()) abort(FinanceDataError.ProjectionMismatch)
        val audit = DatabaseIntegrityAudit.run(connection)
        if (audit.foreignKeyViolationCount != 0) {
            abort(app.ledger.finance.domain.DomainViolation.Invariant("INV-003"))
        }
        if (audit.unbalancedJournalCount != 0) {
            abort(app.ledger.finance.domain.DomainViolation.Invariant("INV-001"))
        }
        if (audit.invalidCurrentSubtypeCount != 0) {
            abort(app.ledger.finance.domain.DomainViolation.Invariant("INV-005"))
        }
        if (audit.integrityCheck != "ok") {
            abort(app.ledger.finance.domain.DomainViolation.InvalidField("database.integrityCheck"))
        }
        val missingRequiredCapability = !audit.capability.fts5 ||
            !audit.capability.rTree ||
            !audit.capability.json ||
            !audit.capability.windowFunctions
        if (missingRequiredCapability) {
            abort(FinanceDataError.CorruptData)
        }
    }

    private fun primaryEntity(plan: FinancialMutationPlan): StableEntityReference? = plan.transactions.firstOrNull()?.let {
        StableEntityReference(EntityType.TRANSACTION, it.id.value)
    }
        ?: plan.entityChanges.firstOrNull()?.entity
        ?: plan.goalMovements.firstOrNull()?.let { StableEntityReference(EntityType.GOAL, it.goalId.value) }

    private fun StoredCommandReceipt.toDomain(): CommandReceipt {
        val commandType = FinancialCommandType.entries.getOrNull(commandTypeOrdinal)
            ?: abort(FinanceDataError.CorruptData)
        val entityType = when (commandType) {
            FinancialCommandType.RECORD_GOAL_MOVEMENT -> EntityType.GOAL
            FinancialCommandType.RECORD_BUDGET_ADJUSTMENT -> EntityType.BUDGET
            FinancialCommandType.CONFIGURE_BUDGET_MONTH,
            FinancialCommandType.SAVE_BUDGET_TEMPLATE,
            FinancialCommandType.RECORD_BUDGET_ADJUSTMENTS,
            -> EntityType.BUDGET
            FinancialCommandType.BATCH_MUTATION -> null
            else -> EntityType.TRANSACTION
        }
        return CommandReceipt(
            commandId = commandId,
            commandType = commandType,
            payloadHash = payloadHash,
            commitId = commitId,
            primaryEntityId = primaryEntityId?.let { uid -> entityType?.let { StableEntityReference(it, uid) } },
            executedAt = executedAt,
        )
    }

    private inline fun <T> protect(block: () -> DomainResult<T>): DomainResult<T> = try {
        block()
    } catch (abort: FinancialPersistenceAbort) {
        DomainResult.Failure(abort.domainError)
    } catch (_: SQLiteFullException) {
        DomainResult.Failure(FinanceDataError.StorageFull)
    } catch (_: ArithmeticException) {
        DomainResult.Failure(FinanceDataError.NumericRangeExceeded)
    } catch (_: Exception) {
        DomainResult.Failure(FinanceDataError.DatabaseUnavailable)
    }
}

private data class BookWriteState(
    val headCommitId: Long?,
    val localRevision: Long,
    val valuationRevision: Long,
    val ruleSetVersion: Int,
    val state: Int,
    val defaultZoneId: String,
)

private fun FinancialCommand.transactionIdOrNull(): TransactionId? = when (this) {
    is EditTransactionCommand -> transactionId
    is RestoreHistoricalRevisionCommand -> transactionId
    is MoveTransactionToTrashCommand -> transactionId
    is RestoreTransactionCommand -> transactionId
    is PurgeTransactionCommand -> transactionId
    is RecordTransactionCommand<*>,
    is RecordGoalMovementCommand,
    is RecordBudgetAdjustmentCommand,
    is ConfigureBudgetMonthCommand,
    is SaveBudgetTemplateCommand,
    is RecordBudgetAdjustmentsCommand,
    is BatchFinancialCommand,
    -> null
}
