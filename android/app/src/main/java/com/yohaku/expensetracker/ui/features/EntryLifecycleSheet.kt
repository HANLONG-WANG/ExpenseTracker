package com.yohaku.expensetracker.ui.features

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import java.time.YearMonth

@Composable internal fun EntryLifecycleSheet(s:BookSnapshot,vm:AppViewModel,eventId:String?,operation:EntryLifecycleOperation,onClose:()->Unit,onApplied:()->Unit) {
    var review by remember(s.book.id,eventId,operation){mutableStateOf<EntryLifecycleReview?>(null)}
    var refreshed by remember{mutableStateOf(false)}
    var categories by remember{mutableStateOf(false)}
    val busy by vm.busy.collectAsStateWithLifecycle()
    LaunchedEffect(s.book.id,eventId,operation){vm.execute{review=vm.repository.previewEntryLifecycle(s.book.id,eventId,operation)}}
    val permanent=operation in listOf(EntryLifecycleOperation.PURGE,EntryLifecycleOperation.EMPTY)
    val title=when(operation){
        EntryLifecycleOperation.TRASH->word("移至回收站","ごみ箱に移動")
        EntryLifecycleOperation.RESTORE->word("恢复原记录","記録を復元")
        EntryLifecycleOperation.PURGE->word("永久删除记录","記録を完全削除")
        EntryLifecycleOperation.EMPTY->word("清空回收站","ごみ箱を空にする")
    }
    YSheet(title,{if(!busy)onClose()}) {
        val value=review
        if(value==null)YText(word("正在核对记录与关联…","記録と関連を確認中…"),12)else {
            if(refreshed)YText(word("资料已变化，请重新确认当前影响。","情報が変わりました。現在の影響を再確認してください。"),12)
            value.event?.let{e->YText(e.merchant.ifBlank{s.path(e.categoryId).ifBlank{entryKindName(EntryKind.valueOf(e.kind))}},18)}
            RecordFacts(listOf(word("关联组记录数","関連する記録数") to value.events.size.toString(),word("关联附件 / 图片","関連する添付・画像") to value.fileCount.toString(),word("核对资料 / 金融计划","照合資料・金融プラン") to "${value.statementCount} / ${value.planCount}",word("编辑本组的草稿","対象の編集下書き") to value.draftCount.toString()),audit=true)
            if(value.events.size>1) {
                var eventPage by remember(value){mutableIntStateOf(0)}
                value.events.drop(eventPage*30).take(30).forEach{e->YText(e.date+" · "+entryKindName(EntryKind.valueOf(e.kind))+" · "+Money(Currency.valueOf(e.currency),e.amountMinor).display(),12)}
                if(value.events.size>30)YActionRow {
                    YButton(word("上一页","前へ"),{eventPage--},enabled=eventPage>0)
                    YText("${eventPage+1} / ${(value.events.size+29)/30}",12)
                    YButton(word("下一页","次へ"),{eventPage++},enabled=(eventPage+1)*30<value.events.size)
                }
            }
            YText(if(permanent)word("无法恢复。清理本组账目、修订及媒体引用，并放弃编辑本组的草稿；其他记录仍引用的文件保留。独立还款、合同、已确认账单与流水核对原始资料保留。","復元できません。対象記録・変更履歴・メディア参照と編集下書きを削除。他の記録が参照するファイルは保持。独立した返済・契約・確定請求・元の照合資料は保持します。")else if(operation==EntryLifecycleOperation.TRASH)word("确认后退出正常统计，30天内恢复原ID和文件。原消费及本次关联退款一并处理，转账两端不可拆开。旧手工核对关联失效，恢复后须重新确认。独立实际还款和已确认账单保留，相关预测须重新核对。","通常の集計から除外し、30日以内は元のIDとファイルを復元。元の支出と今回の関連返金を一緒に扱い、振替の両端は分割しません。手動照合の関連は失効し、復元後も再確認が必要。独立した実返済と確定請求は保持し、関連予測を再確認します。")else word("恢复原ID与有效关联，不新增一笔。重新计算余额、预算和未完成安排；旧手工核对差异不会自动重新生效，相关金融计划仍按当前资料检查。","元のIDと有効な関連を復元し、重複作成しません。残高・予算・未完了予定を再計算。古い手動照合は自動で再有効化せず、金融プランは現在の情報で確認します。"),12)
            if(!permanent)value.event?.let{RefundLifecycleNotice(s,it,restoring=operation==EntryLifecycleOperation.RESTORE)}
            value.impacts.forEach{impact->value.event?.let{EntryImpactPreview(s,it,impact,YearMonth.now(vm.repository.clock).toString(),restoring=operation==EntryLifecycleOperation.RESTORE)}}
            if(value.categoryImpacts.isNotEmpty()) {
                YLink(word("查看分类预算变化","カテゴリ予算の変化を見る"),{categories=!categories},Modifier.testTag("entry-lifecycle-category-impact"))
                if(categories)value.categoryImpacts.forEach{p->
                    YText(p.month+" · "+s.path(p.categoryId),12)
                    RecordFacts(listOf(word("预算消费","予算に含む支出") to "${Money(p.before.currency,p.before.spentMinor).display()} → ${Money(p.after.currency,p.after.spentMinor).display()}"),audit=true)
                }
            }
            YButton(if(operation==EntryLifecycleOperation.TRASH)word("确认移至回收站","ごみ箱へ移動")else title,{
                var saved=false
                vm.execute(onSuccess={if(saved)onApplied()}) {
                    saved=vm.repository.commitEntryLifecycle(value)
                    if(!saved){review=vm.repository.previewEntryLifecycle(s.book.id,eventId,operation);refreshed=true}
                    else {vm.repository.collectUnusedMedia();vm.reminders?.rebuildSafely(false)}
                }
            },Modifier.testTag("confirm-entry-lifecycle"),primary=true,enabled=!busy)
        }
    }
}
