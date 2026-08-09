@file:Suppress("MagicNumber")

package app.ledger.transfer.domain

import app.ledger.core.common.DomainError
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.finance.domain.Hash256
import app.ledger.finance.domain.LocalRevision
import java.time.Instant
import java.time.LocalDate

object BackupFormatContract {
    const val REPOSITORY_SCHEMA_VERSION: Int = 1
    const val MANIFEST_SCHEMA_VERSION: Int = 1
    const val PORTABLE_SCHEMA_VERSION: Int = 1
    const val DATABASE_CHUNK_BYTES: Int = 4 * 1024 * 1024
    const val COPY_BUFFER_BYTES: Int = 64 * 1024
    const val DEFAULT_RETENTION_COUNT: Int = 30
    const val PORTABLE_EXTENSION: String = ".ledger-backup"

    fun segmentCount(totalBytes: Long, segmentBytes: Int): Long {
        require(totalBytes >= 0L && segmentBytes > 0)
        if (totalBytes == 0L) return 0L
        return Math.addExact(totalBytes, segmentBytes.toLong() - 1L) / segmentBytes.toLong()
    }

    /** Upper bound for one database chunk plus one streaming copy buffer. */
    fun maximumPlaintextWorkingSetBytes(): Long = DATABASE_CHUNK_BYTES.toLong() + COPY_BUFFER_BYTES.toLong()
}

enum class BackupPhase {
    DATABASE_SNAPSHOT,
    OBJECT_PROCESSING,
    WRITING_OR_UPLOADING,
    VERIFYING,
    PUBLISHING_MANIFEST,
    RETENTION,
    COMPLETE,
}

data class BackupProgress(
    val phase: BackupPhase,
    val completedBytes: Long,
    val totalBytes: Long?,
    val completedObjects: Long,
) {
    init {
        require(completedBytes >= 0L)
        require(totalBytes == null || totalBytes >= completedBytes)
        require(completedObjects >= 0L)
    }
}

fun interface BackupProgressObserver {
    suspend fun onProgress(progress: BackupProgress)
}

enum class BackupNetworkPolicy { ANY, UNMETERED }

data class BackupRetentionPolicy(
    val maximumSnapshots: Int = BackupFormatContract.DEFAULT_RETENTION_COUNT,
    val maximumAgeDays: Int? = null,
) {
    init {
        require(maximumSnapshots in 1..3650)
        require(maximumAgeDays == null || maximumAgeDays in 1..36500)
    }
}

data class BackupPolicy(
    val automaticLocalBackup: Boolean,
    val retention: BackupRetentionPolicy,
    val includeVault: Boolean,
    val networkPolicy: BackupNetworkPolicy,
) {
    fun validate(recoveryPasswordConfigured: Boolean): DomainResult<BackupPolicy> = if (includeVault && !recoveryPasswordConfigured) {
        DomainResult.Failure(BackupFailure.RecoveryPasswordRequired)
    } else {
        DomainResult.Success(this)
    }
}

enum class RecoveryPasswordChangeMode {
    FUTURE_BACKUPS_ONLY,
    RE_ENCRYPT_ACCESSIBLE_HISTORY,
}

data class BackupRepositoryHeader(
    val repositoryId: BackupRepositoryId,
    val schemaVersion: Int,
    val createdAt: Instant,
    val recoveryKeyEnvelope: ByteArray,
) {
    init {
        require(schemaVersion == BackupFormatContract.REPOSITORY_SCHEMA_VERSION)
        require(recoveryKeyEnvelope.isNotEmpty())
    }
}

data class BackupManifestObject(
    val id: BackupObjectId,
    val kind: BackupObjectKind,
    val storageName: String,
    val logicalName: String,
    val plaintextHash: Hash256,
    val plaintextSize: Long,
    val ordinal: Long,
) {
    init {
        require(storageName.matches(Regex("[0-9a-f]{32}\\.object")))
        require(logicalName.matches(Regex("[A-Za-z0-9_./-]{1,180}")))
        require(plaintextSize >= 0L)
        require(ordinal >= 0L)
    }
}

