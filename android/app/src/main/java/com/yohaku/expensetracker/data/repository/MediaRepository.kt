package com.yohaku.expensetracker.data.repository

import android.content.Context
import android.graphics.*
import android.os.Build
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.security.DigestOutputStream
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

const val MEDIA_FILE_LIMIT=100_000_000L
fun LedgerRepository.mediaFile(blob:FileBlobEntity):File {
    val root=mediaRoot?:error("文件目录未配置 / 保存先が設定されていません")
    val file=File(root,blob.path)
    require(file.canonicalPath.startsWith(root.canonicalPath+File.separator))
    return file
}
fun BookSnapshot.entryAttachments(eventId:String)=attachments.filter{it.eventId==eventId}.map{link->files.first{it.id==link.fileId}}
fun BookSnapshot.entryReceiptImages(eventId:String):List<FileBlobEntity> {
    val receipt=receipts.find{it.eventId==eventId}?:return emptyList()
    return receiptImages.filter{it.receiptId==receipt.id&&it.revision==receipt.revision}.sortedBy{it.position}.map{image->files.first{it.id==image.fileId}}
}
data class MediaImportResult(val blob:FileBlobEntity,val optimizationFailed:Boolean=false)
/** One picked file; [open] is called once while the media lock is held. */
class MediaSource(val name:String,val mime:String,val open:()->InputStream)
data class MediaBatchResult(val success:Int,val fallback:Int,val failed:Int,val firstFailure:String?)
suspend fun LedgerRepository.importMedia(bookId:String,session:String,name:String,mime:String,receipt:Boolean=false,preserveOriginal:Boolean=false,open:()->InputStream):FileBlobEntity = importMediaResult(bookId,session,name,mime,receipt,preserveOriginal,open).blob
/** File workflow: call outside database transactions; file creation cannot be rolled back by Room. */
suspend fun LedgerRepository.importMediaResult(bookId:String,session:String,name:String,mime:String,receipt:Boolean=false,preserveOriginal:Boolean=false,open:()->InputStream):MediaImportResult = mediaWrites.withLock {
    require(dao.book(bookId)!=null)
    val draft=dao.storedDraft(bookId)?.let{json.decodeFromString<EntryDraft>(it.json)}?:error("请先打开记账表单 / 記録フォームを開いてください")
    require(draft.mediaSession==session){"表单已变化，请重新选择文件 / フォームが変わりました。ファイルを選び直してください"}
    val stored=storeMedia(bookId,name,mime,receipt,preserveOriginal,open)
    try { db.withTransaction{commitImportedMediaInTransaction(stored.blob,session,receipt)} }catch(e:Throwable){stored.file.delete();throw e}
    MediaImportResult(stored.blob,stored.optimizationFailed)
}
/**
 * Gives a saved record without receipt images its first receipt revision. Files are stored one by one (each may fail alone),
 * then committed together, so a batch yields one receipt revision. The record itself is not revised.
 */
