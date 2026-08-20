@file:Suppress(
    "ktlint:standard:function-naming",
    "LongMethod",
    "LongParameterList",
    "TooManyFunctions",
    "CyclomaticComplexMethod",
    "FunctionNaming",
    "MaxLineLength",
    "MagicNumber",
)

package app.ledger.feature.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.compose.collectAsLazyPagingItems
import app.ledger.core.common.CheckedArithmetic
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.core.designsystem.AccessibleDataTable
import app.ledger.core.designsystem.AccessibleTableUiModel
import app.ledger.core.designsystem.AccountSummaryCard
import app.ledger.core.designsystem.AccountSummaryUiModel
import app.ledger.core.designsystem.ChartCard
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerButton
import app.ledger.core.designsystem.LedgerButtonVariant
import app.ledger.core.designsystem.LedgerCard
import app.ledger.core.designsystem.LedgerChartSeries
import app.ledger.core.designsystem.LedgerChartType
import app.ledger.core.designsystem.LedgerChartUiModel
import app.ledger.core.designsystem.LedgerChoiceRow
import app.ledger.core.designsystem.LedgerChip
import app.ledger.core.designsystem.LedgerEmptyState
import app.ledger.core.designsystem.LedgerErrorState
import app.ledger.core.designsystem.LedgerIcon
import app.ledger.core.designsystem.LedgerLineChart
import app.ledger.core.designsystem.LedgerDatePickerFlow
import app.ledger.core.designsystem.LedgerLoadingState
import app.ledger.core.designsystem.LedgerReferenceDisplayDefaults
import app.ledger.core.designsystem.LedgerSaveFab
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextField
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.LedgerVicoLineRenderer
import app.ledger.core.designsystem.MetricCard
import app.ledger.core.designsystem.MetricCardVariant
import app.ledger.core.designsystem.MoneyExpressionField
import app.ledger.core.designsystem.ReferenceDisplayStyleIcons
import app.ledger.core.designsystem.ReferenceDisplayStylePicker
import app.ledger.core.designsystem.SelectorField
import app.ledger.core.designsystem.UiErrorCode
import app.ledger.core.money.AmountSemantic
import app.ledger.core.money.AmountVisibility
import app.ledger.core.money.CurrencyCode
import app.ledger.core.money.EvaluatedMoneyExpression
import app.ledger.core.money.JvmLegalTenderCurrencyCatalog
import app.ledger.core.money.LocaleCurrencyFormatter
import app.ledger.core.money.Money
import app.ledger.core.money.MoneyFormatRequest
import app.ledger.core.money.MoneyExpressionEvaluator
import app.ledger.core.money.MoneyUiModel
import app.ledger.finance.application.AccountReferenceView
import app.ledger.finance.application.AccountTransactionReferenceView
import app.ledger.finance.application.CardReferenceView
import app.ledger.finance.application.CheckpointReferenceView
import app.ledger.finance.application.ReferenceDataSnapshot
import app.ledger.finance.domain.CardType
import app.ledger.finance.domain.EntityStatus
import app.ledger.finance.domain.UserAccountType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
public fun AccountsDestination(
    screenId: String,
    encodedArguments: Map<String, String>,
    dataState: AccountsDataState,
    actions: AccountsActions,
    selectedAccountType: UserAccountType,
    preferredCardAccountId: StableId? = null,
    replacementCardId: StableId? = null,
    pending: Boolean,
    amountsVisible: Boolean = true,
    stateOverride: AccountsRequiredState? = null,
    modifier: Modifier = Modifier,
) {
    val screenId = uiState.screenId
    val encodedArguments = uiState.encodedArguments
    val dataState = uiState.dataState
    val selectedAccountType = uiState.selectedAccountType
    val preferredCardAccountId = uiState.preferredCardAccountId
    val pending = uiState.pending
    val stateOverride = uiState.stateOverride
    val actions = accountsActions(onAction)
    require(screenId in SUPPORTED_SCREENS)
    require(stateOverride == null || stateOverride.screenId == screenId)
    val snapshot = (dataState as? AccountsDataState.Content)?.snapshot
    val stateName = stateOverride?.contractName ?: actualState(screenId, dataState, snapshot, encodedArguments, pending)
    Column(
        modifier.fillMaxSize().testTag(LedgerTestTags.P12_ACCOUNTS_ROOT)
            .padding(vertical = LedgerTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
    ) {
        when {
            dataState is AccountsDataState.Loading && stateOverride == null -> LedgerLoadingState(Modifier.fillMaxSize())
            dataState is AccountsDataState.Error && stateOverride == null -> LedgerErrorState(
                UiErrorCode(dataState.code.sanitizeCode()),
                stringResource(R.string.accounts_load_failed),
                actions.onRetry,
                Modifier.fillMaxSize(),
            )
            screenId == "ACC-001" -> AccountHome(snapshot, stateName, actions, amountsVisible)
            screenId == "ACC-002" -> AccountTypePicker(actions)
            screenId == "ACC-003" -> AccountEditor(snapshot, encodedArguments.stableId("accountId"), selectedAccountType, stateName, actions)
            screenId == "ACC-004" -> OpeningBalance(snapshot, encodedArguments.requireStableId("accountId"), stateName, actions)
            screenId == "ACC-005" -> AccountDetail(snapshot, encodedArguments.requireStableId("accountId"), stateName, actions, amountsVisible)
            screenId == "ACC-006" -> AccountTransactions(snapshot, encodedArguments.requireStableId("accountId"), stateName, actions, amountsVisible)
            screenId == "ACC-007" -> CheckpointEditor(snapshot, encodedArguments.requireStableId("accountId"), stateName, actions)
            screenId == "ACC-008" -> CheckpointResolution(snapshot, encodedArguments.requireStableId("checkpointId"), actions)
            screenId == "ACC-009" -> CardList(snapshot, encodedArguments.requireStableId("accountId"), stateName, actions)
            screenId == "ACC-010" -> CardEditor(snapshot, encodedArguments.stableId("cardId"), preferredCardAccountId, replacementCardId, stateName, actions)
            screenId == "ACC-011" -> CardDetail(snapshot, encodedArguments.requireStableId("cardId"), stateName, actions)
            screenId == "ACC-012" -> ArchiveDelete(snapshot, encodedArguments.requireStableId("accountId"), stateName, actions)
        }
    }
}

@Composable
private fun AccountHome(snapshot: ReferenceDataSnapshot?, state: String, actions: AccountsActions, amountsVisible: Boolean) {
    if (state == "error") {
        LedgerErrorState(UiErrorCode("ACCOUNT_LIST_FAILED"), stringResource(R.string.accounts_load_failed), actions.onRetry)
        return
    }
    val accounts = snapshot?.accounts.orEmpty()
    if (state == "noAccounts" || accounts.isEmpty()) {
        LedgerEmptyState(
            stringResource(R.string.accounts_empty_title),
            stringResource(R.string.accounts_empty_body),
            stringResource(R.string.accounts_create),
            { actions.onNavigate("ACC-002", emptyMap()) },
            secondaryAction = stringResource(R.string.accounts_import),
            onSecondaryAction = { actions.onNavigate("IMP-001", emptyMap()) },
        )
        return
    }
    if (state == "valuationStale" || snapshot?.valuationMissing == true) {
        LedgerBanner(stringResource(R.string.accounts_valuation_unavailable), LedgerBannerVariant.WARNING)
    }
    MetricCard(
        title = stringResource(R.string.accounts_core_net_assets),
        value = snapshot?.coreNetFinancialAssetsMinor.money(snapshot?.baseCurrency?.value.orEmpty(), amountsVisible),
        variant = MetricCardVariant.EMPHASIZED,
        explanation = stringResource(
            R.string.accounts_adjusted_position_value,
            snapshot?.adjustedNetFinancialPositionMinor.displayMoney(snapshot?.baseCurrency?.value.orEmpty(), amountsVisible),
        ),
    )
    LazyColumn(verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
        UserAccountType.entries.forEach { type ->
            val grouped = accounts.filter { it.type == type }
            if (grouped.isNotEmpty()) {
                item { LedgerText(type.label(), LedgerTextRole.SECTION) }
                items(grouped, key = { it.id.toString() }) { account ->
                    AccountSummaryCard(account.toUi(snapshot.baseCurrency.value, amountsVisible), { actions.onNavigate("ACC-005", mapOf("accountId" to account.id)) }, Modifier.fillMaxWidth())
                }
            }
        }
        item { LedgerButton(stringResource(R.string.accounts_add), { actions.onNavigate("ACC-002", emptyMap()) }, Modifier.fillMaxWidth(), leadingIcon = LedgerIcon.ADD) }
    }
}

@Composable
private fun AccountTypePicker(actions: AccountsActions) {
    UserAccountType.entries.forEach { type ->
        LedgerCard(Modifier.fillMaxWidth(), onClick = { actions.onSelectAccountType(type) }) {
            Column(Modifier.padding(LedgerTheme.spacing.sm)) {
                LedgerText(type.label(), LedgerTextRole.SECTION)
                LedgerText(type.explanation(), LedgerTextRole.SUPPORTING)
            }
        }
    }
}

@Composable
private fun AccountEditor(
    snapshot: ReferenceDataSnapshot?,
    accountId: StableId?,
    selectedType: UserAccountType,
    state: String,
    actions: AccountsActions,
) {
    val existing = snapshot?.accounts?.singleOrNull { it.id == accountId }
    var name by remember(accountId) { mutableStateOf(existing?.name.orEmpty()) }
    var currency by remember(accountId, snapshot) { mutableStateOf(existing?.currency?.value ?: snapshot?.baseCurrency?.value.orEmpty()) }
    var institution by remember(accountId) { mutableStateOf(existing?.institutionName.orEmpty()) }
    var branch by remember(accountId) { mutableStateOf(existing?.branchName.orEmpty()) }
    var accountNumber by remember(accountId) { mutableStateOf(existing?.accountNumber.orEmpty()) }
    val supportedCurrencies = remember { JvmLegalTenderCurrencyCatalog.create().activeLegalTenderCurrencies().map { it.code }.sortedBy { it.value } }
    var selectedIcon by remember(accountId) {
        mutableStateOf(
            ReferenceDisplayStyleIcons.firstOrNull { it.name.equals(existing?.iconKey, ignoreCase = true) }
                ?: LedgerIcon.ACCOUNT,
        )
    }
    var selectedColor by remember(accountId) {
        mutableStateOf(existing?.colorArgb ?: LedgerReferenceDisplayDefaults.COLOR_ARGB)
    }
    var selectedPalette by remember(accountId) {
        mutableStateOf(LedgerReferenceDisplayDefaults.paletteId(selectedColor))
    }
    val currencyValid = supportedCurrencies.any { it.value == currency }
    val validation = state == "validationError" || name.isBlank() || !currencyValid
    if (state == "currencyLocked" || existing?.hasFinancialPostings == true) {
        LedgerBanner(stringResource(R.string.accounts_currency_locked), LedgerBannerVariant.INFO)
    }
    if (validation && state == "validationError") LedgerBanner(stringResource(R.string.accounts_validation), LedgerBannerVariant.DANGER)
    LedgerTextField(name, { name = it.take(MAX_NAME) }, stringResource(R.string.accounts_name), required = true, errorText = stringResource(R.string.accounts_required).takeIf { validation && name.isBlank() })
    SelectorField(
        stringResource(R.string.accounts_currency),
        currency.ifBlank { stringResource(R.string.accounts_currency_choose) },
        {
            val index = supportedCurrencies.indexOfFirst { it.value == currency }
            currency = supportedCurrencies[(index + 1).mod(supportedCurrencies.size)].value
        },
        supportingText = stringResource(R.string.accounts_currency_supported),
        enabled = existing?.hasFinancialPostings != true && supportedCurrencies.isNotEmpty(),
    )
    if (!currencyValid) LedgerText(stringResource(R.string.accounts_currency_invalid), LedgerTextRole.SUPPORTING)
    if ((existing?.type ?: selectedType) != UserAccountType.CASH) {
        LedgerTextField(institution, { institution = it.take(MAX_NAME) }, stringResource(R.string.accounts_institution))
        LedgerTextField(branch, { branch = it.take(MAX_NAME) }, stringResource(R.string.accounts_branch))
    }
    if ((existing?.type ?: selectedType) in setOf(UserAccountType.CASH, UserAccountType.BANK)) {
        LedgerTextField(accountNumber, { accountNumber = it.take(MAX_ACCOUNT_NUMBER) }, stringResource(R.string.accounts_account_number), sensitive = true)
    }
    LedgerBanner(stringResource(R.string.accounts_type_value, (existing?.type ?: selectedType).label()), LedgerBannerVariant.NEUTRAL)
    ReferenceDisplayStylePicker(
        selectedIcon = selectedIcon,
        selectedPaletteId = selectedPalette,
        iconSectionLabel = stringResource(R.string.accounts_appearance_icon),
        colorSectionLabel = stringResource(R.string.accounts_appearance_color),
        onIconSelected = { selectedIcon = it },
        onPaletteSelected = { palette, color ->
            selectedPalette = palette
            selectedColor = color
        },
    )
    LedgerSaveFab(
        onClick = {
            actions.onSaveAccount(
                AccountEditorSubmission(
                    accountId,
                    existing?.type ?: selectedType,
                    name.trim(),
                    currency,
                    institution.clean(),
                    branch.clean(),
                    accountNumber.clean(),
                    existing?.openedOn,
                    selectedIcon.name.lowercase(),
                    selectedColor,
                ),
            )
        },
        enabled = !validation && state != "saving",
        submitting = state == "saving",
    )
}

@Composable
private fun OpeningBalance(snapshot: ReferenceDataSnapshot?, accountId: StableId, state: String, actions: AccountsActions) {
    val account = snapshot?.accounts?.singleOrNull { it.id == accountId }
    val baseCurrency = snapshot?.baseCurrency
    var amount by remember(accountId) { mutableStateOf("") }
    var baseAmount by remember(accountId) { mutableStateOf("") }
    var date by remember(accountId) { mutableStateOf<LocalDate?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    val amountResult = evaluateExpression(amount, account?.currency)
    val baseResult = evaluateExpression(baseAmount, baseCurrency)
    LedgerBanner(stringResource(R.string.accounts_opening_not_statistics), LedgerBannerVariant.INFO)
    MoneyExpressionField(amount, amountResult?.expression?.normalized.orEmpty(), amountResult?.let { it.roundedMoney.minor.money(account?.currency?.value.orEmpty()) }, { amount = it.take(MAX_EXPRESSION_LENGTH) }, currencyCode = account?.currency?.value.orEmpty(), errorText = stringResource(R.string.accounts_amount_invalid).takeIf { amount.isNotBlank() && amountResult == null })
    if (account != null && baseCurrency != null && account.currency != baseCurrency) {
        MoneyExpressionField(baseAmount, baseResult?.expression?.normalized.orEmpty(), baseResult?.let { it.roundedMoney.minor.money(baseCurrency.value) }, { baseAmount = it.take(MAX_EXPRESSION_LENGTH) }, currencyCode = baseCurrency.value, errorText = stringResource(R.string.accounts_amount_invalid).takeIf { baseAmount.isNotBlank() && baseResult == null })
    }
    SelectorField(stringResource(R.string.accounts_date), date?.localizedDate() ?: stringResource(R.string.accounts_choose_date), { showDatePicker = true })
    LedgerSaveFab(
        onClick = {
            actions.onSaveOpeningBalance(OpeningBalanceSubmission(accountId, date ?: return@LedgerSaveFab, amountResult?.roundedMoney?.minor ?: return@LedgerSaveFab, baseResult?.roundedMoney?.minor))
        },
        enabled = amountResult != null && date != null && (account == null || account.currency == snapshot?.baseCurrency || baseResult != null) && state != "saving",
        submitting = state == "saving",
    )
    if (showDatePicker) {
        LedgerDatePickerFlow(
            (date ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            { dateMillis -> date = java.time.Instant.ofEpochMilli(dateMillis).atZone(ZoneOffset.UTC).toLocalDate(); showDatePicker = false },
            { showDatePicker = false },
        )
    }
}

@Composable
private fun AccountDetail(snapshot: ReferenceDataSnapshot?, accountId: StableId, state: String, actions: AccountsActions, amountsVisible: Boolean) {
    val account = snapshot?.accounts?.singleOrNull { it.id == accountId }
    if (account == null) {
        LedgerErrorState(UiErrorCode("ACCOUNT_NOT_FOUND"), stringResource(R.string.accounts_not_found), actions.onRetry)
        return
    }
    if (state == "archived" || account.status == EntityStatus.ARCHIVED) LedgerBanner(stringResource(R.string.accounts_archived), LedgerBannerVariant.NEUTRAL)
    if (state == "valuationUnavailable" || (account.currency != snapshot.baseCurrency && account.currentBaseValueMinor == null)) LedgerBanner(stringResource(R.string.accounts_valuation_unavailable), LedgerBannerVariant.WARNING)
    MetricCard(stringResource(R.string.accounts_balance), account.balanceMinor.money(account.currency.value, amountsVisible), variant = MetricCardVariant.EMPHASIZED)
    val reserved = snapshot.accountGoals.filter { it.accountId == accountId }.sumOf { it.balanceMinor }
    val available = CheckedArithmetic.subtract(account.balanceMinor, reserved).getOrNull()
    LedgerText(stringResource(R.string.accounts_available_balance), LedgerTextRole.SECTION)
    LedgerText(available.displayMoney(account.currency.value, amountsVisible), LedgerTextRole.BODY)
    account.currentBaseValueMinor?.let { valuation ->
        val locale = LocalLocale.current.platformLocale
        val valuationTime = account.valuationQuotedAt?.let {
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(LedgerTheme.timeZone).format(it)
        }
        MetricCard(
            stringResource(R.string.accounts_current_valuation),
            valuation.money(snapshot.baseCurrency.value, amountsVisible),
            explanation = valuationTime?.let { stringResource(R.string.accounts_valuation_updated, it) },
        )
    }
    val transactions = snapshot.accountTransactions.filter { it.accountId == accountId }.sortedBy { it.occurredAt }
    if (transactions.isNotEmpty() && amountsVisible) {
        var tableExpanded by remember(accountId) { mutableStateOf(false) }
        val trendRows = transactions.takeLast(TREND_POINT_LIMIT)
        val trendModel = LedgerChartUiModel(
            title = stringResource(R.string.accounts_balance_trend),
            scope = stringResource(R.string.accounts_balance_trend_scope),
            summary = stringResource(
                R.string.accounts_balance_trend_summary,
                trendRows.first().runningBalanceMinor.displayMoney(account.currency.value, true),
                trendRows.last().runningBalanceMinor.displayMoney(account.currency.value, true),
            ),
            type = LedgerChartType.LINE,
            series = listOf(
                LedgerChartSeries(
                    stableSeriesKey = account.id.toString(),
                    label = stringResource(R.string.accounts_balance),
                    values = trendRows.map { it.runningBalanceMinor.toDouble() },
                    pointLabels = trendRows.map { it.localDate.localizedDate() },
                    formattedValues = trendRows.map { it.runningBalanceMinor.displayMoney(it.currency.value, true) },
                ),
            ),
        )
        ChartCard(
            model = trendModel,
            chart = { LedgerLineChart(trendModel, LedgerVicoLineRenderer, Modifier.fillMaxWidth()) },
            dataTable = AccessibleTableUiModel(
                caption = stringResource(R.string.accounts_balance_trend),
                columnHeaders = listOf(stringResource(R.string.accounts_date), stringResource(R.string.accounts_running_balance)),
                rows = trendRows.map { listOf(it.localDate.localizedDate(), it.runningBalanceMinor.displayMoney(it.currency.value, true)) },
            ),
            tableExpanded = tableExpanded,
            onToggleTable = { tableExpanded = !tableExpanded },
        )
    } else if (transactions.isNotEmpty()) {
        LedgerBanner(stringResource(R.string.accounts_chart_values_hidden), LedgerBannerVariant.NEUTRAL)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
        LedgerButton(stringResource(R.string.accounts_transactions), { actions.onNavigate("ACC-006", mapOf("accountId" to account.id)) }, Modifier.weight(1f), variant = LedgerButtonVariant.SECONDARY)
        LedgerButton(stringResource(R.string.accounts_checkpoint), { actions.onNavigate("ACC-007", mapOf("accountId" to account.id)) }, Modifier.weight(1f), variant = LedgerButtonVariant.SECONDARY)
    }
    snapshot.checkpoints
        .asSequence()
        .filter { it.accountId == accountId }
        .maxByOrNull(CheckpointReferenceView::asOf)
        ?.let { checkpoint ->
            LedgerText(stringResource(R.string.accounts_latest_checkpoint), LedgerTextRole.SECTION)
            LedgerText(stringResource(R.string.accounts_checkpoint_date, checkpoint.asOfLocalDate.toString()), LedgerTextRole.SUPPORTING)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                MetricCard(stringResource(R.string.accounts_observed_value), checkpoint.observedMinor.money(account.currency.value), Modifier.weight(1f))
                MetricCard(stringResource(R.string.accounts_book_balance), checkpoint.calculatedMinor.money(account.currency.value), Modifier.weight(1f))
            }
            MetricCard(stringResource(R.string.accounts_difference), checkpoint.differenceMinor.money(account.currency.value))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                LedgerButton(
                    stringResource(R.string.accounts_find_missing),
                    { actions.onNavigate("ACC-006", mapOf("accountId" to account.id)) },
                    Modifier.weight(1f),
                    variant = LedgerButtonVariant.SECONDARY,
                )
                LedgerButton(
                    if (checkpoint.adjustmentTransactionId == null) stringResource(R.string.accounts_create_adjustment) else stringResource(R.string.accounts_adjustment_created),
                    { actions.onNavigate("REC-020", mapOf("accountId" to account.id)) },
                    Modifier.weight(1f),
                    enabled = checkpoint.adjustmentTransactionId == null,
                )
            }
        }
    if (!account.hasFinancialPostings) {
        LedgerButton(
            stringResource(R.string.accounts_add_opening_balance),
            { actions.onNavigate("REC-022", mapOf("accountId" to account.id)) },
            Modifier.fillMaxWidth(),
            variant = LedgerButtonVariant.SECONDARY,
        )
    }
    LedgerButton(stringResource(R.string.accounts_cards), { actions.onNavigate("ACC-009", mapOf("accountId" to account.id)) }, Modifier.fillMaxWidth(), variant = LedgerButtonVariant.SECONDARY)
    val cards = snapshot.cards.filter { it.accountId == accountId }
    LedgerText(stringResource(R.string.accounts_cards_section, cards.size), LedgerTextRole.SECTION)
    cards.take(RECENT_CARD_LIMIT).forEach { card ->
        LedgerText(card.displayName, LedgerTextRole.BODY)
    }
    val goals = snapshot.accountGoals.filter { it.accountId == accountId }
    LedgerText(stringResource(R.string.accounts_goals_section), LedgerTextRole.SECTION)
    if (goals.isEmpty()) {
        LedgerText(stringResource(R.string.accounts_goals_empty), LedgerTextRole.SUPPORTING)
    } else {
        goals.forEach { goal ->
            MetricCard(
                title = goal.name,
                value = goal.balanceMinor.money(goal.currency.value, amountsVisible),
                comparison = stringResource(R.string.accounts_goal_target, goal.targetMinor.displayMoney(goal.currency.value, amountsVisible)),
            )
        }
    }
    if (transactions.isNotEmpty()) {
        LedgerText(stringResource(R.string.accounts_recent_transactions), LedgerTextRole.SECTION)
        AccessibleDataTable(
            AccessibleTableUiModel(
                caption = stringResource(R.string.accounts_recent_transactions),
                columnHeaders = listOf(stringResource(R.string.accounts_date), stringResource(R.string.accounts_amount)),
                rows = transactions.takeLast(RECENT_TRANSACTION_LIMIT).reversed().map {
                    listOf(it.localDate.localizedDate(), it.impactMinor.displayMoney(it.currency.value, amountsVisible))
                },
            ),
        )
    } else {
        LedgerEmptyState(
            stringResource(R.string.accounts_no_transactions),
            stringResource(R.string.accounts_no_transactions_body),
            stringResource(R.string.accounts_record),
            { actions.onNavigate("REC-001", emptyMap()) },
        )
    }
    LedgerButton(stringResource(R.string.accounts_edit), { actions.onNavigate("ACC-003", mapOf("accountId" to account.id)) }, Modifier.fillMaxWidth(), variant = LedgerButtonVariant.TEXT)
    LedgerButton(stringResource(R.string.accounts_archive_delete), { actions.onNavigate("ACC-012", mapOf("accountId" to account.id)) }, Modifier.fillMaxWidth(), variant = LedgerButtonVariant.TEXT)
}

@Composable
private fun AccountTransactions(snapshot: ReferenceDataSnapshot?, accountId: StableId, state: String, actions: AccountsActions, amountsVisible: Boolean) {
    val allTransactions = snapshot?.accountTransactions.orEmpty()
    val transactions = remember(allTransactions, accountId) {
        allTransactions.filter { it.accountId == accountId }.sortedByDescending { it.occurredAt }
    }
    if (state == "error") {
        LedgerErrorState(UiErrorCode("ACCOUNT_TRANSACTIONS_FAILED"), stringResource(R.string.accounts_load_failed), actions.onRetry)
    } else if (state == "empty" || transactions.isEmpty()) {
        LedgerEmptyState(
            stringResource(R.string.accounts_no_transactions),
            stringResource(R.string.accounts_no_transactions_body),
            stringResource(R.string.accounts_record),
            { actions.onNavigate("REC-001", emptyMap()) },
        )
    } else {
        LedgerBanner(stringResource(R.string.accounts_running_balance_explanation), LedgerBannerVariant.INFO)
        var kind by remember(accountId) { mutableStateOf<app.ledger.finance.domain.TransactionKind?>(null) }
        val filtered = remember(transactions, kind) { transactions.filter { kind == null || it.kind == kind } }
        val pages = remember(filtered) {
            Pager(PagingConfig(pageSize = ACCOUNT_TRANSACTION_PAGE_SIZE, initialLoadSize = ACCOUNT_TRANSACTION_PAGE_SIZE)) {
                AccountTransactionPagingSource(filtered)
            }.flow
        }
        val paged = pages.collectAsLazyPagingItems()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            LedgerChip(stringResource(R.string.accounts_filter_all), { kind = null }, selected = kind == null)
            LedgerChip(stringResource(R.string.accounts_filter_expense), { kind = app.ledger.finance.domain.TransactionKind.EXPENSE }, selected = kind == app.ledger.finance.domain.TransactionKind.EXPENSE)
            LedgerChip(stringResource(R.string.accounts_filter_income), { kind = app.ledger.finance.domain.TransactionKind.INCOME }, selected = kind == app.ledger.finance.domain.TransactionKind.INCOME)
        }
        val compact = LocalConfiguration.current.screenWidthDp < ACCOUNT_COMPACT_WIDTH_DP
        LazyColumn(verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xxs)) {
            if (paged.loadState.refresh is LoadState.Loading) {
                item { LedgerLoadingState(Modifier.fillMaxWidth()) }
            }
            if (paged.loadState.refresh is LoadState.Error) {
                item { LedgerErrorState(UiErrorCode("ACCOUNT_TRANSACTIONS_PAGE_FAILED"), stringResource(R.string.accounts_load_failed), paged::retry) }
            }
            items(paged.itemCount, key = { index -> paged.peek(index)?.transactionId?.toString() ?: "account-transaction-placeholder-$index" }) { index ->
                paged[index]?.let { transaction ->
                    LedgerCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                LedgerText(transaction.kind.accountTransactionLabel(), LedgerTextRole.BODY)
                                LedgerText(transaction.localDate.localizedDate(), LedgerTextRole.SUPPORTING)
                            }
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                LedgerText(transaction.impactMinor.displayMoney(transaction.currency.value, amountsVisible), LedgerTextRole.BODY)
                                if (compact) LedgerText(stringResource(R.string.accounts_running_value, transaction.runningBalanceMinor.displayMoney(transaction.currency.value, amountsVisible)), LedgerTextRole.SUPPORTING)
                            }
                            if (!compact) LedgerText(transaction.runningBalanceMinor.displayMoney(transaction.currency.value, amountsVisible), LedgerTextRole.BODY)
                        }
                    }
                }
            }
            if (paged.loadState.append is LoadState.Loading) {
                item { LedgerLoadingState(Modifier.fillMaxWidth()) }
            }
            if (paged.loadState.append is LoadState.Error) {
                item { LedgerErrorState(UiErrorCode("ACCOUNT_TRANSACTIONS_APPEND_FAILED"), stringResource(R.string.accounts_load_failed), paged::retry) }
            }
        }
    }
}

