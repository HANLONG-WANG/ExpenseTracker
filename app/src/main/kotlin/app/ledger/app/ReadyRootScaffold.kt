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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
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
import app.ledger.core.navigation.ScreenId
import app.ledger.core.navigation.SessionGateState
import app.ledger.core.navigation.TopLevelDestination

/** Owns the five-stack Ready shell and keeps the root session dispatcher bounded. */
@Composable
internal fun ReadyRootScaffold(
    viewModel: AppRootViewModel,
    unsavedContentLossNotice: Boolean,
    snackbarController: LedgerSnackbarController,
) {
    val navigator = viewModel.navigator
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
    var navigationEpoch by remember { mutableIntStateOf(0) }
    SideEffect { viewModel.screenVisibilityChanged(navigator.currentKey.contract.screenId.value) }
    val selected = navigator.currentTopLevel.toDesignTopLevel()
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
        navigationEpoch
        NavDisplay(
            backStack = navigator.currentBackStack,
            onBack = {
                viewModel.requestRootBack()
                navigationEpoch += 1
            },
            modifier = Modifier.fillMaxSize().padding(padding),
            entryProvider = { key ->
                NavEntry(key) {
                    val screenId = key.contract.screenId.value
                    if (screenId.startsWith("IMP-")) {
                        ImportRootDestination(viewModel)
                    } else if (screenId.startsWith("EXP-")) {
                        ExportRootDestination(screenId, viewModel, onNavigationChanged = { navigationEpoch += 1 })
                    } else if (screenId.startsWith("BKP-") || screenId == "SYS-003") {
                        BackupRootDestination(screenId, viewModel, onNavigationChanged = { navigationEpoch += 1 })
                    } else if (screenId.startsWith("RST-") || screenId == "CLR-002") {
                        RestoreRootDestination(screenId, viewModel, onNavigationChanged = { navigationEpoch += 1 })
                    } else if (screenId in setOf(
                            "ANA-001", "ANA-002", "ANA-003", "ANA-004", "ANA-005", "ANA-006", "ANA-007",
                            "ANA-008", "ANA-009", "ANA-010", "ANA-011", "ANA-012", "ANA-013", "ANA-014", "ANA-015",
                        )
                    ) {
                        AnalysisRootDestination(
                            screenId,
                            key.encodedArguments,
                            viewModel,
                            onNavigationChanged = { navigationEpoch += 1 },
                        )
                    } else if (screenId.startsWith("AUT-")) {
                        AutomationRootDestination(
                            screenId,
                            key.encodedArguments,
                            viewModel,
                            onNavigationChanged = { navigationEpoch += 1 },
                        )
                    } else if (screenId.startsWith("SET-")) {
                        SettlementRootDestination(
                            screenId,
                            key.encodedArguments,
                            viewModel,
                            onNavigationChanged = { navigationEpoch += 1 },
                        )
                    } else if (screenId.startsWith("LOA-") || screenId == "LIA-001" || screenId in setOf("REC-017", "REC-018", "REC-019")) {
                        LoanRootDestination(
                            screenId,
                            key.encodedArguments,
                            viewModel,
                            onNavigationChanged = { navigationEpoch += 1 },
                        )
                    } else if (screenId.startsWith("INS-") || screenId == "REC-027") {
                        InstallmentRootDestination(
                            screenId,
                            key.encodedArguments,
                            viewModel,
                            onNavigationChanged = { navigationEpoch += 1 },
                        )
                    } else if (screenId.startsWith("CRD-") || screenId == "REC-014") {
                        CreditRootDestination(
                            screenId,
                            key.encodedArguments,
                            viewModel,
                            onNavigationChanged = { navigationEpoch += 1 },
                        )
                    } else if (screenId.startsWith("PRJ-") || screenId.startsWith("GOL-")) {
                        ProjectGoalRootDestination(
                            screenId,
                            key.encodedArguments,
                            viewModel,
                            onNavigationChanged = { navigationEpoch += 1 },
                        )
                    } else if (screenId.startsWith("BUD-")) {
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
                    } else if (screenId == "REC-015" || screenId == "REC-016") {
                        RefundRootDestination(
                            screenId = screenId,
                            encodedArguments = key.encodedArguments,
                            viewModel = viewModel,
                            onNavigationChanged = { navigationEpoch += 1 },
                        )
                    } else {
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
                }
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
