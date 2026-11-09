package com.yohaku.expensetracker.receipt.scan

import kotlin.math.*

/** Pillow RGB uint8 BILINEAR: antialias support, 22-bit weights, rounding after each axis. */
internal object PilBilinear {
    private data class Kernel(val start: Int, val weights: IntArray)
    private fun kernels(input: Int, output: Int): Array<Kernel> {
        val scale = input.toDouble() / output; val filter = max(1.0, scale)
        return Array(output) { i ->
            val center = (i + .5) * scale
            val lo = max(0, (center - filter + .5).toInt()); val hi = min(input, (center + filter + .5).toInt())
            val raw = DoubleArray(hi - lo) { j -> max(0.0, 1 - abs((j + lo - center + .5) * (1.0 / filter))) }
            val sum = raw.sum()
            Kernel(lo, IntArray(raw.size) { (raw[it] / sum * (1 shl 22) + .5).toInt() })
        }
    }
    private fun horizontal(src: ByteArray, w: Int, h: Int, ow: Int): ByteArray {
        val kernels = kernels(w, ow); val out = ByteArray(ow * h * 3)
        for (y in 0 until h) for (x in 0 until ow) for (c in 0..2) {
            val k = kernels[x]; var a = 1 shl 21
            for (j in k.weights.indices) a += (src[(y * w + k.start + j) * 3 + c].toInt() and 255) * k.weights[j]
            out[(y * ow + x) * 3 + c] = (a shr 22).coerceIn(0, 255).toByte()
        }
        return out
    }
    private fun vertical(src: ByteArray, w: Int, h: Int, oh: Int): ByteArray {
        val kernels = kernels(h, oh); val out = ByteArray(w * oh * 3)
        for (y in 0 until oh) for (x in 0 until w) for (c in 0..2) {
            val k = kernels[y]; var a = 1 shl 21
            for (j in k.weights.indices) a += (src[((k.start + j) * w + x) * 3 + c].toInt() and 255) * k.weights[j]
            out[(y * w + x) * 3 + c] = (a shr 22).coerceIn(0, 255).toByte()
        }
        return out
    }
    fun resize(src: ByteArray, w: Int, h: Int, ow: Int, oh: Int): ByteArray {
        require(src.size == w * h * 3)
        // Pillow 13 reduces the intermediate allocation by choosing this axis order.
        return if (!((h - oh) > 0 && (h - oh) > (w - ow) * 2)) vertical(horizontal(src, w, h, ow), ow, h, oh)
        else horizontal(vertical(src, w, h, oh), w, oh, ow)
    }
}

internal object PixelInputs {
    private val mean = floatArrayOf(123.675f, 116.28f, 103.53f)
    private val std = floatArrayOf(58.395f, 57.12f, 57.375f)
    private val edge = Array(3) { c -> FloatArray(256) { (it - mean[c]) / std[c] } }
    private val synthetic = FloatArray(256) { (it / 255f - .5f) / .5f }
    fun edge(bgr: ByteArray, w: Int, h: Int, out: FloatArray) {
        out.fill(0f)
        for (c in 0..2) for (y in 0 until h) for (x in 0 until w)
            out[c * 1024 * 1024 + y * 1024 + x] = edge[c][bgr[(y * w + x) * 3 + 2 - c].toInt() and 255]
    }
    fun synthetic(bgr: ByteArray): FloatArray {
        val n = bgr.size / 3
        return FloatArray(bgr.size) { i -> synthetic[bgr[(i % n) * 3 + 2 - i / n].toInt() and 255] }
    }
}

internal object MaskBounds {
    fun rows(pixels: ByteArray, width: Int, height: Int, left: IntArray, right: IntArray) {
        for (y in 0 until height) {
            val row = y * width; var l = 0; var r = width - 1
            while (l < width && pixels[row + l] == 0.toByte()) l++
            if (l == width) { left[y] = width; right[y] = -1; continue }
            while (r > l && pixels[row + r] == 0.toByte()) r--
            left[y] = l; right[y] = r
        }
    }
}
