@file:Suppress("LongMethod", "MagicNumber", "NestedBlockDepth")

package app.ledger.transfer.data

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.transfer.domain.BackupFailure
import app.ledger.transfer.domain.BackupRepositoryId
import app.ledger.transfer.domain.BackupSnapshotId
import app.ledger.transfer.domain.DriveUploadState
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

class DriveResumableBackupClientTest {
    @TempDir lateinit var temporary: File

    @Test
    fun interruptionResumesAtPersisted256KiBBoundaryWithoutNewSession() {
        MockWebServer().use { server ->
            var initialized = 0
            var chunks = 0
            var accepted = 0L
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.method == "GET" && request.url.encodedPath == "/drive/v3/files" ->
                        MockResponse.Builder().code(200).body("{\"files\":[]}").build()
                    request.url.encodedPath.startsWith("/upload") -> {
                        initialized++
                        MockResponse.Builder().code(200).addHeader("Location", server.url("/session")).build()
                    }
                    request.url.encodedPath == "/session" && request.headers["Content-Range"]?.startsWith("bytes */") == true ->
                        MockResponse.Builder().code(308).apply { if (accepted > 0) addHeader("Range", "bytes=0-${accepted - 1}") }.build()
                    request.url.encodedPath == "/session" -> {
                        chunks++
                        accepted += request.bodySize
                        if (accepted < 2L * DriveResumableBackupClient.MINIMUM_CHUNK_BYTES) {
                            MockResponse.Builder().code(308).addHeader("Range", "bytes=0-${accepted - 1}").build()
                        } else {
                            MockResponse.Builder().code(200).body("{\"id\":\"remote_file_30\"}").build()
                        }
                    }
                    else -> MockResponse.Builder().code(404).build()
                }
            }
            val file = temporary.resolve("object.bin").apply {
                writeBytes(ByteArray(2 * DriveResumableBackupClient.MINIMUM_CHUNK_BYTES) { it.toByte() })
            }
            val client = client(server)
            var saved: DriveResumableCheckpoint? = null
            var cancellationChecks = 0
            val first = client.upload(
                OPERATION, "token", "opaque.object", file, "application/octet-stream", null, null,
                DriveCheckpointWriter { saved = it },
                cancelled = { ++cancellationChecks > 1 },
            )
            assertEquals(BackupFailure.Cancelled, (first as DomainResult.Failure).error)
            assertEquals(DriveResumableBackupClient.MINIMUM_CHUNK_BYTES.toLong(), saved?.nextByte)

