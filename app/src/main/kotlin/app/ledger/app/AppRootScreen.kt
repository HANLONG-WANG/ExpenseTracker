@file:Suppress(
    "LongMethod",
    "LongParameterList",
    "MagicNumber",
    "TooManyFunctions",
    "ktlint:standard:function-naming",
    "FunctionNaming",
    "ComplexCondition",
    "CyclomaticComplexMethod",
)

package app.ledger.app

import android.app.LocaleManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.LocaleList
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.focusable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalProvidableLocaleList
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerButton
import app.ledger.core.designsystem.LedgerButtonVariant
import app.ledger.core.designsystem.LedgerCard
import app.ledger.core.designsystem.LedgerDateFormat
import app.ledger.core.designsystem.LedgerDialog
import app.ledger.core.designsystem.LedgerEmptyState
import app.ledger.core.designsystem.LedgerErrorState
import app.ledger.core.designsystem.LedgerIcon
import app.ledger.core.designsystem.LedgerLoadingState
import app.ledger.core.designsystem.LedgerProgressState
import app.ledger.core.designsystem.LedgerScaffold
import app.ledger.core.designsystem.LedgerSnackbarController
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextField
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.LedgerTopAppBar
import app.ledger.core.designsystem.LedgerTopAppBarVariant
import app.ledger.core.designsystem.LedgerWeekStart
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
import app.ledger.feature.record.BatchRecordState
import app.ledger.feature.record.OrdinaryRecordLoadState
import app.ledger.feature.record.SpecializedTransactionLoadState
import app.ledger.feature.settings.CurrencySettingsDestination
import app.ledger.feature.settings.CurrencySettingsState
import app.ledger.feature.settings.ManagementActions
import app.ledger.feature.settings.ManagementDataState
import app.ledger.feature.settings.ReferenceManagementDestination
import app.ledger.feature.settings.RemainingSettingsDestination
import app.ledger.feature.settings.RemainingSettingsScreenAction
import app.ledger.feature.settings.SecurityPrivacyScreenAction
import app.ledger.feature.settings.SecurityPrivacySettingsDestination
import app.ledger.feature.transfer.BackupExecutionPresentation
import app.ledger.feature.transfer.ExportExecutionPresentation
import app.ledger.feature.transfer.RestoreFlowUiState
import app.ledger.feature.transfer.RestoreResultPresentation
import app.ledger.feature.transfer.TransferHubScreen
import app.ledger.feature.transfer.TransferHubScreenAction
import app.ledger.feature.transfer.TransferHubState
import app.ledger.feature.vault.VaultActions
import app.ledger.feature.vault.VaultDestination
import app.ledger.transfer.domain.BackgroundOperation
import app.ledger.transfer.domain.BackgroundOperationState
import app.ledger.transfer.domain.BackgroundOperationType
import kotlinx.coroutines.delay
import java.util.Locale
import app.ledger.core.files.R as FilesR
import app.ledger.feature.journal.R as JournalR
import app.ledger.feature.record.R as RecordR
import app.ledger.feature.settings.R as SettingsR
import app.ledger.feature.vault.R as VaultR

