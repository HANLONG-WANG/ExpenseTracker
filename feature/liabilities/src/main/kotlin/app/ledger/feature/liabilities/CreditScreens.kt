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
import app.ledger.core.designsystem.LedgerDatePickerFlow
import app.ledger.core.designsystem.LedgerLoadingState
import app.ledger.core.designsystem.LedgerProgressIndicator
import app.ledger.core.designsystem.LedgerStatusVariant
import app.ledger.core.designsystem.LedgerTabRow
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextField
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.LedgerToggleRow
import app.ledger.core.designsystem.MetricCard
import app.ledger.core.designsystem.MetricCardVariant
import app.ledger.core.designsystem.StatusBadge
import app.ledger.core.designsystem.SelectorField
import app.ledger.core.designsystem.UiErrorCode
import app.ledger.core.money.AmountSemantic
import app.ledger.finance.application.CreditAccountView
import app.ledger.finance.application.CreditStatementView
import app.ledger.finance.domain.AutoGenerationMode
import app.ledger.finance.domain.CreditStatementStatus
import app.ledger.finance.domain.StatementAssignmentMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
public fun CreditDestination(
    screenId: String,
    state: CreditLoadState,
    encodedArguments: Map<String, String>,
    actions: CreditActions,
) {
    when (state) {
        CreditLoadState.Loading -> LedgerLoadingState(Modifier.fillMaxSize(), stringResource(R.string.credit_loading))
        is CreditLoadState.Failure -> LedgerErrorState(UiErrorCode(state.code), stringResource(R.string.credit_load_failed), actions.onRetry)
        is CreditLoadState.Content -> {
            val accountId = encodedArguments.stableId("accountId") ?: state.state.selectedAccountId
            val statementId = encodedArguments.stableId("statementId") ?: state.state.selectedStatementId
            val content = state.state.copy(selectedAccountId = accountId, selectedStatementId = statementId)
            when (screenId) {
                "REC-014" -> CreditPaymentEditor(content, actions)
                "CRD-001" -> CreditAccountDetail(content, actions)
                "CRD-002" -> CreditProfileEditor(content, actions)
                "CRD-003" -> CreditStatementList(content, actions)
                "CRD-004" -> CreditStatementDetail(content, actions)
                "CRD-005" -> OfficialStatementEditor(content, actions)
                "CRD-006" -> StatementAssignmentEditor(content, actions)
                "CRD-007" -> PaymentAllocationEditor(content, actions)
                "CRD-008" -> AutoPaymentEditor(content, actions)
                else -> LedgerErrorState(UiErrorCode("CREDIT_SCREEN_UNKNOWN"), stringResource(R.string.credit_load_failed), actions.onRetry)
            }
        }
    }
}

@Composable
private fun CreditPaymentEditor(state: CreditFeatureState, actions: CreditActions) {
    val account = state.account ?: return CreditNotFound(actions)
    val locale = LocalLocale.current.platformLocale
    var showDatePicker by remember { mutableStateOf(false) }
    ScreenList(Modifier.testTag(LedgerTestTags.CREDIT_PAYMENT)) {
        item { ValidationBanner(state) }
        item {
            FormSection(stringResource(R.string.credit_payment_accounts)) {
                SelectorField(
                    stringResource(R.string.credit_payment_account),
                    paymentAccountName(state),
                    actions.onNextPaymentAccount,
                    supportingText = if ("paymentAccount" in state.validationFields) stringResource(R.string.credit_payment_account_required) else null,
                )
                SelectorField(
                    stringResource(R.string.credit_account_title),
                    account.name,
                    {},
                    enabled = false,
                )
            }
        }
        item {
            LedgerTextField(
                state.draft.amount,
                { actions.onFieldChanged(CreditField.AMOUNT, it) },
                stringResource(R.string.credit_payment_amount),
                Modifier.fillMaxWidth().testTag(LedgerTestTags.AMOUNT),
                errorText = if ("amount" in state.validationFields) stringResource(R.string.credit_invalid_amount) else null,
                required = true,
                keyboardType = KeyboardType.Decimal,
            )
        }
        item { AllocationChoices(state, actions) }
        item {
            SelectorField(
                stringResource(R.string.credit_payment_date),
                state.draft.date.toLocalDateOrNull()?.localized(locale) ?: stringResource(R.string.credit_choose_date),
                { showDatePicker = true },
                supportingText = if ("date" in state.validationFields) stringResource(R.string.credit_choose_date) else null,
            )
        }
        item { LedgerBanner(stringResource(R.string.credit_bookkeeping_disclaimer), LedgerBannerVariant.INFO) }
    }
    if (showDatePicker) {
        val initial = state.draft.date.toLocalDateOrNull() ?: LocalDate.now()
        LedgerDatePickerFlow(
            initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            { millis ->
                actions.onFieldChanged(CreditField.DATE, Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString())
                showDatePicker = false
            },
            { showDatePicker = false },
        )
    }
}

