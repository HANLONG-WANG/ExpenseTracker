@file:Suppress(
    "FunctionNaming",
    "LongMethod",
    "LongParameterList",
    "MagicNumber",
    "MaxLineLength",
    "TooManyFunctions",
    "ktlint:standard:function-naming",
)

package app.ledger.feature.transfer

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
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
import app.ledger.core.designsystem.LedgerToggleRow
import app.ledger.core.designsystem.LedgerTopAppBar
import app.ledger.core.designsystem.LedgerTopAppBarVariant
import app.ledger.core.designsystem.OperationCapability
import app.ledger.core.designsystem.OperationProgressPanel
import app.ledger.core.designsystem.OperationProgressUiModel
import app.ledger.transfer.domain.BackupNetworkPolicy
import app.ledger.transfer.domain.BackupPhase
import app.ledger.transfer.domain.BackupRepositoryKind
import app.ledger.transfer.domain.RecoveryPasswordChangeMode
import java.text.NumberFormat

enum class BackupHomePresentation { CONFIGURED, NOT_CONFIGURED, RUNNING, FAILED, PERMISSION_REVOKED }
enum class DriveAuthorizationPresentation { DISCONNECTED, AUTHORIZING, CONNECTED, FAILED }
enum class BackupExecutionPresentation { READY, RUNNING, CANCEL_REQUESTED, FAILED, SUCCEEDED }
enum class BackupIntegrityPresentation { VERIFIED, UNVERIFIED, CORRUPT, REMOTE_UNAVAILABLE }
enum class BackupRepositoryFilter { ALL, APP_PRIVATE, USER_DIRECTORY, GOOGLE_DRIVE }

data class BackupSnapshotUi(
    val snapshotId: String,
    val createdAt: String,
    val logicalContent: String,
    val physicalIncrement: String,
    val repositoryKind: BackupRepositoryKind,
    val locationDetail: String,
    val integrity: BackupIntegrityPresentation,
    val includesVault: Boolean,
    val localRevision: Long? = null,
    val logicalBytes: Long? = null,
    val objectCount: Int? = null,
    val physicalIncrementBytes: Long? = null,
)

data class BackupFlowUiState(
    val screenId: String = "BKP-001",
    val homePresentation: BackupHomePresentation = BackupHomePresentation.NOT_CONFIGURED,
    val repositoryKind: BackupRepositoryKind = BackupRepositoryKind.APP_PRIVATE,
    val repositoryLabel: String = "",
    val directoryPermissionGranted: Boolean = false,
    val driveAuthorization: DriveAuthorizationPresentation = DriveAuthorizationPresentation.DISCONNECTED,
    val recoveryPasswordConfigured: Boolean = false,
    val vaultBackupReady: Boolean = false,
    val recoveryPassword: String = "",
    val recoveryPasswordConfirmation: String = "",
    val recoveryPasswordError: Boolean = false,
    val recoveryPasswordChangeMode: RecoveryPasswordChangeMode = RecoveryPasswordChangeMode.FUTURE_BACKUPS_ONLY,
    val automaticBackup: Boolean = true,
    val retentionCount: String = "30",
    val retentionDays: String = "",
    val includeVault: Boolean = false,
    val networkPolicy: BackupNetworkPolicy = BackupNetworkPolicy.ANY,
    val snapshots: List<BackupSnapshotUi> = emptyList(),
    val selectedSnapshot: BackupSnapshotUi? = null,
    val loadingRemote: Boolean = false,
    val portable: Boolean = false,
    val portableFileName: String = "ledger.ledger-backup",
    val phase: BackupPhase = BackupPhase.DATABASE_SNAPSHOT,
    val completedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val execution: BackupExecutionPresentation = BackupExecutionPresentation.READY,
    val failureCode: String? = null,
    val temporaryCleanupComplete: Boolean = false,
    val estimatedBytes: Long? = null,
    val createdSnapshotId: String? = null,
)

