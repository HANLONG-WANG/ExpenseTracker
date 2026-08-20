@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "MagicNumber", "TooManyFunctions", "ktlint:standard:function-naming")

package app.ledger.feature.transfer

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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerButton
import app.ledger.core.designsystem.LedgerButtonVariant
import app.ledger.core.designsystem.LedgerCard
import app.ledger.core.designsystem.LedgerChip
import app.ledger.core.designsystem.LedgerScaffold
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextField
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.LedgerTopAppBar
import app.ledger.core.designsystem.LedgerTopAppBarVariant
import app.ledger.core.designsystem.OperationCapability
import app.ledger.core.designsystem.OperationProgressPanel
import app.ledger.core.designsystem.OperationProgressUiModel
import app.ledger.core.designsystem.UiErrorCode
import app.ledger.transfer.domain.ExportContent
import app.ledger.transfer.domain.ExportField
import app.ledger.transfer.domain.ExportFormat

enum class ExportDestinationPresentation { CONTENT, PERMISSION_REVOKED, NAME_CONFLICT }
enum class ExportExecutionPresentation { RUNNING, CANCEL_REQUESTED, FAILED, SUCCEEDED }

data class ExportFlowUiState(
    val screenId: String = "EXP-001",
    val availableContents: Set<ExportContent> = setOf(ExportContent.CURRENT_FILTER, ExportContent.FULL_WORKBOOK),
    val content: ExportContent = ExportContent.CURRENT_FILTER,
    val format: ExportFormat = ExportFormat.CSV,
    val selectedFields: Set<ExportField> = ExportField.defaultSelection,
    val includeLocationCoordinates: Boolean = false,
    val filterSummary: String = "",
    val workbookSheets: List<String> = emptyList(),
    val fileName: String = "transactions.csv",
    val destinationLabel: String? = null,
    val destinationPresentation: ExportDestinationPresentation = ExportDestinationPresentation.CONTENT,
    val executionPresentation: ExportExecutionPresentation = ExportExecutionPresentation.RUNNING,
    val processedRows: Long = 0L,
    val totalRows: Long? = null,
    val failureCode: String? = null,
    val canOpen: Boolean = false,
    val canShare: Boolean = false,
    val canViewLocation: Boolean = false,
    val externalApplicationUnavailable: Boolean = false,
    val temporaryCleanupComplete: Boolean = false,
)

sealed interface ExportFlowScreenAction {
    data object Back : ExportFlowScreenAction
    data class ContentSelected(val content: ExportContent) : ExportFlowScreenAction
    data class FormatSelected(val format: ExportFormat) : ExportFlowScreenAction
    data object Continue : ExportFlowScreenAction
    data class FieldToggled(val field: ExportField) : ExportFlowScreenAction
    data class LocationCoordinatesChanged(val included: Boolean) : ExportFlowScreenAction
    data class FileNameChanged(val value: String) : ExportFlowScreenAction
    data class DestinationSelected(val uri: Uri) : ExportFlowScreenAction
    data object ConfirmOverwrite : ExportFlowScreenAction
    data object Cancel : ExportFlowScreenAction
    data object Retry : ExportFlowScreenAction
    data object Open : ExportFlowScreenAction
    data object Share : ExportFlowScreenAction
    data object ViewLocation : ExportFlowScreenAction
    data object Operations : ExportFlowScreenAction
}

private class ExportFlowActions(
    val onBack: () -> Unit,
    val onContentSelected: (ExportContent) -> Unit,
    val onFormatSelected: (ExportFormat) -> Unit,
    val onContinue: () -> Unit,
    val onFieldToggled: (ExportField) -> Unit,
    val onLocationCoordinatesChanged: (Boolean) -> Unit,
    val onFileNameChanged: (String) -> Unit,
    val onDestinationSelected: (Uri) -> Unit,
    val onConfirmOverwrite: () -> Unit,
    val onCancel: () -> Unit,
    val onRetry: () -> Unit,
    val onOpen: () -> Unit,
    val onShare: () -> Unit,
    val onViewLocation: () -> Unit,
    val onOperations: () -> Unit,
)

