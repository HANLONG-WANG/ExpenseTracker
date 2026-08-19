@file:Suppress(
    "ComplexCondition",
    "FunctionNaming",
    "LongMethod",
    "LongParameterList",
    "MatchingDeclarationName",
    "ktlint:standard:function-naming",
)

package app.ledger.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerCard
import app.ledger.core.designsystem.LedgerScaffold
import app.ledger.core.designsystem.LedgerStatusVariant
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.LedgerTopAppBar
import app.ledger.core.designsystem.LedgerTopAppBarVariant
import app.ledger.core.designsystem.StatusBadge
import app.ledger.core.navigation.LedgerDestinationKey
import app.ledger.feature.automation.AutomationLoadState
import app.ledger.feature.settlement.SettlementLoadState
import app.ledger.feature.transfer.BackupExecutionPresentation
import app.ledger.feature.transfer.ExportExecutionPresentation
import app.ledger.feature.transfer.RestoreProgressPresentation
import app.ledger.transfer.domain.BackgroundOperationState

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
    val automation by viewModel.automation.collectAsStateWithLifecycle()
    val settlement by viewModel.settlement.collectAsStateWithLifecycle()
    val durableOperations by viewModel.operationCenter.collectAsStateWithLifecycle()
    LaunchedEffect(key.contract.screenId.value) {
        viewModel.loadOperationCenter()
        viewModel.loadAutomation("AUT-001")
        viewModel.loadSettlement("SET-001")
    }
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
    val durable = (durableOperations as? OperationCenterLoadState.Content)?.operations.orEmpty()
    val hasDurableActive = durable.any { it.state in ACTIVE_OPERATION_STATES }
    val durableFailure = durable.any {
        it.state == BackgroundOperationState.FAILED_RETRYABLE || it.state == BackgroundOperationState.FAILED_FINAL
    }
    val backupFailure = backup.execution == BackupExecutionPresentation.FAILED
    val automationUpdates = (automation as? AutomationLoadState.Content)?.state?.snapshot?.candidates?.isNotEmpty() == true
    val settlementUpdates = (settlement as? SettlementLoadState.Content)?.state?.snapshot?.activities?.any {
        it.requiresAdditionalSettlement
    } == true
    val hasUpdates = durableFailure || backupFailure || automationUpdates || settlementUpdates
    val presentation = if (hasDurableActive || exportRunning || backupRunning || restoreRunning) {
        MorePresentation.OPERATION_IN_PROGRESS
    } else if (hasUpdates) {
        MorePresentation.BADGE_UPDATES
    } else {
        MorePresentation.CONTENT
    }
    MoreContent(
        presentation,
        onOperations,
        onHelp,
        automationUpdates = automationUpdates,
        settlementUpdates = settlementUpdates,
        backupUpdates = backupFailure,
        operationUpdates = durableFailure,
        onTransfer = {
            viewModel.openTransferHub(key)
            onNavigationChanged()
        },
        onSettings = {
            viewModel.navigateP12(key, "SETG-001", emptyMap())
            onNavigationChanged()
        },
        onManagement = {
            viewModel.navigateP12(key, "MGT-001", emptyMap())
            onNavigationChanged()
        },
        onCards = {
            viewModel.navigateP12(key, "ACC-009", emptyMap())
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
            viewModel.navigateLoan(MORE_CREDIT_DESTINATION, null, null)
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
        onVault = {
            viewModel.openVault()
            onNavigationChanged()
        },
        onAppLock = {
            viewModel.openSecurityPrivacySettings("SETG-006")
            onNavigationChanged()
        },
        onScreenPrivacy = {
            viewModel.openSecurityPrivacySettings("SETG-007")
            onNavigationChanged()
        },
        onTrash = {
            viewModel.openSecurityPrivacySettings("SETG-008")
            onNavigationChanged()
        },
        onDiagnostics = {
            viewModel.openSecurityPrivacySettings("SETG-009")
            onNavigationChanged()
        },
        onClearLocal = {
            viewModel.openSecurityPrivacySettings("CLR-001")
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
    onTransfer: () -> Unit = {},
    onSettings: () -> Unit = {},
    automationUpdates: Boolean = presentation == MorePresentation.BADGE_UPDATES,
    settlementUpdates: Boolean = false,
    backupUpdates: Boolean = false,
    operationUpdates: Boolean = false,
    onManagement: () -> Unit = {},
    onCards: () -> Unit = {},
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
    onVault: () -> Unit = {},
    onAppLock: () -> Unit = {},
    onScreenPrivacy: () -> Unit = {},
    onTrash: () -> Unit = {},
    onDiagnostics: () -> Unit = {},
    onClearLocal: () -> Unit = {},
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
            onTransfer = onTransfer,
            onSettings = onSettings,
            automationUpdates = automationUpdates,
            settlementUpdates = settlementUpdates,
            backupUpdates = backupUpdates,
            operationUpdates = operationUpdates,
            modifier = Modifier.padding(padding),
            onManagement = onManagement,
            onCards = onCards,
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
            onVault = onVault,
            onAppLock = onAppLock,
            onScreenPrivacy = onScreenPrivacy,
            onTrash = onTrash,
            onDiagnostics = onDiagnostics,
            onClearLocal = onClearLocal,
        )
    }
}

