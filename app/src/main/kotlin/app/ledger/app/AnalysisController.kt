@file:Suppress("ComplexCondition", "LongParameterList", "MagicNumber", "MaxLineLength", "ReturnCount", "TooManyFunctions")

package app.ledger.app

import app.ledger.analytics.domain.AnalyticsAlgorithmVersion
import app.ledger.analytics.domain.AnalyticsApplicationPort
import app.ledger.analytics.domain.AnalyticsError
import app.ledger.analytics.domain.AnomalyRule
import app.ledger.analytics.domain.AnomalyRuleId
import app.ledger.analytics.domain.AnomalyRuleType
import app.ledger.analytics.domain.DashboardItem
import app.ledger.analytics.domain.DashboardItemWidth
import app.ledger.analytics.domain.Dimension
import app.ledger.analytics.domain.DrilldownQueryId
import app.ledger.analytics.domain.FixedReport
import app.ledger.analytics.domain.FixedReportCatalog
import app.ledger.analytics.domain.ForecastKey
import app.ledger.analytics.domain.FilterExpression
import app.ledger.analytics.domain.MapViewport
import app.ledger.analytics.domain.ReportDefinitionId
import app.ledger.analytics.domain.ReportExecution
import app.ledger.analytics.domain.ReportExportFormat
import app.ledger.analytics.domain.ReportExportPayload
import app.ledger.analytics.domain.ReportKey
import app.ledger.analytics.domain.ReportPeriod
import app.ledger.analytics.domain.ReportSpec
import app.ledger.analytics.domain.ReportVisualization
import app.ledger.analytics.domain.SaveAnomalyRuleRequest
import app.ledger.analytics.domain.SaveDashboardRequest
import app.ledger.analytics.domain.SaveReportDefinitionRequest
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.feature.analysis.AnalysisFeatureState
import app.ledger.feature.analysis.AnalysisEntityFilter
import app.ledger.feature.analysis.AnalysisExportScope
import app.ledger.feature.analysis.AnalysisLoadState
import app.ledger.feature.analysis.AnalysisPolicy
import app.ledger.feature.analysis.AnalysisPresentation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigDecimal
import java.time.LocalDate

