@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package app.ledger.app

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ledger.feature.transfer.RestoreFlowActions
import app.ledger.feature.transfer.RestoreFlowScreen

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
        RestoreFlowActions(
            onBack = viewModel::requestRootBack,
            onPortableSource = { uri ->
                if (viewModel.selectRestorePortable(uri)) onNavigationChanged()
            },
            onRepositorySource = {
                viewModel.selectLatestRestoreRepository()
            },
            onDriveSource = {
                viewModel.requestRestoreDriveAuthorization(
                    launchResolution = { pendingIntent ->
                        driveAuthorization.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                    },
                    onSelected = onNavigationChanged,
                )
            },
            onSnapshotSourceSelected = { snapshotId ->
                if (viewModel.selectRestoreRepositorySnapshot(snapshotId)) onNavigationChanged()
            },
            onPasswordChanged = viewModel::changeRestorePassword,
            onVerifyPassword = {
                viewModel.verifyRestorePassword()
                onNavigationChanged()
            },
            onModeSelected = viewModel::selectRestoreMode,
            onHighRiskPhraseChanged = viewModel::changeRestoreHighRiskPhrase,
            onStartRestore = {
                viewModel.startRestore()
                onNavigationChanged()
            },
            onResolveConflict = viewModel::resolveRestoreConflict,
            onApplyToSimilarChanged = viewModel::changeRestoreApplyToSimilar,
            onApplyMerge = {
                viewModel.applyRestoreMerge()
                onNavigationChanged()
            },
            onCancel = viewModel::cancelRestore,
            onRetry = viewModel::retryRestore,
            onOpenApp = viewModel::finishRestoreFlow,
            onConfirmSafetySnapshotCleanup = viewModel::confirmRestoreSafetySnapshotCleanup,
            onCloudSnapshotSelected = viewModel::selectCloudBackupForDeletion,
            onCloudConfirmationChanged = viewModel::changeCloudBackupDeletePhrase,
            onAuthenticateCloudDelete = viewModel::loadCloudBackupsForDeletion,
            onDeleteCloudBackups = viewModel::deleteSelectedCloudBackups,
        ),
    )
}
