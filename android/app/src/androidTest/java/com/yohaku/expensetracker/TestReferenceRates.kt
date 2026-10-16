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
