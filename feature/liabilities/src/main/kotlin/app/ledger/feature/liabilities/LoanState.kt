package app.ledger.feature.liabilities

import app.ledger.core.common.StableId
import app.ledger.finance.application.CreditAccountView
import app.ledger.finance.application.InstallmentPlanView
import app.ledger.finance.application.LoanContractView
import app.ledger.finance.application.LoanPaymentDetailView
import app.ledger.finance.application.LoanSnapshot
import app.ledger.finance.application.LoanTrancheView
import app.ledger.finance.domain.LoanPrepaymentPolicy
import app.ledger.finance.domain.LoanPrepaymentSimulation
import app.ledger.finance.domain.LoanRatePeriod
import app.ledger.finance.domain.LoanRateType
import app.ledger.finance.domain.LoanRepaymentMethod
import app.ledger.finance.domain.LoanScheduleRevision
import app.ledger.finance.domain.LoanStatus
import app.ledger.finance.domain.PaymentFrequency
import app.ledger.finance.domain.PrepaymentRecalculationStrategy
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId

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
    TRANCHE_NAME,
    PENALTY_RATE,
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
    val trancheName: String = "",
    val penaltyRate: String = "",
    val repaymentMethod: LoanRepaymentMethod = LoanRepaymentMethod.EQUAL_PAYMENT,
    val strategy: PrepaymentRecalculationStrategy = PrepaymentRecalculationStrategy.SHORTEN_TERM,
    val rateType: LoanRateType = LoanRateType.FIXED,
    val paymentFrequency: PaymentFrequency = PaymentFrequency.MONTHLY,
    val prepaymentPolicy: LoanPrepaymentPolicy = LoanPrepaymentPolicy.ALLOWED,
    val roundingMode: RoundingMode = RoundingMode.HALF_EVEN,
)