@Composable
private fun CreditAccountDetail(state: CreditFeatureState, actions: CreditActions) {
    val account = state.account ?: return CreditNotFound(actions)
    val locale = LocalLocale.current.platformLocale
    val current = account.statements.firstOrNull()
    val limit = account.profile?.temporaryLimitMinor ?: account.profile?.standardLimitMinor
    ScreenList(Modifier.testTag(LedgerTestTags.CREDIT_ACCOUNT_DETAIL)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LedgerText(account.name, LedgerTextRole.TITLE)
                StatusBadge(accountStatus(state), accountVariant(state))
            }
        }
        if (state.presentation == CreditPresentation.POSITIVE_BALANCE) item { LedgerBanner(stringResource(R.string.credit_positive_balance_explanation), LedgerBannerVariant.INFO) }
        if (state.presentation == CreditPresentation.OVERDUE) item { LedgerBanner(stringResource(R.string.credit_overdue_warning), LedgerBannerVariant.DANGER) }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
                MetricCard(stringResource(R.string.credit_debt), CreditPolicy.money(account.debtMinor, account.currency, locale, AmountSemantic.OUTFLOW), Modifier.fillMaxWidth(), MetricCardVariant.EMPHASIZED)
                current?.let { MetricCard(stringResource(R.string.credit_current_remaining), CreditPolicy.money(it.remainingAmountMinor, account.currency, locale, AmountSemantic.OUTFLOW), Modifier.fillMaxWidth()) }
                MetricCard(stringResource(R.string.credit_unbilled), CreditPolicy.money(account.unbilledMinor, account.currency, locale), Modifier.fillMaxWidth())
                current?.let { MetricCard(stringResource(R.string.credit_paid), CreditPolicy.money(it.paidAmountMinor, account.currency, locale), Modifier.fillMaxWidth()) }
                MetricCard(stringResource(R.string.credit_overdue), CreditPolicy.money(account.overdueMinor, account.currency, locale, AmountSemantic.OUTFLOW), Modifier.fillMaxWidth())
                account.availableLimitMinor?.let { MetricCard(stringResource(R.string.credit_available_limit), CreditPolicy.money(it, account.currency, locale), Modifier.fillMaxWidth()) }
                val profile = account.profile
                profile?.temporaryLimitMinor?.let { temporary ->
                    MetricCard(
                        stringResource(R.string.credit_temporary_limit),
                        CreditPolicy.money(temporary, account.currency, locale),
                        Modifier.fillMaxWidth(),
                        explanation = profile.temporaryLimitExpiresOn?.let { stringResource(R.string.credit_expires_on, it.localized(locale)) },
                    )
                }
            }
        }
        if (limit != null && limit > 0L) item {
            LedgerProgressIndicator(
                account.debtMinor.toFloat() / limit.toFloat(),
                Modifier.fillMaxWidth(),
                stringResource(R.string.credit_limit_usage),
            )
        }
        current?.let { statement ->
            item {
                LedgerCard(Modifier.fillMaxWidth(), onClick = { actions.onNavigate("CRD-004", statement.id) }) {
                    Column(Modifier.padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                        LedgerText(stringResource(R.string.credit_recent_statement), LedgerTextRole.SECTION)
                        LedgerText(stringResource(R.string.credit_cycle, statement.cycleStart.localized(locale), statement.cycleEnd.localized(locale)), LedgerTextRole.BODY)
                        LedgerText(stringResource(R.string.credit_due_date, statement.dueDate.localized(locale)), LedgerTextRole.SUPPORTING)
                    }
                }
            }
        }
        item {
            LedgerCard(Modifier.fillMaxWidth(), onClick = { actions.onNavigate("INS-001", null) }) {
                Column(Modifier.padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                    LedgerText(stringResource(R.string.credit_installment_preview), LedgerTextRole.SECTION)
                    AmountText(CreditPolicy.money(account.futureInstallmentMinor, account.currency, locale), AmountSize.MEDIUM)
                    LedgerText(
                        account.nextInstallmentDate?.let { stringResource(R.string.credit_next_installment, it.localized(locale)) }
                            ?: stringResource(R.string.credit_no_future_installments),
                        LedgerTextRole.SUPPORTING,
                    )
                }
            }
        }
        item {
            LedgerTabRow(
                0,
                listOf(stringResource(R.string.credit_overview_tab), stringResource(R.string.credit_statements), stringResource(R.string.credit_installments_tab)),
                { index ->
                    when (index) {
                        1 -> actions.onNavigate("CRD-003", account.id)
                        2 -> actions.onNavigate("INS-001", null)
                    }
                },
            )
        }
        item { LedgerButton(stringResource(R.string.credit_profile), { actions.onNavigate("CRD-002", account.id) }, Modifier.fillMaxWidth(), LedgerButtonVariant.TEXT) }
        item { LedgerButton(stringResource(R.string.credit_record_payment), { actions.onNavigate("REC-014", null) }, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun CreditProfileEditor(state: CreditFeatureState, actions: CreditActions) {
    val account = state.account ?: return CreditNotFound(actions)
    val locale = LocalLocale.current.platformLocale
    var showExpiryPicker by remember { mutableStateOf(false) }
    ScreenList(Modifier.testTag(LedgerTestTags.CREDIT_PROFILE)) {
        item { ValidationBanner(state) }
        item { LedgerText(stringResource(R.string.credit_profile_calendar), LedgerTextRole.SECTION) }
        item { LedgerTextField(state.draft.statementDay, { actions.onFieldChanged(CreditField.STATEMENT_DAY, it) }, stringResource(R.string.credit_statement_day), Modifier.fillMaxWidth(), keyboardType = KeyboardType.Number) }
        item {
            SelectorField(
                stringResource(R.string.credit_due_rule),
                stringResource(if (state.draft.dueRuleMode == CreditDueRuleMode.FIXED_DAY) R.string.credit_due_rule_fixed else R.string.credit_due_rule_after),
                actions.onCycleDueRule,
            )
        }
        item { LedgerTextField(state.draft.dueDay, { actions.onFieldChanged(CreditField.DUE_DAY, it) }, stringResource(if (state.draft.dueRuleMode == CreditDueRuleMode.FIXED_DAY) R.string.credit_due_day else R.string.credit_days_after), Modifier.fillMaxWidth(), errorText = if ("dueDay" in state.validationFields) stringResource(R.string.credit_invalid_day) else null, keyboardType = KeyboardType.Number) }
        item { SelectorField(stringResource(R.string.credit_statement_zone), state.draft.zoneId, actions.onNextZone, supportingText = stringResource(R.string.credit_zone_selector_help)) }
        item { LedgerText(stringResource(R.string.credit_profile_limits), LedgerTextRole.SECTION) }
        item { LedgerTextField(state.draft.standardLimit, { actions.onFieldChanged(CreditField.STANDARD_LIMIT, it) }, stringResource(R.string.credit_standard_limit), Modifier.fillMaxWidth(), keyboardType = KeyboardType.Decimal) }
        item { LedgerTextField(state.draft.temporaryLimit, { actions.onFieldChanged(CreditField.TEMPORARY_LIMIT, it) }, stringResource(R.string.credit_temporary_limit), Modifier.fillMaxWidth(), keyboardType = KeyboardType.Decimal) }
        item { SelectorField(stringResource(R.string.credit_temporary_expiry), state.draft.temporaryExpires.toLocalDateOrNull()?.localized(locale) ?: stringResource(R.string.credit_no_expiry), { showExpiryPicker = true }) }
        item { SelectionRow(paymentAccountName(state), actions.onNextPaymentAccount) }
        item {
            LedgerToggleRow(
                stringResource(R.string.credit_auto_payment),
                state.draft.autoPaymentMode == AutoGenerationMode.FORMAL_TRANSACTION,
                actions.onToggleAutoPayment,
                supportingText = stringResource(R.string.credit_auto_mode_explanation),
            )
        }
        item { LedgerText(stringResource(R.string.credit_no_minimum_payment), LedgerTextRole.SUPPORTING) }
    }
    if (showExpiryPicker) {
        val initial = state.draft.temporaryExpires.toLocalDateOrNull() ?: LocalDate.now()
        LedgerDatePickerFlow(
            initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            { millis -> actions.onFieldChanged(CreditField.TEMPORARY_EXPIRY, Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()); showExpiryPicker = false },
            { showExpiryPicker = false },
        )
    }
}

@Composable
private fun CreditStatementList(state: CreditFeatureState, actions: CreditActions) {
    val account = state.account ?: return CreditNotFound(actions)
    if (account.statements.isEmpty()) {
        Box(Modifier.fillMaxSize().testTag(LedgerTestTags.CREDIT_STATEMENTS)) {
            LedgerEmptyState(stringResource(R.string.credit_no_statements), stringResource(R.string.credit_no_statements_body), stringResource(R.string.credit_profile), { actions.onNavigate("CRD-002", account.id) })
        }
        return
    }
    var selectedFilter by remember { mutableIntStateOf(0) }
    val visible = account.statements.filter { statement ->
        when (selectedFilter) {
            1 -> statement.status in setOf(CreditStatementStatus.OPEN, CreditStatementStatus.UNPAID, CreditStatementStatus.PARTIALLY_PAID)
            2 -> statement.status == CreditStatementStatus.PAID
            3 -> statement.status == CreditStatementStatus.OVERDUE
            else -> true
        }
    }
    ScreenList(Modifier.testTag(LedgerTestTags.CREDIT_STATEMENTS)) {
        item {
            LedgerTabRow(
                selectedFilter,
                listOf(stringResource(R.string.credit_filter_all), stringResource(R.string.credit_filter_open), stringResource(R.string.credit_status_paid), stringResource(R.string.credit_status_overdue)),
                { selectedFilter = it },
            )
        }
        if (visible.isEmpty()) item { LedgerBanner(stringResource(R.string.credit_change_filter), LedgerBannerVariant.INFO) }
        items(visible, key = { it.id.toString() }) { statement -> StatementRow(statement, account, actions) }
        item { LedgerText(stringResource(R.string.credit_no_minimum_payment), LedgerTextRole.SUPPORTING) }
    }
}

@Composable
private fun StatementRow(statement: CreditStatementView, account: CreditAccountView, actions: CreditActions) {
    val locale = LocalLocale.current.platformLocale
    LedgerCard(Modifier.fillMaxWidth(), onClick = { actions.onNavigate("CRD-004", statement.id) }) {
        Column(Modifier.padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LedgerText(stringResource(R.string.credit_cycle, statement.cycleStart.localized(locale), statement.cycleEnd.localized(locale)), LedgerTextRole.SECTION)
                StatementBadge(statement.status)
            }
            AmountText(CreditPolicy.money(statement.remainingAmountMinor, account.currency, LocalLocale.current.platformLocale), AmountSize.MEDIUM)
            LedgerText(stringResource(R.string.credit_due_date, statement.dueDate.localized(locale)), LedgerTextRole.SUPPORTING)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LedgerText(stringResource(R.string.credit_due_amount), LedgerTextRole.SUPPORTING)
                AmountText(CreditPolicy.money(statement.officialAmountMinor ?: statement.estimatedAmountMinor, account.currency, locale), AmountSize.LIST)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LedgerText(stringResource(R.string.credit_paid), LedgerTextRole.SUPPORTING)
                AmountText(CreditPolicy.money(statement.paidAmountMinor, account.currency, locale), AmountSize.LIST)
            }
            LedgerText(
                stringResource(
                    R.string.credit_estimate_official,
                    CreditPolicy.money(statement.estimatedAmountMinor, account.currency, locale).formatted,
                    statement.officialAmountMinor?.let { CreditPolicy.money(it, account.currency, locale).formatted } ?: stringResource(R.string.credit_not_recorded),
                ),
                LedgerTextRole.SUPPORTING,
            )
        }
    }
}