sealed interface BackupFlowScreenAction {
    data object Back : BackupFlowScreenAction
    data class Navigate(val screenId: String) : BackupFlowScreenAction
    data class RepositoryKindSelected(val kind: BackupRepositoryKind) : BackupFlowScreenAction
    data class DirectorySelected(val uri: Uri) : BackupFlowScreenAction
    data object AuthorizeDrive : BackupFlowScreenAction
    data object DisconnectDrive : BackupFlowScreenAction
    data class RecoveryPasswordChanged(val value: String) : BackupFlowScreenAction
    data class RecoveryPasswordConfirmationChanged(val value: String) : BackupFlowScreenAction
    data class RecoveryPasswordChangeModeChanged(val mode: RecoveryPasswordChangeMode) : BackupFlowScreenAction
    data object SaveRecoveryPassword : BackupFlowScreenAction
    data class AutomaticBackupChanged(val enabled: Boolean) : BackupFlowScreenAction
    data class RetentionCountChanged(val value: String) : BackupFlowScreenAction
    data class RetentionDaysChanged(val value: String) : BackupFlowScreenAction
    data class IncludeVaultChanged(val enabled: Boolean) : BackupFlowScreenAction
    data class NetworkPolicyChanged(val policy: BackupNetworkPolicy) : BackupFlowScreenAction
    data object SaveSettings : BackupFlowScreenAction
    data class SnapshotSelected(val snapshotId: String) : BackupFlowScreenAction
    data class PortableChanged(val portable: Boolean) : BackupFlowScreenAction
    data class PortableFileNameChanged(val value: String) : BackupFlowScreenAction
    data class StartBackup(val destination: Uri?) : BackupFlowScreenAction
    data object Cancel : BackupFlowScreenAction
    data object Retry : BackupFlowScreenAction
    data object Operations : BackupFlowScreenAction
    data class RestoreSnapshot(val snapshotId: String) : BackupFlowScreenAction
}

private class BackupFlowActions(
    val onBack: () -> Unit,
    val onNavigate: (String) -> Unit,
    val onRepositoryKindSelected: (BackupRepositoryKind) -> Unit,
    val onDirectorySelected: (Uri) -> Unit,
    val onAuthorizeDrive: () -> Unit,
    val onDisconnectDrive: () -> Unit,
    val onRecoveryPasswordChanged: (String) -> Unit,
    val onRecoveryPasswordConfirmationChanged: (String) -> Unit,
    val onRecoveryPasswordChangeModeChanged: (RecoveryPasswordChangeMode) -> Unit,
    val onSaveRecoveryPassword: () -> Unit,
    val onAutomaticBackupChanged: (Boolean) -> Unit,
    val onRetentionCountChanged: (String) -> Unit,
    val onRetentionDaysChanged: (String) -> Unit,
    val onIncludeVaultChanged: (Boolean) -> Unit,
    val onNetworkPolicyChanged: (BackupNetworkPolicy) -> Unit,
    val onSaveSettings: () -> Unit,
    val onSnapshotSelected: (String) -> Unit,
    val onPortableChanged: (Boolean) -> Unit,
    val onPortableFileNameChanged: (String) -> Unit,
    val onStartBackup: (Uri?) -> Unit,
    val onCancel: () -> Unit,
    val onRetry: () -> Unit,
    val onOperations: () -> Unit,
    val onRestoreSnapshot: (String) -> Unit = {},
    val onDeleteSnapshot: (String) -> Unit = {},
)

