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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerButton
import app.ledger.core.designsystem.LedgerButtonVariant
import app.ledger.core.designsystem.LedgerIcon
import app.ledger.core.designsystem.LedgerIconButton
import app.ledger.core.designsystem.LedgerDialog
import app.ledger.core.designsystem.LocalLedgerFormChangeReporter
import app.ledger.core.designsystem.LocalLedgerAmountsVisible
import app.ledger.core.designsystem.LocalLedgerRetainedStateScopeKey
import app.ledger.core.designsystem.LocalLedgerRetainedStateStore
import app.ledger.core.designsystem.LocalLedgerScrollToTopRequest
import app.ledger.core.designsystem.LocalLedgerRestoredScrollState
import app.ledger.core.designsystem.LocalLedgerScrollStateReporter
import app.ledger.core.designsystem.LedgerHaptic
import app.ledger.core.designsystem.LedgerNavigationBar
import app.ledger.core.designsystem.LedgerScaffold
import app.ledger.core.designsystem.LedgerSnackbarController
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerTopAppBar
import app.ledger.core.designsystem.LedgerTopAppBarVariant
import app.ledger.core.designsystem.LedgerTopLevel
import app.ledger.core.designsystem.performLedgerHaptic
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
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.setRecordLocationHostForeground(true)
                Lifecycle.Event.ON_STOP -> viewModel.setRecordLocationHostForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        viewModel.setRecordLocationHostForeground(
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
        )
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setRecordLocationHostForeground(false)
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.successHapticEvents.collect {
            haptic.performLedgerHaptic(LedgerHaptic.SUCCESS)
        }
    }
    val navigator = viewModel.navigator
    var navigationEpoch by remember { mutableIntStateOf(0) }
    val persistedSettings by viewModel.settings.collectAsStateWithLifecycle()
    var accountAmountsVisible by rememberSaveable { mutableStateOf(!persistedSettings.defaultAmountsHidden) }
    LaunchedEffect(persistedSettings.defaultAmountsHidden) {
        accountAmountsVisible = !persistedSettings.defaultAmountsHidden
    }
    // FiveStackNavigator is deliberately platform-independent rather than SnapshotState-backed.
    // Reading the epoch while deriving navigation values makes every stack mutation invalidate
    // the whole shell (top bar, destination and selected bottom item), not only NavDisplay.
    val navigatorVersion = navigator.version
    val currentTopLevel = (navigationEpoch + navigatorVersion).let { navigator.currentTopLevel }
    val selected = currentTopLevel.toDesignTopLevel()
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
    val referenceState by viewModel.referenceData.collectAsStateWithLifecycle()
    val referencePending by viewModel.referenceMutationPending.collectAsStateWithLifecycle()
    val recordState by viewModel.ordinaryRecord.collectAsStateWithLifecycle()
    val recordPending by viewModel.ordinaryRecordPending.collectAsStateWithLifecycle()
    val batchState by viewModel.batchRecord.collectAsStateWithLifecycle()
    val specializedState by viewModel.specializedTransaction.collectAsStateWithLifecycle()
    val specializedPending by viewModel.specializedTransactionPending.collectAsStateWithLifecycle()
    val refundState by viewModel.refund.collectAsStateWithLifecycle()
    val refundPending by viewModel.refundPending.collectAsStateWithLifecycle()
    val currencySettings by viewModel.currencySettings.collectAsStateWithLifecycle()
    val journalState by viewModel.journal.collectAsStateWithLifecycle()
    val creditState by viewModel.credit.collectAsStateWithLifecycle()
    val creditPending by viewModel.creditPending.collectAsStateWithLifecycle()
    val installmentState by viewModel.installment.collectAsStateWithLifecycle()
    val installmentPending by viewModel.installmentPending.collectAsStateWithLifecycle()
    val loanState by viewModel.loan.collectAsStateWithLifecycle()
    val loanPending by viewModel.loanPending.collectAsStateWithLifecycle()
    val settlementState by viewModel.settlement.collectAsStateWithLifecycle()
    val settlementPending by viewModel.settlementPending.collectAsStateWithLifecycle()
    val automationState by viewModel.automation.collectAsStateWithLifecycle()
    val automationPending by viewModel.automationPending.collectAsStateWithLifecycle()
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
    val currentScreenId = navigator.currentKey.contract.screenId.value
    val currentRouteScope = viewModel.currentRouteScopeKey()
    val generalUnsavedPrompt by viewModel.generalUnsavedPrompt.collectAsStateWithLifecycle()
    SideEffect { viewModel.syncRetainedFormScopes(viewModel.activeRouteScopeKeys()) }
    CompositionLocalProvider(
        LocalLedgerAmountsVisible provides accountAmountsVisible,
        LocalLedgerRetainedStateStore provides viewModel.retainedFormStateStore,
        LocalLedgerRetainedStateScopeKey provides currentRouteScope,
        LocalLedgerScrollToTopRequest provides navigator.scrollRootRequestVersion(currentTopLevel),
        LocalLedgerRestoredScrollState provides viewModel.scrollState(currentTopLevel),
        LocalLedgerScrollStateReporter provides { key, offset ->
            viewModel.updateScrollState(currentTopLevel, key, offset)
        },
    ) {
        LedgerScaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarController = snackbarController,
        contentHorizontalPadding = currentScreenId !in SELF_SCAFFOLDED_SCREEN_IDS &&
            SELF_SCAFFOLDED_SCREEN_PREFIXES.none(currentScreenId::startsWith),
        topBar = {
            val key = navigator.currentKey
            if (key.contract.screenId.value in GOVERNED_DIALOG_DESTINATIONS || key.contract.screenId.value in GOVERNED_SHEET_DESTINATIONS) {
                return@LedgerScaffold
            }
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
                    ?: loanDestinationTitleOrNull(key.contract.screenId.value, viewModel.liabilityCreditOnly)
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
            settlementState,
            settlementPending,
            viewModel::saveSettlement,
        ) ?: automationFixedAction(
            navigator.currentKey.contract.screenId.value,
            automationState,
            automationPending,
            viewModel::saveAutomationBlueprint,
            viewModel::saveAutomationRecurrence,
        ) ?: loanFixedAction(
            navigator.currentKey.contract.screenId.value,
            loanState,
            loanPending,
            viewModel::saveLoan,
        ) ?: installmentFixedAction(
            navigator.currentKey.contract.screenId.value,
            installmentState,
            installmentPending,
            viewModel::saveInstallment,
        ) ?: creditFixedAction(
            navigator.currentKey.contract.screenId.value,
            creditState,
            creditPending,
            viewModel::saveCredit,
        ) ?: refundFixedAction(
            navigator.currentKey.contract.screenId.value,
            refundState,
            refundPending,
            viewModel::saveRefund,
        ) ?: specializedTransactionFixedAction(
            navigator.currentKey.contract.screenId.value,
            specializedState,
            specializedPending,
            viewModel::saveSpecializedTransaction,
        ) ?: ordinaryRecordFixedAction(
            navigator.currentKey.contract.screenId.value,
            recordState,
            recordPending,
            viewModel::saveOrdinaryRecord,
        ),
        fixedActionOverlaysContent = currentScreenId == "REC-003",
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
        val navigateToHelpTopic: (String) -> Unit = { topic ->
            val helpScreenId = ScreenId("G-008")
            navigator.navigate(
                LedgerRouteContract.destination(
                    helpScreenId,
                    mapOf("topicKey" to LedgerRouteContract.opaqueKeyArgument(helpScreenId, "topicKey", topic)),
                ),
                SessionGateState.READY,
            )
            navigationEpoch += 1
        }
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
                visibleCurrencyCodes = persistedSettings.visibleCurrencyCodesList,
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
                    navigateToHelpTopic("getting-started")
                },
                onPrivacyPolicy = { navigateToHelpTopic("privacy") },
                onNavigationChanged = { navigationEpoch += 1 },
            )
        }
        CompositionLocalProvider(
            LocalLedgerFormChangeReporter provides if (currentScreenId in DIRTY_TRACKED_SCREEN_IDS) {
                viewModel::markCurrentFormDirty
            } else {
                {}
            },
        ) {
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
                        screenId.startsWith("IMP-") -> ImportRootDestination(viewModel, onNavigationChanged = { navigationEpoch += 1 })
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
        if (generalUnsavedPrompt) {
            LedgerDialog(
                title = stringResource(R.string.global_unsaved_title),
                message = stringResource(R.string.global_unsaved_message),
                confirmLabel = stringResource(R.string.global_discard_changes),
                onConfirm = viewModel::discardGeneralFormChanges,
                onDismiss = viewModel::keepEditingGeneralForm,
                danger = true,
                dismissLabel = stringResource(R.string.global_keep_editing),
            )
        }
    }
}

