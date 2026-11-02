package com.yohaku.expensetracker.data.command

import com.yohaku.expensetracker.domain.currency.ReferenceRateSnapshot
import com.yohaku.expensetracker.domain.finance.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.security.MessageDigest

internal val commandJson=Json { encodeDefaults=true; ignoreUnknownKeys=false }
internal inline fun <reified T> encoded(value:T):JsonElement=commandJson.encodeToJsonElement(value)
internal fun digest(value:JsonElement):String=sha256(canonical(value).toString())
internal fun sha256(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
internal fun canonical(value:JsonElement):JsonElement=when(value) {
    is JsonObject->JsonObject(value.toSortedMap().mapValues{canonical(it.value)})
    is JsonArray->JsonArray(value.map(::canonical))
    else->value
}

@Serializable data class CommandPlan(val format:String,val version:Int,val plan_id:String,val book_id:String,
    val catalog_digest:String,val context_digest:String,val message:String="",val questions:List<String> = emptyList(),val steps:List<CommandStep> = emptyList())
@Serializable data class CommandStep(val id:String,val command:String,val command_version:Int,val input:JsonObject)
@Serializable data class CommandRef(val ref:String,val output:String)
@Serializable data class ExecutionContext(val asOfDate:String,val zoneId:String,val throughDate:String,val referenceRates:List<ReferenceRateSnapshot> = emptyList())
@Serializable enum class ConfirmationKind { INFORMATION, RISK, USER_FACT }
@Serializable data class ConfirmationRequirement(val stepId:String,val kind:ConfirmationKind,val conditionDigest:String,val zh:String,val ja:String) {
    val key:String get()="$stepId:$kind:$conditionDigest"
}
@Serializable data class ObjectEffect(val type:String,val key:String,val before:JsonElement,val after:JsonElement)
@Serializable data class CommandRisk(val code:String,val subject:String,val currency:String?=null,val minor:Long?=null,
    val date:String?=null,val detail:String?=null,val requiresConfirmation:Boolean=false)
@Serializable data class StepEffect(val stepId:String,val command:String,val targets:List<String>,val objects:List<ObjectEffect>,
    val requirements:List<ConfirmationRequirement> = emptyList(),val missing:List<String> = emptyList(),val mode:String="transactional",val risks:List<CommandRisk> = emptyList(),val undo:UndoAvailability = UndoAvailability(false,"旧执行记录没有撤销资料","以前の実行履歴には取消情報がありません"))
@Serializable data class ConfirmationModel(val context:ExecutionContext,val steps:List<StepEffect>,val net:List<ObjectEffect>,
    val basis:String="confirmed-ledger; account-currency debt; category-budget; forecast-estimates",val unknown:List<String> = emptyList(),val conflicts:List<UndoConflict> = emptyList(),val removals:List<UndoRemoval> = emptyList()) {
    val requirements:List<ConfirmationRequirement> get()=steps.flatMap{it.requirements}
    val executable:Boolean get()=conflicts.isEmpty()&&steps.none{it.missing.isNotEmpty()}
}
@Serializable data class NormalizedPlan(val steps:List<CommandStep>)
@Serializable data class CommandExecutionRecord(val outputs:Map<String,Map<String,String>>,val generatedIds:Map<String,String>,val undo:UndoRecord?=null)

@Serializable data class AccountCommandInput(val name:String,val currency:String,val openingMinor:Long,val openingDate:String,
    val account:JsonElement?=null,val type:String="CREDIT",val creditLimit:JsonObject?=null)
@Serializable data class RuleCommandInput(val account:JsonElement,val rule:JsonObject,val paymentAccount:JsonElement?=null)
@Serializable data class PredictionCommandInput(val account:JsonElement,val name:String,val mode:FinanceMode,
    val terms:FinancialScheduleTerms?=null,val sourceEvent:JsonElement?=null,val openingPrincipalMinor:Long?=null,
    val paymentAccount:JsonElement?=null,val plan:JsonElement?=null,val candidates:PredictionCandidateInput?=null)
@Serializable data class PredictionCandidateInput(val agreementDate:String,val count:Int,val firstDate:String,val firstEnd:String,
    val firstPeriodNumber:Int?=null,val remainingFeeMinor:Long?=null)
@Serializable data class EntrySelector(val from:String?=null,val through:String?=null,val account:String?=null,val category:String?=null,
    val merchant_contains:String?=null,val note_contains:String?=null,val kind:String?=null,val min_minor:Long?=null,val max_minor:Long?=null,val currency:String?=null)
@Serializable data class EntryTargets(val ids:List<String>?=null,val select:EntrySelector?=null)
@Serializable data class EntryCategorySet(val category:String)
@Serializable data class EntryUpdateInput(val targets:EntryTargets,val set:EntryCategorySet)
@Serializable data class RecordScope(val mode:String="none",val from:String?=null,val through:String?=null,val account:String?=null)

@Serializable data class UndoAvailability(val available:Boolean,val zh:String,val ja:String)
@Serializable data class UndoConflict(val type:String,val key:String,val field:String,val expected:JsonElement,val current:JsonElement,val reference:UndoReference?=null)
@Serializable data class UndoGuard(val type:String,val key:String,val after:JsonElement)
@Serializable data class UndoRecord(val steps:List<CommandStep>,val guards:List<UndoGuard>,val effects:List<ObjectEffect>,val rows:List<UndoRowChange> = emptyList(),val removals:List<UndoRemoval> = emptyList(),val version:Int=1)
internal data class UndoPreparation(val availability:UndoAvailability,val steps:List<CommandStep> = emptyList(),val guards:List<UndoGuard> = emptyList())

/** Row keys and raw snapshots are local execution evidence, never cloud inputs. Null before means
 * this transaction created the row; only those rows may be removed by local.restoreExecution. */
@Serializable data class UndoRowChange(val table:String,val key:JsonObject,val before:JsonObject?,val after:JsonObject)
@Serializable data class UndoRemoval(val table:String,val key:String,val name:String,val number:Int?=null)
@Serializable data class UndoReference(val table:String,val key:String,val name:String,val targetName:String)
@Serializable internal data class RestoreCategoryInput(val ids:List<String>,val category:String?)