suspend fun LedgerRepository.importRecordReceipts(bookId:String,eventId:String,sources:List<MediaSource>,preserveOriginal:Boolean,onProgress:(Int)->Unit={}):MediaBatchResult = mediaWrites.withLock {
    suspend fun receiptWithoutImages():ReceiptEntity? {
        val receipt=dao.receipt(bookId,eventId)
        require(receipt==null||dao.receiptImages(bookId,receipt.id,receipt.revision).isEmpty()){"这笔记录已有小票，请在编辑中修改 / この記録にはレシートがあります。編集から変更してください"}
        return receipt
    }
    require(dao.event(bookId,eventId)?.let{it.deletedAt==null}==true){"记录已删除 / 記録は削除されています"}
    receiptWithoutImages()
    val stored=mutableListOf<StoredMedia>();var failed=0;var firstFailure:String?=null
    try {
        sources.forEachIndexed{index,source->
            currentCoroutineContext().ensureActive();onProgress(index+1)
            try{stored+=storeMedia(bookId,source.name,source.mime,true,preserveOriginal,source.open)}
            catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){failed++;if(firstFailure==null)firstFailure=e.message?:"无法导入文件 / ファイルを取り込めません"}
        }
        if(stored.isNotEmpty())db.withTransaction {
            val receipt=receiptWithoutImages()
            val next=ReceiptEntity(bookId,receipt?.id?:id(),eventId,(receipt?.revision?:0)+1)
            stored.forEach{dao.putFile(it.blob)}
            dao.putReceipt(next)
            dao.putReceiptImages(stored.mapIndexed{index,it->ReceiptImageEntity(bookId,next.id,next.revision,index,it.blob.id)})
        }
    }catch(e:Throwable){stored.forEach{it.file.delete()};throw e}
    MediaBatchResult(stored.size,stored.count{it.optimizationFailed},failed,firstFailure)
}
private class StoredMedia(val blob:FileBlobEntity,val file:File,val optimizationFailed:Boolean)
/** Caller must hold mediaWrites and stay outside transactions; it commits [StoredMedia.blob] or deletes [StoredMedia.file]. */
private suspend fun LedgerRepository.storeMedia(bookId:String,name:String,mime:String,receipt:Boolean,preserveOriginal:Boolean,open:()->InputStream):StoredMedia {
    require(!receipt||mime.startsWith("image/")){"小票请选择图片 / レシートは画像を選択してください"}
    val fileId=id();val relative="$bookId/$fileId";val root=mediaRoot?:error("文件目录未配置 / 保存先がありません")
    val target=File(root,relative);target.parentFile!!.mkdirs();val partial=File(target.path+".part");val jpeg=File(target.path+".jpg.part")
    var bytes=0L;val digest=MessageDigest.getInstance("SHA-256");var stored=false
    try {
        val coroutine=currentCoroutineContext()
        open().use{input->partial.outputStream().use{output->val buffer=ByteArray(65536);while(true){coroutine.ensureActive();val count=input.read(buffer);if(count<0)break;if(count==0)continue;bytes=Math.addExact(bytes,count.toLong());require(bytes<MEDIA_FILE_LIMIT){"每个文件必须小于100 MB / 各ファイルは100 MB未満にしてください"};digest.update(buffer,0,count);output.write(buffer,0,count)}}}
        var source=partial;var storedName=name.ifBlank{"file"};var storedMime=mime.ifBlank{"application/octet-stream"};var optimizationFailed=false
        if(!preserveOriginal&&mime.startsWith("image/")) {
            val bitmap=decodePhotoForStorage(partial,receipt)
            if(bitmap==null)optimizationFailed=true else {
                try { coroutine.ensureActive();digest.reset();DigestOutputStream(jpeg.outputStream(),digest).use{check(bitmap.compress(Bitmap.CompressFormat.JPEG,if(receipt)90 else 85,it)){"无法保存优化图片 / 最適化した画像を保存できません"}} }finally{bitmap.recycle()}
                bytes=jpeg.length();require(bytes<MEDIA_FILE_LIMIT){"每个文件必须小于100 MB / 各ファイルは100 MB未満にしてください"}
                source=jpeg;storedName=jpegFileName(storedName);storedMime="image/jpeg"
            }
        }
        coroutine.ensureActive()
        check(source.renameTo(target)){"无法完成文件保存 / ファイルを保存できません"}
        stored=true
        return StoredMedia(FileBlobEntity(bookId,fileId,relative,storedName,storedMime,bytes,digest.digest().joinToString(""){"%02x".format(it)}),target,optimizationFailed)
    }finally{partial.delete();jpeg.delete();if(!stored)target.delete()}
}

