package com.yohaku.expensetracker.data.command

import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.Instant

/** Domain serializers remain authoritative. Only typed reference slots and phone-only evidence
 * differ on the wire; cloud input never contains a platformConfirmed/exceptionAccepted boolean. */
private val financeReferenceTypes=mapOf(
    "accountId" to "account","debtAccountId" to "account","sourceAccountId" to "account","fundingAccountId" to "account",
    "paymentAccountId" to "account","separateFeeAccountId" to "account","accountIds" to "account",
    "eventId" to "entry","sourceEventId" to "entry","linkedTransferEventId" to "entry",
    "planId" to "prediction","planIds" to "prediction","poolId" to "prediction","mergePoolId" to "prediction",
    "billId" to "bill","poolBillId" to "bill","contractId" to "contract","allocationId" to "repaymentAllocation",
    "arrangementId" to "arrangement","groupId" to "creditLimitGroup","recoveryId" to "creditLimitRecovery","categoryId" to "category","sourceCategoryId" to "category")
internal fun financeReferences(schema:JsonObject):JsonObject {
    schema["anyOf"]?.jsonArray?.let{return JsonObject(schema+("anyOf" to JsonArray(it.map{child->financeReferences(child.jsonObject)})))}
    val props=schema["properties"] as? JsonObject
    if(props!=null)return patchSchema(schema,props.mapValues{(key,value)->
        val field=value.jsonObject;val type=financeReferenceTypes[key]
        when {
            type==null->financeReferences(field)
            key.endsWith("Ids")->arraySchema(referenceSchema(type))
            field.containsKey("anyOf")->nullableSchema(referenceSchema(type))
            else->referenceSchema(type)
        }
    })
    schema["items"]?.jsonObject?.let{return JsonObject(schema+("items" to financeReferences(it)))}
    return schema
}
internal fun financeInput(schema:JsonObject,input:JsonElement,resolve:(JsonElement)->String):JsonElement {
    if(input==JsonNull)return input
    if(schema["x-reference-type"]!=null)return JsonPrimitive(resolve(input))
    schema["anyOf"]?.jsonArray?.firstOrNull{it.jsonObject["type"]!=JsonPrimitive("null")}?.let{return financeInput(it.jsonObject,input,resolve)}
    if(input is JsonObject) {
        val props=schema["properties"]?.jsonObject.orEmpty()
        return JsonObject(input.mapValues{(k,v)->props[k]?.jsonObject?.let{financeInput(it,v,resolve)}?:v})
    }
    if(input is JsonArray&&schema["items"]!=null)return JsonArray(input.map{financeInput(schema.getValue("items").jsonObject,it,resolve)})
    return input
}

@Serializable internal data class FinanceAllocationResolve(val eventId:String,val billId:String?=null,val contractId:String?=null)
@Serializable internal data class FinanceBillTarget(val billId:String)
@Serializable internal data class FinanceBillResolve(val planId:String,val number:Int)
@Serializable internal data class FinanceBillConfirm(val billId:String,val bill:KnownBillInput)
@Serializable internal data class FinanceContractLink(val contractId:String,val planId:String)
@Serializable internal data class FinanceApplied<T>(val input:T,val appliedAt:String)
@Serializable internal data class FinanceBatchUndo(val undoGroupId:String)
@Serializable internal data class FinanceRecoveryVoid(val recoveryId:String)
@Serializable internal data class FinanceObservation(val scope:String,val availableMinor:Long,val observedAt:String,val accountId:String?=null,val groupId:String?=null)
@Serializable internal data class FinanceDraftCategory(val sourceCategoryId:String,val categoryId:String,val slots:List<String>?=null)
@Serializable internal data class FinancePoolShare(val eventId:String,val minor:Long)
@Serializable internal data class FinancePoolSave(val accountId:String,val name:String,val mode:FinanceMode,val terms:FinancialScheduleTerms,
    val openingMinor:Long,val sourceDate:PoolSourceDate,val paymentAccountId:String?=null,val planId:String?=null,
    val adoptExisting:Boolean=false,val unallocatedShares:List<FinancePoolShare> = emptyList(),val adoptedFees:List<PoolAdoptedFee> = emptyList())

/** Outbound guidance shared by standalone limit configuration and account.save.creditLimit. */
internal const val creditLimitInputGuide="Credit-limit object is a full version: required nullable fields must be present, not omitted. totalMinor is the known total capacity; installmentMinor/revolvingMinor are separate sublimits, combinedMinor is the shared subpool cap, sharing is TOTAL_ONLY/SEPARATE_CAPS/SHARED_SUBPOOL; use null for unspecified caps/sharing, never infer them from the total. usedTotalMinor/usedInstallmentMinor/usedRevolvingMinor are usage at basisDate, not balances computed by the assistant; unknown=null, explicit no debt/no occupancy permits 0 for all three, subject to phone USER_FACT. For current/now statements, effectiveFrom and basisDate may both use task asOfDate, with assumptions in message; basisDate<=effectiveFrom<=phone today. recovery is PAYMENT_DATE/PLATFORM_CONFIRMATION/MANUAL or null unknown; usageBasis is PRINCIPAL_ONLY/PLATFORM_ONLY or null unknown; usageStart is TRANSACTION_DATE/ACCOUNT_POSTING_DATE or null (default); zoneId is explicit; recoveryDelayDays defaults to 0 and only applies to PAYMENT_DATE. Unknown policies are valid saved configuration: automatic available-limit estimates remain unknown unless usageBasis=PRINCIPAL_ONLY and recovery/usageStart are known. They do not block creating the account, saving the stated total limit or saving a billing rule. Do not invent bank-specific recovery/usage policies just to produce a numeric estimate."