            val second = client.upload(
                OPERATION,
                "token",
                "opaque.object",
                file,
                "application/octet-stream",
                null,
                saved,
                DriveCheckpointWriter { saved = it },
            )
            assertTrue(second is DomainResult.Success)
            assertEquals("remote_file_30", (second as DomainResult.Success).value.remoteFileId)
            assertEquals(1, initialized)
            assertEquals(2, chunks)
            assertTrue(saved?.complete == true)
        }
    }

    @Test
    fun rangeDownloadAppendsFromDurableOffset() {
        MockWebServer().use { server ->
            val expected = "0123456789abcdef".toByteArray()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    assertEquals("bytes=5-", request.headers["Range"])
                    return MockResponse.Builder().code(206)
                        .addHeader("Content-Range", "bytes 5-15/16")
                        .body(okio.Buffer().write(expected, 5, expected.size - 5)).build()
                }
            }
            val destination = temporary.resolve("download.partial").apply { writeBytes(expected.copyOfRange(0, 5)) }
            var checkpoint = 0L
            val result = client(server).downloadRange("token", "remote_file_30", destination, expected.size.toLong(), { checkpoint = it })
            assertEquals(expected.size.toLong(), (result as DomainResult.Success).value)
            assertEquals(expected.size.toLong(), checkpoint)
            assertTrue(destination.readBytes().contentEquals(expected))
        }
    }

    @Test
    fun repositoryPublisherUploadsManifestLastAndNeverPublishesTwice() {
        MockWebServer().use { server ->
            val completed = mutableListOf<String>()
            var currentName = ""
            var sessionNumber = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.method == "GET" && request.url.encodedPath == "/drive/v3/files" ->
                        MockResponse.Builder().code(200).body("{\"files\":[]}").build()
                    request.url.encodedPath.startsWith("/upload") -> {
                        currentName = Regex("\\\"name\\\":\\\"([^\\\"]+)").find(requireNotNull(request.body).utf8())!!.groupValues[1]
                        sessionNumber++
                        MockResponse.Builder().code(200).addHeader("Location", server.url("/session/$sessionNumber/$currentName")).build()
                    }
                    request.headers["Content-Range"]?.startsWith("bytes */") == true -> MockResponse.Builder().code(308).build()
                    request.url.encodedPath.startsWith("/session/") -> {
                        val name = request.url.encodedPath.substringAfterLast('/')
                        completed += name
                        MockResponse.Builder().code(200).body("{\"id\":\"id_$sessionNumber\"}").build()
                    }
                    else -> MockResponse.Builder().code(404).build()
                }
            }
            val objectFile = temporary.resolve("a.object").apply { writeBytes(ByteArray(400_000) { 1 }) }
            val manifestFile = temporary.resolve("b.manifest").apply { writeBytes(ByteArray(100_000) { 2 }) }
            val store = MemoryDriveSessionStore()
            var ticks = 0L
            val publisher = DriveBackupRepositoryPublisher(client(server), store) { Instant.ofEpochMilli(++ticks) }
            val initial = DurableDriveBackupSession(
                SESSION, OPERATION, SNAPSHOT, REPOSITORY, DriveUploadState.CREATED, null, emptySet(), false, Instant.EPOCH, Instant.EPOCH,
            )
            val artifacts = listOf(
                DriveBackupArtifact("snapshot.manifest", manifestFile, finalManifest = true),
                DriveBackupArtifact("chunk.object", objectFile),
            )
            assertTrue(publisher.publish(initial, "token", artifacts, null) is DomainResult.Success)
            assertEquals(listOf("chunk.object", "snapshot.manifest"), completed)
            assertTrue(store.value!!.manifestPublished)
            val requestCount = server.requestCount
            val repeated = publisher.publish(initial, "token", artifacts, null) as DomainResult.Success
            assertEquals(0, repeated.value.uploadedThisRun)
            assertEquals(requestCount, server.requestCount)
        }
    }

    @Test
    fun changedRecoveryManifestUsesResumablePatchInsteadOfDuplicatePublication() {
        MockWebServer().use { server ->
            var initializationMethod: String? = null
            var initializationPath: String? = null
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.method == "GET" -> MockResponse.Builder().code(200).body(
                        "{\"files\":[{\"id\":\"existing_file\",\"name\":\"snapshot.manifest\",\"size\":\"64\",\"md5Checksum\":\"00000000000000000000000000000000\"}]}",
                    ).build()
                    request.url.encodedPath.startsWith("/upload") -> {
                        initializationMethod = request.method
                        initializationPath = request.url.encodedPath
                        MockResponse.Builder().code(200).addHeader("Location", server.url("/replace-session")).build()
                    }
                    request.headers["Content-Range"]?.startsWith("bytes */") == true -> MockResponse.Builder().code(308).build()
                    request.url.encodedPath == "/replace-session" -> MockResponse.Builder().code(200).body("{\"id\":\"existing_file\"}").build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
            val manifest = temporary.resolve("changed.manifest").apply { writeBytes(ByteArray(64) { 7 }) }
            val store = MemoryDriveSessionStore()
            val publisher = DriveBackupRepositoryPublisher(client(server), store) { Instant.parse("2026-08-09T00:00:00Z") }
            val initial = DurableDriveBackupSession(
                SESSION, OPERATION, SNAPSHOT, REPOSITORY, DriveUploadState.CREATED, null, emptySet(), false, Instant.EPOCH, Instant.EPOCH,
            )
            val result = publisher.publish(
                initial,
                "token",
                listOf(DriveBackupArtifact("snapshot.manifest", manifest, finalManifest = true, replaceExisting = true)),
                null,
            )
            assertTrue(result is DomainResult.Success)
            assertEquals("PATCH", initializationMethod)
            assertEquals("/upload/drive/v3/files/existing_file", initializationPath)
            assertTrue(store.value!!.manifestPublished)
        }
    }

    @Test
    fun repositoryFolderIsIdempotentAndReferenceGcDeletesOnlyManagedStaleArtifacts() {
        MockWebServer().use { server ->
            var folderCreated = false
            val deleted = mutableListOf<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.method == "GET" && request.url.queryParameter("q")?.contains("mimeType") == true ->
                        MockResponse.Builder().code(200).body(
                            if (folderCreated) "{\"files\":[{\"id\":\"folder_30\",\"name\":\"repository.ledger-repository\"}]}" else "{\"files\":[]}",
                        ).build()
                    request.method == "POST" && request.url.encodedPath == "/drive/v3/files" -> {
                        folderCreated = true
                        MockResponse.Builder().code(200).body(
                            "{\"id\":\"folder_30\",\"name\":\"repository.ledger-repository\"}",
                        ).build()
                    }
                    request.method == "GET" && request.url.queryParameter("q")?.contains("folder_30") == true ->
                        MockResponse.Builder().code(200).body(
                            "{\"files\":[" +
                                "{\"id\":\"keep_30\",\"name\":\"repository-header.header\",\"size\":\"10\",\"md5Checksum\":\"00000000000000000000000000000000\"}," +
                                "{\"id\":\"stale_30\",\"name\":\"0123456789abcdef0123456789abcdef.object\",\"size\":\"10\"}," +
                                "{\"id\":\"foreign_30\",\"name\":\"notes.txt\",\"size\":\"10\"}]}",
                        ).build()
                    request.method == "DELETE" -> {
                        deleted += request.url.encodedPath.substringAfterLast('/')
                        MockResponse.Builder().code(204).build()
                    }
                    else -> MockResponse.Builder().code(404).build()
                }
            }
            val client = client(server)
            assertEquals("folder_30", (client.ensureRepositoryFolder("token", "repository.ledger-repository") as DomainResult.Success).value)
            assertEquals("folder_30", (client.ensureRepositoryFolder("token", "repository.ledger-repository") as DomainResult.Success).value)
            val publisher = DriveBackupRepositoryPublisher(client, MemoryDriveSessionStore()) { Instant.EPOCH }
            val result = publisher.pruneUnreferenced("token", "folder_30", setOf("repository-header.header"))
            assertEquals(1, (result as DomainResult.Success).value)
            assertEquals(listOf("stale_30"), deleted)
        }
    }

    @Test
    fun repositoryDownloadResumesOpaqueObjectAndPublishesManifestLast() {
        MockWebServer().use { server ->
            val objectName = "0123456789abcdef0123456789abcdef.object"
            val manifestName = "1123456789abcdef0123456789abcdef.manifest"
            val contents = mapOf(
                "header_id" to "header-value".toByteArray(),
                "object_id" to "0123456789abcdef".toByteArray(),
                "manifest_id" to "encrypted-manifest".toByteArray(),
            )
            val downloadOrder = mutableListOf<String>()
            val destination = temporary.resolve("drive-repository")
            destination.resolve("objects").mkdirs()
            destination.resolve("objects/.$objectName.partial").writeBytes(contents.getValue("object_id").copyOfRange(0, 5))
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.url.encodedPath == "/drive/v3/files") {
                        fun item(id: String, name: String): String {
                            val bytes = contents.getValue(id)
                            return "{\"id\":\"$id\",\"name\":\"$name\",\"size\":\"${bytes.size}\",\"md5Checksum\":\"${bytes.md5()}\"}"
                        }
                        return MockResponse.Builder().code(200).body(
                            "{\"files\":[${item("manifest_id", manifestName)},${item("object_id", objectName)},${item("header_id", "repository-header.header")}]}",
                        ).build()
                    }
                    val id = request.url.encodedPath.substringAfterLast('/')
                    val value = contents.getValue(id)
                    val offset = request.headers["Range"]?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
                    downloadOrder += id
                    if (id == "manifest_id") {
                        assertTrue(destination.resolve("repository-header.header").isFile)
                        assertTrue(destination.resolve("objects/$objectName").isFile)
                        assertFalse(destination.resolve("snapshots/$manifestName").exists())
                    }
                    return MockResponse.Builder().code(if (offset == 0) 200 else 206)
                        .apply { if (offset > 0) addHeader("Content-Range", "bytes $offset-${value.lastIndex}/${value.size}") }
                        .body(okio.Buffer().write(value, offset, value.size - offset)).build()
                }
            }
            val downloader = DriveBackupRepositoryDownloader(client(server))
            val first = downloader.download("token", "folder_31", destination) as DomainResult.Success
            assertEquals("manifest_id", downloadOrder.last())
            assertEquals(setOf("header_id", "object_id"), downloadOrder.dropLast(1).toSet())
            assertEquals((contents.values.sumOf { it.size } - 5).toLong(), first.value.downloadedBytes)
            assertEquals(listOf(manifestName), first.value.manifestNames)
            assertTrue(destination.resolve("snapshots/$manifestName").isFile)
            assertFalse(destination.resolve("objects/.$objectName.partial").exists())

            val requestCount = downloadOrder.size
            val second = downloader.download("token", "folder_31", destination) as DomainResult.Success
            assertEquals(requestCount, downloadOrder.size)
            assertEquals(0L, second.value.downloadedBytes)
            assertEquals(3, second.value.reusedFiles)
        }
    }

    private fun client(server: MockWebServer): DriveResumableBackupClient {
        if (!server.started) server.start()
        return DriveResumableBackupClient(
            okhttp3.OkHttpClient(),
            server.url("/upload/drive/v3/files"),
            server.url("/drive/v3/files"),
            DriveResumableBackupClient.MINIMUM_CHUNK_BYTES,
        )
    }

    private companion object {
        val OPERATION: StableId = StableId.fromUuid(UUID(30, 20))
        val SESSION: StableId = StableId.fromUuid(UUID(30, 21))
        val SNAPSHOT = BackupSnapshotId(StableId.fromUuid(UUID(30, 22)))
        val REPOSITORY = BackupRepositoryId(StableId.fromUuid(UUID(30, 23)))
    }
}

private fun ByteArray.md5(): String = MessageDigest.getInstance("MD5").digest(this)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private class MemoryDriveSessionStore : DriveBackupSessionStore {
    var value: DurableDriveBackupSession? = null
    override fun save(value: DurableDriveBackupSession) {
        this.value = value
    }
    override fun read(sessionId: StableId, operationId: StableId): DurableDriveBackupSession? = value?.takeIf {
        it.sessionId == sessionId && it.operationId == operationId
    }
}
