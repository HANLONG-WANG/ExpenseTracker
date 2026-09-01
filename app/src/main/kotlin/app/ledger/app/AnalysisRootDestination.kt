@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package app.ledger.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.core.geo.LedgerMap
import app.ledger.core.geo.LedgerMapAccessibleRow
import app.ledger.core.geo.LedgerMapMode
import app.ledger.core.geo.LedgerMapPoint
import app.ledger.core.geo.LedgerMapState
import app.ledger.core.geo.LedgerMapStyleConfiguration
import app.ledger.feature.analysis.AnalysisDestination
import app.ledger.feature.analysis.AnalysisActions
import app.ledger.feature.analysis.AnalysisPolicy
import app.ledger.feature.analysis.R as AnalysisR

@Composable
internal fun analysisDestinationTitleOrNull(screenId: String): String? = when (screenId) {
    "ANA-001" -> stringResource(AnalysisR.string.analysis_title_home)
    "ANA-002" -> stringResource(AnalysisR.string.analysis_title_catalog)
    "ANA-003" -> stringResource(AnalysisR.string.analysis_title_report)
    "ANA-004" -> stringResource(AnalysisR.string.analysis_title_filter)
    "ANA-005" -> stringResource(AnalysisR.string.analysis_title_drilldown)
    "ANA-006" -> stringResource(AnalysisR.string.analysis_title_dashboards)
    "ANA-007" -> stringResource(AnalysisR.string.analysis_title_dashboard_editor)
    "ANA-008" -> stringResource(AnalysisR.string.analysis_title_builder)
    "ANA-009" -> stringResource(AnalysisR.string.analysis_title_visualization)
    "ANA-010" -> stringResource(AnalysisR.string.analysis_title_export)
    "ANA-011" -> stringResource(AnalysisR.string.analysis_title_consumption_map)
    "ANA-012" -> stringResource(AnalysisR.string.analysis_title_map_detail)
    "ANA-013" -> stringResource(AnalysisR.string.analysis_title_anomaly)
    "ANA-014" -> stringResource(AnalysisR.string.analysis_title_forecast)
    "ANA-015" -> stringResource(AnalysisR.string.analysis_title_integrity)
    else -> null
}