private val financeUndoText="Restore complete recorded pre-batch rows, including contracts, allocations, arrangements, drafts and limit history; remove only unchanged execution-owned rows without external references. Any conflict blocks the entire batch."
private val financeCompare=listOf("all before/after domain rows and fixed referenced identities","per-period dates, principal, fees, payment account, rule basis, confirmed/predicted status","source, repayment/refund revisions, contract remaining principal, pool sources and allocations","credit-limit configuration, group revision/members, recovery and observation evidence, absolute available amounts","cash and budget forecast, saved-entry ordering","generated IDs in nested evidence mapped by step/role/business key; audit instants ignored","USER_FACT digest binds the resolved input and complete effect, never display text")
private fun <T> financeAdapter(name:String,serializer:KSerializer<T>,outputs:Map<String,String> = emptyMap(),
    defaults:String,prerequisites:String,description:String,example:String,fact:Boolean=false,omit:Set<String> = emptySet(),
    schemaPatch:Map<String,JsonElement> = emptyMap(),normalizeInput:(suspend (JsonObject,CommandRun)->JsonObject)?=null,execute:suspend (CommandStep,CommandRun,T)->StepEffect):CommandAdapter = object:LedgerAdapter() {
    private val schema=patchSchema(financeReferences(withoutFields(descriptorSchema(serializer.descriptor),omit)),schemaPatch)
    override val capability=CommandCapability(name,1,schema,outputs,defaults,prerequisites,
        listOf(ConfirmationKind.INFORMATION,ConfirmationKind.RISK)+if(fact)listOf(ConfirmationKind.USER_FACT)else emptyList(),financeCompare,
        description,commandJson.parseToJsonElement(example).jsonObject,financeUndoText)
    override suspend fun normalize(input:JsonObject,run:CommandRun)=normalizeInput?.invoke(input,run)?:input
    override fun validate(input:JsonObject) {
        // References are schema-checked before this call; placeholders are only used to decode types.
        commandJson.decodeFromJsonElement(serializer,financeInput(schema,input){if(it is JsonPrimitive)it.content else "typed-reference"})
    }
    override suspend fun execute(step:CommandStep,run:CommandRun):StepEffect = try {
        execute(step,run,commandJson.decodeFromJsonElement(serializer,financeInput(schema,step.input,run::resolve)))
    }catch(incomplete:FinanceCalculationIncomplete) {
        StepEffect(step.id,step.command,emptyList(),emptyList(),missing=incomplete.fields,
            risks=incomplete.fields.map{CommandRisk("PREDICTION_INCOMPLETE",step.id,detail=it)})
    }
}

/** Register all new rows before derived forecasts or fact digests see generated identities. */
internal suspend fun financeEffect(step:CommandStep,run:CommandRun,before:FinancialReviewState,rowsBefore:Map<String,Pair<String,JsonObject>>):StepEffect {
    val after=run.state(refresh=true)
    val changes=CommandUndoJournal(run.ledger,run.bookId).changes(rowsBefore)
    run.registerFinanceIdentities(step.id,before,after)
    run.registerLedgerRows(step.id,changes)
    val accounts=changes.flatMap{row->listOfNotNull(row.after["accountId"]?.jsonPrimitive?.contentOrNull,
        row.after["debtAccountId"]?.jsonPrimitive?.contentOrNull,row.after["sourceAccountId"]?.jsonPrimitive?.contentOrNull,
        row.after["paymentAccountId"]?.jsonPrimitive?.contentOrNull,row.after["fundingAccountId"]?.jsonPrimitive?.contentOrNull)}.toSet()+
        (before.finance.plans+after.finance.plans).filter{p->changes.any{it.after["planId"]==JsonPrimitive(p.id)||it.after["id"]==JsonPrimitive(p.id)||(before.finance.bills+after.finance.bills).any{bill->bill.planId==p.id&&it.after["billId"]==JsonPrimitive(bill.id)}}}.map{it.accountId}+
        (before.limits.members+after.limits.members).filter{m->changes.any{it.after["groupId"]==JsonPrimitive(m.groupId)||it.after["id"]==JsonPrimitive(m.groupId)}}.map{it.accountId}
    val entries=changes.filter{it.table=="events"}.map{it.after.getValue("id").jsonPrimitive.content}.toSet()
    val months=(before.ledger.events+after.ledger.events).filter{it.id in entries}.map{it.date.take(7)}.toSet()+run.context.asOfDate.take(7)
    return run.effect(step,before,after,accounts,entries,months=months)
}
private fun financeRows(run:CommandRun)=CommandUndoJournal(run.ledger,run.bookId).snapshot()
private class FinanceCalculationIncomplete(val fields:List<String>):RuntimeException()
private fun requireReady(value:Calculation<*>) {
    val fields=when(value){is Calculation.Ready->return;is Calculation.Incomplete->value.fields;is Calculation.Unsupported->listOf(value.reason);is Calculation.CannotSettle->listOf("prediction.cannotSettle")}
    throw FinanceCalculationIncomplete(fields)
}
internal fun financeFact(step:CommandStep,run:CommandRun,effect:StepEffect,input:JsonElement,accountId:String?,minor:Long?,date:String,zh:String,ja:String):StepEffect {
    val account=effect.objects.firstOrNull{it.type=="account"&&it.key==run.semantic(JsonPrimitive(accountId.orEmpty())).jsonPrimitive.content}
    val row=(account?.after as? JsonObject)?:account?.before as? JsonObject
    val name=row?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
    val currency=row?.get("currency")?.jsonPrimitive?.contentOrNull
    val money=if(minor!=null&&currency!=null)Money(Currency.valueOf(currency),minor).display()else "金额明细见下方 / 金額内訳は下記"
    val condition=buildJsonObject{put("input",run.semantic(input));put("objects",encoded(effect.objects));put("account",accountId?.let{run.semantic(JsonPrimitive(it))}?:JsonNull);put("minor",minor);put("date",date)}
    return effect.copy(requirements=effect.requirements+requirement(step.id,ConfirmationKind.USER_FACT,condition,
        "我已核对：${name} · ${money} · ${date}。${zh}","確認しました：${name} · ${money} · ${date}。${ja}"))
}

