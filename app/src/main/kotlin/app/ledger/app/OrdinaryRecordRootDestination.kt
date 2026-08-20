@file:Suppress("ktlint:standard:function-naming", "FunctionNaming")

package app.ledger.app

import androidx.compose.runtime.Composable
import app.ledger.feature.record.OrdinaryRecordDestination
import app.ledger.feature.record.OrdinaryRecordScreenAction
import app.ledger.feature.record.OrdinaryRecordScreenUiState

/** Connects the P13 feature contract to the root without exposing persistence or journal construction. */
@Composable
internal fun OrdinaryRecordRootDestination(
    screenId: String,
    uiState: OrdinaryRecordScreenUiState,
    viewModel: AppRootViewModel,
    onAddAttachment: () -> Unit,
    onNavigationChanged: () -> Unit,
) {
    OrdinaryRecordDestination(
        screenId,
        uiState,
        { action ->
            when (action) {
                OrdinaryRecordScreenAction.Retry -> viewModel.loadOrdinaryRecord()
                is OrdinaryRecordScreenAction.SelectTab -> viewModel.selectRecordTab(action.tab)
                is OrdinaryRecordScreenAction.Search -> viewModel.updateRecordSearch(action.query)
                is OrdinaryRecordScreenAction.Navigate -> {
                    viewModel.navigateRecord(action.screenId, action.stableArguments, action.enumArguments)
                    onNavigationChanged()
                }
                is OrdinaryRecordScreenAction.OpenEditor -> {
                    viewModel.openRecordEditor(action.mode, action.direction, action.categoryId, action.sourceId)
                    onNavigationChanged()
                }
                is OrdinaryRecordScreenAction.Expression -> viewModel.recordExpression(action.value)
                is OrdinaryRecordScreenAction.Operator -> viewModel.recordOperator(action.value)
                is OrdinaryRecordScreenAction.SelectCategory -> viewModel.selectRecordCategory(action.categoryId)
                is OrdinaryRecordScreenAction.SelectAccount -> viewModel.selectRecordAccount(action.accountId)
                is OrdinaryRecordScreenAction.SelectCard -> viewModel.selectRecordCard(action.cardId)
                is OrdinaryRecordScreenAction.SelectReference -> viewModel.selectRecordReference(action.field, action.id)
                is OrdinaryRecordScreenAction.Note -> viewModel.updateRecordNote(action.value)
                is OrdinaryRecordScreenAction.SettlementEnabled -> viewModel.setRecordSettlementEnabled(action.enabled)
                is OrdinaryRecordScreenAction.SettlementActivity -> viewModel.selectRecordSettlementActivity(action.activityId)
                is OrdinaryRecordScreenAction.SettlementPayer -> viewModel.selectRecordSettlementPayer(action.participantId)
                is OrdinaryRecordScreenAction.SettlementSplitMethodChanged -> viewModel.selectRecordSettlementSplitMethod(action.method)
                is OrdinaryRecordScreenAction.SettlementChargeDistributionChanged ->
                    viewModel.selectRecordSettlementChargeDistribution(action.distribution)
                is OrdinaryRecordScreenAction.SettlementRoundingRuleChanged -> viewModel.selectRecordSettlementRoundingRule(action.rule)
                is OrdinaryRecordScreenAction.SettlementParticipantIncluded ->
                    viewModel.toggleRecordSettlementParticipant(action.participantId)
                is OrdinaryRecordScreenAction.SettlementAllocationInput ->
                    viewModel.updateRecordSettlementAllocationInput(action.participantId, action.value)
                is OrdinaryRecordScreenAction.SettlementChargeInput ->
                    viewModel.updateRecordSettlementChargeInput(action.participantId, action.value)
                is OrdinaryRecordScreenAction.SettlementTax -> viewModel.updateRecordSettlementTax(action.value)
                is OrdinaryRecordScreenAction.SettlementServiceFee -> viewModel.updateRecordSettlementServiceFee(action.value)
                is OrdinaryRecordScreenAction.OccurredAt -> viewModel.updateRecordOccurredAt(action.dateMillis, action.hour, action.minute)
                OrdinaryRecordScreenAction.AddAttachment -> onAddAttachment()
                is OrdinaryRecordScreenAction.CancelAttachment -> viewModel.cancelRecordAttachment(action.index)
                OrdinaryRecordScreenAction.Save -> viewModel.saveOrdinaryRecord()
                OrdinaryRecordScreenAction.UnsavedDiscard -> {
                    viewModel.discardRecordChanges()
                    onNavigationChanged()
                }
                OrdinaryRecordScreenAction.UnsavedKeepEditing -> viewModel.keepEditingRecord()
                OrdinaryRecordScreenAction.ReloadConflict -> viewModel.reloadRecordConflict()
                OrdinaryRecordScreenAction.CancelConflict -> {
                    viewModel.cancelRecordConflict()
                    onNavigationChanged()
                }
            }
        },
    )
}
