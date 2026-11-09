package com.yohaku.expensetracker.receipt.scan

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

internal class ScanTimings {
    private val start = System.nanoTime()
    private val times = linkedMapOf<String, Double>()
    fun <T> measure(stage: String, work: () -> T): T {
        val begin = System.nanoTime()
        try { return work() } finally { times[stage] = (times[stage] ?: 0.0) + (System.nanoTime() - begin) / 1e6 }
    }
    fun snapshot() = times.toMap() + ("total" to (System.nanoTime() - start) / 1e6)
}

internal class Segmenter(private val models: ScannerModels) {
    private val pixels = FloatArray(3 * 1024 * 1024)
    private val buffer = ByteBuffer.allocateDirect(pixels.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    fun segment(s: CvScope, im: Mat, preview: Boolean, t: ScanTimings, meta: MutableMap<String, Any?>): Mat? {
        val w = im.cols(); val h = im.rows(); val scale = 1024.0 / max(h, w)
        val nw = (w * scale + .5).toInt(); val nh = (h * scale + .5).toInt()
        t.measure("edge_preprocess") {
            val small = s.mat()
            Imgproc.resize(im, small, Size(nw.toDouble(), nh.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            PixelInputs.edge(ReceiptGeometry.bytes(small), nw, nh, pixels); small.release()
            buffer.clear(); buffer.put(pixels).rewind()
        }
        OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), buffer, longArrayOf(1, 3, 1024, 1024)).use { input ->
            t.measure("encoder") { models.run(ScannerModels.Model.ENCODER, preview, mapOf("image" to input)) }.use { enc ->
                val points = FloatArray(8); val fractions = doubleArrayOf(.35, .5, .65)
                for (i in 0..2) {
                    points[2 * i] = ((w * .5).toFloat() * (nw.toDouble() / w)).toFloat()
                    points[2 * i + 1] = ((h * fractions[i]).toFloat() * (nh.toDouble() / h)).toFloat()
                }
                ScannerModels.tensor(points, 1, 4, 2).use { p ->
                    ScannerModels.tensor(floatArrayOf(1f, 1f, 1f, -1f), 1, 4).use { labels ->
                        t.measure("decoder") { models.run(ScannerModels.Model.DECODER, preview, mapOf("image_embeddings" to enc[0] as OnnxTensor, "point_coords" to p, "point_labels" to labels)) }.use { dec ->
                            return t.measure("mask_postprocess") {
                                decodeMask(s, ScannerModels.floats(dec[0]), ScannerModels.floats(dec[1]), (dec[1] as OnnxTensor).info.shape, nw, nh, w, h, meta)
                            }
                        }
                    }
                }
            }
        }
    }
    companion object {
        /** No eligible candidate is a normal detection outcome, not an exception. */
        internal fun decodeMask(s: CvScope, scores: FloatArray, masks: FloatArray, shape: LongArray, nw: Int, nh: Int, w: Int, h: Int, meta: MutableMap<String, Any?>): Mat? {
            val mh = shape[2].toInt(); val mw = shape[3].toInt(); val count = scores.size
            val ch = Numerics.round(nh / 4.0); val cw = Numerics.round(nw / 4.0)
            val areas = DoubleArray(count); val contains = BooleanArray(count); val eligible = BooleanArray(count)
            for (k in 0 until count) {
                var positive = 0
                for (y in 0 until ch) for (x in 0 until cw) if (masks[(k * mh + y) * mw + x] > 0) positive++
                areas[k] = positive.toDouble() / (ch * cw); contains[k] = true
                for (fraction in doubleArrayOf(.35, .5, .65)) {
                    val x = Numerics.round(w * .5 * (cw.toDouble() / w)).coerceIn(0, cw - 1)
                    val y = Numerics.round(h * fraction * (ch.toDouble() / h)).coerceIn(0, ch - 1)
                    contains[k] = contains[k] && masks[(k * mh + y) * mw + x] > 0
                }
                eligible[k] = areas[k] >= .03 && areas[k] <= .85 && contains[k]
            }
            var chosen = -1
            for (k in 0 until count) if (eligible[k]) {
                if (chosen < 0 || scores[k] > scores[chosen] || (scores[k] == scores[chosen] && areas[k] > areas[chosen])) chosen = k
            }
            meta.putAll(mapOf("selected_candidate" to chosen, "candidate_scores" to scores, "candidate_areas" to areas,
                "candidate_contains_prompts" to contains, "candidate_eligible" to eligible, "selection_eligible" to (chosen >= 0), "raw_mask_shape" to shape))
            if (chosen < 0) return null
            meta["model_score"] = scores[chosen]
            val raw = s.own(Mat(mh, mw, CvType.CV_32F)); raw.put(0, 0, masks.copyOfRange(chosen * mh * mw, (chosen + 1) * mh * mw))
            val up = s.mat(); val full = s.mat()
            Imgproc.resize(raw, up, Size(1024.0, 1024.0), 0.0, 0.0, Imgproc.INTER_LINEAR)
            val crop = s.own(up.submat(0, nh, 0, nw))
            Imgproc.resize(crop, full, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            val binary = s.mat(); Core.compare(full, Scalar.all(0.0), binary, Core.CMP_GT)
            full.release(); crop.release(); up.release(); raw.release()
            val clean = ReceiptGeometry.clean(s, binary, meta); binary.release()
            return clean
        }
    }
}
