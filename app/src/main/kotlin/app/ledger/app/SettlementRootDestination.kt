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
import app.ledger.feature.settlement.SettlementActions
import app.ledger.feature.settlement.SettlementDestination
import app.ledger.feature.settlement.SettlementLoadState
import app.ledger.feature.settlement.SettlementPolicy
import app.ledger.feature.settlement.SettlementPresentation
import app.ledger.feature.settlement.R as SettlementR

@Composable
internal fun settlementDestinationTitleOrNull(screenId: String): String? = when (screenId) {
    "SET-001" -> stringResource(SettlementR.string.settlement_title_home)
    "SET-002" -> stringResource(SettlementR.string.settlement_title_editor)
    "SET-003" -> stringResource(SettlementR.string.settlement_title_participants)
    "SET-004" -> stringResource(SettlementR.string.settlement_title_detail)
    "SET-005" -> stringResource(SettlementR.string.settlement_title_position)
    "SET-006" -> stringResource(SettlementR.string.settlement_title_payment)
    "SET-007" -> stringResource(SettlementR.string.settlement_title_history)
    "SET-008" -> stringResource(SettlementR.string.settlement_title_additional)
    else -> null
}

@Composable
internal fun SettlementRootDestination(
    screenId: String,
    encodedArguments: Map<String, String>,
    viewModel: AppRootViewModel,
    onNavigationChanged: () -> Unit,
) {
    val activityId = encodedArguments["activityId"]?.let { StableId.parse(it).getOrNull() }
    val participantId = encodedArguments["participantId"]?.let { StableId.parse(it).getOrNull() }
    val state by viewModel.settlement.collectAsStateWithLifecycle()
    LaunchedEffect(screenId, activityId, participantId) { viewModel.ensureSettlementLoaded(screenId, activityId, participantId) }
    SettlementDestination(
        screenId,
        state,
        SettlementActions(
            onRetry = { viewModel.loadSettlement(screenId, activityId, participantId) },
            onNavigate = { target, id, selectedParticipantId ->
                viewModel.navigateSettlement(target, id, selectedParticipantId)
                onNavigationChanged()
            },
            onFieldChanged = viewModel::updateSettlementField,
            onSelectActivity = viewModel::selectSettlementActivity,
            onSelectPayer = viewModel::selectSettlementPayer,
            onSelectPayee = viewModel::selectSettlementPayee,
            onSelectAccount = viewModel::selectSettlementAccount,
            onSelectProject = viewModel::selectSettlementProject,
            onSelectCurrency = viewModel::selectSettlementCurrency,
            onSplitMethod = viewModel::selectSettlementSplitMethod,
            onChargeDistribution = viewModel::selectSettlementChargeDistribution,
            onRoundingRule = viewModel::selectSettlementRoundingRule,
            onToggleParticipant = viewModel::toggleSettlementParticipant,
            onSetSelfParticipant = viewModel::setSettlementSelfParticipant,
            onMoveParticipant = viewModel::moveSettlementParticipant,
            onAddParticipant = viewModel::addSettlementParticipant,
            onSave = viewModel::saveSettlement,
            onRebuild = viewModel::rebuildSettlement,
        ),
    )
}

internal fun settlementFixedAction(
    screenId: String,
    state: SettlementLoadState,
    pending: Boolean,
    onSave: () -> Unit,
): (@Composable BoxScope.() -> Unit)? {
    if (screenId !in setOf("SET-002", "SET-003", "SET-006")) return null
    return {
        val content = (state as? SettlementLoadState.Content)?.state
        val presentation = content?.presentation
        LedgerSaveFab(
            onSave,
            submitting = pending || presentation == SettlementPresentation.SAVING,
            enabled = !pending && content != null,
        )
    }
}
