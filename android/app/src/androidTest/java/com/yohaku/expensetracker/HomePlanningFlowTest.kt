package com.yohaku.expensetracker

import android.graphics.Bitmap
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
import java.io.File
import java.time.*

@RunWith(AndroidJUnit4::class)
class HomePlanningFlowTest {
    @get:Rule val ui=createComposeRule()
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var vm:AppViewModel
    @Before fun open(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        repo=LedgerRepository(db,Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"),ZoneOffset.UTC))
        vm=AppViewModel(repo,AppPreferences(context));ui.setContent{NativeApp(vm)}
    }
    @After fun close(){vm.viewModelScope.cancel();db.close()}
    private fun shot(name:String,anchor:String="home-cover"){
        ui.onNodeWithTag(anchor).performScrollTo();ui.waitForIdle()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.getExternalFilesDir(null),"phase-b-home-planning/$name.png");file.parentFile!!.mkdirs()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(100,5000)
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun allowanceUsesReservesExcludedScenesUnknownAmountsAndActualConfirmation(){
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")) {
            val ids=runBlocking {
                val book=repo.createBook("首页 $theme $language",Currency.JPY,theme,language,startDate="2026-10-01",budgetMinor=100000)
                repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
                val account=repo.saveAccount(book,"银行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,200000),"2026-10-01")
                val category=repo.saveCategory(book,"生活",EntryKind.EXPENSE,null)
                repo.setActivityMode(book,true)
                val scene=repo.saveActivity(book,"日常活动","2026-10-01","2026-10-31",Money(Currency.JPY,40000))
                val excluded=repo.saveActivity(book,"独立旅行","2026-10-01","2026-10-31",Money(Currency.JPY,30000),excludeMonthly=true)
                repo.saveEntry(book,EntryDraft(accountId=account,categoryId=category,amount="10000",date="2026-10-02"))
                repo.saveEntry(book,EntryDraft(accountId=account,categoryId=category,amount="6000",date="2026-10-03",activityId=excluded))
                repo.saveRecurrence(book,RecurrenceInput("房租",account,category,Money(Currency.JPY,20000),null,false,RecurrenceFrequency.MONTHLY,"2026-10-20"))
                assertTrue(repo.commitReserve(repo.previewReserve(book,ReserveInput(scene,"日常预留","2026-10-14",Money(Currency.JPY,10000)))))
                assertTrue(repo.commitReserve(repo.previewReserve(book,ReserveInput(excluded,"独立预留","2026-10-14",Money(Currency.JPY,15000)))))
                vm.preferences.select(book);listOf(book,account,category)
            }
            val book=ids[0];val account=ids[1];val category=ids[2]
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            ui.runOnIdle{vm.navigate("home")}
            ui.onNodeWithTag("cover-available").assertTextContains("60,000",substring=true)
            if(theme!="magazine")ui.onNodeWithTag("cover-reserved").assertTextContains("30,000",substring=true)
            val known=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(25000L,known.budgetProjection("2026-10").fundReserveMinor)
            assertEquals(184000L,known.balance(known.accounts.single()))
            shot("$theme-$language-known")
            val expense=runBlocking{repo.saveEntry(book,EntryDraft(accountId=account,categoryId=category,amount="100",originalCurrency=Currency.USD,accountAmount="15000",settlementConfirmed=false,date="2026-10-04"))}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.events?.any{it.id==expense}==true}
            ui.onNodeWithTag("cover-available").assertTextContains("45,000",substring=true)
            ui.onNodeWithTag("budget-estimate-note").assertTextContains("1",substring=true)
            ui.onNode(hasText("¥31,000") and hasAnyAncestor(hasTestTag("home-spend-context"))).assertExists()
            shot("$theme-$language-estimated")
            ui.runOnIdle{vm.navigate("budget")}
            ui.onNodeWithTag("budget-estimate-note").performScrollTo().assertIsDisplayed().assertTextContains("1",substring=true)
            ui.runOnIdle{vm.navigate("home")}
            runBlocking{repo.confirmSettlement(book,SettlementConfirmation(expense,1,Money(Currency.JPY,15100),Money(Currency.JPY,15100),"2026-10-05"))}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.settlements?.firstOrNull{it.eventId==expense}?.confirmed==true}
            ui.onNodeWithTag("budget-estimate-note").assertDoesNotExist()
            ui.onNodeWithTag("cover-available").assertTextContains("44,900",substring=true)
            ui.onNode(hasText("¥31,100") and hasAnyAncestor(hasTestTag("home-spend-context"))).assertExists()
            shot("$theme-$language-confirmed")
            runBlocking{repo.saveRecurrence(book,RecurrenceInput("未知安排",account,category,null,null,true,RecurrenceFrequency.MONTHLY,"2026-10-21"))}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.pendingOccurrences()?.size==2&&!vm.busy.value}
            ui.onNodeWithTag("cover-available").assertTextContains(if(language=="ja")"要補完"else"待补齐",substring=true)
            if(theme!="magazine")ui.onNodeWithTag("cover-reserved").assertTextContains(if(language=="ja")"要補完"else"待补齐",substring=true)
            shot("$theme-$language-unknown")
            val final=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertNull(final.budgetProjection("2026-10").availableMinor)
            assertEquals(168900L,final.balance(final.accounts.single()))
            runBlocking{assertTrue(repo.commitBudget(repo.previewBudget(book,"2026-10",null,null)))}
            ui.runOnIdle{vm.navigate("budget")}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.budgetLimit("2026-10")==null&&!vm.busy.value}
            ui.onNodeWithTag("lead-amount").assertTextContains(if(language=="ja")"未設定・未補足"else"未设置 / 待补齐",substring=true)
            shot("$theme-$language-unset-budget","lead-cover")
            assertEquals(168900L,runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}.balance(final.accounts.single()))

        }
    }
}
