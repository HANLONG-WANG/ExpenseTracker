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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.ledger.analytics.domain.AnomalyRuleId
import app.ledger.analytics.domain.ComparisonMode
import app.ledger.analytics.domain.DashboardId
import app.ledger.analytics.domain.Dimension
import app.ledger.analytics.domain.DrilldownQueryId
import app.ledger.analytics.domain.FixedReport
import app.ledger.analytics.domain.FixedReportGroup
import app.ledger.analytics.domain.ForecastKey
import app.ledger.analytics.domain.IntegrityCheckKey
import app.ledger.analytics.domain.IntegritySeverity
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
import app.ledger.core.designsystem.LedgerChip
import app.ledger.core.designsystem.LedgerColumnChart
import app.ledger.core.designsystem.LedgerEmptyState
import app.ledger.core.designsystem.LedgerErrorState
import app.ledger.core.designsystem.LedgerLineChart
import app.ledger.core.designsystem.LedgerLoadingState
import app.ledger.core.designsystem.LedgerPieChart
import app.ledger.core.designsystem.LedgerProgressIndicator
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

data class AnalysisActions(
    val onNavigate: (screenId: String, report: FixedReport?, queryId: DrilldownQueryId?) -> Unit,
    val onRetry: () -> Unit,
    val onPreviousPeriod: () -> Unit,
    val onNextPeriod: () -> Unit,
    val onCycleMeasure: () -> Unit,
    val onCycleDimension: () -> Unit,
    val onCycleGranularity: () -> Unit,
    val onCycleComparison: () -> Unit,
    val onApplyFilter: () -> Unit,
    val onExport: () -> Unit,
    val onLoadMore: () -> Unit,
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
    val onPrepareExport: () -> Unit = {},
)

@Composable
fun AnalysisDestination(screenId: String, state: AnalysisLoadState, actions: AnalysisActions) {
    if (state === AnalysisLoadState.Loading) {
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
        "ANA-013" -> AnomalyRulesScreen(content, actions)
        "ANA-014" -> ForecastDetailScreen(content, actions)
        "ANA-015" -> IntegrityReport(content, actions)
        else -> LedgerErrorState(UiErrorCode("ANALYSIS_SCREEN_UNKNOWN"), stringResource(R.string.analysis_query_failed), actions.onRetry)
    }
}

