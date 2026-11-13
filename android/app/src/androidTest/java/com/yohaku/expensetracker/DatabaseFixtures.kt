package com.yohaku.expensetracker

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject

/** Rebuild one fixture table from its exported old schema, preserving every old column. */
internal fun restoreFixtureTable(db:SQLiteDatabase,asset:String,name:String) {
    val schema=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open(asset).bufferedReader().readText()).getJSONObject("database")
    val entities=schema.getJSONArray("entities")
    val table=(0 until entities.length()).map{entities.getJSONObject(it)}.first{it.getString("tableName")==name}
    val fields=table.getJSONArray("fields")
    val columns=(0 until fields.length()).joinToString(","){"`${fields.getJSONObject(it).getString("columnName")}`"}
    db.execSQL("CREATE TEMP TABLE fixture_backup AS SELECT $columns FROM `$name`")
    db.execSQL("DROP TABLE `$name`")
    db.execSQL(table.getString("createSql").replace("\${TABLE_NAME}",name))
    val indices=table.optJSONArray("indices")
    for(i in 0 until (indices?.length()?:0))db.execSQL(indices!!.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}",name))
    db.execSQL("INSERT INTO `$name` ($columns) SELECT $columns FROM fixture_backup")
    db.execSQL("DROP TABLE fixture_backup")
}

/** Rebuild every table at an exported old version, keeping data in shared columns, so later migrations all apply. */
internal fun restoreFixtureDatabase(db:SQLiteDatabase,version:Int,asset:String="room-v$version-schema.json") {
    val schema=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open(asset).bufferedReader().readText()).getJSONObject("database")
    val entities=schema.getJSONArray("entities").let{a->(0 until a.length()).map{a.getJSONObject(it)}}
    val existing=db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table','android_metadata')",null).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
    (existing-entities.map{it.getString("tableName")}.toSet()).forEach{db.execSQL("DROP TABLE `$it`")}
    for(table in entities) {
        val name=table.getString("tableName")
        val fields=table.getJSONArray("fields").let{a->(0 until a.length()).map{a.getJSONObject(it).getString("columnName")}}
        val present=db.rawQuery("PRAGMA table_info(`$name`)",null).use{c->buildSet{while(c.moveToNext())add(c.getString(1))}}
        val columns=fields.filter{it in present}.joinToString(","){"`$it`"}
        if(present.isNotEmpty()){db.execSQL("CREATE TEMP TABLE fixture_backup AS SELECT $columns FROM `$name`");db.execSQL("DROP TABLE `$name`")}
        db.execSQL(table.getString("createSql").replace("\${TABLE_NAME}",name))
        table.optJSONArray("indices")?.let{a->(0 until a.length()).forEach{db.execSQL(a.getJSONObject(it).getString("createSql").replace("\${TABLE_NAME}",name))}}
        if(present.isNotEmpty()){db.execSQL("INSERT INTO `$name` ($columns) SELECT $columns FROM fixture_backup");db.execSQL("DROP TABLE fixture_backup")}
    }
    db.execSQL("UPDATE room_master_table SET identity_hash=? WHERE id=42",arrayOf(schema.getString("identityHash")))
    db.version=version
}

