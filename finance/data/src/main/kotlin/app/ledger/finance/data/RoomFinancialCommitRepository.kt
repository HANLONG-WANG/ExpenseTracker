@file:Suppress("MagicNumber", "TooManyFunctions")

package app.ledger.finance.data

import android.database.sqlite.SQLiteFullException
import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.database.LedgerDatabase
import app.ledger.finance.application.AtomicFinancialCommitRepository
import app.ledger.finance.application.CommandReceiptRepository
import app.ledger.finance.application.FinanceDataError
import app.ledger.finance.domain.ApplyInstallmentSettlementCommand
import app.ledger.finance.domain.ApplyLoanPaymentCommand
import app.ledger.finance.domain.BatchFinancialCommand
import app.ledger.finance.domain.CommandReceipt
import app.ledger.finance.domain.CommitKind
import app.ledger.finance.domain.ConfigureBudgetMonthCommand
import app.ledger.finance.domain.EditTransactionCommand
import app.ledger.finance.domain.EntityType
import app.ledger.finance.domain.FinancialCommand
import app.ledger.finance.domain.FinancialCommandType
import app.ledger.finance.domain.FinancialMutationPlan
import app.ledger.finance.domain.InstallmentPlanMutation
import app.ledger.finance.domain.MergeRestoreCommand
import app.ledger.finance.domain.MoveTransactionToTrashCommand
import app.ledger.finance.domain.PurgeTransactionCommand
import app.ledger.finance.domain.RecordBudgetAdjustmentCommand
import app.ledger.finance.domain.RecordBudgetAdjustmentsCommand
import app.ledger.finance.domain.RecordGoalMovementCommand
import app.ledger.finance.domain.RecordTransactionCommand
import app.ledger.finance.domain.RestoreHistoricalRevisionCommand
import app.ledger.finance.domain.RestoreTransactionCommand
import app.ledger.finance.domain.SaveBudgetTemplateCommand
import app.ledger.finance.domain.SaveCreditProfileCommand
import app.ledger.finance.domain.SaveCreditStatementCommand
import app.ledger.finance.domain.SaveInstallmentPlanCommand
import app.ledger.finance.domain.SaveLoanContractCommand
import app.ledger.finance.domain.StableEntityReference
import app.ledger.finance.domain.TransactionId
import java.time.ZoneId

enum class FinancialCommitPhase {
    AFTER_COMMIT_HEADER,
    AFTER_IMMUTABLE_FACTS,
    AFTER_PROJECTIONS,
    BEFORE_BOOK_ADVANCE,
    BEFORE_RECEIPT,

