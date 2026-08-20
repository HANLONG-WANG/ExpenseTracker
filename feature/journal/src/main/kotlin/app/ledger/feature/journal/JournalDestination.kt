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

import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import app.ledger.core.common.DomainResult
import app.ledger.core.common.getOrNull
import app.ledger.core.designsystem.DateTimeZoneField
import app.ledger.core.designsystem.FilterChipUiModel
import app.ledger.core.designsystem.FormSection
import app.ledger.core.designsystem.HighRiskConfirmation
import app.ledger.core.designsystem.JournalTransactionRow
import app.ledger.core.designsystem.JournalTransactionUiModel
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerButton
import app.ledger.core.designsystem.LedgerButtonVariant
import app.ledger.core.designsystem.LedgerCard
import app.ledger.core.designsystem.LedgerChip
import app.ledger.core.designsystem.LedgerChoiceRow
import app.ledger.core.designsystem.LedgerEmptyState
import app.ledger.core.designsystem.LedgerErrorState
import app.ledger.core.designsystem.LedgerIcon
import app.ledger.core.designsystem.LedgerDateTimePickerFlow
import app.ledger.core.designsystem.LedgerIconView
import app.ledger.core.designsystem.LedgerLoadingState
import app.ledger.core.designsystem.LedgerProgressIndicator
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextField
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.LedgerToggleRow
import app.ledger.core.designsystem.SearchField
import app.ledger.core.designsystem.SelectorField
import app.ledger.core.designsystem.UiErrorCode
import app.ledger.core.money.AmountSemantic
import app.ledger.core.money.AmountVisibility
import app.ledger.core.money.CurrencyCode
import app.ledger.core.money.JvmLegalTenderCurrencyCatalog
import app.ledger.core.money.LocaleCurrencyFormatter
import app.ledger.core.money.LocaleNumberFormatter
import app.ledger.core.money.Money
import app.ledger.core.money.MoneyFormatRequest
import app.ledger.core.money.MoneyUiModel
import app.ledger.finance.application.JournalAccountCardUpdate
import app.ledger.finance.application.JournalBulkEditPatch
import app.ledger.finance.application.JournalDetailView
import app.ledger.finance.application.JournalFieldUpdate
import app.ledger.finance.application.JournalRevisionView
import app.ledger.finance.application.JournalSelectionMode
import app.ledger.finance.application.JournalTransactionView
import app.ledger.finance.application.PurgeIneligibilityReason
import app.ledger.finance.domain.CategoryId
import app.ledger.finance.domain.DependencyPolicy
import app.ledger.finance.domain.DependencyResolution
import app.ledger.finance.domain.GeoPoint
import app.ledger.finance.domain.GeoRadiusFilter
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
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun JournalDestination(
    screenId: String,
    encodedArguments: Map<String, String>,
    state: JournalLoadState,
    pages: Flow<PagingData<JournalTransactionView>>,
    actions: JournalActions,
) {
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
        screenId == "JRN-011" -> TrashScreen(content, pages, actions)
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
            LedgerButton(stringResource(R.string.p15_journal_saved_filters), { actions.onNavigate("JRN-004", emptyMap()) }, Modifier.weight(1f), LedgerButtonVariant.TEXT)
            LedgerButton(stringResource(R.string.p15_journal_trash), {
                actions.onApplyFilter(state.filter.copy(lifecycleStates = setOf(TransactionLifecycleState.TRASHED)))
                actions.onNavigate("JRN-011", emptyMap())
            }, Modifier.weight(1f), LedgerButtonVariant.TEXT)
        }
        ActiveJournalFilterRow(state, actions)
        PagedJournalList(pages, actions, showRunningBalance = false)
    }
}

@Composable
private fun ActiveJournalFilterRow(state: JournalLoadState.Content, actions: JournalActions) {
    val chips = buildList {
        state.activePresetId?.let { presetId ->
            state.presets.singleOrNull { it.id == presetId }?.let { preset ->
                add(FilterChipUiModel("preset_$presetId", preset.name))
            }
        }
        state.filter.kinds.forEach { add(FilterChipUiModel("kind_${it.name}", it.label())) }
        state.filter.lifecycleStates.forEach { add(FilterChipUiModel("state_${it.name}", it.label())) }
        state.filter.sources.forEach { add(FilterChipUiModel("source_${it.name}", it.label())) }
        state.filter.accountIds.forEach { id -> add(FilterChipUiModel("account_$id", state.bulkOptions.accounts.singleOrNull { it.id == id.value }?.label ?: stringResource(R.string.p15_journal_filter_account))) }
        state.filter.cardIds.forEach { id -> add(FilterChipUiModel("card_$id", state.bulkOptions.cards.singleOrNull { it.id == id.value }?.label ?: stringResource(R.string.p15_journal_bulk_card))) }
        state.filter.categoryIds.forEach { id -> add(FilterChipUiModel("category_$id", state.bulkOptions.categories.singleOrNull { it.id == id.value }?.label ?: stringResource(R.string.p15_journal_bulk_category))) }
        state.filter.merchantIds.forEach { id -> add(FilterChipUiModel("merchant_$id", state.bulkOptions.merchants.singleOrNull { it.id == id.value }?.label ?: stringResource(R.string.p15_journal_bulk_merchant))) }
        state.filter.projectIds.forEach { id -> add(FilterChipUiModel("project_$id", state.bulkOptions.projects.singleOrNull { it.id == id.value }?.label ?: stringResource(R.string.p15_journal_bulk_project))) }
        state.filter.settlementActivityIds.forEach { id -> add(FilterChipUiModel("settlement_$id", state.bulkOptions.settlementActivities.singleOrNull { it.id == id.value }?.label ?: stringResource(R.string.p15_journal_filter_settlement))) }
        state.filter.participantIds.forEach { id -> add(FilterChipUiModel("participant_$id", state.bulkOptions.participants.singleOrNull { it.id == id.value }?.label ?: stringResource(R.string.p15_journal_filter_participant))) }
        state.filter.occurredFrom?.let { add(FilterChipUiModel("occurred_from", stringResource(R.string.p15_journal_occurred_from))) }
        state.filter.occurredThrough?.let { add(FilterChipUiModel("occurred_through", stringResource(R.string.p15_journal_occurred_through))) }
        state.filter.createdFrom?.let { add(FilterChipUiModel("created_from", stringResource(R.string.p15_journal_created_from))) }
        state.filter.createdThrough?.let { add(FilterChipUiModel("created_through", stringResource(R.string.p15_journal_created_through))) }
        state.filter.modifiedFrom?.let { add(FilterChipUiModel("modified_from", stringResource(R.string.p15_journal_modified_from))) }
        state.filter.modifiedThrough?.let { add(FilterChipUiModel("modified_through", stringResource(R.string.p15_journal_modified_through))) }
        state.filter.currencies.forEach { add(FilterChipUiModel("currency_${it.value}", it.value)) }
        state.filter.statisticalNatures.forEach { add(FilterChipUiModel("nature_${it.name}", it.label())) }
        if (state.filter.amountRange != null) add(FilterChipUiModel("amount", stringResource(R.string.p15_journal_filter_amount)))
        state.filter.geoRadius?.let { add(FilterChipUiModel("geo_radius", stringResource(R.string.p15_journal_filter_radius_value, it.radiusMeters))) }
        if (state.filter.hasAttachment != null) add(FilterChipUiModel("attachment", stringResource(R.string.p15_journal_filter_attachment)))
        if (state.filter.isRefund != null) add(FilterChipUiModel("refund", stringResource(R.string.p15_journal_filter_refund)))
        if (state.filter.hasInstallment != null) add(FilterChipUiModel("installment", stringResource(R.string.p15_journal_filter_installment)))
        if (state.filter.includedInBudget != null) add(FilterChipUiModel("budget", stringResource(R.string.p15_journal_filter_budget)))
        if (state.filter.generatedByRecurrence != null) add(FilterChipUiModel("recurrence", stringResource(R.string.p15_journal_filter_recurrence)))
    }
    if (chips.isEmpty()) return
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
        chips.forEach { chip ->
            LedgerChip(
                chip.label,
                onClick = {
                    if (chip.stableKey.startsWith("preset_")) actions.onNavigate("JRN-004", emptyMap()) else actions.onRemoveFilter(chip.stableKey)
                },
                selected = true,
            )
        }
    }
}

