package app.ledger.feature.settings

import app.ledger.core.common.DomainResult
import app.ledger.core.money.CurrencyCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class CurrencySettingsPolicyTest {
    @Test
    fun `base and account currencies remain visible while optional currencies can hide and reorder`() {
        var state = CurrencySettingsPolicy.create(jpy, setOf(usd), listOf("EUR", "JPY"))
        assertEquals(listOf(eur, jpy, usd), state.visibleCodes)

        state = CurrencySettingsPolicy.toggle(state, jpy)
        state = CurrencySettingsPolicy.toggle(state, usd)
        assertTrue(jpy in state.visibleCodes && usd in state.visibleCodes)

        state = CurrencySettingsPolicy.toggle(state, eur)
        assertTrue(eur !in state.visibleCodes)
        state = CurrencySettingsPolicy.move(state, usd, -1)
        assertEquals(listOf(usd, jpy), state.visibleCodes)
    }

    @Test
    fun `search covers legal tender code and localized display name`() {
        val state = CurrencySettingsPolicy.search(CurrencySettingsPolicy.create(jpy, emptySet(), emptyList()), "USD")
        assertEquals(listOf(usd), CurrencySettingsPolicy.filteredCodes(state, Locale.ENGLISH))
    }

    private companion object {
        fun currency(value: String): CurrencyCode = (CurrencyCode.parse(value) as DomainResult.Success).value
        val usd: CurrencyCode = currency("USD")
        val eur: CurrencyCode = currency("EUR")
        val jpy: CurrencyCode = currency("JPY")
    }
}