@Composable
fun BackupFlowScreen(state: BackupFlowUiState, onAction: (BackupFlowScreenAction) -> Unit) {
    val actions = BackupFlowActions(
        onBack = { onAction(BackupFlowScreenAction.Back) },
        onNavigate = { onAction(BackupFlowScreenAction.Navigate(it)) },
        onRepositoryKindSelected = { onAction(BackupFlowScreenAction.RepositoryKindSelected(it)) },
        onDirectorySelected = { onAction(BackupFlowScreenAction.DirectorySelected(it)) },
        onAuthorizeDrive = { onAction(BackupFlowScreenAction.AuthorizeDrive) },
        onDisconnectDrive = { onAction(BackupFlowScreenAction.DisconnectDrive) },
        onRecoveryPasswordChanged = { onAction(BackupFlowScreenAction.RecoveryPasswordChanged(it)) },
        onRecoveryPasswordConfirmationChanged = { onAction(BackupFlowScreenAction.RecoveryPasswordConfirmationChanged(it)) },
        onRecoveryPasswordChangeModeChanged = { onAction(BackupFlowScreenAction.RecoveryPasswordChangeModeChanged(it)) },
        onSaveRecoveryPassword = { onAction(BackupFlowScreenAction.SaveRecoveryPassword) },
        onAutomaticBackupChanged = { onAction(BackupFlowScreenAction.AutomaticBackupChanged(it)) },
        onRetentionCountChanged = { onAction(BackupFlowScreenAction.RetentionCountChanged(it)) },
        onRetentionDaysChanged = { onAction(BackupFlowScreenAction.RetentionDaysChanged(it)) },
        onIncludeVaultChanged = { onAction(BackupFlowScreenAction.IncludeVaultChanged(it)) },
        onNetworkPolicyChanged = { onAction(BackupFlowScreenAction.NetworkPolicyChanged(it)) },
        onSaveSettings = { onAction(BackupFlowScreenAction.SaveSettings) },
        onSnapshotSelected = { onAction(BackupFlowScreenAction.SnapshotSelected(it)) },
        onPortableChanged = { onAction(BackupFlowScreenAction.PortableChanged(it)) },
        onPortableFileNameChanged = { onAction(BackupFlowScreenAction.PortableFileNameChanged(it)) },
        onStartBackup = { onAction(BackupFlowScreenAction.StartBackup(it)) },
        onCancel = { onAction(BackupFlowScreenAction.Cancel) },
        onRetry = { onAction(BackupFlowScreenAction.Retry) },
        onOperations = { onAction(BackupFlowScreenAction.Operations) },
        onRestoreSnapshot = { onAction(BackupFlowScreenAction.RestoreSnapshot(it)) },
    )
    LedgerScaffold(
        state.rootModifier(),
        topBar = { LedgerTopAppBar(state.title(), LedgerTopAppBarVariant.BACK, onNavigation = actions.onBack) },
        fixedAction = if (state.screenId == "BKP-004") {
            {
                LedgerButton(
                    stringResource(R.string.backup_save_settings),
                    actions.onSaveSettings,
                    enabled = state.retentionCount.toIntOrNull()?.let { it in 1..3650 } == true &&
                        (state.retentionDays.isBlank() || state.retentionDays.toIntOrNull()?.let { it in 1..36500 } == true),
                )
            }
        } else {
            null
        },
    ) { padding ->
        when (state.screenId) {
            "BKP-001" -> BackupHome(state, actions, Modifier.padding(padding))
            "BKP-002" -> BackupRepository(state, actions, Modifier.padding(padding))
            "BKP-003" -> RecoveryPassword(state, actions, Modifier.padding(padding))
            "BKP-004" -> BackupSettings(state, actions, Modifier.padding(padding))
            "BKP-005" -> BackupHistory(state, actions, Modifier.padding(padding))
            "BKP-006" -> BackupDetails(state, actions, Modifier.padding(padding))
            "BKP-007" -> ManualBackup(state, actions, Modifier.padding(padding))
            "SYS-003" -> DriveAuthorization(state, actions, Modifier.padding(padding))
            else -> error("unknown backup screen")
        }
    }
}

@Composable
private fun BackupHome(state: BackupFlowUiState, actions: BackupFlowActions, modifier: Modifier) {
    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        item {
            when (state.homePresentation) {
                BackupHomePresentation.NOT_CONFIGURED -> LedgerBanner(stringResource(R.string.backup_not_configured), LedgerBannerVariant.WARNING)
                BackupHomePresentation.RUNNING -> LedgerBanner(stringResource(R.string.backup_running), LedgerBannerVariant.INFO)
                BackupHomePresentation.FAILED -> LedgerBanner(backupFailureMessage(state.failureCode), LedgerBannerVariant.DANGER)
                BackupHomePresentation.PERMISSION_REVOKED -> LedgerBanner(stringResource(R.string.backup_permission_revoked), LedgerBannerVariant.DANGER)
                BackupHomePresentation.CONFIGURED -> LedgerBanner(stringResource(R.string.backup_configured), LedgerBannerVariant.INFO)
            }
        }
        item { SummaryCard(R.string.backup_repository, state.repositoryDisplayLabel()) }
        item { SummaryCard(R.string.backup_policy, stringResource(R.string.backup_policy_summary, state.retentionCount)) }
        item { SummaryCard(R.string.backup_password_status, if (state.recoveryPasswordConfigured) stringResource(R.string.backup_password_set) else stringResource(R.string.backup_password_not_set)) }
        item { SummaryCard(R.string.backup_vault_policy, if (state.includeVault) stringResource(R.string.backup_vault_included) else stringResource(R.string.backup_vault_excluded)) }
        item { LedgerText(stringResource(R.string.backup_recent_list), LedgerTextRole.SECTION) }
        state.snapshots.take(3).forEach { snapshot ->
            item {
                LedgerCard(Modifier.fillMaxWidth(), onClick = { actions.onSnapshotSelected(snapshot.snapshotId) }) {
                    Column(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm)) {
                        LedgerText(snapshot.createdAt, LedgerTextRole.BODY)
                        LedgerText(snapshot.integrity.label(), LedgerTextRole.SUPPORTING)
                    }
                }
            }
        }
        if (state.snapshots.isEmpty()) item { LedgerText(stringResource(R.string.backup_recent_none), LedgerTextRole.SUPPORTING) }
        if (state.failureCode != null) item { SummaryCard(R.string.backup_recent_failure, backupFailureMessage(state.failureCode)) }
        item {
            SummaryCard(
                R.string.backup_next_schedule,
                stringResource(if (state.automaticBackup) R.string.backup_next_automatic else R.string.backup_next_disabled),
            )
        }
        item { LedgerButton(stringResource(R.string.backup_now), { actions.onNavigate("BKP-007") }, Modifier.fillMaxWidth(), enabled = state.recoveryPasswordConfigured) }
        item { LedgerButton(stringResource(R.string.backup_location), { actions.onNavigate("BKP-002") }, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY) }
        item { LedgerButton(stringResource(R.string.backup_recovery_password), { actions.onNavigate("BKP-003") }, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY) }
        item { LedgerButton(stringResource(R.string.backup_settings), { actions.onNavigate("BKP-004") }, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY) }
        item { LedgerButton(stringResource(R.string.backup_history), { actions.onNavigate("BKP-005") }, Modifier.fillMaxWidth(), LedgerButtonVariant.TEXT) }
    }
}

