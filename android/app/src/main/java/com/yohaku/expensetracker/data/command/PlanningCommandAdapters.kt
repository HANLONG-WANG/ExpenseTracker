package com.yohaku.expensetracker.data.command

import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.YearMonth

internal fun arraySchema(item:JsonObject)=buildJsonObject{put("type","array");put("items",item)}

internal fun planningCapability(name:String,schema:JsonObject,outputs:Map<String,String> = emptyMap(),defaults:String,prerequisites:String,description:String,example:String):CommandCapability {
    val base=ledgerCapability(name,schema,outputs,defaults,prerequisites,description,example)
    val fields=when(name.substringBefore('.')) {
        "budget"->listOf("effective month, category scope and nullable limit; carried-forward monthly/category spent, reserved, remaining and available values", "book name/currency/start date when saved together")
        "activity"->listOf("activity mode/current activity; name, start/end, budget, category caps, monthly exclusion, ended/trash status", "affected reservations, recurrence tasks, templates and matching draft; activity spent/held/available and friend balances")
        "activityReserve"->listOf("activity, date, amount/currency, paired account amount/currency, occurrence, released amount and active status", "used reservations, monthly reserve and activity available amounts")
        "activityParticipant"->listOf("activity and friend identity/active state, participant membership and position")
        "activityTreat"->listOf("activity/payer/participants, date/name, original and book amounts, split mode/weights/tail, complete allocations and trash state", "per-friend before/after giving and receiving amounts; no posting")
        "recurrence"->listOf("account/category/activity/merchant, name/note, known-or-unknown amounts, estimated flag, frequency/anchor/end, active, fixed card payment choices", "all affected scheduled-date instances including cancelled/completed status and payment links; per-date cash forecasts")
        "occurrence"->listOf("fixed occurrence/entry pairs or IDs; full instance fields and rule/date identity, status and linked event", "payment entry complete settlement/associations, matching draft/media associations, budgets and cash forecasts")
        else->listOf("expected original/account amounts including unknowns, date/account/status and arrival link; frozen reference conversion evidence", "actual receipt fields/confirmed settlement/date, original expectation retained; income scenario and actual balances")
    }
    return base.copy(comparisonFields=base.comparisonFields+fields,unavailable="Reject the entire batch on missing required facts, invalid relationships or a false/null domain commit. Unknown optional estimates stay unknown; never infer a payment or create an incomplete interactive draft.")
}

