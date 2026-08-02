@file:Suppress(
    "LongMethod",
    "LongParameterList",
    "MagicNumber",
    "TooManyFunctions",
    "ktlint:standard:function-naming",
    "FunctionNaming",
)

package app.ledger.app

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import app.ledger.core.designsystem.HighRiskConfirmation
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerButton
import app.ledger.core.designsystem.LedgerButtonVariant
import app.ledger.core.designsystem.LedgerCard
import app.ledger.core.designsystem.LedgerEmptyState
import app.ledger.core.designsystem.LedgerErrorState
import app.ledger.core.designsystem.LedgerIcon
import app.ledger.core.designsystem.LedgerIconButton
import app.ledger.core.designsystem.LedgerLoadingState
import app.ledger.core.designsystem.LedgerNavigationBar
import app.ledger.core.designsystem.LedgerProgressState
import app.ledger.core.designsystem.LedgerScaffold
import app.ledger.core.designsystem.LedgerSnackbarController
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.LedgerTopAppBar
import app.ledger.core.designsystem.LedgerTopAppBarVariant
import app.ledger.core.designsystem.LedgerTopLevel
import app.ledger.core.designsystem.OperationCapability
import app.ledger.core.designsystem.OperationProgressPanel
import app.ledger.core.designsystem.OperationProgressUiModel
import app.ledger.core.designsystem.ThemeMode
import app.ledger.core.designsystem.UiErrorCode
import app.ledger.core.designsystem.rememberLedgerSnackbarController
import app.ledger.core.navigation.FiveStackNavigator
import app.ledger.core.navigation.LedgerDestinationKey
import app.ledger.core.navigation.LedgerRouteContract
import app.ledger.core.navigation.NavigationOutcome
import app.ledger.core.navigation.ScreenId
import app.ledger.core.navigation.SessionGateState
import app.ledger.core.navigation.TopLevelDestination
import app.ledger.core.security.BookSessionState
import app.ledger.core.security.MaintenanceReason
import app.ledger.core.security.RecoveryDiagnosticCode
import app.ledger.feature.onboarding.OnboardingActions
import app.ledger.feature.onboarding.OnboardingScreen
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
internal fun LedgerAppRoot(viewModel: AppRootViewModel) {
    val root by viewModel.rootState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val baseContext = LocalContext.current
    val onboardingLanguageTag = (root as? AppRootState.Onboarding)?.state?.language?.tag
    val languageTag = if (onboardingLanguageTag != null) {
        onboardingLanguageTag
    } else {
        settings.languageTag.takeIf { language -> language.isNotBlank() }
    }
    val localizedContext = remember(baseContext, languageTag) {
        if (languageTag == null) {
            baseContext
        } else {
            baseContext.createConfigurationContext(
                Configuration(baseContext.resources.configuration).apply {
                    setLocales(LocaleList(Locale.forLanguageTag(languageTag)))
                },
            )
        }
    }
    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedContext.resources.configuration,
    ) {
        val snackbarController = rememberLedgerSnackbarController()
        val settingsWriteFailed = stringResource(R.string.global_settings_write_failed)
        val localClearFailed = stringResource(R.string.global_local_clear_failed)
        LaunchedEffect(viewModel, snackbarController, settingsWriteFailed, localClearFailed) {
            viewModel.globalSnackbarMessages.collect { message ->
                snackbarController.show(
                    if (message == GlobalSnackbarMessage.SETTINGS_WRITE_FAILED) settingsWriteFailed else localClearFailed,
                )
            }
        }
        LedgerTheme(ThemeMode.FOLLOW_SYSTEM, dynamicColor = false, reduceMotion = false) {
            val state = root
            if (state === AppRootState.Starting) {
                LedgerLoadingState(Modifier.fillMaxSize())
            } else if (state is AppRootState.Onboarding) {
                OnboardingScreen(
                    state.state,
                    OnboardingActions(
                        viewModel::selectLanguage,
                        viewModel::updateCurrencySearch,
                        viewModel::selectCurrency,
                        viewModel::updateZoneSearch,
                        viewModel::selectZone,
                        viewModel::setPrivacyAccepted,
                        viewModel::setTelemetry,
                        viewModel::setCrashReporting,
                        viewModel::setAppLock,
                        viewModel::setAppLockTimeout,
                        viewModel::updateRecoveryPassword,
                        viewModel::updateRecoveryConfirmation,
                        viewModel::updateAccountName,
                        viewModel::setAccountType,
                        viewModel::updateCategoryName,
                        viewModel::setCategoryDirection,
                        viewModel::onboardingBack,
                        viewModel::onboardingNext,
                        viewModel::onboardingSkip,
                    ),
                    snackbarController = snackbarController,
                )
            } else if (state is AppRootState.Session) {
                SessionGateScreen(state, viewModel, snackbarController)
            }
        }
    }
}

