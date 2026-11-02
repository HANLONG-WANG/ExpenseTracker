package com.yohaku.expensetracker.data.command

import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.category.CategoryIconChoice
import com.yohaku.expensetracker.domain.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.LocalDate

internal val ledgerUndo=UndoAvailability(true,"已有对象恢复执行前完整值；本方案新建且未修改、无外部引用的对象及从属行整体移除。","既存の対象は実行前の状態に戻します。このプランで作成し、変更や外部参照のない対象と従属データは削除します。")
internal fun ledgerCapability(name:String,schema:JsonObject,outputs:Map<String,String> = emptyMap(),defaults:String,prerequisites:String,description:String,example:String)=
    CommandCapability(name,1,schema,outputs,defaults,prerequisites,listOf(ConfirmationKind.INFORMATION,ConfirmationKind.RISK),
        listOf("full before/after business fields and typed relationships", "fixed targets and identifying facts", "absolute balances, budgets, credit limits and per-period forecasts", "save/default ordering; every intermediate effect", "generated IDs mapped by step/output/business key; audit timestamps ignored; recovery deadlines rechecked"),
        description,commandJson.parseToJsonElement(example).jsonObject,
        "Restore exact recorded existing rows; remove only execution-owned new objects and dependent rows, if unchanged and without external FK/JSON/draft references. Full row/relationship conflicts block the entire undo.")

internal abstract class LedgerAdapter:CommandAdapter {
    override suspend fun inverse(step:CommandStep,run:CommandRun,before:FinancialReviewState,after:FinancialReviewState)=UndoPreparation(ledgerUndo)
    protected suspend fun effect(step:CommandStep,run:CommandRun,before:FinancialReviewState,accounts:Set<String> = emptySet(),categories:Set<String> = emptySet()):StepEffect {
        val after=run.state(refresh=true)
        if(accounts.isEmpty()&&categories.isEmpty())return StepEffect(step.id,step.command,emptyList(),emptyList())
        val months=(before.ledger.events+after.ledger.events).map{it.date.take(7)}.toSet()+(before.ledger.budgets+after.ledger.budgets).map{it.month}+(before.ledger.occurrences+after.ledger.occurrences).map{it.scheduledDate.take(7)}+run.context.asOfDate.take(7)
        return run.effect(step,before,after,accounts,categories=categories,months=months)
    }
}