@Composable
private fun AnalysisHome(state: AnalysisFeatureState, actions: AnalysisActions) {
    val overview = state.overview
    if (state.presentation == AnalysisPresentation.CALCULATING) {
        LedgerLoadingState(
            Modifier.fillMaxSize().testTag(LedgerTestTags.ANALYSIS_HOME),
            stringResource(R.string.analysis_calculating),
        )
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        LedgerButton(stringResource(R.string.analysis_previous_period), actions.onPreviousPeriod, variant = LedgerButtonVariant.TEXT)
        LedgerText("${state.period.start} — ${state.period.endInclusive}", LedgerTextRole.SECTION)
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
                            if (definition.report == FixedReport.DATA_INTEGRITY) "ANA-015" else "ANA-003",
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
        AnalysisPresentation.LOADING -> LedgerLoadingState(
            Modifier.fillMaxSize().testTag(LedgerTestTags.REPORT_DETAIL),
            stringResource(R.string.analysis_loading),
        )
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
private fun ReportContent(state: AnalysisFeatureState, execution: ReportExecution.Content, actions: AnalysisActions) {
    val table = reportTable(state, execution)
    val chartModel = LedgerChartUiModel(
        title = state.fixedReport?.let { reportTitle(it) } ?: stringResource(R.string.analysis_custom_report),
        scope = "${state.period.start} — ${state.period.endInclusive}",
        summary = stringResource(R.string.analysis_chart_summary, execution.rows.size),
        type = AnalysisPolicy.visualizationType(execution),
        series = AnalysisPolicy.chartSeries(execution),
    )
    LazyColumn(
        Modifier.fillMaxSize().testTag(LedgerTestTags.REPORT_DETAIL),
        contentPadding = PaddingValues(LedgerTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.md),
    ) {
        item { PeriodControls(state, actions) }
        item {
            LedgerText(stringResource(R.string.analysis_semantics_help), LedgerTextRole.SUPPORTING)
        }
        execution.comparison?.let { comparison ->
            item {
                LedgerBanner(
                    stringResource(
                        R.string.analysis_comparison_summary,
                        comparison.referencePeriod.start.toString(),
                        comparison.referencePeriod.endInclusive.toString(),
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
                tableExpanded = true,
                onToggleTable = {},
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
    val measureText = buildString {
        spec.measures.forEachIndexed { index, measure ->
            if (index > 0) append(", ")
            append(measureLabel(measure))
        }
    }
    val dimensionText = buildString {
        spec.dimensions.forEachIndexed { index, dimension ->
            if (index > 0) append(", ")
            append(dimensionLabel(dimension))
        }
    }.ifBlank { stringResource(R.string.analysis_none) }
    LazyColumn(
        Modifier.fillMaxSize().testTag(LedgerTestTags.REPORT_FILTER),
        contentPadding = PaddingValues(LedgerTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.md),
    ) {
        if (state.presentation == AnalysisPresentation.INVALID) {
            item { LedgerBanner(stringResource(R.string.analysis_filter_invalid), LedgerBannerVariant.DANGER) }
        }
        item { LedgerBanner(stringResource(R.string.analysis_no_sql_formula), LedgerBannerVariant.INFO) }
        item { SelectorField(stringResource(R.string.analysis_measure), measureText, actions.onCycleMeasure) }
        item { SelectorField(stringResource(R.string.analysis_dimension), dimensionText, actions.onCycleDimension) }
        item { SelectorField(stringResource(R.string.analysis_granularity), granularityLabel(spec.granularity), actions.onCycleGranularity) }
        item { SelectorField(stringResource(R.string.analysis_comparison), comparisonLabel(spec.comparison), actions.onCycleComparison) }
        item {
            app.ledger.core.designsystem.FilterBuilder(
                dimensions = listOf(
                    FilterDimensionUiModel(stringResource(R.string.analysis_period), listOf(FilterChipUiModel("period", "${state.period.start} — ${state.period.endInclusive}"))),
                ),
                naturalLanguageSummary = stringResource(R.string.analysis_filter_summary),
                onRemove = {},
                onReset = {},
                onApply = actions.onApplyFilter,
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
    LazyColumn(
        Modifier.fillMaxSize().testTag(LedgerTestTags.REPORT_DRILLDOWN),
        contentPadding = PaddingValues(LedgerTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
    ) {
        item { LedgerText(stringResource(R.string.analysis_query_summary, state.period.start.toString(), state.period.endInclusive.toString()), LedgerTextRole.SUPPORTING) }
        items(page.rows, key = { it.transactionId.toString() }) { row ->
            LedgerCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(LedgerTheme.spacing.sm)) {
                    LedgerText("${row.localDate} · ${row.kindKey}", LedgerTextRole.BODY)
                    LedgerText(AnalysisPolicy.money(row.amountMinor, row.currency, locale).formatted, LedgerTextRole.SECTION)
                }
            }
        }
        if (page.nextCursor != null) {
            item {
                LedgerButton(
                    stringResource(R.string.analysis_load_more),
                    actions.onLoadMore,
                    Modifier.fillMaxWidth(),
                    LedgerButtonVariant.SECONDARY,
                )
            }
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
        row.dimensionValues.forEach { add(AnalysisPolicy.dimensionLabel(it)) }
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

@Composable private fun measureLabel(measure: Measure): String = stringResource(MEASURE_LABELS.getValue(measure))

@Composable private fun dimensionLabel(dimension: Dimension): String = stringResource(DIMENSION_LABELS.getValue(dimension))

@Composable private fun granularityLabel(value: TimeGranularity): String = stringResource(GRANULARITY_LABELS.getValue(value))

@Composable private fun comparisonLabel(value: ComparisonMode?): String = if (value == null) stringResource(R.string.analysis_none) else stringResource(COMPARISON_LABELS.getValue(value))

@Composable private fun integrityCheckLabel(value: IntegrityCheckKey): String = stringResource(INTEGRITY_LABELS.getValue(value))

@Composable private fun integritySeverityLabel(value: IntegritySeverity): String = stringResource(SEVERITY_LABELS.getValue(value))

@Composable private fun visualizationLabel(value: ReportVisualization): String = stringResource(VISUALIZATION_LABELS.getValue(value))

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

private val MEASURE_LABELS = Measure.entries.associateWith { R.string.analysis_measure_generic }
private val DIMENSION_LABELS = Dimension.entries.associateWith { R.string.analysis_dimension_generic }
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
private val INTEGRITY_LABELS = IntegrityCheckKey.entries.associateWith { R.string.analysis_integrity_check }
private val SEVERITY_LABELS = mapOf(
    IntegritySeverity.PASS to R.string.analysis_integrity_passed,
    IntegritySeverity.WARNING to R.string.analysis_integrity_warnings,
    IntegritySeverity.FAILURE to R.string.analysis_integrity_failed,
)
private val VISUALIZATION_LABELS = ReportVisualization.entries.associateWith { R.string.analysis_visualization }

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
