package com.yohaku.expensetracker

import com.yohaku.expensetracker.domain.ledger.*
import com.yohaku.expensetracker.domain.model.*
import org.junit.Test
import org.junit.Assert.*

class ExchangeTest {
    @Test fun conversionUsesTargetPrecisionAndActualAmountsDetermineRate() {
        val original=Money.parse("100",Currency.USD)
        assertEquals(Money(Currency.JPY,15000),original.convert(Currency.JPY,"150".toBigDecimal()))
        assertEquals(Money(Currency.CNY,71235),original.convert(Currency.CNY,"7.12345".toBigDecimal()))
        assertEquals("148",exchangeRate(original,Money(Currency.JPY,14800)).toPlainString())
        assertEquals(Money(Currency.JPY,2),Money.parse("0.01",Currency.USD).convert(Currency.JPY,"150".toBigDecimal()))
    }
}
