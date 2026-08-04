@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package app.ledger.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.feature.record.RefundActions
import app.ledger.feature.record.RefundDestination
import app.ledger.feature.record.RefundOriginalPickerDestination
import app.ledger.feature.record.RefundPickerActions
import app.ledger.feature.record.RefundPickerState
import app.ledger.finance.application.RefundSearchQuery

@Composable
internal fun refundDestinationTitleOrNull(screenId: String): String? = if (screenId == "REC-015") {
    stringResource(R.string.p16_title_refund)
} else if (screenId == "REC-016") {
    stringResource(R.string.p16_title_refund_original)
} else {
    null
}

@Composable
internal fun RefundRootDestination(
    screenId: String,
    encodedArguments: Map<String, String>,
    viewModel: AppRootViewModel,
    onNavigationChanged: () -> Unit,
) {
    val state by viewModel.refund.collectAsStateWithLifecycle()
    val pickerState by viewModel.refundPicker.collectAsStateWithLifecycle()
    if (screenId == "REC-016") {
        LaunchedEffect(screenId) {
            if (pickerState is RefundPickerState.Loading) viewModel.loadRefundOriginals()
        }
        RefundOriginalPickerDestination(
            pickerState,
            RefundPickerActions(
                onRetry = { viewModel.loadRefundOriginals() },
                onQuery = { text ->
                    val current = (viewModel.refundPicker.value as? RefundPickerState.Content)?.query ?: RefundSearchQuery()
                    viewModel.loadRefundOriginals(current.copy(text = text))
                },
                onPartialOnly = { only ->
                    val current = (viewModel.refundPicker.value as? RefundPickerState.Content)?.query ?: RefundSearchQuery()
                    viewModel.loadRefundOriginals(current.copy(partiallyRefundedOnly = only))
                },
                onChoose = {
                    viewModel.chooseRefundOriginal(it)
                    onNavigationChanged()
                },
                onIndependent = {
                    viewModel.setRefundIndependent(true)
                    viewModel.navigator.pop()
                    onNavigationChanged()
                },
            ),
        )
        return
    }
    val originalId = encodedArguments["transactionId"]?.let { StableId.parse(it).getOrNull() }
    LaunchedEffect(screenId, originalId) { viewModel.loadRefund(originalId) }
    RefundDestination(
        state,
        RefundActions(
            onRetry = { viewModel.loadRefund(originalId) },
            onPickOriginal = {
                viewModel.openRefundOriginalPicker()
                onNavigationChanged()
            },
            onIndependent = viewModel::setRefundIndependent,
            onExpression = viewModel::refundExpression,
            onOperator = viewModel::refundOperator,
            onAccount = viewModel::selectNextRefundAccount,
            onCard = viewModel::selectNextRefundCard,
            onCategory = viewModel::selectNextRefundCategory,
            onMerchant = viewModel::selectNextRefundMerchant,
            onProject = viewModel::selectNextRefundProject,
            onGoal = viewModel::selectNextRefundGoal,
            onDate = viewModel::refundDate,
            onAccrualPolicy = viewModel::refundAccrual,
            onBudgetPolicy = viewModel::refundBudget,
            onProjectPolicy = viewModel::refundProject,
            onGoalPolicy = viewModel::refundGoal,
            onNote = viewModel::refundNote,
            onRequestExcess = viewModel::requestRefundExcess,
            onConfirmExcess = viewModel::confirmRefundExcess,
        ),
    )
}
