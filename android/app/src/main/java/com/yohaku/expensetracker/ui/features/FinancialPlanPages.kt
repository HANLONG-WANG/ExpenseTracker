package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun FinancialPlanDetail(s:BookSnapshot, finance:FinancialGraph, plan:FinancialPlanEntity, vm:AppViewModel,
    onPeriod:(String)->Unit,onHistory:(String)->Unit,onPoolPayment:(String)->Unit,onCompare:(String)->Unit,onCalculate:(String)->Unit,onReconcile:(String)->Unit) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val stale=vm.repository.financialPredictionStale(s,finance,plan)
    var progress by rememberSaveable(plan.id){mutableStateOf(false)}
    Column(Modifier.testTag("finance-plan-detail-${plan.id}"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(plan.active)FinancialPlanSummary(s,finance,plan,vm)else {
            YText(plan.name,18)
            YText(word("已替换计划；当时的期次与金额请查看版本历史。","置換済みの予定です。当時の各回・金額はバージョン履歴で確認できます。"),12,color=LocalYohaku.current.muted)
        }
        if(plan.active) {
        YButton(word("完整进度与待付组成","進捗・未払内訳")+if(progress)" −"else" +",{progress=!progress},Modifier.testTag("finance-progress-open-${plan.id}"))
        if(progress)FinancialPlanProgressView(s,finance,plan,java.time.LocalDate.now(vm.repository.clock).toString(),stale)
        ThemeSection(word("全部期次","全回の予定"),"SCHEDULE")
        finance.bills.filter{it.planId==plan.id&&it.active}.sortedBy{it.number}.forEach{bill->
            val outstanding=s.billOutstanding(bill)
            FinancialPeriodRow(bill.number,bill.date,if(bill.hasUnknownPredictedFee())word("合计未知","合計不明")else Money(Currency.valueOf(plan.currency),outstanding.totalMinor).display(),
                (if(bill.confirmed)word("平台已确认","返済先確定")else if(stale)word("原预测待重算","元予測・要再計算")else word("预测","予測"))+" · "+(if(outstanding.settled)word("已结清","精算済み")else word("待付","未払い")),
                "finance-plan-period-${bill.id}",{onPeriod(bill.id)})
        }
        }
        ThemeSection(word("调整与历史","変更・履歴"),"ACTIONS")
            YActionRow {
                YButton(word("计划版本与变化","予定の履歴と変化"),{onHistory(plan.id)},Modifier.testTag("finance-plan-history-${plan.id}"),enabled=!busy)
                if(plan.active&&plan.origin=="POOL")YButton(word("调整共同月付款","共通月払いを変更"),{onPoolPayment(plan.id)},Modifier.testTag("finance-pool-payment-${plan.id}"),enabled=!busy)
                if(plan.active&&(plan.origin=="POOL"||plan.origin!="POOL_RETAINED"&&finance.arrangements.none{it.originalPlanId==plan.id}))YButton(word("比较付款方案","支払プランを比較"),{onCompare(plan.id)},Modifier.testTag("finance-compare-plan-${plan.id}"),enabled=!busy)
            }
            if(vm.repository.financialPredictionStale(s,finance,plan))YText(word("来源、清偿、账期或规则已变化；以下保留原预测，请核查并重算。","対象・返済・請求期間・条件が変わりました。以下は元の予測です。確認して再計算してください。"),12,Modifier.testTag("prediction-stale-${plan.id}"),color=LocalYohaku.current.warm)
            if(plan.active&&(plan.origin=="POOL"||plan.origin!="POOL_RETAINED"&&finance.arrangements.none{it.originalPlanId==plan.id}))YButton(if(plan.termsJson==null)word("用规则核对这份计划","条件でこのプランを検算")else word("重算未确认预测","未確定の予測を再計算"),{onCalculate(plan.id)},Modifier.testTag("finance-recalculate-${plan.id}"),enabled=!busy)
        finance.arrangements.filter{a->(a.targetPlanId==plan.id||a.originalPlanId==plan.id||finance.reconciliationPool(finance.plans.first{it.id==a.targetPlanId})?.id==plan.id)}.forEach{a->Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            val original=finance.plans.first{it.id==a.originalPlanId};val target=finance.plans.first{it.id==a.targetPlanId}
            YText(word("已办理变更","手続き済み変更")+" · "+a.effectiveDate,16)
            YText(financeLabel(original.mode)+" → "+financeLabel(target.mode),12)
            if(vm.repository.arrangementBaselineIssue(s,finance,target)!=null)YText(word("来源、退款或清偿已变化，请重核当前安排。","元データ・返金・返済に変更があります。現在の予定を再確認してください。"),12,color=LocalYohaku.current.warm)
            if(target.origin!="POOL_RETAINED"||finance.reconciliationPool(target)!=null)YButton(word("重核来源 / 退款 / 清偿","元データ・返金・返済を再確認"),{onReconcile(target.id)},Modifier.testTag("finance-reconcile-${target.id}"),enabled=!busy)
            YButton(word("查看原安排版本","元の予定の履歴を見る"),{onHistory(original.id)},enabled=!busy)
            YButton(if(target.origin=="POOL_MEMBER")word("查看并入记录","統合の記録を見る")else word("查看新安排版本","新しい予定の履歴を見る"),{onHistory(target.id)},Modifier.testTag("finance-arrangement-history-${a.id}"),enabled=!busy)
        }}
    }
}

