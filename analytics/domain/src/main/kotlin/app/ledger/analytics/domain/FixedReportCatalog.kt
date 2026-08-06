@file:Suppress("LongMethod", "LongParameterList", "MagicNumber")

package app.ledger.analytics.domain

import java.math.BigDecimal
import java.math.RoundingMode

object FixedReportCatalog {
    val definitions: List<FixedReportDefinition> = listOf(
        definition(
            FixedReport.INCOME_EXPENSE_NET,
            "income-expense-net",
            FixedReportGroup.INCOME_AND_EXPENSE,
            listOf(Measure.INCOME, Measure.EXPENSE, Measure.SAVINGS_RATE),
            listOf(Dimension.DATE),
            ReportVisualization.LINE,
        ),
        definition(
            FixedReport.CASH_FLOW,
            "cash-flow",
            FixedReportGroup.INCOME_AND_EXPENSE,
            listOf(Measure.NET_CASH_FLOW),
            listOf(Dimension.DATE),
            ReportVisualization.BAR,
        ),
        definition(
            FixedReport.CONSUMPTION_CATEGORY_STRUCTURE,
            "consumption-category-structure",
            FixedReportGroup.INCOME_AND_EXPENSE,
            listOf(Measure.CONSUMPTION),
            listOf(Dimension.CATEGORY),
            ReportVisualization.PIE,
        ),
        definition(
            FixedReport.CATEGORY_TREND,
            "category-trend",
            FixedReportGroup.INCOME_AND_EXPENSE,
            listOf(Measure.CONSUMPTION),
            listOf(Dimension.DATE, Dimension.CATEGORY),
            ReportVisualization.STACKED_BAR,
        ),
        definition(
            FixedReport.MERCHANT_RANKING_TREND,
            "merchant-ranking-trend",
            FixedReportGroup.INCOME_AND_EXPENSE,
            listOf(Measure.EXPENSE),
            listOf(Dimension.MERCHANT),
            ReportVisualization.BAR,
        ),
        definition(
            FixedReport.ACCOUNT_BALANCE_NET_FINANCIAL_ASSETS,
            "account-balance-net-financial-assets",
            FixedReportGroup.ASSETS_AND_LIABILITIES,
            listOf(Measure.ACCOUNT_BALANCE, Measure.CORE_NET_FINANCIAL_ASSETS, Measure.ADJUSTED_NET_FINANCIAL_POSITION),
            listOf(Dimension.ACCOUNT),
            ReportVisualization.TABLE,
        ),
        definition(
            FixedReport.FX_REVALUATION,
            "fx-revaluation",
            FixedReportGroup.ASSETS_AND_LIABILITIES,
            listOf(Measure.FX_REVALUATION),
            listOf(Dimension.CURRENCY),
            ReportVisualization.BAR,
        ),
        definition(
            FixedReport.BUDGET_EXECUTION,
            "budget-execution",
            FixedReportGroup.PLANNING,
            listOf(Measure.BUDGET_USAGE),
            listOf(Dimension.CATEGORY),
            ReportVisualization.BUDGET_PROGRESS,
        ),
        definition(
            FixedReport.PROJECT_BUDGET_CASH_FLOW,
            "project-budget-cash-flow",
            FixedReportGroup.PLANNING,
            listOf(Measure.PROJECT_USAGE, Measure.NET_CASH_FLOW),
            listOf(Dimension.PROJECT),
            ReportVisualization.BAR,
        ),
        definition(
            FixedReport.CONSUMPTION_MAP,
            "consumption-map",
            FixedReportGroup.INCOME_AND_EXPENSE,
            listOf(Measure.CONSUMPTION, Measure.TRANSACTION_COUNT),
            listOf(Dimension.PLACE),
            ReportVisualization.MAP,
        ),
        definition(
            FixedReport.CREDIT_DEBT_STATEMENT_LIMIT,
            "credit-debt-statement-limit",
            FixedReportGroup.ASSETS_AND_LIABILITIES,
            listOf(Measure.CREDIT_DEBT, Measure.CREDIT_AVAILABLE_LIMIT),
            listOf(Dimension.ACCOUNT),
            ReportVisualization.BAR,
        ),
        definition(
            FixedReport.INSTALLMENT_BALANCE_FEES,
            "installment-balance-fees",
            FixedReportGroup.ASSETS_AND_LIABILITIES,
            listOf(Measure.INSTALLMENT_PRINCIPAL, Measure.INSTALLMENT_FEES),
            listOf(Dimension.CURRENCY),
            ReportVisualization.TABLE,
        ),
        definition(
            FixedReport.LOAN_PRINCIPAL_INTEREST_PROGRESS_FORECAST,
            "loan-principal-interest-progress-forecast",
            FixedReportGroup.ASSETS_AND_LIABILITIES,
            listOf(Measure.LOAN_PRINCIPAL, Measure.LOAN_INTEREST),
            listOf(Dimension.DATE),
            ReportVisualization.STACKED_BAR,
        ),
        definition(
            FixedReport.GOAL_FUNDS,
            "goal-funds",
            FixedReportGroup.PLANNING,
            listOf(Measure.GOAL_BALANCE),
            listOf(Dimension.GOAL),
            ReportVisualization.GOAL_PROGRESS,
        ),
        definition(
            FixedReport.RECURRENCE_SUBSCRIPTIONS,
            "recurrence-subscriptions",
            FixedReportGroup.PLANNING,
            listOf(Measure.EXPENSE, Measure.TRANSACTION_COUNT),
            listOf(Dimension.TRANSACTION_SOURCE),
            ReportVisualization.TABLE,
        ),
        definition(
            FixedReport.REFUNDS_CONTRA_EXPENSE,
            "refunds-contra-expense",
            FixedReportGroup.INCOME_AND_EXPENSE,
            listOf(Measure.CONTRA_EXPENSE),
            listOf(Dimension.DATE),
            ReportVisualization.BAR,
        ),
        definition(
            FixedReport.SETTLEMENT_ACTIVITY,
            "settlement-activity",
            FixedReportGroup.RELATIONSHIPS,
            listOf(Measure.SETTLEMENT_POSITION),
            listOf(Dimension.SETTLEMENT_ACTIVITY, Dimension.PARTICIPANT),
            ReportVisualization.TABLE,
        ),
        definition(
            FixedReport.MULTI_CURRENCY_FX_COST,
            "multi-currency-fx-cost",
            FixedReportGroup.ASSETS_AND_LIABILITIES,
            listOf(Measure.EXPENSE, Measure.FX_REVALUATION),
            listOf(Dimension.CURRENCY),
            ReportVisualization.BAR,
        ),
        definition(
            FixedReport.MULTI_DIMENSIONAL,
            "multi-dimensional",
            FixedReportGroup.INCOME_AND_EXPENSE,
            listOf(Measure.INCOME, Measure.EXPENSE, Measure.CONSUMPTION),
            listOf(Dimension.CATEGORY, Dimension.MERCHANT),
            ReportVisualization.TABLE,
        ),
        definition(
            FixedReport.DATA_INTEGRITY,
            "data-integrity",
            FixedReportGroup.DATA_QUALITY,
            listOf(Measure.TRANSACTION_COUNT),
            emptyList(),
            ReportVisualization.TABLE,
        ),
    )

