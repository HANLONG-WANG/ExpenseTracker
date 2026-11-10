package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.merchant.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class MerchantHistoryScope(val merchantId:String?=null,val name:String?=null,
    val objectType:MerchantObjectType?=null,val objectId:String?=null)
private data class HistoryRoute(val scope:MerchantHistoryScope,val operation:MerchantOperationEntity?=null)
private const val HistoryPageSize=20

@Composable internal fun merchantHistoryType(type:MerchantOperationType):String=when(type) {
    MerchantOperationType.CREATE->word("创建商户","お店を作成")
    MerchantOperationType.CREATE_FROM_ORIGINAL_NAME->word("从原名创建","元の名前から作成")
    MerchantOperationType.ASSIGN_ORIGINAL_NAME->word("原名归入","元の名前を取り込み")
    MerchantOperationType.RENAME->word("商户改名","お店の名前変更")
    MerchantOperationType.MERGE->word("合并商户","お店を統合")
    MerchantOperationType.CHANGE_NOTE->word("修改备注","メモを変更")
    MerchantOperationType.CHANGE_ACTIVE->word("修改启停","有効状態を変更")
    MerchantOperationType.ORGANIZE_LOCATIONS->word("整理位置","場所を整理")
    MerchantOperationType.DELETE->word("删除商户","お店を削除")
    MerchantOperationType.HISTORY_CLEANUP->word("历史数据整理","過去のデータを整理")
    MerchantOperationType.CLOUD_UNDO->word("云端撤销","クラウド操作の取消")
}
private fun historyTime(timestamp:Long)=Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
@Composable private fun historyNames(o:MerchantOperationEntity):String {
    val before=o.sourceBefore?.name?:o.originalName?:o.sourceMerchantName?:o.targetBefore?.name
    val after=o.targetAfter?.name?:o.sourceAfter?.name?:o.targetMerchantName
    if(before==null&&after==null)return word("名称变化见影响明细","名前の変更は影響の明細を参照")
    return when(o.type) {
        MerchantOperationType.CREATE->word("新建：","新規：")+after.orEmpty()
        MerchantOperationType.CREATE_FROM_ORIGINAL_NAME->if(o.originalName!=null&&o.originalName!=after)
            word("原名 ","元の名前 ")+o.originalName+" → "+after.orEmpty()
            else word("新建：","新規：")+after.orEmpty()
        MerchantOperationType.DELETE->word("删除：","削除：")+before.orEmpty()
        else->(before?:word("尚未创建","未作成"))+" → "+(after?:word("已删除","削除済み"))
    }
}
@Composable private fun HistorySummary(o:MerchantOperationEntity,original:MerchantOperationEntity?=null) {
    YText(historyTime(o.timestamp)+" · "+when(o.source){
        MerchantOperationSource.LOCAL->word("本机","端末")
        MerchantOperationSource.CLOUD->word("云端","クラウド")
        MerchantOperationSource.UPGRADE->word("升级整理","アップグレード整理")
    },11,color=LocalYohaku.current.muted)
    YText(merchantHistoryType(o.type),16,bold=LocalYohaku.current.id=="magazine")
    if(o.type==MerchantOperationType.CLOUD_UNDO)YText(word("撤销了 ","取り消した操作：")+
        (original?.let{merchantHistoryType(it.type)+" · "+historyNames(it)}?:word("此前的云端操作","以前のクラウド操作")),13)
    else YText(historyNames(o),14)
    MerchantImpactCountsText(o.counts)
    if(o.undoneByOperationId!=null)MerchantHistoryStamp(word("已撤销","取消済み")+(o.undoneAt?.let{" · "+historyTime(it)}?:""))
}

