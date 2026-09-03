@file:Suppress("FunctionNaming", "LongMethod", "MagicNumber", "MaxLineLength", "TooManyFunctions", "ktlint:standard:function-naming")

package app.ledger.feature.analysis

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.ledger.analytics.domain.AnomalyRuleType
import app.ledger.analytics.domain.CustomReportPolicy
import app.ledger.analytics.domain.DashboardItemWidth
import app.ledger.analytics.domain.ForecastKey
import app.ledger.analytics.domain.ReportExecution
import app.ledger.analytics.domain.ReportExportFormat
import app.ledger.analytics.domain.ReportVisualization
import app.ledger.analytics.domain.SortDirection
import app.ledger.core.designsystem.AccessibleTableUiModel
import app.ledger.core.designsystem.ChartCard
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerButton
import app.ledger.core.designsystem.LedgerButtonVariant
import app.ledger.core.designsystem.LedgerCard
import app.ledger.core.designsystem.LedgerChartSeries
import app.ledger.core.designsystem.LedgerChartType
import app.ledger.core.designsystem.LedgerChartUiModel
import app.ledger.core.designsystem.LedgerCheckboxRow
import app.ledger.core.designsystem.LedgerChoiceRow
import app.ledger.core.designsystem.LedgerCycleChoiceSelector
import app.ledger.core.designsystem.LedgerDateFormatterRuntime
import app.ledger.core.designsystem.LedgerEmptyState
import app.ledger.core.designsystem.LedgerLineChart
import app.ledger.core.designsystem.LedgerLoadingState
import app.ledger.core.designsystem.LedgerScaffold
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextField
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.LedgerToggleRow
import app.ledger.core.designsystem.LedgerVicoLineRenderer
import app.ledger.core.money.CurrencyCode
import app.ledger.core.money.JvmLegalTenderCurrencyCatalog
import app.ledger.core.money.LocaleNumberFormatter
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

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
    LedgerScaffold(
        modifier = Modifier.fillMaxSize().testTag(LedgerTestTags.DASHBOARD_EDITOR),
        formContent = true,
        fixedAction = { AnalysisSaveBar(actions.onSaveDashboard, true) },
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = LedgerTheme.spacing.md,
                top = LedgerTheme.spacing.md,
                end = LedgerTheme.spacing.md,
                bottom = LedgerTheme.dimensions.bottomActionInset + LedgerTheme.spacing.xxl,
            ),
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
                    val dragLabel = stringResource(R.string.analysis_drag_handle)
                    val haptic = LocalHapticFeedback.current
                    var dragDistance by remember(item.reportId) { mutableFloatStateOf(0f) }
                    LedgerCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                            LedgerText(
                                "⋮⋮",
                                LedgerTextRole.LABEL,
                                Modifier
                                    .draggable(
                                        orientation = Orientation.Vertical,
                                        onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                        state = rememberDraggableState { delta ->
                                            dragDistance += delta
                                            if (dragDistance > DRAG_REORDER_THRESHOLD) {
                                                actions.onMoveDashboardReport(item.reportId, 1)
                                                dragDistance = 0f
                                            } else if (dragDistance < -DRAG_REORDER_THRESHOLD) {
                                                actions.onMoveDashboardReport(item.reportId, -1)
                                                dragDistance = 0f
                                            }
                                        },
                                    )
                                    .semantics { contentDescription = dragLabel },
                            )
                            LedgerText(report?.definition?.name ?: stringResource(R.string.analysis_saved_report), LedgerTextRole.SECTION)
                            LedgerText(
                                if (item.width == DashboardItemWidth.FULL) stringResource(R.string.analysis_card_full_width) else stringResource(R.string.analysis_card_half_width),
                                LedgerTextRole.SUPPORTING,
                            )
                            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                                LedgerButton(stringResource(R.string.analysis_move_up), { actions.onMoveDashboardReport(item.reportId, -1) }, variant = LedgerButtonVariant.TEXT, enabled = item.sortOrder > 0)
                                LedgerButton(stringResource(R.string.analysis_move_down), { actions.onMoveDashboardReport(item.reportId, 1) }, variant = LedgerButtonVariant.TEXT, enabled = item.sortOrder < selectedIds.size - 1)
                                LedgerButton(stringResource(R.string.analysis_toggle_width), { actions.onToggleDashboardWidth(item.reportId) }, variant = LedgerButtonVariant.TEXT)
                                if (report != null) {
                                    LedgerButton(
                                        stringResource(R.string.analysis_edit_custom_report),
                                        { actions.onNavigateP26("ANA-008", report.definition.id.value, null) },
                                        variant = LedgerButtonVariant.TEXT,
                                    )
                                }
                                LedgerButton(stringResource(R.string.analysis_remove), { actions.onToggleDashboardReport(item.reportId) }, variant = LedgerButtonVariant.TEXT)
                            }
                        }
                    }
                }
            }
            item { LedgerText(stringResource(R.string.analysis_dashboard_palette), LedgerTextRole.SECTION) }
            items(state.savedReports, key = { "dashboard-palette-${it.definition.id.value}" }) { report ->
                LedgerCheckboxRow(
                    report.definition.name,
                    report.definition.id in selectedIds,
                    { actions.onToggleDashboardReport(report.definition.id) },
                    supportingText = stringResource(R.string.analysis_dashboard_palette_help),
                )
            }
        }
    }
}

