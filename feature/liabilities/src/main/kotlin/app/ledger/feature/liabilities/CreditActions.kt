package app.ledger.feature.liabilities

import app.ledger.core.common.StableId
import app.ledger.finance.domain.StatementAssignmentMode

public sealed interface CreditScreenAction {
    public data object Retry : CreditScreenAction
    public data class Navigate(val screenId: String, val id: StableId?) : CreditScreenAction
    public data class FieldChanged(val field: CreditField, val value: String) : CreditScreenAction
    public data object NextPaymentAccount : CreditScreenAction
    public data class SelectStatement(val statementId: StableId?) : CreditScreenAction
    public data object SelectEarliest : CreditScreenAction
    public data object SelectUnallocated : CreditScreenAction
    public data class Assignment(val mode: StatementAssignmentMode) : CreditScreenAction
    public data class ToggleAutoPayment(val enabled: Boolean) : CreditScreenAction
}

internal class CreditActions(
    val onRetry: () -> Unit,
    val onNavigate: (String, StableId?) -> Unit,
    val onFieldChanged: (CreditField, String) -> Unit,
    val onNextPaymentAccount: () -> Unit,
    val onNextZone: () -> Unit,
    val onCycleDueRule: () -> Unit,
    val onSelectStatement: (StableId?) -> Unit,
    val onSelectEarliest: () -> Unit,
    val onSelectUnallocated: () -> Unit,
    val onAssignment: (StatementAssignmentMode) -> Unit,
    val onToggleAutoPayment: (Boolean) -> Unit,
    val onToggleSeal: (Boolean) -> Unit,
)

internal fun creditActions(onAction: (CreditScreenAction) -> Unit): CreditActions = CreditActions(
    onRetry = { onAction(CreditScreenAction.Retry) },
    onNavigate = { screenId, id -> onAction(CreditScreenAction.Navigate(screenId, id)) },
    onFieldChanged = { field, value -> onAction(CreditScreenAction.FieldChanged(field, value)) },
    onNextPaymentAccount = { onAction(CreditScreenAction.NextPaymentAccount) },
    onSelectStatement = { onAction(CreditScreenAction.SelectStatement(it)) },
    onSelectEarliest = { onAction(CreditScreenAction.SelectEarliest) },
    onSelectUnallocated = { onAction(CreditScreenAction.SelectUnallocated) },
    onAssignment = { onAction(CreditScreenAction.Assignment(it)) },
    onToggleAutoPayment = { onAction(CreditScreenAction.ToggleAutoPayment(it)) },
)
