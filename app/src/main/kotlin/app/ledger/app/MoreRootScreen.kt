@file:Suppress(
    "FunctionNaming",
    "LongParameterList",
    "MatchingDeclarationName",
    "ktlint:standard:function-naming",
)

package app.ledger.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerCard
import app.ledger.core.designsystem.LedgerScaffold
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.LedgerTopAppBar
import app.ledger.core.designsystem.LedgerTopAppBarVariant
import app.ledger.core.navigation.LedgerDestinationKey
import app.ledger.feature.transfer.BackupExecutionPresentation
import app.ledger.feature.transfer.ExportExecutionPresentation
import app.ledger.feature.transfer.RestoreProgressPresentation

internal enum class MorePresentation { CONTENT, BADGE_UPDATES, OPERATION_IN_PROGRESS }

@Composable
internal fun MoreRootDestination(
    viewModel: AppRootViewModel,
    key: LedgerDestinationKey,
    onOperations: () -> Unit,
    onHelp: () -> Unit,
    onNavigationChanged: () -> Unit,
) {
    val export by viewModel.exportFlow.collectAsStateWithLifecycle()
    val backup by viewModel.backupFlow.collectAsStateWithLifecycle()
    val restore by viewModel.restoreFlow.collectAsStateWithLifecycle()
    val exportRunning = export.screenId == "EXP-004" && export.executionPresentation in setOf(
        ExportExecutionPresentation.RUNNING,
        ExportExecutionPresentation.CANCEL_REQUESTED,
    )
    val backupRunning = backup.execution in setOf(
        BackupExecutionPresentation.RUNNING,
        BackupExecutionPresentation.CANCEL_REQUESTED,
    )
    val restoreRunning = restore.screenId == "RST-006" &&
        restore.progressPresentation == RestoreProgressPresentation.RUNNING
    val presentation = if (exportRunning || backupRunning || restoreRunning) {
        MorePresentation.OPERATION_IN_PROGRESS
    } else {
        MorePresentation.CONTENT
    }
    MoreContent(
        presentation,
        onOperations,
        onHelp,
        onManagement = {
            viewModel.navigateP12(key, "MGT-001", emptyMap())
            onNavigationChanged()
        },
        onCurrencies = {
            viewModel.navigateP12(key, "SETG-004", emptyMap())
            onNavigationChanged()
        },
        onProjects = {
            viewModel.navigateProjectGoal("PRJ-001", null, null)
            onNavigationChanged()
        },
        onGoals = {
            viewModel.navigateProjectGoal("GOL-001", null, null)
            onNavigationChanged()
        },
        onCredit = {
            viewModel.navigateCredit("CRD-001", null)
            onNavigationChanged()
        },
        onInstallments = {
            viewModel.navigateInstallment("INS-001", null)
            onNavigationChanged()
        },
        onLoans = {
            viewModel.navigateLoan("LIA-001", null, null)
            onNavigationChanged()
        },
        onSettlements = {
            viewModel.navigateSettlement("SET-001", null)
            onNavigationChanged()
        },
        onAutomation = {
            viewModel.navigateAutomation("AUT-001", null)
            onNavigationChanged()
        },
        onImport = {
            viewModel.navigateImportSource()
            onNavigationChanged()
        },
        onImportHistory = {
            viewModel.navigateImportHistory()
            onNavigationChanged()
        },
        onExport = {
            viewModel.navigateCurrentFilterExport()
            onNavigationChanged()
        },
        onBackup = {
            viewModel.openBackup()
            onNavigationChanged()
        },
        onRestore = {
            viewModel.openRestore()
            onNavigationChanged()
        },
        onDeleteCloudBackups = {
            viewModel.openCloudBackupDeletion()
            onNavigationChanged()
        },
    )
}

