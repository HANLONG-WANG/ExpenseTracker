package com.yohaku.expensetracker.ui.features

import com.yohaku.expensetracker.data.repository.ArrangementMoneyRow
import com.yohaku.expensetracker.data.db.FinancialPlanEntity
import com.yohaku.expensetracker.data.db.FinancialBillEntity
import com.yohaku.expensetracker.domain.finance.FinancialRuleVersion
import kotlinx.serialization.json.*

/** Read-only display projections. Never use these values as transaction inputs. */
internal object FinancialDisplayFacts {
    /** Presentation only: the caller retains its original instant/string for saving. */
    fun time(instant:java.time.Instant,zone:java.time.ZoneId,language:String):String =
        localTime(instant.atZone(zone).toLocalDateTime(),when{zone.id=="Z"->"UTC";zone is java.time.ZoneOffset->"UTC"+zone.id;else->zone.id},language)

    fun localTime(value:java.time.LocalDateTime,zoneLabel:String,language:String):String =
        value.format(java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm"))+
            (if(language=="ja")"・"else" · ")+zoneLabel

    data class LimitRelationship(val sharing:String?,val total:Long?,val installment:Long?,val revolving:Long?,val combined:Long?,val configured:Boolean)
    fun limitRelationship(config:com.yohaku.expensetracker.data.db.CreditLimitConfigEntity?)=LimitRelationship(
        config?.sharing,config?.totalMinor,config?.installmentMinor.takeIf{config?.sharing=="SEPARATE_CAPS"},
        config?.revolvingMinor.takeIf{config?.sharing=="SEPARATE_CAPS"},config?.combinedMinor.takeIf{config?.sharing=="SHARED_SUBPOOL"},config!=null)

    fun limitRelationship(config:JsonObject?):LimitRelationship {
        val sharing=config?.get("sharing")?.jsonPrimitive?.contentOrNull
        fun value(key:String)=(config?.get(key) as? JsonPrimitive)?.longOrNull
        return LimitRelationship(sharing,value("totalMinor"),value("installmentMinor").takeIf{sharing=="SEPARATE_CAPS"},
            value("revolvingMinor").takeIf{sharing=="SEPARATE_CAPS"},value("combinedMinor").takeIf{sharing=="SHARED_SUBPOOL"},config!=null)
    }

    private val json=Json { encodeDefaults=true }
    data class RuleDifference(val fieldKey:String,val before:JsonElement?,val after:JsonElement?)
    data class RuleChanges(val firstVersion:Boolean,val fields:List<RuleDifference>)

    fun rule(before:FinancialRuleVersion?,after:FinancialRuleVersion):RuleChanges = rule(
        before?.let{json.encodeToJsonElement(FinancialRuleVersion.serializer(),it).jsonObject},
        json.encodeToJsonElement(FinancialRuleVersion.serializer(),after).jsonObject)

    /** Also accepts command step snapshots, so cloud previews never query the live rule version. */
    fun rule(before:JsonObject?,after:JsonObject):RuleChanges {
        val changes=mutableListOf<RuleDifference>()
        fun visit(path:String,a:JsonElement?,b:JsonElement?) {
            if(a==b||(!path.endsWith(".upToMinor")&&(a?:JsonNull)==(b?:JsonNull)))return
            if(path.substringAfterLast('.') in setOf("annualRate","totalRate","feePerHundred")) {
                val old=(a as? JsonPrimitive)?.contentOrNull?.toBigDecimalOrNull()
                val new=(b as? JsonPrimitive)?.contentOrNull?.toBigDecimalOrNull()
                if(old!=null&&new!=null&&old.compareTo(new)==0)return
            }
            when {
                a is JsonObject||b is JsonObject->{
                    val old=a as? JsonObject;val new=b as? JsonObject
                    (old?.keys.orEmpty()+new?.keys.orEmpty()).forEach{key->visit(if(path.isEmpty())key else "$path.$key",old?.get(key),new?.get(key))}
                }
                a is JsonArray||b is JsonArray->{
                    val old=a as? JsonArray;val new=b as? JsonArray
                    repeat(maxOf(old?.size?:0,new?.size?:0)){i->visit("$path.$i",old?.getOrNull(i),new?.getOrNull(i))}
                }
                else->if(!(before==null&&(b==null||b==JsonNull)&&!path.endsWith(".upToMinor")))changes+=RuleDifference(path,a,b)
            }
        }
        val excluded=setOf("id","bookId","accountId")
        (before?.keys.orEmpty()+after.keys).filterNot{it in excluded}.forEach{visit(it,before?.get(it),after[it])}
        return RuleChanges(before==null,changes)
    }

    data class PlanSide(val principalMinor:Long?,val complete:Boolean,val bills:List<Bill>) {
        val totalMinor=if(bills.all{it.totalKnown})bills.sumOf{it.payment.totalMinor}else null
        val feeMinor=if(bills.all{it.totalKnown&&it.payment.feeMinor!=null})bills.sumOf{it.payment.feeMinor!!}else null
    }
    data class Bill(val id:String,val confirmed:Boolean,val payment:ArrangementMoneyRow,val ruleId:String?,val basis:JsonElement?) {
        val totalKnown get()=confirmed||(basis as? JsonObject)?.get("additionalFeeUnknown")?.jsonPrimitive?.booleanOrNull!=true
    }
    data class PlanChanges(val before:PlanSide?,val after:PlanSide) {
        val retainedConfirmed=after.bills.count{next->next.confirmed&&before?.bills?.any{it==next}==true}
        val retainedOther=after.bills.count{next->!next.confirmed&&before?.bills?.any{it==next}==true}
        val withdrawn=before?.bills?.count{old->after.bills.none{it.id==old.id}}?:0
    }
    fun plan(before:FinancialPlanEntity?,after:FinancialPlanEntity,oldBills:List<FinancialBillEntity>,newBills:List<FinancialBillEntity>):PlanChanges {
        fun side(p:FinancialPlanEntity,rows:List<FinancialBillEntity>)=PlanSide(p.principalMinor,p.complete,rows.filter{it.active}.map{
            Bill(it.id,it.confirmed,ArrangementMoneyRow(it.date,it.principalMinor,it.feeMinor,it.totalMinor,it.paymentAccountId),it.ruleVersionId,it.basisJson?.let(json::parseToJsonElement))
        })
        return PlanChanges(before?.let{side(it,oldBills)},side(after,newBills))
    }
    /** Accepts only this step's snapshots, including unchanged bills. No current DB lookup. */
    fun plan(before:JsonObject?,after:JsonObject,oldBills:List<JsonObject>,newBills:List<JsonObject>):PlanChanges {
        fun side(p:JsonObject,rows:List<JsonObject>)=PlanSide(p["principalMinor"]?.jsonPrimitive?.longOrNull,p["complete"]?.jsonPrimitive?.boolean==true,
            rows.filter{it["active"]?.jsonPrimitive?.boolean!=false}.map{bill->
                val confirmed=bill["confirmed"]?.jsonPrimitive?.boolean==true
                val prefix=if(confirmed)"confirmed"else"predicted"
                Bill(bill.getValue("id").jsonPrimitive.content,confirmed,ArrangementMoneyRow(
                    bill.getValue(prefix+"Date").jsonPrimitive.content,bill[prefix+"PrincipalMinor"]?.jsonPrimitive?.longOrNull,
                    bill[prefix+"FeeMinor"]?.jsonPrimitive?.longOrNull,bill.getValue(prefix+"TotalMinor").jsonPrimitive.long,
                    bill["paymentAccountId"]?.jsonPrimitive?.contentOrNull),bill["ruleVersionId"]?.jsonPrimitive?.contentOrNull,bill["basisJson"]?.takeUnless{it==JsonNull})
            })
        return PlanChanges(before?.let{side(it,oldBills)},side(after,newBills))
    }

    /** Explicit user-facing fields; null stays unknown and removed values stay distinct. */
    fun fields(before:JsonObject?,after:JsonObject,keys:List<String>):List<RuleDifference> = keys
        .filter{it in after||before?.containsKey(it)==true}
        .filter{before==null||before[it]!=after[it]}
        .map{RuleDifference(it,before?.get(it),after[it])}
    /** A cap outside the selected relationship is inapplicable, not an unknown independent cap.
     * Dropping it from this projection also records its removal when switching relationships. */
    fun limitChanges(before:JsonObject?,after:JsonObject):List<RuleDifference> {
        fun applicable(row:JsonObject):JsonObject {
            val sharing=row["sharing"]?.jsonPrimitive?.contentOrNull
            return JsonObject(row.filter{(key,value)->when(key) {
                "installmentMinor","revolvingMinor"->sharing=="SEPARATE_CAPS"
                "combinedMinor"->sharing=="SHARED_SUBPOOL"
                "usedInstallmentMinor","usedRevolvingMinor"->sharing in setOf("SEPARATE_CAPS","SHARED_SUBPOOL")||sharing==null&&value!=JsonNull
                else->true
            }})
        }
        return fields(before?.let(::applicable),applicable(after),limitFields).filterNot{before==null&&it.fieldKey=="sharing"}
    }
    val limitFields=listOf("totalMinor","sharing","installmentMinor","revolvingMinor","combinedMinor","usedTotalMinor","usedInstallmentMinor","usedRevolvingMinor","basisDate","effectiveFrom","usageBasis","usageStart","recovery","recoveryDelayDays","zoneId")
    data class PoolScope(val openingMinor:Long,val beforeOpeningMinor:Long?,val sources:Int,val retained:Int)
    fun poolScope(pool:JsonObject,before:JsonObject?=null):PoolScope {
        val facts=pool.getValue("facts").jsonObject
        return PoolScope(pool.getValue("openingMinor").jsonPrimitive.long,before?.get("openingMinor")?.jsonPrimitive?.longOrNull,facts.getValue("sources").jsonArray.size,facts.getValue("retained").jsonArray.size)
    }
    val limitGroupFields=listOf("name","totalMinor","usedMinor","basisDate")
    fun poolRetainedBillIds(plan:JsonObject?):Set<String> = (((plan?.get("termsJson") as? JsonObject)?.get("pool") as? JsonObject)?.get("facts") as? JsonObject)
        ?.get("retained")?.jsonArray.orEmpty().map{it.jsonObject.getValue("bill").jsonObject.getValue("id").jsonPrimitive.content}.toSet()
    val poolFields=listOf("sourceDate","adoptExisting")
    fun reconciliation(before:JsonObject?,after:JsonObject,loanAccount:Boolean):List<RuleDifference> {
        val keys=listOf("effectiveDate","principalMinor")+listOf("openingSourceMinor","manualComplete","note").filter{key->
            listOf(before?.get(key),after[key]).any{value->value!=null&&value!=JsonNull&&(value as? JsonPrimitive)?.contentOrNull?.isNotBlank()==true}
        }+if(loanAccount)listOf("loanLimitDeltaMinor")else emptyList()
        return fields(before,after,keys)
    }

    data class DateSide(val rows:List<ArrangementMoneyRow>) {
        val totalMinor=rows.sumOf{it.totalMinor}
        val principalMinor=if(rows.all{it.principalMinor!=null})rows.sumOf{it.principalMinor!!}else null
        val feeMinor=if(rows.all{it.feeMinor!=null})rows.sumOf{it.feeMinor!!}else null
    }
    data class DateComparison(val date:String,val before:DateSide?,val after:DateSide?,val changed:Boolean) {
        // Difference of listed payments only. An absent date is never a claim about all debt.
        val deltaMinor=(after?.totalMinor?:0)-(before?.totalMinor?:0)
    }
    fun dates(before:List<ArrangementMoneyRow>,after:List<ArrangementMoneyRow>):List<DateComparison> {
        val old=before.groupBy{it.date};val new=after.groupBy{it.date}
        return (old.keys+new.keys).sorted().map{date->
            val a=old[date];val b=new[date]
            DateComparison(date,a?.let(::DateSide),b?.let(::DateSide),a?.groupingBy{it}?.eachCount()!=b?.groupingBy{it}?.eachCount())
        }
    }
}