@Composable
private fun JournalSearchScreen(state: JournalLoadState.Content, pages: Flow<PagingData<JournalTransactionView>>, actions: JournalActions) {
    Column(Modifier.fillMaxSize().testTag(LedgerTestTags.JOURNAL_SCREEN)) {
        SearchField(state.searchText, actions.onSearch, onClear = { actions.onSearch("") }, onFilter = { actions.onNavigate("JRN-003", emptyMap()) })
        LedgerText(filterSummary(state.filter.copy(searchText = null)), LedgerTextRole.SUPPORTING)
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
    var occurredFrom by remember(state.filter) { mutableStateOf(state.filter.occurredFrom) }
    var occurredThrough by remember(state.filter) { mutableStateOf(state.filter.occurredThrough) }
    var createdFrom by remember(state.filter) { mutableStateOf(state.filter.createdFrom) }
    var createdThrough by remember(state.filter) { mutableStateOf(state.filter.createdThrough) }
    var modifiedFrom by remember(state.filter) { mutableStateOf(state.filter.modifiedFrom) }
    var modifiedThrough by remember(state.filter) { mutableStateOf(state.filter.modifiedThrough) }
    var activeTimeField by remember { mutableStateOf<JournalFilterTimeField?>(null) }
    val locale = LocalLocale.current.platformLocale
    val zoneId = LedgerTheme.timeZone
    val dateTimeFormatter = remember(locale, zoneId) { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(zoneId) }
    val currencyCatalog = remember { JvmLegalTenderCurrencyCatalog.create() }
    val initialAmountCurrency = state.filter.amountRange?.currency ?: state.filter.currencies.singleOrNull() ?: state.bulkOptions.currencies.firstOrNull()
    var amountCurrency by remember(state.filter) { mutableStateOf(initialAmountCurrency) }
    var minimumMajor by remember(state.filter, initialAmountCurrency) { mutableStateOf(state.filter.amountRange?.minimumAccountMinor.toMajorInput(initialAmountCurrency, currencyCatalog)) }
    var maximumMajor by remember(state.filter, initialAmountCurrency) { mutableStateOf(state.filter.amountRange?.maximumAccountMinor.toMajorInput(initialAmountCurrency, currencyCatalog)) }
    var geoEnabled by remember(state.filter) { mutableStateOf(state.filter.geoRadius != null) }
    var latitude by remember(state.filter) { mutableStateOf(state.filter.geoRadius?.center?.latitudeE7?.toCoordinateInput().orEmpty()) }
    var longitude by remember(state.filter) { mutableStateOf(state.filter.geoRadius?.center?.longitudeE7?.toCoordinateInput().orEmpty()) }
    var radiusMeters by remember(state.filter) { mutableStateOf(state.filter.geoRadius?.radiusMeters?.toString().orEmpty()) }
    val parsedMinimum = minimumMajor.toOptionalMinor(amountCurrency, currencyCatalog)
    val parsedMaximum = maximumMajor.toOptionalMinor(amountCurrency, currencyCatalog)
    val parsedGeo = parseGeoRadius(latitude, longitude, radiusMeters)
    val rangesValid = parsedMinimum.valid && parsedMaximum.valid && (!geoEnabled || parsedGeo.valid) &&
        validRange(occurredFrom, occurredThrough) && validRange(createdFrom, createdThrough) &&
        validRange(modifiedFrom, modifiedThrough) && validRange(parsedMinimum.value, parsedMaximum.value)
    val previewFilter = if (rangesValid) {
        draft.copy(
            occurredFrom = occurredFrom,
            occurredThrough = occurredThrough,
            createdFrom = createdFrom,
            createdThrough = createdThrough,
            modifiedFrom = modifiedFrom,
            modifiedThrough = modifiedThrough,
            amountRange = if (parsedMinimum.value != null || parsedMaximum.value != null) TransactionAmountRange(parsedMinimum.value, parsedMaximum.value, amountCurrency) else null,
            geoRadius = parsedGeo.value.takeIf { geoEnabled },
        )
    } else {
        draft
    }
    val resetFilter = {
        draft = TransactionFilter()
        occurredFrom = null
        occurredThrough = null
        createdFrom = null
        createdThrough = null
        modifiedFrom = null
        modifiedThrough = null
        minimumMajor = ""
        maximumMajor = ""
        amountCurrency = state.bulkOptions.currencies.firstOrNull()
        geoEnabled = false
        latitude = ""
        longitude = ""
        radiusMeters = ""
    }
    val applyFilter = {
        if (rangesValid) {
            val range = if (parsedMinimum.value != null || parsedMaximum.value != null) TransactionAmountRange(parsedMinimum.value, parsedMaximum.value, amountCurrency) else null
            actions.onApplyFilter(
                draft.copy(
                    occurredFrom = occurredFrom,
                    occurredThrough = occurredThrough,
                    createdFrom = createdFrom,
                    createdThrough = createdThrough,
                    modifiedFrom = modifiedFrom,
                    modifiedThrough = modifiedThrough,
                    amountRange = range,
                    geoRadius = parsedGeo.value.takeIf { geoEnabled },
                ),
            )
        }
    }
    Column(Modifier.fillMaxSize().testTag(LedgerTestTags.JOURNAL_SCREEN)) {
        LedgerBanner(filterSummary(previewFilter), LedgerBannerVariant.INFO)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
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
                FilterInstantField(occurredFrom, stringResource(R.string.p15_journal_occurred_from), dateTimeFormatter, zoneId, { activeTimeField = JournalFilterTimeField.OCCURRED_FROM }, { occurredFrom = null })
                FilterInstantField(occurredThrough, stringResource(R.string.p15_journal_occurred_through), dateTimeFormatter, zoneId, { activeTimeField = JournalFilterTimeField.OCCURRED_THROUGH }, { occurredThrough = null })
                FilterInstantField(createdFrom, stringResource(R.string.p15_journal_created_from), dateTimeFormatter, zoneId, { activeTimeField = JournalFilterTimeField.CREATED_FROM }, { createdFrom = null })
                FilterInstantField(createdThrough, stringResource(R.string.p15_journal_created_through), dateTimeFormatter, zoneId, { activeTimeField = JournalFilterTimeField.CREATED_THROUGH }, { createdThrough = null })
                FilterInstantField(modifiedFrom, stringResource(R.string.p15_journal_modified_from), dateTimeFormatter, zoneId, { activeTimeField = JournalFilterTimeField.MODIFIED_FROM }, { modifiedFrom = null })
                FilterInstantField(modifiedThrough, stringResource(R.string.p15_journal_modified_through), dateTimeFormatter, zoneId, { activeTimeField = JournalFilterTimeField.MODIFIED_THROUGH }, { modifiedThrough = null })
            }
        }
        item {
            FormSection(stringResource(R.string.p15_journal_filter_amount)) {
                LedgerTextField(minimumMajor, { minimumMajor = it.take(24) }, stringResource(R.string.p15_journal_minimum_amount), errorText = stringResource(R.string.p15_journal_invalid_amount).takeIf { !parsedMinimum.valid })
                LedgerTextField(maximumMajor, { maximumMajor = it.take(24) }, stringResource(R.string.p15_journal_maximum_amount), errorText = stringResource(R.string.p15_journal_invalid_amount).takeIf { !parsedMaximum.valid })
                state.bulkOptions.currencies.forEach { currency ->
                    LedgerChoiceRow(currency.value, currency in draft.currencies, {
                        draft = draft.copy(currencies = draft.currencies.toggled(currency))
                        amountCurrency = currency
                    })
                }
                StatisticalNature.entries.forEach { nature -> LedgerChoiceRow(nature.label(), nature in draft.statisticalNatures, { draft = draft.copy(statisticalNatures = draft.statisticalNatures.toggled(nature)) }) }
            }
        }
        item {
            FormSection(stringResource(R.string.p15_journal_filter_location)) {
                LedgerToggleRow(stringResource(R.string.p15_journal_filter_radius_enabled), geoEnabled, { geoEnabled = it })
                LedgerTextField(latitude, { latitude = it.take(16) }, stringResource(R.string.p15_journal_filter_latitude), enabled = geoEnabled, errorText = stringResource(R.string.p15_journal_invalid_location).takeIf { geoEnabled && !parsedGeo.valid })
                LedgerTextField(longitude, { longitude = it.take(17) }, stringResource(R.string.p15_journal_filter_longitude), enabled = geoEnabled)
                LedgerTextField(radiusMeters, { radiusMeters = it.filter(Char::isDigit).take(7) }, stringResource(R.string.p15_journal_filter_radius), enabled = geoEnabled)
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
        if (!rangesValid) item { LedgerBanner(stringResource(R.string.p15_journal_filter_invalid_range), LedgerBannerVariant.DANGER) }
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
        LedgerCard(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(LedgerTheme.spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs, Alignment.End),
            ) {
                LedgerButton(stringResource(R.string.p15_journal_reset), resetFilter, variant = LedgerButtonVariant.SECONDARY)
                LedgerButton(stringResource(R.string.p15_journal_apply_filter), applyFilter, enabled = rangesValid)
            }
        }
    }
    activeTimeField?.let { field ->
        val current = when (field) {
            JournalFilterTimeField.OCCURRED_FROM -> occurredFrom
            JournalFilterTimeField.OCCURRED_THROUGH -> occurredThrough
            JournalFilterTimeField.CREATED_FROM -> createdFrom
            JournalFilterTimeField.CREATED_THROUGH -> createdThrough
            JournalFilterTimeField.MODIFIED_FROM -> modifiedFrom
            JournalFilterTimeField.MODIFIED_THROUGH -> modifiedThrough
        } ?: Instant.now()
        val local = current.atZone(zoneId)
        LedgerDateTimePickerFlow(
            local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            local.hour,
            local.minute,
            onConfirm = { dateMillis, hour, minute ->
                val selectedDate = Instant.ofEpochMilli(dateMillis).atZone(ZoneOffset.UTC).toLocalDate()
                val selected = selectedDate.atTime(hour, minute).atZone(zoneId).toInstant()
                when (field) {
                    JournalFilterTimeField.OCCURRED_FROM -> occurredFrom = selected
                    JournalFilterTimeField.OCCURRED_THROUGH -> occurredThrough = selected
                    JournalFilterTimeField.CREATED_FROM -> createdFrom = selected
                    JournalFilterTimeField.CREATED_THROUGH -> createdThrough = selected
                    JournalFilterTimeField.MODIFIED_FROM -> modifiedFrom = selected
                    JournalFilterTimeField.MODIFIED_THROUGH -> modifiedThrough = selected
                }
                activeTimeField = null
            },
            onDismiss = { activeTimeField = null },
        )
    }
}

