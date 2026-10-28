package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.YearMonth

/** ACCOUNT_RULE is resolved once at purchase, with the immutable rule version retained. */
enum class CardPaymentChoice { SINGLE, BONUS, DEFERRED, INSTALLMENT, REVOLVING, ACCOUNT_RULE }
internal fun List<FinancialRuleEntity>.cardRule(accountId:String,date:String,mode:String)=filter{it.accountId==accountId&&it.mode==mode&&it.effectiveFrom<=date}
    .maxWithOrNull(compareBy<FinancialRuleEntity>{it.effectiveFrom}.thenBy{it.version})
internal fun retainCardPayment(old:EventEntity?,draft:EntryDraft)=old!=null&&!draft.cardPaymentRecheck&&old.accountId==draft.accountId&&old.date==draft.date&&old.cardPaymentChoice==draft.cardPaymentChoice
internal suspend fun LedgerRepository.cardPayment(event:EventEntity,draft:EntryDraft,old:EventEntity?,account:AccountEntity):EventEntity {
    if(event.kind!=EntryKind.EXPENSE.name||account.type!=AccountType.CREDIT.name)return event
    val choice=draft.cardPaymentChoice?.let{CardPaymentChoice.valueOf(it)}?:return event
    val periods=if(choice==CardPaymentChoice.INSTALLMENT)draft.cardPaymentPeriods.toIntOrNull()?.also{require(it>0){"分期次数须大于0 / 分割回数は0より大きくしてください"}} else null
    if(choice==CardPaymentChoice.INSTALLMENT)require(periods!=null){"请填写分期次数 / 分割回数を入力してください"}
    if(choice==CardPaymentChoice.ACCOUNT_RULE&&retainCardPayment(old,draft))return event.copy(cardPaymentChoice=choice.name,cardPaymentMode=old!!.cardPaymentMode,cardPaymentRuleId=old.cardPaymentRuleId)
    val row=if(choice==CardPaymentChoice.ACCOUNT_RULE)db.financialDao().rules(event.bookId,account.id).cardRule(account.id,event.date,FinanceMode.REVOLVING.name)else null
    val automatic=row?.let{json.decodeFromString<FinancialRuleVersion>(it.json).revolving?.automatic}
    val mode=if(choice==CardPaymentChoice.ACCOUNT_RULE)when(automatic){true->FinanceMode.REVOLVING.name;false->FinanceMode.SINGLE.name;null->null}else if(choice in listOf(CardPaymentChoice.BONUS,CardPaymentChoice.DEFERRED))FinanceMode.SINGLE.name else choice.name
    val single=if(choice in listOf(CardPaymentChoice.BONUS,CardPaymentChoice.DEFERRED))db.financialDao().rules(event.bookId,account.id).cardRule(account.id,event.date,"SINGLE")?.let{json.decodeFromString<FinancialRuleVersion>(it.json)}else null
    var month:String?=null;var fee:Long?=null
    if(choice==CardPaymentChoice.BONUS) {
        val date=single?.takeIf{bonusPaymentIssues(it.bonusPayments).isEmpty()}?.let{bonusPaymentDate(LocalDate.parse(event.date),it.bonusPayments)}
        require(date!=null){"需补齐ボーナス払い设置 / ボーナス払い設定の補完が必要です"}
        month=YearMonth.from(date).toString()
    }
    if(choice==CardPaymentChoice.DEFERRED) {
        require(single?.billing?.issues()?.isEmpty()==true){"需补齐一次付规则 / 一括払い条件の補完が必要です"}
        month=YearMonth.parse(draft.cardPaymentMonth).toString()
        deferredPaymentDate(LocalDate.parse(event.date),YearMonth.parse(month),single.billing!!)
        fee=draft.cardPaymentFee.takeIf{it.isNotBlank()}?.let{Money.parse(it,Currency.valueOf(account.currency)).minor}
        require(fee==null||fee>=0){"手续费不能为负 / 手数料は0以上にしてください"}
    }
    return event.copy(cardPaymentChoice=choice.name,cardPaymentMode=mode,cardPaymentRuleId=row?.id?:single?.id,cardPaymentPeriods=periods,cardPaymentMonth=month,cardPaymentFeeMinor=fee)
}

