package com.yohaku.expensetracker.receipt.scan

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ScanContourEditorTest {
    private val original = ScanContour.centered(1000, 1600)
    private val frame = ScanEditFrame(1000, 1600)

    @Test fun dragsClampToImageKeepSidesOrderedAndDoNotCrossEnds() {
        val cases = listOf(
            Triple(true, 2, ScanPoint(-400.0, 800.0)),
            Triple(true, 2, ScanPoint(1300.0, 800.0)),
            Triple(false, 2, ScanPoint(-500.0, 800.0)),
            Triple(true, 2, ScanPoint(250.0, -800.0)),
            Triple(false, 0, ScanPoint(750.0, 2000.0)),
            Triple(true, 4, ScanPoint(250.0, -800.0)),
        )
        for ((left, index, point) in cases) {
            val next = ScanContourEditor.move(original, frame, left, index, point)
            assertTrue(ScanContourEditor.valid(next, frame))
            assertTrue((next.left + next.right).all(frame::contains))
            assertTrue(next.left.zipWithNext().all { (a,b) -> a.y < b.y })
            assertTrue(next.right.zipWithNext().all { (a,b) -> a.y < b.y })
            assertNotEquals(original, next)
        }
        assertEquals(0.0, ScanContourEditor.move(original, frame, true, 2, ScanPoint(-400.0, 800.0)).left[2].x, .001)
    }

    @Test fun endVisibilityUsesOriginalImageEdgesIncludingRotatedPhotos() {
        assertTrue(ScanContourEditor.endInFrame(frame, ScanPoint(200.0, 11.0), ScanPoint(800.0, 11.0)))
        assertFalse(ScanContourEditor.endInFrame(frame, ScanPoint(200.0, 10.0), ScanPoint(800.0, 11.0)))
        assertFalse(ScanContourEditor.endInFrame(frame, ScanPoint(200.0, 1590.0), ScanPoint(800.0, 1580.0)))
        val rotated = ScanEditFrame(1000, 1600, Math.PI / 2)
        assertFalse(ScanContourEditor.endInFrame(rotated, rotated.toDisplay(ScanPoint(0.0, 400.0)), rotated.toDisplay(ScanPoint(5.0, 1200.0))))
        assertTrue(ScanContourEditor.endInFrame(rotated, rotated.toDisplay(ScanPoint(100.0, 400.0)), rotated.toDisplay(ScanPoint(100.0, 1200.0))))
    }

    @Test fun cubicInterpolationHitsHandlesAndStaysSmoothAfterAnEdit() {
        val edited = ScanContourEditor.move(original, frame, true, 2, ScanPoint(200.0, 790.0))
        val outline = edited.outline(401)
        for (i in 0..4) {
            assertEquals(edited.left[i].x, outline[i * 100].x, 1e-8)
            assertEquals(edited.left[i].y, outline[i * 100].y, 1e-8)
        }
        // Adjacent numerical tangents agree at every interior knot (C1 continuity).
        for (i in listOf(100, 200, 300)) {
            val before = outline[i].x - outline[i - 1].x
            val after = outline[i + 1].x - outline[i].x
            assertEquals(before, after, .05)
        }
    }

    @Test fun contourSerializationAndUprightTransformRoundTrip() {
        val edited = ScanContourEditor.move(original, frame, false, 3, ScanPoint(800.0, 1100.0))
        assertEquals(edited, Json.decodeFromString<ScanContour>(Json.encodeToString(edited)))
        val rotated = ScanEditFrame(1000, 1600, .7)
        val roundTrip = rotated.source(rotated.display(edited))
        (edited.left + edited.right).zip(roundTrip.left + roundTrip.right).forEach { (a,b) ->
            assertEquals(a.x, b.x, 1e-8); assertEquals(a.y, b.y, 1e-8)
        }
        assertEquals(rotated, Json.decodeFromString<ScanEditFrame>(Json.encodeToString(rotated)))
    }
    @Test fun temporaryFilesExpireTogetherAndExportKeepsOnlyTheSelectedJpeg() {
        val cache = java.nio.file.Files.createTempDirectory("scan-files-test").toFile()
        try {
            val stale = ScanTemporaryFiles.newDirectory(cache)
            val active = ScanTemporaryFiles.newDirectory(cache)
            ScanTemporaryFiles.create(stale); ScanTemporaryFiles.create(active)
            ScanTemporaryFiles.write(stale, "original.jpg", byteArrayOf(1))
            ScanTemporaryFiles.write(active, "original.jpg", byteArrayOf(2))
            val result = ScanTemporaryFiles.write(active, "automatic.jpg", byteArrayOf(3))
            val now = System.currentTimeMillis()
            assertTrue(stale.setLastModified(now - ScanTemporaryFiles.MAX_AGE_MS - 1_000))
            ScanTemporaryFiles.cleanExpired(cache, now)
            assertFalse(stale.exists()); assertTrue(active.exists())
            val exported = ScanTemporaryFiles.export(cache, active, result)
            assertArrayEquals(byteArrayOf(3), exported.readBytes()); assertFalse(active.exists())
            assertThrows(IllegalStateException::class.java) { ScanTemporaryFiles.write(active, "late.jpg", byteArrayOf(4)) }
        } finally { cache.deleteRecursively() }
    }

}
