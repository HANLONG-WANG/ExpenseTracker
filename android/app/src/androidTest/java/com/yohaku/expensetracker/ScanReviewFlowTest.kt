package com.yohaku.expensetracker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.repository.AppPreferences
import com.yohaku.expensetracker.receipt.scan.*
import com.yohaku.expensetracker.ui.features.scan.*
import com.yohaku.expensetracker.ui.theme.YohakuTheme
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real process/render over stage-1 synthetic assets; only camera hardware is replaced. */
class ScanReviewFlowTest {
    @get:Rule val ui = createAndroidComposeRule<ScanFlowTestActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "review-test-${UUID.randomUUID()}").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = AppPreferences(context, PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "prefs.preferences_pb") }))
    private val before = File(context.cacheDir, "receipt-capture").listFiles()?.toSet().orEmpty()
    private val sources = mutableListOf<StaticReceiptSource>()
    private val results = mutableListOf<ScanFlowResult>()
    private var cancelled = 0
    private var open by mutableStateOf(true)
    private var theme by mutableStateOf("magazine")
    private var language by mutableStateOf("zh")
    private var request by mutableStateOf<ScanFlowRequest?>(null)
    private fun asset(name: String) = InstrumentationRegistry.getInstrumentation().context.assets.open("receipt-scanner/$name").use { it.readBytes() }
    private fun input(name: String = "flat.jpg") = File(root, name).apply { writeBytes(asset(name)) }
    private fun directories() = File(context.cacheDir, "receipt-capture").listFiles()?.filter { it !in before }.orEmpty()
    private fun session() = directories().single { File(it, "original.jpg").isFile }
    private fun install(initial: ScanFlowRequest) {
        request = initial
        ui.runOnIdle {
            ScanFlowTestActivity.content = {
                YohakuTheme(theme, language) {
                    if (open) ScanFlowHost(requireNotNull(request), prefs, { results.add(it); open = false }, { cancelled++; open = false },
                        sourceFactory = { StaticReceiptSource(it, asset("flat.jpg"), false, false).also(sources::add) },
                        permissionOverride = CameraPermission.GRANTED,
                        motionFactory = { _, _ -> object : ScanMotionSource {
                            override fun start() = Unit
                            override fun isStill(nowMs: Long) = true
                            override fun close() = Unit
                        } })
                }
            }
        }
    }
    private fun waitFor(tag: String) {
        ui.waitUntil(180_000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() || ui.onAllNodesWithTag("scan-review-error").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("scan-review-error").assertDoesNotExist()
        ui.onNodeWithTag(tag).assertIsDisplayed()
    }
    private fun result() { waitFor("scan-result"); ui.onNodeWithTag("scan-use").assertIsEnabled() }
    private fun contour() = ui.onNodeWithTag("scan-contour-editor").fetchSemanticsNode().config[ScanContourValue]
    private fun moveLeftMiddle(held: Boolean = false): ScanContour {
        val prior = contour(); val p = prior.left[2]
        ui.onNodeWithTag("scan-contour-editor").performSemanticsAction(MoveScanHandle) { action ->
            assertTrue(action(2, (p.x + prior.width * .06).toFloat(), p.y.toFloat(), held))
        }
        return contour().also { assertNotEquals(prior, it) }
    }
    private fun shoot() {
        waitFor("scan-shutter")
        ui.waitUntil(30_000) { ui.onAllNodes(hasTestTag("scan-shutter") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("scan-shutter").performClick()
    }

    @After fun cleanup() {
        ui.runOnIdle { ScanFlowTestActivity.content = {}; sources.forEach { it.close() } }
        // Drain the single native worker before removing its files.
        runBlocking { withContext(scanReviewWorker) {} ; scope.coroutineContext[Job]!!.cancelAndJoin() }
        directories().forEach(ScanTemporaryFiles::discard)
        root.deleteRecursively()
    }

    @Test fun existingImageProcessesAndUseTransfersJpegAndDeletesOriginalCopy() {
        val borrowed = input()
        install(ScanFlowRequest("draft-1", borrowed, ScanPhotoSource.EXISTING_IMAGE))
        result()
        val directory = session()
        val originalResult = File(directory, "automatic.jpg").readBytes()
        ui.onNodeWithTag("scan-review-leave").assertTextContains("取消", substring = true)
        ui.onNodeWithTag("scan-use").performClick()
        ui.waitUntil(10_000) { results.size == 1 }
        val output = results.single()
        assertTrue(output.processed); assertEquals("draft-1", output.target)
        assertArrayEquals(originalResult, output.file.readBytes())
        assertNotNull(BitmapFactory.decodeFile(output.file.path))
        assertFalse(directory.exists()); assertTrue(borrowed.exists()); assertEquals(0, cancelled)
    }

    @Test fun cameraRetakeReturnsToCameraAndUnprocessedCaptureBypassesReview() {
        install(ScanFlowRequest("record-42"))
        shoot(); result()
        val directory = session()
        ui.onNodeWithTag("scan-review-leave").assertTextContains("重拍", substring = true).performClick()
        waitFor("scan-camera")
        assertFalse(directory.exists()); assertTrue(results.isEmpty()); assertEquals(0, cancelled)
        runBlocking { prefs.setScanAutoProcess(false) }
        ui.onNode(isToggleable() and hasAnyAncestor(hasTestTag("scan-auto-process"))).assertIsOff()
        shoot()
        ui.waitUntil(10_000) { results.size == 1 }
        assertFalse(results.single().processed)
        assertArrayEquals(asset("flat.jpg"), results.single().file.readBytes())
        ui.onNodeWithTag("scan-review").assertDoesNotExist()
        assertTrue(directories().flatMap { it.listFiles().orEmpty().toList() }.none { it.name == "original.jpg" })
    }

    @Test fun dragReprocessChangesOutputCancelEditKeepsPreviousAndRestoreIsExact() {
        install(ScanFlowRequest("edit", input(), ScanPhotoSource.EXISTING_IMAGE))
        result()
        val directory = session(); val automatic = File(directory, "automatic.jpg").readBytes()
        ui.onNodeWithTag("scan-edit").performClick(); waitFor("scan-contour-editor")
        moveLeftMiddle()
        ui.onNodeWithTag("scan-render").performClick(); result()
        val edited = directory.listFiles()!!.single { it.name.startsWith("edited-") }.readBytes()
        assertFalse(automatic.contentEquals(edited))
        ui.onNodeWithTag("scan-edit").performClick()
        val accepted = contour(); moveLeftMiddle()
        ui.onNodeWithTag("scan-cancel-edit").performClick(); result()
        ui.onNodeWithTag("scan-edit").performClick(); assertEquals(accepted, contour())
        ui.onNodeWithTag("scan-restore").performClick(); result()
        ui.onNodeWithTag("scan-use").performClick()
        ui.waitUntil(10_000) { results.size == 1 }
        assertArrayEquals(automatic, results.single().file.readBytes())
    }

    @Test fun noReceiptStartsInAdjustmentAndCancelDeletesTemporaryFiles() {
        val borrowed = input("no-receipt.png")
        install(ScanFlowRequest("empty", borrowed, ScanPhotoSource.EXISTING_IMAGE))
        waitFor("scan-contour-editor")
        ui.onNodeWithTag("scan-review-hint").assertTextContains("没找到小票，请拖动边框", substring = true)
        ui.onNodeWithTag("scan-restore").assertIsNotEnabled()
        val directory = session()
        val contour = contour()
        assertEquals(ScanContour.centered(contour.width, contour.height), contour)
        ui.onNodeWithTag("scan-cancel-edit").performClick()
        ui.waitUntil(10_000) { cancelled == 1 }
        assertFalse(directory.exists()); assertTrue(borrowed.exists()); assertTrue(results.isEmpty())
    }

    @Test fun activityRecreationKeepsEditingContourAndOriginalPhoto() {
        install(ScanFlowRequest("restore", input(), ScanPhotoSource.EXISTING_IMAGE))
        result(); ui.onNodeWithTag("scan-edit").performClick()
        val edited = moveLeftMiddle()
        val directory = session()
        ui.activityRule.scenario.recreate()
        waitFor("scan-contour-editor")
        assertEquals(edited, contour()); assertEquals(directory, session())
        ui.onNodeWithTag("scan-render").performClick(); result()
        ui.onNodeWithTag("scan-use").performClick()
        ui.waitUntil(10_000) { results.size == 1 }
        assertTrue(results.single().file.isFile); assertFalse(directory.exists())
    }

    @Test fun reviewAdjustmentMagnifierAndNoDetectionAcrossLooks() {
        install(ScanFlowRequest("visual", input(), ScanPhotoSource.EXISTING_IMAGE))
        result()
        for ((t, l) in testLooks(this)) {
            ui.runOnIdle { theme = t; language = l }
            ui.onNodeWithTag("scan-use").assertIsDisplayed()
            shot("$t-$l-result")
            ui.onNodeWithTag("scan-edit").performClick()
            moveLeftMiddle(held = true)
            assertTrue(ui.onNodeWithTag("scan-contour-editor").fetchSemanticsNode().config[ScanMagnifierVisible])
            ui.onNodeWithTag("scan-render").assertIsDisplayed()
            ui.onNodeWithTag("scan-cancel-edit").assertIsDisplayed()
            shot("$t-$l-adjustment")
            ui.onNodeWithTag("scan-cancel-edit").performClick()
        }
        ui.onNodeWithTag("scan-review-close").performClick()
        ui.waitUntil { cancelled == 1 }
        val noReceipt = input("no-receipt.png")
        ui.runOnIdle { request = ScanFlowRequest("visual-empty", noReceipt, ScanPhotoSource.EXISTING_IMAGE); open = true }
        waitFor("scan-contour-editor")
        for ((t, l) in testLooks(this)) {
            ui.runOnIdle { theme = t; language = l }
            ui.onNodeWithTag("scan-review-hint").assertTextContains(if (l == "ja") "レシートが見つかりません" else "没找到小票", substring = true)
            ui.onNodeWithTag("scan-render").assertIsDisplayed()
            ui.onNodeWithTag("scan-cancel-edit").assertIsDisplayed()
            shot("$t-$l-not-detected")
        }
        ui.onNodeWithTag("scan-cancel-edit").performClick()
        ui.waitUntil { cancelled == 2 }
    }

    @Test fun processingAndFailurePresentationAcrossLooks() {
        var failed by mutableStateOf(false)
        ui.runOnIdle {
            ScanFlowTestActivity.content = {
                YohakuTheme(theme, language) {
                    ScanReviewPage(ScanReviewState(File(root, "original.jpg").path), images = null,
                        busy = !failed, failed = failed, source = ScanPhotoSource.CAMERA,
                        onUse = {}, onLeave = {}, onCancel = {}, onChange = {}, onEdit = {},
                        onRender = {}, onRestore = {}, onRetry = {}, onCancelEdit = {})
                }
            }
        }
        ui.mainClock.autoAdvance = false
        for ((t, l) in testLooks(this)) {
            ui.runOnIdle { theme = t; language = l; failed = false }
            ui.mainClock.advanceTimeBy(160)
            ui.onNodeWithTag("scan-review-processing").assertIsDisplayed()
                .assertTextContains(if (l == "ja") "補正中" else "正在处理", substring = true)
            ui.onNodeWithTag("scan-use").assertIsNotEnabled()
            ui.onNodeWithTag("scan-result-frame").assertHeightIsAtLeast(200.dp)
            shot("$t-$l-processing")
            ui.runOnIdle { failed = true }
            ui.mainClock.advanceTimeByFrame()
            ui.onNodeWithTag("scan-review-retry").assertIsDisplayed()
            shot("$t-$l-processing-error")
        }
        ui.mainClock.autoAdvance = true
    }

    private fun shot(name: String) {
        if (!visualEnabled()) return
        val suffix = InstrumentationRegistry.getArguments().getString("captureSuffix", "")
        val directory = File(context.filesDir, "verification/receipt-review$suffix").apply { mkdirs() }
        ui.onNodeWithTag("scan-review").captureToImage().asAndroidBitmap().let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
