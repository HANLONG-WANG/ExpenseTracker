package app.ledger.feature.liabilities

import app.ledger.core.common.StableId
import app.ledger.finance.domain.LoanRepaymentMethod
import app.ledger.finance.domain.LoanPrepaymentPolicy
import app.ledger.finance.domain.LoanRateType
import app.ledger.finance.domain.PaymentFrequency
import app.ledger.finance.domain.PrepaymentRecalculationStrategy
import java.math.RoundingMode
import java.time.Instant

public sealed interface LoanScreenAction {
    public data object Retry : LoanScreenAction
    public data class Navigate(val screenId: String, val primaryId: StableId?, val secondaryId: StableId?) : LoanScreenAction
    public data class FieldChanged(val field: LoanField, val value: String) : LoanScreenAction
    public data class SelectContract(val contractId: StableId) : LoanScreenAction
    public data class SelectTranche(val trancheId: StableId) : LoanScreenAction
    public data class RepaymentMethodChanged(val method: LoanRepaymentMethod) : LoanScreenAction
    public data class StrategyChanged(val strategy: PrepaymentRecalculationStrategy) : LoanScreenAction
    public data object Preview : LoanScreenAction
    public data object Save : LoanScreenAction
    public data object Simulate : LoanScreenAction
    public data object ApplySimulation : LoanScreenAction
    public data object CreateLoanAccount : LoanScreenAction
    public data class OpenCreditAccount(val accountId: StableId) : LoanScreenAction
}

internal class LoanActions(
    val onRetry: () -> Unit,
    val onNavigate: (String, StableId?, StableId?) -> Unit,
    val onFieldChanged: (LoanField, String) -> Unit,
    val onSelectContract: (StableId) -> Unit,
    val onSelectTranche: (StableId) -> Unit,
    val onSelectPaymentAccount: (StableId) -> Unit,
    val onSelectScheduleInstallment: (Int) -> Unit,
    val onOperationOccurredAt: (Instant) -> Unit,
    val onRepaymentMethod: (LoanRepaymentMethod) -> Unit,
    val onStrategy: (PrepaymentRecalculationStrategy) -> Unit,
    val onRateType: (LoanRateType) -> Unit,
    val onFrequency: (PaymentFrequency) -> Unit,
    val onPrepaymentPolicy: (LoanPrepaymentPolicy) -> Unit,
    val onRoundingMode: (RoundingMode) -> Unit,
    val onWizardNext: () -> Unit,
    val onWizardBack: () -> Unit,
    val onAddTranche: () -> Unit,
    val onSelectWizardTranche: (Int) -> Unit,
    val onAddRatePeriod: () -> Unit,
    val onEditRatePeriod: (Int) -> Unit,
    val onPreview: () -> Unit,
    val onSave: () -> Unit,
    val onSimulate: () -> Unit,
    val onApplySimulation: () -> Unit,
    val onCreateLoanAccount: () -> Unit = {},
    val onOpenCreditAccount: (StableId) -> Unit = {},
)

internal fun loanActions(onAction: (LoanScreenAction) -> Unit): LoanActions = LoanActions(
    onRetry = { onAction(LoanScreenAction.Retry) },
    onNavigate = { screenId, primary, secondary -> onAction(LoanScreenAction.Navigate(screenId, primary, secondary)) },
    onFieldChanged = { field, value -> onAction(LoanScreenAction.FieldChanged(field, value)) },
    onSelectContract = { onAction(LoanScreenAction.SelectContract(it)) },
    onSelectTranche = { onAction(LoanScreenAction.SelectTranche(it)) },
    onRepaymentMethod = { onAction(LoanScreenAction.RepaymentMethodChanged(it)) },
    onStrategy = { onAction(LoanScreenAction.StrategyChanged(it)) },
    onPreview = { onAction(LoanScreenAction.Preview) },
    onSave = { onAction(LoanScreenAction.Save) },
    onSimulate = { onAction(LoanScreenAction.Simulate) },
    onApplySimulation = { onAction(LoanScreenAction.ApplySimulation) },
    onCreateLoanAccount = { onAction(LoanScreenAction.CreateLoanAccount) },
    onOpenCreditAccount = { onAction(LoanScreenAction.OpenCreditAccount(it)) },
)