/** Full ledger projections plus activity amounts, using the very same projection functions as UI. */
internal suspend fun planningEffect(step:CommandStep,run:CommandRun,before:FinancialReviewState,months:Set<String> = emptySet()):StepEffect {
    val after=run.state(refresh=true)
    val states=listOf(before.ledger,after.ledger)
    fun <T> changed(a:List<T>,b:List<T>)=(a-b.toSet())+(b-a.toSet())
    val old=before.ledger;val next=after.ledger
    val entries=changed(old.events,next.events)
    val occurrences=changed(old.occurrences,next.occurrences)
    val rules=changed(old.recurrences,next.recurrences)
    val reserves=changed(old.reserves,next.reserves)
    val incomes=changed(old.expectedIncomes,next.expectedIncomes)
    val activityIds=(changed(old.activities,next.activities).map{it.id}+changed(old.activityBudgets,next.activityBudgets).map{it.activityId}+
        changed(old.eventActivities,next.eventActivities).map{it.activityId}+reserves.map{it.activityId}).toSet()
    val activityEvents=states.flatMap{s->s.events.filter{e->s.eventActivities.any{it.eventId==e.id&&it.activityId in activityIds}}}
    val budgets=changed(old.budgets,next.budgets)
    val allMonths=months+run.context.asOfDate.take(7)+budgets.map{it.month}+entries.map{it.date.take(7)}+occurrences.map{it.scheduledDate.take(7)}+reserves.map{it.date.take(7)}+activityEvents.map{it.date.take(7)}
    val accounts=(entries.map{it.accountId}+occurrences.map{it.accountId}+rules.map{it.accountId}+reserves.mapNotNull{it.accountId}+incomes.map{it.accountId}).toSet()
    val categories=(entries.mapNotNull{it.categoryId}+occurrences.map{it.categoryId}+rules.map{it.categoryId}+budgets.mapNotNull{it.categoryId}+activityEvents.mapNotNull{it.categoryId}).toSet()
    val entryIds=(entries.map{it.id}+occurrences.mapNotNull{it.eventId}+incomes.mapNotNull{it.eventId}).toSet()
    val effect=run.effect(step,before,after,accounts,entryIds,categories,allMonths)
    fun activityFacts(s:BookSnapshot,id:String):JsonElement {
        val a=s.activities.firstOrNull{it.id==id}?:return JsonNull
        val spent=s.activitySpent(id);val held=s.activityHeld(id)
        return buildJsonObject {
            put("name",a.name);put("currency",s.book.currency);put("limitMinor",a.budgetMinor);put("spentMinor",spent);put("reserveMinor",held)
            put("remainingMinor",a.budgetMinor?.let{Math.subtractExact(it,spent)});put("availableMinor",a.budgetMinor?.let{Math.subtractExact(Math.subtractExact(it,spent),held)})
            put("categoryBudgets",JsonArray(s.activityBudgets.filter{it.activityId==id}.sortedBy{run.semantic(JsonPrimitive(it.categoryId)).toString()}.map{b->buildJsonObject{
                put("categoryId",b.categoryId);put("limitMinor",b.amountMinor);put("spentMinor",s.activitySpent(id,b.categoryId));put("remainingMinor",Math.subtractExact(b.amountMinor,s.activitySpent(id,b.categoryId)))
            }}))
            put("friendBalances",JsonArray(s.friendBalances(id).sortedBy{run.semantic(JsonPrimitive(it.friend.id)).toString()}.map{b->buildJsonObject{
                put("friendId",b.friend.id);put("iGiveMinor",b.iGive);put("theyGiveMinor",b.theyGive);put("differenceMinor",b.difference)
            }}))
        }
    }
    val activities=states.flatMap{it.activities}.map{it.id}.distinct().map{id->ObjectEffect("activityBalance",id,activityFacts(before.ledger,id),activityFacts(after.ledger,id))}.filter{it.before!=it.after}
    return effect.copy(objects=effect.objects+activities)
}

@Serializable internal data class BudgetSaveCommand(@SchemaFormat("year-month") val month:String,@SchemaRange(minimum=0,property="minor") val amount:Money?,val category:JsonElement?=null)
@Serializable internal data class BookBudgetCommand(@SchemaFormat("year-month") val month:String,@SchemaRange(minimum=0,property="minor") val amount:Money?,val name:String?=null,val currency:Currency?=null,@SchemaFormat("date") val startDate:String?=null)
internal class BudgetAdapter(private val book:Boolean=false):LedgerAdapter() {
    override val capability=planningCapability(if(book)"budget.saveBook"else"budget.save",
        if(book)descriptorSchema(BookBudgetCommand.serializer().descriptor)else patchSchema(descriptorSchema(BudgetSaveCommand.serializer().descriptor),mapOf("category" to nullableSchema(referenceSchema("category")))),
        defaults="month/amount required; amount:null clears the limit while retaining its row. "+if(book)"name/currency/startDate omitted or null preserve the book settings; this command sets only the monthly total, with no category field."else"category omitted/null means monthly total; a category reference selects its budget.",
        prerequisites="YYYY-MM month, including future months; nonnegative amount in the book currency. "+if(book)"Book changes obey currency-change checks; a currency reset physically removing budget rows is forbidden."else"A supplied category must be an enabled expense category in this book.",
        description=if(book)"Save the book-info form and a monthly total budget together. Same atomic review as the phone. Amounts carry forward until the next setting; no physical deletion."else"Set/clear a monthly or category budget, carried forward from this month. For category migration set the destination explicitly, clear each source effective setting with amount=null, then archive the source; null historical rows are retained.",
        example="""{"month":"2026-11","amount":{"currency":"JPY","minor":120000}}""")
    override fun validate(input:JsonObject){YearMonth.parse(if(book)commandJson.decodeFromJsonElement<BookBudgetCommand>(input).month else commandJson.decodeFromJsonElement<BudgetSaveCommand>(input).month)}
    override suspend fun execute(step:CommandStep,run:CommandRun):StepEffect {
        val before=run.state();val r=run.ledger
        val month=if(book) {
            val v=commandJson.decodeFromJsonElement<BookBudgetCommand>(step.input)
            val old=before.ledger.book
            require(v.currency==null||v.currency.name==old.currency||old.initialized||before.ledger.budgets.isEmpty()){permanentAlternative("budget.clearAllRows")}
            check(r.commitBudget(r.previewBookBudget(old.copy(name=v.name?:old.name,currency=v.currency?.name?:old.currency,startDate=v.startDate?:old.startDate),v.month,v.amount)))
            v.month
        }else {
            val v=commandJson.decodeFromJsonElement<BudgetSaveCommand>(step.input)
            check(r.commitBudget(r.previewBudget(run.bookId,v.month,v.category?.let(run::resolve),v.amount)));v.month
        }
        return planningEffect(step,run,before,setOf(month))
    }
}

