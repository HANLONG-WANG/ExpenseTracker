@file:Suppress("LoopWithTooManyJumpStatements", "MagicNumber", "NestedBlockDepth", "ReturnCount")

package app.ledger.transfer.data

import app.ledger.core.common.DomainResult
import app.ledger.transfer.domain.BackupFailure
import app.ledger.transfer.domain.DriveUploadState
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.time.Instant

data class DriveBackupArtifact(
    val opaqueName: String,
    val file: File,
    val mimeType: String = "application/octet-stream",
    val finalManifest: Boolean = false,
    val replaceExisting: Boolean = false,
) {
    init {
        require(opaqueName.matches(Regex("[A-Za-z0-9_.-]{1,180}")))
        require(file.isFile)
    }
}

data class DriveBackupPublishResult(val uploadedThisRun: Int, val manifestRemoteFileId: String?)

/** Uploads encrypted opaque objects first and publishes exactly one final manifest last. */
class DriveBackupRepositoryPublisher(
    private val client: DriveResumableBackupClient,
    private val sessions: DriveBackupSessionStore,
    private val now: () -> Instant,
) {
    fun pruneUnreferenced(
        accessToken: String,
        parentFolderId: String,
        retainedOpaqueNames: Set<String>,
    ): DomainResult<Int> {
        require(retainedOpaqueNames.isNotEmpty() && retainedOpaqueNames.all(MANAGED_ARTIFACT_NAME::matches))
        val listed = client.listRepositoryFiles(accessToken, parentFolderId)
        if (listed is DomainResult.Failure) return listed
        var deleted = 0
        for (remote in (listed as DomainResult.Success).value) {
            if (remote.name !in retainedOpaqueNames && MANAGED_ARTIFACT_NAME.matches(remote.name)) {
                val result = client.deleteRepositoryFile(accessToken, remote.remoteFileId)
                if (result is DomainResult.Failure) return result
                deleted++
            }
        }
        return DomainResult.Success(deleted)
    }

    fun publish(
        initial: DurableDriveBackupSession,
        accessToken: String,
        artifacts: List<DriveBackupArtifact>,
        parentFolderId: String?,
        cancelled: () -> Boolean = { false },
    ): DomainResult<DriveBackupPublishResult> {
        require(artifacts.isNotEmpty())
        require(artifacts.count(DriveBackupArtifact::finalManifest) == 1)
        require(artifacts.map(DriveBackupArtifact::opaqueName).distinct().size == artifacts.size)
        var session = sessions.read(initial.sessionId, initial.operationId) ?: initial.also(sessions::save)
        if (session.manifestPublished) {
            require(session.state == DriveUploadState.COMPLETE)
            return DomainResult.Success(DriveBackupPublishResult(0, session.current?.remoteFileId))
        }
        var uploaded = 0
        var manifestRemoteId: String? = null
        val ordered = artifacts.filterNot(DriveBackupArtifact::finalManifest) + artifacts.single(DriveBackupArtifact::finalManifest)
        for (artifact in ordered) {
            if (artifact.opaqueName in session.uploadedOpaqueNames) continue
            if (cancelled()) {
                sessions.save(session.copy(state = DriveUploadState.PAUSED, updatedAt = now()))
                return DomainResult.Failure(BackupFailure.Cancelled)
            }
            val remote = client.findByOpaqueName(accessToken, artifact.opaqueName, parentFolderId)
            if (remote is DomainResult.Failure) {
                sessions.save(session.copy(state = DriveUploadState.PAUSED, updatedAt = now()))
                return remote
            }
            val existing = (remote as DomainResult.Success).value
            if (existing != null) {
                val exact = existing.bytes == artifact.file.length() && existing.md5Checksum == artifact.file.md5Hex()
                if (!exact && !artifact.replaceExisting) {
                    sessions.save(session.copy(state = DriveUploadState.FAILED, updatedAt = now()))
                    return DomainResult.Failure(BackupFailure.CorruptObject)
                }
                if (exact) {
                    manifestRemoteId = existing.remoteFileId.takeIf { artifact.finalManifest }
                    session = session.copy(
                        state = if (artifact.finalManifest) DriveUploadState.COMPLETE else DriveUploadState.UPLOADING,
                        current = null,
                        uploadedOpaqueNames = session.uploadedOpaqueNames + artifact.opaqueName,
                        manifestPublished = artifact.finalManifest,
                        updatedAt = now(),
                    )
                    sessions.save(session)
                    continue
                }
            }
            val recovered = session.current?.takeIf { it.opaqueObjectName == artifact.opaqueName }
            val receipt = client.upload(
                session.operationId,
                accessToken,
                artifact.opaqueName,
                artifact.file,
                artifact.mimeType,
                parentFolderId,
                recovered,
                DriveCheckpointWriter { checkpoint ->
                    session = session.copy(state = DriveUploadState.UPLOADING, current = checkpoint, updatedAt = now())
                    sessions.save(session)
                },
                cancelled = cancelled,
                replaceRemoteFileId = existing?.remoteFileId,
            )
            if (receipt is DomainResult.Failure) {
                sessions.save(session.copy(state = DriveUploadState.PAUSED, updatedAt = now()))
                return receipt
            }
            val completed = (receipt as DomainResult.Success).value
            manifestRemoteId = completed.remoteFileId.takeIf { artifact.finalManifest }
            session = session.copy(
                state = if (artifact.finalManifest) DriveUploadState.COMPLETE else DriveUploadState.UPLOADING,
                current = null,
                uploadedOpaqueNames = session.uploadedOpaqueNames + artifact.opaqueName,
                manifestPublished = artifact.finalManifest,
                updatedAt = now(),
            )
            sessions.save(session)
            uploaded++
        }
        check(session.manifestPublished && session.state == DriveUploadState.COMPLETE)
        return DomainResult.Success(DriveBackupPublishResult(uploaded, manifestRemoteId))
    }

    private companion object {
        val MANAGED_ARTIFACT_NAME = Regex(
            "(?:repository-header\\.header|[0-9a-f]{32}\\.(?:object|manifest))",
        )
    }
}

private fun File.md5Hex(): String {
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
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
