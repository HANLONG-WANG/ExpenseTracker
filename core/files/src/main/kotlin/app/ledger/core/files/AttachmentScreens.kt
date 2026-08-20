@file:Suppress("ktlint:standard:function-naming", "FunctionNaming", "LongParameterList")

package app.ledger.core.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.ledger.core.designsystem.FormSection
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerButton
import app.ledger.core.designsystem.LedgerButtonVariant
import app.ledger.core.designsystem.LedgerCard
import app.ledger.core.designsystem.LedgerDialog
import app.ledger.core.designsystem.LedgerErrorState
import app.ledger.core.designsystem.LedgerLoadingState
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerTextField
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.UiErrorCode
import app.ledger.finance.domain.AttachmentId

data class AttachmentMetadataUiModel(
    val attachmentId: AttachmentId,
    val displayName: String,
    val typeText: String,
    val sizeText: String,
    val importedAtText: String,
)

sealed interface AttachmentPreviewState {
    data object Loading : AttachmentPreviewState

    data class Image(val metadata: AttachmentMetadataUiModel) : AttachmentPreviewState

    data class UnsupportedPreview(val metadata: AttachmentMetadataUiModel) : AttachmentPreviewState

    data class DecryptError(val code: UiErrorCode) : AttachmentPreviewState
}

@Composable
fun AttachmentPreviewScreen(
    state: AttachmentPreviewState,
    secureImageLoader: SecureAttachmentImageLoader?,
    onRename: () -> Unit,
    onOpenExternally: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.padding(LedgerTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
    ) {
        when (state) {
            AttachmentPreviewState.Loading -> LedgerLoadingState()
            is AttachmentPreviewState.Image -> {
                SecureAttachmentImagePreview(state.metadata.attachmentId, checkNotNull(secureImageLoader))
                AttachmentMetadataPanel(state.metadata)
                AttachmentActions(onRename, onOpenExternally)
            }
            is AttachmentPreviewState.UnsupportedPreview -> {
                LedgerBanner(
                    stringResource(R.string.attachment_preview_unsupported),
                    LedgerBannerVariant.INFO,
                )
                AttachmentMetadataPanel(state.metadata)
                AttachmentActions(onRename, onOpenExternally)
            }
            is AttachmentPreviewState.DecryptError -> LedgerErrorState(
                message = stringResource(R.string.attachment_decrypt_error_message),
                code = state.code,
                onRetry = onRetry,
            )
        }
    }
}

@Composable
private fun AttachmentMetadataPanel(model: AttachmentMetadataUiModel) {
    LedgerCard(Modifier.fillMaxWidth().testTag(LedgerTestTags.ATTACHMENT_METADATA)) {
        FormSection(stringResource(R.string.attachment_metadata_title), Modifier.padding(LedgerTheme.spacing.sm)) {
            androidx.compose.material3.Text(model.displayName, style = LedgerTheme.typography.titleSmall)
            androidx.compose.material3.Text(model.typeText, style = LedgerTheme.typography.bodyMedium)
            androidx.compose.material3.Text(model.sizeText, style = LedgerTheme.typography.bodyMedium)
            androidx.compose.material3.Text(model.importedAtText, style = LedgerTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AttachmentActions(onRename: () -> Unit, onOpenExternally: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        LedgerButton(
            stringResource(R.string.attachment_rename_action),
            onRename,
            modifier = Modifier.weight(1f),
            variant = LedgerButtonVariant.SECONDARY,
        )
        LedgerButton(
            stringResource(R.string.attachment_external_open_action),
            onOpenExternally,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun AttachmentExternalOpenDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LedgerDialog(
        title = stringResource(R.string.attachment_external_open_title),
        message = stringResource(R.string.attachment_external_open_risk),
        confirmLabel = stringResource(R.string.attachment_external_open_confirm),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        modifier = modifier.testTag(LedgerTestTags.ATTACHMENT_EXTERNAL_OPEN),
    )
}

sealed interface AttachmentRenameState {
    data class Editing(val displayName: String) : AttachmentRenameState

    data class Invalid(val displayName: String) : AttachmentRenameState
}

@Composable
fun AttachmentRenameDialog(
    state: AttachmentRenameState,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayName = when (state) {
        is AttachmentRenameState.Editing -> state.displayName
        is AttachmentRenameState.Invalid -> state.displayName
    }
    LedgerDialog(
        title = stringResource(R.string.attachment_rename_title),
        message = null,
        confirmLabel = stringResource(R.string.attachment_rename_confirm),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        modifier = modifier.testTag(LedgerTestTags.ATTACHMENT_RENAME),
        content = {
            LedgerTextField(
                value = displayName,
                onValueChange = onNameChange,
                label = stringResource(R.string.attachment_display_name_label),
                errorText = if (state is AttachmentRenameState.Invalid) {
                    stringResource(R.string.attachment_display_name_invalid)
                } else {
                    null
                },
            )
        },
    )
}
