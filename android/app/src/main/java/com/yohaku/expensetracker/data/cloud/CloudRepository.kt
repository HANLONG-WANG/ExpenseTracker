package com.yohaku.expensetracker.data.cloud

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.yohaku.expensetracker.data.files.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.platform.CloudAckWorker
import com.yohaku.expensetracker.platform.CloudTransferWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

internal class CloudBlockedException(message:String):IllegalStateException(message)

internal fun cloudRetryable(error:Exception):Boolean = when(error) {
    is CloudHttpException -> error.status == 429 || error.status >= 500
    is IOException -> true
    else -> false
}

class CloudRepository(private val context:Context, private val ledger:LedgerRepository,
    private val store:CloudCredentials=CloudConnectionStore(context),
    private val apiFactory:(CloudConnectionConfig)->CloudApi={ CloudApi(it.baseUrl,it) }) {
    private val db=ledger.db
    private val dao=db.cloudDao()
    private val locks=ConcurrentHashMap<String,Mutex>()
    private val connectionLock=Mutex()
    private val root=File(context.filesDir,"cloud")
    private val confirmedStatuses=setOf("queued","analyzing","review","ready")
    val connection:StateFlow<CloudConnectionInfo?> = store.connection
    fun parseConfig(raw:String):CloudConnectionConfig = parseCloudConnectionConfig(raw,
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
    suspend fun bind(config:CloudConnectionConfig) = connectionLock.withLock {
        val validated=parseConfig(cloudJson.encodeToString(config))
        store.save(validated)
        authenticated(apiFactory(validated)) { it.types() }
        store.markBound()
        resumePending()
    }
    suspend fun testConnection() = connectionLock.withLock {
        val config=store.credentials() ?: error("请先导入连接配置 / 接続設定を読み込んでください")
        authenticated(apiFactory(config)) { it.types() }; Unit
    }
    suspend fun unbind() = connectionLock.withLock { store.clear() }
    fun tasks(bookId:String):Flow<List<CloudTaskView>> = dao.tasks(bookId).map { rows ->
        rows.map { cloudTaskView(it.task,it.transfers,it.imports,it.acks) }
    }
    private suspend fun credentials():CloudConnectionConfig {
        val config=store.credentials() ?: error("请先连接云端 / クラウドに接続してください")
        check(connection.value?.bound == true && connection.value?.needsReconnect == false) { "请重新连接云端 / クラウドに再接続してください" }
        return config
    }
    private suspend fun api(task:CloudTaskEntity):CloudApi {
        val config=credentials()
        if(config.baseUrl != task.origin)throw CloudBlockedException("任务绑定的站点不同，请连接原站点 / タスクの接続先が異なります。元のサイトに接続してください")
        return apiFactory(config)
    }
    private suspend fun <T> authenticated(api:CloudApi, action:suspend (CloudApi)->T):T = try { action(api) }
        catch(e:CloudHttpException) { if(e.status==401)store.markNeedsReconnect(); throw e }
    private suspend fun row(localId:String) = dao.task(localId) ?: error("云任务不存在 / クラウドタスクがありません")
    private fun now()=ledger.clock.millis()
    private fun id()=UUID.randomUUID().toString()
    private suspend fun <T> locked(localId:String, action:suspend ()->T):T = withContext(Dispatchers.IO) {
        locks.getOrPut(localId) { Mutex() }.withLock { action() }
    }
    private suspend fun remember(localId:String, remote:CloudTask) {
        val task=row(localId)
        check(task.taskId == remote.id && task.type == remote.type) { "云任务关联不一致 / クラウドタスクの関連が一致しません" }
        dao.updateTask(task.copy(remoteStatus=remote.status,inputRevision=remote.inputRevision,
            remoteJson=cloudJson.encodeToString(remote),lastError=null,updatedAt=now()))
    }
    internal suspend fun recordFailure(localId:String, error:Exception, block:Boolean=true) {
        if(error is CloudHttpException && error.status==401)store.markNeedsReconnect()
        val task=dao.task(localId) ?: return
        dao.updateTask(task.copy(stage=if((block && !cloudRetryable(error)) || error is CloudBlockedException || error is CloudHttpException && error.status==401)"BLOCKED" else task.stage,
            lastError=error.message ?: "云端操作失败 / クラウド操作に失敗しました",updatedAt=now()))
    }
    private suspend fun <T> taskOperation(localId:String, block:Boolean=false, action:suspend ()->T):T = locked(localId) {
        try { action() } catch(e:CancellationException) { throw e }
        catch(e:Exception) { recordFailure(localId,e,block); throw e }
    }
    private fun createBody(type:String,title:String,instructions:String,context:JsonObject):String {
        require(title.trim().length in 1..150) { "标题须为1至150个字符 / タイトルは1～150文字にしてください" }
        require(instructions.length<=20000) { "说明不能超过20000个字符 / 説明は20000文字以内にしてください" }
        return buildJsonObject { put("type",type); put("title",title.trim()); put("instructions",instructions); put("context",context) }.toString()
    }
    private suspend fun prepare(bookId:String,type:String,title:String,instructions:String,accountId:String?=null,
        bundleId:String?=null,taskContext:JsonObject, files:suspend (String,File)->List<CloudTransferEntity>):String = withContext(Dispatchers.IO) {
        val config=credentials()
        check(ledger.dao.book(bookId)!=null) { "账本不存在 / 帳簿がありません" }
        createBody(type,title,instructions,taskContext)
        val localId=id(); val directory=File(root,localId)
        check(directory.mkdirs()) { "无法创建云任务目录 / タスクの保存先を作成できません" }
        var persisted=false
        try {
            val transfers=files(localId,directory)
            require(transfers.size in 1..200) { "每个任务需要1至200份文件 / タスクには1～200個のファイルが必要です" }
            val request=createBody(type,title,instructions,if(type=="receipt") JsonObject(taskContext + ("parts" to JsonPrimitive(transfers.size))) else taskContext)
            val timestamp=now()
            db.withTransaction {
                dao.insertTask(CloudTaskEntity(localId,bookId,type,title.trim(),accountId,bundleId,config.baseUrl,
                    localId,request,createdAt=timestamp,updatedAt=timestamp))
                dao.insertTransfers(transfers)
            }
            persisted=true
            enqueue(localId); localId
        } finally { if(!persisted)directory.deleteRecursively() }
    }
    private fun uniqueName(raw:String,used:MutableSet<String>,position:Int):String {
        val base=raw.map { if(it=='/' || it=='\\' || it.isISOControl()) '_' else it }.joinToString("").trim().ifEmpty { "file" }.take(180)
        var name=base; var prefix=position+1
        while(name in used || name in setOf(".","..")) { val start="${prefix++.toString().padStart(2,'0')}-"; name=start+base.take(180-start.length) }
        used+=name; return name
    }
    private fun copySources(localId:String,directory:File,sources:List<Uri>,existing:List<CloudTransferEntity> = emptyList(),supplement:Boolean=false):List<CloudTransferEntity> {
        require(sources.isNotEmpty() && sources.size+existing.size<=200) { "请选择文件，每任务最多200份 / ファイルを選択してください。1タスク200個までです" }
        val used=existing.map { it.name }.toMutableSet()
        val outputs=mutableListOf<CloudTransferEntity>()
        try {
            sources.forEach { uri ->
                var name=uri.lastPathSegment.orEmpty()
                context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst())name=it.getString(0).orEmpty() }
                val position=existing.size+outputs.size
                name=uniqueName(name,used,position)
                val mime=context.contentResolver.getType(uri) ?: "application/octet-stream"
                val file=File(directory,"source-$position")
                var size=0L; val digest=MessageDigest.getInstance("SHA-256")
                try {
                    val input=context.contentResolver.openInputStream(uri) ?: error("无法读取所选文件 / 選択したファイルを読み込めません")
                    input.use { source -> file.outputStream().use { output ->
                        val buffer=ByteArray(65536)
                        while(true) { val n=source.read(buffer); if(n<0)break
                            size+=n; require(size<CLOUD_FILE_LIMIT) { "每份文件必须小于100 MB / 各ファイルは100 MB未満にしてください" }
                            digest.update(buffer,0,n); output.write(buffer,0,n)
                        }
                    } }
                    require(size>0) { "不能上传空文件 / 空のファイルはアップロードできません" }
                    outputs+=CloudTransferEntity(localId,position,name,file.path,size,digest.digest().joinToString("") { "%02x".format(it) },mime,supplement=supplement)
                } catch(e:Exception) { file.delete(); throw e }
            }
            return outputs
        } catch(e:Exception) { outputs.forEach { File(it.path).delete() }; throw e }
    }
    suspend fun prepareStatementTask(bookId:String,accountId:String,sources:List<Uri>,title:String,note:String):String {
        val account=ledger.dao.account(bookId,accountId) ?: error("账户不存在 / 口座がありません")
        val instructions="目标账户：${account.id}，名称：${account.name}，币种：${account.currency}。仅返回该账户的一份 yohaku.statement v1。入账日或发生日不明确时必须记入 issues，不得猜测。\n$note"
        return prepare(bookId,"statement",title,instructions,accountId=accountId,taskContext=buildJsonObject {
            put("book_id",bookId); put("account_id",accountId); put("account_currency",account.currency)
        }) { localId,directory ->
            require(sources.size<200) { "须为账户标识保留一个文件位置 / 口座識別ファイルの枠を1つ残してください" }
            val copied=copySources(localId,directory,sources)
            val identifiers=File(directory,"identifiers.json")
            identifiers.writeText(FileProtocols.json.encodeToString(ledger.importIdentifiers(bookId)),Charsets.UTF_8)
            require(identifiers.length()<2_000_000) { "账户标识文件过大 / 口座識別ファイルが大きすぎます" }
            val name=uniqueName("yohaku-import-identifiers-$bookId.json",copied.map { it.name }.toMutableSet(),copied.size)
            copied+CloudTransferEntity(localId,copied.size,name,identifiers.path,identifiers.length(),sha256(identifiers),"application/json")
        }
    }
    suspend fun prepareGeneralTask(bookId:String,sources:List<Uri>,title:String,note:String):String =
        prepare(bookId,"general",title,"请分析所附资料，以UTF-8文本返回结果。\n$note",taskContext=buildJsonObject { put("book_id",bookId) }) {
            localId,directory -> copySources(localId,directory,sources)
        }
    suspend fun prepareReceiptTask(bookId:String,receiptIds:Set<String>,title:String,note:String):String {
        val bundleId=id()
        val instructions="返回一份 yohaku.receipt-items v1，原样复制 bundle_id、book_id、record_id、receipt_id、record_revision、receipt_revision 和图片ID，不得猜测。\n$note"
        val localId=prepare(bookId,"receipt",title,instructions,bundleId=bundleId,taskContext=buildJsonObject {
            put("book_id",bookId); put("bundle_id",bundleId)
        }) { operation,directory ->
            val parts=writeReceiptArchives(bundleId,bookId,ledger.receiptArchiveSources(bookId,receiptIds),directory,95_000_000)
            require(parts.all { it.length() in 1 until CLOUD_FILE_LIMIT }) {
                "单张小票的原图超过上传限制，请减少该小票的图片 / 1枚のレシートの元画像がアップロード上限を超えています。画像を減らしてください"
            }
            parts.mapIndexed { index,part ->
                val name="yohaku-receipts-$bundleId-${index+1}-of-${parts.size}.zip"
                val file=File(directory,name); check(part.renameTo(file)) { "无法保存小票包 / レシートZIPを保存できません" }
                val manifest=ZipFile(file).use { zip -> zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader(Charsets.UTF_8).use { it.readText() } }
                CloudTransferEntity(operation,index,name,file.path,file.length(),sha256(file),"application/zip",manifest)
            }
        }
        return localId
    }
    suspend fun addSupplement(localId:String,sources:List<Uri>) = taskOperation(localId) {
        refreshUnlocked(localId)
        val task=row(localId)
        check(task.remoteStatus=="needs_info") { "仅待补资料任务可补充文件 / 追加資料待ちのタスクのみ追加できます" }
        val additions=copySources(localId,File(root,localId),sources,dao.transfers(localId),true)
        try { db.withTransaction {
            dao.insertTransfers(additions); dao.updateTask(task.copy(stage="UPLOADING",lastError=null,updatedAt=now()))
        } } catch(e:Exception) { additions.forEach { File(it.path).delete() }; throw e }
        enqueue(localId)
    }
    fun enqueue(localId:String) { CloudTransferWorker.enqueue(context,localId) }
    private suspend fun refreshUnlocked(localId:String):CloudTask {
        val task=row(localId); val taskId=task.taskId ?: error("任务尚未创建 / タスクは未作成です")
        val remote=authenticated(api(task)) { it.task(taskId) }; remember(localId,remote); return remote
    }
    suspend fun refresh(localId:String) { taskOperation(localId) { refreshUnlocked(localId) } }
    suspend fun refreshActive(bookId:String) {
        dao.active(bookId).forEach { try { refresh(it.localId) } catch(e:CancellationException) { throw e }
            catch(_:Exception) { /* refresh has persisted the per-task error. */ } }
    }
    private suspend fun registerSubmitted(localId:String,remote:CloudTask) = db.withTransaction {
        val task=row(localId); val transfers=dao.transfers(localId)
        check(transfers.all { transfer -> remote.files.any { it.role=="input" && it.name==transfer.name && it.sha256==transfer.sha256 } }) {
            "云端缺少预定文件，不能确认完整提交 / 予定ファイルが不足しているため送信完了を確認できません"
        }
        transfers.mapNotNull { it.manifestJson }.forEach { ledger.recordSavedReceiptExport(FileProtocols.receiptBundle(it)) }
        dao.updateTask(task.copy(stage="SUBMITTED",lastError=null,updatedAt=now()))
    }
    internal suspend fun advance(localId:String) = taskOperation(localId,true) {
        var task=row(localId)
        task=task.copy(attempts=task.attempts+1,updatedAt=now()); dao.updateTask(task)
        val api=api(task)
        if(task.stage=="BLOCKED") { task=task.copy(stage=if(task.taskId==null)"PREPARED" else "UPLOADING",lastError=null,updatedAt=now()); dao.updateTask(task) }
        if(task.taskId==null) {
            val created=authenticated(api) { it.createTask(task.idempotencyKey,task.requestJson) }
            task=task.copy(taskId=created.id,stage="UPLOADING",updatedAt=now())
            dao.updateTask(task)
        }
        val taskId=checkNotNull(task.taskId)
        var remote=authenticated(api) { it.task(taskId) }; remember(localId,remote)
        val needsSubmit=task.stage!="SUBMITTED"
        for(transfer in dao.transfers(localId)) {
            if(transfer.fileId!=null)continue
            val match=remote.files.find { it.role=="input" && it.name==transfer.name && it.sha256==transfer.sha256 }
            val fileId=if(match!=null)match.id else {
                check(remote.status in setOf("uploading","needs_info")) { "任务已离开上传状态，资料尚未完整上传 / アップロード状態が終了し、資料が不足しています" }
                val file=File(transfer.path)
                check(file.isFile && file.length()==transfer.size && sha256(file)==transfer.sha256) { "内部文件已变化，请重新准备任务 / 保存ファイルが変わりました。タスクを作り直してください" }
                dao.updateTransfer(transfer.copy(attempts=transfer.attempts+1))
                try { authenticated(api) { it.uploadFile(taskId,transfer.name,file,transfer.size,transfer.sha256,transfer.mime) } }
                catch(e:IOException) {
                    if(e is CloudHttpException && e.status !in setOf(400,409,404) && !cloudRetryable(e))throw e
                    remote=authenticated(api) { it.task(taskId) }; remember(localId,remote)
                    remote.files.find { it.role=="input" && it.name==transfer.name && it.sha256==transfer.sha256 }?.id ?: throw e
                }
            }
            val current=dao.transfers(localId).first { it.position==transfer.position }
            dao.updateTransfer(current.copy(fileId=fileId,uploadedAt=now()))
        }
        remote=authenticated(api) { it.task(taskId) }; remember(localId,remote)
        if(remote.status in setOf("uploading","needs_info") && needsSubmit) {
            remote=try { authenticated(api) { it.submit(taskId) } }
                catch(e:CloudHttpException) {
                    if(e.status !in setOf(404,409))throw e
                    val detail=authenticated(api) { it.task(taskId) }; remember(localId,detail)
                    if(e.status!=409 || detail.status !in confirmedStatuses)throw e
                    detail
                }
            remember(localId,remote)
        }
        if(remote.status in confirmedStatuses)registerSubmitted(localId,remote)
    }
    suspend fun claim(localId:String):CloudClaim = taskOperation(localId) {
        val remote=refreshUnlocked(localId); val task=row(localId)
        val result=remote.claimableResult() ?: error("暂无可领取结果 / 受け取れる結果がありません")
        val metadata=remote.files.firstOrNull { it.id==result.fileId && it.role=="result" } ?: error("结果文件资料缺失 / 結果ファイル情報がありません")
        check(dao.imported(importKey(task.origin,remote.id,result.id,metadata.sha256))==null) { "结果已导入 / 結果は取込済みです" }
        if(task.type=="receipt")registerSubmitted(localId,remote)
        val directory=File(root,localId); check(directory.isDirectory || directory.mkdirs())
        val dest=File.createTempFile("result-", ".txt",directory)
        try {
            try { authenticated(api(task)) { it.downloadFile(result.fileId,metadata.size,metadata.sha256,dest) } }
            catch(e:CloudIntegrityException) {
                try { refreshUnlocked(localId) } catch(refresh:Exception) { if(refresh is CancellationException)throw refresh; e.addSuppressed(refresh) }
                throw e
            } catch(e:CloudHttpException) {
                if(e.status in setOf(404,409))refreshUnlocked(localId)
                throw e
            }
            val text=try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(dest.readBytes())).toString() }
                catch(_:java.nio.charset.CharacterCodingException) { error("结果不是有效UTF-8文本 / 結果は有効なUTF-8テキストではありません") }
            CloudClaim(localId,task.bookId,task.type,remote.id,result.id,result.version,metadata.sha256,dest,text,result.summary,result.validation.warnings)
        } catch(e:Exception) { dest.delete(); throw e }
    }
    private suspend fun validateClaim(claim:CloudClaim,type:String):CloudTaskEntity {
        val task=row(claim.localId)
        require(task.bookId==claim.bookId && task.type==type && claim.type==type && task.taskId==claim.taskId) { "领取目标不一致 / 受取先が一致しません" }
        val remote=task.remoteJson?.let { cloudJson.decodeFromString<CloudTask>(it) }
        val result=remote?.results?.find { it.id==claim.resultId && it.version==claim.resultVersion }
        require(result!=null && remote.files.any { it.id==result.fileId && it.role=="result" && it.sha256==claim.sha256 }) { "领取结果关联不一致 / 受け取った結果の関連が一致しません" }
        val sha=MessageDigest.getInstance("SHA-256").digest(claim.text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        require(sha==claim.sha256) { "领取内容已变化 / 受け取った内容が変わりました" }
        check(dao.imported(importKey(task.origin,claim.taskId,claim.resultId,claim.sha256))==null) { "结果已导入 / 結果は取込済みです" }
        return task
    }
    private suspend fun recordImport(task:CloudTaskEntity,claim:CloudClaim,localRef:String) {
        dao.insertImport(CloudImportEntity(importKey(task.origin,claim.taskId,claim.resultId,claim.sha256),task.localId,
            claim.taskId,claim.resultId,claim.resultVersion,claim.sha256,localRef,now()))
        dao.insertAck(CloudAckEntity(task.localId,claim.resultId,"imported",claim.taskId,now()))
    }
    suspend fun saveClaimedStatement(claim:CloudClaim,preview:StatementImportPreview,acceptOverlap:Boolean):String = locked(claim.localId) {
        val saved=db.withTransaction {
            val task=validateClaim(claim,"statement")
            require(preview.originalJson==claim.text && preview.file.account_id==task.accountId) { "流水账户或预览内容不一致 / 明細の口座またはプレビュー内容が一致しません" }
            val batchId=ledger.saveStatement(task.bookId,preview,acceptOverlap)
            recordImport(task,claim,batchId); batchId
        }
        CloudAckWorker.enqueue(context); saved
    }
    suspend fun saveClaimedReceiptItems(claim:CloudClaim,preview:ReceiptBackfillPreview,acceptStale:Boolean,acceptReplacement:Boolean) = locked(claim.localId) {
        db.withTransaction {
            val task=validateClaim(claim,"receipt")
            require(preview.originalJson==claim.text && preview.file.bundle_id==task.bundleId) { "小票批次或预览内容不一致 / レシートバッチまたはプレビュー内容が一致しません" }
            ledger.saveReceiptItems(task.bookId,preview,acceptStale,acceptReplacement)
            recordImport(task,claim,checkNotNull(task.bundleId))
        }
        CloudAckWorker.enqueue(context)
    }
    suspend fun recordGeneralSaved(claim:CloudClaim,savedName:String) = locked(claim.localId) {
        require(savedName.isNotBlank()) { "保存名称不能为空 / 保存名を入力してください" }
        db.withTransaction { val task=validateClaim(claim,"general"); recordImport(task,claim,savedName) }
        CloudAckWorker.enqueue(context)
    }
    /** Caller reports an actual failed save attempt; cancelling a preview must not call this. */
    suspend fun reportFailed(claim:CloudClaim) = locked(claim.localId) {
        db.withTransaction {
            validateClaim(claim,claim.type)
            dao.insertAck(CloudAckEntity(claim.localId,claim.resultId,"failed",claim.taskId,now()))
        }
        CloudAckWorker.enqueue(context)
    }
    suspend fun discard(localId:String) = locked(localId) {
        val task=row(localId)
        check(task.taskId==null) { "已创建的云任务不能本地丢弃 / 作成済みのクラウドタスクは破棄できません" }
        val directory=File(root,localId)
        check(!directory.exists() || directory.deleteRecursively()) { "无法清理任务文件 / タスクファイルを削除できません" }
        dao.deleteTask(localId)
    }
    internal suspend fun resumePending() {
        store.credentials()
        if(connection.value?.let { it.bound && !it.needsReconnect } != true)return
        dao.unfinished().forEach { enqueue(it.localId) }
        if(dao.pendingAcks().isNotEmpty())CloudAckWorker.enqueue(context)
    }
    internal suspend fun sendPendingAcks():Boolean {
        var retry=false
        for(ack in dao.pendingAcks()) {
            try {
                authenticated(api(row(ack.localId))) { it.ack(ack.taskId,ack.resultId,ack.status) }
                dao.updateAck(ack.copy(sentAt=now(),lastError=null))
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) {
                if(e is CloudHttpException && e.status==409) {
                    try { refresh(ack.localId) } catch(refresh:Exception) { if(refresh is CancellationException)throw refresh }
                    dao.updateAck(ack.copy(sentAt=now(),lastError="回执版本冲突，本地保存仍有效 / 受領通知のバージョンが競合しています。ローカル保存は有効です"))
                } else {
                    if(e is CloudBlockedException || e is CloudHttpException && e.status==401)
                        locked(ack.localId) { recordFailure(ack.localId,e) }
                    if(e is CloudHttpException && e.status==404) {
                        try { refresh(ack.localId) } catch(refresh:Exception) { if(refresh is CancellationException)throw refresh }
                    }
                    dao.updateAck(ack.copy(lastError=e.message ?: "回执失败 / 受領通知に失敗しました"))
                    retry=retry || cloudRetryable(e)
                    if(e is CloudHttpException && e.status==401)break
                }
            }
        }
        return retry
    }
}
