package com.yohaku.expensetracker.ui.features.scan

import java.io.File
import com.yohaku.expensetracker.receipt.scan.ScanTemporaryFiles

/** Main-thread ownership: pending files belong to the camera, successful files to onCaptured.
 * A late CameraX callback after Back also removes any file recreated by the in-flight write.
 */
internal class ScanCaptureSession(private val cacheDir: File, private val source: ScanCameraSource) : AutoCloseable {
    private val directory = ScanTemporaryFiles.newDirectory(cacheDir)
    private var closed = false
    private var transferred = false
    var busy = false
        private set
    fun capture(autoProcess: Boolean, onCaptured: (File, Boolean) -> Unit, onFailure: (Exception) -> Unit) {
        if (closed || busy || transferred) return
        busy = true
        try { ScanTemporaryFiles.create(directory) }
        catch (e: Exception) { busy = false; ScanTemporaryFiles.discard(directory); onFailure(e); return }
        val photo = File(directory, "original.jpg")
        source.capture(photo) callback@{ result ->
            if (closed) { ScanTemporaryFiles.discard(directory); return@callback }
            result.fold(onSuccess = {
                transferred = true
                close()
                onCaptured(photo, autoProcess)
            }, onFailure = {
                busy = false
                ScanTemporaryFiles.discard(directory)
                onFailure(it as? Exception ?: RuntimeException(it))
            })
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try { source.close() } finally { if (!transferred) ScanTemporaryFiles.discard(directory) }
    }
}
