@file:Suppress(
    "FunctionNaming",
    "LongMethod",
    "LongParameterList",
    "MagicNumber",
    "TooManyFunctions",
    "ktlint:standard:function-naming",
)

package app.ledger.feature.transfer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.ledger.core.designsystem.HighRiskConfirmation
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerButton
import app.ledger.core.designsystem.LedgerButtonVariant
import app.ledger.core.designsystem.LedgerCard
import app.ledger.core.designsystem.LedgerChoiceRow
import app.ledger.core.designsystem.LedgerScaffold
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextField
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.LedgerTopAppBar
import app.ledger.core.designsystem.LedgerTopAppBarVariant
import app.ledger.core.designsystem.OperationCapability
import app.ledger.core.designsystem.OperationProgressPanel
import app.ledger.core.designsystem.OperationProgressUiModel
import app.ledger.core.designsystem.UiErrorCode
import app.ledger.transfer.domain.MergeConflictKind
import app.ledger.transfer.domain.MergeResolution
import app.ledger.transfer.domain.RestoreMode
import app.ledger.transfer.domain.RestoreState

enum class RestoreSourcePresentation { CONTENT, LOADING_REMOTE, PERMISSION_ERROR }
enum class RestorePasswordPresentation { EDITING, VERIFYING, WRONG_PASSWORD, LOCKED_DELAY }
enum class RestoreInspectPresentation { CHECKING, COMPATIBLE, INCOMPATIBLE_BOOK, INCOMPATIBLE_CURRENCY, CORRUPT }
enum class RestoreProgressPresentation { RUNNING, FAILED_ROLLBACK, SUCCEEDED }
enum class RestoreResultPresentation { SUCCESS, ROLLED_BACK, FAILED }
enum class CloudClearPresentation { CONTENT, AUTH_REQUIRED, DELETING, FAILED }

data class RestoreConflictUi(
    val id: String,
    val kind: MergeConflictKind,
    val entityLabel: String,
    val ancestorSummary: String,
    val localSummary: String,
    val incomingSummary: String,
    val resolution: MergeResolution?,
    val purgeTombstoneWins: Boolean,
)

data class RestoreFlowUiState(
    val screenId: String = "RST-001",
    val sourcePresentation: RestoreSourcePresentation = RestoreSourcePresentation.CONTENT,
    val passwordPresentation: RestorePasswordPresentation = RestorePasswordPresentation.EDITING,
    val inspectPresentation: RestoreInspectPresentation = RestoreInspectPresentation.CHECKING,
    val password: RestorePasswordInput = RestorePasswordInput.empty(),
    val sourceLabel: String = "",
    val bookIdentity: String = "",
    val sourceVersion: String = "",
    val contentSummary: String = "",
    val integritySummary: String = "",
    val mode: RestoreMode = RestoreMode.REPLACE,
    val mergeAvailable: Boolean = false,
    val highRiskPhrase: String = "",
    val conflicts: List<RestoreConflictUi> = emptyList(),
    val phase: RestoreState = RestoreState.READING_SOURCE,
    val completedBytes: Long = 0,
    val totalBytes: Long? = null,
    val progressPresentation: RestoreProgressPresentation = RestoreProgressPresentation.RUNNING,
    val resultPresentation: RestoreResultPresentation = RestoreResultPresentation.SUCCESS,
    val safetySnapshotLabel: String = "",
    val safetySnapshotRetained: Boolean = false,
    val verificationSummary: String = "",
    val failureCode: String? = null,
    val cloudClearPresentation: CloudClearPresentation = CloudClearPresentation.CONTENT,
    val cloudSnapshots: List<String> = emptyList(),
    val selectedCloudSnapshots: Set<String> = emptySet(),
    val cloudConfirmationPhrase: String = "",
)

sealed interface RestoreFlowScreenAction {
    data object Back : RestoreFlowScreenAction
    data class PortableSource(val uri: Uri) : RestoreFlowScreenAction
    data object RepositorySource : RestoreFlowScreenAction
    data object DriveSource : RestoreFlowScreenAction
    data class PasswordChanged(val value: String) : RestoreFlowScreenAction
    data object VerifyPassword : RestoreFlowScreenAction
    data class ModeSelected(val mode: RestoreMode) : RestoreFlowScreenAction
    data class HighRiskPhraseChanged(val value: String) : RestoreFlowScreenAction
    data object StartRestore : RestoreFlowScreenAction
    data class ResolveConflict(val conflictId: String, val resolution: MergeResolution) : RestoreFlowScreenAction
    data object ApplyMerge : RestoreFlowScreenAction
    data object Cancel : RestoreFlowScreenAction
    data object Retry : RestoreFlowScreenAction
    data object OpenApp : RestoreFlowScreenAction
    data object ConfirmSafetySnapshotCleanup : RestoreFlowScreenAction
    data class CloudSnapshotSelected(val snapshotId: String) : RestoreFlowScreenAction
    data class CloudConfirmationChanged(val value: String) : RestoreFlowScreenAction
    data object AuthenticateCloudDelete : RestoreFlowScreenAction
    data object DeleteCloudBackups : RestoreFlowScreenAction
}

