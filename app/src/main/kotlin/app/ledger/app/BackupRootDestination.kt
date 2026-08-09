@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package app.ledger.app

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ledger.core.navigation.LedgerRouteContract
import app.ledger.core.navigation.ScreenId
import app.ledger.core.navigation.SessionGateState
import app.ledger.feature.transfer.BackupFlowActions
import app.ledger.feature.transfer.BackupFlowScreen

@Composable
internal fun BackupRootDestination(
    screenId: String,
    viewModel: AppRootViewModel,
    onNavigationChanged: () -> Unit,
) {
    val state by viewModel.backupFlow.collectAsStateWithLifecycle()
    val driveAuthorization = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.completeBackupDriveAuthorization(result.data.takeIf { result.resultCode == Activity.RESULT_OK })
    }
    BackupFlowScreen(
        state.copy(screenId = screenId),
        BackupFlowActions(
            onBack = viewModel::requestRootBack,
            onNavigate = { target ->
                viewModel.navigateBackup(target)
                onNavigationChanged()
            },
            onRepositoryKindSelected = viewModel::selectBackupRepository,
            onDirectorySelected = viewModel::selectBackupDirectory,
            onAuthorizeDrive = {
                viewModel.requestBackupDriveAuthorization { pendingIntent ->
                    driveAuthorization.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                }
            },
            onDisconnectDrive = viewModel::disconnectBackupDrive,
            onRecoveryPasswordChanged = viewModel::changeBackupRecoveryPassword,
            onRecoveryPasswordConfirmationChanged = viewModel::changeBackupRecoveryConfirmation,
            onRecoveryPasswordChangeModeChanged = viewModel::changeBackupRecoveryMode,
            onSaveRecoveryPassword = viewModel::saveBackupRecoveryPassword,
            onAutomaticBackupChanged = viewModel::changeAutomaticBackup,
            onRetentionCountChanged = viewModel::changeBackupRetentionCount,
            onRetentionDaysChanged = viewModel::changeBackupRetentionDays,
            onIncludeVaultChanged = viewModel::changeBackupIncludeVault,
            onNetworkPolicyChanged = viewModel::changeBackupNetworkPolicy,
            onSaveSettings = viewModel::saveBackupSettings,
            onSnapshotSelected = { snapshot -> if (viewModel.selectBackupSnapshot(snapshot)) onNavigationChanged() },
            onPortableChanged = viewModel::changePortableBackup,
            onPortableFileNameChanged = viewModel::changePortableBackupName,
            onStartBackup = viewModel::startBackup,
            onCancel = viewModel::cancelBackup,
            onRetry = viewModel::retryBackup,
            onOperations = {
                viewModel.navigator.navigate(LedgerRouteContract.destination(ScreenId("G-007")), SessionGateState.READY)
                onNavigationChanged()
            },
        ),
    )
}