@Composable
private fun SavedFiltersScreen(state: JournalLoadState.Content, actions: JournalActions) {
    if (state.presets.isEmpty()) {
        LedgerEmptyState(stringResource(R.string.p15_journal_saved_filters), stringResource(R.string.p15_journal_no_saved_filters), stringResource(R.string.p15_journal_back_to_journal), actions.onBack, Modifier.testTag(LedgerTestTags.JOURNAL_SCREEN))
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
        Box(Modifier.weight(1f)) {
            PagedJournalList(pages, actions, false, selectionMode = true)
        }
        LedgerCard(Modifier.fillMaxWidth()) {
            LedgerButton(
                stringResource(R.string.p15_journal_bulk_edit),
                { actions.onNavigate("JRN-006", emptyMap()) },
                Modifier.fillMaxWidth().padding(LedgerTheme.spacing.xs),
                enabled = selection != null,
            )
        }
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
    var occurredAt by remember { mutableStateOf<Instant?>(null) }
    var showTimePicker by remember { mutableStateOf(false) }
    var noteEnabled by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var budgetEnabled by remember { mutableStateOf(false) }
    var includedInBudget by remember { mutableStateOf(true) }
    var natureEnabled by remember { mutableStateOf(false) }
    var natureIndex by remember { mutableStateOf(0) }
    val selectedAccount = options.accounts.getOrNull(accountIndex)
    val compatibleCards = options.cards.filter { it.parentId == selectedAccount?.id }
    val selectedCard = compatibleCards.getOrNull(cardIndex)
    val locale = LocalLocale.current.platformLocale
    val zoneId = LedgerTheme.timeZone
    val timeFormatter = remember(locale, zoneId) { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(zoneId) }
    val anyChange = categoryEnabled || accountEnabled || merchantEnabled || projectEnabled || timeEnabled || noteEnabled || budgetEnabled || natureEnabled
    val inputValid = state.selection != null && anyChange && (!categoryEnabled || options.categories.isNotEmpty()) &&
        (!accountEnabled || selectedAccount != null) && (!timeEnabled || occurredAt != null)
    val changeSummary = buildList {
        if (categoryEnabled) add(stringResource(R.string.p15_journal_bulk_category))
        if (accountEnabled) add(stringResource(R.string.p15_journal_bulk_account))
        if (merchantEnabled) add(stringResource(R.string.p15_journal_bulk_merchant))
        if (projectEnabled) add(stringResource(R.string.p15_journal_bulk_project))
        if (timeEnabled) add(stringResource(R.string.p15_journal_bulk_time))
        if (noteEnabled) add(stringResource(R.string.p15_journal_bulk_note))
        if (budgetEnabled) add(stringResource(R.string.p15_journal_bulk_budget))
        if (natureEnabled) add(stringResource(R.string.p15_journal_bulk_nature))
    }
    val submit = {
        actions.onBulkEdit(
            JournalBulkEditPatch(
                categoryId = if (categoryEnabled) JournalFieldUpdate.Set(options.categories[categoryIndex].id) else JournalFieldUpdate.Unchanged,
                accountAndCard = if (accountEnabled) JournalFieldUpdate.Set(JournalAccountCardUpdate(checkNotNull(selectedAccount).id, selectedCard?.id)) else JournalFieldUpdate.Unchanged,
                merchantId = if (merchantEnabled) options.merchants.getOrNull(merchantIndex)?.let { JournalFieldUpdate.Set(it.id) } ?: JournalFieldUpdate.Clear else JournalFieldUpdate.Unchanged,
                projectId = if (projectEnabled) options.projects.getOrNull(projectIndex)?.let { JournalFieldUpdate.Set(it.id) } ?: JournalFieldUpdate.Clear else JournalFieldUpdate.Unchanged,
                occurredAt = if (timeEnabled) JournalFieldUpdate.Set(checkNotNull(occurredAt)) else JournalFieldUpdate.Unchanged,
                note = if (noteEnabled) note.trim().takeIf(String::isNotEmpty)?.let { JournalFieldUpdate.Set(it) } ?: JournalFieldUpdate.Clear else JournalFieldUpdate.Unchanged,
                includedInBudget = if (budgetEnabled) JournalFieldUpdate.Set(includedInBudget) else JournalFieldUpdate.Unchanged,
                statisticalNature = if (natureEnabled) JournalFieldUpdate.Set(StatisticalNature.entries[natureIndex]) else JournalFieldUpdate.Unchanged,
            ),
        )
    }
    Column(Modifier.fillMaxSize().testTag(LedgerTestTags.JOURNAL_SCREEN)) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        item { LedgerBanner(stringResource(R.string.p15_journal_bulk_forbidden), LedgerBannerVariant.WARNING) }
        item {
            FormSection(stringResource(R.string.p15_journal_change_summary)) {
                if (changeSummary.isEmpty()) LedgerText(stringResource(R.string.p15_journal_no_changes_selected), LedgerTextRole.SUPPORTING)
                else changeSummary.forEach { LedgerText(it, LedgerTextRole.BODY) }
            }
        }
        item { BulkSelector(stringResource(R.string.p15_journal_bulk_category), categoryEnabled, { categoryEnabled = it }, options.categories.getOrNull(categoryIndex)?.label.orEmpty(), { categoryIndex = options.categories.nextIndex(categoryIndex) }, options.categories.isNotEmpty()) }
        item {
            BulkSelector(stringResource(R.string.p15_journal_bulk_account), accountEnabled, { accountEnabled = it }, selectedAccount?.label.orEmpty(), {
                accountIndex = options.accounts.nextIndex(accountIndex)
                cardIndex = -1
            }, options.accounts.isNotEmpty())
        }
        item {
            SelectorField(
                stringResource(R.string.p15_journal_bulk_card),
                selectedCard?.label ?: stringResource(R.string.p15_journal_clear_value),
                { cardIndex = compatibleCards.nextNullableIndex(cardIndex) },
                enabled = accountEnabled && compatibleCards.isNotEmpty(),
            )
        }
        item { BulkSelector(stringResource(R.string.p15_journal_bulk_merchant), merchantEnabled, { merchantEnabled = it }, options.merchants.getOrNull(merchantIndex)?.label ?: stringResource(R.string.p15_journal_clear_value), { merchantIndex = options.merchants.nextNullableIndex(merchantIndex) }, true) }
        item { BulkSelector(stringResource(R.string.p15_journal_bulk_project), projectEnabled, { projectEnabled = it }, options.projects.getOrNull(projectIndex)?.label ?: stringResource(R.string.p15_journal_clear_value), { projectIndex = options.projects.nextNullableIndex(projectIndex) }, true) }
        item {
            LedgerChoiceRow(stringResource(R.string.p15_journal_bulk_time), timeEnabled, { timeEnabled = !timeEnabled })
            if (timeEnabled) {
                DateTimeZoneField(
                    stringResource(R.string.p15_journal_bulk_time),
                    occurredAt?.let(timeFormatter::format) ?: stringResource(R.string.p15_journal_choose_time),
                    zoneId.id,
                    { showTimePicker = true },
                    zoneIsDifferent = true,
                )
            } else {
                SelectorField(
                    stringResource(R.string.p15_journal_bulk_time),
                    stringResource(R.string.p15_journal_choose_time),
                    {},
                    enabled = false,
                )
            }
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
        if (state.operation != JournalOperationState.IDLE) item {
            LedgerBanner(
                state.operation.label(),
                when (state.operation) {
                    JournalOperationState.FAILED -> LedgerBannerVariant.DANGER
                    JournalOperationState.SUCCEEDED -> LedgerBannerVariant.INFO
                    else -> LedgerBannerVariant.WARNING
                },
            )
        }
        }
        LedgerCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(LedgerTheme.spacing.xs), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            LedgerText(
                if (changeSummary.isEmpty()) stringResource(R.string.p15_journal_no_changes_selected) else changeSummary.joinToString(),
                LedgerTextRole.SUPPORTING,
            )
            LedgerButton(
                stringResource(R.string.p15_journal_apply_atomically),
                submit,
                Modifier.fillMaxWidth(),
                enabled = inputValid && state.operation !in setOf(JournalOperationState.VALIDATING, JournalOperationState.COMMITTING),
            )
            }
        }
    }
    if (showTimePicker) {
        val local = (occurredAt ?: Instant.now()).atZone(zoneId)
        LedgerDateTimePickerFlow(
            local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            local.hour,
            local.minute,
            onConfirm = { dateMillis, hour, minute ->
                val date = Instant.ofEpochMilli(dateMillis).atZone(ZoneOffset.UTC).toLocalDate()
                occurredAt = date.atTime(hour, minute).atZone(zoneId).toInstant()
                showTimePicker = false
            },
            onDismiss = { showTimePicker = false },
        )
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
    val locale = LocalLocale.current.platformLocale
    val formatter = remember(locale) { LocaleCurrencyFormatter(JvmLegalTenderCurrencyCatalog.create()) }
    val unavailableAmount = stringResource(R.string.p15_journal_amount_unavailable)
    val kindLabel = detail.transaction.kind.label()
    val dateTimeFormatter = remember(locale, detail.zoneId) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(java.time.ZoneId.of(detail.zoneId))
    }
    val transactionRow = remember(detail.transaction, locale, kindLabel, unavailableAmount) {
        detail.transaction.toUi(locale, formatter, kindLabel, unavailableAmount)
    }
    LazyColumn(Modifier.fillMaxSize().testTag(LedgerTestTags.JOURNAL_SCREEN), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        item { JournalTransactionRow(transactionRow, {}, null, enabled = false) }
        item {
            FormSection(stringResource(R.string.p15_journal_status)) {
                LedgerChip(detail.transaction.state.label(), {}, selected = true, enabled = false)
                LedgerText(detail.transaction.source.label(), LedgerTextRole.SUPPORTING)
            }
        }
        item { DetailSection(stringResource(R.string.p15_journal_user_input), listOfNotNull(detail.amountExpression, detail.fullNote, detail.merchantName, detail.projectName)) }
        item { DetailSection(stringResource(R.string.p15_journal_location), listOfNotNull(detail.locationName)) }
        item {
            DetailSection(
                stringResource(R.string.p15_journal_account_effects),
                detail.accountEffects.map { accountEffectLabel(it, locale) },
            )
        }
        item { DetailSection(stringResource(R.string.p15_journal_budget_semantics), listOfNotNull(detail.budgetSummary, detail.statisticalNature?.localizedNature())) }
        item { DetailSection(stringResource(R.string.p15_journal_fx), detail.fxEvidence.flatMap { it.localizedEvidence(locale, dateTimeFormatter) }) }
        item { DetailSection(stringResource(R.string.p15_journal_relationships), detail.relationshipSummaries) }
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
                        { actions.onEditById(detail.transaction.transactionId, detail.transaction.kind) },
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
                    detail.transaction.source.label(),
                    stringResource(R.string.p15_journal_created_at, dateTimeFormatter.format(detail.createdAt)),
                    stringResource(R.string.p15_journal_modified_at, dateTimeFormatter.format(detail.modifiedAt)),
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
                        { actions.onEditById(detail.transaction.transactionId, detail.transaction.kind) },
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
            FormSection(stringResource(R.string.p15_journal_history_preview)) {
                state.history.take(HISTORY_PREVIEW_LIMIT).forEach { revision ->
                    LedgerText(stringResource(R.string.p15_journal_revision_title, revision.revisionNumber, revision.action.label()), LedgerTextRole.BODY)
                    LedgerText(dateTimeFormatter.format(revision.createdAt), LedgerTextRole.SUPPORTING)
                }
                if (state.history.isEmpty()) LedgerText(stringResource(R.string.p15_journal_history_empty), LedgerTextRole.SUPPORTING)
            }
        }
        if (detail.transaction.state != TransactionLifecycleState.TRASHED) {
            item {
                FormSection(stringResource(R.string.p15_journal_actions)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                        LedgerButton(stringResource(R.string.p15_journal_edit), { actions.onEdit(detail.transaction) }, Modifier.weight(1f), LedgerButtonVariant.SECONDARY)
                        LedgerButton(stringResource(R.string.p15_journal_refund_action), { actions.onRefund(detail.transaction.transactionId) }, Modifier.weight(1f), LedgerButtonVariant.SECONDARY)
                    }
                    LedgerButton(stringResource(R.string.p15_journal_copy_as_template), { actions.onCopyTemplate(detail.transaction.transactionId) }, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY)
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
            return stringResource(
                R.string.p15_journal_account_change,
                legacy.groupValues[1],
                formattedMoney(kotlin.math.abs(minor), currency, locale),
            )
        }
    }
    return stringResource(R.string.p15_journal_account_change_unavailable)
}

