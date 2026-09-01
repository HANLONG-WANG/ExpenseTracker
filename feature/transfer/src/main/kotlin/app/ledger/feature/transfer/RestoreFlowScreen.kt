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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.ledger.core.designsystem.HighRiskConfirmation
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerButton
import app.ledger.core.designsystem.LedgerButtonVariant
import app.ledger.core.designsystem.LedgerCard
import app.ledger.core.designsystem.LedgerChoiceRow
import app.ledger.core.designsystem.LedgerLoadingState
import app.ledger.core.designsystem.LedgerScaffold
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextField
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.LedgerToggleRow
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
import java.text.NumberFormat

enum class RestoreSourcePresentation { CONTENT, LOADING_REMOTE, PERMISSION_ERROR, DRIVE_NOT_CONFIGURED }
enum class RestorePasswordPresentation { EDITING, VERIFYING, WRONG_PASSWORD, LOCKED_DELAY }
enum class RestoreInspectPresentation { CHECKING, COMPATIBLE, INCOMPATIBLE_BOOK, INCOMPATIBLE_CURRENCY, CORRUPT }
enum class RestoreProgressPresentation { RUNNING, FAILED_ROLLBACK, SUCCEEDED }
enum class RestoreResultPresentation { SUCCESS, ROLLED_BACK, FAILED }
enum class CloudClearPresentation { LOADING, CONTENT, EMPTY, AUTH_REQUIRED, DELETING, FAILED }
enum class RestoreSourcePicker { NONE, REPOSITORY, DRIVE }
enum class RestoreIntegrityCheck {
    SCHEMA,
    MIGRATIONS,
    SQLCIPHER,
    ENCRYPTION_AND_HASHES,
    FOREIGN_KEYS,
    JOURNAL_BALANCE,
    PROJECTIONS,
    TRANSACTION_SUBTYPES,
    ATTACHMENTS,
    BOOK_IDENTITY,
    BASE_CURRENCY,
}
enum class RestoreConflictField { RECORD_CONTENT }

data class RestoreSnapshotUi(
    val id: String,
    val createdAt: String,
    val repositoryKind: app.ledger.transfer.domain.BackupRepositoryKind,
    val verified: Boolean,
    val includesVault: Boolean,
)

data class RestoreIntegrityCheckUi(
    val check: RestoreIntegrityCheck,
    val passed: Boolean,
)

data class RestoreConflictFieldUi(
    val field: RestoreConflictField,
    val ancestorValue: String,
    val localValue: String,
    val incomingValue: String,
    val ancestorGeneration: Long? = null,
    val localGeneration: Long? = null,
    val incomingGeneration: Long? = null,
)

data class RestoreConflictUi(
    val id: String,
    val kind: MergeConflictKind,
    val entityLabel: String,
    val ancestorSummary: String,
    val localSummary: String,
    val incomingSummary: String,
    val resolution: MergeResolution?,
    val purgeTombstoneWins: Boolean,
    val fields: List<RestoreConflictFieldUi> = listOf(
        RestoreConflictFieldUi(
            RestoreConflictField.RECORD_CONTENT,
            ancestorSummary,
            localSummary,
            incomingSummary,
        ),
    ),
)

