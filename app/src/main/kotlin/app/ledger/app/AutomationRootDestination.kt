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
import app.ledger.feature.automation.AutomationActions
import app.ledger.feature.automation.AutomationDestination
import app.ledger.feature.automation.AutomationLoadState
import app.ledger.feature.automation.AutomationPresentation
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
    val state by viewModel.automation.collectAsStateWithLifecycle()
    LaunchedEffect(screenId, blueprintId, seriesId, candidateId) {
        viewModel.loadAutomation(screenId, blueprintId, seriesId, candidateId)
    }
    AutomationDestination(
        screenId,
        state,
        AutomationActions(
            onRetry = { viewModel.loadAutomation(screenId, blueprintId, seriesId, candidateId) },
            onNavigate = { target, stableId ->
                viewModel.navigateAutomation(target, stableId)
                onNavigationChanged()
            },
            onSearch = viewModel::updateAutomationSearch,
            onBlueprintField = viewModel::updateAutomationBlueprintField,
            onBlueprintKind = viewModel::updateAutomationBlueprintKind,
            onBlueprintReference = viewModel::updateAutomationBlueprintReference,
            onSaveBlueprint = viewModel::saveAutomationBlueprint,
            onRecurrenceField = viewModel::updateAutomationRecurrenceField,
            onRecurrenceBlueprint = viewModel::selectAutomationRecurrenceBlueprint,
            onFrequency = viewModel::updateAutomationFrequency,
            onWeekday = viewModel::toggleAutomationWeekday,
            onMissingDay = viewModel::updateAutomationMissingDay,
            onWeekend = viewModel::updateAutomationWeekend,
            onGenerationMode = viewModel::updateAutomationGenerationMode,
            onNotifyCandidate = viewModel::updateAutomationNotifyCandidate,
            onSaveRecurrence = viewModel::saveAutomationRecurrence,
            onTemplateSelected = { id ->
                viewModel.selectAutomationTemplate(id)
                onNavigationChanged()
            },
            onCandidateSelected = viewModel::selectAutomationCandidate,
            onCandidateToggle = viewModel::toggleAutomationCandidate,
            onConfirmCandidate = viewModel::confirmAutomationCandidate,
            onSkipCandidate = viewModel::skipAutomationCandidate,
            onScope = viewModel::updateAutomationScope,
            onApplyScope = viewModel::applyAutomationScope,
            onApplyRule = {
                viewModel.applyAutomationRule()
                onNavigationChanged()
            },
        ),
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
