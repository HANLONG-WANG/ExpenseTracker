@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package app.ledger.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.feature.record.SpecializedTransactionDestination
import app.ledger.feature.record.SpecializedTransactionLoadState
import app.ledger.feature.record.SpecializedTransactionScreenAction

@Composable
internal fun SpecializedTransactionRootDestination(
    screenId: String,
    encodedArguments: Map<String, String>,
    state: SpecializedTransactionLoadState,
    viewModel: AppRootViewModel,
    onAddAttachment: () -> Unit,
) {
    val presetAccount = encodedArguments["accountId"]?.let { StableId.parse(it).getOrNull() }
    val transactionId = encodedArguments["transactionId"]?.let { StableId.parse(it).getOrNull() }
    LaunchedEffect(screenId, presetAccount, transactionId) { viewModel.loadSpecializedTransaction(screenId, presetAccount, transactionId) }
    SpecializedTransactionDestination(
        screenId,
        state,
        { action ->
            when (action) {
                SpecializedTransactionScreenAction.Retry -> viewModel.loadSpecializedTransaction(screenId, presetAccount, transactionId)
                SpecializedTransactionScreenAction.SelectFromAccount -> viewModel.selectSpecializedAccount(false)
                SpecializedTransactionScreenAction.SelectToAccount -> viewModel.selectSpecializedAccount(true)
                is SpecializedTransactionScreenAction.OutgoingExpression -> viewModel.specializedExpression(false, action.value)
                is SpecializedTransactionScreenAction.IncomingExpression -> viewModel.specializedExpression(true, action.value)
                is SpecializedTransactionScreenAction.OutgoingOperator -> viewModel.specializedOperator(false, action.value)
                is SpecializedTransactionScreenAction.IncomingOperator -> viewModel.specializedOperator(true, action.value)
                is SpecializedTransactionScreenAction.ManualFromRate -> viewModel.specializedManualRate(false, action.value)
                is SpecializedTransactionScreenAction.ManualToRate -> viewModel.specializedManualRate(true, action.value)
                SpecializedTransactionScreenAction.RefreshRates -> viewModel.refreshSpecializedRates()
                is SpecializedTransactionScreenAction.DirectionChanged -> viewModel.specializedDirection(action.direction)
                is SpecializedTransactionScreenAction.CheckpointSelected -> viewModel.specializedCheckpoint(action.checkpointId)
                is SpecializedTransactionScreenAction.DateChanged -> viewModel.specializedDate(action.date)
                is SpecializedTransactionScreenAction.NoteChanged -> viewModel.specializedNote(action.value)
                SpecializedTransactionScreenAction.AddAttachment -> onAddAttachment()
                is SpecializedTransactionScreenAction.CancelAttachment -> viewModel.cancelSpecializedAttachment(action.index)
                SpecializedTransactionScreenAction.Save -> viewModel.saveSpecializedTransaction()
            }
        },
    )
}
