package app.ledger.transfer.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.ledger.core.common.DomainResult
import app.ledger.transfer.domain.BackupFailure
import app.ledger.transfer.domain.BackupFormatContract
import java.io.IOException
import java.io.OutputStream

/** SAF single-file publisher with provider-level replace rollback and partial cleanup. */
class SafPortableBackupDestination(
    context: Context,
    treeUri: Uri,
    private val fileName: String,
) {
    private val applicationContext = context.applicationContext
    private val root = DocumentFile.fromTreeUri(applicationContext, treeUri)
        ?.takeIf(DocumentFile::exists) ?: throw SecurityException("SAF permission revoked")

    init {
        require(fileName.matches(Regex("[A-Za-z0-9_-]{1,120}\\.ledger-backup")))
    }

    fun writeAndPublish(writer: (OutputStream) -> DomainResult<*>): DomainResult<Uri> = try {
        val partialName = ".$fileName.partial"
        root.findFile(partialName)?.delete()
        val partial = root.createFile(MIME_TYPE, partialName) ?: throw IOException("portable backup create failed")
        val result = applicationContext.contentResolver.openOutputStream(partial.uri, "rwt")?.use(writer)
            ?: throw IOException("portable backup output unavailable")
        if (result is DomainResult.Failure) {
            partial.delete()
            return DomainResult.Failure(result.error)
        }
        val previousName = ".$fileName.previous"
        root.findFile(previousName)?.delete()
        val previous = root.findFile(fileName)?.let { existing ->
            if (!existing.renameTo(previousName)) throw IOException("portable backup replacement unavailable")
            root.findFile(previousName) ?: existing
        }
        if (!partial.renameTo(fileName)) {
            previous?.renameTo(fileName)
            partial.delete()
            throw IOException("portable backup publication failed")
        }
        previous?.delete()
        DomainResult.Success(requireNotNull(root.findFile(fileName)).uri)
    } catch (_: SecurityException) {
        DomainResult.Failure(BackupFailure.PermissionRevoked)
    } catch (error: IOException) {
        DomainResult.Failure(if (error.message?.contains("space", true) == true) BackupFailure.InsufficientSpace else BackupFailure.RepositoryUnavailable)
    }

    fun cleanup(): Boolean = root.listFiles().filter { it.name == ".$fileName.partial" || it.name == ".$fileName.previous" }.all(DocumentFile::delete)

    companion object {
        const val MIME_TYPE = "application/vnd.ledger.backup"
        val EXTENSION: String = BackupFormatContract.PORTABLE_EXTENSION
    }
}
