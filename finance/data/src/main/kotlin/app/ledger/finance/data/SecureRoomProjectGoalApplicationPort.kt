@file:Suppress("LongMethod", "LongParameterList", "MaxLineLength", "TooManyFunctions")

package app.ledger.finance.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.database.LedgerDatabase
import app.ledger.core.money.CurrencyCode
import app.ledger.core.money.Money
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.core.time.EffectiveTime
import app.ledger.finance.application.ChangeProjectStatusRequest
import app.ledger.finance.application.CompleteGoalRequest
import app.ledger.finance.application.DefaultFinancialMutationCoordinator
import app.ledger.finance.application.FinanceDataError
import app.ledger.finance.application.FinancialPlanningPort
import app.ledger.finance.application.FinancialPlanningSnapshotRepository
import app.ledger.finance.application.GoalCompletionStrategy
import app.ledger.finance.application.GoalDraft
import app.ledger.finance.application.GoalMovementView
import app.ledger.finance.application.GoalTrendPoint
import app.ledger.finance.application.GoalView
import app.ledger.finance.application.LedgerWriteGate
import app.ledger.finance.application.PlanningAccountView
import app.ledger.finance.application.PlanningProjectionReadiness
import app.ledger.finance.application.ProjectCashflowPoint
import app.ledger.finance.application.ProjectDraft
import app.ledger.finance.application.ProjectGoalApplicationPort
import app.ledger.finance.application.ProjectGoalSnapshot
import app.ledger.finance.application.ProjectSettlementView
import app.ledger.finance.application.ProjectTransactionCursor
import app.ledger.finance.application.ProjectTransactionPage
import app.ledger.finance.application.ProjectTransactionPageRequest
import app.ledger.finance.application.ProjectTransactionView
import app.ledger.finance.application.ProjectView
import app.ledger.finance.application.RecordGoalMovementRequest
import app.ledger.finance.application.ReferenceDataManagementPort
import app.ledger.finance.application.ReferenceMutation
import app.ledger.finance.application.ReferenceMutationCommand
import app.ledger.finance.application.ReferenceMutationIds
import app.ledger.finance.application.SaveGoalRequest
import app.ledger.finance.application.SaveProjectRequest
import app.ledger.finance.domain.BookCommitId
import app.ledger.finance.domain.CanonicalFinancialHash
import app.ledger.finance.domain.CommandReceipt
import app.ledger.finance.domain.DeterministicFinancialPlanner
import app.ledger.finance.domain.DeviceInstanceId
import app.ledger.finance.domain.EffectPolarity
import app.ledger.finance.domain.Goal
import app.ledger.finance.domain.GoalEffectId
import app.ledger.finance.domain.GoalEffectKind
import app.ledger.finance.domain.GoalId
import app.ledger.finance.domain.GoalMovement
import app.ledger.finance.domain.GoalMovementId
import app.ledger.finance.domain.GoalMovementKind
import app.ledger.finance.domain.GoalStatus
import app.ledger.finance.domain.LocalRevision
import app.ledger.finance.domain.PlanningOperationContext
import app.ledger.finance.domain.PlanningSnapshot
import app.ledger.finance.domain.PositiveMoney
import app.ledger.finance.domain.ProjectStatus
import app.ledger.finance.domain.RecordGoalMovementCommand
import app.ledger.finance.domain.RowVersion
import app.ledger.finance.domain.TransactionKind
import app.ledger.finance.domain.UserAccountId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val RECENT_TRANSACTION_LIMIT = 3

