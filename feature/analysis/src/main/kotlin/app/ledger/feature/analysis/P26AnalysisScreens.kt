@file:Suppress("FunctionNaming", "LongMethod", "MagicNumber", "MaxLineLength", "TooManyFunctions", "ktlint:standard:function-naming")

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
import app.ledger.analytics.domain.AnomalyRuleType
import app.ledger.analytics.domain.CustomReportPolicy
import app.ledger.analytics.domain.DashboardItemWidth
import app.ledger.analytics.domain.ForecastKey
import app.ledger.analytics.domain.ReportExecution
import app.ledger.analytics.domain.ReportExportFormat
import app.ledger.analytics.domain.ReportVisualization
import app.ledger.core.designsystem.AccessibleTableUiModel
import app.ledger.core.designsystem.ChartCard
import app.ledger.core.designsystem.FilterChipUiModel
import app.ledger.core.designsystem.FilterDimensionUiModel
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerButton
import app.ledger.core.designsystem.LedgerButtonVariant
import app.ledger.core.designsystem.LedgerCard
import app.ledger.core.designsystem.LedgerChartSeries
import app.ledger.core.designsystem.LedgerChartType
import app.ledger.core.designsystem.LedgerChartUiModel
import app.ledger.core.designsystem.LedgerChoiceRow
import app.ledger.core.designsystem.LedgerColumnChart
import app.ledger.core.designsystem.LedgerEmptyState
import app.ledger.core.designsystem.LedgerLoadingState
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextField
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.LedgerToggleRow
import app.ledger.core.designsystem.LedgerVicoColumnRenderer

