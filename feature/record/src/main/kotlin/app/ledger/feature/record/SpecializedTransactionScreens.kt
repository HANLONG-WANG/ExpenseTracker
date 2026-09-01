@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "MagicNumber", "MatchingDeclarationName", "MaxLineLength", "TooManyFunctions", "ktlint:standard:function-naming")

package app.ledger.feature.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import app.ledger.core.designsystem.LedgerDatePickerFlow
import app.ledger.core.designsystem.LedgerDialog
import app.ledger.core.designsystem.LedgerErrorState
import app.ledger.core.designsystem.LedgerDateTimePickerFlow
import app.ledger.core.designsystem.LedgerLoadingState
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextField
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerDateFormatterRuntime
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.MoneyExpressionField
import app.ledger.core.designsystem.SearchField
import app.ledger.core.designsystem.SelectorField
import app.ledger.core.designsystem.UiErrorCode
import app.ledger.core.designsystem.UiText
import app.ledger.finance.application.AccountReferenceView
import app.ledger.finance.domain.BalanceAdjustmentDirection
import app.ledger.core.money.FxRateSource
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

public data class SpecializedTransactionActions(
    val onRetry: () -> Unit,
    val onSelectFromAccount: (StableId) -> Unit,
    val onSelectToAccount: (StableId) -> Unit,
    val onOutgoingExpression: (String) -> Unit,
    val onIncomingExpression: (String) -> Unit,
    val onOutgoingOperator: (String) -> Unit,
    val onIncomingOperator: (String) -> Unit,
    val onManualFromRate: (String) -> Unit,
    val onManualToRate: (String) -> Unit,
    val onRefreshRates: () -> Unit,
    val onDirection: (BalanceAdjustmentDirection) -> Unit,
    val onCheckpoint: (StableId?) -> Unit,
    val onOccurredAt: (Instant) -> Unit,
    val onNote: (String) -> Unit,
    val onAddAttachment: () -> Unit,
    val onOpenAttachment: (Int) -> Unit,
    val onCancelAttachment: (Int) -> Unit,
    val onSave: () -> Unit,
)

@Composable
public fun SpecializedTransactionDestination(
    screenId: String,
    state: SpecializedTransactionLoadState,
    actions: SpecializedTransactionActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().testTag(LedgerTestTags.SPECIALIZED_TRANSACTION_ROOT)) {
        when (state) {
            SpecializedTransactionLoadState.Loading -> LedgerLoadingState(Modifier.fillMaxSize())
            is SpecializedTransactionLoadState.Failure -> LedgerErrorState(UiErrorCode(state.code), UiText.Resource(R.string.specialized_load_failed), actions.onRetry)
            is SpecializedTransactionLoadState.Content -> {
                if (screenId.matches(state.editor.kind)) {
                    SpecializedEditor(state.editor, actions)
                } else {
                    LedgerErrorState(
                        UiErrorCode("ROUTE_KIND_MISMATCH"),
                        UiText.Resource(R.string.specialized_load_failed),
                        actions.onRetry,
                    )
                }
            }
        }
    }
}

