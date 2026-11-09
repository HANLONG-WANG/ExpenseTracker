package com.yohaku.expensetracker.receipt.scan

import android.content.Context
import org.opencv.android.OpenCVLoader
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs

internal object OpenCvRuntime {
    private val loaded by lazy { check(OpenCVLoader.initLocal()) { "OpenCV initialization failed" }; Core.setNumThreads(6); true }
    fun ensure() { check(loaded) }
}

data class ScanDiagnostics(val geometry: Map<String, Any?>, val timingsMs: Map<String, Double>, val actualEps: Map<String, String>)
sealed interface ScanDetection {
    val diagnostics: ScanDiagnostics
    class Found internal constructor(val mask: ScanMask, val contour: ScanContour, override val diagnostics: ScanDiagnostics,
                                          internal val source: ReceiptGeometry.Source) : ScanDetection
    data class NotDetected(val width: Int, val height: Int, val reason: String, override val diagnostics: ScanDiagnostics) : ScanDetection
}
/** All positions are image coordinates. Reliability is a fit metric, not an auto-shutter policy. */
data class ScanPreview(val outline: List<ScanPoint>, val areaRatio: Double, val center: ScanPoint?, val modelScore: Double?,
                       val fitResidualPx: Double?, val fitOutlierFraction: Double?, val relaxedEnds: Boolean,
                       val topInFrame: Boolean?, val bottomInFrame: Boolean?, val diagnostics: ScanDiagnostics) {
    val detected get() = outline.isNotEmpty()
}
data class ScanRender(val jpeg: ByteArray, val width: Int, val height: Int, val diagnostics: ScanDiagnostics)
sealed interface ScanProcessResult {
    data class Processed(val detection: ScanDetection.Found, val render: ScanRender) : ScanProcessResult
    data class NotDetected(val detection: ScanDetection.NotDetected) : ScanProcessResult
}

/** Synchronous engine: construct/use/close on one background worker, never share across workers.
 * Photos are encoded image bytes; EXIF is baked into pixels at decode. Preview frames are upright
 * CV_8UC3 BGR Mats owned by the caller. No returned object owns a native Mat/session.
 */
class ReceiptScanner(context: Context) : AutoCloseable {
    private val owner = Thread.currentThread()
    private val models = ScannerModels(context.applicationContext)
    private val segmenter = Segmenter(models)
    private val dewarper = Dewarper(models)
    private var closed = false
    init { OpenCvRuntime.ensure() }
    private fun checkWorker() { check(Thread.currentThread() == owner && !closed) { "ReceiptScanner requires its owning worker and an open session" } }
    private fun diagnostics(meta: Map<String, Any?>, t: ScanTimings) = ScanDiagnostics(meta.toMap(), t.snapshot(), models.actualEps())

