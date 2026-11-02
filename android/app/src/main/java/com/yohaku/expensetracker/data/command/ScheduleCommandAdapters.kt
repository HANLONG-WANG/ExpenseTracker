package com.yohaku.expensetracker.data.command

import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.LocalDate

@Serializable internal data class RecurrenceSaveCommand(val name:String,val account:JsonElement,val category:JsonElement,
    val frequency:RecurrenceFrequency,val anchorDate:String,val amount:Money?=null,val baseAmount:Money?=null,val estimated:Boolean=true,
    val endDate:String?=null,val active:Boolean=true,val activity:JsonElement?=null,val merchant:String="",val merchantId:JsonElement?=null,
    val note:String="",val cardPaymentChoice:String?=null,val cardPaymentPeriods:Int?=null,val recurrence:JsonElement?=null)
@Serializable internal data class RecurrenceActiveCommand(val recurrence:JsonElement,val active:Boolean)
internal class RecurrenceAdapter(private val active:Boolean=false):LedgerAdapter() {
    override val capability=planningCapability(if(active)"recurrence.setActive"else"recurrence.save",patchSchema(if(active)descriptorSchema(RecurrenceActiveCommand.serializer().descriptor)else descriptorSchema(RecurrenceSaveCommand.serializer().descriptor),buildMap {
        put("recurrence",if(active)referenceSchema("recurrence")else nullableSchema(referenceSchema("recurrence")))
        if(!active){put("account",referenceSchema("account"));put("category",referenceSchema("category"));put("activity",nullableSchema(referenceSchema("activity")));put("merchantId",nullableSchema(referenceSchema("merchant")))}
    }),if(active)emptyMap()else mapOf("recurrence" to "recurrence"),
        "save fully replaces editable fields; omitted recurrence creates, amount/baseAmount=null unknown, estimated=true, active=true, endDate/activity/merchantId/payment options=null, merchant/note empty. Same-currency base derives from amount; foreign base stays unknown unless provided. setActive requires both fields and preserves all other values.",
        "Same saveRecurrence validation: expense category and non-loan account (existing archived selections may remain); positive account-currency amount if known, valid optional book-currency base, frequency WEEKLY/MONTHLY/YEARLY, end>=anchor; activity mode/live activity and fixed credit-card payment choices as phone. No guessed dates or amounts.",
        "Save or enable/disable recurring rule; refresh pending/withdrawn instances in existing months and current month, preserving completed/cancelled history. Use occurrence.update to migrate those historical category references too. Rules generate tasks only, never postings; physical deletion forbidden.",
        if(active)"""{"recurrence":"recurrence-id","active":false}"""else"""{"name":"房租","account":"account-id","category":"category-id","frequency":"MONTHLY","anchorDate":"2026-11-01","amount":{"currency":"JPY","minor":80000},"estimated":false}""")
    override fun validate(input:JsonObject){if(active)commandJson.decodeFromJsonElement<RecurrenceActiveCommand>(input)else commandJson.decodeFromJsonElement<RecurrenceSaveCommand>(input)}
    override suspend fun execute(step:CommandStep,run:CommandRun):StepEffect {
        val before=run.state();val r=run.ledger
        if(active){val v=commandJson.decodeFromJsonElement<RecurrenceActiveCommand>(step.input);r.setRecurrenceActive(run.bookId,run.resolve(v.recurrence),v.active)}
        else {val v=commandJson.decodeFromJsonElement<RecurrenceSaveCommand>(step.input);val old=v.recurrence?.let(run::resolve)
            val id=r.saveRecurrence(run.bookId,RecurrenceInput(v.name,run.resolve(v.account),run.resolve(v.category),v.amount,v.baseAmount,v.estimated,v.frequency,v.anchorDate,v.endDate,old,v.active,v.activity?.let(run::resolve),v.merchant,v.merchantId?.let(run::resolve),v.note,v.cardPaymentChoice,v.cardPaymentPeriods))
            run.output(step.id,"recurrence",id,old==null)}
        return planningEffect(step,run,before)
    }
}

