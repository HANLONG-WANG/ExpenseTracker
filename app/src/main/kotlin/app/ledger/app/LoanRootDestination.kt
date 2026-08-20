@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package app.ledger.app

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.core.designsystem.LedgerSaveFab
import app.ledger.feature.liabilities.LoanActions
import app.ledger.feature.liabilities.LoanDestination
import app.ledger.feature.liabilities.LoanLoadState
import app.ledger.feature.liabilities.LoanPresentation
import app.ledger.finance.domain.UserAccountType
import app.ledger.feature.liabilities.R as LiabilitiesR

@Composable
internal fun loanDestinationTitleOrNull(screenId: String): String? = when (screenId) {
    "LIA-001" -> stringResource(LiabilitiesR.string.loan_title_liabilities)
    "REC-017" -> stringResource(LiabilitiesR.string.loan_title_operation)
    "REC-018" -> stringResource(LiabilitiesR.string.loan_title_disbursement)
    "REC-019" -> stringResource(LiabilitiesR.string.loan_title_payment)
    "LOA-001" -> stringResource(LiabilitiesR.string.loan_title_list)
    "LOA-002" -> stringResource(LiabilitiesR.string.loan_title_wizard)
    "LOA-003" -> stringResource(LiabilitiesR.string.loan_title_tranche)
    "LOA-004" -> stringResource(LiabilitiesR.string.loan_title_terms)
    "LOA-005" -> stringResource(LiabilitiesR.string.loan_title_rates)
    "LOA-006" -> stringResource(LiabilitiesR.string.loan_title_preview)
    "LOA-007" -> stringResource(LiabilitiesR.string.loan_title_detail)
    "LOA-008" -> stringResource(LiabilitiesR.string.loan_title_schedule)
    "LOA-009" -> stringResource(LiabilitiesR.string.loan_title_payment_detail)
    "LOA-010" -> stringResource(LiabilitiesR.string.loan_title_simulation)
    "LOA-011" -> stringResource(LiabilitiesR.string.loan_title_apply)
    else -> null
}

@Composable
internal fun LoanRootDestination(
    screenId: String,
    encodedArguments: Map<String, String>,
    viewModel: AppRootViewModel,
    onNavigationChanged: () -> Unit,
) {
    val contractId = encodedArguments.loanStableId("contractId")
    val trancheId = encodedArguments.loanStableId("trancheId")
    val transactionId = encodedArguments.loanStableId("transactionId")
    val simulationId = encodedArguments.loanStableId("simulationId")
    val state by viewModel.loan.collectAsStateWithLifecycle()
    LaunchedEffect(screenId, contractId, trancheId, transactionId, simulationId) {
        viewModel.loadLoan(screenId, contractId, trancheId, transactionId, simulationId)
    }
    LoanDestination(
        screenId,
        state,
        encodedArguments,
        LoanActions(
            onRetry = { viewModel.loadLoan(screenId, contractId, trancheId, transactionId, simulationId) },
            onNavigate = { target, primary, secondary ->
                viewModel.navigateLoan(target, primary, secondary)
                onNavigationChanged()
            },
            onFieldChanged = viewModel::updateLoanField,
            onSelectContract = viewModel::selectLoanContract,
            onSelectTranche = viewModel::selectLoanTranche,
            onRepaymentMethod = viewModel::selectLoanRepaymentMethod,
            onStrategy = viewModel::selectLoanStrategy,
            onPreview = viewModel::previewLoan,
            onSave = viewModel::saveLoan,
            onSimulate = viewModel::simulateLoan,
            onApplySimulation = viewModel::applyLoanSimulation,
            onCreateLoanAccount = {
                viewModel.selectP12AccountType(UserAccountType.LOAN, viewModel.navigator.currentKey)
                onNavigationChanged()
            },
            onOpenCreditAccount = { accountId ->
                viewModel.navigateCredit("CRD-001", accountId)
                onNavigationChanged()
            },
        ),
    )
}

internal fun loanFixedAction(
    screenId: String,
    state: LoanLoadState,
    pending: Boolean,
    onSave: () -> Unit,
): (@Composable BoxScope.() -> Unit)? {
    if (screenId !in setOf("REC-018", "REC-019", "LOA-002", "LOA-003", "LOA-004")) return null
    val missingLoanAccount = (state as? LoanLoadState.Content)
        ?.state
        ?.snapshot
        ?.loanAccounts
        ?.none { it.active } == true
    if (screenId == "LOA-002" && missingLoanAccount) return null
    return {
        val presentation = (state as? LoanLoadState.Content)?.state?.presentation
        LedgerSaveFab(
            onSave,
            submitting = pending || presentation in setOf(LoanPresentation.SAVING, LoanPresentation.GENERATING_SCHEDULE),
            enabled = !pending,
        )
    }
}

private fun Map<String, String>.loanStableId(name: String): StableId? = get(name)?.let { StableId.parse(it).getOrNull() }