data class RestoreFlowUiState(
    val screenId: String = "RST-001",
    val sourcePresentation: RestoreSourcePresentation = RestoreSourcePresentation.CONTENT,
    val passwordPresentation: RestorePasswordPresentation = RestorePasswordPresentation.EDITING,
    val inspectPresentation: RestoreInspectPresentation = RestoreInspectPresentation.CHECKING,
    val password: RestorePasswordInput = RestorePasswordInput.empty(),
    val sourceLabel: String = "",
    val sourcePicker: RestoreSourcePicker = RestoreSourcePicker.NONE,
    val repositorySnapshots: List<RestoreSnapshotUi> = emptyList(),
    val driveSnapshots: List<RestoreSnapshotUi> = emptyList(),
    val bookIdentity: String = "",
    val sourceVersion: String = "",
    val baseCurrency: String = "",
    val restoredObjectCount: Long = 0L,
    val restoredLogicalBytes: Long = 0L,
    val attachmentCount: Int = 0,
    val includesVault: Boolean = false,
    val integrityChecks: List<RestoreIntegrityCheckUi> = emptyList(),
    val mode: RestoreMode = RestoreMode.REPLACE,
    val mergeAvailable: Boolean = false,
    val highRiskPhrase: String = "",
    val conflicts: List<RestoreConflictUi> = emptyList(),
    val applyToSimilar: Boolean = false,
    val phase: RestoreState = RestoreState.READING_SOURCE,
    val completedBytes: Long = 0,
    val totalBytes: Long? = null,
    val progressPresentation: RestoreProgressPresentation = RestoreProgressPresentation.RUNNING,
    val resultPresentation: RestoreResultPresentation = RestoreResultPresentation.SUCCESS,
    val safetySnapshotLabel: String = "",
    val safetySnapshotRetained: Boolean = false,
    val verificationSummary: String = "",
    val failureCode: String? = null,
    val cloudClearPresentation: CloudClearPresentation = CloudClearPresentation.AUTH_REQUIRED,
    val cloudAuthenticated: Boolean = false,
    val cloudSnapshots: List<RestoreSnapshotUi> = emptyList(),
    val selectedCloudSnapshots: Set<String> = emptySet(),
    val cloudConfirmationPhrase: String = "",
)

data class RestoreFlowActions(
    val onBack: () -> Unit,
    val onPortableSource: (Uri) -> Unit,
    val onRepositorySource: () -> Unit,
    val onDriveSource: () -> Unit,
    val onSnapshotSourceSelected: (String) -> Unit,
    val onPasswordChanged: (String) -> Unit,
    val onVerifyPassword: () -> Unit,
    val onModeSelected: (RestoreMode) -> Unit,
    val onHighRiskPhraseChanged: (String) -> Unit,
    val onStartRestore: () -> Unit,
    val onResolveConflict: (String, MergeResolution) -> Unit,
    val onApplyToSimilarChanged: (Boolean) -> Unit,
    val onApplyMerge: () -> Unit,
    val onCancel: () -> Unit,
    val onRetry: () -> Unit,
    val onOpenApp: () -> Unit,
    val onConfirmSafetySnapshotCleanup: () -> Unit,
    val onCloudSnapshotSelected: (String) -> Unit,
    val onCloudConfirmationChanged: (String) -> Unit,
    val onAuthenticateCloudDelete: () -> Unit,
    val onDeleteCloudBackups: () -> Unit,
    val onConfigureDrive: () -> Unit = {},
)

