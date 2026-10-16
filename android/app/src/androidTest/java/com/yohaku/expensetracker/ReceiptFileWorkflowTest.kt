package com.yohaku.expensetracker

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.files.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.features.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.time.*
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class ReceiptFileWorkflowTest {
    @get:Rule val ui=createComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var vm:AppViewModel
    private lateinit var root:File
    private val folders=mutableListOf<String>()
    @Before fun open() {
        val service=instrumentation.uiAutomation.serviceInfo
        service.flags=service.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        instrumentation.uiAutomation.serviceInfo=service
        val context=instrumentation.targetContext
        root=File(context.cacheDir,"receipt-workflow-test").apply{mkdirs()}
        db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        repo=LedgerRepository(db,Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"),ZoneOffset.UTC),root)
        vm=AppViewModel(repo,AppPreferences(context));ui.setContent{NativeApp(vm)}
    }
    @After fun close(){vm.viewModelScope.cancel();db.close();root.deleteRecursively();folders.forEach{shell("rm -r /sdcard/Download/$it")}}
    private fun shell(command:String):ByteArray=ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use{it.readBytes()}
    private fun nodes(node:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(node==null)emptyList()else listOf(node)+(0 until node.childCount).flatMap{nodes(node.getChild(it))}
    private fun systemNodes()=nodes(instrumentation.uiAutomation.rootInActiveWindow)
    private fun systemClick(label:String) {
        try { ui.waitUntil(15000){systemNodes().any{it.text?.toString()?.equals(label,true)==true||it.contentDescription?.toString()?.equals(label,true)==true}} }
        catch(e:ComposeTimeoutException){
            println("System picker missing $label: "+systemNodes().joinToString(" | "){it.text.toString()+" / "+it.contentDescription.toString()})
            shot("picker-failure");throw e
        }
        instrumentation.uiAutomation.waitForIdle(300,5000)
        val node=systemNodes().first{it.text?.toString()?.equals(label,true)==true||it.contentDescription?.toString()?.equals(label,true)==true}
        val bounds=android.graphics.Rect();node.getBoundsInScreen(bounds)
        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
    }
    private fun openFolder(folder:String) {
        ui.waitUntil(15000){systemNodes().any{it.packageName?.toString()?.contains("documentsui")==true}}
        instrumentation.uiAutomation.waitForIdle(500,5000)
        if(systemNodes().any{it.text?.toString()==folder&&it.viewIdResourceName?.endsWith("/breadcrumb_text")==true})return
        if(systemNodes().none{it.text?.toString()==folder}&&systemNodes().any{it.text?.toString()=="Download"}) {
            systemClick("Download")
        }else if(systemNodes().none{it.text?.toString()==folder}) {
            val navigation=systemNodes().firstOrNull{it.contentDescription?.toString()?.contains("roots",true)==true||it.contentDescription?.toString()?.contains("navigation",true)==true}
            navigation?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            systemClick("Downloads")
        }
        systemClick(folder)
    }
    private fun writeResult(folder:String,name:String,raw:String) {
        val pipes=instrumentation.uiAutomation.executeShellCommandRw("tee /sdcard/Download/$folder/$name")
        ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use{it.write(raw.toByteArray())}
        ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use{it.readBytes()}
        shell("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/$folder/$name")
    }
    private fun pickResult(folder:String,name:String="assistant.json") {
        ui.waitUntil(15000){systemNodes().any{it.packageName?.toString()?.contains("documentsui")==true}}
        instrumentation.uiAutomation.waitForIdle(500,5000)
        if(systemNodes().none{it.text?.toString()==name})openFolder(folder)
        systemClick(name)
    }
    private fun click(tag:String) {
        ui.waitUntil(10000){!vm.busy.value&&ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}
        val node=ui.onNodeWithTag(tag);node.performScrollTo();ui.waitForIdle()
        val scrolls=ui.onAllNodes(hasTestTag("sheet-scroll") and hasAnyDescendant(hasTestTag(tag)))
        if(scrolls.fetchSemanticsNodes().isNotEmpty()) {
            val scroll=scrolls.onLast()
            val header=ui.onAllNodes(hasTestTag("sheet-header") and hasAnyAncestor(hasAnyDescendant(hasTestTag(tag)))).onLast()
            val top=node.getUnclippedBoundsInRoot().top.value
            val boundary=header.getUnclippedBoundsInRoot().bottom.value
            if(top<boundary+8)scroll.performSemanticsAction(SemanticsActions.ScrollBy){it(0f,-(boundary+8-top)*instrumentation.targetContext.resources.displayMetrics.density)}
            ui.waitForIdle()
            val bottom=node.getUnclippedBoundsInRoot().bottom.value
            val viewport=scroll.getUnclippedBoundsInRoot().bottom.value
            if(bottom>viewport)scroll.performSemanticsAction(SemanticsActions.ScrollBy){it(0f,(bottom-viewport+8)*instrumentation.targetContext.resources.displayMetrics.density)}
        }
        ui.waitForIdle();node.performClick()
    }
    private fun shot(name:String) {
        ui.waitForIdle();instrumentation.uiAutomation.waitForIdle(400,5000)
        val bitmap=instrumentation.uiAutomation.takeScreenshot()
        val file=File(instrumentation.targetContext.getExternalFilesDir(null),"phase-d-receipts-${if(instrumentation.targetContext.resources.configuration.screenWidthDp<=360)"narrow"else"standard"}/$name.png").apply{parentFile!!.mkdirs()}
        file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    private fun picture(color:Int):ByteArray {
        val bitmap=Bitmap.createBitmap(100,180,Bitmap.Config.ARGB_8888).apply{eraseColor(color)}
        return java.io.ByteArrayOutputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it);bitmap.recycle();it.toByteArray()}
    }
    @Test fun realZipToAssistantJsonPreviewBackfillAndEditAcrossThemesAndLanguages() {
        val themes=listOf("magazine","journal","objects").filter{InstrumentationRegistry.getArguments().getString("theme")?.let{wanted->it==wanted}?:true}
        val languages=listOf("zh","ja").filter{InstrumentationRegistry.getArguments().getString("language")?.let{wanted->it==wanted}?:true}
        for(theme in themes)for(language in languages) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val book=runBlocking {
                val id=repo.createBook("小票 $theme $language",Currency.JPY,theme=theme,language=language)
                repo.updateBook(repo.dao.book(id)!!.copy(initialized=true))
                val account=repo.saveAccount(id,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
                val category=repo.saveCategory(id,"買い物",EntryKind.EXPENSE,null)
                val draft=EntryDraft(accountId=account,categoryId=category,amount="1000",date="2026-10-14",merchant="小票店")
                repo.saveDraft(id,draft)
                for(color in listOf(android.graphics.Color.WHITE,android.graphics.Color.YELLOW))repo.importMedia(id,draft.mediaSession,"original-$color.png","image/png",true){picture(color).inputStream()}
                val loaded=repo.snapshot(repo.dao.book(id)!!).first().draft!!
                repo.saveEntry(id,loaded);vm.preferences.select(id);id
            }
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book}
            ui.runOnIdle{vm.navigate("receipts")}
            val snapshot=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            val receipt=snapshot.receipts.single()
            val folder="YohakuD-"+repo.id();folders+=folder;shell("mkdir -p /sdcard/Download/$folder")
            click("receipt-select-${receipt.id}");click("receipt-export")
            ui.waitUntil(10000){ui.onAllNodesWithTag("receipt-save-zip").fetchSemanticsNodes().isNotEmpty()}
            shot("$theme-$language-export")
            click("receipt-save-zip");openFolder(folder);systemClick("Use this folder");systemClick("Allow")
            ui.waitUntil(20000){!vm.busy.value&&runBlocking{db.fileWorkflowDao().exports(book).first().isNotEmpty()}}
            assertEquals("UNSORTED",runBlocking{repo.snapshot(repo.dao.book(book)!!).first().receipts.single().status})
            val filename=shell("ls /sdcard/Download/$folder").toString(Charsets.UTF_8).trim()
            assertTrue(filename.endsWith(".zip"))
            val entries=mutableMapOf<String,ByteArray>()
            ZipInputStream(shell("cat /sdcard/Download/$folder/$filename").inputStream()).use{zip->while(true){val entry=zip.nextEntry?:break;entries[entry.name]=zip.readBytes();zip.closeEntry()}}
            val manifest=FileProtocols.receiptBundle(entries.getValue("manifest.json").toString(Charsets.UTF_8))
            assertEquals(3,entries.size)
            val exported=manifest.records.single()
            exported.images.forEach{image->
                val bytes=entries.getValue(image.path)
                assertEquals(image.sha256,MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)})
                assertArrayEquals(repo.mediaFile(snapshot.files.first{it.id==image.image_id}).readBytes(),bytes)
            }
            val item=ReceiptItem(repo.id(),exported.images.map{it.image_id},emptyList(),"商品","1","個","950","950")
            val record=ReceiptItemsRecord(exported.record_id,exported.receipt_id,exported.record_revision,exported.receipt_revision,"JPY",exported.images.map{it.image_id},listOf(item),emptyList(),true,emptyList(),"950")
            val returned=ReceiptItemsFile("yohaku.receipt-items",1,manifest.bundle_id,book,listOf(record))
            val raw=FileProtocols.json.encodeToString(returned)
            writeResult(folder,"assistant.json",raw)
            click("receipt-import");ui.onNodeWithTag("receipt-import-cover").assertExists()
            shot("$theme-$language-import-cover")
            click("receipt-result-pick-file");pickResult(folder)
            ui.waitUntil(15000){!vm.busy.value&&ui.onAllNodesWithTag("receipt-confirm-import").fetchSemanticsNodes().isNotEmpty()}
            assertTrue(runBlocking{db.fileWorkflowDao().items(book).first().isEmpty()})
            shot("$theme-$language-review")
            click("receipt-confirm-import")
            ui.waitUntil(10000){!vm.busy.value&&runBlocking{db.fileWorkflowDao().items(book).first().isNotEmpty()}}
            click("receipt-tab-SORTED");click("receipt-open-${receipt.id}")
            click("receipt-edit-items");shot("$theme-$language-item-editor")
            ui.onNodeWithTag("field-"+text("商品名","商品名")).performScrollTo().performTextReplacement("手工商品")
            click("receipt-save-item-edits")
            try { ui.waitUntil(10000){
                vm.error.value?.let{throw AssertionError("Product error after edit: $it")}
                !vm.busy.value&&runBlocking{db.fileWorkflowDao().itemsForReceipt(book,receipt.id)!!.revision==2}
            } }catch(e:Throwable){shot("edit-save-failure");throw e}
            val after=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(1,after.events.size);assertEquals(1000L,after.events.single().amountMinor)
            assertEquals(9000L,after.balance(after.accounts.single()));assertEquals(2,after.entryReceiptImages(exported.record_id).size)
            assertEquals("手工商品",FileProtocols.json.decodeFromString<ReceiptItemsRecord>(runBlocking{db.fileWorkflowDao().itemsForReceipt(book,receipt.id)!!}.recordJson).items.single().name)
            shot("$theme-$language-saved-items")
            runBlocking {
                val original=after.events.single()
                val edit=EntryDraft(id=original.id,accountId=original.accountId,categoryId=original.categoryId,amount="1000",date=original.date,merchant=original.merchant,mediaInitialized=true,receiptFileIds=after.entryReceiptImages(original.id).map{it.id})
                repo.saveDraft(book,edit)
                repo.importMedia(book,edit.mediaSession,"added.png","image/png",true){picture(android.graphics.Color.BLUE).inputStream()}
                repo.saveEntry(book,repo.json.decodeFromString<EntryDraft>(repo.dao.storedDraft(book)!!.json))
            }
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.receipts?.single()?.revision==2}
            val extended=JsonObject(FileProtocols.json.parseToJsonElement(raw).jsonObject+("assistant_extension" to JsonPrimitive("compatible"))).toString()
            writeResult(folder,"assistant-old.json",extended)
            fun openOldResult() {
                click("receipt-import-for-record");ui.onNodeWithTag("receipt-import-cover").assertExists()
                click("receipt-result-pick-file");pickResult(folder,"assistant-old.json")
                ui.waitUntil(10000){!vm.busy.value&&ui.onAllNodesWithTag("receipt-confirm-import").fetchSemanticsNodes().isNotEmpty()}
            }
            openOldResult()
            ui.onNodeWithText("$.assistant_extension").assertExists()
            assertEquals(2,runBlocking{db.fileWorkflowDao().itemsForReceipt(book,receipt.id)!!.revision})
            if(theme=="magazine"&&language=="zh") {
                ui.onNode(hasTestTag("sheet-close")and hasAnyAncestor(hasAnyDescendant(hasTestTag("receipt-confirm-import")))).performClick()
                assertEquals("手工商品",FileProtocols.json.decodeFromString<ReceiptItemsRecord>(runBlocking{db.fileWorkflowDao().itemsForReceipt(book,receipt.id)!!}.recordJson).items.single().name)
                openOldResult()
            }
            click("receipt-confirm-import")
            ui.onNodeWithTag("receipt-confirm-replacement").assertExists()
            assertEquals(2,runBlocking{db.fileWorkflowDao().itemsForReceipt(book,receipt.id)!!.revision})
            shot("$theme-$language-stale-replace")
            click("receipt-confirm-replacement")
            ui.waitUntil(10000){!vm.busy.value&&runBlocking{db.fileWorkflowDao().itemsForReceipt(book,receipt.id)!!.revision==3}}
            val latest=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals("UNSORTED",latest.receipts.single().status);assertEquals(3,latest.entryReceiptImages(exported.record_id).size)
            assertEquals(1,latest.events.size);assertEquals(1000L,latest.events.single().amountMinor);assertEquals(9000L,latest.balance(latest.accounts.single()))
            val replacement=FileProtocols.json.decodeFromString<ReceiptItemsRecord>(runBlocking{db.fileWorkflowDao().itemsForReceipt(book,receipt.id)!!}.recordJson)
            assertEquals(1,replacement.items.size);assertEquals("商品",replacement.items.single().name)
            if(theme=="objects"&&language=="ja") {
                writeResult(folder,"invalid-version.json",FileProtocols.json.encodeToString(returned.copy(version=2)))
                click("receipt-import-for-record");click("receipt-result-pick-file");pickResult(folder,"invalid-version.json")
                ui.waitUntil(10000){vm.error.value?.contains("version")==true&&!vm.busy.value}
                assertEquals(3,runBlocking{db.fileWorkflowDao().itemsForReceipt(book,receipt.id)!!.revision})
                ui.onNode(hasText("確認")and hasClickAction()).performScrollTo().performClick()
                click("receipt-import-format-help");click("file-format-save")
                openFolder(folder);systemClick("Use this folder");systemClick("Allow")
                ui.waitUntil(15000){ui.onAllNodesWithTag("file-format-saved").fetchSemanticsNodes().isNotEmpty()}
                val docs=mutableMapOf<String,ByteArray>()
                ZipInputStream(shell("cat /sdcard/Download/$folder/yohaku-file-formats-v1.zip").inputStream()).use{zip->while(true){val entry=zip.nextEntry?:break;docs[entry.name]=zip.readBytes();zip.closeEntry()}}
                assertTrue(docs.keys.containsAll(listOf("README.md","statement.v1.schema.json","receipt-bundle.v1.schema.json","receipt-items.v1.schema.json","import-identifiers.v1.schema.json","examples/statement.valid.json","examples/statement.invalid.json","examples/receipt-items.valid.json")))
                FileProtocols.statement(docs.getValue("examples/statement.valid.json").toString(Charsets.UTF_8))
                FileProtocols.receiptItems(docs.getValue("examples/receipt-items.valid.json").toString(Charsets.UTF_8))
                shot("$theme-$language-format-help")
                ui.onNode(hasTestTag("sheet-close")and hasAnyAncestor(hasAnyDescendant(hasTestTag("file-format-save")))).performClick()
                ui.onNode(hasTestTag("sheet-close")and hasAnyAncestor(hasAnyDescendant(hasTestTag("receipt-result-pick-file")))).performClick()
            }
            ui.onNodeWithTag("sheet-close").performClick()
            println("Receipt file workflow passed: $theme $language; exact original ZIP → JSON → item edit, unchanged ledger")
        }
    }
}
