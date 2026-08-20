@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package app.ledger.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ledger.feature.transfer.ImportWizardActions
import app.ledger.feature.transfer.ImportWizardScreen

@Composable
internal fun ImportRootDestination(viewModel: AppRootViewModel, onNavigationChanged: () -> Unit) {
    val state by viewModel.importWizard.collectAsStateWithLifecycle()
    ImportWizardScreen(
        state,
        ImportWizardActions(
            onBack = viewModel::requestRootBack,
            onSourceSelected = viewModel::selectImportSource,
            onModeSelected = viewModel::selectImportMode,
            onSheetSelected = viewModel::selectImportSheet,
            onEncodingChanged = viewModel::changeImportEncoding,
            onHeaderRowChanged = viewModel::changeImportHeaderRow,
            onCycleFieldMapping = viewModel::cycleImportFieldMapping,
            onCreateMissingChanged = viewModel::changeImportMissingCreation,
            onCycleEntityMapping = viewModel::cycleImportEntityMapping,
            onFxPolicyChanged = viewModel::changeImportFxPolicy,
            onFxRateChanged = viewModel::changeImportFxRate,
            onDuplicateResolved = viewModel::resolveImportDuplicate,
            onPrevious = viewModel::previousImportStage,
            onNext = viewModel::nextImportStage,
            onPause = viewModel::pauseImport,
            onCancel = viewModel::cancelImport,
            onRetry = viewModel::retryImport,
            onRollback = viewModel::rollbackImport,
            onOpenJournal = { viewModel.selectRootTopLevel(app.ledger.core.navigation.TopLevelDestination.JOURNAL) },
            onShowHistory = viewModel::navigateImportHistory,
            onViewValidationIssues = viewModel::viewImportValidationIssues,
            onCleanupTemporary = viewModel::cleanupImportTemporary,
            onViewHistoryResult = viewModel::viewImportHistoryResult,
            onRollbackHistory = viewModel::rollbackImportHistory,
        ),
    )
}
