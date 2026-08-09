package app.ledger.transfer.data

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.transfer.domain.BackupFailure
import app.ledger.transfer.domain.BackupFormatContract
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

class BackupStreamingScaleTest {
    @TempDir lateinit var temporary: File

    @Test
    fun fortyEightGibibyteSimulationUsesLongOffsetsAndBoundedWorkingSet() {
        val logicalBytes = 48L * 1024L * 1024L * 1024L
        assertTrue(logicalBytes > Int.MAX_VALUE)
        assertEquals(12_288L, BackupFormatContract.segmentCount(logicalBytes, BackupFormatContract.DATABASE_CHUNK_BYTES))
        assertEquals(4L * 1024L * 1024L + 64L * 1024L, BackupFormatContract.maximumPlaintextWorkingSetBytes())
        assertTrue(BackupFormatContract.maximumPlaintextWorkingSetBytes() < 8L * 1024L * 1024L)
        assertTrue(Runtime.getRuntime().maxMemory() <= 256L * 1024L * 1024L)
    }

    @Test
    fun fortyEightGibibyteSparseDriveSourceStreamsOneChunkAndPersistsLongResumeOffset() {
        val logicalBytes = 48L * 1024L * 1024L * 1024L
        val sparse = temporary.resolve("48-gib-sparse.object")
        RandomAccessFile(sparse, "rw").use { it.setLength(logicalBytes) }
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.url.encodedPath == "/upload" ->
                        MockResponse.Builder().code(200).addHeader("Location", server.url("/session")).build()
                    request.headers["Content-Range"]?.startsWith("bytes */") == true -> MockResponse.Builder().code(308).build()
                    request.url.encodedPath == "/session" -> MockResponse.Builder().code(308)
                        .addHeader("Range", "bytes=0-${DriveResumableBackupClient.MINIMUM_CHUNK_BYTES - 1}").build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
            server.start()
            val client = DriveResumableBackupClient(
                okhttp3.OkHttpClient(),
                server.url("/upload"),
                server.url("/files"),
                DriveResumableBackupClient.MINIMUM_CHUNK_BYTES,
            )
            var checkpoint: DriveResumableCheckpoint? = null
            var cancellationChecks = 0
            val result = client.upload(
                StableId.fromUuid(UUID(30, 48)),
                "token",
                "large.object",
                sparse,
                "application/octet-stream",
                null,
                null,
                DriveCheckpointWriter { checkpoint = it },
                cancelled = { ++cancellationChecks > 1 },
            )
            assertEquals(BackupFailure.Cancelled, (result as DomainResult.Failure).error)
            assertEquals(logicalBytes, checkpoint?.totalBytes)
            assertEquals(DriveResumableBackupClient.MINIMUM_CHUNK_BYTES.toLong(), checkpoint?.nextByte)
            assertTrue(Runtime.getRuntime().maxMemory() <= 256L * 1024L * 1024L)
        }
    }
}
