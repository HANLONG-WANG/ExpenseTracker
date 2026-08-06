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
    val state by viewModel.analysis.collectAsStateWithLifecycle()
    LaunchedEffect(screenId, reportKey, queryId) {
        viewModel.loadAnalysis(screenId, reportKey, queryId)
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
            onExport = { viewModel.prepareAnalysisExport() },
            onLoadMore = viewModel::loadNextAnalysisDrilldown,
            onRunIntegrity = viewModel::runAnalysisIntegrity,
            onRepairProjection = viewModel::repairAnalysisProjection,
            onToggleTechnicalDetails = viewModel::toggleAnalysisTechnicalDetails,
        ),
    )
}
