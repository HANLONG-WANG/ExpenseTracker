package app.ledger.finance.domain

import app.ledger.core.common.CheckedArithmetic
import app.ledger.core.common.DomainResult
import app.ledger.core.money.CurrencyCode
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

sealed interface StatementDateRule {
    data class DayOfMonth(val day: Int, val missingDayPolicy: MissingDayPolicy) : StatementDateRule {
        init {
            require(day in 1..MAX_DAY_OF_MONTH)
        }
    }

    data object LastDayOfMonth : StatementDateRule
}

sealed interface DueDateRule {
    data class FixedDay(val day: Int, val missingDayPolicy: MissingDayPolicy) : DueDateRule {
        init {
            require(day in 1..MAX_DAY_OF_MONTH)
        }
    }

    data class DaysAfterStatement(val days: Int) : DueDateRule {
        init {
            require(days > 0)
        }
    }
}

enum class MissingDayPolicy {
    MOVE_TO_MONTH_END,
    SKIP,
}

enum class WeekendAdjustment {
    NONE,
    PREVIOUS_BUSINESS_DAY,
    NEXT_BUSINESS_DAY,
}

data class CreditAccountProfile(
    val accountId: UserAccountId,
    val statementRule: StatementDateRule,
    val paymentDueRule: DueDateRule,
    val statementZoneId: ZoneId,
    val standardLimitMinor: Long?,
    val temporaryLimitMinor: Long?,
    val temporaryLimitExpiresOn: LocalDate?,
    val defaultPaymentAccountId: UserAccountId?,
    val autoPaymentMode: AutoGenerationMode,
    val weekendAdjustment: WeekendAdjustment,
    val lastCommitId: BookCommitId,
) : LifecycleRecord<RecordLifecycle.Current> {
    override val lifecycle: RecordLifecycle.Current = RecordLifecycle.Current

    init {
        require(standardLimitMinor == null || standardLimitMinor >= 0L)
        require(temporaryLimitMinor == null || temporaryLimitMinor >= 0L)
        require((temporaryLimitMinor == null) == (temporaryLimitExpiresOn == null))
    }
}

data class CreditLimitPeriod(
    val creditAccountId: UserAccountId,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate?,
    val limitMinor: Long,
    val createdCommitId: BookCommitId,
) : LifecycleRecord<RecordLifecycle.Revision> {
    override val lifecycle: RecordLifecycle.Revision = RecordLifecycle.Revision

    init {
        require(limitMinor >= 0L)
        require(effectiveTo == null || effectiveTo >= effectiveFrom)
    }
}

enum class CreditStatementStatus {
    OPEN,
    UNPAID,
    PARTIALLY_PAID,
    PAID,
    OVERDUE,
    SEALED,
}

data class CreditStatement(
    val id: CreditStatementId,
    val creditAccountId: UserAccountId,
    val cycleStart: LocalDate,
    val cycleEnd: LocalDate,
    val dueDate: LocalDate,
    val currentRevisionId: CreditStatementRevisionId,
    val status: CreditStatementStatus,
) : LifecycleRecord<RecordLifecycle.Current> {
    override val lifecycle: RecordLifecycle.Current = RecordLifecycle.Current

    init {
        require(cycleEnd >= cycleStart)
        require(dueDate >= cycleEnd)
    }
}

data class CreditStatementRevision(
    val id: CreditStatementRevisionId,
    val statementId: CreditStatementId,
    val revisionNumber: Int,
    val estimatedAmountMinor: Long,
    val officialAmountMinor: Long?,
    val officialRecordedAt: Instant?,
    val differenceMinor: Long?,
    val statementDate: LocalDate,
    val dueDate: LocalDate,
    val sealed: Boolean,
    val createdCommitId: BookCommitId,
) : LifecycleRecord<RecordLifecycle.Revision> {
    override val lifecycle: RecordLifecycle.Revision = RecordLifecycle.Revision

    init {
        require(revisionNumber > 0)
        require(estimatedAmountMinor >= 0L)
        require(officialAmountMinor == null || officialAmountMinor >= 0L)
        require((officialAmountMinor == null) == (officialRecordedAt == null))
        require((officialAmountMinor == null) == (differenceMinor == null))
    }
}

