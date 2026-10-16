package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.data.db.EventEntity
import com.yohaku.expensetracker.data.repository.BookSnapshot
import com.yohaku.expensetracker.data.repository.accountMoney
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.word

/** recordDetail() core; receipt/refund controls join when their real capabilities exist. */
@Composable internal fun RecordDetailCore(s:BookSnapshot,e:EventEntity,onEdit:()->Unit){
    Column(Modifier.fillMaxWidth().testTag("record-detail-core")){
        RecordCover(e.merchant.ifBlank{entryKindName(EntryKind.valueOf(e.kind))},s.accountMoney(e).display(),e.date,entryKindName(EntryKind.valueOf(e.kind)),recordId=e.id)
        Box(Modifier.fillMaxWidth().padding(vertical=22.dp)){
            YButton("↺ "+word("编辑记录","記録を編集"),onEdit,Modifier.testTag("record-edit-button"),primary=true)
        }
        RecordFacts(listOf(
            word("账户","口座") to s.accounts.find{it.id==e.accountId}?.name.orEmpty(),
            word("分类","カテゴリ") to s.path(e.categoryId).ifBlank{"—"},
            word("活动","シーン") to (s.activities.find{a->s.eventActivities.any{it.eventId==e.id&&it.activityId==a.id}}?.let{it.name+if(it.deletedAt!=null)word(" · 已删除，计入日常","・削除済み、日常に計上")else ""}?:word("日常","日常")),
            word("备注","メモ") to e.note.ifBlank{"—"}
        ),exactLineBox=true)
    }
}