internal fun financeUsageFact(step:CommandStep,run:CommandRun,effect:StepEffect,v:CreditLimitInput):StepEffect {
    if(listOf(v.usedTotalMinor,v.usedInstallmentMinor,v.usedRevolvingMinor).all{it==null})return effect
    val key=run.semantic(JsonPrimitive(v.accountId)).jsonPrimitive.content
    val account=effect.objects.first{it.type=="account"&&it.key==key}.after.jsonObject
    val currency=Currency.valueOf(account.getValue("currency").jsonPrimitive.content)
    fun amount(n:Long?,unknown:String)=n?.let{Money(currency,it).display()}?:unknown
    return financeFact(step,run,effect,encoded(v),v.accountId,v.usedTotalMinor,v.basisDate,
        "我已核对占用基准：总占用 ${amount(v.usedTotalMinor,"未知")}，分期 ${amount(v.usedInstallmentMinor,"未知")}，循环 ${amount(v.usedRevolvingMinor,"未知")}。",
        "使用額基準を確認しました：総使用額 ${amount(v.usedTotalMinor,"不明")}、分割 ${amount(v.usedInstallmentMinor,"不明")}、リボ ${amount(v.usedRevolvingMinor,"不明")}。")
}

internal fun financeCommandAdapters():List<CommandAdapter> = listOf(
    financeAdapter("financialBill.resolve",FinanceBillResolve.serializer(),mapOf("bill" to "bill"),
        "planId and positive number required; selects exactly one existing period, never creates a bill.","Plan and active bill number belong to this book.","Resolve a bill by plan and period number, including a plan created by a prior step.","""{"planId":{"ref":"schedule","output":"prediction"},"number":1}""") {step,run,v->
        val before=run.state();require(v.number>0)
        val bill=before.finance.bills.single{it.planId==v.planId&&it.number==v.number&&it.active}
        run.output(step.id,"bill",bill.id,false)
        run.effect(step,before,before,setOf(before.finance.plans.single{it.id==v.planId}.accountId))
    },
    financeAdapter("financialRepaymentAllocation.resolve",FinanceAllocationResolve.serializer(),mapOf("allocation" to "repaymentAllocation"),
        "eventId and at least one of billId/contractId; null means no filter for that optional target. Exactly one matching allocation required.",
        "Existing dedicated repayment and unique allocation in this book, with matching bill/contract target; no new rows are created.",
        "Resolve a repayment allocation, including one created by a prior step, for an arrangement's clearingMoves.",
        """{"eventId":{"ref":"payment","output":"entry"},"billId":{"ref":"period","output":"bill"}}""") {step,run,v->
        val before=run.state();require(v.billId!=null||v.contractId!=null)
        val repayment=before.finance.repayments.single{it.eventId==v.eventId}
        val allocation=before.finance.repaymentAllocations.single{it.eventId==v.eventId&&(v.billId==null||it.billId==v.billId)&&(v.contractId==null||it.contractId==v.contractId)}
        run.output(step.id,"allocation",allocation.id,false)
        run.effect(step,before,before,setOf(repayment.debtAccountId),setOf(v.eventId))
    },
    financeAdapter("financialRule.validate",FinanceBillTarget.serializer(),mapOf("validation" to "ruleValidation"),
        "billId required; no cloud-supplied validation result.","The same bill must have confirmed values and a predicted rule/date/total. Validation keeps its bill revision.","Compare a confirmed bill with its original rule prediction; records differences, does not label an incomplete comparison verified.","""{"billId":"bill-id"}""") {step,run,v->
        val before=run.state();val rows=financeRows(run)
        val result=run.ledger.validateFinancialRule(run.bookId,v.billId);run.output(step.id,"validation",result.id,true)
        financeEffect(step,run,before,rows)
    },
    financeAdapter("financialManualPlan.save",ManualFinancialPlanInput.serializer(),mapOf("prediction" to "prediction"),
        "Create only. principalMinor=null means unknown; complete=false; opening=false; sourceEventId=null. Explicit ordered periods required, unknown principal/fee stays null.",
        "Active debt account and compatible mode; payment accounts valid; dates strictly increase; complete schedules require exact principal sum; source cannot already have an active plan.",
        "Record a known manual schedule, including partial schedules. Phone confirmation covers the supplied account, dates and amounts.",
        """{"accountId":"card-id","name":"已知分期","mode":"INSTALLMENT","periods":[{"date":"2026-12-10","totalMinor":1100,"principalMinor":1000,"feeMinor":100}],"principalMinor":1000,"complete":true}""",fact=true) {step,run,v->
        val before=run.state();val rows=financeRows(run);val review=run.ledger.previewManualFinancialPlan(run.bookId,v)
        run.output(step.id,"prediction",checkNotNull(run.ledger.commitManualFinancialPlan(review)),true)
        financeFact(step,run,financeEffect(step,run,before,rows),encoded(v),v.accountId,v.periods.sumOf{it.totalMinor},v.periods.joinToString{it.date},"这些逐期金额和日期是已核对的安排。","各回の金額・日付は確認済みの予定です。")
    },
    financeAdapter("financialBill.confirm",FinanceBillConfirm.serializer(),mapOf("bill" to "bill"),
        "bill requires date and totalMinor; omitted/null breakdown stays unknown; paymentAccountId omitted/null clears assignment. No confirmation boolean is accepted.",
        "Active bill; known nonnegative total and compatible breakdown; payment account in this book. Existing allocations and source facts remain visible.",
        "Record or correct the platform's actual bill, preserving the prediction for validation.","""{"billId":"bill-id","bill":{"date":"2026-12-10","totalMinor":1100,"principalMinor":1000,"feeMinor":100}}""",fact=true) {step,run,v->
        val before=run.state();val rows=financeRows(run);val review=run.ledger.previewFinancialBillConfirmation(run.bookId,v.billId,v.bill)
        check(run.ledger.commitFinancialBillConfirmation(review));run.output(step.id,"bill",v.billId,false)
        val account=before.finance.plans.single{it.id==review.bill.planId}.accountId
        financeFact(step,run,financeEffect(step,run,before,rows),encoded(v),account,v.bill.totalMinor,v.bill.date,"这是平台账单的实际日期和金额。","返済先の請求の実際の日付・金額です。")
    },
    financeAdapter("financialPool.save",FinancePoolSave.serializer(),mapOf("prediction" to "prediction"),
        "Required openingMinor/sourceDate/terms; no invented principal. planId omitted/null creates, adoptExisting=false, unallocatedShares/adoptedFees=[]; references supported in each share. Existing payment changes are retained.",
        "Active compatible revolving/credit-line account, complete rule inputs, ordered dates and available opening/source principal. Adoption and unallocated payment shares obey poolFacts and reconciliation checks.",
        "Create, adopt existing arrangements into, reconcile or recalculate the account balance pool. Uses previewFinancialPool/commitFinancialPrediction; no fake transfer.",
        """{"accountId":"card-id","name":"循环余额池","mode":"REVOLVING","openingMinor":10000,"sourceDate":"TRANSACTION","terms":{"agreementDate":"2026-11-02","paymentDates":["2026-12-10"],"accrualWindows":[{"start":"2026-11-02","end":"2026-12-09"}],"firstPeriodNumber":1}}""",fact=true) {step,run,v->
        val before=run.state();val rows=financeRows(run);require(v.unallocatedShares.map{it.eventId}.distinct().size==v.unallocatedShares.size)
        val existing=v.planId?.let{id->before.finance.plans.single{it.id==id}.termsJson?.let{commandJson.decodeFromString<FinancialPredictionInput>(it)}}
        val input=FinancialPredictionInput(v.accountId,v.name,v.mode,v.terms,paymentAccountId=v.paymentAccountId,planId=v.planId,currentArrangementConfirmed=true,
            pool=FinancialPoolInput(v.openingMinor,v.sourceDate,v.unallocatedShares.associate{it.eventId to it.minor},v.adoptExisting,paymentChanges=existing?.pool?.paymentChanges.orEmpty(),adoptedFees=v.adoptedFees))
        val review=run.ledger.previewFinancialPool(run.bookId,input);requireReady(review.calculation)
        run.output(step.id,"prediction",checkNotNull(run.ledger.commitFinancialPrediction(review)),v.planId==null)
        financeFact(step,run,financeEffect(step,run,before,rows),encoded(v),v.accountId,review.originalPrincipalMinor,v.terms.agreementDate,"余额池范围、期初本金、已采纳安排和未分配还款归属均已核对。","残高プールの対象・期首元金・統合予定・未充当返済の帰属を確認しました。")
    },
    arrangementAdapter(),reconciliationAdapter(),poolPaymentAdapter(),batchRevolvingAdapter(),batchUndoAdapter(),
    borrowingAdapter(),repaymentAdapter(false),repaymentAdapter(true),
    financeAdapter("financialContract.linkPlan",FinanceContractLink.serializer(),defaults="Both references required; same link is unchanged.",
        prerequisites="Same account, source and mode; pool cannot bind one contract; no competing active plan; existing clearing prevents changing contract.",
        description="Associate a manual/predicted plan with its borrowing contract using the same phone business operation.",example="""{"contractId":{"ref":"loan","output":"contract"},"planId":{"ref":"schedule","output":"prediction"}}""") {step,run,v->
        val before=run.state();val rows=financeRows(run);run.ledger.linkFinancialContractPlan(run.bookId,v.contractId,v.planId);financeEffect(step,run,before,rows)
    },
    limitConfigAdapter(),limitGroupAdapter(),limitRecoveryAdapter(),
    financeAdapter("creditLimitRecovery.void",FinanceRecoveryVoid.serializer(),defaults="recoveryId required; never deletes the recovery history.",
        prerequisites="Existing non-voided recovery in this book; current source evidence and limits compared.",description="Void a mistaken recovery confirmation and recalculate available limits, retaining history.",example="""{"recoveryId":"recovery-id"}""") {step,run,v->
        val before=run.state();val rows=financeRows(run);run.ledger.voidCreditLimitRecovery(run.bookId,v.recoveryId);financeEffect(step,run,before,rows)
    },
    financeAdapter("creditLimitObservation.record",FinanceObservation.serializer(),mapOf("observation" to "creditLimitObservation"),
        "Exactly one of accountId/groupId; scope (TOTAL/INSTALLMENT/REVOLVING) and availableMinor/observedAt required. Explicit UTC instant; null is not zero. No cloud fact boolean.",
        "Existing active debt account or group; TOTAL/INSTALLMENT/REVOLVING, group only TOTAL; nonnegative value; observedAt cannot be future. Source/config/group revision recorded.",
        "Record the available amount seen on the financial platform. Only the phone user can attest this observation.",
        """{"accountId":"card-id","scope":"TOTAL","availableMinor":80000,"observedAt":"2026-11-02T00:00:00Z"}""",fact=true) {step,run,v->
        val before=run.state();val rows=financeRows(run);val review=run.ledger.previewCreditLimitObservation(run.bookId,v.accountId,v.groupId,v.scope,v.availableMinor,v.observedAt)
        check(run.ledger.commitCreditLimit(review));run.output(step.id,"observation",requireNotNull(review.observation).id,true)
        var effect=financeEffect(step,run,before,rows)
        if(v.groupId!=null) {
            val group=before.limits.groups.single{it.id==v.groupId}
            val amount=Money(Currency.valueOf(group.currency),v.availableMinor).display()
            effect=effect.copy(requirements=effect.requirements+requirement(step.id,ConfirmationKind.USER_FACT,buildJsonObject{put("input",run.semantic(encoded(v)));put("objects",encoded(effect.objects))},
                "我已核对：${group.name} · ${amount} · ${v.observedAt}，这是平台显示的共享可用额度。",
                "確認しました：${group.name} · ${amount} · ${v.observedAt}、返済先が表示した共通利用可能額です。"))
            effect
        }else financeFact(step,run,effect,encoded(v),v.accountId,v.availableMinor,v.observedAt,"这是平台显示的可用额度。","返済先が表示した利用可能額です。")
    },
    financeAdapter("financialDraft.reclassify",FinanceDraftCategory.serializer(),defaults="sourceCategoryId/categoryId required. slots omitted selects matching financial draft categories at first preview and freezes exact slots; explicit slots must match source category. Other inputs and original basis preserved.",
        prerequisites="Existing EXPECTED financial drafts; active INCOME destination category in this book. Confirm never expands the frozen draft set; changed source category rejects.",
        description="Migrate expected-income form draft categories without deleting drafts. Other financial forms have no category; ordinary entry drafts use entryDraft.reclassify. Existing phone form category controls provide the same edit.",example="""{"sourceCategoryId":"old-income-category-id","categoryId":"income-category-id"}""",
        normalizeInput={input,run->
            if(input["slots"]!=null&&input["slots"]!=JsonNull)input else {
                val source=run.resolve(input.getValue("sourceCategoryId"))
                val slots=run.ledger.db.financialDao().bookDrafts(run.bookId).filter{row->commandJson.parseToJsonElement(row.valuesJson).jsonObject["category"]==JsonPrimitive(source)}.map{it.slot}.sorted()
                JsonObject(input+("slots" to encoded(slots)))
            }
        }) {step,run,v->
        val before=run.state();val rows=financeRows(run);require(before.ledger.categories.any{it.id==v.sourceCategoryId&&it.kind=="INCOME"});val slots=requireNotNull(v.slots);require(slots.size<=5000&&slots.distinct().size==slots.size)
        for(slot in slots) {
            val row=requireNotNull(run.ledger.db.financialDao().draft(run.bookId,slot))
            require(commandJson.parseToJsonElement(row.valuesJson).jsonObject["category"]==JsonPrimitive(v.sourceCategoryId))
            run.ledger.reclassifyFinancialDraft(run.bookId,slot,v.categoryId)
        }
        financeEffect(step,run,before,rows)
    }
)

