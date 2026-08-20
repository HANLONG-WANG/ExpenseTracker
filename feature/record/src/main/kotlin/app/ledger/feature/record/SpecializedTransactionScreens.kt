@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "MagicNumber", "MatchingDeclarationName", "MaxLineLength", "TooManyFunctions", "ktlint:standard:function-naming")

package app.ledger.feature.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.ledger.core.common.StableId
import app.ledger.core.designsystem.AttachmentField
import app.ledger.core.designsystem.AttachmentTransferState
import app.ledger.core.designsystem.AttachmentUiModel
import app.ledger.core.designsystem.DateTimeZoneField
import app.ledger.core.designsystem.FormSection
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerButton
import app.ledger.core.designsystem.LedgerButtonVariant
import app.ledger.core.designsystem.LedgerChoiceRow
import app.ledger.core.designsystem.LedgerErrorState
import app.ledger.core.designsystem.LedgerLoadingState
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextField
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.MoneyExpressionField
import app.ledger.core.designsystem.SelectorField
import app.ledger.core.designsystem.UiErrorCode
import app.ledger.finance.application.AccountReferenceView
import app.ledger.finance.domain.BalanceAdjustmentDirection
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

public sealed interface SpecializedTransactionScreenAction {
    public data object Retry : SpecializedTransactionScreenAction
    public data object SelectFromAccount : SpecializedTransactionScreenAction
    public data object SelectToAccount : SpecializedTransactionScreenAction
    public data class OutgoingExpression(val value: String) : SpecializedTransactionScreenAction
    public data class IncomingExpression(val value: String) : SpecializedTransactionScreenAction
    public data class OutgoingOperator(val value: String) : SpecializedTransactionScreenAction
    public data class IncomingOperator(val value: String) : SpecializedTransactionScreenAction
    public data class ManualFromRate(val value: String) : SpecializedTransactionScreenAction
    public data class ManualToRate(val value: String) : SpecializedTransactionScreenAction
    public data object RefreshRates : SpecializedTransactionScreenAction
    public data class DirectionChanged(val direction: BalanceAdjustmentDirection) : SpecializedTransactionScreenAction
    public data class CheckpointSelected(val checkpointId: StableId?) : SpecializedTransactionScreenAction
    public data class DateChanged(val date: LocalDate) : SpecializedTransactionScreenAction
    public data class NoteChanged(val value: String) : SpecializedTransactionScreenAction
    public data object AddAttachment : SpecializedTransactionScreenAction
    public data class CancelAttachment(val index: Int) : SpecializedTransactionScreenAction
    public data object Save : SpecializedTransactionScreenAction
}

internal class SpecializedTransactionActions(
    val onRetry: () -> Unit,
    val onSelectFromAccount: () -> Unit,
    val onSelectToAccount: () -> Unit,
    val onOutgoingExpression: (String) -> Unit,
    val onIncomingExpression: (String) -> Unit,
    val onOutgoingOperator: (String) -> Unit,
    val onIncomingOperator: (String) -> Unit,
    val onManualFromRate: (String) -> Unit,
    val onManualToRate: (String) -> Unit,
    val onRefreshRates: () -> Unit,
    val onDirection: (BalanceAdjustmentDirection) -> Unit,
    val onCheckpoint: (StableId?) -> Unit,
    val onDate: (LocalDate) -> Unit,
    val onNote: (String) -> Unit,
    val onAddAttachment: () -> Unit,
    val onCancelAttachment: (Int) -> Unit,
    val onSave: () -> Unit,
)

internal fun specializedTransactionActions(onAction: (SpecializedTransactionScreenAction) -> Unit): SpecializedTransactionActions = SpecializedTransactionActions(
    onRetry = { onAction(SpecializedTransactionScreenAction.Retry) },
    onSelectFromAccount = { onAction(SpecializedTransactionScreenAction.SelectFromAccount) },
    onSelectToAccount = { onAction(SpecializedTransactionScreenAction.SelectToAccount) },
    onOutgoingExpression = { onAction(SpecializedTransactionScreenAction.OutgoingExpression(it)) },
    onIncomingExpression = { onAction(SpecializedTransactionScreenAction.IncomingExpression(it)) },
    onOutgoingOperator = { onAction(SpecializedTransactionScreenAction.OutgoingOperator(it)) },
    onIncomingOperator = { onAction(SpecializedTransactionScreenAction.IncomingOperator(it)) },
    onManualFromRate = { onAction(SpecializedTransactionScreenAction.ManualFromRate(it)) },
    onManualToRate = { onAction(SpecializedTransactionScreenAction.ManualToRate(it)) },
    onRefreshRates = { onAction(SpecializedTransactionScreenAction.RefreshRates) },
    onDirection = { onAction(SpecializedTransactionScreenAction.DirectionChanged(it)) },
    onCheckpoint = { onAction(SpecializedTransactionScreenAction.CheckpointSelected(it)) },
    onDate = { onAction(SpecializedTransactionScreenAction.DateChanged(it)) },
    onNote = { onAction(SpecializedTransactionScreenAction.NoteChanged(it)) },
    onAddAttachment = { onAction(SpecializedTransactionScreenAction.AddAttachment) },
    onCancelAttachment = { onAction(SpecializedTransactionScreenAction.CancelAttachment(it)) },
    onSave = { onAction(SpecializedTransactionScreenAction.Save) },
)

