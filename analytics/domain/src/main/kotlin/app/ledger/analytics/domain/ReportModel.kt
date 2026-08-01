package app.ledger.analytics.domain

import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.domain.CategoryId
import app.ledger.finance.domain.LocalRevision
import app.ledger.finance.domain.MerchantId
import app.ledger.finance.domain.ParticipantId
import app.ledger.finance.domain.PlaceId
import app.ledger.finance.domain.ProjectId
import app.ledger.finance.domain.RecordLifecycle
import app.ledger.finance.domain.TransactionId
import app.ledger.finance.domain.UserAccountId
import java.time.LocalDate

@JvmInline value class ReportDefinitionId(val value: StableId)

@JvmInline value class ReportRevisionId(val value: StableId)

@JvmInline value class DashboardId(val value: StableId)

enum class FixedReport {
    INCOME_EXPENSE_NET,
    CASH_FLOW,
    CONSUMPTION_CATEGORY_STRUCTURE,
    CATEGORY_TREND,
    MERCHANT_RANKING_TREND,
    ACCOUNT_BALANCE_NET_FINANCIAL_ASSETS,
    FX_REVALUATION,
    BUDGET_EXECUTION,
    PROJECT_BUDGET_CASH_FLOW,
    CONSUMPTION_MAP,
    CREDIT_DEBT_STATEMENT_LIMIT,
    INSTALLMENT_BALANCE_FEES,
    LOAN_PRINCIPAL_INTEREST_PROGRESS_FORECAST,
    GOAL_FUNDS,
    RECURRENCE_SUBSCRIPTIONS,
    REFUNDS_CONTRA_EXPENSE,
    SETTLEMENT_ACTIVITY,
    MULTI_CURRENCY_FX_COST,
    MULTI_DIMENSIONAL,
    DATA_INTEGRITY,
}

enum class Measure {
    INCOME,
    EXPENSE,
    CONSUMPTION,
    NON_CONSUMPTION_EXPENSE,
    CONTRA_EXPENSE,
    NET_CASH_FLOW,
    SAVINGS_RATE,
    BUDGET_USAGE,
    PROJECT_USAGE,
    GOAL_BALANCE,
    ACCOUNT_BALANCE,
    CORE_NET_FINANCIAL_ASSETS,
    ADJUSTED_NET_FINANCIAL_POSITION,
    FX_REVALUATION,
    CREDIT_DEBT,
    CREDIT_AVAILABLE_LIMIT,
    INSTALLMENT_PRINCIPAL,
    INSTALLMENT_FEES,
    LOAN_PRINCIPAL,
    LOAN_INTEREST,
    SETTLEMENT_POSITION,
    TRANSACTION_COUNT,
}

enum class Dimension {
    DATE,
    CATEGORY,
    MERCHANT,
    ACCOUNT,
    CARD,
    PROJECT,
    GOAL,
    CURRENCY,
    PLACE,
    SETTLEMENT_ACTIVITY,
    PARTICIPANT,
    TRANSACTION_SOURCE,
}

enum class TimeGranularity {
    DAY,
    WEEK,
    MONTH,
    QUARTER,
    YEAR,
}

enum class ComparisonMode {
    PREVIOUS_PERIOD,
    YEAR_OVER_YEAR,
    MOVING_AVERAGE,
    TREND,
    FORECAST,
}

enum class SortDirection {
    ASCENDING,
    DESCENDING,
}

sealed interface ReportSort {
    val direction: SortDirection

    data class ByMeasure(
        val measure: Measure,
        override val direction: SortDirection,
    ) : ReportSort

    data class ByDimension(
        val dimension: Dimension,
        override val direction: SortDirection,
    ) : ReportSort
}

sealed interface FilterExpression {
    data object All : FilterExpression

    data class And(val operands: List<FilterExpression>) : FilterExpression {
        init {
            require(operands.size >= 2)
        }
    }

    data class Or(val operands: List<FilterExpression>) : FilterExpression {
        init {
            require(operands.size >= 2)
        }
    }

    data class Not(val operand: FilterExpression) : FilterExpression

    data class Predicate(
        val field: FilterField,
        val operator: FilterOperator,
        val value: FilterValue,
    ) : FilterExpression
}

enum class FilterField {
    OCCURRED_DATE,
    ACCRUAL_DATE,
    TRANSACTION_KIND,
    ACCOUNT,
    CARD,
    CATEGORY,
    MERCHANT,
    PROJECT,
    GOAL,
    SETTLEMENT_ACTIVITY,
    PARTICIPANT,
    CURRENCY,
    BASE_AMOUNT,
    PLACE,
    HAS_ATTACHMENT,
    IS_REFUND,
    HAS_INSTALLMENT,
    TRANSACTION_SOURCE,
    BUDGET_ATTRIBUTE,
    ECONOMIC_NATURE,
    LIFECYCLE_STATE,
    CREATED_AT,
    MODIFIED_AT,
}

