package app.ledger.feature.liabilities

import app.ledger.core.common.StableId
import app.ledger.finance.application.CreditAccountView
import app.ledger.finance.application.LoanContractView
import app.ledger.finance.application.LoanSnapshot
import app.ledger.finance.application.LoanTrancheView
import app.ledger.finance.domain.LoanPrepaymentSimulation
import app.ledger.finance.domain.LoanRepaymentMethod
import app.ledger.finance.domain.LoanScheduleRevision
import app.ledger.finance.domain.LoanStatus
import app.ledger.finance.domain.PrepaymentRecalculationStrategy

private const val LOAN_DATE_INPUT_MAX_CHARS = 10
private const val LOAN_TEXT_INPUT_MAX_CHARS = 48

public enum class LoanPresentation {
    CONTENT,
    EMPTY,
    OVERDUE,
    CLOSED,
    EDITING,
    ALLOCATION_ERROR,
    SAVING,
    PRINCIPAL_EXCEEDED,
    SUM_MISMATCH,
    INVALID,
    GENERATING_SCHEDULE,
    READY,
    CREATE,
    EDIT,
    OVERLAP_ERROR,
    GENERATING,
    CALCULATION_ERROR,
    ACTIVE,
    OVERDUE_PLAN_DIFFERENCE,
    MULTI_TRANCHE,
    CALCULATING,
    RESULT,
    CONFLICT,
}

public enum class LoanField {
    NAME,
    LENDER,
    PRINCIPAL,
    PAYMENT_COUNT,
    START_DATE,
    FIRST_PAYMENT_DATE,
    END_DATE,
    ANNUAL_RATE,
    FEE_PER_PAYMENT,
    AMOUNT,
    PRINCIPAL_COMPONENT,
    INTEREST_COMPONENT,
    FEE_COMPONENT,
    PENALTY_COMPONENT,
    CONFIRM_PHRASE,
}

public data class LoanDraft(
    val name: String = "",
    val lender: String = "",
    val principal: String = "",
    val paymentCount: String = "12",
    val startDate: String = "",
    val firstPaymentDate: String = "",
    val endDate: String = "",
    val annualRate: String = "",
    val feePerPayment: String = "",
    val amount: String = "",
    val principalComponent: String = "",
    val interestComponent: String = "",
    val feeComponent: String = "",
    val penaltyComponent: String = "",
    val confirmPhrase: String = "",
    val repaymentMethod: LoanRepaymentMethod = LoanRepaymentMethod.EQUAL_PAYMENT,
    val strategy: PrepaymentRecalculationStrategy = PrepaymentRecalculationStrategy.SHORTEN_TERM,
)

public data class LoanFeatureState(
    val snapshot: LoanSnapshot,
    val selectedContractId: StableId?,
    val selectedTrancheId: StableId?,
    val selectedTransactionId: StableId?,
    val selectedSimulationId: StableId?,
    val presentation: LoanPresentation,
    val draft: LoanDraft,
    val preview: List<LoanScheduleRevision> = emptyList(),
    val simulation: LoanPrepaymentSimulation? = null,
    val validationFields: Set<String> = emptySet(),
    val wizardStep: Int = 0,
    val creditAccounts: List<CreditAccountView> = emptyList(),
    val creditLoadFailureCode: String? = null,
) {
    public val contract: LoanContractView?
        get() = selectedContractId?.let { id -> snapshot.contracts.singleOrNull { it.id == id } }
    public val tranche: LoanTrancheView?
        get() = selectedTrancheId?.let { id -> contract?.tranches?.singleOrNull { it.id == id } }
            ?: contract?.tranches?.firstOrNull()
}

public sealed interface LoanLoadState {
    public data object Loading : LoanLoadState
    public data class Content(val state: LoanFeatureState) : LoanLoadState
    public data class Failure(val code: String) : LoanLoadState
}