@Composable
public fun SpecializedTransactionDestination(
    screenId: String,
    state: SpecializedTransactionLoadState,
    onAction: (SpecializedTransactionScreenAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = specializedTransactionActions(onAction)
    Column(modifier.fillMaxSize().testTag(LedgerTestTags.SPECIALIZED_TRANSACTION_ROOT)) {
        when (state) {
            SpecializedTransactionLoadState.Loading -> LedgerLoadingState(Modifier.fillMaxSize())
            is SpecializedTransactionLoadState.Failure -> LedgerErrorState(UiErrorCode(state.code), stringResource(R.string.specialized_load_failed), actions.onRetry)
            is SpecializedTransactionLoadState.Content -> {
                if (screenId.matches(state.editor.kind)) {
                    SpecializedEditor(state.editor, actions)
                } else {
                    LedgerErrorState(
                        UiErrorCode("ROUTE_KIND_MISMATCH"),
                        stringResource(R.string.specialized_load_failed),
                        actions.onRetry,
                    )
                }
            }
        }
    }
}

@Composable
private fun SpecializedEditor(state: SpecializedTransactionEditorState, actions: SpecializedTransactionActions) {
    val from = SpecializedTransactionPolicy.account(state, state.draft.fromAccountId)
    val to = SpecializedTransactionPolicy.account(state, state.draft.toAccountId)
    LazyColumn(
        Modifier.fillMaxSize().testTag(LedgerTestTags.SPECIALIZED_TRANSACTION_FORM).padding(horizontal = LedgerTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
    ) {
        item { ContractStateBanners(state, from, to) }
        item { SelectorField(stringResource(R.string.specialized_from_account), from?.accountLabel().orEmpty(), actions.onSelectFromAccount) }
        if (state.kind == SpecializedTransactionKind.TRANSFER || state.kind == SpecializedTransactionKind.FX_EXCHANGE) {
            item { SelectorField(stringResource(R.string.specialized_to_account), to?.accountLabel().orEmpty(), actions.onSelectToAccount) }
        }
        item {
            MoneyExpressionField(
                expression = state.draft.outgoingExpression,
                normalizedExpression = state.draft.outgoingNormalized,
                result = state.draft.outgoingFormatted,
                onExpressionChange = actions.onOutgoingExpression,
                currencyCode = from?.currency?.value ?: state.snapshot.baseCurrency.value,
                errorText = state.errors.fieldError(SpecializedField.OUTGOING_AMOUNT),
                onOperator = actions.onOutgoingOperator,
            )
        }
        if (state.kind == SpecializedTransactionKind.FX_EXCHANGE || state.kind == SpecializedTransactionKind.TRANSFER && from?.currency != to?.currency) {
            item {
                MoneyExpressionField(
                    expression = state.draft.incomingExpression,
                    normalizedExpression = state.draft.incomingNormalized,
                    result = state.draft.incomingFormatted,
                    onExpressionChange = actions.onIncomingExpression,
                    currencyCode = to?.currency?.value ?: state.snapshot.baseCurrency.value,
                    errorText = state.errors.fieldError(SpecializedField.INCOMING_AMOUNT),
                    onOperator = actions.onIncomingOperator,
                )
            }
        }
        if (state.kind == SpecializedTransactionKind.BALANCE_ADJUSTMENT) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                    LedgerChoiceRow(stringResource(R.string.specialized_increase), state.draft.direction == BalanceAdjustmentDirection.INCREASE, { actions.onDirection(BalanceAdjustmentDirection.INCREASE) }, Modifier.weight(1f))
                    LedgerChoiceRow(stringResource(R.string.specialized_decrease), state.draft.direction == BalanceAdjustmentDirection.DECREASE, { actions.onDirection(BalanceAdjustmentDirection.DECREASE) }, Modifier.weight(1f))
                }
            }
            item { CheckpointField(state, actions) }
        }
        item { FxEvidenceSection(state, from, to, actions) }
        if (state.kind == SpecializedTransactionKind.FX_EXCHANGE) item { FxExchangeSummary(state, from, to) }
        item {
            DateTimeZoneField(
                label = if (state.kind == SpecializedTransactionKind.OPENING_BALANCE) stringResource(R.string.specialized_opening_date) else stringResource(R.string.specialized_effective_time),
                localDateTime = state.draft.localDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                zoneText = state.draft.zoneId.id,
                onClick = { actions.onDate(state.draft.localDate.plusDays(1)) },
            )
        }
        if (state.kind == SpecializedTransactionKind.TRANSFER) {
            item { LedgerTextField(state.draft.note, actions.onNote, stringResource(R.string.specialized_note), singleLine = false) }
            item {
                AttachmentField(
                    attachments = state.draft.attachmentIds.mapIndexed { index, _ ->
                        AttachmentUiModel("specialized_attachment_$index", stringResource(R.string.specialized_attachment_index, index + 1), stringResource(R.string.specialized_encrypted), stringResource(R.string.specialized_size_hidden), null, AttachmentTransferState.READY)
                    } + if (state.attachmentImporting) listOf(AttachmentUiModel("specialized_attachment_import", stringResource(R.string.specialized_importing), stringResource(R.string.specialized_encrypted), stringResource(R.string.specialized_size_hidden), null, AttachmentTransferState.IMPORTING)) else emptyList(),
                    onAdd = actions.onAddAttachment,
                    onOpen = {},
                    onCancel = { item -> actions.onCancelAttachment(if (item.stableKey == "specialized_attachment_import") state.draft.attachmentIds.size else item.stableKey.substringAfterLast('_').toInt()) },
                    addLabel = stringResource(R.string.specialized_add_attachment),
                )
            }
        }
    }
}

