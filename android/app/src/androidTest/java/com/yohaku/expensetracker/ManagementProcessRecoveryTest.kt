package com.yohaku.expensetracker

import android.graphics.Bitmap
import android.os.Process
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.features.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/** Run each phase separately, force-stopping the app between phases. Own DB, preferences and media only. */
@RunWith(AndroidJUnit4::class)
class ManagementProcessRecoveryTest {
    @get:Rule val ui=createComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val database="phase-e-management-recovery.db"
    private val marker get()=File(context.filesDir,"verification/phase-e-management-recovery.json")
    private val media get()=File(context.filesDir,"verification/phase-e-management-recovery-media")
    private val preferenceFile get()=File(context.filesDir,"verification/phase-e-management-recovery.preferences_pb")
    private lateinit var db:YohakuDatabase
    private lateinit var scope:CoroutineScope
    private lateinit var repo:LedgerRepository
    private lateinit var prefs:AppPreferences
    private lateinit var vm:AppViewModel
    private var cleanup=false
    @Before fun open() {
        Assume.assumeTrue("Requires external process stops",InstrumentationRegistry.getArguments().getString("managementRecovery")=="true")
        db=Room.databaseBuilder(context,YohakuDatabase::class.java,database).build()
        scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        prefs=AppPreferences(context,PreferenceDataStoreFactory.create(scope=scope,produceFile={preferenceFile}))
        repo=LedgerRepository(db,mediaRoot=media);vm=AppViewModel(repo,prefs)
    }
    @After fun close() {
        if(::vm.isInitialized)vm.viewModelScope.cancel()
        if(::scope.isInitialized)scope.cancel()
        if(::db.isInitialized)db.close()
        if(cleanup){context.deleteDatabase(database);media.deleteRecursively();preferenceFile.delete();marker.delete()}
    }
    private fun state()=JSONObject(marker.readText())
    private fun tap(tag:String) {
        ui.waitUntil(10000){!vm.busy.value&&ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}
        val node=ui.onNodeWithTag(tag).performScrollTo();ui.waitForIdle()
        if(ui.onAllNodesWithTag("sheet-header").fetchSemanticsNodes().isNotEmpty()) {
            val top=node.getUnclippedBoundsInRoot().top.value
            val header=ui.onNodeWithTag("sheet-header").getUnclippedBoundsInRoot().bottom.value
            if(top<header+8)ui.onNodeWithTag("sheet-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,-(header+8-top)*context.resources.displayMetrics.density)}
        }
        node.performClick();ui.waitForIdle()
    }
    private fun shot(name:String) {
        instrumentation.uiAutomation.waitForIdle(350,5000)
        val file=File(context.getExternalFilesDir(null),"phase-e-management-recovery/$name.png");file.parentFile!!.mkdirs()
        val image=instrumentation.uiAutomation.takeScreenshot();file.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
    }
    @Test fun seedArchivedAndTrashedBooksWithOriginalMediaAndDraft()=runBlocking {
        assertFalse("Do not overwrite unfinished recovery fixture",marker.exists())
        assertTrue(repo.books.first().isEmpty())
        val today=LocalDate.now(repo.clock).toString()
        val a=repo.createBook("冷启动保留 / 再起動",Currency.JPY,"journal","ja")
        val b=repo.createBook("冷启动回收 / ごみ箱",Currency.JPY,"objects","zh")
        for(id in listOf(a,b))repo.dao.putBook(repo.dao.book(id)!!.copy(initialized=true))
        val bank=repo.saveAccount(a,"銀行原名",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),today)
        val category=repo.saveCategory(a,"食事原名",EntryKind.EXPENSE,null)
        val draft=EntryDraft(accountId=bank,categoryId=category,amount="600",date=today,merchant="喫茶 原名",mediaInitialized=true)
        repo.saveDraft(a,draft)
        val file=repo.importMedia(a,draft.mediaSession,"original.bin","application/octet-stream"){byteArrayOf(1,3,5,7).inputStream()}
        val event=repo.saveEntry(a,draft.copy(attachmentIds=listOf(file.id)))
        repo.saveDraft(a,EntryDraft(accountId=bank,categoryId=category,amount="500",date=today,merchant="続きの原名",note="再起動後も未保存"))
        assertTrue(repo.commitBookOperation(repo.previewBookOperation(a,BookOperation.ARCHIVE)))
        assertTrue(repo.commitBookOperation(repo.previewBookOperation(b,BookOperation.TRASH)))
        prefs.clearSelection()
        val s=JSONObject().put("book",a).put("trash",b).put("bank",bank).put("event",event).put("file",file.id).put("pid",Process.myPid())
        marker.parentFile!!.mkdirs();marker.writeText(s.toString())
        println("MANAGEMENT_RECOVERY seed pid=${Process.myPid()}")
    }
    @Test fun coldLibraryRestoresThenUnarchivesWithoutRecreatingAnyFacts()=runBlocking {
        val s=state();assertNotEquals(s.getInt("pid"),Process.myPid())
        val a=s.getString("book");val b=s.getString("trash")
        assertNull(prefs.activeBook.first());assertTrue(repo.dao.book(a)!!.archived);assertNull(repo.dao.book(b))
        ui.setContent{NativeApp(vm)}
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.let{it.snapshot==null&&it.books.size==2}==true}
        ui.onNodeWithTag("book-archive-$a").performScrollTo().assertExists();shot("cold-library")
        tap("book-management-toggle-trash");tap("book-restore-$b");tap("confirm-book-operation")
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==b&&!vm.busy.value}
        tap("book-archive-$a");tap("confirm-book-operation")
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.books?.firstOrNull{it.id==a}?.archived==false&&!vm.busy.value}
        tap("book-open-$a")
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==a&&!vm.busy.value}
        assertEquals(a,prefs.activeBook.first());assertFalse(repo.dao.book(a)!!.archived);assertNotNull(repo.dao.book(b))
        val snapshot=repo.snapshot(repo.dao.book(a)!!).first()
        assertEquals(s.getString("event"),snapshot.events.single().id);assertEquals(9400L,snapshot.balance(snapshot.accounts.single()))
        assertEquals("500",snapshot.draft!!.amount);assertEquals("続きの原名",snapshot.draft!!.merchant)
        assertEquals(listOf(1,3,5,7),repo.mediaFile(snapshot.files.single{it.id==s.getString("file")}).readBytes().map{it.toInt()})
        assertNull(vm.error.value);shot("restored-originals")
        s.put("pid",Process.myPid());marker.writeText(s.toString())
        println("MANAGEMENT_RECOVERY restored pid=${Process.myPid()}")
    }
    @Test fun anotherColdStartContinuesTheSameUnpostedDraftAndCleansOnlyItsFixture()=runBlocking {
        val s=state();assertNotEquals(s.getInt("pid"),Process.myPid())
        ui.setContent{NativeApp(vm)}
        val a=s.getString("book")
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==a}
        tap("home-resume-draft")
        ui.onNodeWithTag("field-お店 / 買ったもの").assertTextEquals("続きの原名")
        ui.waitUntil(10000){vm.route.value=="entry"&&vm.entry.value?.merchant=="続きの原名"}
        assertEquals("500",vm.entry.value!!.amount);assertEquals("再起動後も未保存",vm.entry.value!!.note)
        val snapshot=repo.snapshot(repo.dao.book(a)!!).first()
        assertEquals(s.getString("event"),snapshot.events.single().id);assertEquals(9400L,snapshot.balance(snapshot.accounts.single()))
        assertNotNull(repo.dao.book(s.getString("trash")));assertTrue(repo.mediaFile(snapshot.files.single()).exists())
        shot("cold-draft");assertNull(vm.error.value)
        println("MANAGEMENT_RECOVERY final pid=${Process.myPid()}")
        cleanup=true
    }
}