@Composable
private fun SpecializedEditor(state: SpecializedTransactionEditorState, actions: SpecializedTransactionActions) {
    val locale = LocalLocale.current.platformLocale
    val from = SpecializedTransactionPolicy.account(state, state.draft.fromAccountId)
    val to = SpecializedTransactionPolicy.account(state, state.draft.toAccountId)
    var showDateTimePicker by remember { mutableStateOf(false) }
    var accountPickerIncoming by remember { mutableStateOf<Boolean?>(null) }
    var accountSearch by remember { mutableStateOf("") }
    var pendingAccountId by remember { mutableStateOf<StableId?>(null) }
    LazyColumn(
        Modifier.fillMaxSize().testTag(LedgerTestTags.SPECIALIZED_TRANSACTION_FORM).padding(horizontal = LedgerTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
    ) {
        item { ContractStateBanners(state, from, to) }
        item {
            SelectorField(
                stringResource(if (state.kind == SpecializedTransactionKind.BALANCE_ADJUSTMENT) R.string.specialized_adjustment_account else R.string.specialized_from_account),
                from?.accountLabel().orEmpty(),
                {
                    accountPickerIncoming = false
                    accountSearch = ""
                    pendingAccountId = state.draft.fromAccountId
                },
                supportingText = state.errors.fieldError(SpecializedField.FROM_ACCOUNT)
                    ?: state.assetEndpointHint(),
            )
        }
        if (state.kind == SpecializedTransactionKind.TRANSFER || state.kind == SpecializedTransactionKind.FX_EXCHANGE) {
            item {
                SelectorField(
                    stringResource(R.string.specialized_to_account),
                    to?.accountLabel().orEmpty(),
                    {
                        accountPickerIncoming = true
                        accountSearch = ""
                        pendingAccountId = state.draft.toAccountId
                    },
                    supportingText = state.errors.fieldError(SpecializedField.TO_ACCOUNT)
                        ?: state.assetEndpointHint(),
                )
            }
        }
        item {
            MoneyExpressionField(
                expression = state.draft.outgoingExpression,
                normalizedExpression = state.draft.outgoingNormalized,
                result = state.draft.outgoingFormatted,
                onExpressionChange = actions.onOutgoingExpression,
                currencyCode = from?.currency?.value ?: state.snapshot.baseCurrency.value,
                errorText = state.errors.fieldError(SpecializedField.OUTGOING_AMOUNT),
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
            if (state.kind == SpecializedTransactionKind.OPENING_BALANCE) {
                SelectorField(
                    label = stringResource(R.string.specialized_opening_date),
                    selectedText = state.draft.localDate.localized(locale),
                    onClick = { showDateTimePicker = true },
                )
            } else {
                DateTimeZoneField(
                    label = stringResource(R.string.specialized_effective_time),
                    localDateTime = state.draft.occurredAt.localized(state.draft.zoneId, locale),
                    zoneText = state.draft.zoneId.id,
                    onClick = { showDateTimePicker = true },
                )
            }
        }
        if (state.kind == SpecializedTransactionKind.TRANSFER) {
            item { LedgerTextField(state.draft.note, actions.onNote, stringResource(R.string.specialized_note), singleLine = false) }
            item {
                AttachmentField(
                    attachments = state.draft.attachmentIds.mapIndexed { index, id ->
                        val metadata = state.attachmentPresentations.singleOrNull { it.attachmentId == id }
                        AttachmentUiModel(
                            "specialized_attachment_$index",
                            metadata?.displayName ?: stringResource(R.string.specialized_attachment_index, index + 1),
                            metadata?.sizeText ?: stringResource(R.string.specialized_size_hidden),
                            metadata?.typeLabel ?: stringResource(R.string.specialized_encrypted),
                            null,
                            AttachmentTransferState.READY,
                            metadata?.typeLabel.attachmentIcon(),
                        )
                    } + (if (state.attachmentImporting) listOf(AttachmentUiModel("specialized_attachment_import", stringResource(R.string.specialized_importing), stringResource(R.string.specialized_size_hidden), stringResource(R.string.specialized_encrypted), null, AttachmentTransferState.IMPORTING)) else emptyList()) +
                        (if (state.attachmentFailureCode != null) listOf(AttachmentUiModel("specialized_attachment_failed", stringResource(R.string.specialized_importing), stringResource(R.string.specialized_size_hidden), stringResource(R.string.specialized_encrypted), null, AttachmentTransferState.FAILED)) else emptyList()),
                    onAdd = actions.onAddAttachment,
                    onOpen = { model ->
                        state.draft.attachmentIds.indices.firstOrNull { index -> "specialized_attachment_$index" == model.stableKey }
                            ?.let(actions.onOpenAttachment)
                    },
                    onCancel = { item -> actions.onCancelAttachment(if (item.stableKey == "specialized_attachment_import") state.draft.attachmentIds.size else item.stableKey.substringAfterLast('_').toInt()) },
                    addLabel = stringResource(R.string.specialized_add_attachment),
                    onRetry = { actions.onAddAttachment() },
                )
            }
        }
    }
    if (showDateTimePicker) {
        val local = state.draft.occurredAt.atZone(state.draft.zoneId)
        val initialDateMillis = local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        if (state.kind == SpecializedTransactionKind.OPENING_BALANCE) {
            LedgerDatePickerFlow(
                initialDateMillis,
                { dateMillis ->
                    val date = Instant.ofEpochMilli(dateMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    actions.onOccurredAt(date.atTime(local.toLocalTime()).atZone(state.draft.zoneId).toInstant())
                    showDateTimePicker = false
                },
                { showDateTimePicker = false },
            )
        } else {
            LedgerDateTimePickerFlow(
                initialDateMillis = initialDateMillis,
                initialHour = local.hour,
                initialMinute = local.minute,
                onConfirm = { dateMillis, hour, minute ->
                    val date = Instant.ofEpochMilli(dateMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    actions.onOccurredAt(date.atTime(hour, minute).atZone(state.draft.zoneId).toInstant())
                    showDateTimePicker = false
                },
                onDismiss = { showDateTimePicker = false },
            )
        }
    }
    accountPickerIncoming?.let { incoming ->
        val accounts = SpecializedTransactionPolicy.selectableAccounts(state)
            .filter { accountSearch.isBlank() || it.accountLabel().contains(accountSearch, ignoreCase = true) }
        LedgerDialog(
            title = stringResource(if (incoming) R.string.specialized_to_account else R.string.specialized_from_account),
            message = null,
            confirmLabel = stringResource(R.string.specialized_apply_account),
            onConfirm = {
                pendingAccountId?.let { id ->
                    if (incoming) actions.onSelectToAccount(id) else actions.onSelectFromAccount(id)
                }
                accountPickerIncoming = null
            },
            onDismiss = { accountPickerIncoming = null },
            confirmEnabled = pendingAccountId != null,
        ) {
            SearchField(
                value = accountSearch,
                onValueChange = { accountSearch = it },
                placeholder = stringResource(R.string.specialized_search_accounts),
                onClear = { accountSearch = "" },
                autoFocus = false,
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(accounts, key = { it.id.toString() }) { account ->
                    LedgerChoiceRow(
                        title = account.accountLabel(),
                        selected = pendingAccountId == account.id,
                        onClick = { pendingAccountId = account.id },
                    )
                }
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
    val locale = LocalLocale.current.platformLocale
    val summary = SpecializedTransactionPolicy.fxDisplaySummary(state)
    FormSection(
        title = stringResource(R.string.specialized_effective_rate_title),
        modifier = Modifier.testTag(LedgerTestTags.EFFECTIVE_RATE_SUMMARY),
    ) {
        LedgerText(
            stringResource(
                R.string.specialized_effective_rate_value,
                from?.currency?.value.orEmpty(),
                summary.effectiveRate?.localizedRate(locale) ?: stringResource(R.string.specialized_pending_value),
                to?.currency?.value.orEmpty(),
            ),
            LedgerTextRole.BODY,
        )
        LedgerText(
            stringResource(
                R.string.specialized_reference_rate_value,
                from?.currency?.value.orEmpty(),
                summary.referenceRate?.localizedRate(locale) ?: stringResource(R.string.specialized_pending_value),
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
                summary.spreadCostBaseMinor?.let {
                    SpecializedTransactionPolicy.formatMoney(it, state.snapshot.baseCurrency, locale).formatted
                } ?: stringResource(R.string.specialized_pending_value),
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
    val locale = LocalLocale.current.platformLocale
    val candidates = state.snapshot.checkpoints.filter { it.accountId == state.draft.fromAccountId && it.adjustmentTransactionId == null }
    val current = candidates.singleOrNull { it.id == state.draft.checkpointId }
    SelectorField(
        stringResource(R.string.specialized_checkpoint),
        current?.asOfLocalDate?.localized(locale) ?: stringResource(R.string.specialized_none),
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
    val locale = LocalLocale.current.platformLocale
    if (account == null || account.currency == state.snapshot.baseCurrency) return
    val quote = state.quotesToBase[account.currency]
    val manual = if (incoming) state.draft.manualToBaseRate else state.draft.manualFromBaseRate
    val text = when {
        account.currency in state.quotePending -> stringResource(R.string.specialized_rate_loading)
        manual.isNotBlank() -> stringResource(
            R.string.specialized_rate_summary_with_source,
            account.currency.value,
            manual.toBigDecimalOrNull()?.localizedRate(locale) ?: manual,
            state.snapshot.baseCurrency.value,
            stringResource(R.string.specialized_rate_source_manual),
        )
        quote != null -> stringResource(
            R.string.specialized_rate_summary_with_source,
            account.currency.value,
            quote.evidence.rate.localizedRate(locale),
            state.snapshot.baseCurrency.value,
            fxSourceLabel(quote.evidence.source),
        )
        else -> stringResource(R.string.specialized_rate_unavailable)
    }
    LedgerText(text, LedgerTextRole.SUPPORTING)
    quote?.let {
        LedgerText(stringResource(R.string.specialized_rate_provider, it.evidence.provider.value), LedgerTextRole.SUPPORTING)
        it.evidence.quotedAt?.let { quotedAt ->
            LedgerText(stringResource(R.string.specialized_rate_time, quotedAt.localized(state.draft.zoneId, locale)), LedgerTextRole.SUPPORTING)
        }
        it.evidence.fetchedAt?.let { fetchedAt ->
            LedgerText(stringResource(R.string.specialized_rate_received_time, fetchedAt.localized(state.draft.zoneId, locale)), LedgerTextRole.SUPPORTING)
        }
    }
    if (quote?.stale == true) LedgerBanner(stringResource(R.string.specialized_rate_stale), LedgerBannerVariant.WARNING)
    LedgerTextField(manual, onManualRate, stringResource(R.string.specialized_manual_rate, account.currency.value, state.snapshot.baseCurrency.value), supportingText = stringResource(R.string.specialized_manual_override))
}

@Composable
private fun fxSourceLabel(source: FxRateSource): String = stringResource(
    when (source) {
        FxRateSource.ONLINE_LATEST -> R.string.specialized_rate_source_online
        FxRateSource.CACHE -> R.string.specialized_rate_source_cache
        FxRateSource.MANUAL -> R.string.specialized_rate_source_manual
        FxRateSource.IMPLIED_FROM_ACTUAL_AMOUNTS -> R.string.specialized_rate_source_implied
        FxRateSource.OFFICIAL_SETTLEMENT -> R.string.specialized_rate_source_official
        FxRateSource.HISTORICAL_FALLBACK -> R.string.specialized_rate_source_historical
    },
)

private fun BigDecimal.localizedRate(locale: Locale): String = NumberFormat.getNumberInstance(locale).apply {
    minimumFractionDigits = 0
    maximumFractionDigits = 10
    isGroupingUsed = true
}.format(this)

private fun LocalDate.localized(locale: Locale): String =
    LedgerDateFormatterRuntime.formatter(locale).format(this)

private fun Instant.localized(zoneId: java.time.ZoneId, locale: Locale): String =
    LedgerDateFormatterRuntime.dateTimeFormatter(locale)
        .withZone(zoneId)
        .format(this)

private fun AccountReferenceView.accountLabel(): String = "$name · ${currency.value}"

private fun String.matches(kind: SpecializedTransactionKind): Boolean = when (this) {
    "REC-013" -> kind == SpecializedTransactionKind.TRANSFER
    "REC-020" -> kind == SpecializedTransactionKind.BALANCE_ADJUSTMENT
    "REC-021" -> kind == SpecializedTransactionKind.FX_EXCHANGE
    "REC-022" -> kind == SpecializedTransactionKind.OPENING_BALANCE
    else -> false
}

@Composable
private fun SpecializedTransactionEditorState.assetEndpointHint(): String? = when (kind) {
    SpecializedTransactionKind.TRANSFER -> stringResource(R.string.specialized_transfer_asset_account_hint)
    SpecializedTransactionKind.FX_EXCHANGE -> stringResource(R.string.specialized_fx_asset_account_hint)
    else -> null
}

@Composable
private fun List<SpecializedValidationError>.fieldError(field: SpecializedField): String? = firstOrNull { it.field == field }?.let { error ->
    stringResource(
        if (error.code == "ASSET_ACCOUNT_REQUIRED") R.string.specialized_asset_account_required else R.string.specialized_invalid_field,
    )
}
