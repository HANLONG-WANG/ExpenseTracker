package app.ledger.core.time

import app.ledger.core.common.DomainResult
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

@JvmInline
value class LocalDateKey private constructor(val value: Int) {
    fun toLocalDate(): LocalDate {
        val year = value / DATE_YEAR_FACTOR
        val month = value / DATE_MONTH_FACTOR % DATE_MONTH_FACTOR
        val day = value % DATE_MONTH_FACTOR
        return LocalDate.of(year, month, day)
    }

    companion object {
        fun from(date: LocalDate): LocalDateKey = LocalDateKey(
            Math.addExact(
                Math.addExact(
                    Math.multiplyExact(date.year, DATE_YEAR_FACTOR),
                    Math.multiplyExact(date.monthValue, DATE_MONTH_FACTOR),
                ),
                date.dayOfMonth,
            ),
        )

        fun parse(value: Int): DomainResult<LocalDateKey> = try {
            val key = LocalDateKey(value)
            key.toLocalDate()
            DomainResult.Success(key)
        } catch (_: DateTimeException) {
            DomainResult.Failure(TemporalError(TemporalErrorKind.INVALID_DATE_KEY))
        }

        private const val DATE_YEAR_FACTOR = 10_000
        private const val DATE_MONTH_FACTOR = 100
    }
}

@JvmInline
value class YearMonthKey private constructor(val value: Int) : Comparable<YearMonthKey> {
    fun toYearMonth(): YearMonth = YearMonth.of(value / MONTH_KEY_FACTOR, value % MONTH_KEY_FACTOR)

    override fun compareTo(other: YearMonthKey): Int = value.compareTo(other.value)

    companion object {
        fun from(month: YearMonth): YearMonthKey = YearMonthKey(
            Math.addExact(Math.multiplyExact(month.year, MONTH_KEY_FACTOR), month.monthValue),
        )

        fun parse(value: Int): DomainResult<YearMonthKey> = try {
            val key = YearMonthKey(value)
            key.toYearMonth()
            DomainResult.Success(key)
        } catch (_: DateTimeException) {
            DomainResult.Failure(TemporalError(TemporalErrorKind.INVALID_MONTH_KEY))
        }

        private const val MONTH_KEY_FACTOR = 100
    }
}

object TemporalStorageCodec {
    fun instantToEpochMilliseconds(instant: Instant): DomainResult<Long> = try {
        DomainResult.Success(instant.toEpochMilli())
    } catch (_: ArithmeticException) {
        DomainResult.Failure(TemporalError(TemporalErrorKind.INSTANT_OUT_OF_RANGE))
    }

    fun instantFromEpochMilliseconds(value: Long): Instant = Instant.ofEpochMilli(value)

    fun localDateToKey(date: LocalDate): LocalDateKey = LocalDateKey.from(date)

    fun localDateFromKey(value: Int): DomainResult<LocalDateKey> = LocalDateKey.parse(value)

    fun yearMonthToKey(month: YearMonth): YearMonthKey = YearMonthKey.from(month)

    fun yearMonthFromKey(value: Int): DomainResult<YearMonthKey> = YearMonthKey.parse(value)
}