@Composable
private fun BackupRepository(state: BackupFlowUiState, actions: BackupFlowActions, modifier: Modifier) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(actions.onDirectorySelected) }
    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        item { LedgerText(stringResource(R.string.backup_location_supporting), LedgerTextRole.SUPPORTING) }
        item { RepositoryChoice(BackupRepositoryKind.APP_PRIVATE, R.string.backup_app_private, R.string.backup_app_private_supporting, state, actions) }
        item { RepositoryChoice(BackupRepositoryKind.USER_SELECTED_DIRECTORY, R.string.backup_saf_directory, R.string.backup_saf_supporting, state, actions) }
        item { RepositoryChoice(BackupRepositoryKind.GOOGLE_DRIVE, R.string.backup_drive, R.string.backup_drive_supporting, state, actions) }
        if (state.repositoryKind == BackupRepositoryKind.USER_SELECTED_DIRECTORY) {
            item { LedgerButton(stringResource(R.string.backup_choose_directory), { picker.launch(null) }, Modifier.fillMaxWidth()) }
            if (!state.directoryPermissionGranted) item { LedgerBanner(stringResource(R.string.backup_permission_revoked), LedgerBannerVariant.WARNING) }
        }
        if (state.repositoryKind == BackupRepositoryKind.GOOGLE_DRIVE) {
            item { LedgerButton(stringResource(R.string.backup_drive_authorization), { actions.onNavigate("SYS-003") }, Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun RepositoryChoice(
    kind: BackupRepositoryKind,
    title: Int,
    supporting: Int,
    state: BackupFlowUiState,
    actions: BackupFlowActions,
) {
    LedgerChoiceRow(stringResource(title), state.repositoryKind == kind, { actions.onRepositoryKindSelected(kind) }, supportingText = stringResource(supporting))
}

@Composable
private fun RecoveryPassword(state: BackupFlowUiState, actions: BackupFlowActions, modifier: Modifier) {
    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        item { LedgerBanner(stringResource(R.string.backup_password_unrecoverable), LedgerBannerVariant.DANGER) }
        item { LedgerText(stringResource(R.string.backup_password_local_rules), LedgerTextRole.SUPPORTING) }
        item {
            LedgerTextField(
                state.recoveryPassword,
                actions.onRecoveryPasswordChanged,
                stringResource(R.string.backup_password),
                required = true,
                sensitive = true,
                hideValueFromSemantics = true,
                errorText = stringResource(R.string.backup_password_invalid).takeIf { state.recoveryPasswordError },
            )
        }
        item {
            LedgerTextField(
                state.recoveryPasswordConfirmation,
                actions.onRecoveryPasswordConfirmationChanged,
                stringResource(R.string.backup_password_confirm),
                required = true,
                sensitive = true,
                hideValueFromSemantics = true,
            )
        }
        if (state.recoveryPasswordConfigured) {
            item { LedgerText(stringResource(R.string.backup_password_change_scope), LedgerTextRole.SECTION) }
            item { LedgerChoiceRow(stringResource(R.string.backup_future_only), state.recoveryPasswordChangeMode == RecoveryPasswordChangeMode.FUTURE_BACKUPS_ONLY, { actions.onRecoveryPasswordChangeModeChanged(RecoveryPasswordChangeMode.FUTURE_BACKUPS_ONLY) }) }
            item { LedgerChoiceRow(stringResource(R.string.backup_reencrypt_history), state.recoveryPasswordChangeMode == RecoveryPasswordChangeMode.RE_ENCRYPT_ACCESSIBLE_HISTORY, { actions.onRecoveryPasswordChangeModeChanged(RecoveryPasswordChangeMode.RE_ENCRYPT_ACCESSIBLE_HISTORY) }) }
        }
        if (state.execution in setOf(BackupExecutionPresentation.RUNNING, BackupExecutionPresentation.CANCEL_REQUESTED)) {
            item {
                OperationProgressPanel(
                    OperationProgressUiModel(
                        stringResource(R.string.backup_reencrypt_history),
                        state.phase.label(),
                        stringResource(R.string.backup_progress_bytes, state.completedBytes),
                        state.totalBytes?.takeIf { it > 0L }?.let { state.completedBytes.toFloat() / it.toFloat() },
                        OperationCapability.CANCELABLE,
                        stringResource(R.string.backup_safe_cancel),
                    ),
                    onCancel = actions.onCancel,
                )
            }
        }
        item {
            LedgerButton(
                stringResource(R.string.backup_password_save),
                actions.onSaveRecoveryPassword,
                Modifier.fillMaxWidth(),
                enabled = state.execution !in setOf(BackupExecutionPresentation.RUNNING, BackupExecutionPresentation.CANCEL_REQUESTED),
            )
        }
    }
}

@Composable
private fun BackupSettings(state: BackupFlowUiState, actions: BackupFlowActions, modifier: Modifier) {
    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        item { LedgerToggleRow(stringResource(R.string.backup_automatic), state.automaticBackup, actions.onAutomaticBackupChanged, supportingText = stringResource(R.string.backup_automatic_supporting)) }
        item { LedgerTextField(state.retentionCount, actions.onRetentionCountChanged, stringResource(R.string.backup_retention_count), keyboardType = KeyboardType.Number, required = true) }
        item { LedgerTextField(state.retentionDays, actions.onRetentionDaysChanged, stringResource(R.string.backup_retention_days), keyboardType = KeyboardType.Number) }
        item {
            LedgerToggleRow(
                stringResource(R.string.backup_include_vault),
                state.includeVault,
                actions.onIncludeVaultChanged,
                supportingText = state.vaultSupportingText(),
                enabled = state.recoveryPasswordConfigured && state.vaultBackupReady,
            )
        }
        if (!state.recoveryPasswordConfigured || !state.vaultBackupReady) {
            item {
                LedgerBanner(
                    stringResource(R.string.backup_vault_setup_required),
                    LedgerBannerVariant.WARNING,
                    actionLabel = stringResource(R.string.backup_open_password_setup),
                    onAction = { actions.onNavigate("BKP-003") },
                )
            }
        }
        item { LedgerText(stringResource(R.string.backup_network_policy), LedgerTextRole.SECTION) }
        item { LedgerChoiceRow(stringResource(R.string.backup_network_any), state.networkPolicy == BackupNetworkPolicy.ANY, { actions.onNetworkPolicyChanged(BackupNetworkPolicy.ANY) }) }
        item { LedgerChoiceRow(stringResource(R.string.backup_network_unmetered), state.networkPolicy == BackupNetworkPolicy.UNMETERED, { actions.onNetworkPolicyChanged(BackupNetworkPolicy.UNMETERED) }) }
    }
}

@Composable
private fun BackupHistory(state: BackupFlowUiState, actions: BackupFlowActions, modifier: Modifier) {
    var filter by remember { mutableStateOf(BackupRepositoryFilter.ALL) }
    val filteredSnapshots = state.snapshots.filter { snapshot ->
        when (filter) {
            BackupRepositoryFilter.ALL -> true
            BackupRepositoryFilter.APP_PRIVATE -> snapshot.repositoryKind == BackupRepositoryKind.APP_PRIVATE
            BackupRepositoryFilter.USER_DIRECTORY -> snapshot.repositoryKind == BackupRepositoryKind.USER_SELECTED_DIRECTORY
            BackupRepositoryFilter.GOOGLE_DRIVE -> snapshot.repositoryKind == BackupRepositoryKind.GOOGLE_DRIVE
        }
    }
    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        item { LedgerText(stringResource(R.string.backup_repository_filter), LedgerTextRole.SECTION) }
        items(BackupRepositoryFilter.entries) { value ->
            LedgerChoiceRow(value.label(), filter == value, { filter = value })
        }
        if (state.loadingRemote) item { LedgerBanner(stringResource(R.string.backup_loading_remote), LedgerBannerVariant.INFO) }
        if (filteredSnapshots.isEmpty()) item { LedgerBanner(stringResource(R.string.backup_history_empty), LedgerBannerVariant.INFO) }
        items(filteredSnapshots, key = BackupSnapshotUi::snapshotId) { snapshot ->
            LedgerCard(Modifier.fillMaxWidth(), onClick = { actions.onSnapshotSelected(snapshot.snapshotId) }) {
                Column(Modifier.padding(LedgerTheme.spacing.sm)) {
                    LedgerText(snapshot.createdAt, LedgerTextRole.SECTION)
                    LedgerText(snapshot.logicalContentLabel(), LedgerTextRole.BODY)
                    LedgerText(stringResource(R.string.backup_snapshot_increment, snapshot.physicalIncrementLabel()), LedgerTextRole.SUPPORTING)
                    LedgerText(snapshot.integrity.label(), LedgerTextRole.SUPPORTING)
                    LedgerText(repositoryDisplayLabel(snapshot.repositoryKind, snapshot.locationDetail), LedgerTextRole.SUPPORTING)
                    LedgerText(
                        stringResource(if (snapshot.includesVault) R.string.backup_vault_included else R.string.backup_vault_excluded),
                        LedgerTextRole.SUPPORTING,
                    )
                }
            }
        }
    }
}

