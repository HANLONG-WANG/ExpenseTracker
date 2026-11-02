package com.yohaku.expensetracker.data.command

import com.yohaku.expensetracker.domain.model.Currency
import com.yohaku.expensetracker.domain.model.Money
import kotlinx.serialization.json.*

/** Risk notices describe concrete states. Only worsening actual negative cash requires the same
 * separate acceptance as the entry editor; forecasts, limits and unknowns are notices. */
internal fun commandRisks(objects:List<ObjectEffect>):List<CommandRisk> = buildList {
    for(effect in objects) {
        val after=effect.after as? JsonObject
        val before=effect.before as? JsonObject
        val currency=after?.get("currency")?.jsonPrimitive?.contentOrNull
        when(effect.type) {
            "balance"->if(after?.get("debt")?.jsonPrimitive?.booleanOrNull==false) {
                val amount=after["ledgerMinor"]?.jsonPrimitive?.longOrNull
                val previous=before?.get("ledgerMinor")?.jsonPrimitive?.longOrNull?:0L
                if(amount!=null&&amount<0)add(CommandRisk("NEGATIVE_BALANCE",effect.key,currency,amount,
                    requiresConfirmation=amount<previous))
            }
            "creditLimit","creditLimitGroup"->{
                for(scope in listOf("total","installment","revolving")) {
                    val excess=(after?.get(scope) as? JsonObject)?.get("excessMinor")?.jsonPrimitive?.longOrNull
                    if(excess!=null&&excess>0)add(CommandRisk("LIMIT_EXCESS",effect.key,currency,excess,detail=scope))
                }
                after?.get("issues")?.jsonArray.orEmpty().forEach{add(CommandRisk("LIMIT_ISSUE",effect.key,detail=it.jsonPrimitive.content))}
            }
            "budget"->{
                val remaining=after?.get("remainingMinor")?.jsonPrimitive?.longOrNull
                val unit=(after?.get("projection") as? JsonObject)?.get("currency")?.jsonPrimitive?.contentOrNull
                if(remaining!=null&&remaining<0)add(CommandRisk("BUDGET_EXCESS",effect.key,unit,Math.negateExact(remaining)))
            }
            "budgetForecast"->{
                val excess=after?.get("projectedOverMinor")?.jsonPrimitive?.longOrNull
                if(excess!=null&&excess>0)add(CommandRisk("BUDGET_FORECAST_EXCESS",effect.key,currency,excess))
            }
            "cashForecast"->{
                val unit=after?.get("accountCurrency")?.jsonPrimitive?.contentOrNull
                val periods=after?.get("periods")?.jsonArray.orEmpty()
                val shortage=periods.firstOrNull{(it.jsonObject["baselineAfterMinor"]?.jsonPrimitive?.longOrNull?:0L)<0}?.jsonObject
                if(shortage!=null)add(CommandRisk("CASH_SHORTAGE",effect.key,unit,shortage.getValue("baselineAfterMinor").jsonPrimitive.long,
                    shortage.getValue("item").jsonObject.getValue("date").jsonPrimitive.content))
                periods.mapNotNull{it.jsonObject["item"] as? JsonObject}.forEach{item->
                    item["issue"]?.jsonPrimitive?.contentOrNull?.let{add(CommandRisk("CASH_ISSUE",effect.key,date=item["date"]?.jsonPrimitive?.contentOrNull,detail=it))}
                }
            }
            "unknownRuleFields"->effect.after.jsonArray.forEach{add(CommandRisk("RULE_INCOMPLETE",effect.key,detail=it.jsonPrimitive.content))}
        }
    }
}.distinct()

internal fun commandRiskRequirements(stepId:String,objects:List<ObjectEffect>,risks:List<CommandRisk>):List<ConfirmationRequirement> =
    risks.filter{it.requiresConfirmation}.map{risk->
        val account=objects.first{it.type=="account"&&it.key==risk.subject}.after.jsonObject.getValue("name").jsonPrimitive.content
        val amount=Money(Currency.valueOf(requireNotNull(risk.currency)),requireNotNull(risk.minor)).display()
        requirement(stepId,ConfirmationKind.RISK,encoded(risk),
            "账户「$account」的余额将降至 $amount，我确认继续。","口座「$account」の残高が $amount に下がることを確認し、続行します。")
    }

/** This controls only the short UI summary, never execution equality or the full detail list. */
internal fun commandSummaryChanged(effect:ObjectEffect):Boolean {
    fun value(raw:JsonElement):JsonElement {
        val row=raw as? JsonObject
        return when(effect.type) {
            "creditLimit"->(row?.get("total") as? JsonObject)?.get("availableMinor")?:JsonNull
            "budget"->row?.get("remainingMinor")?:JsonNull
            "balance"->row?.get("ledgerMinor")?:JsonNull
            "bill"->if(row==null)JsonNull else buildJsonObject {
                val confirmed=row["confirmed"]?.jsonPrimitive?.booleanOrNull==true
                put("confirmed",confirmed)
                put("date",row[if(confirmed)"confirmedDate"else"predictedDate"]?:JsonNull)
                put("total",row[if(confirmed)"confirmedTotalMinor"else"predictedTotalMinor"]?:JsonNull)
            }
            else->raw
        }
    }
    return value(effect.before)!=value(effect.after)
}
