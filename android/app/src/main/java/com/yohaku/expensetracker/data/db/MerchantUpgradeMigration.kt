package com.yohaku.expensetracker.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.text.Normalizer
import java.util.Locale
import kotlinx.serialization.json.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_39_40 = object:Migration(39,40) {
    override fun migrate(db:SupportSQLiteDatabase) {
        createMerchantUpgradeStructure(db)
        backfillCsvSourcesV40(db)
        organizeMerchantHistoryV40(db)
    }
}

private fun createMerchantUpgradeStructure(db:SupportSQLiteDatabase) {
    db.execSQL("CREATE TABLE `merchant_operations` (`bookId` TEXT NOT NULL, `id` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `type` TEXT NOT NULL, `source` TEXT NOT NULL, `sourceMerchantId` TEXT, `sourceMerchantName` TEXT, `targetMerchantId` TEXT, `targetMerchantName` TEXT, `originalName` TEXT, `sourceBefore_name` TEXT, `sourceBefore_note` TEXT, `sourceBefore_active` INTEGER, `sourceAfter_name` TEXT, `sourceAfter_note` TEXT, `sourceAfter_active` INTEGER, `targetBefore_name` TEXT, `targetBefore_note` TEXT, `targetBefore_active` INTEGER, `targetAfter_name` TEXT, `targetAfter_note` TEXT, `targetAfter_active` INTEGER, `count_affectedRecords` INTEGER NOT NULL, `count_newlyLinkedRecords` INTEGER NOT NULL, `count_renamedRecords` INTEGER NOT NULL, `count_trashedRecords` INTEGER NOT NULL, `count_linkedRefunds` INTEGER NOT NULL, `count_templates` INTEGER NOT NULL, `count_recurrenceRules` INTEGER NOT NULL, `count_savedLocations` INTEGER NOT NULL, `count_pendingPlaces` INTEGER NOT NULL, `count_drafts` INTEGER NOT NULL, `cloudPlanId` TEXT, `cloudExecutionId` TEXT, `cloudCommandId` TEXT, `undoOfOperationId` TEXT, `undoneByOperationId` TEXT, `undoneAt` INTEGER, PRIMARY KEY(`bookId`, `id`), FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)")
    db.execSQL("CREATE INDEX `index_merchant_operations_bookId_timestamp_id` ON `merchant_operations` (`bookId`, `timestamp`, `id`)")
    db.execSQL("CREATE INDEX `index_merchant_operations_bookId_sourceMerchantId` ON `merchant_operations` (`bookId`, `sourceMerchantId`)")
    db.execSQL("CREATE INDEX `index_merchant_operations_bookId_targetMerchantId` ON `merchant_operations` (`bookId`, `targetMerchantId`)")
    db.execSQL("CREATE INDEX `index_merchant_operations_bookId_cloudExecutionId` ON `merchant_operations` (`bookId`, `cloudExecutionId`)")
    db.execSQL("CREATE TABLE `merchant_operation_impacts` (`bookId` TEXT NOT NULL, `operationId` TEXT NOT NULL, `objectType` TEXT NOT NULL, `objectId` TEXT NOT NULL, `oldMerchantName` TEXT, `newMerchantName` TEXT, `oldMerchantId` TEXT, `newMerchantId` TEXT, PRIMARY KEY(`bookId`, `operationId`, `objectType`, `objectId`), FOREIGN KEY(`bookId`, `operationId`) REFERENCES `merchant_operations`(`bookId`, `id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
    db.execSQL("CREATE INDEX `index_merchant_operation_impacts_objectType_objectId` ON `merchant_operation_impacts` (`objectType`, `objectId`)")
    db.execSQL("CREATE INDEX `index_merchant_operation_impacts_bookId_objectType_objectId` ON `merchant_operation_impacts` (`bookId`, `objectType`, `objectId`)")
    db.execSQL("ALTER TABLE csv_batch_events ADD COLUMN sourceMatchKey TEXT DEFAULT NULL")
}

/** Frozen v40 normalization: future search/input rules must not change what restoring a v39
 * backup does. Keep this pure function independent of repository normalization helpers. */
private fun merchantNameV40(value:String)=Normalizer.normalize(value.trim(),Normalizer.Form.NFKC).lowercase(Locale.ROOT)

private data class MerchantV40(val id:String,val name:String)
private data class MerchantRowV40(val id:String,val name:String,val merchantId:String?,val kind:String,val trashed:Boolean=false)
private data class MerchantImpactV40(val type:String,val before:MerchantRowV40,val name:String,val merchantId:String?)

/** Raw cursor -> frozen calculation -> changed columns only. Room and prepareRestore provide
 * the upgrade transaction. One readable HISTORY_CLEANUP summary per changed book; details are
 * also durable markers for pre-v40 undo journals. No revisions, save times or drafts are touched. */
private fun organizeMerchantHistoryV40(db:SupportSQLiteDatabase) {
    val books=db.query("SELECT id FROM books").use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
    val timestamp=System.currentTimeMillis()
    for(book in books) {
        val merchants=db.query("SELECT id,name FROM merchants WHERE bookId=?",arrayOf(book)).use{c->
            buildList{while(c.moveToNext())add(MerchantV40(c.getString(0),c.getString(1)))}}
        val byId=merchants.associateBy{it.id}
        val byName=merchants.groupBy{merchantNameV40(it.name)}
        fun resolve(row:MerchantRowV40):MerchantV40? = byId[row.merchantId]
            ?:if(row.kind=="EXPENSE"&&row.name.isNotBlank())byName[merchantNameV40(row.name)]?.singleOrNull()else null
        val impacts=mutableListOf<MerchantImpactV40>()
        fun update(table:String,id:String,values:ContentValues) {
            if(values.size()>0)db.update(table,SQLiteDatabase.CONFLICT_ABORT,values,"bookId=? AND id=?",arrayOf(book,id))
        }
        val events=db.query("""SELECT e.id,e.merchant,m.merchantId,e.kind,e.deletedAt FROM events e
            LEFT JOIN event_merchants m ON m.bookId=e.bookId AND m.eventId=e.id
            WHERE e.bookId=? AND e.purgedAt IS NULL""",arrayOf(book)).use{c->buildList {
            while(c.moveToNext())add(MerchantRowV40(c.getString(0),c.getString(1),c.getString(2),c.getString(3),!c.isNull(4)))
        }}
        val finalNames=events.associate{it.id to it.name}.toMutableMap()
        fun writeEvent(row:MerchantRowV40,name:String,id:String?) {
            if(row.name==name&&row.merchantId==id)return
            update("events",row.id,ContentValues().apply{if(row.name!=name)put("merchant",name)})
            if(row.merchantId!=id) {
                if(id==null)db.execSQL("DELETE FROM event_merchants WHERE bookId=? AND eventId=?",arrayOf(book,row.id))
                else if(row.merchantId==null)db.execSQL("INSERT INTO event_merchants (bookId,eventId,merchantId) VALUES (?,?,?)",arrayOf(book,row.id,id))
                else db.execSQL("UPDATE event_merchants SET merchantId=? WHERE bookId=? AND eventId=?",arrayOf(id,book,row.id))
            }
            finalNames[row.id]=name
            impacts+=MerchantImpactV40(if(row.kind=="REFUND")"REFUND"else"EVENT",row,name,id)
        }
        for(row in events.filter{it.kind!="REFUND"}) {
            val merchant=resolve(row)
            writeEvent(row,if(row.kind in setOf("EXPENSE","INCOME"))merchant?.name?:row.name else row.name,merchant?.id)
        }
        // Resolve all refund originals once, after purchase display names have reached final state.
        val originals=db.query("SELECT eventId,originalEventId FROM settlements WHERE bookId=? AND originalEventId IS NOT NULL",arrayOf(book)).use{c->
            buildMap{while(c.moveToNext())put(c.getString(0),c.getString(1))}}
        val expenses=events.filter{it.kind=="EXPENSE"}.map{it.id}.toSet()
        for(row in events.filter{it.kind=="REFUND"}) {
            val original=originals[row.id]?.takeIf{it in expenses}
            writeEvent(row,original?.let{finalNames.getValue(it)}?:row.name,null)
        }
        for((table,type) in listOf("entry_templates" to "TEMPLATE","recurrences" to "RECURRENCE")) {
            val rows=db.query("SELECT id,merchant,merchantId,"+(if(table=="recurrences")"'EXPENSE'"else"kind")+",name FROM $table WHERE bookId=?",arrayOf(book)).use{c->buildList {
                while(c.moveToNext())add(MerchantRowV40(c.getString(0),c.getString(1),c.getString(2),c.getString(3)) to c.getString(4))
            }}
            for((row,title) in rows) {
                val merchant=resolve(if(table=="recurrences"&&row.name.isBlank())row.copy(name=title)else row)
                val name=merchant?.name?:row.name
                val id=merchant?.id
                if(row.name==name&&row.merchantId==id)continue
                update(table,row.id,ContentValues().apply {
                    if(row.name!=name)put("merchant",name)
                    if(row.merchantId!=id)put("merchantId",id)
                })
                impacts+=MerchantImpactV40(type,row,name,id)
            }
        }
        if(impacts.isEmpty())continue
        val operation="merchant-history-v40"
        val records=impacts.filter{it.type=="EVENT"}
        db.insert("merchant_operations",SQLiteDatabase.CONFLICT_ABORT,ContentValues().apply {
            put("bookId",book);put("id",operation);put("timestamp",timestamp)
            put("type","HISTORY_CLEANUP");put("source","UPGRADE")
            put("count_affectedRecords",records.size)
            put("count_newlyLinkedRecords",records.count{it.before.merchantId!=it.merchantId&&it.merchantId!=null})
            put("count_renamedRecords",records.count{it.before.name!=it.name})
            put("count_trashedRecords",records.count{it.before.trashed})
            put("count_linkedRefunds",impacts.count{it.type=="REFUND"})
            put("count_templates",impacts.count{it.type=="TEMPLATE"})
            put("count_recurrenceRules",impacts.count{it.type=="RECURRENCE"})
            put("count_savedLocations",0);put("count_pendingPlaces",0);put("count_drafts",0)
        })
        for(impact in impacts)db.insert("merchant_operation_impacts",SQLiteDatabase.CONFLICT_ABORT,ContentValues().apply {
            put("bookId",book);put("operationId",operation);put("objectType",impact.type);put("objectId",impact.before.id)
            put("oldMerchantName",impact.before.name);put("newMerchantName",impact.name)
            put("oldMerchantId",impact.before.merchantId);put("newMerchantId",impact.merchantId)
        })
    }
}

/** Frozen source-key v1, also used to upgrade pre-v40 undo expectations. Revision v1 stores
 * parsed ISO date/kind and absolute amountMinor, not CSV signed text or baseMinor. Never resolve
 * merchant names here. Read fields directly so later Entity defaults/serializers cannot alter it. */
internal fun csvSourceKeyFromRevisionV40(afterJson:String):String {
    val row=Json.parseToJsonElement(afterJson).jsonObject
    return JsonArray(listOf(row.getValue("date").jsonPrimitive.content,
        row.getValue("kind").jsonPrimitive.content,row.getValue("amountMinor").jsonPrimitive.long.toString(),
        row.getValue("note").jsonPrimitive.content,row.getValue("merchant").jsonPrimitive.content).map(::JsonPrimitive)).toString()
}

internal fun csvRevisionSourceKeyV40(db:SupportSQLiteDatabase,bookId:String,eventId:String):String? =
    db.query("SELECT afterJson FROM revisions WHERE bookId=? AND eventId=? AND version=1",arrayOf(bookId,eventId)).use{c->
        if(c.moveToFirst())csvSourceKeyFromRevisionV40(c.getString(0))else null
    }

/** Run before merchant cleanup to keep import provenance separate from merchant attribution.
 * An inner join leaves missing revisions NULL; current event values are never a substitute. */
private fun backfillCsvSourcesV40(db:SupportSQLiteDatabase) {
    db.query("""SELECT c.bookId,c.eventId,r.afterJson FROM csv_batch_events c
        JOIN revisions r ON r.bookId=c.bookId AND r.eventId=c.eventId AND r.version=1
        WHERE c.sourceMatchKey IS NULL""").use{c->
        // Read every row before updating: each update drops a row from this query, and a cursor refilling its
        // window past the first ~2MB would re-run it against the shrunk result and fail.
        buildList{while(c.moveToNext())add(Triple(c.getString(0),c.getString(1),c.getString(2)))}
    }.forEach{(book,event,json)->
        db.execSQL("UPDATE csv_batch_events SET sourceMatchKey=? WHERE bookId=? AND eventId=?",arrayOf(csvSourceKeyFromRevisionV40(json),book,event))
    }
}
