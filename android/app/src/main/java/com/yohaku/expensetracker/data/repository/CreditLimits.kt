package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.lang.Math.addExact
import java.lang.Math.subtractExact

@Serializable enum class CreditLimitSharing { TOTAL_ONLY, SEPARATE_CAPS, SHARED_SUBPOOL }
@Serializable enum class CreditLimitUsageStart { TRANSACTION_DATE, ACCOUNT_POSTING_DATE }
@Serializable enum class CreditLimitUsageBasis { PRINCIPAL_ONLY, PLATFORM_ONLY }
@Serializable data class LimitMovement(val id:String,val date:String,val total:Long?,val installment:Long?,val revolving:Long?,val sourceId:String?,val kind:String)
@Serializable data class LimitAnchor(val movements:List<LimitMovement>)
@Serializable data class GroupLimitAnchor(val configs:Map<String,String?>,val movements:Map<String,List<LimitMovement>>)
data class LimitPayment(val eventId:String,val accountId:String,val date:String,val clearingDate:String?,val principalMinor:Long?,val confirmed:Boolean,val installmentMinor:Long?,val revolvingMinor:Long?,val proof:String)
data class LimitAmount(val capacityMinor:Long?,val usedMinor:Long?,val availableMinor:Long?,val excessMinor:Long?)
data class AccountCreditLimit(val config:CreditLimitConfigEntity?,val total:LimitAmount,val installment:LimitAmount,val revolving:LimitAmount,
    val issues:List<String>,val groupId:String?,val effectiveTotalMinor:Long?)
data class GroupCreditLimit(val group:CreditLimitGroupEntity,val total:LimitAmount,val issues:List<String>)

fun CreditLimitGraph.currentConfig(accountId:String,date:String)=configs.filter{it.accountId==accountId&&it.effectiveFrom<=date}.maxWithOrNull(compareBy<CreditLimitConfigEntity>{it.effectiveFrom}.thenBy{it.version})
private fun mode(s:BookSnapshot,f:FinancialGraph,accountId:String,eventId:String):String? {
    s.contracts.firstOrNull{it.accountId==accountId&&it.fundingEventId==eventId}?.let{return it.mode}
    s.visibleEvents.firstOrNull{it.id==eventId&&it.accountId==accountId}?.cardPaymentMode?.let{return it}
    val roots=f.plans.filter{(it.active||f.arrangements.any{a->a.originalPlanId==it.id})&&it.accountId==accountId&&it.sourceEventId==eventId&&f.arrangements.none{a->a.targetPlanId==it.id}}
    return roots.map{it.mode}.distinct().singleOrNull()
}
private fun sub(principal:Long?,mode:String?):Pair<Long?,Long?> = when(mode) {
    FinanceMode.INSTALLMENT.name->principal to 0L
    FinanceMode.REVOLVING.name->0L to principal
    FinanceMode.SINGLE.name,FinanceMode.FIXED_LOAN.name,FinanceMode.CREDIT_LINE.name->0L to 0L
    else->null to null
}

