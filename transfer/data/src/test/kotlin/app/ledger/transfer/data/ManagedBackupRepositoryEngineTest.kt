@file:Suppress("LongMethod", "MagicNumber", "MaxLineLength")

package app.ledger.transfer.data

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.common.StableIdSource
import app.ledger.core.security.LedgerTink
import app.ledger.core.security.SecretBytes
import app.ledger.finance.domain.BookCommitId
import app.ledger.finance.domain.Hash256
import app.ledger.finance.domain.LocalRevision
import app.ledger.transfer.domain.BackupFailure
import app.ledger.transfer.domain.BackupObject
import app.ledger.transfer.domain.BackupObjectId
import app.ledger.transfer.domain.BackupObjectKind
import app.ledger.transfer.domain.BackupRepositoryId
import app.ledger.transfer.domain.BackupRepositoryKind
import app.ledger.transfer.domain.BackupRetentionPolicy
import app.ledger.transfer.domain.BackupSnapshot
import app.ledger.transfer.domain.BackupSnapshotId
import app.ledger.transfer.domain.BackupSnapshotObject
import app.ledger.transfer.domain.BackupSnapshotState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

class ManagedBackupRepositoryEngineTest {
    @TempDir lateinit var temporary: File

    @Test
    fun onlyVerifiedManifestPublishesAndSecondSnapshotReusesObjects() = runBlocking {
        val catalog = MemoryBackupCatalog()
        val storage = FileBackupRepositoryStorage(temporary.resolve("repository"))
        val ids = SequentialIds()
        val portableKeyId = ids.id()
        val engine = ManagedBackupRepositoryEngine(ids)
        val key = SecretBytes.copyOf(LedgerTink.generateStreamingAeadKeyset().useBytes(ByteArray::copyOf))
        try {
            val first = engine.create(input(ids.id(), portableKeyId), storage, catalog, key, byteArrayOf(8, 9), BackupRetentionPolicy())
            assertTrue(first is DomainResult.Success, first.toString())
            assertEquals(BackupSnapshotState.COMPLETE, catalog.snapshots.values.single().state)
            val physicalObjects = storage.names(BackupStorageArea.OBJECTS)
            assertTrue(physicalObjects.isNotEmpty())
            assertTrue(storage.names(BackupStorageArea.SNAPSHOTS).single().endsWith(".manifest"))

            val second = engine.create(input(ids.id(), portableKeyId), storage, catalog, key, byteArrayOf(8, 9), BackupRetentionPolicy())
            assertTrue(second is DomainResult.Success, second.toString())
            assertEquals(physicalObjects, storage.names(BackupStorageArea.OBJECTS))
            assertEquals(2, catalog.completeSnapshots(REPOSITORY).size)
            assertEquals(0L, (second as DomainResult.Success).value.physicalIncrementBytes)
        } finally {
            key.close()
        }
    }

    @Test
    fun interruptedObjectNeverPublishesManifestAndIsReclaimed() = runBlocking {
        val catalog = MemoryBackupCatalog()
        val storage = FileBackupRepositoryStorage(temporary.resolve("cancelled"))
        val ids = SequentialIds()
        val key = LedgerTink.generateStreamingAeadKeyset()
        var checks = 0
        try {
            val result = ManagedBackupRepositoryEngine(ids).create(
                input(ids.id(), ids.id(), databaseBytes = ByteArray(6 * 1024 * 1024) { it.toByte() }),
                storage,
                catalog,
                key,
                byteArrayOf(1),
                BackupRetentionPolicy(),
                cancelled = { ++checks > 2 },
            )
            assertEquals(BackupFailure.Cancelled, (result as DomainResult.Failure).error)
            assertTrue(storage.names(BackupStorageArea.SNAPSHOTS).isEmpty())
            assertTrue(storage.names(BackupStorageArea.OBJECTS).isEmpty())
            assertFalse(catalog.snapshots.values.any { it.state == BackupSnapshotState.COMPLETE })
        } finally {
            key.close()
        }
    }

    @Test
    fun manifestWriteFailureLeavesNoValidSnapshot() = runBlocking {
        val catalog = MemoryBackupCatalog()
        val delegate = FileBackupRepositoryStorage(temporary.resolve("failed-manifest"))
        val storage = object : BackupRepositoryStorage by delegate {
            override fun writeAtomically(area: BackupStorageArea, name: String, writer: (java.io.OutputStream) -> Unit): Long {
                if (area == BackupStorageArea.SNAPSHOTS) throw java.io.IOException("injected manifest failure")
                return delegate.writeAtomically(area, name, writer)
            }
        }
        val ids = SequentialIds()
        val key = LedgerTink.generateStreamingAeadKeyset()
        try {
            val result = ManagedBackupRepositoryEngine(ids).create(
                input(ids.id(), ids.id()),
                storage,
                catalog,
                key,
                byteArrayOf(1),
                BackupRetentionPolicy(),
            )
            assertEquals(BackupFailure.RepositoryUnavailable, (result as DomainResult.Failure).error)
            assertTrue(catalog.completeSnapshots(REPOSITORY).isEmpty())
            assertTrue(delegate.names(BackupStorageArea.SNAPSHOTS).isEmpty())
            assertTrue(delegate.names(BackupStorageArea.OBJECTS).isEmpty())
        } finally {
            key.close()
        }
    }

