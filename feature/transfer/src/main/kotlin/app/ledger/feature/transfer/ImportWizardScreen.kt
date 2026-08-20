@file:Suppress(
    "FunctionNaming",
    "LongMethod",
    "LongParameterList",
    "MaxLineLength",
    "TooManyFunctions",
    "ktlint:standard:function-naming",
)

package app.ledger.feature.transfer

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerButton
import app.ledger.core.designsystem.LedgerButtonVariant
import app.ledger.core.designsystem.LedgerCard
import app.ledger.core.designsystem.LedgerChoiceRow
import app.ledger.core.designsystem.LedgerEmptyState
import app.ledger.core.designsystem.LedgerScaffold
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextField
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTabRow
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.LedgerTopAppBar
import app.ledger.core.designsystem.LedgerTopAppBarVariant
import app.ledger.core.designsystem.OperationCapability
import app.ledger.core.designsystem.OperationProgressPanel
import app.ledger.core.designsystem.OperationProgressUiModel
import app.ledger.core.designsystem.LedgerStatusVariant
import app.ledger.core.designsystem.StatusBadge
import app.ledger.core.designsystem.UiErrorCode
import app.ledger.core.money.LocaleNumberFormatter
import app.ledger.transfer.domain.DuplicateResolution
import app.ledger.transfer.domain.ImportWizardStage

enum class ImportModeUi { GENERAL, STRUCTURED }
enum class ImportSourceState { CONTENT, PERMISSION_ERROR }
enum class ImportStructureState { CONTENT, PARSING, PAUSED, CORRUPT_FILE, UNSUPPORTED }
enum class ImportValidationState { VALIDATING, ERRORS, WARNINGS, VALID }
enum class ImportExecutionState { PREPARING, APPLYING_SHADOW, VALIDATING, COMMITTING, CANCEL_REQUESTED, CANCELLED, FAILED, SUCCEEDED }
enum class ImportFxPolicyUi { HISTORICAL_FROM_FILE, MANUAL }
enum class ImportValidationSeverityUi { ERROR, WARNING }
enum class ImportResultOutcomeUi { SUCCESS, CANCELLED, FAILED_PARTIAL_NOT_ALLOWED, ROLLED_BACK }

data class ImportPreviewRowUi(val rowNumber: Long, val summary: String, val status: String)
data class ImportMappingRowUi(val source: String, val target: String?, val sample: String, val valid: Boolean)
data class ImportEntityMappingUi(val type: String, val missingCount: Long, val createMissing: Boolean, val canCreate: Boolean)
data class ImportEntityValueMappingUi(
    val type: String,
    val sourceValue: String,
    val targetLabel: String?,
    val targetOptions: List<String>,
    val createMissing: Boolean,
)
data class ImportFxRowUi(
    val sourceCurrency: String,
    val targetCurrency: String,
    val rate: String?,
    val manualRequired: Boolean,
    val policy: ImportFxPolicyUi = ImportFxPolicyUi.MANUAL,
    val historicalAvailable: Boolean = false,
) {
    val pair: String get() = "$sourceCurrency/$targetCurrency"
}
data class ImportHistoryRowUi(
    val title: String,
    val importedAt: String,
    val rowCount: Long,
    val reversible: Boolean,
    val batchId: String = "",
)
data class ImportDuplicateRowUi(val rowNumber: Long, val matchKind: String, val resolution: DuplicateResolution?)
data class ImportValidationIssueUi(
    val rowNumber: Long?,
    val field: String?,
    val severity: ImportValidationSeverityUi,
    val message: String,
)
data class ImportResultCountsUi(
    val transactions: Long = 0L,
    val accounts: Long = 0L,
    val categories: Long = 0L,
    val merchants: Long = 0L,
)

data class ImportWizardUiState(
    val stage: ImportWizardStage = ImportWizardStage.SOURCE,
    val mode: ImportModeUi = ImportModeUi.GENERAL,
    val sourceState: ImportSourceState = ImportSourceState.CONTENT,
    val structureState: ImportStructureState = ImportStructureState.CONTENT,
    val validationState: ImportValidationState = ImportValidationState.VALID,
    val executionState: ImportExecutionState = ImportExecutionState.PREPARING,
    val sheetNames: List<String> = emptyList(),
    val selectedSheet: String? = null,
    val encoding: String = "UTF-8",
    val headerRowNumber: String = "1",
    val mappings: List<ImportMappingRowUi> = emptyList(),
    val entityMappings: List<ImportEntityMappingUi> = emptyList(),
    val entityValueMappings: List<ImportEntityValueMappingUi> = emptyList(),
    val fxRows: List<ImportFxRowUi> = emptyList(),
    val previewRowCount: Int = 0,
    val previewRow: (Int) -> ImportPreviewRowUi = { index -> ImportPreviewRowUi(index + 1L, "", "") },
    val errorCount: Long = 0,
    val warningCount: Long = 0,
    val duplicateCount: Long = 0,
    val duplicates: List<ImportDuplicateRowUi> = emptyList(),
    val missingEntityCount: Long = 0,
    val validationIssues: List<ImportValidationIssueUi> = emptyList(),
    val processedRows: Long = 0,
    val totalRows: Long? = null,
    val history: List<ImportHistoryRowUi> = emptyList(),
    val showHistory: Boolean = false,
    val temporaryCleanupComplete: Boolean = false,
    val resultCounts: ImportResultCountsUi = ImportResultCountsUi(),
    val resultOutcome: ImportResultOutcomeUi = ImportResultOutcomeUi.SUCCESS,
)

