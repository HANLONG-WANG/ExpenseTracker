package com.yohaku.expensetracker.data.command

import android.database.Cursor
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.serialization.json.*

/** Transaction-local ownership journal. Only changed rows in the declared write set are
 * restorable. Deleted relationship rows retain both identity and their exact pre-batch snapshot.
 * Book creation also records its initial budget; no subsequent command may target that book. */
internal class CommandUndoJournal(private val ledger:LedgerRepository,private val bookId:String) {
    private val sql=ledger.db.openHelper.writableDatabase
    private val deleteOrder=listOf("revisions","treat_allocations","reserve_uses","event_merchants","event_places","event_activities",
        "financial_plan_revisions","financial_bills","financial_plans","financial_rules","credit_limit_configs",
        "statement_decisions","occurrences","treats","expense_components","transfers","settlements","postings","events",
        "entry_templates","merchant_locations","merchants","friends","budgets","categories","accounts","books")
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
            val type=commandTableType(table)
            val objectId=row["id"]?.jsonPrimitive?.contentOrNull?:return@mapNotNull null
            if((type to objectId) !in references)null
            else UndoRowChange(table,key(table,row),before[id]?.second,row,removed=id !in after)
        }
    }
    private fun name(row:JsonObject,names:Map<String,String>):String {
        fun text(key:String)=row[key]?.jsonPrimitive?.contentOrNull?.takeIf{it.isNotBlank()}
        return text("name")?:text("title")?:text("originalName")
            ?:text("merchant")?.let{listOfNotNull(text("date"),it).joinToString(" · ")}
            ?:listOf("planId","accountId","eventId").firstNotNullOfOrNull{field->text(field)?.let{names[it]}}
            ?:text("slot")?.let{"草稿 / 下書き"}?:text("date")?:"关联资料 / 関連情報"
    }
    private fun names():Map<String,String> {
        val named=(listOf("accounts","categories","merchants","merchant_locations","friends","entry_templates","financial_plans").flatMap{readRows(it)}+readRows("books","1=1",emptyArray())).associate{it.getValue("id").jsonPrimitive.content to it.getValue("name").jsonPrimitive.content}
        val entries=readRows("events").associate{row->row.getValue("id").jsonPrimitive.content to listOfNotNull(row["date"]?.jsonPrimitive?.contentOrNull,row["merchant"]?.jsonPrimitive?.contentOrNull?.takeIf{it.isNotBlank()},row["note"]?.jsonPrimitive?.contentOrNull?.takeIf{it.isNotBlank()}).joinToString(" · ")}
        return named+entries
    }
    fun removals(changes:List<UndoRowChange>):List<UndoRemoval> {
        val names=names()
        return changes.filter{it.before==null&&!it.removed}.map{change->UndoRemoval(change.table,objectKey(change.table,change.after),name(change.after,names),
            (change.after["number"]?:change.after["version"])?.jsonPrimitive?.intOrNull)}
    }
    fun conflicts(record:UndoRecord):List<UndoConflict> = record.rows.flatMap{change->
        undoDifferences(change.table,objectKey(change.table,change.after),if(change.removed)JsonNull else change.after,read(change)?:JsonNull)
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
        val sources=tables.values.filter{"bookId" in it.columns&&!it.name.startsWith("command_")&&!it.name.startsWith("cloud_")&&it.name!="revisions"&&
            (it.columns.any(::jsonColumn)||it.columns.any{column->column.endsWith("Id")}||foreignKeys.any{fk->fk.table==it.name})}
        val names=names().toMutableMap()
        val conflicts=mutableListOf<UndoConflict>()
        val scopes=(listOf(bookId)+created.filter{it.table=="books"}.map{it.after.getValue("id").jsonPrimitive.content}).distinct()
        for(table in sources)for(row in readRows(table.name,"bookId IN ("+scopes.joinToString{ "?" }+")",scopes.toTypedArray())) {
            val sourceKey=identity(table.name,row)
            row["id"]?.jsonPrimitive?.content?.let{names[it]=name(row,names)}
            if(sourceKey in allowed)continue
            fun found(target:UndoRowChange,field:String) {
                val sourceName=name(row,names);val targetName=name(target.after,names)
                conflicts+=UndoConflict("externalReference",objectKey(target.table,target.after),field,JsonNull,JsonPrimitive(sourceName),
                    UndoReference(table.name,sourceKey,sourceName,targetName))
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
                    is JsonPrimitive->if(value.isString)byId[value.content]?.let{found(it,path)}
                    else->Unit
                }
            }
            for(column in table.columns.filter(::jsonColumn)) {
                val raw=row[column]?.jsonPrimitive?.contentOrNull?:continue
                if(raw.trimStart().let{it.startsWith("{")||it.startsWith("[")})scan(commandJson.parseToJsonElement(raw),column)
            }
        }
        return conflicts.distinctBy{listOf(it.key,it.reference!!.key,it.field)}
    }

    /** Called only by local.restoreExecution, after whole-batch conflict checks in the same outer
     * transaction. UPDATE preserves original rows; DELETE is restricted to recorded null-before rows. */
    suspend fun restore(run:CommandRun):Set<String> {
        val record=requireNotNull(run.undoRecord)
        check(record.version in 2..3)
        val rows=record.rows
        for(change in rows) {
            check(change.table in deleteOrder&&change.key==key(change.table,change.after)&&(change.table=="books"||change.after["bookId"]==JsonPrimitive(bookId)||change.table=="budgets"&&rows.any{it.table=="books"&&it.before==null&&it.after["id"]==change.after["bookId"]}))
            check(read(change)==if(change.removed)null else change.after)
        }
        val accounts=rows.flatMap{row->listOfNotNull(
            if(row.table=="accounts")row.after["id"]?.jsonPrimitive?.content else null,
            row.after["accountId"]?.jsonPrimitive?.content,
            row.after["paymentAccountId"]?.jsonPrimitive?.contentOrNull)}.toSet()
        // Deferred constraints handle existing parents temporarily referencing children being
        // restored; the final FK check is mandatory. No broad cascade deletion is used.
        sql.execSQL("PRAGMA defer_foreign_keys=ON")
        for(change in rows.filter{it.before!=null}.sortedByDescending{deleteOrder.indexOf(it.table)}) {
            val before=requireNotNull(change.before)
            if(change.removed)sql.execSQL("INSERT INTO `${change.table}` ("+before.keys.joinToString{ "`$it`" }+") VALUES ("+before.keys.joinToString{ "?" }+")",values(before))
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
        return run.effect(step,before,after,accounts,ids,events.mapNotNull{it.categoryId}.toSet(),events.map{it.date.take(7)}.toSet())
    }
}
