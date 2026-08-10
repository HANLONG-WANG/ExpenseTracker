package app.ledger.transfer.data

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.finance.domain.BookCommitId
import app.ledger.finance.domain.Hash256
import app.ledger.finance.domain.LocalRevision
import app.ledger.transfer.domain.BackupObject
import app.ledger.transfer.domain.BackupObjectId
import app.ledger.transfer.domain.BackupObjectKind
import app.ledger.transfer.domain.BackupRepositoryId
import app.ledger.transfer.domain.BackupRepositoryKind
import app.ledger.transfer.domain.BackupSnapshot
import app.ledger.transfer.domain.BackupSnapshotId
import app.ledger.transfer.domain.BackupSnapshotState
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class DriveSnapshotDeletionServiceTest {
    @Test
    fun manifestsDisappearBeforeOnlyUnreferencedObjectsAreCollected() {
        MockWebServer().use { server ->
            val requests = mutableListOf<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request.url.encodedPath.substringAfterLast('/')
                    return MockResponse.Builder().code(204).build()
                }
            }
            val repository = BackupRepositoryId(id(10))
            val first = snapshot(20, repository)
            val second = snapshot(21, repository)
            val firstName = first.id.value.hex() + ".manifest"
            val secondName = second.id.value.hex() + ".manifest"
            val unique = id(30).hex() + ".object"
            val shared = id(31).hex() + ".object"
            val catalog = FakeCatalog(
                mutableMapOf(first.id to mutableSetOf(unique, shared), second.id to mutableSetOf(shared)),
            )
            val remote = listOf(
                DriveRemoteFile("manifest_first", firstName, 1, null),
                DriveRemoteFile("manifest_second", secondName, 1, null),
                DriveRemoteFile("object_unique", unique, 1, null),
                DriveRemoteFile("object_shared", shared, 1, null),
            )
            val result = DriveSnapshotDeletionService(client(server)).delete(
                "token",
                listOf(
                    DriveSnapshotDeletionRequest(first, remote[0]),
                    DriveSnapshotDeletionRequest(second, remote[1]),
                ),
                remote,
                catalog,
            )
            val value = (result as DomainResult.Success).value
            assertEquals(setOf(firstName, secondName), value.deletedManifestNames)
            assertEquals(setOf(unique, shared), value.collectedObjectNames)
            assertEquals(listOf("manifest_first", "object_unique", "manifest_second", "object_shared"), requests)
            assertTrue(catalog.references.isEmpty())
            assertEquals(setOf(unique, shared), catalog.deletedObjects)
        }
    }

    private fun client(server: MockWebServer): DriveResumableBackupClient {
        if (!server.started) server.start()
        return DriveResumableBackupClient(
            OkHttpClient(),
            uploadEndpoint = server.url("/upload/drive/v3/files"),
            filesEndpoint = server.url("/drive/v3/files"),
            chunkBytes = DriveResumableBackupClient.MINIMUM_CHUNK_BYTES,
        )
    }

    private fun snapshot(seed: Int, repository: BackupRepositoryId) = BackupSnapshot(
        BackupSnapshotId(id(seed)),
        repository,
        BookCommitId(id(seed + 40)),
        LocalRevision.of(seed.toLong()).success(),
        Instant.ofEpochSecond(seed.toLong()),
        BackupSnapshotState.COMPLETE,
        Hash256.sha256(byteArrayOf(seed.toByte())),
        emptyList(),
    )

    private fun id(seed: Int) = StableId.fromBytes(ByteArray(16) { (seed + it).toByte() }).success()
    private fun StableId.hex() = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun <T> DomainResult<T>.success() = (this as DomainResult.Success).value
}

private class FakeCatalog(
    val references: MutableMap<BackupSnapshotId, MutableSet<String>>,
) : BackupCatalogPort {
    val deletedObjects = linkedSetOf<String>()

    override fun deleteSnapshot(snapshotId: BackupSnapshotId): List<String> {
        val removed = references.remove(snapshotId).orEmpty()
        val retained = references.values.flatten().toSet()
        return removed.filterNot(retained::contains)
    }

    override fun deleteUnreferencedObject(repositoryId: BackupRepositoryId, storageName: String): Boolean = references.values.none { storageName in it }.also { deleted ->
        if (deleted) deletedObjects += storageName
    }

    override fun ensureRepository(repositoryId: BackupRepositoryId, kind: BackupRepositoryKind, handleId: StableId, createdAt: Instant) = Unit
    override fun findObject(repositoryId: BackupRepositoryId, hash: Hash256, size: Long, kind: BackupObjectKind): BackupCatalogObject? = null
    override fun recordObject(value: BackupObject): BackupCatalogObject = error("unused")
    override fun publishSnapshot(snapshot: BackupSnapshot, manifestHash: Hash256, objectIds: List<BackupObjectId>) = Unit
    override fun completeSnapshots(repositoryId: BackupRepositoryId): List<BackupSnapshot> = emptyList()
    override fun unreferencedObjects(repositoryId: BackupRepositoryId): List<String> = emptyList()
}
