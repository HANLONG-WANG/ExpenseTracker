@file:Suppress("FunctionNaming", "LongMethod", "MagicNumber", "MaxLineLength", "TooManyFunctions", "ktlint:standard:function-naming")

package app.ledger.feature.automation

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import app.ledger.core.common.StableId
import app.ledger.core.designsystem.FormSection
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerButton
import app.ledger.core.designsystem.LedgerButtonVariant
import app.ledger.core.designsystem.LedgerCard
import app.ledger.core.designsystem.LedgerChoiceRow
import app.ledger.core.designsystem.LedgerEmptyState
import app.ledger.core.designsystem.LedgerErrorState
import app.ledger.core.designsystem.LedgerLoadingState
import app.ledger.core.designsystem.LedgerDatePickerFlow
import app.ledger.core.designsystem.LedgerIcon
import app.ledger.core.designsystem.LedgerStatusVariant
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextField
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.LedgerToggleRow
import app.ledger.core.designsystem.LedgerTabRow
import app.ledger.core.designsystem.SearchField
import app.ledger.core.designsystem.SelectorField
import app.ledger.core.designsystem.StatusBadge
import app.ledger.core.designsystem.UiErrorCode
import app.ledger.core.money.LocaleNumberFormatter
import app.ledger.finance.application.BlueprintView
import app.ledger.finance.application.CandidateView
import app.ledger.finance.application.RecurrenceSeriesView
import app.ledger.finance.domain.EntityStatus
import app.ledger.finance.domain.MissingDayPolicy
import app.ledger.finance.domain.RecurrenceCandidateStatus
import app.ledger.finance.domain.RecurrenceFrequency
import app.ledger.finance.domain.RecurrenceGenerationMode
import app.ledger.finance.domain.RecurrenceModificationScope
import app.ledger.finance.domain.RecurrenceStatus
import app.ledger.finance.domain.TransactionKind
import app.ledger.finance.domain.WeekendAdjustment
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
public fun AutomationDestination(
    screenId: String,
    state: AutomationLoadState,
    actions: AutomationActions,
) {
    when (state) {
        AutomationLoadState.Loading -> LedgerLoadingState(Modifier.fillMaxSize(), stringResource(R.string.automation_loading))
        is AutomationLoadState.Failure -> LedgerErrorState(UiErrorCode(state.code), stringResource(R.string.automation_load_failed), actions.onRetry)
        is AutomationLoadState.Content -> when (screenId) {
            "AUT-001" -> AutomationHub(state.state, actions)
            "AUT-002" -> TemplateList(state.state, actions)
            "AUT-003" -> TemplateEditor(state.state, actions)
            "AUT-004" -> SeriesList(state.state, actions)
            "AUT-005" -> SeriesEditor(state.state, actions)
            "AUT-006" -> RuleEditor(state.state, actions)
            "AUT-007" -> OccurrencePreview(state.state, actions)
            "AUT-008" -> CandidateList(state.state, actions)
            "AUT-009" -> CandidateEditor(state.state, actions)
            "AUT-010" -> ScopePicker(state.state, actions)
            else -> LedgerErrorState(UiErrorCode("AUTOMATION_SCREEN_UNKNOWN"), stringResource(R.string.automation_load_failed), actions.onRetry)
        }
    }
}

@Composable
private fun AutomationHub(state: AutomationFeatureState, actions: AutomationActions) {
    ScreenList(Modifier.testTag(LedgerTestTags.AUTOMATION_HUB)) {
        item { HubCard(stringResource(R.string.automation_templates), stringResource(R.string.automation_templates_body), { actions.onNavigate("AUT-002", null) }) }
        item { HubCard(stringResource(R.string.automation_recurrences), stringResource(R.string.automation_recurrences_body), { actions.onNavigate("AUT-004", null) }) }
        item {
            LedgerCard(Modifier.fillMaxWidth(), onClick = { actions.onNavigate("AUT-008", null) }) {
                Row(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        LedgerText(stringResource(R.string.automation_candidates), LedgerTextRole.SECTION)
                        LedgerText(stringResource(R.string.automation_candidates_body), LedgerTextRole.SUPPORTING)
                    }
                    StatusBadge(
                        LocaleNumberFormatter.integer(
                            state.snapshot.candidates.count { it.status == RecurrenceCandidateStatus.PENDING_CONFIRMATION },
                            LocalLocale.current.platformLocale,
                        ),
                        LedgerStatusVariant.CANDIDATE,
                    )
                }
            }
        }
        item { LedgerBanner(stringResource(R.string.automation_reality_disclaimer), LedgerBannerVariant.INFO) }
    }
}

