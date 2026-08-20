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
import app.ledger.feature.transfer.BackupFlowScreen
import app.ledger.feature.transfer.BackupFlowScreenAction

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
        { action ->
            when (action) {
                BackupFlowScreenAction.Back -> viewModel.requestRootBack()
                is BackupFlowScreenAction.Navigate -> {
                    viewModel.navigateBackup(action.screenId)
                    onNavigationChanged()
                }
                is BackupFlowScreenAction.RepositoryKindSelected -> viewModel.selectBackupRepository(action.kind)
                is BackupFlowScreenAction.DirectorySelected -> viewModel.selectBackupDirectory(action.uri)
                BackupFlowScreenAction.AuthorizeDrive -> {
                    viewModel.requestBackupDriveAuthorization { pendingIntent ->
                        driveAuthorization.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                    }
                }
                BackupFlowScreenAction.DisconnectDrive -> viewModel.disconnectBackupDrive()
                is BackupFlowScreenAction.RecoveryPasswordChanged -> viewModel.changeBackupRecoveryPassword(action.value)
                is BackupFlowScreenAction.RecoveryPasswordConfirmationChanged -> viewModel.changeBackupRecoveryConfirmation(action.value)
                is BackupFlowScreenAction.RecoveryPasswordChangeModeChanged -> viewModel.changeBackupRecoveryMode(action.mode)
                BackupFlowScreenAction.SaveRecoveryPassword -> viewModel.saveBackupRecoveryPassword()
                is BackupFlowScreenAction.AutomaticBackupChanged -> viewModel.changeAutomaticBackup(action.enabled)
                is BackupFlowScreenAction.RetentionCountChanged -> viewModel.changeBackupRetentionCount(action.value)
                is BackupFlowScreenAction.RetentionDaysChanged -> viewModel.changeBackupRetentionDays(action.value)
                is BackupFlowScreenAction.IncludeVaultChanged -> viewModel.changeBackupIncludeVault(action.enabled)
                is BackupFlowScreenAction.NetworkPolicyChanged -> viewModel.changeBackupNetworkPolicy(action.policy)
                BackupFlowScreenAction.SaveSettings -> viewModel.saveBackupSettings()
                is BackupFlowScreenAction.SnapshotSelected -> if (viewModel.selectBackupSnapshot(action.snapshotId)) onNavigationChanged()
                is BackupFlowScreenAction.PortableChanged -> viewModel.changePortableBackup(action.portable)
                is BackupFlowScreenAction.PortableFileNameChanged -> viewModel.changePortableBackupName(action.value)
                is BackupFlowScreenAction.StartBackup -> viewModel.startBackup(action.destination)
                BackupFlowScreenAction.Cancel -> viewModel.cancelBackup()
                BackupFlowScreenAction.Retry -> viewModel.retryBackup()
                BackupFlowScreenAction.Operations -> {
                    viewModel.navigator.navigate(LedgerRouteContract.destination(ScreenId("G-007")), SessionGateState.READY)
                    onNavigationChanged()
                }
                is BackupFlowScreenAction.RestoreSnapshot -> {
                    viewModel.openRestoreSnapshot(action.snapshotId)
                    onNavigationChanged()
                }
            }
        },
    )
}
