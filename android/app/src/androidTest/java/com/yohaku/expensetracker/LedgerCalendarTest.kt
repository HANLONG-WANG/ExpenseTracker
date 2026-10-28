package com.yohaku.expensetracker

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
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
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.*

@RunWith(AndroidJUnit4::class)
class LedgerCalendarTest {
    @get:Rule val ui=createComposeRule()
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var vm:AppViewModel
    @Before fun open() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        repo=LedgerRepository(db,Clock.fixed(Instant.parse("2026-10-26T03:00:00Z"),ZoneOffset.UTC))
        vm=AppViewModel(repo,AppPreferences(context), testReferenceRates(AppPreferences(context),repo.clock));ui.setContent{NativeApp(vm)}
    }
    @After fun close(){vm.viewModelScope.cancel();db.close()}
    private fun shot(name:String) {
        if (!visualEnabled()) return
        ui.waitForIdle()
        val file=File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"ledger-calendar/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use{ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
    }

    @Test fun calendarShowsDailyNetSpendingAndOpensTheChosenDay() {
        val amounts=mapOf(1 to 1280,2 to 2620,3 to 12800,5 to 980,6 to 2350,7 to 1640,9 to 1980,10 to 15400,11 to 3200,12 to 760,13 to 1890,14 to 300,15 to 2480,16 to 2720,17 to 4850,18 to 2100,19 to 1150,21 to 3480,22 to 1920,23 to 2300,25 to 2750,26 to 1210)
        for((theme,language) in testLooks(this@LedgerCalendarTest)) {
            val book=runBlocking {
                val book=repo.createBook("日常 $theme $language",Currency.JPY,startDate="2026-09-01",budgetMinor=120000,theme=theme,language=language)
                repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
                val cash=repo.saveAccount(book,"现金",AccountType.CASH,Currency.JPY,Money(Currency.JPY,300000),"2026-09-01")
                val food=repo.saveCategory(book,"餐饮",EntryKind.EXPENSE,null)
                val daily=repo.saveCategory(book,"日用",EntryKind.EXPENSE,null)
                val salary=repo.saveCategory(book,"工资",EntryKind.INCOME,null)
                amounts.forEach{(day,amount)->repo.saveEntry(book,EntryDraft(accountId=cash,categoryId=food,amount=amount.toString(),date="2026-10-%02d".format(day),merchant="店 $day"))}
                repo.saveEntry(book,EntryDraft(accountId=cash,categoryId=food,amount="680",date="2026-10-24",merchant="喫茶と余白"))
                repo.saveEntry(book,EntryDraft(accountId=cash,categoryId=daily,amount="5480",date="2026-10-24",merchant="无印良品"))
                repo.saveEntry(book,EntryDraft(accountId=cash,categoryId=food,amount="2800",date="2026-10-24",merchant="鸟贵族"))
                repo.saveEntry(book,EntryDraft(kind=EntryKind.INCOME,accountId=cash,categoryId=salary,amount="250000",date="2026-10-24",merchant="工资"))
                repo.saveEntry(book,EntryDraft(accountId=cash,categoryId=food,amount="900",date="2026-09-30",merchant="上个月"))
                vm.preferences.select(book);book
            }
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book}
            ui.runOnIdle{vm.navigate("ledger")}
            ui.onNodeWithTag("ledger-view-calendar").performScrollTo().performClick()
            ui.onNodeWithTag("calendar-day-2026-10-24").performScrollTo()
            ui.onNodeWithTag("ledger-view-tabs").performScrollTo()
            shot("$theme-$language-month")
            // Day total: three expenses only; the same-day income is listed but not counted.
            ui.onNodeWithTag("calendar-day-2026-10-24").performScrollTo().performClick()
            ui.onNodeWithTag("calendar-day-panel").performScrollTo()
            ui.onNodeWithText("¥8,960",substring=true).assertExists()
            ui.onNodeWithTag("calendar-add-day").performScrollTo()
            shot("$theme-$language-day")
            ui.onNodeWithTag("calendar-prev").performScrollTo().performClick()
            ui.onNodeWithTag("calendar-month").assertTextContains(if(theme=="magazine")"09" else "9月",substring=true)
            ui.onNodeWithTag("calendar-day-2026-09-30").assertExists()
            ui.onNodeWithTag("calendar-next").performClick()
            ui.onNodeWithTag("page-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,-100000f)}
        }
        ui.onNodeWithTag("calendar-day-2026-10-24").performScrollTo().performClick()
        ui.onNodeWithTag("calendar-add-day").performScrollTo().performClick()
        ui.waitUntil(10000){vm.entry.value!=null}
        assertEquals("2026-10-24",vm.entry.value!!.date)
    }
}
