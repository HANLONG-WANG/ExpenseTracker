package com.yohaku.expensetracker.data.command

import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.AccountType
import kotlinx.serialization.json.*
import java.time.LocalDate

/** Equality is over typed facts, never rendered prose. Timestamps/revisions are handled by each
 * projection below; in particular repayment/refund fact revisions are deliberately retained. */
object CommandConfirmationComparator {
    fun same(a:ConfirmationModel,b:ConfirmationModel):Boolean=firstDifference(a,b)==null
    /** Paths only: no financial values or assistant prose enter debug logs. */
    fun firstDifference(a:ConfirmationModel,b:ConfirmationModel):String?=difference(semanticModel(a),semanticModel(b),"$")
    private fun difference(a:JsonElement?,b:JsonElement?,path:String):String? {
        if(a==b)return null
        if(a is JsonObject&&b is JsonObject) {
            for(key in (a.keys+b.keys))difference(a[key],b[key],"$path.$key")?.let{return it}
        }else if(a is JsonArray&&b is JsonArray) {
            for(i in 0 until maxOf(a.size,b.size))difference(a.getOrNull(i),b.getOrNull(i),"$path[$i]")?.let{return it}
        }
        return path
    }
    private fun semanticModel(m:ConfirmationModel)=encoded(m).jsonObject.let{root->JsonObject(root+mapOf(
        "steps" to JsonArray(m.steps.map{s->encoded(s).jsonObject.let{value->JsonObject(value+("requirements" to JsonArray(s.requirements.map{r->
            buildJsonObject{put("stepId",r.stepId);put("kind",r.kind.name);put("conditionDigest",r.conditionDigest)}
        }))+("undo" to buildJsonObject{put("available",s.undo.available)}))}}))) }
}

internal class CommandIdentityMap {
    private val values=mutableMapOf<String,String>()
    fun register(id:String,step:String,role:String,businessKey:String=""){values[id]="@$step/$role/$businessKey"}
    fun name(id:String)=values[id]?:id
    fun actualIds():Map<String,String> = values.entries.associate{it.value to it.key}
    fun normalize(value:JsonElement):JsonElement=when(value) {
        is JsonObject->JsonObject(value.mapKeys{name(it.key)}.mapValues{normalize(it.value)})
        is JsonArray->JsonArray(value.map(::normalize))
        is JsonPrimitive->if(value.isString)values[value.content]?.let(::JsonPrimitive)?:value else value
        else->value
    }
}
internal fun commandObjectFacts(value:JsonElement,ignore:Set<String> = emptySet(),jsonFields:Set<String> = emptySet()):JsonObject =
    JsonObject(value.jsonObject.filterKeys{it !in ignore}.mapValues{(key,v)->
        if((key.endsWith("Json")||key in jsonFields)&&v is JsonPrimitive&&v.isString)commandJson.parseToJsonElement(v.content)else v
    })

/** FinancialRuleEntity stores its definition in lowercase `json`, unlike termsJson/basisJson. */
internal fun commandRuleFacts(value:JsonElement)=commandObjectFacts(value,setOf("createdAt"),setOf("json"))

