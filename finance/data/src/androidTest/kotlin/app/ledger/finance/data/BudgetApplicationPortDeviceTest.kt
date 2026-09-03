@file:Suppress("LongMethod", "LongParameterList", "MagicNumber", "MaxLineLength")

package app.ledger.finance.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.money.CurrencyCode
import app.ledger.core.security.AndroidKeystoreKeys
import app.ledger.core.security.DeviceKeyHierarchy
import app.ledger.core.security.SecurityEnvelopeStore
import app.ledger.finance.application.BudgetCategoryLimitDraft
import app.ledger.finance.application.BudgetMutationIds
import app.ledger.finance.application.CategoryDraft
import app.ledger.finance.application.InitialAccountCommand
import app.ledger.finance.application.InitialCategoryCommand
import app.ledger.finance.application.InitializeLedgerCommand
import app.ledger.finance.application.LedgerGenesisIds
import app.ledger.finance.application.OrdinaryAmountDraft
import app.ledger.finance.application.OrdinaryDirection
import app.ledger.finance.application.OrdinaryTransactionWriteIds
import app.ledger.finance.application.OrdinaryTransactionWriteRequest
import app.ledger.finance.application.RecordBudgetAdjustmentRequest
import app.ledger.finance.application.ReferenceMutation
import app.ledger.finance.application.ReferenceMutationCommand
import app.ledger.finance.application.ReferenceMutationIds
import app.ledger.finance.application.SaveBudgetMonthRequest
import app.ledger.finance.application.SaveBudgetTemplateRequest
import app.ledger.finance.domain.BudgetAdjustmentKind
import app.ledger.finance.domain.CategoryDirection
import app.ledger.finance.domain.DomainViolation
import app.ledger.finance.domain.EntityStatus
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.SystemLedgerCode
import app.ledger.finance.domain.TransactionSource
import app.ledger.finance.domain.UserAccountType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BudgetApplicationPortDeviceTest {
    private lateinit var context: Context
    private lateinit var keys: DeviceKeyHierarchy
    private lateinit var databaseAccess: DeviceTestLedgerDatabaseAccess
    private lateinit var references: SecureRoomReferenceDataManagementPort
    private lateinit var ordinary: SecureRoomOrdinaryTransactionEntryPort
    private lateinit var budgets: SecureRoomBudgetApplicationPort

    @Before
    fun prepare() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        keys = DeviceKeyHierarchy(AndroidKeystoreKeys(context), SecurityEnvelopeStore(context))
        keys.destroyLocal(BOOK_ID)
        databaseAccess = DeviceTestLedgerDatabaseAccess(context, keys)
        SecureRoomLedgerInitializationPort(context, keys).apply {
            initialize(
                InitializeLedgerCommand(
                    LedgerGenesisIds(BOOK_ID, id(2), id(3), id(4), SystemLedgerCode.entries.mapIndexed { index, code -> code to id(100L + index) }.toMap()),
                    JPY,
                    ZONE,
                    Instant.ofEpochMilli(1_000),
                ),
            ).success()
            createFirstAccount(
                BOOK_ID,
                InitialAccountCommand(ACCOUNT_ID, id(201), id(202), id(203), id(204), Instant.ofEpochMilli(2_000), UserAccountType.CASH, "Wallet", JPY, "account", 0xff006c4c.toInt()),
            ).success()
            createFirstCategory(
                BOOK_ID,
                InitialCategoryCommand(ROOT_CATEGORY_ID, id(211), id(212), id(213), Instant.ofEpochMilli(3_000), CategoryDirection.EXPENSE, "Food", "food", StatisticalNature.CONSUMPTION_EXPENSE, "record", 0xff006c4c.toInt()),
            ).success()
        }
        references = SecureRoomReferenceDataManagementPort(databaseAccess)
        val snapshot = references.snapshot(BOOK_ID).success()
        references.mutate(
            ReferenceMutationCommand(
                ReferenceMutationIds(BOOK_ID, snapshot.localRevision, id(220), List(16) { id(221L + it) }, id(240), Instant.ofEpochMilli(4_000)),
                ReferenceMutation.SaveCategory(
                    CategoryDraft(CHILD_CATEGORY_ID, null, CategoryDirection.EXPENSE, ROOT_CATEGORY_ID, "Lunch", "lunch", "record", 0xff006c4c.toInt(), 0, StatisticalNature.CONSUMPTION_EXPENSE, null, null, null),
                ),
            ),
        ).success()
        ordinary = SecureRoomOrdinaryTransactionEntryPort(databaseAccess, references)
        budgets = SecureRoomBudgetApplicationPort(databaseAccess)
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        keys.destroyLocal(BOOK_ID)
    }

    @Test
    fun hierarchyRolloverHistoryAndIdempotencyRebuildExactlyFromFacts() = runBlocking {
        val julyRequest = monthRequest(YearMonth.of(2026, 7), 1_000L, 800L, 800L, 1_000L)
        val first = budgets.saveMonth(julyRequest).success()
        assertEquals(first, budgets.saveMonth(julyRequest).success())
        ordinary.submit(expense(1_200L, 2_000L)).success()

        val july = budgets.snapshot(BOOK_ID, YearMonth.of(2026, 7), LocalDate.of(2026, 7, 20)).success()
        val julyTotal = july.composition.single { it.categoryId == null }
        val julyRoot = july.composition.single { it.categoryId == ROOT_CATEGORY_ID }
        val julyChild = july.composition.single { it.categoryId == CHILD_CATEGORY_ID }
        assertEquals(1_200L, julyTotal.usedMinor)
        assertEquals(1_200L, julyRoot.usedMinor)
        assertEquals(1_200L, julyChild.usedMinor)
        assertEquals(-200L, julyTotal.remainingMinor)

        budgets.saveMonth(monthRequest(YearMonth.of(2026, 8), 1_000L, 800L, 800L, 3_000L)).success()
        var august = budgets.snapshot(BOOK_ID, YearMonth.of(2026, 8), LocalDate.of(2026, 8, 10)).success()
        assertEquals(-200L, august.composition.single { it.categoryId == null }.rolloverMinor)
        assertEquals(800L, august.composition.single { it.categoryId == null }.remainingMinor)

        ordinary.submit(expense(100L, 2_500L)).success()
        august = budgets.snapshot(BOOK_ID, YearMonth.of(2026, 8), LocalDate.of(2026, 8, 10)).success()
        assertEquals(-300L, august.composition.single { it.categoryId == null }.rolloverMinor)
        assertEquals(700L, august.composition.single { it.categoryId == null }.remainingMinor)

        val currentJuly = budgets.snapshot(BOOK_ID, YearMonth.of(2026, 7), LocalDate.of(2026, 7, 20)).success()
        budgets.saveMonth(monthRequest(YearMonth.of(2026, 7), 2_000L, 1_500L, 1_500L, 4_000L, currentJuly.currentRevision?.id)).success()
        august = budgets.snapshot(BOOK_ID, YearMonth.of(2026, 8), LocalDate.of(2026, 8, 10)).success()
        assertEquals(700L, august.composition.single { it.categoryId == null }.rolloverMinor)
        assertEquals(1_700L, august.composition.single { it.categoryId == null }.remainingMinor)
        assertEquals(2, budgets.snapshot(BOOK_ID, YearMonth.of(2026, 7), LocalDate.of(2026, 7, 20)).success().revisionHistory.size)

        seedFutureFixedReservation(august.localRevision.value)
        august = budgets.snapshot(BOOK_ID, YearMonth.of(2026, 8), LocalDate.of(2026, 8, 10)).success()
        assertEquals(310L, august.dailyAvailable?.reservedRecurrenceBaseMinor)
        assertEquals(22, august.dailyAvailable?.remainingDayCount)
        assertEquals(63L, august.dailyAvailable?.dailyAvailableBaseMinor)
        budgets.saveTemplate(templateRequest("Reservation revision carry", 2_000L, 1_500L, 1_000L, 4_500L)).success()
        august = budgets.snapshot(BOOK_ID, YearMonth.of(2026, 8), LocalDate.of(2026, 8, 10)).success()
        assertEquals(310L, august.dailyAvailable?.reservedRecurrenceBaseMinor)
        assertEquals(63L, august.dailyAvailable?.dailyAvailableBaseMinor)

        val beforeInvalid = august.localRevision
        val invalid = budgets.saveMonth(monthRequest(YearMonth.of(2026, 9), 1_000L, 1_001L, 0L, 5_000L))
        assertTrue(invalid is DomainResult.Failure)
        assertEquals(beforeInvalid, budgets.snapshot(BOOK_ID, YearMonth.of(2026, 8), LocalDate.of(2026, 8, 10)).success().localRevision)

        assertCanonicalRebuildStable()
    }

    @Test
    fun templatesAndSignedAdjustmentsRemainSeparateAndNeverChangeAccountBalance() = runBlocking {
        budgets.saveMonth(monthRequest(YearMonth.of(2026, 8), 2_000L, 1_500L, 1_000L, 10_000L)).success()
        val beforeBalance = accountBalance()

        val template = templateRequest("Baseline", 2_000L, 1_500L, 1_000L, 11_000L)
        budgets.saveTemplate(template).success()
        val templateSnapshot = budgets.snapshot(BOOK_ID, YearMonth.of(2026, 8), LocalDate.of(2026, 8, 10)).success()
        val firstTemplate = templateSnapshot.templates.single()
        budgets.saveTemplate(templateRequest("Baseline revised", 2_500L, 2_000L, 1_500L, 12_000L, firstTemplate.revision.id, firstTemplate.id)).success()

        budgets.recordAdjustment(adjustment(BudgetAdjustmentKind.INCREASE_AVAILABLE, 200L, null, null, 13_000L)).success()
        budgets.recordAdjustment(adjustment(BudgetAdjustmentKind.DECREASE_AVAILABLE, 50L, ROOT_CATEGORY_ID, null, 14_000L)).success()
        budgets.recordAdjustment(adjustment(BudgetAdjustmentKind.TRANSFER_IN, 100L, ROOT_CATEGORY_ID, CHILD_CATEGORY_ID, 15_000L)).success()

        val snapshot = budgets.snapshot(BOOK_ID, YearMonth.of(2026, 8), LocalDate.of(2026, 8, 10)).success()
        assertEquals(3, snapshot.adjustments.map { it.createdAt }.distinct().size)
        assertEquals(4, snapshot.adjustments.size)
        assertEquals(200L, snapshot.composition.single { it.categoryId == null }.adjustmentMinor)
        assertEquals(-150L, snapshot.composition.single { it.categoryId == ROOT_CATEGORY_ID }.adjustmentMinor)
        assertEquals(100L, snapshot.composition.single { it.categoryId == CHILD_CATEGORY_ID }.adjustmentMinor)
        assertEquals("Baseline revised", snapshot.templates.single().name)
        assertEquals(2, templateRevisionCount(firstTemplate.id))
        assertEquals(beforeBalance, accountBalance())
        assertCanonicalRebuildStable()
    }

    private fun monthRequest(month: YearMonth, total: Long, root: Long, child: Long, seed: Long, expected: StableId? = null): SaveBudgetMonthRequest = SaveBudgetMonthRequest(
        mutationIds(seed, expected?.let { monthEntity(month) } ?: monthEntity(month)),
        month,
        expected,
        total,
        listOf(BudgetCategoryLimitDraft(ROOT_CATEGORY_ID, root), BudgetCategoryLimitDraft(CHILD_CATEGORY_ID, child)),
        null,
        Instant.ofEpochMilli(seed),
    )

    private fun templateRequest(name: String, total: Long, root: Long, child: Long, seed: Long, expected: StableId? = null, templateId: StableId = TEMPLATE_ID): SaveBudgetTemplateRequest = SaveBudgetTemplateRequest(
        mutationIds(seed, templateId),
        expected,
        name,
        EntityStatus.ACTIVE,
        total,
        listOf(BudgetCategoryLimitDraft(ROOT_CATEGORY_ID, root), BudgetCategoryLimitDraft(CHILD_CATEGORY_ID, child)),
        Instant.ofEpochMilli(seed),
    )

    private fun adjustment(kind: BudgetAdjustmentKind, amount: Long, source: StableId?, target: StableId?, seed: Long): RecordBudgetAdjustmentRequest = RecordBudgetAdjustmentRequest(mutationIds(seed, id(seed + 3), if (kind == BudgetAdjustmentKind.TRANSFER_IN) 2 else 1), YearMonth.of(2026, 8), kind, amount, source, target, Instant.ofEpochMilli(seed))

    private fun expense(amount: Long, seed: Long): OrdinaryTransactionWriteRequest = OrdinaryTransactionWriteRequest(
        OrdinaryTransactionWriteIds(BOOK_ID, id(seed), id(seed + 1), id(seed + 2), id(seed + 3), id(seed + 4), (seed + 10..seed + 300).map(::id), (seed + 400..seed + 407).map(::id)),
        null, OrdinaryDirection.EXPENSE, CHILD_CATEGORY_ID, OrdinaryAmountDraft(amount.toString(), amount, JPY, amount, amount), ACCOUNT_ID, null, null,
        Instant.parse("2026-07-08T03:00:00Z"), ZONE, LocalDate.of(2026, 7, 8), null, null, null, emptyList(), null, null, "purchase", emptyList(), TransactionSource.MANUAL, null, Instant.ofEpochMilli(seed),
    )

    private fun mutationIds(seed: Long, entity: StableId, factCount: Int = 0) = BudgetMutationIds(
        BOOK_ID,
        CommandId(id(seed)),
        id(seed + 1),
        entity,
        id(seed + 2),
        List(factCount) { id(seed + 10 + it) },
        id(seed + 100),
    )

    private fun monthEntity(month: YearMonth): StableId = StableId.fromUuid(UUID(0x1717L, month.year.toLong() * 100L + month.monthValue))

    private fun accountBalance(): Long = withDatabase { db -> scalar(db, "SELECT normal_balance_minor FROM account_balance_current abc JOIN user_account ua ON ua.id=abc.account_id WHERE ua.uid=?", ACCOUNT_ID.bytes) }

    private fun templateRevisionCount(templateId: StableId): Long = withDatabase { db -> scalar(db, "SELECT COUNT(*) FROM budget_template_revision btr JOIN budget_template bt ON bt.id=btr.template_id WHERE bt.uid=?", templateId.bytes) }

    private fun seedFutureFixedReservation(asOfRevision: Long) = withDatabase { db ->
        db.execSQL(
            "INSERT INTO transaction_blueprint(id,uid,name,current_revision_id,status,icon_key,color_argb) VALUES (?,?,?,?,?,?,?)",
            arrayOf<Any?>(9_001L, id(90_001).bytes, "Fixed expense", null, 0, "record", 0xff006c4c.toInt()),
        )
        db.execSQL(
            "INSERT INTO recurrence_series(id,uid,blueprint_id,current_revision_id,status) VALUES (?,?,?,?,?)",
            arrayOf<Any?>(9_002L, id(90_002).bytes, 9_001L, null, 0),
        )
        db.execSQL(
            "INSERT INTO budget_future_reservation(year_month,recurrence_series_id,occurrence_date,reserved_base_minor,as_of_local_revision) VALUES (?,?,?,?,?)",
            arrayOf<Any>(202608, 9_002L, LocalDate.of(2026, 8, 20).toStorageInt(), 310L, asOfRevision),
        )
    }

    private fun assertCanonicalRebuildStable() = withDatabase { db ->
        val before = RoomProjectionEngine().canonicalHash(db)
        val book = RoomBookRepository.mapCurrent(db)
        RoomProjectionEngine().rebuildAll(db, book.localRevision.value, book.valuationRevision.value, LocalDate.of(2026, 8, 10).toStorageInt())
        assertEquals(before, RoomProjectionEngine().canonicalHash(db))
        assertEquals(
            "ok",
            db.query("PRAGMA integrity_check").use {
                it.moveToFirst()
                it.getString(0)
            },
        )
        assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
    }

    private fun <T> withDatabase(block: (androidx.sqlite.db.SupportSQLiteDatabase) -> T): T = keys.open(BOOK_ID).use { opened ->
        val database = opened.databaseDek.useBytes { EncryptedDatabaseFactory.openPrimary(context, it) }
        try {
            database.inLedgerTransaction(block)
        } finally {
            database.close()
        }
    }

    private fun scalar(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String, vararg args: Any?): Long = db.query(sql, args).use { cursor ->
        cursor.moveToFirst()
        cursor.getLong(0)
    }
    private fun id(value: Long): StableId = StableId.fromUuid(UUID(0x1717L, value))
    private fun <T> DomainResult<T>.success(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error(error.toString())
    }

    private companion object {
        fun currency(code: String): CurrencyCode = requireNotNull(CurrencyCode.parse(code).getOrNull())
        val BOOK_ID = StableId.fromUuid(UUID(0x1717L, 1))
        val ACCOUNT_ID = StableId.fromUuid(UUID(0x1717L, 200))
        val ROOT_CATEGORY_ID = StableId.fromUuid(UUID(0x1717L, 210))
        val CHILD_CATEGORY_ID = StableId.fromUuid(UUID(0x1717L, 219))
        val TEMPLATE_ID = StableId.fromUuid(UUID(0x1717L, 900))
        val JPY = currency("JPY")
        val ZONE: ZoneId = ZoneId.of("Asia/Tokyo")
    }
}