data class CreditStatementProjection(
    val statementId: CreditStatementId,
    val estimatedAmountMinor: Long,
    val officialAmountMinor: Long?,
    val paidAmountMinor: Long,
    val remainingAmountMinor: Long,
    val status: CreditStatementStatus,
    val asOfLocalRevision: LocalRevision,
) : LifecycleRecord<RecordLifecycle.Projection> {
    override val lifecycle: RecordLifecycle.Projection = RecordLifecycle.Projection
}

enum class InstallmentStatus {
    ACTIVE,
    SETTLED,
    CANCELLED,
    ARCHIVED,
}

enum class InstallmentFeeRateType {
    NONE,
    FIXED_PER_TERM,
    FIRST_TERM_FIXED,
    REMAINING_PRINCIPAL_RATE,
    EFFECTIVE_ANNUAL_RATE,
}

enum class InstallmentPrepaymentPolicy {
    ALLOWED_WITHOUT_FEE,
    ALLOWED_WITH_FEE,
    NOT_ALLOWED,
}

enum class InstallmentRefundPolicy {
    REDUCE_REMAINING_PRINCIPAL,
    REDUCE_FINAL_TERMS,
    REBUILD_SCHEDULE,
}

data class InstallmentPlan(
    val id: InstallmentPlanId,
    val purchaseTransactionId: TransactionId,
    val creditAccountId: UserAccountId,
    val originalPrincipalMinor: Long,
    val currency: CurrencyCode,
    val termCount: Int,
    val currentRevisionId: InstallmentPlanRevisionId,
    val status: InstallmentStatus,
) : LifecycleRecord<RecordLifecycle.Current> {
    override val lifecycle: RecordLifecycle.Current = RecordLifecycle.Current

    init {
        require(originalPrincipalMinor > 0L)
        require(termCount > 0)
    }
}

data class InstallmentPlanRevision(
    val id: InstallmentPlanRevisionId,
    val planId: InstallmentPlanId,
    val revisionNumber: Int,
    val feeRateType: InstallmentFeeRateType,
    val fixedFeePerTermMinor: Long?,
    val firstTermFeeMinor: Long?,
    val remainingPrincipalRate: InterestRate?,
    val effectiveAnnualRate: InterestRate?,
    val prepaymentPolicy: InstallmentPrepaymentPolicy,
    val prepaymentFeeMinor: Long?,
    val refundPolicy: InstallmentRefundPolicy,
    val roundingMode: RoundingMode,
    val createdCommitId: BookCommitId,
) : LifecycleRecord<RecordLifecycle.Revision> {
    override val lifecycle: RecordLifecycle.Revision = RecordLifecycle.Revision

    init {
        require(revisionNumber > 0)
        require(fixedFeePerTermMinor == null || fixedFeePerTermMinor >= 0L)
        require(firstTermFeeMinor == null || firstTermFeeMinor >= 0L)
        require(prepaymentFeeMinor == null || prepaymentFeeMinor >= 0L)
        require(
            when (feeRateType) {
                InstallmentFeeRateType.NONE ->
                    fixedFeePerTermMinor == null && firstTermFeeMinor == null &&
                        remainingPrincipalRate == null && effectiveAnnualRate == null
                InstallmentFeeRateType.FIXED_PER_TERM ->
                    fixedFeePerTermMinor != null && firstTermFeeMinor == null &&
                        remainingPrincipalRate == null && effectiveAnnualRate == null
                InstallmentFeeRateType.FIRST_TERM_FIXED ->
                    fixedFeePerTermMinor == null && firstTermFeeMinor != null &&
                        remainingPrincipalRate == null && effectiveAnnualRate == null
                InstallmentFeeRateType.REMAINING_PRINCIPAL_RATE ->
                    fixedFeePerTermMinor == null && firstTermFeeMinor == null &&
                        remainingPrincipalRate != null && effectiveAnnualRate == null
                InstallmentFeeRateType.EFFECTIVE_ANNUAL_RATE ->
                    fixedFeePerTermMinor == null && firstTermFeeMinor == null &&
                        remainingPrincipalRate == null && effectiveAnnualRate != null
            },
        )
        require(
            (prepaymentPolicy == InstallmentPrepaymentPolicy.ALLOWED_WITH_FEE) == (prepaymentFeeMinor != null),
        )
    }
}

