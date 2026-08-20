@file:Suppress("FunctionNaming", "LongMethod", "MagicNumber", "MaxLineLength", "TooManyFunctions", "ktlint:standard:function-naming")

package app.ledger.feature.settlement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.text.input.KeyboardType
import app.ledger.core.designsystem.AccessibleDataTable
import app.ledger.core.designsystem.AccessibleTableUiModel
import app.ledger.core.designsystem.AmountSize
import app.ledger.core.designsystem.AmountText
import app.ledger.core.designsystem.FormSection
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerButton
import app.ledger.core.designsystem.LedgerButtonVariant
import app.ledger.core.designsystem.LedgerCard
import app.ledger.core.designsystem.LedgerEmptyState
import app.ledger.core.designsystem.LedgerErrorState
import app.ledger.core.designsystem.LedgerLoadingState
import app.ledger.core.designsystem.LedgerStatusVariant
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextField
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.MetricCard
import app.ledger.core.designsystem.MetricCardVariant
import app.ledger.core.designsystem.StatusBadge
import app.ledger.core.designsystem.UiErrorCode
import app.ledger.core.money.AmountSemantic
import app.ledger.finance.application.SettlementActivityView
import app.ledger.finance.domain.SettlementActivityStatus
import app.ledger.finance.domain.SettlementChargeDistribution
import app.ledger.finance.domain.SettlementRoundingRule
import app.ledger.finance.domain.SettlementSplitMethod

@Composable
public fun SettlementDestination(
    screenId: String,
    state: SettlementLoadState,
    actions: SettlementActions,
) {
    when (state) {
        SettlementLoadState.Loading -> LedgerLoadingState(Modifier.fillMaxSize(), stringResource(R.string.settlement_loading))
        is SettlementLoadState.Failure -> LedgerErrorState(UiErrorCode(state.code), stringResource(R.string.settlement_load_failed), actions.onRetry)
        is SettlementLoadState.Content -> when (screenId) {
            "SET-001" -> SettlementHome(state.state, actions)
            "SET-002" -> SettlementEditor(state.state, actions)
            "SET-003" -> ParticipantManager(state.state, actions)
            "SET-004" -> SettlementDetail(state.state, actions)
            "SET-005" -> PositionDetail(state.state, actions)
            "SET-006" -> PaymentEditor(state.state, actions)
            "SET-007" -> PaymentHistory(state.state, actions)
            "SET-008" -> AdditionalSettlement(state.state, actions)
            else -> LedgerErrorState(UiErrorCode("SETTLEMENT_SCREEN_UNKNOWN"), stringResource(R.string.settlement_load_failed), actions.onRetry)
        }
    }
}

