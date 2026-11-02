package com.yohaku.expensetracker.data.command

import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.ledger.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Patch fields only: missing preserves, null clears only an explicitly nullable association/option.
 * Money is always minor units plus currency; editor decimal strings never cross the protocol. */
@Serializable internal data class EntryValues(
    val useReferenceRates:Boolean=false,val kind:EntryKind?=null,val account:JsonElement?=null,val category:JsonElement?=null,
    val amount:Money?=null,val accountAmount:Money?=null,val baseAmount:Money?=null,val date:String?=null,
    val merchant:String?=null,val merchantId:JsonElement?=null,val note:String?=null,val historicalIncluded:Boolean?=null,
    val settlementConfirmed:Boolean?=null,val settlementDate:String?=null,val rateSource:String?=null,val rateDate:String?=null,val manualRate:String?=null,
    val originalEvent:JsonElement?=null,val toAccount:JsonElement?=null,val toAmount:Money?=null,val toDate:String?=null,
    val fromConfirmed:Boolean?=null,val toConfirmed:Boolean?=null,val feeAmount:Money?=null,val feeAccountAmount:Money?=null,val feeBaseAmount:Money?=null,
    val feePosition:TransferFeePosition?=null,val feeAccount:JsonElement?=null,val feeDate:String?=null,val feeConfirmed:Boolean?=null,val estimateDirection:String?=null,
    val activity:JsonElement?=null,val occurrence:JsonElement?=null,val reserve:JsonElement?=null,val reserveAmount:Money?=null,
    val treatEnabled:Boolean?=null,val treatMode:TreatSplitMode?=null,val treatShares:List<EntryShare>?=null,val treatTail:JsonElement?=null,
    val cardPaymentChoice:String?=null,val cardPaymentPeriods:Int?=null,val cardPaymentRecheck:Boolean?=null,val cardPaymentMonth:String?=null,val cardPaymentFee:Money?=null)
@Serializable internal data class EntryShare(val friend:JsonElement?=null,val minor:Long?=null,val percent:String?=null)
@Serializable internal data class EntryWriteTargets(val ids:List<JsonElement>?=null,val select:EntrySelector?=null)
@Serializable internal data class EntryEditInput(val targets:EntryWriteTargets,val set:JsonObject)
@Serializable internal data class EntryLifecycleInput(val entry:JsonElement,val operation:EntryLifecycleOperation)
@Serializable internal data class TemplateEntryInput(val template:JsonElement,val set:JsonObject=buildJsonObject{})

internal val entryValueSchema:JsonObject get()=patchSchema(descriptorSchema(EntryValues.serializer().descriptor),buildMap {
    for((field,type) in mapOf("account" to "account","category" to "category","merchantId" to "merchant","originalEvent" to "entry","toAccount" to "account","feeAccount" to "account","activity" to "activity","occurrence" to "occurrence","reserve" to "reserve","treatTail" to "friend"))put(field,nullableSchema(referenceSchema(type)))
    put("treatShares",nullableSchema(buildJsonObject{put("type","array");put("items",patchSchema(descriptorSchema(EntryShare.serializer().descriptor),mapOf("friend" to nullableSchema(referenceSchema("friend")))))}))
})
internal fun validateEntryValues(input:JsonObject) {
    val v=commandJson.decodeFromJsonElement<EntryValues>(input)
    require(input.isNotEmpty()){"请指定记录字段 / 記録の項目を指定してください"}
    val nullable=setOf("merchantId","activity","occurrence","reserve","reserveAmount","feeAmount","feeAccountAmount","feeBaseAmount","feeAccount","cardPaymentChoice","cardPaymentPeriods","cardPaymentMonth","cardPaymentFee","treatShares","treatTail")
    require(input.none{it.value==JsonNull&&it.key !in nullable}){"该字段不能为 null / この項目は null にできません"}
    require(v.kind==null||v.kind in listOf(EntryKind.EXPENSE,EntryKind.INCOME,EntryKind.TRANSFER,EntryKind.REFUND))
    v.manualRate?.takeIf{it.isNotEmpty()}?.let{require(it.toBigDecimal().signum()>0)}
    require(v.estimateDirection==null||v.estimateDirection in listOf("TO","FROM"))
}

