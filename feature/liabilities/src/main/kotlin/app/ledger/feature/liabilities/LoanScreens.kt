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
import app.ledger.core.common.getOrNull
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
import app.ledger.finance.application.CreditAccountView
import app.ledger.finance.application.LoanContractView
import app.ledger.finance.application.LoanScheduleItemView
import app.ledger.finance.application.LoanTrancheView
import app.ledger.finance.domain.LoanRepaymentMethod
import app.ledger.finance.domain.LoanStatus
import app.ledger.finance.domain.PrepaymentRecalculationStrategy

@Composable
public fun LoanDestination(
    screenId: String,
    state: LoanLoadState,
    encodedArguments: Map<String, String>,
    actions: LoanActions,
) {
    when (state) {
        LoanLoadState.Loading -> LedgerLoadingState(Modifier.fillMaxSize(), stringResource(R.string.loan_loading))
        is LoanLoadState.Failure -> LedgerErrorState(UiErrorCode(state.code), stringResource(R.string.loan_load_failed), actions.onRetry)
        is LoanLoadState.Content -> {
            val contractId = encodedArguments.loanId("contractId") ?: state.state.selectedContractId
            val trancheId = encodedArguments.loanId("trancheId") ?: state.state.selectedTrancheId
            val content = state.state.copy(
                selectedContractId = contractId,
                selectedTrancheId = trancheId,
                selectedTransactionId = encodedArguments.loanId("transactionId") ?: state.state.selectedTransactionId,
                selectedSimulationId = encodedArguments.loanId("simulationId") ?: state.state.selectedSimulationId,
            )
            when (screenId) {
                "LIA-001" -> LiabilityHome(content, actions)
                "REC-017" -> LoanOperation(content, actions)
                "REC-018" -> LoanDisbursement(content, actions)
                "REC-019" -> LoanPayment(content, actions)
                "LOA-001" -> LoanList(content, actions)
                "LOA-002" -> LoanWizard(content, actions)
                "LOA-003" -> TrancheEditor(content, actions)
                "LOA-004" -> TermsEditor(content, actions)
                "LOA-005" -> RatePeriods(content)
                "LOA-006" -> SchedulePreview(content)
                "LOA-007" -> LoanDetail(content, actions)
                "LOA-008" -> LoanSchedule(content)
                "LOA-009" -> LoanPaymentDetail(content)
                "LOA-010" -> LoanSimulation(content, actions)
                "LOA-011" -> ApplySimulation(content, actions)
                else -> LedgerErrorState(UiErrorCode("LOAN_SCREEN_UNKNOWN"), stringResource(R.string.loan_load_failed), actions.onRetry)
            }
        }
    }
}

@Composable
private fun LiabilityHome(state: LoanFeatureState, actions: LoanActions) {
    LoanListLayout(Modifier.testTag(LedgerTestTags.LIABILITY_HOME)) {
        item { StateBanner(state) }
        item { LedgerText(stringResource(R.string.liability_credit_section), LedgerTextRole.SECTION) }
        when {
            state.creditLoadFailureCode != null -> item {
                LedgerBanner(stringResource(R.string.credit_load_failed), LedgerBannerVariant.DANGER)
            }
            state.creditAccounts.isEmpty() -> item {
                LedgerText(stringResource(R.string.liability_credit_empty), LedgerTextRole.SUPPORTING)
            }
            else -> items(state.creditAccounts, key = { "credit:${it.id}" }) { account ->
                CreditAccountRow(account, actions)
            }
        }
        item { LedgerText(stringResource(R.string.loan_section), LedgerTextRole.SECTION) }
        if (state.snapshot.contracts.isEmpty()) {
            item { LedgerText(stringResource(R.string.loan_empty_body), LedgerTextRole.SUPPORTING) }
            item { LedgerButton(stringResource(R.string.loan_add), { actions.onNavigate("LOA-002", null, null) }, Modifier.fillMaxWidth()) }
        } else {
            item { LoanSummary(state.snapshot.contracts) }
            items(state.snapshot.contracts, key = { it.id.toString() }) { LoanRow(it, actions) }
        }
        item { LedgerText(stringResource(R.string.liability_installment_section), LedgerTextRole.SECTION) }
        item { LedgerText(stringResource(R.string.liability_installment_managed), LedgerTextRole.SUPPORTING) }
    }
}

