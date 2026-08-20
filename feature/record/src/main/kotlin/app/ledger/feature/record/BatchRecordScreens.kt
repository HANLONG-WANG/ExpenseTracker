@file:Suppress(
    "ktlint:standard:function-naming",
    "FunctionNaming",
    "LongMethod",
    "LongParameterList",
    "MagicNumber",
    "MatchingDeclarationName",
    "MaxLineLength",
    "CyclomaticComplexMethod",
)

package app.ledger.feature.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.ledger.core.common.StableId
import app.ledger.core.designsystem.BatchCommitBar
import app.ledger.core.designsystem.BatchSummaryRowUiModel
import app.ledger.core.designsystem.BatchSummaryTable
import app.ledger.core.designsystem.BatchToolbar
import app.ledger.core.designsystem.FormSection
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerButton
import app.ledger.core.designsystem.LedgerButtonVariant
import app.ledger.core.designsystem.LedgerCard
import app.ledger.core.designsystem.LedgerChoiceRow
import app.ledger.core.designsystem.LedgerDialog
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextField
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.SelectorField
import app.ledger.core.designsystem.ValidationItemUiModel
import app.ledger.core.designsystem.ValidationSummary
import app.ledger.finance.application.BatchEntryField
import app.ledger.finance.application.BatchValidationIssue
import app.ledger.finance.application.BatchValidationSeverity
import java.time.Instant

public sealed interface BatchRecordScreenAction {
    public data class OpenRow(val rowId: StableId) : BatchRecordScreenAction
    public data object Add : BatchRecordScreenAction
    public data class Copy(val rowId: StableId) : BatchRecordScreenAction
    public data class Delete(val rowId: StableId) : BatchRecordScreenAction
    public data class Move(val rowId: StableId, val offset: Int) : BatchRecordScreenAction
    public data class Sort(val sort: BatchSort) : BatchRecordScreenAction
    public data class Paste(val text: String) : BatchRecordScreenAction
    public data class RowChange(val row: BatchRowDraft) : BatchRecordScreenAction
    public data class CycleReference(val rowId: StableId, val field: BatchEntryField) : BatchRecordScreenAction
    public data class AddAttachment(val rowId: StableId) : BatchRecordScreenAction
    public data object Validate : BatchRecordScreenAction
    public data object ConfirmWarnings : BatchRecordScreenAction
    public data object Commit : BatchRecordScreenAction
    public data object Undo : BatchRecordScreenAction
    public data object Discard : BatchRecordScreenAction
    public data object KeepEditing : BatchRecordScreenAction
    public data class JumpToIssue(val issue: BatchValidationIssue) : BatchRecordScreenAction
}

internal class BatchRecordActions(
    val onOpenRow: (StableId) -> Unit,
    val onAdd: () -> Unit,
    val onCopy: (StableId) -> Unit,
    val onDelete: (StableId) -> Unit,
    val onMove: (StableId, Int) -> Unit,
    val onSort: (BatchSort) -> Unit,
    val onPaste: (String) -> Unit,
    val onRowChange: (BatchRowDraft) -> Unit,
    val onCycleReference: (StableId, BatchEntryField) -> Unit,
    val onAddAttachment: (StableId) -> Unit,
    val onValidate: () -> Unit,
    val onConfirmWarnings: () -> Unit,
    val onCommit: () -> Unit,
    val onUndo: () -> Unit,
    val onDiscard: () -> Unit,
    val onKeepEditing: () -> Unit,
    val onJumpToIssue: (BatchValidationIssue) -> Unit,
)