class SecureRoomProjectGoalApplicationPort(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
    private val references: ReferenceDataManagementPort = SecureRoomReferenceDataManagementPort(context, keyProvider),
    private val databaseName: String = EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME,
) : ProjectGoalApplicationPort {
    private val applicationContext = context.applicationContext
    private val writeGate: LedgerWriteGate = ProjectGoalWriteGate()

    override suspend fun snapshot(bookId: StableId): DomainResult<ProjectGoalSnapshot> = withDatabase(bookId) { database ->
        database.readLedger { db -> DomainResult.Success(readSnapshot(db, bookId)) }
    }

    override suspend fun projectTransactionPage(request: ProjectTransactionPageRequest): DomainResult<ProjectTransactionPage> = withDatabase(request.bookId) { database ->
        database.readLedger { db ->
            val book = RoomBookRepository.mapCurrent(db)
            if (book.id.value != request.bookId) abort(FinanceDataError.CorruptData)
            DomainResult.Success(projectTransactions(db, request.projectId, request.cursor, request.limit))
        }
    }

    override suspend fun saveProject(request: SaveProjectRequest): DomainResult<Unit> = references.mutate(
        ReferenceMutationCommand(
            request.ids.toReferenceIds(request.changedAt),
            ReferenceMutation.SaveProject(
                ProjectDraft(
                    request.projectId,
                    request.expectedRowVersion,
                    request.name,
                    request.description,
                    request.startDate,
                    request.endDate,
                    request.budgetBaseMinor,
                    request.includedInMonthlyBudget,
                    request.goalId,
                    request.status,
                ),
            ),
        ),
    )

    override suspend fun saveGoal(request: SaveGoalRequest): DomainResult<Unit> = references.mutate(
        ReferenceMutationCommand(
            request.ids.toReferenceIds(request.changedAt),
            ReferenceMutation.SaveGoal(
                GoalDraft(
                    request.goalId,
                    request.expectedRowVersion,
                    request.accountId,
                    request.name,
                    request.targetAmountMinor,
                    request.dueDate,
                    request.suggestedMonthlyAmountMinor,
                    request.status,
                ),
            ),
        ),
    )

    override suspend fun changeProjectStatus(request: ChangeProjectStatusRequest): DomainResult<Unit> = references.mutate(
        ReferenceMutationCommand(
            request.ids.toReferenceIds(request.changedAt),
            ReferenceMutation.ChangeProjectStatus(
                request.projectId,
                request.expectedRowVersion,
                request.status,
            ),
        ),
    )

    override suspend fun recordGoalMovement(request: RecordGoalMovementRequest): DomainResult<CommandReceipt> = withDatabase(request.ids.bookId) { database ->
        val book = database.readLedger(RoomBookRepository::mapCurrent)
        val stored = database.readLedger { currentGoal(it, request.goalId) }
            ?: return@withDatabase DomainResult.Failure(FinanceDataError.CorruptData)
        val rowVersion = RowVersion.of(request.expectedGoalRowVersion).valueOrAbort()
        if (stored.goal.rowVersion != rowVersion) {
            return@withDatabase DomainResult.Failure(app.ledger.finance.domain.DomainViolation.StaleExpectedRevision)
        }
        val movement = GoalMovement(
            GoalMovementId(request.ids.movementId),
            stored.goal.id,
            request.kind,
            PositiveMoney.from(Money(request.amountMinor, stored.goal.currency)).valueOrAbort(),
            EffectiveTime.fromInstant(request.occurredAt, stored.zoneId),
            null,
            null,
            null,
            BookCommitId(request.ids.commitId),
        )
        val draft = RecordGoalMovementCommand(
            request.ids.commandId,
            zeroHash(),
            movement,
            GoalEffectId(request.ids.effectId),
            rowVersion,
        )
        val command = draft.copy(payloadHash = CanonicalFinancialHash.command(draft))
        val operation = PlanningOperationContext(
            BookCommitId(request.ids.commitId),
            request.changedAt,
            DeviceInstanceId(request.ids.deviceInstanceId),
        )
        execute(
            database,
            command,
            PlanningSnapshot(
                book,
                null,
                null,
                emptyList(),
                emptySet(),
                emptyList(),
                null,
                emptyList(),
                operationContext = operation,
                goal = stored.goal,
                goalBalanceMinor = stored.balanceMinor,
            ),
        )
    }

    @Suppress("ReturnCount")
    override suspend fun completeGoal(request: CompleteGoalRequest): DomainResult<Unit> {
        var current = when (val loaded = snapshot(request.ids.bookId)) {
            is DomainResult.Failure -> return loaded
            is DomainResult.Success -> loaded.value
        }
        val goal = current.goals.singleOrNull { it.id == request.goalId }
            ?: return DomainResult.Failure(FinanceDataError.CorruptData)
        if (goal.rowVersion != request.expectedRowVersion) {
            return DomainResult.Failure(app.ledger.finance.domain.DomainViolation.StaleExpectedRevision)
        }
        if (request.strategy == GoalCompletionStrategy.RELEASE && goal.balanceMinor > 0L) {
            val movementIds = requireNotNull(request.movementIds)
            when (
                val result = recordGoalMovement(
                    RecordGoalMovementRequest(
                        movementIds,
                        request.goalId,
                        request.expectedRowVersion,
                        GoalMovementKind.RELEASE,
                        goal.balanceMinor,
                        request.changedAt,
                        request.changedAt,
                    ),
                )
            ) {
                is DomainResult.Failure -> return result
                is DomainResult.Success -> Unit
            }
            current = when (val loaded = snapshot(request.ids.bookId)) {
                is DomainResult.Failure -> return loaded
                is DomainResult.Success -> loaded.value
            }
        }
        val refreshed = current.goals.single { it.id == request.goalId }
        val targetStatus = if (request.strategy == GoalCompletionStrategy.CONTINUE) GoalStatus.ACTIVE else GoalStatus.COMPLETED
        return saveGoal(
            SaveGoalRequest(
                request.ids.copy(expectedLocalRevision = current.localRevision),
                refreshed.id,
                refreshed.rowVersion,
                refreshed.accountId,
                refreshed.name,
                refreshed.targetAmountMinor,
                refreshed.dueDate,
                refreshed.suggestedMonthlyAmountMinor,
                targetStatus,
                request.changedAt,
            ),
        )
    }

    private suspend fun execute(
        database: LedgerDatabase,
        command: RecordGoalMovementCommand,
        snapshot: PlanningSnapshot,
    ): DomainResult<CommandReceipt> {
        val repository = RoomFinancialCommitRepository(database)
        return DefaultFinancialMutationCoordinator(
            writeGate,
            repository,
            object : FinancialPlanningSnapshotRepository {
                override suspend fun load(command: app.ledger.finance.domain.FinancialCommand): DomainResult<PlanningSnapshot> = DomainResult.Success(snapshot)
            },
            FinancialPlanningPort(DeterministicFinancialPlanner::plan),
            repository,
        ).execute(command)
    }

    private fun readSnapshot(db: SupportSQLiteDatabase, bookId: StableId): ProjectGoalSnapshot {
        val book = RoomBookRepository.mapCurrent(db)
        if (book.id.value != bookId) abort(FinanceDataError.CorruptData)
        val stale = !db.isProjectionFamilyCurrent(app.ledger.finance.application.ProjectionFamily.PROJECT, book.localRevision) ||
            !db.isProjectionFamilyCurrent(app.ledger.finance.application.ProjectionFamily.GOAL, book.localRevision)
        if (stale) {
            return ProjectGoalSnapshot(bookId, book.baseCurrency, book.localRevision, PlanningProjectionReadiness.FAILED, emptyList(), emptyList(), emptyList())
        }
        val accounts = accounts(db)
        return ProjectGoalSnapshot(
            bookId,
            book.baseCurrency,
            book.localRevision,
            PlanningProjectionReadiness.CURRENT,
            accounts,
            projects(db),
            goals(db, accounts),
        )
    }

    private fun accounts(db: SupportSQLiteDatabase): List<PlanningAccountView> = db.queryList(
        """
        SELECT ua.uid,ua.name,ua.currency_code,COALESCE(abc.normal_balance_minor,0) actual,
          COALESCE(SUM(CASE WHEN g.status<>? THEN gbp.balance_minor ELSE 0 END),0) reserved
        FROM user_account ua LEFT JOIN account_balance_current abc ON abc.account_id=ua.id
          LEFT JOIN goal g ON g.account_id=ua.id LEFT JOIN goal_balance_projection gbp ON gbp.goal_id=g.id
        WHERE ua.status=0 GROUP BY ua.id,ua.uid,ua.name,ua.currency_code,abc.normal_balance_minor
        ORDER BY ua.sort_order,ua.id
        """.trimIndent(),
        arrayOf(GoalStatus.ARCHIVED.ordinal),
    ) { cursor ->
        val actual = cursor.long("actual")
        val reserved = cursor.long("reserved")
        val available = Math.subtractExact(actual, reserved)
        PlanningAccountView(cursor.stableId("uid"), cursor.string("name"), currency(cursor.string("currency_code")), actual, reserved, available, available < 0L)
    }

    private fun projects(db: SupportSQLiteDatabase): List<ProjectView> = db.queryList(
        """
        SELECT p.uid,p.name,p.description,p.start_date,p.end_date,p.budget_base_minor,p.included_in_monthly_budget,
          g.uid goal_uid,g.name goal_name,p.status,p.row_version,
          COALESCE(pup.used_base_minor,0) used,COALESCE(pup.restored_base_minor,0) restored,
          COALESCE(pup.remaining_base_minor,p.budget_base_minor) remaining,
          COALESCE(pup.cash_inflow_base_minor,0) cash_in,COALESCE(pup.cash_outflow_base_minor,0) cash_out
        FROM project p LEFT JOIN goal g ON g.id=p.goal_id LEFT JOIN project_usage_projection pup ON pup.project_id=p.id
        ORDER BY p.status,p.start_date DESC,p.id
        """.trimIndent(),
    ) { cursor ->
        val id = cursor.stableId("uid")
        ProjectView(
            id, cursor.string("name"), cursor.nullableString("description"), cursor.int("start_date").toStoredLocalDate(),
            cursor.nullableLong("end_date")?.toInt()?.toStoredLocalDate(), cursor.long("budget_base_minor"),
            cursor.int("included_in_monthly_budget") == 1, cursor.nullableStableId("goal_uid"), cursor.nullableString("goal_name"),
            ProjectStatus.entries[cursor.int("status")], cursor.long("row_version"), cursor.long("used"), cursor.long("restored"),
            cursor.long("remaining"), cursor.long("cash_in"), cursor.long("cash_out"), projectTransactions(db, id, null, RECENT_TRANSACTION_LIMIT).items,
            projectCashflow(db, id), projectSettlements(db, id),
        )
    }

    private fun projectTransactions(
        db: SupportSQLiteDatabase,
        projectId: StableId,
        cursor: ProjectTransactionCursor?,
        limit: Int,
    ): ProjectTransactionPage {
        val cursorClause = if (cursor == null) "" else "AND (ctp.occurred_at<? OR (ctp.occurred_at=? AND ctp.transaction_uid<?))"
        val arguments = buildList<Any> {
            add(projectId.bytes)
            if (cursor != null) {
                add(cursor.occurredAtEpochMilli)
                add(cursor.occurredAtEpochMilli)
                add(cursor.transactionId.bytes)
            }
            add(limit + 1)
        }.toTypedArray()
        val loaded = db.queryList(
            """
            SELECT ctp.transaction_uid,tr.uid revision_uid,ctp.occurred_at,ctp.local_date,ctp.kind,
              COALESCE(SUM(CASE pe.kind WHEN 0 THEN pe.polarity*pe.base_amount_minor WHEN 1 THEN -pe.polarity*pe.base_amount_minor ELSE 0 END),0) amount,
              MAX(CASE WHEN pe.kind=1 AND pe.polarity=1 THEN 1 ELSE 0 END) restored
            FROM current_transaction_projection ctp JOIN transaction_revision tr ON tr.id=ctp.current_revision_id
              JOIN project p ON p.id=ctp.project_id LEFT JOIN project_effect pe ON pe.source_revision_id=tr.id
            WHERE p.uid=? AND ctp.state=0 $cursorClause
            GROUP BY ctp.transaction_id,tr.id ORDER BY ctp.occurred_at DESC,ctp.transaction_uid DESC LIMIT ?
            """.trimIndent(),
            arguments,
        ) { row ->
            ProjectTransactionView(
                row.stableId("transaction_uid"),
                row.stableId("revision_uid"),
                Instant.ofEpochMilli(row.long("occurred_at")),
                row.int("local_date").toStoredLocalDate(),
                TransactionKind.entries[row.int("kind")],
                row.long("amount"),
                row.int("restored") == 1,
            )
        }
        val items = loaded.take(limit)
        val next = items.lastOrNull()?.takeIf { loaded.size > limit }?.let { ProjectTransactionCursor(it.occurredAt.toEpochMilli(), it.id) }
        return ProjectTransactionPage(items, next)
    }

    private fun projectCashflow(db: SupportSQLiteDatabase, projectId: StableId): List<ProjectCashflowPoint> = db.queryList(
        """
        SELECT ee.accrual_local_date,
          COALESCE(SUM(CASE WHEN ee.nature=1 THEN ee.polarity*ee.base_amount_minor ELSE 0 END),0) expense,
          COALESCE(SUM(CASE WHEN ee.nature=0 THEN ee.polarity*ee.base_amount_minor ELSE 0 END),0) income
        FROM economic_effect ee JOIN project p ON p.id=ee.project_id WHERE p.uid=?
        GROUP BY ee.accrual_local_date ORDER BY ee.accrual_local_date
        """.trimIndent(),
        arrayOf(projectId.bytes),
    ) { cursor ->
        val expense = cursor.long("expense")
        val income = cursor.long("income")
        ProjectCashflowPoint(cursor.int("accrual_local_date").toStoredLocalDate(), expense, income, Math.subtractExact(income, expense))
    }

    private fun projectSettlements(db: SupportSQLiteDatabase, projectId: StableId): List<ProjectSettlementView> = db.queryList(
        "SELECT sa.uid,sa.name,sa.requires_additional_settlement FROM settlement_activity sa JOIN project p ON p.id=sa.project_id WHERE p.uid=? ORDER BY sa.start_date,sa.id",
        arrayOf(projectId.bytes),
    ) { cursor -> ProjectSettlementView(cursor.stableId("uid"), cursor.string("name"), cursor.int("requires_additional_settlement") == 1) }

    private fun goals(db: SupportSQLiteDatabase, accounts: List<PlanningAccountView>): List<GoalView> = db.queryList(
        """
        SELECT g.uid,ua.uid account_uid,ua.name account_name,g.name,g.target_amount_minor,ua.currency_code,g.due_date,
          g.suggested_monthly_minor,g.status,g.row_version,gbp.balance_minor
        FROM goal g JOIN user_account ua ON ua.id=g.account_id JOIN goal_balance_projection gbp ON gbp.goal_id=g.id
        ORDER BY ua.sort_order,g.status,g.due_date IS NULL,g.due_date,g.id
        """.trimIndent(),
    ) { cursor ->
        val id = cursor.stableId("uid")
        val accountId = cursor.stableId("account_uid")
        val account = accounts.singleOrNull { it.id == accountId } ?: abort(FinanceDataError.CorruptData)
        GoalView(
            id, accountId, cursor.string("account_name"), cursor.string("name"), cursor.long("target_amount_minor"), currency(cursor.string("currency_code")),
            cursor.nullableLong("due_date")?.toInt()?.toStoredLocalDate(), cursor.nullableLong("suggested_monthly_minor"), GoalStatus.entries[cursor.int("status")],
            cursor.long("row_version"), cursor.long("balance_minor"), account.actualBalanceMinor, account.reservedMinor, account.availableMinor, account.underfunded,
            db.queryList("SELECT p.uid FROM project p JOIN goal g ON g.id=p.goal_id WHERE g.uid=? ORDER BY p.id", arrayOf(id.bytes)) { it.stableId("uid") },
            goalMovements(db, id), goalTrend(db, id),
        )
    }

    private fun goalMovements(db: SupportSQLiteDatabase, goalId: StableId): List<GoalMovementView> = db.queryList(
        "SELECT gm.uid,gm.kind,gm.amount_minor,gm.occurred_at,reversed.uid reversed_uid FROM goal_movement gm JOIN goal g ON g.id=gm.goal_id LEFT JOIN goal_movement reversed ON reversed.id=gm.reversal_of_id WHERE g.uid=? ORDER BY gm.occurred_at DESC,gm.id DESC",
        arrayOf(goalId.bytes),
    ) { cursor -> GoalMovementView(cursor.stableId("uid"), GoalMovementKind.entries[cursor.int("kind")], cursor.long("amount_minor"), cursor.long("occurred_at").toStoredInstant(), cursor.nullableStableId("reversed_uid")) }

    private fun goalTrend(db: SupportSQLiteDatabase, goalId: StableId): List<GoalTrendPoint> {
        val rows = db.queryList(
            """
            SELECT COALESCE(tr.local_date,CAST(strftime('%Y%m%d',gm.occurred_at/1000,'unixepoch') AS INTEGER)) local_date,
              ge.kind,ge.polarity,ge.amount_minor
            FROM goal_effect ge JOIN goal g ON g.id=ge.goal_id
              LEFT JOIN transaction_revision tr ON tr.id=ge.source_revision_id LEFT JOIN goal_movement gm ON gm.id=ge.goal_movement_id
            WHERE g.uid=? ORDER BY local_date,ge.id
            """.trimIndent(),
            arrayOf(goalId.bytes),
        ) { cursor -> GoalDelta(cursor.int("local_date").toStoredLocalDate(), GoalEffectKind.entries[cursor.int("kind")], if (cursor.int("polarity") == 1) EffectPolarity.APPLY else EffectPolarity.REVERSE, cursor.long("amount_minor")) }
        var balance = 0L
        return rows.map { delta ->
            val positive = delta.kind in setOf(GoalEffectKind.ALLOCATE, GoalEffectKind.RESTORE, GoalEffectKind.ADJUST)
            val signed = if (positive) delta.amount else Math.negateExact(delta.amount)
            balance = Math.addExact(balance, if (delta.polarity == EffectPolarity.APPLY) signed else Math.negateExact(signed))
            GoalTrendPoint(delta.date, balance)
        }
    }

    private fun currentGoal(db: SupportSQLiteDatabase, goalId: StableId): StoredGoal? = db.queryOne(
        """
        SELECT g.uid,ua.uid account_uid,g.name,g.target_amount_minor,ua.currency_code,g.due_date,g.suggested_monthly_minor,
          g.status,g.row_version,bc.uid commit_uid,b.default_zone_id,COALESCE(gbp.balance_minor,0) balance_minor
        FROM goal g JOIN user_account ua ON ua.id=g.account_id JOIN book_commit bc ON bc.id=g.last_commit_id JOIN book b ON b.id=1
          LEFT JOIN goal_balance_projection gbp ON gbp.goal_id=g.id
        WHERE g.uid=?
        """.trimIndent(),
        arrayOf(goalId.bytes),
    ) { cursor ->
        StoredGoal(
            Goal(
                GoalId(cursor.stableId("uid")), UserAccountId(cursor.stableId("account_uid")), cursor.string("name"), cursor.long("target_amount_minor"),
                currency(cursor.string("currency_code")), cursor.nullableLong("due_date")?.toInt()?.toStoredLocalDate(), cursor.nullableLong("suggested_monthly_minor"),
                GoalStatus.entries[cursor.int("status")], BookCommitId(cursor.stableId("commit_uid")), RowVersion.of(cursor.long("row_version")).valueOrAbort(),
            ),
            ZoneId.of(cursor.string("default_zone_id")),
            cursor.long("balance_minor"),
        )
    }

    private suspend fun <T> withDatabase(
        bookId: StableId,
        block: suspend (LedgerDatabase) -> DomainResult<T>,
    ): DomainResult<T> = try {
        keyProvider.open(bookId).use { keys ->
            val database = keys.databaseDek.useBytes { openSelectedLedger(applicationContext, it, databaseName) }
            try {
                block(database)
            } finally {
                database.close()
            }
        }
    } catch (abort: FinancialPersistenceAbort) {
        DomainResult.Failure(abort.domainError)
    } catch (_: ArithmeticException) {
        DomainResult.Failure(FinanceDataError.NumericRangeExceeded)
    } catch (failure: Exception) {
        DomainResult.Failure(failure.toFinanceDatabaseError())
    }

    private fun app.ledger.finance.application.PlanningMutationIds.toReferenceIds(changedAt: Instant) = ReferenceMutationIds(
        bookId,
        expectedLocalRevision.value,
        commitId,
        listOf(entityRevisionId),
        deviceInstanceId,
        changedAt,
    )

    private fun zeroHash() = app.ledger.finance.domain.Hash256.fromBytes(ByteArray(HASH_SIZE_BYTES)).valueOrAbort()
    private fun currency(code: String): CurrencyCode = CurrencyCode.parse(code).valueOrAbort()
    private data class StoredGoal(val goal: Goal, val zoneId: ZoneId, val balanceMinor: Long)
    private data class GoalDelta(val date: LocalDate, val kind: GoalEffectKind, val polarity: EffectPolarity, val amount: Long)
}

private class ProjectGoalWriteGate : LedgerWriteGate {
    private val mutex = Mutex()
    override suspend fun <T> execute(block: suspend () -> T): T = mutex.withLock { block() }
}

private const val HASH_SIZE_BYTES = 32

private fun android.database.Cursor.int(name: String): Int = getInt(getColumnIndexOrThrow(name))
private fun android.database.Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
private fun android.database.Cursor.string(name: String): String = getString(getColumnIndexOrThrow(name))