/** Monetary source proof excludes names/notes and includes the dates which govern restoration. */
fun limitPayments(s:BookSnapshot,f:FinancialGraph,accountId:String):List<LimitPayment> {
    val live=s.visibleEvents.associateBy{it.id}
    val result=mutableListOf<LimitPayment>()
    for(e in live.values) {
        val repayment=s.repayments.firstOrNull{it.eventId==e.id&&it.debtAccountId==accountId}
        val credits=s.storedPostings.filter{it.eventId==e.id&&it.accountId==accountId&&it.deltaMinor<0}
        val refund=s.settlements.firstOrNull{it.eventId==e.id&&it.originalEventId!=null&&it.accountCurrency==s.accounts.first{a->a.id==accountId}.currency&&credits.isNotEmpty()}
        if(repayment==null&&credits.isEmpty())continue
        if(repayment==null&&e.kind !in listOf(EntryKind.REPAYMENT.name,EntryKind.REFUND.name))continue
        val principal=repayment?.principalMinor?:if(repayment==null)credits.fold(0L){sum,p->subtractExact(sum,p.deltaMinor)}else null
        val confirmed=repayment?.platformConfirmed?:credits.all{it.confirmed}
        val clear=repayment?.clearingDate?:credits.maxOfOrNull{it.date}
        val date=if(refund!=null)refund.settlementDate else e.date
        var split:Long?=0;var revo:Long?=0
        if(refund!=null) {
            val parts=sub(principal,refund.originalEventId?.let{mode(s,f,accountId,it)})
            split=parts.first;revo=parts.second
            for(a in f.currentArrangements.filter{it.effectiveDate<=date}) {
                val target=f.plans.first{it.id==a.targetPlanId};val original=f.plans.first{it.id==a.originalPlanId}
                val share=target.reconciliation()?.refunds?.firstOrNull{it.fact.eventId==e.id&&it.fact.date>a.effectiveDate}?.targetMinor?:continue
                val from=sub(-share,original.mode);val to=sub(share,target.mode)
                split=split?.let{n->from.first?.let{x->to.first?.let{addExact(n,addExact(x,it))}}}
                revo=revo?.let{n->from.second?.let{x->to.second?.let{addExact(n,addExact(x,it))}}}
            }
        }else {
            val allocations=s.repaymentAllocations.filter{it.eventId==e.id}
            val allocated=allocations.mapNotNull{it.principalMinor}.fold(0L,::addExact)
            if(principal==null||allocations.any{it.principalMinor==null}||allocated!=principal){split=null;revo=null}
            else for(a in allocations) {
                val m=a.billId?.let{id->f.bills.firstOrNull{it.id==id}?.let{b->f.plans.firstOrNull{it.id==b.planId}?.mode}}
                    ?:a.contractId?.let{id->s.contracts.firstOrNull{it.id==id}?.mode}
                val parts=sub(a.principalMinor,m)
                split=split?.let{n->parts.first?.let{addExact(n,it)}};revo=revo?.let{n->parts.second?.let{addExact(n,it)}}
            }
        }
        val account=s.accounts.first{it.id==accountId}
        var relevant=principal
        if(account.type==AccountType.LOAN.name) {
            val allocations=s.repaymentAllocations.filter{it.eventId==e.id}
            val allocated=allocations.mapNotNull{it.principalMinor}.fold(0L,::addExact)
            relevant=if(principal==null||allocated!=principal||allocations.any{it.principalMinor==null})null else {
                var sum=0L;var unknown=false
                for(a in allocations) {
                    val m=a.billId?.let{id->f.bills.firstOrNull{it.id==id}?.let{b->f.plans.firstOrNull{it.id==b.planId}?.mode}}?:a.contractId?.let{id->s.contracts.firstOrNull{it.id==id}?.mode}
                    if(m==FinanceMode.CREDIT_LINE.name)sum=addExact(sum,a.principalMinor!!)else if(m!=FinanceMode.FIXED_LOAN.name)unknown=true
                }
                if(unknown)null else sum
            }
        }
        val proof=Json.encodeToString(listOf(accountId,date,clear,principal?.toString(),relevant?.toString(),split?.toString(),revo?.toString()))
        result+=LimitPayment(e.id,accountId,date,clear,relevant,confirmed,split,revo,proof)
    }
    return result.sortedBy{it.eventId}
}

