@file:Suppress("ktlint:standard:function-naming", "FunctionNaming", "LongParameterList")

package app.ledger.feature.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.ledger.core.designsystem.AttachmentField
import app.ledger.core.designsystem.AttachmentTransferState
import app.ledger.core.designsystem.AttachmentUiModel
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerButton
import app.ledger.core.designsystem.LedgerEmptyState
import app.ledger.core.designsystem.LedgerScaffold
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.LedgerTopAppBar
import app.ledger.core.designsystem.LedgerTopAppBarVariant
import app.ledger.core.designsystem.LocationField
import app.ledger.core.designsystem.LocationFieldState
import app.ledger.core.designsystem.SelectorField
import app.ledger.core.designsystem.UiErrorCode

sealed interface RecordLocationEditorState {
    data object Locating : RecordLocationEditorState

    data class Located(val accuracyText: String, val selectedPlaceText: String) : RecordLocationEditorState

    data object PermissionDenied : RecordLocationEditorState

    data object Timeout : RecordLocationEditorState

    data class Manual(val selectedPlaceText: String) : RecordLocationEditorState

    data object MapUnavailable : RecordLocationEditorState
}

@Composable
fun RecordLocationEditorScreen(
    state: RecordLocationEditorState,
    mapContent: @Composable () -> Unit,
    onBack: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenMap: () -> Unit,
    onSelectPlace: () -> Unit,
    onUseLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.testTag(LedgerTestTags.LOCATION_EDITOR)) {
        LedgerScaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                LedgerTopAppBar(
                    title = stringResource(R.string.record_location_title),
                    variant = LedgerTopAppBarVariant.BACK,
                    onNavigation = onBack,
                )
            },
            fixedAction = {
                LedgerButton(stringResource(R.string.record_location_use), onUseLocation)
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
            ) {
                LocationStatus(state, onRequestPermission, onOpenMap)
                mapContent()
                when (state) {
                    is RecordLocationEditorState.Located -> PlaceSelector(state.selectedPlaceText, onSelectPlace)
                    is RecordLocationEditorState.Manual -> PlaceSelector(state.selectedPlaceText, onSelectPlace)
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun LocationStatus(
    state: RecordLocationEditorState,
    onRequestPermission: () -> Unit,
    onOpenMap: () -> Unit,
) {
    val fieldState = when (state) {
        RecordLocationEditorState.Locating -> LocationFieldState.Locating
        is RecordLocationEditorState.Located -> LocationFieldState.Located(state.accuracyText)
        RecordLocationEditorState.PermissionDenied -> LocationFieldState.PermissionDenied
        RecordLocationEditorState.Timeout -> LocationFieldState.Unavailable
        is RecordLocationEditorState.Manual -> LocationFieldState.ManuallyAdjusted
        RecordLocationEditorState.MapUnavailable -> LocationFieldState.Unavailable
    }
    LocationField(fieldState, onOpenMap, mapLabel = stringResource(R.string.record_location_adjust))
    when (state) {
        RecordLocationEditorState.PermissionDenied -> LedgerBanner(
            message = stringResource(R.string.record_location_permission_denied),
            variant = LedgerBannerVariant.INFO,
            actionLabel = stringResource(R.string.record_location_permission_action),
            onAction = onRequestPermission,
        )
        RecordLocationEditorState.Timeout -> LedgerBanner(
            message = stringResource(R.string.record_location_timeout),
            variant = LedgerBannerVariant.WARNING,
        )
        RecordLocationEditorState.MapUnavailable -> LedgerBanner(
            message = stringResource(R.string.record_location_map_unavailable),
            variant = LedgerBannerVariant.INFO,
        )
        else -> Unit
    }
}

@Composable
private fun PlaceSelector(selectedText: String, onSelectPlace: () -> Unit) {
    SelectorField(
        label = stringResource(R.string.record_location_place_label),
        selectedText = selectedText,
        onClick = onSelectPlace,
        modifier = Modifier.fillMaxWidth(),
        supportingText = stringResource(R.string.record_location_place_offline_note),
    )
}

sealed interface RecordAttachmentsState {
    data class Content(val attachments: List<AttachmentUiModel>) : RecordAttachmentsState {
        init {
            require(attachments.isNotEmpty())
            require(attachments.none { it.state == AttachmentTransferState.IMPORTING })
        }
    }

    data object Empty : RecordAttachmentsState

    data class Importing(val attachments: List<AttachmentUiModel>) : RecordAttachmentsState {
        init {
            require(attachments.any { it.state == AttachmentTransferState.IMPORTING })
        }
    }

    data class Failed(val attachments: List<AttachmentUiModel>, val errorCode: UiErrorCode) : RecordAttachmentsState
}

@Composable
fun RecordAttachmentsScreen(
    state: RecordAttachmentsState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (AttachmentUiModel) -> Unit,
    onCancel: (AttachmentUiModel) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.testTag(LedgerTestTags.ATTACHMENT_LIST)) {
        LedgerScaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                LedgerTopAppBar(
                    title = stringResource(R.string.record_attachments_title),
                    variant = LedgerTopAppBarVariant.BACK,
                    onNavigation = onBack,
                )
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
            ) {
                when (state) {
                    RecordAttachmentsState.Empty -> LedgerEmptyState(
                        title = stringResource(R.string.record_attachments_empty_title),
                        explanation = stringResource(R.string.record_attachments_empty_message),
                        primaryAction = stringResource(R.string.record_attachments_add),
                        onPrimaryAction = onAdd,
                    )
                    is RecordAttachmentsState.Failed -> {
                        LedgerBanner(
                            message = stringResource(R.string.record_attachments_failed, state.errorCode.value),
                            variant = LedgerBannerVariant.DANGER,
                            actionLabel = stringResource(R.string.record_attachments_retry),
                            onAction = onRetry,
                        )
                        AttachmentList(state.attachments, onAdd, onOpen, onCancel)
                    }
                    is RecordAttachmentsState.Content -> AttachmentList(state.attachments, onAdd, onOpen, onCancel)
                    is RecordAttachmentsState.Importing -> AttachmentList(state.attachments, onAdd, onOpen, onCancel)
                }
            }
        }
    }
}

@Composable
private fun AttachmentList(
    attachments: List<AttachmentUiModel>,
    onAdd: () -> Unit,
    onOpen: (AttachmentUiModel) -> Unit,
    onCancel: (AttachmentUiModel) -> Unit,
) {
    AttachmentField(
        attachments = attachments,
        onAdd = onAdd,
        onOpen = onOpen,
        onCancel = onCancel,
        addLabel = stringResource(R.string.record_attachments_add),
    )
}
