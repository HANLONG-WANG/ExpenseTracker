package com.yohaku.expensetracker
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yohaku.expensetracker.data.db.YohakuDatabase
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.features.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred

@RunWith(AndroidJUnit4::class)
class EntryFlowTest {
    @get:Rule val ui=createComposeRule()
    @get:Rule(order=1) val failureArtifacts=RegressionFailureArtifacts(ui)
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var vm:AppViewModel
    @Before fun setup(){val context=InstrumentationRegistry.getInstrumentation().targetContext;db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build();repo=LedgerRepository(db);vm=AppViewModel(repo,AppPreferences(context));ui.setContent{NativeApp(vm)}}
    @After fun close(){vm.viewModelScope.cancel();db.close()}
    private fun waitFor(text:String){ui.waitUntil(10000){ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}}
    private fun tap(text:String){waitFor(text);ui.onNodeWithText(text).performScrollTo().performClick()}
    @Test fun firstSetupRepeatedNextCreatesOneBookWithSubmittedValues(){
        tap("开始设置我的账本")
        ui.onNodeWithTag("field-账本名称").performTextReplacement("唯一账本")
        ui.onNodeWithTag("field-月预算 / 可稍后设置").performScrollTo().performTextReplacement("68000")
        val gate=CompletableDeferred<Unit>();val settled=CompletableDeferred<Unit>()
        ui.runOnIdle{vm.execute{gate.await()}}
        ui.waitUntil(10000){vm.busy.value}
        ui.onNodeWithText("下一步：资金账户").performScrollTo()
        val click=ui.onNodeWithText("下一步：资金账户").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        ui.runOnIdle{click();click()}
        ui.onNodeWithTag("field-账本名称").performScrollTo().performTextReplacement("提交之后改名")
        ui.runOnIdle{vm.execute(onComplete={settled.complete(Unit)}){}}
        gate.complete(Unit)
        ui.waitUntil(10000){settled.isCompleted}
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.setupStep==2}
        runBlocking{
            val saved=repo.books.first().single()
            assertEquals("唯一账本",saved.name);assertEquals(68000L,saved.budgetMinor)
            assertFalse(saved.initialized)
            assertEquals(0,vm.preferences.setupDraft.first().step)
            val snapshot=repo.snapshot(saved).first()
            assertTrue(snapshot.accounts.isEmpty());assertTrue(snapshot.categories.isEmpty());assertTrue(snapshot.events.isEmpty())
        }
    }
    @Test fun firstSetupInvalidBudgetCanBeCorrectedWithoutCreatingBook(){
        tap("开始设置我的账本")
        ui.onNodeWithTag("field-账本名称").performTextReplacement("补正账本")
        ui.onNodeWithTag("field-月预算 / 可稍后设置").performScrollTo().performTextReplacement("invalid")
        tap("下一步：资金账户")
        ui.waitUntil(10000){vm.error.value!=null&&!vm.busy.value}
        assertTrue(runBlocking{repo.books.first()}.isEmpty())
        tap("知道了")
        ui.onNodeWithTag("field-账本名称").performScrollTo().assertTextEquals("补正账本")
        ui.onNodeWithTag("field-月预算 / 可稍后设置").performScrollTo().performTextReplacement("50000")
        tap("下一步：资金账户")
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.setupStep==2}
        assertEquals(50000L,runBlocking{repo.books.first()}.single().budgetMinor)
    }
    @Test fun firstSetupAppearanceAndLanguageKeepUnsavedBookInputs(){
        tap("开始设置我的账本")
        ui.onNodeWithTag("field-账本名称").performTextReplacement("我的外币日常")
        ui.onNodeWithTag("field-月预算 / 可稍后设置").performScrollTo().performTextReplacement("68000")
        ui.onNodeWithTag("header-settings").performScrollTo().performClick()
        tap("纸质手账")
        ui.waitUntil(10000){vm.setupDraft.value?.theme=="journal"}
        ui.onNodeWithTag("field-账本名称").performScrollTo().assertTextEquals("我的外币日常")
        ui.onNodeWithTag("field-月预算 / 可稍后设置").performScrollTo().assertTextEquals("68000")
        ui.onNodeWithTag("header-settings").performScrollTo().performClick()
        ui.onNodeWithTag("settings-language").performScrollTo().performClick()
        ui.onNodeWithText("×").performClick()
        waitFor("帳簿名")
        ui.onNodeWithTag("field-帳簿名").performScrollTo().assertTextEquals("我的外币日常")
        ui.onNodeWithTag("field-月予算・後から設定可").performScrollTo().assertTextEquals("68000")
        ui.onNodeWithTag("header-settings").performScrollTo().performClick()
        tap("日常の小物")
        ui.waitUntil(10000){vm.setupDraft.value?.theme=="objects"}
        ui.onNodeWithTag("field-帳簿名").performScrollTo().assertTextEquals("我的外币日常")
        ui.onNodeWithTag("field-月予算・後から設定可").performScrollTo().assertTextEquals("68000")
        ui.onNodeWithTag("header-book").assertDoesNotExist()
        ui.onNodeWithTag("setup-back-link").performScrollTo().performClick()
        tap("マイ帳簿を設定")
        ui.onNodeWithTag("field-帳簿名").performScrollTo().assertTextEquals("我的外币日常")
        ui.onNodeWithTag("field-月予算・後から設定可").performScrollTo().assertTextEquals("68000")
        runBlocking{assertTrue(repo.books.first().isEmpty())}
        tap("次へ：資金口座")
        tap("後で追加して次へ")
        tap("後で追加して次へ")
        tap("完了・空白のマイ帳簿へ")
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.initialized==true}
        runBlocking{
            val b=repo.books.first().single();val saved=repo.snapshot(b).first()
            assertEquals("我的外币日常",b.name);assertEquals(68000L,b.budgetMinor)
            assertEquals("objects",b.theme);assertEquals("ja",b.language)
            assertTrue(saved.accounts.isEmpty());assertTrue(saved.categories.isEmpty());assertTrue(saved.events.isEmpty())
        }
    }
    @Test fun emptyBookCategoryFirstAndCreateAccountWithoutLosingEntry() {
        tap("开始设置我的账本")
        ui.onNodeWithTag("field-账本名称").performTextReplacement("我的日常")
        tap("下一步：资金账户")
        tap("先跳过，稍后添加")
        tap("先跳过，稍后添加")
        tap("完成，进入我的空白账本")
        ui.onAllNodesWithText("记一笔").onLast().performClick()
        ui.onNodeWithTag("category-cover").assertExists()
        tap("创建第一个分类")
        ui.onNodeWithTag("field-分类名称").performTextInput("餐饮")
        tap("创建并使用")
        waitFor("金额 · JPY")
        ui.onNodeWithTag("field-金额 · JPY").performTextInput("680")
        ui.onNodeWithTag("field-商户 / 买了什么").performTextInput("喫茶と余白")
        tap("添加第一个账户")
        ui.onNodeWithTag("field-账户名称").performTextInput("随身现金")
        ui.onNodeWithTag("field-期初余额").performTextReplacement("10000")
        tap("保存账户信息")
        waitFor("保存记录")
        ui.waitUntil(10000){!vm.busy.value && vm.entry.value?.accountId!=null}
        tap("保存记录")
        waitFor("日常的每一笔。")
        runBlocking {
            val b=repo.books.first().single();val s=repo.snapshot(b).first()
            assertTrue(b.initialized);assertEquals(1,s.categories.size)
            assertEquals("喫茶と余白",s.visibleEvents.single().merchant)
            assertEquals(680L,s.visibleEvents.single().amountMinor)
            assertEquals(9320L,s.balance(s.accounts.single()))
            assertNull(s.draft)
        }
    }
    @Test fun foreignEntryEditsKeepOriginalAndAccountAmountsAcrossThemesAndLanguages() {
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")) {
            val pair=runBlocking {
                val book=repo.createBook("外币 $theme $language",Currency.JPY,theme,language)
                repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
                val account=repo.saveAccount(book,"信用卡",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,0),"2026-09-01")
                val category=repo.saveCategory(book,"购物",EntryKind.EXPENSE,null)
                val event=repo.saveEntry(book,EntryDraft(accountId=account,categoryId=category,originalCurrency=Currency.USD,amount="100",accountAmount="15000",date="2026-09-10"))
                vm.preferences.select(book)
                repo.snapshot(repo.dao.book(book)!!).first() to event
            }
            val snapshot=pair.first
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==snapshot.book.id}
            ui.runOnIdle{vm.editEntry(snapshot,snapshot.events.first{it.id==pair.second})}
            val amountTag=if(language=="ja")"field-金額 · JPY" else "field-金额 · JPY"
            val originalTag=if(language=="ja")"field-原通貨額 · USD" else "field-原币金额 · USD"
            ui.waitUntil(10000){ui.onAllNodesWithTag(originalTag).fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag(originalTag).performScrollTo().assertTextEquals("100.00")
            ui.onNodeWithTag(amountTag).performScrollTo().assertTextEquals("15000")
            refundTarget(ui.onNode(hasTestTag("currency-entry-note") and hasAnyDescendant(hasText(if(language=="ja")"二つの金額、同じ一件" else "两种金额，仍是一笔记录"))))
            refundShot("$theme-$language-currency-entry-note",false)
            tap(if(language=="ja")"換算を確認・手入力" else "查看 / 手动填写换算")
            val rateLabel=if(language=="ja")"手動レート・見込額の仮入力のみ" else "手动汇率 / 仅预填预计金额"
            ui.onNodeWithTag("field-$rateLabel").performScrollTo().performTextReplacement("152")
            val saveRate=if(language=="ja")"換算情報を保持して記録へ" else "保存换算资料，返回记账"
            tap(saveRate)
            ui.waitUntil(10000){ui.onAllNodesWithTag(amountTag).fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag(amountTag).performScrollTo().assertTextEquals("15000")
            tap(if(language=="ja")"換算を確認・手入力" else "查看 / 手动填写换算")
            ui.onNodeWithTag("field-$rateLabel").performScrollTo().performTextReplacement("150")
            val estimateLabel=if(language=="ja")"このレートで計上額を再見積もり" else "用此汇率重新估算入账"
            ui.onNodeWithTag("toggle-$estimateLabel").performScrollTo().performClick()
            tap(saveRate)
            ui.waitUntil(10000){ui.onAllNodesWithTag(amountTag).fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag(amountTag).performScrollTo().assertTextEquals("15000").performTextReplacement("15100")
            val stateLabel=if(language=="ja")"計上額の状態" else "入账金额状态"
            ui.onNodeWithTag("select-trigger-$stateLabel").performScrollTo().performClick()
            tap(if(language=="ja")"実額確認済" else "已确认实际金额")
            refundTarget(ui.onNodeWithTag("entry-save-button"))
            refundShot("$theme-$language-foreign-entry-footer",false)
            tap(if(language=="ja")"変更を保存" else "保存修改")
            val confirmLabel=if(language=="ja")"確認して保存" else "确认并保存"
            waitFor(confirmLabel)
            refundTarget(ui.onNode(hasText(confirmLabel) and hasClickAction()))
            refundShot("$theme-$language-foreign-review-footer",false)
            refundTap(if(language=="ja")"フォームに戻って編集"else"返回表单继续编辑")
            assertEquals("15100",vm.entry.value!!.accountAmount)
            assertEquals("100.00",vm.entry.value!!.amount)
            assertEquals(15000L,runBlocking{repo.snapshot(repo.dao.book(snapshot.book.id)!!).first()}.balance(snapshot.accounts.single()))
            tap(if(language=="ja")"変更を保存" else "保存修改")
            tap(confirmLabel)
            ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value}
            val result=runBlocking{repo.snapshot(repo.dao.book(snapshot.book.id)!!).first()}
            assertEquals(pair.second,result.events.single().id)
            assertEquals(10000L,result.events.single().amountMinor)
            assertEquals("USD",result.events.single().currency)
            assertEquals(15100L,result.balance(result.accounts.single()))
            assertEquals("150",result.settlements.single().manualRate)
        }
    }

    private fun refundTarget(node:SemanticsNodeInteraction):SemanticsNodeInteraction {
        ui.waitUntil(10000){!vm.busy.value};node.performScrollTo();ui.waitForIdle()
        if(ui.onAllNodesWithTag("sheet-header").fetchSemanticsNodes().size==1) {
            val density=InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            val header=ui.onNodeWithTag("sheet-header").getUnclippedBoundsInRoot().bottom.value
            val top=node.getUnclippedBoundsInRoot().top.value
            if(top<header+8)ui.onNodeWithTag("sheet-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,-(header+8-top)*density)}
            ui.waitForIdle();val bottom=node.getUnclippedBoundsInRoot().bottom.value
            val viewport=ui.onNodeWithTag("sheet-scroll").getUnclippedBoundsInRoot().bottom.value
            if(bottom>viewport)ui.onNodeWithTag("sheet-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,(bottom-viewport+8)*density)}
        }
        ui.waitForIdle();return node
    }
    private fun refundTap(text:String){ui.waitUntil(10000){ui.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()&&!vm.busy.value};refundTarget(ui.onNode(hasText(text) and hasClickAction())).performClick()}
    private fun refundField(label:String,value:String) {
        val node=refundTarget(ui.onNodeWithTag("field-$label"));node.performClick();node.performTextReplacement(value)
        if(InstrumentationRegistry.getArguments().getString("realIme")=="true")ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().any{androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}}
    }
    private fun refundShot(name:String,reset:Boolean=true) {
        if(reset)ui.onNodeWithTag("sheet-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,-100000f)}
        ui.waitForIdle()
        val root=InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        val file=File(root,"phase-b-p08/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use{ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation;automation.waitForIdle(300,5000)
        val screen=automation.takeScreenshot();File(file.parentFile,"$name-screen.png").outputStream().use{screen.compress(Bitmap.CompressFormat.PNG,100,it)};screen.recycle()
    }
    @Test fun pendingRefundActualConfirmationAndRemainingRefundAcrossThemesAndLanguages() {
        for(theme in listOf("magazine","journal","objects").filter{InstrumentationRegistry.getArguments().getString("theme")?.let{wanted->it==wanted}?:true})for(language in listOf("zh","ja").filter{InstrumentationRegistry.getArguments().getString("language")?.let{wanted->it==wanted}?:true}) {
            val ja=language=="ja"
            fun text(zh:String,jp:String)=if(ja)jp else zh
            val initial=runBlocking {
                val book=repo.createBook("返金 $theme $language",Currency.JPY,theme,language)
                repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
                val account=repo.saveAccount(book,"信用卡",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,0),"2026-09-01")
                val category=repo.saveCategory(book,"购物",EntryKind.EXPENSE,null)
                repo.saveEntry(book,EntryDraft(accountId=account,categoryId=category,originalCurrency=Currency.USD,amount="100",accountAmount="15000",date="2026-09-10",merchant="Refund shop"))
                vm.preferences.select(book)
                repo.snapshot(repo.dao.book(book)!!).first()
            }
            val book=initial.book.id
            val original=initial.events.single()
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book}
            ui.runOnIdle{vm.navigate("ledger")}
            ui.onNodeWithTag("event-${original.id}").performScrollTo().performClick()
            refundTap(text("记录退款","返金を記録"))
            refundField(text("商户退回原币金额","お店が返金した原通貨額")+" · USD","40.00")
            refundField(text("账户实际退款 / 待到账可填预计","口座の実返金額・未入金なら見込み")+" · JPY","5920")
            val derived=ui.onNodeWithTag("field-"+text("原消费月冲减金额","元の購入月から控除する額")+" · JPY")
            derived.assertTextEquals("5920").assert(hasSetTextAction().not())
            val status=text("退款状态","返金の状態")
            refundTarget(ui.onNodeWithTag("select-trigger-$status")).performClick()
            refundTap(text("待到账，不提前冲减消费","未入金・支出をまだ控除しない"))
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true")refundField(text("账户实际退款 / 待到账可填预计","口座の実返金額・未入金なら見込み")+" · JPY","5920")
            ui.onNodeWithTag("refund-source").performScrollTo()
            if(InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.fontScale==1f) {
                val box=ui.onNodeWithTag("refund-source").getUnclippedBoundsInRoot()
                val expected=when(theme){"magazine"->118.6f;"journal"->126.6f;else->122.6f}
                assertEquals(expected,(box.bottom-box.top).value,1f)
            }
            refundShot("$theme-$language-refund-form")
            refundTarget(ui.onNode(hasText(text("预览退款变化","返金の変化を確認")) and hasClickAction()))
            refundShot("$theme-$language-refund-form-footer",false)
            refundTap(text("预览退款变化","返金の変化を確認"))
            waitFor(text("确认并保存","確認して保存"))
            refundShot("$theme-$language-refund-preview")
            refundTarget(ui.onNode(hasText(text("确认并保存","確認して保存")) and hasClickAction()))
            refundShot("$theme-$language-refund-review-footer",false)
            refundTap(text("返回表单继续编辑","フォームに戻って編集"))
            assertEquals("40.00",vm.entry.value!!.amount);assertEquals("5920",vm.entry.value!!.accountAmount)
            assertEquals(1,runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}.events.size)
            refundTap(text("预览退款变化","返金の変化を確認"))
            refundTap(text("确认并保存","確認して保存"))
            ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value}
            val pending=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(15000L,pending.monthExpense("2026-09"))
            assertEquals(15000L,pending.balance(pending.accounts.single()))
            val refund=pending.visibleEvents.first{it.kind==EntryKind.REFUND.name}
            ui.onNodeWithTag("event-${original.id}").performScrollTo().performClick()
            val historyRow=ui.onNode(hasTestTag("event-${refund.id}") and hasAnyAncestor(hasTestTag("refund-history")))
            refundTarget(historyRow).assertTextContains("+¥5,920",substring=true).assertTextContains("US$40.00",substring=true).assertTextContains(text("待到账","未入金"),substring=true).assertTextContains("信用卡",substring=true)
            refundShot("$theme-$language-refund-history-pending",false)
            historyRow.performClick()
            refundTap(text("确认实际入账金额","実計上額を確認"))
            refundTap(text("预览退款变化","返金の変化を確認"))
            refundTap(text("确认并保存","確認して保存"))
            ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value}
            val actual=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(9080L,actual.monthExpense("2026-09"))
            assertEquals(2,actual.visibleEvents.size)
            assertEquals(refund.id,actual.visibleEvents.first{it.kind==EntryKind.REFUND.name}.id)
            ui.onNodeWithTag("event-${original.id}").performScrollTo().performClick()
            val actualHistory=ui.onNode(hasTestTag("event-${refund.id}") and hasAnyAncestor(hasTestTag("refund-history")))
            refundTarget(actualHistory).assertTextContains(text("已到账","入金済"),substring=true).assertTextContains("+¥5,920",substring=true)
            refundShot("$theme-$language-refund-history-actual",false)
            refundTap(text("记录退款","返金を記録"))
            refundField(text("账户实际退款 / 待到账可填预计","口座の実返金額・未入金なら見込み")+" · JPY","8880")
            refundTap(text("预览退款变化","返金の変化を確認"))
            refundTap(text("确认并保存","確認して保存"))
            ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value}
            val complete=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(200L,complete.monthExpense("2026-09"))
            assertEquals(0L,complete.monthExpense("2026-10"))
            assertEquals(200L,complete.balance(complete.accounts.single()))
            ui.onNodeWithTag("event-${original.id}").performScrollTo().performClick()
            val rows=ui.onAllNodes(hasTestTag("event-${refund.id}") and hasAnyAncestor(hasTestTag("refund-history")))
            rows.assertCountEquals(1)
            refundTarget(rows[0])
            refundShot("$theme-$language-refund-history-complete",false)
        }
    }

    @Test fun refundToAnotherCurrencyKeepsOriginalAccountAndStatisticalAmountsSeparate(){
        for(theme in listOf("magazine","journal","objects").filter{InstrumentationRegistry.getArguments().getString("theme")?.let{wanted->it==wanted}?:true})for(language in listOf("zh","ja").filter{InstrumentationRegistry.getArguments().getString("language")?.let{wanted->it==wanted}?:true}) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val ids=runBlocking {
                val book=repo.createBook("三通貨 $theme $language",Currency.JPY,theme,language)
                repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
                val card=repo.saveAccount(book,"カードJPY",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,0),"2026-09-01")
                val bank=repo.saveAccount(book,"返金銀行CNY",AccountType.BANK,Currency.CNY,Money(Currency.CNY,0),"2026-09-01")
                val category=repo.saveCategory(book,"買い物",EntryKind.EXPENSE,null)
                val event=repo.saveEntry(book,EntryDraft(accountId=card,categoryId=category,originalCurrency=Currency.USD,amount="100",accountAmount="15000",date="2026-09-10",merchant="Refund shop"))
                vm.preferences.select(book);listOf(book,event,bank,card)
            }
            val book=ids[0];val original=ids[1];val bank=ids[2];val card=ids[3]
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book}
            ui.runOnIdle{vm.navigate("ledger")}
            ui.onNodeWithTag("event-$original").performScrollTo().performClick()
            refundTap(text("记录退款","返金を記録"))
            refundTarget(ui.onNodeWithTag("select-trigger-"+text("实际退款到账账户","実際の返金口座"))).performClick()
            refundTap("返金銀行CNY")
            refundField(text("商户退回原币金额","お店が返金した原通貨額")+" · USD","10.00")
            refundField(text("账户实际退款 / 待到账可填预计","口座の実返金額・未入金なら見込み")+" · CNY","71.50")
            val base=text("原消费月冲减金额","元の購入月から控除する額")+" · JPY"
            ui.onNodeWithTag("field-$base").assert(hasSetTextAction())
            refundField(base,"1420")
            assertEquals("71.50",vm.entry.value!!.accountAmount)
            refundShot("$theme-$language-three-currency-form")
            refundTap(text("预览退款变化","返金の変化を確認"))
            refundTap(text("确认并保存","確認して保存"))
            ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value}
            val saved=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            val refund=saved.events.single{it.kind==EntryKind.REFUND.name}
            assertEquals("USD",refund.currency);assertEquals(1000L,refund.amountMinor);assertEquals(1420L,refund.baseMinor)
            assertEquals(7150L,saved.balance(saved.accounts.single{it.id==bank}))
            assertEquals(15000L,saved.balance(saved.accounts.single{it.id==card}))
            assertEquals(13580L,saved.monthExpense("2026-09"));assertEquals(0L,saved.monthExpense("2026-10"));assertEquals(2,saved.events.size)
        }
    }

    @Test fun refundEditAndOriginalRemovalPreviewRestoreTheirSameGroup(){
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val ids=runBlocking {
                val book=repo.createBook("退款关联 $theme $language",Currency.JPY,theme,language);repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
                val card=repo.saveAccount(book,"返金カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,0),"2026-09-01")
                val category=repo.saveCategory(book,"购物",EntryKind.EXPENSE,null)
                val original=repo.saveEntry(book,EntryDraft(accountId=card,categoryId=category,originalCurrency=Currency.USD,amount="100",accountAmount="15000",date="2026-09-10",merchant="Refund lifecycle"))
                val arrived=repo.saveRefund(book,RefundInput(original,Money(Currency.USD,4000),card,Money(Currency.JPY,5920),Money(Currency.JPY,5920),"2026-10-01",true))
                val pending=repo.saveRefund(book,RefundInput(original,Money(Currency.USD,2000),card,Money(Currency.JPY,3000),Money(Currency.JPY,3000),"2026-10-03",false))
                vm.preferences.select(book);listOf(book,card,original,arrived,pending)
            }
            val book=ids[0];val card=ids[1];val original=ids[2];val arrived=ids[3];val pending=ids[4]
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book}
            ui.runOnIdle{vm.navigate("ledger")}
            ui.onNodeWithTag("event-$arrived").performScrollTo().performClick()
            refundTarget(ui.onNodeWithTag("record-edit-button")).performClick()
            refundField(text("商户退回原币金额","お店が返金した原通貨額")+" · USD","35.00")
            refundField(text("账户实际退款 / 待到账可填预计","口座の実返金額・未入金なら見込み")+" · JPY","5180")
            refundTap(text("预览退款变化","返金の変化を確認"))
            ui.waitUntil(10000){vm.entryReview.value!=null&&!vm.busy.value}
            assertEquals(9080L,vm.entryReview.value!!.originalNetBefore);assertEquals(9820L,vm.entryReview.value!!.originalNetAfter)
            ui.onAllNodesWithText("¥9,080 → ¥9,820")[0].assertExists()
            refundShot("$theme-$language-lifecycle-edit-review")
            refundTap(text("确认并保存","確認して保存"))
            ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value}
            fun snapshot()=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            val edited=snapshot()
            assertEquals(3500L,edited.events.single{it.id==arrived}.amountMinor);assertEquals(3,edited.events.size)
            assertEquals(9820L,edited.monthExpense("2026-09"));assertEquals(9820L,edited.balance(edited.accounts.single{it.id==card}));assertEquals(0L,edited.monthExpense("2026-10"))
            ui.onNodeWithTag("event-$original").performScrollTo().performClick()
            refundTap(text("移至回收站","ごみ箱に移動"))
            ui.onNodeWithTag("refund-lifecycle").assertExists()
            ui.onNodeWithText("2026-10-01 · US$35.00").assertExists();ui.onNodeWithText("2026-10-03 · US$20.00").assertExists()
            refundShot("$theme-$language-lifecycle-delete-review")
            ui.onNodeWithTag("sheet-close").performClick()
            assertEquals(3,snapshot().visibleEvents.size)
            refundTap(text("移至回收站","ごみ箱に移動"));refundTap(text("确认移至回收站","ごみ箱へ移動"))
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.visibleEvents?.isEmpty()==true&&!vm.busy.value}
            val removed=snapshot();assertEquals(0L,removed.balance(removed.accounts.single{it.id==card}));assertEquals(0L,removed.monthExpense("2026-09"))
            ui.runOnIdle{vm.navigate("trash")};ui.waitForIdle()
            ui.onNodeWithTag("trash-$original").assertExists();ui.onNodeWithTag("trash-$arrived").assertDoesNotExist();ui.onNodeWithTag("trash-$pending").assertDoesNotExist()
            ui.onNodeWithText(text("同时恢复关联退款","関連する返金も復元")).assertExists()
            ui.onNodeWithTag("restore-$original").performScrollTo()
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            val file=File(context.getExternalFilesDir(null),"phase-b-p08/$theme-$language-lifecycle-trash-screen.png")
            val auto=InstrumentationRegistry.getInstrumentation().uiAutomation;auto.waitForIdle(300,5000);val image=auto.takeScreenshot();file.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
            ui.onNodeWithTag("restore-$original").performClick()
            ui.waitUntil(10000){ui.onAllNodesWithTag("confirm-entry-lifecycle").fetchSemanticsNodes().isNotEmpty()}
            refundTarget(ui.onNodeWithTag("confirm-entry-lifecycle")).performClick()
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.visibleEvents?.size==3&&!vm.busy.value}
            val restored=snapshot()
            assertEquals(setOf(original,arrived,pending),restored.visibleEvents.map{it.id}.toSet());assertEquals(2,restored.postings.size);assertEquals(3,restored.storedPostings.size)
            assertEquals(9820L,restored.monthExpense("2026-09"));assertEquals(0L,restored.monthExpense("2026-10"));assertEquals(9820L,restored.balance(restored.accounts.single{it.id==card}))
            assertFalse(restored.settlements.single{it.eventId==pending}.confirmed)
            ui.runOnIdle{vm.navigate("ledger")}
            ui.onNodeWithTag("event-$arrived").performScrollTo().performClick()
            refundTap(text("移至回收站","ごみ箱に移動"))
            ui.onNodeWithTag("refund-lifecycle").assertDoesNotExist()
            refundTap(text("确认移至回收站","ごみ箱へ移動"))
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.visibleEvents?.size==2&&!vm.busy.value}
            assertEquals(setOf(original,pending),snapshot().visibleEvents.map{it.id}.toSet())
            assertEquals(15000L,snapshot().monthExpense("2026-09"))
            ui.runOnIdle{vm.navigate("trash")};ui.waitForIdle()
            ui.onNodeWithTag("restore-$arrived").performScrollTo().assertIsEnabled().performClick()
            ui.waitUntil(10000){ui.onAllNodesWithTag("confirm-entry-lifecycle").fetchSemanticsNodes().isNotEmpty()}
            refundTarget(ui.onNodeWithTag("confirm-entry-lifecycle")).performClick()
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.visibleEvents?.size==3&&!vm.busy.value}
            val individuallyRestored=snapshot()
            assertEquals(9820L,individuallyRestored.monthExpense("2026-09"));assertEquals(9820L,individuallyRestored.balance(individuallyRestored.accounts.single{it.id==card}));assertEquals(3,individuallyRestored.events.size)
        }
    }

}
