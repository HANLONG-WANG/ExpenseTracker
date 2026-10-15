package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.BookSnapshot
import com.yohaku.expensetracker.data.repository.LedgerDetails
import com.yohaku.expensetracker.data.repository.accountMoney
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import kotlinx.coroutines.flow.*
import java.time.*
import java.time.format.DateTimeFormatter

private data class Change(val version:Int,val timestamp:Long,val before:EventEntity,val after:EventEntity,val beforeDetails:LedgerDetails?,val afterDetails:LedgerDetails?)
private sealed interface HistoryRead{
    data object Loading:HistoryRead
    data class Ready(val changes:List<Change>):HistoryRead
    data class Failure(val message:String):HistoryRead
}

@Composable internal fun RevisionHistory(s:BookSnapshot,event:EventEntity,vm:AppViewModel){
    var attempt by remember(s.book.id,event.id){mutableIntStateOf(0)}
    val result by remember(s.book.id,event.id,attempt){
        vm.repository.dao.revisions(s.book.id,event.id).map<List<RevisionEntity>,HistoryRead>{rows->
            HistoryRead.Ready(rows.filter{it.beforeJson!=null}.map{r->
                Change(r.version,r.timestamp,vm.repository.json.decodeFromString(r.beforeJson!!),vm.repository.json.decodeFromString(r.afterJson),
                    r.beforeDetailsJson?.let{vm.repository.json.decodeFromString<LedgerDetails>(it)},
                    r.afterDetailsJson?.let{vm.repository.json.decodeFromString<LedgerDetails>(it)})
            })
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
                val oldAttachments=change.beforeDetails?.attachmentIds.orEmpty();val newAttachments=change.afterDetails?.attachmentIds.orEmpty()
                if(oldAttachments!=newAttachments)field(word("附件列表 / 顺序","添付一覧・順序"),oldAttachments.size.toString(),newAttachments.size.toString())
                if(change.beforeDetails?.receiptRevision!=change.afterDetails?.receiptRevision)field(word("小票原图修订","レシート原画像の版"),change.beforeDetails?.receiptRevision?.toString().orEmpty(),change.afterDetails?.receiptRevision?.toString().orEmpty())
                val bs=change.beforeDetails?.settlement;val ds=change.afterDetails?.settlement
                if(bs!=null&&ds!=null) {
                    if(bs.accountMinor!=ds.accountMinor||bs.accountCurrency!=ds.accountCurrency)field(word("账户入账金额","口座の計上額"),bs.accountMoney().display(),ds.accountMoney().display())
                    if(bs.confirmed!=ds.confirmed)field(word("入账状态","計上の状態"),if(bs.confirmed)word("已确认","確認済")else word("预计","見込み"),if(ds.confirmed)word("已确认","確認済")else word("预计","見込み"))
                    if(bs.settlementDate!=ds.settlementDate)field(word("入账日期","計上日"),bs.settlementDate,ds.settlementDate)
                }
                val bt=change.beforeDetails?.transfer;val dt=change.afterDetails?.transfer
                if(bt!=null&&dt!=null) {
                    if(bt.toMinor!=dt.toMinor||bt.toCurrency!=dt.toCurrency)field(word("到账净额","入金純額"),Money(Currency.valueOf(bt.toCurrency),bt.toMinor).display(),Money(Currency.valueOf(dt.toCurrency),dt.toMinor).display())
                    if(bt.toAccountId!=dt.toAccountId)field(word("转入账户","入金口座"),s.accounts.find{it.id==bt.toAccountId}?.name.orEmpty(),s.accounts.find{it.id==dt.toAccountId}?.name.orEmpty())
                    if(bt.toConfirmed!=dt.toConfirmed)field(word("到账状态","入金の状態"),if(bt.toConfirmed)word("已到账","入金済")else word("待到账","未入金"),if(dt.toConfirmed)word("已到账","入金済")else word("待到账","未入金"))
                    if(bt.toDate!=dt.toDate)field(word("到账日期","入金日"),bt.toDate,dt.toDate)
                }
                val beforeFee=change.beforeDetails?.components?.firstOrNull();val afterFee=change.afterDetails?.components?.firstOrNull()
                if(beforeFee!=afterFee) {
                    field(word("手续费","手数料"),beforeFee?.let{Money(Currency.valueOf(it.originalCurrency),it.originalMinor).display()}.orEmpty(),afterFee?.let{Money(Currency.valueOf(it.originalCurrency),it.originalMinor).display()}.orEmpty())
                    if(beforeFee!=null&&afterFee!=null) {
                        if(beforeFee.position!=afterFee.position)field(word("费用扣收方式","手数料の扱い"),feePositionName(TransferFeePosition.valueOf(beforeFee.position)),feePositionName(TransferFeePosition.valueOf(afterFee.position)))
                        if(beforeFee.confirmed!=afterFee.confirmed)field(word("费用扣收状态","手数料の状態"),if(beforeFee.confirmed)word("已扣收","引落済")else word("未扣收","未引落"),if(afterFee.confirmed)word("已扣收","引落済")else word("未扣收","未引落"))
                        if(beforeFee.date!=afterFee.date)field(word("费用日期","手数料の日付"),beforeFee.date,afterFee.date)
                    }
                }
                RecordFacts(fields,audit=true)
            }}
        }
    }
}