@Composable
internal fun ReportBuilderScreen(state: AnalysisFeatureState, actions: AnalysisActions) {
    val spec = requireNotNull(state.draftSpec)
    val validation = CustomReportPolicy.validate(spec, state.draftVisualization)
    val ready = state.presentation != AnalysisPresentation.PREVIEWING
    val canContinue = validation.valid && ready && (state.builderStep != BUILDER_LAST_STEP || state.draftName.isNotBlank())
    LedgerScaffold(
        modifier = Modifier.fillMaxSize().testTag(LedgerTestTags.REPORT_BUILDER),
        formContent = true,
        fixedAction = { BuilderActionBar(state.builderStep, canContinue, ready, actions) },
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = LedgerTheme.spacing.md,
                top = LedgerTheme.spacing.md,
                end = LedgerTheme.spacing.md,
                bottom = LedgerTheme.dimensions.bottomActionInset + LedgerTheme.spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.md),
        ) {
            if (state.presentation == AnalysisPresentation.INVALID || !validation.valid) {
                item { LedgerBanner(stringResource(R.string.analysis_builder_invalid), LedgerBannerVariant.DANGER) }
            }
            if (state.presentation == AnalysisPresentation.PREVIEWING) {
                item { LedgerLoadingState(Modifier.fillMaxWidth(), stringResource(R.string.analysis_previewing)) }
            }
            item { LedgerBanner(stringResource(R.string.analysis_no_sql_formula), LedgerBannerVariant.INFO) }
            item {
                LedgerText(
                    stringResource(R.string.analysis_builder_step_indicator, state.builderStep + 1, BUILDER_LAST_STEP + 1),
                    LedgerTextRole.SECTION,
                )
            }
            item { LedgerText(stringResource(R.string.analysis_builder_steps), LedgerTextRole.SUPPORTING) }
            item {
                LedgerTextField(
                    state.draftName,
                    { actions.onDraftNameChanged(it.take(80)) },
                    stringResource(R.string.analysis_report_name),
                    required = true,
                    errorText = stringResource(R.string.analysis_name_required).takeIf { state.presentation == AnalysisPresentation.INVALID && state.draftName.isBlank() },
                )
            }
            when (state.builderStep) {
                0 -> {
                    item { LedgerText(stringResource(R.string.analysis_measure), LedgerTextRole.SECTION) }
                    items(app.ledger.analytics.domain.Measure.entries, key = { "builder-measure-${it.name}" }) { measure ->
                        LedgerCheckboxRow(measureLabel(measure), measure in spec.measures, { actions.onSelectMeasure(measure) })
                    }
                }
                1 -> {
                    item { LedgerText(stringResource(R.string.analysis_dimension), LedgerTextRole.SECTION) }
                    items(app.ledger.analytics.domain.Dimension.entries, key = { "builder-dimension-${it.name}" }) { dimension ->
                        LedgerCheckboxRow(dimensionLabel(dimension), dimension in spec.dimensions, { actions.onSelectDimension(dimension) })
                    }
                }
                2 -> item { ReportFilterEditor(state, spec, actions, includeApply = false) }
                3 -> {
                    item { LedgerText(stringResource(R.string.analysis_granularity), LedgerTextRole.SECTION) }
                    items(app.ledger.analytics.domain.TimeGranularity.entries, key = { "builder-granularity-${it.name}" }) { granularity ->
                        LedgerChoiceRow(granularityLabel(granularity), spec.granularity == granularity, { actions.onSelectGranularity(granularity) })
                    }
                    item { LedgerText(stringResource(R.string.analysis_comparison), LedgerTextRole.SECTION) }
                    item { LedgerChoiceRow(stringResource(R.string.analysis_none), spec.comparison == null, { actions.onSelectComparison(null) }) }
                    items(app.ledger.analytics.domain.ComparisonMode.entries, key = { "builder-comparison-${it.name}" }) { comparison ->
                        LedgerChoiceRow(comparisonLabel(comparison), spec.comparison == comparison, { actions.onSelectComparison(comparison) })
                    }
                }
                4 -> item { ReportSortEditor(spec, actions) }
                5 -> item {
                    app.ledger.core.designsystem.SelectorField(
                        stringResource(R.string.analysis_visualization),
                        visualizationLabel(state.draftVisualization),
                        { actions.onNavigateP26("ANA-009", null, null) },
                        supportingText = stringResource(R.string.analysis_visualization_choose_help),
                    )
                }
                else -> {
                    (state.execution as? ReportExecution.Content)?.let { execution ->
                        item { ReportBuilderPreview(state, execution, actions) }
                    } ?: item { LedgerBanner(stringResource(R.string.analysis_preview_required), LedgerBannerVariant.INFO) }
                }
            }
        }
    }
}

