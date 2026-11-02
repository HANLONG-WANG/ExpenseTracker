package com.yohaku.expensetracker.data.command

import com.yohaku.expensetracker.data.files.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable internal data class StatementCommandInput(val batch:String,val row:String,val entry:JsonElement?=null,
    val note:String="",val kind:EntryKind=EntryKind.EXPENSE,val occurredDate:String?=null,val postedDate:String?=null,
    val originalEvent:JsonElement?=null,val set:JsonObject=buildJsonObject{})
@Serializable internal data class CsvUndoInput(val batch:String,val targets:List<String>?=null)
@Serializable internal data class AnalysisInput(val modules:List<String>,val pinned:List<String> = emptyList())
@Serializable internal data class ReceiptProductInput(val name:String?=null,val quantity:String?=null,val unit:String?=null,
    val unitPrice:Money?=null,val unitPriceDecimal:String?=null,val amount:Money?=null,val note:String?=null,val imageIds:List<String>?=null,val uncertainFields:List<String> = emptyList())
@Serializable internal data class ReceiptAdjustmentInput(val type:String,val amount:Money,val includedInItems:Boolean,val note:String?=null)
@Serializable internal data class ReceiptProductsInput(val receipt:String,val items:List<ReceiptProductInput>,val imageIds:List<String>?=null,
    val adjustments:List<ReceiptAdjustmentInput> = emptyList(),val total:Money?=null,val complete:Boolean=false,val issues:List<String> = emptyList())

