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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
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
import app.ledger.core.money.CurrencyCode
import app.ledger.core.money.JvmLegalTenderCurrencyCatalog
import app.ledger.core.money.LocaleCurrencyFormatter
import app.ledger.core.money.Money
import app.ledger.core.money.MoneyFormatRequest
import app.ledger.core.money.MoneyUiModel
import app.ledger.finance.application.JournalAccountCardUpdate
import app.ledger.finance.application.JournalBulkEditPatch
import app.ledger.finance.application.JournalBulkEditOptions
import app.ledger.finance.application.JournalBulkOption
import app.ledger.finance.application.JournalDetailView
import app.ledger.finance.application.JournalFieldUpdate
import app.ledger.finance.application.JournalRevisionView
import app.ledger.finance.application.JournalSelectionMode
import app.ledger.finance.application.JournalTransactionView
import app.ledger.finance.application.PurgeIneligibilityReason
import app.ledger.finance.domain.CategoryId
import app.ledger.finance.domain.DependencyPolicy
import app.ledger.finance.domain.DependencyResolution
import app.ledger.finance.domain.MerchantId
import app.ledger.finance.domain.ParticipantId
import app.ledger.finance.domain.PaymentCardId
import app.ledger.finance.domain.ProjectId
import app.ledger.finance.domain.RevisionAction
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
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun JournalDestination(
    screenId: String,
    encodedArguments: Map<String, String>,
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
        screenId == "JRN-007" -> DetailScreen(
            content,
            actions,
            encodedArguments["transactionId"]?.let { StableId.parse(it).getOrNull() },
        )
        screenId == "JRN-008" -> HistoryScreen(content, actions)
        screenId == "JRN-009" -> ComparisonScreen(content, actions)
        screenId == "JRN-010" -> DependenciesScreen(content, actions)
        screenId == "JRN-011" -> TrashScreen(pages, actions)
        screenId == "JRN-012" -> PurgeScreen(content, actions)
        else -> LedgerErrorState(UiErrorCode("JOURNAL_DESTINATION_UNKNOWN"), stringResource(R.string.p15_journal_error), actions.onRetry)
    }
}

