@file:Suppress(
    "ktlint:standard:function-naming",
    "FunctionNaming",
    "LongMethod",
    "LongParameterList",
    "TooManyFunctions",
    "MagicNumber",
    "CyclomaticComplexMethod",
    "MaxLineLength",
)

package app.ledger.feature.journal

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import app.ledger.core.common.DomainResult
import app.ledger.core.designsystem.FilterBuilder
import app.ledger.core.designsystem.FilterChipUiModel
import app.ledger.core.designsystem.FilterDimensionUiModel
import app.ledger.core.designsystem.FormSection
import app.ledger.core.designsystem.HighRiskConfirmation
import app.ledger.core.designsystem.JournalTransactionRow
import app.ledger.core.designsystem.JournalTransactionUiModel
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerButton
import app.ledger.core.designsystem.LedgerButtonVariant
import app.ledger.core.designsystem.LedgerCard
import app.ledger.core.designsystem.LedgerChoiceRow
import app.ledger.core.designsystem.LedgerEmptyState
import app.ledger.core.designsystem.LedgerErrorState
import app.ledger.core.designsystem.LedgerIcon
import app.ledger.core.designsystem.LedgerLoadingState
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextField
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.SearchField
import app.ledger.core.designsystem.SelectorField
import app.ledger.core.designsystem.UiErrorCode
import app.ledger.core.money.AmountSemantic
import app.ledger.core.money.AmountVisibility
import app.ledger.core.money.JvmLegalTenderCurrencyCatalog
import app.ledger.core.money.LocaleCurrencyFormatter
import app.ledger.core.money.Money
import app.ledger.core.money.MoneyFormatRequest
import app.ledger.core.money.MoneyUiModel
import app.ledger.finance.application.JournalAccountCardUpdate
import app.ledger.finance.application.JournalBulkEditPatch
import app.ledger.finance.application.JournalBulkOption
import app.ledger.finance.application.JournalDetailView
import app.ledger.finance.application.JournalFieldUpdate
import app.ledger.finance.application.JournalRevisionView
import app.ledger.finance.application.JournalSelectionMode
import app.ledger.finance.application.JournalTransactionView
import app.ledger.finance.domain.CategoryId
import app.ledger.finance.domain.DependencyPolicy
import app.ledger.finance.domain.DependencyResolution
import app.ledger.finance.domain.MerchantId
import app.ledger.finance.domain.ParticipantId
import app.ledger.finance.domain.PaymentCardId
import app.ledger.finance.domain.ProjectId
import app.ledger.finance.domain.SettlementActivityId
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.TransactionAmountRange
import app.ledger.finance.domain.TransactionDependency
import app.ledger.finance.domain.TransactionDependencyType
import app.ledger.finance.domain.TransactionFilter
import app.ledger.finance.domain.TransactionId
import app.ledger.finance.domain.TransactionKind
import app.ledger.finance.domain.TransactionLifecycleState
import app.ledger.finance.domain.TransactionSource
import app.ledger.finance.domain.UserAccountId
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun JournalDestination(
    screenId: String,
    encodedArguments: Map<String, *>,
    state: JournalLoadState,
    pages: Flow<PagingData<JournalTransactionView>>,
    onAction: (JournalScreenAction) -> Unit,
) {
    val actions = journalActions(onAction)
    val content = state as? JournalLoadState.Content
    when {
        !journalArgumentsValid(screenId, encodedArguments) -> LedgerErrorState(
            UiErrorCode("JOURNAL_ARGUMENTS_INVALID"),
            stringResource(R.string.p15_journal_error),
            actions.onRetry,
        )
        state === JournalLoadState.Loading -> LedgerLoadingState(Modifier.fillMaxSize(), stringResource(R.string.p15_journal_loading))
        state is JournalLoadState.Failure -> LedgerErrorState(UiErrorCode("JOURNAL_QUERY_FAILED"), stringResource(R.string.p15_journal_error), actions.onRetry)
        content == null -> LedgerErrorState(UiErrorCode("JOURNAL_STATE_INVALID"), stringResource(R.string.p15_journal_error), actions.onRetry)
        screenId == "JRN-001" -> JournalListScreen(content, pages, actions)
        screenId == "JRN-002" -> JournalSearchScreen(content, pages, actions)
        screenId == "JRN-003" -> JournalFilterScreen(content, actions)
        screenId == "JRN-004" -> SavedFiltersScreen(content, actions)
        screenId == "JRN-005" -> SelectionScreen(content, pages, actions)
        screenId == "JRN-006" -> BulkEditScreen(content, actions)
        screenId == "JRN-007" -> DetailScreen(content, actions)
        screenId == "JRN-008" -> HistoryScreen(content, actions)
        screenId == "JRN-009" -> ComparisonScreen(content, actions)
        screenId == "JRN-010" -> DependenciesScreen(content, actions)
        screenId == "JRN-011" -> TrashScreen(pages, actions)
        screenId == "JRN-012" -> PurgeScreen(content, actions)
        else -> LedgerErrorState(UiErrorCode("JOURNAL_DESTINATION_UNKNOWN"), stringResource(R.string.p15_journal_error), actions.onRetry)
    }
}

private fun journalArgumentsValid(screenId: String, arguments: Map<String, *>): Boolean {
    val allowed = when (screenId) {
        "JRN-003" -> setOf("presetId")
        "JRN-007", "JRN-008", "JRN-010", "JRN-012" -> setOf("transactionId")
        "JRN-009" -> setOf("transactionId", "leftRevisionId", "rightRevisionId")
        else -> emptySet()
    }
    return arguments.keys.all(allowed::contains)
}

@Composable
private fun JournalListScreen(state: JournalLoadState.Content, pages: Flow<PagingData<JournalTransactionView>>, actions: JournalActions) {
    Column(Modifier.fillMaxSize().testTag(LedgerTestTags.JOURNAL_SCREEN), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            LedgerButton(stringResource(R.string.p15_journal_search), { actions.onNavigate("JRN-002", emptyMap()) }, Modifier.weight(1f), LedgerButtonVariant.SECONDARY, leadingIcon = LedgerIcon.SEARCH)
            LedgerButton(stringResource(R.string.p15_journal_filter), { actions.onNavigate("JRN-003", emptyMap()) }, Modifier.weight(1f), LedgerButtonVariant.SECONDARY)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            LedgerButton(stringResource(R.string.p15_journal_saved_filters), { actions.onNavigate("JRN-004", emptyMap()) }, Modifier.weight(1f), LedgerButtonVariant.TEXT)
            LedgerButton(stringResource(R.string.p15_journal_trash), {
                actions.onApplyFilter(state.filter.copy(lifecycleStates = setOf(TransactionLifecycleState.TRASHED)))
                actions.onNavigate("JRN-011", emptyMap())
            }, Modifier.weight(1f), LedgerButtonVariant.TEXT)
        }
        state.activePresetId?.let { LedgerBanner(state.presets.singleOrNull { preset -> preset.id == it }?.naturalLanguageSummary.orEmpty(), LedgerBannerVariant.INFO) }
        PagedJournalList(pages, actions, showRunningBalance = false)
    }
}

