@file:Suppress("LongMethod", "LongParameterList", "MaxLineLength", "TooManyFunctions")

package app.ledger.finance.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.database.LedgerDatabase
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.finance.application.BudgetAdjustmentView
import app.ledger.finance.application.BudgetApplicationPort
import app.ledger.finance.application.BudgetCategoryLimitDraft
import app.ledger.finance.application.BudgetCategoryReference
import app.ledger.finance.application.BudgetCompositionView
import app.ledger.finance.application.BudgetMutationIds
import app.ledger.finance.application.BudgetProjectionReadiness
import app.ledger.finance.application.BudgetRevisionView
import app.ledger.finance.application.BudgetSnapshot
import app.ledger.finance.application.BudgetTemplateView
import app.ledger.finance.application.DefaultFinancialMutationCoordinator
import app.ledger.finance.application.FinanceDataError
import app.ledger.finance.application.FinancialPlanningPort
import app.ledger.finance.application.FinancialPlanningSnapshotRepository
import app.ledger.finance.application.LedgerWriteGate
import app.ledger.finance.application.RecordBudgetAdjustmentRequest
import app.ledger.finance.application.SaveBudgetMonthRequest
import app.ledger.finance.application.SaveBudgetTemplateRequest
import app.ledger.finance.domain.BookCommitId
import app.ledger.finance.domain.BudgetAdjustment
import app.ledger.finance.domain.BudgetAdjustmentId
import app.ledger.finance.domain.BudgetAdjustmentKind
import app.ledger.finance.domain.BudgetAdjustmentScope
import app.ledger.finance.domain.BudgetMonth
import app.ledger.finance.domain.BudgetMonthId
import app.ledger.finance.domain.BudgetMonthMutation
import app.ledger.finance.domain.BudgetMonthRevision
import app.ledger.finance.domain.BudgetMonthRevisionId
import app.ledger.finance.domain.BudgetTemplate
import app.ledger.finance.domain.BudgetTemplateId
import app.ledger.finance.domain.BudgetTemplateMutation
import app.ledger.finance.domain.BudgetTemplateRevision
import app.ledger.finance.domain.BudgetTemplateRevisionId
import app.ledger.finance.domain.CanonicalFinancialHash
import app.ledger.finance.domain.CategoryBudgetLimit
import app.ledger.finance.domain.CategoryId
import app.ledger.finance.domain.ConfigureBudgetMonthCommand
import app.ledger.finance.domain.DailyAvailableBudgetPolicy
import app.ledger.finance.domain.DeterministicFinancialPlanner
import app.ledger.finance.domain.DeviceInstanceId
import app.ledger.finance.domain.EntityStatus
import app.ledger.finance.domain.FinancialCommand
import app.ledger.finance.domain.LocalRevision
import app.ledger.finance.domain.PlanningOperationContext
import app.ledger.finance.domain.PlanningSnapshot
import app.ledger.finance.domain.RecordBudgetAdjustmentsCommand
import app.ledger.finance.domain.SaveBudgetTemplateCommand
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.YearMonth