@Composable
private fun CreditStatementDetail(state: CreditFeatureState, actions: CreditActions) {
    val account = state.account ?: return CreditNotFound(actions)
    val statement = state.statement ?: return CreditNotFound(actions)
    val locale = LocalLocale.current.platformLocale
    ScreenList(Modifier.testTag(LedgerTestTags.CREDIT_STATEMENT_DETAIL)) {
        item { StatementBadge(statement.status) }
        if (statement.sealed) item { LedgerBanner(stringResource(R.string.credit_sealed_warning), LedgerBannerVariant.WARNING) }
        item { MetricCard(stringResource(R.string.credit_estimated), CreditPolicy.money(statement.estimatedAmountMinor, account.currency, locale), Modifier.fillMaxWidth()) }
        statement.officialAmountMinor?.let { official -> item { MetricCard(stringResource(R.string.credit_official), CreditPolicy.money(official, account.currency, locale), Modifier.fillMaxWidth(), MetricCardVariant.EMPHASIZED) } }
        statement.differenceMinor?.let { difference -> item { MetricCard(stringResource(R.string.credit_difference), CreditPolicy.money(difference, account.currency, locale), Modifier.fillMaxWidth(), explanation = stringResource(R.string.credit_difference_no_adjustment)) } }
        item { MetricCard(stringResource(R.string.credit_paid), CreditPolicy.money(statement.paidAmountMinor, account.currency, locale), Modifier.fillMaxWidth()) }
        item { MetricCard(stringResource(R.string.credit_remaining), CreditPolicy.money(statement.remainingAmountMinor, account.currency, locale), Modifier.fillMaxWidth()) }
        item {
            val total = statement.paidAmountMinor + statement.remainingAmountMinor
            LedgerProgressIndicator(
                if (total > 0L) statement.paidAmountMinor.toFloat() / total.toFloat() else 1f,
                Modifier.fillMaxWidth(),
                stringResource(R.string.credit_payment_progress),
            )
        }
        item { LedgerText(stringResource(R.string.credit_statement_transactions), LedgerTextRole.SECTION) }
        if (statement.transactions.isEmpty()) item { LedgerText(stringResource(R.string.credit_no_statement_transactions), LedgerTextRole.SUPPORTING) }
        items(statement.transactions, key = { "statement-${it.transactionId}" }) { transaction ->
            LedgerCard(Modifier.fillMaxWidth(), onClick = { actions.onNavigate("JRN-007", transaction.transactionId) }) {
                Row(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        LedgerText(transaction.note ?: stringResource(R.string.credit_statement_transaction), LedgerTextRole.BODY)
                        LedgerText(transaction.localDate.localized(locale), LedgerTextRole.SUPPORTING)
                    }
                    AmountText(CreditPolicy.money(transaction.amountMinor, account.currency, locale), AmountSize.LIST)
                }
            }
        }
        item { LedgerText(stringResource(R.string.credit_payment_allocations), LedgerTextRole.SECTION) }
        if (statement.paymentAllocations.isEmpty()) item { LedgerText(stringResource(R.string.credit_no_payment_allocations), LedgerTextRole.SUPPORTING) }
        items(statement.paymentAllocations, key = { "allocation-${it.transactionId}" }) { allocation ->
            LedgerCard(Modifier.fillMaxWidth(), onClick = { actions.onNavigate("JRN-007", allocation.transactionId) }) {
                Row(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm), horizontalArrangement = Arrangement.SpaceBetween) {
                    LedgerText(allocation.localDate.localized(locale), LedgerTextRole.BODY)
                    AmountText(CreditPolicy.money(allocation.amountMinor, account.currency, locale), AmountSize.LIST)
                }
            }
        }
        if (statement.sealed) statement.transactions.firstOrNull()?.let { transaction -> item {
            LedgerButton(stringResource(R.string.credit_review_sealed_impact), { actions.onNavigate("CRD-006", transaction.transactionId) }, Modifier.fillMaxWidth(), LedgerButtonVariant.TEXT)
        } }
        item { LedgerButton(stringResource(R.string.credit_record_official), { actions.onNavigate("CRD-005", statement.id) }, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY) }
        item { LedgerButton(stringResource(R.string.credit_record_payment), { actions.onNavigate("REC-014", null) }, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun OfficialStatementEditor(state: CreditFeatureState, actions: CreditActions) {
    val statement = state.statement ?: return CreditNotFound(actions)
    val account = state.account ?: return CreditNotFound(actions)
    val locale = LocalLocale.current.platformLocale
    ScreenList(Modifier.testTag(LedgerTestTags.CREDIT_OFFICIAL_STATEMENT)) {
        item { LedgerText(stringResource(R.string.credit_estimated_value, CreditPolicy.money(statement.estimatedAmountMinor, account.currency, locale).formatted), LedgerTextRole.BODY) }
        item { LedgerTextField(state.draft.officialAmount, { actions.onFieldChanged(CreditField.OFFICIAL_AMOUNT, it) }, stringResource(R.string.credit_official), Modifier.fillMaxWidth(), required = true, keyboardType = KeyboardType.Decimal) }
        if (state.presentation == CreditPresentation.DIFFERENCE || statement.differenceMinor != null) item { LedgerBanner(stringResource(R.string.credit_difference_no_adjustment), LedgerBannerVariant.INFO) }
        item { LedgerToggleRow(stringResource(R.string.credit_seal_statement), state.draft.sealOfficial, actions.onToggleSeal, supportingText = stringResource(R.string.credit_seal_explanation)) }
        item { LedgerBanner(stringResource(R.string.credit_seal_explanation), LedgerBannerVariant.INFO) }
        if (state.presentation == CreditPresentation.SAVING) item { LedgerProgressIndicator(null, accessibleText = stringResource(R.string.credit_saving)) }
    }
}

@Composable
private fun StatementAssignmentEditor(state: CreditFeatureState, actions: CreditActions) {
    val account = state.account ?: return CreditNotFound(actions)
    val locale = LocalLocale.current.platformLocale
    val ordered = account.statements.sortedBy { it.cycleEnd }
    val currentIndex = ordered.indexOfFirst { it.id == state.selectedStatementId }.takeIf { it >= 0 } ?: ordered.lastIndex
    val choices = listOf(
        StatementAssignmentMode.PREVIOUS_CYCLE to ordered.getOrNull(currentIndex - 1),
        StatementAssignmentMode.EXPLICIT_STATEMENT to ordered.getOrNull(currentIndex),
        StatementAssignmentMode.NEXT_CYCLE to ordered.getOrNull(currentIndex + 1),
    )
    var selectedMode by remember { mutableStateOf<StatementAssignmentMode?>(null) }
    ScreenList(Modifier.testTag(LedgerTestTags.CREDIT_ASSIGNMENT)) {
        if (state.presentation == CreditPresentation.SEALED_WARNING) item { LedgerBanner(stringResource(R.string.credit_assignment_sealed_warning), LedgerBannerVariant.WARNING) }
        item { LedgerText(stringResource(R.string.credit_assignment_explanation), LedgerTextRole.BODY) }
        items(choices.filter { it.second != null }) { (mode, candidate) ->
            requireNotNull(candidate)
            LedgerCard(Modifier.fillMaxWidth(), onClick = { selectedMode = mode }) {
                Column(Modifier.padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        LedgerText(assignmentModeLabel(mode), LedgerTextRole.SECTION)
                        StatusBadge(if (selectedMode == mode) stringResource(R.string.credit_selected) else statementStatus(candidate.status), if (selectedMode == mode) LedgerStatusVariant.POSITIVE else LedgerStatusVariant.NEUTRAL)
                    }
                    LedgerText(stringResource(R.string.credit_cycle, candidate.cycleStart.localized(locale), candidate.cycleEnd.localized(locale)), LedgerTextRole.BODY)
                    AmountText(CreditPolicy.money(candidate.remainingAmountMinor, account.currency, locale), AmountSize.LIST)
                    LedgerText(stringResource(R.string.credit_manual_assignment), LedgerTextRole.SUPPORTING)
                }
            }
        }
        item { LedgerButton(stringResource(R.string.credit_confirm_assignment), { selectedMode?.let(actions.onAssignment) }, Modifier.fillMaxWidth(), enabled = selectedMode != null && state.presentation != CreditPresentation.SAVING) }
        if (state.presentation == CreditPresentation.SAVING) item { LedgerProgressIndicator(null, accessibleText = stringResource(R.string.credit_saving)) }
        item { LedgerText(stringResource(R.string.credit_assignment_revision_audit), LedgerTextRole.SUPPORTING) }
    }
}

@Composable
private fun PaymentAllocationEditor(state: CreditFeatureState, actions: CreditActions) {
    val account = state.account ?: return CreditNotFound(actions)
    val locale = LocalLocale.current.platformLocale
    val payment = CreditPolicy.parseMinor(state.draft.amount, account.currency) ?: 0L
    var remaining = payment
    val rows = account.statements.sortedBy { it.dueDate }.map { statement ->
        val allocated = when (state.draft.allocationMode) {
            CreditAllocationMode.EARLIEST_UNPAID -> minOf(remaining, maxOf(0L, statement.remainingAmountMinor)).also { remaining -= it }
            CreditAllocationMode.SPECIFIC -> if (state.draft.selectedStatementId == statement.id) minOf(payment, maxOf(0L, statement.remainingAmountMinor)).also { remaining -= it } else 0L
            CreditAllocationMode.UNALLOCATED_ADVANCE -> 0L
        }
        statement to allocated
    }
    ScreenList(Modifier.testTag(LedgerTestTags.CREDIT_PAYMENT_ALLOCATION)) {
        item { ValidationBanner(state) }
        item { MetricCard(stringResource(R.string.credit_payment_total), CreditPolicy.money(payment, account.currency, locale), Modifier.fillMaxWidth(), MetricCardVariant.EMPHASIZED) }
        item { AllocationChoices(state, actions) }
        item { LedgerText(stringResource(R.string.credit_allocation_table), LedgerTextRole.SECTION) }
        items(rows, key = { it.first.id.toString() }) { (statement, allocated) ->
            LedgerCard(Modifier.fillMaxWidth(), onClick = { actions.onSelectStatement(statement.id) }) {
                Row(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        LedgerText(statement.dueDate.localized(locale), LedgerTextRole.BODY)
                        LedgerText(stringResource(R.string.credit_statement_remaining, CreditPolicy.money(statement.remainingAmountMinor, account.currency, locale).formatted), LedgerTextRole.SUPPORTING)
                    }
                    AmountText(CreditPolicy.money(allocated, account.currency, locale), AmountSize.LIST)
                }
            }
        }
        item {
            val difference = if (state.draft.allocationMode == CreditAllocationMode.UNALLOCATED_ADVANCE) payment else remaining
            MetricCard(
                stringResource(R.string.credit_allocation_difference),
                CreditPolicy.money(difference, account.currency, locale),
                Modifier.fillMaxWidth(),
                if (difference == 0L || state.draft.allocationMode == CreditAllocationMode.UNALLOCATED_ADVANCE) MetricCardVariant.STANDARD else MetricCardVariant.EMPHASIZED,
            )
        }
        item { LedgerText(stringResource(R.string.credit_allocation_immutable), LedgerTextRole.SUPPORTING) }
    }
}

