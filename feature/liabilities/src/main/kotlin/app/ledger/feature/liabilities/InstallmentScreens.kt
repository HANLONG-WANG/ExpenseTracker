@file:Suppress("FunctionNaming", "LongMethod", "MagicNumber", "MaxLineLength", "TooManyFunctions", "ktlint:standard:function-naming")

package app.ledger.feature.liabilities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import app.ledger.core.common.StableId
import app.ledger.core.designsystem.AccessibleDataTable
import app.ledger.core.designsystem.AccessibleTableUiModel
import app.ledger.core.designsystem.AmountSize
import app.ledger.core.designsystem.AmountText
import app.ledger.core.designsystem.FormSection
import app.ledger.core.designsystem.HighRiskConfirmation
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
import app.ledger.finance.application.InstallmentPlanView
import app.ledger.finance.domain.InstallmentFeeRateType
import app.ledger.finance.domain.InstallmentStatus

@Composable
public fun InstallmentDestination(
    screenId: String,
    state: InstallmentLoadState,
    encodedArguments: Map<String, String>,
    onAction: (InstallmentScreenAction) -> Unit,
) {
    val actions = installmentActions(onAction)
    when (state) {
        InstallmentLoadState.Loading -> LedgerLoadingState(Modifier.fillMaxSize(), stringResource(R.string.installment_loading))
        is InstallmentLoadState.Failure -> LedgerErrorState(UiErrorCode(state.code), stringResource(R.string.installment_load_failed), actions.onRetry)
        is InstallmentLoadState.Content -> {
            val planId = encodedArguments.stableId("planId") ?: state.state.selectedPlanId
            val purchaseId = encodedArguments.stableId("purchaseTransactionId") ?: state.state.selectedPurchaseId
            val content = state.state.copy(selectedPlanId = planId, selectedPurchaseId = purchaseId)
            when (screenId) {
                "REC-027" -> InstallmentEditor(content, actions, Modifier.testTag(LedgerTestTags.INSTALLMENT_SETUP))
                "INS-001" -> InstallmentList(content, actions)
                "INS-002" -> InstallmentEditor(content, actions, Modifier.testTag(LedgerTestTags.INSTALLMENT_EDITOR))
                "INS-003" -> InstallmentDetail(content, actions)
                "INS-004" -> InstallmentSchedule(content)
                "INS-005" -> EarlySettlement(content, actions)
                "INS-006" -> RefundImpact(content, actions)
                else -> LedgerErrorState(UiErrorCode("INSTALLMENT_SCREEN_UNKNOWN"), stringResource(R.string.installment_load_failed), actions.onRetry)
            }
        }
    }
}

@Composable
private fun InstallmentEditor(state: InstallmentFeatureState, actions: InstallmentActions, modifier: Modifier) {
    val purchase = state.snapshot.purchases.singleOrNull { it.transactionId == state.selectedPurchaseId }
    val plan = state.plan
    if (purchase == null && plan == null) {
        LedgerEmptyState(
            stringResource(R.string.installment_no_eligible_purchase),
            stringResource(R.string.installment_no_eligible_purchase_body),
            stringResource(R.string.installment_open_credit_accounts),
            { actions.onNavigate("LIA-001", null) },
            modifier.fillMaxSize(),
        )
        return
    }
    ScreenList(modifier) {
        if (state.presentation == InstallmentPresentation.INVALID) item { LedgerBanner(stringResource(R.string.installment_validation_error), LedgerBannerVariant.DANGER) }
        item {
            FormSection(stringResource(R.string.installment_linked_purchase)) {
                if (purchase == null && plan == null) {
                    LedgerText(stringResource(R.string.installment_select_purchase), LedgerTextRole.BODY)
                } else {
                    LedgerText(purchase?.creditAccountName ?: plan?.creditAccountName.orEmpty(), LedgerTextRole.SECTION)
                    AmountText(
                        CreditPolicy.money(
                            purchase?.principalMinor ?: plan?.originalPrincipalMinor ?: 0L,
                            purchase?.currency ?: requireNotNull(plan).currency,
                            LocalLocale.current.platformLocale,
                            AmountSemantic.OUTFLOW,
                        ),
                        AmountSize.MEDIUM,
                    )
                }
                state.snapshot.purchases.filter { !it.alreadyLinked || it.transactionId == state.selectedPurchaseId }.take(3).forEach { option ->
                    LedgerButton(option.creditAccountName, { actions.onSelectPurchase(option.transactionId) }, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY)
                }
            }
        }
        item { LedgerTextField(state.draft.termCount, { actions.onFieldChanged(InstallmentField.TERM_COUNT, it) }, stringResource(R.string.installment_term_count), Modifier.fillMaxWidth(), required = true, keyboardType = KeyboardType.Number) }
        item { LedgerTextField(state.draft.firstStatementDate, { actions.onFieldChanged(InstallmentField.FIRST_STATEMENT_DATE, it) }, stringResource(R.string.installment_first_statement), Modifier.fillMaxWidth(), required = true) }
        item { FeeModelEditor(state, actions) }
        item { SettlementRuleEditor(state, actions) }
        item { RefundPolicyEditor(state, actions) }
        item { LedgerButton(stringResource(R.string.installment_generate_preview), actions.onPreview, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY) }
        if (state.previewSchedule != null || plan != null) {
            item { SchedulePreview(plan, state.previewSchedule, purchase?.currency ?: plan?.currency) }
        }
        item { LedgerText(stringResource(R.string.installment_purchase_not_split), LedgerTextRole.SUPPORTING) }
    }
}