@Composable
private fun BackupDetails(state: BackupFlowUiState, actions: BackupFlowActions, modifier: Modifier) {
    val snapshot = state.selectedSnapshot
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        if (snapshot == null) {
            LedgerBanner(stringResource(R.string.backup_snapshot_unavailable), LedgerBannerVariant.DANGER)
            return@Column
        }
        LedgerText(snapshot.createdAt, LedgerTextRole.TITLE)
        LedgerBanner(snapshot.integrity.label(), if (snapshot.integrity == BackupIntegrityPresentation.VERIFIED) LedgerBannerVariant.INFO else LedgerBannerVariant.DANGER)
        SummaryCard(R.string.backup_contents, snapshot.logicalContentLabel())
        SummaryCard(R.string.backup_snapshot_increment_label, snapshot.physicalIncrementLabel())
        SummaryCard(R.string.backup_repository, repositoryDisplayLabel(snapshot.repositoryKind, snapshot.locationDetail))
        SummaryCard(R.string.backup_vault_policy, if (snapshot.includesVault) stringResource(R.string.backup_vault_included) else stringResource(R.string.backup_vault_excluded))
        LedgerButton(
            stringResource(R.string.backup_restore_action),
            { actions.onRestoreSnapshot(snapshot.snapshotId) },
            Modifier.fillMaxWidth(),
            enabled = snapshot.integrity == BackupIntegrityPresentation.VERIFIED,
        )
        LedgerButton(
            stringResource(R.string.backup_delete_snapshot),
            { actions.onDeleteSnapshot(snapshot.snapshotId) },
            Modifier.fillMaxWidth(),
            LedgerButtonVariant.DANGER,
        )
    }
}