private val SELF_SCAFFOLDED_SCREEN_PREFIXES = setOf("IMP-", "EXP-", "BKP-", "RST-")

private val SELF_SCAFFOLDED_SCREEN_IDS = setOf(
    "CLR-002",
    "SYS-003",
    "CAT-002",
    "MER-002",
    "PLC-002",
    "PRJ-002",
    "GOL-002",
    "GOL-004",
    "BUD-002",
    "BUD-003",
    "BUD-005",
    "BUD-008",
    "ANA-004",
    "ANA-007",
    "ANA-008",
    "ANA-011",
    "ANA-013",
    "VLT-003",
)

private val DIRTY_TRACKED_SCREEN_IDS = setOf(
    "REC-013", "REC-015", "REC-016", "REC-020", "REC-021", "REC-022", "REC-023", "REC-024", "REC-025",
    "ACC-003", "ACC-004", "ACC-007", "ACC-010", "CAT-002", "CAT-003", "CAT-004", "MER-002", "MER-003", "PLC-002", "PLC-003",
    "BUD-002", "BUD-004", "BUD-007", "BUD-008", "PRJ-002", "GOL-002", "GOL-004",
    "CRD-002", "CRD-005", "CRD-006", "CRD-007", "CRD-008", "REC-014", "INS-002", "INS-004", "INS-006", "REC-027",
    "LOA-002", "LOA-003", "LOA-004", "LOA-005", "REC-018", "REC-019",
    "SET-002", "SET-003", "SET-006", "AUT-003", "AUT-005", "AUT-006", "AUT-010",
    "ANA-004", "ANA-007", "ANA-008", "ANA-009", "ANA-010", "ANA-011", "ANA-013", "VLT-003",
    "JRN-003", "JRN-005", "JRN-006", "JRN-012", "EXP-001", "BKP-002", "BKP-003", "BKP-004", "RST-002", "RST-004", "RST-005",
)

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
