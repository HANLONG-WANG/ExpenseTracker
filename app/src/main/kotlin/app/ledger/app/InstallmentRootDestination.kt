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
import app.ledger.feature.liabilities.InstallmentActions
import app.ledger.feature.liabilities.InstallmentDestination
import app.ledger.feature.liabilities.InstallmentLoadState
import app.ledger.feature.liabilities.InstallmentPresentation
import app.ledger.feature.liabilities.R as LiabilitiesR

@Composable
internal fun installmentDestinationTitleOrNull(screenId: String): String? = when (screenId) {
    "REC-027" -> stringResource(LiabilitiesR.string.installment_setup_title)
    "INS-001" -> stringResource(LiabilitiesR.string.installment_list_title)
    "INS-002" -> stringResource(LiabilitiesR.string.installment_editor_title)
    "INS-003" -> stringResource(LiabilitiesR.string.installment_detail_title)
    "INS-004" -> stringResource(LiabilitiesR.string.installment_schedule_title)
    "INS-005" -> stringResource(LiabilitiesR.string.installment_settlement_title)
    "INS-006" -> stringResource(LiabilitiesR.string.installment_refund_title)
    else -> null
}

@Composable
internal fun InstallmentRootDestination(
    screenId: String,
    encodedArguments: Map<String, String>,
    viewModel: AppRootViewModel,
    onNavigationChanged: () -> Unit,
) {
    val planId = encodedArguments.stableId("planId")
    val purchaseId = encodedArguments.stableId("purchaseTransactionId")
    val state by viewModel.installment.collectAsStateWithLifecycle()
    LaunchedEffect(screenId, planId, purchaseId) { viewModel.loadInstallment(screenId, planId, purchaseId) }
    InstallmentDestination(
        screenId,
        state,
        encodedArguments,
        InstallmentActions(
            onRetry = { viewModel.loadInstallment(screenId, planId, purchaseId) },
            onNavigate = { target, stableId ->
                viewModel.navigateInstallment(target, stableId)
                onNavigationChanged()
            },
            onFieldChanged = viewModel::updateInstallmentField,
            onFeeModelChanged = viewModel::updateInstallmentFeeModel,
            onRefundPolicyChanged = viewModel::updateInstallmentRefundPolicy,
            onSelectPurchase = viewModel::selectInstallmentPurchase,
            onPreview = viewModel::previewInstallment,
            onCalculateSettlement = viewModel::calculateInstallmentSettlement,
            onApplySettlement = viewModel::applyInstallmentSettlement,
        ),
    )
}

internal fun installmentFixedAction(
    screenId: String,
    state: InstallmentLoadState,
    pending: Boolean,
    onSave: () -> Unit,
): (@Composable BoxScope.() -> Unit)? {
    if (screenId !in setOf("REC-027", "INS-002")) return null
    return {
        val presentation = (state as? InstallmentLoadState.Content)?.state?.presentation
        LedgerSaveFab(
            onSave,
            submitting = pending || presentation == InstallmentPresentation.SAVING,
            enabled = !pending && presentation == InstallmentPresentation.PREVIEW,
        )
    }
}

private fun Map<String, String>.stableId(name: String): StableId? = get(name)?.let { StableId.parse(it).getOrNull() }
