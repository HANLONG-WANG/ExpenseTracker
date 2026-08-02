@file:Suppress("LongMethod", "TooManyFunctions", "MagicNumber", "LongParameterList", "LargeClass", "MaxLineLength")

package app.ledger.app

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import app.ledger.feature.accounts.AccountEditorSubmission
import app.ledger.feature.accounts.CardEditorSubmission
import app.ledger.feature.accounts.CheckpointSubmission
import app.ledger.feature.accounts.OpeningBalanceSubmission
import app.ledger.feature.onboarding.InitialAccountType
import app.ledger.feature.onboarding.InitialCategoryDirection
import app.ledger.feature.onboarding.OnboardingLanguage
import app.ledger.feature.onboarding.OnboardingRenderState
import app.ledger.feature.onboarding.OnboardingStep
import app.ledger.feature.onboarding.OnboardingUiState
import app.ledger.feature.onboarding.OnboardingValidator
import app.ledger.feature.settings.CategorySubmission
import app.ledger.feature.settings.MerchantSubmission
import app.ledger.feature.settings.PlaceSubmission
import app.ledger.finance.application.AccountDraft
import app.ledger.finance.application.CardDraft
import app.ledger.finance.application.CategoryDraft
import app.ledger.finance.application.InitialAccountCommand
import app.ledger.finance.application.InitialCategoryCommand
import app.ledger.finance.application.InitializeLedgerCommand
import app.ledger.finance.application.LedgerGenesisIds
import app.ledger.finance.application.LedgerInitializationPort
import app.ledger.finance.application.MerchantDraft
import app.ledger.finance.application.OpeningBalanceWriteIds
import app.ledger.finance.application.OpeningBalanceWritePort
import app.ledger.finance.application.OpeningBalanceWriteRequest
import app.ledger.finance.application.PlaceDraft
import app.ledger.finance.application.ReferenceDataManagementPort
import app.ledger.finance.application.ReferenceDataSnapshot
import app.ledger.finance.application.ReferenceMutation
import app.ledger.finance.application.ReferenceMutationCommand
import app.ledger.finance.application.ReferenceMutationIds
import app.ledger.finance.application.UpdateBookLocaleCommand
import app.ledger.finance.data.RoomLedgerStartupInspector
import app.ledger.finance.domain.CategoryDirection
import app.ledger.finance.domain.CategoryRemovalStrategy
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.SystemLedgerCode
import app.ledger.finance.domain.UserAccountType
import com.google.protobuf.ByteString
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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
    private val openingBalanceWritePort: OpeningBalanceWritePort,
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
                is DomainResult.Success -> AppReferenceDataState.Content(result.value)
                is DomainResult.Failure -> AppReferenceDataState.Error(result.error.code)
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
            is DomainResult.Success -> AppReferenceDataState.Content(result.value)
            is DomainResult.Failure -> AppReferenceDataState.Error(result.error.code)
        }
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
    }
}