private fun arrangementAdapter()=financeAdapter("financialArrangement.record",FinanceApplied.serializer(FinancialArrangementInput.serializer()),mapOf("prediction" to "prediction","arrangement" to "arrangement"),
    "Explicit appliedAt instant; unknown fees/treatment stay unknown and block incomplete calculation. Optional manualPeriods permit manual replacement. referenceRates and undoGroupId are phone-owned and forbidden in input.",
    "Current original plan/source/clearings; compatible target mode, rules or manual schedule; valid pool selection/merge; nonfuture appliedAt. Deadline exception requires a separate phone USER_FACT; rerun rechecks all prerequisites.",
    "Record an already completed platform arrangement, including installment/revolving, manual schedules, pool extraction and merge. It does not contact the platform.",
    """{"input":{"planId":"plan-id","name":"分期变更","mode":"INSTALLMENT","terms":{"agreementDate":"2026-11-02","paymentDates":["2026-12-10"],"firstPeriodNumber":1},"paymentAccountId":"bank-id","originalFeeTreatment":"RETAIN","changeFeeMinor":0},"appliedAt":"2026-11-02T00:00:00Z"}""",fact=true,
    schemaPatch=mapOf("input" to financeReferences(withoutFields(descriptorSchema(FinancialArrangementInput.serializer().descriptor),setOf("referenceRates","undoGroupId"))))) {step,run,v->
        val before=run.state();val rows=financeRows(run)
        val review=run.ledger.previewFinancialArrangement(run.bookId,v.input.copy(referenceRates=run.context.referenceRates));requireReady(review.calculation)
        val exception=review.deadlines.any{Instant.parse(v.appliedAt)>it.instant()}
        val id=checkNotNull(run.ledger.commitFinancialArrangement(review,v.appliedAt,true,exception))
        run.output(step.id,"prediction",id,true);run.output(step.id,"arrangement",review.arrangementId,true)
        val effect=financeFact(step,run,financeEffect(step,run,before,rows),encoded(v),review.original.accountId,review.target?.principalMinor,v.appliedAt,"我已在平台完成此安排，原费用处理和清偿迁移均与平台一致。","返済先で手続き済みで、元の費用と返済充当の移行も一致しています。")
        deadlineFact(step,run,effect,encoded(v),exception)
    }
