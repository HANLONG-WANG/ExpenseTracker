package com.yohaku.expensetracker.ui.features

import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.db.CategoryEntity
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun CategoryManagementSheet(s:BookSnapshot,c:CategoryEntity,vm:AppViewModel,onClose:()->Unit) {
    var operation by remember(c.id){mutableStateOf(if(c.active)CategoryOperation.DEACTIVATE else CategoryOperation.ACTIVATE)}
    var target by remember(c.id){mutableStateOf<String?>(null)}
    var policy by remember(c.id){mutableStateOf(CategoryBudgetPolicy.KEEP_TARGET)}
    var review by remember(c.id){mutableStateOf<CategoryOperationReview?>(null)}
    var refreshed by remember{mutableStateOf(false)}
    val busy by vm.busy.collectAsStateWithLifecycle()
    val scroll=rememberScrollState()
    LaunchedEffect(review){scroll.scrollTo(0)}
    val ids=setOf(c.id)+s.categories.filter{it.parentId==c.id}.map{it.id}
    @Composable fun operationName(op:CategoryOperation)=when(op){
        CategoryOperation.DEACTIVATE->word("停用，保留历史与计划","停止し、履歴と予定を保持")
        CategoryOperation.ACTIVATE->word("重新启用","再開")
        CategoryOperation.REPARENT->word("调整父级，保留分类ID","親を変更し、カテゴリIDを保持")
        CategoryOperation.DELETE_EMPTY->word("删除无引用分类组","参照のないカテゴリを削除")
        CategoryOperation.MERGE_DELETE->word("迁移整组资料并删除分类","情報を統合しカテゴリを削除")
    }
    val currency=Currency.valueOf(s.book.currency)
    @Composable fun money(n:Long?)=n?.let{Money(currency,it).display()}?:word("未设置","未設定")
    YSheet(word("分类状态与归属","カテゴリの状態と分類"),{if(!busy){if(review!=null)review=null else onClose()}},scrollState=scroll) {
        YText(s.path(c.id),18,Modifier.testTag("category-operation-name"))
        val value=review
        if(value==null) {
            YSelect(word("操作","操作"),operation,listOf(if(c.active)CategoryOperation.DEACTIVATE else CategoryOperation.ACTIVATE,CategoryOperation.REPARENT,CategoryOperation.MERGE_DELETE,CategoryOperation.DELETE_EMPTY).map{it to operationName(it)},{operation=it;target=null},Modifier.testTag("category-operation"))
            if(operation in listOf(CategoryOperation.REPARENT,CategoryOperation.MERGE_DELETE)) {
                val options=s.categories.filter{it.id !in ids&&it.kind==c.kind&&s.categoryEnabled(it.id)&&(operation!=CategoryOperation.REPARENT||it.parentId==null)}
                YSelect(word("迁往分类 / 父级","統合先・新しい親"),target,(if(operation==CategoryOperation.REPARENT)listOf(null to word("无 · 作为一级分类","なし・親カテゴリにする"))else listOf(null to word("请选择迁入分类","統合先を選択")))+options.map{it.id as String? to s.path(it.id)},{target=it},Modifier.testTag("category-operation-target"))
            }
            if(operation==CategoryOperation.MERGE_DELETE) {
                YSelect(word("分类预算处理","カテゴリ予算の扱い"),policy,listOf(CategoryBudgetPolicy.KEEP_TARGET to word("保留目标额度，移除来源额度","統合先を保持、元の予算を削除"),CategoryBudgetPolicy.COMBINE to word("合并已设额度，父级覆盖时不重复","設定額を合算、親の包含分は重複しない")),{policy=it},Modifier.testTag("category-budget-policy"))
                YText(word("包含子分类、回收记录、周期安排、草稿及活动分类预算。迁移后删除来源分类，无法撤销；记录和文件仍保留。","子カテゴリ・ごみ箱の記録・定期予定・下書き・シーン別予算を含みます。統合元のカテゴリ削除は元に戻せません。記録とファイルは保持します。"),12)
            } else YText(word("停用一级分类会隐藏其下各分类，保留子分类各自的启用状态。已有记录、预算与周期安排不删除。","親の停止で配下を非表示にし、子それぞれの状態は保持します。既存の記録・予算・定期予定は削除しません。"),12)
            if(operation==CategoryOperation.REPARENT&&ids.size>1)YText(word("已有子分类，需先移动子分类，才能将本组改为二级。","子カテゴリを先に移動してから、この親を子に変更できます。"),12)
            YButton(word("预览调整影响","変更の影響を確認"),{vm.execute{review=vm.repository.previewCategoryOperation(s.book.id,c.id,operation,target,policy);refreshed=false}},Modifier.testTag("category-operation-preview"),primary=true,enabled=!busy&&(operation!=CategoryOperation.MERGE_DELETE||target!=null)&&(operation!=CategoryOperation.REPARENT||target!=c.parentId)&&(operation!=CategoryOperation.REPARENT||ids.size==1||target==null))
        } else {
            YText(operationName(value.operation),14)
            if(value.operation==CategoryOperation.DELETE_EMPTY)YText(if(value.deletionBlocked)word("仍有记录、预算、计划或草稿引用，请停用或明确迁移。","記録・予算・予定・下書きの参照があります。停止または統合してください。")else word("确认后永久删除本分类及无引用子分类，无法恢复。","このカテゴリと参照のない子カテゴリを完全に削除し、復元できません。"),12)
            if(refreshed)YText(word("资料已变化，请重新确认当前影响。","情報が変わりました。現在の影響を再確認してください。"),12)
            RecordFacts(listOf(word("记录（含回收）","記録（ごみ箱を含む）") to value.recordCount.toString(),word("周期规则 / 安排","定期ルール・予定") to "${value.recurrenceCount} / ${value.occurrenceCount}",word("草稿","下書き") to value.draftCount.toString(),word("来源分类数","対象カテゴリ数") to value.sourceIds.size.toString()),audit=true)
            YText(word("账户资金、消费总额及月份不变；分类汇总按新归属计算。","口座資金・支出総額・計上月は変わりません。カテゴリ集計は新しい分類に従います。"),12)
            value.projections.forEach{p->
                YText("${p.month} · ${s.path(p.categoryId)}",12)
                RecordFacts(listOf(word("分类消费","カテゴリ支出") to "${money(p.before.spentMinor)} → ${money(p.after.spentMinor)}",word("预算上限","予算上限") to "${money(p.before.limitMinor)} → ${money(p.after.limitMinor)}",word("待确认预留","未確定の確保") to "${money(p.before.reserveMinor)} → ${money(p.after.reserveMinor)}"),audit=true)
            }
            val (removed,added)=value.budgetChanges
            if(removed.isNotEmpty()||added.isNotEmpty())YText(word("预算设置变动：移除 / 替换 ${removed.size} 项，写入 ${added.size} 项；沿用月份一并调整。","予算設定：${removed.size}件を削除・置換、${added.size}件を設定。繰越月も変更します。"),12)
            val (oldActivity,newActivity)=value.activityBudgetChanges
            (oldActivity.map{it.activityId}+newActivity.map{it.activityId}).distinct().forEach{id->
                YText(s.activities.first{it.id==id}.name,13)
                oldActivity.filter{it.activityId==id}.forEach{YText("− ${s.path(it.categoryId)} · ${money(it.amountMinor)}",12)}
                newActivity.filter{it.activityId==id}.forEach{YText("＋ ${s.path(it.categoryId)} · ${money(it.amountMinor)}",12)}
            }
            YActionRow {
                YButton(word("确认分类调整","カテゴリ変更を確定"),{
                    var saved=false
                    vm.execute(onSuccess={if(saved)onClose()}) {
                        saved=vm.repository.commitCategoryOperation(value)
                        if(!saved){review=vm.repository.previewCategoryOperation(s.book.id,c.id,value.operation,value.targetId,value.budgetPolicy);refreshed=true}
                        else vm.reminders?.rebuildSafely(false)
                    }
                },Modifier.testTag("confirm-category-operation"),primary=true,enabled=!busy&&(value.operation!=CategoryOperation.DELETE_EMPTY||!value.deletionBlocked))
                YLink(word("返回修改","戻って変更"),{review=null},Modifier.testTag("category-operation-back"))
            }
        }
    }
}