@Composable
fun ExportFlowScreen(state: ExportFlowUiState, onAction: (ExportFlowScreenAction) -> Unit) {
    ExportFlowContent(
        state,
        ExportFlowActions(
            onBack = { onAction(ExportFlowScreenAction.Back) },
            onContentSelected = { onAction(ExportFlowScreenAction.ContentSelected(it)) },
            onFormatSelected = { onAction(ExportFlowScreenAction.FormatSelected(it)) },
            onContinue = { onAction(ExportFlowScreenAction.Continue) },
            onFieldToggled = { onAction(ExportFlowScreenAction.FieldToggled(it)) },
            onLocationCoordinatesChanged = { onAction(ExportFlowScreenAction.LocationCoordinatesChanged(it)) },
            onFileNameChanged = { onAction(ExportFlowScreenAction.FileNameChanged(it)) },
            onDestinationSelected = { onAction(ExportFlowScreenAction.DestinationSelected(it)) },
            onConfirmOverwrite = { onAction(ExportFlowScreenAction.ConfirmOverwrite) },
            onCancel = { onAction(ExportFlowScreenAction.Cancel) },
            onRetry = { onAction(ExportFlowScreenAction.Retry) },
            onOpen = { onAction(ExportFlowScreenAction.Open) },
            onShare = { onAction(ExportFlowScreenAction.Share) },
            onViewLocation = { onAction(ExportFlowScreenAction.ViewLocation) },
            onOperations = { onAction(ExportFlowScreenAction.Operations) },
        ),
    )
}

@Composable
private fun ExportFlowContent(state: ExportFlowUiState, actions: ExportFlowActions) {
    LedgerScaffold(
        state.fixedRootModifier(),
        topBar = { LedgerTopAppBar(stringResource(R.string.export_title), LedgerTopAppBarVariant.BACK, onNavigation = actions.onBack) },
        fixedAction = {
            if (state.screenId in setOf("EXP-001", "EXP-002")) {
                LedgerButton(stringResource(R.string.export_continue), actions.onContinue)
            }
        },
    ) { padding ->
        when (state.screenId) {
            "EXP-001" -> ExportTypeContent(state, actions, Modifier.padding(padding))
            "EXP-002" -> ExportFieldsContent(state, actions, Modifier.padding(padding))
            "EXP-003" -> ExportDestinationContent(state, actions, Modifier.padding(padding))
            "EXP-004" -> ExportProgressContent(state, actions, Modifier.padding(padding))
            else -> error("unknown export screen")
        }
    }
}

@Composable
private fun ExportTypeContent(state: ExportFlowUiState, actions: ExportFlowActions, modifier: Modifier) {
    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        item { StageHeading(R.string.export_content_heading, R.string.export_content_supporting) }
        items(ExportContent.entries.filter(state.availableContents::contains), key = ExportContent::name) { content ->
            LedgerCard(Modifier.fillMaxWidth(), onClick = { actions.onContentSelected(content) }) {
                Column(Modifier.padding(LedgerTheme.spacing.sm)) {
                    LedgerText(content.label(), LedgerTextRole.SECTION)
                    LedgerText(content.supporting(), LedgerTextRole.SUPPORTING)
                }
            }
        }
        if (state.content == ExportContent.CURRENT_FILTER) {
            item { LedgerBanner(stringResource(R.string.export_filter_summary, state.filterSummary), LedgerBannerVariant.INFO) }
        }
        if (state.content == ExportContent.FULL_WORKBOOK) {
            item { LedgerText(stringResource(R.string.export_workbook_sheets, state.workbookSheets.joinToString()), LedgerTextRole.SUPPORTING) }
        }
        item { LedgerText(stringResource(R.string.export_format_heading), LedgerTextRole.SECTION) }
        items(state.availableFormats(), key = ExportFormat::name) { format ->
            LedgerChip(format.label(), { actions.onFormatSelected(format) }, selected = state.format == format)
        }
        item { LedgerBanner(stringResource(R.string.export_not_backup), LedgerBannerVariant.WARNING) }
    }
}

