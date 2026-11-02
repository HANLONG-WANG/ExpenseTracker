package com.yohaku.expensetracker.data.command

import android.database.Cursor
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.serialization.json.*

/** The write set of the three stage-1 account/finance adapters, including prediction archives.
 * Capture once before/after the forward batch. No entry, attachment, cloud or unrelated row can be
 * deleted here. All mutations below are private to an adapter with a persisted undo source. */
internal class CommandUndoJournal(private val ledger:LedgerRepository,private val bookId:String) {
    private val sql=ledger.db.openHelper.writableDatabase
    private val deleteOrder=listOf("financial_plan_revisions","financial_bills","financial_plans","financial_rules","credit_limit_configs","accounts")
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
        for(table in deleteOrder)for(row in readRows(table))put(identity(table,row),table to row)
    }
    fun changes(before:Map<String,Pair<String,JsonObject>>):List<UndoRowChange> {
        val after=snapshot()
        check(before.keys.all{it in after}){"Forward adapters must register any deleted rows explicitly"}
        return after.mapNotNull{(id,pair)->val (table,row)=pair;val old=before[id]?.second
            if(old==row)null else UndoRowChange(table,key(table,row),old,row)
        }
    }
    private fun name(row:JsonObject,names:Map<String,String>):String {
        fun text(key:String)=row[key]?.jsonPrimitive?.contentOrNull?.takeIf{it.isNotBlank()}
        return text("name")?:text("title")?:text("originalName")
            ?:text("merchant")?.let{listOfNotNull(text("date"),it).joinToString(" · ")}
            ?:listOf("planId","accountId","eventId").firstNotNullOfOrNull{field->text(field)?.let{names[it]}}
            ?:text("slot")?:text("date")?:text("id").orEmpty()
    }
    private fun names():Map<String,String> = (readRows("accounts")+readRows("financial_plans")).associate{it.getValue("id").jsonPrimitive.content to it.getValue("name").jsonPrimitive.content}
    fun removals(changes:List<UndoRowChange>):List<UndoRemoval> {
        val names=names()
        return changes.filter{it.before==null}.map{change->UndoRemoval(change.table,objectKey(change.table,change.after),name(change.after,names),
            (change.after["number"]?:change.after["version"])?.jsonPrimitive?.intOrNull)}
    }
    fun conflicts(record:UndoRecord):List<UndoConflict> = record.rows.flatMap{change->
        undoDifferences(change.table,objectKey(change.table,change.after),change.after,read(change)?:JsonNull)
    }+references(record.rows,allowRecorded=true)

    /** Same FK inventory as scopedReferenceCounts/accountUsage, augmented with embedded JSON.
     * A row changed by this batch is internal only because its exact final snapshot is checked above
     * and its pre-batch value is restored. Merely being in the same account never grants ownership. */
    private fun references(changes:List<UndoRowChange>,allowRecorded:Boolean):List<UndoConflict> {
        val created=changes.filter{it.before==null}
        if(created.isEmpty())return emptyList()
        val allowed=if(allowRecorded)changes.map{identity(it.table,it.after)}.toSet()else emptySet()
        val byTable=created.groupBy{it.table}
        val byId=created.mapNotNull{row->row.after["id"]?.jsonPrimitive?.content?.let{it to row}}.toMap()
        val foreignKeys=ledger.scopedForeignKeys().filter{it.parent in byTable}
        fun jsonColumn(name:String)=name.endsWith("Json")||name in setOf("json","encoded","sourceProof")
        val sources=tables.values.filter{"bookId" in it.columns&&!it.name.startsWith("command_")&&!it.name.startsWith("cloud_")&&
            (it.columns.any(::jsonColumn)||foreignKeys.any{fk->fk.table==it.name})}
        val names=names().toMutableMap()
        val conflicts=mutableListOf<UndoConflict>()
        for(table in sources)for(row in readRows(table.name)) {
            val sourceKey=identity(table.name,row)
            row["id"]?.jsonPrimitive?.content?.let{names[it]=name(row,names)}
            if(sourceKey in allowed)continue
            fun found(target:UndoRowChange,field:String) {
                val sourceName=name(row,names);val targetName=name(target.after,names)
                conflicts+=UndoConflict("externalReference",objectKey(target.table,target.after),field,JsonNull,JsonPrimitive(sourceName),
                    UndoReference(table.name,sourceKey,sourceName,targetName))
            }
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
        check(record.version==2)
        val rows=record.rows
        for(change in rows) {
            check(change.table in deleteOrder&&change.key==key(change.table,change.after)&&change.after["bookId"]==JsonPrimitive(bookId))
            check(read(change)==change.after)
        }
        val accounts=rows.flatMap{row->listOfNotNull(
            if(row.table=="accounts")row.after["id"]?.jsonPrimitive?.content else null,
            row.after["accountId"]?.jsonPrimitive?.content,
            row.after["paymentAccountId"]?.jsonPrimitive?.contentOrNull)}.toSet()
        // Restore existing financial parents/children first, dropping their references to new rows.
        for(change in rows.filter{it.before!=null&&it.table!="accounts"}.sortedByDescending{deleteOrder.indexOf(it.table)}) {
            val before=requireNotNull(change.before)
            sql.execSQL("UPDATE `${change.table}` SET "+before.keys.joinToString{ "`$it`=?" }+" WHERE "+clause(change.key),arrayOf(*values(before),*values(change.key)))
            check(read(change)==before)
        }
        // Child → parent, including the exact revision rows created by this execution.
        for(change in rows.filter{it.before==null}.sortedBy{deleteOrder.indexOf(it.table)})
            sql.execSQL("DELETE FROM `${change.table}` WHERE "+clause(change.key),values(change.key))
        for(change in rows.filter{it.before!=null&&it.table=="accounts"}) {
            val before=requireNotNull(change.before)
            ledger.saveAccount(bookId,before.getValue("name").jsonPrimitive.content,AccountType.valueOf(before.getValue("type").jsonPrimitive.content),
                Currency.valueOf(before.getValue("currency").jsonPrimitive.content),Money(Currency.valueOf(before.getValue("currency").jsonPrimitive.content),before.getValue("openingMinor").jsonPrimitive.long),
                before.getValue("openingDate").jsonPrimitive.content,before.getValue("id").jsonPrimitive.content)
            check(read(change)==before)
        }
        check(rows.filter{it.before==null}.all{read(it)==null})
        check(references(rows,allowRecorded=false).isEmpty()){"撤销后仍有残留引用 / 取消後に参照が残っています"}
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
        return run.effect(step,before,run.state(refresh=true),accounts)
    }
}
