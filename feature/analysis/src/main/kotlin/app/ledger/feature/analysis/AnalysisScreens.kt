@file:Suppress(
    "FunctionNaming",
    "LongMethod",
    "MagicNumber",
    "MatchingDeclarationName",
    "MaxLineLength",
    "ReturnCount",
    "TooManyFunctions",
    "ktlint:standard:function-naming",
)

package app.ledger.feature.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.ledger.analytics.domain.AnomalyRuleId
import app.ledger.analytics.domain.ComparisonMode
import app.ledger.analytics.domain.ConsumptionMapResult
import app.ledger.analytics.domain.DashboardId
import app.ledger.analytics.domain.Dimension
import app.ledger.analytics.domain.DrilldownQueryId
import app.ledger.analytics.domain.FixedReport
import app.ledger.analytics.domain.FixedReportGroup
import app.ledger.analytics.domain.ForecastKey
import app.ledger.analytics.domain.IntegrityCheckKey
import app.ledger.analytics.domain.IntegritySeverity
import app.ledger.analytics.domain.MapViewport
import app.ledger.analytics.domain.Measure
import app.ledger.analytics.domain.ReportDefinitionId
import app.ledger.analytics.domain.ReportExecution
import app.ledger.analytics.domain.ReportExportFormat
import app.ledger.analytics.domain.ReportSpec
import app.ledger.analytics.domain.ReportVisualization
import app.ledger.analytics.domain.TimeGranularity
import app.ledger.core.common.StableId
import app.ledger.core.designsystem.AccessibleTableUiModel
import app.ledger.core.designsystem.ChartCard
import app.ledger.core.designsystem.FilterChipUiModel
import app.ledger.core.designsystem.FilterDimensionUiModel
import app.ledger.core.designsystem.FormSection
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerButton
import app.ledger.core.designsystem.LedgerButtonVariant
import app.ledger.core.designsystem.LedgerCard
import app.ledger.core.designsystem.LedgerChartUiModel
import app.ledger.core.designsystem.LedgerColumnChart
import app.ledger.core.designsystem.LedgerEmptyState
import app.ledger.core.designsystem.LedgerErrorState
import app.ledger.core.designsystem.JournalTransactionRow
import app.ledger.core.designsystem.JournalTransactionUiModel
import app.ledger.core.designsystem.LedgerIcon
import app.ledger.core.designsystem.LedgerLineChart
import app.ledger.core.designsystem.LedgerLoadingState
import app.ledger.core.designsystem.LedgerPieChart
import app.ledger.core.designsystem.LedgerProgressIndicator
import app.ledger.core.designsystem.LedgerScaffold
import app.ledger.core.designsystem.LedgerStackedChart
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.LedgerVicoColumnRenderer
import app.ledger.core.designsystem.LedgerVicoLineRenderer
import app.ledger.core.designsystem.LedgerVicoPieRenderer
import app.ledger.core.designsystem.LedgerVicoStackedRenderer
import app.ledger.core.designsystem.MetricCard
import app.ledger.core.designsystem.MetricCardVariant
import app.ledger.core.designsystem.SelectorField
import app.ledger.core.designsystem.StatusBadge
import app.ledger.core.designsystem.UiErrorCode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

sealed interface AnalysisScreenAction {
    data class Navigate(val screenId: String, val report: FixedReport?, val queryId: DrilldownQueryId?) : AnalysisScreenAction
    data object Retry : AnalysisScreenAction
    data object PreviousPeriod : AnalysisScreenAction
    data object NextPeriod : AnalysisScreenAction
    data object CycleMeasure : AnalysisScreenAction
    data object CycleDimension : AnalysisScreenAction
    data object CycleGranularity : AnalysisScreenAction
    data object CycleComparison : AnalysisScreenAction
    data object ApplyFilter : AnalysisScreenAction
    data object Export : AnalysisScreenAction
    data object LoadMore : AnalysisScreenAction
    data object RunIntegrity : AnalysisScreenAction
    data object RepairProjection : AnalysisScreenAction
    data object ToggleTechnicalDetails : AnalysisScreenAction
    data class NavigateP26(val screenId: String, val id: StableId?, val forecastKey: ForecastKey?) : AnalysisScreenAction
    data class DraftNameChanged(val value: String) : AnalysisScreenAction
    data object SaveReport : AnalysisScreenAction
    data object PreviewReport : AnalysisScreenAction
    data class CopyReport(val reportId: ReportDefinitionId) : AnalysisScreenAction
    data class SelectVisualization(val visualization: ReportVisualization) : AnalysisScreenAction
    data object SaveDashboard : AnalysisScreenAction
    data class ToggleDashboardReport(val reportId: ReportDefinitionId) : AnalysisScreenAction
    data class MoveDashboardReport(val reportId: ReportDefinitionId, val offset: Int) : AnalysisScreenAction
    data class ToggleDashboardWidth(val reportId: ReportDefinitionId) : AnalysisScreenAction
    data class SaveAnomalyRule(val ruleId: AnomalyRuleId?) : AnalysisScreenAction
    data class EditAnomalyRule(val ruleId: AnomalyRuleId?) : AnalysisScreenAction
    data object CycleAnomalyType : AnalysisScreenAction
    data class AnomalyThresholdChanged(val value: String) : AnalysisScreenAction
    data class AnomalyLookbackChanged(val value: String) : AnalysisScreenAction
    data class SelectExportFormat(val format: ReportExportFormat) : AnalysisScreenAction
    data object PrepareExport : AnalysisScreenAction
    data object CycleMapMode : AnalysisScreenAction
    data object CycleMapWeight : AnalysisScreenAction
    data object CycleMapAggregation : AnalysisScreenAction
    data object CycleMapPresentation : AnalysisScreenAction
    data object ToggleMapSpecialTransactions : AnalysisScreenAction
    data object ResetMapFilters : AnalysisScreenAction
    data object CycleMapAccountFilter : AnalysisScreenAction
    data object CycleMapCategoryFilter : AnalysisScreenAction
    data object CycleMapMerchantFilter : AnalysisScreenAction
    data object CycleMapPlaceFilter : AnalysisScreenAction
    data object CycleMapProjectFilter : AnalysisScreenAction
    data object CycleMapAmountFilter : AnalysisScreenAction
    data class RemoveMapFilter(val key: String) : AnalysisScreenAction
    data class MapViewportChanged(val viewport: MapViewport) : AnalysisScreenAction
    data class SelectMapPoint(val pointId: StableId) : AnalysisScreenAction
    data class OpenMapTransactions(val queryId: DrilldownQueryId) : AnalysisScreenAction
}