public data class LoanWizardTrancheDraft(
    val name: String,
    val principal: String,
    val paymentCount: String,
    val firstPaymentDate: String,
    val endDate: String,
    val annualRate: String,
    val feePerPayment: String,
    val repaymentMethod: LoanRepaymentMethod,
    val rateType: LoanRateType,
    val paymentFrequency: PaymentFrequency,
    val prepaymentPolicy: LoanPrepaymentPolicy,
    val prepaymentStrategy: PrepaymentRecalculationStrategy,
    val penaltyRate: String,
    val roundingMode: RoundingMode,
    val ratePeriods: List<LoanRatePeriod>,
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
    val installmentPlans: List<InstallmentPlanView> = emptyList(),
    val installmentLoadFailureCode: String? = null,
    val wizardTranches: List<LoanWizardTrancheDraft> = emptyList(),
    val selectedWizardTrancheIndex: Int = 0,
    val ratePeriods: List<LoanRatePeriod> = emptyList(),
    val editingRatePeriodIndex: Int? = null,
    val paymentDetail: LoanPaymentDetailView? = null,
    val creatingTranche: Boolean = false,
    val selectedPaymentAccountId: StableId? = null,
    val selectedScheduleInstallmentNumber: Int? = null,
    val operationOccurredAt: Instant? = null,
    val operationZoneId: ZoneId? = null,
) {
    public val contract: LoanContractView?
        get() = selectedContractId?.let { id -> snapshot.contracts.singleOrNull { it.id == id } }
    public val tranche: LoanTrancheView?
        get() {
            if (creatingTranche) return null
            return selectedTrancheId?.let { id -> contract?.tranches?.singleOrNull { it.id == id } } ?: contract?.tranches?.firstOrNull()
        }
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
        val contract = if (screenId == "LOA-002" && contractId == null) {
            null
        } else {
            contractId?.let { target -> snapshot.contracts.singleOrNull { it.id == target } }
                ?: snapshot.contracts.firstOrNull()
        }
        val tranche = if (screenId == "LOA-003" && trancheId == null) {
            null
        } else {
            trancheId?.let { target -> contract?.tranches?.singleOrNull { it.id == target } } ?: contract?.tranches?.firstOrNull()
        }
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
        val currency = contract?.currency ?: snapshot.loanAccounts.firstOrNull()?.currency
        val wizardTranches = contract?.tranches?.map { it.toWizardDraft(requireNotNull(currency)) }
            ?: listOf(blankWizardDraft())
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
                principal = tranche?.remainingPrincipalMinor?.let { minor -> currency?.let { CreditPolicy.minorText(minor, it) } }.orEmpty(),
                paymentCount = tranche?.schedule?.size?.toString() ?: "12",
                startDate = contract?.disbursementDate?.toString().orEmpty(),
                firstPaymentDate = tranche?.schedule?.firstOrNull()?.plannedDate?.toString().orEmpty(),
                endDate = tranche?.schedule?.lastOrNull()?.plannedDate?.toString().orEmpty(),
                annualRate = tranche?.ratePeriods?.firstOrNull()?.annualRate?.annualDecimal?.toPlainString().orEmpty(),
                feePerPayment = tranche?.schedule?.firstOrNull()?.feeMinor?.let { minor -> currency?.let { CreditPolicy.minorText(minor, it) } }.orEmpty(),
                trancheName = tranche?.name.orEmpty(),
                penaltyRate = tranche?.penaltyRate?.annualDecimal?.toPlainString().orEmpty(),
                repaymentMethod = tranche?.repaymentMethod ?: LoanRepaymentMethod.EQUAL_PAYMENT,
                strategy = tranche?.prepaymentStrategy ?: PrepaymentRecalculationStrategy.SHORTEN_TERM,
                rateType = tranche?.rateType ?: LoanRateType.FIXED,
                paymentFrequency = tranche?.paymentFrequency ?: PaymentFrequency.MONTHLY,
                prepaymentPolicy = tranche?.prepaymentPolicy ?: LoanPrepaymentPolicy.ALLOWED,
                roundingMode = tranche?.roundingMode ?: RoundingMode.HALF_EVEN,
            ),
            wizardTranches = wizardTranches,
            ratePeriods = tranche?.ratePeriods.orEmpty(),
            creatingTranche = screenId == "LOA-003" && trancheId == null,
            selectedPaymentAccountId = snapshot.paymentAccounts.firstOrNull {
                it.active && (currency == null || it.currency == currency)
            }?.id,
            selectedScheduleInstallmentNumber = tranche?.schedule?.firstOrNull {
                it.actualPrincipalMinor + it.actualInterestMinor + it.actualFeeMinor + it.actualPenaltyMinor <
                    it.principalMinor + it.interestMinor + it.feeMinor
            }?.installmentNumber,
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
            LoanField.TRANCHE_NAME -> state.draft.copy(trancheName = safe)
            LoanField.PENALTY_RATE -> state.draft.copy(penaltyRate = safe)
        }
        return state.copy(
            draft = draft,
            presentation = if (state.creatingTranche) LoanPresentation.CREATE else LoanPresentation.EDITING,
            validationFields = emptySet(),
        )
    }

    private val dateFields = setOf(LoanField.START_DATE, LoanField.FIRST_PAYMENT_DATE, LoanField.END_DATE)

    public fun syncWizardTranche(state: LoanFeatureState): LoanFeatureState {
        if (state.wizardTranches.isEmpty() || state.selectedWizardTrancheIndex !in state.wizardTranches.indices) return state
        val draft = state.draft
        val updated = state.wizardTranches.toMutableList()
        updated[state.selectedWizardTrancheIndex] = LoanWizardTrancheDraft(
            draft.trancheName.ifBlank { draft.name }, draft.principal, draft.paymentCount, draft.firstPaymentDate,
            draft.endDate, draft.annualRate, draft.feePerPayment, draft.repaymentMethod, draft.rateType,
            draft.paymentFrequency, draft.prepaymentPolicy, draft.strategy, draft.penaltyRate, draft.roundingMode, state.ratePeriods,
        )
        return state.copy(wizardTranches = updated)
    }

    public fun canSave(state: LoanFeatureState, screenId: String): Boolean = when (screenId) {
        "REC-018" -> state.selectedPaymentAccountId != null && state.operationOccurredAt != null
        "REC-019" -> state.selectedPaymentAccountId != null && state.selectedScheduleInstallmentNumber != null && state.operationOccurredAt != null
        "LOA-002" -> state.wizardStep == 5 && state.preview.isNotEmpty() && state.wizardTranches.isNotEmpty()
        "LOA-003" -> state.draft.trancheName.isNotBlank() && state.draft.principal.isNotBlank()
        "LOA-004" -> state.draft.paymentCount.toIntOrNull()?.let { it > 0 } == true &&
            state.draft.firstPaymentDate.toLocalDateOrNull() != null && state.draft.annualRate.isNotBlank()
        "LOA-005" -> state.ratePeriods.isNotEmpty() && state.ratePeriods.zipWithNext().none { (first, second) ->
            val effectiveTo = first.effectiveTo
            effectiveTo == null || effectiveTo >= second.effectiveFrom
        }
        else -> true
    }

    private fun String.toLocalDateOrNull(): java.time.LocalDate? = runCatching { java.time.LocalDate.parse(this) }.getOrNull()

    public fun selectWizardTranche(state: LoanFeatureState, index: Int): LoanFeatureState {
        val synced = syncWizardTranche(state)
        val tranche = synced.wizardTranches.getOrNull(index) ?: return synced
        return synced.copy(
            selectedWizardTrancheIndex = index,
            ratePeriods = tranche.ratePeriods,
            draft = synced.draft.copy(
                trancheName = tranche.name, principal = tranche.principal, paymentCount = tranche.paymentCount,
                firstPaymentDate = tranche.firstPaymentDate, endDate = tranche.endDate, annualRate = tranche.annualRate,
                feePerPayment = tranche.feePerPayment, repaymentMethod = tranche.repaymentMethod, rateType = tranche.rateType,
                paymentFrequency = tranche.paymentFrequency, prepaymentPolicy = tranche.prepaymentPolicy,
                strategy = tranche.prepaymentStrategy, penaltyRate = tranche.penaltyRate, roundingMode = tranche.roundingMode,
            ),
        )
    }

    private fun LoanTrancheView.toWizardDraft(currency: app.ledger.core.money.CurrencyCode): LoanWizardTrancheDraft = LoanWizardTrancheDraft(
        name, CreditPolicy.minorText(originalPrincipalMinor, currency), schedule.size.coerceAtLeast(1).toString(),
        schedule.firstOrNull()?.plannedDate?.toString().orEmpty(), schedule.lastOrNull()?.plannedDate?.toString().orEmpty(),
        ratePeriods.firstOrNull()?.annualRate?.annualDecimal?.toPlainString().orEmpty(),
        schedule.firstOrNull()?.feeMinor?.let { CreditPolicy.minorText(it, currency) }.orEmpty(), repaymentMethod, rateType,
        paymentFrequency, prepaymentPolicy, prepaymentStrategy, penaltyRate?.annualDecimal?.toPlainString().orEmpty(), roundingMode, ratePeriods,
    )

    private fun blankWizardDraft(): LoanWizardTrancheDraft = LoanWizardTrancheDraft(
        "", "", "12", "", "", "", "", LoanRepaymentMethod.EQUAL_PAYMENT, LoanRateType.FIXED,
        PaymentFrequency.MONTHLY, LoanPrepaymentPolicy.ALLOWED, PrepaymentRecalculationStrategy.SHORTEN_TERM,
        "", RoundingMode.HALF_EVEN, emptyList(),
    )
}
