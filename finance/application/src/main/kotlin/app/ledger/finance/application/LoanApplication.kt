package app.ledger.finance.application

import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.core.money.FxEvidence
import app.ledger.finance.domain.CommandReceipt
import app.ledger.finance.domain.InterestRate
import app.ledger.finance.domain.LoanPaymentComponent
import app.ledger.finance.domain.LoanPrepaymentPolicy
import app.ledger.finance.domain.LoanPrepaymentSimulation
import app.ledger.finance.domain.LoanRatePeriod
import app.ledger.finance.domain.LoanRateType
import app.ledger.finance.domain.LoanRepaymentMethod
import app.ledger.finance.domain.LoanScheduleRevision
import app.ledger.finance.domain.LoanSimulationScenario
import app.ledger.finance.domain.LoanStatus
import app.ledger.finance.domain.LocalRevision
import app.ledger.finance.domain.PaymentFrequency
import app.ledger.finance.domain.PrepaymentRecalculationStrategy
import app.ledger.finance.domain.ScheduleRevisionReason
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val FIXED_LOAN_MUTATION_ID_COUNT = 3

data class LoanScheduleItemView(
    val installmentNumber: Int,
    val plannedDate: LocalDate,
    val principalMinor: Long,
    val interestMinor: Long,
    val feeMinor: Long,
    val remainingPrincipalMinor: Long,
    val actualPrincipalMinor: Long,
    val actualInterestMinor: Long,
    val actualFeeMinor: Long,
    val actualPenaltyMinor: Long,
)

data class LoanScheduleRevisionView(
    val id: StableId,
    val revisionNumber: Int,
    val items: List<LoanScheduleItemView>,
)

data class LoanPaymentAllocationView(
    val trancheName: String,
    val installmentNumber: Int?,
    val component: LoanPaymentComponent,
    val amountMinor: Long,
)

data class LoanPaymentDetailView(
    val transactionId: StableId,
    val contractName: String,
    val paymentAccountName: String?,
    val currency: CurrencyCode,
    val localDate: LocalDate,
    val principalMinor: Long,
    val interestMinor: Long,
    val feeMinor: Long,
    val penaltyMinor: Long,
    val allocations: List<LoanPaymentAllocationView>,
)

data class LoanTrancheView(
    val id: StableId,
    val ledgerAccountId: StableId,
    val name: String,
    val originalPrincipalMinor: Long,
    val remainingPrincipalMinor: Long,
    val paidPrincipalMinor: Long,
    val paidInterestMinor: Long,
    val paidFeeMinor: Long,
    val paidPenaltyMinor: Long,
    val status: LoanStatus,
    val currentTermsRevisionId: StableId,
    val termsRevisionNumber: Int,
    val repaymentMethod: LoanRepaymentMethod,
    val rateType: LoanRateType,
    val paymentFrequency: PaymentFrequency,
    val prepaymentPolicy: LoanPrepaymentPolicy,
    val prepaymentStrategy: PrepaymentRecalculationStrategy,
    val penaltyRate: InterestRate?,
    val roundingMode: RoundingMode,
    val ratePeriods: List<LoanRatePeriod>,
    val currentScheduleRevisionId: StableId,
    val scheduleRevisionNumber: Int,
    val scheduleHistoryCount: Int,
    val schedule: List<LoanScheduleItemView>,
    val scheduleRevisions: List<LoanScheduleRevisionView> = emptyList(),
)

data class LoanContractView(
    val id: StableId,
    val displayAccountId: StableId,
    val name: String,
    val lender: String?,
    val currency: CurrencyCode,
    val disbursementDate: LocalDate,
    val status: LoanStatus,
    val lastCommitId: StableId,
    val tranches: List<LoanTrancheView>,
) {
    val originalPrincipalMinor: Long
        get() = tranches.fold(0L) { total, tranche -> Math.addExact(total, tranche.originalPrincipalMinor) }
    val remainingPrincipalMinor: Long
        get() = tranches.fold(0L) { total, tranche -> Math.addExact(total, tranche.remainingPrincipalMinor) }
}

data class LoanAccountOption(
    val id: StableId,
    val name: String,
    val currency: CurrencyCode,
    val type: String,
    val ledgerAccountId: StableId,
    val active: Boolean,
)

data class LoanSnapshot(
    val bookId: StableId,
    val baseCurrency: CurrencyCode,
    val localRevision: LocalRevision,
    val contracts: List<LoanContractView>,
    val loanAccounts: List<LoanAccountOption>,
    val paymentAccounts: List<LoanAccountOption>,
)

data class LoanTrancheMutationIds(
    val trancheId: StableId,
    val termsRevisionId: StableId,
    val scheduleRevisionId: StableId,
    val scheduleItemIds: List<StableId>,
) {
    init {
        require(
            (listOf(trancheId, termsRevisionId, scheduleRevisionId) + scheduleItemIds).toSet().size ==
                scheduleItemIds.size + FIXED_LOAN_MUTATION_ID_COUNT,
        )
    }
}

data class LoanMutationIds(
    val bookId: StableId,
    val commandId: CommandId,
    val commitId: StableId,
    val deviceInstanceId: StableId,
    val contractId: StableId,
    val tranches: List<LoanTrancheMutationIds>,
) {
    init {
        require(tranches.isNotEmpty())
        val ids = listOf(bookId, commandId.stableId, commitId, deviceInstanceId, contractId) + tranches.flatMap {
            listOf(it.trancheId, it.termsRevisionId, it.scheduleRevisionId) + it.scheduleItemIds
        }
        require(ids.toSet().size == ids.size)
    }
}

