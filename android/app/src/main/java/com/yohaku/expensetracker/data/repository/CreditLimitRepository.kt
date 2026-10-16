package com.yohaku.expensetracker.data.repository

import androidx.room.withTransaction
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.*

@Serializable data class CreditLimitInput(val accountId:String,val totalMinor:Long?,val installmentMinor:Long?,val revolvingMinor:Long?,val sharing:CreditLimitSharing?,val combinedMinor:Long?,
    val effectiveFrom:String,val basisDate:String,val usedTotalMinor:Long?,val usedInstallmentMinor:Long?,val usedRevolvingMinor:Long?,val recovery:LimitRecovery?,val usageBasis:CreditLimitUsageBasis?,val zoneId:String,val usageStart:CreditLimitUsageStart?=null)
@Serializable data class CreditLimitGroupInput(val name:String,val currency:Currency,val totalMinor:Long?,val usedMinor:Long?,val basisDate:String,val accountIds:List<String>,val id:String?=null)
data class CreditLimitRecoveryInput(val accountId:String,val eventId:String,val date:String,val principalMinor:Long,val installmentMinor:Long?,val revolvingMinor:Long?)
@Serializable data class CreditLimitGroupArchive(val group:CreditLimitGroupEntity,val accountIds:List<String>)
class CreditLimitReview internal constructor(internal val before:FinancialReviewState,val config:CreditLimitConfigEntity?,val group:CreditLimitGroupEntity?,val members:List<String>,val recovery:CreditLimitRecoveryEntity?,val observation:CreditLimitObservationEntity?,val estimate:AccountCreditLimit?,val groupEstimate:GroupCreditLimit?)

private fun checkAmounts(vararg amounts:Long?){require(amounts.filterNotNull().all{it>=0}){"额度和占用金额不能为负 / 利用枠・使用額は0以上にしてください"}}
private fun FinancialReviewState.account(id:String)=requireNotNull(ledger.accounts.firstOrNull{it.id==id&&it.active&&AccountType.valueOf(it.type).debt}){"请选择本账本启用的债务账户 / この帳簿の有効な借入口座を選択してください"}
private fun LedgerRepository.today(zone:String)=LocalDate.now(clock.withZone(ZoneId.of(zone))).toString()

