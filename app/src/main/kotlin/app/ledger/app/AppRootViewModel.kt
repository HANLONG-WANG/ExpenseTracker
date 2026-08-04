@file:Suppress("LongMethod", "TooManyFunctions", "MagicNumber", "LongParameterList", "LargeClass", "MaxLineLength", "ReturnCount")

package app.ledger.app

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.ledger.app.settings.DestinationProto
import app.ledger.app.settings.LedgerAppSettings
import app.ledger.app.settings.NavigationSnapshotProto
import app.ledger.app.settings.RouteArgumentProto
import app.ledger.app.settings.TopLevelStackProto
import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.core.designsystem.LedgerReferenceDisplayDefaults
import app.ledger.core.geo.ForegroundLocationSaveSession
import app.ledger.core.geo.ProductionForegroundLocationClient
import app.ledger.core.money.CurrencyCode
import app.ledger.core.navigation.DestinationSnapshot
import app.ledger.core.navigation.EncodedRouteArgument
import app.ledger.core.navigation.FiveStackNavigator
import app.ledger.core.navigation.FiveStackSnapshot
import app.ledger.core.navigation.LedgerDestinationKey
import app.ledger.core.navigation.LedgerRouteContract
import app.ledger.core.navigation.SafeRouteArgument
import app.ledger.core.navigation.ScreenId
import app.ledger.core.navigation.SessionGateState
import app.ledger.core.navigation.StableIdArgument
import app.ledger.core.navigation.TopLevelDestination
import app.ledger.core.navigation.TopLevelStackSnapshot
import app.ledger.core.security.AndroidKeystoreKeys
import app.ledger.core.security.AppLockController
import app.ledger.core.security.AppLockSettings
import app.ledger.core.security.AppLockState
import app.ledger.core.security.AppLockTimeout
import app.ledger.core.security.Argon2idCalibrator
import app.ledger.core.security.BookSessionManager
import app.ledger.core.security.BookSessionState
import app.ledger.core.security.DefaultLedgerStartupInspector
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.core.security.DeviceSecurityCapability
import app.ledger.core.security.RecoveryPassword
import app.ledger.core.security.RecoveryPasswordKeyWrapper
import app.ledger.core.security.RecoveryWrappedKeyMaterial
import app.ledger.core.security.SecretBytes
import app.ledger.core.security.SecurityAssociatedData
import app.ledger.core.security.SqlCipherBookDatabaseResourceFactory
import app.ledger.core.security.VaultExposureRegistry
import app.ledger.core.time.InjectedJavaClock
import app.ledger.feature.accounts.AccountEditorSubmission
import app.ledger.feature.accounts.CardEditorSubmission
import app.ledger.feature.accounts.CheckpointSubmission
import app.ledger.feature.accounts.OpeningBalanceSubmission
import app.ledger.feature.journal.JournalLoadState
import app.ledger.feature.journal.JournalOperationState
import app.ledger.feature.journal.JournalPagingSource
import app.ledger.feature.journal.JournalSelectionPolicy
import app.ledger.feature.onboarding.InitialAccountType
import app.ledger.feature.onboarding.InitialCategoryDirection
import app.ledger.feature.onboarding.OnboardingLanguage
import app.ledger.feature.onboarding.OnboardingRenderState
import app.ledger.feature.onboarding.OnboardingStep
import app.ledger.feature.onboarding.OnboardingUiState
import app.ledger.feature.onboarding.OnboardingValidator
import app.ledger.feature.record.OrdinaryRecordEditorState
import app.ledger.feature.record.OrdinaryRecordLoadState
import app.ledger.feature.record.OrdinaryRecordPolicy
import app.ledger.feature.record.RecordEditorMode
import app.ledger.feature.record.RecordEditorPresentation
import app.ledger.feature.record.RecordField
import app.ledger.feature.record.RecordTab
import app.ledger.feature.record.RefundEditorState
import app.ledger.feature.record.RefundLoadState
import app.ledger.feature.record.RefundPickerState
import app.ledger.feature.record.RefundPolicy
import app.ledger.feature.record.RefundPresentation
import app.ledger.feature.record.SpecializedPresentation
import app.ledger.feature.record.SpecializedTransactionEditorState
import app.ledger.feature.record.SpecializedTransactionKind
import app.ledger.feature.record.SpecializedTransactionLoadState
import app.ledger.feature.record.SpecializedTransactionPolicy
import app.ledger.feature.settings.CategorySubmission
import app.ledger.feature.settings.CurrencySettingsPolicy
import app.ledger.feature.settings.CurrencySettingsState
import app.ledger.feature.settings.MerchantSubmission
import app.ledger.feature.settings.PlaceSubmission
import app.ledger.finance.application.AccountDraft
import app.ledger.finance.application.AttachmentContentSource
import app.ledger.finance.application.AttachmentImportRequest
import app.ledger.finance.application.BookAttachmentObjectPort
import app.ledger.finance.application.CardDraft
import app.ledger.finance.application.CategoryDraft
import app.ledger.finance.application.InitialAccountCommand
import app.ledger.finance.application.InitialCategoryCommand
import app.ledger.finance.application.InitializeLedgerCommand
import app.ledger.finance.application.JournalApplicationPort
import app.ledger.finance.application.JournalBulkEditPatch
import app.ledger.finance.application.JournalBulkEditRequest
import app.ledger.finance.application.JournalDependencyView
import app.ledger.finance.application.JournalMutationIds
import app.ledger.finance.application.JournalMutationRequest
import app.ledger.finance.application.JournalSavedFilterCommand
import app.ledger.finance.application.JournalSelectionSpec
import app.ledger.finance.application.JournalTransactionView
import app.ledger.finance.application.LedgerGenesisIds
import app.ledger.finance.application.LedgerInitializationPort
import app.ledger.finance.application.MerchantDraft
import app.ledger.finance.application.OpeningBalanceWriteIds
import app.ledger.finance.application.OpeningBalanceWritePort
import app.ledger.finance.application.OpeningBalanceWriteRequest
import app.ledger.finance.application.OrdinaryAmountDraft
import app.ledger.finance.application.OrdinaryDirection
import app.ledger.finance.application.OrdinaryLocationDraft
import app.ledger.finance.application.OrdinaryLocationProvider
import app.ledger.finance.application.OrdinaryTransactionEntryPort
import app.ledger.finance.application.OrdinaryTransactionWriteIds
import app.ledger.finance.application.OrdinaryTransactionWriteRequest
import app.ledger.finance.application.PlaceDraft
import app.ledger.finance.application.ReferenceDataManagementPort
import app.ledger.finance.application.ReferenceDataSnapshot
import app.ledger.finance.application.ReferenceMutation
import app.ledger.finance.application.ReferenceMutationCommand
import app.ledger.finance.application.ReferenceMutationIds
import app.ledger.finance.application.RefundApplicationPort
import app.ledger.finance.application.RefundSearchQuery
import app.ledger.finance.application.RefundWriteIds
import app.ledger.finance.application.RefundWriteRequest
import app.ledger.finance.application.SpecializedFxQuoteRequest
import app.ledger.finance.application.SpecializedTransactionContext
import app.ledger.finance.application.SpecializedTransactionEntryPort
import app.ledger.finance.application.SpecializedTransactionWriteIds
import app.ledger.finance.application.SpecializedTransactionWriteRequest
import app.ledger.finance.application.UpdateBookLocaleCommand
import app.ledger.finance.data.RoomLedgerStartupInspector
import app.ledger.finance.domain.BalanceAdjustmentDirection
import app.ledger.finance.domain.CategoryDirection
import app.ledger.finance.domain.CategoryRemovalStrategy
import app.ledger.finance.domain.DependencyPolicy
import app.ledger.finance.domain.DependencyResolution
import app.ledger.finance.domain.RefundAccrualPolicy
import app.ledger.finance.domain.RefundBudgetPolicy
import app.ledger.finance.domain.RefundGoalPolicy
import app.ledger.finance.domain.RefundProjectPolicy
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.SystemLedgerCode
import app.ledger.finance.domain.TransactionDependency
import app.ledger.finance.domain.TransactionFilter
import app.ledger.finance.domain.TransactionId
import app.ledger.finance.domain.TransactionLifecycleState
import app.ledger.finance.domain.TransactionSource
import app.ledger.finance.domain.UserAccountType
import com.google.protobuf.ByteString
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject

internal sealed interface AppRootState {
    data object Starting : AppRootState
    data class Onboarding(val state: OnboardingUiState) : AppRootState
    data class Session(
        val state: BookSessionState,
        val authentication: AppAuthenticationState,
        val unsavedContentLossNotice: Boolean,
    ) : AppRootState
}

internal enum class AppAuthenticationState {
    BIOMETRIC_AVAILABLE,
    CREDENTIAL_ONLY,
    AUTHENTICATING,
    AUTH_FAILED,
    LOCKED_OUT,
}

internal enum class AppAuthenticationError { FAILED, LOCKED_OUT, CANCELED, DEVICE_SECURITY_CHANGED }

internal enum class GlobalSnackbarMessage { SETTINGS_WRITE_FAILED, LOCAL_CLEAR_FAILED }

internal sealed interface AppReferenceDataState {
    data object Loading : AppReferenceDataState
    data class Content(val snapshot: ReferenceDataSnapshot) : AppReferenceDataState
    data class Error(val code: String) : AppReferenceDataState
}

