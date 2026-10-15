package com.yohaku.expensetracker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import java.io.*
import java.time.*

@RunWith(AndroidJUnit4::class)
class ReceiptWorkbenchFlowTest {
    @get:Rule val ui=createComposeRule()
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var vm:AppViewModel
    private lateinit var root:File
    @Before fun open(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        root=File(context.filesDir,"receipt-workbench-test").apply{mkdirs()}
        db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        repo=LedgerRepository(db,Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"),ZoneOffset.UTC),root)
        vm=AppViewModel(repo,AppPreferences(context));ui.setContent{NativeApp(vm)}
    }
    @After fun close(){vm.viewModelScope.cancel();db.close();root.deleteRecursively()}
    private fun picture(label:String):ByteArray {
        val bitmap=Bitmap.createBitmap(120,200,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
        val paint=Paint().apply{color=Color.DKGRAY;textSize=14f};canvas.drawText(label,10f,25f,paint)
        for(y in 45..170 step 20)canvas.drawLine(10f,y.toFloat(),110f,y.toFloat(),paint)
        return ByteArrayOutputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it);bitmap.recycle();it.toByteArray()}
    }
    private fun shot(name:String,windows:Int=1){ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().size==windows};ui.waitForIdle();val auto=InstrumentationRegistry.getInstrumentation().uiAutomation;auto.waitForIdle(400,5000);val bitmap=auto.takeScreenshot();val context=InstrumentationRegistry.getInstrumentation().targetContext;val file=File(context.getExternalFilesDir(null),"phase-b-p13-workbench/$name.png");file.parentFile!!.mkdirs();file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
    private fun clearHeader(node:SemanticsNodeInteraction):SemanticsNodeInteraction {
        ui.waitUntil(10000){!vm.busy.value};node.performScrollTo();ui.waitForIdle()
        val density=InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val header=ui.onNodeWithTag("sheet-header").getUnclippedBoundsInRoot().bottom.value
        val top=node.getUnclippedBoundsInRoot().top.value
        if(top<header+8)ui.onNodeWithTag("sheet-scroll").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy){it(0f,-(header+8-top)*density)}
        ui.waitForIdle()
        val bottom=node.getUnclippedBoundsInRoot().bottom.value
        val viewport=ui.onNodeWithTag("sheet-scroll").getUnclippedBoundsInRoot().bottom.value
        if(bottom>viewport)ui.onNodeWithTag("sheet-scroll").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy){it(0f,(bottom-viewport+8)*density)}
        ui.waitForIdle();return node
    }
    @Test fun coverTabsOriginalImagesAndRecordLinksUseOnlyTheCurrentRevisionInEveryThemeAndLanguage(){
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")) {
            val ids=runBlocking {
                val book=repo.createBook("レシート $theme $language",Currency.JPY,startDate="2026-10-01",theme=theme,language=language)
                repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
                val account=repo.saveAccount(book,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
                val category=repo.saveCategory(book,"買い物",EntryKind.EXPENSE,null)
                val draft=EntryDraft(accountId=account,categoryId=category,amount="1000",merchant="小票店",date="2026-10-02")
                repo.saveDraft(book,draft)
                val first=repo.importMedia(book,draft.mediaSession,"一枚目.png","image/png",true){picture("RECEIPT ONE").inputStream()}
                val second=repo.importMedia(book,draft.mediaSession,"二枚目.png","image/png",true){picture("RECEIPT TWO").inputStream()}
                val loaded=repo.snapshot(repo.dao.book(book)!!).first().draft!!
                val event=repo.saveEntry(book,loaded)
                val receipt=repo.snapshot(repo.dao.book(book)!!).first().receipts.single()
                vm.preferences.select(book);listOf(book,event,receipt.id,first.id,second.id)
            }
            val book=ids[0];val event=ids[1];val receipt=ids[2];val first=ids[3];val second=ids[4]
            println("Receipt workbench UI: $theme $language")
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book}
            ui.runOnIdle{vm.navigate("receipts")}
            ui.onNodeWithTag("receipt-pending-count").assertTextEquals(if(theme=="magazine")"01"else"1")
            shot("$theme-$language-cover")
            ui.onNodeWithTag("receipt-card-$receipt").performScrollTo();shot("$theme-$language-card")
            ui.onNodeWithTag("receipt-image-$first").assertExists();ui.onNodeWithTag("receipt-image-$second").assertExists()
            ui.onNodeWithTag("receipt-image-$first").performScrollTo().performClick()
            ui.waitUntil(10000){ui.onAllNodesWithTag("media-image").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("media-image").assertExists()
            ui.onNodeWithTag("media-image-index").assertTextEquals("1 / 2")
            ui.onNodeWithTag("media-image-previous").assertIsNotEnabled()
            clearHeader(ui.onNodeWithTag("media-image-zoom")).performClick()
            ui.onNodeWithTag("media-image").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"180%"))
            ui.onNodeWithTag("media-image-next").performClick()
            ui.waitUntil(10000){ui.onAllNodesWithTag("media-image").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("media-image-index").assertTextEquals("2 / 2")
            ui.onNodeWithTag("media-image-next").assertIsNotEnabled()
            ui.onNodeWithTag("media-image").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"100%"))
            shot("$theme-$language-image-preview",2)
            clearHeader(ui.onNodeWithTag("media-image-previous")).performClick()
            ui.waitUntil(10000){ui.onAllNodesWithTag("media-image").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("media-image-index").assertTextEquals("1 / 2")
            ui.onNodeWithTag("media-image").performScrollTo().performTouchInput{pinch(center,center+androidx.compose.ui.geometry.Offset(5f,5f),center-androidx.compose.ui.geometry.Offset(70f,70f),center+androidx.compose.ui.geometry.Offset(70f,70f))}
            ui.waitUntil(10000){ui.onNodeWithTag("media-image").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription]!="100%"}
            clearHeader(ui.onNodeWithTag("media-image-zoom")).performClick()
            ui.onNodeWithTag("media-image").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"100%"))
            ui.onNodeWithTag("sheet-close").performClick()
            ui.onNodeWithTag("receipt-tab-SORTED").performScrollTo().performClick().assertIsSelected()
            ui.onNodeWithTag("receipt-card-$receipt").assertDoesNotExist()
            ui.onNodeWithTag("receipt-tab-ALL").performScrollTo().performClick().assertIsSelected()
            runBlocking {
                val prior=repo.snapshot(repo.dao.book(book)!!).first()
                val original=prior.events.single()
                val edit=EntryDraft(id=event,accountId=original.accountId,categoryId=original.categoryId,amount="1000",merchant=original.merchant,date=original.date,receiptFileIds=listOf(second),mediaInitialized=true)
                repo.saveDraft(book,edit);repo.saveEntry(book,edit)
            }
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.receipts?.single()?.revision==2}
            ui.onNodeWithTag("receipt-image-$first").assertDoesNotExist()
            ui.onNodeWithTag("receipt-image-$second").assertExists()
            ui.onNodeWithTag("receipt-record-$receipt").assertTextContains("第2版",substring=true)
            ui.onNodeWithTag("receipt-card-$receipt").performScrollTo();shot("$theme-$language-current-revision")
            ui.onNodeWithTag("receipt-open-$receipt").performScrollTo().performClick()
            ui.onNodeWithTag("record-edit-button").assertExists()
            ui.onNodeWithTag("sheet-close").performClick()
            val after=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(listOf(second),after.entryReceiptImages(event).map{it.id})
            assertEquals(2,after.receipts.single().revision);assertEquals(1,after.events.size);assertEquals(9000L,after.balance(after.accounts.single()))
            assertTrue(after.files.all{repo.mediaFile(it).isFile})
        }
    }
    @Test fun mediaEditOrderingRemovalCancellationAndSaveKeepTheOriginalRecordAndFiles(){
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")) {
            val ids=runBlocking {
                val book=repo.createBook("添付 $theme $language",Currency.JPY,startDate="2026-10-01",theme=theme,language=language)
                repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
                val account=repo.saveAccount(book,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
                val category=repo.saveCategory(book,"買い物",EntryKind.EXPENSE,null)
                val draft=EntryDraft(accountId=account,categoryId=category,amount="1000",merchant="小票店",date="2026-10-02")
                repo.saveDraft(book,draft)
                val first=repo.importMedia(book,draft.mediaSession,"第一张原图.png","image/png",true){picture("RECEIPT ONE").inputStream()}
                val second=repo.importMedia(book,draft.mediaSession,"第二张原图.png","image/png",true){picture("RECEIPT TWO").inputStream()}
                val attachment=repo.importMedia(book,draft.mediaSession,"很长的原始购物凭证名称_購入の証明書_2026-10-02.txt","text/plain",false){"Original document".byteInputStream()}
                val photo=repo.importMedia(book,draft.mediaSession,"购物照片.png","image/png",false){picture("PHOTO").inputStream()}
                val loaded=repo.snapshot(repo.dao.book(book)!!).first().draft!!
                val event=repo.saveEntry(book,loaded)
                vm.preferences.select(book);listOf(book,event,first.id,second.id,attachment.id,photo.id)
            }
            val book=ids[0];val event=ids[1];val first=ids[2];val second=ids[3];val attachment=ids[4]
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book}
            ui.runOnIdle{val s=(vm.state.value as AppState.Ready).snapshot!!;vm.editEntry(s,s.events.single())}
            ui.waitUntil(10000){vm.entry.value?.receiptFileIds?.size==2}
            ui.onNodeWithTag("media-file-$attachment").performScrollTo().assertExists();shot("$theme-$language-edit-files",2)
            ui.onNodeWithTag("media-receipt-previous-$first").assertIsNotEnabled()
            val order=clearHeader(ui.onNodeWithTag("media-receipt-previous-$second"));order.assertIsEnabled()
            println("ORDER $theme $language bounds="+order.getUnclippedBoundsInRoot()+" header="+ui.onNodeWithTag("sheet-header").getUnclippedBoundsInRoot()+" before="+vm.entry.value!!.receiptFileIds)
            shot("$theme-$language-order-touch",2)
            order.performClick()
            println("ORDER AFTER "+vm.entry.value?.receiptFileIds)
            ui.waitUntil(10000){vm.entry.value?.receiptFileIds==listOf(second,first)}
            ui.onNodeWithTag("media-receipt-next-$first").assertIsNotEnabled()
            ui.onNodeWithTag("media-receipt-strip").performScrollTo();shot("$theme-$language-edit-receipts",2)
            clearHeader(ui.onNodeWithTag("media-receipt-remove-$first")).performClick()
            clearHeader(ui.onNodeWithTag("media-remove-$attachment")).performClick()
            ui.waitUntil(10000){vm.entry.value?.receiptFileIds==listOf(second)&&attachment !in vm.entry.value!!.attachmentIds}
            clearHeader(ui.onNodeWithTag("entry-cancel-edit")).performClick()
            ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value}
            val original=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(listOf(first,second),original.entryReceiptImages(event).map{it.id})
            assertEquals(2,original.entryAttachments(event).size)
            assertTrue(original.files.all{repo.mediaFile(it).isFile})
            ui.runOnIdle{vm.editEntry(original,original.events.single())}
            ui.waitUntil(10000){vm.entry.value?.receiptFileIds?.size==2}
            clearHeader(ui.onNodeWithTag("media-receipt-previous-$second")).performClick()
            ui.waitUntil(10000){vm.entry.value?.receiptFileIds==listOf(second,first)}
            clearHeader(ui.onNodeWithTag("entry-save-button")).performClick()
            ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value}
            val saved=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(listOf(second,first),saved.entryReceiptImages(event).map{it.id})
            assertEquals(2,saved.entryAttachments(event).size);assertEquals(1,saved.events.size)
            assertEquals(9000L,saved.balance(saved.accounts.single()));assertEquals(2,saved.receipts.single().revision)
            assertTrue(saved.files.all{repo.mediaFile(it).isFile})
        }
    }

}