@Composable
private fun TemplateList(state: AutomationFeatureState, actions: AutomationActions) {
    if (state.presentation == AutomationPresentation.EMPTY) {
        EmptyScreen(Modifier.testTag(LedgerTestTags.AUTOMATION_TEMPLATE_LIST), R.string.automation_template_empty, R.string.automation_template_empty_body) { actions.onNavigate("AUT-003", null) }
        return
    }
    val filtered = state.snapshot.blueprints
        .filter { template ->
            (state.search.isBlank() || template.name.contains(state.search, ignoreCase = true)) && when (state.templateFilter) {
                AutomationTemplateFilter.ALL -> true
                AutomationTemplateFilter.ACTIVE -> template.status == EntityStatus.ACTIVE
                AutomationTemplateFilter.ARCHIVED -> template.status != EntityStatus.ACTIVE
            }
        }
        .let { templates -> if (state.templateSort == AutomationTemplateSort.NAME) templates.sortedBy { it.name.lowercase() } else templates.sortedByDescending { it.revisionNumber } }
    ScreenList(Modifier.testTag(LedgerTestTags.AUTOMATION_TEMPLATE_LIST)) {
        item { SearchField(state.search, actions.onSearch, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.automation_search_template)) }
        item {
            LedgerTabRow(
                state.templateFilter.ordinal,
                listOf(stringResource(R.string.automation_filter_all), stringResource(R.string.automation_filter_active), stringResource(R.string.automation_filter_archived)),
                { actions.onTemplateFilter(AutomationTemplateFilter.entries[it]) },
            )
        }
        item { SelectorField(stringResource(R.string.automation_sort), stringResource(if (state.templateSort == AutomationTemplateSort.NAME) R.string.automation_sort_name else R.string.automation_sort_recent), { actions.onTemplateSort(if (state.templateSort == AutomationTemplateSort.NAME) AutomationTemplateSort.RECENTLY_REVISED else AutomationTemplateSort.NAME) }) }
        if (filtered.isEmpty()) item { LedgerText(stringResource(R.string.automation_filter_empty), LedgerTextRole.SUPPORTING) }
        items(filtered, key = { it.id.toString() }) { template -> TemplateCard(template, actions) { actions.onNavigate("AUT-003", template.id) } }
        item { LedgerButton(stringResource(R.string.automation_add_template), { actions.onNavigate("AUT-003", null) }, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun TemplateCard(blueprint: BlueprintView, actions: AutomationActions, onClick: () -> Unit) {
    LedgerCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                LedgerText(blueprint.name, LedgerTextRole.SECTION)
                LedgerText(kindLabel(blueprint.targetKind), LedgerTextRole.SUPPORTING)
                LedgerText(blueprint.amountExpression ?: stringResource(R.string.automation_amount_when_used), LedgerTextRole.BODY)
            }
            StatusBadge(if (blueprint.status == EntityStatus.ACTIVE) stringResource(R.string.automation_active) else stringResource(R.string.automation_archived), if (blueprint.status == EntityStatus.ACTIVE) LedgerStatusVariant.POSITIVE else LedgerStatusVariant.ARCHIVED)
        }
        if (blueprint.status == EntityStatus.ACTIVE) LedgerButton(stringResource(R.string.automation_archive_template), { actions.onArchiveBlueprint(blueprint.id) }, Modifier.fillMaxWidth(), LedgerButtonVariant.TEXT, compact = true)
    }
}

@Composable
private fun TemplateEditor(state: AutomationFeatureState, actions: AutomationActions) {
    val draft = state.blueprintDraft ?: return
    ScreenList(Modifier.testTag(LedgerTestTags.AUTOMATION_TEMPLATE_EDITOR)) {
        if (state.presentation == AutomationPresentation.VALIDATION_ERROR) item { LedgerBanner(stringResource(R.string.automation_validation_error), LedgerBannerVariant.DANGER) }
        item {
            FormSection(stringResource(R.string.automation_template_identity)) {
                LedgerTextField(draft.name, { actions.onBlueprintField(BlueprintField.NAME, it) }, stringResource(R.string.automation_template_name), required = true, errorText = errorIf(state, "name"))
                LedgerText(stringResource(R.string.automation_icon), LedgerTextRole.LABEL)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                    templateIcons.forEach { icon ->
                        LedgerButton(templateIconLabel(icon), { actions.onBlueprintField(BlueprintField.ICON, icon.name) }, variant = if (draft.iconKey == icon.name) LedgerButtonVariant.TONAL else LedgerButtonVariant.TEXT)
                    }
                }
            }
        }
        item {
            FormSection(stringResource(R.string.automation_transaction_fields)) {
                TransactionKind.entries.filter { it in supportedBlueprintKinds }.forEach { kind ->
                    LedgerChoiceRow(kindLabel(kind), draft.targetKind == kind, { actions.onBlueprintKind(kind) })
                }
                ReferenceChoices(
                    state = state,
                    selectedCategoryId = draft.categoryId,
                    selectedAccountId = draft.primaryAccountId,
                    onReference = actions.onBlueprintReference,
                )
                LedgerTextField(draft.amountExpression, { actions.onBlueprintField(BlueprintField.AMOUNT, it) }, stringResource(R.string.automation_amount_expression), supportingText = stringResource(R.string.automation_amount_optional), keyboardType = KeyboardType.Decimal)
                LedgerTextField(draft.currency, { actions.onBlueprintField(BlueprintField.CURRENCY, it) }, stringResource(R.string.automation_currency), supportingText = stringResource(R.string.automation_currency_optional), errorText = errorIf(state, "currency"))
                LedgerTextField(draft.noteTemplate, { actions.onBlueprintField(BlueprintField.NOTE, it) }, stringResource(R.string.automation_note_template), singleLine = false)
            }
        }
        item { LedgerBanner(stringResource(R.string.automation_unsupported_fields), LedgerBannerVariant.WARNING) }
    }
}