class SecureRoomBudgetApplicationPort(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
    private val databaseName: String = EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME,
) : BudgetApplicationPort {
    private val applicationContext = context.applicationContext
    private val writeGate: LedgerWriteGate = BudgetWriteGate()

    override suspend fun snapshot(bookId: StableId, month: YearMonth, today: LocalDate): DomainResult<BudgetSnapshot> = withDatabase(bookId) { database -> database.readLedger { DomainResult.Success(readSnapshot(it, bookId, month, today)) } }

    override suspend fun saveMonth(request: SaveBudgetMonthRequest) = withDatabase(request.ids.bookId) { database ->
        val book = database.readLedger(RoomBookRepository::mapCurrent)
        val current = database.readLedger { currentBudgetRevision(it, request.month) }
        val expected = request.expectedRevisionId?.let { revisionId -> database.readLedger { budgetRevision(it, revisionId) } }
        if (request.expectedRevisionId != null && expected == null) return@withDatabase DomainResult.Failure(app.ledger.finance.domain.DomainViolation.StaleExpectedRevision)
        val limits = database.readLedger { mapLimits(it, request.limits) }
        val monthId = expected?.budgetMonthId ?: BudgetMonthId(request.ids.entityId)
        val revision = BudgetMonthRevision(
            BudgetMonthRevisionId(request.ids.revisionId),
            monthId,
            (expected?.revisionNumber ?: 0) + 1,
            request.totalBaseMinor,
            limits,
            request.sourceTemplateRevisionId?.let(::BudgetTemplateRevisionId),
            BookCommitId(request.ids.commitId),
        )
        val mutation = BudgetMonthMutation(
            BudgetMonth(monthId, request.month, revision.id),
            revision,
            request.expectedRevisionId?.let(::BudgetMonthRevisionId),
        )
        val draft = ConfigureBudgetMonthCommand(request.ids.commandId, zeroHash(), mutation)
        execute(database, draft.copy(payloadHash = CanonicalFinancialHash.command(draft)), planning(book, current?.second, null, request.ids, request.createdAt))
    }

    override suspend fun saveTemplate(request: SaveBudgetTemplateRequest) = withDatabase(request.ids.bookId) { database ->
        val book = database.readLedger(RoomBookRepository::mapCurrent)
        val current = database.readLedger { currentTemplateRevision(it, request.ids.entityId) }
        val expected = request.expectedRevisionId?.let { revisionId -> database.readLedger { templateRevision(it, revisionId) } }
        if (request.expectedRevisionId != null && expected == null) return@withDatabase DomainResult.Failure(app.ledger.finance.domain.DomainViolation.StaleExpectedRevision)
        val limits = database.readLedger { mapLimits(it, request.limits) }
        val templateId = expected?.templateId ?: BudgetTemplateId(request.ids.entityId)
        val revision = BudgetTemplateRevision(
            BudgetTemplateRevisionId(request.ids.revisionId),
            templateId,
            (expected?.revisionNumber ?: 0) + 1,
            request.totalBaseMinor,
            limits,
            BookCommitId(request.ids.commitId),
        )
        val mutation = BudgetTemplateMutation(
            BudgetTemplate(templateId, request.name.trim(), revision.id, request.status),
            revision,
            request.expectedRevisionId?.let(::BudgetTemplateRevisionId),
        )
        val draft = SaveBudgetTemplateCommand(request.ids.commandId, zeroHash(), mutation)
        execute(database, draft.copy(payloadHash = CanonicalFinancialHash.command(draft)), planning(book, null, current?.second, request.ids, request.createdAt))
    }

    override suspend fun recordAdjustment(request: RecordBudgetAdjustmentRequest) = withDatabase(request.ids.bookId) { database ->
        val book = database.readLedger(RoomBookRepository::mapCurrent)
        val adjustments = database.readLedger { buildAdjustments(it, request) }
        val draft = RecordBudgetAdjustmentsCommand(request.ids.commandId, zeroHash(), adjustments)
        execute(database, draft.copy(payloadHash = CanonicalFinancialHash.command(draft)), planning(book, null, null, request.ids, request.createdAt))
    }

    private suspend fun execute(database: LedgerDatabase, command: FinancialCommand, snapshot: PlanningSnapshot) = RoomFinancialCommitRepository(database).let { repository ->
        DefaultFinancialMutationCoordinator(
            writeGate,
            repository,
            object : FinancialPlanningSnapshotRepository {
                override suspend fun load(command: FinancialCommand): DomainResult<PlanningSnapshot> = DomainResult.Success(snapshot)
            },
            FinancialPlanningPort(DeterministicFinancialPlanner::plan),
            repository,
        ).execute(command)
    }

    private fun planning(
        book: app.ledger.finance.domain.Book,
        month: BudgetMonthRevision?,
        template: BudgetTemplateRevision?,
        ids: BudgetMutationIds,
        createdAt: java.time.Instant,
    ) = PlanningSnapshot(
        book, null, null, emptyList(), emptySet(), emptyList(), month, emptyList(),
        operationContext = PlanningOperationContext(BookCommitId(ids.commitId), createdAt, DeviceInstanceId(ids.deviceInstanceId)),
        budgetTemplateRevision = template,
    )

    private fun buildAdjustments(db: SupportSQLiteDatabase, request: RecordBudgetAdjustmentRequest): List<BudgetAdjustment> {
        val commitId = BookCommitId(request.ids.commitId)
        val monthKey = request.month.toStorageInt()
        fun adjustment(index: Int, kind: BudgetAdjustmentKind, category: StableId?, amount: Long) = BudgetAdjustment(
            BudgetAdjustmentId(request.ids.factIds[index]),
            request.month,
            if (category == null) BudgetAdjustmentScope.TOTAL else BudgetAdjustmentScope.CATEGORY,
            category?.let(::CategoryId),
            amount,
            kind,
            commitId,
            null,
        )
        return when (request.kind) {
            BudgetAdjustmentKind.INCREASE_AVAILABLE -> listOf(adjustment(0, request.kind, request.targetCategoryId, request.amountBaseMinor))
            BudgetAdjustmentKind.DECREASE_AVAILABLE -> listOf(adjustment(0, request.kind, request.sourceCategoryId, Math.negateExact(request.amountBaseMinor)))
            BudgetAdjustmentKind.CLEAR_ROLLOVER -> {
                val rollover = db.queryOne(
                    "SELECT rollover_minor FROM budget_usage_projection WHERE year_month=? AND category_id IS ?",
                    arrayOf<Any?>(monthKey, request.sourceCategoryId?.let { db.requireInternalId("category", it) }),
                ) { it.getLong(0) } ?: abort(FinanceDataError.CorruptData)
                if (rollover == 0L) abort(app.ledger.finance.domain.DomainViolation.InvalidStateTransition("budget.clearRollover"))
                listOf(adjustment(0, request.kind, request.sourceCategoryId, Math.negateExact(rollover)))
            }
            BudgetAdjustmentKind.TRANSFER_IN,
            BudgetAdjustmentKind.ARCHIVED_CATEGORY_TRANSFER,
            -> {
                val source = request.sourceCategoryId ?: abort(FinanceDataError.CorruptData)
                val target = request.targetCategoryId ?: abort(FinanceDataError.CorruptData)
                if (source == target) abort(app.ledger.finance.domain.DomainViolation.InvalidField("budget.adjustment.transfer"))
                listOf(
                    adjustment(0, BudgetAdjustmentKind.TRANSFER_OUT, source, Math.negateExact(request.amountBaseMinor)),
                    adjustment(1, request.kind, target, request.amountBaseMinor),
                )
            }
            BudgetAdjustmentKind.TRANSFER_OUT -> abort(app.ledger.finance.domain.DomainViolation.InvalidField("budget.adjustment.kind"))
        }
    }

    private fun readSnapshot(db: SupportSQLiteDatabase, bookId: StableId, month: YearMonth, today: LocalDate): BudgetSnapshot {
        val book = RoomBookRepository.mapCurrent(db)
        if (book.id.value != bookId) abort(FinanceDataError.CorruptData)
        val current = currentBudgetRevision(db, month)
        val categories = categories(db)
        val history = current?.first?.let { budgetHistory(db, it.id) }.orEmpty()
        val rows = composition(db, month, book.localRevision, categories)
        val stale = !db.isProjectionFamilyCurrent(app.ledger.finance.application.ProjectionFamily.BUDGET, book.localRevision)
        val readiness = if (stale) BudgetProjectionReadiness.FAILED else BudgetProjectionReadiness.CURRENT
        val reservations = if (readiness == BudgetProjectionReadiness.CURRENT) {
            db.queryOne(
                "SELECT COALESCE(SUM(reserved_base_minor),0) FROM budget_future_reservation WHERE year_month=? AND occurrence_date>=?",
                arrayOf<Any>(month.toStorageInt(), today.toStorageInt()),
            ) { it.getLong(0) } ?: 0L
        } else {
            0L
        }
        val total = rows.singleOrNull { it.categoryId == null }
        val days = remainingDays(month, today)
        val daily = if (readiness == BudgetProjectionReadiness.CURRENT && total != null && days > 0) {
            DailyAvailableBudgetPolicy.calculate(month, total.remainingMinor, reservations, days, book.localRevision).valueOrAbort()
        } else {
            null
        }
        return BudgetSnapshot(
            bookId, book.baseCurrency, month, today, book.localRevision, readiness, current?.first?.id?.value,
            current?.second?.toView(db), history, categories, if (stale) emptyList() else rows,
            adjustments(db, month), templates(db), daily,
        )
    }

    private fun currentBudgetRevision(db: SupportSQLiteDatabase, month: YearMonth): Pair<BudgetMonth, BudgetMonthRevision>? = db.queryOne(
        "SELECT bm.uid month_uid,bmr.id revision_internal,bmr.uid revision_uid,bmr.revision_no,bmr.base_total_minor,btr.uid template_revision_uid,bc.uid commit_uid " +
            "FROM budget_month bm JOIN budget_month_revision bmr ON bmr.id=bm.current_revision_id " +
            "JOIN book_commit bc ON bc.id=bmr.created_commit_id LEFT JOIN budget_template_revision btr ON btr.id=bmr.source_template_revision_id WHERE bm.year_month=?",
        arrayOf(month.toStorageInt()),
    ) { cursor ->
        val monthId = BudgetMonthId(cursor.stableId("month_uid"))
        val revision = BudgetMonthRevision(
            BudgetMonthRevisionId(cursor.stableId("revision_uid")),
            monthId,
            cursor.int("revision_no"),
            cursor.long("base_total_minor"),
            budgetLimits(db, "budget_category_limit", "budget_month_revision_id", cursor.long("revision_internal")),
            cursor.nullableStableId("template_revision_uid")?.let(::BudgetTemplateRevisionId),
            BookCommitId(cursor.stableId("commit_uid")),
        )
        BudgetMonth(monthId, month, revision.id) to revision
    }

    private fun budgetRevision(db: SupportSQLiteDatabase, revisionUid: StableId): BudgetMonthRevision? = db.queryOne(
        "SELECT bmr.id revision_internal,bmr.uid revision_uid,bmr.revision_no,bmr.base_total_minor,bm.uid month_uid,btr.uid template_revision_uid,bc.uid commit_uid " +
            "FROM budget_month_revision bmr JOIN budget_month bm ON bm.id=bmr.budget_month_id JOIN book_commit bc ON bc.id=bmr.created_commit_id " +
            "LEFT JOIN budget_template_revision btr ON btr.id=bmr.source_template_revision_id WHERE bmr.uid=?",
        arrayOf(revisionUid.bytes),
    ) { cursor ->
        val monthId = BudgetMonthId(cursor.stableId("month_uid"))
        BudgetMonthRevision(
            BudgetMonthRevisionId(cursor.stableId("revision_uid")),
            monthId,
            cursor.int("revision_no"),
            cursor.long("base_total_minor"),
            budgetLimits(db, "budget_category_limit", "budget_month_revision_id", cursor.long("revision_internal")),
            cursor.nullableStableId("template_revision_uid")?.let(::BudgetTemplateRevisionId),
            BookCommitId(cursor.stableId("commit_uid")),
        )
    }

    private fun currentTemplateRevision(db: SupportSQLiteDatabase, templateUid: StableId): Pair<BudgetTemplate, BudgetTemplateRevision>? = db.queryOne(
        "SELECT bt.uid template_uid,bt.name,bt.status,btr.id revision_internal,btr.uid revision_uid,btr.revision_no,btr.total_base_minor,bc.uid commit_uid " +
            "FROM budget_template bt JOIN budget_template_revision btr ON btr.id=bt.current_revision_id JOIN book_commit bc ON bc.id=btr.created_commit_id WHERE bt.uid=?",
        arrayOf(templateUid.bytes),
    ) { cursor ->
        val id = BudgetTemplateId(cursor.stableId("template_uid"))
        val revision = BudgetTemplateRevision(
            BudgetTemplateRevisionId(cursor.stableId("revision_uid")),
            id,
            cursor.int("revision_no"),
            cursor.long("total_base_minor"),
            budgetLimits(db, "budget_template_category_limit", "template_revision_id", cursor.long("revision_internal")),
            BookCommitId(cursor.stableId("commit_uid")),
        )
        BudgetTemplate(id, cursor.string("name"), revision.id, EntityStatus.entries[cursor.int("status")]) to revision
    }

    private fun templateRevision(db: SupportSQLiteDatabase, revisionUid: StableId): BudgetTemplateRevision? = db.queryOne(
        "SELECT btr.id revision_internal,btr.uid revision_uid,btr.revision_no,btr.total_base_minor,bt.uid template_uid,bc.uid commit_uid " +
            "FROM budget_template_revision btr JOIN budget_template bt ON bt.id=btr.template_id JOIN book_commit bc ON bc.id=btr.created_commit_id WHERE btr.uid=?",
        arrayOf(revisionUid.bytes),
    ) { cursor ->
        val templateId = BudgetTemplateId(cursor.stableId("template_uid"))
        BudgetTemplateRevision(
            BudgetTemplateRevisionId(cursor.stableId("revision_uid")),
            templateId,
            cursor.int("revision_no"),
            cursor.long("total_base_minor"),
            budgetLimits(db, "budget_template_category_limit", "template_revision_id", cursor.long("revision_internal")),
            BookCommitId(cursor.stableId("commit_uid")),
        )
    }

    private fun mapLimits(db: SupportSQLiteDatabase, drafts: List<BudgetCategoryLimitDraft>): List<CategoryBudgetLimit> {
        val refs = categories(db).associateBy(BudgetCategoryReference::id)
        return drafts.map { draft ->
            val ref = refs[draft.categoryId] ?: abort(FinanceDataError.CorruptData)
            CategoryBudgetLimit(CategoryId(ref.id), CategoryId(ref.rootCategoryId), ref.parentCategoryId?.let(::CategoryId), ref.depth, draft.amountBaseMinor)
        }
    }

    private fun categories(db: SupportSQLiteDatabase): List<BudgetCategoryReference> = db.queryList(
        "SELECT c.uid,c.name,c.parent_id,c.status,parent.uid parent_uid FROM category c LEFT JOIN category parent ON parent.id=c.parent_id " +
            "WHERE c.direction=0 ORDER BY c.sort_order,c.id",
    ) { cursor ->
        val id = cursor.stableId("uid")
        val parent = cursor.nullableStableId("parent_uid")
        BudgetCategoryReference(id, cursor.string("name"), parent ?: id, parent, if (parent == null) 1 else 2, EntityStatus.entries[cursor.int("status")])
    }

    private fun budgetLimits(db: SupportSQLiteDatabase, table: String, foreignKey: String, revisionId: Long): List<CategoryBudgetLimit> {
        val refs = categories(db).associateBy(BudgetCategoryReference::id)
        return db.queryList("SELECT c.uid,l.amount_base_minor FROM $table l JOIN category c ON c.id=l.category_id WHERE l.$foreignKey=? ORDER BY c.id", arrayOf(revisionId)) { cursor ->
            val ref = refs.getValue(cursor.stableId("uid"))
            CategoryBudgetLimit(CategoryId(ref.id), CategoryId(ref.rootCategoryId), ref.parentCategoryId?.let(::CategoryId), ref.depth, cursor.long("amount_base_minor"))
        }
    }

    private fun BudgetMonthRevision.toView(db: SupportSQLiteDatabase): BudgetRevisionView = BudgetRevisionView(
        id.value,
        revisionNumber,
        totalBaseMinor,
        categoryLimits.map { BudgetCategoryLimitDraft(it.categoryId.value, it.amountBaseMinor) },
        sourceTemplateRevisionId?.value,
        db.queryOne("SELECT bc.created_at FROM budget_month_revision bmr JOIN book_commit bc ON bc.id=bmr.created_commit_id WHERE bmr.uid=?", arrayOf(id.value.bytes)) { it.getLong(0).toStoredInstant() }
            ?: abort(FinanceDataError.CorruptData),
    )

    private fun budgetHistory(db: SupportSQLiteDatabase, id: BudgetMonthId): List<BudgetRevisionView> = db.queryList(
        "SELECT bmr.id,bmr.uid,bmr.revision_no,bmr.base_total_minor,btr.uid template_revision_uid,bc.created_at FROM budget_month_revision bmr " +
            "JOIN budget_month bm ON bm.id=bmr.budget_month_id JOIN book_commit bc ON bc.id=bmr.created_commit_id " +
            "LEFT JOIN budget_template_revision btr ON btr.id=bmr.source_template_revision_id WHERE bm.uid=? ORDER BY bmr.revision_no DESC",
        arrayOf(id.value.bytes),
    ) { cursor -> BudgetRevisionView(cursor.stableId("uid"), cursor.int("revision_no"), cursor.long("base_total_minor"), budgetLimits(db, "budget_category_limit", "budget_month_revision_id", cursor.long("id")).map { BudgetCategoryLimitDraft(it.categoryId.value, it.amountBaseMinor) }, cursor.nullableStableId("template_revision_uid"), cursor.long("created_at").toStoredInstant()) }

    private fun composition(db: SupportSQLiteDatabase, month: YearMonth, revision: LocalRevision, refs: List<BudgetCategoryReference>): List<BudgetCompositionView> {
        val categories = refs.associateBy(BudgetCategoryReference::id)
        return db.queryList(
            "SELECT bup.category_id,c.uid,bup.base_budget_minor,bup.rollover_minor,bup.adjustment_minor,bup.used_minor,bup.remaining_minor,bup.as_of_local_revision " +
                "FROM budget_usage_projection bup LEFT JOIN category c ON c.id=bup.category_id WHERE bup.year_month=? ORDER BY bup.category_id",
            arrayOf(month.toStorageInt()),
        ) { cursor ->
            val id = cursor.nullableStableId("uid")
            val category = id?.let(categories::get)
            BudgetCompositionView(id, category?.name, category?.parentCategoryId, category?.depth ?: 0, cursor.long("base_budget_minor"), cursor.long("rollover_minor"), cursor.long("adjustment_minor"), cursor.long("used_minor"), cursor.long("remaining_minor"), revision)
        }
    }

    private fun adjustments(db: SupportSQLiteDatabase, month: YearMonth): List<BudgetAdjustmentView> = db.queryList(
        "SELECT ba.uid,ba.scope,c.uid category_uid,ba.amount_base_minor,ba.kind,bc.created_at,reversed.uid reversed_uid FROM budget_adjustment ba " +
            "JOIN book_commit bc ON bc.id=ba.created_commit_id LEFT JOIN category c ON c.id=ba.category_id LEFT JOIN budget_adjustment reversed ON reversed.id=ba.reversal_of_id " +
            "WHERE ba.year_month=? ORDER BY bc.created_at DESC,ba.id DESC",
        arrayOf(month.toStorageInt()),
    ) { cursor -> BudgetAdjustmentView(cursor.stableId("uid"), BudgetAdjustmentScope.entries[cursor.int("scope")], cursor.nullableStableId("category_uid"), cursor.long("amount_base_minor"), BudgetAdjustmentKind.entries[cursor.int("kind")], cursor.long("created_at").toStoredInstant(), cursor.nullableStableId("reversed_uid")) }

    private fun templates(db: SupportSQLiteDatabase): List<BudgetTemplateView> = db.queryList("SELECT uid FROM budget_template WHERE status=0 ORDER BY name,id") { it.stableId("uid") }.map { uid ->
        val pair = currentTemplateRevision(db, uid) ?: abort(FinanceDataError.CorruptData)
        BudgetTemplateView(pair.first.id.value, pair.first.name, pair.first.status, BudgetRevisionView(pair.second.id.value, pair.second.revisionNumber, pair.second.totalBaseMinor, pair.second.categoryLimits.map { BudgetCategoryLimitDraft(it.categoryId.value, it.amountBaseMinor) }, null, db.queryOne("SELECT bc.created_at FROM budget_template_revision btr JOIN book_commit bc ON bc.id=btr.created_commit_id WHERE btr.uid=?", arrayOf(pair.second.id.value.bytes)) { it.getLong(0).toStoredInstant() } ?: abort(FinanceDataError.CorruptData)))
    }

    private fun remainingDays(month: YearMonth, today: LocalDate): Int = when {
        month < YearMonth.from(today) -> 0
        month > YearMonth.from(today) -> month.lengthOfMonth()
        else -> month.lengthOfMonth() - today.dayOfMonth + 1
    }

    private suspend fun <T> withDatabase(bookId: StableId, block: suspend (LedgerDatabase) -> DomainResult<T>): DomainResult<T> = try {
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

    private fun zeroHash() = app.ledger.finance.domain.Hash256.fromBytes(ByteArray(HASH_BYTE_COUNT)).valueOrAbort()

    private companion object {
        const val HASH_BYTE_COUNT = 32
    }
}

private class BudgetWriteGate : LedgerWriteGate {
    private val mutex = Mutex()
    override suspend fun <T> execute(block: suspend () -> T): T = mutex.withLock { block() }
}

private fun android.database.Cursor.int(name: String): Int = getInt(getColumnIndexOrThrow(name))
private fun android.database.Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
private fun android.database.Cursor.string(name: String): String = getString(getColumnIndexOrThrow(name))
