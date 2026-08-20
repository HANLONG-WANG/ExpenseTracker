@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package app.ledger.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ledger.core.navigation.LedgerRouteContract
import app.ledger.core.navigation.ScreenId
import app.ledger.core.navigation.SessionGateState
import app.ledger.feature.transfer.ExportFlowScreen
import app.ledger.feature.transfer.ExportFlowScreenAction

@Composable
internal fun ExportRootDestination(
    screenId: String,
    viewModel: AppRootViewModel,
    onNavigationChanged: () -> Unit,
) {
    val state by viewModel.exportFlow.collectAsStateWithLifecycle()
    ExportFlowScreen(
        state.copy(screenId = screenId),
        { action ->
            when (action) {
                ExportFlowScreenAction.Back -> viewModel.requestRootBack()
                is ExportFlowScreenAction.ContentSelected -> viewModel.selectExportContent(action.content)
                is ExportFlowScreenAction.FormatSelected -> viewModel.selectExportFormat(action.format)
                ExportFlowScreenAction.Continue -> {
                    viewModel.nextExportStep()
                    onNavigationChanged()
                }
                is ExportFlowScreenAction.FieldToggled -> viewModel.toggleExportField(action.field)
                is ExportFlowScreenAction.LocationCoordinatesChanged -> viewModel.changeExportCoordinates(action.included)
                is ExportFlowScreenAction.FileNameChanged -> viewModel.changeExportFileName(action.value)
                is ExportFlowScreenAction.DestinationSelected -> viewModel.selectExportDestination(action.uri)
                ExportFlowScreenAction.ConfirmOverwrite -> viewModel.confirmExportOverwrite()
                ExportFlowScreenAction.Cancel -> viewModel.cancelExport()
                ExportFlowScreenAction.Retry -> viewModel.retryExport()
                ExportFlowScreenAction.Open -> viewModel.openExport()
                ExportFlowScreenAction.Share -> viewModel.shareExport()
                ExportFlowScreenAction.ViewLocation -> viewModel.viewExportLocation()
                ExportFlowScreenAction.Operations -> {
                    viewModel.navigator.navigate(LedgerRouteContract.destination(ScreenId("G-007")), SessionGateState.READY)
                    onNavigationChanged()
                }
            }
        },
    )
}