@Composable
private fun FeeModelEditor(state: InstallmentFeatureState, actions: InstallmentActions) {
    FormSection(stringResource(R.string.installment_fee_model)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            InstallmentFeeRateType.entries.forEach { model ->
                LedgerButton(
                    feeModelLabel(model),
                    { actions.onFeeModelChanged(model) },
                    variant = if (state.draft.feeModel == model) LedgerButtonVariant.PRIMARY else LedgerButtonVariant.SECONDARY,
                )
            }
        }
        when (state.draft.feeModel) {
            InstallmentFeeRateType.FIXED_PER_TERM, InstallmentFeeRateType.REMAINING_PRINCIPAL_RATE ->
                LedgerTextField(state.draft.feeValue, { actions.onFieldChanged(InstallmentField.FEE_VALUE, it) }, stringResource(R.string.installment_fee_or_rate), Modifier.fillMaxWidth(), required = true, keyboardType = KeyboardType.Decimal)
            InstallmentFeeRateType.FIRST_TERM_FIXED ->
                LedgerTextField(state.draft.firstTermFee, { actions.onFieldChanged(InstallmentField.FIRST_TERM_FEE, it) }, stringResource(R.string.installment_first_fee), Modifier.fillMaxWidth(), required = true, keyboardType = KeyboardType.Decimal)
            InstallmentFeeRateType.EFFECTIVE_ANNUAL_RATE ->
                LedgerTextField(state.draft.annualRate, { actions.onFieldChanged(InstallmentField.ANNUAL_RATE, it) }, stringResource(R.string.installment_actual_annual_rate), Modifier.fillMaxWidth(), required = true, keyboardType = KeyboardType.Decimal)
            InstallmentFeeRateType.NONE -> LedgerText(stringResource(R.string.installment_no_fee), LedgerTextRole.SUPPORTING)
        }
    }
}

@Composable
private fun SettlementRuleEditor(state: InstallmentFeatureState, actions: InstallmentActions) {
    FormSection(stringResource(R.string.installment_early_settlement_rule)) {
        LedgerTextField(state.draft.prepaymentFee, { actions.onFieldChanged(InstallmentField.PREPAYMENT_FEE, it) }, stringResource(R.string.installment_settlement_fee), Modifier.fillMaxWidth(), keyboardType = KeyboardType.Decimal)
        LedgerText(stringResource(R.string.installment_settlement_simulation_notice), LedgerTextRole.SUPPORTING)
    }
}

@Composable
private fun RefundPolicyEditor(state: InstallmentFeatureState, actions: InstallmentActions) {
    FormSection(stringResource(R.string.installment_refund_policy)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            app.ledger.finance.domain.InstallmentRefundPolicy.entries.forEach { policy ->
                LedgerButton(
                    refundPolicyLabel(policy),
                    { actions.onRefundPolicyChanged(policy) },
                    variant = if (state.draft.refundPolicy == policy) LedgerButtonVariant.PRIMARY else LedgerButtonVariant.TEXT,
                )
            }
        }
    }
}

