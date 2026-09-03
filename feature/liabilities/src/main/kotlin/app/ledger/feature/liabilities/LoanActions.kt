package app.ledger.feature.liabilities

import app.ledger.core.common.StableId
import app.ledger.finance.domain.LoanPrepaymentPolicy
import app.ledger.finance.domain.LoanRateType
import app.ledger.finance.domain.LoanRepaymentMethod
import app.ledger.finance.domain.PaymentFrequency
import app.ledger.finance.domain.PrepaymentRecalculationStrategy
import java.math.RoundingMode
import java.time.Instant

public data class LoanActions(
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
    val onCancelConfirmation: () -> Unit = {},
    val onCreateLoanAccount: () -> Unit = {},
    val onCreateCreditAccount: () -> Unit = {},
    val onOpenCreditAccount: (StableId) -> Unit = {},
    val onOpenTransactions: (StableId) -> Unit = {},
)
