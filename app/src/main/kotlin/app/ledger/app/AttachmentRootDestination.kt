@file:Suppress("ktlint:standard:function-naming")

package app.ledger.app

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.files.AttachmentExternalOpenDialog
import app.ledger.core.files.AttachmentPreviewScreen
import app.ledger.core.files.AttachmentRenameDialog

@Composable
internal fun AttachmentRootDestination(
    screenId: String,
    encodedArguments: Map<String, String>,
    viewModel: AppRootViewModel,
    onNavigationChanged: () -> Unit,
) {
    val attachmentId = encodedArguments["attachmentId"]
        ?.let(StableId::parse)
        ?.let { (it as? DomainResult.Success)?.value }
    val state by viewModel.attachmentFlow.collectAsStateWithLifecycle()
    LaunchedEffect(attachmentId) {
        if (attachmentId != null) viewModel.ensureAttachmentLoaded(attachmentId)
    }
    when (screenId) {
        "ATT-001" -> AttachmentPreviewScreen(
            state.preview,
            viewModel.attachmentImageLoader(),
            {
                viewModel.openAttachmentRename()
                onNavigationChanged()
            },
            {
                viewModel.openAttachmentExternal()
                onNavigationChanged()
            },
            viewModel::retryAttachment,
        )
        "ATT-002" -> {
            val context = LocalContext.current
            AttachmentExternalOpenDialog(
                onConfirm = {
                    val intent = viewModel.authorizeAttachmentExternalOpen()
                    if (intent != null) {
                        try {
                            context.startActivity(Intent.createChooser(intent, null))
                            viewModel.dismissAttachmentDialog()
                            onNavigationChanged()
                        } catch (_: ActivityNotFoundException) {
                            viewModel.externalApplicationUnavailable()
                        }
                    }
                },
                onDismiss = {
                    viewModel.dismissAttachmentDialog()
                    onNavigationChanged()
                },
            )
        }
        "ATT-003" -> AttachmentRenameDialog(
            state.rename,
            viewModel::changeAttachmentName,
            { viewModel.saveAttachmentName(onNavigationChanged) },
            {
                viewModel.dismissAttachmentDialog()
                onNavigationChanged()
            },
        )
    }
}
