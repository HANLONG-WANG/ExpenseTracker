package app.ledger.finance.domain

import app.ledger.core.common.CheckedArithmetic
import app.ledger.core.common.DomainResult
import app.ledger.core.money.CurrencyCode
import app.ledger.core.time.EffectiveTime
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class Participant(
    val id: ParticipantId,
    val name: String,
    val isSelf: Boolean,
    val status: EntityStatus,
    val lastCommitId: BookCommitId,
) : LifecycleRecord<RecordLifecycle.Current> {
    override val lifecycle: RecordLifecycle.Current = RecordLifecycle.Current
}

enum class SettlementActivityStatus {
    ACTIVE,
    SETTLED,
    REQUIRES_ADDITIONAL_SETTLEMENT,
    ARCHIVED,
}

data class SettlementActivity(
    val id: SettlementActivityId,
    val name: String,
    val description: String?,
    val settlementCurrency: CurrencyCode,
    val projectId: ProjectId?,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val status: SettlementActivityStatus,
    val participantIds: List<ParticipantId>,
    val lastCommitId: BookCommitId,
) : LifecycleRecord<RecordLifecycle.Current> {
    override val lifecycle: RecordLifecycle.Current = RecordLifecycle.Current

    init {
        require(endDate == null || startDate == null || endDate >= startDate)
        require(participantIds.size >= 2)
        require(participantIds.toSet().size == participantIds.size)
    }
}

data class SettlementActivityParticipant(
    val activityId: SettlementActivityId,
    val participantId: ParticipantId,
    val sortOrder: Int,
    val joinedAt: Instant,
    val leftAt: Instant?,
) : LifecycleRecord<RecordLifecycle.Current> {
    override val lifecycle: RecordLifecycle.Current = RecordLifecycle.Current
}

enum class SettlementAllocationMode {
    EQUAL,
    FIXED_AMOUNT,
    PERCENTAGE,
    WEIGHT,
    EXCLUSIONS,
    TAX_AND_SERVICE_FEE,
}

data class SettlementShare(
    val participantId: ParticipantId,
    val paidMinor: Long,
    val owedMinor: Long,
    val weight: BigDecimal?,
    val roundingAdjustmentMinor: Long,
) {
    init {
        require(paidMinor >= 0L && owedMinor >= 0L)
        require(weight == null || weight.signum() > 0)
    }
}

object SettlementSharePolicy {
    @Suppress("ComplexCondition", "ReturnCount")
    fun validate(totalMinor: Long, shares: List<SettlementShare>): DomainResult<Unit> {
        if (
            totalMinor <= 0L ||
            shares.isEmpty() ||
            shares.map { it.participantId }.toSet().size != shares.size ||
            shares.count { it.paidMinor > 0L } != 1
        ) {
            return DomainResult.Failure(DomainViolation.Invariant("INV-022"))
        }
        val paid = CheckedArithmetic.sum(shares.map { it.paidMinor })
        val owed = CheckedArithmetic.sum(shares.map { it.owedMinor })
        if (paid !is DomainResult.Success || owed !is DomainResult.Success) {
            return DomainResult.Failure(DomainViolation.NumericOverflow("settlementShare"))
        }
        return if (paid.value == totalMinor && owed.value == totalMinor) {
            DomainResult.Success(Unit)
        } else {
            DomainResult.Failure(DomainViolation.Invariant("INV-022"))
        }
    }

    fun validateEffects(effects: List<SettlementEffect>): DomainResult<Unit> {
        val total = CheckedArithmetic.sum(effects.map { it.signedDeltaMinor })
        return if (total is DomainResult.Success && total.value == 0L) {
            DomainResult.Success(Unit)
        } else {
            DomainResult.Failure(DomainViolation.Invariant("INV-022"))
        }
    }
}

data class SettlementPaymentRecord(
    val id: SettlementPaymentRecordId,
    val activityId: SettlementActivityId,
    val payerParticipantId: ParticipantId,
    val payeeParticipantId: ParticipantId,
    val amount: PositiveMoney,
    val occurredAt: EffectiveTime,
    val linkedTransactionId: TransactionId?,
    val selfParticipates: Boolean,
    val createdCommitId: BookCommitId,
    val reversalOfId: SettlementPaymentRecordId?,
) : LifecycleRecord<RecordLifecycle.Fact> {
    override val lifecycle: RecordLifecycle.Fact = RecordLifecycle.Fact

    init {
        require(payerParticipantId != payeeParticipantId)
        require(selfParticipates == (linkedTransactionId != null))
    }
}

data class SettlementPositionProjection(
    val activityId: SettlementActivityId,
    val participantId: ParticipantId,
    val paidMinor: Long,
    val owedMinor: Long,
    val settledPaidMinor: Long,
    val settledReceivedMinor: Long,
    val netPositionMinor: Long,
    val currency: CurrencyCode,
    val asOfLocalRevision: LocalRevision,
) : LifecycleRecord<RecordLifecycle.Projection> {
    override val lifecycle: RecordLifecycle.Projection = RecordLifecycle.Projection
}