@Composable
internal fun MoreContent(
    presentation: MorePresentation,
    onOperations: () -> Unit,
    onHelp: () -> Unit,
    onTransfer: () -> Unit = {},
    onSettings: () -> Unit = {},
    automationUpdates: Boolean = presentation == MorePresentation.BADGE_UPDATES,
    settlementUpdates: Boolean = false,
    backupUpdates: Boolean = false,
    operationUpdates: Boolean = false,
    modifier: Modifier = Modifier,
    onManagement: () -> Unit = {},
    onCards: () -> Unit = {},
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
    onVault: () -> Unit = {},
    onAppLock: () -> Unit = {},
    onScreenPrivacy: () -> Unit = {},
    onTrash: () -> Unit = {},
    onDiagnostics: () -> Unit = {},
    onClearLocal: () -> Unit = {},
) {
    val groups = listOf(
        FeatureGroup(
            R.string.global_group_planning,
            listOf(
                FeatureEntry(stringResource(R.string.global_projects), stringResource(R.string.global_projects_explanation), onProjects),
                FeatureEntry(stringResource(R.string.global_goals), stringResource(R.string.global_goals_explanation), onGoals),
            ),
        ),
        FeatureGroup(
            R.string.global_group_liabilities,
            listOf(
                FeatureEntry(stringResource(R.string.global_credit), stringResource(R.string.global_credit_explanation), onCredit),
                FeatureEntry(stringResource(R.string.global_installments), stringResource(R.string.global_installments_explanation), onInstallments),
                FeatureEntry(stringResource(R.string.global_loans), stringResource(R.string.global_loans_explanation), onLoans),
            ),
        ),
        FeatureGroup(
            R.string.global_group_settlement,
            listOf(
                FeatureEntry(
                    stringResource(R.string.global_settlements),
                    stringResource(R.string.global_settlements_explanation),
                    onSettlements,
                    stringResource(R.string.global_status_updates).takeIf { settlementUpdates },
                ),
            ),
        ),
        FeatureGroup(
            R.string.global_group_automation,
            listOf(
                FeatureEntry(
                    stringResource(R.string.global_automation),
                    stringResource(R.string.global_automation_explanation),
                    onAutomation,
                    stringResource(R.string.global_status_updates).takeIf { automationUpdates },
                ),
            ),
        ),
        FeatureGroup(
            R.string.global_group_data,
            listOf(
                FeatureEntry(stringResource(R.string.global_transfer_center), stringResource(R.string.global_transfer_center_explanation), onTransfer),
                FeatureEntry(stringResource(R.string.global_import), stringResource(R.string.global_import_explanation), onImport),
                FeatureEntry(stringResource(R.string.global_import_history), stringResource(R.string.global_import_history_explanation), onImportHistory),
                FeatureEntry(stringResource(R.string.global_export), stringResource(R.string.global_export_explanation), onExport),
                FeatureEntry(
                    stringResource(R.string.global_backup),
                    stringResource(R.string.global_backup_explanation),
                    onBackup,
                    stringResource(R.string.global_status_updates).takeIf { backupUpdates },
                ),
                FeatureEntry(stringResource(R.string.global_restore), stringResource(R.string.global_restore_explanation), onRestore),
                FeatureEntry(
                    stringResource(R.string.global_operations),
                    stringResource(R.string.global_operations_explanation),
                    onOperations,
                    when {
                        presentation == MorePresentation.OPERATION_IN_PROGRESS -> stringResource(R.string.global_status_running)
                        operationUpdates -> stringResource(R.string.global_status_updates)
                        else -> null
                    },
                ),
            ),
        ),
        FeatureGroup(
            R.string.global_group_reference,
            listOf(
                FeatureEntry(stringResource(R.string.global_management), stringResource(R.string.global_management_explanation), onManagement),
                FeatureEntry(stringResource(R.string.p12_title_cards), stringResource(R.string.global_cards_explanation), onCards),
                FeatureEntry(stringResource(R.string.global_vault), stringResource(R.string.global_vault_explanation), onVault),
            ),
        ),
        FeatureGroup(
            R.string.global_group_settings,
            listOf(
                FeatureEntry(stringResource(R.string.global_settings), stringResource(R.string.global_settings_explanation), onSettings),
                FeatureEntry(stringResource(R.string.global_currencies), stringResource(R.string.global_currencies_explanation), onCurrencies),
                FeatureEntry(stringResource(R.string.global_app_lock_settings), stringResource(R.string.global_app_lock_settings_explanation), onAppLock),
                FeatureEntry(stringResource(R.string.global_screen_privacy), stringResource(R.string.global_screen_privacy_explanation), onScreenPrivacy),
                FeatureEntry(stringResource(R.string.global_trash_settings), stringResource(R.string.global_trash_settings_explanation), onTrash),
                FeatureEntry(stringResource(R.string.global_diagnostics), stringResource(R.string.global_diagnostics_explanation), onDiagnostics),
                FeatureEntry(stringResource(R.string.global_clear_local), stringResource(R.string.global_clear_local_explanation), onClearLocal),
                FeatureEntry(stringResource(R.string.global_delete_cloud_backups), stringResource(R.string.global_delete_cloud_backups_explanation), onDeleteCloudBackups),
                FeatureEntry(stringResource(R.string.global_help), stringResource(R.string.global_help_explanation), onHelp),
            ),
        ),
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
        groups.forEach { group ->
            item { LedgerText(stringResource(group.title), LedgerTextRole.SECTION) }
            items(group.entries) { entry -> FeatureHubItem(entry.title, entry.explanation, entry.onClick, entry.status) }
        }
    }
}

@Composable
private fun FeatureHubItem(title: String, explanation: String, onClick: () -> Unit, status: String? = null) {
    LedgerCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm)) {
            Column(Modifier.weight(1f)) {
                LedgerText(title, LedgerTextRole.SECTION)
                LedgerText(explanation, LedgerTextRole.SUPPORTING)
            }
            if (status != null) StatusBadge(status, LedgerStatusVariant.WARNING)
        }
    }
}

private data class FeatureGroup(val title: Int, val entries: List<FeatureEntry>)

private data class FeatureEntry(
    val title: String,
    val explanation: String,
    val onClick: () -> Unit,
    val status: String? = null,
)

internal val ACTIVE_OPERATION_STATES = setOf(
    BackgroundOperationState.QUEUED,
    BackgroundOperationState.PREPARING,
    BackgroundOperationState.RUNNING,
    BackgroundOperationState.PAUSED,
    BackgroundOperationState.CANCEL_REQUESTED,
    BackgroundOperationState.COMMITTING,
    BackgroundOperationState.ROLLING_BACK,
)

internal const val MORE_CREDIT_DESTINATION = "LIA-001"
