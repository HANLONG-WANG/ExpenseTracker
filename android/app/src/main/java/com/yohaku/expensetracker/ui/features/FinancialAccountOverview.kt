package com.yohaku.expensetracker.ui.features

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import java.time.*

@Composable private fun financialAmount(c:Currency,n:Long?)=n?.let{Money(c,it).display()}?:word("未知","不明")
@Composable private fun recordedPaymentLabel(s:BookSnapshot,e:EventEntity):String {
    val mode=e.cardPaymentMode?:s.contracts.firstOrNull{it.fundingEventId==e.id}?.mode
    return if(mode==null)cardPaymentLabel(null)else financeLabel(mode)
}
@Composable internal fun FinancialAccountOverview(s:BookSnapshot,a:AccountEntity,f:FinancialGraph,vm:AppViewModel,onStatement:(String)->Unit,onRepay:(String?)->Unit,onCompare:(String)->Unit,onRules:()->Unit,onPlan:()->Unit,onRecord:(EventEntity)->Unit,onMovements:()->Unit={},onFund:((String,String?)->Unit)?=null) {
    val date by vm.today.collectAsStateWithLifecycle();val today=date.toString();val c=Currency.valueOf(a.currency)
    val stale=f.plans.filter{vm.repository.financialPredictionStale(s,f,it)}.map{it.id}.toSet()
    val summary=s.financialAccountSummary(f,a.id,today,stale)
    val totals=summary.next.statementTotals()
    val density=androidx.compose.ui.platform.LocalDensity.current
    val window=androidx.compose.ui.platform.LocalWindowInfo.current
    val compact=with(density){window.containerSize.width.toDp()<=360.dp}||density.fontScale>1.1f
    val graph by vm.creditLimitState.collectAsStateWithLifecycle()
    val limits=graph?.takeIf{it.book.id==s.book.id}?.let{accountCreditLimit(s,f,it,a.id,today)}
    val rateRevision by vm.referenceRates.cacheRevision.collectAsStateWithLifecycle()
    val rates by produceState<List<com.yohaku.expensetracker.domain.currency.ReferenceRateSnapshot>>(emptyList(),s,f,rateRevision){value=vm.referenceRates.store.referenceRateCache().rates;value=cashReferenceRates(s,f,vm.referenceRates)}
    val through=maxOf(date,summary.nextDate?.let(LocalDate::parse)?:date)
    val timeline=s.cashTimeline(f,date,through,stale,rates)
    if(a.type==AccountType.LOAN.name)FinancialLoanNext(s,a,f,summary,limits?.effectiveTotalMinor,stale,onStatement)
    if(summary.overdue.isNotEmpty())YText(word("有${summary.overdue.size}期已到期未清偿，请核对付款或平台确认。","期限を過ぎた未返済が${summary.overdue.size}回あります。支払・返済先の確認を見直してください。"),13,Modifier.testTag("finance-overdue"),color=LocalYohaku.current.warm)
    val banks=summary.next.groupBy{it.bill.paymentAccountId}
    val ids=summary.next.map{it.bill.id}.toSet()
    val deadlines=f.rules.filter{it.accountId==a.id}.groupBy{it.mode}.values.mapNotNull{versions->versions.filter{it.effectiveFrom<=today}.maxWithOrNull(compareBy<FinancialRuleEntity>{it.effectiveFrom}.thenBy{it.version})}.flatMap{vm.repository.json.decodeFromString<FinancialRuleVersion>(it.json).deadlines}.filter{it.billId in ids}
    @Composable fun FundingContent() {
    banks.forEach{(id,rows)->
        val bank=s.accounts.firstOrNull{it.id==id&&!AccountType.valueOf(it.type).debt}
        FinancialFundingGroup(a.type==AccountType.CREDIT.name,id) {
            if(a.type==AccountType.CREDIT.name)RecordFacts(listOf(word("扣款账户","引落口座") to (bank?.let{it.name+" · "+it.currency}?:word("尚未确定","未定"))))
            else YText(bank?.name?:word("扣款账户未确定","引落口座未定"),18)
            if(bank==null)YButton(word("补齐扣款安排","引落予定を補完"),onPlan)
            else {
                val bc=Currency.valueOf(bank.currency);val current=s.cashBalanceAt(bank,today)
                val ownNeed=rows.statementTotals().cashRequiredMinor
                val rate=rates.filter{it.base==c&&it.quote==bc&&it.date<=today}.maxByOrNull{it.date}
                val need=if(c==bc)ownNeed else ownNeed?.let{rate?.convert(Money(c,it))?.minor}
                val currentGap=need?.let{Math.subtractExact(it,current).coerceAtLeast(0)}
                val after=timeline.steps.lastOrNull{it.item.accountId==id}
                val fundingFacts=listOf(word("当前实际资金","現在の実資金") to Money(bc,current).display(),word("本次需备款","今回必要な資金") to ((if(c!=bc&&need!=null)"≈ "else"")+financialAmount(bc,need)),word("当前备款缺口","現在の資金不足") to ((if(c!=bc&&need!=null)"≈ "else"")+financialAmount(bc,currentGap)))
                RecordFacts(if(a.type==AccountType.CREDIT.name)fundingFacts.take(1)else fundingFacts)
                if(a.type!=AccountType.CREDIT.name)YText(word("扣款日资金预测","引落日の予測"),14)
                RecordFacts(listOf((if(a.type==AccountType.CREDIT.name)word("不含预计收入，扣款后","見込み収入なしの支払後")else word("基础情景","基本シナリオ")) to ((if(after?.baselineEstimated==true)"≈ "else"")+financialAmount(bc,after?.baselineAfterMinor)),word("含预计收入","収入見込みあり") to ((if(after?.includingEstimated==true)"≈ "else"")+financialAmount(bc,after?.includingIncomeAfterMinor))))
                if(a.type==AccountType.CREDIT.name)RecordFacts(fundingFacts.drop(1))
                if(currentGap?.let{it>0}==true)YText(word("现在：该扣款账户资金不足。其他账户资金不等于已转入。","現在：この引落口座の資金が不足。他口座の残高は振替済み資金ではありません。"),12,color=LocalYohaku.current.warm)
                if(after?.baselineAfterMinor?.let{it<0}==true)YText(word("基础情景预计本账户不足；未到账收入不消除基础风险。","基本シナリオではこの口座が不足する見込み。未入金の収入で基本リスクは消えません。"),12,color=LocalYohaku.current.warm)
                YText(word("含其他账单、周期、在途及主动预留；金额或折算缺失时保持未知。","他の請求・定期予定・振替途中・資金確保を含み、不明な金額・換算は不明のまま表示します。"),11)
                val other=s.accounts.filter{it.active&&!AccountType.valueOf(it.type).debt&&it.id!=bank.id}.sortedByDescending{it.currency==bank.currency}.firstOrNull()
                if(other!=null&&onFund!=null)YButton(word("安排账户转入","口座への振替を記録"),{onFund?.invoke(other.id,bank.id)},Modifier.testTag("finance-fund-transfer-${bank.id}"))
                if(onFund!=null)YLink(word("补记已实际到账收入","実入金の記録を補完"),{onFund(bank.id,null)})
            }
        }
    }
    }
    if(a.type==AccountType.CREDIT.name)ThemeAuditPanel(if(compact)word("先看扣款账户","実際の引落口座")else word("先看实际扣款账户","まず、実際の引落口座"),Modifier.testTag("finance-card-funding")) {
        if(banks.isEmpty())YText(word("尚无已知扣款安排，请补齐账单与扣款账户。","既知の引落予定はありません。請求と引落口座を補完してください。"),12)
        FundingContent()
        RecordFacts(listOf(word("当前未偿本金","現在の未返済元金") to Money(c,s.cashBalanceAt(a,today)).display(),
            word("≈ 可用额度 / 非现金","≈ 利用可能額・現金ではない") to financialAmount(c,limits?.effectiveTotalMinor),
            word("分期 / リボ子额度上限","分割・リボの個別枠上限") to (financialAmount(c,limits?.installment?.capacityMinor)+" / "+financialAmount(c,limits?.revolving?.capacityMinor))))
        val unset=word("未填写","未入力")
        RecordFacts(listOf(FinanceMode.INSTALLMENT,FinanceMode.REVOLVING).map{mode->
            financeLabel(mode.name)+word("变更截止","変更締切") to deadlines.filter{it.changeType==mode}.joinToString(" / "){it.date+" "+it.time+" · "+it.zoneId}.ifBlank{unset}
        })
        YText(word("本地预计与平台确认分开。额度恢复依核对的规则，信用额度不作为现金；账单不再次加到欠款。未安排部分另列，不代表完整债务预测。","ローカル予測と返済先の確定額を区別。枠回復は確認した条件に従い、枠を現金に含めず、請求を残債へ重複加算しません。未設定分は別に示し、残債全体の予測とは限りません。"),11,color=LocalYohaku.current.muted)
        Column(Modifier.fillMaxWidth().testTag("finance-overview-next"),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        YText(word("本期账单组成","当期請求の内訳"),14)

        YText(summary.nextDate?:word("尚无已知待付日期","既知の支払日はありません"),12)
        YText(if(summary.next.isEmpty())"—"else if(summary.next.any{!it.bill.confirmed&&it.plan.id in stale})word("待核对","要確認")else Money(c,totals.totalMinor).display(),19,Modifier.testTag("finance-overview-amount"),number=true)
        if(summary.next.isNotEmpty()) {
            YText(when{totals.predicted==0->word("平台已确认账单","返済先の確定請求");totals.confirmed==0->word("本地预测账单","ローカル予測");else->word("含已确认与本地预测","確定・予測を含みます")},12,color=LocalYohaku.current.accent)
            RecordFacts(listOf(word("待清偿本金","未返済元金") to financialAmount(c,totals.principalMinor),word("待清偿费用","未返済手数料") to financialAmount(c,totals.feeMinor),word("已付待确认","支払済み・確認待ち") to financialAmount(c,totals.pendingMinor),word("仍需备款","今後必要な資金") to financialAmount(c,totals.cashRequiredMinor)))
            YButton(word("查看该月账单","該当月の明細を見る"),{onStatement(summary.nextDate!!.take(7))},Modifier.testTag("finance-next-statement"))
        }
        }
    }else FundingContent()
    val waiting=s.repayments.filter{it.debtAccountId==a.id&&!it.platformConfirmed&&s.visibleEvents.any{e->e.id==it.eventId}}
    if(waiting.isNotEmpty()) {
        ThemeSection(word("已付款待确认","支払済み・確認待ち"),"AWAITING CLEARING")
        YText(word("实际资金已按付款记录扣除。分配到本期的已付金额不再重复备款；尚未分配的付款请核对对应义务。","実資金は支払記録で出金済み。当期に充当した支払額を再度準備せず、未充当分は対象債務を確認してください。"),11)
        waiting.forEach{p->val e=s.visibleEvents.first{it.id==p.eventId}
            RecordFacts(listOf(word("实际付款","実支払") to (e.date+" · "+s.accountMoney(e).display()),word("目标本金","返済先元金") to financialAmount(c,p.principalMinor)))
            YButton(word("核对这笔实际付款","この実支払を確認"),{onRecord(e)},Modifier.testTag("finance-pending-${e.id}"))
        }
    }
    ThemeSection(word("付款与变更","支払と変更"),"PAYMENT & DEADLINES")
    if(deadlines.isEmpty())YText(word("本期支払い変更期限未填写，不推定机构期限。","当期の変更締切は未入力。会社別の締切を推定しません。"),11)
    deadlines.forEach{d->YText(financeLabel(d.changeType.name)+" · "+d.date+" "+d.time+" · "+d.zoneId,12);if(d.instant()<vm.repository.clock.instant())YText(word("期限已过，需核查实际受理结果。","締切を過ぎています。実際の受付結果を確認してください。"),11,color=LocalYohaku.current.warm)}
    summary.next.distinctBy{it.plan.id}.forEach{r->if(r.plan.origin=="POOL"||r.plan.origin!="POOL_RETAINED"&&f.arrangements.none{it.originalPlanId==r.plan.id})YButton(word("比较付款方案","支払プランを比較")+" · "+r.plan.name,{onCompare(r.plan.id)},Modifier.testTag("finance-overview-compare-${r.plan.id}"))}
    YButton(word("记录实际还款","実際の返済を記録"),{onRepay(summary.next.singleOrNull()?.bill?.id)},Modifier.testTag("finance-overview-repay"),primary=true)
    if(a.type==AccountType.LOAN.name)FinancialLoanSchedule(summary,c,stale,onPlan)
    if(summary.unplanned.isNotEmpty()||summary.unassignedOpeningMinor!=0L||summary.reconciliationRemainders.isNotEmpty()) {
        ThemeSection(word("尚未完整安排","予定の未補完分"),"NOT YET SCHEDULED")
        RecordFacts(listOf(word("未安排消费 / 借入","予定のない利用・借入") to summary.unplanned.size.toString(),word("未分配期初本金","未割当ての期首元金") to financialAmount(c,summary.unassignedOpeningMinor)))
        YText(word("这部分不会被已有账单合计掩盖，也不会按零费用或虚构日期补齐。","既存の請求合計でこの分を隠さず、手数料0・架空の日付で補いません。"),11)
        summary.unplanned.forEach{e->
            YText(e.date+" · "+e.merchant.ifBlank{e.note}+" · "+s.accountMoney(e).display()+" · "+recordedPaymentLabel(s,e),12)
            YButton(word("核对原消费 / 借入","元の利用・借入を確認"),{onRecord(e)},Modifier.testTag("finance-unplanned-${e.id}"))
        }
        summary.reconciliationRemainders.forEach{part->YText(word("上次重核仍未安排的来源本金：","前回の再確認で未予定の元金：")+Money(c,part.principalMinor).display()+" · "+f.plans.first{it.id==part.planId}.name,12,Modifier.testTag("finance-reconciliation-unassigned-${part.planId}"))}
        YButton(word("补齐计划与账单","予定・請求を補完"),onPlan,Modifier.testTag("finance-unplanned-open"))
    }
    YButton(word("查看 / 补齐用户规则","条件を確認・補完"),onRules,Modifier.testTag("finance-overview-rules"))
    FinancialLoanModes(s,a,f,onPlan,onMovements)
}

