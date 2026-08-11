package app.ledger.finance.application

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.finance.domain.LocalRevision
import java.time.LocalDate

/** Complete, bounded read model consumed by Glance. It contains no transaction, location, note or Vault fields. */
data class WidgetBookSnapshot(
    val coreNetFinancialAssetsBaseMinor: Long,
    val adjustedNetFinancialPositionBaseMinor: Long,
    val baseCurrency: String,
    val localRevision: LocalRevision,
    val valuationRevision: LocalRevision,
    val snapshotLocalDate: Int,
    val monthKey: Int,
    val monthConsumptionBaseMinor: Long,
    val previousMonthConsumptionBaseMinor: Long,
    val monthBudgetAvailableBaseMinor: Long?,
    val monthBudgetUsedBaseMinor: Long?,
    val todayAvailableBaseMinor: Long?,
    val previousCoreNetFinancialAssetsBaseMinor: Long,
)

data class WidgetAccountSnapshot(
    val accountId: StableId,
    val displayName: String,
    val balanceMinor: Long,
    val availableMinor: Long,
    val currency: String,
    val localRevision: LocalRevision,
)

data class WidgetCreditSnapshot(
    val accountId: StableId,
    val displayName: String,
    val debtMinor: Long,
    val availableLimitMinor: Long?,
    val statementRemainingMinor: Long?,
    val statementDueDate: Int?,
    val currency: String,
    val localRevision: LocalRevision,
)

data class WidgetGoalSnapshot(
    val goalId: StableId,
    val displayName: String,
    val balanceMinor: Long,
    val targetMinor: Long,
    val currency: String,
    val localRevision: LocalRevision,
)

data class WidgetSnapshotBundle(
    val book: WidgetBookSnapshot?,
    val accounts: List<WidgetAccountSnapshot>,
    val creditAccounts: List<WidgetCreditSnapshot>,
    val goals: List<WidgetGoalSnapshot>,
) {
    init {
        val revision = book?.localRevision
        require(
            revision == null ||
                accounts.all { it.localRevision == revision } &&
                creditAccounts.all { it.localRevision == revision } &&
                goals.all { it.localRevision == revision },
        )
    }
}

enum class WidgetQuickTargetKind { CATEGORY, TEMPLATE }

enum class WidgetQuickDirection { EXPENSE, INCOME }

data class WidgetQuickTarget(
    val id: StableId,
    val kind: WidgetQuickTargetKind,
    val direction: WidgetQuickDirection,
    val displayName: String,
)

/** Glance calls only [read]; configuration UI may call the separately scoped [quickTargets]. */
interface WidgetSnapshotApplicationPort {
    suspend fun read(bookId: StableId): DomainResult<WidgetSnapshotBundle>

    suspend fun quickTargets(bookId: StableId): DomainResult<List<WidgetQuickTarget>>
}

/** Foreground-only derived maintenance used at a local-date boundary; Glance never invokes it. */
interface WidgetSnapshotRefreshApplicationPort {
    suspend fun refreshIfStale(bookId: StableId, localDate: LocalDate): DomainResult<Boolean>
}
