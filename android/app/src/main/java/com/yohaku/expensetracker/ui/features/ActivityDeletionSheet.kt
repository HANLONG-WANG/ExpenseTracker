package com.yohaku.expensetracker.ui.features

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.db.ActivityEntity
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun ActivityDeletionSheet(a:ActivityEntity,vm:AppViewModel,onClose:()->Unit,onDeleted:()->Unit) {
    var review by remember(a.id){mutableStateOf<ActivityDeletionReview?>(null)}
    var showCategories by remember(a.id){mutableStateOf(false)}
    var refreshed by remember{mutableStateOf(false)}
    val busy by vm.busy.collectAsStateWithLifecycle()
    LaunchedEffect(a.id){vm.execute{review=vm.repository.previewActivityDeletion(a.bookId,a.id)}}
    YSheet(word("删除活动的影响","シーン削除の影響"),{if(!busy)onClose()}) {
        YText(a.name,18)
        val value=review
        if(value==null)YText(word("正在核对关联资料…","関連情報を確認中…"),12) else {
            val currency=Currency.valueOf(value.months.first().first.currency.name)
            fun money(n:Long)=Money(currency,n).display()
            if(refreshed)YText(word("资料已变化，请按当前影响重新确认。","情報が変わりました。現在の影響を再確認してください。"),12)
            RecordFacts(listOf(word("保留消费（含回收）","保持する支出（ごみ箱を含む）") to value.recordCount.toString(),word("保留互请历史","保持するごちそう履歴") to value.treatCount.toString(),word("解除未用预留","未使用の確保を解除") to money(value.releasedMinor),word("停止周期规则 / 未完成安排","停止する定期ルール・未完了予定") to "${value.before.recurrences.count{it.activityId==a.id&&it.active}} / ${value.cancelledCount}"),audit=true)
            YText(word("从活动列表和新记录选择中移除；已有消费回归日常，各历史月份重新计入月预算。消费、账户资金、附件和原记录ID保留，不转移互请到其他活动。","通常のシーン一覧と新規選択から除外。既存の支出は日常に戻し、各月の予算へ再計上します。支出・口座資金・添付・元の記録IDを保持し、ごちそうを他のシーンへ移しません。"),12)
            if(value.draftConverted)YText(word("未保存的新记录草稿将改为日常，移除活动、预留、周期和互请选择，其他输入保留。","未保存の新規下書きを日常に変更し、シーン・確保・定期予定・ごちそうの選択を解除。他の入力は保持します。"),12)
            value.months.forEach{(before,after)->
                YText(before.month,14)
                RecordFacts(listOf(word("计入月预算的消费","月予算に含む支出") to "${money(before.spentMinor)} → ${money(after.spentMinor)}",word("活动独立支出","シーンの独立支出") to "${money(before.independentMinor)} → ${money(after.independentMinor)}",word("全部消费","すべての支出") to "${money(before.allExpenseMinor)} → ${money(after.allExpenseMinor)}"),audit=true)
            }
            if(value.categoryBudgets.isNotEmpty()) {
                YLink(word("分类预算变化（${value.categoryBudgets.size}项）","カテゴリ予算の変化（${value.categoryBudgets.size}件）"),{showCategories=!showCategories},Modifier.testTag("activity-category-impact"))
                if(showCategories)value.categoryBudgets.forEach{change->
                    YText(change.month+" · "+value.before.path(change.categoryId),13)
                    RecordFacts(listOf(word("计入分类预算的消费","カテゴリ予算に含む支出") to "${money(change.before.spentMinor)} → ${money(change.after.spentMinor)}",word("分类额度","カテゴリ上限") to money(change.after.limitMinor!!)),audit=true)
                }
            }
            YText(word("删除无法撤销。原活动的消费和互请仍可从“已删除活动的历史”查看；此处不是还款或清偿。","削除は元に戻せません。「削除済みシーンの履歴」で元の支出とごちそうを確認できます。返済や清算の処理ではありません。"),12)
            YButton(word("确认删除活动","シーンを削除"),{
                var saved=false
                vm.execute(onSuccess={if(saved)onDeleted()}) {
                    saved=vm.repository.commitActivityDeletion(value)
                    if(!saved){review=vm.repository.previewActivityDeletion(a.bookId,a.id);refreshed=true}
                    else vm.reminders?.rebuildSafely(false)
                }
            },Modifier.testTag("confirm-activity-deletion"),primary=true,enabled=!busy)
        }
    }
}
