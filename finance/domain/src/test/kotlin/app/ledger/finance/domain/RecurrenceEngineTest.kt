@file:Suppress("MaxLineLength")

package app.ledger.finance.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random

class RecurrenceEngineTest {
    @Test
    fun `all closed frequency variants produce deterministic future dates`() {
        val expected = mapOf(
            RecurrenceFrequency.DAILY to listOf("2026-01-01", "2026-01-02", "2026-01-03"),
            RecurrenceFrequency.BUSINESS_DAYS to listOf("2026-01-01", "2026-01-02", "2026-01-05"),
            RecurrenceFrequency.WEEKLY to listOf("2026-01-01", "2026-01-05", "2026-01-08"),
            RecurrenceFrequency.MONTHLY_DAY to listOf("2026-01-31", "2026-02-28", "2026-03-31"),
            RecurrenceFrequency.MONTHLY_LAST_DAY to listOf("2026-01-31", "2026-02-28", "2026-03-31"),
            RecurrenceFrequency.MONTHLY_NTH_WEEKDAY to listOf("2026-01-12", "2026-02-09", "2026-03-09"),
            RecurrenceFrequency.MONTH_INTERVAL to listOf("2026-01-31", "2026-02-28", "2026-03-31"),
            RecurrenceFrequency.YEARLY to listOf("2026-01-31", "2027-01-31", "2028-01-31"),
            RecurrenceFrequency.CUSTOM_INTERVAL to listOf("2026-01-01", "2026-01-02", "2026-01-03"),
        )
        expected.forEach { (frequency, dates) ->
            val revision = revision(frequency)
            RecurrenceEngine.preview(revision, 3).map { it.localDate.toString() } shouldBe dates
            RecurrenceEngine.preview(revision, 3) shouldBe RecurrenceEngine.preview(revision, 3)
        }
    }

    @Test
    fun `missing dates weekend adjustment exceptions limits and zones are explicit`() {
        val monthly = revision(RecurrenceFrequency.MONTHLY_DAY).copy(
            rule = revision(RecurrenceFrequency.MONTHLY_DAY).rule.copy(
                monthDay = 31,
                missingDayPolicy = MissingDayPolicy.SKIP,
                weekendAdjustment = WeekendAdjustment.NEXT_BUSINESS_DAY,
            ),
            endDate = LocalDate.of(2026, 5, 31),
            maxOccurrences = 5,
            zoneId = ZoneId.of("Asia/Tokyo"),
            occurrenceTime = LocalTime.of(9, 30),
        )
        val moved = RecurrenceException(
            monthly.seriesId,
            LocalDate.of(2026, 3, 31),
            RecurrenceExceptionAction.MOVE,
            null,
            Instant.parse("2026-03-30T00:30:00Z"),
        )
        val skipped = RecurrenceException(monthly.seriesId, LocalDate.of(2026, 2, 2), RecurrenceExceptionAction.SKIP, null, null)
        val result = RecurrenceEngine.next(monthly, listOf(moved, skipped), Instant.parse("2026-06-01T00:00:00Z"))
        result.map(PlannedRecurrenceOccurrence::localDate) shouldBe listOf(LocalDate.of(2026, 3, 30))
        result.single().occurrenceInstant shouldBe Instant.parse("2026-03-30T00:30:00Z")
        val withoutSkip = RecurrenceEngine.next(monthly, listOf(moved), Instant.parse("2026-06-01T00:00:00Z"))
        RecurrenceEngine.next(monthly, listOf(moved), Instant.parse("2026-06-01T00:00:00Z"), withoutSkip.mapTo(mutableSetOf(), PlannedRecurrenceOccurrence::occurrenceInstant)) shouldBe emptyList()
    }

    @Test
    fun `two thousand generated rules are monotonic bounded and duplicate free`() {
        val random = Random(23)
        repeat(2_000) { index ->
            val frequency = RecurrenceFrequency.entries[index % RecurrenceFrequency.entries.size]
            val base = revision(frequency).copy(
                rule = revision(frequency).rule.copy(interval = random.nextInt(1, 5)),
                startDate = LocalDate.of(2020 + random.nextInt(0, 8), random.nextInt(1, 13), random.nextInt(1, 26)),
                maxOccurrences = random.nextInt(1, 50),
            )
            val dates = RecurrenceEngine.next(base, emptyList(), Instant.parse("2040-01-01T00:00:00Z"), limit = 50)
            dates shouldBe dates.sortedBy(PlannedRecurrenceOccurrence::occurrenceInstant).distinctBy(PlannedRecurrenceOccurrence::occurrenceInstant)
            (dates.size <= requireNotNull(base.maxOccurrences)) shouldBe true
            dates.all { it.localDate >= base.startDate } shouldBe true
        }
    }

    private fun revision(frequency: RecurrenceFrequency): RecurrenceSeriesRevision {
        val monthDay = if (frequency in setOf(RecurrenceFrequency.MONTHLY_DAY, RecurrenceFrequency.MONTH_INTERVAL, RecurrenceFrequency.YEARLY)) 31 else null
        val nthWeek = if (frequency == RecurrenceFrequency.MONTHLY_NTH_WEEKDAY) 2 else null
        val weekday = if (frequency == RecurrenceFrequency.MONTHLY_NTH_WEEKDAY) DayOfWeek.MONDAY else null
        val weekdays = if (frequency == RecurrenceFrequency.WEEKLY) setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY) else emptySet()
        return RecurrenceSeriesRevision(
            RecurrenceSeriesRevisionId(stableId(23_001)),
            RecurrenceSeriesId(stableId(23_002)),
            1,
            RecurrenceRule(frequency, 1, weekdays, monthDay, nthWeek, weekday, MissingDayPolicy.MOVE_TO_MONTH_END, WeekendAdjustment.NONE),
            LocalDate.of(2026, 1, if (frequency in setOf(RecurrenceFrequency.MONTHLY_DAY, RecurrenceFrequency.MONTHLY_LAST_DAY, RecurrenceFrequency.MONTH_INTERVAL, RecurrenceFrequency.YEARLY)) 31 else 1),
            null,
            null,
            LocalTime.of(8, 0),
            ZoneId.of("UTC"),
            RecurrenceGenerationMode.CANDIDATE,
            null,
            true,
            BookCommitId(stableId(23_003)),
        )
    }
}