@Serializable internal data class OccurrenceResolveCommand(val recurrence:JsonElement,val date:String)
@Serializable internal data class OccurrenceTargetCommand(val occurrence:JsonElement)
@Serializable internal data class OccurrenceLifecycleCommand(val occurrence:JsonElement,val operation:String)
@Serializable internal data class OccurrenceCategoryCommand(val occurrences:List<JsonElement>,val category:JsonElement)
@Serializable internal data class OccurrenceLinkCommand(val occurrence:JsonElement,val entry:JsonElement)
@Serializable internal data class OccurrenceLinksCommand(val links:List<OccurrenceLinkCommand>)
@Serializable internal data class OccurrencePayCommand(val occurrence:JsonElement,val set:JsonObject=buildJsonObject{})
internal class OccurrenceAdapter(private val action:String):LedgerAdapter() {
    override val capability=planningCapability("occurrence.$action",when(action) {
        "resolve"->patchSchema(descriptorSchema(OccurrenceResolveCommand.serializer().descriptor),mapOf("recurrence" to referenceSchema("recurrence")))
        "update"->patchSchema(descriptorSchema(OccurrenceCategoryCommand.serializer().descriptor),mapOf("occurrences" to arraySchema(referenceSchema("occurrence")),"category" to referenceSchema("category")))
        "link"->patchSchema(descriptorSchema(OccurrenceLinksCommand.serializer().descriptor),mapOf("links" to arraySchema(patchSchema(descriptorSchema(OccurrenceLinkCommand.serializer().descriptor),mapOf("occurrence" to referenceSchema("occurrence"),"entry" to referenceSchema("entry"))))))
        "pay"->patchSchema(descriptorSchema(OccurrencePayCommand.serializer().descriptor),mapOf("occurrence" to referenceSchema("occurrence"),"set" to entryValueSchema))
        "lifecycle"->patchSchema(descriptorSchema(OccurrenceLifecycleCommand.serializer().descriptor),mapOf("occurrence" to referenceSchema("occurrence")))
        else->patchSchema(descriptorSchema(OccurrenceTargetCommand.serializer().descriptor),mapOf("occurrence" to referenceSchema("occurrence")))
    },when(action){"resolve"->mapOf("occurrence" to "occurrence");"pay"->mapOf("entry" to "entry");else->emptyMap()},
        "resolve requires rule+scheduled date and outputs the exact occurrence (materializes that rule's month if necessary). update requires explicit 1..5000 occurrence references and category. link requires 1..5000 occurrence/entry pairs; no selector expansion. pay resumes the matching stored payment draft, including its local attachments, otherwise starts with the phone's occurrenceDraft; omitted set preserves its defaults, optional set uses entry.create fields but cannot change kind/occurrence. Unknown amount/base still fails until explicitly supplied. lifecycle CANCEL/RESTORE; unlink requires occurrence.",
        "Same occurrence domain rules: link/pay only pending or already linked to the same entry, live expense, no expense completed by another occurrence. Restore only cancelled with active rule still including that date. update allows completed/cancelled history but only an enabled expense category. Payment must be a complete EXPENSE draft with positive actual amount and required currency/credit-payment details.",
        when(action){"resolve"->"Find/materialize the scheduled occurrence by rule/date; permits referencing a new rule without guessing a generated ID. Date must be included by the active rule, or already exist in its history.";"update"->"Batch update historical/pending occurrence category references; completed payment records are separately updated through entry.update. One preparation, no per-row full-ledger reads.";"link"->"Batch link existing expenses using the shared occurrence-link preparation. No duplicate posting; consumes only a matching payment draft and records its exact row/media associations for undo.";"pay"->"Record a recurring payment using the shared occurrencePaymentDraft, entry patch preparation and commitOccurrencePayment/commitEntryReview; consume only the matching payment draft. Existing negative-balance confirmation remains RISK. No duplicate payment, no unknown-as-zero.";"unlink"->"Detach a payment record; retain the record and restore PENDING/WITHDRAWN according to the rule's current schedule.";else->"Cancel/restore an instance without deleting its row or linked financial facts. Restore preserves an existing live payment or refreshes the pending fields from its rule."},
        when(action){"resolve"->"""{"recurrence":"recurrence-id","date":"2026-11-01"}""";"update"->"""{"occurrences":["occurrence-id"],"category":"category-id"}""";"link"->"""{"links":[{"occurrence":"occurrence-id","entry":"entry-id"}]}""";"pay"->"""{"occurrence":"occurrence-id","set":{"amount":{"currency":"JPY","minor":80000}}}""";"unlink"->"""{"occurrence":"occurrence-id"}""";else->"""{"occurrence":"occurrence-id","operation":"CANCEL"}"""})
    override fun validate(input:JsonObject){when(action) {
        "resolve"->LocalDate.parse(commandJson.decodeFromJsonElement<OccurrenceResolveCommand>(input).date)
        "update"->{val v=commandJson.decodeFromJsonElement<OccurrenceCategoryCommand>(input);require(v.occurrences.size in 1..5000&&v.occurrences.distinct().size==v.occurrences.size)}
        "link"->{val v=commandJson.decodeFromJsonElement<OccurrenceLinksCommand>(input);require(v.links.size in 1..5000&&v.links.map{it.occurrence}.distinct().size==v.links.size)}
        "pay"->{val v=commandJson.decodeFromJsonElement<OccurrencePayCommand>(input);if(v.set.isNotEmpty())validateEntryValues(v.set);require(v.set.keys.none{it in setOf("kind","occurrence","originalEvent")})}
        "lifecycle"->{val v=commandJson.decodeFromJsonElement<OccurrenceLifecycleCommand>(input);require(v.operation in listOf("CANCEL","RESTORE")){permanentAlternative("occurrence.delete")}}
        else->commandJson.decodeFromJsonElement<OccurrenceTargetCommand>(input)
    }}
    override suspend fun execute(step:CommandStep,run:CommandRun):StepEffect {
        val before=run.state();val r=run.ledger;val s=before.ledger
        when(action) {
            "resolve"->{val v=commandJson.decodeFromJsonElement<OccurrenceResolveCommand>(step.input);val id=run.resolve(v.recurrence);val rule=s.recurrences.single{it.id==id}
                val old=s.occurrences.firstOrNull{it.recurrenceId==id&&it.scheduledDate==v.date}
                require(old!=null||rule.includesScheduledDate(v.date)){"该日期不在周期安排内 / この日は定期予定の範囲外です"}
                if(old==null)r.ensureOccurrences(run.bookId,v.date.take(7),id)
                val occurrence=old?:r.dao.occurrences(run.bookId).single{it.recurrenceId==id&&it.scheduledDate==v.date}
                run.output(step.id,"occurrence",occurrence.id,old==null)}
            "update"->{val v=commandJson.decodeFromJsonElement<OccurrenceCategoryCommand>(step.input);r.updateOccurrenceCategories(run.bookId,v.occurrences.map(run::resolve),run.resolve(v.category))}
            "link"->{val v=commandJson.decodeFromJsonElement<OccurrenceLinksCommand>(step.input);r.linkOccurrenceBatch(run.bookId,v.links.map{run.resolve(it.occurrence) to run.resolve(it.entry)})}
            "pay"->{val v=commandJson.decodeFromJsonElement<OccurrencePayCommand>(step.input);val o=s.occurrences.single{it.id==run.resolve(v.occurrence)}
                require(o.status=="PENDING"&&s.visibleEvents.none{it.id==o.eventId&&it.kind==EntryKind.EXPENSE.name}){"周期实例已完成或取消 / 定期予定は完了・取消済みです"}
                var base=s.occurrencePaymentDraft(o,resumeStored=true)
                require(base.occurrenceLinkEventId==null){"支付草稿已选择关联已有记录，请改用 occurrence.link / 支払下書きは既存記録を選択済みです。occurrence.link を使ってください"}
                val amount=commandJson.decodeFromJsonElement<EntryValues>(v.set).amount
                if(amount!=null&&"baseAmount" !in v.set&&s.accounts.single{it.id==base.accountId}.currency!=s.book.currency)
                    base=base.withOccurrenceAmount(amount.decimal.toPlainString(),true)
                val draft=entryDraft(v.set,run,s,base)
                val id=requireNotNull(r.commitOccurrencePayment(run.bookId,r.previewEntry(run.bookId,draft),discardPaymentDraft=true))
                run.output(step.id,"entry",id,true)}
            "unlink"->{val v=commandJson.decodeFromJsonElement<OccurrenceTargetCommand>(step.input);r.unlinkOccurrence(run.bookId,run.resolve(v.occurrence))}
            else->{val v=commandJson.decodeFromJsonElement<OccurrenceLifecycleCommand>(step.input);if(v.operation=="CANCEL")r.cancelOccurrence(run.bookId,run.resolve(v.occurrence))else r.restoreOccurrence(run.bookId,run.resolve(v.occurrence))}
        }
        return planningEffect(step,run,before)
    }
}

