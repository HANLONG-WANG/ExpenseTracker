@file:Suppress("MagicNumber")

package app.ledger.core.security

import android.content.Context
import app.ledger.core.common.StableId

data class ImportTemporaryCleanupResult(
    val stagingDatabasesRemoved: Int,
    val shadowDatabasesRemoved: Int,
    val safetyDatabasesRemoved: Int,
    val exchangeMarkersRemoved: Int,
)

/** Deletes only closed, expired import artifacts whose opaque operation id is not active. */
class SecureImportTemporaryFileCleaner(context: Context) {
    private val applicationContext = context.applicationContext

    fun cleanup(activeOperationIds: Set<StableId>, olderThanEpochMillis: Long): ImportTemporaryCleanupResult {
        require(olderThanEpochMillis >= 0L)
        val activeHex = activeOperationIds.mapTo(mutableSetOf()) { it.bytes.toHex() }
        var staging = 0
        var shadow = 0
        var safety = 0
        applicationContext.databaseList().forEach { name ->
            val match = DATABASE_NAME.matchEntire(name) ?: return@forEach
            val kind = match.groupValues[1]
            val operationHex = match.groupValues[2]
            val file = applicationContext.getDatabasePath(name)
            if (operationHex !in activeHex && file.lastModified() < olderThanEpochMillis && applicationContext.deleteDatabase(name)) {
                when (kind) {
                    "import" -> staging++
                    "ledger_shadow" -> shadow++
                    "ledger_safety" -> safety++
                }
            }
        }
        var markers = 0
        applicationContext.filesDir.listFiles().orEmpty().forEach { file ->
            val match = MARKER_NAME.matchEntire(file.name) ?: return@forEach
            if (match.groupValues[1] !in activeHex && file.lastModified() < olderThanEpochMillis && file.delete()) markers++
        }
        return ImportTemporaryCleanupResult(staging, shadow, safety, markers)
    }

    private companion object {
        val DATABASE_NAME = Regex("(import|ledger_shadow|ledger_safety)_([0-9a-f]{32})\\.db")
        val MARKER_NAME = Regex("ledger_exchange_([0-9a-f]{32})\\.marker")
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