    fun preview(frame: Mat): ScanPreview {
        checkWorker(); require(frame.type() == CvType.CV_8UC3 && !frame.empty())
        models.beginCall(); val t = ScanTimings(); val meta = linkedMapOf<String, Any?>()
        CvScope().use { s ->
            val mask = segmenter.segment(s, frame, true, t, meta)
                ?: return ScanPreview(emptyList(), 0.0, null, null, null, null, false, null, null, diagnostics(meta, t))
            val source = t.measure("geometry") { ReceiptGeometry.source(s, mask, preview = true) }
            meta.putAll(source.meta)
            val moments = org.opencv.geometry.Geometry.moments(mask, true)
            return ScanPreview(source.outline.asList().chunked(2).map { ScanPoint(it[0].toDouble(), it[1].toDouble()) },
                meta["mask_area_ratio"] as Double, ScanPoint(moments.m10 / moments.m00, moments.m01 / moments.m00),
                (meta["model_score"] as Number).toDouble(), (meta["fit_inlier_residual_work_px"] as Double) / (meta["geometry_scale"] as Double),
                meta["fit_outlier_fraction"] as Double, meta["preview_relaxed_end"] as Boolean, source.topVisible, source.bottomVisible, diagnostics(meta, t))
        }
    }
    fun detect(photo: ByteArray): ScanDetection {
        checkWorker(); models.beginCall(); val t = ScanTimings(); val meta = linkedMapOf<String, Any?>()
        CvScope().use { s -> return detect(s, decode(s, photo, t), t, meta) }
    }
    private fun detect(s: CvScope, im: Mat, t: ScanTimings, meta: MutableMap<String, Any?>): ScanDetection {
        val mask = segmenter.segment(s, im, false, t, meta) ?: return ScanDetection.NotDetected(im.cols(), im.rows(), "No candidate covers all prompts with area 3%-85%", diagnostics(meta, t))
        val source = t.measure("geometry") { ReceiptGeometry.source(s, mask) }
        meta["source_geometry"] = source.meta
        val contour = ScanContour.fromSource(im.cols(), im.rows(), source)
        val pixels = ReceiptGeometry.bytes(mask)
        mask.release()
        return ScanDetection.Found(ScanMask(im.cols(), im.rows(), pixels), contour, diagnostics(meta, t), source)
    }
    /** The unchanged detection keeps the original mask; no contour approximation or EdgeSAM rerun. */
    fun render(photo: ByteArray, detection: ScanDetection.Found): ScanRender {
        checkWorker(); models.beginCall(); val t = ScanTimings()
        CvScope().use { s ->
            val im = decode(s, photo, t)
            require(im.cols() == detection.mask.width && im.rows() == detection.mask.height) { "Detection and upright photo dimensions differ" }
            return render(s, im, detection.mask.mat(s), detection.source, t, detection.diagnostics.geometry.toMutableMap())
        }
    }
    /** Edited contour -> filled mask -> geometry -> SyntheticDoc; never runs EdgeSAM. */
    fun render(photo: ByteArray, contour: ScanContour): ScanRender {
        checkWorker(); models.beginCall(); val t = ScanTimings(); val meta = linkedMapOf<String, Any?>()
        CvScope().use { s ->
            val im = decode(s, photo, t)
            require(im.cols() == contour.width && im.rows() == contour.height) { "Contour and upright photo dimensions differ" }
            val mask = t.measure("contour_mask") { contour.toMask().mat(s) }
            val source = t.measure("geometry") { ReceiptGeometry.source(s, mask) }.let {
                // Edited end visibility is authoritative (the user can mark a cut-off end).
                it.copy(topVisible = contour.topInFrame, bottomVisible = contour.bottomInFrame,
                    meta = it.meta + mapOf("top_in_frame" to contour.topInFrame, "bottom_in_frame" to contour.bottomInFrame))
            }
            return render(s, im, mask, source, t, meta)
        }
    }
    fun process(photo: ByteArray): ScanProcessResult {
        checkWorker(); models.beginCall(); val t = ScanTimings(); val meta = linkedMapOf<String, Any?>()
        CvScope().use { s ->
            val im = decode(s, photo, t)
            return when (val detection = detect(s, im, t, meta)) {
                is ScanDetection.NotDetected -> ScanProcessResult.NotDetected(detection)
                is ScanDetection.Found -> ScanProcessResult.Processed(detection, render(s, im, detection.mask.mat(s), detection.source, t, meta))
            }
        }
    }
    private fun render(s: CvScope, im: Mat, mask: Mat, source: ReceiptGeometry.Source, t: ScanTimings, meta: MutableMap<String, Any?>): ScanRender {
        val jpeg = dewarper.render(s, im, mask, source, t, meta)
        return ScanRender(jpeg, meta["width"] as Int, meta["height"] as Int, diagnostics(meta, t))
    }
    override fun close() { checkWorker(); closed = true; models.close() }
    internal companion object {
        fun decode(s: CvScope, photo: ByteArray, t: ScanTimings): Mat = t.measure("jpeg_decode") {
            val encoded = s.own(MatOfByte(*photo))
            // OpenCV IMREAD_COLOR applies EXIF orientations 1..8, including mirror/transpose.
            // Do NOT add IMREAD_IGNORE_ORIENTATION (ReceiptBench inputs were pre-oriented).
            val im = s.own(Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR))
            encoded.release(); require(!im.empty()) { "Cannot decode receipt photo" }; im
        }
    }
}