    init {
        require(definitions.size == 20)
        require(definitions.map(FixedReportDefinition::report).toSet() == FixedReport.entries.toSet())
        require(definitions.map(FixedReportDefinition::group).toSet() == FixedReportGroup.entries.toSet())
        require(definitions.map(FixedReportDefinition::key).toSet().size == definitions.size)
    }

    fun definition(report: FixedReport): FixedReportDefinition = definitions.single { it.report == report }

    fun definition(key: ReportKey): FixedReportDefinition? = definitions.singleOrNull { it.key == key }

    private fun definition(
        report: FixedReport,
        key: String,
        group: FixedReportGroup,
        measures: List<Measure>,
        dimensions: List<Dimension>,
        visualization: ReportVisualization,
    ): FixedReportDefinition = FixedReportDefinition(
        report = report,
        key = ReportKey(key),
        group = group,
        spec = ReportSpec(
            measures = measures,
            dimensions = dimensions,
            filters = FilterExpression.All,
            granularity = TimeGranularity.MONTH,
            sorting = emptyList(),
            comparison = ComparisonMode.PREVIOUS_PERIOD,
        ),
        defaultVisualization = visualization,
        compatibleVisualizations = compatible(visualization),
    )

    private fun compatible(default: ReportVisualization): Set<ReportVisualization> = when (default) {
        ReportVisualization.METRIC_CARD -> setOf(default, ReportVisualization.TABLE)
        ReportVisualization.LINE -> setOf(default, ReportVisualization.BAR, ReportVisualization.TABLE)
        ReportVisualization.BAR -> setOf(default, ReportVisualization.PIE, ReportVisualization.TABLE)
        ReportVisualization.STACKED_BAR -> setOf(default, ReportVisualization.LINE, ReportVisualization.TABLE)
        ReportVisualization.PIE -> setOf(default, ReportVisualization.BAR, ReportVisualization.TABLE)
        ReportVisualization.TABLE -> setOf(default)
        ReportVisualization.MAP -> setOf(default, ReportVisualization.BAR, ReportVisualization.TABLE)
        ReportVisualization.BUDGET_PROGRESS -> setOf(default, ReportVisualization.BAR, ReportVisualization.TABLE)
        ReportVisualization.GOAL_PROGRESS -> setOf(default, ReportVisualization.BAR, ReportVisualization.TABLE)
    }
}

