@file:Suppress("MagicNumber", "ReturnCount")

package app.ledger.core.files

import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import app.ledger.core.security.CryptographicRandomSource
import app.ledger.core.security.PlatformCryptographicRandomSource
import app.ledger.finance.domain.AttachmentId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class SecureAttachmentExternalOpen(
    context: Context,
    private val runtime: SecureAttachmentProviderRuntime,
) {
    private val applicationContext = context.applicationContext

    fun beginConfirmation(attachmentId: AttachmentId): ExternalOpenConfirmation? {
        val metadata = runtime.metadata(attachmentId) ?: return null
        return ExternalOpenConfirmation(metadata) { targetPackage ->
            runtime.authorize(applicationContext, attachmentId, targetPackage)
        }
    }
}

class ExternalOpenConfirmation internal constructor(
    val metadata: AttachmentMetadata,
    private val authorizeAction: (String?) -> ExternalOpenAuthorization,
) {
    private var consumed = false

    @Synchronized
    fun authorize(targetPackage: String? = null): ExternalOpenAuthorization {
        check(!consumed) { "external-open confirmation was already consumed" }
        consumed = true
        return authorizeAction(targetPackage)
    }
}

class ExternalOpenAuthorization internal constructor(
    val intent: Intent,
    val expiresAtElapsedRealtimeMillis: Long,
)

