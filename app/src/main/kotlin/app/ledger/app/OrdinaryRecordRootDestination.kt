@file:Suppress("ktlint:standard:function-naming", "FunctionNaming")

package app.ledger.app

import androidx.compose.runtime.Composable
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
            onOccurredAt = viewModel::updateRecordOccurredAt,
            onAddAttachment = onAddAttachment,
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
    )
}
