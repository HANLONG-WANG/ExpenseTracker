package app.ledger.core.files

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import app.ledger.core.common.DomainError
import app.ledger.finance.application.AttachmentContentSource
import app.ledger.finance.application.AttachmentImportRequest
import app.ledger.finance.domain.AttachmentId
import app.ledger.finance.domain.BlobId
import app.ledger.finance.domain.Hash256
import java.io.FileNotFoundException
import java.io.InputStream
import java.time.Instant
import java.util.Locale

enum class AttachmentInfrastructureError(override val code: String) : DomainError {
    SOURCE_UNAVAILABLE("ATTACHMENT_SOURCE_UNAVAILABLE"),
    INVALID_METADATA("ATTACHMENT_INVALID_METADATA"),
    IO_FAILURE("ATTACHMENT_IO_FAILURE"),
    STORAGE_EXHAUSTED("ATTACHMENT_STORAGE_EXHAUSTED"),
    DATABASE_FAILURE("ATTACHMENT_DATABASE_FAILURE"),
    MISSING_ENCRYPTED_OBJECT("ATTACHMENT_MISSING_ENCRYPTED_OBJECT"),
    DECRYPTION_FAILED("ATTACHMENT_DECRYPTION_FAILED"),
    CANCELLED("ATTACHMENT_CANCELLED"),
    NOT_FOUND("ATTACHMENT_NOT_FOUND"),
    STILL_REFERENCED("ATTACHMENT_STILL_REFERENCED"),
    APP_LOCKED("ATTACHMENT_APP_LOCKED"),
    AUTHORIZATION_EXPIRED("ATTACHMENT_AUTHORIZATION_EXPIRED"),
}

data class AttachmentImportProgress(
    val processedBytes: Long,
    val declaredTotalBytes: Long?,
) {
    init {
        require(processedBytes >= 0L)
        require(declaredTotalBytes == null || declaredTotalBytes >= 0L)
    }

    val fraction: Float?
        get() = declaredTotalBytes?.takeIf { it > 0L }?.let { total ->
            (processedBytes.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
        }
}

data class AttachmentMetadata(
    val attachmentId: AttachmentId,
    val displayName: String,
    val mimeType: String,
    val extension: String?,
    val plaintextSize: Long,
    val importedAt: Instant,
) {
    init {
        require(displayName == AttachmentMetadataPolicy.sanitizeDisplayName(displayName))
        require(plaintextSize >= 0L)
        require(AttachmentMetadataPolicy.isNormalizedMimeType(mimeType))
    }
}

internal data class StoredAttachmentObject(
    val attachmentId: AttachmentId,
    val blobId: BlobId,
    val storageName: String,
    val plaintextHash: Hash256,
    val plaintextSize: Long,
    val mimeType: String,
    val extension: String?,
    val wrappedDataKey: ByteArray,
    val encryptionVersion: Int,
    val displayName: String,
    val importedAt: Instant,
) {
    init {
        require(OPAQUE_STORAGE_NAME.matches(storageName))
        require(plaintextSize >= 0L)
        require(encryptionVersion > 0)
    }

    override fun toString(): String = "StoredAttachmentObject(attachmentId=redacted,blobId=redacted,storageName=redacted,hash=redacted,size=$plaintextSize)"

    private companion object {
        val OPAQUE_STORAGE_NAME = Regex("[0-9a-f]{32}\\.blob")
    }
}

object AttachmentMetadataPolicy {
    private const val DEFAULT_DISPLAY_NAME = "attachment"
    private const val DEFAULT_MIME_TYPE = "application/octet-stream"
    private const val MAXIMUM_DISPLAY_NAME_CHARS = 255
    private const val MAXIMUM_EXTENSION_CHARS = 16
    private val MIME_TYPE = Regex("[a-z0-9][a-z0-9!#$&^_.+-]{0,126}/[a-z0-9][a-z0-9!#$&^_.+-]{0,126}")
    private val UNSAFE_DISPLAY_CHARS = Regex("[\\p{Cc}\\p{Cf}/\\\\]")
    private val EXTENSION = Regex("[a-z0-9][a-z0-9._+-]{0,15}")

    fun sanitizeDisplayName(value: String): String {
        val sanitized = value
            .replace(UNSAFE_DISPLAY_CHARS, " ")
            .trim()
            .trimStart('.', ' ')
            .take(MAXIMUM_DISPLAY_NAME_CHARS)
        return sanitized.ifBlank { DEFAULT_DISPLAY_NAME }
    }

    fun normalizeMimeType(value: String?): String = value
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.takeIf(::isNormalizedMimeType)
        ?: DEFAULT_MIME_TYPE

    fun extensionFromDisplayName(value: String): String? = value
        .substringAfterLast('.', missingDelimiterValue = "")
        .lowercase(Locale.ROOT)
        .take(MAXIMUM_EXTENSION_CHARS)
        .takeIf { EXTENSION.matches(it) }

    fun normalizeExtension(value: String?, displayName: String): String? = value
        ?.trim()
        ?.removePrefix(".")
        ?.lowercase(Locale.ROOT)
        ?.take(MAXIMUM_EXTENSION_CHARS)
        ?.takeIf { EXTENSION.matches(it) }
        ?: extensionFromDisplayName(displayName)

    fun isNormalizedMimeType(value: String): Boolean = MIME_TYPE.matches(value)
}

class ContentResolverAttachmentRequestFactory(
    private val resolver: ContentResolver,
) {
    fun create(uri: Uri, requestedDisplayName: String? = null): AttachmentImportRequest {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "attachment imports require a content URI" }
        val providerName = queryDisplayName(uri)
        val displayName = AttachmentMetadataPolicy.sanitizeDisplayName(requestedDisplayName ?: providerName.orEmpty())
        return AttachmentImportRequest(
            displayName = displayName,
            mimeType = AttachmentMetadataPolicy.normalizeMimeType(resolver.getType(uri)),
            extension = AttachmentMetadataPolicy.extensionFromDisplayName(displayName),
            declaredSize = queryDeclaredSize(uri),
            content = ContentResolverAttachmentContent(resolver, uri),
        )
    }

    private fun queryDisplayName(uri: Uri): String? = resolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor -> cursor.firstStringOrNull() }

    private fun queryDeclaredSize(uri: Uri): Long? {
        val queried = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).takeIf { it >= 0L } else null
        }
        return queried ?: resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.length.takeIf { it >= 0L }
        }
    }
}

private class ContentResolverAttachmentContent(
    private val resolver: ContentResolver,
    private val uri: Uri,
) : AttachmentContentSource {
    override fun openStream(): InputStream = resolver.openInputStream(uri)
        ?: throw FileNotFoundException("attachment source is unavailable")

    override fun toString(): String = "ContentResolverAttachmentContent(redacted)"
}

private fun Cursor.firstStringOrNull(): String? = if (moveToFirst() && !isNull(0)) getString(0) else null
