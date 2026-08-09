@file:Suppress("NestedBlockDepth", "TooGenericExceptionCaught")

package app.ledger.transfer.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.transfer.domain.ExportDescriptor
import app.ledger.transfer.domain.ExportFailure
import app.ledger.transfer.domain.ExportResult
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

data class PublishedExport(
    val documentUri: Uri,
    val result: ExportResult,
)

/** App-private bounded-memory staging plus SAF publish. Existing files are not touched until generation succeeds. */
class SafExportDestination(
    context: Context,
    private val operationId: StableId,
    private val treeUri: Uri,
    private val descriptor: ExportDescriptor,
) {
    private val applicationContext = context.applicationContext
    private val temporaryDirectory = applicationContext.noBackupFilesDir.resolve("export_temporary")
    private val temporaryFile = temporaryDirectory.resolve("$operationId.partial")

    fun hasCompleteTemporary(): Boolean = temporaryFile.isFile && temporaryFile.length() > 0L

    fun openTemporary(): FileOutputStream {
        temporaryDirectory.mkdirs()
        return FileOutputStream(temporaryFile, false)
    }

    fun publish(result: ExportResult): DomainResult<PublishedExport> = try {
        if (!hasCompleteTemporary()) return DomainResult.Failure(ExportFailure.SourceUnavailable)
        val root = DocumentFile.fromTreeUri(applicationContext, treeUri)
            ?: return DomainResult.Failure(ExportFailure.PermissionRevoked)
        if (!root.exists()) return DomainResult.Failure(ExportFailure.PermissionRevoked)
        val existing = root.findFile(descriptor.fileName)
        if (existing != null && !descriptor.overwriteConfirmed) return DomainResult.Failure(ExportFailure.NameConflict)
        val temporaryName = ".${descriptor.fileName}.$operationId.partial"
        root.findFile(temporaryName)?.delete()
        val providerTemporary = root.createFile(result.mimeType, temporaryName)
            ?: return DomainResult.Failure(ExportFailure.DestinationUnavailable)
        try {
            applicationContext.contentResolver.openOutputStream(providerTemporary.uri, "rwt")?.use { output ->
                FileInputStream(temporaryFile).use { input -> input.copyTo(output, COPY_BUFFER_BYTES) }
                output.flush()
            } ?: return DomainResult.Failure(ExportFailure.PermissionRevoked)
            val providerLength = providerTemporary.length()
            if (providerLength > 0L && providerLength != temporaryFile.length()) {
                providerTemporary.delete()
                return DomainResult.Failure(ExportFailure.InsufficientSpace)
            }
            val providerBackup = if (existing != null) {
                val backupName = ".${descriptor.fileName}.$operationId.previous"
                root.findFile(backupName)?.delete()
                if (!existing.renameTo(backupName)) {
                    providerTemporary.delete()
                    return DomainResult.Failure(ExportFailure.DestinationUnavailable)
                }
                root.findFile(backupName) ?: existing
            } else {
                null
            }
            if (!providerTemporary.renameTo(descriptor.fileName)) {
                providerBackup?.renameTo(descriptor.fileName)
                providerTemporary.delete()
                return DomainResult.Failure(ExportFailure.DestinationUnavailable)
            }
            providerBackup?.delete()
        } catch (failure: Exception) {
            providerTemporary.delete()
            throw failure
        }
        val target = root.findFile(descriptor.fileName) ?: providerTemporary
        cleanup()
        DomainResult.Success(PublishedExport(target.uri, result))
    } catch (_: SecurityException) {
        DomainResult.Failure(ExportFailure.PermissionRevoked)
    } catch (io: IOException) {
        DomainResult.Failure(if (io.message?.contains("space", true) == true) ExportFailure.InsufficientSpace else ExportFailure.DestinationUnavailable)
    } catch (_: Exception) {
        DomainResult.Failure(ExportFailure.DestinationUnavailable)
    }

    fun cleanup(): Boolean = !temporaryFile.exists() || temporaryFile.delete()

    fun temporaryBytes(): Long = temporaryFile.takeIf(File::isFile)?.length() ?: 0L

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
