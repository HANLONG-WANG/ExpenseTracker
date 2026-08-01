package app.ledger.finance.domain

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.common.ValidationError
import app.ledger.core.common.ValidationReason
import app.ledger.core.money.CurrencyCode
import app.ledger.core.money.Money
import java.math.BigDecimal
import java.security.MessageDigest

sealed interface RecordLifecycle {
    data object Current : RecordLifecycle

    data object Revision : RecordLifecycle

    data object Fact : RecordLifecycle

    data object Projection : RecordLifecycle

    data object Cache : RecordLifecycle

    data object Operation : RecordLifecycle
}

interface LifecycleRecord<out L : RecordLifecycle> {
    val lifecycle: L
}

@JvmInline value class BookId(val value: StableId)

@JvmInline value class BookCommitId(val value: StableId)

@JvmInline value class DeviceInstanceId(val value: StableId)

@JvmInline value class EntityRevisionId(val value: StableId)

@JvmInline value class LedgerAccountId(val value: StableId)

@JvmInline value class UserAccountId(val value: StableId)

@JvmInline value class PaymentCardId(val value: StableId)

@JvmInline value class CategoryId(val value: StableId)

@JvmInline value class MerchantId(val value: StableId)

@JvmInline value class PlaceId(val value: StableId)

@JvmInline value class LocationRecordId(val value: StableId)

@JvmInline value class TransactionId(val value: StableId)

@JvmInline value class TransactionRevisionId(val value: StableId)

@JvmInline value class FxRateSnapshotId(val value: StableId)

@JvmInline value class JournalEntryId(val value: StableId)

@JvmInline value class PostingId(val value: StableId)

@JvmInline value class EconomicEffectId(val value: StableId)

@JvmInline value class BudgetEffectId(val value: StableId)

@JvmInline value class ProjectEffectId(val value: StableId)

@JvmInline value class GoalEffectId(val value: StableId)

@JvmInline value class StatementEffectId(val value: StableId)

@JvmInline value class LoanEffectId(val value: StableId)

@JvmInline value class SettlementEffectId(val value: StableId)

@JvmInline value class RefundAllocationId(val value: StableId)

@JvmInline value class BudgetTemplateId(val value: StableId)

@JvmInline value class BudgetTemplateRevisionId(val value: StableId)

@JvmInline value class BudgetMonthId(val value: StableId)

@JvmInline value class BudgetMonthRevisionId(val value: StableId)

@JvmInline value class BudgetAdjustmentId(val value: StableId)

@JvmInline value class ProjectId(val value: StableId)

@JvmInline value class GoalId(val value: StableId)

@JvmInline value class GoalMovementId(val value: StableId)

@JvmInline value class CreditStatementId(val value: StableId)

@JvmInline value class CreditStatementRevisionId(val value: StableId)

@JvmInline value class InstallmentPlanId(val value: StableId)

@JvmInline value class InstallmentPlanRevisionId(val value: StableId)

@JvmInline value class InstallmentScheduleRevisionId(val value: StableId)

@JvmInline value class InstallmentScheduleItemId(val value: StableId)

@JvmInline value class LoanContractId(val value: StableId)

@JvmInline value class LoanTrancheId(val value: StableId)

@JvmInline value class LoanTermsRevisionId(val value: StableId)

@JvmInline value class LoanScheduleRevisionId(val value: StableId)

@JvmInline value class LoanScheduleItemId(val value: StableId)

@JvmInline value class LoanSimulationId(val value: StableId)

@JvmInline value class ParticipantId(val value: StableId)

@JvmInline value class SettlementActivityId(val value: StableId)

@JvmInline value class SettlementPaymentRecordId(val value: StableId)

@JvmInline value class TransactionBlueprintId(val value: StableId)

@JvmInline value class TransactionBlueprintRevisionId(val value: StableId)

@JvmInline value class RecurrenceSeriesId(val value: StableId)

@JvmInline value class RecurrenceSeriesRevisionId(val value: StableId)

@JvmInline value class RecurrenceOccurrenceId(val value: StableId)

@JvmInline value class RecurrenceCandidateId(val value: StableId)

@JvmInline value class BlobId(val value: StableId)

@JvmInline value class AttachmentId(val value: StableId)

@JvmInline
value class RuleSetVersion private constructor(val value: Int) {
    companion object {
        fun of(value: Int): DomainResult<RuleSetVersion> = if (value > 0) {
            DomainResult.Success(RuleSetVersion(value))
        } else {
            DomainResult.Failure(ValidationError("ruleSetVersion", ValidationReason.MUST_BE_POSITIVE))
        }
    }
}

@JvmInline
value class LocalRevision private constructor(val value: Long) : Comparable<LocalRevision> {
    fun next(): DomainResult<LocalRevision> = try {
        DomainResult.Success(LocalRevision(Math.addExact(value, 1L)))
    } catch (_: ArithmeticException) {
        DomainResult.Failure(DomainViolation.NumericOverflow("localRevision"))
    }

    override fun compareTo(other: LocalRevision): Int = value.compareTo(other.value)

    companion object {
        fun of(value: Long): DomainResult<LocalRevision> = if (value >= 0L) {
            DomainResult.Success(LocalRevision(value))
        } else {
            DomainResult.Failure(ValidationError("localRevision", ValidationReason.OUT_OF_RANGE))
        }
    }
}