@Composable
private fun ReferenceChoices(
    state: AutomationFeatureState,
    selectedCategoryId: StableId?,
    selectedAccountId: StableId?,
    onReference: (String, StableId?) -> Unit,
) {
    LedgerText(stringResource(R.string.automation_category), LedgerTextRole.LABEL)
    state.entrySnapshot.references.categories.filter { it.status.name == "ACTIVE" }.forEach { category ->
        LedgerChoiceRow(category.name, selectedCategoryId == category.id, { onReference("category", category.id) })
    }
    LedgerText(stringResource(R.string.automation_account), LedgerTextRole.LABEL)
    state.entrySnapshot.references.accounts.filter { it.status == EntityStatus.ACTIVE }.forEach { account ->
        LedgerChoiceRow(account.name, selectedAccountId == account.id, { onReference("primaryAccount", account.id) }, supportingText = account.currency.value)
    }
    LedgerText(stringResource(R.string.automation_secondary_account), LedgerTextRole.LABEL)
    LedgerChoiceRow(stringResource(R.string.automation_none), state.blueprintDraft?.secondaryAccountId == null, { onReference("secondaryAccount", null) })
    state.entrySnapshot.references.accounts.filter { it.status == EntityStatus.ACTIVE }.forEach { account ->
        LedgerChoiceRow(account.name, state.blueprintDraft?.secondaryAccountId == account.id, { onReference("secondaryAccount", account.id) }, supportingText = account.currency.value)
    }
    LedgerText(stringResource(R.string.automation_card), LedgerTextRole.LABEL)
    LedgerChoiceRow(stringResource(R.string.automation_none), state.blueprintDraft?.cardId == null, { onReference("card", null) })
    state.entrySnapshot.references.cards.filter { it.status == EntityStatus.ACTIVE }.forEach { card ->
        LedgerChoiceRow(card.displayName, state.blueprintDraft?.cardId == card.id, { onReference("card", card.id) })
    }
    LedgerText(stringResource(R.string.automation_merchant), LedgerTextRole.LABEL)
    LedgerChoiceRow(stringResource(R.string.automation_none), state.blueprintDraft?.merchantId == null, { onReference("merchant", null) })
    state.entrySnapshot.references.merchants.filter { it.status == EntityStatus.ACTIVE }.forEach { merchant ->
        LedgerChoiceRow(merchant.name, state.blueprintDraft?.merchantId == merchant.id, { onReference("merchant", merchant.id) })
    }
    LedgerText(stringResource(R.string.automation_project), LedgerTextRole.LABEL)
    LedgerChoiceRow(stringResource(R.string.automation_none), state.blueprintDraft?.projectId == null, { onReference("project", null) })
    state.entrySnapshot.projects.filter { it.active }.forEach { project ->
        LedgerChoiceRow(project.name, state.blueprintDraft?.projectId == project.id, { onReference("project", project.id) })
    }
    LedgerText(stringResource(R.string.automation_goal), LedgerTextRole.LABEL)
    LedgerChoiceRow(stringResource(R.string.automation_none), state.blueprintDraft?.goalId == null, { onReference("goal", null) })
    state.entrySnapshot.references.accountGoals.forEach { goal ->
        LedgerChoiceRow(goal.name, state.blueprintDraft?.goalId == goal.id, { onReference("goal", goal.id) })
    }
    LedgerText(stringResource(R.string.automation_settlement), LedgerTextRole.LABEL)
    LedgerChoiceRow(stringResource(R.string.automation_none), state.blueprintDraft?.settlementActivityId == null, { onReference("settlement", null) })
    state.entrySnapshot.settlementActivities.filter { it.active }.forEach { activity ->
        LedgerChoiceRow(activity.name, state.blueprintDraft?.settlementActivityId == activity.id, { onReference("settlement", activity.id) })
    }
    LedgerText(stringResource(R.string.automation_fixed_place), LedgerTextRole.LABEL)
    LedgerChoiceRow(stringResource(R.string.automation_no_fixed_place), state.blueprintDraft?.fixedPlaceId == null, { onReference("fixedPlace", null) })
    state.entrySnapshot.references.places.filter { it.status == EntityStatus.ACTIVE }.forEach { place ->
        LedgerChoiceRow(place.name, state.blueprintDraft?.fixedPlaceId == place.id, { onReference("fixedPlace", place.id) })
    }
}

