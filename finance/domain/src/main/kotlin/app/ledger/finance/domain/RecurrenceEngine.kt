@file:Suppress("NestedBlockDepth")

package app.ledger.finance.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** A deterministic occurrence before persistence or financial generation. */
data class PlannedRecurrenceOccurrence(
    val localDate: LocalDate,
    val occurrenceInstant: Instant,
    val blueprintRevisionId: TransactionBlueprintRevisionId?,
)

/**
 * Pure recurrence calculator. It never reads a clock, device zone, network or location and is safe
 * to run from both the startup catch-up path and a delayed background executor.
 */
object RecurrenceEngine {
    fun next(
        revision: RecurrenceSeriesRevision,
        exceptions: List<RecurrenceException>,
        through: Instant,
        existingInstants: Set<Instant> = emptySet(),
        limit: Int = DEFAULT_LIMIT,
    ): List<PlannedRecurrenceOccurrence> {
        require(limit in 1..MAX_LIMIT)
        val throughDate = through.atZone(revision.zoneId).toLocalDate()
        if (throughDate < revision.startDate) return emptyList()
        val exceptionByDate = exceptions.associateBy(RecurrenceException::occurrenceLocalDate)
        val candidates = rawDates(revision, throughDate)
            .mapNotNull { raw -> resolveMissingAndWeekend(revision, raw) }
            .distinct()
            .sorted()
        val result = ArrayList<PlannedRecurrenceOccurrence>(limit)
        for (date in candidates) {
            val inSeriesRange = date >= revision.startDate && revision.endDate?.let { date <= it } != false
            if (inSeriesRange) {
                val exception = exceptionByDate[date]
                val instant = exception?.overrideInstant ?: date.atTime(revision.occurrenceTime).atZone(revision.zoneId).toInstant()
                if (exception?.action != RecurrenceExceptionAction.SKIP && instant <= through && instant !in existingInstants) {
                    result += PlannedRecurrenceOccurrence(
                        localDate = instant.atZone(revision.zoneId).toLocalDate(),
                        occurrenceInstant = instant,
                        blueprintRevisionId = exception?.overrideBlueprintRevisionId,
                    )
                    if (result.size == limit) break
                }
            }
        }
        return result
    }

    fun preview(
        revision: RecurrenceSeriesRevision,
        count: Int = PREVIEW_COUNT,
    ): List<PlannedRecurrenceOccurrence> {
        require(count in 1..PREVIEW_COUNT)
        val horizon = revision.startDate.plusYears(PREVIEW_HORIZON_YEARS)
            .atTime(revision.occurrenceTime).atZone(revision.zoneId).toInstant()
        return next(revision, emptyList(), horizon, limit = count)
    }

    private fun rawDates(revision: RecurrenceSeriesRevision, through: LocalDate): Sequence<LocalDate> = sequence {
        val start = revision.startDate
        val rule = revision.rule
        when (rule.frequency) {
            RecurrenceFrequency.DAILY, RecurrenceFrequency.CUSTOM_INTERVAL -> {
                var date = start
                while (date <= through) {
                    yield(date)
                    date = date.plusDays(rule.interval.toLong())
                }
            }
            RecurrenceFrequency.BUSINESS_DAYS -> {
                var date = start
                var accepted = 0
                while (date <= through) {
                    if (date.dayOfWeek !in WEEKEND) {
                        if (accepted % rule.interval == 0) yield(date)
                        accepted += 1
                    }
                    date = date.plusDays(1)
                }
            }
            RecurrenceFrequency.WEEKLY -> {
                val weekdays = rule.weekdays.ifEmpty { setOf(start.dayOfWeek) }.sortedBy(DayOfWeek::getValue)
                var date = start
                while (date <= through) {
                    val weeks = ChronoUnit.WEEKS.between(start.with(DayOfWeek.MONDAY), date.with(DayOfWeek.MONDAY))
                    if (weeks % rule.interval == 0L && date.dayOfWeek in weekdays) yield(date)
                    date = date.plusDays(1)
                }
            }
            RecurrenceFrequency.MONTHLY_DAY, RecurrenceFrequency.MONTH_INTERVAL -> {
                val targetDay = rule.monthDay ?: start.dayOfMonth
                var month = YearMonth.from(start)
                while (!month.atDay(1).isAfter(through)) {
                    yield(month.atDay(minOf(targetDay, month.lengthOfMonth())))
                    month = month.plusMonths(rule.interval.toLong())
                }
            }
            RecurrenceFrequency.MONTHLY_LAST_DAY -> {
                var month = YearMonth.from(start)
                while (!month.atDay(1).isAfter(through)) {
                    yield(month.atEndOfMonth())
                    month = month.plusMonths(rule.interval.toLong())
                }
            }
            RecurrenceFrequency.MONTHLY_NTH_WEEKDAY -> {
                val nth = requireNotNull(rule.nthWeek)
                val weekday = requireNotNull(rule.weekday)
                var month = YearMonth.from(start)
                while (!month.atDay(1).isAfter(through)) {
                    val date = month.atDay(1).with(TemporalAdjusters.dayOfWeekInMonth(nth, weekday))
                    if (YearMonth.from(date) == month) yield(date)
                    month = month.plusMonths(rule.interval.toLong())
                }
            }
            RecurrenceFrequency.YEARLY -> {
                var year = start.year
                while (year <= through.year) {
                    val month = YearMonth.of(year, start.month)
                    yield(month.atDay(minOf(rule.monthDay ?: start.dayOfMonth, month.lengthOfMonth())))
                    year = Math.addExact(year, rule.interval)
                }
            }
        }
    }.filter { it >= revision.startDate }.take(revision.maxOccurrences ?: Int.MAX_VALUE)

    private fun resolveMissingAndWeekend(revision: RecurrenceSeriesRevision, candidate: LocalDate): LocalDate? {
        val rule = revision.rule
        val targetDay = rule.monthDay
        if (targetDay != null && targetDay > YearMonth.from(candidate).lengthOfMonth() && rule.missingDayPolicy == MissingDayPolicy.SKIP) {
            return null
        }
        return when (rule.weekendAdjustment) {
            WeekendAdjustment.NONE -> candidate
            WeekendAdjustment.PREVIOUS_BUSINESS_DAY -> candidate.moveFromWeekend(-1)
            WeekendAdjustment.NEXT_BUSINESS_DAY -> candidate.moveFromWeekend(1)
        }
    }

    private fun LocalDate.moveFromWeekend(direction: Long): LocalDate {
        var adjusted = this
        while (adjusted.dayOfWeek in WEEKEND) adjusted = adjusted.plusDays(direction)
        return adjusted
    }

    private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    private const val DEFAULT_LIMIT = 1_000
    private const val MAX_LIMIT = 10_000
    private const val PREVIEW_COUNT = 10
    private const val PREVIEW_HORIZON_YEARS = 100L
}

/** Used to prove that a background-job payload contains only one opaque stable identifier. */
data class RecurrenceOperationToken(val operationId: RecurrenceOccurrenceId)