@Composable
private fun ManualBackup(state: BackupFlowUiState, actions: BackupFlowActions, modifier: Modifier) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) actions.onStartBackup(uri)
    }
    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        item { SummaryCard(R.string.backup_scope, stringResource(R.string.backup_scope_full)) }
        item { SummaryCard(R.string.backup_repository, state.repositoryDisplayLabel()) }
        item {
            SummaryCard(
                R.string.backup_estimated_space,
                state.estimatedBytes?.let { formatEstimatedBytes(it) }
                    ?: stringResource(R.string.backup_estimated_space_unknown),
            )
        }
        item { LedgerToggleRow(stringResource(R.string.backup_portable), state.portable, actions.onPortableChanged, supportingText = stringResource(R.string.backup_portable_supporting)) }
        if (state.portable) item { LedgerTextField(state.portableFileName, actions.onPortableFileNameChanged, stringResource(R.string.backup_file_name), required = true) }
        item {
            LedgerToggleRow(
                stringResource(R.string.backup_include_vault),
                state.includeVault,
                actions.onIncludeVaultChanged,
                enabled = state.recoveryPasswordConfigured && state.vaultBackupReady,
                supportingText = state.vaultSupportingText(),
            )
        }
        if (!state.recoveryPasswordConfigured) item { LedgerBanner(stringResource(R.string.backup_manual_password_required), LedgerBannerVariant.WARNING) }
        item {
            when (state.execution) {
                BackupExecutionPresentation.READY -> LedgerButton(
                    stringResource(R.string.backup_start),
                    { if (state.portable) picker.launch(null) else actions.onStartBackup(null) },
                    Modifier.fillMaxWidth(),
                    enabled = state.recoveryPasswordConfigured,
                )
                BackupExecutionPresentation.RUNNING, BackupExecutionPresentation.CANCEL_REQUESTED -> OperationProgressPanel(
                    OperationProgressUiModel(
                        stringResource(R.string.backup_operation_name),
                        state.phase.label(),
                        stringResource(R.string.backup_progress_bytes, state.completedBytes),
                        state.totalBytes?.takeIf { it > 0 }?.let { state.completedBytes.toFloat() / it.toFloat() },
                        OperationCapability.CANCELABLE,
                        stringResource(R.string.backup_safe_cancel),
                    ),
                    onCancel = actions.onCancel,
                )
                BackupExecutionPresentation.FAILED -> Column(verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
                    LedgerBanner(backupFailureMessage(state.failureCode), LedgerBannerVariant.DANGER)
                    if (state.temporaryCleanupComplete) LedgerText(stringResource(R.string.backup_temporary_cleaned), LedgerTextRole.SUPPORTING)
                    LedgerButton(stringResource(R.string.backup_retry), actions.onRetry, Modifier.fillMaxWidth())
                }
                BackupExecutionPresentation.SUCCEEDED -> Column(verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
                    LedgerBanner(stringResource(R.string.backup_succeeded), LedgerBannerVariant.INFO)
                    LedgerButton(
                        stringResource(R.string.backup_view_created),
                        { state.createdSnapshotId?.let(actions.onSnapshotSelected) },
                        Modifier.fillMaxWidth(),
                        LedgerButtonVariant.SECONDARY,
                        enabled = state.createdSnapshotId != null,
                    )
                }
            }
        }
        item { LedgerButton(stringResource(R.string.backup_operations), actions.onOperations, Modifier.fillMaxWidth(), LedgerButtonVariant.TEXT) }
    }
}