@Composable
private fun LoanOperation(state: LoanFeatureState, actions: LoanActions) = LoanListLayout(Modifier.testTag(LedgerTestTags.LOAN_OPERATION)) {
    item { LedgerText(stringResource(R.string.loan_operation_explanation), LedgerTextRole.BODY) }
    item { LedgerButton(stringResource(R.string.loan_disbursement), { actions.onNavigate("REC-018", state.selectedContractId, null) }, Modifier.fillMaxWidth()) }
    item { LedgerButton(stringResource(R.string.loan_payment), { actions.onNavigate("REC-019", state.selectedContractId, null) }, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY) }
    item { LedgerButton(stringResource(R.string.loan_manage), { actions.onNavigate("LOA-001", null, null) }, Modifier.fillMaxWidth(), LedgerButtonVariant.TEXT) }
}

@Composable
private fun LoanDisbursement(state: LoanFeatureState, actions: LoanActions) = LoanListLayout(Modifier.testTag(LedgerTestTags.LOAN_DISBURSEMENT)) {
    item { StateBanner(state) }
    item { ContractSelector(state, actions) }
    item { AccountExplanation(state, receiving = true) }
    item { AmountEditor(state.draft.amount, LoanField.AMOUNT, R.string.loan_total_disbursement, actions) }
    item { TrancheAllocation(state, stringResource(R.string.loan_disbursement_allocation), actions) }
    item { LedgerText(stringResource(R.string.loan_atomic_write_notice), LedgerTextRole.SUPPORTING) }
}

@Composable
private fun LoanPayment(state: LoanFeatureState, actions: LoanActions) = LoanListLayout(Modifier.testTag(LedgerTestTags.LOAN_PAYMENT)) {
    item { StateBanner(state) }
    item { ContractSelector(state, actions) }
    item { AccountExplanation(state, receiving = false) }
    item { AmountEditor(state.draft.amount, LoanField.AMOUNT, R.string.loan_total_payment, actions) }
    item { PaymentComponents(state, actions) }
    item { TrancheAllocation(state, stringResource(R.string.loan_payment_allocation), actions) }
    item { LedgerBanner(stringResource(R.string.loan_principal_not_expense), LedgerBannerVariant.INFO) }
}

