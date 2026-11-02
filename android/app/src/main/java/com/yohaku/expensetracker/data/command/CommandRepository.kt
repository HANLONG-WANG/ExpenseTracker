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

private class TrialRollback(val normalized:NormalizedPlan,val confirmation:ConfirmationModel):RuntimeException(null,null,false,false)
class CommandRepreviewRequired(val differencePath:String?=null):IllegalStateException("相关资料或日期已变化，请重新预览并勾选 / 関連情報または日付が変わりました。再確認してチェックしてください")

/** No network/file work inside the media lock/transaction. Cloud receipt recovery uses cloud_acks. */
class CommandRepository(val ledger:LedgerRepository,val registry:CommandRegistry=CommandRegistry(),
    private val referenceSnapshot:suspend (CommandPlan)->List<ReferenceRateSnapshot> = {emptyList()},private val enqueueAcks:()->Unit={}) {
    private val db=ledger.db
    private val dao=db.commandDao()
    fun history(bookId:String)=dao.history(bookId)
    suspend fun plan(id:String)=requireNotNull(dao.plan(id))
    fun confirmation(row:CommandPlanEntity)=row.confirmationJson?.let{readCommandDocument<ConfirmationModel>(it)}

    /** The caller verifies the downloaded cloud claim. Tests submit the same fixed claim here. */
    suspend fun receive(claim:CloudClaim,origin:String,expectedContextDigest:String):CommandPlanEntity {
        require(sha256(claim.text)==claim.sha256){"方案校验值不符 / プランのチェックサムが一致しません"}
        val key=importKey(origin,claim.taskId,claim.resultId,claim.sha256)
        dao.source(key)?.let{return it}
        val parsed=try{registry.parse(claim.text,claim.bookId,expectedContextDigest)}catch(e:Exception){
            val failed=CommandPlanEntity("invalid:$key",claim.bookId,claim.localId,origin,claim.taskId,claim.resultId,claim.resultVersion,claim.sha256,key,
                storeCommandJson(claim.text),expectedContextDigest,status="FAILED",error=e.message,createdAt=ledger.clock.millis())
            db.withTransaction {if(dao.source(key)==null)dao.insert(failed);db.cloudDao().insertAck(CloudAckEntity(claim.localId,claim.resultId,"failed",claim.taskId,ledger.clock.millis()))}
            enqueueAcks();return failed
        }
        val row=db.withTransaction {
            val existing=dao.source(key)?:dao.plan(parsed.plan_id)
            if(existing!=null) {
                require(existing.bookId==claim.bookId){"方案ID已被另一账本使用 / プランIDは別の帳簿で使用済みです"}
                if(existing.status=="EXECUTED")recordReceipt(existing.copy(localId=claim.localId,origin=origin,taskId=claim.taskId,resultId=claim.resultId,resultVersion=claim.resultVersion,sha256=claim.sha256,sourceKey=key))
                existing
            }else CommandPlanEntity(parsed.plan_id,claim.bookId,claim.localId,origin,claim.taskId,claim.resultId,claim.resultVersion,claim.sha256,key,
                storeCommandJson(claim.text),expectedContextDigest,createdAt=ledger.clock.millis()).also{dao.insert(it)}
        }
        enqueueAcks();return row
    }
    suspend fun preview(id:String):ConfirmationModel {
        val row=plan(id);check(row.status in listOf("PENDING","FAILED")){"方案已处理 / プランは処理済みです"}
        val parsed=try{registry.parse(readCommandJson(row.planJson),row.bookId,row.contextDigest)}catch(e:Exception){fail(row,e);throw e}
        // Fetch public reference rates before entering any database transaction.
        val rates=referenceSnapshot(parsed)
        val date=LocalDate.now(ledger.clock)
        val context=ExecutionContext(date.toString(),ledger.clock.zone.id,date.plusDays(366).toString(),rates)
        try {
            ledger.mediaWrites.withLock {
                db.withTransaction {
                    check(plan(id).status in listOf("PENDING","FAILED"))
                    val (normalized,model)=run(parsed,null,context)
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
        if(row.status=="EXECUTED")return row
        check(row.status=="PENDING")
        val preview=requireNotNull(confirmation(row)){"请先预览 / 先にプレビューしてください"}
        check(preview.executable){"资料不足，不能执行 / 情報不足のため実行できません"}
        val required=preview.requirements.filter{it.kind!=ConfirmationKind.INFORMATION}.map{it.key}.toSet()
        require(checks==required){"请逐项勾选当前事实和风险 / 現在の事実とリスクを確認してチェックしてください"}
        val normalized=readCommandDocument<NormalizedPlan>(requireNotNull(row.normalizedJson))
        val parsed=try{registry.parse(readCommandJson(row.planJson),row.bookId,row.contextDigest)}catch(e:Exception){fail(row,e);throw e}
        try {
            val result=ledger.mediaWrites.withLock {
                db.withTransaction {
                    val current=plan(id)
                    if(current.status=="EXECUTED")return@withTransaction current
                    check(current.status=="PENDING"&&current.confirmationJson==row.confirmationJson)
                    if(LocalDate.now(ledger.clock).toString()!=preview.context.asOfDate)throw CommandRepreviewRequired("$.context.asOfDate")
                    if(ledger.clock.zone.id!=preview.context.zoneId)throw CommandRepreviewRequired("$.context.zoneId")
                    val (_,actual,execution)=run(parsed,normalized,preview.context)
                    CommandConfirmationComparator.firstDifference(preview,actual)?.let{throw CommandRepreviewRequired(it)}
                    if(LocalDate.now(ledger.clock).toString()!=preview.context.asOfDate)throw CommandRepreviewRequired("$.context.asOfDate")
                    if(ledger.clock.zone.id!=preview.context.zoneId)throw CommandRepreviewRequired("$.context.zoneId")
                    currentCoroutineContext().ensureActive()
                    val done=current.copy(status="EXECUTED",checksJson=commandJson.encodeToString(checks.sorted()),executedAt=ledger.clock.millis(),executionJson=storeCommandJson(commandJson.encodeToString(execution)),error=null)
                    dao.update(done)
                    recordReceipt(done)
                    done
                }
            }
            enqueueAcks();return result
        }catch(e:CancellationException){throw e}
        catch(e:CommandRepreviewRequired) {
            android.util.Log.d("CommandStage1","confirmation mismatch at ${e.differencePath}")
            db.withTransaction{val current=plan(id);if(current.status!="EXECUTED")dao.update(current.copy(confirmationJson=null,normalizedJson=null,executionContextJson=null,checksJson="[]",error=e.message))}
            throw e
        }catch(e:Exception){fail(row,e);throw e}
    }
    suspend fun reject(id:String) {
        db.withTransaction {val row=plan(id);check(row.status!="EXECUTED");dao.update(row.copy(status="REJECTED",checksJson="[]"))}
    }
    private suspend fun run(plan:CommandPlan,fixed:NormalizedPlan?,context:ExecutionContext):Triple<NormalizedPlan,ConfirmationModel,CommandExecutionRecord> {
        val run=CommandRun(ledger,plan.book_id,context);val steps=mutableListOf<CommandStep>();val effects=mutableListOf<StepEffect>()
        for((index,step) in plan.steps.withIndex()) {
            currentCoroutineContext().ensureActive()
            val adapter=registry.adapter(step.command)
            val normalized=step.copy(input=fixed?.steps?.get(index)?.input?:adapter.normalize(step.input,run))
            steps+=normalized
            effects+=adapter.execute(normalized,run)
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
        return Triple(NormalizedPlan(steps),ConfirmationModel(context,effects,netEffects(effects),unknown=unknown),run.executionRecord())
    }
    private suspend fun recordReceipt(row:CommandPlanEntity) {
        val cloud=db.cloudDao()
        if(cloud.imported(row.sourceKey)==null)cloud.insertImport(CloudImportEntity(row.sourceKey,row.localId,row.taskId,row.resultId,row.resultVersion,row.sha256,row.planId,ledger.clock.millis()))
        cloud.insertAck(CloudAckEntity(row.localId,row.resultId,"imported",row.taskId,ledger.clock.millis()))
    }
    private suspend fun fail(row:CommandPlanEntity,error:Exception) {
        db.withTransaction {
            val current=plan(row.planId)
            if(current.status!="EXECUTED") {
                dao.update(current.copy(status="FAILED",error=error.message,checksJson="[]"))
                db.cloudDao().insertAck(CloudAckEntity(row.localId,row.resultId,"failed",row.taskId,ledger.clock.millis()))
            }
        }
        enqueueAcks()
    }
}