private fun deadlineFact(step:CommandStep,run:CommandRun,effect:StepEffect,input:JsonElement,exception:Boolean)=if(!exception)effect else effect.copy(requirements=effect.requirements+requirement(step.id,ConfirmationKind.USER_FACT,
    buildJsonObject{put("input",run.semantic(input));put("objects",encoded(effect.objects));put("deadlineException",true)},
    effect.requirements.last{it.kind==ConfirmationKind.USER_FACT}.zh.substringBefore("。")+"。我确认平台明确接受了这次期限外办理。",effect.requirements.last{it.kind==ConfirmationKind.USER_FACT}.ja.substringBefore("。")+"。返済先がこの期限外手続きを明示的に受理したことを確認しました。"))

private fun reconciliationAdapter()=financeAdapter("financialArrangement.reconcile",ArrangementReconciliationInput.serializer(),mapOf("prediction" to "prediction"),
    "Explicit plan/date/principal/bills/refunds; principal null stays unknown. Omitted clearingMoves=[], note empty, optional source corrections null. poolUnallocatedShares is a literal event-ID-to-integer map; use existing payment IDs.",
    "Existing applied arrangement; source, parent chain, refunds, bills and allocations must reconcile. No conflicting obligations; all calculation missing fields block execution.",
    "Recheck an applied arrangement after source/refund/clearing changes; supports pool evidence and exact bill allocation, without creating a cash transfer.",
    """{"planId":"arranged-plan-id","effectiveDate":"2026-11-02","principalMinor":10000,"bills":[{"billId":"original-bill-id","principalMinor":0,"feeMinor":0}],"refunds":[]}""",fact=true,
    schemaPatch=mapOf("poolUnallocatedShares" to nullableSchema(integerMapSchema()))) {step,run,v->
        val before=run.state();val rows=financeRows(run);val review=run.ledger.previewArrangementReconciliation(run.bookId,v);requireReady(review.calculation)
        run.output(step.id,"prediction",checkNotNull(run.ledger.commitArrangementReconciliation(review,true)),false)
        financeFact(step,run,financeEffect(step,run,before,rows),encoded(v),review.original.accountId,v.principalMinor,v.effectiveDate,"当前来源本金、退款和清偿归属已与平台重新核对。","現在の対象元金・返金・返済充当の帰属を返済先と再確認しました。")
    }
