@file:Suppress("ktlint:standard:function-naming", "FunctionNaming")

package app.ledger.core.files

import android.content.Context
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.finance.domain.AttachmentId
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.Options
import okio.buffer
import okio.source

data class EncryptedAttachmentImage(
    val attachmentId: AttachmentId,
    val variant: AttachmentContentVariant,
)

class SecureAttachmentImageLoader(
    context: Context,
    reader: AttachmentContentReader,
) : AutoCloseable {
    val imageLoader: ImageLoader = ImageLoader.Builder(context.applicationContext)
        .components {
            add(EncryptedAttachmentImageKeyer())
            add(EncryptedAttachmentFetcher.Factory(reader))
        }
        .diskCache(null)
        .build()

    fun onApplicationLocked() {
        imageLoader.memoryCache?.clear()
        imageLoader.diskCache?.clear()
    }

    override fun close() = imageLoader.shutdown()
}

private class EncryptedAttachmentImageKeyer : Keyer<EncryptedAttachmentImage> {
    override fun key(data: EncryptedAttachmentImage, options: Options): String = "attachment:${data.attachmentId.value}:${data.variant.name}"
}

private class EncryptedAttachmentFetcher(
    private val model: EncryptedAttachmentImage,
    private val options: Options,
    private val reader: AttachmentContentReader,
) : Fetcher {
    override suspend fun fetch(): SourceFetchResult {
        check(options.diskCachePolicy == CachePolicy.DISABLED) { "encrypted attachment disk cache must remain disabled" }
        check(options.networkCachePolicy == CachePolicy.DISABLED) { "encrypted attachment preview is local-only" }
        val decrypted = when (model.variant) {
            AttachmentContentVariant.ORIGINAL -> reader.openOriginal(model.attachmentId)
            AttachmentContentVariant.THUMBNAIL -> reader.openThumbnail(model.attachmentId) ?: reader.openOriginal(model.attachmentId)
        }
        return SourceFetchResult(
            source = ImageSource(decrypted.plaintext.source().buffer(), options.fileSystem),
            mimeType = decrypted.metadata.mimeType,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val reader: AttachmentContentReader) : Fetcher.Factory<EncryptedAttachmentImage> {
        override fun create(
            data: EncryptedAttachmentImage,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = EncryptedAttachmentFetcher(data, options, reader)
    }
}

@Composable
fun SecureAttachmentImagePreview(
    attachmentId: AttachmentId,
    secureImageLoader: SecureAttachmentImageLoader,
    modifier: Modifier = Modifier,
    variant: AttachmentContentVariant = AttachmentContentVariant.ORIGINAL,
) {
    var scale by remember(attachmentId) { mutableFloatStateOf(1f) }
    val transform = rememberTransformableState { _, zoomChange, _, _ ->
        scale = (scale * zoomChange).coerceIn(MINIMUM_PREVIEW_SCALE, MAXIMUM_PREVIEW_SCALE)
    }
    val request = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
        .data(EncryptedAttachmentImage(attachmentId, variant))
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.DISABLED)
        .networkCachePolicy(CachePolicy.DISABLED)
        .build()
    AsyncImage(
        model = request,
        imageLoader = secureImageLoader.imageLoader,
        contentDescription = stringResource(R.string.attachment_secure_image_description),
        modifier = modifier
            .fillMaxWidth()
            .testTag(LedgerTestTags.ATTACHMENT_PREVIEW)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .transformable(transform),
        contentScale = ContentScale.Fit,
    )
}

private const val MINIMUM_PREVIEW_SCALE = 1f
private const val MAXIMUM_PREVIEW_SCALE = 5f
