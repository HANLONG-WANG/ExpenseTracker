package com.yohaku.expensetracker.receipt.scan

import android.media.ExifInterface
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import java.io.File
import kotlin.math.*

/** CPU integration tests, procedural assets only. No activity, camera, permissions or private photos. */
@RunWith(AndroidJUnit4::class)
class ReceiptScannerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun asset(name: String) = instrumentation.context.assets.open("receipt-scanner/$name").use { it.readBytes() }
    private val manifest get() = JSONObject(String(asset("manifest.json")))
    private fun json(name: String) = JSONObject(String(asset(name)))
    private fun decode(s: CvScope, bytes: ByteArray, flag: Int = Imgcodecs.IMREAD_COLOR): Mat {
        val encoded = s.own(MatOfByte(*bytes))
        return s.own(Imgcodecs.imdecode(encoded, flag)).also { assertFalse(it.empty()); encoded.release() }
    }
    private fun encode(s: CvScope, im: Mat): ByteArray {
        val encoded = s.own(MatOfByte()); assertTrue(Imgcodecs.imencode(".png", im, encoded)); return encoded.toArray()
    }
    private fun iou(a: ByteArray, b: ByteArray): Double {
        assertEquals(a.size, b.size)
        var intersection = 0; var union = 0
        for (i in a.indices) { if (a[i] != 0.toByte() || b[i] != 0.toByte()) union++; if (a[i] != 0.toByte() && b[i] != 0.toByte()) intersection++ }
        assertTrue(union > 0); return intersection.toDouble() / union
    }
    private fun cpu(diagnostics: ScanDiagnostics, keys: Set<String>) {
        assertEquals(keys, diagnostics.actualEps.keys)
        assertTrue("${diagnostics.actualEps}", diagnostics.actualEps.values.all { it == "CPU" })
    }
    private fun geometry(expected: JSONObject, actual: Map<String, Any?>) {
        val fields = manifest.getJSONArray("geometry_fields"); val limits = manifest.getJSONObject("limits")
        fun compare(path: String, a: Any?, b: Any?, limit: Double) {
            when {
                a == JSONObject.NULL || a == null -> assertTrue("$path: $b", b == null)
                a is Boolean -> assertEquals(path, a, b)
                a is JSONArray -> {
                    val values = when (b) { is DoubleArray -> b.toList(); is IntArray -> b.toList(); is Array<*> -> b.toList(); else -> error("$path missing array: $b") }
                    assertEquals(path, a.length(), values.size)
                    for (i in values.indices) compare("$path[$i]", a.get(i), values[i], limit)
                }
                a is Number -> { assertTrue("$path missing number", b is Number); assertEquals(path, a.toDouble(), (b as Number).toDouble(), limit) }
                else -> error("Unexpected geometry field $path=$a")
            }
        }
        for (i in 0 until fields.length()) {
            val field = fields.getString(i)
            assertTrue("Missing $field", actual.containsKey(field))
            val limit = when (field) { "width", "height" -> 0.0; "input_axis_degrees" -> limits.getDouble("angle_difference_max_deg"); else -> limits.getDouble("geometry_coordinate_difference_max_px") }
            compare(field, expected.get(field), actual[field], limit)
        }
    }
    /** Same phase/ECC/identity translation-only candidates and 10px interior as compare_device.py. */
    private fun alignedMae(s: CvScope, reference: Mat, actual: Mat): Double {
        assertEquals(reference.size(), actual.size())
        val scale = min(1.0, 1000.0 / max(reference.rows(), reference.cols()))
        fun gray(im: Mat): Mat {
            val g = s.mat(); val small = s.mat(); val f = s.mat()
            Imgproc.cvtColor(im, g, Imgproc.COLOR_BGR2GRAY)
            Imgproc.resize(g, small, Size(), scale, scale, Imgproc.INTER_AREA); small.convertTo(f, CvType.CV_32F)
            g.release(); small.release(); return f
        }
        val a = gray(reference); val b = gray(actual); val window = s.mat()
        Imgproc.createHanningWindow(window, a.size(), CvType.CV_32F)
        val phase = Imgproc.phaseCorrelate(s.own(a.clone()), s.own(b.clone()), window)
        val warp = s.own(Mat(2, 3, CvType.CV_32F))
        warp.put(0, 0, floatArrayOf(1f, 0f, phase.x.toFloat(), 0f, 1f, phase.y.toFloat()))
        val candidates = mutableListOf(Point(0.0, 0.0), Point(phase.x / scale, phase.y / scale))
        try {
            Video.findTransformECC(a, b, warp, Video.MOTION_TRANSLATION, TermCriteria(TermCriteria.EPS or TermCriteria.COUNT, 40, 1e-5))
            candidates.add(Point(warp.get(0, 2)[0] / scale, warp.get(1, 2)[0] / scale))
        } catch (error: CvException) { Log.w("ReceiptScannerTest", "ECC failed; using phase/identity as reference does", error) }
        val bound = manifest.getJSONObject("limits").getDouble("alignment_translation_max_px")
        val border = bound.toInt() + 2; var best = Double.POSITIVE_INFINITY
        val interior = Rect(border, border, actual.cols() - border * 2, actual.rows() - border * 2)
        for (shift in candidates) {
            if (!shift.x.isFinite() || !shift.y.isFinite() || max(abs(shift.x), abs(shift.y)) > bound) continue
            CvScope().use { stripe ->
                val matrix = stripe.own(Mat(2, 3, CvType.CV_32F))
                matrix.put(0, 0, floatArrayOf(1f, 0f, shift.x.toFloat(), 0f, 1f, shift.y.toFloat()))
                val picture = stripe.mat(); val diff = stripe.mat()
                Imgproc.warpAffine(actual, picture, matrix, actual.size(), Imgproc.INTER_LINEAR or Imgproc.WARP_INVERSE_MAP)
                Core.absdiff(reference, picture, diff)
                val region = stripe.own(diff.submat(interior)); val mean = Core.mean(region).`val`
                best = min(best, (mean[0] + mean[1] + mean[2]) / 3)
            }
        }
        return best
    }
    @Test fun processMatchesPythonD7aAndContourRoundTripOnFiveSyntheticReceipts() {
        val cases = manifest.getJSONArray("cases"); val limits = manifest.getJSONObject("limits")
        ReceiptScanner(context).use { scanner ->
            for (i in 0 until cases.length()) {
                val name = cases.getJSONObject(i).getString("name"); val photo = asset("$name.jpg")
                val result = scanner.process(photo) as ScanProcessResult.Processed
                cpu(result.render.diagnostics, setOf("encoder", "decoder", "synthetic"))
                geometry(json("$name.geometry.json"), result.render.diagnostics.geometry)
                assertEquals(1, result.render.diagnostics.geometry["final_texture_remap_count"])
                CvScope().use { s ->
                    val expectedMask = decode(s, asset("$name.mask.png"), Imgcodecs.IMREAD_GRAYSCALE)
                    val overlap = iou(ReceiptGeometry.bytes(expectedMask), result.detection.mask.pixels)
                    assertTrue("$name mask IoU=$overlap", overlap >= limits.getDouble("mask_iou_min"))
                    val roundTrip = iou(result.detection.mask.pixels, ScanContour.fromMask(result.detection.mask).toMask().pixels)
                    assertTrue("$name contour IoU=$roundTrip", roundTrip >= .98)
                    val expected = decode(s, asset("$name.gold.jpg")); val actual = decode(s, result.render.jpeg)
                    val mae = alignedMae(s, expected, actual)
                    assertTrue("$name aligned MAE=$mae", mae <= limits.getDouble("aligned_mae_max_255"))
                    Log.i("ReceiptScannerTest", "$name mask=$overlap contour=$roundTrip mae=$mae ${result.render.width}x${result.render.height}")
                }
                if (i == 0) {
                    // Separate detect/render has the same semantics as process and does not rerun EdgeSAM.
                    val detection = scanner.detect(photo) as ScanDetection.Found
                    val rendered = scanner.render(photo, detection)
                    assertArrayEquals(result.render.jpeg, rendered.jpeg)
                    cpu(rendered.diagnostics, setOf("synthetic"))
                    val c = detection.contour
                    val edited = c.copy(left = c.left.map { it.copy(x = it.x + 8) })
                    val edit = scanner.render(photo, edited)
                    cpu(edit.diagnostics, setOf("synthetic"))
                    assertTrue(edit.width in 200..2000); assertTrue(edit.height in 1800..3000)
                    CvScope().use { s ->
                        val im = decode(s, photo); val preview = scanner.preview(im)
                        assertTrue(preview.detected); assertEquals(128, preview.outline.size)
                        assertTrue(preview.areaRatio in .03.. .85); assertNotNull(preview.center); assertNotNull(preview.fitResidualPx)
                        cpu(preview.diagnostics, setOf("encoder", "decoder"))
                    }
                }
            }
        }
    }
    @Test fun noEligibleCandidateIsANormalNotDetectedResult() {
        ReceiptScanner(context).use { scanner ->
            val photo = asset("no-receipt.png")
            val result = scanner.process(photo) as ScanProcessResult.NotDetected
            assertTrue(result.detection.reason.isNotEmpty()); assertFalse(result.detection.diagnostics.geometry["selection_eligible"] as Boolean)
            cpu(result.detection.diagnostics, setOf("encoder", "decoder"))
            assertTrue(scanner.detect(photo) is ScanDetection.NotDetected)
            CvScope().use { s -> assertFalse(scanner.preview(decode(s, photo)).detected) }
        }
    }
    @Test fun exifAllEightOrientationsAndOrientedPhotoProcessingAgree() {
        OpenCvRuntime.ensure()
        val file = File.createTempFile("scan-exif-", ".jpg", context.cacheDir)
        try {
            CvScope().use { s ->
                val photo = asset("flat.jpg"); val original = decode(s, photo)
                for (orientation in 1..8) {
                    file.writeBytes(photo)
                    ExifInterface(file.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
                    val expected = s.mat()
                    when (orientation) {
                        1 -> original.copyTo(expected)
                        2 -> Core.flip(original, expected, 1)
                        3 -> Core.rotate(original, expected, Core.ROTATE_180)
                        4 -> Core.flip(original, expected, 0)
                        5 -> Core.transpose(original, expected)
                        6 -> Core.rotate(original, expected, Core.ROTATE_90_CLOCKWISE)
                        7 -> { Core.transpose(original, expected); Core.flip(expected, expected, -1) }
                        8 -> Core.rotate(original, expected, Core.ROTATE_90_COUNTERCLOCKWISE)
                    }
                    val oriented = file.readBytes(); val decoded = ReceiptScanner.decode(s, oriented, ScanTimings())
                    assertEquals("EXIF $orientation dimensions", expected.size(), decoded.size())
                    assertEquals("EXIF $orientation pixels", 0.0, Core.norm(expected, decoded, Core.NORM_INF), 0.0)
                    if (orientation == 6) {
                        // Same upright pixel content via EXIF JPEG and lossless, pre-oriented PNG.
                        ReceiptScanner(context).use { scanner ->
                            val a = scanner.process(oriented) as ScanProcessResult.Processed
                            val b = scanner.process(encode(s, expected)) as ScanProcessResult.Processed
                            assertArrayEquals(a.detection.mask.pixels, b.detection.mask.pixels)
                            assertEquals(a.render.width, b.render.width); assertEquals(a.render.height, b.render.height)
                            assertArrayEquals(a.render.jpeg, b.render.jpeg)
                        }
                    }
                    expected.release(); decoded.release()
                }
            }
        } finally { file.delete() }
    }
}
