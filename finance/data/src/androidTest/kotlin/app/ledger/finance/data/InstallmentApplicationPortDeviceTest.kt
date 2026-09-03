@file:Suppress("LongMethod", "MagicNumber", "MaxLineLength")

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
import app.ledger.finance.application.AccountDraft
import app.ledger.finance.application.ApplyInstallmentRefundRequest
import app.ledger.finance.application.ApplyInstallmentSettlementRequest
import app.ledger.finance.application.InitialAccountCommand
import app.ledger.finance.application.InitialCategoryCommand
import app.ledger.finance.application.InitializeLedgerCommand
import app.ledger.finance.application.InstallmentMutationIds
import app.ledger.finance.application.InstallmentSettlementIds
import app.ledger.finance.application.InstallmentTermsDraft
import app.ledger.finance.application.LedgerGenesisIds
import app.ledger.finance.application.OrdinaryAmountDraft
import app.ledger.finance.application.OrdinaryDirection
import app.ledger.finance.application.OrdinaryTransactionWriteIds
import app.ledger.finance.application.OrdinaryTransactionWriteRequest
import app.ledger.finance.application.ReferenceMutation
import app.ledger.finance.application.ReferenceMutationCommand
import app.ledger.finance.application.ReferenceMutationIds
import app.ledger.finance.application.RefundAllocationDraft
import app.ledger.finance.application.RefundAmountDraft
import app.ledger.finance.application.RefundWriteIds
import app.ledger.finance.application.RefundWriteRequest
import app.ledger.finance.application.SaveInstallmentPlanRequest
import app.ledger.finance.application.SpecializedAccountAmountDraft
import app.ledger.finance.domain.CategoryDirection
import app.ledger.finance.domain.DomainViolation
import app.ledger.finance.domain.InstallmentFeeRateType
import app.ledger.finance.domain.InstallmentPrepaymentPolicy
import app.ledger.finance.domain.InstallmentRefundPolicy
import app.ledger.finance.domain.InstallmentStatus
import app.ledger.finance.domain.RefundAccrualPolicy
import app.ledger.finance.domain.RefundBudgetPolicy
import app.ledger.finance.domain.RefundGoalPolicy
import app.ledger.finance.domain.RefundProjectPolicy
import app.ledger.finance.domain.ScheduleRevisionReason
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
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class InstallmentApplicationPortDeviceTest {
    private lateinit var context: Context
    private lateinit var keys: DeviceKeyHierarchy
    private lateinit var databaseAccess: DeviceTestLedgerDatabaseAccess
    private lateinit var references: SecureRoomReferenceDataManagementPort
    private lateinit var ordinary: SecureRoomOrdinaryTransactionEntryPort
    private lateinit var refunds: SecureRoomRefundApplicationPort
    private lateinit var installments: SecureRoomInstallmentApplicationPort

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
                    Instant.parse("2026-08-01T00:00:00Z"),
                ),
            ).success()
            createFirstAccount(
                BOOK_ID,
                InitialAccountCommand(BANK_ID, id(201), id(202), id(203), id(204), Instant.parse("2026-08-01T00:00:01Z"), UserAccountType.BANK, "Bank", JPY, "account", 0xff006c4c.toInt()),
            ).success()
            createFirstCategory(
                BOOK_ID,
                InitialCategoryCommand(CATEGORY_ID, id(211), id(212), id(213), Instant.parse("2026-08-01T00:00:02Z"), CategoryDirection.EXPENSE, "Food", "food", StatisticalNature.CONSUMPTION_EXPENSE, "record", 0xff006c4c.toInt()),
            ).success()
        }
        references = SecureRoomReferenceDataManagementPort(databaseAccess)
        ordinary = SecureRoomOrdinaryTransactionEntryPort(databaseAccess, references)
        refunds = SecureRoomRefundApplicationPort(databaseAccess, references)
        installments = SecureRoomInstallmentApplicationPort(databaseAccess)
        references.mutate(
            ReferenceMutationCommand(
                ReferenceMutationIds(BOOK_ID, 3, id(250), List(16) { id(260L + it) }, id(280), Instant.parse("2026-08-01T00:00:03Z")),
                ReferenceMutation.SaveAccount(
                    AccountDraft(CREDIT_ID, id(281), null, UserAccountType.CREDIT, "Credit", JPY, null, null, null, null, "credit", 0xff006c4c.toInt(), 1),
                ),
            ),
        ).success()
        ordinary.submit(purchase()).success()
        Unit
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        keys.destroyLocal(BOOK_ID)
    }

    @Test
    fun planPreviewSettlementIdempotencyAndProjectionRebuildPreserveAccountingSemantics() = runBlocking {
        val localBeforePreview = scalar("SELECT local_revision FROM book")
        val request = planRequest(40_000, PLAN_ID, null, 1, 1)
        val preview = installments.preview(request).success()
        assertEquals(localBeforePreview, scalar("SELECT local_revision FROM book"))
        assertEquals(12_000L, preview.items.sumOf { it.principalMinor })
        assertEquals(0L, preview.items.last().remainingPrincipalMinor)
        assertEquals(120L, preview.items.sumOf { it.feeMinor })

        val receipt = installments.save(request).success()
        assertEquals(receipt, installments.save(request).success())
        var snapshot = installments.snapshot(BOOK_ID, LocalDate.of(2026, 8, 20)).success()
        var plan = snapshot.plans.single()
        assertEquals(12_000L, plan.currentPrincipalMinor)
        assertEquals(0L, plan.progress.postedPrincipalMinor)
        assertEquals(12_000L, plan.progress.unpostedCommittedPrincipalMinor)
        assertEquals(12, plan.currentSchedule.items.size)

        val duplicate = installments.save(planRequest(41_000, id(41_500), null, 1, 1))
        assertTrue(duplicate is DomainResult.Failure)
        val stale = installments.save(planRequest(42_000, PLAN_ID, id(42_999), 2, 2))
        assertEquals(DomainViolation.StaleExpectedRevision, (stale as DomainResult.Failure).error)
        assertEquals(1L, scalar("SELECT COUNT(*) FROM installment_plan"))

        val revisionBeforeSimulation = scalar("SELECT local_revision FROM book")
        val simulation = installments.simulateSettlement(BOOK_ID, PLAN_ID, LocalDate.of(2026, 10, 1)).success()
        assertEquals(revisionBeforeSimulation, scalar("SELECT local_revision FROM book"))
        assertEquals(11_000L, simulation.outstandingPrincipalMinor)
        assertEquals(110L, simulation.futureFeeMinor)
        assertEquals(20L, simulation.settlementFeeMinor)
        assertEquals(11_020L, simulation.paymentMinor)
        assertEquals(90L, simulation.savedCostMinor)

        val settlement = settlementRequest(plan, simulation.settlementDate, 50_000)
        val settlementReceipt = installments.applySettlement(settlement).success()
        assertEquals(settlementReceipt, installments.applySettlement(settlement).success())
        snapshot = installments.snapshot(BOOK_ID, LocalDate.of(2026, 10, 1)).success()
        plan = snapshot.plans.single()
        assertEquals(InstallmentStatus.SETTLED, plan.status)
        assertEquals(0L, plan.currentPrincipalMinor)
        assertTrue(plan.currentSchedule.items.isEmpty())
        assertEquals(2L, scalar("SELECT COUNT(*) FROM business_transaction"))
        assertEquals(2L, scalar("SELECT COUNT(*) FROM economic_effect"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM economic_effect WHERE is_consumption=1"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM economic_effect WHERE is_consumption=0"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM (SELECT journal_entry_id FROM posting GROUP BY journal_entry_id HAVING SUM(CASE side WHEN 0 THEN base_amount_minor ELSE -base_amount_minor END)<>0)"))
        assertEquals(scalar("SELECT local_revision FROM book"), scalar("SELECT as_of_local_revision FROM installment_progress_projection"))
        rebuildAndAudit(LocalDate.of(2026, 10, 1))
        assertEquals("ok", textScalar("PRAGMA integrity_check"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM pragma_foreign_key_check"))
    }

    @Test
    fun linkedPartialRefundAllocatesPrincipalAndFeeAndKeepsPriorScheduleVersion() = runBlocking {
        installments.save(planRequest(60_000, PLAN_ID, null, 1, 1)).success()
        val refund = refundRequest(3_000, 70_000)
        refunds.submit(refund).success()
        val plan = installments.snapshot(BOOK_ID, LocalDate.of(2026, 8, 20)).success().plans.single()
        val apply = ApplyInstallmentRefundRequest(
            mutationIds(80_000, PLAN_ID, 12),
            plan.currentRevision.id.value,
            2,
            2,
            refund.ids.transactionId,
            refund.ids.revisionId,
            2_990,
            10,
            LocalDate.of(2026, 9, 25),
            Instant.parse("2026-08-20T04:00:00Z"),
        )
        val receipt = installments.applyRefund(apply).success()
        assertEquals(receipt, installments.applyRefund(apply).success())
        val adjusted = installments.snapshot(BOOK_ID, LocalDate.of(2026, 8, 20)).success().plans.single()
        assertEquals(9_010L, adjusted.currentPrincipalMinor)
        assertEquals(2_990L, adjusted.refundedPrincipalMinor)
        assertEquals(10L, adjusted.refundedFeeMinor)
        assertEquals(9_010L, adjusted.currentSchedule.items.sumOf { it.principalMinor })
        assertEquals(2L, scalar("SELECT COUNT(*) FROM installment_plan_revision"))
        assertEquals(2L, scalar("SELECT COUNT(*) FROM installment_schedule_revision"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM installment_refund_allocation"))
        assertEquals(12_000L, scalar("SELECT SUM(principal_minor) FROM installment_schedule_item WHERE schedule_revision_id=(SELECT MIN(id) FROM installment_schedule_revision)"))
        val unrelated = apply.copy(
            ids = mutationIds(81_000, PLAN_ID, 12),
            expectedRevisionId = adjusted.currentRevision.id.value,
            revisionNumber = 3,
            scheduleRevisionNumber = 3,
            refundTransactionId = PURCHASE_ID,
            refundRevisionId = PURCHASE_REVISION_ID,
        )
        assertTrue(installments.applyRefund(unrelated) is DomainResult.Failure)
        rebuildAndAudit(LocalDate.of(2026, 8, 20))
    }

    private fun planRequest(seed: Long, planId: StableId, expected: StableId?, revision: Int, scheduleRevision: Int) = SaveInstallmentPlanRequest(
        mutationIds(seed, planId, 12), PURCHASE_ID, CREDIT_ID, JPY, 12_000, 12_000, 12, expected, revision, scheduleRevision,
        LocalDate.of(2026, 9, 25),
        InstallmentTermsDraft(
            InstallmentFeeRateType.FIXED_PER_TERM, 10, null, null, null,
            InstallmentPrepaymentPolicy.ALLOWED_WITH_FEE, 20, InstallmentRefundPolicy.REBUILD_SCHEDULE, RoundingMode.HALF_EVEN,
        ),
        if (revision == 1) ScheduleRevisionReason.INITIAL else ScheduleRevisionReason.RATE_CHANGE,
        Instant.ofEpochMilli(seed),
    )

    private fun settlementRequest(
        plan: app.ledger.finance.application.InstallmentPlanView,
        date: LocalDate,
        seed: Long,
    ) = ApplyInstallmentSettlementRequest(
        InstallmentSettlementIds(mutationIds(seed, PLAN_ID, 0), id(seed + 100), id(seed + 101), (seed + 110..seed + 300).map(::id), emptyList()),
        plan.currentRevision.id.value, 2, 2, date, ZONE,
        SpecializedAccountAmountDraft(BANK_ID, 11_020, 11_020, null),
        SpecializedAccountAmountDraft(CREDIT_ID, 11_000, 11_000, null),
        SpecializedAccountAmountDraft(BANK_ID, 20, 20, null),
        Instant.parse("2026-10-01T03:00:00Z"),
    )

    private fun mutationIds(seed: Long, planId: StableId, itemCount: Int) = InstallmentMutationIds(
        BOOK_ID,
        CommandId(id(seed)),
        id(seed + 1),
        id(seed + 2),
        planId,
        id(seed + 3),
        id(seed + 4),
        List(itemCount) { id(seed + 10 + it) },
    )

    private fun purchase() = OrdinaryTransactionWriteRequest(
        OrdinaryTransactionWriteIds(BOOK_ID, id(30_000), PURCHASE_ID, PURCHASE_REVISION_ID, id(30_002), id(30_003), (30_010L..30_320L).map(::id), emptyList()),
        null, OrdinaryDirection.EXPENSE, CATEGORY_ID, OrdinaryAmountDraft("12000", 12_000, JPY, 12_000, 12_000), CREDIT_ID,
        null, null, Instant.parse("2026-08-04T03:00:00Z"), ZONE, LocalDate.of(2026, 8, 4), null, null, null, emptyList(),
        null, null, "installment purchase", emptyList(), TransactionSource.MANUAL, null, Instant.parse("2026-08-04T03:00:01Z"),
    )

    private fun refundRequest(amount: Long, seed: Long) = RefundWriteRequest(
        RefundWriteIds(BOOK_ID, CommandId(id(seed)), id(seed + 1), id(seed + 2), id(seed + 3), id(seed + 4), (seed + 10..seed + 400).map(::id), emptyList()),
        listOf(RefundAllocationDraft(PURCHASE_ID, PURCHASE_REVISION_ID, amount, amount)),
        RefundAmountDraft(amount, JPY, CREDIT_ID, amount, amount, null, null), null, false, CATEGORY_ID, null, null, null, null,
        emptyList(), Instant.parse("2026-08-20T03:00:00Z"), ZONE, LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 4),
        YearMonth.of(2026, 8), RefundBudgetPolicy.RESTORE_ORIGINAL_MONTH, RefundProjectPolicy.DO_NOT_RESTORE,
        RefundGoalPolicy.DO_NOT_RESTORE, RefundAccrualPolicy.ORIGINAL_TRANSACTION_DATE, false, false, amount.toString(), null, emptyList(),
        Instant.ofEpochMilli(seed),
    )

    private fun rebuildAndAudit(date: LocalDate) = keys.open(BOOK_ID).use { opened ->
        val database = opened.databaseDek.useBytes { EncryptedDatabaseFactory.openPrimary(context, it) }
        try {
            database.inLedgerTransaction { db ->
                val book = RoomBookRepository.mapCurrent(db)
                val engine = RoomProjectionEngine()
                val before = engine.canonicalTableHashes(db)
                engine.rebuildAll(db, book.localRevision.value, book.valuationRevision.value, date.toStorageInt())
                assertEquals(before, engine.canonicalTableHashes(db))
                assertTrue(engine.mismatchedFamilies(db, book.localRevision.value, book.valuationRevision.value).isEmpty())
            }
        } finally {
            database.close()
        }
    }

    private fun scalar(sql: String): Long = query { db ->
        db.query(sql).use {
            it.moveToFirst()
            it.getLong(0)
        }
    }
    private fun textScalar(sql: String): String = query { db ->
        db.query(sql).use {
            it.moveToFirst()
            it.getString(0)
        }
    }
    private fun <T> query(block: (androidx.sqlite.db.SupportSQLiteDatabase) -> T): T = keys.open(BOOK_ID).use { opened ->
        val database = opened.databaseDek.useBytes { EncryptedDatabaseFactory.openPrimary(context, it) }
        try {
            database.readLedger(block)
        } finally {
            database.close()
        }
    }
    private fun id(value: Long): StableId = StableId.fromUuid(UUID(0x2020L, value))
    private fun <T> DomainResult<T>.success(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error(error.toString())
    }

    private companion object {
        val BOOK_ID = StableId.fromUuid(UUID(0x2020L, 1))
        val BANK_ID = StableId.fromUuid(UUID(0x2020L, 200))
        val CREDIT_ID = StableId.fromUuid(UUID(0x2020L, 300))
        val CATEGORY_ID = StableId.fromUuid(UUID(0x2020L, 210))
        val PURCHASE_ID = StableId.fromUuid(UUID(0x2020L, 500))
        val PURCHASE_REVISION_ID = StableId.fromUuid(UUID(0x2020L, 30_001))
        val PLAN_ID = StableId.fromUuid(UUID(0x2020L, 600))
        val JPY: CurrencyCode = requireNotNull(CurrencyCode.parse("JPY").getOrNull())
        val ZONE: ZoneId = ZoneId.of("Asia/Tokyo")
    }
}