@Composable internal fun FinancialPlanPeriod(s:BookSnapshot,finance:FinancialGraph,plan:FinancialPlanEntity,bill:FinancialBillEntity,vm:AppViewModel,onPay:(String)->Unit,onConfirm:(String)->Unit,onRecord:(EventEntity)->Unit,onPayment:(EventEntity)->Unit) {
    val currency=Currency.valueOf(plan.currency)
    val busy by vm.busy.collectAsStateWithLifecycle()
    val stale=vm.repository.financialPredictionStale(s,finance,plan)
    val current=plan.termsJson?.let{vm.repository.json.decodeFromString<FinancialPredictionInput>(it)}
    Column(Modifier.testTag("finance-bill-${bill.id}"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                FinancialBillFacts(bill,currency,showAmountStatus=false)
                val manualReview=plan.reconciliation()?.input?.principalMinor==0L
                FinancialRemainingAfterBill(bill,currency,stale||manualReview)
                val outstanding=s.billOutstanding(bill)
                RecordFacts(listOf(word("已实际清偿本金 / 费用","実返済済みの元金・費用") to (Money(currency,outstanding.paidPrincipalMinor).display()+" / "+Money(currency,outstanding.paidFeeMinor).display())))
                RecordFacts(listOf(word("实际清偿后待付","実返済後の請求残額") to Money(currency,outstanding.totalMinor).display()))
                if(plan.reconciliation()?.input?.bills?.any{it.billId==bill.id}==true)RecordFacts(listOf(word("重核后待付本金 / 费用","再確認後の未払元金・手数料") to ((outstanding.principalMinor?.let{Money(currency,it).display()}?:word("未知","不明"))+" / "+(outstanding.feeMinor?.let{Money(currency,it).display()}?:word("未知","不明")))))
                s.financialAccountSummary(finance,plan.accountId,java.time.LocalDate.now(vm.repository.clock).toString(),if(stale)setOf(plan.id)else emptySet()).rows.firstOrNull{it.bill.id==bill.id}?.let{row->
                    FinancialBillPayment(s,row,onPay,onPayment,"period")
                }
                current?.projections?.firstOrNull{it.number==bill.number}?.let{FinancialProjectionFacts(it.basis,currency)}
                if(plan.termsJson!=null&&bill.basisJson!=null)FinancialPredictionBasis(bill,finance,currency,vm,s,historical=manualReview||current?.facts?.retainedBills?.any{it.id==bill.id}==true)
                plan.sourceEventId?.let{id->s.events.firstOrNull{it.id==id}?.let{event->
                    YText(word("来源：","対象：")+event.date+" · "+event.merchant,12)
                    if(event.deletedAt==null)YButton(word("查看原记录","元の記録を見る"),{onRecord(event)},Modifier.testTag("finance-period-source-${bill.id}"))
                }}
                YText(word("付款账户：","引落口座：")+(s.accounts.firstOrNull{it.id==bill.paymentAccountId}?.name?:word("未确定","未定")),11)
                YButton(if(bill.confirmed)word("明确调整这期账单","この回の請求を明示的に修正")else word("确认平台账单","返済先の請求を確定"),{onConfirm(bill.id)},Modifier.testTag("finance-confirm-bill-${bill.id}"),enabled=!busy)
                if(bill.confirmed&&bill.ruleVersionId!=null&&bill.predictedTotalMinor!=null)YButton(word("与原预测核对规则","元の予測で条件を検証"),{vm.execute{vm.repository.validateFinancialRule(s.book.id,bill.id)}},Modifier.testTag("finance-validate-bill-${bill.id}"),enabled=!busy)
    }
}