fun creditLimitMovements(s:BookSnapshot,f:FinancialGraph,l:CreditLimitGraph,accountId:String,asOf:String,policy:String?,usageStart:String?=null,recoveryDelayDays:Int=0):List<LimitMovement> {
    val result=mutableListOf<LimitMovement>();val account=s.accounts.first{it.id==accountId}
    val live=s.visibleEvents.associateBy{it.id}
    for(p in s.storedPostings.filter{it.accountId==accountId&&it.deltaMinor>0&&it.eventId in live}) {
        val date=if(usageStart==CreditLimitUsageStart.TRANSACTION_DATE.name)live.getValue(p.eventId).date else p.date
        if(date>asOf)continue
        val m=mode(s,f,accountId,p.eventId)
        val total=if(account.type==AccountType.LOAN.name)when(m){FinanceMode.CREDIT_LINE.name->p.deltaMinor;FinanceMode.FIXED_LOAN.name->0L;else->null}else p.deltaMinor
        val parts=sub(total,m)
        result+=LimitMovement("charge:${p.id}",date,total,parts.first,parts.second,p.eventId,"CHARGE")
    }
    for(payment in limitPayments(s,f,accountId)) {
        val actual=l.recoveries.filter{it.accountId==accountId&&it.eventId==payment.eventId&&it.voidedAt==null}
        if(actual.isNotEmpty()) {
            for(r in actual.filter{it.date<=asOf}) {
                val valid=r.sourceProof==payment.proof
                result+=LimitMovement("actual:${r.id}",r.date,if(valid)-r.principalMinor else null,if(valid)r.installmentMinor?.let{Math.negateExact(it)}else null,if(valid)r.revolvingMinor?.let{Math.negateExact(it)}else null,payment.eventId,if(valid)"ACTUAL_RECOVERY"else"RECOVERY_CHANGED")
            }
        }else {
            val date=when(policy){LimitRecovery.PAYMENT_DATE.name->paymentRecoveryDate(LocalDate.parse(payment.date),recoveryDelayDays).toString();LimitRecovery.PLATFORM_CONFIRMATION.name->if(payment.confirmed)payment.clearingDate else null;else->null}
            if(date!=null&&date<=asOf)result+=LimitMovement("rule:${payment.eventId}",date,payment.principalMinor?.let{Math.negateExact(it)},payment.installmentMinor?.let{Math.negateExact(it)},payment.revolvingMinor?.let{Math.negateExact(it)},payment.eventId,"RULE_RECOVERY")
        }
    }
    for(a in f.currentArrangements.filter{it.effectiveDate<=asOf&&f.plans.any{p->p.id==it.originalPlanId&&p.accountId==accountId}}) {
        val old=f.plans.first{it.id==a.originalPlanId};val new=f.plans.first{it.id==a.targetPlanId}
        if(account.type==AccountType.LOAN.name) {
            val declared=Json.decodeFromString<FinancialArrangementInput>(a.inputJson).loanLimitDeltaMinor
            result+=LimitMovement("arrangement:${a.id}",a.effectiveDate,declared,0,0,old.sourceEventId,if(declared==null)"ARRANGEMENT_LIMIT_UNKNOWN"else"ARRANGEMENT")
            continue
        }
        val from=sub(-a.principalMinor,old.mode);val to=sub(a.principalMinor,new.mode)
        result+=LimitMovement("arrangement:${a.id}",a.effectiveDate,0,from.first?.let{x->to.first?.let{addExact(x,it)}},from.second?.let{x->to.second?.let{addExact(x,it)}},old.sourceEventId,"ARRANGEMENT")
    }
    return result.sortedBy{it.id}
}

