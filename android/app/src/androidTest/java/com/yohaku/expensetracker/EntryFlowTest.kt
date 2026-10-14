package com.yohaku.expensetracker
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yohaku.expensetracker.data.db.YohakuDatabase
import com.yohaku.expensetracker.data.repository.*
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
        ui.onNodeWithTag("header-language").performScrollTo().performClick()
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
        waitFor("账户实际入账金额 · JPY")
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
}
