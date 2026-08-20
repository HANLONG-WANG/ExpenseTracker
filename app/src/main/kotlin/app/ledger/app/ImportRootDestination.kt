@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package app.ledger.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ledger.feature.transfer.ImportWizardScreen
import app.ledger.feature.transfer.ImportWizardScreenAction

@Composable
internal fun ImportRootDestination(viewModel: AppRootViewModel, onNavigationChanged: () -> Unit) {
    val state by viewModel.importWizard.collectAsStateWithLifecycle()
    ImportWizardScreen(
        state,
        { action ->
            when (action) {
                ImportWizardScreenAction.Back -> viewModel.exitImport(onNavigationChanged)
                is ImportWizardScreenAction.SourceSelected -> viewModel.selectImportSource(action.uri)
                is ImportWizardScreenAction.ModeSelected -> viewModel.selectImportMode(action.mode)
                is ImportWizardScreenAction.SheetSelected -> viewModel.selectImportSheet(action.sheet)
                is ImportWizardScreenAction.EncodingChanged -> viewModel.changeImportEncoding(action.encoding)
                is ImportWizardScreenAction.HeaderRowChanged -> viewModel.changeImportHeaderRow(action.value)
                is ImportWizardScreenAction.CycleFieldMapping -> viewModel.cycleImportFieldMapping(action.sourceField)
                is ImportWizardScreenAction.CreateMissingChanged -> viewModel.changeImportMissingCreation(action.entity, action.enabled)
                is ImportWizardScreenAction.FxRateChanged -> viewModel.changeImportFxRate(action.currency, action.value)
                is ImportWizardScreenAction.DuplicateResolved -> viewModel.resolveImportDuplicate(action.row, action.resolution)
                ImportWizardScreenAction.Previous -> viewModel.previousImportStage()
                ImportWizardScreenAction.Next -> viewModel.nextImportStage()
                ImportWizardScreenAction.Pause -> viewModel.pauseImport()
                ImportWizardScreenAction.Cancel -> viewModel.cancelImport()
                ImportWizardScreenAction.Retry -> viewModel.retryImport()
                ImportWizardScreenAction.Rollback -> viewModel.rollbackImport()
                ImportWizardScreenAction.OpenJournal -> viewModel.selectRootTopLevel(app.ledger.core.navigation.TopLevelDestination.JOURNAL)
            }
        },
    )
}
