package com.yohaku.expensetracker.ui.features.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import com.yohaku.expensetracker.receipt.scan.*
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlin.math.*

enum class ScanPhotoSource { CAMERA, EXISTING_IMAGE }

/** With no photo, opens the camera. A supplied CAMERA photo must be in the stage-2 scan
 * cache directory and transfers ownership;
 * EXISTING_IMAGE is copied to scan cache and the caller's original is never modified.
 * Keep request/target stable across recreation; remove ScanFlow after either callback.
 */
data class ScanFlowRequest(val target: String, val photo: File? = null, val source: ScanPhotoSource = ScanPhotoSource.CAMERA) {
    init { require(photo != null || source == ScanPhotoSource.CAMERA) }
}
data class ScanFlowResult(val file: File, val processed: Boolean, val target: String)

internal val scanReviewWorker = Executors.newSingleThreadExecutor { task -> Thread(task, "receipt-review") }.asCoroutineDispatcher()

@Serializable
internal data class ScanReviewState(
    val photoPath: String,
    val frame: ScanEditFrame? = null,
    val automatic: ScanContour? = null,
    val accepted: ScanContour? = null,
    val acceptedFile: String? = null,
    val draft: ScanContour? = null,
    val editing: Boolean = false,
    val notDetected: Boolean = false,
    val initialized: Boolean = false,
) {
    val photo get() = File(photoPath)
    val directory get() = photo.parentFile!!
    val result get() = File(directory, acceptedFile ?: "automatic.jpg")
    val hasResult get() = initialized && (!notDetected || accepted != null)
}
internal data class ScanReviewImages(val original: Bitmap, val result: Bitmap?)
internal enum class ScanReviewOperation { LOAD, RENDER, RESTORE }

/** Each call creates/uses/closes the synchronous engine on the SAME worker thread. */
internal fun loadScanReview(context: Context, state: ScanReviewState, operation: ScanReviewOperation): Pair<ScanReviewState, ScanReviewImages> {
    var next = state
    if (!state.initialized) {
        ReceiptScanner(context).use { scanner ->
            when (val processed = scanner.process(state.photo.readBytes())) {
                is ScanProcessResult.Processed -> {
                    val contour = processed.detection.contour
                    val frame = ScanEditFrame.forContour(contour)
                    ScanTemporaryFiles.write(state.directory, "automatic.jpg", processed.render.jpeg)
                    next = state.copy(frame = frame, automatic = frame.display(contour), initialized = true)
                }
                is ScanProcessResult.NotDetected -> {
                    val d = processed.detection
                    val frame = ScanEditFrame(d.width, d.height)
                    next = state.copy(frame = frame, draft = ScanContour.centered(d.width, d.height),
                        initialized = true, notDetected = true, editing = true)
                }
            }
        }
    } else if (operation == ScanReviewOperation.RENDER) {
        val contour = requireNotNull(state.draft)
        val resultName = "edited-${UUID.randomUUID()}.jpg"
        ReceiptScanner(context).use { scanner ->
            val result = scanner.render(state.photo.readBytes(), requireNotNull(state.frame).source(contour))
            ScanTemporaryFiles.write(state.directory, resultName, result.jpeg)
        }
        next = state.copy(accepted = contour, acceptedFile = resultName, editing = false)
    } else if (operation == ScanReviewOperation.RESTORE) {
        check(state.automatic != null)
        next = state.copy(accepted = null, acceptedFile = null, draft = state.automatic, editing = false)
    }
    // Files normally survive recreation. Re-render a missing preview from saved contour;
    // automatic restoration prefers the cached exact mask result, not a five-point approximation.
    if (next.hasResult && !next.result.isFile) {
        ReceiptScanner(context).use { scanner ->
            val bytes = next.photo.readBytes()
            val rendered = if (next.accepted != null) scanner.render(bytes, next.frame!!.source(next.accepted))
            else (scanner.process(bytes) as ScanProcessResult.Processed).render
            ScanTemporaryFiles.write(next.directory, next.result.name, rendered.jpeg)
        }
    }
    val original = scanDisplayPhoto(next.photo, requireNotNull(next.frame))
    val result = if (next.hasResult) BitmapFactory.decodeFile(next.result.path) ?: error("Cannot decode scan result") else null
    return next to ScanReviewImages(original, result)
}

/** ImageDecoder applies EXIF (including mirrored orientations). Rotation is display-only. */
private fun scanDisplayPhoto(photo: File, frame: ScanEditFrame): Bitmap {
    val scale = min(1.0, 1600.0 / max(frame.width, frame.height))
    val source = ImageDecoder.decodeBitmap(ImageDecoder.createSource(photo)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetSize(max(1, (info.size.width * scale).roundToInt()), max(1, (info.size.height * scale).roundToInt()))
    }
    val bitmap = Bitmap.createBitmap(ceil(frame.width * scale).toInt(), ceil(frame.height * scale).toInt(), Bitmap.Config.ARGB_8888)
    val c = cos(frame.radians); val s = sin(frame.radians)
    val sx = scale * frame.sourceWidth / source.width; val sy = scale * frame.sourceHeight / source.height
    val matrix = Matrix().apply { setValues(floatArrayOf((c*sx).toFloat(), (-s*sy).toFloat(), (-frame.origin.x*scale).toFloat(),
        (s*sx).toFloat(), (c*sy).toFloat(), (-frame.origin.y*scale).toFloat(), 0f, 0f, 1f)) }
    Canvas(bitmap).drawBitmap(source, matrix, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
    source.recycle()
    return bitmap
}
