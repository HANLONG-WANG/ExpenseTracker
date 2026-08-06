@file:Suppress("LongMethod", "TooManyFunctions", "MagicNumber", "LongParameterList", "LargeClass", "MaxLineLength", "ReturnCount", "CyclomaticComplexMethod")

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
import app.ledger.core.common.map
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
import app.ledger.core.navigation.YearMonthArgument
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
import app.ledger.feature.automation.AutomationFeatureState
import app.ledger.feature.automation.AutomationLoadState
import app.ledger.feature.automation.AutomationPolicy
import app.ledger.feature.automation.AutomationPresentation
import app.ledger.feature.automation.BlueprintField
import app.ledger.feature.automation.RecurrenceField
import app.ledger.feature.journal.JournalLoadState
import app.ledger.feature.journal.JournalOperationState
import app.ledger.feature.journal.JournalPagingSource
import app.ledger.feature.journal.JournalSelectionPolicy
import app.ledger.feature.liabilities.CreditAllocationMode
import app.ledger.feature.liabilities.CreditFeatureState
import app.ledger.feature.liabilities.CreditField
import app.ledger.feature.liabilities.CreditLoadState
import app.ledger.feature.liabilities.CreditPolicy
import app.ledger.feature.liabilities.CreditPresentation
import app.ledger.feature.liabilities.InstallmentFeatureState
import app.ledger.feature.liabilities.InstallmentField
import app.ledger.feature.liabilities.InstallmentLoadState
import app.ledger.feature.liabilities.InstallmentPolicy
import app.ledger.feature.liabilities.InstallmentPresentation
import app.ledger.feature.liabilities.LoanFeatureState
import app.ledger.feature.liabilities.LoanField
import app.ledger.feature.liabilities.LoanLoadState
import app.ledger.feature.liabilities.LoanPolicy
import app.ledger.feature.liabilities.LoanPresentation
import app.ledger.feature.onboarding.InitialAccountType
import app.ledger.feature.onboarding.InitialCategoryDirection
import app.ledger.feature.onboarding.OnboardingLanguage
import app.ledger.feature.onboarding.OnboardingRenderState
import app.ledger.feature.onboarding.OnboardingStep
import app.ledger.feature.onboarding.OnboardingUiState
import app.ledger.feature.onboarding.OnboardingValidator
import app.ledger.feature.planning.BudgetFeatureState
import app.ledger.feature.planning.BudgetLoadState
import app.ledger.feature.planning.BudgetPolicy
import app.ledger.feature.planning.BudgetPresentation
import app.ledger.feature.planning.ProjectGoalFeatureState
import app.ledger.feature.planning.ProjectGoalLoadState
import app.ledger.feature.planning.ProjectGoalPolicy
import app.ledger.feature.planning.ProjectGoalPresentation
import app.ledger.feature.planning.ProjectTransactionPagingSource
import app.ledger.feature.record.BatchRecordState
import app.ledger.feature.record.BatchRowDraft
import app.ledger.feature.record.BatchSort
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
import app.ledger.feature.settlement.SettlementFeatureState
import app.ledger.feature.settlement.SettlementField
import app.ledger.feature.settlement.SettlementLoadState
import app.ledger.feature.settlement.SettlementParticipantDraft
import app.ledger.feature.settlement.SettlementPolicy
import app.ledger.feature.settlement.SettlementPresentation
import app.ledger.finance.application.AccountDraft
import app.ledger.finance.application.ApplyInstallmentSettlementRequest
import app.ledger.finance.application.ApplyLoanSimulationRequest
import app.ledger.finance.application.AssignCreditStatementRequest
import app.ledger.finance.application.AttachmentContentSource
import app.ledger.finance.application.AttachmentImportRequest
import app.ledger.finance.application.AutomationApplicationPort
import app.ledger.finance.application.AutomationMutationIds
import app.ledger.finance.application.BatchEntryApplicationPort
import app.ledger.finance.application.BatchEntryField
import app.ledger.finance.application.BlueprintDraft
import app.ledger.finance.application.BookAttachmentObjectPort
import app.ledger.finance.application.BudgetApplicationPort
import app.ledger.finance.application.BudgetMutationIds
import app.ledger.finance.application.CardDraft
import app.ledger.finance.application.CategoryDraft
import app.ledger.finance.application.ChangeProjectStatusRequest
import app.ledger.finance.application.CompleteGoalRequest
import app.ledger.finance.application.CreditApplicationPort
import app.ledger.finance.application.CreditMutationIds
import app.ledger.finance.application.CreditPaymentContext
import app.ledger.finance.application.CreditStatementMutationIds
import app.ledger.finance.application.CreditTransactionMutationIds
import app.ledger.finance.application.GoalCompletionStrategy
import app.ledger.finance.application.GoalMovementMutationIds
import app.ledger.finance.application.InitialAccountCommand
import app.ledger.finance.application.InitialCategoryCommand
import app.ledger.finance.application.InitializeLedgerCommand
import app.ledger.finance.application.InstallmentApplicationPort
import app.ledger.finance.application.InstallmentMutationIds
import app.ledger.finance.application.InstallmentSettlementIds
import app.ledger.finance.application.InstallmentTermsDraft
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
import app.ledger.finance.application.LoanApplicationPort
import app.ledger.finance.application.LoanComponentAllocationDraft
import app.ledger.finance.application.LoanComponentAmountDraft
import app.ledger.finance.application.LoanMutationIds
import app.ledger.finance.application.LoanPaymentAmountsDraft
import app.ledger.finance.application.LoanSimulationRequest
import app.ledger.finance.application.LoanTermsDraft
import app.ledger.finance.application.LoanTrancheDraft
import app.ledger.finance.application.LoanTrancheMutationIds
import app.ledger.finance.application.LoanTransactionContext
import app.ledger.finance.application.LoanTransactionIds
import app.ledger.finance.application.MerchantDraft
import app.ledger.finance.application.ModifyOccurrenceRequest
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
import app.ledger.finance.application.PlanningMutationIds
import app.ledger.finance.application.ProjectGoalApplicationPort
import app.ledger.finance.application.RecordBudgetAdjustmentRequest
import app.ledger.finance.application.RecordCreditPaymentRequest
import app.ledger.finance.application.RecordGoalMovementRequest
import app.ledger.finance.application.RecordLoanDisbursementRequest
import app.ledger.finance.application.RecordLoanPaymentRequest
import app.ledger.finance.application.RecordSettlementPaymentRequest
import app.ledger.finance.application.RecurrenceSeriesDraft
import app.ledger.finance.application.ReferenceDataManagementPort
import app.ledger.finance.application.ReferenceDataSnapshot
import app.ledger.finance.application.ReferenceMutation
import app.ledger.finance.application.ReferenceMutationCommand
import app.ledger.finance.application.ReferenceMutationIds
import app.ledger.finance.application.RefundApplicationPort
import app.ledger.finance.application.RefundSearchQuery
import app.ledger.finance.application.RefundWriteIds
import app.ledger.finance.application.RefundWriteRequest
import app.ledger.finance.application.SaveBlueprintRequest
import app.ledger.finance.application.SaveBudgetMonthRequest
import app.ledger.finance.application.SaveBudgetTemplateRequest
import app.ledger.finance.application.SaveCreditProfileRequest
import app.ledger.finance.application.SaveCreditStatementRequest
import app.ledger.finance.application.SaveGoalRequest
import app.ledger.finance.application.SaveInstallmentPlanRequest
import app.ledger.finance.application.SaveLoanContractRequest
import app.ledger.finance.application.SaveProjectRequest
import app.ledger.finance.application.SaveRecurrenceRequest
import app.ledger.finance.application.SaveSettlementActivityRequest
import app.ledger.finance.application.SettlementApplicationPort
import app.ledger.finance.application.SettlementMutationIds
import app.ledger.finance.application.SettlementPaymentIds
import app.ledger.finance.application.SpecializedAccountAmountDraft
import app.ledger.finance.application.SpecializedFxQuoteRequest
import app.ledger.finance.application.SpecializedTransactionContext
import app.ledger.finance.application.SpecializedTransactionEntryPort
import app.ledger.finance.application.SpecializedTransactionWriteIds
import app.ledger.finance.application.SpecializedTransactionWriteRequest
import app.ledger.finance.application.UpdateBookLocaleCommand
import app.ledger.finance.data.RoomLedgerStartupInspector
import app.ledger.finance.domain.AutoGenerationMode
import app.ledger.finance.domain.BalanceAdjustmentDirection
import app.ledger.finance.domain.BudgetAdjustmentKind
import app.ledger.finance.domain.CategoryDirection
import app.ledger.finance.domain.CategoryRemovalStrategy
import app.ledger.finance.domain.CreditPaymentSelection
import app.ledger.finance.domain.DependencyPolicy
import app.ledger.finance.domain.DependencyResolution
import app.ledger.finance.domain.DueDateRule
import app.ledger.finance.domain.GoalMovementKind
import app.ledger.finance.domain.GoalStatus
import app.ledger.finance.domain.InstallmentFeeRateType
import app.ledger.finance.domain.InstallmentPrepaymentPolicy
import app.ledger.finance.domain.InstallmentRefundPolicy
import app.ledger.finance.domain.InterestRate
import app.ledger.finance.domain.LoanPaymentComponent
import app.ledger.finance.domain.LoanPrepaymentPolicy
import app.ledger.finance.domain.LoanRatePeriod
import app.ledger.finance.domain.LoanRateType
import app.ledger.finance.domain.LoanRepaymentMethod
import app.ledger.finance.domain.LoanSimulationScenario
import app.ledger.finance.domain.LoanStatus
import app.ledger.finance.domain.MissingDayPolicy
import app.ledger.finance.domain.PaymentFrequency
import app.ledger.finance.domain.PrepaymentRecalculationStrategy
import app.ledger.finance.domain.ProjectStatus
import app.ledger.finance.domain.RecurrenceFrequency
import app.ledger.finance.domain.RecurrenceGenerationMode
import app.ledger.finance.domain.RecurrenceModificationScope
import app.ledger.finance.domain.RefundAccrualPolicy
import app.ledger.finance.domain.RefundBudgetPolicy
import app.ledger.finance.domain.RefundGoalPolicy
import app.ledger.finance.domain.RefundProjectPolicy
import app.ledger.finance.domain.ScheduleRevisionReason
import app.ledger.finance.domain.SettlementActivityStatus
import app.ledger.finance.domain.SettlementChargeDistribution
import app.ledger.finance.domain.SettlementRoundingRule
import app.ledger.finance.domain.SettlementSplitMethod
import app.ledger.finance.domain.StatementAssignmentMode
import app.ledger.finance.domain.StatementDateRule
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.SystemLedgerCode
import app.ledger.finance.domain.TransactionDependency
import app.ledger.finance.domain.TransactionFilter
import app.ledger.finance.domain.TransactionId
import app.ledger.finance.domain.TransactionLifecycleState
import app.ledger.finance.domain.TransactionSource
import app.ledger.finance.domain.UserAccountType
import app.ledger.finance.domain.WeekendAdjustment
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
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import app.ledger.finance.application.SettlementParticipantDraft as SettlementParticipantWriteDraft

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
    batchEntryApplicationPort: BatchEntryApplicationPort,
    private val refundApplicationPort: RefundApplicationPort,
    private val budgetApplicationPort: BudgetApplicationPort,
    private val projectGoalApplicationPort: ProjectGoalApplicationPort,
    private val creditApplicationPort: CreditApplicationPort,
    private val installmentApplicationPort: InstallmentApplicationPort,
    private val loanApplicationPort: LoanApplicationPort,
    private val settlementApplicationPort: SettlementApplicationPort,
    private val automationApplicationPort: AutomationApplicationPort,
    private val specializedTransactionEntryPort: SpecializedTransactionEntryPort,
    private val bookAttachmentObjectPort: BookAttachmentObjectPort,
    private val runtimeSources: AppRuntimeSources,
) : ViewModel() {
    private val batchEntryController = BatchEntryController(
        batchEntryApplicationPort,
        ordinaryTransactionEntryPort,
        refundApplicationPort,
        installmentApplicationPort,
        runtimeSources,
    )
    val batchRecord: StateFlow<BatchRecordState?> = batchEntryController.state
    val batchRecordPending: StateFlow<Boolean> = batchEntryController.pending
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
    private val mutableBudget = MutableStateFlow<BudgetLoadState>(BudgetLoadState.Loading)
    val budget: StateFlow<BudgetLoadState> = mutableBudget.asStateFlow()
    private val mutableBudgetPending = MutableStateFlow(false)
    val budgetPending: StateFlow<Boolean> = mutableBudgetPending.asStateFlow()
    private val mutableProjectGoal = MutableStateFlow<ProjectGoalLoadState>(ProjectGoalLoadState.Loading)
    val projectGoal: StateFlow<ProjectGoalLoadState> = mutableProjectGoal.asStateFlow()
    private val mutableProjectGoalPending = MutableStateFlow(false)
    val projectGoalPending: StateFlow<Boolean> = mutableProjectGoalPending.asStateFlow()
    private val mutableCredit = MutableStateFlow<CreditLoadState>(CreditLoadState.Loading)
    val credit: StateFlow<CreditLoadState> = mutableCredit.asStateFlow()
    private val mutableCreditPending = MutableStateFlow(false)
    val creditPending: StateFlow<Boolean> = mutableCreditPending.asStateFlow()
    private var currentCreditScreenId: String = "CRD-001"
    private var currentCreditTransactionId: StableId? = null
    private val mutableInstallment = MutableStateFlow<InstallmentLoadState>(InstallmentLoadState.Loading)
    val installment: StateFlow<InstallmentLoadState> = mutableInstallment.asStateFlow()
    private val mutableInstallmentPending = MutableStateFlow(false)
    val installmentPending: StateFlow<Boolean> = mutableInstallmentPending.asStateFlow()
    private var currentInstallmentScreenId: String = "INS-001"
    private val mutableLoan = MutableStateFlow<LoanLoadState>(LoanLoadState.Loading)
    val loan: StateFlow<LoanLoadState> = mutableLoan.asStateFlow()
    private val mutableLoanPending = MutableStateFlow(false)
    val loanPending: StateFlow<Boolean> = mutableLoanPending.asStateFlow()
    private var currentLoanScreenId: String = "LIA-001"
    private var currentLoanSimulationRequest: LoanSimulationRequest? = null
    private var currentLoanSimulation: app.ledger.finance.domain.LoanPrepaymentSimulation? = null
    private val mutableSettlement = MutableStateFlow<SettlementLoadState>(SettlementLoadState.Loading)
    val settlement: StateFlow<SettlementLoadState> = mutableSettlement.asStateFlow()
    private val mutableSettlementPending = MutableStateFlow(false)
    val settlementPending: StateFlow<Boolean> = mutableSettlementPending.asStateFlow()
    private var currentSettlementScreenId: String = "SET-001"
    private val mutableAutomation = MutableStateFlow<AutomationLoadState>(AutomationLoadState.Loading)
    val automation: StateFlow<AutomationLoadState> = mutableAutomation.asStateFlow()
    private val mutableAutomationPending = MutableStateFlow(false)
    val automationPending: StateFlow<Boolean> = mutableAutomationPending.asStateFlow()
    private var currentAutomationScreenId: String = "AUT-001"
    private var pendingCandidateId: StableId? = null
    private val mutableProjectTransactionPagingRequest = MutableStateFlow<ProjectTransactionPagingRequest?>(null)

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

    @OptIn(ExperimentalCoroutinesApi::class)
    val projectTransactionPages = mutableProjectTransactionPagingRequest.flatMapLatest { request ->
        if (request == null) {
            flowOf(PagingData.empty())
        } else {
            Pager(
                PagingConfig(pageSize = 40, prefetchDistance = 10, initialLoadSize = 40, enablePlaceholders = false, maxSize = 200),
            ) { ProjectTransactionPagingSource(projectGoalApplicationPort, request.bookId, request.projectId) }.flow
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
    private var pendingBatchAttachmentRowId: StableId? = null
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
                    catchUpAutomation(bookId)
                }
            }
        }
        manager.initialize()
        if (!saved.appLockEnabled) manager.unlockUi()
    }

    private fun catchUpAutomation(bookId: StableId) {
        RecurrenceWorkScheduler.enqueueCatchUp(context, bookId)
        RecurrenceWorkScheduler.ensurePeriodicCatchUp(context, bookId)
        viewModelScope.launch(Dispatchers.IO) {
            automationApplicationPort.catchUp(bookId, runtimeSources.clock.now())
        }
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

    fun openBatchEntry() {
        if ((mutableRootState.value as? AppRootState.Session)?.state !is BookSessionState.Ready) return
        viewModelScope.launch(Dispatchers.IO) {
            val saved = settingsRepository.current()
            if (batchEntryController.open(requireBookId(saved), ZoneId.of(saved.zoneId.ifBlank { DEFAULT_ZONE }))) {
                navigator.navigate(LedgerRouteContract.destination(ScreenId("REC-023")), SessionGateState.READY)
            }
        }
    }

    fun openBatchRow(rowId: StableId) {
        batchEntryController.selectRow(rowId)
        val screenId = ScreenId("REC-024")
        navigator.navigate(
            LedgerRouteContract.destination(screenId, mapOf("rowId" to StableIdArgument(rowId))),
            SessionGateState.READY,
        )
    }

    fun addBatchRow() = batchEntryController.add()
    fun copyBatchRow(rowId: StableId) = batchEntryController.copy(rowId)
    fun deleteBatchRow(rowId: StableId) = batchEntryController.delete(rowId)
    fun moveBatchRow(rowId: StableId, targetIndex: Int) = batchEntryController.move(rowId, targetIndex)
    fun sortBatchRows(order: BatchSort) = batchEntryController.sort(order)
    fun pasteBatchRows(text: String) = batchEntryController.paste(text)
    fun updateBatchRow(row: BatchRowDraft) = batchEntryController.updateRow(row)
    fun cycleBatchReference(rowId: StableId, field: BatchEntryField) = batchEntryController.cycle(rowId, field)

    fun requestBatchAttachment(rowId: StableId) {
        pendingBatchAttachmentRowId = rowId
    }

    fun importBatchAttachment(uri: Uri) {
        val rowId = pendingBatchAttachmentRowId ?: return
        pendingBatchAttachmentRowId = null
        viewModelScope.launch(Dispatchers.IO) {
            val state = batchRecord.value ?: return@launch
            val metadata = attachmentMetadata(uri) ?: return@launch
            val request = AttachmentImportRequest(
                displayName = metadata.first,
                mimeType = context.contentResolver.getType(uri),
                extension = metadata.first.substringAfterLast('.', "").takeIf(String::isNotBlank),
                declaredSize = metadata.second,
                content = AttachmentContentSource { requireNotNull(context.contentResolver.openInputStream(uri)) },
            )
            when (val result = bookAttachmentObjectPort.import(state.snapshot.references.bookId, request)) {
                is DomainResult.Success -> batchEntryController.attach(rowId, result.value.attachmentId.value)
                is DomainResult.Failure -> Unit
            }
        }
    }

    fun validateBatchEntry() {
        if (navigator.currentKey.contract.screenId.value != "REC-025") {
            navigator.navigate(LedgerRouteContract.destination(ScreenId("REC-025")), SessionGateState.READY)
        }
        viewModelScope.launch(Dispatchers.IO) {
            batchEntryController.validate()
        }
    }

    fun confirmBatchWarnings() = batchEntryController.confirmWarnings()

    fun submitBatchEntry() {
        viewModelScope.launch(Dispatchers.IO) {
            if (batchEntryController.submit()) {
                val bookId = batchRecord.value?.snapshot?.references?.bookId
                if (bookId != null) loadReferenceDataAfterMutation(bookId)
                loadJournal()
                while (navigator.currentKey.contract.screenId.value != "REC-023" && navigator.currentBackStack.size > 1) navigator.pop()
            }
        }
    }

    fun undoBatchEntry() {
        viewModelScope.launch(Dispatchers.IO) {
            val bookId = batchRecord.value?.snapshot?.references?.bookId
            if (batchEntryController.undo()) {
                if (bookId != null) loadReferenceDataAfterMutation(bookId)
                loadJournal()
                discardBatchEntry()
            }
        }
    }

    fun discardBatchEntry() {
        batchEntryController.discard()
        pendingBatchAttachmentRowId = null
        while (navigator.currentKey.contract.screenId.value != "REC-001" && navigator.currentBackStack.size > 1) navigator.pop()
    }

    fun keepEditingBatchEntry() = batchEntryController.keepEditing()

    fun jumpToBatchIssue(issue: app.ledger.finance.application.BatchValidationIssue) {
        val rowId = issue.rowId ?: return
        openBatchRow(rowId)
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
        if (target == "REC-023") {
            openBatchEntry()
            return
        }
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
    fun selectRecordSettlementPayer(id: StableId) = updateEditor { OrdinaryRecordPolicy.selectSettlementPayer(it, id) }
    fun selectRecordSettlementSplitMethod(method: app.ledger.finance.domain.SettlementSplitMethod) = updateEditor { OrdinaryRecordPolicy.selectSettlementSplitMethod(it, method) }
    fun selectRecordSettlementChargeDistribution(distribution: app.ledger.finance.domain.SettlementChargeDistribution) = updateEditor { OrdinaryRecordPolicy.selectSettlementChargeDistribution(it, distribution) }
    fun selectRecordSettlementRoundingRule(rule: app.ledger.finance.domain.SettlementRoundingRule) = updateEditor { OrdinaryRecordPolicy.selectSettlementRoundingRule(it, rule) }
    fun toggleRecordSettlementParticipant(id: StableId) = updateEditor { OrdinaryRecordPolicy.toggleSettlementParticipant(it, id) }
    fun updateRecordSettlementAllocationInput(id: StableId, value: String) = updateEditor { OrdinaryRecordPolicy.updateSettlementAllocationInput(it, id, value) }
    fun updateRecordSettlementChargeInput(id: StableId, value: String) = updateEditor { OrdinaryRecordPolicy.updateSettlementChargeInput(it, id, value) }
    fun updateRecordSettlementTax(value: String) = updateEditor { OrdinaryRecordPolicy.updateSettlementTax(it, value) }
    fun updateRecordSettlementServiceFee(value: String) = updateEditor { OrdinaryRecordPolicy.updateSettlementServiceFee(it, value) }
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
        val activity = validated.snapshot.settlementActivities.singleOrNull { it.id == validated.draft.settlementActivityId }
        val externalPayer = validated.draft.settlementEnabled &&
            activity?.participants?.singleOrNull { it.id == validated.draft.settlementPayerParticipantId }?.isSelf == false
        val account = validated.snapshot.references.accounts.singleOrNull { it.id == validated.draft.accountId }
        if (!externalPayer && account == null) return
        val userCurrency = if (externalPayer) requireNotNull(activity).currency else requireNotNull(account).currency
        val baseMinor = if (externalPayer && userCurrency == validated.snapshot.references.baseCurrency) {
            amountMinor
        } else if (externalPayer) {
            validated.snapshot.references.accounts.asSequence()
                .filter { it.currency == userCurrency }
                .mapNotNull { valuedBaseMinor(amountMinor, it.balanceMinor, it.currentBaseValueMinor) }
                .firstOrNull()
        } else {
            val localAccount = requireNotNull(account)
            baseMinor(validated, localAccount.balanceMinor, localAccount.currentBaseValueMinor)
        } ?: run {
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
                    amount = OrdinaryAmountDraft(validated.draft.expression, amountMinor, userCurrency, amountMinor, baseMinor),
                    accountId = if (externalPayer) null else validated.draft.accountId,
                    cardId = if (externalPayer) null else validated.draft.cardId,
                    merchantId = validated.draft.merchantId,
                    occurredAt = validated.draft.occurredAt,
                    zoneId = validated.draft.zoneId,
                    localDate = validated.draft.occurredAt.atZone(validated.draft.zoneId).toLocalDate(),
                    projectId = validated.draft.projectId,
                    goalId = null,
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
                    acceptedCandidateId = validated.sourceReferenceId.takeIf { validated.mode == RecordEditorMode.CANDIDATE },
                )
                when (val result = ordinaryTransactionEntryPort.submit(request)) {
                    is DomainResult.Success -> {
                        if (validated.mode == RecordEditorMode.CANDIDATE) pendingCandidateId = null
                        finishRecordSave(validated, ids.transactionId)
                    }
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

    fun loadBudget(month: YearMonth, templateId: StableId? = null, screenId: String = "BUD-001") {
        if ((mutableRootState.value as? AppRootState.Session)?.state !is BookSessionState.Ready) return
        mutableBudget.value = BudgetLoadState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            val bookId = runCatching { requireBookId(settingsRepository.current()) }.getOrNull() ?: return@launch
            val today = runtimeSources.clock.now().atZone(ZoneId.of(settingsRepository.current().zoneId.ifBlank { DEFAULT_ZONE })).toLocalDate()
            mutableBudget.value = when (val result = budgetApplicationPort.snapshot(bookId, month, today)) {
                is DomainResult.Failure -> BudgetLoadState.Failure(sanitizeCode(result.error.code))
                is DomainResult.Success -> {
                    var state = BudgetPolicy.create(result.value)
                    if (templateId != null) {
                        val template = result.value.templates.singleOrNull { it.id == templateId }
                        if (template != null) {
                            state = BudgetPolicy.validate(
                                state.copy(
                                    selectedTemplateId = template.id,
                                    presentation = BudgetPresentation.EDIT,
                                    editor = state.editor.copy(
                                        templateName = template.name,
                                        totalText = budgetMinorText(template.revision.totalBaseMinor, result.value.baseCurrency),
                                        categoryTexts = template.revision.limits.associate { limit ->
                                            limit.categoryId to budgetMinorText(limit.amountBaseMinor, result.value.baseCurrency)
                                        },
                                    ),
                                ),
                            )
                        }
                    }
                    state = when (screenId) {
                        "BUD-002", "BUD-003" -> BudgetPolicy.edit(state)
                        "BUD-004" -> state.copy(presentation = if (state.snapshot.adjustments.isEmpty()) BudgetPresentation.EMPTY else BudgetPresentation.CONTENT)
                        "BUD-005" -> state.copy(presentation = BudgetPresentation.EDITING)
                        "BUD-006" -> state.copy(presentation = if (state.snapshot.revisionHistory.size <= 1) BudgetPresentation.SINGLE_REVISION else BudgetPresentation.CONTENT)
                        "BUD-007" -> state.copy(presentation = if (state.snapshot.templates.isEmpty()) BudgetPresentation.EMPTY else BudgetPresentation.CONTENT)
                        "BUD-008" -> state.copy(presentation = if (templateId == null) BudgetPresentation.CREATE else BudgetPresentation.EDIT)
                        else -> state
                    }
                    BudgetLoadState.Content(state)
                }
            }
        }
    }

    fun editBudget() = updateBudget(BudgetPolicy::edit)

    fun currentBudgetMonth(): YearMonth {
        val zone = ZoneId.of(settings.value.zoneId.ifBlank { DEFAULT_ZONE })
        return YearMonth.from(runtimeSources.clock.now().atZone(zone))
    }
    fun updateBudgetTotal(value: String) = updateBudget { BudgetPolicy.updateTotal(it, value) }
    fun updateBudgetCategory(id: StableId, value: String) = updateBudget { BudgetPolicy.updateCategory(it, id, value) }
    fun updateBudgetTemplateName(value: String) = updateBudget { BudgetPolicy.updateTemplateName(it, value) }
    fun updateBudgetAdjustmentAmount(value: String) = updateBudget { BudgetPolicy.updateAdjustmentAmount(it, value) }
    fun selectBudgetAdjustmentSource() = updateBudget(BudgetPolicy::selectNextAdjustmentSource)
    fun selectBudgetAdjustmentTarget() = updateBudget(BudgetPolicy::selectNextAdjustmentTarget)

    fun saveBudgetMonth() {
        val content = mutableBudget.value as? BudgetLoadState.Content ?: return
        val validated = BudgetPolicy.validate(content.state)
        if (!validated.validation.valid) {
            mutableBudget.value = BudgetLoadState.Content(validated.copy(presentation = BudgetPresentation.CONSTRAINT_ERROR))
            return
        }
        if (mutableBudgetPending.value) return
        mutableBudgetPending.value = true
        mutableBudget.value = BudgetLoadState.Content(validated.copy(presentation = BudgetPresentation.SAVING))
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val snapshot = validated.snapshot
                val request = SaveBudgetMonthRequest(
                    budgetIds(snapshot.bookId, snapshot.monthId ?: nextId(), 0),
                    snapshot.month,
                    snapshot.currentRevision?.id,
                    requireNotNull(validated.validation.totalMinor),
                    validated.validation.limits,
                    null,
                    runtimeSources.clock.now(),
                )
                when (val result = budgetApplicationPort.saveMonth(request)) {
                    is DomainResult.Success -> {
                        loadBudget(snapshot.month)
                        while (navigator.currentKey.contract.screenId.value != "BUD-001" && navigator.currentBackStack.size > 1) navigator.pop()
                    }
                    is DomainResult.Failure -> mutableBudget.value = BudgetLoadState.Content(validated.copy(presentation = BudgetPresentation.FAILED, failureCode = sanitizeCode(result.error.code)))
                }
            } finally {
                mutableBudgetPending.value = false
            }
        }
    }

    fun saveBudgetTemplate() {
        val content = mutableBudget.value as? BudgetLoadState.Content ?: return
        val validated = BudgetPolicy.validate(content.state)
        if (!validated.validation.valid || validated.editor.templateName.isBlank()) {
            mutableBudget.value = BudgetLoadState.Content(validated.copy(presentation = BudgetPresentation.CONSTRAINT_ERROR))
            return
        }
        if (mutableBudgetPending.value) return
        mutableBudgetPending.value = true
        mutableBudget.value = BudgetLoadState.Content(validated.copy(presentation = BudgetPresentation.SAVING))
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val existing = validated.selectedTemplateId?.let { id -> validated.snapshot.templates.singleOrNull { it.id == id } }
                val request = SaveBudgetTemplateRequest(
                    budgetIds(validated.snapshot.bookId, existing?.id ?: nextId(), 0),
                    existing?.revision?.id,
                    validated.editor.templateName.trim(),
                    app.ledger.finance.domain.EntityStatus.ACTIVE,
                    requireNotNull(validated.validation.totalMinor),
                    validated.validation.limits,
                    runtimeSources.clock.now(),
                )
                when (val result = budgetApplicationPort.saveTemplate(request)) {
                    is DomainResult.Success -> {
                        loadBudget(validated.snapshot.month)
                        while (navigator.currentKey.contract.screenId.value != "BUD-001" && navigator.currentBackStack.size > 1) navigator.pop()
                    }
                    is DomainResult.Failure -> mutableBudget.value = BudgetLoadState.Content(validated.copy(presentation = BudgetPresentation.FAILED, failureCode = sanitizeCode(result.error.code)))
                }
            } finally {
                mutableBudgetPending.value = false
            }
        }
    }

    fun saveBudgetAdjustment(kind: BudgetAdjustmentKind) {
        val content = mutableBudget.value as? BudgetLoadState.Content ?: return
        val state = content.state
        val amount = if (kind == BudgetAdjustmentKind.CLEAR_ROLLOVER) 1L else BudgetPolicy.adjustmentMinor(state)
        if (amount == null) {
            mutableBudget.value = BudgetLoadState.Content(state.copy(presentation = BudgetPresentation.INVALID))
            return
        }
        val source = state.adjustmentSourceCategoryId
        val target = state.adjustmentTargetCategoryId
        if (kind in setOf(BudgetAdjustmentKind.TRANSFER_IN, BudgetAdjustmentKind.ARCHIVED_CATEGORY_TRANSFER) && (source == null || target == null)) {
            mutableBudget.value = BudgetLoadState.Content(state.copy(presentation = BudgetPresentation.INVALID))
            return
        }
        if (mutableBudgetPending.value) return
        mutableBudgetPending.value = true
        mutableBudget.value = BudgetLoadState.Content(state.copy(presentation = BudgetPresentation.SAVING))
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val request = RecordBudgetAdjustmentRequest(
                    budgetIds(state.snapshot.bookId, state.snapshot.monthId ?: nextId(), if (kind in setOf(BudgetAdjustmentKind.TRANSFER_IN, BudgetAdjustmentKind.ARCHIVED_CATEGORY_TRANSFER)) 2 else 1),
                    state.snapshot.month,
                    kind,
                    amount,
                    if (kind == BudgetAdjustmentKind.INCREASE_AVAILABLE) null else source,
                    if (kind in setOf(BudgetAdjustmentKind.DECREASE_AVAILABLE, BudgetAdjustmentKind.CLEAR_ROLLOVER)) null else target,
                    runtimeSources.clock.now(),
                )
                when (val result = budgetApplicationPort.recordAdjustment(request)) {
                    is DomainResult.Success -> {
                        loadBudget(state.snapshot.month)
                        while (navigator.currentKey.contract.screenId.value != "BUD-001" && navigator.currentBackStack.size > 1) navigator.pop()
                    }
                    is DomainResult.Failure -> mutableBudget.value = BudgetLoadState.Content(state.copy(presentation = BudgetPresentation.FAILED, failureCode = sanitizeCode(result.error.code)))
                }
            } finally {
                mutableBudgetPending.value = false
            }
        }
    }

    fun navigateBudget(target: String, month: YearMonth, stableId: StableId?, adjustmentKind: BudgetAdjustmentKind?) {
        val screenId = ScreenId(target)
        val arguments = buildMap<String, SafeRouteArgument> {
            if (target in setOf("BUD-001", "BUD-002", "BUD-003", "BUD-004", "BUD-005", "BUD-006")) put("yearMonth", YearMonthArgument(month))
            if (target == "BUD-003" && stableId != null) put("categoryId", StableIdArgument(stableId))
            if (target == "BUD-008" && stableId != null) put("templateId", StableIdArgument(stableId))
            if (target == "BUD-005" && adjustmentKind != null) {
                val routeValue = when (adjustmentKind) {
                    BudgetAdjustmentKind.CLEAR_ROLLOVER -> "CLEAR_ROLLOVER"
                    BudgetAdjustmentKind.INCREASE_AVAILABLE -> "ADD"
                    BudgetAdjustmentKind.DECREASE_AVAILABLE -> "SUBTRACT"
                    else -> "TRANSFER"
                }
                put("type", LedgerRouteContract.enumArgument(screenId, "type", routeValue))
            }
        }
        navigator.navigate(LedgerRouteContract.destination(screenId, arguments), SessionGateState.READY)
    }

    private fun budgetIds(bookId: StableId, entityId: StableId, factCount: Int) = BudgetMutationIds(
        bookId,
        CommandId(nextId()),
        nextId(),
        entityId,
        nextId(),
        List(factCount) { nextId() },
        nextId(),
    )

    private fun budgetMinorText(minor: Long, currency: CurrencyCode): String {
        val scale = app.ledger.core.money.JvmLegalTenderCurrencyCatalog.create().find(currency)?.fractionDigits ?: 0
        return java.math.BigDecimal.valueOf(minor, scale).stripTrailingZeros().toPlainString()
    }

    private fun updateBudget(block: (BudgetFeatureState) -> BudgetFeatureState) {
        val current = mutableBudget.value as? BudgetLoadState.Content ?: return
        mutableBudget.value = BudgetLoadState.Content(block(current.state))
    }

    fun loadProjectGoal(
        screenId: String,
        projectId: StableId? = null,
        goalId: StableId? = null,
        movementKind: GoalMovementKind? = null,
    ) {
        if ((mutableRootState.value as? AppRootState.Session)?.state !is BookSessionState.Ready) return
        mutableProjectGoal.value = ProjectGoalLoadState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            val appSettings = settingsRepository.current()
            val bookId = runCatching { requireBookId(appSettings) }.getOrNull() ?: return@launch
            val today = runtimeSources.clock.now().atZone(ZoneId.of(appSettings.zoneId.ifBlank { DEFAULT_ZONE })).toLocalDate()
            mutableProjectGoal.value = when (val result = projectGoalApplicationPort.snapshot(bookId)) {
                is DomainResult.Failure -> ProjectGoalLoadState.Failure(sanitizeCode(result.error.code))
                is DomainResult.Success -> {
                    val requestedEntityMissing =
                        projectId != null && result.value.projects.none { it.id == projectId } ||
                            goalId != null && result.value.goals.none { it.id == goalId }
                    if (requestedEntityMissing) {
                        ProjectGoalLoadState.Failure(PROJECT_GOAL_NOT_FOUND)
                    } else {
                        mutableProjectTransactionPagingRequest.value = if (screenId == "PRJ-004" && projectId != null) {
                            ProjectTransactionPagingRequest(bookId, projectId)
                        } else {
                            null
                        }
                        val initial = ProjectGoalPolicy.create(
                            result.value,
                            today,
                            projectId,
                            goalId,
                            ProjectGoalPolicy.presentationFor(screenId, result.value, projectId, goalId),
                        )
                        ProjectGoalLoadState.Content(initial.copy(movementKind = movementKind ?: initial.movementKind))
                    }
                }
            }
        }
    }

    fun updateProjectName(value: String) = updateProjectGoal { ProjectGoalPolicy.projectName(it, value) }
    fun updateProjectDescription(value: String) = updateProjectGoal { ProjectGoalPolicy.projectDescription(it, value) }
    fun updateProjectStartDate(value: String) = updateProjectGoal { ProjectGoalPolicy.projectStartDate(it, value) }
    fun updateProjectEndDate(value: String) = updateProjectGoal { ProjectGoalPolicy.projectEndDate(it, value) }
    fun updateProjectBudget(value: String) = updateProjectGoal { ProjectGoalPolicy.projectBudget(it, value) }
    fun toggleProjectMonthlyBudget() = updateProjectGoal(ProjectGoalPolicy::toggleMonthlyBudget)
    fun selectNextProjectGoal() = updateProjectGoal(ProjectGoalPolicy::selectNextGoal)
    fun updateGoalName(value: String) = updateProjectGoal { ProjectGoalPolicy.goalName(it, value) }
    fun updateGoalTarget(value: String) = updateProjectGoal { ProjectGoalPolicy.goalTarget(it, value) }
    fun updateGoalSuggested(value: String) = updateProjectGoal { ProjectGoalPolicy.goalSuggested(it, value) }
    fun updateGoalDueDate(value: String) = updateProjectGoal { ProjectGoalPolicy.goalDueDate(it, value) }
    fun selectNextGoalAccount() = updateProjectGoal(ProjectGoalPolicy::selectNextAccount)
    fun updateGoalMovementAmount(value: String) = updateProjectGoal { ProjectGoalPolicy.movementAmount(it, value) }
    fun updateGoalMovementDate(value: String) = updateProjectGoal { ProjectGoalPolicy.movementDate(it, value) }
    fun selectProjectStatusTab(archived: Boolean) = updateProjectGoal {
        it.copy(presentation = if (archived) ProjectGoalPresentation.ARCHIVED_ONLY else ProjectGoalPresentation.CONTENT)
    }

    fun saveProject() {
        val content = mutableProjectGoal.value as? ProjectGoalLoadState.Content ?: return
        val state = ProjectGoalPolicy.validateProject(content.state)
        val budgetMinor = ProjectGoalPolicy.projectBudgetMinor(state)
        if (state.projectErrors.isNotEmpty() || budgetMinor == null) {
            mutableProjectGoal.value = ProjectGoalLoadState.Content(state)
            return
        }
        if (!beginProjectGoalMutation(state)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val existing = state.project
                val now = runtimeSources.clock.now()
                val request = SaveProjectRequest(
                    planningIds(state, nextId()),
                    existing?.id ?: nextId(),
                    existing?.rowVersion,
                    state.projectDraft.name.trim(),
                    state.projectDraft.description.trim().takeIf(String::isNotEmpty),
                    state.projectDraft.startDate,
                    state.projectDraft.endDate,
                    budgetMinor,
                    state.projectDraft.includedInMonthlyBudget,
                    state.projectDraft.goalId,
                    existing?.status ?: ProjectStatus.ACTIVE,
                    now,
                )
                finishProjectGoalMutation(projectGoalApplicationPort.saveProject(request), "PRJ-001")
            } finally {
                mutableProjectGoalPending.value = false
            }
        }
    }

    fun saveGoal() {
        val content = mutableProjectGoal.value as? ProjectGoalLoadState.Content ?: return
        val state = ProjectGoalPolicy.validateGoal(content.state)
        val target = ProjectGoalPolicy.goalTargetMinor(state)
        val accountId = state.goalDraft.accountId
        if (state.goalErrors.isNotEmpty() || target == null || accountId == null) {
            mutableProjectGoal.value = ProjectGoalLoadState.Content(state)
            return
        }
        if (!beginProjectGoalMutation(state)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val existing = state.goal
                val request = SaveGoalRequest(
                    planningIds(state, nextId()),
                    existing?.id ?: nextId(),
                    existing?.rowVersion,
                    accountId,
                    state.goalDraft.name.trim(),
                    target,
                    state.goalDraft.dueDate,
                    ProjectGoalPolicy.goalSuggestedMinor(state),
                    existing?.status ?: GoalStatus.ACTIVE,
                    runtimeSources.clock.now(),
                )
                finishProjectGoalMutation(projectGoalApplicationPort.saveGoal(request), "GOL-001")
            } finally {
                mutableProjectGoalPending.value = false
            }
        }
    }

    fun saveGoalMovement() {
        val content = mutableProjectGoal.value as? ProjectGoalLoadState.Content ?: return
        val state = content.state
        val goal = state.goal ?: return
        val amount = ProjectGoalPolicy.movementMinor(state) ?: return
        if ("movementDate" in state.goalErrors) return
        if (!beginProjectGoalMutation(state.copy(presentation = ProjectGoalPresentation.SAVING))) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val now = runtimeSources.clock.now()
                val settings = settingsRepository.current()
                val zone = ZoneId.of(settings.zoneId.ifBlank { DEFAULT_ZONE })
                val occurredAt = state.movementDate.atTime(now.atZone(zone).toLocalTime()).atZone(zone).toInstant()
                val ids = goalMovementIds(state)
                val result = projectGoalApplicationPort.recordGoalMovement(
                    RecordGoalMovementRequest(ids, goal.id, goal.rowVersion, state.movementKind, amount, occurredAt, now),
                )
                finishProjectGoalMutation(result.map { Unit }, "GOL-003", goal.id)
            } finally {
                mutableProjectGoalPending.value = false
            }
        }
    }

    fun changeProjectStatus() {
        val content = mutableProjectGoal.value as? ProjectGoalLoadState.Content ?: return
        val state = content.state
        val project = state.project ?: return
        if (!beginProjectGoalMutation(state)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val request = ChangeProjectStatusRequest(
                    planningIds(state, nextId()),
                    project.id,
                    project.rowVersion,
                    if (project.status == ProjectStatus.ACTIVE) ProjectStatus.ARCHIVED else ProjectStatus.ACTIVE,
                    runtimeSources.clock.now(),
                )
                finishProjectGoalMutation(projectGoalApplicationPort.changeProjectStatus(request), "PRJ-003", project.id)
            } finally {
                mutableProjectGoalPending.value = false
            }
        }
    }

    fun completeGoal(strategy: GoalCompletionStrategy) {
        val content = mutableProjectGoal.value as? ProjectGoalLoadState.Content ?: return
        val state = content.state
        val goal = state.goal ?: return
        if (!beginProjectGoalMutation(state)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val request = CompleteGoalRequest(
                    planningIds(state, nextId()),
                    goalMovementIds(state).takeIf { strategy == GoalCompletionStrategy.RELEASE },
                    goal.id,
                    goal.rowVersion,
                    strategy,
                    runtimeSources.clock.now(),
                )
                finishProjectGoalMutation(projectGoalApplicationPort.completeGoal(request), "GOL-003", goal.id)
            } finally {
                mutableProjectGoalPending.value = false
            }
        }
    }

    fun navigateProjectGoal(target: String, stableId: StableId?, movementKind: GoalMovementKind?) {
        val screenId = ScreenId(target)
        val arguments = buildMap<String, SafeRouteArgument> {
            if (stableId != null && target in setOf("PRJ-002", "PRJ-003", "PRJ-004", "PRJ-005", "PRJ-006")) {
                put("projectId", StableIdArgument(stableId))
            }
            if (stableId != null && target in setOf("GOL-002", "GOL-003", "GOL-004", "GOL-005")) {
                put("goalId", StableIdArgument(stableId))
            }
            if (target == "GOL-004" && movementKind != null) {
                put("kind", LedgerRouteContract.enumArgument(screenId, "kind", movementKind.name))
            }
        }
        navigator.navigate(LedgerRouteContract.destination(screenId, arguments), SessionGateState.READY)
    }

    private fun planningIds(state: ProjectGoalFeatureState, entityRevisionId: StableId) = PlanningMutationIds(
        state.snapshot.bookId,
        state.snapshot.localRevision,
        nextId(),
        entityRevisionId,
        nextId(),
    )

    private fun goalMovementIds(state: ProjectGoalFeatureState) = GoalMovementMutationIds(
        state.snapshot.bookId,
        CommandId(nextId()),
        nextId(),
        nextId(),
        nextId(),
        nextId(),
    )

    private fun beginProjectGoalMutation(state: ProjectGoalFeatureState): Boolean {
        if (mutableProjectGoalPending.value) return false
        mutableProjectGoalPending.value = true
        mutableProjectGoal.value = ProjectGoalLoadState.Content(state)
        return true
    }

    private suspend fun finishProjectGoalMutation(
        result: DomainResult<Unit>,
        target: String,
        stableId: StableId? = null,
    ) {
        when (result) {
            is DomainResult.Success -> {
                navigateProjectGoal(target, stableId, null)
            }
            is DomainResult.Failure -> {
                val current = (mutableProjectGoal.value as? ProjectGoalLoadState.Content)?.state ?: return
                mutableProjectGoal.value = ProjectGoalLoadState.Content(
                    current.copy(presentation = ProjectGoalPresentation.VALIDATION_ERROR, failureCode = sanitizeCode(result.error.code)),
                )
            }
        }
    }

    private fun updateProjectGoal(block: (ProjectGoalFeatureState) -> ProjectGoalFeatureState) {
        val current = mutableProjectGoal.value as? ProjectGoalLoadState.Content ?: return
        mutableProjectGoal.value = ProjectGoalLoadState.Content(block(current.state))
    }

    fun loadCredit(
        screenId: String,
        accountId: StableId? = null,
        statementId: StableId? = null,
        transactionId: StableId? = null,
    ) {
        if ((mutableRootState.value as? AppRootState.Session)?.state !is BookSessionState.Ready) return
        currentCreditScreenId = screenId
        currentCreditTransactionId = transactionId
        mutableCredit.value = CreditLoadState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            val appSettings = settingsRepository.current()
            val bookId = runCatching { requireBookId(appSettings) }.getOrNull() ?: return@launch
            mutableCredit.value = when (val result = creditApplicationPort.snapshot(bookId)) {
                is DomainResult.Failure -> CreditLoadState.Failure(sanitizeCode(result.error.code))
                is DomainResult.Success -> {
                    val resolvedAccountId = accountId ?: statementId?.let { target ->
                        result.value.accounts.singleOrNull { account -> account.statements.any { it.id == target } }?.id
                    }
                    val missing = resolvedAccountId != null && result.value.accounts.none { it.id == resolvedAccountId } ||
                        statementId != null && result.value.accounts.none { account -> account.statements.any { it.id == statementId } }
                    if (missing) {
                        CreditLoadState.Failure(CREDIT_NOT_FOUND)
                    } else {
                        val state = CreditPolicy.create(result.value, screenId, resolvedAccountId, statementId)
                        val zone = ZoneId.of(appSettings.zoneId.ifBlank { DEFAULT_ZONE })
                        CreditLoadState.Content(
                            if (state.draft.date.isBlank()) {
                                state.copy(draft = state.draft.copy(date = runtimeSources.clock.now().atZone(zone).toLocalDate().toString()))
                            } else {
                                state
                            },
                        )
                    }
                }
            }
        }
    }

    fun updateCreditField(field: CreditField, value: String) = updateCredit { CreditPolicy.updateDraft(it, field, value) }

    fun selectNextCreditPaymentAccount() = updateCredit { state ->
        val active = state.snapshot.paymentAccounts.filter { it.active }
        if (active.isEmpty()) {
            state
        } else {
            val current = active.indexOfFirst { it.id == state.draft.selectedPaymentAccountId }
            state.copy(draft = state.draft.copy(selectedPaymentAccountId = active[(current + 1).mod(active.size)].id))
        }
    }

    fun selectCreditStatement(statementId: StableId?) = updateCredit { state ->
        state.copy(
            draft = state.draft.copy(selectedStatementId = statementId, allocationMode = CreditAllocationMode.SPECIFIC),
            presentation = CreditPresentation.EDITING,
        )
    }

    fun selectCreditEarliest() = updateCredit { state ->
        state.copy(
            draft = state.draft.copy(selectedStatementId = null, allocationMode = CreditAllocationMode.EARLIEST_UNPAID),
            presentation = CreditPresentation.EDITING,
        )
    }

    fun selectCreditUnallocated() = updateCredit { state ->
        state.copy(
            draft = state.draft.copy(selectedStatementId = null, allocationMode = CreditAllocationMode.UNALLOCATED_ADVANCE),
            presentation = CreditPresentation.UNALLOCATED,
        )
    }

    fun toggleCreditAutoPayment(enabled: Boolean) = updateCredit { state ->
        state.copy(
            draft = state.draft.copy(autoPaymentMode = if (enabled) AutoGenerationMode.FORMAL_TRANSACTION else AutoGenerationMode.CONFIRMATION_CANDIDATE),
            presentation = CreditPolicy.autoPresentation(
                state.account,
                state.statement,
                if (enabled) AutoGenerationMode.FORMAL_TRANSACTION else AutoGenerationMode.CONFIRMATION_CANDIDATE,
            ),
        )
    }

    fun saveCredit() {
        when (currentCreditScreenId) {
            "REC-014" -> saveCreditPayment()
            "CRD-002", "CRD-008" -> saveCreditProfile()
            "CRD-005" -> saveOfficialCreditStatement()
            "CRD-007" -> reallocateCreditPayment()
        }
    }

    fun assignCreditStatement(mode: StatementAssignmentMode) {
        val state = (mutableCredit.value as? CreditLoadState.Content)?.state ?: return
        val transactionId = currentCreditTransactionId ?: return
        val statement = statementForMode(state, mode) ?: return
        if (!beginCreditMutation(state.copy(presentation = if (statement.sealed) CreditPresentation.SEALED_WARNING else CreditPresentation.SAVING))) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val detail = journalApplicationPort.detail(state.snapshot.bookId, transactionId)
                val expected = (detail as? DomainResult.Success)?.value?.transaction?.revisionId
                if (expected == null) {
                    finishCreditFailure("CREDIT_TRANSACTION_NOT_FOUND")
                    return@launch
                }
                val result = creditApplicationPort.assignStatement(
                    AssignCreditStatementRequest(
                        creditTransactionIds(state.snapshot.bookId, transactionId),
                        expected,
                        statement.id,
                        mode,
                        runtimeSources.clock.now(),
                    ),
                )
                finishCreditMutation(result, "CRD-004", statement.id)
            } finally {
                mutableCreditPending.value = false
            }
        }
    }

    fun navigateCredit(target: String, stableId: StableId?) {
        val screenId = ScreenId(target)
        val arguments = buildMap<String, SafeRouteArgument> {
            if (stableId != null && target in setOf("CRD-001", "CRD-002", "CRD-003", "CRD-008")) put("accountId", StableIdArgument(stableId))
            if (stableId != null && target in setOf("CRD-004", "CRD-005")) put("statementId", StableIdArgument(stableId))
            if (stableId != null && target in setOf("CRD-006", "CRD-007")) put("transactionId", StableIdArgument(stableId))
            if (stableId != null && target == "REC-014") put("transactionId", StableIdArgument(stableId))
        }
        navigator.navigate(LedgerRouteContract.destination(screenId, arguments), SessionGateState.READY)
    }

    private fun saveCreditProfile() {
        val state = (mutableCredit.value as? CreditLoadState.Content)?.state ?: return
        val account = state.account ?: return
        val statementDay = state.draft.statementDay.toIntOrNull()
        val dueDay = state.draft.dueDay.toIntOrNull()
        val standard = state.draft.standardLimit.takeIf(String::isNotBlank)?.let { CreditPolicy.parseMinor(it, account.currency) }
        val temporary = state.draft.temporaryLimit.takeIf(String::isNotBlank)?.let { CreditPolicy.parseMinor(it, account.currency) }
        val expires = state.draft.temporaryExpires.takeIf(String::isNotBlank)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val errors = buildSet {
            if (statementDay !in 1..31) add("statementDay")
            if (dueDay !in 1..31) add("dueDay")
            if (runCatching { ZoneId.of(state.draft.zoneId) }.isFailure) add("zone")
            if ((temporary == null) != (expires == null)) add("temporaryLimit")
        }
        if (errors.isNotEmpty()) {
            mutableCredit.value = CreditLoadState.Content(state.copy(presentation = CreditPresentation.VALIDATION_ERROR, validationFields = errors))
            return
        }
        if (!beginCreditMutation(state.copy(presentation = CreditPresentation.SAVING))) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val now = runtimeSources.clock.now()
                val result = creditApplicationPort.saveProfile(
                    SaveCreditProfileRequest(
                        CreditMutationIds(state.snapshot.bookId, CommandId(nextId()), nextId(), nextId()),
                        account.id,
                        account.profile?.lastCommitId,
                        StatementDateRule.DayOfMonth(requireNotNull(statementDay), MissingDayPolicy.MOVE_TO_MONTH_END),
                        DueDateRule.FixedDay(requireNotNull(dueDay), MissingDayPolicy.MOVE_TO_MONTH_END),
                        ZoneId.of(state.draft.zoneId),
                        standard,
                        temporary,
                        expires,
                        state.draft.selectedPaymentAccountId,
                        state.draft.autoPaymentMode,
                        WeekendAdjustment.NEXT_BUSINESS_DAY,
                        now.atZone(ZoneId.of(state.draft.zoneId)).toLocalDate().takeIf { standard != account.profile?.standardLimitMinor },
                        now,
                    ),
                )
                finishCreditMutation(result, "CRD-001", account.id)
            } finally {
                mutableCreditPending.value = false
            }
        }
    }

    private fun saveOfficialCreditStatement() {
        val state = (mutableCredit.value as? CreditLoadState.Content)?.state ?: return
        val account = state.account ?: return
        val statement = state.statement ?: return
        val official = CreditPolicy.parseMinor(state.draft.officialAmount, account.currency)
        if (official == null || official < 0L) {
            mutableCredit.value = CreditLoadState.Content(state.copy(presentation = CreditPresentation.VALIDATION_ERROR, validationFields = setOf("officialAmount")))
            return
        }
        if (!beginCreditMutation(state.copy(presentation = CreditPresentation.SAVING))) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val now = runtimeSources.clock.now()
                val result = creditApplicationPort.saveStatement(
                    SaveCreditStatementRequest(
                        CreditStatementMutationIds(
                            CreditMutationIds(state.snapshot.bookId, CommandId(nextId()), nextId(), nextId()),
                            statement.id,
                            nextId(),
                        ),
                        account.id,
                        statement.revisionId,
                        statement.revisionNumber + 1,
                        statement.cycleStart,
                        statement.cycleEnd,
                        statement.dueDate,
                        statement.estimatedAmountMinor,
                        official,
                        now,
                        true,
                        now,
                    ),
                )
                finishCreditMutation(result, "CRD-004", statement.id)
            } finally {
                mutableCreditPending.value = false
            }
        }
    }

    private fun saveCreditPayment() {
        val content = mutableCredit.value as? CreditLoadState.Content ?: return
        val state = CreditPolicy.validatePayment(content.state)
        val account = state.account ?: return
        val paymentId = state.draft.selectedPaymentAccountId
        val payment = state.snapshot.paymentAccounts.singleOrNull { it.id == paymentId && it.active }
        val amount = CreditPolicy.parseMinor(state.draft.amount, account.currency)
        if (state.presentation in setOf(CreditPresentation.VALIDATION_ERROR, CreditPresentation.OVERPAYMENT_BLOCKED) || payment == null || amount == null) {
            mutableCredit.value = CreditLoadState.Content(state)
            return
        }
        if (payment.currency != account.currency || account.currency != state.snapshot.baseCurrency) {
            finishCreditFailure("CREDIT_FX_EVIDENCE_REQUIRED")
            return
        }
        if (!beginCreditMutation(state.copy(presentation = CreditPresentation.SAVING))) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val now = runtimeSources.clock.now()
                val ids = creditTransactionIds(state.snapshot.bookId, nextId())
                val result = creditApplicationPort.recordPayment(
                    RecordCreditPaymentRequest(
                        ids,
                        CreditPaymentContext(
                            LocalDate.parse(state.draft.date).atTime(now.atZone(ZoneId.of(account.profile?.statementZoneId?.id ?: DEFAULT_ZONE)).toLocalTime()).atZone(ZoneId.of(account.profile?.statementZoneId?.id ?: DEFAULT_ZONE)).toInstant(),
                            ZoneId.of(account.profile?.statementZoneId?.id ?: DEFAULT_ZONE),
                            LocalDate.parse(state.draft.date),
                            state.draft.amount,
                            null,
                            now,
                        ),
                        SpecializedAccountAmountDraft(payment.id, amount, amount, null),
                        SpecializedAccountAmountDraft(account.id, amount, amount, null),
                        state.creditPaymentSelection(),
                    ),
                )
                finishCreditMutation(result, "CRD-001", account.id)
            } finally {
                mutableCreditPending.value = false
            }
        }
    }

    private fun reallocateCreditPayment() {
        val state = (mutableCredit.value as? CreditLoadState.Content)?.state ?: return
        val transactionId = currentCreditTransactionId ?: return
        if (!beginCreditMutation(state.copy(presentation = CreditPresentation.SAVING))) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val detail = journalApplicationPort.detail(state.snapshot.bookId, transactionId)
                val expected = (detail as? DomainResult.Success)?.value?.transaction?.revisionId
                if (expected == null) {
                    finishCreditFailure("CREDIT_TRANSACTION_NOT_FOUND")
                    return@launch
                }
                val result = creditApplicationPort.reallocatePayment(
                    app.ledger.finance.application.ReallocateCreditPaymentRequest(
                        creditTransactionIds(state.snapshot.bookId, transactionId),
                        expected,
                        state.creditPaymentSelection(),
                        runtimeSources.clock.now(),
                    ),
                )
                finishCreditMutation(result, "JRN-007", transactionId)
            } finally {
                mutableCreditPending.value = false
            }
        }
    }

    private fun statementForMode(state: CreditFeatureState, mode: StatementAssignmentMode): app.ledger.finance.application.CreditStatementView? {
        val ordered = state.account?.statements?.sortedBy { it.cycleEnd }.orEmpty()
        if (ordered.isEmpty()) return null
        val selected = ordered.indexOfFirst { it.id == state.selectedStatementId }.takeIf { it >= 0 } ?: ordered.lastIndex
        return ordered.getOrNull(
            when (mode) {
                StatementAssignmentMode.PREVIOUS_CYCLE -> selected - 1
                StatementAssignmentMode.NEXT_CYCLE -> selected + 1
                else -> selected
            },
        )
    }

    private fun CreditFeatureState.creditPaymentSelection(): CreditPaymentSelection = when (draft.allocationMode) {
        CreditAllocationMode.EARLIEST_UNPAID -> CreditPaymentSelection.EarliestUnpaid
        CreditAllocationMode.SPECIFIC -> CreditPaymentSelection.Specific(
            app.ledger.finance.domain.CreditStatementId(requireNotNull(draft.selectedStatementId)),
        )
        CreditAllocationMode.UNALLOCATED_ADVANCE -> CreditPaymentSelection.UnallocatedAdvance
    }

    private fun creditTransactionIds(bookId: StableId, transactionId: StableId) = CreditTransactionMutationIds(
        bookId,
        CommandId(nextId()),
        transactionId,
        nextId(),
        nextId(),
        nextId(),
        List(CREDIT_FACT_ID_COUNT) { nextId() },
        emptyList(),
    )

    private fun beginCreditMutation(state: CreditFeatureState): Boolean {
        if (mutableCreditPending.value) return false
        mutableCreditPending.value = true
        mutableCredit.value = CreditLoadState.Content(state)
        return true
    }

    private suspend fun finishCreditMutation(result: DomainResult<app.ledger.finance.domain.CommandReceipt>, target: String, stableId: StableId?) {
        when (result) {
            is DomainResult.Success -> navigateCredit(target, stableId)
            is DomainResult.Failure -> finishCreditFailure(sanitizeCode(result.error.code))
        }
    }

    private fun finishCreditFailure(code: String) {
        val state = (mutableCredit.value as? CreditLoadState.Content)?.state ?: return
        mutableCredit.value = CreditLoadState.Content(state.copy(presentation = CreditPresentation.VALIDATION_ERROR, failureCode = code))
    }

    private fun updateCredit(block: (CreditFeatureState) -> CreditFeatureState) {
        val current = mutableCredit.value as? CreditLoadState.Content ?: return
        mutableCredit.value = CreditLoadState.Content(block(current.state))
    }

    fun loadInstallment(screenId: String, planId: StableId? = null, purchaseId: StableId? = null) {
        if ((mutableRootState.value as? AppRootState.Session)?.state !is BookSessionState.Ready) return
        currentInstallmentScreenId = screenId
        mutableInstallment.value = InstallmentLoadState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            val saved = settingsRepository.current()
            val bookId = runCatching { requireBookId(saved) }.getOrNull() ?: return@launch
            val zone = ZoneId.of(saved.zoneId.ifBlank { DEFAULT_ZONE })
            val today = runtimeSources.clock.now().atZone(zone).toLocalDate()
            mutableInstallment.value = when (val result = installmentApplicationPort.snapshot(bookId, today)) {
                is DomainResult.Failure -> InstallmentLoadState.Failure(sanitizeCode(result.error.code))
                is DomainResult.Success -> {
                    val missing = planId != null && result.value.plans.none { it.id == planId } ||
                        purchaseId != null && result.value.purchases.none { it.transactionId == purchaseId }
                    if (missing) {
                        InstallmentLoadState.Failure(INSTALLMENT_NOT_FOUND)
                    } else {
                        InstallmentLoadState.Content(InstallmentPolicy.create(result.value, screenId, planId, purchaseId))
                    }
                }
            }
        }
    }

    fun updateInstallmentField(field: InstallmentField, value: String) = updateInstallment {
        InstallmentPolicy.update(it, field, value)
    }

    fun updateInstallmentFeeModel(model: InstallmentFeeRateType) = updateInstallment { state ->
        state.copy(
            draft = state.draft.copy(feeModel = model),
            presentation = InstallmentPresentation.EDITING,
            previewSchedule = null,
            validationFields = emptySet(),
        )
    }

    fun updateInstallmentRefundPolicy(policy: InstallmentRefundPolicy) = updateInstallment { state ->
        state.copy(
            draft = state.draft.copy(refundPolicy = policy),
            presentation = InstallmentPresentation.EDITING,
            previewSchedule = null,
        )
    }

    fun selectInstallmentPurchase(purchaseId: StableId) = updateInstallment { state ->
        state.copy(
            selectedPurchaseId = purchaseId,
            selectedPlanId = null,
            presentation = InstallmentPresentation.EDITING,
            previewSchedule = null,
        )
    }

    fun navigateInstallment(target: String, stableId: StableId?) {
        val screenId = ScreenId(target)
        val arguments = buildMap<String, SafeRouteArgument> {
            if (stableId != null && target == "REC-027") put("purchaseTransactionId", StableIdArgument(stableId))
            if (stableId != null && target in setOf("INS-002", "INS-003", "INS-004", "INS-005", "INS-006")) {
                put("planId", StableIdArgument(stableId))
            }
        }
        navigator.navigate(LedgerRouteContract.destination(screenId, arguments), SessionGateState.READY)
    }

    fun previewInstallment() {
        val state = (mutableInstallment.value as? InstallmentLoadState.Content)?.state ?: return
        val request = installmentPlanRequest(state)
        if (request == null) {
            markInstallmentInvalid()
            return
        }
        if (!beginInstallmentMutation(state.copy(presentation = InstallmentPresentation.SAVING))) return
        viewModelScope.launch(Dispatchers.Default) {
            try {
                when (val result = installmentApplicationPort.preview(request)) {
                    is DomainResult.Success -> mutableInstallment.value = InstallmentLoadState.Content(
                        state.copy(presentation = InstallmentPresentation.PREVIEW, previewSchedule = result.value),
                    )
                    is DomainResult.Failure -> markInstallmentInvalid(sanitizeCode(result.error.code))
                }
            } finally {
                mutableInstallmentPending.value = false
            }
        }
    }

    fun saveInstallment() {
        val state = (mutableInstallment.value as? InstallmentLoadState.Content)?.state ?: return
        if (state.presentation != InstallmentPresentation.PREVIEW || state.previewSchedule == null) {
            markInstallmentInvalid()
            return
        }
        val request = installmentPlanRequest(state)
        if (request == null || !beginInstallmentMutation(state.copy(presentation = InstallmentPresentation.SAVING))) {
            if (request == null) markInstallmentInvalid()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val result = installmentApplicationPort.save(request)) {
                    is DomainResult.Success -> navigateInstallment("INS-003", request.ids.planId)
                    is DomainResult.Failure -> markInstallmentInvalid(sanitizeCode(result.error.code))
                }
            } finally {
                mutableInstallmentPending.value = false
            }
        }
    }

    fun calculateInstallmentSettlement() {
        val state = (mutableInstallment.value as? InstallmentLoadState.Content)?.state ?: return
        val plan = state.plan ?: return
        val date = runCatching { LocalDate.parse(state.draft.settlementDate) }.getOrNull()
        if (date == null) {
            markInstallmentInvalid()
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            when (val result = installmentApplicationPort.simulateSettlement(state.snapshot.bookId, plan.id, date)) {
                is DomainResult.Success -> mutableInstallment.value = InstallmentLoadState.Content(
                    state.copy(
                        presentation = if (result.value.allowed) InstallmentPresentation.CALCULATED else InstallmentPresentation.INVALID,
                        simulation = result.value,
                    ),
                )
                is DomainResult.Failure -> markInstallmentInvalid(sanitizeCode(result.error.code))
            }
        }
    }

    fun applyInstallmentSettlement() {
        val state = (mutableInstallment.value as? InstallmentLoadState.Content)?.state ?: return
        val plan = state.plan ?: return
        val simulation = state.simulation?.takeIf { it.allowed } ?: return
        val payment = state.snapshot.paymentAccounts.firstOrNull { it.active && it.currency == plan.currency }
        if (payment == null || plan.currency != state.snapshot.baseCurrency) {
            markInstallmentInvalid(INSTALLMENT_PAYMENT_ACCOUNT_REQUIRED)
            return
        }
        if (!beginInstallmentMutation(state.copy(presentation = InstallmentPresentation.SAVING))) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val now = runtimeSources.clock.now()
                val mutation = installmentMutationIds(state.snapshot.bookId, plan.id, 0)
                val request = ApplyInstallmentSettlementRequest(
                    InstallmentSettlementIds(
                        mutation,
                        nextId(),
                        nextId(),
                        List(INSTALLMENT_FACT_ID_COUNT) { nextId() },
                        emptyList(),
                    ),
                    plan.currentRevision.id.value,
                    plan.currentRevision.revisionNumber + 1,
                    plan.currentSchedule.revisionNumber + 1,
                    simulation.settlementDate,
                    ZoneId.of(settingsRepository.current().zoneId.ifBlank { DEFAULT_ZONE }),
                    SpecializedAccountAmountDraft(payment.id, simulation.paymentMinor, simulation.paymentMinor, null),
                    SpecializedAccountAmountDraft(plan.creditAccountId, simulation.outstandingPrincipalMinor, simulation.outstandingPrincipalMinor, null),
                    simulation.settlementFeeMinor.takeIf { it > 0L }?.let {
                        SpecializedAccountAmountDraft(payment.id, it, it, null)
                    },
                    now,
                )
                when (val result = installmentApplicationPort.applySettlement(request)) {
                    is DomainResult.Success -> navigateInstallment("INS-003", plan.id)
                    is DomainResult.Failure -> markInstallmentInvalid(sanitizeCode(result.error.code))
                }
            } finally {
                mutableInstallmentPending.value = false
            }
        }
    }

    private fun installmentPlanRequest(state: InstallmentFeatureState): SaveInstallmentPlanRequest? = runCatching {
        val plan = state.plan
        val purchase = state.snapshot.purchases.singleOrNull { it.transactionId == state.selectedPurchaseId }
            ?: plan?.let { selected -> state.snapshot.purchases.singleOrNull { it.transactionId == selected.purchaseTransactionId } }
            ?: return null
        val termCount = state.draft.termCount.toInt().takeIf { it in 1..INSTALLMENT_MAX_TERMS } ?: return null
        val firstStatementDate = LocalDate.parse(state.draft.firstStatementDate)
        val prepaymentFee = state.draft.prepaymentFee.takeIf(String::isNotBlank)?.let {
            CreditPolicy.parseMinor(it, purchase.currency)
        }
        if (state.draft.prepaymentFee.isNotBlank() && prepaymentFee == null) return null
        val terms = installmentTerms(state, purchase.currency, prepaymentFee) ?: return null
        val planId = plan?.id ?: nextId()
        val currentPrincipal = plan?.currentPrincipalMinor ?: purchase.principalMinor
        SaveInstallmentPlanRequest(
            installmentMutationIds(state.snapshot.bookId, planId, termCount),
            purchase.transactionId,
            purchase.creditAccountId,
            purchase.currency,
            plan?.originalPrincipalMinor ?: purchase.principalMinor,
            currentPrincipal,
            termCount,
            plan?.currentRevision?.id?.value,
            (plan?.currentRevision?.revisionNumber ?: 0) + 1,
            (plan?.currentSchedule?.revisionNumber ?: 0) + 1,
            firstStatementDate,
            terms,
            if (plan == null) ScheduleRevisionReason.INITIAL else ScheduleRevisionReason.RATE_CHANGE,
            runtimeSources.clock.now(),
        )
    }.getOrNull()

    private fun installmentTerms(
        state: InstallmentFeatureState,
        currency: CurrencyCode,
        prepaymentFee: Long?,
    ): InstallmentTermsDraft? {
        val fixedPerTerm = if (state.draft.feeModel == InstallmentFeeRateType.FIXED_PER_TERM) {
            CreditPolicy.parseMinor(state.draft.feeValue, currency)
        } else {
            null
        }
        val firstTerm = if (state.draft.feeModel == InstallmentFeeRateType.FIRST_TERM_FIXED) {
            CreditPolicy.parseMinor(state.draft.firstTermFee, currency)
        } else {
            null
        }
        val remainingRate = if (state.draft.feeModel == InstallmentFeeRateType.REMAINING_PRINCIPAL_RATE) {
            parseInstallmentRate(state.draft.feeValue)
        } else {
            null
        }
        val annualRate = if (state.draft.feeModel == InstallmentFeeRateType.EFFECTIVE_ANNUAL_RATE) {
            parseInstallmentRate(state.draft.annualRate)
        } else {
            null
        }
        val requiredValueMissing = when (state.draft.feeModel) {
            InstallmentFeeRateType.FIXED_PER_TERM -> fixedPerTerm == null
            InstallmentFeeRateType.FIRST_TERM_FIXED -> firstTerm == null
            InstallmentFeeRateType.REMAINING_PRINCIPAL_RATE -> remainingRate == null
            InstallmentFeeRateType.EFFECTIVE_ANNUAL_RATE -> annualRate == null
            InstallmentFeeRateType.NONE -> false
        }
        if (requiredValueMissing) return null
        return InstallmentTermsDraft(
            state.draft.feeModel,
            fixedPerTerm,
            firstTerm,
            remainingRate,
            annualRate,
            if (prepaymentFee == null) InstallmentPrepaymentPolicy.ALLOWED_WITHOUT_FEE else InstallmentPrepaymentPolicy.ALLOWED_WITH_FEE,
            prepaymentFee,
            state.draft.refundPolicy,
            RoundingMode.HALF_EVEN,
        )
    }

    private fun parseInstallmentRate(raw: String): InterestRate? = runCatching {
        val entered = BigDecimal(raw.trim().removeSuffix("%"))
        val decimal = if (raw.contains('%') || entered > BigDecimal.ONE) entered.movePointLeft(2) else entered
        InterestRate.of(decimal).getOrNull()
    }.getOrNull()

    private fun installmentMutationIds(bookId: StableId, planId: StableId, termCount: Int) = InstallmentMutationIds(
        bookId,
        CommandId(nextId()),
        nextId(),
        nextId(),
        planId,
        nextId(),
        nextId(),
        List(termCount) { nextId() },
    )

    private fun beginInstallmentMutation(state: InstallmentFeatureState): Boolean {
        if (mutableInstallmentPending.value) return false
        mutableInstallmentPending.value = true
        mutableInstallment.value = InstallmentLoadState.Content(state)
        return true
    }

    private fun markInstallmentInvalid(code: String? = null) {
        val state = (mutableInstallment.value as? InstallmentLoadState.Content)?.state ?: return
        mutableInstallment.value = InstallmentLoadState.Content(
            state.copy(
                presentation = InstallmentPresentation.INVALID,
                validationFields = setOfNotNull(code ?: "installment"),
            ),
        )
    }

    private fun updateInstallment(block: (InstallmentFeatureState) -> InstallmentFeatureState) {
        val current = mutableInstallment.value as? InstallmentLoadState.Content ?: return
        mutableInstallment.value = InstallmentLoadState.Content(block(current.state))
    }

    fun loadLoan(
        screenId: String,
        contractId: StableId? = null,
        trancheId: StableId? = null,
        transactionId: StableId? = null,
        simulationId: StableId? = null,
    ) {
        if ((mutableRootState.value as? AppRootState.Session)?.state !is BookSessionState.Ready) return
        currentLoanScreenId = screenId
        mutableLoan.value = LoanLoadState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            val bookId = runCatching { requireBookId(settingsRepository.current()) }.getOrNull() ?: return@launch
            mutableLoan.value = when (val result = loanApplicationPort.snapshot(bookId)) {
                is DomainResult.Failure -> LoanLoadState.Failure(sanitizeCode(result.error.code))
                is DomainResult.Success -> {
                    val missing = contractId != null && result.value.contracts.none { it.id == contractId } ||
                        trancheId != null && result.value.contracts.none { contract -> contract.tranches.any { it.id == trancheId } }
                    if (missing) {
                        LoanLoadState.Failure(LOAN_NOT_FOUND)
                    } else {
                        val created = LoanPolicy.create(result.value, screenId, contractId, trancheId, transactionId, simulationId)
                        val retained = currentLoanSimulationRequest?.takeIf { request ->
                            request.contractId == created.selectedContractId && request.simulationId == simulationId
                        }
                        LoanLoadState.Content(
                            if (screenId == "LOA-011" && retained != null) {
                                created.copy(simulation = currentLoanSimulation)
                            } else {
                                created
                            },
                        )
                    }
                }
            }
        }
    }

    fun updateLoanField(field: LoanField, value: String) = updateLoan { LoanPolicy.update(it, field, value) }

    fun selectLoanContract(contractId: StableId) = updateLoan { state ->
        LoanPolicy.create(state.snapshot, currentLoanScreenId, contractId, null, state.selectedTransactionId, state.selectedSimulationId)
    }

    fun selectLoanTranche(trancheId: StableId) = updateLoan { state ->
        LoanPolicy.create(state.snapshot, currentLoanScreenId, state.selectedContractId, trancheId, state.selectedTransactionId, state.selectedSimulationId)
    }

    fun selectLoanRepaymentMethod(method: LoanRepaymentMethod) = updateLoan { state ->
        state.copy(draft = state.draft.copy(repaymentMethod = method), presentation = LoanPresentation.EDITING, preview = emptyList())
    }

    fun selectLoanStrategy(strategy: PrepaymentRecalculationStrategy) = updateLoan { state ->
        state.copy(draft = state.draft.copy(strategy = strategy), presentation = LoanPresentation.EDITING, simulation = null)
    }

    fun navigateLoan(target: String, primary: StableId?, secondary: StableId?) {
        val screenId = ScreenId(target)
        val arguments = buildMap<String, SafeRouteArgument> {
            when (target) {
                "REC-018", "REC-019", "LOA-002", "LOA-006", "LOA-007", "LOA-008", "LOA-010" ->
                    primary?.let { put("contractId", StableIdArgument(it)) }
                "LOA-003" -> {
                    primary?.let { put("contractId", StableIdArgument(it)) }
                    secondary?.let { put("trancheId", StableIdArgument(it)) }
                }
                "LOA-004", "LOA-005" -> {
                    primary?.let { put("contractId", StableIdArgument(it)) }
                    requireNotNull(secondary).let { put("trancheId", StableIdArgument(it)) }
                }
                "LOA-009" -> primary?.let { put("transactionId", StableIdArgument(it)) }
                "LOA-011" -> {
                    primary?.let { put("contractId", StableIdArgument(it)) }
                    currentLoanSimulationRequest?.simulationId?.let { put("simulationId", StableIdArgument(it)) }
                }
            }
        }
        navigator.navigate(LedgerRouteContract.destination(screenId, arguments), SessionGateState.READY)
    }

    fun previewLoan() {
        val state = (mutableLoan.value as? LoanLoadState.Content)?.state ?: return
        val request = loanContractRequest(state)
        if (request == null || !beginLoanMutation(state.copy(presentation = LoanPresentation.GENERATING_SCHEDULE))) {
            if (request == null) markLoanInvalid(LoanPresentation.INVALID)
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                mutableLoan.value = when (val result = loanApplicationPort.preview(request)) {
                    is DomainResult.Success -> LoanLoadState.Content(state.copy(presentation = LoanPresentation.READY, preview = result.value))
                    is DomainResult.Failure -> LoanLoadState.Content(state.copy(presentation = LoanPresentation.CALCULATION_ERROR, validationFields = setOf(sanitizeCode(result.error.code))))
                }
            } finally {
                mutableLoanPending.value = false
            }
        }
    }

    fun saveLoan() {
        when (currentLoanScreenId) {
            "REC-018" -> recordLoanDisbursement()
            "REC-019" -> recordLoanPayment()
            else -> saveLoanContract()
        }
    }

    private fun saveLoanContract() {
        val state = (mutableLoan.value as? LoanLoadState.Content)?.state ?: return
        val request = loanContractRequest(state)
        if (request == null || !beginLoanMutation(state.copy(presentation = LoanPresentation.SAVING))) {
            if (request == null) markLoanInvalid(LoanPresentation.INVALID)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val result = loanApplicationPort.saveContract(request)) {
                    is DomainResult.Success -> navigateLoan("LOA-007", request.ids.contractId, null)
                    is DomainResult.Failure -> markLoanFailure(result.error.code)
                }
            } finally {
                mutableLoanPending.value = false
            }
        }
    }

    private fun recordLoanDisbursement() {
        val state = (mutableLoan.value as? LoanLoadState.Content)?.state ?: return
        val contract = state.contract ?: return markLoanInvalid(LoanPresentation.INVALID)
        val tranche = state.tranche ?: return markLoanInvalid(LoanPresentation.ALLOCATION_ERROR)
        val amount = CreditPolicy.parseMinor(state.draft.amount, contract.currency)
        val account = state.snapshot.paymentAccounts.firstOrNull { it.active && it.currency == contract.currency }
        if (amount == null || account == null || contract.currency != state.snapshot.baseCurrency) {
            markLoanInvalid(LoanPresentation.ALLOCATION_ERROR)
            return
        }
        val ids = loanExistingMutationIds(state, contract.id, tranche.id)
        val request = RecordLoanDisbursementRequest(
            ids,
            loanTransactionIds(),
            loanTransactionContext(),
            SpecializedAccountAmountDraft(account.id, amount, amount, null),
            LoanComponentAmountDraft(amount, amount, null),
            listOf(LoanComponentAllocationDraft(tranche.id, null, LoanPaymentComponent.PRINCIPAL, amount, amount)),
        )
        if (!beginLoanMutation(state.copy(presentation = LoanPresentation.SAVING))) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val result = loanApplicationPort.recordDisbursement(request)) {
                    is DomainResult.Success -> navigateLoan("LOA-007", contract.id, null)
                    is DomainResult.Failure -> markLoanFailure(result.error.code)
                }
            } finally {
                mutableLoanPending.value = false
            }
        }
    }

    private fun recordLoanPayment() {
        val state = (mutableLoan.value as? LoanLoadState.Content)?.state ?: return
        val contract = state.contract ?: return markLoanInvalid(LoanPresentation.INVALID)
        val tranche = state.tranche ?: return markLoanInvalid(LoanPresentation.INVALID)
        val components = loanComponents(state, contract.currency) ?: return markLoanInvalid(LoanPresentation.SUM_MISMATCH)
        val total = runCatching { components.filterNotNull().fold(0L, Math::addExact) }.getOrNull()
        val enteredTotal = CreditPolicy.parseMinor(state.draft.amount, contract.currency)
        if (total == null || enteredTotal != total) return markLoanInvalid(LoanPresentation.SUM_MISMATCH)
        val principal = components[0] ?: 0L
        if (principal > tranche.remainingPrincipalMinor) return markLoanInvalid(LoanPresentation.PRINCIPAL_EXCEEDED)
        val account = state.snapshot.paymentAccounts.firstOrNull { it.active && it.currency == contract.currency }
        if (account == null || contract.currency != state.snapshot.baseCurrency) return markLoanInvalid(LoanPresentation.INVALID)
        val mutation = loanContractRequest(state, selectedOnly = true, principalAfter = tranche.remainingPrincipalMinor - principal)
            ?: return markLoanInvalid(LoanPresentation.INVALID)
        val amounts = components.map { minor -> minor?.let { LoanComponentAmountDraft(it, it, null) } }
        val allocation = LoanPaymentComponent.entries.mapIndexedNotNull { index, component ->
            amounts[index]?.let { LoanComponentAllocationDraft(tranche.id, null, component, it.accountMinor, it.baseMinor) }
        }
        val request = RecordLoanPaymentRequest(
            mutation,
            loanTransactionIds(),
            loanTransactionContext(),
            SpecializedAccountAmountDraft(account.id, total, total, null),
            LoanPaymentAmountsDraft(amounts[0], amounts[1], amounts[2], amounts[3]),
            allocation,
        )
        if (!beginLoanMutation(state.copy(presentation = LoanPresentation.SAVING))) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val result = loanApplicationPort.recordPayment(request)) {
                    is DomainResult.Success -> navigateLoan("LOA-007", contract.id, null)
                    is DomainResult.Failure -> markLoanFailure(result.error.code)
                }
            } finally {
                mutableLoanPending.value = false
            }
        }
    }

    fun simulateLoan() {
        val state = (mutableLoan.value as? LoanLoadState.Content)?.state ?: return
        val contract = state.contract ?: return markLoanInvalid(LoanPresentation.INVALID)
        val tranche = state.tranche ?: return markLoanInvalid(LoanPresentation.INVALID)
        val amount = CreditPolicy.parseMinor(state.draft.principalComponent, contract.currency)
            ?.takeIf { it in 1..tranche.remainingPrincipalMinor }
            ?: return markLoanInvalid(LoanPresentation.INVALID)
        val remaining = tranche.remainingPrincipalMinor - amount
        val itemCount = if (remaining == 0L) 0 else tranche.schedule.count { it.remainingPrincipalMinor < remaining }.coerceAtLeast(1)
        val ids = loanTrancheMutationIds(itemCount)
        val terms = loanTerms(state, tranche, itemCount.coerceAtLeast(1)) ?: return markLoanInvalid(LoanPresentation.INVALID)
        val now = runtimeSources.clock.now()
        val zone = ZoneId.of(settings.value.zoneId.ifBlank { DEFAULT_ZONE })
        val request = LoanSimulationRequest(
            nextId(),
            state.snapshot.bookId,
            contract.id,
            tranche.id,
            LoanSimulationScenario.PartialPrepayment(amount, state.draft.strategy, now.atZone(zone).toLocalDate()),
            ids,
            terms,
            now,
        )
        if (!beginLoanMutation(state.copy(presentation = LoanPresentation.CALCULATING))) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val result = loanApplicationPort.simulate(request)) {
                    is DomainResult.Success -> {
                        currentLoanSimulationRequest = request
                        currentLoanSimulation = result.value
                        mutableLoan.value = LoanLoadState.Content(state.copy(presentation = LoanPresentation.RESULT, simulation = result.value, selectedSimulationId = request.simulationId))
                    }
                    is DomainResult.Failure -> markLoanFailure(result.error.code)
                }
            } finally {
                mutableLoanPending.value = false
            }
        }
    }

    fun applyLoanSimulation() {
        val state = (mutableLoan.value as? LoanLoadState.Content)?.state ?: return
        val simulation = state.simulation ?: return markLoanInvalid(LoanPresentation.CONFLICT)
        val request = currentLoanSimulationRequest ?: return markLoanInvalid(LoanPresentation.CONFLICT)
        val contract = state.contract ?: return markLoanInvalid(LoanPresentation.CONFLICT)
        val tranche = state.tranche ?: return markLoanInvalid(LoanPresentation.CONFLICT)
        val account = state.snapshot.paymentAccounts.firstOrNull { it.active && it.currency == contract.currency }
        if (account == null || contract.currency != state.snapshot.baseCurrency || state.draft.confirmPhrase.isBlank()) {
            return markLoanInvalid(LoanPresentation.INVALID)
        }
        val mutation = loanContractRequest(
            state,
            selectedOnly = true,
            principalAfter = simulation.remainingPrincipalBeforeMinor - simulation.prepaymentPrincipalMinor,
            fixedIds = request.replacementIds,
        ) ?: return markLoanInvalid(LoanPresentation.CONFLICT)
        val penalty = simulation.penaltyMinor.takeIf { it > 0L }?.let { LoanComponentAmountDraft(it, it, null) }
        val apply = ApplyLoanSimulationRequest(
            request,
            mutation,
            loanTransactionIds(),
            loanTransactionContext(),
            SpecializedAccountAmountDraft(account.id, simulation.paymentNowMinor, simulation.paymentNowMinor, null),
            LoanComponentAmountDraft(simulation.prepaymentPrincipalMinor, simulation.prepaymentPrincipalMinor, null),
            penalty,
        )
        if (!beginLoanMutation(state.copy(presentation = LoanPresentation.SAVING))) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val result = loanApplicationPort.applySimulation(apply)) {
                    is DomainResult.Success -> navigateLoan("LOA-007", contract.id, null)
                    is DomainResult.Failure -> markLoanFailure(result.error.code)
                }
            } finally {
                mutableLoanPending.value = false
            }
        }
    }

    private fun loanContractRequest(
        state: LoanFeatureState,
        selectedOnly: Boolean = false,
        principalAfter: Long? = null,
        fixedIds: LoanTrancheMutationIds? = null,
    ): SaveLoanContractRequest? = runCatching {
        val contract = state.contract
        val account = contract?.let { selected -> state.snapshot.loanAccounts.singleOrNull { it.id == selected.displayAccountId } }
            ?: state.snapshot.loanAccounts.firstOrNull { it.active }
            ?: return null
        val contractId = contract?.id ?: nextId()
        val views = if (contract == null) {
            emptyList()
        } else if (selectedOnly) {
            listOfNotNull(state.tranche)
        } else {
            contract.tranches
        }
        val descriptors = if (views.isEmpty()) listOf(null) else views
        val trancheRequests = descriptors.map { view ->
            val selected = view == null || view.id == state.selectedTrancheId
            val currentPrincipal = when {
                selected && principalAfter != null -> principalAfter
                view != null -> view.remainingPrincipalMinor
                else -> CreditPolicy.parseMinor(state.draft.principal, account.currency) ?: return null
            }
            val requestedCount = if (currentPrincipal == 0L) {
                0
            } else if (selected) {
                state.draft.paymentCount.toIntOrNull()?.takeIf { it in 1..LOAN_MAX_PAYMENTS } ?: view?.schedule?.size ?: return null
            } else {
                view.schedule.size.coerceAtLeast(1)
            }
            val ids = if (selected && fixedIds != null) fixedIds else loanTrancheMutationIds(requestedCount)
            val terms = loanTerms(state, view, requestedCount.coerceAtLeast(1)) ?: return null
            LoanTrancheDraft(
                ids,
                view?.ledgerAccountId ?: account.ledgerAccountId,
                view?.name ?: state.draft.name.ifBlank { account.name },
                view?.originalPrincipalMinor ?: currentPrincipal,
                currentPrincipal,
                if (currentPrincipal == 0L) LoanStatus.PAID_OFF else view?.status ?: LoanStatus.ACTIVE,
                view?.currentTermsRevisionId,
                (view?.termsRevisionNumber ?: 0) + 1,
                (view?.scheduleRevisionNumber ?: 0) + 1,
                if (view == null) ScheduleRevisionReason.INITIAL else ScheduleRevisionReason.PREPAYMENT,
                terms,
            )
        }
        val ids = LoanMutationIds(state.snapshot.bookId, CommandId(nextId()), nextId(), nextId(), contractId, trancheRequests.map { it.ids })
        SaveLoanContractRequest(
            ids,
            contract?.displayAccountId ?: account.id,
            state.draft.name.ifBlank { contract?.name ?: account.name },
            state.draft.lender.ifBlank { null },
            contract?.currency ?: account.currency,
            state.draft.startDate.takeIf(String::isNotBlank)?.let(LocalDate::parse) ?: contract?.disbursementDate ?: loanToday(),
            if (trancheRequests.all { it.status == LoanStatus.PAID_OFF }) LoanStatus.PAID_OFF else contract?.status ?: LoanStatus.ACTIVE,
            contract?.lastCommitId,
            trancheRequests,
            runtimeSources.clock.now(),
        )
    }.getOrNull()

    private fun loanTerms(
        state: LoanFeatureState,
        view: app.ledger.finance.application.LoanTrancheView?,
        paymentCount: Int,
    ): LoanTermsDraft? {
        val start = state.draft.startDate.takeIf(String::isNotBlank)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: view?.ratePeriods?.firstOrNull()?.effectiveFrom ?: loanToday()
        val first = state.draft.firstPaymentDate.takeIf(String::isNotBlank)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: view?.schedule?.firstOrNull()?.plannedDate ?: start.plusMonths(1)
        val end = state.draft.endDate.takeIf(String::isNotBlank)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: view?.schedule?.lastOrNull()?.plannedDate ?: first.plusMonths((paymentCount - 1).toLong())
        val rate = state.draft.annualRate.takeIf(String::isNotBlank)?.let(::parseLoanRate)
            ?: view?.ratePeriods?.firstOrNull()?.annualRate ?: return null
        val periods = if (view != null && state.draft.annualRate.isBlank()) view.ratePeriods else listOf(LoanRatePeriod(start, end, rate, null, null))
        val fee = CreditPolicy.parseMinor(state.draft.feePerPayment, state.snapshot.baseCurrency) ?: 0L
        return LoanTermsDraft(
            state.draft.repaymentMethod,
            LoanRateType.FIXED,
            PaymentFrequency.MONTHLY,
            start,
            end,
            paymentCount,
            first,
            RoundingMode.HALF_EVEN,
            LoanPrepaymentPolicy.ALLOWED,
            state.draft.strategy,
            null,
            periods,
            fee,
        )
    }

    private fun loanExistingMutationIds(state: LoanFeatureState, contractId: StableId, trancheId: StableId): LoanMutationIds {
        val tranche = state.snapshot.contracts.single { it.id == contractId }.tranches.single { it.id == trancheId }
        return LoanMutationIds(
            state.snapshot.bookId,
            CommandId(nextId()),
            nextId(),
            nextId(),
            contractId,
            listOf(LoanTrancheMutationIds(tranche.id, tranche.currentTermsRevisionId, tranche.currentScheduleRevisionId, tranche.schedule.map { nextId() })),
        )
    }

    private fun loanTrancheMutationIds(itemCount: Int) = LoanTrancheMutationIds(nextId(), nextId(), nextId(), List(itemCount) { nextId() })

    private fun loanTransactionIds() = LoanTransactionIds(nextId(), nextId(), List(LOAN_FACT_ID_COUNT) { nextId() }, emptyList())

    private fun loanTransactionContext(): LoanTransactionContext {
        val now = runtimeSources.clock.now()
        val zone = ZoneId.of(settings.value.zoneId.ifBlank { DEFAULT_ZONE })
        return LoanTransactionContext(now, zone, now.atZone(zone).toLocalDate(), null, null)
    }

    private fun loanComponents(state: LoanFeatureState, currency: CurrencyCode): List<Long?>? {
        val raw = listOf(state.draft.principalComponent, state.draft.interestComponent, state.draft.feeComponent, state.draft.penaltyComponent)
        val parsed = raw.map { value -> if (value.isBlank()) null else CreditPolicy.parseMinor(value, currency) }
        return parsed.takeIf { values -> values.any { it != null } && values.zip(raw).all { (value, source) -> source.isBlank() || value != null } }
    }

    private fun parseLoanRate(raw: String): InterestRate? = runCatching {
        val entered = BigDecimal(raw.trim().removeSuffix("%"))
        InterestRate.of(if (raw.contains('%') || entered > BigDecimal.ONE) entered.movePointLeft(2) else entered).getOrNull()
    }.getOrNull()

    private fun loanToday(): LocalDate {
        val zone = ZoneId.of(settings.value.zoneId.ifBlank { DEFAULT_ZONE })
        return runtimeSources.clock.now().atZone(zone).toLocalDate()
    }

    private fun beginLoanMutation(state: LoanFeatureState): Boolean {
        if (mutableLoanPending.value) return false
        mutableLoanPending.value = true
        mutableLoan.value = LoanLoadState.Content(state)
        return true
    }

    private fun markLoanInvalid(presentation: LoanPresentation) {
        val state = (mutableLoan.value as? LoanLoadState.Content)?.state ?: return
        mutableLoan.value = LoanLoadState.Content(state.copy(presentation = presentation, validationFields = setOf("loan")))
    }

    private fun markLoanFailure(code: String) {
        val presentation = if (sanitizeCode(code).contains("STALE")) LoanPresentation.CONFLICT else LoanPresentation.INVALID
        val state = (mutableLoan.value as? LoanLoadState.Content)?.state ?: return
        mutableLoan.value = LoanLoadState.Content(state.copy(presentation = presentation, validationFields = setOf(sanitizeCode(code))))
    }

    private fun updateLoan(block: (LoanFeatureState) -> LoanFeatureState) {
        val current = mutableLoan.value as? LoanLoadState.Content ?: return
        mutableLoan.value = LoanLoadState.Content(block(current.state))
    }

    fun loadSettlement(screenId: String, activityId: StableId? = null, participantId: StableId? = null) {
        if ((mutableRootState.value as? AppRootState.Session)?.state !is BookSessionState.Ready) return
        currentSettlementScreenId = screenId
        mutableSettlement.value = SettlementLoadState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            val bookId = runCatching { requireBookId(settingsRepository.current()) }.getOrNull() ?: return@launch
            mutableSettlement.value = when (val result = settlementApplicationPort.snapshot(bookId)) {
                is DomainResult.Failure -> SettlementLoadState.Failure(sanitizeCode(result.error.code))
                is DomainResult.Success -> {
                    val activity = activityId?.let { id -> result.value.activities.singleOrNull { it.id == id } }
                    val missingActivity = activityId != null && activity == null
                    val missingParticipant = participantId != null && activity?.participants?.none { it.id == participantId } != false
                    if (missingActivity || missingParticipant) {
                        SettlementLoadState.Failure("SETTLEMENT_NOT_FOUND")
                    } else {
                        val created = SettlementPolicy.create(result.value, screenId, activityId, participantId)
                        val zone = ZoneId.of(settings.value.zoneId.ifBlank { DEFAULT_ZONE })
                        SettlementLoadState.Content(
                            if (screenId == "SET-002" && activityId == null && created.draft.startDate.isBlank()) {
                                created.copy(draft = created.draft.copy(startDate = runtimeSources.clock.now().atZone(zone).toLocalDate().toString()))
                            } else {
                                created
                            },
                        )
                    }
                }
            }
        }
    }

    fun navigateSettlement(target: String, activityId: StableId?, participantId: StableId? = null) {
        val screenId = ScreenId(target)
        val arguments = buildMap<String, SafeRouteArgument> {
            if (target != "SET-001") activityId?.let { put("activityId", StableIdArgument(it)) }
            if (target == "SET-005") {
                put("participantId", StableIdArgument(requireNotNull(participantId)))
            }
        }
        navigator.navigate(LedgerRouteContract.destination(screenId, arguments), SessionGateState.READY)
    }

    fun updateSettlementField(field: SettlementField, value: String) = updateSettlement { SettlementPolicy.update(it, field, value) }

    fun selectSettlementActivity(activityId: StableId) = updateSettlement { state ->
        SettlementPolicy.create(state.snapshot, currentSettlementScreenId, activityId)
    }

    fun selectSettlementPayer(participantId: StableId) = updateSettlement { state ->
        val activity = state.activity
        val payerSelf = activity?.participants?.singleOrNull { it.id == participantId }?.isSelf == true
        val payeeSelf = activity?.participants?.singleOrNull { it.id == state.draft.payeeParticipantId }?.isSelf == true
        state.copy(
            draft = state.draft.copy(
                payerParticipantId = participantId,
                accountId = if (payerSelf || payeeSelf) state.draft.accountId ?: state.snapshot.accounts.firstOrNull { it.active && it.currency == activity.currency }?.id else null,
            ),
            presentation = if (payerSelf) {
                SettlementPresentation.SELF_PAYS
            } else if (payeeSelf) {
                SettlementPresentation.SELF_RECEIVES
            } else {
                SettlementPresentation.EXTERNAL_TO_EXTERNAL
            },
        )
    }

    fun selectSettlementPayee(participantId: StableId) = updateSettlement { state ->
        val activity = state.activity
        val payerSelf = activity?.participants?.singleOrNull { it.id == state.draft.payerParticipantId }?.isSelf == true
        val payeeSelf = activity?.participants?.singleOrNull { it.id == participantId }?.isSelf == true
        state.copy(
            draft = state.draft.copy(
                payeeParticipantId = participantId,
                accountId = if (payerSelf || payeeSelf) state.draft.accountId ?: state.snapshot.accounts.firstOrNull { it.active && it.currency == activity.currency }?.id else null,
            ),
            presentation = if (payerSelf) {
                SettlementPresentation.SELF_PAYS
            } else if (payeeSelf) {
                SettlementPresentation.SELF_RECEIVES
            } else {
                SettlementPresentation.EXTERNAL_TO_EXTERNAL
            },
        )
    }

    fun selectSettlementAccount(accountId: StableId?) = updateSettlement { it.copy(draft = it.draft.copy(accountId = accountId)) }
    fun selectSettlementProject(projectId: StableId?) = updateSettlement { state ->
        require(projectId == null || state.snapshot.projects.any { it.id == projectId && it.active })
        state.copy(draft = state.draft.copy(projectId = projectId))
    }
    fun selectSettlementSplitMethod(method: SettlementSplitMethod) = updateSettlement { it.copy(draft = it.draft.copy(splitMethod = method)) }
    fun selectSettlementChargeDistribution(distribution: SettlementChargeDistribution) = updateSettlement { it.copy(draft = it.draft.copy(chargeDistribution = distribution)) }
    fun selectSettlementRoundingRule(rule: SettlementRoundingRule) = updateSettlement { it.copy(draft = it.draft.copy(roundingRule = rule)) }

    fun toggleSettlementParticipant(participantId: StableId) = updateSettlement { state ->
        val participants = state.draft.participants.map { participant ->
            if (participant.id == participantId && !participant.isSelf) participant.copy(included = !participant.included) else participant
        }
        state.copy(draft = state.draft.copy(participants = participants), presentation = SettlementPresentation.EDIT)
    }

    fun moveSettlementParticipant(participantId: StableId, delta: Int) = updateSettlement { state ->
        require(delta == -1 || delta == 1)
        val from = state.draft.participants.indexOfFirst { it.id == participantId }
        if (from < 0) {
            state
        } else {
            val to = (from + delta).coerceIn(0, state.draft.participants.lastIndex)
            if (from == to) return@updateSettlement state
            val reordered = state.draft.participants.toMutableList()
            val participant = reordered.removeAt(from)
            reordered.add(to, participant)
            state.copy(draft = state.draft.copy(participants = reordered), presentation = SettlementPresentation.EDIT)
        }
    }

    fun addSettlementParticipant() = updateSettlement { state ->
        val name = state.draft.participantName.trim()
        if (name.isEmpty() || state.draft.participants.any { it.name.equals(name, ignoreCase = true) }) {
            state.copy(presentation = SettlementPresentation.VALIDATION_ERROR, validationFields = state.validationFields + "participantName")
        } else {
            state.copy(
                draft = state.draft.copy(
                    participantName = "",
                    participants = state.draft.participants + SettlementParticipantDraft(nextId(), name, isSelf = false),
                ),
                presentation = SettlementPresentation.EDIT,
            )
        }
    }

    fun saveSettlement() {
        if (currentSettlementScreenId == "SET-006") saveSettlementPayment() else saveSettlementActivity()
    }

    private fun saveSettlementActivity() {
        val raw = (mutableSettlement.value as? SettlementLoadState.Content)?.state ?: return
        val state = SettlementPolicy.validateActivity(raw)
        if (state.validationFields.isNotEmpty()) {
            mutableSettlement.value = SettlementLoadState.Content(state)
            return
        }
        val included = state.draft.participants.filter { it.included }
        val activity = state.activity
        val activityId = activity?.id ?: nextId()
        val start = runCatching { LocalDate.parse(state.draft.startDate) }.getOrNull() ?: return
        val end = state.draft.endDate.takeIf(String::isNotBlank)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: if (state.draft.endDate.isBlank()) null else return
        val ids = SettlementMutationIds(
            state.snapshot.bookId,
            CommandId(nextId()),
            nextId(),
            nextId(),
            List(included.size + 1) { nextId() },
            included.filterNot { it.isSelf }.associate { it.id to nextId() },
            state.snapshot.localRevision,
        )
        val request = SaveSettlementActivityRequest(
            ids, activityId, activity?.lastCommitId, state.draft.name, state.draft.description.ifBlank { null },
            activity?.currency ?: state.snapshot.baseCurrency, state.draft.projectId, start, end,
            activity?.status ?: SettlementActivityStatus.ACTIVE,
            included.map { SettlementParticipantWriteDraft(it.id, it.name, it.isSelf) }, runtimeSources.clock.now(),
        )
        if (!beginSettlementMutation(state.copy(presentation = SettlementPresentation.SAVING))) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val result = settlementApplicationPort.saveActivity(request)) {
                    is DomainResult.Success -> {
                        navigateSettlement("SET-004", activityId)
                        loadSettlement("SET-004", activityId)
                    }
                    is DomainResult.Failure -> markSettlementFailure(result.error.code)
                }
            } finally {
                mutableSettlementPending.value = false
            }
        }
    }

    private fun saveSettlementPayment() {
        val state = (mutableSettlement.value as? SettlementLoadState.Content)?.state ?: return
        val activity = state.activity ?: return markSettlementInvalid()
        val amount = SettlementPolicy.minor(state.draft.total, activity.currency)?.takeIf { it > 0L } ?: return markSettlementInvalid()
        val payer = state.draft.payerParticipantId ?: return markSettlementInvalid()
        val payee = state.draft.payeeParticipantId?.takeIf { it != payer } ?: return markSettlementInvalid()
        val selfInvolved = activity.participants.any { it.isSelf && it.id in setOf(payer, payee) }
        val account = state.draft.accountId?.let { id -> state.snapshot.accounts.singleOrNull { it.id == id && it.active } }
        val accountPresenceMismatch = selfInvolved != (account != null)
        val currencyMismatch = activity.currency != state.snapshot.baseCurrency || account?.currency?.let { it != activity.currency } == true
        if (accountPresenceMismatch || selfInvolved && currencyMismatch) {
            return markSettlementInvalid()
        }
        val now = runtimeSources.clock.now()
        val zone = ZoneId.of(settings.value.zoneId.ifBlank { DEFAULT_ZONE })
        val request = RecordSettlementPaymentRequest(
            SettlementPaymentIds(
                state.snapshot.bookId, CommandId(nextId()), nextId(), nextId(), nextId(), nextId(), nextId(),
                List(FINANCIAL_FACT_ID_RESERVE) { nextId() }, emptyList(),
            ),
            activity.id, payer, payee, amount, account?.id,
            account?.let { amount }, account?.let { amount }, null, null,
            now, zone, now.atZone(zone).toLocalDate(), state.draft.note.ifBlank { null }, now,
        )
        if (!beginSettlementMutation(state.copy(presentation = SettlementPresentation.SAVING))) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val result = settlementApplicationPort.recordPayment(request)) {
                    is DomainResult.Success -> {
                        navigator.pop()
                        loadSettlement("SET-004", activity.id)
                    }
                    is DomainResult.Failure -> markSettlementFailure(result.error.code)
                }
            } finally {
                mutableSettlementPending.value = false
            }
        }
    }

    fun rebuildSettlement() {
        val state = (mutableSettlement.value as? SettlementLoadState.Content)?.state ?: return
        if (!beginSettlementMutation(state.copy(presentation = SettlementPresentation.SAVING))) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val result = settlementApplicationPort.rebuildAndAudit(state.snapshot.bookId)) {
                    is DomainResult.Success -> loadSettlement(currentSettlementScreenId, state.selectedActivityId, state.selectedParticipantId)
                    is DomainResult.Failure -> markSettlementFailure(result.error.code)
                }
            } finally {
                mutableSettlementPending.value = false
            }
        }
    }

    private fun beginSettlementMutation(state: SettlementFeatureState): Boolean {
        if (mutableSettlementPending.value) return false
        mutableSettlementPending.value = true
        mutableSettlement.value = SettlementLoadState.Content(state)
        return true
    }

    private fun markSettlementInvalid() = updateSettlement {
        it.copy(presentation = SettlementPresentation.VALIDATION_ERROR, validationFields = setOf("settlement"))
    }

    private fun markSettlementFailure(code: String) = updateSettlement {
        it.copy(presentation = SettlementPresentation.VALIDATION_ERROR, validationFields = setOf(sanitizeCode(code)))
    }

    private fun updateSettlement(block: (SettlementFeatureState) -> SettlementFeatureState) {
        val current = mutableSettlement.value as? SettlementLoadState.Content ?: return
        mutableSettlement.value = SettlementLoadState.Content(block(current.state))
    }

    fun requestRootBack() {
        val screen = navigator.currentKey.contract.screenId.value
        val editor = (mutableOrdinaryRecord.value as? OrdinaryRecordLoadState.Content)?.editor
        if (screen in setOf("REC-023", "REC-024", "REC-025") && batchRecord.value?.presentation != app.ledger.feature.record.BatchRecordPresentation.COMMITTED) {
            batchEntryController.requestDiscardConfirmation()
        } else if (screen == "REC-003" && editor?.draft?.dirty == true) {
            pendingRecordExit = PendingRecordExit.Back
            updateEditor { it.copy(showUnsavedDialog = true) }
        } else {
            navigator.pop()
        }
    }

    fun selectRootTopLevel(target: TopLevelDestination) {
        val screen = navigator.currentKey.contract.screenId.value
        val editor = (mutableOrdinaryRecord.value as? OrdinaryRecordLoadState.Content)?.editor
        if (screen in setOf("REC-023", "REC-024", "REC-025") && batchRecord.value?.presentation != app.ledger.feature.record.BatchRecordPresentation.COMMITTED) {
            batchEntryController.requestDiscardConfirmation()
        } else if (screen == "REC-003" && editor?.draft?.dirty == true) {
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

    fun loadAutomation(
        screenId: String = currentAutomationScreenId,
        blueprintId: StableId? = null,
        seriesId: StableId? = null,
        candidateId: StableId? = null,
    ) {
        if ((mutableRootState.value as? AppRootState.Session)?.state !is BookSessionState.Ready) return
        currentAutomationScreenId = screenId
        viewModelScope.launch(Dispatchers.IO) {
            val saved = settingsRepository.current()
            val bookId = runCatching { requireBookId(saved) }.getOrNull() ?: return@launch
            val zone = ZoneId.of(saved.zoneId.ifBlank { DEFAULT_ZONE })
            val today = runtimeSources.clock.now().atZone(zone).toLocalDate()
            mutableAutomation.value = AutomationLoadState.Loading
            val automationResult = automationApplicationPort.snapshot(bookId)
            val entryResult = ordinaryTransactionEntryPort.snapshot(bookId)
            mutableAutomation.value = if (automationResult is DomainResult.Success && entryResult is DomainResult.Success) {
                AutomationLoadState.Content(
                    AutomationPolicy.create(
                        automationResult.value,
                        entryResult.value,
                        screenId,
                        blueprintId,
                        seriesId,
                        candidateId,
                        zone,
                        today,
                    ),
                )
            } else {
                val code = (automationResult as? DomainResult.Failure)?.error?.code
                    ?: (entryResult as? DomainResult.Failure)?.error?.code
                    ?: "AUTOMATION_LOAD_FAILED"
                AutomationLoadState.Failure(sanitizeCode(code))
            }
        }
    }

    fun navigateAutomation(target: String, stableId: StableId?) {
        val screenId = ScreenId(target)
        val argumentName = when {
            target == "AUT-003" -> "templateId"
            target in setOf("AUT-005", "AUT-006", "AUT-007", "AUT-010") -> "seriesId"
            target == "AUT-009" -> "candidateId"
            else -> null
        }
        val arguments = if (argumentName != null && stableId != null) mapOf(argumentName to StableIdArgument(stableId)) else emptyMap()
        navigator.navigate(LedgerRouteContract.destination(screenId, arguments), SessionGateState.READY)
    }

    fun updateAutomationSearch(value: String) = updateAutomationContent { copy(search = value.take(80)) }

    fun updateAutomationBlueprintField(field: BlueprintField, value: String) = updateAutomationContent {
        AutomationPolicy.updateBlueprint(this, field, value)
    }

    fun updateAutomationBlueprintKind(kind: app.ledger.finance.domain.TransactionKind) = updateAutomationContent {
        val draft = requireNotNull(blueprintDraft)
        copy(blueprintDraft = draft.copy(targetKind = kind), presentation = AutomationPresentation.EDITING, validationFields = emptySet())
    }

    fun updateAutomationBlueprintReference(field: String, id: StableId?) = updateAutomationContent {
        val draft = requireNotNull(blueprintDraft)
        val updated = when (field) {
            "category" -> draft.copy(categoryId = id)
            "primaryAccount" -> {
                val currency = entrySnapshot.references.accounts.singleOrNull { it.id == id }?.currency?.value.orEmpty()
                draft.copy(primaryAccountId = id, currency = currency)
            }
            "secondaryAccount" -> draft.copy(secondaryAccountId = id)
            "card" -> draft.copy(cardId = id)
            "merchant" -> draft.copy(merchantId = id)
            "project" -> draft.copy(projectId = id)
            "goal" -> draft.copy(goalId = id)
            "settlement" -> draft.copy(settlementActivityId = id)
            "fixedPlace" -> draft.copy(fixedPlaceId = id)
            else -> draft
        }
        copy(blueprintDraft = updated, presentation = AutomationPresentation.EDITING, validationFields = emptySet())
    }

    fun saveAutomationBlueprint() {
        if (mutableAutomationPending.value) return
        val content = mutableAutomation.value as? AutomationLoadState.Content ?: return
        val validated = AutomationPolicy.validateBlueprint(content.state)
        mutableAutomation.value = AutomationLoadState.Content(validated)
        if (validated.validationFields.isNotEmpty()) return
        val draft = requireNotNull(validated.blueprintDraft)
        mutableAutomationPending.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val snapshot = validated.snapshot
                val current = draft.id?.let { id -> snapshot.blueprints.singleOrNull { it.id == id } }
                val request = SaveBlueprintRequest(
                    AutomationMutationIds(snapshot.bookId, nextId(), nextId(), nextId(), snapshot.localRevision, runtimeSources.clock.now()),
                    BlueprintDraft(
                        id = draft.id ?: nextId(),
                        revisionId = nextId(),
                        expectedRevisionId = current?.revisionId,
                        name = draft.name.trim(),
                        iconKey = LedgerReferenceDisplayDefaults.CATEGORY_ICON_KEY,
                        colorArgb = LedgerReferenceDisplayDefaults.COLOR_ARGB,
                        status = current?.status ?: app.ledger.finance.domain.EntityStatus.ACTIVE,
                        targetKind = draft.targetKind,
                        categoryId = draft.categoryId,
                        primaryAccountId = draft.primaryAccountId,
                        secondaryAccountId = draft.secondaryAccountId,
                        cardId = draft.cardId,
                        merchantId = draft.merchantId,
                        projectId = draft.projectId,
                        goalId = draft.goalId,
                        settlementActivityId = draft.settlementActivityId,
                        amountExpression = draft.amountExpression.trim().takeIf(String::isNotEmpty),
                        currency = draft.currency.takeIf(String::isNotBlank)?.let { CurrencyCode.parse(it).getOrNull() },
                        noteTemplate = draft.noteTemplate.trim().takeIf(String::isNotEmpty),
                        fixedPlaceId = draft.fixedPlaceId,
                    ),
                )
                when (val result = automationApplicationPort.saveBlueprint(request)) {
                    is DomainResult.Success -> {
                        loadAutomation("AUT-002")
                        navigator.pop()
                    }
                    is DomainResult.Failure -> mutableAutomation.value = AutomationLoadState.Content(validated.copy(presentation = AutomationPresentation.VALIDATION_ERROR, failureCode = sanitizeCode(result.error.code)))
                }
            } finally {
                mutableAutomationPending.value = false
            }
        }
    }

    fun updateAutomationRecurrenceField(field: RecurrenceField, value: String) = updateAutomationContent {
        AutomationPolicy.updateRecurrence(this, field, value)
    }

    fun selectAutomationRecurrenceBlueprint(id: StableId) = updateAutomationContent {
        val draft = requireNotNull(recurrenceDraft)
        val fixedPlace = snapshot.blueprints.singleOrNull { it.id == id }?.fixedPlaceId
        copy(recurrenceDraft = draft.copy(blueprintId = id, fixedPlaceId = fixedPlace), presentation = AutomationPresentation.EDITING)
    }

    fun updateAutomationFrequency(frequency: RecurrenceFrequency) = updateAutomationContent {
        val draft = requireNotNull(recurrenceDraft)
        val old = draft.rule
        val updated = old.copy(
            frequency = frequency,
            weekdays = if (frequency in setOf(RecurrenceFrequency.WEEKLY, RecurrenceFrequency.BUSINESS_DAYS)) old.weekdays.ifEmpty { setOf(DayOfWeek.MONDAY) } else emptySet(),
            monthDay = if (frequency == RecurrenceFrequency.MONTHLY_DAY) old.monthDay ?: 1 else null,
            nthWeek = if (frequency == RecurrenceFrequency.MONTHLY_NTH_WEEKDAY) old.nthWeek ?: 1 else null,
            weekday = if (frequency == RecurrenceFrequency.MONTHLY_NTH_WEEKDAY) old.weekday ?: DayOfWeek.MONDAY else null,
        )
        copy(recurrenceDraft = draft.copy(rule = updated), presentation = AutomationPresentation.EDITING)
    }

    fun toggleAutomationWeekday(day: DayOfWeek) = updateAutomationContent {
        val draft = requireNotNull(recurrenceDraft)
        val days = if (day in draft.rule.weekdays) draft.rule.weekdays - day else draft.rule.weekdays + day
        copy(recurrenceDraft = draft.copy(rule = draft.rule.copy(weekdays = days)), presentation = AutomationPresentation.EDITING)
    }

    fun updateAutomationMissingDay(value: MissingDayPolicy) = updateAutomationContent {
        val draft = requireNotNull(recurrenceDraft)
        copy(recurrenceDraft = draft.copy(rule = draft.rule.copy(missingDayPolicy = value)), presentation = AutomationPresentation.EDITING)
    }

    fun updateAutomationWeekend(value: WeekendAdjustment) = updateAutomationContent {
        val draft = requireNotNull(recurrenceDraft)
        copy(recurrenceDraft = draft.copy(rule = draft.rule.copy(weekendAdjustment = value)), presentation = AutomationPresentation.EDITING)
    }

    fun updateAutomationGenerationMode(value: RecurrenceGenerationMode) = updateAutomationContent {
        val draft = requireNotNull(recurrenceDraft)
        copy(recurrenceDraft = draft.copy(generationMode = value, notifyCandidate = value == RecurrenceGenerationMode.CANDIDATE && draft.notifyCandidate), presentation = AutomationPresentation.EDITING)
    }

    fun updateAutomationNotifyCandidate(value: Boolean) = updateAutomationContent {
        val draft = requireNotNull(recurrenceDraft)
        copy(recurrenceDraft = draft.copy(notifyCandidate = value && draft.generationMode == RecurrenceGenerationMode.CANDIDATE), presentation = AutomationPresentation.EDITING)
    }

    fun saveAutomationRecurrence() {
        if (mutableAutomationPending.value) return
        val content = mutableAutomation.value as? AutomationLoadState.Content ?: return
        val validated = AutomationPolicy.validateRecurrence(content.state)
        mutableAutomation.value = AutomationLoadState.Content(validated)
        if (validated.validationFields.isNotEmpty()) return
        val uiDraft = requireNotNull(validated.recurrenceDraft)
        val start = LocalDate.parse(uiDraft.startDate)
        val end = uiDraft.endDate.takeIf(String::isNotBlank)?.let(LocalDate::parse)
        mutableAutomationPending.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val snapshot = validated.snapshot
                val current = uiDraft.id?.let { id -> snapshot.series.singleOrNull { it.id == id } }
                val draft = RecurrenceSeriesDraft(
                    id = uiDraft.id ?: nextId(),
                    revisionId = nextId(),
                    expectedRevisionId = current?.revisionId,
                    blueprintId = requireNotNull(uiDraft.blueprintId),
                    status = uiDraft.status,
                    rule = uiDraft.rule,
                    startDate = start,
                    endDate = end,
                    maxOccurrences = uiDraft.maxOccurrences.takeIf(String::isNotBlank)?.toInt(),
                    occurrenceTime = LocalTime.of(9, 0),
                    zoneId = uiDraft.zoneId,
                    generationMode = uiDraft.generationMode,
                    fixedPlaceId = uiDraft.fixedPlaceId,
                    notifyCandidate = uiDraft.notifyCandidate,
                )
                val result = automationApplicationPort.saveSeries(
                    SaveRecurrenceRequest(
                        AutomationMutationIds(snapshot.bookId, nextId(), nextId(), nextId(), snapshot.localRevision, runtimeSources.clock.now()),
                        draft,
                    ),
                )
                when (result) {
                    is DomainResult.Success -> {
                        loadAutomation("AUT-004")
                        navigator.pop()
                        RecurrenceWorkScheduler.enqueueCatchUp(context, snapshot.bookId)
                    }
                    is DomainResult.Failure -> mutableAutomation.value = AutomationLoadState.Content(validated.copy(presentation = AutomationPresentation.INVALID, failureCode = sanitizeCode(result.error.code)))
                }
            } finally {
                mutableAutomationPending.value = false
            }
        }
    }

    fun selectAutomationTemplate(id: StableId) {
        val content = mutableAutomation.value as? AutomationLoadState.Content ?: return
        val template = content.state.snapshot.blueprints.singleOrNull { it.id == id } ?: return
        openRecordEditor(
            RecordEditorMode.TEMPLATE,
            if (template.targetKind == app.ledger.finance.domain.TransactionKind.INCOME) OrdinaryDirection.INCOME else OrdinaryDirection.EXPENSE,
            template.categoryId,
            id,
        )
    }

    fun selectAutomationCandidate(id: StableId) {
        navigateAutomation("AUT-009", id)
    }

    fun toggleAutomationCandidate(id: StableId) = updateAutomationContent {
        copy(selectedCandidateIds = if (id in selectedCandidateIds) selectedCandidateIds - id else selectedCandidateIds + id, presentation = AutomationPresentation.SELECTION)
    }

    fun confirmAutomationCandidate() {
        val content = mutableAutomation.value as? AutomationLoadState.Content ?: return
        val candidate = content.state.selectedCandidate ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val bookId = content.state.snapshot.bookId
            when (val confirmed = automationApplicationPort.confirmCandidate(bookId, candidate.id)) {
                is DomainResult.Failure -> mutableAutomation.value = AutomationLoadState.Content(content.state.copy(presentation = AutomationPresentation.INVALID_SOURCE, failureCode = sanitizeCode(confirmed.error.code)))
                is DomainResult.Success -> {
                    val template = confirmed.value.blueprint
                    val snapshot = when (val loaded = ordinaryTransactionEntryPort.snapshot(bookId)) {
                        is DomainResult.Success -> loaded.value
                        is DomainResult.Failure -> return@launch
                    }
                    val direction = if (template.targetKind == app.ledger.finance.domain.TransactionKind.INCOME) OrdinaryDirection.INCOME else OrdinaryDirection.EXPENSE
                    val zone = ZoneId.of(settingsRepository.current().zoneId.ifBlank { DEFAULT_ZONE })
                    val editor = OrdinaryRecordPolicy.createEditor(snapshot, RecordEditorMode.TEMPLATE, direction, template.categoryId, template.id, runtimeSources.clock.now(), zone, recordLocale())
                        .copy(mode = RecordEditorMode.CANDIDATE, sourceReferenceId = candidate.id)
                    mutableOrdinaryRecord.value = OrdinaryRecordLoadState.Content(snapshot, if (direction == OrdinaryDirection.EXPENSE) RecordTab.EXPENSE else RecordTab.INCOME, selectedCategoryId = template.categoryId, editor = editor)
                    pendingCandidateId = candidate.id
                    val screenId = ScreenId("REC-003")
                    navigator.navigate(
                        LedgerRouteContract.destination(
                            screenId,
                            mapOf("mode" to LedgerRouteContract.enumArgument(screenId, "mode", RecordEditorMode.CANDIDATE.name)),
                        ),
                        SessionGateState.READY,
                    )
                }
            }
        }
    }

    fun skipAutomationCandidate() {
        val content = mutableAutomation.value as? AutomationLoadState.Content ?: return
        val candidate = content.state.selectedCandidate ?: return
        viewModelScope.launch(Dispatchers.IO) {
            when (automationApplicationPort.skipCandidate(content.state.snapshot.bookId, candidate.id, runtimeSources.clock.now())) {
                is DomainResult.Success -> {
                    loadAutomation("AUT-008")
                    navigator.pop()
                }
                is DomainResult.Failure -> Unit
            }
        }
    }

    fun updateAutomationScope(value: RecurrenceModificationScope) = updateAutomationContent { copy(modificationScope = value) }

    fun applyAutomationScope() {
        val content = mutableAutomation.value as? AutomationLoadState.Content ?: return
        val state = content.state
        val selected = state.selectedSeries ?: return
        val uiDraft = state.recurrenceDraft ?: return
        val date = selected.preview.firstOrNull()?.localDate ?: selected.startDate
        viewModelScope.launch(Dispatchers.IO) {
            val replacement = RecurrenceSeriesDraft(
                selected.id,
                nextId(),
                selected.revisionId,
                requireNotNull(uiDraft.blueprintId),
                uiDraft.status,
                uiDraft.rule,
                LocalDate.parse(uiDraft.startDate),
                uiDraft.endDate.takeIf(String::isNotBlank)?.let(LocalDate::parse),
                uiDraft.maxOccurrences.takeIf(String::isNotBlank)?.toInt(),
                LocalTime.of(9, 0),
                uiDraft.zoneId,
                uiDraft.generationMode,
                uiDraft.fixedPlaceId,
                uiDraft.notifyCandidate,
            )
            when (
                automationApplicationPort.modifyOccurrence(
                    ModifyOccurrenceRequest(
                        AutomationMutationIds(state.snapshot.bookId, nextId(), nextId(), nextId(), state.snapshot.localRevision, runtimeSources.clock.now()),
                        selected.id,
                        date,
                        state.modificationScope,
                        replacement,
                    ),
                )
            ) {
                is DomainResult.Success -> {
                    loadAutomation("AUT-004")
                    navigator.pop()
                }
                is DomainResult.Failure -> Unit
            }
        }
    }

    private fun updateAutomationContent(block: AutomationFeatureState.() -> AutomationFeatureState) {
        val content = mutableAutomation.value as? AutomationLoadState.Content ?: return
        mutableAutomation.value = AutomationLoadState.Content(content.state.block())
    }

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
        return valuedBaseMinor(result, balanceMinor, currentBaseValueMinor)
    }

    private fun valuedBaseMinor(resultMinor: Long, balanceMinor: Long, currentBaseValueMinor: Long?): Long? {
        if (balanceMinor == 0L || currentBaseValueMinor == null) return null
        return runCatching {
            java.math.BigDecimal.valueOf(resultMinor)
                .multiply(java.math.BigDecimal.valueOf(currentBaseValueMinor).abs())
                .divide(java.math.BigDecimal.valueOf(balanceMinor).abs(), 0, java.math.RoundingMode.HALF_EVEN)
                .longValueExact()
                .takeIf { it > 0L }
        }.getOrNull()
    }

    private fun recordSource(editor: OrdinaryRecordEditorState): TransactionSource = when (editor.mode) {
        RecordEditorMode.TEMPLATE -> TransactionSource.QUICK_TEMPLATE
        RecordEditorMode.CANDIDATE -> TransactionSource.MANUAL
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
        const val PROJECT_GOAL_NOT_FOUND = "PROJECT_GOAL_NOT_FOUND"
        const val CREDIT_NOT_FOUND = "CREDIT_NOT_FOUND"
        const val CREDIT_FACT_ID_COUNT = 96
        const val INSTALLMENT_NOT_FOUND = "INSTALLMENT_NOT_FOUND"
        const val INSTALLMENT_PAYMENT_ACCOUNT_REQUIRED = "INSTALLMENT_PAYMENT_ACCOUNT_REQUIRED"
        const val INSTALLMENT_FACT_ID_COUNT = 96
        const val INSTALLMENT_MAX_TERMS = 600
        const val LOAN_NOT_FOUND = "LOAN_NOT_FOUND"
        const val LOAN_FACT_ID_COUNT = 128
        const val LOAN_MAX_PAYMENTS = 1_200
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

private data class ProjectTransactionPagingRequest(
    val bookId: StableId,
    val projectId: StableId,
)

private sealed interface PendingRecordExit {
    data object Back : PendingRecordExit
    data class TopLevel(val target: TopLevelDestination) : PendingRecordExit
}
