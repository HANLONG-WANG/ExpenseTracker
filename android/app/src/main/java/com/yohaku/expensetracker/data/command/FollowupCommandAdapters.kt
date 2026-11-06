package com.yohaku.expensetracker.data.command

import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.domain.reminders.ReminderPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Durable, local-only payload. No paths or destination URI are accepted from a cloud plan. */
@Serializable data class CommandFollowup(val id:String,val stepId:String,val command:String,val bookId:String,val mode:String,
    val kind:String,val input:JsonObject,val before:JsonElement=JsonNull,val after:JsonElement=JsonNull)
@Serializable internal data class SwitchBookInput(val book:JsonElement)
@Serializable internal data class EnabledInput(val enabled:Boolean)
@Serializable internal data class PhotoInput(val original:Boolean)
@Serializable internal data class AttachmentInput(val entry:JsonElement,val sourceName:String)
@Serializable internal data class DraftInput(val set:JsonObject=buildJsonObject{},val media:String="NONE")
@Serializable internal data class StatementExportInput(val batch:String)
@Serializable internal data class RestorePreferenceInput(val key:String,val before:JsonElement,val after:JsonElement)

internal val preferenceUndo=UndoAvailability(true,"后续步骤完成后可恢复原偏好；若当前值已改变则阻止整批撤销。系统权限不随偏好撤销。","後続処理の完了後に元の設定へ戻せます。現在の値が変わっている場合は全体の取消を停止します。システム権限は取り消しません。")
internal val attachmentUndo=UndoAvailability(true,"后续步骤完成后移除本方案新增且未变化的附件关联；保留源文件和文件字节，不执行永久删除。","後続処理の完了後、このプランで追加した未変更の添付関係を取り消します。元ファイルとデータは保持し、完全削除は行いません。")
internal val interactionUndo=UndoAvailability(false,"需要你在本机操作；草稿补完、系统授权及已导出的文件不能由整批撤销恢复。可在对应本机页面处理。","端末での操作が必要です。下書きの補完・システム許可・書き出したファイルは一括取消では戻せません。対応する端末画面で操作してください。")

internal fun CommandRun.queue(step:CommandStep,kind:String,input:JsonObject,before:JsonElement=JsonNull,after:JsonElement=JsonNull,mode:String="post-commit",suffix:String=""):CommandFollowup {
    val value=CommandFollowup("$planId:${step.id}:$suffix",step.id,step.command,bookId,mode,kind,input,before,after)
    followups+=value
    return value
}
private fun followupCapability(name:String,schema:JsonObject,defaults:String,prerequisites:String,description:String,example:String,mode:String="post-commit",undo:UndoAvailability=preferenceUndo)=
    ledgerCapability(name,schema,defaults=defaults,prerequisites=prerequisites,description=description,example=example)
        .copy(mode=mode,undo="${undo.zh} / ${undo.ja}",comparisonFields=when(name) {
            "attachment.attachSource"->listOf("this task's uploaded source name/size/SHA-256 and full target entry/relations", "post-commit attachment identity and undo ownership")
            "entry.prepareDraft"->listOf("fixed draft fields, referenced objects, absence of an existing draft and requested media interaction")
            "backup.export"->listOf("interactive full-backup intent; contents are captured when the user starts export, not at preview")
            "statement.exportOriginal","command.exportResult"->listOf("exact original content digest and interactive export intent")
            else->listOf("exact preference before/after and referenced book identity", "permission requirement and execution mode")
        },unavailable=when(name) {
            "attachment.attachSource"->"Before commit, a missing/unuploaded/non-source file or invalid target rejects the batch. After commit, changed/missing bytes or target conflicts leave this attachment effect failed and retryable; already committed ledger changes are not rolled back."
            "entry.prepareDraft"->"An existing draft or invalid supplied references reject the batch. Once created, wait for the user to open/complete it; never submit it automatically."
            "backup.export","statement.exportOriginal","command.exportResult"->"Wait for the user to choose a destination. File/permission failures remain visible and retryable; do not claim export completed or roll back already committed ledger changes."
            "preferences.location","preferences.reminders"->"Invalid settings reject the batch before commit. A changed preference leaves its post-commit effect retryable; missing permission waits for phone interaction, never a cloud grant."
            else->"Invalid settings/targets reject the batch before commit. A changed preference at application time leaves its post-commit effect retryable; already committed ledger changes are not rolled back."
        },confirmationTypes=listOf(ConfirmationKind.INFORMATION))

