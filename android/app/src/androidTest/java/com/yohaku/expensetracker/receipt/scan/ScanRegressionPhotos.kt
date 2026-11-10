package com.yohaku.expensetracker.receipt.scan

import androidx.test.platform.app.InstrumentationRegistry
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/** Crops of the checked-in procedural receipt, never personal images. */
internal object ScanRegressionPhotos {
    fun flat() = asset("flat.jpg")
    fun empty() = asset("no-receipt.png")
    private fun asset(name: String) = InstrumentationRegistry.getInstrumentation().context.assets
        .open("receipt-scanner/$name").use { it.readBytes() }

    fun corner() = crop(Rect(0, 0, 460, 240))
    // All three prompts are on paper, but fewer than 40 normalized rows can supply a fit.
    fun insufficientGeometry() = crop(Rect(0, 850, 1300, 32))
    // Paper is ~530px wide and 400px high, with background visible on both sides.
    fun wideMiddle() = crop(Rect(0, 700, 1300, 400), markers = true)

    private fun crop(rect: Rect, markers: Boolean = false): ByteArray {
        OpenCvRuntime.ensure()
        CvScope().use { s ->
            val full = ReceiptScanner.decode(s, flat(), ScanTimings())
            val cropped = s.own(full.submat(rect))
            if (markers) {
                // Known red upper / blue lower strips make rotation and inversion observable.
                Imgproc.rectangle(cropped, Point(460.0, 55.0), Point(820.0, 80.0), Scalar(0.0, 0.0, 240.0), -1)
                Imgproc.rectangle(cropped, Point(460.0, 320.0), Point(820.0, 345.0), Scalar(240.0, 0.0, 0.0), -1)
            }
            val encoded = s.own(MatOfByte())
            check(Imgcodecs.imencode(".png", cropped, encoded))
            return encoded.toArray()
        }
    }
}
