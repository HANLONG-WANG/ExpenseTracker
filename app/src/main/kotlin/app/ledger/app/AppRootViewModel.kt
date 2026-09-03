@file:Suppress("LongMethod", "TooManyFunctions", "MagicNumber", "LongParameterList", "LargeClass", "MaxLineLength", "ReturnCount", "CyclomaticComplexMethod")

package app.ledger.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.biometric.BiometricPrompt
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.work.WorkManager
import app.ledger.analytics.domain.AnalyticsApplicationPort
import app.ledger.analytics.domain.DimensionValue
import app.ledger.analytics.domain.DrilldownQueryId
import app.ledger.analytics.domain.FixedReport
import app.ledger.analytics.domain.FixedReportCatalog
import app.ledger.analytics.domain.ReportExportFormat
import app.ledger.analytics.domain.ReportExportPayload
import app.ledger.app.settings.DateFormatProto
import app.ledger.app.settings.DestinationProto
import app.ledger.app.settings.LedgerAppSettings
import app.ledger.app.settings.NavigationSnapshotProto
import app.ledger.app.settings.RouteArgumentProto
import app.ledger.app.settings.ThemeModeProto
import app.ledger.app.settings.TopLevelStackProto
import app.ledger.app.settings.WeekStartProto
import app.ledger.core.background.NotificationPermissionStatus
import app.ledger.core.background.OperationNotificationCoordinator
import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.core.common.map
import app.ledger.core.designsystem.LedgerReferenceDisplayDefaults
import app.ledger.core.designsystem.LedgerRetainedStateStore
import app.ledger.core.files.SecureBookAttachmentObjectPort
import app.ledger.core.geo.ForegroundLocationRetryRunner
import app.ledger.core.geo.ForegroundLocationSaveSession
import app.ledger.core.geo.LocationSaveDisposition
import app.ledger.core.geo.ProductionForegroundLocationClient
import app.ledger.core.money.CurrencyCode
import app.ledger.core.money.LocaleNumberFormatter
import app.ledger.core.navigation.DestinationSnapshot
import app.ledger.core.navigation.EncodedRouteArgument
import app.ledger.core.navigation.EnumMaskArgument
import app.ledger.core.navigation.FiveStackNavigator
import app.ledger.core.navigation.FiveStackSnapshot
import app.ledger.core.navigation.LedgerDestinationKey
import app.ledger.core.navigation.LedgerRouteContract
import app.ledger.core.navigation.NavigationOutcome
import app.ledger.core.navigation.RouteArgumentKind
import app.ledger.core.navigation.SafeRouteArgument
import app.ledger.core.navigation.ScreenId
import app.ledger.core.navigation.SessionGateState
import app.ledger.core.navigation.StableIdArgument
import app.ledger.core.navigation.TopLevelDestination
import app.ledger.core.navigation.TopLevelStackSnapshot
import app.ledger.core.navigation.YearMonthArgument
import app.ledger.core.security.ActiveBookSessionRuntime
import app.ledger.core.security.AndroidKeystoreKeys
import app.ledger.core.security.AppLockController
import app.ledger.core.security.AppLockSettings
import app.ledger.core.security.AppLockState
import app.ledger.core.security.AppLockTimeout
import app.ledger.core.security.Argon2idCalibrator
import app.ledger.core.security.BiometricErrorCode
import app.ledger.core.security.BookSessionManager
import app.ledger.core.security.BookSessionState
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.core.security.DeviceSecurityCapability
import app.ledger.core.security.LedgerAccessMode
import app.ledger.core.security.LedgerInteractionOperation
import app.ledger.core.security.LedgerSessionPerformance
import app.ledger.core.security.MaintenanceReason
import app.ledger.core.security.RecoveryPassword
import app.ledger.core.security.RecoveryPasswordKeyWrapper
import app.ledger.core.security.RecoveryWrappedKeyMaterial
import app.ledger.core.security.ScreenPrivacyPolicy
import app.ledger.core.security.SecretBytes
import app.ledger.core.security.SecurePrimaryLedgerAccess
import app.ledger.core.security.SecurityAssociatedData
import app.ledger.core.security.VaultExposureRegistry
import app.ledger.core.telemetry.TelemetryRuntime
import app.ledger.core.time.InjectedJavaClock
import app.ledger.feature.accounts.AccountEditorSubmission
import app.ledger.feature.accounts.CardEditorSubmission
import app.ledger.feature.accounts.CheckpointSubmission
import app.ledger.feature.accounts.OpeningBalanceSubmission
import app.ledger.feature.analysis.AnalysisLoadState
import app.ledger.feature.automation.AutomationFeatureState
import app.ledger.feature.automation.AutomationLoadState
import app.ledger.feature.automation.AutomationPolicy
import app.ledger.feature.automation.AutomationPresentation
import app.ledger.feature.automation.AutomationSeriesFilter
import app.ledger.feature.automation.AutomationTemplateFilter
import app.ledger.feature.automation.AutomationTemplateSort
import app.ledger.feature.automation.BlueprintEditorDraft
import app.ledger.feature.automation.BlueprintField
import app.ledger.feature.automation.RecurrenceEditorDraft
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
import app.ledger.feature.record.OrdinaryRecordScreenUiState
import app.ledger.feature.record.RecordAttachmentPresentation
import app.ledger.feature.record.RecordEditorMode
import app.ledger.feature.record.RecordEditorPresentation
import app.ledger.feature.record.RecordField
import app.ledger.feature.record.RecordLocationEditorState
import app.ledger.feature.record.RecordPendingLocation
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
import app.ledger.feature.settings.CrashQueueRow
import app.ledger.feature.settings.CurrencySettingsPolicy
import app.ledger.feature.settings.CurrencySettingsState
import app.ledger.feature.settings.FeatureQueueRow
import app.ledger.feature.settings.MerchantSubmission
import app.ledger.feature.settings.PlaceSubmission
import app.ledger.feature.settings.RemainingSettingsState
import app.ledger.feature.settings.SecurityPrivacySettingsState
import app.ledger.feature.settings.SecuritySettingsRequiredState
import app.ledger.feature.settings.SettingsDateFormat
import app.ledger.feature.settings.SettingsThemeMode
import app.ledger.feature.settings.SettingsWeekStart
import app.ledger.feature.settings.TrashRetention
import app.ledger.feature.settlement.SettlementFeatureState
import app.ledger.feature.settlement.SettlementField
import app.ledger.feature.settlement.SettlementLoadState
import app.ledger.feature.settlement.SettlementParticipantDraft
import app.ledger.feature.settlement.SettlementPolicy
import app.ledger.feature.settlement.SettlementPresentation
import app.ledger.feature.transfer.BackupFlowUiState
import app.ledger.feature.transfer.ExportFlowUiState
import app.ledger.feature.transfer.ImportModeUi
import app.ledger.feature.transfer.RestoreFlowUiState
import app.ledger.feature.vault.VaultEditSubmission
import app.ledger.feature.vault.VaultPresentationState
import app.ledger.finance.application.AccountDraft
import app.ledger.finance.application.AccountSummarySnapshot
import app.ledger.finance.application.ApplyInstallmentSettlementRequest
import app.ledger.finance.application.ApplyLoanSimulationRequest
import app.ledger.finance.application.AssignCreditStatementRequest
import app.ledger.finance.application.AsyncContent
import app.ledger.finance.application.AttachmentContentSource
import app.ledger.finance.application.AttachmentImportRequest
import app.ledger.finance.application.AutomationApplicationPort
import app.ledger.finance.application.AutomationMutationIds
import app.ledger.finance.application.BatchEntryApplicationPort
import app.ledger.finance.application.BatchEntryField
import app.ledger.finance.application.BlueprintDraft
import app.ledger.finance.application.BudgetApplicationPort
import app.ledger.finance.application.BudgetMutationIds
import app.ledger.finance.application.CardDraft
import app.ledger.finance.application.CategoryDraft
import app.ledger.finance.application.ChangeProjectStatusRequest
import app.ledger.finance.application.CommittedLedgerChange
import app.ledger.finance.application.CompleteGoalRequest
import app.ledger.finance.application.ControlledPurgeApplicationPort
import app.ledger.finance.application.ControlledPurgeRequest
import app.ledger.finance.application.CreateCreditAccountProfileRequest
import app.ledger.finance.application.CreditApplicationPort
import app.ledger.finance.application.CreditMutationIds
import app.ledger.finance.application.CreditPaymentContext
import app.ledger.finance.application.CreditStatementMutationIds
import app.ledger.finance.application.CreditTransactionMutationIds
import app.ledger.finance.application.DEFAULT_INTERACTIVE_LOAD_TIMEOUT_MILLIS
import app.ledger.finance.application.DebouncedSingleFlightLoader
import app.ledger.finance.application.FinancialCommitObserver
import app.ledger.finance.application.FinancialCommitObserverRegistry
import app.ledger.finance.application.GoalCompletionStrategy
import app.ledger.finance.application.GoalMovementMutationIds
import app.ledger.finance.application.ImportFinancialApplicationPort
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
import app.ledger.finance.application.KeyedLoadRegistry
import app.ledger.finance.application.LedgerDataScope
import app.ledger.finance.application.LedgerGenesisIds
import app.ledger.finance.application.LedgerInitializationPort
import app.ledger.finance.application.LedgerRevisionCacheControl
import app.ledger.finance.application.LoadResult
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
import app.ledger.finance.application.RefundCandidatePage
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
import app.ledger.finance.application.SingleFlightLoader
import app.ledger.finance.application.SpecializedAccountAmountDraft
import app.ledger.finance.application.SpecializedFxQuoteRequest
import app.ledger.finance.application.SpecializedTransactionContext
import app.ledger.finance.application.SpecializedTransactionEntryPort
import app.ledger.finance.application.SpecializedTransactionWriteIds
import app.ledger.finance.application.SpecializedTransactionWriteRequest
import app.ledger.finance.application.UpdateBookLocaleCommand
import app.ledger.finance.application.VaultSecretApplicationPort
import app.ledger.finance.application.WidgetQuickDirection
import app.ledger.finance.application.WidgetQuickTargetKind
import app.ledger.finance.application.WidgetSnapshotRefreshApplicationPort
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
import app.ledger.finance.domain.TransactionKind
import app.ledger.finance.domain.TransactionLifecycleState
import app.ledger.finance.domain.TransactionSource
import app.ledger.finance.domain.UserAccountId
import app.ledger.finance.domain.UserAccountType
import app.ledger.finance.domain.WeekendAdjustment
import app.ledger.transfer.data.SqlCipherBackgroundOperationRepository
import app.ledger.transfer.domain.BackgroundOperation
import app.ledger.transfer.domain.BackgroundOperationId
import app.ledger.transfer.domain.BackgroundOperationState
import app.ledger.transfer.domain.BackgroundOperationType
import app.ledger.transfer.domain.BackupNetworkPolicy
import app.ledger.transfer.domain.BackupRepositoryKind
import app.ledger.transfer.domain.ExportContent
import app.ledger.transfer.domain.ExportField
import app.ledger.transfer.domain.ExportFilter
import app.ledger.transfer.domain.ExportFormat
import app.ledger.transfer.domain.ExportReportSnapshot
import app.ledger.transfer.domain.MergeResolution
import app.ledger.transfer.domain.RecoveryPasswordChangeMode
import app.ledger.transfer.domain.RestoreMode
import app.ledger.transfer.domain.StagingValue
import app.ledger.widget.LedgerWidgetRuntime
import com.google.protobuf.ByteString
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.inject.Inject
import app.ledger.feature.analysis.R as AnalysisR
import app.ledger.feature.journal.R as JournalR
import app.ledger.feature.settings.AppLockTimeout as SettingsAppLockTimeout
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

internal enum class GlobalSnackbarMessage {
    SETTINGS_WRITE_FAILED,
    LOCAL_CLEAR_FAILED,
    EXTERNAL_APP_UNAVAILABLE,
    REFERENCE_SAVED,
    REFERENCE_ARCHIVED,
    REFERENCE_DELETED,
    REFERENCE_MUTATION_FAILED,
    JOURNAL_MOVED_TO_TRASH,
    JOURNAL_RESTORED,
    JOURNAL_MUTATION_FAILED,
    JOURNAL_PERMANENTLY_DELETED,
    JOURNAL_BULK_UPDATED,
    PLANNING_UPDATED,
    LOAN_UPDATED,
    SETTLEMENT_UPDATED,
    AUTOMATION_UPDATED,
}

internal sealed interface OperationCenterLoadState {
    data object Loading : OperationCenterLoadState
    data class Content(val operations: List<BackgroundOperation>) : OperationCenterLoadState
    data class Failure(val code: String) : OperationCenterLoadState
}

internal enum class SensitiveSettingsAuthenticationPurpose { ENABLE_APP_LOCK, CLEAR_LOCAL, DELETE_CLOUD }

internal enum class NotificationPermissionPresentation { FIRST_ASK, DENIED, GRANTED }

private data class WidgetQuickDeepLink(
    val kind: WidgetQuickTargetKind,
    val direction: WidgetQuickDirection,
    val targetId: StableId,
)

private sealed interface WidgetDeepLink {
    data class Destination(val destination: LedgerDestinationKey) : WidgetDeepLink
    data class Quick(val value: WidgetQuickDeepLink) : WidgetDeepLink
}

internal sealed interface AppReferenceDataState {
    data object Loading : AppReferenceDataState
    data class Content(val snapshot: ReferenceDataSnapshot) : AppReferenceDataState
    data class Error(val code: String) : AppReferenceDataState
}