@Composable
private fun JournalSearchScreen(state: JournalLoadState.Content, pages: Flow<PagingData<JournalTransactionView>>, actions: JournalActions) {
    Column(Modifier.fillMaxSize().testTag(LedgerTestTags.JOURNAL_SCREEN)) {
        SearchField(state.searchText, actions.onSearch, onClear = { actions.onSearch("") }, onFilter = { actions.onNavigate("JRN-003", emptyMap()) })
        LedgerText(stringResource(R.string.p15_journal_filter_summary), LedgerTextRole.SUPPORTING)
        if (state.searchText.isBlank()) {
            LedgerText(stringResource(R.string.p15_journal_search), LedgerTextRole.BODY)
        } else {
            PagedJournalList(pages, actions, showRunningBalance = false)
        }
    }
}

@Composable
private fun JournalFilterScreen(state: JournalLoadState.Content, actions: JournalActions) {
    var presetName by remember { mutableStateOf("") }
    var draft by remember(state.filter) { mutableStateOf(state.filter) }
    var occurredFrom by remember(state.filter) { mutableStateOf(state.filter.occurredFrom?.toString().orEmpty()) }
    var occurredThrough by remember(state.filter) { mutableStateOf(state.filter.occurredThrough?.toString().orEmpty()) }
    var createdFrom by remember(state.filter) { mutableStateOf(state.filter.createdFrom?.toString().orEmpty()) }
    var createdThrough by remember(state.filter) { mutableStateOf(state.filter.createdThrough?.toString().orEmpty()) }
    var modifiedFrom by remember(state.filter) { mutableStateOf(state.filter.modifiedFrom?.toString().orEmpty()) }
    var modifiedThrough by remember(state.filter) { mutableStateOf(state.filter.modifiedThrough?.toString().orEmpty()) }
    var minimumMinor by remember(state.filter) { mutableStateOf(state.filter.amountRange?.minimumAccountMinor?.toString().orEmpty()) }
    var maximumMinor by remember(state.filter) { mutableStateOf(state.filter.amountRange?.maximumAccountMinor?.toString().orEmpty()) }
    val parsedOccurredFrom = occurredFrom.toOptionalInstant()
    val parsedOccurredThrough = occurredThrough.toOptionalInstant()
    val parsedCreatedFrom = createdFrom.toOptionalInstant()
    val parsedCreatedThrough = createdThrough.toOptionalInstant()
    val parsedModifiedFrom = modifiedFrom.toOptionalInstant()
    val parsedModifiedThrough = modifiedThrough.toOptionalInstant()
    val parsedMinimum = minimumMinor.toOptionalLong()
    val parsedMaximum = maximumMinor.toOptionalLong()
    val rangesValid = listOf(parsedOccurredFrom, parsedOccurredThrough, parsedCreatedFrom, parsedCreatedThrough, parsedModifiedFrom, parsedModifiedThrough).all { it.valid } &&
        parsedMinimum.valid && parsedMaximum.valid &&
        validRange(parsedOccurredFrom.value, parsedOccurredThrough.value) && validRange(parsedCreatedFrom.value, parsedCreatedThrough.value) &&
        validRange(parsedModifiedFrom.value, parsedModifiedThrough.value) && validRange(parsedMinimum.value, parsedMaximum.value)
    val dimensions = listOf(
        FilterDimensionUiModel(stringResource(R.string.p15_journal_filter_type), draft.kinds.map { FilterChipUiModel("kind_${it.name}", it.name) }),
        FilterDimensionUiModel(stringResource(R.string.p15_journal_filter_state), draft.lifecycleStates.map { FilterChipUiModel("state_${it.name}", it.name) }),
        FilterDimensionUiModel(stringResource(R.string.p15_journal_filter_source), draft.sources.map { FilterChipUiModel("source_${it.name}", it.name) }),
        FilterDimensionUiModel(stringResource(R.string.p15_journal_filter_context), contextFilterChips(draft)),
        FilterDimensionUiModel(stringResource(R.string.p15_journal_filter_time), timeFilterChips(draft)),
        FilterDimensionUiModel(stringResource(R.string.p15_journal_filter_amount), amountFilterChips(draft)),
    )
    LazyColumn(Modifier.fillMaxSize().testTag(LedgerTestTags.JOURNAL_SCREEN), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        item {
            FilterChoiceSection(stringResource(R.string.p15_journal_filter_type)) {
                TransactionKind.entries.forEach { value -> LedgerChoiceRow(value.name, value in draft.kinds, { draft = draft.copy(kinds = draft.kinds.toggled(value)) }) }
            }
        }
        item {
            FilterChoiceSection(stringResource(R.string.p15_journal_filter_state)) {
                TransactionLifecycleState.entries.forEach { value -> LedgerChoiceRow(value.name, value in draft.lifecycleStates, { draft = draft.copy(lifecycleStates = draft.lifecycleStates.toggled(value)) }) }
            }
        }
        item {
            FilterChoiceSection(stringResource(R.string.p15_journal_filter_source)) {
                TransactionSource.entries.forEach { value -> LedgerChoiceRow(value.name, value in draft.sources, { draft = draft.copy(sources = draft.sources.toggled(value)) }) }
            }
        }
        item {
            FilterChoiceSection(stringResource(R.string.p15_journal_filter_context)) {
                state.bulkOptions.accounts.forEach { option -> LedgerChoiceRow(option.label, UserAccountId(option.id) in draft.accountIds, { draft = draft.copy(accountIds = draft.accountIds.toggled(UserAccountId(option.id))) }) }
                state.bulkOptions.cards.forEach { option -> LedgerChoiceRow(option.label, PaymentCardId(option.id) in draft.cardIds, { draft = draft.copy(cardIds = draft.cardIds.toggled(PaymentCardId(option.id))) }) }
                state.bulkOptions.categories.forEach { option -> LedgerChoiceRow(option.label, CategoryId(option.id) in draft.categoryIds, { draft = draft.copy(categoryIds = draft.categoryIds.toggled(CategoryId(option.id))) }) }
                state.bulkOptions.merchants.forEach { option -> LedgerChoiceRow(option.label, MerchantId(option.id) in draft.merchantIds, { draft = draft.copy(merchantIds = draft.merchantIds.toggled(MerchantId(option.id))) }) }
                state.bulkOptions.projects.forEach { option -> LedgerChoiceRow(option.label, ProjectId(option.id) in draft.projectIds, { draft = draft.copy(projectIds = draft.projectIds.toggled(ProjectId(option.id))) }) }
                state.bulkOptions.settlementActivities.forEach { option -> LedgerChoiceRow(option.label, SettlementActivityId(option.id) in draft.settlementActivityIds, { draft = draft.copy(settlementActivityIds = draft.settlementActivityIds.toggled(SettlementActivityId(option.id))) }) }
                state.bulkOptions.participants.forEach { option -> LedgerChoiceRow(option.label, ParticipantId(option.id) in draft.participantIds, { draft = draft.copy(participantIds = draft.participantIds.toggled(ParticipantId(option.id))) }) }
            }
        }
        item {
            FormSection(stringResource(R.string.p15_journal_filter_time)) {
                FilterInstantField(occurredFrom, { occurredFrom = it }, stringResource(R.string.p15_journal_occurred_from), parsedOccurredFrom.valid)
                FilterInstantField(occurredThrough, { occurredThrough = it }, stringResource(R.string.p15_journal_occurred_through), parsedOccurredThrough.valid)
                FilterInstantField(createdFrom, { createdFrom = it }, stringResource(R.string.p15_journal_created_from), parsedCreatedFrom.valid)
                FilterInstantField(createdThrough, { createdThrough = it }, stringResource(R.string.p15_journal_created_through), parsedCreatedThrough.valid)
                FilterInstantField(modifiedFrom, { modifiedFrom = it }, stringResource(R.string.p15_journal_modified_from), parsedModifiedFrom.valid)
                FilterInstantField(modifiedThrough, { modifiedThrough = it }, stringResource(R.string.p15_journal_modified_through), parsedModifiedThrough.valid)
            }
        }
        item {
            FormSection(stringResource(R.string.p15_journal_filter_amount)) {
                LedgerTextField(minimumMinor, { minimumMinor = it.take(20) }, stringResource(R.string.p15_journal_minimum_minor), errorText = stringResource(R.string.p15_journal_invalid_number).takeIf { !parsedMinimum.valid })
                LedgerTextField(maximumMinor, { maximumMinor = it.take(20) }, stringResource(R.string.p15_journal_maximum_minor), errorText = stringResource(R.string.p15_journal_invalid_number).takeIf { !parsedMaximum.valid })
                state.bulkOptions.currencies.forEach { currency -> LedgerChoiceRow(currency.value, currency in draft.currencies, { draft = draft.copy(currencies = draft.currencies.toggled(currency)) }) }
                StatisticalNature.entries.forEach { nature -> LedgerChoiceRow(nature.name, nature in draft.statisticalNatures, { draft = draft.copy(statisticalNatures = draft.statisticalNatures.toggled(nature)) }) }
            }
        }
        item {
            FormSection(stringResource(R.string.p15_journal_filter_flags)) {
                TriStateFilter(stringResource(R.string.p15_journal_filter_attachment), draft.hasAttachment) { draft = draft.copy(hasAttachment = it) }
                TriStateFilter(stringResource(R.string.p15_journal_filter_refund), draft.isRefund) { draft = draft.copy(isRefund = it) }
                TriStateFilter(stringResource(R.string.p15_journal_filter_installment), draft.hasInstallment) { draft = draft.copy(hasInstallment = it) }
                TriStateFilter(stringResource(R.string.p15_journal_filter_budget), draft.includedInBudget) { draft = draft.copy(includedInBudget = it) }
                TriStateFilter(stringResource(R.string.p15_journal_filter_recurrence), draft.generatedByRecurrence) { draft = draft.copy(generatedByRecurrence = it) }
            }
        }
        item {
            FilterBuilder(
                dimensions = dimensions,
                naturalLanguageSummary = filterSummary(draft),
                onRemove = { actions.onRemoveFilter(it.stableKey) },
                onReset = {
                    draft = TransactionFilter()
                    occurredFrom = ""
                    occurredThrough = ""
                    createdFrom = ""
                    createdThrough = ""
                    modifiedFrom = ""
                    modifiedThrough = ""
                    minimumMinor = ""
                    maximumMinor = ""
                },
                onApply = {
                    if (rangesValid) {
                        val range = if (parsedMinimum.value != null || parsedMaximum.value != null) TransactionAmountRange(parsedMinimum.value, parsedMaximum.value, null) else null
                        actions.onApplyFilter(
                            draft.copy(
                                occurredFrom = parsedOccurredFrom.value,
                                occurredThrough = parsedOccurredThrough.value,
                                createdFrom = parsedCreatedFrom.value,
                                createdThrough = parsedCreatedThrough.value,
                                modifiedFrom = parsedModifiedFrom.value,
                                modifiedThrough = parsedModifiedThrough.value,
                                amountRange = range,
                            ),
                        )
                    }
                },
            )
            if (!rangesValid) LedgerBanner(stringResource(R.string.p15_journal_filter_invalid_range), LedgerBannerVariant.DANGER)
        }
        item {
            FormSection(stringResource(R.string.p15_journal_save_filter)) {
                LedgerTextField(presetName, { presetName = it.take(80) }, stringResource(R.string.p15_journal_filter_name))
                LedgerButton(
                    stringResource(R.string.p15_journal_save_filter),
                    {
                        actions.onSaveFilter(presetName)
                        presetName = ""
                    },
                    Modifier.fillMaxWidth(),
                    enabled = presetName.isNotBlank(),
                )
            }
        }
    }
}

