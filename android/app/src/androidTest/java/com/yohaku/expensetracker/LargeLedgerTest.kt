package com.yohaku.expensetracker

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.db.YohakuDatabase
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.csv.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.features.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import java.time.*

@RunWith(AndroidJUnit4::class)
class LargeLedgerTest {
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

    /** About three years of fictional history imported from CSV, the size that made the ledger crash. */
    @Test fun threeThousandImportedRecordsStayUsable() {
        org.junit.Assume.assumeTrue("Requires perf=true", testOption("perf"))
        val expense=listOf("食費","交通費","日用品","外食","娯楽","衣服","医療","通信","家賃","水道光熱","交際費","書籍","美容","旅行","家具","雑費")
        val income=listOf("給料","臨時収入","退款","送金")
        val random=java.util.Random(7)
        val rows=(0 until 3700).map{i->
            val date=LocalDate.of(2023,8,17).plusDays((i*1167L)/3700)
            if(i%40==0)"收入,$date,${income[i/40%4]},${random.nextInt(200000)+1000},架空 $i"
            else "支出,$date,${expense[random.nextInt(expense.size)]},${random.nextInt(5000)+100},${if(i%4==0)"" else "架空の店 ${i%300}"}"
        }
        val bytes=("账簿,日期,类别,金额,备注\n"+rows.joinToString("\n")).toByteArray()
        val book=runBlocking {
            val book=repo.createBook("大量记录",Currency.JPY,startDate="2026-10-01",budgetMinor=150000)
            repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
            val cash=repo.saveAccount(book,"现金",AccountType.CASH,Currency.JPY,Money(Currency.JPY,52000),"2026-10-27")
            val document=CsvParser.read(bytes)
            val settings=CsvParser.guess(document).copy(accountId=cash)
            val preview=repo.csvPreview(book,document,settings,emptyMap())
            repo.importCsv(book,"fictional.csv",CsvParser.hash(bytes),document,settings,emptyMap(),preview)
            vm.preferences.select(book);book
        }
        ui.waitUntil(30000){(vm.state.value as? AppState.Ready)?.snapshot?.let{it.book.id==book&&it.events.size==3700}==true}
        ui.runOnIdle{vm.navigate("analysis")}
        ui.onNodeWithText("80 ",substring=true).assertExists()
        // Composing every row at once ran out of memory; the lazy list reaches the oldest day directly.
        ui.runOnIdle{vm.navigate("ledger")}
        ui.onNodeWithTag("transaction-list-day-2026-10-26").assertExists()
        ui.onNodeWithTag("page-scroll").performScrollToKey("page-foot")
        ui.onNodeWithTag("transaction-list-day-2023-08-17").assertExists()
        ui.runOnIdle{vm.navigate("accounts")}
        ui.onNodeWithText("现金").performScrollTo().performClick()
        ui.onNodeWithTag("page-scroll").performScrollToKey("page-foot")
    }
}