@Composable
private fun SessionGateScreen(
    state: AppRootState.Session,
    viewModel: AppRootViewModel,
    snackbarController: LedgerSnackbarController,
) {
    Column(Modifier.fillMaxSize().testTag(LedgerTestTags.SESSION_GATE)) {
        val session = state.state
        if (session === BookSessionState.Uninitialized) {
            LedgerLoadingState(label = stringResource(R.string.global_opening))
        } else if (session === BookSessionState.Locked) {
            LockScreen(state.authentication, viewModel::beginAuthentication)
        } else if (session === BookSessionState.Opening) {
            OpeningBookScreen(OpeningPresentation.OPENING, viewModel::retryOpen)
        } else if (session is BookSessionState.Maintenance) {
            MaintenanceScreen(session.reason, MaintenancePresentation.RUNNING)
        } else if (session is BookSessionState.RecoveryRequired) {
            RecoveryRequiredScreen(
                session.diagnosticCode,
                RecoveryPresentation.NO_BACKUP,
                viewModel::retryOpen,
                onRestore = {},
                onClear = viewModel::clearLocalBookData,
            )
        } else if (session is BookSessionState.Ready) {
            ReadyRootScaffold(
                viewModel = viewModel,
                unsavedContentLossNotice = state.unsavedContentLossNotice,
                snackbarController = snackbarController,
            )
        }
    }
}

@Composable
internal fun LockScreen(state: AppAuthenticationState, onAuthenticate: () -> Unit) {
    LedgerScaffold(Modifier.fillMaxSize(), formContent = true) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.Center,
        ) {
            LedgerText(stringResource(R.string.global_locked_title), LedgerTextRole.TITLE, centered = true, modifier = Modifier.fillMaxWidth())
            LedgerText(stringResource(R.string.global_locked_message), LedgerTextRole.BODY, centered = true, modifier = Modifier.fillMaxWidth())
            if (state == AppAuthenticationState.AUTH_FAILED) {
                LedgerBanner(stringResource(R.string.global_auth_failed), LedgerBannerVariant.DANGER)
            } else if (state == AppAuthenticationState.LOCKED_OUT) {
                LedgerBanner(stringResource(R.string.global_auth_locked_out), LedgerBannerVariant.WARNING)
            } else if (state == AppAuthenticationState.AUTHENTICATING) {
                LedgerLoadingState(label = stringResource(R.string.global_authenticating))
            } else if (state == AppAuthenticationState.BIOMETRIC_AVAILABLE) {
                LedgerText(
                    stringResource(R.string.global_biometric_available),
                    LedgerTextRole.SUPPORTING,
                    Modifier.fillMaxWidth(),
                    centered = true,
                )
            } else {
                LedgerText(
                    stringResource(R.string.global_credential_only),
                    LedgerTextRole.SUPPORTING,
                    Modifier.fillMaxWidth(),
                    centered = true,
                )
            }
            LedgerButton(
                stringResource(R.string.global_unlock),
                onAuthenticate,
                Modifier.fillMaxWidth(),
                enabled = state != AppAuthenticationState.AUTHENTICATING,
            )
        }
    }
}

internal enum class OpeningPresentation { OPENING, MIGRATION_DETECTED, FAILED }

