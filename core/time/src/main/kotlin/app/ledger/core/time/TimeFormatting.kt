package app.ledger.core.time

import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

data class DateTimeUiModel(
    val formatted: String,
    val fullAccessibleText: String,
    val zoneSupplement: String?,
)

data class MonthUiModel(
    val formatted: String,
    val fullAccessibleText: String,
)

data class DateTimeFormatRequest(
    val effectiveTime: EffectiveTime,
    val defaultZoneId: ZoneId,
    val locale: Locale,
)

interface LedgerDateTimeFormatter {
    fun format(request: DateTimeFormatRequest): DateTimeUiModel

    fun formatMonth(month: YearMonth, locale: Locale): MonthUiModel
}

class LocaleLedgerDateTimeFormatter : LedgerDateTimeFormatter {
    override fun format(request: DateTimeFormatRequest): DateTimeUiModel {
        val zoned = request.effectiveTime.zonedDateTime
        val defaultZoned = request.effectiveTime.instant.atZone(request.defaultZoneId)
        val shortFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(request.locale)
        val accessibleFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.FULL, FormatStyle.LONG)
            .withLocale(request.locale)
        val needsZone = request.effectiveTime.zoneId != request.defaultZoneId ||
            request.effectiveTime.localDate != defaultZoned.toLocalDate()
        val zoneSupplement = if (needsZone) {
            "${request.effectiveTime.zoneId.id} ${zoned.offset.id}"
        } else {
            null
        }
        return DateTimeUiModel(
            formatted = shortFormatter.format(zoned),
            fullAccessibleText = accessibleFormatter.format(zoned),
            zoneSupplement = zoneSupplement,
        )
    }

    override fun formatMonth(month: YearMonth, locale: Locale): MonthUiModel {
        val formatter = DateTimeFormatter.ofPattern("yyyy MMMM", locale)
        val formatted = formatter.format(month)
        return MonthUiModel(formatted, formatted)
    }
}
