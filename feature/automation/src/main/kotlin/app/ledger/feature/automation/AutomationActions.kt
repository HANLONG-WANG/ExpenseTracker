package app.ledger.feature.automation

import app.ledger.core.common.StableId
import app.ledger.finance.domain.MissingDayPolicy
import app.ledger.finance.domain.RecurrenceFrequency
import app.ledger.finance.domain.RecurrenceGenerationMode
import app.ledger.finance.domain.RecurrenceModificationScope
import app.ledger.finance.domain.TransactionKind
import app.ledger.finance.domain.WeekendAdjustment
import java.time.DayOfWeek

public data class AutomationActions(
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
    val onApplyRule: () -> Unit = {},
)
