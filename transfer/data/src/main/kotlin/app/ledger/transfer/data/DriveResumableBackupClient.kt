@file:Suppress(
    "LongMethod",
    "LongParameterList",
    "LoopWithTooManyJumpStatements",
    "MagicNumber",
    "NestedBlockDepth",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
)

package app.ledger.transfer.data

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.transfer.domain.BackupFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

data class DriveResumableCheckpoint(
    val operationId: StableId,
    val opaqueObjectName: String,
    val sessionUrl: String,
    val nextByte: Long,
    val totalBytes: Long,
    val remoteFileId: String?,
    val complete: Boolean,
) {
    init {
        require(opaqueObjectName.matches(Regex("[A-Za-z0-9_.-]{1,180}")))
        require(sessionUrl.startsWith("https://") || sessionUrl.startsWith("http://127.0.0.1") || sessionUrl.startsWith("http://localhost"))
        require(nextByte in 0L..totalBytes)
        require(!complete || nextByte == totalBytes)
    }
}

fun interface DriveCheckpointWriter {
    fun save(value: DriveResumableCheckpoint)
}

data class DriveUploadReceipt(val remoteFileId: String, val bytes: Long)
data class DriveRemoteFile(val remoteFileId: String, val name: String, val bytes: Long, val md5Checksum: String?)