private fun journalArgumentsValid(screenId: String, arguments: Map<String, String>): Boolean {
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
    var draft by remember(state.filter) {
        val rangeCurrency = state.filter.amountRange?.currency
        mutableStateOf(if (rangeCurrency == null) state.filter else state.filter.copy(currencies = state.filter.currencies + rangeCurrency))
    }
    val zoneId = remember(state.zoneId) { runCatching { ZoneId.of(state.zoneId) }.getOrDefault(ZoneId.of("UTC")) }
    val initialRangeCurrency = state.filter.amountRange?.currency
    var occurredFrom by remember(state.filter, zoneId) { mutableStateOf(state.filter.occurredFrom?.localInput(zoneId).orEmpty()) }
    var occurredThrough by remember(state.filter, zoneId) { mutableStateOf(state.filter.occurredThrough?.localInput(zoneId).orEmpty()) }
    var createdFrom by remember(state.filter, zoneId) { mutableStateOf(state.filter.createdFrom?.localInput(zoneId).orEmpty()) }
    var createdThrough by remember(state.filter, zoneId) { mutableStateOf(state.filter.createdThrough?.localInput(zoneId).orEmpty()) }
    var modifiedFrom by remember(state.filter, zoneId) { mutableStateOf(state.filter.modifiedFrom?.localInput(zoneId).orEmpty()) }
    var modifiedThrough by remember(state.filter, zoneId) { mutableStateOf(state.filter.modifiedThrough?.localInput(zoneId).orEmpty()) }
    var minimumAmount by remember(state.filter) { mutableStateOf(journalMinorToMajor(state.filter.amountRange?.minimumAccountMinor, initialRangeCurrency)) }
    var maximumAmount by remember(state.filter) { mutableStateOf(journalMinorToMajor(state.filter.amountRange?.maximumAccountMinor, initialRangeCurrency)) }
    val rangeCurrency = draft.currencies.singleOrNull()
    val parsedOccurredFrom = occurredFrom.toOptionalInstant(zoneId)
    val parsedOccurredThrough = occurredThrough.toOptionalInstant(zoneId)
    val parsedCreatedFrom = createdFrom.toOptionalInstant(zoneId)
    val parsedCreatedThrough = createdThrough.toOptionalInstant(zoneId)
    val parsedModifiedFrom = modifiedFrom.toOptionalInstant(zoneId)
    val parsedModifiedThrough = modifiedThrough.toOptionalInstant(zoneId)
    val parsedMinimum = minimumAmount.toOptionalMinor(rangeCurrency)
    val parsedMaximum = maximumAmount.toOptionalMinor(rangeCurrency)
    val amountCurrencyValid = minimumAmount.isBlank() && maximumAmount.isBlank() || rangeCurrency != null
    val rangesValid = listOf(parsedOccurredFrom, parsedOccurredThrough, parsedCreatedFrom, parsedCreatedThrough, parsedModifiedFrom, parsedModifiedThrough).all { it.valid } &&
        amountCurrencyValid && parsedMinimum.valid && parsedMaximum.valid &&
        validRange(parsedOccurredFrom.value, parsedOccurredThrough.value) && validRange(parsedCreatedFrom.value, parsedCreatedThrough.value) &&
        validRange(parsedModifiedFrom.value, parsedModifiedThrough.value) && validRange(parsedMinimum.value, parsedMaximum.value)
    val dimensions = listOf(
        FilterDimensionUiModel(stringResource(R.string.p15_journal_filter_type), draft.kinds.map { FilterChipUiModel("kind_${it.name}", it.label()) }),
        FilterDimensionUiModel(stringResource(R.string.p15_journal_filter_state), draft.lifecycleStates.map { FilterChipUiModel("state_${it.name}", it.label()) }),
        FilterDimensionUiModel(stringResource(R.string.p15_journal_filter_source), draft.sources.map { FilterChipUiModel("source_${it.name}", it.label()) }),
        FilterDimensionUiModel(stringResource(R.string.p15_journal_filter_context), contextFilterChips(draft, state.bulkOptions)),
        FilterDimensionUiModel(stringResource(R.string.p15_journal_filter_time), timeFilterChips(draft, zoneId)),
        FilterDimensionUiModel(stringResource(R.string.p15_journal_filter_amount), amountFilterChips(draft)),
    )
    LazyColumn(Modifier.fillMaxSize().testTag(LedgerTestTags.JOURNAL_SCREEN), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        item {
            FilterChoiceSection(stringResource(R.string.p15_journal_filter_type)) {
                TransactionKind.entries.forEach { value -> LedgerChoiceRow(value.label(), value in draft.kinds, { draft = draft.copy(kinds = draft.kinds.toggled(value)) }) }
            }
        }
        item {
            FilterChoiceSection(stringResource(R.string.p15_journal_filter_state)) {
                TransactionLifecycleState.entries.forEach { value -> LedgerChoiceRow(value.label(), value in draft.lifecycleStates, { draft = draft.copy(lifecycleStates = draft.lifecycleStates.toggled(value)) }) }
            }
        }
        item {
            FilterChoiceSection(stringResource(R.string.p15_journal_filter_source)) {
                TransactionSource.entries.forEach { value -> LedgerChoiceRow(value.label(), value in draft.sources, { draft = draft.copy(sources = draft.sources.toggled(value)) }) }
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
                LedgerTextField(minimumAmount, { minimumAmount = it.take(24) }, stringResource(R.string.p15_journal_minimum_minor), errorText = stringResource(R.string.p15_journal_invalid_number).takeIf { !parsedMinimum.valid })
                LedgerTextField(maximumAmount, { maximumAmount = it.take(24) }, stringResource(R.string.p15_journal_maximum_minor), errorText = stringResource(R.string.p15_journal_invalid_number).takeIf { !parsedMaximum.valid })
                if (!amountCurrencyValid) LedgerBanner(stringResource(R.string.p15_journal_amount_currency_required), LedgerBannerVariant.WARNING)
                state.bulkOptions.currencies.forEach { currency -> LedgerChoiceRow(currency.value, currency in draft.currencies, { draft = draft.copy(currencies = draft.currencies.toggled(currency)) }) }
                StatisticalNature.entries.forEach { nature -> LedgerChoiceRow(nature.label(), nature in draft.statisticalNatures, { draft = draft.copy(statisticalNatures = draft.statisticalNatures.toggled(nature)) }) }
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
                    minimumAmount = ""
                    maximumAmount = ""
                },
                onApply = {
                    if (rangesValid) {
                        val range = if (parsedMinimum.value != null || parsedMaximum.value != null) TransactionAmountRange(parsedMinimum.value, parsedMaximum.value, rangeCurrency) else null
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
    val zoneId = remember(state.zoneId) { runCatching { ZoneId.of(state.zoneId) }.getOrDefault(ZoneId.of("UTC")) }
    val parsedTime = occurredAt.toOptionalInstant(zoneId).value
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
            LedgerTextField(
                occurredAt,
                { occurredAt = it.take(40) },
                stringResource(R.string.p15_journal_iso_time),
                supportingText = stringResource(R.string.p15_journal_local_time_hint),
                enabled = timeEnabled,
                errorText = stringResource(R.string.p15_journal_invalid_time).takeIf { timeEnabled && parsedTime == null },
            )
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
            SelectorField(stringResource(R.string.p15_journal_bulk_nature), StatisticalNature.entries[natureIndex].label(), { natureIndex = (natureIndex + 1) % StatisticalNature.entries.size }, enabled = natureEnabled)
        }
        item {
            LedgerText(state.operation.label(), LedgerTextRole.SUPPORTING)
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
private fun DetailScreen(state: JournalLoadState.Content, actions: JournalActions, requestedTransactionId: StableId?) {
    val detail = state.detail
    if (detail == null) {
        if (state.detailLoading) {
            LedgerLoadingState(Modifier.fillMaxSize(), stringResource(R.string.p15_journal_loading))
        } else {
            LedgerErrorState(
                UiErrorCode(state.detailFailureCode ?: "JOURNAL_DETAIL_UNAVAILABLE"),
                stringResource(R.string.p15_journal_not_found),
                { requestedTransactionId?.let(actions.onLoadDetail) ?: actions.onRetry() },
                Modifier.testTag(LedgerTestTags.JOURNAL_SCREEN),
            )
        }
        return
    }
    val locale = LocalConfiguration.current.locales[0]
    LazyColumn(Modifier.fillMaxSize().testTag(LedgerTestTags.JOURNAL_SCREEN), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        item { JournalTransactionRow(detail.transaction.toUi(locale), {}, {}) }
        item { DetailSection(stringResource(R.string.p15_journal_user_input), detail.userInputLabels()) }
        item { DetailSection(stringResource(R.string.p15_journal_account_effects), detail.accountEffects.map { accountEffectLabel(it, locale) }) }
        item {
            DetailSection(
                stringResource(R.string.p15_journal_budget_semantics),
                listOf(
                    stringResource(if (detail.budgetSummary == null) R.string.p15_journal_budget_excluded else R.string.p15_journal_budget_included),
                    detail.statisticalNature?.let(::statisticalNatureFromName)?.label() ?: stringResource(R.string.p15_journal_nature_unspecified),
                ),
            )
        }
        item {
            DetailSection(
                stringResource(R.string.p15_journal_fx),
                detail.fxEvidence.map { stringResource(R.string.p15_journal_fx_rate, it.sourceCurrency.value, it.targetCurrency.value, it.rate, it.provider) },
            )
        }
        item { DetailSection(stringResource(R.string.p15_journal_relationships), detail.relationshipSummaries.map { relationshipLabel(it, locale) }) }
        item {
            FormSection(stringResource(R.string.p15_journal_attachments)) {
                if (detail.attachmentNames.isEmpty()) {
                    LedgerText("—", LedgerTextRole.SUPPORTING)
                } else {
                    detail.attachmentIds.zip(detail.attachmentNames).forEach { (attachmentId, displayName) ->
                        LedgerButton(
                            displayName,
                            { actions.onOpenAttachment(attachmentId) },
                            Modifier.fillMaxWidth(),
                            LedgerButtonVariant.TEXT,
                        )
                    }
                }
                if (detail.transaction.state == TransactionLifecycleState.ACTIVE && detail.transaction.kind in ORDINARY_KINDS) {
                    LedgerButton(
                        stringResource(R.string.p15_journal_manage_attachments),
                        { actions.onEdit(detail.transaction.transactionId, detail.transaction.kind) },
                        Modifier.fillMaxWidth(),
                        LedgerButtonVariant.SECONDARY,
                    )
                }
            }
        }
        item {
            DetailSection(
                stringResource(R.string.p15_journal_source),
                listOf(
                    sourceFromName(detail.sourceDescription)?.label() ?: stringResource(R.string.p15_journal_source_unknown),
                    stringResource(R.string.p15_journal_created_at, detail.createdAt.localized(detail.zoneId, locale)),
                    stringResource(R.string.p15_journal_modified_at, detail.modifiedAt.localized(detail.zoneId, locale)),
                ),
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                LedgerButton(stringResource(R.string.p15_journal_history), { actions.onNavigate("JRN-008", mapOf("transactionId" to detail.transaction.transactionId)) }, Modifier.weight(1f), LedgerButtonVariant.SECONDARY)
                LedgerButton(stringResource(R.string.p15_journal_dependencies), { actions.onNavigate("JRN-010", mapOf("transactionId" to detail.transaction.transactionId)) }, Modifier.weight(1f), LedgerButtonVariant.SECONDARY)
            }
        }
        if (detail.transaction.state == TransactionLifecycleState.ACTIVE) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                    LedgerButton(
                        stringResource(R.string.p15_journal_edit_transaction),
                        { actions.onEdit(detail.transaction.transactionId, detail.transaction.kind) },
                        Modifier.weight(1f),
                    )
                    if (detail.transaction.kind == TransactionKind.EXPENSE) {
                        LedgerButton(
                            stringResource(R.string.p15_journal_create_refund),
                            { actions.onNavigate("REC-015", mapOf("transactionId" to detail.transaction.transactionId)) },
                            Modifier.weight(1f),
                            LedgerButtonVariant.SECONDARY,
                        )
                    }
                }
            }
        }
        item {
            if (detail.transaction.state == TransactionLifecycleState.TRASHED) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                    LedgerButton(stringResource(R.string.p15_journal_restore), { actions.onRestore(detail.transaction.transactionId, detail.transaction.revisionId) }, Modifier.weight(1f))
                    LedgerButton(
                        stringResource(R.string.p15_journal_purge),
                        { actions.onNavigate("JRN-012", mapOf("transactionId" to detail.transaction.transactionId)) },
                        Modifier.weight(1f),
                        LedgerButtonVariant.DANGER,
                    )
                }
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

private val ORDINARY_KINDS = setOf(TransactionKind.EXPENSE, TransactionKind.INCOME)

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
                        LedgerText(stringResource(R.string.p15_journal_revision_title, version.revisionNumber, version.action.label()), LedgerTextRole.SECTION)
                        LedgerText(version.createdAt.localized(state.detail?.zoneId, LocalConfiguration.current.locales[0]), LedgerTextRole.SUPPORTING)
                        LedgerText(version.changedFields.map { changedFieldLabel(it) }.joinToString(), LedgerTextRole.BODY)
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
            DetailSection(stringResource(R.string.p15_journal_changed), comparison.changedFields.map { changedFieldLabel(it) })
            DetailSection(stringResource(R.string.p15_journal_unchanged), comparison.unchangedFields.map { changedFieldLabel(it) })
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
                        DetailSection(dependency.type.label(), listOf(stringResource(R.string.p15_journal_dependency_state, dependency.childState.label())))
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
            assessment.reasons.forEach { LedgerText(purgeReasonLabel(it), LedgerTextRole.BODY) }
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
    val locale = LocalConfiguration.current.locales[0]
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

@Composable
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
    val typeLabel = kind.label()
    val displayCategory = categoryOrType.takeUnless { it == kind.name }?.ifBlank { typeLabel } ?: typeLabel
    val displaySummary = summary.takeUnless { it == kind.name || it == displayCategory }.orEmpty()
    val displayBadges = badges.map { it.badgeLabel() }
    val accessible = listOf(typeLabel, displayCategory, displaySummary, accountAndCard, amount.fullAccessibleText, displayBadges.joinToString()).filter(String::isNotBlank).joinToString(". ")
    return JournalTransactionUiModel(transactionId.toString(), displayCategory, displaySummary, accountAndCard, amount, typeLabel, iconFor(kind), displayBadges, running, accessible)
}

private fun iconFor(kind: TransactionKind): LedgerIcon = when (kind) {
    TransactionKind.TRANSFER, TransactionKind.FX_EXCHANGE -> LedgerIcon.TRANSFER
    TransactionKind.REFUND -> LedgerIcon.REFUND
    else -> LedgerIcon.JOURNAL
}

@Composable
private fun TransactionKind.label(): String = stringResource(
    when (this) {
        TransactionKind.EXPENSE -> R.string.p15_journal_kind_expense
        TransactionKind.INCOME -> R.string.p15_journal_kind_income
        TransactionKind.TRANSFER -> R.string.p15_journal_kind_transfer
        TransactionKind.REFUND -> R.string.p15_journal_kind_refund
        TransactionKind.CREDIT_PAYMENT -> R.string.p15_journal_kind_credit_payment
        TransactionKind.LOAN_DISBURSEMENT -> R.string.p15_journal_kind_loan_disbursement
        TransactionKind.LOAN_PAYMENT -> R.string.p15_journal_kind_loan_payment
        TransactionKind.BALANCE_ADJUSTMENT -> R.string.p15_journal_kind_balance_adjustment
        TransactionKind.FX_EXCHANGE -> R.string.p15_journal_kind_fx_exchange
        TransactionKind.SETTLEMENT_PAYMENT -> R.string.p15_journal_kind_settlement_payment
        TransactionKind.OPENING_BALANCE -> R.string.p15_journal_kind_opening_balance
    },
)

@Composable
private fun TransactionLifecycleState.label(): String = stringResource(
    when (this) {
        TransactionLifecycleState.ACTIVE -> R.string.p15_journal_state_active
        TransactionLifecycleState.TRASHED -> R.string.p15_journal_state_trashed
    },
)

@Composable
private fun TransactionSource.label(): String = stringResource(
    when (this) {
        TransactionSource.MANUAL -> R.string.p15_journal_source_manual
        TransactionSource.QUICK_TEMPLATE -> R.string.p15_journal_source_quick_template
        TransactionSource.RECURRENCE_AUTO -> R.string.p15_journal_source_recurrence_auto
        TransactionSource.RECURRENCE_CANDIDATE -> R.string.p15_journal_source_recurrence_candidate
        TransactionSource.CSV_IMPORT -> R.string.p15_journal_source_csv_import
        TransactionSource.XLSX_IMPORT -> R.string.p15_journal_source_xlsx_import
        TransactionSource.STRUCTURED_IMPORT -> R.string.p15_journal_source_structured_import
        TransactionSource.SYSTEM_GENERATED -> R.string.p15_journal_source_system_generated
        TransactionSource.MERGE_RESTORE -> R.string.p15_journal_source_merge_restore
        TransactionSource.BATCH_OPERATION -> R.string.p15_journal_source_batch_operation
    },
)

@Composable
private fun StatisticalNature.label(): String = stringResource(
    when (this) {
        StatisticalNature.CONSUMPTION_EXPENSE -> R.string.p15_journal_nature_consumption_expense
        StatisticalNature.NON_CONSUMPTION_EXPENSE -> R.string.p15_journal_nature_non_consumption_expense
        StatisticalNature.REGULAR_INCOME -> R.string.p15_journal_nature_regular_income
        StatisticalNature.NON_RECURRING_INCOME -> R.string.p15_journal_nature_non_recurring_income
    },
)

@Composable
private fun RevisionAction.label(): String = stringResource(
    when (this) {
        RevisionAction.CREATE -> R.string.p15_journal_action_create
        RevisionAction.EDIT -> R.string.p15_journal_action_edit
        RevisionAction.MOVE_TO_TRASH -> R.string.p15_journal_action_move_to_trash
        RevisionAction.RESTORE -> R.string.p15_journal_action_restore
        RevisionAction.BULK_EDIT -> R.string.p15_journal_action_bulk_edit
        RevisionAction.DEPENDENCY_REWRITE -> R.string.p15_journal_action_dependency_rewrite
    },
)

@Composable
private fun TransactionDependencyType.label(): String = stringResource(
    when (this) {
        TransactionDependencyType.REFUND -> R.string.p15_journal_relation_refund
        TransactionDependencyType.INSTALLMENT_PLAN -> R.string.p15_journal_relation_installment
        TransactionDependencyType.CREDIT_STATEMENT -> R.string.p15_journal_relation_credit_statement
        TransactionDependencyType.LOAN_SCHEDULE -> R.string.p15_journal_relation_loan_schedule
        TransactionDependencyType.SETTLEMENT_ACTIVITY -> R.string.p15_journal_relation_settlement
        TransactionDependencyType.RECURRENCE_OCCURRENCE -> R.string.p15_journal_relation_recurrence
        TransactionDependencyType.ATTACHMENT_REFERENCE -> R.string.p15_journal_relation_attachment
    },
)

@Composable
private fun String.badgeLabel(): String = stringResource(
    when (this) {
        "attachment" -> R.string.p15_journal_badge_attachment
        "location" -> R.string.p15_journal_badge_location
        "refund" -> R.string.p15_journal_badge_refund
        "refunded" -> R.string.p15_journal_badge_refunded
        "installment" -> R.string.p15_journal_badge_installment
        else -> R.string.p15_journal_badge_related
    },
)

@Composable
private fun JournalDetailView.userInputLabels(): List<String> = buildList {
    amountExpression?.let { add(stringResource(R.string.p15_journal_input_amount, it)) }
    fullNote?.let { add(stringResource(R.string.p15_journal_input_note, it)) }
    merchantName?.let { add(stringResource(R.string.p15_journal_input_merchant, it)) }
    projectName?.let { add(stringResource(R.string.p15_journal_input_project, it)) }
    locationName?.let { add(stringResource(R.string.p15_journal_input_location, it)) }
}

@Composable
private fun accountEffectLabel(value: String, locale: Locale): String {
    val encoded = value.split('|')
    if (encoded.size == 4 && encoded[0] == "account-change") {
        val minor = encoded[2].toLongOrNull()
        val currency = currencyFromCode(encoded[3])
        if (minor != null && currency != null) {
            val amount = formattedMoney(kotlin.math.abs(minor), currency, locale)
            return stringResource(
                if (minor >= 0) R.string.p15_journal_account_increase else R.string.p15_journal_account_decrease,
                encoded[1],
                amount,
            )
        }
    }
    val legacy = LEGACY_ACCOUNT_EFFECT.matchEntire(value)
    if (legacy != null) {
        val currency = currencyFromCode(legacy.groupValues[4])
        val minor = legacy.groupValues[3].toLongOrNull()
        if (currency != null && minor != null) {
            return stringResource(R.string.p15_journal_account_change, legacy.groupValues[1], formattedMoney(kotlin.math.abs(minor), currency, locale))
        }
    }
    return stringResource(R.string.p15_journal_account_change_unavailable)
}

@Composable
private fun relationshipLabel(value: String, locale: Locale): String {
    REFUND_PROGRESS.find(value)?.let { match ->
        val gross = match.groupValues[1].toLongOrNull()
        val refunded = match.groupValues[2].toLongOrNull()
        val remaining = match.groupValues[3].toLongOrNull()
        val currency = currencyFromCode(match.groupValues[4])
        if (gross != null && refunded != null && remaining != null && currency != null) {
            return stringResource(
                R.string.p15_journal_refund_progress,
                formattedMoney(gross, currency, locale),
                formattedMoney(refunded, currency, locale),
                formattedMoney(remaining, currency, locale),
            )
        }
    }
    if (value.startsWith("refund.dates:")) return stringResource(R.string.p15_journal_refund_dates_retained)
    val dependency = value.substringBefore(':').let { raw -> TransactionDependencyType.entries.singleOrNull { it.name == raw } }
    return dependency?.let { stringResource(R.string.p15_journal_related_record, it.label()) }
        ?: stringResource(R.string.p15_journal_related_record_generic)
}

@Composable
private fun changedFieldLabel(value: String): String = stringResource(
    when (value) {
        "created" -> R.string.p15_journal_field_created
        "occurredAt" -> R.string.p15_journal_field_occurred_at
        "category" -> R.string.p15_journal_field_category
        "account" -> R.string.p15_journal_field_account
        "amount" -> R.string.p15_journal_field_amount
        "note" -> R.string.p15_journal_field_note
        "merchant" -> R.string.p15_journal_field_merchant
        "project" -> R.string.p15_journal_field_project
        "location" -> R.string.p15_journal_field_location
        "state" -> R.string.p15_journal_field_state
        else -> R.string.p15_journal_field_other
    },
)

@Composable
private fun purgeReasonLabel(reason: PurgeIneligibilityReason): String = stringResource(
    when (reason) {
        PurgeIneligibilityReason.NOT_TRASHED -> R.string.p15_journal_purge_reason_not_trashed
        PurgeIneligibilityReason.RETENTION_NOT_ELAPSED -> R.string.p15_journal_purge_reason_retention
        PurgeIneligibilityReason.ACCOUNT_NET_NON_ZERO -> R.string.p15_journal_purge_reason_account_net
        PurgeIneligibilityReason.BASE_NET_NON_ZERO -> R.string.p15_journal_purge_reason_base_net
        PurgeIneligibilityReason.EFFECT_NET_NON_ZERO -> R.string.p15_journal_purge_reason_effect_net
        PurgeIneligibilityReason.DEPENDENCIES_OPEN -> R.string.p15_journal_purge_reason_dependencies
        PurgeIneligibilityReason.OPERATION_REFERENCE -> R.string.p15_journal_purge_reason_operation
        PurgeIneligibilityReason.ATTACHMENTS_READ_BY_BACKUP -> R.string.p15_journal_purge_reason_backup
    },
)

private fun statisticalNatureFromName(value: String): StatisticalNature? = StatisticalNature.entries.singleOrNull { it.name == value }

private fun sourceFromName(value: String): TransactionSource? = TransactionSource.entries.singleOrNull { it.name == value }

private fun currencyFromCode(value: String): CurrencyCode? = when (val result = CurrencyCode.parse(value)) {
    is DomainResult.Success -> result.value
    is DomainResult.Failure -> null
}

private fun formattedMoney(minor: Long, currency: CurrencyCode, locale: Locale): String {
    val formatter = LocaleCurrencyFormatter(JvmLegalTenderCurrencyCatalog.create())
    return when (val result = formatter.format(MoneyFormatRequest(Money(minor, currency), locale, AmountSemantic.NEUTRAL, AmountVisibility.VISIBLE))) {
        is DomainResult.Success -> result.value.formatted
        is DomainResult.Failure -> "${currency.value} $minor"
    }
}

private fun Instant.localized(zoneId: String?, locale: Locale): String {
    val zone = runCatching { ZoneId.of(zoneId ?: "UTC") }.getOrDefault(ZoneId.of("UTC"))
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale).withZone(zone).format(this)
}

private val LEGACY_ACCOUNT_EFFECT = Regex("^(.+):(debit|credit):(-?\\d+) ([A-Z]{3})$")
private val REFUND_PROGRESS = Regex("gross=(-?\\d+):refunded=(-?\\d+):remaining=(-?\\d+):([A-Z]{3})")

private data class OptionalInput<T>(val value: T?, val valid: Boolean)

private fun Instant.localInput(zoneId: ZoneId): String = JOURNAL_LOCAL_TIME.format(atZone(zoneId))

private fun String.toOptionalInstant(zoneId: ZoneId): OptionalInput<Instant> = if (isBlank()) {
    OptionalInput(null, true)
} else {
    val parsed = runCatching { Instant.parse(trim()) }.getOrNull() ?: runCatching {
        LocalDateTime.parse(trim(), JOURNAL_LOCAL_TIME).atZone(zoneId).toInstant()
    }.getOrNull()
    OptionalInput(parsed, parsed != null)
}

private fun String.toOptionalMinor(currency: CurrencyCode?): OptionalInput<Long> = if (isBlank()) {
    OptionalInput(null, true)
} else {
    val fractionDigits = currency?.let { JvmLegalTenderCurrencyCatalog.create().find(it)?.fractionDigits }
    val parsed = fractionDigits?.let { digits ->
        runCatching {
            BigDecimal(trim().replace(',', '.'))
                .movePointRight(digits)
                .setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact()
        }.getOrNull()
    }
    OptionalInput(parsed, parsed != null)
}

private fun journalMinorToMajor(value: Long?, currency: CurrencyCode?): String = value?.let { minor ->
    val fractionDigits = currency?.let { JvmLegalTenderCurrencyCatalog.create().find(it)?.fractionDigits }
        ?: return@let minor.toString()
    BigDecimal.valueOf(minor, fractionDigits).stripTrailingZeros().toPlainString()
}.orEmpty()

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
        supportingText = stringResource(R.string.p15_journal_local_time_hint),
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

private fun contextFilterChips(filter: TransactionFilter, options: JournalBulkEditOptions): List<FilterChipUiModel> = buildList {
    filter.accountIds.forEach { id -> add(FilterChipUiModel("account_$id", options.accounts.labelFor(id.value))) }
    filter.cardIds.forEach { id -> add(FilterChipUiModel("card_$id", options.cards.labelFor(id.value))) }
    filter.categoryIds.forEach { id -> add(FilterChipUiModel("category_$id", options.categories.labelFor(id.value))) }
    filter.merchantIds.forEach { id -> add(FilterChipUiModel("merchant_$id", options.merchants.labelFor(id.value))) }
    filter.projectIds.forEach { id -> add(FilterChipUiModel("project_$id", options.projects.labelFor(id.value))) }
    filter.settlementActivityIds.forEach { id -> add(FilterChipUiModel("settlement_$id", options.settlementActivities.labelFor(id.value))) }
    filter.participantIds.forEach { id -> add(FilterChipUiModel("participant_$id", options.participants.labelFor(id.value))) }
    filter.geoRadius?.let { add(FilterChipUiModel("geo_radius", "${it.radiusMeters} m")) }
}

private fun List<JournalBulkOption>.labelFor(id: app.ledger.core.common.StableId): String = singleOrNull { it.id == id }?.label.orEmpty()

private fun timeFilterChips(filter: TransactionFilter, zoneId: ZoneId): List<FilterChipUiModel> = buildList {
    filter.occurredFrom?.let { add(FilterChipUiModel("occurred_from", it.localInput(zoneId))) }
    filter.occurredThrough?.let { add(FilterChipUiModel("occurred_through", it.localInput(zoneId))) }
    filter.createdFrom?.let { add(FilterChipUiModel("created_from", it.localInput(zoneId))) }
    filter.createdThrough?.let { add(FilterChipUiModel("created_through", it.localInput(zoneId))) }
    filter.modifiedFrom?.let { add(FilterChipUiModel("modified_from", it.localInput(zoneId))) }
    filter.modifiedThrough?.let { add(FilterChipUiModel("modified_through", it.localInput(zoneId))) }
}

@Composable
private fun amountFilterChips(filter: TransactionFilter): List<FilterChipUiModel> {
    val chips = mutableListOf<FilterChipUiModel>()
    filter.amountRange?.let {
        chips += FilterChipUiModel(
            "amount",
            "${journalMinorToMajor(it.minimumAccountMinor, it.currency)}–${journalMinorToMajor(it.maximumAccountMinor, it.currency)} ${it.currency?.value.orEmpty()}".trim(),
        )
    }
    filter.currencies.forEach { chips += FilterChipUiModel("currency_${it.value}", it.value) }
    filter.statisticalNatures.forEach { chips += FilterChipUiModel("nature_${it.name}", it.label()) }
    filter.includedInBudget?.let { chips += FilterChipUiModel("budget", filterFlagLabel(R.string.p15_journal_filter_budget, it)) }
    filter.hasAttachment?.let { chips += FilterChipUiModel("attachment", filterFlagLabel(R.string.p15_journal_filter_attachment, it)) }
    filter.isRefund?.let { chips += FilterChipUiModel("refund", filterFlagLabel(R.string.p15_journal_filter_refund, it)) }
    filter.hasInstallment?.let { chips += FilterChipUiModel("installment", filterFlagLabel(R.string.p15_journal_filter_installment, it)) }
    filter.generatedByRecurrence?.let { chips += FilterChipUiModel("recurrence", filterFlagLabel(R.string.p15_journal_filter_recurrence, it)) }
    return chips
}

@Composable
private fun filterFlagLabel(labelResource: Int, value: Boolean): String = stringResource(
    R.string.p15_journal_filter_flag_value,
    stringResource(labelResource),
    stringResource(if (value) R.string.p15_journal_yes else R.string.p15_journal_no),
)

@Composable
private fun JournalOperationState.label(): String = stringResource(
    when (this) {
        JournalOperationState.IDLE -> R.string.p15_journal_operation_idle
        JournalOperationState.VALIDATING -> R.string.p15_journal_operation_validating
        JournalOperationState.COMMITTING -> R.string.p15_journal_operation_committing
        JournalOperationState.FAILED -> R.string.p15_journal_operation_failed
        JournalOperationState.SUCCEEDED -> R.string.p15_journal_operation_succeeded
    },
)

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

private val JOURNAL_LOCAL_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
