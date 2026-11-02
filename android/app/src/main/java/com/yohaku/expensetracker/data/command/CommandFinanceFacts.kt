package com.yohaku.expensetracker.data.command

import com.yohaku.expensetracker.data.repository.*
import kotlinx.serialization.json.*

/** Comparison projection only. Never pass this result to a domain write operation. Business
 * revisions, actual appliedAt/observedAt and source dates are deliberately retained. */
internal fun commandFinanceFacts(value:JsonElement):JsonElement=when(value) {
    is JsonObject->JsonObject(value.filterKeys{it !in setOf("createdAt","updatedAt","checkedAt","confirmedAt","recordedAt")}.mapValues{(key,v)->
        val decoded=if((key.endsWith("Json")||key in setOf("json","sourceProof"))&&v is JsonPrimitive&&v.isString&&v.content.trimStart().let{it.startsWith("{")||it.startsWith("[")})commandJson.parseToJsonElement(v.content)else v
        if(key=="voidedAt")JsonPrimitive((v as? JsonPrimitive)?.booleanOrNull?: (v!=JsonNull))else commandFinanceFacts(decoded)
    })
    is JsonArray->JsonArray(value.map{part->
        // Source proofs and observation anchors encode nested objects as array strings.
        commandFinanceFacts(if(part is JsonPrimitive&&part.isString&&part.content.trimStart().let{it.startsWith("{")||it.startsWith("[")})commandJson.parseToJsonElement(part.content)else part)
    })
    else->value
}
internal val financeSetFields=setOf("sources","payments","repayments","refunds","allocations","adjustments","billAdjustments","rules","movements","clearings","contractPlans","retained","retainedBills","accountIds","sourcePlanIds","retainedBillIds","arrangementFees")
internal fun orderFinanceFacts(value:JsonElement,key:String=""):JsonElement=when(value) {
    is JsonObject->{
        val fields=value.mapValues{(k,v)->orderFinanceFacts(v,k)}
        // FinancialArrangementComparison contains unordered obligation rows, originally read in
        // bill UUID order. Unlike bill facts, these rows carry no ID/period number. Sort only this
        // typed comparison's two collections, after identity normalization; keep every whole row
        // (including duplicates). Payment-date sequences and arbitrary "original" arrays retain order.
        if("originalUnscheduledPrincipalMinor" in fields&&"proposedRemainingPrincipalMinor" in fields)
            JsonObject(fields+listOf("original","proposed").associateWith{field->
                JsonArray(fields.getValue(field).jsonArray.sortedBy{canonical(it).toString()})
            })
        else JsonObject(fields)
    }
    is JsonArray->value.map{orderFinanceFacts(it)}.let{JsonArray(if(key in financeSetFields||it.isNotEmpty()&&it.all{row->row is JsonObject&&(row["id"]!=null||row["eventId"]!=null||(row["bill"] as? JsonObject)?.get("id")!=null)})it.sortedBy{row->canonical(row).toString()}else it)}
    else->value
}
internal fun CommandRun.registerFinanceIdentities(step:String,before:FinancialReviewState,after:FinancialReviewState) {
    fun name(id:String)=semantic(JsonPrimitive(id)).jsonPrimitive.content
    val old=before.finance;val next=after.finance
    val oldPlans=old.plans.associateBy{it.id};val oldBills=old.bills.map{it.id}.toSet()
    val oldArrangements=old.arrangements.map{it.id}.toSet();val oldContracts=old.contracts.map{it.id}.toSet()
    val oldAllocations=old.repaymentAllocations.map{it.id}.toSet();val oldAdjustments=old.billAdjustments.map{it.id}.toSet()
    val oldValidations=old.validations.map{it.id}.toSet();val oldPostings=before.ledger.storedPostings.map{it.id}.toSet()
    val oldConfigs=before.limits.configs.map{it.id}.toSet();val oldGroups=before.limits.groups.map{it.id}.toSet()
    val oldRecoveries=before.limits.recoveries.map{it.id}.toSet();val oldObservations=before.limits.observations.map{it.id}.toSet()
    next.plans.filter{p->p.id !in oldPlans}.forEach{p->generated(p.id,step,"prediction",name(p.accountId)+":"+(p.sourceEventId?.let(::name)?:p.name)+":"+p.mode)}
    next.bills.filter{b->b.id !in oldBills}.forEach{b->generated(b.id,step,"bill",name(b.planId)+":"+b.number)}
    next.arrangements.filter{a->a.id !in oldArrangements}.forEach{a->generated(a.id,step,"arrangement",name(a.originalPlanId)+":"+name(a.targetPlanId))}
    next.contracts.filter{c->c.id !in oldContracts}.forEach{c->generated(c.id,step,"contract",name(c.accountId)+":"+c.name+":"+c.date)}
    next.repaymentAllocations.filter{a->a.id !in oldAllocations}.forEach{a->generated(a.id,step,"repaymentAllocation",name(a.eventId)+":"+a.billId?.let(::name)+":"+a.contractId?.let(::name)+":"+a.principalMinor+":"+a.interestMinor+":"+a.feeMinor)}
    next.billAdjustments.filter{a->a.id !in oldAdjustments}.forEach{a->generated(a.id,step,"billAdjustment",name(a.arrangementId)+":"+name(a.billId)+":"+a.principalMinor+":"+a.feeMinor)}
    next.validations.filter{v->v.id !in oldValidations}.forEach{v->generated(v.id,step,"ruleValidation",name(v.billId)+":"+v.billRevision)}
    after.limits.configs.filter{v->v.id !in oldConfigs}.forEach{v->generated(v.id,step,"creditLimitConfig",name(v.accountId)+":"+v.version)}
    after.limits.groups.filter{v->v.id !in oldGroups}.forEach{v->generated(v.id,step,"creditLimitGroup",v.name)}
    after.limits.recoveries.filter{v->v.id !in oldRecoveries}.forEach{v->generated(v.id,step,"creditLimitRecovery",name(v.eventId)+":"+v.date+":"+v.principalMinor)}
    after.limits.observations.filter{v->v.id !in oldObservations}.forEach{v->generated(v.id,step,"creditLimitObservation",(v.accountId?:v.groupId)?.let(::name)+":"+v.scope+":"+v.observedAt)}
    after.ledger.storedPostings.filter{p->p.id !in oldPostings}.forEach{p->generated(p.id,step,"posting",name(p.eventId)+":"+name(p.accountId)+":"+p.id.substringAfterLast(':'))}
    for(p in next.plans) {
        val previous=oldPlans[p.id]
        if(previous==p)continue
        val changes=poolInput(p)?.paymentChanges.orEmpty()
        val oldChanges=previous?.let(::poolInput)?.paymentChanges.orEmpty().map{it.id}.toSet()
        changes.filter{it.id !in oldChanges}.forEachIndexed{i,c->generated(c.id,step,"poolPaymentChange",name(p.id)+":"+c.effectiveDate+":"+i)}
        p.reconciliation()?.let{r->if(previous?.reconciliation()?.id!=r.id)generated(r.id,step,"arrangementReconciliation",name(p.id))}
    }
    // Derived identifiers are registered as whole values. Never replace substrings in notes/names.
    for(p in after.ledger.storedPostings)if(isGenerated(p.id))for(prefix in listOf("source","charge"))generated("$prefix:${p.id}",step,"financeSource",prefix+":"+name(p.id))
    for(e in after.ledger.events)if(isGenerated(e.id))for(prefix in listOf("refund","rule"))generated("$prefix:${e.id}",step,"financeSource",prefix+":"+name(e.id))
    for(a in next.arrangements) {
        if(isGenerated(a.id))generated("arrangement:${a.id}",step,"financeSource","arrangement:"+name(a.id))
        val source=next.plans.first{it.id==a.originalPlanId}.sourceEventId
        val refunds=after.ledger.settlements.filter{source!=null&&it.originalEventId==source}.map{it.eventId}+
            next.plans.first{it.id==a.targetPlanId}.reconciliation()?.refunds.orEmpty().map{it.fact.eventId}
        for(event in refunds.distinct())if(isGenerated(a.id)||isGenerated(event))generated("refund-allocation:${a.id}:${event}",step,"financeSource","refund-allocation:"+name(a.id)+":"+name(event))
    }
    for(r in after.limits.recoveries)if(isGenerated(r.id))generated("actual:${r.id}",step,"financeSource","actual:"+name(r.id))
}