@Composable
internal fun OpeningBookScreen(presentation: OpeningPresentation, onRetry: () -> Unit) {
    var delayedVisible by remember(presentation) { mutableStateOf(presentation != OpeningPresentation.OPENING) }
    val delayMillis = LedgerTheme.motion.skeletonDelayMs
    LaunchedEffect(presentation) {
        if (presentation == OpeningPresentation.OPENING) {
            delay(delayMillis.toLong())
            delayedVisible = true
        }
    }
    if (!delayedVisible) return
    if (presentation == OpeningPresentation.OPENING) {
        LedgerLoadingState(Modifier.fillMaxSize(), stringResource(R.string.global_opening))
    } else if (presentation == OpeningPresentation.MIGRATION_DETECTED) {
        LedgerLoadingState(Modifier.fillMaxSize(), stringResource(R.string.global_opening_migration))
    } else {
        LedgerErrorState(
            code = UiErrorCode("BOOK_OPEN_FAILED"),
            message = stringResource(R.string.global_opening_failed),
            onRetry = onRetry,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

internal enum class MaintenancePresentation { PREPARING, RUNNING, NON_CANCELABLE, CANCELABLE, FAILED, SUCCEEDED }

@Composable
internal fun MaintenanceScreen(reason: MaintenanceReason, presentation: MaintenancePresentation) {
    val phaseResource = if (presentation == MaintenancePresentation.PREPARING) {
        R.string.global_maintenance_preparing
    } else if (presentation == MaintenancePresentation.RUNNING) {
        R.string.global_maintenance_running
    } else if (presentation == MaintenancePresentation.NON_CANCELABLE) {
        R.string.global_maintenance_non_cancelable
    } else if (presentation == MaintenancePresentation.CANCELABLE) {
        R.string.global_maintenance_cancelable
    } else if (presentation == MaintenancePresentation.FAILED) {
        R.string.global_maintenance_failed
    } else {
        R.string.global_maintenance_succeeded
    }
    val phase = stringResource(phaseResource)
    val failure = UiErrorCode("MAINTENANCE_FAILED").takeIf { presentation == MaintenancePresentation.FAILED }
    LedgerScaffold(Modifier.fillMaxSize(), formContent = true) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center) {
            LedgerText(stringResource(R.string.global_maintenance_title), LedgerTextRole.TITLE)
            OperationProgressPanel(
                OperationProgressUiModel(
                    name = reason.name,
                    phase = phase,
                    processedText = stringResource(R.string.global_maintenance_explanation),
                    progress = if (presentation == MaintenancePresentation.PREPARING) {
                        0f
                    } else if (presentation == MaintenancePresentation.SUCCEEDED) {
                        1f
                    } else if (presentation == MaintenancePresentation.FAILED) {
                        null
                    } else {
                        .5f
                    },
                    capability = if (presentation == MaintenancePresentation.CANCELABLE) {
                        OperationCapability.CANCELABLE
                    } else {
                        OperationCapability.NON_CANCELABLE_COMMIT
                    },
                    statusExplanation = stringResource(R.string.global_maintenance_explanation),
                    failureCode = failure,
                ),
            )
        }
    }
}

internal enum class RecoveryPresentation { CORRUPT, KEY_UNAVAILABLE, PROJECTION_FAILURE, RESTORE_AVAILABLE, NO_BACKUP }

@Composable
internal fun RecoveryRequiredScreen(
    code: RecoveryDiagnosticCode,
    presentation: RecoveryPresentation,
    onRetry: () -> Unit,
    onRestore: () -> Unit,
    onClear: () -> Unit,
) {
    var confirmingClear by remember { mutableStateOf(false) }
    var phrase by remember { mutableStateOf("") }
    val explanationResource = if (presentation == RecoveryPresentation.CORRUPT) {
        R.string.global_recovery_corrupt
    } else if (presentation == RecoveryPresentation.KEY_UNAVAILABLE) {
        R.string.global_recovery_key
    } else if (presentation == RecoveryPresentation.PROJECTION_FAILURE) {
        R.string.global_recovery_projection
    } else if (presentation == RecoveryPresentation.RESTORE_AVAILABLE) {
        R.string.global_recovery_restore
    } else {
        R.string.global_recovery_no_backup
    }
    val explanation = stringResource(explanationResource)
    LedgerScaffold(Modifier.fillMaxSize(), formContent = true) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center) {
            LedgerText(stringResource(R.string.global_recovery_title), LedgerTextRole.TITLE)
            LedgerBanner(explanation, LedgerBannerVariant.DANGER)
            LedgerText(stringResource(R.string.global_recovery_diagnostic, code.name), LedgerTextRole.SUPPORTING)
            LedgerButton(stringResource(R.string.global_recovery_retry), onRetry, Modifier.fillMaxWidth())
            LedgerButton(
                stringResource(R.string.global_recovery_from_backup),
                onClick = onRestore,
                modifier = Modifier.fillMaxWidth(),
                variant = LedgerButtonVariant.SECONDARY,
                enabled = presentation == RecoveryPresentation.RESTORE_AVAILABLE,
            )
            LedgerButton(
                stringResource(R.string.global_recovery_clear),
                onClick = { confirmingClear = true },
                modifier = Modifier.fillMaxWidth(),
                variant = LedgerButtonVariant.DANGER,
            )
            if (confirmingClear) {
                HighRiskConfirmation(
                    title = stringResource(R.string.global_recovery_clear),
                    scope = stringResource(R.string.global_recovery_clear_scope),
                    consequence = stringResource(R.string.global_recovery_clear_consequence),
                    unaffected = stringResource(R.string.global_recovery_clear_unaffected),
                    requiredPhrase = stringResource(R.string.global_recovery_clear_phrase),
                    enteredPhrase = phrase,
                    onPhraseChange = { phrase = it },
                    onConfirm = onClear,
                    onCancel = {
                        confirmingClear = false
                        phrase = ""
                    },
                )
            }
        }
    }
}

