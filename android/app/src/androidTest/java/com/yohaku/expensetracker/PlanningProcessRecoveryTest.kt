package com.yohaku.expensetracker

import android.os.Process
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.features.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.*

/** Run the three steps separately, with host force-stop between them. */
@RunWith(AndroidJUnit4::class)
class PlanningProcessRecoveryTest {
    @get:Rule val ui=createComposeRule()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val marker get()=File(context.filesDir,"verification/phase-b-planning-process.json")
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var vm:AppViewModel
    private val prefs get()=AppPreferences(context)
    @Before fun open(){
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("planningRecovery")=="true")
        db=Room.databaseBuilder(context,YohakuDatabase::class.java,"phase-b-planning-process.db").build()
        repo=LedgerRepository(db,Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"),ZoneOffset.UTC))
        vm=AppViewModel(repo,prefs)
    }
    @After fun close(){if(::vm.isInitialized)vm.viewModelScope.cancel();if(::db.isInitialized)db.close()}
    private suspend fun snapshot(book:String)=repo.snapshot(repo.dao.book(book)!!).first()
    private fun cases(data:JSONObject)=(0 until data.getJSONArray("cases").length()).map{data.getJSONArray("cases").getJSONObject(it)}
    private fun select(c:JSONObject){
        runBlocking{prefs.select(c.getString("book"))}
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==c.getString("book")&&!vm.busy.value}
        ui.runOnIdle{vm.navigate("recurring")}
    }
    private fun verifyFacts(c:JSONObject,s:BookSnapshot,reserve:Long){
        assertEquals((0 until c.getJSONArray("ids").length()).map{c.getJSONArray("ids").getString(it)}.toSet(),s.occurrences.map{it.id}.toSet())
        assertEquals(1,s.events.size);assertEquals(c.getString("event"),s.events.single().id);assertEquals(1,s.postings.size)
        assertEquals(47000L,s.balance(s.accounts.single()));assertEquals(3000L,s.monthExpense("2026-10"))
        assertEquals("COMPLETED",s.occurrences.single{it.id==c.getString("paid")}.status)
        assertEquals(2000L,s.occurrences.single{it.id==c.getString("paid")}.amountMinor)
        assertEquals(reserve,s.budgetProjection("2026-10").reserveMinor);assertEquals(90000L,s.budgetLimit("2026-11"))
        assertEquals(c.getString("theme"),s.book.theme);assertEquals(c.getString("language"),s.book.language)
    }
    @Test fun seedStoppedRulesAndBudget()=runBlocking {
        assertFalse("Keep an unfinished recovery check",marker.exists())
        val data=JSONObject().put("previous",prefs.activeBook.first()?:"").put("seedPid",Process.myPid()).put("pid",Process.myPid())
        val all=JSONArray()
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")){
            val book=repo.createBook("进程恢复 $theme $language",Currency.JPY,theme,language,startDate="2026-10-01",budgetMinor=100000)
            repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
            val account=repo.saveAccount(book,"支払銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,50000),"2026-10-01")
            val category=repo.saveCategory(book,"生活",EntryKind.EXPENSE,null)
            val input=RecurrenceInput("每周安排",account,category,Money(Currency.JPY,2000),null,false,RecurrenceFrequency.WEEKLY,"2026-10-14")
            val rule=repo.saveRecurrence(book,input);repo.ensureOccurrences(book,"2026-11")
            val before=snapshot(book)
            val paid=before.occurrences.single{it.scheduledDate=="2026-10-14"}.id
            val cancelled=before.occurrences.single{it.scheduledDate=="2026-10-21"}.id
            val future=before.occurrences.single{it.scheduledDate=="2026-10-28"}.id
            val event=repo.saveEntry(book,EntryDraft(accountId=account,categoryId=category,amount="3000",date="2026-10-14",occurrenceId=paid))
            repo.cancelOccurrence(book,cancelled)
            repo.saveRecurrence(book,input.copy(id=rule,active=false,amount=Money(Currency.JPY,4000)))
            assertTrue(repo.commitBudget(repo.previewBudget(book,"2026-11",null,Money(Currency.JPY,90000))))
            val c=JSONObject().put("book",book).put("rule",rule).put("paid",paid).put("cancelled",cancelled).put("future",future).put("event",event).put("ids",JSONArray(before.occurrences.map{it.id})).put("theme",theme).put("language",language)
            verifyFacts(c,snapshot(book),0);all.put(c)
        }
        data.put("cases",all);marker.parentFile!!.mkdirs();marker.writeText(data.toString())
    }
    @Test fun resumeStoppedRulesAndReactivateThroughUi(){
        val data=JSONObject(marker.readText());assertNotEquals(data.getInt("pid"),Process.myPid())
        ui.setContent{NativeApp(vm)}
        for(c in cases(data)){
            fun text(zh:String,ja:String)=if(c.getString("language")=="ja")ja else zh
            select(c)
            val s=runBlocking{snapshot(c.getString("book"))};verifyFacts(c,s,0)
            assertEquals("CANCELLED",s.occurrences.single{it.id==c.getString("cancelled")}.status)
            assertEquals("WITHDRAWN",s.occurrences.single{it.id==c.getString("future")}.status)
            ui.onNodeWithTag("occurrence-status-${c.getString("future")}").assertTextEquals(text("规则已撤下","ルール対象外"))
            ui.onNodeWithTag("edit-occurrence-${c.getString("future")}").performScrollTo().performClick()
            ui.onNodeWithTag("toggle-"+text("启用此周期规则","この定期ルールを有効にする")).performScrollTo().performClick()
            ui.onNodeWithTag("recurrence-save").performScrollTo().performClick()
            ui.waitUntil(10000){!vm.busy.value&&(vm.state.value as? AppState.Ready)?.snapshot?.recurrences?.single()?.active==true}
            assertEquals("CANCELLED",runBlocking{snapshot(c.getString("book"))}.occurrences.single{it.id==c.getString("cancelled")}.status)
            ui.onNodeWithTag("restore-occurrence-${c.getString("cancelled")}").performScrollTo().performClick()
            ui.waitUntil(10000){!vm.busy.value&&(vm.state.value as? AppState.Ready)?.snapshot?.occurrences?.single{it.id==c.getString("cancelled")}?.status=="PENDING"}
            verifyFacts(c,runBlocking{snapshot(c.getString("book"))},8000)
        }
        data.put("resumePid",Process.myPid()).put("pid",Process.myPid());marker.writeText(data.toString())
    }
    @Test fun verifyReactivatedFactsAfterAnotherProcessAndCleanup(){
        val data=JSONObject(marker.readText());assertNotEquals(data.getInt("pid"),Process.myPid());assertNotEquals(data.getInt("seedPid"),Process.myPid())
        ui.setContent{NativeApp(vm)}
        for(c in cases(data)){
            select(c)
            val s=runBlocking{snapshot(c.getString("book"))};verifyFacts(c,s,8000)
            assertEquals("PENDING",s.occurrences.single{it.id==c.getString("cancelled")}.status)
            assertEquals("PENDING",s.occurrences.single{it.id==c.getString("future")}.status)
            assertEquals(16000L,s.budgetProjection("2026-11").reserveMinor);assertEquals(74000L,s.budgetProjection("2026-11").availableMinor)
            ui.onNodeWithTag("recurring-reserve-summary").assert(hasAnyDescendant(hasText("¥8,000")))
            ui.onNodeWithTag("confirm-occurrence-${c.getString("paid")}").assertDoesNotExist()
        }
        data.put("verifiedPid",Process.myPid()).put("verifiedCases",6)
        File(context.getExternalFilesDir(null),"phase-b-planning-process-result.json").writeText(data.toString())
        runBlocking{prefs.select(data.getString("previous"))}
        vm.viewModelScope.cancel();db.close();context.deleteDatabase("phase-b-planning-process.db");marker.delete()
    }
}