@Composable
private fun SeriesList(state: AutomationFeatureState, actions: AutomationActions) {
    if (state.presentation == AutomationPresentation.EMPTY) {
        EmptyScreen(Modifier.testTag(LedgerTestTags.AUTOMATION_SERIES_LIST), R.string.automation_series_empty, R.string.automation_series_empty_body) { actions.onNavigate("AUT-005", null) }
        return
    }
    val visible = state.snapshot.series.filter { series -> when (state.seriesFilter) {
        AutomationSeriesFilter.ALL -> true
        AutomationSeriesFilter.ACTIVE -> series.status == RecurrenceStatus.ACTIVE
        AutomationSeriesFilter.PAUSED -> series.status == RecurrenceStatus.PAUSED
        AutomationSeriesFilter.ARCHIVED -> series.status == RecurrenceStatus.ARCHIVED
    } }
    ScreenList(Modifier.testTag(LedgerTestTags.AUTOMATION_SERIES_LIST)) {
        if (state.presentation == AutomationPresentation.PAUSED) item { LedgerBanner(stringResource(R.string.automation_all_paused), LedgerBannerVariant.WARNING) }
        item {
            LedgerTabRow(
                state.seriesFilter.ordinal,
                listOf(stringResource(R.string.automation_filter_all), stringResource(R.string.automation_filter_active), stringResource(R.string.automation_filter_paused), stringResource(R.string.automation_filter_archived)),
                { actions.onSeriesFilter(AutomationSeriesFilter.entries[it]) },
            )
        }
        if (visible.isEmpty()) item { LedgerText(stringResource(R.string.automation_filter_empty), LedgerTextRole.SUPPORTING) }
        items(visible, key = { it.id.toString() }) { series -> SeriesCard(series) { actions.onNavigate("AUT-005", series.id) } }
        item { LedgerButton(stringResource(R.string.automation_add_series), { actions.onNavigate("AUT-005", null) }, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun SeriesCard(series: RecurrenceSeriesView, onClick: () -> Unit) {
    LedgerCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LedgerText(series.blueprintName, LedgerTextRole.SECTION)
                StatusBadge(seriesStatus(series.status), if (series.status == RecurrenceStatus.ACTIVE) LedgerStatusVariant.POSITIVE else LedgerStatusVariant.WARNING)
            }
            LedgerText(ruleSummary(series), LedgerTextRole.BODY)
            LedgerText(if (series.generationMode == RecurrenceGenerationMode.CANDIDATE) stringResource(R.string.automation_candidate_mode) else stringResource(R.string.automation_formal_mode), LedgerTextRole.SUPPORTING)
        }
    }
}

@Composable
private fun SeriesEditor(state: AutomationFeatureState, actions: AutomationActions) {
    val draft = state.recurrenceDraft ?: return
    ScreenList(Modifier.testTag(LedgerTestTags.AUTOMATION_SERIES_EDITOR)) {
        if (state.presentation == AutomationPresentation.INVALID) item { LedgerBanner(stringResource(R.string.automation_validation_error), LedgerBannerVariant.DANGER) }
        item {
            FormSection(stringResource(R.string.automation_blueprint_selector)) {
                state.snapshot.blueprints.filter { it.status == EntityStatus.ACTIVE }.forEach { blueprint ->
                    LedgerChoiceRow(blueprint.name, draft.blueprintId == blueprint.id, { actions.onRecurrenceBlueprint(blueprint.id) })
                }
            }
        }
        item {
            FormSection(stringResource(R.string.automation_generation_mode)) {
                LedgerChoiceRow(stringResource(R.string.automation_candidate_mode), draft.generationMode == RecurrenceGenerationMode.CANDIDATE, { actions.onGenerationMode(RecurrenceGenerationMode.CANDIDATE) }, supportingText = stringResource(R.string.automation_candidate_mode_body))
                LedgerChoiceRow(stringResource(R.string.automation_formal_mode), draft.generationMode == RecurrenceGenerationMode.FORMAL_TRANSACTION, { actions.onGenerationMode(RecurrenceGenerationMode.FORMAL_TRANSACTION) }, supportingText = stringResource(R.string.automation_formal_mode_body))
            }
        }
        item {
            LedgerCard(Modifier.fillMaxWidth(), onClick = { actions.onNavigate("AUT-006", draft.id) }) {
                Column(Modifier.padding(LedgerTheme.spacing.sm)) {
                    LedgerText(stringResource(R.string.automation_rule), LedgerTextRole.SECTION)
                    LedgerText(ruleSummary(draft.rule, draft.startDate), LedgerTextRole.BODY)
                }
            }
        }
        item { LedgerToggleRow(stringResource(R.string.automation_notify_candidate), draft.notifyCandidate, actions.onNotifyCandidate, supportingText = stringResource(R.string.automation_notify_candidate_body), enabled = draft.generationMode == RecurrenceGenerationMode.CANDIDATE) }
        item {
            FormSection(stringResource(R.string.automation_fixed_place), description = stringResource(R.string.automation_fixed_place_notice)) {
                val place = draft.fixedPlaceId?.let { id -> state.entrySnapshot.references.places.singleOrNull { it.id == id } }
                LedgerChoiceRow(place?.name ?: stringResource(R.string.automation_no_fixed_place), true, { actions.onFixedPlace(draft.fixedPlaceId) })
                draft.blueprintId?.let { blueprintId ->
                    LedgerButton(stringResource(R.string.automation_edit_template_place), { actions.onNavigate("AUT-003", blueprintId) }, Modifier.fillMaxWidth(), LedgerButtonVariant.TEXT)
                }
            }
        }
        item { LedgerButton(stringResource(R.string.automation_preview_next), { actions.onNavigate("AUT-007", draft.id) }, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY) }
        item { LedgerBanner(stringResource(R.string.automation_reality_disclaimer), LedgerBannerVariant.WARNING) }
    }
}

@Composable
private fun RuleEditor(state: AutomationFeatureState, actions: AutomationActions) {
    val draft = state.recurrenceDraft ?: return
    val locale = LocalLocale.current.platformLocale
    var startPicker by remember { mutableStateOf(false) }
    var endPicker by remember { mutableStateOf(false) }
    ScreenList(Modifier.testTag(LedgerTestTags.AUTOMATION_RULE_EDITOR)) {
        if (state.presentation == AutomationPresentation.INVALID) item { LedgerBanner(stringResource(R.string.automation_validation_error), LedgerBannerVariant.DANGER) }
        item {
            FormSection(stringResource(R.string.automation_frequency)) {
                RecurrenceFrequency.entries.forEach { frequency -> LedgerChoiceRow(frequencyLabel(frequency), draft.rule.frequency == frequency, { actions.onFrequency(frequency) }) }
            }
        }
        item { LedgerTextField(draft.rule.interval.toString(), { actions.onRecurrenceField(RecurrenceField.INTERVAL, it) }, stringResource(R.string.automation_interval), keyboardType = KeyboardType.Number, required = true) }
        if (draft.rule.frequency in setOf(RecurrenceFrequency.WEEKLY, RecurrenceFrequency.BUSINESS_DAYS)) {
            item {
                FormSection(stringResource(R.string.automation_weekdays)) {
                    DayOfWeek.entries.forEach { day -> LedgerToggleRow(dayLabel(day), day in draft.rule.weekdays, { actions.onWeekday(day) }) }
                }
            }
        }
        if (draft.rule.frequency == RecurrenceFrequency.MONTHLY_DAY) item { LedgerTextField(draft.rule.monthDay?.toString().orEmpty(), { actions.onRecurrenceField(RecurrenceField.MONTH_DAY, it) }, stringResource(R.string.automation_month_day), keyboardType = KeyboardType.Number) }
        if (draft.rule.frequency == RecurrenceFrequency.MONTHLY_NTH_WEEKDAY) {
            item { LedgerTextField(draft.rule.nthWeek?.toString().orEmpty(), { actions.onRecurrenceField(RecurrenceField.NTH_WEEK, it) }, stringResource(R.string.automation_nth_week), keyboardType = KeyboardType.Number) }
            item {
                FormSection(stringResource(R.string.automation_nth_weekday)) {
                    DayOfWeek.entries.forEach { day -> LedgerChoiceRow(dayLabel(day), draft.rule.weekday == day, { actions.onNthWeekday(day) }) }
                }
            }
        }
        item {
            FormSection(stringResource(R.string.automation_missing_day_policy)) {
                MissingDayPolicy.entries.forEach { policy -> LedgerChoiceRow(missingDayLabel(policy), draft.rule.missingDayPolicy == policy, { actions.onMissingDay(policy) }) }
            }
        }
        item {
            FormSection(stringResource(R.string.automation_weekend_policy)) {
                WeekendAdjustment.entries.forEach { policy -> LedgerChoiceRow(weekendLabel(policy), draft.rule.weekendAdjustment == policy, { actions.onWeekend(policy) }) }
            }
        }
        item { SelectorField(stringResource(R.string.automation_start_date), draft.startDate.toLocalDateOrNull()?.localized(locale) ?: stringResource(R.string.automation_choose_date), { startPicker = true }, supportingText = errorIf(state, "startDate")) }
        item { SelectorField(stringResource(R.string.automation_end_date), draft.endDate.toLocalDateOrNull()?.localized(locale) ?: stringResource(R.string.automation_no_end_date), { endPicker = true }, supportingText = errorIf(state, "endDate")) }
        item { LedgerTextField(draft.maxOccurrences, { actions.onRecurrenceField(RecurrenceField.MAX_OCCURRENCES, it) }, stringResource(R.string.automation_max_occurrences), keyboardType = KeyboardType.Number, errorText = errorIf(state, "maxOccurrences")) }
        item { LedgerButton(stringResource(R.string.automation_apply_rule), actions.onApplyRule, Modifier.fillMaxWidth(), enabled = AutomationPolicy.canSaveRecurrence(state)) }
    }
    if (startPicker) AutomationDatePicker(draft.startDate, { actions.onRecurrenceField(RecurrenceField.START_DATE, it); startPicker = false }, { startPicker = false })
    if (endPicker) AutomationDatePicker(draft.endDate, { actions.onRecurrenceField(RecurrenceField.END_DATE, it); endPicker = false }, { endPicker = false })
}

@Composable
private fun OccurrencePreview(state: AutomationFeatureState, actions: AutomationActions) {
    val series = state.selectedSeries
    if (series == null || series.preview.isEmpty()) {
        EmptyScreen(Modifier.testTag(LedgerTestTags.AUTOMATION_PREVIEW), R.string.automation_preview_empty, R.string.automation_preview_empty_body) { actions.onNavigate("AUT-005", state.selectedSeriesId) }
        return
    }
    val locale = LocalLocale.current.platformLocale
    ScreenList(Modifier.testTag(LedgerTestTags.AUTOMATION_PREVIEW)) {
        item { LedgerBanner(stringResource(R.string.automation_timezone_disclosure, series.zoneId.id, series.occurrenceTime.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))), LedgerBannerVariant.INFO) }
        items(series.preview.take(10), key = { it.occurrenceInstant.toString() }) { occurrence ->
            LedgerCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm), horizontalArrangement = Arrangement.SpaceBetween) {
                    LedgerText(occurrence.localDate.localized(locale), LedgerTextRole.BODY)
                    LedgerText(occurrence.occurrenceInstant.localized(locale, series.zoneId), LedgerTextRole.SUPPORTING)
                }
            }
        }
    }
}