@Serializable internal data class ExpectedSaveCommand(val name:String,val amount:Money,val date:String,val account:JsonElement,
    val accountAmount:Money?=null,val income:JsonElement?=null,val useReferenceRates:Boolean=false)
@Serializable internal data class ExpectedTargetCommand(val income:JsonElement)
@Serializable internal data class ExpectedArrivalCommand(val income:JsonElement,val entry:JsonElement?=null,val set:JsonObject?=null)
internal class ExpectedIncomeAdapter(private val action:String):LedgerAdapter() {
    override val capability=planningCapability("expectedIncome.$action",when(action){
        "save"->patchSchema(descriptorSchema(ExpectedSaveCommand.serializer().descriptor),mapOf("account" to referenceSchema("account"),"income" to nullableSchema(referenceSchema("expectedIncome"))))
        "arrive"->patchSchema(descriptorSchema(ExpectedArrivalCommand.serializer().descriptor),mapOf("income" to referenceSchema("expectedIncome"),"entry" to nullableSchema(referenceSchema("entry")),"set" to nullableSchema(entryValueSchema)))
        else->patchSchema(descriptorSchema(ExpectedTargetCommand.serializer().descriptor),mapOf("income" to referenceSchema("expectedIncome")))
    },when(action){"save"->mapOf("expectedIncome" to "expectedIncome");"arrive"->mapOf("entry" to "entry");else->emptyMap()},
        "save fully replaces editable fields; income omitted creates; accountAmount omitted/null stays unknown for foreign currency, same currency derives from amount; useReferenceRates=false (true uses frozen local quote only when accountAmount missing). arrive requires exactly one of existing entry or set for a new receipt; new receipt defaults INCOME, planned account, plan name as note, asOfDate as date, confirmed settlement; actual amount/category must be supplied, never inferred from expectation. cancel requires income.",
        "Same expected-income reviews: active cash/bank/IC account, positive planned amount, valid date/currency, received plans cannot be edited/cancelled. Arrival must be live confirmed INCOME in planned account, not historical opening, record and settlement dates <=today, no other expected item already linked. Cancelled plans cannot arrive.",
        "Save/cancel an expectation without changing actual balances; arrive creates an income through the normal entry transaction or links an existing receipt without double counting. Original expected values survive arrival. Existing financial editor drafts remain for stages 5–6.",
        when(action){"save"->"""{"name":"工资","amount":{"currency":"JPY","minor":250000},"date":"2026-11-02","account":"account-id"}""";"arrive"->"""{"income":"income-id","entry":"entry-id"}""";else->"""{"income":"income-id"}"""})
    override fun validate(input:JsonObject){when(action){"save"->commandJson.decodeFromJsonElement<ExpectedSaveCommand>(input);"cancel"->commandJson.decodeFromJsonElement<ExpectedTargetCommand>(input);else->{val v=commandJson.decodeFromJsonElement<ExpectedArrivalCommand>(input);require((v.entry==null)!=(v.set==null));v.set?.let{validateEntryValues(it);require(it.keys.none{key->key in setOf("kind","account","historicalIncluded","settlementConfirmed")})}}}}
    override suspend fun execute(step:CommandStep,run:CommandRun):StepEffect {
        val before=run.state();val r=run.ledger;val s=before.ledger
        when(action) {
            "save"->{val v=commandJson.decodeFromJsonElement<ExpectedSaveCommand>(step.input);val id=v.income?.let(run::resolve);val account=s.accounts.single{it.id==run.resolve(v.account)}
                val quote=if(v.useReferenceRates&&v.accountAmount==null&&account.currency!=v.amount.currency.name)run.context.referenceRates.firstOrNull{it.base==v.amount.currency&&it.quote.name==account.currency}?:error("缺少参考汇率，请提供预计到账金额 / 参考レートがありません。入金見込額を入力してください")else null
                val review=r.previewExpectedIncome(run.bookId,ExpectedIncomeInput(v.name,v.amount,v.date,account.id,v.accountAmount?:quote?.convert(v.amount),id,quote))
                run.output(step.id,"expectedIncome",requireNotNull(r.commitExpectedIncome(review)),id==null)}
            "cancel"->{val v=commandJson.decodeFromJsonElement<ExpectedTargetCommand>(step.input);r.cancelExpectedIncome(run.bookId,run.resolve(v.income))}
            else->{val v=commandJson.decodeFromJsonElement<ExpectedArrivalCommand>(step.input);val id=run.resolve(v.income);val income=s.expectedIncomes.single{it.id==id}
                val draft=v.set?.let{values->
                    val base=s.expectedIncomeReceiptDraft(income,run.context.asOfDate)
                    require("amount" in values&&"category" in values){"请提供实际到账金额和收入分类 / 実入金額と収入カテゴリを指定してください"}
                    entryDraft(values,run,s,base)
                }
                val review=r.previewExpectedIncomeArrival(run.bookId,id,draft,v.entry?.let(run::resolve))
                run.output(step.id,"entry",requireNotNull(r.commitExpectedIncomeArrival(review)),draft!=null)}
        }
        return planningEffect(step,run,before)
    }
}