internal fun batchRecordActions(onAction: (BatchRecordScreenAction) -> Unit): BatchRecordActions = BatchRecordActions(
    onOpenRow = { onAction(BatchRecordScreenAction.OpenRow(it)) },
    onAdd = { onAction(BatchRecordScreenAction.Add) },
    onCopy = { onAction(BatchRecordScreenAction.Copy(it)) },
    onDelete = { onAction(BatchRecordScreenAction.Delete(it)) },
    onMove = { id, offset -> onAction(BatchRecordScreenAction.Move(id, offset)) },
    onSort = { onAction(BatchRecordScreenAction.Sort(it)) },
    onPaste = { onAction(BatchRecordScreenAction.Paste(it)) },
    onRowChange = { onAction(BatchRecordScreenAction.RowChange(it)) },
    onCycleReference = { id, field -> onAction(BatchRecordScreenAction.CycleReference(id, field)) },
    onAddAttachment = { onAction(BatchRecordScreenAction.AddAttachment(it)) },
    onValidate = { onAction(BatchRecordScreenAction.Validate) },
    onConfirmWarnings = { onAction(BatchRecordScreenAction.ConfirmWarnings) },
    onCommit = { onAction(BatchRecordScreenAction.Commit) },
    onUndo = { onAction(BatchRecordScreenAction.Undo) },
    onDiscard = { onAction(BatchRecordScreenAction.Discard) },
    onKeepEditing = { onAction(BatchRecordScreenAction.KeepEditing) },
    onJumpToIssue = { onAction(BatchRecordScreenAction.JumpToIssue(it)) },
)

