package com.yohaku.expensetracker
import com.yohaku.expensetracker.domain.model.*
import org.junit.Assert.*
import org.junit.Test
class MoneyTest {
    @Test fun decimalAmountPreservesMinorUnits() {
        assertEquals(1234L,Money.parse("12.34",Currency.CNY).minor)
        assertEquals(680L,Money.parse("680",Currency.JPY).minor)
        assertEquals("CN¥12.34",Money(Currency.CNY,1234).display())
        assertEquals("US$12.34",Money(Currency.USD,1234).display())
    }
    @Test fun excessPrecisionIsNotSilentlyRounded() {
        assertThrows(ArithmeticException::class.java){Money.parse("1.5",Currency.JPY)}
        assertThrows(ArithmeticException::class.java){Money.parse("1.234",Currency.USD)}
    }
}
