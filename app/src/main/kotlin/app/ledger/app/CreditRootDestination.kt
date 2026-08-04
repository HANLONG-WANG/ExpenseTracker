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
import app.ledger.feature.liabilities.CreditActions
import app.ledger.feature.liabilities.CreditDestination
import app.ledger.feature.liabilities.CreditLoadState
import app.ledger.feature.liabilities.CreditPresentation
import app.ledger.feature.liabilities.R as CreditR

@Composable
internal fun creditDestinationTitleOrNull(screenId: String): String? = when (screenId) {
    "REC-014" -> stringResource(CreditR.string.credit_record_payment)
    "CRD-001" -> stringResource(CreditR.string.credit_account_title)
    "CRD-002" -> stringResource(CreditR.string.credit_profile)
    "CRD-003" -> stringResource(CreditR.string.credit_statements)
    "CRD-004" -> stringResource(CreditR.string.credit_official)
    "CRD-005" -> stringResource(CreditR.string.credit_record_official)
    "CRD-006" -> stringResource(CreditR.string.credit_assignment_explanation)
    "CRD-007" -> stringResource(CreditR.string.credit_payment_allocation)
    "CRD-008" -> stringResource(CreditR.string.credit_auto_payment)
    else -> null
}

@Composable
internal fun CreditRootDestination(
    screenId: String,
    encodedArguments: Map<String, String>,
    viewModel: AppRootViewModel,
    onNavigationChanged: () -> Unit,
) {
    val accountId = encodedArguments.stableId("accountId")
    val statementId = encodedArguments.stableId("statementId")
    val transactionId = encodedArguments.stableId("transactionId")
    val state by viewModel.credit.collectAsStateWithLifecycle()
    LaunchedEffect(screenId, accountId, statementId, transactionId) {
        viewModel.loadCredit(screenId, accountId, statementId, transactionId)
    }
    CreditDestination(
        screenId,
        state,
        encodedArguments,
        CreditActions(
            onRetry = { viewModel.loadCredit(screenId, accountId, statementId, transactionId) },
            onNavigate = { target, stableId ->
                viewModel.navigateCredit(target, stableId)
                onNavigationChanged()
            },
            onFieldChanged = viewModel::updateCreditField,
            onNextPaymentAccount = viewModel::selectNextCreditPaymentAccount,
            onSelectStatement = viewModel::selectCreditStatement,
            onSelectEarliest = viewModel::selectCreditEarliest,
            onSelectUnallocated = viewModel::selectCreditUnallocated,
            onAssignment = viewModel::assignCreditStatement,
            onToggleAutoPayment = viewModel::toggleCreditAutoPayment,
        ),
    )
}

private fun Map<String, String>.stableId(name: String): StableId? = get(name)?.let { StableId.parse(it).getOrNull() }

internal fun creditFixedAction(
    screenId: String,
    state: CreditLoadState,
    pending: Boolean,
    onSave: () -> Unit,
): (@Composable BoxScope.() -> Unit)? {
    if (screenId !in setOf("REC-014", "CRD-002", "CRD-005", "CRD-007", "CRD-008")) return null
    return {
        val content = (state as? CreditLoadState.Content)?.state
        LedgerSaveFab(onSave, submitting = pending || content?.presentation == CreditPresentation.SAVING, enabled = !pending)
    }
}
