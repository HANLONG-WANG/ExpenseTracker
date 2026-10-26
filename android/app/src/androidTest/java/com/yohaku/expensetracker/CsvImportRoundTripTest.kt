package com.yohaku.expensetracker

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
                val vm=AppViewModel(repo,prefs)
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
                        ui.onNodeWithTag(tag).captureToImage().asAndroidBitmap().let{bitmap->
                            File(folder,name).outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
                        }
                    }
                    for(t in listOf("magazine","journal","objects"))for(l in listOf("zh","ja")) {
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
}
