@file:Suppress("LongParameterList")

package app.ledger.finance.application

import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.core.money.FxEvidence
import app.ledger.finance.domain.CommandReceipt
import app.ledger.finance.domain.RefundAccrualPolicy
import app.ledger.finance.domain.RefundBudgetPolicy
import app.ledger.finance.domain.RefundGoalPolicy
import app.ledger.finance.domain.RefundProjectPolicy
import app.ledger.finance.domain.SettlementShare
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.TransactionLifecycleState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

public data class RefundSearchQuery(
    val text: String = "",
    val partiallyRefundedOnly: Boolean = false,
    val accountId: StableId? = null,
) {
    init {
        require(text.length <= MAX_REFUND_SEARCH_LENGTH)
    }

    private companion object {
        const val MAX_REFUND_SEARCH_LENGTH: Int = 160
    }
}

public data class RefundNamedReference(val id: StableId, val name: String)

/** A bounded original-purchase view. Notes and private fields never enter routes or semantics. */
public data class RefundableTransactionView(
    val transactionId: StableId,
    val revisionId: StableId,
    val lifecycleState: TransactionLifecycleState,
    val originalMinor: Long,
    val originalCurrency: CurrencyCode,
    val originalBaseMinor: Long,
    val baseCurrency: CurrencyCode,
    val refundedMinor: Long,
    val remainingMinor: Long,
    val excessRefundedMinor: Long,
    val occurredAt: Instant,
    val localDate: LocalDate,
    val accountId: StableId?,
    val cardId: StableId?,
    val categoryId: StableId,
    val categoryName: String,
    val statisticalNature: StatisticalNature,
    val merchantId: StableId?,
    val merchantName: String?,
    val projectId: StableId?,
    val projectName: String?,
    val goalId: StableId?,
    val goalName: String?,
    val settlementActivityId: StableId?,
    val settlementShares: List<SettlementShare>,
    val installmentPlanId: StableId?,
) {
    init {
        require(originalMinor > 0L && originalBaseMinor > 0L)
        require(refundedMinor >= 0L && remainingMinor >= 0L && excessRefundedMinor >= 0L)
        require(remainingMinor == maxOf(originalMinor - refundedMinor, 0L))
        require(excessRefundedMinor == maxOf(refundedMinor - originalMinor, 0L))
    }
}

public data class RefundSnapshot(
    val references: ReferenceDataSnapshot,
    val originals: List<RefundableTransactionView>,
    val projects: List<RefundNamedReference>,
    val goals: List<RefundNamedReference>,
)

public data class RefundWriteIds(
    val bookId: StableId,
    val commandId: CommandId,
    val transactionId: StableId,
    val revisionId: StableId,
    val commitId: StableId,
    val deviceInstanceId: StableId,
    val factIds: List<StableId>,
    val fxRateSnapshotIds: List<StableId>,
) {
    init {
        require(factIds.isNotEmpty())
        val all = listOf(bookId, commandId.stableId, transactionId, revisionId, commitId, deviceInstanceId) + factIds + fxRateSnapshotIds
        require(all.toSet().size == all.size)
    }
}

public data class RefundAmountDraft(
    val inputMinor: Long,
    val inputCurrency: CurrencyCode,
    val receivingAccountId: StableId,
    val receivingAccountMinor: Long,
    val baseMinor: Long,
    val inputToAccountEvidence: FxEvidence?,
    val accountToBaseEvidence: FxEvidence?,
) {
    init {
        require(inputMinor > 0L && receivingAccountMinor > 0L && baseMinor > 0L)
    }
}

public data class RefundAllocationDraft(
    val originalTransactionId: StableId,
    val originalRevisionId: StableId,
    val originalCurrencyMinor: Long,
    val baseMinor: Long,
) {
    init {
        require(originalCurrencyMinor > 0L && baseMinor > 0L)
    }
}

public data class RefundWriteRequest(
    val ids: RefundWriteIds,
    val allocations: List<RefundAllocationDraft>,
    val amount: RefundAmountDraft,
    val receivingCardId: StableId?,
    val independent: Boolean,
    val categoryId: StableId?,
    val merchantId: StableId?,
    val projectId: StableId?,
    val goalId: StableId?,
    val settlementActivityId: StableId?,
    val settlementShares: List<SettlementShare>,
    val occurredAt: Instant,
    val zoneId: ZoneId,
    val localDate: LocalDate,
    val accrualDate: LocalDate,
    val budgetTargetMonth: java.time.YearMonth?,
    val budgetPolicy: RefundBudgetPolicy,
    val projectPolicy: RefundProjectPolicy,
    val goalPolicy: RefundGoalPolicy,
    val accrualPolicy: RefundAccrualPolicy,
    val allowExcessOverride: Boolean,
    val excessRiskConfirmed: Boolean,
    val amountExpression: String,
    val note: String?,
    val attachmentIds: List<StableId>,
    val createdAt: Instant,
) {
    init {
        require(independent == allocations.isEmpty())
        require(!allowExcessOverride || excessRiskConfirmed)
        require((settlementActivityId == null) == settlementShares.isEmpty())
        require(amountExpression.isNotBlank())
        require(note == null || note.isNotBlank())
        require(attachmentIds.toSet().size == attachmentIds.size)
        require((budgetPolicy == RefundBudgetPolicy.DO_NOT_RESTORE) == (budgetTargetMonth == null))
    }
}

/** Sole P16 application boundary; every submit is delegated to FinancialMutationCoordinator. */
public interface RefundApplicationPort {
    public suspend fun snapshot(bookId: StableId, query: RefundSearchQuery = RefundSearchQuery()): DomainResult<RefundSnapshot>
    public suspend fun submit(request: RefundWriteRequest): DomainResult<CommandReceipt>
}
