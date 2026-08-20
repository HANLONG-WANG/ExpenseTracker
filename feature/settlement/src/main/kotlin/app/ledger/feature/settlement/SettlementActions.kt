package app.ledger.feature.settlement

import app.ledger.core.common.StableId
import app.ledger.finance.domain.SettlementChargeDistribution
import app.ledger.finance.domain.SettlementRoundingRule
import app.ledger.finance.domain.SettlementSplitMethod

public sealed interface SettlementScreenAction {
    public data object Retry : SettlementScreenAction
    public data class Navigate(val screenId: String, val activityId: StableId?, val participantId: StableId?) : SettlementScreenAction
    public data class FieldChanged(val field: SettlementField, val value: String) : SettlementScreenAction
    public data class SelectActivity(val activityId: StableId) : SettlementScreenAction
    public data class SelectPayer(val participantId: StableId) : SettlementScreenAction
    public data class SelectPayee(val participantId: StableId) : SettlementScreenAction
    public data class SelectAccount(val accountId: StableId?) : SettlementScreenAction
    public data class SelectProject(val projectId: StableId?) : SettlementScreenAction
    public data class SplitMethodChanged(val method: SettlementSplitMethod) : SettlementScreenAction
    public data class ChargeDistributionChanged(val distribution: SettlementChargeDistribution) : SettlementScreenAction
    public data class RoundingRuleChanged(val rule: SettlementRoundingRule) : SettlementScreenAction
    public data class ToggleParticipant(val participantId: StableId) : SettlementScreenAction
    public data class MoveParticipant(val participantId: StableId, val offset: Int) : SettlementScreenAction
    public data object AddParticipant : SettlementScreenAction
    public data object Save : SettlementScreenAction
    public data object Rebuild : SettlementScreenAction
}

internal class SettlementActions(
    val onRetry: () -> Unit,
    val onNavigate: (String, StableId?, StableId?) -> Unit,
    val onFieldChanged: (SettlementField, String) -> Unit,
    val onSelectActivity: (StableId) -> Unit,
    val onSelectPayer: (StableId) -> Unit,
    val onSelectPayee: (StableId) -> Unit,
    val onSelectAccount: (StableId?) -> Unit,
    val onSelectProject: (StableId?) -> Unit,
    val onSplitMethod: (SettlementSplitMethod) -> Unit,
    val onChargeDistribution: (SettlementChargeDistribution) -> Unit,
    val onRoundingRule: (SettlementRoundingRule) -> Unit,
    val onToggleParticipant: (StableId) -> Unit,
    val onMoveParticipant: (StableId, Int) -> Unit,
    val onAddParticipant: () -> Unit,
    val onSave: () -> Unit,
    val onRebuild: () -> Unit,
)

internal fun settlementActions(onAction: (SettlementScreenAction) -> Unit): SettlementActions = SettlementActions(
    onRetry = { onAction(SettlementScreenAction.Retry) },
    onNavigate = { screenId, activityId, participantId -> onAction(SettlementScreenAction.Navigate(screenId, activityId, participantId)) },
    onFieldChanged = { field, value -> onAction(SettlementScreenAction.FieldChanged(field, value)) },
    onSelectActivity = { onAction(SettlementScreenAction.SelectActivity(it)) },
    onSelectPayer = { onAction(SettlementScreenAction.SelectPayer(it)) },
    onSelectPayee = { onAction(SettlementScreenAction.SelectPayee(it)) },
    onSelectAccount = { onAction(SettlementScreenAction.SelectAccount(it)) },
    onSelectProject = { onAction(SettlementScreenAction.SelectProject(it)) },
    onSplitMethod = { onAction(SettlementScreenAction.SplitMethodChanged(it)) },
    onChargeDistribution = { onAction(SettlementScreenAction.ChargeDistributionChanged(it)) },
    onRoundingRule = { onAction(SettlementScreenAction.RoundingRuleChanged(it)) },
    onToggleParticipant = { onAction(SettlementScreenAction.ToggleParticipant(it)) },
    onMoveParticipant = { id, offset -> onAction(SettlementScreenAction.MoveParticipant(id, offset)) },
    onAddParticipant = { onAction(SettlementScreenAction.AddParticipant) },
    onSave = { onAction(SettlementScreenAction.Save) },
    onRebuild = { onAction(SettlementScreenAction.Rebuild) },
)