internal class CommandRun(val ledger:LedgerRepository,val bookId:String,val context:ExecutionContext,val undoRecord:UndoRecord?=null) {
    private val outputs=mutableMapOf<Pair<String,String>,String>()
    private val identities=CommandIdentityMap()
    private var cachedState:FinancialReviewState?=null
    suspend fun state(refresh:Boolean=false):FinancialReviewState {
        if(refresh||cachedState==null)cachedState=ledger.financialReviewState(bookId)
        return requireNotNull(cachedState)
    }
    fun generated(id:String,step:String,role:String,key:String="") { identities.register(id,step,role,key) }
    fun output(step:String,role:String,id:String,new:Boolean) {outputs[step to role]=id;if(new)generated(id,step,role)}
    fun resolve(value:JsonElement):String=if(value is JsonPrimitive) value.content else {
        val ref=commandJson.decodeFromJsonElement<CommandRef>(value)
        outputs[ref.ref to ref.output]?:error("引用步骤没有可用输出，请补充资料 / 参照先の出力がありません。情報を補ってください")
    }
    fun semantic(value:JsonElement)=identities.normalize(value)
    fun executionRecord()=CommandExecutionRecord(outputs.entries.groupBy{it.key.first}.mapValues{(_,entries)->entries.associate{it.key.second to it.value}},identities.actualIds())
    private fun row(value:JsonElement,ignore:Set<String> = emptySet()):JsonObject=commandObjectFacts(value,ignore)
    private fun project(state:FinancialReviewState,accounts:Set<String>,entries:Set<String>,categories:Set<String>,months:Set<String>):Map<Pair<String,String>,JsonElement> {
        val s=state.ledger;val f=state.finance;val result=linkedMapOf<Pair<String,String>,JsonElement>()
        fun add(type:String,id:String,value:JsonElement){result[type to id]=value}
        val paymentAccounts=f.bills.filter{bill->f.plans.any{it.id==bill.planId&&it.accountId in accounts}}.mapNotNull{it.paymentAccountId}.toSet()
        val allAccounts=accounts+paymentAccounts
        for(a in s.accounts.filter{it.id in allAccounts}) {
            add("account",a.id,encoded(a))
            add("balance",a.id,buildJsonObject{
                put("currency",a.currency);put("ledgerMinor",s.balance(a));put("asOfMinor",s.cashBalanceAt(a,context.asOfDate));put("debt",AccountType.valueOf(a.type).debt)
                put("negative",!AccountType.valueOf(a.type).debt&&s.balance(a)<0)
            })
            if(AccountType.valueOf(a.type).debt) {
                val limit=accountCreditLimit(s,f,state.limits,a.id,context.asOfDate)
                val value=encoded(limit).jsonObject
                add("creditLimit",a.id,JsonObject(value+mapOf("currency" to JsonPrimitive(a.currency),"config" to (limit.config?.let{row(encoded(it),setOf("createdAt"))}?:JsonNull))))
                limit.groupId?.let{groupId->
                    val group=groupCreditLimit(s,f,state.limits,groupId,context.asOfDate)
                    add("creditLimitGroup",groupId,JsonObject(encoded(group).jsonObject+mapOf("currency" to JsonPrimitive(group.group.currency),"group" to row(encoded(group.group),setOf("createdAt")))))
                }
            }
        }
        for(e in s.events.filter{it.id in entries}) {
            // Event revision is an audit counter. Prediction facts separately carry business revisions.
            add("entry",e.id,row(encoded(e),setOf("revision")))
            add("entryDetails",e.id,encoded(s.entryDetails(e.id)))
            add("entryCategory",e.id,s.categories.firstOrNull{it.id==e.categoryId}?.let{encoded(it)}?:JsonNull)
        }
        if(entries.isNotEmpty()) {
            // Saved time drives remembered defaults: compare its ordering, not millisecond values.
            val order=s.events.filter{it.id in entries}.sortedWith(compareBy<com.yohaku.expensetracker.data.db.EventEntity>{s.eventSaveTimes[it.id]?:it.createdAt}.thenBy{it.createdAt}.thenBy{it.id}).map{it.id}
            add("saveOrder",entries.sorted().joinToString(","),encoded(order))
            for(kind in s.events.filter{it.id in entries}.map{it.kind}.distinct()) {
                val last=s.lastSavedEntry{it.kind==kind}
                add("entryDefaults",kind,buildJsonObject{put("eventId",last?.id);put("accountId",last?.accountId);put("categoryId",last?.categoryId);put("cardPaymentChoice",last?.cardPaymentChoice);put("cardPaymentPeriods",last?.cardPaymentPeriods)})
            }
        }
        val categoryIds=categories+s.categories.filter{it.id in categories}.mapNotNull{it.parentId}
        for(c in s.categories.filter{it.id in categoryIds})add("category",c.id,encoded(c))
        for(month in months)for(category in listOf<String?>(null)+categoryIds.sorted()) {
            val p=s.budgetProjection(month,category)
            add("budget","$month:${category.orEmpty()}",buildJsonObject{
                put("scope",category);put("projection",encoded(p));put("remainingMinor",p.remainingMinor);put("availableMinor",p.availableMinor)
                put("overBudget",p.remainingMinor?.let{it<0});put("overAvailable",p.availableMinor?.let{it<0})
            })
        }
        for(rule in f.rules.filter{it.accountId in accounts})add("rule",rule.id,commandRuleFacts(encoded(rule)))
        val plans=f.plans.filter{it.accountId in accounts}
        for(plan in plans) {
            add("prediction",plan.id,row(encoded(plan),setOf("createdAt","revision")))
            add("predictionFacts",plan.id,JsonObject(encoded(predictionFacts(s,f,plan.id,plan.sourceEventId,context.asOfDate)).jsonObject+("currency" to JsonPrimitive(plan.currency))))
        }
        val planIds=plans.map{it.id}.toSet()
        for(bill in f.bills.filter{it.planId in planIds})add("bill",bill.id,JsonObject(row(encoded(bill),setOf("revision","confirmedAt"))+mapOf(
            "confirmed" to JsonPrimitive(bill.confirmed),"currency" to JsonPrimitive(plans.first{it.id==bill.planId}.currency))))
        val stale=plans.filter{ledger.financialPredictionStale(s,f,it)}.map{it.id}.toSet()
        val cash=s.cashTimeline(f,LocalDate.parse(context.asOfDate),LocalDate.parse(context.throughDate),stale,context.referenceRates)
        for(a in s.accounts.filter{it.id in allAccounts&&!AccountType.valueOf(it.type).debt}) {
            // Timeline item IDs contain generated IDs; sourceId + kind + date are the business key.
            val steps=cash.steps.filter{it.item.accountId==a.id}.map{step->
                val value=encoded(step).jsonObject
                JsonObject(value+("item" to JsonObject(value.getValue("item").jsonObject.filterKeys{it!="id"}+("accountCurrency" to JsonPrimitive(a.currency)))))
            }
            add("cashForecast",a.id,buildJsonObject{put("accountCurrency",a.currency);put("periods",JsonArray(steps))})
        }
        val pace=s.budgetPace(LocalDate.parse(context.asOfDate),f,context.referenceRates)
        add("budgetForecast",context.asOfDate.take(7),buildJsonObject{put("currency",s.book.currency);put("pace",encoded(pace));put("projectedMinor",pace.projectedMinor);put("projectedOverMinor",pace.projectedOverMinor);put("lowConfidence",pace.lowConfidence)})
        return result
    }
    fun effect(step:CommandStep,before:FinancialReviewState,after:FinancialReviewState,accounts:Set<String>,entries:Set<String> = emptySet(),categories:Set<String> = emptySet(),months:Set<String> = emptySet(),unknown:List<String> = emptyList()):StepEffect {
        val relatedPayments=(before.finance.bills+after.finance.bills).filter{bill->(before.finance.plans+after.finance.plans).any{it.id==bill.planId&&it.accountId in accounts}}.mapNotNull{it.paymentAccountId}
        val scope=accounts+relatedPayments
        val beforeValues=project(before,scope,entries,categories,months);val afterValues=project(after,scope,entries,categories,months)
        val objects=(beforeValues.keys+afterValues.keys).map{key->ObjectEffect(key.first,key.second,beforeValues[key]?:JsonNull,afterValues[key]?:JsonNull)}
        val normalized=commandJson.decodeFromJsonElement<List<ObjectEffect>>(semantic(encoded(objects))).sortedWith(compareBy<ObjectEffect>{it.type}.thenBy{it.key})
        val objectsWithUnknown=normalized+if(unknown.isEmpty())emptyList()else listOf(ObjectEffect("unknownRuleFields",step.id,JsonNull,encoded(unknown)))
        val risks=commandRisks(objectsWithUnknown)
        val requirements=listOf(requirement(step.id,ConfirmationKind.INFORMATION,encoded(objectsWithUnknown),
            "此步骤为方案中的中间状态；整批一起提交。","このステップは途中の状態です。全体をまとめて保存します。"))+
            commandRiskRequirements(step.id,objectsWithUnknown,risks)
        return StepEffect(step.id,step.command,(if(entries.isNotEmpty())entries else accounts).map{identities.name(it)}.sorted(),
            objectsWithUnknown,requirements,risks=risks)
    }
}

internal fun netEffects(steps:List<StepEffect>):List<ObjectEffect> {
    val values=linkedMapOf<Pair<String,String>,ObjectEffect>()
    for(effect in steps.flatMap{it.objects}) {
        val key=effect.type to effect.key;val old=values[key]
        values[key]=if(old==null)effect else effect.copy(before=old.before)
    }
    return values.values.toList()
}
