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
import app.ledger.finance.application.AutomationMutationIds
import app.ledger.finance.application.BlueprintDraft
import app.ledger.finance.application.FormalOccurrenceGenerator
import app.ledger.finance.application.InitialAccountCommand
import app.ledger.finance.application.InitialCategoryCommand
import app.ledger.finance.application.InitializeLedgerCommand
import app.ledger.finance.application.LedgerGenesisIds
import app.ledger.finance.application.ModifyOccurrenceRequest
import app.ledger.finance.application.OrdinaryAmountDraft
import app.ledger.finance.application.OrdinaryDirection
import app.ledger.finance.application.OrdinaryTransactionWriteIds
import app.ledger.finance.application.OrdinaryTransactionWriteRequest
import app.ledger.finance.application.RecurrenceSeriesDraft
import app.ledger.finance.application.SaveBlueprintRequest
import app.ledger.finance.application.SaveRecurrenceRequest
import app.ledger.finance.domain.CategoryDirection
import app.ledger.finance.domain.EntityStatus
import app.ledger.finance.domain.MissingDayPolicy
import app.ledger.finance.domain.RecurrenceCandidateStatus
import app.ledger.finance.domain.RecurrenceFrequency
import app.ledger.finance.domain.RecurrenceGenerationMode
import app.ledger.finance.domain.RecurrenceModificationScope
import app.ledger.finance.domain.RecurrenceOccurrenceStatus
import app.ledger.finance.domain.RecurrenceRule
import app.ledger.finance.domain.RecurrenceStatus
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.SystemLedgerCode
import app.ledger.finance.domain.TransactionKind
import app.ledger.finance.domain.TransactionSource
import app.ledger.finance.domain.UserAccountType
import app.ledger.finance.domain.WeekendAdjustment
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AutomationApplicationPortDeviceTest {
    private lateinit var context: Context
    private lateinit var keys: DeviceKeyHierarchy
    private lateinit var automation: SecureRoomAutomationApplicationPort
    private lateinit var ordinary: SecureRoomOrdinaryTransactionEntryPort

    @Before
    fun prepare() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        keys = DeviceKeyHierarchy(AndroidKeystoreKeys(context), SecurityEnvelopeStore(context))
        keys.destroyLocal(BOOK_ID)
        val initialization = SecureRoomLedgerInitializationPort(context, keys)
        initialization.initialize(
            InitializeLedgerCommand(
                LedgerGenesisIds(BOOK_ID, id(2), id(3), id(4), SystemLedgerCode.entries.mapIndexed { index, code -> code to id(100L + index) }.toMap()),
                currency("JPY"),
                ZONE,
                Instant.ofEpochMilli(1_000),
            ),
        ).success()
        initialization.createFirstAccount(
            BOOK_ID,
            InitialAccountCommand(ACCOUNT_ID, id(201), id(202), id(203), id(204), Instant.ofEpochMilli(2_000), UserAccountType.CASH, "Wallet", currency("JPY"), "account", 0xff006c4c.toInt()),
        ).success()
        initialization.createFirstCategory(
            BOOK_ID,
            InitialCategoryCommand(CATEGORY_ID, id(211), id(212), id(213), Instant.ofEpochMilli(3_000), CategoryDirection.EXPENSE, "Food", "food", StatisticalNature.CONSUMPTION_EXPENSE, "record", 0xff006c4c.toInt()),
        ).success()
        val generator = FormalOccurrenceGenerator { DomainResult.Failure(app.ledger.finance.domain.DomainViolation.InvalidField("test.formal")) }
        automation = SecureRoomAutomationApplicationPort(context, keys, generator)
        ordinary = SecureRoomOrdinaryTransactionEntryPort(context, keys)
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        keys.destroyLocal(BOOK_ID)
    }

    @Test
    fun startupWorkerRestartAndManualRetryNeverDuplicateCandidateOrFacts() = runBlocking {
        saveBlueprintAndCandidateSeries()
        val first = automation.catchUp(BOOK_ID, Instant.parse("2026-08-06T23:59:59Z")).success()
        assertEquals(3, first.createdCandidates)
        val second = automation.catchUp(BOOK_ID, Instant.parse("2026-08-06T23:59:59Z")).success()
        assertEquals(0, second.createdCandidates)

        val restarted = SecureRoomAutomationApplicationPort(
            context,
            keys,
            FormalOccurrenceGenerator { DomainResult.Failure(app.ledger.finance.domain.DomainViolation.InvalidField("test.formal")) },
        )
        val afterRestart = restarted.catchUp(BOOK_ID, Instant.parse("2026-08-06T23:59:59Z")).success()
        assertEquals(0, afterRestart.createdCandidates)
        val candidate = restarted.snapshot(BOOK_ID).success().candidates.first()
        assertEquals(0, restarted.retryOccurrence(BOOK_ID, candidate.occurrenceId).success().createdCandidates)

        read { db ->
            assertEquals(3L, scalar(db, "SELECT COUNT(*) FROM recurrence_occurrence"))
            assertEquals(3L, scalar(db, "SELECT COUNT(*) FROM recurrence_candidate"))
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM business_transaction"))
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM journal_entry"))
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM posting"))
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM economic_effect"))
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM budget_effect"))
        }
    }

    @Test
    fun candidateConfirmationAndFinancialFactsCommitAtomicallyThroughCoordinator() = runBlocking {
        saveBlueprintAndCandidateSeries()
        automation.catchUp(BOOK_ID, Instant.parse("2026-08-04T23:59:59Z")).success()
        val candidate = automation.snapshot(BOOK_ID).success().candidates.single()
        val request = ordinaryRequest(candidate.id)
        ordinary.submit(request).success()
        ordinary.submit(request).success()

        val snapshot = automation.snapshot(BOOK_ID).success()
        assertEquals(RecurrenceCandidateStatus.ACCEPTED, snapshot.candidates.single().status)
        assertEquals(RecurrenceOccurrenceStatus.TRANSACTION_CREATED, snapshot.occurrences.single().status)
        assertEquals(TRANSACTION_ID, snapshot.occurrences.single().transactionId)
        automation.completeCandidate(BOOK_ID, candidate.id, TRANSACTION_ID).success()
        read { db ->
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM business_transaction"))
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM journal_entry"))
            assertTrue(scalar(db, "SELECT COUNT(*) FROM posting") >= 2L)
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM command_receipt WHERE command_uid=x'${COMMAND_ID.bytes.toHex()}'"))
            assertEquals(
                "ok",
                db.query("PRAGMA integrity_check").use {
                    it.moveToFirst()
                    it.getString(0)
                },
            )
        }
    }

    @Test
    fun onlyThisFutureAndEntireScopesAppendAuditableSeriesHistory() = runBlocking {
        saveBlueprintAndCandidateSeries()
        val original = automation.snapshot(BOOK_ID).success().series.single()
        val onlyThisRequest = ModifyOccurrenceRequest(
            ids(600, 5),
            SERIES_ID,
            LocalDate.of(2026, 8, 5),
            RecurrenceModificationScope.THIS_OCCURRENCE,
            recurrenceDraft(original, id(610), original.revisionId, LocalTime.of(10, 0)),
        )
        val onlyThisReceipt = automation.modifyOccurrence(onlyThisRequest).success()
        assertEquals(onlyThisReceipt, automation.modifyOccurrence(onlyThisRequest).success())
        val afterOnlyThis = automation.snapshot(BOOK_ID).success()
        assertEquals(6L, afterOnlyThis.localRevision)

        val futureRequest = ModifyOccurrenceRequest(
            ids(620, 6),
            SERIES_ID,
            LocalDate.of(2026, 8, 5),
            RecurrenceModificationScope.THIS_AND_FUTURE,
            recurrenceDraft(afterOnlyThis.series.single(), id(630), original.revisionId, LocalTime.of(9, 0)),
        )
        val futureReceipt = automation.modifyOccurrence(futureRequest).success()
        assertEquals(futureReceipt, automation.modifyOccurrence(futureRequest).success())
        val afterFuture = automation.snapshot(BOOK_ID).success()
        assertEquals(LocalDate.of(2026, 8, 5), afterFuture.series.single().startDate)

        val entireRequest = ModifyOccurrenceRequest(
            ids(640, 7),
            SERIES_ID,
            LocalDate.of(2026, 8, 6),
            RecurrenceModificationScope.ENTIRE_SERIES,
            recurrenceDraft(afterFuture.series.single(), id(650), afterFuture.series.single().revisionId, LocalTime.of(9, 0)),
        )
        val entireReceipt = automation.modifyOccurrence(entireRequest).success()
        assertEquals(entireReceipt, automation.modifyOccurrence(entireRequest).success())
        read { db ->
            assertEquals(3L, scalar(db, "SELECT COUNT(*) FROM recurrence_series_revision"))
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM recurrence_exception"))
            assertEquals(4L, scalar(db, "SELECT COUNT(*) FROM entity_revision WHERE entity_type=${app.ledger.finance.domain.EntityType.RECURRENCE_SERIES.ordinal}"))
            assertEquals(5L, scalar(db, "SELECT COUNT(*) FROM command_receipt"))
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM book_commit WHERE local_revision>=4 AND command_uid IS NULL"))
        }
    }

    @Test
    fun automationCommandReplayReturnsFirstReceiptBeforeStaleRevisionChecks() = runBlocking {
        val request = SaveBlueprintRequest(
            ids(300, 3),
            BlueprintDraft(BLUEPRINT_ID, BLUEPRINT_REVISION_ID, null, "Daily food", "record", 0xff006c4c.toInt(), EntityStatus.ACTIVE, TransactionKind.EXPENSE, CATEGORY_ID, ACCOUNT_ID, null, null, null, null, null, null, "100", currency("JPY"), null, null),
        )

        val first = automation.saveBlueprint(request).success()
        val replayed = automation.saveBlueprint(request).success()
        val conflicting = automation.saveBlueprint(request.copy(draft = request.draft.copy(name = "Different payload")))

        assertEquals(first, replayed)
        assertEquals(
            DomainResult.Failure(app.ledger.finance.domain.DomainViolation.DuplicateCommandPayloadMismatch),
            conflicting,
        )
        assertEquals(CommandId(id(300)), first.commandId)
        read { db ->
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM transaction_blueprint_revision"))
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM command_receipt WHERE command_uid=x'${id(300).bytes.toHex()}'"))
            assertEquals(4L, scalar(db, "SELECT local_revision FROM book WHERE id=1"))
        }
    }

    private suspend fun saveBlueprintAndCandidateSeries() {
        val blueprintResult = automation.saveBlueprint(
            SaveBlueprintRequest(
                ids(300, 3),
                BlueprintDraft(BLUEPRINT_ID, BLUEPRINT_REVISION_ID, null, "Daily food", "record", 0xff006c4c.toInt(), EntityStatus.ACTIVE, TransactionKind.EXPENSE, CATEGORY_ID, ACCOUNT_ID, null, null, null, null, null, null, "100", currency("JPY"), null, null),
            ),
        )
        if (blueprintResult is DomainResult.Failure) error("save blueprint: ${blueprintResult.error.code}")
        val seriesResult = automation.saveSeries(
            SaveRecurrenceRequest(
                ids(400, 4),
                RecurrenceSeriesDraft(
                    SERIES_ID, SERIES_REVISION_ID, null, BLUEPRINT_ID, RecurrenceStatus.ACTIVE,
                    RecurrenceRule(RecurrenceFrequency.DAILY, 1, emptySet(), null, null, null, MissingDayPolicy.MOVE_TO_MONTH_END, WeekendAdjustment.NONE),
                    LocalDate.of(2026, 8, 4), LocalDate.of(2026, 8, 6), null, LocalTime.of(9, 0), ZONE, RecurrenceGenerationMode.CANDIDATE, null, true,
                ),
            ),
        )
        if (seriesResult is DomainResult.Failure) error("save series: ${seriesResult.error.code}")
    }

    private fun recurrenceDraft(
        source: app.ledger.finance.application.RecurrenceSeriesView,
        revisionId: StableId,
        expectedRevisionId: StableId,
        occurrenceTime: LocalTime,
    ) = RecurrenceSeriesDraft(
        source.id,
        revisionId,
        expectedRevisionId,
        source.blueprintId,
        source.status,
        source.rule,
        source.startDate,
        source.endDate,
        source.maxOccurrences,
        occurrenceTime,
        source.zoneId,
        source.generationMode,
        source.fixedPlaceId,
        source.notifyCandidate,
    )

    private fun ordinaryRequest(candidateId: StableId) = OrdinaryTransactionWriteRequest(
        OrdinaryTransactionWriteIds(BOOK_ID, COMMAND_ID, TRANSACTION_ID, id(504), id(505), id(506), (510L..800L).map(::id), (810L..817L).map(::id)),
        null, OrdinaryDirection.EXPENSE, CATEGORY_ID, OrdinaryAmountDraft("100", 100, currency("JPY"), 100, 100), ACCOUNT_ID, null, null,
        Instant.parse("2026-08-04T00:00:00Z"), ZONE, LocalDate.of(2026, 8, 4), null, null, null, emptyList(), null, null, null, emptyList(),
        TransactionSource.MANUAL, candidateId, Instant.parse("2026-08-06T00:00:00Z"), candidateId,
    )

    private fun ids(seed: Long, expected: Long) = AutomationMutationIds(
        BOOK_ID,
        CommandId(id(seed)),
        id(seed + 1),
        id(seed + 2),
        id(seed + 3),
        expected,
        Instant.ofEpochMilli(seed * 100),
    )
    private fun read(block: (androidx.sqlite.db.SupportSQLiteDatabase) -> Unit) {
        keys.open(BOOK_ID).use { opened ->
            val database = opened.databaseDek.useBytes { EncryptedDatabaseFactory.openPrimary(context, it) }
            try {
                database.readLedger(block)
            } finally {
                database.close()
            }
        }
    }
    private fun scalar(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Long = db.query(sql).use {
        it.moveToFirst()
        it.getLong(0)
    }
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun currency(value: String): CurrencyCode = requireNotNull(CurrencyCode.parse(value).getOrNull())
    private fun id(value: Long): StableId = StableId.fromUuid(UUID(0x23L, value))
    private fun <T> DomainResult<T>.success(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error(error.code)
    }

    private companion object {
        val BOOK_ID = StableId.fromUuid(UUID(0x23L, 1))
        val ACCOUNT_ID = StableId.fromUuid(UUID(0x23L, 200))
        val CATEGORY_ID = StableId.fromUuid(UUID(0x23L, 210))
        val BLUEPRINT_ID = StableId.fromUuid(UUID(0x23L, 220))
        val BLUEPRINT_REVISION_ID = StableId.fromUuid(UUID(0x23L, 221))
        val SERIES_ID = StableId.fromUuid(UUID(0x23L, 230))
        val SERIES_REVISION_ID = StableId.fromUuid(UUID(0x23L, 231))
        val COMMAND_ID = StableId.fromUuid(UUID(0x23L, 500))
        val TRANSACTION_ID = StableId.fromUuid(UUID(0x23L, 501))
        val ZONE = ZoneId.of("Asia/Tokyo")
    }
}
