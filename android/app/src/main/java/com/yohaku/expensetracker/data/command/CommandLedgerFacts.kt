package com.yohaku.expensetracker.data.command

import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneOffset

/** Audit instants are not user facts. Trash dates retain their recovery-day meaning; the domain
 * rechecks the exact expiry timestamp on every execution. Deletion groups remain mapped identities. */
internal fun commandEventFacts(value:JsonElement):JsonObject=commandLedgerRow("events",value.jsonObject)
internal fun commandLedgerRow(table:String,value:JsonObject):JsonObject {
    val audit=setOf("createdAt","timestamp")+if(table in setOf("events","financial_plans","financial_bills","treats"))setOf("revision")else emptySet()
    val booleans=setOf("active","archived","initialized","favorite","historicalIncluded","confirmed","fromConfirmed","toConfirmed","complete","estimated")
    return JsonObject(value.filterKeys{it !in audit}.mapValues{(key,v)->
        when {
            key in setOf("deletedAt","invalidatedAt")&&v!=JsonNull->JsonPrimitive(Instant.ofEpochMilli(v.jsonPrimitive.long).atZone(ZoneOffset.UTC).toLocalDate().toString())
            key in booleans&&v is JsonPrimitive&&!v.isString&&v.longOrNull!=null->JsonPrimitive(v.long!=0L)
            (key.endsWith("Json")||key=="json")&&v is JsonPrimitive&&v.isString->commandJson.parseToJsonElement(v.content)
            else->v
        }
    })
}
internal fun commandTableType(table:String)=when(table) {
    "books"->"book";"accounts"->"account";"categories"->"category";"events"->"entry";"merchants"->"merchant";"merchant_locations"->"merchantLocation";"friends"->"friend";"entry_templates"->"template"
    "financial_rules"->"rule";"financial_plans"->"prediction";"financial_bills"->"bill";"budgets"->"budgetSetting"
    else->table
}
internal fun CommandRun.registerLedgerRows(step:String,rows:List<UndoRowChange>) {
    val order=listOf("books","accounts","categories","merchants","merchant_locations","friends","events","entry_templates","financial_rules","financial_plans","financial_bills")
    rows.filter{it.before==null&&!it.removed&&it.table!="revisions"}.sortedBy{order.indexOf(it.table).let{i->if(i<0)100 else i}}.forEach{row->
        row.after["id"]?.jsonPrimitive?.contentOrNull?.let{id->
            val facts=commandLedgerRow(row.table,row.after).filterKeys{it!="id"}
            generated(id,step,commandTableType(row.table),digest(semantic(JsonObject(facts))))
        }
    }
    rows.filter{it.table=="events"}.mapNotNull{it.after["deletionGroupId"]?.jsonPrimitive?.contentOrNull}.distinct().forEachIndexed{i,id->if(rows.none{it.before?.get("deletionGroupId")==JsonPrimitive(id)})generated(id,step,"trashGroup",i.toString())}
}
internal fun CommandRun.ledgerRowEffects(rows:List<UndoRowChange>):List<ObjectEffect> = rows.filter{it.table !in setOf("revisions","financial_plan_revisions")}.map { row->
    val key=row.after["id"]?.jsonPrimitive?.contentOrNull?:row.after["eventId"]?.jsonPrimitive?.contentOrNull
        ?:canonical(semantic(row.key)).toString()
    ObjectEffect(commandTableType(row.table),semantic(JsonPrimitive(key)).jsonPrimitive.content,
        row.before?.let{semantic(commandLedgerRow(row.table,it))}?:JsonNull,
        if(row.removed)JsonNull else semantic(commandLedgerRow(row.table,row.after)))
}.sortedWith(compareBy<ObjectEffect>{it.type}.thenBy{it.key})
internal fun CommandRun.finishLedgerEffect(step:CommandStep,effect:StepEffect,rows:List<UndoRowChange>,basis:List<UndoRowChange> = emptyList()):StepEffect {
    registerLedgerRows(step.id,rows)
    val extra=ledgerRowEffects(rows+basis).distinctBy{it.type to it.key}
    val normalized=commandJson.decodeFromJsonElement<StepEffect>(semantic(encoded(effect)))
    val keys=normalized.objects.map{it.type to it.key}.toSet()
    val objects=(normalized.objects+extra.filter{it.type to it.key !in keys}).sortedWith(compareBy<ObjectEffect>{it.type}.thenBy{it.key})
    val risks=commandRisks(objects)
    val information=requirement(step.id,ConfirmationKind.INFORMATION,encoded(objects),"此步骤为方案中的中间状态；整批一起提交。","このステップは途中の状態です。全体をまとめて保存します。")
    val primary=ledgerRowEffects(rows).filter{it.type in setOf("book","category","merchant","merchantLocation","friend","template")}
    return normalized.copy(targets=normalized.targets.ifEmpty{primary.ifEmpty{extra}.map{it.key}.distinct()},objects=objects,requirements=listOf(information)+normalized.requirements.filter{it.kind==ConfirmationKind.USER_FACT}+commandRiskRequirements(step.id,objects,risks),risks=risks)
}