@Composable
private fun ReportSortEditor(spec: app.ledger.analytics.domain.ReportSpec, actions: AnalysisActions) {
    LedgerText(stringResource(R.string.analysis_sort_editor), LedgerTextRole.SECTION)
    LedgerText(stringResource(R.string.analysis_sort_whitelist), LedgerTextRole.BODY)
    (spec.measures.map { "measure:${it.name}" to measureLabel(it) } + spec.dimensions.map { "dimension:${it.name}" to dimensionLabel(it) })
        .forEach { (key, label) ->
            val current = spec.sorting.singleOrNull { AnalysisPolicy.run { it.sortKey() == key } }
            LedgerCycleChoiceSelector(
                label,
                when (current?.direction) {
                    null -> 0
                    SortDirection.ASCENDING -> 1
                    SortDirection.DESCENDING -> 2
                },
                listOf(
                    stringResource(R.string.analysis_sort_none),
                    stringResource(R.string.analysis_sort_ascending),
                    stringResource(R.string.analysis_sort_descending),
                ),
                { actions.onCycleSort(key) },
            )
        }
}

@Composable
private fun BuilderActionBar(step: Int, canContinue: Boolean, canSave: Boolean, actions: AnalysisActions) {
    LedgerCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
        ) {
            if (step > 0) {
                LedgerButton(stringResource(R.string.analysis_previous_step), { actions.onBuilderStep(-1) }, Modifier.weight(1f), LedgerButtonVariant.SECONDARY)
            }
            if (step < BUILDER_LAST_STEP) {
                LedgerButton(stringResource(R.string.analysis_next_step), { actions.onBuilderStep(1) }, Modifier.weight(1f), enabled = canContinue)
            } else {
                LedgerButton(stringResource(R.string.analysis_preview), actions.onPreviewReport, Modifier.weight(1f), LedgerButtonVariant.SECONDARY)
                LedgerButton(stringResource(R.string.analysis_save), actions.onSaveReport, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ReportBuilderPreview(state: AnalysisFeatureState, execution: ReportExecution.Content, actions: AnalysisActions) {
    val locale = LocalLocale.current.platformLocale
    val model = LedgerChartUiModel(
        state.draftName.ifBlank { stringResource(R.string.analysis_custom_report) },
        "${state.period.start.localized(locale)} — ${state.period.endInclusive.localized(locale)}",
        stringResource(R.string.analysis_chart_summary, execution.rows.size),
        AnalysisPolicy.visualizationType(execution),
        localizedChartSeries(execution, locale, state.baseCurrency),
    )
    var tableExpanded by remember(model.title) { mutableStateOf(true) }
    ChartCard(
        model = model,
        chart = { ReportBuilderChart(model) },
        dataTable = AccessibleTableUiModel(
            stringResource(R.string.analysis_data_table_caption),
            listOf(stringResource(R.string.analysis_result)),
            execution.rows.map { row ->
                listOf(
                    row.measureValues.joinToString { value ->
                        value.minorValue?.let { minor ->
                            AnalysisPolicy.money(minor, value.currency ?: state.baseCurrency, locale).formatted
                        } ?: value.decimalValue?.let { decimal -> AnalysisPolicy.decimalText(decimal, locale) }.orEmpty()
                    },
                )
            },
        ),
        tableExpanded = tableExpanded,
        onToggleTable = { tableExpanded = !tableExpanded },
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
                visualizationLabel(visualization),
                state.draftVisualization == visualization,
                { actions.onSelectVisualization(visualization) },
                supportingText = if (validation.valid) {
                    stringResource(R.string.analysis_visualization_compatible)
                } else {
                    stringResource(R.string.analysis_visualization_incompatible_reason, visualizationLabel(validation.visualization.resolved))
                },
                enabled = validation.valid,
            )
        }
    }
}

@Composable
internal fun ReportExportScreen(state: AnalysisFeatureState, actions: AnalysisActions) {
    val locale = LocalLocale.current.platformLocale
    LazyColumn(
        Modifier.fillMaxSize().testTag(LedgerTestTags.REPORT_EXPORT),
        contentPadding = PaddingValues(LedgerTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.md),
    ) {
        item { LedgerBanner(stringResource(R.string.analysis_export_handoff), LedgerBannerVariant.INFO) }
        item { LedgerText(stringResource(R.string.analysis_export_scope, state.period.start.localized(locale), state.period.endInclusive.localized(locale)), LedgerTextRole.BODY) }
        item { LedgerText(stringResource(R.string.analysis_export_scope_title), LedgerTextRole.SECTION) }
        items(AnalysisExportScope.entries, key = { "export-scope-${it.name}" }) { scope ->
            LedgerChoiceRow(
                if (scope == AnalysisExportScope.CURRENT_RESULTS) stringResource(R.string.analysis_export_scope_current) else stringResource(R.string.analysis_export_scope_comparison),
                state.exportScope == scope,
                { actions.onSelectExportScope(scope) },
                supportingText = if (scope == AnalysisExportScope.CURRENT_RESULTS) {
                    stringResource(R.string.analysis_export_scope_current_help)
                } else {
                    stringResource(R.string.analysis_export_scope_comparison_help)
                },
            )
        }
        item { LedgerText(stringResource(R.string.analysis_export_format_title), LedgerTextRole.SECTION) }
        items(ReportExportFormat.entries, key = { it.name }) { format ->
            LedgerChoiceRow(exportFormatLabel(format), state.exportFormat == format, { actions.onSelectExportFormat(format) })
        }
        item { LedgerBanner(stringResource(R.string.analysis_export_sensitive_notice), LedgerBannerVariant.WARNING) }
        item { LedgerButton(stringResource(R.string.analysis_start_export_flow), actions.onPrepareExport, Modifier.fillMaxWidth()) }
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
    val locale = LocalLocale.current.platformLocale
    LedgerScaffold(
        modifier = Modifier.fillMaxSize().testTag(LedgerTestTags.ANOMALY_RULES),
        formContent = true,
        fixedAction = {
            LedgerCard(Modifier.fillMaxWidth()) {
                LedgerButton(
                    stringResource(R.string.analysis_save_rule),
                    { actions.onSaveAnomalyRule(state.editingAnomalyRuleId) },
                    Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm),
                    enabled = state.presentation != AnalysisPresentation.RUNNING,
                )
            }
        },
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(LedgerTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.md),
        ) {
            if (state.presentation == AnalysisPresentation.INVALID) item { LedgerBanner(stringResource(R.string.analysis_anomaly_invalid), LedgerBannerVariant.DANGER) }
            item { LedgerBanner(stringResource(R.string.analysis_anomaly_disclosure), LedgerBannerVariant.INFO) }
            item {
                app.ledger.core.designsystem.LedgerChoiceSelector(
                    stringResource(R.string.analysis_anomaly_type),
                    state.anomalyDraftType.ordinal,
                    AnomalyRuleType.entries.map { anomalyTitle(it) },
                    { actions.onSelectAnomalyType(AnomalyRuleType.entries[it]) },
                )
            }
            item { LedgerTextField(state.anomalyThresholdText, { actions.onAnomalyThresholdChanged(it.filter { char -> char.isDigit() || char == '.' }.take(32)) }, stringResource(R.string.analysis_anomaly_threshold), required = true) }
            item { LedgerTextField(state.anomalyLookbackText, { actions.onAnomalyLookbackChanged(it.filter(Char::isDigit).take(3)) }, stringResource(R.string.analysis_anomaly_lookback), required = true) }
            items(state.anomalyRules, key = { it.id.value.toString() }) { saved ->
                LedgerCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                        LedgerText(anomalyTitle(saved.rule.type), LedgerTextRole.SECTION)
                        LedgerText(stringResource(R.string.analysis_rule_details, saved.rule.version.value, saved.rule.lookbackPeriods, LocaleNumberFormatter.decimal(saved.rule.threshold, locale, 2)), LedgerTextRole.SUPPORTING)
                        LedgerButton(stringResource(R.string.analysis_edit_rule), { actions.onEditAnomalyRule(saved.id) }, Modifier.fillMaxWidth(), LedgerButtonVariant.TEXT)
                    }
                }
            }
            items(state.anomalyFindings, key = { "${it.rule.type}-${it.seriesKey}-${it.date}" }) { finding ->
                val observed = AnalysisPolicy.money(finding.observedMinor, state.baseCurrency, locale).formatted
                val baseline = AnalysisPolicy.money(finding.baselineMinor, state.baseCurrency, locale).formatted
                val impact = AnalysisPolicy.money(finding.observedMinor - finding.baselineMinor, state.baseCurrency, locale).formatted
                LedgerCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                        LedgerText(anomalyTitle(finding.rule.type), LedgerTextRole.SECTION)
                        LedgerText(
                            stringResource(
                                R.string.analysis_finding_algorithm,
                                finding.rule.version.value,
                                LocaleNumberFormatter.decimal(finding.rule.threshold, locale, 2),
                                LocaleNumberFormatter.decimal(finding.score, locale, 2),
                            ),
                            LedgerTextRole.SUPPORTING,
                        )
                        LedgerText(
                            stringResource(
                                R.string.analysis_finding_window_formatted,
                                finding.windowStart.localized(locale),
                                finding.windowEndInclusive.localized(locale),
                                observed,
                                baseline,
                            ),
                            LedgerTextRole.SUPPORTING,
                        )
                        LedgerText(stringResource(R.string.analysis_finding_impact, impact), LedgerTextRole.BODY)
                    }
                }
            }
            item { LedgerButton(stringResource(R.string.analysis_add_rule), { actions.onEditAnomalyRule(null) }, Modifier.fillMaxWidth()) }
        }
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
    val trend = state.forecastComparisons[ForecastKey.MONTH_END_SPENDING] ?: forecast
    val historical = state.forecastComparisons[ForecastKey.HISTORICAL_SAME_MONTH]
    val currentLabels = listOf(trend.windowEndInclusive.localized(locale), trend.throughDate.localized(locale))
    val series = buildList {
        add(
            LedgerChartSeries(
                "forecast-v${forecast.version.value}",
                stringResource(R.string.analysis_forecast_current_model),
                listOf(chartMajor(trend.observedMinor, state.baseCurrency), chartMajor(trend.projectedMinor, state.baseCurrency)),
                currentLabels,
                listOf(
                    AnalysisPolicy.money(trend.observedMinor, state.baseCurrency, locale).formatted,
                    AnalysisPolicy.money(trend.projectedMinor, state.baseCurrency, locale).formatted,
                ),
            ),
        )
        historical?.let {
            add(
                LedgerChartSeries(
                    "forecast-historical-v${it.version.value}",
                    stringResource(R.string.analysis_forecast_historical_series),
                    listOf(chartMajor(it.projectedMinor, state.baseCurrency), chartMajor(it.projectedMinor, state.baseCurrency)),
                    currentLabels,
                    List(2) { _ -> AnalysisPolicy.money(it.projectedMinor, state.baseCurrency, locale).formatted },
                ),
            )
        }
    }
    val model = LedgerChartUiModel(
        stringResource(R.string.analysis_forecast_title),
        stringResource(R.string.analysis_forecast_scope, forecast.windowStart.localized(locale), forecast.throughDate.localized(locale)),
        stringResource(R.string.analysis_forecast_summary, amount),
        LedgerChartType.LINE,
        series,
    )
    val observedAmount = AnalysisPolicy.money(trend.observedMinor, state.baseCurrency, locale).formatted
    val averageAmount = AnalysisPolicy.money(trend.dailyAverageMinor, state.baseCurrency, locale).formatted
    val recurrenceAmount = AnalysisPolicy.money(forecast.recurrenceIncludedMinor, state.baseCurrency, locale).formatted
    val forecastRows = buildList {
        add(listOf(trend.windowEndInclusive.localized(locale), observedAmount, stringResource(R.string.analysis_forecast_observed)))
        add(listOf(forecast.throughDate.localized(locale), amount, stringResource(R.string.analysis_forecast_projected)))
        historical?.let {
            add(
                listOf(
                    it.throughDate.localized(locale),
                    AnalysisPolicy.money(it.projectedMinor, state.baseCurrency, locale).formatted,
                    stringResource(R.string.analysis_forecast_historical_series),
                ),
            )
        }
    }
    var tableExpanded by remember(model.title) { mutableStateOf(true) }
    LazyColumn(
        Modifier.fillMaxSize().testTag(LedgerTestTags.FORECAST_DETAIL),
        contentPadding = PaddingValues(LedgerTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.md),
    ) {
        item {
            ChartCard(
                model = model,
                chart = { LedgerLineChart(model, LedgerVicoLineRenderer, Modifier.fillMaxWidth()) },
                dataTable = AccessibleTableUiModel(
                    stringResource(R.string.analysis_forecast_table),
                    listOf(stringResource(R.string.analysis_period), stringResource(R.string.analysis_result), stringResource(R.string.analysis_forecast_model)),
                    forecastRows,
                ),
                tableExpanded = tableExpanded,
                onToggleTable = { tableExpanded = !tableExpanded },
            )
        }
        item {
            LedgerBanner(
                stringResource(
                    R.string.analysis_forecast_version,
                    forecast.version.value,
                    forecast.windowStart.localized(locale),
                    forecast.windowEndInclusive.localized(locale),
                ),
                LedgerBannerVariant.INFO,
            )
        }
        item { LedgerText(stringResource(R.string.analysis_forecast_assumptions_formatted, observedAmount, averageAmount, recurrenceAmount), LedgerTextRole.BODY) }
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

@Composable
private fun AnalysisSaveBar(onSave: () -> Unit, enabled: Boolean) {
    LedgerCard(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm)) {
            LedgerButton(
                stringResource(R.string.analysis_save),
                onSave,
                Modifier.fillMaxWidth().testTag(LedgerTestTags.SAVE),
                enabled = enabled,
            )
        }
    }
}

private fun LocalDate.localized(locale: Locale): String = format(LedgerDateFormatterRuntime.formatter(locale))

private const val DRAG_REORDER_THRESHOLD = 72f
private const val BUILDER_LAST_STEP = 6

private val chartCurrencyCatalog = JvmLegalTenderCurrencyCatalog.create()

private fun chartMajor(minor: Long, currency: CurrencyCode): Double = BigDecimal.valueOf(minor, requireNotNull(chartCurrencyCatalog.find(currency)).fractionDigits).toDouble()