@Composable
private fun CheckpointEditor(snapshot: ReferenceDataSnapshot?, accountId: StableId, state: String, actions: AccountsActions) {
    val account = snapshot?.accounts?.singleOrNull { it.id == accountId }
    var observed by remember(accountId) { mutableStateOf("") }
    var selectedDate by remember(accountId) { mutableStateOf<LocalDate?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    val observedResult = evaluateExpression(observed, account?.currency)
    val observedMinor = observedResult?.roundedMoney?.minor
    val calculated = if (selectedDate == null) {
        account?.balanceMinor ?: 0L
    } else {
        snapshot?.accountTransactions.orEmpty()
            .asSequence()
            .filter { it.accountId == accountId && it.localDate <= selectedDate }
            .maxByOrNull { it.occurredAt }
            ?.runningBalanceMinor ?: 0L
    }
    val difference = observedMinor?.let { CheckedArithmetic.subtract(it, calculated).getOrNull() }
    SelectorField(stringResource(R.string.accounts_date), selectedDate?.localizedDate() ?: stringResource(R.string.accounts_choose_date), { showDatePicker = true })
    MoneyExpressionField(observed, observedResult?.expression?.normalized.orEmpty(), observedResult?.let { it.roundedMoney.minor.money(account?.currency?.value.orEmpty()) }, { observed = it.take(MAX_EXPRESSION_LENGTH) }, currencyCode = account?.currency?.value.orEmpty(), errorText = stringResource(R.string.accounts_amount_invalid).takeIf { observed.isNotBlank() && observedResult == null })
    MetricCard(stringResource(R.string.accounts_book_balance), calculated.money(account?.currency?.value.orEmpty()))
    if (state == "match" || difference == 0L) LedgerBanner(stringResource(R.string.accounts_checkpoint_match), LedgerBannerVariant.INFO)
    if (state == "difference" || difference?.let { it != 0L } == true) MetricCard(stringResource(R.string.accounts_difference), (difference ?: 1L).money(account?.currency?.value.orEmpty()))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
        LedgerButton(stringResource(R.string.accounts_find_missing), { actions.onNavigate("ACC-006", mapOf("accountId" to accountId)) }, Modifier.weight(1f), LedgerButtonVariant.SECONDARY)
        LedgerButton(stringResource(R.string.accounts_create_adjustment), { actions.onNavigate("REC-020", mapOf("accountId" to accountId)) }, Modifier.weight(1f), LedgerButtonVariant.SECONDARY)
    }
    LedgerButton(
        stringResource(R.string.accounts_save_checkpoint_only),
        onClick = {
            actions.onSaveCheckpoint(CheckpointSubmission(accountId, selectedDate ?: return@LedgerButton, observedMinor ?: return@LedgerButton, null))
        },
        Modifier.fillMaxWidth(),
        enabled = observedMinor != null && selectedDate != null && state != "saving",
    )
    if (state == "saving") LedgerLoadingState(label = stringResource(R.string.accounts_saving_checkpoint))
    if (showDatePicker) {
        LedgerDatePickerFlow(
            (selectedDate ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            { dateMillis -> selectedDate = java.time.Instant.ofEpochMilli(dateMillis).atZone(ZoneOffset.UTC).toLocalDate(); showDatePicker = false },
            { showDatePicker = false },
        )
    }
}

@Composable
private fun CheckpointResolution(snapshot: ReferenceDataSnapshot?, checkpointId: StableId, actions: AccountsActions) {
    val checkpoint = snapshot?.checkpoints?.singleOrNull { it.id == checkpointId }
    if (checkpoint == null) {
        LedgerErrorState(UiErrorCode("CHECKPOINT_NOT_FOUND"), stringResource(R.string.accounts_checkpoint_not_found), actions.onRetry)
        return
    }
    val account = snapshot.accounts.singleOrNull { it.id == checkpoint.accountId }
    val currency = account?.currency?.value ?: snapshot.baseCurrency.value
    LedgerBanner(stringResource(R.string.accounts_checkpoint_saved), LedgerBannerVariant.INFO)
    LedgerBanner(stringResource(R.string.accounts_checkpoint_does_not_change), LedgerBannerVariant.INFO)
    LedgerText(stringResource(R.string.accounts_checkpoint_date, checkpoint.asOfLocalDate.toString()), LedgerTextRole.SUPPORTING)
    MetricCard(stringResource(R.string.accounts_observed_value), checkpoint.observedMinor.money(currency))
    MetricCard(stringResource(R.string.accounts_book_balance), checkpoint.calculatedMinor.money(currency))
    MetricCard(stringResource(R.string.accounts_difference), checkpoint.differenceMinor.money(currency))
    LedgerButton(stringResource(R.string.accounts_find_missing), { actions.onNavigate("ACC-006", mapOf("accountId" to checkpoint.accountId)) }, Modifier.fillMaxWidth())
    LedgerButton(
        if (checkpoint.adjustmentTransactionId == null) stringResource(R.string.accounts_create_adjustment) else stringResource(R.string.accounts_adjustment_created),
        { actions.onNavigate("REC-020", mapOf("accountId" to checkpoint.accountId)) },
        Modifier.fillMaxWidth(),
        variant = LedgerButtonVariant.SECONDARY,
        enabled = checkpoint.adjustmentTransactionId == null,
    )
}

@Composable
private fun CardList(snapshot: ReferenceDataSnapshot?, accountId: StableId, state: String, actions: AccountsActions) {
    val cards = snapshot?.cards.orEmpty().filter { it.accountId == accountId }
    if (state == "empty" || cards.isEmpty()) {
        LedgerEmptyState(stringResource(R.string.accounts_cards_empty), stringResource(R.string.accounts_cards_empty_body), stringResource(R.string.accounts_card_add), { actions.onNavigate("ACC-010", emptyMap()) })
        return
    }
    val active = cards.filter { it.status == EntityStatus.ACTIVE }
    val archived = cards.filter { it.status == EntityStatus.ARCHIVED }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
        if (active.isNotEmpty()) item { LedgerText(stringResource(R.string.accounts_active_cards), LedgerTextRole.SECTION) }
        items(active, key = { it.id.toString() }) { card -> CardRow(card) { actions.onNavigate("ACC-011", mapOf("cardId" to card.id)) } }
        if (archived.isNotEmpty()) item { LedgerText(stringResource(R.string.accounts_archived_cards), LedgerTextRole.SECTION) }
        items(archived, key = { "archived_${it.id}" }) { card -> CardRow(card) { actions.onNavigate("ACC-011", mapOf("cardId" to card.id)) } }
        item { LedgerButton(stringResource(R.string.accounts_card_add), { actions.onNavigate("ACC-010", emptyMap()) }, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun CardRow(card: CardReferenceView, onClick: () -> Unit) {
    LedgerCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.padding(LedgerTheme.spacing.sm)) {
            LedgerText(card.displayName, LedgerTextRole.SECTION)
            LedgerText(card.lastFour?.let { stringResource(R.string.accounts_card_tail, it) } ?: stringResource(R.string.accounts_card_no_tail), LedgerTextRole.SUPPORTING)
            if (card.status == EntityStatus.ARCHIVED) LedgerText(stringResource(R.string.accounts_archived), LedgerTextRole.LABEL)
        }
    }
}

@Composable
private fun CardEditor(snapshot: ReferenceDataSnapshot?, cardId: StableId?, preferredAccountId: StableId?, replacementCardId: StableId?, state: String, actions: AccountsActions) {
    val existing = snapshot?.cards?.singleOrNull { it.id == cardId }
    val compatible = snapshot?.accounts.orEmpty().filter { it.status == EntityStatus.ACTIVE && it.type in setOf(UserAccountType.BANK, UserAccountType.CREDIT) }
    var selectedAccount by remember(cardId, preferredAccountId) {
        mutableStateOf(existing?.accountId ?: preferredAccountId?.takeIf { candidate -> compatible.any { it.id == candidate } } ?: compatible.firstOrNull()?.id)
    }
    var name by remember(cardId) { mutableStateOf(existing?.displayName.orEmpty()) }
    var lastFour by remember(cardId) { mutableStateOf(existing?.lastFour.orEmpty()) }
    var type by remember(cardId) { mutableStateOf(existing?.type ?: compatible.firstOrNull()?.let { if (it.type == UserAccountType.BANK) CardType.DEBIT else CardType.CREDIT_PRIMARY } ?: CardType.DEBIT) }
    if (compatible.isEmpty()) LedgerBanner(stringResource(R.string.accounts_card_no_compatible), LedgerBannerVariant.WARNING)
    compatible.forEach { account ->
        LedgerChoiceRow(account.name, selectedAccount == account.id, {
            selectedAccount = account.id
            type = if (account.type == UserAccountType.BANK) CardType.DEBIT else CardType.CREDIT_PRIMARY
        }, supportingText = account.type.label())
    }
    LedgerTextField(name, { name = it.take(MAX_NAME) }, stringResource(R.string.accounts_card_name), required = true)
    LedgerTextField(lastFour, { lastFour = it.filter(Char::isDigit).take(CARD_TAIL_LENGTH) }, stringResource(R.string.accounts_card_last_four), errorText = stringResource(R.string.accounts_card_tail_invalid).takeIf { state == "validationError" || lastFour.isNotEmpty() && lastFour.length != CARD_TAIL_LENGTH })
    LedgerBanner(stringResource(R.string.accounts_vault_later), LedgerBannerVariant.INFO)
    LedgerButton(stringResource(R.string.accounts_vault_setup), { actions.onNavigate("VLT-001", emptyMap()) }, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY)
    LedgerSaveFab(
        onClick = { selectedAccount?.let { actions.onSaveCard(CardEditorSubmission(cardId, it, type, name.trim(), lastFour.clean(), existing?.replacementOfId ?: replacementCardId)) } },
        enabled = selectedAccount != null && name.isNotBlank() && (lastFour.isBlank() || lastFour.length == CARD_TAIL_LENGTH) && state != "saving",
        submitting = state == "saving",
    )
}

@Composable
private fun CardDetail(snapshot: ReferenceDataSnapshot?, cardId: StableId, state: String, actions: AccountsActions) {
    val card = snapshot?.cards?.singleOrNull { it.id == cardId }
    if (card == null) {
        LedgerErrorState(UiErrorCode("CARD_NOT_FOUND"), stringResource(R.string.accounts_card_not_found), actions.onRetry)
        return
    }
    CardRow(card) { }
    val account = snapshot.accounts.singleOrNull { it.id == card.accountId }
    SelectorField(stringResource(R.string.accounts_linked_account), account?.name.orEmpty(), { account?.let { actions.onNavigate("ACC-005", mapOf("accountId" to it.id)) } })
    if (state == "replacement" || card.replacementOfId != null) LedgerBanner(stringResource(R.string.accounts_replacement_relation), LedgerBannerVariant.INFO)
    if (state == "archived" || card.status == EntityStatus.ARCHIVED) LedgerBanner(stringResource(R.string.accounts_archived), LedgerBannerVariant.NEUTRAL)
    LedgerButton(stringResource(R.string.accounts_vault_entry), { actions.onNavigate("VLT-001", emptyMap()) }, Modifier.fillMaxWidth(), variant = LedgerButtonVariant.SECONDARY)
    LedgerText(stringResource(R.string.accounts_card_history, card.historicalTransactionCount), LedgerTextRole.BODY)
    if (card.status == EntityStatus.ACTIVE) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            LedgerButton(stringResource(R.string.accounts_replace_card), { actions.onCreateReplacementCard(card.id, card.accountId) }, Modifier.weight(1f), LedgerButtonVariant.SECONDARY)
            LedgerButton(stringResource(R.string.accounts_archive_card), { actions.onArchiveCard(card.id, card.rowVersion) }, Modifier.weight(1f), variant = LedgerButtonVariant.DANGER)
        }
    }
}

