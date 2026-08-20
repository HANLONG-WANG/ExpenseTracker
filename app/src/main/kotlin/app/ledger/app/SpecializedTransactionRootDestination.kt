@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package app.ledger.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.feature.record.SpecializedTransactionActions
import app.ledger.feature.record.SpecializedTransactionDestination
import app.ledger.feature.record.SpecializedTransactionLoadState

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
        SpecializedTransactionActions(
            onRetry = { viewModel.loadSpecializedTransaction(screenId, presetAccount, transactionId) },
            onSelectFromAccount = { viewModel.selectSpecializedAccount(false) },
            onSelectToAccount = { viewModel.selectSpecializedAccount(true) },
            onOutgoingExpression = { viewModel.specializedExpression(false, it) },
            onIncomingExpression = { viewModel.specializedExpression(true, it) },
            onOutgoingOperator = { viewModel.specializedOperator(false, it) },
            onIncomingOperator = { viewModel.specializedOperator(true, it) },
            onManualFromRate = { viewModel.specializedManualRate(false, it) },
            onManualToRate = { viewModel.specializedManualRate(true, it) },
            onRefreshRates = viewModel::refreshSpecializedRates,
            onDirection = viewModel::specializedDirection,
            onCheckpoint = viewModel::specializedCheckpoint,
            onDate = viewModel::specializedDate,
            onNote = viewModel::specializedNote,
            onAddAttachment = onAddAttachment,
            onCancelAttachment = viewModel::cancelSpecializedAttachment,
            onSave = viewModel::saveSpecializedTransaction,
        ),
    )
}
