package app.ledger.finance.application

import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.domain.CommandReceipt
import app.ledger.finance.domain.InstallmentFeeRateType
import app.ledger.finance.domain.InstallmentPlanRevision
import app.ledger.finance.domain.InstallmentPrepaymentPolicy
import app.ledger.finance.domain.InstallmentProgress
import app.ledger.finance.domain.InstallmentRefundPolicy
import app.ledger.finance.domain.InstallmentScheduleRevision
import app.ledger.finance.domain.InstallmentSettlementSimulation
import app.ledger.finance.domain.InstallmentStatus
import app.ledger.finance.domain.InterestRate
import app.ledger.finance.domain.LocalRevision
import app.ledger.finance.domain.ScheduleRevisionReason
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class InstallmentPurchaseView(
    val transactionId: StableId,
    val creditAccountId: StableId,
    val creditAccountName: String,
    val currency: CurrencyCode,
    val principalMinor: Long,
    val purchaseDate: LocalDate,
    val alreadyLinked: Boolean,
)

data class InstallmentPlanView(
    val id: StableId,
    val purchaseTransactionId: StableId,
    val creditAccountId: StableId,
    val creditAccountName: String,
    val currency: CurrencyCode,
    val originalPrincipalMinor: Long,
    val currentPrincipalMinor: Long,
    val termCount: Int,
    val status: InstallmentStatus,
    val currentRevision: InstallmentPlanRevision,
    val currentSchedule: InstallmentScheduleRevision,
    val progress: InstallmentProgress,
    val refundedPrincipalMinor: Long,
    val refundedFeeMinor: Long,
    val previousSchedule: InstallmentScheduleRevision? = null,
)

data class InstallmentSnapshot(
    val bookId: StableId,
    val baseCurrency: CurrencyCode,
    val localRevision: LocalRevision,
    val plans: List<InstallmentPlanView>,
    val purchases: List<InstallmentPurchaseView>,
    val paymentAccounts: List<CreditPaymentAccountView>,
)

data class InstallmentMutationIds(
    val bookId: StableId,
    val commandId: CommandId,
    val commitId: StableId,
    val deviceInstanceId: StableId,
    val planId: StableId,
    val planRevisionId: StableId,
    val scheduleRevisionId: StableId,
    val scheduleItemIds: List<StableId>,
) {
    init {
        val all = listOf(
            bookId,
            commandId.stableId,
            commitId,
            deviceInstanceId,
            planId,
            planRevisionId,
            scheduleRevisionId,
        ) + scheduleItemIds
        require(all.toSet().size == all.size)
    }
}

data class InstallmentSettlementIds(
    val mutation: InstallmentMutationIds,
    val transactionId: StableId,
    val transactionRevisionId: StableId,
    val factIds: List<StableId>,
    val fxRateSnapshotIds: List<StableId>,
) {
    init {
        require(factIds.isNotEmpty())
        val all = listOf(transactionId, transactionRevisionId) + factIds + fxRateSnapshotIds
        require(all.toSet().size == all.size)
        require(all.none { it in listOf(mutation.bookId, mutation.commandId.stableId, mutation.commitId, mutation.deviceInstanceId) })
    }
}

data class InstallmentTermsDraft(
    val feeRateType: InstallmentFeeRateType,
    val fixedFeePerTermMinor: Long?,
    val firstTermFeeMinor: Long?,
    val remainingPrincipalRate: InterestRate?,
    val effectiveAnnualRate: InterestRate?,
    val prepaymentPolicy: InstallmentPrepaymentPolicy,
    val prepaymentFeeMinor: Long?,
    val refundPolicy: InstallmentRefundPolicy,
    val roundingMode: RoundingMode,
)

data class SaveInstallmentPlanRequest(
    val ids: InstallmentMutationIds,
    val purchaseTransactionId: StableId,
    val creditAccountId: StableId,
    val currency: CurrencyCode,
    val originalPrincipalMinor: Long,
    val currentPrincipalMinor: Long,
    val termCount: Int,
    val expectedRevisionId: StableId?,
    val revisionNumber: Int,
    val scheduleRevisionNumber: Int,
    val firstStatementDate: LocalDate,
    val terms: InstallmentTermsDraft,
    val reason: ScheduleRevisionReason,
    val changedAt: Instant,
)

data class ApplyInstallmentSettlementRequest(
    val ids: InstallmentSettlementIds,
    val expectedRevisionId: StableId,
    val revisionNumber: Int,
    val scheduleRevisionNumber: Int,
    val settlementDate: LocalDate,
    val zoneId: ZoneId,
    val payment: SpecializedAccountAmountDraft,
    val credit: SpecializedAccountAmountDraft,
    val settlementFee: SpecializedAccountAmountDraft?,
    val changedAt: Instant,
)

data class ApplyInstallmentRefundRequest(
    val ids: InstallmentMutationIds,
    val expectedRevisionId: StableId,
    val revisionNumber: Int,
    val scheduleRevisionNumber: Int,
    val refundTransactionId: StableId,
    val refundRevisionId: StableId,
    val refundedPrincipalMinor: Long,
    val refundedFeeMinor: Long,
    val firstRemainingStatementDate: LocalDate,
    val changedAt: Instant,
)

interface InstallmentApplicationPort {
    suspend fun snapshot(bookId: StableId, asOfDate: LocalDate): DomainResult<InstallmentSnapshot>

    suspend fun preview(request: SaveInstallmentPlanRequest): DomainResult<InstallmentScheduleRevision>

    suspend fun save(request: SaveInstallmentPlanRequest): DomainResult<CommandReceipt>

    suspend fun simulateSettlement(
        bookId: StableId,
        planId: StableId,
        settlementDate: LocalDate,
    ): DomainResult<InstallmentSettlementSimulation>

    suspend fun applySettlement(request: ApplyInstallmentSettlementRequest): DomainResult<CommandReceipt>

    suspend fun applyRefund(request: ApplyInstallmentRefundRequest): DomainResult<CommandReceipt>
}