private fun integerMapSchema()=buildJsonObject{put("type","object");put("additionalProperties",buildJsonObject{put("type","integer")});put("properties",buildJsonObject{});put("required",JsonArray(emptyList()))}
private fun poolPaymentAdapter()=financeAdapter("financialPool.changePayment",FinanceApplied.serializer(PoolPaymentChangeInput.serializer()),mapOf("prediction" to "prediction"),
    "Explicit setting/effectiveDate/feeMinor/appliedAt; feeMinor null unknown. feeDate=null, feeIncludedInRetained=false; rates supplied by phone execution context.",
    "Active pool with valid unchanged source facts; complete calculation; nonfuture appliedAt; phone must attest platform acceptance including any configured deadline exception.",
    "Record an applied common monthly pool payment change and its fee; preserves the existing pool source/adoption history.",
    """{"input":{"planId":"pool-id","effectiveDate":"2026-11-02","setting":{"fromPaymentDate":"2026-12-10","method":"FIXED_PRINCIPAL","paymentMinor":10000},"feeMinor":0},"appliedAt":"2026-11-02T00:00:00Z"}""",fact=true,
    schemaPatch=mapOf("input" to financeReferences(withoutFields(descriptorSchema(PoolPaymentChangeInput.serializer().descriptor),setOf("referenceRates"))))) {step,run,v->
        val before=run.state();val rows=financeRows(run);val review=run.ledger.previewPoolPaymentChange(run.bookId,v.input.copy(referenceRates=run.context.referenceRates));requireReady(review.calculation)
        val exception=review.deadlines.any{Instant.parse(v.appliedAt)>it.instant()}
        val id=checkNotNull(run.ledger.commitPoolPaymentChange(review,v.appliedAt,true,exception));run.output(step.id,"prediction",id,false)
        deadlineFact(step,run,financeFact(step,run,financeEffect(step,run,before,rows),encoded(v),review.original.accountId,review.prediction?.originalPrincipalMinor,v.appliedAt,"共同月付款设置和费用已在平台办理并核对。","共通月払い設定と費用を返済先で手続き・確認しました。"),encoded(v),exception)
    }
