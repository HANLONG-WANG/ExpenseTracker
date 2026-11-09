package com.yohaku.expensetracker.receipt.scan

import java.math.BigInteger
import org.opencv.core.*
import kotlin.math.*

/** D7a conventions: ties to even, linear percentiles, NumPy default_rng(2026). */
internal object Numerics {
    fun round(x: Double) = Math.rint(x).toInt()
    fun percentile(a: DoubleArray, p: Double): Double {
        require(a.isNotEmpty())
        val b = a.copyOf()
        val pos = (b.size - 1) * p
        val i = pos.toInt()
        val lo = select(b, i)
        val hi = select(b, min(i + 1, b.lastIndex))
        return lo + (hi - lo) * (pos - i)
    }
    private fun select(a: DoubleArray, k: Int): Double {
        var lo = 0; var hi = a.lastIndex
        while (lo < hi) {
            val x = a[lo]; val y = a[(lo + hi) ushr 1]; val z = a[hi]
            val pivot = max(min(x, y), min(max(x, y), z))
            var lt = lo; var scan = lo; var gt = hi
            while (scan <= gt) {
                if (a[scan] < pivot) {
                    val t = a[lt]; a[lt++] = a[scan]; a[scan++] = t
                } else if (a[scan] > pivot) {
                    val t = a[gt]; a[gt--] = a[scan]; a[scan] = t
                } else scan++
            }
            if (k < lt) hi = lt - 1 else if (k > gt) lo = gt + 1 else return a[k]
        }
        return a[k]
    }
    fun median(a: DoubleArray) = percentile(a, .5)
    fun poly(c: DoubleArray, x: Double): Double {
        var y = 0.0
        for (i in c.indices.reversed()) y = y * x + c[i]
        return y
    }
    data class Fit(val c: DoubleArray, val residual: DoubleArray, val inliers: BooleanArray)
    fun solve(x: DoubleArray, y: DoubleArray, degree: Int, weights: DoubleArray? = null, ids: IntArray? = null): DoubleArray {
        val n = ids?.size ?: x.size; val k = degree + 1
        CvScope().use { s ->
            val a = s.own(Mat(n, k, CvType.CV_64F)); val b = s.own(Mat(n, 1, CvType.CV_64F)); val c = s.mat()
            val aa = DoubleArray(n * k); val bb = DoubleArray(n)
            for (i in 0 until n) {
                val j = ids?.get(i) ?: i
                val w = weights?.let { sqrt(it[j]) } ?: 1.0
                var v = w
                for (d in 0 until k) { aa[i * k + d] = v; v *= x[j] }
                bb[i] = y[j] * w
            }
            a.put(0, 0, *aa); b.put(0, 0, *bb)
            require(Core.solve(a, b, c, Core.DECOMP_SVD)) { "least squares failed" }
            return DoubleArray(k).also { c.get(0, 0, it) }
        }
    }
    fun robustFit(x: DoubleArray, y: DoubleArray, degree: Int, scale: Double): Fit {
        val n = x.size; val k = degree + 1
        require(n >= max(15, degree + 2)) { "too few boundary samples: $n" }
        val base = max(2.0, scale * .012)
        var best = solve(x, y, degree); var cost = cost(x, y, best)
        val rng = Pcg64()
        repeat(70) {
            var start = 0
            val ids = IntArray(k) { j ->
                val len = n / k + if (j < n % k) 1 else 0
                (start + rng.bounded(len)).also { start += len }
            }
            val c = solve(x, y, degree, ids = ids); val z = cost(x, y, c)
            if (z < cost) { best = c; cost = z }
        }
        for (t in 0 until 8) {
            val r = DoubleArray(n) { y[it] - poly(best, x[it]) }
            val med = median(r)
            val sigma = max(base, 1.4826 * median(DoubleArray(n) { abs(r[it] - med) }))
            var count = 0
            val weights = DoubleArray(n) {
                val u = r[it] / (4.685 * sigma); val v = max(0.0, 1 - u * u)
                if (v > 0) count++
                v * v
            }
            if (count < degree + 2) break
            best = solve(x, y, degree, weights)
        }
        val r = DoubleArray(n) { y[it] - poly(best, x[it]) }
        val lim = 3 * max(base, 1.4826 * median(DoubleArray(n) { abs(r[it]) }))
        return Fit(best, r, BooleanArray(n) { abs(r[it]) < lim })
    }
    private fun cost(x: DoubleArray, y: DoubleArray, c: DoubleArray) = median(DoubleArray(x.size) { abs(poly(c, x[it]) - y[it]) })
    class Pcg64 {
        private var state = BigInteger("185168654372936159333097075870931536645")
        private val inc = BigInteger("253583831573602660626028118101560951819")
        private val mul = BigInteger("47026247687942121848144207491837523525")
        private val mask = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE)
        private var high = 0L; private var buffered = false
        private fun next32(): Long {
            if (buffered) { buffered = false; return high }
            state = state.multiply(mul).add(inc).and(mask)
            val word = java.lang.Long.rotateRight(state.shiftRight(64).toLong() xor state.toLong(), state.shiftRight(122).toInt())
            high = word ushr 32; buffered = true
            return word and 0xffffffffL
        }
        fun bounded(n: Int): Int {
            if (n == 1) return 0
            val threshold = (0x100000000L - n) % n
            while (true) {
                val m = next32() * n
                if ((m and 0xffffffffL) >= threshold) return (m ushr 32).toInt()
            }
        }
    }
}

/** Owns per-call native allocations, including all exceptional exits. Early release is safe. */
internal class CvScope : AutoCloseable {
    private val mats = ArrayList<Mat>()
    fun <T : Mat> own(m: T): T { mats.add(m); return m }
    fun mat() = own(Mat())
    override fun close() { for (i in mats.indices.reversed()) mats[i].release() }
}
