package com.yohaku.expensetracker.receipt.scan

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class ScanOrientationTest {
    private fun axisAngle(degrees: Double): Double {
        val a = Math.toRadians(degrees)
        val x = sin(a); val y = cos(a)
        // Covariance with a distinct major direction and nonzero minor variance.
        val v = ReceiptGeometry.axis(1 + 3*x*x, 3*x*y, 1 + 3*y*y)
        assertTrue(v[1] > 0)
        return Math.toDegrees(atan2(v[0], v[1]))
    }

    @Test fun verticalMajorAxisIsKeptAndHorizontalMajorAxisUsesItsPerpendicular() {
        for (angle in listOf(-40.0, -10.0, 0.0, 10.0, 40.0)) assertEquals(angle, axisAngle(angle), 1e-10)
        for ((input, expected) in listOf(-90.0 to 0.0, -80.0 to 10.0, 80.0 to -10.0, 90.0 to 0.0)) {
            assertEquals(expected, axisAngle(input), 1e-10)
        }
    }

    @Test fun diagonalTiesStayAndOnlyAnglesBeyondFortyFiveSwitchAxes() {
        for (sign in listOf(-1, 1)) {
            assertEquals(sign * 44.999, axisAngle(sign * 44.999), 1e-9)
            assertEquals(sign * -44.999, axisAngle(sign * 45.001), 1e-9)
            // Equal diagonal covariance avoids trigonometric construction rounding at the tie.
            val v = ReceiptGeometry.axis(2.0, sign.toDouble(), 2.0)
            assertEquals(sign * 45.0, Math.toDegrees(atan2(v[0], v[1])), 1e-10)
            assertEquals(sign * PI / 4, ScanOrientation.deskewRadians(sign * PI / 4), 0.0)
        }
    }

    @Test fun defaultAndEditedContourDisplayKeepsImageTopEvenWhenWideOrSideways() {
        val wide = ScanContour.centered(1600, 400)
        assertEquals(0.0, ScanEditFrame.forContour(wide).radians, 0.0)
        for (angle in listOf(-80.0, -45.0, -20.0, 20.0, 45.0, 80.0)) {
            val a = Math.toRadians(angle)
            fun side(offset: Double) = List(5) { i ->
                val y = (i - 2) * 50.0
                ScanPoint(500 + cos(a) * offset + sin(a) * y, 500 - sin(a) * offset + cos(a) * y)
            }
            val contour = ScanContour(1000, 1000, side(-50.0), side(50.0), true, true)
            val expected = ScanOrientation.deskewRadians(a)
            assertEquals(expected, ScanEditFrame.forContour(contour).radians, 1e-10)
        }
    }
}
