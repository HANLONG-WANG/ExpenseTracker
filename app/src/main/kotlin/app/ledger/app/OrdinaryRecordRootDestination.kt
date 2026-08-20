@file:Suppress("ktlint:standard:function-naming", "FunctionNaming")

package app.ledger.app

import androidx.compose.runtime.Composable
import app.ledger.core.geo.LedgerMap
import app.ledger.core.geo.LedgerMapAccessibleRow
import app.ledger.core.geo.LedgerMapMode
import app.ledger.core.geo.LedgerMapPoint
import app.ledger.core.geo.LedgerMapState
import app.ledger.core.geo.LedgerMapStyleConfiguration
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
        state,
        OrdinaryRecordActions(
            onRetry = viewModel::loadOrdinaryRecord,
            onTab = viewModel::selectRecordTab,
            onSearch = viewModel::updateRecordSearch,
            onNavigate = { target, stable, enums ->
                viewModel.navigateRecord(target, stable, enums)
                onNavigationChanged()
            },
            onOpenEditor = { mode, direction, category, source ->
                viewModel.openRecordEditor(mode, direction, category, source)
                onNavigationChanged()
            },
            onExpression = viewModel::recordExpression,
            onOperator = viewModel::recordOperator,
            onSelectCategory = viewModel::selectRecordCategory,
            onSelectAccount = viewModel::selectRecordAccount,
            onSelectCard = viewModel::selectRecordCard,
            onSelectReference = viewModel::selectRecordReference,
            onNote = viewModel::updateRecordNote,
            onSettlementEnabled = viewModel::setRecordSettlementEnabled,
            onSettlementActivity = viewModel::selectRecordSettlementActivity,
            onSettlementPayer = viewModel::selectRecordSettlementPayer,
            onSettlementSplitMethod = viewModel::selectRecordSettlementSplitMethod,
            onSettlementChargeDistribution = viewModel::selectRecordSettlementChargeDistribution,
            onSettlementRoundingRule = viewModel::selectRecordSettlementRoundingRule,
            onSettlementParticipantIncluded = viewModel::toggleRecordSettlementParticipant,
            onSettlementAllocationInput = viewModel::updateRecordSettlementAllocationInput,
            onSettlementChargeInput = viewModel::updateRecordSettlementChargeInput,
            onSettlementTax = viewModel::updateRecordSettlementTax,
            onSettlementServiceFee = viewModel::updateRecordSettlementServiceFee,
            onOccurredAt = viewModel::updateRecordOccurredAt,
            onAddAttachment = onAddAttachment,
            onOpenAttachment = {
                viewModel.openRecordAttachment(it)
                onNavigationChanged()
            },
            onCancelAttachment = viewModel::cancelRecordAttachment,
            onSave = viewModel::saveOrdinaryRecord,
            onUnsavedDiscard = {
                viewModel.discardRecordChanges()
                onNavigationChanged()
            },
            onUnsavedKeepEditing = viewModel::keepEditingRecord,
            onReloadConflict = viewModel::reloadRecordConflict,
            onCancelConflict = {
                viewModel.cancelRecordConflict()
                onNavigationChanged()
            },
            onLocationPoint = viewModel::selectRecordLocationPoint,
            onLocationCoordinate = viewModel::moveRecordLocationPin,
            onLocationMapUnavailable = viewModel::recordLocationMapUnavailable,
            onUseLocation = {
                viewModel.useRecordLocation()
                onNavigationChanged()
            },
            onClearLocation = {
                viewModel.clearRecordLocation()
                onNavigationChanged()
            },
        ),
    )
}