internal class AnalysisActions(
    val onNavigate: (screenId: String, report: FixedReport?, queryId: DrilldownQueryId?) -> Unit,
    val onRetry: () -> Unit,
    val onPreviousPeriod: () -> Unit,
    val onNextPeriod: () -> Unit,
    val onCycleMeasure: () -> Unit,
    val onCycleDimension: () -> Unit,
    val onCycleGranularity: () -> Unit,
    val onCycleComparison: () -> Unit,
    val onSelectMeasure: (Measure) -> Unit = {},
    val onSelectDimension: (Dimension) -> Unit = {},
    val onSelectGranularity: (TimeGranularity) -> Unit = {},
    val onSelectComparison: (ComparisonMode?) -> Unit = {},
    val onCycleSort: (String) -> Unit = {},
    val onToggleReportFilter: (AnalysisEntityFilter, StableId) -> Unit = { _, _ -> },
    val onRemoveReportFilter: (String) -> Unit = {},
    val onResetReportFilters: () -> Unit = {},
    val onBuilderStep: (Int) -> Unit = {},
    val onApplyFilter: () -> Unit,
    val onExport: () -> Unit,
    val onLoadMore: () -> Unit,
    val onOpenTransaction: (StableId) -> Unit = {},
    val onRunIntegrity: () -> Unit,
    val onRepairProjection: () -> Unit,
    val onToggleTechnicalDetails: () -> Unit,
    val onNavigateP26: (screenId: String, id: StableId?, forecastKey: ForecastKey?) -> Unit = { _, _, _ -> },
    val onDraftNameChanged: (String) -> Unit = {},
    val onSaveReport: () -> Unit = {},
    val onPreviewReport: () -> Unit = {},
    val onCopyReport: (ReportDefinitionId) -> Unit = {},
    val onSelectVisualization: (ReportVisualization) -> Unit = {},
    val onSaveDashboard: () -> Unit = {},
    val onToggleDashboardReport: (ReportDefinitionId) -> Unit = {},
    val onMoveDashboardReport: (ReportDefinitionId, Int) -> Unit = { _, _ -> },
    val onToggleDashboardWidth: (ReportDefinitionId) -> Unit = {},
    val onSaveAnomalyRule: (AnomalyRuleId?) -> Unit = {},
    val onEditAnomalyRule: (AnomalyRuleId?) -> Unit = {},
    val onCycleAnomalyType: () -> Unit = {},
    val onAnomalyThresholdChanged: (String) -> Unit = {},
    val onAnomalyLookbackChanged: (String) -> Unit = {},
    val onSelectExportFormat: (ReportExportFormat) -> Unit = {},
    val onSelectExportScope: (AnalysisExportScope) -> Unit = {},
    val onPrepareExport: () -> Unit = {},
    val onCycleMapMode: () -> Unit = {},
    val onCycleMapWeight: () -> Unit = {},
    val onCycleMapAggregation: () -> Unit = {},
    val onCycleMapPresentation: () -> Unit = {},
    val onToggleMapSpecialTransactions: () -> Unit = {},
    val onResetMapFilters: () -> Unit = {},
    val onCycleMapAccountFilter: () -> Unit = {},
    val onCycleMapCategoryFilter: () -> Unit = {},
    val onCycleMapMerchantFilter: () -> Unit = {},
    val onCycleMapPlaceFilter: () -> Unit = {},
    val onCycleMapProjectFilter: () -> Unit = {},
    val onCycleMapAmountFilter: () -> Unit = {},
    val onRemoveMapFilter: (String) -> Unit = {},
    val onMapViewportChanged: (MapViewport) -> Unit = {},
    val onSelectMapPoint: (app.ledger.core.common.StableId) -> Unit = {},
    val onOpenMapTransactions: (app.ledger.analytics.domain.DrilldownQueryId) -> Unit = {},
)

internal fun analysisActions(onAction: (AnalysisScreenAction) -> Unit): AnalysisActions = AnalysisActions(
    onNavigate = { screenId, report, query -> onAction(AnalysisScreenAction.Navigate(screenId, report, query)) },
    onRetry = { onAction(AnalysisScreenAction.Retry) },
    onPreviousPeriod = { onAction(AnalysisScreenAction.PreviousPeriod) },
    onNextPeriod = { onAction(AnalysisScreenAction.NextPeriod) },
    onCycleMeasure = { onAction(AnalysisScreenAction.CycleMeasure) },
    onCycleDimension = { onAction(AnalysisScreenAction.CycleDimension) },
    onCycleGranularity = { onAction(AnalysisScreenAction.CycleGranularity) },
    onCycleComparison = { onAction(AnalysisScreenAction.CycleComparison) },
    onApplyFilter = { onAction(AnalysisScreenAction.ApplyFilter) },
    onExport = { onAction(AnalysisScreenAction.Export) },
    onLoadMore = { onAction(AnalysisScreenAction.LoadMore) },
    onRunIntegrity = { onAction(AnalysisScreenAction.RunIntegrity) },
    onRepairProjection = { onAction(AnalysisScreenAction.RepairProjection) },
    onToggleTechnicalDetails = { onAction(AnalysisScreenAction.ToggleTechnicalDetails) },
    onNavigateP26 = { screenId, id, key -> onAction(AnalysisScreenAction.NavigateP26(screenId, id, key)) },
    onDraftNameChanged = { onAction(AnalysisScreenAction.DraftNameChanged(it)) },
    onSaveReport = { onAction(AnalysisScreenAction.SaveReport) },
    onPreviewReport = { onAction(AnalysisScreenAction.PreviewReport) },
    onCopyReport = { onAction(AnalysisScreenAction.CopyReport(it)) },
    onSelectVisualization = { onAction(AnalysisScreenAction.SelectVisualization(it)) },
    onSaveDashboard = { onAction(AnalysisScreenAction.SaveDashboard) },
    onToggleDashboardReport = { onAction(AnalysisScreenAction.ToggleDashboardReport(it)) },
    onMoveDashboardReport = { id, offset -> onAction(AnalysisScreenAction.MoveDashboardReport(id, offset)) },
    onToggleDashboardWidth = { onAction(AnalysisScreenAction.ToggleDashboardWidth(it)) },
    onSaveAnomalyRule = { onAction(AnalysisScreenAction.SaveAnomalyRule(it)) },
    onEditAnomalyRule = { onAction(AnalysisScreenAction.EditAnomalyRule(it)) },
    onCycleAnomalyType = { onAction(AnalysisScreenAction.CycleAnomalyType) },
    onAnomalyThresholdChanged = { onAction(AnalysisScreenAction.AnomalyThresholdChanged(it)) },
    onAnomalyLookbackChanged = { onAction(AnalysisScreenAction.AnomalyLookbackChanged(it)) },
    onSelectExportFormat = { onAction(AnalysisScreenAction.SelectExportFormat(it)) },
    onPrepareExport = { onAction(AnalysisScreenAction.PrepareExport) },
    onCycleMapMode = { onAction(AnalysisScreenAction.CycleMapMode) },
    onCycleMapWeight = { onAction(AnalysisScreenAction.CycleMapWeight) },
    onCycleMapAggregation = { onAction(AnalysisScreenAction.CycleMapAggregation) },
    onCycleMapPresentation = { onAction(AnalysisScreenAction.CycleMapPresentation) },
    onToggleMapSpecialTransactions = { onAction(AnalysisScreenAction.ToggleMapSpecialTransactions) },
    onResetMapFilters = { onAction(AnalysisScreenAction.ResetMapFilters) },
    onCycleMapAccountFilter = { onAction(AnalysisScreenAction.CycleMapAccountFilter) },
    onCycleMapCategoryFilter = { onAction(AnalysisScreenAction.CycleMapCategoryFilter) },
    onCycleMapMerchantFilter = { onAction(AnalysisScreenAction.CycleMapMerchantFilter) },
    onCycleMapPlaceFilter = { onAction(AnalysisScreenAction.CycleMapPlaceFilter) },
    onCycleMapProjectFilter = { onAction(AnalysisScreenAction.CycleMapProjectFilter) },
    onCycleMapAmountFilter = { onAction(AnalysisScreenAction.CycleMapAmountFilter) },
    onRemoveMapFilter = { onAction(AnalysisScreenAction.RemoveMapFilter(it)) },
    onMapViewportChanged = { onAction(AnalysisScreenAction.MapViewportChanged(it)) },
    onSelectMapPoint = { onAction(AnalysisScreenAction.SelectMapPoint(it)) },
    onOpenMapTransactions = { onAction(AnalysisScreenAction.OpenMapTransactions(it)) },
)