enum class ScheduleRevisionReason {
    INITIAL,
    RATE_CHANGE,
    PREPAYMENT,
    ACTUAL_VARIANCE,
    REFUND,
    MANUAL_REBUILD,
}

data class InstallmentScheduleRevision(
    val id: InstallmentScheduleRevisionId,
    val planId: InstallmentPlanId,
    val revisionNumber: Int,
    val reason: ScheduleRevisionReason,
    val generatedAt: Instant,
    val createdCommitId: BookCommitId,
    val items: List<InstallmentScheduleItem>,
) : LifecycleRecord<RecordLifecycle.Revision> {
    override val lifecycle: RecordLifecycle.Revision = RecordLifecycle.Revision
}

data class InstallmentScheduleItem(
    val id: InstallmentScheduleItemId,
    val installmentNumber: Int,
    val statementDate: LocalDate,
    val principalMinor: Long,
    val interestMinor: Long,
    val feeMinor: Long,
    val remainingPrincipalMinor: Long,
) {
    init {
        require(installmentNumber > 0)
        require(principalMinor >= 0L && interestMinor >= 0L && feeMinor >= 0L && remainingPrincipalMinor >= 0L)
    }
}

object InstallmentSchedulePolicy {
    fun validate(principalMinor: Long, items: List<InstallmentScheduleItem>): DomainResult<Unit> = try {
        if (!hasValidShape(principalMinor, items) || !hasValidRemainingChain(items)) {
            DomainResult.Failure(DomainViolation.Invariant("INV-026"))
        } else {
            val total = CheckedArithmetic.sum(items.map { it.principalMinor })
            if (total is DomainResult.Success && total.value == principalMinor) {
                DomainResult.Success(Unit)
            } else {
                DomainResult.Failure(DomainViolation.Invariant("INV-026"))
            }
        }
    } catch (_: ArithmeticException) {
        DomainResult.Failure(DomainViolation.NumericOverflow("installment.scheduleValidation"))
    }

    private fun hasValidShape(principalMinor: Long, items: List<InstallmentScheduleItem>): Boolean {
        val numbers = items.map { it.installmentNumber }
        return principalMinor >= 0L &&
            (principalMinor == 0L || items.isNotEmpty()) &&
            numbers.toSet().size == items.size &&
            numbers == (1..items.size).toList() &&
            items.zipWithNext().none { (first, second) -> second.statementDate <= first.statementDate }
    }

    private fun hasValidRemainingChain(items: List<InstallmentScheduleItem>): Boolean = items.lastOrNull()?.remainingPrincipalMinor in setOf(null, 0L) &&
        items.zipWithNext().all { (first, second) ->
            first.remainingPrincipalMinor == Math.addExact(second.principalMinor, second.remainingPrincipalMinor)
        }
}

data class InstallmentRefundAllocation(
    val refundTransactionId: TransactionId,
    val refundRevisionId: TransactionRevisionId,
    val planId: InstallmentPlanId,
    val principalMinor: Long,
    val feeMinor: Long,
    val reversalOfId: app.ledger.core.common.StableId?,
) : LifecycleRecord<RecordLifecycle.Fact> {
    override val lifecycle: RecordLifecycle.Fact = RecordLifecycle.Fact

    init {
        require(principalMinor >= 0L && feeMinor >= 0L)
        require(principalMinor > 0L || feeMinor > 0L)
    }
}

enum class LoanStatus {
    ACTIVE,
    PAID_OFF,
    DEFAULTED,
    ARCHIVED,
}

data class LoanContract(
    val id: LoanContractId,
    val displayAccountId: UserAccountId,
    val name: String,
    val lender: String?,
    val currency: CurrencyCode,
    val disbursementDate: LocalDate,
    val status: LoanStatus,
    val lastCommitId: BookCommitId,
    val trancheIds: List<LoanTrancheId>,
) : LifecycleRecord<RecordLifecycle.Current> {
    override val lifecycle: RecordLifecycle.Current = RecordLifecycle.Current

    init {
        require(trancheIds.isNotEmpty())
        require(trancheIds.toSet().size == trancheIds.size)
    }
}

