package app.ledger.core.money

import app.ledger.core.common.CurrencyError
import app.ledger.core.common.CurrencyErrorReason
import app.ledger.core.common.DomainResult
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.Locale

class FxAndFormattingTest {
    private val catalog = JvmLegalTenderCurrencyCatalog.create()
    private val usd = metadata("USD")
    private val jpy = metadata("JPY")

    @Test
    fun `FX conversion preserves immutable evidence and rounds to target minor unit`() {
        val evidence = evidence(rate = "152.0000")
        val conversion = FxConverter().convert(Money(1001L, usd.code), jpy, evidence, usd)

        conversion shouldBe DomainResult.Success(
            FxConversion(
                source = Money(1001L, usd.code),
                target = Money(1522L, jpy.code),
                evidence = evidence,
            ),
        )
        evidence.rate shouldBe BigDecimal("152")
        evidence.quotedAt shouldBe Instant.parse("2026-07-30T07:00:00Z")
    }

    @Test
    fun `FX conversion rejects mismatched evidence and non-positive rates`() {
        FxEvidence.create(
            FxEvidenceInput(
                sourceCurrency = usd.code,
                targetCurrency = jpy.code,
                rate = BigDecimal.ZERO,
                provider = provider(),
                quotedAt = null,
                fetchedAt = null,
                source = FxRateSource.MANUAL,
                manuallyOverridden = true,
            ),
        ) shouldBe DomainResult.Failure(CurrencyError(CurrencyErrorReason.INVALID_RATE, "JPY"))

        FxConverter().convert(Money(100L, jpy.code), jpy, evidence("152"), jpy) shouldBe
            DomainResult.Failure(CurrencyError(CurrencyErrorReason.CURRENCY_MISMATCH, "JPY"))
    }

    @Test
    fun `currency formatter emits preformatted visible and privacy-safe hidden UI models`() {
        val formatter = LocaleCurrencyFormatter(catalog)
        val visible = formatter.format(
            MoneyFormatRequest(
                money = Money(1234L, usd.code),
                locale = Locale.US,
                semantic = AmountSemantic.OUTFLOW,
                visibility = AmountVisibility.VISIBLE,
                display = CurrencyDisplay.SYMBOL,
            ),
        ) as DomainResult.Success
        visible.value.formatted shouldBe "$12.34"
        visible.value.fullAccessibleText shouldBe "12.34\u00a0USD"

        val hidden = formatter.format(
            MoneyFormatRequest(
                money = Money(987654321L, jpy.code),
                locale = Locale.JAPAN,
                semantic = AmountSemantic.NEUTRAL,
                visibility = AmountVisibility.HIDDEN,
                hiddenContent = HiddenMoneyContent("••••", "非表示"),
            ),
        ) as DomainResult.Success
        hidden.value shouldBe MoneyUiModel(
            formatted = "••••",
            fullAccessibleText = "非表示",
            semantic = AmountSemantic.NEUTRAL,
            visibility = AmountVisibility.HIDDEN,
        )
        hidden.value.toString().contains("987654321") shouldBe false
    }

    private fun evidence(rate: String): FxEvidence = (
        FxEvidence.create(
            FxEvidenceInput(
                sourceCurrency = usd.code,
                targetCurrency = jpy.code,
                rate = BigDecimal(rate),
                provider = provider(),
                quotedAt = Instant.parse("2026-07-30T07:00:00Z"),
                fetchedAt = Instant.parse("2026-07-30T23:12:00Z"),
                source = FxRateSource.CACHE,
                manuallyOverridden = false,
            ),
        ) as DomainResult.Success
        ).value

    private fun provider(): FxProvider = (FxProvider.of("fixture-provider") as DomainResult.Success).value

    private fun metadata(value: String): CurrencyMetadata = (catalog.require((CurrencyCode.parse(value) as DomainResult.Success).value) as DomainResult.Success).value
}
