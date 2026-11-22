package com.yohaku.expensetracker.ui.features.command

import com.yohaku.expensetracker.data.command.ObjectEffect
import com.yohaku.expensetracker.data.repository.BookSnapshot
import com.yohaku.expensetracker.domain.model.Currency
import com.yohaku.expensetracker.domain.model.Money
import kotlinx.serialization.json.*

/** Currency references belong to one side of the reviewed step, including its generated IDs. */
internal class CommandMoneyFacts(s:BookSnapshot,effects:List<ObjectEffect>,after:Boolean) {
    val baseCurrency=s.book.currency
    private val accounts=s.accounts.associate{it.id to it.currency}.toMutableMap()
    private val entries=s.events.associate{it.id to it.currency}.toMutableMap()
    private val debts=s.repayments.associate{it.eventId to it.debtAccountId}.toMutableMap()

    init {
        for(effect in effects) {
            val row=(if(after)effect.after else effect.before) as? JsonObject?:continue
            when(effect.type) {
                "account"->row.text("currency")?.let{accounts[effect.key]=it}
                "entry"->row.text("currency")?.let{entries[effect.key]=it}
                "repayment"->row.text("debtAccountId")?.let{debts[effect.key]=it}
            }
        }
    }

    fun rootCurrency(type:String,key:String):String=when(type) {
        "entry"->entries[key]
        "repayment","repaymentAllocation"->debts[key]?.let(accounts::get)
        "account","balance","creditLimit","cashForecast"->accounts[key]
        else->null
    }?:baseCurrency

    fun objectCurrency(row:JsonObject,inherited:String):String =
        row.text("currency")?:row.text("debtAccountId")?.let(accounts::get)
        ?:row.text("eventId")?.let(debts::get)?.let(accounts::get)
        ?:(row["repayment"] as? JsonObject)?.text("debtAccountId")?.let(accounts::get)
        ?:row.text("accountCurrency")?:row.text("accountId")?.let(accounts::get)?:inherited

    /** Mixed-currency fields must never inherit the debt or transaction currency. */
    fun fieldCurrency(row:JsonObject,key:String,unit:String):String?=when {
        key=="baseMinor"||key.endsWith("BaseMinor")->baseCurrency
        key=="sourceFeeMinor"->row.text("sourceCurrency")?:row.text("eventId")?.let(entries::get)
        key=="receivedMinor"&&"fundingAccountId" in row->row.text("fundingCurrency")?:row.text("fundingAccountId")?.let(accounts::get)
        key=="separateFeeMinor"->row.text("separateFeeCurrency")?:(row.text("separateFeeAccountId")?:row.text("fundingAccountId"))?.let(accounts::get)
        key=="accountMinor"||key=="accountDeltaMinor"->row.text("accountCurrency")?:row.text("accountId")?.let(accounts::get)?:unit
        key=="expectedAccountMinor"->row.text("expectedAccountCurrency")?:row.text("accountCurrency")?:unit
        key=="toMinor"->row.text("toCurrency")
        key=="expectedToMinor"->row.text("expectedToCurrency")?:row.text("toCurrency")
        key=="originalMinor"->row.text("originalCurrency")
        else->unit
    }
}

private fun JsonObject.text(key:String)=(get(key) as? JsonPrimitive)?.contentOrNull?.takeIf{it.isNotEmpty()}
internal fun commandSummaryAmount(row:JsonObject?,value:JsonElement?,baseCurrency:String):String? =
    (value as? JsonPrimitive)?.longOrNull?.let { minor ->
        Money(Currency.valueOf(row?.text("currency")?:baseCurrency),minor).display()
    }

internal fun isCommandMoneyField(key:String)=key.endsWith("Minor")||key=="minor"
internal fun commandMoneyDisplay(value:Pair<JsonElement,String?>):String =
    Money(Currency.valueOf(requireNotNull(value.second){"Missing currency for command amount"}),value.first.jsonPrimitive.long).display()

/** Each amount carries its own unit before rendering; no formatter-side debt/base guessing. */
internal fun flattenCommandFacts(value:JsonElement,currencies:CommandMoneyFacts,currency:String,prefix:String=""):Map<String,Pair<JsonElement,String?>> = buildMap {
    if(prefix.isEmpty()&&value==JsonNull)return@buildMap
    if(value is JsonPrimitive&&value.isString&&prefix.substringAfterLast('.').let{it.endsWith("Json")||it in setOf("json","encoded","sourceProof")}&&value.content.trimStart().let{it.startsWith("{")||it.startsWith("[")}) {
        putAll(flattenCommandFacts(com.yohaku.expensetracker.data.command.commandJson.parseToJsonElement(value.content),currencies,currency,prefix))
        return@buildMap
    }
    when(value) {
        is JsonObject->{
            val unit=currencies.objectCurrency(value,currency)
            value.filterKeys{it !in setOf("id","bookId","createdAt","explanation","deletionGroupId","slot","basis","item_id","record_id","receipt_id","row_id","bundleId","checksum","sha256","mediaSession","path","undoGroupId")}.forEach{(key,v)->
                val fieldCurrency=currencies.fieldCurrency(value,key,unit)
                val path=if(prefix.isEmpty())key else "$prefix.$key"
                if(isCommandMoneyField(key)&&v is JsonPrimitive&&v.longOrNull!=null) {
                    put(path,v to fieldCurrency)
                }else putAll(flattenCommandFacts(v,currencies,fieldCurrency?:unit,path))
            }
        }
        is JsonArray->if(value.isEmpty())put(prefix,value to currency)else value.forEachIndexed{i,v->putAll(flattenCommandFacts(v,currencies,currency,if(prefix.isEmpty())"${i+1}"else"$prefix.${i+1}"))}
        else->put(prefix.ifEmpty{"value"},value to currency)
    }
}