@Composable
private fun ExportFieldsContent(state: ExportFlowUiState, actions: ExportFlowActions, modifier: Modifier) {
    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
        item { StageHeading(R.string.export_fields_heading, R.string.export_fields_supporting) }
        item { LedgerBanner(stringResource(R.string.export_vault_excluded), LedgerBannerVariant.INFO) }
        items(ExportField.entries.filterNot(ExportField::sensitiveLocation), key = ExportField::name) { field ->
            LedgerChip(
                field.label(),
                { actions.onFieldToggled(field) },
                Modifier.fillMaxWidth(),
                selected = field in state.selectedFields,
            )
        }
        item {
            LedgerCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(LedgerTheme.spacing.sm)) {
                    LedgerText(stringResource(R.string.export_location_coordinates), LedgerTextRole.SECTION)
                    LedgerText(stringResource(R.string.export_location_sensitive), LedgerTextRole.SUPPORTING)
                    LedgerButton(
                        if (state.includeLocationCoordinates) stringResource(R.string.export_location_disable) else stringResource(R.string.export_location_enable),
                        { actions.onLocationCoordinatesChanged(!state.includeLocationCoordinates) },
                        variant = if (state.includeLocationCoordinates) LedgerButtonVariant.TONAL else LedgerButtonVariant.SECONDARY,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExportDestinationContent(state: ExportFlowUiState, actions: ExportFlowActions, modifier: Modifier) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) actions.onDestinationSelected(uri)
    }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        StageHeading(R.string.export_destination_heading, R.string.export_destination_supporting)
        if (state.destinationPresentation == ExportDestinationPresentation.PERMISSION_REVOKED) {
            LedgerBanner(stringResource(R.string.export_permission_revoked), LedgerBannerVariant.DANGER)
        }
        if (state.destinationPresentation == ExportDestinationPresentation.NAME_CONFLICT) {
            LedgerBanner(
                stringResource(R.string.export_name_conflict),
                LedgerBannerVariant.WARNING,
                actionLabel = stringResource(R.string.export_overwrite),
                onAction = actions.onConfirmOverwrite,
            )
        }
        LedgerTextField(state.fileName, actions.onFileNameChanged, stringResource(R.string.export_file_name), required = true)
        LedgerButton(stringResource(R.string.export_choose_location), { picker.launch(null) }, Modifier.fillMaxWidth())
        state.destinationLabel?.let { LedgerText(stringResource(R.string.export_selected_location, it), LedgerTextRole.SUPPORTING) }
    }
}

@Composable
private fun ExportProgressContent(state: ExportFlowUiState, actions: ExportFlowActions, modifier: Modifier) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        StageHeading(R.string.export_progress_heading, R.string.export_progress_supporting)
        if (state.externalApplicationUnavailable) {
            LedgerBanner(stringResource(R.string.export_external_unavailable), LedgerBannerVariant.WARNING)
        }
        when (state.executionPresentation) {
            ExportExecutionPresentation.RUNNING, ExportExecutionPresentation.CANCEL_REQUESTED -> OperationProgressPanel(
                OperationProgressUiModel(
                    stringResource(R.string.export_operation_name),
                    if (state.executionPresentation == ExportExecutionPresentation.CANCEL_REQUESTED) {
                        stringResource(R.string.export_cancel_requested)
                    } else {
                        stringResource(R.string.export_running)
                    },
                    stringResource(R.string.export_processed_rows, state.processedRows),
                    state.totalRows?.takeIf { it > 0L }?.let { state.processedRows.toFloat() / it.toFloat() },
                    OperationCapability.CANCELABLE,
                    stringResource(R.string.export_safe_cancel),
                ),
                onCancel = actions.onCancel,
            )
            ExportExecutionPresentation.FAILED -> {
                LedgerBanner(stringResource(R.string.export_failed, state.failureCode.orEmpty()), LedgerBannerVariant.DANGER)
                if (state.temporaryCleanupComplete) LedgerText(stringResource(R.string.export_temporary_cleaned), LedgerTextRole.SUPPORTING)
                LedgerButton(stringResource(R.string.export_retry), actions.onRetry, Modifier.fillMaxWidth())
            }
            ExportExecutionPresentation.SUCCEEDED -> {
                LedgerBanner(stringResource(R.string.export_succeeded, state.processedRows), LedgerBannerVariant.INFO)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                    LedgerButton(stringResource(R.string.export_open), actions.onOpen, Modifier.weight(1f), enabled = state.canOpen)
                    LedgerButton(stringResource(R.string.export_share), actions.onShare, Modifier.weight(1f), LedgerButtonVariant.SECONDARY, state.canShare)
                }
                LedgerButton(stringResource(R.string.export_view_location), actions.onViewLocation, Modifier.fillMaxWidth(), LedgerButtonVariant.TEXT, state.canViewLocation)
            }
        }
        LedgerButton(stringResource(R.string.export_operations), actions.onOperations, Modifier.fillMaxWidth(), LedgerButtonVariant.TEXT)
    }
}

