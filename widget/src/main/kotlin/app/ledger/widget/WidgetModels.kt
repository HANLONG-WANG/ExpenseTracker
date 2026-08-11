package app.ledger.widget

import app.ledger.core.common.StableId
import app.ledger.finance.application.WidgetAccountSnapshot
import app.ledger.finance.application.WidgetCreditSnapshot
import app.ledger.finance.application.WidgetGoalSnapshot
import app.ledger.finance.application.WidgetQuickDirection
import app.ledger.finance.application.WidgetQuickTargetKind
import app.ledger.finance.application.WidgetSnapshotBundle
import java.time.LocalDate

enum class LedgerWidgetType {
    QUICK_ENTRY,
    MONTH_CONSUMPTION,
    MONTH_BUDGET,
    TODAY_AVAILABLE,
    ACCOUNT,
    CORE_NET_ASSETS,
    CREDIT_CARD,
    GOAL,
    FINANCIAL_OVERVIEW,
}

data class LedgerWidgetConfiguration(
    val appWidgetId: Int,
    val bookId: StableId,
    val type: LedgerWidgetType,
    val selectedId: StableId? = null,
    val quickTargetKind: WidgetQuickTargetKind? = null,
    val quickDirection: WidgetQuickDirection? = null,
    val revealAmounts: Boolean = false,
) {
    init {
        require(appWidgetId >= 0)
        val needsSelection = type in setOf(
            LedgerWidgetType.QUICK_ENTRY,
            LedgerWidgetType.ACCOUNT,
            LedgerWidgetType.CREDIT_CARD,
            LedgerWidgetType.GOAL,
        )
        require(!needsSelection || selectedId != null)
        require(type == LedgerWidgetType.QUICK_ENTRY || quickTargetKind == null)
        require(type == LedgerWidgetType.QUICK_ENTRY || quickDirection == null)
        require(type != LedgerWidgetType.QUICK_ENTRY || quickTargetKind != null && quickDirection != null)
    }
}

sealed interface LedgerWidgetContent {
    data object NotConfigured : LedgerWidgetContent
    data object NoEligibleData : LedgerWidgetContent
    data object Locked : LedgerWidgetContent
    data object Stale : LedgerWidgetContent
    data class Ready(
        val bundle: WidgetSnapshotBundle,
        val account: WidgetAccountSnapshot? = null,
        val credit: WidgetCreditSnapshot? = null,
        val goal: WidgetGoalSnapshot? = null,
    ) : LedgerWidgetContent
}

object LedgerWidgetPolicy {
    fun resolve(
        configuration: LedgerWidgetConfiguration,
        bundle: WidgetSnapshotBundle,
        today: LocalDate,
    ): LedgerWidgetContent {
        val book = bundle.book
        return when {
            configuration.type == LedgerWidgetType.QUICK_ENTRY -> LedgerWidgetContent.Ready(bundle)
            book == null -> LedgerWidgetContent.NoEligibleData
            book.snapshotLocalDate != today.toStorageInt() -> LedgerWidgetContent.Stale
            else -> resolveCurrent(configuration, bundle, book)
        }
    }

    private fun resolveCurrent(
        configuration: LedgerWidgetConfiguration,
        bundle: WidgetSnapshotBundle,
        book: app.ledger.finance.application.WidgetBookSnapshot,
    ): LedgerWidgetContent = when (configuration.type) {
        LedgerWidgetType.ACCOUNT -> bundle.accounts.singleOrNull { it.accountId == configuration.selectedId }
            ?.let { LedgerWidgetContent.Ready(bundle, account = it) }
            ?: LedgerWidgetContent.NoEligibleData
        LedgerWidgetType.CREDIT_CARD -> bundle.creditAccounts.singleOrNull { it.accountId == configuration.selectedId }
            ?.let { LedgerWidgetContent.Ready(bundle, credit = it) }
            ?: LedgerWidgetContent.NoEligibleData
        LedgerWidgetType.GOAL -> bundle.goals.singleOrNull { it.goalId == configuration.selectedId }
            ?.let { LedgerWidgetContent.Ready(bundle, goal = it) }
            ?: LedgerWidgetContent.NoEligibleData
        LedgerWidgetType.MONTH_BUDGET -> if (
            book.monthBudgetAvailableBaseMinor == null || book.monthBudgetUsedBaseMinor == null
        ) {
            LedgerWidgetContent.NoEligibleData
        } else {
            LedgerWidgetContent.Ready(bundle)
        }
        LedgerWidgetType.TODAY_AVAILABLE -> if (book.todayAvailableBaseMinor == null) {
            LedgerWidgetContent.NoEligibleData
        } else {
            LedgerWidgetContent.Ready(bundle)
        }
        LedgerWidgetType.QUICK_ENTRY,
        LedgerWidgetType.MONTH_CONSUMPTION,
        LedgerWidgetType.CORE_NET_ASSETS,
        LedgerWidgetType.FINANCIAL_OVERVIEW,
        -> LedgerWidgetContent.Ready(bundle)
    }
}

interface LedgerWidgetConfigurationRepository {
    suspend fun activeBookId(): StableId?

    suspend fun read(appWidgetId: Int): LedgerWidgetConfiguration?

    suspend fun save(configuration: LedgerWidgetConfiguration)

    suspend fun delete(appWidgetIds: Set<Int>)
}

private fun LocalDate.toStorageInt(): Int = year * DATE_YEAR_MULTIPLIER + monthValue * DATE_MONTH_MULTIPLIER + dayOfMonth

private const val DATE_YEAR_MULTIPLIER: Int = 10_000
private const val DATE_MONTH_MULTIPLIER: Int = 100
