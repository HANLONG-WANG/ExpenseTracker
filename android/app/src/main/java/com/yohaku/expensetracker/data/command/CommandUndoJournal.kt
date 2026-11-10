package com.yohaku.expensetracker.data.command

import android.database.Cursor
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString

/** Transaction-local ownership journal. Only changed rows in the declared write set are
 * restorable. Deleted relationship rows retain both identity and their exact pre-batch snapshot.
 * Book creation also records its initial budget; no subsequent command may target that book. */
internal class CommandUndoJournal(private val ledger:LedgerRepository,private val bookId:String) {
    private val sql=ledger.db.openHelper.writableDatabase
    private val deleteOrder=listOf("monthly_plans","receipt_item_documents","csv_batch_events","csv_batches","analysis_preferences","receipt_images","receipts","attachment_links","draft_media","drafts","revisions","treat_allocations","reserve_uses","event_merchants","event_places","event_activities",
        "financial_drafts","credit_limit_observations","credit_limit_recoveries","credit_limit_group_revisions","credit_limit_group_members","credit_limit_groups",
        "financial_rule_validations","financial_plan_revisions","financial_bill_adjustments","financial_repayment_allocations","financial_contract_plans","financial_arrangements",
        "financial_repayments","debt_contracts","financial_bills","financial_plans","financial_rules","credit_limit_configs",
        "statement_decisions","expected_incomes","activity_reserves","occurrences","treats","expense_components","transfers","settlements","postings","events",
        "entry_templates","activity_budgets","activity_participants","book_features","recurrences","activities","merchant_locations","merchants","friends","budgets","categories","accounts","books")
    private val merchantHistoryTables=setOf("merchant_operations","merchant_operation_impacts")
    private data class Table(val name:String,val columns:List<String>,val primary:List<String>)
    private val tables by lazy {
        sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").use{c->buildList{while(c.moveToNext())add(c.getString(0))}}.map{name->
            val columns=mutableListOf<String>();val primary=sortedMapOf<Int,String>()
            sql.query("PRAGMA table_info(`$name`)").use{c->while(c.moveToNext()){columns+=c.getString(1);if(c.getInt(5)>0)primary[c.getInt(5)]=c.getString(1)}}
            Table(name,columns,primary.values.toList())
        }.associateBy{it.name}
    }
    private fun key(table:String,row:JsonObject)=JsonObject(tables.getValue(table).primary.associateWith{row.getValue(it)})
    private fun identity(table:String,row:JsonObject)=table+":"+canonical(key(table,row))
    private fun objectKey(table:String,row:JsonObject)=row["id"]?.jsonPrimitive?.content?:identity(table,row)
    private fun readRows(table:String,where:String="bookId=?",args:Array<out Any?> = arrayOf(bookId)):List<JsonObject> =
        sql.query("SELECT * FROM `$table` WHERE $where ORDER BY "+tables.getValue(table).primary.joinToString{ "`$it`" },args).use{c->buildList {
            while(c.moveToNext())add(buildJsonObject {
                c.columnNames.forEachIndexed{index,name->put(name,when(c.getType(index)) {
                    Cursor.FIELD_TYPE_NULL->JsonNull
                    Cursor.FIELD_TYPE_INTEGER->JsonPrimitive(c.getLong(index))
                    Cursor.FIELD_TYPE_FLOAT->JsonPrimitive(c.getDouble(index))
                    Cursor.FIELD_TYPE_STRING->JsonPrimitive(c.getString(index))
                    else->error("Command undo does not accept BLOB rows: $table.$name")
                })}
            })
        }}
    private fun clause(key:JsonObject)=key.keys.joinToString(" AND "){"`$it`=?"}
    private fun values(row:JsonObject):Array<Any?> = row.values.map{v->
        if(v==JsonNull)null else v.jsonPrimitive.let{if(it.isString)it.content else it.longOrNull?:it.double}
    }.toTypedArray()
    private fun read(change:UndoRowChange)=readRows(change.table,clause(change.key),values(change.key)).singleOrNull()
    fun snapshot():Map<String,Pair<String,JsonObject>> = buildMap {
        for(table in deleteOrder)for(row in if(table in setOf("books","budgets"))readRows(table,"1=1",emptyArray())else readRows(table))put(identity(table,row),table to row)
    }
    fun changes(before:Map<String,Pair<String,JsonObject>>,touched:List<UndoRowChange> = emptyList(),after:Map<String,Pair<String,JsonObject>> = snapshot()):List<UndoRowChange> {
        val touchedKeys=touched.map{identity(it.table,it.after)}.toSet()
        return (before.keys+after.keys).mapNotNull{id->
            val pair=after[id]?:before.getValue(id);val (table,row)=pair;val old=before[id]?.second
            if(old==after[id]?.second&&id !in touchedKeys)null else UndoRowChange(table,key(table,row),old,row,removed=id !in after)
        }
    }
    fun referenceBasis(schema:JsonObject,input:JsonElement,run:CommandRun,before:Map<String,Pair<String,JsonObject>>,after:Map<String,Pair<String,JsonObject>>):List<UndoRowChange> {
        val references=mutableSetOf<Pair<String,String>>()
        fun collect(s:JsonObject,v:JsonElement) {
            if(v==JsonNull)return
            s["x-reference-type"]?.jsonPrimitive?.content?.let{references+=it to run.resolve(v);return}
            s["anyOf"]?.jsonArray?.forEach{collect(it.jsonObject,v)}
            if(v is JsonObject)s["properties"]?.jsonObject?.forEach{(key,field)->v[key]?.let{collect(field.jsonObject,it)}}
            if(v is JsonArray)s["items"]?.jsonObject?.let{field->v.forEach{collect(field,it)}}
        }
        collect(schema,input)
        return (before.keys+after.keys).mapNotNull{id->
            val (table,row)=after[id]?:before.getValue(id)
            val type=if(table=="credit_limit_groups")"creditLimitGroup"else commandTableType(table)
            val objectId=row["id"]?.jsonPrimitive?.contentOrNull?:return@mapNotNull null
            if((type to objectId) !in references)null
            else UndoRowChange(table,key(table,row),before[id]?.second,row,removed=id !in after)
        }
    }
    private fun name(row:JsonObject,names:Map<String,String>):String {
        fun text(key:String)=row[key]?.jsonPrimitive?.contentOrNull?.takeIf{it.isNotBlank()}
        if("text" in row)return "月度计划 / 月の計画"
        if(text("modulesJson")!=null)return "分析设置 / 分析設定 · "+names[text("bookId")].orEmpty()
        text("receiptId")?.let{id->names[id]?.let{return it}}
        text("fileName")?.let{return it}
        if(text("origin")=="POOL")return "余额池 / 残高プール · "+text("name").orEmpty()
        if(text("groupId")!=null&&text("accountId")!=null)return listOfNotNull(names[text("groupId")],names[text("accountId")]).joinToString(" · ")
        if(text("kind") in setOf("BORROWING","REPAYMENT"))return listOfNotNull(text("date"),if(text("kind")=="BORROWING")"借入 / 借入"else"还款 / 返済",text("currency")?.let{c->row["amountMinor"]?.jsonPrimitive?.longOrNull?.let{Money(Currency.valueOf(c),it).display()}},text("merchant")).joinToString(" · ")
        if(text("scheduledDate")!=null)return listOfNotNull(text("name"),text("scheduledDate")).joinToString(" · ")
        if(text("month")!=null)return text("month")+" · "+(text("categoryId")?.let{names[it]}?:"月预算 / 月予算")
        return text("name")?:text("title")?:text("originalName")
            ?:text("merchant")?.let{listOfNotNull(text("date"),it).joinToString(" · ")}
            ?:listOf("activityId","reserveId","treatId","recurrenceId","friendId","planId","originalPlanId","targetPlanId","contractId","billId","ruleVersionId","groupId","accountId","eventId").firstNotNullOfOrNull{field->text(field)?.let{names[it]}}
            ?:text("slot")?.let{"草稿 / 下書き"}?:text("date")?:"关联资料 / 関連情報"
    }
    private fun names():Map<String,String> {
        val named=(listOf("debt_contracts","credit_limit_groups","accounts","categories","merchants","merchant_locations","friends","entry_templates","financial_plans","activities","activity_reserves","recurrences","occurrences","treats","expected_incomes").flatMap{readRows(it)}+readRows("books","1=1",emptyArray())).associate{it.getValue("id").jsonPrimitive.content to it.getValue("name").jsonPrimitive.content}
        val entries=readRows("events").associate{row->row.getValue("id").jsonPrimitive.content to if(row["kind"]?.jsonPrimitive?.content in setOf("BORROWING","REPAYMENT"))name(row,named)else listOfNotNull(row["date"]?.jsonPrimitive?.contentOrNull,row["merchant"]?.jsonPrimitive?.contentOrNull?.takeIf{it.isNotBlank()},row["note"]?.jsonPrimitive?.contentOrNull?.takeIf{it.isNotBlank()}).joinToString(" · ")}
        val bills=readRows("financial_bills").associate{row->row.getValue("id").jsonPrimitive.content to
            ((named[row["planId"]?.jsonPrimitive?.content].orEmpty())+" · "+row.getValue("number").jsonPrimitive.content+"期 / 回 · "+(row["confirmedDate"]?.jsonPrimitive?.contentOrNull?:row["predictedDate"]?.jsonPrimitive?.contentOrNull).orEmpty())}
        val rules=readRows("financial_rules").associate{row->row.getValue("id").jsonPrimitive.content to
            (named[row["accountId"]?.jsonPrimitive?.content].orEmpty()+" · "+row.getValue("version").jsonPrimitive.content+"版 / 版")}
        val receipts=readRows("receipts").associate{row->row.getValue("id").jsonPrimitive.content to (entries[row.getValue("eventId").jsonPrimitive.content]?:"小票 / レシート")}
        return named+entries+bills+rules+receipts
    }
    // A batch can touch thousands of instances. Load relationship tables once per final-state
    // guard pass, not once per instance; CommandRun invalidates this cache on a state refresh.
    private val relationRows by lazy {tables.values.filter{"bookId" in it.columns&&!it.name.startsWith("command_")&&!it.name.startsWith("cloud_")&&it.name!="revisions"&&it.name !in merchantHistoryTables&&it.name!="monthly_reviews"}.associate{it.name to readRows(it.name)}}
    fun planningRelations(type:String,id:String):JsonObject {
        val fields=when(type) {
            "activity"->setOf("activityId","activeActivityId")
            "recurrence"->setOf("recurrenceId")
            "occurrence"->setOf("occurrenceId")
            "reserve"->setOf("reserveId")
            "treat"->setOf("treatId")
            "expectedIncome"->setOf("targetId")
            else->error("Unknown planning guard: $type")
        }
        val related=linkedMapOf<String,List<JsonObject>>()
        fun mentions(value:JsonElement):Boolean=when(value) {
            is JsonObject->value.any{(k,v)->k==id||mentions(v)}
            is JsonArray->value.any(::mentions)
            is JsonPrimitive->value.isString&&value.content==id
            else->false
        }
        for(t in tables.values.filter{"bookId" in it.columns&&!it.name.startsWith("command_")&&!it.name.startsWith("cloud_")&&it.name!="revisions"&&it.name !in merchantHistoryTables&&it.name!="monthly_reviews"}) {
            val columns=t.columns.filter{it in fields}
            val jsonColumns=t.columns.filter{it.endsWith("Json")||it=="json"}
            if(columns.isEmpty()&&jsonColumns.isEmpty())continue
            val rows=relationRows.getValue(t.name).filter{row->columns.any{row[it]==JsonPrimitive(id)}||jsonColumns.any{c->
                row[c]?.jsonPrimitive?.contentOrNull?.let{raw->mentions(commandJson.parseToJsonElement(raw))}==true
            }}
            if(rows.isNotEmpty())related[t.name]=rows
        }
        // Include outgoing payment links too: occurrences/expected incomes hold eventId on their
        // own row, so looking only for incoming FKs would miss later edits to an existing receipt.
        val ownTable=when(type){"activity"->"activities";"recurrence"->"recurrences";"occurrence"->"occurrences";"reserve"->"activity_reserves";"treat"->"treats";else->"expected_incomes"}
        val own=relationRows.getValue(ownTable).filter{it["id"]==JsonPrimitive(id)}
        related[ownTable]=(related[ownTable].orEmpty()+own).distinct()
        if(type=="occurrence")related["recurrences"]=relationRows.getValue("recurrences").filter{rule->own.any{it["recurrenceId"]==rule["id"]}}
        val eventIds=related.values.flatten().mapNotNull{it["eventId"]?.jsonPrimitive?.contentOrNull}.toSet()
        val treatIds=related["treats"].orEmpty().map{it.getValue("id").jsonPrimitive.content}.toSet()
        if(eventIds.isNotEmpty())for(t in listOf("events","settlements","postings","transfers","expense_components","event_places","event_merchants","reserve_uses","event_activities","attachment_links","receipts"))
            related[t]=(related[t].orEmpty()+relationRows.getValue(t).filter{(it[if(t=="events")"id"else"eventId"]?.jsonPrimitive?.contentOrNull) in eventIds}).distinct()
        if(treatIds.isNotEmpty())related["treat_allocations"]=relationRows.getValue("treat_allocations").filter{it["treatId"]?.jsonPrimitive?.contentOrNull in treatIds}
        return JsonObject(related.toSortedMap().mapValues{(_,rows)->JsonArray(rows.sortedBy{canonical(it).toString()})})
    }
    /** Full incoming references plus outgoing source rows protect already-existing financial
     * objects, not only newly-owned rows. Raw values are for undo conflicts, never comparison. */
    fun financeRelations(table:String,id:String):JsonObject {
        fun mentions(v:JsonElement):Boolean=when(v) {
            is JsonObject->v.any{(k,x)->k==id||mentions(x)}
            is JsonArray->v.any(::mentions)
            is JsonPrimitive->v.isString&&(id in financialReferenceIds(v.content)||v.content.trimStart().let{it.startsWith("{")||it.startsWith("[")}&&runCatching{commandJson.parseToJsonElement(v.content)}.getOrNull()?.let(::mentions)==true)
            else->false
        }
        val own=relationRows.getValue(table).filter{it["id"]==JsonPrimitive(id)||table=="financial_repayments"&&it["eventId"]==JsonPrimitive(id)}
        val outgoing=own.flatMap{row->row.filterKeys{it.endsWith("Id")&&it!="bookId"}.values.mapNotNull{it.jsonPrimitive.contentOrNull}}.toSet()
        return JsonObject(relationRows.filterKeys{it !in setOf("financial_plan_revisions","credit_limit_group_revisions")}.mapValues{(_,rows)->JsonArray(rows.filter{row->
            row in own||row["id"]?.jsonPrimitive?.contentOrNull in outgoing||row.any{(key,value)->
                (key.endsWith("Id")&&key!="bookId"||key.endsWith("Json")||key in setOf("json","sourceProof","slot"))&&mentions(value)
            }
        }.sortedBy{canonical(it).toString()})}.filterValues{it.isNotEmpty()})
    }
    fun fileSnapshot(id:String):JsonElement=readRows("file_blobs","bookId=? AND id=?",arrayOf(bookId,id)).singleOrNull()?:JsonNull
    fun removals(changes:List<UndoRowChange>):List<UndoRemoval> {
        val names=names()
        return changes.filter{it.before==null&&!it.removed}.map{change->UndoRemoval(change.table,objectKey(change.table,change.after),name(change.after,names),
            (change.after["number"]?:change.after["version"])?.jsonPrimitive?.intOrNull)}
    }
    // Local merchant deletion detaches live references without rewriting historical journals.
    // Project conflict expectations after local deletion; business restoration is checked separately. Other fields
    // still participate in exact conflict checks.
    private val availableMerchants by lazy {readRows("merchants").map{it.getValue("id").jsonPrimitive.content}.toSet()}
    private fun restorableRow(table:String,row:JsonObject):JsonObject? {
        if(table in setOf("event_merchants","entry_templates","recurrences")) {
            val merchant=row["merchantId"]?.jsonPrimitive?.contentOrNull
            if(merchant!=null&&merchant !in availableMerchants)
                return if(table=="event_merchants")null else JsonObject(row+("merchantId" to JsonNull))
        }
        if(table=="drafts") {
            val draft=ledger.json.decodeFromString<EntryDraft>(row.getValue("json").jsonPrimitive.content)
            if(draft.merchantId!=null&&draft.merchantId !in availableMerchants)
                return JsonObject(row+("json" to JsonPrimitive(ledger.json.encodeToString(draft.withoutMerchantLink()))))
        }
        return row
    }
    fun restorableGuard(guard:UndoGuard):JsonElement {
        val expected=guard.after
        if(guard.type=="entryDetails"&&expected is JsonObject)return restorableRow("entry_templates",expected)!!
        if((guard.type.startsWith("planning:")||guard.type.startsWith("finance:"))&&expected is JsonObject) {
            val rows=expected.mapValues{(table,value)->JsonArray(value.jsonArray.mapNotNull{restorableRow(table,it.jsonObject)})}
            return JsonObject(if(guard.type.startsWith("finance:"))rows.filterValues{it.isNotEmpty()}else rows)
        }
        return expected
    }
    // Synchronization generations are concurrency metadata, not user-editable undo fields.
    private fun comparableRow(table:String,row:JsonObject?):JsonObject? {
        if(table!="drafts"||row==null)return row
        val draft=ledger.json.decodeFromString<EntryDraft>(row.getValue("json").jsonPrimitive.content)
        return JsonObject(row+("json" to JsonPrimitive(ledger.json.encodeToString(draft.copy(merchantSyncVersion=0)))))
    }
    private fun restoredBefore(change:UndoRowChange):JsonObject? = change.before?.let {
        if(change.table!="drafts")it else {
            val row=requireNotNull(restorableRow(change.table,it))
            val draft=ledger.json.decodeFromString<EntryDraft>(row.getValue("json").jsonPrimitive.content)
            val current=read(change)?.let{value->ledger.json.decodeFromString<EntryDraft>(value.getValue("json").jsonPrimitive.content)}
            JsonObject(row+("json" to JsonPrimitive(ledger.json.encodeToString(draft.copy(merchantSyncVersion=maxOf(draft.merchantSyncVersion,current?.merchantSyncVersion?:0)+1)))))
        }
    }
    fun merchantConflicts(record:UndoRecord):List<UndoConflict> {
        val relevant=setOf("merchants","events","event_merchants","settlements","entry_templates","recurrences","merchant_locations")
        if(record.rows.none{it.table in relevant})return emptyList()
        val before=relevant.associateWith{table->readRows(table).associateBy{identity(table,it)}.toMutableMap()}
        val after=before.mapValues{(_,rows)->rows.toMutableMap()}
        for(change in record.rows.filter{it.table in relevant}) {
            val rows=after.getValue(change.table);val id=identity(change.table,change.after)
            restoredBefore(change)?.let{rows[id]=it}?:rows.remove(id)
        }
        return newMerchantUndoConflicts(before.mapValues{it.value.values.toList()},after.mapValues{it.value.values.toList()})
    }
    /** Missing databaseVersion identifies journals written before v40. Only frozen migration
     * values are supplied; current values (including later source keys) are still compared. */
    fun migratedRecord(record:UndoRecord):UndoRecord {
        if(record.databaseVersion>=40)return record
        fun row(table:String,value:JsonObject):JsonObject {
            val defaults=buildMap<String,JsonElement> {
                if(record.databaseVersion<40&&table=="csv_batch_events")put("sourceMatchKey",com.yohaku.expensetracker.data.db.csvRevisionSourceKeyV40(sql,bookId,value.getValue("eventId").jsonPrimitive.content)?.let(::JsonPrimitive)?:JsonNull)
                if(record.databaseVersion<39&&table in setOf("events","entry_templates"))put("budgetExcluded",JsonPrimitive(0))
            }
            return JsonObject(defaults+value)
        }
        fun event(value:JsonElement):JsonElement = if(record.databaseVersion<39&&value is JsonObject)
            JsonObject(mapOf("budgetExcluded" to JsonPrimitive(false))+value)else value
        val guards=record.guards.map{guard->guard.copy(after=when {
            guard.type=="entry"->event(guard.after)
            guard.type=="entryAssociations"->JsonObject(guard.after.jsonObject.mapValues{(field,value)->when(field) {
                "original"->event(value)
                "refunds"->JsonArray(value.jsonArray.map(::event))
                else->value
            }})
            guard.type.startsWith("planning:")||guard.type.startsWith("finance:")->JsonObject(guard.after.jsonObject.mapValues{(table,value)->JsonArray(value.jsonArray.map{row(table,it.jsonObject)})})
            else->guard.after
        })}
        return record.copy(guards=guards,rows=record.rows.map{it.copy(before=it.before?.let{v->row(it.table,v)},after=row(it.table,it.after))})
    }

    fun upgradeConflicts(record:UndoRecord):List<UndoConflict> {
        if(record.databaseVersion>=40)return emptyList()
        val touched=sql.query("""SELECT i.objectType,i.objectId FROM merchant_operation_impacts i
            JOIN merchant_operations o ON o.bookId=i.bookId AND o.id=i.operationId
            WHERE i.bookId=? AND o.type='HISTORY_CLEANUP' AND o.source='UPGRADE'""",arrayOf(bookId)).use{c->buildSet {
            while(c.moveToNext())add((if(c.getString(0)=="REFUND")"EVENT"else c.getString(0)) to c.getString(1))
        }}
        if(touched.isEmpty())return emptyList()
        val referenced=mutableSetOf<Pair<String,String>>()
        fun add(type:String,value:JsonElement?) { value?.jsonPrimitive?.contentOrNull?.let{referenced+=type to it} }
        fun row(table:String,value:JsonObject) {
            when(table) {
                "events"->add("EVENT",value["id"])
                "entry_templates"->add("TEMPLATE",value["id"])
                "recurrences"->add("RECURRENCE",value["id"])
            }
            for(field in listOf("eventId","originalEventId","sourceEventId","fundingEventId","linkedTransferEventId","causeEventId"))add("EVENT",value[field])
            add("RECURRENCE",value["recurrenceId"])
        }
        for(change in record.rows) { row(change.table,change.after);change.before?.let{row(change.table,it)} }
        for(guard in record.guards) {
            if(guard.type in setOf("entry","entryDetails","entryPlace","entryAssociations"))referenced+="EVENT" to guard.key
            if(guard.type=="entryAssociations") {
                val value=guard.after.jsonObject
                (value["original"] as? JsonObject)?.let{row("events",it)}
                (value["refunds"] as? JsonArray)?.forEach{row("events",it.jsonObject)}
            }
            if(guard.type.startsWith("planning:")||guard.type.startsWith("finance:"))
                guard.after.jsonObject.forEach{(table,values)->values.jsonArray.forEach{row(table,it.jsonObject)}}
        }
        return (referenced intersect touched).map{(type,id)->UndoConflict(type,id,"数据已升级整理 / データはアップグレード時に整理済み",
            JsonPrimitive("升级前数据 / アップグレード前のデータ"),JsonPrimitive("数据已升级整理 / データはアップグレード時に整理済み"))}
    }

    fun conflicts(record:UndoRecord):List<UndoConflict> = record.rows.flatMap{change->
        // Unknown/missing columns cannot be silently treated as null, even on a deleted row.
        val columns=tables.getValue(change.table).columns.toSet()
        val missing=(columns-change.after.keys)+(change.before?.let{columns-it.keys}.orEmpty())
        missing.map{field->UndoConflict(change.table,objectKey(change.table,change.after),field,
            JsonPrimitive("日志缺少字段 / ログに項目がありません"),JsonPrimitive("无法安全解释升级后的字段 / 更新後の項目を安全に解釈できません"))}+
        undoDifferences(change.table,objectKey(change.table,change.after),if(change.removed)JsonNull else comparableRow(change.table,restorableRow(change.table,change.after))?:JsonNull,comparableRow(change.table,read(change))?:JsonNull)
    }+record.rows.filter{it.table in setOf("books","events")&&it.before?.get("deletedAt")==JsonNull&&it.after["deletedAt"]!=JsonNull&&it.after["deletedAt"]?.jsonPrimitive?.longOrNull?.let{time->time<=ledger.clock.millis()-recoveryDuration.toMillis()}==true}.map{change->
        UndoConflict(change.table,objectKey(change.table,change.after),"recoveryDeadline",JsonPrimitive("可恢复 / 復元可能"),JsonPrimitive("已超过恢复期限 / 復元期限切れ"))
    }+references(record.rows,allowRecorded=true)

    /** Same FK inventory as scopedReferenceCounts/accountUsage, augmented with embedded JSON.
     * A row changed by this batch is internal only because its exact final snapshot is checked above
     * and its pre-batch value is restored. Merely being in the same account never grants ownership. */
    private fun references(changes:List<UndoRowChange>,allowRecorded:Boolean):List<UndoConflict> {
        val created=changes.filter{it.before==null&&!it.removed}
        if(created.isEmpty())return emptyList()
        val allowed=if(allowRecorded)changes.map{identity(it.table,it.after)}.toSet()else emptySet()
        val byTable=created.groupBy{it.table}
        val byId=created.mapNotNull{row->row.after["id"]?.jsonPrimitive?.content?.let{it to row}}.toMap()
        val foreignKeys=ledger.scopedForeignKeys().filter{it.parent in byTable}
        fun jsonColumn(name:String)=name.endsWith("Json")||name in setOf("json","encoded","sourceProof")
        val sources=tables.values.filter{"bookId" in it.columns&&!it.name.startsWith("command_")&&!it.name.startsWith("cloud_")&&it.name!="revisions"&&it.name !in merchantHistoryTables&&
            (it.columns.any(::jsonColumn)||it.columns.any{column->column.endsWith("Id")}||foreignKeys.any{fk->fk.table==it.name})}
        // History is never restored or scanned for snapshot IDs. Its real book FK still
        // prevents removing a book with later history (book lifecycle/purge owns that cleanup).
        val historyBookSources=if("books" in byTable)tables.values.filter{it.name in merchantHistoryTables}else emptyList()
        val names=names().toMutableMap()
        val conflicts=mutableListOf<UndoConflict>()
        val scopes=(listOf(bookId)+created.filter{it.table=="books"}.map{it.after.getValue("id").jsonPrimitive.content}).distinct()
        for(table in sources+historyBookSources)for(row in readRows(table.name,"bookId IN ("+scopes.joinToString{ "?" }+")",scopes.toTypedArray())) {
            val sourceKey=identity(table.name,row)
            row["id"]?.jsonPrimitive?.content?.let{names[it]=name(row,names)}
            if(sourceKey in allowed)continue
            fun found(target:UndoRowChange,field:String) {
                val sourceName=name(row,names);val targetName=name(target.after,names)
                conflicts+=UndoConflict("externalReference",objectKey(target.table,target.after),field,JsonNull,JsonPrimitive(sourceName),
                    UndoReference(table.name,sourceKey,sourceName,targetName))
            }
            if(table.name in merchantHistoryTables) {
                byTable["books"].orEmpty().filter{it.after["id"]==row["bookId"]}.forEach{found(it,"bookId")}
                continue
            }
            // Logical associations such as template.merchantId deliberately have no Room FK.
            for(column in table.columns.filter{it.endsWith("Id")})row[column]?.jsonPrimitive?.contentOrNull?.let{id->byId[id]?.let{found(it,column)}}
            for(fk in foreignKeys.filter{it.table==table.name})for(target in byTable.getValue(fk.parent)) {
                if(fk.columns.all{(from,to)->row[from]!=JsonNull&&row[from]==target.after[to]})
                    found(target,fk.columns.keys.filter{it!="bookId"}.joinToString(","))
            }
            fun scan(value:JsonElement,path:String) {
                when(value) {
                    is JsonObject->value.forEach{(key,value)->
                        // An opening baseline is historical evidence, not a live merchant reference.
                        if(table.name=="drafts"&&key=="merchantBaseline")return@forEach
                        byId[key]?.let{found(it,"$path.$key")}
                        if(jsonColumn(key)&&value is JsonPrimitive&&value.isString&&value.content.trimStart().let{it.startsWith("{")||it.startsWith("[")})
                            scan(commandJson.parseToJsonElement(value.content),"$path.$key")
                        else scan(value,"$path.$key")
                    }
                    is JsonArray->value.forEachIndexed{i,v->
                        // Credit-limit proofs also store serialized objects as array elements.
                        val nested=path.substringAfterLast('.') in setOf("basisJson","sourceProof")&&v is JsonPrimitive&&v.isString&&v.content.trimStart().let{it.startsWith("{")||it.startsWith("[")}
                        scan(if(nested)commandJson.parseToJsonElement(v.jsonPrimitive.content)else v,"$path.$i")
                    }
                    is JsonPrimitive->if(value.isString)financialReferenceIds(value.content).forEach{id->byId[id]?.let{found(it,path)}}
                    else->Unit
                }
            }
            // Monthly upload snapshots are historical copies, not live references to ledger objects.
            // Keep their actual book FK above, so undoing book creation cannot erase later history.
            for(column in table.columns.filter{jsonColumn(it)&&table.name!="monthly_reviews"}) {
                val raw=row[column]?.jsonPrimitive?.contentOrNull?:continue
                if(raw.trimStart().let{it.startsWith("{")||it.startsWith("[")})scan(commandJson.parseToJsonElement(raw),column)
            }
        }
        return conflicts.distinctBy{listOf(it.key,it.reference!!.key,it.field)}
    }

    /** Called only by local.restoreExecution, after whole-batch conflict checks in the same outer
     * transaction. UPDATE preserves original rows; DELETE is restricted to recorded null-before rows. */
    suspend fun restore(run:CommandRun):Set<String> {
        val record=migratedRecord(requireNotNull(run.undoRecord))
        check(record.version in 2..3)
        val rows=record.rows
        for(change in rows) {
            check(change.table in deleteOrder&&change.key==key(change.table,change.after)&&(change.table=="books"||change.after["bookId"]==JsonPrimitive(bookId)||change.table=="budgets"&&rows.any{it.table=="books"&&it.before==null&&it.after["id"]==change.after["bookId"]}))
            check(comparableRow(change.table,read(change))==if(change.removed)null else comparableRow(change.table,restorableRow(change.table,change.after)))
        }
        val accounts=rows.flatMap{row->listOfNotNull(
            if(row.table=="accounts")row.after["id"]?.jsonPrimitive?.content else null,
            row.after["accountId"]?.jsonPrimitive?.content,
            row.after["paymentAccountId"]?.jsonPrimitive?.contentOrNull)}.toSet()
        // Deferred constraints handle existing parents temporarily referencing children being
        // restored; the final FK check is mandatory. No broad cascade deletion is used.
        sql.execSQL("PRAGMA defer_foreign_keys=ON")
        for(change in rows.filter{it.before!=null}.sortedByDescending{deleteOrder.indexOf(it.table)}) {
            val before=restoredBefore(change)
            if(before==null) {
                sql.execSQL("DELETE FROM `${change.table}` WHERE "+clause(change.key),values(change.key))
                continue
            }
            if(read(change)==null)sql.execSQL("INSERT INTO `${change.table}` ("+before.keys.joinToString{ "`$it`" }+") VALUES ("+before.keys.joinToString{ "?" }+")",values(before))
            else sql.execSQL("UPDATE `${change.table}` SET "+before.keys.joinToString{ "`$it`=?" }+" WHERE "+clause(change.key),arrayOf(*values(before),*values(change.key)))
            check(read(change)==before)
        }
        // The v3 journal owns the newly written audit rows too. Removing those owned rows restores
        // original event revisions and remembered-save order exactly, so restored refund/prediction
        // facts do not become spuriously stale. The original command and undo audit are retained.
        for(change in rows.filter{it.before==null}.sortedBy{deleteOrder.indexOf(it.table)})
            sql.execSQL("DELETE FROM `${change.table}` WHERE "+clause(change.key),values(change.key))
        check(rows.filter{it.before==null}.all{read(it)==null})
        check(references(rows,allowRecorded=false).isEmpty()){"撤销后仍有残留引用 / 取消後に参照が残っています"}
        sql.query("PRAGMA foreign_key_check").use{check(!it.moveToFirst()){"撤销后存在无效关联 / 取消後に無効な参照があります"}}
        return accounts
    }
}

