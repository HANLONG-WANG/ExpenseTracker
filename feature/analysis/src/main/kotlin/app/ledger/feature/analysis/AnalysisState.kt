@file:Suppress("MagicNumber", "TooManyFunctions")

package app.ledger.feature.analysis

import app.ledger.analytics.domain.AnalysisOverview
import app.ledger.analytics.domain.AnalyticsIntegrityReport
import app.ledger.analytics.domain.AnomalyFinding
import app.ledger.analytics.domain.AnomalyRuleId
import app.ledger.analytics.domain.AnomalyRuleType
import app.ledger.analytics.domain.ComparisonMode
import app.ledger.analytics.domain.ConsumptionMapDetail
import app.ledger.analytics.domain.ConsumptionMapFilterOptions
import app.ledger.analytics.domain.ConsumptionMapResult
import app.ledger.analytics.domain.DashboardItem
import app.ledger.analytics.domain.Dimension
import app.ledger.analytics.domain.DimensionValue
import app.ledger.analytics.domain.DrilldownPage
import app.ledger.analytics.domain.FixedReport
import app.ledger.analytics.domain.FixedReportDefinition
import app.ledger.analytics.domain.ForecastKey
import app.ledger.analytics.domain.ForecastResult
import app.ledger.analytics.domain.FilterExpression
import app.ledger.analytics.domain.FilterField
import app.ledger.analytics.domain.FilterOperator
import app.ledger.analytics.domain.FilterValue
import app.ledger.analytics.domain.Measure
import app.ledger.analytics.domain.ReportExecution
import app.ledger.analytics.domain.ReportExportFormat
import app.ledger.analytics.domain.ReportExportPayload
import app.ledger.analytics.domain.ReportPeriod
import app.ledger.analytics.domain.ReportRow
import app.ledger.analytics.domain.ReportSpec
import app.ledger.analytics.domain.ReportSort
import app.ledger.analytics.domain.ReportVisualization
import app.ledger.analytics.domain.SortDirection
import app.ledger.analytics.domain.SavedAnomalyRule
import app.ledger.analytics.domain.SavedDashboard
import app.ledger.analytics.domain.SavedReportDefinition
import app.ledger.analytics.domain.TimeGranularity
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.AmountSemantic
import app.ledger.core.money.AmountVisibility
import app.ledger.core.money.CurrencyCode
import app.ledger.core.money.JvmLegalTenderCurrencyCatalog
import app.ledger.core.money.LocaleCurrencyFormatter
import app.ledger.core.money.LocaleNumberFormatter
import app.ledger.core.money.Money
import app.ledger.core.money.MoneyFormatRequest
import app.ledger.core.money.MoneyUiModel
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

enum class AnalysisEntityFilter(val field: FilterField) {
    ACCOUNT(FilterField.ACCOUNT),
    CATEGORY(FilterField.CATEGORY),
    MERCHANT(FilterField.MERCHANT),
    PLACE(FilterField.PLACE),
    PROJECT(FilterField.PROJECT),
}

enum class AnalysisExportScope {
    CURRENT_RESULTS,
    CURRENT_AND_COMPARISON,
}

enum class AnalysisPresentation {
    CONTENT,
    NO_DATA,
    CALCULATING,
    ERROR,
    LOADING,
    EMPTY,
    QUERY_ERROR,
    STALE_REBUILD_REQUIRED,
    EDITING,
    INVALID,
    EXPIRED_QUERY,
    NOT_RUN,
    RUNNING,
    PASSED,
    WARNINGS,
    FAILED,
    CREATE,
    EDIT,
    EMPTY_CANVAS,
    PREVIEWING,
    AUTO_FALLBACK_TO_BAR,
    INSUFFICIENT_DATA,
    CLUSTERS,
    HEATMAP,
    SINGLE_POINTS,
    NO_LOCATION_DATA,
    MAP_UNAVAILABLE,
    PLACE,
    CLUSTER,
    SINGLE_TRANSACTION,
}

