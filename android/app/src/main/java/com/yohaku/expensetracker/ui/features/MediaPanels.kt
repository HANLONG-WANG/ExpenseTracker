package com.yohaku.expensetracker.ui.features

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.EntryDraft
import com.yohaku.expensetracker.domain.model.Money
import com.yohaku.expensetracker.domain.model.Currency
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable internal fun EntryMediaPanel(s:BookSnapshot,d:EntryDraft,vm:AppViewModel) {
    val context=LocalContext.current
    val busy by vm.busy.collectAsStateWithLifecycle()
    val attachments=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->vm.importFiles(context,s.book.id,uris,false)}
    val receipts=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->vm.importFiles(context,s.book.id,uris,true)}
    var preview by rememberSaveable(d.mediaSession){mutableStateOf<String?>(null)}
    ThemeSection(word("普通附件","添付ファイル"),"ATTACHMENTS")
    if(d.attachmentIds.isNotEmpty())Column(verticalArrangement=Arrangement.spacedBy(9.dp)) {
        d.attachmentIds.forEach{id->s.files.find{it.id==id}?.let{file->MediaFileRow(vm.repository,file,{preview=id},{vm.changeEntry(s.book.id,d.copy(attachmentIds=d.attachmentIds-id))})}}
    }
    MaterialUpload(word("添加文件 / 每个小于100 MB","ファイル追加・各100 MB未満"),"media-add-attachments",!busy){attachments.launch(arrayOf("*/*"))}
    ThemeSection(word("小票原图","レシートの原画像"),"ORIGINAL RECEIPTS")
    MaterialReceiptStrip(d.receiptFileIds.size,null) {
        d.receiptFileIds.forEachIndexed{index,id->s.files.find{it.id==id}?.let{file->
            fun move(to:Int){val values=d.receiptFileIds.toMutableList();values[index]=values[to];values[to]=id;vm.changeEntry(s.book.id,d.copy(receiptFileIds=values))}
            MaterialReceiptImage(id,index,{preview=id},{vm.changeEntry(s.book.id,d.copy(receiptFileIds=d.receiptFileIds-id))},if(index>0&&!busy)({move(index-1)})else null,if(index<d.receiptFileIds.lastIndex&&!busy)({move(index+1)})else null) {
                MediaThumbnail(vm.repository,file,Modifier.fillMaxSize(),crop=true)
            }
        }}
    }
    MaterialUpload(word("添加小票图片","レシート画像を追加"),"media-add-receipts",!busy){receipts.launch(arrayOf("image/*"))}
    YText(word("小票独立于普通附件，原图保留，不自动识别或新增消费。","レシートは添付と分け、原画像を保存します。自動認識や支出の追加は行いません。"),11,color=LocalYohaku.current.muted)
    preview?.let{id->s.files.find{it.id==id}?.let{file->MediaPreview(vm.repository,file,{preview=null},if(id in d.receiptFileIds)d.receiptFileIds.mapNotNull{key->s.files.find{it.id==key}}else d.attachmentIds.mapNotNull{key->s.files.find{it.id==key}})}}
}
@Composable private fun MediaFileRow(repository:LedgerRepository,file:FileBlobEntity,onPreview:()->Unit,onRemove:(()->Unit)?=null) {
    val thumbnail:(@Composable ()->Unit)?=if(file.mime.startsWith("image/"))({MediaThumbnail(repository,file,Modifier.fillMaxSize(),crop=true)})else null
    MaterialFileTile(file.id,file.originalName,file.mime+" · "+file.size+word(" 字节"," バイト"),file.mime.startsWith("audio/"),onPreview,onRemove,thumbnail)
}
@Composable internal fun RecordMediaPanel(s:BookSnapshot,event:EventEntity,vm:AppViewModel) {
    var preview by rememberSaveable(event.id){mutableStateOf<String?>(null)}
    val attachments=s.entryAttachments(event.id);val receipts=s.entryReceiptImages(event.id)
    if(attachments.isNotEmpty())ThemeSection(word("普通附件","添付ファイル"),"ATTACHMENTS")
    if(attachments.isNotEmpty())Column(verticalArrangement=Arrangement.spacedBy(9.dp)) {
        attachments.forEach{file->MediaFileRow(vm.repository,file,{preview=file.id})}
    }
    if(receipts.isNotEmpty()) {
        ThemeSection(word("小票原图 · 待整理","レシート原画像・未整理"),"ORIGINAL RECEIPTS")
        MaterialReceiptStrip(receipts.size,s.receipts.find{it.eventId==event.id}?.revision) {
            receipts.forEachIndexed{index,file->MaterialReceiptImage(file.id,index,{preview=file.id},null,null,null) {
                MediaThumbnail(vm.repository,file,Modifier.fillMaxSize(),crop=true)
            }}
        }
        YLink(word("打开小票工作台","レシート一覧を開く"),{vm.navigate("receipts")})
    }
    preview?.let{id->s.files.find{it.id==id}?.let{MediaPreview(vm.repository,it,{preview=null},if(receipts.any{f->f.id==id})receipts else attachments)}}
}
private suspend fun decodeMedia(repository:LedgerRepository,file:FileBlobEntity,maxSize:Int):android.graphics.Bitmap=withContext(Dispatchers.IO) {
    val path=repository.mediaFile(file);check(path.isFile){"附件文件缺失 / 添付ファイルがありません"}
    val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(path.path,bounds)
    check(bounds.outWidth>0&&bounds.outHeight>0){"无法解码这张图片 / この画像を読み込めません"}
    var sample=1;while(bounds.outWidth/sample>maxSize||bounds.outHeight/sample>maxSize)sample*=2
    BitmapFactory.decodeFile(path.path,BitmapFactory.Options().apply{inSampleSize=sample})?:error("无法解码这张图片 / この画像を読み込めません")
}
@Composable private fun MediaThumbnail(repository:LedgerRepository,file:FileBlobEntity,modifier:Modifier=Modifier.fillMaxWidth().heightIn(max=180.dp),crop:Boolean=false) {
    var failure by remember(file.id){mutableStateOf<String?>(null)}
    val bitmap by produceState<android.graphics.Bitmap?>(null,file.id){try{value=decodeMedia(repository,file,512)}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){failure=e.message}}
    bitmap?.let{Image(it.asImageBitmap(),file.originalName,modifier,contentScale=if(crop)ContentScale.Crop else ContentScale.Fit,alignment=androidx.compose.ui.Alignment.TopCenter)}
    failure?.let{MediaFailure(it,11)}
}
@Composable private fun MediaFailure(message:String,size:Int,modifier:Modifier=Modifier) {
    val localized=if(LocalLanguage.current=="ja")message.substringAfter(" / ",message)else message.substringBefore(" / ")
    YText(localized,size,modifier,color=LocalYohaku.current.warm)
}
@Composable internal fun MediaPreview(repository:LedgerRepository,file:FileBlobEntity,onDismiss:()->Unit,images:List<FileBlobEntity> = listOf(file)) {
    val group=images.filter{it.mime.startsWith("image/")}
    var selected by rememberSaveable(file.id){mutableStateOf(file.id)}
    val current=group.find{it.id==selected}?:file
    val index=group.indexOf(current).coerceAtLeast(0)
    key(current.id){MediaPreviewFile(repository,current,onDismiss,index,group.size.coerceAtLeast(1),{selected=group[index-1].id},{selected=group[index+1].id})}
}
@Composable private fun MediaPreviewFile(repository:LedgerRepository,file:FileBlobEntity,onDismiss:()->Unit,index:Int,count:Int,onPrevious:()->Unit,onNext:()->Unit) {
    val context=LocalContext.current
    var failure by remember(file.id){mutableStateOf<String?>(null)}
    var bitmap by remember(file.id){mutableStateOf<android.graphics.Bitmap?>(null)}
    var scale by remember(file.id){mutableFloatStateOf(1f)}
    var x by remember(file.id){mutableFloatStateOf(0f)};var y by remember(file.id){mutableFloatStateOf(0f)}
    LaunchedEffect(file.id){if(file.mime.startsWith("image/"))try{bitmap=decodeMedia(repository,file,2048)}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){failure=e.message}}
    fun external() {
        try {
            val physical=repository.mediaFile(file);check(physical.isFile){"附件文件缺失 / 添付ファイルがありません"}
            val uri=FileProvider.getUriForFile(context,context.packageName+".files",physical,file.originalName).buildUpon().appendQueryParameter("mime",file.mime).build()
            val intent=Intent(Intent.ACTION_VIEW).setDataAndType(uri,file.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply{clipData=ClipData.newRawUri(file.originalName,uri)}
            context.startActivity(intent)
        }catch(_:ActivityNotFoundException){failure="没有可以打开此格式的应用 / この形式を開くアプリがありません"}catch(e:Exception){failure=e.message?:"无法打开附件 / 添付を開けません"}
    }
    YSheet(if(file.mime.startsWith("audio/"))word("音频附件","音声添付")else if(file.mime.startsWith("image/"))word("图片预览","画像プレビュー")else word("使用外部应用打开","外部アプリで開く"),onDismiss) {
        if(file.mime.startsWith("image/")||file.mime.startsWith("audio/"))YText(file.mime+" · "+file.size+word("字节","バイト"),11,Modifier.testTag("media-preview-meta"))
        failure?.let{MediaFailure(it,13,Modifier.testTag("media-preview-error"))}
        when {
            file.mime.startsWith("image/")->ImageViewer(file.originalName,index,count,scale!=1f||x!=0f||y!=0f,onPrevious,onNext,{
                if(scale!=1f||x!=0f||y!=0f){scale=1f;x=0f;y=0f}else scale=1.8f
            }) {
                bitmap?.let{BoxWithConstraints(Modifier.fillMaxWidth().height(320.dp).clipToBounds(),contentAlignment=Alignment.Center) {
                    val ratio=it.width.toFloat()/it.height
                    val width=minOf(maxWidth,320.dp*ratio)
                    val t=LocalYohaku.current
                    val paper=when(t.id){"journal"->Modifier.shadow(5.dp);"objects"->Modifier.shadow(3.dp).border(5.dp,Color(0xfffffaf0));else->Modifier}
                    Image(it.asImageBitmap(),file.originalName,Modifier.width(width).height(width/ratio).then(paper).testTag("media-image").semantics{stateDescription="${(scale*100).toInt()}%"}.pointerInput(file.id){detectTransformGestures{_,pan,zoom,_->scale=(scale*zoom).coerceIn(1f,6f);x+=pan.x;y+=pan.y}}.graphicsLayer(scaleX=scale,scaleY=scale,translationX=x,translationY=y),contentScale=ContentScale.Fit)
                }}?:if(failure==null)YText(word("正在读取原图…","原画像を読み込み中…"),12)else Unit
            }
            file.mime.startsWith("audio/")->AudioPreview(repository,file,{failure=it})
            else->ExternalFileCover(file.originalName,file.size.toString()+word(" 字节 · "," バイト・")+file.mime)
        }
        YButton(word("在其他应用打开","他のアプリで開く"),::external,Modifier.testTag("media-external-open"),primary=true)
        YButton(word("← 返回附件列表","← 添付一覧へ戻る"),onDismiss,Modifier.fillMaxWidth().testTag("media-preview-back"))
    }
}
@Composable private fun AudioPreview(repository:LedgerRepository,file:FileBlobEntity,onFailure:(String)->Unit) {
    var player by remember(file.id){mutableStateOf<MediaPlayer?>(null)}
    var playing by remember(file.id){mutableStateOf(false)}
    var duration by remember(file.id){mutableIntStateOf(0)};var position by remember(file.id){mutableIntStateOf(0)}
    DisposableEffect(file.id) {
        val media=MediaPlayer()
        try {
            val physical=repository.mediaFile(file);check(physical.isFile){"附件文件缺失 / 添付ファイルがありません"}
            media.setDataSource(physical.path)
            media.setOnPreparedListener{duration=it.duration;player=it}
            media.setOnCompletionListener{playing=false;position=duration}
            media.setOnErrorListener{_,_,_->playing=false;player=null;onFailure("无法解码这个音频 / この音声を再生できません");true}
            media.prepareAsync()
        }catch(e:Exception){onFailure(e.message?:"无法打开音频 / 音声を開けません")}
        onDispose{media.release();player=null}
    }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_PAUSE){if(playing){player?.pause();playing=false}}
    LaunchedEffect(playing){while(playing){position=player?.currentPosition?:0;delay(250)}}
    ThemedAudio(file.originalName,file.size,position,duration,playing,player!=null,{
        val media=player!!
        if(playing)media.pause()else {if(position>=duration){media.seekTo(0);position=0};media.start()}
        playing=!playing
    },{position=it;player?.seekTo(it)})
}