internal class RestoreExecutionAdapter:CommandAdapter {
    override val capability=capability("local.restoreExecution",buildJsonObject{},emptyMap(),"","Restore recorded pre-batch state; remove only owned creations.","{}")
    override fun validate(input:JsonObject) {require(input.isEmpty())}
    override suspend fun inverse(step:CommandStep,run:CommandRun,before:FinancialReviewState,after:FinancialReviewState)=UndoPreparation(localUndoUnavailable)
    override suspend fun execute(step:CommandStep,run:CommandRun):StepEffect {
        check(step in requireNotNull(run.undoRecord).steps)
        val before=run.state()
        val accounts=CommandUndoJournal(run.ledger,run.bookId).restore(run)
        val after=run.state(refresh=true)
        val ids=run.undoRecord.rows.filter{it.table=="events"}.map{it.after.getValue("id").jsonPrimitive.content}.toSet()
        val events=(before.ledger.events+after.ledger.events).filter{it.id in ids}
        return if(run.undoRecord.rows.any{it.table in setOf("budgets","activities","activity_budgets","activity_reserves","recurrences","occurrences","expected_incomes","treats","book_features")})
            planningEffect(step,run,before,run.undoRecord.rows.filter{it.table=="budgets"}.map{it.after.getValue("month").jsonPrimitive.content}.toSet())
        else run.effect(step,before,after,accounts,ids,events.mapNotNull{it.categoryId}.toSet(),events.map{it.date.take(7)}.toSet())
    }
}
