@file:Suppress("LongParameterList")

package app.ledger.feature.analysis

import app.ledger.analytics.domain.AnalysisOverview
import app.ledger.analytics.domain.AnalyticsAlgorithmVersion
import app.ledger.analytics.domain.AnalyticsIntegrityReport
import app.ledger.analytics.domain.AnomalyFinding
import app.ledger.analytics.domain.AnomalyRule
import app.ledger.analytics.domain.AnomalyRuleId
import app.ledger.analytics.domain.AnomalyRuleType
import app.ledger.analytics.domain.ConsumptionMapCategoryComposition
import app.ledger.analytics.domain.ConsumptionMapDetail
import app.ledger.analytics.domain.ConsumptionMapFilterOption
import app.ledger.analytics.domain.ConsumptionMapFilterOptions
import app.ledger.analytics.domain.ConsumptionMapFilters
import app.ledger.analytics.domain.ConsumptionMapGroupKind
import app.ledger.analytics.domain.ConsumptionMapPoint
import app.ledger.analytics.domain.ConsumptionMapQuery
import app.ledger.analytics.domain.ConsumptionMapResult
import app.ledger.analytics.domain.Dashboard
import app.ledger.analytics.domain.DashboardId
import app.ledger.analytics.domain.DashboardItem
import app.ledger.analytics.domain.DashboardRevision
import app.ledger.analytics.domain.DashboardRevisionId
import app.ledger.analytics.domain.DimensionValue
import app.ledger.analytics.domain.DrilldownPage
import app.ledger.analytics.domain.DrilldownQueryId
import app.ledger.analytics.domain.DrilldownTransaction
import app.ledger.analytics.domain.FixedReport
import app.ledger.analytics.domain.FixedReportCatalog
import app.ledger.analytics.domain.ForecastKey
import app.ledger.analytics.domain.ForecastMethod
import app.ledger.analytics.domain.ForecastResult
import app.ledger.analytics.domain.IntegrityCheckKey
import app.ledger.analytics.domain.IntegrityCheckResult
import app.ledger.analytics.domain.IntegritySeverity
import app.ledger.analytics.domain.MapViewport
import app.ledger.analytics.domain.Measure
import app.ledger.analytics.domain.MeasureValue
import app.ledger.analytics.domain.QuerySource
import app.ledger.analytics.domain.ReportComparison
import app.ledger.analytics.domain.ReportDefinition
import app.ledger.analytics.domain.ReportDefinitionId
import app.ledger.analytics.domain.ReportDefinitionRevision
import app.ledger.analytics.domain.ReportExecution
import app.ledger.analytics.domain.ReportPeriod
import app.ledger.analytics.domain.ReportQueryPlan
import app.ledger.analytics.domain.ReportRevisionId
import app.ledger.analytics.domain.ReportRow
import app.ledger.analytics.domain.ReportVisualization
import app.ledger.analytics.domain.SavedAnomalyRule
import app.ledger.analytics.domain.SavedDashboard
import app.ledger.analytics.domain.SavedReportDefinition
import app.ledger.analytics.domain.VisualizationResolution
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.domain.CategoryId
import app.ledger.finance.domain.LocalRevision
import app.ledger.finance.domain.UserAccountId
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
    val mapAccountOneId: StableId = id(1_130)
    val mapAccountTwoId: StableId = id(1_131)
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
        savedReports = savedReports(),
        dashboards = dashboards(),
        selectedDashboard = dashboards().single(),
        dashboardItems = dashboards().single().revision.items,
        draftName = "Monthly overview",
        draftVisualization = ReportVisualization.LINE,
        anomalyRules = anomalyRules(),
        anomalyFindings = anomalyFindings(),
        forecastKey = ForecastKey.MONTH_END_SPENDING,
        forecast = forecast(),
        consumptionMap = consumptionMap(),
        consumptionMapDetail = consumptionMapDetail(),
    )

    fun consumptionMap(): ConsumptionMapResult = ConsumptionMapResult(
        query = ConsumptionMapQuery(period, MapViewport.World),
        baseCurrency = currency,
        points = listOf(
            ConsumptionMapPoint(id(1_100), ConsumptionMapGroupKind.PLACE, "新宿", 356_900_000, 1_397_000_000, 12_800, 4),
            ConsumptionMapPoint(id(1_101), ConsumptionMapGroupKind.RECORDED_LOCATION, null, 356_800_000, 1_397_500_000, 6_200, 2),
        ),
        viewportBaseAmountMinor = 19_000,
        viewportTransactionCount = 6,
        asOfLocalRevision = revision,
        resultLimited = false,
    )

    fun consumptionMapWithSelectedAccounts(): ConsumptionMapResult = consumptionMap().let { result ->
        result.copy(
            query = result.query.copy(
                filters = ConsumptionMapFilters(
                    accountIds = setOf(UserAccountId(mapAccountOneId), UserAccountId(mapAccountTwoId)),
                ),
            ),
        )
    }

    fun consumptionMapFilterOptions(): ConsumptionMapFilterOptions = ConsumptionMapFilterOptions(
        accounts = listOf(
            ConsumptionMapFilterOption(mapAccountOneId, "Account 1"),
            ConsumptionMapFilterOption(mapAccountTwoId, "Account 2"),
        ),
        categories = emptyList(),
        merchants = emptyList(),
        places = emptyList(),
        projects = emptyList(),
    )

    fun consumptionMapDetail(): ConsumptionMapDetail = ConsumptionMapDetail(
        point = consumptionMap().points.first(),
        baseCurrency = currency,
        categories = listOf(
            ConsumptionMapCategoryComposition(CategoryId(id(1_120)), "餐饮", 8_000, 2),
            ConsumptionMapCategoryComposition(CategoryId(id(1_121)), "交通", 4_800, 2),
        ),
        transactionPreview = drilldown().rows,
        drilldownQueryId = queryId,
        asOfLocalRevision = revision,
    )

    fun savedReports(): List<SavedReportDefinition> {
        val definitionId = ReportDefinitionId(id(1_001))
        val revisionId = ReportRevisionId(id(1_002))
        return listOf(
            SavedReportDefinition(
                ReportDefinition(definitionId, "Monthly overview", revisionId, false, 1),
                ReportDefinitionRevision(revisionId, definitionId, 1, definition.spec, ReportVisualization.LINE, 1, 1_786_000_000_000L),
            ),
        )
    }

    fun dashboards(): List<SavedDashboard> {
        val dashboardId = DashboardId(id(1_010))
        val revisionId = DashboardRevisionId(id(1_011))
        val items = listOf(DashboardItem(savedReports().single().definition.id, 0))
        return listOf(
            SavedDashboard(
                Dashboard(dashboardId, "Planning", items, false, revisionId, 1),
                DashboardRevision(revisionId, dashboardId, 1, items, 1_786_000_000_000L),
            ),
        )
    }

    fun anomalyRules(): List<SavedAnomalyRule> = listOf(
        SavedAnomalyRule(
            AnomalyRuleId(id(1_020)),
            AnomalyRule(AnomalyRuleType.HISTORICAL_MEAN_STANDARD_DEVIATION, BigDecimal("2.0"), 12, AnalyticsAlgorithmVersion(1)),
            true,
            1,
        ),
    )

    fun anomalyFindings(): List<AnomalyFinding> = listOf(
        AnomalyFinding(
            anomalyRules().single().rule,
            LocalDate.of(2026, 8, 5),
            5_000,
            1_000,
            BigDecimal("4.0"),
            "ANOMALY_HISTORICAL_MEAN_STANDARD_DEVIATION",
            "food",
            1,
            LocalDate.of(2025, 8, 1),
            LocalDate.of(2026, 7, 31),
        ),
    )

    fun forecast(): ForecastResult = ForecastResult(
        ForecastMethod.CURRENT_DAILY_AVERAGE,
        82_000,
        LocalDate.of(2026, 8, 31),
        AnalyticsAlgorithmVersion(1),
        "FORECAST_DAILY_AVERAGE",
        revision,
        18_000,
        3_000,
        0,
        LocalDate.of(2026, 8, 1),
        LocalDate.of(2026, 8, 6),
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
