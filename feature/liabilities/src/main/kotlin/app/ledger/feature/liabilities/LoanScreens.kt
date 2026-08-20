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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import app.ledger.core.designsystem.LedgerDatePickerFlow
import app.ledger.core.designsystem.LedgerDateTimePickerFlow
import app.ledger.core.designsystem.LedgerProgressIndicator
import app.ledger.core.designsystem.LedgerStatusVariant
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextField
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.MetricCard
import app.ledger.core.designsystem.MetricCardVariant
import app.ledger.core.designsystem.StatusBadge
import app.ledger.core.designsystem.SelectorField
import app.ledger.core.designsystem.LedgerTabRow
import app.ledger.core.designsystem.UiErrorCode
import app.ledger.core.money.AmountSemantic
import app.ledger.core.money.LocaleNumberFormatter
import app.ledger.finance.application.CreditAccountView
import app.ledger.finance.application.LoanContractView
import app.ledger.finance.application.LoanScheduleItemView
import app.ledger.finance.application.LoanTrancheView
import app.ledger.finance.domain.LoanRepaymentMethod
import app.ledger.finance.domain.LoanScheduleSummary
import app.ledger.finance.domain.LoanPrepaymentPolicy
import app.ledger.finance.domain.LoanPaymentComponent
import app.ledger.finance.domain.LoanRateType
import app.ledger.finance.domain.LoanStatus
import app.ledger.finance.domain.PaymentFrequency
import app.ledger.finance.domain.PrepaymentRecalculationStrategy
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

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
                "LOA-005" -> RatePeriods(content, actions)
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
    val locale = LocalLocale.current.platformLocale
    val base = state.snapshot.baseCurrency
    val creditTotal = state.creditAccounts.filter { it.currency == base }.sumOf { it.debtMinor }
    val loanTotal = state.snapshot.contracts.filter { it.currency == base }.sumOf { it.remainingPrincipalMinor }
    val installmentTotal = state.installmentPlans.filter { it.currency == base }.sumOf { it.progress.unpostedCommittedPrincipalMinor }
    val nearest = buildList {
        state.creditAccounts.flatMap { it.statements }.filter { it.remainingAmountMinor > 0L }.forEach { add(it.dueDate) }
        state.snapshot.contracts.flatMap { it.tranches }.flatMap { it.schedule }
            .filter { row ->
                row.actualPrincipalMinor + row.actualInterestMinor + row.actualFeeMinor + row.actualPenaltyMinor <
                    row.principalMinor + row.interestMinor + row.feeMinor
            }
            .forEach { add(it.plannedDate) }
        state.installmentPlans.mapNotNull { it.progress.nextStatementDate }.forEach(::add)
    }.minOrNull()
    LoanListLayout(Modifier.testTag(LedgerTestTags.LIABILITY_HOME)) {
        item { StateBanner(state) }
        item { MetricCard(stringResource(R.string.liability_credit_total), CreditPolicy.money(creditTotal, base, locale), Modifier.fillMaxWidth()) }
        item { MetricCard(stringResource(R.string.liability_loan_total), CreditPolicy.money(loanTotal, base, locale), Modifier.fillMaxWidth()) }
        item { MetricCard(stringResource(R.string.liability_installment_total), CreditPolicy.money(installmentTotal, base, locale), Modifier.fillMaxWidth()) }
        item { LedgerText(nearest?.let { stringResource(R.string.liability_nearest_due, it.localized(locale)) } ?: stringResource(R.string.liability_no_upcoming_due), LedgerTextRole.BODY) }
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
        when {
            state.installmentLoadFailureCode != null -> item { LedgerBanner(stringResource(R.string.installment_load_failed), LedgerBannerVariant.DANGER) }
            state.installmentPlans.isEmpty() -> item { LedgerText(stringResource(R.string.liability_installment_empty), LedgerTextRole.SUPPORTING) }
            else -> items(state.installmentPlans, key = { "installment:${it.id}" }) { plan ->
                LedgerCard(Modifier.fillMaxWidth(), onClick = { actions.onNavigate("INS-003", plan.id, null) }) {
                    Column(Modifier.padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                        LedgerText(plan.creditAccountName, LedgerTextRole.SECTION)
                        AmountText(CreditPolicy.money(plan.progress.unpostedCommittedPrincipalMinor, plan.currency, locale), AmountSize.MEDIUM)
                        LedgerText(plan.progress.nextStatementDate?.let { stringResource(R.string.liability_next_installment, it.localized(locale)) } ?: stringResource(R.string.liability_no_upcoming_due), LedgerTextRole.SUPPORTING)
                    }
                }
            }
        }
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
private fun LoanDisbursement(state: LoanFeatureState, actions: LoanActions) {
    var showDateTimePicker by remember { mutableStateOf(false) }
    LoanListLayout(Modifier.testTag(LedgerTestTags.LOAN_DISBURSEMENT)) {
        item { StateBanner(state) }
        item { ContractSelector(state, actions) }
        item { AccountSelector(state, receiving = true, actions = actions) }
        item { OperationDateTimeField(state) { showDateTimePicker = true } }
        item { AmountEditor(state.draft.amount, LoanField.AMOUNT, R.string.loan_total_disbursement, actions) }
        item { TrancheAllocation(state, stringResource(R.string.loan_disbursement_allocation), actions) }
        item { LedgerText(stringResource(R.string.loan_atomic_write_notice), LedgerTextRole.SUPPORTING) }
    }
    if (showDateTimePicker) LoanOperationDateTimePicker(state, actions) { showDateTimePicker = false }
}

@Composable
private fun LoanPayment(state: LoanFeatureState, actions: LoanActions) {
    var showDateTimePicker by remember { mutableStateOf(false) }
    LoanListLayout(Modifier.testTag(LedgerTestTags.LOAN_PAYMENT)) {
        item { StateBanner(state) }
        item { ContractSelector(state, actions) }
        item { AccountSelector(state, receiving = false, actions = actions) }
        item { OperationDateTimeField(state) { showDateTimePicker = true } }
        item { AmountEditor(state.draft.amount, LoanField.AMOUNT, R.string.loan_total_payment, actions) }
        item { PaymentComponents(state, actions) }
        item { TrancheAllocation(state, stringResource(R.string.loan_payment_allocation), actions) }
        item { ScheduleInstallmentSelector(state, actions) }
        item { LedgerBanner(stringResource(R.string.loan_principal_not_expense), LedgerBannerVariant.INFO) }
    }
    if (showDateTimePicker) LoanOperationDateTimePicker(state, actions) { showDateTimePicker = false }
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
private fun LoanWizard(state: LoanFeatureState, actions: LoanActions) = LoanListLayout(Modifier.testTag(LedgerTestTags.LOAN_WIZARD)) {
    item { StateBanner(state) }
    item { LedgerText(stringResource(R.string.loan_wizard_step, state.wizardStep + 1, 6), LedgerTextRole.SECTION) }
    item { LedgerProgressIndicator((state.wizardStep + 1) / 6f, accessibleText = stringResource(R.string.loan_wizard_progress)) }
    when (state.wizardStep) {
        0 -> item { BasicFields(state, actions) }
        1 -> {
            item {
                FormSection(stringResource(R.string.loan_tranches_step)) {
                    state.wizardTranches.forEachIndexed { index, tranche ->
                        LedgerButton(
                            tranche.name.ifBlank { stringResource(R.string.loan_unnamed_tranche, index + 1) },
                            { actions.onSelectWizardTranche(index) },
                            Modifier.fillMaxWidth(),
                            if (index == state.selectedWizardTrancheIndex) LedgerButtonVariant.PRIMARY else LedgerButtonVariant.SECONDARY,
                        )
                    }
                    LedgerButton(stringResource(R.string.loan_add_tranche), actions.onAddTranche, Modifier.fillMaxWidth(), LedgerButtonVariant.TEXT)
                    LedgerTextField(state.draft.trancheName, { actions.onFieldChanged(LoanField.TRANCHE_NAME, it) }, stringResource(R.string.loan_tranche_name), Modifier.fillMaxWidth(), required = true)
                    AmountEditor(state.draft.principal, LoanField.PRINCIPAL, R.string.loan_principal, actions)
                }
            }
        }
        2 -> item { TermsFields(state, actions) }
        3 -> item { RatePeriodEditor(state, actions) }
        4 -> {
            item { LedgerButton(stringResource(R.string.loan_generate_schedule), actions.onPreview, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY) }
            if (state.preview.isNotEmpty()) {
                item { ScheduleSummary(state.preview.flatMap { it.items }.map { LoanScheduleItemView(it.installmentNumber, it.plannedDate, it.principalMinor, it.interestMinor, it.feeMinor, it.remainingPrincipalMinor, 0, 0, 0, 0) }, state.snapshot.loanAccounts.firstOrNull()?.currency ?: state.snapshot.baseCurrency) }
                item { PreviewTable(state) }
            }
        }
        else -> {
            item { LedgerText(stringResource(R.string.loan_review_title), LedgerTextRole.TITLE) }
            item { LedgerText(stringResource(R.string.loan_review_contract, state.draft.name, state.wizardTranches.size), LedgerTextRole.BODY) }
            item { PreviewTable(state) }
            item { LedgerBanner(stringResource(R.string.loan_review_save), LedgerBannerVariant.INFO) }
        }
    }
    if (state.wizardStep > 0) item { LedgerButton(stringResource(R.string.loan_wizard_back), actions.onWizardBack, Modifier.fillMaxWidth(), LedgerButtonVariant.TEXT) }
    if (state.wizardStep < 5) item { LedgerButton(stringResource(R.string.loan_wizard_next), actions.onWizardNext, Modifier.fillMaxWidth(), enabled = state.presentation !in setOf(LoanPresentation.INVALID, LoanPresentation.GENERATING_SCHEDULE)) }
}

@Composable
private fun TrancheEditor(state: LoanFeatureState, actions: LoanActions) = LoanListLayout(Modifier.testTag(LedgerTestTags.LOAN_TRANCHE)) {
    item { StateBanner(state) }
    if (state.creatingTranche) item { LedgerBanner(stringResource(R.string.loan_create_tranche_detail), LedgerBannerVariant.NEUTRAL) }
    state.tranche?.let { tranche -> item { LedgerText(stringResource(R.string.loan_current_tranche_summary, tranche.name, tranche.termsRevisionNumber, tranche.scheduleRevisionNumber), LedgerTextRole.SUPPORTING) } }
    item { LedgerTextField(state.draft.trancheName, { actions.onFieldChanged(LoanField.TRANCHE_NAME, it) }, stringResource(R.string.loan_tranche_name), Modifier.fillMaxWidth(), required = true) }
    item { AmountEditor(state.draft.principal, LoanField.PRINCIPAL, R.string.loan_principal, actions) }
    item { LedgerText(stringResource(R.string.loan_tranche_terms_summary, methodLabel(state.draft.repaymentMethod), state.draft.paymentCount), LedgerTextRole.BODY) }
    item { LedgerBanner(stringResource(R.string.loan_combination_explanation), LedgerBannerVariant.INFO) }
}

@Composable
private fun TermsEditor(state: LoanFeatureState, actions: LoanActions) = LoanListLayout(Modifier.testTag(LedgerTestTags.LOAN_TERMS)) {
    item { StateBanner(state) }
    item { TermsFields(state, actions) }
    item { LedgerText(stringResource(R.string.loan_rounding_explanation), LedgerTextRole.SUPPORTING) }
}

@Composable
private fun RatePeriods(state: LoanFeatureState, actions: LoanActions) {
    val tranche = state.tranche
    if (tranche == null) {
        return LoanListLayout(Modifier.testTag(LedgerTestTags.LOAN_RATES)) {
            item { LedgerText(stringResource(R.string.loan_rate_empty), LedgerTextRole.TITLE) }
            item { LedgerText(stringResource(R.string.loan_rate_empty_body), LedgerTextRole.BODY) }
        }
    }
    LoanListLayout(Modifier.testTag(LedgerTestTags.LOAN_RATES)) {
        item { StateBanner(state) }
        item { RatePeriodEditor(state, actions) }
        items(state.ratePeriods.size) { index ->
            val period = state.ratePeriods[index]
            LedgerCard(Modifier.fillMaxWidth(), onClick = { actions.onEditRatePeriod(index) }) {
                Column(Modifier.padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                    LedgerText("${period.effectiveFrom.localized(LocalLocale.current.platformLocale)} — ${period.effectiveTo?.localized(LocalLocale.current.platformLocale) ?: stringResource(R.string.loan_open_ended)}", LedgerTextRole.SECTION)
                    LedgerText(stringResource(R.string.loan_rate_value, LocaleNumberFormatter.percentage(period.annualRate.annualDecimal, LocalLocale.current.platformLocale)), LedgerTextRole.BODY)
                    period.benchmark?.let { benchmark -> LedgerText(stringResource(R.string.loan_benchmark_value, benchmark, period.margin?.annualDecimal?.let { LocaleNumberFormatter.percentage(it, LocalLocale.current.platformLocale) }.orEmpty()), LedgerTextRole.SUPPORTING) }
                }
            }
        }
        if (state.presentation == LoanPresentation.OVERLAP_ERROR) item { LedgerBanner(stringResource(R.string.loan_rate_conflict_detail), LedgerBannerVariant.DANGER) }
        item { LedgerButton(stringResource(R.string.loan_regenerate_future_schedule), actions.onPreview, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY) }
        item { LedgerBanner(stringResource(R.string.loan_rate_no_overlap), LedgerBannerVariant.INFO) }
    }
}

@Composable
private fun SchedulePreview(state: LoanFeatureState) = LoanListLayout(Modifier.testTag(LedgerTestTags.LOAN_SCHEDULE_PREVIEW)) {
    val contract = state.contract
    item { StateBanner(state) }
    if (state.presentation == LoanPresentation.GENERATING) item { LedgerLoadingState(label = stringResource(R.string.loan_generating)) }
    if (state.presentation != LoanPresentation.GENERATING && state.preview.isNotEmpty()) item { PreviewTable(state) }
    if (state.preview.isNotEmpty()) item { ScheduleSummary(state.preview.flatMap { revision -> revision.items.map { LoanScheduleItemView(it.installmentNumber, it.plannedDate, it.principalMinor, it.interestMinor, it.feeMinor, it.remainingPrincipalMinor, 0, 0, 0, 0) } }, contract?.currency ?: state.snapshot.baseCurrency) }
    if (state.preview.isEmpty() && contract != null) {
        item { ScheduleSummary(contract.tranches.flatMap { it.schedule }, contract.currency) }
        item { ScheduleTable(contract.tranches.flatMap { it.schedule }, contract.currency) }
    }
    if (state.presentation == LoanPresentation.CALCULATION_ERROR) item { LedgerBanner(stringResource(R.string.loan_calculation_error_detail, state.validationFields.firstOrNull().orEmpty()), LedgerBannerVariant.DANGER) }
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
        item { MetricCard(stringResource(R.string.loan_paid_principal), CreditPolicy.money(contract.tranches.sumOf { it.paidPrincipalMinor }, contract.currency, locale), Modifier.fillMaxWidth()) }
        item { MetricCard(stringResource(R.string.loan_paid_interest), CreditPolicy.money(contract.tranches.sumOf { it.paidInterestMinor }, contract.currency, locale), Modifier.fillMaxWidth()) }
        item { MetricCard(stringResource(R.string.loan_paid_fees), CreditPolicy.money(contract.tranches.sumOf { Math.addExact(it.paidFeeMinor, it.paidPenaltyMinor) }, contract.currency, locale), Modifier.fillMaxWidth()) }
        item {
            LedgerProgressIndicator(
                if (contract.originalPrincipalMinor > 0L) (contract.originalPrincipalMinor - contract.remainingPrincipalMinor).toFloat() / contract.originalPrincipalMinor.toFloat() else 1f,
                Modifier.fillMaxWidth(), stringResource(R.string.loan_repayment_progress),
            )
        }
        item {
            val planned = contract.tranches.flatMap { it.schedule }.sumOf { Math.addExact(Math.addExact(it.principalMinor, it.interestMinor), it.feeMinor) }
            val actual = contract.tranches.flatMap { it.schedule }.sumOf { Math.addExact(Math.addExact(it.actualPrincipalMinor, it.actualInterestMinor), Math.addExact(it.actualFeeMinor, it.actualPenaltyMinor)) }
            MetricCard(stringResource(R.string.loan_plan_actual_variance), CreditPolicy.money(actual - planned, contract.currency, locale), Modifier.fillMaxWidth())
        }
        item {
            LedgerTabRow(
                0,
                listOf(stringResource(R.string.loan_tab_overview), stringResource(R.string.loan_tab_schedule), stringResource(R.string.loan_tab_transactions), stringResource(R.string.loan_tab_rates), stringResource(R.string.loan_tab_simulation)),
                { index -> when (index) {
                    1 -> actions.onNavigate("LOA-008", contract.id, null)
                    2 -> actions.onNavigate("JRN-001", null, null)
                    3 -> contract.tranches.firstOrNull()?.let { actions.onNavigate("LOA-005", contract.id, it.id) }
                    4 -> actions.onNavigate("LOA-010", contract.id, null)
                } },
            )
        }
        item { LedgerText(stringResource(R.string.loan_next_payment), LedgerTextRole.SECTION) }
        item { NextPayment(contract) }
        item { LedgerText(stringResource(R.string.loan_recent_payments), LedgerTextRole.SECTION) }
        val recent = contract.tranches.flatMap { tranche -> tranche.schedule.map { tranche to it } }
            .filter { (_, row) -> row.actualPrincipalMinor + row.actualInterestMinor + row.actualFeeMinor + row.actualPenaltyMinor > 0L }
            .sortedByDescending { it.second.plannedDate }.take(3)
        if (recent.isEmpty()) item { LedgerText(stringResource(R.string.loan_no_recent_payments), LedgerTextRole.SUPPORTING) }
        items(recent) { (tranche, row) ->
            LedgerCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) { LedgerText(tranche.name, LedgerTextRole.BODY); LedgerText(row.plannedDate.localized(locale), LedgerTextRole.SUPPORTING) }
                    AmountText(CreditPolicy.money(row.actualPrincipalMinor + row.actualInterestMinor + row.actualFeeMinor + row.actualPenaltyMinor, contract.currency, locale), AmountSize.LIST)
                }
            }
        }
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
    var revisionIndex by remember { mutableIntStateOf(0) }
    var actualMode by remember { mutableStateOf(false) }
    val revisions = contract.tranches.flatMap { tranche -> tranche.scheduleRevisions.map { tranche.name to it } }
    val selected = revisions.getOrNull(revisionIndex)
    val rows = selected?.second?.items ?: contract.tranches.flatMap { it.schedule }
    LoanListLayout(Modifier.testTag(LedgerTestTags.LOAN_SCHEDULE)) {
        item { SelectorField(stringResource(R.string.loan_schedule_revision_selector), selected?.let { stringResource(R.string.loan_revision_with_tranche, it.first, it.second.revisionNumber) } ?: stringResource(R.string.loan_schedule_revision, contract.tranches.maxOf { it.scheduleRevisionNumber }), { if (revisions.isNotEmpty()) revisionIndex = (revisionIndex + 1).mod(revisions.size) }) }
        item { LedgerTabRow(if (actualMode) 1 else 0, listOf(stringResource(R.string.loan_planned), stringResource(R.string.loan_actual)), { actualMode = it == 1 }) }
        item { ScheduleTable(rows, contract.currency, actualOnly = actualMode) }
        item { LedgerBanner(stringResource(R.string.loan_plan_actual_explanation), LedgerBannerVariant.INFO) }
    }
}

