package com.yohaku.expensetracker.ui.features.scan

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import kotlinx.coroutines.*
import java.io.File

@Composable internal fun ScanReviewScreen(
    state: ScanReviewState, onState: (ScanReviewState) -> Unit, source: ScanPhotoSource,
    onUse: (File) -> Unit, onLeave: () -> Unit, onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val t = LocalYohaku.current
    var images by remember { mutableStateOf<ScanReviewImages?>(null) }
    var busy by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf(ScanReviewOperation.LOAD) }
    var attempt by remember { mutableIntStateOf(0) }
    val currentState by rememberUpdatedState(state)
    val update by rememberUpdatedState(onState)
    fun run(action: ScanReviewOperation) { operation = action; busy = true; attempt++ }
    LaunchedEffect(attempt) {
        busy = true; failed = false
        try {
            val (next, loaded) = withContext(scanReviewWorker) { loadScanReview(context, currentState, operation) }
            images = loaded; update(next)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { android.util.Log.e("ScanReview", "Receipt processing failed", e); failed = true }
        finally { busy = false }
    }
    Column(Modifier.fillMaxSize().background(t.bg).safeDrawingPadding().padding(16.dp).testTag("scan-review"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            YText(if (state.editing) word("调整轮廓", "輪郭を調整") else word("确认小票", "レシートを確認"), 22, Modifier.weight(1f))
            YButton(word("关闭", "閉じる"), onCancel, Modifier.testTag("scan-review-close"))
        }
        if (state.editing) {
            YText(if (state.notDetected && !state.hasResult) word("没找到小票，请拖动边框", "レシートが見つかりません。枠をドラッグしてください")
                else word("拖动圆点贴合小票边缘", "丸い点をレシートの縁に合わせてください"), 14, Modifier.testTag("scan-review-hint"), color = t.muted)
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(t.soft).testTag("scan-result-frame"), contentAlignment = Alignment.Center) {
            val loaded = images
            if (loaded != null && !busy) {
                if (state.editing) ScanContourCanvas(loaded.original, requireNotNull(state.frame), requireNotNull(state.draft),
                    { onState(state.copy(draft = it)) }, Modifier.fillMaxSize())
                else loaded.result?.let { ScanResultImage(it, Modifier.fillMaxSize()) }
            }
            if (busy) YText(word("正在处理…", "補正中…"), 18, Modifier.padding(20.dp).testTag("scan-review-processing"))
            if (failed) Column(Modifier.background(t.surface).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                YText(word("处理失败，请重试或调整轮廓", "補正できませんでした。再試行するか輪郭を調整してください"), 16, Modifier.testTag("scan-review-error"))
                YButton(word("重试", "再試行"), { run(operation) }, Modifier.testTag("scan-review-retry"))
            }
        }
        ScanReviewActions(state, busy, failed, source, onUse = { onUse(state.result) }, onLeave = onLeave,
            onEdit = { onState(state.copy(editing = true, draft = state.accepted ?: state.automatic)) },
            onRender = { run(ScanReviewOperation.RENDER) }, onRestore = { run(ScanReviewOperation.RESTORE) },
            onCancelEdit = { if (state.hasResult) { failed = false; onState(state.copy(editing = false, draft = state.accepted ?: state.automatic)) } else onCancel() })
    }
}

/** Separate result, controls and editor visuals are the replacement points for stage 5. */
@Composable private fun ScanResultImage(bitmap: android.graphics.Bitmap, modifier: Modifier) {
    Image(bitmap.asImageBitmap(), word("处理后的小票", "補正したレシート"), modifier.padding(8.dp).testTag("scan-result"), contentScale = ContentScale.Fit)
}

@Composable private fun ScanReviewActions(
    state: ScanReviewState, busy: Boolean, failed: Boolean, source: ScanPhotoSource,
    onUse: () -> Unit, onLeave: () -> Unit, onEdit: () -> Unit, onRender: () -> Unit, onRestore: () -> Unit, onCancelEdit: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.editing) {
            YButton(word("重新处理", "再補正"), onRender, Modifier.fillMaxWidth().testTag("scan-render"), primary = true, enabled = !busy)
            YButton(word("恢复自动结果", "自動補正に戻す"), onRestore, Modifier.fillMaxWidth().testTag("scan-restore"), enabled = !busy && state.automatic != null)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                YButton(word("取消调整", "調整をキャンセル"), onCancelEdit, Modifier.weight(1f).testTag("scan-cancel-edit"), enabled = !busy)
                if (source == ScanPhotoSource.CAMERA) YButton(word("重拍", "撮り直す"), onLeave, Modifier.weight(1f).testTag("scan-review-leave"))
            }
        } else {
            YButton(word("使用", "使用する"), onUse, Modifier.fillMaxWidth().testTag("scan-use"), primary = true, enabled = !busy && !failed && state.hasResult)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                YButton(word("调整轮廓", "輪郭を調整"), onEdit, Modifier.weight(1f).testTag("scan-edit"), enabled = !busy && state.initialized)
                YButton(if (source == ScanPhotoSource.CAMERA) word("重拍", "撮り直す") else word("取消", "キャンセル"), onLeave,
                    Modifier.weight(1f).testTag("scan-review-leave"))
            }
        }
    }
}

@Composable internal fun ScanReviewWaiting(failed: Boolean, retry: () -> Unit, cancel: () -> Unit, cancellable: Boolean = true) {
    val t = LocalYohaku.current
    Column(Modifier.fillMaxSize().background(t.bg).safeDrawingPadding().padding(24.dp).testTag("scan-review-waiting"),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        YText(if (failed) word("照片读取或保存失败，请重试", "写真を読み込み・保存できませんでした。再試行してください") else word("正在准备照片…", "写真を準備中…"), 18)
        if (failed) YButton(word("重试", "再試行"), retry)
        Spacer(Modifier.height(16.dp))
        YButton(word("取消", "キャンセル"), cancel, Modifier.testTag("scan-waiting-cancel"), enabled = cancellable)
    }
}