@HiltViewModel
internal class AppRootViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: AppSettingsRepository,
    private val activeBookSessionRuntime: ActiveBookSessionRuntime,
    private val keyProvider: DeviceLedgerKeyProvider,
    private val initializationPort: LedgerInitializationPort,
    private val referenceDataPort: ReferenceDataManagementPort,
    vaultSecretApplicationPort: VaultSecretApplicationPort,
    private val journalApplicationPort: JournalApplicationPort,
    private val controlledPurgeApplicationPort: ControlledPurgeApplicationPort,
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
    importFinancialApplicationPort: ImportFinancialApplicationPort,
    analyticsApplicationPort: AnalyticsApplicationPort,
    private val specializedTransactionEntryPort: SpecializedTransactionEntryPort,
    private val bookAttachmentObjectPort: SecureBookAttachmentObjectPort,
    private val widgetSnapshotRefreshApplicationPort: WidgetSnapshotRefreshApplicationPort,
    private val runtimeSources: AppRuntimeSources,
    private val vaultExposureRegistry: VaultExposureRegistry,
    private val revisionCacheControl: LedgerRevisionCacheControl,
) : ViewModel() {
    internal fun currentUiInstant(): Instant = runtimeSources.clock.now()

    private val vaultController = VaultController(
        context,
        vaultSecretApplicationPort,
        vaultExposureRegistry,
        viewModelScope,
        runtimeSources.clock::now,
    )
    val vault: StateFlow<VaultPresentationState> = vaultController.state
    val vaultAuthenticationRequests = vaultController.authentication
    private val securityPrivacyViewModel = SecurityPrivacyScreenViewModel(defaultSecurityPrivacyState())
    val securityPrivacy: StateFlow<SecurityPrivacySettingsState> = securityPrivacyViewModel.state
    private val mutableSecurityPrivacy get() = securityPrivacyViewModel.mutableState
    private val mutableSensitiveSettingsAuthenticationRequests =
        MutableSharedFlow<SensitiveSettingsAuthenticationPurpose>(extraBufferCapacity = 1)
    val sensitiveSettingsAuthenticationRequests = mutableSensitiveSettingsAuthenticationRequests.asSharedFlow()
    private var pendingSensitiveSettingsPurpose: SensitiveSettingsAuthenticationPurpose? = null
    private val mutableScreenPrivacyPolicy = MutableStateFlow(ScreenPrivacyPolicy())
    val screenPrivacyPolicy: StateFlow<ScreenPrivacyPolicy> = mutableScreenPrivacyPolicy.asStateFlow()
    private val mutableOpenSystemSecurityRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openSystemSecurityRequests = mutableOpenSystemSecurityRequests.asSharedFlow()
    private val mutableExternalLinkRequests = MutableSharedFlow<Uri>(extraBufferCapacity = 1)
    val externalLinkRequests = mutableExternalLinkRequests.asSharedFlow()
    private val mutableNotificationPermissionRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val notificationPermissionRequests = mutableNotificationPermissionRequests.asSharedFlow()
    private val mutableOpenNotificationSettingsRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openNotificationSettingsRequests = mutableOpenNotificationSettingsRequests.asSharedFlow()
    private val availableZoneIds: List<String> = ZoneId.getAvailableZoneIds().sorted()
    private val analysisController = AnalysisController(analyticsApplicationPort) { name ->
        formatPresentationString(AnalysisR.string.analysis_copy_name, name)
    }
    private val importController = ImportController(
        context,
        keyProvider,
        referenceDataPort,
        importFinancialApplicationPort,
        runtimeSources,
        activeBookSessionRuntime,
        ::formatPresentationDateTime,
        ::formatImportPreviewValue,
    )
    private val exportController = ExportController(context, keyProvider, runtimeSources, activeBookSessionRuntime)
    private val backupController = BackupController(context, keyProvider, runtimeSources, activeBookSessionRuntime, ::formatPresentationDateTime)
    private val restoreController = RestoreController(
        context,
        keyProvider,
        activeBookSessionRuntime,
        runtimeSources,
        ::formatPresentationDateTime,
    )
    private val schemaVersionMarker = LedgerSchemaVersionMarker(context)
    private val attachmentController = AttachmentController(
        context,
        bookAttachmentObjectPort,
        ::formatPresentationDateTime,
        ::formatPresentationFileSize,
    )
    val importWizard = importController.state
    val exportFlow: StateFlow<ExportFlowUiState> = exportController.state
    val backupFlow: StateFlow<BackupFlowUiState> = backupController.state
    val restoreFlow: StateFlow<RestoreFlowUiState> = restoreController.state
    val attachmentFlow: StateFlow<AttachmentFlowState> = attachmentController.state
    private val mutableOperationCenter = MutableStateFlow<OperationCenterLoadState>(OperationCenterLoadState.Loading)
    val operationCenter: StateFlow<OperationCenterLoadState> = mutableOperationCenter.asStateFlow()
    val analysis: StateFlow<AnalysisLoadState> = analysisController.state
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
    private val mutableCurrentRouteContentReadyGeneration = MutableStateFlow<Long?>(null)
    val currentRouteContentReadyGeneration: StateFlow<Long?> = mutableCurrentRouteContentReadyGeneration.asStateFlow()
    private val mutableTopLevelContentGenerations = MutableStateFlow<Map<TopLevelDestination, Long>>(emptyMap())
    private val mutableRecoveryRestoreActive = MutableStateFlow(false)
    val recoveryRestoreActive: StateFlow<Boolean> = mutableRecoveryRestoreActive.asStateFlow()
    private val mutableRecoveryBackupAvailable = MutableStateFlow<Boolean?>(null)
    val recoveryBackupAvailable: StateFlow<Boolean?> = mutableRecoveryBackupAvailable.asStateFlow()
    private val mutableMaintenancePresentation = MutableStateFlow<MaintenancePresentation?>(null)
    val maintenancePresentation: StateFlow<MaintenancePresentation?> = mutableMaintenancePresentation.asStateFlow()
    private val mutableOpeningPresentation = MutableStateFlow(OpeningPresentation.OPENING)
    val openingPresentation: StateFlow<OpeningPresentation> = mutableOpeningPresentation.asStateFlow()

    private val mutableAuthenticationRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val authenticationRequests = mutableAuthenticationRequests.asSharedFlow()

    private val mutableBackupVaultAuthenticationRequests = MutableSharedFlow<BiometricPrompt.CryptoObject>(extraBufferCapacity = 1)
    val backupVaultAuthenticationRequests = mutableBackupVaultAuthenticationRequests.asSharedFlow()

    private val mutableGlobalSnackbarMessages = MutableSharedFlow<GlobalSnackbarMessage>(extraBufferCapacity = 1)
    val globalSnackbarMessages = mutableGlobalSnackbarMessages.asSharedFlow()

    private val mutableSuccessHapticEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val successHapticEvents = mutableSuccessHapticEvents.asSharedFlow()

    val settings: StateFlow<LedgerAppSettings> = settingsRepository.data.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        LedgerAppSettings.getDefaultInstance(),
    )

    private val mutableReferenceData = MutableStateFlow<AppReferenceDataState>(AppReferenceDataState.Loading)
    val referenceData: StateFlow<AppReferenceDataState> = mutableReferenceData.asStateFlow()
    private val referenceRouteLoader = SingleFlightLoader<ReferenceDataLoadKey, ReferenceDataSnapshot>(
        viewModelScope,
        worker = { key ->
            val previous = (mutableReferenceData.value as? AppReferenceDataState.Content)?.snapshot
            val loaded = loadReferenceRouteSnapshot(
                key.bookId,
                key.screenId,
                key.accountId,
                key.categoryDirection,
                previous,
            )
            if (loaded == null) LoadResult.Failure("REFERENCE_ROUTE_FAILED") else LoadResult.Success(loaded)
        },
        publish = { key, state ->
            when (state) {
                is AsyncContent.Content -> if (
                    activeBookSessionRuntime.readyGeneration(key.bookId) == key.sessionGeneration &&
                    currentRouteScopeKey() == key.routeScope
                ) {
                    mutableReferenceData.value = AppReferenceDataState.Content(state.value)
                }
                AsyncContent.Empty,
                is AsyncContent.Failure,
                is AsyncContent.Loading,
                -> Unit
            }
        },
    )
    private val featureLoadRegistry = KeyedLoadRegistry<String, AppFeatureLoadKey>()
    private val featureLoadKeyLock = Any()
    private val completedFeatureLoadKeys = mutableMapOf<String, AppFeatureLoadKey>()
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
    private val refundSearchLoader = DebouncedSingleFlightLoader<RefundSearchLoadKey, DomainResult<RefundCandidatePage>>(
        viewModelScope,
        delayMillis = SEARCH_DEBOUNCE_MILLIS,
        worker = { key -> refundApplicationPort.candidates(key.bookId, key.query) },
        onTimeout = { key ->
            val current = mutableRefundPicker.value as? RefundPickerState.Content
            if (activeBookSessionRuntime.readyGeneration(key.bookId) == key.sessionGeneration && current?.query == key.query) {
                mutableRefundPicker.value = current.copy(searching = false)
            }
        },
        publish = { key, result ->
            val current = mutableRefundPicker.value as? RefundPickerState.Content
            if (activeBookSessionRuntime.readyGeneration(key.bookId) == key.sessionGeneration && current?.query == key.query) {
                mutableRefundPicker.value = when (result) {
                    is DomainResult.Success -> current.copy(
                        snapshot = current.snapshot.copy(originals = result.value.items),
                        searching = false,
                    )
                    is DomainResult.Failure -> current.copy(searching = false)
                }
            }
        },
    )
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
    private val journalSearchLoader = DebouncedSingleFlightLoader<JournalSearchLoadKey, JournalSearchLoadKey>(
        viewModelScope,
        delayMillis = SEARCH_DEBOUNCE_MILLIS,
        worker = { it },
        onTimeout = { key ->
            val ready = (mutableRootState.value as? AppRootState.Session)?.state as? BookSessionState.Ready
            val current = mutableJournal.value as? JournalLoadState.Content
            if (ready?.bookId == key.bookId && ready.generation == key.sessionGeneration && current?.filter == key.filter) {
                mutableJournal.value = current.copy(searchPending = false, searchResultReady = false)
                cancelJournalSearchTrace()
            }
        },
        publish = { key, _ ->
            val ready = (mutableRootState.value as? AppRootState.Session)?.state as? BookSessionState.Ready
            val current = mutableJournal.value as? JournalLoadState.Content
            if (ready?.bookId == key.bookId && ready.generation == key.sessionGeneration && current?.filter == key.filter) {
                journalSearchContentTrace?.close()
                journalSearchContentTrace = if (key.filter.searchText.isNullOrBlank()) {
                    null
                } else {
                    LedgerSessionPerformance.begin(LedgerInteractionOperation.SEARCH_CONTENT)
                }
                val request = JournalPagingRequest(
                    key.bookId,
                    key.filter,
                    refreshEpoch = (mutableJournalPagingRequest.value?.refreshEpoch ?: 0) + 1,
                )
                mutableJournal.value = current.copy(
                    pagingEpoch = request.refreshEpoch,
                    pageLoadedEpoch = null,
                )
                mutableJournalPagingRequest.value = request
            }
        },
    )
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
    private var creditDraftBaseline: app.ledger.feature.liabilities.CreditDraft? = null
    private var pendingCreditAccountDraft: AccountDraft? = null
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
    internal var liabilityCreditOnly: Boolean = false
        private set
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
    private var automationBlueprintDraftCache: BlueprintEditorDraft? = null
    private var automationRecurrenceDraftCache: app.ledger.feature.automation.RecurrenceEditorDraft? = null
    private var pendingCandidateId: StableId? = null
    private var pendingAutomationRuleDraft: app.ledger.feature.automation.RecurrenceEditorDraft? = null
    private val mutableProjectTransactionPagingRequest = MutableStateFlow<ProjectTransactionPagingRequest?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val journalPages = mutableJournalPagingRequest.flatMapLatest { request ->
        if (request == null) {
            flowOf(PagingData.empty())
        } else {
            Pager(
                PagingConfig(pageSize = 40, prefetchDistance = 10, initialLoadSize = 40, enablePlaceholders = false, maxSize = 200),
            ) {
                JournalPagingSource(
                    journalApplicationPort,
                    request.bookId,
                    request.filter,
                    request.runningBalanceAccountId,
                    onPageLoadStarted = { beginJournalPageTrace(request) },
                ) { successful ->
                    completeJournalPage(request, successful)
                }
            }.flow
        }
    }.cachedIn(viewModelScope)

    @OptIn(ExperimentalCoroutinesApi::class)
    val projectTransactionPages = mutableProjectTransactionPagingRequest.flatMapLatest { request ->
        if (request == null) {
            flowOf(PagingData.empty())
        } else {
            Pager(
                PagingConfig(pageSize = 40, prefetchDistance = 10, initialLoadSize = 40, enablePlaceholders = false, maxSize = 200),
            ) { ProjectTransactionPagingSource(projectGoalApplicationPort, request.bookId, request.projectId, request.kind) }.flow
        }
    }.cachedIn(viewModelScope)
    var selectedAccountType: UserAccountType = UserAccountType.CASH
    var replacementCardId: StableId? = null
        private set
    private var pendingCardAccountId: StableId? = null
    val preferredCardAccountId: StableId?
        get() = pendingCardAccountId

    private var onboardingState = OnboardingUiState()
    private var sessionManager: BookSessionManager? = null
    private val invalidatedLedgerScopes = MutableStateFlow<Map<LedgerDataScope, Long>>(emptyMap())
    private val financialCommitRegistration = FinancialCommitObserverRegistry.register(
        FinancialCommitObserver(::onFinancialCommitted),
    )
    private var restoreRecoveryRunning = false
    private var appLockController: AppLockController? = null
    private var applicationUnlockJob: Job? = null
    private var applicationUnlockGeneration: Long = 0L
    private var unlockToContentJob: Job? = null
    private var unlockToContentTrace: AutoCloseable? = null
    private var applicationLockJob: Job? = null
    private var postFirstContentJob: Job? = null
    private var routeInteractionJob: Job? = null
    private var routeRequestTrace: AutoCloseable? = null
    private var routeContentTrace: AutoCloseable? = null
    private var journalMetadataTrace: AutoCloseable? = null
    private var journalPageTrace: AutoCloseable? = null
    private var journalPresentationTrace: AutoCloseable? = null
    private var journalStageRequest: JournalPagingRequest? = null
    private var journalSearchRequestTrace: AutoCloseable? = null
    private var journalSearchContentTrace: AutoCloseable? = null
    private var journalBulkOptionsJob: Job? = null
    private var journalBulkOptionsGeneration: Long? = null
    private var presentedJournalPagingRequest: JournalPagingRequest? = null
    private var currentOrdinaryLoadKey: OrdinaryLoadKey? = null
    private var currentOrdinaryLoadJob: Job? = null
    private var currentBudgetLoadKey: BudgetLoadKey? = null
    private var currentBudgetLoadJob: Job? = null
    private var lastReferenceRouteLoadKey: String? = null
    private var unsavedContentLossNotice: Boolean = false
    val navigator: FiveStackNavigator = FiveStackNavigator()
    private var pendingDeepLink: LedgerDestinationKey? = null
    private var pendingWidgetQuickDeepLink: WidgetQuickDeepLink? = null
    private val scrollStates = mutableMapOf<TopLevelDestination, Pair<String, Int>>()
    private var recordLocationJob: Job? = null
    private var recordLocationGeneration: Long = 0L

    @Volatile private var recordLocationShouldRun: Boolean = false

    @Volatile private var recordLocationHostForeground: Boolean = true
    private var recordAttachmentImportJob: Job? = null
    private var pendingBatchAttachmentRowId: StableId? = null
    private var specializedAttachmentImportJob: Job? = null
    private var pendingRecordExit: PendingRecordExit? = null
    private var pendingGeneralExit: PendingGeneralExit? = null
    private val dirtyRouteScopes = mutableSetOf<String>()
    private val retainedFormValues = mutableMapOf<Pair<String, String>, Any?>()
    private val mutableGeneralUnsavedPrompt = MutableStateFlow(false)
    val generalUnsavedPrompt: StateFlow<Boolean> = mutableGeneralUnsavedPrompt.asStateFlow()
    val retainedFormStateStore: LedgerRetainedStateStore = object : LedgerRetainedStateStore {
        override fun read(scopeKey: String, stateKey: String): Any? = synchronized(retainedFormValues) {
            retainedFormValues[scopeKey to stateKey]
        }

        override fun write(scopeKey: String, stateKey: String, value: Any?) {
            synchronized(retainedFormValues) { retainedFormValues[scopeKey to stateKey] = value }
        }
    }
    private var pendingTransferSource: LedgerDestinationKey? = null

    init {
        viewModelScope.launch { start() }
    }

    private suspend fun start() {
        val saved = settingsRepository.current()
        if (!saved.onboardingComplete) {
            onboardingState = OnboardingUiState(
                step = saved.onboardingStep.toDomain(),
                language = languageFromTag(saved.languageTag.ifBlank { Locale.getDefault().toLanguageTag() }),
                baseCurrency = saved.baseCurrency.takeIf(String::isNotBlank) ?: DEFAULT_CURRENCY,
                zoneId = saved.zoneId.takeIf(String::isNotBlank) ?: ZoneId.systemDefault().id.takeIf { it == DEFAULT_ZONE },
                privacyAccepted = saved.privacyAccepted,
                telemetryEnabled = if (saved.diagnosticsChoiceRecorded) saved.telemetryEnabled else false,
                crashReportingEnabled = if (saved.diagnosticsChoiceRecorded) saved.crashReportingEnabled else false,
                deviceSecurityAvailable = AndroidKeystoreKeys(context).deviceSecurityCapability() != DeviceSecurityCapability.MISSING_DEVICE_CREDENTIAL,
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
    fun setCategoryIcon(value: app.ledger.core.designsystem.LedgerIcon) = updateOnboarding { copy(categoryIcon = value) }
    fun setCategoryPalette(paletteId: String, colorArgb: Int) = updateOnboarding {
        copy(categoryPaletteId = paletteId, categoryColorArgb = colorArgb)
    }

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
                it.diagnosticsChoiceRecorded = true
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
            iconKey = onboardingState.categoryIcon.name.lowercase(Locale.ROOT),
            colorArgb = onboardingState.categoryColorArgb,
        )
        initializationPort.createFirstCategory(requireBookId(saved), command).requireSuccess()
        settingsRepository.update { it.firstCategoryCreated = true }
        updateOnboarding { copy(firstCategoryCreated = true) }
    }

    private suspend fun openSavedBook(saved: LedgerAppSettings) {
        revisionCacheControl.clearAll()
        mutableOpeningPresentation.value = if (schemaVersionMarker.migrationExpected()) {
            OpeningPresentation.MIGRATION_DETECTED
        } else {
            OpeningPresentation.OPENING
        }
        if (!saved.securitySettingsInitialized) {
            settingsRepository.update {
                it.securitySettingsInitialized = true
                it.obscureRecentTasks = true
                it.trashRetentionDays = 30
            }
        }
        val bookId = requireBookId(saved)
        restoreNavigationIfAllowed(saved)
        unsavedContentLossNotice = settingsRepository.consumeUnsavedContentLoss()
        val manager = activeBookSessionRuntime.activate(bookId)
        sessionManager = manager
        val lockSettings = AppLockSettings(saved.appLockEnabled, timeout(saved.appLockTimeoutMillis))
        appLockController = AppLockController(lockSettings, SystemClock::elapsedRealtime) {
            vaultController.onApplicationLocked()
            attachmentController.close()
            lockApplicationSession(manager)
        }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            manager.state.collectLatest { state ->
                if (state !is BookSessionState.Ready) {
                    revisionCacheControl.clearBook(bookId)
                    mutableCurrentRouteContentReadyGeneration.value = null
                    mutableTopLevelContentGenerations.value = emptyMap()
                    cancelRouteInteractionTrace()
                    cancelJournalSearchTrace()
                    journalBulkOptionsJob?.cancel()
                    journalBulkOptionsJob = null
                    journalBulkOptionsGeneration = null
                    presentedJournalPagingRequest = null
                }
                publishSession(state)
                if (state is BookSessionState.Ready) {
                    runCatching { schemaVersionMarker.markCurrent() }
                    mutableOpeningPresentation.value = OpeningPresentation.OPENING
                    mutableMaintenancePresentation.value = null
                    mutableRecoveryBackupAvailable.value = null
                    consumePendingDeepLink()
                    loadReferenceData()
                    loadTopLevelMinimum(navigator.currentTopLevel)
                    trackTopLevelContent(bookId, state.generation, navigator.currentTopLevel, reportFullyDrawn = true)
                    schedulePostFirstContentWork(bookId, state.generation, navigator.currentTopLevel)
                } else if (
                    state is BookSessionState.Maintenance &&
                    state.reason == MaintenanceReason.UNFINISHED_OPERATION &&
                    !restoreRecoveryRunning
                ) {
                    mutableMaintenancePresentation.value = MaintenancePresentation.PREPARING
                    restoreRecoveryRunning = true
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            mutableMaintenancePresentation.value = MaintenancePresentation.RUNNING
                            manager.close()
                            if (withOfflinePrimaryMaintenance(manager) { restoreController.recoverInterrupted(bookId) }) {
                                mutableMaintenancePresentation.value = MaintenancePresentation.SUCCEEDED
                                delay(MAINTENANCE_RESULT_VISIBILITY_MILLIS)
                                manager.finishMaintenance()
                            } else {
                                mutableMaintenancePresentation.value = MaintenancePresentation.FAILED
                            }
                        } finally {
                            restoreRecoveryRunning = false
                        }
                    }
                } else if (state is BookSessionState.Maintenance) {
                    mutableMaintenancePresentation.value = when (state.reason) {
                        MaintenanceReason.PROJECTION_REBUILD -> MaintenancePresentation.NON_CANCELABLE
                        MaintenanceReason.CONTROLLED_MAINTENANCE -> MaintenancePresentation.CANCELABLE
                        MaintenanceReason.DATABASE_MIGRATION -> MaintenancePresentation.RUNNING
                        MaintenanceReason.UNFINISHED_OPERATION -> MaintenancePresentation.PREPARING
                    }
                } else if (state is BookSessionState.RecoveryRequired) {
                    revisionCacheControl.clearBook(bookId)
                    mutableRecoveryBackupAvailable.value = null
                    viewModelScope.launch(Dispatchers.IO) {
                        mutableRecoveryBackupAvailable.value = VerifiedBackupAvailabilityStore(context).hasVerifiedBackup(bookId)
                    }
                }
            }
        }
        manager.initialize()
        if (AppUnlockTransitionPolicy.mayOpenWithoutAuthentication(manager.state.value, saved.appLockEnabled)) {
            unlockUiAndTrackContent(manager)
        }
    }

    private suspend fun unlockUiAndTrackContent(manager: BookSessionManager) {
        cancelUnlockToContentTrace()
        val trace = LedgerSessionPerformance.begin(LedgerInteractionOperation.UNLOCK_TO_CONTENT)
        unlockToContentTrace = trace
        manager.unlockUi()
        val ready = manager.state.value as? BookSessionState.Ready
        if (ready == null) {
            trace.close()
            if (unlockToContentTrace === trace) unlockToContentTrace = null
            return
        }
        unlockToContentJob = viewModelScope.launch {
            try {
                mutableCurrentRouteContentReadyGeneration.filter { it == ready.generation }.first()
            } finally {
                trace.close()
                if (unlockToContentTrace === trace) {
                    unlockToContentTrace = null
                    unlockToContentJob = null
                }
            }
        }
    }

    private fun cancelUnlockToContentTrace() {
        unlockToContentJob?.cancel()
        unlockToContentJob = null
        unlockToContentTrace?.close()
        unlockToContentTrace = null
    }

    private suspend fun catchUpAutomation(bookId: StableId) {
        automationApplicationPort.catchUp(bookId, runtimeSources.clock.now())
        RecurrenceWorkScheduler.scheduleStartupCatchUpAndAwait(context, bookId)
    }

    private fun loadTopLevelMinimum(target: TopLevelDestination) {
        when (target) {
            TopLevelDestination.RECORD -> if (mutableOrdinaryRecord.value !is OrdinaryRecordLoadState.Content) {
                loadOrdinaryRecord()
            }
            // Journal owns a frame-first loading handshake in AppRootScreen. Starting its fast
            // metadata read here can replace Loading before Compose presents that bounded response,
            // forcing target-scale Paging content into the first navigation frame.
            TopLevelDestination.JOURNAL -> Unit
            TopLevelDestination.ACCOUNTS -> if (mutableReferenceData.value !is AppReferenceDataState.Content) {
                loadReferenceData()
            }
            // Budget and Analysis destinations own their route-keyed LaunchedEffect loaders.
            TopLevelDestination.BUDGET,
            TopLevelDestination.ANALYSIS,
            -> Unit
        }
    }

    private fun schedulePostFirstContentWork(
        bookId: StableId,
        generation: Long,
        topLevel: TopLevelDestination,
    ) {
        val application = context.applicationContext as? LedgerApplication ?: return
        if (application.hasStartedFirstInteractiveContentWork()) return
        postFirstContentJob?.cancel()
        postFirstContentJob = viewModelScope.launch {
            awaitTopLevelContent(topLevel, generation)
            if (
                activeBookSessionRuntime.readyGeneration(bookId) != generation ||
                navigator.currentTopLevel != topLevel
            ) {
                return@launch
            }
            application.onFirstInteractiveContent {
                catchUpAutomation(bookId)
                refreshWidgetSnapshotNow(bookId)
            }
        }
    }

    private fun trackTopLevelContent(
        bookId: StableId,
        generation: Long,
        topLevel: TopLevelDestination,
        reportFullyDrawn: Boolean,
    ) {
        cancelRouteInteractionTrace()
        val requestTrace = LedgerSessionPerformance.begin(LedgerInteractionOperation.ROUTE_REQUEST)
        val contentTrace = LedgerSessionPerformance.begin(LedgerInteractionOperation.ROUTE_CONTENT)
        routeRequestTrace = requestTrace
        routeContentTrace = contentTrace
        routeInteractionJob = viewModelScope.launch {
            try {
                awaitTopLevelContent(topLevel, generation)
                if (
                    activeBookSessionRuntime.readyGeneration(bookId) == generation &&
                    navigator.currentTopLevel == topLevel &&
                    reportFullyDrawn
                ) {
                    mutableCurrentRouteContentReadyGeneration.value = generation
                }
            } finally {
                contentTrace.close()
                requestTrace.close()
                if (routeContentTrace === contentTrace) routeContentTrace = null
                if (routeRequestTrace === requestTrace) routeRequestTrace = null
            }
        }
    }

    private suspend fun awaitTopLevelContent(topLevel: TopLevelDestination, generation: Long) {
        mutableTopLevelContentGenerations.filter { it[topLevel] == generation }.first()
    }

    private fun markTopLevelContent(bookId: StableId, topLevel: TopLevelDestination) {
        val generation = activeBookSessionRuntime.readyGeneration(bookId) ?: return
        mutableTopLevelContentGenerations.update { current -> current + (topLevel to generation) }
    }

    private fun cancelRouteInteractionTrace() {
        routeInteractionJob?.cancel()
        routeInteractionJob = null
        routeContentTrace?.close()
        routeContentTrace = null
        routeRequestTrace?.close()
        routeRequestTrace = null
        resetJournalRouteStageTraces()
    }

    private fun resetJournalRouteStageTraces() {
        journalMetadataTrace?.close()
        journalMetadataTrace = null
        journalPageTrace?.close()
        journalPageTrace = null
        journalPresentationTrace?.close()
        journalPresentationTrace = null
        journalStageRequest = null
    }

    @Suppress("ComplexCondition")
    private fun beginJournalPageTrace(request: JournalPagingRequest) {
        if (
            routeContentTrace == null ||
            mutableJournalPagingRequest.value != request ||
            journalStageRequest != request ||
            journalPageTrace != null
        ) {
            return
        }
        journalPageTrace = LedgerSessionPerformance.begin(LedgerInteractionOperation.JOURNAL_PAGE)
    }

    private fun beginJournalSearchTrace() {
        cancelJournalSearchTrace()
        journalSearchRequestTrace = LedgerSessionPerformance.begin(LedgerInteractionOperation.SEARCH_REQUEST)
    }

    private fun completeJournalPage(request: JournalPagingRequest, successful: Boolean) {
        if (mutableJournalPagingRequest.value != request) return
        if (journalStageRequest == request) {
            journalPageTrace?.close()
            journalPageTrace = null
            if (successful && journalPresentationTrace == null) {
                journalPresentationTrace = LedgerSessionPerformance.begin(
                    LedgerInteractionOperation.JOURNAL_PRESENTATION,
                )
            }
        }
        val current = mutableJournal.value as? JournalLoadState.Content
        if (
            successful &&
            current?.filter == request.filter &&
            current.pagingEpoch == request.refreshEpoch
        ) {
            mutableJournal.value = current.copy(pageLoadedEpoch = request.refreshEpoch)
        }
        completeJournalSearchTrace(request, successful)
    }

    fun onJournalPagePresented() {
        val request = mutableJournalPagingRequest.value ?: return
        if (presentedJournalPagingRequest == request) return
        val ready = (mutableRootState.value as? AppRootState.Session)?.state as? BookSessionState.Ready ?: return
        if (ready.bookId != request.bookId || navigator.currentTopLevel != TopLevelDestination.JOURNAL) return
        presentedJournalPagingRequest = request
        if (journalStageRequest == request) {
            journalPresentationTrace?.close()
            journalPresentationTrace = null
        }
        completeJournalRoutePresentation(request.bookId)
    }

    private fun completeJournalRoutePresentation(bookId: StableId) {
        markTopLevelContent(bookId, TopLevelDestination.JOURNAL)
        routeContentTrace?.close()
        routeContentTrace = null
        routeRequestTrace?.close()
        routeRequestTrace = null
    }

    private fun completeJournalSearchTrace(request: JournalPagingRequest, successful: Boolean) {
        if (request.filter.searchText.isNullOrBlank()) return
        val current = mutableJournal.value as? JournalLoadState.Content ?: return
        if (current.filter != request.filter) return
        mutableJournal.value = current.copy(searchPending = false, searchResultReady = successful)
        if (successful) {
            journalSearchContentTrace?.close()
            journalSearchContentTrace = null
            journalSearchRequestTrace?.close()
            journalSearchRequestTrace = null
        } else {
            cancelJournalSearchTrace()
        }
    }

    private fun cancelJournalSearchTrace() {
        journalSearchContentTrace?.close()
        journalSearchContentTrace = null
        journalSearchRequestTrace?.close()
        journalSearchRequestTrace = null
    }

    fun beginAuthentication() {
        val current = mutableRootState.value as? AppRootState.Session ?: return
        if (current.state != BookSessionState.Locked || current.authentication == AppAuthenticationState.AUTHENTICATING) return
        publishSession(current.state, AppAuthenticationState.AUTHENTICATING)
        mutableAuthenticationRequests.tryEmit(Unit)
    }

    fun authenticationSucceeded() {
        val current = mutableRootState.value as? AppRootState.Session ?: return
        if (!AppUnlockTransitionPolicy.mayConsumeSuccess(current.state, current.authentication)) return
        if (applicationUnlockJob?.isActive == true) return
        if (appLockController?.authenticationSucceeded() != true) return

        // Leave the lock screen synchronously; database opening is blocking work and must not
        // starve the main-thread state collector that renders the Opening/Ready transition.
        publishSession(BookSessionState.Opening)
        val manager = sessionManager ?: run {
            publishSession(BookSessionState.Locked, AppAuthenticationState.AUTH_FAILED)
            return
        }
        val generation = ++applicationUnlockGeneration
        val pendingLock = applicationLockJob
        applicationUnlockJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                pendingLock?.join()
                if (generation != applicationUnlockGeneration) return@launch
                when (manager.state.value) {
                    BookSessionState.Locked -> unlockUiAndTrackContent(manager)
                    BookSessionState.Opening -> Unit
                    is BookSessionState.Ready -> publishSession(manager.state.value)
                    else -> publishSession(manager.state.value)
                }
            } catch (_: Exception) {
                if (generation == applicationUnlockGeneration && manager.state.value == BookSessionState.Locked) {
                    publishSession(BookSessionState.Locked, AppAuthenticationState.AUTH_FAILED)
                }
            } finally {
                if (generation == applicationUnlockGeneration) applicationUnlockJob = null
            }
        }
    }

    fun authenticationFailed(error: AppAuthenticationError) {
        val current = mutableRootState.value as? AppRootState.Session ?: return
        if (!AppUnlockTransitionPolicy.mayConsumeFailure(current.state, current.authentication)) return
        val state = when (error) {
            AppAuthenticationError.LOCKED_OUT -> AppAuthenticationState.LOCKED_OUT
            AppAuthenticationError.FAILED,
            AppAuthenticationError.CANCELED,
            AppAuthenticationError.DEVICE_SECURITY_CHANGED,
            -> AppAuthenticationState.AUTH_FAILED
        }
        publishSession(current.state, state)
    }

    private fun lockApplicationSession(manager: BookSessionManager? = sessionManager) {
        manager?.let { revisionCacheControl.clearBook(it.bookId) }
        applicationUnlockGeneration++
        applicationUnlockJob?.cancel()
        applicationUnlockJob = null
        cancelUnlockToContentTrace()
        postFirstContentJob?.cancel()
        postFirstContentJob = null
        referenceRouteLoader.cancel()
        journalSearchLoader.reset()
        refundSearchLoader.reset()
        featureLoadRegistry.cancelAll()
        synchronized(featureLoadKeyLock) { completedFeatureLoadKeys.clear() }
        currentOrdinaryLoadJob?.cancel()
        currentBudgetLoadJob?.cancel()
        publishSession(BookSessionState.Locked)
        if (manager == null || applicationLockJob?.isActive == true) return
        applicationLockJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                manager.lockUi()
            } finally {
                applicationLockJob = null
            }
        }
    }

    fun onApplicationBackgrounded(vaultAuthenticationPromptActive: Boolean = false) {
        vaultController.onApplicationBackgrounded(vaultAuthenticationPromptActive)
        // Android's device-credential screen may stop this Activity even though the user has not
        // left the app. Starting the app-lock timeout here would immediately cancel the Vault
        // request through onApplicationLocked(), before BiometricPrompt can deliver success.
        if (!vaultAuthenticationPromptActive) appLockController?.onApplicationBackgrounded()
        viewModelScope.launch { persistNavigationIfAllowed() }
    }

    fun onApplicationForegrounded() {
        if (appLockController?.onApplicationForegrounded() == AppLockState.Locked) {
            lockApplicationSession()
        }
        val ready = (mutableRootState.value as? AppRootState.Session)?.state as? BookSessionState.Ready
        if (ready != null) refreshWidgetSnapshot(ready.bookId)
    }

    private fun refreshWidgetSnapshot(bookId: StableId) {
        viewModelScope.launch(Dispatchers.IO) {
            refreshWidgetSnapshotNow(bookId)
        }
    }

    private suspend fun refreshWidgetSnapshotNow(bookId: StableId) {
        val saved = settingsRepository.current()
        val zone = runCatching { ZoneId.of(saved.zoneId.ifBlank { DEFAULT_ZONE }) }.getOrDefault(ZoneId.of(DEFAULT_ZONE))
        val today = runtimeSources.clock.now().atZone(zone).toLocalDate()
        val refreshed = (widgetSnapshotRefreshApplicationPort.refreshIfStale(bookId, today) as? DomainResult.Success)?.value
        if (refreshed == true) LedgerWidgetRuntime.updateAll(context)
    }

    fun retryOpen() {
        val state = (mutableRootState.value as? AppRootState.Session)?.state
        if (state == BookSessionState.Locked) {
            viewModelScope.launch(Dispatchers.IO) { sessionManager?.unlockUi() }
        } else if (state is BookSessionState.RecoveryRequired && state.diagnosticCode == app.ledger.core.security.RecoveryDiagnosticCode.DATABASE_UNAVAILABLE) {
            viewModelScope.launch(Dispatchers.IO) {
                sessionManager?.close()
                sessionManager = null
                openSavedBook(settingsRepository.current())
            }
        }
    }

    fun openVault() {
        val ready = (mutableRootState.value as? AppRootState.Session)?.state as? BookSessionState.Ready ?: return
        val references = (mutableReferenceData.value as? AppReferenceDataState.Content)?.snapshot ?: return
        vaultController.openList(ready.bookId, references.cards)
        navigator.navigate(LedgerRouteContract.destination(ScreenId("VLT-001")), SessionGateState.READY)
    }

    fun openVaultCard(cardId: StableId) {
        vaultController.openCard(cardId)
        val screen = ScreenId("VLT-002")
        navigator.navigate(
            LedgerRouteContract.destination(screen, mapOf("cardId" to StableIdArgument(cardId))),
            SessionGateState.READY,
        )
    }

    fun openVaultEditor(cardId: StableId) {
        vaultController.openEditor(cardId)
        val screen = ScreenId("VLT-003")
        navigator.navigate(
            LedgerRouteContract.destination(screen, mapOf("cardId" to StableIdArgument(cardId))),
            SessionGateState.READY,
        )
    }

    fun revealVaultPrimaryNumber(cardId: StableId) = vaultController.requestRevealPrimaryNumber(cardId)
    fun copyVaultPrimaryNumber(cardId: StableId) = vaultController.requestCopyPrimaryNumber(cardId)
    fun revealVaultSecurityCode(cardId: StableId) = vaultController.requestRevealSecurityCode(cardId)
    fun hideVaultSensitive() = vaultController.hideSensitive()
    fun authenticateVaultList() = vaultController.requestListAuthentication()
    fun openVaultCards() {
        navigator.navigate(LedgerRouteContract.destination(ScreenId("ACC-001")), SessionGateState.READY)
    }
    fun authenticateVaultEdit(cardId: StableId) = vaultController.requestEditAuthentication(cardId)
    fun saveVault(cardId: StableId, submission: VaultEditSubmission) = vaultController.save(cardId, submission)
    fun vaultAuthenticationSucceeded(cryptoObject: BiometricPrompt.CryptoObject?) = vaultController.authenticationSucceeded(cryptoObject)
    fun vaultAuthenticationFailed(error: BiometricErrorCode) = vaultController.authenticationFailed(error)

    fun openSecurityPrivacySettings(screenId: String) {
        require(screenId in app.ledger.feature.settings.SUPPORTED_SECURITY_SETTINGS_SCREENS)
        refreshSecurityPrivacy(screenId)
        navigator.navigate(LedgerRouteContract.destination(ScreenId(screenId)), SessionGateState.READY)
    }

    fun refreshSecurityPrivacy(screenId: String = mutableSecurityPrivacy.value.screenId) {
        viewModelScope.launch(Dispatchers.IO) {
            val saved = settingsRepository.current()
            val snapshot = TelemetryRuntime.snapshot()
            val deviceSecurityCapability = AndroidKeystoreKeys(context).deviceSecurityCapability()
            val deviceSecurity = deviceSecurityCapability != DeviceSecurityCapability.MISSING_DEVICE_CREDENTIAL
            val appLockTimeout = when (saved.appLockTimeoutMillis) {
                0L -> SettingsAppLockTimeout.IMMEDIATE
                60_000L -> SettingsAppLockTimeout.ONE_MINUTE
                300_000L -> SettingsAppLockTimeout.FIVE_MINUTES
                900_000L -> SettingsAppLockTimeout.FIFTEEN_MINUTES
                else -> SettingsAppLockTimeout.CUSTOM
            }
            val presentation = when (screenId) {
                "SETG-006" -> when {
                    !deviceSecurity -> SecuritySettingsRequiredState.SETG_006_DEVICE_SECURITY_MISSING
                    saved.appLockEnabled -> SecuritySettingsRequiredState.SETG_006_ENABLED
                    else -> SecuritySettingsRequiredState.SETG_006_DISABLED
                }
                "SETG-007" -> SecuritySettingsRequiredState.SETG_007_CONTENT
                "SETG-008" -> SecuritySettingsRequiredState.SETG_008_CONTENT
                "SETG-009" -> when {
                    !saved.privacyAccepted -> SecuritySettingsRequiredState.SETG_009_PRE_CONSENT
                    saved.telemetryEnabled || saved.crashReportingEnabled -> SecuritySettingsRequiredState.SETG_009_ENABLED
                    else -> SecuritySettingsRequiredState.SETG_009_DISABLED
                }
                "SETG-010" -> if (snapshot?.featureEvents.isNullOrEmpty()) SecuritySettingsRequiredState.SETG_010_EMPTY else SecuritySettingsRequiredState.SETG_010_CONTENT
                "SETG-011" -> if (snapshot?.crashEvents.isNullOrEmpty()) SecuritySettingsRequiredState.SETG_011_EMPTY else SecuritySettingsRequiredState.SETG_011_CONTENT
                "CLR-001" -> mutableSecurityPrivacy.value.presentation.takeIf { it.screenId == "CLR-001" }
                    ?: SecuritySettingsRequiredState.CLR_001_CONTENT
                "SYS-004" -> if (deviceSecurity) SecuritySettingsRequiredState.SYS_004_CONFIGURED else SecuritySettingsRequiredState.SYS_004_MISSING
                else -> error("unsupported security settings screen")
            }
            mutableSecurityPrivacy.value = SecurityPrivacySettingsState(
                screenId = screenId,
                presentation = presentation,
                appLockEnabled = saved.appLockEnabled,
                appLockTimeout = appLockTimeout,
                customTimeoutMinutes = (saved.appLockTimeoutMillis / 60_000L).toInt().coerceIn(1, 1_440),
                deviceSecurityConfigured = deviceSecurity,
                authenticationCapability = when (deviceSecurityCapability) {
                    DeviceSecurityCapability.BIOMETRIC_OR_CREDENTIAL -> app.ledger.feature.settings.DeviceAuthenticationCapability.STRONG_BIOMETRIC_OR_DEVICE_CREDENTIAL
                    DeviceSecurityCapability.DEVICE_CREDENTIAL_ONLY -> app.ledger.feature.settings.DeviceAuthenticationCapability.DEVICE_CREDENTIAL_ONLY
                    DeviceSecurityCapability.MISSING_DEVICE_CREDENTIAL -> app.ledger.feature.settings.DeviceAuthenticationCapability.UNAVAILABLE
                },
                globalScreenshotBlocked = saved.globalFlagSecure,
                obscureRecentTasks = if (saved.securitySettingsInitialized) saved.obscureRecentTasks else true,
                trashRetention = TrashRetention.entries.singleOrNull { it.days == saved.trashRetentionDays } ?: TrashRetention.CUSTOM,
                customTrashRetentionDays = saved.trashRetentionDays.coerceIn(
                    SecurityPrivacySettingsState.MINIMUM_TRASH_RETENTION_DAYS,
                    SecurityPrivacySettingsState.MAXIMUM_TRASH_RETENTION_DAYS,
                ),
                privacyAccepted = saved.privacyAccepted,
                telemetryEnabled = if (saved.diagnosticsChoiceRecorded) saved.telemetryEnabled else false,
                crashEnabled = if (saved.diagnosticsChoiceRecorded) saved.crashReportingEnabled else false,
                featureRows = snapshot?.featureEvents.orEmpty().map { entry ->
                    FeatureQueueRow(
                        entry.occurredAtEpochMillis,
                        entry.event.name.name,
                        entry.event.entry.name,
                        entry.event.outcome.name,
                        entry.event.duration.name,
                        entry.event.errorCode.name,
                    )
                },
                crashRows = snapshot?.crashEvents.orEmpty().map { entry ->
                    CrashQueueRow(
                        entry.occurredAtEpochMillis,
                        entry.diagnostic.kind.name,
                        entry.diagnostic.errorCode.name,
                        entry.diagnostic.frames.size,
                    )
                },
                zoneId = saved.zoneId.ifBlank { DEFAULT_ZONE },
                localClearAuthenticationPending = mutableSecurityPrivacy.value.localClearAuthenticationPending,
                errorCode = mutableSecurityPrivacy.value.errorCode,
            )
        }
    }

    fun updateAppLockEnabled(enabled: Boolean) {
        if (enabled) {
            if (AndroidKeystoreKeys(context).deviceSecurityCapability() == DeviceSecurityCapability.MISSING_DEVICE_CREDENTIAL) {
                openSecurityPrivacySettings("SYS-004")
            } else {
                requestSensitiveSettingsAuthentication(SensitiveSettingsAuthenticationPurpose.ENABLE_APP_LOCK)
            }
        } else {
            viewModelScope.launch(Dispatchers.IO) {
                val updated = settingsRepository.update { it.appLockEnabled = false }
                appLockController?.updateSettings(AppLockSettings(false, timeout(updated.appLockTimeoutMillis)), authenticated = false)
                refreshSecurityPrivacy("SETG-006")
            }
        }
    }

    fun updateAppLockTimeout(value: SettingsAppLockTimeout, customMinutes: Int) {
        val millis = if (value == SettingsAppLockTimeout.CUSTOM) customMinutes.coerceIn(1, 1_440) * 60_000L else value.millis
        viewModelScope.launch(Dispatchers.IO) {
            val updated = settingsRepository.update { it.appLockTimeoutMillis = millis }
            appLockController?.updateSettings(AppLockSettings(updated.appLockEnabled, timeout(millis)), authenticated = false)
            refreshSecurityPrivacy("SETG-006")
        }
    }

    fun testAppLock() {
        vaultController.onApplicationLocked()
        attachmentController.close()
        appLockController?.forceLock()
    }

    fun updateGlobalScreenshotBlocked(enabled: Boolean) = updateSecuritySetting("SETG-007") { it.globalFlagSecure = enabled }
    fun updateObscureRecentTasks(enabled: Boolean) = updateSecuritySetting("SETG-007") { it.obscureRecentTasks = enabled }
    fun updateTrashRetention(retention: TrashRetention) {
        if (retention == TrashRetention.CUSTOM) return
        updateSecuritySetting("SETG-008") { it.trashRetentionDays = retention.days }
    }

    fun updateCustomTrashRetention(days: Int) = updateSecuritySetting("SETG-008") {
        it.trashRetentionDays = days.coerceIn(
            SecurityPrivacySettingsState.MINIMUM_TRASH_RETENTION_DAYS,
            SecurityPrivacySettingsState.MAXIMUM_TRASH_RETENTION_DAYS,
        )
    }

    fun remainingSettingsState(screenId: String): RemainingSettingsState {
        require(screenId in REMAINING_SETTINGS_SCREENS)
        val saved = settings.value
        val locale = Locale.forLanguageTag(saved.languageTag.ifBlank { "zh-CN" })
        val zone = runCatching { ZoneId.of(saved.zoneId.ifBlank { DEFAULT_ZONE }) }.getOrDefault(ZoneId.of(DEFAULT_ZONE))
        val date = runtimeSources.clock.now().atZone(zone).toLocalDate()
        val dateFormat = saved.dateFormat.toFeature()
        return RemainingSettingsState(
            screenId = screenId,
            themeMode = saved.themeMode.toFeature(),
            dynamicColor = saved.dynamicColorEnabled,
            defaultAmountsHidden = saved.defaultAmountsHidden,
            reduceMotion = saved.reduceMotionEnabled,
            languageTag = saved.languageTag.ifBlank { "zh-CN" },
            dateFormat = dateFormat,
            numberFormatSummary = NumberFormat.getNumberInstance(locale).format(12_345.67),
            zoneId = zone.id,
            availableZoneIds = availableZoneIds,
            weekStart = saved.weekStart.toFeature(),
            datePreview = formatSettingsDate(date, dateFormat, locale),
            appVersion = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
                .orEmpty().ifBlank { "0" },
            licenses = OPEN_SOURCE_NOTICES,
        )
    }

    fun updateSettingsThemeMode(value: SettingsThemeMode) = updateRemainingSettings { it.themeMode = value.toProto() }
    fun updateSettingsDynamicColor(value: Boolean) = updateRemainingSettings { it.dynamicColorEnabled = value }
    fun updateSettingsDefaultAmountsHidden(value: Boolean) = updateRemainingSettings { it.defaultAmountsHidden = value }
    fun updateSettingsReduceMotion(value: Boolean) = updateRemainingSettings { it.reduceMotionEnabled = value }
    fun updateSettingsLanguage(value: String) {
        if (value !in SUPPORTED_LANGUAGE_TAGS) return
        updateRemainingSettings { it.languageTag = value }
    }
    fun updateSettingsDateFormat(value: SettingsDateFormat) = updateRemainingSettings { it.dateFormat = value.toProto() }
    fun updateSettingsWeekStart(value: SettingsWeekStart) = updateRemainingSettings { it.weekStart = value.toProto() }
    fun updateSettingsZone(value: String) {
        val zone = runCatching { ZoneId.of(value) }.getOrNull() ?: return
        updateRemainingSettings { it.zoneId = zone.id }
    }

    fun openSourceCode() {
        mutableExternalLinkRequests.tryEmit(Uri.parse(SOURCE_CODE_URL))
    }

    fun externalApplicationUnavailable() {
        mutableGlobalSnackbarMessages.tryEmit(GlobalSnackbarMessage.EXTERNAL_APP_UNAVAILABLE)
    }

    fun openTransferHub(source: LedgerDestinationKey) {
        if (
            OperationNotificationCoordinator.permissionStatus(context) == NotificationPermissionStatus.REQUIRED &&
            !settings.value.notificationPermissionExplained
        ) {
            pendingTransferSource = source
            navigator.navigate(LedgerRouteContract.destination(ScreenId("SYS-002")), SessionGateState.READY)
        } else {
            navigator.navigate(LedgerRouteContract.destination(ScreenId("TRF-001")), SessionGateState.READY)
        }
    }

    fun notificationPermissionPresentation(): NotificationPermissionPresentation = when {
        OperationNotificationCoordinator.permissionStatus(context) == NotificationPermissionStatus.GRANTED ->
            NotificationPermissionPresentation.GRANTED
        settings.value.notificationPermissionExplained -> NotificationPermissionPresentation.DENIED
        else -> NotificationPermissionPresentation.FIRST_ASK
    }

    fun requestNotificationPermission() {
        viewModelScope.launch {
            settingsRepository.update { it.notificationPermissionExplained = true }
            mutableNotificationPermissionRequests.emit(Unit)
        }
    }

    fun notificationPermissionResult(granted: Boolean) {
        if (granted || settings.value.notificationPermissionExplained) finishNotificationPermissionFlow()
    }

    fun dismissNotificationPermission() {
        viewModelScope.launch {
            settingsRepository.update { it.notificationPermissionExplained = true }
            finishNotificationPermissionFlow()
        }
    }

    fun openNotificationSettings() {
        mutableOpenNotificationSettingsRequests.tryEmit(Unit)
    }

    private fun finishNotificationPermissionFlow() {
        val source = pendingTransferSource
        pendingTransferSource = null
        if (source != null) {
            navigator.navigate(LedgerRouteContract.destination(ScreenId("TRF-001")), SessionGateState.READY)
        } else if (navigator.currentKey.contract.screenId.value == "SYS-002") {
            navigator.pop()
        }
    }

    fun loadOperationCenter() {
        val ready = (mutableRootState.value as? AppRootState.Session)?.state as? BookSessionState.Ready ?: return
        val previous = mutableOperationCenter.value as? OperationCenterLoadState.Content
        val lease = beginFeatureLoad(
            FEATURE_LOAD_OPERATION_CENTER,
            AppFeatureLoadKey(ready.bookId, ready.generation, "SYS-002"),
            previous != null,
        ) {
            mutableOperationCenter.value = previous ?: OperationCenterLoadState.Failure("OPERATION_LOAD_TIMEOUT")
        } ?: return
        if (previous == null) mutableOperationCenter.value = OperationCenterLoadState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            val bookId = runCatching { requireBookId(settingsRepository.current()) }.getOrNull()
            if (bookId == null) {
                publishFeatureLoad(lease, reusable = false) {
                    mutableOperationCenter.value = previous ?: OperationCenterLoadState.Failure("OPERATION_BOOK_UNAVAILABLE")
                }
                return@launch
            }
            val repository = SqlCipherBackgroundOperationRepository(
                bookId,
                SecurePrimaryLedgerAccess(context, keyProvider, activeBookSessionRuntime),
            )
            val loaded = when (val result = repository.list()) {
                is DomainResult.Success -> OperationCenterLoadState.Content(result.value)
                is DomainResult.Failure -> previous ?: OperationCenterLoadState.Failure(sanitizeCode(result.error.code))
            }
            publishFeatureLoad(lease, reusable = loaded is OperationCenterLoadState.Content) {
                mutableOperationCenter.value = loaded
            }
        }
    }

    fun cancelOperation(operationId: BackgroundOperationId) {
        viewModelScope.launch(Dispatchers.IO) {
            val bookId = runCatching { requireBookId(settingsRepository.current()) }.getOrNull() ?: return@launch
            val repository = SqlCipherBackgroundOperationRepository(
                bookId,
                SecurePrimaryLedgerAccess(context, keyProvider, activeBookSessionRuntime),
            )
            val operation = (repository.get(operationId) as? DomainResult.Success)?.value ?: return@launch
            if (operation.state !in CANCELABLE_OPERATION_STATES) return@launch
            val cancelling = (operation.transition(BackgroundOperationState.CANCEL_REQUESTED, runtimeSources.clock.now()) as? DomainResult.Success)
                ?.value ?: return@launch
            if (repository.save(cancelling) !is DomainResult.Success) return@launch
            when (operation.type) {
                BackgroundOperationType.IMPORT -> {
                    ImportRunControlRegistry.cancel(operation.id.value)
                }
                BackgroundOperationType.EXPORT -> {
                    ExportRunControlRegistry.cancel(operation.id.value)
                }
                BackgroundOperationType.FULL_BACKUP,
                BackgroundOperationType.DRIVE_UPLOAD,
                BackgroundOperationType.BACKUP_KEY_ROTATION,
                -> {
                    BackupRunControlRegistry.cancel(operation.id.value)
                }
                BackgroundOperationType.RESTORE_REPLACE,
                BackgroundOperationType.RESTORE_MERGE,
                -> restoreController.cancel()
                BackgroundOperationType.ATTACHMENT_MIGRATION,
                BackgroundOperationType.DATABASE_MAINTENANCE,
                -> Unit
            }
            loadOperationCenter()
        }
    }

    fun retryOperation(operationId: BackgroundOperationId) {
        viewModelScope.launch(Dispatchers.IO) {
            val bookId = runCatching { requireBookId(settingsRepository.current()) }.getOrNull() ?: return@launch
            val repository = SqlCipherBackgroundOperationRepository(
                bookId,
                SecurePrimaryLedgerAccess(context, keyProvider, activeBookSessionRuntime),
            )
            val operation = (repository.get(operationId) as? DomainResult.Success)?.value ?: return@launch
            if (!operation.canRetryFromOperationCenter()) return@launch
            if (operation.type == BackgroundOperationType.IMPORT) {
                val zone = runCatching { ZoneId.of(settings.value.zoneId.ifBlank { DEFAULT_ZONE }) }
                    .getOrDefault(ZoneId.of(DEFAULT_ZONE))
                if (!importController.restoreAndRetry(bookId, zone, operation)) return@launch
                withContext(Dispatchers.Main.immediate) { navigateImportStage() }
                importController.awaitRestoredRetry()
                withContext(Dispatchers.Main.immediate) { navigateImportStage() }
                return@launch
            }
            val queued = (
                operation.transition(
                    BackgroundOperationState.QUEUED,
                    runtimeSources.clock.now(),
                    errorCode = null,
                ) as? DomainResult.Success
                )?.value ?: return@launch
            if (repository.save(queued) !is DomainResult.Success) return@launch
            when (operation.type) {
                BackgroundOperationType.EXPORT -> ExportWorkScheduler.enqueue(context, operation.id.value, remoteProvider = false)
                BackgroundOperationType.FULL_BACKUP,
                BackgroundOperationType.DRIVE_UPLOAD,
                BackgroundOperationType.BACKUP_KEY_ROTATION,
                -> {
                    val configuration = runCatching {
                        app.ledger.transfer.data.BackupConfigurationStore(context, keyProvider).read(bookId)
                    }.getOrNull()
                    BackupWorkScheduler.enqueue(
                        context,
                        operation.id.value,
                        drive = configuration?.repositoryKind == BackupRepositoryKind.GOOGLE_DRIVE ||
                            operation.type == BackgroundOperationType.DRIVE_UPLOAD,
                        userInitiated = true,
                        unmetered = configuration?.policy?.networkPolicy == BackupNetworkPolicy.UNMETERED,
                        replaceExisting = true,
                    )
                }
                else -> return@launch
            }
            loadOperationCenter()
        }
    }

    fun openImportSourceFromOperationCenter() {
        importController.startOver()
        navigator.navigate(LedgerRouteContract.destination(ScreenId("IMP-001")), SessionGateState.READY)
    }

    private fun updateRemainingSettings(update: (LedgerAppSettings.Builder) -> Unit) {
        viewModelScope.launch {
            runCatching { settingsRepository.update(update) }
                .onFailure { mutableGlobalSnackbarMessages.tryEmit(GlobalSnackbarMessage.SETTINGS_WRITE_FAILED) }
        }
    }

    private fun updateSecuritySetting(screenId: String, update: (LedgerAppSettings.Builder) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.update {
                it.securitySettingsInitialized = true
                update(it)
            }
            refreshSecurityPrivacy(screenId)
        }
    }

    fun updateTelemetryEnabled(enabled: Boolean) = updateDiagnosticConsent(feature = enabled, crash = null)
    fun updateCrashEnabled(enabled: Boolean) = updateDiagnosticConsent(feature = null, crash = enabled)

    private fun updateDiagnosticConsent(feature: Boolean?, crash: Boolean?) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = settingsRepository.update {
                it.diagnosticsChoiceRecorded = true
                feature?.let { value -> it.telemetryEnabled = value }
                crash?.let { value -> it.crashReportingEnabled = value }
            }
            TelemetryRuntime.applyConsent(updated.privacyAccepted, updated.telemetryEnabled, updated.crashReportingEnabled)
            refreshSecurityPrivacy("SETG-009")
        }
    }

    fun deleteFeatureDiagnosticQueue() {
        TelemetryRuntime.deleteFeatureQueue()
        refreshSecurityPrivacy("SETG-010")
    }

    fun deleteCrashDiagnosticQueue() {
        TelemetryRuntime.deleteCrashQueue()
        refreshSecurityPrivacy("SETG-011")
    }

    fun beginLocalClear() {
        refreshLocalClear(SecuritySettingsRequiredState.CLR_001_CONFIRMING)
    }

    fun cancelLocalClear() {
        pendingSensitiveSettingsPurpose = null
        refreshLocalClear(SecuritySettingsRequiredState.CLR_001_CONTENT)
    }

    fun confirmLocalClear() {
        refreshLocalClear(SecuritySettingsRequiredState.CLR_001_CONFIRMING, authenticationPending = true)
        requestSensitiveSettingsAuthentication(SensitiveSettingsAuthenticationPurpose.CLEAR_LOCAL)
    }

    private fun requestSensitiveSettingsAuthentication(purpose: SensitiveSettingsAuthenticationPurpose) {
        check(pendingSensitiveSettingsPurpose == null) { "a sensitive settings authentication is already pending" }
        pendingSensitiveSettingsPurpose = purpose
        mutableSensitiveSettingsAuthenticationRequests.tryEmit(purpose)
    }

    fun sensitiveSettingsAuthenticationSucceeded() {
        when (pendingSensitiveSettingsPurpose.also { pendingSensitiveSettingsPurpose = null }) {
            SensitiveSettingsAuthenticationPurpose.ENABLE_APP_LOCK -> viewModelScope.launch(Dispatchers.IO) {
                val updated = settingsRepository.update { it.appLockEnabled = true }
                appLockController?.updateSettings(AppLockSettings(true, timeout(updated.appLockTimeoutMillis)), authenticated = true)
                appLockController?.authenticationSucceeded()
                refreshSecurityPrivacy("SETG-006")
            }
            SensitiveSettingsAuthenticationPurpose.CLEAR_LOCAL -> {
                refreshLocalClear(SecuritySettingsRequiredState.CLR_001_CLEARING)
                clearLocalBookDataAuthenticated()
            }
            SensitiveSettingsAuthenticationPurpose.DELETE_CLOUD -> loadCloudBackupsForDeletionAuthenticated()
            null -> Unit
        }
    }

    fun sensitiveSettingsAuthenticationFailed() {
        val purpose = pendingSensitiveSettingsPurpose
        pendingSensitiveSettingsPurpose = null
        if (purpose == SensitiveSettingsAuthenticationPurpose.CLEAR_LOCAL) {
            refreshLocalClear(SecuritySettingsRequiredState.CLR_001_FAILED, "AUTHENTICATION_REJECTED")
        }
    }

    fun openSystemSecuritySettings() {
        mutableOpenSystemSecurityRequests.tryEmit(Unit)
    }

    fun deviceSecurityConfigured() {
        if (AndroidKeystoreKeys(context).deviceSecurityCapability() == DeviceSecurityCapability.MISSING_DEVICE_CREDENTIAL) {
            refreshSecurityPrivacy("SYS-004")
        } else {
            requestRootBack()
        }
    }

    fun screenVisibilityChanged(screenId: String) {
        val saved = settings.value
        mutableScreenPrivacyPolicy.value = ScreenPrivacyPolicy(
            obscureRecentTasks = if (saved.securitySettingsInitialized) saved.obscureRecentTasks else true,
            globalFlagSecure = saved.globalFlagSecure,
            vaultVisible = screenId.startsWith("VLT-"),
            applicationInBackground = false,
        )
        if (screenId.startsWith("VLT-")) vaultController.synchronizeVisibleScreen(screenId)
        if (!screenId.startsWith("VLT-") && vault.value.screenId.startsWith("VLT-")) vaultController.hideSensitive(autoHidden = false)
        val routeLoadKey = currentRouteScopeKey()
        if (screenId.requiresReferenceRouteData() && lastReferenceRouteLoadKey != routeLoadKey) {
            lastReferenceRouteLoadKey = routeLoadKey
            loadReferenceRouteData(screenId)
        }
    }

    private fun refreshLocalClear(
        presentation: SecuritySettingsRequiredState,
        errorCode: String? = null,
        authenticationPending: Boolean = false,
    ) {
        mutableSecurityPrivacy.value = mutableSecurityPrivacy.value.copy(
            screenId = "CLR-001",
            presentation = presentation,
            localClearAuthenticationPending = authenticationPending,
            errorCode = errorCode,
        )
    }

    fun clearLocalBookData() = requestSensitiveSettingsAuthentication(SensitiveSettingsAuthenticationPurpose.CLEAR_LOCAL)

    private fun clearLocalBookDataAuthenticated() {
        viewModelScope.launch(Dispatchers.IO) {
            val saved = settingsRepository.current()
            val bookId = saved.bookId.toByteArray().takeIf { it.size == StableId.BYTE_COUNT }
                ?.let { StableId.fromBytes(it).getOrNull() }
            val backupConfiguration = bookId?.let { id ->
                runCatching { app.ledger.transfer.data.BackupConfigurationStore(context, keyProvider).read(id) }.getOrNull()
            }
            val workStopped = runCatching {
                WorkManager.getInstance(context).cancelAllWork().result.get()
                true
            }.getOrDefault(false)
            if (!workStopped) {
                mutableGlobalSnackbarMessages.tryEmit(GlobalSnackbarMessage.LOCAL_CLEAR_FAILED)
                refreshLocalClear(SecuritySettingsRequiredState.CLR_001_FAILED, "WORK_CANCELLATION_FAILED")
                return@launch
            }
            sessionManager?.close()
            sessionManager = null
            revisionCacheControl.clearAll()
            val cleared = bookId?.let { initializationPort.clearLocalBook(it) }
            if (cleared !is DomainResult.Success) {
                mutableGlobalSnackbarMessages.tryEmit(GlobalSnackbarMessage.LOCAL_CLEAR_FAILED)
                refreshLocalClear(SecuritySettingsRequiredState.CLR_001_FAILED, "LEDGER_CLEAR_FAILED")
                openSavedBook(saved)
                return@launch
            }
            val artifactsCleared = LocalBookArtifactCleaner(context, keyProvider).clear(bookId, backupConfiguration)
            TelemetryRuntime.deleteAllLocal()
            settingsRepository.reset()
            onboardingState = OnboardingUiState(
                baseCurrency = DEFAULT_CURRENCY,
                zoneId = ZoneId.systemDefault().id.takeIf { it == DEFAULT_ZONE },
                deviceSecurityAvailable = AndroidKeystoreKeys(context).deviceSecurityCapability() != DeviceSecurityCapability.MISSING_DEVICE_CREDENTIAL,
            )
            withContext(Dispatchers.Main.immediate) { publishOnboarding() }
            if (!artifactsCleared) mutableGlobalSnackbarMessages.tryEmit(GlobalSnackbarMessage.LOCAL_CLEAR_FAILED)
        }
    }

    private fun defaultSecurityPrivacyState(): SecurityPrivacySettingsState = SecurityPrivacySettingsState(
        "SETG-006",
        SecuritySettingsRequiredState.SETG_006_DISABLED,
    )

    /** Parses only closed deep-link targets and keeps them in memory behind SessionGate. */
    fun handleDeepLink(uri: Uri?) {
        val value = uri ?: return
        if (value.scheme == DEEP_LINK_SCHEME && value.host == WIDGET_DEEP_LINK_HOST) {
            val widget = parseWidgetDeepLink(value) ?: return
            when (widget) {
                is WidgetDeepLink.Destination -> pendingDeepLink = widget.destination
                is WidgetDeepLink.Quick -> pendingWidgetQuickDeepLink = widget.value
            }
        } else {
            pendingDeepLink = parseDeepLink(value) ?: return
        }
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

    private fun parseWidgetDeepLink(uri: Uri): WidgetDeepLink? = runCatching {
        require(uri.scheme == DEEP_LINK_SCHEME && uri.host == WIDGET_DEEP_LINK_HOST && uri.query == null)
        val segments = uri.pathSegments
        when (segments.firstOrNull()) {
            "quick" -> {
                require(segments.size == 4)
                val kind = WidgetQuickTargetKind.valueOf(segments[1])
                val direction = WidgetQuickDirection.valueOf(segments[2])
                val id = StableId.parse(segments[3]).getOrNull() ?: error("invalid stable identifier")
                WidgetDeepLink.Quick(WidgetQuickDeepLink(kind, direction, id))
            }
            "open" -> WidgetDeepLink.Destination(parseWidgetDestination(segments.drop(1)))
            else -> error("unknown widget deep link")
        }
    }.getOrNull()

    private fun parseWidgetDestination(segments: List<String>): LedgerDestinationKey {
        val screen = segments.firstOrNull() ?: error("missing widget destination")
        val screenId = ScreenId(screen)
        return when (screen) {
            "ACC-001", "ANA-001", "BUD-001" -> {
                require(segments.size == 1)
                LedgerRouteContract.destination(screenId)
            }
            "ACC-005", "CRD-001", "GOL-003" -> {
                require(segments.size == 2)
                val stableId = StableId.parse(segments[1]).getOrNull() ?: error("invalid stable identifier")
                val parameter = if (screen == "GOL-003") "goalId" else "accountId"
                LedgerRouteContract.destination(screenId, mapOf(parameter to StableIdArgument(stableId)))
            }
            "ANA-003" -> {
                require(segments == listOf("ANA-003", WIDGET_CONSUMPTION_REPORT_KEY))
                LedgerRouteContract.destination(
                    screenId,
                    mapOf(
                        "reportKey" to LedgerRouteContract.opaqueKeyArgument(
                            screenId,
                            "reportKey",
                            WIDGET_CONSUMPTION_REPORT_KEY,
                        ),
                    ),
                )
            }
            else -> error("widget destination is not allowlisted")
        }
    }

    fun updateScrollState(topLevel: TopLevelDestination, stableKey: String, offset: Int) {
        require(Regex("[A-Za-z][A-Za-z0-9._-]{0,63}").matches(stableKey))
        require(offset >= 0)
        scrollStates[topLevel] = stableKey to offset
    }

    fun scrollState(topLevel: TopLevelDestination): Pair<String, Int>? = scrollStates[topLevel]

    fun dismissUnsavedContentLossNotice() {
        unsavedContentLossNotice = false
        val state = (mutableRootState.value as? AppRootState.Session)?.state ?: return
        publishSession(state)
    }

    fun loadReferenceData() {
        if ((mutableRootState.value as? AppRootState.Session)?.state !is BookSessionState.Ready) return
        viewModelScope.launch(Dispatchers.IO) {
            val bookId = runCatching { requireBookId(settingsRepository.current()) }.getOrNull() ?: return@launch
            if (mutableReferenceData.value !is AppReferenceDataState.Content) {
                mutableReferenceData.value = AppReferenceDataState.Loading
            }
            val result = withTimeoutOrNull(DEFAULT_INTERACTIVE_LOAD_TIMEOUT_MILLIS) {
                referenceDataPort.accountSummary(bookId)
            }
            mutableReferenceData.value = when (result) {
                null -> AppReferenceDataState.Error("REFERENCE_LOAD_TIMEOUT")
                is DomainResult.Success -> {
                    val snapshot = result.value.toReferenceSnapshot(
                        (mutableReferenceData.value as? AppReferenceDataState.Content)?.snapshot,
                    )
                    updateCurrencySettings(snapshot)
                    withContext(Dispatchers.Main.immediate) {
                        vaultController.synchronizeCards(bookId, snapshot.cards)
                    }
                    AppReferenceDataState.Content(snapshot)
                }
                is DomainResult.Failure -> AppReferenceDataState.Error(result.error.code)
            }
            if (mutableReferenceData.value is AppReferenceDataState.Content) {
                markTopLevelContent(bookId, TopLevelDestination.ACCOUNTS)
            }
        }
    }

    private fun loadReferenceRouteData(screenId: String) {
        if ((mutableRootState.value as? AppRootState.Session)?.state !is BookSessionState.Ready) return
        val route = navigator.currentKey
        val bookId = runCatching { requireBookId(settings.value) }.getOrNull() ?: return
        val generation = activeBookSessionRuntime.readyGeneration(bookId) ?: return
        val previous = (mutableReferenceData.value as? AppReferenceDataState.Content)?.snapshot
        val accountId = route.encodedArguments["accountId"]?.let { StableId.parse(it).getOrNull() }
        val categoryDirection = route.encodedArguments["direction"]
            ?.let { runCatching { CategoryDirection.valueOf(it) }.getOrNull() }
        referenceRouteLoader.request(
            ReferenceDataLoadKey(
                bookId,
                generation,
                currentRouteScopeKey(),
                screenId,
                accountId,
                categoryDirection,
            ),
            previous,
        )
    }

    private suspend fun loadReferenceRouteSnapshot(
        bookId: StableId,
        screenId: String,
        accountId: StableId?,
        categoryDirection: CategoryDirection?,
        previous: ReferenceDataSnapshot?,
    ): ReferenceDataSnapshot? {
        val summary = (referenceDataPort.accountSummary(bookId) as? DomainResult.Success)?.value ?: return null
        var snapshot = summary.toReferenceSnapshot(previous)
        when {
            screenId.startsWith("ACC-") -> {
                if (accountId != null && screenId in ACCOUNT_HISTORY_SCREENS) {
                    val history = (referenceDataPort.accountHistory(bookId, accountId) as? DomainResult.Success)?.value
                        ?: return null
                    snapshot = snapshot.copy(
                        checkpoints = history.checkpoints,
                        accountTransactions = history.transactions,
                        accountGoals = history.goals,
                    )
                }
            }
            screenId.startsWith("CAT-") -> {
                val direction = categoryDirection ?: CategoryDirection.EXPENSE
                val categories = (referenceDataPort.categoryPage(bookId, direction) as? DomainResult.Success)?.value
                    ?: return null
                val merchants = if (screenId == "CAT-002") {
                    (referenceDataPort.merchantPage(bookId) as? DomainResult.Success)?.value?.items ?: return null
                } else {
                    snapshot.merchants
                }
                snapshot = snapshot.copy(categories = categories.items, merchants = merchants)
            }
            screenId.startsWith("MER-") -> {
                val merchants = (referenceDataPort.merchantPage(bookId) as? DomainResult.Success)?.value ?: return null
                snapshot = snapshot.copy(merchants = merchants.items)
            }
            screenId.startsWith("PLC-") -> {
                val places = (referenceDataPort.placePage(bookId) as? DomainResult.Success)?.value ?: return null
                val locations = (referenceDataPort.locationPage(bookId) as? DomainResult.Success)?.value ?: return null
                val merchants = (referenceDataPort.merchantPage(bookId) as? DomainResult.Success)?.value ?: return null
                snapshot = snapshot.copy(
                    merchants = merchants.items,
                    places = places.items,
                    locations = locations.items,
                )
            }
        }
        return snapshot
    }

    private fun AccountSummarySnapshot.toReferenceSnapshot(previous: ReferenceDataSnapshot?): ReferenceDataSnapshot = ReferenceDataSnapshot(
        bookId = bookId,
        baseCurrency = baseCurrency,
        localRevision = localRevision,
        valuationRevision = valuationRevision,
        accounts = accounts,
        cards = cards,
        categories = previous?.takeIf { it.bookId == bookId }?.categories.orEmpty(),
        merchants = previous?.takeIf { it.bookId == bookId }?.merchants.orEmpty(),
        places = previous?.takeIf { it.bookId == bookId }?.places.orEmpty(),
        locations = previous?.takeIf { it.bookId == bookId }?.locations.orEmpty(),
        checkpoints = previous?.takeIf { it.bookId == bookId }?.checkpoints.orEmpty(),
        accountTransactions = previous?.takeIf { it.bookId == bookId }?.accountTransactions.orEmpty(),
        accountGoals = previous?.takeIf { it.bookId == bookId }?.accountGoals.orEmpty(),
        coreNetFinancialAssetsMinor = coreNetFinancialAssetsMinor,
        adjustedNetFinancialPositionMinor = adjustedNetFinancialPositionMinor,
        valuationMissing = valuationMissing,
    )

    private fun String.requiresReferenceRouteData(): Boolean = startsWith("ACC-") || startsWith("CAT-") || startsWith("MER-") || startsWith("PLC-")

    fun loadAnalysis(
        screenId: String,
        reportKey: String? = null,
        queryId: StableId? = null,
        entityId: StableId? = null,
        forecastKey: app.ledger.analytics.domain.ForecastKey? = null,
    ) {
        if ((mutableRootState.value as? AppRootState.Session)?.state !is BookSessionState.Ready) return
        viewModelScope.launch(Dispatchers.IO) {
            val saved = settingsRepository.current()
            val bookId = runCatching { requireBookId(saved) }.getOrNull() ?: return@launch
            val currency = CurrencyCode.parse(saved.baseCurrency.ifBlank { DEFAULT_CURRENCY }).getOrNull() ?: return@launch
            val today = runtimeSources.clock.now().atZone(ZoneId.of(saved.zoneId.ifBlank { DEFAULT_ZONE })).toLocalDate()
            val completed = withTimeoutOrNull(DEFAULT_INTERACTIVE_LOAD_TIMEOUT_MILLIS) {
                analysisController.open(bookId, currency, today, screenId, reportKey, queryId?.let(::DrilldownQueryId), entityId, forecastKey)
                true
            } ?: false
            if (!completed) analysisController.loadTimedOut()
            if (completed && analysis.value is AnalysisLoadState.Content) {
                markTopLevelContent(bookId, TopLevelDestination.ANALYSIS)
            }
        }
    }

    fun retryAnalysis() {
        viewModelScope.launch(Dispatchers.IO) { analysisController.reload() }
    }

    fun previousAnalysisPeriod() {
        viewModelScope.launch(Dispatchers.IO) { analysisController.previousPeriod() }
    }

    fun nextAnalysisPeriod() {
        viewModelScope.launch(Dispatchers.IO) { analysisController.nextPeriod() }
    }

    fun cycleAnalysisMeasure() = analysisController.cycleMeasure()

    fun cycleAnalysisDimension() = analysisController.cycleDimension()

    fun cycleAnalysisGranularity() = analysisController.cycleGranularity()

    fun cycleAnalysisComparison() = analysisController.cycleComparison()

    fun selectAnalysisMeasure(value: app.ledger.analytics.domain.Measure) = analysisController.selectMeasure(value)

    fun selectAnalysisDimension(value: app.ledger.analytics.domain.Dimension) = analysisController.selectDimension(value)

    fun selectAnalysisGranularity(value: app.ledger.analytics.domain.TimeGranularity) = analysisController.selectGranularity(value)

    fun selectAnalysisComparison(value: app.ledger.analytics.domain.ComparisonMode?) = analysisController.selectComparison(value)

    fun cycleAnalysisSort(stableKey: String) = analysisController.cycleSort(stableKey)

    fun toggleAnalysisReportFilter(filter: app.ledger.feature.analysis.AnalysisEntityFilter, id: StableId) = analysisController.toggleReportFilter(filter, id)

    fun removeAnalysisReportFilter(stableKey: String) = analysisController.removeReportFilter(stableKey)

    fun resetAnalysisReportFilters() = analysisController.resetReportFilters()

    fun changeAnalysisBuilderStep(delta: Int) = analysisController.changeBuilderStep(delta)

    fun applyAnalysisFilter(): Boolean = analysisController.applyFilter()

    fun prepareAnalysisExport(): Boolean {
        if (!analysisController.prepareExport()) return false
        val instanceId = nextId()
        analysisController.bindPreparedExportId(instanceId)
        navigateAnalysisP26("ANA-010", instanceId, null)
        return true
    }

    fun updateAnalysisDraftName(value: String) = analysisController.updateDraftName(value)

    fun previewCustomAnalysisReport() {
        viewModelScope.launch(Dispatchers.IO) { analysisController.previewCustomReport() }
    }

    fun saveCustomAnalysisReport() {
        viewModelScope.launch(Dispatchers.IO) { analysisController.saveCustomReport() }
    }

    fun copyCustomAnalysisReport(id: app.ledger.analytics.domain.ReportDefinitionId) {
        viewModelScope.launch(Dispatchers.IO) { analysisController.copyCustomReport(id) }
    }

    fun selectAnalysisVisualization(value: app.ledger.analytics.domain.ReportVisualization) = analysisController.selectVisualization(value)

    fun completeAnalysisVisualizationSelection(value: app.ledger.analytics.domain.ReportVisualization) {
        analysisController.selectVisualization(value)
        commitCurrentFormChanges()
        navigator.pop()
    }

    fun saveAnalysisDashboard() {
        viewModelScope.launch(Dispatchers.IO) { analysisController.saveDashboard() }
    }

    fun toggleAnalysisDashboardReport(id: app.ledger.analytics.domain.ReportDefinitionId) = analysisController.toggleDashboardReport(id)

    fun moveAnalysisDashboardReport(id: app.ledger.analytics.domain.ReportDefinitionId, delta: Int) = analysisController.moveDashboardReport(id, delta)

    fun toggleAnalysisDashboardWidth(id: app.ledger.analytics.domain.ReportDefinitionId) = analysisController.toggleDashboardWidth(id)

    fun saveAnalysisAnomalyRule(id: app.ledger.analytics.domain.AnomalyRuleId?) {
        viewModelScope.launch(Dispatchers.IO) { analysisController.saveAnomalyRule(id) }
    }

    fun editAnalysisAnomalyRule(id: app.ledger.analytics.domain.AnomalyRuleId?) = analysisController.editAnomalyRule(id)

    fun cycleAnalysisAnomalyType() = analysisController.cycleAnomalyType()
    fun selectAnalysisAnomalyType(value: app.ledger.analytics.domain.AnomalyRuleType) = analysisController.selectAnomalyType(value)

    fun updateAnalysisAnomalyThreshold(value: String) = analysisController.updateAnomalyThreshold(value)

    fun updateAnalysisAnomalyLookback(value: String) = analysisController.updateAnomalyLookback(value)

    fun selectAnalysisExportFormat(format: ReportExportFormat) = analysisController.selectExportFormat(format)

    fun selectAnalysisExportScope(scope: app.ledger.feature.analysis.AnalysisExportScope) = analysisController.selectExportScope(scope)

    fun prepareCurrentAnalysisExport(): Boolean = analysisController.prepareCurrentExport()

    fun runAnalysisIntegrity() {
        viewModelScope.launch(Dispatchers.IO) { analysisController.runIntegrity() }
    }

    fun repairAnalysisProjection() {
        viewModelScope.launch(Dispatchers.IO) { analysisController.runIntegrity(repair = true) }
    }

    fun toggleAnalysisTechnicalDetails() = analysisController.toggleTechnicalDetails()

    fun loadNextAnalysisDrilldown() {
        viewModelScope.launch(Dispatchers.IO) { analysisController.loadNextDrilldown() }
    }

    fun openAnalysisTransaction(transactionId: StableId) = openProjectTransaction(transactionId)

    fun cycleConsumptionMapMode() {
        viewModelScope.launch(Dispatchers.IO) { analysisController.cycleMapMode() }
    }

    fun cycleConsumptionMapWeight() {
        viewModelScope.launch(Dispatchers.IO) { analysisController.cycleMapWeight() }
    }

    fun cycleConsumptionMapAggregation() {
        viewModelScope.launch(Dispatchers.IO) { analysisController.cycleMapAggregation() }
    }

    fun cycleConsumptionMapPresentation() {
        viewModelScope.launch(Dispatchers.IO) { analysisController.cycleMapPresentation() }
    }

    fun selectConsumptionMapMode(value: app.ledger.analytics.domain.ConsumptionMapMode) = viewModelScope.launch(Dispatchers.IO) { analysisController.selectMapMode(value) }

    fun selectConsumptionMapWeight(value: app.ledger.analytics.domain.ConsumptionMapWeight) = viewModelScope.launch(Dispatchers.IO) { analysisController.selectMapWeight(value) }

    fun selectConsumptionMapAggregation(value: app.ledger.analytics.domain.ConsumptionMapAggregation) = viewModelScope.launch(Dispatchers.IO) { analysisController.selectMapAggregation(value) }

    fun selectConsumptionMapPresentation(value: app.ledger.analytics.domain.ConsumptionMapPresentation) = viewModelScope.launch(Dispatchers.IO) { analysisController.selectMapPresentation(value) }

    fun toggleConsumptionMapSpecialTransactions() {
        viewModelScope.launch(Dispatchers.IO) { analysisController.toggleMapSpecialTransactions() }
    }

    fun resetConsumptionMapFilters() {
        viewModelScope.launch(Dispatchers.IO) { analysisController.resetMapFilters() }
    }

    fun cycleConsumptionMapAccountFilter() = viewModelScope.launch(Dispatchers.IO) { analysisController.cycleMapAccountFilter() }

    fun cycleConsumptionMapCategoryFilter() = viewModelScope.launch(Dispatchers.IO) { analysisController.cycleMapCategoryFilter() }

    fun cycleConsumptionMapMerchantFilter() = viewModelScope.launch(Dispatchers.IO) { analysisController.cycleMapMerchantFilter() }

    fun cycleConsumptionMapPlaceFilter() = viewModelScope.launch(Dispatchers.IO) { analysisController.cycleMapPlaceFilter() }

    fun cycleConsumptionMapProjectFilter() = viewModelScope.launch(Dispatchers.IO) { analysisController.cycleMapProjectFilter() }

    fun cycleConsumptionMapAmountFilter() = viewModelScope.launch(Dispatchers.IO) { analysisController.cycleMapAmountFilter() }

    fun selectConsumptionMapFilter(dimension: String, selectedId: StableId?) = viewModelScope.launch(Dispatchers.IO) {
        val parsed = runCatching { ConsumptionMapFilterDimension.valueOf(dimension) }.getOrNull() ?: return@launch
        analysisController.selectMapFilter(parsed, selectedId)
    }

    fun selectConsumptionMapAmountFilter(minimumBaseAmountMinor: Long?) = viewModelScope.launch(Dispatchers.IO) {
        analysisController.selectMapAmountFilter(minimumBaseAmountMinor)
    }

    fun removeConsumptionMapFilter(stableKey: String) {
        viewModelScope.launch(Dispatchers.IO) { analysisController.removeMapFilter(stableKey) }
    }

    fun updateConsumptionMapViewport(viewport: app.ledger.analytics.domain.MapViewport) {
        viewModelScope.launch(Dispatchers.IO) { analysisController.updateMapViewport(viewport) }
    }

    fun markConsumptionMapUnavailable() = analysisController.markMapUnavailable()

    fun navigateConsumptionMapDetail(pointId: StableId) {
        val screenId = ScreenId("ANA-012")
        navigator.navigate(
            LedgerRouteContract.destination(screenId, mapOf("placeOrClusterId" to StableIdArgument(pointId))),
            SessionGateState.READY,
        )
    }

    fun navigateAnalysis(targetScreenId: String, report: FixedReport?, queryId: DrilldownQueryId?) {
        val screenId = ScreenId(targetScreenId)
        val arguments = buildMap<String, SafeRouteArgument> {
            if (targetScreenId == "ANA-003" || targetScreenId == "ANA-004") {
                val fixed = requireNotNull(report)
                put(
                    "reportKey",
                    LedgerRouteContract.opaqueKeyArgument(
                        screenId,
                        "reportKey",
                        FixedReportCatalog.definition(fixed).key.value,
                    ),
                )
            }
            if (targetScreenId == "ANA-005") {
                put("queryId", StableIdArgument(requireNotNull(queryId).value))
            }
        }
        navigator.navigate(LedgerRouteContract.destination(screenId, arguments), SessionGateState.READY)
    }

    fun navigateAnalysisP26(
        targetScreenId: String,
        id: StableId?,
        forecastKey: app.ledger.analytics.domain.ForecastKey?,
    ) {
        val screenId = ScreenId(targetScreenId)
        val arguments = buildMap<String, SafeRouteArgument> {
            when (targetScreenId) {
                "ANA-007" -> if (id != null) put("dashboardId", StableIdArgument(id))
                "ANA-008" -> if (id != null) put("definitionId", StableIdArgument(id))
                "ANA-010" -> put("reportInstanceId", StableIdArgument(requireNotNull(id)))
                "ANA-014" -> put(
                    "forecastKey",
                    LedgerRouteContract.opaqueKeyArgument(screenId, "forecastKey", requireNotNull(forecastKey).routeKey),
                )
            }
        }
        navigator.navigate(LedgerRouteContract.destination(screenId, arguments), SessionGateState.READY)
    }

    fun loadJournal() {
        if ((mutableRootState.value as? AppRootState.Session)?.state !is BookSessionState.Ready) return
        journalSearchLoader.reset()
        if (routeContentTrace != null && navigator.currentTopLevel == TopLevelDestination.JOURNAL) {
            resetJournalRouteStageTraces()
            journalMetadataTrace = LedgerSessionPerformance.begin(LedgerInteractionOperation.JOURNAL_METADATA)
        }
        viewModelScope.launch(Dispatchers.IO) {
            val saved = settingsRepository.current()
            val bookId = runCatching { requireBookId(saved) }.getOrNull()
            if (bookId == null) {
                journalMetadataTrace?.close()
                journalMetadataTrace = null
                return@launch
            }
            val current = mutableJournal.value as? JournalLoadState.Content
            val loaded: JournalLoadState? = withTimeoutOrNull(DEFAULT_INTERACTIVE_LOAD_TIMEOUT_MILLIS) {
                val presets = (journalApplicationPort.savedFilters(bookId) as? DomainResult.Success)?.value.orEmpty()
                val defaultPreset = presets.singleOrNull { it.isDefault }
                val filter = current?.filter
                    ?: defaultPreset?.filter
                    ?: TransactionFilter(lifecycleStates = setOf(TransactionLifecycleState.ACTIVE))
                (current ?: JournalLoadState.Content()).copy(
                    filter = filter,
                    zoneId = saved.zoneId.ifBlank { DEFAULT_ZONE },
                    searchText = filter.searchText.orEmpty(),
                    presets = presets,
                    activePresetId = current?.activePresetId ?: defaultPreset?.id,
                )
            }
            journalMetadataTrace?.close()
            journalMetadataTrace = null
            if (loaded == null) {
                mutableJournal.value = current ?: JournalLoadState.Failure("JOURNAL_LOAD_TIMEOUT")
                return@launch
            }
            if (loaded is JournalLoadState.Failure) {
                mutableJournal.value = current ?: loaded
                return@launch
            }
            check(loaded is JournalLoadState.Content)
            val request = JournalPagingRequest(
                bookId,
                loaded.filter,
                refreshEpoch = (mutableJournalPagingRequest.value?.refreshEpoch ?: 0) + 1,
            )
            if (routeContentTrace != null) journalStageRequest = request
            mutableJournal.value = loaded.copy(
                pagingEpoch = request.refreshEpoch,
                pageLoadedEpoch = null,
            )
            mutableJournalPagingRequest.value = request
        }
    }

    /** Completes the bounded Journal response before admitting metadata and Paging work. */
    fun onJournalFirstResponsePresented(screenId: String) {
        LedgerSessionPerformance.completeBlockingProgress()
        val state = mutableJournal.value
        if (state === JournalLoadState.Loading) {
            loadJournal()
        } else if (state is JournalLoadState.Content) {
            if (screenId in JOURNAL_OPTION_SCREENS) loadJournalBulkOptions()
            if (screenId !in JOURNAL_PAGED_SCREENS || screenId == "JRN-002" && state.searchText.isBlank()) {
                val ready = (mutableRootState.value as? AppRootState.Session)?.state as? BookSessionState.Ready
                if (ready != null && navigator.currentTopLevel == TopLevelDestination.JOURNAL) {
                    completeJournalRoutePresentation(ready.bookId)
                }
            }
        }
    }

    private fun loadJournalBulkOptions() {
        val ready = (mutableRootState.value as? AppRootState.Session)?.state as? BookSessionState.Ready ?: return
        if (journalBulkOptionsGeneration == ready.generation || journalBulkOptionsJob?.isActive == true) return
        journalBulkOptionsJob = viewModelScope.launch {
            try {
                val options = withContext(Dispatchers.IO) {
                    withTimeoutOrNull(DEFAULT_INTERACTIVE_LOAD_TIMEOUT_MILLIS) {
                        (journalApplicationPort.bulkEditOptions(ready.bookId) as? DomainResult.Success)?.value
                    }
                } ?: return@launch
                val currentReady = (mutableRootState.value as? AppRootState.Session)?.state as? BookSessionState.Ready
                if (currentReady?.bookId == ready.bookId && currentReady.generation == ready.generation) {
                    updateJournalContent { copy(bulkOptions = options) }
                    journalBulkOptionsGeneration = ready.generation
                }
            } finally {
                journalBulkOptionsJob = null
            }
        }
    }

    @Suppress("ComplexCondition")
    fun updateJournalSearch(value: String) {
        val query = value.take(RECORD_SEARCH_LIMIT)
        val current = mutableJournal.value as? JournalLoadState.Content ?: return
        if (
            current.searchText == query &&
            (query.isBlank() || current.searchPending || current.searchResultReady)
        ) {
            return
        }
        val updated = current.copy(
            searchText = query,
            filter = current.filter.copy(searchText = query.takeIf(String::isNotBlank)),
            searchPending = query.isNotBlank(),
            searchResultReady = false,
        )
        mutableJournal.value = updated
        val ready = (mutableRootState.value as? AppRootState.Session)?.state as? BookSessionState.Ready ?: return
        if (query.isBlank()) cancelJournalSearchTrace() else beginJournalSearchTrace()
        journalSearchLoader.request(JournalSearchLoadKey(ready.bookId, ready.generation, updated.filter))
    }

    fun applyJournalFilter(filter: TransactionFilter) {
        updateJournalContent { copy(filter = filter, searchText = filter.searchText.orEmpty(), activePresetId = null) }
        refreshJournalPaging()
    }

    fun openJournalForAccount(accountId: StableId) {
        val filter = TransactionFilter(
            accountIds = setOf(UserAccountId(accountId)),
            lifecycleStates = setOf(TransactionLifecycleState.ACTIVE),
        )
        val current = (mutableJournal.value as? JournalLoadState.Content) ?: JournalLoadState.Content()
        mutableJournal.value = current.copy(filter = filter, searchText = "", activePresetId = null)
        loadJournal()
        navigator.navigate(LedgerRouteContract.destination(ScreenId("JRN-001")), SessionGateState.READY)
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
        updateJournalContent { copy(detail = null, detailLoading = true, detailFailureCode = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val bookId = requireBookId(settingsRepository.current())
            val bundle = withTimeoutOrNull(DEFAULT_INTERACTIVE_LOAD_TIMEOUT_MILLIS) {
                journalApplicationPort.detailBundle(bookId, transactionId)
            }
            if (bundle is DomainResult.Success && bundle.value.detail != null) {
                updateJournalContent {
                    copy(
                        detail = bundle.value.detail,
                        detailLoading = false,
                        detailFailureCode = null,
                        history = bundle.value.history,
                        dependencies = bundle.value.dependencies,
                        dependencyResolutions = emptyList(),
                    )
                }
            } else {
                updateJournalContent {
                    copy(
                        detailLoading = false,
                        detailFailureCode = if (bundle == null) "JOURNAL_DETAIL_TIMEOUT" else "JOURNAL_DETAIL_FAILED",
                    )
                }
            }
        }
    }

    fun editJournalTransaction(transaction: JournalTransactionView) {
        when (transaction.kind) {
            app.ledger.finance.domain.TransactionKind.EXPENSE,
            app.ledger.finance.domain.TransactionKind.INCOME,
            -> openRecordEditor(
                RecordEditorMode.EDIT,
                if (transaction.kind == app.ledger.finance.domain.TransactionKind.INCOME) OrdinaryDirection.INCOME else OrdinaryDirection.EXPENSE,
                null,
                transaction.transactionId,
            )
            app.ledger.finance.domain.TransactionKind.REFUND -> {
                loadRefund()
                navigateRecord("REC-015", mapOf("transactionId" to transaction.transactionId), emptyMap())
            }
            app.ledger.finance.domain.TransactionKind.CREDIT_PAYMENT -> {
                loadCredit("REC-014", transactionId = transaction.transactionId)
                navigateCredit("REC-014", transaction.transactionId)
            }
            app.ledger.finance.domain.TransactionKind.TRANSFER -> openSpecializedJournalEditor("REC-013", mapOf("transactionId" to transaction.transactionId))
            app.ledger.finance.domain.TransactionKind.BALANCE_ADJUSTMENT -> openSpecializedJournalEditor("REC-020", mapOf("transactionId" to transaction.transactionId))
            app.ledger.finance.domain.TransactionKind.FX_EXCHANGE -> openSpecializedJournalEditor("REC-021", mapOf("transactionId" to transaction.transactionId))
            app.ledger.finance.domain.TransactionKind.OPENING_BALANCE -> openSpecializedJournalEditor("REC-022", mapOf("transactionId" to transaction.transactionId))
            app.ledger.finance.domain.TransactionKind.LOAN_DISBURSEMENT -> navigateRecord("REC-018", emptyMap(), emptyMap())
            app.ledger.finance.domain.TransactionKind.LOAN_PAYMENT -> navigateRecord("REC-019", emptyMap(), emptyMap())
            app.ledger.finance.domain.TransactionKind.SETTLEMENT_PAYMENT -> openSpecializedJournalEditor("REC-013")
        }
    }

    private fun openSpecializedJournalEditor(screenId: String, arguments: Map<String, StableId> = emptyMap()) {
        loadSpecializedTransaction(screenId, transactionId = arguments["transactionId"])
        navigateRecord(screenId, arguments, emptyMap())
    }

    fun refundJournalTransaction(transactionId: StableId) {
        val detail = (mutableJournal.value as? JournalLoadState.Content)?.detail
            ?.takeIf { it.transaction.transactionId == transactionId }
        if (detail?.transaction?.kind != app.ledger.finance.domain.TransactionKind.EXPENSE || detail.transaction.state != app.ledger.finance.domain.TransactionLifecycleState.ACTIVE) {
            mutableGlobalSnackbarMessages.tryEmit(GlobalSnackbarMessage.JOURNAL_MUTATION_FAILED)
            return
        }
        loadRefund(transactionId)
        navigateRecord("REC-015", mapOf("transactionId" to transactionId), emptyMap())
    }

    fun copyJournalTransactionToTemplate(transactionId: StableId) {
        val detail = (mutableJournal.value as? JournalLoadState.Content)?.detail
            ?.takeIf { it.transaction.transactionId == transactionId }
            ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val saved = settingsRepository.current()
            val bookId = runCatching { requireBookId(saved) }.getOrNull() ?: return@launch
            val automation = automationApplicationPort.snapshot(bookId)
            val entry = ordinaryTransactionEntryPort.snapshot(bookId)
            if (automation !is DomainResult.Success || entry !is DomainResult.Success) return@launch
            val zone = ZoneId.of(saved.zoneId.ifBlank { DEFAULT_ZONE })
            val base = AutomationPolicy.create(
                automation.value,
                entry.value,
                "AUT-003",
                null,
                null,
                null,
                zone,
                runtimeSources.clock.now().atZone(zone).toLocalDate(),
            )
            val draft = requireNotNull(base.blueprintDraft)
            val references = entry.value.references
            val category = references.categories.firstOrNull { it.name == detail.transaction.categoryOrType }
            val account = references.accounts.firstOrNull { detail.transaction.accountAndCard.contains(it.name) }
            val card = references.cards.firstOrNull { detail.transaction.accountAndCard.contains(it.displayName) }
            val merchant = detail.merchantName?.let { name -> references.merchants.firstOrNull { it.name == name } }
            val place = detail.locationName?.let { name -> references.places.firstOrNull { it.name == name } }
            val targetKind = when (detail.transaction.kind) {
                app.ledger.finance.domain.TransactionKind.INCOME,
                app.ledger.finance.domain.TransactionKind.REFUND,
                -> app.ledger.finance.domain.TransactionKind.INCOME
                app.ledger.finance.domain.TransactionKind.CREDIT_PAYMENT -> app.ledger.finance.domain.TransactionKind.CREDIT_PAYMENT
                app.ledger.finance.domain.TransactionKind.LOAN_PAYMENT -> app.ledger.finance.domain.TransactionKind.LOAN_PAYMENT
                else -> app.ledger.finance.domain.TransactionKind.EXPENSE
            }
            mutableAutomation.value = AutomationLoadState.Content(
                base.copy(
                    presentation = AutomationPresentation.EDITING,
                    blueprintDraft = draft.copy(
                        name = detail.transaction.summary.ifBlank { detail.transaction.categoryOrType }.take(AUTOMATION_TEMPLATE_NAME_LIMIT),
                        targetKind = targetKind,
                        categoryId = category?.id,
                        primaryAccountId = account?.id,
                        cardId = card?.id,
                        merchantId = merchant?.id,
                        amountExpression = detail.amountExpression.orEmpty(),
                        currency = detail.transaction.currency.value,
                        noteTemplate = detail.fullNote.orEmpty().take(AUTOMATION_TEMPLATE_NOTE_LIMIT),
                        fixedPlaceId = place?.id,
                    ),
                ),
            )
            withContext(Dispatchers.Main.immediate) { navigateAutomation("AUT-003", null) }
        }
    }

    fun selectJournalTransaction(transactionId: StableId) = updateJournalContent {
        val next = selection?.let { JournalSelectionPolicy.toggle(it, transactionId) } ?: JournalSelectionPolicy.begin(filter, transactionId)
        copy(selection = next)
    }

    fun editJournalTransaction(transactionId: StableId, kind: TransactionKind) {
        if (kind in setOf(TransactionKind.EXPENSE, TransactionKind.INCOME)) {
            openRecordEditor(
                RecordEditorMode.EDIT,
                if (kind == TransactionKind.INCOME) OrdinaryDirection.INCOME else OrdinaryDirection.EXPENSE,
                null,
                transactionId,
            )
            return
        }
        if (kind == TransactionKind.TRANSFER) {
            navigator.navigate(
                LedgerRouteContract.destination(
                    ScreenId("REC-013"),
                    mapOf("transactionId" to StableIdArgument(transactionId)),
                ),
                SessionGateState.READY,
            )
            return
        }
        val content = mutableJournal.value as? JournalLoadState.Content ?: return
        mutableJournal.value = content.copy(selection = JournalSelectionPolicy.begin(content.filter, transactionId))
        navigator.navigate(LedgerRouteContract.destination(ScreenId("JRN-006")), SessionGateState.READY)
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
        mutateJournalPresets(
            JournalSavedFilterCommand.Copy(
                id,
                nextId(),
                formatPresentationString(JournalR.string.p15_journal_copy_name, source.name),
            ),
        )
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
            when (val result = journalApplicationPort.bulkEdit(request)) {
                is DomainResult.Success -> {
                    updateJournalContent { copy(operation = JournalOperationState.SUCCEEDED, selection = null) }
                    loadReferenceDataAfterMutation(bookId)
                    refreshJournalPaging()
                    mutableGlobalSnackbarMessages.emit(GlobalSnackbarMessage.JOURNAL_BULK_UPDATED)
                    withContext(Dispatchers.Main.immediate) {
                        navigator.pop()
                        navigator.pop()
                    }
                }
                is DomainResult.Failure -> updateJournalContent {
                    copy(
                        operation = if (result.error == app.ledger.finance.domain.DomainViolation.InvalidField("bulk.noChanges")) {
                            JournalOperationState.NO_CHANGES
                        } else {
                            JournalOperationState.FAILED
                        },
                    )
                }
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
        val filter = (mutableJournal.value as? JournalLoadState.Content)?.filter
            ?: return context.getString(R.string.export_filter_all_transactions)
        val dimensions = buildList {
            if (filter.searchText != null) add(context.getString(R.string.export_filter_search))
            if (filter.occurredFrom != null || filter.occurredThrough != null) add(context.getString(R.string.export_filter_occurrence_time))
            if (filter.kinds.isNotEmpty()) add(context.getString(R.string.export_filter_types, filter.kinds.size))
            if (filter.accountIds.isNotEmpty()) add(context.getString(R.string.export_filter_accounts, filter.accountIds.size))
            if (filter.categoryIds.isNotEmpty()) add(context.getString(R.string.export_filter_categories, filter.categoryIds.size))
            if (filter.lifecycleStates.isNotEmpty()) add(context.getString(R.string.export_filter_states, filter.lifecycleStates.size))
            if (filter.amountRange != null) add(context.getString(R.string.export_filter_amount))
            if (filter.hasAttachment != null) add(context.getString(R.string.export_filter_attachment))
            if (filter.includedInBudget != null) add(context.getString(R.string.export_filter_budget))
        }
        return dimensions.ifEmpty { listOf(context.getString(R.string.export_filter_all_transactions)) }.joinToString(" · ")
    }

    fun resolveJournalDependency(dependency: JournalDependencyView, policy: DependencyPolicy) = updateJournalContent {
        val domain = TransactionDependency(TransactionId(dependency.parentTransactionId), TransactionId(dependency.childTransactionId), dependency.type)
        val resolution = DependencyResolution(domain, policy)
        copy(dependencyResolutions = dependencyResolutions.filterNot { it.dependency == domain } + resolution)
    }

    fun moveJournalToTrash(transactionId: StableId, expectedRevisionId: StableId, resolutions: List<DependencyResolution>) = executeJournalMutation(
        transactionId,
    ) { ids, now ->
        val retentionDays = settingsRepository.current().trashRetentionDays
        val purgeAfter = if (retentionDays == TrashRetention.NEVER.days) {
            java.time.Instant.ofEpochMilli(Long.MAX_VALUE)
        } else {
            now.plusSeconds(retentionDays.coerceAtLeast(1).toLong() * SECONDS_PER_DAY)
        }
        JournalMutationRequest.MoveToTrash(ids, expectedRevisionId, now, purgeAfter, resolutions)
    }

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
        updateJournalContent { copy(operation = JournalOperationState.VALIDATING, purgeAssessment = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val bookId = requireBookId(settingsRepository.current())
            when (val result = controlledPurgeApplicationPort.assess(bookId, transactionId, runtimeSources.clock.now())) {
                is DomainResult.Success -> updateJournalContent { copy(purgeAssessment = result.value, operation = JournalOperationState.IDLE) }
                is DomainResult.Failure -> updateJournalContent { copy(operation = JournalOperationState.FAILED) }
            }
        }
    }

    fun purgeJournalTransaction(transactionId: StableId) {
        val content = mutableJournal.value as? JournalLoadState.Content ?: return
        val assessment = content.purgeAssessment?.takeIf { it.transactionId == transactionId && it.canPurgeNow } ?: return
        val expectedRevision = content.history.firstOrNull()?.revisionId ?: return
        if (content.operation in setOf(JournalOperationState.VALIDATING, JournalOperationState.COMMITTING)) return
        viewModelScope.launch(Dispatchers.IO) {
            updateJournalContent { copy(operation = JournalOperationState.COMMITTING) }
            val request = ControlledPurgeRequest(
                bookId = requireBookId(settingsRepository.current()),
                commandId = nextId(),
                transactionId = transactionId,
                expectedRevisionId = expectedRevision,
                purgeCommitId = nextId(),
                deviceInstanceId = nextId(),
                evaluatedAt = assessment.evaluatedAt,
            )
            when (controlledPurgeApplicationPort.purge(request)) {
                is DomainResult.Success -> {
                    loadReferenceDataAfterMutation(request.bookId)
                    updateJournalContent {
                        copy(
                            detail = null,
                            detailLoading = false,
                            detailFailureCode = null,
                            history = emptyList(),
                            purgeAssessment = null,
                            operation = JournalOperationState.SUCCEEDED,
                        )
                    }
                    refreshJournalPaging()
                    withContext(Dispatchers.Main.immediate) { requestRootBack() }
                    mutableGlobalSnackbarMessages.emit(GlobalSnackbarMessage.JOURNAL_PERMANENTLY_DELETED)
                }
                is DomainResult.Failure -> {
                    updateJournalContent { copy(operation = JournalOperationState.FAILED) }
                    mutableGlobalSnackbarMessages.emit(GlobalSnackbarMessage.JOURNAL_MUTATION_FAILED)
                }
            }
        }
    }

    private fun executeJournalMutation(
        transactionId: StableId,
        request: suspend (JournalMutationIds, java.time.Instant) -> JournalMutationRequest,
    ) {
        if ((mutableJournal.value as? JournalLoadState.Content)?.operation in setOf(JournalOperationState.VALIDATING, JournalOperationState.COMMITTING)) return
        viewModelScope.launch(Dispatchers.IO) {
            updateJournalContent { copy(operation = JournalOperationState.COMMITTING) }
            val bookId = requireBookId(settingsRepository.current())
            val ids = JournalMutationIds(bookId, nextId(), transactionId, nextId(), nextId(), nextId(), List(FINANCIAL_FACT_ID_RESERVE) { nextId() }, List(FX_ID_RESERVE) { nextId() })
            val mutation = request(ids, runtimeSources.clock.now())
            when (val result = journalApplicationPort.mutate(mutation)) {
                is DomainResult.Success -> {
                    loadReferenceDataAfterMutation(bookId)
                    updateJournalContent {
                        copy(
                            detail = null,
                            detailLoading = false,
                            detailFailureCode = null,
                            history = emptyList(),
                            dependencies = emptyList(),
                            dependencyResolutions = emptyList(),
                            operation = JournalOperationState.SUCCEEDED,
                        )
                    }
                    refreshJournalPaging()
                    withContext(Dispatchers.Main.immediate) { requestRootBack() }
                    mutableGlobalSnackbarMessages.emit(
                        if (mutation is JournalMutationRequest.MoveToTrash) {
                            GlobalSnackbarMessage.JOURNAL_MOVED_TO_TRASH
                        } else {
                            GlobalSnackbarMessage.JOURNAL_RESTORED
                        },
                    )
                }
                is DomainResult.Failure -> {
                    updateJournalContent { copy(operation = JournalOperationState.FAILED) }
                    mutableGlobalSnackbarMessages.emit(GlobalSnackbarMessage.JOURNAL_MUTATION_FAILED)
                }
            }
        }
    }

    private fun refreshJournalPaging() {
        journalSearchLoader.reset()
        viewModelScope.launch(Dispatchers.IO) {
            val bookId = runCatching { requireBookId(settingsRepository.current()) }.getOrNull() ?: return@launch
            val content = mutableJournal.value as? JournalLoadState.Content ?: return@launch
            val request = JournalPagingRequest(
                bookId,
                content.filter,
                refreshEpoch = (mutableJournalPagingRequest.value?.refreshEpoch ?: 0) + 1,
            )
            mutableJournal.value = content.copy(
                pagingEpoch = request.refreshEpoch,
                pageLoadedEpoch = null,
            )
            mutableJournalPagingRequest.value = request
        }
    }

    private fun updateJournalContent(block: JournalLoadState.Content.() -> JournalLoadState.Content) {
        val current = mutableJournal.value as? JournalLoadState.Content ?: return
        mutableJournal.value = current.block()
    }

    fun loadOrdinaryRecord(transactionId: StableId? = null) {
        val ready = (mutableRootState.value as? AppRootState.Session)?.state as? BookSessionState.Ready ?: return
        val key = OrdinaryLoadKey(ready.bookId, ready.generation, transactionId)
        val visible = (mutableOrdinaryRecord.value as? OrdinaryRecordLoadState.Content)
            ?.takeIf { currentOrdinaryLoadKey == key }
        if (currentOrdinaryLoadKey == key && (visible != null || currentOrdinaryLoadJob?.isActive == true)) return
        currentOrdinaryLoadJob?.cancel()
        currentOrdinaryLoadKey = key
        if (visible == null) mutableOrdinaryRecord.value = OrdinaryRecordLoadState.Loading
        currentOrdinaryLoadJob = viewModelScope.launch(Dispatchers.IO) {
            val bookId = runCatching { requireBookId(settingsRepository.current()) }.getOrNull() ?: return@launch
            val result = withTimeoutOrNull(DEFAULT_INTERACTIVE_LOAD_TIMEOUT_MILLIS) {
                ordinaryTransactionEntryPort.snapshot(bookId, transactionId)
            }
            val loaded = when (result) {
                null -> visible ?: OrdinaryRecordLoadState.Failure("RECORD_LOAD_TIMEOUT")
                is DomainResult.Success -> {
                    OrdinaryRecordLoadState.Content(
                        result.value,
                        visible?.tab ?: RecordTab.EXPENSE,
                        visible?.search.orEmpty(),
                        visible?.selectedCategoryId,
                        visible?.editor,
                        visible?.expenseScrollIndex ?: 0,
                        visible?.incomeScrollIndex ?: 0,
                    )
                }
                is DomainResult.Failure -> visible ?: OrdinaryRecordLoadState.Failure(sanitizeCode(result.error.code))
            }
            if (currentOrdinaryLoadKey == key && activeBookSessionRuntime.readyGeneration(bookId) == key.sessionGeneration) {
                mutableOrdinaryRecord.value = loaded
                if (loaded is OrdinaryRecordLoadState.Content) {
                    markTopLevelContent(bookId, TopLevelDestination.RECORD)
                }
                currentOrdinaryLoadJob = null
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
    fun deleteBatchRow(rowId: StableId) {
        batchEntryController.delete(rowId)
        val current = navigator.currentKey
        if (current.contract.screenId.value == "REC-024" && current.encodedArguments["rowId"] == rowId.toString()) {
            navigator.pop()
        }
    }
    fun moveBatchRow(rowId: StableId, targetIndex: Int) = batchEntryController.move(rowId, targetIndex)
    fun sortBatchRows(order: BatchSort) = batchEntryController.sort(order)
    fun pasteBatchRows(text: String) = batchEntryController.paste(text)
    fun updateBatchRow(row: BatchRowDraft) = batchEntryController.updateRow(row)
    fun cycleBatchReference(rowId: StableId, field: BatchEntryField) = batchEntryController.cycle(rowId, field)
    fun selectBatchReference(rowId: StableId, field: BatchEntryField, selectedId: StableId?) = batchEntryController.selectReference(rowId, field, selectedId)

    fun openAttachment(value: StableId) {
        val activeBook = ((mutableRootState.value as? AppRootState.Session)?.state as? BookSessionState.Ready)?.bookId ?: return
        openAttachment(activeBook, value)
    }

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
            val createdEditor = OrdinaryRecordPolicy.createEditor(snapshot, mode, direction, categoryId, sourceId, runtimeSources.clock.now(), zone, locale)
            val editor = hydrateRecordEditor(createdEditor)
            mutableOrdinaryRecord.value = current.copy(snapshot = snapshot, selectedCategoryId = editor.draft.categoryId, editor = editor)
            startRecordLocationAcquisition(restart = true)
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
        if (target == "REC-009" && !context.hasLedgerLocationPermission()) {
            navigator.navigate(LedgerRouteContract.destination(ScreenId("SYS-001")), SessionGateState.READY)
            return
        }
        val screenId = ScreenId(target)
        val arguments = buildMap<String, SafeRouteArgument> {
            stable.forEach { (name, value) -> put(name, StableIdArgument(value)) }
            enums.forEach { (name, value) ->
                val kind = LedgerRouteContract.screen(screenId).parameters.single { it.name == name }.kind
                put(
                    name,
                    if (kind == RouteArgumentKind.ENUM_MASK) {
                        EnumMaskArgument.fromBits(value.toInt())
                    } else {
                        LedgerRouteContract.enumArgument(screenId, name, value)
                    },
                )
            }
        }
        navigator.navigate(LedgerRouteContract.destination(screenId, arguments), SessionGateState.READY)
        val editor = (mutableOrdinaryRecord.value as? OrdinaryRecordLoadState.Content)?.editor
        if (target == "REC-009" && editor?.locationPresentation in setOf(
                RecordLocationEditorState.NotRequested,
                RecordLocationEditorState.Locating,
                RecordLocationEditorState.Timeout,
            )
        ) {
            startRecordLocationAcquisition()
        }
    }

    fun recordExpression(value: String) = updateEditor { OrdinaryRecordPolicy.changeExpression(it, value, recordLocale()) }
    fun consumeRecordAmountAutoFocus() = updateEditor(OrdinaryRecordPolicy::consumeAmountAutoFocus)

    fun completeLocationPermission() {
        if (!LocationPermissionCompletionPolicy.shouldHandle(navigator.currentKey.contract.screenId.value)) return
        navigator.pop()
        navigator.navigate(LedgerRouteContract.destination(ScreenId("REC-009")), SessionGateState.READY)
        startRecordLocationAcquisition(restart = true)
    }

    fun dismissLocationPermission() {
        if (!LocationPermissionCompletionPolicy.shouldHandle(navigator.currentKey.contract.screenId.value)) return
        navigator.pop()
        stopRecordLocationAcquisition()
        updateEditor { it.copy(locationPresentation = RecordLocationEditorState.PermissionDenied) }
        navigator.navigate(LedgerRouteContract.destination(ScreenId("REC-009")), SessionGateState.READY)
    }

    private fun startRecordLocationAcquisition(restart: Boolean = false) {
        val editor = (mutableOrdinaryRecord.value as? OrdinaryRecordLoadState.Content)?.editor ?: return
        if (editor.draft.locationRecordId != null || editor.pendingLocation != null || editor.locationPresentation == RecordLocationEditorState.Cleared) {
            stopRecordLocationAcquisition()
            return
        }
        recordLocationShouldRun = true
        if (!context.hasLedgerLocationPermission()) {
            recordLocationShouldRun = false
            recordLocationJob?.cancel()
            recordLocationJob = null
            updateEditor { it.copy(locationPresentation = RecordLocationEditorState.PermissionDenied) }
            return
        }
        if (!recordLocationHostForeground) return
        if (recordLocationJob?.isActive == true && !restart) return

        if (restart) recordLocationJob?.cancel()
        val generation = ++recordLocationGeneration
        updateEditor { it.copy(locationPresentation = RecordLocationEditorState.Locating) }
        recordLocationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val runner = ForegroundLocationRetryRunner(
                    captureAttempt = {
                        val platformClock = InjectedJavaClock(runtimeSources.clock)
                        ForegroundLocationSaveSession(
                            ProductionForegroundLocationClient(context, platformClock),
                            platformClock,
                            SystemClock::elapsedRealtime,
                        ).locationForSave()
                    },
                )
                val result = runner.captureUntilTerminal(
                    onAttemptStarted = {
                        if (generation == recordLocationGeneration) {
                            updateEditor { current ->
                                if (current.pendingLocation == null && current.draft.locationRecordId == null) {
                                    current.copy(locationPresentation = RecordLocationEditorState.Locating)
                                } else {
                                    current
                                }
                            }
                        }
                    },
                    onAttemptTimedOut = {
                        if (generation == recordLocationGeneration) {
                            updateEditor { current ->
                                if (current.pendingLocation == null && current.draft.locationRecordId == null) {
                                    current.copy(locationPresentation = RecordLocationEditorState.Timeout)
                                } else {
                                    current
                                }
                            }
                        }
                    },
                )
                if (generation != recordLocationGeneration) return@launch
                when (result.disposition) {
                    LocationSaveDisposition.LOCATED -> applyCapturedRecordLocation(result.location ?: return@launch)
                    LocationSaveDisposition.PERMISSION_DENIED -> {
                        recordLocationShouldRun = false
                        updateEditor { it.copy(locationPresentation = RecordLocationEditorState.PermissionDenied) }
                    }
                    LocationSaveDisposition.UNAVAILABLE -> {
                        recordLocationShouldRun = false
                        updateEditor { it.copy(locationPresentation = RecordLocationEditorState.ServiceUnavailable) }
                    }
                    LocationSaveDisposition.TIMED_OUT -> Unit
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == recordLocationGeneration) {
                    recordLocationShouldRun = false
                    updateEditor { it.copy(locationPresentation = RecordLocationEditorState.ServiceUnavailable) }
                }
            } finally {
                if (generation == recordLocationGeneration) recordLocationJob = null
            }
        }
    }

    private fun applyCapturedRecordLocation(location: app.ledger.finance.application.CapturedLocation) {
        val pending = RecordPendingLocation(
            location.latitudeE7,
            location.longitudeE7,
            location.accuracyMillimeters,
            location.capturedAt,
            when (location.provider) {
                app.ledger.finance.application.CapturedLocationProvider.FUSED -> OrdinaryLocationProvider.FUSED
                app.ledger.finance.application.CapturedLocationProvider.GPS -> OrdinaryLocationProvider.GPS
                app.ledger.finance.application.CapturedLocationProvider.NETWORK -> OrdinaryLocationProvider.NETWORK
            },
        )
        val meters = location.accuracyMillimeters?.let { value ->
            context.getString(app.ledger.feature.record.R.string.record_accuracy_meters, value / 1000.0)
        } ?: context.getString(app.ledger.feature.record.R.string.record_accuracy_unknown)
        recordLocationShouldRun = false
        updateEditor { current ->
            if (current.draft.locationRecordId != null || current.pendingLocation != null) return@updateEditor current
            current.copy(
                pendingLocation = pending,
                locationPresentation = RecordLocationEditorState.Located(
                    meters,
                    context.getString(app.ledger.feature.record.R.string.record_location_new_pin),
                ),
            )
        }
    }

    private fun stopRecordLocationAcquisition() {
        recordLocationShouldRun = false
        recordLocationGeneration++
        recordLocationJob?.cancel()
        recordLocationJob = null
    }

    fun setRecordLocationHostForeground(foreground: Boolean) {
        if (recordLocationHostForeground == foreground) return
        recordLocationHostForeground = foreground
        if (foreground) {
            if (recordLocationShouldRun) startRecordLocationAcquisition()
        } else {
            recordLocationGeneration++
            recordLocationJob?.cancel()
            recordLocationJob = null
        }
    }

    fun selectRecordLocationPoint(locationId: StableId) {
        stopRecordLocationAcquisition()
        updateEditor { editor ->
            val location = editor.snapshot.references.locations.singleOrNull { it.id == locationId } ?: return@updateEditor editor
            val place = editor.snapshot.references.places.singleOrNull { it.id == location.placeId }
            editor.copy(
                draft = editor.draft.copy(
                    locationRecordId = location.id,
                    touched = editor.draft.touched + RecordField.LOCATION,
                ),
                pendingLocation = null,
                locationPresentation = RecordLocationEditorState.Manual(
                    place?.name ?: context.getString(app.ledger.feature.record.R.string.record_saved_location),
                ),
            )
        }
    }

    fun moveRecordLocationPin(latitudeE7: Int, longitudeE7: Int) {
        stopRecordLocationAcquisition()
        updateEditor { editor ->
            editor.copy(
                draft = editor.draft.copy(
                    locationRecordId = null,
                    touched = editor.draft.touched + RecordField.LOCATION,
                ),
                pendingLocation = RecordPendingLocation(
                    latitudeE7,
                    longitudeE7,
                    null,
                    runtimeSources.clock.now(),
                    OrdinaryLocationProvider.MANUAL,
                ),
                locationPresentation = RecordLocationEditorState.Manual(
                    context.getString(app.ledger.feature.record.R.string.record_location_new_pin),
                ),
            )
        }
    }

    fun recordLocationMapUnavailable() = updateEditor {
        it.copy(locationMapUnavailable = true)
    }

    fun retryRecordLocation() {
        if (!context.hasLedgerLocationPermission()) {
            if (navigator.currentKey.contract.screenId.value != "SYS-001") {
                navigator.navigate(LedgerRouteContract.destination(ScreenId("SYS-001")), SessionGateState.READY)
            }
            return
        }
        updateEditor { editor ->
            editor.copy(
                draft = editor.draft.copy(touched = editor.draft.touched - RecordField.LOCATION),
                pendingLocation = null,
                locationPresentation = RecordLocationEditorState.NotRequested,
            )
        }
        startRecordLocationAcquisition(restart = true)
    }

    fun openRecordLocationSettings() {
        recordLocationShouldRun = true
        context.startActivity(
            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun useRecordLocation() {
        if (navigator.currentKey.contract.screenId.value == "REC-009") navigator.pop()
    }

    fun clearRecordLocation() {
        stopRecordLocationAcquisition()
        updateEditor { editor ->
            editor.copy(
                draft = editor.draft.copy(
                    locationRecordId = null,
                    touched = editor.draft.touched + RecordField.LOCATION,
                ),
                pendingLocation = null,
                locationPresentation = RecordLocationEditorState.Cleared,
            )
        }
        useRecordLocation()
    }
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
    fun setRecordManualLocation(latitudeE7: Int, longitudeE7: Int) = updateEditor { editor ->
        OrdinaryRecordPolicy.updateManualLocation(
            editor,
            OrdinaryLocationDraft(
                id = editor.draft.newLocation?.id ?: nextId(),
                latitudeE7 = latitudeE7,
                longitudeE7 = longitudeE7,
                accuracyMillimeters = null,
                capturedAt = runtimeSources.clock.now(),
                provider = OrdinaryLocationProvider.MANUAL,
                placeId = null,
            ),
        )
    }

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
                    val importedId = result.value.attachmentId.value
                    val imported = RecordAttachmentPresentation(
                        attachmentId = importedId,
                        displayName = metadata.first,
                        sizeText = formatPresentationFileSize(result.value.plaintextSize),
                        typeLabel = request.mimeType ?: "application/octet-stream",
                    )
                    it.copy(
                        draft = it.draft.copy(
                            attachmentIds = it.draft.attachmentIds + importedId,
                            touched = it.draft.touched + RecordField.ATTACHMENTS,
                        ),
                        attachmentImporting = false,
                        attachmentFailureCode = null,
                        uncommittedAttachmentIds = it.uncommittedAttachmentIds + importedId,
                        attachmentPresentations = it.attachmentPresentations + imported,
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
                attachmentPresentations = it.attachmentPresentations.filterNot { item -> item.attachmentId == attachmentId },
            )
        }
        if (attachmentId in editor.uncommittedAttachmentIds) {
            viewModelScope.launch(Dispatchers.IO) {
                bookAttachmentObjectPort.discardUncommitted(editor.snapshot.references.bookId, app.ledger.finance.domain.AttachmentId(attachmentId))
            }
        }
    }

    fun openRecordAttachment(index: Int) {
        val editor = (mutableOrdinaryRecord.value as? OrdinaryRecordLoadState.Content)?.editor ?: return
        val id = editor.draft.attachmentIds.getOrNull(index) ?: return
        openAttachment(editor.snapshot.references.bookId, id)
    }

    fun openSpecializedAttachment(index: Int) {
        val editor = (mutableSpecializedTransaction.value as? SpecializedTransactionLoadState.Content)?.editor ?: return
        val id = editor.draft.attachmentIds.getOrNull(index) ?: return
        openAttachment(editor.snapshot.bookId, id)
    }

    fun ensureAttachmentLoaded(attachmentId: StableId) {
        if (attachmentController.state.value.attachmentId?.value == attachmentId && attachmentController.imageLoader != null) return
        val activeBook = ((mutableRootState.value as? AppRootState.Session)?.state as? BookSessionState.Ready)?.bookId ?: return
        attachmentController.prepare(app.ledger.finance.domain.AttachmentId(attachmentId))
        viewModelScope.launch(Dispatchers.IO) {
            attachmentController.open(activeBook, app.ledger.finance.domain.AttachmentId(attachmentId))
        }
    }

    fun retryAttachment() {
        viewModelScope.launch(Dispatchers.IO) { attachmentController.retry() }
    }

    fun openAttachmentRename() = navigateAttachment("ATT-003")

    fun openAttachmentExternal() = navigateAttachment("ATT-002")

    fun changeAttachmentName(value: String) = attachmentController.renameChanged(value)

    fun saveAttachmentName(onSaved: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            if (attachmentController.commitRename()) {
                withContext(Dispatchers.Main.immediate) {
                    navigator.pop()
                    onSaved()
                }
            }
        }
    }

    fun authorizeAttachmentExternalOpen(): Intent? = attachmentController.externalOpenIntent()

    fun attachmentImageLoader() = attachmentController.imageLoader

    fun dismissAttachmentDialog() = navigator.pop()

    private fun openAttachment(activeBook: StableId, attachmentId: StableId) {
        val typedId = app.ledger.finance.domain.AttachmentId(attachmentId)
        attachmentController.prepare(typedId)
        val screenId = ScreenId("ATT-001")
        navigator.navigate(
            LedgerRouteContract.destination(screenId, mapOf("attachmentId" to StableIdArgument(attachmentId))),
            SessionGateState.READY,
        )
        viewModelScope.launch(Dispatchers.IO) { attachmentController.open(activeBook, typedId) }
    }

    private fun navigateAttachment(target: String) {
        val attachmentId = attachmentController.state.value.attachmentId?.value ?: return
        val screenId = ScreenId(target)
        navigator.navigate(
            LedgerRouteContract.destination(screenId, mapOf("attachmentId" to StableIdArgument(attachmentId))),
            SessionGateState.READY,
        )
    }

    fun loadRefund(presetOriginalId: StableId? = null) {
        val ready = (mutableRootState.value as? AppRootState.Session)?.state as? BookSessionState.Ready ?: return
        val previous = mutableRefund.value as? RefundLoadState.Content
        val lease = beginFeatureLoad(
            FEATURE_LOAD_REFUND,
            AppFeatureLoadKey(ready.bookId, ready.generation, "REC-015", listOf(presetOriginalId?.toString())),
            previous != null,
        ) {
            mutableRefund.value = previous ?: RefundLoadState.Failure("REFUND_LOAD_TIMEOUT")
        } ?: return
        if (previous == null) mutableRefund.value = RefundLoadState.Loading
        refundSearchLoader.reset()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val saved = settingsRepository.current()
                val bookId = requireBookId(saved)
                when (val result = refundApplicationPort.snapshot(bookId)) {
                    is DomainResult.Failure -> publishFeatureLoad(lease, reusable = false) {
                        mutableRefund.value = previous ?: RefundLoadState.Failure(sanitizeCode(result.error.code))
                    }
                    is DomainResult.Success -> {
                        val editor = RefundPolicy.create(
                            result.value,
                            presetOriginalId,
                            runtimeSources.clock.now(),
                            ZoneId.of(saved.zoneId.ifBlank { DEFAULT_ZONE }),
                            recordLocale(),
                        )
                        publishFeatureLoad(lease, reusable = true) {
                            mutableRefund.value = RefundLoadState.Content(editor)
                            mutableRefundPicker.value = RefundPickerState.Content(result.value)
                        }
                    }
                }
            } catch (_: Exception) {
                publishFeatureLoad(lease, reusable = false) {
                    mutableRefund.value = previous ?: RefundLoadState.Failure("REFUND_LOAD_FAILED")
                }
            }
        }
    }

    fun loadRefundOriginals(query: RefundSearchQuery = RefundSearchQuery()) {
        val editor = (mutableRefund.value as? RefundLoadState.Content)?.editor
        val bookId = editor?.snapshot?.references?.bookId ?: return loadRefund()
        val current = mutableRefundPicker.value as? RefundPickerState.Content
        if (current?.query == query) return
        if (current != null) mutableRefundPicker.value = current.copy(query = query, searching = true)
        val generation = activeBookSessionRuntime.readyGeneration(bookId) ?: return
        refundSearchLoader.request(RefundSearchLoadKey(bookId, generation, query))
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

    fun selectRefundAccount(id: StableId) = updateRefund { RefundPolicy.selectAccount(it, id, recordLocale()) }

    fun selectRefundCard(id: StableId?) = updateRefund { RefundPolicy.selectCard(it, id) }

    fun selectRefundCategory(id: StableId) = updateRefund { editor ->
        val exists = editor.snapshot.references.categories.any {
            it.id == id && it.status == app.ledger.finance.domain.CategoryStatus.ACTIVE && it.direction == CategoryDirection.EXPENSE
        }
        if (!exists) {
            val screenId = ScreenId("CAT-002")
            navigator.navigate(
                LedgerRouteContract.destination(
                    screenId,
                    mapOf("direction" to LedgerRouteContract.enumArgument(screenId, "direction", CategoryDirection.EXPENSE.name)),
                ),
                SessionGateState.READY,
            )
            return@updateRefund editor
        }
        RefundPolicy.updateReference(editor, app.ledger.feature.record.RefundField.CATEGORY, id)
    }
    fun openRefundCategoryCreator() {
        val screenId = ScreenId("CAT-002")
        navigator.navigate(
            LedgerRouteContract.destination(
                screenId,
                mapOf("direction" to LedgerRouteContract.enumArgument(screenId, "direction", CategoryDirection.EXPENSE.name)),
            ),
            SessionGateState.READY,
        )
    }
    fun selectRefundMerchant(id: StableId?) = updateRefund { RefundPolicy.updateInherited(it, merchantId = id) }
    fun selectRefundProject(id: StableId?) = updateRefund { RefundPolicy.updateInherited(it, projectId = id) }
    fun selectRefundGoal(id: StableId?) = updateRefund { RefundPolicy.updateInherited(it, goalId = id) }

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
                        mutableSuccessHapticEvents.tryEmit(Unit)
                        loadReferenceDataAfterMutation(validated.snapshot.references.bookId)
                        invalidateFeatureLoad(FEATURE_LOAD_REFUND)
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

    fun loadSpecializedTransaction(
        screenId: String,
        presetAccountId: StableId? = null,
        transactionId: StableId? = null,
    ) {
        val ready = (mutableRootState.value as? AppRootState.Session)?.state as? BookSessionState.Ready ?: return
        val kind = when (screenId) {
            "REC-013" -> SpecializedTransactionKind.TRANSFER
            "REC-020" -> SpecializedTransactionKind.BALANCE_ADJUSTMENT
            "REC-021" -> SpecializedTransactionKind.FX_EXCHANGE
            "REC-022" -> SpecializedTransactionKind.OPENING_BALANCE
            else -> return
        }
        val previous = mutableSpecializedTransaction.value as? SpecializedTransactionLoadState.Content
        val lease = beginFeatureLoad(
            FEATURE_LOAD_SPECIALIZED,
            AppFeatureLoadKey(
                ready.bookId,
                ready.generation,
                screenId,
                listOf(presetAccountId?.toString(), transactionId?.toString()),
            ),
            previous != null,
        ) {
            mutableSpecializedTransaction.value = previous
                ?: SpecializedTransactionLoadState.Failure("SPECIALIZED_LOAD_TIMEOUT")
        } ?: return
        if (previous == null) mutableSpecializedTransaction.value = SpecializedTransactionLoadState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val saved = settingsRepository.current()
                val bookId = requireBookId(saved)
                when (val result = specializedTransactionEntryPort.snapshot(bookId, transactionId)) {
                    is DomainResult.Failure -> publishFeatureLoad(lease, reusable = false) {
                        mutableSpecializedTransaction.value = previous
                            ?: SpecializedTransactionLoadState.Failure(sanitizeCode(result.error.code))
                    }
                    is DomainResult.Success -> {
                        val editor = SpecializedTransactionPolicy.create(
                            kind,
                            result.value.references,
                            presetAccountId,
                            runtimeSources.clock.now(),
                            ZoneId.of(saved.zoneId.ifBlank { DEFAULT_ZONE }),
                            recordLocale(),
                            result.value.editing,
                        )
                        val published = publishFeatureLoad(lease, reusable = true) {
                            mutableSpecializedTransaction.value = SpecializedTransactionLoadState.Content(editor)
                        }
                        if (published) quoteSpecializedRates(refreshOnline = true)
                    }
                }
            } catch (_: Exception) {
                publishFeatureLoad(lease, reusable = false) {
                    mutableSpecializedTransaction.value = previous
                        ?: SpecializedTransactionLoadState.Failure("SPECIALIZED_LOAD_FAILED")
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

    fun selectSpecializedAccount(incoming: Boolean, accountId: StableId) {
        updateSpecialized { SpecializedTransactionPolicy.selectAccount(it, incoming, accountId) }
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

    fun specializedOccurredAt(occurredAt: Instant) = updateSpecialized {
        SpecializedTransactionPolicy.changeOccurredAt(it, occurredAt)
    }
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
                    val importedId = result.value.attachmentId.value
                    val imported = RecordAttachmentPresentation(
                        attachmentId = importedId,
                        displayName = metadata.first,
                        sizeText = formatPresentationFileSize(result.value.plaintextSize),
                        typeLabel = request.mimeType ?: "application/octet-stream",
                    )
                    it.copy(
                        draft = it.draft.copy(attachmentIds = it.draft.attachmentIds + importedId, dirty = true),
                        attachmentImporting = false,
                        uncommittedAttachmentIds = it.uncommittedAttachmentIds + importedId,
                        attachmentPresentations = it.attachmentPresentations + imported,
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
                attachmentPresentations = it.attachmentPresentations.filterNot { item -> item.attachmentId == id },
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
                    transactionId = validated.transactionId ?: nextId(),
                    revisionId = nextId(),
                    commitId = nextId(),
                    deviceInstanceId = nextId(),
                    factIds = List(FINANCIAL_FACT_ID_RESERVE) { nextId() },
                    fxRateSnapshotIds = List(FX_ID_RESERVE) { nextId() },
                    expectedRevisionId = validated.expectedRevisionId,
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
                        mutableSuccessHapticEvents.tryEmit(Unit)
                        loadReferenceDataAfterMutation(validated.snapshot.bookId)
                        invalidateFeatureLoad(FEATURE_LOAD_SPECIALIZED)
                        if (validated.transactionId != null) {
                            refreshJournalPaging()
                            loadJournalDetail(ids.transactionId)
                        }
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
        stopRecordLocationAcquisition()
        updateEditor { editor ->
            if (editor.locationPresentation in setOf(RecordLocationEditorState.Locating, RecordLocationEditorState.Timeout)) {
                editor.copy(locationPresentation = RecordLocationEditorState.NotRequested)
            } else {
                editor
            }
        }
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
        val requestTrace = LedgerSessionPerformance.begin(LedgerInteractionOperation.SAVE_REQUEST)
        mutableOrdinaryRecordPending.value = true
        mutableOrdinaryRecord.value = content.copy(editor = validated.copy(presentation = RecordEditorPresentation.SAVING, errors = emptyList()))
        val saveJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val pendingLocation = validated.pendingLocation
                val locationId = pendingLocation?.let { nextId() }
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
                    newLocation = pendingLocation?.let { captured ->
                        OrdinaryLocationDraft(
                            requireNotNull(locationId),
                            captured.latitudeE7,
                            captured.longitudeE7,
                            captured.accuracyMillimeters,
                            captured.capturedAt,
                            captured.provider,
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
                val commitTrace = LedgerSessionPerformance.begin(LedgerInteractionOperation.SAVE_COMMIT)
                val result = try {
                    ordinaryTransactionEntryPort.submit(request)
                } finally {
                    commitTrace.close()
                }
                when (result) {
                    is DomainResult.Success -> {
                        val settledTrace = LedgerSessionPerformance.begin(LedgerInteractionOperation.SAVE_SETTLED)
                        try {
                            mutableSuccessHapticEvents.tryEmit(Unit)
                            if (validated.mode == RecordEditorMode.CANDIDATE) pendingCandidateId = null
                            finishRecordSave(validated, ids.transactionId)
                        } finally {
                            settledTrace.close()
                        }
                    }
                    is DomainResult.Failure -> {
                        val code = sanitizeCode(result.error.code)
                        val presentation = if (code.contains("STALE") || code.contains("REVISION")) RecordEditorPresentation.REVISION_CONFLICT else RecordEditorPresentation.SAVE_ERROR
                        updateEditor { it.copy(presentation = presentation, sanitizedFailureCode = code) }
                    }
                }
            } finally {
                requestTrace.close()
                mutableOrdinaryRecordPending.value = false
            }
        }
        saveJob.invokeOnCompletion { requestTrace.close() }
    }

    fun loadBudget(month: YearMonth, templateId: StableId? = null, screenId: String = "BUD-001") {
        val ready = (mutableRootState.value as? AppRootState.Session)?.state as? BookSessionState.Ready ?: return
        val key = BudgetLoadKey(ready.bookId, ready.generation, month, templateId, screenId)
        val visible = mutableBudget.value as? BudgetLoadState.Content
        if (currentBudgetLoadKey == key && (visible != null || currentBudgetLoadJob?.isActive == true)) return
        currentBudgetLoadJob?.cancel()
        currentBudgetLoadKey = key
        if (visible == null) mutableBudget.value = BudgetLoadState.Loading
        currentBudgetLoadJob = viewModelScope.launch(Dispatchers.IO) {
            val bookId = runCatching { requireBookId(settingsRepository.current()) }.getOrNull() ?: return@launch
            val today = runtimeSources.clock.now().atZone(ZoneId.of(settingsRepository.current().zoneId.ifBlank { DEFAULT_ZONE })).toLocalDate()
            val result = withTimeoutOrNull(DEFAULT_INTERACTIVE_LOAD_TIMEOUT_MILLIS) {
                budgetApplicationPort.snapshot(bookId, month, today)
            }
            val loaded = when (result) {
                null -> visible ?: BudgetLoadState.Failure("BUDGET_LOAD_TIMEOUT")
                is DomainResult.Failure -> visible ?: BudgetLoadState.Failure(sanitizeCode(result.error.code))
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
            if (currentBudgetLoadKey == key && activeBookSessionRuntime.readyGeneration(bookId) == key.sessionGeneration) {
                mutableBudget.value = loaded
                if (loaded is BudgetLoadState.Content) {
                    markTopLevelContent(bookId, TopLevelDestination.BUDGET)
                }
                currentBudgetLoadJob = null
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
    fun clearBudgetCategory(id: StableId) = updateBudget { BudgetPolicy.clearCategory(it, id) }

    fun applyBudgetTemplate(id: StableId) = updateBudget { state ->
        val template = state.snapshot.templates.singleOrNull { it.id == id } ?: return@updateBudget state
        BudgetPolicy.validate(
            state.copy(
                editor = state.editor.copy(
                    totalText = budgetMinorText(template.revision.totalBaseMinor, state.snapshot.baseCurrency),
                    categoryTexts = template.revision.limits.associate { limit ->
                        limit.categoryId to budgetMinorText(limit.amountBaseMinor, state.snapshot.baseCurrency)
                    },
                    dirty = true,
                ),
                presentation = BudgetPresentation.EDITING,
            ),
        )
    }
    fun updateBudgetTemplateName(value: String) = updateBudget { BudgetPolicy.updateTemplateName(it, value) }
    fun updateBudgetAdjustmentAmount(value: String) = updateBudget { BudgetPolicy.updateAdjustmentAmount(it, value) }
    fun selectBudgetAdjustmentSource(categoryId: StableId) = updateBudget { BudgetPolicy.selectAdjustmentSource(it, categoryId) }
    fun selectBudgetAdjustmentTarget(categoryId: StableId) = updateBudget { BudgetPolicy.selectAdjustmentTarget(it, categoryId) }

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
        if (target == "BUD-005" && adjustmentKind == BudgetAdjustmentKind.ARCHIVED_CATEGORY_TRANSFER) {
            updateBudget { state ->
                state.copy(
                    adjustmentSourceCategoryId = state.snapshot.categories
                        .firstOrNull { it.status == app.ledger.finance.domain.EntityStatus.ARCHIVED }
                        ?.id,
                )
            }
        }
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
                    BudgetAdjustmentKind.ARCHIVED_CATEGORY_TRANSFER -> "TRANSFER"
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
        val ready = (mutableRootState.value as? AppRootState.Session)?.state as? BookSessionState.Ready ?: return
        val previous = mutableProjectGoal.value as? ProjectGoalLoadState.Content
        val lease = beginFeatureLoad(
            FEATURE_LOAD_PROJECT_GOAL,
            AppFeatureLoadKey(
                ready.bookId,
                ready.generation,
                screenId,
                listOf(projectId?.toString(), goalId?.toString(), movementKind?.name),
            ),
            previous != null,
        ) {
            mutableProjectGoal.value = previous ?: ProjectGoalLoadState.Failure("PROJECT_GOAL_LOAD_TIMEOUT")
        } ?: return
        if (previous == null) mutableProjectGoal.value = ProjectGoalLoadState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            val appSettings = settingsRepository.current()
            val bookId = runCatching { requireBookId(appSettings) }.getOrNull()
            if (bookId == null) {
                publishFeatureLoad(lease, reusable = false) {
                    mutableProjectGoal.value = previous ?: ProjectGoalLoadState.Failure("PROJECT_GOAL_BOOK_UNAVAILABLE")
                }
                return@launch
            }
            val today = runtimeSources.clock.now().atZone(ZoneId.of(appSettings.zoneId.ifBlank { DEFAULT_ZONE })).toLocalDate()
            val loaded = when (val result = projectGoalApplicationPort.snapshot(bookId)) {
                is DomainResult.Failure -> previous ?: ProjectGoalLoadState.Failure(sanitizeCode(result.error.code))
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
            publishFeatureLoad(lease, reusable = loaded is ProjectGoalLoadState.Content) {
                mutableProjectGoal.value = loaded
            }
        }
    }

    fun updateProjectName(value: String) = updateProjectGoal { ProjectGoalPolicy.projectName(it, value) }
    fun updateProjectDescription(value: String) = updateProjectGoal { ProjectGoalPolicy.projectDescription(it, value) }
    fun updateProjectStartDate(value: String) = updateProjectGoal { ProjectGoalPolicy.projectStartDate(it, value) }
    fun updateProjectEndDate(value: String) = updateProjectGoal { ProjectGoalPolicy.projectEndDate(it, value) }
    fun updateProjectBudget(value: String) = updateProjectGoal { ProjectGoalPolicy.projectBudget(it, value) }
    fun toggleProjectMonthlyBudget() = updateProjectGoal(ProjectGoalPolicy::toggleMonthlyBudget)
    fun selectProjectGoal(goalId: StableId?) = updateProjectGoal { ProjectGoalPolicy.selectGoal(it, goalId) }
    fun updateGoalName(value: String) = updateProjectGoal { ProjectGoalPolicy.goalName(it, value) }
    fun updateGoalTarget(value: String) = updateProjectGoal { ProjectGoalPolicy.goalTarget(it, value) }
    fun updateGoalSuggested(value: String) = updateProjectGoal { ProjectGoalPolicy.goalSuggested(it, value) }
    fun updateGoalDueDate(value: String) = updateProjectGoal { ProjectGoalPolicy.goalDueDate(it, value) }
    fun selectGoalAccount(accountId: StableId) = updateProjectGoal { ProjectGoalPolicy.selectAccount(it, accountId) }
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
        if (!beginProjectGoalMutation(state.copy(presentation = ProjectGoalPresentation.SAVING))) return
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
                finishProjectGoalMutation(
                    projectGoalApplicationPort.saveProject(request),
                    if (existing == null) "PRJ-001" else "PRJ-003",
                    existing?.id,
                )
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
        if (!beginProjectGoalMutation(state.copy(presentation = ProjectGoalPresentation.SAVING))) return
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
                finishProjectGoalMutation(
                    projectGoalApplicationPort.saveGoal(request),
                    if (existing == null) "GOL-001" else "GOL-003",
                    existing?.id,
                )
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
        val validated = ProjectGoalPolicy.movementAmount(state, state.movementAmountText)
        if (validated.goalErrors.isNotEmpty()) {
            mutableProjectGoal.value = ProjectGoalLoadState.Content(validated)
            return
        }
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

    fun switchProjectView(target: String, projectId: StableId) {
        if (navigator.currentKey.contract.screenId.value in setOf("PRJ-003", "PRJ-004", "PRJ-005")) navigator.pop()
        val screenId = ScreenId(target)
        navigator.navigate(
            LedgerRouteContract.destination(screenId, mapOf("projectId" to StableIdArgument(projectId))),
            SessionGateState.READY,
        )
    }

    fun openProjectTransaction(transactionId: StableId) {
        loadJournalDetail(transactionId)
        navigator.navigate(
            LedgerRouteContract.destination(ScreenId("JRN-007"), mapOf("transactionId" to StableIdArgument(transactionId))),
            SessionGateState.READY,
        )
    }

    fun setProjectTransactionKind(kind: TransactionKind?) {
        mutableProjectTransactionPagingRequest.value = mutableProjectTransactionPagingRequest.value?.copy(kind = kind)
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
                withContext(Dispatchers.Main.immediate) {
                    navigator.pop()
                    if (navigator.currentKey.contract.screenId.value != target) navigateProjectGoal(target, stableId, null)
                }
                mutableGlobalSnackbarMessages.emit(GlobalSnackbarMessage.PLANNING_UPDATED)
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
        val ready = (mutableRootState.value as? AppRootState.Session)?.state as? BookSessionState.Ready ?: return
        currentCreditScreenId = screenId
        currentCreditTransactionId = transactionId
        val previous = mutableCredit.value as? CreditLoadState.Content
        val lease = beginFeatureLoad(
            FEATURE_LOAD_CREDIT,
            AppFeatureLoadKey(
                ready.bookId,
                ready.generation,
                screenId,
                listOf(accountId?.toString(), statementId?.toString(), transactionId?.toString()),
            ),
            previous != null,
        ) {
            mutableCredit.value = previous ?: CreditLoadState.Failure("CREDIT_LOAD_TIMEOUT")
        } ?: return
        if (previous == null) mutableCredit.value = CreditLoadState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val appSettings = settingsRepository.current()
                val bookId = requireBookId(appSettings)
                val loaded = when (val result = creditApplicationPort.snapshot(bookId)) {
                    is DomainResult.Failure -> previous ?: CreditLoadState.Failure(sanitizeCode(result.error.code))
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
                            val transactionAmount = transactionId?.let { id ->
                                (journalApplicationPort.detail(bookId, id) as? DomainResult.Success)?.value?.transaction?.amountMinor
                            }
                            CreditLoadState.Content(
                                state.copy(
                                    draft = state.draft.copy(
                                        date = state.draft.date.ifBlank { runtimeSources.clock.now().atZone(zone).toLocalDate().toString() },
                                        amount = transactionAmount?.let { amount -> state.account?.let { CreditPolicy.minorText(amount, it.currency) } } ?: state.draft.amount,
                                    ),
                                ),
                            )
                        }
                    }
                }
                publishFeatureLoad(lease, reusable = loaded is CreditLoadState.Content) {
                    mutableCredit.value = loaded
                    (loaded as? CreditLoadState.Content)?.let { creditDraftBaseline = it.state.draft }
                }
            } catch (_: Exception) {
                publishFeatureLoad(lease, reusable = false) {
                    mutableCredit.value = previous ?: CreditLoadState.Failure("CREDIT_LOAD_FAILED")
                }
            }
        }
    }

    fun ensureCreditLoaded(
        screenId: String,
        accountId: StableId? = null,
        statementId: StableId? = null,
        transactionId: StableId? = null,
    ) {
        val current = (mutableCredit.value as? CreditLoadState.Content)?.state
        val sameRoute = currentCreditScreenId == screenId && currentCreditTransactionId == transactionId &&
            (accountId == null || current?.selectedAccountId == accountId) &&
            (statementId == null || current?.selectedStatementId == statementId)
        if (current != null && sameRoute) return
        loadCredit(screenId, accountId, statementId, transactionId)
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

    fun selectCreditPaymentAccount(accountId: StableId?) = updateCredit { state ->
        val selected = state.snapshot.paymentAccounts.singleOrNull { it.id == accountId && it.active }
        state.copy(
            draft = state.draft.copy(selectedPaymentAccountId = selected?.id),
            presentation = CreditPresentation.EDITING,
            validationFields = state.validationFields - "paymentAccount",
        )
    }

    fun selectNextCreditZone() = updateCredit { state ->
        state
    }

    fun selectCreditZone(zoneId: String) = updateCredit { state ->
        if (runCatching { ZoneId.of(zoneId) }.isFailure) state else CreditPolicy.updateDraft(state, CreditField.ZONE, zoneId)
    }

    fun cycleCreditDueRule() = updateCredit { state ->
        state.copy(
            draft = state.draft.copy(
                dueRuleMode = if (state.draft.dueRuleMode == app.ledger.feature.liabilities.CreditDueRuleMode.FIXED_DAY) {
                    app.ledger.feature.liabilities.CreditDueRuleMode.DAYS_AFTER_STATEMENT
                } else {
                    app.ledger.feature.liabilities.CreditDueRuleMode.FIXED_DAY
                },
            ),
            presentation = CreditPresentation.EDITING,
            validationFields = emptySet(),
        )
    }

    fun toggleCreditStatementSeal(sealed: Boolean) = updateCredit { state ->
        state.copy(draft = state.draft.copy(sealOfficial = sealed), presentation = CreditPresentation.EDITING)
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
        val formalEnabled = enabled && CreditPolicy.formalAutoPaymentEligible(state)
        state.copy(
            draft = state.draft.copy(autoPaymentMode = if (formalEnabled) AutoGenerationMode.FORMAL_TRANSACTION else AutoGenerationMode.CONFIRMATION_CANDIDATE),
            presentation = CreditPolicy.autoPresentation(
                state.account,
                state.statement,
                if (formalEnabled) AutoGenerationMode.FORMAL_TRANSACTION else AutoGenerationMode.CONFIRMATION_CANDIDATE,
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
            if (stableId != null && target == "JRN-007") put("transactionId", StableIdArgument(stableId))
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
        val errors = CreditPolicy.profileErrors(state)
        if (errors.isNotEmpty()) {
            mutableCredit.value = CreditLoadState.Content(state.copy(presentation = CreditPresentation.VALIDATION_ERROR, validationFields = errors))
            return
        }
        if (!beginCreditMutation(state.copy(presentation = CreditPresentation.SAVING))) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val now = runtimeSources.clock.now()
                val profileRequest = SaveCreditProfileRequest(
                    CreditMutationIds(state.snapshot.bookId, CommandId(nextId()), nextId(), nextId()),
                    account.id,
                    account.profile?.lastCommitId,
                    StatementDateRule.DayOfMonth(requireNotNull(statementDay), MissingDayPolicy.MOVE_TO_MONTH_END),
                    if (state.draft.dueRuleMode == app.ledger.feature.liabilities.CreditDueRuleMode.FIXED_DAY) {
                        DueDateRule.FixedDay(requireNotNull(dueDay), MissingDayPolicy.MOVE_TO_MONTH_END)
                    } else {
                        DueDateRule.DaysAfterStatement(requireNotNull(dueDay))
                    },
                    ZoneId.of(state.draft.zoneId),
                    standard,
                    temporary,
                    expires,
                    state.draft.selectedPaymentAccountId,
                    state.draft.autoPaymentMode,
                    WeekendAdjustment.NEXT_BUSINESS_DAY,
                    now.atZone(ZoneId.of(state.draft.zoneId)).toLocalDate().takeIf { standard != account.profile?.standardLimitMinor },
                    now,
                )
                val pendingAccount = pendingCreditAccountDraft?.takeIf { it.accountId == account.id }
                val result = if (pendingAccount == null) {
                    creditApplicationPort.saveProfile(profileRequest)
                } else {
                    creditApplicationPort.createAccountWithProfile(CreateCreditAccountProfileRequest(pendingAccount, profileRequest))
                }
                if (result is DomainResult.Success && pendingAccount != null) {
                    pendingCreditAccountDraft = null
                    loadReferenceDataAfterMutation(state.snapshot.bookId)
                }
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
                        state.draft.sealOfficial,
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
        val ready = (mutableRootState.value as? AppRootState.Session)?.state as? BookSessionState.Ready ?: return
        currentInstallmentScreenId = screenId
        val previous = mutableInstallment.value as? InstallmentLoadState.Content
        val lease = beginFeatureLoad(
            FEATURE_LOAD_INSTALLMENT,
            AppFeatureLoadKey(
                ready.bookId,
                ready.generation,
                screenId,
                listOf(planId?.toString(), purchaseId?.toString()),
            ),
            previous != null,
        ) {
            mutableInstallment.value = previous ?: InstallmentLoadState.Failure("INSTALLMENT_LOAD_TIMEOUT")
        } ?: return
        if (previous == null) mutableInstallment.value = InstallmentLoadState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            val saved = settingsRepository.current()
            val bookId = runCatching { requireBookId(saved) }.getOrNull()
            if (bookId == null) {
                publishFeatureLoad(lease, reusable = false) {
                    mutableInstallment.value = previous ?: InstallmentLoadState.Failure("INSTALLMENT_BOOK_UNAVAILABLE")
                }
                return@launch
            }
            val zone = ZoneId.of(saved.zoneId.ifBlank { DEFAULT_ZONE })
            val today = runtimeSources.clock.now().atZone(zone).toLocalDate()
            val loaded = when (val result = installmentApplicationPort.snapshot(bookId, today)) {
                is DomainResult.Failure -> previous ?: InstallmentLoadState.Failure(sanitizeCode(result.error.code))
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
            publishFeatureLoad(lease, reusable = loaded is InstallmentLoadState.Content) {
                mutableInstallment.value = loaded
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

    fun updateInstallmentRoundingMode(mode: RoundingMode) = updateInstallment { state ->
        state.copy(draft = state.draft.copy(roundingMode = mode), presentation = InstallmentPresentation.EDITING, previewSchedule = null)
    }

    fun selectInstallmentPurchase(purchaseId: StableId) = updateInstallment { state ->
        val purchase = state.snapshot.purchases.singleOrNull { it.transactionId == purchaseId }
        state.copy(
            selectedPurchaseId = purchaseId,
            selectedPlanId = null,
            draft = state.draft.copy(principal = purchase?.let { CreditPolicy.minorText(it.principalMinor, it.currency) }.orEmpty()),
            presentation = InstallmentPresentation.EDITING,
            previewSchedule = null,
        )
    }

    fun navigateInstallment(target: String, stableId: StableId?) {
        if (target == "INS-002" && stableId == null) {
            val state = (mutableInstallment.value as? InstallmentLoadState.Content)?.state
            if (state != null && state.snapshot.purchases.none { !it.alreadyLinked }) return
        }
        val screenId = ScreenId(target)
        val arguments = buildMap<String, SafeRouteArgument> {
            if (stableId != null && target == "REC-027") put("purchaseTransactionId", StableIdArgument(stableId))
            if (stableId != null && target in setOf("INS-002", "INS-003", "INS-004", "INS-005", "INS-006")) {
                put("planId", StableIdArgument(stableId))
            }
            if (stableId != null && target == "JRN-007") put("transactionId", StableIdArgument(stableId))
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
        val enteredPrincipal = CreditPolicy.parseMinor(state.draft.principal, purchase.currency)
            ?.takeIf { it > 0L && it <= purchase.principalMinor } ?: return null
        val firstStatementDate = LocalDate.parse(state.draft.firstStatementDate)
        val prepaymentFee = state.draft.prepaymentFee.takeIf(String::isNotBlank)?.let {
            CreditPolicy.parseMinor(it, purchase.currency)
        }
        if (state.draft.prepaymentFee.isNotBlank() && prepaymentFee == null) return null
        val terms = installmentTerms(state, purchase.currency, prepaymentFee) ?: return null
        val planId = plan?.id ?: nextId()
        val currentPrincipal = plan?.currentPrincipalMinor ?: enteredPrincipal
        SaveInstallmentPlanRequest(
            installmentMutationIds(state.snapshot.bookId, planId, termCount),
            purchase.transactionId,
            purchase.creditAccountId,
            purchase.currency,
            plan?.originalPrincipalMinor ?: enteredPrincipal,
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
            state.draft.roundingMode,
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
        val ready = (mutableRootState.value as? AppRootState.Session)?.state as? BookSessionState.Ready ?: return
        currentLoanScreenId = screenId
        val previous = mutableLoan.value as? LoanLoadState.Content
        val lease = beginFeatureLoad(
            FEATURE_LOAD_LOAN,
            AppFeatureLoadKey(
                ready.bookId,
                ready.generation,
                screenId,
                listOf(contractId?.toString(), trancheId?.toString(), transactionId?.toString(), simulationId?.toString()),
            ),
            previous != null,
        ) {
            mutableLoan.value = previous ?: LoanLoadState.Failure("LOAN_LOAD_TIMEOUT")
        } ?: return
        if (previous == null) mutableLoan.value = LoanLoadState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val savedSettings = settingsRepository.current()
                val bookId = requireBookId(savedSettings)
                val today = runtimeSources.clock.now()
                    .atZone(ZoneId.of(savedSettings.zoneId.ifBlank { DEFAULT_ZONE }))
                    .toLocalDate()
                val hub = activeBookSessionRuntime.withCurrentDatabase(bookId, LedgerAccessMode.READ) {
                    val loan = loanApplicationPort.snapshot(bookId)
                    if (screenId == "LIA-001" && loan is DomainResult.Success) {
                        LiabilityHubQueryResults(
                            loan,
                            creditApplicationPort.snapshot(bookId),
                            installmentApplicationPort.snapshot(bookId, today),
                        )
                    } else {
                        LiabilityHubQueryResults(loan, null, null)
                    }
                }
                val loaded = when (val result = hub.loan) {
                    is DomainResult.Failure -> previous ?: LoanLoadState.Failure(sanitizeCode(result.error.code))
                    is DomainResult.Success -> {
                        val missing = contractId != null && result.value.contracts.none { it.id == contractId } ||
                            trancheId != null && result.value.contracts.none { contract -> contract.tranches.any { it.id == trancheId } }
                        if (missing) {
                            LoanLoadState.Failure(LOAN_NOT_FOUND)
                        } else {
                            val created = LoanPolicy.create(result.value, screenId, contractId, trancheId, transactionId, simulationId).let { state ->
                                if (screenId != "LIA-001") return@let state
                                val withCredit = when (val creditResult = hub.credit) {
                                    is DomainResult.Success -> state.copy(creditAccounts = creditResult.value.accounts)
                                    is DomainResult.Failure -> state.copy(creditLoadFailureCode = sanitizeCode(creditResult.error.code))
                                    null -> state
                                }
                                val enriched = when (val installmentResult = hub.installment) {
                                    is DomainResult.Success -> withCredit.copy(installmentPlans = installmentResult.value.plans)
                                    is DomainResult.Failure -> withCredit.copy(installmentLoadFailureCode = sanitizeCode(installmentResult.error.code))
                                    null -> withCredit
                                }
                                val hasAny = enriched.creditAccounts.isNotEmpty() || enriched.snapshot.contracts.isNotEmpty() || enriched.installmentPlans.isNotEmpty()
                                val loanScheduleOverdue = enriched.snapshot.contracts.flatMap { it.tranches }.flatMap { it.schedule }.any { row ->
                                    row.plannedDate < today && row.actualPrincipalMinor + row.actualInterestMinor + row.actualFeeMinor + row.actualPenaltyMinor <
                                        row.principalMinor + row.interestMinor + row.feeMinor
                                }
                                val overdue = enriched.creditAccounts.any { it.overdueMinor > 0L } ||
                                    enriched.snapshot.contracts.any { it.status == LoanStatus.DEFAULTED } || loanScheduleOverdue
                                enriched.copy(
                                    presentation = when {
                                        overdue -> LoanPresentation.OVERDUE
                                        !hasAny -> LoanPresentation.EMPTY
                                        else -> LoanPresentation.CONTENT
                                    },
                                )
                            }
                            val operationReady = if (screenId in setOf("REC-018", "REC-019")) {
                                created.copy(
                                    operationOccurredAt = runtimeSources.clock.now(),
                                    operationZoneId = ZoneId.of(savedSettings.zoneId.ifBlank { DEFAULT_ZONE }),
                                )
                            } else {
                                created
                            }
                            val payment = if (screenId == "LOA-009" && transactionId != null) {
                                loanApplicationPort.paymentDetail(bookId, transactionId)
                            } else {
                                null
                            }
                            val paymentFailure = when (payment) {
                                is DomainResult.Success -> if (payment.value == null) LOAN_NOT_FOUND else null
                                is DomainResult.Failure -> sanitizeCode(payment.error.code)
                                null -> null
                            }
                            if (paymentFailure != null) {
                                LoanLoadState.Failure(paymentFailure)
                            } else {
                                val withPayment = (payment as? DomainResult.Success)?.value
                                    ?.let { operationReady.copy(paymentDetail = it) }
                                    ?: operationReady
                                val retained = currentLoanSimulationRequest?.takeIf { request ->
                                    request.contractId == withPayment.selectedContractId && request.simulationId == simulationId
                                }
                                LoanLoadState.Content(
                                    if (screenId == "LOA-011" && retained != null) {
                                        withPayment.copy(simulation = currentLoanSimulation)
                                    } else {
                                        withPayment
                                    },
                                )
                            }
                        }
                    }
                }
                publishFeatureLoad(lease, reusable = loaded is LoanLoadState.Content) {
                    mutableLoan.value = loaded
                }
            } catch (_: Exception) {
                publishFeatureLoad(lease, reusable = false) {
                    mutableLoan.value = previous ?: LoanLoadState.Failure("LIABILITY_LOAD_FAILED")
                }
            }
        }
    }

    fun ensureLoanLoaded(
        screenId: String,
        contractId: StableId? = null,
        trancheId: StableId? = null,
        transactionId: StableId? = null,
        simulationId: StableId? = null,
    ) {
        val current = (mutableLoan.value as? LoanLoadState.Content)?.state
        val sameRoute = currentLoanScreenId == screenId &&
            (contractId == null || current?.selectedContractId == contractId) &&
            (trancheId == null || current?.selectedTrancheId == trancheId) &&
            (transactionId == null || current?.selectedTransactionId == transactionId) &&
            (simulationId == null || current?.selectedSimulationId == simulationId)
        if (current != null && sameRoute) return
        loadLoan(screenId, contractId, trancheId, transactionId, simulationId)
    }

    fun updateLoanField(field: LoanField, value: String) = updateLoan { state ->
        val updated = LoanPolicy.update(state, field, value)
        if (currentLoanScreenId == "LOA-002") LoanPolicy.syncWizardTranche(updated) else updated
    }

    fun selectLoanContract(contractId: StableId) = updateLoan { state ->
        LoanPolicy.create(state.snapshot, currentLoanScreenId, contractId, null, state.selectedTransactionId, state.selectedSimulationId).copy(
            operationOccurredAt = state.operationOccurredAt,
            operationZoneId = state.operationZoneId,
        )
    }

    fun selectLoanTranche(trancheId: StableId) = updateLoan { state ->
        LoanPolicy.create(state.snapshot, currentLoanScreenId, state.selectedContractId, trancheId, state.selectedTransactionId, state.selectedSimulationId).copy(
            operationOccurredAt = state.operationOccurredAt,
            operationZoneId = state.operationZoneId,
        )
    }

    fun selectLoanPaymentAccount(accountId: StableId) = updateLoan { state ->
        state.copy(selectedPaymentAccountId = accountId, presentation = LoanPresentation.EDITING)
    }

    fun selectLoanScheduleInstallment(installmentNumber: Int) = updateLoan { state ->
        state.copy(selectedScheduleInstallmentNumber = installmentNumber, presentation = LoanPresentation.EDITING)
    }

    fun selectLoanOperationOccurredAt(occurredAt: Instant) = updateLoan { state ->
        state.copy(operationOccurredAt = occurredAt, presentation = LoanPresentation.EDITING)
    }

    fun selectLoanRepaymentMethod(method: LoanRepaymentMethod) = updateLoan { state ->
        syncLoanWizard(state.copy(draft = state.draft.copy(repaymentMethod = method), presentation = LoanPresentation.EDITING, preview = emptyList()))
    }

    fun selectLoanStrategy(strategy: PrepaymentRecalculationStrategy) = updateLoan { state ->
        syncLoanWizard(state.copy(draft = state.draft.copy(strategy = strategy), presentation = LoanPresentation.EDITING, simulation = null))
    }

    fun selectLoanRateType(type: LoanRateType) = updateLoan { state ->
        syncLoanWizard(state.copy(draft = state.draft.copy(rateType = type), presentation = LoanPresentation.EDITING, preview = emptyList()))
    }

    fun selectLoanFrequency(frequency: PaymentFrequency) = updateLoan { state ->
        syncLoanWizard(state.copy(draft = state.draft.copy(paymentFrequency = frequency), presentation = LoanPresentation.EDITING, preview = emptyList()))
    }

    fun selectLoanPrepaymentPolicy(policy: LoanPrepaymentPolicy) = updateLoan { state ->
        syncLoanWizard(state.copy(draft = state.draft.copy(prepaymentPolicy = policy), presentation = LoanPresentation.EDITING, preview = emptyList()))
    }

    fun selectLoanRoundingMode(mode: RoundingMode) = updateLoan { state ->
        syncLoanWizard(state.copy(draft = state.draft.copy(roundingMode = mode), presentation = LoanPresentation.EDITING, preview = emptyList()))
    }

    fun nextLoanWizardStep() = updateLoan { state ->
        val synced = LoanPolicy.syncWizardTranche(state)
        val canAdvance = when (synced.wizardStep) {
            0 -> synced.draft.name.isNotBlank() && runCatching { LocalDate.parse(synced.draft.startDate) }.isSuccess
            1 -> synced.wizardTranches.isNotEmpty() && synced.wizardTranches.all { it.name.isNotBlank() && it.principal.isNotBlank() }
            2 -> synced.wizardTranches.all { it.paymentCount.toIntOrNull()?.let { count -> count > 0 } == true && it.firstPaymentDate.isNotBlank() }
            3 -> synced.wizardTranches.all { it.ratePeriods.isNotEmpty() || it.annualRate.isNotBlank() }
            4 -> synced.preview.isNotEmpty()
            else -> false
        }
        if (canAdvance) synced.copy(wizardStep = (synced.wizardStep + 1).coerceAtMost(5), presentation = LoanPresentation.EDITING) else synced.copy(presentation = LoanPresentation.INVALID)
    }

    fun previousLoanWizardStep() = updateLoan { state ->
        LoanPolicy.syncWizardTranche(state).copy(wizardStep = (state.wizardStep - 1).coerceAtLeast(0), presentation = LoanPresentation.EDITING)
    }

    fun addLoanWizardTranche() = updateLoan { state ->
        val synced = LoanPolicy.syncWizardTranche(state)
        val blank = app.ledger.feature.liabilities.LoanWizardTrancheDraft(
            "", "", "12", "", "", "", "", LoanRepaymentMethod.EQUAL_PAYMENT, LoanRateType.FIXED,
            PaymentFrequency.MONTHLY, LoanPrepaymentPolicy.ALLOWED, PrepaymentRecalculationStrategy.SHORTEN_TERM,
            "", RoundingMode.HALF_EVEN, emptyList(),
        )
        LoanPolicy.selectWizardTranche(synced.copy(wizardTranches = synced.wizardTranches + blank), synced.wizardTranches.size)
    }

    fun selectLoanWizardTranche(index: Int) = updateLoan { LoanPolicy.selectWizardTranche(it, index) }

    fun addLoanRatePeriod() = updateLoan { state ->
        val from = runCatching { LocalDate.parse(state.draft.startDate) }.getOrNull()
        val to = state.draft.endDate.takeIf(String::isNotBlank)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val rate = parseLoanRate(state.draft.annualRate)
        if (from == null || rate == null || (to != null && to < from)) return@updateLoan state.copy(presentation = LoanPresentation.INVALID)
        val candidate = LoanRatePeriod(from, to, rate, null, null)
        val edited = state.ratePeriods.toMutableList().apply {
            val index = state.editingRatePeriodIndex
            if (index != null && index in indices) set(index, candidate) else add(candidate)
        }
        val periods = edited.sortedBy { it.effectiveFrom }
        val overlap = periods.zipWithNext().any { (first, second) ->
            val effectiveTo = first.effectiveTo
            effectiveTo == null || effectiveTo >= second.effectiveFrom
        }
        val updated = state.copy(ratePeriods = periods, editingRatePeriodIndex = null, presentation = if (overlap) LoanPresentation.OVERLAP_ERROR else LoanPresentation.EDITING, preview = emptyList())
        syncLoanWizard(updated)
    }

    fun editLoanRatePeriod(index: Int) = updateLoan { state ->
        val period = state.ratePeriods.getOrNull(index) ?: return@updateLoan state
        state.copy(
            editingRatePeriodIndex = index,
            draft = state.draft.copy(
                startDate = period.effectiveFrom.toString(),
                endDate = period.effectiveTo?.toString().orEmpty(),
                annualRate = period.annualRate.annualDecimal.toPlainString(),
            ),
            presentation = LoanPresentation.EDITING,
        )
    }

    private fun syncLoanWizard(state: LoanFeatureState): LoanFeatureState = if (currentLoanScreenId == "LOA-002") LoanPolicy.syncWizardTranche(state) else state

    fun navigateLoan(target: String, primary: StableId?, secondary: StableId?) {
        liabilityCreditOnly = false
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
                "INS-003" -> primary?.let { put("planId", StableIdArgument(it)) }
            }
        }
        navigator.navigate(LedgerRouteContract.destination(screenId, arguments), SessionGateState.READY)
    }

    fun openCreditAccounts() {
        liabilityCreditOnly = true
        navigator.navigate(LedgerRouteContract.destination(ScreenId("LIA-001")), SessionGateState.READY)
    }

    fun previewLoan() {
        val rawState = (mutableLoan.value as? LoanLoadState.Content)?.state ?: return
        val state = if (currentLoanScreenId == "LOA-002") LoanPolicy.syncWizardTranche(rawState) else rawState
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
        val rawState = (mutableLoan.value as? LoanLoadState.Content)?.state ?: return
        val state = if (currentLoanScreenId == "LOA-002") LoanPolicy.syncWizardTranche(rawState) else rawState
        val request = loanContractRequest(state)
        if (request == null || !beginLoanMutation(state.copy(presentation = LoanPresentation.SAVING))) {
            if (request == null) markLoanInvalid(LoanPresentation.INVALID)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val result = loanApplicationPort.saveContract(request)) {
                    is DomainResult.Success -> {
                        withContext(Dispatchers.Main.immediate) {
                            navigator.pop()
                            navigateLoan("LOA-007", request.ids.contractId, null)
                        }
                        mutableGlobalSnackbarMessages.emit(GlobalSnackbarMessage.LOAN_UPDATED)
                    }
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
        val account = state.snapshot.paymentAccounts.singleOrNull {
            it.id == state.selectedPaymentAccountId && it.active && it.currency == contract.currency
        }
        if (amount == null || account == null || contract.currency != state.snapshot.baseCurrency) {
            markLoanInvalid(LoanPresentation.ALLOCATION_ERROR)
            return
        }
        val ids = loanExistingMutationIds(state, contract.id, tranche.id)
        val request = RecordLoanDisbursementRequest(
            ids,
            loanTransactionIds(),
            loanTransactionContext(state),
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
        val account = state.snapshot.paymentAccounts.singleOrNull {
            it.id == state.selectedPaymentAccountId && it.active && it.currency == contract.currency
        }
        if (account == null || contract.currency != state.snapshot.baseCurrency) return markLoanInvalid(LoanPresentation.INVALID)
        val mutation = loanContractRequest(state, selectedOnly = true, principalAfter = tranche.remainingPrincipalMinor - principal)
            ?: return markLoanInvalid(LoanPresentation.INVALID)
        val amounts = components.map { minor -> minor?.let { LoanComponentAmountDraft(it, it, null) } }
        val allocation = LoanPaymentComponent.entries.mapIndexedNotNull { index, component ->
            amounts[index]?.let {
                LoanComponentAllocationDraft(
                    tranche.id,
                    state.selectedScheduleInstallmentNumber,
                    component,
                    it.accountMinor,
                    it.baseMinor,
                )
            }
        }
        val request = RecordLoanPaymentRequest(
            mutation,
            loanTransactionIds(),
            loanTransactionContext(state),
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
            loanTransactionContext(state),
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
        val descriptors = if (views.isEmpty() && currentLoanScreenId == "LOA-002" && state.wizardTranches.isNotEmpty()) {
            state.wizardTranches.map { null to it }
        } else if (currentLoanScreenId == "LOA-003" && state.creatingTranche) {
            views.map { it to null } + (null to null)
        } else if (views.isEmpty()) {
            listOf(null to null)
        } else {
            views.map { it to null }
        }
        val trancheRequests = descriptors.map { (view, wizard) ->
            val selected = view == null || view.id == state.selectedTrancheId
            val currentPrincipal = when {
                selected && principalAfter != null -> principalAfter
                selected && currentLoanScreenId == "LOA-003" -> CreditPolicy.parseMinor(state.draft.principal, account.currency) ?: return null
                view != null -> view.remainingPrincipalMinor
                wizard != null -> CreditPolicy.parseMinor(wizard.principal, account.currency) ?: return null
                else -> CreditPolicy.parseMinor(state.draft.principal, account.currency) ?: return null
            }
            val requestedCount = if (currentPrincipal == 0L) {
                0
            } else if (selected) {
                (wizard?.paymentCount ?: state.draft.paymentCount).toIntOrNull()?.takeIf { it in 1..LOAN_MAX_PAYMENTS } ?: view?.schedule?.size ?: return null
            } else {
                view.schedule.size.coerceAtLeast(1)
            }
            val ids = if (selected && fixedIds != null) fixedIds else loanTrancheMutationIds(requestedCount)
            val terms = wizard?.let { loanTerms(state, it, requestedCount.coerceAtLeast(1), account.currency) }
                ?: loanTerms(state, view, requestedCount.coerceAtLeast(1)) ?: return null
            LoanTrancheDraft(
                ids,
                view?.ledgerAccountId ?: account.ledgerAccountId,
                wizard?.name?.takeIf(String::isNotBlank)
                    ?: if (selected && state.draft.trancheName.isNotBlank()) state.draft.trancheName else view?.name ?: state.draft.name.ifBlank { account.name },
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
        val periods = state.ratePeriods.ifEmpty {
            if (view != null && state.draft.annualRate.isBlank()) view.ratePeriods else listOf(LoanRatePeriod(start, end, rate, null, null))
        }
        val currency = state.contract?.currency ?: state.snapshot.loanAccounts.firstOrNull()?.currency ?: state.snapshot.baseCurrency
        val fee = state.draft.feePerPayment.takeIf(String::isNotBlank)?.let { CreditPolicy.parseMinor(it, currency) } ?: 0L
        val penalty = state.draft.penaltyRate.takeIf(String::isNotBlank)?.let(::parseLoanRate)
        if (state.draft.prepaymentPolicy == LoanPrepaymentPolicy.ALLOWED_WITH_PENALTY && penalty == null) return null
        return LoanTermsDraft(
            state.draft.repaymentMethod,
            state.draft.rateType,
            state.draft.paymentFrequency,
            start,
            end,
            paymentCount,
            first,
            state.draft.roundingMode,
            state.draft.prepaymentPolicy,
            state.draft.strategy,
            penalty,
            periods,
            fee,
        )
    }

    private fun loanTerms(
        state: LoanFeatureState,
        draft: app.ledger.feature.liabilities.LoanWizardTrancheDraft,
        paymentCount: Int,
        currency: CurrencyCode,
    ): LoanTermsDraft? {
        val start = runCatching { LocalDate.parse(state.draft.startDate) }.getOrNull() ?: loanToday()
        val first = runCatching { LocalDate.parse(draft.firstPaymentDate) }.getOrNull() ?: return null
        val end = draft.endDate.takeIf(String::isNotBlank)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: first.plusMonths((paymentCount - 1).toLong())
        val rate = parseLoanRate(draft.annualRate) ?: draft.ratePeriods.firstOrNull()?.annualRate ?: return null
        val periods = draft.ratePeriods.ifEmpty { listOf(LoanRatePeriod(start, end, rate, null, null)) }
        val fee = draft.feePerPayment.takeIf(String::isNotBlank)?.let { CreditPolicy.parseMinor(it, currency) } ?: 0L
        val penalty = draft.penaltyRate.takeIf(String::isNotBlank)?.let(::parseLoanRate)
        if (draft.prepaymentPolicy == LoanPrepaymentPolicy.ALLOWED_WITH_PENALTY && penalty == null) return null
        return LoanTermsDraft(
            draft.repaymentMethod, draft.rateType, draft.paymentFrequency, start, end, paymentCount, first,
            draft.roundingMode, draft.prepaymentPolicy, draft.prepaymentStrategy, penalty, periods, fee,
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

    private fun loanTransactionContext(state: LoanFeatureState): LoanTransactionContext {
        val zone = state.operationZoneId ?: ZoneId.of(settings.value.zoneId.ifBlank { DEFAULT_ZONE })
        val occurredAt = state.operationOccurredAt ?: runtimeSources.clock.now()
        return LoanTransactionContext(occurredAt, zone, occurredAt.atZone(zone).toLocalDate(), null, null)
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
        val ready = (mutableRootState.value as? AppRootState.Session)?.state as? BookSessionState.Ready ?: return
        currentSettlementScreenId = screenId
        val previous = mutableSettlement.value as? SettlementLoadState.Content
        val lease = beginFeatureLoad(
            FEATURE_LOAD_SETTLEMENT,
            AppFeatureLoadKey(
                ready.bookId,
                ready.generation,
                screenId,
                listOf(activityId?.toString(), participantId?.toString()),
            ),
            previous != null,
        ) {
            mutableSettlement.value = previous ?: SettlementLoadState.Failure("SETTLEMENT_LOAD_TIMEOUT")
        } ?: return
        if (previous == null) mutableSettlement.value = SettlementLoadState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            val bookId = runCatching { requireBookId(settingsRepository.current()) }.getOrNull()
            if (bookId == null) {
                publishFeatureLoad(lease, reusable = false) {
                    mutableSettlement.value = previous ?: SettlementLoadState.Failure("SETTLEMENT_BOOK_UNAVAILABLE")
                }
                return@launch
            }
            val loaded = when (val result = settlementApplicationPort.snapshot(bookId)) {
                is DomainResult.Failure -> previous ?: SettlementLoadState.Failure(sanitizeCode(result.error.code))
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
                            } else if (screenId == "SET-006" && created.draft.paymentDate.isBlank()) {
                                created.copy(draft = created.draft.copy(paymentDate = runtimeSources.clock.now().atZone(zone).toLocalDate().toString()))
                            } else {
                                created
                            },
                        )
                    }
                }
            }
            publishFeatureLoad(lease, reusable = loaded is SettlementLoadState.Content) {
                mutableSettlement.value = loaded
            }
        }
    }

    fun ensureSettlementLoaded(screenId: String, activityId: StableId? = null, participantId: StableId? = null) {
        val current = (mutableSettlement.value as? SettlementLoadState.Content)?.state
        val sameRoute = currentSettlementScreenId == screenId &&
            (activityId == null || current?.selectedActivityId == activityId) &&
            (participantId == null || current?.selectedParticipantId == participantId)
        if (current != null && sameRoute) return
        loadSettlement(screenId, activityId, participantId)
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
    fun selectSettlementCurrency(currency: CurrencyCode) = updateSettlement { state ->
        if (state.activity != null) state else state.copy(draft = state.draft.copy(currency = currency), presentation = SettlementPresentation.EDIT)
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

    fun setSettlementSelfParticipant(participantId: StableId) = updateSettlement { state ->
        require(state.draft.participants.any { it.id == participantId })
        state.copy(
            draft = state.draft.copy(
                participants = state.draft.participants.map { participant ->
                    participant.copy(
                        isSelf = participant.id == participantId,
                        included = if (participant.id == participantId) true else participant.included,
                    )
                },
            ),
            presentation = SettlementPresentation.EDIT,
            validationFields = state.validationFields - "participants",
        )
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
                    participants = state.draft.participants + SettlementParticipantDraft(
                        nextId(),
                        name,
                        isSelf = state.draft.participants.none { it.isSelf },
                    ),
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
            activity?.currency ?: requireNotNull(state.draft.currency), state.draft.projectId, start, end,
            activity?.status ?: SettlementActivityStatus.ACTIVE,
            included.map { SettlementParticipantWriteDraft(it.id, it.name, it.isSelf) }, runtimeSources.clock.now(),
        )
        if (!beginSettlementMutation(state.copy(presentation = SettlementPresentation.SAVING))) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val result = settlementApplicationPort.saveActivity(request)) {
                    is DomainResult.Success -> {
                        withContext(Dispatchers.Main.immediate) {
                            navigator.pop()
                            navigateSettlement("SET-004", activityId)
                        }
                        loadSettlement("SET-004", activityId)
                        mutableGlobalSnackbarMessages.emit(GlobalSnackbarMessage.SETTLEMENT_UPDATED)
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
        val localDate = runCatching { LocalDate.parse(state.draft.paymentDate) }.getOrNull() ?: return markSettlementInvalid()
        val occurredAt = localDate.atTime(now.atZone(zone).toLocalTime()).atZone(zone).toInstant()
        val request = RecordSettlementPaymentRequest(
            SettlementPaymentIds(
                state.snapshot.bookId, CommandId(nextId()), nextId(), nextId(), nextId(), nextId(), nextId(),
                List(FINANCIAL_FACT_ID_RESERVE) { nextId() }, emptyList(),
            ),
            activity.id, payer, payee, amount, account?.id,
            account?.let { amount }, account?.let { amount }, null, null,
            occurredAt, zone, localDate, state.draft.note.ifBlank { null }, now,
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
        if (mutableRecoveryRestoreActive.value) {
            mutableRecoveryRestoreActive.value = false
            return
        }
        val screen = navigator.currentKey.contract.screenId.value
        val routeScope = currentRouteScopeKey()
        if (routeScope in dirtyRouteScopes && screen != "REC-003" && currentRouteHasActualChanges(screen)) {
            pendingGeneralExit = PendingGeneralExit.Back(routeScope)
            mutableGeneralUnsavedPrompt.value = true
            return
        }
        if (screen.startsWith("VLT-")) vaultController.hideSensitive(autoHidden = false)
        if (screen == "ATT-001") attachmentController.close()
        val editor = (mutableOrdinaryRecord.value as? OrdinaryRecordLoadState.Content)?.editor
        if (screen.startsWith("IMP-")) {
            if (screen in setOf("IMP-002", "IMP-003", "IMP-004", "IMP-005", "IMP-006", "IMP-007")) {
                previousImportStage()
            } else if (importController.canAbandon()) {
                viewModelScope.launch(Dispatchers.IO) {
                    if (importController.abandon()) {
                        withContext(Dispatchers.Main.immediate) { navigator.pop() }
                    }
                }
            }
        } else if (screen in setOf("REC-023", "REC-024", "REC-025") && batchRecord.value?.presentation != app.ledger.feature.record.BatchRecordPresentation.COMMITTED) {
            batchEntryController.requestDiscardConfirmation()
        } else if (screen == "REC-003" && editor?.draft?.dirty == true) {
            pendingRecordExit = PendingRecordExit.Back
            updateEditor { it.copy(showUnsavedDialog = true) }
        } else {
            if (screen == "JRN-002") updateJournalSearch("")
            if (screen == "JRN-011") {
                applyJournalFilter(TransactionFilter(lifecycleStates = setOf(TransactionLifecycleState.ACTIVE)))
            }
            if (screen == "REC-003") stopRecordLocationAcquisition()
            navigator.pop()
        }
    }

    fun selectRootTopLevel(target: TopLevelDestination) {
        val screen = navigator.currentKey.contract.screenId.value
        val routeScope = currentRouteScopeKey()
        if (routeScope in dirtyRouteScopes && screen != "REC-003" && currentRouteHasActualChanges(screen)) {
            pendingGeneralExit = PendingGeneralExit.TopLevel(routeScope, target)
            mutableGeneralUnsavedPrompt.value = true
            return
        }
        if (screen.startsWith("VLT-")) vaultController.hideSensitive(autoHidden = false)
        if (screen.startsWith("ATT-")) attachmentController.close()
        val editor = (mutableOrdinaryRecord.value as? OrdinaryRecordLoadState.Content)?.editor
        if (screen in setOf("REC-023", "REC-024", "REC-025") && batchRecord.value?.presentation != app.ledger.feature.record.BatchRecordPresentation.COMMITTED) {
            batchEntryController.requestDiscardConfirmation()
        } else if (screen == "REC-003" && editor?.draft?.dirty == true) {
            pendingRecordExit = PendingRecordExit.TopLevel(target)
            updateEditor { it.copy(showUnsavedDialog = true) }
        } else {
            if (editor != null) stopRecordLocationAcquisition()
            if (target == TopLevelDestination.JOURNAL && navigator.currentTopLevel != target) {
                presentedJournalPagingRequest = null
                LedgerSessionPerformance.beginBlockingProgress()
            } else {
                LedgerSessionPerformance.completeBlockingProgress()
            }
            val outcome = navigator.select(target)
            loadTopLevelMinimum(target)
            val ready = (mutableRootState.value as? AppRootState.Session)?.state as? BookSessionState.Ready
            if (ready != null && outcome != NavigationOutcome.ScrollRootToTop) {
                trackTopLevelContent(ready.bookId, ready.generation, target, reportFullyDrawn = false)
                schedulePostFirstContentWork(ready.bookId, ready.generation, target)
            }
        }
    }

    override fun onCleared() {
        financialCommitRegistration.close()
        attachmentController.close()
        revisionCacheControl.clearAll()
        applicationUnlockGeneration++
        applicationUnlockJob?.cancel()
        cancelUnlockToContentTrace()
        applicationLockJob?.cancel()
        postFirstContentJob?.cancel()
        cancelRouteInteractionTrace()
        cancelJournalSearchTrace()
        LedgerSessionPerformance.completeBlockingProgress()
        journalBulkOptionsJob?.cancel()
        referenceRouteLoader.cancel()
        journalSearchLoader.reset()
        refundSearchLoader.reset()
        featureLoadRegistry.cancelAll()
        synchronized(featureLoadKeyLock) { completedFeatureLoadKeys.clear() }
        currentOrdinaryLoadJob?.cancel()
        currentBudgetLoadJob?.cancel()
        stopRecordLocationAcquisition()
        super.onCleared()
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
            is PendingRecordExit.TopLevel -> {
                navigator.select(pending.target)
                loadTopLevelMinimum(pending.target)
            }
            null -> Unit
        }
        pendingRecordExit = null
        stopRecordLocationAcquisition()
    }

    fun keepEditingRecord() {
        pendingRecordExit = null
        updateEditor { it.copy(showUnsavedDialog = false) }
    }

    fun markCurrentFormDirty() {
        dirtyRouteScopes += currentRouteScopeKey()
    }

    fun commitCurrentFormChanges() {
        val scope = currentRouteScopeKey()
        dirtyRouteScopes -= scope
        synchronized(retainedFormValues) { retainedFormValues.keys.removeAll { (entryScope, _) -> entryScope == scope } }
    }

    private fun currentRouteHasActualChanges(screenId: String): Boolean = when (screenId) {
        "CRD-002", "REC-014" -> {
            val current = (mutableCredit.value as? CreditLoadState.Content)?.state?.draft
            val stagedCreation = screenId == "CRD-002" &&
                pendingCreditAccountDraft?.accountId == (mutableCredit.value as? CreditLoadState.Content)?.state?.selectedAccountId
            stagedCreation || current != null && current != creditDraftBaseline
        }
        "ANA-001", "ANA-002", "ANA-003", "ANA-005", "ANA-006", "ANA-009", "ANA-010", "ANA-011", "ANA-012", "ANA-014", "ANA-015" -> false
        else -> true
    }

    fun currentRouteScopeKey(): String = navigator.currentKey.retainedScopeKey()

    fun activeRouteScopeKeys(): Set<String> = navigator.currentBackStack.mapTo(linkedSetOf()) { it.retainedScopeKey() }

    fun syncRetainedFormScopes(activeScopes: Set<String>) {
        dirtyRouteScopes.retainAll(activeScopes)
        synchronized(retainedFormValues) {
            retainedFormValues.keys.removeAll { (scope, _) -> scope !in activeScopes }
        }
    }

    fun keepEditingGeneralForm() {
        pendingGeneralExit = null
        mutableGeneralUnsavedPrompt.value = false
    }

    fun discardGeneralFormChanges() {
        val pending = pendingGeneralExit ?: return
        val scope = pending.scope
        if (navigator.currentKey.contract.screenId.value == "CRD-002") {
            val selectedAccountId = (mutableCredit.value as? CreditLoadState.Content)?.state?.selectedAccountId
            if (pendingCreditAccountDraft?.accountId == selectedAccountId) pendingCreditAccountDraft = null
        }
        dirtyRouteScopes -= scope
        synchronized(retainedFormValues) { retainedFormValues.keys.removeAll { (entryScope, _) -> entryScope == scope } }
        pendingGeneralExit = null
        mutableGeneralUnsavedPrompt.value = false
        when (pending) {
            is PendingGeneralExit.Back -> navigator.pop()
            is PendingGeneralExit.TopLevel -> {
                navigator.select(pending.target)
                loadTopLevelMinimum(pending.target)
            }
        }
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
            replacementCardId = null
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

    fun openNewAccountEditor(type: UserAccountType) {
        selectedAccountType = type
        navigator.navigate(LedgerRouteContract.destination(ScreenId("ACC-003")), SessionGateState.READY)
    }

    fun createReplacementCard(cardId: StableId, accountId: StableId, source: LedgerDestinationKey) {
        replacementCardId = cardId
        pendingCardAccountId = accountId
        navigateP12(source, "ACC-010", emptyMap())
    }

    fun saveAccount(value: AccountEditorSubmission) {
        if (value.accountId == null && value.type == UserAccountType.CREDIT) {
            stageCreditAccountCreation(value)
            return
        }
        var savedAccountId = value.accountId
        executeReferenceMutation(factory = { snapshot ->
            val existing = value.accountId?.let { id -> snapshot.accounts.singleOrNull { it.id == id } }
            val currency = CurrencyCode.parse(value.currencyCode).getOrNull() ?: error("REFERENCE_INVALID_CURRENCY")
            val accountId = existing?.id ?: nextId()
            savedAccountId = accountId
            ReferenceMutation.SaveAccount(
                AccountDraft(
                    accountId = accountId,
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
        }, onSuccess = {
            if (value.accountId == null) {
                when (value.type) {
                    UserAccountType.CREDIT -> savedAccountId?.let { id ->
                        loadCredit("CRD-002", accountId = id)
                        navigateCredit("CRD-002", id)
                    }
                    UserAccountType.LOAN -> {
                        loadLoan("LOA-002")
                        navigateLoan("LOA-002", null, null)
                    }
                    else -> savedAccountId?.let { id ->
                        popToReferenceScreen("ACC-001")
                        navigateP12(navigator.currentKey, "ACC-005", mapOf("accountId" to id))
                    }
                }
            } else {
                navigator.pop()
            }
        })
    }

    private fun stageCreditAccountCreation(value: AccountEditorSubmission) {
        if (mutableReferenceMutationPending.value) return
        viewModelScope.launch(Dispatchers.IO) {
            val reference = (mutableReferenceData.value as? AppReferenceDataState.Content)?.snapshot ?: return@launch
            val currency = CurrencyCode.parse(value.currencyCode).getOrNull() ?: return@launch
            mutableReferenceMutationPending.value = true
            try {
                val accountId = nextId()
                val accountDraft = AccountDraft(
                    accountId = accountId,
                    ledgerAccountId = nextId(),
                    expectedRowVersion = null,
                    type = UserAccountType.CREDIT,
                    name = value.name,
                    currency = currency,
                    institutionName = value.institutionName,
                    branchName = value.branchName,
                    accountNumber = value.accountNumber,
                    openedOn = value.openedOn,
                    iconKey = value.iconKey,
                    colorArgb = value.colorArgb,
                    sortOrder = reference.accounts.size,
                )
                when (val snapshot = creditApplicationPort.snapshot(reference.bookId)) {
                    is DomainResult.Failure -> mutableGlobalSnackbarMessages.emit(GlobalSnackbarMessage.REFERENCE_MUTATION_FAILED)
                    is DomainResult.Success -> {
                        pendingCreditAccountDraft = accountDraft
                        val stagedAccount = app.ledger.finance.application.CreditAccountView(
                            id = accountId,
                            name = value.name.trim(),
                            currency = currency,
                            archived = false,
                            profile = null,
                            signedLiabilityMinor = 0L,
                            debtMinor = 0L,
                            positiveBalanceMinor = 0L,
                            availableLimitMinor = null,
                            unbilledMinor = 0L,
                            overdueMinor = 0L,
                            statements = emptyList(),
                        )
                        val stagedSnapshot = snapshot.value.copy(accounts = snapshot.value.accounts + stagedAccount)
                        val stagedState = CreditPolicy.create(stagedSnapshot, "CRD-002", accountId, null)
                        currentCreditScreenId = "CRD-002"
                        currentCreditTransactionId = null
                        creditDraftBaseline = stagedState.draft
                        mutableCredit.value = CreditLoadState.Content(stagedState)
                        withContext(Dispatchers.Main.immediate) { navigateCredit("CRD-002", accountId) }
                    }
                }
            } finally {
                mutableReferenceMutationPending.value = false
            }
        }
    }

    fun archiveAccount(id: StableId, rowVersion: Long) = executeReferenceMutation(
        successMessage = GlobalSnackbarMessage.REFERENCE_ARCHIVED,
        onSuccess = { popToReferenceScreen("ACC-001") },
    ) { ReferenceMutation.ArchiveAccount(id, rowVersion) }
    fun deleteEmptyAccount(id: StableId, rowVersion: Long) = executeReferenceMutation(
        successMessage = GlobalSnackbarMessage.REFERENCE_DELETED,
        onSuccess = { popToReferenceScreen("ACC-001") },
    ) { ReferenceMutation.DeleteEmptyAccount(id, rowVersion) }

    fun saveCard(value: CardEditorSubmission) = executeReferenceMutation(factory = { snapshot ->
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
    }, onSuccess = {
        replacementCardId = null
        pendingCardAccountId = null
        navigator.pop()
    })

    fun archiveCard(id: StableId, rowVersion: Long) = executeReferenceMutation(
        successMessage = GlobalSnackbarMessage.REFERENCE_ARCHIVED,
        onSuccess = { navigator.pop() },
    ) { ReferenceMutation.ArchiveCard(id, rowVersion) }

    fun saveCheckpoint(value: CheckpointSubmission) {
        val checkpointId = nextId()
        executeReferenceMutation(
            onSuccess = {
                popToReferenceScreen("ACC-005")
            },
        ) {
            val zone = ZoneId.of(settings.value.zoneId.ifBlank { DEFAULT_ZONE })
            ReferenceMutation.SaveCheckpoint(
                checkpointId = checkpointId,
                accountId = value.accountId,
                asOf = value.localDate.plusDays(1).atStartOfDay(zone).minusNanos(1).toInstant(),
                asOfLocalDate = value.localDate,
                observedMinor = value.observedMinor,
                note = value.note,
            )
        }
    }

    fun saveOpeningBalance(value: OpeningBalanceSubmission) {
        executeOpeningBalance(value)
    }

    fun saveCategory(value: CategorySubmission) = executeReferenceMutation(onSuccess = { navigator.pop() }) { snapshot ->
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

    fun reorderCategories(direction: CategoryDirection, ids: List<StableId>) = executeReferenceMutation(onSuccess = { navigator.pop() }) {
        ReferenceMutation.ReorderCategories(direction, ids)
    }
    fun removeCategory(id: StableId, rowVersion: Long, strategy: CategoryRemovalStrategy, target: StableId?) = executeReferenceMutation(
        onSuccess = { popToReferenceScreen("CAT-001") },
    ) { ReferenceMutation.RemoveCategory(id, rowVersion, strategy, target) }

    fun saveMerchant(value: MerchantSubmission) = executeReferenceMutation(onSuccess = { navigator.pop() }) { snapshot ->
        val existing = value.merchantId?.let { id -> snapshot.merchants.singleOrNull { it.id == id } }
        ReferenceMutation.SaveMerchant(MerchantDraft(existing?.id ?: nextId(), existing?.rowVersion, value.name, value.name.trim().lowercase(Locale.ROOT), value.aliases))
    }

    fun mergeMerchant(source: StableId, target: StableId) = executeReferenceMutation(onSuccess = { navigator.pop() }) {
        ReferenceMutation.MergeMerchant(source, target)
    }

    fun savePlace(value: PlaceSubmission) = executeReferenceMutation(onSuccess = { navigator.pop() }) { snapshot ->
        val existing = value.placeId?.let { id -> snapshot.places.singleOrNull { it.id == id } }
        ReferenceMutation.SavePlace(PlaceDraft(existing?.id ?: nextId(), existing?.rowVersion, value.name, value.latitudeE7, value.longitudeE7, value.merchantId))
    }

    fun mergePlace(source: StableId, target: StableId) = executeReferenceMutation(onSuccess = { navigator.pop() }) {
        ReferenceMutation.MergePlace(source, target)
    }

    fun splitPlace(source: StableId, value: PlaceSubmission, locations: List<StableId>) = executeReferenceMutation(onSuccess = { navigator.pop() }) {
        ReferenceMutation.SplitPlace(
            source,
            PlaceDraft(nextId(), null, value.name, value.latitudeE7, value.longitudeE7, value.merchantId),
            locations,
            List(locations.size) { nextId() },
        )
    }

    private fun executeReferenceMutation(
        successMessage: GlobalSnackbarMessage = GlobalSnackbarMessage.REFERENCE_SAVED,
        onSuccess: () -> Unit = {},
        factory: (ReferenceDataSnapshot) -> ReferenceMutation,
    ) {
        if (mutableReferenceMutationPending.value) return
        viewModelScope.launch(Dispatchers.IO) {
            val cached = (mutableReferenceData.value as? AppReferenceDataState.Content)?.snapshot ?: return@launch
            mutableReferenceMutationPending.value = true
            try {
                // Financial writes advance the same book revision used by reference mutations.
                // Refresh the authoritative header/balances while retaining only the bounded
                // reference family loaded for this route.
                val snapshot = when (val latest = referenceDataPort.accountSummary(cached.bookId)) {
                    is DomainResult.Success -> latest.value.toReferenceSnapshot(cached)
                    is DomainResult.Failure -> {
                        mutableGlobalSnackbarMessages.emit(GlobalSnackbarMessage.REFERENCE_MUTATION_FAILED)
                        return@launch
                    }
                }
                mutableReferenceData.value = AppReferenceDataState.Content(snapshot)
                val ids = ReferenceMutationIds(
                    bookId = snapshot.bookId,
                    expectedLocalRevision = snapshot.localRevision,
                    commitId = nextId(),
                    entityRevisionIds = List(REFERENCE_REVISION_ID_RESERVE) { nextId() },
                    deviceInstanceId = nextId(),
                    changedAt = runtimeSources.clock.now(),
                )
                when (val result = runCatching { referenceDataPort.mutate(ReferenceMutationCommand(ids, factory(snapshot))) }.getOrNull()) {
                    is DomainResult.Success -> {
                        if (loadReferenceDataAfterMutation(snapshot.bookId)) {
                            withContext(Dispatchers.Main.immediate) { onSuccess() }
                            mutableGlobalSnackbarMessages.emit(successMessage)
                        } else {
                            mutableGlobalSnackbarMessages.emit(GlobalSnackbarMessage.REFERENCE_MUTATION_FAILED)
                        }
                    }
                    is DomainResult.Failure, null -> mutableGlobalSnackbarMessages.emit(GlobalSnackbarMessage.REFERENCE_MUTATION_FAILED)
                }
            } finally {
                mutableReferenceMutationPending.value = false
            }
        }
    }

    private suspend fun loadReferenceDataAfterMutation(bookId: StableId): Boolean = when (val result = referenceDataPort.accountSummary(bookId)) {
        is DomainResult.Success -> {
            val previous = (mutableReferenceData.value as? AppReferenceDataState.Content)?.snapshot
            val summarySnapshot = result.value.toReferenceSnapshot(previous)
            val screenId = navigator.currentKey.contract.screenId.value
            val snapshot = if (screenId.requiresReferenceRouteData()) {
                val arguments = navigator.currentKey.encodedArguments
                loadReferenceRouteSnapshot(
                    bookId,
                    screenId,
                    arguments["accountId"]?.let { StableId.parse(it).getOrNull() },
                    arguments["direction"]?.let { runCatching { CategoryDirection.valueOf(it) }.getOrNull() },
                    summarySnapshot,
                ) ?: return false
            } else {
                summarySnapshot
            }
            updateCurrencySettings(snapshot)
            mutableReferenceData.value = AppReferenceDataState.Content(snapshot)
            withContext(Dispatchers.Main.immediate) {
                vaultController.synchronizeCards(bookId, snapshot.cards)
            }
            updateRecordContent {
                val refreshedReferences = this.snapshot.references.copy(
                    localRevision = snapshot.localRevision,
                    valuationRevision = snapshot.valuationRevision,
                    accounts = snapshot.accounts,
                    cards = snapshot.cards,
                    coreNetFinancialAssetsMinor = snapshot.coreNetFinancialAssetsMinor,
                    adjustedNetFinancialPositionMinor = snapshot.adjustedNetFinancialPositionMinor,
                    valuationMissing = snapshot.valuationMissing,
                )
                val refreshed = this.snapshot.copy(references = refreshedReferences)
                val currentEditor = editor
                copy(
                    snapshot = refreshed,
                    editor = currentEditor?.copy(
                        snapshot = currentEditor.snapshot.copy(references = refreshedReferences),
                    ),
                )
            }
            true
        }
        is DomainResult.Failure -> false
    }

    private fun popToReferenceScreen(screenId: String) {
        while (navigator.currentKey.contract.screenId.value != screenId && navigator.currentBackStack.size > 1) {
            navigator.pop()
        }
        if (navigator.currentKey.contract.screenId.value != screenId) {
            navigator.navigate(LedgerRouteContract.destination(ScreenId(screenId)), SessionGateState.READY)
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
                mutableGlobalSnackbarMessages.emit(GlobalSnackbarMessage.REFERENCE_MUTATION_FAILED)
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
                    is DomainResult.Success -> {
                        if (loadReferenceDataAfterMutation(snapshot.bookId)) {
                            navigator.pop()
                            mutableGlobalSnackbarMessages.emit(GlobalSnackbarMessage.REFERENCE_SAVED)
                        } else {
                            mutableGlobalSnackbarMessages.emit(GlobalSnackbarMessage.REFERENCE_MUTATION_FAILED)
                        }
                    }
                    is DomainResult.Failure -> mutableGlobalSnackbarMessages.emit(GlobalSnackbarMessage.REFERENCE_MUTATION_FAILED)
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
        pendingWidgetQuickDeepLink?.let { quick ->
            pendingWidgetQuickDeepLink = null
            openWidgetQuickForm(quick)
            return
        }
        val destination = pendingDeepLink ?: return
        if (navigator.navigate(destination, SessionGateState.READY) == app.ledger.core.navigation.NavigationOutcome.Navigated) {
            pendingDeepLink = null
        }
    }

    /** Reads a fresh snapshot and opens a prefilled complete form; this path never submits a mutation. */
    private fun openWidgetQuickForm(link: WidgetQuickDeepLink) {
        viewModelScope.launch(Dispatchers.IO) {
            val saved = settingsRepository.current()
            val bookId = runCatching { requireBookId(saved) }.getOrNull() ?: return@launch
            val snapshot = when (val result = ordinaryTransactionEntryPort.snapshot(bookId)) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> {
                    mutableOrdinaryRecord.value = OrdinaryRecordLoadState.Failure(sanitizeCode(result.error.code))
                    return@launch
                }
            }
            val requestedDirection = if (link.direction == WidgetQuickDirection.INCOME) {
                OrdinaryDirection.INCOME
            } else {
                OrdinaryDirection.EXPENSE
            }
            val seed = when (link.kind) {
                WidgetQuickTargetKind.CATEGORY -> {
                    val category = snapshot.references.categories.singleOrNull { it.id == link.targetId }
                        ?: return@launch
                    val compatible = if (category.direction == CategoryDirection.INCOME) OrdinaryDirection.INCOME else OrdinaryDirection.EXPENSE
                    require(compatible == requestedDirection)
                    WidgetFormSeed(RecordEditorMode.CREATE, compatible, category.id, null)
                }
                WidgetQuickTargetKind.TEMPLATE -> {
                    val template = snapshot.templates.singleOrNull { it.id == link.targetId } ?: return@launch
                    require(template.direction == requestedDirection)
                    WidgetFormSeed(RecordEditorMode.TEMPLATE, template.direction, template.categoryId, template.id)
                }
            }
            val zone = runCatching { ZoneId.of(saved.zoneId.ifBlank { DEFAULT_ZONE }) }.getOrDefault(ZoneId.of(DEFAULT_ZONE))
            val editor = hydrateRecordEditor(
                OrdinaryRecordPolicy.createEditor(
                    snapshot,
                    seed.mode,
                    seed.direction,
                    seed.categoryId,
                    seed.sourceId,
                    runtimeSources.clock.now(),
                    zone,
                    recordLocale(),
                ),
            )
            mutableOrdinaryRecord.value = OrdinaryRecordLoadState.Content(
                snapshot,
                if (seed.direction == OrdinaryDirection.EXPENSE) RecordTab.EXPENSE else RecordTab.INCOME,
                selectedCategoryId = editor.draft.categoryId,
                editor = editor,
            )
            startRecordLocationAcquisition(restart = true)
            val editorScreen = ScreenId("REC-003")
            val arguments = buildMap<String, SafeRouteArgument> {
                put("mode", LedgerRouteContract.enumArgument(editorScreen, "mode", seed.mode.name))
                seed.sourceId?.let { put("transactionId", StableIdArgument(it)) }
            }
            navigator.navigate(LedgerRouteContract.destination(editorScreen, arguments), SessionGateState.READY)
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
        else -> runCatching { AppLockTimeout.Custom.of(value) }.getOrDefault(AppLockTimeout.Immediately)
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
        val ready = (mutableRootState.value as? AppRootState.Session)?.state as? BookSessionState.Ready ?: return
        val previousContent = mutableAutomation.value as? AutomationLoadState.Content
        val previousAutomation = (mutableAutomation.value as? AutomationLoadState.Content)?.state
        automationBlueprintDraftCache = if (screenId == "AUT-003" && previousAutomation?.screenId == "AUT-003") {
            previousAutomation.blueprintDraft ?: automationBlueprintDraftCache
        } else if (screenId != "AUT-003") {
            null
        } else {
            automationBlueprintDraftCache
        }
        val recurrenceEditorScreens = setOf("AUT-005", "AUT-006")
        val recurrenceFlowScreens = recurrenceEditorScreens + "AUT-007"
        if (screenId in recurrenceFlowScreens) {
            val previous = (mutableAutomation.value as? AutomationLoadState.Content)?.state
            automationRecurrenceDraftCache = if (previous?.screenId?.let(recurrenceFlowScreens::contains) == true) {
                previous.recurrenceDraft ?: automationRecurrenceDraftCache
            } else {
                null
            }
        } else {
            automationRecurrenceDraftCache = null
        }
        currentAutomationScreenId = screenId
        val lease = beginFeatureLoad(
            FEATURE_LOAD_AUTOMATION,
            AppFeatureLoadKey(
                ready.bookId,
                ready.generation,
                screenId,
                listOf(blueprintId?.toString(), seriesId?.toString(), candidateId?.toString()),
            ),
            previousContent != null,
        ) {
            mutableAutomation.value = previousContent ?: AutomationLoadState.Failure("AUTOMATION_LOAD_TIMEOUT")
        } ?: return
        if (previousContent == null) mutableAutomation.value = AutomationLoadState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            val saved = settingsRepository.current()
            val bookId = runCatching { requireBookId(saved) }.getOrNull()
            if (bookId == null) {
                publishFeatureLoad(lease, reusable = false) {
                    mutableAutomation.value = previousContent ?: AutomationLoadState.Failure("AUTOMATION_BOOK_UNAVAILABLE")
                }
                return@launch
            }
            val zone = ZoneId.of(saved.zoneId.ifBlank { DEFAULT_ZONE })
            val today = runtimeSources.clock.now().atZone(zone).toLocalDate()
            val hub = activeBookSessionRuntime.withCurrentDatabase(bookId, LedgerAccessMode.READ) {
                AutomationHubQueryResults(
                    automationApplicationPort.snapshot(bookId),
                    ordinaryTransactionEntryPort.snapshot(bookId),
                )
            }
            val automationResult = hub.automation
            val entryResult = hub.entry
            val loaded = if (automationResult is DomainResult.Success && entryResult is DomainResult.Success) {
                val created = AutomationPolicy.create(
                    automationResult.value,
                    entryResult.value,
                    screenId,
                    blueprintId,
                    seriesId,
                    candidateId,
                    zone,
                    today,
                )
                val retainedRule = pendingAutomationRuleDraft?.takeIf { draft ->
                    screenId == "AUT-006" || screenId == "AUT-005" && draft.id == seriesId
                }
                val retainedRecurrence = retainedRule ?: automationRecurrenceDraftCache?.takeIf { screenId in recurrenceFlowScreens }
                val retainedBlueprint = automationBlueprintDraftCache?.takeIf { draft ->
                    screenId == "AUT-003" && (blueprintId == null || draft.id == blueprintId)
                }
                AutomationLoadState.Content(
                    created.copy(
                        blueprintDraft = retainedBlueprint ?: created.blueprintDraft,
                        recurrenceDraft = retainedRecurrence ?: created.recurrenceDraft,
                        presentation = if (retainedBlueprint != null || retainedRecurrence != null) AutomationPresentation.EDITING else created.presentation,
                    ),
                )
            } else {
                val code = (automationResult as? DomainResult.Failure)?.error?.code
                    ?: (entryResult as? DomainResult.Failure)?.error?.code
                    ?: "AUTOMATION_LOAD_FAILED"
                previousContent ?: AutomationLoadState.Failure(sanitizeCode(code))
            }
            publishFeatureLoad(lease, reusable = loaded is AutomationLoadState.Content) {
                mutableAutomation.value = loaded
            }
        }
    }

    fun navigateAutomation(target: String, stableId: StableId?) {
        if (target == "AUT-006") {
            pendingAutomationRuleDraft = (mutableAutomation.value as? AutomationLoadState.Content)?.state?.recurrenceDraft
        }
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

    fun openAutomationCategoryCreator() {
        val current = (mutableAutomation.value as? AutomationLoadState.Content)?.state
        automationBlueprintDraftCache = current?.blueprintDraft ?: automationBlueprintDraftCache
        val screenId = ScreenId("CAT-002")
        navigator.navigate(
            LedgerRouteContract.destination(
                screenId,
                mapOf("direction" to LedgerRouteContract.enumArgument(screenId, "direction", CategoryDirection.EXPENSE.name)),
            ),
            SessionGateState.READY,
        )
    }

    fun updateAutomationSearch(value: String) = updateAutomationContent { copy(search = value.take(80)) }
    fun updateAutomationTemplateFilter(value: AutomationTemplateFilter) = updateAutomationContent { copy(templateFilter = value) }
    fun updateAutomationTemplateSort(value: AutomationTemplateSort) = updateAutomationContent { copy(templateSort = value) }
    fun updateAutomationSeriesFilter(value: AutomationSeriesFilter) = updateAutomationContent { copy(seriesFilter = value) }

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

    fun archiveAutomationBlueprint(id: StableId) {
        if (mutableAutomationPending.value) return
        val content = mutableAutomation.value as? AutomationLoadState.Content ?: return
        val blueprint = content.state.snapshot.blueprints.singleOrNull { it.id == id } ?: return
        val snapshot = content.state.snapshot
        mutableAutomationPending.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = automationApplicationPort.saveBlueprint(
                    SaveBlueprintRequest(
                        AutomationMutationIds(snapshot.bookId, CommandId(nextId()), nextId(), nextId(), nextId(), snapshot.localRevision, runtimeSources.clock.now()),
                        BlueprintDraft(
                            blueprint.id, nextId(), blueprint.revisionId, blueprint.name, blueprint.iconKey, blueprint.colorArgb,
                            app.ledger.finance.domain.EntityStatus.ARCHIVED, blueprint.targetKind, blueprint.categoryId,
                            blueprint.primaryAccountId, blueprint.secondaryAccountId, blueprint.cardId, blueprint.merchantId,
                            blueprint.projectId, blueprint.goalId, blueprint.settlementActivityId, blueprint.amountExpression,
                            blueprint.currency, blueprint.noteTemplate, blueprint.fixedPlaceId,
                        ),
                    ),
                )
                when (result) {
                    is DomainResult.Success -> loadAutomation("AUT-002")
                    is DomainResult.Failure -> mutableAutomation.value = AutomationLoadState.Content(content.state.copy(presentation = AutomationPresentation.VALIDATION_ERROR, failureCode = sanitizeCode(result.error.code)))
                }
            } finally {
                mutableAutomationPending.value = false
            }
        }
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
                    AutomationMutationIds(snapshot.bookId, CommandId(nextId()), nextId(), nextId(), nextId(), snapshot.localRevision, runtimeSources.clock.now()),
                    BlueprintDraft(
                        id = draft.id ?: nextId(),
                        revisionId = nextId(),
                        expectedRevisionId = current?.revisionId,
                        name = draft.name.trim(),
                        iconKey = draft.iconKey,
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
                        automationBlueprintDraftCache = null
                        withContext(Dispatchers.Main.immediate) { navigator.pop() }
                        loadAutomation("AUT-002")
                        mutableGlobalSnackbarMessages.emit(GlobalSnackbarMessage.AUTOMATION_UPDATED)
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

    fun updateAutomationNthWeekday(day: DayOfWeek) = updateAutomationContent {
        val draft = requireNotNull(recurrenceDraft)
        copy(recurrenceDraft = draft.copy(rule = draft.rule.copy(weekday = day)), presentation = AutomationPresentation.EDITING)
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

    fun updateAutomationFixedPlace(id: StableId?) = updateAutomationContent {
        val draft = requireNotNull(recurrenceDraft)
        copy(recurrenceDraft = draft.copy(fixedPlaceId = id), presentation = AutomationPresentation.EDITING)
    }

    fun applyAutomationRule() {
        val content = mutableAutomation.value as? AutomationLoadState.Content ?: return
        val validated = AutomationPolicy.validateRecurrence(content.state)
        if (validated.validationFields.isNotEmpty()) {
            mutableAutomation.value = AutomationLoadState.Content(validated)
            return
        }
        pendingAutomationRuleDraft = validated.recurrenceDraft
        navigator.pop()
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
                        AutomationMutationIds(snapshot.bookId, CommandId(nextId()), nextId(), nextId(), nextId(), snapshot.localRevision, runtimeSources.clock.now()),
                        draft,
                    ),
                )
                when (result) {
                    is DomainResult.Success -> {
                        pendingAutomationRuleDraft = null
                        automationRecurrenceDraftCache = null
                        withContext(Dispatchers.Main.immediate) { navigator.pop() }
                        loadAutomation("AUT-004")
                        RecurrenceWorkScheduler.enqueueCatchUp(context, snapshot.bookId)
                        mutableGlobalSnackbarMessages.emit(GlobalSnackbarMessage.AUTOMATION_UPDATED)
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

    fun reviewSelectedAutomationCandidates() {
        val state = (mutableAutomation.value as? AutomationLoadState.Content)?.state ?: return
        state.selectedCandidateIds.firstOrNull()?.let { navigateAutomation("AUT-009", it) }
    }

    fun skipSelectedAutomationCandidates() {
        val content = mutableAutomation.value as? AutomationLoadState.Content ?: return
        val selected = content.state.selectedCandidateIds
        if (selected.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            selected.forEach { id -> automationApplicationPort.skipCandidate(content.state.snapshot.bookId, id, runtimeSources.clock.now()) }
            loadAutomation("AUT-008")
        }
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

    fun cancelAutomationCandidate() {
        navigator.pop()
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
                        AutomationMutationIds(state.snapshot.bookId, CommandId(nextId()), nextId(), nextId(), nextId(), state.snapshot.localRevision, runtimeSources.clock.now()),
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
        val updated = content.state.block()
        if (updated.screenId == "AUT-003") automationBlueprintDraftCache = updated.blueprintDraft
        if (updated.screenId in setOf("AUT-005", "AUT-006")) automationRecurrenceDraftCache = updated.recurrenceDraft
        mutableAutomation.value = AutomationLoadState.Content(updated)
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

    private fun finishRecordSave(editor: OrdinaryRecordEditorState, transactionId: StableId) {
        mutableOrdinaryRecord.value = OrdinaryRecordLoadState.Content(
            snapshot = editor.snapshot,
            tab = if (editor.draft.direction == OrdinaryDirection.EXPENSE) RecordTab.EXPENSE else RecordTab.INCOME,
            selectedCategoryId = editor.draft.categoryId,
        )
        when (editor.mode) {
            RecordEditorMode.EDIT -> {
                val screen = ScreenId("JRN-007")
                navigator.pop()
                if (
                    navigator.currentKey.contract.screenId == screen &&
                    navigator.currentKey.encodedArguments["transactionId"] == transactionId.toString()
                ) {
                    loadJournalDetail(transactionId)
                } else {
                    navigator.navigate(
                        LedgerRouteContract.destination(screen, mapOf("transactionId" to StableIdArgument(transactionId))),
                        SessionGateState.READY,
                    )
                }
            }
            RecordEditorMode.CANDIDATE -> navigator.navigate(LedgerRouteContract.destination(ScreenId("AUT-008")), SessionGateState.READY)
            RecordEditorMode.CREATE, RecordEditorMode.DUPLICATE, RecordEditorMode.TEMPLATE -> {
                while (navigator.currentKey.contract.screenId.value != "REC-001" && navigator.currentBackStack.size > 1) navigator.pop()
            }
        }
        refreshJournalPaging()
        stopRecordLocationAcquisition()
    }

    private fun onFinancialCommitted(change: CommittedLedgerChange) {
        val ready = (mutableRootState.value as? AppRootState.Session)?.state as? BookSessionState.Ready ?: return
        if (ready.bookId != change.bookId) return
        invalidatedLedgerScopes.value = invalidatedLedgerScopes.value.toMutableMap().apply {
            change.scopes.forEach { scope -> put(scope, change.localRevision.value) }
        }
        synchronized(featureLoadKeyLock) { completedFeatureLoadKeys.clear() }
    }

    private fun beginFeatureLoad(
        owner: String,
        key: AppFeatureLoadKey,
        hasContent: Boolean,
        onTimeout: () -> Unit,
    ): KeyedLoadRegistry.Lease<String, AppFeatureLoadKey>? {
        if (hasContent && synchronized(featureLoadKeyLock) { completedFeatureLoadKeys[owner] == key }) return null
        val lease = featureLoadRegistry.begin(owner, key) ?: return null
        viewModelScope.launch {
            delay(DEFAULT_INTERACTIVE_LOAD_TIMEOUT_MILLIS)
            publishFeatureLoad(lease, reusable = false, publish = onTimeout)
        }
        return lease
    }

    private fun invalidateFeatureLoad(owner: String) {
        synchronized(featureLoadKeyLock) { completedFeatureLoadKeys.remove(owner) }
    }

    private fun publishFeatureLoad(
        lease: KeyedLoadRegistry.Lease<String, AppFeatureLoadKey>,
        reusable: Boolean,
        publish: () -> Unit,
    ): Boolean {
        val current = featureLoadRegistry.isCurrent(lease) &&
            activeBookSessionRuntime.readyGeneration(lease.key.bookId) == lease.key.sessionGeneration
        if (current) {
            publish()
            synchronized(featureLoadKeyLock) {
                if (reusable) completedFeatureLoadKeys[lease.owner] = lease.key else completedFeatureLoadKeys.remove(lease.owner)
            }
        }
        featureLoadRegistry.complete(lease)
        return current
    }

    private fun recordLocale(): Locale {
        val tag = settings.value.languageTag.ifBlank { Locale.getDefault().toLanguageTag() }
        return Locale.forLanguageTag(tag)
    }

    private fun formatPresentationDateTime(instant: Instant): String {
        val saved = settings.value
        val locale = Locale.forLanguageTag(saved.languageTag.ifBlank { Locale.getDefault().toLanguageTag() })
        val zone = runCatching { ZoneId.of(saved.zoneId.ifBlank { DEFAULT_ZONE }) }
            .getOrDefault(ZoneId.of(DEFAULT_ZONE))
        val zoned = instant.atZone(zone)
        val date = formatSettingsDate(zoned.toLocalDate(), saved.dateFormat.toFeature(), locale)
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(locale)
            .format(zoned)
        return "$date $time"
    }

    private fun formatImportPreviewValue(value: StagingValue): String = when (value) {
        is StagingValue.Text -> value.value
        is StagingValue.Integer -> LocaleNumberFormatter.integer(value.value, recordLocale())
        is StagingValue.Decimal -> LocaleNumberFormatter.decimal(value.value, recordLocale())
        is StagingValue.Date -> {
            val saved = settings.value
            formatSettingsDate(value.value, saved.dateFormat.toFeature(), recordLocale())
        }
        is StagingValue.InstantValue -> formatPresentationDateTime(value.value)
        StagingValue.Empty -> ""
    }

    private fun formatPresentationFileSize(bytes: Long): String {
        val configuration = Configuration(context.resources.configuration).apply { setLocale(recordLocale()) }
        val localizedContext = context.createConfigurationContext(configuration)
        return android.text.format.Formatter.formatShortFileSize(localizedContext, bytes)
    }

    private fun formatPresentationString(resourceId: Int, vararg arguments: Any): String {
        val configuration = Configuration(context.resources.configuration).apply { setLocale(recordLocale()) }
        return context.createConfigurationContext(configuration).getString(resourceId, *arguments)
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

    private suspend fun recordAttachmentPresentations(
        bookId: StableId,
        attachmentIds: List<StableId>,
    ): List<RecordAttachmentPresentation> {
        if (attachmentIds.isEmpty()) return emptyList()
        return runCatching {
            val session = bookAttachmentObjectPort.openSession(bookId)
            try {
                attachmentIds.mapNotNull { id ->
                    session.metadata(app.ledger.finance.domain.AttachmentId(id))?.let { metadata ->
                        RecordAttachmentPresentation(
                            attachmentId = id,
                            displayName = metadata.displayName,
                            sizeText = formatPresentationFileSize(metadata.plaintextSize),
                            typeLabel = metadata.mimeType,
                        )
                    }
                }
            } finally {
                session.close()
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun hydrateRecordEditor(editor: OrdinaryRecordEditorState): OrdinaryRecordEditorState {
        val selectedLocation = editor.draft.locationRecordId?.let { id ->
            editor.snapshot.references.locations.singleOrNull { it.id == id }
        }
        val selectedPlace = selectedLocation?.placeId?.let { placeId ->
            editor.snapshot.references.places.singleOrNull { it.id == placeId }
        }
        return editor.copy(
            attachmentPresentations = recordAttachmentPresentations(
                editor.snapshot.references.bookId,
                editor.draft.attachmentIds,
            ),
            locationPresentation = if (selectedLocation == null) {
                editor.locationPresentation
            } else {
                RecordLocationEditorState.Manual(
                    selectedPlace?.name ?: context.getString(app.ledger.feature.record.R.string.record_saved_location),
                )
            },
        )
    }

    fun navigateImportSource() {
        navigator.navigate(LedgerRouteContract.destination(ScreenId("IMP-001")), SessionGateState.READY)
    }

    fun navigateImportHistory() {
        val ready = (mutableRootState.value as? AppRootState.Session)?.state as? BookSessionState.Ready ?: return
        viewModelScope.launch {
            importController.showHistory(ready.bookId)
            navigator.navigate(LedgerRouteContract.destination(ScreenId("IMP-010")), SessionGateState.READY)
        }
    }

    fun navigateCurrentFilterExport() {
        val ready = (mutableRootState.value as? AppRootState.Session)?.state as? BookSessionState.Ready ?: return
        val filter = (mutableJournal.value as? JournalLoadState.Content)?.filter ?: TransactionFilter()
        exportController.beginCurrentFilter(ready.bookId, filter.toExportFilter(), journalFilterSummary())
        navigateExport("EXP-001")
    }

    fun navigateFullWorkbookExport() {
        val ready = (mutableRootState.value as? AppRootState.Session)?.state as? BookSessionState.Ready ?: return
        exportController.beginFullWorkbook(ready.bookId)
        navigateExport("EXP-001")
    }

    fun navigatePreparedReportExport(): Boolean {
        val ready = (mutableRootState.value as? AppRootState.Session)?.state as? BookSessionState.Ready ?: return false
        if (!analysisController.prepareCurrentExport()) return false
        val payload = analysisController.preparedExportForTransfer() ?: return false
        exportController.beginReport(ready.bookId, payload.toExportSnapshot(), payload.format.toTransferFormat())
        navigateExport("EXP-001")
        return true
    }

    fun selectExportContent(content: ExportContent) = exportController.selectContent(content)
    fun selectExportFormat(format: ExportFormat) = exportController.selectFormat(format)
    fun toggleExportField(field: ExportField) = exportController.toggleField(field)
    fun changeExportCoordinates(enabled: Boolean) = exportController.setCoordinates(enabled)
    fun changeExportFileName(value: String) = exportController.changeFileName(value)

    fun nextExportStep() {
        navigateExport(exportController.next())
    }

    fun selectExportDestination(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            exportController.selectDestination(uri)
        }
    }

    fun confirmExportOverwrite() {
        viewModelScope.launch(Dispatchers.IO) {
            exportController.confirmOverwrite()
        }
    }

    fun startExport() {
        viewModelScope.launch(Dispatchers.IO) {
            if (exportController.start()) {
                navigateExport("EXP-004")
                exportController.awaitCurrent()
            }
        }
    }

    fun cancelExport() = exportController.cancel()
    fun retryExport() {
        viewModelScope.launch(Dispatchers.IO) {
            if (exportController.retry()) {
                navigateExport("EXP-004")
                exportController.awaitCurrent()
            }
        }
    }
    fun openExport() = exportController.open()
    fun shareExport() = exportController.share()
    fun viewExportLocation() = exportController.viewLocation()

    private fun navigateExport(screenId: String) {
        val arguments = if (screenId == "EXP-001") {
            emptyMap()
        } else {
            exportController.currentOperationId()?.let { mapOf("operationId" to StableIdArgument(it)) }.orEmpty()
        }
        navigator.navigate(LedgerRouteContract.destination(ScreenId(screenId), arguments), SessionGateState.READY)
    }

    fun openBackup() {
        val ready = (mutableRootState.value as? AppRootState.Session)?.state as? BookSessionState.Ready ?: return
        viewModelScope.launch(Dispatchers.IO) {
            backupController.begin(ready.bookId)
            withContext(Dispatchers.Main.immediate) { navigateBackup("BKP-001") }
        }
    }

    fun navigateBackup(screenId: String) {
        backupController.setScreen(screenId)
        navigator.navigate(LedgerRouteContract.destination(ScreenId(screenId)), SessionGateState.READY)
    }

    fun openBackupDriveSettings() {
        val ready = (mutableRootState.value as? AppRootState.Session)?.state as? BookSessionState.Ready ?: return
        viewModelScope.launch(Dispatchers.IO) {
            backupController.begin(ready.bookId)
            withContext(Dispatchers.Main.immediate) { navigateBackup("SYS-003") }
        }
    }

    fun selectBackupRepository(kind: BackupRepositoryKind) = backupController.selectRepositoryKind(kind)

    fun selectBackupDirectory(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) { backupController.selectDirectory(uri) }
    }

    fun requestBackupDriveAuthorization(launchResolution: (PendingIntent) -> Unit) {
        viewModelScope.launch {
            backupController.authorizeDrive()?.let(launchResolution)
        }
    }

    fun completeBackupDriveAuthorization(intent: Intent?) = backupController.completeDriveAuthorization(intent)

    fun disconnectBackupDrive() {
        viewModelScope.launch { backupController.disconnectDrive() }
    }

    fun changeBackupRecoveryPassword(value: String) = backupController.changeRecoveryPassword(value)
    fun changeBackupRecoveryConfirmation(value: String) = backupController.changeRecoveryConfirmation(value)
    fun changeBackupRecoveryMode(mode: RecoveryPasswordChangeMode) = backupController.changePasswordMode(mode)

    fun saveBackupRecoveryPassword() {
        viewModelScope.launch(Dispatchers.Default) {
            val saved = backupController.saveRecoveryPassword()
            if (saved.succeeded) {
                saved.vaultCryptoObject?.let { mutableBackupVaultAuthenticationRequests.emit(it) }
                backupController.awaitCurrent()
            }
        }
    }

    fun backupVaultAuthenticationSucceeded(cryptoObject: BiometricPrompt.CryptoObject?) {
        if (cryptoObject == null || !backupController.completeVaultBackupEnrollment(cryptoObject)) {
            backupController.cancelVaultBackupEnrollment()
        }
    }

    fun backupVaultAuthenticationCancelled() = backupController.cancelVaultBackupEnrollment()

    fun changeAutomaticBackup(enabled: Boolean) = backupController.setAutomaticBackup(enabled)
    fun changeBackupRetentionCount(value: String) = backupController.changeRetentionCount(value)
    fun changeBackupRetentionDays(value: String) = backupController.changeRetentionDays(value)
    fun changeBackupIncludeVault(enabled: Boolean) = backupController.setIncludeVault(enabled)
    fun changeBackupNetworkPolicy(policy: BackupNetworkPolicy) = backupController.setNetworkPolicy(policy)
    fun saveBackupSettings() {
        backupController.saveSettings()
    }

    fun selectBackupSnapshot(snapshotId: String): Boolean {
        if (!backupController.selectSnapshot(snapshotId)) return false
        val stableId = StableId.parse(snapshotId).getOrNull() ?: return false
        navigator.navigate(
            LedgerRouteContract.destination(ScreenId("BKP-006"), mapOf("snapshotId" to StableIdArgument(stableId))),
            SessionGateState.READY,
        )
        return true
    }

    fun deleteBackupSnapshot(snapshotId: String) {
        if (backupFlow.value.selectedSnapshot?.repositoryKind == BackupRepositoryKind.GOOGLE_DRIVE) {
            openCloudBackupDeletion()
            return
        }
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) { backupController.deleteSnapshot(snapshotId) }
            if (deleted) navigateBackup("BKP-005")
        }
    }

    fun changePortableBackup(enabled: Boolean) = backupController.setPortable(enabled)
    fun changePortableBackupName(value: String) = backupController.changePortableName(value)

    fun startBackup(treeUri: Uri?) {
        viewModelScope.launch(Dispatchers.IO) {
            if (backupController.startBackup(treeUri)) backupController.awaitCurrent()
        }
    }

    fun cancelBackup() = backupController.cancel()

    fun retryBackup() {
        viewModelScope.launch(Dispatchers.IO) {
            if (backupController.retry()) backupController.awaitCurrent()
        }
    }

    fun openRestore() {
        val ready = (mutableRootState.value as? AppRootState.Session)?.state as? BookSessionState.Ready ?: return
        val baseCurrency = (mutableReferenceData.value as? AppReferenceDataState.Content)?.snapshot?.baseCurrency?.value.orEmpty()
        restoreController.begin(ready.bookId, baseCurrency)
        navigateRestore("RST-001")
    }

    fun openRestoreFromRecovery() {
        viewModelScope.launch(Dispatchers.IO) {
            val activeBook = runCatching { requireBookId(settingsRepository.current()) }.getOrNull() ?: return@launch
            restoreController.begin(activeBook, settingsRepository.current().baseCurrency)
            mutableRecoveryRestoreActive.value = true
        }
    }

    fun openRestoreSnapshot(snapshotId: String) {
        val ready = (mutableRootState.value as? AppRootState.Session)?.state as? BookSessionState.Ready ?: return
        val baseCurrency = (mutableReferenceData.value as? AppReferenceDataState.Content)?.snapshot?.baseCurrency?.value.orEmpty()
        restoreController.begin(ready.bookId, baseCurrency)
        if (restoreController.selectRepositorySnapshot(snapshotId)) navigateRestore("RST-002")
    }

    fun selectRestorePortable(uri: Uri): Boolean {
        if (!restoreController.selectPortable(uri)) return false
        navigateRestore("RST-002")
        return true
    }

    fun selectLatestRestoreRepository(): Boolean = restoreController.showRepositorySnapshots()

    fun selectRestoreRepositorySnapshot(snapshotId: String): Boolean {
        if (!restoreController.selectRepositorySnapshot(snapshotId)) return false
        navigateRestore("RST-002")
        return true
    }

    fun requestRestoreDriveAuthorization(
        launchResolution: (PendingIntent) -> Unit,
        onSelected: () -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolution = restoreController.selectLatestDriveRepository()
            withContext(Dispatchers.Main.immediate) {
                if (resolution != null) {
                    launchResolution(resolution)
                } else if (restoreController.state.value.sourcePicker == app.ledger.feature.transfer.RestoreSourcePicker.DRIVE) {
                    onSelected()
                }
            }
        }
    }

    fun completeRestoreDriveAuthorization(intent: Intent?, onSelected: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            restoreController.selectLatestDriveRepository(intent)
            withContext(Dispatchers.Main.immediate) {
                if (restoreController.state.value.sourcePicker == app.ledger.feature.transfer.RestoreSourcePicker.DRIVE) {
                    onSelected()
                }
            }
        }
    }

    fun changeRestorePassword(value: String) = restoreController.passwordChanged(value)

    fun verifyRestorePassword() {
        viewModelScope.launch(Dispatchers.IO) {
            restoreController.verifyAndInspect()
            withContext(Dispatchers.Main.immediate) { navigateRestore(restoreController.state.value.screenId) }
        }
    }

    fun selectRestoreMode(mode: RestoreMode) = restoreController.selectMode(mode)
    fun changeRestoreHighRiskPhrase(value: String) = restoreController.highRiskPhraseChanged(value)

    fun startRestore() {
        if (restoreController.state.value.mode == RestoreMode.MERGE) {
            viewModelScope.launch(Dispatchers.IO) {
                restoreController.start()
                withContext(Dispatchers.Main.immediate) { navigateRestore(restoreController.state.value.screenId) }
            }
        } else {
            executeRestoreMaintenance { restoreController.start() }
        }
    }

    fun resolveRestoreConflict(conflictId: String, resolution: MergeResolution) = restoreController.resolve(conflictId, resolution)
    fun changeRestoreApplyToSimilar(value: Boolean) = restoreController.applyToSimilarChanged(value)

    fun applyRestoreMerge() = executeRestoreMaintenance { restoreController.applyMerge() }
    fun cancelRestore() = restoreController.cancel()

    fun retryRestore() {
        val ready = (mutableRootState.value as? AppRootState.Session)?.state as? BookSessionState.Ready ?: return
        restoreController.begin(ready.bookId)
        navigateRestore("RST-001")
    }

    fun finishRestoreFlow() {
        mutableRecoveryRestoreActive.value = false
        selectRootTopLevel(TopLevelDestination.JOURNAL)
    }

    fun confirmRestoreSafetySnapshotCleanup() = restoreController.confirmSafetySnapshotCleanup()

    fun openCloudBackupDeletion() {
        val ready = (mutableRootState.value as? AppRootState.Session)?.state as? BookSessionState.Ready ?: return
        restoreController.begin(ready.bookId)
        navigateRestore("CLR-002")
    }

    fun loadCloudBackupsForDeletion() = requestSensitiveSettingsAuthentication(SensitiveSettingsAuthenticationPurpose.DELETE_CLOUD)

    private fun loadCloudBackupsForDeletionAuthenticated() {
        viewModelScope.launch(Dispatchers.IO) { restoreController.loadCloudBackups() }
    }

    fun selectCloudBackupForDeletion(value: String) = restoreController.selectCloudSnapshot(value)
    fun changeCloudBackupDeletePhrase(value: String) = restoreController.cloudPhraseChanged(value)
    fun deleteSelectedCloudBackups() {
        viewModelScope.launch(Dispatchers.IO) { restoreController.deleteSelectedCloudBackups() }
    }

    private fun executeRestoreMaintenance(block: suspend () -> Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val manager = sessionManager ?: return@launch
            val recovering = manager.state.value is BookSessionState.RecoveryRequired
            mutableMaintenancePresentation.value = MaintenancePresentation.PREPARING
            revisionCacheControl.clearBook(manager.bookId)
            if (!recovering) manager.enterMaintenance(MaintenanceReason.CONTROLLED_MAINTENANCE)
            manager.close()
            mutableMaintenancePresentation.value = MaintenancePresentation.CANCELABLE
            val succeeded = withOfflinePrimaryMaintenance(manager, block)
            val recoveredVaultDek = if (succeeded) restoreController.takeRecoveredVaultDek() else null
            if (!recovering) {
                mutableMaintenancePresentation.value = if (succeeded) MaintenancePresentation.SUCCEEDED else MaintenancePresentation.FAILED
                if (succeeded) {
                    delay(MAINTENANCE_RESULT_VISIBILITY_MILLIS)
                    manager.finishMaintenance()
                }
            } else if (succeeded) {
                sessionManager = null
                openSavedBook(settingsRepository.current())
            }
            recoveredVaultDek?.let { key ->
                runCatching { vaultController.requestRecoveredVaultRewrap(requireBookId(settingsRepository.current()), key) }
                    .onFailure { key.close() }
            }
            withContext(Dispatchers.Main.immediate) { navigateRestore("RST-007") }
        }
    }

    fun cancelMaintenance() = restoreController.cancel()

    private suspend fun <T> withOfflinePrimaryMaintenance(
        manager: app.ledger.core.security.BookSessionManager,
        block: suspend () -> T,
    ): T {
        val operationId = restoreController.currentOperationId() ?: runtimeSources.stableIds.nextStableId()
        val permit = manager.acquireOfflinePrimaryMaintenancePermit(operationId)
        restoreController.enterOfflinePrimaryMaintenance(permit)
        return try {
            block()
        } finally {
            restoreController.leaveOfflinePrimaryMaintenance()
            permit.release()
        }
    }

    fun retryMaintenance() {
        val manager = sessionManager ?: return
        val state = manager.state.value as? BookSessionState.Maintenance ?: return
        if (state.reason != MaintenanceReason.UNFINISHED_OPERATION || restoreRecoveryRunning) return
        restoreRecoveryRunning = true
        mutableMaintenancePresentation.value = MaintenancePresentation.PREPARING
        viewModelScope.launch(Dispatchers.IO) {
            try {
                mutableMaintenancePresentation.value = MaintenancePresentation.RUNNING
                manager.close()
                if (withOfflinePrimaryMaintenance(manager) {
                        restoreController.recoverInterrupted(requireBookId(settingsRepository.current()))
                    }
                ) {
                    mutableMaintenancePresentation.value = MaintenancePresentation.SUCCEEDED
                    delay(MAINTENANCE_RESULT_VISIBILITY_MILLIS)
                    manager.finishMaintenance()
                } else {
                    mutableMaintenancePresentation.value = MaintenancePresentation.FAILED
                }
            } finally {
                restoreRecoveryRunning = false
            }
        }
    }

    private fun navigateRestore(screenId: String) {
        restoreController.setScreen(screenId)
        val arguments = if (screenId in setOf("RST-001", "CLR-002")) {
            emptyMap()
        } else {
            restoreController.currentOperationId()?.let { mapOf("operationId" to StableIdArgument(it)) }.orEmpty()
        }
        navigator.navigate(LedgerRouteContract.destination(ScreenId(screenId), arguments), SessionGateState.READY)
    }

    fun selectImportMode(mode: ImportModeUi) = importController.selectMode(mode)

    fun selectImportSheet(name: String) = importController.selectSheet(name)
    fun changeImportEncoding(value: String) = importController.changeEncoding(value)
    fun changeImportHeaderRow(value: String) = importController.changeHeaderRow(value)
    fun cycleImportFieldMapping(source: String) = importController.cycleFieldMapping(source)
    fun selectImportFieldMapping(source: String, target: String?) = importController.selectFieldMapping(source, target)
    fun changeImportMissingCreation(type: String, enabled: Boolean) = importController.setCreateMissing(type, enabled)
    fun cycleImportEntityMapping(type: String, source: String) = importController.cycleEntityMapping(type, source)
    fun selectImportEntityMapping(type: String, source: String, target: String) = importController.selectEntityMapping(type, source, target)
    fun changeImportFxPolicy(source: String, policy: app.ledger.feature.transfer.ImportFxPolicyUi) = importController.setFxPolicy(source, policy)
    fun changeImportFxRate(sourceCurrency: String, value: String) = importController.setFxRate(sourceCurrency, value)
    fun resolveImportDuplicate(rowNumber: Long, resolution: app.ledger.transfer.domain.DuplicateResolution) {
        viewModelScope.launch(Dispatchers.IO) { importController.resolveDuplicate(rowNumber, resolution) }
    }

    fun setImportRowExcluded(rowNumber: Long, excluded: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { importController.setRowExcluded(rowNumber, excluded) }
    }

    fun selectImportSource(uri: Uri) {
        val ready = (mutableRootState.value as? AppRootState.Session)?.state as? BookSessionState.Ready ?: return
        val zone = runCatching { ZoneId.of(settings.value.zoneId.ifBlank { DEFAULT_ZONE }) }.getOrDefault(ZoneId.of(DEFAULT_ZONE))
        viewModelScope.launch(Dispatchers.IO) {
            importController.selectSource(ready.bookId, zone, uri)
            navigateImportStage()
        }
    }

    fun nextImportStage() {
        viewModelScope.launch(Dispatchers.IO) {
            importController.next()
            navigateImportStage()
        }
    }

    fun previousImportStage() {
        viewModelScope.launch {
            importController.previous()
            navigateImportStage()
        }
    }

    fun exitImport(onExited: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!importController.abandon()) return@launch
            withContext(Dispatchers.Main.immediate) {
                while (navigator.currentKey.contract.screenId.value.startsWith("IMP-")) navigator.pop()
                onExited()
            }
        }
    }

    fun pauseImport() = importController.togglePause()
    fun cancelImport() {
        viewModelScope.launch(Dispatchers.IO) {
            importController.cancel()
            navigateImportStage()
        }
    }
    fun retryImport() {
        viewModelScope.launch(Dispatchers.IO) {
            importController.retry()
            navigateImportStage()
        }
    }
    fun rollbackImport() {
        viewModelScope.launch(Dispatchers.IO) { importController.rollback() }
    }

    fun viewImportValidationIssues() {
        importController.viewValidationIssues()
        navigateImportStage()
    }

    fun cleanupImportTemporary() {
        viewModelScope.launch(Dispatchers.IO) { importController.cleanupTemporary() }
    }

    fun viewImportHistoryResult(batchId: String) {
        importController.viewHistoryResult(batchId)
        navigateImportStage()
    }

    fun rollbackImportHistory(batchId: String) {
        viewModelScope.launch(Dispatchers.IO) { importController.rollbackHistory(batchId) }
    }

    private fun navigateImportStage() {
        val screen = when (importWizard.value.showHistory) {
            true -> "IMP-010"
            false -> "IMP-${(importWizard.value.stage.ordinal + 1).toString().padStart(3, '0')}"
        }
        val operation = importController.currentOperationId()
        val arguments = if (screen in setOf("IMP-001", "IMP-010") || operation == null) {
            emptyMap()
        } else {
            mapOf("operationId" to StableIdArgument(operation))
        }
        if (navigator.currentKey.contract.screenId.value.startsWith("IMP-")) navigator.pop()
        navigator.navigate(LedgerRouteContract.destination(ScreenId(screen), arguments), SessionGateState.READY)
    }

    private fun sanitizeCode(value: String): String = value.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9_]"), "_").take(48).ifBlank { "RECORD_FAILURE" }

    private fun nextId(): StableId = runtimeSources.stableIds.nextStableId()

    private fun <T> DomainResult<T>.requireSuccess(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error(error.code)
    }

    private fun languageFromTag(value: String): OnboardingLanguage = OnboardingLanguage.entries.firstOrNull {
        it.tag.equals(value, ignoreCase = true) || value.startsWith("${it.tag}-", ignoreCase = true)
    } ?: OnboardingLanguage.SIMPLIFIED_CHINESE

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
        const val MAINTENANCE_RESULT_VISIBILITY_MILLIS = 600L
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
        const val WIDGET_DEEP_LINK_HOST = "widget"
        const val WIDGET_CONSUMPTION_REPORT_KEY = "consumption-category-structure"
        const val SOURCE_CODE_URL = "https://github.com/HANLONG-WANG/ExpenseTracker"
        const val REFERENCE_REVISION_ID_RESERVE = 128
        const val FINANCIAL_FACT_ID_RESERVE = 256
        const val FX_ID_RESERVE = 8
        const val RECORD_SEARCH_LIMIT = 80
        const val SEARCH_DEBOUNCE_MILLIS = 300L
        val JOURNAL_OPTION_SCREENS = setOf("JRN-003", "JRN-006")
        val JOURNAL_PAGED_SCREENS = setOf("JRN-001", "JRN-002", "JRN-005", "JRN-011")
        const val FEATURE_LOAD_REFUND = "refund"
        const val FEATURE_LOAD_SPECIALIZED = "specialized"
        const val FEATURE_LOAD_PROJECT_GOAL = "project_goal"
        const val FEATURE_LOAD_CREDIT = "credit"
        const val FEATURE_LOAD_INSTALLMENT = "installment"
        const val FEATURE_LOAD_SETTLEMENT = "settlement"
        const val FEATURE_LOAD_AUTOMATION = "automation"
        const val FEATURE_LOAD_LOAN = "loan"
        const val FEATURE_LOAD_OPERATION_CENTER = "operation_center"
        const val RECORD_ATTACHMENT_NAME_LIMIT = 255
        const val SECONDS_PER_DAY = 24L * 60L * 60L
        const val AUTOMATION_TEMPLATE_NAME_LIMIT = 80
        const val AUTOMATION_TEMPLATE_NOTE_LIMIT = 500
        val REMAINING_SETTINGS_SCREENS = setOf("SETG-001", "SETG-002", "SETG-003", "SETG-005", "SETG-012")
        val ACCOUNT_HISTORY_SCREENS = setOf("ACC-005", "ACC-006", "ACC-007", "ACC-008")
        val SUPPORTED_LANGUAGE_TAGS = setOf("zh-CN", "ja", "en")
        val CANCELABLE_OPERATION_STATES = setOf(
            BackgroundOperationState.QUEUED,
            BackgroundOperationState.PREPARING,
            BackgroundOperationState.RUNNING,
            BackgroundOperationState.PAUSED,
        )
        val OPEN_SOURCE_NOTICES = listOf(
            "AndroidX · Apache License 2.0",
            "Jetpack Compose · Apache License 2.0",
            "SQLCipher · BSD-style license",
            "Tink · Apache License 2.0",
            "Apache Commons CSV/Compress · Apache License 2.0",
            "FastExcel · Apache License 2.0",
            "MapLibre Native · BSD 2-Clause License",
            "Vico · Apache License 2.0",
            "ACRA · Apache License 2.0",
        )
    }
}

private fun ThemeModeProto.toFeature(): SettingsThemeMode = when (this) {
    ThemeModeProto.THEME_MODE_LIGHT -> SettingsThemeMode.LIGHT
    ThemeModeProto.THEME_MODE_DARK -> SettingsThemeMode.DARK
    ThemeModeProto.THEME_MODE_FOLLOW_SYSTEM, ThemeModeProto.UNRECOGNIZED -> SettingsThemeMode.FOLLOW_SYSTEM
}

private fun SettingsThemeMode.toProto(): ThemeModeProto = when (this) {
    SettingsThemeMode.FOLLOW_SYSTEM -> ThemeModeProto.THEME_MODE_FOLLOW_SYSTEM
    SettingsThemeMode.LIGHT -> ThemeModeProto.THEME_MODE_LIGHT
    SettingsThemeMode.DARK -> ThemeModeProto.THEME_MODE_DARK
}

private fun DateFormatProto.toFeature(): SettingsDateFormat = when (this) {
    DateFormatProto.DATE_FORMAT_YEAR_MONTH_DAY -> SettingsDateFormat.YEAR_MONTH_DAY
    DateFormatProto.DATE_FORMAT_DAY_MONTH_YEAR -> SettingsDateFormat.DAY_MONTH_YEAR
    DateFormatProto.DATE_FORMAT_MONTH_DAY_YEAR -> SettingsDateFormat.MONTH_DAY_YEAR
    DateFormatProto.DATE_FORMAT_LOCALE_DEFAULT, DateFormatProto.UNRECOGNIZED -> SettingsDateFormat.LOCALE_DEFAULT
}

private fun SettingsDateFormat.toProto(): DateFormatProto = when (this) {
    SettingsDateFormat.LOCALE_DEFAULT -> DateFormatProto.DATE_FORMAT_LOCALE_DEFAULT
    SettingsDateFormat.YEAR_MONTH_DAY -> DateFormatProto.DATE_FORMAT_YEAR_MONTH_DAY
    SettingsDateFormat.DAY_MONTH_YEAR -> DateFormatProto.DATE_FORMAT_DAY_MONTH_YEAR
    SettingsDateFormat.MONTH_DAY_YEAR -> DateFormatProto.DATE_FORMAT_MONTH_DAY_YEAR
}

private fun WeekStartProto.toFeature(): SettingsWeekStart = when (this) {
    WeekStartProto.WEEK_START_MONDAY -> SettingsWeekStart.MONDAY
    WeekStartProto.WEEK_START_SUNDAY -> SettingsWeekStart.SUNDAY
    WeekStartProto.WEEK_START_LOCALE_DEFAULT, WeekStartProto.UNRECOGNIZED -> SettingsWeekStart.LOCALE_DEFAULT
}

private fun SettingsWeekStart.toProto(): WeekStartProto = when (this) {
    SettingsWeekStart.LOCALE_DEFAULT -> WeekStartProto.WEEK_START_LOCALE_DEFAULT
    SettingsWeekStart.MONDAY -> WeekStartProto.WEEK_START_MONDAY
    SettingsWeekStart.SUNDAY -> WeekStartProto.WEEK_START_SUNDAY
}

private fun formatSettingsDate(
    date: java.time.LocalDate,
    format: SettingsDateFormat,
    locale: Locale,
): String = date.format(
    when (format) {
        SettingsDateFormat.LOCALE_DEFAULT -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        SettingsDateFormat.YEAR_MONTH_DAY -> DateTimeFormatter.ofPattern("uuuu/MM/dd", locale)
        SettingsDateFormat.DAY_MONTH_YEAR -> DateTimeFormatter.ofPattern("dd/MM/uuuu", locale)
        SettingsDateFormat.MONTH_DAY_YEAR -> DateTimeFormatter.ofPattern("MM/dd/uuuu", locale)
    },
)

internal data class JournalPagingRequest(
    val bookId: StableId,
    val filter: TransactionFilter,
    val runningBalanceAccountId: StableId? = null,
    val refreshEpoch: Int,
)

private data class JournalSearchLoadKey(
    val bookId: StableId,
    val sessionGeneration: Long,
    val filter: TransactionFilter,
)

private data class OrdinaryLoadKey(
    val bookId: StableId,
    val sessionGeneration: Long,
    val transactionId: StableId?,
)

private data class BudgetLoadKey(
    val bookId: StableId,
    val sessionGeneration: Long,
    val month: YearMonth,
    val templateId: StableId?,
    val screenId: String,
)

private data class AppFeatureLoadKey(
    val bookId: StableId,
    val sessionGeneration: Long,
    val screenId: String,
    val arguments: List<String?> = emptyList(),
)

private data class RefundSearchLoadKey(
    val bookId: StableId,
    val sessionGeneration: Long,
    val query: RefundSearchQuery,
)

private data class AutomationHubQueryResults(
    val automation: DomainResult<app.ledger.finance.application.AutomationSnapshot>,
    val entry: DomainResult<app.ledger.finance.application.OrdinaryTransactionEntrySnapshot>,
)

private data class LiabilityHubQueryResults(
    val loan: DomainResult<app.ledger.finance.application.LoanSnapshot>,
    val credit: DomainResult<app.ledger.finance.application.CreditSnapshot>?,
    val installment: DomainResult<app.ledger.finance.application.InstallmentSnapshot>?,
)

internal data class ProjectTransactionPagingRequest(
    val bookId: StableId,
    val projectId: StableId,
    val kind: TransactionKind? = null,
)

private data class WidgetFormSeed(
    val mode: RecordEditorMode,
    val direction: OrdinaryDirection,
    val categoryId: StableId?,
    val sourceId: StableId?,
)

private sealed interface PendingRecordExit {
    data object Back : PendingRecordExit
    data class TopLevel(val target: TopLevelDestination) : PendingRecordExit
}

private data class ReferenceDataLoadKey(
    val bookId: StableId,
    val sessionGeneration: Long,
    val routeScope: String,
    val screenId: String,
    val accountId: StableId?,
    val categoryDirection: CategoryDirection?,
)

private sealed interface PendingGeneralExit {
    val scope: String

    data class Back(override val scope: String) : PendingGeneralExit
    data class TopLevel(override val scope: String, val target: TopLevelDestination) : PendingGeneralExit
}

private fun LedgerDestinationKey.retainedScopeKey(): String = buildString {
    append(contract.screenId.value)
    encodedArguments.toSortedMap().forEach { (name, value) ->
        append('|').append(name).append('=').append(value)
    }
}

private fun TransactionFilter.toExportFilter(): ExportFilter = ExportFilter(
    occurredFrom = occurredFrom,
    occurredThrough = occurredThrough,
    createdFrom = createdFrom,
    createdThrough = createdThrough,
    modifiedFrom = modifiedFrom,
    modifiedThrough = modifiedThrough,
    kinds = kinds.mapTo(mutableSetOf()) { it.ordinal },
    accountIds = accountIds.mapTo(mutableSetOf()) { it.value },
    cardIds = cardIds.mapTo(mutableSetOf()) { it.value },
    categoryIds = categoryIds.mapTo(mutableSetOf()) { it.value },
    merchantIds = merchantIds.mapTo(mutableSetOf()) { it.value },
    projectIds = projectIds.mapTo(mutableSetOf()) { it.value },
    settlementActivityIds = settlementActivityIds.mapTo(mutableSetOf()) { it.value },
    participantIds = participantIds.mapTo(mutableSetOf()) { it.value },
    currencies = currencies.mapTo(mutableSetOf()) { it.value },
    statisticalNatures = statisticalNatures.mapTo(mutableSetOf()) { it.ordinal },
    lifecycleStates = lifecycleStates.mapTo(mutableSetOf()) { it.ordinal },
    sources = sources.mapTo(mutableSetOf()) { it.ordinal },
    minimumAccountMinor = amountRange?.minimumAccountMinor,
    maximumAccountMinor = amountRange?.maximumAccountMinor,
    amountCurrency = amountRange?.currency?.value,
    centerLatitudeE7 = geoRadius?.center?.latitudeE7,
    centerLongitudeE7 = geoRadius?.center?.longitudeE7,
    radiusMeters = geoRadius?.radiusMeters,
    hasAttachment = hasAttachment,
    isRefund = isRefund,
    hasInstallment = hasInstallment,
    includedInBudget = includedInBudget,
    generatedByRecurrence = generatedByRecurrence,
    searchText = searchText,
)

private fun ReportExportPayload.toExportSnapshot(): ExportReportSnapshot {
    val compared = comparison != null
    val headers = buildList {
        if (compared) add("period_kind")
        plan.spec.dimensions.forEach { add("dimension_${it.name.lowercase(Locale.ROOT)}") }
        plan.spec.measures.forEach { add("measure_${it.name.lowercase(Locale.ROOT)}") }
    }
    fun exportRow(row: app.ledger.analytics.domain.ReportRow, periodKind: String?): List<String> = buildList {
        if (compared) add(requireNotNull(periodKind))
        row.dimensionValues.forEach { value ->
            add(
                when (value) {
                    is DimensionValue.Date -> value.value.toString()
                    is DimensionValue.Entity -> value.label
                    is DimensionValue.Currency -> value.value.value
                    is DimensionValue.ClosedKey -> value.value
                },
            )
        }
        row.measureValues.forEach { value ->
            add(
                value.minorValue?.toString()?.let { minor -> value.currency?.value?.let { "$minor $it" } ?: minor }
                    ?: requireNotNull(value.decimalValue).toPlainString(),
            )
        }
    }
    val exportRows = buildList {
        rows.forEach { add(exportRow(it, "current".takeIf { compared })) }
        comparison?.rows?.forEach { add(exportRow(it, "reference")) }
    }
    return ExportReportSnapshot(
        reportKey = reportKey?.value ?: "custom-report",
        periodStart = period.start.toString(),
        periodEndInclusive = period.endInclusive.toString(),
        headers = headers,
        rows = exportRows,
        localRevision = plan.asOfLocalRevision.value,
        valuationRevision = plan.asOfValuationRevision?.value,
    )
}

private fun ReportExportFormat.toTransferFormat(): ExportFormat = when (this) {
    ReportExportFormat.IMAGE -> ExportFormat.IMAGE
    ReportExportFormat.PDF -> ExportFormat.PDF
    ReportExportFormat.CSV -> ExportFormat.CSV
    ReportExportFormat.XLSX -> ExportFormat.XLSX
}
