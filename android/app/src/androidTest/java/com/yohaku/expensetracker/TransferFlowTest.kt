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
class TransferFlowTest {
    @get:Rule val ui=createComposeRule()
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var vm:AppViewModel
    @Before fun open() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        repo=LedgerRepository(db,Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"),ZoneOffset.UTC));vm=AppViewModel(repo,AppPreferences(context),ReferenceRates(TestReferenceRateStore().apply{automaticReferenceRates.value=false},clock=repo.clock))
        ui.setContent{NativeApp(vm)}
    }
    @After fun close(){vm.viewModelScope.cancel();db.close()}
    private fun target(node:SemanticsNodeInteraction):SemanticsNodeInteraction {
        ui.waitUntil(10000){!vm.busy.value};node.performScrollTo();ui.waitForIdle()
        val density=InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val header=ui.onNodeWithTag("sheet-header").getUnclippedBoundsInRoot().bottom.value
        val top=node.getUnclippedBoundsInRoot().top.value
        if(top<header+8)ui.onNodeWithTag("sheet-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,-(header+8-top)*density)}
        ui.waitForIdle();val bottom=node.getUnclippedBoundsInRoot().bottom.value
        val viewport=ui.onNodeWithTag("sheet-scroll").getUnclippedBoundsInRoot().bottom.value
        if(bottom>viewport)ui.onNodeWithTag("sheet-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,(bottom-viewport+8)*density)}
        ui.waitForIdle();return node
    }
    private fun tap(text:String) {
        ui.waitUntil(10000){ui.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()&&!vm.busy.value}
        target(ui.onNode(hasText(text) and hasClickAction())).performClick()
    }
    private fun field(label:String,value:String) {
        val node=target(ui.onNodeWithTag("field-$label"));node.performClick();node.performTextReplacement(value)
        if(InstrumentationRegistry.getArguments().getString("realIme")=="true")ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().any{androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}}
    }
    private fun choose(label:String,index:Int){target(ui.onNodeWithTag("select-trigger-$label")).performClick();target(ui.onNodeWithTag("select-option-$label-$index")).performClick()}
    private fun date(index:Int,date:String,previous:Boolean=false,language:String) {
        target(ui.onNodeWithTag("date-field-$index")).performClick();ui.onAllNodesWithTag("shared-calendar").assertCountEquals(1)
        if(previous)target(ui.onNodeWithContentDescription(if(language=="ja")"前の月"else"上一月")).performClick()
        target(ui.onNodeWithContentDescription(date)).performClick()
    }
    private fun shot(name:String,reset:Boolean=true) {
        if(reset)ui.onNodeWithTag("sheet-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,-100000f)};ui.waitForIdle()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.getExternalFilesDir(null),"phase-b-p09/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use{ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        val auto=InstrumentationRegistry.getInstrumentation().uiAutomation;auto.waitForIdle(300,5000);val screen=auto.takeScreenshot()
        File(file.parentFile,"$name-screen.png").outputStream().use{screen.compress(Bitmap.CompressFormat.PNG,100,it)};screen.recycle()
    }
    @Test fun accountShortcutTransitConfirmationAndOriginalIdEditInEveryThemeAndLanguage() {
        for(theme in listOf("magazine","journal","objects").filter{InstrumentationRegistry.getArguments().getString("theme")?.let{wanted->it==wanted}?:true})for(language in listOf("zh","ja").filter{InstrumentationRegistry.getArguments().getString("language")?.let{wanted->it==wanted}?:true}) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val setup=runBlocking {
                val book=repo.createBook("换汇 $theme $language",Currency.JPY,theme,language)
                repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
                val from=repo.saveAccount(book,"银行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,100000),"2026-09-01")
                val to=repo.saveAccount(book,"人民币",AccountType.BANK,Currency.CNY,Money(Currency.CNY,0),"2026-09-01")
                vm.preferences.select(book)
                Triple(book,from,to)
            }
            val (book,source,target)=setup
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book}
            ui.runOnIdle{vm.navigate("accounts")}
            ui.onNodeWithTag("account-$source").performScrollTo().performClick()
            ui.onNodeWithTag("account-detail-transfer").performScrollTo().performClick()
            ui.waitUntil(10000){ui.onAllNodesWithTag("transfer-form").fetchSemanticsNodes().isNotEmpty()}
            assertEquals(source,vm.entry.value!!.accountId)
            field(text("手填汇率","手入力レート")+" · 1 JPY = CNY","0.05")
            field(text("实际扣款总额","実際の出金総額")+" · JPY","30000")
            ui.onNodeWithTag("field-"+text("实际到账净额","実際の入金純額")+" · CNY").performScrollTo().assertTextEquals("1500.00")
            field(text("明确收取的手续费 / 可留空","確定手数料・空欄可")+" · JPY","750")
            tap(text("估算未确认的到账额","未確認の入金額を見積もる"))
            ui.waitUntil(10000){vm.entry.value?.toAmount=="1462.50"&&!vm.busy.value}
            val stateLabel=text("资金状态","資金の状態")
            ui.onNodeWithTag("select-trigger-$stateLabel").performScrollTo().performClick()
            tap(text("已转出，尚在途中","出金済・送金中"))
            ui.onNodeWithTag("date-field-0").performScrollTo().performClick()
            ui.onAllNodesWithTag("shared-calendar").assertCountEquals(1)
            ui.onNodeWithTag("date-field-1").performScrollTo().performClick()
            ui.onAllNodesWithTag("shared-calendar").assertCountEquals(1)
            ui.onNodeWithTag("calendar-done").performScrollTo().performClick()
            shot("$theme-$language-transfer-form")
            target(ui.onNodeWithTag("select-trigger-"+text("费用币种","手数料通貨")))
            shot("$theme-$language-fee-grid",false)
            target(ui.onNodeWithTag("transfer-state-note")).assertTextEquals(text("仅转出账户扣款，收款账户等待确认到账。","出金口座のみ控除。入金口座は入金確認待ち。"))
            shot("$theme-$language-transit-note",false)
            target(ui.onNodeWithTag("field-"+text("实际到账净额","実際の入金純額")+" · CNY"))
            shot("$theme-$language-transfer-amounts",false)
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true")field(text("名称 / 说明","名称 / 内容"),"FX footer")
            target(ui.onNode(hasText(text("保存这笔转账","この振替を保存")) and hasClickAction()))
            shot("$theme-$language-transfer-form-footer",false)
            tap(text("保存这笔转账","この振替を保存"))
            ui.waitUntil(10000){vm.entryReview.value!=null&&!vm.busy.value}
            ui.onNodeWithText(text("转账变化预览","振替の変化を確認")).assertExists()
            ui.onAllNodesWithText(text("转出账户 / 金额","出金口座・金額")).assertCountEquals(1)
            ui.onAllNodesWithText(text("转入账户 / 净额","入金口座・純額")).assertCountEquals(1)
            ui.onNodeWithText(text("原币金额","原通貨額")).assertDoesNotExist()
            ui.onNodeWithText(text("入账日期","計上日")).assertDoesNotExist()
            ui.onNodeWithText(text("原预计转出","元の出金見込み")).assertExists()
            shot("$theme-$language-transfer-review")
            target(ui.onNodeWithText(text("原预计转出","元の出金見込み")))
            shot("$theme-$language-transfer-review-fees",false)
            target(ui.onNode(hasText(text("确认并保存","確認して保存")) and hasClickAction()))
            shot("$theme-$language-transfer-review-footer",false)
            tap(text("返回表单继续编辑","フォームに戻って編集"))
            assertEquals("30000",vm.entry.value!!.amount);assertEquals("1462.50",vm.entry.value!!.toAmount)
            val before=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertTrue(before.events.isEmpty());assertEquals(100000L,before.balance(before.accounts.first{it.id==source}))
            tap(text("保存这笔转账","この振替を保存"))
            ui.waitUntil(10000){vm.entryReview.value!=null&&!vm.busy.value}
            tap(text("确认并保存","確認して保存"))
            ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value}
            val transit=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(70000L,transit.balance(transit.accounts.first{it.id==source}))
            assertEquals(0L,transit.balance(transit.accounts.first{it.id==target}))
            assertEquals(1,transit.inTransitTransfers().size)
            val event=transit.visibleEvents.single()
            ui.onNodeWithTag("event-${event.id}").performScrollTo().performClick()
            ui.onNodeWithTag("record-edit-button").performScrollTo().performClick()
            ui.waitUntil(10000){ui.onAllNodesWithTag("transfer-form").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("select-trigger-$stateLabel").performScrollTo().performClick()
            tap(text("已转出，已到账","出金・入金済"))
            field(text("实际到账净额","実際の入金純額")+" · CNY","1440.00")
            ui.onNodeWithTag("field-"+text("实际扣款总额","実際の出金総額")+" · JPY").performScrollTo().assertTextEquals("30000")
            tap(text("保存转账修改","振替の変更を保存"))
            ui.waitUntil(10000){vm.entryReview.value!=null&&!vm.busy.value}
            ui.onNodeWithText(text("转账变化预览","振替の変化を確認")).assertExists()
            ui.onNodeWithText("人民币 · CN¥1,440.00").assertExists()
            ui.onNodeWithText("CN¥1,462.50").assertExists()
            ui.onNodeWithText("¥30,000").assertExists()
            shot("$theme-$language-transfer-actual-review")
            tap(text("确认并保存","確認して保存"))
            ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value}
            val actual=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(event.id,actual.visibleEvents.single().id)
            assertEquals(70000L,actual.balance(actual.accounts.first{it.id==source}))
            assertEquals(144000L,actual.balance(actual.accounts.first{it.id==target}))
            assertEquals(146250L,actual.transfers.single().expectedToMinor)
            assertTrue(actual.inTransitTransfers().isEmpty());assertEquals(2,actual.postings.size)
            assertEquals(750L,actual.monthExpense(actual.expenseComponents.single().date.take(7)))
            ui.runOnIdle{vm.navigate("accounts")}
            ui.onNodeWithTag("account-$target").performScrollTo().performClick()
            ui.onNodeWithTag("event-${event.id}").assertExists()
            ui.onNodeWithText("+CN¥1,440.00").assertExists()
        }
    }
    @Test fun missingRecipientDoesNotInferCurrencyOrAmountBeforeThePairIsKnown(){
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val book=runBlocking {
                val id=repo.createBook("未選択 $theme $language",Currency.JPY,theme,language);repo.updateBook(repo.dao.book(id)!!.copy(initialized=true))
                repo.saveAccount(id,"出金JPY",AccountType.BANK,Currency.JPY,Money(Currency.JPY,100000),"2026-09-01");vm.preferences.select(id);id
            }
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book}
            ui.runOnIdle{vm.startTransfer((vm.state.value as AppState.Ready).snapshot!!)}
            field(text("实际扣款总额","実際の出金総額")+" · JPY","10000")
            assertEquals("",vm.entry.value!!.toAmount)
            ui.onNodeWithTag("field-"+text("实际到账净额","実際の入金純額")+" · —").assertExists()
            val incoming=runBlocking{repo.saveAccount(book,"入金CNY",AccountType.BANK,Currency.CNY,Money(Currency.CNY,0),"2026-09-01")}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.accounts?.size==2&&!vm.busy.value}
            choose(text("转入账户","入金口座"),0)
            assertEquals(incoming,vm.entry.value!!.toAccountId);assertEquals("10000",vm.entry.value!!.amount)
            field(text("手填汇率","手入力レート")+" · 1 JPY = CNY","0.05")
            tap(text("估算未确认的到账额","未確認の入金額を見積もる"))
            ui.waitUntil(10000){vm.entry.value?.toAmount=="500.00"&&!vm.busy.value}
            ui.runOnIdle{vm.leaveEntry(book,false,"ledger")}
            ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value}
            assertTrue(runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}.events.isEmpty())
        }
    }
    @Test fun sameCurrencyTransfersKeepSeparateDatesAndCountIncludedOrSeparateFeesOnce(){
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val ids=runBlocking {
                val book=repo.createBook("振替 $theme $language",Currency.JPY,theme,language);repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
                val from=repo.saveAccount(book,"出金JPY",AccountType.BANK,Currency.JPY,Money(Currency.JPY,100000),"2026-09-01")
                val to=repo.saveAccount(book,"入金JPY",AccountType.CASH,Currency.JPY,Money(Currency.JPY,0),"2026-09-01")
                val fee=repo.saveAccount(book,"費用JPY",AccountType.BANK,Currency.JPY,Money(Currency.JPY,2500),"2026-09-01")
                vm.preferences.select(book);listOf(book,from,to,fee)
            }
            val book=ids[0];val from=ids[1];val to=ids[2];val fee=ids[3]
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book}
            fun start(){ui.runOnIdle{val s=(vm.state.value as AppState.Ready).snapshot!!;vm.startTransfer(s,from)};val s=(vm.state.value as AppState.Ready).snapshot!!;choose(text("转入账户","入金口座"),s.accounts.filter{it.active&&!AccountType.valueOf(it.type).debt&&it.id!=from}.indexOfFirst{it.id==to})}
            start()
            ui.onNodeWithText(text("账户转账","口座振替")).assertExists()
            field(text("实际扣款总额","実際の出金総額")+" · JPY","10000")
            field(text("明确收取的手续费 / 可留空","確定手数料・空欄可")+" · JPY","600")
            tap(text("估算未确认的到账额","未確認の入金額を見積もる"))
            ui.waitUntil(10000){vm.entry.value?.toAmount=="9400"&&!vm.busy.value}
            choose(text("资金状态","資金の状態"),0)
            date(0,"2026-09-30",true,language);date(1,"2026-10-02",false,language)
            shot("$theme-$language-same-currency")
            tap(text("保存这笔转账","この振替を保存"));tap(text("确认并保存","確認して保存"))
            ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value}
            val first=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(90000L,first.balance(first.accounts.single{it.id==from}));assertEquals(9400L,first.balance(first.accounts.single{it.id==to}))
            assertEquals(600L,first.monthExpense("2026-09"));assertEquals(0L,first.monthExpense("2026-10"))
            assertEquals("2026-09-30",first.transfers.single().fromDate);assertEquals("2026-10-02",first.transfers.single().toDate)
            start();field(text("实际扣款总额","実際の出金総額")+" · JPY","2000")
            choose(text("手续费如何扣收","手数料の扱い"),2)
            choose(text("另扣手续费的账户","別途手数料の口座"),first.accounts.filter{it.active}.indexOfFirst{it.id==fee})
            field(text("明确收取的手续费 / 可留空","確定手数料・空欄可")+" · JPY","300")
            choose(text("资金状态","資金の状態"),0)
            date(0,"2026-09-30",true,language);date(1,"2026-10-02",false,language);date(2,"2026-10-02",false,language)
            target(ui.onNodeWithTag("date-field-2"))
            shot("$theme-$language-three-dates",false)
            target(ui.onNodeWithTag("toggle-"+text("手续费已经实际扣收","手数料は引落済み"))).performClick()
            tap(text("保存这笔转账","この振替を保存"));tap(text("确认并保存","確認して保存"))
            ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value}
            val second=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(88000L,second.balance(second.accounts.single{it.id==from}));assertEquals(11400L,second.balance(second.accounts.single{it.id==to}));assertEquals(2200L,second.balance(second.accounts.single{it.id==fee}))
            assertEquals(600L,second.monthExpense("2026-09"));assertEquals(300L,second.monthExpense("2026-10"));assertEquals(2,second.events.size);assertEquals(5,second.postings.size)
        }
    }

    @Test fun plannedArrivalFirstAndRepeatedConfirmationKeepOneEventAndArrivalFee(){
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val ids=runBlocking {
                val book=repo.createBook("先到账 $theme $language",Currency.JPY,theme,language);repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
                val from=repo.saveAccount(book,"出金JPY",AccountType.BANK,Currency.JPY,Money(Currency.JPY,100000),"2026-09-01")
                val to=repo.saveAccount(book,"入金JPY",AccountType.CASH,Currency.JPY,Money(Currency.JPY,0),"2026-09-01")
                vm.preferences.select(book);listOf(book,from,to)
            }
            val book=ids[0];val from=ids[1];val to=ids[2]
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book}
            ui.runOnIdle{vm.startTransfer((vm.state.value as AppState.Ready).snapshot!!,from)}
            field(text("实际扣款总额","実際の出金総額")+" · JPY","10000")
            choose(text("手续费如何扣收","手数料の扱い"),1)
            field(text("明确收取的手续费 / 可留空","確定手数料・空欄可")+" · JPY","600")
            tap(text("估算未确认的到账额","未確認の入金額を見積もる"))
            ui.waitUntil(10000){vm.entry.value?.toAmount=="9400"&&!vm.busy.value}
            choose(text("资金状态","資金の状態"),2)
            date(0,"2026-09-30",true,language);date(1,"2026-10-02",false,language)
            shot("$theme-$language-planned-state")
            target(ui.onNodeWithTag("transfer-state-note")).assertTextEquals(text("只是计划，不修改实际余额。","予定のみ。実残高は変更しません。"))
            shot("$theme-$language-planned-note",false)
            tap(text("保存这笔转账","この振替を保存"));tap(text("确认并保存","確認して保存"))
            ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value}
            fun snapshot()=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            val planned=snapshot();val eventId=planned.events.single().id
            assertEquals(100000L,planned.balance(planned.accounts.single{it.id==from}));assertEquals(0L,planned.balance(planned.accounts.single{it.id==to}))
            assertEquals(0L,planned.monthExpense("2026-10"));assertFalse(planned.transfers.single().fromConfirmed);assertFalse(planned.transfers.single().toConfirmed);assertTrue(planned.postings.isEmpty());assertEquals(2,planned.storedPostings.size)
            fun edit(){ui.onNodeWithTag("event-$eventId").performScrollTo().performClick();ui.onNodeWithTag("record-edit-button").performScrollTo().performClick();ui.waitUntil(10000){ui.onAllNodesWithTag("transfer-form").fetchSemanticsNodes().isNotEmpty()}}
            fun save(){tap(text("保存转账修改","振替の変更を保存"));tap(text("确认并保存","確認して保存"));ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value}}
            edit();choose(text("资金状态","資金の状態"),3)
            field(text("实际到账净额","実際の入金純額")+" · JPY","9300")
            target(ui.onNodeWithTag("select-trigger-"+text("资金状态","資金の状態")))
            shot("$theme-$language-arrival-first-state",false)
            target(ui.onNodeWithTag("transfer-state-note")).assertTextEquals(text("仅收款账户增加实际到账，转出账户等待确认扣款。","入金口座のみ実入金を反映。出金口座は引落確認待ち。"))
            shot("$theme-$language-arrival-first-note",false);save()
            val received=snapshot()
            assertEquals(eventId,received.events.single().id);assertEquals(100000L,received.balance(received.accounts.single{it.id==from}));assertEquals(9300L,received.balance(received.accounts.single{it.id==to}))
            assertEquals(600L,received.monthExpense("2026-10"));assertEquals(0L,received.monthExpense("2026-09"));assertEquals(9400L,received.transfers.single().expectedToMinor)
            assertFalse(received.transfers.single().fromConfirmed);assertTrue(received.transfers.single().toConfirmed);assertEquals(1,received.postings.size);assertEquals(2,received.storedPostings.size)
            edit();choose(text("资金状态","資金の状態"),0)
            field(text("实际扣款总额","実際の出金総額")+" · JPY","10000");save()
            edit()
            target(ui.onNodeWithTag("transfer-state-note")).assertTextEquals(text("两端按各自实扣实收记账，转账本金不计消费。","両側を実額で記録。振替元金は支出に含めません。"))
            shot("$theme-$language-confirmed-note",false);save()
            val confirmed=snapshot()
            assertEquals(eventId,confirmed.events.single().id);assertEquals(90000L,confirmed.balance(confirmed.accounts.single{it.id==from}));assertEquals(9300L,confirmed.balance(confirmed.accounts.single{it.id==to}))
            assertEquals(600L,confirmed.monthExpense("2026-10"));assertEquals(0L,confirmed.monthExpense("2026-09"));assertEquals(9400L,confirmed.transfers.single().expectedToMinor)
            assertTrue(confirmed.transfers.single().fromConfirmed);assertTrue(confirmed.transfers.single().toConfirmed);assertEquals(2,confirmed.postings.size);assertEquals(1,confirmed.events.size)
        }
    }

    @Test fun accountHistoryUsesItsOwnNameAmountsDatesAndArrivalOrder(){
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            lateinit var firstDraft:EntryDraft
            val ids=runBlocking {
                val book=repo.createBook("账户流水 $theme $language",Currency.JPY,theme,language);repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
                val from=repo.saveAccount(book,"来源银行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,100000),"2026-09-01")
                val to=repo.saveAccount(book,"目标人民币",AccountType.BANK,Currency.CNY,Money(Currency.CNY,0),"2026-09-01")
                firstDraft=EntryDraft(kind=EntryKind.TRANSFER,accountId=from,toAccountId=to,amount="10000",toAmount="500.00",date="2026-09-30",toDate="2026-10-10",fromConfirmed=true,merchant="九月转出")
                val first=repo.commitEntryReview(book,repo.previewEntry(book,firstDraft))!!
                val second=repo.commitEntryReview(book,repo.previewEntry(book,firstDraft.copy(amount="2000",toAmount="100.00",date="2026-10-05",toDate="2026-10-06",toConfirmed=true,merchant="十月转出")))!!
                vm.preferences.select(book);listOf(book,from,to,first,second)
            }
            val book=ids[0];val from=ids[1];val to=ids[2];val first=ids[3];val second=ids[4]
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book}
            fun account(id:String){ui.runOnIdle{vm.navigate("accounts")};ui.onNodeWithTag("account-$id").performScrollTo().performClick();ui.waitForIdle()}
            fun pageShot(name:String,top:Boolean=false){
                if(top)ui.onNodeWithTag("page-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,-100000f)}
                ui.waitForIdle();val context=InstrumentationRegistry.getInstrumentation().targetContext
                val file=File(context.getExternalFilesDir(null),"phase-b-p09/$theme-$language-account-history-$name.png");file.parentFile!!.mkdirs()
                val auto=InstrumentationRegistry.getInstrumentation().uiAutomation;auto.waitForIdle(300,5000);val screen=auto.takeScreenshot()
                file.outputStream().use{screen.compress(Bitmap.CompressFormat.PNG,100,it)};screen.recycle()
            }
            account(from)
            ui.onNodeWithText(text("在途资金 · 尚未计入收款余额","送金中・入金残高に未反映")).assertExists()
            ui.onNodeWithTag("event-$second").assert(hasText("来源银行")).assert(hasText("−¥2,000")).assert(hasText("10.05 · "+text("转账","振替")))
            assertTrue(ui.onNodeWithTag("event-$second").getUnclippedBoundsInRoot().top<ui.onNodeWithTag("event-$first").getUnclippedBoundsInRoot().top)
            pageShot("source-transit",true)
            account(to)
            ui.onNodeWithTag("event-$first").assert(hasText("目标人民币")).assert(hasText("+CN¥500.00")).assert(hasText("10.10 · "+text("转账","振替"))).assert(hasText(text("在途","送金中")))
            ui.onNodeWithTag("event-$second").assert(hasText("目标人民币")).assert(hasText("+CN¥100.00")).assert(hasText("10.06 · "+text("转账","振替")))
            assertTrue(ui.onNodeWithTag("event-$first").getUnclippedBoundsInRoot().top<ui.onNodeWithTag("event-$second").getUnclippedBoundsInRoot().top)
            ui.onNodeWithTag("event-$second").performScrollTo();pageShot("recipient-order")
            runBlocking{repo.commitEntryReview(book,repo.previewEntry(book,firstDraft.copy(id=first,toConfirmed=true)))}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.transfers?.all{it.toConfirmed}==true}
            ui.onNodeWithText(text("在途资金 · 尚未计入收款余额","送金中・入金残高に未反映")).assertDoesNotExist()
            ui.onNodeWithTag("event-$first").assert(hasText(text("两端已确认","両側確認済")))
            account(to);pageShot("recipient-arrived",true)
            val final=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(88000L,final.balance(final.accounts.single{it.id==from}));assertEquals(60000L,final.balance(final.accounts.single{it.id==to}));assertEquals(2,final.events.size);assertEquals(4,final.postings.size)
            assertEquals(0L,final.monthExpense("2026-09"));assertEquals(0L,final.monthExpense("2026-10"))
        }
    }

}