@Composable internal fun FinancialStatements(s:BookSnapshot,a:AccountEntity,f:FinancialGraph,vm:AppViewModel,month:String,onMonth:(String)->Unit,onBill:(String)->Unit,onPlan:()->Unit,onRecord:(EventEntity)->Unit) {
    val today by vm.today.collectAsStateWithLifecycle();val c=Currency.valueOf(a.currency)
    val stale=f.plans.filter{vm.repository.financialPredictionStale(s,f,it)}.map{it.id}.toSet()
    val summary=s.financialAccountSummary(f,a.id,today.toString(),stale);val selected=YearMonth.parse(month)
    val choices=(summary.rows.map{it.bill.date.take(7)}+month).distinct().sortedDescending()
    YSelect(word("还款月份","支払月"),month,choices.map{it to it},onMonth,Modifier.testTag("finance-statement-month"))
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        YButton(word("上个月","前月"),{onMonth(selected.minusMonths(1).toString())},Modifier.weight(1f).testTag("finance-statement-previous"),inline=true)
        YButton(word("下个月","翌月"),{onMonth(selected.plusMonths(1).toString())},Modifier.weight(1f).testTag("finance-statement-next"),inline=true)
    }
    val rows=summary.rows.filter{it.bill.date.startsWith(month)};val total=rows.statementTotals()
    ThemeAuditPanel(month,Modifier.testTag("finance-statement-total")) {
        val known=rows.isNotEmpty()&&rows.none{!it.bill.confirmed&&it.plan.id in stale}
        YText(if(rows.isEmpty())"—"else if(!known)word("待核对","要確認")else Money(c,total.totalMinor).display(),34,Modifier.testTag("finance-statement-amount"),number=known)
        YText(word("按有效扣款日期归属，不按消费月份筛选。","適用された引落日で集計し、利用月では絞り込みません。"),11)
        if(rows.isNotEmpty())RecordFacts(listOf(word("待清偿本金","未返済元金") to financialAmount(c,total.principalMinor),word("待清偿费用","未返済手数料") to financialAmount(c,total.feeMinor),word("已确认 / 预测期数","確定・予測の回数") to "${total.confirmed} / ${total.predicted}",word("仍需备款","今後必要な資金") to financialAmount(c,total.cashRequiredMinor)))
        else YText(word("该月没有已保存账单；不代表没有尚未安排的债务。","この月に保存済みの請求はありません。予定未補完の債務がないことは意味しません。"),12)
    }
    rows.groupBy{it.bill.date}.forEach{(date,bills)->
        ThemeSection(date,"PAYMENT DATE")
        bills.forEach{r->Panel(Modifier.testTag("statement-bill-${r.bill.id}")) {
            YText(r.plan.name,18);YText(financeLabel(r.plan.mode),12);FinancialBillFacts(r.bill,c)
            RecordFacts(listOf(word("实际清偿后剩余","実返済後の残額") to Money(c,r.outstanding.totalMinor).display(),word("已付待确认","支払済み・確認待ち") to financialAmount(c,r.pendingTotalMinor),word("扣款账户","引落口座") to (s.accounts.firstOrNull{it.id==r.bill.paymentAccountId}?.name?:word("未确定","未定"))))
            if(vm.repository.financialPredictionStale(s,f,r.plan))YText(word("原预测需重算，当前仍保留原值。","元の予測は再計算が必要です。現在は元の値を保持しています。"),12,color=LocalYohaku.current.warm)
            YButton(word("核对本期账单","当期の請求を確認"),{onBill(r.bill.id)},Modifier.testTag("statement-confirm-${r.bill.id}"))
        }}
    }
    ThemeSection(word("相关消费","対象の利用明細"),"PURCHASES")
    val poolSources=rows.filter{it.plan.origin=="POOL"}.flatMap{row->poolInput(row.plan)?.facts?.sources.orEmpty().filter{it.principalMinor>0&&it.date<=row.bill.date}.mapNotNull{it.eventId}}
    val sources=(rows.mapNotNull{it.plan.sourceEventId}+poolSources).distinct()
    if(poolSources.isNotEmpty())YText(word("循环池来源仅表示关联；原金额不代表本期逐笔分配。","プールの対象記録は関連のみを示し、元の金額を当期の個別充当額とはみなしません。"),11)
    sources.mapNotNull{id->s.events.firstOrNull{it.id==id}}.forEach{e->Panel {
        YText(e.date+" · "+e.merchant.ifBlank{e.note},14)
        YText(s.accountMoney(e).display()+" · "+recordedPaymentLabel(s,e),14)
        if(e.deletedAt!=null)YText(word("原消费已在回收站，独立账单仍保留。","元の利用はごみ箱にあります。独立した請求は保持しています。"),11,color=LocalYohaku.current.warm)
        else YButton(word("查看原记录","元の記録を見る"),{onRecord(e)},Modifier.testTag("statement-source-${e.id}"))
    }}
    if(summary.unplanned.isNotEmpty())YText(word("另有${summary.unplanned.size}笔未安排记录，尚不能确定还款月份。","予定未補完の記録が別に${summary.unplanned.size}件あり、支払月は未確定です。"),12,color=LocalYohaku.current.warm)
    YButton(word("管理逐期计划","各回の予定を管理"),onPlan,Modifier.testTag("statement-plans"))
}

@Composable private fun FinancialFundingGroup(card:Boolean,id:String?,content:@Composable ColumnScope.()->Unit) {
    val modifier=Modifier.testTag("finance-funding-${id?:"unknown"}")
    if(card)Column(modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
    else Panel(modifier,content=content)
}
