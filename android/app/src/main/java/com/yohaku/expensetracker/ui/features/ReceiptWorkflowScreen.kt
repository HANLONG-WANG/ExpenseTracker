package com.yohaku.expensetracker.ui.features

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.db.EventEntity
import com.yohaku.expensetracker.data.files.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.Currency
import com.yohaku.expensetracker.domain.model.Money
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import kotlinx.serialization.encodeToString
import java.io.File

@Composable internal fun ReceiptsScreen(s:BookSnapshot,vm:AppViewModel,onRecord:(EventEntity)->Unit) {
    val context=LocalContext.current
    val busy by vm.busy.collectAsStateWithLifecycle()
    var tab by rememberSaveable(s.book.id){mutableStateOf("UNSORTED")}
    var selected by rememberSaveable(s.book.id){mutableStateOf(listOf<String>())}
    var imagePreview by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    var exportPath by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    var importOpen by rememberSaveable(s.book.id){mutableStateOf(false)}
    var exportManifest by remember(s.book.id){mutableStateOf<ReceiptBundleFile?>(null)}
    var notice by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    var showNote by rememberSaveable(s.book.id){mutableStateOf(false)}
    var formatHelp by rememberSaveable(s.book.id){mutableStateOf(false)}
    LaunchedEffect(exportPath) {
        val path=exportPath ?: return@LaunchedEffect
        if(exportManifest!=null)return@LaunchedEffect
        var manifest:ReceiptBundleFile?=null
        vm.execute(onSuccess={exportManifest=manifest}) {
            require(File(path).isFile){"导出预览已过期，请重新选择小票 / 出力プレビューの期限が切れました。レシートを選び直してください"}
            manifest=FileProtocols.receiptBundle(File(path).readText())
        }
    }
    val destination=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val manifest=exportManifest
        if(uri!=null&&manifest!=null) vm.execute(onSuccess={
            exportPath?.let { File(it).delete() };exportPath=null;exportManifest=null
            selected=emptyList();notice="导出已保存，仍待商品回填 / 書出しを保存しました。商品取込待ちです"
        }) { vm.repository.saveReceiptArchivesToTree(context,uri,manifest) }
    }
    val exports by remember(s.book.id){vm.repository.db.fileWorkflowDao().exports(s.book.id)}.collectAsStateWithLifecycle(emptyList())
    val exportedReceiptIds=remember(exports,s.receipts,s.events) { exports.flatMap { FileProtocols.receiptBundle(it.manifestJson).records }.filter { record->
        s.receipts.any{it.id==record.receipt_id&&it.revision==record.receipt_revision}&&s.visibleEvents.any{it.id==record.record_id&&it.revision==record.record_revision}
    }.map{it.receipt_id}.toSet() }
    val available=s.receipts.filter{r->s.visibleEvents.any{it.id==r.eventId}&&s.receiptImages.any{it.receiptId==r.id&&it.revision==r.revision}}
    val pending=available.filter{it.status!="SORTED"}.map{it.id}
    ReceiptInboxCover(pending.size)
    ReceiptWorkflow()
    YLink(word("应用不识别小票 · 查看整理说明","認識機能なし・整理の説明"),{showNote=!showNote})
    if(showNote)YText(word("将导出包交给助手，回来后预览并保存商品明细。原图不会自动上传。","書出しを助手へ渡し、結果を確認して商品明細を保存。原画像の自動送信は行いません。"),11,color=LocalYohaku.current.muted)
    ReceiptInboxTabs(tab){tab=it}
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
        YLink(word("全选待整理","未整理を全選択"),{selected=if(pending.all{it in selected})emptyList()else pending},Modifier.testTag("receipt-select-all"))
        YText(word("已选 ${selected.count{it in pending}}","選択 ${selected.count{it in pending}}"),11)
    }
    notice?.let { YText(if(LocalLanguage.current=="ja")it.substringAfter(" / ")else it.substringBefore(" / "),12,color=LocalYohaku.current.accent) }
    val records=available.filter{tab=="ALL"||it.status==tab}
    if(records.isEmpty())YEmpty(word("这一格暂时空着。可以从记账中添加小票。","ここは空白です。記録からレシートを追加できます。"))
    records.forEachIndexed{index,receipt->
        val event=s.visibleEvents.first{it.id==receipt.eventId}
        val images=s.entryReceiptImages(event.id)
        ReceiptInboxCard(receipt.id,index,event.merchant.ifBlank{word("这张小票","このレシート")},event.date+" · "+s.path(event.categoryId)+" · "+Money(Currency.valueOf(event.currency),event.amountMinor).display()+" · "+word("第${receipt.revision}版","第${receipt.revision}版"),
            if(receipt.status=="SORTED")word("已整理","整理済み")else if(receipt.id in exportedReceiptIds)word("已导出 · 待回填","書出済・取込待ち")else word("待整理","未整理"),receipt.status=="SORTED",images.size,{onRecord(event)},
            selected=receipt.id in selected,onSelect=if(receipt.status!="SORTED"&&!busy)({selected=if(receipt.id in selected)selected-receipt.id else selected+receipt.id})else null) {
            images.forEachIndexed{imageIndex,file->ReceiptThumbnailFrame(file.id,imageIndex,{imagePreview=file.id}){MediaThumbnail(vm.repository,file,Modifier.fillMaxSize(),crop=true)}}
        }
    }
    YButton(word("批量导出待整理小票 ↗","未整理レシートを書出し ↗"),{
        var manifest:ReceiptBundleFile?=null
        var path:String?=null
        vm.execute(onSuccess={exportManifest=manifest;exportPath=path}) {
            val sources=vm.repository.receiptArchiveSources(s.book.id,selected.filter{it in pending}.toSet())
            manifest=ReceiptBundleFile("yohaku.receipt-bundle",1,vm.repository.id(),s.book.id,1,1,sources.map{it.record})
            path=File.createTempFile("receipt-manifest-", ".json",context.cacheDir).apply{writeText(FileProtocols.json.encodeToString(manifest!!))}.path
        }
    },Modifier.fillMaxWidth().testTag("receipt-export"),primary=true,enabled=!busy&&selected.any{it in pending})
    YButton(word("导入助手整理结果","助手の整理結果を取込"),{importOpen=true},Modifier.fillMaxWidth().testTag("receipt-import"),enabled=!busy)
    YLink(word("文件格式与助手说明","ファイル形式・助手への説明"),{formatHelp=true})
    exportManifest?.let { manifest -> YSheet(word("导出包预览","書出しパックの確認"),{if(!busy){exportPath?.let{File(it).delete()};exportPath=null;exportManifest=null}}) {
        ReceiptHandoffSummary("YOHAKU / RECEIPT HANDOFF",word("把小票，交给助手。","レシートを、助手へ。"),"",review=false)
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            YText(manifest.records.size.toString(),26,number=true)
            YText(word("组小票","組のレシート"),10)
            YText(manifest.records.sumOf{it.images.size}.toString(),26,number=true)
            YText(word("张原图","枚の原画像"),10)
        }
        ReceiptPaper(packageTree=true) {
            YText("yohaku-receipts.zip",12)
            YText("├ manifest.json",11)
            YText(word("账本、原记账ID、小票ID、图片顺序、金额与币种","帳簿・記録ID・レシートID・画像順・金額・通貨"),10,color=LocalYohaku.current.muted)
            manifest.records.forEach { record ->
                YText("└ ${record.record_id} / ${record.receipt_id} /",9,color=LocalYohaku.current.muted)
                record.images.forEach { image->YText("  ${(image.position+1).toString().padStart(2,'0')} · "+(s.files.find{it.id==image.image_id}?.originalName?:image.image_id),10) }
            }
        }
        YText(word("仅打包所选小票原图和必要关联信息。按100 MB目标分包，同张小票不拆散；大于目标的小票独立成包。导出后仍待回填。","選択したレシート原画像と関連情報のみ。100 MBを目安に分割し、同じレシートは分けません。大きなレシートは単独パックにします。書出し後も取込待ちです。"),11,color=LocalYohaku.current.muted)
        YButton(word("选择保存目录并导出","保存フォルダを選んで書出し"),{destination.launch(null)},Modifier.fillMaxWidth().testTag("receipt-save-zip"),primary=true,enabled=!busy)
        YLink(word("返回小票工作台","レシート整理へ戻る"),{if(!busy){exportPath?.let{File(it).delete()};exportPath=null;exportManifest=null}},Modifier.testTag("receipt-export-back"))
    } }
    if(importOpen)ReceiptImportFlow(s,vm,{importOpen=false},{importOpen=false;notice="商品明细已保存 / 商品明細を保存しました"})
    imagePreview?.let { id->s.files.find{it.id==id}?.let { file->MediaPreview(vm.repository,file,{imagePreview=null},s.receipts.find{r->s.entryReceiptImages(r.eventId).any{it.id==id}}?.let{r->s.entryReceiptImages(r.eventId)}?:listOf(file)) } }
    if(formatHelp)ReceiptFormatHelp({formatHelp=false})
}

