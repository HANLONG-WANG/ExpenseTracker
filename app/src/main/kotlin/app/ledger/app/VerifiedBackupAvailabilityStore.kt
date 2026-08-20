package app.ledger.app

import android.content.Context
import androidx.core.util.AtomicFile
import app.ledger.core.common.StableId
import java.io.File

/**
 * Non-sensitive recovery hint written only after a backup operation reaches its verified,
 * published success state. It remains readable when the ledger key itself is unavailable.
 */
internal class VerifiedBackupAvailabilityStore(context: Context) {
    private val directory = File(context.applicationContext.noBackupFilesDir, DIRECTORY)

    fun markVerified(bookId: StableId) {
        require(directory.isDirectory || directory.mkdirs())
        val atomic = AtomicFile(file(bookId))
        var output: java.io.FileOutputStream? = null
        try {
            output = atomic.startWrite()
            output.write(MARKER_VERSION)
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (error: Exception) {
            output?.let(atomic::failWrite)
            throw error
        }
    }

    fun hasVerifiedBackup(bookId: StableId): Boolean = runCatching {
        AtomicFile(file(bookId)).readFully().contentEquals(MARKER_VERSION)
    }.getOrDefault(false)

    private fun file(bookId: StableId): File = File(directory, "$bookId.available")

    private companion object {
        const val DIRECTORY = "verified-backup-availability-v1"
        val MARKER_VERSION = byteArrayOf(1)
    }
}