public object LoanPolicy {
    @Suppress("LongParameterList")
    public fun create(
        snapshot: LoanSnapshot,
        screenId: String,
        contractId: StableId?,
        trancheId: StableId?,
        transactionId: StableId?,
        simulationId: StableId?,
    ): LoanFeatureState {
        val contract = contractId?.let { target -> snapshot.contracts.singleOrNull { it.id == target } }
            ?: snapshot.contracts.firstOrNull()
        val tranche = trancheId?.let { target -> contract?.tranches?.singleOrNull { it.id == target } }
            ?: contract?.tranches?.firstOrNull()
        val presentation = when (screenId) {
            "LIA-001", "LOA-001" -> when {
                snapshot.contracts.isEmpty() -> LoanPresentation.EMPTY
                snapshot.contracts.all { it.status == LoanStatus.PAID_OFF || it.status == LoanStatus.ARCHIVED } -> LoanPresentation.CLOSED
                else -> LoanPresentation.CONTENT
            }
            "REC-017" -> LoanPresentation.CONTENT
            "REC-018", "REC-019", "LOA-002", "LOA-004", "LOA-010" -> LoanPresentation.EDITING
            "LOA-003" -> if (trancheId == null) LoanPresentation.CREATE else LoanPresentation.EDIT
            "LOA-005", "LOA-006", "LOA-008", "LOA-009", "LOA-011" -> LoanPresentation.CONTENT
            "LOA-007" -> when {
                contract?.status == LoanStatus.PAID_OFF || contract?.status == LoanStatus.ARCHIVED -> LoanPresentation.CLOSED
                (contract?.tranches?.size ?: 0) > 1 -> LoanPresentation.MULTI_TRANCHE
                else -> LoanPresentation.ACTIVE
            }
            else -> LoanPresentation.CONTENT
        }
        return LoanFeatureState(
            snapshot,
            contract?.id ?: contractId,
            tranche?.id ?: trancheId,
            transactionId,
            simulationId,
            presentation,
            LoanDraft(
                name = contract?.name.orEmpty(),
                lender = contract?.lender.orEmpty(),
                principal = tranche?.remainingPrincipalMinor?.toString().orEmpty(),
                paymentCount = tranche?.schedule?.size?.toString() ?: "12",
                startDate = contract?.disbursementDate?.toString().orEmpty(),
                firstPaymentDate = tranche?.schedule?.firstOrNull()?.plannedDate?.toString().orEmpty(),
                endDate = tranche?.schedule?.lastOrNull()?.plannedDate?.toString().orEmpty(),
                annualRate = tranche?.ratePeriods?.firstOrNull()?.annualRate?.annualDecimal?.toPlainString().orEmpty(),
                repaymentMethod = tranche?.repaymentMethod ?: LoanRepaymentMethod.EQUAL_PAYMENT,
                strategy = tranche?.prepaymentStrategy ?: PrepaymentRecalculationStrategy.SHORTEN_TERM,
            ),
        )
    }

    public fun update(state: LoanFeatureState, field: LoanField, value: String): LoanFeatureState {
        val safe = value.take(if (field in dateFields) LOAN_DATE_INPUT_MAX_CHARS else LOAN_TEXT_INPUT_MAX_CHARS)
        val draft = when (field) {
            LoanField.NAME -> state.draft.copy(name = safe)
            LoanField.LENDER -> state.draft.copy(lender = safe)
            LoanField.PRINCIPAL -> state.draft.copy(principal = safe)
            LoanField.PAYMENT_COUNT -> state.draft.copy(paymentCount = safe)
            LoanField.START_DATE -> state.draft.copy(startDate = safe)
            LoanField.FIRST_PAYMENT_DATE -> state.draft.copy(firstPaymentDate = safe)
            LoanField.END_DATE -> state.draft.copy(endDate = safe)
            LoanField.ANNUAL_RATE -> state.draft.copy(annualRate = safe)
            LoanField.FEE_PER_PAYMENT -> state.draft.copy(feePerPayment = safe)
            LoanField.AMOUNT -> state.draft.copy(amount = safe)
            LoanField.PRINCIPAL_COMPONENT -> state.draft.copy(principalComponent = safe)
            LoanField.INTEREST_COMPONENT -> state.draft.copy(interestComponent = safe)
            LoanField.FEE_COMPONENT -> state.draft.copy(feeComponent = safe)
            LoanField.PENALTY_COMPONENT -> state.draft.copy(penaltyComponent = safe)
            LoanField.CONFIRM_PHRASE -> state.draft.copy(confirmPhrase = safe)
        }
        return state.copy(draft = draft, presentation = LoanPresentation.EDITING, validationFields = emptySet())
    }

    private val dateFields = setOf(LoanField.START_DATE, LoanField.FIRST_PAYMENT_DATE, LoanField.END_DATE)
}
