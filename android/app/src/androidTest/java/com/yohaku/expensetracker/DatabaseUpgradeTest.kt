package com.yohaku.expensetracker

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.merchant.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseUpgradeTest {

    @Test fun version39AddsHistoryAndCleanupWithAtomicHistoryAndBookPurge()=runBlocking<Unit> {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="upgrade-merchants-39-40.db";context.deleteDatabase(name)
        val old=SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name),null)
        try {
            val schema=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("com.yohaku.expensetracker.data.db.YohakuDatabase/39.json").bufferedReader().readText()).getJSONObject("database")
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val entity=entities.getJSONObject(i);val table=entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",table))
                val indices=entity.optJSONArray("indices")
                for(j in 0 until (indices?.length()?:0))old.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
            }
            val queries=schema.getJSONArray("setupQueries")
            for(i in 0 until queries.length())old.execSQL(queries.getString(i))
            old.execSQL("INSERT INTO books (id,name,currency,startDate,theme,language,initialized,budgetMinor,setupStep,archived,deletedAt) VALUES ('b','Existing','JPY','2026-01-01','journal','ja',1,NULL,4,0,NULL)")
            old.execSQL("INSERT INTO accounts (bookId,id,name,type,currency,openingMinor,openingDate,active) VALUES ('b','bank','Bank','BANK','JPY',1000,'2026-01-01',1)")
            old.execSQL("INSERT INTO events (bookId,id,kind,accountId,categoryId,amountMinor,currency,baseMinor,date,merchant,note,historicalIncluded,revision) VALUES ('b','e','EXPENSE','bank',NULL,100,'JPY',100,'2026-01-01','Coffee','',0,1)")
            old.execSQL("INSERT INTO merchants (bookId,id,name,note,active) VALUES ('b','m','Coffee','old note',0)")
            old.execSQL("INSERT INTO csv_batches (bookId,id,fileName,sha256,createdAt,rowCount,importedCount,skippedCount,accountId,undoGroupId) VALUES ('b','batch','input.csv','hash',1,1,1,0,'bank',NULL)")
            old.execSQL("INSERT INTO csv_batch_events (bookId,eventId,batchId) VALUES ('b','e','batch')")
            old.version=39
        }finally{old.close()}
        // Same migration entry point as prepareRestore's staged database copy.
        val db=com.yohaku.expensetracker.data.backup.openYohakuDatabase(context,name)
        try {
            val repo=LedgerRepository(db);val dao=db.merchantHistoryDao()
            assertEquals(DATABASE_VERSION,db.openHelper.readableDatabase.version)
            assertEquals(100L,repo.dao.event("b","e")!!.amountMinor)
            assertEquals("Coffee",repo.dao.event("b","e")!!.merchant)
            assertEquals("m",repo.dao.eventMerchant("b","e")!!.merchantId)
            assertFalse(repo.dao.merchant("b","m")!!.active)
            db.openHelper.readableDatabase.query("SELECT sourceMatchKey FROM csv_batch_events").use{assertTrue(it.moveToFirst());assertTrue(it.isNull(0))}
            assertEquals(1,dao.page("b",10).size)
            val operation=MerchantOperationEntity("b","operation",System.currentTimeMillis()+1000,MerchantOperationType.RENAME,MerchantOperationSource.LOCAL,
                sourceMerchantId="m",sourceMerchantName="Coffee",targetMerchantId="m",targetMerchantName="Tea",
                sourceBefore=MerchantProfile("Coffee","old note",false),sourceAfter=MerchantProfile("Tea","old note",false),
                counts=MerchantImpactCounts(affectedRecords=1,renamedRecords=1))
            val impact=MerchantOperationImpactEntity("b",operation.id,MerchantObjectType.EVENT,"e","Coffee","Tea","m","m")
            db.withTransaction {
                assertFalse(repo.writeMerchantOperationInTransaction(operation,listOf(impact),changed=false))
            }
            assertEquals(1,dao.page("b",10).size)
            val failed=runCatching { db.withTransaction {
                repo.dao.putEvent(repo.dao.event("b","e")!!.copy(merchant="Tea"))
                repo.writeMerchantOperationInTransaction(operation,listOf(impact),changed=true)
                error("Rollback business and history together")
            } }
            assertTrue(failed.isFailure);assertEquals(1,dao.page("b",10).size);assertTrue(dao.impacts("b",operation.id,10).isEmpty())
            assertEquals("Coffee",repo.dao.event("b","e")!!.merchant)
            db.withTransaction { repo.writeMerchantOperationInTransaction(operation,listOf(impact),changed=true) }
            assertEquals(operation,dao.page("b",1).single());assertEquals(MerchantOperationType.HISTORY_CLEANUP,dao.page("b",1,1).single().type)
            assertEquals(operation,dao.forMerchant("b","m",10).single())
            assertEquals(operation,dao.forObject("b",MerchantObjectType.EVENT,"e",10).first())
            assertEquals(impact,dao.impacts("b",operation.id,10).single())
            db.withTransaction {
                repo.dao.clearEventMerchant("b","e")
                db.openHelper.writableDatabase.execSQL("DELETE FROM events WHERE bookId='b' AND id='e'")
                repo.dao.deleteMerchant("b","m")
            }
            assertEquals(operation,dao.forObject("b",MerchantObjectType.EVENT,"e",10).first())
            val other=repo.createBook("Other",com.yohaku.expensetracker.domain.model.Currency.JPY)
            db.withTransaction { repo.writeMerchantOperationInTransaction(operation.copy(bookId=other),listOf(impact.copy(bookId=other)),true) }
            assertTrue(repo.commitBookOperation(repo.previewBookOperation("b",BookOperation.TRASH)))
            assertTrue(repo.commitBookOperation(repo.previewBookOperation("b",BookOperation.PURGE)))
            assertTrue(dao.page("b",10).isEmpty());assertTrue(dao.impacts("b",operation.id,10).isEmpty())
            assertEquals(1,dao.page(other,10).size);assertEquals(1,dao.impacts(other,operation.id,10).size)
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use{assertFalse(it.moveToFirst())}
        }finally{db.close();context.deleteDatabase(name)}
    }

    @Test fun version39OrganizesHistoryAndRetainsPendingPlaces()=runBlocking<Unit> {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="merchant-cleanup-v39.db";context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name),null).use{merchantV39Fixture(it)}
        val db=com.yohaku.expensetracker.data.backup.openYohakuDatabase(context,name)
        try {
            assertMerchantV40Fixture(db)
            val repo=LedgerRepository(db);val s=repo.snapshot(repo.dao.book("b")!!).first()
            assertEquals(900L,s.balance(s.accounts.single()))
            assertEquals(1,s.recordedPlaces("Ｃａｆｅ").size)
            val count=db.merchantHistoryDao().impacts("b","merchant-history-v40",100).size
            repo.saveEntry("b",s.editingEntryDraft(s.events.single{it.id=="e"}).copy(note="ordinary save"))
            val after=repo.snapshot(repo.dao.book("b")!!).first()
            assertEquals(s.eventPlaces,after.eventPlaces)
            assertEquals(count,db.merchantHistoryDao().impacts("b","merchant-history-v40",100).size)
        }finally{db.close();context.deleteDatabase(name)}
    }

    @Test fun version38AddsBudgetExclusionDefaultFalseToEventsAndTemplates()=runBlocking<Unit> {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="upgrade-budget-38-39.db";context.deleteDatabase(name)
        val initial=com.yohaku.expensetracker.data.backup.openYohakuDatabase(context,name)
        val repo=LedgerRepository(initial)
        val book=repo.createBook("Existing",com.yohaku.expensetracker.domain.model.Currency.JPY)
        val account=repo.saveAccount(book,"Bank",com.yohaku.expensetracker.domain.model.AccountType.BANK,com.yohaku.expensetracker.domain.model.Currency.JPY,com.yohaku.expensetracker.domain.model.Money(com.yohaku.expensetracker.domain.model.Currency.JPY,1000),"2026-01-01")
        val category=repo.saveCategory(book,"Food",com.yohaku.expensetracker.domain.model.EntryKind.EXPENSE,null)
        val event=repo.saveEntry(book,com.yohaku.expensetracker.domain.model.EntryDraft(accountId=account,categoryId=category,amount="100"))
        initial.dao().putTemplate(EntryTemplateEntity(book,"t","Meal","EXPENSE",category,account,"JPY",createdAt=1))
        initial.close()
        val old=SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE)
        try {
            restoreFixtureDatabase(old,38,"com.yohaku.expensetracker.data.db.YohakuDatabase/38.json")
        }finally{old.close()}
        val db=com.yohaku.expensetracker.data.backup.openYohakuDatabase(context,name)
        try {
            val snapshot=LedgerRepository(db).snapshot(db.dao().book(book)!!).first()
            assertEquals(DATABASE_VERSION,db.openHelper.readableDatabase.version)
            assertEquals(event,snapshot.events.single().id);assertEquals(100L,snapshot.events.single().amountMinor)
            assertFalse(snapshot.events.single().budgetExcluded);assertFalse(snapshot.templates.single().budgetExcluded)
            db.dao().putEvent(snapshot.events.single().copy(budgetExcluded=true))
            db.dao().putTemplate(snapshot.templates.single().copy(budgetExcluded=true))
            val updated=LedgerRepository(db).snapshot(db.dao().book(book)!!).first()
            assertTrue(updated.events.single().budgetExcluded);assertTrue(updated.templates.single().budgetExcluded)
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use{assertFalse(it.moveToFirst())}
        }finally{db.close();context.deleteDatabase(name)}
    }

    @Test fun version37AddsMonthlyPlanAndReviewWithoutChangingExistingRows()=runBlocking<Unit> {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="upgrade-monthly-37-38.db";context.deleteDatabase(name)
        val old=SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name),null)
        try {
            val schema=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("com.yohaku.expensetracker.data.db.YohakuDatabase/37.json").bufferedReader().readText()).getJSONObject("database")
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val entity=entities.getJSONObject(i);val table=entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",table))
                val indices=entity.optJSONArray("indices")
                for(j in 0 until (indices?.length()?:0))old.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
            }
            val queries=schema.getJSONArray("setupQueries")
            for(i in 0 until queries.length())old.execSQL(queries.getString(i))
            old.execSQL("INSERT INTO books (id,name,currency,startDate,theme,language,initialized,budgetMinor,setupStep,archived,deletedAt) VALUES ('monthly-book','Existing','JPY','2026-01-01','journal','ja',1,50000,4,0,NULL)")
            old.execSQL("INSERT INTO command_plans (planId,bookId,localId,origin,taskId,resultId,resultVersion,sha256,sourceKey,planJson,contextDigest,status,checksJson,createdAt) VALUES ('p','monthly-book','local','https://example.test','task','result',1,'sha','source','{}','digest','PENDING','[]',1)")
            old.version=37
        }finally{old.close()}
        val db=com.yohaku.expensetracker.data.backup.openYohakuDatabase(context,name)
        try {
            assertEquals(DATABASE_VERSION,db.openHelper.readableDatabase.version)
            assertEquals("Existing",db.dao().book("monthly-book")!!.name);assertEquals(50000L,db.dao().book("monthly-book")!!.budgetMinor)
            assertEquals("PENDING",db.commandDao().plan("p")!!.status)
            assertNull(db.monthlyDao().plan("monthly-book"));assertTrue(db.monthlyDao().history("monthly-book").first().isEmpty())
            val plan=com.yohaku.expensetracker.data.command.MonthlyPlanEntity("monthly-book","原文を保存")
            val review=com.yohaku.expensetracker.data.command.MonthlyReviewEntity("monthly-book","2026-10","submission","{}",2,"p")
            db.monthlyDao().savePlan(plan);db.monthlyDao().insert(review)
            assertEquals(plan,db.monthlyDao().plan("monthly-book"));assertEquals(review,db.monthlyDao().review("monthly-book","2026-10"))
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use{assertFalse(it.moveToFirst())}
        }finally{db.close();context.deleteDatabase(name)}
    }

    @Test fun version36AddsUndoIdentityAndPreservesExecutedPlan()=runBlocking<Unit> {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="upgrade-command-36-37.db";context.deleteDatabase(name)
        val old=SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name),null)
        val execution="""{"outputs":{},"generatedIds":{}}"""
        try {
            val schema=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("com.yohaku.expensetracker.data.db.YohakuDatabase/36.json").bufferedReader().readText()).getJSONObject("database")
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val entity=entities.getJSONObject(i);val table=entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",table))
                val indices=entity.optJSONArray("indices")
                for(j in 0 until (indices?.length()?:0))old.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
            }
            val queries=schema.getJSONArray("setupQueries")
            for(i in 0 until queries.length())old.execSQL(queries.getString(i))
            old.execSQL("INSERT INTO command_plans (planId,bookId,localId,origin,taskId,resultId,resultVersion,sha256,sourceKey,planJson,contextDigest,status,checksJson,createdAt,executedAt,executionJson) VALUES ('p','b','local','https://example.test','task','result',1,'sha','source','{}','digest','EXECUTED','[]',1,2,?)",arrayOf(execution))
            old.execSQL("INSERT INTO cloud_imports (importKey,localId,taskId,resultId,resultVersion,sha256,localRef,importedAt) VALUES ('source','local','task','result',1,'sha','p',2)")
            old.version=36
        }finally{old.close()}
        val db=com.yohaku.expensetracker.data.backup.openYohakuDatabase(context,name)
        try {
            assertEquals(DATABASE_VERSION,db.openHelper.readableDatabase.version)
            val row=db.commandDao().plan("p")!!
            assertEquals("EXECUTED",row.status);assertEquals(2L,row.executedAt);assertEquals(execution,row.executionJson)
            assertNull(row.undoOf);assertNull(row.undoneAt);assertNotNull(db.cloudDao().imported("source"))
            val commands=com.yohaku.expensetracker.data.command.CommandRepository(LedgerRepository(db))
            assertFalse(commands.undoAvailability(row).available)
            val compressed=row.copy(executionJson=com.yohaku.expensetracker.data.command.storeCommandJson(execution))
            assertFalse(commands.undoAvailability(compressed).available)
            assertEquals(com.yohaku.expensetracker.data.command.readCommandExecution(execution),
                com.yohaku.expensetracker.data.command.readCommandExecution(compressed.executionJson!!))
            val inverse=row.copy(planId="undo",sourceKey="local-undo:p",status="PENDING",undoOf="p",executedAt=null)
            db.commandDao().insert(inverse);assertEquals(inverse,db.commandDao().undoFor("p"))
            db.commandDao().update(row.copy(status="UNDONE",undoneAt=3))
            assertEquals(3L,db.commandDao().plan("p")!!.undoneAt)
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use{assertFalse(it.moveToFirst())}
        }finally{db.close();context.deleteDatabase(name)}
    }

    @Test fun version35AddsCommandTablesAndPreservesExistingCloudTask()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="upgrade-command-35-36.db";context.deleteDatabase(name)
        val old=SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name),null)
        try {
            val schema=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("com.yohaku.expensetracker.data.db.YohakuDatabase/35.json").bufferedReader().readText()).getJSONObject("database")
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val entity=entities.getJSONObject(i);val table=entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",table))
                val indices=entity.optJSONArray("indices")
                for(j in 0 until (indices?.length()?:0))old.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
            }
            val queries=schema.getJSONArray("setupQueries")
            for(i in 0 until queries.length())old.execSQL(queries.getString(i))
            old.execSQL("INSERT INTO books (id,name,currency,startDate,theme,language,initialized,budgetMinor,setupStep,archived,deletedAt) VALUES ('b','Existing','JPY','2026-01-01','journal','ja',1,NULL,4,0,NULL)")
            old.execSQL("INSERT INTO cloud_tasks (localId,bookId,type,title,accountId,bundleId,origin,idempotencyKey,requestJson,taskId,stage,remoteStatus,inputRevision,remoteJson,lastError,createdAt,updatedAt,attempts) VALUES ('task','b','general','Old',NULL,NULL,'https://example.test','key','{}',NULL,'PREPARED',NULL,NULL,NULL,NULL,1,1,0)")
            old.version=35
        }finally{old.close()}
        val db=com.yohaku.expensetracker.data.backup.openYohakuDatabase(context,name)
        try {
            assertEquals(DATABASE_VERSION,db.openHelper.readableDatabase.version)
            assertEquals("Existing",db.dao().book("b")!!.name)
            assertEquals("analysis",db.cloudDao().task("task")!!.purpose)
            assertTrue(db.commandDao().history("b").first().isEmpty())
            val plan=com.yohaku.expensetracker.data.command.CommandPlanEntity("p","b","task","https://example.test","remote","result",1,"sha","source","{}","digest",createdAt=2)
            db.commandDao().insert(plan);assertEquals(plan,db.commandDao().plan("p"))
            val effect=com.yohaku.expensetracker.data.command.CommandEffectEntity("e","p","s","post-commit","{}")
            db.commandDao().enqueue(effect);assertEquals(listOf(effect),db.commandDao().pendingEffects())
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use{assertFalse(it.moveToFirst())}
        }finally{db.close();context.deleteDatabase(name)}
    }

    @Test fun versions33And34RestoreThroughBackupDatabaseWithExistingData()=runBlocking {
        for(version in listOf(33,34)) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="upgrade-places-$version-35.db";context.deleteDatabase(name)
        val old=SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name),null)
        try {
            val schema=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("com.yohaku.expensetracker.data.db.YohakuDatabase/$version.json").bufferedReader().readText()).getJSONObject("database")
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val entity=entities.getJSONObject(i);val table=entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",table))
                val indices=entity.optJSONArray("indices")
                for(j in 0 until (indices?.length()?:0))old.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
            }
            val queries=schema.getJSONArray("setupQueries")
            for(i in 0 until queries.length())old.execSQL(queries.getString(i))
            old.execSQL("INSERT INTO books (id,name,currency,startDate,theme,language,initialized,budgetMinor,setupStep,archived,deletedAt) VALUES ('b','Existing','JPY','2026-01-01','journal','ja',1,10000,4,0,NULL)")
            old.execSQL("INSERT INTO accounts (bookId,id,name,type,currency,openingMinor,openingDate,active) VALUES ('b','bank','銀行','BANK','JPY',50000,'2026-01-01',1)")
            old.execSQL("INSERT INTO categories (bookId,id,name,kind,parentId,favorite,active,icon) VALUES ('b','food','食費','EXPENSE',NULL,0,1,NULL)")
            old.execSQL("INSERT INTO recurrences (bookId,id,name,accountId,categoryId,currency,amountMinor,baseMinor,estimated,frequency,anchorDate,endDate,active,activityId) VALUES ('b','r','家賃','bank','food','JPY',2000,2000,0,'MONTHLY','2026-10-01',NULL,1,NULL)")
            old.execSQL("INSERT INTO events (bookId,id,kind,accountId,categoryId,amountMinor,currency,baseMinor,date,merchant,note,historicalIncluded,revision) VALUES ('b','e','EXPENSE','bank','food',500,'JPY',500,'2026-10-01','原名','旧记录',0,1)")
            old.version=version
        }finally{old.close()}
        val db=com.yohaku.expensetracker.data.backup.openYohakuDatabase(context,name)
        try {
            val repo=LedgerRepository(db)
            val book=db.dao().book("b")!!;val s=repo.snapshot(book).first();val rule=s.recurrences.single()
            assertEquals(DATABASE_VERSION,db.openHelper.readableDatabase.version);assertEquals("Existing",book.name)
            assertEquals(50000L,s.accounts.single().openingMinor);assertEquals(2000L,rule.amountMinor)
            assertEquals("",rule.merchant);assertEquals("",rule.note);assertNull(rule.merchantId);assertNull(rule.cardPaymentChoice);assertNull(rule.cardPaymentPeriods)
            assertEquals("原名",s.events.single().merchant);assertEquals(500L,s.events.single().amountMinor)
            assertTrue(s.eventPlaces.isEmpty())
            assertTrue(s.templates.isEmpty())
            db.dao().putTemplate(EntryTemplateEntity("b","t","水","EXPENSE","food","bank","JPY",createdAt=1))
            assertEquals("t",db.dao().templates("b").single().id)
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use{assertFalse(it.moveToFirst())}
        }finally{db.close();context.deleteDatabase(name)}
        }
    }

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
            try {restoreFixtureDatabase(old,32)}finally{old.close()}
            db=com.yohaku.expensetracker.data.backup.openYohakuDatabase(context,name)
            val config=db.creditLimitDao().graph(book).first()!!.configs.single()
            assertEquals(DATABASE_VERSION,db.openHelper.readableDatabase.version);assertEquals(0,config.recoveryDelayDays);assertEquals("PAYMENT_DATE",config.recovery)
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
            old.version=1
        }finally{old.close()}
        val db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).addMigrations(*ALL_MIGRATIONS).build()
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
            old.version=4
        }finally{old.close()}
        val db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).addMigrations(*ALL_MIGRATIONS).build()
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