data class AnalysisFeatureState(
    val screenId: String,
    val presentation: AnalysisPresentation,
    val period: ReportPeriod,
    val catalog: List<FixedReportDefinition>,
    val baseCurrency: CurrencyCode,
    val overview: AnalysisOverview? = null,
    val fixedReport: FixedReport? = null,
    val execution: ReportExecution? = null,
    val draftSpec: ReportSpec? = null,
    val drilldown: DrilldownPage? = null,
    val integrity: AnalyticsIntegrityReport? = null,
    val technicalDetailsExpanded: Boolean = false,
    val failureCode: String? = null,
    val savedReports: List<SavedReportDefinition> = emptyList(),
    val dashboards: List<SavedDashboard> = emptyList(),
    val selectedDashboard: SavedDashboard? = null,
    val dashboardItems: List<DashboardItem> = emptyList(),
    val draftName: String = "",
    val draftVisualization: ReportVisualization = ReportVisualization.TABLE,
    val anomalyRules: List<SavedAnomalyRule> = emptyList(),
    val anomalyFindings: List<AnomalyFinding> = emptyList(),
    val editingAnomalyRuleId: AnomalyRuleId? = null,
    val anomalyDraftType: AnomalyRuleType = AnomalyRuleType.HISTORICAL_MEAN_STANDARD_DEVIATION,
    val anomalyThresholdText: String = "2.0",
    val anomalyLookbackText: String = "12",
    val forecastKey: ForecastKey? = null,
    val forecast: ForecastResult? = null,
    val exportFormat: ReportExportFormat = ReportExportFormat.CSV,
    val exportScope: AnalysisExportScope = AnalysisExportScope.CURRENT_AND_COMPARISON,
    val exportPayload: ReportExportPayload? = null,
    val builderStep: Int = 0,
    val forecastComparisons: Map<ForecastKey, ForecastResult> = emptyMap(),
    val consumptionMap: ConsumptionMapResult? = null,
    val consumptionMapDetail: ConsumptionMapDetail? = null,
    val consumptionMapFilterOptions: ConsumptionMapFilterOptions? = null,
)

sealed interface AnalysisLoadState {
    data class Loading(val previous: AnalysisFeatureState? = null) : AnalysisLoadState

    data class Content(val state: AnalysisFeatureState) : AnalysisLoadState

    data class Failure(val screenId: String, val code: String) : AnalysisLoadState
}

object AnalysisPolicy {
    private val currencies = JvmLegalTenderCurrencyCatalog.create()
    private val formatter = LocaleCurrencyFormatter(currencies)

    fun initialPeriod(today: LocalDate): ReportPeriod {
        val month = YearMonth.from(today)
        return ReportPeriod(month.atDay(1), month.atEndOfMonth())
    }

    fun previousPeriod(period: ReportPeriod): ReportPeriod {
        val month = YearMonth.from(period.start).minusMonths(1)
        return ReportPeriod(month.atDay(1), month.atEndOfMonth())
    }

    fun nextPeriod(period: ReportPeriod): ReportPeriod {
        val month = YearMonth.from(period.start).plusMonths(1)
        return ReportPeriod(month.atDay(1), month.atEndOfMonth())
    }

    fun reportPresentation(execution: ReportExecution): AnalysisPresentation = when (execution) {
        is ReportExecution.Content -> AnalysisPresentation.CONTENT
        is ReportExecution.Empty -> AnalysisPresentation.EMPTY
        is ReportExecution.StaleProjection -> AnalysisPresentation.STALE_REBUILD_REQUIRED
    }