data class LoanTranche(
    val id: LoanTrancheId,
    val contractId: LoanContractId,
    val ledgerAccountId: LedgerAccountId,
    val name: String,
    val originalPrincipalMinor: Long,
    val status: LoanStatus,
) : LifecycleRecord<RecordLifecycle.Current> {
    override val lifecycle: RecordLifecycle.Current = RecordLifecycle.Current

    init {
        require(originalPrincipalMinor > 0L)
    }
}

enum class LoanRateType {
    FIXED,
    FLOATING,
}

enum class LoanRepaymentMethod {
    EQUAL_PAYMENT,
    EQUAL_PRINCIPAL,
    INTEREST_ONLY_THEN_PRINCIPAL,
    BULLET,
    CUSTOM,
}

enum class PaymentFrequency {
    WEEKLY,
    BIWEEKLY,
    MONTHLY,
    QUARTERLY,
    YEARLY,
    CUSTOM,
}

enum class PrepaymentRecalculationStrategy {
    SHORTEN_TERM,
    REDUCE_PAYMENT,
}

enum class LoanPrepaymentPolicy {
    ALLOWED,
    ALLOWED_WITH_PENALTY,
    NOT_ALLOWED,
}

data class LoanTermsRevision(
    val id: LoanTermsRevisionId,
    val trancheId: LoanTrancheId,
    val revisionNumber: Int,
    val repaymentMethod: LoanRepaymentMethod,
    val rateType: LoanRateType,
    val paymentFrequency: PaymentFrequency,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val roundingMode: RoundingMode,
    val prepaymentPolicy: LoanPrepaymentPolicy,
    val prepaymentStrategy: PrepaymentRecalculationStrategy,
    val penaltyRate: InterestRate?,
    val createdCommitId: BookCommitId,
    val ratePeriods: List<LoanRatePeriod>,
) : LifecycleRecord<RecordLifecycle.Revision> {
    override val lifecycle: RecordLifecycle.Revision = RecordLifecycle.Revision

    init {
        require(revisionNumber > 0)
        require(endDate >= startDate)
        require(ratePeriods.isNotEmpty())
        require(LoanRatePeriodPolicy.validate(ratePeriods) is DomainResult.Success)
        require((prepaymentPolicy == LoanPrepaymentPolicy.ALLOWED_WITH_PENALTY) == (penaltyRate != null))
        require(
            when (rateType) {
                LoanRateType.FIXED -> ratePeriods.all { it.benchmark == null && it.margin == null }
                LoanRateType.FLOATING -> ratePeriods.all { !it.benchmark.isNullOrBlank() && it.margin != null }
            },
        )
    }
}

data class LoanRatePeriod(
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate?,
    val annualRate: InterestRate,
    val benchmark: String?,
    val margin: InterestRate?,
) {
    init {
        require(effectiveTo == null || effectiveTo >= effectiveFrom)
    }
}

object LoanRatePeriodPolicy {
    @Suppress("ReturnCount")
    fun validate(periods: List<LoanRatePeriod>): DomainResult<Unit> {
        val sorted = periods.sortedBy { it.effectiveFrom }
        for (index in 1 until sorted.size) {
            val previousEnd = sorted[index - 1].effectiveTo
                ?: return DomainResult.Failure(DomainViolation.InvalidField("loanRatePeriod.openEndedOverlap"))
            if (sorted[index].effectiveFrom <= previousEnd) {
                return DomainResult.Failure(DomainViolation.InvalidField("loanRatePeriod.overlap"))
            }
        }
        return DomainResult.Success(Unit)
    }
}

data class LoanScheduleRevision(
    val id: LoanScheduleRevisionId,
    val trancheId: LoanTrancheId,
    val revisionNumber: Int,
    val termsRevisionId: LoanTermsRevisionId,
    val reason: ScheduleRevisionReason,
    val generatedAt: Instant,
    val createdCommitId: BookCommitId,
    val items: List<LoanScheduleItem>,
) : LifecycleRecord<RecordLifecycle.Revision> {
    override val lifecycle: RecordLifecycle.Revision = RecordLifecycle.Revision
}