@Composable
private fun ArchiveDelete(snapshot: ReferenceDataSnapshot?, accountId: StableId, state: String, actions: AccountsActions) {
    val account = snapshot?.accounts?.singleOrNull { it.id == accountId }
    if (account == null) return
    val activeCount = snapshot.accounts.count { it.status == EntityStatus.ACTIVE }
    val last = state == "lastAccountWarning" || activeCount == 1
    val used = state == "usedArchiveOnly" || account.hasFinancialPostings || account.cardCount > 0
    if (last) LedgerBanner(stringResource(R.string.accounts_last_warning), LedgerBannerVariant.WARNING)
    LedgerText(stringResource(R.string.accounts_usage, account.cardCount, if (account.hasFinancialPostings) 1 else 0), LedgerTextRole.BODY)
    LedgerButton(stringResource(R.string.accounts_archive), { actions.onArchiveAccount(account.id, account.rowVersion) }, Modifier.fillMaxWidth(), variant = LedgerButtonVariant.DANGER)
    LedgerButton(stringResource(R.string.accounts_delete_permanently), { actions.onDeleteEmptyAccount(account.id, account.rowVersion) }, Modifier.fillMaxWidth(), variant = LedgerButtonVariant.DANGER, enabled = !used)
    if (used) LedgerText(stringResource(R.string.accounts_used_archive_only), LedgerTextRole.SUPPORTING)
}

