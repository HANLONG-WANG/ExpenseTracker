@file:Suppress("LongParameterList")

package app.ledger.feature.analysis

import app.ledger.analytics.domain.AnalysisOverview
import app.ledger.analytics.domain.AnalyticsIntegrityReport
import app.ledger.analytics.domain.DimensionValue
import app.ledger.analytics.domain.DrilldownPage
import app.ledger.analytics.domain.DrilldownQueryId
import app.ledger.analytics.domain.DrilldownTransaction
import app.ledger.analytics.domain.FixedReport
import app.ledger.analytics.domain.FixedReportCatalog
import app.ledger.analytics.domain.IntegrityCheckKey
import app.ledger.analytics.domain.IntegrityCheckResult
import app.ledger.analytics.domain.IntegritySeverity
import app.ledger.analytics.domain.Measure
import app.ledger.analytics.domain.MeasureValue
import app.ledger.analytics.domain.QuerySource
import app.ledger.analytics.domain.ReportComparison
import app.ledger.analytics.domain.ReportExecution
import app.ledger.analytics.domain.ReportPeriod
import app.ledger.analytics.domain.ReportQueryPlan
import app.ledger.analytics.domain.ReportRow
import app.ledger.analytics.domain.ReportVisualization
import app.ledger.analytics.domain.VisualizationResolution
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.domain.LocalRevision
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

object AnalysisDeviceFixtures {
    val actions = AnalysisActions(
        onNavigate = { _, _, _ -> },
        onRetry = {},
        onPreviousPeriod = {},
        onNextPeriod = {},
        onCycleMeasure = {},
        onCycleDimension = {},
        onCycleGranularity = {},
        onCycleComparison = {},
        onApplyFilter = {},
        onExport = {},
        onLoadMore = {},
        onRunIntegrity = {},
        onRepairProjection = {},
        onToggleTechnicalDetails = {},
    )

    val period = ReportPeriod(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31))
    val currency: CurrencyCode = CurrencyCode.parse("JPY").success()
    val revision: LocalRevision = LocalRevision.of(42).success()
    val definition = FixedReportCatalog.definition(FixedReport.INCOME_EXPENSE_NET)
    private val queryId = DrilldownQueryId(id(800))

    fun base(
        screenId: String,
        presentation: AnalysisPresentation,
        overview: AnalysisOverview? = overview(),
        execution: ReportExecution? = contentExecution(),
        drilldown: DrilldownPage? = drilldown(),
        integrity: AnalyticsIntegrityReport? = integrity(IntegritySeverity.PASS),
        failureCode: String? = null,
    ): AnalysisFeatureState = AnalysisFeatureState(
        screenId = screenId,
        presentation = presentation,
        period = period,
        catalog = FixedReportCatalog.definitions,
        baseCurrency = currency,
        overview = overview,
        fixedReport = definition.report,
        execution = execution,
        draftSpec = definition.spec,
        drilldown = drilldown,
        integrity = integrity,
        technicalDetailsExpanded = true,
        failureCode = failureCode,
    )

    fun overview(): AnalysisOverview = AnalysisOverview(
        period = period,
        baseCurrency = currency,
        incomeMinor = 120_000,
        allExpenseMinor = 74_000,
        consumptionMinor = 62_000,
        netSurplusMinor = 46_000,
        savingsRate = BigDecimal("0.38333333"),
        transactionCount = 18,
        asOfLocalRevision = revision,
    )

    fun contentExecution(): ReportExecution.Content {
        val currentRows = listOf(
            row(LocalDate.of(2026, 8, 1), 52_000, 31_000, BigDecimal("0.40384615")),
            row(LocalDate.of(2026, 8, 2), 68_000, 43_000, BigDecimal("0.36764706")),
        )
        val referenceRows = listOf(
            row(LocalDate.of(2026, 7, 1), 49_000, 36_000, BigDecimal("0.26530612")),
            row(LocalDate.of(2026, 7, 2), 61_000, 41_000, BigDecimal("0.32786885")),
        )
        return ReportExecution.Content(
            fixedReport = definition.report,
            plan = ReportQueryPlan(QuerySource.MONTHLY_ROLLUP, definition.spec, revision, null),
            rows = currentRows,
            visualization = VisualizationResolution(
                requested = ReportVisualization.LINE,
                resolved = ReportVisualization.LINE,
                mergedOther = false,
                reason = null,
            ),
            summaryCode = "FIXED_INCOME_EXPENSE_NET",
            comparison = ReportComparison(
                app.ledger.analytics.domain.ComparisonMode.PREVIOUS_PERIOD,
                ReportPeriod(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)),
                referenceRows,
            ),
        )
    }

    fun emptyExecution(): ReportExecution.Empty = ReportExecution.Empty(
        definition.report,
        ReportQueryPlan(QuerySource.MONTHLY_ROLLUP, definition.spec, revision, null),
        VisualizationResolution(ReportVisualization.LINE, ReportVisualization.LINE, false, null),
    )

    fun staleExecution(): ReportExecution.StaleProjection = ReportExecution.StaleProjection(
        revision,
        setOf("analytics_monthly_total"),
    )

    fun drilldown(): DrilldownPage = DrilldownPage(
        rows = listOf(
            DrilldownTransaction(id(901), LocalDate.of(2026, 8, 6), "EXPENSE", 3_600, currency),
            DrilldownTransaction(id(902), LocalDate.of(2026, 8, 5), "REFUND", 800, currency),
        ),
        nextCursor = app.ledger.analytics.domain.DrilldownCursor(1_786_000_000_000L, id(902)),
    )

    fun integrity(severity: IntegritySeverity): AnalyticsIntegrityReport {
        val checks = IntegrityCheckKey.entries.map { key ->
            val itemSeverity = if (key == IntegrityCheckKey.FACT_REBUILD) severity else IntegritySeverity.PASS
            IntegrityCheckResult(key, itemSeverity, if (itemSeverity == IntegritySeverity.PASS) 0 else 1, "P25_${key.name}")
        }
        return AnalyticsIntegrityReport(checks, revision, "live-hash", if (severity == IntegritySeverity.FAILURE) "rebuilt-hash" else "live-hash")
    }

    private fun row(date: LocalDate, income: Long, expense: Long, savingsRate: BigDecimal): ReportRow = ReportRow(
        dimensionValues = listOf(DimensionValue.Date(date)),
        measureValues = listOf(
            MeasureValue(Measure.INCOME, income, null),
            MeasureValue(Measure.EXPENSE, expense, null),
            MeasureValue(Measure.SAVINGS_RATE, null, savingsRate),
        ),
        drilldownQueryId = queryId,
    )

    private fun id(seed: Long): StableId = StableId.fromUuid(UUID(0, seed))

    private fun <T> DomainResult<T>.success(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error(error.code)
    }
}
