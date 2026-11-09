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
    ScanReviewPage(state, images, busy, failed, source, onUse = { onUse(state.result) }, onLeave, onCancel,
        onChange = { onState(state.copy(draft = it)) },
        onEdit = { onState(state.copy(editing = true, draft = state.accepted ?: state.automatic)) },
        onRender = { run(ScanReviewOperation.RENDER) }, onRestore = { run(ScanReviewOperation.RESTORE) }, onRetry = { run(operation) },
        onCancelEdit = { if (state.hasResult) { failed = false; onState(state.copy(editing = false, draft = state.accepted ?: state.automatic)) } else onCancel() })
}

/** Production presentation also used for deterministic in-flight screenshots, without delaying inference. */
@Composable internal fun ScanReviewPage(
    state: ScanReviewState, images: ScanReviewImages?, busy: Boolean, failed: Boolean, source: ScanPhotoSource,
    onUse: () -> Unit, onLeave: () -> Unit, onCancel: () -> Unit,
    onChange: (com.yohaku.expensetracker.receipt.scan.ScanContour) -> Unit,
    onEdit: () -> Unit, onRender: () -> Unit, onRestore: () -> Unit, onRetry: () -> Unit, onCancelEdit: () -> Unit,
) {
    Column(Modifier.fillMaxSize().scanPaper().safeDrawingPadding().padding(16.dp).testTag("scan-review"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScanHeading(if (state.editing) word("调整轮廓", "輪郭を調整") else word("确认小票", "レシートを確認"),
            if (state.editing) "03" else "02", word("关闭", "閉じる"), "scan-review-close", onCancel)
        ScanFrame(Modifier.weight(1f).fillMaxWidth().testTag("scan-result-frame")) {
            if (images != null && !busy && !failed) {
                if (state.editing) ScanContourCanvas(images.original, requireNotNull(state.frame), requireNotNull(state.draft), onChange, Modifier.fillMaxSize())
                else images.result?.let { ScanResultImage(it, Modifier.fillMaxSize()) }
            }
            if (busy) ScanStatus(word("正在处理…", "補正中…"), Modifier.testTag("scan-review-processing"), processing = true)
            if (failed) ScanStatus(word("处理失败，请重试或调整轮廓", "補正できませんでした。再試行するか輪郭を調整してください"),
                Modifier.testTag("scan-review-error"), action = { YButton(word("重试", "再試行"), onRetry, Modifier.testTag("scan-review-retry")) })
        }
        if (!busy && !failed) {
            if (state.editing) ScanCaption(
                if (state.notDetected && !state.hasResult) word("没找到小票，请拖动边框", "レシートが見つかりません。枠をドラッグしてください")
                else word("拖动控制点贴合边缘 · 拖动时放大", "点を縁に合わせる・ドラッグ中に拡大"),
                Modifier.fillMaxWidth().testTag("scan-review-hint"), attention = state.notDetected && !state.hasResult)
            else ScanCaption(word("检查边缘与文字，确认后使用", "縁と文字を確かめてから使用してください"), Modifier.fillMaxWidth())
        }
        ScanReviewActions(state, busy, failed, source, onUse, onLeave, onEdit, onRender, onRestore, onCancelEdit)
    }
}

/** Keep the processed image undistorted inside the theme's paper/window mount. */
@Composable private fun ScanResultImage(bitmap: android.graphics.Bitmap, modifier: Modifier) {
    Image(bitmap.asImageBitmap(), word("处理后的小票", "補正したレシート"), modifier.padding(8.dp).testTag("scan-result"), contentScale = ContentScale.Fit)
}

@Composable private fun ScanReviewActions(
    state: ScanReviewState, busy: Boolean, failed: Boolean, source: ScanPhotoSource,
    onUse: () -> Unit, onLeave: () -> Unit, onEdit: () -> Unit, onRender: () -> Unit, onRestore: () -> Unit, onCancelEdit: () -> Unit,
) {
    val t = LocalYohaku.current
    val primary: @Composable (Modifier) -> Unit = { modifier ->
        YButton(if (state.editing) word("重新处理", "再補正") else word("使用", "使用する"),
            if (state.editing) onRender else onUse, modifier.testTag(if (state.editing) "scan-render" else "scan-use"),
            primary = true, enabled = !busy && (state.editing || (!failed && state.hasResult)))
    }
    val leave: @Composable (Modifier) -> Unit = { modifier ->
        YButton(if (source == ScanPhotoSource.CAMERA) word("重拍", "撮り直す") else word("取消", "キャンセル"),
            onLeave, modifier.testTag("scan-review-leave"))
    }
    val edit: @Composable (Modifier) -> Unit = { modifier ->
        YButton(word("调整轮廓", "輪郭を調整"), onEdit, modifier.testTag("scan-edit"), enabled = !busy && state.initialized)
    }
    val cancelEdit: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            YButton(word("取消调整", "調整をキャンセル"), onCancelEdit, Modifier.weight(1f).testTag("scan-cancel-edit"), enabled = !busy)
            if (source == ScanPhotoSource.CAMERA) leave(Modifier.weight(1f))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(if (t.id == "objects") 10.dp else 8.dp)) {
        if (state.editing) {
            if (t.id == "magazine") primary(Modifier.fillMaxWidth())
            YButton(word("恢复自动结果", "自動補正に戻す"), onRestore, Modifier.fillMaxWidth().testTag("scan-restore"), enabled = !busy && state.automatic != null)
            if (t.id == "objects") primary(Modifier.fillMaxWidth())
            cancelEdit()
            if (t.id == "journal") primary(Modifier.fillMaxWidth())
        } else when (t.id) {
            "journal" -> {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { edit(Modifier.weight(1f)); leave(Modifier.weight(1f)) }
                primary(Modifier.fillMaxWidth())
            }
            "objects" -> {
                edit(Modifier.fillMaxWidth())
                // YButton primary normally fills a row; its own box bounds the physical button.
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(2f)) { primary(Modifier.fillMaxWidth()) }
                    leave(Modifier.weight(1f))
                }
            }
            else -> {
                primary(Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { edit(Modifier.weight(1f)); leave(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable internal fun ScanReviewWaiting(failed: Boolean, retry: () -> Unit, cancel: () -> Unit, cancellable: Boolean = true) {
    Column(Modifier.fillMaxSize().scanPaper().safeDrawingPadding().padding(16.dp).testTag("scan-review-waiting"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScanCaption(word("小票 · 准备照片", "レシート・写真を準備"), Modifier.fillMaxWidth())
        ScanFrame(Modifier.weight(1f).fillMaxWidth()) {
            ScanStatus(if (failed) word("照片读取或保存失败，请重试", "写真を読み込み・保存できませんでした。再試行してください") else word("正在准备照片…", "写真を準備中…"), processing = !failed,
                action = if (failed) { { YButton(word("重试", "再試行"), retry) } } else null)
        }
        YButton(word("取消", "キャンセル"), cancel, Modifier.fillMaxWidth().testTag("scan-waiting-cancel"), enabled = cancellable)
    }
}
