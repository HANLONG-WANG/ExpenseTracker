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

@Serializable internal data class BookCreateInput(val name:String,val currency:Currency,@SchemaFormat("date") val startDate:String,val theme:String="magazine",val language:String="zh",@SchemaRange(minimum=0) val budgetMinor:Long?=null)
@Serializable internal data class BookPatch(val name:String?=null,val currency:Currency?=null,@SchemaFormat("date") val startDate:String?=null,val theme:String?=null,val language:String?=null,@SchemaRange(minimum=0) val budgetMinor:Long?=null)
@Serializable internal data class BookSetupInput(@SchemaRange(minimum=0,maximum=4) val step:Int=4,val complete:Boolean=true)
@Serializable internal data class BookLifecycleInput(val operation:BookOperation,val book:String?=null)
internal class BookAdapter(private val action:String):LedgerAdapter() {
    override val capability=ledgerCapability("book.$action",when(action){"create"->descriptorSchema(BookCreateInput.serializer().descriptor);"update"->descriptorSchema(BookPatch.serializer().descriptor);"completeSetup"->descriptorSchema(BookSetupInput.serializer().descriptor);else->patchSchema(descriptorSchema(BookLifecycleInput.serializer().descriptor),mapOf("operation" to enumSchema("ARCHIVE","UNARCHIVE","TRASH","RESTORE")))},
        if(action=="create")mapOf("book" to "book")else emptyMap(),
        when(action){"create"->"name/currency/startDate required; theme=magazine, language=zh, budgetMinor=null. Creates an uninitialized empty book; no automatic selection.";"update"->"Patch: omitted fields preserved; explicit null only clears budgetMinor. At least one field required.";"completeSetup"->"step defaults to 4 (0..4); complete defaults true. false preserves the existing initialized status.";else->"operation required; book omitted/null is plan.book_id. Explicit book must be a literal existing ID, never a newly created book reference."},
        when(action){"create"->"Nonblank name, valid currency/startDate, nonnegative optional budget; supported theme/language. No later step may write into this new book.";"update"->"Task book exists. Currency changes obey existing records/planning/budget constraints; any reset physically deleting budget rows is forbidden. Valid date, optional nonnegative budget and supported theme/language.";"completeSetup"->"Task book exists and is not trashed; step within 0..4; no clearing of already initialized status.";else->"Existing non-generated book; lifecycle checks archived/trash state and 30-day recovery deadline. Permanent deletion is forbidden."},
        when(action){"create"->"Create an empty book. Following writes remain scoped to plan.book_id, never this new book. Its output can be used by book.select after commit.";"update"->"Edit the task book's settings, appearance and opening budget fields; does not create/select a book or change its lifecycle.";"completeSetup"->"Set this book's setup step and, when complete=true, mark setup completed. complete=false does not undo an already completed setup.";else->"Archive/unarchive/trash/restore the specified existing book; recovery deadline is checked on device. PURGE is forbidden. Does not switch the current-book preference."},
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
            "update"->{val v=commandJson.decodeFromJsonElement<BookPatch>(step.input);val old=requireNotNull(r.dao.book(run.bookId));require(v.currency==null||v.currency.name==old.currency||old.initialized||before.ledger.budgets.isEmpty()){permanentAlternative("budget.clearAllRows")};r.updateBook(old.copy(name=v.name?:old.name,currency=v.currency?.name?:old.currency,startDate=v.startDate?:old.startDate,theme=v.theme?:old.theme,language=v.language?:old.language,budgetMinor=if("budgetMinor" in step.input)v.budgetMinor else old.budgetMinor))}
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
    override val capability=ledgerCapability("$type.setActive",patchSchema(descriptorSchema(ActiveInput.serializer().descriptor),mapOf("target" to referenceSchema(type))),defaults="Both target and active required; no toggle/default inference.",prerequisites=when(type){"account"->"Target must be an account in plan.book_id. There is no dependency block: deactivating keeps the balance, debt, financial plans, bills and history unchanged and still counted; it does not settle or delete anything. No account trash exists.";"merchant"->"Target must be a merchant in plan.book_id; saved name, note and locations are retained. Saving also synchronizes owned record names and linked templates/recurrences/drafts, and formally links name-matched expenses.";else->"Target must be a friend in plan.book_id; name and participation history are retained."},description="Enable/disable this $type without deleting it. The target accepts only a $type ID or a preceding $type output.",example="""{"target":"$type-id","active":false}""")
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
        if(action=="lifecycle")put("operation",enumSchema("ARCHIVE","UNARCHIVE"))
    }),if(action=="save")mapOf("category" to "category")else emptyMap(),
        when(action){"save"->"name/kind required; category omitted/null creates. Omitted parent/favorite/icon preserve existing values; new category defaults root/false/auto icon. parent:null makes root, icon:null clears the explicit icon; favorite:null preserves/defaults false.";"setFavorite"->"category and favorite required; explicit boolean state, not a toggle.";"reparent"->"category and parent required; parent:null means root.";else->"category and operation required; only ARCHIVE/UNARCHIVE."},
        when(action){"save"->"Expense/income only; nonblank name; existing kind immutable. Parent must be enabled, same kind and keep at most two levels; hierarchy changes use the shared review.";"setFavorite"->"Existing category in this book; retains all other category fields.";"reparent"->"Existing category and valid enabled same-kind parent or root; at most two levels, no cycle; shared hierarchy review.";else->"Existing category in this book; shared hierarchy impact review. MERGE_DELETE/DELETE_EMPTY forbidden; use category.reparent for parent changes."},
        (when(action){"save"->"Create/edit this category's name, icon, parent and favorite state.";"setFavorite"->"Set this category's explicit favorite state, without changing other fields.";"lifecycle"->"Archive/unarchive this category and show the hierarchy impact.";else->"Move this category to the specified parent (null means root) with hierarchy impact review."})+" To replace a category, update entries and templates then archive it; use budget.save (destination limit plus null source settings), activity.save categoryBudgets, recurrence.save and occurrence.update for planning references. Null budget history remains. Migrate ordinary drafts with entryDraft.reclassify and expected-income financial drafts with financialDraft.reclassify. Keep the archived source and its history.",
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
@Serializable internal data class LocationSaveInput(val merchant:JsonElement,val name:String,val location:JsonElement?=null,val latitude:Double?=null,val longitude:Double?=null,@SchemaRange(minimum=1) val radiusMeters:Int=100)
@Serializable internal data class FriendSaveInput(val name:String,val friend:JsonElement?=null)
internal class ContactAdapter(private val type:String):LedgerAdapter() {
    override val capability=ledgerCapability("$type.save",patchSchema(when(type){"merchant"->descriptorSchema(MerchantSaveInput.serializer().descriptor);"merchantLocation"->descriptorSchema(LocationSaveInput.serializer().descriptor);else->descriptorSchema(FriendSaveInput.serializer().descriptor)},when(type){"merchantLocation"->mapOf("merchant" to referenceSchema("merchant"),"location" to nullableSchema(referenceSchema("merchantLocation")));else->mapOf(type to nullableSchema(referenceSchema(type)))}),mapOf(type to type),
        when(type){"merchant"->"name required; merchant omitted/null creates; active state preserved. note omitted/null preserves (new empty), empty string clears.";"friend"->"name required; friend omitted/null creates; existing active state preserved.";else->"merchant/name required; location omitted/null creates. Full save: latitude/longitude default null (unknown), radiusMeters=100; null coordinates must be paired."},
        when(type){"merchant"->"Nonblank name; existing merchant ID must belong to this book. Names match after trim, case and width normalization. A new or renamed merchant cannot duplicate another saved merchant; merge in the app instead. Existing historical duplicates may be saved without changing the normalized name.";"friend"->"Nonblank name; existing friend ID must belong to this book.";else->"Nonblank name; merchant and optional existing location belong to this book; existing location cannot change merchant; valid paired latitude/longitude and radius."},
        when(type){"merchant"->"Create or edit a merchant. Synchronize owned record names (including trash), linked templates, recurrences and drafts. Name-matched unlinked expenses become linked; Ordinary income/transfer text is not owned by name; explicitly linked income follows the merchant. Refund ownership and display text follow the original expense, including recoverable refunds, without independent links. A new name matching unlinked records adopts those records automatically. The name on a record is a reference to the merchant: synchronization writes no entry revision and the previous name is not kept. Save the merchant first, then merchantLocation.save can reference its output. Disable with merchant.setActive; deletion and merging are only available in the app, never through cloud commands.";"friend"->"Create or rename a friend. Disable with friend.setActive; deletion is permanently forbidden.";else->"Create or edit a saved location of a merchant (name, coordinates, radius); may reference a merchant created by an earlier step. Location removal is permanently forbidden; edit it or disable the merchant."},
        if(type=="merchantLocation")"""{"merchant":"merchant-id","name":"车站店","latitude":35.68,"longitude":139.76}"""else """{"name":"${if(type=="friend")"小林"else"咖啡店"}"}""")
    override fun validate(input:JsonObject){when(type){"merchant"->commandJson.decodeFromJsonElement<MerchantSaveInput>(input);"merchantLocation"->commandJson.decodeFromJsonElement<LocationSaveInput>(input);else->commandJson.decodeFromJsonElement<FriendSaveInput>(input)}}
    override suspend fun execute(step:CommandStep,run:CommandRun):StepEffect {
        val before=run.state();val r=run.ledger;val id:String;val old:String?
        when(type){
            "merchant"->{val v=commandJson.decodeFromJsonElement<MerchantSaveInput>(step.input);old=v.merchant?.let(run::resolve);val prior=old?.let{x->before.ledger.merchants.single{it.id==x}};id=r.saveMerchantWithLocations(run.bookId,v.name,v.note?:prior?.note.orEmpty(),old,prior?.active?:true,confirmAdoption=true)}
            "merchantLocation"->{val v=commandJson.decodeFromJsonElement<LocationSaveInput>(step.input);old=v.location?.let(run::resolve);id=r.saveMerchantLocation(run.bookId,run.resolve(v.merchant),v.name,v.latitude,v.longitude,v.radiusMeters,old)}
            else->{val v=commandJson.decodeFromJsonElement<FriendSaveInput>(step.input);old=v.friend?.let(run::resolve);id=r.saveFriend(run.bookId,v.name,old)}
        }
        run.output(step.id,type,id,old==null);return effect(step,run,before)
    }
}