@Serializable internal data class ActivityModeCommand(val enabled:Boolean)
@Serializable internal data class ActivityCurrentCommand(val activity:JsonElement?)
@Serializable internal data class ActivityTargetCommand(val activity:JsonElement)
@Serializable internal data class ActivityLifecycleCommand(val activity:JsonElement,val operation:String)
@Serializable internal data class ActivityBudgetCommand(val category:JsonElement,@SchemaRange(minimum=0,property="minor") val amount:Money)
@Serializable internal data class ActivitySaveCommand(val name:String,@SchemaFormat("date") val start:String,@SchemaFormat("date") val end:String,@SchemaRange(minimum=0,property="minor") val budget:Money?=null,
    val categoryBudgets:List<ActivityBudgetCommand> = emptyList(),val excludeMonthly:Boolean=false,val ended:Boolean=false,val activity:JsonElement?=null)
internal class ActivityAdapter(private val action:String):LedgerAdapter() {
    override val capability=planningCapability("activity.$action",when(action) {
        "setMode"->descriptorSchema(ActivityModeCommand.serializer().descriptor)
        "setCurrent"->patchSchema(descriptorSchema(ActivityCurrentCommand.serializer().descriptor),mapOf("activity" to nullableSchema(referenceSchema("activity"))))
        "save"->patchSchema(descriptorSchema(ActivitySaveCommand.serializer().descriptor),mapOf("activity" to nullableSchema(referenceSchema("activity")),"categoryBudgets" to arraySchema(patchSchema(descriptorSchema(ActivityBudgetCommand.serializer().descriptor),mapOf("category" to referenceSchema("category"))))))
        "lifecycle"->patchSchema(descriptorSchema(ActivityLifecycleCommand.serializer().descriptor),mapOf("activity" to referenceSchema("activity"),"operation" to enumSchema("TRASH","RESTORE")))
        else->patchSchema(descriptorSchema(ActivityTargetCommand.serializer().descriptor),mapOf("activity" to referenceSchema("activity")))
    },if(action=="save")mapOf("activity" to "activity")else emptyMap(),
        when(action){"save"->"Full replacement: name/start/end required; activity omitted/null creates, budget=null, categoryBudgets=[], excludeMonthly=false, ended=false.";"setMode"->"enabled required; disabling clears current activity, enabling preserves a valid current activity.";"setCurrent"->"activity required; null exits current activity without disabling the mode.";"end"->"activity required; preserves other fields/category caps while ending and releasing unused reservations.";else->"activity/operation required; TRASH/RESTORE only. Restore does not restart rules/reservations/templates/drafts."},
        when(action){"save"->"Activity mode on; nonblank name, start<=end, nonnegative book-currency caps, distinct expense categories; existing archived category caps may remain. Ended activity cannot reopen.";"setMode"->"Task book exists; no activity reference required.";"setCurrent"->"Activity mode on; non-null activity must belong to this book and not be ended/deleted.";"end"->"Existing live activity; same ending review and category caps as local editor.";else->"TRASH requires a live activity; RESTORE requires soft-deleted history in this book; no activity expiry policy. Physical deletion forbidden."},
        when(action){"save"->"Save all activity fields and category caps via previewActivity/commitActivity. Ending releases unused reservations and clears current selection; actual records remain. Replacing categoryBudgets edits relationships and supports category migration.";"end"->"End an activity, preserving its fields/category caps; release unused reservations and clear current selection via the same activity confirmation.";"lifecycle"->"TRASH reuses activity deletion review: stops rules, cancels pending tasks, deactivates reservations, clears current activity, converts templates and a matching new-entry draft to daily. RESTORE restores visibility and historical budget attribution only; stopped rules/reservations/templates/draft are not restarted. Existing activity history has no expiry. PURGE/EMPTY forbidden. Exact multi-object recovery is plan undo.";else->"Change activity mode or current recording activity; no ledger entries are created."},
        when(action){"setMode"->"""{"enabled":true}""";"setCurrent"->"""{"activity":"activity-id"}""";"save"->"""{"name":"京都旅行","start":"2026-11-02","end":"2026-11-05","budget":{"currency":"JPY","minor":50000}}""";"end"->"""{"activity":"activity-id"}""";else->"""{"activity":"activity-id","operation":"TRASH"}"""})
    override fun validate(input:JsonObject){when(action){
        "setMode"->commandJson.decodeFromJsonElement<ActivityModeCommand>(input)
        "setCurrent"->commandJson.decodeFromJsonElement<ActivityCurrentCommand>(input)
        "save"->commandJson.decodeFromJsonElement<ActivitySaveCommand>(input)
        "end"->commandJson.decodeFromJsonElement<ActivityTargetCommand>(input)
        else->{val v=commandJson.decodeFromJsonElement<ActivityLifecycleCommand>(input);require(v.operation in listOf("TRASH","RESTORE")){permanentAlternative("activity.purge")}}
    }}
    override suspend fun execute(step:CommandStep,run:CommandRun):StepEffect {
        val before=run.state();val r=run.ledger;val s=before.ledger
        when(action) {
            "setMode"->{val v=commandJson.decodeFromJsonElement<ActivityModeCommand>(step.input);r.setActivityMode(run.bookId,v.enabled,if(v.enabled)s.features?.activeActivityId else null)}
            "setCurrent"->{val v=commandJson.decodeFromJsonElement<ActivityCurrentCommand>(step.input);require(s.features?.activityMode==true);r.setActivityMode(run.bookId,true,v.activity?.let(run::resolve))}
            "save"->{val v=commandJson.decodeFromJsonElement<ActivitySaveCommand>(step.input);val id=v.activity?.let(run::resolve);val budgets=v.categoryBudgets.map{run.resolve(it.category) to it.amount};require(budgets.map{it.first}.distinct().size==budgets.size)
                val review=r.previewActivity(run.bookId,ActivityInput(v.name,v.start,v.end,v.budget,budgets.toMap(),v.excludeMonthly,v.ended,id));check(r.commitActivity(review));run.output(step.id,"activity",review.activity.id,id==null)}
            "end"->{val v=commandJson.decodeFromJsonElement<ActivityTargetCommand>(step.input);val a=s.activities.single{it.id==run.resolve(v.activity)}
                check(r.commitActivity(r.previewActivity(run.bookId,ActivityInput(a.name,a.startDate,a.endDate,a.budgetMinor?.let{Money(Currency.valueOf(s.book.currency),it)},s.activityBudgets.filter{it.activityId==a.id}.associate{it.categoryId to Money(Currency.valueOf(s.book.currency),it.amountMinor)},a.excludeMonthly,true,a.id))))}
            else->{val v=commandJson.decodeFromJsonElement<ActivityLifecycleCommand>(step.input);val id=run.resolve(v.activity);if(v.operation=="TRASH")check(r.commitActivityDeletion(r.previewActivityDeletion(run.bookId,id)))else r.restoreActivity(run.bookId,id)}
        }
        return planningEffect(step,run,before)
    }
}

