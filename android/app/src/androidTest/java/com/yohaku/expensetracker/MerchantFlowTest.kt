package com.yohaku.expensetracker

import android.graphics.Bitmap
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
class MerchantFlowTest {
    @get:Rule val ui=createComposeRule()
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var vm:AppViewModel
    @Before fun open(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        repo=LedgerRepository(db,Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"),ZoneOffset.UTC))
        vm=AppViewModel(repo,AppPreferences(context), testReferenceRates(AppPreferences(context),repo.clock));ui.setContent{NativeApp(vm)}
    }
    @After fun close(){vm.viewModelScope.cancel();db.close()}
    private fun tap(text:String){ui.waitUntil(10000){!vm.busy.value&&ui.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()};ui.onNode(hasText(text) and hasClickAction()).performScrollTo().performClick()}
    private fun clearHeader(node:SemanticsNodeInteraction):SemanticsNodeInteraction {
        ui.waitUntil(10000){!vm.busy.value}
        node.performScrollTo();ui.waitForIdle()
        val density=InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val header=ui.onNodeWithTag("sheet-header").getUnclippedBoundsInRoot().bottom.value
        val top=node.getUnclippedBoundsInRoot().top.value
        if(top<header+8)ui.onNodeWithTag("sheet-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,-(header+8-top)*density)}
        ui.waitForIdle()
        val bottom=node.getUnclippedBoundsInRoot().bottom.value
        val viewport=ui.onNodeWithTag("sheet-scroll").getUnclippedBoundsInRoot().bottom.value
        if(bottom>viewport)ui.onNodeWithTag("sheet-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,(bottom-viewport+8)*density)}
        ui.waitForIdle();return node
    }
    private fun imeVisible()=android.view.inspector.WindowInspector.getGlobalWindowViews().any{view->androidx.core.view.ViewCompat.getRootWindowInsets(view)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}
    private fun shot(name:String){
        if (!visualEnabled()) return
        ui.waitForIdle();val automation=InstrumentationRegistry.getInstrumentation().uiAutomation;automation.waitForIdle(100,5000)
        val bitmap=automation.takeScreenshot();val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.getExternalFilesDir(null),"phase-b-p12-merchants/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun legacyHistoryLocationsAndMerchantShortcutKeepOriginalFactsAndProtectDrafts(){
        for((theme,language) in testLooks(this@MerchantFlowTest)) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val ids=runBlocking {
                val book=repo.createBook("お店 $theme $language",Currency.JPY,startDate="2026-10-01",theme=theme,language=language)
                repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
                val account=repo.saveAccount(book,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,200000),"2026-10-01")
                val category=repo.saveCategory(book,"咖啡",EntryKind.EXPENSE,null)
                val event=repo.saveEntry(book,EntryDraft(accountId=account,categoryId=category,amount="1200",date="2026-10-02",merchant="原名店"))
                val refund=repo.saveRefund(book,RefundInput(event,Money(Currency.JPY,100),account,Money(Currency.JPY,100),Money(Currency.JPY,100),"2026-10-03",true))
                val merchant=repo.saveMerchant(book,"原名店")
                repo.saveMerchantLocation(book,merchant,"入口",35.66130,139.66790)
                vm.preferences.select(book);listOf(book,account,category,event,refund,merchant)
            }
            val book=ids[0];val account=ids[1];val category=ids[2];val event=ids[3];val refund=ids[4];val merchant=ids[5]
            println("Merchant UI: $theme $language")
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book}
            ui.runOnIdle{vm.navigate("merchants")}
            ui.onNodeWithTag("merchant-card-$merchant").performScrollTo();shot("$theme-$language-catalog")
            ui.onNodeWithTag("merchant-open-$merchant").performClick()
            ui.onNodeWithTag("event-$event").assertExists()
            assertEquals(1,ui.onAllNodesWithTag("event-$refund").fetchSemanticsNodes().size)
            tap(text("编辑地点","場所を編集"))
            val keyboard=InstrumentationRegistry.getArguments().getString("phaseBKeyboard")=="true"
            val name=clearHeader(ui.onNodeWithTag("field-"+text("地点名称","場所の名前")))
            if(keyboard){name.performTouchInput{click()};ui.waitUntil(10000){imeVisible()}}
            name.performTextReplacement("北门")
            ui.onNodeWithTag("place-picker-map").assertExists()
            // The map sits in a scrolling sheet; a drag must move the map, not the sheet.
            fun pin()=ui.onNodeWithTag("place-picker-center").fetchSemanticsNode().config[PlacePickerCoordinates]
            ui.waitUntil(10000){kotlin.math.abs(pin().first-35.66130)<0.0005}
            val before=pin();ui.onNodeWithTag("place-picker-map").performScrollTo();ui.waitForIdle()
            // A real system touch: the map is a native View, so drive it through the input pipeline.
            val box=ui.onNodeWithTag("place-picker-map").fetchSemanticsNode().let{it.positionOnScreen to it.size}
            val x=(box.first.x+box.second.width/2).toInt();val y=(box.first.y+box.second.height/2).toInt()
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input swipe $x $y ${x-90} ${y+60} 500").close()
            ui.waitUntil(10000){pin()!=before}
            if(visualEnabled()) {
                ui.runOnUiThread{android.view.inspector.WindowInspector.getGlobalWindowViews().forEach{androidx.core.view.ViewCompat.getWindowInsetsController(it)?.hide(androidx.core.view.WindowInsetsCompat.Type.ime())}}
                ui.waitUntil(5000){!imeVisible()};Thread.sleep(6000)
                ui.onNodeWithTag("place-picker-locate").performScrollTo();shot("$theme-$language-place-picker")
            }
            ui.onNodeWithTag("field-"+text("纬度 / 可留空","緯度・空欄可")).assertDoesNotExist()
            ui.waitUntil(10000){ui.onAllNodes(hasTestTag("merchant-location-save") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
            if(keyboard)ui.waitUntil(10000){imeVisible()}
            val save=clearHeader(ui.onNodeWithTag("merchant-location-save"))
            if(keyboard){assertTrue(save.getUnclippedBoundsInRoot().bottom<=ui.onNodeWithTag("sheet-scroll").getUnclippedBoundsInRoot().bottom);shot("$theme-$language-location-keyboard");save.performTouchInput{click()}}else save.performClick()
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.merchantLocations?.any{it.name=="北门"}==true&&!vm.busy.value}
            val savedPlace=runBlocking{repo.snapshot(repo.dao.book(book)!!).first().merchantLocations.single()}
            assertNotNull(savedPlace.latitude);assertNotNull(savedPlace.longitude)
            // Legacy name-only places remain readable; new UI saves always include a map center.
            runBlocking{repo.saveMerchantLocation(book,merchant,"只保存名字",null,null)}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.merchantLocations?.size==2}
            val old=EntryDraft(accountId=account,categoryId=category,amount="77",merchant="旧草稿",date="2026-10-14")
            runBlocking{repo.saveDraft(book,old)}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.draft?.mediaSession==old.mediaSession}
            tap(text("全部商户","すべてのお店"))
            ui.onNodeWithTag("merchant-entry-$merchant").performScrollTo().performClick()
            tap(text("继续这笔草稿","下書きを続ける"))
            assertEquals(old.mediaSession,vm.entry.value!!.mediaSession);assertEquals("旧草稿",vm.entry.value!!.merchant);assertNull(vm.entry.value!!.merchantId)
            ui.runOnIdle{vm.leaveEntry(book,true,"merchants")};ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value}
            ui.onNodeWithTag("merchant-entry-$merchant").performScrollTo().performClick()
            tap(text("放弃草稿，记新的一笔","下書きを破棄して新しく記録"))
            ui.waitUntil(10000){vm.entry.value?.merchantId==merchant&&!vm.busy.value}
            assertEquals("原名店",vm.entry.value!!.merchant)
            ui.onNode(hasText("咖啡") and hasClickAction() and hasAnyAncestor(hasTestTag("category-group-$category"))).performScrollTo().performClick()
            ui.onNodeWithTag("field-"+text("金额","金額")+" · JPY").performScrollTo().performTextReplacement("200")
            ui.entryAction("entry-save-button").assertIsEnabled().performClick()
            ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value&&(vm.state.value as? AppState.Ready)?.snapshot?.events?.size==3}
            assertEquals("ledger",vm.route.value)
            ui.runOnIdle{vm.navigate("merchants")}
            ui.onNodeWithTag("merchant-open-$merchant").performScrollTo().performClick()
            tap(text("编辑原名与备注","元の名前・メモを編集"))
            ui.onNodeWithTag("field-"+text("商户原名","お店の元の名前")).performScrollTo().performTextReplacement("新资料名")
            if(keyboard)ui.waitUntil(10000){imeVisible()}
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(100,5000)
            val merchantSave=clearHeader(ui.onNodeWithTag("merchant-save"));merchantSave.assertIsEnabled()
            shot("$theme-$language-merchant-before-bounds")
            println("Merchant bounds: ${merchantSave.getUnclippedBoundsInRoot()} header=${ui.onNodeWithTag("sheet-header").getUnclippedBoundsInRoot()} viewport=${ui.onNodeWithTag("sheet-scroll").getUnclippedBoundsInRoot()}")
            assertTrue("Merchant save is within the visible body",merchantSave.getUnclippedBoundsInRoot().bottom<=ui.onNodeWithTag("sheet-scroll").getUnclippedBoundsInRoot().bottom)
            shot("$theme-$language-merchant-name-keyboard")
            merchantSave.performTouchInput{click()}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.merchants?.single()?.name=="新资料名"&&!vm.busy.value}
            val after=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(3,after.events.size);assertEquals(198700L,after.balance(after.accounts.single()))
            assertEquals("原名店",after.events.first{it.id==event}.merchant)
            assertEquals(1,after.eventMerchants.size);assertEquals(merchant,after.eventMerchants.single().merchantId)
            assertTrue(after.events.filter{it.kind==EntryKind.EXPENSE.name}.all{it.merchant=="原名店"})
            assertEquals(2,after.merchantLocations.size);assertEquals(merchant,after.merchantCandidates(35.66130,139.66790).single().merchant.id)
            assertNull(after.merchantLocations.first{it.name=="只保存名字"}.latitude)
            val editingDraft=after.editingEntryDraft(after.events.first{it.id!=event&&it.kind==EntryKind.EXPENSE.name})
            ui.runOnIdle{vm.entry.value=editingDraft}
            ui.onNodeWithTag("merchant-delete").performScrollTo().performClick()
            ui.onNodeWithText(text("关联的 1 条记录会保留原来的商户文字，只是不再关联这个商户。","関連する 1 件の記録には元のお店名を残し、このお店との関連付けだけを解除します。")).assertExists()
            shot("$theme-$language-delete-confirm")
            clearHeader(ui.onNodeWithTag("merchant-delete-confirm")).assertIsEnabled().performClick()
            ui.waitUntil(10000){!vm.busy.value&&(vm.state.value as? AppState.Ready)?.snapshot?.merchants?.isEmpty()==true}
            ui.onNodeWithTag("merchant-card-$merchant").assertDoesNotExist()
            ui.onNodeWithTag("merchant-add").assertExists()
            ui.onNodeWithTag("merchant-delete-confirm").assertDoesNotExist()
            assertNull(vm.entry.value!!.merchantId);assertEquals(editingDraft.merchant,vm.entry.value!!.merchant)
            shot("$theme-$language-deleted-list")
            ui.runOnIdle{vm.entry.value=null}

        }
    }
}
