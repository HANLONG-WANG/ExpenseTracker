package com.yohaku.expensetracker.ui.features.scan

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yohaku.expensetracker.BuildConfig
import com.yohaku.expensetracker.data.repository.AppPreferences
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/** Caller keeps the serialized Draft/Record target stable across recreation. */
data class ScanCameraRequest(val target: String)
internal enum class CameraPermission { GRANTED, DENIED, PERMANENTLY_DENIED }

/** Full-screen flow like FinancialFlow. Remove from composition on exit/capture.
 * onCaptured receives an EXIF-oriented JPEG in cache/receipt-capture; ownership transfers to the
 * caller (ScanFlow), which hands it to the importer until successful import/abandonment.
 */
@Composable fun ScanCameraFlow(request: ScanCameraRequest, preferences: AppPreferences, onBack: () -> Unit, onCaptured: (File, Boolean) -> Unit) {
    ScanCameraHost(request, preferences, onBack, onCaptured)
}

@Composable internal fun ScanCameraHost(
    request: ScanCameraRequest, preferences: AppPreferences, onBack: () -> Unit, onCaptured: (File, Boolean) -> Unit,
    sourceFactory: (Context) -> ScanCameraSource = { CameraXScanSource(it) },
    permissionOverride: CameraPermission? = null,
    motionFactory: (Context, () -> Unit) -> ScanMotionSource = { context, changed -> ScanMotionMonitor(context, changed) },
) {
    key(request) {
        Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            val window = (LocalView.current.parent as DialogWindowProvider).window
            DisposableEffect(window) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
                onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
            }
            ScanCameraPage(preferences, onBack, onCaptured, sourceFactory, permissionOverride, motionFactory)
        }
    }
}

private fun Context.activity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> error("Camera flow requires an Activity")
}

@Composable private fun ScanCameraPage(
    preferences: AppPreferences, onBack: () -> Unit, onCaptured: (File, Boolean) -> Unit,
    sourceFactory: (Context) -> ScanCameraSource, permissionOverride: CameraPermission?,
    motionFactory: (Context, () -> Unit) -> ScanMotionSource,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val permissionHistory = remember { context.getSharedPreferences("receipt-camera-permission", Context.MODE_PRIVATE) }
    fun readPermission(): CameraPermission = when {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> CameraPermission.GRANTED
        permissionHistory.getBoolean("requested", false) && !ActivityCompat.shouldShowRequestPermissionRationale(context.activity(), Manifest.permission.CAMERA) -> CameraPermission.PERMANENTLY_DENIED
        else -> CameraPermission.DENIED
    }
    val hasCamera = permissionOverride != null || remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA) }
    var permission by remember { mutableStateOf(readPermission()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = readPermission() }
    fun askPermission() {
        permissionHistory.edit().putBoolean("requested", true).apply()
        launcher.launch(Manifest.permission.CAMERA)
    }
    LaunchedEffect(Unit) { if (hasCamera && permissionOverride == null && permission != CameraPermission.GRANTED && !permissionHistory.getBoolean("requested", false)) askPermission() }
    DisposableEffect(owner, permissionOverride) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME && permissionOverride == null) permission = readPermission() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val currentPermission = permissionOverride ?: permission
    Column(Modifier.fillMaxSize().scanPaper().safeDrawingPadding().padding(horizontal = 16.dp).testTag("scan-camera")) {
        ScanHeading(word("扫描小票", "レシートを撮影"), "01", word("返回", "戻る"), "scan-back", onBack)
        Spacer(Modifier.height(12.dp))
        if (!hasCamera) {
            ScanFrame(Modifier.weight(1f).fillMaxWidth().padding(bottom = 16.dp)) {
                ScanStatus(word("此设备没有可用的后置相机", "この端末には使用できる背面カメラがありません"), Modifier.testTag("scan-camera-error"))
            }
        } else if (currentPermission != CameraPermission.GRANTED) {
            ScanFrame(Modifier.weight(1f).fillMaxWidth().padding(bottom = 16.dp)) {
                ScanStatus(word("允许使用相机，即可扫描小票。", "カメラを許可すると、レシートを撮影できます。"), Modifier.testTag("scan-permission"), action = {
                    if (currentPermission == CameraPermission.PERMANENTLY_DENIED) {
                        YButton(word("前往系统设置", "設定を開く"), {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                        }, Modifier.testTag("scan-open-settings"), primary = true)
                    } else YButton(word("允许相机 / 重试", "カメラを許可 / 再試行"), ::askPermission, Modifier.testTag("scan-request-permission"), primary = true)
                })
            }
        } else {
            var attempt by remember { mutableIntStateOf(0) }
            key(attempt) {
                val initialZoom by produceState<Boolean?>(null, preferences) { value = preferences.scanZoom2x.first() }
                initialZoom?.let { ScanCameraContent(preferences, it, sourceFactory, motionFactory, onCaptured, { attempt++ }) }
            }
        }
    }
}

