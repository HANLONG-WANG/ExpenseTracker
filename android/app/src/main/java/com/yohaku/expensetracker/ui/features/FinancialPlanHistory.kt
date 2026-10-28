package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import kotlinx.coroutines.flow.*
import java.time.*
import java.time.format.DateTimeFormatter

private data class PlanHistoryItem(val row:FinancialPlanRevisionEntity,val archive:FinancialPlanArchive)
private sealed interface PlanHistoryRead {
    data object Loading:PlanHistoryRead
    data class Ready(val rows:List<PlanHistoryItem>):PlanHistoryRead
    data class Failure(val message:String):PlanHistoryRead
}

@Composable internal fun FinancialPlanHistory(plan:FinancialPlanEntity,vm:AppViewModel,onClose:()->Unit) {
    val appState by vm.state.collectAsStateWithLifecycle()
    var attempt by remember{mutableIntStateOf(0)}
    var selected by rememberSaveable(plan.bookId,plan.id){mutableStateOf<Int?>(null)}
    val history=remember(plan.bookId,plan.id,attempt){vm.repository.db.financialDao().planRevisions(plan.bookId,plan.id)
        .map<List<FinancialPlanRevisionEntity>,PlanHistoryRead>{rows->PlanHistoryRead.Ready(rows.map{PlanHistoryItem(it,vm.repository.json.decodeFromString(it.json))})}
        .catch{emit(PlanHistoryRead.Failure(it.message?:"版本读取失败 / 版の読込に失敗しました"))}
    }
    YSheet(word("计划版本","予定の履歴"),onClose) {
        // Collect in the dialog composition that consumes the state, including its initial emission.
        val state by history.collectAsStateWithLifecycle(PlanHistoryRead.Loading)
        YText(plan.name,18)
        YText(word("各版保存当时的计划、账单及可得的清偿依据；当前实际余额请返回账户查看。","各版は当時の予定・請求・判明している返済根拠を保持します。現在の実残高は口座で確認できます。"),11)
        when(val read=state) {
            PlanHistoryRead.Loading->YText(word("正在读取…","読込中…"),12)
            is PlanHistoryRead.Failure->{YText(read.message,12);YButton(word("重新读取","再読込"),{attempt++})}
            is PlanHistoryRead.Ready->{
                if(read.rows.isEmpty())YText(word("这份旧计划还没有版本快照；下次确认或重算会保存当前基线。","この既存予定の版はまだありません。次回の確定・再計算時に現在の状態を保存します。"),12)
                read.rows.forEach{item->Panel(Modifier.testTag("finance-plan-version-${item.row.version}")) {
                    val r=item.row;val a=item.archive;val currency=Currency.valueOf(a.plan.currency)
                    YText(word("第${r.version}版","第${r.version}版")+" · "+financialPlanReason(r.reason),14)
                    YText(Instant.ofEpochMilli(r.timestamp).atZone(vm.repository.clock.zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),11)
                    a.facts?.let{YText(word("清偿资料基准日：","返済情報の基準日：")+it.asOf,11)}
                    YButton(word("查看当时各期","当時の各回を見る")+if(selected==r.version)" −"else" +",{selected=if(selected==r.version)null else r.version},Modifier.testTag("finance-plan-version-open-${r.version}"))
                    if(selected==r.version) {
                        if(r.reason in listOf("ARRANGEMENT_RECONCILED","RECONCILIATION_RECALCULATED"))a.plan.reconciliation()?.let{record->FinancialReconciliationHistory(record,currency,vm.repository.clock.zone)}
                        a.batchArrangement?.let{batch->
                            RecordFacts(listOf(word("原付款日","元の支払日") to batch.paymentDate,word("整组转换本金","グループの変更元金") to Money(currency,batch.arrangements.sumOf{it.principalMinor}).display()))
                            ArrangementRows(word("办理时的新付款","手続き時の新しい支払"),batch.comparison.proposed,currency,batch.comparison.proposedRemainingPrincipalMinor)
                        }
                        a.poolMerge?.let{proof->
                            val change=vm.repository.json.decodeFromString<FinancialArrangementInput>(proof.arrangement.inputJson)
                            val comparison=vm.repository.json.decodeFromString<FinancialArrangementComparison>(proof.arrangement.comparisonJson)
                            ThemeAuditPanel(word("当时的共同付款","当時の共通支払"),Modifier.testTag("pool-merge-history")) {
                                YText(proof.pool.name,16)
                                RecordFacts(listOf(word("并入本金","統合元金") to Money(currency,proof.arrangement.principalMinor).display(),word("办理费","変更手数料") to Money(currency,proof.arrangement.changeFeeMinor).display(),word("费用付款日","手数料期日") to (change.mergeFeeDate?:word("无费用","手数料なし"))))
                                YText(word("生效日：","適用日：")+proof.arrangement.effectiveDate,11)
                                YText(word("记录办理：","手続きの記録：")+Instant.parse(proof.arrangement.appliedAt).atZone(vm.repository.clock.zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),11)
                                YText(word("这是并入记录，不是另一套还款计划。以下保留办理当时的共同付款结果，不随当前重算改变。","これは統合記録で、別の返済予定ではありません。以下は手続き当時の共通支払で、現在の再計算では変更しません。"),11)
                                ArrangementRows(word("当时的共同付款","当時の共通支払"),comparison.proposed,currency,comparison.proposedRemainingPrincipalMinor.takeIf{comparison.proposedComplete})
                            }
                        }
                        val input=a.plan.termsJson?.let{vm.repository.json.decodeFromString<FinancialPredictionInput>(it)}
                        if(r.reason=="POOL_PAYMENT_CHANGED")input?.pool?.paymentChanges?.lastOrNull()?.let{change->
                            val snapshot=(appState as? AppState.Ready)?.snapshot?.takeIf{it.book.id==plan.bookId}
                            if(snapshot!=null)PoolPaymentChangeHistory(change,currency,snapshot,vm.repository.clock.zone)
                        }
                        val projections=input?.projections.orEmpty()
                        val external=input?.pool?.facts?.retained.orEmpty().map{it.bill}.filter{b->a.bills.none{it.id==b.id}}
                        (a.bills+external).sortedWith(compareBy<FinancialBillEntity>{it.date}.thenBy{it.number}).forEach{bill->
                            if(bill.planId!=a.plan.id)YText(word("保留的原账单","保持する元の請求"),12)
                            FinancialBillFacts(bill,currency)
                            if(!bill.active)YText(word("此版已退出当前安排","この版では適用対象外"),11,color=LocalYohaku.current.muted)
                            a.facts?.let{facts->
                                val rows=facts.repayments.filter{it.repayment.platformConfirmed}.flatMap{it.allocations}.filter{it.billId==bill.id}
                                val paid=rows.fold(0L){sum,p->Math.addExact(sum,Math.addExact(p.principalMinor!!,Math.addExact(p.interestMinor!!,p.feeMinor!!)))}
                                val moved=facts.billAdjustments.filter{it.billId==bill.id}.fold(0L){sum,a->Math.addExact(sum,Math.addExact(a.principalMinor,a.feeMinor))}
                                Column(Modifier.testTag("finance-plan-history-outstanding-${r.version}-${bill.id}")) {
                                    RecordFacts(listOf(word("当时清偿后待付","当時の返済後残額") to Money(currency,Math.subtractExact(Math.subtractExact(bill.totalMinor,paid),moved)).display()))
                                }
                            }
                            if(bill.planId==a.plan.id)projections.firstOrNull{it.number==bill.number}?.let{FinancialProjectionFacts(it.basis,currency)}
                        }
                    }
                }}
            }
        }
    }
}