/** Representative pre-upgrade data shared by direct-open and actual backup preparation tests. */
internal fun merchantV39Fixture(db:SQLiteDatabase) {
    val schema=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("com.yohaku.expensetracker.data.db.YohakuDatabase/39.json").bufferedReader().readText()).getJSONObject("database")
    val entities=schema.getJSONArray("entities")
    for(i in 0 until entities.length()) {
        val entity=entities.getJSONObject(i);val table=entity.getString("tableName")
        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",table))
        entity.optJSONArray("indices")?.let{a->for(j in 0 until a.length())db.execSQL(a.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))}
    }
    schema.getJSONArray("setupQueries").let{a->for(i in 0 until a.length())db.execSQL(a.getString(i))}
    for(book in listOf("b","archived","trashed","unchanged")) {
        db.execSQL("INSERT INTO books (id,name,currency,startDate,theme,language,initialized,budgetMinor,setupStep,archived,deletedAt) VALUES (?,?,'JPY','2026-01-01','journal','ja',1,NULL,4,?,?)",arrayOf<Any?>(book,book,if(book=="archived")1 else 0,if(book=="trashed")1L else null))
        db.execSQL("INSERT INTO accounts (bookId,id,name,type,currency,openingMinor,openingDate,active) VALUES (?,'bank','Bank','BANK','JPY',1000,'2026-01-01',1)",arrayOf(book))
        db.execSQL("INSERT INTO merchants (bookId,id,name,note,active) VALUES (?,'m','Ｃａｆｅ','',0)",arrayOf(book))
        if(book!="unchanged")db.execSQL("INSERT INTO events (bookId,id,kind,accountId,amountMinor,currency,baseMinor,date,merchant,note,historicalIncluded,revision) VALUES (?,'e','EXPENSE','bank',100,'JPY',100,'2026-01-01',' cafe ','',0,3)",arrayOf(book))
    }
    db.execSQL("INSERT INTO categories (bookId,id,name,kind,parentId,favorite,active,icon) VALUES ('b','food','Food','EXPENSE',NULL,0,1,NULL),('b','income','Income','INCOME',NULL,0,1,NULL)")
    db.execSQL("UPDATE events SET categoryId='food' WHERE bookId='b'")
    db.execSQL("INSERT INTO merchants (bookId,id,name,note,active) VALUES ('b','d1','Shop','',1),('b','d2','ＳＨＯＰ','',1)")
    for((id,kind,name) in listOf(Triple("refund","REFUND","old refund"),Triple("duplicate","EXPENSE","shop"),Triple("explicit","EXPENSE","stale"),Triple("income","INCOME","cafe"),Triple("linked-income","INCOME","old income"),Triple("invalid","EXPENSE","Unknown"),Triple("trash","EXPENSE","cafe"),Triple("purged","EXPENSE","cafe"))) {
        db.execSQL("INSERT INTO events (bookId,id,kind,accountId,categoryId,amountMinor,currency,baseMinor,date,merchant,note,historicalIncluded,revision,deletedAt,purgedAt) VALUES ('b',?,?,'bank',?,20,'JPY',20,'2026-01-02',?,'',0,2,?,?)",arrayOf<Any?>(id,kind,if(kind=="INCOME")"income"else"food",name,if(id in listOf("trash","purged","refund"))1L else null,if(id=="purged")2L else null))
    }
    db.execSQL("INSERT INTO event_merchants VALUES ('b','explicit','d2'),('b','linked-income','m'),('b','invalid','missing')")
    db.execSQL("INSERT INTO settlements (bookId,eventId,accountMinor,accountCurrency,confirmed,settlementDate,rateSource,rateDate,expectedAccountMinor,expectedBaseMinor,originalEventId) VALUES ('b','refund',20,'JPY',1,'2026-01-02','manual','2026-01-02',20,20,'e')")
    db.execSQL("INSERT INTO postings (bookId,id,eventId,accountId,deltaMinor,currency,date,confirmed) VALUES ('b','p','e','bank',-100,'JPY','2026-01-01',1)")
    db.execSQL("INSERT INTO event_places VALUES ('b','e',35,139,5)")
    for((id,kind,merchant,link) in listOf(listOf("template","EXPENSE","cafe",null),listOf("duplicate-template","EXPENSE","shop",null),listOf("income-template","INCOME","cafe",null),listOf("invalid-template","EXPENSE","Unknown","missing"))) {
        db.execSQL("INSERT INTO entry_templates (bookId,id,name,kind,categoryId,accountId,currency,merchant,merchantId,note,sortOrder,createdAt) VALUES ('b',?,'Template',?,?,'bank','JPY',?,?,'',0,1)",arrayOf<Any?>(id,kind,if(kind=="INCOME")"income"else"food",merchant,link))
    }
    db.execSQL("INSERT INTO recurrences (bookId,id,name,accountId,categoryId,currency,amountMinor,baseMinor,estimated,frequency,anchorDate,active,merchant,merchantId) VALUES ('b','rule','cafe','bank','food','JPY',100,100,0,'MONTHLY','2026-01-01',1,'',NULL),('b','invalid-rule','Rule','bank','food','JPY',100,100,0,'MONTHLY','2026-01-01',1,'Unknown','missing')")
    db.execSQL("INSERT INTO drafts VALUES ('b','{\"merchant\":\"cafe\"}',7)")
    db.execSQL("INSERT INTO revisions (bookId,id,eventId,version,timestamp,afterJson) VALUES ('b','revision','e',3,7,'{}')")
    db.version=39
}

