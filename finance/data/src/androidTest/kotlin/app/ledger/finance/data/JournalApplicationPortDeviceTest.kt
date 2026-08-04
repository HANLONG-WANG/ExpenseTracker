@file:Suppress("LongMethod", "MagicNumber", "MaxLineLength")

package app.ledger.finance.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import app.ledger.finance.application.JournalBulkEditPatch
import app.ledger.finance.application.JournalBulkEditRequest
import app.ledger.finance.application.JournalFieldUpdate
import app.ledger.finance.application.JournalMutationIds
import app.ledger.finance.application.JournalMutationRequest
import app.ledger.finance.application.JournalPageRequest
import app.ledger.finance.application.JournalSavedFilterCommand
import app.ledger.finance.application.JournalSelectionMode
import app.ledger.finance.application.JournalSelectionSpec
import app.ledger.finance.application.LedgerGenesisIds
import app.ledger.finance.application.OrdinaryAmountDraft
import app.ledger.finance.application.OrdinaryDirection
import app.ledger.finance.application.OrdinaryTransactionWriteIds
import app.ledger.finance.application.OrdinaryTransactionWriteRequest
import app.ledger.finance.application.PurgeIneligibilityReason
import app.ledger.finance.domain.CategoryDirection
import app.ledger.finance.domain.RevisionAction
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.SystemLedgerCode
import app.ledger.finance.domain.TransactionFilter
import app.ledger.finance.domain.TransactionLifecycleState
import app.ledger.finance.domain.TransactionSource
import app.ledger.finance.domain.UserAccountType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class JournalApplicationPortDeviceTest {
    private lateinit var context: Context
    private lateinit var keys: DeviceKeyHierarchy
    private lateinit var initialization: SecureRoomLedgerInitializationPort
    private lateinit var entry: SecureRoomOrdinaryTransactionEntryPort
    private lateinit var journal: SecureRoomJournalApplicationPort

    @Before
    fun prepare() {
        runBlocking {
            context = ApplicationProvider.getApplicationContext()
            context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
            keys = DeviceKeyHierarchy(AndroidKeystoreKeys(context), SecurityEnvelopeStore(context))
            keys.destroyLocal(BOOK_ID)
            initialization = SecureRoomLedgerInitializationPort(context, keys)
            entry = SecureRoomOrdinaryTransactionEntryPort(context, keys)
            journal = SecureRoomJournalApplicationPort(context, keys)
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
            entry.submit(createRequest(1_000, TRANSACTION_A, "alpha private note")).success()
            entry.submit(createRequest(2_000, TRANSACTION_B, "beta private note")).success()
        }
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        keys.destroyLocal(BOOK_ID)
        context.noBackupFilesDir.resolve("journal_filters").deleteRecursively()
    }

    @Test
    fun bulkHistoryRestoreTrashAndPurgeAssessmentUseImmutableAtomicPaths() = runBlocking {
        val activeFilter = TransactionFilter(lifecycleStates = setOf(TransactionLifecycleState.ACTIVE))
        val globalRows = journal.page(JournalPageRequest(BOOK_ID, activeFilter)).success()
        assertEquals(2, globalRows.items.size)
        assertTrue(globalRows.items.all { it.runningBalanceMinor == null })
        val accountRows = journal.page(
            JournalPageRequest(BOOK_ID, activeFilter, runningBalanceAccountId = ACCOUNT_ID),
        ).success()
        assertEquals(2, accountRows.items.size)
        assertTrue(accountRows.items.all { it.runningBalanceMinor != null })
        val selection = JournalSelectionSpec(
            JournalSelectionSpec.fingerprint(activeFilter),
            JournalSelectionMode.EXPLICIT,
            includedIds = setOf(TRANSACTION_A, TRANSACTION_B),
        )
        journal.bulkEdit(
            JournalBulkEditRequest(
                BOOK_ID,
                id(4_000),
                id(4_001),
                id(4_002),
                selection,
                activeFilter,
                JournalBulkEditPatch(note = JournalFieldUpdate.Set("bulk-private-note")),
                Instant.parse("2026-08-04T00:00:00Z"),
            ),
        ).success()
        listOf(TRANSACTION_A, TRANSACTION_B).forEach { transactionId ->
            val detail = requireNotNull(journal.detail(BOOK_ID, transactionId).success())
            assertEquals("bulk-private-note", detail.fullNote)
            val history = journal.history(BOOK_ID, transactionId).success()
            assertEquals(listOf(RevisionAction.BULK_EDIT, RevisionAction.CREATE), history.map { it.action })
        }

        val detailAfterBulk = requireNotNull(journal.detail(BOOK_ID, TRANSACTION_A).success())
        val historyAfterBulk = journal.history(BOOK_ID, TRANSACTION_A).success()
        val originalRevision = historyAfterBulk.last().revisionId
        journal.mutate(
            JournalMutationRequest.RestoreHistorical(
                mutationIds(10_000, TRANSACTION_A),
                detailAfterBulk.transaction.revisionId,
                Instant.parse("2026-08-05T00:00:00Z"),
                originalRevision,
            ),
        ).success()
        val restored = requireNotNull(journal.detail(BOOK_ID, TRANSACTION_A).success())
        assertEquals("alpha private note", restored.fullNote)
        assertEquals(RevisionAction.RESTORE, journal.history(BOOK_ID, TRANSACTION_A).success().first().action)

        val purgeAfter = Instant.parse("2026-08-10T00:00:00Z")
        journal.mutate(
            JournalMutationRequest.MoveToTrash(mutationIds(30_000, TRANSACTION_A), restored.transaction.revisionId, Instant.parse("2026-08-06T00:00:00Z"), purgeAfter),
        ).success()
        val assessment = journal.assessPurge(BOOK_ID, TRANSACTION_A, purgeAfter.plusSeconds(1)).success()
        assertTrue(assessment.financiallyEligible)
        assertFalse(assessment.canPurgeNow)
        assertEquals(setOf(PurgeIneligibilityReason.PHYSICAL_PURGE_REQUIRES_MAINTENANCE), assessment.reasons)
        assertEquals(TransactionLifecycleState.TRASHED, requireNotNull(journal.detail(BOOK_ID, TRANSACTION_A).success()).transaction.state)
    }

    @Test
    fun savedFiltersRoundTripUnderAeadWithoutPlaintextAndSupportEveryMutation() = runBlocking {
        val secret = "private-search-47f918"
        val filter = TransactionFilter(searchText = secret, lifecycleStates = setOf(TransactionLifecycleState.ACTIVE))
        journal.mutateSavedFilter(BOOK_ID, JournalSavedFilterCommand.Save(id(7_000), "Monthly", filter, "search · active")).success()
        journal.mutateSavedFilter(BOOK_ID, JournalSavedFilterCommand.Copy(id(7_000), id(7_001), "Monthly copy")).success()
        journal.mutateSavedFilter(BOOK_ID, JournalSavedFilterCommand.SetDefault(id(7_001))).success()
        val reordered = journal.mutateSavedFilter(BOOK_ID, JournalSavedFilterCommand.Reorder(listOf(id(7_001), id(7_000)))).success()
        assertEquals(id(7_001), reordered.first().id)
        assertTrue(reordered.first().isDefault)
        journal.mutateSavedFilter(BOOK_ID, JournalSavedFilterCommand.Delete(id(7_000))).success()
        val reopened = SecureRoomJournalApplicationPort(context, keys).savedFilters(BOOK_ID).success()
        assertEquals(listOf(id(7_001)), reopened.map { it.id })
        assertEquals(secret, reopened.single().filter.searchText)
        val encryptedFiles = context.noBackupFilesDir.resolve("journal_filters").listFiles().orEmpty()
        assertEquals(1, encryptedFiles.size)
        assertTrue(encryptedFiles.single().readBytes().indexOf(secret.toByteArray()) < 0)
    }

    private fun createRequest(seed: Long, transactionId: StableId, note: String): OrdinaryTransactionWriteRequest = OrdinaryTransactionWriteRequest(
        ids = OrdinaryTransactionWriteIds(BOOK_ID, id(seed), transactionId, id(seed + 1), id(seed + 2), id(seed + 3), (seed + 10..seed + 300).map(::id), (seed + 400..seed + 407).map(::id)),
        expectedRevisionId = null, direction = OrdinaryDirection.EXPENSE, categoryId = CATEGORY_ID,
        amount = OrdinaryAmountDraft("1250", 1_250, currency("JPY"), 1_250, 1_250), accountId = ACCOUNT_ID, cardId = null,
        merchantId = null, occurredAt = Instant.parse("2026-08-03T03:30:00Z").plusMillis(seed), zoneId = ZONE, localDate = LocalDate.of(2026, 8, 3),
        projectId = null, goalId = null, settlementActivityId = null, settlementShares = emptyList(), locationRecordId = null, newLocation = null,
        note = note, attachmentIds = emptyList(), source = TransactionSource.MANUAL, sourceReferenceId = null, createdAt = Instant.parse("2026-08-03T04:30:00Z").plusMillis(seed),
    )

    private fun mutationIds(seed: Long, transactionId: StableId): JournalMutationIds = JournalMutationIds(
        BOOK_ID,
        id(seed),
        transactionId,
        id(seed + 1),
        id(seed + 2),
        id(seed + 3),
        (seed + 10..seed + 1_100).map(::id),
        (seed + 1_200..seed + 1_263).map(::id),
    )

    private fun ByteArray.indexOf(needle: ByteArray): Int = indices.firstOrNull { start -> start + needle.size <= size && needle.indices.all { this[start + it] == needle[it] } } ?: -1
    private fun currency(value: String): CurrencyCode = requireNotNull(CurrencyCode.parse(value).getOrNull())
    private fun id(value: Long): StableId = StableId.fromUuid(UUID(0x25L, value))
    private fun <T> DomainResult<T>.success(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error(error.code)
    }

    private companion object {
        val BOOK_ID = StableId.fromUuid(UUID(0x25L, 1))
        val ACCOUNT_ID = StableId.fromUuid(UUID(0x25L, 200))
        val CATEGORY_ID = StableId.fromUuid(UUID(0x25L, 210))
        val TRANSACTION_A = StableId.fromUuid(UUID(0x25L, 900))
        val TRANSACTION_B = StableId.fromUuid(UUID(0x25L, 901))
        val ZONE: ZoneId = ZoneId.of("Asia/Tokyo")
    }
}
