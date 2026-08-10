@file:Suppress(
    "LoopWithTooManyJumpStatements",
    "MagicNumber",
    "NestedBlockDepth",
    "TooGenericExceptionCaught",
)

package app.ledger.transfer.data

import app.ledger.core.common.DomainResult
import app.ledger.transfer.domain.BackupFailure
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class DriveRepositoryDownloadResult(
    val manifestNames: List<String>,
    val downloadedBytes: Long,
    val reusedFiles: Int,
)

/**
 * Mirrors opaque encrypted Drive repository files without inspecting plaintext.
 * File length is the durable Range checkpoint; manifests move into visibility only after all
 * repository objects have been downloaded and verified.
 */
class DriveBackupRepositoryDownloader(
    private val client: DriveResumableBackupClient,
) {
    fun download(
        accessToken: String,
        parentFolderId: String,
        destinationRoot: File,
        cancelled: () -> Boolean = { false },
        checkpoint: (name: String, completedBytes: Long, totalBytes: Long) -> Unit = { _, _, _ -> },
    ): DomainResult<DriveRepositoryDownloadResult> = try {
        val listed = client.listRepositoryFiles(accessToken, parentFolderId)
        if (listed is DomainResult.Failure) return listed
        val files = (listed as DomainResult.Success).value.filter { MANAGED_ARTIFACT_NAME.matches(it.name) }
        if (files.none { it.name == REPOSITORY_HEADER } || files.none { it.name.endsWith(MANIFEST_SUFFIX) }) {
            return DomainResult.Failure(BackupFailure.CorruptObject)
        }
        val ordered = files.filterNot { it.name.endsWith(MANIFEST_SUFFIX) } +
            files.filter { it.name.endsWith(MANIFEST_SUFFIX) }
        val totalBytes = files.fold(0L) { total, item -> Math.addExact(total, item.bytes) }
        var completedBytes = 0L
        var downloadedBytes = 0L
        var reusedFiles = 0
        for (remote in ordered) {
            if (cancelled() || Thread.currentThread().isInterrupted) {
                return DomainResult.Failure(BackupFailure.Cancelled)
            }
            val target = target(destinationRoot, remote.name)
            require(target.parentFile?.isDirectory == true || requireNotNull(target.parentFile).mkdirs())
            if (target.isFile && target.length() == remote.bytes && target.matchesMd5(remote.md5Checksum)) {
                reusedFiles++
                completedBytes = Math.addExact(completedBytes, remote.bytes)
                checkpoint(remote.name, completedBytes, totalBytes)
                continue
            }
            val partial = File(target.parentFile, ".${remote.name}.partial")
            if (partial.isFile && partial.length() > remote.bytes) check(partial.delete())
            val before = partial.takeIf(File::isFile)?.length() ?: 0L
            val downloaded = client.downloadRange(
                accessToken,
                remote.remoteFileId,
                partial,
                remote.bytes,
                checkpoint = { current -> checkpoint(remote.name, Math.addExact(completedBytes, current), totalBytes) },
                cancelled = cancelled,
            )
            if (downloaded is DomainResult.Failure) return downloaded
            if (partial.length() != remote.bytes || !partial.matchesMd5(remote.md5Checksum)) {
                check(!partial.exists() || partial.delete())
                return DomainResult.Failure(BackupFailure.CorruptObject)
            }
            atomicPublish(partial, target)
            downloadedBytes = Math.addExact(downloadedBytes, remote.bytes - before)
            completedBytes = Math.addExact(completedBytes, remote.bytes)
            checkpoint(remote.name, completedBytes, totalBytes)
        }
        DomainResult.Success(
            DriveRepositoryDownloadResult(
                files.filter { it.name.endsWith(MANIFEST_SUFFIX) }.map(DriveRemoteFile::name).sorted(),
                downloadedBytes,
                reusedFiles,
            ),
        )
    } catch (_: ArithmeticException) {
        DomainResult.Failure(BackupFailure.RepositoryUnavailable)
    } catch (_: IOException) {
        DomainResult.Failure(BackupFailure.RepositoryUnavailable)
    } catch (_: Exception) {
        DomainResult.Failure(BackupFailure.RepositoryUnavailable)
    }

    private fun target(root: File, name: String): File {
        require(MANAGED_ARTIFACT_NAME.matches(name))
        val area = when {
            name == REPOSITORY_HEADER -> root
            name.endsWith(OBJECT_SUFFIX) -> File(root, BackupStorageArea.OBJECTS.directoryName)
            else -> File(root, BackupStorageArea.SNAPSHOTS.directoryName)
        }.canonicalFile
        val target = File(area, name).canonicalFile
        require(target.parentFile == area)
        return target
    }

    private fun atomicPublish(partial: File, target: File) {
        try {
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: AtomicMoveNotSupportedException) {
            throw IOException("atomic Drive repository publication is unavailable", error)
        }
    }

    private companion object {
        const val REPOSITORY_HEADER = "repository-header.header"
        const val OBJECT_SUFFIX = ".object"
        const val MANIFEST_SUFFIX = ".manifest"
        val MANAGED_ARTIFACT_NAME = Regex("(?:repository-header\\.header|[0-9a-f]{32}\\.(?:object|manifest))")
    }
}

private fun File.matchesMd5(expected: String?): Boolean {
    if (expected == null) return true
    val digest = MessageDigest.getInstance("MD5")
    FileInputStream(this).use { input ->
        val buffer = ByteArray(64 * 1024)
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        } finally {
            buffer.fill(0)
        }
    }
    return MessageDigest.isEqual(
        digest.digest(),
        expected.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
    )
}
