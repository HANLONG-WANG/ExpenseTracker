package com.yohaku.expensetracker.data.command

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import com.yohaku.expensetracker.data.backup.BackupService
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.EntryDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest

internal fun attachmentTarget(s:BookSnapshot,id:String,owned:Set<String> = emptySet()):JsonObject=buildJsonObject {
    put("entry",encoded(s.visibleEvents.single{it.id==id}))
    put("details",encoded(s.entryDetails(id).let{it.copy(attachmentIds=it.attachmentIds.filterNot{f->f in owned})}))
}
/** One coordinator for the existing command_effects outbox. The process mutex only serializes
 * runners; durability comes from DataStore receipts / atomic Room completion / fixed output URI.
 * RUNNING is recoverable without leases because no runner survives process death. */
class CommandFollowupCoordinator internal constructor(private val commands:CommandRepository,
    private val sourceRoot:File?,private val context:Context?,private val afterWrite:(suspend (CommandEffectEntity)->Unit)?) {
    private val ledger=commands.ledger
    private val db=ledger.db
    private val dao=db.commandDao()
    companion object {private val runners=Mutex()}
    fun payload(row:CommandEffectEntity):CommandFollowup=readCommandDocument(row.payloadJson)
    suspend fun resume()=withContext(Dispatchers.IO){runners.withLock{resumeLocked()}}
    private suspend fun resumeLocked() {
        for(row in dao.pendingEffects()) {
            val prior=dao.planEffects(row.planId).takeWhile{it.id!=row.id}
            // A failed automatic predecessor may supply the value this step expects.
            if(prior.any{it.status=="RETRY"})continue
            perform(row.id)
        }
    }
    suspend fun retry(id:String)=withContext(Dispatchers.IO){runners.withLock {
        val row=requireNotNull(dao.effect(id));check(row.status=="RETRY")
        check(dao.planEffects(row.planId).takeWhile{it.id!=row.id}.none{it.status=="RETRY"})
        dao.updateEffect(row.copy(status="PENDING",lastError=null))
        perform(id);resumeLocked()
        dao.effect(id)?.status!="RETRY"
    }}
    private suspend fun perform(id:String) {
        val row=requireNotNull(dao.effect(id));if(row.status !in setOf("PENDING","RUNNING"))return
        val running=row.copy(status="RUNNING",attempts=row.attempts+1,lastError=null)
        dao.updateEffect(running)
        val f=payload(running)
        try {
            when(f.kind) {
                "PREFERENCE"->ledger.mediaWrites.withLock {
                    val key=f.input.getValue("key").jsonPrimitive.content
                    val preferences=requireNotNull(commands.preferences){"偏好存储未配置 / 設定ストアが未設定です"}
                    if(!preferences.commandPreferenceApplied(f.id)&&key=="activeBook"&&f.after!=JsonNull){val book=requireNotNull(ledger.dao.book(f.after.jsonPrimitive.content));check(book.deletedAt==null)}
                    preferences.applyCommandPreference(f.id,f.bookId,key,f.before,f.after)
                    afterWrite?.invoke(running)
                    dao.updateEffect(running.copy(status="DONE"))
                }
                "ATTACHMENT"->attach(running,f)
                "DRAFT"->ledger.mediaWrites.withLock {db.withTransaction {
                    require(ledger.dao.storedDraft(f.bookId)==null){"已有记账草稿，请先在本机处理后重试 / 既存の下書きを処理してから再試行してください"}
                    ledger.saveDraft(f.bookId,commandJson.decodeFromJsonElement<EntryDraft>(f.input))
                    dao.updateEffect(running.copy(status="WAITING"))
                }}
                "PERMISSION"->dao.updateEffect(running.copy(status="WAITING"))
                "EXPORT"->if(f.input["treeUri"]==null)dao.updateEffect(running.copy(status="WAITING"))else export(running,f,recovering=row.status=="RUNNING")
                else->error("Unknown local follow-up: ${f.kind}")
            }
        }catch(e:CancellationException){throw e}
        catch(e:Exception) {
            // A callback/test interruption AFTER an atomic commit must not revive a finished step.
            val current=requireNotNull(dao.effect(id))
            if(current.status !in setOf("DONE","WAITING"))dao.updateEffect(current.copy(status="RETRY",lastError=e.message?:e.javaClass.simpleName))
        }
    }
    private suspend fun attach(row:CommandEffectEntity,f:CommandFollowup)=ledger.mediaWrites.withLock {
        val input=f.input;fun text(key:String)=input.getValue(key).jsonPrimitive.content
        val eventId=text("entryId");val fileId=text("fileId");val localId=text("localId")
        val source=ledger.db.cloudDao().transfers(localId).single{it.position==input.getValue("position").jsonPrimitive.int}
        require(source.fileId!=null&&source.uploadedAt!=null)
        require(source.name==text("name")&&source.sha256==text("checksum")&&source.size==input.getValue("size").jsonPrimitive.long&&source.mime==text("mime"))
        val root=requireNotNull(sourceRoot){"源文件目录未配置 / 元ファイルの保存先が未設定です"}.canonicalFile
        val expected=File(File(root,localId),"source-${source.position}").canonicalFile
        require(expected.parentFile.parentFile==root&&File(source.path).canonicalFile==expected)
        require(expected.isFile&&expected.length()==source.size&&fileHash(expected)==source.sha256){"本任务源文件缺失或已变化 / このタスクの元ファイルがないか変更されています"}
        val blob=FileBlobEntity(f.bookId,fileId,"${f.bookId}/$fileId",source.name,source.mime,source.size,source.sha256)
        val target=ledger.mediaFile(blob);target.parentFile!!.mkdirs()
        if(!target.isFile||target.length()!=source.size||fileHash(target)!=source.sha256) {
            val partial=File(target.path+".part")
            expected.inputStream().use{inputStream->partial.outputStream().use{output->
                val buffer=ByteArray(65536)
                while(true){currentCoroutineContext().ensureActive();val n=inputStream.read(buffer);if(n<0)break;output.write(buffer,0,n)}
            }}
            check(fileHash(partial)==source.sha256&&partial.renameTo(target)){"无法保存源文件附件 / 元ファイルを添付として保存できません"}
        }
        db.withTransaction {
            val plan=commands.plan(row.planId);check(plan.status=="EXECUTED")
            val execution=readCommandExecution(requireNotNull(plan.executionJson));val undo=requireNotNull(execution.undo)
            val ownFiles=dao.planEffects(row.planId).filter{it.status=="DONE"}.map(::payload).filter{it.kind=="ATTACHMENT"&&it.input["entryId"]==JsonPrimitive(eventId)}.map{it.input.getValue("fileId").jsonPrimitive.content}.toSet()
            val s=ledger.snapshot(requireNotNull(ledger.dao.book(f.bookId))).first()
            check(attachmentTarget(s,eventId,ownFiles)==f.before){"附件目标在提交后已变化，请恢复原记录后重试 / 保存後に添付先が変わりました。元の記録に戻して再試行してください"}
            val journal=CommandUndoJournal(ledger,f.bookId)
            val before=journal.snapshot()
            val existing=ledger.dao.file(f.bookId,fileId)
            check(existing==null||existing==blob)
            if(existing==null)ledger.dao.putFile(blob)
            val link=AttachmentLinkEntity(f.bookId,"$eventId:attachment:$fileId",eventId,fileId)
            check(ledger.dao.attachments(f.bookId,eventId).none{it.fileId==fileId})
            ledger.dao.putAttachments(listOf(link))
            val changes=journal.changes(before)
            val s2=ledger.snapshot(requireNotNull(ledger.dao.book(f.bookId))).first()
            val rows=undo.rows+changes
            val guards=undo.guards.filterNot{it.type=="entryDetails"&&it.key==eventId}+UndoGuard("entryDetails",eventId,encoded(s2.entryDetails(eventId)))+
                if(undo.guards.any{it.type=="entry"&&it.key==eventId})emptyList()else listOf(UndoGuard("entry",eventId,encoded(s2.visibleEvents.single{it.id==eventId})))
            val steps=if(undo.steps.any{it.command=="local.restoreExecution"})undo.steps else listOf(CommandStep("restore_objects","local.restoreExecution",1,buildJsonObject{}))+undo.steps
            val updated=execution.copy(undo=undo.copy(rows=rows,guards=guards,steps=steps,removals=journal.removals(rows)))
            dao.update(plan.copy(executionJson=storeCommandJson(commandJson.encodeToString(updated))))
            dao.updateEffect(row.copy(status="DONE"))
        }
        afterWrite?.invoke(row)
    }
    /** Called only from a user's picker result. Save its durable URI before touching external bytes. */
    suspend fun selectExportDestination(id:String,uri:Uri)=withContext(Dispatchers.IO){runners.withLock {
        val row=requireNotNull(dao.effect(id));val f=payload(row)
        check(row.status=="WAITING"&&f.kind=="EXPORT"&&f.input["treeUri"]==null)
        val app=requireNotNull(context)
        app.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val updated=f.copy(input=JsonObject(f.input+("treeUri" to JsonPrimitive(uri.toString()))))
        dao.updateEffect(row.copy(payloadJson=storeCommandJson(commandJson.encodeToString(updated)),status="PENDING"))
        perform(id);resumeLocked()
    }}
    private suspend fun export(row:CommandEffectEntity,f:CommandFollowup,recovering:Boolean) {
        val app=requireNotNull(context);val preferences=requireNotNull(commands.preferences)
        val stage=File(app.filesDir,"command-exports").apply{mkdirs()}
        val prepared=File(stage,sha256(f.id)+".yohaku")
        // A stable prepared archive and URI make a replay overwrite only this one owned output.
        if(!prepared.isFile) {
            val partial=File(prepared.path+".part")
            partial.outputStream().use{output->
                if(f.input["exportType"]?.jsonPrimitive?.content=="EXPORT")BackupService(app,ledger,preferences).export(output,completedCommandEffectId=f.id)
                else output.write(f.input.getValue("content").jsonPrimitive.content.toByteArray(Charsets.UTF_8))
            }
            check(partial.renameTo(prepared))
        }
        var created=false
        val uri=f.input["outputUri"]?.jsonPrimitive?.content?.let(Uri::parse)?:run {
            val tree=Uri.parse(f.input.getValue("treeUri").jsonPrimitive.content)
            val parent=DocumentsContract.getTreeDocumentId(tree)
            val base=f.input.getValue("name").jsonPrimitive.content
            // Unique per effect; a crash between provider creation and Room persistence is
            // reconciled by this exact reserved name, never by creating a second document.
            val name=base.substringBeforeLast('.')+"-"+sha256(f.id).take(16)+"."+base.substringAfterLast('.')
            val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent)
            val existing=requireNotNull(app.contentResolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)){"无法检查导出目录，请重试 / 出力先フォルダを確認できません。再試行してください"}.use{cursor->
                val matches=mutableListOf<Uri>()
                while(cursor.moveToNext())if(cursor.getString(1)==name)matches+=DocumentsContract.buildDocumentUriUsingTree(tree,cursor.getString(0))
                check(matches.size<=1){"目标目录存在重复导出名称，请在本机整理后重试 / 保存先に同名の出力が複数あります。整理後に再試行してください"}
                matches.singleOrNull()
            }
            val document=existing?:requireNotNull(DocumentsContract.createDocument(app.contentResolver,DocumentsContract.buildDocumentUriUsingTree(tree,parent),f.input.getValue("mime").jsonPrimitive.content,name)).also{created=true}
            val saved=f.copy(input=JsonObject(f.input+("outputUri" to JsonPrimitive(document.toString()))))
            dao.updateEffect(requireNotNull(dao.effect(row.id)).copy(payloadJson=storeCommandJson(commandJson.encodeToString(saved))))
            document
        }
        val hash=fileHash(prepared)
        val already=try{app.contentResolver.openInputStream(uri)?.use{streamHash(it)}==hash}catch(_:java.io.FileNotFoundException){false}
        if(recovering&&!created&&!already)error("导出目标未完成或已被改动；请先检查，重试会重写同一个文件 / 出力先が未完了または変更されています。確認してください。再試行は同じファイルを上書きします")
        if(!already)app.contentResolver.openOutputStream(uri,"wt")!!.use{output->prepared.inputStream().use{it.copyTo(output)}}
        check(app.contentResolver.openInputStream(uri)?.use{streamHash(it)}==hash){"导出文件校验失败，可重试同一目标 / 出力ファイルの検証に失敗しました。同じ保存先へ再試行できます"}
        afterWrite?.invoke(row)
        dao.updateEffect(requireNotNull(dao.effect(row.id)).copy(status="DONE"))
        prepared.delete() // Only this operation's temporary archive; never the exported document.
    }
    /** Completing a handoff is NOT permission to submit its draft. The normal editor owns save. */
    suspend fun completeInteraction(id:String)=runners.withLock {
        val row=requireNotNull(dao.effect(id));val f=payload(row)
        check(row.status=="WAITING"&&f.kind in setOf("DRAFT","PERMISSION"))
        dao.updateEffect(row.copy(status="DONE"))
        resumeLocked()
    }
}
private fun fileHash(file:File)=file.inputStream().use(::streamHash)
private fun streamHash(input:java.io.InputStream):String {
    val hash=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
    while(true){val n=input.read(buffer);if(n<0)break;hash.update(buffer,0,n)}
    return hash.digest().joinToString(""){"%02x".format(it)}
}
