package com.yohaku.expensetracker.ui.features

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.yohaku.expensetracker.data.db.FinancialBillEntity
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun FinancialLedgerPlanImpacts(r:FinancialLedgerReview) {
    r.repaymentInput?.let{input->
        val unallocated=r.after.unallocatedRepaymentPrincipal(input.debtAccountId)
        if(unallocated>0)YText(word("本账户已确认但尚未分配到具体义务的本金：","この口座の確定済み・個別債務に未充当の元金：")+Money(Currency.valueOf(r.after.accounts.first{it.id==input.debtAccountId}.currency),unallocated).display(),12,color=LocalYohaku.current.warm)
    }
    if(r.planImpacts.isEmpty())return
    ThemeSection(word("还款计划影响","返済予定への影響"),"SCHEDULE IMPACT")
    r.planImpacts.forEach{impact->Panel(Modifier.testTag("finance-plan-impact-${impact.original.id}")) {
        var detail by rememberSaveable(impact.original.id){mutableStateOf(false)}
        val currency=Currency.valueOf(impact.original.currency)
        val before=impact.originalBills.filter{it.active}
        val after=impact.nextBills
        fun total(rows:List<FinancialBillEntity>,s:BookSnapshot)=rows.fold(0L){sum,b->Math.addExact(sum,s.billOutstanding(b).totalMinor)}
        fun fees(rows:List<FinancialBillEntity>,s:BookSnapshot):Long? {
            if(rows.any{s.billOutstanding(it).feeMinor==null})return null
            return rows.fold(0L){sum,b->Math.addExact(sum,s.billOutstanding(b).feeMinor!!)}
        }
        val unknown=word("未知","不明")
        fun amount(n:Long?)=n?.let{Money(currency,it).display()}?:unknown
        YText(impact.original.name,18)
        YText(if(impact.updatesPrediction)word("确认后与实际记录一起更新预测","確定時に実記録と予測を一緒に更新")else word("保留原计划；待付金额按实际分配更新","元の予定を保持し、請求残額は実充当に応じて更新"),12,color=if(impact.updatesPrediction)LocalYohaku.current.accent else LocalYohaku.current.muted)
        impact.issue?.let{YText(planImpactIssue(it),11,color=LocalYohaku.current.muted)}
        when(val result=impact.forecast?.calculation) {
            is Calculation.Incomplete->FinancialReadiness(RuleReadiness.Incomplete(result.fields),word("此计划暂不能自动重算","この予定は自動再計算できません"))
            is Calculation.Unsupported->FinancialCalculationResult(result,currency)
            is Calculation.CannotSettle->FinancialCalculationResult(result,currency)
            else->Unit
        }
        if(impact.updatesPrediction&&impact.forecast!!.plan.principalMinor!=impact.original.principalMinor)RecordFacts(listOf(word("计划来源本金","予定の対象元金") to (amount(impact.original.principalMinor)+" → "+amount(impact.forecast.plan.principalMinor))))
        RecordFacts(listOf(word("待付合计","請求残額の合計") to (amount(total(before,r.before.ledger))+" → "+amount(total(after,r.after))),
            word("待付利息及费用","未払利息・手数料") to (amount(fees(before,r.before.ledger))+" → "+amount(fees(after,r.after))),
            word("保存的计划版本","保存する予定の版") to "${impact.original.revision} → ${impact.original.revision+1}"))
        YText(word("确认账单金额与原预测依据保持原值，本次清偿会减少对应待付。","確定請求額・元の予測根拠を保持し、今回の返済で対応する請求残額を減らします。"),11)
        YButton(word("逐期变化","各回の変化")+if(detail)" −"else" +",{detail=!detail},Modifier.testTag("finance-plan-impact-detail-${impact.original.id}"))
        if(detail)(before+after).distinctBy{it.id}.sortedWith(compareBy<FinancialBillEntity>{it.date}.thenBy{it.number}).forEach{bill->
            val old=before.firstOrNull{it.id==bill.id};val next=after.firstOrNull{it.id==bill.id}
            Panel(Modifier.testTag("finance-plan-impact-period-${impact.original.id}-${bill.number}")) {
                YText(word("第${bill.number}期","第${bill.number}回"),14)
                RecordFacts(listOf(word("日期","日付") to ((old?.date?:"—")+" → "+(next?.date?:"—")),
                    word("本金","元金") to ((old?.let{amount(it.principalMinor)}?:"—")+" → "+(next?.let{amount(it.principalMinor)}?:"—")),
                    word("利息 / 费用","利息・手数料") to ((old?.let{amount(it.feeMinor)}?:"—")+" → "+(next?.let{amount(it.feeMinor)}?:"—")),
                    word("实际分配后待付","実充当後の請求残額") to (amount(old?.let{r.before.ledger.billOutstanding(it).totalMinor}?:0)+" → "+amount(next?.let{r.after.billOutstanding(it).totalMinor}?:0))))
            }
        }
    }}
}

@Composable private fun planImpactIssue(issue:FinancialPlanImpactIssue)=when(issue) {
    FinancialPlanImpactIssue.RETAINED_POOL->word("原账单已纳入账户余额池，保留原金额并通过余额池更新未来预测。","元の請求は口座プールに統合済みです。元の金額を保持し、プールで将来の予測を更新します。")
    FinancialPlanImpactIssue.RETAINED_ARRANGEMENT->word("这是已办理变更后保留的义务，按原账单及实际分配核对。","手続き済み変更後に保持する債務です。元の請求・実充当で確認します。")
    FinancialPlanImpactIssue.MANUAL->word("这是手动已知计划。本次保存实际分配与版本快照，逐期平台金额由用户明确核对。","既知の手動予定です。実充当と版の記録を保存し、各回の返済先金額は明示的に確認します。")
    FinancialPlanImpactIssue.SOURCE_CHANGED->word("来源资料已变化，请打开原计划核对后重算。","対象情報が変わっています。元の予定を確認して再計算してください。")
    FinancialPlanImpactIssue.SOURCE_REMOVED->word("来源记录已移入回收站，保留独立计划和本次实际清偿。","対象記録はごみ箱にあります。独立した予定と今回の実返済を保持します。")
    FinancialPlanImpactIssue.ACCOUNT_INACTIVE->word("计划账户已停用，请先核对原计划。","予定の口座が無効です。元の予定を確認してください。")
    FinancialPlanImpactIssue.PAYMENT_ACCOUNT->word("计划扣款账户已停用，请核对未来实际付款账户。","予定の引落口座が無効です。将来の実支払口座を確認してください。")
    FinancialPlanImpactIssue.OPENING_PRINCIPAL->word("期初本金或其他计划的分配已变化，请核对来源本金。","期首元金・他の予定への割当てが変わっています。対象元金を確認してください。")
    FinancialPlanImpactIssue.CONTRACT_PRINCIPAL->word("合同本金与计划来源本金不同，请核对合同和原计划。","契約元金と予定の対象元金が異なります。契約・元の予定を確認してください。")
}