@Composable
private fun ReadyRootScaffold(
    viewModel: AppRootViewModel,
    unsavedContentLossNotice: Boolean,
    snackbarController: LedgerSnackbarController,
) {
    val navigator = viewModel.navigator
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
                    navigator.pop()
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
        bottomBar = {
            if (navigator.isBottomNavigationVisible) {
                LedgerNavigationBar(selected = selected, onSelected = { target ->
                    navigator.select(target.toNavigationTopLevel())
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
                navigator.pop()
                navigationEpoch += 1
            },
            modifier = Modifier.fillMaxSize().padding(padding),
            entryProvider = { key ->
                NavEntry(key) {
                    RootDestination(
                        key,
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
                    )
                }
            },
        )
    }
}

@Composable
private fun RootDestination(
    key: LedgerDestinationKey,
    onBack: () -> Unit,
    onMore: () -> Unit,
    onOperations: () -> Unit,
    onHelp: () -> Unit,
) {
    val screenId = key.contract.screenId.value
    if (screenId == "REC-001") {
        EmptyTopLevel(R.string.global_record_empty_title, R.string.global_record_empty_message, onMore)
    } else if (screenId == "JRN-001") {
        EmptyTopLevel(R.string.global_journal_empty_title, R.string.global_journal_empty_message, onMore)
    } else if (screenId == "ACC-001") {
        EmptyTopLevel(R.string.global_accounts_empty_title, R.string.global_accounts_empty_message, onMore)
    } else if (screenId == "BUD-001") {
        EmptyTopLevel(R.string.global_budget_empty_title, R.string.global_budget_empty_message, onMore)
    } else if (screenId == "ANA-001") {
        EmptyTopLevel(R.string.global_analysis_empty_title, R.string.global_analysis_empty_message, onMore)
    } else if (screenId == "G-006") {
        MoreContent(MorePresentation.CONTENT, onOperations, onHelp)
    } else if (screenId == "G-007") {
        OperationCenterContent(OperationCenterPresentation.EMPTY, onBack)
    } else if (screenId == "G-008") {
        HelpContent(key.encodedArguments["topicKey"], onBack)
    } else {
        LedgerErrorState(
            code = UiErrorCode("DESTINATION_NOT_REGISTERED"),
            message = stringResource(R.string.global_destination_unavailable),
            onRetry = onBack,
        )
    }
}

@Composable
private fun EmptyTopLevel(emptyTitle: Int, explanation: Int, onMore: () -> Unit) {
    LedgerEmptyState(
        stringResource(emptyTitle),
        stringResource(explanation),
        stringResource(R.string.global_open_more),
        onMore,
    )
}

internal enum class MorePresentation { CONTENT, BADGE_UPDATES, OPERATION_IN_PROGRESS }

@Composable
internal fun MoreScreen(
    presentation: MorePresentation,
    onBack: () -> Unit,
    onOperations: () -> Unit,
    onHelp: () -> Unit,
) {
    LedgerScaffold(
        Modifier.fillMaxSize(),
        topBar = { LedgerTopAppBar(stringResource(R.string.global_more_title), LedgerTopAppBarVariant.BACK, onNavigation = onBack) },
    ) { padding ->
        MoreContent(presentation, onOperations, onHelp, Modifier.padding(padding))
    }
}

@Composable
private fun MoreContent(
    presentation: MorePresentation,
    onOperations: () -> Unit,
    onHelp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        if (presentation == MorePresentation.BADGE_UPDATES) LedgerBanner(stringResource(R.string.global_badge_updates), LedgerBannerVariant.INFO)
        if (presentation == MorePresentation.OPERATION_IN_PROGRESS) LedgerBanner(stringResource(R.string.global_active_operation), LedgerBannerVariant.WARNING)
        FeatureHubItem(stringResource(R.string.global_operations), stringResource(R.string.global_operations_explanation), onOperations)
        FeatureHubItem(stringResource(R.string.global_help), stringResource(R.string.global_help_explanation), onHelp)
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

internal enum class OperationCenterPresentation { ACTIVE, PAUSED, FAILED, COMPLETED, EMPTY }

@Composable
internal fun OperationCenterScreen(presentation: OperationCenterPresentation, onBack: () -> Unit) {
    LedgerScaffold(
        Modifier.fillMaxSize(),
        topBar = { LedgerTopAppBar(stringResource(R.string.global_operations), LedgerTopAppBarVariant.BACK, onNavigation = onBack) },
    ) { padding ->
        OperationCenterContent(presentation, onBack, Modifier.padding(padding))
    }
}

@Composable
private fun OperationCenterContent(
    presentation: OperationCenterPresentation,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (presentation == OperationCenterPresentation.EMPTY) {
        LedgerEmptyState(
            stringResource(R.string.global_operations_empty_title),
            stringResource(R.string.global_operations_empty_message),
            stringResource(R.string.global_back),
            onBack,
            modifier,
        )
    } else {
        val phaseResource = if (presentation == OperationCenterPresentation.ACTIVE) {
            R.string.global_operations_active
        } else if (presentation == OperationCenterPresentation.PAUSED) {
            R.string.global_operations_paused
        } else if (presentation == OperationCenterPresentation.FAILED) {
            R.string.global_operations_failed
        } else if (presentation == OperationCenterPresentation.COMPLETED) {
            R.string.global_operations_completed
        } else {
            R.string.global_operations_active
        }
        val phase = stringResource(phaseResource)
        OperationProgressPanel(
            OperationProgressUiModel(
                "operation",
                phase,
                phase,
                if (presentation == OperationCenterPresentation.ACTIVE) .5f else null,
                OperationCapability.NON_CANCELABLE_COMMIT,
                phase,
                UiErrorCode("OPERATION_FAILED").takeIf { presentation == OperationCenterPresentation.FAILED },
            ),
            modifier,
        )
    }
}

@Composable
internal fun HelpScreen(topicKey: String?, onBack: () -> Unit) {
    LedgerScaffold(
        Modifier.fillMaxSize(),
        topBar = { LedgerTopAppBar(stringResource(R.string.global_help_title), LedgerTopAppBarVariant.BACK, onNavigation = onBack) },
    ) { padding ->
        HelpContent(topicKey, onBack, Modifier.padding(padding))
    }
}

@Composable
private fun HelpContent(topicKey: String?, onBack: () -> Unit, modifier: Modifier = Modifier) {
    if (topicKey == "getting-started") {
        Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
            LedgerText(stringResource(R.string.global_help_getting_started), LedgerTextRole.TITLE)
            LedgerText(stringResource(R.string.global_help_body), LedgerTextRole.BODY)
        }
    } else {
        LedgerEmptyState(
            stringResource(R.string.global_help_not_found_title),
            stringResource(R.string.global_help_not_found_message),
            stringResource(R.string.global_back),
            onBack,
            modifier,
        )
    }
}

@Composable
private fun destinationTitle(key: LedgerDestinationKey): String {
    val screenId = key.contract.screenId.value
    val resource = if (screenId == "REC-001") {
        R.string.global_record_title
    } else if (screenId == "JRN-001") {
        R.string.global_journal_title
    } else if (screenId == "ACC-001") {
        R.string.global_accounts_title
    } else if (screenId == "BUD-001") {
        R.string.global_budget_title
    } else if (screenId == "ANA-001") {
        R.string.global_analysis_title
    } else if (screenId == "G-006") {
        R.string.global_more_title
    } else if (screenId == "G-007") {
        R.string.global_operations
    } else if (screenId == "G-008") {
        R.string.global_help_title
    } else {
        R.string.app_name
    }
    return stringResource(resource)
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