object ReportVisualizationPolicy {
    const val MAX_PIE_CATEGORIES: Int = 6

    fun resolve(
        requested: ReportVisualization,
        categoryCount: Int,
        dimensions: List<Dimension>,
        measures: List<Measure> = emptyList(),
    ): VisualizationResolution {
        require(categoryCount >= 0)
        if (requested == ReportVisualization.PIE && categoryCount > MAX_PIE_CATEGORIES) {
            return VisualizationResolution(
                requested,
                ReportVisualization.BAR,
                mergedOther = true,
                VisualizationFallbackReason.TOO_MANY_PIE_CATEGORIES,
            )
        }
        val incompatible = when (requested) {
            ReportVisualization.LINE -> Dimension.DATE !in dimensions
            ReportVisualization.STACKED_BAR -> Dimension.DATE !in dimensions || dimensions.size < 2
            ReportVisualization.PIE -> dimensions.size != 1 || Dimension.DATE in dimensions || measures.size > 1
            ReportVisualization.MAP -> Dimension.PLACE !in dimensions
            ReportVisualization.BUDGET_PROGRESS -> Measure.BUDGET_USAGE !in measures
            ReportVisualization.GOAL_PROGRESS -> Measure.GOAL_BALANCE !in measures
            ReportVisualization.METRIC_CARD -> dimensions.isNotEmpty() || measures.size != 1
            ReportVisualization.BAR,
            ReportVisualization.TABLE,
            -> false
        }
        return if (incompatible) {
            VisualizationResolution(
                requested,
                ReportVisualization.TABLE,
                mergedOther = false,
                VisualizationFallbackReason.INCOMPATIBLE_WITH_DIMENSIONS,
            )
        } else {
            VisualizationResolution(requested, requested, mergedOther = false, reason = null)
        }
    }
}

object SavingsRatePolicy {
    fun calculate(incomeMinor: Long, allExpenseMinor: Long): BigDecimal? {
        if (incomeMinor == 0L) return null
        val retained = Math.subtractExact(incomeMinor, allExpenseMinor)
        return BigDecimal.valueOf(retained).divide(BigDecimal.valueOf(incomeMinor), SCALE, RoundingMode.HALF_EVEN)
    }

    private const val SCALE = 8
}
