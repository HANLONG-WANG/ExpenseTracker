package com.yohaku.expensetracker.receipt.scan

import java.io.File
import java.util.UUID

/** One owner for camera, review and exported scan files. Call I/O methods on a worker.
 * Successful output ownership passes to the caller, which deletes it after import.
 * A cancelled worker cannot recreate a removed session: writes require its directory to exist.
 */
object ScanTemporaryFiles {
    const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
    fun newDirectory(cacheDir: File) = File(cacheDir, "receipt-capture/${UUID.randomUUID()}")
    @Synchronized fun create(directory: File) { check(directory.mkdirs()) { "Cannot create scan directory" } }
    @Synchronized fun write(directory: File, name: String, bytes: ByteArray): File {
        check(directory.isDirectory) { "Scan session has ended" }
        val file = File(directory, name)
        val pending = File(directory, "$name.pending")
        pending.writeBytes(bytes)
        check(pending.renameTo(file)) { "Cannot save scan file" }
        directory.setLastModified(System.currentTimeMillis())
        return file
    }
    @Synchronized fun discard(directory: File) { directory.deleteRecursively() }
    @Synchronized fun export(cacheDir: File, directory: File, selected: File): File {
        val destination = newDirectory(cacheDir)
        create(destination)
        val output = File(destination, "output.jpg")
        try {
            check(selected.renameTo(output)) { "Cannot transfer scan result" }
            discard(directory)
            return output
        } catch (e: Exception) { discard(destination); throw e }
    }
    @Synchronized fun cleanExpired(cacheDir: File, now: Long = System.currentTimeMillis()) {
        File(cacheDir, "receipt-capture").listFiles()?.filter { now - it.lastModified() > MAX_AGE_MS }?.forEach(::discard)
    }
}
