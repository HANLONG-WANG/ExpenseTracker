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
import app.ledger.finance.application.AccountDraft
import app.ledger.finance.application.InitialAccountCommand
import app.ledger.finance.application.InitialCategoryCommand
import app.ledger.finance.application.InitializeLedgerCommand
import app.ledger.finance.application.JournalMutationIds
import app.ledger.finance.application.JournalMutationRequest
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
import app.ledger.finance.application.RefundSearchQuery
import app.ledger.finance.application.RefundWriteIds
import app.ledger.finance.application.RefundWriteRequest
import app.ledger.finance.domain.CategoryDirection
import app.ledger.finance.domain.DependencyPolicy
import app.ledger.finance.domain.DependencyResolution
import app.ledger.finance.domain.DomainViolation
import app.ledger.finance.domain.EconomicNature
import app.ledger.finance.domain.RefundAccrualPolicy
import app.ledger.finance.domain.RefundBudgetPolicy
import app.ledger.finance.domain.RefundGoalPolicy
import app.ledger.finance.domain.RefundProjectPolicy
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.SystemLedgerCode
import app.ledger.finance.domain.TransactionKind
import app.ledger.finance.domain.TransactionLifecycleState
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
class RefundApplicationPortDeviceTest {
    private lateinit var context: Context
    private lateinit var keys: DeviceKeyHierarchy
    private lateinit var ordinary: SecureRoomOrdinaryTransactionEntryPort
    private lateinit var references: SecureRoomReferenceDataManagementPort
    private lateinit var refunds: SecureRoomRefundApplicationPort
    private lateinit var journal: SecureRoomJournalApplicationPort

    @Before
    fun prepare() {
        runBlocking {
            context = ApplicationProvider.getApplicationContext()
            context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
            keys = DeviceKeyHierarchy(AndroidKeystoreKeys(context), SecurityEnvelopeStore(context))
            keys.destroyLocal(BOOK_ID)
            SecureRoomLedgerInitializationPort(context, keys).apply {
                initialize(InitializeLedgerCommand(LedgerGenesisIds(BOOK_ID, id(2), id(3), id(4), SystemLedgerCode.entries.mapIndexed { index, code -> code to id(100L + index) }.toMap()), JPY, ZONE, Instant.ofEpochMilli(1_000))).success()
                createFirstAccount(BOOK_ID, InitialAccountCommand(ACCOUNT_A, id(201), id(202), id(203), id(204), Instant.ofEpochMilli(2_000), UserAccountType.CASH, "Wallet", JPY, "account", 0xff006c4c.toInt())).success()
                createFirstCategory(BOOK_ID, InitialCategoryCommand(CATEGORY_ID, id(211), id(212), id(213), Instant.ofEpochMilli(3_000), CategoryDirection.EXPENSE, "Food", "food", StatisticalNature.CONSUMPTION_EXPENSE, "record", 0xff006c4c.toInt())).success()
            }
            references = SecureRoomReferenceDataManagementPort(context, keys)
            val beforeAccount = references.snapshot(BOOK_ID).success()
            references.mutate(
                ReferenceMutationCommand(
                    ReferenceMutationIds(BOOK_ID, beforeAccount.localRevision, id(220), List(16) { id(221L + it) }, id(240), Instant.ofEpochMilli(4_000)),
                    ReferenceMutation.SaveAccount(AccountDraft(ACCOUNT_B, id(250), null, UserAccountType.BANK, "Refund bank", JPY, null, null, null, null, "account", 0xff4f6357.toInt(), 1)),
                ),
            ).success()
            ordinary = SecureRoomOrdinaryTransactionEntryPort(context, keys, references)
            refunds = SecureRoomRefundApplicationPort(context, keys, references)
            journal = SecureRoomJournalApplicationPort(context, keys)
            ordinary.submit(expense(1_000, ORIGINAL_A, 1_000L)).success()
            ordinary.submit(expense(500, ORIGINAL_B, 2_000L)).success()
        }
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        keys.destroyLocal(BOOK_ID)
    }

