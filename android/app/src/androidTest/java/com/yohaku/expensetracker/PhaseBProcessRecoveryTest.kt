package com.yohaku.expensetracker

import android.graphics.Bitmap
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
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.time.*

/** Each step must run in a distinct instrumentation process, separated by host force-stop. */
@RunWith(AndroidJUnit4::class)
class PhaseBProcessRecoveryTest {
    @get:Rule val ui=createComposeRule()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val marker get()=File(context.filesDir,"verification/phase-b-process.json")
    private val mediaRoot get()=File(context.filesDir,"attachments/phase-b-process")
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var vm:AppViewModel
    private val prefs get()=AppPreferences(context)
    @Before fun open() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("phaseBRecovery")=="true")
        db=Room.databaseBuilder(context,YohakuDatabase::class.java,"phase-b-process-check.db").build()
        repo=LedgerRepository(db,Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"),ZoneOffset.UTC),mediaRoot)
        vm=AppViewModel(repo,prefs, testReferenceRates(prefs,repo.clock))
    }
    @After fun close(){if(::vm.isInitialized)vm.viewModelScope.cancel();if(::db.isInitialized)db.close()}
    private fun tap(text:String) {
        ui.waitUntil(10000){ui.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()&&!vm.busy.value}
        ui.onNode(hasText(text) and hasClickAction()).performScrollTo().performClick()
    }
    private suspend fun snapshot(id:String)=repo.snapshot(repo.dao.book(id)!!).first()
    private fun image():ByteArray {
        val bitmap=Bitmap.createBitmap(16,24,Bitmap.Config.ARGB_8888);bitmap.eraseColor(android.graphics.Color.WHITE)
        return java.io.ByteArrayOutputStream().use{output->bitmap.compress(Bitmap.CompressFormat.PNG,100,output);bitmap.recycle();output.toByteArray()}
    }
    @Test fun seedTransferDraft()=runBlocking {
        assertFalse("Do not replace an unfinished recovery check",marker.exists())
        val previous=prefs.activeBook.first()
        val book=repo.createBook("阶段B恢复",Currency.JPY,"objects","zh",startDate="2026-09-01",budgetMinor=100000)
        repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
        val source=repo.saveAccount(book,"银行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,100000),"2026-09-01")
        val target=repo.saveAccount(book,"人民币",AccountType.BANK,Currency.CNY,Money(Currency.CNY,0),"2026-09-01")
        val category=repo.saveCategory(book,"旅途",EntryKind.EXPENSE,null)
        val draft=EntryDraft(kind=EntryKind.TRANSFER,accountId=source,toAccountId=target,amount="30000",toAmount="1450.00",date="2026-09-30",toDate="2026-10-02",fromConfirmed=true,feeAmount="750",manualRate="0.05",mediaInitialized=true,note="待到账继续确认")
        repo.saveDraft(book,draft)
        val file=File(context.cacheDir,"phase-b-source.txt").apply{writeText("private copy survives source deletion")}
        val attachment=repo.importMedia(book,draft.mediaSession,"转账凭据.txt","text/plain"){file.inputStream()};file.delete()
        val receipt=repo.importMedia(book,draft.mediaSession,"转账原图.png","image/png",true){image().inputStream()}
        File(mediaRoot,"interrupted.part").writeText("incomplete")
        prefs.select(book)
        marker.parentFile!!.mkdirs()
        marker.writeText(JSONObject().put("book",book).put("previous",previous?:"").put("source",source).put("target",target).put("category",category).put("attachment",attachment.id).put("receipt",receipt.id).put("pid",Process.myPid()).toString())
    }
    @Test fun resumeTransferAndSeedActivityDraft() {
        val data=JSONObject(marker.readText());assertNotEquals(data.getInt("pid"),Process.myPid())
        ui.setContent{NativeApp(vm)}
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==data.getString("book")&&!vm.busy.value}
        tap("继续本账本未保存的记录")
        ui.onNodeWithTag("field-实际扣款总额 · JPY").performScrollTo().assertTextEquals("30000")
        ui.onNodeWithTag("field-实际到账净额 · CNY").performScrollTo().assertTextEquals("1450.00")
        val d=vm.entry.value!!;assertEquals("0.05",d.manualRate);assertTrue(d.fromConfirmed);assertFalse(d.toConfirmed);assertEquals("750",d.feeAmount)
        assertEquals(listOf(data.getString("attachment")),d.attachmentIds);assertEquals(listOf(data.getString("receipt")),d.receiptFileIds)
        assertFalse(File(mediaRoot,"interrupted.part").exists())
        tap("保存这笔转账");tap("确认并保存")
        ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value}
        runBlocking {
            val book=data.getString("book");val saved=snapshot(book)
            assertEquals(1,saved.events.size);assertEquals(70000L,saved.balance(saved.accounts.first{it.id==data.getString("source")}));assertEquals(0L,saved.balance(saved.accounts.first{it.id==data.getString("target")}))
            assertEquals(1,saved.inTransitTransfers().size);assertEquals(1,saved.entryAttachments(saved.events.single().id).size);assertEquals(1,saved.entryReceiptImages(saved.events.single().id).size)
            assertEquals("private copy survives source deletion",repo.mediaFile(saved.files.first{it.id==data.getString("attachment")}).readText())
            repo.setActivityMode(book,true)
            val activity=repo.saveActivity(book,"秋の旅","2026-10-01","2026-10-31",Money(Currency.JPY,30000),true)
            val friend=repo.saveFriend(book,"友達A");repo.addParticipant(book,activity,friend)
            assertTrue(repo.commitReserve(repo.previewReserve(book,ReserveInput(activity,"旅途资金","2026-10-14",Money(Currency.JPY,20000)))))
            val reserve=snapshot(book).reserves.single()
            repo.saveAnalysisPreferences(book,listOf(AnalysisModule.MERCHANTS,AnalysisModule.TREND),setOf(AnalysisModule.TREND))
            val draft=EntryDraft(accountId=data.getString("source"),categoryId=data.getString("category"),amount="10000",date="2026-10-14",activityId=activity,reserveId=reserve.id,reserveAmount="10000",treatEnabled=true,treatFriends=listOf(friend),treatMode=TreatSplitMode.PERCENT,treatWeights=mapOf("ME" to "70",friend to "30","__tail" to friend),mediaInitialized=true,merchant="旅行晚餐")
            repo.saveDraft(book,draft)
            val attachment=repo.importMedia(book,draft.mediaSession,"旅行记录.txt","text/plain"){"activity draft".byteInputStream()}
            val receipt=repo.importMedia(book,draft.mediaSession,"晚餐.png","image/png",true){image().inputStream()}
            repo.updateAppearance(book,"journal","ja")
            data.put("activity",activity).put("friend",friend).put("reserve",reserve.id).put("activityAttachment",attachment.id).put("activityReceipt",receipt.id).put("pid",Process.myPid())
            marker.writeText(data.toString())
        }
    }
    @Test fun resumeActivitySaveOnceAndCleanup() {
        val data=JSONObject(marker.readText());assertNotEquals(data.getInt("pid"),Process.myPid())
        ui.setContent{NativeApp(vm)}
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==data.getString("book")&&!vm.busy.value}
        tap("この帳簿の下書きを続ける")
        ui.onNodeWithTag("field-金額 · JPY").performScrollTo().assertTextEquals("10000")
        val draft=vm.entry.value!!
        assertEquals(data.getString("activity"),draft.activityId);assertEquals(data.getString("reserve"),draft.reserveId);assertEquals("10000",draft.reserveAmount)
        assertTrue(draft.treatEnabled);assertEquals(TreatSplitMode.PERCENT,draft.treatMode);assertEquals("70",draft.treatWeights["ME"]);assertEquals("30",draft.treatWeights[data.getString("friend")])
        assertEquals(listOf(data.getString("activityAttachment")),draft.attachmentIds);assertEquals(listOf(data.getString("activityReceipt")),draft.receiptFileIds)
        ui.onNodeWithTag("entry-save-button").performScrollTo().performClick();tap("確認して保存")
        ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value}
        runBlocking {
            val book=data.getString("book");val saved=snapshot(book)
            assertEquals(2,saved.events.size);assertEquals(60000L,saved.balance(saved.accounts.first{it.id==data.getString("source")}))
            assertEquals(3000L,saved.friendBalances(data.getString("activity")).single().difference)
            assertEquals(10000L,saved.activityHeld(data.getString("activity")));assertEquals(0L,saved.budgetProjection("2026-10").spentMinor)
            assertEquals(listOf(AnalysisModule.MERCHANTS,AnalysisModule.TREND),repo.analysisModules(saved));assertEquals(setOf(AnalysisModule.TREND),repo.analysisPins(saved))
            assertEquals("journal",saved.book.theme);assertEquals("ja",saved.book.language);assertNull(saved.draft)
            assertEquals(2,saved.attachments.size);assertEquals(2,saved.receipts.size)
            prefs.select(data.getString("previous"))
        }
        vm.viewModelScope.cancel();db.close();context.deleteDatabase("phase-b-process-check.db");mediaRoot.deleteRecursively();marker.delete()
    }
}