@JvmInline
value class RowVersion private constructor(val value: Long) {
    fun next(): DomainResult<RowVersion> = try {
        DomainResult.Success(RowVersion(Math.addExact(value, 1L)))
    } catch (_: ArithmeticException) {
        DomainResult.Failure(DomainViolation.NumericOverflow("rowVersion"))
    }

    companion object {
        fun of(value: Long): DomainResult<RowVersion> = if (value > 0L) {
            DomainResult.Success(RowVersion(value))
        } else {
            DomainResult.Failure(ValidationError("rowVersion", ValidationReason.MUST_BE_POSITIVE))
        }
    }
}

class Hash256 private constructor(bytes: ByteArray) {
    private val stored = bytes.copyOf()

    val bytes: ByteArray
        get() = stored.copyOf()

    override fun equals(other: Any?): Boolean = other is Hash256 && stored.contentEquals(other.stored)

    override fun hashCode(): Int = stored.contentHashCode()

    companion object {
        const val BYTE_COUNT: Int = 32

        /** Canonical SHA-256 used by immutable revisions, facts and commit roots. */
        fun sha256(bytes: ByteArray): Hash256 = Hash256(
            MessageDigest.getInstance("SHA-256").digest(bytes),
        )

        fun fromBytes(bytes: ByteArray): DomainResult<Hash256> = if (bytes.size == BYTE_COUNT) {
            DomainResult.Success(Hash256(bytes))
        } else {
            DomainResult.Failure(ValidationError("hash256", ValidationReason.INVALID_FORMAT))
        }
    }
}

@JvmInline value class ContentHash(val value: Hash256)

@JvmInline
value class PositiveMinor private constructor(val value: Long) {
    companion object {
        fun of(value: Long): DomainResult<PositiveMinor> = if (value > 0L) {
            DomainResult.Success(PositiveMinor(value))
        } else {
            DomainResult.Failure(ValidationError("amount", ValidationReason.MUST_BE_POSITIVE))
        }
    }
}

@ConsistentCopyVisibility
data class PositiveMoney private constructor(
    val minor: PositiveMinor,
    val currency: CurrencyCode,
) {
    val money: Money = Money(minor.value, currency)

    companion object {
        fun from(money: Money): DomainResult<PositiveMoney> = when (val minor = PositiveMinor.of(money.minor)) {
            is DomainResult.Success -> DomainResult.Success(PositiveMoney(minor.value, money.currency))
            is DomainResult.Failure -> minor
        }
    }
}

/** Currency-checked evidence required by commands; raw [Money] cannot enter an account posting. */
@ConsistentCopyVisibility
data class AccountAmount private constructor(
    val accountId: UserAccountId,
    val amount: PositiveMoney,
) {
    companion object {
        @Suppress("ReturnCount")
        fun create(account: AccountSnapshot, money: Money): DomainResult<AccountAmount> {
            if (account.currency != money.currency) {
                return DomainResult.Failure(DomainViolation.CurrencyMismatch(account.currency, money.currency))
            }
            if (account.status != EntityStatus.ACTIVE) {
                return DomainResult.Failure(DomainViolation.ArchivedReference("account"))
            }
            return when (val positive = PositiveMoney.from(money)) {
                is DomainResult.Success -> DomainResult.Success(AccountAmount(account.id, positive.value))
                is DomainResult.Failure -> positive
            }
        }
    }
}

@ConsistentCopyVisibility
data class Percentage private constructor(val decimal: BigDecimal) {
    companion object {
        fun of(decimal: BigDecimal): DomainResult<Percentage> = if (
            decimal.signum() >= 0 && decimal <= BigDecimal.ONE
        ) {
            DomainResult.Success(Percentage(decimal.stripTrailingZeros()))
        } else {
            DomainResult.Failure(ValidationError("percentage", ValidationReason.OUT_OF_RANGE))
        }
    }
}

@ConsistentCopyVisibility
data class InterestRate private constructor(val annualDecimal: BigDecimal) {
    companion object {
        fun of(annualDecimal: BigDecimal): DomainResult<InterestRate> = if (annualDecimal.signum() >= 0) {
            DomainResult.Success(InterestRate(annualDecimal.stripTrailingZeros()))
        } else {
            DomainResult.Failure(ValidationError("interestRate", ValidationReason.OUT_OF_RANGE))
        }
    }
}

@JvmInline value class IconKey(val value: String)

@JvmInline value class ColorArgb(val value: Int)

data class DisplayStyle(
    val icon: IconKey,
    val color: ColorArgb,
    val sortOrder: Int,
)

@ConsistentCopyVisibility
data class GeoPoint private constructor(
    val latitudeE7: Int,
    val longitudeE7: Int,
) {
    companion object {
        private const val LATITUDE_LIMIT_E7 = 900_000_000
        private const val LONGITUDE_LIMIT_E7 = 1_800_000_000

        fun create(latitudeE7: Int, longitudeE7: Int): DomainResult<GeoPoint> = if (
            latitudeE7 in -LATITUDE_LIMIT_E7..LATITUDE_LIMIT_E7 &&
            longitudeE7 in -LONGITUDE_LIMIT_E7..LONGITUDE_LIMIT_E7
        ) {
            DomainResult.Success(GeoPoint(latitudeE7, longitudeE7))
        } else {
            DomainResult.Failure(ValidationError("geoPoint", ValidationReason.OUT_OF_RANGE))
        }
    }
}

enum class EntityStatus {
    ACTIVE,
    ARCHIVED,
    DELETED_TOMBSTONE,
}