@Composable
internal fun LedgerAppRoot(viewModel: AppRootViewModel) {
    val root by viewModel.rootState.collectAsStateWithLifecycle()
    val recoveryRestoreActive by viewModel.recoveryRestoreActive.collectAsStateWithLifecycle()
    val restoreState by viewModel.restoreFlow.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val baseContext = LocalContext.current
    val baseConfiguration = LocalConfiguration.current
    val activityResultRegistryOwner = checkNotNull(LocalActivityResultRegistryOwner.current) {
        "LedgerAppRoot requires an ActivityResultRegistryOwner"
    }
    val onboardingLanguageTag = (root as? AppRootState.Onboarding)?.state?.language?.tag
    val languageTag = if (onboardingLanguageTag != null) {
        onboardingLanguageTag
    } else {
        settings.languageTag.takeIf { language -> language.isNotBlank() }
    }
    val localizedContext = remember(baseContext, baseConfiguration, languageTag) {
        if (languageTag == null) {
            baseContext
        } else {
            baseContext.createConfigurationContext(
                Configuration(baseConfiguration).apply {
                    setLocales(LocaleList(Locale.forLanguageTag(languageTag)))
                },
            )
        }
    }
    val localizedLocaleList = remember(languageTag, localizedContext) {
        androidx.compose.ui.text.intl.LocaleList(
            languageTag ?: localizedContext.resources.configuration.locales[0].toLanguageTag(),
        )
    }
    val selectedJavaLocale = remember(languageTag, localizedContext) {
        Locale.forLanguageTag(
            languageTag ?: localizedContext.resources.configuration.locales[0].toLanguageTag(),
        )
    }
    SideEffect {
        // Some Compose/Material accessibility actions still resolve their strings from the
        // platform application locale instead of the composition-local Resources instance.
        Locale.setDefault(selectedJavaLocale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && languageTag != null) {
            val localeManager = baseContext.getSystemService(LocaleManager::class.java)
            val applicationLocales = LocaleList.forLanguageTags(languageTag)
            if (localeManager.applicationLocales.toLanguageTags() != applicationLocales.toLanguageTags()) {
                localeManager.applicationLocales = applicationLocales
            }
        }
    }
    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedContext.resources.configuration,
        LocalResources provides localizedContext.resources,
        LocalProvidableLocaleList provides localizedLocaleList,
        LocalActivityResultRegistryOwner provides activityResultRegistryOwner,
    ) {
        val snackbarController = rememberLedgerSnackbarController()
        val settingsWriteFailed = stringResource(R.string.global_settings_write_failed)
        val localClearFailed = stringResource(R.string.global_local_clear_failed)
        val externalAppUnavailable = stringResource(R.string.global_external_app_unavailable)
        val referenceSaved = stringResource(R.string.global_reference_saved)
        val referenceArchived = stringResource(R.string.global_reference_archived)
        val referenceDeleted = stringResource(R.string.global_reference_deleted)
        val referenceMutationFailed = stringResource(R.string.global_reference_mutation_failed)
        val journalMovedToTrash = stringResource(R.string.global_journal_moved_to_trash)
        val journalRestored = stringResource(R.string.global_journal_restored)
        val journalMutationFailed = stringResource(R.string.global_journal_mutation_failed)
        val journalPermanentlyDeleted = stringResource(R.string.global_journal_permanently_deleted)
        val journalBulkUpdated = stringResource(R.string.global_journal_bulk_updated)
        val planningUpdated = stringResource(R.string.global_planning_updated)
        val loanUpdated = stringResource(R.string.global_loan_updated)
        val settlementUpdated = stringResource(R.string.global_settlement_updated)
        val automationUpdated = stringResource(R.string.global_automation_updated)
        LaunchedEffect(
            viewModel,
            snackbarController,
            settingsWriteFailed,
            localClearFailed,
            externalAppUnavailable,
            referenceSaved,
            referenceArchived,
            referenceDeleted,
            referenceMutationFailed,
            journalMovedToTrash,
            journalRestored,
            journalMutationFailed,
            journalPermanentlyDeleted,
            journalBulkUpdated,
            planningUpdated,
            loanUpdated,
            settlementUpdated,
            automationUpdated,
        ) {
            viewModel.globalSnackbarMessages.collect { message ->
                snackbarController.show(
                    when (message) {
                        GlobalSnackbarMessage.SETTINGS_WRITE_FAILED -> settingsWriteFailed
                        GlobalSnackbarMessage.LOCAL_CLEAR_FAILED -> localClearFailed
                        GlobalSnackbarMessage.EXTERNAL_APP_UNAVAILABLE -> externalAppUnavailable
                        GlobalSnackbarMessage.REFERENCE_SAVED -> referenceSaved
                        GlobalSnackbarMessage.REFERENCE_ARCHIVED -> referenceArchived
                        GlobalSnackbarMessage.REFERENCE_DELETED -> referenceDeleted
                        GlobalSnackbarMessage.REFERENCE_MUTATION_FAILED -> referenceMutationFailed
                        GlobalSnackbarMessage.JOURNAL_MOVED_TO_TRASH -> journalMovedToTrash
                        GlobalSnackbarMessage.JOURNAL_RESTORED -> journalRestored
                        GlobalSnackbarMessage.JOURNAL_MUTATION_FAILED -> journalMutationFailed
                        GlobalSnackbarMessage.JOURNAL_PERMANENTLY_DELETED -> journalPermanentlyDeleted
                        GlobalSnackbarMessage.JOURNAL_BULK_UPDATED -> journalBulkUpdated
                        GlobalSnackbarMessage.PLANNING_UPDATED -> planningUpdated
                        GlobalSnackbarMessage.LOAN_UPDATED -> loanUpdated
                        GlobalSnackbarMessage.SETTLEMENT_UPDATED -> settlementUpdated
                        GlobalSnackbarMessage.AUTOMATION_UPDATED -> automationUpdated
                    },
                )
            }
        }
        val themeMode = when (settings.themeMode) {
            app.ledger.app.settings.ThemeModeProto.THEME_MODE_LIGHT -> ThemeMode.LIGHT
            app.ledger.app.settings.ThemeModeProto.THEME_MODE_DARK -> ThemeMode.DARK
            else -> ThemeMode.FOLLOW_SYSTEM
        }
        val darkTheme = when (themeMode) {
            ThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
        SideEffect {
            (baseContext as? androidx.activity.ComponentActivity)?.enableEdgeToEdge(
                statusBarStyle = if (darkTheme) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                navigationBarStyle = if (darkTheme) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            )
        }
        val ledgerNow = remember(viewModel) { viewModel::currentUiInstant }
        LedgerTheme(
            themeMode,
            dynamicColor = settings.dynamicColorEnabled,
            reduceMotion = settings.reduceMotionEnabled,
            ledgerTimeZoneId = settings.zoneId.ifBlank { "UTC" },
            ledgerNow = ledgerNow,
            ledgerDateFormat = when (settings.dateFormat) {
                app.ledger.app.settings.DateFormatProto.DATE_FORMAT_YEAR_MONTH_DAY -> LedgerDateFormat.YEAR_MONTH_DAY
                app.ledger.app.settings.DateFormatProto.DATE_FORMAT_DAY_MONTH_YEAR -> LedgerDateFormat.DAY_MONTH_YEAR
                app.ledger.app.settings.DateFormatProto.DATE_FORMAT_MONTH_DAY_YEAR -> LedgerDateFormat.MONTH_DAY_YEAR
                else -> LedgerDateFormat.LOCALE_DEFAULT
            },
            ledgerWeekStart = when (settings.weekStart) {
                app.ledger.app.settings.WeekStartProto.WEEK_START_MONDAY -> LedgerWeekStart.MONDAY
                app.ledger.app.settings.WeekStartProto.WEEK_START_SUNDAY -> LedgerWeekStart.SUNDAY
                else -> LedgerWeekStart.LOCALE_DEFAULT
            },
        ) {
            val state = root
            if (recoveryRestoreActive) {
                RestoreRootDestination(restoreState.screenId, viewModel, onNavigationChanged = {})
            } else if (state === AppRootState.Starting) {
                LedgerLoadingState(Modifier.fillMaxSize())
            } else if (state is AppRootState.Onboarding) {
                BackHandler(enabled = state.state.step != app.ledger.feature.onboarding.OnboardingStep.LANGUAGE) {
                    viewModel.onboardingBack()
                }
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
                        viewModel::setCategoryIcon,
                        viewModel::setCategoryPalette,
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
    val maintenancePresentation by viewModel.maintenancePresentation.collectAsStateWithLifecycle()
    val recoveryBackupAvailable by viewModel.recoveryBackupAvailable.collectAsStateWithLifecycle()
    val openingPresentation by viewModel.openingPresentation.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().testTag(LedgerTestTags.SESSION_GATE)) {
        val session = state.state
        if (session === BookSessionState.Uninitialized) {
            LedgerLoadingState(label = stringResource(R.string.global_opening))
        } else if (session === BookSessionState.Locked) {
            LockScreen(state.authentication, viewModel::beginAuthentication)
        } else if (session === BookSessionState.Opening) {
            OpeningBookScreen(openingPresentation, viewModel::retryOpen)
        } else if (session is BookSessionState.Maintenance && session.reason == MaintenanceReason.DATABASE_MIGRATION) {
            OpeningBookScreen(OpeningPresentation.MIGRATION_DETECTED, viewModel::retryOpen)
        } else if (session is BookSessionState.Maintenance) {
            MaintenanceScreen(
                session.reason,
                maintenancePresentation ?: MaintenancePresentation.PREPARING,
                onCancel = if (session.reason == MaintenanceReason.CONTROLLED_MAINTENANCE) viewModel::cancelMaintenance else null,
                onRetry = if (session.reason == MaintenanceReason.UNFINISHED_OPERATION) viewModel::retryMaintenance else null,
            )
        } else if (session is BookSessionState.RecoveryRequired) {
            if (session.diagnosticCode == RecoveryDiagnosticCode.DATABASE_UNAVAILABLE) {
                OpeningBookScreen(OpeningPresentation.FAILED, viewModel::retryOpen)
            } else {
                val reasonPresentation = when (session.diagnosticCode) {
                    RecoveryDiagnosticCode.KEY_UNAVAILABLE -> RecoveryPresentation.KEY_UNAVAILABLE
                    RecoveryDiagnosticCode.PROJECTION_FAILURE -> RecoveryPresentation.PROJECTION_FAILURE
                    RecoveryDiagnosticCode.SCHEMA_INVALID -> RecoveryPresentation.CORRUPT
                    RecoveryDiagnosticCode.DATABASE_UNAVAILABLE -> error("handled as opening failure")
                }
                RecoveryRequiredScreen(
                    session.diagnosticCode,
                    when (recoveryBackupAvailable) {
                        true -> RecoveryPresentation.RESTORE_AVAILABLE
                        false -> RecoveryPresentation.NO_BACKUP
                        null -> reasonPresentation
                    },
                    viewModel::retryOpen,
                    onRestore = viewModel::openRestoreFromRecovery,
                    onClear = viewModel::clearLocalBookData,
                )
            }
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
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding),
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
internal fun MaintenanceScreen(
    reason: MaintenanceReason,
    presentation: MaintenancePresentation,
    onCancel: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
) {
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
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding), verticalArrangement = Arrangement.Center) {
            LedgerText(stringResource(R.string.global_maintenance_title), LedgerTextRole.TITLE)
            OperationProgressPanel(
                OperationProgressUiModel(
                    name = maintenanceReasonLabel(reason),
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
                onCancel = onCancel?.takeIf { presentation == MaintenancePresentation.CANCELABLE },
                onRetry = onRetry?.takeIf { presentation == MaintenancePresentation.FAILED },
            )
        }
    }
}

@Composable
private fun maintenanceReasonLabel(reason: MaintenanceReason): String = stringResource(
    when (reason) {
        MaintenanceReason.DATABASE_MIGRATION -> R.string.global_maintenance_reason_migration
        MaintenanceReason.UNFINISHED_OPERATION -> R.string.global_maintenance_reason_interrupted
        MaintenanceReason.PROJECTION_REBUILD -> R.string.global_maintenance_reason_projection
        MaintenanceReason.CONTROLLED_MAINTENANCE -> R.string.global_maintenance_reason_controlled
    },
)

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
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(padding), verticalArrangement = Arrangement.Center) {
            LedgerText(stringResource(R.string.global_recovery_title), LedgerTextRole.TITLE)
            LedgerBanner(explanation, LedgerBannerVariant.DANGER)
            LedgerText(stringResource(R.string.global_recovery_diagnostic, recoveryDiagnosticLabel(code)), LedgerTextRole.SUPPORTING)
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
        }
    }
    if (confirmingClear) {
        val requiredPhrase = stringResource(R.string.global_recovery_clear_phrase)
        LedgerDialog(
            title = stringResource(R.string.global_recovery_clear),
            message = stringResource(R.string.global_recovery_clear_scope),
            confirmLabel = stringResource(R.string.global_recovery_clear),
            onConfirm = onClear,
            onDismiss = {
                confirmingClear = false
                phrase = ""
            },
            danger = true,
            confirmEnabled = phrase == requiredPhrase,
            content = {
                LedgerText(stringResource(R.string.global_recovery_clear_consequence), LedgerTextRole.BODY)
                LedgerText(stringResource(R.string.global_recovery_clear_unaffected), LedgerTextRole.SUPPORTING)
                LedgerTextField(phrase, { phrase = it }, stringResource(R.string.global_recovery_clear), supportingText = requiredPhrase)
            },
        )
    }
}

