package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.data.repository.BookSnapshot
import com.yohaku.expensetracker.data.repository.linkedLifecycleRefunds
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import java.time.*
import java.time.format.DateTimeFormatter

@Composable internal fun TrashScreen(s:BookSnapshot,vm:AppViewModel) {
    val t=LocalYohaku.current
    YLead(word("给误删，留一点余地。","消しすぎにも、余白を。"),word("恢复原记录及其关联，不会新增一笔。","元の記録と関連を復元。新しい記録は作りません。"),"RECOVER WITHIN 30 DAYS")
    val entries=s.events.filter{it.deletedAt!=null}.sortedByDescending{it.deletedAt}
    if(entries.isEmpty())YEmpty(word("回收站是空的","ごみ箱は空です"))
    val groupedRefundIds=entries.flatMap{s.linkedLifecycleRefunds(it,true)}.map{it.id}.toSet()
    entries.filter{it.id !in groupedRefundIds}.forEach{e->
        val expiry=Instant.ofEpochMilli(e.deletedAt!!).plus(Duration.ofDays(30))
        val available=expiry.isAfter(vm.repository.clock.instant())
        val expiryText=expiry.atZone(vm.repository.clock.zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        Column(Modifier.fillMaxWidth().testTag("trash-"+e.id),verticalArrangement=Arrangement.spacedBy(15.dp)){
            RecordCover(e.merchant.ifBlank{s.path(e.categoryId)},Money(Currency.valueOf(e.currency),e.amountMinor).display(),e.date,word("已移至回收站","ごみ箱に移動済み"),stamp="↺")
            RecordFacts(listOf(word("分类","カテゴリ") to s.path(e.categoryId),word("保留至","保存期限") to expiryText))
            RefundLifecycleNotice(s,e,restoring=true)
            val originalId=s.settlements.find{it.eventId==e.id}?.originalEventId
            val originalDeleted=originalId!=null&&s.events.first{it.id==originalId}.deletedAt!=null
            if(originalDeleted)YText(word("请先恢复原消费，再恢复这笔独立删除的退款。","元の購入を復元してから、個別に削除した返金を復元してください。"),12,color=t.warm)
            else s.restorationImpacts(e.id).forEach{EntryImpactPreview(s,e,it,YearMonth.now(vm.repository.clock).toString(),restoring=true)}
            if(!available)YText(word("已超过30天恢复期限。","30日の復元期限を過ぎています。"),12,color=t.warm)
            YButton(word("恢复原记录","記録を復元"),{vm.execute{vm.repository.restoreEntry(s.book.id,e.id)}},Modifier.testTag("restore-"+e.id),primary=true,enabled=available&&!originalDeleted)
        }
    }
}
