@file:Suppress("TooGenericExceptionCaught")

package app.ledger.transfer.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

interface BackupRepositoryStorage {
    fun exists(area: BackupStorageArea, name: String): Boolean
    fun size(area: BackupStorageArea, name: String): Long?
    fun open(area: BackupStorageArea, name: String): InputStream
    fun writeAtomically(area: BackupStorageArea, name: String, writer: (OutputStream) -> Unit): Long
    fun names(area: BackupStorageArea): Set<String>
    fun delete(area: BackupStorageArea, name: String): Boolean
}

enum class BackupStorageArea(val directoryName: String) {
    ROOT(""),
    OBJECTS("objects"),
    SNAPSHOTS("snapshots"),
}

class FileBackupRepositoryStorage(root: File) : BackupRepositoryStorage {
    private val repositoryRoot = root.canonicalFile

    init {
        require(repositoryRoot.isDirectory || repositoryRoot.mkdirs())
        BackupStorageArea.entries.filterNot { it == BackupStorageArea.ROOT }.forEach { area ->
            val directory = directory(area)
            require(directory.isDirectory || directory.mkdirs())
        }
    }

    override fun exists(area: BackupStorageArea, name: String): Boolean = file(area, name).isFile

    override fun size(area: BackupStorageArea, name: String): Long? = file(area, name).takeIf(File::isFile)?.length()

    override fun open(area: BackupStorageArea, name: String): InputStream = FileInputStream(file(area, name))

    override fun writeAtomically(area: BackupStorageArea, name: String, writer: (OutputStream) -> Unit): Long {
        val target = file(area, name)
        val partial = file(area, ".$name.partial")
        check(!partial.exists() || partial.delete())
        try {
            FileOutputStream(partial).use { output ->
                writer(NonClosingOutputStream(output))
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: IOException) {
                // Android's libcore may report an unsupported atomic rename as a generic
                // FileSystemException instead of AtomicMoveNotSupportedException. The completed
                // partial remains beside its target, so a same-directory replacement is still a
                // safe publication fallback.
                Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return target.length()
        } finally {
            if (partial.exists()) check(partial.delete())
        }
    }

    override fun names(area: BackupStorageArea): Set<String> = directory(area).listFiles().orEmpty()
        .filter(File::isFile)
        .mapTo(linkedSetOf(), File::getName)

    override fun delete(area: BackupStorageArea, name: String): Boolean = file(area, name).let { !it.exists() || it.delete() }

    private fun directory(area: BackupStorageArea): File = if (area == BackupStorageArea.ROOT) {
        repositoryRoot
    } else {
        File(repositoryRoot, area.directoryName)
    }

    private fun file(area: BackupStorageArea, name: String): File {
        require(SAFE_NAME.matches(name))
        val result = File(directory(area), name).canonicalFile
        require(result.parentFile == directory(area).canonicalFile)
        return result
    }

    companion object {
        val SAFE_NAME = Regex("\\.?[A-Za-z0-9_-]{1,160}\\.(?:object|manifest|header)(?:\\.(?:partial|previous))?")
    }
}

/** SAF directory adapter; provider operations, not advisory canWrite(), determine permission state. */
class SafBackupRepositoryStorage(context: Context, private val treeUri: Uri) : BackupRepositoryStorage {
    private val applicationContext = context.applicationContext

    override fun exists(area: BackupStorageArea, name: String): Boolean = fileOrNull(area, name)?.exists() == true

    override fun size(area: BackupStorageArea, name: String): Long? = fileOrNull(area, name)?.takeIf(DocumentFile::exists)?.length()

    override fun open(area: BackupStorageArea, name: String): InputStream = applicationContext.contentResolver
        .openInputStream(requireNotNull(fileOrNull(area, name)).uri) ?: throw IOException("repository object unavailable")

    override fun writeAtomically(area: BackupStorageArea, name: String, writer: (OutputStream) -> Unit): Long {
        val directory = directory(area)
        val partialName = ".$name.partial"
        directory.findFile(partialName)?.delete()
        val partial = directory.createFile(MIME_BINARY, partialName) ?: throw IOException("provider create failed")
        try {
            applicationContext.contentResolver.openOutputStream(partial.uri, "rwt")?.use { output ->
                writer(NonClosingOutputStream(output))
                output.flush()
            } ?: throw IOException("provider output unavailable")
            val existing = directory.findFile(name)
            val previousName = ".$name.previous"
            directory.findFile(previousName)?.delete()
            val previous = if (existing != null) {
                if (!existing.renameTo(previousName)) throw IOException("provider replace preparation failed")
                directory.findFile(previousName) ?: existing
            } else {
                null
            }
            if (!partial.renameTo(name)) {
                previous?.renameTo(name)
                throw IOException("provider atomic publication failed")
            }
            previous?.delete()
            return directory.findFile(name)?.length() ?: partial.length()
        } catch (error: Exception) {
            partial.delete()
            throw error
        }
    }

    override fun names(area: BackupStorageArea): Set<String> = directory(area).listFiles().filter(DocumentFile::isFile)
        .mapNotNullTo(linkedSetOf(), DocumentFile::getName)

    override fun delete(area: BackupStorageArea, name: String): Boolean = fileOrNull(area, name)?.delete() ?: true

    private fun root(): DocumentFile = DocumentFile.fromTreeUri(applicationContext, treeUri)
        ?.takeIf(DocumentFile::exists) ?: throw SecurityException("SAF permission revoked")

    private fun directory(area: BackupStorageArea): DocumentFile {
        if (area == BackupStorageArea.ROOT) return root()
        val root = root()
        return root.findFile(area.directoryName)?.takeIf(DocumentFile::isDirectory)
            ?: root.createDirectory(area.directoryName)
            ?: throw IOException("repository directory unavailable")
    }

    private fun fileOrNull(area: BackupStorageArea, name: String): DocumentFile? {
        require(FileBackupRepositoryStorage.SAFE_NAME.matches(name))
        return directory(area).findFile(name)
    }

    private companion object {
        const val MIME_BINARY = "application/octet-stream"
    }
}

private class NonClosingOutputStream(output: OutputStream) : FilterOutputStream(output) {
    override fun close() = flush()
}