@Composable
internal fun AnalysisRootDestination(
    screenId: String,
    encodedArguments: Map<String, String>,
    viewModel: AppRootViewModel,
    onNavigationChanged: () -> Unit,
) {
    val reportKey = encodedArguments["reportKey"]
    val queryId = encodedArguments["queryId"]?.let { StableId.parse(it).getOrNull() }
    val entityId = when (screenId) {
        "ANA-007" -> encodedArguments["dashboardId"]
        "ANA-008" -> encodedArguments["definitionId"]
        "ANA-010" -> encodedArguments["reportInstanceId"]
        "ANA-012" -> encodedArguments["placeOrClusterId"]
        else -> null
    }?.let { StableId.parse(it).getOrNull() }
    val forecastKey = encodedArguments["forecastKey"]?.let(app.ledger.analytics.domain.ForecastKey::fromRouteKey)
    val state by viewModel.analysis.collectAsStateWithLifecycle()
    LaunchedEffect(screenId, reportKey, queryId, entityId, forecastKey) {
        viewModel.loadAnalysis(screenId, reportKey, queryId, entityId, forecastKey)
    }
    GovernedDestinationModal(
        screenId,
        analysisDestinationTitleOrNull(screenId).orEmpty(),
        onDismiss = {
            viewModel.requestRootBack()
            onNavigationChanged()
        },
    ) {
    AnalysisDestination(
        screenId,
        state,
        AnalysisActions(
            onNavigate = { target, report, drilldown ->
                viewModel.navigateAnalysis(target, report, drilldown)
                onNavigationChanged()
            },
            onRetry = viewModel::retryAnalysis,
            onPreviousPeriod = viewModel::previousAnalysisPeriod,
            onNextPeriod = viewModel::nextAnalysisPeriod,
            onCycleMeasure = viewModel::cycleAnalysisMeasure,
            onCycleDimension = viewModel::cycleAnalysisDimension,
            onCycleGranularity = viewModel::cycleAnalysisGranularity,
            onCycleComparison = viewModel::cycleAnalysisComparison,
            onSelectMeasure = viewModel::selectAnalysisMeasure,
            onSelectDimension = viewModel::selectAnalysisDimension,
            onSelectGranularity = viewModel::selectAnalysisGranularity,
            onSelectComparison = viewModel::selectAnalysisComparison,
            onCycleSort = viewModel::cycleAnalysisSort,
            onToggleReportFilter = viewModel::toggleAnalysisReportFilter,
            onRemoveReportFilter = viewModel::removeAnalysisReportFilter,
            onResetReportFilters = viewModel::resetAnalysisReportFilters,
            onBuilderStep = viewModel::changeAnalysisBuilderStep,
            onApplyFilter = {
                if (viewModel.applyAnalysisFilter()) {
                    viewModel.commitCurrentFormChanges()
                    viewModel.requestRootBack()
                    onNavigationChanged()
                }
            },
            onExport = {
                if (viewModel.prepareAnalysisExport()) onNavigationChanged()
            },
            onLoadMore = viewModel::loadNextAnalysisDrilldown,
            onOpenTransaction = { transactionId ->
                viewModel.openAnalysisTransaction(transactionId)
                onNavigationChanged()
            },
            onRunIntegrity = viewModel::runAnalysisIntegrity,
            onRepairProjection = viewModel::repairAnalysisProjection,
            onToggleTechnicalDetails = viewModel::toggleAnalysisTechnicalDetails,
            onNavigateP26 = { target, id, key ->
                viewModel.navigateAnalysisP26(target, id, key)
                onNavigationChanged()
            },
            onDraftNameChanged = viewModel::updateAnalysisDraftName,
            onSaveReport = viewModel::saveCustomAnalysisReport,
            onPreviewReport = viewModel::previewCustomAnalysisReport,
            onCopyReport = viewModel::copyCustomAnalysisReport,
            onSelectVisualization = { visualization ->
                viewModel.completeAnalysisVisualizationSelection(visualization)
                onNavigationChanged()
            },
            onSaveDashboard = viewModel::saveAnalysisDashboard,
            onToggleDashboardReport = viewModel::toggleAnalysisDashboardReport,
            onMoveDashboardReport = viewModel::moveAnalysisDashboardReport,
            onToggleDashboardWidth = viewModel::toggleAnalysisDashboardWidth,
            onSaveAnomalyRule = viewModel::saveAnalysisAnomalyRule,
            onEditAnomalyRule = viewModel::editAnalysisAnomalyRule,
            onCycleAnomalyType = viewModel::cycleAnalysisAnomalyType,
            onSelectAnomalyType = viewModel::selectAnalysisAnomalyType,
            onAnomalyThresholdChanged = viewModel::updateAnalysisAnomalyThreshold,
            onAnomalyLookbackChanged = viewModel::updateAnalysisAnomalyLookback,
            onSelectExportFormat = viewModel::selectAnalysisExportFormat,
            onSelectExportScope = viewModel::selectAnalysisExportScope,
            onPrepareExport = {
                if (viewModel.navigatePreparedReportExport()) onNavigationChanged()
            },
            onCycleMapMode = viewModel::cycleConsumptionMapMode,
            onCycleMapWeight = viewModel::cycleConsumptionMapWeight,
            onCycleMapAggregation = viewModel::cycleConsumptionMapAggregation,
            onCycleMapPresentation = viewModel::cycleConsumptionMapPresentation,
            onToggleMapSpecialTransactions = viewModel::toggleConsumptionMapSpecialTransactions,
            onResetMapFilters = viewModel::resetConsumptionMapFilters,
            onCycleMapAccountFilter = { viewModel.cycleConsumptionMapAccountFilter() },
            onCycleMapCategoryFilter = { viewModel.cycleConsumptionMapCategoryFilter() },
            onCycleMapMerchantFilter = { viewModel.cycleConsumptionMapMerchantFilter() },
            onCycleMapPlaceFilter = { viewModel.cycleConsumptionMapPlaceFilter() },
            onCycleMapProjectFilter = { viewModel.cycleConsumptionMapProjectFilter() },
            onCycleMapAmountFilter = { viewModel.cycleConsumptionMapAmountFilter() },
            onSelectMapMode = viewModel::selectConsumptionMapMode,
            onSelectMapWeight = viewModel::selectConsumptionMapWeight,
            onSelectMapAggregation = viewModel::selectConsumptionMapAggregation,
            onSelectMapPresentation = viewModel::selectConsumptionMapPresentation,
            onSelectMapFilter = viewModel::selectConsumptionMapFilter,
            onSelectMapAmountFilter = viewModel::selectConsumptionMapAmountFilter,
            onRemoveMapFilter = viewModel::removeConsumptionMapFilter,
            onMapViewportChanged = viewModel::updateConsumptionMapViewport,
            onSelectMapPoint = { pointId ->
                viewModel.navigateConsumptionMapDetail(pointId)
                onNavigationChanged()
            },
            onOpenMapTransactions = { drilldown ->
                viewModel.navigateAnalysis("ANA-005", null, drilldown)
                onNavigationChanged()
            },
        ),
        mapContent = { result, unavailable -> ConsumptionMapHost(result, unavailable, viewModel, onNavigationChanged) },
    )
    }
}

