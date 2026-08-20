package app.ledger.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ledger.core.designsystem.LedgerErrorState
import app.ledger.core.designsystem.LedgerLoadingState
import app.ledger.core.files.AttachmentExternalOpenDialog
import app.ledger.core.files.AttachmentPreviewScreen
import app.ledger.core.files.AttachmentPreviewState
import app.ledger.core.files.AttachmentRenameDialog
import app.ledger.core.files.R as FilesR

@Composable
internal fun AttachmentRootDestination(
    screenId: String,
    viewModel: AppRootViewModel,
    onNavigationChanged: () -> Unit,
) {
    val preview by viewModel.attachmentPreview.collectAsStateWithLifecycle()
    val rename by viewModel.attachmentRename.collectAsStateWithLifecycle()
    when (screenId) {
        "ATT-001" -> {
            val imageLoader = viewModel.attachmentImageLoader
            when {
                preview is AttachmentPreviewState.DecryptError -> LedgerErrorState(
                    message = stringResource(FilesR.string.attachment_decrypt_error_message),
                    code = (preview as AttachmentPreviewState.DecryptError).code,
                    onRetry = viewModel::retryAttachment,
                    modifier = Modifier.fillMaxSize(),
                )
                imageLoader == null -> LedgerLoadingState(Modifier.fillMaxSize())
                else -> AttachmentPreviewScreen(
                    state = preview,
                    secureImageLoader = imageLoader,
                    onRename = {
                        viewModel.beginAttachmentRename()
                        onNavigationChanged()
                    },
                    onOpenExternally = {
                        viewModel.beginAttachmentExternalOpen()
                        onNavigationChanged()
                    },
                    onRetry = viewModel::retryAttachment,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        "ATT-002" -> AttachmentExternalOpenDialog(
            onConfirm = { viewModel.confirmAttachmentExternalOpen(onNavigationChanged) },
            onDismiss = {
                viewModel.requestRootBack()
                onNavigationChanged()
            },
        )
        "ATT-003" -> rename?.let { state ->
            AttachmentRenameDialog(
                state = state,
                onNameChange = viewModel::updateAttachmentRename,
                onConfirm = { viewModel.confirmAttachmentRename(onNavigationChanged) },
                onDismiss = {
                    viewModel.requestRootBack()
                    onNavigationChanged()
                },
            )
        } ?: LedgerLoadingState(Modifier.fillMaxSize())
    }
}