@Composable
private fun DriveAuthorization(state: BackupFlowUiState, actions: BackupFlowActions, modifier: Modifier) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        LedgerBanner(stringResource(R.string.backup_drive_scope), LedgerBannerVariant.INFO)
        LedgerText(state.driveAuthorization.label(), LedgerTextRole.SECTION)
        if (state.driveAuthorization == DriveAuthorizationPresentation.CONNECTED) {
            LedgerButton(stringResource(R.string.backup_drive_disconnect), actions.onDisconnectDrive, Modifier.fillMaxWidth(), LedgerButtonVariant.DANGER)
        } else {
            LedgerButton(stringResource(R.string.backup_drive_authorize), actions.onAuthorizeDrive, Modifier.fillMaxWidth(), enabled = state.driveAuthorization != DriveAuthorizationPresentation.AUTHORIZING)
        }
    }
}

@Composable
private fun SummaryCard(label: Int, value: String) {
    LedgerCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(LedgerTheme.spacing.sm)) {
            LedgerText(stringResource(label), LedgerTextRole.SECTION)
            LedgerText(value, LedgerTextRole.SUPPORTING)
        }
    }
}

@Composable
private fun BackupFlowUiState.vaultSupportingText(): String = stringResource(
    when {
        !recoveryPasswordConfigured -> R.string.backup_vault_password_required
        !vaultBackupReady -> R.string.backup_vault_auth_required
        else -> R.string.backup_include_vault_supporting
    },
)

@Composable
private fun BackupFlowUiState.failureMessage(): String = stringResource(
    when (failureCode) {
        "BACKUP_RECOVERY_PASSWORD_REQUIRED", "BACKUP_INVALID_RECOVERY_PASSWORD" -> R.string.backup_failure_password
        "BACKUP_VAULT_RECOVERY_ENVELOPE_MISSING" -> R.string.backup_failure_vault
        "BACKUP_PERMISSION_REVOKED" -> R.string.backup_permission_revoked
        "BACKUP_DRIVE_AUTHORIZATION_REQUIRED" -> R.string.backup_failure_drive_auth
        "BACKUP_NETWORK_UNAVAILABLE" -> R.string.backup_failure_network
        "BACKUP_INSUFFICIENT_SPACE" -> R.string.backup_failure_space
        "BACKUP_REPOSITORY_UNAVAILABLE" -> R.string.backup_failure_repository
        "BACKUP_CANCELLED" -> R.string.backup_failure_cancelled
        else -> R.string.backup_failure_generic
    },
)

@Composable
private fun BackupFlowUiState.title(): String = stringResource(
    when (screenId) {
        "BKP-001" -> R.string.backup_title
        "BKP-002" -> R.string.backup_location
        "BKP-003" -> R.string.backup_recovery_password
        "BKP-004" -> R.string.backup_settings
        "BKP-005" -> R.string.backup_history
        "BKP-006" -> R.string.backup_details
        "BKP-007" -> R.string.backup_create
        "SYS-003" -> R.string.backup_drive_connect
        else -> R.string.backup_title
    },
)

