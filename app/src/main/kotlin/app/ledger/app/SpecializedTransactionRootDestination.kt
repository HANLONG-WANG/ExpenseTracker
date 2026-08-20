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
    onNavigationChanged: () -> Unit,
) {
    val presetAccount = encodedArguments["accountId"]?.let { StableId.parse(it).getOrNull() }
    LaunchedEffect(screenId, presetAccount) { viewModel.loadSpecializedTransaction(screenId, presetAccount) }
    SpecializedTransactionDestination(
        screenId,
        state,
        SpecializedTransactionActions(
            onRetry = { viewModel.loadSpecializedTransaction(screenId, presetAccount) },
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
            onOccurredAt = viewModel::specializedOccurredAt,
            onNote = viewModel::specializedNote,
            onAddAttachment = onAddAttachment,
            onOpenAttachment = {
                viewModel.openSpecializedAttachment(it)
                onNavigationChanged()
            },
            onCancelAttachment = viewModel::cancelSpecializedAttachment,
            onSave = viewModel::saveSpecializedTransaction,
        ),
    )
}
