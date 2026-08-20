@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package app.ledger.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ledger.core.navigation.LedgerRouteContract
import app.ledger.core.navigation.ScreenId
import app.ledger.core.navigation.SessionGateState
import app.ledger.feature.transfer.ExportFlowActions
import app.ledger.feature.transfer.ExportFlowScreen

@Composable
internal fun ExportRootDestination(
    screenId: String,
    viewModel: AppRootViewModel,
    onNavigationChanged: () -> Unit,
) {
    val state by viewModel.exportFlow.collectAsStateWithLifecycle()
    ExportFlowScreen(
        state.copy(screenId = screenId),
        ExportFlowActions(
            onBack = viewModel::requestRootBack,
            onContentSelected = viewModel::selectExportContent,
            onFormatSelected = viewModel::selectExportFormat,
            onContinue = {
                viewModel.nextExportStep()
                onNavigationChanged()
            },
            onFieldToggled = viewModel::toggleExportField,
            onLocationCoordinatesChanged = viewModel::changeExportCoordinates,
            onFileNameChanged = viewModel::changeExportFileName,
            onDestinationSelected = viewModel::selectExportDestination,
            onConfirmOverwrite = viewModel::confirmExportOverwrite,
            onStart = viewModel::startExport,
            onCancel = viewModel::cancelExport,
            onRetry = viewModel::retryExport,
            onOpen = { viewModel.openExport() },
            onShare = { viewModel.shareExport() },
            onViewLocation = { viewModel.viewExportLocation() },
            onOperations = {
                viewModel.navigator.navigate(LedgerRouteContract.destination(ScreenId("G-007")), SessionGateState.READY)
                onNavigationChanged()
            },
        ),
    )
}