    fun integrityPresentation(report: AnalyticsIntegrityReport): AnalysisPresentation = when (report.severity) {
        app.ledger.analytics.domain.IntegritySeverity.PASS -> AnalysisPresentation.PASSED
        app.ledger.analytics.domain.IntegritySeverity.WARNING -> AnalysisPresentation.WARNINGS
        app.ledger.analytics.domain.IntegritySeverity.FAILURE -> AnalysisPresentation.FAILED
    }

    fun money(minor: Long, currency: CurrencyCode, locale: Locale): MoneyUiModel {
        val semantic = when {
            minor < 0L -> AmountSemantic.OUTFLOW
            minor > 0L -> AmountSemantic.INFLOW
            else -> AmountSemantic.NEUTRAL
        }
        return (formatter.format(MoneyFormatRequest(Money(minor, currency), locale, semantic, AmountVisibility.VISIBLE)) as DomainResult.Success).value
    }

    fun decimalText(value: BigDecimal, locale: Locale = Locale.getDefault()): String =
        LocaleNumberFormatter.percentage(value, locale)

    fun dimensionLabel(value: DimensionValue, locale: Locale = Locale.getDefault()): String = when (value) {
        is DimensionValue.Date -> value.value.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
        is DimensionValue.Entity -> value.label
        is DimensionValue.Currency -> value.value.value
        is DimensionValue.ClosedKey -> value.value
    }

    fun visualizationType(execution: ReportExecution.Content): app.ledger.core.designsystem.LedgerChartType = when (execution.visualization.resolved) {
        ReportVisualization.LINE -> app.ledger.core.designsystem.LedgerChartType.LINE
        ReportVisualization.BAR -> app.ledger.core.designsystem.LedgerChartType.COLUMN
        ReportVisualization.STACKED_BAR -> app.ledger.core.designsystem.LedgerChartType.STACKED
        ReportVisualization.PIE -> app.ledger.core.designsystem.LedgerChartType.PIE
        ReportVisualization.METRIC_CARD,
        ReportVisualization.TABLE,
        ReportVisualization.MAP,
        -> app.ledger.core.designsystem.LedgerChartType.TABLE
        ReportVisualization.BUDGET_PROGRESS,
        ReportVisualization.GOAL_PROGRESS,
        -> app.ledger.core.designsystem.LedgerChartType.PROGRESS
    }

    fun chartSeries(
        execution: ReportExecution.Content,
        locale: Locale = Locale.getDefault(),
        baseCurrency: CurrencyCode,
    ): List<app.ledger.core.designsystem.LedgerChartSeries> = buildList {
        execution.plan.spec.measures.forEach { measure ->
            val currentValues = execution.rows.map { row -> row.measureValues.single { it.measure == measure } }
            add(
                app.ledger.core.designsystem.LedgerChartSeries(
                    stableSeriesKey = measure.name.lowercase(Locale.ROOT),
                    label = measure.name,
                    values = currentValues.map { it.chartValue() },
                    pointLabels = execution.rows.map { row -> rowLabel(row, locale) },
                    formattedValues = currentValues.map { it.formattedChartValue(baseCurrency, locale) },
                ),
            )
            execution.comparison?.let { comparison ->
                val comparisonValues = comparison.rows.map { row -> row.measureValues.single { it.measure == measure } }
                add(
                    app.ledger.core.designsystem.LedgerChartSeries(
                        stableSeriesKey = "${measure.name.lowercase(Locale.ROOT)}_comparison",
                        label = "${measure.name} · ${comparison.mode.name}",
                        values = comparisonValues.map { it.chartValue() },
                        pointLabels = comparison.rows.map { row -> rowLabel(row, locale) },
                        formattedValues = comparisonValues.map { it.formattedChartValue(baseCurrency, locale) },
                    ),
                )
            }
        }
    }

    fun cycleGranularity(spec: ReportSpec): ReportSpec = spec.copy(
        granularity = TimeGranularity.entries[(spec.granularity.ordinal + 1) % TimeGranularity.entries.size],
    )