internal suspend fun entryDraft(input:JsonObject,run:CommandRun,s:BookSnapshot,old:EntryDraft?=null):EntryDraft {
    val v=commandJson.decodeFromJsonElement<EntryValues>(input)
    val kind=v.kind?:old?.kind?:EntryKind.EXPENSE
    require(old?.id==null||kind==old.kind){"已有记录种类不可直接改写 / 既存の記録の種類は直接変更できません"}
    val transferFields=setOf("toAccount","toAmount","toDate","fromConfirmed","toConfirmed","feeAmount","feeAccountAmount","feeBaseAmount","feePosition","feeAccount","feeDate","feeConfirmed","estimateDirection")
    val ordinaryFields=setOf("category","historicalIncluded","activity","occurrence","reserve","reserveAmount","treatEnabled","treatMode","treatShares","treatTail")
    val incompatible=when(kind) {
        EntryKind.TRANSFER->ordinaryFields+setOf("originalEvent","accountAmount","baseAmount","settlementConfirmed","settlementDate")
        EntryKind.REFUND->transferFields+ordinaryFields+setOf("merchant","merchantId","cardPaymentChoice","cardPaymentPeriods","cardPaymentRecheck","cardPaymentMonth","cardPaymentFee")
        else->transferFields+setOf("originalEvent")
    }
    require(input.keys.none{it in incompatible}){"包含不适用于此记录种类的字段 / この記録の種類に適用できない項目があります"}
    val date=v.date?:old?.date?:run.context.asOfDate
    val original=v.originalEvent?.let(run::resolve)?:old?.originalEventId
    var d=old?:if(kind==EntryKind.REFUND)s.refundDraft(s.visibleEvents.single{it.id==original},date)else s.newEntryDraft(kind,date)
    if(old==null&&kind==EntryKind.REFUND&&v.amount!=null) {
        val source=s.events.single{it.id==original}
        val st=s.settlements.single{it.eventId==source.id}
        fun proportional(value:Long)=value.toBigDecimal().multiply(v.amount.minor.toBigDecimal()).divide(source.amountMinor.toBigDecimal(),0,java.math.RoundingMode.HALF_UP).longValueExact()
        d=d.copy(accountAmount=Money(Currency.valueOf(st.accountCurrency),proportional(st.accountMinor)).decimal.toPlainString(),baseAmount=Money(Currency.valueOf(s.book.currency),proportional(source.baseMinor)).decimal.toPlainString())
    }
    val fields=encoded(d).jsonObject.toMutableMap()
    fun put(name:String,value:JsonElement){fields[name]=value}
    val refs=mapOf("account" to "accountId","category" to "categoryId","merchantId" to "merchantId","originalEvent" to "originalEventId","toAccount" to "toAccountId","feeAccount" to "feeAccountId","activity" to "activityId","occurrence" to "occurrenceId","reserve" to "reserveId")
    refs.forEach{(key,dest)->if(key in input)put(dest,input[key]?.takeUnless{it==JsonNull}?.let{JsonPrimitive(run.resolve(it))}?:JsonNull)}
    val moneyFields=setOf("amount","accountAmount","baseAmount","toAmount","feeAmount","feeAccountAmount","feeBaseAmount","reserveAmount","cardPaymentFee")
    val special=refs.keys+moneyFields+setOf("treatShares","treatTail","cardPaymentPeriods","useReferenceRates")
    input.filterKeys{it !in special}.forEach{(key,value)->put(key,if(value==JsonNull&&key=="cardPaymentMonth")JsonPrimitive("")else value)}
    if("date" in input&&d.id==null)for(key in listOf("settlementDate","toDate","feeDate","rateDate"))if(key !in input)put(key,JsonPrimitive(date))
    if("cardPaymentPeriods" in input)put("cardPaymentPeriods",JsonPrimitive(v.cardPaymentPeriods?.toString().orEmpty()))
    val account=s.accounts.singleOrNull{it.id==fields["accountId"]?.jsonPrimitive?.contentOrNull}?:error("请选择本账本的账户 / この帳簿の口座を選択してください")
    val accountCurrency=Currency.valueOf(account.currency);val bookCurrency=Currency.valueOf(s.book.currency)
    val targetCurrency=fields["toAccountId"]?.jsonPrimitive?.contentOrNull?.let{id->s.accounts.single{it.id==id}.currency}?.let(Currency::valueOf)
    val feeCurrency=when(v.feePosition?:d.feePosition){TransferFeePosition.INCLUDED_FROM->accountCurrency;TransferFeePosition.DEDUCTED_TO->targetCurrency;TransferFeePosition.SEPARATE->fields["feeAccountId"]?.jsonPrimitive?.contentOrNull?.let{id->Currency.valueOf(s.accounts.single{it.id==id}.currency)}}
    for(key in moneyFields)if(key in input) {
        val money=input[key]?.takeUnless{it==JsonNull}?.let{commandJson.decodeFromJsonElement<Money>(it)}
        if(money!=null) {
            val expected=when(key){"amount"->if(kind==EntryKind.TRANSFER)accountCurrency else if(kind==EntryKind.REFUND)Currency.valueOf(s.events.single{it.id==original}.currency)else null;"accountAmount","cardPaymentFee"->accountCurrency;"baseAmount","feeBaseAmount","reserveAmount"->bookCurrency;"toAmount"->targetCurrency;"feeAccountAmount"->feeCurrency;else->null}
            require(expected==null||money.currency==expected){"金额币种与账户或账本不符 / 金額の通貨が口座・帳簿と一致しません"}
            if(key=="amount")put("originalCurrency",JsonPrimitive(money.currency.name))
            if(key=="feeAmount")put("feeCurrency",JsonPrimitive(money.currency.name))
        }
        put(key,JsonPrimitive(money?.decimal?.toPlainString().orEmpty()))
    }
    if("treatShares" in input) {
        val shares=v.treatShares.orEmpty();val friends=shares.mapNotNull{it.friend?.let(run::resolve)}
        require(friends.distinct().size==friends.size)
        put("treatFriends",encoded(friends))
        val weights=shares.associate{share->(share.friend?.let(run::resolve)?:"ME") to when(v.treatMode?:d.treatMode){TreatSplitMode.EQUAL->"1";TreatSplitMode.AMOUNTS->Money(v.amount?.currency?:d.originalCurrency?:accountCurrency,requireNotNull(share.minor)).decimal.toPlainString();TreatSplitMode.PERCENT->requireNotNull(share.percent)}}.toMutableMap()
        v.treatTail?.let{weights["__tail"]=run.resolve(it)};put("treatWeights",encoded(weights))
    }
    d=commandJson.decodeFromJsonElement(JsonObject(fields))
    if(kind==EntryKind.REFUND&&"settlementDate" in input) {
        require(v.date==null||v.date==v.settlementDate){"退款日期与入账日期须一致 / 返金日と計上日は同じ日付にしてください"}
        d=d.copy(date=requireNotNull(v.settlementDate))
    }
    if(old?.accountId!=null&&s.accounts.single{it.id==old.accountId}.currency!=account.currency) {
        if("accountAmount" !in input)d=d.copy(accountAmount="")
        if("baseAmount" !in input)d=d.copy(baseAmount="")
    }
    if(old?.toAccountId!=null&&d.toAccountId!=old.toAccountId&&s.accounts.single{it.id==old.toAccountId}.currency!=targetCurrency?.name&&"toAmount" !in input)d=d.copy(toAmount="")
    if(old==null||old.accountId!=d.accountId)d=s.withRememberedCardPayment(d).let{remembered->if("cardPaymentChoice" in input)d else remembered}
    if("cardPaymentPeriods" in input)d=d.copy(cardPaymentPeriods=v.cardPaymentPeriods?.toString().orEmpty())
    if("merchantId" in input&&v.merchantId!=null&&"merchant" !in input)d=d.copy(merchant=s.merchants.single{it.id==run.resolve(v.merchantId)}.name)
    if("merchant" in input&&"merchantId" !in input)d=s.merchantInput(d,requireNotNull(v.merchant))
    // Same-currency amounts are exact. Foreign amounts require explicit settlement/base or an
    // explicit manual conversion; no assumed public rate and no inferred actual confirmation.
    val originalCurrency=d.originalCurrency?:accountCurrency
    if(d.id==null&&"settlementConfirmed" !in input&&originalCurrency!=accountCurrency)d=d.copy(settlementConfirmed=false)
    if(kind in listOf(EntryKind.EXPENSE,EntryKind.INCOME,EntryKind.REFUND)) {
        if(originalCurrency==accountCurrency)d=d.copy(accountAmount=d.amount)
        else if(d.accountAmount.isBlank()&&d.manualRate.isNotBlank())d=d.copy(accountAmount=Money.parse(d.amount,originalCurrency).decimal.multiply(d.manualRate.toBigDecimal()).setScale(accountCurrency.scale,java.math.RoundingMode.HALF_UP).toPlainString())
        if(accountCurrency==bookCurrency)d=d.copy(baseAmount=d.accountAmount)
    }else if(!v.useReferenceRates&&kind==EntryKind.TRANSFER&&((d.estimateDirection=="TO"&&d.toAmount.isBlank())||(d.estimateDirection=="FROM"&&d.amount.isBlank())))d=estimateTransfer(d,accountCurrency,requireNotNull(targetCurrency),d.estimateDirection)
    if(v.useReferenceRates) {
        fun rate(from:Currency,to:Currency)=run.context.referenceRates.firstOrNull{it.base==from&&it.quote==to}?:error("缺少指定币种的参考汇率，请补充明确金额 / 対象通貨の参考レートがありません。金額を入力してください")
        if(kind==EntryKind.TRANSFER) {
            if((d.estimateDirection=="TO"&&!d.toConfirmed)||(d.estimateDirection=="FROM"&&!d.fromConfirmed))d=applyReferenceTransfer(d,accountCurrency,requireNotNull(targetCurrency),rate(accountCurrency,targetCurrency),d.estimateDirection)
        }else {
            if(originalCurrency!=accountCurrency&&!d.settlementConfirmed&&"accountAmount" !in input)d=applyReferenceAccountAmount(d,accountCurrency,rate(originalCurrency,accountCurrency))
            if(accountCurrency!=bookCurrency&&"baseAmount" !in input)d=applyReferenceBaseAmount(d,accountCurrency,bookCurrency,rate(accountCurrency,bookCurrency))
            if(accountCurrency==bookCurrency)d=d.copy(baseAmount=d.accountAmount)
        }
    }
    return d
}

