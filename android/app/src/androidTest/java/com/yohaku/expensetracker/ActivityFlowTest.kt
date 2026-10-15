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
class ActivityFlowTest {
    @get:Rule val ui=createComposeRule()
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var vm:AppViewModel
    @Before fun open() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        repo=LedgerRepository(db,Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"),ZoneOffset.UTC))
        vm=AppViewModel(repo,AppPreferences(context));ui.setContent{NativeApp(vm)}
    }
    @After fun close(){vm.viewModelScope.cancel();db.close()}
    private fun tap(text:String) {
        ui.waitUntil(10000){ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()&&!vm.busy.value}
        ui.onNodeWithText(text).performScrollTo().performClick()
    }
    private fun shot(name:String,sheet:Boolean,cover:Boolean=false,reset:Boolean=true) {
        if(reset)ui.onNodeWithTag(if(sheet)"sheet-scroll"else"page-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,-100000f)}
        if(cover)ui.onNodeWithTag("activity-cover").performScrollTo()
        ui.waitForIdle()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.getExternalFilesDir(null),"phase-b-p11/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use{ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        if(name.contains("friend")||name.endsWith("-reserve-keyboard")) {
            val screen=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(file.parentFile,"$name-screen.png").outputStream().use{screen.compress(Bitmap.CompressFormat.PNG,100,it)}
            screen.recycle()
        }
    }
    private fun clearHeader(node:SemanticsNodeInteraction):SemanticsNodeInteraction {
        node.performScrollTo();ui.waitForIdle()
        val top=node.getUnclippedBoundsInRoot().top.value
        val header=ui.onNodeWithTag("sheet-header",useUnmergedTree=true).getUnclippedBoundsInRoot().bottom.value
        if(top<header+8) {
            val density=InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            ui.onNodeWithTag("sheet-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,-(header+8-top)*density)}
            ui.waitForIdle()
        }
        return node
    }
    private fun imeVisible()=android.view.inspector.WindowInspector.getGlobalWindowViews().any{view->
        androidx.core.view.ViewCompat.getRootWindowInsets(view)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true
    }
    @Test fun activitiesModeReserveAndOtherPayerInEveryThemeAndLanguage() {
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val values=runBlocking {
                val book=repo.createBook("活動 $theme $language",Currency.JPY,startDate="2026-10-01",budgetMinor=100000,theme=theme,language=language)
                repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
                val account=repo.saveAccount(book,"银行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,200000),"2026-10-01")
                val category=repo.saveCategory(book,"生活",EntryKind.EXPENSE,null)
                repo.setActivityMode(book,true)
                val activity=repo.saveActivity(book,"秋の旅","2026-10-01","2026-10-31",Money(Currency.JPY,40000))
                val friend=repo.saveFriend(book,"朋友A");repo.addParticipant(book,activity,friend)
                repo.saveEntry(book,EntryDraft(accountId=account,categoryId=category,amount="6000",date="2026-10-14",merchant="晚餐",activityId=activity,treatEnabled=true,treatFriends=listOf(friend)))
                vm.preferences.select(book);book to activity
            }
            val (book,activity)=values
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book}
            ui.runOnIdle{vm.navigate("life")}
            shot("$theme-$language-list",false)
            tap(text("打开活动","シーンを開く"))
            shot("$theme-$language-cover",false,true)
            tap(text("进入记录模式","記録モードを開始"))
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.features?.activeActivityId==activity&&!vm.busy.value}
            shot("$theme-$language-activity",false)
            tap(text("＋ 预留资金","＋ 資金を確保"))
            val keyboard=InstrumentationRegistry.getArguments().getString("phaseBKeyboard")=="true"
            val reserveName=ui.onNodeWithTag("field-"+text("预留名称","確保の名前"))
            if(keyboard){clearHeader(reserveName).performTouchInput{click()};ui.waitUntil(10000){imeVisible()}}
            reserveName.performScrollTo().performTextReplacement("住宿")
            ui.onNodeWithTag("field-"+text("本位币金额","基準通貨の金額")+" · JPY").performScrollTo().performTextReplacement("20000")
            if(keyboard) {
                val preview=clearHeader(ui.onNodeWithText(text("预览影响","影響を確認")))
                val viewport=ui.onNodeWithTag("sheet-scroll").getUnclippedBoundsInRoot()
                val button=preview.getUnclippedBoundsInRoot()
                assertTrue("Preview remains above the real keyboard",button.bottom<=viewport.bottom)
                val view=android.view.inspector.WindowInspector.getGlobalWindowViews().first{androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}
                val status=androidx.core.view.ViewCompat.getRootWindowInsets(view)!!.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top/view.resources.displayMetrics.density
                assertTrue("Sheet title stays below the Android status bar",ui.onNodeWithTag("sheet-header",useUnmergedTree=true).getUnclippedBoundsInRoot().top.value>=status)
                assertTrue("Preview clears the sticky sheet header",button.top>=ui.onNodeWithTag("sheet-header",useUnmergedTree=true).getUnclippedBoundsInRoot().bottom)
                shot("$theme-$language-reserve-keyboard",true,reset=false)
                clearHeader(preview).performTouchInput{click()}
            }else tap(text("预览影响","影響を確認"))
            tap(text("确认","確定"))
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.reserves?.size==1&&!vm.busy.value}
            tap(text("别人付款 · 记录互请","友達の支払・ごちそうを記録"))
            ui.onNodeWithTag("field-"+text("这次是什么","何の支払？")).performScrollTo().performTextReplacement("午餐")
            ui.onNodeWithTag("field-"+text("整单金额","全体額")).performScrollTo().performTextReplacement("3000")
            tap(text("预览份额","取り分を確認"));shot("$theme-$language-portions",true)
            tap(text("确认互请记录","ごちそう記録を確定"))
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.treats?.size==2&&!vm.busy.value}
            tap(text("查看与这位朋友的明细","この友達との明細を見る"))
            ui.onNode(hasText(text("你多替对方承担","自分が多く負担")) and hasAnyAncestor(hasTestTag("sheet-scroll"))).performScrollTo().assertExists()
            ui.onNodeWithTag("lead-amount").assertTextEquals(if(theme=="journal")"¥1,500 ↗"else"¥1,500")
            shot("$theme-$language-friend",true)
            ui.onNode(hasText(text("对方替我承担：","相手が自分に：")+"¥1,500") and hasAnyAncestor(hasTestTag("sheet-scroll"))).performScrollTo().assertExists()
            ui.onNodeWithTag("sheet-close").performClick()
            val s=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(1,s.events.size);assertEquals(194000L,s.balance(s.accounts.single()));assertEquals(1500L,s.friendBalances(activity).single().difference)
            assertEquals(20000L,s.budgetProjection("2026-10").activityReserveMinor)
            ui.runOnIdle{vm.navigate("friends")}
            shot("$theme-$language-friend-directory",false)
            ui.onNodeWithTag("friend-roster-"+s.friends.single().id).performScrollTo().performClick()
            tap("秋の旅 · 2026-10-01 — 2026-10-31")
            ui.onNodeWithTag("lead-amount").assertTextEquals(if(theme=="journal")"¥1,500 ↗"else"¥1,500")
            ui.onNodeWithTag("sheet-close").performClick()
            ui.runOnIdle{vm.editEntry(s,s.events.single())}
            ui.waitUntil(10000){vm.entry.value?.id==s.events.single().id}
            assertTrue(vm.entry.value!!.treatEnabled);assertEquals(s.allocations.filter{it.treatId==s.treats.first{t->t.eventId!=null}.id}.mapNotNull{it.friendId},vm.entry.value!!.treatFriends)
            ui.onNodeWithText(text("本人付款 · 活动内互请","自分の支払・シーンのごちそう")).performScrollTo().assertExists()
            ui.onNodeWithTag("entry-save-button").performScrollTo().performClick()
            tap(text("确认并保存","確認して保存"))
            ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value}
            val edited=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(1,edited.events.size);assertEquals(1,edited.postings.size);assertEquals(1500L,edited.friendBalances(activity).single().difference)
            ui.runOnIdle{vm.navigate("life")}
            tap(text("退出记录模式","記録モードを終了"))
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.features?.activeActivityId==null&&!vm.busy.value}
        }
    }
    @Test fun friendShortcutsPreselectSecondFriendProtectDraftAndSaveOnlyOnce() {
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val ids=runBlocking {
                val book=repo.createBook("快捷 $theme $language",Currency.JPY,startDate="2026-09-01",theme=theme,language=language)
                repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
                val account=repo.saveAccount(book,"银行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,100000),"2026-09-01")
                val category=repo.saveCategory(book,"餐饮",EntryKind.EXPENSE,null)
                repo.setActivityMode(book,true)
                val activity=repo.saveActivity(book,"九月旅途","2026-09-01","2026-09-30",Money(Currency.JPY,40000))
                val first=repo.saveFriend(book,"朋友A");val second=repo.saveFriend(book,"朋友B")
                repo.addParticipant(book,activity,first);repo.addParticipant(book,activity,second)
                repo.saveEntry(book,EntryDraft(accountId=account,categoryId=category,amount="6000",date="2026-09-14",activityId=activity,treatEnabled=true,treatFriends=listOf(first,second)))
                vm.preferences.select(book);listOf(book,account,category,activity,first,second)
            }
            val book=ids[0];val account=ids[1];val category=ids[2];val activity=ids[3];val first=ids[4];val second=ids[5]
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            fun openPair(){ui.runOnIdle{vm.openActivityFriend.value=activity to second;vm.navigate("life")};ui.waitUntil(10000){ui.onAllNodesWithTag("sheet-close").fetchSemanticsNodes().isNotEmpty()}}
            openPair();shot("$theme-$language-friend-shortcut-pair",true);tap(text("别人请我","ごちそうになった"))
            ui.onNodeWithTag("select-trigger-"+text("付款朋友","支払った友達")).assertTextContains("朋友B",substring=true)
            ui.onNodeWithTag("field-"+text("这次是什么","何の支払？")).performScrollTo().performTextReplacement("旅途午餐")
            ui.onNodeWithTag("field-"+text("整单金额","全体額")).performScrollTo().performTextReplacement("2000")
            shot("$theme-$language-friend-shortcut-other",true)
            tap(text("预览份额","取り分を確認"));tap(text("确认互请记录","ごちそう記録を確定"))
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.treats?.size==2&&!vm.busy.value}
            val old=EntryDraft(accountId=account,categoryId=category,amount="123",note="未保存旧记录")
            runBlocking{repo.saveDraft(book,old)}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.draft?.mediaSession==old.mediaSession}
            openPair();tap(text("我请客","ごちそうした"));tap(text("继续这笔草稿","下書きを続ける"))
            assertEquals(old.mediaSession,vm.entry.value!!.mediaSession);assertEquals("123",vm.entry.value!!.amount);assertNull(vm.entry.value!!.activityId)
            ui.runOnIdle{vm.leaveEntry(book,true,"life")};ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value}
            openPair();tap(text("我请客","ごちそうした"));tap(text("放弃草稿，记新的一笔","下書きを破棄して新しく記録"))
            ui.waitUntil(10000){vm.entry.value?.activityId==activity&&!vm.busy.value}
            assertTrue(vm.entry.value!!.treatEnabled);assertEquals(listOf(second),vm.entry.value!!.treatFriends)
            assertEquals("2026-09-30",vm.entry.value!!.date)
            ui.onNode(hasText("餐饮") and hasClickAction() and hasAnyAncestor(hasTestTag("category-group-$category"))).performScrollTo().performClick()
            val keyboard=InstrumentationRegistry.getArguments().getString("phaseBKeyboard")=="true"
            val amount=ui.onNodeWithTag("field-"+text("金额","金額")+" · JPY")
            if(keyboard){clearHeader(amount).performTouchInput{click()};ui.waitUntil(10000){imeVisible()}}
            amount.performScrollTo().performTextReplacement("10000")
            val save=clearHeader(ui.onNodeWithTag("entry-save-button"))
            if(keyboard) {
                assertTrue("Save remains above the real keyboard",save.getUnclippedBoundsInRoot().bottom<=ui.onNodeWithTag("sheet-scroll").getUnclippedBoundsInRoot().bottom)
                shot("$theme-$language-friend-shortcut-me-keyboard",true,reset=false)
                save.performTouchInput{click()}
            }else save.performClick()
            tap(text("确认并保存","確認して保存"))
            ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value}
            val saved=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(2,saved.events.size);assertEquals(2,saved.postings.size);assertEquals(84000L,saved.balance(saved.accounts.single()))
            assertEquals(2000L,saved.friendBalances(activity).first{it.friend.id==first}.difference)
            val pair=saved.friendBalances(activity).first{it.friend.id==second}
            assertEquals(7000L,pair.iGive);assertEquals(1000L,pair.theyGive);assertEquals(6000L,pair.difference)
            assertEquals("2026-09-30",saved.treats.single{it.payerFriendId==second}.date)
            assertEquals("2026-09-30",saved.events.single{it.amountMinor==10000L}.date)
        }
    }

}