@Composable
fun AnalysisDestination(
    screenId: String,
    state: AnalysisLoadState,
    onAction: (AnalysisScreenAction) -> Unit,
    mapContent: @Composable (ConsumptionMapResult, Boolean) -> Unit = { _, _ -> },
) {
    if (state is AnalysisLoadState.Loading) {
        LedgerLoadingState(
            Modifier.fillMaxSize().analysisRootTag(screenId),
            stringResource(R.string.analysis_loading),
        )
        return
    }
    if (state is AnalysisLoadState.Failure) {
        LedgerErrorState(
            UiErrorCode(state.code),
            stringResource(R.string.analysis_query_failed),
            actions.onRetry,
            Modifier.fillMaxSize().analysisRootTag(screenId),
        )
        return
    }
    val content = (state as AnalysisLoadState.Content).state
    when (screenId) {
        "ANA-001" -> AnalysisHome(content, actions)
        "ANA-002" -> ReportCatalog(content, actions)
        "ANA-003" -> ReportDetail(content, actions)
        "ANA-004" -> ReportFilter(content, actions)
        "ANA-005" -> ReportDrilldown(content, actions)
        "ANA-006" -> DashboardListScreen(content, actions)
        "ANA-007" -> DashboardEditorScreen(content, actions)
        "ANA-008" -> ReportBuilderScreen(content, actions)
        "ANA-009" -> VisualizationPickerScreen(content, actions)
        "ANA-010" -> ReportExportScreen(content, actions)
        "ANA-011" -> ConsumptionMapScreen(content, actions, mapContent)
        "ANA-012" -> ConsumptionMapDetailScreen(content, actions)
        "ANA-013" -> AnomalyRulesScreen(content, actions)
        "ANA-014" -> ForecastDetailScreen(content, actions)
        "ANA-015" -> IntegrityReport(content, actions)
        else -> LedgerErrorState(UiErrorCode("ANALYSIS_SCREEN_UNKNOWN"), stringResource(R.string.analysis_query_failed), actions.onRetry)
    }
}

@Composable
private fun AnalysisHome(state: AnalysisFeatureState, actions: AnalysisActions) {
    val overview = state.overview
    if (state.presentation == AnalysisPresentation.CALCULATING && overview == null) {
        LazyColumn(
            Modifier.fillMaxSize().testTag(LedgerTestTags.ANALYSIS_HOME),
            contentPadding = PaddingValues(LedgerTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.md),
        ) {
            item { PeriodControls(state, actions) }
            item { LedgerLoadingState(Modifier.fillMaxWidth(), stringResource(R.string.analysis_calculating)) }
        }
        return
    }
    if (state.presentation == AnalysisPresentation.ERROR || overview == null) {
        LedgerErrorState(
            UiErrorCode(state.failureCode ?: "ANALYSIS_HOME_FAILED"),
            stringResource(R.string.analysis_query_failed),
            actions.onRetry,
            Modifier.fillMaxSize().testTag(LedgerTestTags.ANALYSIS_HOME),
        )
        return
    }
    if (state.presentation == AnalysisPresentation.NO_DATA) {
        LedgerEmptyState(
            stringResource(R.string.analysis_no_data_title),
            stringResource(R.string.analysis_no_data_body),
            stringResource(R.string.analysis_all_reports),
            { actions.onNavigate("ANA-002", null, null) },
            Modifier.fillMaxSize().testTag(LedgerTestTags.ANALYSIS_HOME),
        )
        return
    }
    val locale = LocalLocale.current.platformLocale
    LazyColumn(
        Modifier.fillMaxSize().testTag(LedgerTestTags.ANALYSIS_HOME),
        contentPadding = PaddingValues(LedgerTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.md),
    ) {
        item { PeriodControls(state, actions) }
        if (state.presentation == AnalysisPresentation.CALCULATING) {
            item { LedgerLoadingState(Modifier.fillMaxWidth(), stringResource(R.string.analysis_calculating)) }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
                MetricCard(stringResource(R.string.analysis_consumption), AnalysisPolicy.money(overview.consumptionMinor, overview.baseCurrency, locale), Modifier.fillMaxWidth(0.48f))
                MetricCard(stringResource(R.string.analysis_income), AnalysisPolicy.money(overview.incomeMinor, overview.baseCurrency, locale), Modifier.fillMaxWidth(0.48f))
                MetricCard(stringResource(R.string.analysis_net_surplus), AnalysisPolicy.money(overview.netSurplusMinor, overview.baseCurrency, locale), Modifier.fillMaxWidth(0.48f), MetricCardVariant.EMPHASIZED)
                LedgerCard(Modifier.fillMaxWidth(0.48f)) {
                    Column(Modifier.padding(LedgerTheme.spacing.sm)) {
                        LedgerText(stringResource(R.string.analysis_savings_rate), LedgerTextRole.SUPPORTING)
                        LedgerText(overview.savingsRate?.let(AnalysisPolicy::decimalText) ?: stringResource(R.string.analysis_not_available), LedgerTextRole.TITLE)
                    }
                }
            }
        }
        item {
            DashboardReportCard(
                state.catalog.first { it.report == FixedReport.INCOME_EXPENSE_NET },
                stringResource(R.string.analysis_summary_deterministic),
                actions,
            )
        }
        item {
            DashboardReportCard(
                state.catalog.first { it.report == FixedReport.CONSUMPTION_CATEGORY_STRUCTURE },
                stringResource(R.string.analysis_pie_fallback_summary),
                actions,
            )
        }
        item {
            LedgerBanner(stringResource(R.string.analysis_deterministic_method), LedgerBannerVariant.INFO)
        }
        item {
            LedgerButton(
                stringResource(R.string.analysis_all_reports),
                { actions.onNavigate("ANA-002", null, null) },
                Modifier.fillMaxWidth(),
                LedgerButtonVariant.SECONDARY,
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
                LedgerButton(stringResource(R.string.analysis_title_dashboards), { actions.onNavigateP26("ANA-006", null, null) }, Modifier.weight(1f), LedgerButtonVariant.SECONDARY)
                LedgerButton(stringResource(R.string.analysis_title_anomaly), { actions.onNavigateP26("ANA-013", null, null) }, Modifier.weight(1f), LedgerButtonVariant.SECONDARY)
            }
        }
        item {
            LedgerButton(
                stringResource(R.string.analysis_title_forecast),
                { actions.onNavigateP26("ANA-014", null, ForecastKey.MONTH_END_SPENDING) },
                Modifier.fillMaxWidth(),
                LedgerButtonVariant.SECONDARY,
            )
        }
    }
}