internal class EntryWriteAdapter(private val action:String):LedgerAdapter() {
    override val capability=ledgerCapability("entry.$action",if(action=="create")entryValueSchema else patchSchema(descriptorSchema(EntryEditInput.serializer().descriptor),mapOf("set" to entryValueSchema,"targets" to entryWriteTargetsSchema)),if(action=="create")mapOf("entry" to "entry")else emptyMap(),
        "Missing patch fields preserve the editor's full reconstructed values. New records use editor account/card/activity/date defaults; no amount/category is invented. Money={currency,minor}; same-currency settlement/base copied exactly. Explicit null clears only optional associations/card/fee fields. useReferenceRates=false by default; true uses frozen local reference quotes and shared conversion functions, never overwriting actual amounts. Foreign actual confirmation is false unless explicit; transfers default pending at both ends. Selectors freeze at first preview.",
        "1–5000 fixed targets; category-only updates also accept explicitly identified trash records (preserve trash status); selectors and other edits remain live-only; expense/income/transfer/refund only. Existing domain validation covers category/account status, currency amounts, opening inclusion, refund ceilings/source, card payment choices, activity/reserve/participants. Incomplete entries reject the whole plan; no interactive draft in stage 3.",
        "Create or batch-edit all ordinary record and transfer/refund fields, preserving attachments. Uses the shared entry preparation and financial refresh. Explicit negative-balance risk identifies the account and resulting balance. entry.confirmActual is an update requiring actual confirmation flags.",
        if(action=="create")"""{"kind":"EXPENSE","account":"account-id","category":"category-id","amount":{"currency":"JPY","minor":900}}"""else """{"targets":{"ids":["entry-id"]},"set":{"note":"已核对"}}""")
    override fun validate(input:JsonObject) {
        if(action=="create")validateEntryValues(input)else {val v=commandJson.decodeFromJsonElement<EntryEditInput>(input);validateTargets(v.targets);validateEntryValues(v.set)
            if(action=="confirmActual")require(v.set["settlementConfirmed"]==JsonPrimitive(true)||v.set["fromConfirmed"]==JsonPrimitive(true)||v.set["toConfirmed"]==JsonPrimitive(true)||v.set["feeConfirmed"]==JsonPrimitive(true)){"请明确要确认的实际金额端 / 確定する実額の側を指定してください"}}
    }
    override suspend fun normalize(input:JsonObject,run:CommandRun):JsonObject {
        if(action=="create")return input
        val v=commandJson.decodeFromJsonElement<EntryEditInput>(input)
        val ids=v.targets.ids?:selectEntries(run.state().ledger,requireNotNull(v.targets.select)).map{run.freezeTarget(it.id)}
        require(ids.size in 1..5000){"匹配记录须为1至5000笔 / 対象は1〜5000件にしてください"}
        return encoded(v.copy(targets=EntryWriteTargets(ids.sortedBy{canonical(it).toString()}))).jsonObject
    }
    override suspend fun execute(step:CommandStep,run:CommandRun):StepEffect {
        val before=run.state();val s=before.ledger
        if(action!="create") {
            val v=commandJson.decodeFromJsonElement<EntryEditInput>(step.input)
            if(v.set.keys.all{it in setOf("category","note")}) {
                val category=v.set["category"]?.let(run::resolve)
                val changes=requireNotNull(v.targets.ids).map{run.resolve(it)}.map{id->
                    val old=(if(v.set.keys==setOf("category"))s.events.filter{it.purgedAt==null}else s.visibleEvents).single{it.id==id}
                    require(old.kind in listOf("EXPENSE","INCOME","TRANSFER","REFUND")){"借入和还款需对应金融命令 / 借入・返済は専用の金融コマンドを使ってください"}
                    if(category!=null)validateEntryCategory(old.kind,s.categories.single{it.id==category},s.categoryEnabled(category),old.categoryId)
                    PreparedCategoryChange(old,old.copy(categoryId=category?:old.categoryId,note=v.set["note"]?.jsonPrimitive?.content?:old.note,revision=Math.addExact(old.revision,1)),s.entryDetails(id))
                }
                run.ledger.writeEntryCategoriesInTransaction(changes)
                return run.effect(step,before,run.state(refresh=true),changes.map{it.before.accountId}.toSet(),requireNotNull(v.targets.ids).map{run.resolve(it)}.toSet(),(changes.mapNotNull{it.before.categoryId}+listOfNotNull(category)).toSet(),changes.map{it.before.date.take(7)}.toSet())
            }
        }
        val drafts=if(action=="create")listOf(entryDraft(step.input,run,s))else {
            val v=commandJson.decodeFromJsonElement<EntryEditInput>(step.input)
            requireNotNull(v.targets.ids).map{run.resolve(it)}.map{id->val e=s.visibleEvents.single{it.id==id};entryDraft(v.set,run,s,s.editingEntryDraft(e))}
        }
        if(action!="create") {
            val input=commandJson.decodeFromJsonElement<EntryEditInput>(step.input)
            if("occurrence" in input.set)for(draft in drafts)for(old in s.occurrences.filter{it.eventId==draft.id&&it.id!=draft.occurrenceId&&it.status in listOf("PENDING","COMPLETED")})run.ledger.unlinkOccurrence(run.bookId,old.id)
        }
        val changes=run.ledger.writeEntryBatchInTransaction(s,drafts)
        if(action=="create")run.output(step.id,"entry",changes.single().event.id,true)
        val after=run.state(refresh=true);val ids=changes.map{it.event.id}.toSet()
        val entries=(s.events+after.ledger.events).filter{it.id in ids||s.settlements.any{st->st.eventId==it.id&&st.originalEventId in ids}}
        val accounts=(s.storedPostings+after.ledger.storedPostings).filter{it.eventId in entries.map{e->e.id}}.map{it.accountId}.toSet()+changes.flatMap{listOfNotNull(it.before?.accountId,it.event.accountId)}
        return run.effect(step,before,after,accounts,entries.map{it.id}.toSet(),entries.mapNotNull{it.categoryId}.toSet(),entries.map{it.date.take(7)}.toSet())
    }
}
internal fun validateTargets(targets:EntryWriteTargets) {
    require((targets.ids==null)!=(targets.select==null)){"目标须为ID列表或选择器 / ID一覧かセレクターを指定してください"}
    targets.ids?.let{require(it.size in 1..5000&&it.distinct().size==it.size)}
    targets.select?.let(::validateSelector)
}