private data class LimitDelta(val total:Long?,val split:Long?,val revo:Long?,val issues:List<String>)
private fun delta(anchor:List<LimitMovement>,current:List<LimitMovement>,basisDate:String):LimitDelta {
    val now=current.associateBy{it.id}
    if(anchor.any{old->now[old.id]?.let{it.date==old.date&&it.total==old.total&&it.kind==old.kind}!=true})return LimitDelta(null,null,null,listOf("limit.basisChanged"))
    val ids=anchor.map{it.id}.toSet();val added=current.filter{it.id !in ids}
    if(added.any{it.date<basisDate})return LimitDelta(null,null,null,listOf("limit.backdated"))
    fun sum(value:(LimitMovement)->Long?):Long? {var n=0L;for(m in added)n=addExact(n,value(m)?:return null);return n}
    return LimitDelta(sum{it.total},sum{it.installment},sum{it.revolving},buildList{if(added.any{it.total==null})add("limit.unknownPrincipal");if(added.any{it.installment==null||it.revolving==null})add("limit.unknownMode")})
}
private fun amount(capacity:Long?,used:Long?)=LimitAmount(capacity,used,if(capacity==null||used==null)null else subtractExact(capacity,used).coerceAtLeast(0),if(capacity==null||used==null)null else subtractExact(used,capacity).coerceAtLeast(0))
private fun plus(basis:Long?,change:Long?)=if(basis==null||change==null)null else addExact(basis,change)
private fun constrained(a:LimitAmount,total:LimitAmount):LimitAmount=a.copy(availableMinor=if(a.availableMinor==null||total.availableMinor==null)null else minOf(a.availableMinor,total.availableMinor))
fun accountCreditLimit(s:BookSnapshot,f:FinancialGraph,l:CreditLimitGraph,accountId:String,asOf:String):AccountCreditLimit {
    val config=l.currentConfig(accountId,asOf)
    if(config==null)return AccountCreditLimit(null,amount(null,null),amount(null,null),amount(null,null),listOf("limit.config"),null,null)
    val movements=creditLimitMovements(s,f,l,accountId,asOf,config.recovery,config.usageStart,config.recoveryDelayDays)
    val anchor=Json.decodeFromString<LimitAnchor>(config.basisJson)
    val change=delta(anchor.movements,movements,config.basisDate)
    val issues=change.issues.filterNot{it=="limit.unknownMode"&&(config.sharing==CreditLimitSharing.TOTAL_ONLY.name||s.accounts.first{a->a.id==accountId}.type==AccountType.LOAN.name)}.toMutableList()
    val supported=config.usageBasis==CreditLimitUsageBasis.PRINCIPAL_ONLY.name
    if(!supported)issues+="limit.platformOnly"
    if(config.recovery==null)issues+="limit.recovery"
    if(config.usageStart==null)issues+="limit.usageStart"
    val known=supported&&config.recovery!=null&&config.usageStart!=null
    val totalUsed=if(known)plus(config.usedTotalMinor,change.total)else null
    val splitUsed=if(known)plus(config.usedInstallmentMinor,change.split)else null
    val revoUsed=if(known)plus(config.usedRevolvingMinor,change.revo)else null
    if(listOfNotNull(totalUsed,splitUsed,revoUsed).any{it<0})issues+="limit.negativeUsage"
    val total=amount(config.totalMinor,totalUsed?.takeIf{it>=0})
    val joint=amount(config.combinedMinor,if(splitUsed==null||revoUsed==null||splitUsed<0||revoUsed<0)null else addExact(splitUsed,revoUsed))
    val split=when(config.sharing){CreditLimitSharing.TOTAL_ONLY.name->total;CreditLimitSharing.SHARED_SUBPOOL.name->constrained(joint,total);CreditLimitSharing.SEPARATE_CAPS.name->constrained(amount(config.installmentMinor,splitUsed?.takeIf{it>=0}),total);else->amount(null,splitUsed)}
    val revo=when(config.sharing){CreditLimitSharing.TOTAL_ONLY.name->total;CreditLimitSharing.SHARED_SUBPOOL.name->constrained(joint,total);CreditLimitSharing.SEPARATE_CAPS.name->constrained(amount(config.revolvingMinor,revoUsed?.takeIf{it>=0}),total);else->amount(null,revoUsed)}
    val groupId=l.members.firstOrNull{it.accountId==accountId}?.groupId
    val shared=groupId?.let{groupCreditLimit(s,f,l,it,asOf).total.availableMinor}
    val effective=if(groupId==null)total.availableMinor else if(shared==null||total.availableMinor==null)null else minOf(shared,total.availableMinor)
    fun sharedConstraint(value:LimitAmount)=if(groupId==null)value else value.copy(availableMinor=if(shared==null||value.availableMinor==null)null else minOf(shared,value.availableMinor))
    return AccountCreditLimit(config,total,sharedConstraint(split),sharedConstraint(revo),issues.distinct(),groupId,effective)
}
fun groupCreditLimit(s:BookSnapshot,f:FinancialGraph,l:CreditLimitGraph,groupId:String,asOf:String):GroupCreditLimit {
    val group=l.groups.first{it.id==groupId};val anchor=Json.decodeFromString<GroupLimitAnchor>(group.basisJson)
    val members=l.members.filter{it.groupId==groupId}.map{it.accountId}.sorted()
    val issues=mutableListOf<String>();var change:Long?=0
    if(members!=anchor.configs.keys.sorted())issues+="limit.groupChanged"
    for(id in members) {
        val config=l.currentConfig(id,asOf)
        if(config?.id!=anchor.configs[id]||config?.usageBasis!=CreditLimitUsageBasis.PRINCIPAL_ONLY.name||config.recovery==null||config.usageStart==null){issues+="limit.groupChanged";change=null;continue}
        val d=delta(anchor.movements[id].orEmpty(),creditLimitMovements(s,f,l,id,asOf,config.recovery,config.usageStart,config.recoveryDelayDays),group.basisDate)
        issues+=d.issues.filterNot{it=="limit.unknownMode"};change=change?.let{n->d.total?.let{addExact(n,it)}}
    }
    val used=if(issues.any{it=="limit.groupChanged"})null else plus(group.usedMinor,change)
    if(used?.let{it<0}==true)issues+="limit.negativeUsage"
    return GroupCreditLimit(group,amount(group.totalMinor,used?.takeIf{it>=0}),issues.distinct())
}