internal suspend fun assertMerchantV40Fixture(db:com.yohaku.expensetracker.data.db.YohakuDatabase) {
    val dao=db.dao();val history=db.merchantHistoryDao()
    org.junit.Assert.assertEquals(com.yohaku.expensetracker.data.db.DATABASE_VERSION,db.openHelper.readableDatabase.version)
    for(book in listOf("b","archived","trashed")) {
        org.junit.Assert.assertEquals("m",dao.eventMerchant(book,"e")!!.merchantId)
        org.junit.Assert.assertEquals("Ｃａｆｅ",dao.event(book,"e")!!.merchant)
        org.junit.Assert.assertEquals(com.yohaku.expensetracker.domain.merchant.MerchantOperationType.HISTORY_CLEANUP,history.page(book,100).single().type)
    }
    org.junit.Assert.assertTrue(history.page("unchanged",100).isEmpty())
    org.junit.Assert.assertEquals("m",dao.eventMerchant("b","trash")!!.merchantId)
    org.junit.Assert.assertEquals("d2",dao.eventMerchant("b","explicit")!!.merchantId)
    org.junit.Assert.assertEquals("ＳＨＯＰ",dao.event("b","explicit")!!.merchant)
    org.junit.Assert.assertEquals("Ｃａｆｅ",dao.event("b","linked-income")!!.merchant)
    org.junit.Assert.assertEquals("Ｃａｆｅ",dao.event("b","refund")!!.merchant)
    for(id in listOf("duplicate","income","invalid","purged","refund"))org.junit.Assert.assertNull(dao.eventMerchant("b",id))
    org.junit.Assert.assertEquals("cafe",dao.event("b","income")!!.merchant)
    val templates=dao.templates("b").associateBy{it.id}
    org.junit.Assert.assertEquals("m",templates.getValue("template").merchantId)
    org.junit.Assert.assertEquals("Ｃａｆｅ",templates.getValue("template").merchant)
    for(id in listOf("duplicate-template","income-template","invalid-template"))org.junit.Assert.assertNull(templates.getValue(id).merchantId)
    val operation=history.page("b",100).single()
    org.junit.Assert.assertEquals(com.yohaku.expensetracker.domain.merchant.MerchantOperationSource.UPGRADE,operation.source)
    val impacts=history.impacts("b",operation.id,100)
    org.junit.Assert.assertEquals(10,impacts.size)
    org.junit.Assert.assertEquals(" cafe ",impacts.single{it.objectId=="e"}.oldMerchantName)
    org.junit.Assert.assertEquals("missing",impacts.single{it.objectId=="invalid"}.oldMerchantId)
    fun scalar(query:String)=db.openHelper.readableDatabase.query(query).use{it.moveToFirst();it.getString(0)}
    org.junit.Assert.assertEquals("cafe",scalar("SELECT merchant FROM events WHERE bookId='b' AND id='purged'"))
    org.junit.Assert.assertEquals("m",scalar("SELECT merchantId FROM recurrences WHERE bookId='b' AND id='rule'"))
    org.junit.Assert.assertEquals("Ｃａｆｅ",scalar("SELECT merchant FROM recurrences WHERE bookId='b' AND id='rule'"))
    org.junit.Assert.assertNull(scalar("SELECT merchantId FROM recurrences WHERE bookId='b' AND id='invalid-rule'"))
    org.junit.Assert.assertEquals("3",scalar("SELECT COUNT(*) FROM merchants WHERE bookId='b'"))
    org.junit.Assert.assertEquals("9",scalar("SELECT COUNT(*) FROM events WHERE bookId='b'"))
    org.junit.Assert.assertEquals("260",scalar("SELECT SUM(amountMinor) FROM events WHERE bookId='b'"))
    org.junit.Assert.assertEquals("-100",scalar("SELECT SUM(deltaMinor) FROM postings WHERE bookId='b'"))
    org.junit.Assert.assertEquals("3",scalar("SELECT revision FROM events WHERE bookId='b' AND id='e'"))
    org.junit.Assert.assertEquals("7",scalar("SELECT timestamp FROM revisions WHERE bookId='b'"))
    org.junit.Assert.assertEquals("{\"merchant\":\"cafe\"}",scalar("SELECT json FROM drafts WHERE bookId='b'"))
    org.junit.Assert.assertEquals("1",scalar("SELECT COUNT(*) FROM event_places WHERE bookId='b'"))
    db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use{org.junit.Assert.assertFalse(it.moveToFirst())}
}
