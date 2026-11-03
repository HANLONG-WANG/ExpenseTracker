@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package com.yohaku.expensetracker.data.command

import com.yohaku.expensetracker.domain.model.AccountType
import com.yohaku.expensetracker.domain.finance.FinancialRuleVersion
import com.yohaku.expensetracker.data.repository.CreditLimitInput
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID

@Serializable data class CommandCapability(val name:String,val version:Int,val inputSchema:JsonObject,val outputs:Map<String,String>,
    val defaults:String,val prerequisites:String,val confirmationTypes:List<ConfirmationKind>,val comparisonFields:List<String>,
    val description:String,val example:JsonObject,val undo:String,val mode:String="transactional",val unavailable:String="Reject the whole batch if this command's input or prerequisites fail; no step is skipped.")

class CommandRegistry {
    private val maxSteps=500
    private val stepIdPattern="^[A-Za-z][A-Za-z0-9_-]{0,63}$"
    val forbidden=permanentAlternatives.keys.toList()
    internal val adapters:List<CommandAdapter> = listOf(AccountCommandAdapter(),RuleCommandAdapter(),PredictionCommandAdapter(),
        MonthlyPlanAdapter(),BookAdapter("create"),BookAdapter("update"),BookAdapter("completeSetup"),BookAdapter("lifecycle"),
        ActiveAdapter("account"),CategoryAdapter("save"),CategoryAdapter("setFavorite"),CategoryAdapter("lifecycle"),CategoryAdapter("reparent"),
        ContactAdapter("merchant"),ContactAdapter("merchantLocation"),ContactAdapter("friend"),ActiveAdapter("merchant"),ActiveAdapter("friend"),
        EntryWriteAdapter("create"),EntryWriteAdapter("update"),EntryWriteAdapter("confirmActual"),EntryLifecycleAdapter(),
        TemplateAdapter("save"),TemplateAdapter("move"),TemplateEntryAdapter(),
        BudgetAdapter(),BudgetAdapter(true),ActivityAdapter("setMode"),ActivityAdapter("setCurrent"),ActivityAdapter("save"),ActivityAdapter("end"),ActivityAdapter("lifecycle"),
        ReserveAdapter(),ReserveAdapter(true),ParticipantAdapter(),TreatAdapter(),TreatAdapter(true),
        RecurrenceAdapter(),RecurrenceAdapter(true),OccurrenceAdapter("resolve"),OccurrenceAdapter("update"),OccurrenceAdapter("lifecycle"),OccurrenceAdapter("link"),OccurrenceAdapter("unlink"),OccurrenceAdapter("pay"),
        ExpectedIncomeAdapter("save"),ExpectedIncomeAdapter("cancel"),ExpectedIncomeAdapter("arrive")) + financeCommandAdapters() + fileCommandAdapters() + followupCommandAdapters()
    val capabilities:List<CommandCapability> get()=adapters.map{it.capability}
    private val content get()=buildJsonObject{put("format","yohaku.capabilities");put("version",1);put("\$defs",buildJsonObject{put("stepReference",descriptorSchema(CommandRef.serializer().descriptor))});put("commands",encoded(capabilities));put("forbidden",encoded(forbidden));put("forbiddenAlternatives",encoded(permanentAlternatives));put("limits",buildJsonObject{put("steps",maxSteps);put("targets",5000)})}
    val catalogDigest:String get()=digest(content)
    fun export():JsonObject=JsonObject(content+("catalogDigest" to JsonPrimitive(catalogDigest)))
    /** Derived entirely from the v1 serializers and this registry; no independent digest. */
    fun planSchema():JsonObject {
        val commands=capabilities
        val step=descriptorSchema(CommandStep.serializer().descriptor)
        val schema=patchSchema(descriptorSchema(CommandPlan.serializer().descriptor),mapOf(
            "format" to buildJsonObject{put("const","yohaku.command-plan")},
            "version" to buildJsonObject{put("const",1)},
            "plan_id" to buildJsonObject{put("type","string");put("format","uuid");put("pattern","^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")},
            "catalog_digest" to buildJsonObject{put("type","string");put("pattern","^[0-9a-f]{64}$")},
            "context_digest" to buildJsonObject{put("type","string");put("pattern","^[0-9a-f]{64}$")},
            "steps" to buildJsonObject{put("type","array");put("maxItems",maxSteps);put("items",buildJsonObject{
                put("oneOf",JsonArray(commands.map{capability->patchSchema(step,mapOf(
                    "id" to buildJsonObject{put("type","string");put("pattern",stepIdPattern)},
                    "command" to buildJsonObject{put("const",capability.name)},
                    "command_version" to buildJsonObject{put("const",capability.version)},
                    "input" to buildJsonObject{put("\$ref","#/\$defs/${capability.name}")}
                ))}))
            })}
        ))
        return JsonObject(schema+buildJsonObject{
            put("\$schema","https://json-schema.org/draft/2020-12/schema")
            put("title","yohaku.command-plan v1")
            put("description","Step IDs must be unique; references must target earlier steps and a declared output whose type matches x-reference-type (protocol: 引用与金额). Copy book_id and both digests unchanged from this task's attachments (protocol: 只看附件时的填写顺序); structural validation does not replace phone preview/confirmation (protocol: 确认与一致性).")
            put("\$defs",buildJsonObject{put("stepReference",descriptorSchema(CommandRef.serializer().descriptor));commands.forEach{put(it.name,it.inputSchema)}})
        })
    }
    internal fun adapter(name:String)=adapters.singleOrNull{it.capability.name==name}?:error("不支持的命令 / 未対応のコマンド: $name")
    fun parse(raw:String,bookId:String,contextDigest:String):CommandPlan {
        require(raw.toByteArray(Charsets.UTF_8).size<=2*1024*1024){"方案超过2MB / プランが2MBを超えています"}
        val plan=commandJson.decodeFromString<CommandPlan>(raw)
        require(plan.format=="yohaku.command-plan"&&plan.version==1){"未知协议版本 / 未知のプロトコルバージョン"}
        require(UUID.fromString(plan.plan_id).toString()==plan.plan_id.lowercase()){"plan_id 必须为UUID / plan_id はUUIDが必要です"}
        require(plan.book_id==bookId){"账本不匹配 / 帳簿が一致しません"}
        require(plan.catalog_digest==catalogDigest){"能力清单已变化 / 対応機能一覧が変わりました"}
        require(plan.context_digest==contextDigest){"账本上下文不匹配 / 帳簿コンテキストが一致しません"}
        require(plan.steps.size<=maxSteps){"步骤超过500，请拆分 / 500ステップを超えています。分割してください"}
        val prior=mutableMapOf<String,Map<String,String>>()
        for(step in plan.steps) {
            require(step.id.matches(Regex(stepIdPattern))&&step.id !in prior){"步骤ID重复或无效 / ステップIDが重複または無効です"}
            require(step.command !in forbidden){permanentAlternative(step.command)}
            val adapter=adapter(step.command)
            require(step.command_version==adapter.capability.version){"未知命令版本 / 未知のコマンドバージョン"}
            validateSchema(step.input,adapter.capability.inputSchema,step.id,prior)
            adapter.validate(step.input)
            prior[step.id]=adapter.capability.outputs
        }
        return plan.copy(plan_id=UUID.fromString(plan.plan_id).toString())
    }
}

internal val accountSchema:JsonObject get()=patchSchema(descriptorSchema(AccountCommandInput.serializer().descriptor),mapOf(
    "account" to nullableSchema(referenceSchema("account")),
    "type" to enumSchema(*AccountType.entries.map{it.name}.toTypedArray()),
    "creditLimit" to nullableSchema(withoutFields(descriptorSchema(CreditLimitInput.serializer().descriptor),setOf("accountId")))))
internal val ruleSchema:JsonObject get()=patchSchema(descriptorSchema(RuleCommandInput.serializer().descriptor),mapOf(
    "account" to referenceSchema("account"),"paymentAccount" to nullableSchema(referenceSchema("account")),
    "rule" to financeReferences(withoutFields(descriptorSchema(FinancialRuleVersion.serializer().descriptor),setOf("id","bookId","accountId")))))
internal val predictionSchema:JsonObject get()=patchSchema(descriptorSchema(PredictionCommandInput.serializer().descriptor),mapOf(
    "account" to referenceSchema("account"),"paymentAccount" to nullableSchema(referenceSchema("account")),
    "sourceEvent" to nullableSchema(referenceSchema("entry")),"plan" to nullableSchema(referenceSchema("prediction"))))