@Composable
private fun DashboardReportCard(definition: app.ledger.analytics.domain.FixedReportDefinition, summary: String, actions: AnalysisActions) {
    LedgerCard(Modifier.fillMaxWidth(), onClick = { actions.onNavigate("ANA-003", definition.report, null) }) {
        Column(Modifier.padding(LedgerTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            LedgerText(reportTitle(definition.report), LedgerTextRole.SECTION)
            LedgerText(summary, LedgerTextRole.BODY)
            LedgerText(stringResource(R.string.analysis_view_data_table), LedgerTextRole.SUPPORTING)
        }
    }
}

@Composable
private fun PeriodControls(state: AnalysisFeatureState, actions: AnalysisActions) {
    val locale = LocalLocale.current.platformLocale
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        LedgerButton(stringResource(R.string.analysis_previous_period), actions.onPreviousPeriod, variant = LedgerButtonVariant.TEXT)
        LedgerText("${state.period.start.localized(locale)} — ${state.period.endInclusive.localized(locale)}", LedgerTextRole.SECTION)
        LedgerButton(stringResource(R.string.analysis_next_period), actions.onNextPeriod, variant = LedgerButtonVariant.TEXT)
    }
}

@Composable
private fun ReportCatalog(state: AnalysisFeatureState, actions: AnalysisActions) {
    LazyColumn(
        Modifier.fillMaxSize().testTag(LedgerTestTags.REPORT_CATALOG),
        contentPadding = PaddingValues(LedgerTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
    ) {
        item {
            LedgerButton(
                stringResource(R.string.analysis_title_builder),
                { actions.onNavigateP26("ANA-008", null, null) },
                Modifier.fillMaxWidth(),
            )
        }
        FixedReportGroup.entries.forEach { group ->
            item(key = group.name) { LedgerText(reportGroupTitle(group), LedgerTextRole.SECTION) }
            items(state.catalog.filter { it.group == group }, key = { it.key.value }) { definition ->
                LedgerCard(
                    Modifier.fillMaxWidth(),
                    onClick = {
                        actions.onNavigate(
                            when (definition.report) {
                                FixedReport.DATA_INTEGRITY -> "ANA-015"
                                FixedReport.CONSUMPTION_MAP -> "ANA-011"
                                else -> "ANA-003"
                            },
                            definition.report,
                            null,
                        )
                    },
                ) {
                    Column(Modifier.padding(LedgerTheme.spacing.sm)) {
                        LedgerText(reportTitle(definition.report), LedgerTextRole.BODY)
                        LedgerText(visualizationLabel(definition.defaultVisualization), LedgerTextRole.SUPPORTING)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportDetail(state: AnalysisFeatureState, actions: AnalysisActions) {
    when (state.presentation) {
        AnalysisPresentation.LOADING -> {
            val execution = state.execution as? ReportExecution.Content
            if (execution == null) {
                LazyColumn(
                    Modifier.fillMaxSize().testTag(LedgerTestTags.REPORT_DETAIL),
                    contentPadding = PaddingValues(LedgerTheme.spacing.md),
                    verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.md),
                ) {
                    item { PeriodControls(state, actions) }
                    item { LedgerLoadingState(Modifier.fillMaxWidth(), stringResource(R.string.analysis_loading)) }
                }
            } else {
                ReportContent(state, execution, actions, loading = true)
            }
        }
        AnalysisPresentation.QUERY_ERROR -> LedgerErrorState(
            UiErrorCode(state.failureCode ?: "REPORT_QUERY_FAILED"),
            stringResource(R.string.analysis_query_failed),
            actions.onRetry,
            Modifier.fillMaxSize().testTag(LedgerTestTags.REPORT_DETAIL),
        )
        AnalysisPresentation.STALE_REBUILD_REQUIRED -> LedgerErrorState(
            UiErrorCode("STALE_REBUILD_REQUIRED"),
            stringResource(R.string.analysis_stale_projection),
            actions.onRepairProjection,
            Modifier.fillMaxSize().testTag(LedgerTestTags.REPORT_DETAIL),
        )
        AnalysisPresentation.EMPTY -> LedgerEmptyState(
            stringResource(R.string.analysis_report_empty),
            stringResource(R.string.analysis_report_empty_body),
            stringResource(R.string.analysis_retry),
            actions.onRetry,
            Modifier.fillMaxSize().testTag(LedgerTestTags.REPORT_DETAIL),
        )
        else -> {
            val execution = state.execution as? ReportExecution.Content
            if (execution == null) {
                LedgerLoadingState(
                    Modifier.fillMaxSize().testTag(LedgerTestTags.REPORT_DETAIL),
                    stringResource(R.string.analysis_loading),
                )
            } else {
                ReportContent(state, execution, actions)
            }
        }
    }
}

@Composable
private fun ReportContent(
    state: AnalysisFeatureState,
    execution: ReportExecution.Content,
    actions: AnalysisActions,
    loading: Boolean = false,
) {
    val locale = LocalLocale.current.platformLocale
    val table = reportTable(state, execution)
    val chartModel = LedgerChartUiModel(
        title = state.fixedReport?.let { reportTitle(it) } ?: stringResource(R.string.analysis_custom_report),
        scope = "${state.period.start.localized(locale)} — ${state.period.endInclusive.localized(locale)}",
        summary = stringResource(R.string.analysis_chart_summary, execution.rows.size),
        type = AnalysisPolicy.visualizationType(execution),
        series = localizedChartSeries(execution, locale, state.baseCurrency),
    )
    var tableExpanded by remember(chartModel.title) { mutableStateOf(true) }
    LazyColumn(
        Modifier.fillMaxSize().testTag(LedgerTestTags.REPORT_DETAIL),
        contentPadding = PaddingValues(LedgerTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.md),
    ) {
        item { PeriodControls(state, actions) }
        if (loading) item { LedgerLoadingState(Modifier.fillMaxWidth(), stringResource(R.string.analysis_loading)) }
        item { MetricSummary(state, execution) }
        item {
            LedgerText(stringResource(R.string.analysis_semantics_help), LedgerTextRole.SUPPORTING)
        }
        execution.comparison?.let { comparison ->
            item {
                LedgerBanner(
                    stringResource(
                        R.string.analysis_comparison_summary,
                        comparison.referencePeriod.start.localized(locale),
                        comparison.referencePeriod.endInclusive.localized(locale),
                    ),
                    LedgerBannerVariant.NEUTRAL,
                )
            }
        }
        if (execution.visualization.reason != null) {
            item {
                LedgerBanner(
                    if (execution.visualization.mergedOther) stringResource(R.string.analysis_pie_fallback) else stringResource(R.string.analysis_visualization_incompatible),
                    LedgerBannerVariant.INFO,
                    Modifier.testTag(LedgerTestTags.REPORT_VISUALIZATION_REASON),
                )
            }
        }
        item {
            ChartCard(
                chartModel,
                chart = { ReportChart(chartModel) },
                dataTable = table,
                tableExpanded = tableExpanded,
                onToggleTable = { tableExpanded = !tableExpanded },
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
                LedgerButton(stringResource(R.string.analysis_filter), { actions.onNavigate("ANA-004", state.fixedReport, null) }, Modifier.weight(1f), LedgerButtonVariant.SECONDARY)
                LedgerButton(stringResource(R.string.analysis_export), actions.onExport, Modifier.weight(1f), LedgerButtonVariant.SECONDARY)
            }
        }
        execution.rows.firstOrNull()?.drilldownQueryId?.let { queryId ->
            item {
                LedgerButton(stringResource(R.string.analysis_drilldown), { actions.onNavigate("ANA-005", state.fixedReport, queryId) }, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun MetricSummary(state: AnalysisFeatureState, execution: ReportExecution.Content) {
    val locale = LocalLocale.current.platformLocale
    val values = execution.rows.firstOrNull()?.measureValues.orEmpty()
    if (values.isEmpty()) return
    FormSection(stringResource(R.string.analysis_metric_summary)) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
        ) {
            values.forEach { value ->
                LedgerCard(Modifier.fillMaxWidth(if (values.size > 1) 0.48f else 1f)) {
                    Column(Modifier.padding(LedgerTheme.spacing.sm)) {
                        LedgerText(measureLabel(value.measure), LedgerTextRole.SUPPORTING)
                        LedgerText(
                            value.minorValue?.let { AnalysisPolicy.money(it, value.currency ?: state.baseCurrency, locale).formatted }
                                ?: value.decimalValue?.let(AnalysisPolicy::decimalText)
                                ?: stringResource(R.string.analysis_not_available),
                            LedgerTextRole.TITLE,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ReportChart(model: LedgerChartUiModel) {
    when (model.type) {
        app.ledger.core.designsystem.LedgerChartType.LINE -> LedgerLineChart(model, LedgerVicoLineRenderer, Modifier.fillMaxWidth())
        app.ledger.core.designsystem.LedgerChartType.COLUMN -> LedgerColumnChart(model, LedgerVicoColumnRenderer, Modifier.fillMaxWidth())
        app.ledger.core.designsystem.LedgerChartType.STACKED -> LedgerStackedChart(model, LedgerVicoStackedRenderer, Modifier.fillMaxWidth())
        app.ledger.core.designsystem.LedgerChartType.PIE -> LedgerPieChart(model, LedgerVicoPieRenderer, Modifier.fillMaxWidth())
        app.ledger.core.designsystem.LedgerChartType.PROGRESS -> LedgerProgressIndicator(0f, Modifier.fillMaxWidth(), model.summary)
        app.ledger.core.designsystem.LedgerChartType.TABLE -> LedgerText(stringResource(R.string.analysis_table_primary), LedgerTextRole.BODY)
    }
}

@Composable
internal fun localizedChartSeries(
    execution: ReportExecution.Content,
    locale: Locale,
    baseCurrency: app.ledger.core.money.CurrencyCode,
): List<app.ledger.core.designsystem.LedgerChartSeries> = AnalysisPolicy.chartSeries(execution, locale, baseCurrency).map { series ->
    val measureKey = series.stableSeriesKey.removeSuffix("_comparison").uppercase(Locale.ROOT)
    val measure = Measure.entries.singleOrNull { it.name == measureKey }
    series.copy(
        label = buildString {
            append(measure?.let { measureLabel(it) } ?: series.label)
            if (series.stableSeriesKey.endsWith("_comparison")) {
                append(" · ")
                append(comparisonLabel(execution.comparison?.mode))
            }
        },
    )
}

@Composable
private fun ReportFilter(state: AnalysisFeatureState, actions: AnalysisActions) {
    val spec = state.draftSpec
    if (spec == null) {
        LedgerErrorState(
            UiErrorCode("REPORT_FILTER_MISSING"),
            stringResource(R.string.analysis_filter_invalid),
            actions.onRetry,
            Modifier.fillMaxSize().testTag(LedgerTestTags.REPORT_FILTER),
        )
        return
    }
    val valid = AnalysisPolicy.reportSpecValid(spec) && state.presentation != AnalysisPresentation.INVALID
    LedgerScaffold(
        modifier = Modifier.fillMaxSize().testTag(LedgerTestTags.REPORT_FILTER),
        formContent = true,
        fixedAction = { AnalysisApplyBar(actions.onApplyFilter, valid) },
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(LedgerTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.md),
        ) {
            if (!valid) item { LedgerBanner(stringResource(R.string.analysis_filter_invalid), LedgerBannerVariant.DANGER) }
            item { LedgerBanner(stringResource(R.string.analysis_no_sql_formula), LedgerBannerVariant.INFO) }
            item { LedgerText(stringResource(R.string.analysis_measure), LedgerTextRole.SECTION) }
            items(Measure.entries, key = { "measure-${it.name}" }) { measure ->
                LedgerChoiceRow(measureLabel(measure), measure in spec.measures, { actions.onSelectMeasure(measure) })
            }
            item { LedgerText(stringResource(R.string.analysis_dimension), LedgerTextRole.SECTION) }
            items(Dimension.entries, key = { "dimension-${it.name}" }) { dimension ->
                LedgerChoiceRow(dimensionLabel(dimension), dimension in spec.dimensions, { actions.onSelectDimension(dimension) })
            }
            item { LedgerText(stringResource(R.string.analysis_granularity), LedgerTextRole.SECTION) }
            items(TimeGranularity.entries, key = { "granularity-${it.name}" }) { granularity ->
                LedgerChoiceRow(granularityLabel(granularity), spec.granularity == granularity, { actions.onSelectGranularity(granularity) })
            }
            item { LedgerText(stringResource(R.string.analysis_comparison), LedgerTextRole.SECTION) }
            item { LedgerChoiceRow(stringResource(R.string.analysis_none), spec.comparison == null, { actions.onSelectComparison(null) }) }
            items(ComparisonMode.entries, key = { "comparison-${it.name}" }) { comparison ->
                LedgerChoiceRow(comparisonLabel(comparison), spec.comparison == comparison, { actions.onSelectComparison(comparison) })
            }
            item { ReportFilterEditor(state, spec, actions, includeApply = false) }
        }
    }
}

@Composable
internal fun ReportFilterEditor(
    state: AnalysisFeatureState,
    spec: ReportSpec,
    actions: AnalysisActions,
    includeApply: Boolean,
) {
    val locale = LocalLocale.current.platformLocale
    val options = state.consumptionMapFilterOptions
    val dimensions = buildList {
        add(
            FilterDimensionUiModel(
                stringResource(R.string.analysis_period),
                listOf(FilterChipUiModel("period", "${state.period.start.localized(locale)} — ${state.period.endInclusive.localized(locale)}")),
            ),
        )
        AnalysisEntityFilter.entries.forEach { filter ->
            val selected = AnalysisPolicy.selectedFilterIds(spec, filter)
            if (selected.isNotEmpty()) {
                add(
                    FilterDimensionUiModel(
                        entityFilterLabel(filter),
                        selected.map { id -> FilterChipUiModel(filter.name.lowercase(Locale.ROOT), filterOptions(options, filter).singleOrNull { it.id == id }?.label ?: stringResource(R.string.analysis_filter_selected)) },
                    ),
                )
            }
        }
    }
    app.ledger.core.designsystem.FilterBuilder(
        dimensions = dimensions,
        naturalLanguageSummary = stringResource(R.string.analysis_filter_summary),
        onRemove = { chip -> if (chip.stableKey != "period") actions.onRemoveReportFilter(chip.stableKey) },
        onReset = actions.onResetReportFilters,
        onApply = actions.onApplyFilter,
        showActions = includeApply,
    )
    if (!includeApply) {
        LedgerButton(
            stringResource(R.string.analysis_reset_filters),
            actions.onResetReportFilters,
            Modifier.fillMaxWidth(),
            LedgerButtonVariant.SECONDARY,
        )
    }
    AnalysisEntityFilter.entries.forEach { filter ->
        val available = filterOptions(options, filter)
        val selected = AnalysisPolicy.selectedFilterIds(spec, filter)
        val selectedFallback = stringResource(R.string.analysis_filter_selected)
        val allLabel = stringResource(R.string.analysis_filter_all)
        val selectedText = selected.joinToString { id -> available.singleOrNull { it.id == id }?.label ?: selectedFallback }.ifBlank { allLabel }
        SelectorField(
            entityFilterLabel(filter),
            selectedText,
            {
                val current = selected.singleOrNull()
                val next = available.getOrNull((available.indexOfFirst { it.id == current } + 1).coerceAtLeast(0))
                if (current != null) actions.onToggleReportFilter(filter, current)
                if (next != null) actions.onToggleReportFilter(filter, next.id)
            },
        )
    }
}

@Composable
private fun AnalysisApplyBar(onApply: () -> Unit, enabled: Boolean) {
    LedgerCard(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm)) {
            LedgerButton(
                stringResource(R.string.analysis_apply_filters),
                onApply,
                Modifier.fillMaxWidth().testTag(LedgerTestTags.SAVE),
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun ReportDrilldown(state: AnalysisFeatureState, actions: AnalysisActions) {
    if (state.presentation == AnalysisPresentation.EXPIRED_QUERY) {
        LedgerErrorState(
            UiErrorCode("EXPIRED_QUERY"),
            stringResource(R.string.analysis_expired_query),
            actions.onRetry,
            Modifier.fillMaxSize().testTag(LedgerTestTags.REPORT_DRILLDOWN),
        )
        return
    }
    val page = state.drilldown
    if (state.presentation == AnalysisPresentation.EMPTY || page == null || page.rows.isEmpty()) {
        LedgerEmptyState(
            stringResource(R.string.analysis_drilldown_empty),
            stringResource(R.string.analysis_drilldown_empty_body),
            stringResource(R.string.analysis_retry),
            actions.onRetry,
            Modifier.fillMaxSize().testTag(LedgerTestTags.REPORT_DRILLDOWN),
        )
        return
    }
    val locale = LocalLocale.current.platformLocale
    PagedTransactionList(state, page, actions, locale)
}

@Composable
private fun PagedTransactionList(
    state: AnalysisFeatureState,
    page: app.ledger.analytics.domain.DrilldownPage,
    actions: AnalysisActions,
    locale: Locale,
) {
    LaunchedEffect(page.nextCursor) {
        if (page.nextCursor != null) actions.onLoadMore()
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag(LedgerTestTags.REPORT_DRILLDOWN),
        contentPadding = PaddingValues(LedgerTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
    ) {
        item {
            LedgerText(
                stringResource(R.string.analysis_query_summary, state.period.start.localized(locale), state.period.endInclusive.localized(locale)),
                LedgerTextRole.SUPPORTING,
            )
        }
        items(page.rows, key = { it.transactionId.toString() }) { row ->
            val type = transactionKindLabel(row.kindKey)
            val category = row.categoryLabel ?: type
            val account = listOfNotNull(row.accountLabel, row.cardLabel).joinToString(" · ").ifBlank { stringResource(R.string.analysis_account_unavailable) }
            val summary = listOfNotNull(row.merchantLabel, row.localDate.localized(locale)).joinToString(" · ")
            val amount = AnalysisPolicy.money(row.amountMinor, row.currency, locale)
            JournalTransactionRow(
                JournalTransactionUiModel(
                    stableKey = row.transactionId.toString(),
                    categoryOrType = category,
                    summary = summary,
                    accountAndCard = account,
                    amount = amount,
                    typeLabel = type,
                    icon = transactionKindIcon(row.kindKey),
                    badges = listOf(stringResource(R.string.analysis_badge_report_drilldown)),
                    accessibleText = listOf(type, category, summary, account, amount.fullAccessibleText).joinToString(". "),
                ),
                onClick = { actions.onOpenTransaction(row.transactionId) },
                onLongClick = null,
            )
        }
        if (page.nextCursor != null) {
            item { LedgerLoadingState(Modifier.fillMaxWidth(), stringResource(R.string.analysis_loading_more)) }
        }
    }
}

@Composable
private fun IntegrityReport(state: AnalysisFeatureState, actions: AnalysisActions) {
    if (state.presentation == AnalysisPresentation.NOT_RUN) {
        LedgerEmptyState(
            stringResource(R.string.analysis_integrity_not_run),
            stringResource(R.string.analysis_integrity_not_run_body),
            stringResource(R.string.analysis_run_integrity),
            actions.onRunIntegrity,
            Modifier.fillMaxSize().testTag(LedgerTestTags.INTEGRITY_REPORT),
        )
        return
    }
    if (state.presentation == AnalysisPresentation.RUNNING) {
        LedgerLoadingState(Modifier.fillMaxSize().testTag(LedgerTestTags.INTEGRITY_REPORT), stringResource(R.string.analysis_integrity_running))
        return
    }
    val report = state.integrity
    if (report == null) {
        LedgerErrorState(
            UiErrorCode("INTEGRITY_REPORT_FAILED"),
            stringResource(R.string.analysis_integrity_failed),
            actions.onRunIntegrity,
            Modifier.fillMaxSize().testTag(LedgerTestTags.INTEGRITY_REPORT),
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag(LedgerTestTags.INTEGRITY_REPORT),
        contentPadding = PaddingValues(LedgerTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.md),
    ) {
        item {
            val variant = when (report.severity) {
                IntegritySeverity.PASS -> app.ledger.core.designsystem.LedgerStatusVariant.POSITIVE
                IntegritySeverity.WARNING -> app.ledger.core.designsystem.LedgerStatusVariant.WARNING
                IntegritySeverity.FAILURE -> app.ledger.core.designsystem.LedgerStatusVariant.DANGER
            }
            StatusBadge(integritySeverityLabel(report.severity), variant)
        }
        item { LedgerText(stringResource(R.string.analysis_integrity_plain_language), LedgerTextRole.BODY) }
        items(report.checks, key = { it.key.name }) { check ->
            LedgerCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(LedgerTheme.spacing.sm)) {
                    LedgerText(integrityCheckLabel(check.key), LedgerTextRole.SECTION)
                    LedgerText(stringResource(R.string.analysis_affected_count, check.affectedCount), LedgerTextRole.SUPPORTING)
                    if (state.technicalDetailsExpanded) LedgerText(check.diagnosticCode, LedgerTextRole.SUPPORTING)
                }
            }
        }
        item {
            LedgerButton(
                if (state.technicalDetailsExpanded) stringResource(R.string.analysis_hide_technical) else stringResource(R.string.analysis_show_technical),
                actions.onToggleTechnicalDetails,
                Modifier.fillMaxWidth(),
                LedgerButtonVariant.TEXT,
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
                LedgerButton(stringResource(R.string.analysis_run_integrity), actions.onRunIntegrity, Modifier.weight(1f), LedgerButtonVariant.SECONDARY)
                LedgerButton(stringResource(R.string.analysis_repair_projection), actions.onRepairProjection, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun reportTable(state: AnalysisFeatureState, execution: ReportExecution.Content): AccessibleTableUiModel {
    val hasComparison = execution.comparison != null
    val headers = buildList {
        if (hasComparison) add(stringResource(R.string.analysis_period_kind))
        execution.plan.spec.dimensions.forEach { add(dimensionLabel(it)) }
        execution.plan.spec.measures.forEach { add(measureLabel(it)) }
    }
    val locale = LocalLocale.current.platformLocale
    val currentLabel = stringResource(R.string.analysis_current_period)
    val referenceLabel = stringResource(R.string.analysis_reference_period)
    fun values(row: app.ledger.analytics.domain.ReportRow, periodLabel: String): List<String> = buildList {
        if (hasComparison) add(periodLabel)
        row.dimensionValues.forEach { add(AnalysisPolicy.dimensionLabel(it, locale)) }
        row.measureValues.forEach { value ->
            add(
                value.minorValue?.let { AnalysisPolicy.money(it, value.currency ?: state.baseCurrency, locale).formatted }
                    ?: value.decimalValue?.let(AnalysisPolicy::decimalText)
                    ?: "—",
            )
        }
    }
    val rows = execution.rows.map { values(it, currentLabel) } + execution.comparison.orEmptyRows().map { values(it, referenceLabel) }
    return AccessibleTableUiModel(stringResource(R.string.analysis_data_table_caption), headers, rows)
}

private fun app.ledger.analytics.domain.ReportComparison?.orEmptyRows(): List<app.ledger.analytics.domain.ReportRow> = this?.rows.orEmpty()

@Composable private fun reportTitle(report: FixedReport): String = stringResource(REPORT_TITLES.getValue(report))

@Composable private fun reportGroupTitle(group: FixedReportGroup): String = stringResource(GROUP_TITLES.getValue(group))

@Composable internal fun measureLabel(measure: Measure): String = stringResource(MEASURE_LABELS.getValue(measure))

@Composable internal fun dimensionLabel(dimension: Dimension): String = stringResource(DIMENSION_LABELS.getValue(dimension))

@Composable internal fun granularityLabel(value: TimeGranularity): String = stringResource(GRANULARITY_LABELS.getValue(value))

@Composable internal fun comparisonLabel(value: ComparisonMode?): String = if (value == null) stringResource(R.string.analysis_none) else stringResource(COMPARISON_LABELS.getValue(value))

@Composable private fun integrityCheckLabel(value: IntegrityCheckKey): String = stringResource(INTEGRITY_LABELS.getValue(value))

@Composable private fun integritySeverityLabel(value: IntegritySeverity): String = stringResource(SEVERITY_LABELS.getValue(value))

@Composable internal fun visualizationLabel(value: ReportVisualization): String = stringResource(VISUALIZATION_LABELS.getValue(value))

@Composable
internal fun exportFormatLabel(value: ReportExportFormat): String = stringResource(EXPORT_FORMAT_LABELS.getValue(value))

@Composable
internal fun entityFilterLabel(value: AnalysisEntityFilter): String = stringResource(
    when (value) {
        AnalysisEntityFilter.ACCOUNT -> R.string.analysis_filter_account
        AnalysisEntityFilter.CATEGORY -> R.string.analysis_filter_category
        AnalysisEntityFilter.MERCHANT -> R.string.analysis_filter_merchant
        AnalysisEntityFilter.PLACE -> R.string.analysis_filter_place
        AnalysisEntityFilter.PROJECT -> R.string.analysis_filter_project
    },
)

internal fun filterOptions(
    options: app.ledger.analytics.domain.ConsumptionMapFilterOptions?,
    filter: AnalysisEntityFilter,
): List<app.ledger.analytics.domain.ConsumptionMapFilterOption> = when (filter) {
    AnalysisEntityFilter.ACCOUNT -> options?.accounts.orEmpty()
    AnalysisEntityFilter.CATEGORY -> options?.categories.orEmpty()
    AnalysisEntityFilter.MERCHANT -> options?.merchants.orEmpty()
    AnalysisEntityFilter.PLACE -> options?.places.orEmpty()
    AnalysisEntityFilter.PROJECT -> options?.projects.orEmpty()
}

@Composable
internal fun transactionKindLabel(kindKey: String): String = stringResource(
    when (kindKey) {
        "EXPENSE" -> R.string.analysis_kind_expense
        "INCOME" -> R.string.analysis_kind_income
        "TRANSFER" -> R.string.analysis_kind_transfer
        "REFUND" -> R.string.analysis_kind_refund
        "CREDIT_PAYMENT" -> R.string.analysis_kind_credit_payment
        "LOAN_DISBURSEMENT" -> R.string.analysis_kind_loan_disbursement
        "LOAN_PAYMENT" -> R.string.analysis_kind_loan_payment
        "BALANCE_ADJUSTMENT" -> R.string.analysis_kind_balance_adjustment
        "FX_EXCHANGE" -> R.string.analysis_kind_fx_exchange
        "SETTLEMENT_PAYMENT" -> R.string.analysis_kind_settlement_payment
        "OPENING_BALANCE" -> R.string.analysis_kind_opening_balance
        else -> R.string.analysis_kind_other
    },
)

private fun transactionKindIcon(kindKey: String): LedgerIcon = when (kindKey) {
    "TRANSFER", "CREDIT_PAYMENT", "LOAN_DISBURSEMENT", "LOAN_PAYMENT", "FX_EXCHANGE", "SETTLEMENT_PAYMENT" -> LedgerIcon.TRANSFER
    "REFUND" -> LedgerIcon.REFUND
    else -> LedgerIcon.RECORD
}

private val REPORT_TITLES = FixedReport.entries.associateWith { report ->
    when (report) {
        FixedReport.INCOME_EXPENSE_NET -> R.string.report_income_expense_net
        FixedReport.CASH_FLOW -> R.string.report_cash_flow
        FixedReport.CONSUMPTION_CATEGORY_STRUCTURE -> R.string.report_consumption_category
        FixedReport.CATEGORY_TREND -> R.string.report_category_trend
        FixedReport.MERCHANT_RANKING_TREND -> R.string.report_merchant_ranking
        FixedReport.ACCOUNT_BALANCE_NET_FINANCIAL_ASSETS -> R.string.report_account_assets
        FixedReport.FX_REVALUATION -> R.string.report_fx_revaluation
        FixedReport.BUDGET_EXECUTION -> R.string.report_budget_execution
        FixedReport.PROJECT_BUDGET_CASH_FLOW -> R.string.report_project_budget
        FixedReport.CONSUMPTION_MAP -> R.string.report_consumption_map
        FixedReport.CREDIT_DEBT_STATEMENT_LIMIT -> R.string.report_credit_debt
        FixedReport.INSTALLMENT_BALANCE_FEES -> R.string.report_installment
        FixedReport.LOAN_PRINCIPAL_INTEREST_PROGRESS_FORECAST -> R.string.report_loan
        FixedReport.GOAL_FUNDS -> R.string.report_goal
        FixedReport.RECURRENCE_SUBSCRIPTIONS -> R.string.report_recurrence
        FixedReport.REFUNDS_CONTRA_EXPENSE -> R.string.report_refunds
        FixedReport.SETTLEMENT_ACTIVITY -> R.string.report_settlement
        FixedReport.MULTI_CURRENCY_FX_COST -> R.string.report_multi_currency
        FixedReport.MULTI_DIMENSIONAL -> R.string.report_multi_dimensional
        FixedReport.DATA_INTEGRITY -> R.string.report_data_integrity
    }
}

private val GROUP_TITLES = mapOf(
    FixedReportGroup.INCOME_AND_EXPENSE to R.string.report_group_income_expense,
    FixedReportGroup.ASSETS_AND_LIABILITIES to R.string.report_group_assets_liabilities,
    FixedReportGroup.PLANNING to R.string.report_group_planning,
    FixedReportGroup.RELATIONSHIPS to R.string.report_group_relationships,
    FixedReportGroup.DATA_QUALITY to R.string.report_group_data_quality,
)

private val MEASURE_LABELS = mapOf(
    Measure.INCOME to R.string.analysis_measure_income,
    Measure.EXPENSE to R.string.analysis_measure_expense,
    Measure.CONSUMPTION to R.string.analysis_measure_consumption,
    Measure.NON_CONSUMPTION_EXPENSE to R.string.analysis_measure_non_consumption,
    Measure.CONTRA_EXPENSE to R.string.analysis_measure_contra_expense,
    Measure.NET_CASH_FLOW to R.string.analysis_measure_net_cash_flow,
    Measure.SAVINGS_RATE to R.string.analysis_measure_savings_rate,
    Measure.BUDGET_USAGE to R.string.analysis_measure_budget_usage,
    Measure.PROJECT_USAGE to R.string.analysis_measure_project_usage,
    Measure.GOAL_BALANCE to R.string.analysis_measure_goal_balance,
    Measure.ACCOUNT_BALANCE to R.string.analysis_measure_account_balance,
    Measure.CORE_NET_FINANCIAL_ASSETS to R.string.analysis_measure_core_assets,
    Measure.ADJUSTED_NET_FINANCIAL_POSITION to R.string.analysis_measure_adjusted_position,
    Measure.FX_REVALUATION to R.string.analysis_measure_fx_revaluation,
    Measure.CREDIT_DEBT to R.string.analysis_measure_credit_debt,
    Measure.CREDIT_AVAILABLE_LIMIT to R.string.analysis_measure_credit_limit,
    Measure.INSTALLMENT_PRINCIPAL to R.string.analysis_measure_installment_principal,
    Measure.INSTALLMENT_FEES to R.string.analysis_measure_installment_fees,
    Measure.LOAN_PRINCIPAL to R.string.analysis_measure_loan_principal,
    Measure.LOAN_INTEREST to R.string.analysis_measure_loan_interest,
    Measure.SETTLEMENT_POSITION to R.string.analysis_measure_settlement_position,
    Measure.TRANSACTION_COUNT to R.string.analysis_measure_transaction_count,
)
private val DIMENSION_LABELS = mapOf(
    Dimension.DATE to R.string.analysis_dimension_date,
    Dimension.CATEGORY to R.string.analysis_dimension_category,
    Dimension.MERCHANT to R.string.analysis_dimension_merchant,
    Dimension.ACCOUNT to R.string.analysis_dimension_account,
    Dimension.CARD to R.string.analysis_dimension_card,
    Dimension.PROJECT to R.string.analysis_dimension_project,
    Dimension.GOAL to R.string.analysis_dimension_goal,
    Dimension.CURRENCY to R.string.analysis_dimension_currency,
    Dimension.PLACE to R.string.analysis_dimension_place,
    Dimension.SETTLEMENT_ACTIVITY to R.string.analysis_dimension_settlement,
    Dimension.PARTICIPANT to R.string.analysis_dimension_participant,
    Dimension.TRANSACTION_SOURCE to R.string.analysis_dimension_source,
)
private val GRANULARITY_LABELS = mapOf(
    TimeGranularity.DAY to R.string.analysis_day,
    TimeGranularity.WEEK to R.string.analysis_week,
    TimeGranularity.MONTH to R.string.analysis_month,
    TimeGranularity.QUARTER to R.string.analysis_quarter,
    TimeGranularity.YEAR to R.string.analysis_year,
)
private val COMPARISON_LABELS = mapOf(
    ComparisonMode.PREVIOUS_PERIOD to R.string.analysis_previous_comparison,
    ComparisonMode.YEAR_OVER_YEAR to R.string.analysis_year_over_year,
    ComparisonMode.MOVING_AVERAGE to R.string.analysis_moving_average,
    ComparisonMode.TREND to R.string.analysis_trend,
    ComparisonMode.FORECAST to R.string.analysis_forecast,
)
private val INTEGRITY_LABELS = mapOf(
    IntegrityCheckKey.DATABASE to R.string.analysis_integrity_database,
    IntegrityCheckKey.FOREIGN_KEYS to R.string.analysis_integrity_foreign_keys,
    IntegrityCheckKey.JOURNALS to R.string.analysis_integrity_journals,
    IntegrityCheckKey.POSTING_CURRENCIES to R.string.analysis_integrity_posting_currencies,
    IntegrityCheckKey.REVISIONS to R.string.analysis_integrity_revisions,
    IntegrityCheckKey.PROJECTIONS to R.string.analysis_integrity_projections,
    IntegrityCheckKey.FTS to R.string.analysis_integrity_fts,
    IntegrityCheckKey.RTREE to R.string.analysis_integrity_rtree,
    IntegrityCheckKey.FACT_REBUILD to R.string.analysis_integrity_fact_rebuild,
)
private val SEVERITY_LABELS = mapOf(
    IntegritySeverity.PASS to R.string.analysis_integrity_passed,
    IntegritySeverity.WARNING to R.string.analysis_integrity_warnings,
    IntegritySeverity.FAILURE to R.string.analysis_integrity_failed,
)
private val VISUALIZATION_LABELS = mapOf(
    ReportVisualization.METRIC_CARD to R.string.analysis_visualization_metric,
    ReportVisualization.LINE to R.string.analysis_visualization_line,
    ReportVisualization.BAR to R.string.analysis_visualization_bar,
    ReportVisualization.STACKED_BAR to R.string.analysis_visualization_stacked,
    ReportVisualization.PIE to R.string.analysis_visualization_pie,
    ReportVisualization.TABLE to R.string.analysis_visualization_table,
    ReportVisualization.MAP to R.string.analysis_visualization_map,
    ReportVisualization.BUDGET_PROGRESS to R.string.analysis_visualization_budget,
    ReportVisualization.GOAL_PROGRESS to R.string.analysis_visualization_goal,
)
private val EXPORT_FORMAT_LABELS = mapOf(
    ReportExportFormat.IMAGE to R.string.analysis_export_format_image,
    ReportExportFormat.PDF to R.string.analysis_export_format_pdf,
    ReportExportFormat.CSV to R.string.analysis_export_format_csv,
    ReportExportFormat.XLSX to R.string.analysis_export_format_xlsx,
)

private fun LocalDate.localized(locale: Locale): String =
    format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))

private fun Modifier.analysisRootTag(screenId: String): Modifier = when (screenId) {
    "ANA-001" -> testTag(LedgerTestTags.ANALYSIS_HOME)
    "ANA-002" -> testTag(LedgerTestTags.REPORT_CATALOG)
    "ANA-003" -> testTag(LedgerTestTags.REPORT_DETAIL)
    "ANA-004" -> testTag(LedgerTestTags.REPORT_FILTER)
    "ANA-005" -> testTag(LedgerTestTags.REPORT_DRILLDOWN)
    "ANA-006" -> testTag(LedgerTestTags.DASHBOARD_LIST)
    "ANA-007" -> testTag(LedgerTestTags.DASHBOARD_EDITOR)
    "ANA-008" -> testTag(LedgerTestTags.REPORT_BUILDER)
    "ANA-009" -> testTag(LedgerTestTags.VISUALIZATION_PICKER)
    "ANA-010" -> testTag(LedgerTestTags.REPORT_EXPORT)
    "ANA-013" -> testTag(LedgerTestTags.ANOMALY_RULES)
    "ANA-014" -> testTag(LedgerTestTags.FORECAST_DETAIL)
    "ANA-015" -> testTag(LedgerTestTags.INTEGRITY_REPORT)
    else -> testTag(LedgerTestTags.ANALYSIS_HOME)
}