    @Test
    fun retentionFailureAfterImmutablePublicationDoesNotReportCompletedSnapshotAsFailed() = runBlocking {
        val catalog = MemoryBackupCatalog()
        var published = false
        val failingRetentionCatalog = object : BackupCatalogPort by catalog {
            override fun publishSnapshot(snapshot: BackupSnapshot, manifestHash: Hash256, objectIds: List<BackupObjectId>) {
                catalog.publishSnapshot(snapshot, manifestHash, objectIds)
                published = true
            }

            override fun completeSnapshots(repositoryId: BackupRepositoryId): List<BackupSnapshot> {
                check(!published) { "injected retention failure" }
                return catalog.completeSnapshots(repositoryId)
            }
        }
        val storage = FileBackupRepositoryStorage(temporary.resolve("retention-failure"))
        val ids = SequentialIds()
        val key = LedgerTink.generateStreamingAeadKeyset()
        try {
            val snapshot = BackupSnapshotId(ids.id())
            val result = ManagedBackupRepositoryEngine(ids).create(
                input(snapshot.value, ids.id()),
                storage,
                failingRetentionCatalog,
                key,
                byteArrayOf(1),
                BackupRetentionPolicy(),
            )
            assertTrue(result is DomainResult.Success)
            assertEquals(BackupSnapshotState.COMPLETE, catalog.snapshots.getValue(snapshot).state)
            assertEquals(1, storage.names(BackupStorageArea.SNAPSHOTS).size)
            assertEquals(snapshot, BackupRepositoryInspector().readManifest(storage, REPOSITORY, snapshot, key).snapshotId)
        } finally {
            key.close()
        }
    }

    @Test
    fun historicalRecoveryEnvelopeRewrapKeepsEncryptedObjectsAndManifestsVerifiable() = runBlocking {
        val catalog = MemoryBackupCatalog()
        val storage = FileBackupRepositoryStorage(temporary.resolve("rewrap"))
        val ids = SequentialIds()
        val key = LedgerTink.generateStreamingAeadKeyset()
        try {
            val snapshot = BackupSnapshotId(ids.id())
            assertTrue(
                ManagedBackupRepositoryEngine(ids).create(
                    input(snapshot.value, ids.id()),
                    storage,
                    catalog,
                    key,
                    byteArrayOf(1, 2),
                    BackupRetentionPolicy(),
                ) is DomainResult.Success,
            )
            val before = storage.names(BackupStorageArea.OBJECTS).associateWith { name ->
                MessageDigest.getInstance("SHA-256").digest(storage.open(BackupStorageArea.OBJECTS, name).use { it.readBytes() }).toList()
            }
            val result = BackupRecoveryReencryption().rewriteAccessibleHistory(
                storage,
                REPOSITORY,
                listOf(snapshot),
                key,
                byteArrayOf(8, 9, 10),
            )
            assertEquals(1, (result as DomainResult.Success).value)
            assertEquals(byteArrayOf(8, 9, 10).toList(), BackupManifestCodec.decodeHeader(storage.open(BackupStorageArea.ROOT, "repository-header.header").use { it.readBytes() }).recoveryKeyEnvelope.toList())
            assertEquals(snapshot, BackupRepositoryInspector().readManifest(storage, REPOSITORY, snapshot, key).snapshotId)
            val after = storage.names(BackupStorageArea.OBJECTS).associateWith { name ->
                MessageDigest.getInstance("SHA-256").digest(storage.open(BackupStorageArea.OBJECTS, name).use { it.readBytes() }).toList()
            }
            assertEquals(before, after)
        } finally {
            key.close()
        }
    }