@Composable
private fun AutoPaymentEditor(state: CreditFeatureState, actions: CreditActions) {
    val account = state.account ?: return CreditNotFound(actions)
    val statement = state.statement
    val formallyEligible = CreditPolicy.formalAutoPaymentEligible(state)
    val enabled = state.draft.autoPaymentMode == AutoGenerationMode.FORMAL_TRANSACTION
    val paymentAccount = state.snapshot.paymentAccounts.singleOrNull { it.id == account.profile?.defaultPaymentAccountId }
    ScreenList(Modifier.testTag(LedgerTestTags.CREDIT_AUTO_PAYMENT)) {
        item { LedgerToggleRow(stringResource(R.string.credit_auto_payment), enabled, actions.onToggleAutoPayment, supportingText = stringResource(R.string.credit_auto_mode_explanation), enabled = formallyEligible || enabled) }
        item { EligibilityRow(stringResource(R.string.credit_eligibility_official), statement?.officialAmountMinor != null) }
        item { EligibilityRow(stringResource(R.string.credit_eligibility_remaining), minOf(statement?.remainingAmountMinor ?: 0L, account.debtMinor) > 0L) }
        item { EligibilityRow(stringResource(R.string.credit_eligibility_payment_account), paymentAccount?.active == true) }
        item { LedgerText(stringResource(R.string.credit_default_payment_account, paymentAccount?.name ?: stringResource(R.string.credit_not_recorded)), LedgerTextRole.SUPPORTING) }
        item { EligibilityRow(stringResource(R.string.credit_eligibility_active), !account.archived) }
        item { EligibilityRow(stringResource(R.string.credit_eligibility_duplicate), statement?.hasAutomaticPayment == false) }
        if (!formallyEligible) item { LedgerBanner(stringResource(R.string.credit_candidate_fallback), LedgerBannerVariant.WARNING) }
        item { LedgerBanner(stringResource(R.string.credit_bookkeeping_disclaimer), LedgerBannerVariant.INFO) }
    }
}