@Composable
internal fun MoreScreen(
    presentation: MorePresentation,
    onBack: () -> Unit,
    onOperations: () -> Unit,
    onHelp: () -> Unit,
    onManagement: () -> Unit = {},
    onProjects: () -> Unit = {},
    onGoals: () -> Unit = {},
    onCredit: () -> Unit = {},
    onInstallments: () -> Unit = {},
    onLoans: () -> Unit = {},
    onSettlements: () -> Unit = {},
    onAutomation: () -> Unit = {},
    onImport: () -> Unit = {},
    onImportHistory: () -> Unit = {},
    onExport: () -> Unit = {},
    onBackup: () -> Unit = {},
    onRestore: () -> Unit = {},
    onDeleteCloudBackups: () -> Unit = {},
) {
    LedgerScaffold(
        Modifier.fillMaxSize(),
        topBar = {
            LedgerTopAppBar(
                stringResource(R.string.global_more_title),
                LedgerTopAppBarVariant.BACK,
                onNavigation = onBack,
            )
        },
    ) { padding ->
        MoreContent(
            presentation = presentation,
            onOperations = onOperations,
            onHelp = onHelp,
            modifier = Modifier.padding(padding),
            onManagement = onManagement,
            onProjects = onProjects,
            onGoals = onGoals,
            onCredit = onCredit,
            onInstallments = onInstallments,
            onLoans = onLoans,
            onSettlements = onSettlements,
            onAutomation = onAutomation,
            onImport = onImport,
            onImportHistory = onImportHistory,
            onExport = onExport,
            onBackup = onBackup,
            onRestore = onRestore,
            onDeleteCloudBackups = onDeleteCloudBackups,
        )
    }
}

@Composable
internal fun MoreContent(
    presentation: MorePresentation,
    onOperations: () -> Unit,
    onHelp: () -> Unit,
    modifier: Modifier = Modifier,
    onManagement: () -> Unit = {},
    onCurrencies: () -> Unit = {},
    onProjects: () -> Unit = {},
    onGoals: () -> Unit = {},
    onCredit: () -> Unit = {},
    onInstallments: () -> Unit = {},
    onLoans: () -> Unit = {},
    onSettlements: () -> Unit = {},
    onAutomation: () -> Unit = {},
    onImport: () -> Unit = {},
    onImportHistory: () -> Unit = {},
    onExport: () -> Unit = {},
    onBackup: () -> Unit = {},
    onRestore: () -> Unit = {},
    onDeleteCloudBackups: () -> Unit = {},
) {
    val entries = listOf(
        Triple(stringResource(R.string.global_operations), stringResource(R.string.global_operations_explanation), onOperations),
        Triple(stringResource(R.string.global_help), stringResource(R.string.global_help_explanation), onHelp),
        Triple(stringResource(R.string.global_management), stringResource(R.string.global_management_explanation), onManagement),
        Triple(stringResource(R.string.global_currencies), stringResource(R.string.global_currencies_explanation), onCurrencies),
        Triple(stringResource(R.string.global_projects), stringResource(R.string.global_projects_explanation), onProjects),
        Triple(stringResource(R.string.global_goals), stringResource(R.string.global_goals_explanation), onGoals),
        Triple(stringResource(R.string.global_credit), stringResource(R.string.global_credit_explanation), onCredit),
        Triple(stringResource(R.string.global_installments), stringResource(R.string.global_installments_explanation), onInstallments),
        Triple(stringResource(R.string.global_loans), stringResource(R.string.global_loans_explanation), onLoans),
        Triple(stringResource(R.string.global_settlements), stringResource(R.string.global_settlements_explanation), onSettlements),
        Triple(stringResource(R.string.global_automation), stringResource(R.string.global_automation_explanation), onAutomation),
        Triple(stringResource(R.string.global_import), stringResource(R.string.global_import_explanation), onImport),
        Triple(stringResource(R.string.global_import_history), stringResource(R.string.global_import_history_explanation), onImportHistory),
        Triple(stringResource(R.string.global_export), stringResource(R.string.global_export_explanation), onExport),
        Triple(stringResource(R.string.global_backup), stringResource(R.string.global_backup_explanation), onBackup),
        Triple(stringResource(R.string.global_restore), stringResource(R.string.global_restore_explanation), onRestore),
        Triple(stringResource(R.string.global_delete_cloud_backups), stringResource(R.string.global_delete_cloud_backups_explanation), onDeleteCloudBackups),
    )
    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        if (presentation == MorePresentation.BADGE_UPDATES) {
            item {
                LedgerBanner(stringResource(R.string.global_badge_updates), LedgerBannerVariant.INFO)
            }
        }
        if (presentation == MorePresentation.OPERATION_IN_PROGRESS) {
            item {
                LedgerBanner(stringResource(R.string.global_active_operation), LedgerBannerVariant.WARNING)
            }
        }
        items(entries) { entry -> FeatureHubItem(entry.first, entry.second, entry.third) }
    }
}

@Composable
private fun FeatureHubItem(title: String, explanation: String, onClick: () -> Unit) {
    LedgerCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm)) {
            LedgerText(title, LedgerTextRole.SECTION)
            LedgerText(explanation, LedgerTextRole.SUPPORTING)
        }
    }
}
