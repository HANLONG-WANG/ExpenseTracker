package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.domain.currency.*
import com.yohaku.expensetracker.domain.model.Currency
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.*

interface ReferenceRateStore {
    val automaticReferenceRates:Flow<Boolean>
    suspend fun referenceRateCache():ReferenceRateCache
    suspend fun saveReferenceRateCache(cache:ReferenceRateCache)
    suspend fun automaticReferenceRatesEnabled():Boolean
    suspend fun setAutomaticReferenceRates(enabled:Boolean)
}
fun interface ReferenceRateTransport { suspend fun fetch(base:Currency,quote:Currency,date:String):String }
class ReferenceRateHttpException(val status:Int):IOException("HTTP $status")
class FrankfurterTransport:ReferenceRateTransport {
    override suspend fun fetch(base:Currency,quote:Currency,date:String):String=withContext(Dispatchers.IO) {
        // These are the only outbound parameters. No book/account/event information is accepted.
        val connection=URL("$REFERENCE_RATE_API/rate/${base.name.lowercase()}/${quote.name.lowercase()}?date=$date").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout=10000;connection.readTimeout=12000
            connection.setRequestProperty("Accept","application/json")
            if(connection.responseCode!=200)throw ReferenceRateHttpException(connection.responseCode)
            connection.inputStream.bufferedReader(Charsets.UTF_8).use{it.readText()}
        }finally{connection.disconnect()}
    }
}
class ReferenceRates(val store:ReferenceRateStore,private val transport:ReferenceRateTransport=FrankfurterTransport(),val clock:Clock=DeviceClock()) {
    private val mutex=Mutex()
    val cacheRevision=kotlinx.coroutines.flow.MutableStateFlow(0L)
    val automatic=store.automaticReferenceRates
    suspend fun lookup(base:Currency,quote:Currency,requested:LocalDate=LocalDate.now(clock),refresh:Boolean=false):ReferenceRateResult=mutex.withLock {
        require(base!=quote)
        val today=LocalDate.now(clock);val queryDate=minOf(requested,today)
        val enabled=store.automaticReferenceRatesEnabled()
        var cache=store.referenceRateCache()
        fun result(failure:String?=null):ReferenceRateResult {
            val latest=cache.rates.filter{it.base==base&&it.quote==quote&&LocalDate.parse(it.date)<=queryDate}.maxWithOrNull(compareBy<ReferenceRateSnapshot>{it.date}.thenBy{it.fetchedAt})
            return ReferenceRateResult(requested.toString(),latest,failure,enabled)
        }
        val attempt=cache.attempts.firstOrNull{it.base==base&&it.quote==quote&&it.requestedDate==queryDate.toString()}
        if(!refresh&&(!enabled||attempt?.attemptedOn==today.toString()))return@withLock result(if(!enabled)"AUTO_DISABLED"else attempt?.failure)
        var failure:String?=null
        val value=try {
            val row=Json.parseToJsonElement(transport.fetch(base,quote,queryDate.toString())).jsonObject
            require(row.getValue("base").jsonPrimitive.content==base.name&&row.getValue("quote").jsonPrimitive.content==quote.name)
            val date=LocalDate.parse(row.getValue("date").jsonPrimitive.content)
            val rate=row.getValue("rate").jsonPrimitive.content.toBigDecimal()
            require(date<=queryDate&&rate.signum()>0)
            ReferenceRateSnapshot(base,quote,rate.toPlainString(),date.toString(),clock.instant().toString())
        }catch(e:IOException) {failure=if(e is ReferenceRateHttpException)"HTTP_${e.status}"else"NETWORK";null}
        catch(e:IllegalArgumentException){failure="INVALID_RESPONSE";null}
        catch(e:NoSuchElementException){failure="INVALID_RESPONSE";null}
        catch(e:DateTimeException){failure="INVALID_RESPONSE";null}
        cache=cache.copy(rates=if(value==null)cache.rates else cache.rates.filterNot{it.base==base&&it.quote==quote&&it.date==value.date}+value,
            attempts=cache.attempts.filterNot{it.base==base&&it.quote==quote&&it.requestedDate==queryDate.toString()}+ReferenceRateAttempt(base,quote,queryDate.toString(),today.toString(),failure))
        store.saveReferenceRateCache(cache);cacheRevision.value+=1;result(failure)
    }
}

/** Query only public currency pairs needed by unresolved future amounts, outside Room transactions. */
suspend fun cashReferenceRates(s:BookSnapshot,f:com.yohaku.expensetracker.data.db.FinancialGraph,service:ReferenceRates):List<ReferenceRateSnapshot> {
    val pairs=mutableSetOf<Pair<Currency,Currency>>()
    fun pair(from:String,to:String){if(from!=to)pairs+=Currency.valueOf(from) to Currency.valueOf(to)}
    for(b in f.bills.filter{it.active&&s.billOutstanding(it).totalMinor>0}) {
        val plan=f.plans.firstOrNull{it.id==b.planId&&it.active}?:continue
        s.accounts.firstOrNull{it.id==b.paymentAccountId}?.let{pair(plan.currency,it.currency)}
        if(s.billOutstanding(b).feeMinor?.let{it>0}==true)pair(plan.currency,s.book.currency)
    }
    for(e in s.expectedIncomes.filter{it.status=="PENDING"&&it.accountMinor==null})s.accounts.firstOrNull{it.id==e.accountId}?.let{pair(e.currency,it.currency)}
    for(r in s.recurrences.filter{it.active&&it.baseMinor==null&&it.amountMinor!=null})pair(r.currency,s.book.currency)
    return pairs.mapNotNull{(from,to)->service.lookup(from,to).rate}
}
