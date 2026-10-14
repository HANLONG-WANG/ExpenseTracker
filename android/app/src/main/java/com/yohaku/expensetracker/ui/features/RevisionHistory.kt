package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.BookSnapshot
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import kotlinx.coroutines.flow.*
import java.time.*
import java.time.format.DateTimeFormatter

private data class Change(val version:Int,val timestamp:Long,val before:EventEntity,val after:EventEntity)
private sealed interface HistoryRead{
    data object Loading:HistoryRead
    data class Ready(val changes:List<Change>):HistoryRead
    data class Failure(val message:String):HistoryRead
}

@Composable internal fun RevisionHistory(s:BookSnapshot,event:EventEntity,vm:AppViewModel){
    var attempt by remember(s.book.id,event.id){mutableIntStateOf(0)}
    val result by remember(s.book.id,event.id,attempt){
        vm.repository.dao.revisions(s.book.id,event.id).map<List<RevisionEntity>,HistoryRead>{rows->
            HistoryRead.Ready(rows.filter{it.beforeJson!=null}.map{r->Change(r.version,r.timestamp,vm.repository.json.decodeFromString(r.beforeJson!!),vm.repository.json.decodeFromString(r.afterJson))})
        }.catch{emit(HistoryRead.Failure(it.message ?: "读取失败 / 読込失敗"))}
    }.collectAsStateWithLifecycle(HistoryRead.Loading)
    when(val state=result){
        HistoryRead.Loading->YText(word("正在读取修改记录…","変更履歴を読み込み中…"),11,color=LocalYohaku.current.muted)
        is HistoryRead.Failure->ThemeAuditPanel(word("修改记录","変更履歴"),Modifier.testTag("revision-error")){
            YText(word("无法读取修改记录","変更履歴を読み込めません"),12)
            YText(state.message,10,color=LocalYohaku.current.muted)
            YButton(word("重新读取","再読み込み"),{attempt++},primary=true)
        }
        is HistoryRead.Ready->if(state.changes.isNotEmpty())ThemeAuditPanel(word("修改记录","変更履歴"),Modifier.testTag("revision-history")){
            state.changes.forEach{change->Column(Modifier.fillMaxWidth().testTag("revision-${change.version}").padding(bottom=12.dp)){
                val b=change.before;val a=change.after
                val date=Instant.ofEpochMilli(change.timestamp).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                YText(word("第${change.version}版","第${change.version}版")+" · "+date,11,color=LocalYohaku.current.muted)
                val fields=mutableListOf<Pair<String,String>>()
                fun field(label:String,before:String,after:String){fields+=label to (before.ifBlank{"—"}+" → "+after.ifBlank{"—"})}
                if(b.kind!=a.kind)field(word("类型","種類"),entryKindName(EntryKind.valueOf(b.kind)),entryKindName(EntryKind.valueOf(a.kind)))
                if(b.amountMinor!=a.amountMinor||b.currency!=a.currency)field(word("金额","金額"),Money(Currency.valueOf(b.currency),b.amountMinor).display(),Money(Currency.valueOf(a.currency),a.amountMinor).display())
                if(b.baseMinor!=a.baseMinor&&(b.currency!=s.book.currency||a.currency!=s.book.currency))field(word("本位币统计","基準通貨集計"),Money(Currency.valueOf(s.book.currency),b.baseMinor).display(),Money(Currency.valueOf(s.book.currency),a.baseMinor).display())
                if(b.accountId!=a.accountId)field(word("账户","口座"),s.accounts.find{it.id==b.accountId}?.name ?: word("已移除账户","削除済みの口座"),s.accounts.find{it.id==a.accountId}?.name ?: word("已移除账户","削除済みの口座"))
                if(b.categoryId!=a.categoryId)field(word("分类","カテゴリ"),s.path(b.categoryId),s.path(a.categoryId))
                if(b.date!=a.date)field(word("日期","日付"),b.date,a.date)
                if(b.merchant!=a.merchant)field(word("商户","お店"),b.merchant,a.merchant)
                if(b.note!=a.note)field(word("备注","メモ"),b.note,a.note)
                if(b.historicalIncluded!=a.historicalIncluded)field(word("期初包含","期首に含む"),if(b.historicalIncluded)word("已包含","含む") else word("不包含","含まない"),if(a.historicalIncluded)word("已包含","含む") else word("不包含","含まない"))
                RecordFacts(fields,audit=true)
            }}
        }
    }
}
