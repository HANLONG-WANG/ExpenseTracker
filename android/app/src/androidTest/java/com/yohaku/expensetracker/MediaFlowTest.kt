package com.yohaku.expensetracker

import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
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

@RunWith(AndroidJUnit4::class)
class MediaFlowTest {
    @get:Rule val ui=createComposeRule()
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var vm:AppViewModel
    private lateinit var root:File
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    @Before fun open() {
        val service=instrumentation.uiAutomation.serviceInfo;service.flags=service.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;instrumentation.uiAutomation.serviceInfo=service
        val context=instrumentation.targetContext
        root=File(File(context.filesDir,"attachments"),"ui-media-"+java.util.UUID.randomUUID()).apply{mkdirs()}
        db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build();repo=LedgerRepository(db,mediaRoot=root)
        vm=AppViewModel(repo,AppPreferences(context), testReferenceRates(AppPreferences(context),repo.clock));ui.setContent{
            var camera:((String,String)->Unit)?=null
            androidx.compose.runtime.CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides cameraOwner){camera=rememberReceiptCamera(vm)}
            androidx.compose.runtime.CompositionLocalProvider(LocalReceiptCamera provides camera){NativeApp(vm)}
        }
    }
    /** Stands in for the camera app: writes [cameraPhoto] into the EXTRA_OUTPUT uri, or cancels when null. */
    private var cameraPhoto:ByteArray?=null
    private val cameraOwner=object:androidx.activity.result.ActivityResultRegistryOwner {
        override val activityResultRegistry=object:androidx.activity.result.ActivityResultRegistry() {
            override fun <I,O> onLaunch(requestCode:Int,contract:androidx.activity.result.contract.ActivityResultContract<I,O>,input:I,options:androidx.core.app.ActivityOptionsCompat?) {
                val photo=cameraPhoto;if(photo!=null)instrumentation.targetContext.contentResolver.openOutputStream(input as android.net.Uri)!!.use{it.write(photo)}
                dispatchResult(requestCode,photo!=null)
            }
        }
    }
    @After fun close(){vm.viewModelScope.cancel();db.close();root.deleteRecursively()}
    private fun nodes(root:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(root==null)emptyList()else listOf(root)+(0 until root.childCount).flatMap{nodes(root.getChild(it))}
    private fun systemNodes()=nodes(instrumentation.uiAutomation.rootInActiveWindow)
    private fun systemClick(text:String,long:Boolean=false,rootOnly:Boolean=false) {
        fun matches(node:AccessibilityNodeInfo):Boolean {
            if(!(node.text?.toString()?.equals(text,true)==true||node.contentDescription?.toString()?.equals(text,true)==true))return false
            if(!rootOnly)return true
            var parent=node.parent
            while(parent!=null){if(parent.viewIdResourceName?.endsWith("/roots_list")==true)return true;parent=parent.parent}
            return false
        }
        var previous=android.graphics.Rect();var stableSince=0L;var selected:AccessibilityNodeInfo?=null
        try{ui.waitUntil(15000){
            val node=systemNodes().firstOrNull(::matches)
            node?.refresh()
            val bounds=android.graphics.Rect();node?.getBoundsInScreen(bounds)
            val time=android.os.SystemClock.uptimeMillis()
            if(bounds!=previous){previous=android.graphics.Rect(bounds);stableSince=time}
            selected=node
            node!=null&&!bounds.isEmpty&&time-stableSince>=150
        }
        }catch(e:ComposeTimeoutException){
            val folder=File(instrumentation.targetContext.getExternalFilesDir(null),"phase-b-p13-system-failure").apply{mkdirs()}
            File(folder,"picker.txt").writeText("Missing: $text\n"+systemNodes().joinToString("\n"){it.text.toString()+" | "+it.contentDescription+" | "+it.viewIdResourceName})
            val bitmap=instrumentation.uiAutomation.takeScreenshot();File(folder,"picker.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle();throw e
        }
        val node=selected!!
        val bounds=android.graphics.Rect();node.getBoundsInScreen(bounds)
        println("System touch $text at $bounds")
        val command=if(long)"input swipe ${bounds.centerX()} ${bounds.centerY()} ${bounds.centerX()} ${bounds.centerY()} 650"else "input tap ${bounds.centerX()} ${bounds.centerY()}"
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use{it.readBytes()}
    }
    private fun pickFiles(names:List<String>) {
        ui.waitUntil(15000){systemNodes().any{it.packageName?.toString()?.contains("documentsui")==true}}
        if(systemNodes().none{it.text?.toString()==names.first()}) {
            val navigation=systemNodes().firstOrNull{it.contentDescription?.toString()?.contains("navigation",true)==true||it.contentDescription?.toString()?.contains("roots",true)==true||it.viewIdResourceName?.endsWith("/toolbar") == true&&it.isClickable}
            if(navigation!=null)navigation.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            else error("Document navigation missing: "+systemNodes().mapNotNull{it.text?.toString()?:it.contentDescription?.toString()}.joinToString())
            systemClick("Yohaku Verification Files",rootOnly=true)
        }
        ui.waitUntil(15000){systemNodes().any{it.text?.toString()==names.first()}}
        if(systemNodes().any{it.contentDescription?.toString()=="List view"})systemClick("List view")
        instrumentation.uiAutomation.waitForIdle(300,5000)
        systemClick(names.first(),long=names.size>1)
        if(names.size>1) {
            ui.waitUntil(15000){systemNodes().any{it.text?.toString()=="1 selected"}}
            names.drop(1).forEach{systemClick(it)}
            println("Picker selection: "+systemNodes().mapNotNull{it.text?.toString()?:it.contentDescription?.toString()}.joinToString())
            systemClick("Select")
        }
        ui.waitUntil(15000){systemNodes().none{it.packageName?.toString()?.contains("documentsui")==true}&&!vm.busy.value}
    }
    private fun tap(text:String) {
        ui.waitUntil(10000){ui.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()&&!vm.busy.value}
        ui.onNode(hasText(text) and hasClickAction()).performScrollTo().performClick()
    }
    private fun openFile(id:String) {
        ui.waitUntil(10000){!vm.busy.value&&android.view.inspector.WindowInspector.getGlobalWindowViews().size==2}
        val node=ui.onNodeWithTag("media-file-$id");node.performScrollTo();ui.waitForIdle()
        val density=instrumentation.targetContext.resources.displayMetrics.density
        val header=ui.onNode(hasTestTag("sheet-header") and hasAnyAncestor(hasAnyDescendant(hasTestTag("media-file-$id")))).getUnclippedBoundsInRoot().bottom.value
        val scroll=ui.onNode(hasTestTag("sheet-scroll") and hasAnyDescendant(hasTestTag("media-file-$id")))
        val top=node.getUnclippedBoundsInRoot().top.value
        if(top<header+8)scroll.performSemanticsAction(SemanticsActions.ScrollBy){it(0f,-(header+8-top)*density)}
        ui.waitForIdle()
        val bottom=node.getUnclippedBoundsInRoot().bottom.value
        val viewport=scroll.getUnclippedBoundsInRoot().bottom.value
        if(bottom>viewport)scroll.performSemanticsAction(SemanticsActions.ScrollBy){it(0f,(bottom-viewport+8)*density)}
        ui.waitForIdle();node.performClick()
    }
    private fun closePreview()=ui.onNode(hasTestTag("sheet-close") and hasAnyAncestor(hasAnyDescendant(hasTestTag("media-preview-meta")))).performClick()
    private fun shot(name:String) {
        if (!visualEnabled()) return
        val scroll=ui.onNode(hasTestTag("sheet-scroll") and hasAnyDescendant(hasTestTag("media-preview-meta")))
        scroll.performSemanticsAction(SemanticsActions.ScrollBy){it(0f,-100000f)};ui.waitForIdle()
        val file=File(instrumentation.targetContext.getExternalFilesDir(null),"phase-b-p13/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use{ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    private fun externalShot(name:String) {
        if (!visualEnabled()) return
        ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().size==3}
        ui.onNode(hasTestTag("sheet-scroll") and hasAnyDescendant(hasTestTag("media-preview-meta"))).performSemanticsAction(SemanticsActions.ScrollBy){it(0f,-100000f)}
        ui.waitForIdle();instrumentation.uiAutomation.waitForIdle(400,5000)
        val bitmap=instrumentation.uiAutomation.takeScreenshot();val file=File(instrumentation.targetContext.getExternalFilesDir(null),"phase-b-p13-external/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun chooserAlwaysWrapsGetContentAndParsesMultipleOrSingleUris() {
        val context=instrumentation.targetContext
        val first=android.net.Uri.parse("content://test/one");val second=android.net.Uri.parse("content://test/two")
        for(mime in listOf("image/*","*/*")) {
            val contract=MediaPickerContract(mime,"选择应用 / アプリを選択")
            val chooser=contract.createIntent(context,Unit)
            assertEquals(android.content.Intent.ACTION_CHOOSER,chooser.action)
            @Suppress("DEPRECATION") val request=chooser.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)!!
            assertEquals(android.content.Intent.ACTION_GET_CONTENT,request.action);assertEquals(mime,request.type)
            assertTrue(request.hasCategory(android.content.Intent.CATEGORY_OPENABLE));assertTrue(request.getBooleanExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE,false))
            val multiple=android.content.Intent().apply{clipData=android.content.ClipData.newRawUri("one",first).apply{addItem(android.content.ClipData.Item(second))}}
            assertEquals(listOf(first,second),contract.parseResult(android.app.Activity.RESULT_OK,multiple))
            assertEquals(listOf(first),contract.parseResult(android.app.Activity.RESULT_OK,android.content.Intent().setData(first)))
            assertTrue(contract.parseResult(android.app.Activity.RESULT_CANCELED,multiple).isEmpty())
        }
    }
    @Test fun photoDefaultToggleProgressAndBatchSummaryAcrossThemes() {
        val context=instrumentation.targetContext
        val previous=runBlocking{vm.preferences.photoSaveOriginal.first()}
        fun source(name:String,mime:String,bytes:ByteArray):android.net.Uri {
            val file=File(context.filesDir,"attachments/ui-source-$name").apply{writeBytes(bytes)}
            return androidx.core.content.FileProvider.getUriForFile(context,context.packageName+".files",file,name).buildUpon().appendQueryParameter("mime",mime).build()
        }
        val png=java.io.ByteArrayOutputStream().also{out->Bitmap.createBitmap(40,20,Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.GREEN);compress(Bitmap.CompressFormat.PNG,100,out);recycle()}}.toByteArray()
        try {
            for((theme,language) in testLooks(this@MediaFlowTest)) {
                runBlocking{vm.preferences.setPhotoSaveOriginal(false)}
                val book=runBlocking {
                    val id=repo.createBook("照片 $theme $language",Currency.JPY,theme,language);repo.updateBook(repo.dao.book(id)!!.copy(initialized=true))
                    repo.saveAccount(id,"现金",AccountType.CASH,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01");repo.saveCategory(id,"生活",EntryKind.EXPENSE,null);vm.preferences.select(id);id
                }
                ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value&&vm.photoSaveOriginal.value==false}
                ui.runOnIdle{vm.navigate("settings")}
                ui.onNodeWithTag("settings-photo-storage").performScrollTo().performClick()
                ui.onNodeWithTag("photo-storage-true").performScrollTo().performClick()
                ui.waitUntil(10000){vm.photoSaveOriginal.value==true&&!vm.busy.value}
                if(visualEnabled()) {
                    ui.waitForIdle();android.os.SystemClock.sleep(350) // Compose idleness does not wait for the dialog window fade.
                    val bitmap=instrumentation.uiAutomation.takeScreenshot();val folder=File(context.filesDir,"verification/media-import").apply{mkdirs()}
                    File(folder,"$theme-$language-setting.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
                }
                ui.onNodeWithTag("sheet-close").performClick()
                val snapshot=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
                ui.runOnIdle{vm.startEntry(snapshot);vm.changeEntry(book,vm.entry.value!!.copy(categoryId=snapshot.categories.first().id));vm.navigate("entry")}
                val toggle=ui.onNodeWithTag("media-preserve-original")
                toggle.performScrollTo().assertIsOn()
                ui.waitUntil(10000){!vm.busy.value}
                val image=source("photo.png","image/png",png)
                ui.runOnIdle{vm.importFiles(context,book,listOf(image),true)}
                ui.waitUntil(10000){!vm.busy.value&&vm.entry.value?.receiptFileIds?.size==1}
                val original=runBlocking{repo.dao.file(book,vm.entry.value!!.receiptFileIds.single())!!}
                assertArrayEquals(png,repo.mediaFile(original).readBytes())
                toggle.performScrollTo().performClick().assertIsOff()
                assertTrue(runBlocking{vm.preferences.photoSaveOriginal.first()})
                val bad=source("broken.png","image/png",byteArrayOf(1,2,3))
                val missing=source("missing.txt","text/plain",byteArrayOf(0));File(context.filesDir,"attachments/ui-source-missing.txt").delete()
                runBlocking{repo.mediaWrites.lock()}
                try {
                    ui.runOnIdle{vm.importFiles(context,book,listOf(image,missing,bad,image),false)}
                    ui.waitUntil(10000){vm.mediaImportProgress.value?.current==1}
                    ui.onNodeWithTag("media-import-progress").performScrollTo().assertTextEquals(if(language=="ja")"取り込み中 1/4"else"正在导入 1/4")
                    if(visualEnabled()) {
                        val bitmap=instrumentation.uiAutomation.takeScreenshot();val folder=File(context.filesDir,"verification/media-import").apply{mkdirs()}
                        File(folder,"$theme-$language-progress.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
                    }
                }finally{repo.mediaWrites.unlock()}
                ui.waitUntil(15000){!vm.busy.value&&vm.entry.value?.attachmentIds?.size==3}
                val files=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}.files.filter{it.id in vm.entry.value!!.attachmentIds}
                assertEquals(2,files.count{it.mime=="image/jpeg"});assertEquals(1,files.count{it.mime=="image/png"})
                val notice=vm.notice.value!!;assertTrue(notice.zh.contains("已导入 3 个文件"));assertTrue(notice.zh.contains("1 张图片无法优化"));assertTrue(notice.zh.contains("失败 1 个"))
                ui.onNodeWithText(if(language=="ja")notice.ja else notice.zh).assertExists()
                assertNull(vm.mediaImportProgress.value)
                ui.runOnIdle{vm.leaveEntry(book,false,"home")};ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value}
            }
        }finally{runBlocking{vm.preferences.setPhotoSaveOriginal(previous)};listOf("photo.png","broken.png","missing.txt").forEach{File(context.filesDir,"attachments/ui-source-$it").delete()}}
    }
    @Test fun cameraCapturesOneReceiptThroughImportAndRemovesTemporaryFile() {
        val context=instrumentation.targetContext;val cache=File(context.cacheDir,"camera")
        cameraPhoto=java.io.ByteArrayOutputStream().also{out->Bitmap.createBitmap(2400,4800,Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.WHITE);compress(Bitmap.CompressFormat.JPEG,95,out);recycle()}}.toByteArray()
        for((theme,language) in testLooks(this@MediaFlowTest)) {
            val book=runBlocking {
                val id=repo.createBook("拍照 $theme $language",Currency.JPY,theme,language);repo.updateBook(repo.dao.book(id)!!.copy(initialized=true))
                repo.saveAccount(id,"现金",AccountType.CASH,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01");repo.saveCategory(id,"生活",EntryKind.EXPENSE,null);vm.preferences.select(id);id
            }
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value&&vm.photoSaveOriginal.value!=null}
            val snapshot=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            ui.runOnIdle{vm.startEntry(snapshot);vm.changeEntry(book,vm.entry.value!!.copy(categoryId=snapshot.categories.first().id));vm.navigate("entry")}
            ui.waitUntil(10000){!vm.busy.value}
            ui.runOnIdle{vm.setMediaImportOption(vm.entry.value!!.mediaSession,false)}
            ui.onNodeWithTag("media-take-receipt").performScrollTo().performClick()
            ui.waitUntil(15000){!vm.busy.value&&vm.entry.value?.receiptFileIds?.size==1}
            val stored=runBlocking{repo.dao.file(book,vm.entry.value!!.receiptFileIds.single())!!}
            assertEquals("image/jpeg",stored.mime);assertTrue(stored.originalName.matches(Regex("IMG_\\d{8}_\\d{6}\\.jpg")))
            val size=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}.also{android.graphics.BitmapFactory.decodeFile(repo.mediaFile(stored).path,it)}
            assertEquals(2000,minOf(size.outWidth,size.outHeight))
            assertTrue(cache.listFiles().isNullOrEmpty())
            if(visualEnabled()) {
                ui.onNodeWithTag("media-take-receipt").performScrollTo();ui.waitForIdle()
                val bitmap=instrumentation.uiAutomation.takeScreenshot();val folder=File(context.filesDir,"verification/media-import").apply{mkdirs()}
                File(folder,"$theme-$language-camera.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            }
            val photo=cameraPhoto;cameraPhoto=null
            ui.onNodeWithTag("media-take-receipt").performScrollTo().performClick();ui.waitForIdle()
            assertEquals(1,vm.entry.value!!.receiptFileIds.size);assertTrue(cache.listFiles().isNullOrEmpty())
            cameraPhoto=photo
            ui.runOnIdle{vm.leaveEntry(book,false,"home")};ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value}
        }
    }
    @Test fun actualDocumentsImagesAudioAndSeparateUidReadOnlyViewer() {
        org.junit.Assume.assumeTrue("Requires system=true", testOption("system"))
        for((theme,language) in testLooks(this@MediaFlowTest)) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            println("Media combination: $theme $language")
            val book=runBlocking {
                val id=repo.createBook("媒体 $theme $language",Currency.JPY,theme=theme,language=language)
                repo.updateBook(repo.dao.book(id)!!.copy(initialized=true))
                val account=repo.saveAccount(id,"现金",AccountType.CASH,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
                val category=repo.saveCategory(id,"生活",EntryKind.EXPENSE,null)
                repo.saveEntry(id,EntryDraft(accountId=account,categoryId=category,amount="1000",date="2026-10-14",merchant="有小票的一笔"));vm.preferences.select(id);id
            }
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book}
            val snapshot=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            ui.runOnIdle{vm.editEntry(snapshot,snapshot.events.single())}
            ui.onNodeWithTag("media-add-attachments").performScrollTo().performClick()
            pickFiles(listOf("receipt-1.png","tone.wav","notes.yohaku","broken.jpg","unknown.yohaku"))
            ui.waitUntil(15000){vm.entry.value?.attachmentIds?.size==5&&!vm.busy.value}
            ui.onNodeWithTag("media-add-receipts").performScrollTo().performClick()
            pickFiles(listOf("receipt-1.png","receipt-2.png"))
            ui.waitUntil(15000){vm.entry.value?.receiptFileIds?.size==2&&!vm.busy.value}
            val d=vm.entry.value!!
            ui.onNodeWithTag("entry-save-button").performScrollTo().performClick()
            ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value}
            val saved=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(1,saved.events.size);assertEquals(9000L,saved.balance(saved.accounts.single()));assertEquals(5,saved.entryAttachments(saved.events.single().id).size);assertEquals(2,saved.entryReceiptImages(saved.events.single().id).size)
            ui.runOnIdle{vm.editEntry(saved,saved.events.single())}
            val image=saved.files.first{it.id in d.attachmentIds&&it.originalName.startsWith("receipt-1.")}
            openFile(image.id)
            ui.waitUntil(10000){ui.onAllNodesWithTag("media-image").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("media-image").performTouchInput{pinch(center,center+androidx.compose.ui.geometry.Offset(5f,5f),center-androidx.compose.ui.geometry.Offset(70f,70f),center+androidx.compose.ui.geometry.Offset(70f,70f))}
            ui.waitUntil(10000){ui.onNodeWithTag("media-image").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription]!="100%"}
            tap(text("重置缩放","拡大をリセット"))
            ui.onNodeWithTag("media-image").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"100%"));shot("$theme-$language-image");closePreview()
            val audio=saved.files.first{it.id in d.attachmentIds&&it.mime=="audio/wav"}
            openFile(audio.id)
            ui.waitUntil(10000){ui.onAllNodesWithTag("media-audio-play").fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Disabled)==null&&ui.onAllNodesWithTag("media-audio-play").fetchSemanticsNodes().isNotEmpty()}
            tap(text("播放","再生"))
            ui.waitUntil(10000){ui.onNodeWithTag("media-audio-position").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].first().text.startsWith("1s")}
            tap(text("暂停","一時停止"))
            val currentPosition=ui.onNodeWithTag("media-audio-progress").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo].current
            ui.onNodeWithTag("media-audio-progress").performTouchInput{swipe(androidx.compose.ui.geometry.Offset(width*currentPosition/8000f,center.y),androidx.compose.ui.geometry.Offset(width*.6f,center.y),300)}
            ui.onNodeWithTag("media-audio-position").assertTextEquals("4s / 8s")
            tap(text("播放","再生"))
            ui.waitUntil(10000){ui.onNodeWithTag("media-audio-position").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].first().text.startsWith("5s")}
            tap(text("暂停","一時停止"));shot("$theme-$language-audio");closePreview()
            val broken=saved.files.first{it.id in d.attachmentIds&&it.originalName=="broken.jpg"}
            openFile(broken.id)
            ui.waitUntil(10000){ui.onAllNodesWithText(text("无法解码这张图片","この画像を読み込めません"),substring=true).fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("media-preview-error").assertTextEquals(text("无法解码这张图片","この画像を読み込めません"))
            ui.onNodeWithTag("media-external-open").assertIsEnabled();closePreview()
            val unknown=saved.files.first{it.id in d.attachmentIds&&it.originalName=="unknown.yohaku"}
            openFile(unknown.id)
            ui.onNodeWithTag("media-external-open").performScrollTo().performClick()
            ui.waitUntil(10000){ui.onAllNodesWithText(text("没有可以打开此格式的应用","この形式を開くアプリがありません"),substring=true).fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("media-preview-error").assertTextEquals(text("没有可以打开此格式的应用","この形式を開くアプリがありません"))
            externalShot("$theme-$language-no-viewer")
            ui.onNodeWithTag("media-preview-back").performScrollTo().performClick()
            ui.waitUntil(10000){ui.onAllNodesWithTag("media-external-cover").fetchSemanticsNodes().isEmpty()}
            val document=saved.files.first{it.id in d.attachmentIds&&it.mime=="application/x-yohaku-verification"}
            openFile(document.id)
            ui.onNodeWithTag("media-external-cover").assertExists()
            externalShot("$theme-$language-document")
            ui.onNodeWithTag("media-external-open").performScrollTo().performClick()
            ui.waitUntil(10000){systemNodes().any{it.contentDescription?.toString()=="media-verification-result"}}
            val result=systemNodes().first{it.contentDescription?.toString()=="media-verification-result"}.text.toString()
            assertTrue(result.contains("READ_OK"));assertTrue(result.contains("WRITE_BLOCKED=true"));assertTrue(result.contains("SHA256="+document.checksum));assertTrue(result.contains("NAME=notes.yohaku"));assertNotEquals(android.os.Process.myUid(),result.substringAfter("UID=").trim().toInt());println(result)
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input keyevent 4")).use{it.readBytes()}
            ui.waitUntil(10000){systemNodes().any{it.packageName?.toString()==instrumentation.targetContext.packageName}}
            ui.waitForIdle();closePreview()
            ui.runOnIdle{vm.leaveEntry(book,false,"ledger")}
            ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value}
        }
    }
}