@Composable internal fun ReceiptBackfillSheet(s:BookSnapshot,preview:ReceiptBackfillPreview,busy:Boolean,onClose:()->Unit,onReselect:()->Unit,onSave:(Boolean,Boolean)->Unit) {
    var confirming by rememberSaveable(preview.file.bundle_id){mutableStateOf(false)}
    val needsStale=preview.targets.any{it.staleExport}
    val needsReplacement=preview.targets.any{it.existingItemsRevision!=null}
    if(confirming&&(needsStale||needsReplacement)) {
        YSheet(if(needsReplacement)word("确认替换已有商品明细","既存の商品明細を置換")else word("确认使用旧修订结果","旧リビジョンの結果を確認"),{if(!busy)confirming=false}) {
            YText(word("只替换已预览的商品明细。原记账金额、分类和小票图片保持原样。","確認した商品明細のみ置換。記録額・カテゴリ・画像は保持。"),12,color=LocalYohaku.current.muted)
            if(needsStale)YText(word("导出后原记录或小票已变化。确认即表示已检查旧修订；新增未覆盖图片仍待整理。","出力後に元記録・レシートが変わっています。確定は旧リビジョンを確認したことを意味し、追加された未処理画像は未整理のままです。"),12,color=LocalYohaku.current.warm)
            YButton(if(needsReplacement)word("替换并保存","置換して保存")else word("确认保存旧修订结果","旧リビジョンの結果を保存"),{onSave(needsStale,needsReplacement)},Modifier.fillMaxWidth().testTag("receipt-confirm-replacement"),primary=true,enabled=!busy)
            YButton(word("取消，重新选择","取消・選び直す"),onReselect,Modifier.fillMaxWidth().testTag("receipt-cancel-replacement"),enabled=!busy)
            YLink(word("返回预览","プレビューに戻る"),{confirming=false},Modifier.testTag("receipt-back-to-preview"))
        }
        return
    }
    YSheet(word("确认商品明细","商品明細を確認"),onClose) {
        ReceiptHandoffSummary("RECEIPT ITEMS / REVIEW",word("${preview.targets.size} 笔可关联记账","${preview.targets.size} 件の対応記録"),word("预览后确认，不会生成新的消费。","確認してから保存。新しい支出は作りません。"))
        val unknown=remember(preview.originalJson){unknownFileFields(preview.originalJson,ReceiptItemsFile.serializer().descriptor)}
        if(unknown.isNotEmpty()) {
            YText(word("以下扩展字段将忽略，不产生业务效果：","次の拡張項目は無視され、処理に影響しません："),11,color=LocalYohaku.current.warm)
            unknown.forEach{YText(it,10,color=LocalYohaku.current.muted)}
        }
        preview.targets.forEach { target-> ReceiptPaper {
            val event=s.visibleEvents.firstOrNull{it.id==target.record.record_id}
            YText(event?.merchant?.ifBlank{null}?:target.record.record_id,when(LocalYohaku.current.id){"journal"->23;"objects"->18;else->19})
            YText(word("已找到原记账","元の記録あり"),10,color=LocalYohaku.current.accent)
            YText(event?.let{it.date+" · "+Money(Currency.valueOf(it.currency),it.amountMinor).display()}.orEmpty(),9,color=LocalYohaku.current.muted)
            ReceiptItemRows(target.record,target.totals.knownItems)
            ReceiptTotalLines(target.record,target.totals)
            if(target.staleExport)YText(word("导出后原记录或小票已变化；请核对旧修订。","出力後に元記録・レシートが変わっています。旧リビジョンを確認してください。"),12,color=LocalYohaku.current.warm)
            if(target.addedImageIds.isNotEmpty())YText(word("另有 ${target.addedImageIds.size} 张原图未覆盖，保存后仍待整理。","ほかに ${target.addedImageIds.size} 枚の未処理画像があり、保存後も未整理です。"),11,color=LocalYohaku.current.warm)
            if(target.existingItemsRevision!=null)YText(word("已有商品：保存将替换，不重复追加。","明細あり：保存時に置換し、重複追加しません。"),11)
        } }
        YButton(word("确认保存商品明细","商品明細の保存を確認"),{if(needsStale||needsReplacement)confirming=true else onSave(false,false)},Modifier.fillMaxWidth().testTag("receipt-confirm-import"),primary=true,enabled=!busy)
        YButton(word("重新选择文件","別のファイルを選択"),onReselect,Modifier.fillMaxWidth(),enabled=!busy)
        YText(word("保存不修改原记账金额。未知价格、数量和行金额保留待补。","元記録の金額は変更しません。不明な価格・数量・行金額は未入力のまま保持します。"),11,color=LocalYohaku.current.muted)
    }
}