    /** Runs only after SQLite has durably committed and cannot participate in rollback. */
    AFTER_DATABASE_COMMIT,
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
    private val beforeCommitSideEffect: FinancialCommitSideEffect = FinancialCommitSideEffect.NONE,
    private val sideEffect: FinancialCommitSideEffect = FinancialCommitSideEffect.NONE,
    private val afterFinancialWriteSideEffect: FinancialCommitSideEffect = FinancialCommitSideEffect.NONE,
    private val forceFullProjectionRebuild: Boolean = false,
    private val creditAccountCreatedInCommit: StableId? = null,
) : AtomicFinancialCommitRepository,
    CommandReceiptRepository {
    private val writer = RoomFinancialPlanWriter()
    private val projections = RoomProjectionEngine()
    private val logicalPurgeValidator = RoomLogicalPurgeValidator()

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
            return if (command is PurgeTransactionCommand && plan.commit.kind == CommitKind.PURGE) {
                commitPrivacyPurge(command, plan)
            } else {
                DomainResult.Failure(FinanceDataError.CorruptData)
            }.also { committed ->
                if (committed is DomainResult.Success) failureInjector.checkpoint(FinancialCommitPhase.AFTER_DATABASE_COMMIT)
            }
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
                writer.write(
                    connection,
                    plan,
                    failureInjector::checkpoint,
                    beforeCommitSideEffect::apply,
                    sideEffect::apply,
                    afterFinancialWriteSideEffect::apply,
                )
                val projectionDate = plan.commit.createdAt
                    .atZone(ZoneId.of(book.defaultZoneId))
                    .toLocalDate()
                    .toStorageInt()
                if (forceFullProjectionRebuild) {
                    projections.rebuildAll(
                        connection,
                        plan.targetLocalRevision.value,
                        book.valuationRevision,
                        projectionDate,
                    )
                } else {
                    projections.applyIncremental(
                        connection,
                        plan.projectionChanges,
                        connection.commitId(plan.commit.id),
                        book.valuationRevision,
                        projectionDate,
                    )
                }
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
        }.also { committed ->
            if (committed is DomainResult.Success) failureInjector.checkpoint(FinancialCommitPhase.AFTER_DATABASE_COMMIT)
        }
    }

    private suspend fun commitPrivacyPurge(
        command: PurgeTransactionCommand,
        plan: FinancialMutationPlan,
    ): DomainResult<CommandReceipt> = protect {
        database.inLedgerTransaction { connection ->
            connection.commandReceipt(command.commandId)?.let { stored ->
                val receipt = stored.toDomain()
                if (receipt.payloadHash == command.payloadHash && receipt.commandType == command.commandType) {
                    return@inLedgerTransaction DomainResult.Success(receipt)
                }
                abort(app.ledger.finance.domain.DomainViolation.DuplicateCommandPayloadMismatch)
            }
            val book = connection.queryOne(
                "SELECT head_commit_id,local_revision,valuation_revision,rule_set_version,state,default_zone_id FROM book WHERE id=1",
            ) { cursor ->
                BookWriteState(
                    cursor.nullableLong("head_commit_id"),
                    cursor.namedLong("local_revision"),
                    cursor.namedLong("valuation_revision"),
                    cursor.namedInt("rule_set_version"),
                    cursor.namedInt("state"),
                    cursor.namedString("default_zone_id"),
                )
            } ?: abort(FinanceDataError.CorruptData)
            verifyCommitPreconditions(connection, command, plan, book)
            val entered = connection.compileStatement("UPDATE book SET state=1 WHERE id=1 AND state=0")
                .executeUpdateDelete()
            if (entered != 1) abort(FinanceDataError.MaintenanceRequired)
            logicalPurgeValidator.revalidate(connection, command)
            writer.write(
                connection,
                plan,
                failureInjector::checkpoint,
                beforeCommitSideEffect::apply,
                sideEffect::apply,
                afterFinancialWrite = afterFinancialWriteSideEffect::apply,
            )
            val projectionDate = plan.commit.createdAt.atZone(ZoneId.of(book.defaultZoneId)).toLocalDate().toStorageInt()
            projections.rebuildAll(connection, plan.targetLocalRevision.value, book.valuationRevision, projectionDate)
            failureInjector.checkpoint(FinancialCommitPhase.AFTER_PROJECTIONS)
            verifyNewState(connection, plan, book.valuationRevision)
            failureInjector.checkpoint(FinancialCommitPhase.BEFORE_BOOK_ADVANCE)
            val advanced = connection.compileStatement(
                "UPDATE book SET head_commit_id=?,local_revision=?,state=0 WHERE id=1 AND local_revision=? AND head_commit_id=? AND state=1",
            ).apply {
                bindLong(1, connection.commitId(plan.commit.id))
                bindLong(2, plan.targetLocalRevision.value)
                bindLong(3, book.localRevision)
                bindLong(4, book.headCommitId ?: abort(FinanceDataError.CorruptData))
            }.executeUpdateDelete()
            if (advanced != 1) abort(app.ledger.finance.domain.DomainViolation.StaleExpectedRevision)
            failureInjector.checkpoint(FinancialCommitPhase.BEFORE_RECEIPT)
            val entity = primaryEntity(plan)
            connection.execSQL(
                "INSERT INTO command_receipt(command_uid,command_type,payload_hash,commit_id,primary_entity_uid,executed_at) " +
                    "VALUES(?,?,?,?,?,?)",
                arrayOf<Any?>(
                    command.commandId.stableId.bytes,
                    command.commandType.ordinal,
                    command.payloadHash.bytes,
                    connection.commitId(plan.commit.id),
                    entity?.stableId?.bytes,
                    plan.commit.createdAt.toStorageEpochMillis(),
                ),
            )
            val receipt = connection.commandReceipt(command.commandId)?.toDomain()
                ?: abort(FinanceDataError.CorruptData)
            DomainResult.Success(receipt)
        }
    }.also { committed ->
        if (committed is DomainResult.Success) failureInjector.checkpoint(FinancialCommitPhase.AFTER_DATABASE_COMMIT)
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
            plan.commit.parentIds.size != if (command is app.ledger.finance.domain.MergeRestoreCommand) 2 else 1
        ) {
            abort(FinanceDataError.CorruptData)
        }
        val revisionAdvancesExactly = if (command is MergeRestoreCommand) {
            plan.targetLocalRevision.value > book.localRevision
        } else {
            plan.targetLocalRevision.value == book.localRevision + 1L
        }
        if (!revisionAdvancesExactly || book.headCommitId == null ||
            connection.commitId(plan.commit.parentIds.first()) != book.headCommitId
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
        if (command is BatchFinancialCommand) verifyBatchPreconditions(connection, command, plan)
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
        verifyCreditPreconditions(connection, command)
        InstallmentPreconditionVerifier.verify(connection, command)
        LoanPreconditionVerifier.verify(connection, command)
    }

    private fun verifyCreditPreconditions(
        connection: androidx.sqlite.db.SupportSQLiteDatabase,
        command: FinancialCommand,
    ) {
        when (command) {
            is SaveCreditProfileCommand -> verifyCreditProfilePreconditions(connection, command)
            is SaveCreditStatementCommand -> verifyCreditStatementPreconditions(connection, command)
            else -> Unit
        }
    }

    private fun verifyCreditProfilePreconditions(
        connection: androidx.sqlite.db.SupportSQLiteDatabase,
        command: SaveCreditProfileCommand,
    ) {
        val accountType = connection.queryOne(
            "SELECT type FROM user_account WHERE uid=?",
            arrayOf(command.mutation.profile.accountId.value.bytes),
        ) { it.getInt(0) }
        val createsAccountInThisCommit =
            accountType == null && command.mutation.profile.accountId.value == creditAccountCreatedInCommit
        if (createsAccountInThisCommit) {
            if (command.mutation.expectedLastCommitId != null) {
                abort(app.ledger.finance.domain.DomainViolation.StaleExpectedRevision)
            }
        } else if (accountType != CREDIT_ACCOUNT_TYPE) {
            abort(app.ledger.finance.domain.DomainViolation.InvalidField("creditProfile.accountType"))
        }
        val actual = connection.queryOne(
            "SELECT bc.uid FROM credit_account_profile cap JOIN book_commit bc ON bc.id=cap.last_commit_id " +
                "WHERE cap.account_id=(SELECT id FROM user_account WHERE uid=?)",
            arrayOf(command.mutation.profile.accountId.value.bytes),
        ) { it.stableId("uid") }
        if (actual != command.mutation.expectedLastCommitId?.value) {
            abort(app.ledger.finance.domain.DomainViolation.StaleExpectedRevision)
        }
        command.mutation.profile.defaultPaymentAccountId?.let { paymentId ->
            val validPayment = connection.queryOne(
                "SELECT COUNT(*) FROM user_account WHERE uid=? AND status=0 AND type IN (0,1)",
                arrayOf(paymentId.value.bytes),
            ) { it.getLong(0) } == 1L
            if (!validPayment) {
                abort(app.ledger.finance.domain.DomainViolation.InvalidField("creditProfile.defaultPaymentAccount"))
            }
        }
    }

    private fun verifyCreditStatementPreconditions(
        connection: androidx.sqlite.db.SupportSQLiteDatabase,
        command: SaveCreditStatementCommand,
    ) {
        val mutation = command.mutation
        val actual = connection.queryOne(
            "SELECT csr.uid FROM credit_statement cs LEFT JOIN credit_statement_revision csr " +
                "ON csr.id=cs.current_revision_id WHERE cs.uid=?",
            arrayOf(mutation.statement.id.value.bytes),
        ) { it.nullableStableId("uid") }
        if (actual != mutation.expectedRevisionId?.value) {
            abort(app.ledger.finance.domain.DomainViolation.StaleExpectedRevision)
        }
        val projectedEstimate = connection.queryOne(
            "SELECT estimated_amount_minor FROM credit_statement_projection csp " +
                "JOIN credit_statement cs ON cs.id=csp.statement_id WHERE cs.uid=?",
            arrayOf(mutation.statement.id.value.bytes),
        ) { it.getLong(0) }
        if (projectedEstimate != null && projectedEstimate != mutation.revision.estimatedAmountMinor) {
            abort(app.ledger.finance.domain.DomainViolation.InvalidField("creditStatement.estimatedAmount"))
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
        val transactionUids = plan.transactions.map { it.id.value.bytes }.fold(mutableListOf<ByteArray>()) { unique, candidate ->
            if (unique.none { existing -> existing.contentEquals(candidate) }) unique += candidate
            unique
        }
        val invalidSubtype = if (transactionUids.isEmpty()) {
            0L
        } else {
            connection.queryOne(
                "SELECT COUNT(*) FROM current_transaction_subtype_audit WHERE has_matching_detail=0 " +
                    "AND transaction_id IN (SELECT id FROM business_transaction WHERE uid IN " +
                    "(${transactionUids.joinToString(",") { "?" }}))",
                transactionUids.map { it as Any }.toTypedArray(),
            ) { it.getLong(0) } ?: 0L
        }
        val mismatches = projections.mismatchedFamiliesAtStartup(
            connection,
            plan.targetLocalRevision.value,
            valuationRevision,
        )
        if (unbalanced != 0L || invalidSubtype != 0L) {
            abort(app.ledger.finance.domain.DomainViolation.Invariant("INV-001"))
        }
        if (mismatches.isNotEmpty()) abort(FinanceDataError.ProjectionMismatch)
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
            FinancialCommandType.SAVE_CREDIT_PROFILE -> EntityType.ACCOUNT
            FinancialCommandType.SAVE_CREDIT_STATEMENT -> EntityType.CREDIT_STATEMENT
            FinancialCommandType.SAVE_INSTALLMENT_PLAN -> EntityType.INSTALLMENT_PLAN
            FinancialCommandType.APPLY_INSTALLMENT_SETTLEMENT -> EntityType.TRANSACTION
            FinancialCommandType.SAVE_LOAN_CONTRACT -> EntityType.LOAN
            FinancialCommandType.APPLY_LOAN_PAYMENT -> EntityType.TRANSACTION
            FinancialCommandType.SAVE_TRANSACTION_BLUEPRINT -> EntityType.BLUEPRINT
            FinancialCommandType.SAVE_RECURRENCE_SERIES,
            FinancialCommandType.MODIFY_RECURRENCE_OCCURRENCE,
            -> EntityType.RECURRENCE_SERIES
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
    } catch (failure: Exception) {
        DomainResult.Failure(
            if (failure.isSqliteNumericRangeFailure()) FinanceDataError.NumericRangeExceeded else FinanceDataError.DatabaseUnavailable,
        )
    }
}