@Serializable internal data class BookCreateInput(val name:String,val currency:Currency,val startDate:String,val theme:String="magazine",val language:String="zh",val budgetMinor:Long?=null)
@Serializable internal data class BookPatch(val name:String?=null,val currency:Currency?=null,val startDate:String?=null,val theme:String?=null,val language:String?=null,val budgetMinor:Long?=null)
@Serializable internal data class BookSetupInput(val step:Int=4,val complete:Boolean=true)
@Serializable internal data class BookLifecycleInput(val operation:BookOperation,val book:String?=null)
internal class BookAdapter(private val action:String):LedgerAdapter() {
    override val capability=ledgerCapability("book.$action",when(action){"create"->descriptorSchema(BookCreateInput.serializer().descriptor);"update"->descriptorSchema(BookPatch.serializer().descriptor);"completeSetup"->descriptorSchema(BookSetupInput.serializer().descriptor);else->descriptorSchema(BookLifecycleInput.serializer().descriptor)},
        if(action=="create")mapOf("book" to "book")else emptyMap(),
        "create: theme=magazine, language=zh, budget=null. update: omitted fields preserved; explicit null only clears budgetMinor. completeSetup: step=4, complete=true. lifecycle: omitted book is plan.book_id; book must be a literal existing ID, never a newly created book reference.",
        "Same book validation as editor: currency change blocked by records/planning/budgets; valid date, nonnegative optional budget, supported theme/language. Setup step 0..4. Lifecycle checks archived/trash state and 30-day recovery deadline.",
        "Create a book, edit this book's settings, complete setup, or archive/unarchive/trash/restore an existing book. V1: all following writes remain scoped to plan.book_id; a created book cannot be written by later steps. Current-book preference is stage 6. PURGE is forbidden.",
        when(action){"create"->"""{"name":"旅行","currency":"JPY","startDate":"2026-11-01"}""";"update"->"""{"name":"家計","theme":"journal","language":"ja"}""";"completeSetup"->"{}";else->"""{"operation":"ARCHIVE"}"""})
    override fun validate(input:JsonObject) {
        when(action){
            "create"->{val v=commandJson.decodeFromJsonElement<BookCreateInput>(input);LocalDate.parse(v.startDate);require(v.theme in listOf("magazine","journal","objects")&&v.language in listOf("zh","ja"))}
            "update"->{commandJson.decodeFromJsonElement<BookPatch>(input);require(input.isNotEmpty());require(input.none{it.value==JsonNull&&it.key!="budgetMinor"})}
            "completeSetup"->{val v=commandJson.decodeFromJsonElement<BookSetupInput>(input);require(v.step in 0..4)}
            else->{val v=commandJson.decodeFromJsonElement<BookLifecycleInput>(input);require(v.operation!=BookOperation.PURGE){permanentAlternative("book.purge")}}
        }
    }
    override suspend fun execute(step:CommandStep,run:CommandRun):StepEffect {
        val before=run.state();val r=run.ledger
        var scope:ObjectEffect?=null
        when(action) {
            "create"->{val v=commandJson.decodeFromJsonElement<BookCreateInput>(step.input);val id=r.createBook(v.name,v.currency,v.theme,v.language,v.startDate,v.budgetMinor);run.output(step.id,"book",id,true)}
            "update"->{val v=commandJson.decodeFromJsonElement<BookPatch>(step.input);val old=requireNotNull(r.dao.book(run.bookId));r.updateBook(old.copy(name=v.name?:old.name,currency=v.currency?.name?:old.currency,startDate=v.startDate?:old.startDate,theme=v.theme?:old.theme,language=v.language?:old.language,budgetMinor=if("budgetMinor" in step.input)v.budgetMinor else old.budgetMinor))}
            "completeSetup"->{val v=commandJson.decodeFromJsonElement<BookSetupInput>(step.input);r.setupStep(run.bookId,v.step);if(v.complete)r.updateBook(requireNotNull(r.dao.book(run.bookId)).copy(initialized=true))}
            else->{val v=commandJson.decodeFromJsonElement<BookLifecycleInput>(step.input);val id=v.book?:run.bookId;require(!run.isGenerated(id)){"本方案新建账本不能成为后续写入目标 / このプランで作成した帳簿は後続の変更対象にできません"};val review=r.previewBookOperation(id,v.operation)
                fun facts(value:BookScope)=buildJsonObject{put("name",value.book.name);put("accounts",value.accounts);put("records",value.records);put("files",value.files);put("financialPlans",value.financialPlans);put("statements",value.statements)}
                check(r.commitBookOperationInTransaction(review));scope=ObjectEffect("bookScope",id,facts(review.scope),facts(r.bookScope(id)))}
        }
        return effect(step,run,before,if(action=="update"&&step.input.keys.any{it in setOf("currency","startDate","budgetMinor")})before.ledger.accounts.map{it.id}.toSet()else emptySet(),if(action=="update"&&step.input.keys.any{it in setOf("currency","startDate","budgetMinor")})before.ledger.categories.map{it.id}.toSet()else emptySet()).let{it.copy(objects=it.objects+listOfNotNull(scope))}
    }
}

