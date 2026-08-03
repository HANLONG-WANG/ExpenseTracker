package app.ledger.feature.record

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.core.money.FxEvidence
import app.ledger.core.money.FxEvidenceInput
import app.ledger.core.money.FxProvider
import app.ledger.core.money.FxRateSource
import app.ledger.finance.application.AccountReferenceView
import app.ledger.finance.application.ReferenceDataSnapshot
import app.ledger.finance.application.SpecializedFxQuote
import app.ledger.finance.domain.EntityStatus
import app.ledger.finance.domain.UserAccountType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class SpecializedTransactionPolicyTest {
    @Test
    fun `cross currency transfer keeps both authoritative account amounts but one balanced base amount`() {
        var state = SpecializedTransactionPolicy.create(SpecializedTransactionKind.TRANSFER, snapshot(), null, NOW, ZONE, Locale.ENGLISH)
        state = SpecializedTransactionPolicy.updateExpression(state, false, "10", Locale.ENGLISH)
        state = SpecializedTransactionPolicy.updateExpression(state, true, "9", Locale.ENGLISH)
        state = SpecializedTransactionPolicy.withQuote(state, usd, quote(usd, jpy, "150"))

        val prepared = SpecializedTransactionPolicy.prepareAmounts(state)

        assertEquals(1_000L, prepared.outgoing.accountMinor)
        assertEquals(900L, prepared.incoming?.accountMinor)
        assertEquals(1_500L, prepared.outgoing.baseMinor)
        assertEquals(prepared.outgoing.baseMinor, prepared.incoming?.baseMinor)
        assertEquals(FxRateSource.IMPLIED_FROM_ACTUAL_AMOUNTS, prepared.incoming?.accountToBaseEvidence?.source)
    }

    @Test
    fun `exchange preserves both base valuations and exposes exact spread without floating point`() {
        var state = SpecializedTransactionPolicy.create(SpecializedTransactionKind.FX_EXCHANGE, snapshot(), null, NOW, ZONE, Locale.ENGLISH)
        state = SpecializedTransactionPolicy.updateExpression(state, false, "10", Locale.ENGLISH)
        state = SpecializedTransactionPolicy.updateExpression(state, true, "9", Locale.ENGLISH)
        state = SpecializedTransactionPolicy.withQuote(state, usd, quote(usd, jpy, "150"))
        state = SpecializedTransactionPolicy.withQuote(state, eur, quote(eur, jpy, "165"))

        val prepared = SpecializedTransactionPolicy.prepareAmounts(state)

        assertEquals(1_500L, prepared.outgoing.baseMinor)
        assertEquals(1_485L, prepared.incoming?.baseMinor)
        assertEquals(15L, prepared.spreadCostBaseMinor)
        val display = SpecializedTransactionPolicy.fxDisplaySummary(state)
        assertEquals(BigDecimal("0.9"), display.effectiveRate)
        assertEquals(BigDecimal("0.9090909090909090909090909090909091"), display.referenceRate)
        assertEquals(15L, display.spreadCostBaseMinor)
    }

    @Test
    fun `offline no cache requires manual positive rate and never fabricates zero`() {
        var state = SpecializedTransactionPolicy.create(SpecializedTransactionKind.BALANCE_ADJUSTMENT, snapshot(), null, NOW, ZONE, Locale.ENGLISH)
        state = SpecializedTransactionPolicy.updateExpression(state, false, "12.34", Locale.ENGLISH)

        assertTrue(SpecializedTransactionPolicy.validate(state).errors.any { it.field == SpecializedField.RATE })

        state = SpecializedTransactionPolicy.setManualRate(state, false, "150.25")
        val prepared = SpecializedTransactionPolicy.prepareAmounts(state)
        assertTrue(prepared.outgoing.baseMinor > 0L)
        assertEquals(FxRateSource.MANUAL, prepared.outgoing.accountToBaseEvidence?.source)
    }

    @Test
    fun `same account transfer is rejected before application submission`() {
        var state = SpecializedTransactionPolicy.create(SpecializedTransactionKind.TRANSFER, snapshot(), null, NOW, ZONE, Locale.ENGLISH)
        state = state.copy(draft = state.draft.copy(toAccountId = state.draft.fromAccountId))
        state = SpecializedTransactionPolicy.updateExpression(state, false, "1", Locale.ENGLISH)

        assertTrue(SpecializedTransactionPolicy.validate(state).errors.any { it.code == "SAME_ACCOUNT" })
    }

    private fun snapshot(): ReferenceDataSnapshot = ReferenceDataSnapshot(
        bookId = id(1),
        baseCurrency = jpy,
        localRevision = 8L,
        accounts = listOf(account(id(2), "USD wallet", usd, 0), account(id(3), "EUR wallet", eur, 1)),
        cards = emptyList(),
        categories = emptyList(),
        merchants = emptyList(),
        places = emptyList(),
        locations = emptyList(),
        checkpoints = emptyList(),
        accountTransactions = emptyList(),
        accountGoals = emptyList(),
        coreNetFinancialAssetsMinor = null,
        adjustedNetFinancialPositionMinor = null,
        valuationMissing = true,
    )

    private fun account(id: StableId, name: String, currency: CurrencyCode, sort: Int): AccountReferenceView = AccountReferenceView(
        id, UserAccountType.CASH, name, currency, EntityStatus.ACTIVE, null, null, null, "account", 0, sort, 1L,
        0L, null, null, false, 0L,
    )

    private fun quote(source: CurrencyCode, target: CurrencyCode, rate: String): SpecializedFxQuote {
        val evidence = (
            FxEvidence.create(
                FxEvidenceInput(source, target, BigDecimal(rate), provider, NOW, NOW, FxRateSource.CACHE, false),
            ) as DomainResult.Success
            ).value
        return SpecializedFxQuote(evidence, false)
    }

    private fun id(seed: Byte): StableId = (StableId.fromBytes(ByteArray(StableId.BYTE_COUNT) { seed }) as DomainResult.Success).value
    private companion object {
        fun currency(value: String): CurrencyCode = (CurrencyCode.parse(value) as DomainResult.Success).value
        val NOW: Instant = Instant.parse("2026-08-03T04:05:06Z")
        val ZONE: ZoneId = ZoneId.of("Asia/Tokyo")
        val provider: FxProvider = (FxProvider.of("test-cache") as DomainResult.Success).value
        val usd: CurrencyCode = currency("USD")
        val eur: CurrencyCode = currency("EUR")
        val jpy: CurrencyCode = currency("JPY")
    }
}
