@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package app.ledger.app

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ledger.feature.transfer.RestoreFlowScreen
import app.ledger.feature.transfer.RestoreFlowScreenAction

@Composable
internal fun RestoreRootDestination(
    screenId: String,
    viewModel: AppRootViewModel,
    onNavigationChanged: () -> Unit,
) {
    val state by viewModel.restoreFlow.collectAsStateWithLifecycle()
    val driveAuthorization = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.completeRestoreDriveAuthorization(
            result.data.takeIf { result.resultCode == Activity.RESULT_OK },
            onNavigationChanged,
        )
    }
    RestoreFlowScreen(
        state.copy(screenId = screenId),
        { action ->
            when (action) {
                RestoreFlowScreenAction.Back -> viewModel.requestRootBack()
                is RestoreFlowScreenAction.PortableSource -> if (viewModel.selectRestorePortable(action.uri)) onNavigationChanged()
                RestoreFlowScreenAction.RepositorySource -> {
                    if (viewModel.selectLatestRestoreRepository()) onNavigationChanged()
                }
                RestoreFlowScreenAction.DriveSource -> {
                    viewModel.requestRestoreDriveAuthorization(
                        launchResolution = { pendingIntent ->
                            driveAuthorization.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                        },
                        onSelected = onNavigationChanged,
                    )
                }
                is RestoreFlowScreenAction.PasswordChanged -> viewModel.changeRestorePassword(action.value)
                RestoreFlowScreenAction.VerifyPassword -> {
                    viewModel.verifyRestorePassword()
                    onNavigationChanged()
                }
                is RestoreFlowScreenAction.ModeSelected -> viewModel.selectRestoreMode(action.mode)
                is RestoreFlowScreenAction.HighRiskPhraseChanged -> viewModel.changeRestoreHighRiskPhrase(action.value)
                RestoreFlowScreenAction.StartRestore -> {
                    viewModel.startRestore()
                    onNavigationChanged()
                }
                is RestoreFlowScreenAction.ResolveConflict -> viewModel.resolveRestoreConflict(action.conflictId, action.resolution)
                RestoreFlowScreenAction.ApplyMerge -> {
                    viewModel.applyRestoreMerge()
                    onNavigationChanged()
                }
                RestoreFlowScreenAction.Cancel -> viewModel.cancelRestore()
                RestoreFlowScreenAction.Retry -> viewModel.retryRestore()
                RestoreFlowScreenAction.OpenApp -> viewModel.finishRestoreFlow()
                RestoreFlowScreenAction.ConfirmSafetySnapshotCleanup -> viewModel.confirmRestoreSafetySnapshotCleanup()
                is RestoreFlowScreenAction.CloudSnapshotSelected -> viewModel.selectCloudBackupForDeletion(action.snapshotId)
                is RestoreFlowScreenAction.CloudConfirmationChanged -> viewModel.changeCloudBackupDeletePhrase(action.value)
                RestoreFlowScreenAction.AuthenticateCloudDelete -> viewModel.loadCloudBackupsForDeletion()
                RestoreFlowScreenAction.DeleteCloudBackups -> viewModel.deleteSelectedCloudBackups()
            }
        },
    )
}