@Composable
private fun CandidateList(state: AutomationFeatureState, actions: AutomationActions) {
    val pending = state.snapshot.candidates.filter { it.status in setOf(RecurrenceCandidateStatus.PENDING_CONFIRMATION, RecurrenceCandidateStatus.INVALID) }
    if (pending.isEmpty()) {
        EmptyScreen(Modifier.testTag(LedgerTestTags.AUTOMATION_CANDIDATES), R.string.automation_candidate_empty, R.string.automation_candidate_empty_body) { actions.onNavigate("AUT-001", null) }
        return
    }
    ScreenList(Modifier.testTag(LedgerTestTags.AUTOMATION_CANDIDATES)) {
        item { LedgerBanner(stringResource(R.string.automation_candidate_not_posted), LedgerBannerVariant.INFO) }
        items(pending, key = { it.id.toString() }) { candidate -> CandidateCard(candidate, candidate.id in state.selectedCandidateIds, actions) }
        if (state.selectedCandidateIds.isNotEmpty()) {
            item { LedgerText(stringResource(R.string.automation_selection_count, state.selectedCandidateIds.size), LedgerTextRole.SUPPORTING) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                    LedgerButton(stringResource(R.string.automation_review_selected), actions.onReviewSelectedCandidates, Modifier.weight(1f), LedgerButtonVariant.SECONDARY)
                    LedgerButton(stringResource(R.string.automation_skip_selected), actions.onSkipSelectedCandidates, Modifier.weight(1f), LedgerButtonVariant.DANGER)
                }
            }
        }
    }
}