@Composable
private fun SavedFiltersScreen(state: JournalLoadState.Content, actions: JournalActions) {
    if (state.presets.isEmpty()) {
        LedgerEmptyState(stringResource(R.string.p15_journal_saved_filters), stringResource(R.string.p15_journal_no_saved_filters), stringResource(R.string.p15_journal_retry), {}, Modifier.testTag(LedgerTestTags.JOURNAL_SCREEN))
    } else {
        LazyColumn(Modifier.fillMaxSize().testTag(LedgerTestTags.JOURNAL_SCREEN), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            items(state.presets.sortedBy { it.sortOrder }, key = { it.id.toString() }) { preset ->
                LedgerCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(LedgerTheme.spacing.sm)) {
                        LedgerText(preset.name, LedgerTextRole.SECTION)
                        LedgerText(preset.naturalLanguageSummary, LedgerTextRole.SUPPORTING)
                        if (preset.isDefault) LedgerBanner(stringResource(R.string.p15_journal_default_filter), LedgerBannerVariant.INFO)
                        LedgerButton(stringResource(R.string.p15_journal_apply_filter), { actions.onApplyPreset(preset.id) }, Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                            LedgerButton(stringResource(R.string.p15_journal_copy_filter), { actions.onCopyPreset(preset.id) }, Modifier.weight(1f), LedgerButtonVariant.SECONDARY)
                            LedgerButton(stringResource(R.string.p15_journal_set_default), { actions.onSetDefaultPreset(preset.id) }, Modifier.weight(1f), LedgerButtonVariant.SECONDARY)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                            val ordered = state.presets.sortedBy { it.sortOrder }
                            val index = ordered.indexOfFirst { it.id == preset.id }
                            LedgerButton(stringResource(R.string.p15_journal_move_up), { actions.onReorderPresets(ordered.swap(index, index - 1).map { it.id }) }, Modifier.weight(1f), LedgerButtonVariant.TEXT, enabled = index > 0)
                            LedgerButton(stringResource(R.string.p15_journal_move_down), { actions.onReorderPresets(ordered.swap(index, index + 1).map { it.id }) }, Modifier.weight(1f), LedgerButtonVariant.TEXT, enabled = index in 0 until ordered.lastIndex)
                        }
                        LedgerButton(stringResource(R.string.p15_journal_delete_filter), { actions.onDeletePreset(preset.id) }, Modifier.fillMaxWidth(), LedgerButtonVariant.DANGER)
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionScreen(state: JournalLoadState.Content, pages: Flow<PagingData<JournalTransactionView>>, actions: JournalActions) {
    val selection = state.selection
    Column(Modifier.fillMaxSize().testTag(LedgerTestTags.JOURNAL_SCREEN)) {
        if (selection == null) {
            LedgerBanner(stringResource(R.string.p15_journal_selection), LedgerBannerVariant.INFO)
        } else if (selection.queryChanged(JournalSelectionPolicy.fingerprint(state.filter))) {
            LedgerBanner(stringResource(R.string.p15_journal_query_changed), LedgerBannerVariant.WARNING)
        } else if (selection.mode == JournalSelectionMode.ALL_MATCHING) {
            LedgerBanner(stringResource(R.string.p15_journal_all_matching), LedgerBannerVariant.INFO)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            LedgerButton(stringResource(R.string.p15_journal_select_all), actions.onSelectAllMatching, Modifier.weight(1f))
            LedgerButton(stringResource(R.string.p15_journal_clear_selection), actions.onClearSelection, Modifier.weight(1f), LedgerButtonVariant.TEXT, enabled = selection != null)
        }
        PagedJournalList(pages, actions, false, selectionMode = true)
        LedgerButton(stringResource(R.string.p15_journal_bulk_edit), { actions.onNavigate("JRN-006", emptyMap()) }, Modifier.fillMaxWidth(), enabled = selection != null)
    }
}

@Composable
private fun BulkEditScreen(state: JournalLoadState.Content, actions: JournalActions) {
    val options = state.bulkOptions
    var categoryEnabled by remember { mutableStateOf(false) }
    var categoryIndex by remember { mutableStateOf(0) }
    var accountEnabled by remember { mutableStateOf(false) }
    var accountIndex by remember { mutableStateOf(0) }
    var cardIndex by remember { mutableStateOf(-1) }
    var merchantEnabled by remember { mutableStateOf(false) }
    var merchantIndex by remember { mutableStateOf(-1) }
    var projectEnabled by remember { mutableStateOf(false) }
    var projectIndex by remember { mutableStateOf(-1) }
    var timeEnabled by remember { mutableStateOf(false) }
    var occurredAt by remember { mutableStateOf("") }
    var noteEnabled by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var budgetEnabled by remember { mutableStateOf(false) }
    var includedInBudget by remember { mutableStateOf(true) }
    var natureEnabled by remember { mutableStateOf(false) }
    var natureIndex by remember { mutableStateOf(0) }
    val selectedAccount = options.accounts.getOrNull(accountIndex)
    val compatibleCards = options.cards.filter { it.parentId == selectedAccount?.id }
    val selectedCard = compatibleCards.getOrNull(cardIndex)
    val parsedTime = occurredAt.takeIf(String::isNotBlank)?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
    val anyChange = categoryEnabled || accountEnabled || merchantEnabled || projectEnabled || timeEnabled || noteEnabled || budgetEnabled || natureEnabled
    val inputValid = state.selection != null && anyChange && (!categoryEnabled || options.categories.isNotEmpty()) &&
        (!accountEnabled || selectedAccount != null) && (!timeEnabled || parsedTime != null)
    LazyColumn(Modifier.fillMaxSize().testTag(LedgerTestTags.JOURNAL_SCREEN), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        item { LedgerBanner(stringResource(R.string.p15_journal_bulk_forbidden), LedgerBannerVariant.WARNING) }
        item { BulkSelector(stringResource(R.string.p15_journal_bulk_category), categoryEnabled, { categoryEnabled = it }, options.categories.getOrNull(categoryIndex)?.label.orEmpty(), { categoryIndex = options.categories.nextIndex(categoryIndex) }, options.categories.isNotEmpty()) }
        item {
            BulkSelector(stringResource(R.string.p15_journal_bulk_account), accountEnabled, { accountEnabled = it }, selectedAccount?.label.orEmpty(), {
                accountIndex = options.accounts.nextIndex(accountIndex)
                cardIndex = -1
            }, options.accounts.isNotEmpty())
        }
        item { BulkSelector(stringResource(R.string.p15_journal_bulk_card), accountEnabled, {}, selectedCard?.label ?: stringResource(R.string.p15_journal_clear_value), { cardIndex = compatibleCards.nextNullableIndex(cardIndex) }, compatibleCards.isNotEmpty()) }
        item { BulkSelector(stringResource(R.string.p15_journal_bulk_merchant), merchantEnabled, { merchantEnabled = it }, options.merchants.getOrNull(merchantIndex)?.label ?: stringResource(R.string.p15_journal_clear_value), { merchantIndex = options.merchants.nextNullableIndex(merchantIndex) }, true) }
        item { BulkSelector(stringResource(R.string.p15_journal_bulk_project), projectEnabled, { projectEnabled = it }, options.projects.getOrNull(projectIndex)?.label ?: stringResource(R.string.p15_journal_clear_value), { projectIndex = options.projects.nextNullableIndex(projectIndex) }, true) }
        item {
            LedgerChoiceRow(stringResource(R.string.p15_journal_bulk_time), timeEnabled, { timeEnabled = !timeEnabled })
            LedgerTextField(occurredAt, { occurredAt = it.take(40) }, stringResource(R.string.p15_journal_iso_time), enabled = timeEnabled, errorText = stringResource(R.string.p15_journal_invalid_time).takeIf { timeEnabled && occurredAt.isNotBlank() && parsedTime == null })
        }
        item {
            LedgerChoiceRow(stringResource(R.string.p15_journal_bulk_note), noteEnabled, { noteEnabled = !noteEnabled })
            LedgerTextField(note, { note = it.take(2_000) }, stringResource(R.string.p15_journal_bulk_note), enabled = noteEnabled, singleLine = false, hideValueFromSemantics = true)
        }
        item {
            LedgerChoiceRow(stringResource(R.string.p15_journal_bulk_budget), budgetEnabled, { budgetEnabled = !budgetEnabled })
            SelectorField(stringResource(R.string.p15_journal_bulk_budget), if (includedInBudget) stringResource(R.string.p15_journal_yes) else stringResource(R.string.p15_journal_no), { includedInBudget = !includedInBudget }, enabled = budgetEnabled)
        }
        item {
            LedgerChoiceRow(stringResource(R.string.p15_journal_bulk_nature), natureEnabled, { natureEnabled = !natureEnabled })
            SelectorField(stringResource(R.string.p15_journal_bulk_nature), StatisticalNature.entries[natureIndex].name, { natureIndex = (natureIndex + 1) % StatisticalNature.entries.size }, enabled = natureEnabled)
        }
        item {
            LedgerText(state.operation.name, LedgerTextRole.SUPPORTING)
            LedgerButton(
                stringResource(R.string.p15_journal_apply_atomically),
                {
                    actions.onBulkEdit(
                        JournalBulkEditPatch(
                            categoryId = if (categoryEnabled) JournalFieldUpdate.Set(options.categories[categoryIndex].id) else JournalFieldUpdate.Unchanged,
                            accountAndCard = if (accountEnabled) JournalFieldUpdate.Set(JournalAccountCardUpdate(checkNotNull(selectedAccount).id, selectedCard?.id)) else JournalFieldUpdate.Unchanged,
                            merchantId = if (merchantEnabled) options.merchants.getOrNull(merchantIndex)?.let { JournalFieldUpdate.Set(it.id) } ?: JournalFieldUpdate.Clear else JournalFieldUpdate.Unchanged,
                            projectId = if (projectEnabled) options.projects.getOrNull(projectIndex)?.let { JournalFieldUpdate.Set(it.id) } ?: JournalFieldUpdate.Clear else JournalFieldUpdate.Unchanged,
                            occurredAt = if (timeEnabled) JournalFieldUpdate.Set(checkNotNull(parsedTime)) else JournalFieldUpdate.Unchanged,
                            note = if (noteEnabled) note.trim().takeIf(String::isNotEmpty)?.let { JournalFieldUpdate.Set(it) } ?: JournalFieldUpdate.Clear else JournalFieldUpdate.Unchanged,
                            includedInBudget = if (budgetEnabled) JournalFieldUpdate.Set(includedInBudget) else JournalFieldUpdate.Unchanged,
                            statisticalNature = if (natureEnabled) JournalFieldUpdate.Set(StatisticalNature.entries[natureIndex]) else JournalFieldUpdate.Unchanged,
                        ),
                    )
                },
                Modifier.fillMaxWidth(),
                enabled = inputValid && state.operation !in setOf(JournalOperationState.VALIDATING, JournalOperationState.COMMITTING),
            )
        }
    }
}

@Composable
private fun DetailScreen(state: JournalLoadState.Content, actions: JournalActions) {
    val detail = state.detail
    if (detail == null) {
        LedgerEmptyState(stringResource(R.string.p15_journal_detail), stringResource(R.string.p15_journal_not_found), stringResource(R.string.p15_journal_retry), actions.onRetry, Modifier.testTag(LedgerTestTags.JOURNAL_SCREEN))
        return
    }
    val locale = LocalLocale.current.platformLocale
    LazyColumn(Modifier.fillMaxSize().testTag(LedgerTestTags.JOURNAL_SCREEN), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        item { JournalTransactionRow(detail.transaction.toUi(locale), {}, {}) }
        item { DetailSection(stringResource(R.string.p15_journal_user_input), listOfNotNull(detail.amountExpression, detail.fullNote, detail.merchantName, detail.projectName, detail.locationName)) }
        item { DetailSection(stringResource(R.string.p15_journal_account_effects), detail.accountEffects) }
        item { DetailSection(stringResource(R.string.p15_journal_budget_semantics), listOfNotNull(detail.budgetSummary, detail.statisticalNature)) }
        item { DetailSection(stringResource(R.string.p15_journal_fx), detail.fxEvidence.map { "${it.sourceCurrency.value}/${it.targetCurrency.value} ${it.rate} · ${it.provider}" }) }
        item { DetailSection(stringResource(R.string.p15_journal_relationships), detail.relationshipSummaries) }
        item { DetailSection(stringResource(R.string.p15_journal_attachments), detail.attachmentNames) }
        item { DetailSection(stringResource(R.string.p15_journal_source), listOf(detail.sourceDescription, detail.createdAt.toString(), detail.modifiedAt.toString())) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                LedgerButton(stringResource(R.string.p15_journal_history), { actions.onNavigate("JRN-008", mapOf("transactionId" to detail.transaction.transactionId)) }, Modifier.weight(1f), LedgerButtonVariant.SECONDARY)
                LedgerButton(stringResource(R.string.p15_journal_dependencies), { actions.onNavigate("JRN-010", mapOf("transactionId" to detail.transaction.transactionId)) }, Modifier.weight(1f), LedgerButtonVariant.SECONDARY)
            }
        }
        item {
            if (detail.transaction.state == TransactionLifecycleState.TRASHED) {
                LedgerButton(stringResource(R.string.p15_journal_restore), { actions.onRestore(detail.transaction.transactionId, detail.transaction.revisionId) }, Modifier.fillMaxWidth())
            } else {
                val resolved = state.dependencies.size == state.dependencyResolutions.size
                if (!resolved) LedgerBanner(stringResource(R.string.p15_journal_dependency_blocked), LedgerBannerVariant.WARNING)
                LedgerButton(
                    stringResource(R.string.p15_journal_move_trash),
                    { actions.onMoveToTrash(detail.transaction.transactionId, detail.transaction.revisionId, state.dependencyResolutions) },
                    Modifier.fillMaxWidth(),
                    LedgerButtonVariant.DANGER,
                    enabled = resolved,
                )
            }
        }
    }
}

@Composable
private fun DetailSection(title: String, values: List<String>) {
    FormSection(title) {
        if (values.isEmpty()) LedgerText("—", LedgerTextRole.SUPPORTING) else values.forEach { LedgerText(it, LedgerTextRole.BODY) }
    }
}

@Composable
private fun BulkSelector(
    label: String,
    selected: Boolean,
    onSelected: (Boolean) -> Unit,
    selectedText: String,
    onNext: () -> Unit,
    available: Boolean,
) {
    LedgerChoiceRow(label, selected, { onSelected(!selected) }, supportingText = stringResource(R.string.p15_journal_enable_field))
    SelectorField(label, selectedText.ifBlank { stringResource(R.string.p15_journal_no_options) }, onNext, enabled = selected && available)
}

@Composable
private fun HistoryScreen(state: JournalLoadState.Content, actions: JournalActions) {
    val history = state.history
    if (history.isEmpty()) {
        LedgerLoadingState(Modifier.fillMaxSize(), stringResource(R.string.p15_journal_loading))
    } else {
        LazyColumn(Modifier.fillMaxSize().testTag(LedgerTestTags.JOURNAL_SCREEN), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            items(history, key = { it.revisionId.toString() }) { version ->
                LedgerCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(LedgerTheme.spacing.sm)) {
                        LedgerText("#${version.revisionNumber} · ${version.action}", LedgerTextRole.SECTION)
                        LedgerText(version.createdAt.toString(), LedgerTextRole.SUPPORTING)
                        LedgerText(version.changedFields.joinToString(), LedgerTextRole.BODY)
                        val transaction = state.detail?.transaction
                        val current = history.firstOrNull()
                        if (transaction != null && current != null && version.revisionId != current.revisionId) {
                            LedgerButton(
                                stringResource(R.string.p15_journal_compare),
                                {
                                    actions.onCompareRevisions(transaction.transactionId, version.revisionId, current.revisionId)
                                    actions.onNavigate("JRN-009", mapOf("transactionId" to transaction.transactionId, "leftRevisionId" to version.revisionId, "rightRevisionId" to current.revisionId))
                                },
                                Modifier.fillMaxWidth(),
                                LedgerButtonVariant.SECONDARY,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComparisonScreen(state: JournalLoadState.Content, actions: JournalActions) {
    val comparison = state.comparison
    if (comparison == null) {
        LedgerLoadingState(Modifier.fillMaxSize(), stringResource(R.string.p15_journal_loading))
    } else {
        Column(Modifier.fillMaxSize().testTag(LedgerTestTags.JOURNAL_SCREEN), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
            LedgerText(stringResource(R.string.p15_journal_compare), LedgerTextRole.TITLE)
            DetailSection("CHANGED", comparison.changedFields)
            DetailSection("UNCHANGED", comparison.unchangedFields)
            val transaction = state.detail?.transaction
            LedgerButton(
                stringResource(R.string.p15_journal_restore_version),
                { transaction?.let { actions.onRestoreRevision(it.transactionId, it.revisionId, comparison.left.revisionId, state.dependencyResolutions) } },
                Modifier.fillMaxWidth(),
                enabled = transaction != null && state.dependencies.size == state.dependencyResolutions.size,
            )
        }
    }
}

@Composable
private fun DependenciesScreen(state: JournalLoadState.Content, actions: JournalActions) {
    if (state.dependencies.isEmpty()) {
        LedgerEmptyState(stringResource(R.string.p15_journal_dependencies), stringResource(R.string.p15_journal_no_dependencies), stringResource(R.string.p15_journal_retry), {}, Modifier.testTag(LedgerTestTags.JOURNAL_SCREEN))
    } else {
        LazyColumn(Modifier.fillMaxSize().testTag(LedgerTestTags.JOURNAL_SCREEN), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            item { LedgerBanner(stringResource(R.string.p15_journal_dependency_blocked), LedgerBannerVariant.WARNING) }
            items(state.dependencies, key = { "${it.parentTransactionId}:${it.childTransactionId}:${it.type}" }) { dependency ->
                val policies = dependencyPolicies(dependency.type)
                val selected = state.dependencyResolutions.singleOrNull { it.dependency == dependency.toDomain() }?.policy
                val index = policies.indexOf(selected).takeIf { it >= 0 } ?: -1
                LedgerCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                        DetailSection(dependency.type.name, listOf(dependency.parentTransactionId.toString(), dependency.childTransactionId.toString(), dependency.childState.name))
                        SelectorField(
                            stringResource(R.string.p15_journal_dependency_strategy),
                            selected?.policyLabel() ?: stringResource(R.string.p15_journal_dependency_choose),
                            { actions.onResolveDependency(dependency, policies[(index + 1).mod(policies.size)]) },
                        )
                    }
                }
            }
            item {
                val complete = state.dependencies.size == state.dependencyResolutions.size
                LedgerBanner(
                    if (complete) stringResource(R.string.p15_journal_dependency_complete) else stringResource(R.string.p15_journal_dependency_blocked),
                    if (complete) LedgerBannerVariant.INFO else LedgerBannerVariant.WARNING,
                )
            }
        }
    }
}

@Composable
private fun TrashScreen(pages: Flow<PagingData<JournalTransactionView>>, actions: JournalActions) {
    Column(Modifier.fillMaxSize().testTag(LedgerTestTags.JOURNAL_SCREEN)) {
        LedgerBanner(stringResource(R.string.p15_journal_retention), LedgerBannerVariant.INFO)
        PagedJournalList(pages, actions, false)
    }
}

@Composable
private fun PurgeScreen(state: JournalLoadState.Content, actions: JournalActions) {
    val assessment = state.purgeAssessment
    var phrase by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().testTag(LedgerTestTags.JOURNAL_SCREEN), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        if (assessment == null) {
            LedgerLoadingState(label = stringResource(R.string.p15_journal_loading))
        } else if (!assessment.canPurgeNow) {
            LedgerBanner(stringResource(R.string.p15_journal_purge_blocked), LedgerBannerVariant.DANGER)
            assessment.reasons.forEach { LedgerText(it.name, LedgerTextRole.BODY) }
            LedgerBanner(stringResource(R.string.p15_journal_purge_p31), LedgerBannerVariant.INFO)
        } else {
            HighRiskConfirmation(
                stringResource(R.string.p15_journal_purge), stringResource(R.string.p15_journal_purge_scope),
                stringResource(R.string.p15_journal_purge_consequence), stringResource(R.string.p15_journal_purge_unaffected),
                stringResource(R.string.p15_journal_purge_phrase), phrase, { phrase = it },
                { assessment.transactionId.let(actions.onPurgeRequested) }, { phrase = "" },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PagedJournalList(
    pages: Flow<PagingData<JournalTransactionView>>,
    actions: JournalActions,
    showRunningBalance: Boolean,
    selectionMode: Boolean = false,
) {
    val items = pages.collectAsLazyPagingItems()
    when {
        items.loadState.refresh is LoadState.Loading -> LedgerLoadingState(Modifier.fillMaxSize(), stringResource(R.string.p15_journal_loading))
        items.loadState.refresh is LoadState.Error -> LedgerErrorState(UiErrorCode("JOURNAL_PAGE_FAILED"), stringResource(R.string.p15_journal_error), items::retry)
        items.itemCount == 0 -> LedgerEmptyState(stringResource(R.string.p15_journal_empty_title), stringResource(R.string.p15_journal_empty_body), stringResource(R.string.p15_journal_retry), items::retry)
        else -> JournalLazyColumn(items, actions, showRunningBalance, selectionMode)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun JournalLazyColumn(items: LazyPagingItems<JournalTransactionView>, actions: JournalActions, showRunningBalance: Boolean, selectionMode: Boolean) {
    val locale = LocalLocale.current.platformLocale
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xxs)) {
        var previous: LocalDate? = null
        for (index in 0 until items.itemCount) {
            val date = items.peek(index)?.localDate
            if (date != null && date != previous) {
                stickyHeader(key = "date_$date") {
                    LedgerCard(Modifier.fillMaxWidth()) { LedgerText(date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)), LedgerTextRole.SECTION, Modifier.padding(LedgerTheme.spacing.xs)) }
                }
                previous = date
            }
            item(key = items.itemKey { it.transactionId.toString() }(index), contentType = items.itemContentType { "transaction" }(index)) {
                items[index]?.let { transaction ->
                    JournalTransactionRow(
                        transaction.toUi(locale),
                        onClick = {
                            if (selectionMode) {
                                actions.onSelect(transaction.transactionId)
                            } else {
                                actions.onLoadDetail(transaction.transactionId)
                                actions.onNavigate("JRN-007", mapOf("transactionId" to transaction.transactionId))
                            }
                        },
                        onLongClick = {
                            actions.onSelect(transaction.transactionId)
                            actions.onNavigate("JRN-005", emptyMap())
                        },
                        showRunningBalance = showRunningBalance,
                    )
                }
            }
        }
        if (items.loadState.append is LoadState.Loading) item { LedgerLoadingState(label = stringResource(R.string.p15_journal_loading)) }
        if (items.loadState.append is LoadState.Error) item { LedgerButton(stringResource(R.string.p15_journal_retry), items::retry, Modifier.fillMaxWidth()) }
    }
}

private fun JournalTransactionView.toUi(locale: Locale): JournalTransactionUiModel {
    val secondaryMinor = secondaryAmountMinor
    val secondaryCode = secondaryCurrency
    val secondary = if (secondaryMinor != null && secondaryCode != null) Money(secondaryMinor, secondaryCode) else null
    val semantic = when (kind) {
        TransactionKind.EXPENSE -> AmountSemantic.OUTFLOW
        TransactionKind.INCOME -> AmountSemantic.INFLOW
        TransactionKind.REFUND -> AmountSemantic.REFUND
        TransactionKind.TRANSFER, TransactionKind.FX_EXCHANGE -> AmountSemantic.TRANSFER
        else -> AmountSemantic.NEUTRAL
    }
    val formatter = LocaleCurrencyFormatter(JvmLegalTenderCurrencyCatalog.create())
    val amount = when (val result = formatter.format(MoneyFormatRequest(Money(amountMinor, currency), locale, semantic, AmountVisibility.VISIBLE, secondaryMoney = secondary))) {
        is DomainResult.Success -> result.value
        is DomainResult.Failure -> MoneyUiModel("${currency.value} $amountMinor", "${currency.value} $amountMinor", semantic, AmountVisibility.VISIBLE)
    }
    val running = runningBalanceMinor?.let { balance ->
        when (val result = formatter.format(MoneyFormatRequest(Money(balance, currency), locale, AmountSemantic.NEUTRAL, AmountVisibility.VISIBLE))) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> null
        }
    }
    val accessible = listOf(kind.name, categoryOrType, summary, accountAndCard, amount.fullAccessibleText, badges.joinToString()).filter(String::isNotBlank).joinToString(". ")
    return JournalTransactionUiModel(transactionId.toString(), categoryOrType, summary, accountAndCard, amount, kind.name, iconFor(kind), badges, running, accessible)
}

private fun iconFor(kind: TransactionKind): LedgerIcon = when (kind) {
    TransactionKind.TRANSFER, TransactionKind.FX_EXCHANGE -> LedgerIcon.TRANSFER
    TransactionKind.REFUND -> LedgerIcon.REFUND
    else -> LedgerIcon.JOURNAL
}

private data class OptionalInput<T>(val value: T?, val valid: Boolean)

private fun String.toOptionalInstant(): OptionalInput<Instant> = if (isBlank()) {
    OptionalInput(null, true)
} else {
    runCatching { Instant.parse(trim()) }.fold({ OptionalInput(it, true) }, { OptionalInput(null, false) })
}

private fun String.toOptionalLong(): OptionalInput<Long> = if (isBlank()) {
    OptionalInput(null, true)
} else {
    toLongOrNull()?.let { OptionalInput(it, true) } ?: OptionalInput(null, false)
}

private fun <T : Comparable<T>> validRange(from: T?, through: T?): Boolean = from == null || through == null || through >= from

private fun <T> Set<T>.toggled(value: T): Set<T> = if (value in this) this - value else this + value

private fun app.ledger.finance.application.JournalDependencyView.toDomain(): TransactionDependency = TransactionDependency(
    TransactionId(parentTransactionId),
    TransactionId(childTransactionId),
    type,
)

private fun dependencyPolicies(type: TransactionDependencyType): List<DependencyPolicy> = when (type) {
    TransactionDependencyType.REFUND -> listOf(DependencyPolicy.ReverseDependentTransactions, DependencyPolicy.ConvertRefundToIndependent)
    TransactionDependencyType.INSTALLMENT_PLAN -> listOf(DependencyPolicy.ReverseDependentTransactions, DependencyPolicy.CancelInstallmentPlan)
    TransactionDependencyType.CREDIT_STATEMENT -> listOf(DependencyPolicy.ReverseDependentTransactions, DependencyPolicy.RegenerateCreditStatement)
    TransactionDependencyType.LOAN_SCHEDULE -> listOf(DependencyPolicy.ReverseDependentTransactions, DependencyPolicy.RecalculateLoanSchedule)
    TransactionDependencyType.SETTLEMENT_ACTIVITY -> listOf(DependencyPolicy.ReverseDependentTransactions, DependencyPolicy.RecalculateSettlement, DependencyPolicy.ReopenSettledActivity)
    TransactionDependencyType.RECURRENCE_OCCURRENCE,
    TransactionDependencyType.ATTACHMENT_REFERENCE,
    -> listOf(DependencyPolicy.ReverseDependentTransactions)
}

@Composable
private fun DependencyPolicy.policyLabel(): String = when (this) {
    DependencyPolicy.ReverseDependentTransactions -> stringResource(R.string.p15_journal_dependency_reverse)
    DependencyPolicy.ConvertRefundToIndependent -> stringResource(R.string.p15_journal_dependency_independent_refund)
    DependencyPolicy.CancelInstallmentPlan -> stringResource(R.string.p15_journal_dependency_cancel_installment)
    is DependencyPolicy.RebindInstallmentPlan -> stringResource(R.string.p15_journal_dependency_rebind_installment)
    DependencyPolicy.RecalculateSettlement -> stringResource(R.string.p15_journal_dependency_recalculate_settlement)
    DependencyPolicy.ReopenSettledActivity -> stringResource(R.string.p15_journal_dependency_reopen_settlement)
    DependencyPolicy.RegenerateCreditStatement -> stringResource(R.string.p15_journal_dependency_regenerate_statement)
    DependencyPolicy.RecalculateLoanSchedule -> stringResource(R.string.p15_journal_dependency_recalculate_loan)
}

@Composable
private fun FilterChoiceSection(title: String, content: @Composable () -> Unit) {
    FormSection(title) { Column(verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xxs)) { content() } }
}

@Composable
private fun FilterInstantField(value: String, onValueChange: (String) -> Unit, label: String, valid: Boolean) {
    LedgerTextField(
        value,
        { onValueChange(it.take(40)) },
        label,
        errorText = stringResource(R.string.p15_journal_invalid_time).takeIf { !valid },
    )
}

@Composable
private fun TriStateFilter(label: String, value: Boolean?, onValueChange: (Boolean?) -> Unit) {
    val displayed = when (value) {
        null -> stringResource(R.string.p15_journal_any)
        true -> stringResource(R.string.p15_journal_yes)
        false -> stringResource(R.string.p15_journal_no)
    }
    SelectorField(
        label,
        displayed,
        {
            onValueChange(
                when (value) {
                    null -> true
                    true -> false
                    false -> null
                },
            )
        },
    )
}

@Composable
private fun filterSummary(filter: TransactionFilter): String = buildList {
    if (filter.kinds.isNotEmpty()) add("${stringResource(R.string.p15_journal_filter_type)}:${filter.kinds.size}")
    if (filter.lifecycleStates.isNotEmpty()) add("${stringResource(R.string.p15_journal_filter_state)}:${filter.lifecycleStates.size}")
    if (filter.sources.isNotEmpty()) add("${stringResource(R.string.p15_journal_filter_source)}:${filter.sources.size}")
    val contextCount = filter.accountIds.size + filter.cardIds.size + filter.categoryIds.size + filter.merchantIds.size +
        filter.projectIds.size + filter.settlementActivityIds.size + filter.participantIds.size
    if (contextCount > 0) add("${stringResource(R.string.p15_journal_filter_context)}:$contextCount")
    val hasTimeFilter = listOf(
        filter.occurredFrom,
        filter.occurredThrough,
        filter.createdFrom,
        filter.createdThrough,
        filter.modifiedFrom,
        filter.modifiedThrough,
    ).any { it != null }
    if (hasTimeFilter) {
        add(stringResource(R.string.p15_journal_filter_time))
    }
    if (filter.amountRange != null || filter.currencies.isNotEmpty() || filter.statisticalNatures.isNotEmpty()) add(stringResource(R.string.p15_journal_filter_amount))
    if (listOf(filter.hasAttachment, filter.isRefund, filter.hasInstallment, filter.includedInBudget, filter.generatedByRecurrence).any { it != null }) add(stringResource(R.string.p15_journal_filter_flags))
}.ifEmpty { listOf(stringResource(R.string.p15_journal_filter_all)) }.joinToString(" · ")

private fun contextFilterChips(filter: TransactionFilter): List<FilterChipUiModel> = buildList {
    filter.accountIds.forEach { add(FilterChipUiModel("account_$it", "ACCOUNT")) }
    filter.cardIds.forEach { add(FilterChipUiModel("card_$it", "CARD")) }
    filter.categoryIds.forEach { add(FilterChipUiModel("category_$it", "CATEGORY")) }
    filter.merchantIds.forEach { add(FilterChipUiModel("merchant_$it", "MERCHANT")) }
    filter.projectIds.forEach { add(FilterChipUiModel("project_$it", "PROJECT")) }
    filter.settlementActivityIds.forEach { add(FilterChipUiModel("settlement_$it", "SETTLEMENT")) }
    filter.participantIds.forEach { add(FilterChipUiModel("participant_$it", "PARTICIPANT")) }
    filter.geoRadius?.let { add(FilterChipUiModel("geo_radius", "${it.radiusMeters}m")) }
}

private fun timeFilterChips(filter: TransactionFilter): List<FilterChipUiModel> = buildList {
    filter.occurredFrom?.let { add(FilterChipUiModel("occurred_from", it.toString())) }
    filter.occurredThrough?.let { add(FilterChipUiModel("occurred_through", it.toString())) }
    filter.createdFrom?.let { add(FilterChipUiModel("created_from", it.toString())) }
    filter.createdThrough?.let { add(FilterChipUiModel("created_through", it.toString())) }
    filter.modifiedFrom?.let { add(FilterChipUiModel("modified_from", it.toString())) }
    filter.modifiedThrough?.let { add(FilterChipUiModel("modified_through", it.toString())) }
}

private fun amountFilterChips(filter: TransactionFilter): List<FilterChipUiModel> = buildList {
    filter.amountRange?.let { add(FilterChipUiModel("amount", "${it.minimumAccountMinor ?: ""}–${it.maximumAccountMinor ?: ""}")) }
    filter.currencies.forEach { add(FilterChipUiModel("currency_${it.value}", it.value)) }
    filter.statisticalNatures.forEach { add(FilterChipUiModel("nature_${it.name}", it.name)) }
    filter.includedInBudget?.let { add(FilterChipUiModel("budget", "BUDGET:$it")) }
    filter.hasAttachment?.let { add(FilterChipUiModel("attachment", "ATTACHMENT:$it")) }
    filter.isRefund?.let { add(FilterChipUiModel("refund", "REFUND:$it")) }
    filter.hasInstallment?.let { add(FilterChipUiModel("installment", "INSTALLMENT:$it")) }
    filter.generatedByRecurrence?.let { add(FilterChipUiModel("recurrence", "RECURRENCE:$it")) }
}

private fun <T> List<T>.nextIndex(current: Int): Int = if (isEmpty()) 0 else (current + 1).mod(size)

private fun <T> List<T>.nextNullableIndex(current: Int): Int = if (isEmpty() || current >= lastIndex) -1 else current + 1

private fun <T> List<T>.swap(first: Int, second: Int): List<T> = if (first !in indices || second !in indices) {
    this
} else {
    toMutableList().also {
        val value = it[first]
        it[first] = it[second]
        it[second] = value
    }
}