private fun verifyBatchPreconditions(
    connection: androidx.sqlite.db.SupportSQLiteDatabase,
    command: BatchFinancialCommand,
    plan: FinancialMutationPlan,
) {
    if (command.commands.size != plan.transactions.size) abort(FinanceDataError.CorruptData)
    command.commands.zip(plan.transactions).forEach { (child, plannedTransaction) ->
        if (child is RecordTransactionCommand<*>) {
            val exists = connection.queryOne(
                "SELECT 1 FROM business_transaction WHERE uid=?",
                arrayOf(plannedTransaction.id.value.bytes),
            ) { true } ?: false
            if (exists) abort(app.ledger.finance.domain.DomainViolation.DuplicateCommandPayloadMismatch)
        } else {
            verifyExistingBatchChildPrecondition(connection, child)
        }
    }
}

private fun verifyExistingBatchChildPrecondition(
    connection: androidx.sqlite.db.SupportSQLiteDatabase,
    child: FinancialCommand,
) {
    val targetId = child.transactionIdOrNull() ?: abort(FinanceDataError.CorruptData)
    val expected = child.expectedRevisionId ?: abort(FinanceDataError.CorruptData)
    val actual = connection.queryOne(
        "SELECT tr.uid FROM business_transaction bt JOIN transaction_revision tr " +
            "ON tr.id = bt.current_revision_id WHERE bt.uid = ?",
        arrayOf(targetId.value.bytes),
    ) { cursor -> cursor.stableId("uid") }
    if (actual != expected.value) {
        abort(app.ledger.finance.domain.DomainViolation.StaleExpectedRevision)
    }
}