private class RestoreFlowActions(
    val onBack: () -> Unit,
    val onPortableSource: (Uri) -> Unit,
    val onRepositorySource: () -> Unit,
    val onDriveSource: () -> Unit,
    val onPasswordChanged: (String) -> Unit,
    val onVerifyPassword: () -> Unit,
    val onModeSelected: (RestoreMode) -> Unit,
    val onHighRiskPhraseChanged: (String) -> Unit,
    val onStartRestore: () -> Unit,
    val onResolveConflict: (String, MergeResolution) -> Unit,
    val onApplyMerge: () -> Unit,
    val onCancel: () -> Unit,
    val onRetry: () -> Unit,
    val onOpenApp: () -> Unit,
    val onConfirmSafetySnapshotCleanup: () -> Unit,
    val onCloudSnapshotSelected: (String) -> Unit,
    val onCloudConfirmationChanged: (String) -> Unit,
    val onAuthenticateCloudDelete: () -> Unit,
    val onDeleteCloudBackups: () -> Unit,
)

@Composable
fun RestoreFlowScreen(state: RestoreFlowUiState, onAction: (RestoreFlowScreenAction) -> Unit) {
    val actions = RestoreFlowActions(
        onBack = { onAction(RestoreFlowScreenAction.Back) },
        onPortableSource = { onAction(RestoreFlowScreenAction.PortableSource(it)) },
        onRepositorySource = { onAction(RestoreFlowScreenAction.RepositorySource) },
        onDriveSource = { onAction(RestoreFlowScreenAction.DriveSource) },
        onPasswordChanged = { onAction(RestoreFlowScreenAction.PasswordChanged(it)) },
        onVerifyPassword = { onAction(RestoreFlowScreenAction.VerifyPassword) },
        onModeSelected = { onAction(RestoreFlowScreenAction.ModeSelected(it)) },
        onHighRiskPhraseChanged = { onAction(RestoreFlowScreenAction.HighRiskPhraseChanged(it)) },
        onStartRestore = { onAction(RestoreFlowScreenAction.StartRestore) },
        onResolveConflict = { id, resolution -> onAction(RestoreFlowScreenAction.ResolveConflict(id, resolution)) },
        onApplyMerge = { onAction(RestoreFlowScreenAction.ApplyMerge) },
        onCancel = { onAction(RestoreFlowScreenAction.Cancel) },
        onRetry = { onAction(RestoreFlowScreenAction.Retry) },
        onOpenApp = { onAction(RestoreFlowScreenAction.OpenApp) },
        onConfirmSafetySnapshotCleanup = { onAction(RestoreFlowScreenAction.ConfirmSafetySnapshotCleanup) },
        onCloudSnapshotSelected = { onAction(RestoreFlowScreenAction.CloudSnapshotSelected(it)) },
        onCloudConfirmationChanged = { onAction(RestoreFlowScreenAction.CloudConfirmationChanged(it)) },
        onAuthenticateCloudDelete = { onAction(RestoreFlowScreenAction.AuthenticateCloudDelete) },
        onDeleteCloudBackups = { onAction(RestoreFlowScreenAction.DeleteCloudBackups) },
    )
    LedgerScaffold(
        Modifier.fillMaxSize().testTag("restore_flow_root"),
        topBar = { LedgerTopAppBar(state.title(), LedgerTopAppBarVariant.BACK, onNavigation = actions.onBack) },
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding)
        when (state.screenId) {
            "RST-001" -> RestoreSource(state, actions, modifier)
            "RST-002" -> RestorePassword(state, actions, modifier)
            "RST-003" -> RestoreInspection(state, modifier)
            "RST-004" -> RestoreModeChoice(state, actions, modifier)
            "RST-005" -> RestoreConflicts(state, actions, modifier)
            "RST-006" -> RestoreProgress(state, actions, modifier)
            "RST-007" -> RestoreResult(state, actions, modifier)
            "CLR-002" -> CloudBackupClear(state, actions, modifier)
            else -> error("unknown restore screen")
        }
    }
}

