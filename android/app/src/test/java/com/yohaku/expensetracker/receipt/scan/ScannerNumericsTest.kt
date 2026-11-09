package com.yohaku.expensetracker.receipt.scan

import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*

/** Small independent Python/Pillow/NumPy fixtures; no Android or OpenCV native calls. */
class ScannerNumericsTest {
    private val gold = Json.parseToJsonElement(javaClass.getResource("/receipt-scanner/numerics.json")!!.readText()).jsonObject
    private fun JsonElement.floats() = jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
    private fun JsonElement.doubles() = jsonArray.map { it.jsonPrimitive.double }.toDoubleArray()
    private fun JsonElement.ints() = jsonArray.map { it.jsonPrimitive.int }.toIntArray()
    private fun JsonElement.bytes() = ints().map { it.toByte() }.toByteArray()

    @Test fun percentilesRoundToEvenAndFixedSamplingMatchNumpy() {
        val values = gold.getValue("values").doubles(); val ps = gold.getValue("percentiles").doubles()
        assertArrayEquals(gold.getValue("quantiles").doubles(), ps.map { Numerics.percentile(values, it) }.toDoubleArray(), 1e-12)
        assertArrayEquals(gold.getValue("rounds").doubles(), doubleArrayOf(-.5, -1.5, -2.5, .5, 1.5, 2.5, 3.5).map { Numerics.round(it).toDouble() }.toDoubleArray(), 0.0)
        val rng = Numerics.Pcg64()
        assertArrayEquals(gold.getValue("random").ints(), gold.getValue("bounds").ints().map { rng.bounded(it) }.toIntArray())
    }
    @Test fun pillowBilinearMatchesBothAxisOrdersAndUpsampling() {
        for (fixture in gold.getValue("pil").jsonArray) {
            val f = fixture.jsonObject
            fun n(key: String) = f.getValue(key).jsonPrimitive.int
            assertArrayEquals(f.getValue("expected").bytes(), PilBilinear.resize(f.getValue("source").bytes(), n("w"), n("h"), n("ow"), n("oh")))
        }
    }
    @Test fun gridExtensionAndAlignCornersMatchReference() {
        val grid = Grid(5, 7, gold.getValue("grid").floats()); val ext = grid.extend()
        assertArrayEquals(gold.getValue("extended_wh").ints(), intArrayOf(ext.w, ext.h))
        assertArrayEquals(gold.getValue("extended").floats(), ext.a, 1e-6f)
        val xs = FloatArray(11 * 13); val ys = FloatArray(xs.size)
        GridMath.pixelRows(GridMath.horizontal(grid.a, 5, 7, 11), 7, 11, 13, 0, 13, 9f, 12f, xs, ys)
        assertArrayEquals(gold.getValue("pixels").floats(), FloatArray(xs.size * 2) { if (it % 2 == 0) xs[it / 2] else ys[it / 2] }, 2e-6f)
    }
    @Test fun composedCoordinatesAndEndClippingMatchReference() {
        val f = gold.getValue("compose").jsonObject
        val xs = FloatArray(9); val ys = FloatArray(9); val outside = ByteArray(9)
        GridMath.composeRow(gold.getValue("grid").floats(), 5, 7, GridMath.unitCoordinates(9), 5.125f, 1.25f, 8.875f,
            doubleArrayOf(2.0, .45), doubleArrayOf(8.0, -.1), f.getValue("back").doubles(), 19, 25, 11, 13, 19, 25, xs, ys, outside, 0)
        assertArrayEquals(f.getValue("mx").floats(), xs, 2e-6f)
        assertArrayEquals(f.getValue("my").floats(), ys, 2e-6f)
        assertArrayEquals(f.getValue("outside").bytes(), outside)
    }
    @Test fun editableContourInterpolatesCubicSidesAndRejectsCrossing() {
        fun left(t: Double) = ScanPoint(50 + 2*t*t*t - 5*t*t + 3*t, 20 + 90*t)
        val l = List(5) { left(it.toDouble()) }; val r = l.map { it.copy(x = it.x + 150) }
        val contour = ScanContour(400, 500, l, r, true, false)
        val outline = contour.outline()
        for (i in 0 until 64) {
            val expected = left(4.0 * i / 63)
            assertEquals(expected.x, outline[i].x, 1e-9); assertEquals(expected.y, outline[i].y, 1e-9)
        }
        assertFalse(contour.bottom.inFrame)
        val edited = contour.withEnd(true, ScanEndLine(ScanPoint(60.0, 30.0), ScanPoint(210.0, 35.0), true))
        assertEquals(30.0, edited.left[0].y, 0.0)
        assertThrows(IllegalArgumentException::class.java) { contour.copy(right = r.toMutableList().also { it[2] = ScanPoint(0.0, 200.0) }) }
        assertThrows(IllegalArgumentException::class.java) { contour.copy(left = l.toMutableList().also { it[2] = ScanPoint(-1.0, 200.0) }) }
    }
}
