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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
import app.ledger.core.designsystem.LedgerEmptyState
import app.ledger.core.designsystem.LedgerErrorState
import app.ledger.core.designsystem.LedgerIcon
import app.ledger.core.designsystem.LedgerLineChart
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
import app.ledger.core.designsystem.ReferenceDisplayStyleIcons
import app.ledger.core.designsystem.ReferenceDisplayStylePicker
import app.ledger.core.designsystem.SelectorField
import app.ledger.core.designsystem.UiErrorCode
import app.ledger.core.money.AmountSemantic
import app.ledger.core.money.AmountVisibility
import app.ledger.core.money.MoneyUiModel
import app.ledger.finance.application.AccountReferenceView
import app.ledger.finance.application.CardReferenceView
import app.ledger.finance.application.CheckpointReferenceView
import app.ledger.finance.application.ReferenceDataSnapshot
import app.ledger.finance.domain.CardType
import app.ledger.finance.domain.EntityStatus
import app.ledger.finance.domain.UserAccountType
import java.time.LocalDate

@Composable
public fun AccountsDestination(
    uiState: AccountsScreenUiState,
    onAction: (AccountsScreenAction) -> Unit,
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
            screenId == "ACC-001" -> AccountHome(snapshot, stateName, actions)
            screenId == "ACC-002" -> AccountTypePicker(actions)
            screenId == "ACC-003" -> AccountEditor(snapshot, encodedArguments.stableId("accountId"), selectedAccountType, stateName, actions)
            screenId == "ACC-004" -> OpeningBalance(snapshot, encodedArguments.requireStableId("accountId"), stateName, actions)
            screenId == "ACC-005" -> AccountDetail(snapshot, encodedArguments.requireStableId("accountId"), stateName, actions)
            screenId == "ACC-006" -> AccountTransactions(snapshot, encodedArguments.requireStableId("accountId"), stateName, actions)
            screenId == "ACC-007" -> CheckpointEditor(snapshot, encodedArguments.requireStableId("accountId"), stateName, actions)
            screenId == "ACC-008" -> CheckpointResolution(snapshot, encodedArguments.requireStableId("checkpointId"), actions)
            screenId == "ACC-009" -> CardList(snapshot, encodedArguments.requireStableId("accountId"), stateName, actions)
            screenId == "ACC-010" -> CardEditor(snapshot, encodedArguments.stableId("cardId"), preferredCardAccountId, stateName, actions)
            screenId == "ACC-011" -> CardDetail(snapshot, encodedArguments.requireStableId("cardId"), stateName, actions)
            screenId == "ACC-012" -> ArchiveDelete(snapshot, encodedArguments.requireStableId("accountId"), stateName, actions)
        }
    }
}