@Composable
private fun StageHeading(title: Int, supporting: Int) {
    Column(Modifier.fillMaxWidth()) {
        LedgerText(stringResource(title), LedgerTextRole.TITLE)
        LedgerText(stringResource(supporting), LedgerTextRole.SUPPORTING)
    }
}

private fun ExportFlowUiState.availableFormats(): List<ExportFormat> = when (content) {
    ExportContent.CURRENT_FILTER -> listOf(ExportFormat.CSV)
    ExportContent.FULL_WORKBOOK -> listOf(ExportFormat.XLSX)
    ExportContent.REPORT -> listOf(ExportFormat.CSV, ExportFormat.XLSX, ExportFormat.PDF, ExportFormat.IMAGE)
}

@Composable
private fun ExportContent.label(): String = stringResource(
    when (this) {
        ExportContent.CURRENT_FILTER -> R.string.export_current_filter
        ExportContent.FULL_WORKBOOK -> R.string.export_full_workbook
        ExportContent.REPORT -> R.string.export_report
    },
)

@Composable
private fun ExportContent.supporting(): String = stringResource(
    when (this) {
        ExportContent.CURRENT_FILTER -> R.string.export_current_filter_supporting
        ExportContent.FULL_WORKBOOK -> R.string.export_full_workbook_supporting
        ExportContent.REPORT -> R.string.export_report_supporting
    },
)

@Composable
private fun ExportFormat.label(): String = when (this) {
    ExportFormat.CSV -> "CSV"
    ExportFormat.XLSX -> "XLSX"
    ExportFormat.PDF -> "PDF"
    ExportFormat.IMAGE -> "PNG"
    ExportFormat.PORTABLE_BACKUP -> error("backup is not an ordinary export format")
}

@Composable
private fun ExportField.label(): String = stringResource(
    when (this) {
        ExportField.TRANSACTION_ID -> R.string.export_field_transaction_id
        ExportField.TRANSACTION_TYPE -> R.string.export_field_type
        ExportField.STATE -> R.string.export_field_state
        ExportField.OCCURRED_AT -> R.string.export_field_occurred
        ExportField.LOCAL_DATE -> R.string.export_field_local_date
        ExportField.TIME_ZONE -> R.string.export_field_time_zone
        ExportField.AMOUNT_MINOR -> R.string.export_field_amount
        ExportField.CURRENCY -> R.string.export_field_currency
        ExportField.ORIGINAL_AMOUNT_MINOR -> R.string.export_field_original_amount
        ExportField.ORIGINAL_CURRENCY -> R.string.export_field_original_currency
        ExportField.ACCOUNT -> R.string.export_field_account
        ExportField.CARD_DISPLAY_NAME -> R.string.export_field_card
        ExportField.CATEGORY -> R.string.export_field_category
        ExportField.MERCHANT -> R.string.export_field_merchant
        ExportField.PROJECT -> R.string.export_field_project
        ExportField.SETTLEMENT_ACTIVITY -> R.string.export_field_settlement
        ExportField.PLACE -> R.string.export_field_place
        ExportField.NOTE -> R.string.export_field_note
        ExportField.ATTACHMENT_REFERENCES -> R.string.export_field_attachments
        ExportField.SOURCE -> R.string.export_field_source
        ExportField.LATITUDE_E7, ExportField.LONGITUDE_E7 -> R.string.export_location_coordinates
    },
)

private fun ExportFlowUiState.fixedRootModifier(): Modifier = when (screenId) {
    "EXP-001" -> Modifier.fillMaxSize().testTag("export_type")
    "EXP-002" -> Modifier.fillMaxSize().testTag("export_fields")
    "EXP-003" -> Modifier.fillMaxSize().testTag("export_destination")
    "EXP-004" -> Modifier.fillMaxSize().testTag("export_progress")
    else -> error("unknown export screen")
}