@Serializable internal data class ActiveInput(val target:JsonElement,val active:Boolean)
internal class ActiveAdapter(private val type:String):LedgerAdapter() {
    override val capability=ledgerCapability("$type.setActive",patchSchema(descriptorSchema(ActiveInput.serializer().descriptor),mapOf("target" to referenceSchema(type))),defaults="Both target and active required; no toggle/default inference.",prerequisites="Target must belong to plan.book_id. Account uses AccountOperation.ACTIVATE/DISABLE and its existing domain checks; no account trash exists.",description="Enable/disable an account, merchant or friend without deleting it.",example="""{"target":"$type-id","active":false}""")
    override fun validate(input:JsonObject){commandJson.decodeFromJsonElement<ActiveInput>(input)}
    override suspend fun execute(step:CommandStep,run:CommandRun):StepEffect {
        val v=commandJson.decodeFromJsonElement<ActiveInput>(step.input);val id=run.resolve(v.target);val before=run.state();val r=run.ledger
        when(type) {
            "account"->{val old=before.ledger.accounts.single{it.id==id};if(old.active!=v.active)check(r.commitAccountOperation(r.previewAccountOperation(run.bookId,id,if(v.active)AccountOperation.ACTIVATE else AccountOperation.DEACTIVATE)))}
            "merchant"->{val old=before.ledger.merchants.single{it.id==id};r.saveMerchant(run.bookId,old.name,old.note,id,v.active)}
            "friend"->r.setFriendActive(run.bookId,id,v.active)
        }
        return effect(step,run,before,if(type=="account")setOf(id)else emptySet())
    }
}

@Serializable internal data class CategorySaveInput(val name:String,val kind:EntryKind,val category:JsonElement?=null,val parent:JsonElement?=null,val favorite:Boolean?=null,val icon:String?=null)
@Serializable internal data class CategoryFavoriteInput(val category:JsonElement,val favorite:Boolean)
@Serializable internal data class CategoryLifecycleInput(val category:JsonElement,val operation:CategoryOperation)
@Serializable internal data class CategoryParentInput(val category:JsonElement,val parent:JsonElement?)
internal class CategoryAdapter(private val action:String):LedgerAdapter() {
    override val capability=ledgerCapability("category.$action",patchSchema(when(action){"save"->descriptorSchema(CategorySaveInput.serializer().descriptor);"setFavorite"->descriptorSchema(CategoryFavoriteInput.serializer().descriptor);"reparent"->descriptorSchema(CategoryParentInput.serializer().descriptor);else->descriptorSchema(CategoryLifecycleInput.serializer().descriptor)},buildMap {
        put("category",if(action=="save")nullableSchema(referenceSchema("category"))else referenceSchema("category"))
        if(action in listOf("save","reparent"))put("parent",nullableSchema(referenceSchema("category")))
    }),if(action=="save")mapOf("category" to "category")else emptyMap(),
        "save: omitted category creates; omitted parent/favorite/icon preserve existing values; new category defaults root/false/auto icon. Explicit parent=null makes a root, icon=null clears explicit icon. reparent requires parent (null=root).",
        "Expense/income only; at most two levels with matching kind; parent must be enabled. Existing kind immutable. Hierarchy edits go through category impact review; MERGE_DELETE and DELETE_EMPTY forbidden.",
        "Save name/icon, set favorite, archive/unarchive, or adjust hierarchy. To replace a category, update entries and templates then archive it; budget/recurrence/draft references are retained, not silently migrated (corresponding commands in later stages).",
        when(action){"save"->"""{"name":"咖啡","kind":"EXPENSE"}""";"setFavorite"->"""{"category":"category-id","favorite":true}""";"reparent"->"""{"category":"category-id","parent":null}""";else->"""{"category":"category-id","operation":"ARCHIVE"}"""})
    override fun validate(input:JsonObject) {
        when(action){"save"->{val v=commandJson.decodeFromJsonElement<CategorySaveInput>(input);require(v.kind in listOf(EntryKind.EXPENSE,EntryKind.INCOME))};"setFavorite"->commandJson.decodeFromJsonElement<CategoryFavoriteInput>(input);"reparent"->{commandJson.decodeFromJsonElement<CategoryParentInput>(input);require("parent" in input)};else->{val v=commandJson.decodeFromJsonElement<CategoryLifecycleInput>(input);require(v.operation!=CategoryOperation.MERGE_DELETE){permanentAlternative("category.mergeDelete")};require(v.operation!=CategoryOperation.DELETE_EMPTY){permanentAlternative("category.deleteEmpty")};require(v.operation in listOf(CategoryOperation.ARCHIVE,CategoryOperation.UNARCHIVE)){"调整层级请用 category.reparent / 階層変更には category.reparent を使ってください"}}}
    }
    override suspend fun execute(step:CommandStep,run:CommandRun):StepEffect {
        val before=run.state();val r=run.ledger;var id:String
        when(action) {
            "save"->{val v=commandJson.decodeFromJsonElement<CategorySaveInput>(step.input);val old=v.category?.let{ref->before.ledger.categories.single{it.id==run.resolve(ref)}};val parent=if("parent" in step.input)v.parent?.let(run::resolve)else old?.parentId
                if(old!=null&&old.parentId!=parent)check(r.commitCategoryOperation(r.previewCategoryOperation(run.bookId,old.id,CategoryOperation.REPARENT,parent)))
                id=r.saveCategory(run.bookId,v.name,v.kind,parent,old?.id,v.favorite?:old?.favorite?:false,if("icon" in step.input)CategoryIconChoice.Set(v.icon)else CategoryIconChoice.Auto);run.output(step.id,"category",id,old==null)}
            "setFavorite"->{val v=commandJson.decodeFromJsonElement<CategoryFavoriteInput>(step.input);id=run.resolve(v.category);if(before.ledger.categories.single{it.id==id}.favorite!=v.favorite)r.toggleCategoryFavorite(run.bookId,id)}
            "reparent"->{val v=commandJson.decodeFromJsonElement<CategoryParentInput>(step.input);id=run.resolve(v.category);check(r.commitCategoryOperation(r.previewCategoryOperation(run.bookId,id,CategoryOperation.REPARENT,v.parent?.let(run::resolve))))}
            else->{val v=commandJson.decodeFromJsonElement<CategoryLifecycleInput>(step.input);id=run.resolve(v.category);check(r.commitCategoryOperation(r.previewCategoryOperation(run.bookId,id,v.operation)))}
        }
        val hierarchyChanged=action in setOf("reparent","lifecycle")||action=="save"&&step.input["category"]!=null&&"parent" in step.input
        return effect(step,run,before,categories=if(hierarchyChanged)(before.ledger.categories.map{it.id}+id).toSet()else emptySet())
    }
}

