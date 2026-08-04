package app.ledger.finance.application

import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.domain.BudgetAdjustmentKind
import app.ledger.finance.domain.BudgetAdjustmentScope
import app.ledger.finance.domain.CommandReceipt
import app.ledger.finance.domain.DailyAvailableBudget
import app.ledger.finance.domain.EntityStatus
import app.ledger.finance.domain.LocalRevision
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

data class BudgetCategoryReference(
    val id: StableId,
    val name: String,
    val rootCategoryId: StableId,
    val parentCategoryId: StableId?,
    val depth: Int,
    val status: EntityStatus,
)

data class BudgetCategoryLimitDraft(val categoryId: StableId, val amountBaseMinor: Long) {
    init {
        require(amountBaseMinor >= 0L)
    }
}

data class BudgetRevisionView(
    val id: StableId,
    val revisionNumber: Int,
    val totalBaseMinor: Long,
    val limits: List<BudgetCategoryLimitDraft>,
    val sourceTemplateRevisionId: StableId?,
    val createdAt: Instant,
)

data class BudgetCompositionView(
    val categoryId: StableId?,
    val categoryName: String?,
    val parentCategoryId: StableId?,
    val depth: Int,
    val baseMinor: Long,
    val rolloverMinor: Long,
    val adjustmentMinor: Long,
    val usedMinor: Long,
    val remainingMinor: Long,
    val asOfLocalRevision: LocalRevision,
) {
    val availableMinor: Long = Math.addExact(Math.addExact(baseMinor, rolloverMinor), adjustmentMinor)
    val exceededMinor: Long = if (remainingMinor < 0L) Math.negateExact(remainingMinor) else 0L
}

data class BudgetAdjustmentView(
    val id: StableId,
    val scope: BudgetAdjustmentScope,
    val categoryId: StableId?,
    val amountBaseMinor: Long,
    val kind: BudgetAdjustmentKind,
    val createdAt: Instant,
    val reversalOfId: StableId?,
)

data class BudgetTemplateView(
    val id: StableId,
    val name: String,
    val status: EntityStatus,
    val revision: BudgetRevisionView,
)

enum class BudgetProjectionReadiness { CURRENT, RECALCULATING, FAILED }

data class BudgetSnapshot(
    val bookId: StableId,
    val baseCurrency: CurrencyCode,
    val month: YearMonth,
    val today: LocalDate,
    val localRevision: LocalRevision,
    val projectionReadiness: BudgetProjectionReadiness,
    val monthId: StableId?,
    val currentRevision: BudgetRevisionView?,
    val revisionHistory: List<BudgetRevisionView>,
    val categories: List<BudgetCategoryReference>,
    val composition: List<BudgetCompositionView>,
    val adjustments: List<BudgetAdjustmentView>,
    val templates: List<BudgetTemplateView>,
    val dailyAvailable: DailyAvailableBudget?,
) {
    init {
        require(projectionReadiness == BudgetProjectionReadiness.CURRENT || composition.isEmpty())
        require(projectionReadiness == BudgetProjectionReadiness.CURRENT || dailyAvailable == null)
    }

    val configured: Boolean get() = currentRevision != null
    val historical: Boolean get() = month < YearMonth.from(today)
    val future: Boolean get() = month > YearMonth.from(today)
}

data class BudgetMutationIds(
    val bookId: StableId,
    val commandId: CommandId,
    val commitId: StableId,
    val entityId: StableId,
    val revisionId: StableId,
    val factIds: List<StableId>,
    val deviceInstanceId: StableId,
)

data class SaveBudgetMonthRequest(
    val ids: BudgetMutationIds,
    val month: YearMonth,
    val expectedRevisionId: StableId?,
    val totalBaseMinor: Long,
    val limits: List<BudgetCategoryLimitDraft>,
    val sourceTemplateRevisionId: StableId?,
    val createdAt: Instant,
)

data class SaveBudgetTemplateRequest(
    val ids: BudgetMutationIds,
    val expectedRevisionId: StableId?,
    val name: String,
    val status: EntityStatus,
    val totalBaseMinor: Long,
    val limits: List<BudgetCategoryLimitDraft>,
    val createdAt: Instant,
)

data class RecordBudgetAdjustmentRequest(
    val ids: BudgetMutationIds,
    val month: YearMonth,
    val kind: BudgetAdjustmentKind,
    val amountBaseMinor: Long,
    val sourceCategoryId: StableId?,
    val targetCategoryId: StableId?,
    val createdAt: Instant,
) {
    init {
        require(amountBaseMinor > 0L)
        require(factIdsRequired(kind) == ids.factIds.size)
    }

    private fun factIdsRequired(value: BudgetAdjustmentKind): Int = if (
        value == BudgetAdjustmentKind.TRANSFER_IN || value == BudgetAdjustmentKind.ARCHIVED_CATEGORY_TRANSFER
    ) {
        2
    } else {
        1
    }
}

/** The only P17 application boundary. Every budget mutation terminates at FinancialMutationCoordinator. */
interface BudgetApplicationPort {
    suspend fun snapshot(bookId: StableId, month: YearMonth, today: LocalDate): DomainResult<BudgetSnapshot>

    suspend fun saveMonth(request: SaveBudgetMonthRequest): DomainResult<CommandReceipt>

    suspend fun saveTemplate(request: SaveBudgetTemplateRequest): DomainResult<CommandReceipt>

    suspend fun recordAdjustment(request: RecordBudgetAdjustmentRequest): DomainResult<CommandReceipt>
}