@Composable
private fun RestoreSource(state: RestoreFlowUiState, actions: RestoreFlowActions, modifier: Modifier) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(actions.onPortableSource)
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        item { LedgerText(stringResource(R.string.restore_source_supporting), LedgerTextRole.SUPPORTING) }
        if (state.sourcePresentation == RestoreSourcePresentation.LOADING_REMOTE) {
            item { LedgerBanner(stringResource(R.string.restore_loading_remote), LedgerBannerVariant.INFO) }
        }
        if (state.sourcePresentation == RestoreSourcePresentation.PERMISSION_ERROR) {
            item { LedgerBanner(stringResource(R.string.restore_permission_error), LedgerBannerVariant.DANGER) }
        }
        item { LedgerButton(stringResource(R.string.restore_choose_file), { picker.launch(arrayOf("application/octet-stream", "application/zip")) }, Modifier.fillMaxWidth()) }
        item { LedgerButton(stringResource(R.string.restore_choose_repository), actions.onRepositorySource, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY) }
        item { LedgerButton(stringResource(R.string.restore_choose_drive), actions.onDriveSource, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY) }
        if (state.sourceLabel.isNotBlank()) item { Summary(stringResource(R.string.restore_selected_source), state.sourceLabel) }
    }
}

@Composable
private fun RestorePassword(state: RestoreFlowUiState, actions: RestoreFlowActions, modifier: Modifier) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        item { LedgerBanner(stringResource(R.string.restore_no_backdoor), LedgerBannerVariant.WARNING) }
        item {
            LedgerTextField(
                state.password.editableText(),
                actions.onPasswordChanged,
                stringResource(R.string.restore_password),
                required = true,
                sensitive = true,
                hideValueFromSemantics = true,
            )
        }
        if (state.passwordPresentation == RestorePasswordPresentation.WRONG_PASSWORD) {
            item { LedgerBanner(stringResource(R.string.restore_wrong_password), LedgerBannerVariant.DANGER) }
        }
        if (state.passwordPresentation == RestorePasswordPresentation.LOCKED_DELAY) {
            item { LedgerBanner(stringResource(R.string.restore_locked_delay), LedgerBannerVariant.WARNING) }
        }
        item {
            LedgerButton(
                stringResource(R.string.restore_verify_password),
                actions.onVerifyPassword,
                Modifier.fillMaxWidth(),
                enabled = !state.password.isBlank && state.passwordPresentation != RestorePasswordPresentation.VERIFYING,
            )
        }
    }
}

@Composable
private fun RestoreInspection(state: RestoreFlowUiState, modifier: Modifier) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        item {
            val (text, variant) = when (state.inspectPresentation) {
                RestoreInspectPresentation.CHECKING -> stringResource(R.string.restore_checking) to LedgerBannerVariant.INFO
                RestoreInspectPresentation.COMPATIBLE -> stringResource(R.string.restore_compatible) to LedgerBannerVariant.INFO
                RestoreInspectPresentation.INCOMPATIBLE_BOOK -> stringResource(R.string.restore_incompatible_book) to LedgerBannerVariant.DANGER
                RestoreInspectPresentation.INCOMPATIBLE_CURRENCY -> stringResource(R.string.restore_incompatible_currency) to LedgerBannerVariant.DANGER
                RestoreInspectPresentation.CORRUPT -> stringResource(R.string.restore_corrupt) to LedgerBannerVariant.DANGER
            }
            LedgerBanner(text, variant)
        }
        item { Summary(stringResource(R.string.restore_identity), state.bookIdentity) }
        item { Summary(stringResource(R.string.restore_version), state.sourceVersion) }
        item { Summary(stringResource(R.string.restore_contents), state.contentSummary) }
        item { Summary(stringResource(R.string.restore_integrity), state.integritySummary) }
    }
}