@Serializable internal data class ReserveSaveCommand(val activity:JsonElement,val name:String,@SchemaFormat("date") val date:String,@SchemaRange(minimum=1,property="minor") val amount:Money,
    val account:JsonElement?=null,@SchemaRange(minimum=1,property="minor",condition="Only when account is supplied; otherwise ignored.") val accountAmount:Money?=null,val occurrence:JsonElement?=null,val reserve:JsonElement?=null)
@Serializable internal data class ReserveReleaseCommand(val reserve:JsonElement,@SchemaRange(minimum=1,property="minor") val amount:Money)
internal class ReserveAdapter(private val release:Boolean=false):LedgerAdapter() {
    override val capability=planningCapability(if(release)"activityReserve.release"else"activityReserve.save",patchSchema(if(release)descriptorSchema(ReserveReleaseCommand.serializer().descriptor)else descriptorSchema(ReserveSaveCommand.serializer().descriptor),buildMap {
        put("reserve",if(release)referenceSchema("reserve")else nullableSchema(referenceSchema("reserve")))
        if(!release){put("activity",referenceSchema("activity"));put("account",nullableSchema(referenceSchema("account")));put("occurrence",nullableSchema(referenceSchema("occurrence")))}
    }),if(release)emptyMap()else mapOf("reserve" to "reserve"),
        if(release)"reserve/amount required; positive book-currency amount; do not assume full release without an amount."else"Full save: reserve omitted/null creates; account/accountAmount/occurrence omitted/null unassigned. Existing released amount preserved.",
        if(release)"Existing usable reservation; positive amount cannot exceed remaining reservation; shared release review. No ledger posting."else"Activity mode on, live unended activity; positive book-currency amount; account/amount paired with matching currency. Optional pending occurrence belongs to activity and is unique to this reserve. New total >= used+released.",
        if(release)"Release the specified positive part/all of the remaining reservation without a ledger posting; show held/available and budget changes."else"Create/edit a reservation without a ledger posting; preserve already released amount and show held/available and budget changes.",
        if(release)"""{"reserve":"reserve-id","amount":{"currency":"JPY","minor":1000}}"""else"""{"activity":"activity-id","name":"旅馆","date":"2026-11-02","amount":{"currency":"JPY","minor":12000}}""")
    override fun validate(input:JsonObject){if(release)commandJson.decodeFromJsonElement<ReserveReleaseCommand>(input)else commandJson.decodeFromJsonElement<ReserveSaveCommand>(input)}
    override suspend fun execute(step:CommandStep,run:CommandRun):StepEffect {
        val before=run.state();val r=run.ledger
        if(release){val v=commandJson.decodeFromJsonElement<ReserveReleaseCommand>(step.input);check(r.commitReserve(r.previewRelease(run.bookId,run.resolve(v.reserve),v.amount)))}
        else {val v=commandJson.decodeFromJsonElement<ReserveSaveCommand>(step.input);val id=v.reserve?.let(run::resolve)
            val review=r.previewReserve(run.bookId,ReserveInput(run.resolve(v.activity),v.name,v.date,v.amount,v.account?.let(run::resolve),v.accountAmount,v.occurrence?.let(run::resolve),id));check(r.commitReserve(review));run.output(step.id,"reserve",review.reserve.id,id==null)}
        return planningEffect(step,run,before)
    }
}