/** Caller must hold mediaWrites and be inside a transaction; it owns the prepared file's lifecycle. */
internal suspend fun LedgerRepository.commitImportedMediaInTransaction(blob:FileBlobEntity,session:String,receipt:Boolean) {
    val bookId=blob.bookId;val fileId=blob.id
    val current=dao.storedDraft(bookId)?.let{json.decodeFromString<EntryDraft>(it.json)}
    require(current?.mediaSession==session){"表单已关闭 / フォームが閉じられました"}
    dao.putFile(blob);dao.stageMedia(DraftMediaEntity(bookId,fileId,session))
    saveDraft(bookId,if(receipt)current.copy(mediaInitialized=true,receiptFileIds=current.receiptFileIds+fileId)else current.copy(mediaInitialized=true,attachmentIds=current.attachmentIds+fileId))
}
private fun decodePhotoForStorage(file:File,receipt:Boolean):Bitmap? {
    val decoded=try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)){decoder,info,_->
            val size=optimizedPhotoSize(info.size.width,info.size.height,receipt)
            decoder.setTargetSize(size.width,size.height);decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE;decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }
    }catch(e:ImageDecoder.DecodeException){if(e.error==ImageDecoder.DecodeException.SOURCE_EXCEPTION)throw e;return null}catch(_:IOException){return null}
    if(Build.VERSION.SDK_INT>=34)decoded.setGainmap(null)
    if(!decoded.hasAlpha())return decoded
    try {
        val opaque=Bitmap.createBitmap(decoded.width,decoded.height,Bitmap.Config.ARGB_8888)
        Canvas(opaque).apply{drawColor(Color.WHITE);drawBitmap(decoded,0f,0f,null)};opaque.setHasAlpha(false)
        return opaque
    }finally{decoded.recycle()}
}
fun mediaSource(context:Context,uri:Uri):MediaSource {
    var name=uri.lastPathSegment.orEmpty()
    context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{cursor->if(cursor.moveToFirst())name=cursor.getString(0).orEmpty()}
    val mime=context.contentResolver.getType(uri)?:"application/octet-stream"
    return MediaSource(name,mime){context.contentResolver.openInputStream(uri)?:error("无法读取所选文件 / 選択したファイルを読み込めません")}
}
suspend fun LedgerRepository.importUri(context:Context,bookId:String,session:String,uri:Uri,receipt:Boolean,preserveOriginal:Boolean=false):MediaImportResult {
    val source=mediaSource(context,uri)
    return importMediaResult(bookId,session,source.name,source.mime,receipt,preserveOriginal,source.open)
}
data class PreparedMedia(val session:String,val attachments:List<String>,val receiptImages:List<String>)
internal suspend fun LedgerRepository.withMedia(change:PreparedLedgerChange,draft:EntryDraft):PreparedLedgerChange {
    if(!draft.mediaInitialized)return change
    val bookId=change.event.bookId
    require(draft.attachmentIds.distinct()==draft.attachmentIds&&draft.receiptFileIds.distinct()==draft.receiptFileIds)
    val staged=dao.stagedMedia(bookId,draft.mediaSession).map{it.fileId}.toSet()
    val existing=dao.attachments(bookId,change.event.id).map{it.fileId}.toSet()
    val receipt=dao.receipt(bookId,change.event.id)
    val oldImages=receipt?.let{dao.receiptImages(bookId,it.id,it.revision).map{image->image.fileId}.toSet()}.orEmpty()
    draft.attachmentIds.forEach{id->require(id in staged||id in existing);require(dao.file(bookId,id)!=null)}
    draft.receiptFileIds.forEach{id->require(id in staged||id in oldImages);require(dao.file(bookId,id)?.mime?.startsWith("image/")==true)}
    return change.copy(media=PreparedMedia(draft.mediaSession,draft.attachmentIds,draft.receiptFileIds))
}
internal suspend fun LedgerRepository.writeMedia(bookId:String,eventId:String,media:PreparedMedia) {
    dao.clearAttachments(bookId,eventId)
    dao.putAttachments(media.attachments.map{AttachmentLinkEntity(bookId,eventId+":attachment:"+it,eventId,it)})
    val old=dao.receipt(bookId,eventId)
    val prior=old?.let{dao.receiptImages(bookId,it.id,it.revision).map{image->image.fileId}}.orEmpty()
    if(prior!=media.receiptImages) {
        val receipt=ReceiptEntity(bookId,old?.id?:id(),eventId,(old?.revision?:0)+1)
        dao.putReceipt(receipt)
        dao.putReceiptImages(media.receiptImages.mapIndexed{index,fileId->ReceiptImageEntity(bookId,receipt.id,receipt.revision,index,fileId)})
    }
    dao.clearStagedMedia(bookId,media.session)
}
/** A file is removed only after the database proves there is no event, receipt or draft reference. */
suspend fun LedgerRepository.collectUnusedMedia()=mediaWrites.withLock { db.withTransaction {
    collectUnusedMediaInTransaction()
} }

/** Caller must hold mediaWrites and be inside a transaction. Physical deletion is not rollback-safe. */
internal suspend fun LedgerRepository.collectUnusedMediaInTransaction() {
    if(mediaRoot==null)return
    val files=dao.allFiles()
    val referencedPaths=files.filter{dao.fileReferenced(it.bookId,it.id)}.map{mediaFile(it).canonicalPath}.toSet()
    files.forEach{file->if(!dao.fileReferenced(file.bookId,file.id)) {
        val physical=mediaFile(file)
        check(physical.canonicalPath in referencedPaths||!physical.exists()||physical.delete()){ "无法清理未引用的附件 / 未参照ファイルを削除できません" }
        dao.deleteFile(file.bookId,file.id)
    }}
    val known=dao.allFiles().map{mediaFile(it).canonicalPath}.toSet()
    mediaRoot.walkTopDown().filter{it.isFile&&it.canonicalPath !in known}.forEach{check(it.delete()){ "无法清理未完成的文件 / 未完了ファイルを削除できません" }}
}