@Composable
private fun SettlementHome(state: SettlementFeatureState, actions: SettlementActions) {
    if (state.snapshot.activities.isEmpty() || state.presentation == SettlementPresentation.EMPTY) {
        return LedgerEmptyState(
            title = stringResource(R.string.settlement_empty_title),
            explanation = stringResource(R.string.settlement_empty_body),
            primaryAction = stringResource(R.string.settlement_create),
            onPrimaryAction = { actions.onNavigate("SET-002", null, null) },
            modifier = Modifier.fillMaxSize().testTag(LedgerTestTags.SETTLEMENT_HOME),
        )
    }
    SettlementList(Modifier.testTag(LedgerTestTags.SETTLEMENT_HOME)) {
        if (state.presentation == SettlementPresentation.REQUIRES_ADDITIONAL_SETTLEMENT) {
            item { LedgerBanner(stringResource(R.string.settlement_additional_banner), LedgerBannerVariant.WARNING) }
        }
        items(state.snapshot.activities, key = { it.id.toString() }) { activity -> ActivityCard(activity, actions) }
        item { LedgerButton(stringResource(R.string.settlement_create), { actions.onNavigate("SET-002", null, null) }, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun SettlementEditor(state: SettlementFeatureState, actions: SettlementActions) = SettlementList(Modifier.testTag(LedgerTestTags.SETTLEMENT_EDITOR)) {
    item { StateBanner(state) }
    item { LedgerTextField(state.draft.name, { actions.onFieldChanged(SettlementField.NAME, it) }, stringResource(R.string.settlement_name), Modifier.fillMaxWidth(), errorText = stringResource(R.string.settlement_validation).takeIf { "name" in state.validationFields }, required = true) }
    item { LedgerTextField(state.draft.description, { actions.onFieldChanged(SettlementField.DESCRIPTION, it) }, stringResource(R.string.settlement_description), Modifier.fillMaxWidth()) }
    item { LedgerTextField(state.draft.startDate, { actions.onFieldChanged(SettlementField.START_DATE, it) }, stringResource(R.string.settlement_start_date), Modifier.fillMaxWidth(), errorText = stringResource(R.string.settlement_validation).takeIf { "startDate" in state.validationFields }, required = true) }
    item { LedgerTextField(state.draft.endDate, { actions.onFieldChanged(SettlementField.END_DATE, it) }, stringResource(R.string.settlement_end_date), Modifier.fillMaxWidth(), errorText = stringResource(R.string.settlement_validation).takeIf { "endDate" in state.validationFields }) }
    item {
        FormSection(
            stringResource(R.string.settlement_currency),
            description = stringResource(R.string.settlement_currency_locked),
        ) {
            val currencies = (listOf(state.snapshot.baseCurrency) + state.snapshot.accounts.filter { it.active }.map { it.currency })
                .distinct()
                .sorted()
            if (state.activity != null) {
                LedgerText(state.activity.currency.value, LedgerTextRole.BODY)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                    currencies.forEach { currency ->
                        LedgerButton(
                            currency.value,
                            { actions.onSelectCurrency(currency) },
                            variant = if (state.draft.currency == currency) LedgerButtonVariant.TONAL else LedgerButtonVariant.TEXT,
                        )
                    }
                }
            }
        }
    }
    item {
        FormSection(stringResource(R.string.settlement_project), description = stringResource(R.string.settlement_project_support)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                LedgerButton(stringResource(R.string.settlement_no_project), { actions.onSelectProject(null) }, variant = if (state.draft.projectId == null) LedgerButtonVariant.TONAL else LedgerButtonVariant.TEXT)
                state.snapshot.projects.filter { it.active }.forEach { project ->
                    LedgerButton(project.name, { actions.onSelectProject(project.id) }, variant = if (state.draft.projectId == project.id) LedgerButtonVariant.TONAL else LedgerButtonVariant.TEXT)
                }
            }
        }
    }
    item { ParticipantChips(state, actions) }
    item { LedgerTextField(state.draft.participantName, { actions.onFieldChanged(SettlementField.PARTICIPANT_NAME, it) }, stringResource(R.string.settlement_participant_name), Modifier.fillMaxWidth()) }
    item { LedgerButton(stringResource(R.string.settlement_add_participant), actions.onAddParticipant, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY) }
    item { StateBanner(state) }
    item { LedgerButton(stringResource(R.string.settlement_save_activity), actions.onSave, Modifier.fillMaxWidth()) }
}

@Composable
private fun ParticipantManager(state: SettlementFeatureState, actions: SettlementActions) = SettlementList(Modifier.testTag(LedgerTestTags.SETTLEMENT_PARTICIPANTS)) {
    item { LedgerTextField(state.draft.participantName, { actions.onFieldChanged(SettlementField.PARTICIPANT_NAME, it) }, stringResource(R.string.settlement_participant_name), Modifier.fillMaxWidth(), required = true) }
    item { LedgerButton(stringResource(R.string.settlement_add_participant), actions.onAddParticipant, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY) }
    if (state.draft.participants.isEmpty()) {
        item { LedgerText(stringResource(R.string.settlement_participant_empty), LedgerTextRole.TITLE) }
    } else {
        item { LedgerBanner(stringResource(R.string.settlement_unique_self), LedgerBannerVariant.INFO) }
        items(state.draft.participants, key = { it.id.toString() }) { participant ->
            LedgerCard(Modifier.fillMaxWidth(), onClick = { actions.onToggleParticipant(participant.id) }) {
                Column(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        LedgerText(participant.name, LedgerTextRole.BODY)
                        StatusBadge(
                            if (participant.isSelf) {
                                stringResource(R.string.settlement_self)
                            } else if (participant.included) {
                                stringResource(R.string.settlement_included)
                            } else {
                                stringResource(R.string.settlement_excluded)
                            },
                            if (participant.included) LedgerStatusVariant.POSITIVE else LedgerStatusVariant.NEUTRAL,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                        LedgerButton(stringResource(R.string.settlement_move_up), { actions.onMoveParticipant(participant.id, -1) }, variant = LedgerButtonVariant.TEXT)
                        LedgerButton(stringResource(R.string.settlement_move_down), { actions.onMoveParticipant(participant.id, 1) }, variant = LedgerButtonVariant.TEXT)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettlementDetail(state: SettlementFeatureState, actions: SettlementActions) {
    val activity = state.activity ?: return LedgerEmptyState(stringResource(R.string.settlement_missing), stringResource(R.string.settlement_missing_body), stringResource(R.string.settlement_reload), actions.onRetry, Modifier.fillMaxSize().testTag(LedgerTestTags.SETTLEMENT_DETAIL))
    SettlementList(Modifier.testTag(LedgerTestTags.SETTLEMENT_DETAIL)) {
        item { StateBanner(state) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LedgerText(activity.name, LedgerTextRole.TITLE)
                StatusBadge(activityStatus(activity.status), activityVariant(activity.status))
            }
        }
        activity.description?.let { description -> item { LedgerText(description, LedgerTextRole.BODY) } }
        item { PositionSummary(activity) }
        items(activity.positions, key = { it.participantId.toString() }) { position ->
            val participant = activity.participants.single { it.id == position.participantId }
            LedgerCard(Modifier.fillMaxWidth(), onClick = { actions.onNavigate("SET-005", activity.id, participant.id) }) {
                Row(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm), horizontalArrangement = Arrangement.SpaceBetween) {
                    LedgerText(participant.name, LedgerTextRole.BODY)
                    LedgerText(money(position.netPositionMinor, activity, LocalLocale.current.platformLocale), LedgerTextRole.BODY)
                }
            }
        }
        item { LedgerButton(stringResource(R.string.settlement_record_payment), { actions.onNavigate("SET-006", activity.id, null) }, Modifier.fillMaxWidth()) }
        item { LedgerButton(stringResource(R.string.settlement_payment_history), { actions.onNavigate("SET-007", activity.id, null) }, Modifier.fillMaxWidth(), LedgerButtonVariant.TEXT) }
        if (activity.requiresAdditionalSettlement) item { LedgerButton(stringResource(R.string.settlement_additional_action), { actions.onNavigate("SET-008", activity.id, null) }, Modifier.fillMaxWidth(), LedgerButtonVariant.TONAL) }
    }
}

@Composable
private fun PositionDetail(state: SettlementFeatureState, actions: SettlementActions) {
    val activity = state.activity ?: return LedgerEmptyState(stringResource(R.string.settlement_position_zero), stringResource(R.string.settlement_missing_body), stringResource(R.string.settlement_reload), actions.onRetry, Modifier.fillMaxSize().testTag(LedgerTestTags.SETTLEMENT_POSITIONS))
    val locale = LocalLocale.current.platformLocale
    val selectedId = state.selectedParticipantId ?: activity.participants.singleOrNull { it.isSelf }?.id
    val selectedPosition = activity.positions.singleOrNull { it.participantId == selectedId }
    val selectedParticipant = activity.participants.singleOrNull { it.id == selectedId }
    SettlementList(Modifier.testTag(LedgerTestTags.SETTLEMENT_POSITIONS)) {
        item { StateBanner(state) }
        selectedParticipant?.let { participant -> item { LedgerText(participant.name, LedgerTextRole.TITLE) } }
        item {
            AccessibleDataTable(
                AccessibleTableUiModel(
                    stringResource(R.string.settlement_position_table),
                    listOf(stringResource(R.string.settlement_participant), stringResource(R.string.settlement_paid), stringResource(R.string.settlement_owed), stringResource(R.string.settlement_settled), stringResource(R.string.settlement_net)),
                    listOfNotNull(selectedPosition).map { position ->
                        val participant = activity.participants.single { it.id == position.participantId }
                        listOf(participant.name, money(position.paidMinor, activity, locale), money(position.owedMinor, activity, locale), money(Math.subtractExact(position.settledPaidMinor, position.settledReceivedMinor), activity, locale), money(position.netPositionMinor, activity, locale))
                    },
                ),
            )
        }
        val related = activity.transactions.filter { transaction -> selectedId != null && (transaction.payerParticipantId == selectedId || (transaction.owedMinorByParticipant[selectedId] ?: 0L) > 0L) }
        item { LedgerText(stringResource(R.string.settlement_related_transactions), LedgerTextRole.SECTION) }
        if (related.isEmpty()) item { LedgerText(stringResource(R.string.settlement_related_empty), LedgerTextRole.SUPPORTING) }
        items(related, key = { it.transactionId.toString() }) { transaction ->
            LedgerText(stringResource(R.string.settlement_related_row, transaction.occurredAt.toString(), transaction.owedMinorByParticipant[selectedId] ?: 0L), LedgerTextRole.BODY)
        }
        if (activity.suggestions.isEmpty()) item { LedgerBanner(stringResource(R.string.settlement_no_suggestion), LedgerBannerVariant.INFO) }
        items(activity.suggestions) { suggestion ->
            val payer = activity.participants.single { it.id == suggestion.payerParticipantId.value }
            val payee = activity.participants.single { it.id == suggestion.payeeParticipantId.value }
            LedgerText(stringResource(R.string.settlement_suggestion, payer.name, payee.name, money(suggestion.amountMinor, activity, locale)), LedgerTextRole.BODY)
        }
    }
}

@Composable
private fun PaymentEditor(state: SettlementFeatureState, actions: SettlementActions) {
    val activity = state.activity ?: return LedgerEmptyState(stringResource(R.string.settlement_missing), stringResource(R.string.settlement_missing_body), stringResource(R.string.settlement_reload), actions.onRetry, Modifier.fillMaxSize().testTag(LedgerTestTags.SETTLEMENT_PAYMENT))
    SettlementList(Modifier.testTag(LedgerTestTags.SETTLEMENT_PAYMENT)) {
        item { StateBanner(state) }
        item { ParticipantSelector(stringResource(R.string.settlement_payer), activity, state.draft.payerParticipantId, actions.onSelectPayer) }
        item { ParticipantSelector(stringResource(R.string.settlement_payee), activity, state.draft.payeeParticipantId, actions.onSelectPayee) }
        item { LedgerTextField(state.draft.total, { actions.onFieldChanged(SettlementField.TOTAL, it) }, stringResource(R.string.settlement_amount), Modifier.fillMaxWidth(), required = true, keyboardType = KeyboardType.Decimal) }
        val payerSelf = activity.participants.singleOrNull { it.id == state.draft.payerParticipantId }?.isSelf == true
        val payeeSelf = activity.participants.singleOrNull { it.id == state.draft.payeeParticipantId }?.isSelf == true
        if (payerSelf || payeeSelf) {
            item { AccountSelector(state, actions) }
            item { LedgerBanner(stringResource(R.string.settlement_self_account_required), LedgerBannerVariant.INFO) }
        } else {
            item { LedgerBanner(stringResource(R.string.settlement_external_no_account), LedgerBannerVariant.INFO) }
        }
        item { LedgerTextField(state.draft.note, { actions.onFieldChanged(SettlementField.NOTE, it) }, stringResource(R.string.settlement_note), Modifier.fillMaxWidth()) }
        item { LedgerButton(stringResource(R.string.settlement_save_payment), actions.onSave, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun PaymentHistory(state: SettlementFeatureState, actions: SettlementActions) {
    val activity = state.activity
    if (activity == null || activity.payments.isEmpty()) {
        return LedgerEmptyState(stringResource(R.string.settlement_history_empty), stringResource(R.string.settlement_history_empty_body), stringResource(R.string.settlement_reload), actions.onRetry, Modifier.fillMaxSize().testTag(LedgerTestTags.SETTLEMENT_HISTORY))
    }
    SettlementList(Modifier.testTag(LedgerTestTags.SETTLEMENT_HISTORY)) {
        items(activity.payments, key = { it.id.toString() }) { payment ->
            LedgerCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                    val payer = activity.participants.single { it.id == payment.payerParticipantId }
                    val payee = activity.participants.single { it.id == payment.payeeParticipantId }
                    LedgerText(stringResource(R.string.settlement_payment_row, payer.name, payee.name), LedgerTextRole.SECTION)
                    AmountText(SettlementPolicy.money(payment.amountMinor, activity.currency, LocalLocale.current.platformLocale), AmountSize.LIST)
                    LedgerText(payment.occurredAt.toString(), LedgerTextRole.SUPPORTING)
                    LedgerText(if (payment.linkedTransactionId == null) stringResource(R.string.settlement_subledger_only) else stringResource(R.string.settlement_linked_transaction), LedgerTextRole.SUPPORTING)
                }
            }
        }
    }
}

@Composable
private fun AdditionalSettlement(state: SettlementFeatureState, actions: SettlementActions) {
    val activity = state.activity ?: return LedgerEmptyState(stringResource(R.string.settlement_missing), stringResource(R.string.settlement_missing_body), stringResource(R.string.settlement_reload), actions.onRetry, Modifier.fillMaxSize().testTag(LedgerTestTags.SETTLEMENT_ADDITIONAL))
    SettlementList(Modifier.testTag(LedgerTestTags.SETTLEMENT_ADDITIONAL)) {
        item { StateBanner(state) }
        if (activity.requiresAdditionalSettlement) {
            item { LedgerBanner(stringResource(R.string.settlement_history_immutable), LedgerBannerVariant.WARNING) }
            item { LedgerText(stringResource(R.string.settlement_theoretical_recalculated), LedgerTextRole.BODY) }
            item { PositionSummary(activity) }
            item { LedgerButton(stringResource(R.string.settlement_record_supplement), { actions.onNavigate("SET-006", activity.id, null) }, Modifier.fillMaxWidth()) }
        } else {
            item { LedgerBanner(stringResource(R.string.settlement_resolved), LedgerBannerVariant.INFO) }
        }
        item { LedgerButton(stringResource(R.string.settlement_rebuild), actions.onRebuild, Modifier.fillMaxWidth(), LedgerButtonVariant.TEXT) }
    }
}

@Composable
private fun ActivityCard(activity: SettlementActivityView, actions: SettlementActions) {
    LedgerCard(Modifier.fillMaxWidth(), onClick = { actions.onNavigate("SET-004", activity.id, null) }) {
        Column(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LedgerText(activity.name, LedgerTextRole.SECTION)
                StatusBadge(activityStatus(activity.status), activityVariant(activity.status))
            }
            LedgerText(stringResource(R.string.settlement_member_count, activity.participants.size), LedgerTextRole.SUPPORTING)
            PositionSummary(activity)
        }
    }
}

@Composable
private fun PositionSummary(activity: SettlementActivityView) {
    val self = activity.participants.singleOrNull { it.isSelf }
    val net = activity.positions.singleOrNull { it.participantId == self?.id }?.netPositionMinor ?: 0L
    MetricCard(
        if (net > 0L) {
            stringResource(R.string.settlement_you_receive)
        } else if (net < 0L) {
            stringResource(R.string.settlement_you_pay)
        } else {
            stringResource(R.string.settlement_balanced)
        },
        SettlementPolicy.money(
            if (net < 0L) Math.negateExact(net) else net,
            activity.currency,
            LocalLocale.current.platformLocale,
            if (net > 0L) {
                AmountSemantic.INFLOW
            } else if (net < 0L) {
                AmountSemantic.OUTFLOW
            } else {
                AmountSemantic.NEUTRAL
            },
        ),
        Modifier.fillMaxWidth(),
        MetricCardVariant.EMPHASIZED,
    )
}

@Composable
private fun ParticipantChips(state: SettlementFeatureState, actions: SettlementActions) {
    FormSection(stringResource(R.string.settlement_participants), description = stringResource(R.string.settlement_participants_support)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            state.draft.participants.forEach { participant ->
                LedgerButton(
                    if (participant.isSelf) stringResource(R.string.settlement_self_name, participant.name) else participant.name,
                    { actions.onToggleParticipant(participant.id) },
                    variant = if (participant.included) LedgerButtonVariant.TONAL else LedgerButtonVariant.TEXT,
                )
            }
        }
    }
}

@Composable
private fun ParticipantSelector(label: String, activity: SettlementActivityView, selected: app.ledger.core.common.StableId?, onSelect: (app.ledger.core.common.StableId) -> Unit) {
    FormSection(label) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            activity.participants.forEach { participant -> LedgerButton(participant.name, { onSelect(participant.id) }, variant = if (participant.id == selected) LedgerButtonVariant.TONAL else LedgerButtonVariant.TEXT) }
        }
    }
}

@Composable
private fun AccountSelector(state: SettlementFeatureState, actions: SettlementActions) = FormSection(stringResource(R.string.settlement_account)) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
        state.snapshot.accounts.filter { it.active && it.currency == state.activity?.currency }.forEach { account ->
            LedgerButton(account.name, { actions.onSelectAccount(account.id) }, variant = if (state.draft.accountId == account.id) LedgerButtonVariant.TONAL else LedgerButtonVariant.TEXT)
        }
    }
}

@Composable
private fun StateBanner(state: SettlementFeatureState) {
    when (state.presentation) {
        SettlementPresentation.VALIDATION_ERROR -> LedgerBanner(stringResource(R.string.settlement_validation), LedgerBannerVariant.DANGER)
        SettlementPresentation.REQUIRES_ADDITIONAL_SETTLEMENT, SettlementPresentation.REQUIRED -> LedgerBanner(stringResource(R.string.settlement_additional_banner), LedgerBannerVariant.WARNING)
        SettlementPresentation.SAVING -> LedgerBanner(stringResource(R.string.settlement_saving), LedgerBannerVariant.INFO)
        SettlementPresentation.SETTLED, SettlementPresentation.RESOLVED -> LedgerBanner(stringResource(R.string.settlement_resolved), LedgerBannerVariant.INFO)
        SettlementPresentation.RECEIVABLE -> LedgerBanner(stringResource(R.string.settlement_receivable), LedgerBannerVariant.INFO)
        SettlementPresentation.PAYABLE -> LedgerBanner(stringResource(R.string.settlement_payable), LedgerBannerVariant.WARNING)
        SettlementPresentation.ZERO -> LedgerBanner(stringResource(R.string.settlement_position_zero), LedgerBannerVariant.NEUTRAL)
        SettlementPresentation.EXTERNAL_TO_EXTERNAL -> LedgerBanner(stringResource(R.string.settlement_external_no_account), LedgerBannerVariant.INFO)
        else -> Unit
    }
}

@Composable
private fun SettlementList(modifier: Modifier, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) = LazyColumn(
    modifier.fillMaxSize().padding(horizontal = LedgerTheme.spacing.md),
    verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
    content = content,
)

@Composable
private fun activityStatus(status: SettlementActivityStatus): String = when (status) {
    SettlementActivityStatus.ACTIVE -> stringResource(R.string.settlement_status_open)
    SettlementActivityStatus.SETTLED -> stringResource(R.string.settlement_status_settled)
    SettlementActivityStatus.REQUIRES_ADDITIONAL_SETTLEMENT -> stringResource(R.string.settlement_status_additional)
    SettlementActivityStatus.ARCHIVED -> stringResource(R.string.settlement_status_archived)
}

private fun activityVariant(status: SettlementActivityStatus): LedgerStatusVariant = when (status) {
    SettlementActivityStatus.ACTIVE -> LedgerStatusVariant.INFO
    SettlementActivityStatus.SETTLED -> LedgerStatusVariant.POSITIVE
    SettlementActivityStatus.REQUIRES_ADDITIONAL_SETTLEMENT -> LedgerStatusVariant.WARNING
    SettlementActivityStatus.ARCHIVED -> LedgerStatusVariant.ARCHIVED
}

private fun money(minor: Long, activity: SettlementActivityView, locale: java.util.Locale): String = SettlementPolicy.money(minor, activity.currency, locale).formatted
