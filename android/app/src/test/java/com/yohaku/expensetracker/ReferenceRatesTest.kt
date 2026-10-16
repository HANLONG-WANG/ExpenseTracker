package com.yohaku.expensetracker

import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.currency.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.domain.ledger.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.io.IOException

class MemoryReferenceRateStore:ReferenceRateStore {
    override val automaticReferenceRates=MutableStateFlow(true)
    var cache=ReferenceRateCache()
    override suspend fun referenceRateCache()=cache
    override suspend fun saveReferenceRateCache(cache:ReferenceRateCache){this.cache=cache}
    override suspend fun automaticReferenceRatesEnabled()=automaticReferenceRates.value
    override suspend fun setAutomaticReferenceRates(enabled:Boolean){automaticReferenceRates.value=enabled}
}
class ReferenceRatesTest {
    @Test fun fixedSourceDailyCachePreviousBusinessDateFailureAndManualRefreshAreDistinct()=runBlocking {
        val store=MemoryReferenceRateStore();var calls=0;var offline=false
        val clock=Clock.fixed(Instant.parse("2026-10-11T00:00:00Z"),ZoneId.of("Asia/Tokyo"))
        val transport=ReferenceRateTransport{base,quote,date->
            calls++;assertEquals(Currency.JPY,base);assertEquals(Currency.CNY,quote)
            if(offline)throw IOException("offline")
            assertEquals("2026-10-11",date)
            """{"date":"2026-10-09","base":"JPY","quote":"CNY","rate":0.04229}"""
        }
        val service=ReferenceRates(store,transport,clock)
        val first=service.lookup(Currency.JPY,Currency.CNY)
        assertTrue(first.previousDate);assertEquals("ECB",first.rate!!.provider);assertEquals(REFERENCE_RATE_API,first.rate!!.api)
        assertEquals(42290L,first.rate!!.convert(Money(Currency.JPY,10000)).minor)
        assertEquals(first,service.lookup(Currency.JPY,Currency.CNY));assertEquals(1,calls)
        // A new service instance still consults the persisted day attempt.
        assertEquals(first,ReferenceRates(store,transport,clock).lookup(Currency.JPY,Currency.CNY));assertEquals(1,calls)
        offline=true
        val failed=service.lookup(Currency.JPY,Currency.CNY,refresh=true)
        assertEquals(first.rate,failed.rate);assertEquals("NETWORK",failed.failure);assertEquals(2,calls)
        service.lookup(Currency.JPY,Currency.CNY);assertEquals(2,calls)
        val historical=service.lookup(Currency.JPY,Currency.CNY,LocalDate.parse("2026-10-08"))
        assertNull(historical.rate) // October 9 cannot fill an earlier historical query.
        store.setAutomaticReferenceRates(false)
        assertFalse(service.lookup(Currency.JPY,Currency.CNY).automatic);assertEquals(3,calls)
        service.lookup(Currency.JPY,Currency.CNY,refresh=true);assertEquals(4,calls)
    }
    @Test fun invalidOrFutureProviderDataIsNotStoredAsAZeroRate()=runBlocking {
        val store=MemoryReferenceRateStore()
        val service=ReferenceRates(store,ReferenceRateTransport{_,_,_->"""{"date":"2026-10-16","base":"JPY","quote":"CNY","rate":0.1}"""},Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"),ZoneOffset.UTC))
        val result=service.lookup(Currency.JPY,Currency.CNY)
        assertNull(result.rate);assertEquals("INVALID_RESPONSE",result.failure);assertTrue(store.cache.rates.isEmpty())
    }
    @Test fun referenceEstimationPreservesActualSidesAndHandlesFeesInBothDirections() {
        val rate=ReferenceRateSnapshot(Currency.JPY,Currency.CNY,"0.04","2026-10-14","2026-10-15T00:00:00Z")
        val input=EntryDraft(kind=EntryKind.TRANSFER,amount="10000",toAmount="",fromConfirmed=true,toConfirmed=false,feeAmount="100",feeCurrency=Currency.JPY,feePosition=TransferFeePosition.INCLUDED_FROM)
        val to=applyReferenceTransfer(input,Currency.JPY,Currency.CNY,rate,"TO")
        val linked=estimateTransfer(to.copy(amount="20000"),Currency.JPY,Currency.CNY,"TO");assertEquals(79600L,linked.referenceExpectedToMinor)
        assertEquals("396.00",to.toAmount);assertEquals("10000",to.amount);assertTrue(to.fromConfirmed);assertFalse(to.toConfirmed);assertEquals(listOf(rate),to.referenceRates)
        val from=applyReferenceTransfer(input.copy(amount="",toAmount="396",fromConfirmed=false,toConfirmed=true),Currency.JPY,Currency.CNY,rate,"FROM")
        assertEquals("10000",from.amount);assertEquals("396",from.toAmount)
        assertThrows(IllegalArgumentException::class.java){applyReferenceTransfer(to.copy(toConfirmed=true),Currency.JPY,Currency.CNY,rate,"TO")}
        assertThrows(IllegalArgumentException::class.java){applyReferenceAccountAmount(EntryDraft(amount="100",originalCurrency=Currency.JPY,settlementConfirmed=true),Currency.CNY,rate)}
        assertEquals("4.00",applyReferenceAccountAmount(EntryDraft(amount="100",originalCurrency=Currency.JPY,settlementConfirmed=false),Currency.CNY,rate).accountAmount)
    }
}
