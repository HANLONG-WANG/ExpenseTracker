package com.yohaku.expensetracker.data.command

import androidx.room.withTransaction
import com.yohaku.expensetracker.data.cloud.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.currency.ReferenceRateSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.util.UUID

private class TrialRollback(val normalized:NormalizedPlan,val confirmation:ConfirmationModel):RuntimeException(null,null,false,false)
class CommandRepreviewRequired(val differencePath:String?=null):IllegalStateException("相关资料或日期已变化，请重新预览并勾选 / 関連情報または日付が変わりました。再確認してチェックしてください")

/** No network/file work inside the media lock/transaction. Cloud receipt recovery uses cloud_acks. */
class CommandRepository(val ledger:LedgerRepository,val registry:CommandRegistry=CommandRegistry(),
    private val referenceSnapshot:suspend (CommandPlan)->List<ReferenceRateSnapshot> = {emptyList()},private val enqueueAcks:()->Unit={},
    internal val preferences:AppPreferences?=null,sourceRoot:java.io.File?=null,
    private val applicationContext:android.content.Context?=null,afterEffectWrite:(suspend (CommandEffectEntity)->Unit)?=null) {
    private val db=ledger.db
    private val dao=db.commandDao()
    val followups=CommandFollowupCoordinator(this,sourceRoot,applicationContext,afterEffectWrite)
    fun effects(planId:String)=dao.effects(planId)
    fun history(bookId:String)=dao.history(bookId)
    fun observePlan(id:String)=dao.observePlan(id)
    suspend fun plan(id:String)=requireNotNull(dao.plan(id))
    fun confirmation(row:CommandPlanEntity)=row.confirmationJson?.let{readCommandDocument<ConfirmationModel>(it)}

    /** The caller verifies the downloaded cloud claim. Tests submit the same fixed claim here. */
    suspend fun receive(claim:CloudClaim,origin:String,expectedContextDigest:String):CommandPlanEntity {
        require(sha256(claim.text)==claim.sha256){"方案校验值不符 / プランのチェックサムが一致しません"}
        val key=importKey(origin,claim.taskId,claim.resultId,claim.sha256)
        dao.source(key)?.let{return it}
        val parsed=try{registry.parse(claim.text,claim.bookId,expectedContextDigest).also{plan->
            if(claim.purpose=="monthly-review") {
                val submission=requireNotNull(db.monthlyDao().submission(claim.localId)){"月评提交快照不存在 / 月の振り返りの送信内容がありません"}
                require(submission.planId==null||submission.planId==plan.plan_id){"本月评语已保存，请在历史查看原评语 / この月のコメントは保存済みです。履歴をご確認ください"}
                require(submission.bookId==claim.bookId&&plan.message.isNotBlank()){"评语不能为空 / コメントが必要です"}
            }
        }}catch(e:Exception){
            val failed=CommandPlanEntity("invalid:$key",claim.bookId,claim.localId,origin,claim.taskId,claim.resultId,claim.resultVersion,claim.sha256,key,
                storeCommandJson(claim.text),expectedContextDigest,status="FAILED",error=e.message,createdAt=ledger.clock.millis())
            db.withTransaction {if(dao.source(key)==null)dao.insert(failed);db.cloudDao().insertAck(CloudAckEntity(claim.localId,claim.resultId,"failed",claim.taskId,ledger.clock.millis()))}
            enqueueAcks();return failed
        }
        val row=db.withTransaction {
            val existing=dao.source(key)?:dao.plan(parsed.plan_id)
            if(existing!=null) {
                if(claim.purpose=="monthly-review")require(existing.localId==claim.localId){"此方案已属于另一个任务 / このプランは別のタスクに属しています"}
                require(existing.bookId==claim.bookId){"方案ID已被另一账本使用 / プランIDは別の帳簿で使用済みです"}
                if(existing.status in listOf("ANSWER","EXECUTED","UNDONE")||claim.purpose=="monthly-review")recordReceipt(existing.copy(localId=claim.localId,origin=origin,taskId=claim.taskId,resultId=claim.resultId,resultVersion=claim.resultVersion,sha256=claim.sha256,sourceKey=key))
                existing
            }else CommandPlanEntity(parsed.plan_id,claim.bookId,claim.localId,origin,claim.taskId,claim.resultId,claim.resultVersion,claim.sha256,key,
                storeCommandJson(claim.text),expectedContextDigest,status=if(parsed.steps.isEmpty())"ANSWER"else"PENDING",createdAt=ledger.clock.millis()).also{new->
                dao.insert(new)
                if(claim.purpose=="monthly-review") {
                    val submission=requireNotNull(db.monthlyDao().submission(claim.localId))
                    db.monthlyDao().update(submission.copy(planId=new.planId))
                }
                if(new.status=="ANSWER"||claim.purpose=="monthly-review")recordReceipt(new)
            }
        }
        enqueueAcks();return row
    }

    /** Local-only plan identity. Never accepts an inverse command through the cloud parser. */
    suspend fun prepareUndo(id:String):CommandPlanEntity=db.withTransaction {
        val original=plan(id)
        check(original.status=="EXECUTED"&&original.undoOf==null){"方案不可再次撤销 / このプランは再び取り消せません"}
        check(undoAvailability(original).available){undoAvailability(original).let{"${it.zh} / ${it.ja}"}}
        check(dao.planEffects(id).all{it.status=="DONE"}){"请先完成或重试后续步骤 / 後続処理を完了・再試行してください"}
        dao.undoFor(id)?.let{existing->
            check(existing.status!="EXECUTED")
            val pending=existing.copy(status="PENDING",confirmationJson=null,normalizedJson=null,checksJson="[]",error=null)
            dao.update(pending);return@withTransaction pending
        }
        val execution=readCommandExecution(requireNotNull(original.executionJson))
        val inverse=requireNotNull(execution.undo)
        val undoId=UUID.randomUUID().toString()
        val input=CommandPlan("yohaku.command-plan",1,undoId,original.bookId,registry.catalogDigest,original.contextDigest,
            steps=inverse.steps)
        CommandPlanEntity(undoId,original.bookId,"","local","","",0,"","local-undo:$id",
            storeCommandJson(commandJson.encodeToString(input)),original.contextDigest,createdAt=ledger.clock.millis(),undoOf=id).also{dao.insert(it)}
    }
    fun undoAvailability(row:CommandPlanEntity):UndoAvailability {
        if(row.undoOf!=null)return UndoAvailability(false,"撤销操作不能再次撤销。","取消操作を再び取り消すことはできません。")
        val saved=row.executionJson?.let{readCommandExecution(it).undo}
        if(saved==null||saved.version==1&&saved.steps.any{it.command=="local.account.deleteUnused"})
            return UndoAvailability(false,"旧执行记录没有完整的对象创建与引用快照，不能安全撤销。","以前の実行履歴には対象の作成・参照の完全な情報がなく、安全に取り消せません。")
        val model=confirmation(row)
        val unavailable=model?.steps?.withIndex()?.filter{!it.value.undo.available}.orEmpty()
        if(unavailable.isNotEmpty())return UndoAvailability(false,
            unavailable.joinToString("\n"){"${commandStepTitle(it.index+1,it.value.command).first}：${it.value.undo.zh}"},unavailable.joinToString("\n"){"${commandStepTitle(it.index+1,it.value.command).second}：${it.value.undo.ja}"})

        return UndoAvailability(true,"可整批撤销；任何受影响对象发生变化都会阻止整批撤销。","一括取消が可能です。対象の変更がある場合は全体の取消を停止します。")
    }
    private fun parseStored(row:CommandPlanEntity):CommandPlan {
        if(row.undoOf==null)return registry.parse(readCommandJson(row.planJson),row.bookId,row.contextDigest)
        val parsed=readCommandDocument<CommandPlan>(row.planJson)
        check(parsed.book_id==row.bookId&&parsed.plan_id==row.planId)
        parsed.steps.forEach{step->
            val adapter=registry.undoAdapter(step.command)
            adapter.validate(step.input)
        }
        return parsed
    }
    suspend fun preview(id:String):ConfirmationModel {
        val row=plan(id);check(row.status in listOf("PENDING","FAILED")){"方案已处理 / プランは処理済みです"}
        val parsed=try{parseStored(row)}catch(e:Exception){fail(row,e);throw e}
        require(row.undoOf!=null||parsed.steps.isNotEmpty()){"回答无需执行 / 回答は実行不要です"}
        // Fetch public reference rates before entering any database transaction.
        val rates=referenceSnapshot(parsed)
        val date=LocalDate.now(ledger.clock)
        val context=ExecutionContext(date.toString(),ledger.clock.zone.id,date.plusDays(366).toString(),rates)
        try {
            ledger.mediaWrites.withLock {
                db.withTransaction {
                    check(plan(id).status in listOf("PENDING","FAILED"))
                    val (normalized,model)=run(parsed,null,context,row.undoOf)
                    throw TrialRollback(normalized,model)
                }
            }
        }catch(trial:TrialRollback) {
            // Only catch outside the outermost transaction. Cancellation/business errors propagate.
            db.withTransaction {
                val current=plan(id);check(current.status in listOf("PENDING","FAILED"))
                dao.update(current.copy(status="PENDING",normalizedJson=storeCommandJson(commandJson.encodeToString(trial.normalized)),
                    confirmationJson=storeCommandJson(commandJson.encodeToString(trial.confirmation)),executionContextJson=commandJson.encodeToString(context),checksJson="[]",error=null))
            }
            return trial.confirmation
        }catch(e:CancellationException){throw e}
        catch(e:Exception){fail(row,e);throw e}
    }
    suspend fun confirm(id:String,checks:Set<String>):CommandPlanEntity {
        val row=plan(id)
        if(row.status=="EXECUTED"){followups.resume();return plan(id)}
        check(row.status=="PENDING")
        val preview=requireNotNull(confirmation(row)){"请先预览 / 先にプレビューしてください"}
        check(preview.executable){if(preview.conflicts.isNotEmpty())"存在撤销冲突，不能整批撤销 / 変更の競合があるため一括取消できません"else"资料不足，不能执行 / 情報不足のため実行できません"}
        val required=preview.requirements.filter{it.kind!=ConfirmationKind.INFORMATION}.map{it.key}.toSet()
        require(checks==required){"请逐项勾选当前事实和风险 / 現在の事実とリスクを確認してチェックしてください"}
        val normalized=readCommandDocument<NormalizedPlan>(requireNotNull(row.normalizedJson))
        val parsed=try{parseStored(row)}catch(e:Exception){fail(row,e);throw e}
        try {
            val result=ledger.mediaWrites.withLock {
                db.withTransaction {
                    val current=plan(id)
                    if(current.status=="EXECUTED")return@withTransaction current
                    check(current.status=="PENDING"&&current.confirmationJson==row.confirmationJson)
                    if(LocalDate.now(ledger.clock).toString()!=preview.context.asOfDate)throw CommandRepreviewRequired("$.context.asOfDate")
                    if(ledger.clock.zone.id!=preview.context.zoneId)throw CommandRepreviewRequired("$.context.zoneId")
                    val (_,actual,execution)=run(parsed,normalized,preview.context,row.undoOf)
                    CommandConfirmationComparator.firstDifference(preview,actual)?.let{throw CommandRepreviewRequired(it)}
                    if(LocalDate.now(ledger.clock).toString()!=preview.context.asOfDate)throw CommandRepreviewRequired("$.context.asOfDate")
                    if(ledger.clock.zone.id!=preview.context.zoneId)throw CommandRepreviewRequired("$.context.zoneId")
                    currentCoroutineContext().ensureActive()
                    execution.followups.forEach{effect->dao.enqueue(CommandEffectEntity(effect.id,current.planId,effect.stepId,effect.mode,storeCommandJson(commandJson.encodeToString(effect))))}
                    val done=current.copy(status="EXECUTED",checksJson=commandJson.encodeToString(checks.sorted()),executedAt=ledger.clock.millis(),executionJson=storeCommandJson(commandJson.encodeToString(execution)),error=null)
                    dao.update(done)
                    if(done.undoOf==null)recordReceipt(done) else {
                        val original=plan(done.undoOf)
                        check(original.status=="EXECUTED")
                        ledger.recordMerchantCloudUndoInTransaction(original.bookId,original.planId,original.localId,done.planId)
                        dao.update(original.copy(status="UNDONE",undoneAt=done.executedAt))
                    }
                    done
                }
            }
            enqueueAcks();followups.resume();return plan(result.planId)
        }catch(e:CancellationException){throw e}
        catch(e:CommandRepreviewRequired) {
            android.util.Log.d("CommandStage1","confirmation mismatch at ${e.differencePath}")
            db.withTransaction{val current=plan(id);if(current.status !in listOf("EXECUTED","UNDONE"))dao.update(current.copy(confirmationJson=null,normalizedJson=null,executionContextJson=null,checksJson="[]",error=e.message))}
            throw e
        }catch(e:Exception){fail(row,e);throw e}
    }
    suspend fun reject(id:String) {
        db.withTransaction {val row=plan(id);check(row.status in listOf("PENDING","FAILED"));dao.update(row.copy(status="REJECTED",checksJson="[]"))}
    }
    private suspend fun run(plan:CommandPlan,fixed:NormalizedPlan?,context:ExecutionContext,undoOf:String?=null):Triple<NormalizedPlan,ConfirmationModel,CommandExecutionRecord> {
        val record=undoOf?.let{id->
            val original=this.plan(id)
            check(original.status=="EXECUTED"){"方案已撤销 / プランは取消済みです"}
            val availability=undoAvailability(original)
            check(availability.available){"${availability.zh} / ${availability.ja}"}
            requireNotNull(readCommandExecution(requireNotNull(original.executionJson)).undo).also {
                check(it.version in 1..3&&plan.steps==it.steps)
            }
        }
        val source=this.plan(plan.plan_id)
        val run=CommandRun(ledger,plan.book_id,context,record,plan.plan_id,source.localId,preferences?.commandValues(plan.book_id).orEmpty(),grantedPermissions());val steps=mutableListOf<CommandStep>();val effects=mutableListOf<StepEffect>()
        val inverses=mutableListOf<UndoPreparation>()
        if(record!=null) {
            val conflicts=run.undoConflicts(record)
            if(conflicts.isNotEmpty())return Triple(NormalizedPlan(plan.steps),ConfirmationModel(context,emptyList(),
                record.effects.filter{it.type in setOf("monthlyPlan","preference","analysisPreference","statementAssociation","statementRow","receiptProducts","attachment","csvBatch","drafts","entryDraft","book","account","category","merchant","merchantLocation","friend","template","entry","rule","prediction","bill","activity","reserve","recurrence","occurrence","expectedIncome","treat","budgetSetting","ruleValidation","contract","contractBalance","repayment","repaymentAllocation","contractPlan","arrangement","arrangementReconciliation","poolPaymentChange","billAdjustment","creditLimitConfig","creditLimitGroup","creditLimitGroupSetting","creditLimitMember","creditLimitRecovery","creditLimitObservation","financialDraft")&&it.before!=it.after}.map{it.copy(before=it.after,after=it.before)},
                conflicts=conflicts,removals=record.removals),run.executionRecord())
        }
        val journal=CommandUndoJournal(ledger,plan.book_id)
        val beforeRows=journal.snapshot()
        val touchedRows=mutableListOf<UndoRowChange>()
        for((index,step) in plan.steps.withIndex()) {
            currentCoroutineContext().ensureActive()
            val adapter=if(undoOf!=null)registry.undoAdapter(step.command)else registry.adapter(step.command)
            val normalized=step.copy(input=fixed?.steps?.get(index)?.input?:adapter.normalize(step.input,run))
            steps+=normalized
            val before=run.state()
            val stepRows=journal.snapshot()
            val rawEffect=adapter.execute(normalized,run)
            val afterStepRows=journal.snapshot()
            val stepChanges=journal.changes(stepRows,after=afterStepRows)
            touchedRows+=stepChanges
            val referenceBasis=journal.referenceBasis(adapter.capability.inputSchema,normalized.input,run,stepRows,afterStepRows)
            val effect=run.finishLedgerEffect(normalized,rawEffect,stepChanges,referenceBasis)
            val inverse=if(undoOf!=null)UndoPreparation(UndoAvailability(false,"撤销操作不能再次撤销。","取消操作を再び取り消すことはできません。"))
                else if(effect.missing.isNotEmpty())UndoPreparation(UndoAvailability(false,"资料不足，尚不能确定逆操作。","情報不足のため取消方法を確定できません。"))
                else adapter.inverse(normalized,run,before,run.state())
            inverses+=inverse
            effects+=effect.copy(undo=inverse.availability)
            // Without this output, dependent commands cannot be simulated honestly. Keep remaining
            // steps visible as blocked, and roll back all preceding writes.
            if(effects.last().missing.isNotEmpty()) {
                plan.steps.drop(index+1).forEach{pending->steps+=pending;effects+=StepEffect(pending.id,pending.command,emptyList(),emptyList(),missing=listOf("previousStep.incomplete"))}
                break
            }
        }
        val unknown=(effects.flatMap{it.missing}+effects.flatMap{it.objects}.flatMap{effect->
            if(effect.type=="unknownRuleFields")effect.after.jsonArray.map{it.jsonPrimitive.content}
            else if(effect.type in setOf("creditLimit","creditLimitGroup"))(effect.after as? JsonObject)?.get("issues")?.jsonArray.orEmpty().map{it.jsonPrimitive.content}
            else if(effect.type=="cashForecast")(effect.after as? JsonObject)?.get("periods")?.jsonArray.orEmpty().mapNotNull{it.jsonObject["item"]?.jsonObject?.get("issue")?.jsonPrimitive?.contentOrNull}
            else emptyList()
        }).distinct()
        // Deferred attachments protect the FINAL transactional entry, including later steps.
        val finalState=run.state().ledger
        val execution=run.executionRecord().let{record->record.copy(followups=record.followups.map{f->
            if(f.kind=="PREFERENCE"&&f.input["key"]==JsonPrimitive("activeBook")&&f.after!=JsonNull)
                requireNotNull(ledger.dao.book(f.after.jsonPrimitive.content)){"最终所选账本已不可用 / 最後に選択する帳簿が利用できません"}
            if(f.kind in setOf("ATTACHMENT","DRAFT"))requireNotNull(ledger.dao.book(f.bookId)){"后续步骤所属账本已回收 / 後続処理の帳簿はごみ箱にあります"}
            if(f.kind=="ATTACHMENT")f.copy(before=attachmentTarget(finalState,f.input.getValue("entryId").jsonPrimitive.content))else f
        })}
        val guards=inverses.flatMap{it.guards}.distinctBy{it.type to it.key}.map{it.copy(after=run.undoValue(it.type,it.key))}
        val actualEffects=commandJson.decodeFromJsonElement<List<ObjectEffect>>(actualCommandValues(encoded(netEffects(effects)),execution.generatedIds))
        val rows=if(record==null)journal.changes(beforeRows,touchedRows)else emptyList()
        val accountGuards=rows.filter{it.table=="accounts"&&it.before!=null}.map{row->
            val id=row.after.getValue("id").jsonPrimitive.content
            UndoGuard("accountUsage",id,run.undoValue("accountUsage",id))
        }
        val entryGuards=rows.filter{it.table=="events"&&it.before!=null}.flatMap{row->val id=row.after.getValue("id").jsonPrimitive.content
            listOf(UndoGuard("entry",id,run.undoValue("entry",id)),UndoGuard("entryDetails",id,run.undoValue("entryDetails",id)),UndoGuard("entryPlace",id,run.undoValue("entryPlace",id)),UndoGuard("entryAssociations",id,run.undoValue("entryAssociations",id)))}
        val structureGuards=rows.filter{it.before!=null}.mapNotNull{row->
            val type=when {
                row.table=="categories"&&row.before?.get("parentId")!=row.after["parentId"]->"categoryChildren"
                row.table=="books"&&row.before?.get("currency")!=row.after["currency"]->"bookUsage"
                else->null
            }?:return@mapNotNull null
            val id=row.after.getValue("id").jsonPrimitive.content
            UndoGuard(type,id,run.undoValue(type,id))
        }
        val planningGuards=rows.filter{it.before!=null&&commandTableType(it.table) in setOf("activity","recurrence","occurrence","reserve","treat","expectedIncome")}.map{row->
            val type="planning:"+commandTableType(row.table);val id=row.after.getValue("id").jsonPrimitive.content
            UndoGuard(type,id,run.undoValue(type,id))
        }+rows.filter{it.table=="draft_media"&&it.removed}.map{row->
            val id=row.after.getValue("fileId").jsonPrimitive.content
            UndoGuard("planningFile",id,run.undoValue("planningFile",id))
        }
        val financeGuards=rows.filter{it.before!=null&&it.table in setOf("financial_rules","financial_plans","financial_bills","financial_arrangements","financial_repayments","financial_repayment_allocations","debt_contracts","credit_limit_configs","credit_limit_groups","credit_limit_recoveries","credit_limit_observations")}.map{row->
            val id=(row.after["id"]?:row.after.getValue("eventId")).jsonPrimitive.content
            val type="finance:"+row.table
            UndoGuard(type,id,run.undoValue(type,id))
        }
        val inverseSteps=(if(rows.isEmpty())emptyList()else listOf(CommandStep("restore_objects","local.restoreExecution",1,buildJsonObject{})))+preferenceInverseSteps(inverses.asReversed().flatMap{it.steps})
        val undo=if(undoOf==null)UndoRecord(inverseSteps,guards+accountGuards+entryGuards+structureGuards+planningGuards+financeGuards,actualEffects,rows,journal.removals(rows),version=3)else null
        return Triple(NormalizedPlan(steps),ConfirmationModel(context,effects,netEffects(effects),unknown=unknown,removals=record?.removals.orEmpty()),execution.copy(undo=undo))
    }
    private fun grantedPermissions():Set<String> {
        val context=applicationContext?:return emptySet()
        fun granted(permission:String)=context.checkSelfPermission(permission)==android.content.pm.PackageManager.PERMISSION_GRANTED
        return buildSet {
            if(granted(android.Manifest.permission.ACCESS_COARSE_LOCATION)||granted(android.Manifest.permission.ACCESS_FINE_LOCATION))add("LOCATION")
            if(android.os.Build.VERSION.SDK_INT<33||granted(android.Manifest.permission.POST_NOTIFICATIONS))add("NOTIFICATIONS")
        }
    }
    private suspend fun recordReceipt(row:CommandPlanEntity) {
        val cloud=db.cloudDao()
        if(cloud.imported(row.sourceKey)==null)cloud.insertImport(CloudImportEntity(row.sourceKey,row.localId,row.taskId,row.resultId,row.resultVersion,row.sha256,row.planId,ledger.clock.millis()))
        cloud.insertAck(CloudAckEntity(row.localId,row.resultId,"imported",row.taskId,ledger.clock.millis()))
    }
    private suspend fun fail(row:CommandPlanEntity,error:Exception) {
        db.withTransaction {
            val current=plan(row.planId)
            if(current.status !in listOf("EXECUTED","UNDONE")) {
                dao.update(current.copy(status="FAILED",error=error.message,checksJson="[]"))
                if(row.undoOf==null&&db.monthlyDao().submission(row.localId)?.planId!=row.planId)db.cloudDao().insertAck(CloudAckEntity(row.localId,row.resultId,"failed",row.taskId,ledger.clock.millis()))
            }
        }
        enqueueAcks()
    }
}