@Composable internal fun ReceiptsScreen(s:BookSnapshot,vm:AppViewModel,onRecord:(EventEntity)->Unit) {
    var tab by rememberSaveable(s.book.id){mutableStateOf("UNSORTED")}
    var preview by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    val available=s.receipts.filter{r->s.visibleEvents.any{it.id==r.eventId}&&s.receiptImages.any{it.receiptId==r.id&&it.revision==r.revision}}
    ReceiptInboxCover(available.count{it.status=="UNSORTED"})
    YText(word("保存原图，查看并关联原记账。小票不会自动识别或生成消费。","原画像を保存し、元の記録を確認。自動認識や支出の生成は行いません。"),11,color=LocalYohaku.current.muted)
    ReceiptInboxTabs(tab){tab=it}
    val records=available.filter{tab=="ALL"||it.status==tab}
    if(records.isEmpty())YEmpty(if(available.isEmpty())word("在记账表单中添加小票原图。","記録フォームでレシート原画像を追加しましょう。")else if(tab=="SORTED")word("暂无已整理的小票。原图可在待整理中查看。","整理済みレシートはありません。原画像は未整理タブで確認できます。")else word("没有待整理的小票。","未整理のレシートはありません。"))
    records.forEachIndexed{index,receipt->
        val event=s.visibleEvents.first{it.id==receipt.eventId}
        val images=s.entryReceiptImages(event.id)
        ReceiptInboxCard(receipt.id,index,event.merchant.ifBlank{word("这张小票","このレシート")},event.date+" · "+s.path(event.categoryId)+" · "+Money(Currency.valueOf(event.currency),event.amountMinor).display()+" · "+word("第${receipt.revision}版","第${receipt.revision}版"),if(receipt.status=="SORTED")word("已整理","整理済み")else word("待整理","未整理"),receipt.status=="SORTED",images.size,{onRecord(event)}) {
            images.forEachIndexed{imageIndex,file->ReceiptThumbnailFrame(file.id,imageIndex,{preview=file.id}){MediaThumbnail(vm.repository,file,Modifier.fillMaxSize(),crop=true)}}
        }
    }
    YText(word("原图导出与商品导入尚未提供。原图可在原记录中增减并保留修订。","画像の書出し・商品取込は未提供です。元の記録で画像を増減し、改訂を保持できます。"),11,color=LocalYohaku.current.muted)
    preview?.let{id->s.files.find{it.id==id}?.let{MediaPreview(vm.repository,it,{preview=null},s.receipts.find{r->s.entryReceiptImages(r.eventId).any{f->f.id==id}}?.let{r->s.entryReceiptImages(r.eventId)}?:listOf(it))}}
}
