package com.yohaku.expensetracker.receipt.scan

import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

internal class Dewarper(private val models: ScannerModels) {
    fun render(s: CvScope, im: Mat, mask: Mat, source: ReceiptGeometry.Source, t: ScanTimings, meta: MutableMap<String, Any?>): ByteArray {
        meta["source_geometry"] = source.meta
        val crop = t.measure("geometry") { ReceiptGeometry.upright(s, im, mask) }
        meta.putAll(crop.meta)
        val coarse = t.measure("synthetic_preprocess") {
            val small = PilBilinear.resize(ReceiptGeometry.bytes(crop.image), crop.image.cols(), crop.image.rows(), 512, 720)
            ScannerModels.tensor(PixelInputs.synthetic(small), 1, 3, 720, 512)
        }.use { input ->
            t.measure("synthetic_inference") { models.run(ScannerModels.Model.SYNTHETIC, false, mapOf("image" to input)) }.use { prediction ->
                t.measure("grid_composition") { Grid.fromChw(ScannerModels.floats(prediction[0])) }
            }
        }
        crop.image.release()
        val extended = t.measure("grid_composition") { coarse.extend() }
        val cw = Numerics.round((crop.mask.cols() - 1.0) * (extended.w - 1) / (coarse.w - 1)) + 1
        val ch = Numerics.round((crop.mask.rows() - 1.0) * (extended.h - 1) / (coarse.h - 1)) + 1
        val work = t.measure("grid_composition") { extended.folds(crop.mask, meta); extended.warpMasks(s, crop.mask, crop.valid, cw, ch) }
        val ribbon = t.measure("geometry") { ReceiptGeometry.Ribbon(work[0], work[1], source.topVisible, source.bottomVisible) }
        meta.putAll(ribbon.meta); work.forEach { it.release() }
        val maps = t.measure("grid_composition") { extended.compose(s, ribbon, crop.back, im.cols(), im.rows(), cw, ch, crop.mask.cols(), crop.mask.rows()) }
        crop.mask.release(); crop.valid.release()
        val out = t.measure("remap") {
            s.mat().also {
                Imgproc.remap(im, it, maps.x, maps.y, Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar.all(255.0))
                it.setTo(Scalar.all(255.0), maps.outside)
            }
        }
        maps.x.release(); maps.y.release(); maps.outside.release()
        val encoded = t.measure("jpeg_encode") {
            val bytes = s.own(MatOfByte()); val params = s.own(MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 95))
            check(Imgcodecs.imencode(".jpg", out, bytes, params)) { "JPEG encode failed" }
            bytes.toArray()
        }
        out.release()
        meta.putAll(mapOf("source_top_in_frame" to source.topVisible, "source_bottom_in_frame" to source.bottomVisible,
            "grid_model" to "D7a", "uv_canvas_wh" to intArrayOf(cw, ch), "width" to ribbon.ow, "height" to ribbon.oh,
            "final_texture_remap_count" to 1, "scheme" to "D7a", "failed" to false))
        return encoded
    }
}
