package com.yohaku.expensetracker.data.command

import com.yohaku.expensetracker.data.repository.normalizedMerchantName
import kotlinx.serialization.json.*

/** Compare complete batch states, not intermediate restore order. Evidence contains only the
 * violated relationship, so unrelated edits cannot turn an existing legacy defect into a new one. */
internal fun newMerchantUndoConflicts(before:Map<String,List<JsonObject>>,after:Map<String,List<JsonObject>>):List<UndoConflict> {
    data class Violation(val table:String,val key:String,val reason:String,val evidence:List<String>)
    fun violations(state:Map<String,List<JsonObject>>):Set<Violation> = buildSet {
        fun rows(table:String)=state.getValue(table)
        fun JsonObject.text(field:String)=get(field)?.jsonPrimitive?.contentOrNull
        val merchants=rows("merchants").associateBy{it.text("id")!!}
        val groups=merchants.values.groupBy{normalizedMerchantName(it.text("name")!!)}
        val events=rows("events").filter{it["purgedAt"]==JsonNull}.associateBy{it.text("id")!!}
        val links=rows("event_merchants").associate{it.text("eventId")!! to it.text("merchantId")!!}
        // A duplicate is a pair of identities in a normalized name group. Adding a third
        // merchant or moving the pair to another name is new; an untouched legacy pair is not.
        for((name,group) in groups) {
            val ids=group.map{it.text("id")!!}.sorted()
            for(i in ids.indices)for(j in i+1 until ids.size)
                add(Violation("merchants",ids[i],"撤销会新增规范化重名商户 / 取消により同名の店舗が重複します",listOf(name,ids[i],ids[j])))
        }
        for((id,event) in events) {
            val name=event.text("merchant")!!;val merchantId=links[id]
            if(event.text("kind")=="EXPENSE"&&merchantId==null) {
                val match=groups[normalizedMerchantName(name)]?.singleOrNull()
                if(match!=null)add(Violation("events",id,"撤销会留下未关联的唯一同名支出（含停用商户） / 取消により同名店舗が一意の支出が未関連になります（無効の店舗を含む）",listOf(name,match.text("id")!!)))
            }
            val merchant=merchants[merchantId]
            if(event.text("kind") in setOf("EXPENSE","INCOME")&&merchant!=null&&name!=merchant.text("name"))
                add(Violation("events",id,"撤销会使记录名称与关联商户不一致 / 取消により記録名と関連店舗名が一致しなくなります",listOf(merchantId!!,name,merchant.text("name")!!)))
        }
        for(settlement in rows("settlements")) {
            val refund=events[settlement.text("eventId")]?:continue
            if(refund.text("kind")!="REFUND")continue
            val original=events[settlement.text("originalEventId")]?:continue
            if(refund.text("merchant")!=original.text("merchant"))add(Violation("events",refund.text("id")!!,
                "撤销会使退款名称与原消费不一致 / 取消により返金名と元の支出名が一致しなくなります",
                listOf(original.text("id")!!,refund.text("merchant")!!,original.text("merchant")!!)))
        }
        for(table in listOf("event_merchants","entry_templates","recurrences","merchant_locations"))for(row in rows(table)) {
            val merchantId=row.text("merchantId")?:continue
            if(merchantId !in merchants)add(Violation(table,row.text("id")?:row.text("eventId")!!,
                "撤销会恢复失效的商户引用 / 取消により無効な店舗参照が復元されます",listOf(merchantId)))
        }
    }
    return (violations(after)-violations(before)).map {
        UndoConflict(it.table,it.key,it.reason,JsonPrimitive("撤销前未发生此问题 / 取消前はこの問題なし"),
            JsonPrimitive("撤销后：${it.evidence.joinToString(" · ")} / 取消後"))
    }
}