/** Direct Drive REST v3 transport. Access tokens remain in memory and never enter the checkpoint. */
class DriveResumableBackupClient(
    private val client: OkHttpClient,
    private val uploadEndpoint: HttpUrl = DEFAULT_UPLOAD_ENDPOINT,
    private val filesEndpoint: HttpUrl = DEFAULT_FILES_ENDPOINT,
    private val chunkBytes: Int = DEFAULT_CHUNK_BYTES,
) {
    init {
        require(chunkBytes >= MINIMUM_CHUNK_BYTES && chunkBytes % MINIMUM_CHUNK_BYTES == 0)
    }

    fun ensureRepositoryFolder(token: String, opaqueName: String): DomainResult<String> = try {
        require(token.isNotBlank() && opaqueName.matches(Regex("[A-Za-z0-9_.-]{1,180}")))
        val query = "name = '$opaqueName' and mimeType = '$DRIVE_FOLDER_MEDIA_TYPE' and trashed = false"
        val request = Request.Builder()
            .url(
                filesEndpoint.newBuilder()
                    .addQueryParameter("q", query)
                    .addQueryParameter("spaces", "drive")
                    .addQueryParameter("fields", "files(id,name)")
                    .addQueryParameter("pageSize", "2")
                    .build(),
            )
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val existing = client.newCall(request).execute().use { response ->
            checkAuthorization(response)
            if (!response.isSuccessful) throw IOException("Drive repository folder lookup failed")
            Json.parseToJsonElement(response.body.string()).jsonObject["files"]?.jsonArray.orEmpty().map { item ->
                val value = item.jsonObject
                require(value["name"]?.jsonPrimitive?.content == opaqueName)
                requireNotNull(value["id"]?.jsonPrimitive?.content).also(::requireRemoteId)
            }.also { require(it.size <= 1) { "duplicate Drive backup repository folder" } }.singleOrNull()
        }
        if (existing != null) return DomainResult.Success(existing)
        val metadata = buildJsonObject {
            put("name", opaqueName)
            put("mimeType", DRIVE_FOLDER_MEDIA_TYPE)
        }.toString()
        val create = Request.Builder()
            .url(filesEndpoint.newBuilder().addQueryParameter("fields", "id,name").build())
            .header("Authorization", "Bearer $token")
            .post(metadata.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(create).execute().use { response ->
            checkAuthorization(response)
            if (!response.isSuccessful) throw IOException("Drive repository folder creation failed")
            val value = Json.parseToJsonElement(response.body.string()).jsonObject
            require(value["name"]?.jsonPrimitive?.content == opaqueName)
            DomainResult.Success(requireNotNull(value["id"]?.jsonPrimitive?.content).also(::requireRemoteId))
        }
    } catch (_: DriveAuthorizationException) {
        DomainResult.Failure(BackupFailure.DriveAuthorizationRequired)
    } catch (_: IOException) {
        DomainResult.Failure(BackupFailure.NetworkUnavailable)
    } catch (_: Exception) {
        DomainResult.Failure(BackupFailure.RepositoryUnavailable)
    }

    fun listRepositoryFiles(token: String, parentFolderId: String): DomainResult<List<DriveRemoteFile>> = try {
        require(token.isNotBlank())
        requireRemoteId(parentFolderId)
        val result = mutableListOf<DriveRemoteFile>()
        var pageToken: String? = null
        do {
            val request = Request.Builder()
                .url(
                    filesEndpoint.newBuilder()
                        .addQueryParameter("q", "'$parentFolderId' in parents and trashed = false")
                        .addQueryParameter("spaces", "drive")
                        .addQueryParameter("fields", "nextPageToken,files(id,name,size,md5Checksum)")
                        .addQueryParameter("pageSize", "1000")
                        .apply { if (pageToken != null) addQueryParameter("pageToken", pageToken) }
                        .build(),
                )
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            pageToken = client.newCall(request).execute().use { response ->
                checkAuthorization(response)
                if (!response.isSuccessful) throw IOException("Drive repository listing failed")
                val body = Json.parseToJsonElement(response.body.string()).jsonObject
                body["files"]?.jsonArray.orEmpty().forEach { item -> result += item.jsonObject.remoteFile() }
                body["nextPageToken"]?.jsonPrimitive?.content
            }
        } while (pageToken != null)
        require(result.map(DriveRemoteFile::name).distinct().size == result.size) { "duplicate Drive repository object" }
        DomainResult.Success(result)
    } catch (_: DriveAuthorizationException) {
        DomainResult.Failure(BackupFailure.DriveAuthorizationRequired)
    } catch (_: IOException) {
        DomainResult.Failure(BackupFailure.NetworkUnavailable)
    } catch (_: Exception) {
        DomainResult.Failure(BackupFailure.RepositoryUnavailable)
    }

    fun deleteRepositoryFile(token: String, remoteFileId: String): DomainResult<Unit> = try {
        require(token.isNotBlank())
        requireRemoteId(remoteFileId)
        val request = Request.Builder()
            .url(filesEndpoint.newBuilder().addPathSegment(remoteFileId).build())
            .header("Authorization", "Bearer $token")
            .delete()
            .build()
        client.newCall(request).execute().use { response ->
            checkAuthorization(response)
            if (response.code != 204 && response.code != 404) throw IOException("Drive repository object deletion failed")
        }
        DomainResult.Success(Unit)
    } catch (_: DriveAuthorizationException) {
        DomainResult.Failure(BackupFailure.DriveAuthorizationRequired)
    } catch (_: IOException) {
        DomainResult.Failure(BackupFailure.NetworkUnavailable)
    } catch (_: Exception) {
        DomainResult.Failure(BackupFailure.RepositoryUnavailable)
    }

    fun upload(
        operationId: StableId,
        token: String,
        opaqueName: String,
        content: File,
        mimeType: String,
        parentFolderId: String?,
        recovered: DriveResumableCheckpoint?,
        checkpointWriter: DriveCheckpointWriter,
        cancelled: () -> Boolean = { false },
        replaceRemoteFileId: String? = null,
    ): DomainResult<DriveUploadReceipt> = try {
        require(token.isNotBlank())
        require(content.isFile)
        var checkpoint = recovered?.also {
            require(it.operationId == operationId && it.opaqueObjectName == opaqueName && it.totalBytes == content.length())
        } ?: begin(operationId, token, opaqueName, content.length(), mimeType, parentFolderId, replaceRemoteFileId)
        checkpointWriter.save(checkpoint)
        if (checkpoint.complete) return DomainResult.Success(DriveUploadReceipt(requireNotNull(checkpoint.remoteFileId), checkpoint.totalBytes))
        checkpoint = queryStatus(token, checkpoint) ?: begin(
            operationId,
            token,
            opaqueName,
            content.length(),
            mimeType,
            parentFolderId,
            replaceRemoteFileId,
        )
        checkpointWriter.save(checkpoint)
        while (!checkpoint.complete) {
            if (cancelled() || Thread.currentThread().isInterrupted) return DomainResult.Failure(BackupFailure.Cancelled)
            checkpoint = uploadChunk(token, checkpoint, content)
            checkpointWriter.save(checkpoint)
        }
        DomainResult.Success(DriveUploadReceipt(requireNotNull(checkpoint.remoteFileId), checkpoint.totalBytes))
    } catch (_: DriveAuthorizationException) {
        DomainResult.Failure(BackupFailure.DriveAuthorizationRequired)
    } catch (_: IOException) {
        DomainResult.Failure(BackupFailure.NetworkUnavailable)
    } catch (_: Exception) {
        DomainResult.Failure(BackupFailure.RepositoryUnavailable)
    }

    fun downloadRange(
        token: String,
        remoteFileId: String,
        destination: File,
        expectedBytes: Long,
        checkpoint: (Long) -> Unit,
        cancelled: () -> Boolean = { false },
    ): DomainResult<Long> = try {
        require(token.isNotBlank() && remoteFileId.matches(Regex("[A-Za-z0-9_-]{1,200}")))
        require(expectedBytes >= 0L)
        var offset = destination.takeIf(File::isFile)?.length() ?: 0L
        require(offset <= expectedBytes)
        while (offset < expectedBytes) {
            if (cancelled() || Thread.currentThread().isInterrupted) return DomainResult.Failure(BackupFailure.Cancelled)
            val request = Request.Builder()
                .url(filesEndpoint.newBuilder().addPathSegment(remoteFileId).addQueryParameter("alt", "media").build())
                .header("Authorization", "Bearer $token")
                .header("Range", "bytes=$offset-")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) throw DriveAuthorizationException()
                if (response.code !in setOf(200, 206)) throw IOException("Drive Range download failed")
                if (offset > 0L && response.code != 206) throw IOException("Drive ignored resume range")
                val body = response.body
                FileOutputStream(destination, offset > 0L).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        while (true) {
                            if (cancelled()) return DomainResult.Failure(BackupFailure.Cancelled)
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            output.write(buffer, 0, count)
                            offset = Math.addExact(offset, count.toLong())
                            require(offset <= expectedBytes)
                            checkpoint(offset)
                        }
                        buffer.fill(0)
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
        }
        DomainResult.Success(offset)
    } catch (_: DriveAuthorizationException) {
        DomainResult.Failure(BackupFailure.DriveAuthorizationRequired)
    } catch (_: IOException) {
        DomainResult.Failure(BackupFailure.NetworkUnavailable)
    } catch (_: Exception) {
        DomainResult.Failure(BackupFailure.RepositoryUnavailable)
    }

    fun findByOpaqueName(
        token: String,
        opaqueName: String,
        parentFolderId: String?,
    ): DomainResult<DriveRemoteFile?> = try {
        require(token.isNotBlank() && opaqueName.matches(Regex("[A-Za-z0-9_.-]{1,180}")))
        val query = buildString {
            append("name = '").append(opaqueName).append("' and trashed = false")
            if (parentFolderId != null) append(" and '").append(parentFolderId).append("' in parents")
        }
        val request = Request.Builder()
            .url(
                filesEndpoint.newBuilder()
                    .addQueryParameter("q", query)
                    .addQueryParameter("spaces", "drive")
                    .addQueryParameter("fields", "files(id,name,size,md5Checksum)")
                    .addQueryParameter("pageSize", "2")
                    .build(),
            )
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            checkAuthorization(response)
            if (!response.isSuccessful) throw IOException("Drive object lookup failed")
            val files = Json.parseToJsonElement(response.body.string()).jsonObject["files"]?.jsonArray.orEmpty()
            require(files.size <= 1) { "duplicate opaque Drive backup object" }
            DomainResult.Success(files.singleOrNull()?.jsonObject?.remoteFile()?.also { require(it.name == opaqueName) })
        }
    } catch (_: DriveAuthorizationException) {
        DomainResult.Failure(BackupFailure.DriveAuthorizationRequired)
    } catch (_: IOException) {
        DomainResult.Failure(BackupFailure.NetworkUnavailable)
    } catch (_: Exception) {
        DomainResult.Failure(BackupFailure.RepositoryUnavailable)
    }

    private fun begin(
        operationId: StableId,
        token: String,
        name: String,
        bytes: Long,
        mimeType: String,
        parentFolderId: String?,
        replaceRemoteFileId: String?,
    ): DriveResumableCheckpoint {
        val metadata = buildJsonObject {
            put("name", name)
            put("mimeType", mimeType)
            if (parentFolderId != null) put("parents", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(parentFolderId)) })
        }.toString()
        require(replaceRemoteFileId == null || replaceRemoteFileId.matches(Regex("[A-Za-z0-9_-]{1,200}")))
        val requestBuilder = Request.Builder()
            .url(
                uploadEndpoint.newBuilder()
                    .apply { if (replaceRemoteFileId != null) addPathSegment(replaceRemoteFileId) }
                    .addQueryParameter("uploadType", "resumable")
                    .addQueryParameter("fields", "id,name,size")
                    .build(),
            )
            .header("Authorization", "Bearer $token")
            .header("X-Upload-Content-Type", mimeType)
            .header("X-Upload-Content-Length", bytes.toString())
        val request = if (replaceRemoteFileId == null) {
            requestBuilder.post(metadata.toRequestBody(JSON_MEDIA_TYPE)).build()
        } else {
            requestBuilder.patch(metadata.toRequestBody(JSON_MEDIA_TYPE)).build()
        }
        client.newCall(request).execute().use { response ->
            checkAuthorization(response)
            if (!response.isSuccessful) throw IOException("Drive resumable initialization failed")
            val location = response.header("Location") ?: throw IOException("Drive resumable session missing")
            return DriveResumableCheckpoint(operationId, name, location, 0L, bytes, null, bytes == 0L)
        }
    }

    private fun queryStatus(token: String, value: DriveResumableCheckpoint): DriveResumableCheckpoint? {
        val request = Request.Builder()
            .url(value.sessionUrl)
            .header("Authorization", "Bearer $token")
            .header("Content-Range", "bytes */${value.totalBytes}")
            .put(EMPTY_REQUEST_BODY)
            .build()
        client.newCall(request).execute().use { response ->
            checkAuthorization(response)
            return when (response.code) {
                200, 201 -> value.copy(nextByte = value.totalBytes, remoteFileId = response.remoteId(), complete = true)
                308 -> value.copy(nextByte = response.nextByte())
                404 -> null
                else -> throw IOException("Drive resumable status failed")
            }
        }
    }

    private fun uploadChunk(token: String, value: DriveResumableCheckpoint, file: File): DriveResumableCheckpoint {
        val remaining = Math.subtractExact(value.totalBytes, value.nextByte)
        val length = minOf(chunkBytes.toLong(), remaining)
        val endInclusive = Math.subtractExact(Math.addExact(value.nextByte, length), 1L)
        val request = Request.Builder()
            .url(value.sessionUrl)
            .header("Authorization", "Bearer $token")
            .header("Content-Length", length.toString())
            .header("Content-Range", "bytes ${value.nextByte}-$endInclusive/${value.totalBytes}")
            .put(FileRangeRequestBody(file, value.nextByte, length))
            .build()
        client.newCall(request).execute().use { response ->
            checkAuthorization(response)
            return when (response.code) {
                200, 201 -> value.copy(nextByte = value.totalBytes, remoteFileId = response.remoteId(), complete = true)
                308 -> value.copy(nextByte = response.nextByte())
                404 -> throw IOException("Drive resumable session expired")
                else -> throw IOException("Drive chunk upload failed")
            }
        }
    }

    private fun checkAuthorization(response: Response) {
        if (response.code == 401 || response.code == 403) throw DriveAuthorizationException()
    }

    private fun kotlinx.serialization.json.JsonObject.remoteFile(): DriveRemoteFile {
        val id = requireNotNull(this["id"]?.jsonPrimitive?.content).also(::requireRemoteId)
        val name = requireNotNull(this["name"]?.jsonPrimitive?.content)
        val size = requireNotNull(this["size"]?.jsonPrimitive?.content?.toLongOrNull())
        require(name.matches(Regex("[A-Za-z0-9_.-]{1,180}")) && size >= 0L)
        val md5 = this["md5Checksum"]?.jsonPrimitive?.content
        require(md5 == null || md5.matches(Regex("[0-9a-f]{32}")))
        return DriveRemoteFile(id, name, size, md5)
    }

    private fun requireRemoteId(value: String) {
        require(value.matches(Regex("[A-Za-z0-9_-]{1,200}")))
    }

    private fun Response.nextByte(): Long {
        val range = header("Range") ?: return 0L
        val last = RANGE.matchEntire(range)?.groupValues?.get(1)?.toLongOrNull() ?: throw IOException("invalid Drive Range")
        return Math.addExact(last, 1L)
    }

    private fun Response.remoteId(): String {
        val bodyText = body.string()
        return Json.parseToJsonElement(bodyText).jsonObject["id"]?.jsonPrimitive?.content
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,200}")) }
            ?: throw IOException("Drive completion identifier invalid")
    }

    private fun String.toRequestBody(type: okhttp3.MediaType): RequestBody = object : RequestBody() {
        private val encoded = toByteArray(Charsets.UTF_8)
        override fun contentType(): okhttp3.MediaType = type
        override fun contentLength(): Long = encoded.size.toLong()
        override fun writeTo(sink: BufferedSink) {
            sink.write(encoded)
        }
    }

    private class DriveAuthorizationException : IOException()

    private class FileRangeRequestBody(private val file: File, private val offset: Long, private val length: Long) : RequestBody() {
        override fun contentType() = BINARY_MEDIA_TYPE
        override fun contentLength(): Long = length
        override fun writeTo(sink: BufferedSink) {
            RandomAccessFile(file, "r").use { source ->
                source.seek(offset)
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var remaining = length
                try {
                    while (remaining > 0L) {
                        val count = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (count < 0) throw IOException("source truncated during upload")
                        if (count == 0) continue
                        sink.write(buffer, 0, count)
                        remaining -= count.toLong()
                    }
                } finally {
                    buffer.fill(0)
                }
            }
        }
    }

    companion object {
        private val DEFAULT_UPLOAD_ENDPOINT = "https://www.googleapis.com/upload/drive/v3/files".toHttpUrl()
        private val DEFAULT_FILES_ENDPOINT = "https://www.googleapis.com/drive/v3/files".toHttpUrl()
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val BINARY_MEDIA_TYPE = "application/octet-stream".toMediaType()
        private const val DRIVE_FOLDER_MEDIA_TYPE = "application/vnd.google-apps.folder"
        private val EMPTY_REQUEST_BODY = ByteArray(0).let { bytes ->
            object : RequestBody() {
                override fun contentType() = BINARY_MEDIA_TYPE
                override fun contentLength(): Long = 0L
                override fun writeTo(sink: BufferedSink) {
                    sink.write(bytes)
                }
            }
        }
        private val RANGE = Regex("bytes=0-([0-9]+)")
        const val MINIMUM_CHUNK_BYTES = 256 * 1024
        const val DEFAULT_CHUNK_BYTES = 8 * 1024 * 1024
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
