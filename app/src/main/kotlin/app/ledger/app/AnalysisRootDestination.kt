@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package app.ledger.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.feature.analysis.AnalysisActions
import app.ledger.feature.analysis.AnalysisDestination
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
            onApplyFilter = {
                if (viewModel.applyAnalysisFilter()) {
                    viewModel.requestRootBack()
                    onNavigationChanged()
                }
            },
            onExport = {
                if (viewModel.prepareAnalysisExport()) onNavigationChanged()
            },
            onLoadMore = viewModel::loadNextAnalysisDrilldown,
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
            onSelectVisualization = viewModel::selectAnalysisVisualization,
            onSaveDashboard = viewModel::saveAnalysisDashboard,
            onToggleDashboardReport = viewModel::toggleAnalysisDashboardReport,
            onMoveDashboardReport = viewModel::moveAnalysisDashboardReport,
            onToggleDashboardWidth = viewModel::toggleAnalysisDashboardWidth,
            onSaveAnomalyRule = viewModel::saveAnalysisAnomalyRule,
            onEditAnomalyRule = viewModel::editAnalysisAnomalyRule,
            onCycleAnomalyType = viewModel::cycleAnalysisAnomalyType,
            onAnomalyThresholdChanged = viewModel::updateAnalysisAnomalyThreshold,
            onAnomalyLookbackChanged = viewModel::updateAnalysisAnomalyLookback,
            onSelectExportFormat = viewModel::selectAnalysisExportFormat,
            onPrepareExport = { viewModel.prepareCurrentAnalysisExport() },
        ),
    )
}