@Serializable internal data class ParticipantCommand(val activity:JsonElement,val friend:JsonElement)
internal class ParticipantAdapter:LedgerAdapter() {
    override val capability=planningCapability("activityParticipant.add",patchSchema(descriptorSchema(ParticipantCommand.serializer().descriptor),mapOf("activity" to referenceSchema("activity"),"friend" to referenceSchema("friend"))),
        defaults="Both references required; adding an existing participant is unchanged.",prerequisites="Live activity and active friend in this book; existing participant ordering preserved.",description="Add an activity participant using addParticipant; create a friend first with friend.save when necessary. There is no participant-removal UI; physical removal is forbidden.",example="""{"activity":"activity-id","friend":"friend-id"}""")
    override fun validate(input:JsonObject){commandJson.decodeFromJsonElement<ParticipantCommand>(input)}
    override suspend fun execute(step:CommandStep,run:CommandRun):StepEffect {val before=run.state();val v=commandJson.decodeFromJsonElement<ParticipantCommand>(step.input);run.ledger.addParticipant(run.bookId,run.resolve(v.activity),run.resolve(v.friend));return planningEffect(step,run,before)}
}

@Serializable internal data class TreatSaveCommand(val activity:JsonElement,val payer:JsonElement,@SchemaRange(minimum=1,property="minor") val total:Money,@SchemaRange(minimum=1,property="minor") val base:Money,@SchemaFormat("date") val date:String,val name:String,
    val shares:List<EntryShare>,val mode:TreatSplitMode=TreatSplitMode.EQUAL,val tail:JsonElement?=null,val treat:JsonElement?=null)