sealed interface ImportWizardScreenAction {
    data object Back : ImportWizardScreenAction
    data class SourceSelected(val uri: Uri) : ImportWizardScreenAction
    data class ModeSelected(val mode: ImportModeUi) : ImportWizardScreenAction
    data class SheetSelected(val sheet: String) : ImportWizardScreenAction
    data class EncodingChanged(val encoding: String) : ImportWizardScreenAction
    data class HeaderRowChanged(val value: String) : ImportWizardScreenAction
    data class CycleFieldMapping(val sourceField: String) : ImportWizardScreenAction
    data class CreateMissingChanged(val entity: String, val enabled: Boolean) : ImportWizardScreenAction
    data class FxRateChanged(val currency: String, val value: String) : ImportWizardScreenAction
    data class DuplicateResolved(val row: Long, val resolution: DuplicateResolution) : ImportWizardScreenAction
    data object Previous : ImportWizardScreenAction
    data object Next : ImportWizardScreenAction
    data object Pause : ImportWizardScreenAction
    data object Cancel : ImportWizardScreenAction
    data object Retry : ImportWizardScreenAction
    data object Rollback : ImportWizardScreenAction
    data object OpenJournal : ImportWizardScreenAction
}

private class ImportWizardActions(
    val onBack: () -> Unit,
    val onSourceSelected: (Uri) -> Unit,
    val onModeSelected: (ImportModeUi) -> Unit,
    val onSheetSelected: (String) -> Unit,
    val onEncodingChanged: (String) -> Unit,
    val onHeaderRowChanged: (String) -> Unit,
    val onCycleFieldMapping: (String) -> Unit,
    val onCreateMissingChanged: (String, Boolean) -> Unit,
    val onCycleEntityMapping: (String, String) -> Unit,
    val onFxPolicyChanged: (String, ImportFxPolicyUi) -> Unit,
    val onFxRateChanged: (String, String) -> Unit,
    val onDuplicateResolved: (Long, DuplicateResolution) -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onPause: () -> Unit,
    val onCancel: () -> Unit,
    val onRetry: () -> Unit,
    val onRollback: () -> Unit,
    val onOpenJournal: () -> Unit,
    val onShowHistory: () -> Unit = {},
    val onViewValidationIssues: () -> Unit = {},
    val onCleanupTemporary: () -> Unit = {},
    val onViewHistoryResult: (String) -> Unit = {},
    val onRollbackHistory: (String) -> Unit = {},
)

@Composable
fun ImportWizardScreen(state: ImportWizardUiState, onAction: (ImportWizardScreenAction) -> Unit) {
    val actions = ImportWizardActions(
        onBack = { onAction(ImportWizardScreenAction.Back) },
        onSourceSelected = { onAction(ImportWizardScreenAction.SourceSelected(it)) },
        onModeSelected = { onAction(ImportWizardScreenAction.ModeSelected(it)) },
        onSheetSelected = { onAction(ImportWizardScreenAction.SheetSelected(it)) },
        onEncodingChanged = { onAction(ImportWizardScreenAction.EncodingChanged(it)) },
        onHeaderRowChanged = { onAction(ImportWizardScreenAction.HeaderRowChanged(it)) },
        onCycleFieldMapping = { onAction(ImportWizardScreenAction.CycleFieldMapping(it)) },
        onCreateMissingChanged = { entity, enabled -> onAction(ImportWizardScreenAction.CreateMissingChanged(entity, enabled)) },
        onFxRateChanged = { currency, value -> onAction(ImportWizardScreenAction.FxRateChanged(currency, value)) },
        onDuplicateResolved = { row, resolution -> onAction(ImportWizardScreenAction.DuplicateResolved(row, resolution)) },
        onPrevious = { onAction(ImportWizardScreenAction.Previous) },
        onNext = { onAction(ImportWizardScreenAction.Next) },
        onPause = { onAction(ImportWizardScreenAction.Pause) },
        onCancel = { onAction(ImportWizardScreenAction.Cancel) },
        onRetry = { onAction(ImportWizardScreenAction.Retry) },
        onRollback = { onAction(ImportWizardScreenAction.Rollback) },
        onOpenJournal = { onAction(ImportWizardScreenAction.OpenJournal) },
    )
    LedgerScaffold(
        Modifier.fillMaxSize().importRootTag(state),
        topBar = { LedgerTopAppBar(stringResource(R.string.import_title), LedgerTopAppBarVariant.BACK, onNavigation = actions.onBack) },
        fixedAction = {
            if (!state.showHistory && state.stage !in setOf(ImportWizardStage.EXECUTION, ImportWizardStage.RESULT)) {
                Row(horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                    if (state.stage != ImportWizardStage.SOURCE) {
                        LedgerButton(stringResource(R.string.import_previous), actions.onPrevious, variant = LedgerButtonVariant.SECONDARY)
                    }
                    LedgerButton(
                        stringResource(if (state.stage == ImportWizardStage.CONFIRMATION) R.string.import_commit else R.string.import_next),
                        actions.onNext,
                        enabled = state.canAdvance(),
                    )
                }
            }
        },
    ) { padding ->
        if (state.showHistory) {
            ImportHistoryContent(state, actions, Modifier.padding(padding))
        } else {
            when (state.stage) {
                ImportWizardStage.SOURCE -> ImportSourceContent(state, actions, Modifier.padding(padding))
                ImportWizardStage.STRUCTURE -> ImportStructureContent(state, actions, Modifier.padding(padding))
                ImportWizardStage.FIELD_MAPPING -> ImportFieldMappingContent(state, actions, Modifier.padding(padding))
                ImportWizardStage.ENTITY_MAPPING -> ImportEntityMappingContent(state, actions, Modifier.padding(padding))
                ImportWizardStage.FX -> ImportFxContent(state, actions, Modifier.padding(padding))
                ImportWizardStage.VALIDATION -> ImportValidationContent(state, actions, Modifier.padding(padding))
                ImportWizardStage.CONFIRMATION -> ImportConfirmationContent(state, Modifier.padding(padding))
                ImportWizardStage.EXECUTION -> ImportExecutionContent(state, actions, Modifier.padding(padding))
                ImportWizardStage.RESULT -> ImportResultContent(state, actions, Modifier.padding(padding))
            }
        }
    }
}