    fun cycleComparison(spec: ReportSpec): ReportSpec = spec.copy(
        comparison = when (spec.comparison) {
            null -> ComparisonMode.PREVIOUS_PERIOD
            ComparisonMode.PREVIOUS_PERIOD -> ComparisonMode.YEAR_OVER_YEAR
            else -> null
        },
    )

    fun cycleMeasure(spec: ReportSpec): ReportSpec {
        val current = spec.measures.first()
        return spec.copy(measures = listOf(Measure.entries[(current.ordinal + 1) % Measure.entries.size]))
    }

    fun cycleDimension(spec: ReportSpec): ReportSpec {
        val current = spec.dimensions.firstOrNull()
        val next = if (current == null) Dimension.DATE else Dimension.entries[(current.ordinal + 1) % Dimension.entries.size]
        return spec.copy(dimensions = listOf(next))
    }

    fun selectMeasure(spec: ReportSpec, measure: Measure): ReportSpec = spec.copy(
        measures = listOf(measure),
        sorting = spec.sorting.filterNot { it is ReportSort.ByMeasure && it.measure != measure },
    )

    fun selectDimension(spec: ReportSpec, dimension: Dimension): ReportSpec = spec.copy(
        dimensions = listOf(dimension),
        sorting = spec.sorting.filterNot { it is ReportSort.ByDimension && it.dimension != dimension },
    )

    fun selectGranularity(spec: ReportSpec, granularity: TimeGranularity): ReportSpec = spec.copy(granularity = granularity)

    fun selectComparison(spec: ReportSpec, comparison: ComparisonMode?): ReportSpec = spec.copy(comparison = comparison)

    fun cycleSort(spec: ReportSpec, stableKey: String): ReportSpec {
        val requested = when {
            stableKey.startsWith("measure:") -> ReportSort.ByMeasure(Measure.valueOf(stableKey.substringAfter(':')), SortDirection.ASCENDING)
            stableKey.startsWith("dimension:") -> ReportSort.ByDimension(Dimension.valueOf(stableKey.substringAfter(':')), SortDirection.ASCENDING)
            else -> return spec
        }
        val index = spec.sorting.indexOfFirst { it.sortKey() == stableKey }
        val updated = spec.sorting.toMutableList()
        if (index < 0) {
            updated += requested
        } else {
            updated[index] = when (val current = updated[index]) {
                is ReportSort.ByMeasure -> if (current.direction == SortDirection.ASCENDING) current.copy(direction = SortDirection.DESCENDING) else return spec.copy(sorting = updated - current)
                is ReportSort.ByDimension -> if (current.direction == SortDirection.ASCENDING) current.copy(direction = SortDirection.DESCENDING) else return spec.copy(sorting = updated - current)
            }
        }
        return spec.copy(sorting = updated.take(4))
    }

    fun selectedFilterIds(spec: ReportSpec, entityFilter: AnalysisEntityFilter): Set<StableId> =
        spec.filters.predicates().filter { it.field == entityFilter.field }.flatMap { predicate ->
            when (val value = predicate.value) {
                is FilterValue.Accounts -> value.values.map { it.value }
                is FilterValue.Categories -> value.values.map { it.value }
                is FilterValue.Merchants -> value.values.map { it.value }
                is FilterValue.Places -> value.values.map { it.value }
                is FilterValue.Projects -> value.values.map { it.value }
                else -> emptyList()
            }
        }.toSet()

