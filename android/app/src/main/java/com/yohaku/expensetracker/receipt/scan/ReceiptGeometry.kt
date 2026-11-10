package com.yohaku.expensetracker.receipt.scan

import com.yohaku.expensetracker.receipt.scan.Numerics.median
import com.yohaku.expensetracker.receipt.scan.Numerics.percentile
import com.yohaku.expensetracker.receipt.scan.Numerics.poly
import com.yohaku.expensetracker.receipt.scan.Numerics.robustFit
import com.yohaku.expensetracker.receipt.scan.Numerics.round
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/** ReceiptBench geometry with EXIF-relative deskew limited to +/-45 degrees; D7a units retained. */
internal object ReceiptGeometry {
    fun axis(xx: Double, xy: Double, yy: Double): DoubleArray {
        val angle = .5 * atan2(2 * xy, xx - yy)
        // Compute the angle before sin/cos so exact diagonal ties stay at +/-45 degrees.
        val fromVertical = if (angle >= 0) PI / 2 - angle else -PI / 2 - angle
        val deskew = ScanOrientation.deskewRadians(fromVertical)
        var vx = cos(angle); var vy = sin(angle)
        if (deskew != fromVertical) { val x = vx; vx = -vy; vy = x }
        if (vy < 0) { vx = -vx; vy = -vy }
        return doubleArrayOf(vx, vy)
    }
    fun transform(a: DoubleArray, x: Double, y: Double) = doubleArrayOf(a[0] * x + a[1] * y + a[2], a[3] * x + a[4] * y + a[5])
    fun affine(s: CvScope, a: DoubleArray) = s.own(Mat(2, 3, CvType.CV_64F)).also { it.put(0, 0, *a) }
    fun inverse(s: CvScope, a: DoubleArray): DoubleArray {
        val inv = s.mat()
        org.opencv.geometry.Geometry.invertAffineTransform(affine(s, a), inv)
        return DoubleArray(6).also { inv.get(0, 0, it) }
    }
    fun warp(s: CvScope, src: Mat, a: DoubleArray, w: Int, h: Int, interpolation: Int): Mat = s.mat().also {
        Imgproc.warpAffine(src, it, affine(s, a), Size(w.toDouble(), h.toDouble()), interpolation, Core.BORDER_CONSTANT, Scalar.all(0.0))
    }
    fun bytes(m: Mat) = ByteArray((m.total() * m.channels()).toInt()).also { m.get(0, 0, it) }
    private fun bbox(m: Mat): IntArray {
        val b = org.opencv.geometry.Geometry.boundingRect(m)
        requireGeometry(b.width != 0 && b.height != 0) { "empty warped receipt mask" }
        return intArrayOf(b.x, b.y, b.x + b.width, b.y + b.height)
    }
    fun clean(s: CvScope, raw: Mat, meta: MutableMap<String, Any?>): Mat {
        val labels = s.mat(); val n = Imgproc.connectedComponents(raw, labels, 8, CvType.CV_32S)
        requireGeometry(n > 1) { "empty EdgeSAM mask" }
        val h = raw.rows(); val w = raw.cols(); var chosen = labels.get(h / 2, w / 2)[0].toInt(); val center = chosen != 0
        val contours = ArrayList<MatOfPoint>(); val hierarchy = s.mat()
        try {
            if (!center) {
                Imgproc.findContours(raw, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                var best = Double.POSITIVE_INFINITY; var p: Point? = null
                for (c in contours) for (q in c.toArray()) {
                    val d = (q.x - w / 2.0) * (q.x - w / 2.0) + (q.y - h / 2.0) * (q.y - h / 2.0)
                    if (d < best) { best = d; p = q }
                }
                checkNotNull(p) { "Nonempty mask has no contour" }
                chosen = labels.get(p.y.toInt(), p.x.toInt())[0].toInt()
            }
            contours.forEach { it.release() }; contours.clear()
            val out = s.mat()
            Core.compare(labels, Scalar(chosen.toDouble()), out, Core.CMP_EQ)
            Imgproc.findContours(out, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            Imgproc.drawContours(out, contours, -1, Scalar.all(255.0), Imgproc.FILLED)
            meta["center_in_mask"] = center; meta["mask_area_ratio"] = Core.countNonZero(out).toDouble() / (w * h)
            return out
        } finally { contours.forEach { it.release() }; labels.release() }
    }
    data class Crop(val image: Mat, val mask: Mat, val valid: Mat, val back: DoubleArray, val meta: Map<String, Any?>)
    fun upright(s: CvScope, im: Mat, mask: Mat): Crop {
        val m = org.opencv.geometry.Geometry.moments(mask, true)
        requireGeometry(m.m00 > 0) { "empty source mask" }
        val v = axis(m.mu20, m.mu11, m.mu02); val w = im.cols(); val h = im.rows()
        val a = doubleArrayOf(v[1], -v[0], 0.0, v[0], v[1], 0.0)
        var minx = 0.0; var miny = 0.0; var maxx = 0.0; var maxy = 0.0
        for (p in arrayOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(w - 1.0, 0.0), doubleArrayOf(w - 1.0, h - 1.0), doubleArrayOf(0.0, h - 1.0))) {
            val q = transform(a, p[0], p[1]); minx = min(minx, q[0]); miny = min(miny, q[1]); maxx = max(maxx, q[0]); maxy = max(maxy, q[1])
        }
        a[2] = -minx; a[5] = -miny
        val rw = ceil(maxx - minx + 1).toInt(); val rh = ceil(maxy - miny + 1).toInt()
        val rot = warp(s, im, a, rw, rh, Imgproc.INTER_LINEAR); val rm = warp(s, mask, a, rw, rh, Imgproc.INTER_NEAREST)
        val ones = s.own(Mat(h, w, CvType.CV_8U, Scalar.all(1.0))); val valid = warp(s, ones, a, rw, rh, Imgproc.INTER_NEAREST)
        ones.release()
        val b = bbox(rm); val pad = round(min(b[2] - b[0], b[3] - b[1]) * .1)
        b[0] = max(0, b[0] - pad); b[1] = max(0, b[1] - pad); b[2] = min(rw, b[2] + pad); b[3] = min(rh, b[3] + pad)
        val rect = Rect(b[0], b[1], b[2] - b[0], b[3] - b[1])
        val ci = cloneRegion(s, rot, rect); val cm = cloneRegion(s, rm, rect); val cv = cloneRegion(s, valid, rect)
        rot.release(); rm.release(); valid.release()
        val back = inverse(s, a)
        back[2] += back[0] * b[0] + back[1] * b[1]; back[5] += back[3] * b[0] + back[4] * b[1]
        return Crop(ci, cm, cv, back, linkedMapOf(
            "input_axis_degrees" to Math.toDegrees(atan2(v[0], v[1])), "rotation_affine" to matrix(a),
            "rotation_canvas_wh" to intArrayOf(rw, rh), "rotated_crop_bbox" to b,
            "crop_to_original_affine" to matrix(back), "crop_margin" to .1))
    }
    private fun cloneRegion(s: CvScope, m: Mat, rect: Rect): Mat {
        val view = m.submat(rect)
        try { return s.own(view.clone()) } finally { view.release() }
    }
    fun matrix(a: DoubleArray) = arrayOf(a.copyOfRange(0, 3), a.copyOfRange(3, 6))

    open class Edges(m: Mat, valid: Mat, ribbon: Boolean) {
        val w = m.cols(); val h = m.rows(); val mask = bytes(m)
        val lo: Double; val hi: Double; val mid: Double; val half: Double
        val left: DoubleArray; val right: DoubleArray
        val meta = linkedMapOf<String, Any?>()
        init {
            val vb = bytes(valid); val l = IntArray(h); val r = IntArray(h); val vl = IntArray(h); val vr = IntArray(h)
            MaskBounds.rows(mask, w, h, l, r); MaskBounds.rows(vb, w, h, vl, vr)
            val ys = (0 until h).filter { r[it] >= 0 }
            requireGeometry(ys.size >= if (ribbon) 100 else 2) { "too few ribbon rows" }
            lo = ys.first().toDouble(); hi = ys.last().toDouble(); mid = (lo + hi) / 2; half = (hi - lo) / 2
            // Absolute sampling span, not an aspect-ratio requirement: wide, short paper is valid.
            requireGeometry(ribbon || half >= 20) { "mask longitudinal span too small" }
            val med = median(ys.map { (r[it] - l[it]).toDouble() }.toDoubleArray())
            val lx = ArrayList<Double>(); val ly = ArrayList<Double>(); val rx = ArrayList<Double>(); val ry = ArrayList<Double>()
            for (y in ys) if (r[y] - l[y] > .55 * med && y > lo + .035 * (hi - lo) && y < hi - .035 * (hi - lo)) {
                if (l[y] > vl[y] + 2) { lx.add((y - mid) / half); ly.add(l[y].toDouble()) }
                if (r[y] < vr[y] - 2) { rx.add((y - mid) / half); ry.add(r[y].toDouble()) }
            }
            val lf = robustFit(lx.toDoubleArray(), ly.toDoubleArray(), 3, med); val rf = robustFit(rx.toDoubleArray(), ry.toDoubleArray(), 3, med)
            left = lf.c; right = rf.c
            var sum = 0.0; var insideSum = 0.0; var count = 0; var insideCount = 0
            for (f in arrayOf(lf, rf)) for (i in f.residual.indices) {
                val d = f.residual[i]; sum += d * d; count++
                if (f.inliers[i]) { insideSum += d * d; insideCount++ }
            }
            meta["fit_residual_work_px"] = sqrt(sum / count); meta["fit_inlier_residual_work_px"] = sqrt(insideSum / insideCount)
            meta["fit_outlier_fraction"] = 1 - insideCount.toDouble() / count
        }
        fun side(c: DoubleArray, y: Double) = poly(c, (y - mid) / half)
        fun end(top: Boolean, conservative: Boolean): DoubleArray {
            val end = if (top) lo else hi; val l = side(left, end); val r = side(right, end)
            val xx = ArrayList<Double>(); val yy = ArrayList<Double>()
            for (x in max(0, (l + .15 * (r - l)).toInt()) until min(w, (r - .15 * (r - l)).toInt())) {
                var found = -1
                for (i in 0..(hi - lo).toInt()) {
                    val y = if (top) lo.toInt() + i else hi.toInt() - i
                    if (mask[y * w + x] != 0.toByte()) { found = y; break }
                }
                if (found >= 0 && abs(found - end) < .15 * (hi - lo)) { xx.add(x.toDouble() / w); yy.add(found.toDouble()) }
            }
            val f = robustFit(xx.toDoubleArray(), yy.toDoubleArray(), 1, r - l)
            val offset = if (conservative) percentile(f.residual.filterIndexed { i, _ -> f.inliers[i] }.toDoubleArray(), if (top) .1 else .9) else 0.0
            return doubleArrayOf(f.c[0] + offset, f.c[1] / w)
        }
        fun intersect(c: DoubleArray, line: DoubleArray, start: Double, limit: Double): Double {
            var y = start
            repeat(20) {
                val x = side(c, y); val z = (y - mid) / half
                val d = (c[1] + z * (2 * c[2] + z * 3 * c[3])) / half; val den = 1 - line[1] * d
                requireGeometry(abs(den) >= .05) { "unstable ribbon/end intersection" }
                y -= (y - line[0] - line[1] * x) / den
            }
            requireGeometry(abs(y - start) <= limit * (hi - lo)) { "end intersection outside fit domain" }
            return y
        }
    }
    class Ribbon(m: Mat, valid: Mat, tv: Boolean, bv: Boolean) : Edges(m, valid, true) {
        val top = if (tv) end(true, true) else null; val bottom = if (bv) end(false, true) else null
        val y0 = max(0.0, if (top == null) lo else floor(min(intersect(left, top, lo, .3), intersect(right, top, lo, .3))))
        val y1 = min(h - 1.0, if (bottom == null) hi else ceil(max(intersect(left, bottom, hi, .3), intersect(right, bottom, hi, .3))))
        val ow: Int; val oh: Int
        init {
            val widths = DoubleArray(512) {
                val y = y0 + (y1 - y0) * it / 511
                (side(right, y) - side(left, y)).also { width -> requireGeometry(width >= 10) { "crossing fitted ribbon" } }
            }
            requireGeometry(y1 - y0 >= 100) { "degenerate ribbon" }
            val rw = round(median(widths)) + 1; val rh = round(y1 - y0) + 1; val scale = max(1.0, 2000.0 / max(rw, rh))
            ow = round(rw * scale); oh = round(rh * scale)
            require(ow.toLong() * oh <= 35000000 && max(ow, oh) < 32767) { "ribbon output exceeds memory/remap bound" }
            meta.putAll(mapOf("ribbon_left_poly" to left, "ribbon_right_poly" to right, "ribbon_y_mid" to mid, "ribbon_y_half" to half,
                "ribbon_y_range" to doubleArrayOf(y0, y1), "ribbon_top_line" to top, "ribbon_bottom_line" to bottom, "output_scale" to scale))
        }
    }
    data class Source(val topVisible: Boolean, val bottomVisible: Boolean, val outline: FloatArray, val meta: Map<String, Any?>)
    fun source(s: CvScope, mask: Mat, preview: Boolean = false): Source {
        val h = mask.rows(); val w = mask.cols()
        requireGeometry(Core.countNonZero(mask) >= .01 * h * w) { "mask covers less than 1% of frame" }
        val scale = 1200.0 / max(h, w); val sm = s.mat()
        Imgproc.resize(mask, sm, Size(round(w * scale).toDouble(), round(h * scale).toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST)
        val b = bytes(sm); val sw = sm.cols(); val sh = sm.rows()
        var idx = 0; var n = 0; var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        for (y in 0 until sh) for (x in 0 until sw) if (b[y * sw + x] != 0.toByte()) {
            if (idx++ % 4 == 0) { n++; sx += x; sy += y; sxx += x.toDouble() * x; sxy += x.toDouble() * y; syy += y.toDouble() * y }
        }
        requireGeometry(idx >= 100) { "mask too small for fitting" }
        val v = axis(sxx - sx * sx / n, sxy - sx * sy / n, syy - sy * sy / n)
        val a = doubleArrayOf(v[1], -v[0], 0.0, v[0], v[1], 0.0)
        val frame = arrayOf(doubleArrayOf(0.0, 0.0), doubleArrayOf((w - 1) * scale, 0.0), doubleArrayOf((w - 1) * scale, (h - 1) * scale), doubleArrayOf(0.0, (h - 1) * scale))
        var minx = 0.0; var miny = 0.0; var maxx = 0.0; var maxy = 0.0
        for (i in 0..3) {
            frame[i] = transform(a, frame[i][0], frame[i][1]); minx = min(minx, frame[i][0]); maxx = max(maxx, frame[i][0]); miny = min(miny, frame[i][1]); maxy = max(maxy, frame[i][1])
        }
        a[2] = 2 - minx; a[5] = 2 - miny
        for (p in frame) { p[0] += a[2]; p[1] += a[5] }
        val rw = ceil(maxx + a[2] + 3).toInt(); val rh = ceil(maxy + a[5] + 3).toInt()
        val rot = warp(s, sm, a, rw, rh, Imgproc.INTER_NEAREST)
        val ones = s.own(Mat(sh, sw, CvType.CV_8U, Scalar.all(1.0))); val valid = warp(s, ones, a, rw, rh, Imgproc.INTER_NEAREST)
        val e = Edges(rot, valid, false)
        val lines = Array(2) { DoubleArray(2) }; val visible = booleanArrayOf(true, true)
        for (end in 0..1) {
            var bestCount = 0; var bestEdge = -1
            for (edge in 0..3) {
                var count = 0; var total = 0; val len = if (edge % 2 == 0) sw else sh
                for (i in 0 until len) {
                    var hit = false
                    for (j in 0..2) {
                        val x = when (edge) { 0, 2 -> i; 1 -> sw - 1 - j; else -> j }
                        val y = when (edge) { 0 -> j; 2 -> sh - 1 - j; else -> i }
                        if (b[y * sw + x] != 0.toByte()) hit = true
                    }
                    if (!hit) continue
                    total++
                    val x = if (edge % 2 == 0) i else if (edge == 1) sw - 1 else 0
                    val y = if (edge % 2 == 1) i else if (edge == 2) sh - 1 else 0
                    val q = transform(a, x.toDouble(), y.toDouble())[1]
                    if (if (end == 0) q < e.lo + .08 * (e.hi - e.lo) else q > e.hi - .08 * (e.hi - e.lo)) count++
                }
                if (total >= 3 && count >= 3 && count >= bestCount) { bestCount = count; bestEdge = edge }
            }
            if (bestEdge >= 0) {
                val p = frame[bestEdge]; val q = frame[(bestEdge + 1) % 4]
                requireGeometry(abs(q[0] - p[0]) >= 1) { "end exits lateral frame edge" }
                val slope = (q[1] - p[1]) / (q[0] - p[0]); lines[end] = doubleArrayOf(p[1] - slope * p[0], slope); visible[end] = false
            } else lines[end] = e.end(end == 0, false)
        }
        var relaxed = false; var relaxedReason: String? = null
        val ends = try {
            doubleArrayOf(e.intersect(e.left, lines[0], e.lo, .25), e.intersect(e.right, lines[0], e.lo, .25), e.intersect(e.left, lines[1], e.hi, .25), e.intersect(e.right, lines[1], e.hi, .25))
        } catch (error: ScanGeometryUnavailable) {
            if (!preview) throw error
            relaxed = true; relaxedReason = error.message
            doubleArrayOf(e.lo, e.lo, e.hi, e.hi)
        }
        val (tl, tr, bl, br) = ends
        requireGeometry(min(bl - tl, br - tr) >= 40) { "crossed or degenerate end lines" }
        for (i in 0 until 512) {
            val t = i / 511.0
            requireGeometry(e.side(e.right, tr + t * (br - tr)) - e.side(e.left, tl + t * (bl - tl)) >= 5) { "fitted source sides cross" }
        }
        val inv = inverse(s, a); val outline = FloatArray(128 * 2)
        for (i in 0 until 128) {
            val left = i < 64; val t = if (left) i / 63.0 else (127 - i) / 63.0
            val y = if (left) tl + t * (bl - tl) else tr + t * (br - tr)
            val p = transform(inv, e.side(if (left) e.left else e.right, y), y)
            outline[2 * i] = (p[0] / scale).toFloat(); outline[2 * i + 1] = (p[1] / scale).toFloat()
        }
        val meta = linkedMapOf<String, Any?>("preview_relaxed_end" to relaxed, "preview_relaxed_reason" to relaxedReason,
            "top_in_frame" to visible[0], "bottom_in_frame" to visible[1], "angle_degrees" to Math.toDegrees(atan2(v[0], v[1])),
            "left_poly" to e.left, "right_poly" to e.right, "top_line" to lines[0], "bottom_line" to lines[1],
            "fit_y_mid" to e.mid, "fit_y_half" to e.half, "geometry_scale" to scale, "rotation_affine" to matrix(a))
        // Additional preview diagnostics; does not change D7a geometry.
        meta.putAll(e.meta)
        sm.release(); rot.release(); valid.release(); ones.release()
        return Source(visible[0], visible[1], outline, meta)
    }
}