private fun actualState(
    screenId: String,
    dataState: AccountsDataState,
    snapshot: ReferenceDataSnapshot?,
    args: Map<String, String>,
    pending: Boolean,
): String = when (screenId) {
    "ACC-001" -> when {
        dataState is AccountsDataState.Error -> "error"
        snapshot?.accounts.isNullOrEmpty() -> "noAccounts"
        snapshot.valuationMissing -> "valuationStale"
        else -> "content"
    }
    "ACC-002" -> "content"
    "ACC-003" -> if (pending) {
        "saving"
    } else if (args.containsKey("accountId")) {
        "edit"
    } else {
        "create"
    }
    "ACC-004" -> if (pending) "saving" else "editing"
    "ACC-005" -> snapshot?.accounts?.singleOrNull { it.id == args.stableId("accountId") }?.let { account ->
        when {
            account.status == EntityStatus.ARCHIVED -> "archived"
            account.currency != snapshot.baseCurrency && account.currentBaseValueMinor == null -> "valuationUnavailable"
            snapshot.accountTransactions.none { it.accountId == account.id } -> "emptyTransactions"
            else -> "active"
        }
    } ?: "active"
    "ACC-006" -> if (snapshot?.accountTransactions.orEmpty().none { it.accountId == args.stableId("accountId") }) "empty" else "content"
    "ACC-007" -> if (pending) "saving" else "editing"
    "ACC-008" -> "content"
    "ACC-009" -> if (snapshot?.cards.orEmpty().none { it.accountId == args.stableId("accountId") }) "empty" else "content"
    "ACC-010" -> if (pending) {
        "saving"
    } else if (args.containsKey("cardId")) {
        "edit"
    } else {
        "create"
    }
    "ACC-011" -> snapshot?.cards?.singleOrNull { it.id == args.stableId("cardId") }?.let {
        when {
            it.status == EntityStatus.ARCHIVED -> "archived"
            it.replacementOfId != null -> "replacement"
            else -> "active"
        }
    } ?: "active"
    "ACC-012" -> snapshot?.accounts?.singleOrNull { it.id == args.stableId("accountId") }?.let { account ->
        when {
            snapshot.accounts.count { it.status == EntityStatus.ACTIVE } == 1 -> "lastAccountWarning"
            account.hasFinancialPostings || account.cardCount > 0 -> "usedArchiveOnly"
            else -> "unusedDeletable"
        }
    } ?: "unusedDeletable"
    else -> error("unsupported P12 account screen")
}