@Serializable internal data class MerchantSaveInput(val name:String,val merchant:JsonElement?=null,val note:String?=null)
@Serializable internal data class LocationSaveInput(val merchant:JsonElement,val name:String,val location:JsonElement?=null,val latitude:Double?=null,val longitude:Double?=null,val radiusMeters:Int=100)
@Serializable internal data class FriendSaveInput(val name:String,val friend:JsonElement?=null)
internal class ContactAdapter(private val type:String):LedgerAdapter() {
    override val capability=ledgerCapability("$type.save",patchSchema(when(type){"merchant"->descriptorSchema(MerchantSaveInput.serializer().descriptor);"merchantLocation"->descriptorSchema(LocationSaveInput.serializer().descriptor);else->descriptorSchema(FriendSaveInput.serializer().descriptor)},when(type){"merchantLocation"->mapOf("merchant" to referenceSchema("merchant"),"location" to nullableSchema(referenceSchema("merchantLocation")));else->mapOf(type to nullableSchema(referenceSchema(type)))}),mapOf(type to type),
        "Omitted object creates; existing active state preserved. Merchant note omitted preserves (new empty). Location is a full save: coordinates default unknown/null, radius=100m. Null coordinates must be paired.",
        "Nonblank name; references in this book; existing location cannot change merchant; valid latitude/longitude/radius.","Save merchant/friend or saved merchant location. Save a merchant then save locations referencing its output. Location removal is permanently forbidden; edit its name/coordinates/radius, or disable the merchant.",
        if(type=="merchantLocation")"""{"merchant":"merchant-id","name":"车站店","latitude":35.68,"longitude":139.76}"""else """{"name":"${if(type=="friend")"小林"else"咖啡店"}"}""")
    override fun validate(input:JsonObject){when(type){"merchant"->commandJson.decodeFromJsonElement<MerchantSaveInput>(input);"merchantLocation"->commandJson.decodeFromJsonElement<LocationSaveInput>(input);else->commandJson.decodeFromJsonElement<FriendSaveInput>(input)}}
    override suspend fun execute(step:CommandStep,run:CommandRun):StepEffect {
        val before=run.state();val r=run.ledger;val id:String;val old:String?
        when(type){
            "merchant"->{val v=commandJson.decodeFromJsonElement<MerchantSaveInput>(step.input);old=v.merchant?.let(run::resolve);val prior=old?.let{x->before.ledger.merchants.single{it.id==x}};id=r.saveMerchantWithLocations(run.bookId,v.name,v.note?:prior?.note.orEmpty(),old,prior?.active?:true)}
            "merchantLocation"->{val v=commandJson.decodeFromJsonElement<LocationSaveInput>(step.input);old=v.location?.let(run::resolve);id=r.saveMerchantLocation(run.bookId,run.resolve(v.merchant),v.name,v.latitude,v.longitude,v.radiusMeters,old)}
            else->{val v=commandJson.decodeFromJsonElement<FriendSaveInput>(step.input);old=v.friend?.let(run::resolve);id=r.saveFriend(run.bookId,v.name,old)}
        }
        run.output(step.id,type,id,old==null);return effect(step,run,before)
    }
}

