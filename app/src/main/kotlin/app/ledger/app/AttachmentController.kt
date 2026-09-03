package app.ledger.app

import android.content.Context
import android.content.Intent
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.designsystem.UiErrorCode
import app.ledger.core.files.AttachmentMetadata
import app.ledger.core.files.AttachmentMetadataPolicy
import app.ledger.core.files.AttachmentMetadataUiModel
import app.ledger.core.files.AttachmentPreviewState
import app.ledger.core.files.AttachmentRenameState
import app.ledger.core.files.SecureAttachmentImageLoader
import app.ledger.core.files.SecureAttachmentSession
import app.ledger.core.files.SecureBookAttachmentObjectPort
import app.ledger.finance.domain.AttachmentId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

internal data class AttachmentFlowState(
    val attachmentId: AttachmentId? = null,
    val preview: AttachmentPreviewState = AttachmentPreviewState.Loading,
    val rename: AttachmentRenameState = AttachmentRenameState.Editing(""),
)

internal class AttachmentController(
    context: Context,
    private val port: SecureBookAttachmentObjectPort,
    private val formatImportedAt: (Instant) -> String,
    private val formatSize: (Long) -> String,
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val mutableState = MutableStateFlow(AttachmentFlowState())
    val state: StateFlow<AttachmentFlowState> = mutableState.asStateFlow()
    private var bookId: StableId? = null
    private var session: SecureAttachmentSession? = null

    val imageLoader: SecureAttachmentImageLoader?
        get() = session?.imageLoader

    fun prepare(attachmentId: AttachmentId) {
        mutableState.value = AttachmentFlowState(attachmentId = attachmentId)
    }

    suspend fun open(activeBookId: StableId, attachmentId: AttachmentId) {
        closeSession()
        bookId = activeBookId
        prepare(attachmentId)
        try {
            val opened = port.openSession(activeBookId)
            session = opened
            val metadata = opened.metadata(attachmentId) ?: error("attachment unavailable")
            publish(metadata)
        } catch (_: Exception) {
            closeSession()
            mutableState.value = AttachmentFlowState(
                attachmentId = attachmentId,
                preview = AttachmentPreviewState.DecryptError(UiErrorCode("ATTACHMENT_DECRYPTION_FAILED")),
            )
        }
    }

    suspend fun retry() {
        val activeBook = bookId ?: return
        val attachment = mutableState.value.attachmentId ?: return
        open(activeBook, attachment)
    }

    fun renameChanged(value: String) {
        val valid = runCatching { AttachmentMetadataPolicy.sanitizeDisplayName(value) == value }.getOrDefault(false)
        mutableState.value = mutableState.value.copy(
            rename = if (valid) AttachmentRenameState.Editing(value) else AttachmentRenameState.Invalid(value),
        )
    }

    suspend fun commitRename(): Boolean {
        val attachment = mutableState.value.attachmentId ?: return false
        val requested = when (val value = mutableState.value.rename) {
            is AttachmentRenameState.Editing -> value.displayName
            is AttachmentRenameState.Invalid -> return false
        }
        return when (val result = session?.rename(attachment, requested)) {
            is DomainResult.Success -> {
                publish(result.value)
                true
            }
            else -> {
                mutableState.value = mutableState.value.copy(rename = AttachmentRenameState.Invalid(requested))
                false
            }
        }
    }

    fun externalOpenIntent(): Intent? {
        val attachment = mutableState.value.attachmentId ?: return null
        return session?.externalOpenIntent(attachment)
    }

    override fun close() {
        closeSession()
        bookId = null
        mutableState.value = AttachmentFlowState()
    }

    private fun publish(metadata: AttachmentMetadata) {
        val ui = metadata.toUi()
        mutableState.value = AttachmentFlowState(
            metadata.attachmentId,
            if (metadata.mimeType.startsWith("image/")) {
                AttachmentPreviewState.Image(ui)
            } else {
                AttachmentPreviewState.UnsupportedPreview(ui)
            },
            AttachmentRenameState.Editing(metadata.displayName),
        )
    }

    private fun AttachmentMetadata.toUi(): AttachmentMetadataUiModel = AttachmentMetadataUiModel(
        attachmentId,
        displayName,
        mimeType,
        formatSize(plaintextSize),
        formatImportedAt(importedAt),
    )

    private fun closeSession() {
        session?.close()
        session = null
    }
}
