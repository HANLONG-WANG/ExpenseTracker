package com.yohaku.expensetracker.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.model.EntryDraft
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
/** Copy one source at a time; reported metadata never substitutes for the byte count. */
suspend fun LedgerRepository.importMedia(bookId:String,session:String,name:String,mime:String,receipt:Boolean=false,open:()->InputStream):FileBlobEntity = mediaWrites.withLock {
    require(dao.book(bookId)!=null)
    val draft=dao.storedDraft(bookId)?.let{json.decodeFromString<EntryDraft>(it.json)}?:error("请先打开记账表单 / 記録フォームを開いてください")
    require(draft.mediaSession==session){"表单已变化，请重新选择文件 / フォームが変わりました。ファイルを選び直してください"}
    require(!receipt||mime.startsWith("image/")){"小票请选择图片 / レシートは画像を選択してください"}
    val fileId=id();val relative="$bookId/$fileId";val root=mediaRoot?:error("文件目录未配置 / 保存先がありません")
    val target=File(root,relative);target.parentFile!!.mkdirs();val partial=File(target.path+".part")
    var bytes=0L;val digest=MessageDigest.getInstance("SHA-256")
    try {
        open().use{input->partial.outputStream().use{output->val buffer=ByteArray(65536);while(true){val count=input.read(buffer);if(count<0)break;if(count==0)continue;bytes=Math.addExact(bytes,count.toLong());require(bytes<MEDIA_FILE_LIMIT){"每个文件必须小于100 MB / 各ファイルは100 MB未満にしてください"};digest.update(buffer,0,count);output.write(buffer,0,count)}}}
        check(partial.renameTo(target)){"无法完成文件保存 / ファイルを保存できません"}
        val blob=FileBlobEntity(bookId,fileId,relative,name.ifBlank{"file"},mime.ifBlank{"application/octet-stream"},bytes,digest.digest().joinToString(""){"%02x".format(it)})
        db.withTransaction {
            val current=dao.storedDraft(bookId)?.let{json.decodeFromString<EntryDraft>(it.json)}
            require(current?.mediaSession==session){"表单已关闭 / フォームが閉じられました"}
            dao.putFile(blob);dao.stageMedia(DraftMediaEntity(bookId,fileId,session))
            saveDraft(bookId,if(receipt)current.copy(mediaInitialized=true,receiptFileIds=current.receiptFileIds+fileId)else current.copy(mediaInitialized=true,attachmentIds=current.attachmentIds+fileId))
        }
        blob
    }catch(e:Exception){partial.delete();target.delete();throw e}
}
suspend fun LedgerRepository.importUri(context:Context,bookId:String,session:String,uri:Uri,receipt:Boolean):FileBlobEntity {
    var name=uri.lastPathSegment.orEmpty()
    context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{cursor->if(cursor.moveToFirst())name=cursor.getString(0).orEmpty()}
    val mime=context.contentResolver.getType(uri)?:"application/octet-stream"
    return importMedia(bookId,session,name,mime,receipt){context.contentResolver.openInputStream(uri)?:error("无法读取所选文件 / 選択したファイルを読み込めません")}
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
    if(mediaRoot==null)return@withTransaction
    dao.allFiles().forEach{file->if(!dao.fileReferenced(file.bookId,file.id)) {
        val physical=mediaFile(file)
        check(!physical.exists()||physical.delete()){ "无法清理未引用的附件 / 未参照ファイルを削除できません" }
        dao.deleteFile(file.bookId,file.id)
    }}
    val known=dao.allFiles().map{mediaFile(it).canonicalPath}.toSet()
    mediaRoot.walkTopDown().filter{it.isFile&&it.canonicalPath !in known}.forEach{check(it.delete()){ "无法清理未完成的文件 / 未完了ファイルを削除できません" }}
}

}