@Serializable internal data class TemplateSaveInput(val name:String,val kind:EntryKind,val category:JsonElement,val account:JsonElement,val currency:Currency,
    val amountMinor:Long?=null,val merchant:String="",val merchantId:JsonElement?=null,val note:String="",val cardPaymentChoice:String?=null,val cardPaymentPeriods:Int?=null,val activity:JsonElement?=null,val template:JsonElement?=null)
@Serializable internal data class TemplateMoveInput(val template:JsonElement,val direction:Int)
internal class TemplateAdapter(private val action:String):LedgerAdapter() {
    override val capability=ledgerCapability("template.$action",patchSchema(if(action=="save")descriptorSchema(TemplateSaveInput.serializer().descriptor)else descriptorSchema(TemplateMoveInput.serializer().descriptor),buildMap {
        put("template",if(action=="save")nullableSchema(referenceSchema("template"))else referenceSchema("template"))
        if(action=="save"){put("category",referenceSchema("category"));put("account",referenceSchema("account"));put("merchantId",nullableSchema(referenceSchema("merchant")));put("activity",nullableSchema(referenceSchema("activity")))}
    }),if(action=="save")mapOf("template" to "template")else emptyMap(),
        "save is a complete replacement: omitted optional amount=null, merchant/note empty, associations/payment choice=null; existing sort position preserved. move direction is -1 or +1, boundary position is unchanged.",
        "Expense/income only; enabled matching category and account/currency, active merchant, available activity; positive amount if specified; supported fixed card choice. Same template validation as editor.",
        "Save or reorder a template. Delete is forbidden; edit or move it instead. template.createEntry compiles into ordinary entry creation and requires a complete amount/currency settlement.",
        if(action=="save")"""{"name":"午餐","kind":"EXPENSE","category":"category-id","account":"account-id","currency":"JPY","amountMinor":900}"""else """{"template":"template-id","direction":-1}""")
    override fun validate(input:JsonObject){if(action=="save")commandJson.decodeFromJsonElement<TemplateSaveInput>(input)else require(commandJson.decodeFromJsonElement<TemplateMoveInput>(input).direction in listOf(-1,1))}
    override suspend fun execute(step:CommandStep,run:CommandRun):StepEffect {
        val before=run.state()
        if(action=="save") {
            val v=commandJson.decodeFromJsonElement<TemplateSaveInput>(step.input);val old=v.template?.let(run::resolve)
            val id=run.ledger.saveTemplate(run.bookId,TemplateInput(v.name,v.kind,run.resolve(v.category),run.resolve(v.account),v.currency,v.amountMinor,v.merchant,v.merchantId?.let(run::resolve),v.note,v.cardPaymentChoice,v.cardPaymentPeriods,v.activity?.let(run::resolve),old));run.output(step.id,"template",id,old==null)
        }else {val v=commandJson.decodeFromJsonElement<TemplateMoveInput>(step.input);run.ledger.moveTemplate(run.bookId,run.resolve(v.template),v.direction)}
        return effect(step,run,before)
    }
}
