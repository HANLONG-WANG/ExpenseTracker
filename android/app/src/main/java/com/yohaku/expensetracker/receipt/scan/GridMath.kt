package com.yohaku.expensetracker.receipt.scan

import kotlin.math.*

/** Pure float32 kernels. Preserve evaluation order and align_corners=true from D7a. */
internal object GridMath {
    fun horizontal(grid: FloatArray, gw: Int, gh: Int, width: Int): FloatArray {
        val ix = IntArray(width); val fx = FloatArray(width)
        for (x in 0 until width) {
            val gx = (x * ((gw - 1.0) / (width - 1))).toFloat()
            ix[x] = gx.toInt(); fx[x] = gx - ix[x]
        }
        val out = FloatArray(gh * width * 2)
        for (y in 0 until gh) for (x in 0 until width) {
            val i = (y * gw + ix[x]) * 2; val j = (y * gw + min(ix[x] + 1, gw - 1)) * 2; val o = (y * width + x) * 2
            val f = fx[x]
            out[o] = grid[i] * (1 - f) + grid[j] * f
            out[o + 1] = grid[i + 1] * (1 - f) + grid[j + 1] * f
        }
        return out
    }
    fun pixelRows(horizontal: FloatArray, gh: Int, width: Int, height: Int, start: Int, rows: Int,
                  scaleX: Float, scaleY: Float, mx: FloatArray, my: FloatArray) {
        val step = (gh - 1.0) / (height - 1)
        for (row in 0 until rows) {
            val gy = ((start + row) * step).toFloat(); val iy = gy.toInt(); val jy = min(iy + 1, gh - 1)
            val f = gy - iy; var a = iy * width * 2; var b = jy * width * 2; val out = row * width
            for (x in 0 until width) {
                mx[out + x] = (horizontal[a] * (1 - f) + horizontal[b] * f + 1) * scaleX
                my[out + x] = (horizontal[a + 1] * (1 - f) + horizontal[b + 1] * f + 1) * scaleY
                a += 2; b += 2
            }
        }
    }
    fun unitCoordinates(width: Int) = FloatArray(width) { (it.toDouble() / (width - 1)).toFloat() }
    fun composeRow(grid: FloatArray, gw: Int, gh: Int, u: FloatArray, y: Float, left: Float, right: Float,
                   top: DoubleArray?, bottom: DoubleArray?, back: DoubleArray, sw: Int, sh: Int,
                   canvasW: Int, canvasH: Int, cropW: Int, cropH: Int,
                   mx: FloatArray, my: FloatArray, outside: ByteArray, offset: Int) {
        val gxScale = ((gw - 1.0) / (canvasW - 1)).toFloat(); val gyScale = ((gh - 1.0) / (canvasH - 1)).toFloat()
        val gy = (y * gyScale).coerceIn(0f, (gh - 1).toFloat())
        val iy = gy.toInt(); val jy = min(iy + 1, gh - 1); val fy = gy - iy; val oneY = 1 - fy
        val rowA = iy * gw * 2; val rowB = jy * gw * 2
        val sourceX = (cropW - 1) / 2f; val sourceY = (cropH - 1) / 2f
        for (col in u.indices) {
            val x = left + (right - left) * u[col]
            val keep = (top == null || y >= top[0] + top[1] * x) && (bottom == null || y <= bottom[0] + bottom[1] * x)
            val gx = (x * gxScale).coerceIn(0f, (gw - 1).toFloat())
            val ix = gx.toInt(); val jx = min(ix + 1, gw - 1); val fx = gx - ix; val oneX = 1 - fx
            val a = rowA + ix * 2; val b = rowA + jx * 2; val c = rowB + ix * 2; val d = rowB + jx * 2
            val qx = (grid[a] * oneX + grid[b] * fx) * oneY + (grid[c] * oneX + grid[d] * fx) * fy
            val qy = (grid[a + 1] * oneX + grid[b + 1] * fx) * oneY + (grid[c + 1] * oneX + grid[d + 1] * fx) * fy
            val px = (qx + 1) * sourceX; val py = (qy + 1) * sourceY
            val ox = (back[0] * px + back[1] * py + back[2]).toFloat(); val oy = (back[3] * px + back[4] * py + back[5]).toFloat()
            val at = offset + col
            mx[at] = ox; my[at] = oy
            outside[at] = if (keep && ox >= 0 && ox <= sw - 1 && oy >= 0 && oy <= sh - 1) 0 else 255.toByte()
        }
    }
}
