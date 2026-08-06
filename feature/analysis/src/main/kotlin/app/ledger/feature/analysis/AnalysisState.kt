@file:Suppress("MagicNumber", "TooManyFunctions")

package app.ledger.feature.analysis

import app.ledger.analytics.domain.AnalysisOverview
import app.ledger.analytics.domain.AnalyticsIntegrityReport
import app.ledger.analytics.domain.ComparisonMode
import app.ledger.analytics.domain.Dimension
import app.ledger.analytics.domain.DimensionValue
import app.ledger.analytics.domain.DrilldownPage
import app.ledger.analytics.domain.FixedReport
import app.ledger.analytics.domain.FixedReportDefinition
import app.ledger.analytics.domain.Measure
import app.ledger.analytics.domain.ReportExecution
import app.ledger.analytics.domain.ReportPeriod
import app.ledger.analytics.domain.ReportRow
import app.ledger.analytics.domain.ReportSpec
import app.ledger.analytics.domain.ReportVisualization
import app.ledger.analytics.domain.TimeGranularity
import app.ledger.core.common.DomainResult
import app.ledger.core.money.AmountSemantic
import app.ledger.core.money.AmountVisibility
import app.ledger.core.money.CurrencyCode
import app.ledger.core.money.JvmLegalTenderCurrencyCatalog
import app.ledger.core.money.LocaleCurrencyFormatter
import app.ledger.core.money.Money
import app.ledger.core.money.MoneyFormatRequest
import app.ledger.core.money.MoneyUiModel
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

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
)

sealed interface AnalysisLoadState {
    data object Loading : AnalysisLoadState

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

    fun decimalText(value: BigDecimal): String = value.multiply(BigDecimal(100)).setScale(1, RoundingMode.HALF_EVEN).toPlainString() + "%"

    fun dimensionLabel(value: DimensionValue): String = when (value) {
        is DimensionValue.Date -> value.value.toString()
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

    fun chartSeries(execution: ReportExecution.Content): List<app.ledger.core.designsystem.LedgerChartSeries> = buildList {
        execution.plan.spec.measures.forEach { measure ->
            add(
                app.ledger.core.designsystem.LedgerChartSeries(
                    stableSeriesKey = measure.name.lowercase(Locale.ROOT),
                    label = measure.name,
                    values = execution.rows.map { row -> row.measureValues.single { it.measure == measure }.chartValue() },
                    pointLabels = execution.rows.map(::rowLabel),
                ),
            )
            execution.comparison?.let { comparison ->
                add(
                    app.ledger.core.designsystem.LedgerChartSeries(
                        stableSeriesKey = "${measure.name.lowercase(Locale.ROOT)}_comparison",
                        label = "${measure.name} · ${comparison.mode.name}",
                        values = comparison.rows.map { row -> row.measureValues.single { it.measure == measure }.chartValue() },
                        pointLabels = comparison.rows.map(::rowLabel),
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

    private fun rowLabel(row: ReportRow): String = row.dimensionValues.joinToString(" · ", transform = ::dimensionLabel).ifBlank { "total" }

    private fun app.ledger.analytics.domain.MeasureValue.chartValue(): Double = minorValue?.toDouble() ?: decimalValue?.toDouble() ?: 0.0
}
