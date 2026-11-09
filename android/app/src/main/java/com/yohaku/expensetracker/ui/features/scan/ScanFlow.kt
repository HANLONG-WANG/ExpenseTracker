package com.yohaku.expensetracker.ui.features.scan

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yohaku.expensetracker.data.repository.AppPreferences
import com.yohaku.expensetracker.receipt.scan.ScanTemporaryFiles
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
private data class ScanFlowState(
    val review: ScanReviewState? = null,
    val importingTo: String? = null,
    val bypass: Boolean = false,
    val ended: Boolean = false,
)

/** Complete scan flow. The caller imports the output into its saved draft/record target.
 * Output is a JPEG in scan cache, owned by onDone; delete it (and its parent) after import.
 * Original scan photos are removed BEFORE onDone/onCancel. Existing-image input is borrowed.
 */
@Composable fun ScanFlow(request: ScanFlowRequest, preferences: AppPreferences,
                         onDone: (ScanFlowResult) -> Unit, onCancel: () -> Unit) {
    ScanFlowHost(request, preferences, onDone, onCancel)
}

@Composable internal fun ScanFlowHost(
    request: ScanFlowRequest, preferences: AppPreferences, onDone: (ScanFlowResult) -> Unit, onCancel: () -> Unit,
    sourceFactory: (Context) -> ScanCameraSource = { CameraXScanSource(it) },
    permissionOverride: CameraPermission? = null,
    motionFactory: (Context, () -> Unit) -> ScanMotionSource = { context, changed -> ScanMotionMonitor(context, changed) },
) = key(request.target, request.photo?.path, request.source.name) {
    // Use persisted primitive keys: enum/object identity hashes change after process death.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestDone by rememberUpdatedState(onDone)
    val latestCancel by rememberUpdatedState(onCancel)
    var state by rememberSaveable(stateSaver = Saver(
        save = { Json.encodeToString(it) }, restore = { Json.decodeFromString<ScanFlowState>(it) },
    )) { mutableStateOf(if (request.photo != null && request.source == ScanPhotoSource.CAMERA) {
        require(request.photo.parentFile?.parentFile == File(context.cacheDir, "receipt-capture")) { "Camera photo must belong to scan cache" }
        ScanFlowState(review = ScanReviewState(request.photo.path))
    } else ScanFlowState(importingTo = request.photo?.let { ScanTemporaryFiles.newDirectory(context.cacheDir).path })) }
    val abandoned = remember { AtomicBoolean(false) }
    var leaving by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf(false) }
    var importAttempt by remember { mutableIntStateOf(0) }
    fun exit(retake: Boolean) {
        if (leaving || state.ended) return
        leaving = true; abandoned.set(true)
        val directory = state.review?.directory ?: state.importingTo?.let(::File)
        scope.launch {
            withContext(Dispatchers.IO) { directory?.let(ScanTemporaryFiles::discard) }
            if (retake) { state = ScanFlowState(); abandoned.set(false); leaving = false }
            else { state = state.copy(ended = true); latestCancel() }
        }
    }
    fun complete(selected: File, processed: Boolean) {
        if (leaving || state.ended) return
        leaving = true
        scope.launch {
            try {
                val output = withContext(Dispatchers.IO) { ScanTemporaryFiles.export(context.cacheDir, selected.parentFile!!, selected) }
                state = state.copy(ended = true)
                latestDone(ScanFlowResult(output, processed, request.target))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.e("ScanReview", "Export failed", e); importError = true; leaving = false }
        }
    }
    LaunchedEffect(state.importingTo, importAttempt) {
        val path = state.importingTo ?: return@LaunchedEffect
        val directory = File(path)
        importError = false
        try {
            val photo = withContext(Dispatchers.IO) {
                try {
                    if (!directory.exists()) ScanTemporaryFiles.create(directory)
                    val original = File(directory, "original.jpg")
                    if (!original.isFile) ScanTemporaryFiles.write(directory, original.name, requireNotNull(request.photo).readBytes())
                    original
                } finally { if (abandoned.get()) ScanTemporaryFiles.discard(directory) }
            }
            state = state.copy(importingTo = null, review = ScanReviewState(photo.path))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { android.util.Log.e("ScanReview", "Photo import failed", e); importError = true }
    }
    LaunchedEffect(state.bypass, state.review?.photoPath) {
        if (state.bypass && !state.ended) complete(requireNotNull(state.review).photo, false)
    }
    if (!state.ended) {
        val review = state.review
        if (review == null && state.importingTo == null) {
            ScanCameraHost(ScanCameraRequest(request.target), preferences, { exit(false) }, { file, autoProcess ->
                state = state.copy(review = ScanReviewState(file.path), bypass = !autoProcess)
            }, sourceFactory, permissionOverride, motionFactory)
        } else Dialog(onDismissRequest = { if (!leaving) exit(false) }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            if (review == null || state.bypass || leaving || importError) ScanReviewWaiting(importError,
                { importError = false; if (state.importingTo != null) importAttempt++
                    else complete(if (state.bypass) requireNotNull(state.review).photo else requireNotNull(state.review).result, !state.bypass) }, { exit(false) }, cancellable = !leaving)
            else ScanReviewScreen(review, { state = state.copy(review = it) }, request.source,
                onUse = { complete(it, true) }, onLeave = { exit(request.source == ScanPhotoSource.CAMERA) }, onCancel = { exit(false) })
        }
    }
}