@Composable
private fun recoveryDiagnosticLabel(code: RecoveryDiagnosticCode): String = stringResource(
    when (code) {
        RecoveryDiagnosticCode.SCHEMA_INVALID -> R.string.global_recovery_code_schema
        RecoveryDiagnosticCode.KEY_UNAVAILABLE -> R.string.global_recovery_code_key
        RecoveryDiagnosticCode.PROJECTION_FAILURE -> R.string.global_recovery_code_projection
        RecoveryDiagnosticCode.DATABASE_UNAVAILABLE -> R.string.global_recovery_code_database
    },
)

@Composable
internal fun RootDestination(
    key: LedgerDestinationKey,
    viewModel: AppRootViewModel,
    accountAmountsVisible: Boolean,
    visibleCurrencyCodes: List<String>,
    onAddAttachment: () -> Unit,
    onBack: () -> Unit,
    onMore: () -> Unit,
    onOperations: () -> Unit,
    onHelp: () -> Unit,
    onPrivacyPolicy: () -> Unit,
    onNavigationChanged: () -> Unit,
) {
    val screenId = key.contract.screenId.value
    SideEffect {
        P37ComposeRecompositionProbe.record(P37ComposeRecompositionProbe.Scope.ROUTE, screenId)
    }
    if (screenId.startsWith("ACC-")) {
        val referenceState by viewModel.referenceData.collectAsStateWithLifecycle()
        val referencePending by viewModel.referenceMutationPending.collectAsStateWithLifecycle()
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
                onCreateReplacementCard = { cardId, accountId ->
                    viewModel.createReplacementCard(cardId, accountId, key)
                    onNavigationChanged()
                },
                onDismissLifecycle = onBack,
            ),
            selectedAccountType = viewModel.selectedAccountType,
            preferredCardAccountId = viewModel.preferredCardAccountId,
            replacementCardId = viewModel.replacementCardId,
            pending = referencePending,
            amountsVisible = accountAmountsVisible,
            visibleCurrencyCodes = visibleCurrencyCodes,
        )
    } else if (screenId == "MGT-001" || screenId.startsWith("CAT-") || screenId.startsWith("MER-") || screenId.startsWith("PLC-")) {
        val referenceState by viewModel.referenceData.collectAsStateWithLifecycle()
        val referencePending by viewModel.referenceMutationPending.collectAsStateWithLifecycle()
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
            placeMap = { points, unavailable, onCoordinateSelected -> PlaceMapContent(points, unavailable, onCoordinateSelected) },
            pending = referencePending,
        )
    } else if (screenId.startsWith("VLT-")) {
        val vaultState by viewModel.vault.collectAsStateWithLifecycle()
        VaultDestination(
            vaultState,
            VaultActions(
                onCard = viewModel::openVaultCard,
                onEdit = viewModel::openVaultEditor,
                onRevealPrimaryNumber = viewModel::revealVaultPrimaryNumber,
                onCopyPrimaryNumber = viewModel::copyVaultPrimaryNumber,
                onRevealSecurityCode = viewModel::revealVaultSecurityCode,
                onHide = viewModel::hideVaultSensitive,
                onAuthenticateEdit = viewModel::authenticateVaultEdit,
                onSave = viewModel::saveVault,
                onAuthenticateList = viewModel::authenticateVaultList,
                onOpenCards = viewModel::openVaultCards,
                onOpenDeviceSecurity = { viewModel.openSecurityPrivacySettings("SYS-004") },
            ),
        )
    } else if (screenId in app.ledger.feature.settings.SUPPORTED_SECURITY_SETTINGS_SCREENS) {
        val securityState by viewModel.securityPrivacy.collectAsStateWithLifecycle()
        SecurityPrivacySettingsDestination(
            securityState.copy(
                screenId = screenId,
                presentation = securityState.presentation.takeIf { it.screenId == screenId }
                    ?: when (screenId) {
                        "SETG-007" -> app.ledger.feature.settings.SecuritySettingsRequiredState.SETG_007_CONTENT
                        "SETG-008" -> app.ledger.feature.settings.SecuritySettingsRequiredState.SETG_008_CONTENT
                        "SETG-009" -> app.ledger.feature.settings.SecuritySettingsRequiredState.SETG_009_DISABLED
                        "SETG-010" -> app.ledger.feature.settings.SecuritySettingsRequiredState.SETG_010_EMPTY
                        "SETG-011" -> app.ledger.feature.settings.SecuritySettingsRequiredState.SETG_011_EMPTY
                        "CLR-001" -> app.ledger.feature.settings.SecuritySettingsRequiredState.CLR_001_CONTENT
                        "SYS-004" -> app.ledger.feature.settings.SecuritySettingsRequiredState.SYS_004_MISSING
                        else -> app.ledger.feature.settings.SecuritySettingsRequiredState.SETG_006_DISABLED
                    },
            ),
            { action ->
                when (action) {
                    is SecurityPrivacyScreenAction.AppLockEnabled -> viewModel.updateAppLockEnabled(action.enabled)
                    is SecurityPrivacyScreenAction.AppLockTimeoutChanged -> viewModel.updateAppLockTimeout(action.timeout, action.customMinutes)
                    SecurityPrivacyScreenAction.TestLock -> viewModel.testAppLock()
                    is SecurityPrivacyScreenAction.GlobalScreenshotBlocked -> viewModel.updateGlobalScreenshotBlocked(action.blocked)
                    is SecurityPrivacyScreenAction.ObscureRecentTasks -> viewModel.updateObscureRecentTasks(action.enabled)
                    is SecurityPrivacyScreenAction.TrashRetentionChanged -> viewModel.updateTrashRetention(action.retention)
                    is SecurityPrivacyScreenAction.CustomTrashRetentionChanged -> viewModel.updateCustomTrashRetention(action.days)
                    SecurityPrivacyScreenAction.OpenTrash -> {
                        val trash = ScreenId("JRN-011")
                        viewModel.navigator.navigate(LedgerRouteContract.destination(trash), app.ledger.core.navigation.SessionGateState.READY)
                        onNavigationChanged()
                    }
                    is SecurityPrivacyScreenAction.TelemetryEnabled -> viewModel.updateTelemetryEnabled(action.enabled)
                    is SecurityPrivacyScreenAction.CrashEnabled -> viewModel.updateCrashEnabled(action.enabled)
                    SecurityPrivacyScreenAction.OpenFeatureQueue -> {
                        viewModel.openSecurityPrivacySettings("SETG-010")
                        onNavigationChanged()
                    }
                    SecurityPrivacyScreenAction.OpenCrashQueue -> {
                        viewModel.openSecurityPrivacySettings("SETG-011")
                        onNavigationChanged()
                    }
                    SecurityPrivacyScreenAction.OpenPrivacyPolicy -> onPrivacyPolicy()
                    SecurityPrivacyScreenAction.DeleteFeatureQueue -> viewModel.deleteFeatureDiagnosticQueue()
                    SecurityPrivacyScreenAction.DeleteCrashQueue -> viewModel.deleteCrashDiagnosticQueue()
                    SecurityPrivacyScreenAction.BeginLocalClear -> viewModel.beginLocalClear()
                    SecurityPrivacyScreenAction.CancelLocalClear -> viewModel.cancelLocalClear()
                    SecurityPrivacyScreenAction.ConfirmLocalClear -> viewModel.confirmLocalClear()
                    SecurityPrivacyScreenAction.OpenSystemSecurity -> viewModel.openSystemSecuritySettings()
                    SecurityPrivacyScreenAction.SecurityConfigured -> viewModel.deviceSecurityConfigured()
                }
            },
        )
    } else if (screenId in setOf("REC-013", "REC-020", "REC-021", "REC-022")) {
        val specializedState by viewModel.specializedTransaction.collectAsStateWithLifecycle()
        SpecializedTransactionRootDestination(
            screenId = screenId,
            encodedArguments = key.encodedArguments,
            state = specializedState,
            viewModel = viewModel,
            onAddAttachment = onAddAttachment,
            onNavigationChanged = onNavigationChanged,
        )
    } else if (screenId in setOf("REC-023", "REC-024", "REC-025")) {
        val batchState by viewModel.batchRecord.collectAsStateWithLifecycle()
        if (batchState != null) {
            BatchRecordRootDestination(
                screenId,
                requireNotNull(batchState),
                viewModel,
                onAddAttachment,
                onNavigationChanged,
            )
        }
    } else if (screenId.startsWith("REC-")) {
        val recordState by viewModel.ordinaryRecord.collectAsStateWithLifecycle()
        OrdinaryRecordRootDestination(
            screenId = screenId,
            state = recordState,
            viewModel = viewModel,
            onAddAttachment = onAddAttachment,
            onNavigationChanged = onNavigationChanged,
        )
    } else if (screenId.startsWith("JRN-")) {
        val journalState by viewModel.journal.collectAsStateWithLifecycle()
        JournalDestination(
            screenId = screenId,
            encodedArguments = key.encodedArguments,
            state = journalState,
            pages = viewModel.journalPages,
            onFirstResponsePresented = { viewModel.onJournalFirstResponsePresented(screenId) },
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
                onPurgeRequested = viewModel::purgeJournalTransaction,
                onEditById = { id, kind -> viewModel.editJournalTransaction(id, kind) },
                onOpenAttachment = viewModel::openAttachment,
                onEdit = { transaction -> viewModel.editJournalTransaction(transaction) },
                onRefund = viewModel::refundJournalTransaction,
                onCopyTemplate = viewModel::copyJournalTransactionToTemplate,
                onPagePresented = viewModel::onJournalPagePresented,
                onBack = onBack,
            ),
        )
    } else if (screenId == "ACC-001") {
        EmptyTopLevel(R.string.global_accounts_empty_title, R.string.global_accounts_empty_message, onMore)
    } else if (screenId == "ANA-001") {
        EmptyTopLevel(R.string.global_analysis_empty_title, R.string.global_analysis_empty_message, onMore)
    } else if (screenId == "G-006") {
        MoreRootDestination(viewModel, key, onOperations, onHelp, onNavigationChanged)
    } else if (screenId == "TRF-001") {
        val export by viewModel.exportFlow.collectAsStateWithLifecycle()
        val backup by viewModel.backupFlow.collectAsStateWithLifecycle()
        val restore by viewModel.restoreFlow.collectAsStateWithLifecycle()
        val durableOperations by viewModel.operationCenter.collectAsStateWithLifecycle()
        LaunchedEffect(screenId) { viewModel.loadOperationCenter() }
        val durableActive = (durableOperations as? OperationCenterLoadState.Content)?.operations.orEmpty()
            .any { it.state in ACTIVE_OPERATION_STATES }
        val active = durableActive || operationCenterPresentation(
            export.screenId,
            export.executionPresentation,
            backup.execution,
            restore,
        ) == OperationCenterPresentation.ACTIVE
        TransferHubScreen(
            TransferHubState(
                operationActive = active,
                notificationPermissionAvailable = viewModel.notificationPermissionPresentation() ==
                    NotificationPermissionPresentation.GRANTED,
            ),
            { action ->
                when (action) {
                    TransferHubScreenAction.OpenImport -> {
                        viewModel.navigateImportSource()
                        onNavigationChanged()
                    }
                    TransferHubScreenAction.OpenExport -> {
                        viewModel.navigateCurrentFilterExport()
                        onNavigationChanged()
                    }
                    TransferHubScreenAction.OpenBackup -> {
                        viewModel.openBackup()
                        onNavigationChanged()
                    }
                    TransferHubScreenAction.OpenRestore -> {
                        viewModel.openRestore()
                        onNavigationChanged()
                    }
                    TransferHubScreenAction.OpenOperations -> onOperations()
                }
            },
        )
    } else if (screenId in setOf("SETG-001", "SETG-002", "SETG-003", "SETG-005", "SETG-012")) {
        RemainingSettingsDestination(
            state = viewModel.remainingSettingsState(screenId),
            onAction = { action ->
                when (action) {
                    is RemainingSettingsScreenAction.Navigate -> {
                        viewModel.navigateP12(key, action.screenId, emptyMap())
                        onNavigationChanged()
                    }
                    is RemainingSettingsScreenAction.ThemeModeChanged -> viewModel.updateSettingsThemeMode(action.mode)
                    is RemainingSettingsScreenAction.DynamicColorChanged -> viewModel.updateSettingsDynamicColor(action.enabled)
                    is RemainingSettingsScreenAction.DefaultAmountsHiddenChanged -> viewModel.updateSettingsDefaultAmountsHidden(action.hidden)
                    is RemainingSettingsScreenAction.ReduceMotionChanged -> viewModel.updateSettingsReduceMotion(action.enabled)
                    is RemainingSettingsScreenAction.LanguageTagChanged -> viewModel.updateSettingsLanguage(action.tag)
                    is RemainingSettingsScreenAction.DateFormatChanged -> viewModel.updateSettingsDateFormat(action.format)
                    is RemainingSettingsScreenAction.ZoneIdChanged -> viewModel.updateSettingsZone(action.zoneId)
                    is RemainingSettingsScreenAction.WeekStartChanged -> viewModel.updateSettingsWeekStart(action.weekStart)
                    RemainingSettingsScreenAction.OpenSourceCode -> viewModel.openSourceCode()
                    RemainingSettingsScreenAction.OpenPrivacyPolicy -> onPrivacyPolicy()
                }
            },
        )
    } else if (screenId == "SETG-004") {
        val currencySettings by viewModel.currencySettings.collectAsStateWithLifecycle()
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
        val operations by viewModel.operationCenter.collectAsStateWithLifecycle()
        LaunchedEffect(screenId) { viewModel.loadOperationCenter() }
        DurableOperationCenterContent(
            operations,
            onBack,
            viewModel::loadOperationCenter,
            viewModel::cancelOperation,
            onRetryOperation = viewModel::retryOperation,
            onReplaceImportSource = viewModel::openImportSourceFromOperationCenter,
        )
    } else if (screenId == "G-008") {
        HelpContent(key.encodedArguments["topicKey"], onBack)
    } else if (screenId == "SYS-002") {
        GovernedDestinationModal(screenId, stringResource(R.string.global_notification_title), onBack) {
            NotificationPermissionContent(
                viewModel.notificationPermissionPresentation(),
                viewModel::requestNotificationPermission,
                viewModel::dismissNotificationPermission,
                viewModel::openNotificationSettings,
            )
        }
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
private fun PlaceMapContent(
    points: List<app.ledger.feature.settings.ManagementMapPoint>,
    unavailable: Boolean,
    onCoordinateSelected: (Int, Int) -> Unit,
) {
    val identity = points.joinToString(separator = "|") { "${it.id}:${it.latitudeE7}:${it.longitudeE7}" }
    var rendererFailed by remember(identity) { mutableStateOf(false) }
    var retryGeneration by remember(identity) { mutableStateOf(0) }
    val summary = stringResource(R.string.global_place_map_summary, points.count())
    val rows = points.map { point ->
        LedgerMapAccessibleRow(point.label, stringResource(R.string.global_location_record_count, point.recordCount))
    }
    val state = if (unavailable || rendererFailed || points.none()) {
        LedgerMapState.Unavailable(summary, rows)
    } else {
        LedgerMapState.Available(
            summary = summary,
            mode = LedgerMapMode.CLUSTERS,
            points = points.map { point ->
                LedgerMapPoint(point.id, point.latitudeE7, point.longitudeE7, point.recordCount.coerceAtLeast(1L))
            },
            accessibleRows = rows,
        )
    }
    key(retryGeneration) {
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
            onCoordinateSelected = onCoordinateSelected,
        )
    }
    if (unavailable || rendererFailed) {
        LedgerBanner(
            stringResource(R.string.global_map_renderer_unavailable),
            LedgerBannerVariant.INFO,
            actionLabel = stringResource(R.string.global_retry),
            onAction = {
                rendererFailed = false
                retryGeneration += 1
            },
        )
    } else if (points.none()) {
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

internal fun exportOperationCenterPresentation(
    screenId: String,
    execution: ExportExecutionPresentation,
): OperationCenterPresentation = if (screenId != "EXP-004") {
    OperationCenterPresentation.EMPTY
} else {
    when (execution) {
        ExportExecutionPresentation.RUNNING, ExportExecutionPresentation.CANCEL_REQUESTED -> OperationCenterPresentation.ACTIVE
        ExportExecutionPresentation.FAILED -> OperationCenterPresentation.FAILED
        ExportExecutionPresentation.SUCCEEDED -> OperationCenterPresentation.COMPLETED
    }
}

internal fun operationCenterPresentation(
    exportScreenId: String,
    exportExecution: ExportExecutionPresentation,
    backupExecution: BackupExecutionPresentation,
    restore: RestoreFlowUiState,
): OperationCenterPresentation {
    val export = exportOperationCenterPresentation(exportScreenId, exportExecution)
    val backup = when (backupExecution) {
        BackupExecutionPresentation.READY -> OperationCenterPresentation.EMPTY
        BackupExecutionPresentation.RUNNING, BackupExecutionPresentation.CANCEL_REQUESTED -> OperationCenterPresentation.ACTIVE
        BackupExecutionPresentation.FAILED -> OperationCenterPresentation.FAILED
        BackupExecutionPresentation.SUCCEEDED -> OperationCenterPresentation.COMPLETED
    }
    val restoreOperation = when (restore.screenId) {
        "RST-006" -> OperationCenterPresentation.ACTIVE
        "RST-007" -> if (restore.resultPresentation == RestoreResultPresentation.SUCCESS) {
            OperationCenterPresentation.COMPLETED
        } else {
            OperationCenterPresentation.FAILED
        }
        else -> OperationCenterPresentation.EMPTY
    }
    return when {
        OperationCenterPresentation.ACTIVE in setOf(export, backup, restoreOperation) -> OperationCenterPresentation.ACTIVE
        OperationCenterPresentation.FAILED in setOf(export, backup, restoreOperation) -> OperationCenterPresentation.FAILED
        OperationCenterPresentation.COMPLETED in setOf(export, backup, restoreOperation) -> OperationCenterPresentation.COMPLETED
        else -> OperationCenterPresentation.EMPTY
    }
}

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
internal fun DurableOperationCenterContent(
    state: OperationCenterLoadState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onCancel: (app.ledger.transfer.domain.BackgroundOperationId) -> Unit,
    modifier: Modifier = Modifier,
    onRetryOperation: (app.ledger.transfer.domain.BackgroundOperationId) -> Unit = {},
    onReplaceImportSource: () -> Unit = {},
) {
    when (state) {
        OperationCenterLoadState.Loading -> LedgerLoadingState(modifier.fillMaxSize())
        is OperationCenterLoadState.Failure -> LedgerErrorState(
            UiErrorCode(state.code),
            stringResource(R.string.global_operations_load_failed),
            onRetry,
            modifier,
        )
        is OperationCenterLoadState.Content -> if (state.operations.isEmpty()) {
            LedgerEmptyState(
                stringResource(R.string.global_operations_empty_title),
                stringResource(R.string.global_operations_empty_message),
                stringResource(R.string.global_back),
                onBack,
                modifier,
            )
        } else {
            LazyColumn(
                modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
            ) {
                item {
                    LedgerText(
                        pluralStringResource(
                            R.plurals.global_operation_count,
                            state.operations.size,
                            state.operations.size,
                        ),
                        LedgerTextRole.SUPPORTING,
                    )
                }
                items(state.operations, key = { it.id.value.toString() }) { operation ->
                    DurableOperationRow(operation, onCancel, onRetryOperation, onReplaceImportSource)
                }
            }
        }
    }
}

@Composable
private fun DurableOperationRow(
    operation: BackgroundOperation,
    onCancel: (app.ledger.transfer.domain.BackgroundOperationId) -> Unit,
    onRetry: (app.ledger.transfer.domain.BackgroundOperationId) -> Unit,
    onReplaceImportSource: () -> Unit,
) {
    val type = stringResource(operationTypeResource(operation.type))
    val userCancelled = operation.cancelRequested && operation.errorCode?.endsWith("_CANCELLED") == true
    val replaceImportSource = operation.requiresReplacementImportSource()
    val displayState = if (replaceImportSource) BackgroundOperationState.FAILED_FINAL else operation.state
    val phase = stringResource(if (userCancelled) R.string.global_operation_cancelled else operationStateResource(displayState))
    val numberFormat = java.text.NumberFormat.getIntegerInstance(LocalLocale.current.platformLocale)
    val totalText = operation.progress.total?.let(numberFormat::format) ?: stringResource(R.string.global_operations_total_unknown)
    val cancelable = operation.state in setOf(
        BackgroundOperationState.QUEUED,
        BackgroundOperationState.PREPARING,
        BackgroundOperationState.RUNNING,
        BackgroundOperationState.PAUSED,
    )
    val progress = operation.progress.total?.takeIf { it > 0L }?.let { total ->
        (operation.progress.current.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
    }
    OperationProgressPanel(
        OperationProgressUiModel(
            name = type,
            phase = phase,
            processedText = stringResource(R.string.global_operations_processed, operation.progress.current, totalText),
            progress = progress,
            capability = if (cancelable) OperationCapability.CANCELABLE else OperationCapability.NON_CANCELABLE_COMMIT,
            statusExplanation = stringResource(
                if (replaceImportSource) {
                    R.string.global_operations_import_reselect_explanation
                } else if (userCancelled) {
                    R.string.global_operations_cancelled_explanation
                } else if (operation.state == BackgroundOperationState.COMMITTING) {
                    R.string.global_operations_committing_explanation
                } else {
                    R.string.global_operations_durable_explanation
                },
            ),
            failureCode = operation.errorCode?.takeUnless { userCancelled }?.let(::UiErrorCode),
        ),
        onCancel = { onCancel(operation.id) }.takeIf { cancelable },
        onRetry = when {
            operation.canRetryFromOperationCenter() -> ({ onRetry(operation.id) })
            replaceImportSource -> onReplaceImportSource
            else -> null
        },
        retryLabel = stringResource(R.string.global_operations_choose_new_source).takeIf { replaceImportSource },
    )
}

internal fun BackgroundOperation.requiresReplacementImportSource(): Boolean = type == BackgroundOperationType.IMPORT &&
    state == BackgroundOperationState.FAILED_RETRYABLE &&
    errorCode != null && errorCode in NON_RETRYABLE_IMPORT_SOURCE_ERRORS

internal fun BackgroundOperation.canRetryFromOperationCenter(): Boolean = state == BackgroundOperationState.FAILED_RETRYABLE &&
    !requiresReplacementImportSource() &&
    type in OPERATION_CENTER_RETRY_TYPES

private val NON_RETRYABLE_IMPORT_SOURCE_ERRORS = setOf("IMPORT_UNSUPPORTED_SOURCE", "IMPORT_INVALID_ENCODING")
private val OPERATION_CENTER_RETRY_TYPES = setOf(
    BackgroundOperationType.IMPORT,
    BackgroundOperationType.EXPORT,
    BackgroundOperationType.FULL_BACKUP,
    BackgroundOperationType.DRIVE_UPLOAD,
    BackgroundOperationType.BACKUP_KEY_ROTATION,
)

private fun operationTypeResource(type: BackgroundOperationType): Int = when (type) {
    BackgroundOperationType.IMPORT -> R.string.global_operation_import
    BackgroundOperationType.EXPORT -> R.string.global_operation_export
    BackgroundOperationType.FULL_BACKUP -> R.string.global_operation_backup
    BackgroundOperationType.DRIVE_UPLOAD -> R.string.global_operation_drive
    BackgroundOperationType.RESTORE_REPLACE -> R.string.global_operation_restore_replace
    BackgroundOperationType.RESTORE_MERGE -> R.string.global_operation_restore_merge
    BackgroundOperationType.ATTACHMENT_MIGRATION -> R.string.global_operation_attachment
    BackgroundOperationType.DATABASE_MAINTENANCE -> R.string.global_operation_maintenance
    BackgroundOperationType.BACKUP_KEY_ROTATION -> R.string.global_operation_key_rotation
}

private fun operationStateResource(state: BackgroundOperationState): Int = when (state) {
    BackgroundOperationState.QUEUED -> R.string.global_operation_queued
    BackgroundOperationState.PREPARING -> R.string.global_operation_preparing
    BackgroundOperationState.RUNNING -> R.string.global_operation_running
    BackgroundOperationState.PAUSED -> R.string.global_operation_paused
    BackgroundOperationState.CANCEL_REQUESTED -> R.string.global_operation_cancel_requested
    BackgroundOperationState.FAILED_RETRYABLE -> R.string.global_operation_failed_retryable
    BackgroundOperationState.FAILED_FINAL -> R.string.global_operation_failed_final
    BackgroundOperationState.COMMITTING -> R.string.global_operation_committing
    BackgroundOperationState.ROLLING_BACK -> R.string.global_operation_rolling_back
    BackgroundOperationState.SUCCEEDED -> R.string.global_operation_succeeded
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
    val topic = HelpTopic.entries.singleOrNull { it.key == topicKey }
    if (topic != null) {
        val topics = if (topic == HelpTopic.GETTING_STARTED) HelpTopic.entries.toList() else listOf(topic)
        LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
            items(topics, key = HelpTopic::key) { helpTopic ->
                LedgerCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                        LedgerText(stringResource(helpTopic.title), LedgerTextRole.SECTION, Modifier.focusable())
                        LedgerText(stringResource(helpTopic.body), LedgerTextRole.BODY, Modifier.focusable())
                    }
                }
            }
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

private enum class HelpTopic(val key: String, val title: Int, val body: Int) {
    GETTING_STARTED("getting-started", R.string.global_help_getting_started, R.string.global_help_body),
    DATA_TRANSFER("data-transfer", R.string.global_help_data_transfer, R.string.global_help_data_transfer_body),
    BACKUP_RESTORE("backup-restore", R.string.global_help_backup_restore, R.string.global_help_backup_restore_body),
    PRIVACY("privacy", R.string.global_help_privacy, R.string.global_help_privacy_body),
    WIDGETS("widgets", R.string.global_help_widgets, R.string.global_help_widgets_body),
}

@Composable
internal fun NotificationPermissionContent(
    presentation: NotificationPermissionPresentation,
    onContinue: () -> Unit,
    onNotNow: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(LedgerTheme.spacing.lg),
        verticalArrangement = Arrangement.Center,
    ) {
        LedgerText(stringResource(R.string.global_notification_title), LedgerTextRole.TITLE)
        LedgerText(stringResource(R.string.global_notification_rationale), LedgerTextRole.BODY)
        if (presentation == NotificationPermissionPresentation.DENIED) {
            LedgerBanner(stringResource(R.string.global_notification_denied), LedgerBannerVariant.WARNING)
            LedgerButton(stringResource(R.string.global_notification_open_settings), onOpenSettings, Modifier.fillMaxWidth())
        } else if (presentation == NotificationPermissionPresentation.FIRST_ASK) {
            LedgerButton(stringResource(R.string.global_notification_continue), onContinue, Modifier.fillMaxWidth())
        } else {
            LedgerBanner(stringResource(R.string.global_notification_granted), LedgerBannerVariant.INFO)
        }
        LedgerButton(
            stringResource(R.string.global_notification_not_now),
            onNotNow,
            Modifier.fillMaxWidth(),
            LedgerButtonVariant.SECONDARY,
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
} else if (screenId in setOf("REC-023", "REC-024", "REC-025")) {
    RecordR.string.batch_entry_title
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
} else if (screenId == "ATT-001") {
    FilesR.string.attachment_preview_title
} else if (screenId == "ATT-002") {
    FilesR.string.attachment_external_open_title
} else if (screenId == "ATT-003") {
    FilesR.string.attachment_rename_title
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
} else if (screenId == "TRF-001") {
    app.ledger.feature.transfer.R.string.transfer_hub_title
} else if (screenId == "SYS-002") {
    R.string.global_notification_title
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
} else if (screenId == "SETG-001") {
    SettingsR.string.settings_title
} else if (screenId == "SETG-002") {
    SettingsR.string.settings_appearance
} else if (screenId == "SETG-003") {
    SettingsR.string.settings_language_region
} else if (screenId == "SETG-004") {
    R.string.global_currencies
} else if (screenId == "SETG-005") {
    SettingsR.string.settings_calendar
} else if (screenId == "VLT-001") {
    VaultR.string.vault_title
} else if (screenId == "VLT-002") {
    VaultR.string.vault_card_title
} else if (screenId == "VLT-003") {
    VaultR.string.vault_edit_title
} else if (screenId == "SETG-006") {
    SettingsR.string.security_app_lock
} else if (screenId == "SETG-007") {
    R.string.global_screen_privacy
} else if (screenId == "SETG-008") {
    R.string.global_trash_settings
} else if (screenId == "SETG-009") {
    R.string.global_diagnostics
} else if (screenId == "SETG-010") {
    SettingsR.string.diagnostics_feature
} else if (screenId == "SETG-011") {
    SettingsR.string.diagnostics_crash
} else if (screenId == "SETG-012") {
    SettingsR.string.settings_about
} else if (screenId == "CLR-001") {
    SettingsR.string.clear_local
} else if (screenId == "SYS-004") {
    SettingsR.string.security_open_system
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