@Composable
private fun CandidateCard(candidate: CandidateView, selected: Boolean, actions: AutomationActions) {
    LedgerCard(Modifier.fillMaxWidth(), onClick = { actions.onCandidateSelected(candidate.id) }) {
        Column(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LedgerText(candidate.blueprint.name, LedgerTextRole.SECTION)
                StatusBadge(if (candidate.status == RecurrenceCandidateStatus.INVALID) stringResource(R.string.automation_invalid) else stringResource(R.string.automation_not_posted), if (candidate.status == RecurrenceCandidateStatus.INVALID) LedgerStatusVariant.DANGER else LedgerStatusVariant.CANDIDATE)
            }
            LedgerText(candidate.localDate.localized(LocalLocale.current.platformLocale), LedgerTextRole.BODY)
            candidate.validationErrorCode?.let { LedgerText(candidateErrorLabel(it), LedgerTextRole.SUPPORTING) }
            LedgerButton(if (selected) stringResource(R.string.automation_unselect) else stringResource(R.string.automation_select), { actions.onCandidateToggle(candidate.id) }, variant = LedgerButtonVariant.TEXT, compact = true)
        }
    }
}

@Composable
private fun CandidateEditor(state: AutomationFeatureState, actions: AutomationActions) {
    val candidate = state.selectedCandidate
    if (candidate == null || state.presentation == AutomationPresentation.INVALID_SOURCE) {
        Box(Modifier.fillMaxSize().testTag(LedgerTestTags.AUTOMATION_CANDIDATE_EDITOR)) { LedgerErrorState(UiErrorCode("AUTOMATION_INVALID_SOURCE"), stringResource(R.string.automation_candidate_invalid_source), actions.onRetry) }
        return
    }
    ScreenList(Modifier.testTag(LedgerTestTags.AUTOMATION_CANDIDATE_EDITOR)) {
        item { LedgerBanner(stringResource(R.string.automation_candidate_not_posted), LedgerBannerVariant.WARNING) }
        if (state.presentation == AutomationPresentation.VALIDATION_ERROR) item { LedgerBanner(stringResource(R.string.automation_candidate_needs_fields), LedgerBannerVariant.DANGER) }
        item {
            FormSection(stringResource(R.string.automation_candidate_source)) {
                LedgerText(candidate.blueprint.name, LedgerTextRole.SECTION)
                LedgerText(candidate.localDate.localized(LocalLocale.current.platformLocale), LedgerTextRole.BODY)
                LedgerText(candidate.blueprint.amountExpression ?: stringResource(R.string.automation_amount_when_used), LedgerTextRole.BODY)
                LedgerText(stringResource(R.string.automation_candidate_full_form), LedgerTextRole.SUPPORTING)
            }
        }
        item { LedgerButton(stringResource(R.string.automation_confirm_and_post), actions.onConfirmCandidate, Modifier.fillMaxWidth()) }
        item { LedgerButton(stringResource(R.string.automation_skip_candidate), actions.onSkipCandidate, Modifier.fillMaxWidth(), LedgerButtonVariant.DANGER) }
        item { LedgerButton(stringResource(R.string.automation_cancel_candidate), actions.onCancelCandidate, Modifier.fillMaxWidth(), LedgerButtonVariant.TEXT) }
    }
}

