@file:Suppress("ktlint:standard:function-naming", "FunctionNaming")

package app.ledger.app

import androidx.compose.runtime.Composable
import app.ledger.feature.record.BatchRecordActions
import app.ledger.feature.record.BatchRecordDestination
import app.ledger.feature.record.BatchRecordState

@Composable
internal fun BatchRecordRootDestination(
    screenId: String,
    state: BatchRecordState,
    viewModel: AppRootViewModel,
    onLaunchAttachmentPicker: () -> Unit,
    onNavigationChanged: () -> Unit,
) {
    BatchRecordDestination(
        screenId,
        state,
        BatchRecordActions(
            onOpenRow = {
                viewModel.openBatchRow(it)
                onNavigationChanged()
            },
            onAdd = viewModel::addBatchRow,
            onCopy = viewModel::copyBatchRow,
            onDelete = {
                viewModel.deleteBatchRow(it)
                onNavigationChanged()
            },
            onMove = viewModel::moveBatchRow,
            onSort = viewModel::sortBatchRows,
            onPaste = viewModel::pasteBatchRows,
            onRowChange = viewModel::updateBatchRow,
            onCycleReference = viewModel::cycleBatchReference,
            onAddAttachment = { rowId ->
                viewModel.requestBatchAttachment(rowId)
                onLaunchAttachmentPicker()
            },
            onValidate = {
                viewModel.validateBatchEntry()
                onNavigationChanged()
            },
            onConfirmWarnings = viewModel::confirmBatchWarnings,
            onCommit = viewModel::submitBatchEntry,
            onUndo = viewModel::undoBatchEntry,
            onDiscard = viewModel::discardBatchEntry,
            onKeepEditing = viewModel::keepEditingBatchEntry,
            onJumpToIssue = {
                viewModel.jumpToBatchIssue(it)
                onNavigationChanged()
            },
        ),
    )
}
