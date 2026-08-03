@file:Suppress("ktlint:standard:function-naming", "FunctionNaming")

package app.ledger.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
    val launchAttachmentPicker = rememberRecordAttachmentPicker(viewModel::importRecordAttachment)
    var navigationEpoch by remember { mutableIntStateOf(0) }
    val selected = navigator.currentTopLevel.toDesignTopLevel()
    LedgerScaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarController = snackbarController,
        topBar = {
            val key = navigator.currentKey
            val topLevel = key.contract.screenId.value in setOf("REC-001", "JRN-001", "ACC-001", "BUD-001", "ANA-001")
            LedgerTopAppBar(
                title = destinationTitle(key),
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
        fixedAction = ordinaryRecordFixedAction(
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
                    RootDestination(
                        key,
                        viewModel = viewModel,
                        referenceState = referenceState,
                        referencePending = referencePending,
                        recordState = recordState,
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
                            val screenId = ScreenId("G-008")
                            navigator.navigate(
                                LedgerRouteContract.destination(
                                    screenId,
                                    mapOf("topicKey" to LedgerRouteContract.opaqueKeyArgument(screenId, "topicKey", "getting-started")),
                                ),
                                SessionGateState.READY,
                            )
                            navigationEpoch += 1
                        },
                        onNavigationChanged = { navigationEpoch += 1 },
                    )
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
