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
import androidx.compose.ui.Modifier
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
import app.ledger.core.designsystem.LedgerStatusVariant
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextField
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.LedgerToggleRow
import app.ledger.core.designsystem.StatusBadge
import app.ledger.core.designsystem.UiErrorCode
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
import java.time.format.DateTimeFormatter

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
                    StatusBadge(state.snapshot.candidates.count { it.status == RecurrenceCandidateStatus.PENDING_CONFIRMATION }.toString(), LedgerStatusVariant.CANDIDATE)
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
    ScreenList(Modifier.testTag(LedgerTestTags.AUTOMATION_TEMPLATE_LIST)) {
        items(state.snapshot.blueprints, key = { it.id.toString() }) { template -> TemplateCard(template) { actions.onNavigate("AUT-003", template.id) } }
        item { LedgerButton(stringResource(R.string.automation_add_template), { actions.onNavigate("AUT-003", null) }, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun TemplateCard(blueprint: BlueprintView, onClick: () -> Unit) {
    LedgerCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                LedgerText(blueprint.name, LedgerTextRole.SECTION)
                LedgerText(kindLabel(blueprint.targetKind), LedgerTextRole.SUPPORTING)
                LedgerText(blueprint.amountExpression ?: stringResource(R.string.automation_amount_when_used), LedgerTextRole.BODY)
            }
            StatusBadge(if (blueprint.status == EntityStatus.ACTIVE) stringResource(R.string.automation_active) else stringResource(R.string.automation_archived), if (blueprint.status == EntityStatus.ACTIVE) LedgerStatusVariant.POSITIVE else LedgerStatusVariant.ARCHIVED)
        }
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
                LedgerText(stringResource(R.string.automation_icon_governed), LedgerTextRole.SUPPORTING)
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
        item { LedgerButton(stringResource(R.string.automation_save_template), actions.onSaveBlueprint, Modifier.fillMaxWidth(), enabled = state.presentation != AutomationPresentation.SAVING) }
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
    state.entrySnapshot.references.categories.filter { it.status.name == "ACTIVE" }.take(8).forEach { category ->
        LedgerChoiceRow(category.name, selectedCategoryId == category.id, { onReference("category", category.id) })
    }
    LedgerText(stringResource(R.string.automation_account), LedgerTextRole.LABEL)
    state.entrySnapshot.references.accounts.filter { it.status == EntityStatus.ACTIVE }.forEach { account ->
        LedgerChoiceRow(account.name, selectedAccountId == account.id, { onReference("primaryAccount", account.id) }, supportingText = account.currency.value)
    }
    LedgerText(stringResource(R.string.automation_fixed_place), LedgerTextRole.LABEL)
    LedgerChoiceRow(stringResource(R.string.automation_no_fixed_place), state.blueprintDraft?.fixedPlaceId == null, { onReference("fixedPlace", null) })
    state.entrySnapshot.references.places.filter { it.status == EntityStatus.ACTIVE }.take(5).forEach { place ->
        LedgerChoiceRow(place.name, state.blueprintDraft?.fixedPlaceId == place.id, { onReference("fixedPlace", place.id) })
    }
}

@Composable
private fun SeriesList(state: AutomationFeatureState, actions: AutomationActions) {
    if (state.presentation == AutomationPresentation.EMPTY) {
        EmptyScreen(Modifier.testTag(LedgerTestTags.AUTOMATION_SERIES_LIST), R.string.automation_series_empty, R.string.automation_series_empty_body) { actions.onNavigate("AUT-005", null) }
        return
    }
    ScreenList(Modifier.testTag(LedgerTestTags.AUTOMATION_SERIES_LIST)) {
        if (state.presentation == AutomationPresentation.PAUSED) item { LedgerBanner(stringResource(R.string.automation_all_paused), LedgerBannerVariant.WARNING) }
        item { LedgerText(stringResource(R.string.automation_status_tabs), LedgerTextRole.SUPPORTING) }
        items(state.snapshot.series, key = { it.id.toString() }) { series -> SeriesCard(series) { actions.onNavigate("AUT-005", series.id) } }
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
        item { LedgerBanner(stringResource(R.string.automation_fixed_place_notice), LedgerBannerVariant.INFO) }
        item { LedgerButton(stringResource(R.string.automation_preview_next), { actions.onNavigate("AUT-007", draft.id) }, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY) }
        item { LedgerBanner(stringResource(R.string.automation_reality_disclaimer), LedgerBannerVariant.WARNING) }
        item { LedgerButton(stringResource(R.string.automation_save_series), actions.onSaveRecurrence, Modifier.fillMaxWidth(), enabled = state.presentation != AutomationPresentation.SAVING) }
    }
}

@Composable
private fun RuleEditor(state: AutomationFeatureState, actions: AutomationActions) {
    val draft = state.recurrenceDraft ?: return
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
        if (draft.rule.frequency == RecurrenceFrequency.MONTHLY_NTH_WEEKDAY) item { LedgerTextField(draft.rule.nthWeek?.toString().orEmpty(), { actions.onRecurrenceField(RecurrenceField.NTH_WEEK, it) }, stringResource(R.string.automation_nth_week), keyboardType = KeyboardType.Number) }
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
        item { LedgerTextField(draft.startDate, { actions.onRecurrenceField(RecurrenceField.START_DATE, it) }, stringResource(R.string.automation_start_date), required = true, errorText = errorIf(state, "startDate")) }
        item { LedgerTextField(draft.endDate, { actions.onRecurrenceField(RecurrenceField.END_DATE, it) }, stringResource(R.string.automation_end_date), errorText = errorIf(state, "endDate")) }
        item { LedgerTextField(draft.maxOccurrences, { actions.onRecurrenceField(RecurrenceField.MAX_OCCURRENCES, it) }, stringResource(R.string.automation_max_occurrences), keyboardType = KeyboardType.Number, errorText = errorIf(state, "maxOccurrences")) }
        item { LedgerButton(stringResource(R.string.automation_apply_rule), actions.onSaveRecurrence, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun OccurrencePreview(state: AutomationFeatureState, actions: AutomationActions) {
    val series = state.selectedSeries
    if (series == null || series.preview.isEmpty()) {
        EmptyScreen(Modifier.testTag(LedgerTestTags.AUTOMATION_PREVIEW), R.string.automation_preview_empty, R.string.automation_preview_empty_body) { actions.onNavigate("AUT-005", state.selectedSeriesId) }
        return
    }
    ScreenList(Modifier.testTag(LedgerTestTags.AUTOMATION_PREVIEW)) {
        item { LedgerBanner(stringResource(R.string.automation_timezone_disclosure, series.zoneId.id, series.occurrenceTime.toString()), LedgerBannerVariant.INFO) }
        items(series.preview.take(10), key = { it.occurrenceInstant.toString() }) { occurrence ->
            LedgerCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm), horizontalArrangement = Arrangement.SpaceBetween) {
                    LedgerText(occurrence.localDate.format(DateTimeFormatter.ISO_DATE), LedgerTextRole.BODY)
                    LedgerText(occurrence.occurrenceInstant.toString(), LedgerTextRole.SUPPORTING)
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
        if (state.selectedCandidateIds.isNotEmpty()) item { LedgerText(stringResource(R.string.automation_selection_count, state.selectedCandidateIds.size), LedgerTextRole.SUPPORTING) }
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
            LedgerText(candidate.localDate.toString(), LedgerTextRole.BODY)
            candidate.validationErrorCode?.let { LedgerText(it, LedgerTextRole.SUPPORTING) }
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
                LedgerText(candidate.localDate.toString(), LedgerTextRole.BODY)
                LedgerText(candidate.blueprint.amountExpression ?: stringResource(R.string.automation_amount_when_used), LedgerTextRole.BODY)
                LedgerText(stringResource(R.string.automation_candidate_full_form), LedgerTextRole.SUPPORTING)
            }
        }
        item { LedgerButton(stringResource(R.string.automation_open_full_form), actions.onConfirmCandidate, Modifier.fillMaxWidth()) }
        item { LedgerButton(stringResource(R.string.automation_skip_candidate), actions.onSkipCandidate, Modifier.fillMaxWidth(), LedgerButtonVariant.DANGER) }
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

private fun ruleSummary(series: RecurrenceSeriesView): String = ruleSummary(series.rule, series.startDate.toString())
private fun ruleSummary(rule: app.ledger.finance.domain.RecurrenceRule, startDate: String): String = "${rule.frequency.name.lowercase().replace('_', ' ')} · ${rule.interval} · $startDate"

private val supportedBlueprintKinds = setOf(TransactionKind.EXPENSE, TransactionKind.INCOME, TransactionKind.CREDIT_PAYMENT, TransactionKind.LOAN_PAYMENT)
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