@Composable
private fun ScopePicker(state: AutomationFeatureState, actions: AutomationActions) {
    ScreenList(Modifier.testTag(LedgerTestTags.AUTOMATION_SCOPE)) {
        item { LedgerText(stringResource(R.string.automation_scope_explanation), LedgerTextRole.SUPPORTING) }
        item {
            FormSection(stringResource(R.string.automation_scope_title)) {
                RecurrenceModificationScope.entries.forEach { scope -> LedgerChoiceRow(scopeLabel(scope), state.modificationScope == scope, { actions.onScope(scope) }, supportingText = scopeBody(scope)) }
            }
        }
        item { LedgerButton(stringResource(R.string.automation_apply_scope), actions.onApplyScope, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun HubCard(title: String, body: String, onClick: () -> Unit) {
    LedgerCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm)) {
            LedgerText(title, LedgerTextRole.SECTION)
            LedgerText(body, LedgerTextRole.SUPPORTING)
        }
    }
}

@Composable
private fun EmptyScreen(modifier: Modifier, title: Int, body: Int, action: () -> Unit) {
    Box(modifier.fillMaxSize()) { LedgerEmptyState(stringResource(title), stringResource(body), stringResource(R.string.automation_create), action) }
}

@Composable
private fun ScreenList(modifier: Modifier, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(modifier.fillMaxSize().padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm), content = content)
}

@Composable private fun errorIf(state: AutomationFeatureState, key: String): String? = if (key in state.validationFields) stringResource(R.string.automation_required_error) else null

@Composable private fun kindLabel(kind: TransactionKind): String = stringResource(
    when (kind) {
        TransactionKind.EXPENSE -> R.string.automation_kind_expense
        TransactionKind.INCOME -> R.string.automation_kind_income
        TransactionKind.CREDIT_PAYMENT -> R.string.automation_kind_credit_payment
        TransactionKind.LOAN_PAYMENT -> R.string.automation_kind_loan_payment
        else -> R.string.automation_kind_other
    },
)

@Composable private fun frequencyLabel(value: RecurrenceFrequency): String = stringResource(frequencyResources.getValue(value))

@Composable private fun dayLabel(value: DayOfWeek): String = stringResource(dayResources.getValue(value))

@Composable private fun missingDayLabel(value: MissingDayPolicy): String = stringResource(if (value == MissingDayPolicy.SKIP) R.string.automation_missing_skip else R.string.automation_missing_month_end)

@Composable private fun weekendLabel(value: WeekendAdjustment): String = stringResource(
    when (value) {
        WeekendAdjustment.NONE -> R.string.automation_weekend_none
        WeekendAdjustment.PREVIOUS_BUSINESS_DAY -> R.string.automation_weekend_previous
        WeekendAdjustment.NEXT_BUSINESS_DAY -> R.string.automation_weekend_next
    },
)

