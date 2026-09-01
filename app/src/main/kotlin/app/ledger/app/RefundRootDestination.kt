@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package app.ledger.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.feature.record.RefundDestination
import app.ledger.feature.record.RefundOriginalPickerDestination
import app.ledger.feature.record.RefundPickerScreenAction
import app.ledger.feature.record.RefundPickerState
import app.ledger.feature.record.RefundScreenAction
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
            { action ->
                when (action) {
                    RefundPickerScreenAction.Retry -> viewModel.loadRefundOriginals()
                    is RefundPickerScreenAction.QueryChanged -> {
                        val current = (viewModel.refundPicker.value as? RefundPickerState.Content)?.query ?: RefundSearchQuery()
                        viewModel.loadRefundOriginals(current.copy(text = action.query))
                    }
                    is RefundPickerScreenAction.PartialOnlyChanged -> {
                        val current = (viewModel.refundPicker.value as? RefundPickerState.Content)?.query ?: RefundSearchQuery()
                        viewModel.loadRefundOriginals(current.copy(partiallyRefundedOnly = action.enabled))
                    }
                    is RefundPickerScreenAction.Choose -> {
                        viewModel.chooseRefundOriginal(action.transactionId)
                        onNavigationChanged()
                    }
                    RefundPickerScreenAction.Independent -> {
                        viewModel.setRefundIndependent(true)
                        viewModel.navigator.pop()
                        onNavigationChanged()
                    }
                }
            },
        )
        return
    }
    val originalId = encodedArguments["transactionId"]?.let { StableId.parse(it).getOrNull() }
    LaunchedEffect(screenId, originalId) { viewModel.loadRefund(originalId) }
    RefundDestination(
        state,
        { action ->
            when (action) {
                RefundScreenAction.Retry -> viewModel.loadRefund(originalId)
                RefundScreenAction.PickOriginal -> {
                    viewModel.openRefundOriginalPicker()
                    onNavigationChanged()
                }
                is RefundScreenAction.IndependentChanged -> viewModel.setRefundIndependent(action.independent)
                is RefundScreenAction.ExpressionChanged -> viewModel.refundExpression(action.value)
                is RefundScreenAction.Operator -> viewModel.refundOperator(action.value)
                is RefundScreenAction.Account -> viewModel.selectRefundAccount(action.id)
                is RefundScreenAction.Card -> viewModel.selectRefundCard(action.id)
                is RefundScreenAction.Category -> viewModel.selectRefundCategory(action.id)
                RefundScreenAction.CreateCategory -> {
                    viewModel.openRefundCategoryCreator()
                    onNavigationChanged()
                }
                is RefundScreenAction.Merchant -> viewModel.selectRefundMerchant(action.id)
                is RefundScreenAction.Project -> viewModel.selectRefundProject(action.id)
                is RefundScreenAction.Goal -> viewModel.selectRefundGoal(action.id)
                is RefundScreenAction.DateChanged -> viewModel.refundDate(action.date)
                is RefundScreenAction.AccrualPolicyChanged -> viewModel.refundAccrual(action.policy)
                is RefundScreenAction.BudgetPolicyChanged -> viewModel.refundBudget(action.policy)
                is RefundScreenAction.ProjectPolicyChanged -> viewModel.refundProject(action.policy)
                is RefundScreenAction.GoalPolicyChanged -> viewModel.refundGoal(action.policy)
                is RefundScreenAction.NoteChanged -> viewModel.refundNote(action.value)
                is RefundScreenAction.RequestExcess -> viewModel.requestRefundExcess(action.requested)
                RefundScreenAction.ConfirmExcess -> viewModel.confirmRefundExcess()
            }
        },
    )
}