@Composable
private fun RestoreModeChoice(state: RestoreFlowUiState, actions: RestoreFlowActions, modifier: Modifier) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        item { LedgerBanner(stringResource(R.string.restore_safety_snapshot), LedgerBannerVariant.WARNING) }
        item {
            LedgerChoiceRow(
                stringResource(R.string.restore_replace),
                state.mode == RestoreMode.REPLACE,
                { actions.onModeSelected(RestoreMode.REPLACE) },
                supportingText = stringResource(R.string.restore_replace_supporting),
            )
        }
        item {
            LedgerChoiceRow(
                stringResource(R.string.restore_merge),
                state.mode == RestoreMode.MERGE,
                { actions.onModeSelected(RestoreMode.MERGE) },
                supportingText = stringResource(
                    if (state.mergeAvailable) {
                        R.string.restore_merge_supporting
                    } else {
                        R.string.restore_merge_unavailable
                    },
                ),
                enabled = state.mergeAvailable,
            )
        }
        if (state.mode == RestoreMode.REPLACE) {
            item {
                HighRiskConfirmation(
                    stringResource(R.string.restore_replace_confirm_title),
                    stringResource(R.string.restore_replace_scope),
                    stringResource(R.string.restore_replace_consequence),
                    stringResource(R.string.restore_replace_unaffected),
                    stringResource(R.string.restore_replace_phrase),
                    state.highRiskPhrase,
                    actions.onHighRiskPhraseChanged,
                    actions.onStartRestore,
                    actions.onBack,
                )
            }
        } else {
            item { LedgerButton(stringResource(R.string.restore_continue_merge), actions.onStartRestore, Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun RestoreConflicts(state: RestoreFlowUiState, actions: RestoreFlowActions, modifier: Modifier) {
    val resolved = state.conflicts.count { it.resolution != null }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        item { LedgerText(stringResource(R.string.restore_conflict_progress, resolved, state.conflicts.size), LedgerTextRole.SECTION) }
        items(state.conflicts, key = RestoreConflictUi::id) { conflict ->
            LedgerCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                    LedgerText(conflict.entityLabel, LedgerTextRole.SECTION)
                    LedgerText(conflict.kind.name, LedgerTextRole.SUPPORTING)
                    if (conflict.purgeTombstoneWins) {
                        LedgerBanner(stringResource(R.string.restore_purge_wins), LedgerBannerVariant.DANGER)
                    } else {
                        LedgerText(stringResource(R.string.restore_ancestor_value, conflict.ancestorSummary), LedgerTextRole.BODY)
                        LedgerChoiceRow(
                            stringResource(R.string.restore_local_value, conflict.localSummary),
                            conflict.resolution == MergeResolution.KeepLocal,
                            { actions.onResolveConflict(conflict.id, MergeResolution.KeepLocal) },
                        )
                        LedgerChoiceRow(
                            stringResource(R.string.restore_incoming_value, conflict.incomingSummary),
                            conflict.resolution == MergeResolution.KeepIncoming,
                            { actions.onResolveConflict(conflict.id, MergeResolution.KeepIncoming) },
                        )
                    }
                }
            }
        }
        item {
            LedgerButton(
                stringResource(R.string.restore_apply_merge),
                actions.onApplyMerge,
                Modifier.fillMaxWidth(),
                enabled = state.conflicts.all { it.resolution != null || it.purgeTombstoneWins },
            )
        }
    }
}

