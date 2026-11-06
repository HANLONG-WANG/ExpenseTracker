package com.yohaku.expensetracker.ui.features

import com.yohaku.expensetracker.ui.haptics.Haptic
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import java.time.*

/** Shared by local history and cloud command previews; all names/amounts are archived facts. */
@Composable internal fun FinancialBatchSummary(facts:BatchRestorationFacts) {
    val ordered=remember(facts){facts.inDisplayOrder()}
    val currency=Currency.valueOf(facts.currency)
    FinancialRepaymentSummary(word("这次批量转换与恢复范围","今回の一括変更と復元範囲"),"finance-batch-undo-summary") {
        ordered.transfers.forEach{row->
            YText(row.name+" · "+Money(currency,row.principalMinor).display(),14)
            FinancialTime(Instant.parse(row.appliedAt),ZoneId.of(row.zoneId),"batch-${row.id}")
            RecordFacts(listOf(word("原一次付安排","元の一括払い予定") to row.bills.joinToString(" · "){it.date+" / "+Money(currency,it.totalMinor).display()}))
        }
        RecordFacts(listOf(word("转入合计","移行合計") to Money(currency,facts.transfers.sumOf{it.principalMinor}).display(),word("原付款日","元の支払日") to facts.paymentDate))
        YText(word("目标循环还款计划：","統合先のリボ・循環返済プラン：")+facts.poolName,13)
        YText(word("撤销后恢复以下办理前安排（包含该循环计划的原有账期），本批次新建安排退出当前计划：","取消後は次の変更前予定（統合先の元の請求を含む）を復元し、今回作成した予定を適用対象外にします："),12)
        ordered.restore.forEach{original->
            YText(original.name+" · "+if(original.complete)word("完整安排","全期間の予定")else word("已列范围，后续仍可能未安排","記載範囲・未予定の残額がある場合あり"),12)
            original.bills.forEach{bill->YText(bill.date+" · "+Money(currency,bill.totalMinor).display(),11)}
            if(original.bills.isEmpty())YText(word("当时没有有效账期","当時の有効な請求なし"),11)
        }
        YText(word("仅撤销这次本地批量转换记录；实际资金、实际付款和原消费不变，也不会向平台申请取消办理。","今回のローカル一括変更記録のみ取り消します。実資金・実支払・元の利用は変更せず、返済先への取消申請も行いません。"),12)
    }
}

@Composable internal fun FinancialBatchHistoryList(bookId:String,accountId:String,vm:AppViewModel,onCurrent:(String)->Unit,planId:String?=null) {
    val finance by vm.financialState.collectAsStateWithLifecycle()
    var rows by remember(bookId,accountId){mutableStateOf<List<FinancialBatchHistory>?>(null)}
    var error by remember{mutableStateOf<String?>(null)}
    var selected by remember{mutableStateOf<FinancialBatchHistory?>(null)}
    var blocked by remember{mutableStateOf<String?>(null)}
    val language=LocalLanguage.current
    val busy by vm.busy.collectAsStateWithLifecycle()
    LaunchedEffect(bookId,accountId,finance){runCatching{vm.repository.financialBatchHistory(bookId,accountId)}.onSuccess{rows=it;error=null}.onFailure{error=it.message}}
    error?.let{YText(it,12,color=LocalYohaku.current.warm)}
    rows?.filter{planId==null||it.archive.before.any{p->p.plan.id==planId}}?.forEach{item->
        val archive=item.archive
        val facts=batchRestorationFacts(item)
        FinancialRepaymentSummary(word("批量转换历史","一括変更履歴"),"finance-batch-history-${archive.undoGroupId}") {
            YText(facts.transfers.joinToString(" · "){it.name},14)
            FinancialTime(Instant.parse(archive.arrangements.first().appliedAt),ZoneId.of(item.zones.values.first()),"batch-history-${archive.undoGroupId}")
            YText(Money(Currency.valueOf(archive.before.first().plan.currency),archive.arrangements.sumOf{it.principalMinor}).display(),14,number=true)
            YText(if(item.undone)word("已撤销，原安排已恢复；办理历史保留。","取消済み・元の予定を復元。手続き履歴は保持します。")else word("已记录平台办理结果","返済先の手続き結果を記録済み"),11)
            YButton(if(item.undone)word("查看","見る")else word("查看并撤销","確認して取り消す"),{selected=item;blocked=null},Modifier.testTag("finance-batch-open-${archive.undoGroupId}"))
        }
    }
    selected?.let{item->YSheet(word("批量转换记录","一括変更の記録"),{selected=null}) {
        FinancialBatchSummary(batchRestorationFacts(item))
        blocked?.let{reason->
            YText(reason,12,Modifier.testTag("finance-batch-undo-blocked"),color=LocalYohaku.current.warm)
            YButton(word("查看当前还款安排","現在の返済予定を見る"),{selected=null;onCurrent(item.archive.before.first{it.plan.origin=="POOL"}.plan.id)},Modifier.testTag("finance-batch-current"))
        }
        if(!item.undone&&blocked==null)YButton(word("确认撤销这次批量转换记录","この一括変更の記録を取り消す"),{vm.execute{
            try { vm.repository.undoBatchRevolving(bookId,item.archive.undoGroupId);vm.haptic(Haptic.Restored);selected=null }
            catch(e:IllegalArgumentException){vm.haptic(Haptic.Warning);blocked=e.message?.let{if(language=="ja")it.substringAfter(" / ",it)else it.substringBefore(" / ")}}
        }},Modifier.testTag("batch-revolving-undo-confirm"),primary=true,enabled=!busy)
        YButton(word("返回历史","履歴に戻る"),{selected=null},Modifier.testTag("finance-batch-undo-cancel"))
    }}
}