enum class FilterOperator {
    EQUALS,
    NOT_EQUALS,
    IN,
    NOT_IN,
    BETWEEN,
    GREATER_THAN_OR_EQUAL,
    LESS_THAN_OR_EQUAL,
    IS_TRUE,
    IS_FALSE,
}

sealed interface FilterValue {
    data class DateRange(val start: LocalDate, val endInclusive: LocalDate) : FilterValue {
        init {
            require(endInclusive >= start)
        }
    }

    data class AmountRange(val minimumMinor: Long?, val maximumMinor: Long?) : FilterValue {
        init {
            require(minimumMinor != null || maximumMinor != null)
            require(minimumMinor == null || maximumMinor == null || maximumMinor >= minimumMinor)
        }
    }

    data class Accounts(val values: Set<UserAccountId>) : FilterValue

    data class Categories(val values: Set<CategoryId>) : FilterValue

    data class Merchants(val values: Set<MerchantId>) : FilterValue

    data class Projects(val values: Set<ProjectId>) : FilterValue

    data class Places(val values: Set<PlaceId>) : FilterValue

    data class Participants(val values: Set<ParticipantId>) : FilterValue

    data class Currencies(val values: Set<CurrencyCode>) : FilterValue

    data class ClosedKeys(val values: Set<String>) : FilterValue

    data class Flag(val value: Boolean) : FilterValue
}

data class ReportSpec(
    val measures: List<Measure>,
    val dimensions: List<Dimension>,
    val filters: FilterExpression,
    val granularity: TimeGranularity,
    val sorting: List<ReportSort>,
    val comparison: ComparisonMode?,
) {
    init {
        require(measures.isNotEmpty())
        require(measures.toSet().size == measures.size)
        require(dimensions.toSet().size == dimensions.size)
    }
}

enum class ReportVisualization {
    METRIC_CARD,
    LINE,
    BAR,
    STACKED_BAR,
    PIE,
    TABLE,
    MAP,
    BUDGET_PROGRESS,
    GOAL_PROGRESS,
}

data class ReportDefinition(
    val id: ReportDefinitionId,
    val name: String,
    val currentRevisionId: ReportRevisionId,
    val archived: Boolean,
) : app.ledger.finance.domain.LifecycleRecord<RecordLifecycle.Current> {
    override val lifecycle: RecordLifecycle.Current = RecordLifecycle.Current
}

data class ReportDefinitionRevision(
    val id: ReportRevisionId,
    val reportId: ReportDefinitionId,
    val revisionNumber: Int,
    val spec: ReportSpec,
    val visualization: ReportVisualization,
    val algorithmVersion: Int,
) : app.ledger.finance.domain.LifecycleRecord<RecordLifecycle.Revision> {
    override val lifecycle: RecordLifecycle.Revision = RecordLifecycle.Revision

    init {
        require(revisionNumber > 0)
        require(algorithmVersion > 0)
    }
}

data class DashboardItem(
    val reportId: ReportDefinitionId,
    val sortOrder: Int,
)

data class Dashboard(
    val id: DashboardId,
    val name: String,
    val items: List<DashboardItem>,
    val archived: Boolean,
) : app.ledger.finance.domain.LifecycleRecord<RecordLifecycle.Current> {
    override val lifecycle: RecordLifecycle.Current = RecordLifecycle.Current
}

enum class QuerySource {
    MONTHLY_ROLLUP,
    DAILY_ROLLUP,
    ECONOMIC_EFFECTS,
    JOURNAL_POSTINGS,
}

data class ReportQueryPlan(
    val source: QuerySource,
    val spec: ReportSpec,
    val asOfLocalRevision: LocalRevision,
    val asOfValuationRevision: LocalRevision?,
)

data class ReportRow(
    val dimensionValues: List<DimensionValue>,
    val measureValues: List<MeasureValue>,
    val drillDownTransactionIds: List<TransactionId>,
)

sealed interface DimensionValue {
    data class Date(val value: LocalDate) : DimensionValue

    data class Entity(val dimension: Dimension, val id: StableId) : DimensionValue

    data class Currency(val value: CurrencyCode) : DimensionValue

    data class ClosedKey(val dimension: Dimension, val value: String) : DimensionValue
}

data class MeasureValue(
    val measure: Measure,
    val minorValue: Long?,
    val decimalValue: java.math.BigDecimal?,
) {
    init {
        require((minorValue == null) != (decimalValue == null))
    }
}

data class VersionedReportCache(
    val reportRevisionId: ReportRevisionId,
    val rows: List<ReportRow>,
    val bookRevision: LocalRevision,
    val valuationRevision: LocalRevision?,
) : app.ledger.finance.domain.LifecycleRecord<RecordLifecycle.Cache> {
    override val lifecycle: RecordLifecycle.Cache = RecordLifecycle.Cache

    fun isUsable(bookRevision: LocalRevision, valuationRevision: LocalRevision?): Boolean = this.bookRevision == bookRevision && this.valuationRevision == valuationRevision
}