/** Owns analysis drafts in memory: names and filter values never enter SavedState or routes; only stable identifiers do. */
internal class AnalysisController(
    private val application: AnalyticsApplicationPort,
    private val formatCopyName: (String) -> String,
) {
    private val mutableState = MutableStateFlow<AnalysisLoadState>(AnalysisLoadState.Loading())
    val state: StateFlow<AnalysisLoadState> = mutableState.asStateFlow()

    private var bookId: StableId? = null
    private var baseCurrency: CurrencyCode? = null
    private var period: ReportPeriod? = null
    private var today: LocalDate? = null
    private var screenId: String = "ANA-001"
    private var report: FixedReport? = null
    private var queryId: DrilldownQueryId? = null
    private var entityId: StableId? = null
    private var forecastKey: ForecastKey? = null
    private var appliedSpec: ReportSpec? = null
    private var appliedSpecReport: FixedReport? = null
    private var preparedExport: ReportExportPayload? = null
    private var preparedExportId: StableId? = null
    private var exportFormat: ReportExportFormat = ReportExportFormat.CSV
    private var exportScope: AnalysisExportScope = AnalysisExportScope.CURRENT_AND_COMPARISON
    private var reportDraftId: StableId? = null
    private var reportDraftName: String = ""
    private var customDraftSpec: ReportSpec? = null
    private var customVisualization: ReportVisualization = ReportVisualization.LINE
    private var builderStep: Int = 0
    private var draftFilterBaseline: FilterExpression? = null
    private var dashboardDraftId: StableId? = null
    private var dashboardDraftName: String = ""
    private var dashboardItems: List<DashboardItem> = emptyList()
    private var anomalyDraftId: AnomalyRuleId? = null
    private var anomalyDraftType: AnomalyRuleType = AnomalyRuleType.HISTORICAL_MEAN_STANDARD_DEVIATION
    private var anomalyThresholdText: String = "2.0"
    private var anomalyLookbackText: String = "12"
    private val consumptionMap = ConsumptionMapController(application)

    suspend fun open(
        bookId: StableId,
        baseCurrency: CurrencyCode,
        today: LocalDate,
        screenId: String,
        reportKey: String?,
        queryId: DrilldownQueryId?,
        entityId: StableId? = null,
        forecastKey: ForecastKey? = null,
    ) {
        if (this.bookId != bookId) {
            this.bookId = bookId
            period = AnalysisPolicy.initialPeriod(today)
            appliedSpec = null
            appliedSpecReport = null
            preparedExport = null
            preparedExportId = null
            reportDraftId = null
            builderStep = 0
            draftFilterBaseline = null
            dashboardDraftId = null
            dashboardItems = emptyList()
            consumptionMap.reset()
        }
        this.baseCurrency = baseCurrency
        this.today = today
        this.screenId = screenId
        report = reportKey?.toFixedReportOrNull()
        this.queryId = queryId
        this.entityId = entityId
        this.forecastKey = forecastKey
        loadCurrent()
    }

    suspend fun reload() = loadCurrent()

    suspend fun previousPeriod() {
        period = period?.let(AnalysisPolicy::previousPeriod)
        loadCurrent()
    }

    suspend fun nextPeriod() {
        period = period?.let(AnalysisPolicy::nextPeriod)
        loadCurrent()
    }

    fun cycleMeasure() = editDraft(AnalysisPolicy::cycleMeasure)
    fun cycleDimension() = editDraft(AnalysisPolicy::cycleDimension)
    fun cycleGranularity() = editDraft(AnalysisPolicy::cycleGranularity)
    fun cycleComparison() = editDraft(AnalysisPolicy::cycleComparison)

    fun selectMeasure(value: app.ledger.analytics.domain.Measure) = editDraft { AnalysisPolicy.selectMeasure(it, value) }

    fun selectDimension(value: Dimension) = editDraft { AnalysisPolicy.selectDimension(it, value) }

    fun selectGranularity(value: app.ledger.analytics.domain.TimeGranularity) = editDraft { AnalysisPolicy.selectGranularity(it, value) }

    fun selectComparison(value: app.ledger.analytics.domain.ComparisonMode?) = editDraft { AnalysisPolicy.selectComparison(it, value) }

    fun cycleSort(stableKey: String) = editDraft { AnalysisPolicy.cycleSort(it, stableKey) }

    fun toggleReportFilter(filter: AnalysisEntityFilter, id: StableId) = editDraft { spec ->
        val selected = AnalysisPolicy.selectedFilterIds(spec, filter).toMutableSet().apply {
            if (!add(id)) remove(id)
        }
        AnalysisPolicy.replaceEntityFilter(spec, filter, selected)
    }

    fun removeReportFilter(stableKey: String) = editDraft { AnalysisPolicy.removeFilter(it, stableKey) }

    fun resetReportFilters() = editDraft { it.copy(filters = draftFilterBaseline ?: FilterExpression.All) }

    fun changeBuilderStep(delta: Int) {
        builderStep = (builderStep + delta).coerceIn(0, BUILDER_LAST_STEP)
        updateCurrent { it.copy(builderStep = builderStep) }
    }

    fun updateDraftName(value: String) {
        if (screenId == "ANA-007") dashboardDraftName = value.take(80) else reportDraftName = value.take(80)
        updateCurrent {
            it.copy(
                draftName = value.take(80),
                presentation = if (value.isBlank()) {
                    AnalysisPresentation.INVALID
                } else if (screenId == "ANA-007") {
                    if (dashboardItems.isEmpty()) AnalysisPresentation.EMPTY_CANVAS else if (dashboardDraftId == null) AnalysisPresentation.CREATE else AnalysisPresentation.EDIT
                } else {
                    AnalysisPresentation.EDITING
                },
            )
        }
    }

    fun selectVisualization(value: ReportVisualization) {
        customVisualization = value
        updateCurrent {
            it.copy(
                draftVisualization = value,
                presentation = if (value == ReportVisualization.PIE && Dimension.CATEGORY in requireNotNull(customDraftSpec).dimensions) {
                    AnalysisPresentation.AUTO_FALLBACK_TO_BAR
                } else {
                    AnalysisPresentation.CONTENT
                },
            )
        }
    }

    fun applyFilter(): Boolean {
        val content = (mutableState.value as? AnalysisLoadState.Content)?.state ?: return false
        val fixed = content.fixedReport ?: return false
        val spec = content.draftSpec ?: return false
        if (!AnalysisPolicy.reportSpecValid(spec)) {
            updateCurrent { it.copy(presentation = AnalysisPresentation.INVALID) }
            return false
        }
        appliedSpec = spec
        appliedSpecReport = fixed
        return true
    }

    suspend fun previewCustomReport() {
        val spec = customDraftSpec ?: return
        updateCurrent { it.copy(presentation = AnalysisPresentation.PREVIEWING) }
        when (val result = application.executeCustom(requireBookId(), spec, requirePeriod(), customVisualization)) {
            is DomainResult.Success -> updateCurrent {
                it.copy(presentation = AnalysisPolicy.reportPresentation(result.value), execution = result.value)
            }
            is DomainResult.Failure -> updateCurrent { it.copy(presentation = AnalysisPresentation.INVALID, failureCode = result.error.code) }
        }
    }

    suspend fun saveCustomReport() {
        val spec = customDraftSpec ?: return
        if (reportDraftName.isBlank()) return updateCurrent { it.copy(presentation = AnalysisPresentation.INVALID) }
        val reports = (application.savedReports(requireBookId()) as? DomainResult.Success)?.value.orEmpty()
        val existing = reports.singleOrNull { it.definition.id.value == reportDraftId }
        when (
            val result = application.saveReport(
                requireBookId(),
                SaveReportDefinitionRequest(existing?.definition?.id, reportDraftName, existing?.definition?.rowVersion, spec, customVisualization),
            )
        ) {
            is DomainResult.Success -> {
                reportDraftId = result.value.definition.id.value
                entityId = reportDraftId
                loadBuilder(forceReload = true)
            }
            is DomainResult.Failure -> updateCurrent { it.copy(presentation = AnalysisPresentation.INVALID, failureCode = result.error.code) }
        }
    }

    suspend fun copyCustomReport(reportId: ReportDefinitionId) {
        val reports = (application.savedReports(requireBookId()) as? DomainResult.Success)?.value.orEmpty()
        val source = reports.singleOrNull { it.definition.id == reportId } ?: return
        application.copyReport(requireBookId(), reportId, formatCopyName(source.definition.name))
        loadCurrent()
    }

    fun toggleDashboardReport(reportId: ReportDefinitionId) {
        dashboardItems = if (dashboardItems.any { it.reportId == reportId }) {
            dashboardItems.filterNot { it.reportId == reportId }.mapIndexed { index, item -> item.copy(sortOrder = index) }
        } else {
            dashboardItems + DashboardItem(reportId, dashboardItems.size, DashboardItemWidth.FULL)
        }
        updateCurrent {
            it.copy(
                dashboardItems = dashboardItems,
                presentation = if (dashboardItems.isEmpty()) AnalysisPresentation.EMPTY_CANVAS else if (dashboardDraftId == null) AnalysisPresentation.CREATE else AnalysisPresentation.EDIT,
            )
        }
    }

    fun moveDashboardReport(reportId: ReportDefinitionId, delta: Int) {
        val ordered = dashboardItems.sortedBy(DashboardItem::sortOrder).toMutableList()
        val index = ordered.indexOfFirst { it.reportId == reportId }
        val target = index + delta
        if (index < 0 || target !in ordered.indices) return
        val item = ordered.removeAt(index)
        ordered.add(target, item)
        dashboardItems = ordered.mapIndexed { order, value -> value.copy(sortOrder = order) }
        updateCurrent { it.copy(dashboardItems = dashboardItems, presentation = if (dashboardDraftId == null) AnalysisPresentation.CREATE else AnalysisPresentation.EDIT) }
    }

    fun toggleDashboardWidth(reportId: ReportDefinitionId) {
        val current = (mutableState.value as? AnalysisLoadState.Content)?.state ?: return
        val report = current.savedReports.singleOrNull { it.definition.id == reportId } ?: return
        val index = dashboardItems.indexOfFirst { it.reportId == reportId }
        if (index < 0) return
        val item = dashboardItems[index]
        if (item.width == DashboardItemWidth.FULL && report.revision.visualization != ReportVisualization.METRIC_CARD) {
            return updateCurrent { it.copy(presentation = AnalysisPresentation.INVALID, failureCode = "DASHBOARD_HALF_REQUIRES_METRIC") }
        }
        dashboardItems = dashboardItems.toMutableList().also { items ->
            items[index] = item.copy(width = if (item.width == DashboardItemWidth.FULL) DashboardItemWidth.HALF_METRIC else DashboardItemWidth.FULL)
        }
        updateCurrent { it.copy(dashboardItems = dashboardItems, presentation = if (dashboardDraftId == null) AnalysisPresentation.CREATE else AnalysisPresentation.EDIT) }
    }

    suspend fun saveDashboard() {
        if (dashboardDraftName.isBlank()) return updateCurrent { it.copy(presentation = AnalysisPresentation.INVALID) }
        val dashboards = (application.dashboards(requireBookId()) as? DomainResult.Success)?.value.orEmpty()
        val existing = dashboards.singleOrNull { it.dashboard.id.value == dashboardDraftId }
        when (
            val result = application.saveDashboard(
                requireBookId(),
                SaveDashboardRequest(existing?.dashboard?.id, dashboardDraftName, existing?.dashboard?.rowVersion, dashboardItems),
            )
        ) {
            is DomainResult.Success -> {
                dashboardDraftId = result.value.dashboard.id.value
                entityId = dashboardDraftId
                loadDashboardEditor(forceReload = true)
            }
            is DomainResult.Failure -> updateCurrent { it.copy(presentation = AnalysisPresentation.INVALID, failureCode = result.error.code) }
        }
    }

    fun editAnomalyRule(ruleId: AnomalyRuleId?) {
        val current = (mutableState.value as? AnalysisLoadState.Content)?.state ?: return
        val existing = current.anomalyRules.singleOrNull { it.id == ruleId }
        anomalyDraftId = existing?.id
        anomalyDraftType = existing?.rule?.type ?: AnomalyRuleType.HISTORICAL_MEAN_STANDARD_DEVIATION
        anomalyThresholdText = existing?.rule?.threshold?.toPlainString() ?: "2.0"
        anomalyLookbackText = existing?.rule?.lookbackPeriods?.toString() ?: "12"
        updateCurrent { it.withAnomalyDraft(AnalysisPresentation.CONTENT) }
    }

    fun cycleAnomalyType() {
        anomalyDraftType = AnomalyRuleType.entries[(anomalyDraftType.ordinal + 1) % AnomalyRuleType.entries.size]
        updateCurrent { it.withAnomalyDraft(it.presentation) }
    }

    fun updateAnomalyThreshold(value: String) {
        anomalyThresholdText = value.take(32)
        updateCurrent { it.withAnomalyDraft(it.presentation) }
    }

    fun updateAnomalyLookback(value: String) {
        anomalyLookbackText = value.take(3)
        updateCurrent { it.withAnomalyDraft(it.presentation) }
    }

    suspend fun saveAnomalyRule(ruleId: AnomalyRuleId?) {
        val current = (mutableState.value as? AnalysisLoadState.Content)?.state ?: return
        val existing = current.anomalyRules.singleOrNull { it.id == (ruleId ?: anomalyDraftId) }
        val threshold = anomalyThresholdText.toBigDecimalOrNull()
        val lookback = anomalyLookbackText.toIntOrNull()
        if (threshold == null || threshold.signum() < 0 || lookback == null || lookback !in 1..120) {
            return updateCurrent { it.withAnomalyDraft(AnalysisPresentation.INVALID) }
        }
        val rule = AnomalyRule(anomalyDraftType, threshold, lookback, AnalyticsAlgorithmVersion(1))
        when (val result = application.saveAnomalyRule(requireBookId(), SaveAnomalyRuleRequest(existing?.id, existing?.rowVersion, rule, true))) {
            is DomainResult.Success -> loadAnomalies()
            is DomainResult.Failure -> updateCurrent { it.copy(presentation = AnalysisPresentation.INVALID, failureCode = result.error.code) }
        }
    }

    fun selectExportFormat(format: ReportExportFormat) {
        exportFormat = format
        updateCurrent { it.copy(exportFormat = format) }
    }

    fun selectExportScope(scope: AnalysisExportScope) {
        exportScope = scope
        updateCurrent { it.copy(exportScope = scope) }
    }

    fun prepareExport(format: ReportExportFormat = exportFormat): Boolean {
        val content = (mutableState.value as? AnalysisLoadState.Content)?.state ?: return false
        val execution = content.execution as? ReportExecution.Content ?: return false
        preparedExport = (application.prepareExport(execution, content.period, format) as? DomainResult.Success)?.value
        return preparedExport != null
    }

    fun prepareCurrentExport(): Boolean {
        val payload = preparedExport ?: return false
        val selected = payload.copy(
            format = exportFormat,
            comparison = payload.comparison.takeIf { exportScope == AnalysisExportScope.CURRENT_AND_COMPARISON },
        )
        updateCurrent { it.copy(exportFormat = exportFormat, exportScope = exportScope, exportPayload = selected) }
        return true
    }

    fun bindPreparedExportId(id: StableId) {
        preparedExportId = id
    }

    internal fun preparedExportForTransfer(): ReportExportPayload? = preparedExport?.copy(
        format = exportFormat,
        comparison = preparedExport?.comparison.takeIf { exportScope == AnalysisExportScope.CURRENT_AND_COMPARISON },
    )

    suspend fun loadNextDrilldown() {
        val id = queryId ?: return
        val current = (mutableState.value as? AnalysisLoadState.Content)?.state ?: return
        val currentPage = current.drilldown ?: return
        val cursor = currentPage.nextCursor ?: return
        val result = application.drillDown(requireBookId(), id, cursor, DRILLDOWN_PAGE_SIZE)
        if (result is DomainResult.Success) {
            mutableState.value = AnalysisLoadState.Content(
                current.copy(drilldown = currentPage.copy(rows = currentPage.rows + result.value.rows, nextCursor = result.value.nextCursor)),
            )
        }
    }

    suspend fun runIntegrity(repair: Boolean = false) {
        val current = (mutableState.value as? AnalysisLoadState.Content)?.state
        mutableState.value = AnalysisLoadState.Content(baseState("ANA-015", AnalysisPresentation.RUNNING).copy(integrity = current?.integrity))
        val result = if (repair) application.repairAnalyticsProjections(requireBookId()) else application.integrity(requireBookId())
        mutableState.value = when (result) {
            is DomainResult.Success -> AnalysisLoadState.Content(baseState("ANA-015", AnalysisPolicy.integrityPresentation(result.value)).copy(integrity = result.value))
            is DomainResult.Failure -> AnalysisLoadState.Failure("ANA-015", result.error.code)
        }
    }

    fun toggleTechnicalDetails() = updateCurrent { it.copy(technicalDetailsExpanded = !it.technicalDetailsExpanded) }

    suspend fun cycleMapMode() = updateMap { consumptionMap.cycleMode(requirePeriod()) }

    suspend fun cycleMapWeight() = updateMap { consumptionMap.cycleWeight(requirePeriod()) }

    suspend fun cycleMapAggregation() = updateMap { consumptionMap.cycleAggregation(requirePeriod()) }

    suspend fun cycleMapPresentation() = updateMap { consumptionMap.cyclePresentation(requirePeriod()) }

    suspend fun toggleMapSpecialTransactions() = updateMap { consumptionMap.toggleSpecialTransactions(requirePeriod()) }

    suspend fun resetMapFilters() {
        period = AnalysisPolicy.initialPeriod(requireNotNull(today))
        updateMap { consumptionMap.resetFilters(requirePeriod()) }
    }

    suspend fun cycleMapAccountFilter() = cycleMapFilter(ConsumptionMapFilterDimension.ACCOUNT)

    suspend fun cycleMapCategoryFilter() = cycleMapFilter(ConsumptionMapFilterDimension.CATEGORY)

    suspend fun cycleMapMerchantFilter() = cycleMapFilter(ConsumptionMapFilterDimension.MERCHANT)

    suspend fun cycleMapPlaceFilter() = cycleMapFilter(ConsumptionMapFilterDimension.PLACE)

    suspend fun cycleMapProjectFilter() = cycleMapFilter(ConsumptionMapFilterDimension.PROJECT)

    suspend fun cycleMapAmountFilter() = updateMap { consumptionMap.cycleAmount(requirePeriod()) }

    suspend fun removeMapFilter(stableKey: String) = updateMap {
        consumptionMap.removeFilter(requirePeriod(), stableKey)
    }

    private suspend fun cycleMapFilter(dimension: ConsumptionMapFilterDimension) = updateMap {
        consumptionMap.cycleFilter(requireBookId(), requirePeriod(), dimension)
    }

    suspend fun updateMapViewport(viewport: MapViewport) {
        if (consumptionMap.updateViewport(requirePeriod(), viewport)) loadMapIntoState()
    }

    fun markMapUnavailable() {
        consumptionMap.markUnavailable()
        updateCurrent { current ->
            if (current.screenId == "ANA-011" && current.consumptionMap != null) {
                current.copy(presentation = AnalysisPresentation.MAP_UNAVAILABLE)
            } else {
                current
            }
        }
    }

    private suspend fun loadCurrent() {
        val previous = (mutableState.value as? AnalysisLoadState.Content)?.state?.takeIf { it.screenId == screenId }
        mutableState.value = if (previous == null) {
            AnalysisLoadState.Loading()
        } else {
            AnalysisLoadState.Content(
                previous.copy(
                    period = requirePeriod(),
                    presentation = if (screenId == "ANA-001") AnalysisPresentation.CALCULATING else AnalysisPresentation.LOADING,
                ),
            )
        }
        mutableState.value = when (screenId) {
            "ANA-001" -> loadOverview()
            "ANA-002" -> AnalysisLoadState.Content(baseState(screenId, AnalysisPresentation.CONTENT))
            "ANA-003" -> loadReport()
            "ANA-004" -> loadFilter()
            "ANA-005" -> loadDrilldown()
            "ANA-006" -> loadDashboards()
            "ANA-007" -> loadDashboardEditor()
            "ANA-008" -> loadBuilder()
            "ANA-009" -> loadVisualizationPicker()
            "ANA-010" -> loadExport()
            "ANA-011" -> loadMap()
            "ANA-012" -> loadMapDetail()
            "ANA-013" -> loadAnomalies()
            "ANA-014" -> loadForecast()
            "ANA-015" -> AnalysisLoadState.Content(baseState(screenId, AnalysisPresentation.NOT_RUN))
            else -> AnalysisLoadState.Failure(screenId, "ANALYSIS_SCREEN_UNKNOWN")
        }
    }

    private suspend fun loadOverview(): AnalysisLoadState = when (val result = application.overview(requireBookId(), requirePeriod())) {
        is DomainResult.Success -> AnalysisLoadState.Content(
            baseState("ANA-001", if (result.value.transactionCount == 0L) AnalysisPresentation.NO_DATA else AnalysisPresentation.CONTENT).copy(overview = result.value),
        )
        is DomainResult.Failure -> AnalysisLoadState.Failure("ANA-001", result.error.code)
    }

    private suspend fun loadReport(): AnalysisLoadState {
        val fixed = report ?: return AnalysisLoadState.Failure("ANA-003", "REPORT_KEY_INVALID")
        val override = appliedSpec.takeIf { appliedSpecReport == fixed }
        val result = if (override == null) {
            application.executeFixed(requireBookId(), fixed, requirePeriod())
        } else {
            application.executeCustom(
                requireBookId(),
                override,
                requirePeriod(),
                FixedReportCatalog.definition(fixed).defaultVisualization,
            )
        }
        return when (result) {
            is DomainResult.Success -> {
                val execution = result.value.attachFixedReport(fixed)
                AnalysisLoadState.Content(baseState("ANA-003", AnalysisPolicy.reportPresentation(execution)).copy(fixedReport = fixed, execution = execution))
            }
            is DomainResult.Failure -> AnalysisLoadState.Failure("ANA-003", result.error.code)
        }
    }

    private suspend fun loadFilter(): AnalysisLoadState {
        val fixed = report ?: return AnalysisLoadState.Failure("ANA-004", "REPORT_KEY_INVALID")
        val spec = appliedSpec.takeIf { appliedSpecReport == fixed } ?: FixedReportCatalog.definition(fixed).spec
        draftFilterBaseline = FixedReportCatalog.definition(fixed).spec.filters
        val options = (application.consumptionMapFilterOptions(requireBookId()) as? DomainResult.Success)?.value
        return AnalysisLoadState.Content(
            baseState("ANA-004", AnalysisPresentation.EDITING).copy(
                fixedReport = fixed,
                draftSpec = spec,
                consumptionMapFilterOptions = options,
            ),
        )
    }

    private suspend fun loadDrilldown(): AnalysisLoadState {
        val id = queryId ?: return AnalysisLoadState.Content(baseState("ANA-005", AnalysisPresentation.EXPIRED_QUERY))
        return when (val result = application.drillDown(requireBookId(), id, null, DRILLDOWN_PAGE_SIZE)) {
            is DomainResult.Success -> AnalysisLoadState.Content(
                baseState("ANA-005", if (result.value.rows.isEmpty()) AnalysisPresentation.EMPTY else AnalysisPresentation.CONTENT).copy(drilldown = result.value),
            )
            is DomainResult.Failure -> AnalysisLoadState.Content(
                baseState("ANA-005", if (result.error == AnalyticsError.ExpiredDrilldown) AnalysisPresentation.EXPIRED_QUERY else AnalysisPresentation.ERROR).copy(failureCode = result.error.code),
            )
        }
    }

    private suspend fun loadDashboards(): AnalysisLoadState {
        val reports = (application.savedReports(requireBookId()) as? DomainResult.Success)?.value
            ?: return AnalysisLoadState.Failure("ANA-006", AnalyticsError.DatabaseUnavailable.code)
        return when (val result = application.dashboards(requireBookId())) {
            is DomainResult.Success -> AnalysisLoadState.Content(
                baseState("ANA-006", if (result.value.isEmpty() && reports.isEmpty()) AnalysisPresentation.EMPTY else AnalysisPresentation.CONTENT)
                    .copy(dashboards = result.value, savedReports = reports),
            )
            is DomainResult.Failure -> AnalysisLoadState.Failure("ANA-006", result.error.code)
        }
    }

    private suspend fun loadDashboardEditor(forceReload: Boolean = false): AnalysisLoadState {
        val reports = (application.savedReports(requireBookId()) as? DomainResult.Success)?.value
            ?: return AnalysisLoadState.Failure("ANA-007", AnalyticsError.DatabaseUnavailable.code)
        val dashboards = (application.dashboards(requireBookId()) as? DomainResult.Success)?.value
            ?: return AnalysisLoadState.Failure("ANA-007", AnalyticsError.DatabaseUnavailable.code)
        val selected = entityId?.let { id -> dashboards.singleOrNull { it.dashboard.id.value == id } }
        if (entityId != null && selected == null) return AnalysisLoadState.Failure("ANA-007", AnalyticsError.DefinitionNotFound.code)
        if (forceReload || dashboardDraftId != entityId) {
            dashboardDraftId = entityId
            dashboardDraftName = selected?.dashboard?.name.orEmpty()
            dashboardItems = selected?.revision?.items.orEmpty()
        }
        val presentation = when {
            dashboardItems.isEmpty() -> AnalysisPresentation.EMPTY_CANVAS
            selected == null -> AnalysisPresentation.CREATE
            else -> AnalysisPresentation.EDIT
        }
        return AnalysisLoadState.Content(
            baseState("ANA-007", presentation).copy(
                savedReports = reports,
                dashboards = dashboards,
                selectedDashboard = selected,
                dashboardItems = dashboardItems,
                draftName = dashboardDraftName,
            ),
        )
    }

    private suspend fun loadBuilder(forceReload: Boolean = false): AnalysisLoadState {
        val reports = (application.savedReports(requireBookId()) as? DomainResult.Success)?.value
            ?: return AnalysisLoadState.Failure("ANA-008", AnalyticsError.DatabaseUnavailable.code)
        val selected = entityId?.let { id -> reports.singleOrNull { it.definition.id.value == id } }
        if (entityId != null && selected == null) return AnalysisLoadState.Failure("ANA-008", AnalyticsError.DefinitionNotFound.code)
        if (forceReload || reportDraftId != entityId || customDraftSpec == null) {
            reportDraftId = entityId
            reportDraftName = selected?.definition?.name.orEmpty()
            customDraftSpec = selected?.revision?.spec ?: FixedReportCatalog.definition(FixedReport.INCOME_EXPENSE_NET).spec
            customVisualization = selected?.revision?.visualization ?: ReportVisualization.LINE
            builderStep = 0
            draftFilterBaseline = customDraftSpec?.filters
        }
        val options = (application.consumptionMapFilterOptions(requireBookId()) as? DomainResult.Success)?.value
        return AnalysisLoadState.Content(
            baseState("ANA-008", AnalysisPresentation.EDITING).copy(
                savedReports = reports,
                draftSpec = customDraftSpec,
                draftName = reportDraftName,
                draftVisualization = customVisualization,
                builderStep = builderStep,
                consumptionMapFilterOptions = options,
            ),
        )
    }

    private fun loadVisualizationPicker(): AnalysisLoadState {
        val spec = customDraftSpec ?: FixedReportCatalog.definition(FixedReport.INCOME_EXPENSE_NET).spec.also { customDraftSpec = it }
        return AnalysisLoadState.Content(baseState("ANA-009", AnalysisPresentation.CONTENT).copy(draftSpec = spec, draftVisualization = customVisualization))
    }

    private fun loadExport(): AnalysisLoadState = if (entityId == null || entityId != preparedExportId || preparedExport == null) {
        AnalysisLoadState.Failure("ANA-010", "REPORT_EXPORT_EXPIRED")
    } else {
        AnalysisLoadState.Content(
            baseState("ANA-010", AnalysisPresentation.CONTENT).copy(
                exportFormat = exportFormat,
                exportScope = exportScope,
                exportPayload = preparedExport,
            ),
        )
    }

    private suspend fun loadAnomalies(): AnalysisLoadState {
        val rules = (application.anomalyRules(requireBookId()) as? DomainResult.Success)?.value
            ?: return AnalysisLoadState.Failure("ANA-013", AnalyticsError.DatabaseUnavailable.code)
        val findings = (application.anomalyFindings(requireBookId(), requirePeriod()) as? DomainResult.Success)?.value
            ?: return AnalysisLoadState.Failure("ANA-013", AnalyticsError.DatabaseUnavailable.code)
        return AnalysisLoadState.Content(
            baseState("ANA-013", if (rules.isEmpty()) AnalysisPresentation.EMPTY else AnalysisPresentation.CONTENT)
                .copy(anomalyRules = rules, anomalyFindings = findings)
                .withAnomalyDraft(if (rules.isEmpty()) AnalysisPresentation.EMPTY else AnalysisPresentation.CONTENT),
        )
    }

    private suspend fun loadForecast(): AnalysisLoadState {
        val key = forecastKey ?: return AnalysisLoadState.Failure("ANA-014", "FORECAST_KEY_INVALID")
        val comparisons = buildMap {
            ForecastKey.entries.forEach { candidate ->
                (application.forecast(requireBookId(), candidate, requireNotNull(today)) as? DomainResult.Success)?.value?.let { put(candidate, it) }
            }
        }
        return when (val result = comparisons[key]) {
            is app.ledger.analytics.domain.ForecastResult -> AnalysisLoadState.Content(
                baseState("ANA-014", AnalysisPresentation.CONTENT).copy(
                    forecastKey = key,
                    forecast = result,
                    forecastComparisons = comparisons,
                ),
            )
            null -> AnalysisLoadState.Content(baseState("ANA-014", AnalysisPresentation.INSUFFICIENT_DATA).copy(forecastKey = key, failureCode = "FORECAST_INSUFFICIENT_DATA"))
        }
    }

    private suspend fun loadMap(): AnalysisLoadState = consumptionMap.loadMap(requireBookId(), requirePeriod()) { presentation ->
        baseState("ANA-011", presentation)
    }

    private suspend fun loadMapIntoState() {
        val expected = consumptionMap.current(requirePeriod())
        val loaded = loadMap()
        if (expected == consumptionMap.current(requirePeriod()) && screenId == "ANA-011") mutableState.value = loaded
    }

    private suspend fun loadMapDetail(): AnalysisLoadState {
        val pointId = entityId ?: return AnalysisLoadState.Failure("ANA-012", "MAP_POINT_ID_INVALID")
        return consumptionMap.loadDetail(requireBookId(), requirePeriod(), pointId) { presentation ->
            baseState("ANA-012", presentation)
        }
    }

    private suspend fun updateMap(action: suspend () -> Unit) {
        action()
        loadMapIntoState()
    }

    private fun editDraft(transform: (ReportSpec) -> ReportSpec) {
        val content = (mutableState.value as? AnalysisLoadState.Content)?.state ?: return
        val draft = content.draftSpec ?: return
        val updated = transform(draft)
        if (screenId == "ANA-008" || screenId == "ANA-009") customDraftSpec = updated
        mutableState.value = AnalysisLoadState.Content(content.copy(draftSpec = updated, presentation = AnalysisPresentation.EDITING))
    }

    private fun updateCurrent(transform: (AnalysisFeatureState) -> AnalysisFeatureState) {
        val current = (mutableState.value as? AnalysisLoadState.Content)?.state ?: return
        mutableState.value = AnalysisLoadState.Content(transform(current))
    }

    private fun AnalysisFeatureState.withAnomalyDraft(presentation: AnalysisPresentation): AnalysisFeatureState = copy(
        presentation = presentation,
        editingAnomalyRuleId = anomalyDraftId,
        anomalyDraftType = anomalyDraftType,
        anomalyThresholdText = anomalyThresholdText,
        anomalyLookbackText = anomalyLookbackText,
    )

    private fun baseState(screen: String, presentation: AnalysisPresentation): AnalysisFeatureState = AnalysisFeatureState(
        screen,
        presentation,
        requirePeriod(),
        application.fixedReports(),
        requireNotNull(baseCurrency),
    )

    private fun String.toFixedReportOrNull(): FixedReport? = runCatching { FixedReportCatalog.definition(ReportKey(this))?.report }.getOrNull()

    private fun ReportExecution.attachFixedReport(fixed: FixedReport): ReportExecution = when (this) {
        is ReportExecution.Content -> copy(fixedReport = fixed)
        is ReportExecution.Empty -> copy(fixedReport = fixed)
        is ReportExecution.StaleProjection -> this
    }

    private fun requireBookId(): StableId = requireNotNull(bookId)
    private fun requirePeriod(): ReportPeriod = requireNotNull(period)

    private companion object {
        const val DRILLDOWN_PAGE_SIZE = 50
        const val BUILDER_LAST_STEP = 6
    }
}
