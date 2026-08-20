package app.ledger.widget

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.finance.application.WidgetAccountSnapshot
import app.ledger.finance.application.WidgetBookSnapshot
import app.ledger.finance.application.WidgetCreditSnapshot
import app.ledger.finance.application.WidgetGoalSnapshot
import app.ledger.finance.application.WidgetQuickDirection
import app.ledger.finance.application.WidgetQuickTarget
import app.ledger.finance.application.WidgetQuickTargetKind
import app.ledger.finance.application.WidgetSnapshotApplicationPort
import app.ledger.finance.application.WidgetSnapshotBundle
import app.ledger.finance.domain.LocalRevision
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class LedgerWidgetPolicyTest {
    @Test
    fun exactlyNineFrozenTypesResolveAgainstOnlyBoundedSnapshots() {
        assertEquals(9, LedgerWidgetType.entries.size)
        LedgerWidgetType.entries.forEach { type ->
            val content = LedgerWidgetPolicy.resolve(configuration(type), completeBundle(), TODAY)
            assertInstanceOf(LedgerWidgetContent.Ready::class.java, content, type.name)
        }
    }

    @Test
    fun amountsAreHiddenUnlessEachConfigurationExplicitlyOptsIn() {
        LedgerWidgetType.entries.forEach { type -> assertFalse(configuration(type).revealAmounts) }
        assertTrue(configuration(LedgerWidgetType.QUICK_ENTRY, revealAmounts = true).revealAmounts)
    }

    @Test
    fun selectedAndOptionalDataAbsenceProducesNoEligibleData() {
        val bundle = completeBundle().copy(accounts = emptyList(), creditAccounts = emptyList(), goals = emptyList())
        listOf(LedgerWidgetType.ACCOUNT, LedgerWidgetType.CREDIT_CARD, LedgerWidgetType.GOAL).forEach { type ->
            assertEquals(LedgerWidgetContent.NoEligibleData, LedgerWidgetPolicy.resolve(configuration(type), bundle, TODAY))
        }
        val withoutBudget = completeBundle().copy(
            book = requireNotNull(completeBundle().book).copy(
                monthBudgetAvailableBaseMinor = null,
                monthBudgetUsedBaseMinor = null,
                todayAvailableBaseMinor = null,
            ),
        )
        assertEquals(
            LedgerWidgetContent.NoEligibleData,
            LedgerWidgetPolicy.resolve(configuration(LedgerWidgetType.MONTH_BUDGET), withoutBudget, TODAY),
        )
        assertEquals(
            LedgerWidgetContent.NoEligibleData,
            LedgerWidgetPolicy.resolve(configuration(LedgerWidgetType.TODAY_AVAILABLE), withoutBudget, TODAY),
        )
    }

    @Test
    fun datedSnapshotsExpireButQuickEntryStillOnlyOpensTheForm() {
        val stale = completeBundle().copy(book = requireNotNull(completeBundle().book).copy(snapshotLocalDate = 20260810))
        assertEquals(
            LedgerWidgetContent.Stale,
            LedgerWidgetPolicy.resolve(configuration(LedgerWidgetType.MONTH_CONSUMPTION), stale, TODAY),
        )
        assertInstanceOf(
            LedgerWidgetContent.Ready::class.java,
            LedgerWidgetPolicy.resolve(configuration(LedgerWidgetType.QUICK_ENTRY), stale, TODAY),
        )
    }

    @Test
    fun keyUnavailabilityLocksWidgetAndNoApplicationLockStateIsConsulted() = runBlocking {
        LedgerWidgetRuntime.install(
            snapshots = object : WidgetSnapshotApplicationPort {
                override suspend fun read(bookId: StableId) = DomainResult.Failure(TestSnapshotError)
                override suspend fun quickTargets(bookId: StableId) = DomainResult.Success(emptyList<WidgetQuickTarget>())
            },
            configurations = InMemoryConfigurations,
            localDate = { TODAY },
        )
        assertEquals(
            LedgerWidgetContent.Locked,
            LedgerWidgetRuntime.resolve(configuration(LedgerWidgetType.MONTH_CONSUMPTION)),
        )
    }

    @Test
    fun selectionTypesCannotBePersistedWithoutEligibleSelection() {
        listOf(
            LedgerWidgetType.QUICK_ENTRY,
            LedgerWidgetType.ACCOUNT,
            LedgerWidgetType.CREDIT_CARD,
            LedgerWidgetType.GOAL,
        ).forEach { type ->
            assertThrows(IllegalArgumentException::class.java) {
                LedgerWidgetConfiguration(1, BOOK_ID, type)
            }
        }
    }

    @Test
    fun newlySavedConfigurationIsReadableDuringTheFirstLauncherRefresh() = runBlocking {
        val saved = configuration(LedgerWidgetType.QUICK_ENTRY)
        LedgerWidgetRuntime.install(
            snapshots = object : WidgetSnapshotApplicationPort {
                override suspend fun read(bookId: StableId) = DomainResult.Success(completeBundle())
                override suspend fun quickTargets(bookId: StableId) = DomainResult.Success(emptyList<WidgetQuickTarget>())
            },
            configurations = InMemoryConfigurations,
            localDate = { TODAY },
        )

        LedgerWidgetRuntime.saveConfiguration(saved)

        assertEquals(saved, LedgerWidgetRuntime.readConfiguration(saved.appWidgetId))
    }

    private fun configuration(type: LedgerWidgetType, revealAmounts: Boolean = false): LedgerWidgetConfiguration = LedgerWidgetConfiguration(
        appWidgetId = 42,
        bookId = BOOK_ID,
        type = type,
        selectedId = if (type in SELECTION_TYPES) SELECTED_ID else null,
        quickTargetKind = WidgetQuickTargetKind.CATEGORY.takeIf { type == LedgerWidgetType.QUICK_ENTRY },
        quickDirection = WidgetQuickDirection.EXPENSE.takeIf { type == LedgerWidgetType.QUICK_ENTRY },
        revealAmounts = revealAmounts,
    )

    private fun completeBundle(): WidgetSnapshotBundle = WidgetSnapshotBundle(
        book = WidgetBookSnapshot(
            coreNetFinancialAssetsBaseMinor = 80_000L,
            adjustedNetFinancialPositionBaseMinor = 75_000L,
            baseCurrency = "JPY",
            localRevision = REVISION,
            valuationRevision = REVISION,
            snapshotLocalDate = 20260811,
            monthKey = 202608,
            monthConsumptionBaseMinor = 20_000L,
            previousMonthConsumptionBaseMinor = 18_000L,
            monthBudgetAvailableBaseMinor = 30_000L,
            monthBudgetUsedBaseMinor = 20_000L,
            todayAvailableBaseMinor = 2_000L,
            previousCoreNetFinancialAssetsBaseMinor = 79_000L,
        ),
        accounts = listOf(WidgetAccountSnapshot(SELECTED_ID, "Wallet", 50_000L, 50_000L, "JPY", REVISION)),
        creditAccounts = listOf(WidgetCreditSnapshot(SELECTED_ID, "Card", 5_000L, 45_000L, 5_000L, 20260825, "JPY", REVISION)),
        goals = listOf(WidgetGoalSnapshot(SELECTED_ID, "Trip", 10_000L, 100_000L, "JPY", REVISION)),
    )

    private data object TestSnapshotError : app.ledger.core.common.DomainError {
        override val code: String = "WIDGET_KEY_UNAVAILABLE"
    }

    private data object InMemoryConfigurations : LedgerWidgetConfigurationRepository {
        override suspend fun activeBookId(): StableId = BOOK_ID
        override suspend fun read(appWidgetId: Int): LedgerWidgetConfiguration? = null
        override suspend fun save(configuration: LedgerWidgetConfiguration) = Unit
        override suspend fun delete(appWidgetIds: Set<Int>) = Unit
    }

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 8, 11)
        val BOOK_ID: StableId = StableId.fromUuid(UUID(1L, 1L))
        val SELECTED_ID: StableId = StableId.fromUuid(UUID(1L, 2L))
        val REVISION: LocalRevision = LocalRevision.of(7L).let { (it as DomainResult.Success).value }
        val SELECTION_TYPES = setOf(
            LedgerWidgetType.QUICK_ENTRY,
            LedgerWidgetType.ACCOUNT,
            LedgerWidgetType.CREDIT_CARD,
            LedgerWidgetType.GOAL,
        )
    }
}