@Composable
internal fun DashboardListScreen(state: AnalysisFeatureState, actions: AnalysisActions) {
    if (state.presentation == AnalysisPresentation.EMPTY && state.dashboards.isEmpty() && state.savedReports.isEmpty()) {
        LedgerEmptyState(
            stringResource(R.string.analysis_dashboards_empty),
            stringResource(R.string.analysis_dashboards_empty_body),
            stringResource(R.string.analysis_dashboard_create),
            { actions.onNavigateP26("ANA-007", null, null) },
            Modifier.fillMaxSize().testTag(LedgerTestTags.DASHBOARD_LIST),
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag(LedgerTestTags.DASHBOARD_LIST),
        contentPadding = PaddingValues(LedgerTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
    ) {
        item {
            LedgerButton(stringResource(R.string.analysis_dashboard_create), { actions.onNavigateP26("ANA-007", null, null) }, Modifier.fillMaxWidth())
        }
        items(state.dashboards, key = { it.dashboard.id.value.toString() }) { saved ->
            LedgerCard(Modifier.fillMaxWidth(), onClick = { actions.onNavigateP26("ANA-007", saved.dashboard.id.value, null) }) {
                Column(Modifier.padding(LedgerTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                    LedgerText(saved.dashboard.name, LedgerTextRole.SECTION)
                    LedgerText(stringResource(R.string.analysis_dashboard_card_count, saved.revision.items.size), LedgerTextRole.SUPPORTING)
                }
            }
        }
        item { LedgerText(stringResource(R.string.analysis_custom_reports), LedgerTextRole.SECTION) }
        items(state.savedReports, key = { "report-${it.definition.id.value}" }) { saved ->
            LedgerCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                    LedgerText(saved.definition.name, LedgerTextRole.SECTION)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                        LedgerButton(
                            stringResource(R.string.analysis_edit_custom_report),
                            { actions.onNavigateP26("ANA-008", saved.definition.id.value, null) },
                            Modifier.weight(1f),
                            LedgerButtonVariant.TEXT,
                        )
                        LedgerButton(
                            stringResource(R.string.analysis_copy_custom_report),
                            { actions.onCopyReport(saved.definition.id) },
                            Modifier.weight(1f),
                            LedgerButtonVariant.TEXT,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun DashboardEditorScreen(state: AnalysisFeatureState, actions: AnalysisActions) {
    val selectedIds = state.dashboardItems.associateBy { it.reportId }
    LazyColumn(
        Modifier.fillMaxSize().testTag(LedgerTestTags.DASHBOARD_EDITOR),
        contentPadding = PaddingValues(LedgerTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.md),
    ) {
        if (state.presentation == AnalysisPresentation.INVALID) {
            item { LedgerBanner(stringResource(R.string.analysis_dashboard_invalid), LedgerBannerVariant.DANGER) }
        }
        item {
            LedgerTextField(
                state.draftName,
                { actions.onDraftNameChanged(it.take(80)) },
                stringResource(R.string.analysis_dashboard_name),
                required = true,
                errorText = stringResource(R.string.analysis_name_required).takeIf { state.presentation == AnalysisPresentation.INVALID },
            )
        }
        if (selectedIds.isEmpty()) {
            item { LedgerBanner(stringResource(R.string.analysis_dashboard_empty_canvas), LedgerBannerVariant.INFO) }
        } else {
            items(selectedIds.values.sortedBy { it.sortOrder }, key = { "dashboard-item-${it.reportId.value}" }) { item ->
                val report = state.savedReports.singleOrNull { it.definition.id == item.reportId }
                LedgerCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                        LedgerText(report?.definition?.name ?: stringResource(R.string.analysis_saved_report), LedgerTextRole.SECTION)
                        LedgerText(
                            if (item.width == DashboardItemWidth.FULL) stringResource(R.string.analysis_card_full_width) else stringResource(R.string.analysis_card_half_width),
                            LedgerTextRole.SUPPORTING,
                        )
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                            LedgerButton(stringResource(R.string.analysis_move_up), { actions.onMoveDashboardReport(item.reportId, -1) }, variant = LedgerButtonVariant.TEXT, enabled = item.sortOrder > 0)
                            LedgerButton(stringResource(R.string.analysis_move_down), { actions.onMoveDashboardReport(item.reportId, 1) }, variant = LedgerButtonVariant.TEXT, enabled = item.sortOrder < selectedIds.size - 1)
                            LedgerButton(stringResource(R.string.analysis_toggle_width), { actions.onToggleDashboardWidth(item.reportId) }, variant = LedgerButtonVariant.TEXT)
                            LedgerButton(stringResource(R.string.analysis_remove), { actions.onToggleDashboardReport(item.reportId) }, variant = LedgerButtonVariant.TEXT)
                        }
                    }
                }
            }
        }
        item { LedgerText(stringResource(R.string.analysis_dashboard_palette), LedgerTextRole.SECTION) }
        items(state.savedReports, key = { "dashboard-palette-${it.definition.id.value}" }) { report ->
            LedgerChoiceRow(
                report.definition.name,
                report.definition.id in selectedIds,
                { actions.onToggleDashboardReport(report.definition.id) },
                supportingText = stringResource(R.string.analysis_dashboard_palette_help),
            )
        }
        item { LedgerButton(stringResource(R.string.analysis_save), actions.onSaveDashboard, Modifier.fillMaxWidth()) }
    }
}

@Composable
internal fun ReportBuilderScreen(state: AnalysisFeatureState, actions: AnalysisActions) {
    val spec = state.draftSpec
    if (state.presentation == AnalysisPresentation.PREVIEWING) {
        LedgerLoadingState(Modifier.fillMaxSize().testTag(LedgerTestTags.REPORT_BUILDER), stringResource(R.string.analysis_previewing))
        return
    }
    requireNotNull(spec)
    val validation = CustomReportPolicy.validate(spec, state.draftVisualization)
    LazyColumn(
        Modifier.fillMaxSize().testTag(LedgerTestTags.REPORT_BUILDER),
        contentPadding = PaddingValues(LedgerTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.md),
    ) {
        if (state.presentation == AnalysisPresentation.INVALID || !validation.valid) {
            item { LedgerBanner(stringResource(R.string.analysis_builder_invalid), LedgerBannerVariant.DANGER) }
        }
        item { LedgerBanner(stringResource(R.string.analysis_no_sql_formula), LedgerBannerVariant.INFO) }
        item { LedgerText(stringResource(R.string.analysis_builder_steps), LedgerTextRole.SUPPORTING) }
        item { LedgerTextField(state.draftName, { actions.onDraftNameChanged(it.take(80)) }, stringResource(R.string.analysis_report_name), required = true) }
        item { app.ledger.core.designsystem.SelectorField(stringResource(R.string.analysis_measure), spec.measures.joinToString { it.name }, actions.onCycleMeasure) }
        item { app.ledger.core.designsystem.SelectorField(stringResource(R.string.analysis_dimension), spec.dimensions.joinToString { it.name }.ifBlank { stringResource(R.string.analysis_none) }, actions.onCycleDimension) }
        item {
            app.ledger.core.designsystem.FilterBuilder(
                listOf(FilterDimensionUiModel(stringResource(R.string.analysis_period), listOf(FilterChipUiModel("period", "${state.period.start} — ${state.period.endInclusive}")))),
                stringResource(R.string.analysis_filter_summary),
                {},
                {},
                {},
            )
        }
        item { app.ledger.core.designsystem.SelectorField(stringResource(R.string.analysis_granularity), spec.granularity.name, actions.onCycleGranularity) }
        item { app.ledger.core.designsystem.SelectorField(stringResource(R.string.analysis_comparison), spec.comparison?.name ?: stringResource(R.string.analysis_none), actions.onCycleComparison) }
        item { LedgerText(stringResource(R.string.analysis_sort_whitelist), LedgerTextRole.BODY) }
        item {
            app.ledger.core.designsystem.SelectorField(
                stringResource(R.string.analysis_visualization),
                state.draftVisualization.name,
                { actions.onNavigateP26("ANA-009", null, null) },
            )
        }
        (state.execution as? ReportExecution.Content)?.let { execution ->
            item { ReportBuilderPreview(state, execution, actions) }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
                LedgerButton(stringResource(R.string.analysis_preview), actions.onPreviewReport, Modifier.weight(1f), LedgerButtonVariant.SECONDARY)
                LedgerButton(stringResource(R.string.analysis_save), actions.onSaveReport, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ReportBuilderPreview(state: AnalysisFeatureState, execution: ReportExecution.Content, actions: AnalysisActions) {
    val model = LedgerChartUiModel(
        state.draftName.ifBlank { stringResource(R.string.analysis_custom_report) },
        "${state.period.start} — ${state.period.endInclusive}",
        stringResource(R.string.analysis_chart_summary, execution.rows.size),
        AnalysisPolicy.visualizationType(execution),
        AnalysisPolicy.chartSeries(execution),
    )
    ChartCard(
        model = model,
        chart = { ReportBuilderChart(model) },
        dataTable = AccessibleTableUiModel(stringResource(R.string.analysis_data_table_caption), listOf(stringResource(R.string.analysis_result)), execution.rows.map { listOf(it.measureValues.joinToString { value -> value.minorValue?.toString() ?: value.decimalValue.toString() }) }),
        tableExpanded = true,
        onToggleTable = {},
    )
    execution.rows.firstOrNull()?.drilldownQueryId?.let { queryId ->
        LedgerButton(
            stringResource(R.string.analysis_drilldown),
            { actions.onNavigate("ANA-005", null, queryId) },
            Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ReportBuilderChart(model: LedgerChartUiModel) {
    ReportChart(model)
}

@Composable
internal fun VisualizationPickerScreen(state: AnalysisFeatureState, actions: AnalysisActions) {
    val spec = requireNotNull(state.draftSpec)
    LazyColumn(
        Modifier.fillMaxSize().testTag(LedgerTestTags.VISUALIZATION_PICKER),
        contentPadding = PaddingValues(LedgerTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs),
    ) {
        if (state.presentation == AnalysisPresentation.AUTO_FALLBACK_TO_BAR) {
            item { LedgerBanner(stringResource(R.string.analysis_pie_fallback), LedgerBannerVariant.INFO) }
        }
        items(ReportVisualization.entries, key = { it.name }) { visualization ->
            val validation = CustomReportPolicy.validate(spec, visualization, categoryCount = if (visualization == ReportVisualization.PIE) 7 else 0)
            LedgerChoiceRow(
                visualization.name,
                state.draftVisualization == visualization,
                { actions.onSelectVisualization(visualization) },
                supportingText = if (validation.valid) stringResource(R.string.analysis_visualization_compatible) else stringResource(R.string.analysis_visualization_incompatible),
            )
        }
    }
}

@Composable
internal fun ReportExportScreen(state: AnalysisFeatureState, actions: AnalysisActions) {
    LazyColumn(
        Modifier.fillMaxSize().testTag(LedgerTestTags.REPORT_EXPORT),
        contentPadding = PaddingValues(LedgerTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.md),
    ) {
        item { LedgerBanner(stringResource(R.string.analysis_export_interface_only), LedgerBannerVariant.INFO) }
        item { LedgerText(stringResource(R.string.analysis_export_scope, state.period.start.toString(), state.period.endInclusive.toString()), LedgerTextRole.BODY) }
        items(ReportExportFormat.entries, key = { it.name }) { format ->
            LedgerChoiceRow(format.name, state.exportFormat == format, { actions.onSelectExportFormat(format) })
        }
        item { LedgerBanner(stringResource(R.string.analysis_export_sensitive_notice), LedgerBannerVariant.WARNING) }
        item { LedgerButton(stringResource(R.string.analysis_prepare_export), actions.onPrepareExport, Modifier.fillMaxWidth()) }
    }
}

@Composable
internal fun AnomalyRulesScreen(state: AnalysisFeatureState, actions: AnalysisActions) {
    if (state.presentation == AnalysisPresentation.EMPTY && state.anomalyRules.isEmpty()) {
        LedgerEmptyState(
            stringResource(R.string.analysis_anomaly_empty),
            stringResource(R.string.analysis_anomaly_empty_body),
            stringResource(R.string.analysis_add_rule),
            { actions.onEditAnomalyRule(null) },
            Modifier.fillMaxSize().testTag(LedgerTestTags.ANOMALY_RULES),
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag(LedgerTestTags.ANOMALY_RULES),
        contentPadding = PaddingValues(LedgerTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.md),
    ) {
        if (state.presentation == AnalysisPresentation.INVALID) item { LedgerBanner(stringResource(R.string.analysis_anomaly_invalid), LedgerBannerVariant.DANGER) }
        item { LedgerBanner(stringResource(R.string.analysis_anomaly_disclosure), LedgerBannerVariant.INFO) }
        item { app.ledger.core.designsystem.SelectorField(stringResource(R.string.analysis_anomaly_type), anomalyTitle(state.anomalyDraftType), actions.onCycleAnomalyType) }
        item { LedgerTextField(state.anomalyThresholdText, { actions.onAnomalyThresholdChanged(it.filter { char -> char.isDigit() || char == '.' }.take(32)) }, stringResource(R.string.analysis_anomaly_threshold), required = true) }
        item { LedgerTextField(state.anomalyLookbackText, { actions.onAnomalyLookbackChanged(it.filter(Char::isDigit).take(3)) }, stringResource(R.string.analysis_anomaly_lookback), required = true) }
        item { LedgerButton(stringResource(R.string.analysis_save_rule), { actions.onSaveAnomalyRule(state.editingAnomalyRuleId) }, Modifier.fillMaxWidth()) }
        items(state.anomalyRules, key = { it.id.value.toString() }) { saved ->
            LedgerCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                    LedgerText(anomalyTitle(saved.rule.type), LedgerTextRole.SECTION)
                    LedgerText(stringResource(R.string.analysis_rule_details, saved.rule.version.value, saved.rule.lookbackPeriods, saved.rule.threshold.toPlainString()), LedgerTextRole.SUPPORTING)
                    LedgerButton(stringResource(R.string.analysis_edit_rule), { actions.onEditAnomalyRule(saved.id) }, Modifier.fillMaxWidth(), LedgerButtonVariant.TEXT)
                }
            }
        }
        items(state.anomalyFindings, key = { "${it.rule.type}-${it.seriesKey}-${it.date}" }) { finding ->
            LedgerCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(LedgerTheme.spacing.sm)) {
                    LedgerText(finding.explanationCode, LedgerTextRole.SECTION)
                    LedgerText(stringResource(R.string.analysis_finding_window, finding.windowStart.toString(), finding.windowEndInclusive.toString(), finding.observedMinor, finding.baselineMinor), LedgerTextRole.SUPPORTING)
                }
            }
        }
        item { LedgerButton(stringResource(R.string.analysis_add_rule), { actions.onEditAnomalyRule(null) }, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun anomalyTitle(type: AnomalyRuleType): String = when (type) {
    AnomalyRuleType.HISTORICAL_MEAN_STANDARD_DEVIATION -> stringResource(R.string.analysis_rule_standard_deviation)
    AnomalyRuleType.RECENT_MONTH_GROWTH_THRESHOLD -> stringResource(R.string.analysis_rule_growth)
    AnomalyRuleType.LARGE_SINGLE_TRANSACTION -> stringResource(R.string.analysis_rule_large)
    AnomalyRuleType.MERCHANT_FREQUENCY -> stringResource(R.string.analysis_rule_merchant_frequency)
    AnomalyRuleType.CATEGORY_FREQUENCY -> stringResource(R.string.analysis_rule_category_frequency)
}

@Composable
internal fun ForecastDetailScreen(state: AnalysisFeatureState, actions: AnalysisActions) {
    val forecast = state.forecast
    if (state.presentation == AnalysisPresentation.INSUFFICIENT_DATA || forecast == null) {
        LedgerEmptyState(
            stringResource(R.string.analysis_forecast_insufficient),
            stringResource(R.string.analysis_forecast_insufficient_body),
            stringResource(R.string.analysis_retry),
            actions.onRetry,
            Modifier.fillMaxSize().testTag(LedgerTestTags.FORECAST_DETAIL),
        )
        return
    }
    val locale = LocalLocale.current.platformLocale
    val amount = AnalysisPolicy.money(forecast.projectedMinor, state.baseCurrency, locale).formatted
    val model = LedgerChartUiModel(
        stringResource(R.string.analysis_forecast_title),
        forecast.throughDate.toString(),
        stringResource(R.string.analysis_forecast_summary, amount),
        LedgerChartType.COLUMN,
        listOf(LedgerChartSeries("forecast-v${forecast.version.value}", stringResource(R.string.analysis_forecast_projected), listOf(forecast.projectedMinor.toDouble()), listOf(forecast.throughDate.toString()))),
    )
    LazyColumn(
        Modifier.fillMaxSize().testTag(LedgerTestTags.FORECAST_DETAIL),
        contentPadding = PaddingValues(LedgerTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.md),
    ) {
        item {
            ChartCard(
                model = model,
                chart = { LedgerColumnChart(model, LedgerVicoColumnRenderer, Modifier.fillMaxWidth()) },
                dataTable = AccessibleTableUiModel(stringResource(R.string.analysis_forecast_table), listOf(stringResource(R.string.analysis_period), stringResource(R.string.analysis_result)), listOf(listOf(forecast.throughDate.toString(), amount))),
                tableExpanded = true,
                onToggleTable = {},
            )
        }
        item { LedgerBanner(stringResource(R.string.analysis_forecast_version, forecast.version.value, forecast.windowStart.toString(), forecast.windowEndInclusive.toString()), LedgerBannerVariant.INFO) }
        item { LedgerText(stringResource(R.string.analysis_forecast_assumptions, forecast.observedMinor, forecast.dailyAverageMinor, forecast.recurrenceIncludedMinor), LedgerTextRole.BODY) }
        item {
            LedgerToggleRow(
                stringResource(R.string.analysis_forecast_recurrence),
                state.forecastKey == ForecastKey.MONTH_END_BALANCE_WITH_RECURRENCE,
                { actions.onNavigateP26("ANA-014", null, if (it) ForecastKey.MONTH_END_BALANCE_WITH_RECURRENCE else ForecastKey.MONTH_END_SPENDING) },
                supportingText = stringResource(R.string.analysis_forecast_recurrence_help),
            )
        }
        item {
            LedgerButton(
                stringResource(R.string.analysis_forecast_historical),
                { actions.onNavigateP26("ANA-014", null, ForecastKey.HISTORICAL_SAME_MONTH) },
                Modifier.fillMaxWidth(),
                LedgerButtonVariant.SECONDARY,
            )
        }
    }
}
