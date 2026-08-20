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
import app.ledger.feature.analysis.AnalysisPolicy
import app.ledger.feature.analysis.AnalysisScreenAction
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
    AnalysisDestination(
        screenId,
        state,
        { action ->
            when (action) {
                is AnalysisScreenAction.Navigate -> {
                    viewModel.navigateAnalysis(action.screenId, action.report, action.queryId)
                    onNavigationChanged()
                }
                AnalysisScreenAction.Retry -> viewModel.retryAnalysis()
                AnalysisScreenAction.PreviousPeriod -> viewModel.previousAnalysisPeriod()
                AnalysisScreenAction.NextPeriod -> viewModel.nextAnalysisPeriod()
                AnalysisScreenAction.CycleMeasure -> viewModel.cycleAnalysisMeasure()
                AnalysisScreenAction.CycleDimension -> viewModel.cycleAnalysisDimension()
                AnalysisScreenAction.CycleGranularity -> viewModel.cycleAnalysisGranularity()
                AnalysisScreenAction.CycleComparison -> viewModel.cycleAnalysisComparison()
                AnalysisScreenAction.ApplyFilter -> {
                    if (viewModel.applyAnalysisFilter()) {
                        viewModel.requestRootBack()
                        onNavigationChanged()
                    }
                }
                AnalysisScreenAction.Export -> {
                    if (viewModel.prepareAnalysisExport()) onNavigationChanged()
                }
                AnalysisScreenAction.LoadMore -> viewModel.loadNextAnalysisDrilldown()
                AnalysisScreenAction.RunIntegrity -> viewModel.runAnalysisIntegrity()
                AnalysisScreenAction.RepairProjection -> viewModel.repairAnalysisProjection()
                AnalysisScreenAction.ToggleTechnicalDetails -> viewModel.toggleAnalysisTechnicalDetails()
                is AnalysisScreenAction.NavigateP26 -> {
                    viewModel.navigateAnalysisP26(action.screenId, action.id, action.forecastKey)
                    onNavigationChanged()
                }
                is AnalysisScreenAction.DraftNameChanged -> viewModel.updateAnalysisDraftName(action.value)
                AnalysisScreenAction.SaveReport -> viewModel.saveCustomAnalysisReport()
                AnalysisScreenAction.PreviewReport -> viewModel.previewCustomAnalysisReport()
                is AnalysisScreenAction.CopyReport -> viewModel.copyCustomAnalysisReport(action.reportId)
                is AnalysisScreenAction.SelectVisualization -> viewModel.selectAnalysisVisualization(action.visualization)
                AnalysisScreenAction.SaveDashboard -> viewModel.saveAnalysisDashboard()
                is AnalysisScreenAction.ToggleDashboardReport -> viewModel.toggleAnalysisDashboardReport(action.reportId)
                is AnalysisScreenAction.MoveDashboardReport -> viewModel.moveAnalysisDashboardReport(action.reportId, action.offset)
                is AnalysisScreenAction.ToggleDashboardWidth -> viewModel.toggleAnalysisDashboardWidth(action.reportId)
                is AnalysisScreenAction.SaveAnomalyRule -> viewModel.saveAnalysisAnomalyRule(action.ruleId)
                is AnalysisScreenAction.EditAnomalyRule -> viewModel.editAnalysisAnomalyRule(action.ruleId)
                AnalysisScreenAction.CycleAnomalyType -> viewModel.cycleAnalysisAnomalyType()
                is AnalysisScreenAction.AnomalyThresholdChanged -> viewModel.updateAnalysisAnomalyThreshold(action.value)
                is AnalysisScreenAction.AnomalyLookbackChanged -> viewModel.updateAnalysisAnomalyLookback(action.value)
                is AnalysisScreenAction.SelectExportFormat -> viewModel.selectAnalysisExportFormat(action.format)
                AnalysisScreenAction.PrepareExport -> {
                    if (viewModel.navigatePreparedReportExport()) onNavigationChanged()
                }
                AnalysisScreenAction.CycleMapMode -> viewModel.cycleConsumptionMapMode()
                AnalysisScreenAction.CycleMapWeight -> viewModel.cycleConsumptionMapWeight()
                AnalysisScreenAction.CycleMapAggregation -> viewModel.cycleConsumptionMapAggregation()
                AnalysisScreenAction.CycleMapPresentation -> viewModel.cycleConsumptionMapPresentation()
                AnalysisScreenAction.ToggleMapSpecialTransactions -> viewModel.toggleConsumptionMapSpecialTransactions()
                AnalysisScreenAction.ResetMapFilters -> viewModel.resetConsumptionMapFilters()
                AnalysisScreenAction.CycleMapAccountFilter -> viewModel.cycleConsumptionMapAccountFilter()
                AnalysisScreenAction.CycleMapCategoryFilter -> viewModel.cycleConsumptionMapCategoryFilter()
                AnalysisScreenAction.CycleMapMerchantFilter -> viewModel.cycleConsumptionMapMerchantFilter()
                AnalysisScreenAction.CycleMapPlaceFilter -> viewModel.cycleConsumptionMapPlaceFilter()
                AnalysisScreenAction.CycleMapProjectFilter -> viewModel.cycleConsumptionMapProjectFilter()
                AnalysisScreenAction.CycleMapAmountFilter -> viewModel.cycleConsumptionMapAmountFilter()
                is AnalysisScreenAction.RemoveMapFilter -> viewModel.removeConsumptionMapFilter(action.key)
                is AnalysisScreenAction.MapViewportChanged -> viewModel.updateConsumptionMapViewport(action.viewport)
                is AnalysisScreenAction.SelectMapPoint -> {
                    viewModel.navigateConsumptionMapDetail(action.pointId)
                    onNavigationChanged()
                }
                is AnalysisScreenAction.OpenMapTransactions -> {
                    viewModel.navigateAnalysis("ANA-005", null, action.queryId)
                    onNavigationChanged()
                }
            }
        },
        mapContent = { result, unavailable -> ConsumptionMapHost(result, unavailable, viewModel, onNavigationChanged) },
    )
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