@Composable
private fun AllocationChoices(state: CreditFeatureState, actions: CreditActions) {
    val account = state.account ?: return
    val locale = LocalLocale.current.platformLocale
    LedgerCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            LedgerText(stringResource(R.string.credit_payment_allocation), LedgerTextRole.SECTION)
            LedgerButton(
                stringResource(R.string.credit_earliest_default),
                actions.onSelectEarliest,
                Modifier.fillMaxWidth(),
                if (state.draft.allocationMode == CreditAllocationMode.EARLIEST_UNPAID) LedgerButtonVariant.PRIMARY else LedgerButtonVariant.SECONDARY,
            )
            account.statements.filter { it.remainingAmountMinor > 0L }.take(3).forEach { statement ->
                LedgerButton(
                    stringResource(R.string.credit_specific_statement, statement.dueDate.localized(locale)),
                    { actions.onSelectStatement(statement.id) },
                    Modifier.fillMaxWidth(),
                    if (state.draft.allocationMode == CreditAllocationMode.SPECIFIC && state.draft.selectedStatementId == statement.id) LedgerButtonVariant.PRIMARY else LedgerButtonVariant.TEXT,
                )
            }
            LedgerButton(
                stringResource(R.string.credit_unallocated_advance),
                actions.onSelectUnallocated,
                Modifier.fillMaxWidth(),
                if (state.draft.allocationMode == CreditAllocationMode.UNALLOCATED_ADVANCE) LedgerButtonVariant.PRIMARY else LedgerButtonVariant.TEXT,
            )
        }
    }
}

