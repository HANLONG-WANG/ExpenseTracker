package com.yohaku.expensetracker.ui.features

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.domain.finance.FinancialCalculationBasis
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

internal fun FinancialBillEntity.hasUnknownPredictedFee() = !confirmed && basisJson?.let{kotlinx.serialization.json.Json.decodeFromString<FinancialCalculationBasis>(it).additionalFeeUnknown}==true

@Composable internal fun FinancialPlanSummary(s:BookSnapshot,f:FinancialGraph,plan:FinancialPlanEntity,vm:AppViewModel,onOpen:(()->Unit)?=null) {
    val today=java.time.LocalDate.now(vm.repository.clock).toString()
    val stale=vm.repository.financialPredictionStale(s,f,plan)
    val p=s.financialPlanProgress(f,plan,today,stale)
    val unpaid=f.bills.filter{it.planId==plan.id&&it.active&&s.billOutstanding(it).totalMinor>0}
    val nextDate=unpaid.minOfOrNull{it.date}
    val next=unpaid.filter{it.date==nextDate}
    val currency=Currency.valueOf(plan.currency)
    val unknown=word("未知","不明")
    fun amount(n:Long?)=n?.let{Money(currency,it).display()}?:unknown
    FinancialPlanRow(plan.name,financeLabel(plan.mode),if(next.isEmpty())word("无待付","未払なし")else (if(stale)word("原预测 · ","元予測・")else "")+amount(if(next.any{it.hasUnknownPredictedFee()})null else next.sumOf{s.billOutstanding(it).totalMinor}),
        word("下次付款：","次の支払：")+(nextDate?:"—")+
            (plan.sourceEventId?.let{id->s.events.firstOrNull{it.id==id}}?.let{event->"\n"+word("来源：","対象：")+event.date+" · "+event.merchant.ifBlank{event.note}}?:""),
        word("已列未清 ${p.unpaidPeriods}/${p.listedPeriods} 期","記載分の未払 ${p.unpaidPeriods}/${p.listedPeriods} 回")+" · "+(if(p.complete)word("完整安排","全期間")else word("部分安排","一部予定"))+"\n"+
            word("已列待付本金 / 费用：","記載済みの未払元金・費用：")+amount(p.principalMinor)+" / "+amount(if(unpaid.any{it.hasUnknownPredictedFee()})null else p.feeMinor),
        listOfNotNull(if(stale)word("待重算","要再計算")else null,if(p.overduePeriods>0)word("逾期 ${p.overduePeriods} 期","延滞 ${p.overduePeriods} 回")else null,
            if(p.pendingMinor==null||p.pendingMinor>0)word("已付待确认：","支払済・確認待ち：")+amount(p.pendingMinor)else null,
            if(p.needsReconciliation)word("分配待核对","充当を要確認")else null).joinToString(" · ").ifEmpty{word("平台确认 ${p.confirmedPeriods} 期 · 预测 ${p.predictedPeriods} 期","返済先確定 ${p.confirmedPeriods} 回・予測 ${p.predictedPeriods} 回")},
        if(onOpen!=null)"finance-plan-row-${plan.id}"else "finance-plan-summary-${plan.id}",onOpen)
}

@Composable internal fun FinancialAddArrangement(account:AccountEntity,onClose:()->Unit,onManual:()->Unit,onPrediction:()->Unit,onPool:()->Unit,contract:Boolean=false) {
    YSheet(word("添加还款安排","返済予定を追加"),onClose) {
        YText(if(account.type==AccountType.LOAN.name)word("固定合同逐笔安排；循环借入使用账户共同还款计划。","固定契約は個別に予定を作成。循環借入は口座共通の返済予定を使用します。")else word("选择已知账单、规则预测或账户共同循环还款。","既知の請求、条件による予測、口座共通のリボ返済から選びます。"),12)
        YButton(word("录入已知账单","既知の請求を入力"),onManual,Modifier.testTag("finance-add-plan"))
        YText(word("平台已给出日期和金额，无需计算规则。","返済先から日付・金額が提示済み。計算条件は不要です。"),11)
        YButton(word("按规则生成预测","条件から予測を作成"),onPrediction,Modifier.testTag("finance-add-prediction"))
        YText(word("按已保存规则计算，保存前核对预测。","保存済みの条件で計算し、保存前に予測を確認します。"),11)
        if(!contract){YButton(word("管理循环还款","リボ・循環返済を管理"),onPool,Modifier.testTag("finance-pool-open"));YText(word("维护共同月付款与循环还款计划，不绑定单一合同。","共通の月払いとリボ・循環返済プランを管理。単一契約には関連付けません。"),11)}
    }
}