@Serializable internal data class TreatLifecycleCommand(val treat:JsonElement,val operation:String)
internal class TreatAdapter(private val lifecycle:Boolean=false):LedgerAdapter() {
    override val capability=planningCapability(if(lifecycle)"activityTreat.lifecycle"else"activityTreat.save",if(lifecycle)patchSchema(descriptorSchema(TreatLifecycleCommand.serializer().descriptor),mapOf("treat" to referenceSchema("treat"),"operation" to enumSchema("TRASH","RESTORE")))else patchSchema(descriptorSchema(TreatSaveCommand.serializer().descriptor),mapOf(
        "activity" to referenceSchema("activity"),"payer" to referenceSchema("friend"),"treat" to nullableSchema(referenceSchema("treat")),"tail" to nullableSchema(referenceSchema("friend")),
        "shares" to arraySchema(patchSchema(descriptorSchema(EntryShare.serializer().descriptor),mapOf("friend" to nullableSchema(referenceSchema("friend"))))))),if(lifecycle)emptyMap()else mapOf("treat" to "treat"),
        if(lifecycle)"treat/operation required; TRASH/RESTORE only; original allocations retained."else"Full replacement; treat omitted/null creates; mode=EQUAL. shares identify friends, null friend is ME; PERCENT includes ME and sums decimal percents to 100; AMOUNTS sums minor to total. tail omitted uses the final participant, tail:null selects ME. base required.",
        if(lifecycle)"Existing other-paid treat in this book; TRASH requires live record, RESTORE requires soft-deleted record. Own-paid treats use entry.lifecycle."else"Activity mode enabled; valid activity/date, positive total and book-currency base; selected friends belong to activity, payer among selected, ME share positive. Ended activity allows facts only within its dates. Existing other-paid treat must stay in the same activity.",
        if(lifecycle)"Trash/restore an other-paid treat while preserving its allocations; no money posting or permanent deletion."else"Record/edit an other-paid treat and its complete split using the phone apportioning rules. No money posting; own-paid treats use entry.update instead.",
        if(lifecycle)"""{"treat":"treat-id","operation":"TRASH"}"""else"""{"activity":"activity-id","payer":"friend-id","name":"晚餐","date":"2026-11-02","total":{"currency":"JPY","minor":6000},"base":{"currency":"JPY","minor":6000},"shares":[{"friend":null},{"friend":"friend-id"}]}""")
    override fun validate(input:JsonObject){if(lifecycle){val v=commandJson.decodeFromJsonElement<TreatLifecycleCommand>(input);require(v.operation in listOf("TRASH","RESTORE")){permanentAlternative("activityTreat.purge")}}else {val v=commandJson.decodeFromJsonElement<TreatSaveCommand>(input);require(v.shares.isNotEmpty())}}
    override suspend fun execute(step:CommandStep,run:CommandRun):StepEffect {
        val before=run.state();val r=run.ledger
        if(lifecycle){val v=commandJson.decodeFromJsonElement<TreatLifecycleCommand>(step.input);if(v.operation=="TRASH")r.removeOtherTreat(run.bookId,run.resolve(v.treat))else r.restoreOtherTreat(run.bookId,run.resolve(v.treat))}
        else {
            val v=commandJson.decodeFromJsonElement<TreatSaveCommand>(step.input);val id=v.treat?.let(run::resolve)
            val keys=v.shares.map{it.friend?.let(run::resolve)?:"ME"};require(keys.distinct().size==keys.size)
            require(v.mode==TreatSplitMode.EQUAL||"ME" in keys)
            val weights=buildMap<String,String>{v.shares.zip(keys).forEach{(share,key)->when(v.mode){TreatSplitMode.PERCENT->put(key,requireNotNull(share.percent));TreatSplitMode.AMOUNTS->put(key,Money(v.total.currency,requireNotNull(share.minor)).decimal.toPlainString());else->Unit}};if("tail" in step.input)put("__tail",v.tail?.let(run::resolve)?:"ME")}
            val review=r.previewOtherTreat(run.bookId,OtherTreatInput(run.resolve(v.activity),run.resolve(v.payer),v.total,v.base,v.date,v.name,keys.filter{it!="ME"},v.mode,weights,id))
            check(r.commitOtherTreat(review));run.output(step.id,"treat",review.change.treat.id,id==null)
        }
        return planningEffect(step,run,before)
    }
}