@Composable private fun seriesStatus(value: RecurrenceStatus): String = stringResource(
    if (value == RecurrenceStatus.ACTIVE) {
        R.string.automation_active
    } else if (value == RecurrenceStatus.PAUSED) {
        R.string.automation_paused
    } else {
        R.string.automation_archived
    },
)

@Composable private fun scopeLabel(value: RecurrenceModificationScope): String = stringResource(
    when (value) {
        RecurrenceModificationScope.THIS_OCCURRENCE -> R.string.automation_scope_this
        RecurrenceModificationScope.THIS_AND_FUTURE -> R.string.automation_scope_future
        RecurrenceModificationScope.ENTIRE_SERIES -> R.string.automation_scope_entire
    },
)

@Composable private fun scopeBody(value: RecurrenceModificationScope): String = stringResource(
    when (value) {
        RecurrenceModificationScope.THIS_OCCURRENCE -> R.string.automation_scope_this_body
        RecurrenceModificationScope.THIS_AND_FUTURE -> R.string.automation_scope_future_body
        RecurrenceModificationScope.ENTIRE_SERIES -> R.string.automation_scope_entire_body
    },
)

@Composable
private fun ruleSummary(series: RecurrenceSeriesView): String = ruleSummary(series.rule, series.startDate.toString())

@Composable
private fun ruleSummary(rule: app.ledger.finance.domain.RecurrenceRule, startDate: String): String {
    val locale = LocalLocale.current.platformLocale
    val date = startDate.toLocalDateOrNull()?.localized(locale) ?: startDate
    return stringResource(R.string.automation_rule_summary, frequencyLabel(rule.frequency), rule.interval, date)
}

@Composable
private fun templateIconLabel(icon: LedgerIcon): String = stringResource(
    when (icon) {
        LedgerIcon.RECORD -> R.string.automation_icon_record
        LedgerIcon.ACCOUNT -> R.string.automation_icon_account
        LedgerIcon.TRANSFER -> R.string.automation_icon_transfer
        else -> R.string.automation_icon_calendar
    },
)

@Composable
private fun candidateErrorLabel(code: String): String = stringResource(
    when {
        code.contains("AMOUNT", ignoreCase = true) -> R.string.automation_candidate_error_amount
        code.contains("ACCOUNT", ignoreCase = true) -> R.string.automation_candidate_error_account
        code.contains("CATEGORY", ignoreCase = true) -> R.string.automation_candidate_error_category
        code.contains("SOURCE", ignoreCase = true) -> R.string.automation_candidate_error_source
        else -> R.string.automation_candidate_error_fields
    },
)

@Composable
private fun AutomationDatePicker(value: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val initial = value.toLocalDateOrNull() ?: LocalDate.now()
    LedgerDatePickerFlow(
        initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        { millis -> onConfirm(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()) },
        onDismiss,
    )
}

private fun LocalDate.localized(locale: java.util.Locale): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(this)
private fun Instant.localized(locale: java.util.Locale, zoneId: java.time.ZoneId): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale).withZone(zoneId).format(this)
private fun String.toLocalDateOrNull(): LocalDate? = runCatching { LocalDate.parse(this) }.getOrNull()

private val supportedBlueprintKinds = setOf(TransactionKind.EXPENSE, TransactionKind.INCOME, TransactionKind.CREDIT_PAYMENT, TransactionKind.LOAN_PAYMENT)
private val templateIcons = listOf(LedgerIcon.RECORD, LedgerIcon.ACCOUNT, LedgerIcon.TRANSFER, LedgerIcon.ANALYSIS)
private val frequencyResources = mapOf(
    RecurrenceFrequency.DAILY to R.string.automation_frequency_daily,
    RecurrenceFrequency.BUSINESS_DAYS to R.string.automation_frequency_business,
    RecurrenceFrequency.WEEKLY to R.string.automation_frequency_weekly,
    RecurrenceFrequency.MONTHLY_DAY to R.string.automation_frequency_month_day,
    RecurrenceFrequency.MONTHLY_LAST_DAY to R.string.automation_frequency_month_end,
    RecurrenceFrequency.MONTHLY_NTH_WEEKDAY to R.string.automation_frequency_nth_weekday,
    RecurrenceFrequency.MONTH_INTERVAL to R.string.automation_frequency_month_interval,
    RecurrenceFrequency.YEARLY to R.string.automation_frequency_yearly,
    RecurrenceFrequency.CUSTOM_INTERVAL to R.string.automation_frequency_custom,
)
private val dayResources = mapOf(
    DayOfWeek.MONDAY to R.string.automation_day_monday,
    DayOfWeek.TUESDAY to R.string.automation_day_tuesday,
    DayOfWeek.WEDNESDAY to R.string.automation_day_wednesday,
    DayOfWeek.THURSDAY to R.string.automation_day_thursday,
    DayOfWeek.FRIDAY to R.string.automation_day_friday,
    DayOfWeek.SATURDAY to R.string.automation_day_saturday,
    DayOfWeek.SUNDAY to R.string.automation_day_sunday,
)
