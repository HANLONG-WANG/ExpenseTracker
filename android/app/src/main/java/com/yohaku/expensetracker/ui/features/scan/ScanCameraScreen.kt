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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import kotlinx.coroutines.launch
import java.io.File

/** Caller keeps the target stable and captures it in onCaptured; stage 4 maps Draft/Record here. */
data class ScanCameraRequest(val target: String)
internal enum class CameraPermission { GRANTED, DENIED, PERMANENTLY_DENIED }

/** Full-screen flow like FinancialFlow. Remove from composition on exit/capture.
 * onCaptured receives an EXIF-oriented JPEG in cache/receipt-capture; ownership transfers to the
 * caller, which must delete it after confirmation/abandonment. No existing camera entry is changed.
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
    val t = LocalYohaku.current
    Column(Modifier.fillMaxSize().background(t.bg).safeDrawingPadding().testTag("scan-camera")) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            YButton(word("返回", "戻る"), onBack, Modifier.testTag("scan-back"))
            Spacer(Modifier.width(16.dp))
            YText(word("扫描小票", "レシートを撮影"), 20, Modifier.weight(1f))
        }
        if (!hasCamera) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                YText(word("此设备没有可用的后置相机", "この端末には使用できる背面カメラがありません"), 18, Modifier.testTag("scan-camera-error"))
            }
        } else if (currentPermission != CameraPermission.GRANTED) {
            Column(Modifier.fillMaxSize().padding(24.dp).testTag("scan-permission"), verticalArrangement = Arrangement.Center) {
                YText(word("允许使用相机，即可扫描小票。", "カメラを許可すると、レシートを撮影できます。"), 18)
                Spacer(Modifier.height(20.dp))
                if (currentPermission == CameraPermission.PERMANENTLY_DENIED) {
                    YButton(word("前往系统设置", "設定を開く"), {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    }, Modifier.testTag("scan-open-settings"), primary = true)
                } else YButton(word("允许相机 / 重试", "カメラを許可 / 再試行"), ::askPermission, Modifier.testTag("scan-request-permission"), primary = true)
            }
        } else {
            var attempt by remember { mutableIntStateOf(0) }
            key(attempt) { ScanCameraContent(preferences, sourceFactory, motionFactory, onCaptured, { attempt++ }) }
        }
    }
}

@Composable private fun ColumnScope.ScanCameraContent(
    preferences: AppPreferences, sourceFactory: (Context) -> ScanCameraSource,
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
        gate.interrupt(); progress = 0f; cameraFrame = null
    }
    DisposableEffect(source, owner) {
        var activeSince = SystemClock.elapsedRealtime()
        fun resume() { active = true; activeSince = SystemClock.elapsedRealtime(); motion.start() }
        fun pause() { active = false; motion.close(); gate.interrupt(); progress = 0f; cameraFrame = null; source.torch(false); torch = false }
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resume()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resume()
            if (event == Lifecycle.Event.ON_PAUSE) pause()
        }
        owner.lifecycle.addObserver(observer)
        source.start(owner, object : ScanCameraListener {
            override fun ready(hasTorch: Boolean) { ready = true; torchAvailable = hasTorch }
            override fun frame(frame: ScanCameraFrame) {
                if (!active || busy || frame.timestampMs < activeSince) return
                cameraFrame = frame
                val decision = gate.update(frame.timestampMs, latestAutoCapture, frame.hint == ScanHint.NONE, motion.isStill(SystemClock.elapsedRealtime()))
                progress = decision.progress
                if (decision.capture) shootLatest()
            }
            override fun failed(issue: CameraIssue, cause: Exception?) { ready = false; cameraIssue = issue; gate.interrupt(); progress = 0f }
        })
        onDispose { owner.lifecycle.removeObserver(observer); motion.close(); session.close() }
    }
    Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
        if (cameraIssue == null) {
            // A fixed 3:4 view gives all three CameraX use cases the same 4:3 portrait ViewPort.
            Box(Modifier.aspectRatio(3f / 4f, matchHeightConstraintsFirst = true).clipToBounds().testTag("scan-viewfinder")) {
                AndroidView(factory = { source.view }, modifier = Modifier.fillMaxSize())
                ScanContourOverlay(cameraFrame, progress, Modifier.fillMaxSize())
                if (BuildConfig.DEBUG) YText(
                    "%.1f fps\n%s".format(cameraFrame?.fps ?: 0.0, cameraFrame?.actualEps?.entries?.joinToString("\n") { "${it.key}: ${it.value}" } ?: "EP …"),
                    10, Modifier.align(Alignment.TopStart).background(Color.Black.copy(alpha = .65f)).padding(8.dp).testTag("scan-debug"), color = Color.White,
                )
                val hint = cameraFrame?.hint
                if (hint != null && hint != ScanHint.NONE) ScanHintLabel(hint, Modifier.align(Alignment.BottomCenter).padding(12.dp))
            }
        } else Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            YText(if (cameraIssue == CameraIssue.NO_CAMERA) word("此设备没有可用的后置相机", "この端末には使用できる背面カメラがありません") else word("相机或轮廓分析失败，请重试", "カメラまたは輪郭の解析に失敗しました。再試行してください"), 16, Modifier.testTag("scan-camera-error"), color = Color.White)
            YButton(word("重试", "再試行"), retry, primary = true)
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.weight(1f).testTag("scan-auto-capture")) { YToggle(word("自动拍摄", "自動撮影"), autoCapture, { scope.launch { preferences.setScanAutoCapture(it) } }) }
            Box(Modifier.weight(1f).testTag("scan-auto-process")) { YToggle(word("自动处理", "自動補正"), autoProcess ?: true, { scope.launch { preferences.setScanAutoProcess(it) } }) }
        }
        if (captureError) YText(word("拍摄失败，可再次按快门", "撮影に失敗しました。もう一度撮影してください"), 13, Modifier.testTag("scan-capture-error"), color = t.warm)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            YButton(if (torch) word("补光：开", "ライト：オン") else word("补光：关", "ライト：オフ"), { torch = !torch; source.torch(torch) }, Modifier.weight(1f).testTag("scan-torch"), enabled = torchAvailable && ready && !busy)
            Spacer(Modifier.width(16.dp))
            ScanShutter(ready && active && !busy && autoProcess != null, busy, ::shoot)
            Spacer(Modifier.width(16.dp))
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable private fun ScanShutter(enabled: Boolean, busy: Boolean, shoot: () -> Unit) {
    val t = LocalYohaku.current
    val label = if (busy) word("拍摄中", "撮影中") else word("拍照", "撮影")
    Box(Modifier.size(72.dp).border(2.dp, t.accent, CircleShape).padding(6.dp).background(t.accent.copy(alpha = if (enabled) 1f else .4f), CircleShape)
        .testTag("scan-shutter").clickable(enabled = enabled, role = Role.Button, onClick = shoot), contentAlignment = Alignment.Center) {
        YText(label, 13, color = t.surface)
    }
}

@Composable internal fun ScanContourOverlay(frame: ScanCameraFrame?, progress: Float, modifier: Modifier = Modifier) {
    val t = LocalYohaku.current
    Canvas(modifier.testTag("scan-outline").semantics { contentDescription = "contour:${frame?.outline?.size ?: 0}" }) {
        frame?.outline?.takeIf { it.isNotEmpty() }?.let { points ->
            val path = Path().apply {
                moveTo((points.first().x * size.width).toFloat(), (points.first().y * size.height).toFloat())
                points.drop(1).forEach { lineTo((it.x * size.width).toFloat(), (it.y * size.height).toFloat()) }; close()
            }
            drawPath(path, Color.White.copy(alpha = .85f), style = Stroke(5.dp.toPx()))
            drawPath(path, t.accent, style = Stroke(2.dp.toPx()))
        }
    }
    if (progress > 0f) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(60.dp).background(Color.Black.copy(alpha = .45f), CircleShape).padding(5.dp).testTag("scan-auto-progress")
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f) }) {
            drawArc(Color.White, -90f, 360f * progress, false, style = Stroke(4.dp.toPx()))
        }
    }
}

@Composable private fun ScanHintLabel(hint: ScanHint, modifier: Modifier) {
    val label = when (hint) {
        ScanHint.CENTER -> word("把小票放在中间", "レシートを中央に合わせてください")
        ScanHint.CLOSER -> word("靠近一些", "もう少し近づけてください")
        ScanHint.STEADY -> word("拿稳", "動かさずにお待ちください")
        ScanHint.NOT_FOUND -> word("未找到小票", "レシートが見つかりません")
        ScanHint.NONE -> ""
    }
    YText(label, 15, modifier.background(Color.Black.copy(alpha = .72f)).padding(horizontal = 14.dp, vertical = 10.dp).testTag("scan-hint"), color = Color.White)
}