@Composable
private fun LoanPaymentDetail(state: LoanFeatureState) = LoanListLayout(Modifier.testTag(LedgerTestTags.LOAN_PAYMENT_DETAIL)) {
    val detail = state.paymentDetail
    if (detail == null) {
        item { LedgerText(stringResource(R.string.loan_payment_not_found), LedgerTextRole.BODY) }
        return@LoanListLayout
    }
    val locale = LocalLocale.current.platformLocale
    item { LedgerText(stringResource(R.string.loan_payment_breakdown), LedgerTextRole.TITLE) }
    item { LedgerText(stringResource(R.string.loan_payment_context, detail.contractName, detail.localDate.localized(locale), detail.paymentAccountName ?: stringResource(R.string.loan_unknown_account)), LedgerTextRole.BODY) }
    item { MetricCard(stringResource(R.string.loan_principal_component), CreditPolicy.money(detail.principalMinor, detail.currency, locale), Modifier.fillMaxWidth()) }
    item { MetricCard(stringResource(R.string.loan_interest_component), CreditPolicy.money(detail.interestMinor, detail.currency, locale), Modifier.fillMaxWidth()) }
    item { MetricCard(stringResource(R.string.loan_fee_component), CreditPolicy.money(detail.feeMinor, detail.currency, locale), Modifier.fillMaxWidth()) }
    item { MetricCard(stringResource(R.string.loan_penalty_component), CreditPolicy.money(detail.penaltyMinor, detail.currency, locale), Modifier.fillMaxWidth()) }
    item { LedgerText(stringResource(R.string.loan_schedule_allocations), LedgerTextRole.SECTION) }
    if (detail.allocations.isEmpty()) item { LedgerText(stringResource(R.string.loan_no_schedule_allocations), LedgerTextRole.SUPPORTING) }
    items(detail.allocations) { allocation ->
        LedgerCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm), horizontalArrangement = Arrangement.SpaceBetween) {
                LedgerText(
                    stringResource(
                        R.string.loan_allocation_natural,
                        allocation.trancheName,
                        allocation.installmentNumber?.let { LocaleNumberFormatter.integer(it, locale) } ?: stringResource(R.string.loan_unplanned),
                        loanComponentLabel(allocation.component),
                    ),
                    LedgerTextRole.BODY,
                )
                AmountText(CreditPolicy.money(allocation.amountMinor, detail.currency, locale), AmountSize.LIST)
            }
        }
    }
    item { LedgerBanner(stringResource(R.string.loan_account_impact_detail, detail.paymentAccountName ?: stringResource(R.string.loan_unknown_account), CreditPolicy.money(detail.principalMinor, detail.currency, locale).formatted, CreditPolicy.money(detail.interestMinor + detail.feeMinor + detail.penaltyMinor, detail.currency, locale).formatted), LedgerBannerVariant.INFO) }
}