@Composable
public fun BatchRecordDestination(
    screenId: String,
    state: BatchRecordState,
    onAction: (BatchRecordScreenAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = batchRecordActions(onAction)
    when (screenId) {
        "REC-023" -> BatchSummaryScreen(state, actions, modifier)
        "REC-024" -> BatchRowEditorScreen(state, actions, modifier)
        "REC-025" -> BatchValidationScreen(state, actions, modifier)
        else -> LedgerText(stringResource(R.string.batch_unknown_route), LedgerTextRole.BODY, modifier)
    }
    if (state.showDiscardConfirmation) {
        LedgerDialog(
            stringResource(R.string.batch_discard_title),
            stringResource(R.string.batch_discard_message),
            stringResource(R.string.batch_discard),
            actions.onDiscard,
            actions.onKeepEditing,
            Modifier.testTag(LedgerTestTags.BATCH_DISCARD),
            danger = true,
        )
    }
}

@Composable
private fun BatchSummaryScreen(state: BatchRecordState, actions: BatchRecordActions, modifier: Modifier) {
    var pasteText by remember { mutableStateOf("") }
    Column(
        modifier.fillMaxSize().testTag(LedgerTestTags.BATCH_RECORD_ROOT),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
    ) {
        LedgerBanner(stringResource(R.string.batch_atomic_notice), LedgerBannerVariant.INFO)
        when (state.presentation) {
            BatchRecordPresentation.VALIDATING -> LedgerBanner(stringResource(R.string.batch_validating), LedgerBannerVariant.INFO)
            BatchRecordPresentation.ERRORS -> LedgerBanner(stringResource(R.string.batch_has_errors), LedgerBannerVariant.DANGER)
            BatchRecordPresentation.READY_TO_COMMIT -> LedgerBanner(stringResource(R.string.batch_ready), LedgerBannerVariant.INFO)
            BatchRecordPresentation.COMMITTING -> LedgerBanner(stringResource(R.string.batch_committing), LedgerBannerVariant.INFO)
            BatchRecordPresentation.COMMITTED -> LedgerBanner(stringResource(R.string.batch_committed), LedgerBannerVariant.INFO)
            BatchRecordPresentation.COMMIT_FAILED -> LedgerBanner(
                stringResource(R.string.batch_commit_failed, state.sanitizedFailureCode.orEmpty()),
                LedgerBannerVariant.DANGER,
            )
            BatchRecordPresentation.EDITING -> Unit
        }
        if (state.presentation == BatchRecordPresentation.COMMITTED) {
            LedgerButton(stringResource(R.string.batch_undo), actions.onUndo, Modifier.fillMaxWidth(), variant = LedgerButtonVariant.TONAL)
        }
        BatchToolbar(
            listOf(
                stringResource(R.string.batch_add_row) to actions.onAdd,
                stringResource(R.string.batch_paste) to {
                    actions.onPaste(pasteText)
                    pasteText = ""
                },
                stringResource(R.string.batch_sort_date) to { actions.onSort(BatchSort.DATE_ASCENDING) },
                stringResource(R.string.batch_sort_amount) to { actions.onSort(BatchSort.AMOUNT_DESCENDING) },
            ),
        )
        LedgerTextField(
            value = pasteText,
            onValueChange = { pasteText = it },
            label = stringResource(R.string.batch_paste_input),
            supportingText = stringResource(R.string.batch_paste_format),
            singleLine = false,
        )
        BatchSummaryTable(
            rowCount = state.rows.size,
            rowAt = { index -> state.rows[index].summaryModel(state, index) },
            headers = listOf(
                stringResource(R.string.batch_column_row),
                stringResource(R.string.batch_field_category),
                stringResource(R.string.batch_field_amount),
                stringResource(R.string.batch_field_account_card),
                stringResource(R.string.batch_field_merchant),
                stringResource(R.string.batch_field_date),
                stringResource(R.string.batch_field_project),
                stringResource(R.string.batch_field_complex),
                stringResource(R.string.batch_column_status),
            ),
            onRowClick = { actions.onOpenRow(state.rows[it].rowId) },
            modifier = Modifier.weight(1f),
        )
        if (state.presentation != BatchRecordPresentation.COMMITTED) {
            BatchCommitBar(
                validationLabel = stringResource(R.string.batch_validate),
                commitLabel = stringResource(R.string.batch_commit),
                discardLabel = stringResource(R.string.batch_discard),
                onValidate = actions.onValidate,
                onCommit = actions.onCommit,
                onDiscard = actions.onDiscard,
                committing = state.presentation in setOf(BatchRecordPresentation.VALIDATING, BatchRecordPresentation.COMMITTING),
            )
        }
    }
}

@Composable
private fun BatchRowEditorScreen(state: BatchRecordState, actions: BatchRecordActions, modifier: Modifier) {
    val row = state.selectedRow
    if (row == null) {
        LedgerBanner(stringResource(R.string.batch_row_missing), LedgerBannerVariant.DANGER, modifier)
        return
    }
    val rowIssues = state.validation.issues.filter { it.rowId == row.rowId }
    val snapshot = state.snapshot
    val references = snapshot.references
    val category = references.categories.singleOrNull { it.id == row.categoryId }?.name
    val account = references.accounts.singleOrNull { it.id == row.accountId }?.name
    val card = references.cards.singleOrNull { it.id == row.cardId }?.displayName
    val merchant = references.merchants.singleOrNull { it.id == row.merchantId }?.name
    val project = snapshot.projects.singleOrNull { it.id == row.projectId }?.name
    LazyColumn(
        modifier.fillMaxSize().imePadding().testTag(LedgerTestTags.BATCH_ROW_EDITOR),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.md),
    ) {
        if (rowIssues.isNotEmpty()) {
            item {
                ValidationSummary(
                    rowIssues.map { ValidationItemUiModel("batch_${it.field.name.lowercase()}", issueMessage(it)) },
                    onErrorClick = { selected -> rowIssues.firstOrNull { "batch_${it.field.name.lowercase()}" == selected.stableFieldTag }?.let(actions.onJumpToIssue) },
                )
            }
        }
        item {
            FormSection(stringResource(R.string.batch_field_kind)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                    BatchRowKind.entries.forEach { kind ->
                        LedgerChoiceRow(kindLabel(kind), row.kind == kind, { actions.onRowChange(BatchRecordPolicy.changeKind(row, kind)) })
                    }
                }
            }
        }
        item { SelectorField(stringResource(R.string.batch_field_category), category ?: stringResource(R.string.batch_none), { actions.onCycleReference(row.rowId, BatchEntryField.CATEGORY) }) }
        item {
            FormSection(stringResource(R.string.batch_field_amount), description = stringResource(R.string.batch_amount_evidence)) {
                LedgerTextField(row.amountExpression, { value ->
                    val minor = value.trim().toLongOrNull()
                    actions.onRowChange(row.copy(amountExpression = value, userMinor = minor))
                }, stringResource(R.string.batch_amount_expression))
                LedgerTextField(row.userCurrencyCode, { actions.onRowChange(row.copy(userCurrencyCode = it.uppercase().take(3))) }, stringResource(R.string.batch_currency))
                LedgerTextField(row.accountMinor?.toString().orEmpty(), { actions.onRowChange(row.copy(accountMinor = it.trim().toLongOrNull())) }, stringResource(R.string.batch_account_amount))
                LedgerTextField(row.baseMinor?.toString().orEmpty(), { actions.onRowChange(row.copy(baseMinor = it.trim().toLongOrNull())) }, stringResource(R.string.batch_base_amount))
            }
        }
        item { SelectorField(stringResource(R.string.batch_field_account_card), listOfNotNull(account, card).joinToString(" · ").ifBlank { stringResource(R.string.batch_none) }, { actions.onCycleReference(row.rowId, BatchEntryField.ACCOUNT_AND_CARD) }) }
        item { SelectorField(stringResource(R.string.batch_field_merchant), merchant ?: stringResource(R.string.batch_none), { actions.onCycleReference(row.rowId, BatchEntryField.MERCHANT) }) }
        item {
            val dateIssue = rowIssues.firstOrNull { it.field == BatchEntryField.DATE }
            LedgerTextField(
                row.occurredAt.toString(),
                { value -> runCatching { Instant.parse(value) }.getOrNull()?.let { actions.onRowChange(row.copy(occurredAt = it)) } },
                stringResource(R.string.batch_field_date),
                errorText = if (dateIssue == null) null else issueMessage(dateIssue),
            )
        }
        item { SelectorField(stringResource(R.string.batch_field_project), project ?: stringResource(R.string.batch_none), { actions.onCycleReference(row.rowId, BatchEntryField.PROJECT) }) }
        item {
            FormSection(stringResource(R.string.batch_complex_fields), description = stringResource(R.string.batch_complex_explanation)) {
                SelectorField(stringResource(R.string.batch_field_attachments), stringResource(R.string.batch_item_count, row.attachmentIds.size), { actions.onAddAttachment(row.rowId) })
                SelectorField(stringResource(R.string.batch_field_settlement), linkedLabel(row.settlementActivityId), { actions.onCycleReference(row.rowId, BatchEntryField.SETTLEMENT) })
                SelectorField(stringResource(R.string.batch_field_location), linkedLabel(row.locationRecordId), { actions.onCycleReference(row.rowId, BatchEntryField.LOCATION) })
                SelectorField(stringResource(R.string.batch_field_installment), linkedLabel(row.installmentPlanId), { actions.onCycleReference(row.rowId, BatchEntryField.INSTALLMENT) })
                SelectorField(stringResource(R.string.batch_field_refund), linkedLabel(row.refundOriginalTransactionId), { actions.onCycleReference(row.rowId, BatchEntryField.REFUND_RELATION) }, enabled = row.kind == BatchRowKind.REFUND)
            }
        }
        item { LedgerTextField(row.note, { actions.onRowChange(row.copy(note = it)) }, stringResource(R.string.batch_field_note), singleLine = false) }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                LedgerButton(stringResource(R.string.batch_copy_row), { actions.onCopy(row.rowId) }, variant = LedgerButtonVariant.TONAL)
                LedgerButton(stringResource(R.string.batch_move_up), { actions.onMove(row.rowId, (state.rows.indexOf(row) - 1).coerceAtLeast(0)) }, variant = LedgerButtonVariant.TEXT)
                LedgerButton(stringResource(R.string.batch_delete_row), { actions.onDelete(row.rowId) }, variant = LedgerButtonVariant.TEXT)
                LedgerButton(stringResource(R.string.batch_done), actions.onValidate)
            }
        }
    }
}