private object LoanPreconditionVerifier {
    fun verify(connection: androidx.sqlite.db.SupportSQLiteDatabase, command: FinancialCommand) {
        val mutation = when (command) {
            is SaveLoanContractCommand -> command.mutation
            is ApplyLoanPaymentCommand -> command.mutation
            else -> return
        }
        val actualCommit = connection.queryOne(
            "SELECT bc.uid FROM loan_contract lc LEFT JOIN book_commit bc ON bc.id=lc.last_commit_id WHERE lc.uid=?",
            arrayOf(mutation.contract.id.value.bytes),
        ) { it.nullableStableId("uid") }
        if (actualCommit != mutation.expectedLastCommitId?.value) {
            abort(app.ledger.finance.domain.DomainViolation.StaleExpectedRevision)
        }
        val account = connection.queryOne(
            "SELECT type,currency_code FROM user_account WHERE uid=?",
            arrayOf(mutation.contract.displayAccountId.value.bytes),
        ) { it.getInt(0) to it.getString(1) } ?: abort(FinanceDataError.CorruptData)
        if (account.first != LOAN_ACCOUNT_TYPE || account.second != mutation.contract.currency.value) {
            abort(app.ledger.finance.domain.DomainViolation.InvalidField("loan.displayAccount"))
        }
        mutation.tranches.forEach { trancheMutation ->
            val ledger = connection.queryOne(
                "SELECT account_class,currency_code FROM ledger_account WHERE uid=?",
                arrayOf(trancheMutation.tranche.ledgerAccountId.value.bytes),
            ) { it.getInt(0) to it.getString(1) } ?: abort(FinanceDataError.CorruptData)
            if (ledger.first != LIABILITY_LEDGER_CLASS || ledger.second != mutation.contract.currency.value) {
                abort(app.ledger.finance.domain.DomainViolation.InvalidField("loan.trancheLedger"))
            }
            val actualTerms = connection.queryOne(
                "SELECT ltr.uid FROM loan_tranche lt LEFT JOIN loan_terms_revision ltr ON ltr.tranche_id=lt.id " +
                    "WHERE lt.uid=? ORDER BY ltr.revision_no DESC LIMIT 1",
                arrayOf(trancheMutation.tranche.id.value.bytes),
            ) { it.nullableStableId("uid") }
            if (actualTerms != trancheMutation.expectedTermsRevisionId?.value) {
                abort(app.ledger.finance.domain.DomainViolation.StaleExpectedRevision)
            }
        }
    }
}