@Composable
private fun BackupIntegrityPresentation.label(): String = stringResource(
    when (this) {
        BackupIntegrityPresentation.VERIFIED -> R.string.backup_verified
        BackupIntegrityPresentation.UNVERIFIED -> R.string.backup_unverified
        BackupIntegrityPresentation.CORRUPT -> R.string.backup_corrupt
        BackupIntegrityPresentation.REMOTE_UNAVAILABLE -> R.string.backup_remote_unavailable
    },
)

@Composable
private fun BackupRepositoryFilter.label(): String = stringResource(
    when (this) {
        BackupRepositoryFilter.ALL -> R.string.backup_filter_all
        BackupRepositoryFilter.APP_PRIVATE -> R.string.backup_app_private
        BackupRepositoryFilter.USER_DIRECTORY -> R.string.backup_saf_directory
        BackupRepositoryFilter.GOOGLE_DRIVE -> R.string.backup_drive
    },
)

@Composable
private fun backupFailureMessage(code: String?): String = stringResource(
    when (code) {
        "BACKUP_PERMISSION_REVOKED" -> R.string.backup_failure_permission
        "BACKUP_AUTHENTICATION_REQUIRED" -> R.string.backup_failure_authentication
        "BACKUP_REPOSITORY_UNAVAILABLE" -> R.string.backup_failure_repository
        else -> R.string.backup_failure_generic
    },
)

@Composable
private fun formatEstimatedBytes(bytes: Long): String = Formatter.formatFileSize(LocalContext.current, bytes)

@Composable
private fun BackupSnapshotUi.logicalContentLabel(): String {
    val context = LocalContext.current
    val count = objectCount
    val bytes = logicalBytes
    return when {
        bytes != null && count != null -> stringResource(
            R.string.backup_snapshot_content_summary,
            Formatter.formatFileSize(context, bytes),
            NumberFormat.getIntegerInstance(context.resources.configuration.locales[0]).format(count),
        )
        localRevision != null -> stringResource(
            R.string.backup_snapshot_revision,
            NumberFormat.getIntegerInstance(context.resources.configuration.locales[0]).format(localRevision),
        )
        else -> logicalContent
    }
}

@Composable
private fun BackupSnapshotUi.physicalIncrementLabel(): String = physicalIncrementBytes
    ?.let { Formatter.formatFileSize(LocalContext.current, it) }
    ?: physicalIncrement.takeIf(String::isNotBlank)
    ?: stringResource(R.string.backup_snapshot_increment_unavailable)

@Composable
private fun DriveAuthorizationPresentation.label(): String = stringResource(
    when (this) {
        DriveAuthorizationPresentation.DISCONNECTED -> R.string.backup_drive_disconnected
        DriveAuthorizationPresentation.AUTHORIZING -> R.string.backup_drive_authorizing
        DriveAuthorizationPresentation.CONNECTED -> R.string.backup_drive_connected
        DriveAuthorizationPresentation.FAILED -> R.string.backup_drive_failed
    },
)

@Composable
private fun BackupPhase.label(): String = stringResource(
    when (this) {
        BackupPhase.DATABASE_SNAPSHOT -> R.string.backup_phase_database
        BackupPhase.OBJECT_PROCESSING -> R.string.backup_phase_objects
        BackupPhase.WRITING_OR_UPLOADING -> R.string.backup_phase_writing
        BackupPhase.VERIFYING -> R.string.backup_phase_verifying
        BackupPhase.PUBLISHING_MANIFEST -> R.string.backup_phase_manifest
        BackupPhase.RETENTION -> R.string.backup_phase_retention
        BackupPhase.COMPLETE -> R.string.backup_phase_complete
    },
)

@Composable
private fun BackupFlowUiState.repositoryDisplayLabel(): String = repositoryDisplayLabel(repositoryKind, repositoryLabel)

@Composable
private fun repositoryDisplayLabel(kind: BackupRepositoryKind, detail: String): String = when (kind) {
    BackupRepositoryKind.APP_PRIVATE -> stringResource(R.string.backup_app_private)
    BackupRepositoryKind.GOOGLE_DRIVE -> stringResource(R.string.backup_drive)
    BackupRepositoryKind.USER_SELECTED_DIRECTORY -> detail.ifBlank { stringResource(R.string.backup_saf_directory) }
}

private fun BackupFlowUiState.rootModifier(): Modifier = Modifier.fillMaxSize().testTag("backup_flow_root")