@Composable
private fun BatchValidationScreen(state: BatchRecordState, actions: BatchRecordActions, modifier: Modifier) {
    val errors = state.validation.errors
    val warnings = state.validation.warnings
    LazyColumn(
        modifier.fillMaxSize().testTag(LedgerTestTags.BATCH_VALIDATION),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
    ) {
        item {
            val message = if (errors.isNotEmpty()) {
                stringResource(R.string.batch_validation_errors, errors.size)
            } else if (warnings.isNotEmpty()) {
                stringResource(R.string.batch_validation_warnings, warnings.size)
            } else {
                stringResource(R.string.batch_validation_valid)
            }
            LedgerBanner(
                message,
                if (errors.isNotEmpty()) {
                    LedgerBannerVariant.DANGER
                } else if (warnings.isNotEmpty()) {
                    LedgerBannerVariant.WARNING
                } else {
                    LedgerBannerVariant.INFO
                },
            )
        }
        items(state.validation.issues) { issue ->
            LedgerCard(Modifier.fillMaxWidth(), onClick = { actions.onJumpToIssue(issue) }) {
                Column(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm)) {
                    LedgerText(issue.rowId?.let { stringResource(R.string.batch_row_issue) } ?: stringResource(R.string.batch_whole_issue), LedgerTextRole.LABEL)
                    LedgerText(issueMessage(issue), LedgerTextRole.BODY)
                    LedgerText(stringResource(R.string.batch_jump_to_row), LedgerTextRole.SUPPORTING)
                }
            }
        }
        if (warnings.isNotEmpty() && errors.isEmpty() && !state.warningsConfirmed) {
            item { LedgerButton(stringResource(R.string.batch_confirm_warnings), actions.onConfirmWarnings, Modifier.fillMaxWidth()) }
        }
        item {
            BatchCommitBar(
                stringResource(R.string.batch_validate),
                stringResource(R.string.batch_commit),
                stringResource(R.string.batch_discard),
                actions.onValidate,
                actions.onCommit,
                actions.onDiscard,
                state.presentation == BatchRecordPresentation.COMMITTING,
            )
        }
    }
}