/** Mirrors the existing link transaction, including current links and applied payments. */
internal fun contractPlanCandidates(s:BookSnapshot,f:FinancialGraph,c:DebtContractEntity):List<FinancialPlanEntity> = f.plans.filter{p->
    p.accountId==c.accountId&&p.active&&p.origin!="POOL"&&p.sourceEventId==c.fundingEventId&&p.mode==c.mode&&
        f.contractPlans.none{link->link.contractId==c.id&&link.planId!=p.id&&f.plans.any{it.id==link.planId&&it.active&&f.arrangements.none{a->a.originalPlanId==it.id}}}&&
        (f.contractPlans.firstOrNull{it.planId==p.id}?.contractId.let{it==null||it==c.id}||s.appliedRepaymentAllocations().none{a->f.bills.any{it.planId==p.id&&it.id==a.billId}})
}

@Composable internal fun FinancialContractPlanPicker(s:BookSnapshot,f:FinancialGraph,c:DebtContractEntity,vm:AppViewModel,created:String?,onClose:()->Unit,onCreate:()->Unit,onLink:(String)->Unit) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val candidates=contractPlanCandidates(s,f,c)
    YSheet(word("关联已有计划","既存プランを関連付け"),onClose) {
        YText(c.name,18)
        YText(financeLabel(c.mode)+" · "+(c.fundingEventId?.let{id->s.events.firstOrNull{it.id==id}?.let{it.date+" · "+it.merchant}}?:word("期初已有合同","期首の既存契約")),12)
        YText(word("只列出同账户、同来源、同模式的有效计划；循环还款计划不关联单一合同。已有当前关联或清偿事实时仍遵守原关联限制。","同じ口座・対象・方式の有効なプランのみ表示。リボ・循環返済プランは単一契約に関連付けません。適用中の関連や返済記録がある場合は既存の制限に従います。"),11)
        if(created!=null)YText(word("计划已保存，尚未关联。请选择新计划确认关联；关闭后计划仍保留。","プランは保存済み・未関連です。新しいプランを選んで関連付けてください。閉じてもプランは保持されます。"),12,Modifier.testTag("finance-contract-saved"),color=LocalYohaku.current.accent)
        if(candidates.isEmpty())YText(word("暂无可关联计划","関連付けできるプランがありません"),14,Modifier.testTag("finance-contract-link-empty"))
        candidates.sortedByDescending{it.id==created}.forEach{p->
            YButton((if(p.id==created)word("新建 · ","新規・")else "")+p.name+" · "+financeLabel(p.mode)+" · "+word("确认关联","関連付ける"),{onLink(p.id)},Modifier.testTag("finance-contract-candidate-${p.id}"),enabled=!busy)
            if(p.id!=created&&f.contractPlans.none{it.planId==p.id&&it.contractId==c.id})YText(word("计划已保存，尚未关联","プランは保存済み・未関連です"),11,color=LocalYohaku.current.accent)
            val periods=f.bills.count{it.planId==p.id&&it.active}
            YText(if(p.id==created&&periods==0)word("正在读取期次…","各回の予定を読込中…")else word("已列 $periods 期","記載 $periods 回")+" · "+(if(p.complete)word("完整安排","全期間")else word("部分安排","一部予定")),11)
        }
        YButton(word("为此合同建立计划","この契約のプランを作成"),onCreate,Modifier.testTag("finance-contract-create-plan"),primary=candidates.isEmpty(),enabled=!busy)
    }
}