@Composable
private fun LoanSimulation(state: LoanFeatureState, actions: LoanActions) = LoanListLayout(Modifier.testTag(LedgerTestTags.LOAN_SIMULATION)) {
    item { StateBanner(state) }
    item { AmountEditor(state.draft.principalComponent, LoanField.PRINCIPAL_COMPONENT, R.string.loan_prepayment_amount, actions) }
    item { StrategySelector(state, actions) }
    item { LedgerButton(stringResource(R.string.loan_calculate), actions.onSimulate, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY) }
    state.simulation?.let { simulation ->
        val contract = state.contract
        val currency = contract?.currency ?: state.snapshot.baseCurrency
        val locale = LocalLocale.current.platformLocale
        item { LedgerText(stringResource(R.string.loan_before_after), LedgerTextRole.SECTION) }
        item { SimulationSummary(stringResource(R.string.loan_before), simulation.before, currency) }
        item { SimulationSummary(stringResource(R.string.loan_after), simulation.afterSummary, currency) }
        item { MetricCard(stringResource(R.string.loan_saved_cost), CreditPolicy.money(simulation.savedInterestAndFeeMinor, currency, locale), Modifier.fillMaxWidth(), MetricCardVariant.EMPHASIZED) }
        contract?.let {
            item { LedgerText(stringResource(R.string.loan_before), LedgerTextRole.SECTION) }
            item { ScheduleTable(it.tranches.flatMap { tranche -> tranche.schedule }, currency) }
        }
        item { LedgerText(stringResource(R.string.loan_after), LedgerTextRole.SECTION) }
        item { ScheduleTable(simulation.after.items.map { LoanScheduleItemView(it.installmentNumber, it.plannedDate, it.principalMinor, it.interestMinor, it.feeMinor, it.remainingPrincipalMinor, 0, 0, 0, 0) }, currency) }
        item { LedgerBanner(stringResource(R.string.loan_simulation_no_write), LedgerBannerVariant.NEUTRAL) }
        item { LedgerButton(stringResource(R.string.loan_apply_plan), { actions.onNavigate("LOA-011", state.selectedContractId, null) }, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun ApplySimulation(state: LoanFeatureState, actions: LoanActions) = LoanListLayout(Modifier.testTag(LedgerTestTags.LOAN_SIMULATION_APPLY)) {
    item { StateBanner(state) }
    item { LedgerBanner(stringResource(R.string.loan_apply_creates_versions), LedgerBannerVariant.WARNING) }
    state.simulation?.let { simulation ->
        val currency = state.contract?.currency ?: state.snapshot.baseCurrency
        val locale = LocalLocale.current.platformLocale
        item { MetricCard(stringResource(R.string.loan_apply_payment_now), CreditPolicy.money(simulation.paymentNowMinor, currency, locale), Modifier.fillMaxWidth(), MetricCardVariant.EMPHASIZED) }
        item { MetricCard(stringResource(R.string.loan_apply_principal), CreditPolicy.money(simulation.prepaymentPrincipalMinor, currency, locale), Modifier.fillMaxWidth()) }
        item { MetricCard(stringResource(R.string.loan_apply_penalty), CreditPolicy.money(simulation.penaltyMinor, currency, locale), Modifier.fillMaxWidth()) }
        item { LedgerText(stringResource(R.string.loan_apply_version_summary, simulation.afterSummary.paymentCount, simulation.afterSummary.endDate.localized(locale)), LedgerTextRole.BODY) }
    }
    if (state.presentation == LoanPresentation.CONFLICT) item { LedgerButton(stringResource(R.string.loan_reload_simulation), actions.onRetry, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY) }
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
    val locale = LocalLocale.current.platformLocale
    var showDate by remember { mutableStateOf(false) }
    FormSection(stringResource(R.string.loan_basic)) {
        LedgerTextField(state.draft.name, { actions.onFieldChanged(LoanField.NAME, it) }, stringResource(R.string.loan_name), Modifier.fillMaxWidth(), required = true)
        LedgerTextField(state.draft.lender, { actions.onFieldChanged(LoanField.LENDER, it) }, stringResource(R.string.loan_lender), Modifier.fillMaxWidth())
        SelectorField(stringResource(R.string.loan_disbursement_date), state.draft.startDate.toLocalDateOrNull()?.localized(locale) ?: stringResource(R.string.loan_choose_date), { showDate = true })
    }
    if (showDate) {
        LoanDatePicker(state.draft.startDate, { actions.onFieldChanged(LoanField.START_DATE, it); showDate = false }, { showDate = false })
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
    val locale = LocalLocale.current.platformLocale
    var showFirstDate by remember { mutableStateOf(false) }
    FormSection(stringResource(R.string.loan_terms)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            LoanRepaymentMethod.entries.forEach { method ->
                LedgerButton(methodLabel(method), { actions.onRepaymentMethod(method) }, variant = if (method == state.draft.repaymentMethod) LedgerButtonVariant.PRIMARY else LedgerButtonVariant.SECONDARY)
            }
        }
        SelectorField(stringResource(R.string.loan_rate_type), rateTypeLabel(state.draft.rateType), { actions.onRateType(if (state.draft.rateType == LoanRateType.FIXED) LoanRateType.FLOATING else LoanRateType.FIXED) })
        SelectorField(stringResource(R.string.loan_payment_frequency), frequencyLabel(state.draft.paymentFrequency), { actions.onFrequency(nextFrequency(state.draft.paymentFrequency)) })
        LedgerTextField(state.draft.paymentCount, { actions.onFieldChanged(LoanField.PAYMENT_COUNT, it) }, stringResource(R.string.loan_payment_count), Modifier.fillMaxWidth(), required = true, keyboardType = KeyboardType.Number)
        SelectorField(stringResource(R.string.loan_first_payment), state.draft.firstPaymentDate.toLocalDateOrNull()?.localized(locale) ?: stringResource(R.string.loan_choose_date), { showFirstDate = true })
        LedgerTextField(state.draft.annualRate, { actions.onFieldChanged(LoanField.ANNUAL_RATE, it) }, stringResource(R.string.loan_annual_rate), Modifier.fillMaxWidth(), required = true, keyboardType = KeyboardType.Decimal)
        LedgerTextField(state.draft.feePerPayment, { actions.onFieldChanged(LoanField.FEE_PER_PAYMENT, it) }, stringResource(R.string.loan_fee_per_payment), Modifier.fillMaxWidth(), keyboardType = KeyboardType.Decimal)
        SelectorField(stringResource(R.string.loan_prepayment_policy), prepaymentPolicyLabel(state.draft.prepaymentPolicy), { actions.onPrepaymentPolicy(nextPrepaymentPolicy(state.draft.prepaymentPolicy)) })
        StrategySelector(state, actions)
        if (state.draft.prepaymentPolicy == LoanPrepaymentPolicy.ALLOWED_WITH_PENALTY) {
            LedgerTextField(state.draft.penaltyRate, { actions.onFieldChanged(LoanField.PENALTY_RATE, it) }, stringResource(R.string.loan_penalty_rate), Modifier.fillMaxWidth(), required = true, keyboardType = KeyboardType.Decimal)
        }
        SelectorField(stringResource(R.string.loan_rounding_rule), loanRoundingLabel(state.draft.roundingMode), { actions.onRoundingMode(nextLoanRounding(state.draft.roundingMode)) })
    }
    if (showFirstDate) {
        LoanDatePicker(state.draft.firstPaymentDate, { actions.onFieldChanged(LoanField.FIRST_PAYMENT_DATE, it); showFirstDate = false }, { showFirstDate = false })
    }
}

@Composable
private fun RatePeriodEditor(state: LoanFeatureState, actions: LoanActions) {
    val locale = LocalLocale.current.platformLocale
    var startPicker by remember { mutableStateOf(false) }
    var endPicker by remember { mutableStateOf(false) }
    FormSection(stringResource(R.string.loan_rate_period_editor), description = stringResource(R.string.loan_rate_period_help)) {
        SelectorField(stringResource(R.string.loan_rate_from), state.draft.startDate.toLocalDateOrNull()?.localized(locale) ?: stringResource(R.string.loan_choose_date), { startPicker = true })
        SelectorField(stringResource(R.string.loan_rate_to), state.draft.endDate.toLocalDateOrNull()?.localized(locale) ?: stringResource(R.string.loan_open_ended), { endPicker = true })
        LedgerTextField(state.draft.annualRate, { actions.onFieldChanged(LoanField.ANNUAL_RATE, it) }, stringResource(R.string.loan_annual_rate), Modifier.fillMaxWidth(), required = true, keyboardType = KeyboardType.Decimal)
        if (state.draft.rateType == LoanRateType.FLOATING) LedgerText(stringResource(R.string.loan_floating_rate_evidence), LedgerTextRole.SUPPORTING)
        LedgerButton(stringResource(if (state.editingRatePeriodIndex == null) R.string.loan_add_rate_period else R.string.loan_update_rate_period), actions.onAddRatePeriod, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY)
    }
    if (startPicker) LoanDatePicker(state.draft.startDate, { actions.onFieldChanged(LoanField.START_DATE, it); startPicker = false }, { startPicker = false })
    if (endPicker) LoanDatePicker(state.draft.endDate, { actions.onFieldChanged(LoanField.END_DATE, it); endPicker = false }, { endPicker = false })
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
private fun AccountSelector(state: LoanFeatureState, receiving: Boolean, actions: LoanActions) {
    val accounts = state.snapshot.paymentAccounts.filter { account ->
        account.active && (state.contract == null || account.currency == state.contract?.currency)
    }
    FormSection(stringResource(if (receiving) R.string.loan_receiving_account else R.string.loan_payment_account)) {
        if (accounts.isEmpty()) {
            LedgerText(stringResource(R.string.loan_no_compatible_account), LedgerTextRole.SUPPORTING)
        } else {
            accounts.forEach { account ->
                LedgerChoiceRow(
                    "${account.name} · ${account.currency.value}",
                    state.selectedPaymentAccountId == account.id,
                    { actions.onSelectPaymentAccount(account.id) },
                )
            }
        }
    }
}

@Composable
private fun OperationDateTimeField(state: LoanFeatureState, onClick: () -> Unit) {
    val zone = state.operationZoneId ?: LedgerTheme.timeZone
    val occurredAt = state.operationOccurredAt ?: Instant.EPOCH
    val formatted = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(LocalLocale.current.platformLocale)
        .withZone(zone)
        .format(occurredAt)
    SelectorField(stringResource(R.string.loan_operation_time), formatted, onClick, supportingText = zone.id)
}

@Composable
private fun LoanOperationDateTimePicker(state: LoanFeatureState, actions: LoanActions, dismiss: () -> Unit) {
    val zone = state.operationZoneId ?: LedgerTheme.timeZone
    val local = (state.operationOccurredAt ?: Instant.EPOCH).atZone(zone)
    LedgerDateTimePickerFlow(
        initialDateMillis = local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        initialHour = local.hour,
        initialMinute = local.minute,
        onConfirm = { dateMillis, hour, minute ->
            val date = Instant.ofEpochMilli(dateMillis).atZone(ZoneOffset.UTC).toLocalDate()
            actions.onOperationOccurredAt(date.atTime(hour, minute).atZone(zone).toInstant())
            dismiss()
        },
        onDismiss = dismiss,
    )
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
                "${tranche.name} · ${CreditPolicy.money(tranche.remainingPrincipalMinor, requireNotNull(state.contract).currency, LocalLocale.current.platformLocale).formatted}",
                { actions.onSelectTranche(tranche.id) },
                Modifier.fillMaxWidth(),
                if (tranche.id == state.selectedTrancheId) LedgerButtonVariant.PRIMARY else LedgerButtonVariant.SECONDARY,
            )
        }
    }
}

@Composable
private fun ScheduleInstallmentSelector(state: LoanFeatureState, actions: LoanActions) {
    val tranche = state.tranche ?: return
    val locale = LocalLocale.current.platformLocale
    FormSection(stringResource(R.string.loan_schedule_installment)) {
        val openItems = tranche.schedule.filter { item ->
            item.actualPrincipalMinor + item.actualInterestMinor + item.actualFeeMinor + item.actualPenaltyMinor <
                item.principalMinor + item.interestMinor + item.feeMinor
        }
        if (openItems.isEmpty()) {
            LedgerText(stringResource(R.string.loan_no_open_installment), LedgerTextRole.SUPPORTING)
        } else {
            openItems.forEach { item ->
                val amount = item.principalMinor + item.interestMinor + item.feeMinor
                LedgerChoiceRow(
                    stringResource(
                        R.string.loan_schedule_installment_option,
                        item.installmentNumber,
                        item.plannedDate.localized(locale),
                        CreditPolicy.money(amount, requireNotNull(state.contract).currency, locale).formatted,
                    ),
                    state.selectedScheduleInstallmentNumber == item.installmentNumber,
                    { actions.onSelectScheduleInstallment(item.installmentNumber) },
                )
            }
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
            LedgerText(stringResource(R.string.loan_tranche_rate_summary, rateTypeLabel(tranche.rateType), methodLabel(tranche.repaymentMethod), tranche.ratePeriods.firstOrNull()?.annualRate?.annualDecimal?.let { LocaleNumberFormatter.percentage(it, LocalLocale.current.platformLocale) }.orEmpty()), LedgerTextRole.BODY)
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
        val locale = LocalLocale.current.platformLocale
        LedgerText(stringResource(R.string.loan_next_payment_value, next.plannedDate.localized(locale), CreditPolicy.money(Math.addExact(Math.addExact(next.principalMinor, next.interestMinor), next.feeMinor), contract.currency, locale).formatted), LedgerTextRole.BODY)
    }
}

@Composable
private fun PreviewTable(state: LoanFeatureState) = ScheduleTable(
    state.preview.flatMap { revision -> revision.items.map { item -> LoanScheduleItemView(item.installmentNumber, item.plannedDate, item.principalMinor, item.interestMinor, item.feeMinor, item.remainingPrincipalMinor, 0, 0, 0, 0) } },
    state.contract?.currency ?: state.snapshot.loanAccounts.firstOrNull()?.currency ?: state.snapshot.baseCurrency,
)

@Composable
private fun ScheduleSummary(rows: List<LoanScheduleItemView>, currency: app.ledger.core.money.CurrencyCode) {
    val locale = LocalLocale.current.platformLocale
    val principal = rows.sumOf { it.principalMinor }
    val interest = rows.sumOf { it.interestMinor }
    val fees = rows.sumOf { it.feeMinor }
    LedgerCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            LedgerText(stringResource(R.string.loan_schedule_summary), LedgerTextRole.SECTION)
            LedgerText(stringResource(R.string.loan_schedule_summary_value, rows.size, CreditPolicy.money(principal, currency, locale).formatted, CreditPolicy.money(interest, currency, locale).formatted, CreditPolicy.money(fees, currency, locale).formatted), LedgerTextRole.BODY)
            rows.lastOrNull()?.let { LedgerText(stringResource(R.string.loan_schedule_end_date, it.plannedDate.localized(locale)), LedgerTextRole.SUPPORTING) }
        }
    }
}

@Composable
private fun SimulationSummary(label: String, summary: LoanScheduleSummary, currency: app.ledger.core.money.CurrencyCode) {
    val locale = LocalLocale.current.platformLocale
    LedgerCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            LedgerText(label, LedgerTextRole.SECTION)
            LedgerText(stringResource(R.string.loan_simulation_summary, summary.paymentCount, CreditPolicy.money(summary.totalPaymentMinor, currency, locale).formatted, CreditPolicy.money(summary.interestMinor, currency, locale).formatted, CreditPolicy.money(summary.feeMinor, currency, locale).formatted, summary.endDate.localized(locale)), LedgerTextRole.BODY)
        }
    }
}

