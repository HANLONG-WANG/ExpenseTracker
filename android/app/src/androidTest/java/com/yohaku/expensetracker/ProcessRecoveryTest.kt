package com.yohaku.expensetracker

import android.os.Process
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.db.YohakuDatabase
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

/** Run each step in a separate instrumentation process, with host force-stop between steps. */
@RunWith(AndroidJUnit4::class)
class ProcessRecoveryTest {
    @get:Rule val ui=createComposeRule()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val marker get()=File(context.filesDir,"verification/process-recovery.json")
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var vm:AppViewModel
    private val prefs get()=AppPreferences(context)
    @Before fun open(){
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("processRecovery")=="true")
        db=Room.databaseBuilder(context,YohakuDatabase::class.java,"phase-a-process-check.db").build()
        repo=LedgerRepository(db)
        vm=AppViewModel(repo,prefs)
    }
    @After fun close(){if(::vm.isInitialized)vm.viewModelScope.cancel();if(::db.isInitialized)db.close()}
    private fun waitFor(text:String){ui.waitUntil(10000){ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}}
    @Test fun seedSetup(){
        assertFalse("Do not overwrite an unfinished recovery check",marker.exists())
        val data=runBlocking{
            val previous=prefs.activeBook.first()
            val book=repo.createBook("进程恢复验证",Currency.JPY,"journal","ja")
            repo.setupStep(book,3)
            prefs.select(book)
            JSONObject().put("book",book).put("previous",previous?:"").put("pid",Process.myPid())
        }
        marker.parentFile!!.mkdirs();marker.writeText(data.toString())
    }
    @Test fun verifySetupAndSeedDraft(){
        val data=JSONObject(marker.readText());assertNotEquals(data.getInt("pid"),Process.myPid())
        ui.setContent{NativeApp(vm)}
        waitFor("今ある借入も、ここから。")
        val id=data.getString("book")
        runBlocking{
            val book=repo.dao.book(id)!!
            assertEquals(3,book.setupStep);assertFalse(book.initialized)
            assertEquals("journal",book.theme);assertEquals("ja",book.language)
            val account=repo.saveAccount(id,"现金",AccountType.CASH,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
            repo.saveCategory(id,"餐饮",EntryKind.EXPENSE,null)
            val root=repo.dao.categories(id).first().single()
            repo.saveCategory(id,"咖啡",EntryKind.EXPENSE,root.id)
            val child=repo.dao.categories(id).first().first{it.parentId!=null}
            repo.updateBook(book.copy(initialized=true,language="zh"))
            repo.saveDraft(id,EntryDraft(accountId=account,categoryId=child.id,amount="777",merchant="喫茶と余白",note="继续这一笔"))
        }
        data.put("pid",Process.myPid());marker.writeText(data.toString())
    }
    @Test fun verifyDraftAndCleanup(){
        val data=JSONObject(marker.readText());assertNotEquals(data.getInt("pid"),Process.myPid())
        ui.setContent{NativeApp(vm)}
        waitFor("继续本账本未保存的记录")
        ui.onNodeWithText("继续本账本未保存的记录").performScrollTo().performClick()
        waitFor("账户实际入账金额 · JPY")
        ui.onNodeWithTag("field-金额 · JPY").assertTextEquals("777")
        ui.onNodeWithTag("field-商户 / 买了什么").assertTextEquals("喫茶と余白")
        ui.onNodeWithTag("field-备注").performScrollTo().assertTextEquals("继续这一笔")
        runBlocking{
            val snapshot=repo.snapshot(repo.dao.book(data.getString("book"))!!).first()
            assertEquals("餐饮 / 咖啡",snapshot.path(snapshot.draft!!.categoryId))
            assertEquals(10000L,snapshot.balance(snapshot.accounts.single()))
            assertEquals(0,snapshot.events.size)
            prefs.select(data.getString("previous"))
        }
        vm.viewModelScope.cancel();db.close()
        context.deleteDatabase("phase-a-process-check.db")
        marker.delete()
    }
}
