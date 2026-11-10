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
import com.yohaku.expensetracker.domain.merchant.MerchantOperationType
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
    private fun expand(id:String) {
        ui.waitForIdle()
        if(ui.onAllNodesWithTag("merchant-open-$id").fetchSemanticsNodes().isEmpty())ui.onNodeWithTag("merchant-card-$id").performScrollTo().performClick()
    }
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
    private fun waitForSheet(title:String,editorAbsent:Boolean=false) {
        ui.waitUntil(10000){
            ui.onAllNodesWithTag("theme-sheet").fetchSemanticsNodes().size==1&&
                ui.onAllNodes(hasTestTag("sheet-title") and hasText(title)).fetchSemanticsNodes().size==1&&
                (!editorAbsent||ui.onAllNodesWithTag("merchant-save").fetchSemanticsNodes().isEmpty())
        }
        ui.onNodeWithTag("sheet-title").assertIsDisplayed()
    }
    private fun shot(name:String){
        if (!visualEnabled()) return
        ui.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val automation=instrumentation.uiAutomation
        // Compose/accessibility idleness does not include WindowManager fades or IME movement.
        // Wait for rendered pixels, not a fixed delay; exclude the system clock/navigation bar.
        var previous:Bitmap?=null
        var frame:Bitmap?=null
        var identicalFrames=0
        var samples=0
        try {
            ui.waitUntil(10000){
                val next=automation.takeScreenshot()
                val inset=(48*instrumentation.targetContext.resources.displayMetrics.density).toInt()
                val content=Bitmap.createBitmap(next,0,inset,next.width,next.height-2*inset)
                identicalFrames=if(previous?.sameAs(content)==true)identicalFrames+1 else 0
                previous?.recycle();previous=content
                frame?.recycle();frame=next;samples++
                identicalFrames>=2
            }
            println("Merchant screenshot $name settled after $samples frames")
            val context=instrumentation.targetContext
            val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix").orEmpty()
            val file=File(context.getExternalFilesDir(null),"phase-b-p12-merchants$suffix/$name.png");file.parentFile!!.mkdirs()
            file.outputStream().use{requireNotNull(frame).compress(Bitmap.CompressFormat.PNG,100,it)}
        }finally { previous?.recycle();frame?.recycle() }
    }
    @Test fun ambiguousOriginalNamesRemainVisibleWithoutInferredOwnership() {
        for((theme,language) in testLooks(this@MerchantFlowTest)) {
            val ids=runBlocking {
                val book=repo.createBook("历史重名 $theme $language",Currency.JPY,startDate="2026-10-01",theme=theme,language=language)
                repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
                val account=repo.saveAccount(book,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,1000),"2026-10-01")
                val category=repo.saveCategory(book,"咖啡",EntryKind.EXPENSE,null)
                repo.dao.putMerchant(MerchantEntity(book,"one","Cafe",note="北门"))
                repo.dao.putMerchant(MerchantEntity(book,"two","ＣＡＦＥ",note="南门",active=false))
                val event=repo.saveEntry(book,EntryDraft(accountId=account,categoryId=category,amount="100",merchant="cafe"))
                vm.preferences.select(book);book to event
            }
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==ids.first}
            ui.runOnIdle{vm.navigate("merchants")}
            ui.onNodeWithTag("merchant-card-one").assertTextContains(if(language=="ja")"0 件"else"0 笔",substring=true)
            ui.onNodeWithTag("merchant-card-two").assertTextContains(if(language=="ja")"0 件"else"0 笔",substring=true)
            ui.onNodeWithTag("merchant-card-recorded-${ids.second}").performScrollTo().assertIsDisplayed()
            shot("$theme-$language-ambiguous-original")
        }
    }

    @Test fun compactDirectoryAdoptionAndBothMergeEntrances() {
        for((theme,language) in testLooks(this@MerchantFlowTest)) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val ids=runBlocking {
                val book=repo.createBook("商户 $theme $language",Currency.JPY,startDate="2026-10-01",theme=theme,language=language)
                repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
                val account=repo.saveAccount(book,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,100000),"2026-10-01")
                val category=repo.saveCategory(book,"食事",EntryKind.EXPENSE,null)
                val source=repo.saveMerchant(book,"很长很长的商户名称・駅前の小さな喫茶と手作りパンのお店","常去，靠窗")
                val target=repo.saveMerchant(book,"Target cafe",active=false)
                val third=repo.saveMerchant(book,"街角书店")
                repo.saveMerchant(book,"无地点的小店")
                repo.saveMerchantLocation(book,source,"北门",35.0,139.0);repo.saveMerchantLocation(book,source,"二楼",35.0,139.0)
                val event=repo.saveEntry(book,EntryDraft(accountId=account,categoryId=category,amount="100",date="2026-10-02",merchant="旧名",merchantId=source))
                val adopted=repo.saveEntry(book,EntryDraft(accountId=account,categoryId=category,amount="200",date="2026-10-03",merchant="新名字"))
                repo.saveEntry(book,EntryDraft(accountId=account,categoryId=category,amount="50",date="2026-10-04",merchant="另一个记录里的名字"))
                vm.preferences.select(book);listOf(book,source,target,third,event,adopted)
            }
            val book=ids[0];val source=ids[1];val target=ids[2];val third=ids[3];val event=ids[4];val adopted=ids[5]
            fun snapshot()=(vm.state.value as AppState.Ready).snapshot!!
            fun tag(name:String)=ui.onNodeWithTag(name)
            fun sheetTap(name:String)=clearHeader(tag(name)).performClick()
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book}
            ui.runOnIdle{vm.navigate("merchants")}
            tag("merchant-card-$source").performScrollTo();shot("$theme-$language-compact-list")
            tag("merchant-entry-$source").assertDoesNotExist()
            expand(source);tag("merchant-expanded-$source").performScrollTo();shot("$theme-$language-expanded")
            tag("merchant-card-$source").performScrollTo().performClick();tag("merchant-entry-$source").assertDoesNotExist()
            expand(target);expand(source);tag("merchant-expanded-$target").assertDoesNotExist()
            tag("merchant-open-$source").performScrollTo().performClick()
            tap(text("编辑名称与备注","名前・メモを編集"))
            val field="field-"+text("商户名称","お店の名前")
            tag("merchant-name-impact").assertTextEquals(text("不影响其他记录","ほかの記録には影響しません"))
            clearHeader(tag(field)).performTextReplacement("新名字")
            // The adopted record already says 新名字; only the source record's text changes.
            tag("merchant-name-impact").assertTextContains("1",substring=true)
            sheetTap("merchant-save")
            waitForSheet(text("归入同名记录","同名の記録を取り込む"),editorAbsent=true)
            // The two saved locations also carry the renamed merchant in the impact snapshot.
            tag("merchant-name-impact").assertTextEquals(text("正常记录 2\n新关联 1 · 改名 1\n保存地点 2","通常の記録 2\n新規関連 1 · 名前変更 1\n保存場所 2"))
            shot("$theme-$language-adoption-confirm")
            sheetTap("merchant-adoption-cancel");waitForSheet(text("商户资料","お店の情報"));tag(field).assertTextContains("新名字");assertNotEquals("新名字",snapshot().merchants.single{it.id==source}.name)
            sheetTap("merchant-save");sheetTap("merchant-adoption-confirm")
            ui.waitUntil(10000){!vm.busy.value&&snapshot().merchants.single{it.id==source}.name=="新名字"}
            tag("event-$event").assertTextContains("新名字",substring=true)
            assertEquals(source,snapshot().eventMerchants.single{it.eventId==adopted}.merchantId)
            tap(text("编辑名称与备注","名前・メモを編集"))
            clearHeader(tag(field)).performTextReplacement("ＴＡＲＧＥＴ ＣＡＦＥ")
            clearHeader(tag("field-"+text("备注","メモ"))).performTextReplacement("编辑后的备注")
            sheetTap("merchant-save");waitForSheet(text("合并商户","お店を統合"),editorAbsent=true);shot("$theme-$language-merge-confirm")
            sheetTap("merchant-merge-cancel");waitForSheet(text("商户资料","お店の情報"));tag(field).assertTextContains("ＴＡＲＧＥＴ ＣＡＦＥ");assertEquals(4,snapshot().merchants.size);assertEquals("新名字",snapshot().merchants.single{it.id==source}.name)
            sheetTap("merchant-save");sheetTap("merchant-merge-confirm")
            ui.waitUntil(10000){!vm.busy.value&&snapshot().merchants.size==3}
            assertFalse(snapshot().merchants.any{it.id==source});assertEquals("编辑后的备注",snapshot().merchants.single{it.id==target}.note)
            tag("event-$event").assertTextContains("Target cafe",substring=true)
            tag("merchant-merge").performScrollTo().performClick()
            clearHeader(tag("field-"+text("搜索商户","お店を検索"))).performTextReplacement("书店")
            tag("merchant-merge-target-$target").assertDoesNotExist()
            sheetTap("merchant-merge-target-$third");tag("merchant-name-impact").assertTextContains("2",substring=true)
            sheetTap("merchant-merge-confirm")
            ui.waitUntil(10000){!vm.busy.value&&snapshot().merchants.size==2}
            tag("event-$event").assertTextContains("街角书店",substring=true)
            assertEquals(2,snapshot().merchantLocations.count{it.merchantId==third})
            ui.runOnIdle{vm.navigate("ledger")}
            tag("page-scroll").performScrollToNode(hasTestTag("event-$event"));tag("event-$event").assertTextContains("街角书店",substring=true)
        }
    }
    @Test fun legacyHistoryLocationsAndMerchantShortcutSynchronizeNamesAndProtectDrafts(){
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
            ui.onNodeWithTag("merchant-card-$merchant").performClick()
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
            expand(merchant)
            ui.onNodeWithTag("merchant-entry-$merchant").performScrollTo().performClick()
            tap(text("继续这笔草稿","下書きを続ける"))
            assertEquals(old.mediaSession,vm.entry.value!!.mediaSession);assertEquals("旧草稿",vm.entry.value!!.merchant);assertNull(vm.entry.value!!.merchantId)
            ui.runOnIdle{vm.leaveEntry(book,true,"merchants")};ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value}
            expand(merchant)
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
            expand(merchant)
            ui.onNodeWithTag("merchant-open-$merchant").performScrollTo().performClick()
            tap(text("编辑名称与备注","名前・メモを編集"))
            ui.onNodeWithTag("field-"+text("商户名称","お店の名前")).performScrollTo().performTextReplacement("新资料名")
            if(keyboard)ui.waitUntil(10000){imeVisible()}
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(100,5000)
            val merchantSave=ui.onNodeWithTag("merchant-save")
            // The IME inset and the focused field's bring-into-view keep moving the sheet after the IME reports visible;
            // scroll again until the button is still in view after things settle.
            ui.waitUntil(15000){clearHeader(merchantSave);Thread.sleep(400);ui.waitForIdle()
                merchantSave.getUnclippedBoundsInRoot().bottom<=ui.onNodeWithTag("sheet-scroll").getUnclippedBoundsInRoot().bottom}
            merchantSave.assertIsEnabled()
            shot("$theme-$language-merchant-before-bounds")
            println("Merchant bounds: ${merchantSave.getUnclippedBoundsInRoot()} header=${ui.onNodeWithTag("sheet-header").getUnclippedBoundsInRoot()} viewport=${ui.onNodeWithTag("sheet-scroll").getUnclippedBoundsInRoot()}")
            assertTrue("Merchant save is within the visible body",merchantSave.getUnclippedBoundsInRoot().bottom<=ui.onNodeWithTag("sheet-scroll").getUnclippedBoundsInRoot().bottom)
            shot("$theme-$language-merchant-name-keyboard")
            merchantSave.performTouchInput{click()}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.merchants?.single()?.name=="新资料名"&&!vm.busy.value}
            val after=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(3,after.events.size);assertEquals(198700L,after.balance(after.accounts.single()))
            assertEquals("新资料名",after.events.first{it.id==event}.merchant)
            assertEquals(2,after.eventMerchants.size);assertTrue(after.eventMerchants.all{it.merchantId==merchant})
            assertTrue(after.events.filter{it.kind==EntryKind.EXPENSE.name}.all{it.merchant=="新资料名"})
            ui.onNodeWithTag("event-$event").assertTextContains("新资料名",substring=true)
            assertEquals(2,after.merchantLocations.size);assertEquals(merchant,after.merchantCandidates(35.66130,139.66790).single().merchant.id)
            assertNull(after.merchantLocations.first{it.name=="只保存名字"}.latitude)
            val editingDraft=after.editingEntryDraft(after.events.first{it.id!=event&&it.kind==EntryKind.EXPENSE.name})
            ui.runOnIdle{vm.entry.value=editingDraft}
            ui.onNodeWithTag("merchant-delete").performScrollTo().performClick()
            // Two linked expenses lose their ID; the refund follows its purchase and is counted separately.
            ui.onNodeWithTag("merchant-name-impact").assertTextContains(text("正常记录 2","通常の記録 2"),substring=true)
            ui.onNodeWithTag("merchant-name-impact").assertTextContains(text("退款 1","返金 1"),substring=true)
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

    @Test fun originalNameAssignmentAndPendingLocationReviewAcrossLooks() {
        for((theme,language) in testLooks(this@MerchantFlowTest)) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val ids=runBlocking {
                val book=repo.createBook("原名 $theme $language",Currency.JPY,startDate="2026-10-01",theme=theme,language=language)
                repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
                val account=repo.saveAccount(book,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
                val category=repo.saveCategory(book,"咖啡",EntryKind.EXPENSE,null)
                val event=repo.saveEntry(book,EntryDraft(accountId=account,categoryId=category,amount="100",date="2026-10-14",merchant="星巴克咖啡",capturedPlace=CapturedPlace(35.0,139.0,5.0)))
                val target=repo.saveMerchant(book,"星巴克",note="目标备注",active=false)
                vm.preferences.select(book);listOf(book,event,target)
            }
            val book=ids[0];val event=ids[1];val target=ids[2]
            fun snapshot()=(vm.state.value as AppState.Ready).snapshot!!
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book}
            ui.runOnIdle{vm.navigate("merchants")}
            ui.onNodeWithTag("merchant-card-recorded-$event").performScrollTo().performClick()
            clearHeader(ui.onNodeWithTag("field-"+text("商户名称","お店の名前"))).performTextReplacement("星巴克")
            clearHeader(ui.onNodeWithTag("field-"+text("备注","メモ"))).performTextReplacement("来源备注")
            ui.onNodeWithTag("merchant-original-group").assertTextContains("星巴克咖啡",substring=true)
            shot("$theme-$language-original-group-editor")
            clearHeader(ui.onNodeWithTag("merchant-save")).performClick()
            waitForSheet(text("归入同名记录","同名の記録を取り込む"),editorAbsent=true)
            ui.onNodeWithTag("merchant-adoption-message").assertTextContains(text("停用","停止"),substring=true)
            ui.onNodeWithTag("recorded-place-0").performScrollTo();shot("$theme-$language-original-assignment-places")
            clearHeader(ui.onNodeWithTag("merchant-adoption-confirm")).performClick()
            ui.waitUntil(10000){!vm.busy.value&&snapshot().eventMerchants.any{it.eventId==event&&it.merchantId==target}}
            assertEquals(1,snapshot().merchants.size);assertFalse(snapshot().merchants.single().active)
            assertEquals("目标备注\n来源备注",snapshot().merchants.single().note)
            assertEquals("星巴克",snapshot().events.single().merchant);assertTrue(snapshot().eventPlaces.isEmpty())
            assertEquals(target,snapshot().merchantLocations.single().merchantId)
            // A legacy linked capture is offered through the same place-review flow.
            runBlocking{repo.dao.putEventPlace(EventPlaceEntity(book,event,35.01,139.01,5.0))}
            ui.waitUntil(10000){snapshot().eventPlaces.isNotEmpty()}
            ui.onNodeWithTag("merchant-organize-places").performScrollTo().performClick()
            waitForSheet(text("整理待处理位置","未整理の場所を整理"),editorAbsent=true)
            ui.onNodeWithTag("recorded-place-0").performScrollTo();shot("$theme-$language-pending-place-review")
            clearHeader(ui.onNodeWithTag("toggle-"+text("保留地点","場所を保存"))).performClick()
            clearHeader(ui.onNodeWithTag("merchant-adoption-confirm")).performClick()
            ui.waitUntil(10000){!vm.busy.value&&snapshot().eventPlaces.isEmpty()}
            assertEquals(1,snapshot().merchantLocations.size)
        }
    }

    @Test fun disabledMerchantInputAndRefundOwnershipAcrossLooks() {
        for((theme,language) in testLooks(this@MerchantFlowTest)) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val ids=runBlocking {
                val book=repo.createBook("Merchant B $theme $language",Currency.JPY,startDate="2026-10-01",theme=theme,language=language)
                repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
                val account=repo.saveAccount(book,"Bank",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
                val category=repo.saveCategory(book,"Food",EntryKind.EXPENSE,null)
                val shop=repo.saveMerchant(book,"Ｃａｆｅ",active=false)
                val other=repo.saveMerchant(book,"B")
                vm.preferences.select(book);listOf(book,account,category,shop,other)
            }
            val (book,account,category,shop,other)=ids
            fun snapshot()=(vm.state.value as AppState.Ready).snapshot!!
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book}
            ui.runOnIdle {
                vm.startEntry(snapshot())
                vm.changeEntry(book,vm.entry.value!!.copy(categoryId=category,accountId=account,amount="100"))
                vm.navigate("entry")
            }
            val input=ui.onNodeWithTag("field-"+text("商户 / 买了什么","お店 / 買ったもの"))
            input.performScrollTo().performTextReplacement("cafe")
            ui.waitUntil(10000){vm.entry.value?.merchantId==shop}
            ui.onNodeWithTag("entry-merchant-selected").assertTextContains(text("将关联到已停用商户 Ｃａｆｅ","停止中のお店「Ｃａｆｅ」に関連付けます"),substring=true)
            ui.onNodeWithTag("entry-merchant-selected").performScrollTo();shot("$theme-$language-disabled-merchant")
            input.performScrollTo().performTextReplacement("New shop");assertNull(vm.entry.value!!.merchantId)
            input.performTextReplacement("cafe")
            ui.entryAction("entry-save-button").performClick()
            ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value&&snapshot().events.size==1}
            val purchase=snapshot().events.single().id
            val refunds=runBlocking {
                val confirmed=repo.saveRefund(book,RefundInput(purchase,Money(Currency.JPY,20),account,Money(Currency.JPY,20),Money(Currency.JPY,20),"2026-10-14",true))
                val pending=repo.saveRefund(book,RefundInput(purchase,Money(Currency.JPY,5),account,Money(Currency.JPY,5),Money(Currency.JPY,5),"2026-10-14",false))
                repo.saveEntry(book,EntryDraft(id=purchase,merchantBaseline=MerchantBaseline("Ｃａｆｅ",shop),accountId=account,categoryId=category,amount="100",date="2026-10-14",merchant="B"))
                listOf(confirmed,pending)
            }
            ui.waitUntil(10000){snapshot().events.size==3&&snapshot().events.all{it.merchant=="B"}}
            ui.runOnIdle{vm.navigate("merchants")}
            expand(other);ui.onNodeWithTag("merchant-open-$other").performScrollTo().performClick()
            ui.onNodeWithTag("event-${refunds[0]}").performScrollTo().assertTextContains(text("已到账","入金済"),substring=true)
            ui.onNodeWithTag("event-${refunds[1]}").performScrollTo().assertTextContains(text("待到账","未入金"),substring=true)
            shot("$theme-$language-refund-ownership")
            assertTrue(snapshot().merchantEvents(shop).isEmpty())
            assertEquals(80L,snapshot().analysis(AnalysisFilter("2026-10-01","2026-10-31",merchantKey="id:$other")).total)
        }
    }
    @Test fun templateAndRecurrenceEditorsFollowSystemMerchantChangesButKeepUserInput() {
        for(template in listOf(true,false))for(userEdited in listOf(false,true)) {
            val values=runBlocking {
                vm.preferences.clearSetup()
                val book=repo.createBook("Editor sync",Currency.JPY,"magazine","zh",startDate="2026-10-01")
                repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
                val account=repo.saveAccount(book,"Cash",AccountType.CASH,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
                val category=repo.saveCategory(book,"Food",EntryKind.EXPENSE,null)
                val id=if(template)repo.saveTemplate(book,TemplateInput("Daily",EntryKind.EXPENSE,category,account,Currency.JPY,merchant="Raw"))
                    else repo.saveRecurrence(book,RecurrenceInput("Daily",account,category,Money(Currency.JPY,100),null,false,RecurrenceFrequency.MONTHLY,"2026-10-01",merchant="Raw"))
                vm.preferences.select(book);listOf(book,id)
            }
            val (book,id)=values
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            ui.runOnIdle{vm.navigate(if(template)"templates"else"recurring")}
            if(template)ui.onNodeWithTag("edit-template-$id").performScrollTo().performClick()else tap("编辑规则")
            val input=ui.onNodeWithTag("field-商户 / 买了什么")
            input.performScrollTo().assertTextEquals("Raw")
            if(userEdited)input.performTextReplacement("My choice")
            val target=runBlocking {
                val saved=repo.commitMerchantOperation(repo.previewMerchantOperation(book,MerchantOperationInput(
                    MerchantOperationType.CREATE_FROM_ORIGINAL_NAME,name="Formal",originalName="Raw",createdId="organized")))!!
                repo.saveMerchant(book,"Renamed",id=saved)
                repo.saveMerchant(book,"My choice")
                saved
            }
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.merchants?.any{it.name=="Renamed"}==true}
            input.assertTextEquals(if(userEdited)"My choice"else"Renamed")
            ui.onNodeWithTag(if(template)"template-save"else"recurrence-save").performScrollTo().performClick()
            ui.waitUntil(10000){!vm.busy.value&&ui.onAllNodesWithTag(if(template)"template-save"else"recurrence-save").fetchSemanticsNodes().isEmpty()}
            val saved=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            val fields=if(template)saved.templates.single().let{MerchantBaseline(it.merchant,it.merchantId)}else saved.recurrences.single().let{MerchantBaseline(it.merchant,it.merchantId)}
            assertEquals(if(userEdited)"My choice"else"Renamed",fields.name)
            assertEquals(if(userEdited)saved.merchants.single{it.name=="My choice"}.id else target,fields.id)
            assertNull(vm.error.value)
        }
    }

}
