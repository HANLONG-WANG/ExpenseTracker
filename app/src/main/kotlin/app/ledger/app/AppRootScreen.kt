@file:Suppress(
    "LongMethod",
    "LongParameterList",
    "MagicNumber",
    "TooManyFunctions",
    "ktlint:standard:function-naming",
    "FunctionNaming",
    "ComplexCondition",
)

package app.ledger.app

import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ledger.core.designsystem.HighRiskConfirmation
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerButton
import app.ledger.core.designsystem.LedgerButtonVariant
import app.ledger.core.designsystem.LedgerCard
import app.ledger.core.designsystem.LedgerEmptyState
import app.ledger.core.designsystem.LedgerErrorState
import app.ledger.core.designsystem.LedgerIcon
import app.ledger.core.designsystem.LedgerLoadingState
import app.ledger.core.designsystem.LedgerProgressState
import app.ledger.core.designsystem.LedgerScaffold
import app.ledger.core.designsystem.LedgerSnackbarController
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.LedgerTopAppBar
import app.ledger.core.designsystem.LedgerTopAppBarVariant
import app.ledger.core.designsystem.OperationCapability
import app.ledger.core.designsystem.OperationProgressPanel
import app.ledger.core.designsystem.OperationProgressUiModel
import app.ledger.core.designsystem.ThemeMode
import app.ledger.core.designsystem.UiErrorCode
import app.ledger.core.designsystem.rememberLedgerSnackbarController
import app.ledger.core.geo.LedgerMap
import app.ledger.core.geo.LedgerMapAccessibleRow
import app.ledger.core.geo.LedgerMapMode
import app.ledger.core.geo.LedgerMapPoint
import app.ledger.core.geo.LedgerMapState
import app.ledger.core.geo.LedgerMapStyleConfiguration
import app.ledger.core.navigation.LedgerDestinationKey
import app.ledger.core.navigation.LedgerRouteContract
import app.ledger.core.navigation.NavigationOutcome
import app.ledger.core.navigation.ScreenId
import app.ledger.core.security.BookSessionState
import app.ledger.core.security.MaintenanceReason
import app.ledger.core.security.RecoveryDiagnosticCode
import app.ledger.feature.accounts.AccountsActions
import app.ledger.feature.accounts.AccountsDataState
import app.ledger.feature.accounts.AccountsDestination
import app.ledger.feature.journal.JournalActions
import app.ledger.feature.journal.JournalDestination
import app.ledger.feature.journal.JournalLoadState
import app.ledger.feature.onboarding.OnboardingActions
import app.ledger.feature.onboarding.OnboardingScreen
import app.ledger.feature.record.OrdinaryRecordLoadState
import app.ledger.feature.record.SpecializedTransactionLoadState
import app.ledger.feature.settings.CurrencySettingsDestination
import app.ledger.feature.settings.CurrencySettingsState
import app.ledger.feature.settings.ManagementActions
import app.ledger.feature.settings.ManagementDataState
import app.ledger.feature.settings.ReferenceManagementDestination
import kotlinx.coroutines.delay
import java.util.Locale
import app.ledger.feature.journal.R as JournalR