@Composable
private fun FxExchangeSummary(
    state: SpecializedTransactionEditorState,
    from: AccountReferenceView?,
    to: AccountReferenceView?,
) {
    val summary = SpecializedTransactionPolicy.fxDisplaySummary(state)
    FormSection(
        title = stringResource(R.string.specialized_effective_rate_title),
        modifier = Modifier.testTag(LedgerTestTags.EFFECTIVE_RATE_SUMMARY),
    ) {
        LedgerText(
            stringResource(
                R.string.specialized_effective_rate_value,
                from?.currency?.value.orEmpty(),
                summary.effectiveRate?.toPlainString() ?: stringResource(R.string.specialized_pending_value),
                to?.currency?.value.orEmpty(),
            ),
            LedgerTextRole.BODY,
        )
        LedgerText(
            stringResource(
                R.string.specialized_reference_rate_value,
                from?.currency?.value.orEmpty(),
                summary.referenceRate?.toPlainString() ?: stringResource(R.string.specialized_pending_value),
                to?.currency?.value.orEmpty(),
            ),
            LedgerTextRole.SUPPORTING,
        )
    }
    FormSection(
        title = stringResource(R.string.specialized_fx_cost_title),
        modifier = Modifier.testTag(LedgerTestTags.FX_COST_SECTION),
    ) {
        LedgerText(
            stringResource(
                R.string.specialized_fx_cost_value,
                summary.spreadCostBaseMinor?.toString() ?: stringResource(R.string.specialized_pending_value),
                state.snapshot.baseCurrency.value,
            ),
            LedgerTextRole.BODY,
        )
        LedgerText(stringResource(R.string.specialized_rounding_evidence), LedgerTextRole.SUPPORTING)
    }
}

@Composable
private fun ContractStateBanners(
    state: SpecializedTransactionEditorState,
    from: AccountReferenceView?,
    to: AccountReferenceView?,
) {
    if (state.presentation == SpecializedPresentation.SAVE_ERROR) LedgerBanner(stringResource(R.string.specialized_save_failed), LedgerBannerVariant.DANGER)
    if (
        state.draft.fromAccountId != null &&
        state.draft.fromAccountId == state.draft.toAccountId &&
        state.kind in setOf(SpecializedTransactionKind.TRANSFER, SpecializedTransactionKind.FX_EXCHANGE)
    ) {
        LedgerBanner(stringResource(R.string.specialized_same_account), LedgerBannerVariant.DANGER)
    }
    if (state.kind == SpecializedTransactionKind.FX_EXCHANGE && from != null && from.currency == to?.currency) {
        LedgerBanner(stringResource(R.string.specialized_same_currency), LedgerBannerVariant.INFO)
    }
    if (state.kind in setOf(SpecializedTransactionKind.BALANCE_ADJUSTMENT, SpecializedTransactionKind.OPENING_BALANCE)) {
        LedgerBanner(stringResource(R.string.specialized_no_statistics), LedgerBannerVariant.INFO)
    }
    if (state.kind == SpecializedTransactionKind.OPENING_BALANCE) {
        LedgerBanner(
            stringResource(R.string.specialized_currency_immutable, from?.currency?.value.orEmpty()),
            LedgerBannerVariant.INFO,
        )
    }
    if (state.errors.any { it.field == SpecializedField.RATE }) LedgerBanner(stringResource(R.string.specialized_fx_required), LedgerBannerVariant.WARNING)
}