@Composable
private fun ImportSourceContent(state: ImportWizardUiState, actions: ImportWizardActions, modifier: Modifier) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            actions.onSourceSelected(uri)
        }
    }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        StageHeading(R.string.import_source_heading, R.string.import_source_supporting)
        if (state.sourceState == ImportSourceState.PERMISSION_ERROR) {
            LedgerBanner(stringResource(R.string.import_permission_error), LedgerBannerVariant.DANGER)
        }
        LedgerCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(LedgerTheme.spacing.sm)) {
                LedgerText(stringResource(R.string.import_mode), LedgerTextRole.SECTION)
                LedgerButton(
                    stringResource(R.string.import_mode_general),
                    { actions.onModeSelected(ImportModeUi.GENERAL) },
                    variant = if (state.mode == ImportModeUi.GENERAL) LedgerButtonVariant.TONAL else LedgerButtonVariant.TEXT,
                )
                LedgerButton(
                    stringResource(R.string.import_mode_structured),
                    { actions.onModeSelected(ImportModeUi.STRUCTURED) },
                    variant = if (state.mode == ImportModeUi.STRUCTURED) LedgerButtonVariant.TONAL else LedgerButtonVariant.TEXT,
                )
            }
        }
        LedgerButton(
            stringResource(R.string.import_choose_file),
            { picker.launch(arrayOf("text/csv", "text/comma-separated-values", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) },
            Modifier.fillMaxWidth(),
        )
        LedgerButton(
            stringResource(R.string.import_recent_history),
            actions.onShowHistory,
            Modifier.fillMaxWidth(),
            LedgerButtonVariant.SECONDARY,
        )
    }
}

@Composable
private fun ImportStructureContent(state: ImportWizardUiState, actions: ImportWizardActions, modifier: Modifier) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        StageHeading(R.string.import_structure_heading, R.string.import_structure_supporting)
        when (state.structureState) {
            ImportStructureState.PARSING -> {
                LedgerBanner(stringResource(R.string.import_parsing), LedgerBannerVariant.INFO)
                OperationProgressPanel(
                    OperationProgressUiModel(
                        stringResource(R.string.import_operation_name),
                        stringResource(R.string.import_parsing),
                        stringResource(R.string.import_processed_unknown, state.processedRows),
                        null,
                        OperationCapability.PAUSABLE,
                        stringResource(R.string.import_safe_cancel),
                    ),
                    onCancel = actions.onCancel,
                    onPause = actions.onPause,
                )
            }
            ImportStructureState.PAUSED -> {
                LedgerBanner(stringResource(R.string.import_paused), LedgerBannerVariant.INFO)
                LedgerButton(stringResource(R.string.import_resume), actions.onPause)
                LedgerButton(stringResource(R.string.import_cancel), actions.onCancel, variant = LedgerButtonVariant.SECONDARY)
            }
            ImportStructureState.CORRUPT_FILE -> LedgerBanner(stringResource(R.string.import_corrupt), LedgerBannerVariant.DANGER)
            ImportStructureState.UNSUPPORTED -> LedgerBanner(stringResource(R.string.import_unsupported), LedgerBannerVariant.DANGER)
            ImportStructureState.CONTENT -> Unit
        }
        LedgerCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(LedgerTheme.spacing.sm)) {
                LedgerText(stringResource(R.string.import_sheets), LedgerTextRole.SECTION)
                state.sheetNames.forEach { name ->
                    LedgerButton(
                        if (name == state.selectedSheet) "✓ $name" else name,
                        { actions.onSheetSelected(name) },
                        variant = if (name == state.selectedSheet) LedgerButtonVariant.TONAL else LedgerButtonVariant.TEXT,
                    )
                }
                LedgerTextField(
                    state.encoding,
                    actions.onEncodingChanged,
                    stringResource(R.string.import_encoding),
                    Modifier.fillMaxWidth(),
                    supportingText = stringResource(R.string.import_encoding_value, state.encoding),
                )
                LedgerTextField(
                    state.headerRowNumber,
                    actions.onHeaderRowChanged,
                    stringResource(R.string.import_header_row),
                    Modifier.fillMaxWidth(),
                    required = true,
                    keyboardType = KeyboardType.Number,
                )
            }
        }
        VirtualPreview(state)
    }
}