private object InstallmentPreconditionVerifier {
    fun verify(
        connection: androidx.sqlite.db.SupportSQLiteDatabase,
        command: FinancialCommand,
    ) {
        val mutation = when (command) {
            is SaveInstallmentPlanCommand -> command.mutation
            is ApplyInstallmentSettlementCommand -> command.mutation
            else -> return
        }
        val actualRevision = connection.queryOne(
            "SELECT ipr.uid FROM installment_plan ip LEFT JOIN installment_plan_revision ipr " +
                "ON ipr.id=ip.current_revision_id WHERE ip.uid=?",
            arrayOf(mutation.plan.id.value.bytes),
        ) { it.nullableStableId("uid") }
        if (actualRevision != mutation.expectedRevisionId?.value) {
            abort(app.ledger.finance.domain.DomainViolation.StaleExpectedRevision)
        }
        if (mutation.expectedRevisionId == null) {
            verifyNewPlan(connection, mutation)
        } else {
            verifyExistingPlan(connection, mutation)
        }
        verifyRefund(connection, mutation)
    }

    private fun verifyNewPlan(
        connection: androidx.sqlite.db.SupportSQLiteDatabase,
        mutation: InstallmentPlanMutation,
    ) {
        val purchase = connection.queryOne(
            "SELECT bt.kind transaction_kind,bt.lifecycle_state lifecycle_state,ua.type account_type," +
                "ua.uid account_uid,ua.currency_code currency_code,ra.amount_minor amount_minor " +
                "FROM business_transaction bt JOIN transaction_revision tr ON tr.id=bt.current_revision_id " +
                "JOIN expense_revision_detail erd ON erd.revision_id=tr.id " +
                "JOIN user_account ua ON ua.id=erd.payer_account_id " +
                "JOIN revision_amount ra ON ra.revision_id=tr.id AND ra.role=0 AND ra.representation=1 " +
                "WHERE bt.uid=?",
            arrayOf(mutation.plan.purchaseTransactionId.value.bytes),
        ) { cursor ->
            InstallmentPurchaseIdentity(
                cursor.namedInt("transaction_kind"),
                cursor.namedInt("lifecycle_state"),
                cursor.namedInt("account_type"),
                cursor.stableId("account_uid"),
                cursor.namedString("currency_code"),
                cursor.namedLong("amount_minor"),
            )
        } ?: abort(app.ledger.finance.domain.DomainViolation.InvalidField("installment.purchase"))
        val expected = InstallmentPurchaseIdentity(
            EXPENSE_TRANSACTION_KIND,
            ACTIVE_TRANSACTION_LIFECYCLE,
            CREDIT_ACCOUNT_TYPE,
            mutation.plan.creditAccountId.value,
            mutation.plan.currency.value,
            mutation.plan.originalPrincipalMinor,
        )
        if (purchase != expected) {
            abort(app.ledger.finance.domain.DomainViolation.InvalidField("installment.purchase"))
        }
        val duplicate = connection.queryOne(
            "SELECT COUNT(*) row_count FROM installment_plan " +
                "WHERE purchase_transaction_id=(SELECT id FROM business_transaction WHERE uid=?)",
            arrayOf(mutation.plan.purchaseTransactionId.value.bytes),
        ) { it.namedLong("row_count") } ?: 0L
        if (duplicate != 0L) {
            abort(app.ledger.finance.domain.DomainViolation.InvalidField("installment.purchase.duplicate"))
        }
    }