@Composable
private fun ConsumptionMapHost(
    result: app.ledger.analytics.domain.ConsumptionMapResult,
    unavailable: Boolean,
    viewModel: AppRootViewModel,
    onNavigationChanged: () -> Unit,
) {
    val locale = LocalLocale.current.platformLocale
    val summary = stringResource(
        AnalysisR.string.analysis_map_point_summary,
        AnalysisPolicy.money(result.viewportBaseAmountMinor, result.baseCurrency, locale).formatted,
        result.viewportTransactionCount,
    )
    val rows = result.points.map { point ->
        LedgerMapAccessibleRow(
            point.label ?: stringResource(AnalysisR.string.analysis_map_recorded_location),
            stringResource(
                AnalysisR.string.analysis_map_point_summary,
                AnalysisPolicy.money(point.baseAmountMinor, result.baseCurrency, locale).formatted,
                point.transactionCount,
            ),
        )
    }
    val state = if (unavailable) {
        LedgerMapState.Unavailable(summary, rows)
    } else {
        LedgerMapState.Available(
            summary,
            when (result.query.presentation) {
                app.ledger.analytics.domain.ConsumptionMapPresentation.CLUSTERS -> LedgerMapMode.CLUSTERS
                app.ledger.analytics.domain.ConsumptionMapPresentation.HEATMAP -> LedgerMapMode.HEATMAP
                app.ledger.analytics.domain.ConsumptionMapPresentation.SINGLE_POINTS -> LedgerMapMode.SINGLE_POINTS
            },
            result.points.map { point ->
                LedgerMapPoint(point.id, point.latitudeE7, point.longitudeE7, point.renderWeight(result.query.weight))
            },
            rows,
        )
    }
    LedgerMap(
        state,
        LedgerMapStyleConfiguration.OpenFreeMap,
        stringResource(AnalysisR.string.analysis_map_list_alternative),
        listOf(stringResource(AnalysisR.string.analysis_map_group_place), stringResource(AnalysisR.string.analysis_transaction_count)),
        stringResource(app.ledger.app.R.string.global_show_place_list),
        stringResource(app.ledger.app.R.string.global_hide_place_list),
        onFailure = { viewModel.markConsumptionMapUnavailable() },
        onViewportChanged = { viewport ->
            viewModel.updateConsumptionMapViewport(
                app.ledger.analytics.domain.MapViewport(
                    viewport.minimumLatitudeE7,
                    viewport.maximumLatitudeE7,
                    viewport.minimumLongitudeE7,
                    viewport.maximumLongitudeE7,
                    viewport.zoomBucket,
                ),
            )
        },
        onPointSelected = { id ->
            viewModel.navigateConsumptionMapDetail(id)
            onNavigationChanged()
        },
    )
}