data class LoanScheduleItem(
    val id: LoanScheduleItemId,
    val installmentNumber: Int,
    val plannedDate: LocalDate,
    val principalMinor: Long,
    val interestMinor: Long,
    val feeMinor: Long,
    val remainingPrincipalMinor: Long,
    val finalInstallment: Boolean,
) {
    init {
        require(installmentNumber > 0)
        require(principalMinor >= 0L && interestMinor >= 0L && feeMinor >= 0L && remainingPrincipalMinor >= 0L)
    }
}

object LoanSchedulePolicy {
    @Suppress("ComplexCondition")
    fun validate(remainingPrincipalMinor: Long, items: List<LoanScheduleItem>): DomainResult<Unit> {
        if (remainingPrincipalMinor < 0L || items.isEmpty()) {
            return DomainResult.Failure(DomainViolation.Invariant("INV-025"))
        }
        val total = CheckedArithmetic.sum(items.map { it.principalMinor })
        return if (
            total is DomainResult.Success &&
            total.value == remainingPrincipalMinor &&
            items.count { it.finalInstallment } == 1 &&
            items.last().finalInstallment
        ) {
            DomainResult.Success(Unit)
        } else {
            DomainResult.Failure(DomainViolation.Invariant("INV-025"))
        }
    }
}

enum class LoanPaymentComponent {
    PRINCIPAL,
    INTEREST,
    FEE,
    PENALTY,
}

data class LoanActualAllocation(
    val paymentTransactionId: TransactionId,
    val paymentRevisionId: TransactionRevisionId,
    val trancheId: LoanTrancheId,
    val scheduleItemId: LoanScheduleItemId?,
    val component: LoanPaymentComponent,
    val amount: PositiveMoney,
    val baseAmount: PositiveMoney,
    val reversalOfId: app.ledger.core.common.StableId?,
) : LifecycleRecord<RecordLifecycle.Fact> {
    override val lifecycle: RecordLifecycle.Fact = RecordLifecycle.Fact
}

sealed interface LoanSimulationScenario {
    data class PartialPrepayment(
        val amountMinor: Long,
        val strategy: PrepaymentRecalculationStrategy,
        val onDate: LocalDate,
    ) : LoanSimulationScenario

    data class FullSettlement(val onDate: LocalDate) : LoanSimulationScenario

    data class RateChange(
        val effectiveFrom: LocalDate,
        val annualRate: InterestRate,
    ) : LoanSimulationScenario
}

data class LoanSimulation(
    val id: LoanSimulationId,
    val contractId: LoanContractId,
    val baseScheduleRevisionId: LoanScheduleRevisionId,
    val scenario: LoanSimulationScenario,
    val createdAt: Instant,
) : LifecycleRecord<RecordLifecycle.Cache> {
    override val lifecycle: RecordLifecycle.Cache = RecordLifecycle.Cache
}

data class LoanSimulationItem(
    val simulationId: LoanSimulationId,
    val installmentNumber: Int,
    val plannedDate: LocalDate,
    val principalMinor: Long,
    val interestMinor: Long,
    val feeMinor: Long,
    val remainingPrincipalMinor: Long,
    val basedOnLocalRevision: LocalRevision,
) : LifecycleRecord<RecordLifecycle.Projection> {
    override val lifecycle: RecordLifecycle.Projection = RecordLifecycle.Projection
}

data class LoanProgressProjection(
    val contractId: LoanContractId,
    val principalRemainingMinor: Long,
    val principalPaidMinor: Long,
    val interestPaidMinor: Long,
    val feePaidMinor: Long,
    val nextPaymentDate: LocalDate?,
    val nextPaymentMinor: Long?,
    val asOfLocalRevision: LocalRevision,
) : LifecycleRecord<RecordLifecycle.Projection> {
    override val lifecycle: RecordLifecycle.Projection = RecordLifecycle.Projection
}

data class BusinessCalendar(
    val weekendDays: Set<DayOfWeek>,
    val holidays: Set<LocalDate>,
)

private const val MAX_DAY_OF_MONTH = 31