    private fun input(
        snapshotStable: StableId,
        keyStable: StableId,
        databaseBytes: ByteArray = "encrypted-sqlcipher-history".repeat(9_000).toByteArray(),
    ): ManagedBackupInput {
        val settings = "settings-非ASCII-日本語".toByteArray()
        val attachment = ByteArray(90_000) { (it * 17).toByte() }
        val keys = keyStable.bytes
        return ManagedBackupInput(
            BOOK,
            REPOSITORY,
            BackupRepositoryKind.APP_PRIVATE,
            HANDLE,
            BackupSnapshotId(snapshotStable),
            BookCommitId(COMMIT),
            LocalRevision.of(42).success(),
            Instant.parse("2026-08-09T00:00:00Z"),
            "1.0",
            2,
            databaseBytes.source("database/ledger.db", BackupObjectKind.DATABASE_CHUNK),
            listOf(settings.source("settings/app.pb", BackupObjectKind.SETTINGS)),
            listOf(attachment.source("attachments/receipt.object", BackupObjectKind.ATTACHMENT)),
            keys.source("keys/device.envelope", BackupObjectKind.KEY_ENVELOPE),
            null,
        )
    }

    private fun ByteArray.source(name: String, kind: BackupObjectKind): ReopenableBackupSource {
        val stored = copyOf()
        return ReopenableBackupSource(name, kind, size.toLong(), sha256()) { ByteArrayInputStream(stored) }
    }

    private fun ByteArray.sha256() = Hash256.fromBytes(MessageDigest.getInstance("SHA-256").digest(this)).success()

    private companion object {
        val BOOK: StableId = StableId.fromUuid(UUID(30, 1))
        val REPOSITORY = BackupRepositoryId(StableId.fromUuid(UUID(30, 2)))
        val HANDLE: StableId = StableId.fromUuid(UUID(30, 3))
        val COMMIT: StableId = StableId.fromUuid(UUID(30, 4))
    }
}

private class SequentialIds : StableIdSource {
    private var next = 100L
    override fun nextStableId(): StableId = id()
    fun id(): StableId = StableId.fromUuid(UUID(30, next++))
}

private class MemoryBackupCatalog : BackupCatalogPort {
    val snapshots = linkedMapOf<BackupSnapshotId, BackupSnapshot>()
    private val repositories = linkedSetOf<BackupRepositoryId>()
    private val objects = linkedMapOf<BackupObjectId, BackupCatalogObject>()
    private val links = linkedMapOf<BackupSnapshotId, MutableList<BackupSnapshotObject>>()

    override fun ensureRepository(repositoryId: BackupRepositoryId, kind: BackupRepositoryKind, handleId: StableId, createdAt: Instant) {
        repositories += repositoryId
    }
    override fun findObject(repositoryId: BackupRepositoryId, hash: Hash256, size: Long, kind: BackupObjectKind) = objects.values.singleOrNull { it.value.repositoryId == repositoryId && it.value.contentHash == hash && it.value.size == size && it.value.kind == kind }
    override fun recordObject(value: BackupObject): BackupCatalogObject = findObject(value.repositoryId, value.contentHash, value.size, value.kind)
        ?: BackupCatalogObject(value, value.id.value.bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) } + ".object").also { objects[value.id] = it }
    override fun publishSnapshot(snapshot: BackupSnapshot, manifestHash: Hash256, objectIds: List<BackupObjectId>) {
        check(snapshot.repositoryId in repositories)
        val complete = snapshot.copy(state = BackupSnapshotState.COMPLETE, manifestHash = manifestHash, objectIds = objectIds)
        snapshots[snapshot.id]?.let {
            check(it == complete)
            return
        }
        snapshots[snapshot.id] = complete
        links[snapshot.id] = objectIds.mapIndexedTo(mutableListOf()) { ordinal, objectId ->
            BackupSnapshotObject(snapshot.id, objectId, ordinal.toLong())
        }
    }
    override fun completeSnapshots(repositoryId: BackupRepositoryId) = snapshots.values
        .filter { it.repositoryId == repositoryId && it.state == BackupSnapshotState.COMPLETE }.sortedByDescending(BackupSnapshot::createdAt)
    override fun deleteSnapshot(snapshotId: BackupSnapshotId): List<String> {
        val repositoryId = snapshots.remove(snapshotId)?.repositoryId ?: return emptyList()
        links.remove(snapshotId)
        return unreferencedObjects(repositoryId)
    }
    override fun unreferencedObjects(repositoryId: BackupRepositoryId): List<String> {
        val referenced = links.values.flatten().mapTo(mutableSetOf(), BackupSnapshotObject::objectId)
        return objects.values.filter { it.value.repositoryId == repositoryId && it.value.id !in referenced }.map(BackupCatalogObject::storageName)
    }
    override fun deleteUnreferencedObject(repositoryId: BackupRepositoryId, storageName: String): Boolean {
        val referenced = links.values.flatten().mapTo(mutableSetOf(), BackupSnapshotObject::objectId)
        val candidate = objects.values.singleOrNull { it.value.repositoryId == repositoryId && it.storageName == storageName && it.value.id !in referenced } ?: return false
        objects.remove(candidate.value.id)
        return true
    }
}

private fun <T> DomainResult<T>.success(): T = (this as DomainResult.Success).value
