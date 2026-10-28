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
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.domain.finance.*
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
class CreditLimitFlowTest {
    @get:Rule val ui=createComposeRule()
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var vm:AppViewModel
    @Before fun open() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        repo=LedgerRepository(db,Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"),ZoneId.of("Asia/Tokyo")))
        vm=AppViewModel(repo,AppPreferences(context), testReferenceRates(AppPreferences(context),repo.clock))
        ui.setContent{NativeApp(vm)}
    }
    @After fun close(){vm.viewModelScope.cancel();db.close()}
    private fun tapTag(tag:String,ancestor:String?=null) {
        val matcher=if(ancestor==null)hasTestTag(tag)else hasTestTag(tag) and hasAnyAncestor(hasTestTag(ancestor))
        ui.waitUntil(10000){!vm.busy.value&&ui.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()}
        val node=ui.onNode(matcher)
        node.performScrollTo();ui.waitForIdle()
        if(ui.onAllNodesWithTag("sheet-header").fetchSemanticsNodes().isNotEmpty()) {
            val density=InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            val header=ui.onNodeWithTag("sheet-header").getUnclippedBoundsInRoot().bottom.value
            val top=node.getUnclippedBoundsInRoot().top.value
            if(top<header+8)ui.onNodeWithTag("sheet-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,-(header+8-top)*density)}
        }
        ui.waitForIdle()
        if(ui.onAllNodesWithTag("sheet-scroll").fetchSemanticsNodes().isNotEmpty()) {
            val density=InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            val bottom=node.getUnclippedBoundsInRoot().bottom.value
            val viewport=ui.onNodeWithTag("sheet-scroll").getUnclippedBoundsInRoot().bottom.value
            if(bottom>viewport)ui.onNodeWithTag("sheet-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,(bottom-viewport+8)*density)}
        }
        ui.waitForIdle();node.performClick();ui.waitForIdle()
    }
    private fun textField(label:String,ancestor:String?=null):SemanticsNodeInteraction {
        val matcher=hasTestTag("field-$label")
        return ui.onNode(if(ancestor==null)matcher else matcher and hasAnyAncestor(hasTestTag(ancestor)))
    }
    private fun shot(name:String) {
        if (!visualEnabled()) return
        ui.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(350,5000)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.getExternalFilesDir(null),"phase-c-limits/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use{ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        if(name.endsWith("-ime")||name.endsWith("-history")) {
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(file.parentFile,"$name-screen.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
    @Test fun limitsObservationsAndSharedPoolSaveWithoutCashInEveryThemeAndLanguage() {
        for((theme,language) in testLooks(this@CreditLimitFlowTest)) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val (book,card,other)=runBlocking {
                val b=repo.createBook("利用枠 $theme $language",Currency.JPY,theme,language,startDate="2026-10-01")
                repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
                repo.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
                val a=repo.saveAccount(b,"カード A",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,4000),"2026-10-01")
                val o=repo.saveAccount(b,"カード B",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
                for((id,used) in listOf(a to 4000L,o to 0L))repo.commitCreditLimit(repo.previewCreditLimit(b,CreditLimitInput(id,10000,null,null,CreditLimitSharing.TOTAL_ONLY,null,"2026-10-15","2026-10-15",used,null,null,LimitRecovery.MANUAL,CreditLimitUsageBasis.PRINCIPAL_ONLY,"Asia/Tokyo",CreditLimitUsageStart.TRANSACTION_DATE)))
                vm.preferences.select(b);Triple(b,a,o)
            }
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            ui.runOnIdle{vm.navigate("accounts")}
            tapTag("account-$card");tapTag("account-financial");tapTag("analysis-tab-limits")
            ui.waitUntil(10000){vm.creditLimitState.value?.book?.id==book}
            shot("$theme-$language-limits")
            tapTag("limit-configure")
            val total=textField(text("总额度 / 未知留空","総枠・不明は空欄"));total.performScrollTo()
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true")tapTag("field-"+text("总额度 / 未知留空","総枠・不明は空欄"))
            total.performTextReplacement("12000")
            textField(text("总占用基准 / 未知留空","総使用額の基準・不明は空欄")).performScrollTo().performTextReplacement("4000")
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true") {
                ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().any{androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}}
                shot("$theme-$language-limit-form-ime")
            }
            tapTag("limit-preview");ui.onNodeWithTag("limit-confirm").assertExists();shot("$theme-$language-limit-review")
            runBlocking{repo.saveAccount(book,"カード A 更新",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,4000),"2026-10-01",card)}
            tapTag("limit-confirm")
            ui.waitUntil(10000){!vm.busy.value&&ui.onAllNodesWithTag("limit-preview").fetchSemanticsNodes().isNotEmpty()}
            assertEquals(1,runBlocking{db.creditLimitDao().graph(book).first()!!.configs.count{it.accountId==card}})
            textField(text("总额度 / 未知留空","総枠・不明は空欄")).assertTextEquals("12000")
            tapTag("limit-preview");tapTag("limit-confirm")
            ui.waitUntil(10000){!vm.busy.value&&vm.creditLimitState.value?.configs?.count{it.accountId==card}==2}
            tapTag("limit-observe")
            textField(text("平台显示可用金额","返済先の利用可能額")).performScrollTo().performTextReplacement("7800")
            tapTag("limit-fact-preview");tapTag("limit-confirm")
            ui.waitUntil(10000){!vm.busy.value&&vm.creditLimitState.value?.observations?.size==1}
            tapTag("limit-group-add")
            textField(text("共享池名称","共通枠の名前")).performScrollTo().performTextReplacement("共同枠")
            textField(text("共享额度上限 / 未知留空","共通枠の上限・不明は空欄")).performScrollTo().performTextReplacement("15000")
            textField(text("共享池占用基准 / 未知留空","共通枠の使用額基準・不明は空欄")).performScrollTo().performTextReplacement("4000")
            ui.onNode(hasText("カード B") and hasClickAction()).performScrollTo().performClick()
            tapTag("limit-group-preview");shot("$theme-$language-shared-review");tapTag("limit-confirm")
            ui.waitUntil(10000){!vm.busy.value&&vm.creditLimitState.value?.groups?.size==1}
            ui.onNodeWithTag("limit-estimate").performScrollTo();shot("$theme-$language-limits-saved")
            tapTag("limit-history");shot("$theme-$language-limit-history")
            val saved=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(4000L,saved.balance(saved.accounts.first{it.id==card}));assertEquals(0L,saved.balance(saved.accounts.first{it.id==other}));assertTrue(saved.events.isEmpty())
            assertEquals(7800L,runBlocking{db.creditLimitDao().graph(book).first()!!.observations.single().availableMinor})
            val payment=runBlocking {
                val bank=saved.accounts.first{it.name=="銀行"}.id
                repo.commitFinancialLedger(repo.previewRepayment(book,FinancialRepaymentInput(bank,card,Money(Currency.JPY,1000),"2026-10-15",Money(Currency.JPY,1000),Money(Currency.JPY,1000),Money(Currency.JPY,0),Money(Currency.JPY,0),platformConfirmed=true,clearingDate="2026-10-15")))!!
            }
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.repayments?.any{it.eventId==payment}==true}
            tapTag("limit-recover-$payment")
            textField(text("已实际恢复本金","実際に回復した元金")).performScrollTo().performTextReplacement("400")
            tapTag("limit-fact-preview");shot("$theme-$language-recovery-review");tapTag("limit-confirm")
            ui.waitUntil(10000){!vm.busy.value&&vm.creditLimitState.value?.recoveries?.size==1}
            ui.onNode(hasText(text("作废这次恢复确认","この回復確認を無効にする")) and hasClickAction()).performScrollTo().performClick()
            tapTag("limit-void-confirm")
            ui.waitUntil(10000){!vm.busy.value&&vm.creditLimitState.value?.recoveries?.single()?.voidedAt!=null}
            val after=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(1,after.events.size);assertEquals(3000L,after.balance(after.accounts.first{it.id==card}));assertEquals(9000L,after.balance(after.accounts.first{it.name=="銀行"}))
            assertNull(vm.error.value)
        }
    }
}
