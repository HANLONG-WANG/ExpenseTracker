package com.yohaku.expensetracker.data.command

import com.yohaku.expensetracker.domain.finance.FinancialRuleVersion
import com.yohaku.expensetracker.data.repository.CreditLimitInput
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID

@Serializable data class CommandCapability(val name:String,val version:Int,val inputSchema:JsonObject,val outputs:Map<String,String>,
    val defaults:String,val prerequisites:String,val confirmationTypes:List<ConfirmationKind>,val comparisonFields:List<String>,
    val description:String,val example:JsonObject,val undo:String,val mode:String="transactional",val unavailable:String="Reject the whole batch; incomplete predictions remain a non-executable preview.")

class CommandRegistry {
    val forbidden=permanentAlternatives.keys.toList()
    internal val adapters:List<CommandAdapter> = listOf(AccountCommandAdapter(),RuleCommandAdapter(),PredictionCommandAdapter(),
        BookAdapter("create"),BookAdapter("update"),BookAdapter("completeSetup"),BookAdapter("lifecycle"),
        ActiveAdapter("account"),CategoryAdapter("save"),CategoryAdapter("setFavorite"),CategoryAdapter("lifecycle"),CategoryAdapter("reparent"),
        ContactAdapter("merchant"),ContactAdapter("merchantLocation"),ContactAdapter("friend"),ActiveAdapter("merchant"),ActiveAdapter("friend"),
        EntryWriteAdapter("create"),EntryWriteAdapter("update"),EntryWriteAdapter("confirmActual"),EntryLifecycleAdapter(),
        TemplateAdapter("save"),TemplateAdapter("move"),TemplateEntryAdapter(),
        BudgetAdapter(),BudgetAdapter(true),ActivityAdapter("setMode"),ActivityAdapter("setCurrent"),ActivityAdapter("save"),ActivityAdapter("end"),ActivityAdapter("lifecycle"),
        ReserveAdapter(),ReserveAdapter(true),ParticipantAdapter(),TreatAdapter(),TreatAdapter(true),
        RecurrenceAdapter(),RecurrenceAdapter(true),OccurrenceAdapter("resolve"),OccurrenceAdapter("update"),OccurrenceAdapter("lifecycle"),OccurrenceAdapter("link"),OccurrenceAdapter("unlink"),OccurrenceAdapter("pay"),
        ExpectedIncomeAdapter("save"),ExpectedIncomeAdapter("cancel"),ExpectedIncomeAdapter("arrive"))
    val capabilities:List<CommandCapability> get()=adapters.map{it.capability}
    private val content get()=buildJsonObject{put("format","yohaku.capabilities");put("version",1);put("\$defs",buildJsonObject{put("stepReference",descriptorSchema(CommandRef.serializer().descriptor))});put("commands",encoded(capabilities));put("forbidden",encoded(forbidden));put("forbiddenAlternatives",encoded(permanentAlternatives));put("limits",buildJsonObject{put("steps",500);put("targets",5000)})}
    val catalogDigest:String get()=digest(content)
    fun export():JsonObject=JsonObject(content+("catalogDigest" to JsonPrimitive(catalogDigest)))
    internal fun adapter(name:String)=adapters.singleOrNull{it.capability.name==name}?:error("不支持的命令 / 未対応のコマンド: $name")
    fun parse(raw:String,bookId:String,contextDigest:String):CommandPlan {
        require(raw.toByteArray(Charsets.UTF_8).size<=2*1024*1024){"方案超过2MB / プランが2MBを超えています"}
        val plan=commandJson.decodeFromString<CommandPlan>(raw)
        require(plan.format=="yohaku.command-plan"&&plan.version==1){"未知协议版本 / 未知のプロトコルバージョン"}
        require(UUID.fromString(plan.plan_id).toString()==plan.plan_id.lowercase()){"plan_id 必须为UUID / plan_id はUUIDが必要です"}
        require(plan.book_id==bookId){"账本不匹配 / 帳簿が一致しません"}
        require(plan.catalog_digest==catalogDigest){"能力清单已变化 / 対応機能一覧が変わりました"}
        require(plan.context_digest==contextDigest){"账本上下文不匹配 / 帳簿コンテキストが一致しません"}
        require(plan.steps.size<=500){"步骤超过500，请拆分 / 500ステップを超えています。分割してください"}
        val prior=mutableMapOf<String,Map<String,String>>()
        for(step in plan.steps) {
            require(step.id.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,63}"))&&step.id !in prior){"步骤ID重复或无效 / ステップIDが重複または無効です"}
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
    "creditLimit" to nullableSchema(withoutFields(descriptorSchema(CreditLimitInput.serializer().descriptor),setOf("accountId")))))
internal val ruleSchema:JsonObject get()=patchSchema(descriptorSchema(RuleCommandInput.serializer().descriptor),mapOf(
    "account" to referenceSchema("account"),"paymentAccount" to nullableSchema(referenceSchema("account")),
    "rule" to withoutFields(descriptorSchema(FinancialRuleVersion.serializer().descriptor),setOf("id","bookId","accountId"))))
internal val predictionSchema:JsonObject get()=patchSchema(descriptorSchema(PredictionCommandInput.serializer().descriptor),mapOf(
    "account" to referenceSchema("account"),"paymentAccount" to nullableSchema(referenceSchema("account")),
    "sourceEvent" to nullableSchema(referenceSchema("entry")),"plan" to nullableSchema(referenceSchema("prediction"))))
