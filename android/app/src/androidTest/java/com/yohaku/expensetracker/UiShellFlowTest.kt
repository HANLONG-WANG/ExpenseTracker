package com.yohaku.expensetracker

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.finance.*
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
class UiShellFlowTest {
    @get:Rule(order=-2) val testPreferences=TestAppPreferencesRule()
    @get:Rule(order=0) val ui=createComposeRule()
    @get:Rule(order=1) val failureArtifacts=RegressionFailureArtifacts(ui)
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var vm:AppViewModel
    private lateinit var restoration:StateRestorationTester

    @Before fun open() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        repo=LedgerRepository(db,Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"),ZoneId.of("Asia/Tokyo")))
        val prefs=testPreferences.preferences
        runBlocking{prefs.clearSetup()}
        vm=AppViewModel(repo,prefs,testReferenceRates(prefs,repo.clock))
        restoration=StateRestorationTester(ui)
        restoration.setContent{NativeApp(vm)}
    }
    @After fun close(){vm.viewModelScope.cancel();db.close()}
    private fun snapshot(book:String)=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
    private fun waitTag(tag:String) {ui.waitUntil(10000){!vm.busy.value&&ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}}
    private fun tapTag(tag:String) {waitTag(tag);ui.onNodeWithTag(tag).scrollIfScrollable().performClick()}
    private fun tap(text:String) {
        ui.waitUntil(10000){!vm.busy.value&&ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText(text).scrollIfScrollable().performClick()
    }
    private fun selectBook(book:String) {
        ui.runOnIdle{vm.selectBook(book)}
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
    }
    private fun shot(name:String) {
        if(!visualEnabled())return
        ui.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(350,5000)
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix").orEmpty()
        val directory=File(instrumentation.targetContext.getExternalFilesDir(null),"ui-shell$suffix").also{it.mkdirs()}
        val bitmap=instrumentation.uiAutomation.takeScreenshot()
        try {File(directory,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
    }

    @Test fun accountEditRestoresIdentityAndMissingTargetNeverBecomesCreation() {
        for((theme,language) in testLooks(this))for(setup in listOf(false,true)) {
            val (book,account)=runBlocking {
                val book=repo.createBook("Account edit",Currency.JPY,theme,language,startDate="2026-10-01")
                repo.updateBook(repo.dao.book(book)!!.copy(initialized=!setup,setupStep=2))
                book to repo.saveAccount(book,"Bank B",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
            }
            selectBook(book)
            fun openEditor() {
                if(setup)tapTag("setup-account-$account") else {
                    ui.runOnIdle{vm.navigate("accounts")}
                    tapTag("account-$account");tapTag("account-detail-edit")
                }
                waitTag("account-form")
            }
            openEditor()
            val name=if(language=="ja")"口座名" else "账户名称"
            val opening=if(language=="ja")"期首残高" else "期初余额"
            ui.onNodeWithTag("field-$name").performScrollTo().performTextReplacement("Bank B edited")
            ui.onNodeWithTag("field-$opening").performScrollTo().performTextReplacement("12000")
            ui.settleKeyboard()
            restoration.emulateSavedInstanceStateRestore()
            waitTag("account-form")
            ui.onNodeWithTag("field-$name").assertTextEquals("Bank B edited")
            ui.onNodeWithTag("field-$opening").assertTextEquals("12000")
            shot("$theme-$language-$setup-account-restored")
            tapTag("account-save-button")
            ui.waitUntil(10000){!vm.busy.value&&ui.onAllNodesWithTag("account-form").fetchSemanticsNodes().isEmpty()}
            val saved=snapshot(book)
            assertEquals(account,saved.accounts.single().id)
            assertEquals("Bank B edited",saved.accounts.single().name)
            assertEquals(12000L,saved.accounts.single().openingMinor)
            assertEquals(12000L,saved.balance(saved.accounts.single()))
            assertTrue(saved.events.isEmpty())

            openEditor()
            // An unused account can disappear through another operation while the sheet is open.
            runBlocking{repo.dao.deleteAccount(book,account)}
            waitTag("account-edit-missing")
            restoration.emulateSavedInstanceStateRestore()
            waitTag("account-edit-missing")
            ui.onNodeWithTag("account-save-button").assertDoesNotExist()
            shot("$theme-$language-$setup-account-missing")
            tapTag("account-edit-missing-close")
            assertTrue(snapshot(book).accounts.isEmpty())
        }
    }

    @Test fun actualIncomeFromRiskAndFinancialOverviewKeepsRequestedAccount() {
        for((theme,language) in testLooks(this))for(fromFinance in listOf(false,true)) {
            val ids=runBlocking {
                val book=repo.createBook("Income target",Currency.JPY,theme,language,startDate="2026-10-01")
                repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
                val bankA=repo.saveAccount(book,"Bank A",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
                val bankB=repo.saveAccount(book,"Bank B",AccountType.BANK,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
                val category=repo.saveCategory(book,"Salary",EntryKind.INCOME,null)
                repo.saveEntry(book,EntryDraft(kind=EntryKind.INCOME,accountId=bankA,categoryId=category,amount="100",date="2026-10-15"))
                val card=repo.saveAccount(book,"Card",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,1000),"2026-10-01")
                repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(book,ManualFinancialPlanInput(card,"Bill",FinanceMode.SINGLE,listOf(KnownBillInput("2026-11-05",1000,1000,0,bankB)),principalMinor=1000,opening=true)))
                // Exercise the saved pending request as well as the immediate entry path.
                if(!fromFinance)repo.saveDraft(book,EntryDraft(accountId=bankA,note="Existing draft"))
                listOf(book,bankA,bankB,category,card)
            }
            val (book,bankA,bankB,category,card)=ids
            selectBook(book)
            assertEquals(bankA,snapshot(book).defaultEntryAccount(EntryKind.INCOME))
            ui.runOnIdle{vm.navigate("accounts")}
            tapTag("account-${if(fromFinance)card else bankB}")
            if(fromFinance) {
                tapTag("account-financial")
                tap(if(language=="ja")"実入金の記録を補完" else "补记已实际到账收入")
            } else {
                tapTag("account-cash-risk")
                waitTag("risk-actual-income-$bankB")
                shot("$theme-$language-risk")
                tapTag("risk-actual-income-$bankB")
                val discard=if(language=="ja")"下書きを破棄して新しく記録" else "放弃草稿，记新的一笔"
                ui.waitUntil(10000){ui.onAllNodesWithText(discard).fetchSemanticsNodes().isNotEmpty()}
                restoration.emulateSavedInstanceStateRestore()
                shot("$theme-$language-pending-income")
                tap(discard)
            }
            ui.waitUntil(10000){!vm.busy.value&&vm.entry.value?.kind==EntryKind.INCOME}
            assertEquals(bankB,vm.entry.value!!.accountId)
            ui.runOnIdle{vm.changeEntry(book,vm.entry.value!!.copy(categoryId=category,amount="500"));vm.navigate("entry")}
            ui.waitForIdle();ui.settleKeyboard()
            val accountLabel=if(language=="ja")"入金口座" else "到账账户"
            ui.onNodeWithTag("select-trigger-$accountLabel").performScrollTo().assertTextContains("Bank B")
            shot("$theme-$language-$fromFinance-income-target")
            ui.runOnIdle{vm.saveEntry(book)}
            ui.waitUntil(10000){!vm.busy.value&&vm.entry.value==null}
            val saved=snapshot(book)
            assertEquals(bankB,saved.visibleEvents.single{it.amountMinor==500L}.accountId)
            assertEquals(10100L,saved.balance(saved.accounts.single{it.id==bankA}))
            assertEquals(500L,saved.balance(saved.accounts.single{it.id==bankB}))
            assertEquals(2,saved.visibleEvents.size)
            assertNull(vm.error.value)
        }
    }

    @Test fun invalidIncomeTargetReportsErrorWithoutOpeningDraft() {
        val book=runBlocking {
            val book=repo.createBook("Invalid income target",Currency.JPY)
            repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
            repo.saveAccount(book,"Bank A",AccountType.BANK,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
            book
        }
        selectBook(book)
        val before=snapshot(book)
        ui.runOnIdle{assertFalse(vm.startEntry(before,EntryKind.INCOME,accountId="missing"))}
        assertNull(vm.entry.value)
        assertNotNull(vm.error.value)
        assertEquals("home",vm.route.value)
        assertNull(snapshot(book).draft)
    }
}
