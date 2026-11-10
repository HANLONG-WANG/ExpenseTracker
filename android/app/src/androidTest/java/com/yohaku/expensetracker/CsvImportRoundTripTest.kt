package com.yohaku.expensetracker

import com.yohaku.expensetracker.ui.haptics.Haptic
import androidx.room.withTransaction
import com.yohaku.expensetracker.data.command.*
import androidx.room.Room
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import com.yohaku.expensetracker.ui.features.*
import com.yohaku.expensetracker.ui.theme.*
import kotlinx.coroutines.*
import org.junit.Rule
import java.io.File
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.csv.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CsvImportRoundTripTest {
    @get:Rule val ui=createComposeRule()
    @Test fun importUndoAndRepeatedParseFailuresEmitOnce()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val path=File(context.cacheDir,"csv-haptics-${java.util.UUID.randomUUID()}.preferences_pb")
        val malformed=File(context.cacheDir,"csv-invalid-${java.util.UUID.randomUUID()}.csv").apply{writeText("\"unclosed")}
        val prefs=AppPreferences(context,PreferenceDataStoreFactory.create(scope=scope,produceFile={path}))
        val repo=LedgerRepository(db);val vm=AppViewModel(repo,prefs,testReferenceRates(prefs,repo.clock))
        val book=repo.createBook("CSV feedback",Currency.JPY,theme="journal",startDate="2025-01-01")
        repo.dao.putBook(repo.dao.book(book)!!.copy(initialized=true))
        val account=repo.saveAccount(book,"Cash",AccountType.CASH,Currency.JPY,Money(Currency.JPY,10000),"2025-01-01")
        val bytes="日期,金额,类别\n2024-01-01,100,食物".toByteArray()
        val document=CsvParser.read(bytes);val settings=CsvParser.guess(document).copy(accountId=account)
        val preview=repo.csvPreview(book,document,settings,emptyMap())
        val model=CsvImportViewModel(repo,book)
        val recorder=RecordingHapticOutput()
        val factory=object:androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T:androidx.lifecycle.ViewModel> create(modelClass:Class<T>):T=model as T
        }
        try {
            prefs.select(book)
            ui.setContent {
                androidx.lifecycle.viewmodel.compose.viewModel<CsvImportViewModel>(key="csv-$book",factory=factory)
                NativeApp(vm,haptics=recorder)
            }
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&vm.hapticsEnabled.value==true}
            ui.runOnIdle{model.state.value=CsvUiState(fileName="fixture.csv",sha256=CsvParser.hash(bytes),document=document,settings=settings,preview=preview,step=3);vm.navigate("csv-import")}
            ui.onNodeWithTag("csv-import").performScrollTo().performClick()
            recorder.clear();ui.onNodeWithText("确认导入").performScrollTo().performClick()
            ui.waitUntil(10000){!model.state.value.busy&&model.state.value.imported==1&&recorder.calls.isNotEmpty()}
            assertEquals(listOf(Triple("journal",Haptic.Done,1f)),recorder.calls.toList())
            ui.runOnIdle{vm.navigate("home")};ui.waitForIdle()
            ui.runOnIdle{vm.navigate("csv-import")};ui.waitForIdle()
            assertEquals(1,recorder.calls.size)
            val batch=db.csvImportDao().batches(book).first().single()
            recorder.clear();ui.runOnIdle{model.undo(batch)}
            ui.waitUntil(10000){!model.state.value.busy&&recorder.calls.isNotEmpty()}
            assertTrue(db.csvImportDao().events(book,batch.id).all{it.deletedAt!=null})
            assertEquals(listOf(Triple("journal",Haptic.Removed,1f)),recorder.calls.toList())
            ui.runOnIdle{model.closeEvents()};recorder.clear()
            repeat(2){attempt->
                ui.runOnIdle{model.open(context,android.net.Uri.fromFile(malformed))}
                ui.waitUntil(10000){!model.state.value.busy&&model.state.value.error!=null&&recorder.calls.size==attempt+1}
            }
            assertTrue(model.state.value.error!!.contains("CSV"))
            assertEquals(listOf(Haptic.Error,Haptic.Error),recorder.calls.map{it.second})
            ui.runOnIdle{vm.navigate("home")};ui.waitForIdle()
            ui.runOnIdle{vm.navigate("csv-import")};ui.waitForIdle()
            assertEquals(2,recorder.calls.size);assertNull(vm.error.value)
        }finally{vm.viewModelScope.cancel();model.viewModelScope.cancel();scope.cancel();db.close();path.delete();malformed.delete()}
    }
    @Test fun importHistoricalRowsUndoAndRestoreAsOneGroup()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val repo=LedgerRepository(db)
            val book=repo.createBook("CSV test",Currency.JPY,startDate="2025-01-01",budgetMinor=1000)
            val account=repo.saveAccount(book,"Test cash",AccountType.CASH,Currency.JPY,Money(Currency.JPY,10000),"2025-01-01")
            val bytes="账簿,日期,类别,金额,备注\n支出,2024-01-01,食物,100,架空の記録\n支出,2024-01-01,食物,100,架空の記録\n收入,2024-01-02,退款,20,架空の収入\n支出,2024-01-03,食物,1.2,修正\n支出,2024-01-04,食物,0,スキップ".toByteArray()
            val document=CsvParser.read(bytes)
            val settings=CsvParser.guess(document).copy(accountId=account)
            val first=repo.csvPreview(book,document,settings,emptyMap())
            assertEquals(2,first.rows.count{it.issues.isNotEmpty()});assertFalse(first.canImport)
            assertFalse(first.rows.any{it.duplicate})
            val edits=mapOf(3 to CsvEdit(amount="2"),4 to CsvEdit(skip=true))
            val preview=repo.csvPreview(book,document,settings,edits)
            assertTrue(preview.canImport)
            // Optional real native screenshots use this same fictional fixture, never the personal CSV.
            if(InstrumentationRegistry.getArguments().getString("csvVisual")=="true") {
                val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
                val path=File(context.cacheDir,"csv-ui-${java.util.UUID.randomUUID()}.preferences_pb")
                val prefs=AppPreferences(context,PreferenceDataStoreFactory.create(scope=scope,produceFile={path}))
                val vm=AppViewModel(repo,prefs, testReferenceRates(prefs,repo.clock))
                val model=CsvImportViewModel(repo,book)
                val snapshot=repo.snapshot(repo.dao.book(book)!!).first()
                var theme by mutableStateOf("magazine");var language by mutableStateOf("zh")
                val fixture=CsvUiState(fileName="fictional.csv",document=document,settings=settings,edits=edits,preview=preview,step=3)
                model.state.value=fixture
                try {
                    ui.setContent {YohakuTheme(theme,language){
                        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp).testTag("csv-visual"),verticalArrangement=Arrangement.spacedBy(15.dp)) {
                            CsvImportScreen(snapshot.copy(book=snapshot.book.copy(theme=theme,language=language)),vm,model)
                        }
                    }}
                    val folder=File(context.getExternalFilesDir(null),"csv-visual").apply{mkdirs()}
                    fun capture(tag:String,name:String) {
                        if (!visualEnabled()) return
                        ui.onNodeWithTag(tag).captureToImage().asAndroidBitmap().let{bitmap->
                            File(folder,name).outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
                        }
                    }
                    for((t,l) in testLooks(this@CsvImportRoundTripTest)) {
                        for(step in 0..3) {
                            ui.runOnIdle{theme=t;language=l;model.state.value=fixture.copy(step=step)}
                            if(step in 1..2)ui.onNodeWithText(if(step==1)if(l=="ja")"次へ"else"下一步" else if(l=="ja")"プレビューを作成"else"生成预览").performScrollTo().assertIsDisplayed()
                            else ui.onNodeWithTag("csv-open").performScrollTo().assertIsDisplayed()
                            capture("csv-visual","$t-$l-step$step.png")
                        }
                        ui.onNodeWithTag("csv-import").performScrollTo().assertIsEnabled().performClick()
                        capture("theme-sheet","$t-$l-confirm.png")
                        ui.onNodeWithContentDescription(if(l=="ja")"閉じる"else"关闭").performClick()
                    }
                }finally{vm.viewModelScope.cancel();model.viewModelScope.cancel();scope.coroutineContext[Job]!!.cancelAndJoin();path.delete()}
            }
            val batch=repo.importCsv(book,"fictional.csv",CsvParser.hash(bytes),document,settings,edits,preview)
            val snapshot=repo.snapshot(repo.dao.book(book)!!).first()
            assertEquals(4,snapshot.events.size);assertTrue(snapshot.events.all{it.historicalIncluded&&it.revision==1&&it.createdAt>0})
            assertEquals(10000L,snapshot.balance(snapshot.accounts.single()))
            assertEquals(4,snapshot.settlements.size);assertTrue(snapshot.storedPostings.isEmpty())
            assertEquals(202L,snapshot.monthExpense("2024-01"))
            assertEquals(setOf("食物","退款"),snapshot.categories.map{it.name}.toSet())
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM revisions").use{assertTrue(it.moveToFirst());assertEquals(4,it.getInt(0))}
            assertTrue(db.csvImportDao().batches(book).first().any{it.sha256==CsvParser.hash(bytes)})
            val again=repo.csvPreview(book,document,settings,edits)
            assertEquals(4,again.rows.count{it.duplicate});assertTrue(again.rows.all{it.skipped})
            repo.undoCsvBatch(book,batch.id)
            val removed=db.csvImportDao().events(book,batch.id)
            assertTrue(removed.all{it.deletedAt!=null});assertEquals(1,removed.map{it.deletionGroupId}.distinct().size)
            repo.restoreEntry(book,removed.first{it.kind=="EXPENSE"}.id)
            val restored=db.csvImportDao().events(book,batch.id)
            assertTrue(restored.all{it.deletedAt==null});assertEquals(4,restored.size)
            assertEquals(10000L,repo.snapshot(repo.dao.book(book)!!).first().balance(snapshot.accounts.single()))
        }finally{db.close()}
    }

    @Test fun renamedSourcesAndCurrentValuesBothMatchUntilTrashOrPurge()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,YohakuDatabase::class.java).build()
        try {
            val repo=LedgerRepository(db)
            val book=repo.createBook("CSV rename",Currency.JPY,startDate="2026-01-01")
            val account=repo.saveAccount(book,"Cash",AccountType.CASH,Currency.JPY,Money(Currency.JPY,10000),"2026-01-01")
            val merchant=repo.saveMerchant(book,"Coffee")
            fun document(name:String)=CsvParser.read("日期,金额,类别,商户,备注\n2026-10-01,100,Food,$name,note\n2026-10-01,100,Food,$name,note".toByteArray())
            val original=document("Coffee")
            val settings=CsvParser.guess(original).copy(accountId=account,noteAsMerchant=false,columns=mapOf("date" to 0,"amount" to 1,"category" to 2,"merchant" to 3,"note" to 4),rule="EXPENSE")
            val first=repo.csvPreview(book,original,settings,emptyMap())
            assertFalse(first.rows.any{it.duplicate})
            val batch=repo.importCsv(book,"source.csv","hash",original,settings,emptyMap(),first)
            assertEquals(2,db.csvImportDao().events(book,batch.id).size)
            repo.saveMerchant(book,"Tea",id=merchant)
            for(name in listOf("Coffee","Tea"))assertTrue(repo.csvPreview(book,document(name),settings,emptyMap()).rows.all{it.duplicate&&it.skipped})
            assertFalse(repo.csvPreview(book,document("Bakery"),settings,emptyMap()).rows.any{it.duplicate})
            if(visualEnabled()) {
                val context=InstrumentationRegistry.getInstrumentation().targetContext
                val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
                val path=File(context.cacheDir,"csv-rename-${java.util.UUID.randomUUID()}.preferences_pb")
                val prefs=AppPreferences(context,PreferenceDataStoreFactory.create(scope=scope,produceFile={path}))
                val vm=AppViewModel(repo,prefs,testReferenceRates(prefs,repo.clock))
                val model=CsvImportViewModel(repo,book)
                val snapshot=repo.snapshot(repo.dao.book(book)!!).first()
                var theme by mutableStateOf("magazine");var language by mutableStateOf("zh")
                model.state.value=CsvUiState(fileName="source.csv",document=original,settings=settings,preview=repo.csvPreview(book,original,settings,emptyMap()),step=3)
                try {
                    ui.setContent {YohakuTheme(theme,language){
                        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
                            CsvImportScreen(snapshot.copy(book=snapshot.book.copy(theme=theme,language=language)),vm,model)
                        }
                    }}
                    val folder=File(context.getExternalFilesDir(null),"merchant-f-csv").apply{mkdirs()}
                    for((t,l) in testLooks(this@CsvImportRoundTripTest)) {
                        ui.runOnIdle{theme=t;language=l}
                        ui.onNodeWithTag("csv-import").performScrollTo().assertIsNotEnabled()
                        ui.waitForIdle()
                        ui.onRoot().captureToImage().asAndroidBitmap().let{bitmap->
                            File(folder,"$t-$l-source-duplicates.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
                        }
                    }
                }finally{vm.viewModelScope.cancel();model.viewModelScope.cancel();scope.cancel();path.delete()}
            }
            repo.undoCsvBatch(book,batch.id)
            assertFalse(repo.csvPreview(book,original,settings,emptyMap()).rows.any{it.duplicate})
            val id=db.csvImportDao().events(book,batch.id).first().id
            repo.restoreEntry(book,id)
            assertTrue(repo.csvPreview(book,original,settings,emptyMap()).rows.all{it.duplicate})
            repo.undoCsvBatch(book,batch.id)
            db.withTransaction { repo.purgeEntryGroup(book,id) }
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM csv_batch_events WHERE bookId=? AND eventId=?",arrayOf(book,id)).use{it.moveToFirst();assertEquals(0,it.getInt(0))}
        }finally{db.close()}
    }

    @Test fun sourceMatchKeyCapturesOriginalValuesBeforeMerchantResolution()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val repo=LedgerRepository(db)
            val book=repo.createBook("CSV source",Currency.JPY,startDate="2026-01-01")
            val account=repo.saveAccount(book,"Cash",AccountType.CASH,Currency.JPY,Money(Currency.JPY,1000),"2026-01-01")
            val shop=repo.saveMerchant(book,"Ｃａｆｅ",active=false)
            // Keep the saved CSV category mapping outside the import-owned row set.
            repo.saveCategory(book,"Food",EntryKind.EXPENSE,null)
            val bytes="日期,金额,类别,商户,备注\n2026-10-01,100,Food,cafe,original note\n2026-10-01,100,Food,cafe,original note".toByteArray()
            val document=CsvParser.read(bytes)
            val settings=CsvParser.guess(document).copy(accountId=account,noteAsMerchant=false,columns=mapOf("date" to 0,"amount" to 1,"category" to 2,"merchant" to 3,"note" to 4),rule="EXPENSE")
            val journal=com.yohaku.expensetracker.data.command.CommandUndoJournal(repo,book)
            val before=journal.snapshot()
            val batch=repo.importCsv(book,"source.csv",CsvParser.hash(bytes),document,settings,emptyMap(),repo.csvPreview(book,document,settings,emptyMap()))
            val events=db.csvImportDao().events(book,batch.id)
            assertEquals(2,events.size) // Equal source rows within one file remain separate.
            assertTrue(events.all{it.merchant=="Ｃａｆｅ"})
            assertTrue(events.all{repo.dao.eventMerchant(book,it.id)!!.merchantId==shop})
            val expected=CsvParser.sourceMatchKey("2026-10-01","EXPENSE",100,"original note","cafe")
            db.openHelper.readableDatabase.query("SELECT sourceMatchKey FROM csv_batch_events WHERE bookId=?",arrayOf(book)).use{c->
                var count=0;while(c.moveToNext()){assertEquals(expected,c.getString(0));count++};assertEquals(2,count)
            }
            val links=journal.changes(before).filter{it.table=="csv_batch_events"}
            assertEquals(2,links.size);assertTrue(links.all{it.before==null&&!it.removed})
            assertTrue(links.all{it.after["sourceMatchKey"]==kotlinx.serialization.json.JsonPrimitive(expected)})
            assertTrue(repo.csvPreview(book,document,settings,emptyMap()).rows.all{it.duplicate})
            val record=UndoRecord(emptyList(),emptyList(),emptyList(),rows=journal.changes(before),version=3)
            db.openHelper.writableDatabase.execSQL("UPDATE csv_batch_events SET sourceMatchKey='later source' WHERE bookId=?",arrayOf(book))
            assertTrue(journal.conflicts(record).any{it.type=="csv_batch_events"&&it.field=="sourceMatchKey"})
            db.openHelper.writableDatabase.execSQL("UPDATE csv_batch_events SET sourceMatchKey=? WHERE bookId=?",arrayOf(expected,book))
            db.withTransaction {
                assertTrue(journal.conflicts(record).toString(),journal.conflicts(record).isEmpty())
                assertTrue(journal.merchantConflicts(record).toString(),journal.merchantConflicts(record).isEmpty())
                journal.restore(CommandRun(repo,book,ExecutionContext("2026-11-02","Asia/Tokyo","2027-11-02"),record))
            }
            assertEquals(before,CommandUndoJournal(repo,book).snapshot())
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM csv_batch_events WHERE bookId=?",arrayOf(book)).use{c->c.moveToFirst();assertEquals(0,c.getInt(0))}

        }finally{db.close()}
    }
}