    @Test
    fun partialFullCrossMonthOtherAccountIndependentAndExcessRefundsRebuildFromFacts() = runBlocking {
        val original = refunds.snapshot(BOOK_ID).success().originals.single { it.transactionId == ORIGINAL_A }
        val firstRequest = linkedRefund(400, original.transactionId, original.revisionId, ACCOUNT_A, 10_000L, false)
        val firstReceipt = refunds.submit(firstRequest).success()
        assertEquals(firstReceipt, refunds.submit(firstRequest).success())
        val partial = refunds.snapshot(BOOK_ID).success().originals.single { it.transactionId == ORIGINAL_A }
        assertEquals(400L, partial.refundedMinor)
        assertEquals(600L, partial.remainingMinor)
        assertEquals(listOf(ORIGINAL_A), refunds.snapshot(BOOK_ID, RefundSearchQuery(partiallyRefundedOnly = true)).success().originals.map { it.transactionId })

        val denied = refunds.submit(linkedRefund(601, ORIGINAL_A, original.revisionId, ACCOUNT_B, 11_000L, false))
        assertTrue(denied is DomainResult.Failure && denied.error == DomainViolation.Invariant("INV-010"))

        refunds.submit(linkedRefund(600, ORIGINAL_A, original.revisionId, ACCOUNT_B, 12_000L, false)).success()
        val full = refunds.snapshot(BOOK_ID).success().originals.single { it.transactionId == ORIGINAL_A }
        assertEquals(1_000L, full.refundedMinor)
        assertEquals(0L, full.remainingMinor)
        assertTrue(refunds.snapshot(BOOK_ID, RefundSearchQuery(partiallyRefundedOnly = true)).success().originals.none { it.transactionId == ORIGINAL_A })

        refunds.submit(independentRefund(250, 13_000L)).success()
        val originalB = refunds.snapshot(BOOK_ID).success().originals.single { it.transactionId == ORIGINAL_B }
        refunds.submit(linkedRefund(600, ORIGINAL_B, originalB.revisionId, ACCOUNT_B, 14_000L, true)).success()
        val excess = refunds.snapshot(BOOK_ID).success().originals.single { it.transactionId == ORIGINAL_B }
        assertEquals(600L, excess.refundedMinor)
        assertEquals(100L, excess.excessRefundedMinor)
        assertEquals(0L, excess.remainingMinor)

        keys.open(BOOK_ID).use { opened ->
            val database = opened.databaseDek.useBytes { EncryptedDatabaseFactory.openPrimary(context, it) }
            try {
                database.inLedgerTransaction { db ->
                    val before = RoomProjectionEngine().canonicalHash(db)
                    val book = RoomBookRepository.mapCurrent(db)
                    RoomProjectionEngine().rebuildAll(db, book.localRevision.value, book.valuationRevision.value, LocalDate.of(2026, 9, 20).toStorageInt())
                    assertEquals(before, RoomProjectionEngine().canonicalHash(db))
                    assertEquals(3L, scalar(db, "SELECT COUNT(*) FROM refund_revision_detail WHERE independent=0"))
                    assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM refund_revision_detail WHERE independent=1"))
                    assertEquals(3L, scalar(db, "SELECT COUNT(*) FROM transaction_dependency WHERE dependency_type=0"))
                    assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM economic_effect WHERE nature=?", EconomicNature.INCOME.ordinal))
                    assertEquals(4L, scalar(db, "SELECT COUNT(*) FROM economic_effect WHERE nature=? AND polarity=1", EconomicNature.CONTRA_EXPENSE.ordinal))
                    assertEquals(3L, scalar(db, "SELECT COUNT(*) FROM budget_effect be JOIN transaction_revision tr ON tr.id=be.source_revision_id JOIN business_transaction bt ON bt.id=tr.transaction_id WHERE bt.kind=? AND be.target_year_month=? AND be.polarity=1", TransactionKind.REFUND.ordinal, YearMonth.of(2026, 7).toStorageInt()))
                    assertEquals(3L, scalar(db, "SELECT COUNT(*) FROM economic_effect ee JOIN transaction_revision tr ON tr.id=ee.source_revision_id JOIN business_transaction bt ON bt.id=tr.transaction_id WHERE bt.kind=? AND tr.local_date=? AND ee.accrual_local_date=? AND ee.polarity=1", TransactionKind.REFUND.ordinal, LocalDate.of(2026, 9, 20).toStorageInt(), LocalDate.of(2026, 7, 8).toStorageInt()))
                    assertEquals(600L, scalar(db, "SELECT original_currency_amount_minor FROM refund_allocation WHERE original_transaction_id=(SELECT id FROM business_transaction WHERE uid=?) ORDER BY id DESC LIMIT 1", ORIGINAL_B.bytes))
                    assertEquals(1L, scalar(db, "SELECT allow_excess FROM refund_revision_detail rrd JOIN transaction_revision tr ON tr.id=rrd.revision_id JOIN business_transaction bt ON bt.current_revision_id=tr.id WHERE bt.kind=? AND rrd.allow_excess=1", TransactionKind.REFUND.ordinal))
                }
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun originalTrashCanAtomicallyConvertAllLinkedRefundsToIndependent() = runBlocking {
        val original = refunds.snapshot(BOOK_ID).success().originals.single { it.transactionId == ORIGINAL_A }
        refunds.submit(linkedRefund(300, ORIGINAL_A, original.revisionId, ACCOUNT_A, 20_000L, false)).success()
        refunds.submit(linkedRefund(200, ORIGINAL_A, original.revisionId, ACCOUNT_B, 21_000L, false)).success()
        val detail = requireNotNull(journal.detail(BOOK_ID, ORIGINAL_A).success())
        val dependencies = journal.dependencies(BOOK_ID, ORIGINAL_A).success()
        val resolutions = dependencies.map { view ->
            DependencyResolution(
                app.ledger.finance.domain.TransactionDependency(
                    app.ledger.finance.domain.TransactionId(view.parentTransactionId),
                    app.ledger.finance.domain.TransactionId(view.childTransactionId),
                    view.type,
                ),
                DependencyPolicy.ConvertRefundToIndependent,
            )
        }
        journal.mutate(
            JournalMutationRequest.MoveToTrash(
                mutationIds(30_000L, ORIGINAL_A),
                detail.transaction.revisionId,
                Instant.parse("2026-09-21T00:00:00Z"),
                Instant.parse("2026-10-21T00:00:00Z"),
                resolutions,
            ),
        ).success()

        assertEquals(TransactionLifecycleState.TRASHED, requireNotNull(journal.detail(BOOK_ID, ORIGINAL_A).success()).transaction.state)
        keys.open(BOOK_ID).use { opened ->
            val database = opened.databaseDek.useBytes { EncryptedDatabaseFactory.openPrimary(context, it) }
            try {
                database.readLedger { db ->
                    assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM transaction_dependency WHERE dependency_type=0"))
                    assertEquals(2L, scalar(db, "SELECT COUNT(*) FROM refund_revision_detail rrd JOIN transaction_revision tr ON tr.id=rrd.revision_id JOIN business_transaction bt ON bt.current_revision_id=tr.id WHERE rrd.independent=1 AND bt.lifecycle_state=0"))
                    assertEquals(0L, scalar(db, "SELECT COALESCE(SUM(CASE WHEN reversal_of_id IS NULL THEN original_currency_amount_minor ELSE -original_currency_amount_minor END),0) FROM refund_allocation WHERE original_transaction_id=(SELECT id FROM business_transaction WHERE uid=?)", ORIGINAL_A.bytes))
                    assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM book_commit bc JOIN command_receipt cr ON cr.commit_id=bc.id WHERE cr.command_type=?", app.ledger.finance.domain.FinancialCommandType.BATCH_MUTATION.ordinal))
                }
            } finally {
                database.close()
            }
        }
    }

    private fun expense(amount: Long, transaction: StableId, seed: Long): OrdinaryTransactionWriteRequest = OrdinaryTransactionWriteRequest(
        OrdinaryTransactionWriteIds(BOOK_ID, id(seed), transaction, id(seed + 1), id(seed + 2), id(seed + 3), (seed + 10..seed + 300).map(::id), (seed + 400..seed + 407).map(::id)),
        null, OrdinaryDirection.EXPENSE, CATEGORY_ID, OrdinaryAmountDraft(amount.toString(), amount, JPY, amount, amount), ACCOUNT_A, null, null,
        Instant.parse("2026-07-08T03:00:00Z"), ZONE, LocalDate.of(2026, 7, 8), null, null, emptyList(), null, null, "purchase", emptyList(), TransactionSource.MANUAL, null, Instant.ofEpochMilli(seed * 10),
    )

    private fun linkedRefund(amount: Long, original: StableId, originalRevision: StableId, account: StableId, seed: Long, excess: Boolean): RefundWriteRequest = refundRequest(
        amount, seed, account, listOf(RefundAllocationDraft(original, originalRevision, amount, amount)), false, excess,
        RefundBudgetPolicy.RESTORE_ORIGINAL_MONTH, LocalDate.of(2026, 7, 8), YearMonth.of(2026, 7),
    )

    private fun independentRefund(amount: Long, seed: Long): RefundWriteRequest = refundRequest(
        amount, seed, ACCOUNT_B, emptyList(), true, false, RefundBudgetPolicy.DO_NOT_RESTORE, LocalDate.of(2026, 9, 20), null,
    )

    private fun refundRequest(amount: Long, seed: Long, account: StableId, allocations: List<RefundAllocationDraft>, independent: Boolean, excess: Boolean, budgetPolicy: RefundBudgetPolicy, accrualDate: LocalDate, budgetMonth: YearMonth?): RefundWriteRequest = RefundWriteRequest(
        RefundWriteIds(BOOK_ID, CommandId(id(seed)), id(seed + 1), id(seed + 2), id(seed + 3), id(seed + 4), (seed + 10..seed + 400).map(::id), (seed + 500..seed + 507).map(::id)),
        allocations, RefundAmountDraft(amount, JPY, account, amount, amount, null, null), null, independent, CATEGORY_ID, null, null, null, null, emptyList(),
        Instant.parse("2026-09-20T03:00:00Z"), ZONE, LocalDate.of(2026, 9, 20), accrualDate, budgetMonth, budgetPolicy,
        RefundProjectPolicy.DO_NOT_RESTORE, RefundGoalPolicy.DO_NOT_RESTORE, if (independent) RefundAccrualPolicy.REFUND_DATE else RefundAccrualPolicy.ORIGINAL_TRANSACTION_DATE,
        excess, excess, amount.toString(), null, emptyList(), Instant.ofEpochMilli(seed * 10),
    )

    private fun mutationIds(seed: Long, transaction: StableId) = JournalMutationIds(BOOK_ID, id(seed), transaction, id(seed + 1), id(seed + 2), id(seed + 3), (seed + 10..seed + 1_100).map(::id), (seed + 1_200..seed + 1_263).map(::id))
    private fun scalar(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String, vararg args: Any?): Long = db.query(sql, args).use {
        it.moveToFirst()
        it.getLong(0)
    }
    private fun id(value: Long): StableId = StableId.fromUuid(UUID(0x1616L, value))
    private fun <T> DomainResult<T>.success(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error(error.toString())
    }

    private companion object {
        fun currency(code: String): CurrencyCode = requireNotNull(CurrencyCode.parse(code).getOrNull())
        val BOOK_ID = StableId.fromUuid(UUID(0x1616L, 1))
        val ACCOUNT_A = StableId.fromUuid(UUID(0x1616L, 200))
        val ACCOUNT_B = StableId.fromUuid(UUID(0x1616L, 249))
        val CATEGORY_ID = StableId.fromUuid(UUID(0x1616L, 210))
        val ORIGINAL_A = StableId.fromUuid(UUID(0x1616L, 900))
        val ORIGINAL_B = StableId.fromUuid(UUID(0x1616L, 901))
        val JPY = currency("JPY")
        val ZONE: ZoneId = ZoneId.of("Asia/Tokyo")
    }
}
