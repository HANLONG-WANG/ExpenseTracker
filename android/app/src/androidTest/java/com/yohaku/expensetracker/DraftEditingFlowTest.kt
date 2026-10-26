package com.yohaku.expensetracker

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.features.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DraftEditingFlowTest {
    @get:Rule val ui=createComposeRule()
    @get:Rule(order=1) val failureArtifacts=RegressionFailureArtifacts(ui)
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var vm:AppViewModel
    private lateinit var a:String
    private lateinit var b:String
    private lateinit var account:String
    private lateinit var category:String
    private lateinit var event:String
    @Before fun setup() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        repo=LedgerRepository(db)
        val prefs=AppPreferences(context)
        runBlocking {
            prefs.clearSetup()
            a=repo.createBook("生活",Currency.JPY)
            repo.updateBook(repo.dao.book(a)!!.copy(initialized=true))
            b=repo.createBook("別の生活",Currency.CNY,"journal","ja")
            repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
            account=repo.saveAccount(a,"现金",AccountType.CASH,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
            repo.saveCategory(a,"餐饮",EntryKind.EXPENSE,null)
            val root=repo.dao.categories(a).first().single()
            repo.saveCategory(a,"咖啡",EntryKind.EXPENSE,root.id)
            category=repo.dao.categories(a).first().first{it.parentId!=null}.id
            event=repo.saveEntry(a,EntryDraft(accountId=account,categoryId=category,amount="300",merchant="喫茶と余白"))
            prefs.select(a)
        }
        vm=AppViewModel(repo,prefs);ui.setContent{NativeApp(vm)}
    }
    @After fun close(){vm.viewModelScope.cancel();db.close()}
    private fun waitFor(text:String){ui.waitUntil(10000){ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}}
    private fun tap(text:String){waitFor(text);ui.onNodeWithText(text).performScrollTo().performClick()}
    private fun snapshot(id:String)=runBlocking {repo.snapshot(repo.dao.book(id)!!).first()}
    @Test fun categoryCreationAndFixedParentBodiesMatchSource(){
        val i=InstrumentationRegistry.getInstrumentation()
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=java.io.File(i.targetContext.filesDir,"verification/category-create$suffix").also{it.mkdirs()}
        val csv=java.io.File(folder,"dimensions.csv").also{it.writeText("theme,language,fixed,tag,top,height\n")}
        val root=snapshot(a).categories.first{it.id==category}.parentId!!
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja"))for(fixed in listOf(false,true)){
            val ja=language=="ja"
            ui.runOnIdle{vm.appearance(a,theme=theme,language=language);vm.navigate("categories")}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.let{it.theme==theme&&it.language==language}==true}
            if(fixed)ui.onNodeWithTag("category-manage-parent-$root").performScrollTo().performClick()
            else ui.onAllNodesWithText(if(ja)"＋ 親カテゴリを追加" else "＋ 添加一级分类").onFirst().performScrollTo().performClick()
            val origin=ui.onNodeWithTag("sheet-body-content").getUnclippedBoundsInRoot().top
            val noteTop=if(theme=="objects")29f else 0f
            val noteHeight=if(ja)41.78125f else 20.890625f
            val nameHeight=when(theme){"journal"->67f;"objects"->72f;else->69f}
            val parentHeight=if(fixed)when(theme){"journal"->56.797f;"objects"->53f;else->55f} else when(theme){"journal"->72f;"objects"->71f;else->69f}
            val nameTop=noteTop+noteHeight+16
            val parentTop=nameTop+nameHeight+if(fixed)20 else 16
            // The P23 management link is a formal extension below the unchanged source fields.
            val managementHeight=if(fixed)ui.onNodeWithTag("category-manage").getUnclippedBoundsInRoot().let{(it.bottom-it.top).value} else 0f
            val saveTop=parentTop+parentHeight+(if(fixed)35+16+managementHeight else 31f)
            val expected=listOf(Triple("category-editor-intro",noteTop,noteHeight),Triple(if(ja)"field-group-カテゴリ名" else "field-group-分类名称",nameTop,nameHeight),Triple(if(fixed)"category-parent-fixed" else if(ja)"select-field-階層" else "select-field-分类层级",parentTop,parentHeight),Triple("category-editor-save",saveTop,when(theme){"journal"->50f;"objects"->52f;else->48f}))
            for((tag,top,height) in expected){
                val r=ui.onNodeWithTag(tag).getUnclippedBoundsInRoot()
                csv.appendText("$theme,$language,$fixed,$tag,${(r.top-origin).value},${(r.bottom-r.top).value}\n")
                if(i.targetContext.resources.configuration.let{it.fontScale==1f&&it.screenWidthDp==390}){
                    assertEquals("Original category create top $theme/$language/$tag",top,(r.top-origin).value,1f)
                    assertEquals("Original category create height $theme/$language/$tag",height,(r.bottom-r.top).value,1f)
                }
            }
            ui.onNodeWithTag("category-editor-save").performScrollTo().assertIsDisplayed()
            captureScreen("category-create","$theme-$language-$fixed")
            ui.onNodeWithText("×").performClick()
            assertEquals(2,snapshot(a).categories.size);assertEquals(1,snapshot(a).events.size)
        }
    }
    @Test fun longCategoryPathsRemainSelectableAndVisibleAfterEditing(){
        val parentName="外出して楽しむ食事と日々の買い物"
        val childName="お気に入りの喫茶店と近所で買う飲み物"
        val merchant="駅前の喫茶店で友達と過ごした休日の午後"
        val root=snapshot(a).categories.first{it.id==category}.parentId!!
        runBlocking{
            repo.saveCategory(a,parentName,EntryKind.EXPENSE,null,root)
            repo.saveCategory(a,childName,EntryKind.EXPENSE,root,category,true)
        }
        for(theme in listOf("magazine","journal","objects")){
            ui.runOnIdle{vm.appearance(a,theme=theme,language="ja")}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.let{it.theme==theme&&it.language=="ja"}==true}
            ui.runOnIdle{vm.editEntry(snapshot(a),snapshot(a).events.single())}
            ui.onNodeWithTag("entry-reselect").performScrollTo().performClick()
            ui.onNodeWithTag("field-カテゴリを検索").performScrollTo().performTextReplacement("飲み物")
            ui.onNodeWithTag("category-child-$category").performScrollTo().assertIsDisplayed()
            captureScreen("long-category","$theme-picker")
            ui.onNodeWithTag("category-child-$category").performClick()
            ui.onNodeWithTag("field-お店 / 買ったもの").performScrollTo().performTextReplacement(merchant)
            ui.onNodeWithTag("entry-save-button").performScrollTo().performClick()
            ui.waitUntil(10000){vm.entry.value==null}
            val saved=snapshot(a)
            assertEquals(event,saved.events.single().id);assertEquals(category,saved.events.single().categoryId)
            assertEquals(merchant,saved.events.single().merchant);assertEquals(300L,saved.events.single().amountMinor)
            assertEquals(9700L,saved.balance(saved.accounts.single()))
            ui.runOnIdle{vm.navigate("ledger")};tap(merchant)
            ui.onNodeWithTag("record-fact-1").performScrollTo()
            ui.onNode(hasText("$parentName / $childName") and hasAnyAncestor(hasTestTag("record-fact-1"))).assertIsDisplayed()
            val r=ui.onNodeWithTag("record-fact-1").getUnclippedBoundsInRoot()
            val viewport=ui.onNodeWithTag("sheet-viewport").getUnclippedBoundsInRoot()
            val header=ui.onNodeWithTag("sheet-header",useUnmergedTree=true).getUnclippedBoundsInRoot()
            assertTrue("Full category path clears the sticky header",r.top>=header.bottom)
            assertTrue("Full category path fits the visible viewport",r.bottom<=viewport.bottom)
            captureScreen("long-category","$theme-detail")
            ui.onNodeWithText("×").performClick()
        }
    }
    @Test fun foreignAccountReferenceUsesEnteredBaseAmountAndNeverInventsZero(){
        // This case verifies the no-reference state, independently of earlier persisted rates.
        val previous=runBlocking { vm.preferences.referenceRateCache() to vm.preferences.automaticReferenceRatesEnabled() }
        runBlocking {
            vm.preferences.saveReferenceRateCache(com.yohaku.expensetracker.domain.currency.ReferenceRateCache())
            vm.preferences.setAutomaticReferenceRates(false)
        }
        try {
        val foreign=runBlocking{repo.saveAccount(a,"人民币账户",AccountType.BANK,Currency.CNY,Money(Currency.CNY,10000),"2026-10-01")}
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.accounts?.any{it.id==foreign}==true}
        ui.runOnIdle{vm.startEntry(snapshot(a));vm.changeEntry(a,vm.entry.value!!.copy(accountId=foreign,categoryId=category,amount="100"));vm.navigate("entry")}
        ui.onNodeWithTag("entry-base-caption").assertTextEquals("本位币参考 —")
        ui.onNodeWithTag("entry-quick-amount").assertDoesNotExist()
        ui.onNodeWithTag("field-本位币统计金额 · JPY").performScrollTo().performTextReplacement("2100")
        ui.onNodeWithTag("entry-base-caption").assertTextEquals("本位币参考 ¥2,100")
        assertEquals(1,snapshot(a).events.size)
        assertEquals(10000L,snapshot(a).balance(snapshot(a).accounts.first{it.id==foreign}))
        ui.runOnIdle{vm.leaveEntry(a,false)}
        ui.waitUntil(10000){vm.entry.value==null}
        } finally {
            runBlocking {
                vm.preferences.saveReferenceRateCache(previous.first)
                vm.preferences.setAutomaticReferenceRates(previous.second)
            }
        }
    }

    @Test fun yenQuickEntrySavesSameRecordAndIsAbsentForIncome(){
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")){
            ui.runOnIdle{vm.appearance(a,theme=theme,language=language)}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.let{it.theme==theme&&it.language==language}==true}
            val before=snapshot(a);ui.runOnIdle{vm.editEntry(before,before.events.single())}
            val tag=if(language=="zh")"field-金额 · JPY" else "field-金額 · JPY"
            ui.waitUntil(10000){ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag(tag).performTextReplacement("")
            for(n in listOf(100,500,1000))ui.onNodeWithTag("entry-quick-$n").performScrollTo().performClick()
            ui.onNodeWithTag(tag).assertTextEquals("1600")
            captureScreen("entry-quick","$theme-$language")
            ui.onNodeWithTag("entry-save-button").performScrollTo().performClick()
            ui.waitUntil(10000){vm.entry.value==null}
            val saved=snapshot(a);assertEquals(1,saved.events.size);assertEquals(event,saved.events.single().id)
            assertEquals(1600L,saved.events.single().amountMinor);assertEquals(8400L,saved.balance(saved.accounts.single()))
            ui.runOnIdle{vm.startEntry(saved)}
            ui.waitUntil(10000){ui.onAllNodesWithTag("category-kind-INCOME").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("category-kind-INCOME").performScrollTo().performClick()
            ui.runOnIdle{vm.navigate("entry")}
            ui.waitUntil(10000){ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("entry-quick-amount").assertDoesNotExist()
            ui.runOnIdle{vm.leaveEntry(a,false)}
            ui.waitUntil(10000){vm.entry.value==null}
        }
    }
    @Test fun editSurvivesThemeAndLanguageSwitchAndCancelKeepsOriginal() {
        tap("喫茶と余白")
        tap("↺ 编辑记录")
        ui.onNodeWithTag("field-金额 · JPY").performTextReplacement("680")
        ui.onNodeWithTag("header-settings").performScrollTo().performClick()
        tap("保留草稿并返回")
        ui.onNodeWithTag("settings-appearance").performScrollTo().performClick()
        ui.onNodeWithTag("theme-choice-journal").performScrollTo().performClick()
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.theme=="journal"}
        ui.onNodeWithTag("page-back").performScrollTo().performClick()
        ui.onNodeWithTag("settings-language").performScrollTo().performClick()
        ui.runOnIdle{vm.navigate("detail");vm.resumeEntry(snapshot(a))}
        waitFor("金額 · JPY")
        ui.onNodeWithTag("field-金額 · JPY").assertTextEquals("680")
        ui.onNodeWithText("×").performClick()
        tap("編集を続ける")
        ui.onNodeWithTag("field-金額 · JPY").assertTextEquals("680")
        ui.onNodeWithText("×").performClick()
        tap("変更を破棄")
        ui.waitUntil(10000){vm.entry.value==null}
        assertEquals(300L,snapshot(a).events.single().amountMinor)
        tap("↺ 記録を編集")
        ui.onNodeWithTag("field-金額 · JPY").performTextReplacement("680")
        ui.waitUntil(10000){!vm.busy.value}
        tap("変更を保存")
        waitFor("日々の記録。")
        val saved=snapshot(a)
        assertEquals(event,saved.events.single().id)
        assertEquals(2,saved.events.single().revision)
        assertEquals(9320L,saved.balance(saved.accounts.single()))
        assertEquals(680L,saved.events.single().baseMinor)
        assertEquals("元の記録に変更を保存",vm.notice.value?.ja)
    }
    @Test fun switchingBooksKeepsDraftOnlyInItsOwnBook() {
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==a}
        ui.onAllNodesWithText("记一笔").onLast().performClick()
        tap("餐饮")
        tap("咖啡")
        ui.onNodeWithTag("field-金额 · JPY").performTextInput("777")
        ui.onNodeWithTag("header-book").performScrollTo().performClick()
        tap("別の生活")
        tap("继续编辑")
        ui.onNodeWithTag("field-金额 · JPY").assertTextEquals("777")
        ui.onNodeWithTag("header-book").performScrollTo().performClick()
        tap("別の生活")
        tap("保留草稿并切换")
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==b && vm.entry.value==null}
        assertEquals("777",snapshot(a).draft!!.amount)
        assertNull(snapshot(b).draft)
        ui.onNodeWithTag("header-book").performScrollTo().performClick()
        ui.onNodeWithTag("book-choice-$a").performScrollTo().performClick()
        tap("继续本账本未保存的记录")
        ui.onNodeWithTag("field-金额 · JPY").assertTextEquals("777")
        assertEquals(category,vm.entry.value!!.categoryId)
        assertEquals(1,snapshot(a).events.size)
    }
    @Test fun resumedEditCancelOpensOriginalDetailThenReturnsHome(){
        ui.runOnIdle{vm.appearance(a,theme="journal",language="ja")}
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.language=="ja"}
        val before=snapshot(a).visibleEvents.single()
        tap("喫茶と余白");ui.onNodeWithTag("record-edit-button").performScrollTo().performClick()
        ui.onNodeWithTag("field-金額 · JPY").performTextReplacement("999")
        ui.onNodeWithText("×").performClick();tap("下書きを残して戻る")
        ui.waitUntil(10000){vm.entry.value==null&&vm.route.value=="detail"}
        ui.onNodeWithText("×").performClick();ui.waitUntil(10000){vm.route.value=="home"}
        tap("この帳簿の下書きを続ける")
        assertEquals(event,vm.entry.value!!.id);assertEquals("999",vm.entry.value!!.amount)
        ui.onNodeWithTag("entry-cancel-edit").performScrollTo().performClick()
        ui.waitUntil(10000){vm.entry.value==null&&vm.route.value=="detail"}
        assertEquals(before,snapshot(a).visibleEvents.single());assertNull(snapshot(a).draft)
        ui.onNodeWithTag("record-detail-core").assertExists()
        ui.onNodeWithText("×").performClick();ui.waitUntil(10000){vm.route.value=="home"}
    }
    @Test fun inlineEditCancelReturnsToOriginalDetailAndSaveRevisesSameId(){
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")){
            ui.runOnIdle{vm.appearance(a,theme=theme,language=language);vm.navigate("ledger")}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.let{it.theme==theme&&it.language==language}==true}
            val before=snapshot(a).visibleEvents.single()
            ui.onNodeWithTag("event-"+event).performScrollTo().performClick()
            ui.waitUntil(10000){ui.onAllNodesWithTag("record-edit-button").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("record-edit-button").performScrollTo().performClick()
            ui.onNodeWithTag(if(language=="zh")"field-金额 · JPY" else "field-金額 · JPY").performTextReplacement("999")
            ui.onNodeWithTag("entry-save-button").assert(hasText(if(language=="zh")"保存修改" else "変更を保存"))
            ui.onNodeWithTag("entry-cancel-edit").performScrollTo().assertIsDisplayed()
            val rect=ui.onNodeWithTag("entry-cancel-edit").getUnclippedBoundsInRoot()
            if(InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.fontScale==1f)assertEquals("Original cancel height",19f,(rect.bottom-rect.top).value,1f)
            captureScreen("edit-cancel","$theme-$language")
            ui.onNodeWithTag("entry-cancel-edit").performClick()
            ui.waitUntil(10000){vm.entry.value==null&&vm.route.value=="detail"}
            assertEquals(before,snapshot(a).visibleEvents.single());assertNull(snapshot(a).draft)
            ui.onNodeWithText("×").performClick();ui.waitUntil(10000){vm.route.value=="ledger"}
            ui.onNodeWithTag("event-"+event).performScrollTo().performClick()
            ui.waitUntil(10000){ui.onAllNodesWithTag("record-edit-button").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("record-edit-button").performScrollTo().performClick()
            val amount=before.amountMinor+100
            ui.onNodeWithTag(if(language=="zh")"field-金额 · JPY" else "field-金額 · JPY").performTextReplacement(amount.toString())
            ui.onNodeWithTag("entry-save-button").performScrollTo().performClick()
            ui.waitUntil(10000){vm.entry.value==null&&snapshot(a).visibleEvents.single().amountMinor==amount}
            val saved=snapshot(a)
            assertEquals(event,saved.visibleEvents.single().id);assertEquals(before.revision+1,saved.visibleEvents.single().revision)
            assertEquals(10000-amount,saved.balance(saved.accounts.single()))
        }
    }
    @Test fun recordDetailUsesThemedSheetAndReturnsToSourcePage() {
        for(theme in listOf("magazine","journal","objects")) {
            ui.runOnIdle{vm.appearance(a,theme=theme)}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.theme==theme}
            tap("喫茶と余白")
            waitFor("这一笔的详情")
            ui.onNodeWithTag("record-cover").assertIsDisplayed()
            assertEquals("detail",vm.route.value)
            captureScreen("record-detail",theme)
            ui.onNodeWithText("×").performClick()
            ui.waitUntil(10000){vm.route.value=="home"}
            ui.onAllNodesWithTag("record-cover").assertCountEquals(0)
            assertEquals(9700L,snapshot(a).balance(snapshot(a).accounts.single()))
        }
    }
    @Test fun homeFundsKeepCurrenciesSeparateAndDebtIsNotCash(){
        runBlocking{
            repo.saveAccount(a,"人民币账户",AccountType.BANK,Currency.CNY,Money(Currency.CNY,10000),"2026-10-01")
            repo.saveAccount(a,"信用卡",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,3000),"2026-10-01")
            repo.saveAccount(b,"借款",AccountType.LOAN,Currency.CNY,Money(Currency.CNY,50000),"2026-10-01")
        }
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")){
            ui.runOnIdle{vm.selectBook(a)}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==a}
            ui.runOnIdle{vm.appearance(a,theme=theme,language=language)}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.let{it.theme==theme&&it.language==language}==true}
            ui.onNodeWithTag("home-cash-note").performScrollTo().assertTextContains("¥9,700").assertTextContains("CN¥100.00")
            val text=ui.onNodeWithTag("home-cash-note").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString()
            assertFalse(text.contains("3,000"))
            ui.onNodeWithTag("home-cash-note").performClick();ui.waitUntil(10000){vm.route.value=="accounts"}
            assertEquals(1,snapshot(a).events.size);assertEquals(9700L,snapshot(a).balance(snapshot(a).accounts.first{it.id==account}))
            ui.runOnIdle{vm.selectBook(b)}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==b}
            ui.runOnIdle{vm.appearance(b,theme=theme,language=language)}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.let{it.theme==theme&&it.language==language}==true}
            ui.onNodeWithTag("home-cash-note").performScrollTo().assertTextContains("—")
            val debtOnly=ui.onNodeWithTag("home-cash-note").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString()
            assertFalse(debtOnly.contains("500"));assertTrue(snapshot(b).events.isEmpty())
        }
    }
    @Test fun homeRecentExpensesAndAccountLedgerUseGroupedOriginalRows(){
        val income=runBlocking{
            val category=repo.saveCategory(a,"収入",EntryKind.INCOME,null)
            repo.saveEntry(a,EntryDraft(kind=EntryKind.INCOME,accountId=account,categoryId=category,amount="400",merchant="income-only"))
        }
        val expense=runBlocking{repo.saveEntry(a,EntryDraft(accountId=account,categoryId=category,amount="500",merchant="new-expense"))}
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")){
            ui.runOnIdle{vm.appearance(a,theme=theme,language=language);vm.navigate("home")}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.let{it.theme==theme&&it.language==language}==true}
            ui.onNodeWithTag("event-"+income).assertDoesNotExist()
            val first=ui.onNodeWithTag("event-"+expense).performScrollTo().getUnclippedBoundsInRoot()
            val second=ui.onNodeWithTag("event-"+event).getUnclippedBoundsInRoot()
            assertEquals("Original row gap $theme/$language",if(theme=="objects")8f else 0f,(second.top-first.bottom).value,1f)
            ui.onNodeWithTag("event-"+expense).performClick()
            waitFor(if(language=="zh")"这一笔的详情" else "この一筆の詳細")
            ui.onNodeWithText("×").performClick();ui.waitUntil(10000){vm.route.value=="home"}
            ui.runOnIdle{vm.navigate("accounts")}
            ui.onNodeWithTag("account-"+account).performScrollTo().performClick()
            ui.waitUntil(10000){ui.onAllNodesWithText(if(language=="zh")"账户流水" else "口座の記録",substring=true).fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("event-"+income).performScrollTo().assertIsDisplayed()
            captureScreen("account-ledger","$theme-$language")
            assertEquals(3,snapshot(a).visibleEvents.size)
            assertEquals(9600L,snapshot(a).balance(snapshot(a).accounts.first{it.id==account}))
        }
    }
    @Test fun longPagesScrollHeaderAwayKeepNavigationFixedAndReachSettings(){
        runBlocking{repeat(5){repo.saveAccount(a,"口座 $it",AccountType.BANK,Currency.JPY,Money(Currency.JPY,1000),"2026-10-01")}}
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")){
            ui.runOnIdle{vm.appearance(a,theme=theme,language=language);vm.navigate("accounts")}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.let{it.theme==theme&&it.language==language}==true}
            val header=ui.onNodeWithTag("app-header").performScrollTo().getUnclippedBoundsInRoot()
            val nav=ui.onNodeWithTag("bottom-nav").getUnclippedBoundsInRoot()
            ui.onNodeWithTag("page-scroll").performTouchInput{swipeUp(startY=height*.8f,endY=height*.2f)}
            val afterHeader=ui.onNodeWithTag("app-header").getUnclippedBoundsInRoot()
            val afterNav=ui.onNodeWithTag("bottom-nav").getUnclippedBoundsInRoot()
            assertTrue("HTML header scrolls with page",afterHeader.top.value<header.top.value-100)
            assertEquals("Bottom navigation remains fixed",nav.top.value,afterNav.top.value,1f)
            captureScreen("shell-scroll","$theme-$language")
            ui.onNodeWithTag("header-settings").performScrollTo().assertIsDisplayed().performClick()
            ui.waitUntil(10000){vm.route.value=="settings"}
            assertEquals(6,snapshot(a).accounts.size)
            assertEquals(event,snapshot(a).visibleEvents.single().id)
        }
    }
    @Test fun accountsKeepCurrenciesAndDebtSeparateAcrossThemes() {
        val credit=runBlocking {
            repo.saveAccount(a,"人民币账户",AccountType.BANK,Currency.CNY,Money(Currency.CNY,10000),"2026-10-01")
            repo.saveAccount(a,"信用卡",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,3000),"2026-10-01")
        }
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")) {
            ui.runOnIdle{vm.appearance(a,theme=theme,language=language);vm.navigate("accounts")}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.let{it.theme==theme&&it.language==language}==true}
            waitFor(if(language=="zh")"持有的资金 · 按原币" else "手元の資金・元通貨別")
            ui.onAllNodesWithText("CN¥100.00").onFirst().assertExists()
            captureScreen("accounts","$theme-$language-top")
            ui.onNodeWithTag("account-"+credit).performScrollTo().assertIsDisplayed()
            captureScreen("accounts","$theme-$language-cards")
            ui.onNodeWithTag("account-"+account).performScrollTo().performClick()
            waitFor(if(language=="zh")"编辑账户" else "口座を編集")
            ui.onNodeWithTag("lead-amount").assertTextEquals(if(theme=="journal")"¥9,700 ↗" else "¥9,700")
            ui.runOnIdle{vm.back()}
            ui.waitUntil(10000){vm.route.value=="accounts"}
        }
        assertEquals(9700L,snapshot(a).balance(snapshot(a).accounts.first{it.id==account}))
    }
    @Test fun ledgerEntryActionsRespectExistingDraftAndRequestedKind(){
        for(theme in listOf("magazine","journal","objects")){
            runBlocking{repo.saveDraft(a,EntryDraft(kind=EntryKind.EXPENSE,accountId=account,categoryId=category,amount="777"))}
            ui.runOnIdle{vm.appearance(a,theme=theme,language="zh");vm.navigate("ledger")}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.let{it.book.theme==theme&&it.draft?.amount=="777"}==true}
            ui.onNodeWithTag("ledger-entry-INCOME").performScrollTo().performClick()
            waitFor("还有一笔草稿")
            ui.onNodeWithText("×").performClick()
            assertEquals("777",snapshot(a).draft?.amount)
            ui.onNodeWithTag("ledger-entry-INCOME").performClick()
            tap("继续这笔草稿")
            ui.waitUntil(10000){vm.route.value=="entry"}
            assertEquals(EntryKind.EXPENSE,vm.entry.value?.kind)
            assertEquals("777",vm.entry.value?.amount)
            ui.runOnIdle{vm.leaveEntry(a,true,"ledger")}
            ui.waitUntil(10000){vm.route.value=="ledger"}
            ui.onNodeWithTag("ledger-entry-INCOME").performScrollTo().performClick()
            tap("放弃草稿，记新的一笔")
            ui.waitUntil(10000){vm.route.value=="category-picker"&&vm.entry.value?.kind==EntryKind.INCOME}
            assertNull(vm.entry.value?.categoryId)
            assertEquals("",vm.entry.value?.amount)
            ui.runOnIdle{vm.leaveEntry(a,false,"ledger")}
            ui.waitUntil(10000){vm.route.value=="ledger"&&(vm.state.value as? AppState.Ready)?.snapshot?.draft==null}
            ui.onNodeWithTag("ledger-entry-EXPENSE").performScrollTo().performClick()
            ui.waitUntil(10000){vm.route.value=="category-picker"&&vm.entry.value?.kind==EntryKind.EXPENSE}
            ui.runOnIdle{vm.leaveEntry(a,false,"ledger")}
            ui.waitUntil(10000){vm.route.value=="ledger"&&(vm.state.value as? AppState.Ready)?.snapshot?.draft==null}
            assertEquals(1,snapshot(a).events.size)
            assertEquals(9700L,snapshot(a).balance(snapshot(a).accounts.single()))
            assertNull(snapshot(b).draft)
        }
    }
    @Test fun ledgerSearchAndParentFilterKeepOriginalRecordsAcrossThemes() {
        val second=runBlocking {
            val root=repo.dao.category(a,category)!!.parentId!!
            repo.saveEntry(a,EntryDraft(accountId=account,categoryId=root,amount="500",merchant="食材店",note="每周采购"))
        }
        for(theme in listOf("magazine","journal","objects")){
            ui.runOnIdle{vm.appearance(a,theme=theme);vm.navigate("ledger")}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.theme==theme}
            waitFor("日常的每一笔。")
            ui.onNodeWithTag("event-"+event).performScrollTo().assertIsDisplayed()
            captureScreen("ledger",theme)
            ui.onNodeWithTag("field-搜索商户、备注或金额").performScrollTo().performTextReplacement("采购")
            ui.onNodeWithTag("event-"+event).assertDoesNotExist()
            ui.onNodeWithTag("event-"+second).assertExists()
            ui.onNodeWithTag("field-搜索商户、备注或金额").performTextReplacement("300")
            ui.onNodeWithTag("event-"+event).assertExists()
            ui.onNodeWithTag("event-"+second).assertDoesNotExist()
            ui.onNodeWithTag("field-搜索商户、备注或金额").performTextReplacement("")
            ui.onNodeWithTag("ledger-filters").performScrollTo().performClick()
            ui.onNodeWithTag("select-trigger-分类").performScrollTo().performClick()
            ui.onNodeWithTag("select-option-分类-1").performScrollTo().performClick()
            ui.onNodeWithTag("event-"+event).assertExists()
            ui.onNodeWithTag("event-"+second).assertExists()
            ui.onNodeWithTag("select-trigger-分类").performScrollTo().performClick()
            ui.onNodeWithTag("select-option-分类-2").performScrollTo().performClick()
            ui.onNodeWithTag("event-"+event).assertExists()
            ui.onNodeWithTag("event-"+second).assertDoesNotExist()
            tap("清除筛选")
            ui.onNodeWithTag("ledger-filters").performScrollTo().performClick()
            ui.onNodeWithTag("event-"+event).performScrollTo().performClick()
            waitFor("这一笔的详情")
            ui.onNodeWithText("×").performClick()
            ui.waitUntil(10000){vm.route.value=="ledger"}
        }
        val saved=snapshot(a)
        assertEquals(2,saved.events.size)
        assertEquals(9200L,saved.balance(saved.accounts.single()))
    }
    @Test fun categorySearchKeepsHierarchyAndIndependentExpandedParents(){
        val root=snapshot(a).categories.first{it.id==category}.parentId!!
        val other=runBlocking{repo.saveCategory(a,"交通",EntryKind.EXPENSE,null)}
        runBlocking{repo.saveCategory(a,"電車",EntryKind.EXPENSE,other)}
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")){
            ui.runOnIdle{vm.appearance(a,theme=theme,language=language);vm.startEntry(snapshot(a))}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.let{it.theme==theme&&it.language==language}==true}
            ui.onNodeWithTag("category-group-trigger-$root").performScrollTo().performClick()
            ui.onNodeWithTag("category-group-trigger-$other").performScrollTo().performClick()
            ui.onNodeWithTag("category-parent-choice-$root").assertExists()
            ui.onNodeWithTag("category-parent-choice-$other").assertExists()
            val search=ui.onNodeWithTag(if(language=="zh")"field-搜索分类" else "field-カテゴリを検索")
            search.performScrollTo().performTextReplacement("咖啡")
            ui.onNodeWithTag("category-group-$root").assertExists()
            ui.onNodeWithTag("category-group-$other").assertDoesNotExist()
            ui.onNodeWithTag("category-parent-choice-$root").assertExists()
            search.performTextReplacement("不存在的分类")
            ui.onNodeWithTag("category-no-results").performScrollTo().assertIsDisplayed()
            ui.onAllNodesWithTag("category-group-$root").assertCountEquals(0)
            search.performScrollTo().performTextReplacement("咖啡")
            captureScreen("category-search","$theme-$language")
            ui.onNodeWithTag("category-child-$category").performScrollTo().performClick()
            ui.waitUntil(10000){vm.route.value=="entry"}
            assertEquals(category,vm.entry.value!!.categoryId)
            assertEquals(1,snapshot(a).visibleEvents.size);assertEquals(9700L,snapshot(a).balance(snapshot(a).accounts.single()))
            ui.runOnIdle{vm.leaveEntry(a,false)};ui.waitUntil(10000){vm.entry.value==null}
        }
    }
    @Test fun allThemesSelectParentOrFavoriteChildWithoutCreatingExpense() {
        val root=runBlocking{
            val child=repo.dao.category(a,category)!!
            repo.saveCategory(a,child.name,EntryKind.EXPENSE,child.parentId,child.id,true)
            child.parentId!!
        }
        for(theme in listOf("magazine","journal","objects")){
            ui.runOnIdle{vm.appearance(a,theme=theme)}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.theme==theme}
            ui.runOnIdle{vm.startEntry(snapshot(a))}
            ui.waitUntil(10000){ui.onAllNodesWithTag("category-cover").fetchSemanticsNodes().isNotEmpty()}
            tap("餐饮")
            captureScreen("category-picker",theme)
            tap("餐饮 / 未细分")
            ui.waitUntil(10000){vm.route.value=="entry"}
            assertEquals(root,vm.entry.value!!.categoryId)
            ui.runOnIdle{vm.leaveEntry(a,false)}
            ui.waitUntil(10000){vm.entry.value==null&&vm.route.value=="home"}
            ui.runOnIdle{vm.startEntry(snapshot(a))}
            waitFor("我的常用 · 一步选择")
            ui.onAllNodesWithTag("category-shortcut-"+category).onFirst().performScrollTo().performClick()
            ui.waitUntil(10000){vm.route.value=="entry"}
            assertEquals(category,vm.entry.value!!.categoryId)
            ui.runOnIdle{vm.leaveEntry(a,false)}
            ui.waitUntil(10000){vm.entry.value==null&&vm.route.value=="home"}
        }
        assertEquals(1,snapshot(a).events.size)
        assertEquals(9700L,snapshot(a).balance(snapshot(a).accounts.single()))
    }
    @Test fun entryReselectKeepsAmountAccountAndOriginalIdInAllThemes() {
        val before=snapshot(a)
        val root=before.categories.first{it.id==category}.parentId!!
        for(theme in listOf("magazine","journal","objects")){
            ui.runOnIdle{vm.appearance(a,theme=theme)}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.theme==theme}
            ui.runOnIdle{vm.editEntry(before,before.events.single())}
            waitFor("金额 · JPY")
            ui.onNodeWithTag("field-金额 · JPY").performTextReplacement("680")
            ui.onNodeWithTag("entry-reselect").performScrollTo().performClick()
            waitFor("餐饮 / 未细分")
            tap("餐饮 / 未细分")
            ui.waitUntil(10000){vm.route.value=="entry"}
            ui.onNodeWithTag("field-金额 · JPY").assertTextEquals("680")
            assertEquals(root,vm.entry.value!!.categoryId)
            assertEquals(account,vm.entry.value!!.accountId)
            assertEquals(event,vm.entry.value!!.id)
            captureScreen("entry",theme)
            ui.runOnIdle{vm.leaveEntry(a,false)}
            ui.waitUntil(10000){vm.entry.value==null&&vm.route.value=="home"}
        }
        assertEquals(300L,snapshot(a).events.single().amountMinor)
        assertEquals(category,snapshot(a).events.single().categoryId)
    }
    @Test fun incomeSwitchesToFundsAndAppearsInIncomeFilterWithoutRaisingBudget() {
        val credit=runBlocking{
            repo.saveCategory(a,"兼职",EntryKind.INCOME,null)
            repo.updateBook(repo.dao.book(a)!!.copy(budgetMinor=5000))
            repo.saveAccount(a,"信用卡",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,4000),"2026-10-01")
        }
        for(theme in listOf("magazine","journal","objects")){
            val ja=theme!="magazine"
            ui.runOnIdle{vm.appearance(a,theme=theme,language=if(ja)"ja" else "zh")}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.theme==theme}
            val before=snapshot(a)
            ui.runOnIdle{vm.startEntry(before);vm.changeEntry(a,vm.entry.value!!.copy(accountId=credit))}
            ui.onNodeWithTag("category-kind-INCOME").performScrollTo().performClick()
            assertEquals(account,vm.entry.value!!.accountId)
            waitFor("兼职")
            ui.onAllNodesWithText("兼职").onLast().performScrollTo().performClick()
            val accountLabel=if(ja)"入金口座" else "到账账户"
            ui.onNodeWithTag("select-trigger-"+accountLabel).performScrollTo().performClick()
            ui.onAllNodesWithTag("select-option-$accountLabel-1").assertCountEquals(0)
            ui.onNodeWithTag("select-option-$accountLabel-0").performClick()
            ui.onNodeWithTag(if(ja)"field-金額 · JPY" else "field-金额 · JPY").performScrollTo().performTextInput("4250")
            ui.onNodeWithTag(if(ja)"field-名称 / 内容" else "field-名称 / 说明").performScrollTo().performTextInput("手动收入")
            ui.waitUntil(10000){!vm.busy.value}
            tap(if(ja)"記録を保存" else "保存记录")
            ui.waitUntil(10000){vm.entry.value==null&&vm.route.value=="ledger"}
            val after=snapshot(a)
            assertEquals(before.balance(before.accounts.first{it.id==account})+4250,after.balance(after.accounts.first{it.id==account}))
            assertEquals(4000L,after.balance(after.accounts.first{it.id==credit}))
            assertEquals(5000L,after.book.budgetMinor)
            val typeLabel=if(ja)"種類" else "类型"
            ui.onNodeWithTag("select-trigger-"+typeLabel).performScrollTo().performClick()
            ui.onNodeWithTag("select-option-$typeLabel-2").performScrollTo().performClick()
            ui.onNodeWithTag("event-"+event).assertDoesNotExist()
            val saved=after.visibleEvents.first{it.kind=="INCOME"}
            ui.onNodeWithTag("event-"+saved.id).assertExists()
            ui.runOnIdle{vm.navigate("home")}
        }
    }
    @Test fun deletingAndRestoringThroughTrashKeepsOriginalIdAndBalance() {
        runBlocking{repo.updateBook(repo.dao.book(a)!!.copy(budgetMinor=10000))}
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")){
            val ja=language=="ja"
            ui.runOnIdle{vm.appearance(a,theme=theme,language=language)}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.let{it.theme==theme&&it.language==language}==true}
            tap("喫茶と余白")
            tap(if(ja)"ごみ箱に移動" else "移至回收站")
            ui.onNodeWithTag("impact-budget").performScrollTo().onChildren().onLast().assertTextEquals("¥9,700 → ¥10,000")
            captureScreen("delete-impact","$theme-$language")
            tap(if(ja)"ごみ箱へ移動" else "确认移至回收站")
            ui.waitUntil(10000){snapshot(a).visibleEvents.isEmpty()&&vm.route.value=="ledger"}
            assertEquals(10000L,snapshot(a).balance(snapshot(a).accounts.single()))
            ui.onNodeWithTag("header-settings").performScrollTo().performClick()
            tap(if(ja)"ごみ箱 · 30日" else "回收站 · 30天")
            waitFor(if(ja)"記録を復元" else "恢复原记录")
            ui.onNodeWithTag("trash-"+event).assertExists()
            ui.onNodeWithTag("restore-"+event).performScrollTo().performClick()
            ui.waitUntil(10000){ui.onAllNodesWithTag("confirm-entry-lifecycle").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("impact-balance-"+account).performScrollTo().onChildren().onLast().assertTextEquals("¥10,000 → ¥9,700")
            ui.onNodeWithTag("impact-budget").performScrollTo().onChildren().onLast().assertTextEquals("¥10,000 → ¥9,700")
            captureScreen("trash","$theme-$language")
            ui.onNodeWithTag("confirm-entry-lifecycle").performScrollTo().performClick()
            ui.waitUntil(10000){snapshot(a).visibleEvents.size==1}
            val restored=snapshot(a)
            assertEquals(event,restored.visibleEvents.single().id)
            assertEquals(1,restored.visibleEvents.single().revision)
            assertEquals(9700L,restored.balance(restored.accounts.single()))
            assertNull(restored.removalImpact(event).remainingBudget(java.time.YearMonth.now(repo.clock).minusMonths(1).toString(),10000))
            assertNull(restored.removalImpact(event).remainingBudget(java.time.YearMonth.now(repo.clock).toString(),null))
            ui.runOnIdle{vm.navigate("home")}
        }
    }
    @Test fun incomeAndOpeningIncludedRemovalShowTheirActualBudgetEffects(){
        val income=runBlocking{
            repo.updateBook(repo.dao.book(a)!!.copy(budgetMinor=10000))
            val c=repo.saveCategory(a,"収入",EntryKind.INCOME,null)
            repo.saveEntry(a,EntryDraft(kind=EntryKind.INCOME,accountId=account,categoryId=c,amount="400",merchant="income-preview"))
        }
        val historical=runBlocking{repo.saveEntry(a,EntryDraft(accountId=account,categoryId=category,amount="250",date="2026-10-01",merchant="opening-included",historicalIncluded=true))}
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")){
            ui.runOnIdle{vm.appearance(a,theme=theme,language=language);vm.navigate("ledger")}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.let{it.theme==theme&&it.language==language}==true}
            for(id in listOf(income,historical)){
                ui.onNodeWithTag("event-"+id).performScrollTo().performClick()
                tap(if(language=="ja")"ごみ箱に移動" else "移至回收站")
                if(id==income){
                    ui.onNodeWithTag("impact-income").performScrollTo().onChildren().onLast().assertTextEquals("¥400 → ¥0")
                    ui.onNodeWithTag("impact-budget").performScrollTo().onChildren().onLast().assertTextEquals("¥9,450 → ¥9,450")
                }else{
                    ui.onNodeWithTag("impact-balance-"+account).assertDoesNotExist()
                    ui.onNodeWithTag("impact-budget").performScrollTo().onChildren().onLast().assertTextEquals("¥9,450 → ¥9,700")
                }
                captureScreen("impact-types","$theme-$language-${if(id==income)"income" else "opening"}")
                val before=snapshot(a)
                tap(if(language=="ja")"ごみ箱へ移動" else "确认移至回收站")
                ui.waitUntil(10000){snapshot(a).events.first{it.id==id}.deletedAt!=null}
                if(id==historical)assertEquals(before.balance(before.accounts.single()),snapshot(a).balance(snapshot(a).accounts.single()))
                runBlocking{repo.restoreEntry(a,id)}
                ui.waitUntil(10000){snapshot(a).visibleEvents.size==3}
            }
            assertEquals(10100L,snapshot(a).balance(snapshot(a).accounts.single()))
        }
    }
    @Test fun settingsPreviewsSwitchOnlyCurrentBooksThemeAndLanguage() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val metrics=instrumentation.targetContext.resources.displayMetrics
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=java.io.File(instrumentation.targetContext.filesDir,"verification/settings$suffix").also{it.mkdirs()}
        val dimensions=java.io.File(folder,"dimensions.csv").also{it.writeText("theme,language,option,width,height\n")}
        val pageDimensions=java.io.File(folder,"page-dimensions.csv").also{it.writeText("theme,language,component,x,y,width,height\n")}
        fun measureOptions(theme:String,language:String){
            ui.onNodeWithTag("page-edition").performScrollTo()
            val start=ui.onNodeWithTag("page-edition").getUnclippedBoundsInRoot().top.value
            for(tag in listOf("page-back","page-title","page-title-heading","settings-theme-panel")){
                val r=ui.onNodeWithTag(tag).getUnclippedBoundsInRoot()
                val y=r.top.value-start;val height=(r.bottom-r.top).value
                pageDimensions.appendText("$theme,$language,$tag,${r.left.value},$y,${(r.right-r.left).value},$height\n")
                if(metrics.widthPixels/metrics.density==390f&&instrumentation.targetContext.resources.configuration.fontScale==1f){
                    if(tag=="page-title"){
                        // Magazine's masthead and back bar no longer draw hairlines (2026-10-25 rule hierarchy): anchors move up 2dp.
                        val expectedY=when(theme){"magazine"->92f;"journal"->101f;else->83f}
                        assertEquals("HTML title page anchor: $theme/$language",expectedY,y,1f)
                    }

                }
            }
            for(option in listOf("magazine","journal","objects")){
                val node=ui.onNodeWithTag("theme-choice-"+option).performScrollTo()
                val bounds=node.getUnclippedBoundsInRoot()
                val width=(bounds.right-bounds.left).value;val height=(bounds.bottom-bounds.top).value
                dimensions.appendText("$theme,$language,$option,$width,$height\n")
                if(metrics.widthPixels/metrics.density==390f&&instrumentation.targetContext.resources.configuration.fontScale==1f){
                    val expectedWidth=when(theme){"magazine"->340f;"journal"->313f;else->316f}
                    val expectedHeight=when(theme){"magazine"->107f;"journal"->105f;else->101f}
                    assertEquals("HTML option width: $theme/$language/$option",expectedWidth,width,1f)
                    assertEquals("HTML option height: $theme/$language/$option",expectedHeight,height,1f)
                }
            }
            ui.onNodeWithTag("theme-choice-"+theme).performScrollTo()
        }
        runBlocking{repo.updateBook(repo.dao.book(a)!!.copy(budgetMinor=52500))}
        for(theme in listOf("magazine","journal","objects")){
            ui.runOnIdle{vm.appearance(a,language="zh");vm.navigate("settings")}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.language=="zh"}
            ui.onNodeWithTag("settings-appearance").performScrollTo().performClick()
            ui.onNodeWithTag("theme-choice-"+theme).performScrollTo().performClick()
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.theme==theme}
            ui.onNodeWithTag("theme-choice-"+theme).assertIsSelected()
            measureOptions(theme,"zh")
            captureScreen("settings","$theme-zh")
            ui.onNodeWithTag("page-back").performScrollTo().performClick()
            ui.onNodeWithTag("settings-language").performScrollTo().performClick()
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.language=="ja"}
            ui.onNodeWithTag("settings-appearance").performScrollTo().performClick()
            ui.onNodeWithTag("theme-choice-"+theme).performScrollTo().assertIsSelected()
            measureOptions(theme,"ja")
            captureScreen("settings","$theme-ja")
            val current=snapshot(a)
            assertEquals(theme,current.book.theme)
            assertEquals("ja",current.book.language)
            assertEquals(52500L,current.book.budgetMinor)
            assertEquals(9700L,current.balance(current.accounts.single()))
            val other=snapshot(b)
            assertEquals("journal",other.book.theme)
            assertEquals("ja",other.book.language)
            assertTrue(other.accounts.isEmpty())
        }
    }
    @Test fun discardWhileSwitchingBookDoesNotChangeOriginalRecord() {
        tap("喫茶と余白")
        tap("↺ 编辑记录")
        ui.onNodeWithTag("field-金额 · JPY").performTextReplacement("680")
        ui.onNodeWithTag("header-book").performScrollTo().performClick()
        tap("別の生活")
        tap("放弃草稿并切换")
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==b&&vm.entry.value==null}
        val source=snapshot(a)
        assertNull(source.draft)
        assertEquals(event,source.events.single().id)
        assertEquals(300L,source.events.single().amountMinor)
        assertEquals(9700L,source.balance(source.accounts.single()))
        assertNull(snapshot(b).draft)
        ui.onNodeWithTag("header-book").performScrollTo().performClick()
        ui.onNodeWithTag("book-choice-$a").performScrollTo().performClick()
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==a}
        ui.onAllNodesWithText("继续本账本未保存的记录").assertCountEquals(0)
    }
    @Test fun customDateRangeUsesBothBoundsAndDoesNotAlterEntries() {
        val month=java.time.YearMonth.now()
        val later=runBlocking{
            repo.saveEntry(a,EntryDraft(id=event,accountId=account,categoryId=category,amount="300",date=month.atDay(5).toString(),merchant="喫茶と余白"))
            repo.saveEntry(a,EntryDraft(accountId=account,categoryId=category,amount="200",date=month.atDay(15).toString(),merchant="月中消费"))
        }
        ui.runOnIdle{vm.navigate("ledger")}
        waitFor("日常的每一笔。")
        ui.onNodeWithTag("ledger-filters").performScrollTo().performClick()
        ui.onNodeWithTag("select-trigger-日期范围").performScrollTo().performClick()
        ui.onNodeWithTag("select-option-日期范围-2").performScrollTo().performClick()
        ui.onNodeWithTag("date-field-0").performScrollTo().performClick()
        ui.onNodeWithText("10").performScrollTo().performClick()
        ui.onNodeWithTag("date-field-1").performScrollTo().performClick()
        ui.onNodeWithText("20").performScrollTo().performClick()
        ui.onNodeWithTag("event-"+event).assertDoesNotExist()
        ui.onNodeWithTag("event-"+later).assertExists()
        ui.onNodeWithTag("date-field-0").performScrollTo().performClick()
        ui.onNodeWithText("25").performScrollTo().performClick()
        waitFor("结束日期不能早于开始日期。")
        ui.onNodeWithTag("event-"+later).assertDoesNotExist()
        tap("清除筛选")
        ui.onNodeWithTag("event-"+event).assertExists()
        ui.onNodeWithTag("event-"+later).assertExists()
        ui.onNodeWithTag("select-trigger-日期范围").performScrollTo().performClick()
        ui.onNodeWithTag("select-option-日期范围-2").performScrollTo().performClick()
        ui.onAllNodesWithText("结束日期不能早于开始日期。").assertCountEquals(0)
        val saved=snapshot(a)
        assertEquals(2,saved.events.size)
        assertEquals(9500L,saved.balance(saved.accounts.single()))
    }
    @Test fun accountOpeningEditPreviewCancelAndSaveKeepLedgerIntact() {
        for(theme in listOf("magazine","journal","objects")){
            runBlocking{repo.saveAccount(a,"现金",AccountType.CASH,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01",account)}
            ui.runOnIdle{vm.appearance(a,theme=theme);vm.navigate("accounts")}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.theme==theme}
            ui.onNodeWithTag("account-"+account).performScrollTo().performClick()
            tap("编辑账户")
            ui.waitUntil(10000){ui.onAllNodesWithTag("account-form").fetchSemanticsNodes().isNotEmpty()&&!vm.busy.value}
            ui.onNodeWithTag("account-currency-fixed").assertExists()
            ui.onNodeWithTag("select-trigger-账户币种").assertDoesNotExist()
            ui.onNodeWithTag("field-期初余额").performScrollTo().performTextReplacement("12000")
            waitFor("保存后账户余额：¥11,700")
            ui.onNodeWithText("×").performClick()
            assertEquals(10000L,snapshot(a).accounts.single().openingMinor)
            assertEquals(9700L,snapshot(a).balance(snapshot(a).accounts.single()))
            tap("编辑账户")
            ui.waitUntil(10000){ui.onAllNodesWithTag("account-form").fetchSemanticsNodes().isNotEmpty()&&!vm.busy.value}
            ui.onNodeWithTag("account-currency-fixed").assertExists()
            ui.onNodeWithTag("select-trigger-账户币种").assertDoesNotExist()
            ui.onNodeWithTag("field-期初余额").assertTextEquals("10000")
            ui.onNodeWithTag("field-账户名称").performTextReplacement("随身现金")
            ui.onNodeWithTag("field-期初余额").performScrollTo().performTextReplacement("12000")
            ui.onNodeWithText("保存后账户余额：¥11,700").performScrollTo().assertIsDisplayed()
            captureScreen("account-edit",theme)
            tap("保存账户信息")
            ui.waitUntil(10000){snapshot(a).accounts.single().openingMinor==12000L}
            val saved=snapshot(a)
            assertEquals(account,saved.accounts.single().id)
            assertEquals("JPY",saved.accounts.single().currency)
            assertEquals("随身现金",saved.accounts.single().name)
            assertEquals(11700L,saved.balance(saved.accounts.single()))
            assertEquals(event,saved.events.single().id)
            assertEquals(300L,saved.events.single().amountMinor)
            assertEquals(1,saved.events.single().revision)
            assertEquals(-300L,saved.postings.single().deltaMinor)
            ui.runOnIdle{vm.navigate("home")}
        }
    }
    @Test fun navigationUsesOriginalColumnRatioAndSelectedState() {
        for(theme in listOf("magazine","journal","objects")){
            ui.runOnIdle{vm.appearance(a,theme=theme);vm.navigate("home")}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.theme==theme}
            ui.onNodeWithTag("nav-home").assertIsSelected()
            val side=ui.onNodeWithTag("nav-home").fetchSemanticsNode().boundsInRoot.width
            val center=ui.onNodeWithTag("nav-add").fetchSemanticsNode().boundsInRoot.width
            assertEquals(1.3f,center/side,.01f)
            val density=InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            val bar=ui.onNodeWithTag("bottom-nav").fetchSemanticsNode().boundsInRoot
            assertEquals(if(theme=="objects")83f else 80f,bar.height/density,1f)
            val edition=ui.onNodeWithTag("page-edition").fetchSemanticsNode().boundsInRoot
            assertEquals(when(theme){"magazine"->22f;"journal"->24f;else->11f},edition.height/density,1f)
            ui.onNodeWithTag("nav-accounts").performClick()
            ui.onNodeWithTag("nav-accounts").assertIsSelected()
            ui.onNodeWithTag("nav-home").assertIsNotSelected()
            captureScreen("navigation",theme)
            ui.onNodeWithTag("nav-add").performClick()
            ui.waitUntil(10000){ui.onAllNodesWithTag("category-cover").fetchSemanticsNodes().isNotEmpty()}
            assertEquals("category-picker",vm.route.value)
            ui.runOnIdle{vm.leaveEntry(a,false)}
            ui.waitUntil(10000){vm.entry.value==null}
        }
    }
    @Test fun themedAnalysisTotalsAndDrilldownCountEachExpenseOnce(){
        val dimensions=mutableListOf<String>()
        val root=snapshot(a).categories.first{it.id==category}.parentId!!
        val other=runBlocking{
            val child=repo.saveCategory(a,"食材",EntryKind.EXPENSE,root)
            val other=repo.saveCategory(a,"日用",EntryKind.EXPENSE,null)
            val income=repo.saveCategory(a,"収入",EntryKind.INCOME,null)
            repo.saveEntry(a,EntryDraft(accountId=account,categoryId=child,amount="200",merchant="市场"))
            repo.saveEntry(a,EntryDraft(accountId=account,categoryId=root,amount="100",merchant="未细分消费"))
            repo.saveEntry(a,EntryDraft(accountId=account,categoryId=other,amount="50",merchant="日用品"))
            repo.saveEntry(a,EntryDraft(kind=EntryKind.INCOME,accountId=account,categoryId=income,amount="400",merchant="收入"))
            repo.saveEntry(a,EntryDraft(accountId=account,categoryId=other,amount="600",date=java.time.LocalDate.now().minusMonths(1).withDayOfMonth(1).toString(),merchant="上月消费"))
            repo.saveAnalysisPreferences(a,listOf(AnalysisModule.CATEGORIES),emptySet())
            other
        }
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")){
            ui.runOnIdle{vm.appearance(a,theme=theme,language=language);vm.navigate("analysis")}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.let{it.theme==theme&&it.language==language}==true}
            ui.onNodeWithTag("lead-amount").performScrollTo().assertIsDisplayed()
            ui.onNodeWithTag("lead-amount").assertTextEquals(if(theme=="journal")"¥650 ↗" else "¥650")
            val totalBounds=ui.onNodeWithTag("lead-amount").fetchSemanticsNode().boundsInRoot
            val totalDensity=InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            dimensions+="$theme,$language,total,${totalBounds.width/totalDensity},${totalBounds.height/totalDensity}"
            captureScreen("basic-analysis","$theme-$language-top")
            ui.onNodeWithTag("analysis-category-$other").performScrollTo().assertIsDisplayed()
            ui.onNodeWithText("¥50").assertExists()
            captureScreen("basic-analysis","$theme-$language-categories")
            ui.onNodeWithTag("analysis-category-$root").performScrollTo()
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            val bounds=ui.onNodeWithTag("analysis-category-$root").fetchSemanticsNode().boundsInRoot
            val density=context.resources.displayMetrics.density
            dimensions+="$theme,$language,${bounds.width/density},${bounds.height/density}"
            ui.onNodeWithTag("analysis-category-$root").performClick()
            ui.onAllNodesWithText("餐饮").onLast().assertExists()
            ui.onNodeWithText(if(language=="zh")"未细分 · 直接记入一级" else "内訳未分類・親への直接記録").performScrollTo().assertIsDisplayed()
            ui.onAllNodesWithText("¥100").onFirst().assertExists()
            ui.onAllNodesWithText("¥200").onFirst().assertExists()
            captureScreen("basic-analysis","$theme-$language-detail")
            tap("喫茶と余白")
            waitFor(if(language=="zh")"这一笔的详情" else "この一筆の詳細")
            ui.onNodeWithText("×").performClick()
            ui.waitUntil(10000){vm.route.value=="analysis"}
            assertEquals(6,snapshot(a).events.size)
            assertEquals(9150L,snapshot(a).balance(snapshot(a).accounts.single()))
        }
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"verification/basic-analysis$suffix/dimensions.csv").writeText(dimensions.joinToString("\n"))
    }
    @Test fun categoryManagementEditsFavoritesAndCreatesSeparateIncomeWithoutChangingLedger(){
        val sizes=mutableListOf<String>()
        val root=snapshot(a).categories.first{it.id==category}.parentId!!
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")){
            val ja=language=="ja"
            val variant="$theme-$language"
            ui.runOnIdle{vm.appearance(a,theme=theme,language=if(ja)"ja" else "zh");vm.navigate("categories")}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.let{it.theme==theme&&it.language==language}==true}
            val nameTag=if(ja)"field-カテゴリ名" else "field-分类名称"
            val save=if(ja)"カテゴリを保存" else "保存分类"
            val before=snapshot(a)
            ui.onNodeWithTag("category-manage-parent-$root").performScrollTo()
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            val rect=ui.onNodeWithTag("category-manage-parent-$root").fetchSemanticsNode().boundsInRoot
            val density=context.resources.displayMetrics.density
            sizes+="$theme,$language,${rect.width/density},${rect.height/density}"
            if(context.resources.configuration.fontScale==1f)assertEquals(if(theme=="journal")94f else 87f,rect.height/density,1f)
            ui.onNodeWithTag("category-manage-parent-$root").performClick()
            ui.onNodeWithText(if(ja)"子カテゴリがあるため親カテゴリを保持。" else "已有子分类，保持为一级分类。").assertExists()
            ui.onNodeWithTag(nameTag).performTextReplacement("取消的用途")
            ui.onNodeWithText("×").performClick()
            assertEquals(before.categories.first{it.id==root}.name,snapshot(a).categories.first{it.id==root}.name)
            if(!snapshot(a).categories.first{it.id==category}.favorite){
                ui.onNodeWithTag("category-favorites-toggle").performScrollTo().performClick()
                ui.onNodeWithTag("category-favorite-$category").performScrollTo().performClick()
                ui.waitUntil(10000){snapshot(a).categories.first{it.id==category}.favorite}
            }
            ui.onNodeWithTag("category-manage-child-$category").performScrollTo().performClick()
            ui.onNodeWithTag("category-parent-fixed").assertExists()
            ui.onNodeWithTag(nameTag).performTextReplacement("一杯 $variant")
            ui.onNodeWithTag(if(ja)"toggle-よく使うカテゴリ" else "toggle-常用分类").assertDoesNotExist()
            captureScreen("category-editor",variant)
            tap(save)
            ui.waitUntil(10000){snapshot(a).categories.first{it.id==category}.name=="一杯 $variant"}
            assertTrue(snapshot(a).categories.first{it.id==category}.favorite)
            ui.onNodeWithTag("category-manage-add-$root").performScrollTo().performClick()
            ui.onNodeWithTag(nameTag).performTextReplacement("軽食 $variant")
            tap(save)
            ui.waitUntil(10000){snapshot(a).categories.any{it.name=="軽食 $variant"}}
            assertEquals(root,snapshot(a).categories.first{it.name=="軽食 $variant"}.parentId)
            ui.onAllNodesWithText(if(ja)"＋ 親カテゴリを追加" else "＋ 添加一级分类").onLast().performScrollTo().performClick()
            ui.onNodeWithTag(nameTag).performTextReplacement("兼职 $variant")
            tap(save)
            ui.waitUntil(10000){snapshot(a).categories.any{it.name=="兼职 $variant"}}
            val incomeId=snapshot(a).categories.first{it.name=="兼职 $variant"}.id
            if(ui.onAllNodesWithTag("category-favorite-$incomeId").fetchSemanticsNodes().isEmpty())ui.onNodeWithTag("category-favorites-toggle").performScrollTo().performClick()
            ui.onNodeWithTag("category-favorite-$incomeId").performScrollTo().performClick()
            ui.waitUntil(10000){snapshot(a).categories.first{it.id==incomeId}.favorite}
            ui.onNodeWithTag("category-favorite-$incomeId").assertIsSelected().performClick()
            ui.waitUntil(10000){!snapshot(a).categories.first{it.id==incomeId}.favorite}
            val saved=snapshot(a)
            assertEquals(EntryKind.INCOME.name,saved.categories.first{it.name=="兼职 $variant"}.kind)
            assertNull(saved.categories.first{it.name=="兼职 $variant"}.parentId)
            assertEquals(category,saved.events.single().categoryId);assertEquals(event,saved.events.single().id)
            assertEquals(300L,saved.events.single().amountMinor);assertEquals(1,saved.events.single().revision)
            assertEquals(9700L,saved.balance(saved.accounts.single()))
            assertEquals("餐饮 / 一杯 $variant",saved.path(category))
            assertTrue(snapshot(b).categories.isEmpty())
            ui.onNodeWithTag("category-manage-parent-$root").performScrollTo()
            captureScreen("category-management",variant)
        }
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"verification/category-management$suffix/dimensions.csv").writeText(sizes.joinToString("\n"))
    }
    @Test fun bookMetadataCancelSaveAndEmptyCurrencyChangeStayIsolated(){
        for(theme in listOf("magazine","journal","objects")){
            val ja=theme=="journal"
            ui.runOnIdle{vm.appearance(a,theme=theme,language=if(ja)"ja" else "zh");vm.navigate("settings")}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.theme==theme}
            val old=snapshot(a).book.name
            val previousLimit=snapshot(a).budgetProjection(java.time.YearMonth.now(repo.clock).toString()).limitMinor
            val nameTag=if(ja)"field-帳簿名" else "field-账本名称"
            val budgetTag=if(ja)"field-月予算・後から設定可" else "field-月预算 / 可稍后设置"
            val info=if(ja)"帳簿情報" else "账本名称与资料"
            tap(info)
            ui.onNodeWithTag(if(ja)"select-trigger-基準通貨" else "select-trigger-统计本位币").assertDoesNotExist()
            ui.onNodeWithTag(nameTag).performTextReplacement("取消修改")
            ui.onNodeWithText("×").performClick()
            assertEquals(old,snapshot(a).book.name)
            tap(info)
            ui.onNodeWithTag(nameTag).assertTextEquals(old)
            ui.onNodeWithTag(nameTag).performTextReplacement("日常 $theme")
            ui.onNodeWithTag(budgetTag).performScrollTo().performTextReplacement("52000")
            captureScreen("book-editor",theme)
            tap(if(ja)"帳簿を保存" else "保存账本")
            if(previousLimit!=52000L){
                assertEquals(old,snapshot(a).book.name)
                tap(if(ja)"帳簿と予算を保存" else "确认保存账本与预算")
            }
            ui.waitUntil(10000){snapshot(a).book.name=="日常 $theme"}
            val saved=snapshot(a)
            assertEquals("JPY",saved.book.currency);assertEquals(52000L,saved.budgetProjection(java.time.YearMonth.now(repo.clock).toString()).limitMinor)
            assertEquals(event,saved.events.single().id);assertEquals(300L,saved.events.single().amountMinor)
            assertEquals(9700L,saved.balance(saved.accounts.single()))
            assertEquals("journal",snapshot(b).book.theme);assertEquals("ja",snapshot(b).book.language)
        }
        ui.runOnIdle{vm.selectBook(b);vm.navigate("settings")}
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==b}
        ui.runOnIdle{vm.navigate("settings")}
        tap("帳簿情報")
        ui.onNodeWithTag("select-trigger-基準通貨").performScrollTo().performClick()
        ui.onNodeWithTag("select-option-基準通貨-2").performScrollTo().performClick()
        ui.onNodeWithTag("field-月予算・後から設定可").performScrollTo().performTextReplacement("12.34")
        tap("帳簿を保存")
        assertEquals("CNY",snapshot(b).book.currency)
        tap("帳簿と予算を保存")
        ui.waitUntil(10000){snapshot(b).book.currency=="USD"}
        assertEquals(1234L,snapshot(b).budgetProjection(java.time.YearMonth.now(repo.clock).toString()).limitMinor)
        assertTrue(snapshot(b).events.isEmpty());assertTrue(snapshot(b).accounts.isEmpty())
        assertEquals("JPY",snapshot(a).book.currency);assertEquals(52000L,snapshot(a).budgetProjection(java.time.YearMonth.now(repo.clock).toString()).limitMinor)
    }
    @Test fun realKeyboardLeavesSaveReachableAcrossThemesAndLanguages(){
        Assume.assumeTrue("Requires -e realKeyboard true with an actual visible IME",InstrumentationRegistry.getArguments().getString("realKeyboard")=="true")
        val measurements=mutableListOf("theme,language,viewportBefore,viewportWithIme,saveBottom")
        val fieldSizes=mutableListOf("theme,language,field,width,height")
        val resources=InstrumentationRegistry.getInstrumentation().targetContext.resources
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")){
            ui.runOnIdle{vm.appearance(a,theme=theme,language=language);vm.editEntry(snapshot(a),snapshot(a).visibleEvents.single())}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.let{it.theme==theme&&it.language==language}==true&&vm.entry.value!=null}
            for((label,expectedHeight) in listOf(
                (if(language=="zh")"金额 · JPY" else "金額 · JPY") to when(theme){"magazine"->106f;"journal"->119f;else->115f},
                (if(language=="zh")"商户 / 买了什么" else "お店 / 買ったもの") to when(theme){"magazine"->69f;"journal"->67f;else->72f}
            )){
                val r=ui.onNodeWithTag("field-group-"+label).getUnclippedBoundsInRoot()
                fieldSizes+="$theme,$language,$label,${(r.right-r.left).value},${(r.bottom-r.top).value}"
                if(resources.configuration.fontScale==1f)assertEquals("HTML field height: $theme/$language/$label",expectedHeight,(r.bottom-r.top).value,1f)
            }
            val noteTag=if(language=="zh")"field-备注" else "field-メモ"
            if(ui.onAllNodesWithTag(noteTag).fetchSemanticsNodes().isEmpty())ui.onNodeWithTag("entry-notes-toggle").performScrollTo().performClick()
            val viewport=ui.onNodeWithTag("sheet-viewport")
            val normalBottom=ui.runOnIdle{
                val view=android.view.inspector.WindowInspector.getGlobalWindowViews().last()
                val density=resources.displayMetrics.density
                val bars=androidx.core.view.ViewCompat.getRootWindowInsets(view)!!.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
                InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken,0)
                (view.height-bars.bottom)/density
            }
            ui.waitUntil(10000){viewport.getUnclippedBoundsInRoot().bottom.value>=normalBottom-1}
            val before=viewport.getUnclippedBoundsInRoot()
            ui.onNodeWithTag(noteTag).performScrollTo()
            // ScrollTo aligns to the raw viewport; a real user scrolls clear of the sticky title.
            val noteBounds=ui.onNodeWithTag(noteTag).getUnclippedBoundsInRoot()
            val titleBounds=ui.onNodeWithTag("sheet-header",useUnmergedTree=true).getUnclippedBoundsInRoot()
            if(noteBounds.top<titleBounds.bottom){
                val density=resources.displayMetrics.density
                ui.onNodeWithTag("sheet-scroll").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy){action->
                    action(0f,-((titleBounds.bottom-noteBounds.top).value+12)*density)
                }
                ui.waitForIdle()
            }
            val clickBounds=ui.onNodeWithTag(noteTag).getUnclippedBoundsInRoot()
            assertTrue("Input touch clears the sticky title",clickBounds.top>=ui.onNodeWithTag("sheet-header",useUnmergedTree=true).getUnclippedBoundsInRoot().bottom)
            captureScreen("entry-keyboard-touch","$theme-$language")
            assertTrue("Input touch stays in the viewport",clickBounds.bottom<=before.bottom)
            ui.onNodeWithTag(noteTag).performTouchInput{click()}
            try{ui.waitUntil(10000){viewport.getUnclippedBoundsInRoot().bottom.value<before.bottom.value-100}}
            catch(failure:androidx.compose.ui.test.ComposeTimeoutException){captureScreen("entry-keyboard-failure","$theme-$language");throw failure}
            val note="键盘输入 / キーボード $theme-$language"
            ui.onNodeWithTag(noteTag).performTextReplacement(note)
            val save=ui.onNodeWithText(if(language=="zh")"保存修改" else "変更を保存").performScrollTo().assertIsDisplayed()
            val visible=viewport.getUnclippedBoundsInRoot();val button=save.getUnclippedBoundsInRoot()
            assertTrue("Save must stay above the real IME",button.bottom.value<=visible.bottom.value+1)
            assertTrue("Save must stay below the visible header",button.top.value>=visible.top.value)
            measurements+="$theme,$language,${before.bottom.value},${visible.bottom.value},${button.bottom.value}"
            captureScreen("entry-keyboard","$theme-$language")
            save.performClick()
            ui.waitUntil(10000){vm.entry.value==null}
            val saved=snapshot(a)
            assertEquals(event,saved.visibleEvents.single().id)
            assertEquals(note,saved.visibleEvents.single().note)
            assertEquals(300L,saved.visibleEvents.single().amountMinor)
            assertEquals(9700L,saved.balance(saved.accounts.single()))
            assertTrue(snapshot(b).visibleEvents.isEmpty())
            // Dialog dismissal hides the IME asynchronously; wait before opening the next fixture.
            ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().none{view->
                androidx.core.view.ViewCompat.getRootWindowInsets(view)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true
            }}
        }
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"verification/entry-keyboard$suffix").also{it.mkdirs()}
        java.io.File(folder,"dimensions.csv").writeText(measurements.joinToString("\n")+"\n")
        java.io.File(folder,"field-dimensions.csv").writeText(fieldSizes.joinToString("\n")+"\n")
    }

    @Test fun notesDisclosureKeepsInputAcrossCollapseLanguageAndSave() {
        for(theme in listOf("magazine","journal","objects")){
            ui.runOnIdle{vm.appearance(a,theme=theme,language="zh");vm.editEntry(snapshot(a),snapshot(a).events.single())}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.theme==theme&&vm.entry.value!=null}
            if(ui.onAllNodesWithTag("field-备注").fetchSemanticsNodes().isEmpty())ui.onNodeWithTag("entry-notes-toggle").performScrollTo().performClick()
            ui.onNodeWithTag("field-备注").performScrollTo().performTextReplacement("保留的小记 $theme")
            ui.onNodeWithTag("entry-notes-toggle").performScrollTo().performClick()
            ui.onNodeWithTag("field-备注").assertDoesNotExist()
            ui.runOnIdle{vm.appearance(a,language="ja")}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.language=="ja"}
            ui.onNodeWithTag("entry-notes-toggle").performScrollTo().performClick()
            ui.onNodeWithTag("field-メモ").assertTextEquals("保留的小记 $theme")
            captureScreen("entry-notes",theme)
            tap("変更を保存")
            ui.waitUntil(10000){vm.entry.value==null}
            val saved=snapshot(a)
            assertEquals(event,saved.events.single().id)
            assertEquals("保留的小记 $theme",saved.events.single().note)
            assertEquals(300L,saved.events.single().amountMinor)
            assertEquals(9700L,saved.balance(saved.accounts.single()))
            ui.runOnIdle{vm.editEntry(saved,saved.events.single())}
            ui.waitUntil(10000){vm.entry.value!=null}
            ui.onNodeWithTag("field-メモ").performScrollTo().assertTextEquals("保留的小记 $theme")
            ui.runOnIdle{vm.discardEntry(a)}
            ui.waitUntil(10000){vm.entry.value==null}
        }
    }

    @Test fun homeCoverShowsSameBudgetInAllThemesAndLanguages() {
        runBlocking{repo.updateBook(repo.dao.book(a)!!.copy(budgetMinor=10000))}
        val sizes=mutableListOf<String>()
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")){
            ui.runOnIdle{vm.appearance(a,theme=theme,language=language);vm.navigate("home")}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.let{it.theme==theme&&it.language==language}==true}
            ui.onNodeWithTag("home-cover").performScrollTo().assertIsDisplayed()
            ui.onAllNodesWithText("9,700").onFirst().assertExists()
            captureScreen("home-cover","$theme-$language")
            val bounds=ui.onNodeWithTag("home-cover").fetchSemanticsNode().boundsInRoot
            val density=InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            sizes+="$theme,$language,${bounds.width/density},${bounds.height/density}"
            if(theme=="magazine")listOf("cover-caption","cover-title","cover-grid","cover-aside","cover-main-money","cover-footer","cover-footer-text","cover-prefix","cover-value","cover-available").forEach{tag->
                val rect=ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                sizes+="$theme,$language,$tag,${rect.top/density},${rect.width/density},${rect.height/density}"
                if(tag in listOf("cover-prefix","cover-value","cover-available"))ui.onNodeWithTag(tag).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult){get->
                    val text=mutableListOf<androidx.compose.ui.text.TextLayoutResult>();get(text)
                    sizes+="$theme,$language,$tag-baseline,${text.single().firstBaseline/density},${text.single().size.height/density}"
                }
            }
            assertEquals(10000L,snapshot(a).book.budgetMinor)
            assertEquals(300L,snapshot(a).events.single().baseMinor)
        }
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"verification/home-cover$suffix/dimensions.csv").writeText(sizes.joinToString("\n"))
    }
    private fun captureScreen(path:String,name:String) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-"+it}.orEmpty()
        val folder=java.io.File(instrumentation.targetContext.filesDir,"verification/"+path+suffix).also{it.mkdirs()}
        ui.waitForIdle()
        // Compose idleness does not wait for the Android dialog window fade.
        android.os.SystemClock.sleep(350)
        val bitmap=instrumentation.uiAutomation.takeScreenshot()
        try { java.io.File(folder,"$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)} } finally {bitmap.recycle()}
    }
    @Test fun consecutiveSaveRequestsCreateOnlyOneNewExpense() {
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==a}
        val before=snapshot(a)
        ui.runOnIdle {
            vm.startEntry(before)
            vm.changeEntry(a,EntryDraft(accountId=account,categoryId=category,amount="777"))
            vm.saveEntry(a)
            vm.saveEntry(a)
        }
        ui.waitUntil(10000){vm.entry.value==null && vm.route.value=="ledger"}
        val after=snapshot(a)
        assertEquals(2,after.events.size)
        assertEquals(1,after.events.count{it.amountMinor==777L})
        assertEquals(8923L,after.balance(after.accounts.single()))
        assertNull(after.draft)
        ui.onNodeWithTag("theme-toast").assertIsDisplayed()
        assertEquals(a,vm.notice.value?.bookId)
        assertEquals("记录已保存",vm.notice.value?.zh)
        ui.runOnIdle{vm.selectBook(b)}
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==b}
        ui.onNodeWithTag("theme-toast").assertDoesNotExist()
    }

    @Test fun revisionHistoryShowsOnlyRealChangesInEveryThemeAndLanguage(){
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==a}
        tap("喫茶と余白")
        ui.waitUntil(10000){ui.onAllNodesWithText("正在读取修改记录…").fetchSemanticsNodes().isEmpty()}
        ui.onNodeWithTag("revision-history").assertDoesNotExist()
        ui.onNodeWithText("×").performClick()
        val before=snapshot(a).events.single()
        val changedDate=java.time.LocalDate.parse(before.date).minusDays(1).toString()
        runBlocking{repo.saveEntry(a,EntryDraft(id=event,accountId=account,categoryId=category,amount="680",date=changedDate,merchant="新商户",note="新的备注"))}
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")){
            ui.runOnIdle{vm.appearance(a,theme=theme,language=language);vm.navigate("home")}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.let{it.theme==theme&&it.language==language}==true}
            tap("新商户")
            ui.waitUntil(10000){ui.onAllNodesWithTag("revision-history").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("revision-1").assertDoesNotExist()
            ui.onNodeWithTag("revision-2").performScrollTo().assertIsDisplayed()
            ui.onNodeWithText("¥300 → ¥680").assertExists()
            ui.onNodeWithText(before.date+" → "+changedDate).assertExists()
            ui.onNodeWithText("喫茶と余白 → 新商户").assertExists()
            ui.onNodeWithText("— → 新的备注").assertExists()
            ui.onAllNodesWithText(if(language=="zh")"本位币统计" else "基準通貨集計").assertCountEquals(0)
            captureScreen("revision-history","$theme-$language")
            ui.onNodeWithText("×").performClick()
            assertEquals(event,snapshot(a).events.single().id)
            assertEquals(2,snapshot(a).events.single().revision)
            assertEquals(9320L,snapshot(a).balance(snapshot(a).accounts.single()))
            assertTrue(snapshot(b).events.isEmpty())
        }
    }
    @Test fun unreadableRevisionShowsErrorAndCanRetryWithoutChangingEntry(){
        runBlocking{repo.saveEntry(a,EntryDraft(id=event,accountId=account,categoryId=category,amount="680",merchant="新商户"))}
        val saved=runBlocking{repo.dao.revisions(a,event).first().single{it.version==2}}
        db.openHelper.writableDatabase.execSQL("UPDATE revisions SET beforeJson=? WHERE bookId=? AND eventId=? AND version=2",arrayOf("{bad json",a,event))
        tap("新商户")
        ui.waitUntil(10000){ui.onAllNodesWithTag("revision-error").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("无法读取修改记录").performScrollTo().assertIsDisplayed()
        db.openHelper.writableDatabase.execSQL("UPDATE revisions SET beforeJson=? WHERE bookId=? AND eventId=? AND version=2",arrayOf(saved.beforeJson,a,event))
        tap("重新读取")
        ui.waitUntil(10000){ui.onAllNodesWithTag("revision-history").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("revision-2").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("¥300 → ¥680").assertExists()
        assertEquals(680L,snapshot(a).events.single().amountMinor)
        assertEquals(9320L,snapshot(a).balance(snapshot(a).accounts.single()))
        assertEquals(2,runBlocking{repo.dao.revisions(a,event).first()}.size)
    }
    @Test fun booksPageShowsRealCountsAndOwnCurrencyAndEditsOtherBook(){
        runBlocking{repo.updateBook(repo.dao.book(a)!!.copy(budgetMinor=52500));repo.updateBook(repo.dao.book(b)!!.copy(budgetMinor=12345))}
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")){
            ui.runOnIdle{vm.appearance(a,theme=theme,language=language);vm.navigate("home")}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.let{it.id==a&&it.theme==theme&&it.language==language}==true}
            ui.onNodeWithTag("header-book").performScrollTo().performClick()
            ui.waitUntil(10000){vm.route.value=="books"}
            ui.onNodeWithTag("books-page").assertExists()
            ui.onNodeWithTag("book-choice-$a").performScrollTo().assertIsSelected()
            val instrumentation=InstrumentationRegistry.getInstrumentation()
            val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
            val folder=java.io.File(instrumentation.targetContext.filesDir,"verification/books$suffix").also{it.mkdirs()}
            val density=instrumentation.targetContext.resources.displayMetrics.density
            val bounds=ui.onNodeWithTag("book-choice-$a").fetchSemanticsNode().boundsInRoot
            java.io.File(folder,"dimensions.csv").let{file->
                if(theme=="magazine"&&language=="zh")file.writeText("theme,language,width,height\n")
                file.appendText("$theme,$language,${bounds.width/density},${bounds.height/density}\n")
            }
            ui.onNodeWithText("1 "+if(language=="zh")"个账户 · 月预算 ¥52,500" else "口座 · 月予算 ¥52,500").assertExists()
            ui.onNodeWithTag("book-choice-$b").performScrollTo().assertIsNotSelected()
            ui.onNodeWithText("0 "+if(language=="zh")"个账户 · 月预算 CN¥123.45" else "口座 · 月予算 CN¥123.45").assertExists()
            captureScreen("books","$theme-$language")
            ui.onNodeWithTag("book-edit-$b").performScrollTo().performClick()
            val nameTag=if(language=="zh")"field-账本名称" else "field-帳簿名"
            ui.onNodeWithTag(nameTag).performTextReplacement("別の生活 $theme $language")
            tap(if(language=="zh")"保存账本" else "帳簿を保存")
            ui.waitUntil(10000){snapshot(b).book.name=="別の生活 $theme $language"}
            assertEquals(a,(vm.state.value as AppState.Ready).snapshot?.book?.id)
            assertEquals("journal",snapshot(b).book.theme);assertEquals("ja",snapshot(b).book.language)
            ui.onNodeWithTag("book-edit-$a").performScrollTo().performClick()
            ui.onNodeWithTag(if(language=="zh")"select-trigger-统计本位币" else "select-trigger-基準通貨").assertDoesNotExist()
            ui.onNodeWithText("×").performClick()
            ui.onNodeWithTag("book-choice-$b").performScrollTo().performClick()
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==b&&vm.route.value=="home"}
            assertEquals(300L,snapshot(a).events.single().amountMinor);assertEquals(9700L,snapshot(a).balance(snapshot(a).accounts.single()))
            ui.runOnIdle{vm.selectBook(a)}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==a}
        }
    }
    @Test fun repeatedFormSaveCreatesOneAccountCategoryAndBook(){
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==a}
        ui.runOnIdle{vm.navigate("accounts")}
        tap("＋ 添加账户")
        ui.onNodeWithTag("field-账户名称").performTextReplacement("一次账户")
        submitTwiceBehindPendingWrite("保存账户信息"){ui.onNodeWithTag("field-账户名称").performTextReplacement("提交后的新名字")}
        assertEquals(1,snapshot(a).accounts.count{it.name=="一次账户"})
        ui.runOnIdle{vm.navigate("categories")}
        ui.onAllNodesWithText("＋ 添加一级分类").onFirst().performScrollTo().performClick()
        ui.onNodeWithTag("field-分类名称").performTextReplacement("一次分类")
        submitTwiceBehindPendingWrite("保存分类")
        assertEquals(1,snapshot(a).categories.count{it.name=="一次分类"})
        ui.onNodeWithTag("header-book").performScrollTo().performClick()
        tap("＋ 创建独立账本")
        ui.onNodeWithTag("field-账本名称").performTextReplacement("一次账本")
        submitTwiceBehindPendingWrite("保存账本")
        assertEquals(1,runBlocking{repo.books.first()}.count{it.name=="一次账本"})
        assertEquals(3,runBlocking{repo.books.first()}.size)
        assertEquals(9700L,snapshot(a).balance(snapshot(a).accounts.first{it.id==account}))
        assertEquals(300L,snapshot(a).events.single().amountMinor)
    }
    private fun submitTwiceBehindPendingWrite(label:String,changeAfterSubmit:()->Unit={}){
        val gate=kotlinx.coroutines.CompletableDeferred<Unit>()
        val settled=kotlinx.coroutines.CompletableDeferred<Unit>()
        ui.runOnIdle{vm.execute{gate.await()}}
        ui.waitUntil(10000){vm.busy.value}
        ui.onNodeWithText(label).performScrollTo()
        val click=ui.onNodeWithText(label).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        ui.runOnIdle{click();click();vm.execute(onComplete={settled.complete(Unit)}){}}
        changeAfterSubmit()
        gate.complete(Unit)
        ui.waitUntil(10000){settled.isCompleted}
        ui.waitForIdle()
    }
    @Test fun accountSaveFailureCanBeCorrectedWithoutClosingForm(){
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==a}
        ui.runOnIdle{vm.navigate("accounts")}
        tap("＋ 添加账户")
        tap("保存账户信息")
        ui.waitUntil(10000){vm.error.value!=null&&!vm.busy.value}
        assertEquals(1,snapshot(a).accounts.size)
        tap("知道了")
        ui.onNodeWithTag("field-账户名称").performTextReplacement("补正账户")
        ui.onNodeWithTag("field-期初余额").performTextReplacement("500")
        tap("保存账户信息")
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.accounts?.any{it.name=="补正账户"}==true&&!vm.busy.value}
        val saved=snapshot(a)
        assertEquals(2,saved.accounts.size)
        assertEquals(500L,saved.balance(saved.accounts.single{it.name=="补正账户"}))
        assertEquals(300L,saved.events.single().amountMinor)
        assertNull(vm.error.value)
    }
    @Test fun failedSaveKeepsDraftAndDoesNotShowSuccessNotice(){
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==a}
        val original=snapshot(a)
        ui.runOnIdle{
            vm.startEntry(original)
            vm.changeEntry(a,EntryDraft(accountId=account,categoryId=category,amount="invalid"))
            vm.saveEntry(a)
        }
        ui.waitUntil(10000){vm.error.value!=null&&!vm.busy.value}
        assertNull(vm.notice.value)
        ui.onNodeWithTag("theme-toast").assertDoesNotExist()
        assertEquals("invalid",vm.entry.value?.amount)
        assertEquals(original.events,snapshot(a).events)
        assertEquals(9700L,snapshot(a).balance(original.accounts.single()))
    }

}