@Serializable internal data class TemplateSaveInput(val name:String,val kind:EntryKind,val category:JsonElement,val account:JsonElement,val currency:Currency,
    @SchemaRange(minimum=1) val amountMinor:Long?=null,val merchant:String="",val merchantId:JsonElement?=null,val note:String="",val cardPaymentChoice:String?=null,val cardPaymentPeriods:Int?=null,val activity:JsonElement?=null,val template:JsonElement?=null,val budgetExcluded:Boolean=false)
@Serializable internal data class TemplateMoveInput(val template:JsonElement,val direction:Int)
internal class TemplateAdapter(private val action:String):LedgerAdapter() {
    override val capability=ledgerCapability("template.$action",patchSchema(if(action=="save")descriptorSchema(TemplateSaveInput.serializer().descriptor)else descriptorSchema(TemplateMoveInput.serializer().descriptor),buildMap {
        put("template",if(action=="save")nullableSchema(referenceSchema("template"))else referenceSchema("template"))
        if(action=="save")put("cardPaymentPeriods",localConstraintSchema(nullableSchema(buildJsonObject{put("type","integer")}),"Selected account is CREDIT and cardPaymentChoice is INSTALLMENT; ignored otherwise.",rangeSchema(1)))
        if(action=="move")put("direction",JsonObject(rangeSchema(-1,1)+("enum" to encoded(listOf(-1,1)))))
        if(action=="save"){put("category",referenceSchema("category"));put("account",referenceSchema("account"));put("merchantId",nullableSchema(referenceSchema("merchant")));put("activity",nullableSchema(referenceSchema("activity")))}
    }),if(action=="save")mapOf("template" to "template")else emptyMap(),
        if(action=="save")"Complete replacement: omitted template creates; budgetExcluded=false (true only for EXPENSE), optional amount=null, merchant/note empty, associations/payment choice=null; existing sort position preserved."else"template/direction required; direction=-1/+1 moves one position, edge position remains unchanged.",
        if(action=="save")"Expense/income only; enabled matching category and account/currency, existing merchant in this book (disabled allowed), available activity; positive amount if specified; supported fixed card choice. Same template validation as editor."else"Existing template in this book; direction must be -1 or +1. Moving at an edge leaves order unchanged.",
        if(action=="save")"Create/edit a template with complete replacement of editable fields, preserving existing order. Does not create a record; use template.createEntry for that. Physical deletion is forbidden."else"Move this template one position; preserves all template fields. Does not create a record or delete a template.",
        if(action=="save")"""{"name":"午餐","kind":"EXPENSE","category":"category-id","account":"account-id","currency":"JPY","amountMinor":900}"""else """{"template":"template-id","direction":-1}""")
    override fun validate(input:JsonObject){if(action=="save"){val v=commandJson.decodeFromJsonElement<TemplateSaveInput>(input);require(!v.budgetExcluded||v.kind==EntryKind.EXPENSE){"不计入预算仅适用于支出 / 予算の除外は支出のみ指定できます"}}else require(commandJson.decodeFromJsonElement<TemplateMoveInput>(input).direction in listOf(-1,1))}
    override suspend fun execute(step:CommandStep,run:CommandRun):StepEffect {
        val before=run.state()
        if(action=="save") {
            val v=commandJson.decodeFromJsonElement<TemplateSaveInput>(step.input);val old=v.template?.let(run::resolve)
            resolveMerchant(run.bookId,v.kind,v.merchantId?.let(run::resolve),v.merchant,before.ledger.merchants).merchantOrThrow()
            val id=run.ledger.saveTemplate(run.bookId,TemplateInput(v.name,v.kind,run.resolve(v.category),run.resolve(v.account),v.currency,v.amountMinor,v.merchant,v.merchantId?.let(run::resolve),v.note,v.cardPaymentChoice,v.cardPaymentPeriods,v.activity?.let(run::resolve),old,budgetExcluded=v.budgetExcluded));run.output(step.id,"template",id,old==null)
        }else {val v=commandJson.decodeFromJsonElement<TemplateMoveInput>(step.input);run.ledger.moveTemplate(run.bookId,run.resolve(v.template),v.direction)}
        return effect(step,run,before)
    }
}