/** One sheet with a back stack, including deleted merge sources; no live merchant/event is required. */
@Composable internal fun MerchantHistorySheet(bookId:String,vm:AppViewModel,scope:MerchantHistoryScope=MerchantHistoryScope(),onDismiss:()->Unit) {
    var routes by remember(bookId,scope){mutableStateOf(listOf(HistoryRoute(scope)))}
    val route=routes.last()
    val dao=vm.repository.db.merchantHistoryDao()
    key(route) {
        YSheet(if(route.operation==null)word("商户操作历史","お店の操作履歴")else word("操作详情","操作の詳細"),onDismiss,rememberScrollState()) {
            if(routes.size>1)YLink(word("返回上一层","前の履歴へ"),{routes=routes.dropLast(1)},Modifier.testTag("merchant-history-back"))
            if(route.operation==null) {
                route.scope.name?.let{YText(it,17)}
                HistoryPages(route.scope,load={offset->when {
                    route.scope.merchantId!=null->dao.forMerchant(bookId,route.scope.merchantId,HistoryPageSize,offset)
                    route.scope.objectId!=null->dao.forObject(bookId,requireNotNull(route.scope.objectType),route.scope.objectId,HistoryPageSize,offset)
                    else->dao.page(bookId,HistoryPageSize,offset)
                }}) { rows->
                    rows.forEachIndexed { index,o->
                        val original by produceState<MerchantOperationEntity?>(null,o.id){value=o.undoOfOperationId?.let{dao.operation(bookId,it)}}
                        MerchantHistoryPaper((index+1).toString().padStart(2,'0'),Modifier.testTag("merchant-history-row-${o.id}")) {
                            HistorySummary(o,original)
                            YLink(word("查看详情","詳細を見る"),{routes=routes+HistoryRoute(route.scope,o)},Modifier.testTag("merchant-history-detail-${o.id}"))
                        }
                    }
                }
            }else {
                val o=route.operation
                val original by produceState<MerchantOperationEntity?>(null,o.id){value=o.undoOfOperationId?.let{dao.operation(bookId,it)}}
                MerchantHistoryPaper("01",Modifier.testTag("merchant-history-detail")){HistorySummary(o,original)}
                original?.let{prior->YLink(word("查看被撤销的操作","取り消された操作を見る"),{routes=routes+HistoryRoute(route.scope,prior)},Modifier.testTag("merchant-history-undo-origin"))}
                if(o.type==MerchantOperationType.MERGE&&o.sourceMerchantId!=null)YButton(
                    word("来源商户的此前历史","統合元のお店の過去の履歴"),{routes=routes+HistoryRoute(MerchantHistoryScope(o.sourceMerchantId,o.sourceMerchantName))},Modifier.testTag("merchant-history-source"))
                HistoryProfiles(if(o.sourceMerchantId==o.targetMerchantId)word("商户资料","お店の情報")else word("来源商户","変更元のお店"),o.sourceBefore,o.sourceAfter)
                if(o.sourceMerchantId!=o.targetMerchantId)HistoryProfiles(word("目标商户","変更先のお店"),o.targetBefore,o.targetAfter)
                ThemeSection(word("影响明细","影響の明細"),"A RECORD OF CHANGE")
                YText(word("保留操作当时的快照；商户或对象删除后仍可查阅。","操作時点の情報を保存しています。お店や対象の削除後も確認できます。"),11,color=LocalYohaku.current.muted)
                HistoryPages(o.id,load={dao.impacts(bookId,o.id,HistoryPageSize,it)}) { impacts->
                    impacts.forEachIndexed { index,i->MerchantHistoryPaper((index+1).toString().padStart(2,'0'),Modifier.testTag("merchant-impact-${i.objectId}")) {
                        YText(when(i.objectType){
                            MerchantObjectType.MERCHANT->word("商户","お店")
                            MerchantObjectType.EVENT->word("记录","記録")
                            MerchantObjectType.REFUND->word("退款","返金")
                            MerchantObjectType.TEMPLATE->word("模板","テンプレート")
                            MerchantObjectType.RECURRENCE->word("周期规则","定期ルール")
                            MerchantObjectType.SAVED_LOCATION->word("保存地点","保存場所")
                            MerchantObjectType.PENDING_PLACE->word("待整理位置","未整理の位置")
                            MerchantObjectType.DRAFT->word("草稿","下書き")
                        },14)
                        YText(word("对象编号：","対象番号：")+i.objectId,10,color=LocalYohaku.current.muted)
                        HistoryObjectSide(word("修改前","変更前"),i.oldMerchantName,i.oldMerchantId,o)
                        HistoryObjectSide(word("修改后","変更後"),i.newMerchantName,i.newMerchantId,o)
                    }}
                }
            }
        }
    }
}

@Composable private fun HistoryProfiles(title:String,before:MerchantProfile?,after:MerchantProfile?) {
    if(before==null&&after==null)return
    MerchantHistoryPaper(modifier=Modifier.testTag("merchant-history-profile"),heading=title) {
        listOf(word("修改前","変更前") to before,word("修改后","変更後") to after).forEachIndexed{index,(label,p)->
            YText(label,11,color=LocalYohaku.current.muted)
            if(p==null)YText(if(index==0)word("尚未创建","未作成")else word("已删除","削除済み"),13)else {
                YText(word("商户名称：","お店の名前：")+p.name,14)
                YText(word("备注：","メモ：")+p.note.ifBlank{word("无","なし")},12)
                YText(if(p.active)word("启用","有効")else word("停用","停止"),12)
            }
        }
    }
}
@Composable private fun HistoryObjectSide(label:String,name:String?,id:String?,o:MerchantOperationEntity) {
    YText(label+" · "+(name?.ifBlank{word("空文字","空欄")}?:word("无文字快照","文字の保存なし")),13)
    val merchant=if(id==o.sourceMerchantId)o.sourceMerchantName else if(id==o.targetMerchantId)o.targetMerchantName else null
    YText(if(id==null)word("无正式商户关联","保存済みのお店との関連なし")else word("关联商户：","関連するお店：")+(merchant?.let{"$it · "}?:"")+id,11,color=LocalYohaku.current.muted)
}

/** Explicit 20-row pages bound each query; history is never part of BookSnapshot. */
@Composable private fun <T> HistoryPages(key:Any,load:suspend(Int)->List<T>,content:@Composable (List<T>)->Unit) {
    var rows by remember(key){mutableStateOf(emptyList<T>())}
    var page by remember(key){mutableIntStateOf(0)}
    var retry by remember(key){mutableIntStateOf(0)}
    var loading by remember(key){mutableStateOf(true)}
    var more by remember(key){mutableStateOf(true)}
    var error by remember(key){mutableStateOf<String?>(null)}
    LaunchedEffect(key,page,retry) {
        loading=true;error=null
        try { val next=load(rows.size);rows=rows+next;more=next.size==HistoryPageSize }
        catch(e:CancellationException){throw e}
        catch(e:Exception){error=e.message?:e.javaClass.simpleName}
        finally{loading=false}
    }
    content(rows)
    when {
        loading->YText(word("正在读取…","読み込み中…"),12)
        error!=null->MerchantHistoryPaper(heading=word("读取失败","読込失敗")){
            YText(word("历史读取失败：","履歴を読み込めません：")+error,12)
            YButton(word("重新读取","再読み込み"),{retry++})
        }
        rows.isEmpty()->MerchantHistoryPaper(modifier=Modifier.testTag("merchant-history-empty")){
            YText(word("这里还没有历史","まだ履歴はありません"),17)
            YText(word("商户整理留下的变化会保存在这里。","お店の整理による変更をここに残します。"),12)
        }
        more->YButton(word("加载更多","さらに読み込む"),{page++},Modifier.testTag("merchant-history-more"))
    }
}
