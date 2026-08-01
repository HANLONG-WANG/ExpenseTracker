package app.ledger.core.time

import app.ledger.core.common.DomainResult
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class CalendarPeriodsPropertyTest {
    @Test
    fun `budget months are natural months across leap years and zones`() = runTest {
        checkAll(iterations = 500, Arb.int(2000, 2100), Arb.int(1, 12)) { year, month ->
            val period = BudgetMonthPeriod(YearMonth.of(year, month))

            period.firstDate shouldBe LocalDate.of(year, month, 1)
            period.lastDate shouldBe YearMonth.of(year, month).atEndOfMonth()
            period.remainingDaysInclusive(period.firstDate) shouldBe period.month.lengthOfMonth()
            period.remainingDaysInclusive(period.lastDate) shouldBe 1
            period.next().previous() shouldBe period
        }
        BudgetMonthPeriod(YearMonth.of(2024, 2)).lastDate shouldBe LocalDate.of(2024, 2, 29)

        val tokyoBounds = BudgetMonthPeriod(YearMonth.of(2026, 3)).instantBounds(ZoneId.of("Asia/Tokyo"))
        val newYorkBounds = BudgetMonthPeriod(YearMonth.of(2026, 3)).instantBounds(ZoneId.of("America/New_York"))
        tokyoBounds.startInclusive shouldBe Instant.parse("2026-02-28T15:00:00Z")
        newYorkBounds.startInclusive shouldBe Instant.parse("2026-03-01T05:00:00Z")
    }

    @Test
    fun `statement day at month end clamps and creates contiguous cycles`() {
        val calculator = StatementCycleCalculator(StatementDateRule(31))
        val zone = ZoneId.of("Asia/Tokyo")
        val february = calculator.cycleForDate(LocalDate.of(2024, 2, 29), zone)
        val march = calculator.next(february)

        february shouldBe StatementCycle(LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 29), zone)
        march shouldBe StatementCycle(LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 31), zone)
        calculator.previous(march) shouldBe february
    }

    @Test
    fun `statement assignment uses account zone rather than transaction local date`() {
        val calculator = StatementCycleCalculator(StatementDateRule(15))
        val instant = Instant.parse("2026-08-15T16:00:00Z")
        val transactionTime = EffectiveTime.fromInstant(instant, ZoneId.of("America/Los_Angeles"))

        transactionTime.localDate shouldBe LocalDate.of(2026, 8, 15)
        calculator.cycleFor(transactionTime, ZoneId.of("Asia/Tokyo")) shouldBe StatementCycle(
            cycleStart = LocalDate.of(2026, 8, 16),
            cycleEnd = LocalDate.of(2026, 9, 15),
            statementZoneId = ZoneId.of("Asia/Tokyo"),
        )
    }

    @Test
    fun `database temporal keys round trip and reject invalid dates`() = runTest {
        checkAll(iterations = 500, Arb.int(2000, 2100), Arb.int(1, 12), Arb.int(1, 28)) { year, month, day ->
            val date = LocalDate.of(year, month, day)
            val key = TemporalStorageCodec.localDateToKey(date)
            (TemporalStorageCodec.localDateFromKey(key.value) as DomainResult.Success).value.toLocalDate() shouldBe date

            val yearMonth = YearMonth.of(year, month)
            val monthKey = TemporalStorageCodec.yearMonthToKey(yearMonth)
            (TemporalStorageCodec.yearMonthFromKey(monthKey.value) as DomainResult.Success).value.toYearMonth() shouldBe
                yearMonth
        }
        TemporalStorageCodec.localDateFromKey(20260230) shouldBe
            DomainResult.Failure(TemporalError(TemporalErrorKind.INVALID_DATE_KEY))
        TemporalStorageCodec.yearMonthFromKey(202613) shouldBe
            DomainResult.Failure(TemporalError(TemporalErrorKind.INVALID_MONTH_KEY))
    }
}