@Composable
private fun BatchRowDraft.summaryModel(state: BatchRecordState, index: Int): BatchSummaryRowUiModel {
    val refs = state.snapshot.references
    val issues = state.validation.issues.filter { it.rowId == rowId }
    val errorCount = issues.count { it.severity == BatchValidationSeverity.ERROR }
    val warningCount = issues.size - errorCount
    val categoryName = refs.categories.singleOrNull { it.id == categoryId }?.name ?: stringResource(R.string.batch_none)
    val accountName = refs.accounts.singleOrNull { it.id == accountId }?.name ?: stringResource(R.string.batch_none)
    val cardName = refs.cards.singleOrNull { it.id == cardId }?.displayName
    val merchantName = refs.merchants.singleOrNull { it.id == merchantId }?.name ?: stringResource(R.string.batch_none)
    val projectName = state.snapshot.projects.singleOrNull { it.id == projectId }?.name ?: stringResource(R.string.batch_none)
    val complexCount = listOf(attachmentIds.isNotEmpty(), settlementActivityId != null, locationRecordId != null, installmentPlanId != null, refundOriginalTransactionId != null).count { it }
    val status = if (errorCount > 0) {
        stringResource(R.string.batch_status_errors, errorCount)
    } else if (warningCount > 0) {
        stringResource(R.string.batch_status_warnings, warningCount)
    } else {
        stringResource(R.string.batch_status_ready)
    }
    val rowNumber = (index + 1).toString()
    return BatchSummaryRowUiModel(
        stableKey = "batch_row_${index.toString().padStart(6, '0')}",
        rowNumber = rowNumber,
        category = categoryName,
        amount = listOfNotNull(userMinor?.toString(), userCurrencyCode).joinToString(" "),
        accountAndCard = listOfNotNull(accountName, cardName).joinToString(" · "),
        merchant = merchantName,
        date = occurredAt.atZone(zoneId).toLocalDate().toString(),
        project = projectName,
        complexSummary = stringResource(R.string.batch_item_count, complexCount),
        status = status,
        accessibilitySummary = stringResource(R.string.batch_accessibility_row, rowNumber, kindLabel(kind), status),
    )
}

@Composable private fun linkedLabel(value: StableId?): String = if (value == null) stringResource(R.string.batch_none) else stringResource(R.string.batch_linked)

@Composable private fun kindLabel(kind: BatchRowKind): String = when (kind) {
    BatchRowKind.EXPENSE -> stringResource(R.string.batch_kind_expense)
    BatchRowKind.INCOME -> stringResource(R.string.batch_kind_income)
    BatchRowKind.REFUND -> stringResource(R.string.batch_kind_refund)
}

@Composable
private fun issueMessage(issue: BatchValidationIssue): String = stringResource(
    when (issue.code) {
        "CATEGORY_REQUIRED" -> R.string.batch_error_category
        "AMOUNT_INVALID" -> R.string.batch_error_amount
        "ACCOUNT_REQUIRED" -> R.string.batch_error_account
        "CARD_INCOMPATIBLE" -> R.string.batch_error_card
        "SETTLEMENT_INCOMPLETE" -> R.string.batch_error_settlement
        "INDEPENDENT_REFUND_CATEGORY_REQUIRED" -> R.string.batch_error_refund
        "INSTALLMENT_EXPENSE_ONLY" -> R.string.batch_error_installment
        "MANY_ATTACHMENTS" -> R.string.batch_warning_attachments
        "EXCESS_REFUND_OVERRIDE" -> R.string.batch_warning_refund_excess
        else -> R.string.batch_error_generic
    },
)