data class BackupSnapshotManifest(
    val schemaVersion: Int,
    val snapshotId: BackupSnapshotId,
    val repositoryId: BackupRepositoryId,
    val bookId: StableId,
    val localRevision: LocalRevision,
    val createdAt: Instant,
    val applicationVersion: String,
    val databaseSchemaVersion: Int,
    val includesSettings: Boolean,
    val includesAttachments: Boolean,
    val includesHistory: Boolean,
    val includesVault: Boolean,
    val logicalBytes: Long,
    val physicalIncrementBytes: Long,
    val objects: List<BackupManifestObject>,
) {
    init {
        require(schemaVersion == BackupFormatContract.MANIFEST_SCHEMA_VERSION)
        require(applicationVersion.isNotBlank())
        require(databaseSchemaVersion > 0)
        require(includesSettings && includesAttachments && includesHistory)
        require(logicalBytes >= 0L && physicalIncrementBytes >= 0L)
        require(objects.map(BackupManifestObject::ordinal) == objects.indices.map(Int::toLong))
        require(objects.map(BackupManifestObject::storageName).distinct().size == objects.size)
    }
}

data class BackupCreationResult(
    val snapshotId: BackupSnapshotId,
    val manifestHash: Hash256,
    val logicalBytes: Long,
    val physicalIncrementBytes: Long,
    val objectCount: Long,
    val verifiedAt: Instant,
)

sealed interface BackupFailure : DomainError {
    data object RecoveryPasswordRequired : BackupFailure {
        override val code: String = "BACKUP_RECOVERY_PASSWORD_REQUIRED"
    }
    data object InvalidRecoveryPassword : BackupFailure {
        override val code: String = "BACKUP_INVALID_RECOVERY_PASSWORD"
    }
    data object VaultRecoveryEnvelopeMissing : BackupFailure {
        override val code: String = "BACKUP_VAULT_RECOVERY_ENVELOPE_MISSING"
    }
    data object PermissionRevoked : BackupFailure {
        override val code: String = "BACKUP_PERMISSION_REVOKED"
    }
    data object DriveAuthorizationRequired : BackupFailure {
        override val code: String = "BACKUP_DRIVE_AUTHORIZATION_REQUIRED"
    }
    data object NetworkUnavailable : BackupFailure {
        override val code: String = "BACKUP_NETWORK_UNAVAILABLE"
    }
    data object InsufficientSpace : BackupFailure {
        override val code: String = "BACKUP_INSUFFICIENT_SPACE"
    }
    data object CorruptObject : BackupFailure {
        override val code: String = "BACKUP_CORRUPT_OBJECT"
    }
    data object CorruptManifest : BackupFailure {
        override val code: String = "BACKUP_CORRUPT_MANIFEST"
    }
    data object RepositoryUnavailable : BackupFailure {
        override val code: String = "BACKUP_REPOSITORY_UNAVAILABLE"
    }
    data object Cancelled : BackupFailure {
        override val code: String = "BACKUP_CANCELLED"
    }
    data object TemporaryCleanupFailed : BackupFailure {
        override val code: String = "BACKUP_TEMPORARY_CLEANUP_FAILED"
    }
    data object UnsupportedVersion : BackupFailure {
        override val code: String = "BACKUP_UNSUPPORTED_VERSION"
    }
}

object AutomaticBackupPolicy {
    fun shouldCreateDailyBackup(
        today: LocalDate,
        currentRevision: LocalRevision,
        lastSuccessfulDate: LocalDate?,
        lastSuccessfulRevision: LocalRevision?,
    ): Boolean = lastSuccessfulDate != today && (lastSuccessfulRevision == null || currentRevision.value > lastSuccessfulRevision.value)
}