internal class StatementAdapter(private val action:String):LedgerAdapter() {
    private val fields=when(action){"accept"->setOf("batch","row","entry","note");"unlink"->setOf("batch","row");"supplement"->setOf("batch","row","kind","occurredDate","postedDate","originalEvent","set");else->setOf("batch","row","entry","postedDate","set")}
    private val schema=patchSchema(withoutFields(descriptorSchema(StatementCommandInput.serializer().descriptor),descriptorSchema(StatementCommandInput.serializer().descriptor).getValue("properties").jsonObject.keys-fields),buildMap {
        if("entry" in fields)put("entry",referenceSchema("entry"))
        if("originalEvent" in fields)put("originalEvent",nullableSchema(referenceSchema("entry")))
        if("set" in fields)put("set",if(action=="confirmActual")withoutFields(entryValueSchema,entryValueSchema.getValue("properties").jsonObject.keys-setOf("baseAmount"))else entryValueSchema)
        for(date in listOf("occurredDate","postedDate"))if(date in fields)put(date,buildJsonObject{put("type","string")})
        if("kind" in fields)put("kind",buildJsonObject{put("type","string");put("enum",encoded(listOf("EXPENSE","INCOME","TRANSFER","REFUND")))})
    }).let{base->JsonObject(base+("required" to encoded(when(action){"accept"->listOf("batch","row","entry");"supplement"->listOf("batch","row","occurredDate","postedDate");"confirmActual"->listOf("batch","row","entry","postedDate");else->listOf("batch","row")})))}
    override val capability=ledgerCapability("statement.$action",schema,
        if(action=="supplement")mapOf("entry" to "entry")else emptyMap(),
        when(action){"accept"->"batch/row/entry required; note defaults empty.";"unlink"->"batch/row required; no default association is inferred.";"supplement"->"batch/row/occurredDate/postedDate required; kind=EXPENSE, set={}; originalEvent required for REFUND. Supply missing category/foreign amounts; source-side amount/direction cannot be overridden.";else->"batch/row/entry/postedDate required; set={} may only supply baseAmount. Actual account amount comes from the statement row."},
        when(action){"accept"->"Existing statement row and live entry in this book with matching account/currency/direction; reviewed posting must be unoccupied.";"unlink"->"Existing reviewed association for this statement batch/row in this book; preserves source and entry.";"supplement"->"Existing statement row without exact/manual association; shared record validation. Financial borrowing/repayment use their dedicated commands.";else->"Existing statement row and live entry in this book with matching account/currency/direction; shared actual-amount preparation and phone USER_FACT."},
        when(action){"accept"->"Accept a reviewed difference linking this statement row to the specified existing entry; does not change ledger amounts.";"unlink"->"Remove only this row's existing reviewed association; retain the statement source and all ledger records.";"supplement"->"Create the missing entry from this statement row, preserving its source amount/direction; phone USER_FACT covers the posted date and amount.";else->"Confirm the specified entry's actual account amount from this statement row, with explicit postedDate; only missing baseAmount may be supplied in set. Phone USER_FACT is required."},
        when(action){"accept"->"""{"batch":"batch-id","row":"row-id","entry":"entry-id","note":"日期差异已核对"}""";"unlink"->"""{"batch":"batch-id","row":"row-id"}""";"supplement"->"""{"batch":"batch-id","row":"row-id","occurredDate":"2026-11-01","postedDate":"2026-11-02","set":{"category":"category-id"}}""";else->"""{"batch":"batch-id","row":"row-id","entry":"entry-id","postedDate":"2026-11-02"}"""})
        .let{if(action in setOf("supplement","confirmActual"))it.copy(confirmationTypes=it.confirmationTypes+ConfirmationKind.USER_FACT)else it}
    override fun validate(input:JsonObject) {
        val v=commandJson.decodeFromJsonElement<StatementCommandInput>(input)
        if(action in setOf("accept","confirmActual"))require(v.entry!=null&&v.entry!=JsonNull)
        if(action in setOf("supplement","confirmActual"))java.time.LocalDate.parse(requireNotNull(v.postedDate))
        if(action=="supplement")java.time.LocalDate.parse(requireNotNull(v.occurredDate))
        if(v.set.isNotEmpty())validateEntryValues(v.set)
        if(action=="confirmActual")require(v.set.keys.all{it=="baseAmount"}){"实际金额来自流水，只可补充本位币金额 / 実額は明細から取得し、基準通貨額のみ補足できます"}
    }
    override suspend fun execute(step:CommandStep,run:CommandRun):StepEffect {
        val v=commandJson.decodeFromJsonElement<StatementCommandInput>(step.input);val r=run.ledger
        val before=run.state();val s=before.ledger
        val batch=requireNotNull(r.db.fileWorkflowDao().statement(run.bookId,v.batch))
        val file=FileProtocols.statement(batch.originalJson);val row=file.rows.single{it.row_id==v.row}
        val basis=ObjectEffect("statementRow",v.batch+":"+v.row,encoded(row),encoded(row))
        var ids=emptySet<String>()
        when(action) {
            "accept"->{
                val entry=run.resolve(requireNotNull(v.entry));ids=setOf(entry)
                val posting=s.reconciliationPostings().single{it.eventId==entry&&it.accountId==file.account_id}
                r.acceptStatementDifference(r.previewStatementDifference(run.bookId,v.batch,v.row,posting.postingId,v.note))
            }
            "unlink"->{
                val decision=r.db.fileWorkflowDao().decisions(run.bookId).first().single{it.batchId==v.batch&&it.rowId==v.row}
                ids=setOf(commandJson.decodeFromString<ReviewedPostingFact>(decision.postingJson).eventId)
                r.clearStatementDecision(run.bookId,v.batch,v.row)
            }
            else->{
                val draft=if(action=="supplement") {
                    val review=s.reviewStatements(r.db.fileWorkflowDao().statements(run.bookId).first(),r.db.fileWorkflowDao().decisions(run.bookId).first()).single{it.batch.id==v.batch}
                    require(review.result.exact.none{it.rowId==v.row}&&review.decisions.none{it.saved.rowId==v.row}){"流水已有对应记录 / 明細には既に対応記録があります"}
                    val seed=StatementSupplementSeed(run.bookId,file.account_id,row,v.kind,requireNotNull(v.occurredDate),requireNotNull(v.postedDate),v.originalEvent?.let(run::resolve))
                    val d=s.statementSupplementDraft(seed)
                    // Source account/date/money remain explicit; set supplies missing editor fields.
                    val sourceFields=if(d.kind==EntryKind.TRANSFER) {
                        if(row.direction=="out")setOf("kind","account","amount","date","fromConfirmed")else setOf("kind","toAccount","toAmount","toDate","toConfirmed")
                    }else setOf("kind","account","date","settlementDate","accountAmount","settlementConfirmed","originalEvent")
                    require(v.set.keys.none{it in sourceFields}){"不能改写流水来源端，请只补充缺少资料 / 明細の元の側は変更せず、不足情報のみ補足してください"}
                    entryDraft(v.set,run,s,d)
                }else {
                    val d=s.statementActualDraft(StatementActualSeed(run.resolve(requireNotNull(v.entry)),file.account_id,row,requireNotNull(v.postedDate)))
                    entryDraft(v.set,run,s,d)
                }
                val change=r.writeEntryBatchInTransaction(s,listOf(draft)).single();ids=setOf(change.event.id)
                if(action=="supplement")run.output(step.id,"entry",change.event.id,true)
            }
        }
        val after=run.state(refresh=true)
        val entries=(s.events+after.ledger.events).filter{it.id in ids}
        val accounts=(s.storedPostings+after.ledger.storedPostings).filter{it.eventId in ids}.map{it.accountId}.toSet()+file.account_id
        var effect=run.effect(step,before,after,accounts,ids,entries.mapNotNull{it.categoryId}.toSet(),entries.map{it.date.take(7)}.toSet()).let{it.copy(objects=it.objects+basis)}
        if(action in setOf("supplement","confirmActual"))effect=effect.copy(requirements=effect.requirements+requirement(step.id,ConfirmationKind.USER_FACT,run.semantic(encoded(listOf(step.input,encoded(row)))),
            "已核对流水「${row.summary}」的实际入账日期 ${v.postedDate} 与金额 ${row.settlement.amount} ${row.settlement.currency}。",
            "明細「${row.summary}」の実計上日 ${v.postedDate} と金額 ${row.settlement.amount} ${row.settlement.currency} を確認しました。"))
        return effect
    }
}

