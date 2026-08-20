package app.ledger.app

import android.content.Context
import androidx.core.util.AtomicFile
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.database.LedgerMigrations
import java.io.File

/** Lets the UI announce a pending Room migration before opening the encrypted database. */
internal class LedgerSchemaVersionMarker(context: Context) {
    private val applicationContext = context.applicationContext
    private val marker = AtomicFile(File(applicationContext.noBackupFilesDir, FILE_NAME))

    fun migrationExpected(): Boolean = applicationContext
        .getDatabasePath(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        .isFile && readVersion() != LedgerMigrations.CURRENT_VERSION

    fun markCurrent() {
        var output: java.io.FileOutputStream? = null
        try {
            output = marker.startWrite()
            output.write(LedgerMigrations.CURRENT_VERSION)
            output.fd.sync()
            marker.finishWrite(output)
        } catch (error: Exception) {
            output?.let(marker::failWrite)
            throw error
        }
    }

    private fun readVersion(): Int? = runCatching { marker.readFully().singleOrNull()?.toInt() }.getOrNull()

    private companion object {
        const val FILE_NAME = "ledger-schema-version.marker"
    }
}
