package app.ledger.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class BudgetMonthPeriod(val month: YearMonth) {
    val firstDate: LocalDate = month.atDay(1)
    val lastDate: LocalDate = month.atEndOfMonth()

    fun contains(date: LocalDate): Boolean = !date.isBefore(firstDate) && !date.isAfter(lastDate)

    fun previous(): BudgetMonthPeriod = BudgetMonthPeriod(month.minusMonths(1))

    fun next(): BudgetMonthPeriod = BudgetMonthPeriod(month.plusMonths(1))

    fun remainingDaysInclusive(date: LocalDate): Int {
        require(contains(date)) { "Date must be inside the budget month" }
        return Math.toIntExact(ChronoUnit.DAYS.between(date, lastDate) + 1L)
    }

    fun instantBounds(zoneId: ZoneId): InstantRange {
        val start = firstDate.atStartOfDay(zoneId).toInstant()
        val endExclusive = month.plusMonths(1).atDay(1).atStartOfDay(zoneId).toInstant()
        return InstantRange(start, endExclusive)
    }

    companion object {
        fun from(effectiveTime: EffectiveTime): BudgetMonthPeriod = BudgetMonthPeriod(YearMonth.from(effectiveTime.localDate))
    }
}

data class InstantRange(
    val startInclusive: Instant,
    val endExclusive: Instant,
) {
    init {
        require(startInclusive < endExclusive) { "Instant range must be non-empty" }
    }

    fun contains(value: Instant): Boolean = value >= startInclusive && value < endExclusive
}

data class StatementDateRule(val dayOfMonth: Int) {
    init {
        require(dayOfMonth in 1..MAXIMUM_STATEMENT_DAY) { "Statement day is out of range" }
    }

    fun dateIn(month: YearMonth): LocalDate = month.atDay(dayOfMonth.coerceAtMost(month.lengthOfMonth()))

    private companion object {
        const val MAXIMUM_STATEMENT_DAY = 31
    }
}

data class StatementCycle(
    val cycleStart: LocalDate,
    val cycleEnd: LocalDate,
    val statementZoneId: ZoneId,
) {
    init {
        require(!cycleStart.isAfter(cycleEnd)) { "Statement cycle start must not follow its end" }
    }

    val key: YearMonthKey = YearMonthKey.from(YearMonth.from(cycleEnd))

    fun contains(date: LocalDate): Boolean = !date.isBefore(cycleStart) && !date.isAfter(cycleEnd)
}

class StatementCycleCalculator(private val rule: StatementDateRule) {
    fun cycleFor(instant: Instant, statementZoneId: ZoneId): StatementCycle = cycleForDate(instant.atZone(statementZoneId).toLocalDate(), statementZoneId)

    fun cycleFor(effectiveTime: EffectiveTime, statementZoneId: ZoneId): StatementCycle = cycleFor(effectiveTime.instant, statementZoneId)

    fun cycleForDate(localDate: LocalDate, statementZoneId: ZoneId): StatementCycle {
        val localMonth = YearMonth.from(localDate)
        val currentMonthEnd = rule.dateIn(localMonth)
        val cycleEnd = if (localDate <= currentMonthEnd) currentMonthEnd else rule.dateIn(localMonth.plusMonths(1))
        val previousEnd = rule.dateIn(YearMonth.from(cycleEnd).minusMonths(1))
        return StatementCycle(previousEnd.plusDays(1), cycleEnd, statementZoneId)
    }

    fun previous(cycle: StatementCycle): StatementCycle {
        val previousEnd = cycle.cycleStart.minusDays(1)
        val start = rule.dateIn(YearMonth.from(previousEnd).minusMonths(1)).plusDays(1)
        return StatementCycle(start, previousEnd, cycle.statementZoneId)
    }

    fun next(cycle: StatementCycle): StatementCycle {
        val nextStart = cycle.cycleEnd.plusDays(1)
        val nextEnd = rule.dateIn(YearMonth.from(cycle.cycleEnd).plusMonths(1))
        return StatementCycle(nextStart, nextEnd, cycle.statementZoneId)
    }
}