@Composable
internal fun LedgerAppRoot(viewModel: AppRootViewModel) {
    val root by viewModel.rootState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val baseContext = LocalContext.current
    val activityResultRegistryOwner = checkNotNull(LocalActivityResultRegistryOwner.current) {
        "LedgerAppRoot requires an ActivityResultRegistryOwner"
    }
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
        LocalActivityResultRegistryOwner provides activityResultRegistryOwner,
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
internal fun RootDestination(
    key: LedgerDestinationKey,
    viewModel: AppRootViewModel,
    referenceState: AppReferenceDataState,
    referencePending: Boolean,
    recordState: OrdinaryRecordLoadState,
    specializedState: SpecializedTransactionLoadState,
    currencySettings: CurrencySettingsState?,
    journalState: JournalLoadState,
    onAddAttachment: () -> Unit,
    onBack: () -> Unit,
    onMore: () -> Unit,
    onOperations: () -> Unit,
    onHelp: () -> Unit,
    onNavigationChanged: () -> Unit,
) {
    val screenId = key.contract.screenId.value
    if (screenId.startsWith("ACC-")) {
        AccountsDestination(
            screenId = screenId,
            encodedArguments = key.encodedArguments,
            dataState = referenceState.toAccountsState(),
            actions = AccountsActions(
                onNavigate = { target, arguments ->
                    viewModel.navigateP12(key, target, arguments)
                    onNavigationChanged()
                },
                onSelectAccountType = { type ->
                    viewModel.selectP12AccountType(type, key)
                    onNavigationChanged()
                },
                onSaveAccount = viewModel::saveAccount,
                onArchiveAccount = viewModel::archiveAccount,
                onDeleteEmptyAccount = viewModel::deleteEmptyAccount,
                onSaveCard = viewModel::saveCard,
                onArchiveCard = viewModel::archiveCard,
                onSaveCheckpoint = viewModel::saveCheckpoint,
                onSaveOpeningBalance = viewModel::saveOpeningBalance,
                onRetry = viewModel::loadReferenceData,
            ),
            selectedAccountType = viewModel.selectedAccountType,
            preferredCardAccountId = viewModel.preferredCardAccountId,
            pending = referencePending,
        )
    } else if (screenId == "MGT-001" || screenId.startsWith("CAT-") || screenId.startsWith("MER-") || screenId.startsWith("PLC-")) {
        ReferenceManagementDestination(
            screenId = screenId,
            encodedArguments = key.encodedArguments,
            dataState = referenceState.toManagementState(),
            actions = ManagementActions(
                onNavigate = { target, stable, enums ->
                    viewModel.navigateP12(key, target, stable, enums)
                    onNavigationChanged()
                },
                onSaveCategory = viewModel::saveCategory,
                onReorderCategories = viewModel::reorderCategories,
                onRemoveCategory = viewModel::removeCategory,
                onSaveMerchant = viewModel::saveMerchant,
                onMergeMerchant = viewModel::mergeMerchant,
                onSavePlace = viewModel::savePlace,
                onMergePlace = viewModel::mergePlace,
                onSplitPlace = viewModel::splitPlace,
                onRetry = viewModel::loadReferenceData,
            ),
            placeMap = { places, unavailable -> PlaceMapContent(places, unavailable) },
            pending = referencePending,
        )
    } else if (screenId in setOf("REC-013", "REC-020", "REC-021", "REC-022")) {
        SpecializedTransactionRootDestination(
            screenId = screenId,
            encodedArguments = key.encodedArguments,
            state = specializedState,
            viewModel = viewModel,
            onAddAttachment = onAddAttachment,
        )
    } else if (screenId.startsWith("REC-")) {
        OrdinaryRecordRootDestination(
            screenId = screenId,
            state = recordState,
            viewModel = viewModel,
            onAddAttachment = onAddAttachment,
            onNavigationChanged = onNavigationChanged,
        )
    } else if (screenId.startsWith("JRN-")) {
        JournalDestination(
            screenId = screenId,
            encodedArguments = key.encodedArguments,
            state = journalState,
            pages = viewModel.journalPages,
            actions = JournalActions(
                onNavigate = { target, stable ->
                    stable["transactionId"]?.let(viewModel::loadJournalDetail)
                    if (target == "JRN-012") stable["transactionId"]?.let(viewModel::verifyJournalPurge)
                    viewModel.navigateP12(key, target, stable)
                    onNavigationChanged()
                },
                onSearch = viewModel::updateJournalSearch,
                onApplyFilter = viewModel::applyJournalFilter,
                onRemoveFilter = viewModel::removeJournalFilter,
                onRetry = viewModel::loadJournal,
                onLoadDetail = viewModel::loadJournalDetail,
                onSelect = viewModel::selectJournalTransaction,
                onSelectAllMatching = viewModel::selectAllJournalResults,
                onClearSelection = viewModel::clearJournalSelection,
                onBulkEdit = viewModel::bulkEditJournal,
                onSaveFilter = viewModel::saveJournalFilter,
                onApplyPreset = viewModel::applyJournalPreset,
                onCopyPreset = viewModel::copyJournalPreset,
                onSetDefaultPreset = viewModel::setDefaultJournalPreset,
                onDeletePreset = viewModel::deleteJournalPreset,
                onReorderPresets = viewModel::reorderJournalPresets,
                onResolveDependency = viewModel::resolveJournalDependency,
                onMoveToTrash = viewModel::moveJournalToTrash,
                onRestore = viewModel::restoreJournalTransaction,
                onCompareRevisions = viewModel::compareJournalRevisions,
                onRestoreRevision = viewModel::restoreJournalRevision,
                onVerifyPurge = viewModel::verifyJournalPurge,
                onPurgeRequested = viewModel::verifyJournalPurge,
            ),
        )
    } else if (screenId == "ACC-001") {
        EmptyTopLevel(R.string.global_accounts_empty_title, R.string.global_accounts_empty_message, onMore)
    } else if (screenId == "ANA-001") {
        EmptyTopLevel(R.string.global_analysis_empty_title, R.string.global_analysis_empty_message, onMore)
    } else if (screenId == "G-006") {
        MoreRootDestination(viewModel, key, onOperations, onHelp, onNavigationChanged)
    } else if (screenId == "SETG-004") {
        val state = currencySettings
        if (state == null) {
            LedgerLoadingState(Modifier.fillMaxSize())
        } else {
            CurrencySettingsDestination(
                state = state,
                onSearch = { query -> viewModel.searchCurrencies(query) },
                onToggle = { code -> viewModel.toggleCurrency(code) },
                onMove = { code, delta -> viewModel.moveCurrency(code, delta) },
            )
        }
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

@Composable
private fun PlaceMapContent(places: List<app.ledger.finance.application.PlaceReferenceView>, unavailable: Boolean) {
    val identity = places.joinToString(separator = "|") { "${it.id}:${it.rowVersion}" }
    var rendererFailed by remember(identity) { mutableStateOf(false) }
    val summary = stringResource(R.string.global_place_map_summary, places.count())
    val rows = places.map { place ->
        LedgerMapAccessibleRow(place.name, stringResource(R.string.global_location_record_count, place.locationRecordCount))
    }
    val state = if (unavailable || rendererFailed || places.none()) {
        LedgerMapState.Unavailable(summary, rows)
    } else {
        LedgerMapState.Available(
            summary = summary,
            mode = LedgerMapMode.CLUSTERS,
            points = places.map { place ->
                LedgerMapPoint(place.id, place.latitudeE7, place.longitudeE7, place.locationRecordCount)
            },
            accessibleRows = rows,
        )
    }
    LedgerMap(
        state = state,
        styleConfiguration = LedgerMapStyleConfiguration.OpenFreeMap,
        accessibleCaption = stringResource(R.string.global_place_map_table),
        accessibleColumnHeaders = listOf(
            stringResource(R.string.global_place),
            stringResource(R.string.global_location_records),
        ),
        showAccessibleListLabel = stringResource(R.string.global_show_place_list),
        hideAccessibleListLabel = stringResource(R.string.global_hide_place_list),
        onFailure = { rendererFailed = true },
    )
    if (unavailable || rendererFailed) {
        LedgerBanner(stringResource(R.string.global_map_renderer_unavailable), LedgerBannerVariant.INFO)
    } else if (places.none()) {
        LedgerText(stringResource(R.string.global_place_map_empty), LedgerTextRole.SUPPORTING)
    }
}

private fun AppReferenceDataState.toAccountsState(): AccountsDataState = when (this) {
    AppReferenceDataState.Loading -> AccountsDataState.Loading
    is AppReferenceDataState.Content -> AccountsDataState.Content(snapshot)
    is AppReferenceDataState.Error -> AccountsDataState.Error(code)
}

private fun AppReferenceDataState.toManagementState(): ManagementDataState = when (this) {
    AppReferenceDataState.Loading -> ManagementDataState.Loading
    is AppReferenceDataState.Content -> ManagementDataState.Content(snapshot)
    is AppReferenceDataState.Error -> ManagementDataState.Error(code)
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
internal fun destinationTitle(key: LedgerDestinationKey): String {
    val screenId = key.contract.screenId.value
    val resource = rootDestinationTitleResource(screenId)
        ?: accountDestinationTitleResource(screenId)
        ?: referenceDestinationTitleResource(screenId)
        ?: R.string.app_name
    return stringResource(resource)
}

private fun rootDestinationTitleResource(screenId: String): Int? = if (screenId == "REC-001") {
    R.string.global_record_title
} else if (screenId == "JRN-001") {
    R.string.global_journal_title
} else if (screenId == "JRN-002") {
    JournalR.string.p15_journal_search
} else if (screenId == "JRN-003") {
    JournalR.string.p15_journal_filter
} else if (screenId == "JRN-004") {
    JournalR.string.p15_journal_saved_filters
} else if (screenId == "JRN-005") {
    JournalR.string.p15_journal_selection
} else if (screenId == "JRN-006") {
    JournalR.string.p15_journal_bulk_edit
} else if (screenId == "JRN-007") {
    JournalR.string.p15_journal_detail
} else if (screenId == "JRN-008") {
    JournalR.string.p15_journal_history
} else if (screenId == "JRN-009") {
    JournalR.string.p15_journal_compare
} else if (screenId == "JRN-010") {
    JournalR.string.p15_journal_dependencies
} else if (screenId == "JRN-011") {
    JournalR.string.p15_journal_trash
} else if (screenId == "JRN-012") {
    JournalR.string.p15_journal_purge
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
} else if (screenId == "MGT-001") {
    R.string.global_management
} else if (screenId == "REC-013") {
    R.string.p14_title_transfer
} else if (screenId == "REC-020") {
    R.string.p14_title_adjustment
} else if (screenId == "REC-021") {
    R.string.p14_title_fx_exchange
} else if (screenId == "REC-022") {
    R.string.p14_title_opening_balance
} else if (screenId == "SETG-004") {
    R.string.global_currencies
} else {
    null
}

private fun accountDestinationTitleResource(screenId: String): Int? = if (screenId == "ACC-002") {
    R.string.p12_title_account_type
} else if (screenId == "ACC-003") {
    R.string.p12_title_account_editor
} else if (screenId == "ACC-004") {
    R.string.p12_title_opening_balance
} else if (screenId == "ACC-005") {
    R.string.p12_title_account_detail
} else if (screenId == "ACC-006") {
    R.string.p12_title_account_transactions
} else if (screenId == "ACC-007") {
    R.string.p12_title_checkpoint
} else if (screenId == "ACC-008") {
    R.string.p12_title_checkpoint_resolution
} else if (screenId == "ACC-009") {
    R.string.p12_title_cards
} else if (screenId == "ACC-010") {
    R.string.p12_title_card_editor
} else if (screenId == "ACC-011") {
    R.string.p12_title_card_detail
} else if (screenId == "ACC-012") {
    R.string.p12_title_account_archive
} else {
    null
}

private fun referenceDestinationTitleResource(screenId: String): Int? = if (screenId == "CAT-001") {
    R.string.p12_title_category_list
} else if (screenId == "CAT-002") {
    R.string.p12_title_category_editor
} else if (screenId == "CAT-003") {
    R.string.p12_title_category_reorder
} else if (screenId == "CAT-004") {
    R.string.p12_title_category_remove
} else if (screenId == "MER-001") {
    R.string.p12_title_merchants
} else if (screenId == "MER-002") {
    R.string.p12_title_merchant_editor
} else if (screenId == "MER-003") {
    R.string.p12_title_merchant_merge
} else if (screenId == "PLC-001") {
    R.string.p12_title_places
} else if (screenId == "PLC-002") {
    R.string.p12_title_place_editor
} else if (screenId == "PLC-003") {
    R.string.p12_title_place_merge_split
} else {
    null
}