@Composable
fun RestoreFlowScreen(state: RestoreFlowUiState, actions: RestoreFlowActions) {
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
        if (state.sourcePresentation == RestoreSourcePresentation.DRIVE_NOT_CONFIGURED) {
            item { LedgerBanner(stringResource(R.string.restore_drive_not_configured), LedgerBannerVariant.WARNING) }
            item { LedgerButton(stringResource(R.string.restore_configure_drive), actions.onConfigureDrive, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY) }
        }
        item { LedgerButton(stringResource(R.string.restore_choose_file), { picker.launch(arrayOf("application/octet-stream", "application/zip")) }, Modifier.fillMaxWidth()) }
        item { LedgerButton(stringResource(R.string.restore_choose_repository), actions.onRepositorySource, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY) }
        item { LedgerButton(stringResource(R.string.restore_choose_drive), actions.onDriveSource, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY) }
        val snapshots = when (state.sourcePicker) {
            RestoreSourcePicker.REPOSITORY -> state.repositorySnapshots
            RestoreSourcePicker.DRIVE -> state.driveSnapshots
            RestoreSourcePicker.NONE -> emptyList()
        }
        if (state.sourcePicker != RestoreSourcePicker.NONE && snapshots.isEmpty() && state.sourcePresentation == RestoreSourcePresentation.CONTENT) {
            item { LedgerBanner(stringResource(R.string.restore_snapshot_picker_empty), LedgerBannerVariant.INFO) }
        }
        items(snapshots, key = RestoreSnapshotUi::id) { snapshot ->
            RestoreSnapshotCard(snapshot, snapshot.id == state.sourceLabel) { actions.onSnapshotSourceSelected(snapshot.id) }
        }
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
    val context = LocalContext.current
    val numberFormat = NumberFormat.getIntegerInstance(context.resources.configuration.locales[0])
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
        item { Summary(stringResource(R.string.restore_base_currency), state.baseCurrency) }
        item { Summary(stringResource(R.string.restore_version), state.sourceVersion) }
        item { Summary(stringResource(R.string.restore_object_count), numberFormat.format(state.restoredObjectCount)) }
        item { Summary(stringResource(R.string.restore_logical_bytes), android.text.format.Formatter.formatFileSize(context, state.restoredLogicalBytes)) }
        item { Summary(stringResource(R.string.restore_attachment_count), numberFormat.format(state.attachmentCount)) }
        item { Summary(stringResource(R.string.restore_vault_contents), stringResource(if (state.includesVault) R.string.restore_included else R.string.restore_not_included)) }
        item { LedgerText(stringResource(R.string.restore_integrity), LedgerTextRole.SECTION) }
        items(state.integrityChecks, key = { it.check }) { check ->
            LedgerCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(LedgerTheme.spacing.sm)) {
                    LedgerText(check.check.label(), LedgerTextRole.BODY)
                    LedgerText(
                        stringResource(if (check.passed) R.string.restore_check_passed else R.string.restore_check_failed),
                        LedgerTextRole.SUPPORTING,
                    )
                }
            }
        }
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
        item {
            LedgerToggleRow(
                stringResource(R.string.restore_apply_similar),
                state.applyToSimilar,
                actions.onApplyToSimilarChanged,
                supportingText = stringResource(R.string.restore_apply_similar_supporting),
            )
        }
        items(state.conflicts, key = RestoreConflictUi::id) { conflict ->
            LedgerCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                    LedgerText(conflict.kind.label(), LedgerTextRole.SECTION)
                    if (conflict.purgeTombstoneWins) {
                        LedgerBanner(stringResource(R.string.restore_purge_wins), LedgerBannerVariant.DANGER)
                    } else {
                        conflict.fields.forEach { field ->
                            LedgerText(field.field.label(), LedgerTextRole.SECTION)
                            LedgerText(
                                stringResource(
                                    R.string.restore_ancestor_value,
                                    conflictVersionLabel(field.ancestorValue, field.ancestorGeneration),
                                ),
                                LedgerTextRole.BODY,
                            )
                            LedgerChoiceRow(
                                stringResource(
                                    R.string.restore_local_value,
                                    conflictVersionLabel(field.localValue, field.localGeneration),
                                ),
                                conflict.resolution == MergeResolution.KeepLocal,
                                { actions.onResolveConflict(conflict.id, MergeResolution.KeepLocal) },
                            )
                            LedgerChoiceRow(
                                stringResource(
                                    R.string.restore_incoming_value,
                                    conflictVersionLabel(field.incomingValue, field.incomingGeneration),
                                ),
                                conflict.resolution == MergeResolution.KeepIncoming,
                                { actions.onResolveConflict(conflict.id, MergeResolution.KeepIncoming) },
                            )
                        }
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
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        item { LedgerText(stringResource(R.string.restore_phase_timeline), LedgerTextRole.SECTION) }
        items(RestoreState.entries) { phase ->
            LedgerText(
                stringResource(
                    when {
                        phase == state.phase -> R.string.restore_phase_current
                        phase.isBefore(state.phase) -> R.string.restore_phase_done
                        else -> R.string.restore_phase_pending
                    },
                    phase.label(),
                ),
                if (phase == state.phase) LedgerTextRole.BODY else LedgerTextRole.SUPPORTING,
            )
        }
        item {
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
        }
        if (state.progressPresentation == RestoreProgressPresentation.FAILED_ROLLBACK) {
            item { LedgerBanner(stringResource(R.string.restore_failed_rolled_back), LedgerBannerVariant.DANGER) }
            item { LedgerButton(stringResource(R.string.restore_retry), actions.onRetry, Modifier.fillMaxWidth()) }
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
        val safetySnapshot = state.safetySnapshotLabel.ifBlank {
            stringResource(
                if (state.resultPresentation == RestoreResultPresentation.SUCCESS) {
                    R.string.restore_safety_snapshot_verified
                } else {
                    R.string.restore_safety_snapshot_retained
                },
            )
        }
        val verification = state.verificationSummary.ifBlank {
            stringResource(
                when (state.resultPresentation) {
                    RestoreResultPresentation.SUCCESS -> R.string.restore_verification_live_verified
                    RestoreResultPresentation.ROLLED_BACK -> R.string.restore_verification_rolled_back
                    RestoreResultPresentation.FAILED -> R.string.restore_verification_not_published
                },
            )
        }
        Summary(stringResource(R.string.restore_safety_snapshot_label), safetySnapshot)
        Summary(stringResource(R.string.restore_verification_summary), verification)
        LedgerButton(stringResource(R.string.restore_open_app), actions.onOpenApp, Modifier.fillMaxWidth(), enabled = state.resultPresentation == RestoreResultPresentation.SUCCESS)
    }
}

@Composable
private fun CloudBackupClear(state: RestoreFlowUiState, actions: RestoreFlowActions, modifier: Modifier) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        item { LedgerBanner(stringResource(R.string.clear_cloud_only), LedgerBannerVariant.WARNING) }
        items(state.cloudSnapshots) { snapshot ->
            RestoreSnapshotCard(snapshot, snapshot.id in state.selectedCloudSnapshots) { actions.onCloudSnapshotSelected(snapshot.id) }
        }
        if (state.cloudClearPresentation == CloudClearPresentation.LOADING) {
            item { LedgerLoadingState(Modifier.fillMaxWidth(), stringResource(R.string.clear_cloud_loading)) }
        }
        if (state.cloudClearPresentation == CloudClearPresentation.AUTH_REQUIRED) {
            item { LedgerBanner(stringResource(R.string.clear_cloud_auth_required), LedgerBannerVariant.INFO) }
            item { LedgerButton(stringResource(R.string.clear_cloud_auth), actions.onAuthenticateCloudDelete, Modifier.fillMaxWidth()) }
        }
        if (state.cloudClearPresentation == CloudClearPresentation.EMPTY) {
            item { LedgerBanner(stringResource(R.string.clear_cloud_empty), LedgerBannerVariant.INFO) }
        }
        if (state.cloudClearPresentation == CloudClearPresentation.DELETING) {
            item {
                OperationProgressPanel(
                    OperationProgressUiModel(
                        stringResource(R.string.clear_cloud_progress_title),
                        stringResource(R.string.clear_cloud_progress_phase),
                        stringResource(R.string.clear_cloud_progress_count, state.selectedCloudSnapshots.size),
                        null,
                        OperationCapability.NON_CANCELABLE_COMMIT,
                        stringResource(R.string.clear_cloud_progress_supporting),
                    ),
                )
            }
        }
        if (state.cloudClearPresentation == CloudClearPresentation.FAILED) {
            item { LedgerBanner(stringResource(R.string.clear_cloud_failed), LedgerBannerVariant.DANGER) }
            item { LedgerButton(stringResource(R.string.restore_configure_drive), actions.onConfigureDrive, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY) }
        }
        if (
            state.cloudAuthenticated &&
            state.selectedCloudSnapshots.isNotEmpty() &&
            state.cloudClearPresentation != CloudClearPresentation.DELETING
        ) item {
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
private fun RestoreSnapshotCard(snapshot: RestoreSnapshotUi, selected: Boolean, onSelected: () -> Unit) {
    LedgerCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            LedgerChoiceRow(
                stringResource(R.string.restore_snapshot_date, snapshot.createdAt),
                selected,
                onSelected,
                supportingText = stringResource(
                    R.string.restore_snapshot_location,
                    snapshot.repositoryKind.label(),
                ),
            )
            LedgerText(
                stringResource(if (snapshot.verified) R.string.restore_snapshot_verified else R.string.restore_snapshot_unverified),
                LedgerTextRole.SUPPORTING,
            )
            LedgerText(
                stringResource(if (snapshot.includesVault) R.string.restore_snapshot_vault_included else R.string.restore_snapshot_vault_excluded),
                LedgerTextRole.SUPPORTING,
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
        RestoreState.AUTHENTICATING_PASSWORD -> R.string.restore_phase_password
        RestoreState.VERIFYING_OBJECTS -> R.string.restore_phase_objects
        RestoreState.MIGRATING -> R.string.restore_phase_migrating
        RestoreState.CHECKING_INTEGRITY -> R.string.restore_phase_integrity
        RestoreState.REBUILDING_PROJECTIONS -> R.string.restore_phase_projections
        RestoreState.VALIDATING -> R.string.restore_phase_validating
        RestoreState.READY_TO_EXCHANGE -> R.string.restore_phase_exchange_ready
        RestoreState.EXCHANGING -> R.string.restore_phase_swapping
        RestoreState.VERIFYING_LIVE -> R.string.restore_phase_live_verify
        RestoreState.ROLLING_BACK -> R.string.restore_phase_rollback
        RestoreState.COMPLETE -> R.string.restore_phase_complete
        RestoreState.FAILED -> R.string.restore_phase_failed
    },
)

private fun RestoreState.isBefore(other: RestoreState): Boolean =
    this !in setOf(RestoreState.FAILED, RestoreState.ROLLING_BACK) &&
        other !in setOf(RestoreState.FAILED, RestoreState.ROLLING_BACK) &&
        ordinal < other.ordinal

@Composable
private fun RestoreIntegrityCheck.label(): String = stringResource(
    when (this) {
        RestoreIntegrityCheck.SCHEMA -> R.string.restore_check_schema
        RestoreIntegrityCheck.MIGRATIONS -> R.string.restore_check_migrations
        RestoreIntegrityCheck.SQLCIPHER -> R.string.restore_check_sqlcipher
        RestoreIntegrityCheck.ENCRYPTION_AND_HASHES -> R.string.restore_check_encryption_hashes
        RestoreIntegrityCheck.FOREIGN_KEYS -> R.string.restore_check_foreign_keys
        RestoreIntegrityCheck.JOURNAL_BALANCE -> R.string.restore_check_journal
        RestoreIntegrityCheck.PROJECTIONS -> R.string.restore_check_projections
        RestoreIntegrityCheck.TRANSACTION_SUBTYPES -> R.string.restore_check_transactions
        RestoreIntegrityCheck.ATTACHMENTS -> R.string.restore_check_attachments
        RestoreIntegrityCheck.BOOK_IDENTITY -> R.string.restore_check_identity
        RestoreIntegrityCheck.BASE_CURRENCY -> R.string.restore_check_currency
    },
)

@Composable
private fun RestoreConflictField.label(): String = stringResource(R.string.restore_conflict_field_content)

@Composable
private fun conflictVersionLabel(fixtureValue: String, generation: Long?): String = when {
    generation != null -> stringResource(
        R.string.restore_conflict_version_generation,
        NumberFormat.getIntegerInstance(LocalContext.current.resources.configuration.locales[0]).format(generation),
    )
    fixtureValue.isNotBlank() -> fixtureValue
    else -> stringResource(R.string.restore_conflict_version_absent)
}

@Composable
private fun MergeConflictKind.label(): String = stringResource(
    when (this) {
        MergeConflictKind.BOTH_MODIFIED -> R.string.restore_conflict_both_modified
        MergeConflictKind.DELETE_VERSUS_EDIT -> R.string.restore_conflict_delete_edit
        MergeConflictKind.TRANSACTION_REVISION_FORK -> R.string.restore_conflict_revision_fork
        MergeConflictKind.PURGED_ENTITY -> R.string.restore_conflict_purged
    },
)

@Composable
private fun app.ledger.transfer.domain.BackupRepositoryKind.label(): String = stringResource(
    when (this) {
        app.ledger.transfer.domain.BackupRepositoryKind.APP_PRIVATE -> R.string.restore_repository_app
        app.ledger.transfer.domain.BackupRepositoryKind.USER_SELECTED_DIRECTORY -> R.string.restore_repository_directory
        app.ledger.transfer.domain.BackupRepositoryKind.GOOGLE_DRIVE -> R.string.restore_repository_drive
    },
)