@Composable
private fun AccountReferenceView.toUi(baseCurrency: String, amountsVisible: Boolean): AccountSummaryUiModel = AccountSummaryUiModel(
    stableKey = "account_item",
    name = name,
    typeLabel = type.label(),
    balance = balanceMinor.money(currency.value, amountsVisible),
    secondaryValue = currentBaseValueMinor?.displayMoney(baseCurrency, amountsVisible),
    status = if (status == EntityStatus.ARCHIVED) stringResource(R.string.accounts_archived) else null,
    archived = status == EntityStatus.ARCHIVED,
    icon = LedgerIcon.entries.firstOrNull { it.name.equals(iconKey, ignoreCase = true) } ?: LedgerIcon.ACCOUNT,
    paletteId = LedgerReferenceDisplayDefaults.paletteId(colorArgb),
)

@Composable
private fun Long?.money(currency: String, visible: Boolean = true): MoneyUiModel {
    val locale = LocalLocale.current.platformLocale
    val code = CurrencyCode.parse(currency).getOrNull()
    if (this == null || code == null) return MoneyUiModel("—", "—", AmountSemantic.NEUTRAL, if (visible) AmountVisibility.VISIBLE else AmountVisibility.HIDDEN)
    val request = MoneyFormatRequest(Money(this, code), locale, AmountSemantic.NEUTRAL, if (visible) AmountVisibility.VISIBLE else AmountVisibility.HIDDEN)
    return when (val result = ACCOUNT_MONEY_FORMATTER.format(request)) {
        is app.ledger.core.common.DomainResult.Success -> result.value
        is app.ledger.core.common.DomainResult.Failure -> MoneyUiModel("—", "—", AmountSemantic.NEUTRAL, if (visible) AmountVisibility.VISIBLE else AmountVisibility.HIDDEN)
    }
}

