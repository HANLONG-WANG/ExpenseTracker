package app.ledger.core.money

import app.ledger.core.common.ArithmeticOperation
import app.ledger.core.common.ArithmeticOverflowError
import app.ledger.core.common.CurrencyError
import app.ledger.core.common.CurrencyErrorReason
import app.ledger.core.common.DomainResult
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

class MoneyAndCurrencyPropertyTest {
    private val catalog = JvmLegalTenderCurrencyCatalog.create()
    private val jpy = metadata("JPY")
    private val usd = metadata("USD")

    @Test
    fun `JDK legal tender catalog exposes currency-specific minor units and excludes crypto`() {
        jpy.fractionDigits shouldBe 0
        usd.fractionDigits shouldBe 2
        metadata("KWD").fractionDigits shouldBe 3
        catalog.find(code("BTC")) shouldBe null
        catalog.activeLegalTenderCurrencies().all { it.code.value.matches(Regex("[A-Z]{3}")) } shouldBe true
    }

    @Test
    fun `major amounts round only at the explicit currency boundary`() {
        Money.fromMajor(BigDecimal("1200.4"), jpy, RoundingMode.HALF_EVEN) shouldBe
            DomainResult.Success(Money(1200L, jpy.code))
        Money.fromMajor(BigDecimal("12.345"), usd, RoundingMode.HALF_EVEN) shouldBe
            DomainResult.Success(Money(1234L, usd.code))
        Money(1234L, usd.code).toMajor(usd) shouldBe DomainResult.Success(BigDecimal("12.34"))
    }

    @Test
    fun `Money addition matches checked Long arithmetic for all non-overflowing samples`() = runTest {
        checkAll(
            iterations = 1_000,
            Arb.long(Long.MIN_VALUE / 2L, Long.MAX_VALUE / 2L),
            Arb.long(Long.MIN_VALUE / 2L, Long.MAX_VALUE / 2L),
        ) { left, right ->
            Money(left, jpy.code).plus(Money(right, jpy.code)) shouldBe
                DomainResult.Success(Money(Math.addExact(left, right), jpy.code))
        }
    }

    @Test
    fun `Money accumulation rejects overflow and currency mixing`() {
        Money.sum(listOf(Money(Long.MAX_VALUE, jpy.code), Money(1L, jpy.code)), jpy.code) shouldBe
            DomainResult.Failure(ArithmeticOverflowError(ArithmeticOperation.ACCUMULATE))
        Money.sum(listOf(Money(1L, jpy.code), Money(1L, usd.code)), jpy.code) shouldBe
            DomainResult.Failure(CurrencyError(CurrencyErrorReason.CURRENCY_MISMATCH, "USD"))
        Money.sumWide(listOf(Money(Long.MAX_VALUE, jpy.code), Money(Long.MAX_VALUE, jpy.code)), jpy.code) shouldBe
            DomainResult.Success(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO))
    }

    private fun metadata(value: String): CurrencyMetadata = (catalog.require(code(value)) as DomainResult.Success).value

    private fun code(value: String): CurrencyCode = (CurrencyCode.parse(value) as DomainResult.Success).value
}