@Composable
private fun ValidationBanner(state: CreditFeatureState) {
    when (state.presentation) {
        CreditPresentation.OVERPAYMENT_BLOCKED -> LedgerBanner(stringResource(R.string.credit_overpayment_blocked), LedgerBannerVariant.DANGER)
        CreditPresentation.VALIDATION_ERROR, CreditPresentation.MISMATCH -> LedgerBanner(stringResource(R.string.credit_validation_error), LedgerBannerVariant.DANGER)
        CreditPresentation.UNALLOCATED -> LedgerBanner(stringResource(R.string.credit_unallocated_explanation), LedgerBannerVariant.INFO)
        CreditPresentation.BALANCED -> LedgerBanner(stringResource(R.string.credit_allocation_balanced), LedgerBannerVariant.INFO)
        else -> Unit
    }
}

@Composable
private fun EligibilityRow(label: String, met: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        LedgerText(label, LedgerTextRole.BODY)
        StatusBadge(if (met) stringResource(R.string.credit_met) else stringResource(R.string.credit_not_met), if (met) LedgerStatusVariant.POSITIVE else LedgerStatusVariant.WARNING)
    }
}

@Composable
private fun SelectionRow(label: String, onClick: () -> Unit) {
    LedgerButton(label, onClick, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY)
}

