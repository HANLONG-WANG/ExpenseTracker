package com.yohaku.expensetracker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.widget.ImageView
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.LifecycleOwner
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.repository.AppPreferences
import com.yohaku.expensetracker.receipt.scan.ScanPoint
import com.yohaku.expensetracker.ui.features.scan.*
import com.yohaku.expensetracker.ui.theme.YohakuTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.opencv.core.MatOfByte
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Real preview inference over synthetic assets; no camera permission, hardware, or personal photos.
 * The same production host owns Back, preferences, motion gate, capture, cleanup and fullscreen UI.
 */
class ScanCameraFlowTest {
    @get:Rule val ui = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "scan-test-${UUID.randomUUID()}").apply { mkdirs() }
    private val preferenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val storage = PreferenceDataStoreFactory.create(scope = preferenceScope, produceFile = { File(root, "app.preferences_pb") })
    private val prefs = AppPreferences(context, storage)
    private val photo by lazy { InstrumentationRegistry.getInstrumentation().context.assets.open("receipt-scanner/flat.jpg").use { it.readBytes() } }
    private val sources = mutableListOf<StaticReceiptSource>()
    private val transferred = mutableListOf<File>()
    private val still: (Context, () -> Unit) -> ScanMotionSource = { _, _ -> object : ScanMotionSource {
        override fun start() = Unit
        override fun isStill(nowMs: Long) = true
        override fun close() = Unit
    } }
    private fun source(c: Context, infer: Boolean = false, deferCapture: Boolean = false): StaticReceiptSource =
        StaticReceiptSource(c, photo, infer, deferCapture).also { sources.add(it) }

    @After fun cleanup() {
        ui.runOnIdle { sources.forEach { it.close() } }
        transferred.forEach { it.parentFile!!.deleteRecursively() }
        runBlocking { preferenceScope.coroutineContext[Job]!!.cancelAndJoin() }
        root.deleteRecursively()
    }

    private fun toggle(tag: String) = ui.onNode(isToggleable() and hasAnyAncestor(hasTestTag(tag)))
    private fun waitForSource() {
        ui.waitUntil(30_000) { sources.lastOrNull()?.let { it.started && !it.closed } == true }
        ui.waitUntil(10_000) { ui.onAllNodes(hasTestTag("scan-shutter") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun staticReceiptShowsContourHintsAndAutomaticProgressAcrossLooks() {
        var theme by mutableStateOf("magazine")
        var language by mutableStateOf("zh")
        var captured = 0
        ui.setContent {
            YohakuTheme(theme, language) {
                ScanCameraHost(ScanCameraRequest("test-draft"), prefs, {}, { f, _ -> transferred.add(f); captured++ },
                    sourceFactory = { source(it, infer = true) }, permissionOverride = CameraPermission.GRANTED, motionFactory = still)
            }
        }
        waitForSource()
        ui.waitUntil(90_000) { sources.single().inferred != null || sources.single().failure != null }
        sources.single().failure?.let { throw AssertionError("Static preview failed", it) }
        val source = sources.single()
        assertTrue(source.inferred!!.outline.size > 4)
        assertEquals(setOf("CPU"), source.inferred!!.actualEps.values.toSet())
        for ((t, l) in testLooks(this)) {
            ui.runOnIdle { theme = t; language = l; source.emit(ScanHint.STEADY) }
            ui.onNodeWithTag("scan-hint").assertTextEquals(if (l == "ja") "動かさずにお待ちください" else "拿稳")
            ui.onNodeWithTag("scan-outline").assertContentDescriptionEquals("contour:${source.inferred!!.outline.size}")
            shot("$t-$l-hint")
            runBlocking { prefs.setScanAutoCapture(true) }
            toggle("scan-auto-capture").assertIsOn()
            ui.runOnIdle { source.emit(ScanHint.NONE) }
            ui.runOnIdle { source.emit(ScanHint.NONE, timestamp = source.lastTimestamp + 400) }
            ui.onNodeWithTag("scan-auto-progress").assertRangeInfoEquals(androidx.compose.ui.semantics.ProgressBarRangeInfo(.5f, 0f..1f))
            ui.onNodeWithTag("scan-hint").assertDoesNotExist()
            shot("$t-$l-progress")
            runBlocking { prefs.setScanAutoCapture(false) }
            toggle("scan-auto-capture").assertIsOff()
        }
        assertEquals(0, captured)
    }

    @Test fun switchesPersistAcrossReopeningAndManualShutterExportsBothProcessingModes() {
        var open by mutableStateOf(true)
        val outputs = mutableListOf<Boolean>()
        ui.setContent {
            YohakuTheme("magazine", "zh") {
                if (open) ScanCameraHost(ScanCameraRequest("record-42"), prefs, { open = false }, { file, process ->
                    transferred.add(file); outputs.add(process); open = false
                }, sourceFactory = { source(it) }, permissionOverride = CameraPermission.GRANTED, motionFactory = still)
            }
        }
        waitForSource()
        toggle("scan-auto-capture").assertIsOff().performClick()
        toggle("scan-auto-process").assertIsOn().performClick()
        ui.waitUntil { runBlocking { prefs.scanAutoCapture.first() && !prefs.scanAutoProcess.first() } }
        ui.onNodeWithTag("scan-back").performClick()
        ui.runOnIdle { assertTrue(sources.single().closed); open = true }
        waitForSource()
        toggle("scan-auto-capture").assertIsOn()
        toggle("scan-auto-process").assertIsOff()
        ui.onNodeWithTag("scan-shutter").performClick()
        ui.waitUntil { outputs.size == 1 }
        assertEquals(listOf(false), outputs)
        assertArrayEquals(photo, transferred[0].readBytes())
        assertTrue(sources.last().closed)
        ui.runOnIdle { open = true }
        waitForSource()
        toggle("scan-auto-process").performClick()
        ui.waitUntil { runBlocking { prefs.scanAutoProcess.first() } }
        ui.onNodeWithTag("scan-shutter").performClick()
        ui.waitUntil { outputs.size == 2 }
        assertEquals(listOf(false, true), outputs)
        assertArrayEquals(photo, transferred[1].readBytes())
    }

    @Test fun permissionDenialPermanentDenialAndNoCameraShowActionableUi() {
        var permission by mutableStateOf(CameraPermission.DENIED)
        ui.setContent {
            YohakuTheme("journal", "ja") {
                ScanCameraHost(ScanCameraRequest("permission-test"), prefs, {}, { _, _ -> fail("Unexpected capture") },
                    sourceFactory = { source(it) }, permissionOverride = permission, motionFactory = still)
            }
        }
        ui.onNodeWithTag("scan-permission").assertIsDisplayed()
        ui.onNodeWithTag("scan-request-permission").assertIsDisplayed()
        assertTrue(sources.isEmpty())
        ui.runOnIdle { permission = CameraPermission.PERMANENTLY_DENIED }
        ui.onNodeWithTag("scan-open-settings").assertIsDisplayed()
        ui.onNodeWithTag("scan-request-permission").assertDoesNotExist()
        ui.runOnIdle { permission = CameraPermission.GRANTED }
        waitForSource()
        ui.runOnIdle { sources.single().listener!!.failed(CameraIssue.NO_CAMERA) }
        ui.onNodeWithTag("scan-camera-error").assertTextContains("背面カメラ", substring = true)
        ui.onNodeWithTag("scan-shutter").assertIsNotEnabled()
    }

    @Test fun backReleasesSourceAndDeletesPendingAndLateCaptureFiles() {
        var open by mutableStateOf(true)
        var exits = 0
        var captures = 0
        ui.setContent {
            YohakuTheme("objects", "zh") {
                if (open) ScanCameraHost(ScanCameraRequest("cancel-test"), prefs, { open = false; exits++ }, { _, _ -> captures++ },
                    sourceFactory = { source(it, deferCapture = true) }, permissionOverride = CameraPermission.GRANTED, motionFactory = still)
            }
        }
        waitForSource()
        ui.onNodeWithTag("scan-shutter").performClick()
        val first = sources.single()
        val path = requireNotNull(first.pendingFile)
        assertTrue(path.exists())
        ui.onNodeWithTag("scan-back").performClick()
        ui.runOnIdle {
            assertTrue(first.closed); assertFalse(path.exists())
            first.completeCapture() // Simulate a file being written after cancellation.
            assertFalse(path.exists()); assertFalse(path.parentFile!!.exists())
            open = true
        }
        waitForSource()
        ui.onNodeWithTag("scan-shutter").performClick()
        val second = sources.last()
        Espresso.pressBack()
        ui.runOnIdle {
            assertTrue(second.closed)
            second.completeCapture()
            assertFalse(second.pendingFile!!.exists())
        }
        assertEquals(2, exits)
        assertEquals(0, captures)
    }

    @Test fun automaticCaptureUsesProductionGateAndCannotRepeat() {
        runBlocking { prefs.setScanAutoCapture(true) }
        var captures = 0
        ui.setContent {
            YohakuTheme("magazine", "zh") {
                ScanCameraHost(ScanCameraRequest("auto-test"), prefs, {}, { f, _ -> transferred.add(f); captures++ },
                    sourceFactory = { source(it) }, permissionOverride = CameraPermission.GRANTED, motionFactory = still)
            }
        }
        waitForSource(); toggle("scan-auto-capture").assertIsOn()
        val source = sources.single()
        ui.runOnIdle { source.emit(ScanHint.NONE) }
        ui.runOnIdle { source.emit(ScanHint.NONE, timestamp = source.lastTimestamp + 400) }
        ui.onNodeWithTag("scan-auto-progress").assertIsDisplayed()
        ui.runOnIdle { source.emit(ScanHint.NONE, timestamp = source.lastTimestamp + 400) }
        ui.waitUntil { captures == 1 }
        assertTrue(source.closed)
        ui.runOnIdle { source.emit(ScanHint.NONE, timestamp = source.lastTimestamp + 1000) }
        assertEquals(1, captures)
    }

    private fun shot(name: String) {
        if (!visualEnabled()) return
        val directory = File(context.filesDir, "verification/receipt-camera${InstrumentationRegistry.getArguments().getString("captureSuffix", "")}").apply { mkdirs() }
        ui.onNodeWithTag("scan-camera").captureToImage().asAndroidBitmap().let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}

/** Test-only static ImageView, with the exact file also returned by the replaceable shutter. */
internal class StaticReceiptSource(
    context: Context, private val jpeg: ByteArray, private val infer: Boolean, private val deferCapture: Boolean,
) : ScanCameraSource {
    override val view = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)) }
    private val main = ContextCompat.getMainExecutor(context)
    private val worker = Executors.newSingleThreadExecutor()
    private var processor: ScanFrameProcessor? = null
    var listener: ScanCameraListener? = null
    var started = false
    @Volatile var closed = false
    @Volatile var inferred: ScanCameraFrame? = null
    @Volatile var failure: Throwable? = null
    var pendingFile: File? = null
    private var captureCallback: ((Result<Unit>) -> Unit)? = null
    var lastTimestamp = 0L
    override fun start(owner: LifecycleOwner, listener: ScanCameraListener) {
        this.listener = listener
        view.doOnLayout {
            if (closed) return@doOnLayout
            listener.ready(true); started = true
            if (infer) worker.execute {
                try {
                    val engine = ScanFrameProcessor(context = view.context).also { processor = it }
                    val bytes = MatOfByte(*jpeg); val full = Imgcodecs.imdecode(bytes, Imgcodecs.IMREAD_COLOR); val small = Mat()
                    try {
                        Imgproc.resize(full, small, Size(624.0, 960.0))
                        val (preview, contour) = engine.analyze(small, SystemClock.elapsedRealtime())
                        val width = small.cols(); val height = small.rows()
                        main.execute {
                            if (!closed) {
                                val scale = maxOf(view.width.toDouble()/width, view.height.toDouble()/height)
                                val dx = (view.width - width*scale)/2; val dy = (view.height-height*scale)/2
                                inferred = ScanCameraFrame(contour.points.map { ScanPoint(((it.x*width+.5)*scale+dx)/view.width, ((it.y*height+.5)*scale+dy)/view.height) },
                                    scanHint(preview,width,height,contour.stable),SystemClock.elapsedRealtime(),5.5,preview.diagnostics.actualEps)
                                emit(inferred!!.hint)
                            }
                        }
                    } finally { small.release(); full.release(); bytes.release() }
                } catch (e: Throwable) { failure = e }
            }
        }
    }
    fun emit(hint: ScanHint, timestamp: Long = SystemClock.elapsedRealtime()) {
        lastTimestamp = timestamp
        // Non-inference scenarios test orchestration with an already-classified frame.
        val base = inferred ?: ScanCameraFrame(emptyList(), hint, timestamp, 5.5, mapOf("encoder" to "CPU", "decoder" to "CPU"))
        listener?.frame(base.copy(hint=hint, timestampMs=timestamp))
    }
    override fun capture(file: File, result: (Result<Unit>) -> Unit) {
        pendingFile = file; captureCallback = result
        file.writeBytes(jpeg)
        if (!deferCapture) completeCapture()
    }
    fun completeCapture() {
        val file = requireNotNull(pendingFile)
        file.parentFile!!.mkdirs(); file.writeBytes(jpeg)
        captureCallback?.invoke(Result.success(Unit)); captureCallback = null
    }
    override fun torch(enabled: Boolean) = Unit
    override fun close() {
        if (closed) return
        closed = true
        worker.execute { processor?.close(); processor = null }
        worker.shutdown()
    }
}