    fun replaceEntityFilter(
        spec: ReportSpec,
        entityFilter: AnalysisEntityFilter,
        selectedIds: Set<StableId>,
    ): ReportSpec {
        val without = spec.filters.withoutField(entityFilter.field)
        if (selectedIds.isEmpty()) return spec.copy(filters = without)
        val value = when (entityFilter) {
            AnalysisEntityFilter.ACCOUNT -> FilterValue.Accounts(selectedIds.mapTo(mutableSetOf()) { app.ledger.finance.domain.UserAccountId(it) })
            AnalysisEntityFilter.CATEGORY -> FilterValue.Categories(selectedIds.mapTo(mutableSetOf()) { app.ledger.finance.domain.CategoryId(it) })
            AnalysisEntityFilter.MERCHANT -> FilterValue.Merchants(selectedIds.mapTo(mutableSetOf()) { app.ledger.finance.domain.MerchantId(it) })
            AnalysisEntityFilter.PLACE -> FilterValue.Places(selectedIds.mapTo(mutableSetOf()) { app.ledger.finance.domain.PlaceId(it) })
            AnalysisEntityFilter.PROJECT -> FilterValue.Projects(selectedIds.mapTo(mutableSetOf()) { app.ledger.finance.domain.ProjectId(it) })
        }
        val added = FilterExpression.Predicate(entityFilter.field, FilterOperator.IN, value)
        return spec.copy(filters = without.and(added))
    }

    fun removeFilter(spec: ReportSpec, stableKey: String): ReportSpec {
        val filter = AnalysisEntityFilter.entries.singleOrNull { it.name.lowercase(Locale.ROOT) == stableKey } ?: return spec
        return spec.copy(filters = spec.filters.withoutField(filter.field))
    }

    fun reportSpecValid(spec: ReportSpec?, visualization: ReportVisualization? = null): Boolean = spec != null &&
        (visualization == null || app.ledger.analytics.domain.CustomReportPolicy.validate(spec, visualization).valid)

    fun ReportSort.sortKey(): String = when (this) {
        is ReportSort.ByMeasure -> "measure:${measure.name}"
        is ReportSort.ByDimension -> "dimension:${dimension.name}"
    }

    private fun rowLabel(row: ReportRow, locale: Locale): String = row.dimensionValues.joinToString(" · ") { dimensionLabel(it, locale) }.ifBlank { "total" }

    private fun FilterExpression.predicates(): List<FilterExpression.Predicate> = when (this) {
        FilterExpression.All -> emptyList()
        is FilterExpression.Predicate -> listOf(this)
        is FilterExpression.And -> operands.flatMap { it.predicates() }
        is FilterExpression.Or -> operands.flatMap { it.predicates() }
        is FilterExpression.Not -> operand.predicates()
    }

    private fun FilterExpression.withoutField(field: FilterField): FilterExpression = when (this) {
        FilterExpression.All -> this
        is FilterExpression.Predicate -> if (this.field == field) FilterExpression.All else this
        is FilterExpression.And -> operands.map { it.withoutField(field) }.combineAnd()
        is FilterExpression.Or -> operands.map { it.withoutField(field) }.filterNot { it == FilterExpression.All }.let { values ->
            when (values.size) {
                0 -> FilterExpression.All
                1 -> values.single()
                else -> FilterExpression.Or(values)
            }
        }
        is FilterExpression.Not -> if (operand.predicates().any { it.field == field }) FilterExpression.All else this
    }

    private fun FilterExpression.and(other: FilterExpression): FilterExpression = listOf(this, other).combineAnd()

    private fun List<FilterExpression>.combineAnd(): FilterExpression {
        val values = flatMap { if (it is FilterExpression.And) it.operands else listOf(it) }.filterNot { it == FilterExpression.All }
        return when (values.size) {
            0 -> FilterExpression.All
            1 -> values.single()
            else -> FilterExpression.And(values)
        }
    }

    private fun app.ledger.analytics.domain.MeasureValue.chartValue(): Double = minorValue?.toDouble() ?: decimalValue?.toDouble() ?: 0.0

    private fun app.ledger.analytics.domain.MeasureValue.formattedChartValue(baseCurrency: CurrencyCode, locale: Locale): String =
        minorValue?.let { money(it, currency ?: baseCurrency, locale).formatted }
            ?: decimalValue?.let { decimalText(it, locale) }
            ?: "—"
}
