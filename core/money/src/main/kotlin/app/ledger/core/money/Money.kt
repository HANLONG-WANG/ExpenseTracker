package app.ledger.core.money

import app.ledger.core.common.CheckedArithmetic
import app.ledger.core.common.CurrencyError
import app.ledger.core.common.CurrencyErrorReason
import app.ledger.core.common.DomainResult
import app.ledger.core.common.ValidationError
import app.ledger.core.common.ValidationReason
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

data class Money(
    val minor: Long,
    val currency: CurrencyCode,
) {
    fun plus(other: Money): DomainResult<Money> = withSameCurrency(other) {
        CheckedArithmetic.add(minor, other.minor).toMoney(currency)
    }

    fun minus(other: Money): DomainResult<Money> = withSameCurrency(other) {
        CheckedArithmetic.subtract(minor, other.minor).toMoney(currency)
    }

    fun negated(): DomainResult<Money> = CheckedArithmetic.negate(minor).toMoney(currency)

    fun multipliedBy(factor: Long): DomainResult<Money> = CheckedArithmetic.multiply(minor, factor).toMoney(currency)

    fun multipliedBy(factor: BigInteger): DomainResult<Money> {
        val product = BigInteger.valueOf(minor).multiply(factor)
        return CheckedArithmetic.toLongExact(product).toMoney(currency)
    }

    fun requirePositive(): DomainResult<Money> = if (minor > 0L) {
        DomainResult.Success(this)
    } else {
        DomainResult.Failure(ValidationError("amount", ValidationReason.MUST_BE_POSITIVE))
    }

    fun toMajor(metadata: CurrencyMetadata): DomainResult<BigDecimal> = if (metadata.code == currency) {
        DomainResult.Success(BigDecimal.valueOf(minor, metadata.fractionDigits))
    } else {
        DomainResult.Failure(CurrencyError(CurrencyErrorReason.CURRENCY_MISMATCH, currency.value))
    }

    private inline fun withSameCurrency(other: Money, operation: () -> DomainResult<Money>): DomainResult<Money> = if (currency == other.currency) {
        operation()
    } else {
        DomainResult.Failure(CurrencyError(CurrencyErrorReason.CURRENCY_MISMATCH, other.currency.value))
    }

    companion object {
        fun fromMajor(
            major: BigDecimal,
            metadata: CurrencyMetadata,
            roundingMode: RoundingMode,
        ): DomainResult<Money> = try {
            val roundedMinor = major
                .movePointRight(metadata.fractionDigits)
                .setScale(0, roundingMode)
                .longValueExact()
            DomainResult.Success(Money(roundedMinor, metadata.code))
        } catch (_: ArithmeticException) {
            DomainResult.Failure(CurrencyError(CurrencyErrorReason.NUMERIC_RANGE, metadata.code.value))
        }

        fun sum(values: Iterable<Money>, currency: CurrencyCode): DomainResult<Money> {
            val minors = ArrayList<Long>()
            for (money in values) {
                if (money.currency != currency) {
                    return DomainResult.Failure(
                        CurrencyError(CurrencyErrorReason.CURRENCY_MISMATCH, money.currency.value),
                    )
                }
                minors += money.minor
            }
            return CheckedArithmetic.sum(minors).toMoney(currency)
        }

        fun sumWide(values: Iterable<Money>, currency: CurrencyCode): DomainResult<BigInteger> {
            val minors = ArrayList<Long>()
            for (money in values) {
                if (money.currency != currency) {
                    return DomainResult.Failure(
                        CurrencyError(CurrencyErrorReason.CURRENCY_MISMATCH, money.currency.value),
                    )
                }
                minors += money.minor
            }
            return DomainResult.Success(CheckedArithmetic.sumWide(minors))
        }
    }
}

private fun DomainResult<Long>.toMoney(currency: CurrencyCode): DomainResult<Money> = when (this) {
    is DomainResult.Success -> DomainResult.Success(Money(value, currency))
    is DomainResult.Failure -> this
}