@Composable private fun financialPlanReason(reason:String)=when(reason) {
    "BATCH_REVOLVING"->word("まとめてリボ整组办理","まとめてリボ一括手続き")
    "BATCH_REVOLVING_UNDONE"->word("まとめてリボ整组撤销","まとめてリボ一括取消")
    "POOL_MERGED"->word("并入共同付款池","共通支払プールに統合")
    "ARRANGEMENT_RECONCILED"->word("核对当前已办理义务","手続き済みの現在債務を確認")
    "RECONCILIATION_POOL"->word("并入来源重核后的共同付款","統合元データの再確認後の共同支払")
    "RECONCILIATION_SOURCE"->word("关联安排重核后的保留义务","関連予定の再確認後の保持債務")
    "RECONCILIATION_RECALCULATED"->word("按新核对基准重算","確認した新基準で再計算")
    "POOL_PAYMENT_CHANGED"->word("已办理共同月付款调整","共通月払いの変更手続き済み")
    "ARRANGEMENT_APPLIED"->word("已办理新方案","新プランの手続き済み")
    "ARRANGEMENT_CLEARING"->word("办理变更后重新分配清偿","手続き変更に伴う返済の再充当")
    "ARRANGEMENT_CLEARING_MANUAL"->word("清偿归属调整 · 保留手动计划","返済の再充当・手動予定を保持")
    "ARRANGEMENT_CLEARING_REVIEW"->word("清偿归属调整 · 关联计划待核对","返済の再充当・関連予定を要確認")
    "ARRANGEMENT_RETAINED"->word("变更后保留义务","変更後に保持する債務")
    "POOL_ADOPTED"->word("已归并账户余额池，保留原义务","口座プールへ統合し元債務を保持")
    "POOL_UPDATED"->word("本金变动更新余额池预测","元金変動でプール予測を更新")
    "CREATED"->word("创建计划","予定作成")
    "EXISTING"->word("既有计划基线","既存予定の保存")
    "RECALCULATED"->word("明确重算","再計算")
    "SOURCE_UPDATED"->word("原消费资料更新","元の利用情報を更新")
    "SOURCE_PAYMENT_CORRECTED"->word("纠正付款方式，替换本地预测","支払方式の訂正で予測を置換")
    "CONTRACT_LINKED"->word("合同关联","契約の関連付け")
    "BILL_CONFIRMED"->word("确认 / 修正平台账单","返済先の請求を確定・修正")
    "REPAYMENT"->word("实际还款与预测更新","実返済と予測更新")
    "BORROWING"->word("借入资料与预测更新","借入情報と予測更新")
    "REPAYMENT_MANUAL"->word("实际还款 · 保留手动计划","実返済・手動予定を保持")
    "BORROWING_MANUAL"->word("借入资料 · 保留手动计划","借入情報・手動予定を保持")
    "REPAYMENT_REVIEW"->word("实际还款 · 预测待核对","実返済・予測の確認が必要")
    "BORROWING_REVIEW"->word("借入资料 · 预测待核对","借入情報・予測の確認が必要")
    else->word("计划变更","予定変更")
}