internal class EntryLifecycleAdapter:LedgerAdapter() {
    override val capability=ledgerCapability("entry.lifecycle",patchSchema(descriptorSchema(EntryLifecycleInput.serializer().descriptor),mapOf("entry" to referenceSchema("entry"))),defaults="entry and operation required; no default. Only TRASH or RESTORE.",prerequisites="Existing record in this book; same linked-refund grouping, 30-day recovery deadline, refund ceiling and reserve checks as editor.",description="Move a record (including linked refunds) to trash or restore it. Shows balance/budget/forecast and statement/occurrence consequences. PURGE/EMPTY are forbidden; use trash/restore.",example="""{"entry":"entry-id","operation":"TRASH"}""")
    override fun validate(input:JsonObject){val v=commandJson.decodeFromJsonElement<EntryLifecycleInput>(input);require(v.operation in listOf(EntryLifecycleOperation.TRASH,EntryLifecycleOperation.RESTORE)){permanentAlternative(if(v.operation==EntryLifecycleOperation.EMPTY)"entry.emptyTrash"else"entry.purge")}}
    override suspend fun execute(step:CommandStep,run:CommandRun):StepEffect {
        val v=commandJson.decodeFromJsonElement<EntryLifecycleInput>(step.input);val before=run.state()
        val review=run.ledger.previewEntryLifecycle(run.bookId,run.resolve(v.entry),v.operation)
        check(run.ledger.commitEntryLifecycle(review))
        val ids=review.events.map{it.id}.toSet()
        return run.effect(step,before,run.state(refresh=true),before.ledger.storedPostings.filter{it.eventId in ids}.map{it.accountId}.toSet(),ids,review.events.mapNotNull{it.categoryId}.toSet(),review.impacts.map{it.month}.toSet())
    }
}

