package com.yohaku.expensetracker

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.files.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.features.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.time.*

class StatementDueFlowTest {
    @get:Rule val ui=createComposeRule()
    @get:Rule(order=1) val failureArtifacts=RegressionFailureArtifacts(ui)
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var vm:AppViewModel
    @Before fun setup() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        repo=LedgerRepository(db,Clock.fixed(Instant.parse("2026-11-13T03:00:00Z"),ZoneOffset.UTC))
        val prefs=AppPreferences(context);vm=AppViewModel(repo,prefs,testReferenceRates(prefs,repo.clock));ui.setContent{NativeApp(vm,haptics=RecordingHapticOutput())}
    }
    @After fun close(){vm.viewModelScope.cancel();db.close()}
    private fun shot(name:String) {
        if(!visualEnabled())return
        val i=InstrumentationRegistry.getInstrumentation();ui.waitForIdle();i.uiAutomation.waitForIdle(350,5000)
        val image=i.uiAutomation.takeScreenshot();val dir=File(i.targetContext.filesDir,"verification/statement-due").apply{mkdirs()}
        File(dir,"$name.png").outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};image.recycle()
    }
    private fun dueLine()=ui.onAllNodesWithTag("home-statement-due").fetchSemanticsNodes()

    @Test fun closedUncoveredPeriodsShowOnHomeUntilImportedOrSkipped() {
        for((theme,language) in testLooks(this@StatementDueFlowTest)) {
            val (book,bank,card)=runBlocking {
                val b=repo.createBook("生活",Currency.JPY,theme,language);repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
                repo.saveAccount(b,"現金",AccountType.CASH,Currency.JPY,Money(Currency.JPY,100),"2026-01-01")
                repo.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,1000),"2026-09-10")
                repo.saveAccount(b,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,0),"2026-10-20")
                vm.preferences.select(b)
                val accounts=repo.dao.accounts(b).first()
                Triple(b,accounts.first{it.type=="BANK"}.id,accounts.first{it.type=="CREDIT"}.id)
            }
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            ui.runOnIdle{vm.navigate("home")}
            // Bank: partial September and October; card: its partial first month. Cash never asks.
            ui.waitUntil(10000){dueLine().isNotEmpty()}
            val ja=language=="ja"
            ui.onNodeWithTag("home-statement-due").assertTextContains(if(ja)"銀行 2期間" else "銀行 2 期",substring=true)
                .assertTextContains(if(ja)"カード 10月請求分" else "カード 10月账单",substring=true)
            ui.onNodeWithTag("home-statement-due").performScrollTo();shot("$theme-$language-line")
            // Two imports that together cover the bank's whole range retire both of its periods.
            runBlocking{listOf("2026-09-01" to "2026-09-30","2026-10-01" to "2026-10-31").forEach{(from,to)->
                val file=StatementFile("yohaku.statement",1,java.util.UUID.randomUUID().toString(),book,bank,"JPY",from,to,"posted_date",true,"test",emptyList(),emptyList())
                db.fileWorkflowDao().insertStatement(StatementBatchEntity(book,file.file_id,bank,file.file_id,FileProtocols.json.encodeToString(file),0))
            }}
            ui.waitUntil(10000){ui.onAllNodes(hasTestTag("home-statement-due") and hasText("銀行",substring=true)).fetchSemanticsNodes().isEmpty()}
            ui.onNodeWithTag("home-statement-due").performScrollTo().performClick()
            ui.onNodeWithTag("statement-due-$card").assertExists();ui.onAllNodesWithTag("statement-due-$bank").assertCountEquals(0)
            shot("$theme-$language-sheet")
            ui.onNodeWithTag("statement-due-import-$card").performScrollTo().performClick()
            ui.waitUntil(10000){vm.route.value=="statement-import"}
            // Without a cloud desk the seed waits for the handoff sheet; it names the card and its period.
            vm.statementImportSeed.value?.let{assertEquals(StatementImportSeed(book,card,(if(ja)"照合期間：" else "核对期间：")+"2026-10-20 — 2026-10-31"),it)}
            ui.runOnIdle{vm.statementImportSeed.value=null;vm.navigate("home")}
            ui.onNodeWithTag("home-statement-due").performScrollTo().performClick()
            ui.onNodeWithTag("statement-due-skip-$card").performScrollTo().performClick()
            ui.waitUntil(10000){dueLine().isEmpty()&&!vm.busy.value}
            fun skips()=db.query("SELECT periodEnd FROM statement_review_skips WHERE bookId=?",arrayOf(book)).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
            assertEquals(listOf("2026-10-31"),skips())
            // A skip is not account usage: the unused card still deletes, taking its skips with it.
            runBlocking{assertTrue(repo.commitAccountOperation(repo.previewAccountOperation(book,card,AccountOperation.DELETE)))}
            assertEquals(emptyList<String>(),skips())
        }
    }
}