@Composable
private fun ScheduleTable(
    rows: List<LoanScheduleItemView>,
    currency: app.ledger.core.money.CurrencyCode,
    actualOnly: Boolean = false,
) {
    val locale = LocalLocale.current.platformLocale
    AccessibleDataTable(
        AccessibleTableUiModel(
            caption = stringResource(R.string.loan_schedule_table),
            columnHeaders = listOf(stringResource(R.string.loan_term), stringResource(R.string.loan_date), stringResource(R.string.loan_principal), stringResource(R.string.loan_interest), stringResource(R.string.loan_fee_component), stringResource(R.string.loan_penalty_component), stringResource(R.string.loan_remaining_principal)),
            rows = rows.take(60).map {
                val principal = if (actualOnly) it.actualPrincipalMinor else it.principalMinor
                val interest = if (actualOnly) it.actualInterestMinor else it.interestMinor
                val fee = if (actualOnly) it.actualFeeMinor else it.feeMinor
                listOf(
                    LocaleNumberFormatter.integer(it.installmentNumber, locale), it.plannedDate.localized(locale),
                    CreditPolicy.money(principal, currency, locale).formatted, CreditPolicy.money(interest, currency, locale).formatted,
                    CreditPolicy.money(fee, currency, locale).formatted, CreditPolicy.money(if (actualOnly) it.actualPenaltyMinor else 0L, currency, locale).formatted,
                    CreditPolicy.money(it.remainingPrincipalMinor, currency, locale).formatted,
                )
            },
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

@Composable
private fun rateTypeLabel(type: LoanRateType): String = stringResource(if (type == LoanRateType.FIXED) R.string.loan_rate_type_fixed else R.string.loan_rate_type_floating)

@Composable
private fun frequencyLabel(frequency: PaymentFrequency): String = stringResource(
    when (frequency) {
        PaymentFrequency.WEEKLY -> R.string.loan_frequency_weekly
        PaymentFrequency.BIWEEKLY -> R.string.loan_frequency_biweekly
        PaymentFrequency.MONTHLY -> R.string.loan_frequency_monthly
        PaymentFrequency.QUARTERLY -> R.string.loan_frequency_quarterly
        PaymentFrequency.YEARLY -> R.string.loan_frequency_yearly
        PaymentFrequency.CUSTOM -> R.string.loan_frequency_custom
    },
)

private fun nextFrequency(current: PaymentFrequency): PaymentFrequency {
    val supported = listOf(PaymentFrequency.WEEKLY, PaymentFrequency.BIWEEKLY, PaymentFrequency.MONTHLY, PaymentFrequency.QUARTERLY, PaymentFrequency.YEARLY)
    return supported[(supported.indexOf(current).takeIf { it >= 0 } ?: 0).plus(1).mod(supported.size)]
}

@Composable
private fun prepaymentPolicyLabel(policy: LoanPrepaymentPolicy): String = stringResource(
    when (policy) {
        LoanPrepaymentPolicy.ALLOWED -> R.string.loan_prepayment_allowed
        LoanPrepaymentPolicy.ALLOWED_WITH_PENALTY -> R.string.loan_prepayment_penalty
        LoanPrepaymentPolicy.NOT_ALLOWED -> R.string.loan_prepayment_not_allowed
    },
)

private fun nextPrepaymentPolicy(current: LoanPrepaymentPolicy): LoanPrepaymentPolicy = LoanPrepaymentPolicy.entries[(current.ordinal + 1).mod(LoanPrepaymentPolicy.entries.size)]

@Composable
private fun loanRoundingLabel(mode: RoundingMode): String = stringResource(
    when (mode) {
        RoundingMode.HALF_EVEN -> R.string.installment_rounding_half_even
        RoundingMode.HALF_UP -> R.string.installment_rounding_half_up
        else -> R.string.installment_rounding_down
    },
)

private fun nextLoanRounding(current: RoundingMode): RoundingMode {
    val modes = listOf(RoundingMode.HALF_EVEN, RoundingMode.HALF_UP, RoundingMode.DOWN)
    return modes[(modes.indexOf(current).takeIf { it >= 0 } ?: 0).plus(1).mod(modes.size)]
}

@Composable
private fun loanComponentLabel(component: LoanPaymentComponent): String = stringResource(
    when (component) {
        LoanPaymentComponent.PRINCIPAL -> R.string.loan_principal_component
        LoanPaymentComponent.INTEREST -> R.string.loan_interest_component
        LoanPaymentComponent.FEE -> R.string.loan_fee_component
        LoanPaymentComponent.PENALTY -> R.string.loan_penalty_component
    },
)

@Composable
private fun LoanDatePicker(value: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val initial = value.toLocalDateOrNull() ?: LocalDate.now()
    LedgerDatePickerFlow(
        initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        { millis -> onConfirm(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()) },
        onDismiss,
    )
}

private fun LocalDate.localized(locale: java.util.Locale): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(this)
private fun String.toLocalDateOrNull(): LocalDate? = runCatching { LocalDate.parse(this) }.getOrNull()

private fun Map<String, String>.loanId(name: String): StableId? = get(name)?.let { StableId.parse(it).getOrNull() }