@Composable
private fun CheckpointField(state: SpecializedTransactionEditorState, actions: SpecializedTransactionActions) {
    val candidates = state.snapshot.checkpoints.filter { it.accountId == state.draft.fromAccountId && it.adjustmentTransactionId == null }
    val current = candidates.singleOrNull { it.id == state.draft.checkpointId }
    SelectorField(
        stringResource(R.string.specialized_checkpoint),
        current?.asOfLocalDate?.format(DateTimeFormatter.ISO_LOCAL_DATE) ?: stringResource(R.string.specialized_none),
        { actions.onCheckpoint(if (current == null) candidates.firstOrNull()?.id else null) },
        supportingText = stringResource(R.string.specialized_checkpoint_explanation),
        enabled = candidates.isNotEmpty(),
    )
}

@Composable
private fun FxEvidenceSection(
    state: SpecializedTransactionEditorState,
    from: AccountReferenceView?,
    to: AccountReferenceView?,
    actions: SpecializedTransactionActions,
) {
    val currencies = SpecializedTransactionPolicy.requiredQuoteCurrencies(state)
    if (currencies.isEmpty()) return
    FormSection(stringResource(R.string.specialized_fx_evidence), description = stringResource(R.string.specialized_fx_evidence_explanation)) {
        RateRow(state, from, false, actions.onManualFromRate)
        if (state.kind == SpecializedTransactionKind.FX_EXCHANGE) RateRow(state, to, true, actions.onManualToRate)
        LedgerButton(stringResource(R.string.specialized_refresh_rate), actions.onRefreshRates, variant = LedgerButtonVariant.SECONDARY, enabled = state.quotePending.isEmpty())
    }
}

@Composable
private fun RateRow(state: SpecializedTransactionEditorState, account: AccountReferenceView?, incoming: Boolean, onManualRate: (String) -> Unit) {
    if (account == null || account.currency == state.snapshot.baseCurrency) return
    val quote = state.quotesToBase[account.currency]
    val manual = if (incoming) state.draft.manualToBaseRate else state.draft.manualFromBaseRate
    val text = when {
        account.currency in state.quotePending -> stringResource(R.string.specialized_rate_loading)
        quote != null -> stringResource(R.string.specialized_rate_summary, account.currency.value, quote.evidence.rate.toPlainString(), state.snapshot.baseCurrency.value)
        else -> stringResource(R.string.specialized_rate_unavailable)
    }
    LedgerText(text, LedgerTextRole.SUPPORTING)
    quote?.evidence?.quotedAt?.let { LedgerText(stringResource(R.string.specialized_rate_time, DateTimeFormatter.ISO_INSTANT.format(it.atOffset(ZoneOffset.UTC))), LedgerTextRole.SUPPORTING) }
    if (quote?.stale == true) LedgerBanner(stringResource(R.string.specialized_rate_stale), LedgerBannerVariant.WARNING)
    LedgerTextField(manual, onManualRate, stringResource(R.string.specialized_manual_rate, account.currency.value, state.snapshot.baseCurrency.value), supportingText = stringResource(R.string.specialized_manual_override))
}

private fun AccountReferenceView.accountLabel(): String = "$name · ${currency.value}"

private fun String.matches(kind: SpecializedTransactionKind): Boolean = when (this) {
    "REC-013" -> kind == SpecializedTransactionKind.TRANSFER
    "REC-020" -> kind == SpecializedTransactionKind.BALANCE_ADJUSTMENT
    "REC-021" -> kind == SpecializedTransactionKind.FX_EXCHANGE
    "REC-022" -> kind == SpecializedTransactionKind.OPENING_BALANCE
    else -> false
}

@Composable
private fun List<SpecializedValidationError>.fieldError(field: SpecializedField): String? = firstOrNull { it.field == field }?.let { stringResource(R.string.specialized_invalid_field) }
