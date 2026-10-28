package com.yohaku.expensetracker

import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.currency.*
import kotlinx.coroutines.flow.MutableStateFlow

internal class TestReferenceRateStore:ReferenceRateStore {
    override val automaticReferenceRates=MutableStateFlow(true)
    private var cache=ReferenceRateCache()
    override suspend fun referenceRateCache()=cache
    override suspend fun saveReferenceRateCache(cache:ReferenceRateCache){this.cache=cache}
    override suspend fun automaticReferenceRatesEnabled()=automaticReferenceRates.value
    override suspend fun setAutomaticReferenceRates(enabled:Boolean){automaticReferenceRates.value=enabled}
}

/** Ordinary UI tests never contact the public rates service. */
internal fun testReferenceRates(store:ReferenceRateStore, clock:java.time.Clock=DeviceClock()) = ReferenceRates(store, ReferenceRateTransport { base, quote, date ->
    """{"base":"${base.name}","quote":"${quote.name}","date":"$date","rate":1}"""
}, clock)
