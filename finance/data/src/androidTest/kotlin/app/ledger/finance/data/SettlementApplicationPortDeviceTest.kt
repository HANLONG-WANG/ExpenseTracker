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
import app.ledger.finance.application.InitialAccountCommand
import app.ledger.finance.application.InitialCategoryCommand
import app.ledger.finance.application.InitializeLedgerCommand
import app.ledger.finance.application.LedgerGenesisIds
import app.ledger.finance.application.OrdinaryAmountDraft
import app.ledger.finance.application.OrdinaryDirection
import app.ledger.finance.application.OrdinarySettlementShareDraft
import app.ledger.finance.application.OrdinaryTransactionWriteIds
import app.ledger.finance.application.OrdinaryTransactionWriteRequest
import app.ledger.finance.application.RecordSettlementExpenseRequest
import app.ledger.finance.application.RecordSettlementPaymentRequest
import app.ledger.finance.application.SaveSettlementActivityRequest
import app.ledger.finance.application.SettlementExpenseIds
import app.ledger.finance.application.SettlementExpenseShareDraft
import app.ledger.finance.application.SettlementMutationIds
import app.ledger.finance.application.SettlementParticipantDraft
import app.ledger.finance.application.SettlementPaymentIds
import app.ledger.finance.domain.CategoryDirection
import app.ledger.finance.domain.DomainViolation
import app.ledger.finance.domain.LocalRevision
import app.ledger.finance.domain.SettlementActivityStatus
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.SystemLedgerCode
import app.ledger.finance.domain.TransactionSource
import app.ledger.finance.domain.UserAccountType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Device proof for the encrypted P22 write path; no in-memory or Robolectric database is used. */
@RunWith(AndroidJUnit4::class)
class SettlementApplicationPortDeviceTest {
    private lateinit var context: Context
    private lateinit var keys: DeviceKeyHierarchy
    private lateinit var references: SecureRoomReferenceDataManagementPort
    private lateinit var settlements: SecureRoomSettlementApplicationPort
    private lateinit var ordinary: SecureRoomOrdinaryTransactionEntryPort

