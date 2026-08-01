package app.ledger.core.money

import app.ledger.core.common.CurrencyError
import app.ledger.core.common.CurrencyErrorReason
import app.ledger.core.common.DomainResult
import java.util.Currency
import java.util.Locale

@JvmInline
value class CurrencyCode private constructor(val value: String) : Comparable<CurrencyCode> {
    override fun compareTo(other: CurrencyCode): Int = value.compareTo(other.value)

    override fun toString(): String = value

    companion object {
        fun parse(value: String): DomainResult<CurrencyCode> {
            val normalized = value.uppercase(Locale.ROOT)
            return if (CODE.matches(normalized)) {
                DomainResult.Success(CurrencyCode(normalized))
            } else {
                DomainResult.Failure(CurrencyError(CurrencyErrorReason.INVALID_CODE))
            }
        }

        internal fun trusted(value: String): CurrencyCode = CurrencyCode(value)

        private val CODE = Regex("[A-Z]{3}")
    }
}

data class CurrencyMetadata(
    val code: CurrencyCode,
    val numericCode: Int,
    val fractionDigits: Int,
) {
    init {
        require(numericCode in MINIMUM_NUMERIC_CODE..MAXIMUM_NUMERIC_CODE) {
            "ISO numeric currency code is out of range"
        }
        require(fractionDigits in 0..MAXIMUM_FRACTION_DIGITS) { "Currency fraction digits are out of range" }
    }

    val minorUnitFactor: Long = powerOfTen(fractionDigits)

    private fun powerOfTen(exponent: Int): Long {
        var result = 1L
        repeat(exponent) {
            result = Math.multiplyExact(result, DECIMAL_RADIX)
        }
        return result
    }

    private companion object {
        const val MINIMUM_NUMERIC_CODE = 1
        const val MAXIMUM_NUMERIC_CODE = 999
        const val MAXIMUM_FRACTION_DIGITS = 6
        const val DECIMAL_RADIX = 10L
    }
}

interface CurrencyMetadataCatalog {
    fun find(code: CurrencyCode): CurrencyMetadata?

    fun activeLegalTenderCurrencies(): Set<CurrencyMetadata>

    fun require(code: CurrencyCode): DomainResult<CurrencyMetadata> = find(code)?.let { DomainResult.Success(it) }
        ?: DomainResult.Failure(CurrencyError(CurrencyErrorReason.UNSUPPORTED_CURRENCY, code.value))
}

/**
 * Legal-tender metadata derived from the JDK 17 ISO country/currency data.
 *
 * Looking up the current currency for every ISO country excludes script currencies and arbitrary
 * three-letter codes while covering currencies that are legal tender for at least one country.
 */
class JvmLegalTenderCurrencyCatalog private constructor(
    currencies: Set<CurrencyMetadata>,
) : CurrencyMetadataCatalog {
    private val byCode = currencies.associateBy(CurrencyMetadata::code).toMap()
    private val snapshot = byCode.values.toSet()

    override fun find(code: CurrencyCode): CurrencyMetadata? = byCode[code]

    override fun activeLegalTenderCurrencies(): Set<CurrencyMetadata> = snapshot

    companion object {
        fun create(): JvmLegalTenderCurrencyCatalog {
            val metadata = Locale.getISOCountries().mapNotNull { countryCode ->
                val locale = Locale.Builder().setRegion(countryCode).build()
                val currency = runCatching { Currency.getInstance(locale) }.getOrNull() ?: return@mapNotNull null
                if (currency.defaultFractionDigits < 0 || currency.numericCode <= 0) return@mapNotNull null
                CurrencyMetadata(
                    code = CurrencyCode.trusted(currency.currencyCode),
                    numericCode = currency.numericCode,
                    fractionDigits = currency.defaultFractionDigits,
                )
            }.toSet()
            return JvmLegalTenderCurrencyCatalog(metadata)
        }
    }
}
