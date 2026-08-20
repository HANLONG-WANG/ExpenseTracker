@file:Suppress(
    "ComplexCondition",
    "CyclomaticComplexMethod",
    "FunctionNaming",
    "LongMethod",
    "ktlint:standard:function-naming",
)

package app.ledger.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerButton
import app.ledger.core.designsystem.LedgerButtonVariant
import app.ledger.core.designsystem.LedgerIcon
import app.ledger.core.designsystem.LedgerIconButton
import app.ledger.core.designsystem.LedgerNavigationBar
import app.ledger.core.designsystem.LedgerScaffold
import app.ledger.core.designsystem.LedgerSnackbarController
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerTopAppBar
import app.ledger.core.designsystem.LedgerTopAppBarVariant
import app.ledger.core.designsystem.LedgerTopLevel
import app.ledger.core.navigation.LedgerRouteContract
import app.ledger.core.navigation.LedgerDestinationKey
import app.ledger.core.navigation.LedgerScreenUiAction
import app.ledger.core.navigation.ScreenId
import app.ledger.core.navigation.SessionGateState
import app.ledger.core.navigation.TopLevelDestination
import app.ledger.feature.accounts.accountsDestinations
import app.ledger.feature.analysis.analysisDestinations
import app.ledger.feature.automation.automationDestinations
import app.ledger.feature.journal.journalDestinations
import app.ledger.feature.liabilities.liabilityDestinations
import app.ledger.feature.planning.planningDestinations
import app.ledger.feature.record.recordDestinations
import app.ledger.feature.settings.settingsDestinations
import app.ledger.feature.settlement.settlementDestinations
import app.ledger.feature.transfer.transferDestinations
import app.ledger.feature.vault.vaultDestinations
import app.ledger.feature.record.R as RecordR
import app.ledger.feature.journal.R as JournalR