@Composable
private fun StatementBadge(status: CreditStatementStatus) {
    StatusBadge(
        statementStatus(status),
        when (status) {
            CreditStatementStatus.PAID -> LedgerStatusVariant.POSITIVE
            CreditStatementStatus.OVERDUE -> LedgerStatusVariant.DANGER
            CreditStatementStatus.PARTIALLY_PAID -> LedgerStatusVariant.WARNING
            CreditStatementStatus.SEALED -> LedgerStatusVariant.INFO
            else -> LedgerStatusVariant.NEUTRAL
        },
    )
}

@Composable
private fun statementStatus(status: CreditStatementStatus): String = when (status) {
    CreditStatementStatus.OPEN -> stringResource(R.string.credit_status_open)
    CreditStatementStatus.UNPAID -> stringResource(R.string.credit_status_unpaid)
    CreditStatementStatus.PARTIALLY_PAID -> stringResource(R.string.credit_status_partial)
    CreditStatementStatus.PAID -> stringResource(R.string.credit_status_paid)
    CreditStatementStatus.OVERDUE -> stringResource(R.string.credit_status_overdue)
    CreditStatementStatus.SEALED -> stringResource(R.string.credit_status_sealed)
}

@Composable
private fun accountStatus(state: CreditFeatureState): String = when (state.presentation) {
    CreditPresentation.OVERDUE -> stringResource(R.string.credit_status_overdue)
    CreditPresentation.POSITIVE_BALANCE -> stringResource(R.string.credit_positive_balance)
    CreditPresentation.NO_LIMIT -> stringResource(R.string.credit_no_limit)
    else -> stringResource(R.string.credit_status_normal)
}