class SecureAttachmentProviderRuntime(
    private val elapsedRealtimeMillis: () -> Long,
    private val cryptographicRandom: CryptographicRandomSource = PlatformCryptographicRandomSource,
) : AutoCloseable {
    private val grants = ConcurrentHashMap<String, OneTimeGrant>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var reader: AttachmentContentReader? = null

    @Volatile private var imageLoader: SecureAttachmentImageLoader? = null

    @Volatile private var unlocked = false

    fun onBookReady(contentReader: AttachmentContentReader, secureImageLoader: SecureAttachmentImageLoader) {
        grants.clear()
        reader = contentReader
        imageLoader = secureImageLoader
        unlocked = true
    }

    fun onApplicationLocked() {
        unlocked = false
        grants.clear()
        imageLoader?.onApplicationLocked()
    }

    internal fun metadata(attachmentId: AttachmentId): AttachmentMetadata? = if (unlocked) reader?.metadata(attachmentId) else null

    internal fun authorize(context: Context, attachmentId: AttachmentId, targetPackage: String?): ExternalOpenAuthorization {
        check(unlocked) { "attachment external opening is unavailable while locked" }
        val metadata = reader?.metadata(attachmentId) ?: throw FileNotFoundException("attachment is unavailable")
        val token = nextToken()
        val expiresAt = Math.addExact(elapsedRealtimeMillis(), AUTHORIZATION_LIFETIME_MILLIS)
        grants[token] = OneTimeGrant(attachmentId, metadata, expiresAt)
        val uri = Uri.Builder()
            .scheme("content")
            .authority(context.packageName + AUTHORITY_SUFFIX)
            .appendPath(OPEN_PATH)
            .appendPath(token)
            .build()
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, metadata.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri("attachment", uri)
        if (targetPackage != null) {
            intent.setPackage(targetPackage)
            context.grantUriPermission(targetPackage, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return ExternalOpenAuthorization(intent, expiresAt)
    }

    internal fun peek(token: String): OneTimeGrant? {
        if (!unlocked) return null
        val grant = grants[token] ?: return null
        return if (elapsedRealtimeMillis() <= grant.expiresAtElapsedRealtimeMillis) {
            grant
        } else {
            grants.remove(token)
            null
        }
    }

    internal fun consume(token: String): Pair<OneTimeGrant, DecryptedAttachment>? {
        if (!unlocked) return null
        val grant = grants.remove(token) ?: return null
        if (elapsedRealtimeMillis() > grant.expiresAtElapsedRealtimeMillis) return null
        val opened = reader?.openOriginal(grant.attachmentId) ?: return null
        return grant to opened
    }

    internal fun pipe(decrypted: DecryptedAttachment, writeSide: ParcelFileDescriptor) {
        scope.launch {
            decrypted.use { content ->
                ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { destination ->
                    val buffer = ByteArray(PIPE_BUFFER_BYTES)
                    try {
                        while (true) {
                            val count = content.plaintext.read(buffer)
                            if (count < 0) break
                            if (count > 0) destination.write(buffer, 0, count)
                        }
                    } catch (_: IOException) {
                        // The receiving app may close the read side at any time; no plaintext is persisted.
                    } finally {
                        buffer.fill(0)
                    }
                }
            }
        }
    }

    override fun close() {
        onApplicationLocked()
        scope.cancel()
        reader = null
        imageLoader = null
    }

    private fun nextToken(): String {
        repeat(MAXIMUM_TOKEN_ATTEMPTS) {
            val bytes = ByteArray(TOKEN_BYTES).also(cryptographicRandom::nextBytes)
            val token = buildString(TOKEN_BYTES * 2) {
                bytes.forEach { byte ->
                    val value = byte.toInt() and 0xff
                    append(HEX[value ushr 4])
                    append(HEX[value and 0x0f])
                }
            }
            bytes.fill(0)
            if (!grants.containsKey(token)) return token
        }
        error("unable to create an attachment authorization")
    }

    internal data class OneTimeGrant(
        val attachmentId: AttachmentId,
        val metadata: AttachmentMetadata,
        val expiresAtElapsedRealtimeMillis: Long,
    )

    private companion object {
        const val AUTHORIZATION_LIFETIME_MILLIS = 60_000L
        const val PIPE_BUFFER_BYTES = 64 * 1024
        const val TOKEN_BYTES = 24
        const val MAXIMUM_TOKEN_ATTEMPTS = 16
        const val AUTHORITY_SUFFIX = ".secure-attachments"
        const val OPEN_PATH = "open"
        const val HEX = "0123456789abcdef"
    }
}

object SecureAttachmentProviderProcess {
    @Volatile private var runtime: SecureAttachmentProviderRuntime? = null

    fun install(value: SecureAttachmentProviderRuntime) {
        check(runtime == null) { "secure attachment provider runtime is already installed" }
        runtime = value
    }

    fun uninstall(value: SecureAttachmentProviderRuntime) {
        if (runtime === value) runtime = null
    }

    internal fun requireRuntime(): SecureAttachmentProviderRuntime = runtime ?: throw FileNotFoundException("attachment provider is unavailable")
}

class SecureAttachmentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = grant(uri)?.metadata?.mimeType

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val grant = grant(uri) ?: return null
        val columns = projection?.filter(ALLOWED_QUERY_COLUMNS::contains).orEmpty().ifEmpty { ALLOWED_QUERY_COLUMNS }
        return MatrixCursor(columns.toTypedArray(), 1).apply {
            addRow(
                columns.map { column ->
                    when (column) {
                        OpenableColumns.DISPLAY_NAME -> grant.metadata.displayName
                        OpenableColumns.SIZE -> grant.metadata.plaintextSize
                        else -> null
                    }
                },
            )
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("attachment provider is read-only")
        val token = token(uri) ?: throw FileNotFoundException("attachment authorization is invalid")
        val runtime = SecureAttachmentProviderProcess.requireRuntime()
        val consumed = runtime.consume(token) ?: throw FileNotFoundException("attachment authorization is unavailable")
        val pipe = ParcelFileDescriptor.createReliablePipe()
        context?.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runtime.pipe(consumed.second, pipe[1])
        return pipe[0]
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("attachment provider is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("attachment provider is read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("attachment provider is read-only")

    private fun grant(uri: Uri): SecureAttachmentProviderRuntime.OneTimeGrant? {
        val token = token(uri) ?: return null
        return SecureAttachmentProviderProcess.requireRuntime().peek(token)
    }

    private fun token(uri: Uri): String? {
        val expectedAuthority = context?.packageName + AUTHORITY_SUFFIX
        if (uri.authority != expectedAuthority || URI_MATCHER.match(uri) != OPEN_TOKEN) return null
        return uri.lastPathSegment?.takeIf(TOKEN_PATTERN::matches)
    }

    private companion object {
        const val AUTHORITY_SUFFIX = ".secure-attachments"
        const val OPEN_TOKEN = 1
        val TOKEN_PATTERN = Regex("[0-9a-f]{48}")
        val ALLOWED_QUERY_COLUMNS = listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val URI_MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply { addURI("*", "open/*", OPEN_TOKEN) }
    }
}