@HiltViewModel
internal class AppRootViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: AppSettingsRepository,
    private val keyProvider: DeviceLedgerKeyProvider,
    private val initializationPort: LedgerInitializationPort,
    private val referenceDataPort: ReferenceDataManagementPort,
    private val journalApplicationPort: JournalApplicationPort,
    private val openingBalanceWritePort: OpeningBalanceWritePort,
    private val ordinaryTransactionEntryPort: OrdinaryTransactionEntryPort,
    private val refundApplicationPort: RefundApplicationPort,
    private val specializedTransactionEntryPort: SpecializedTransactionEntryPort,
    private val bookAttachmentObjectPort: BookAttachmentObjectPort,
    private val runtimeSources: AppRuntimeSources,
) : ViewModel() {
    private val mutableRootState = MutableStateFlow<AppRootState>(AppRootState.Starting)
    val rootState: StateFlow<AppRootState> = mutableRootState.asStateFlow()

    private val mutableAuthenticationRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val authenticationRequests = mutableAuthenticationRequests.asSharedFlow()

    private val mutableGlobalSnackbarMessages = MutableSharedFlow<GlobalSnackbarMessage>(extraBufferCapacity = 1)
    val globalSnackbarMessages = mutableGlobalSnackbarMessages.asSharedFlow()

    val settings: StateFlow<LedgerAppSettings> = settingsRepository.data.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        LedgerAppSettings.getDefaultInstance(),
    )

    private val mutableReferenceData = MutableStateFlow<AppReferenceDataState>(AppReferenceDataState.Loading)
    val referenceData: StateFlow<AppReferenceDataState> = mutableReferenceData.asStateFlow()
    private val mutableReferenceMutationPending = MutableStateFlow(false)
    val referenceMutationPending: StateFlow<Boolean> = mutableReferenceMutationPending.asStateFlow()
    private val mutableOrdinaryRecord = MutableStateFlow<OrdinaryRecordLoadState>(OrdinaryRecordLoadState.Loading)
    val ordinaryRecord: StateFlow<OrdinaryRecordLoadState> = mutableOrdinaryRecord.asStateFlow()
    private val mutableOrdinaryRecordPending = MutableStateFlow(false)
    val ordinaryRecordPending: StateFlow<Boolean> = mutableOrdinaryRecordPending.asStateFlow()
    private val mutableRefund = MutableStateFlow<RefundLoadState>(RefundLoadState.Loading)
    val refund: StateFlow<RefundLoadState> = mutableRefund.asStateFlow()
    private val mutableRefundPicker = MutableStateFlow<RefundPickerState>(RefundPickerState.Loading)
    val refundPicker: StateFlow<RefundPickerState> = mutableRefundPicker.asStateFlow()
    private val mutableRefundPending = MutableStateFlow(false)
    val refundPending: StateFlow<Boolean> = mutableRefundPending.asStateFlow()
    private val mutableSpecializedTransaction = MutableStateFlow<SpecializedTransactionLoadState>(SpecializedTransactionLoadState.Loading)
    val specializedTransaction: StateFlow<SpecializedTransactionLoadState> = mutableSpecializedTransaction.asStateFlow()
    private val mutableSpecializedTransactionPending = MutableStateFlow(false)
    val specializedTransactionPending: StateFlow<Boolean> = mutableSpecializedTransactionPending.asStateFlow()
    private val mutableCurrencySettings = MutableStateFlow<CurrencySettingsState?>(null)
    val currencySettings: StateFlow<CurrencySettingsState?> = mutableCurrencySettings.asStateFlow()
    private val mutableJournal = MutableStateFlow<JournalLoadState>(JournalLoadState.Loading)
    val journal: StateFlow<JournalLoadState> = mutableJournal.asStateFlow()
    private val mutableJournalPagingRequest = MutableStateFlow<JournalPagingRequest?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val journalPages = mutableJournalPagingRequest.flatMapLatest { request ->
        if (request == null) {
            flowOf(PagingData.empty())
        } else {
            Pager(
                PagingConfig(pageSize = 40, prefetchDistance = 10, initialLoadSize = 40, enablePlaceholders = false, maxSize = 200),
            ) { JournalPagingSource(journalApplicationPort, request.bookId, request.filter, request.runningBalanceAccountId) }.flow
        }
    }.cachedIn(viewModelScope)
    var selectedAccountType: UserAccountType = UserAccountType.CASH
        private set
    private var pendingCardAccountId: StableId? = null
    val preferredCardAccountId: StableId?
        get() = pendingCardAccountId

    private var onboardingState = OnboardingUiState()
    private var sessionManager: BookSessionManager? = null
    private var appLockController: AppLockController? = null
    private var unsavedContentLossNotice: Boolean = false
    val navigator: FiveStackNavigator = FiveStackNavigator()
    private var pendingDeepLink: LedgerDestinationKey? = null
    private val scrollStates = mutableMapOf<TopLevelDestination, Pair<String, Int>>()
    private var recordLocationSession: ForegroundLocationSaveSession? = null
    private var recordAttachmentImportJob: Job? = null
    private var specializedAttachmentImportJob: Job? = null
    private var pendingRecordExit: PendingRecordExit? = null

    init {
        viewModelScope.launch { start() }
    }

    private suspend fun start() {
        val saved = settingsRepository.current()
        if (!saved.onboardingComplete) {
            onboardingState = OnboardingUiState(
                step = saved.onboardingStep.toDomain(),
                language = saved.languageTag.takeIf(String::isNotBlank)?.let(::languageFromTag),
                baseCurrency = saved.baseCurrency.takeIf(String::isNotBlank) ?: DEFAULT_CURRENCY,
                zoneId = saved.zoneId.takeIf(String::isNotBlank) ?: ZoneId.systemDefault().id.takeIf { it == DEFAULT_ZONE },
                privacyAccepted = saved.privacyAccepted,
                telemetryEnabled = saved.telemetryEnabled,
                crashReportingEnabled = saved.crashReportingEnabled,
                appLockEnabled = saved.appLockEnabled,
                appLockTimeoutMillis = saved.appLockTimeoutMillis.takeIf { it in OnboardingUiState.ALLOWED_TIMEOUTS } ?: 60_000L,
                firstAccountCreated = saved.firstAccountCreated,
                firstCategoryCreated = saved.firstCategoryCreated,
                recoveryConfigured = saved.recoveryPasswordConfigured,
            )
            publishOnboarding()
        } else {
            openSavedBook(saved)
        }
    }

    fun selectLanguage(value: OnboardingLanguage) = updateOnboarding { copy(language = value, errorCode = null) }
    fun updateCurrencySearch(value: String) = updateOnboarding { copy(currencySearch = value.take(12)) }
    fun selectCurrency(value: String) = updateOnboarding { copy(baseCurrency = value, errorCode = null) }
    fun updateZoneSearch(value: String) = updateOnboarding { copy(zoneSearch = value.take(64)) }
    fun selectZone(value: String) = updateOnboarding { copy(zoneId = value, errorCode = null) }
    fun setPrivacyAccepted(value: Boolean) = updateOnboarding { copy(privacyAccepted = value, errorCode = null) }
    fun setTelemetry(value: Boolean) = updateOnboarding { copy(telemetryEnabled = value) }
    fun setCrashReporting(value: Boolean) = updateOnboarding { copy(crashReportingEnabled = value) }
    fun setAppLock(value: Boolean) = updateOnboarding { copy(appLockEnabled = value, errorCode = null) }
    fun setAppLockTimeout(value: Long) = updateOnboarding { copy(appLockTimeoutMillis = value, errorCode = null) }
    fun updateRecoveryPassword(value: String) = updateOnboarding {
        copy(recoveryPassword = value.take(MAX_RECOVERY_LENGTH), errorCode = null)
    }

    fun updateRecoveryConfirmation(value: String) = updateOnboarding {
        copy(recoveryPasswordConfirmation = value.take(MAX_RECOVERY_LENGTH), errorCode = null)
    }
    fun updateAccountName(value: String) = updateOnboarding { copy(accountName = value.take(MAX_REFERENCE_NAME), errorCode = null) }
    fun setAccountType(value: InitialAccountType) = updateOnboarding { copy(accountType = value) }
    fun updateCategoryName(value: String) = updateOnboarding { copy(categoryName = value.take(MAX_REFERENCE_NAME), errorCode = null) }
    fun setCategoryDirection(value: InitialCategoryDirection) = updateOnboarding { copy(categoryDirection = value) }

    fun onboardingBack() {
        if (onboardingState.renderState == OnboardingRenderState.SUBMITTING) return
        updateOnboarding { copy(step = step.previous(), renderState = OnboardingRenderState.CONTENT, errorCode = null) }
        viewModelScope.launch { settingsRepository.saveStep(onboardingState.step) }
    }

    fun onboardingSkip() {
        if (!onboardingState.step.optional || onboardingState.renderState == OnboardingRenderState.SUBMITTING) return
        clearRecoveryPlaintextIfLeavingBackup()
        advanceOnboarding()
    }

    fun onboardingNext() {
        if (onboardingState.renderState == OnboardingRenderState.SUBMITTING) return
        val error = OnboardingValidator.errorCode(onboardingState)
        if (error != null) {
            updateOnboarding { copy(renderState = OnboardingRenderState.VALIDATION_ERROR, errorCode = error) }
            return
        }
        viewModelScope.launch {
            updateOnboarding { copy(renderState = OnboardingRenderState.SUBMITTING, errorCode = null) }
            val result = runCatching { persistCurrentStep() }
            if (result.isSuccess) {
                if (onboardingState.step == OnboardingStep.COMPLETE) {
                    val completed = settingsRepository.update { it.onboardingComplete = true }
                    clearRecoveryPlaintextIfLeavingBackup()
                    openSavedBook(completed)
                } else {
                    advanceOnboarding()
                }
            } else {
                clearRecoveryPlaintextIfLeavingBackup()
                mutableGlobalSnackbarMessages.tryEmit(GlobalSnackbarMessage.SETTINGS_WRITE_FAILED)
                val errorCode = result.exceptionOrNull()?.message
                    ?.takeIf { it == "DEVICE_SECURITY_REQUIRED" }
                    ?: "SETTINGS_WRITE_FAILED"
                updateOnboarding {
                    copy(renderState = OnboardingRenderState.VALIDATION_ERROR, errorCode = errorCode)
                }
            }
        }
    }

    private suspend fun persistCurrentStep() {
        when (onboardingState.step) {
            OnboardingStep.LANGUAGE -> settingsRepository.update { it.languageTag = requireNotNull(onboardingState.language).tag }
            OnboardingStep.BASE_CURRENCY -> settingsRepository.update { it.baseCurrency = requireNotNull(onboardingState.baseCurrency) }
            OnboardingStep.TIME_ZONE -> {
                val zone = ZoneId.of(requireNotNull(onboardingState.zoneId))
                settingsRepository.update { it.zoneId = zone.id }
                initializeBookIfNeeded(zone)
            }
            OnboardingStep.PRIVACY_POLICY -> settingsRepository.update { it.privacyAccepted = onboardingState.privacyAccepted }
            OnboardingStep.TELEMETRY -> settingsRepository.update {
                it.telemetryEnabled = onboardingState.telemetryEnabled
                it.crashReportingEnabled = onboardingState.crashReportingEnabled
            }
            OnboardingStep.APP_LOCK -> {
                val securityCapability = AndroidKeystoreKeys(context).deviceSecurityCapability()
                if (onboardingState.appLockEnabled && securityCapability == DeviceSecurityCapability.MISSING_DEVICE_CREDENTIAL) {
                    error("DEVICE_SECURITY_REQUIRED")
                }
                settingsRepository.update {
                    it.appLockEnabled = onboardingState.appLockEnabled
                    it.appLockTimeoutMillis = onboardingState.appLockTimeoutMillis
                }
            }
            OnboardingStep.BACKUP -> persistRecoveryVerifier()
            OnboardingStep.ACCOUNT -> createFirstAccount()
            OnboardingStep.CATEGORY -> createFirstCategory()
            OnboardingStep.COMPLETE -> Unit
        }
    }

    private suspend fun initializeBookIfNeeded(zone: ZoneId) = withContext(Dispatchers.IO) {
        var saved = settingsRepository.current()
        val bookId = saved.bookId.toByteArray().takeIf { it.size == StableId.BYTE_COUNT }
            ?.let { StableId.fromBytes(it).getOrNull() }
            ?: nextId().also { generated ->
                saved = settingsRepository.update { it.bookId = ByteString.copyFrom(generated.bytes) }
            }
        val currency = requireNotNull(CurrencyCode.parse(saved.baseCurrency.ifBlank { DEFAULT_CURRENCY }).getOrNull())
        val command = InitializeLedgerCommand(
            ids = LedgerGenesisIds(
                bookId = bookId,
                commitId = nextId(),
                bookRevisionId = nextId(),
                deviceInstanceId = nextId(),
                systemLedgerIds = SystemLedgerCode.entries.associateWith { nextId() },
            ),
            baseCurrency = currency,
            defaultZoneId = zone,
            createdAt = runtimeSources.clock.now(),
        )
        initializationPort.initialize(command).requireSuccess()
        initializationPort.updateBookLocale(
            bookId = bookId,
            command = UpdateBookLocaleCommand(
                baseCurrency = currency,
                defaultZoneId = zone,
                commitId = nextId(),
                revisionId = nextId(),
                deviceInstanceId = nextId(),
                changedAt = runtimeSources.clock.now(),
            ),
        ).requireSuccess()
    }

    private suspend fun persistRecoveryVerifier() = withContext(Dispatchers.Default) {
        if (onboardingState.recoveryPassword.isEmpty()) return@withContext
        val saved = settingsRepository.current()
        val bookId = requireBookId(saved)
        val chars = onboardingState.recoveryPassword.toCharArray()
        val password = RecoveryPassword.copyOf(chars)
        chars.fill('\u0000')
        val verifierBytes = ByteArray(RECOVERY_VERIFIER_BYTES).also(runtimeSources.cryptographicRandom::nextBytes)
        val verifier = SecretBytes.copyOf(verifierBytes)
        verifierBytes.fill(0)
        val associatedData = SecurityAssociatedData.recoveryBundle(bookId, 1)
        try {
            val parameters = Argon2idCalibrator().calibrate()
            val wrapped = RecoveryPasswordKeyWrapper().wrap(password, verifier, parameters, associatedData)
            val encoded = encodeRecoveryEnvelope(wrapped)
            try {
                settingsRepository.update {
                    it.recoveryPasswordConfigured = true
                    it.recoveryWrappedVerifier = ByteString.copyFrom(encoded)
                }
                updateOnboarding { copy(recoveryConfigured = true) }
            } finally {
                encoded.fill(0)
            }
        } finally {
            associatedData.fill(0)
            verifier.close()
            password.close()
            clearRecoveryPlaintextIfLeavingBackup()
        }
    }

    private suspend fun createFirstAccount() = withContext(Dispatchers.IO) {
        val saved = settingsRepository.current()
        val command = InitialAccountCommand(
            accountId = nextId(),
            ledgerAccountId = nextId(),
            commitId = nextId(),
            revisionId = nextId(),
            deviceInstanceId = nextId(),
            createdAt = runtimeSources.clock.now(),
            type = if (onboardingState.accountType == InitialAccountType.CASH) UserAccountType.CASH else UserAccountType.BANK,
            name = onboardingState.accountName.trim(),
            currency = requireNotNull(CurrencyCode.parse(saved.baseCurrency).getOrNull()),
            iconKey = LedgerReferenceDisplayDefaults.ACCOUNT_ICON_KEY,
            colorArgb = LedgerReferenceDisplayDefaults.COLOR_ARGB,
        )
        initializationPort.createFirstAccount(requireBookId(saved), command).requireSuccess()
        settingsRepository.update { it.firstAccountCreated = true }
        updateOnboarding { copy(firstAccountCreated = true) }
    }

    private suspend fun createFirstCategory() = withContext(Dispatchers.IO) {
        val saved = settingsRepository.current()
        val direction = if (onboardingState.categoryDirection == InitialCategoryDirection.EXPENSE) {
            CategoryDirection.EXPENSE
        } else {
            CategoryDirection.INCOME
        }
        val command = InitialCategoryCommand(
            categoryId = nextId(),
            commitId = nextId(),
            revisionId = nextId(),
            deviceInstanceId = nextId(),
            createdAt = runtimeSources.clock.now(),
            direction = direction,
            name = onboardingState.categoryName.trim(),
            normalizedName = onboardingState.categoryName.trim().lowercase(Locale.ROOT),
            statisticalNature = if (direction == CategoryDirection.EXPENSE) {
                StatisticalNature.CONSUMPTION_EXPENSE
            } else {
                StatisticalNature.REGULAR_INCOME
            },
            iconKey = LedgerReferenceDisplayDefaults.CATEGORY_ICON_KEY,
            colorArgb = LedgerReferenceDisplayDefaults.COLOR_ARGB,
        )
        initializationPort.createFirstCategory(requireBookId(saved), command).requireSuccess()
        settingsRepository.update { it.firstCategoryCreated = true }
        updateOnboarding { copy(firstCategoryCreated = true) }
    }

    private suspend fun openSavedBook(saved: LedgerAppSettings) {
        val bookId = requireBookId(saved)
        restoreNavigationIfAllowed(saved)
        unsavedContentLossNotice = settingsRepository.consumeUnsavedContentLoss()
        val manager = BookSessionManager(
            bookId,
            keyProvider,
            SqlCipherBookDatabaseResourceFactory(
                context,
                listOf(DefaultLedgerStartupInspector, RoomLedgerStartupInspector()),
            ),
            VaultExposureRegistry(SystemClock::elapsedRealtime),
        )
        sessionManager = manager
        val lockSettings = AppLockSettings(saved.appLockEnabled, timeout(saved.appLockTimeoutMillis))
        appLockController = AppLockController(lockSettings, SystemClock::elapsedRealtime) {
            viewModelScope.launch { manager.lockUi() }
        }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            manager.state.collectLatest { state ->
                publishSession(state)
                if (state is BookSessionState.Ready) {
                    consumePendingDeepLink()
                    loadReferenceData()
                    loadOrdinaryRecord()
                    loadJournal()
                }
            }
        }
        manager.initialize()
        if (!saved.appLockEnabled) manager.unlockUi()
    }

    fun beginAuthentication() {
        val current = mutableRootState.value as? AppRootState.Session ?: return
        if (current.state != BookSessionState.Locked || current.authentication == AppAuthenticationState.AUTHENTICATING) return
        publishSession(current.state, AppAuthenticationState.AUTHENTICATING)
        mutableAuthenticationRequests.tryEmit(Unit)
    }

    fun authenticationSucceeded() {
        appLockController?.authenticationSucceeded()
        viewModelScope.launch { sessionManager?.unlockUi() }
    }

    fun authenticationFailed(error: AppAuthenticationError) {
        val state = when (error) {
            AppAuthenticationError.LOCKED_OUT -> AppAuthenticationState.LOCKED_OUT
            AppAuthenticationError.FAILED,
            AppAuthenticationError.CANCELED,
            AppAuthenticationError.DEVICE_SECURITY_CHANGED,
            -> AppAuthenticationState.AUTH_FAILED
        }
        val session = (mutableRootState.value as? AppRootState.Session)?.state ?: BookSessionState.Locked
        publishSession(session, state)
    }

    fun onApplicationBackgrounded() {
        appLockController?.onApplicationBackgrounded()
        viewModelScope.launch { persistNavigationIfAllowed() }
    }

    fun onApplicationForegrounded() {
        if (appLockController?.onApplicationForegrounded() == AppLockState.Locked) {
            viewModelScope.launch { sessionManager?.lockUi() }
        }
    }

    fun retryOpen() {
        val state = (mutableRootState.value as? AppRootState.Session)?.state
        if (state == BookSessionState.Locked) viewModelScope.launch { sessionManager?.unlockUi() }
    }

    fun clearLocalBookData() {
        viewModelScope.launch(Dispatchers.IO) {
            val saved = settingsRepository.current()
            val bookId = saved.bookId.toByteArray().takeIf { it.size == StableId.BYTE_COUNT }
                ?.let { StableId.fromBytes(it).getOrNull() }
            sessionManager?.close()
            sessionManager = null
            val cleared = bookId?.let { initializationPort.clearLocalBook(it) }
            if (cleared !is DomainResult.Success) {
                mutableGlobalSnackbarMessages.tryEmit(GlobalSnackbarMessage.LOCAL_CLEAR_FAILED)
                openSavedBook(saved)
                return@launch
            }
            settingsRepository.reset()
            onboardingState = OnboardingUiState(
                baseCurrency = DEFAULT_CURRENCY,
                zoneId = ZoneId.systemDefault().id.takeIf { it == DEFAULT_ZONE },
            )
            withContext(Dispatchers.Main.immediate) { publishOnboarding() }
        }
    }

    /** Parses only registered no-argument route IDs and keeps the pending destination in memory behind SessionGate. */
    fun handleDeepLink(uri: Uri?) {
        val destination = uri?.let(::parseDeepLink) ?: return
        pendingDeepLink = destination
        consumePendingDeepLink()
    }

    private fun parseDeepLink(uri: Uri): LedgerDestinationKey? = runCatching {
        require(uri.scheme == DEEP_LINK_SCHEME)
        require(uri.host == DEEP_LINK_HOST)
        require(uri.query == null)
        uri.pathSegments.singleOrNull()?.let { screen ->
            val contract = LedgerRouteContract.screen(ScreenId(screen))
            require(contract.parameters.isEmpty())
            LedgerRouteContract.destination(contract.screenId)
        }
    }.getOrNull()

    fun updateScrollState(topLevel: TopLevelDestination, stableKey: String, offset: Int) {
        require(Regex("[A-Za-z][A-Za-z0-9._-]{0,63}").matches(stableKey))
        require(offset >= 0)
        scrollStates[topLevel] = stableKey to offset
    }

    fun dismissUnsavedContentLossNotice() {
        unsavedContentLossNotice = false
        val state = (mutableRootState.value as? AppRootState.Session)?.state ?: return
        publishSession(state)
    }

    fun loadReferenceData() {
        if ((mutableRootState.value as? AppRootState.Session)?.state !is BookSessionState.Ready) return
        viewModelScope.launch(Dispatchers.IO) {
            val bookId = runCatching { requireBookId(settingsRepository.current()) }.getOrNull() ?: return@launch
            mutableReferenceData.value = AppReferenceDataState.Loading
            mutableReferenceData.value = when (val result = referenceDataPort.snapshot(bookId)) {
                is DomainResult.Success -> {
                    updateCurrencySettings(result.value)
                    AppReferenceDataState.Content(result.value)
                }
                is DomainResult.Failure -> AppReferenceDataState.Error(result.error.code)
            }
        }
    }

    fun loadJournal() {
        if ((mutableRootState.value as? AppRootState.Session)?.state !is BookSessionState.Ready) return
        viewModelScope.launch(Dispatchers.IO) {
            val bookId = runCatching { requireBookId(settingsRepository.current()) }.getOrNull() ?: return@launch
            val current = mutableJournal.value as? JournalLoadState.Content
            val presets = (journalApplicationPort.savedFilters(bookId) as? DomainResult.Success)?.value.orEmpty()
            val options = (journalApplicationPort.bulkEditOptions(bookId) as? DomainResult.Success)?.value
                ?: return@launch run { mutableJournal.value = JournalLoadState.Failure("JOURNAL_OPTIONS_FAILED") }
            val defaultPreset = presets.singleOrNull { it.isDefault }
            val filter = current?.filter ?: defaultPreset?.filter ?: TransactionFilter(lifecycleStates = setOf(TransactionLifecycleState.ACTIVE))
            mutableJournal.value = (current ?: JournalLoadState.Content()).copy(
                filter = filter,
                searchText = filter.searchText.orEmpty(),
                presets = presets,
                bulkOptions = options,
                activePresetId = current?.activePresetId ?: defaultPreset?.id,
            )
            mutableJournalPagingRequest.value = JournalPagingRequest(bookId, filter, refreshEpoch = (mutableJournalPagingRequest.value?.refreshEpoch ?: 0) + 1)
        }
    }

    fun updateJournalSearch(value: String) {
        val query = value.take(RECORD_SEARCH_LIMIT)
        updateJournalContent { copy(searchText = query, filter = filter.copy(searchText = query.takeIf(String::isNotBlank))) }
        refreshJournalPaging()
    }

    fun applyJournalFilter(filter: TransactionFilter) {
        updateJournalContent { copy(filter = filter, searchText = filter.searchText.orEmpty(), activePresetId = null) }
        refreshJournalPaging()
    }

    fun removeJournalFilter(stableKey: String) {
        updateJournalContent {
            val updated = when {
                stableKey.startsWith("kind_") -> filter.copy(kinds = filter.kinds.filterNot { "kind_${it.name}" == stableKey }.toSet())
                stableKey.startsWith("state_") -> filter.copy(lifecycleStates = filter.lifecycleStates.filterNot { "state_${it.name}" == stableKey }.toSet())
                stableKey.startsWith("source_") -> filter.copy(sources = filter.sources.filterNot { "source_${it.name}" == stableKey }.toSet())
                stableKey.startsWith("account_") -> filter.copy(accountIds = filter.accountIds.filterNot { "account_$it" == stableKey }.toSet())
                stableKey.startsWith("card_") -> filter.copy(cardIds = filter.cardIds.filterNot { "card_$it" == stableKey }.toSet())
                stableKey.startsWith("category_") -> filter.copy(categoryIds = filter.categoryIds.filterNot { "category_$it" == stableKey }.toSet())
                stableKey.startsWith("merchant_") -> filter.copy(merchantIds = filter.merchantIds.filterNot { "merchant_$it" == stableKey }.toSet())
                stableKey.startsWith("project_") -> filter.copy(projectIds = filter.projectIds.filterNot { "project_$it" == stableKey }.toSet())
                stableKey.startsWith("settlement_") -> filter.copy(settlementActivityIds = filter.settlementActivityIds.filterNot { "settlement_$it" == stableKey }.toSet())
                stableKey.startsWith("participant_") -> filter.copy(participantIds = filter.participantIds.filterNot { "participant_$it" == stableKey }.toSet())
                stableKey.startsWith("currency_") -> filter.copy(currencies = filter.currencies.filterNot { "currency_${it.value}" == stableKey }.toSet())
                stableKey.startsWith("nature_") -> filter.copy(statisticalNatures = filter.statisticalNatures.filterNot { "nature_${it.name}" == stableKey }.toSet())
                stableKey == "occurred_from" -> filter.copy(occurredFrom = null)
                stableKey == "occurred_through" -> filter.copy(occurredThrough = null)
                stableKey == "created_from" -> filter.copy(createdFrom = null)
                stableKey == "created_through" -> filter.copy(createdThrough = null)
                stableKey == "modified_from" -> filter.copy(modifiedFrom = null)
                stableKey == "modified_through" -> filter.copy(modifiedThrough = null)
                stableKey == "amount" -> filter.copy(amountRange = null)
                stableKey == "geo_radius" -> filter.copy(geoRadius = null)
                stableKey == "budget" -> filter.copy(includedInBudget = null)
                stableKey == "attachment" -> filter.copy(hasAttachment = null)
                stableKey == "refund" -> filter.copy(isRefund = null)
                stableKey == "installment" -> filter.copy(hasInstallment = null)
                stableKey == "recurrence" -> filter.copy(generatedByRecurrence = null)
                else -> filter
            }
            copy(filter = updated)
        }
        refreshJournalPaging()
    }

    fun loadJournalDetail(transactionId: StableId) {
        viewModelScope.launch(Dispatchers.IO) {
            val bookId = requireBookId(settingsRepository.current())
            val detail = journalApplicationPort.detail(bookId, transactionId)
            val history = journalApplicationPort.history(bookId, transactionId)
            val dependencies = journalApplicationPort.dependencies(bookId, transactionId)
            if (detail is DomainResult.Success && history is DomainResult.Success && dependencies is DomainResult.Success) {
                updateJournalContent { copy(detail = detail.value, history = history.value, dependencies = dependencies.value, dependencyResolutions = emptyList()) }
            } else {
                mutableJournal.value = JournalLoadState.Failure("JOURNAL_DETAIL_FAILED")
            }
        }
    }

    fun selectJournalTransaction(transactionId: StableId) = updateJournalContent {
        val next = selection?.let { JournalSelectionPolicy.toggle(it, transactionId) } ?: JournalSelectionPolicy.begin(filter, transactionId)
        copy(selection = next)
    }

    fun selectAllJournalResults() = updateJournalContent { copy(selection = JournalSelectionPolicy.selectAllMatching(filter)) }

    fun clearJournalSelection() = updateJournalContent { copy(selection = null) }

    fun saveJournalFilter(name: String) {
        val filter = (mutableJournal.value as? JournalLoadState.Content)?.filter ?: return
        mutateJournalPresets(JournalSavedFilterCommand.Save(nextId(), name, filter, journalFilterSummary()))
    }

    fun applyJournalPreset(id: StableId) {
        val preset = (mutableJournal.value as? JournalLoadState.Content)?.presets?.singleOrNull { it.id == id } ?: return
        updateJournalContent { copy(filter = preset.filter, searchText = preset.filter.searchText.orEmpty(), activePresetId = id, selection = null) }
        refreshJournalPaging()
    }

    fun copyJournalPreset(id: StableId) {
        val source = (mutableJournal.value as? JournalLoadState.Content)?.presets?.singleOrNull { it.id == id } ?: return
        mutateJournalPresets(JournalSavedFilterCommand.Copy(id, nextId(), "${source.name} copy"))
    }

    fun setDefaultJournalPreset(id: StableId) = mutateJournalPresets(JournalSavedFilterCommand.SetDefault(id))

    fun deleteJournalPreset(id: StableId) = mutateJournalPresets(JournalSavedFilterCommand.Delete(id))

    fun reorderJournalPresets(ids: List<StableId>) = mutateJournalPresets(JournalSavedFilterCommand.Reorder(ids))

    fun bulkEditJournal(patch: JournalBulkEditPatch) {
        val content = mutableJournal.value as? JournalLoadState.Content ?: return
        val selection = content.selection ?: return
        if (selection.queryChanged(JournalSelectionSpec.fingerprint(content.filter))) {
            updateJournalContent { copy(operation = JournalOperationState.FAILED) }
            return
        }
        if (content.operation in setOf(JournalOperationState.VALIDATING, JournalOperationState.COMMITTING)) return
        updateJournalContent { copy(operation = JournalOperationState.VALIDATING) }
        viewModelScope.launch(Dispatchers.IO) {
            val bookId = requireBookId(settingsRepository.current())
            updateJournalContent { copy(operation = JournalOperationState.COMMITTING) }
            val request = JournalBulkEditRequest(bookId, nextId(), nextId(), nextId(), selection, content.filter, patch, runtimeSources.clock.now())
            when (journalApplicationPort.bulkEdit(request)) {
                is DomainResult.Success -> {
                    updateJournalContent { copy(operation = JournalOperationState.SUCCEEDED, selection = null) }
                    refreshJournalPaging()
                }
                is DomainResult.Failure -> updateJournalContent { copy(operation = JournalOperationState.FAILED) }
            }
        }
    }

    private fun mutateJournalPresets(command: JournalSavedFilterCommand) {
        viewModelScope.launch(Dispatchers.IO) {
            val bookId = requireBookId(settingsRepository.current())
            when (val result = journalApplicationPort.mutateSavedFilter(bookId, command)) {
                is DomainResult.Success -> updateJournalContent { copy(presets = result.value) }
                is DomainResult.Failure -> mutableJournal.value = JournalLoadState.Failure(sanitizeCode(result.error.code))
            }
        }
    }

    private fun journalFilterSummary(): String {
        val filter = (mutableJournal.value as? JournalLoadState.Content)?.filter ?: return "all transactions"
        val dimensions = buildList {
            if (filter.searchText != null) add("search")
            if (filter.occurredFrom != null || filter.occurredThrough != null) add("occurrence time")
            if (filter.kinds.isNotEmpty()) add("${filter.kinds.size} types")
            if (filter.accountIds.isNotEmpty()) add("${filter.accountIds.size} accounts")
            if (filter.categoryIds.isNotEmpty()) add("${filter.categoryIds.size} categories")
            if (filter.lifecycleStates.isNotEmpty()) add("${filter.lifecycleStates.size} states")
            if (filter.amountRange != null) add("amount")
            if (filter.hasAttachment != null) add("attachment")
            if (filter.includedInBudget != null) add("budget")
        }
        return dimensions.ifEmpty { listOf("all transactions") }.joinToString(" · ")
    }

    fun resolveJournalDependency(dependency: JournalDependencyView, policy: DependencyPolicy) = updateJournalContent {
        val domain = TransactionDependency(TransactionId(dependency.parentTransactionId), TransactionId(dependency.childTransactionId), dependency.type)
        val resolution = DependencyResolution(domain, policy)
        copy(dependencyResolutions = dependencyResolutions.filterNot { it.dependency == domain } + resolution)
    }

    fun moveJournalToTrash(transactionId: StableId, expectedRevisionId: StableId, resolutions: List<DependencyResolution>) = executeJournalMutation(
        transactionId,
    ) { ids, now -> JournalMutationRequest.MoveToTrash(ids, expectedRevisionId, now, now.plusSeconds(JOURNAL_RETENTION_SECONDS), resolutions) }

    fun restoreJournalTransaction(transactionId: StableId, expectedRevisionId: StableId) = executeJournalMutation(
        transactionId,
    ) { ids, now -> JournalMutationRequest.RestoreFromTrash(ids, expectedRevisionId, now) }

    fun restoreJournalRevision(transactionId: StableId, expectedRevisionId: StableId, sourceRevisionId: StableId, resolutions: List<DependencyResolution>) = executeJournalMutation(
        transactionId,
    ) { ids, now -> JournalMutationRequest.RestoreHistorical(ids, expectedRevisionId, now, sourceRevisionId, resolutions) }

    fun compareJournalRevisions(transactionId: StableId, leftRevisionId: StableId, rightRevisionId: StableId) {
        viewModelScope.launch(Dispatchers.IO) {
            val bookId = requireBookId(settingsRepository.current())
            when (val result = journalApplicationPort.compare(bookId, transactionId, leftRevisionId, rightRevisionId)) {
                is DomainResult.Success -> updateJournalContent { copy(comparison = result.value) }
                is DomainResult.Failure -> mutableJournal.value = JournalLoadState.Failure(sanitizeCode(result.error.code))
            }
        }
    }

    fun verifyJournalPurge(transactionId: StableId) {
        viewModelScope.launch(Dispatchers.IO) {
            val bookId = requireBookId(settingsRepository.current())
            when (val result = journalApplicationPort.assessPurge(bookId, transactionId, runtimeSources.clock.now())) {
                is DomainResult.Success -> updateJournalContent { copy(purgeAssessment = result.value) }
                is DomainResult.Failure -> mutableJournal.value = JournalLoadState.Failure(sanitizeCode(result.error.code))
            }
        }
    }

    private fun executeJournalMutation(
        transactionId: StableId,
        request: (JournalMutationIds, java.time.Instant) -> JournalMutationRequest,
    ) {
        if ((mutableJournal.value as? JournalLoadState.Content)?.operation in setOf(JournalOperationState.VALIDATING, JournalOperationState.COMMITTING)) return
        viewModelScope.launch(Dispatchers.IO) {
            updateJournalContent { copy(operation = JournalOperationState.COMMITTING) }
            val bookId = requireBookId(settingsRepository.current())
            val ids = JournalMutationIds(bookId, nextId(), transactionId, nextId(), nextId(), nextId(), List(FINANCIAL_FACT_ID_RESERVE) { nextId() }, List(FX_ID_RESERVE) { nextId() })
            when (val result = journalApplicationPort.mutate(request(ids, runtimeSources.clock.now()))) {
                is DomainResult.Success -> {
                    updateJournalContent { copy(operation = JournalOperationState.SUCCEEDED) }
                    loadJournalDetail(transactionId)
                    refreshJournalPaging()
                }
                is DomainResult.Failure -> updateJournalContent { copy(operation = JournalOperationState.FAILED) }
            }
        }
    }

    private fun refreshJournalPaging() {
        viewModelScope.launch(Dispatchers.IO) {
            val bookId = runCatching { requireBookId(settingsRepository.current()) }.getOrNull() ?: return@launch
            val content = mutableJournal.value as? JournalLoadState.Content ?: return@launch
            mutableJournalPagingRequest.value = JournalPagingRequest(bookId, content.filter, refreshEpoch = (mutableJournalPagingRequest.value?.refreshEpoch ?: 0) + 1)
        }
    }

    private fun updateJournalContent(block: JournalLoadState.Content.() -> JournalLoadState.Content) {
        val current = mutableJournal.value as? JournalLoadState.Content ?: return
        mutableJournal.value = current.block()
    }

    fun loadOrdinaryRecord(transactionId: StableId? = null) {
        if ((mutableRootState.value as? AppRootState.Session)?.state !is BookSessionState.Ready) return
        viewModelScope.launch(Dispatchers.IO) {
            val bookId = runCatching { requireBookId(settingsRepository.current()) }.getOrNull() ?: return@launch
            if (transactionId == null) mutableOrdinaryRecord.value = OrdinaryRecordLoadState.Loading
            mutableOrdinaryRecord.value = when (val result = ordinaryTransactionEntryPort.snapshot(bookId, transactionId)) {
                is DomainResult.Success -> {
                    val previous = mutableOrdinaryRecord.value as? OrdinaryRecordLoadState.Content
                    OrdinaryRecordLoadState.Content(
                        result.value,
                        previous?.tab ?: RecordTab.EXPENSE,
                        previous?.search.orEmpty(),
                        previous?.selectedCategoryId,
                        previous?.editor,
                        previous?.expenseScrollIndex ?: 0,
                        previous?.incomeScrollIndex ?: 0,
                    )
                }
                is DomainResult.Failure -> OrdinaryRecordLoadState.Failure(sanitizeCode(result.error.code))
            }
        }
    }

    fun selectRecordTab(tab: RecordTab) = updateRecordContent { copy(tab = tab, search = "") }
    fun updateRecordSearch(value: String) = updateRecordContent { copy(search = value.take(RECORD_SEARCH_LIMIT)) }

    fun openRecordEditor(
        mode: RecordEditorMode,
        direction: OrdinaryDirection,
        categoryId: StableId?,
        sourceId: StableId?,
    ) {
        if (mutableOrdinaryRecordPending.value) return
        viewModelScope.launch(Dispatchers.IO) {
            val current = mutableOrdinaryRecord.value as? OrdinaryRecordLoadState.Content ?: return@launch
            val transactionId = sourceId.takeIf { mode in setOf(RecordEditorMode.EDIT, RecordEditorMode.DUPLICATE) }
            val snapshot = if (transactionId == null) {
                current.snapshot
            } else {
                val bookId = requireBookId(settingsRepository.current())
                when (val loaded = ordinaryTransactionEntryPort.snapshot(bookId, transactionId)) {
                    is DomainResult.Success -> loaded.value
                    is DomainResult.Failure -> {
                        mutableOrdinaryRecord.value = OrdinaryRecordLoadState.Failure(sanitizeCode(loaded.error.code))
                        return@launch
                    }
                }
            }
            val locale = recordLocale()
            val zone = ZoneId.of(settingsRepository.current().zoneId.ifBlank { DEFAULT_ZONE })
            val editor = OrdinaryRecordPolicy.createEditor(snapshot, mode, direction, categoryId, sourceId, runtimeSources.clock.now(), zone, locale)
            mutableOrdinaryRecord.value = current.copy(snapshot = snapshot, selectedCategoryId = editor.draft.categoryId, editor = editor)
            val platformClock = InjectedJavaClock(runtimeSources.clock)
            recordLocationSession = ForegroundLocationSaveSession(
                ProductionForegroundLocationClient(context, platformClock),
                platformClock,
                SystemClock::elapsedRealtime,
            ).also { it.prefetch(viewModelScope) }
            val screenId = ScreenId("REC-003")
            val arguments = buildMap<String, SafeRouteArgument> {
                put("mode", LedgerRouteContract.enumArgument(screenId, "mode", mode.name))
                sourceId?.let { put("transactionId", StableIdArgument(it)) }
            }
            navigator.navigate(LedgerRouteContract.destination(screenId, arguments), SessionGateState.READY)
        }
    }

    fun navigateRecord(target: String, stable: Map<String, StableId>, enums: Map<String, String>) {
        val screenId = ScreenId(target)
        val arguments = buildMap<String, SafeRouteArgument> {
            stable.forEach { (name, value) -> put(name, StableIdArgument(value)) }
            enums.forEach { (name, value) -> put(name, LedgerRouteContract.enumArgument(screenId, name, value)) }
        }
        navigator.navigate(LedgerRouteContract.destination(screenId, arguments), SessionGateState.READY)
    }

    fun recordExpression(value: String) = updateEditor { OrdinaryRecordPolicy.changeExpression(it, value, recordLocale()) }
    fun recordOperator(value: String) = updateEditor { OrdinaryRecordPolicy.appendOperator(it, value, recordLocale()) }
    fun selectRecordCategory(id: StableId) = updateEditorAndPop { OrdinaryRecordPolicy.selectCategory(it, id) }
    fun selectRecordAccount(id: StableId) = updateEditorAndPop { OrdinaryRecordPolicy.selectAccount(it, id, recordLocale()) }
    fun selectRecordCard(id: StableId?) = updateEditorAndPop { OrdinaryRecordPolicy.selectCard(it, id) }
    fun selectRecordReference(field: RecordField, id: StableId?) = updateEditorAndPop { OrdinaryRecordPolicy.update(it, field, id) }
    fun updateRecordNote(value: String) = updateEditor { OrdinaryRecordPolicy.updateNote(it, value) }
    fun setRecordSettlementEnabled(value: Boolean) = updateEditor { OrdinaryRecordPolicy.setSettlementEnabled(it, value) }
    fun selectRecordSettlementActivity(id: StableId) = updateEditorAndPop { OrdinaryRecordPolicy.selectSettlementActivity(it, id) }
    fun updateRecordOccurredAt(dateMillis: Long, hour: Int, minute: Int) = updateEditor { OrdinaryRecordPolicy.updateOccurredAt(it, dateMillis, hour, minute) }

    fun importRecordAttachment(uri: Uri) {
        val content = mutableOrdinaryRecord.value as? OrdinaryRecordLoadState.Content ?: return
        val editor = content.editor ?: return
        if (editor.attachmentImporting) return
        updateEditor { it.copy(attachmentImporting = true, attachmentFailureCode = null) }
        recordAttachmentImportJob = viewModelScope.launch(Dispatchers.IO) {
            val metadata = attachmentMetadata(uri)
            if (metadata == null) {
                updateEditor { it.copy(attachmentImporting = false, attachmentFailureCode = "ATTACHMENT_SOURCE_UNAVAILABLE") }
                return@launch
            }
            val request = AttachmentImportRequest(
                displayName = metadata.first,
                mimeType = context.contentResolver.getType(uri),
                extension = metadata.first.substringAfterLast('.', "").takeIf(String::isNotBlank),
                declaredSize = metadata.second,
                content = AttachmentContentSource { requireNotNull(context.contentResolver.openInputStream(uri)) },
            )
            when (val result = bookAttachmentObjectPort.import(editor.snapshot.references.bookId, request)) {
                is DomainResult.Success -> updateEditor {
                    it.copy(
                        draft = it.draft.copy(
                            attachmentIds = it.draft.attachmentIds + result.value.attachmentId.value,
                            touched = it.draft.touched + RecordField.ATTACHMENTS,
                        ),
                        attachmentImporting = false,
                        attachmentFailureCode = null,
                        uncommittedAttachmentIds = it.uncommittedAttachmentIds + result.value.attachmentId.value,
                    )
                }
                is DomainResult.Failure -> updateEditor { it.copy(attachmentImporting = false, attachmentFailureCode = sanitizeCode(result.error.code)) }
            }
        }
        recordAttachmentImportJob?.invokeOnCompletion {
            updateEditor { it.copy(attachmentImporting = false) }
            recordAttachmentImportJob = null
        }
    }

    fun cancelRecordAttachment(index: Int) {
        val editor = (mutableOrdinaryRecord.value as? OrdinaryRecordLoadState.Content)?.editor ?: return
        val attachmentId = editor.draft.attachmentIds.getOrNull(index)
        if (attachmentId == null && editor.attachmentImporting) {
            recordAttachmentImportJob?.cancel()
            return
        }
        attachmentId ?: return
        updateEditor {
            it.copy(
                draft = it.draft.copy(
                    attachmentIds = it.draft.attachmentIds.filterIndexed { itemIndex, _ -> itemIndex != index },
                    touched = it.draft.touched + RecordField.ATTACHMENTS,
                ),
                uncommittedAttachmentIds = it.uncommittedAttachmentIds - attachmentId,
            )
        }
        if (attachmentId in editor.uncommittedAttachmentIds) {
            viewModelScope.launch(Dispatchers.IO) {
                bookAttachmentObjectPort.discardUncommitted(editor.snapshot.references.bookId, app.ledger.finance.domain.AttachmentId(attachmentId))
            }
        }
    }

    fun loadRefund(presetOriginalId: StableId? = null) {
        if ((mutableRootState.value as? AppRootState.Session)?.state !is BookSessionState.Ready) return
        mutableRefund.value = RefundLoadState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            val saved = settingsRepository.current()
            val bookId = requireBookId(saved)
            when (val result = refundApplicationPort.snapshot(bookId)) {
                is DomainResult.Failure -> mutableRefund.value = RefundLoadState.Failure(sanitizeCode(result.error.code))
                is DomainResult.Success -> {
                    val editor = RefundPolicy.create(
                        result.value,
                        presetOriginalId,
                        runtimeSources.clock.now(),
                        ZoneId.of(saved.zoneId.ifBlank { DEFAULT_ZONE }),
                        recordLocale(),
                    )
                    mutableRefund.value = RefundLoadState.Content(editor)
                    mutableRefundPicker.value = RefundPickerState.Content(result.value)
                }
            }
        }
    }

    fun loadRefundOriginals(query: RefundSearchQuery = RefundSearchQuery()) {
        val editor = (mutableRefund.value as? RefundLoadState.Content)?.editor
        val bookId = editor?.snapshot?.references?.bookId ?: return loadRefund()
        val current = mutableRefundPicker.value as? RefundPickerState.Content
        if (current != null) mutableRefundPicker.value = current.copy(query = query, searching = true)
        viewModelScope.launch(Dispatchers.IO) {
            when (val result = refundApplicationPort.snapshot(bookId, query)) {
                is DomainResult.Failure -> mutableRefundPicker.value = RefundPickerState.Failure(sanitizeCode(result.error.code))
                is DomainResult.Success -> mutableRefundPicker.value = RefundPickerState.Content(result.value, query)
            }
        }
    }

    fun chooseRefundOriginal(id: StableId) {
        updateRefund { RefundPolicy.selectOriginal(it, id, recordLocale()) }
        navigator.pop()
    }

    fun openRefundOriginalPicker() {
        navigator.navigate(LedgerRouteContract.destination(ScreenId("REC-016")), SessionGateState.READY)
    }

    fun setRefundIndependent(value: Boolean) = updateRefund { RefundPolicy.setIndependent(it, value, recordLocale()) }
    fun refundExpression(value: String) = updateRefund { RefundPolicy.updateExpression(it, value, recordLocale()) }
    fun refundOperator(value: String) = updateRefund { RefundPolicy.appendOperator(it, value, recordLocale()) }

    fun selectNextRefundAccount() = updateRefund { editor ->
        val active = editor.snapshot.references.accounts.filter { it.status == app.ledger.finance.domain.EntityStatus.ACTIVE }.sortedBy { it.sortOrder }
        val next = active.nextId(editor.draft.receivingAccountId) { it.id } ?: return@updateRefund editor
        RefundPolicy.selectAccount(editor, next, recordLocale())
    }

    fun selectNextRefundCard() = updateRefund { editor ->
        val cards = editor.snapshot.references.cards.filter { it.status == app.ledger.finance.domain.EntityStatus.ACTIVE && it.accountId == editor.draft.receivingAccountId }.sortedBy { it.sortOrder }
        val choices = listOf<StableId?>(null) + cards.map { it.id }
        val next = choices[(choices.indexOf(editor.draft.receivingCardId).takeIf { it >= 0 } ?: 0).plus(1) % choices.size]
        RefundPolicy.selectCard(editor, next)
    }

    fun selectNextRefundCategory() = updateRefund { editor ->
        val values = editor.snapshot.references.categories.filter { it.status == app.ledger.finance.domain.CategoryStatus.ACTIVE && it.direction == CategoryDirection.EXPENSE }.sortedWith(compareBy({ it.sortOrder }, { it.name }))
        val next = values.nextId(editor.draft.categoryId) { it.id } ?: return@updateRefund editor
        RefundPolicy.updateReference(editor, app.ledger.feature.record.RefundField.CATEGORY, next)
    }

    fun selectNextRefundMerchant() = updateRefund { editor ->
        val values = editor.snapshot.references.merchants.filter { it.status == app.ledger.finance.domain.EntityStatus.ACTIVE }.sortedBy { it.name }
        val choices = listOf<StableId?>(null) + values.map { it.id }
        val next = choices[(choices.indexOf(editor.draft.merchantId).takeIf { it >= 0 } ?: 0).plus(1) % choices.size]
        RefundPolicy.updateInherited(editor, merchantId = next)
    }

    fun selectNextRefundProject() = updateRefund { editor ->
        val choices = listOf<StableId?>(null) + editor.snapshot.projects.map { it.id }
        val next = choices[(choices.indexOf(editor.draft.projectId).takeIf { it >= 0 } ?: 0).plus(1) % choices.size]
        RefundPolicy.updateInherited(editor, projectId = next)
    }

    fun selectNextRefundGoal() = updateRefund { editor ->
        val choices = listOf<StableId?>(null) + editor.snapshot.goals.map { it.id }
        val next = choices[(choices.indexOf(editor.draft.goalId).takeIf { it >= 0 } ?: 0).plus(1) % choices.size]
        RefundPolicy.updateInherited(editor, goalId = next)
    }

    fun refundDate(date: java.time.LocalDate) = updateRefund { RefundPolicy.setDate(it, date) }
    fun refundAccrual(policy: RefundAccrualPolicy) = updateRefund { RefundPolicy.setAccrualPolicy(it, policy) }
    fun refundBudget(policy: RefundBudgetPolicy) = updateRefund { RefundPolicy.setBudgetPolicy(it, policy) }
    fun refundProject(policy: RefundProjectPolicy) = updateRefund { RefundPolicy.setProjectPolicy(it, policy) }
    fun refundGoal(policy: RefundGoalPolicy) = updateRefund { RefundPolicy.setGoalPolicy(it, policy) }
    fun refundNote(value: String) = updateRefund { RefundPolicy.setNote(it, value) }
    fun requestRefundExcess(value: Boolean) = updateRefund { RefundPolicy.requestExcessOverride(it, value) }
    fun confirmRefundExcess() = updateRefund(RefundPolicy::confirmExcessRisk)

    fun saveRefund() {
        if (mutableRefundPending.value) return
        val editor = (mutableRefund.value as? RefundLoadState.Content)?.editor ?: return
        val validated = RefundPolicy.validate(editor)
        mutableRefund.value = RefundLoadState.Content(validated)
        if (validated.errors.isNotEmpty()) return
        val prepared = runCatching { RefundPolicy.prepare(validated) }.getOrNull() ?: return
        val original = RefundPolicy.original(validated)
        mutableRefundPending.value = true
        mutableRefund.value = RefundLoadState.Content(validated.copy(presentation = RefundPresentation.SAVING))
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ids = RefundWriteIds(
                    bookId = validated.snapshot.references.bookId,
                    commandId = CommandId(nextId()),
                    transactionId = nextId(),
                    revisionId = nextId(),
                    commitId = nextId(),
                    deviceInstanceId = nextId(),
                    factIds = List(FINANCIAL_FACT_ID_RESERVE) { nextId() },
                    fxRateSnapshotIds = List(FX_ID_RESERVE) { nextId() },
                )
                val request = RefundWriteRequest(
                    ids = ids,
                    allocations = prepared.allocations,
                    amount = prepared.amount,
                    receivingCardId = validated.draft.receivingCardId,
                    independent = validated.draft.independent,
                    categoryId = requireNotNull(validated.draft.categoryId),
                    merchantId = validated.draft.merchantId,
                    projectId = validated.draft.projectId,
                    goalId = validated.draft.goalId,
                    settlementActivityId = original?.settlementActivityId,
                    settlementShares = original?.settlementShares.orEmpty(),
                    occurredAt = validated.draft.occurredAt,
                    zoneId = validated.draft.zoneId,
                    localDate = validated.draft.localDate,
                    accrualDate = prepared.accrualDate,
                    budgetTargetMonth = prepared.budgetTargetMonth,
                    budgetPolicy = validated.draft.budgetPolicy,
                    projectPolicy = validated.draft.projectPolicy,
                    goalPolicy = validated.draft.goalPolicy,
                    accrualPolicy = validated.draft.accrualPolicy,
                    allowExcessOverride = validated.draft.excessOverrideRequested && RefundPolicy.exceedsRemaining(validated),
                    excessRiskConfirmed = validated.draft.excessRiskConfirmed,
                    amountExpression = validated.draft.expression,
                    note = validated.draft.note.trim().takeIf(String::isNotEmpty),
                    attachmentIds = validated.draft.attachmentIds,
                    createdAt = runtimeSources.clock.now(),
                )
                when (val result = refundApplicationPort.submit(request)) {
                    is DomainResult.Success -> {
                        loadReferenceDataAfterMutation(validated.snapshot.references.bookId)
                        mutableRefund.value = RefundLoadState.Loading
                        navigator.pop()
                    }
                    is DomainResult.Failure -> updateRefund { it.copy(presentation = RefundPresentation.SAVE_ERROR, failureCode = sanitizeCode(result.error.code)) }
                }
            } finally {
                mutableRefundPending.value = false
            }
        }
    }

    private fun updateRefund(transform: (RefundEditorState) -> RefundEditorState) {
        val content = mutableRefund.value as? RefundLoadState.Content ?: return
        mutableRefund.value = RefundLoadState.Content(transform(content.editor))
    }

    private inline fun <T> List<T>.nextId(current: StableId?, id: (T) -> StableId): StableId? {
        if (isEmpty()) return null
        val index = indexOfFirst { id(it) == current }
        return id(this[if (index < 0) 0 else (index + 1) % size])
    }

    fun loadSpecializedTransaction(screenId: String, presetAccountId: StableId? = null) {
        if ((mutableRootState.value as? AppRootState.Session)?.state !is BookSessionState.Ready) return
        val kind = when (screenId) {
            "REC-013" -> SpecializedTransactionKind.TRANSFER
            "REC-020" -> SpecializedTransactionKind.BALANCE_ADJUSTMENT
            "REC-021" -> SpecializedTransactionKind.FX_EXCHANGE
            "REC-022" -> SpecializedTransactionKind.OPENING_BALANCE
            else -> return
        }
        mutableSpecializedTransaction.value = SpecializedTransactionLoadState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            val saved = settingsRepository.current()
            val bookId = requireBookId(saved)
            when (val result = specializedTransactionEntryPort.snapshot(bookId)) {
                is DomainResult.Failure -> mutableSpecializedTransaction.value = SpecializedTransactionLoadState.Failure(sanitizeCode(result.error.code))
                is DomainResult.Success -> {
                    val editor = SpecializedTransactionPolicy.create(
                        kind,
                        result.value.references,
                        presetAccountId,
                        runtimeSources.clock.now(),
                        ZoneId.of(saved.zoneId.ifBlank { DEFAULT_ZONE }),
                        recordLocale(),
                    )
                    mutableSpecializedTransaction.value = SpecializedTransactionLoadState.Content(editor)
                    quoteSpecializedRates(refreshOnline = true)
                }
            }
        }
    }

    fun specializedExpression(incoming: Boolean, value: String) = updateSpecialized {
        SpecializedTransactionPolicy.updateExpression(it, incoming, value, recordLocale())
    }

    fun specializedOperator(incoming: Boolean, value: String) = updateSpecialized {
        SpecializedTransactionPolicy.appendOperator(it, incoming, value, recordLocale())
    }

    fun selectSpecializedAccount(incoming: Boolean) {
        updateSpecialized { SpecializedTransactionPolicy.selectAccount(it, incoming) }
        refreshSpecializedRates()
    }

    fun specializedManualRate(incoming: Boolean, value: String) = updateSpecialized {
        SpecializedTransactionPolicy.setManualRate(it, incoming, value)
    }

    fun specializedDirection(direction: BalanceAdjustmentDirection) = updateSpecialized {
        SpecializedTransactionPolicy.setDirection(it, direction)
    }

    fun specializedCheckpoint(id: StableId?) = updateSpecialized { SpecializedTransactionPolicy.setCheckpoint(it, id) }
    fun specializedDate(date: java.time.LocalDate) = updateSpecialized { SpecializedTransactionPolicy.changeDate(it, date) }
    fun specializedNote(value: String) = updateSpecialized { SpecializedTransactionPolicy.setNote(it, value) }

    fun refreshSpecializedRates() {
        viewModelScope.launch(Dispatchers.IO) { quoteSpecializedRates(refreshOnline = true) }
    }

    private suspend fun quoteSpecializedRates(refreshOnline: Boolean) {
        val editor = (mutableSpecializedTransaction.value as? SpecializedTransactionLoadState.Content)?.editor ?: return
        val currencies = SpecializedTransactionPolicy.requiredQuoteCurrencies(editor)
        if (currencies.isEmpty()) return
        mutableSpecializedTransaction.value = SpecializedTransactionLoadState.Content(editor.copy(quotePending = currencies))
        currencies.forEach { currency ->
            val current = (mutableSpecializedTransaction.value as? SpecializedTransactionLoadState.Content)?.editor ?: return
            val result = specializedTransactionEntryPort.quote(
                SpecializedFxQuoteRequest(current.snapshot.bookId, currency, current.snapshot.baseCurrency, current.draft.localDate, refreshOnline),
            )
            updateSpecialized { state ->
                SpecializedTransactionPolicy.withQuote(state, currency, (result as? DomainResult.Success)?.value)
            }
        }
    }

    fun importSpecializedAttachment(uri: Uri) {
        val editor = (mutableSpecializedTransaction.value as? SpecializedTransactionLoadState.Content)?.editor ?: return
        if (editor.attachmentImporting) return
        updateSpecialized { it.copy(attachmentImporting = true, attachmentFailureCode = null) }
        specializedAttachmentImportJob = viewModelScope.launch(Dispatchers.IO) {
            val metadata = attachmentMetadata(uri)
            if (metadata == null) {
                updateSpecialized { it.copy(attachmentImporting = false, attachmentFailureCode = "ATTACHMENT_SOURCE_UNAVAILABLE") }
                return@launch
            }
            val request = AttachmentImportRequest(
                displayName = metadata.first,
                mimeType = context.contentResolver.getType(uri),
                extension = metadata.first.substringAfterLast('.', "").takeIf(String::isNotBlank),
                declaredSize = metadata.second,
                content = AttachmentContentSource { requireNotNull(context.contentResolver.openInputStream(uri)) },
            )
            when (val result = bookAttachmentObjectPort.import(editor.snapshot.bookId, request)) {
                is DomainResult.Success -> updateSpecialized {
                    it.copy(
                        draft = it.draft.copy(attachmentIds = it.draft.attachmentIds + result.value.attachmentId.value, dirty = true),
                        attachmentImporting = false,
                        uncommittedAttachmentIds = it.uncommittedAttachmentIds + result.value.attachmentId.value,
                    )
                }
                is DomainResult.Failure -> updateSpecialized { it.copy(attachmentImporting = false, attachmentFailureCode = sanitizeCode(result.error.code)) }
            }
        }.also { job -> job.invokeOnCompletion { updateSpecialized { it.copy(attachmentImporting = false) } } }
    }

    fun cancelSpecializedAttachment(index: Int) {
        val editor = (mutableSpecializedTransaction.value as? SpecializedTransactionLoadState.Content)?.editor ?: return
        val id = editor.draft.attachmentIds.getOrNull(index)
        if (id == null && editor.attachmentImporting) {
            specializedAttachmentImportJob?.cancel()
            return
        }
        id ?: return
        updateSpecialized {
            it.copy(
                draft = it.draft.copy(attachmentIds = it.draft.attachmentIds.filterIndexed { itemIndex, _ -> itemIndex != index }, dirty = true),
                uncommittedAttachmentIds = it.uncommittedAttachmentIds - id,
            )
        }
        if (id in editor.uncommittedAttachmentIds) {
            viewModelScope.launch(Dispatchers.IO) {
                bookAttachmentObjectPort.discardUncommitted(editor.snapshot.bookId, app.ledger.finance.domain.AttachmentId(id))
            }
        }
    }

    fun saveSpecializedTransaction() {
        if (mutableSpecializedTransactionPending.value) return
        val content = mutableSpecializedTransaction.value as? SpecializedTransactionLoadState.Content ?: return
        val validated = SpecializedTransactionPolicy.validate(content.editor)
        mutableSpecializedTransaction.value = SpecializedTransactionLoadState.Content(validated)
        if (validated.errors.isNotEmpty()) return
        val prepared = runCatching { SpecializedTransactionPolicy.prepareAmounts(validated) }.getOrNull() ?: return
        mutableSpecializedTransactionPending.value = true
        mutableSpecializedTransaction.value = SpecializedTransactionLoadState.Content(validated.copy(presentation = SpecializedPresentation.SAVING))
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ids = SpecializedTransactionWriteIds(
                    bookId = validated.snapshot.bookId,
                    commandId = CommandId(nextId()),
                    transactionId = nextId(),
                    revisionId = nextId(),
                    commitId = nextId(),
                    deviceInstanceId = nextId(),
                    factIds = List(FINANCIAL_FACT_ID_RESERVE) { nextId() },
                    fxRateSnapshotIds = List(FX_ID_RESERVE) { nextId() },
                )
                val context = SpecializedTransactionContext(
                    occurredAt = validated.draft.occurredAt,
                    zoneId = validated.draft.zoneId,
                    localDate = validated.draft.localDate,
                    amountExpression = validated.draft.outgoingExpression,
                    note = validated.draft.note.trim().takeIf(String::isNotEmpty),
                    attachmentIds = validated.draft.attachmentIds,
                    createdAt = runtimeSources.clock.now(),
                )
                val request = when (validated.kind) {
                    SpecializedTransactionKind.TRANSFER -> SpecializedTransactionWriteRequest.Transfer(ids, context, prepared.outgoing, requireNotNull(prepared.incoming))
                    SpecializedTransactionKind.BALANCE_ADJUSTMENT -> SpecializedTransactionWriteRequest.BalanceAdjustment(ids, context, prepared.outgoing, validated.draft.direction, validated.draft.checkpointId)
                    SpecializedTransactionKind.FX_EXCHANGE -> SpecializedTransactionWriteRequest.FxExchange(ids, context, prepared.outgoing, requireNotNull(prepared.incoming), prepared.valuationPolicy, prepared.spreadCostBaseMinor)
                    SpecializedTransactionKind.OPENING_BALANCE -> SpecializedTransactionWriteRequest.OpeningBalance(ids, context, prepared.outgoing, validated.draft.localDate)
                }
                when (val result = specializedTransactionEntryPort.submit(request)) {
                    is DomainResult.Success -> {
                        loadReferenceDataAfterMutation(validated.snapshot.bookId)
                        mutableSpecializedTransaction.value = SpecializedTransactionLoadState.Loading
                        navigator.pop()
                    }
                    is DomainResult.Failure -> updateSpecialized {
                        it.copy(presentation = SpecializedPresentation.SAVE_ERROR, failureCode = sanitizeCode(result.error.code))
                    }
                }
            } finally {
                mutableSpecializedTransactionPending.value = false
            }
        }
    }

    fun saveOrdinaryRecord() {
        if (mutableOrdinaryRecordPending.value) return
        val content = mutableOrdinaryRecord.value as? OrdinaryRecordLoadState.Content ?: return
        val editor = content.editor ?: return
        val validated = OrdinaryRecordPolicy.validate(editor)
        mutableOrdinaryRecord.value = content.copy(editor = validated)
        if (validated.errors.isNotEmpty()) return
        val amountMinor = validated.draft.resultMinor ?: return
        val account = validated.snapshot.references.accounts.singleOrNull { it.id == validated.draft.accountId } ?: return
        val baseMinor = baseMinor(validated, account.balanceMinor, account.currentBaseValueMinor) ?: run {
            mutableOrdinaryRecord.value = content.copy(editor = validated.copy(presentation = RecordEditorPresentation.SAVE_ERROR, sanitizedFailureCode = "FX_EVIDENCE_UNAVAILABLE"))
            return
        }
        mutableOrdinaryRecordPending.value = true
        mutableOrdinaryRecord.value = content.copy(editor = validated.copy(presentation = RecordEditorPresentation.SAVING, errors = emptyList()))
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val locationResult = if (validated.draft.locationRecordId == null && RecordField.LOCATION !in validated.draft.touched) {
                    runCatching { recordLocationSession?.locationForSave() }.getOrNull()?.location
                } else {
                    null
                }
                val locationId = locationResult?.let { nextId() }
                val ids = OrdinaryTransactionWriteIds(
                    bookId = validated.snapshot.references.bookId,
                    commandId = nextId(),
                    transactionId = validated.transactionId ?: nextId(),
                    revisionId = nextId(),
                    commitId = nextId(),
                    deviceInstanceId = nextId(),
                    factIds = List(FINANCIAL_FACT_ID_RESERVE) { nextId() },
                    fxRateSnapshotIds = List(FX_ID_RESERVE) { nextId() },
                )
                val request = OrdinaryTransactionWriteRequest(
                    ids = ids,
                    expectedRevisionId = validated.expectedRevisionId,
                    direction = validated.draft.direction,
                    categoryId = requireNotNull(validated.draft.categoryId),
                    amount = OrdinaryAmountDraft(validated.draft.expression, amountMinor, account.currency, amountMinor, baseMinor),
                    accountId = requireNotNull(validated.draft.accountId),
                    cardId = validated.draft.cardId,
                    merchantId = validated.draft.merchantId,
                    occurredAt = validated.draft.occurredAt,
                    zoneId = validated.draft.zoneId,
                    localDate = validated.draft.occurredAt.atZone(validated.draft.zoneId).toLocalDate(),
                    projectId = validated.draft.projectId,
                    settlementActivityId = validated.draft.settlementActivityId.takeIf { validated.draft.settlementEnabled },
                    settlementShares = validated.draft.settlementShares.takeIf { validated.draft.settlementEnabled }.orEmpty(),
                    locationRecordId = validated.draft.locationRecordId ?: locationId,
                    newLocation = locationResult?.let { captured ->
                        OrdinaryLocationDraft(
                            requireNotNull(locationId),
                            captured.latitudeE7,
                            captured.longitudeE7,
                            captured.accuracyMillimeters,
                            captured.capturedAt,
                            when (captured.provider) {
                                app.ledger.finance.application.CapturedLocationProvider.FUSED -> OrdinaryLocationProvider.FUSED
                                app.ledger.finance.application.CapturedLocationProvider.GPS -> OrdinaryLocationProvider.GPS
                                app.ledger.finance.application.CapturedLocationProvider.NETWORK -> OrdinaryLocationProvider.NETWORK
                            },
                            null,
                        )
                    },
                    note = validated.draft.note.trim().takeIf(String::isNotEmpty),
                    attachmentIds = validated.draft.attachmentIds,
                    source = recordSource(validated),
                    sourceReferenceId = validated.sourceReferenceId.takeIf { validated.mode in setOf(RecordEditorMode.TEMPLATE, RecordEditorMode.CANDIDATE) },
                    createdAt = runtimeSources.clock.now(),
                )
                when (val result = ordinaryTransactionEntryPort.submit(request)) {
                    is DomainResult.Success -> finishRecordSave(validated, ids.transactionId)
                    is DomainResult.Failure -> {
                        val code = sanitizeCode(result.error.code)
                        val presentation = if (code.contains("STALE") || code.contains("REVISION")) RecordEditorPresentation.REVISION_CONFLICT else RecordEditorPresentation.SAVE_ERROR
                        updateEditor { it.copy(presentation = presentation, sanitizedFailureCode = code) }
                    }
                }
            } finally {
                mutableOrdinaryRecordPending.value = false
            }
        }
    }

    fun requestRootBack() {
        val screen = navigator.currentKey.contract.screenId.value
        val editor = (mutableOrdinaryRecord.value as? OrdinaryRecordLoadState.Content)?.editor
        if (screen == "REC-003" && editor?.draft?.dirty == true) {
            pendingRecordExit = PendingRecordExit.Back
            updateEditor { it.copy(showUnsavedDialog = true) }
        } else {
            navigator.pop()
        }
    }

    fun selectRootTopLevel(target: TopLevelDestination) {
        val screen = navigator.currentKey.contract.screenId.value
        val editor = (mutableOrdinaryRecord.value as? OrdinaryRecordLoadState.Content)?.editor
        if (screen == "REC-003" && editor?.draft?.dirty == true) {
            pendingRecordExit = PendingRecordExit.TopLevel(target)
            updateEditor { it.copy(showUnsavedDialog = true) }
        } else {
            navigator.select(target)
        }
    }

    fun discardRecordChanges() {
        recordAttachmentImportJob?.cancel()
        val editor = (mutableOrdinaryRecord.value as? OrdinaryRecordLoadState.Content)?.editor
        if (editor != null && editor.uncommittedAttachmentIds.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                editor.uncommittedAttachmentIds.forEach { id ->
                    bookAttachmentObjectPort.discardUncommitted(editor.snapshot.references.bookId, app.ledger.finance.domain.AttachmentId(id))
                }
            }
        }
        updateEditor { it.copy(showUnsavedDialog = false) }
        when (val pending = pendingRecordExit) {
            PendingRecordExit.Back -> navigator.pop()
            is PendingRecordExit.TopLevel -> navigator.select(pending.target)
            null -> Unit
        }
        pendingRecordExit = null
        recordLocationSession = null
    }

    fun keepEditingRecord() {
        pendingRecordExit = null
        updateEditor { it.copy(showUnsavedDialog = false) }
    }

    fun reloadRecordConflict() {
        val editor = (mutableOrdinaryRecord.value as? OrdinaryRecordLoadState.Content)?.editor ?: return
        val id = editor.transactionId ?: return
        openRecordEditor(RecordEditorMode.EDIT, editor.draft.direction, editor.draft.categoryId, id)
    }

    fun cancelRecordConflict() {
        navigator.pop()
        updateRecordContent { copy(editor = null) }
    }

    fun searchCurrencies(value: String) {
        mutableCurrencySettings.value = mutableCurrencySettings.value?.let { CurrencySettingsPolicy.search(it, value) }
    }

    fun toggleCurrency(code: CurrencyCode) {
        mutableCurrencySettings.value = mutableCurrencySettings.value?.let { CurrencySettingsPolicy.toggle(it, code) }
        persistCurrencyOrder()
    }

    fun moveCurrency(code: CurrencyCode, delta: Int) {
        mutableCurrencySettings.value = mutableCurrencySettings.value?.let { CurrencySettingsPolicy.move(it, code, delta) }
        persistCurrencyOrder()
    }

    private fun persistCurrencyOrder() {
        val value = mutableCurrencySettings.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.update {
                it.clearVisibleCurrencyCodes()
                it.addAllVisibleCurrencyCodes(value.visibleCodes.map { code -> code.value })
            }
        }
    }

    fun navigateP12(
        source: LedgerDestinationKey,
        targetScreenId: String,
        stableArguments: Map<String, StableId>,
        enumArguments: Map<String, String> = emptyMap(),
    ) {
        val screenId = ScreenId(targetScreenId)
        if (source.contract.screenId.value == "ACC-009" && targetScreenId == "ACC-010") {
            pendingCardAccountId = source.encodedArguments["accountId"]?.let { StableId.parse(it).getOrNull() }
        }
        val arguments = buildMap<String, SafeRouteArgument> {
            stableArguments.forEach { (name, value) -> put(name, StableIdArgument(value)) }
            enumArguments.forEach { (name, value) -> put(name, LedgerRouteContract.enumArgument(screenId, name, value)) }
            if (targetScreenId == "REC-001" && "tab" !in enumArguments) put("tab", LedgerRouteContract.enumArgument(screenId, "tab", "EXPENSE"))
        }
        navigator.navigate(LedgerRouteContract.destination(screenId, arguments), SessionGateState.READY)
    }

    fun selectP12AccountType(type: UserAccountType, source: LedgerDestinationKey) {
        selectedAccountType = type
        navigateP12(source, "ACC-003", emptyMap())
    }

    fun saveAccount(value: AccountEditorSubmission) = executeReferenceMutation { snapshot ->
        val existing = value.accountId?.let { id -> snapshot.accounts.singleOrNull { it.id == id } }
        val currency = CurrencyCode.parse(value.currencyCode).getOrNull() ?: error("REFERENCE_INVALID_CURRENCY")
        ReferenceMutation.SaveAccount(
            AccountDraft(
                accountId = existing?.id ?: nextId(),
                ledgerAccountId = if (existing == null) nextId() else nextId(),
                expectedRowVersion = existing?.rowVersion,
                type = value.type,
                name = value.name,
                currency = currency,
                institutionName = value.institutionName,
                branchName = value.branchName,
                accountNumber = value.accountNumber,
                openedOn = value.openedOn,
                iconKey = value.iconKey,
                colorArgb = value.colorArgb,
                sortOrder = existing?.sortOrder ?: snapshot.accounts.size,
            ),
        )
    }

    fun archiveAccount(id: StableId, rowVersion: Long) = executeReferenceMutation { ReferenceMutation.ArchiveAccount(id, rowVersion) }
    fun deleteEmptyAccount(id: StableId, rowVersion: Long) = executeReferenceMutation { ReferenceMutation.DeleteEmptyAccount(id, rowVersion) }

    fun saveCard(value: CardEditorSubmission) = executeReferenceMutation { snapshot ->
        val existing = value.cardId?.let { id -> snapshot.cards.singleOrNull { it.id == id } }
        ReferenceMutation.SaveCard(
            CardDraft(
                cardId = existing?.id ?: nextId(),
                expectedRowVersion = existing?.rowVersion,
                accountId = value.accountId,
                type = value.type,
                displayName = value.displayName,
                lastFour = value.lastFour,
                replacementOfId = value.replacementOfId,
                iconKey = LedgerReferenceDisplayDefaults.ACCOUNT_ICON_KEY,
                colorArgb = LedgerReferenceDisplayDefaults.COLOR_ARGB,
                sortOrder = existing?.sortOrder ?: snapshot.cards.size,
            ),
        )
    }

    fun archiveCard(id: StableId, rowVersion: Long) = executeReferenceMutation { ReferenceMutation.ArchiveCard(id, rowVersion) }

    fun saveCheckpoint(value: CheckpointSubmission) = executeReferenceMutation {
        val zone = ZoneId.of(settings.value.zoneId.ifBlank { DEFAULT_ZONE })
        ReferenceMutation.SaveCheckpoint(
            checkpointId = nextId(),
            accountId = value.accountId,
            asOf = value.localDate.plusDays(1).atStartOfDay(zone).minusNanos(1).toInstant(),
            asOfLocalDate = value.localDate,
            observedMinor = value.observedMinor,
            note = value.note,
        )
    }

    fun saveOpeningBalance(value: OpeningBalanceSubmission) {
        executeOpeningBalance(value)
    }

    fun saveCategory(value: CategorySubmission) = executeReferenceMutation { snapshot ->
        val existing = value.categoryId?.let { id -> snapshot.categories.singleOrNull { it.id == id } }
        ReferenceMutation.SaveCategory(
            CategoryDraft(
                categoryId = existing?.id ?: nextId(),
                expectedRowVersion = existing?.rowVersion,
                direction = value.direction,
                parentId = value.parentId,
                name = value.name,
                normalizedName = value.name.trim().lowercase(Locale.ROOT),
                iconKey = value.iconKey,
                colorArgb = value.colorArgb,
                sortOrder = existing?.sortOrder ?: snapshot.categories.count { it.direction == value.direction },
                statisticalNature = value.statisticalNature,
                defaultAccountId = value.defaultAccountId,
                defaultCardId = value.defaultCardId,
                defaultMerchantId = value.defaultMerchantId,
            ),
        )
    }

    fun reorderCategories(direction: CategoryDirection, ids: List<StableId>) = executeReferenceMutation { ReferenceMutation.ReorderCategories(direction, ids) }
    fun removeCategory(id: StableId, rowVersion: Long, strategy: CategoryRemovalStrategy, target: StableId?) = executeReferenceMutation { ReferenceMutation.RemoveCategory(id, rowVersion, strategy, target) }

    fun saveMerchant(value: MerchantSubmission) = executeReferenceMutation { snapshot ->
        val existing = value.merchantId?.let { id -> snapshot.merchants.singleOrNull { it.id == id } }
        ReferenceMutation.SaveMerchant(MerchantDraft(existing?.id ?: nextId(), existing?.rowVersion, value.name, value.name.trim().lowercase(Locale.ROOT), value.aliases))
    }

    fun mergeMerchant(source: StableId, target: StableId) = executeReferenceMutation { ReferenceMutation.MergeMerchant(source, target) }

    fun savePlace(value: PlaceSubmission) = executeReferenceMutation { snapshot ->
        val existing = value.placeId?.let { id -> snapshot.places.singleOrNull { it.id == id } }
        ReferenceMutation.SavePlace(PlaceDraft(existing?.id ?: nextId(), existing?.rowVersion, value.name, value.latitudeE7, value.longitudeE7, value.merchantId))
    }

    fun mergePlace(source: StableId, target: StableId) = executeReferenceMutation { ReferenceMutation.MergePlace(source, target) }

    fun splitPlace(source: StableId, value: PlaceSubmission, locations: List<StableId>) = executeReferenceMutation {
        ReferenceMutation.SplitPlace(
            source,
            PlaceDraft(nextId(), null, value.name, value.latitudeE7, value.longitudeE7, value.merchantId),
            locations,
            List(locations.size) { nextId() },
        )
    }

    private fun executeReferenceMutation(factory: (ReferenceDataSnapshot) -> ReferenceMutation) {
        if (mutableReferenceMutationPending.value) return
        viewModelScope.launch(Dispatchers.IO) {
            val snapshot = (mutableReferenceData.value as? AppReferenceDataState.Content)?.snapshot ?: return@launch
            mutableReferenceMutationPending.value = true
            try {
                val ids = ReferenceMutationIds(
                    bookId = snapshot.bookId,
                    expectedLocalRevision = snapshot.localRevision,
                    commitId = nextId(),
                    entityRevisionIds = List(REFERENCE_REVISION_ID_RESERVE) { nextId() },
                    deviceInstanceId = nextId(),
                    changedAt = runtimeSources.clock.now(),
                )
                when (val result = runCatching { referenceDataPort.mutate(ReferenceMutationCommand(ids, factory(snapshot))) }.getOrNull()) {
                    is DomainResult.Success -> loadReferenceDataAfterMutation(snapshot.bookId)
                    is DomainResult.Failure -> mutableReferenceData.value = AppReferenceDataState.Error(result.error.code)
                    null -> mutableReferenceData.value = AppReferenceDataState.Error("REFERENCE_MUTATION_FAILED")
                }
            } finally {
                mutableReferenceMutationPending.value = false
            }
        }
    }

    private suspend fun loadReferenceDataAfterMutation(bookId: StableId) {
        mutableReferenceData.value = when (val result = referenceDataPort.snapshot(bookId)) {
            is DomainResult.Success -> {
                updateCurrencySettings(result.value)
                AppReferenceDataState.Content(result.value)
            }
            is DomainResult.Failure -> AppReferenceDataState.Error(result.error.code)
        }
    }

    private suspend fun updateCurrencySettings(snapshot: ReferenceDataSnapshot) {
        val saved = settingsRepository.current()
        mutableCurrencySettings.value = CurrencySettingsPolicy.create(
            snapshot.baseCurrency,
            snapshot.accounts.map { it.currency }.toSet(),
            saved.visibleCurrencyCodesList,
        )
    }

    private fun executeOpeningBalance(value: OpeningBalanceSubmission) {
        if (mutableReferenceMutationPending.value) return
        viewModelScope.launch(Dispatchers.IO) {
            val snapshot = (mutableReferenceData.value as? AppReferenceDataState.Content)?.snapshot ?: return@launch
            val account = snapshot.accounts.singleOrNull { it.id == value.accountId } ?: return@launch
            if (account.hasFinancialPostings) {
                mutableReferenceData.value = AppReferenceDataState.Error("OPENING_BALANCE_ACCOUNT_ALREADY_USED")
                return@launch
            }
            mutableReferenceMutationPending.value = true
            try {
                val request = OpeningBalanceWriteRequest(
                    ids = OpeningBalanceWriteIds(
                        bookId = snapshot.bookId,
                        commandId = CommandId(nextId()),
                        transactionId = nextId(),
                        transactionRevisionId = nextId(),
                        commitId = nextId(),
                        deviceInstanceId = nextId(),
                        fxRateSnapshotId = nextId(),
                        factIds = List(FINANCIAL_FACT_ID_RESERVE) { nextId() },
                    ),
                    accountId = value.accountId,
                    balanceDate = value.balanceDate,
                    accountMinor = value.accountMinor,
                    baseMinor = value.baseMinor,
                    createdAt = runtimeSources.clock.now(),
                )
                when (val result = openingBalanceWritePort.record(request)) {
                    is DomainResult.Success -> loadReferenceDataAfterMutation(snapshot.bookId)
                    is DomainResult.Failure -> mutableReferenceData.value = AppReferenceDataState.Error(result.error.code)
                }
            } finally {
                mutableReferenceMutationPending.value = false
            }
        }
    }

    private fun advanceOnboarding() {
        val next = onboardingState.step.next()
        updateOnboarding { copy(step = next, renderState = OnboardingRenderState.CONTENT, errorCode = null) }
        viewModelScope.launch { settingsRepository.saveStep(next) }
    }

    private fun clearRecoveryPlaintextIfLeavingBackup() {
        updateOnboarding { copy(recoveryPassword = "", recoveryPasswordConfirmation = "") }
    }

    private fun updateOnboarding(block: OnboardingUiState.() -> OnboardingUiState) {
        onboardingState = onboardingState.block()
        publishOnboarding()
    }

    private fun publishOnboarding() {
        mutableRootState.value = AppRootState.Onboarding(onboardingState)
    }

    private fun publishSession(
        state: BookSessionState,
        explicitAuthentication: AppAuthenticationState? = null,
    ) {
        val authentication = explicitAuthentication ?: when (AndroidKeystoreKeys(context).deviceSecurityCapability()) {
            DeviceSecurityCapability.BIOMETRIC_OR_CREDENTIAL -> AppAuthenticationState.BIOMETRIC_AVAILABLE
            else -> AppAuthenticationState.CREDENTIAL_ONLY
        }
        mutableRootState.value = AppRootState.Session(state, authentication, unsavedContentLossNotice)
    }

    private fun consumePendingDeepLink() {
        val state = (mutableRootState.value as? AppRootState.Session)?.state
        if (state !is BookSessionState.Ready) return
        val destination = pendingDeepLink ?: return
        if (navigator.navigate(destination, SessionGateState.READY) == app.ledger.core.navigation.NavigationOutcome.Navigated) {
            pendingDeepLink = null
        }
    }

    private fun restoreNavigationIfAllowed(saved: LedgerAppSettings) {
        if (!saved.shouldRestoreNavigationAfterColdStart()) return
        runCatching {
            val proto = saved.navigationSnapshot
            val snapshot = FiveStackSnapshot(
                selectedTopLevel = TopLevelDestination.valueOf(proto.selectedTopLevel),
                stacks = proto.stacksList.map { stack ->
                    TopLevelStackSnapshot(
                        topLevel = TopLevelDestination.valueOf(stack.topLevel),
                        destinations = stack.destinationsList.map { destination ->
                            DestinationSnapshot(
                                destination.screenId,
                                destination.argumentsList.map { argument -> EncodedRouteArgument(argument.name, argument.encodedValue) },
                            )
                        },
                        scrollKey = stack.scrollKey.takeIf(String::isNotBlank),
                        scrollOffset = stack.scrollOffset,
                    )
                },
            )
            if (navigator.restore(snapshot)) {
                snapshot.stacks.forEach { stack ->
                    val key = stack.scrollKey
                    if (key != null) scrollStates[stack.topLevel] = key to stack.scrollOffset
                }
            }
        }
    }

    private suspend fun persistNavigationIfAllowed() {
        val saved = settingsRepository.current()
        if (!saved.alwaysRestoreLastPage) {
            if (saved.hasNavigationSnapshot()) settingsRepository.update(LedgerAppSettings.Builder::clearNavigationSnapshot)
            return
        }
        val snapshot = navigator.snapshot(scrollStates)
        val proto = NavigationSnapshotProto.newBuilder()
            .setSelectedTopLevel(snapshot.selectedTopLevel.name)
            .addAllStacks(
                snapshot.stacks.map { stack ->
                    TopLevelStackProto.newBuilder()
                        .setTopLevel(stack.topLevel.name)
                        .setScrollKey(stack.scrollKey.orEmpty())
                        .setScrollOffset(stack.scrollOffset)
                        .addAllDestinations(
                            stack.destinations.map { destination ->
                                DestinationProto.newBuilder()
                                    .setScreenId(destination.screenId)
                                    .addAllArguments(
                                        destination.arguments.map { argument ->
                                            RouteArgumentProto.newBuilder().setName(argument.name).setEncodedValue(argument.value).build()
                                        },
                                    )
                                    .build()
                            },
                        )
                        .build()
                },
            )
            .build()
        settingsRepository.update { it.navigationSnapshot = proto }
    }

    private fun timeout(value: Long): AppLockTimeout = when (value) {
        0L -> AppLockTimeout.Immediately
        60_000L -> AppLockTimeout.OneMinute
        300_000L -> AppLockTimeout.FiveMinutes
        900_000L -> AppLockTimeout.FifteenMinutes
        else -> AppLockTimeout.Immediately
    }

    private fun requireBookId(saved: LedgerAppSettings): StableId = requireNotNull(
        StableId.fromBytes(saved.bookId.toByteArray()).getOrNull(),
    )

    private fun updateRecordContent(block: OrdinaryRecordLoadState.Content.() -> OrdinaryRecordLoadState.Content) {
        val current = mutableOrdinaryRecord.value as? OrdinaryRecordLoadState.Content ?: return
        mutableOrdinaryRecord.value = current.block()
    }

    private fun updateEditor(block: (OrdinaryRecordEditorState) -> OrdinaryRecordEditorState) {
        updateRecordContent { copy(editor = editor?.let(block)) }
    }

    private fun updateEditorAndPop(block: (OrdinaryRecordEditorState) -> OrdinaryRecordEditorState) {
        updateEditor(block)
        navigator.pop()
    }

    private fun updateSpecialized(block: (SpecializedTransactionEditorState) -> SpecializedTransactionEditorState) {
        val current = mutableSpecializedTransaction.value as? SpecializedTransactionLoadState.Content ?: return
        mutableSpecializedTransaction.value = SpecializedTransactionLoadState.Content(block(current.editor))
    }

    private fun baseMinor(editor: OrdinaryRecordEditorState, balanceMinor: Long, currentBaseValueMinor: Long?): Long? {
        val result = editor.draft.resultMinor ?: return null
        val account = editor.snapshot.references.accounts.singleOrNull { it.id == editor.draft.accountId } ?: return null
        if (account.currency == editor.snapshot.references.baseCurrency) return result
        if (balanceMinor == 0L || currentBaseValueMinor == null) return null
        return runCatching {
            java.math.BigDecimal.valueOf(result)
                .multiply(java.math.BigDecimal.valueOf(currentBaseValueMinor).abs())
                .divide(java.math.BigDecimal.valueOf(balanceMinor).abs(), 0, java.math.RoundingMode.HALF_EVEN)
                .longValueExact()
                .takeIf { it > 0L }
        }.getOrNull()
    }

    private fun recordSource(editor: OrdinaryRecordEditorState): TransactionSource = when (editor.mode) {
        RecordEditorMode.TEMPLATE -> TransactionSource.QUICK_TEMPLATE
        RecordEditorMode.CANDIDATE -> TransactionSource.RECURRENCE_CANDIDATE
        RecordEditorMode.EDIT -> editor.snapshot.editing?.source ?: TransactionSource.MANUAL
        RecordEditorMode.CREATE, RecordEditorMode.DUPLICATE -> TransactionSource.MANUAL
    }

    private suspend fun finishRecordSave(editor: OrdinaryRecordEditorState, transactionId: StableId) {
        loadReferenceDataAfterMutation(editor.snapshot.references.bookId)
        when (editor.mode) {
            RecordEditorMode.EDIT -> {
                val screen = ScreenId("JRN-007")
                navigator.navigate(
                    LedgerRouteContract.destination(screen, mapOf("transactionId" to StableIdArgument(transactionId))),
                    SessionGateState.READY,
                )
            }
            RecordEditorMode.CANDIDATE -> navigator.navigate(LedgerRouteContract.destination(ScreenId("AUT-008")), SessionGateState.READY)
            RecordEditorMode.CREATE, RecordEditorMode.DUPLICATE, RecordEditorMode.TEMPLATE -> {
                while (navigator.currentKey.contract.screenId.value != "REC-001" && navigator.currentBackStack.size > 1) navigator.pop()
            }
        }
        val loaded = ordinaryTransactionEntryPort.snapshot(editor.snapshot.references.bookId)
        mutableOrdinaryRecord.value = when (loaded) {
            is DomainResult.Success -> OrdinaryRecordLoadState.Content(loaded.value, if (editor.draft.direction == OrdinaryDirection.EXPENSE) RecordTab.EXPENSE else RecordTab.INCOME, selectedCategoryId = editor.draft.categoryId)
            is DomainResult.Failure -> OrdinaryRecordLoadState.Failure(sanitizeCode(loaded.error.code))
        }
        recordLocationSession = null
    }

    private fun recordLocale(): Locale {
        val tag = settings.value.languageTag.ifBlank { Locale.getDefault().toLanguageTag() }
        return Locale.forLanguageTag(tag)
    }

    private fun attachmentMetadata(uri: Uri): Pair<String, Long?>? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val name = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)).trim().take(RECORD_ATTACHMENT_NAME_LIMIT)
            val sizeIndex = cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)
            val size = if (cursor.isNull(sizeIndex)) null else cursor.getLong(sizeIndex).takeIf { it >= 0L }
            name.takeIf(String::isNotBlank)?.let { it to size }
        }
    }.getOrNull()

    private fun sanitizeCode(value: String): String = value.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9_]"), "_").take(48).ifBlank { "RECORD_FAILURE" }

    private fun nextId(): StableId = runtimeSources.stableIds.nextStableId()

    private fun <T> DomainResult<T>.requireSuccess(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error(error.code)
    }

    private fun languageFromTag(value: String): OnboardingLanguage = OnboardingLanguage.entries.firstOrNull { it.tag == value } ?: OnboardingLanguage.SIMPLIFIED_CHINESE

    private fun encodeRecoveryEnvelope(value: RecoveryWrappedKeyMaterial): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(value.envelopeVersion)
            output.writeInt(value.parameters.formatVersion)
            output.writeInt(value.parameters.memoryKiB)
            output.writeInt(value.parameters.iterations)
            output.writeInt(value.parameters.parallelism)
            output.writeInt(value.parameters.outputBytes)
            listOf(value.salt, value.nonce, value.ciphertext).forEach { item ->
                output.writeInt(item.size)
                output.write(item)
                item.fill(0)
            }
        }
        bytes.toByteArray()
    }

    private companion object {
        const val DEFAULT_CURRENCY = "JPY"
        const val DEFAULT_ZONE = "Asia/Tokyo"
        const val MAX_RECOVERY_LENGTH = 256
        const val MAX_REFERENCE_NAME = 80
        const val RECOVERY_VERIFIER_BYTES = 32
        const val DEEP_LINK_SCHEME = "ledger"
        const val DEEP_LINK_HOST = "screen"
        const val REFERENCE_REVISION_ID_RESERVE = 128
        const val FINANCIAL_FACT_ID_RESERVE = 256
        const val FX_ID_RESERVE = 8
        const val RECORD_SEARCH_LIMIT = 80
        const val RECORD_ATTACHMENT_NAME_LIMIT = 255
        const val JOURNAL_RETENTION_SECONDS = 30L * 24L * 60L * 60L
    }
}

private data class JournalPagingRequest(
    val bookId: StableId,
    val filter: TransactionFilter,
    val runningBalanceAccountId: StableId? = null,
    val refreshEpoch: Int,
)

private sealed interface PendingRecordExit {
    data object Back : PendingRecordExit
    data class TopLevel(val target: TopLevelDestination) : PendingRecordExit
}