@Composable
private fun ImportFieldMappingContent(state: ImportWizardUiState, actions: ImportWizardActions, modifier: Modifier) {
    Column(modifier.fillMaxSize()) {
        StageHeading(R.string.import_mapping_heading, R.string.import_mapping_supporting)
        val missing = state.mappings.count { !it.valid }
        LedgerBanner(
            stringResource(R.string.import_required_status, state.mappings.size - missing, missing),
            if (missing == 0) LedgerBannerVariant.INFO else LedgerBannerVariant.DANGER,
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            items(state.mappings, key = ImportMappingRowUi::source) { mapping ->
                LedgerCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(LedgerTheme.spacing.sm)) {
                        LedgerText(
                            "${mapping.source} → ${mapping.target?.importTargetFieldLabel() ?: stringResource(R.string.import_not_mapped)}",
                            LedgerTextRole.BODY,
                        )
                        LedgerText(mapping.sample, LedgerTextRole.SUPPORTING)
                        LedgerButton(
                            stringResource(R.string.import_change_mapping),
                            { actions.onCycleFieldMapping(mapping.source) },
                            variant = LedgerButtonVariant.TEXT,
                        )
                        if (!mapping.valid) LedgerBanner(stringResource(R.string.import_required_missing), LedgerBannerVariant.DANGER)
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportEntityMappingContent(state: ImportWizardUiState, actions: ImportWizardActions, modifier: Modifier) {
    Column(modifier.fillMaxSize()) {
        StageHeading(R.string.import_entity_heading, R.string.import_entity_supporting)
        LedgerBanner(
            stringResource(
                R.string.import_conflict_count_summary,
                state.entityValueMappings.count { it.createMissing },
                state.entityValueMappings.count { !it.createMissing },
            ),
            LedgerBannerVariant.INFO,
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            items(state.entityMappings, key = ImportEntityMappingUi::type) { mapping ->
                LedgerCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(LedgerTheme.spacing.sm)) {
                        LedgerText(mapping.type.importTargetFieldLabel(), LedgerTextRole.SECTION)
                        LedgerText(stringResource(R.string.import_missing_count, mapping.missingCount), LedgerTextRole.BODY)
                        LedgerText(
                            if (mapping.createMissing) stringResource(R.string.import_create_missing) else stringResource(R.string.import_map_existing),
                            LedgerTextRole.SUPPORTING,
                        )
                        LedgerButton(
                            if (mapping.createMissing) stringResource(R.string.import_map_existing) else stringResource(R.string.import_create_missing),
                            { actions.onCreateMissingChanged(mapping.type, !mapping.createMissing) },
                            variant = LedgerButtonVariant.TEXT,
                            enabled = mapping.canCreate,
                        )
                    }
                }
            }
            if (state.entityValueMappings.isNotEmpty()) {
                item { LedgerText(stringResource(R.string.import_source_value_mappings), LedgerTextRole.SECTION) }
            }
            items(state.entityValueMappings, key = { "${it.type}:${it.sourceValue}" }) { mapping ->
                LedgerCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm)) {
                        LedgerText(mapping.type.importTargetFieldLabel(), LedgerTextRole.LABEL)
                        LedgerText(
                            "${mapping.sourceValue} → ${mapping.targetLabel ?: stringResource(R.string.import_create_new_value)}",
                            LedgerTextRole.BODY,
                        )
                        if (mapping.targetOptions.isNotEmpty()) {
                            LedgerButton(
                                stringResource(R.string.import_cycle_existing_mapping),
                                { actions.onCycleEntityMapping(mapping.type, mapping.sourceValue) },
                                variant = LedgerButtonVariant.TEXT,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportFxContent(state: ImportWizardUiState, actions: ImportWizardActions, modifier: Modifier) {
    Column(modifier.fillMaxSize()) {
        StageHeading(R.string.import_fx_heading, R.string.import_fx_supporting)
        LedgerText(stringResource(R.string.import_currency_mapping_list), LedgerTextRole.SECTION)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            items(state.fxRows, key = ImportFxRowUi::pair) { fx ->
                LedgerCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(LedgerTheme.spacing.sm)) {
                        LedgerText(fx.pair, LedgerTextRole.SECTION)
                        LedgerText(
                            stringResource(R.string.import_currency_mapping, fx.sourceCurrency, fx.targetCurrency),
                            LedgerTextRole.BODY,
                        )
                        LedgerChoiceRow(
                            stringResource(R.string.import_rate_policy_historical),
                            fx.policy == ImportFxPolicyUi.HISTORICAL_FROM_FILE,
                            { actions.onFxPolicyChanged(fx.sourceCurrency, ImportFxPolicyUi.HISTORICAL_FROM_FILE) },
                            supportingText = stringResource(
                                if (fx.historicalAvailable) R.string.import_rate_policy_historical_available else R.string.import_rate_policy_historical_missing,
                            ),
                            enabled = fx.historicalAvailable,
                        )
                        LedgerChoiceRow(
                            stringResource(R.string.import_rate_policy_manual),
                            fx.policy == ImportFxPolicyUi.MANUAL,
                            { actions.onFxPolicyChanged(fx.sourceCurrency, ImportFxPolicyUi.MANUAL) },
                        )
                        if (fx.policy == ImportFxPolicyUi.MANUAL) {
                            LedgerTextField(
                                fx.rate.orEmpty(),
                                { actions.onFxRateChanged(fx.sourceCurrency, it) },
                                stringResource(R.string.import_fx_rate, fx.pair),
                                Modifier.fillMaxWidth(),
                                required = true,
                                keyboardType = KeyboardType.Decimal,
                                errorText = stringResource(R.string.import_manual_rate_required).takeIf { fx.manualRequired },
                            )
                            if (fx.manualRequired) LedgerBanner(stringResource(R.string.import_manual_rate_required), LedgerBannerVariant.WARNING)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportValidationContent(state: ImportWizardUiState, actions: ImportWizardActions, modifier: Modifier) {
    var selectedIssueTab by remember { mutableStateOf(ImportValidationSeverityUi.ERROR) }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        StageHeading(R.string.import_validation_heading, R.string.import_validation_supporting)
        val banner = when (state.validationState) {
            ImportValidationState.VALIDATING -> R.string.import_validating to LedgerBannerVariant.INFO
            ImportValidationState.ERRORS -> R.string.import_validation_errors to LedgerBannerVariant.DANGER
            ImportValidationState.WARNINGS -> R.string.import_validation_warnings to LedgerBannerVariant.WARNING
            ImportValidationState.VALID -> R.string.import_validation_valid to LedgerBannerVariant.INFO
        }
        LedgerBanner(stringResource(banner.first), banner.second)
        LedgerText(stringResource(R.string.import_validation_summary), LedgerTextRole.SECTION)
        SummaryCards(state)
        LedgerTabRow(
            selectedIndex = if (selectedIssueTab == ImportValidationSeverityUi.ERROR) 0 else 1,
            labels = listOf(
                stringResource(R.string.import_error_tab, state.errorCount),
                stringResource(R.string.import_warning_tab, state.warningCount),
            ),
            onSelected = { selectedIssueTab = if (it == 0) ImportValidationSeverityUi.ERROR else ImportValidationSeverityUi.WARNING },
        )
        val visibleIssues = state.validationIssues.filter { it.severity == selectedIssueTab }
        if (visibleIssues.isEmpty()) {
            LedgerText(stringResource(R.string.import_no_issues_in_tab), LedgerTextRole.SUPPORTING)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                itemsIndexed(visibleIssues, key = { index, issue -> "${issue.rowNumber}:${issue.field}:$index" }) { _, issue ->
                    LedgerCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm)) {
                            LedgerText(
                                issue.rowNumber?.let { stringResource(R.string.import_issue_row, it) }
                                    ?: stringResource(R.string.import_issue_general),
                                LedgerTextRole.SECTION,
                            )
                            issue.field?.let {
                                LedgerText(stringResource(R.string.import_issue_field, it.importTargetFieldLabel()), LedgerTextRole.LABEL)
                            }
                            LedgerText(issue.message.importValidationMessage(), LedgerTextRole.BODY)
                        }
                    }
                }
            }
        }
        if (state.duplicates.isNotEmpty()) {
            LedgerText(stringResource(R.string.import_duplicate_candidates), LedgerTextRole.SECTION)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                items(state.duplicates, key = ImportDuplicateRowUi::rowNumber) { duplicate ->
                    LedgerCard(Modifier.fillMaxWidth().testTag("import_duplicate_row")) {
                        Column(Modifier.padding(LedgerTheme.spacing.sm)) {
                            LedgerText(stringResource(R.string.import_duplicate_row, duplicate.rowNumber), LedgerTextRole.BODY)
                            LedgerText(duplicate.matchKind.importDuplicateMatchLabel(), LedgerTextRole.SUPPORTING)
                            Row(horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                                LedgerButton(
                                    stringResource(R.string.import_duplicate_skip),
                                    { actions.onDuplicateResolved(duplicate.rowNumber, DuplicateResolution.SKIP) },
                                    Modifier.testTag("import_duplicate_skip"),
                                    variant = if (duplicate.resolution == DuplicateResolution.SKIP) {
                                        LedgerButtonVariant.TONAL
                                    } else {
                                        LedgerButtonVariant.TEXT
                                    },
                                )
                                LedgerButton(
                                    stringResource(R.string.import_duplicate_anyway),
                                    { actions.onDuplicateResolved(duplicate.rowNumber, DuplicateResolution.IMPORT_ANYWAY) },
                                    Modifier.testTag("import_duplicate_anyway"),
                                    variant = if (duplicate.resolution == DuplicateResolution.IMPORT_ANYWAY) {
                                        LedgerButtonVariant.TONAL
                                    } else {
                                        LedgerButtonVariant.TEXT
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (state.temporaryCleanupComplete) {
            LedgerText(stringResource(R.string.import_cleanup_complete), LedgerTextRole.SUPPORTING)
        }
        VirtualPreview(state)
    }
}

@Composable
private fun ImportConfirmationContent(state: ImportWizardUiState, modifier: Modifier) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        StageHeading(R.string.import_confirm_heading, R.string.import_confirm_supporting)
        SummaryCards(state)
        LedgerText(stringResource(R.string.import_entity_creation_summary), LedgerTextRole.SECTION)
        state.entityMappings.filter { it.createMissing && it.missingCount > 0L }.forEach { mapping ->
            LedgerText(
                stringResource(R.string.import_entity_creation_item, mapping.type.importTargetFieldLabel(), mapping.missingCount),
                LedgerTextRole.BODY,
            )
        }
        if (state.entityMappings.none { it.createMissing && it.missingCount > 0L }) {
            LedgerText(stringResource(R.string.import_entity_creation_none), LedgerTextRole.SUPPORTING)
        }
        LedgerCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(LedgerTheme.spacing.sm)) {
                LedgerText(stringResource(R.string.import_atomic_title), LedgerTextRole.SECTION)
                LedgerText(stringResource(R.string.import_atomic_explanation), LedgerTextRole.BODY)
                LedgerText(stringResource(R.string.import_no_split), LedgerTextRole.SUPPORTING)
            }
        }
        VirtualPreview(state)
    }
}

@Composable
private fun ImportExecutionContent(state: ImportWizardUiState, actions: ImportWizardActions, modifier: Modifier) {
    val phase = when (state.executionState) {
        ImportExecutionState.PREPARING -> stringResource(R.string.import_phase_preparing)
        ImportExecutionState.APPLYING_SHADOW -> stringResource(R.string.import_phase_shadow)
        ImportExecutionState.VALIDATING -> stringResource(R.string.import_phase_validating)
        ImportExecutionState.COMMITTING -> stringResource(R.string.import_phase_committing)
        ImportExecutionState.CANCEL_REQUESTED -> stringResource(R.string.import_phase_cancel_requested)
        ImportExecutionState.CANCELLED -> stringResource(R.string.import_phase_cancelled)
        ImportExecutionState.FAILED -> stringResource(R.string.import_phase_failed)
        ImportExecutionState.SUCCEEDED -> stringResource(R.string.import_phase_succeeded)
    }
    val total = state.totalRows
    val progress = total?.takeIf { it > 0L }?.let { (state.processedRows.toDouble() / it.toDouble()).toFloat().coerceIn(0f, 1f) }
    val nonCancelable = state.executionState in setOf(ImportExecutionState.COMMITTING, ImportExecutionState.SUCCEEDED)
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        StageHeading(R.string.import_execution_heading, R.string.import_execution_supporting)
        ImportPhaseTimeline(state.executionState)
        OperationProgressPanel(
            OperationProgressUiModel(
                stringResource(R.string.import_operation_name),
                phase,
                if (total == null) {
                    stringResource(R.string.import_processed_unknown, state.processedRows)
                } else {
                    stringResource(R.string.import_processed_total, state.processedRows, total)
                },
                progress,
                if (nonCancelable) OperationCapability.NON_CANCELABLE_COMMIT else OperationCapability.PAUSABLE,
                if (nonCancelable) stringResource(R.string.import_safe_commit) else stringResource(R.string.import_safe_cancel),
                UiErrorCode("IMPORT_FAILED").takeIf { state.executionState == ImportExecutionState.FAILED },
            ),
            onCancel = actions.onCancel.takeUnless { nonCancelable },
            onPause = actions.onPause.takeUnless { nonCancelable },
            onRetry = actions.onRetry.takeIf { state.executionState == ImportExecutionState.FAILED },
        )
        if (state.executionState in setOf(ImportExecutionState.CANCEL_REQUESTED, ImportExecutionState.CANCELLED, ImportExecutionState.FAILED)) {
            LedgerBanner(
                stringResource(if (state.temporaryCleanupComplete) R.string.import_cleanup_complete else R.string.import_cleanup_pending),
                if (state.temporaryCleanupComplete) LedgerBannerVariant.INFO else LedgerBannerVariant.WARNING,
            )
        }
        if (state.executionState == ImportExecutionState.FAILED) {
            LedgerButton(stringResource(R.string.import_view_errors), actions.onViewValidationIssues, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY)
            LedgerButton(
                stringResource(R.string.import_cleanup_temporary),
                actions.onCleanupTemporary,
                Modifier.fillMaxWidth(),
                LedgerButtonVariant.DANGER,
                enabled = !state.temporaryCleanupComplete,
            )
        }
    }
}

@Composable
private fun ImportResultContent(state: ImportWizardUiState, actions: ImportWizardActions, modifier: Modifier) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        StageHeading(R.string.import_result_heading, R.string.import_result_supporting)
        LedgerBanner(
            when (state.resultOutcome) {
                ImportResultOutcomeUi.SUCCESS -> stringResource(R.string.import_result_success)
                ImportResultOutcomeUi.CANCELLED -> stringResource(R.string.import_result_cancelled)
                ImportResultOutcomeUi.FAILED_PARTIAL_NOT_ALLOWED -> stringResource(R.string.import_result_partial_not_allowed)
                ImportResultOutcomeUi.ROLLED_BACK -> stringResource(R.string.import_result_rolled_back)
            },
            if (state.resultOutcome in setOf(ImportResultOutcomeUi.SUCCESS, ImportResultOutcomeUi.CANCELLED, ImportResultOutcomeUi.ROLLED_BACK)) {
                LedgerBannerVariant.INFO
            } else {
                LedgerBannerVariant.DANGER
            },
        )
        LedgerText(stringResource(R.string.import_actual_result_counts), LedgerTextRole.SECTION)
        ResultCountCards(state.resultCounts)
        if (state.temporaryCleanupComplete) LedgerText(stringResource(R.string.import_cleanup_complete), LedgerTextRole.SUPPORTING)
        if (state.resultOutcome == ImportResultOutcomeUi.SUCCESS) {
            Row(horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                LedgerButton(stringResource(R.string.import_open_journal), actions.onOpenJournal)
                LedgerButton(stringResource(R.string.import_rollback_batch), actions.onRollback, variant = LedgerButtonVariant.SECONDARY)
            }
        }
    }
}

@Composable
private fun ImportHistoryContent(state: ImportWizardUiState, actions: ImportWizardActions, modifier: Modifier) {
    Column(modifier.fillMaxSize()) {
        StageHeading(R.string.import_history_heading, R.string.import_history_supporting)
        if (state.history.isEmpty()) {
            LedgerEmptyState(stringResource(R.string.import_history_empty), stringResource(R.string.import_history_empty_supporting), stringResource(R.string.import_choose_file), actions.onPrevious)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                items(state.history, key = { "${it.title}:${it.importedAt}" }) { item ->
                    LedgerCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(LedgerTheme.spacing.sm)) {
                            LedgerText(item.title.ifBlank { stringResource(R.string.import_history_batch) }, LedgerTextRole.SECTION)
                            LedgerText(item.importedAt, LedgerTextRole.SUPPORTING)
                            LedgerText(stringResource(R.string.import_rows_count, item.rowCount), LedgerTextRole.BODY)
                            StatusBadge(
                                if (item.reversible) stringResource(R.string.import_reversible) else stringResource(R.string.import_not_reversible),
                                if (item.reversible) LedgerStatusVariant.POSITIVE else LedgerStatusVariant.ARCHIVED,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                                LedgerButton(
                                    stringResource(R.string.import_view_result),
                                    { actions.onViewHistoryResult(item.batchId) },
                                    variant = LedgerButtonVariant.TEXT,
                                    enabled = item.batchId.isNotBlank(),
                                )
                                LedgerButton(
                                    stringResource(R.string.import_rollback_batch),
                                    { actions.onRollbackHistory(item.batchId) },
                                    variant = LedgerButtonVariant.SECONDARY,
                                    enabled = item.reversible && item.batchId.isNotBlank(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VirtualPreview(state: ImportWizardUiState) {
    val locale = androidx.compose.ui.platform.LocalLocale.current.platformLocale
    LedgerText(stringResource(R.string.import_preview), LedgerTextRole.SECTION)
    LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
        items(state.previewRowCount, key = { index -> state.previewRow(index).rowNumber }) { index ->
            val row = state.previewRow(index)
            val status = row.status.importPreviewStatusLabel()
            val issues = state.validationIssues.filter { it.rowNumber == row.rowNumber }
                .map { issue ->
                    listOfNotNull(
                        issue.field?.importTargetFieldLabel(),
                        issue.message.importValidationMessage(),
                    ).joinToString(": ")
                }.joinToString("; ")
            LedgerCard(
                Modifier.fillMaxWidth().clearAndSetSemantics {
                    text = AnnotatedString(
                        listOf(LocaleNumberFormatter.integer(row.rowNumber, locale), row.summary, status, issues)
                            .filter(String::isNotBlank)
                            .joinToString(", "),
                    )
                },
            ) {
                Column(Modifier.padding(LedgerTheme.spacing.sm)) {
                    LedgerText(stringResource(R.string.import_row_number, row.rowNumber), LedgerTextRole.LABEL)
                    LedgerText(row.summary, LedgerTextRole.BODY, Modifier.clearAndSetSemantics { })
                    LedgerText(status, LedgerTextRole.SUPPORTING)
                }
            }
        }
    }
}

@Composable
private fun ResultCountCards(counts: ImportResultCountsUi) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            SummaryCard(stringResource(R.string.import_result_transactions), counts.transactions, Modifier.weight(1f))
            SummaryCard(stringResource(R.string.import_result_accounts), counts.accounts, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            SummaryCard(stringResource(R.string.import_result_categories), counts.categories, Modifier.weight(1f))
            SummaryCard(stringResource(R.string.import_result_merchants), counts.merchants, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ImportPhaseTimeline(current: ImportExecutionState) {
    val phases = listOf(
        ImportExecutionState.PREPARING to R.string.import_phase_preparing,
        ImportExecutionState.APPLYING_SHADOW to R.string.import_phase_shadow,
        ImportExecutionState.VALIDATING to R.string.import_phase_validating,
        ImportExecutionState.COMMITTING to R.string.import_phase_committing,
        ImportExecutionState.SUCCEEDED to R.string.import_phase_succeeded,
    )
    val currentIndex = phases.indexOfFirst { it.first == current }.takeIf { it >= 0 } ?: when (current) {
        ImportExecutionState.CANCEL_REQUESTED, ImportExecutionState.CANCELLED, ImportExecutionState.FAILED ->
            phases.indexOfLast { it.first != ImportExecutionState.SUCCEEDED }.coerceAtLeast(0)
        else -> 0
    }
    LedgerText(stringResource(R.string.import_phase_timeline), LedgerTextRole.SECTION)
    phases.forEachIndexed { index, (_, label) ->
        val status = when {
            index < currentIndex -> R.string.import_phase_complete_status
            index == currentIndex -> R.string.import_phase_current_status
            else -> R.string.import_phase_pending_status
        }
        LedgerText("${stringResource(label)} · ${stringResource(status)}", LedgerTextRole.BODY)
    }
}

private fun ImportWizardUiState.canAdvance(): Boolean = when (stage) {
    ImportWizardStage.SOURCE -> false
    ImportWizardStage.STRUCTURE -> structureState == ImportStructureState.CONTENT && headerRowNumber.toLongOrNull()?.let { it > 0L } == true
    ImportWizardStage.FIELD_MAPPING -> mappings.isNotEmpty() && mappings.none { !it.valid }
    ImportWizardStage.ENTITY_MAPPING -> entityValueMappings.none { !it.createMissing && it.targetLabel == null }
    ImportWizardStage.FX -> fxRows.all { row ->
        when (row.policy) {
            ImportFxPolicyUi.HISTORICAL_FROM_FILE -> row.historicalAvailable
            ImportFxPolicyUi.MANUAL -> !row.manualRequired
        }
    }
    ImportWizardStage.VALIDATION -> errorCount == 0L && duplicates.all { it.resolution != null }
    ImportWizardStage.CONFIRMATION -> errorCount == 0L
    ImportWizardStage.EXECUTION, ImportWizardStage.RESULT -> false
}

@Composable
private fun SummaryCards(state: ImportWizardUiState) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            SummaryCard(stringResource(R.string.import_errors), state.errorCount, Modifier.weight(1f))
            SummaryCard(stringResource(R.string.import_warnings), state.warningCount, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            SummaryCard(stringResource(R.string.import_duplicates), state.duplicateCount, Modifier.weight(1f))
            SummaryCard(stringResource(R.string.import_missing_entities), state.missingEntityCount, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SummaryCard(label: String, count: Long, modifier: Modifier = Modifier) {
    LedgerCard(modifier) {
        Column(Modifier.padding(LedgerTheme.spacing.xs)) {
            LedgerText(LocaleNumberFormatter.integer(count, app.ledger.core.designsystem.LocalLocale.current.platformLocale), LedgerTextRole.SECTION)
            LedgerText(label, LedgerTextRole.LABEL)
        }
    }
}

@Composable
private fun String.importTargetFieldLabel(): String = stringResource(
    when (this) {
        "TRANSACTION_KIND" -> R.string.import_field_transaction_kind
        "CATEGORY" -> R.string.import_field_category
        "AMOUNT_EXPRESSION" -> R.string.import_field_amount
        "CURRENCY" -> R.string.import_field_currency
        "ACCOUNT" -> R.string.import_field_account
        "CARD" -> R.string.import_field_card
        "MERCHANT" -> R.string.import_field_merchant
        "OCCURRED_AT" -> R.string.import_field_occurred_at
        "ZONE_ID" -> R.string.import_field_zone
        "PROJECT" -> R.string.import_field_project
        "NOTE" -> R.string.import_field_note
        "ATTACHMENT" -> R.string.import_field_attachment
        "REFUND_REFERENCE" -> R.string.import_field_refund_reference
        "INSTALLMENT" -> R.string.import_field_installment
        "SETTLEMENT_SHARE" -> R.string.import_field_settlement_share
        "LOCATION" -> R.string.import_field_location
        "PAYER" -> R.string.import_field_payer
        "PAYEE" -> R.string.import_field_payee
        "FX_RATE" -> R.string.import_field_fx_rate
        else -> R.string.import_field_unknown
    },
)

@Composable
private fun String.importDuplicateMatchLabel(): String = stringResource(
    when (this) {
        "EXACT_SOURCE_REFERENCE" -> R.string.import_duplicate_exact_reference
        "CONTENT_HASH" -> R.string.import_duplicate_content_hash
        "DATE_AMOUNT_ACCOUNT" -> R.string.import_duplicate_date_amount_account
        else -> R.string.import_duplicate_unknown
    },
)

@Composable
private fun String.importValidationMessage(): String = stringResource(
    when (this) {
        "DUPLICATE_REQUIRES_RESOLUTION" -> R.string.import_validation_duplicate_resolution
        "ENTITY_MAPPING_REQUIRED" -> R.string.import_validation_entity_mapping
        "FIELD_TRANSFORMATION_INVALID" -> R.string.import_validation_transformation
        "FX_HISTORICAL_RATE_REQUIRED" -> R.string.import_validation_historical_rate
        "FX_MANUAL_RATE_REQUIRED" -> R.string.import_validation_manual_rate
        "MULTIPLE_SOURCE_COLUMNS_FOR_SINGLE_FIELD" -> R.string.import_validation_multiple_columns
        "TRANSACTION_KIND_UNSUPPORTED" -> R.string.import_validation_transaction_kind
        "IMPORT_VALIDATION_FAILED" -> R.string.import_validation_failed_explanation
        else -> R.string.import_validation_unknown
    },
)

@Composable
private fun String.importPreviewStatusLabel(): String = stringResource(
    if (this == "READY") R.string.import_preview_ready else R.string.import_preview_status_unknown,
)

@Composable
private fun StageHeading(title: Int, supporting: Int) {
    LedgerText(stringResource(title), LedgerTextRole.TITLE)
    LedgerText(stringResource(supporting), LedgerTextRole.SUPPORTING)
}

private fun Modifier.importRootTag(state: ImportWizardUiState): Modifier = when {
    state.showHistory -> testTag("IMP-010")
    state.stage == ImportWizardStage.SOURCE -> testTag("IMP-001")
    state.stage == ImportWizardStage.STRUCTURE -> testTag("IMP-002")
    state.stage == ImportWizardStage.FIELD_MAPPING -> testTag("IMP-003")
    state.stage == ImportWizardStage.ENTITY_MAPPING -> testTag("IMP-004")
    state.stage == ImportWizardStage.FX -> testTag("IMP-005")
    state.stage == ImportWizardStage.VALIDATION -> testTag("IMP-006")
    state.stage == ImportWizardStage.CONFIRMATION -> testTag("IMP-007")
    state.stage == ImportWizardStage.EXECUTION -> testTag("IMP-008")
    else -> testTag("IMP-009")
}