private fun batchRevolvingAdapter()=financeAdapter("financialBatchRevolving.apply",FinanceApplied.serializer(BatchRevolvingInput.serializer()),mapOf("batch" to "revolvingBatch"),
    "Explicit fixed planIds/principalMinor/poolId/paymentDate/effectiveDate/appliedAt. Unknown fee blocks; mergeFeeDate=null, mergeFeeIncludedInRetained=false. Selection never expands on confirm.",
    "All chosen single-payment bills remain eligible: live source, no later clearing, unchanged prediction, same payment date/account; active revolving pool; positive available transfer principal. Platform and deadline acceptance only via phone checks.",
    "Convert selected card single-payment obligations into an existing revolving pool as one business operation; returns the recorded undo group.",
    """{"input":{"accountId":"card-id","paymentDate":"2026-12-10","planIds":["single-plan-id"],"principalMinor":10000,"poolId":"pool-id","effectiveDate":"2026-11-02","feeMinor":0},"appliedAt":"2026-11-02T00:00:00Z"}""",fact=true) {step,run,v->
        val before=run.state();val rows=financeRows(run);val review=run.ledger.previewBatchRevolving(run.bookId,v.input);requireReady(review.calculation)
        val exception=review.reviews.any{r->r.deadlines.any{Instant.parse(v.appliedAt)>it.instant()}}
        val group=checkNotNull(run.ledger.commitBatchRevolving(review,v.appliedAt,true,exception));run.output(step.id,"batch",group,true)
        deadlineFact(step,run,financeFact(step,run,financeEffect(step,run,before,rows),encoded(v),v.input.accountId,v.input.principalMinor,v.appliedAt,"所选账单的批量转循环已在平台办理，转入本金和费用一致。","選択した請求の一括リボ変更は返済先で手続き済みで、移行元金・費用が一致しています。"),encoded(v),exception)
    }
private fun batchUndoAdapter()=financeAdapter("financialBatchRevolving.undo",FinanceBatchUndo.serializer(),defaults="Explicit existing undo group; no inferred latest group.",
    prerequisites="Exact archived after-plans/bills/arrangements/allocations and pool evidence must still match; later changes, repayment/reconciliation or external FK/JSON/draft references block the entire batch. Only this operation's generated arrangement/adjustment rows may be removed; complete archived history is retained.",
    description="Restore a recorded batch conversion using undoBatchRevolving. This is audited relation restoration, not permanent user-data deletion. The command itself supports exact whole-plan undo.",
    example="""{"undoGroupId":{"ref":"convert","output":"batch"}}""",schemaPatch=mapOf("undoGroupId" to referenceSchema("revolvingBatch"))) {step,run,v->
        val before=run.state();val rows=financeRows(run);run.ledger.undoBatchRevolving(run.bookId,v.undoGroupId);financeEffect(step,run,before,rows)
    }
private fun borrowingAdapter()=financeAdapter("financialBorrowing.save",FinancialBorrowingInput.serializer(),mapOf("contract" to "contract","entry" to "entry"),
    "Full form save: opening=false; nullable fees/received stay unknown, note empty. Opening contract has no cash entry output; actual borrowing requires explicit funding account/received amount. Existing contractId/eventId preserve source identity.",
    "Active LOAN account, matching loan mode/currency; principal positive, date explicit; opening allocation <= available opening debt; actual received/fees reconcile; changing contract principal cannot undercut cleared principal.",
    "Record/edit an actual borrowing or opening debt contract with dedicated principal, received funds and fee semantics; never a generic transfer. entry output is unavailable for opening contracts.",
    """{"accountId":"loan-account-id","name":"借入","mode":"FIXED_LOAN","principal":{"currency":"JPY","minor":100000},"date":"2026-11-02","fundingAccountId":"bank-id","received":{"currency":"JPY","minor":100000},"withheldFee":{"currency":"JPY","minor":0}}""") {step,run,v->
        val before=run.state();val rows=financeRows(run);val review=run.ledger.previewBorrowing(run.bookId,v)
        checkNotNull(run.ledger.commitFinancialLedger(review));run.output(step.id,"contract",requireNotNull(review.contract).id,v.contractId==null)
        review.change?.event?.let{run.output(step.id,"entry",it.id,v.eventId==null)}
        financeEffect(step,run,before,rows)
    }
private fun repaymentAdapter(cleared:Boolean)=financeAdapter(if(cleared)"financialRepayment.confirm"else"financialRepayment.save",FinancialRepaymentInput.serializer(),mapOf("entry" to "entry"),
    "Full save; type=NORMAL, allocations=[], note empty, optional values unknown. platformConfirmed is forbidden: save records pending platform clearing; confirm requires phone USER_FACT. Explicit zero for known zero components.",
    "Actual cash source and debt account, matching currencies/date; payment positive; allocation targets belong to debt account and cannot exceed remaining obligations. Confirm requires received/principal/interest/fee/clearingDate and exact component sum; principal-only debt reduction, fees remain expenses.",
    "Record/edit dedicated repayments and bill/contract allocations, including PARTIAL/EARLY/SETTLEMENT and linked FX transfer. Confirm records platform clearing only after the phone user attests it.",
    if(cleared)"""{"sourceAccountId":"bank-id","debtAccountId":"loan-id","paid":{"currency":"JPY","minor":1000},"date":"2026-11-02","received":{"currency":"JPY","minor":1000},"principal":{"currency":"JPY","minor":1000},"interest":{"currency":"JPY","minor":0},"fee":{"currency":"JPY","minor":0},"clearingDate":"2026-11-02"}"""else"""{"sourceAccountId":"bank-id","debtAccountId":"loan-id","paid":{"currency":"JPY","minor":1000},"date":"2026-11-02"}""",fact=cleared,omit=setOf("platformConfirmed")) {step,run,v->
        val before=run.state();val rows=financeRows(run);val review=run.ledger.previewRepayment(run.bookId,v.copy(platformConfirmed=cleared))
        val id=checkNotNull(run.ledger.commitFinancialLedger(review));run.output(step.id,"entry",id,v.eventId==null)
        val effect=financeEffect(step,run,before,rows)
        if(cleared)financeFact(step,run,effect,encoded(v),v.debtAccountId,v.principal?.minor,requireNotNull(v.clearingDate),"平台已确认清偿；实收、本金、利息、费用和分配对象均已核对。","返済先が返済を確定済みで、受取額・元金・利息・手数料・充当先を確認しました。")else effect
    }
