@file:Suppress("MagicNumber", "NestedBlockDepth")

package app.ledger.transfer.data

import app.ledger.core.common.DomainResult
import app.ledger.transfer.domain.BackupFailure
import app.ledger.transfer.domain.BackupSnapshot

data class DriveSnapshotDeletionRequest(
    val snapshot: BackupSnapshot,
    val manifest: DriveRemoteFile,
)

data class DriveSnapshotDeletionResult(
    val deletedManifestNames: Set<String>,
    val failedManifestNames: Set<String>,
    val collectedObjectNames: Set<String>,
)

/** Deletes the visibility manifest first, then reclaims only catalog-proven unreferenced objects. */
class DriveSnapshotDeletionService(private val client: DriveResumableBackupClient) {
    fun delete(
        accessToken: String,
        requests: List<DriveSnapshotDeletionRequest>,
        remoteFiles: List<DriveRemoteFile>,
        catalog: BackupCatalogPort,
    ): DomainResult<DriveSnapshotDeletionResult> = try {
        require(requests.isNotEmpty())
        val remoteByName = remoteFiles.associateBy(DriveRemoteFile::name)
        require(remoteByName.size == remoteFiles.size)
        val deleted = linkedSetOf<String>()
        val failed = linkedSetOf<String>()
        val collected = linkedSetOf<String>()
        requests.forEach { request ->
            val manifestName = request.snapshot.id.value.bytes.toHex() + MANIFEST_SUFFIX
            require(request.manifest.name == manifestName && request.manifest.name.matches(MANAGED_MANIFEST))
            val manifestDeletion = client.deleteRepositoryFile(accessToken, request.manifest.remoteFileId)
            if (manifestDeletion is DomainResult.Failure) {
                failed += manifestName
                return@forEach
            }
            val unreferenced = runCatching { catalog.deleteSnapshot(request.snapshot.id) }.getOrElse {
                failed += manifestName
                return@forEach
            }
            deleted += manifestName
            unreferenced.forEach { objectName ->
                require(objectName.matches(MANAGED_OBJECT))
                val remote = remoteByName[objectName]
                val remoteDeleted = remote == null ||
                    client.deleteRepositoryFile(accessToken, remote.remoteFileId) is DomainResult.Success
                if (remoteDeleted && catalog.deleteUnreferencedObject(request.snapshot.repositoryId, objectName)) {
                    collected += objectName
                }
            }
        }
        DomainResult.Success(DriveSnapshotDeletionResult(deleted, failed, collected))
    } catch (_: Exception) {
        DomainResult.Failure(BackupFailure.RepositoryUnavailable)
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val MANIFEST_SUFFIX = ".manifest"
        val MANAGED_MANIFEST = Regex("[0-9a-f]{32}\\.manifest")
        val MANAGED_OBJECT = Regex("[0-9a-f]{32}\\.object")
    }
}
