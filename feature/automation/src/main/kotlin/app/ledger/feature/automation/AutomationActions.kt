package app.ledger.feature.automation

import app.ledger.core.common.StableId
import app.ledger.finance.domain.MissingDayPolicy
import app.ledger.finance.domain.RecurrenceFrequency
import app.ledger.finance.domain.RecurrenceGenerationMode
import app.ledger.finance.domain.RecurrenceModificationScope
import app.ledger.finance.domain.TransactionKind
import app.ledger.finance.domain.WeekendAdjustment
import java.time.DayOfWeek

public sealed interface AutomationScreenAction {
    public data object Retry : AutomationScreenAction
    public data class Navigate(val screenId: String, val id: StableId?) : AutomationScreenAction
    public data class Search(val query: String) : AutomationScreenAction
    public data class BlueprintFieldChanged(val field: BlueprintField, val value: String) : AutomationScreenAction
    public data class BlueprintKindChanged(val kind: TransactionKind) : AutomationScreenAction
    public data class BlueprintReferenceChanged(val field: String, val id: StableId?) : AutomationScreenAction
    public data object SaveBlueprint : AutomationScreenAction
    public data class RecurrenceFieldChanged(val field: RecurrenceField, val value: String) : AutomationScreenAction
    public data class RecurrenceBlueprintSelected(val blueprintId: StableId) : AutomationScreenAction
    public data class FrequencyChanged(val frequency: RecurrenceFrequency) : AutomationScreenAction
    public data class WeekdayToggled(val weekday: DayOfWeek) : AutomationScreenAction
    public data class MissingDayChanged(val policy: MissingDayPolicy) : AutomationScreenAction
    public data class WeekendChanged(val adjustment: WeekendAdjustment) : AutomationScreenAction
    public data class GenerationModeChanged(val mode: RecurrenceGenerationMode) : AutomationScreenAction
    public data class NotifyCandidateChanged(val enabled: Boolean) : AutomationScreenAction
    public data object SaveRecurrence : AutomationScreenAction
    public data class TemplateSelected(val templateId: StableId) : AutomationScreenAction
    public data class CandidateSelected(val candidateId: StableId) : AutomationScreenAction
    public data class CandidateToggled(val candidateId: StableId) : AutomationScreenAction
    public data object ConfirmCandidate : AutomationScreenAction
    public data object SkipCandidate : AutomationScreenAction
    public data class ScopeChanged(val scope: RecurrenceModificationScope) : AutomationScreenAction
    public data object ApplyScope : AutomationScreenAction
}

internal class AutomationActions(
    val onRetry: () -> Unit,
    val onNavigate: (String, StableId?) -> Unit,
    val onSearch: (String) -> Unit,
    val onBlueprintField: (BlueprintField, String) -> Unit,
    val onBlueprintKind: (TransactionKind) -> Unit,
    val onBlueprintReference: (String, StableId?) -> Unit,
    val onSaveBlueprint: () -> Unit,
    val onRecurrenceField: (RecurrenceField, String) -> Unit,
    val onRecurrenceBlueprint: (StableId) -> Unit,
    val onFrequency: (RecurrenceFrequency) -> Unit,
    val onWeekday: (DayOfWeek) -> Unit,
    val onMissingDay: (MissingDayPolicy) -> Unit,
    val onWeekend: (WeekendAdjustment) -> Unit,
    val onGenerationMode: (RecurrenceGenerationMode) -> Unit,
    val onNotifyCandidate: (Boolean) -> Unit,
    val onSaveRecurrence: () -> Unit,
    val onTemplateSelected: (StableId) -> Unit,
    val onCandidateSelected: (StableId) -> Unit,
    val onCandidateToggle: (StableId) -> Unit,
    val onConfirmCandidate: () -> Unit,
    val onSkipCandidate: () -> Unit,
    val onScope: (RecurrenceModificationScope) -> Unit,
    val onApplyScope: () -> Unit,
)

internal fun automationActions(onAction: (AutomationScreenAction) -> Unit): AutomationActions = AutomationActions(
    onRetry = { onAction(AutomationScreenAction.Retry) },
    onNavigate = { screenId, id -> onAction(AutomationScreenAction.Navigate(screenId, id)) },
    onSearch = { onAction(AutomationScreenAction.Search(it)) },
    onBlueprintField = { field, value -> onAction(AutomationScreenAction.BlueprintFieldChanged(field, value)) },
    onBlueprintKind = { onAction(AutomationScreenAction.BlueprintKindChanged(it)) },
    onBlueprintReference = { field, id -> onAction(AutomationScreenAction.BlueprintReferenceChanged(field, id)) },
    onSaveBlueprint = { onAction(AutomationScreenAction.SaveBlueprint) },
    onRecurrenceField = { field, value -> onAction(AutomationScreenAction.RecurrenceFieldChanged(field, value)) },
    onRecurrenceBlueprint = { onAction(AutomationScreenAction.RecurrenceBlueprintSelected(it)) },
    onFrequency = { onAction(AutomationScreenAction.FrequencyChanged(it)) },
    onWeekday = { onAction(AutomationScreenAction.WeekdayToggled(it)) },
    onMissingDay = { onAction(AutomationScreenAction.MissingDayChanged(it)) },
    onWeekend = { onAction(AutomationScreenAction.WeekendChanged(it)) },
    onGenerationMode = { onAction(AutomationScreenAction.GenerationModeChanged(it)) },
    onNotifyCandidate = { onAction(AutomationScreenAction.NotifyCandidateChanged(it)) },
    onSaveRecurrence = { onAction(AutomationScreenAction.SaveRecurrence) },
    onTemplateSelected = { onAction(AutomationScreenAction.TemplateSelected(it)) },
    onCandidateSelected = { onAction(AutomationScreenAction.CandidateSelected(it)) },
    onCandidateToggle = { onAction(AutomationScreenAction.CandidateToggled(it)) },
    onConfirmCandidate = { onAction(AutomationScreenAction.ConfirmCandidate) },
    onSkipCandidate = { onAction(AutomationScreenAction.SkipCandidate) },
    onScope = { onAction(AutomationScreenAction.ScopeChanged(it)) },
    onApplyScope = { onAction(AutomationScreenAction.ApplyScope) },
)