private fun accountVariant(state: CreditFeatureState): LedgerStatusVariant = when (state.presentation) {
    CreditPresentation.OVERDUE -> LedgerStatusVariant.DANGER
    CreditPresentation.POSITIVE_BALANCE -> LedgerStatusVariant.INFO
    else -> LedgerStatusVariant.NEUTRAL
}

@Composable
private fun assignmentModeLabel(mode: StatementAssignmentMode): String = when (mode) {
    StatementAssignmentMode.PREVIOUS_CYCLE -> stringResource(R.string.credit_previous_cycle)
    StatementAssignmentMode.EXPLICIT_STATEMENT -> stringResource(R.string.credit_current_cycle)
    StatementAssignmentMode.NEXT_CYCLE -> stringResource(R.string.credit_next_cycle)
    StatementAssignmentMode.AUTOMATIC -> stringResource(R.string.credit_automatic_assignment)
}

private fun LocalDate.localized(locale: java.util.Locale): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(this)

private fun String.toLocalDateOrNull(): LocalDate? = runCatching { LocalDate.parse(this) }.getOrNull()

@Composable
private fun paymentAccountName(state: CreditFeatureState): String = state.snapshot.paymentAccounts.singleOrNull { it.id == state.draft.selectedPaymentAccountId }?.name
    ?: stringResource(R.string.credit_select_payment_account)

@Composable
private fun CreditNotFound(actions: CreditActions) {
    LedgerErrorState(UiErrorCode("CREDIT_NOT_FOUND"), stringResource(R.string.credit_not_found), actions.onRetry)
}

private fun Map<String, String>.stableId(key: String): StableId? = get(key)?.let { StableId.parse(it).getOrNull() }

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
