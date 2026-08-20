@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package app.ledger.app

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.core.designsystem.LedgerSaveFab
import app.ledger.feature.automation.AutomationDestination
import app.ledger.feature.automation.AutomationLoadState
import app.ledger.feature.automation.AutomationPresentation
import app.ledger.feature.automation.AutomationScreenAction
import app.ledger.feature.automation.R as AutomationR

@Composable
internal fun automationDestinationTitleOrNull(screenId: String): String? = when (screenId) {
    "REC-026" -> stringResource(AutomationR.string.automation_title_template_picker)
    "AUT-001" -> stringResource(AutomationR.string.automation_title_hub)
    "AUT-002" -> stringResource(AutomationR.string.automation_title_templates)
    "AUT-003" -> stringResource(AutomationR.string.automation_title_template_editor)
    "AUT-004" -> stringResource(AutomationR.string.automation_title_recurrences)
    "AUT-005" -> stringResource(AutomationR.string.automation_title_recurrence_editor)
    "AUT-006" -> stringResource(AutomationR.string.automation_title_rule)
    "AUT-007" -> stringResource(AutomationR.string.automation_title_preview)
    "AUT-008" -> stringResource(AutomationR.string.automation_title_candidates)
    "AUT-009" -> stringResource(AutomationR.string.automation_title_candidate)
    "AUT-010" -> stringResource(AutomationR.string.automation_title_scope)
    else -> null
}

@Composable
internal fun AutomationRootDestination(
    screenId: String,
    encodedArguments: Map<String, String>,
    viewModel: AppRootViewModel,
    onNavigationChanged: () -> Unit,
) {
    val blueprintId = encodedArguments.stableId("templateId")
    val seriesId = encodedArguments.stableId("seriesId")
    val candidateId = encodedArguments.stableId("candidateId")
    val uiState by viewModel.automationUiState.collectAsStateWithLifecycle()
    LaunchedEffect(screenId, blueprintId, seriesId, candidateId) {
        viewModel.loadAutomation(screenId, blueprintId, seriesId, candidateId)
    }
    AutomationDestination(
        screenId,
        uiState.loadState,
        { action ->
            when (action) {
                AutomationScreenAction.Retry -> viewModel.loadAutomation(screenId, blueprintId, seriesId, candidateId)
                is AutomationScreenAction.Navigate -> {
                    viewModel.navigateAutomation(action.screenId, action.id)
                    onNavigationChanged()
                }
                is AutomationScreenAction.Search -> viewModel.updateAutomationSearch(action.query)
                is AutomationScreenAction.BlueprintFieldChanged -> viewModel.updateAutomationBlueprintField(action.field, action.value)
                is AutomationScreenAction.BlueprintKindChanged -> viewModel.updateAutomationBlueprintKind(action.kind)
                is AutomationScreenAction.BlueprintReferenceChanged -> viewModel.updateAutomationBlueprintReference(action.field, action.id)
                AutomationScreenAction.SaveBlueprint -> viewModel.saveAutomationBlueprint()
                is AutomationScreenAction.RecurrenceFieldChanged -> viewModel.updateAutomationRecurrenceField(action.field, action.value)
                is AutomationScreenAction.RecurrenceBlueprintSelected -> viewModel.selectAutomationRecurrenceBlueprint(action.blueprintId)
                is AutomationScreenAction.FrequencyChanged -> viewModel.updateAutomationFrequency(action.frequency)
                is AutomationScreenAction.WeekdayToggled -> viewModel.toggleAutomationWeekday(action.weekday)
                is AutomationScreenAction.MissingDayChanged -> viewModel.updateAutomationMissingDay(action.policy)
                is AutomationScreenAction.WeekendChanged -> viewModel.updateAutomationWeekend(action.adjustment)
                is AutomationScreenAction.GenerationModeChanged -> viewModel.updateAutomationGenerationMode(action.mode)
                is AutomationScreenAction.NotifyCandidateChanged -> viewModel.updateAutomationNotifyCandidate(action.enabled)
                AutomationScreenAction.SaveRecurrence -> viewModel.saveAutomationRecurrence()
                is AutomationScreenAction.TemplateSelected -> {
                    viewModel.selectAutomationTemplate(action.templateId)
                    onNavigationChanged()
                }
                is AutomationScreenAction.CandidateSelected -> viewModel.selectAutomationCandidate(action.candidateId)
                is AutomationScreenAction.CandidateToggled -> viewModel.toggleAutomationCandidate(action.candidateId)
                AutomationScreenAction.ConfirmCandidate -> viewModel.confirmAutomationCandidate()
                AutomationScreenAction.SkipCandidate -> viewModel.skipAutomationCandidate()
                is AutomationScreenAction.ScopeChanged -> viewModel.updateAutomationScope(action.scope)
                AutomationScreenAction.ApplyScope -> viewModel.applyAutomationScope()
                AutomationScreenAction.ApplyRule -> {
                    viewModel.applyAutomationRule()
                    onNavigationChanged()
                }
            }
        },
    )
}

internal fun automationFixedAction(
    screenId: String,
    state: AutomationLoadState,
    pending: Boolean,
    onSaveBlueprint: () -> Unit,
    onSaveRecurrence: () -> Unit,
): (@Composable BoxScope.() -> Unit)? {
    if (screenId !in setOf("AUT-003", "AUT-005")) return null
    return {
        val presentation = (state as? AutomationLoadState.Content)?.state?.presentation
        LedgerSaveFab(
            onClick = if (screenId == "AUT-003") onSaveBlueprint else onSaveRecurrence,
            submitting = pending || presentation == AutomationPresentation.SAVING,
            enabled = !pending,
        )
    }
}

private fun Map<String, String>.stableId(name: String): StableId? = get(name)?.let { StableId.parse(it).getOrNull() }