suspend fun LedgerRepository.previewCreditLimit(bookId:String,input:CreditLimitInput):CreditLimitReview=db.withTransaction {
    val state=financialReviewState(bookId);val a=state.account(input.accountId)
    checkAmounts(input.totalMinor,input.installmentMinor,input.revolvingMinor,input.combinedMinor,input.usedTotalMinor,input.usedInstallmentMinor,input.usedRevolvingMinor)
    val now=today(input.zoneId);require(LocalDate.parse(input.basisDate)<=LocalDate.parse(now)&&LocalDate.parse(input.effectiveFrom)<=LocalDate.parse(now))
    require(input.basisDate<=input.effectiveFrom){"占用基准不能晚于配置生效日 / 使用額基準日は条件適用日以前にしてください"}
    input.usedTotalMinor?.let{total->require(listOfNotNull(input.usedInstallmentMinor,input.usedRevolvingMinor).all{it<=total})}
    if(input.usedTotalMinor!=null&&input.usedInstallmentMinor!=null&&input.usedRevolvingMinor!=null)require(Math.addExact(input.usedInstallmentMinor,input.usedRevolvingMinor)<=input.usedTotalMinor){"分期与リボ占用合计不能超过总占用 / 分割・リボ使用額の合計は総使用額以内にしてください"}
    require(a.type==AccountType.CREDIT.name||input.installmentMinor==null&&input.revolvingMinor==null&&input.combinedMinor==null&&input.usedInstallmentMinor==null&&input.usedRevolvingMinor==null)
    val version=(state.limits.configs.filter{it.accountId==a.id}.maxOfOrNull{it.version}?:0)+1
    val basis=creditLimitMovements(state.ledger,state.finance,state.limits,a.id,input.basisDate,input.recovery?.name,input.usageStart?.name)
    val c=CreditLimitConfigEntity(bookId,id(),a.id,version,input.effectiveFrom,a.currency,input.totalMinor,input.installmentMinor,input.revolvingMinor,input.sharing?.name,input.combinedMinor,
        input.basisDate,input.usedTotalMinor,input.usedInstallmentMinor,input.usedRevolvingMinor,json.encodeToString(LimitAnchor(basis)),input.recovery?.name,input.usageBasis?.name,input.usageStart?.name,input.zoneId,clock.millis())
    val next=state.limits.copy(configs=state.limits.configs+c)
    CreditLimitReview(state,c,null,emptyList(),null,null,accountCreditLimit(state.ledger,state.finance,next,a.id,now),null)
}
suspend fun LedgerRepository.previewCreditLimitGroup(bookId:String,input:CreditLimitGroupInput):CreditLimitReview=db.withTransaction {
    val state=financialReviewState(bookId);checkAmounts(input.totalMinor,input.usedMinor)
    require(input.name.isNotBlank()&&input.accountIds.distinct().size==input.accountIds.size&&input.accountIds.size>=2)
    require(LocalDate.parse(input.basisDate)<=LocalDate.now(clock))
    val old=input.id?.let{id->requireNotNull(state.limits.groups.firstOrNull{it.id==id})}
    require(old==null||old.currency==input.currency.name){"共享池币种不可直接改写 / 共通枠の通貨は直接変更できません"}
    val selected=input.accountIds.map{state.account(it)}
    require(selected.all{it.currency==input.currency.name}){"共享额度只组合相同币种账户，不按参考汇率拼接 / 共通枠は同じ通貨の口座のみ指定してください"}
    require(state.limits.members.none{it.accountId in input.accountIds&&it.groupId!=old?.id}){"账户已属于另一共享额度池 / 別の共通枠に所属している口座があります"}
    val configs=selected.associate{it.id to state.limits.currentConfig(it.id,input.basisDate)?.id}
    val movements=selected.associate{a->val config=state.limits.currentConfig(a.id,input.basisDate);a.id to creditLimitMovements(state.ledger,state.finance,state.limits,a.id,input.basisDate,config?.recovery,config?.usageStart)}
    val g=CreditLimitGroupEntity(bookId,old?.id?:id(),input.name.trim(),input.currency.name,input.totalMinor,input.usedMinor,input.basisDate,json.encodeToString(GroupLimitAnchor(configs,movements)),(old?.revision?:0)+1,clock.millis())
    val next=state.limits.copy(groups=state.limits.groups.filter{it.id!=g.id}+g,members=state.limits.members.filter{it.groupId!=g.id}+input.accountIds.map{CreditLimitGroupMemberEntity(bookId,it,g.id)})
    CreditLimitReview(state,null,g,input.accountIds,null,null,null,groupCreditLimit(state.ledger,state.finance,next,g.id,LocalDate.now(clock).toString()))
}
suspend fun LedgerRepository.previewCreditLimitRecovery(bookId:String,input:CreditLimitRecoveryInput):CreditLimitReview=db.withTransaction {
    val state=financialReviewState(bookId);val a=state.account(input.accountId)
    val payment=requireNotNull(limitPayments(state.ledger,state.finance,a.id).firstOrNull{it.eventId==input.eventId})
    val principal=requireNotNull(payment.principalMinor){"请先核对实际归还本金，不能按实付总额恢复额度 / 実返済元金を先に確認してください。出金総額で枠を回復しません"}
    checkAmounts(input.installmentMinor,input.revolvingMinor);require(input.principalMinor>0)
    require(listOfNotNull(input.installmentMinor,input.revolvingMinor).all{it<=input.principalMinor})
    val config=state.limits.currentConfig(a.id,LocalDate.now(clock).toString())
    require(input.date>=payment.date&&LocalDate.parse(input.date)<=LocalDate.parse(today(config?.zoneId?:clock.zone.id)))
    val prior=state.limits.recoveries.filter{it.accountId==a.id&&it.eventId==payment.eventId&&it.voidedAt==null}
    require(prior.all{it.sourceProof==payment.proof}){"已有恢复确认的付款资料已变，请先核查并作废旧确认 / 既存の枠回復確認の支払根拠が変わっています。確認して旧確認を無効にしてください"}
    require(Math.addExact(prior.fold(0L){sum,r->Math.addExact(sum,r.principalMinor)},input.principalMinor)<=principal){"额度恢复不能超过此付款尚未确认恢复的本金 / この支払の未回復確認元金を超える枠回復はできません"}
    payment.installmentMinor?.let{known->input.installmentMinor?.let{n->require(Math.addExact(prior.sumOf{it.installmentMinor?:0},n)<=known)}}
    payment.revolvingMinor?.let{known->input.revolvingMinor?.let{n->require(Math.addExact(prior.sumOf{it.revolvingMinor?:0},n)<=known)}}
    if(input.installmentMinor!=null&&input.revolvingMinor!=null)require(Math.addExact(input.installmentMinor,input.revolvingMinor)<=input.principalMinor)
    require(a.type==AccountType.CREDIT.name||input.installmentMinor==null&&input.revolvingMinor==null)
    val r=CreditLimitRecoveryEntity(bookId,id(),a.id,payment.eventId,input.date,input.principalMinor,input.installmentMinor,input.revolvingMinor,payment.proof,clock.millis())
    val next=state.limits.copy(recoveries=state.limits.recoveries+r)
    CreditLimitReview(state,null,null,emptyList(),r,null,accountCreditLimit(state.ledger,state.finance,next,a.id,LocalDate.now(clock).toString()),null)
}
internal fun LedgerRepository.creditLimitObservationBasis(state:FinancialReviewState,accountId:String?,groupId:String?,date:String):String {
    val ids=accountId?.let{listOf(it)}?:state.limits.members.filter{it.groupId==groupId}.map{it.accountId}.sorted()
    val data=ids.associateWith{id->val c=state.limits.currentConfig(id,date);listOf(c?.id,json.encodeToString(creditLimitMovements(state.ledger,state.finance,state.limits,id,date,c?.recovery,c?.usageStart)))}
    return json.encodeToString(listOf(json.encodeToString(data),groupId?.let{id->state.limits.groups.first{it.id==id}.revision.toString()}))
}
suspend fun LedgerRepository.previewCreditLimitObservation(bookId:String,accountId:String?,groupId:String?,scope:String,availableMinor:Long,observedAt:String):CreditLimitReview=db.withTransaction {
    val state=financialReviewState(bookId);require((accountId!=null)!=(groupId!=null));checkAmounts(availableMinor)
    require(scope in listOf("TOTAL","INSTALLMENT","REVOLVING"));require(groupId==null||scope=="TOTAL")
    accountId?.let{state.account(it)};groupId?.let{id->require(state.limits.groups.any{it.id==id})}
    require(Instant.parse(observedAt)<=clock.instant()){"观测时间不能在未来 / 確認時刻を未来に設定できません"}
    val date=Instant.parse(observedAt).atZone(clock.zone).toLocalDate().toString()
    val obs=CreditLimitObservationEntity(bookId,id(),accountId,groupId,scope,availableMinor,observedAt,creditLimitObservationBasis(state,accountId,groupId,date),clock.millis())
    CreditLimitReview(state,null,null,emptyList(),null,obs,null,null)
}
suspend fun LedgerRepository.commitCreditLimit(review:CreditLimitReview):Boolean=db.withTransaction {
    if(financialReviewState(review.before.ledger.book.id)!=review.before)return@withTransaction false
    val dao=db.creditLimitDao()
    review.config?.let{dao.insertConfig(it)}
    review.group?.let{g->dao.putGroup(g);dao.clearMembers(g.bookId,g.id);dao.insertMembers(review.members.map{CreditLimitGroupMemberEntity(g.bookId,it,g.id)});dao.insertGroupRevision(CreditLimitGroupRevisionEntity(g.bookId,g.id,g.revision,json.encodeToString(CreditLimitGroupArchive(g,review.members)),clock.millis()))}
    review.recovery?.let{dao.insertRecovery(it)};review.observation?.let{dao.insertObservation(it)}
    true
}
suspend fun LedgerRepository.voidCreditLimitRecovery(bookId:String,id:String)=db.withTransaction {
    require(db.creditLimitDao().graph(bookId).first()!!.recoveries.any{it.id==id&&it.voidedAt==null})
    db.creditLimitDao().voidRecovery(bookId,id,clock.millis())
}