@Composable
private fun Long?.displayMoney(currency: String, visible: Boolean = true): String = money(currency, visible).formatted

@Composable
private fun LocalDate.localizedDate(): String = format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(LocalLocale.current.platformLocale))

@Composable
private fun app.ledger.finance.domain.TransactionKind.accountTransactionLabel(): String = stringResource(
    when (this) {
        app.ledger.finance.domain.TransactionKind.EXPENSE -> R.string.accounts_filter_expense
        app.ledger.finance.domain.TransactionKind.INCOME -> R.string.accounts_filter_income
        app.ledger.finance.domain.TransactionKind.TRANSFER -> R.string.accounts_kind_transfer
        app.ledger.finance.domain.TransactionKind.REFUND -> R.string.accounts_kind_refund
        app.ledger.finance.domain.TransactionKind.CREDIT_PAYMENT -> R.string.accounts_kind_credit_payment
        app.ledger.finance.domain.TransactionKind.LOAN_DISBURSEMENT -> R.string.accounts_kind_loan_disbursement
        app.ledger.finance.domain.TransactionKind.LOAN_PAYMENT -> R.string.accounts_kind_loan_payment
        app.ledger.finance.domain.TransactionKind.BALANCE_ADJUSTMENT -> R.string.accounts_kind_balance_adjustment
        app.ledger.finance.domain.TransactionKind.FX_EXCHANGE -> R.string.accounts_kind_fx
        app.ledger.finance.domain.TransactionKind.SETTLEMENT_PAYMENT -> R.string.accounts_kind_settlement
        app.ledger.finance.domain.TransactionKind.OPENING_BALANCE -> R.string.accounts_kind_opening
    },
)

