package app.ledger.finance.domain

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.core.money.Money
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

data class CreditCycle(
    val cycleStart: LocalDate,
    val cycleEnd: LocalDate,
    val dueDate: LocalDate,
) {
    init {
        require(cycleEnd >= cycleStart)
        require(dueDate >= cycleEnd)
    }
}

/** Calendar-only policy. It never reads the system clock or the device time zone. */
object CreditCalendarPolicy {
    fun cycleContaining(date: LocalDate, profile: CreditAccountProfile): DomainResult<CreditCycle> = try {
        val end = nextStatementDate(date, profile.statementRule)
        val previous = previousStatementDate(end.minusDays(1), profile.statementRule)
        val due = dueDate(end, profile.paymentDueRule, profile.weekendAdjustment)
        DomainResult.Success(CreditCycle(previous.plusDays(1), end, due))
    } catch (_: ArithmeticException) {
        DomainResult.Failure(DomainViolation.NumericOverflow("creditCalendar"))
    }

    private fun nextStatementDate(date: LocalDate, rule: StatementDateRule): LocalDate {
        var month = YearMonth.from(date)
        repeat(MAX_MONTH_SEARCH) {
            val resolved = resolve(month, rule)
            if (resolved != null && resolved >= date) return resolved
            month = month.plusMonths(1)
        }
        throw ArithmeticException("statement rule has no resolvable date")
    }

    private fun previousStatementDate(date: LocalDate, rule: StatementDateRule): LocalDate {
        var month = YearMonth.from(date)
        repeat(MAX_MONTH_SEARCH) {
            val resolved = resolve(month, rule)
            if (resolved != null && resolved <= date) return resolved
            month = month.minusMonths(1)
        }
        throw ArithmeticException("statement rule has no resolvable date")
    }

    private fun resolve(month: YearMonth, rule: StatementDateRule): LocalDate? = when (rule) {
        StatementDateRule.LastDayOfMonth -> month.atEndOfMonth()
        is StatementDateRule.DayOfMonth -> when {
            rule.day <= month.lengthOfMonth() -> month.atDay(rule.day)
            rule.missingDayPolicy == MissingDayPolicy.MOVE_TO_MONTH_END -> month.atEndOfMonth()
            else -> null
        }
    }

    private fun dueDate(
        statementDate: LocalDate,
        rule: DueDateRule,
        weekendAdjustment: WeekendAdjustment,
    ): LocalDate {
        val raw = when (rule) {
            is DueDateRule.DaysAfterStatement -> statementDate.plusDays(rule.days.toLong())
            is DueDateRule.FixedDay -> fixedDueDate(statementDate, rule)
        }
        return adjustWeekend(raw, weekendAdjustment)
    }

    private fun fixedDueDate(statementDate: LocalDate, rule: DueDateRule.FixedDay): LocalDate {
        var month = YearMonth.from(statementDate)
        repeat(MAX_MONTH_SEARCH) {
            val day = when {
                rule.day <= month.lengthOfMonth() -> month.atDay(rule.day)
                rule.missingDayPolicy == MissingDayPolicy.MOVE_TO_MONTH_END -> month.atEndOfMonth()
                else -> null
            }
            if (day != null && day > statementDate) return day
            month = month.plusMonths(1)
        }
        throw ArithmeticException("due rule has no resolvable date")
    }

    private fun adjustWeekend(date: LocalDate, policy: WeekendAdjustment): LocalDate = when (policy) {
        WeekendAdjustment.NONE -> date
        WeekendAdjustment.PREVIOUS_BUSINESS_DAY -> when (date.dayOfWeek) {
            DayOfWeek.SATURDAY -> date.minusDays(1)
            DayOfWeek.SUNDAY -> date.minusDays(2)
            else -> date
        }
        WeekendAdjustment.NEXT_BUSINESS_DAY -> when (date.dayOfWeek) {
            DayOfWeek.SATURDAY -> date.plusDays(2)
            DayOfWeek.SUNDAY -> date.plusDays(1)
            else -> date
        }
    }
}

object CreditStatementPolicy {
    fun difference(estimatedAmountMinor: Long, officialAmountMinor: Long?): Long? = officialAmountMinor?.let { Math.subtractExact(it, estimatedAmountMinor) }

    @Suppress("LongParameterList")
    fun status(
        estimatedAmountMinor: Long,
        officialAmountMinor: Long?,
        paidAmountMinor: Long,
        dueDate: LocalDate,
        sealed: Boolean,
        asOfDate: LocalDate,
    ): CreditStatementStatus {
        val due = officialAmountMinor ?: estimatedAmountMinor
        val remaining = Math.subtractExact(due, paidAmountMinor)
        return when {
            remaining <= 0L -> CreditStatementStatus.PAID
            dueDate < asOfDate -> CreditStatementStatus.OVERDUE
            paidAmountMinor > 0L -> CreditStatementStatus.PARTIALLY_PAID
            sealed -> CreditStatementStatus.SEALED
            officialAmountMinor != null -> CreditStatementStatus.UNPAID
            else -> CreditStatementStatus.OPEN
        }
    }
}

data class PayableCreditStatement(
    val statementId: CreditStatementId,
    val dueDate: LocalDate,
    val remainingMinor: Long,
) {
    init {
        require(remainingMinor >= 0L)
    }
}

sealed interface CreditPaymentSelection {
    data object EarliestUnpaid : CreditPaymentSelection
    data class Specific(val statementId: CreditStatementId) : CreditPaymentSelection
    data object UnallocatedAdvance : CreditPaymentSelection
}

