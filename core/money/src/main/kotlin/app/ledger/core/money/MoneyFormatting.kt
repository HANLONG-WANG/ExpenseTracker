package app.ledger.core.money

import app.ledger.core.common.DomainResult
import app.ledger.core.common.ValidationError
import app.ledger.core.common.ValidationReason
import app.ledger.core.common.flatMap
import app.ledger.core.common.map
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

enum class AmountSemantic {
    NEUTRAL,
    OUTFLOW,
    INFLOW,
    REFUND,
    TRANSFER,
    CANDIDATE,
}

enum class AmountVisibility {
    VISIBLE,
    HIDDEN,
}

enum class CurrencyDisplay {
    AUTO,
    CODE,
    SYMBOL,
}

data class HiddenMoneyContent(
    val maskedText: String,
    val fullAccessibleText: String,
)

data class MoneyUiModel(
    val formatted: String,
    val fullAccessibleText: String,
    val semantic: AmountSemantic,
    val visibility: AmountVisibility,
    val secondaryFormatted: String? = null,
)

data class MoneyFormatRequest(
    val money: Money,
    val locale: Locale,
    val semantic: AmountSemantic,
    val visibility: AmountVisibility,
    val display: CurrencyDisplay = CurrencyDisplay.AUTO,
    val showPositiveSign: Boolean = false,
    val secondaryMoney: Money? = null,
    val hiddenContent: HiddenMoneyContent? = null,
)

fun interface CurrencyFormatter {
    fun format(request: MoneyFormatRequest): DomainResult<MoneyUiModel>
}

class LocaleCurrencyFormatter(
    private val catalog: CurrencyMetadataCatalog,
) : CurrencyFormatter {
    override fun format(request: MoneyFormatRequest): DomainResult<MoneyUiModel> = when (request.visibility) {
        AmountVisibility.HIDDEN -> formatHidden(request)
        AmountVisibility.VISIBLE -> formatVisibleModel(request)
    }

    private fun formatHidden(request: MoneyFormatRequest): DomainResult<MoneyUiModel> = request.hiddenContent?.let { hidden ->
        DomainResult.Success(
            MoneyUiModel(
                formatted = hidden.maskedText,
                fullAccessibleText = hidden.fullAccessibleText,
                semantic = request.semantic,
                visibility = request.visibility,
                secondaryFormatted = request.secondaryMoney?.let { hidden.maskedText },
            ),
        )
    } ?: DomainResult.Failure(ValidationError("hiddenMoneyContent", ValidationReason.REQUIRED))

    private fun formatVisibleModel(request: MoneyFormatRequest): DomainResult<MoneyUiModel> = formatVisible(request.money, request).flatMap { primary ->
        val secondary = request.secondaryMoney
        if (secondary == null) {
            DomainResult.Success(primary.toUiModel(request, null))
        } else {
            formatVisible(secondary, request.copy(money = secondary, secondaryMoney = null)).map { formatted ->
                primary.toUiModel(request, formatted.display)
            }
        }
    }

    private fun formatVisible(money: Money, request: MoneyFormatRequest): DomainResult<FormattedValue> {
        val metadata = when (val result = catalog.require(money.currency)) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return result
        }
        val major = BigDecimal.valueOf(money.minor, metadata.fractionDigits)
        val display = when (request.display) {
            CurrencyDisplay.CODE -> codeFormat(major, metadata, request.locale)
            CurrencyDisplay.SYMBOL -> symbolFormat(major, metadata, request.locale)
            CurrencyDisplay.AUTO -> if (hasAmbiguousSymbol(metadata.code, request.locale)) {
                codeFormat(major, metadata, request.locale)
            } else {
                symbolFormat(major, metadata, request.locale)
            }
        }.withSign(request.showPositiveSign, money.minor)
        val accessible = codeFormat(major, metadata, request.locale).withSign(request.showPositiveSign, money.minor)
        return DomainResult.Success(FormattedValue(display, accessible))
    }

    private fun codeFormat(value: BigDecimal, metadata: CurrencyMetadata, locale: Locale): String = "${numberFormatter(metadata, locale).format(value)}\u00a0${metadata.code.value}"

    private fun symbolFormat(value: BigDecimal, metadata: CurrencyMetadata, locale: Locale): String {
        val formatter = NumberFormat.getCurrencyInstance(locale) as DecimalFormat
        formatter.currency = Currency.getInstance(metadata.code.value)
        formatter.minimumFractionDigits = metadata.fractionDigits
        formatter.maximumFractionDigits = metadata.fractionDigits
        formatter.roundingMode = RoundingMode.UNNECESSARY
        return formatter.format(value).replace('-', '\u2212')
    }

    private fun numberFormatter(metadata: CurrencyMetadata, locale: Locale): DecimalFormat {
        val formatter = NumberFormat.getNumberInstance(locale) as DecimalFormat
        formatter.minimumFractionDigits = metadata.fractionDigits
        formatter.maximumFractionDigits = metadata.fractionDigits
        formatter.roundingMode = RoundingMode.UNNECESSARY
        return formatter
    }

    private fun hasAmbiguousSymbol(code: CurrencyCode, locale: Locale): Boolean {
        val symbol = Currency.getInstance(code.value).getSymbol(locale)
        return catalog.activeLegalTenderCurrencies().count { metadata ->
            Currency.getInstance(metadata.code.value).getSymbol(locale) == symbol
        } > 1
    }

    private fun String.withSign(showPositiveSign: Boolean, minor: Long): String = when {
        minor < 0L -> replace('-', '\u2212')
        showPositiveSign && minor > 0L -> "+$this"
        else -> this
    }

    private fun FormattedValue.toUiModel(request: MoneyFormatRequest, secondary: String?): MoneyUiModel = MoneyUiModel(
        formatted = display,
        fullAccessibleText = accessible,
        semantic = request.semantic,
        visibility = request.visibility,
        secondaryFormatted = secondary,
    )

    private data class FormattedValue(val display: String, val accessible: String)
}