    private fun verifyExistingPlan(
        connection: androidx.sqlite.db.SupportSQLiteDatabase,
        mutation: InstallmentPlanMutation,
    ) {
        val immutable = connection.queryOne(
            "SELECT bt.uid purchase_uid,ua.uid account_uid,ip.currency_code currency_code," +
                "ip.original_principal_minor original_principal_minor " +
                "FROM installment_plan ip JOIN business_transaction bt ON bt.id=ip.purchase_transaction_id " +
                "JOIN user_account ua ON ua.id=ip.credit_account_id WHERE ip.uid=?",
            arrayOf(mutation.plan.id.value.bytes),
        ) { cursor ->
            InstallmentImmutableIdentity(
                cursor.stableId("purchase_uid"),
                cursor.stableId("account_uid"),
                cursor.namedString("currency_code"),
                cursor.namedLong("original_principal_minor"),
            )
        } ?: abort(FinanceDataError.CorruptData)
        val expected = InstallmentImmutableIdentity(
            mutation.plan.purchaseTransactionId.value,
            mutation.plan.creditAccountId.value,
            mutation.plan.currency.value,
            mutation.plan.originalPrincipalMinor,
        )
        if (immutable != expected) {
            abort(app.ledger.finance.domain.DomainViolation.InvalidField("installment.immutableIdentity"))
        }
    }

    private fun verifyRefund(
        connection: androidx.sqlite.db.SupportSQLiteDatabase,
        mutation: InstallmentPlanMutation,
    ) {
        val allocation = mutation.refundAllocation ?: return
        val valid = connection.queryOne(
            "SELECT COUNT(*) row_count FROM business_transaction bt " +
                "JOIN transaction_revision tr ON tr.transaction_id=bt.id " +
                "WHERE bt.uid=? AND tr.uid=? AND bt.kind=?",
            arrayOf(
                allocation.refundTransactionId.value.bytes,
                allocation.refundRevisionId.value.bytes,
                REFUND_TRANSACTION_KIND,
            ),
        ) { it.namedLong("row_count") } == 1L
        if (!valid) abort(app.ledger.finance.domain.DomainViolation.InvalidField("installment.refund"))
    }
}

private data class InstallmentPurchaseIdentity(
    val transactionKind: Int,
    val lifecycleState: Int,
    val accountType: Int,
    val accountId: StableId,
    val currency: String,
    val amountMinor: Long,
)

private data class InstallmentImmutableIdentity(
    val purchaseId: StableId,
    val accountId: StableId,
    val currency: String,
    val originalPrincipalMinor: Long,
)

private fun android.database.Cursor.namedInt(name: String): Int = getInt(getColumnIndexOrThrow(name))

private fun android.database.Cursor.namedLong(name: String): Long = getLong(getColumnIndexOrThrow(name))

private fun android.database.Cursor.namedString(name: String): String = getString(getColumnIndexOrThrow(name))

private data class BookWriteState(
    val headCommitId: Long?,
    val localRevision: Long,
    val valuationRevision: Long,
    val ruleSetVersion: Int,
    val state: Int,
    val defaultZoneId: String,
)

private const val CREDIT_ACCOUNT_TYPE = 2
private const val EXPENSE_TRANSACTION_KIND = 0
private const val ACTIVE_TRANSACTION_LIFECYCLE = 0
private const val REFUND_TRANSACTION_KIND = 3
private const val LOAN_ACCOUNT_TYPE = 3
private const val LIABILITY_LEDGER_CLASS = 1

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
    is SaveCreditProfileCommand,
    is SaveCreditStatementCommand,
    is SaveInstallmentPlanCommand,
    is SaveLoanContractCommand,
    is MergeRestoreCommand,
    is BatchFinancialCommand,
    -> null
}