internal class TemplateEntryAdapter:LedgerAdapter() {
    override val capability=ledgerCapability("template.createEntry",patchSchema(descriptorSchema(TemplateEntryInput.serializer().descriptor),mapOf("template" to referenceSchema("template"),"set" to entryValueSchema)),mapOf("entry" to "entry"),"set defaults empty; use current template defaults, editor date and remembered card choice. Missing amount or foreign base requires explicit set; no draft is saved.","Template must be available in this book; same prerequisites as entry.create.","Compile a template and overrides into ordinary entry.create execution; preview displays an ordinary record and includes the template basis for confirmation.","""{"template":"template-id","set":{"date":"2026-11-02"}}""")
    override fun validate(input:JsonObject){val v=commandJson.decodeFromJsonElement<TemplateEntryInput>(input);if(v.set.isNotEmpty())validateEntryValues(v.set)}
    override suspend fun execute(step:CommandStep,run:CommandRun):StepEffect {
        val v=commandJson.decodeFromJsonElement<TemplateEntryInput>(step.input);val before=run.state();val s=before.ledger
        val template=s.templates.single{it.id==run.resolve(v.template)}
        val baseline=s.templateDraft(template,run.context.asOfDate)
        val draft=entryDraft(v.set,run,s,baseline)
        val change=run.ledger.writeEntryBatchInTransaction(s,listOf(draft)).single()
        run.output(step.id,"entry",change.event.id,true)
        return run.effect(step.copy(command="entry.create"),before,run.state(refresh=true),setOf(change.event.accountId),setOf(change.event.id),setOfNotNull(change.event.categoryId),setOf(change.event.date.take(7))).let{effect->
            effect.copy(objects=effect.objects+ObjectEffect("template",run.semantic(JsonPrimitive(template.id)).jsonPrimitive.content,run.semantic(commandObjectFacts(encoded(template),setOf("createdAt"))),run.semantic(commandObjectFacts(encoded(template),setOf("createdAt")))))
        }
    }
}

internal val entryWriteTargetsSchema:JsonObject get()=patchSchema(descriptorSchema(EntryWriteTargets.serializer().descriptor),mapOf("ids" to nullableSchema(buildJsonObject{put("type","array");put("items",referenceSchema("entry"))})))