object CreditPaymentAllocationPolicy {
    fun allocate(
        amount: PositiveMoney,
        statements: List<PayableCreditStatement>,
        selection: CreditPaymentSelection,
        activeDebtMinor: Long,
    ): DomainResult<List<CreditPaymentAllocation>> = try {
        require(activeDebtMinor >= 0L)
        val outstanding = Math.addExact(0L, statements.sumExact(PayableCreditStatement::remainingMinor))
        if (amount.minor.value > activeDebtMinor ||
            selection != CreditPaymentSelection.UnallocatedAdvance && amount.minor.value > outstanding
        ) {
            return DomainResult.Failure(DomainViolation.InvalidField("creditPayment.activeOverpayment"))
        }
        when (selection) {
            CreditPaymentSelection.UnallocatedAdvance -> DomainResult.Success(
                listOf(CreditPaymentAllocation(null, amount)),
            )
            is CreditPaymentSelection.Specific -> {
                val statement = statements.singleOrNull { it.statementId == selection.statementId }
                    ?: return DomainResult.Failure(DomainViolation.InvalidField("creditPayment.statementId"))
                if (amount.minor.value > statement.remainingMinor) {
                    DomainResult.Failure(DomainViolation.InvalidField("creditPayment.statementOverpayment"))
                } else {
                    DomainResult.Success(listOf(CreditPaymentAllocation(statement.statementId, amount)))
                }
            }
            CreditPaymentSelection.EarliestUnpaid -> {
                var remaining = amount.minor.value
                val result = buildList {
                    statements.sortedWith(compareBy(PayableCreditStatement::dueDate, { it.statementId.value.toString() }))
                        .forEach { statement ->
                            val assigned = minOf(remaining, statement.remainingMinor)
                            if (assigned > 0L) {
                                add(
                                    CreditPaymentAllocation(
                                        statement.statementId,
                                        PositiveMoney.from(Money(assigned, amount.currency)).valueOrThrow(),
                                    ),
                                )
                                remaining = Math.subtractExact(remaining, assigned)
                            }
                        }
                }
                if (remaining == 0L) {
                    DomainResult.Success(result)
                } else {
                    DomainResult.Failure(DomainViolation.InvalidField("creditPayment.allocation"))
                }
            }
        }
    } catch (_: ArithmeticException) {
        DomainResult.Failure(DomainViolation.NumericOverflow("creditPayment.allocation"))
    }
}

enum class AutoPaymentIneligibility {
    OFFICIAL_STATEMENT_REQUIRED,
    NO_REMAINING_AMOUNT,
    DEFAULT_PAYMENT_ACCOUNT_REQUIRED,
    CREDIT_ACCOUNT_ARCHIVED,
    DUPLICATE_OCCURRENCE,
}

data class AutoPaymentEligibility(
    val mode: AutoGenerationMode,
    val reasons: Set<AutoPaymentIneligibility>,
) {
    val eligible: Boolean get() = reasons.isEmpty()
}

object CreditAutoPaymentPolicy {
    fun evaluate(
        officialAmountMinor: Long?,
        remainingAmountMinor: Long,
        hasActiveDefaultPaymentAccount: Boolean,
        creditAccountActive: Boolean,
        occurrenceAlreadyRecorded: Boolean,
    ): AutoPaymentEligibility {
        val reasons = buildSet {
            if (officialAmountMinor == null) add(AutoPaymentIneligibility.OFFICIAL_STATEMENT_REQUIRED)
            if (remainingAmountMinor <= 0L) add(AutoPaymentIneligibility.NO_REMAINING_AMOUNT)
            if (!hasActiveDefaultPaymentAccount) add(AutoPaymentIneligibility.DEFAULT_PAYMENT_ACCOUNT_REQUIRED)
            if (!creditAccountActive) add(AutoPaymentIneligibility.CREDIT_ACCOUNT_ARCHIVED)
            if (occurrenceAlreadyRecorded) add(AutoPaymentIneligibility.DUPLICATE_OCCURRENCE)
        }
        return AutoPaymentEligibility(
            if (reasons.isEmpty()) AutoGenerationMode.FORMAL_TRANSACTION else AutoGenerationMode.CONFIRMATION_CANDIDATE,
            reasons,
        )
    }
}

data class CreditProfileMutation(
    val profile: CreditAccountProfile,
    val expectedLastCommitId: BookCommitId?,
    val limitPeriod: CreditLimitPeriod?,
) {
    init {
        require(limitPeriod == null || limitPeriod.creditAccountId == profile.accountId)
    }
}

data class CreditStatementMutation(
    val statement: CreditStatement,
    val revision: CreditStatementRevision,
    val expectedRevisionId: CreditStatementRevisionId?,
) {
    init {
        require(statement.currentRevisionId == revision.id)
        require(revision.statementId == statement.id)
        require(statement.dueDate == revision.dueDate)
    }
}

data class CreditAccountPosition(
    val accountId: UserAccountId,
    val currency: CurrencyCode,
    val signedLiabilityMinor: Long,
    val debtMinor: Long,
    val positiveBalanceMinor: Long,
    val effectiveLimitMinor: Long?,
    val availableLimitMinor: Long?,
) {
    init {
        require(debtMinor == maxOf(0L, signedLiabilityMinor))
        require(positiveBalanceMinor == maxOf(0L, -signedLiabilityMinor))
    }
}

private fun <T> Iterable<T>.sumExact(selector: (T) -> Long): Long = fold(0L) { sum, item ->
    Math.addExact(sum, selector(item))
}

private fun <T> DomainResult<T>.valueOrThrow(): T = when (this) {
    is DomainResult.Success -> value
    is DomainResult.Failure -> throw IllegalArgumentException(error.code)
}

private const val MAX_MONTH_SEARCH = 480
