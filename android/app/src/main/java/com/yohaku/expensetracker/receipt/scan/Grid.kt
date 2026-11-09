package com.yohaku.expensetracker.receipt.scan

import com.yohaku.expensetracker.receipt.scan.Numerics.round
import com.yohaku.expensetracker.receipt.scan.Numerics.poly
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

internal class Grid(val w: Int, val h: Int, val a: FloatArray) {
    init { require(a.size == w * h * 2 && a.all { it.isFinite() }) { "invalid grid" } }
    fun at(x: Int, y: Int, c: Int) = a[(y * w + x) * 2 + c]
    companion object {
        fun fromChw(chw: FloatArray): Grid {
            require(chw.size == 2 * 31 * 45)
            val a = FloatArray(chw.size)
            for (i in 0 until 31 * 45) { a[2 * i] = chw[i]; a[2 * i + 1] = chw[i + 31 * 45] }
            return Grid(45, 31, a)
        }
    }
    fun extend(): Grid {
        val py = round((h - 1) * .25); val px = round((w - 1) * .25)
        val sy = max(1, round(3.0 * (h - 1) / 44)); val sx = max(1, round(3.0 * (w - 1) / 30))
        val eh = h + 2 * py; val ew = w + 2 * px
        val yext = FloatArray(eh * w * 2)
        a.copyInto(yext, py * w * 2)
        for (x in 0 until w) for (c in 0..1) for (i in 0 until py) {
            yext[((py - 1 - i) * w + x) * 2 + c] = at(x, 0, c) - (i + 1) * ((at(x, sy, c) - at(x, 0, c)) / sy)
            yext[((py + h + i) * w + x) * 2 + c] = at(x, h - 1, c) + (i + 1) * ((at(x, h - 1, c) - at(x, h - 1 - sy, c)) / sy)
        }
        val out = FloatArray(eh * ew * 2)
        for (y in 0 until eh) {
            yext.copyInto(out, (y * ew + px) * 2, y * w * 2, (y + 1) * w * 2)
            for (c in 0..1) for (i in 0 until px) {
                val l = yext[y * w * 2 + c]; val r = yext[(y * w + w - 1) * 2 + c]
                out[(y * ew + px - 1 - i) * 2 + c] = l - (i + 1) * ((yext[(y * w + sx) * 2 + c] - l) / sx)
                out[(y * ew + px + w + i) * 2 + c] = r + (i + 1) * ((r - yext[(y * w + w - 1 - sx) * 2 + c]) / sx)
            }
        }
        return Grid(ew, eh, out)
    }
    fun folds(mask: Mat, meta: MutableMap<String, Any?>) {
        var fg = 0; var bg = 0; val mw = mask.cols(); val mh = mask.rows(); val mb = ReceiptGeometry.bytes(mask)
        for (y in 0 until h - 1) for (x in 0 until w - 1) {
            val dx0 = at(x + 1, y, 0) - at(x, y, 0); val dx1 = at(x + 1, y, 1) - at(x, y, 1)
            val dy0 = at(x, y + 1, 0) - at(x, y, 0); val dy1 = at(x, y + 1, 1) - at(x, y, 1)
            if (dx0 * dy1 - dx1 * dy0 > 0) continue
            var minx = Double.POSITIVE_INFINITY; var miny = minx; var maxx = Double.NEGATIVE_INFINITY; var maxy = maxx
            for (j in 0..1) for (i in 0..1) {
                val px = (at(x + i, y + j, 0) + 1) * ((mw - 1) / 2.0); val py = (at(x + i, y + j, 1) + 1) * ((mh - 1) / 2.0)
                minx = min(minx, px); maxx = max(maxx, px); miny = min(miny, py); maxy = max(maxy, py)
            }
            val x0 = max(0, floor(minx).toInt()); val y0 = max(0, floor(miny).toInt())
            val x1 = min(mw, ceil(maxx).toInt() + 1); val y1 = min(mh, ceil(maxy).toInt() + 1)
            var hit = false
            for (j in y0 until y1) {
                for (i in x0 until x1) if (mb[j * mw + i] != 0.toByte()) { hit = true; break }
                if (hit) break
            }
            if (hit) fg++ else bg++
        }
        meta["grid_fold_foreground_cells"] = fg; meta["grid_fold_background_cells"] = bg
        require(fg == 0) { "SyntheticDoc folded in $fg receipt cells" }
    }
    fun warpMasks(s: CvScope, mask: Mat, valid: Mat, cw: Int, ch: Int): Array<Mat> {
        val block = 96; val horizontal = GridMath.horizontal(a, w, h, cw)
        val xs = FloatArray(block * cw); val ys = FloatArray(block * cw)
        val mx = s.own(Mat(block, cw, CvType.CV_32F)); val my = s.own(Mat(block, cw, CvType.CV_32F))
        val wm = s.own(Mat(ch, cw, CvType.CV_8U)); val wv = s.own(Mat(ch, cw, CvType.CV_8U))
        for (start in 0 until ch step block) {
            val rows = min(block, ch - start)
            GridMath.pixelRows(horizontal, h, cw, ch, start, rows, (mask.cols() - 1) / 2f, (mask.rows() - 1) / 2f, xs, ys)
            mx.put(0, 0, xs); my.put(0, 0, ys)
            CvScope().use { stripe ->
                val x = stripe.own(mx.rowRange(0, rows)); val y = stripe.own(my.rowRange(0, rows))
                val m = stripe.own(wm.rowRange(start, start + rows)); val v = stripe.own(wv.rowRange(start, start + rows))
                Imgproc.remap(mask, m, x, y, Imgproc.INTER_NEAREST, Core.BORDER_CONSTANT, Scalar.all(0.0))
                Imgproc.remap(valid, v, x, y, Imgproc.INTER_NEAREST, Core.BORDER_CONSTANT, Scalar.all(0.0))
            }
        }
        mx.release(); my.release()
        return arrayOf(wm, wv)
    }
    data class Maps(val x: Mat, val y: Mat, val outside: Mat)
    fun compose(s: CvScope, r: ReceiptGeometry.Ribbon, back: DoubleArray, sw: Int, sh: Int, cw: Int, ch: Int, cropw: Int, croph: Int): Maps {
        val mx = s.own(Mat(r.oh, r.ow, CvType.CV_32F)); val my = s.own(Mat(r.oh, r.ow, CvType.CV_32F)); val outside = s.own(Mat(r.oh, r.ow, CvType.CV_8U))
        val block = 96; val xs = FloatArray(block * r.ow); val ys = FloatArray(block * r.ow); val u = GridMath.unitCoordinates(r.ow); val bad = ByteArray(block * r.ow)
        for (start in 0 until r.oh step block) {
            val rows = min(block, r.oh - start)
            for (row in 0 until rows) {
                val y = (r.y0 + (start + row) * ((r.y1 - r.y0) / (r.oh - 1))).toFloat(); val z = (y - r.mid) / r.half
                GridMath.composeRow(a, w, h, u, y, poly(r.left, z).toFloat(), poly(r.right, z).toFloat(), r.top, r.bottom, back, sw, sh, cw, ch, cropw, croph, xs, ys, bad, row * r.ow)
            }
            mx.put(start, 0, if (rows == block) xs else xs.copyOf(rows * r.ow))
            my.put(start, 0, if (rows == block) ys else ys.copyOf(rows * r.ow))
            outside.put(start, 0, bad, 0, rows * r.ow)
        }
        return Maps(mx, my, outside)
    }
}
