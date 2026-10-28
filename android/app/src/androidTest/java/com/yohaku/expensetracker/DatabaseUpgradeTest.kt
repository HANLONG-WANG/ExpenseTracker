package com.yohaku.expensetracker

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.LedgerRepository
import com.yohaku.expensetracker.data.repository.expectedMoney
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseUpgradeTest {
    @Test fun version32KeepsLimitRecoveryOnPaymentDay()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="upgrade-credit-options-32-33.db";context.deleteDatabase(name)
        var db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).build()
        try {
            val repo=LedgerRepository(db);val book=repo.createBook("カード",com.yohaku.expensetracker.domain.model.Currency.JPY)
            val card=repo.saveAccount(book,"カード",com.yohaku.expensetracker.domain.model.AccountType.CREDIT,com.yohaku.expensetracker.domain.model.Currency.JPY,com.yohaku.expensetracker.domain.model.Money(com.yohaku.expensetracker.domain.model.Currency.JPY,0),"2026-01-01")
            db.creditLimitDao().insertConfig(CreditLimitConfigEntity(book,"limit",card,1,"2026-01-01","JPY",10000,null,null,"TOTAL_ONLY",null,"2026-01-01",0,null,null,"{\"movements\":[]}","PAYMENT_DATE","PRINCIPAL_ONLY","TRANSACTION_DATE","Asia/Tokyo",0))
            db.close()
            val old=SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE)
            try {restoreFixtureTable(old,"room-v32-schema.json","events");restoreFixtureTable(old,"room-v32-schema.json","credit_limit_configs");old.version=32}finally{old.close()}
            db=com.yohaku.expensetracker.data.backup.openYohakuDatabase(context,name)
            val config=db.creditLimitDao().graph(book).first()!!.configs.single()
            assertEquals(33,db.openHelper.readableDatabase.version);assertEquals(0,config.recoveryDelayDays);assertEquals("PAYMENT_DATE",config.recovery)
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use{assertFalse(it.moveToFirst())}
        }finally{db.close();context.deleteDatabase(name)}
    }

    @Test fun version31BackfillsCategoryIcons()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="upgrade-icons-31-32.db"
        context.deleteDatabase(name)
        val old=SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name),null)
        try {
            val schema=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("room-v31-schema.json").bufferedReader().readText()).getJSONObject("database")
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val entity=entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",entity.getString("tableName")))
                val indices=entity.optJSONArray("indices")
                for(j in 0 until (indices?.length()?:0))old.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",entity.getString("tableName")))
            }
            val queries=schema.getJSONArray("setupQueries")
            for(i in 0 until queries.length())old.execSQL(queries.getString(i))
            old.execSQL("INSERT INTO books (id,name,currency,startDate,theme,language,initialized,budgetMinor,setupStep,archived,deletedAt) VALUES ('csv-book','Existing','JPY','2024-01-01','journal','ja',1,1000,4,0,NULL)")
            old.execSQL("INSERT INTO categories (bookId,id,name,kind,parentId,favorite,active) VALUES ('csv-book','coffee','咖啡','EXPENSE',NULL,0,1),('csv-book','misc','生活杂项','EXPENSE',NULL,0,1),('csv-book','transport','交通','EXPENSE',NULL,0,1),('csv-book','train','电车','EXPENSE','transport',0,1),('csv-book','unmatched','未命名','EXPENSE',NULL,0,1)")
            old.version=31
        }finally{old.close()}
        val db=com.yohaku.expensetracker.data.backup.openYohakuDatabase(context,name)
        try {
            assertEquals("Existing",db.dao().book("csv-book")!!.name)
            assertEquals(DATABASE_VERSION,db.openHelper.readableDatabase.version)
            assertEquals("coffee",db.dao().category("csv-book","coffee")!!.icon)
            assertEquals("category",db.dao().category("csv-book","misc")!!.icon)
            assertEquals("train",db.dao().category("csv-book","transport")!!.icon)
            assertNull(db.dao().category("csv-book","train")!!.icon)
            assertNull(db.dao().category("csv-book","unmatched")!!.icon)
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use{assertFalse(it.moveToFirst())}
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun version29AddsCsvTablesAndKeepsExistingLedger()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="upgrade-csv-29-30.db"
        context.deleteDatabase(name)
        val old=SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name),null)
        try {
            val schema=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("room-v29-schema.json").bufferedReader().readText()).getJSONObject("database")
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val entity=entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",entity.getString("tableName")))
                val indices=entity.optJSONArray("indices")
                for(j in 0 until (indices?.length()?:0))old.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",entity.getString("tableName")))
            }
            val queries=schema.getJSONArray("setupQueries")
            for(i in 0 until queries.length())old.execSQL(queries.getString(i))
            old.execSQL("INSERT INTO books (id,name,currency,startDate,theme,language,initialized,budgetMinor,setupStep,archived,deletedAt) VALUES ('csv-book','Existing','JPY','2024-01-01','journal','ja',1,1000,4,0,NULL)")
            old.version=29
        }finally{old.close()}
        val db=com.yohaku.expensetracker.data.backup.openYohakuDatabase(context,name)
        try {
            assertEquals("Existing",db.dao().book("csv-book")!!.name)
            assertEquals(DATABASE_VERSION,db.openHelper.readableDatabase.version)
            assertTrue(db.csvImportDao().batches("csv-book").first().isEmpty())
            db.csvImportDao().putProfile(CsvProfileEntity("csv-book","header","{}"))
            assertEquals("{}",db.csvImportDao().profile("csv-book","header")!!.settingsJson)
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use{assertFalse(it.moveToFirst())}
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun firstVersionUpgradesWithoutErasingBookOrPostings()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val name="upgrade-verification.db"
        context.deleteDatabase(name)
        val schema=JSONObject(instrumentation.context.assets.open("room-v1-schema.json").bufferedReader().readText()).getJSONObject("database")
        val old=SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name),null)
        try {
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val entity=entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",entity.getString("tableName")))
                val indices=entity.optJSONArray("indices")
                for(j in 0 until (indices?.length() ?: 0))old.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",entity.getString("tableName")))
            }
            val setup=schema.getJSONArray("setupQueries")
            for(i in 0 until setup.length())old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO books VALUES ('b','生活','JPY','2026-10-01','journal','ja',1,15000)")
            old.execSQL("INSERT INTO accounts VALUES ('b','a','现金','CASH','JPY',10000,'2026-10-01',1)")
            old.execSQL("INSERT INTO categories VALUES ('b','c','餐饮','EXPENSE',NULL,0,1)")
            old.execSQL("INSERT INTO events VALUES ('b','e','EXPENSE','a','c',500,'JPY',500,'2026-10-13','喫茶と余白','',0,1,NULL)")
            old.execSQL("INSERT INTO postings VALUES ('b','p','e','a',-500,'JPY','2026-10-13',1)")
            old.execSQL("DROP TABLE IF EXISTS financial_drafts");restoreBookLifecycleFixture(old);old.version=1
        }finally{old.close()}
        val db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).addMigrations(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4,MIGRATION_4_5,MIGRATION_5_6,MIGRATION_6_7,MIGRATION_7_8,MIGRATION_8_9,com.yohaku.expensetracker.data.db.MIGRATION_9_10,com.yohaku.expensetracker.data.db.MIGRATION_10_11,com.yohaku.expensetracker.data.db.MIGRATION_11_12,com.yohaku.expensetracker.data.db.MIGRATION_12_13,com.yohaku.expensetracker.data.db.MIGRATION_13_14,com.yohaku.expensetracker.data.db.MIGRATION_14_15,com.yohaku.expensetracker.data.db.MIGRATION_15_16,com.yohaku.expensetracker.data.db.MIGRATION_16_17,com.yohaku.expensetracker.data.db.MIGRATION_17_18,com.yohaku.expensetracker.data.db.MIGRATION_18_19,MIGRATION_19_20,MIGRATION_20_21,MIGRATION_21_22,MIGRATION_22_23,com.yohaku.expensetracker.data.db.MIGRATION_23_24,com.yohaku.expensetracker.data.db.MIGRATION_24_25,com.yohaku.expensetracker.data.db.MIGRATION_25_26,com.yohaku.expensetracker.data.db.MIGRATION_26_27,com.yohaku.expensetracker.data.db.MIGRATION_27_28,com.yohaku.expensetracker.data.db.MIGRATION_28_29,com.yohaku.expensetracker.data.db.MIGRATION_29_30,com.yohaku.expensetracker.data.db.MIGRATION_30_31,MIGRATION_31_32,MIGRATION_32_33).build()
        try {
            val repo=LedgerRepository(db)
            val book=repo.dao.book("b")!!
            val snapshot=repo.snapshot(book).first()
            assertEquals(2,book.setupStep);assertEquals("journal",book.theme);assertEquals("ja",book.language)
            assertEquals(15000L,book.budgetMinor)
            assertEquals("e",snapshot.events.single().id)
            assertEquals("c",snapshot.events.single().categoryId)
            assertEquals(9500L,snapshot.balance(snapshot.accounts.single()))
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun fourthVersionKeepsOriginalPredictionAndSettlementCurrency()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val name="upgrade-currency-verification.db"
        context.deleteDatabase(name)
        val schema=JSONObject(instrumentation.context.assets.open("room-v4-schema.json").bufferedReader().readText()).getJSONObject("database")
        val old=SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name),null)
        try {
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val entity=entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",entity.getString("tableName")))
                val indices=entity.optJSONArray("indices")
                for(j in 0 until (indices?.length() ?: 0))old.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",entity.getString("tableName")))
            }
            val setup=schema.getJSONArray("setupQueries")
            for(i in 0 until setup.length())old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO books VALUES ('b','外币','JPY','2026-09-01','magazine','zh',1,100000,4)")
            old.execSQL("INSERT INTO accounts VALUES ('b','a','卡','CREDIT','JPY',0,'2026-09-01',1)")
            old.execSQL("INSERT INTO categories VALUES ('b','c','购物','EXPENSE',NULL,0,1)")
            old.execSQL("INSERT INTO events VALUES ('b','e','EXPENSE','a','c',10000,'USD',15100,'2026-09-10','Shop','',0,2,NULL,1)")
            old.execSQL("INSERT INTO postings VALUES ('b','p','e','a',15100,'JPY','2026-09-12',1)")
            old.execSQL("INSERT INTO settlements VALUES ('b','e',15100,'JPY',1,'2026-09-12','manual','2026-09-10',15000,15000,NULL)")
            old.execSQL("DROP TABLE IF EXISTS financial_drafts");restoreBookLifecycleFixture(old);old.version=4
        }finally{old.close()}
        val db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).addMigrations(MIGRATION_4_5,MIGRATION_5_6,MIGRATION_6_7,MIGRATION_7_8,MIGRATION_8_9,com.yohaku.expensetracker.data.db.MIGRATION_9_10,com.yohaku.expensetracker.data.db.MIGRATION_10_11,com.yohaku.expensetracker.data.db.MIGRATION_11_12,com.yohaku.expensetracker.data.db.MIGRATION_12_13,com.yohaku.expensetracker.data.db.MIGRATION_13_14,com.yohaku.expensetracker.data.db.MIGRATION_14_15,com.yohaku.expensetracker.data.db.MIGRATION_15_16,com.yohaku.expensetracker.data.db.MIGRATION_16_17,com.yohaku.expensetracker.data.db.MIGRATION_17_18,com.yohaku.expensetracker.data.db.MIGRATION_18_19,MIGRATION_19_20,MIGRATION_20_21,MIGRATION_21_22,MIGRATION_22_23,com.yohaku.expensetracker.data.db.MIGRATION_23_24,com.yohaku.expensetracker.data.db.MIGRATION_24_25,com.yohaku.expensetracker.data.db.MIGRATION_25_26,com.yohaku.expensetracker.data.db.MIGRATION_26_27,com.yohaku.expensetracker.data.db.MIGRATION_27_28,com.yohaku.expensetracker.data.db.MIGRATION_28_29,com.yohaku.expensetracker.data.db.MIGRATION_29_30,com.yohaku.expensetracker.data.db.MIGRATION_30_31,MIGRATION_31_32,MIGRATION_32_33).build()
        try {
            val repo=LedgerRepository(db)
            val snapshot=repo.snapshot(repo.dao.book("b")!!).first()
            assertEquals(15100L,snapshot.balance(snapshot.accounts.single()))
            assertEquals("USD",snapshot.events.single().currency)
            assertEquals(15000L,snapshot.settlements.single().expectedMoney().minor)
            assertEquals("JPY",snapshot.settlements.single().expectedMoney().currency.name)
            assertEquals("",snapshot.settlements.single().manualRate)
        }finally{db.close();context.deleteDatabase(name)}
    }

}