private fun currencyFromCode(value: String): CurrencyCode? = when (val result = CurrencyCode.parse(value)) {
    is DomainResult.Success -> result.value
    is DomainResult.Failure -> null
}

private fun formattedMoney(minor: Long, currency: CurrencyCode, locale: Locale): String {
    val formatter = LocaleCurrencyFormatter(JvmLegalTenderCurrencyCatalog.create())
    return when (
        val result = formatter.format(
            MoneyFormatRequest(Money(minor, currency), locale, AmountSemantic.NEUTRAL, AmountVisibility.VISIBLE),
        )
    ) {
        is DomainResult.Success -> result.value.formatted
        is DomainResult.Failure -> "${currency.value} $minor"
    }
}

private val ORDINARY_KINDS = setOf(TransactionKind.EXPENSE, TransactionKind.INCOME)
private val LEGACY_ACCOUNT_EFFECT = Regex("^(.+):(debit|credit):(-?\\d+) ([A-Z]{3})$")

@Composable
private fun app.ledger.finance.application.JournalFxEvidenceView.localizedEvidence(
    locale: Locale,
    dateTimeFormatter: DateTimeFormatter,
): List<String> = buildList {
    val localizedRate = rate.toBigDecimalOrNull()?.let { LocaleNumberFormatter.decimal(it, locale) }
        ?: stringResource(R.string.p15_journal_rate_unavailable)
    add(stringResource(R.string.p15_journal_rate_direction, sourceCurrency.value, localizedRate, targetCurrency.value))
    add(
        stringResource(
            R.string.p15_journal_rate_source,
            if (manual) stringResource(R.string.p15_journal_rate_manual) else provider,
        ),
    )
    quotedAt?.let { add(stringResource(R.string.p15_journal_rate_quoted_at, dateTimeFormatter.format(it))) }
    if (staleAtUse) add(stringResource(R.string.p15_journal_rate_stale))
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
        val locale = LocalLocale.current.platformLocale
        val zoneId = state.detail?.zoneId?.let(java.time.ZoneId::of) ?: LedgerTheme.timeZone
        val formatter = remember(locale, zoneId) {
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(zoneId)
        }
        LazyColumn(Modifier.fillMaxSize().testTag(LedgerTestTags.JOURNAL_SCREEN), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            items(history, key = { it.revisionId.toString() }) { version ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs), verticalAlignment = Alignment.Top) {
                    LedgerIconView(LedgerIcon.JOURNAL, contentDescription = null)
                    LedgerCard(Modifier.weight(1f)) {
                        Column(Modifier.padding(LedgerTheme.spacing.sm)) {
                        LedgerText(stringResource(R.string.p15_journal_revision_number, version.revisionNumber), LedgerTextRole.SECTION)
                        LedgerChip(version.action.label(), {}, selected = true, enabled = false)
                        LedgerText(formatter.format(version.createdAt), LedgerTextRole.SUPPORTING)
                        LedgerText(version.source.label(), LedgerTextRole.SUPPORTING)
                        LedgerText(version.changedFields.map { it.revisionFieldLabel() }.joinToString(), LedgerTextRole.BODY)
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
                            LedgerButton(
                                stringResource(R.string.p15_journal_create_revision),
                                { actions.onRestoreRevision(transaction.transactionId, transaction.revisionId, version.revisionId, state.dependencyResolutions) },
                                Modifier.fillMaxWidth(),
                                enabled = state.dependencies.size == state.dependencyResolutions.size,
                            )
                        }
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
        var unchangedExpanded by remember(comparison) { mutableStateOf(false) }
        Column(Modifier.fillMaxSize().testTag(LedgerTestTags.JOURNAL_SCREEN), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
            LedgerText(stringResource(R.string.p15_journal_compare), LedgerTextRole.TITLE)
            DetailSection(stringResource(R.string.p15_journal_changed), comparison.changedFields.map { it.revisionFieldLabel() })
            FormSection(
                stringResource(R.string.p15_journal_unchanged),
                expanded = unchangedExpanded,
                onToggle = { unchangedExpanded = !unchangedExpanded },
            ) {
                comparison.unchangedFields.map { it.revisionFieldLabel() }.forEach { LedgerText(it, LedgerTextRole.BODY) }
            }
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
        LedgerEmptyState(stringResource(R.string.p15_journal_dependencies), stringResource(R.string.p15_journal_no_dependencies), stringResource(R.string.p15_journal_back_to_journal), actions.onBack, Modifier.testTag(LedgerTestTags.JOURNAL_SCREEN))
    } else {
        LazyColumn(Modifier.fillMaxSize().testTag(LedgerTestTags.JOURNAL_SCREEN), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            item { LedgerBanner(stringResource(R.string.p15_journal_dependency_summary, state.dependencies.size), LedgerBannerVariant.WARNING) }
            items(state.dependencies, key = { "${it.parentTransactionId}:${it.childTransactionId}:${it.type}" }) { dependency ->
                val policies = dependencyPolicies(dependency.type)
                val selected = state.dependencyResolutions.singleOrNull { it.dependency == dependency.toDomain() }?.policy
                val index = policies.indexOf(selected).takeIf { it >= 0 } ?: -1
                LedgerCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                        DetailSection(
                            dependency.type.label(),
                            listOf(
                                stringResource(R.string.p15_journal_dependency_parent, dependency.parentLabel ?: stringResource(R.string.p15_journal_transaction_label)),
                                stringResource(R.string.p15_journal_dependency_child, dependency.childLabel ?: stringResource(R.string.p15_journal_transaction_label)),
                                stringResource(R.string.p15_journal_dependency_child_state, dependency.childState.label()),
                            ),
                        )
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
private fun TrashScreen(state: JournalLoadState.Content, pages: Flow<PagingData<JournalTransactionView>>, actions: JournalActions) {
    Column(Modifier.fillMaxSize().testTag(LedgerTestTags.JOURNAL_SCREEN)) {
        LedgerBanner(stringResource(R.string.p15_journal_retention), LedgerBannerVariant.INFO)
        PagedJournalList(pages, actions, false, selection = state.selection, trashMode = true)
    }
}

@Composable
private fun PurgeScreen(state: JournalLoadState.Content, actions: JournalActions) {
    val assessment = state.purgeAssessment
    var phrase by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().testTag(LedgerTestTags.JOURNAL_SCREEN), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        if (assessment == null) {
            when (state.operation) {
                JournalOperationState.VALIDATING -> {
                    LedgerText(stringResource(R.string.p15_journal_purge_verifying), LedgerTextRole.SECTION)
                    LedgerProgressIndicator(null, accessibleText = stringResource(R.string.p15_journal_purge_verifying))
                }
                JournalOperationState.FAILED -> LedgerBanner(
                    stringResource(R.string.p15_journal_purge_verify_failed),
                    LedgerBannerVariant.DANGER,
                    actionLabel = stringResource(R.string.p15_journal_retry),
                    onAction = { state.detail?.transaction?.transactionId?.let(actions.onVerifyPurge) },
                )
                else -> LedgerLoadingState(label = stringResource(R.string.p15_journal_loading))
            }
        } else if (!assessment.canPurgeNow) {
            LedgerBanner(stringResource(R.string.p15_journal_purge_blocked), LedgerBannerVariant.DANGER)
            assessment.reasons.forEach { LedgerText(it.label(), LedgerTextRole.BODY) }
            LedgerBanner(stringResource(R.string.p15_journal_purge_p31), LedgerBannerVariant.INFO)
        } else {
            LedgerBanner(stringResource(R.string.p15_journal_purge_dependency_summary, state.dependencies.size), LedgerBannerVariant.INFO)
            if (state.operation == JournalOperationState.COMMITTING) {
                LedgerText(stringResource(R.string.p15_journal_purge_running), LedgerTextRole.SECTION)
                LedgerProgressIndicator(null, accessibleText = stringResource(R.string.p15_journal_purge_running))
            }
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
    selection: app.ledger.finance.application.JournalSelectionSpec? = null,
    trashMode: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val items = pages.collectAsLazyPagingItems()
    when {
        items.itemCount == 0 && items.loadState.refresh is LoadState.Loading -> LedgerLoadingState(modifier.fillMaxSize(), stringResource(R.string.p15_journal_loading))
        items.itemCount == 0 && items.loadState.refresh is LoadState.Error -> LedgerErrorState(UiErrorCode("JOURNAL_PAGE_FAILED"), stringResource(R.string.p15_journal_error), items::retry, modifier.fillMaxSize())
        items.itemCount == 0 -> LedgerEmptyState(stringResource(R.string.p15_journal_empty_title), stringResource(R.string.p15_journal_empty_body), stringResource(R.string.p15_journal_retry), items::retry, modifier)
        else -> JournalLazyColumn(
            items,
            actions,
            showRunningBalance,
            selectionMode,
            selection,
            trashMode,
            refreshing = items.loadState.refresh is LoadState.Loading,
            refreshFailed = items.loadState.refresh is LoadState.Error,
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun JournalLazyColumn(
    pagingItems: LazyPagingItems<JournalTransactionView>,
    actions: JournalActions,
    showRunningBalance: Boolean,
    selectionMode: Boolean,
    selection: app.ledger.finance.application.JournalSelectionSpec?,
    trashMode: Boolean,
    refreshing: Boolean,
    refreshFailed: Boolean,
    modifier: Modifier,
) {
    val locale = LocalLocale.current.platformLocale
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val formatter = remember(locale) { LocaleCurrencyFormatter(JvmLegalTenderCurrencyCatalog.create()) }
    val unavailableAmount = stringResource(R.string.p15_journal_amount_unavailable)
    val snapshot = pagingItems.itemSnapshotList
    val dateGroups = remember(snapshot) {
        snapshot.items
            .mapIndexed { index, transaction -> snapshot.placeholdersBefore + index to transaction }
            .groupBy { (_, transaction) -> transaction.localDate }
    }
    LazyColumn(modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xxs)) {
        if (refreshing) item(key = "refreshing") { JournalLoadingRow() }
        if (refreshFailed) {
            item(key = "refresh_failed") {
                LedgerBanner(
                    stringResource(R.string.p15_journal_refresh_failed),
                    LedgerBannerVariant.WARNING,
                    actionLabel = stringResource(R.string.p15_journal_retry),
                    onAction = pagingItems::retry,
                )
            }
        }
        dateGroups.forEach { (date, indexedTransactions) ->
            stickyHeader(key = "date-$date", contentType = "date_header") {
                LedgerCard(Modifier.fillMaxWidth()) {
                    LedgerText(
                        date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)),
                        LedgerTextRole.SECTION,
                        Modifier.padding(LedgerTheme.spacing.xs),
                    )
                }
            }
            items(
                indexedTransactions,
                key = { (_, transaction) -> transaction.transactionId.toString() },
                contentType = { "transaction" },
            ) { (pagingIndex, snapshotTransaction) ->
                val transaction = pagingItems[pagingIndex] ?: snapshotTransaction
                val kindLabel = transaction.kind.label()
                val row = remember(transaction, locale, kindLabel, unavailableAmount) {
                    transaction.toUi(locale, formatter, kindLabel, unavailableAmount)
                }
                val openDetail = {
                    actions.onLoadDetail(transaction.transactionId)
                    actions.onNavigate("JRN-007", mapOf("transactionId" to transaction.transactionId))
                }
                val select = { actions.onSelect(transaction.transactionId) }
                if (trashMode) {
                    TrashTransactionRow(transaction, row, selection?.contains(transaction.transactionId) == true, openDetail, select, actions)
                } else {
                    JournalTransactionRow(
                        row,
                        onClick = if (selectionMode) select else openDetail,
                        onLongClick = {
                            select()
                            actions.onNavigate("JRN-005", emptyMap())
                        },
                        showRunningBalance = showRunningBalance,
                    )
                }
            }
        }
        if (pagingItems.loadState.append is LoadState.Loading) item(key = "append_loading") { JournalLoadingRow() }
        if (pagingItems.loadState.append is LoadState.Error) item { LedgerButton(stringResource(R.string.p15_journal_retry), pagingItems::retry, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun TrashTransactionRow(
    transaction: JournalTransactionView,
    row: JournalTransactionUiModel,
    selected: Boolean,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
    actions: JournalActions,
) {
    val locale = LocalLocale.current.platformLocale
    val zone = LedgerTheme.timeZone
    val formatter = remember(locale, zone) { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(zone) }
    LedgerCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = LedgerTheme.spacing.xs)) {
            JournalTransactionRow(row, onOpen, onSelect)
            transaction.trashedAt?.let { LedgerText(stringResource(R.string.p15_journal_deleted_at, formatter.format(it)), LedgerTextRole.SUPPORTING) }
            transaction.purgeAfter?.let { LedgerText(stringResource(R.string.p15_journal_cleanup_at, formatter.format(it)), LedgerTextRole.SUPPORTING) }
            LedgerText(stringResource(R.string.p15_journal_dependency_count, transaction.dependencyCount), LedgerTextRole.SUPPORTING)
            LedgerChoiceRow(stringResource(R.string.p15_journal_selected_for_bulk), selected, { onSelect() })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                LedgerButton(stringResource(R.string.p15_journal_restore), { actions.onRestore(transaction.transactionId, transaction.revisionId) }, Modifier.weight(1f), LedgerButtonVariant.SECONDARY)
                LedgerButton(
                    stringResource(R.string.p15_journal_purge),
                    { actions.onNavigate("JRN-012", mapOf("transactionId" to transaction.transactionId)) },
                    Modifier.weight(1f),
                    LedgerButtonVariant.DANGER,
                )
            }
        }
    }
}

@Composable
private fun JournalLoadingRow() {
    Box(Modifier.fillMaxWidth().heightIn(min = LedgerTheme.dimensions.touchTargetMin), contentAlignment = Alignment.Center) {
        LedgerProgressIndicator(
            null,
            Modifier.size(LedgerTheme.dimensions.iconSm),
            accessibleText = stringResource(R.string.p15_journal_loading),
        )
    }
}

private fun JournalTransactionView.toUi(
    locale: Locale,
    formatter: LocaleCurrencyFormatter,
    kindLabel: String,
    unavailableAmount: String,
): JournalTransactionUiModel {
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
    val amount = when (val result = formatter.format(MoneyFormatRequest(Money(amountMinor, currency), locale, semantic, AmountVisibility.VISIBLE, secondaryMoney = secondary))) {
        is DomainResult.Success -> result.value
        is DomainResult.Failure -> MoneyUiModel(unavailableAmount, unavailableAmount, semantic, AmountVisibility.VISIBLE)
    }
    val running = runningBalanceMinor?.let { balance ->
        when (val result = formatter.format(MoneyFormatRequest(Money(balance, currency), locale, AmountSemantic.NEUTRAL, AmountVisibility.VISIBLE))) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> null
        }
    }
    val accessible = listOf(kindLabel, categoryOrType, summary, accountAndCard, amount.fullAccessibleText, badges.joinToString()).filter(String::isNotBlank).joinToString(". ")
    return JournalTransactionUiModel(transactionId.toString(), categoryOrType, summary, accountAndCard, amount, kindLabel, iconFor(kind), badges, running, accessible)
}

private fun iconFor(kind: TransactionKind): LedgerIcon = when (kind) {
    TransactionKind.TRANSFER, TransactionKind.FX_EXCHANGE -> LedgerIcon.TRANSFER
    TransactionKind.REFUND -> LedgerIcon.REFUND
    else -> LedgerIcon.JOURNAL
}

@Composable
private fun TransactionKind.label(): String = stringResource(
    when (this) {
        TransactionKind.EXPENSE -> R.string.p15_kind_expense
        TransactionKind.INCOME -> R.string.p15_kind_income
        TransactionKind.TRANSFER -> R.string.p15_kind_transfer
        TransactionKind.REFUND -> R.string.p15_kind_refund
        TransactionKind.CREDIT_PAYMENT -> R.string.p15_kind_credit_payment
        TransactionKind.LOAN_DISBURSEMENT -> R.string.p15_kind_loan_disbursement
        TransactionKind.LOAN_PAYMENT -> R.string.p15_kind_loan_payment
        TransactionKind.BALANCE_ADJUSTMENT -> R.string.p15_kind_balance_adjustment
        TransactionKind.FX_EXCHANGE -> R.string.p15_kind_fx_exchange
        TransactionKind.SETTLEMENT_PAYMENT -> R.string.p15_kind_settlement_payment
        TransactionKind.OPENING_BALANCE -> R.string.p15_kind_opening_balance
    },
)

@Composable
private fun TransactionLifecycleState.label(): String = stringResource(
    when (this) {
        TransactionLifecycleState.ACTIVE -> R.string.p15_state_active
        TransactionLifecycleState.TRASHED -> R.string.p15_state_trashed
    },
)

@Composable
private fun TransactionSource.label(): String = stringResource(
    when (this) {
        TransactionSource.MANUAL -> R.string.p15_source_manual
        TransactionSource.QUICK_TEMPLATE -> R.string.p15_source_quick_template
        TransactionSource.RECURRENCE_AUTO -> R.string.p15_source_recurrence_auto
        TransactionSource.RECURRENCE_CANDIDATE -> R.string.p15_source_recurrence_candidate
        TransactionSource.CSV_IMPORT -> R.string.p15_source_csv_import
        TransactionSource.XLSX_IMPORT -> R.string.p15_source_xlsx_import
        TransactionSource.STRUCTURED_IMPORT -> R.string.p15_source_structured_import
        TransactionSource.SYSTEM_GENERATED -> R.string.p15_source_system_generated
        TransactionSource.MERGE_RESTORE -> R.string.p15_source_merge_restore
        TransactionSource.BATCH_OPERATION -> R.string.p15_source_batch_operation
    },
)

@Composable
private fun StatisticalNature.label(): String = stringResource(
    when (this) {
        StatisticalNature.CONSUMPTION_EXPENSE -> R.string.p15_nature_consumption_expense
        StatisticalNature.NON_CONSUMPTION_EXPENSE -> R.string.p15_nature_non_consumption_expense
        StatisticalNature.REGULAR_INCOME -> R.string.p15_nature_regular_income
        StatisticalNature.NON_RECURRING_INCOME -> R.string.p15_nature_non_recurring_income
    },
)

@Composable
private fun JournalOperationState.label(): String = stringResource(
    when (this) {
        JournalOperationState.IDLE -> R.string.p15_operation_idle
        JournalOperationState.VALIDATING -> R.string.p15_operation_validating
        JournalOperationState.COMMITTING -> R.string.p15_operation_committing
        JournalOperationState.FAILED -> R.string.p15_operation_failed
        JournalOperationState.SUCCEEDED -> R.string.p15_operation_succeeded
    },
)

@Composable
private fun RevisionAction.label(): String = stringResource(
    when (this) {
        RevisionAction.CREATE -> R.string.p15_revision_create
        RevisionAction.EDIT -> R.string.p15_revision_edit
        RevisionAction.MOVE_TO_TRASH -> R.string.p15_revision_move_to_trash
        RevisionAction.RESTORE -> R.string.p15_revision_restore
        RevisionAction.BULK_EDIT -> R.string.p15_revision_bulk_edit
        RevisionAction.DEPENDENCY_REWRITE -> R.string.p15_revision_dependency_rewrite
    },
)

@Composable
private fun String.localizedNature(): String = StatisticalNature.entries.firstOrNull { it.name == this }?.label() ?: this

@Composable
private fun String.revisionFieldLabel(): String = stringResource(
    when (this) {
        "created" -> R.string.p15_revision_field_created
        "occurredAt" -> R.string.p15_revision_field_occurred_at
        "category" -> R.string.p15_revision_field_category
        "account" -> R.string.p15_revision_field_account
        "amount" -> R.string.p15_revision_field_amount
        "note" -> R.string.p15_revision_field_note
        "merchant" -> R.string.p15_revision_field_merchant
        "project" -> R.string.p15_revision_field_project
        "location" -> R.string.p15_revision_field_location
        "state" -> R.string.p15_revision_field_state
        else -> R.string.p15_revision_field_other
    },
)

@Composable
private fun TransactionDependencyType.label(): String = stringResource(
    when (this) {
        TransactionDependencyType.REFUND -> R.string.p15_dependency_refund
        TransactionDependencyType.INSTALLMENT_PLAN -> R.string.p15_dependency_installment
        TransactionDependencyType.CREDIT_STATEMENT -> R.string.p15_dependency_credit_statement
        TransactionDependencyType.LOAN_SCHEDULE -> R.string.p15_dependency_loan_schedule
        TransactionDependencyType.SETTLEMENT_ACTIVITY -> R.string.p15_dependency_settlement
        TransactionDependencyType.RECURRENCE_OCCURRENCE -> R.string.p15_dependency_recurrence
        TransactionDependencyType.ATTACHMENT_REFERENCE -> R.string.p15_dependency_attachment
    },
)

@Composable
private fun PurgeIneligibilityReason.label(): String = stringResource(
    when (this) {
        PurgeIneligibilityReason.NOT_TRASHED -> R.string.p15_purge_reason_not_trashed
        PurgeIneligibilityReason.RETENTION_NOT_ELAPSED -> R.string.p15_purge_reason_retention
        PurgeIneligibilityReason.ACCOUNT_NET_NON_ZERO -> R.string.p15_purge_reason_account_net
        PurgeIneligibilityReason.BASE_NET_NON_ZERO -> R.string.p15_purge_reason_base_net
        PurgeIneligibilityReason.EFFECT_NET_NON_ZERO -> R.string.p15_purge_reason_effect_net
        PurgeIneligibilityReason.DEPENDENCIES_OPEN -> R.string.p15_purge_reason_dependencies
        PurgeIneligibilityReason.OPERATION_REFERENCE -> R.string.p15_purge_reason_operation
        PurgeIneligibilityReason.ATTACHMENTS_READ_BY_BACKUP -> R.string.p15_purge_reason_backup
    },
)

private data class OptionalInput<T>(val value: T?, val valid: Boolean)

private fun String.toOptionalMinor(
    currency: app.ledger.core.money.CurrencyCode?,
    catalog: JvmLegalTenderCurrencyCatalog,
): OptionalInput<Long> = if (isBlank()) {
    OptionalInput(null, true)
} else {
    val metadata = currency?.let(catalog::find)
    val value = runCatching {
        requireNotNull(metadata)
        BigDecimal(trim().replace(',', '.'))
            .movePointRight(metadata.fractionDigits)
            .setScale(0, RoundingMode.UNNECESSARY)
            .longValueExact()
    }.getOrNull()
    OptionalInput(value, value != null)
}

private fun Long?.toMajorInput(
    currency: app.ledger.core.money.CurrencyCode?,
    catalog: JvmLegalTenderCurrencyCatalog,
): String = if (this == null || currency == null) "" else catalog.find(currency)
    ?.let { BigDecimal.valueOf(this, it.fractionDigits).stripTrailingZeros().toPlainString() }
    .orEmpty()

private fun Int.toCoordinateInput(): String = BigDecimal.valueOf(toLong(), GEO_COORDINATE_SCALE).stripTrailingZeros().toPlainString()

private fun parseGeoRadius(latitude: String, longitude: String, radius: String): OptionalInput<GeoRadiusFilter> {
    if (latitude.isBlank() && longitude.isBlank() && radius.isBlank()) return OptionalInput(null, true)
    val latitudeE7 = latitude.toCoordinateE7()
    val longitudeE7 = longitude.toCoordinateE7()
    val radiusMeters = radius.toIntOrNull()?.takeIf { it > 0 }
    val center = if (latitudeE7 != null && longitudeE7 != null) GeoPoint.create(latitudeE7, longitudeE7).getOrNull() else null
    val value = if (center != null && radiusMeters != null) GeoRadiusFilter(center, radiusMeters) else null
    return OptionalInput(value, value != null)
}

private fun String.toCoordinateE7(): Int? = runCatching {
    BigDecimal(trim().replace(',', '.'))
        .movePointRight(GEO_COORDINATE_SCALE)
        .setScale(0, RoundingMode.HALF_EVEN)
        .intValueExact()
}.getOrNull()

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

private enum class JournalFilterTimeField { OCCURRED_FROM, OCCURRED_THROUGH, CREATED_FROM, CREATED_THROUGH, MODIFIED_FROM, MODIFIED_THROUGH }

@Composable
private fun FilterInstantField(
    value: Instant?,
    label: String,
    formatter: DateTimeFormatter,
    zoneId: ZoneId,
    onChoose: () -> Unit,
    onClear: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
        DateTimeZoneField(
            label,
            value?.let(formatter::format) ?: stringResource(R.string.p15_journal_any),
            zoneId.id,
            onChoose,
            Modifier.weight(1f),
            zoneIsDifferent = value != null,
        )
        if (value != null) LedgerButton(stringResource(R.string.p15_journal_clear_value), onClear, variant = LedgerButtonVariant.TEXT)
    }
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
    if (filter.geoRadius != null) add(stringResource(R.string.p15_journal_filter_location))
    if (listOf(filter.hasAttachment, filter.isRefund, filter.hasInstallment, filter.includedInBudget, filter.generatedByRecurrence).any { it != null }) add(stringResource(R.string.p15_journal_filter_flags))
}.ifEmpty { listOf(stringResource(R.string.p15_journal_filter_all)) }.joinToString(" · ")

private const val GEO_COORDINATE_SCALE = 7
private const val HISTORY_PREVIEW_LIMIT = 3

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