@Composable private fun ColumnScope.ScanCameraContent(
    preferences: AppPreferences, initialZoom2x: Boolean, sourceFactory: (Context) -> ScanCameraSource,
    motionFactory: (Context, () -> Unit) -> ScanMotionSource,
    onCaptured: (File, Boolean) -> Unit, retry: () -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val t = LocalYohaku.current
    val scope = rememberCoroutineScope()
    val autoCapture by preferences.scanAutoCapture.collectAsState(initial = false)
    // Null prevents shooting before the stored preference has loaded.
    val autoProcess: Boolean? by preferences.scanAutoProcess.collectAsState(initial = null)
    val latestCaptured by rememberUpdatedState(onCaptured)
    var cameraFrame by remember { mutableStateOf<ScanCameraFrame?>(null) }
    var ready by remember { mutableStateOf(false) }
    var supports2x by remember { mutableStateOf(false) }
    var zoom2x by remember { mutableStateOf(initialZoom2x) }
    var torchAvailable by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var cameraIssue by remember { mutableStateOf<CameraIssue?>(null) }
    var busy by remember { mutableStateOf(false) }
    var captureError by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var active by remember { mutableStateOf(false) }
    val gate = remember { AutoCaptureGate() }
    val source = remember { sourceFactory(context) }
    val session = remember { ScanCaptureSession(context.cacheDir, source) }
    val motion = remember { motionFactory(context) { gate.interrupt(); progress = 0f } }
    val latestAutoCapture by rememberUpdatedState(autoCapture)
    val latestAutoProcess by rememberUpdatedState(autoProcess)
    fun clearTracking() {
        cameraFrame = null
        gate.interrupt()
        progress = 0f
    }
    fun applyZoom(use2x: Boolean) {
        ready = false
        zoom2x = use2x
        clearTracking()
        source.setZoomRatio(if (use2x) 2f else 1f) { ready = true }
    }
    fun shoot() {
        val process = latestAutoProcess ?: return
        if (!ready || busy || !active || cameraIssue != null) return
        gate.captured(); progress = 0f; busy = true; captureError = false
        session.capture(process, { file, flag -> latestCaptured(file, flag) }, {
            android.util.Log.e("ScanCamera", "Capture failed", it)
            busy = false; captureError = true
        })
    }
    val shootLatest by rememberUpdatedState(::shoot)
    LaunchedEffect(autoCapture) { gate.interrupt(); progress = 0f }
    LaunchedEffect(cameraFrame?.timestampMs) {
        delay(ScanThresholds.MAX_FRAME_GAP_MS)
        clearTracking()
    }
    DisposableEffect(source, owner) {
        var activeSince = SystemClock.elapsedRealtime()
        fun resume() { active = true; activeSince = SystemClock.elapsedRealtime(); motion.start() }
        fun pause() { active = false; motion.close(); clearTracking(); source.torch(false); torch = false }
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resume()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resume()
            if (event == Lifecycle.Event.ON_PAUSE) pause()
        }
        owner.lifecycle.addObserver(observer)
        source.start(owner, object : ScanCameraListener {
            override fun ready(hasTorch: Boolean, zoom2xAvailable: Boolean) {
                torchAvailable = hasTorch
                supports2x = zoom2xAvailable
                if (zoom2xAvailable) applyZoom(zoom2x) else ready = true
            }
            override fun idle() {
                ready = false
                clearTracking()
            }
            override fun frame(frame: ScanCameraFrame) {
                if (!ready || !active || busy || frame.timestampMs < activeSince) return
                cameraFrame = frame
                val decision = gate.update(frame.timestampMs, latestAutoCapture, frame.hint == ScanHint.NONE, motion.isStill(SystemClock.elapsedRealtime()))
                progress = decision.progress
                if (decision.capture) shootLatest()
            }
            override fun failed(issue: CameraIssue, cause: Exception?) { ready = false; cameraIssue = issue; gate.interrupt(); progress = 0f }
        })
        onDispose { owner.lifecycle.removeObserver(observer); motion.close(); session.close() }
    }
    ScanFrame(Modifier.weight(1f).fillMaxWidth(), progress) {
        if (cameraIssue == null) {
            // Fit BOTH constraints: preserve CameraX's portrait 4:3 viewport on narrow/large-font screens.
            BoxWithConstraints(Modifier.fillMaxSize().background(t.ink), contentAlignment = Alignment.Center) {
                val width = minOf(maxWidth, maxHeight * .75f)
                Box(Modifier.width(width).height(width * 4f / 3f).clipToBounds().testTag("scan-viewfinder")) {
                    AndroidView(factory = { source.view }, modifier = Modifier.fillMaxSize())
                    ScanContourOverlay(cameraFrame, Modifier.fillMaxSize())
                    if (supports2x) ScanZoomSwitch(zoom2x, ready && active && !busy,
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp)) { use2x ->
                        if (use2x != zoom2x) {
                            applyZoom(use2x)
                            scope.launch { preferences.setScanZoom2x(use2x) }
                        }
                    }
                    if (BuildConfig.DEBUG) YText(
                        "%.1f fps · dropped %d\n%s".format(cameraFrame?.fps ?: 0.0, cameraFrame?.failedFrames ?: 0, cameraFrame?.actualEps?.entries?.joinToString("\n") { "${it.key}: ${it.value}" } ?: "EP …"),
                        10, Modifier.align(Alignment.TopStart).background(t.ink.copy(alpha = .85f)).padding(8.dp).testTag("scan-debug"), color = t.surface,
                    )
                }
            }
        } else ScanStatus(
            if (cameraIssue == CameraIssue.NO_CAMERA) word("此设备没有可用的后置相机", "この端末には使用できる背面カメラがありません")
            else word("相机连接失败，请重试", "カメラに接続できませんでした。再試行してください"),
            Modifier.testTag("scan-camera-error"), action = { YButton(word("重试", "再試行"), retry, primary = true) })
    }
    val hint = cameraFrame?.hint ?: ScanHint.CENTER
    // Caption occupies paper below the image, including automatic capture feedback.
    if (cameraIssue == null) Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        if (hint != ScanHint.NONE) ScanHintLabel(hint, supports2x && !zoom2x, Modifier.fillMaxWidth())
        else ScanCaption(if (progress > 0f) word("保持稳定 · 即将拍摄", "そのままで・まもなく撮影") else word("小票已入镜 · 可以拍摄", "レシートを捉えました・撮影できます"), Modifier.fillMaxWidth())
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        // Stack toggles only when large type actually needs it; keep the normal preview generous.
        BoxWithConstraints {
            val stacked = maxWidth < 310.dp && LocalDensity.current.fontScale > 1.15f
            val capture: @Composable (Modifier) -> Unit = { modifier ->
                Box(modifier.testTag("scan-auto-capture")) { YToggle(word("自动拍摄", "自動撮影"), autoCapture, { scope.launch { preferences.setScanAutoCapture(it) } }) }
            }
            val process: @Composable (Modifier) -> Unit = { modifier ->
                Box(modifier.testTag("scan-auto-process")) { YToggle(word("自动处理", "自動補正"), autoProcess ?: true, { scope.launch { preferences.setScanAutoProcess(it) } }) }
            }
            if (stacked) Column { capture(Modifier); process(Modifier) }
            else Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { capture(Modifier.weight(1f)); process(Modifier.weight(1f)) }
        }
        if (captureError) ScanCaption(word("拍摄失败，可再次按快门", "撮影に失敗しました。もう一度撮影してください"), Modifier.testTag("scan-capture-error"), attention = true)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            YButton(if (torch) word("补光：开", "ライト：オン") else word("补光：关", "ライト：オフ"), { torch = !torch; source.torch(torch) }, Modifier.weight(1f).testTag("scan-torch"), enabled = torchAvailable && ready && !busy)
            ScanShutter(ready && active && !busy && autoProcess != null, busy, ::shoot)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                YText(if (autoProcess == false) word("拍后原图直出", "撮影後そのまま") else word("拍后自动裁剪展平", "撮影後に自動補正"), 10, color = t.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable private fun ScanShutter(enabled: Boolean, busy: Boolean, shoot: () -> Unit) {
    val t = LocalYohaku.current
    val label = if (busy) word("拍摄中", "撮影中") else word("拍照", "撮影")
    val shape = when (t.id) { "magazine" -> RoundedCornerShape(0.dp); "journal" -> RoundedCornerShape(44, 49, 45, 48); else -> RoundedCornerShape(24.dp) }
    Box(Modifier.size(72.dp).testTag("scan-shutter").alpha(if (enabled) 1f else .45f)
        .clickable(enabled = enabled, role = Role.Button, onClick = shoot), contentAlignment = Alignment.Center) {
        if (t.id == "objects") Box(Modifier.matchParentSize().offset(y = 4.dp).background(t.line, shape))
        Box(Modifier.matchParentSize().border(if (t.id == "journal") 1.dp else 2.dp, if (t.id == "journal") t.warm else t.accent, shape)
            .padding(if (t.id == "objects") 5.dp else 7.dp).background(if (t.id == "journal") t.surface else t.accent, shape), contentAlignment = Alignment.Center) {
            YText(label, 13, color = if (t.id == "journal") t.accent else t.surface)
        }
    }
}

@Composable private fun ScanZoomSwitch(use2x: Boolean, enabled: Boolean, modifier: Modifier, select: (Boolean) -> Unit) {
    val t = LocalYohaku.current
    val shape = when (t.id) {
        "journal" -> RoundedCornerShape(44, 49, 45, 48)
        "objects" -> RoundedCornerShape(13.dp)
        else -> RectangleShape
    }
    val border = if (t.id == "journal") t.warm else t.ink
    Box(modifier.testTag("scan-zoom").alpha(if (enabled) 1f else .65f)) {
        if (t.id == "objects") Box(Modifier.matchParentSize().offset(y = 4.dp).background(t.ink, shape))
        Row(Modifier.background(t.surface, shape).border(if (t.id == "objects") 2.dp else 1.dp, border, shape)
            .padding(if (t.id == "magazine") 1.dp else 3.dp).selectableGroup()) {
            val fill = when (t.id) {
                "journal" -> t.warm
                "objects" -> t.accent
                else -> t.ink
            }
            for (ratio in 1..2) {
                val selected = ratio == (if (use2x) 2 else 1)
                Box(Modifier.size(48.dp).clip(shape).background(if (selected) fill else t.surface)
                    .testTag("scan-zoom-${ratio}x").selectable(selected, enabled = enabled, role = Role.RadioButton,
                        onClick = { select(ratio == 2) }), contentAlignment = Alignment.Center) {
                    YText("${ratio}×", 16, number = true, bold = selected,
                        color = if (selected) t.surface else t.ink)
                }
            }
        }
    }
}

@Composable internal fun ScanContourOverlay(frame: ScanCameraFrame?, modifier: Modifier = Modifier) {
    val t = LocalYohaku.current
    val outline = frame?.outline.orEmpty()
    val tracker = remember { ScanOutlineTracker() }
    var drawing by remember { mutableStateOf(tracker.drawing) }
    var lastTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(outline) {
        tracker.setTarget(outline)
        drawing = tracker.drawing
        while (!tracker.converged) {
            withFrameNanos { now ->
                if (lastTick != 0L) tracker.step((now - lastTick) / 1_000_000.0)
                drawing = tracker.drawing
                lastTick = now
            }
        }
        lastTick = 0L
    }
    Canvas(modifier.testTag("scan-outline").semantics { contentDescription = "contour:${outline.size}" }) {
        val (points, alpha) = drawing
        if (points.isNotEmpty()) {
            val path = Path().apply {
                moveTo((points.first().x * size.width).toFloat(), (points.first().y * size.height).toFloat())
                for (i in 1 until points.size) {
                    lineTo((points[i].x * size.width).toFloat(), (points[i].y * size.height).toFloat())
                }
                close()
            }
            drawPath(path, t.surface.copy(alpha = .85f), alpha = alpha, style = Stroke((if (t.id == "objects") 6 else 4).dp.toPx()))
            drawPath(path, if (t.id == "journal") t.warm else if (t.id == "magazine") t.ink else t.accent, alpha = alpha, style = Stroke(
                (if (t.id == "objects") 3.5f else 1.5f).dp.toPx(), join = StrokeJoin.Round,
                pathEffect = if (t.id == "journal") PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 4.dp.toPx())) else null))
        }
        // Registration marks describe the capture area; they stay near the image corners.
        val inset = 12.dp.toPx(); val arm = 16.dp.toPx()
        for (x in listOf(inset, size.width - inset)) for (y in listOf(inset, size.height - inset)) {
            val dx = if (x < center.x) arm else -arm; val dy = if (y < center.y) arm else -arm
            val color = t.surface.copy(alpha = .8f)
            if (t.id == "objects") drawCircle(color, 2.5.dp.toPx(), Offset(x, y))
            else {
                drawLine(color, Offset(x, y + dy), Offset(x, y), 1.dp.toPx())
                drawLine(color, Offset(x, y), Offset(x + dx, y), 1.dp.toPx())
            }
        }
    }
}

@Composable private fun ScanHintLabel(hint: ScanHint, suggest2x: Boolean, modifier: Modifier) {
    val label = when (hint) {
        ScanHint.CENTER -> word("把小票放在中间", "レシートを中央に合わせてください")
        ScanHint.CLOSER -> if (suggest2x) word("切到 2× 或靠近一些", "2×に切り替えるか、もう少し近づけてください")
            else word("靠近一些", "もう少し近づけてください")
        ScanHint.STEADY -> word("拿稳", "動かさずにお待ちください")
        ScanHint.NOT_FOUND -> word("未找到小票", "レシートが見つかりません")
        ScanHint.NONE -> ""
    }
    ScanCaption(label, modifier.testTag("scan-hint"), attention = hint == ScanHint.NOT_FOUND)
}