internal fun recordedSinglePaymentDate(event:EventEntity,rule:FinancialRuleVersion):LocalDate=when(event.cardPaymentChoice) {
    "BONUS"->requireNotNull(bonusPaymentDate(LocalDate.parse(event.date),rule.bonusPayments)){"需补齐ボーナス払い设置 / ボーナス払い設定の補完が必要です"}
    "DEFERRED"->deferredPaymentDate(LocalDate.parse(event.date),YearMonth.parse(event.cardPaymentMonth!!),rule.billing!!)
    else->billingDates(LocalDate.parse(event.date),rule.billing!!).paymentDate
}

/** A local obligation only; writing it never creates money, income or a second purchase. */
internal suspend fun LedgerRepository.refreshCardEntryPrediction(event:EventEntity) {
    if(event.kind!=EntryKind.EXPENSE.name)return
    var state=financialReviewState(event.bookId)
    val account=state.ledger.accounts.first{it.id==event.accountId}
    val existing=state.finance.plans.filter{it.active&&it.sourceEventId==event.id}
    var old=existing.singleOrNull()
    if(existing.isNotEmpty()&&(old?.origin!="CARD_ENTRY"||state.finance.arrangements.any{it.originalPlanId==old!!.id||it.targetPlanId==old.id}))return
    if(old!=null) {
        val bills=state.finance.bills.filter{it.planId==old!!.id&&it.active}
        val ids=bills.map{it.id}.toSet()
        val protected=bills.any{it.confirmed||it.date<LocalDate.now(clock).toString()}||state.finance.repaymentAllocations.any{it.billId in ids}
        if(protected)return // Preserve real/overdue obligations; their explicit review path remains available.
        val expectedCount=if(event.cardPaymentMode=="SINGLE")1 else event.cardPaymentPeriods
        if(old.mode!=event.cardPaymentMode||old.accountId!=account.id||expectedCount!=bills.size||event.historicalIncluded||account.type!=AccountType.CREDIT.name) {
            val retired=old.copy(active=false,revision=Math.addExact(old.revision,1))
            archiveExistingPlan(old,bills)
            val archived=bills.map{it.copy(active=false,revision=Math.addExact(it.revision,1))}
            db.financialDao().updatePlan(retired);archived.forEach{db.financialDao().updateBill(it)}
            archiveFinancialPlan(retired,archived,"SOURCE_PAYMENT_CORRECTED",event.id)
            old=null;state=financialReviewState(event.bookId)
        }
    }
    if(event.historicalIncluded||account.type!=AccountType.CREDIT.name||event.cardPaymentMode !in listOf("SINGLE","INSTALLMENT"))return
    val mode=FinanceMode.valueOf(event.cardPaymentMode!!)
    val row=state.finance.rules.cardRule(account.id,event.date,mode.name)?:return
    val rule=json.decodeFromString<FinancialRuleVersion>(row.json)
    if(rule.readiness()!=RuleReadiness.Complete)return
    val billing=rule.billing?:return
    if(state.ledger.accounts.none{it.id==billing.paymentAccountId&&it.active&&!AccountType.valueOf(it.type).debt})return
    val count=if(mode==FinanceMode.SINGLE)1 else event.cardPaymentPeriods?:return
    if(mode==FinanceMode.INSTALLMENT&&count !in rule.installment!!.supportedPeriods)return
    val first=recordedSinglePaymentDate(event,rule)
    val dates=if(count==1)listOf(first.toString())else (0 until count).map{dayInMonth(YearMonth.from(first).plusMonths(it.toLong()),billing.paymentDay!!).toString()}
    if(state.ledger.financialSourcePrincipal(event.id,account.id)?.minor?.let{it>0}!=true)return
    val input=FinancialPredictionInput(account.id,event.merchant.ifBlank{event.note.ifBlank{event.date}},mode,FinancialScheduleTerms(event.date,dates,additionalFees=event.cardPaymentFeeMinor?.let{listOf(AdditionalPeriodFee(1,it))}.orEmpty()),sourceEventId=event.id,planId=old?.id,currentArrangementConfirmed=true,additionalFeeUnknown=event.cardPaymentChoice=="DEFERRED"&&event.cardPaymentFeeMinor==null)
    val review=prepareFinancialPrediction(state,input)
    if(review.calculation is Calculation.Ready)writeFinancialPrediction(review,"SOURCE_UPDATED",event.id,origin="CARD_ENTRY")
}
