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
import app.ledger.feature.settlement.SettlementDestination
import app.ledger.feature.settlement.SettlementLoadState
import app.ledger.feature.settlement.SettlementPresentation
import app.ledger.feature.settlement.SettlementScreenAction
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
    val uiState by viewModel.settlementUiState.collectAsStateWithLifecycle()
    LaunchedEffect(screenId, activityId, participantId) { viewModel.loadSettlement(screenId, activityId, participantId) }
    SettlementDestination(
        screenId,
        uiState.loadState,
        { action ->
            when (action) {
                SettlementScreenAction.Retry -> viewModel.loadSettlement(screenId, activityId, participantId)
                is SettlementScreenAction.Navigate -> {
                    viewModel.navigateSettlement(action.screenId, action.activityId, action.participantId)
                    onNavigationChanged()
                }
                is SettlementScreenAction.FieldChanged -> viewModel.updateSettlementField(action.field, action.value)
                is SettlementScreenAction.SelectActivity -> viewModel.selectSettlementActivity(action.activityId)
                is SettlementScreenAction.SelectPayer -> viewModel.selectSettlementPayer(action.participantId)
                is SettlementScreenAction.SelectPayee -> viewModel.selectSettlementPayee(action.participantId)
                is SettlementScreenAction.SelectAccount -> viewModel.selectSettlementAccount(action.accountId)
                is SettlementScreenAction.SelectProject -> viewModel.selectSettlementProject(action.projectId)
                is SettlementScreenAction.SelectCurrency -> viewModel.selectSettlementCurrency(action.currency)
                is SettlementScreenAction.SplitMethodChanged -> viewModel.selectSettlementSplitMethod(action.method)
                is SettlementScreenAction.ChargeDistributionChanged -> viewModel.selectSettlementChargeDistribution(action.distribution)
                is SettlementScreenAction.RoundingRuleChanged -> viewModel.selectSettlementRoundingRule(action.rule)
                is SettlementScreenAction.ToggleParticipant -> viewModel.toggleSettlementParticipant(action.participantId)
                is SettlementScreenAction.MoveParticipant -> viewModel.moveSettlementParticipant(action.participantId, action.offset)
                SettlementScreenAction.AddParticipant -> viewModel.addSettlementParticipant()
                SettlementScreenAction.Save -> viewModel.saveSettlement()
                SettlementScreenAction.Rebuild -> viewModel.rebuildSettlement()
            }
        },
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
        val presentation = (state as? SettlementLoadState.Content)?.state?.presentation
        LedgerSaveFab(
            onSave,
            submitting = pending || presentation == SettlementPresentation.SAVING,
            enabled = !pending,
        )
    }
}