@Composable
private fun evaluateExpression(expression: String, currency: CurrencyCode?): EvaluatedMoneyExpression? {
    if (expression.isBlank() || currency == null) return null
    val locale = LocalLocale.current.platformLocale
    val metadata = ACCOUNT_CURRENCY_CATALOG.find(currency) ?: return null
    return when (val result = ACCOUNT_EXPRESSION_EVALUATOR.evaluate(expression, locale, metadata)) {
        is app.ledger.core.common.DomainResult.Success -> result.value
        is app.ledger.core.common.DomainResult.Failure -> null
    }
}
private fun String.clean(): String? = trim().takeIf(String::isNotEmpty)
private fun Map<String, String>.stableId(name: String): StableId? = get(name)?.let { StableId.parse(it).getOrNull() }
private fun Map<String, String>.requireStableId(name: String): StableId = requireNotNull(stableId(name))
private fun String.sanitizeCode(): String = uppercase().replace(Regex("[^A-Z0-9_]"), "_").take(48).let { if (it.firstOrNull()?.isLetter() == true) it else "REFERENCE_LOAD_FAILED" }

@Composable
private fun UserAccountType.label(): String = stringResource(
    when (this) {
        UserAccountType.CASH -> R.string.accounts_type_cash
        UserAccountType.BANK -> R.string.accounts_type_bank
        UserAccountType.CREDIT -> R.string.accounts_type_credit
        UserAccountType.LOAN -> R.string.accounts_type_loan
    },
)

