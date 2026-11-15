package com.yohaku.expensetracker

import com.yohaku.expensetracker.domain.currency.ReferenceRateSnapshot
import com.yohaku.expensetracker.domain.ledger.changeEntryOriginalCurrency
import com.yohaku.expensetracker.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class EntryCurrencyTest {
    private val rate=ReferenceRateSnapshot(Currency.USD,Currency.JPY,"150","2026-10-14","2026-10-14T00:00:00Z")
    private val foreign=EntryDraft(id="expense",originalCurrency=Currency.USD,amount="10",accountAmount="1500",
        settlementConfirmed=true,manualRate="150",referenceAccountLinked=true,referenceExpectedAccountMinor=1500,referenceRates=listOf(rate))

    @Test fun selectingCurrentCurrencyPreservesConfirmationAndConversionEvidence() {
        assertSame(foreign,changeEntryOriginalCurrency(foreign,Currency.JPY,Currency.USD))
        val implicit=EntryDraft(amount="1500")
        assertSame(implicit,changeEntryOriginalCurrency(implicit,Currency.JPY,Currency.JPY))
    }

    @Test fun changingCurrencyNeverConfirmsOrRevokesActualSettlement() {
        for(confirmed in listOf(false,true)) {
            val same=changeEntryOriginalCurrency(foreign.copy(settlementConfirmed=confirmed),Currency.JPY,Currency.JPY)
            assertEquals(confirmed,same.settlementConfirmed)
            assertEquals("1500",same.amount);assertEquals("",same.accountAmount)
            assertEquals("",same.manualRate);assertTrue(same.referenceRates.isEmpty())
            assertNull(same.referenceExpectedAccountMinor);assertFalse(same.referenceAccountLinked)
            val changed=changeEntryOriginalCurrency(same,Currency.JPY,Currency.CNY)
            assertEquals(confirmed,changed.settlementConfirmed)
            assertEquals("1500",changed.accountAmount);assertEquals("",changed.amount)
        }
    }
}