data class LoanTransactionIds(
    val transactionId: StableId,
    val revisionId: StableId,
    val factIds: List<StableId>,
    val fxRateSnapshotIds: List<StableId>,
) {
    init {
        require(factIds.isNotEmpty())
        val ids = listOf(transactionId, revisionId) + factIds + fxRateSnapshotIds
        require(ids.toSet().size == ids.size)
    }
}

data class LoanTermsDraft(
    val repaymentMethod: LoanRepaymentMethod,
    val rateType: LoanRateType,
    val paymentFrequency: PaymentFrequency,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val paymentCount: Int,
    val firstPaymentDate: LocalDate,
    val roundingMode: RoundingMode,
    val prepaymentPolicy: LoanPrepaymentPolicy,
    val prepaymentStrategy: PrepaymentRecalculationStrategy,
    val penaltyRate: InterestRate?,
    val ratePeriods: List<LoanRatePeriod>,
    val feePerPaymentMinor: Long = 0L,
)

data class LoanTrancheDraft(
    val ids: LoanTrancheMutationIds,
    val ledgerAccountId: StableId,
    val name: String,
    val originalPrincipalMinor: Long,
    val currentPrincipalMinor: Long,
    val status: LoanStatus,
    val expectedTermsRevisionId: StableId?,
    val termsRevisionNumber: Int,
    val scheduleRevisionNumber: Int,
    val scheduleReason: ScheduleRevisionReason,
    val terms: LoanTermsDraft,
)

data class SaveLoanContractRequest(
    val ids: LoanMutationIds,
    val displayAccountId: StableId,
    val name: String,
    val lender: String?,
    val currency: CurrencyCode,
    val disbursementDate: LocalDate,
    val status: LoanStatus,
    val expectedLastCommitId: StableId?,
    val tranches: List<LoanTrancheDraft>,
    val changedAt: Instant,
) {
    init {
        require(tranches.map { it.ids } == ids.tranches)
        require(tranches.isNotEmpty())
    }
}

data class LoanComponentAllocationDraft(
    val trancheId: StableId,
    val installmentNumber: Int?,
    val component: LoanPaymentComponent,
    val accountMinor: Long,
    val baseMinor: Long,
)

data class LoanComponentAmountDraft(
    val accountMinor: Long,
    val baseMinor: Long,
    val accountToBaseEvidence: FxEvidence?,
) {
    init {
        require(accountMinor > 0L && baseMinor > 0L)
    }
}

data class LoanPaymentAmountsDraft(
    val principal: LoanComponentAmountDraft?,
    val interest: LoanComponentAmountDraft?,
    val fee: LoanComponentAmountDraft?,
    val penalty: LoanComponentAmountDraft?,
)

data class LoanTransactionContext(
    val occurredAt: Instant,
    val zoneId: ZoneId,
    val localDate: LocalDate,
    val note: String?,
    val amountExpression: String?,
)

data class RecordLoanDisbursementRequest(
    val mutationIds: LoanMutationIds,
    val transactionIds: LoanTransactionIds,
    val context: LoanTransactionContext,
    val receiving: SpecializedAccountAmountDraft,
    val liability: LoanComponentAmountDraft,
    val allocations: List<LoanComponentAllocationDraft>,
)

data class RecordLoanPaymentRequest(
    val mutation: SaveLoanContractRequest,
    val transactionIds: LoanTransactionIds,
    val context: LoanTransactionContext,
    val payment: SpecializedAccountAmountDraft,
    val components: LoanPaymentAmountsDraft,
    val allocations: List<LoanComponentAllocationDraft>,
    val sourceOccurrenceId: StableId? = null,
)

data class LoanSimulationRequest(
    val simulationId: StableId,
    val bookId: StableId,
    val contractId: StableId,
    val trancheId: StableId,
    val scenario: LoanSimulationScenario,
    val replacementIds: LoanTrancheMutationIds,
    val replacementTerms: LoanTermsDraft,
    val changedAt: Instant,
)

data class ApplyLoanSimulationRequest(
    val simulation: LoanSimulationRequest,
    val mutation: SaveLoanContractRequest,
    val transactionIds: LoanTransactionIds,
    val context: LoanTransactionContext,
    val payment: SpecializedAccountAmountDraft,
    val principal: LoanComponentAmountDraft,
    val penalty: LoanComponentAmountDraft?,
)

interface LoanApplicationPort {
    suspend fun snapshot(bookId: StableId): DomainResult<LoanSnapshot>

    suspend fun paymentDetail(bookId: StableId, transactionId: StableId): DomainResult<LoanPaymentDetailView?>

    suspend fun preview(request: SaveLoanContractRequest): DomainResult<List<LoanScheduleRevision>>

    suspend fun saveContract(request: SaveLoanContractRequest): DomainResult<CommandReceipt>

    suspend fun recordDisbursement(request: RecordLoanDisbursementRequest): DomainResult<CommandReceipt>

    suspend fun recordPayment(request: RecordLoanPaymentRequest): DomainResult<CommandReceipt>

    suspend fun simulate(request: LoanSimulationRequest): DomainResult<LoanPrepaymentSimulation>

    suspend fun applySimulation(request: ApplyLoanSimulationRequest): DomainResult<CommandReceipt>
}
