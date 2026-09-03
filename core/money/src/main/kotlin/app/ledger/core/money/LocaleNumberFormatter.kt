package app.ledger.core.money

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/** Shared locale boundary for user-visible rates, percentages, counts, and weights. */
public object LocaleNumberFormatter {
    public fun decimal(value: BigDecimal, locale: Locale, maximumFractionDigits: Int = 10): String = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 0
        this.maximumFractionDigits = maximumFractionDigits
        isGroupingUsed = true
    }.format(value)

    public fun percentage(value: BigDecimal, locale: Locale): String = NumberFormat.getPercentInstance(locale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 2
    }.format(value)

    public fun integer(value: Number, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(value)

    public fun weights(values: List<BigDecimal>, locale: Locale): String = values.joinToString(" : ") { decimal(it, locale, maximumFractionDigits = 2) }
}