/** Owns the five-stack Ready shell and keeps the root session dispatcher bounded. */
@Composable
internal fun ReadyRootScaffold(
    viewModel: AppRootViewModel,
    unsavedContentLossNotice: Boolean,
    snackbarController: LedgerSnackbarController,
) {
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(viewModel) {
        viewModel.successHapticEvents.collect {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    val navigator = viewModel.navigator
    var navigationEpoch by remember { mutableIntStateOf(0) }
    var accountAmountsVisible by rememberSaveable { mutableStateOf(true) }
    // FiveStackNavigator is deliberately platform-independent rather than SnapshotState-backed.
    // Reading the epoch while deriving navigation values makes every stack mutation invalidate
    // the whole shell (top bar, destination and selected bottom item), not only NavDisplay.
    val selected = navigationEpoch.let { navigator.currentTopLevel.toDesignTopLevel() }
    val dispatchScreenAction: (LedgerScreenUiAction) -> Unit = { action ->
        when (action) {
            LedgerScreenUiAction.Back -> viewModel.requestRootBack()
            is LedgerScreenUiAction.Navigate -> navigator.navigate(action.destination, SessionGateState.READY)
        }
        navigationEpoch += 1
    }
    SideEffect {
        app.ledger.core.designsystem.LedgerPerformanceRuntime.enter(
            when (viewModel.navigator.currentTopLevel) {
                app.ledger.core.navigation.TopLevelDestination.RECORD -> app.ledger.core.designsystem.LedgerPerformanceScene.RECORD
                app.ledger.core.navigation.TopLevelDestination.JOURNAL -> app.ledger.core.designsystem.LedgerPerformanceScene.JOURNAL
                app.ledger.core.navigation.TopLevelDestination.ACCOUNTS -> app.ledger.core.designsystem.LedgerPerformanceScene.ACCOUNTS
                app.ledger.core.navigation.TopLevelDestination.BUDGET -> app.ledger.core.designsystem.LedgerPerformanceScene.BUDGET
                app.ledger.core.navigation.TopLevelDestination.ANALYSIS -> app.ledger.core.designsystem.LedgerPerformanceScene.ANALYSIS
            },
        )
    }
    val referenceUiState by viewModel.referenceDataUiState.collectAsStateWithLifecycle()
    val recordUiState by viewModel.ordinaryRecordUiState.collectAsStateWithLifecycle()
    val batchState by viewModel.batchRecord.collectAsStateWithLifecycle()
    val specializedUiState by viewModel.specializedTransactionUiState.collectAsStateWithLifecycle()
    val refundUiState by viewModel.refundUiState.collectAsStateWithLifecycle()
    val currencySettings by viewModel.currencySettings.collectAsStateWithLifecycle()
    val journalUiState by viewModel.journalUiState.collectAsStateWithLifecycle()
    val creditUiState by viewModel.creditUiState.collectAsStateWithLifecycle()
    val installmentUiState by viewModel.installmentUiState.collectAsStateWithLifecycle()
    val loanUiState by viewModel.loanUiState.collectAsStateWithLifecycle()
    val settlementUiState by viewModel.settlementUiState.collectAsStateWithLifecycle()
    val automationUiState by viewModel.automationUiState.collectAsStateWithLifecycle()
    val launchAttachmentPicker = rememberRecordAttachmentPicker { uri ->
        if (viewModel.navigator.currentKey.contract.screenId.value == "REC-024") {
            viewModel.importBatchAttachment(uri)
        } else if (viewModel.navigator.currentKey.contract.screenId.value == "REC-013") {
            viewModel.importSpecializedAttachment(uri)
        } else {
            viewModel.importRecordAttachment(uri)
        }
    }
    SideEffect { viewModel.screenVisibilityChanged(navigator.currentKey.contract.screenId.value) }
    LedgerScaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarController = snackbarController,
        topBar = {
            val key = navigator.currentKey
            if (
                key.contract.screenId.value.startsWith("IMP-") ||
                key.contract.screenId.value.startsWith("EXP-") ||
                key.contract.screenId.value.startsWith("BKP-") ||
                key.contract.screenId.value.startsWith("RST-") ||
                key.contract.screenId.value == "CLR-002" ||
                key.contract.screenId.value == "SYS-003"
            ) {
                return@LedgerScaffold
            }
            val topLevel = key.contract.screenId.value in setOf("REC-001", "JRN-001", "ACC-001", "BUD-001", "ANA-001")
            LedgerTopAppBar(
                title = projectGoalDestinationTitleOrNull(key.contract.screenId.value)
                    ?: analysisDestinationTitleOrNull(key.contract.screenId.value)
                    ?: automationDestinationTitleOrNull(key.contract.screenId.value)
                    ?: settlementDestinationTitleOrNull(key.contract.screenId.value)
                    ?: budgetDestinationTitleOrNull(key.contract.screenId.value)
                    ?: refundDestinationTitleOrNull(key.contract.screenId.value)
                    ?: creditDestinationTitleOrNull(key.contract.screenId.value)
                    ?: installmentDestinationTitleOrNull(key.contract.screenId.value)
                    ?: loanDestinationTitleOrNull(key.contract.screenId.value)
                    ?: destinationTitle(key),
                variant = if (topLevel) LedgerTopAppBarVariant.TOP_LEVEL else LedgerTopAppBarVariant.BACK,
                onNavigation = {
                    viewModel.requestRootBack()
                    navigationEpoch += 1
                },
                actions = {
                    if (topLevel) {
                        if (key.contract.screenId.value == "REC-001") {
                            LedgerButton(
                                stringResource(RecordR.string.record_templates),
                                onClick = {
                                    navigator.navigate(LedgerRouteContract.destination(ScreenId("REC-026")), SessionGateState.READY)
                                    navigationEpoch += 1
                                },
                                variant = LedgerButtonVariant.TEXT,
                                compact = true,
                            )
                        }
                        if (key.contract.screenId.value == "JRN-001") {
                            LedgerIconButton(
                                LedgerIcon.SEARCH,
                                stringResource(JournalR.string.p15_journal_search),
                                onClick = {
                                    navigator.navigate(LedgerRouteContract.destination(ScreenId("JRN-002")), SessionGateState.READY)
                                    navigationEpoch += 1
                                },
                            )
                            LedgerButton(
                                stringResource(JournalR.string.p15_journal_filter),
                                onClick = {
                                    navigator.navigate(LedgerRouteContract.destination(ScreenId("JRN-003")), SessionGateState.READY)
                                    navigationEpoch += 1
                                },
                                variant = LedgerButtonVariant.TEXT,
                                compact = true,
                            )
                        }
                        if (key.contract.screenId.value == "ACC-001") {
                            LedgerButton(
                                stringResource(if (accountAmountsVisible) R.string.global_hide_amounts else R.string.global_show_amounts),
                                onClick = { accountAmountsVisible = !accountAmountsVisible },
                                variant = LedgerButtonVariant.TEXT,
                                compact = true,
                            )
                        }
                        LedgerIconButton(LedgerIcon.MORE, stringResource(R.string.global_more), onClick = {
                            navigator.navigate(LedgerRouteContract.destination(ScreenId("G-006")), SessionGateState.READY)
                            navigationEpoch += 1
                        })
                    }
                },
            )
        },
        fixedAction = settlementFixedAction(
            navigator.currentKey.contract.screenId.value,
            settlementUiState.loadState,
            settlementUiState.submitting,
            viewModel::saveSettlement,
        ) ?: automationFixedAction(
            navigator.currentKey.contract.screenId.value,
            automationUiState.loadState,
            automationUiState.submitting,
            viewModel::saveAutomationBlueprint,
            viewModel::saveAutomationRecurrence,
        ) ?: loanFixedAction(
            navigator.currentKey.contract.screenId.value,
            loanUiState.loadState,
            loanUiState.submitting,
            viewModel::saveLoan,
        ) ?: installmentFixedAction(
            navigator.currentKey.contract.screenId.value,
            installmentUiState.loadState,
            installmentUiState.submitting,
            viewModel::saveInstallment,
        ) ?: creditFixedAction(
            navigator.currentKey.contract.screenId.value,
            creditUiState.loadState,
            creditUiState.submitting,
            viewModel::saveCredit,
        ) ?: refundFixedAction(
            navigator.currentKey.contract.screenId.value,
            refundUiState.loadState,
            refundUiState.submitting,
            viewModel::saveRefund,
        ) ?: specializedTransactionFixedAction(
            navigator.currentKey.contract.screenId.value,
            specializedUiState.loadState,
            specializedUiState.submitting,
            viewModel::saveSpecializedTransaction,
        ) ?: ordinaryRecordFixedAction(
            navigator.currentKey.contract.screenId.value,
            recordUiState.loadState,
            recordUiState.submitting,
            viewModel::saveOrdinaryRecord,
        ),
        bottomBar = {
            if (navigator.isBottomNavigationVisible) {
                LedgerNavigationBar(selected = selected, onSelected = { target ->
                    viewModel.selectRootTopLevel(target.toNavigationTopLevel())
                    navigationEpoch += 1
                })
            }
        },
        banner = if (unsavedContentLossNotice) {
            {
                LedgerBanner(
                    stringResource(R.string.global_unsaved_lost),
                    LedgerBannerVariant.WARNING,
                    Modifier.testTag(LedgerTestTags.GLOBAL_BANNER),
                    actionLabel = stringResource(R.string.global_dismiss),
                    onAction = viewModel::dismissUnsavedContentLossNotice,
                )
            }
        } else {
            null
        },
    ) { padding ->
        val commonDestination: @Composable (LedgerDestinationKey) -> Unit = { key ->
            RootDestination(
                key,
                viewModel = viewModel,
                referenceState = referenceState,
                referencePending = referencePending,
                recordState = recordState,
                batchState = batchState,
                specializedState = specializedState,
                currencySettings = currencySettings,
                journalState = journalState,
                accountAmountsVisible = accountAmountsVisible,
                onAddAttachment = launchAttachmentPicker,
                onBack = {
                    navigator.pop()
                    navigationEpoch += 1
                },
                onMore = {
                    navigator.navigate(LedgerRouteContract.destination(ScreenId("G-006")), SessionGateState.READY)
                    navigationEpoch += 1
                },
                onOperations = {
                    navigator.navigate(LedgerRouteContract.destination(ScreenId("G-007")), SessionGateState.READY)
                    navigationEpoch += 1
                },
                onHelp = {
                    val helpScreenId = ScreenId("G-008")
                    navigator.navigate(
                        LedgerRouteContract.destination(
                            helpScreenId,
                            mapOf(
                                "topicKey" to LedgerRouteContract.opaqueKeyArgument(
                                    helpScreenId,
                                    "topicKey",
                                    "getting-started",
                                ),
                            ),
                        ),
                        SessionGateState.READY,
                    )
                    navigationEpoch += 1
                },
                onNavigationChanged = { navigationEpoch += 1 },
            )
        }
        NavDisplay(
            backStack = navigator.currentBackStack,
            onBack = {
                viewModel.requestRootBack()
                navigationEpoch += 1
            },
            modifier = Modifier.fillMaxSize().padding(padding),
            entryProvider = entryProvider(
                fallback = { key ->
                    NavEntry(key) {
                        val screenId = key.contract.screenId.value
                        when {
                            screenId.startsWith("ATT-") -> AttachmentRootDestination(
                                screenId,
                                key.encodedArguments,
                                viewModel,
                                onNavigationChanged = { navigationEpoch += 1 },
                            )
                            screenId == "SYS-001" -> LocationPermissionRootDestination(
                                viewModel,
                                onNavigationChanged = { navigationEpoch += 1 },
                            )
                            else -> commonDestination(key)
                        }
                    }
                },
            ) {
                transferDestinations(dispatchScreenAction) { screenState, _ ->
                    val key = screenState.destination
                    val screenId = key.contract.screenId.value
                    when {
                        screenId.startsWith("IMP-") -> ImportRootDestination(viewModel)
                        screenId.startsWith("EXP-") -> ExportRootDestination(screenId, viewModel, onNavigationChanged = { navigationEpoch += 1 })
                        screenId.startsWith("BKP-") || screenId == "SYS-003" -> BackupRootDestination(screenId, viewModel, onNavigationChanged = { navigationEpoch += 1 })
                        screenId.startsWith("RST-") || screenId == "CLR-002" -> RestoreRootDestination(screenId, viewModel, onNavigationChanged = { navigationEpoch += 1 })
                        else -> commonDestination(key)
                    }
                }
                analysisDestinations(dispatchScreenAction) { screenState, _ ->
                    val key = screenState.destination
                    AnalysisRootDestination(
                        key.contract.screenId.value,
                        key.encodedArguments,
                        viewModel,
                        onNavigationChanged = { navigationEpoch += 1 },
                    )
                }
                automationDestinations(dispatchScreenAction) { screenState, _ ->
                    val key = screenState.destination
                    AutomationRootDestination(
                        key.contract.screenId.value,
                        key.encodedArguments,
                        viewModel,
                        onNavigationChanged = { navigationEpoch += 1 },
                    )
                }
                settlementDestinations(dispatchScreenAction) { screenState, _ ->
                    val key = screenState.destination
                    SettlementRootDestination(
                        key.contract.screenId.value,
                        key.encodedArguments,
                        viewModel,
                        onNavigationChanged = { navigationEpoch += 1 },
                    )
                }
                liabilityDestinations(dispatchScreenAction) { screenState, _ ->
                    val key = screenState.destination
                    val screenId = key.contract.screenId.value
                    when {
                        screenId.startsWith("LOA-") || screenId == "LIA-001" || screenId in setOf("REC-017", "REC-018", "REC-019") -> {
                            LoanRootDestination(
                                screenId,
                                key.encodedArguments,
                                viewModel,
                                onNavigationChanged = { navigationEpoch += 1 },
                            )
                        }
                        screenId.startsWith("INS-") || screenId == "REC-027" -> {
                            InstallmentRootDestination(
                                screenId,
                                key.encodedArguments,
                                viewModel,
                                onNavigationChanged = { navigationEpoch += 1 },
                            )
                        }
                        screenId.startsWith("CRD-") || screenId == "REC-014" -> {
                            CreditRootDestination(
                                screenId,
                                key.encodedArguments,
                                viewModel,
                                onNavigationChanged = { navigationEpoch += 1 },
                            )
                        }
                        else -> commonDestination(key)
                    }
                }
                planningDestinations(dispatchScreenAction) { screenState, _ ->
                    val key = screenState.destination
                    val screenId = key.contract.screenId.value
                    if (screenId.startsWith("BUD-")) {
                        BudgetRootDestination(
                            screenId,
                            key.encodedArguments,
                            viewModel,
                            onNavigationChanged = { navigationEpoch += 1 },
                            onOperations = {
                                navigator.navigate(LedgerRouteContract.destination(ScreenId("G-007")), SessionGateState.READY)
                                navigationEpoch += 1
                            },
                        )
                    } else {
                        ProjectGoalRootDestination(
                            screenId,
                            key.encodedArguments,
                            viewModel,
                            onNavigationChanged = { navigationEpoch += 1 },
                        )
                    }
                }
                recordDestinations(dispatchScreenAction) { screenState, _ ->
                    val key = screenState.destination
                    val screenId = key.contract.screenId.value
                    when {
                        screenId == "REC-014" -> CreditRootDestination(
                            screenId,
                            key.encodedArguments,
                            viewModel,
                            onNavigationChanged = { navigationEpoch += 1 },
                        )
                        screenId == "REC-015" || screenId == "REC-016" -> RefundRootDestination(
                            screenId = screenId,
                            encodedArguments = key.encodedArguments,
                            viewModel = viewModel,
                            onNavigationChanged = { navigationEpoch += 1 },
                        )
                        screenId in setOf("REC-017", "REC-018", "REC-019") -> LoanRootDestination(
                            screenId,
                            key.encodedArguments,
                            viewModel,
                            onNavigationChanged = { navigationEpoch += 1 },
                        )
                        else -> commonDestination(key)
                    }
                }
                journalDestinations(dispatchScreenAction) { state, _ -> commonDestination(state.destination) }
                accountsDestinations(dispatchScreenAction) { state, _ -> commonDestination(state.destination) }
                settingsDestinations(dispatchScreenAction) { state, _ -> commonDestination(state.destination) }
                vaultDestinations(dispatchScreenAction) { state, _ -> commonDestination(state.destination) }
            },
        )
    }
}

private fun TopLevelDestination.toDesignTopLevel(): LedgerTopLevel = if (this == TopLevelDestination.RECORD) {
    LedgerTopLevel.RECORD
} else if (this == TopLevelDestination.JOURNAL) {
    LedgerTopLevel.JOURNAL
} else if (this == TopLevelDestination.ACCOUNTS) {
    LedgerTopLevel.ACCOUNTS
} else if (this == TopLevelDestination.BUDGET) {
    LedgerTopLevel.BUDGET
} else {
    LedgerTopLevel.ANALYSIS
}

private fun LedgerTopLevel.toNavigationTopLevel(): TopLevelDestination = if (this == LedgerTopLevel.RECORD) {
    TopLevelDestination.RECORD
} else if (this == LedgerTopLevel.JOURNAL) {
    TopLevelDestination.JOURNAL
} else if (this == LedgerTopLevel.ACCOUNTS) {
    TopLevelDestination.ACCOUNTS
} else if (this == LedgerTopLevel.BUDGET) {
    TopLevelDestination.BUDGET
} else {
    TopLevelDestination.ANALYSIS
}