private fun limitConfigAdapter()=financeAdapter("creditLimit.save",CreditLimitInput.serializer(),mapOf("config" to "creditLimitConfig"),
    creditLimitInputGuide,
    "Active debt account; nonnegative amounts; basisDate<=effectiveFrom<=today in explicit zone; sublimits consistent; loans cannot set card-only sublimits. Unknown nullable policies are saveable and do not require an empty plan; known zero usage still needs the phone fact check.",
    "Append a credit-limit configuration and usage anchor, covering all capacities, sharing modes, recovery policies and usage-date rules.",
    """{"accountId":"card-id","totalMinor":100000,"installmentMinor":null,"revolvingMinor":null,"sharing":"TOTAL_ONLY","combinedMinor":null,"effectiveFrom":"2026-11-02","basisDate":"2026-11-02","usedTotalMinor":10000,"usedInstallmentMinor":null,"usedRevolvingMinor":null,"recovery":null,"usageBasis":"PRINCIPAL_ONLY","zoneId":"Asia/Tokyo"}""",fact=true) {step,run,v->
        val before=run.state();val rows=financeRows(run);val review=run.ledger.previewCreditLimit(run.bookId,v);check(run.ledger.commitCreditLimit(review))
        run.output(step.id,"config",requireNotNull(review.config).id,true)
        val effect=financeEffect(step,run,before,rows)
        financeUsageFact(step,run,effect,v)
    }
private fun limitGroupAdapter()=financeAdapter("creditLimitGroup.save",CreditLimitGroupInput.serializer(),mapOf("group" to "creditLimitGroup"),
    "Full group save; id omitted/null creates; all member accountIds explicit and distinct. totalMinor/usedMinor null means unknown, never zero; known usedMinor requires a phone USER_FACT.",
    "Nonblank name, valid nonfuture basisDate, nonnegative amounts; active debt accounts in same currency, no competing group membership; existing group revision retained as evidence.",
    "Create/edit a shared limit group and its complete membership, preserving group revision history. Removed memberships are replaced relationships, not deleted financial objects.",
    """{"name":"共享额度","currency":"JPY","totalMinor":200000,"usedMinor":30000,"basisDate":"2026-11-02","accountIds":["card-a","card-b"]}""",fact=true,schemaPatch=mapOf("id" to nullableSchema(referenceSchema("creditLimitGroup")))) {step,run,v->
        val before=run.state();val rows=financeRows(run);val review=run.ledger.previewCreditLimitGroup(run.bookId,v);check(run.ledger.commitCreditLimit(review))
        run.output(step.id,"group",requireNotNull(review.group).id,v.id==null)
        val effect=financeEffect(step,run,before,rows)
        if(v.usedMinor==null)effect else effect.copy(requirements=effect.requirements+requirement(step.id,ConfirmationKind.USER_FACT,
            buildJsonObject{put("input",run.semantic(encoded(v)));put("objects",encoded(effect.objects))},
            "我已核对共享组「${v.name}」在 ${v.basisDate} 的占用为 ${Money(v.currency,v.usedMinor).display()}，成员账户与平台一致。",
            "共通枠「${v.name}」の ${v.basisDate} の使用額 ${Money(v.currency,v.usedMinor).display()} と所属口座を返済先で確認しました。"))
    }
private fun limitRecoveryAdapter()=financeAdapter("creditLimitRecovery.confirm",CreditLimitRecoveryInput.serializer(),mapOf("recovery" to "creditLimitRecovery"),
    "All fields required; installmentMinor/revolvingMinor null remain unknown. Explicit account/event/date/principal. No cloud-confirmed flag.",
    "Actual confirmed repayment for this debt account; recovery and subcomponents cannot exceed the payment's unrecovered principal; configured recovery and current source proof must match.",
    "Record platform-confirmed available-limit recovery after repayment. This does not create another repayment or modify cash balances.",
    """{"accountId":"card-id","eventId":"repayment-id","date":"2026-11-02","principalMinor":1000,"installmentMinor":null,"revolvingMinor":null}""",fact=true) {step,run,v->
        val before=run.state();val rows=financeRows(run);val review=run.ledger.previewCreditLimitRecovery(run.bookId,v);check(run.ledger.commitCreditLimit(review))
        run.output(step.id,"recovery",requireNotNull(review.recovery).id,true)
        financeFact(step,run,financeEffect(step,run,before,rows),encoded(v),v.accountId,v.principalMinor,v.date,"这笔还款对应的额度已由平台实际恢复。","この返済に対応する利用枠が返済先で実際に回復しました。")
    }