internal class CsvUndoAdapter:LedgerAdapter() {
    override val capability=ledgerCapability("csvBatch.undo",withoutFields(descriptorSchema(CsvUndoInput.serializer().descriptor),setOf("targets")),defaults="batch required; no default.",
        prerequisites="Existing import batch in this book with live imported records; selected records and linked refunds must satisfy the normal trash domain rules.",
        description="Move this batch's remaining live records and linked refunds to trash, retaining later edits and batch history. No physical deletion; CSV import itself is expressed as entry.create commands.",example="""{"batch":"batch-id"}""")
    override fun validate(input:JsonObject){commandJson.decodeFromJsonElement<CsvUndoInput>(input)}
    override suspend fun normalize(input:JsonObject,run:CommandRun):JsonObject {
        val v=commandJson.decodeFromJsonElement<CsvUndoInput>(input)
        val targets=run.ledger.db.csvImportDao().events(run.bookId,v.batch).filter{it.deletedAt==null}.map{it.id}.sorted()
        require(targets.size in 1..5000){"批次目标须为1至5000笔 / バッチの対象は1〜5000件にしてください"}
        return encoded(v.copy(targets=targets)).jsonObject
    }
    override suspend fun execute(step:CommandStep,run:CommandRun):StepEffect {
        val before=run.state();val v=commandJson.decodeFromJsonElement<CsvUndoInput>(step.input);val batch=v.batch
        require(run.ledger.db.csvImportDao().events(run.bookId,batch).filter{it.deletedAt==null}.map{it.id}.sorted()==v.targets){"导入批次目标已变化，请重新预览 / 取込バッチの対象が変わりました。再確認してください"}
        run.ledger.undoCsvBatch(run.bookId,batch)
        val after=run.state(refresh=true)
        val changed=after.ledger.events.filter{e->before.ledger.events.firstOrNull{it.id==e.id}!=e}
        return run.effect(step,before,after,before.ledger.storedPostings.filter{p->changed.any{it.id==p.eventId}}.map{it.accountId}.toSet(),changed.map{it.id}.toSet(),changed.mapNotNull{it.categoryId}.toSet(),changed.map{it.date.take(7)}.toSet())
    }
}
internal class AnalysisAdapter:LedgerAdapter() {
    override val capability=ledgerCapability("preferences.analysis",patchSchema(descriptorSchema(AnalysisInput.serializer().descriptor),listOf("modules","pinned").associateWith{arraySchema(buildJsonObject{put("type","string");put("enum",encoded(AnalysisModule.entries.map{it.name}))})}),defaults="modules required ordered list; pinned=[]; full replacement, not patch. Unknown module rejects.",prerequisites="Existing book; modules unique and pins subset of selected modules.",description="Set analysis modules, their display order and pinned modules; shared with analysis preferences editor.",example="""{"modules":["CATEGORIES","TREND"],"pinned":["CATEGORIES"]}""")
    override fun validate(input:JsonObject){val v=commandJson.decodeFromJsonElement<AnalysisInput>(input);(v.modules+v.pinned).forEach{AnalysisModule.valueOf(it)};require(v.modules.distinct()==v.modules&&v.pinned.distinct()==v.pinned&&v.pinned.all{it in v.modules})}
    override suspend fun execute(step:CommandStep,run:CommandRun):StepEffect {
        val v=commandJson.decodeFromJsonElement<AnalysisInput>(step.input)
        run.ledger.saveAnalysisPreferences(run.bookId,v.modules.map(AnalysisModule::valueOf),v.pinned.sorted().map(AnalysisModule::valueOf).toSet())
        run.state(refresh=true);return StepEffect(step.id,step.command,emptyList(),emptyList())
    }
}
internal class ReceiptProductsAdapter:LedgerAdapter() {
    override val capability=ledgerCapability("receipt.items.save",descriptorSchema(ReceiptProductsInput.serializer().descriptor),defaults="receipt/items required; imageIds required for first backfill; omitted on edit preserves the previous document coverage (never assumes newly added images covered). Item imageIds defaults to the document coverage. adjustments=[], total=null (unknown), complete=false, issues=[]. Optional item facts stay unknown. Posted/line/adjustment/total amounts are {currency,minor}; quantity is decimal string. Unit-price facts accept unitPrice={currency,minor} OR unitPriceDecimal (exact decimal in receipt currency, allowing sub-minor prices as in the existing receipt protocol). Complete document replacement.",
        prerequisites="Live receipt and entry in this book, current images; currency matches original entry. Same FileProtocols receipt validation as backfill/local editor. Existing document revision and all source entry/image facts compared.",
        description="Backfill or edit product details directly in the result, without changing ledger amount/category. Explicit source image coverage is checked against the current receipt; source revisions are assigned on device; no invented export bundle or image path.",example="""{"receipt":"receipt-id","imageIds":["image-id"],"items":[{"name":"牛奶","quantity":"1","amount":{"currency":"JPY","minor":200}}],"complete":false}""")
    override fun validate(input:JsonObject){commandJson.decodeFromJsonElement<ReceiptProductsInput>(input)}
    override suspend fun execute(step:CommandStep,run:CommandRun):StepEffect {
        val v=commandJson.decodeFromJsonElement<ReceiptProductsInput>(step.input);val s=run.state().ledger;val r=run.ledger
        val receipt=s.receipts.single{it.id==v.receipt};val event=s.visibleEvents.single{it.id==receipt.eventId}
        val old=r.db.fileWorkflowDao().itemsForReceipt(run.bookId,receipt.id)
        val images=v.imageIds?:old?.let{FileProtocols.json.decodeFromString<ReceiptItemsRecord>(it.recordJson).image_ids}
            ?:error("首次回填请明确来源图片 / 初回の補完では元画像を指定してください")
        fun amount(m:Money?):String?=m?.also{require(it.currency.name==event.currency)}?.decimal?.toPlainString()
        val products=v.items.mapIndexed{index,item->
            val id=java.util.UUID.nameUUIDFromBytes("${run.planId}:${receipt.id}:${step.id}:$index".toByteArray()).toString()
            run.generated(id,step.id,"receiptItem",index.toString())
            require(item.unitPrice==null||item.unitPriceDecimal==null){"单价只能填写一种表示 / 単価の表現は1つだけ指定してください"}
            ReceiptItem(id,item.imageIds?:images,item.uncertainFields,item.name,item.quantity,item.unit,item.unitPriceDecimal?:amount(item.unitPrice),amount(item.amount),item.note)
        }
        val record=ReceiptItemsRecord(event.id,receipt.id,event.revision,receipt.revision,event.currency,images,products,
            v.adjustments.map{ReceiptAdjustment(it.type,requireNotNull(amount(it.amount)),it.includedInItems,it.note)},v.complete,v.issues,amount(v.total))
        r.saveDirectReceiptItems(run.bookId,receipt.id,old?.revision,record)
        run.state(refresh=true)
        return StepEffect(step.id,step.command,listOf(receipt.id),listOf(
            ObjectEffect("entry",event.id,run.semantic(commandEventFacts(encoded(event))),run.semantic(commandEventFacts(encoded(event)))),
            ObjectEffect("receiptSource",receipt.id,JsonArray(s.entryReceiptImages(event.id).map{file->buildJsonObject{put("imageId",file.id);put("name",file.originalName);put("size",file.size);put("checksum",file.checksum)}}),JsonArray(s.entryReceiptImages(event.id).map{file->buildJsonObject{put("imageId",file.id);put("name",file.originalName);put("size",file.size);put("checksum",file.checksum)}}))))
    }
}
@Serializable internal data class DraftCategoryInput(val sourceCategory:JsonElement,val category:JsonElement)
internal class DraftCategoryAdapter:LedgerAdapter() {
    override val capability=ledgerCapability("entryDraft.reclassify",patchSchema(descriptorSchema(DraftCategoryInput.serializer().descriptor),mapOf("sourceCategory" to referenceSchema("category"),"category" to referenceSchema("category"))),defaults="sourceCategory/category required; no defaults. Only the one existing ordinary draft in this book is eligible.",prerequisites="Existing expense/income draft still has sourceCategory; destination has the same kind and is enabled. Complete saved draft and media references are compared.",description="Migrate the category of an existing ordinary draft while preserving every other input and media session; never submit or discard the draft.",example="""{"sourceCategory":"old-category","category":"new-category"}""")
    override fun validate(input:JsonObject){commandJson.decodeFromJsonElement<DraftCategoryInput>(input)}
    override suspend fun execute(step:CommandStep,run:CommandRun):StepEffect {
        val v=commandJson.decodeFromJsonElement<DraftCategoryInput>(step.input)
        run.ledger.reclassifyEntryDraft(run.bookId,run.resolve(v.sourceCategory),run.resolve(v.category))
        run.state(refresh=true);return StepEffect(step.id,step.command,emptyList(),emptyList())
    }
}
internal fun fileCommandAdapters():List<CommandAdapter> = listOf(StatementAdapter("accept"),StatementAdapter("unlink"),StatementAdapter("supplement"),StatementAdapter("confirmActual"),CsvUndoAdapter(),AnalysisAdapter(),ReceiptProductsAdapter(),DraftCategoryAdapter())