@Composable
private fun UserAccountType.explanation(): String = stringResource(
    when (this) {
        UserAccountType.CASH -> R.string.accounts_type_cash_body
        UserAccountType.BANK -> R.string.accounts_type_bank_body
        UserAccountType.CREDIT -> R.string.accounts_type_credit_body
        UserAccountType.LOAN -> R.string.accounts_type_loan_body
    },
)

private val SUPPORTED_SCREENS = (1..12).map { "ACC-%03d".format(it) }.toSet()
private const val MAX_NAME = 80
private const val MAX_ACCOUNT_NUMBER = 80
private const val MAX_EXPRESSION_LENGTH = 128
private const val TREND_POINT_LIMIT = 30
private const val RECENT_TRANSACTION_LIMIT = 5
private const val RECENT_CARD_LIMIT = 3
private val CURRENCY_CATALOG = JvmLegalTenderCurrencyCatalog.create()
private const val CARD_TAIL_LENGTH = 4
private const val ACCOUNT_TRANSACTION_PAGE_SIZE = 40
private const val ACCOUNT_COMPACT_WIDTH_DP = 600

private class AccountTransactionPagingSource(
    private val transactions: List<AccountTransactionReferenceView>,
) : PagingSource<Int, AccountTransactionReferenceView>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, AccountTransactionReferenceView> {
        val start = params.key ?: 0
        val end = (start + params.loadSize).coerceAtMost(transactions.size)
        return LoadResult.Page(
            data = transactions.subList(start.coerceAtMost(end), end),
            prevKey = if (start == 0) null else (start - params.loadSize).coerceAtLeast(0),
            nextKey = end.takeIf { it < transactions.size },
        )
    }

    override fun getRefreshKey(state: PagingState<Int, AccountTransactionReferenceView>): Int? =
        state.anchorPosition?.let { anchor -> (anchor - state.config.initialLoadSize / 2).coerceAtLeast(0) }
}
private val ACCOUNT_MONEY_FORMATTER = LocaleCurrencyFormatter(JvmLegalTenderCurrencyCatalog.create())
private val ACCOUNT_CURRENCY_CATALOG = JvmLegalTenderCurrencyCatalog.create()
private val ACCOUNT_EXPRESSION_EVALUATOR = MoneyExpressionEvaluator()