    @Before
    fun prepare() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        keys = DeviceKeyHierarchy(AndroidKeystoreKeys(context), SecurityEnvelopeStore(context))
        keys.destroyLocal(BOOK_ID)
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
                InitialAccountCommand(ACCOUNT_ID, id(201), id(202), id(203), id(204), Instant.parse("2026-08-01T00:00:01Z"), UserAccountType.CASH, "Wallet", JPY, "account", 0xff006c4c.toInt()),
            ).success()
            createFirstCategory(
                BOOK_ID,
                InitialCategoryCommand(CATEGORY_ID, id(211), id(212), id(213), Instant.parse("2026-08-01T00:00:02Z"), CategoryDirection.EXPENSE, "Shared meals", "record", StatisticalNature.CONSUMPTION_EXPENSE, "record", 0xff006c4c.toInt()),
            ).success()
        }
        references = SecureRoomReferenceDataManagementPort(context, keys)
        settlements = SecureRoomSettlementApplicationPort(context, keys)
        ordinary = SecureRoomOrdinaryTransactionEntryPort(context, keys, references)
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        keys.destroyLocal(BOOK_ID)
    }

    @Test
    fun arbitraryParticipantsExpensesPartialSettlementsAndPostSettlementEditRemainAtomicAndRebuildable() = runBlocking {
        createActivity()
        var snapshot = settlements.snapshot(BOOK_ID).success()
        val activity = snapshot.activities.single()
        assertEquals(1, snapshot.participants.count { it.isSelf && it.active })
        assertEquals(setOf(SELF_ID, FRIEND_ID, COLLEAGUE_ID), activity.participants.map { it.id }.toSet())
        assertEquals(2L, scalar("SELECT COUNT(*) FROM ledger_account WHERE system_code LIKE 'SETTLEMENT:%'"))

        val selfExpense = expense(
            seed = 10_000L,
            transactionId = SELF_EXPENSE_ID,
            revisionId = SELF_EXPENSE_REVISION_ID,
            payerId = SELF_ID,
            accountId = ACCOUNT_ID,
            total = 200L,
            shares = listOf(share(SELF_ID, 200L, 100L), share(FRIEND_ID, 0L, 60L), share(COLLEAGUE_ID, 0L, 40L)),
        )
        val selfReceipt = settlements.recordExpense(selfExpense).success()
        assertEquals(selfReceipt, settlements.recordExpense(selfExpense).success())
        assertEquals(-200L, accountBalance())
        assertEquals(100L, scalar("SELECT SUM(base_amount_minor) FROM economic_effect"))
        assertEquals(100L, scalar("SELECT SUM(polarity*base_amount_minor) FROM budget_effect"))
        assertEquals(0L, unbalancedJournals())
        assertPositions(mapOf(SELF_ID to 100L, FRIEND_ID to -60L, COLLEAGUE_ID to -40L))

        val externalExpense = expense(
            seed = 20_000L,
            transactionId = EXTERNAL_EXPENSE_ID,
            revisionId = EXTERNAL_EXPENSE_REVISION_ID,
            payerId = FRIEND_ID,
            accountId = null,
            total = 160L,
            shares = listOf(share(SELF_ID, 0L, 80L), share(FRIEND_ID, 160L, 80L), share(COLLEAGUE_ID, 0L, 0L)),
        )
        settlements.recordExpense(externalExpense).success()
        assertEquals(-200L, accountBalance())
        assertEquals(180L, scalar("SELECT SUM(base_amount_minor) FROM economic_effect"))
        assertEquals(180L, scalar("SELECT SUM(polarity*base_amount_minor) FROM budget_effect"))
        assertPositions(mapOf(SELF_ID to 20L, FRIEND_ID to 20L, COLLEAGUE_ID to -40L))
        assertEquals(0L, unbalancedJournals())

        val externalOnly = payment(30_000L, COLLEAGUE_ID, FRIEND_ID, 20L, null)
        val externalReceipt = settlements.recordPayment(externalOnly).success()
        assertEquals(externalReceipt, settlements.recordPayment(externalOnly).success())
        assertEquals(-200L, accountBalance())
        assertEquals(2L, scalar("SELECT COUNT(*) FROM current_transaction_projection"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM settlement_payment_record WHERE linked_transaction_id IS NULL"))
        assertPositions(mapOf(SELF_ID to 20L, FRIEND_ID to 0L, COLLEAGUE_ID to -20L))

        val selfSettlement = payment(40_000L, COLLEAGUE_ID, SELF_ID, 20L, ACCOUNT_ID)
        val localReceipt = settlements.recordPayment(selfSettlement).success()
        assertEquals(localReceipt, settlements.recordPayment(selfSettlement).success())
        snapshot = settlements.snapshot(BOOK_ID).success()
        assertEquals(SettlementActivityStatus.SETTLED, snapshot.activities.single().status)
        assertTrue(snapshot.activities.single().positions.all { it.netPositionMinor == 0L })
        assertEquals(-180L, accountBalance())
        assertEquals(3L, scalar("SELECT COUNT(*) FROM current_transaction_projection"))
        assertEquals(2L, scalar("SELECT COUNT(*) FROM settlement_payment_record"))
        assertEquals(180L, scalar("SELECT SUM(base_amount_minor) FROM economic_effect"))
        assertEquals(180L, scalar("SELECT SUM(polarity*base_amount_minor) FROM budget_effect"))

        val immutablePaymentDigest = blob("SELECT hex(group_concat(hex(uid)||':'||amount_minor||':'||occurred_at, '|')) FROM settlement_payment_record ORDER BY id")
        val edit = editedSelfExpense(snapshot.localRevision)
        ordinary.submit(edit).success()
        snapshot = settlements.snapshot(BOOK_ID).success()
        assertEquals(SettlementActivityStatus.REQUIRES_ADDITIONAL_SETTLEMENT, snapshot.activities.single().status)
        assertTrue(snapshot.activities.single().requiresAdditionalSettlement)
        assertPositions(mapOf(SELF_ID to 20L, FRIEND_ID to 0L, COLLEAGUE_ID to -20L))
        assertEquals(-200L, accountBalance())
        assertEquals(2L, scalar("SELECT COUNT(*) FROM settlement_payment_record"))
        assertArrayEquals(immutablePaymentDigest, blob("SELECT hex(group_concat(hex(uid)||':'||amount_minor||':'||occurred_at, '|')) FROM settlement_payment_record ORDER BY id"))

        val stale = ordinary.submit(edit.copy(ids = ordinaryIds(60_000L, SELF_EXPENSE_ID, id(69_999L)), expectedRevisionId = SELF_EXPENSE_REVISION_ID))
        assertEquals(DomainViolation.StaleExpectedRevision, (stale as DomainResult.Failure).error)
        assertEquals(2L, scalar("SELECT COUNT(*) FROM transaction_revision WHERE transaction_id=(SELECT id FROM business_transaction WHERE uid=?)", SELF_EXPENSE_ID.bytes))

        val before = withDatabase { RoomProjectionEngine().canonicalTableHashes(it) }
        settlements.rebuildAndAudit(BOOK_ID).success()
        val after = withDatabase { RoomProjectionEngine().canonicalTableHashes(it) }
        assertEquals(before, after)
        assertEquals(0L, unbalancedJournals())
        assertEquals("ok", textScalar("PRAGMA integrity_check"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM pragma_foreign_key_check"))
        assertEquals(scalar("SELECT local_revision FROM book"), scalar("SELECT MIN(as_of_local_revision) FROM settlement_position_projection"))
        assertFalse(snapshot.activities.single().payments.any { it.id == externalOnly.ids.transactionId })
        assertNull(snapshot.activities.single().payments.single { it.id == externalOnly.ids.paymentRecordId }.linkedTransactionId)
    }

    @Test
    fun selfUniquenessInvalidAccountAndExternalOrdinaryEntryFailClosedOrCommitExactly() = runBlocking {
        createActivity()
        val before = scalar("SELECT local_revision FROM book")
        val snapshot = settlements.snapshot(BOOK_ID).success()
        val invalidSelf = activityRequest(
            seed = 70_000L,
            expectedRevision = snapshot.localRevision,
            expectedCommit = snapshot.activities.single().lastCommitId,
            participants = listOf(
                SettlementParticipantDraft(id(70_900L), "Another self", true),
                SettlementParticipantDraft(FRIEND_ID, "Friend", false),
            ),
        )
        assertTrue(settlements.saveActivity(invalidSelf) is DomainResult.Failure)
        assertEquals(before, scalar("SELECT local_revision FROM book"))
        assertEquals(3L, scalar("SELECT COUNT(*) FROM participant"))

        val stale = activityRequest(71_000L, localRevision(0L), snapshot.activities.single().lastCommitId)
        assertEquals(DomainViolation.StaleExpectedRevision, (settlements.saveActivity(stale) as DomainResult.Failure).error)
        assertEquals(before, scalar("SELECT local_revision FROM book"))

        val invalidExternal = payment(72_000L, FRIEND_ID, COLLEAGUE_ID, 10L, ACCOUNT_ID)
        assertTrue(settlements.recordPayment(invalidExternal) is DomainResult.Failure)
        assertEquals(0L, scalar("SELECT COUNT(*) FROM settlement_payment_record"))
        assertEquals(before, scalar("SELECT local_revision FROM book"))

        ordinary.submit(externalOrdinaryExpense()).success()
        assertEquals(0L, accountBalance())
        assertEquals(40L, scalar("SELECT SUM(base_amount_minor) FROM economic_effect"))
        assertEquals(40L, scalar("SELECT SUM(polarity*base_amount_minor) FROM budget_effect"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM current_transaction_projection"))
        assertPositions(mapOf(SELF_ID to -40L, FRIEND_ID to 100L, COLLEAGUE_ID to -60L))
        assertEquals(0L, unbalancedJournals())
    }

    private suspend fun createActivity() {
        val request = activityRequest(1_000L, settlements.snapshot(BOOK_ID).success().localRevision, null)
        settlements.saveActivity(request).success()
    }

    private fun activityRequest(
        seed: Long,
        expectedRevision: LocalRevision,
        expectedCommit: StableId?,
        participants: List<SettlementParticipantDraft> = listOf(
            SettlementParticipantDraft(SELF_ID, "Me", true),
            SettlementParticipantDraft(FRIEND_ID, "Friend", false),
            SettlementParticipantDraft(COLLEAGUE_ID, "Colleague", false),
        ),
    ) = SaveSettlementActivityRequest(
        SettlementMutationIds(
            BOOK_ID,
            CommandId(id(seed)),
            id(seed + 1),
            id(seed + 2),
            List(participants.size + 1) { id(seed + 10 + it) },
            participants.filterNot { it.isSelf }.associate { it.id to id(seed + 100 + participants.indexOf(it)) },
            expectedRevision,
        ),
        ACTIVITY_ID,
        expectedCommit,
        "Summer trip",
        "Shared local expenses",
        JPY,
        null,
        LocalDate.of(2026, 8, 1),
        LocalDate.of(2026, 8, 31),
        SettlementActivityStatus.ACTIVE,
        participants,
        Instant.ofEpochMilli(seed),
    )

    private fun expense(
        seed: Long,
        transactionId: StableId,
        revisionId: StableId,
        payerId: StableId,
        accountId: StableId?,
        total: Long,
        shares: List<SettlementExpenseShareDraft>,
    ) = RecordSettlementExpenseRequest(
        SettlementExpenseIds(BOOK_ID, CommandId(id(seed)), transactionId, revisionId, id(seed + 1), id(seed + 2), (seed + 10..seed + 600).map(::id)),
        CATEGORY_ID,
        ACTIVITY_ID,
        payerId,
        accountId,
        null,
        total,
        accountId?.let { total },
        total,
        null,
        null,
        shares,
        null,
        Instant.parse("2026-08-05T03:00:00Z").plusMillis(seed),
        ZONE,
        LocalDate.of(2026, 8, 5),
        "shared expense",
        Instant.ofEpochMilli(seed),
    )

    private fun payment(seed: Long, payer: StableId, payee: StableId, amount: Long, accountId: StableId?) = RecordSettlementPaymentRequest(
        SettlementPaymentIds(BOOK_ID, CommandId(id(seed)), id(seed + 1), id(seed + 2), id(seed + 3), id(seed + 4), id(seed + 5), (seed + 10..seed + 600).map(::id)),
        ACTIVITY_ID,
        payer,
        payee,
        amount,
        accountId,
        accountId?.let { amount },
        accountId?.let { amount },
        null,
        null,
        Instant.parse("2026-08-06T03:00:00Z").plusMillis(seed),
        ZONE,
        LocalDate.of(2026, 8, 6),
        "partial settlement",
        Instant.ofEpochMilli(seed),
    )

    private fun editedSelfExpense(localRevision: LocalRevision) = OrdinaryTransactionWriteRequest(
        ordinaryIds(50_000L, SELF_EXPENSE_ID, EDITED_SELF_EXPENSE_REVISION_ID),
        SELF_EXPENSE_REVISION_ID,
        OrdinaryDirection.EXPENSE,
        CATEGORY_ID,
        OrdinaryAmountDraft("220", 220L, JPY, 220L, 220L),
        ACCOUNT_ID,
        null,
        null,
        Instant.parse("2026-08-05T03:00:10Z"),
        ZONE,
        LocalDate.of(2026, 8, 5),
        null,
        null,
        ACTIVITY_ID,
        listOf(
            OrdinarySettlementShareDraft(SELF_ID, 220L, 100L, null, 0L),
            OrdinarySettlementShareDraft(FRIEND_ID, 0L, 60L, null, 0L),
            OrdinarySettlementShareDraft(COLLEAGUE_ID, 0L, 60L, null, 0L),
        ),
        null,
        null,
        "edited after settlement",
        emptyList(),
        TransactionSource.MANUAL,
        null,
        Instant.ofEpochMilli(50_000L),
    ).also { require(localRevision.value > 0L) }

    private fun externalOrdinaryExpense() = OrdinaryTransactionWriteRequest(
        ordinaryIds(80_000L, id(80_700L), id(80_701L)),
        null,
        OrdinaryDirection.EXPENSE,
        CATEGORY_ID,
        OrdinaryAmountDraft("100", 100L, JPY, 100L, 100L),
        null,
        null,
        null,
        Instant.parse("2026-08-07T03:00:00Z"),
        ZONE,
        LocalDate.of(2026, 8, 7),
        null,
        null,
        ACTIVITY_ID,
        listOf(
            OrdinarySettlementShareDraft(SELF_ID, 0L, 40L, null, 0L),
            OrdinarySettlementShareDraft(FRIEND_ID, 100L, 0L, null, 0L),
            OrdinarySettlementShareDraft(COLLEAGUE_ID, 0L, 60L, null, 0L),
        ),
        null,
        null,
        "external payer through ordinary entry",
        emptyList(),
        TransactionSource.MANUAL,
        null,
        Instant.ofEpochMilli(80_000L),
    )

    private fun ordinaryIds(seed: Long, transactionId: StableId, revisionId: StableId) = OrdinaryTransactionWriteIds(
        BOOK_ID,
        id(seed),
        transactionId,
        revisionId,
        id(seed + 1),
        id(seed + 2),
        (seed + 10..seed + 600).map(::id),
        emptyList(),
    )

    private fun share(participant: StableId, paid: Long, owed: Long) = SettlementExpenseShareDraft(participant, paid, owed, null, 0L)

    private fun assertPositions(expected: Map<StableId, Long>) {
        val actual = settlementsSnapshot().activities.single().positions.associate { it.participantId to it.netPositionMinor }
        assertEquals(expected, actual)
        assertEquals(0L, actual.values.fold(0L, Math::addExact))
    }

    private fun settlementsSnapshot() = runBlocking { settlements.snapshot(BOOK_ID).success() }

    private fun accountBalance(): Long = scalar("SELECT normal_balance_minor FROM account_balance_current abc JOIN user_account ua ON ua.id=abc.account_id WHERE ua.uid=?", ACCOUNT_ID.bytes)

    private fun unbalancedJournals(): Long = scalar(
        "SELECT COUNT(*) FROM (SELECT journal_entry_id FROM posting GROUP BY journal_entry_id HAVING SUM(CASE side WHEN 0 THEN base_amount_minor ELSE -base_amount_minor END)<>0)",
    )

    private fun scalar(sql: String, vararg args: Any?): Long = withDatabase { db ->
        db.query(sql, args).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }
    }

    private fun textScalar(sql: String): String = withDatabase { db ->
        db.query(sql).use {
            it.moveToFirst()
            it.getString(0)
        }
    }

    private fun blob(sql: String): ByteArray = textScalar(sql).toByteArray()

    private fun <T> withDatabase(block: (androidx.sqlite.db.SupportSQLiteDatabase) -> T): T = keys.open(BOOK_ID).use { opened ->
        val database = opened.databaseDek.useBytes { EncryptedDatabaseFactory.openPrimary(context, it) }
        try {
            database.inLedgerTransaction(block)
        } finally {
            database.close()
        }
    }

    private fun id(value: Long): StableId = StableId.fromUuid(UUID(0x2222L, value))

    private fun localRevision(value: Long): LocalRevision = (LocalRevision.of(value) as DomainResult.Success).value

    private fun <T> DomainResult<T>.success(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error(error.toString())
    }

    private companion object {
        fun currency(code: String): CurrencyCode = requireNotNull(CurrencyCode.parse(code).getOrNull())
        val BOOK_ID = StableId.fromUuid(UUID(0x2222L, 1))
        val ACCOUNT_ID = StableId.fromUuid(UUID(0x2222L, 200))
        val CATEGORY_ID = StableId.fromUuid(UUID(0x2222L, 210))
        val ACTIVITY_ID = StableId.fromUuid(UUID(0x2222L, 300))
        val SELF_ID = StableId.fromUuid(UUID(0x2222L, 301))
        val FRIEND_ID = StableId.fromUuid(UUID(0x2222L, 302))
        val COLLEAGUE_ID = StableId.fromUuid(UUID(0x2222L, 303))
        val SELF_EXPENSE_ID = StableId.fromUuid(UUID(0x2222L, 400))
        val SELF_EXPENSE_REVISION_ID = StableId.fromUuid(UUID(0x2222L, 401))
        val EDITED_SELF_EXPENSE_REVISION_ID = StableId.fromUuid(UUID(0x2222L, 402))
        val EXTERNAL_EXPENSE_ID = StableId.fromUuid(UUID(0x2222L, 500))
        val EXTERNAL_EXPENSE_REVISION_ID = StableId.fromUuid(UUID(0x2222L, 501))
        val JPY = currency("JPY")
        val ZONE: ZoneId = ZoneId.of("Asia/Tokyo")
    }
}
