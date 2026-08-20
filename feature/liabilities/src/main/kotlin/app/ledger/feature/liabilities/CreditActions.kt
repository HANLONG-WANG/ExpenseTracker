package app.ledger.feature.liabilities

import app.ledger.core.common.StableId
import app.ledger.finance.domain.StatementAssignmentMode

public data class CreditActions(
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
