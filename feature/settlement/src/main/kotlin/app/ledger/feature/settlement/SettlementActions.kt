package app.ledger.feature.settlement

import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.domain.SettlementChargeDistribution
import app.ledger.finance.domain.SettlementRoundingRule
import app.ledger.finance.domain.SettlementSplitMethod

public data class SettlementActions(
    val onRetry: () -> Unit,
    val onNavigate: (String, StableId?, StableId?) -> Unit,
    val onFieldChanged: (SettlementField, String) -> Unit,
    val onSelectActivity: (StableId) -> Unit,
    val onSelectPayer: (StableId) -> Unit,
    val onSelectPayee: (StableId) -> Unit,
    val onSelectAccount: (StableId?) -> Unit,
    val onSelectProject: (StableId?) -> Unit,
    val onSelectCurrency: (CurrencyCode) -> Unit,
    val onSplitMethod: (SettlementSplitMethod) -> Unit,
    val onChargeDistribution: (SettlementChargeDistribution) -> Unit,
    val onRoundingRule: (SettlementRoundingRule) -> Unit,
    val onToggleParticipant: (StableId) -> Unit,
    val onMoveParticipant: (StableId, Int) -> Unit,
    val onAddParticipant: () -> Unit,
    val onSave: () -> Unit,
    val onRebuild: () -> Unit,
)