@Composable
private fun RestoreProgress(state: RestoreFlowUiState, actions: RestoreFlowActions, modifier: Modifier) {
    val nonCancelable = state.phase in setOf(RestoreState.EXCHANGING, RestoreState.VERIFYING_LIVE, RestoreState.ROLLING_BACK)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        OperationProgressPanel(
            OperationProgressUiModel(
                stringResource(R.string.restore_operation),
                state.phase.label(),
                stringResource(R.string.restore_progress_bytes, state.completedBytes),
                state.totalBytes?.takeIf { it > 0 }?.let { state.completedBytes.toFloat() / it.toFloat() },
                if (nonCancelable) OperationCapability.NON_CANCELABLE_COMMIT else OperationCapability.CANCELABLE,
                stringResource(if (nonCancelable) R.string.restore_non_interruptible else R.string.restore_safe_cancel),
                state.failureCode?.let(::UiErrorCode),
            ),
            onCancel = actions.onCancel.takeUnless { nonCancelable },
        )
        if (state.progressPresentation == RestoreProgressPresentation.FAILED_ROLLBACK) {
            LedgerBanner(stringResource(R.string.restore_failed_rolled_back), LedgerBannerVariant.DANGER)
            LedgerButton(stringResource(R.string.restore_retry), actions.onRetry, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun RestoreResult(state: RestoreFlowUiState, actions: RestoreFlowActions, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        val text = when (state.resultPresentation) {
            RestoreResultPresentation.SUCCESS -> stringResource(R.string.restore_result_success)
            RestoreResultPresentation.ROLLED_BACK -> stringResource(R.string.restore_result_rolled_back)
            RestoreResultPresentation.FAILED -> stringResource(R.string.restore_result_failed)
        }
        LedgerBanner(text, if (state.resultPresentation == RestoreResultPresentation.SUCCESS) LedgerBannerVariant.INFO else LedgerBannerVariant.DANGER)
        Summary(stringResource(R.string.restore_safety_snapshot_label), state.safetySnapshotLabel)
        Summary(stringResource(R.string.restore_verification_summary), state.verificationSummary)
        if (state.resultPresentation == RestoreResultPresentation.SUCCESS && state.safetySnapshotRetained) {
            LedgerButton(
                stringResource(R.string.restore_confirm_safety_cleanup),
                actions.onConfirmSafetySnapshotCleanup,
                Modifier.fillMaxWidth(),
                variant = LedgerButtonVariant.DANGER,
            )
        }
        LedgerButton(stringResource(R.string.restore_open_app), actions.onOpenApp, Modifier.fillMaxWidth(), enabled = state.resultPresentation == RestoreResultPresentation.SUCCESS)
    }
}

@Composable
private fun CloudBackupClear(state: RestoreFlowUiState, actions: RestoreFlowActions, modifier: Modifier) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        item { LedgerBanner(stringResource(R.string.clear_cloud_only), LedgerBannerVariant.WARNING) }
        items(state.cloudSnapshots) { snapshot ->
            LedgerChoiceRow(snapshot, snapshot in state.selectedCloudSnapshots, { actions.onCloudSnapshotSelected(snapshot) })
        }
        if (state.cloudClearPresentation == CloudClearPresentation.AUTH_REQUIRED) {
            item { LedgerButton(stringResource(R.string.clear_cloud_auth), actions.onAuthenticateCloudDelete, Modifier.fillMaxWidth()) }
        }
        if (state.cloudClearPresentation == CloudClearPresentation.FAILED) {
            item { LedgerBanner(stringResource(R.string.clear_cloud_failed), LedgerBannerVariant.DANGER) }
        }
        item {
            HighRiskConfirmation(
                stringResource(R.string.clear_cloud_title),
                stringResource(R.string.clear_cloud_scope),
                stringResource(R.string.clear_cloud_consequence),
                stringResource(R.string.clear_cloud_unaffected),
                stringResource(R.string.clear_cloud_phrase),
                state.cloudConfirmationPhrase,
                actions.onCloudConfirmationChanged,
                actions.onDeleteCloudBackups,
                actions.onBack,
            )
        }
    }
}

@Composable
private fun Summary(label: String, value: String) {
    LedgerCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(LedgerTheme.spacing.sm)) {
            LedgerText(label, LedgerTextRole.SECTION)
            LedgerText(value.ifBlank { stringResource(R.string.restore_not_available) }, LedgerTextRole.BODY)
        }
    }
}

@Composable
private fun RestoreFlowUiState.title(): String = stringResource(
    when (screenId) {
        "RST-001" -> R.string.restore_source_title
        "RST-002" -> R.string.restore_password_title
        "RST-003" -> R.string.restore_inspect_title
        "RST-004" -> R.string.restore_mode_title
        "RST-005" -> R.string.restore_conflicts_title
        "RST-006" -> R.string.restore_progress_title
        "RST-007" -> R.string.restore_result_title
        "CLR-002" -> R.string.clear_cloud_title
        else -> R.string.restore_source_title
    },
)

@Composable
private fun RestoreState.label(): String = stringResource(
    when (this) {
        RestoreState.READING_SOURCE -> R.string.restore_phase_downloading
        RestoreState.AUTHENTICATING_PASSWORD, RestoreState.VERIFYING_OBJECTS -> R.string.restore_phase_verifying
        RestoreState.MIGRATING -> R.string.restore_phase_migrating
        RestoreState.CHECKING_INTEGRITY, RestoreState.REBUILDING_PROJECTIONS, RestoreState.VALIDATING -> R.string.restore_phase_rebuilding
        RestoreState.READY_TO_EXCHANGE, RestoreState.EXCHANGING -> R.string.restore_phase_swapping
        RestoreState.VERIFYING_LIVE -> R.string.restore_phase_live_verify
        RestoreState.ROLLING_BACK -> R.string.restore_phase_rollback
        RestoreState.COMPLETE -> R.string.restore_phase_complete
        RestoreState.FAILED -> R.string.restore_phase_failed
    },
)
