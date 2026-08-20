package app.ledger.feature.liabilities

import app.ledger.core.common.StableId
import app.ledger.finance.domain.LoanRepaymentMethod
import app.ledger.finance.domain.PrepaymentRecalculationStrategy

public data class LoanActions(
    val onRetry: () -> Unit,
    val onNavigate: (String, StableId?, StableId?) -> Unit,
    val onFieldChanged: (LoanField, String) -> Unit,
    val onSelectContract: (StableId) -> Unit,
    val onSelectTranche: (StableId) -> Unit,
    val onRepaymentMethod: (LoanRepaymentMethod) -> Unit,
    val onStrategy: (PrepaymentRecalculationStrategy) -> Unit,
    val onPreview: () -> Unit,
    val onSave: () -> Unit,
    val onSimulate: () -> Unit,
    val onApplySimulation: () -> Unit,
    val onCreateLoanAccount: () -> Unit = {},
    val onOpenCreditAccount: (StableId) -> Unit = {},
)