@Composable
private fun AccountHome(snapshot: ReferenceDataSnapshot?, state: String, actions: AccountsActions) {
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
        value = snapshot?.coreNetFinancialAssetsMinor.money(snapshot?.baseCurrency?.value.orEmpty()),
        variant = MetricCardVariant.EMPHASIZED,
        explanation = stringResource(
            R.string.accounts_adjusted_position_value,
            snapshot?.adjustedNetFinancialPositionMinor.displayMinor(snapshot?.baseCurrency?.value.orEmpty()),
        ),
    )
    LazyColumn(verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
        UserAccountType.entries.forEach { type ->
            val grouped = accounts.filter { it.type == type }
            if (grouped.isNotEmpty()) {
                item { LedgerText(type.label(), LedgerTextRole.SECTION) }
                items(grouped, key = { it.id.toString() }) { account ->
                    AccountSummaryCard(account.toUi(), { actions.onNavigate("ACC-005", mapOf("accountId" to account.id)) }, Modifier.fillMaxWidth())
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
    val validation = state == "validationError" || name.isBlank() || currency.length != 3
    if (state == "currencyLocked" || existing?.hasFinancialPostings == true) {
        LedgerBanner(stringResource(R.string.accounts_currency_locked), LedgerBannerVariant.INFO)
    }
    if (validation && state == "validationError") LedgerBanner(stringResource(R.string.accounts_validation), LedgerBannerVariant.DANGER)
    LedgerTextField(name, { name = it.take(MAX_NAME) }, stringResource(R.string.accounts_name), required = true, errorText = stringResource(R.string.accounts_required).takeIf { validation && name.isBlank() })
    LedgerTextField(currency, { currency = it.uppercase().take(CURRENCY_LENGTH) }, stringResource(R.string.accounts_currency), required = true, enabled = existing?.hasFinancialPostings != true)
    if ((existing?.type ?: selectedType) != UserAccountType.CASH) {
        LedgerTextField(institution, { institution = it.take(MAX_NAME) }, stringResource(R.string.accounts_institution))
        LedgerTextField(branch, { branch = it.take(MAX_NAME) }, stringResource(R.string.accounts_branch))
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
                    null,
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
    var amount by remember(accountId) { mutableStateOf("") }
    var baseAmount by remember(accountId) { mutableStateOf("") }
    var date by remember(accountId) { mutableStateOf("") }
    LedgerBanner(stringResource(R.string.accounts_opening_not_statistics), LedgerBannerVariant.INFO)
    LedgerTextField(amount, { amount = it.filter(Char::isDigit).take(MAX_AMOUNT_DIGITS) }, stringResource(R.string.accounts_opening_amount, account?.currency?.value.orEmpty()), required = true)
    if (account != null && account.currency != snapshot.baseCurrency) {
        LedgerTextField(baseAmount, { baseAmount = it.filter(Char::isDigit).take(MAX_AMOUNT_DIGITS) }, stringResource(R.string.accounts_opening_base_amount, snapshot.baseCurrency.value), required = true)
    }
    LedgerTextField(date, { date = it.take(DATE_LENGTH) }, stringResource(R.string.accounts_date), required = true)
    LedgerSaveFab(
        onClick = {
            val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull() ?: return@LedgerSaveFab
            val parsedAmount = amount.toLongOrNull() ?: return@LedgerSaveFab
            actions.onSaveOpeningBalance(OpeningBalanceSubmission(accountId, parsedDate, parsedAmount, baseAmount.toLongOrNull()))
        },
        enabled = amount.toLongOrNull()?.let { it > 0L } == true && state != "saving",
        submitting = state == "saving",
    )
}

@Composable
private fun AccountDetail(snapshot: ReferenceDataSnapshot?, accountId: StableId, state: String, actions: AccountsActions) {
    val account = snapshot?.accounts?.singleOrNull { it.id == accountId }
    if (account == null) {
        LedgerErrorState(UiErrorCode("ACCOUNT_NOT_FOUND"), stringResource(R.string.accounts_not_found), actions.onRetry)
        return
    }
    if (state == "archived" || account.status == EntityStatus.ARCHIVED) LedgerBanner(stringResource(R.string.accounts_archived), LedgerBannerVariant.NEUTRAL)
    if (state == "valuationUnavailable" || (account.currency != snapshot.baseCurrency && account.currentBaseValueMinor == null)) LedgerBanner(stringResource(R.string.accounts_valuation_unavailable), LedgerBannerVariant.WARNING)
    MetricCard(stringResource(R.string.accounts_balance), account.balanceMinor.money(account.currency.value), variant = MetricCardVariant.EMPHASIZED)
    LedgerText(stringResource(R.string.accounts_available_balance), LedgerTextRole.SECTION)
    LedgerText(account.balanceMinor.displayMinor(account.currency.value), LedgerTextRole.BODY)
    val transactions = snapshot.accountTransactions.filter { it.accountId == accountId }.sortedBy { it.occurredAt }
    if (transactions.isNotEmpty()) {
        var tableExpanded by remember(accountId) { mutableStateOf(false) }
        val trendRows = transactions.takeLast(TREND_POINT_LIMIT)
        val trendModel = LedgerChartUiModel(
            title = stringResource(R.string.accounts_balance_trend),
            scope = stringResource(R.string.accounts_balance_trend_scope),
            summary = stringResource(
                R.string.accounts_balance_trend_summary,
                trendRows.first().runningBalanceMinor.displayMinor(account.currency.value),
                trendRows.last().runningBalanceMinor.displayMinor(account.currency.value),
            ),
            type = LedgerChartType.LINE,
            series = listOf(
                LedgerChartSeries(
                    stableSeriesKey = account.id.toString(),
                    label = stringResource(R.string.accounts_balance),
                    values = trendRows.map { it.runningBalanceMinor.toDouble() },
                    pointLabels = trendRows.map { it.localDate.toString() },
                ),
            ),
        )
        ChartCard(
            model = trendModel,
            chart = { LedgerLineChart(trendModel, LedgerVicoLineRenderer, Modifier.fillMaxWidth()) },
            dataTable = AccessibleTableUiModel(
                caption = stringResource(R.string.accounts_balance_trend),
                columnHeaders = listOf(stringResource(R.string.accounts_date), stringResource(R.string.accounts_running_balance)),
                rows = trendRows.map { listOf(it.localDate.toString(), it.runningBalanceMinor.displayMinor(it.currency.value)) },
            ),
            tableExpanded = tableExpanded,
            onToggleTable = { tableExpanded = !tableExpanded },
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
        LedgerButton(stringResource(R.string.accounts_transactions), { actions.onNavigate("ACC-006", mapOf("accountId" to account.id)) }, Modifier.weight(1f), variant = LedgerButtonVariant.SECONDARY)
        LedgerButton(stringResource(R.string.accounts_checkpoint), { actions.onNavigate("ACC-007", mapOf("accountId" to account.id)) }, Modifier.weight(1f), variant = LedgerButtonVariant.SECONDARY)
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
                value = goal.balanceMinor.money(goal.currency.value),
                comparison = stringResource(R.string.accounts_goal_target, goal.targetMinor.displayMinor(goal.currency.value)),
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
                    listOf(it.localDate.toString(), it.impactMinor.displayMinor(it.currency.value))
                },
            ),
        )
    }
    LedgerButton(stringResource(R.string.accounts_edit), { actions.onNavigate("ACC-003", mapOf("accountId" to account.id)) }, Modifier.fillMaxWidth(), variant = LedgerButtonVariant.TEXT)
    LedgerButton(stringResource(R.string.accounts_archive_delete), { actions.onNavigate("ACC-012", mapOf("accountId" to account.id)) }, Modifier.fillMaxWidth(), variant = LedgerButtonVariant.TEXT)
    if (state == "emptyTransactions") {
        LedgerEmptyState(
            stringResource(R.string.accounts_no_transactions),
            stringResource(R.string.accounts_no_transactions_body),
            stringResource(R.string.accounts_record),
            { actions.onNavigate("REC-001", emptyMap()) },
        )
    }
}

@Composable
private fun AccountTransactions(snapshot: ReferenceDataSnapshot?, accountId: StableId, state: String, actions: AccountsActions) {
    val account = snapshot?.accounts?.singleOrNull { it.id == accountId }
    val transactions = snapshot?.accountTransactions.orEmpty().filter { it.accountId == accountId }
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
        AccessibleDataTable(
            AccessibleTableUiModel(
                stringResource(R.string.accounts_transactions),
                listOf(stringResource(R.string.accounts_date), stringResource(R.string.accounts_amount), stringResource(R.string.accounts_running_balance)),
                transactions.map { transaction ->
                    listOf(
                        transaction.localDate.toString(),
                        transaction.impactMinor.displayMinor(transaction.currency.value),
                        transaction.runningBalanceMinor.displayMinor(transaction.currency.value),
                    )
                },
            ),
        )
    }
}

@Composable
private fun CheckpointEditor(snapshot: ReferenceDataSnapshot?, accountId: StableId, state: String, actions: AccountsActions) {
    val account = snapshot?.accounts?.singleOrNull { it.id == accountId }
    var observed by remember(accountId) { mutableStateOf("") }
    var date by remember(accountId) { mutableStateOf("") }
    val observedMinor = observed.toLongOrNull()
    val selectedDate = runCatching { LocalDate.parse(date) }.getOrNull()
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
    LedgerTextField(date, { date = it.take(DATE_LENGTH) }, stringResource(R.string.accounts_date), required = true)
    LedgerTextField(observed, { observed = it.filter { char -> char.isDigit() || char == '-' }.take(MAX_AMOUNT_DIGITS) }, stringResource(R.string.accounts_observed), required = true)
    MetricCard(stringResource(R.string.accounts_book_balance), calculated.money(account?.currency?.value.orEmpty()))
    if (state == "match" || difference == 0L) LedgerBanner(stringResource(R.string.accounts_checkpoint_match), LedgerBannerVariant.INFO)
    if (state == "difference" || difference?.let { it != 0L } == true) MetricCard(stringResource(R.string.accounts_difference), (difference ?: 1L).money(account?.currency?.value.orEmpty()))
    LedgerSaveFab(
        onClick = {
            val localDate = runCatching { LocalDate.parse(date) }.getOrNull() ?: return@LedgerSaveFab
            actions.onSaveCheckpoint(CheckpointSubmission(accountId, localDate, observedMinor ?: return@LedgerSaveFab, null))
        },
        enabled = observedMinor != null && state != "saving",
        submitting = state == "saving",
    )
}

@Composable
private fun CheckpointResolution(snapshot: ReferenceDataSnapshot?, checkpointId: StableId, actions: AccountsActions) {
    val checkpoint = snapshot?.checkpoints?.singleOrNull { it.id == checkpointId }
    if (checkpoint == null) {
        LedgerErrorState(UiErrorCode("CHECKPOINT_NOT_FOUND"), stringResource(R.string.accounts_checkpoint_not_found), actions.onRetry)
        return
    }
    LedgerBanner(stringResource(R.string.accounts_checkpoint_does_not_change), LedgerBannerVariant.INFO)
    MetricCard(stringResource(R.string.accounts_difference), checkpoint.differenceMinor.money(snapshot.baseCurrency.value))
    LedgerButton(stringResource(R.string.accounts_find_missing), { actions.onNavigate("ACC-006", mapOf("accountId" to checkpoint.accountId)) }, Modifier.fillMaxWidth())
    LedgerButton(stringResource(R.string.accounts_create_adjustment), { actions.onNavigate("REC-020", mapOf("accountId" to checkpoint.accountId)) }, Modifier.fillMaxWidth(), variant = LedgerButtonVariant.SECONDARY)
}

@Composable
private fun CardList(snapshot: ReferenceDataSnapshot?, accountId: StableId, state: String, actions: AccountsActions) {
    val cards = snapshot?.cards.orEmpty().filter { it.accountId == accountId }
    if (state == "empty" || cards.isEmpty()) {
        LedgerEmptyState(stringResource(R.string.accounts_cards_empty), stringResource(R.string.accounts_cards_empty_body), stringResource(R.string.accounts_card_add), { actions.onNavigate("ACC-010", emptyMap()) })
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
        items(cards, key = { it.id.toString() }) { card -> CardRow(card) { actions.onNavigate("ACC-011", mapOf("cardId" to card.id)) } }
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
private fun CardEditor(snapshot: ReferenceDataSnapshot?, cardId: StableId?, preferredAccountId: StableId?, state: String, actions: AccountsActions) {
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
    LedgerSaveFab(
        onClick = { selectedAccount?.let { actions.onSaveCard(CardEditorSubmission(cardId, it, type, name.trim(), lastFour.clean(), existing?.replacementOfId)) } },
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
    if (card.status == EntityStatus.ACTIVE) LedgerButton(stringResource(R.string.accounts_archive_card), { actions.onArchiveCard(card.id, card.rowVersion) }, Modifier.fillMaxWidth(), variant = LedgerButtonVariant.DANGER)
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
private fun AccountReferenceView.toUi(): AccountSummaryUiModel = AccountSummaryUiModel(
    stableKey = "account_item",
    name = name,
    typeLabel = type.label(),
    balance = balanceMinor.money(currency.value),
    secondaryValue = currentBaseValueMinor?.displayMinor(currency.value),
    status = if (status == EntityStatus.ARCHIVED) "archived" else null,
    archived = status == EntityStatus.ARCHIVED,
    icon = LedgerIcon.entries.firstOrNull { it.name.equals(iconKey, ignoreCase = true) } ?: LedgerIcon.ACCOUNT,
    paletteId = LedgerReferenceDisplayDefaults.paletteId(colorArgb),
)

private fun Long?.money(currency: String): MoneyUiModel = MoneyUiModel(
    formatted = displayMinor(currency),
    fullAccessibleText = displayMinor(currency),
    semantic = AmountSemantic.NEUTRAL,
    visibility = AmountVisibility.VISIBLE,
)

private fun Long?.displayMinor(currency: String): String = if (this == null) "—" else "$this ${currency.ifBlank { "—" }}"
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
private const val CURRENCY_LENGTH = 3
private const val MAX_AMOUNT_DIGITS = 19
private const val DATE_LENGTH = 10
private const val TREND_POINT_LIMIT = 30
private const val RECENT_TRANSACTION_LIMIT = 5
private const val RECENT_CARD_LIMIT = 3
private const val CARD_TAIL_LENGTH = 4