@Composable
private fun LoanList(state: LoanFeatureState, actions: LoanActions) {
    if (state.snapshot.contracts.isEmpty()) return LoanEmpty(Modifier.testTag(LedgerTestTags.LOAN_LIST), actions)
    LoanListLayout(Modifier.testTag(LedgerTestTags.LOAN_LIST)) {
        item { StateBanner(state) }
        item { LoanSummary(state.snapshot.contracts) }
        items(state.snapshot.contracts, key = { it.id.toString() }) { LoanRow(it, actions) }
        item { LedgerButton(stringResource(R.string.loan_add), { actions.onNavigate("LOA-002", null, null) }, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun LoanWizard(state: LoanFeatureState, actions: LoanActions) {
    if (state.snapshot.loanAccounts.none { it.active }) {
        LoanAccountRequired(Modifier.testTag(LedgerTestTags.LOAN_WIZARD), actions)
        return
    }
    LoanListLayout(Modifier.testTag(LedgerTestTags.LOAN_WIZARD)) {
        item { StateBanner(state) }
        item { LedgerText(stringResource(R.string.loan_wizard_step, state.wizardStep + 1, 6), LedgerTextRole.SECTION) }
        item { BasicFields(state, actions) }
        item { TrancheFields(state, actions) }
        item { TermsFields(state, actions) }
        item { StateBanner(state) }
        item { LedgerButton(stringResource(R.string.loan_generate_schedule), actions.onPreview, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY) }
        if (state.preview.isNotEmpty()) item { PreviewTable(state) }
        item { LedgerButton(stringResource(R.string.loan_review_save), actions.onSave, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun TrancheEditor(state: LoanFeatureState, actions: LoanActions) = LoanListLayout(Modifier.testTag(LedgerTestTags.LOAN_TRANCHE)) {
    item { StateBanner(state) }
    item { LedgerTextField(state.draft.name, { actions.onFieldChanged(LoanField.NAME, it) }, stringResource(R.string.loan_tranche_name), Modifier.fillMaxWidth(), required = true) }
    item { AmountEditor(state.draft.principal, LoanField.PRINCIPAL, R.string.loan_principal, actions) }
    item { LedgerBanner(stringResource(R.string.loan_combination_explanation), LedgerBannerVariant.INFO) }
    item { LedgerButton(stringResource(R.string.loan_save_tranche), actions.onSave, Modifier.fillMaxWidth()) }
}

@Composable
private fun TermsEditor(state: LoanFeatureState, actions: LoanActions) = LoanListLayout(Modifier.testTag(LedgerTestTags.LOAN_TERMS)) {
    item { StateBanner(state) }
    item { TermsFields(state, actions) }
    item { LedgerText(stringResource(R.string.loan_rounding_explanation), LedgerTextRole.SUPPORTING) }
    item { LedgerButton(stringResource(R.string.loan_save_terms), actions.onSave, Modifier.fillMaxWidth()) }
}

@Composable
private fun RatePeriods(state: LoanFeatureState) {
    val tranche = state.tranche
    if (tranche == null || tranche.ratePeriods.isEmpty() || state.presentation == LoanPresentation.EMPTY) {
        return LoanListLayout(Modifier.testTag(LedgerTestTags.LOAN_RATES)) {
            item { LedgerText(stringResource(R.string.loan_rate_empty), LedgerTextRole.TITLE) }
            item { LedgerText(stringResource(R.string.loan_rate_empty_body), LedgerTextRole.BODY) }
        }
    }
    LoanListLayout(Modifier.testTag(LedgerTestTags.LOAN_RATES)) {
        item { StateBanner(state) }
        items(tranche.ratePeriods) { period ->
            LedgerCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                    LedgerText("${period.effectiveFrom} — ${period.effectiveTo ?: stringResource(R.string.loan_open_ended)}", LedgerTextRole.SECTION)
                    LedgerText(stringResource(R.string.loan_rate_value, period.annualRate.annualDecimal.toPlainString()), LedgerTextRole.BODY)
                    period.benchmark?.let { LedgerText(stringResource(R.string.loan_benchmark_value, it, period.margin?.annualDecimal?.toPlainString().orEmpty()), LedgerTextRole.SUPPORTING) }
                }
            }
        }
        item { LedgerBanner(stringResource(R.string.loan_rate_no_overlap), LedgerBannerVariant.INFO) }
    }
}

@Composable
private fun SchedulePreview(state: LoanFeatureState) = LoanListLayout(Modifier.testTag(LedgerTestTags.LOAN_SCHEDULE_PREVIEW)) {
    val contract = state.contract
    item { StateBanner(state) }
    if (state.presentation == LoanPresentation.GENERATING) item { LedgerLoadingState(label = stringResource(R.string.loan_generating)) }
    if (state.presentation != LoanPresentation.GENERATING && state.preview.isNotEmpty()) item { PreviewTable(state) }
    if (state.preview.isEmpty() && contract != null) item { ScheduleTable(contract.tranches.flatMap { it.schedule }) }
    item { LedgerBanner(stringResource(R.string.loan_schedule_assumptions), LedgerBannerVariant.INFO) }
}

@Composable
private fun LoanDetail(state: LoanFeatureState, actions: LoanActions) {
    val contract = state.contract ?: return LoanMissing(actions, Modifier.testTag(LedgerTestTags.LOAN_DETAIL))
    val locale = LocalLocale.current.platformLocale
    LoanListLayout(Modifier.testTag(LedgerTestTags.LOAN_DETAIL)) {
        item { StateBanner(state) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LedgerText(contract.name, LedgerTextRole.TITLE)
                StatusBadge(statusLabel(contract.status), statusVariant(contract.status))
            }
        }
        item { MetricCard(stringResource(R.string.loan_remaining_principal), CreditPolicy.money(contract.remainingPrincipalMinor, contract.currency, locale), Modifier.fillMaxWidth(), MetricCardVariant.EMPHASIZED) }
        item { MetricCard(stringResource(R.string.loan_original_principal), CreditPolicy.money(contract.originalPrincipalMinor, contract.currency, locale), Modifier.fillMaxWidth()) }
        item { LedgerText(stringResource(R.string.loan_next_payment), LedgerTextRole.SECTION) }
        item { NextPayment(contract) }
        items(contract.tranches, key = { it.id.toString() }) { TrancheCard(it, contract, actions) }
        item { LedgerButton(stringResource(R.string.loan_open_schedule), { actions.onNavigate("LOA-008", contract.id, null) }, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY) }
        item { LedgerButton(stringResource(R.string.loan_simulate_prepayment), { actions.onNavigate("LOA-010", contract.id, null) }, Modifier.fillMaxWidth()) }
        item { LedgerText(stringResource(R.string.loan_future_not_current), LedgerTextRole.SUPPORTING) }
    }
}

@Composable
private fun LoanSchedule(state: LoanFeatureState) {
    val contract = state.contract
    if (contract == null || contract.tranches.all { it.schedule.isEmpty() } || state.presentation == LoanPresentation.EMPTY) {
        return LoanListLayout(Modifier.testTag(LedgerTestTags.LOAN_SCHEDULE)) {
            item { LedgerText(stringResource(R.string.loan_schedule_empty), LedgerTextRole.TITLE) }
            item { LedgerText(stringResource(R.string.loan_schedule_empty_body), LedgerTextRole.BODY) }
        }
    }
    LoanListLayout(Modifier.testTag(LedgerTestTags.LOAN_SCHEDULE)) {
        item { LedgerText(stringResource(R.string.loan_schedule_revision, contract.tranches.maxOf { it.scheduleRevisionNumber }), LedgerTextRole.SECTION) }
        item { ScheduleTable(contract.tranches.flatMap { it.schedule }) }
        item { LedgerBanner(stringResource(R.string.loan_plan_actual_explanation), LedgerBannerVariant.INFO) }
    }
}

@Composable
private fun LoanPaymentDetail(state: LoanFeatureState) = LoanListLayout(Modifier.testTag(LedgerTestTags.LOAN_PAYMENT_DETAIL)) {
    item { LedgerText(stringResource(R.string.loan_payment_breakdown), LedgerTextRole.TITLE) }
    item { ComponentMetric(R.string.loan_principal_component, state.draft.principalComponent, state) }
    item { ComponentMetric(R.string.loan_interest_component, state.draft.interestComponent, state) }
    item { ComponentMetric(R.string.loan_fee_component, state.draft.feeComponent, state) }
    item { ComponentMetric(R.string.loan_penalty_component, state.draft.penaltyComponent, state) }
    item { LedgerBanner(stringResource(R.string.loan_account_impact), LedgerBannerVariant.INFO) }
}

@Composable
private fun LoanSimulation(state: LoanFeatureState, actions: LoanActions) = LoanListLayout(Modifier.testTag(LedgerTestTags.LOAN_SIMULATION)) {
    item { StateBanner(state) }
    item { AmountEditor(state.draft.principalComponent, LoanField.PRINCIPAL_COMPONENT, R.string.loan_prepayment_amount, actions) }
    item { StrategySelector(state, actions) }
    item { LedgerButton(stringResource(R.string.loan_calculate), actions.onSimulate, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY) }
    state.simulation?.let { simulation ->
        item { LedgerText(stringResource(R.string.loan_before_after), LedgerTextRole.SECTION) }
        item { LedgerText(stringResource(R.string.loan_before_value, simulation.before.paymentCount, simulation.before.interestMinor), LedgerTextRole.BODY) }
        item { LedgerText(stringResource(R.string.loan_after_value, simulation.afterSummary.paymentCount, simulation.afterSummary.interestMinor), LedgerTextRole.BODY) }
        item { LedgerText(stringResource(R.string.loan_saved_value, simulation.savedInterestAndFeeMinor), LedgerTextRole.SECTION) }
        item { LedgerBanner(stringResource(R.string.loan_simulation_no_write), LedgerBannerVariant.INFO) }
        item { LedgerButton(stringResource(R.string.loan_apply_plan), { actions.onNavigate("LOA-011", state.selectedContractId, null) }, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun ApplySimulation(state: LoanFeatureState, actions: LoanActions) = LoanListLayout(Modifier.testTag(LedgerTestTags.LOAN_SIMULATION_APPLY)) {
    item { StateBanner(state) }
    item { LedgerBanner(stringResource(R.string.loan_apply_creates_versions), LedgerBannerVariant.WARNING) }
    item {
        HighRiskConfirmation(
            stringResource(R.string.loan_apply_plan),
            stringResource(R.string.loan_apply_scope),
            stringResource(R.string.loan_apply_consequence),
            stringResource(R.string.loan_apply_unaffected),
            stringResource(R.string.loan_confirmation_phrase),
            state.draft.confirmPhrase,
            { actions.onFieldChanged(LoanField.CONFIRM_PHRASE, it) },
            actions.onApplySimulation,
            {},
        )
    }
}

@Composable
private fun BasicFields(state: LoanFeatureState, actions: LoanActions) {
    FormSection(stringResource(R.string.loan_basic)) {
        LedgerTextField(state.draft.name, { actions.onFieldChanged(LoanField.NAME, it) }, stringResource(R.string.loan_name), Modifier.fillMaxWidth(), required = true)
        LedgerTextField(state.draft.lender, { actions.onFieldChanged(LoanField.LENDER, it) }, stringResource(R.string.loan_lender), Modifier.fillMaxWidth())
        LedgerTextField(state.draft.startDate, { actions.onFieldChanged(LoanField.START_DATE, it) }, stringResource(R.string.loan_disbursement_date), Modifier.fillMaxWidth(), required = true)
    }
}

@Composable
private fun TrancheFields(state: LoanFeatureState, actions: LoanActions) {
    FormSection(stringResource(R.string.loan_tranche)) {
        AmountEditor(state.draft.principal, LoanField.PRINCIPAL, R.string.loan_principal, actions)
        LedgerText(stringResource(R.string.loan_combination_explanation), LedgerTextRole.SUPPORTING)
    }
}

@Composable
private fun TermsFields(state: LoanFeatureState, actions: LoanActions) {
    FormSection(stringResource(R.string.loan_terms)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            LoanRepaymentMethod.entries.forEach { method ->
                LedgerButton(methodLabel(method), { actions.onRepaymentMethod(method) }, variant = if (method == state.draft.repaymentMethod) LedgerButtonVariant.PRIMARY else LedgerButtonVariant.SECONDARY)
            }
        }
        LedgerTextField(state.draft.paymentCount, { actions.onFieldChanged(LoanField.PAYMENT_COUNT, it) }, stringResource(R.string.loan_payment_count), Modifier.fillMaxWidth(), required = true, keyboardType = KeyboardType.Number)
        LedgerTextField(state.draft.firstPaymentDate, { actions.onFieldChanged(LoanField.FIRST_PAYMENT_DATE, it) }, stringResource(R.string.loan_first_payment), Modifier.fillMaxWidth(), required = true)
        LedgerTextField(state.draft.annualRate, { actions.onFieldChanged(LoanField.ANNUAL_RATE, it) }, stringResource(R.string.loan_annual_rate), Modifier.fillMaxWidth(), required = true, keyboardType = KeyboardType.Decimal)
        LedgerTextField(state.draft.feePerPayment, { actions.onFieldChanged(LoanField.FEE_PER_PAYMENT, it) }, stringResource(R.string.loan_fee_per_payment), Modifier.fillMaxWidth(), keyboardType = KeyboardType.Decimal)
    }
}

@Composable
private fun ContractSelector(state: LoanFeatureState, actions: LoanActions) {
    FormSection(stringResource(R.string.loan_contract)) {
        state.snapshot.contracts.forEach { contract ->
            LedgerButton(contract.name, { actions.onSelectContract(contract.id) }, Modifier.fillMaxWidth(), if (contract.id == state.selectedContractId) LedgerButtonVariant.PRIMARY else LedgerButtonVariant.SECONDARY)
        }
    }
}

@Composable
private fun AccountExplanation(state: LoanFeatureState, receiving: Boolean) {
    val accounts = if (receiving) state.snapshot.paymentAccounts else state.snapshot.paymentAccounts
    FormSection(stringResource(if (receiving) R.string.loan_receiving_account else R.string.loan_payment_account)) {
        accounts.filter { it.active }.take(3).forEach { LedgerText("${it.name} · ${it.currency.value}", LedgerTextRole.BODY) }
    }
}

@Composable
private fun PaymentComponents(state: LoanFeatureState, actions: LoanActions) {
    FormSection(stringResource(R.string.loan_payment_components)) {
        AmountEditor(state.draft.principalComponent, LoanField.PRINCIPAL_COMPONENT, R.string.loan_principal_component, actions)
        AmountEditor(state.draft.interestComponent, LoanField.INTEREST_COMPONENT, R.string.loan_interest_component, actions)
        AmountEditor(state.draft.feeComponent, LoanField.FEE_COMPONENT, R.string.loan_fee_component, actions)
        AmountEditor(state.draft.penaltyComponent, LoanField.PENALTY_COMPONENT, R.string.loan_penalty_component, actions)
    }
}

@Composable
private fun TrancheAllocation(state: LoanFeatureState, title: String, actions: LoanActions) {
    FormSection(title) {
        state.contract?.tranches?.forEach { tranche ->
            LedgerButton(
                "${tranche.name} · ${tranche.remainingPrincipalMinor}",
                { actions.onSelectTranche(tranche.id) },
                Modifier.fillMaxWidth(),
                if (tranche.id == state.selectedTrancheId) LedgerButtonVariant.PRIMARY else LedgerButtonVariant.SECONDARY,
            )
        }
    }
}

@Composable
private fun StrategySelector(state: LoanFeatureState, actions: LoanActions) {
    FormSection(stringResource(R.string.loan_strategy)) {
        PrepaymentRecalculationStrategy.entries.forEach { strategy ->
            LedgerButton(strategyLabel(strategy), { actions.onStrategy(strategy) }, Modifier.fillMaxWidth(), if (state.draft.strategy == strategy) LedgerButtonVariant.PRIMARY else LedgerButtonVariant.SECONDARY)
        }
    }
}

@Composable
private fun LoanSummary(contracts: List<LoanContractView>) {
    val first = contracts.first()
    val total = contracts.fold(0L) { sum, contract -> Math.addExact(sum, contract.remainingPrincipalMinor) }
    MetricCard(stringResource(R.string.loan_total_remaining), CreditPolicy.money(total, first.currency, LocalLocale.current.platformLocale), Modifier.fillMaxWidth(), MetricCardVariant.EMPHASIZED)
}

@Composable
private fun CreditAccountRow(account: CreditAccountView, actions: LoanActions) {
    LedgerCard(Modifier.fillMaxWidth(), onClick = { actions.onOpenCreditAccount(account.id) }) {
        Column(Modifier.padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            LedgerText(account.name, LedgerTextRole.SECTION)
            AmountText(CreditPolicy.money(account.debtMinor, account.currency, LocalLocale.current.platformLocale), AmountSize.MEDIUM)
            LedgerText(stringResource(R.string.credit_debt), LedgerTextRole.SUPPORTING)
        }
    }
}

@Composable
private fun LoanRow(contract: LoanContractView, actions: LoanActions) {
    LedgerCard(Modifier.fillMaxWidth(), onClick = { actions.onNavigate("LOA-007", contract.id, null) }) {
        Column(Modifier.padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LedgerText(contract.name, LedgerTextRole.SECTION)
                StatusBadge(statusLabel(contract.status), statusVariant(contract.status))
            }
            AmountText(CreditPolicy.money(contract.remainingPrincipalMinor, contract.currency, LocalLocale.current.platformLocale), AmountSize.MEDIUM)
            LedgerText(stringResource(R.string.loan_tranche_count, contract.tranches.size), LedgerTextRole.SUPPORTING)
        }
    }
}

@Composable
private fun TrancheCard(tranche: LoanTrancheView, contract: LoanContractView, actions: LoanActions) {
    LedgerCard(Modifier.fillMaxWidth(), onClick = { actions.onNavigate("LOA-003", contract.id, tranche.id) }) {
        Column(Modifier.padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            LedgerText(tranche.name, LedgerTextRole.SECTION)
            AmountText(CreditPolicy.money(tranche.remainingPrincipalMinor, contract.currency, LocalLocale.current.platformLocale), AmountSize.MEDIUM)
            LedgerText(stringResource(R.string.loan_terms_version, tranche.termsRevisionNumber, tranche.scheduleRevisionNumber), LedgerTextRole.SUPPORTING)
        }
    }
}

@Composable
private fun NextPayment(contract: LoanContractView) {
    val next = contract.tranches.flatMap { it.schedule }.minByOrNull { it.plannedDate }
    if (next == null) {
        LedgerText(stringResource(R.string.loan_no_future_payment), LedgerTextRole.SUPPORTING)
    } else {
        LedgerText(stringResource(R.string.loan_next_payment_value, next.plannedDate.toString(), Math.addExact(Math.addExact(next.principalMinor, next.interestMinor), next.feeMinor)), LedgerTextRole.BODY)
    }
}

@Composable
private fun PreviewTable(state: LoanFeatureState) = ScheduleTable(state.preview.flatMap { revision -> revision.items.map { item -> LoanScheduleItemView(item.installmentNumber, item.plannedDate, item.principalMinor, item.interestMinor, item.feeMinor, item.remainingPrincipalMinor, 0, 0, 0, 0) } })

@Composable
private fun ScheduleTable(rows: List<LoanScheduleItemView>) {
    AccessibleDataTable(
        AccessibleTableUiModel(
            caption = stringResource(R.string.loan_schedule_table),
            columnHeaders = listOf(stringResource(R.string.loan_term), stringResource(R.string.loan_date), stringResource(R.string.loan_principal), stringResource(R.string.loan_interest), stringResource(R.string.loan_remaining_principal)),
            rows = rows.take(60).map { listOf(it.installmentNumber.toString(), it.plannedDate.toString(), it.principalMinor.toString(), it.interestMinor.toString(), it.remainingPrincipalMinor.toString()) },
        ),
    )
}

@Composable
private fun ComponentMetric(label: Int, raw: String, state: LoanFeatureState) {
    val contract = state.contract ?: return
    MetricCard(stringResource(label), CreditPolicy.money(raw.toLongOrNull() ?: 0L, contract.currency, LocalLocale.current.platformLocale, AmountSemantic.NEUTRAL), Modifier.fillMaxWidth())
}

@Composable
private fun AmountEditor(value: String, field: LoanField, label: Int, actions: LoanActions) {
    LedgerTextField(value, { actions.onFieldChanged(field, it) }, stringResource(label), Modifier.fillMaxWidth(), required = field in setOf(LoanField.AMOUNT, LoanField.PRINCIPAL, LoanField.PRINCIPAL_COMPONENT), keyboardType = KeyboardType.Decimal)
}

@Composable
private fun StateBanner(state: LoanFeatureState) {
    val pair = when (state.presentation) {
        LoanPresentation.ALLOCATION_ERROR -> R.string.loan_allocation_error to LedgerBannerVariant.DANGER
        LoanPresentation.PRINCIPAL_EXCEEDED -> R.string.loan_principal_exceeded to LedgerBannerVariant.DANGER
        LoanPresentation.SUM_MISMATCH -> R.string.loan_sum_mismatch to LedgerBannerVariant.DANGER
        LoanPresentation.INVALID, LoanPresentation.CALCULATION_ERROR -> R.string.loan_invalid to LedgerBannerVariant.DANGER
        LoanPresentation.OVERLAP_ERROR -> R.string.loan_rate_overlap to LedgerBannerVariant.DANGER
        LoanPresentation.SAVING, LoanPresentation.GENERATING, LoanPresentation.GENERATING_SCHEDULE, LoanPresentation.CALCULATING -> R.string.loan_processing to LedgerBannerVariant.INFO
        LoanPresentation.OVERDUE, LoanPresentation.OVERDUE_PLAN_DIFFERENCE -> R.string.loan_overdue_difference to LedgerBannerVariant.WARNING
        LoanPresentation.CONFLICT -> R.string.loan_revision_conflict to LedgerBannerVariant.DANGER
        LoanPresentation.CLOSED -> R.string.loan_closed_explanation to LedgerBannerVariant.NEUTRAL
        LoanPresentation.MULTI_TRANCHE -> R.string.loan_combination_explanation to LedgerBannerVariant.INFO
        else -> return
    }
    LedgerBanner(stringResource(pair.first), pair.second)
}

@Composable
private fun LoanEmpty(modifier: Modifier, actions: LoanActions) = Box(modifier.fillMaxSize()) {
    LedgerEmptyState(stringResource(R.string.loan_empty), stringResource(R.string.loan_empty_body), stringResource(R.string.loan_add), { actions.onNavigate("LOA-002", null, null) })
}

@Composable
private fun LoanAccountRequired(modifier: Modifier, actions: LoanActions) = Box(modifier.fillMaxSize()) {
    LedgerEmptyState(
        stringResource(R.string.loan_account_required_title),
        stringResource(R.string.loan_account_required_body),
        stringResource(R.string.loan_create_account),
        actions.onCreateLoanAccount,
    )
}

@Composable
private fun LoanMissing(actions: LoanActions, modifier: Modifier) = Box(modifier.fillMaxSize()) {
    LedgerErrorState(UiErrorCode("LOAN_NOT_FOUND"), stringResource(R.string.loan_load_failed), actions.onRetry)
}

@Composable
private fun LoanListLayout(modifier: Modifier, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = LedgerTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
        content = content,
    )
}

@Composable
private fun statusLabel(status: LoanStatus): String = when (status) {
    LoanStatus.ACTIVE -> stringResource(R.string.loan_status_active)
    LoanStatus.PAID_OFF -> stringResource(R.string.loan_status_paid)
    LoanStatus.DEFAULTED -> stringResource(R.string.loan_status_defaulted)
    LoanStatus.ARCHIVED -> stringResource(R.string.loan_status_archived)
}

private fun statusVariant(status: LoanStatus): LedgerStatusVariant = when (status) {
    LoanStatus.ACTIVE -> LedgerStatusVariant.INFO
    LoanStatus.PAID_OFF -> LedgerStatusVariant.POSITIVE
    LoanStatus.DEFAULTED -> LedgerStatusVariant.DANGER
    LoanStatus.ARCHIVED -> LedgerStatusVariant.NEUTRAL
}

@Composable
private fun methodLabel(method: LoanRepaymentMethod): String = stringResource(
    when (method) {
        LoanRepaymentMethod.EQUAL_PAYMENT -> R.string.loan_method_equal_payment
        LoanRepaymentMethod.EQUAL_PRINCIPAL -> R.string.loan_method_equal_principal
        LoanRepaymentMethod.INTEREST_ONLY_THEN_PRINCIPAL -> R.string.loan_method_interest_only
        LoanRepaymentMethod.BULLET -> R.string.loan_method_bullet
        LoanRepaymentMethod.CUSTOM -> R.string.loan_method_custom
    },
)

@Composable
private fun strategyLabel(strategy: PrepaymentRecalculationStrategy): String = stringResource(if (strategy == PrepaymentRecalculationStrategy.SHORTEN_TERM) R.string.loan_strategy_shorten else R.string.loan_strategy_reduce)

private fun Map<String, String>.loanId(name: String): StableId? = get(name)?.let { StableId.parse(it).getOrNull() }
