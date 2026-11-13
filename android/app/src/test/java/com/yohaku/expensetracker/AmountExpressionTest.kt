package com.yohaku.expensetracker

import com.yohaku.expensetracker.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class AmountExpressionTest {
    @Test fun precedenceParenthesesAndFullWidthSymbols() {
        assertEquals(14L,AmountExpression.evaluate("2＋3×4",Currency.JPY).minor)
        assertEquals(15L,AmountExpression.evaluate("（8－2）÷2 + -(-3)*4",Currency.JPY).minor)
        assertEquals(6L,AmountExpression.evaluate("2x3",Currency.JPY).minor)
    }
    @Test fun roundsOnceAtCurrencyPrecision() {
        assertEquals(3L,AmountExpression.evaluate("5/2",Currency.JPY).minor)
        assertEquals(267L,AmountExpression.evaluate("8/3",Currency.USD).minor)
        assertEquals(100L,AmountExpression.evaluate("1/3*3",Currency.USD).minor)
    }
    @Test fun rejectsInvalidAndNonpositiveResults() {
        listOf("(1+2","1/0","2-2","1+","abc").forEach{assertTrue(it,runCatching{AmountExpression.evaluate(it,Currency.JPY)}.isFailure)}
    }
    @Test fun resolvesTheDisplayedAccountFieldOnly() {
        assertEquals("12",EntryDraft(amount="3*4").resolveAmountExpression(Currency.JPY).amount)
        val foreign=EntryDraft(amount="10",originalCurrency=Currency.USD,accountAmount="1000+500")
        assertEquals(foreign.copy(accountAmount="1500"),foreign.resolveAmountExpression(Currency.JPY))
    }
}
