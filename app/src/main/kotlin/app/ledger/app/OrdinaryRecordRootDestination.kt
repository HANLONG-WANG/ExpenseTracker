@file:Suppress("ktlint:standard:function-naming", "FunctionNaming")

package app.ledger.app

import androidx.compose.runtime.Composable
import app.ledger.core.geo.LedgerMap
import app.ledger.core.geo.LedgerMapAccessibleRow
import app.ledger.core.geo.LedgerMapMode
import app.ledger.core.geo.LedgerMapPoint
import app.ledger.core.geo.LedgerMapState
import app.ledger.core.geo.LedgerMapStyleConfiguration
import app.ledger.feature.record.OrdinaryRecordActions
import app.ledger.feature.record.OrdinaryRecordDestination
import app.ledger.feature.record.OrdinaryRecordLoadState

/** Connects the P13 feature contract to the root without exposing persistence or journal construction. */
@Composable
internal fun OrdinaryRecordRootDestination(
    screenId: String,
    state: OrdinaryRecordLoadState,
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
            onManualLocation = viewModel::setRecordManualLocation,
            onAddAttachment = onAddAttachment,
            onReuseAttachment = viewModel::reuseRecordAttachment,
            onOpenAttachment = { attachmentId ->
                viewModel.openAttachment(attachmentId)
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
        ),
        locationMap = { model, onPointSelected, onCoordinateSelected, onFailure ->
            val rows = model.rows.map { LedgerMapAccessibleRow(it.label, it.coordinates) }
            val points = model.points.map { LedgerMapPoint(it.id, it.latitudeE7, it.longitudeE7, 1L, it.selected) }
            LedgerMap(
                state = if (model.unavailable) {
                    LedgerMapState.Unavailable(model.summary, rows)
                } else {
                    LedgerMapState.Available(model.summary, LedgerMapMode.SINGLE_POINTS, points, rows)
                },
                styleConfiguration = LedgerMapStyleConfiguration.OpenFreeMap,
                accessibleCaption = model.caption,
                accessibleColumnHeaders = listOf(model.nameHeader, model.coordinateHeader),
                showAccessibleListLabel = model.showListLabel,
                hideAccessibleListLabel = model.hideListLabel,
                onFailure = onFailure,
                onPointSelected = onPointSelected,
                onCoordinateSelected = onCoordinateSelected,
            )
        },
    )
}
