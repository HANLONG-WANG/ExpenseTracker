package app.ledger.finance.data

import app.ledger.finance.domain.FinancialCommand
import app.ledger.finance.domain.PlanningSnapshot

internal data class PreparedFinancialMutation(
    val command: FinancialCommand,
    val snapshot: PlanningSnapshot,
    val sideEffect: FinancialCommitSideEffect = FinancialCommitSideEffect.NONE,
    val afterFinancialWriteSideEffect: FinancialCommitSideEffect = FinancialCommitSideEffect.NONE,
)
