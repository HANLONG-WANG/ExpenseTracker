package com.yohaku.expensetracker

import com.yohaku.expensetracker.data.repository.*

/** Pass the same isolated preferences as the ViewModel/cloud; this transport writes a fake rate. */
internal fun testReferenceRates(store:ReferenceRateStore, clock:java.time.Clock=DeviceClock()) = ReferenceRates(store, ReferenceRateTransport { base, quote, date ->
    """{"base":"${base.name}","quote":"${quote.name}","date":"$date","rate":1}"""
}, clock)