@Composable
private fun InstallmentList(state: InstallmentFeatureState, actions: InstallmentActions) {
    if (state.snapshot.plans.isEmpty()) {
        val hasEligiblePurchase = state.snapshot.purchases.any { !it.alreadyLinked }
        Box(Modifier.fillMaxSize().testTag(LedgerTestTags.INSTALLMENT_LIST)) {
            LedgerEmptyState(
                stringResource(if (hasEligiblePurchase) R.string.installment_empty else R.string.installment_no_eligible_purchase),
                stringResource(if (hasEligiblePurchase) R.string.installment_empty_body else R.string.installment_no_eligible_purchase_body),
                stringResource(if (hasEligiblePurchase) R.string.installment_create else R.string.installment_open_credit_accounts),
                { actions.onNavigate(if (hasEligiblePurchase) "INS-002" else "LIA-001", null) },
            )
        }
        return
    }
    ScreenList(Modifier.testTag(LedgerTestTags.INSTALLMENT_LIST)) {
        item { LedgerBanner(stringResource(R.string.installment_commitment_explanation), LedgerBannerVariant.INFO) }
        items(state.snapshot.plans, key = { it.id.toString() }) { plan -> InstallmentPlanRow(plan, actions) }
        item { LedgerButton(stringResource(R.string.installment_create), { actions.onNavigate("INS-002", null) }, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun InstallmentPlanRow(plan: InstallmentPlanView, actions: InstallmentActions) {
    LedgerCard(Modifier.fillMaxWidth(), onClick = { actions.onNavigate("INS-003", plan.id) }) {
        Column(Modifier.padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LedgerText(plan.creditAccountName, LedgerTextRole.SECTION)
                StatusBadge(if (plan.status == InstallmentStatus.SETTLED) stringResource(R.string.installment_status_completed) else stringResource(R.string.installment_status_active), if (plan.status == InstallmentStatus.SETTLED) LedgerStatusVariant.POSITIVE else LedgerStatusVariant.INFO)
            }
            AmountText(CreditPolicy.money(plan.currentPrincipalMinor, plan.currency, LocalLocale.current.platformLocale), AmountSize.MEDIUM)
            LedgerText(stringResource(R.string.installment_terms_summary, plan.termCount, plan.currentSchedule.items.size), LedgerTextRole.SUPPORTING)
        }
    }
}

@Composable
private fun InstallmentDetail(state: InstallmentFeatureState, actions: InstallmentActions) {
    val plan = state.plan ?: return InstallmentNotFound(actions)
    val locale = LocalLocale.current.platformLocale
    ScreenList(Modifier.testTag(LedgerTestTags.INSTALLMENT_DETAIL)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LedgerText(plan.creditAccountName, LedgerTextRole.TITLE)
                StatusBadge(detailStatus(state.presentation), if (plan.status == InstallmentStatus.SETTLED) LedgerStatusVariant.POSITIVE else LedgerStatusVariant.INFO)
            }
        }
        if (state.presentation == InstallmentPresentation.REFUND_ADJUSTED) item { LedgerBanner(stringResource(R.string.installment_refund_adjusted), LedgerBannerVariant.WARNING) }
        item { MetricCard(stringResource(R.string.installment_posted_debt), CreditPolicy.money(plan.progress.postedPrincipalMinor, plan.currency, locale), Modifier.fillMaxWidth(), MetricCardVariant.EMPHASIZED) }
        item { MetricCard(stringResource(R.string.installment_unposted_commitment), CreditPolicy.money(plan.progress.unpostedCommittedPrincipalMinor, plan.currency, locale), Modifier.fillMaxWidth(), explanation = stringResource(R.string.installment_commitment_not_new_expense)) }
        item { MetricCard(stringResource(R.string.installment_remaining_principal), CreditPolicy.money(plan.currentPrincipalMinor, plan.currency, locale), Modifier.fillMaxWidth()) }
        item { MetricCard(stringResource(R.string.installment_paid_cost), CreditPolicy.money(plan.progress.paidCostMinor, plan.currency, locale), Modifier.fillMaxWidth()) }
        item { MetricCard(stringResource(R.string.installment_future_cost), CreditPolicy.money(plan.progress.futureCostMinor, plan.currency, locale), Modifier.fillMaxWidth()) }
        item { LedgerButton(stringResource(R.string.installment_open_schedule), { actions.onNavigate("INS-004", plan.id) }, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY) }
        item { LedgerButton(stringResource(R.string.installment_early_settlement), { actions.onNavigate("INS-005", plan.id) }, Modifier.fillMaxWidth()) }
        if (plan.refundedPrincipalMinor > 0L) item { LedgerButton(stringResource(R.string.installment_refund_impact), { actions.onNavigate("INS-006", plan.id) }, Modifier.fillMaxWidth(), LedgerButtonVariant.TEXT) }
        item { LedgerText(stringResource(R.string.installment_linked_purchase_value, plan.purchaseTransactionId.toString()), LedgerTextRole.SUPPORTING) }
    }
}

@Composable
private fun InstallmentSchedule(state: InstallmentFeatureState) {
    val plan = state.plan ?: return
    ScreenList(Modifier.testTag(LedgerTestTags.INSTALLMENT_SCHEDULE)) {
        item { AccessibleDataTable(scheduleTable(plan)) }
        item { LedgerBanner(stringResource(R.string.installment_rounding_tail), LedgerBannerVariant.INFO) }
        item { LedgerText(stringResource(R.string.installment_schedule_not_transaction), LedgerTextRole.SUPPORTING) }
    }
}

@Composable
private fun EarlySettlement(state: InstallmentFeatureState, actions: InstallmentActions) {
    val plan = state.plan ?: return InstallmentNotFound(actions)
    val simulation = state.simulation
    val locale = LocalLocale.current.platformLocale
    ScreenList(Modifier.testTag(LedgerTestTags.INSTALLMENT_SETTLEMENT)) {
        if (state.presentation == InstallmentPresentation.INVALID) item { LedgerBanner(stringResource(R.string.installment_settlement_invalid), LedgerBannerVariant.DANGER) }
        item { LedgerTextField(state.draft.settlementDate, { actions.onFieldChanged(InstallmentField.SETTLEMENT_DATE, it) }, stringResource(R.string.installment_settlement_date), Modifier.fillMaxWidth(), required = true) }
        item { LedgerButton(stringResource(R.string.installment_calculate), actions.onCalculateSettlement, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY) }
        if (simulation != null) {
            item { MetricCard(stringResource(R.string.installment_remaining_principal), CreditPolicy.money(simulation.outstandingPrincipalMinor, plan.currency, locale), Modifier.fillMaxWidth()) }
            item { MetricCard(stringResource(R.string.installment_future_cost), CreditPolicy.money(simulation.futureCostMinor, plan.currency, locale), Modifier.fillMaxWidth()) }
            item { MetricCard(stringResource(R.string.installment_settlement_fee), CreditPolicy.money(simulation.settlementFeeMinor, plan.currency, locale), Modifier.fillMaxWidth()) }
            item { MetricCard(stringResource(R.string.installment_saved_cost), CreditPolicy.money(simulation.savedCostMinor, plan.currency, locale), Modifier.fillMaxWidth(), MetricCardVariant.EMPHASIZED) }
            item { LedgerBanner(stringResource(R.string.installment_simulation_no_write), LedgerBannerVariant.INFO) }
            item {
                HighRiskConfirmation(
                    stringResource(R.string.installment_apply_and_record),
                    stringResource(R.string.installment_confirmation_scope),
                    stringResource(R.string.installment_confirmation_consequence),
                    stringResource(R.string.installment_confirmation_unaffected),
                    stringResource(R.string.installment_confirmation_phrase),
                    state.draft.confirmPhrase,
                    { actions.onFieldChanged(InstallmentField.CONFIRM_PHRASE, it) },
                    actions.onApplySettlement,
                    {},
                )
            }
        }
    }
}

@Composable
private fun RefundImpact(state: InstallmentFeatureState, actions: InstallmentActions) {
    val plan = state.plan ?: return InstallmentNotFound(actions)
    val locale = LocalLocale.current.platformLocale
    ScreenList(Modifier.testTag(LedgerTestTags.INSTALLMENT_REFUND)) {
        if (state.presentation == InstallmentPresentation.REQUIRES_DECISION) item { LedgerBanner(stringResource(R.string.installment_refund_decision_required), LedgerBannerVariant.WARNING) }
        item { MetricCard(stringResource(R.string.installment_refunded_principal), CreditPolicy.money(plan.refundedPrincipalMinor, plan.currency, locale), Modifier.fillMaxWidth()) }
        item { MetricCard(stringResource(R.string.installment_refunded_fee), CreditPolicy.money(plan.refundedFeeMinor, plan.currency, locale), Modifier.fillMaxWidth()) }
        item { RefundPolicyEditor(state, actions) }
        item { LedgerText(stringResource(R.string.installment_old_plan_preserved), LedgerTextRole.BODY) }
        item { AccessibleDataTable(scheduleTable(plan)) }
    }
}

@Composable
private fun SchedulePreview(
    plan: InstallmentPlanView?,
    preview: app.ledger.finance.domain.InstallmentScheduleRevision?,
    currency: app.ledger.core.money.CurrencyCode?,
) {
    if (plan == null && preview == null) {
        LedgerBanner(stringResource(R.string.installment_preview_ready), LedgerBannerVariant.INFO)
    } else {
        val resolved = plan?.currentSchedule ?: requireNotNull(preview)
        if (currency == null) {
            LedgerBanner(stringResource(R.string.installment_validation_error), LedgerBannerVariant.DANGER)
        } else {
            AccessibleDataTable(scheduleTable(resolved, currency))
        }
    }
}

@Composable
private fun scheduleTable(plan: InstallmentPlanView): AccessibleTableUiModel = AccessibleTableUiModel(
    stringResource(R.string.installment_schedule_caption),
    listOf(
        stringResource(R.string.installment_col_number),
        stringResource(R.string.installment_col_date),
        stringResource(R.string.installment_col_principal),
        stringResource(R.string.installment_col_interest),
        stringResource(R.string.installment_col_fee),
        stringResource(R.string.installment_col_remaining),
    ),
    plan.currentSchedule.items.map { item ->
        val locale = LocalLocale.current.platformLocale
        listOf(
            item.installmentNumber.toString(),
            item.statementDate.toString(),
            CreditPolicy.money(item.principalMinor, plan.currency, locale).formatted,
            CreditPolicy.money(item.interestMinor, plan.currency, locale).formatted,
            CreditPolicy.money(item.feeMinor, plan.currency, locale).formatted,
            CreditPolicy.money(item.remainingPrincipalMinor, plan.currency, locale).formatted,
        )
    },
)

@Composable
private fun scheduleTable(
    schedule: app.ledger.finance.domain.InstallmentScheduleRevision,
    currency: app.ledger.core.money.CurrencyCode,
): AccessibleTableUiModel = AccessibleTableUiModel(
    stringResource(R.string.installment_schedule_caption),
    listOf(
        stringResource(R.string.installment_col_number),
        stringResource(R.string.installment_col_date),
        stringResource(R.string.installment_col_principal),
        stringResource(R.string.installment_col_interest),
        stringResource(R.string.installment_col_fee),
        stringResource(R.string.installment_col_remaining),
    ),
    schedule.items.map { item ->
        val locale = LocalLocale.current.platformLocale
        listOf(
            item.installmentNumber.toString(),
            item.statementDate.toString(),
            CreditPolicy.money(item.principalMinor, currency, locale).formatted,
            CreditPolicy.money(item.interestMinor, currency, locale).formatted,
            CreditPolicy.money(item.feeMinor, currency, locale).formatted,
            CreditPolicy.money(item.remainingPrincipalMinor, currency, locale).formatted,
        )
    },
)

@Composable
private fun feeModelLabel(model: InstallmentFeeRateType): String = when (model) {
    InstallmentFeeRateType.NONE -> stringResource(R.string.installment_model_none)
    InstallmentFeeRateType.FIXED_PER_TERM -> stringResource(R.string.installment_model_fixed)
    InstallmentFeeRateType.FIRST_TERM_FIXED -> stringResource(R.string.installment_model_first)
    InstallmentFeeRateType.REMAINING_PRINCIPAL_RATE -> stringResource(R.string.installment_model_remaining)
    InstallmentFeeRateType.EFFECTIVE_ANNUAL_RATE -> stringResource(R.string.installment_model_annual)
}

@Composable
private fun refundPolicyLabel(policy: app.ledger.finance.domain.InstallmentRefundPolicy): String = when (policy) {
    app.ledger.finance.domain.InstallmentRefundPolicy.REDUCE_REMAINING_PRINCIPAL -> stringResource(R.string.installment_refund_reduce_principal)
    app.ledger.finance.domain.InstallmentRefundPolicy.REDUCE_FINAL_TERMS -> stringResource(R.string.installment_refund_reduce_terms)
    app.ledger.finance.domain.InstallmentRefundPolicy.REBUILD_SCHEDULE -> stringResource(R.string.installment_refund_rebuild)
}

@Composable
private fun detailStatus(presentation: InstallmentPresentation): String = when (presentation) {
    InstallmentPresentation.COMPLETED -> stringResource(R.string.installment_status_completed)
    InstallmentPresentation.REFUND_ADJUSTED -> stringResource(R.string.installment_status_refund_adjusted)
    else -> stringResource(R.string.installment_status_active)
}

@Composable
private fun InstallmentNotFound(actions: InstallmentActions) {
    LedgerErrorState(UiErrorCode("INSTALLMENT_NOT_FOUND"), stringResource(R.string.installment_not_found), actions.onRetry)
}

private fun Map<String, String>.stableId(key: String): StableId? = get(key)?.let { app.ledger.core.common.StableId.parse(it).getOrNull() }
private fun <T> app.ledger.core.common.DomainResult<T>.getOrNull(): T? = when (this) {
    is app.ledger.core.common.DomainResult.Success -> value
    is app.ledger.core.common.DomainResult.Failure -> null
}

@Composable
private fun ScreenList(modifier: Modifier, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(LedgerTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
        content = content,
    )
}