internal class PreferenceAdapter(private val key:String):CommandAdapter {
    override val capability=when(key) {
        "activeBook"->followupCapability("book.select",patchSchema(descriptorSchema(SwitchBookInput.serializer().descriptor),mapOf("book" to referenceSchema("book"))),"book required; may reference a preceding book.create output. No other writes are redirected to that book.","Target book exists, not trashed; archived books may be opened just like local book management; current selection compared.","Select current book only after the entire transaction commits; does not submit/discard any entry draft.","""{"book":"book-id"}""")
        "reminders"->followupCapability("preferences.reminders",descriptorSchema(ReminderPreferences.serializer().descriptor),"Full replacement: enabled=false; all five important kinds=true; monthlyReview=true independently of enabled; repaymentDays=[7,1], deadlineDays=[3,1], recurringDays=[1], time=09:00. Omitted fields use these defaults.","Valid local time and nonempty day lists with 0..365; existing book.","Save all reminder preferences; enabling also queues a phone notification-permission step. No cloud grant of system permissions. If permission is missing, this step includes irreversible interactive permission handling and blocks whole-batch undo.","""{"enabled":true,"time":"08:30"}""")
        "photoOriginal"->followupCapability("preferences.photoStorage",descriptorSchema(PhotoInput.serializer().descriptor),"original required; true=preserve source, false=space-saving. Applies to all books; existing photos unchanged.","Preference before value must still match at application time.","Set the photo storage default shared by all books.","""{"original":true}""")
        "haptics"->followupCapability("preferences.haptics",descriptorSchema(EnabledInput.serializer().descriptor),"enabled required; no input default; app preference defaults to true and is shared by all books.","Preference before value must still match at application time.","Set the haptic feedback preference shared by all books; system touch vibration settings still apply.","""{"enabled":true}""")
        else->followupCapability(if(key=="location")"preferences.location"else"preferences.automaticRates",descriptorSchema(EnabledInput.serializer().descriptor),if(key=="location")"enabled required; no default; applies to this book only."else"enabled required; no default; global setting for all books.",if(key=="location")"Existing book; foreground location only, no background tracking; permission remains a phone action."else"Changes only the public reference-rate retrieval preference; does not require location permission or supply exchange-rate facts.",if(key=="location")"Save location matching preference; enabling also queues a phone permission step, no permission is granted by the plan. If permission is missing, interactive permission handling blocks whole-batch undo."else"Set automatic public reference-rate retrieval. Does not invent a rate or trigger a transaction-local network request.","""{"enabled":true}""")
    }
        .let{if(key in setOf("location","reminders"))it.copy(undo=it.undo+" If enabling needs system permission, whole-batch undo is unavailable; preview shows this before confirmation.")else it}
    override fun validate(input:JsonObject) {when(key){"activeBook"->commandJson.decodeFromJsonElement<SwitchBookInput>(input);"reminders"->commandJson.decodeFromJsonElement<ReminderPreferences>(input).validate();"photoOriginal"->commandJson.decodeFromJsonElement<PhotoInput>(input);else->commandJson.decodeFromJsonElement<EnabledInput>(input)}}
    override suspend fun execute(step:CommandStep,run:CommandRun):StepEffect {
        val before=run.preferenceValues.getValue(key)
        val after=when(key) {
            "activeBook"->{val id=run.resolve(commandJson.decodeFromJsonElement<SwitchBookInput>(step.input).book);val book=requireNotNull(run.ledger.dao.book(id));require(book.deletedAt==null);JsonPrimitive(id)}
            "reminders"->encoded(commandJson.decodeFromJsonElement<ReminderPreferences>(step.input).let{it.copy(repaymentDays=it.repaymentDays.distinct().sorted(),deadlineDays=it.deadlineDays.distinct().sorted(),recurringDays=it.recurringDays.distinct().sorted())})
            "photoOriginal"->JsonPrimitive(commandJson.decodeFromJsonElement<PhotoInput>(step.input).original)
            else->JsonPrimitive(commandJson.decodeFromJsonElement<EnabledInput>(step.input).enabled)
        }
        run.queue(step,"PREFERENCE",buildJsonObject{put("key",key)},before,after)
        run.preferenceValues[key]=after
        val objects=mutableListOf(ObjectEffect("preference",key,buildJsonObject{put(key,run.semantic(before))},buildJsonObject{put(key,run.semantic(after))}))
        if(key=="activeBook") {
            for(id in listOf(before,after).filterIsInstance<JsonPrimitive>().mapNotNull{it.contentOrNull}.distinct())run.ledger.dao.book(id)?.let{book->
                val facts=commandLedgerRow("books",encoded(book).jsonObject);objects+=ObjectEffect("book",run.semantic(JsonPrimitive(id)).jsonPrimitive.content,run.semantic(facts),run.semantic(facts))
            }
        }
        if(key=="location"&&after==JsonPrimitive(true)||key=="reminders"&&(after.jsonObject["enabled"]==JsonPrimitive(true)||after.jsonObject["monthlyReview"]==JsonPrimitive(true))) {
            val permission=if(key=="location")"LOCATION"else"NOTIFICATIONS"
            if(permission !in run.grantedPermissions) {
                run.queue(step,"PERMISSION",buildJsonObject{put("permission",permission)},mode="interactive",suffix="permission")
                objects+=ObjectEffect("followup",key,JsonNull,buildJsonObject{put("permission",permission);put("mode","interactive")})
            }
        }
        return StepEffect(step.id,step.command,listOf(key),objects,mode="post-commit")
    }
    override suspend fun inverse(step:CommandStep,run:CommandRun,before:FinancialReviewState,after:FinancialReviewState):UndoPreparation {
        if(run.followups.any{it.stepId==step.id&&it.kind=="PERMISSION"})return UndoPreparation(UndoAvailability(false,
            "此步骤包含系统权限操作，不能整批撤销；可在本机设置中修改偏好或系统权限。",
            "このステップにはシステム権限の操作が含まれ、一括取消できません。設定・権限は端末で変更できます。"))
        val change=run.followups.last{it.stepId==step.id&&it.kind=="PREFERENCE"}
        return UndoPreparation(preferenceUndo,listOf(CommandStep("undo_${step.id}","local.restorePreference",1,encoded(RestorePreferenceInput(key,change.after,change.before)).jsonObject)),listOf(UndoGuard("preference",key,change.after)))
    }
}
internal class RestorePreferenceAdapter:CommandAdapter {
    override val capability=followupCapability("local.restorePreference",descriptorSchema(RestorePreferenceInput.serializer().descriptor),"Local stored inverse only.","Complete final preference guard must match before any inverse.","Restore the recorded preference after commit.","{}",undo=localUndoUnavailable)
    override fun validate(input:JsonObject){commandJson.decodeFromJsonElement<RestorePreferenceInput>(input)}
    override suspend fun execute(step:CommandStep,run:CommandRun):StepEffect {
        check(step in requireNotNull(run.undoRecord).steps)
        val saved=commandJson.decodeFromJsonElement<RestorePreferenceInput>(step.input)
        val v=saved.copy(before=completeCommandPreference(saved.key,saved.before),after=completeCommandPreference(saved.key,saved.after))
        check(run.preferenceValues.getValue(v.key)==v.before)
        run.queue(step,"PREFERENCE",buildJsonObject{put("key",v.key)},v.before,v.after)
        run.preferenceValues[v.key]=v.after
        return StepEffect(step.id,step.command,listOf(v.key),listOf(ObjectEffect("preference",v.key,buildJsonObject{put(v.key,v.before)},buildJsonObject{put(v.key,v.after)})),mode="post-commit")
    }
    override suspend fun inverse(step:CommandStep,run:CommandRun,before:FinancialReviewState,after:FinancialReviewState)=UndoPreparation(localUndoUnavailable)
}
internal class AttachSourceAdapter:CommandAdapter {
    override val capability=followupCapability("attachment.attachSource",patchSchema(descriptorSchema(AttachmentInput.serializer().descriptor),mapOf("entry" to referenceSchema("entry"))),"entry/sourceName required. Exact uploaded source filename from THIS task. Prefer task context.source_files (initial source names); supplementary source names come from the current task input list. Otherwise exclude the six exact generated filenames listed in the protocol, not every yohaku-* name. Uploaded names already resolve collisions. No helper task-request/README artifact, arbitrary path/URI, generated attachment, other-task file or remote download.","Source retained on this phone, metadata and SHA-256 validated before copying; live target entry in this book, full entry state protected until attach.","After commit, attach original source bytes to the record, using one durable file identity and one attachment link. Does not recompress uploaded photos.","""{"entry":"entry-id","sourceName":"receipt.jpg"}""",undo=attachmentUndo)
    override fun validate(input:JsonObject){commandJson.decodeFromJsonElement<AttachmentInput>(input)}
    override suspend fun execute(step:CommandStep,run:CommandRun):StepEffect {
        val v=commandJson.decodeFromJsonElement<AttachmentInput>(step.input);val entry=run.resolve(v.entry)
        val event=run.state().ledger.visibleEvents.single{it.id==entry}
        val task=requireNotNull(run.ledger.db.cloudDao().task(run.localId));require(task.bookId==run.bookId&&task.purpose in setOf("command","monthly-review"))
        val source=run.ledger.db.cloudDao().transfers(run.localId).single{it.name==v.sourceName}
        require(source.fileId!=null&&source.uploadedAt!=null){"源文件尚未上传到本任务 / 元ファイルはまだこのタスクへ送信されていません"}
        require(java.io.File(source.path).name=="source-${source.position}"){"只能引用本任务上传的源文件 / このタスクの元ファイルのみ参照できます"}
        val fileId=java.util.UUID.nameUUIDFromBytes("${run.planId}:${step.id}:attachment".toByteArray()).toString()
        run.generated(fileId,step.id,"attachment")
        run.generated("$entry:attachment:$fileId",step.id,"attachmentLink")
        val facts=buildJsonObject{put("entryId",entry);put("name",source.name);put("size",source.size);put("mime",source.mime);put("checksum",source.sha256)}
        run.queue(step,"ATTACHMENT",buildJsonObject{put("localId",run.localId);put("position",source.position);put("fileId",fileId);put("entryId",entry);put("name",source.name);put("size",source.size);put("mime",source.mime);put("checksum",source.sha256)},encoded(event),facts)
        return StepEffect(step.id,step.command,listOf(run.semantic(JsonPrimitive(entry)).jsonPrimitive.content),listOf(ObjectEffect("attachment",run.semantic(JsonPrimitive(fileId)).jsonPrimitive.content,JsonNull,run.semantic(facts)),ObjectEffect("entry",run.semantic(JsonPrimitive(entry)).jsonPrimitive.content,run.semantic(commandEventFacts(encoded(event))),run.semantic(commandEventFacts(encoded(event)))),ObjectEffect("entryDetails",run.semantic(JsonPrimitive(entry)).jsonPrimitive.content,run.semantic(encoded(run.state().ledger.entryDetails(entry))),run.semantic(encoded(run.state().ledger.entryDetails(entry))))),mode="post-commit")
    }
    override suspend fun inverse(step:CommandStep,run:CommandRun,before:FinancialReviewState,after:FinancialReviewState)=UndoPreparation(attachmentUndo)
}
internal class InteractiveAdapter(private val action:String):CommandAdapter {
    override val capability=followupCapability(when(action){"DRAFT"->"entry.prepareDraft";"STATEMENT_EXPORT"->"statement.exportOriginal";"RESULT_EXPORT"->"command.exportResult";else->"backup.export"},
        if(action=="DRAFT")patchSchema(descriptorSchema(DraftInput.serializer().descriptor),mapOf("set" to patchSchema(entryValueSchema,listOf("rateDate","toDate","feeDate").associateWith{nullableSchema(buildJsonObject{put("type","string")})}),"media" to buildJsonObject{put("type","string");put("enum",encoded(listOf("NONE","ATTACHMENT","RECEIPT_IMAGES","RECEIPT_CAMERA")))}))else if(action=="STATEMENT_EXPORT")descriptorSchema(StatementExportInput.serializer().descriptor)else buildJsonObject{put("type","object");put("properties",buildJsonObject{});put("additionalProperties",false)},
        if(action=="DRAFT")"set={} uses current editor defaults; omitted amount/category remain blank. Does not replace an existing draft. media=NONE|ATTACHMENT|RECEIPT_IMAGES|RECEIPT_CAMERA describes the phone editor action."else if(action=="STATEMENT_EXPORT")"batch required, existing statement in this book; exact original JSON copied, no ledger mutation."else if(action=="RESULT_EXPORT")"No inputs; copies this plan result only, never an arbitrary file or another task result."else"No inputs; destination selected by the user on the phone. Exports all books/settings/attachments with the existing backup service.",
        if(action=="DRAFT")"No existing ordinary draft; supported ordinary entry kind and valid supplied references. User must finish and explicitly save on phone."else"User must select a destination. Retain one output URI and prepared bytes for crash recovery; never auto-launch system UI.",
        if(action=="DRAFT")"Create one durable incomplete draft after commit and guide user to its normal editor; never submits a ledger entry."else if(action=="EXPORT")"Prepare a full backup of all books, settings and attachments only when the user starts export; this is the data at that time, not a frozen preview snapshot. Written files cannot be undone."else"Copy the exact previewed original document to a user-selected file; compare its content digest. No ledger mutation; written copies cannot be undone.",if(action=="DRAFT")"""{"set":{"merchant":"超市","note":"请补全金额"}}"""else if(action=="STATEMENT_EXPORT")"""{"batch":"batch-id"}"""else"{}",mode="interactive",undo=interactionUndo)
    override fun validate(input:JsonObject){if(action=="DRAFT"){val v=commandJson.decodeFromJsonElement<DraftInput>(input);if(v.set.isNotEmpty())validateEntryValues(v.set);require(v.media in setOf("NONE","ATTACHMENT","RECEIPT_IMAGES","RECEIPT_CAMERA"))}else if(action=="STATEMENT_EXPORT")commandJson.decodeFromJsonElement<StatementExportInput>(input)else require(input.isEmpty())}
    override suspend fun execute(step:CommandStep,run:CommandRun):StepEffect {
        val input=if(action=="DRAFT") {
            require(run.ledger.dao.storedDraft(run.bookId)==null&&run.followups.none{it.kind=="DRAFT"}){"请先处理现有记账草稿 / 既存の記録下書きを先に処理してください"}
            val v=commandJson.decodeFromJsonElement<DraftInput>(step.input)
            val draft=entryDraft(v.set,run,run.state().ledger,allowIncomplete=true).copy(mediaSession="${run.planId}:${step.id}:draft")
            run.generated(draft.mediaSession,step.id,"draft")
            val s=run.state().ledger
            java.time.LocalDate.parse(draft.date);java.time.LocalDate.parse(draft.settlementDate)
            draft.accountId?.let{id->require(s.accounts.any{it.id==id&&it.active})}
            draft.categoryId?.let{id->require(s.categories.any{it.id==id&&it.kind==draft.kind.name}&&s.categoryEnabled(id))}
            draft.toAccountId?.let{id->require(s.accounts.any{it.id==id&&it.active&&!AccountType.valueOf(it.type).debt})}
            draft.merchantId?.let{id->require(s.merchants.any{it.id==id&&it.active})}
            encoded(draft).jsonObject
        }else if(action=="STATEMENT_EXPORT") {
            val batch=run.ledger.db.fileWorkflowDao().statement(run.bookId,commandJson.decodeFromJsonElement<StatementExportInput>(step.input).batch)?:error("流水批次不存在 / 明細バッチがありません")
            buildJsonObject{put("content",batch.originalJson);put("name","yohaku-statement.json");put("mime","application/json")}
        }else if(action=="RESULT_EXPORT") {
            val plan=requireNotNull(run.ledger.db.commandDao().plan(run.planId))
            buildJsonObject{put("content",readCommandJson(plan.planJson));put("name","yohaku-command-result.json");put("mime","application/json")}
        }else buildJsonObject{put("name","yohaku-backup.yohaku");put("mime","application/octet-stream")}
        val queued=if(action=="DRAFT")input else JsonObject(input+("exportType" to JsonPrimitive(action)))
        run.queue(step,if(action=="DRAFT")action else "EXPORT",queued,after=queued,mode="interactive")
        return StepEffect(step.id,step.command,emptyList(),listOf(ObjectEffect(if(action=="DRAFT")"entryDraft"else"followup",step.id,JsonNull,run.semantic(if(action=="DRAFT")JsonObject(input+("requestedMedia" to JsonPrimitive(commandJson.decodeFromJsonElement<DraftInput>(step.input).media)))else JsonObject(input.filterKeys{it!="content"}+("sourceDigest" to JsonPrimitive(input["content"]?.jsonPrimitive?.contentOrNull?.let(::sha256).orEmpty())))))),mode="interactive")
    }
    override suspend fun inverse(step:CommandStep,run:CommandRun,before:FinancialReviewState,after:FinancialReviewState)=UndoPreparation(interactionUndo)
}
internal fun followupCommandAdapters():List<CommandAdapter> = listOf(PreferenceAdapter("activeBook"),PreferenceAdapter("reminders"),PreferenceAdapter("automaticRates"),PreferenceAdapter("location"),PreferenceAdapter("photoOriginal"),PreferenceAdapter("haptics"),AttachSourceAdapter(),InteractiveAdapter("DRAFT"),InteractiveAdapter("EXPORT"),InteractiveAdapter("STATEMENT_EXPORT"),InteractiveAdapter("RESULT_EXPORT"))

/** Preference assignments have no dependent ledger output. Fold their reverse sequence to the
 * recorded final -> initial value; otherwise undo could select a newly-created book that the
 * transactional inverse has just removed. Original intermediate effects remain in its journal. */
internal fun preferenceInverseSteps(reverse:List<CommandStep>):List<CommandStep> {
    val groups=reverse.filter{it.command=="local.restorePreference"}.groupBy{commandJson.decodeFromJsonElement<RestorePreferenceInput>(it.input).key}
    val emitted=mutableSetOf<String>()
    return reverse.mapNotNull{step->
        if(step.command!="local.restorePreference")step else {
            val first=commandJson.decodeFromJsonElement<RestorePreferenceInput>(step.input)
            if(!emitted.add(first.key))null else {
                val last=commandJson.decodeFromJsonElement<RestorePreferenceInput>(groups.getValue(first.key).last().input)
                step.copy(input=encoded(first.copy(after=last.after)).jsonObject)
            }
        }
    }
}
