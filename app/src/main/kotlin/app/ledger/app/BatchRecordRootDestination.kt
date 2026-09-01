@file:Suppress("ktlint:standard:function-naming", "FunctionNaming")

package app.ledger.app

import androidx.compose.runtime.Composable
import app.ledger.feature.record.BatchRecordDestination
import app.ledger.feature.record.BatchRecordScreenAction
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
        { action ->
            when (action) {
                is BatchRecordScreenAction.OpenRow -> {
                    viewModel.openBatchRow(action.rowId)
                    onNavigationChanged()
                }
                BatchRecordScreenAction.Add -> viewModel.addBatchRow()
                is BatchRecordScreenAction.Copy -> viewModel.copyBatchRow(action.rowId)
                is BatchRecordScreenAction.Delete -> {
                    viewModel.deleteBatchRow(action.rowId)
                    onNavigationChanged()
                }
                is BatchRecordScreenAction.Move -> viewModel.moveBatchRow(action.rowId, action.offset)
                is BatchRecordScreenAction.Sort -> viewModel.sortBatchRows(action.sort)
                is BatchRecordScreenAction.Paste -> viewModel.pasteBatchRows(action.text)
                is BatchRecordScreenAction.RowChange -> viewModel.updateBatchRow(action.row)
                is BatchRecordScreenAction.SelectReference -> viewModel.selectBatchReference(action.rowId, action.field, action.selectedId)
                is BatchRecordScreenAction.AddAttachment -> {
                    viewModel.requestBatchAttachment(action.rowId)
                    onLaunchAttachmentPicker()
                }
                BatchRecordScreenAction.Validate -> {
                    viewModel.validateBatchEntry()
                    onNavigationChanged()
                }
                BatchRecordScreenAction.ConfirmWarnings -> viewModel.confirmBatchWarnings()
                BatchRecordScreenAction.Commit -> viewModel.submitBatchEntry()
                BatchRecordScreenAction.Undo -> viewModel.undoBatchEntry()
                BatchRecordScreenAction.Discard -> viewModel.discardBatchEntry()
                BatchRecordScreenAction.KeepEditing -> viewModel.keepEditingBatchEntry()
                is BatchRecordScreenAction.JumpToIssue -> {
                    viewModel.jumpToBatchIssue(action.issue)
                    onNavigationChanged()
                }
            }
        },
    )
}
