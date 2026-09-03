@file:Suppress("TooManyFunctions")

package app.ledger.app

import android.content.Context
import android.os.SystemClock
import app.ledger.analytics.data.SecureRoomAnalyticsApplicationPort
import app.ledger.analytics.domain.AnalyticsApplicationPort
import app.ledger.core.common.StableId
import app.ledger.core.common.StableIdSource
import app.ledger.core.common.getOrNull
import app.ledger.core.files.SecureBookAttachmentObjectPort
import app.ledger.core.security.ActiveBookSessionManagerFactory
import app.ledger.core.security.ActiveBookSessionRuntime
import app.ledger.core.security.AndroidKeystoreKeys
import app.ledger.core.security.BookSessionManager
import app.ledger.core.security.CryptographicRandomSource
import app.ledger.core.security.DefaultLedgerStartupInspector
import app.ledger.core.security.DeviceKeyHierarchy
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.core.security.PlatformCryptographicRandomSource
import app.ledger.core.security.SecurityEnvelopeStore
import app.ledger.core.security.SqlCipherBookDatabaseResourceFactory
import app.ledger.core.security.VaultExposureRegistry
import app.ledger.core.time.InjectedJavaClock
import app.ledger.core.time.JavaTimeLedgerClock
import app.ledger.core.time.LedgerClock
import app.ledger.finance.application.AutomationApplicationPort
import app.ledger.finance.application.BatchEntryApplicationPort
import app.ledger.finance.application.BudgetApplicationPort
import app.ledger.finance.application.ControlledPurgeApplicationPort
import app.ledger.finance.application.CreditApplicationPort
import app.ledger.finance.application.FormalOccurrenceGenerator
import app.ledger.finance.application.ImportFinancialApplicationPort
import app.ledger.finance.application.InstallmentApplicationPort
import app.ledger.finance.application.JournalApplicationPort
import app.ledger.finance.application.LedgerExportQueryPort
import app.ledger.finance.application.LedgerInitializationPort
import app.ledger.finance.application.LedgerRevisionCacheControl
import app.ledger.finance.application.LoanApplicationPort
import app.ledger.finance.application.OpeningBalanceWritePort
import app.ledger.finance.application.OrdinaryTransactionEntryPort
import app.ledger.finance.application.ProjectGoalApplicationPort
import app.ledger.finance.application.ReferenceDataManagementPort
import app.ledger.finance.application.RefundApplicationPort
import app.ledger.finance.application.SettlementApplicationPort
import app.ledger.finance.application.SpecializedTransactionEntryPort
import app.ledger.finance.application.StructuredImportApplicationPort
import app.ledger.finance.application.VaultSecretApplicationPort
import app.ledger.finance.application.WidgetSnapshotApplicationPort
import app.ledger.finance.application.WidgetSnapshotRefreshApplicationPort
import app.ledger.finance.data.RoomLedgerStartupInspector
import app.ledger.finance.data.SecureRoomAutomationApplicationPort
import app.ledger.finance.data.SecureRoomBatchEntryApplicationPort
import app.ledger.finance.data.SecureRoomBudgetApplicationPort
import app.ledger.finance.data.SecureRoomControlledPurgeApplicationPort
import app.ledger.finance.data.SecureRoomCreditApplicationPort
import app.ledger.finance.data.SecureRoomImportFinancialApplicationPort
import app.ledger.finance.data.SecureRoomInstallmentApplicationPort
import app.ledger.finance.data.SecureRoomJournalApplicationPort
import app.ledger.finance.data.SecureRoomLedgerExportQueryPort
import app.ledger.finance.data.SecureRoomLedgerInitializationPort
import app.ledger.finance.data.SecureRoomLoanApplicationPort
import app.ledger.finance.data.SecureRoomOpeningBalanceWritePort
import app.ledger.finance.data.SecureRoomOrdinaryTransactionEntryPort
import app.ledger.finance.data.SecureRoomProjectGoalApplicationPort
import app.ledger.finance.data.SecureRoomReferenceDataManagementPort
import app.ledger.finance.data.SecureRoomRefundApplicationPort
import app.ledger.finance.data.SecureRoomSettlementApplicationPort
import app.ledger.finance.data.SecureRoomSpecializedTransactionEntryPort
import app.ledger.finance.data.SecureRoomStructuredImportApplicationPort
import app.ledger.finance.data.SecureRoomVaultSecretApplicationPort
import app.ledger.finance.data.SecureRoomWidgetSnapshotApplicationPort
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

internal data class AppRuntimeSources(
    val clock: LedgerClock,
    val stableIds: StableIdSource,
    val cryptographicRandom: CryptographicRandomSource,
)

@Module
@InstallIn(SingletonComponent::class)
internal object AppDependencyModule {
    @Provides
    @Singleton
    fun settings(@ApplicationContext context: Context): AppSettingsRepository = AppSettingsRepository(context)

    @Provides
    @Singleton
    fun keyHierarchy(@ApplicationContext context: Context): DeviceKeyHierarchy = DeviceKeyHierarchy(
        AndroidKeystoreKeys(context),
        SecurityEnvelopeStore(context),
    )

    @Provides
    fun keyProvider(hierarchy: DeviceKeyHierarchy): DeviceLedgerKeyProvider = hierarchy

    @Provides
    @Singleton
    fun vaultExposureRegistry(): VaultExposureRegistry = VaultExposureRegistry(SystemClock::elapsedRealtime)

    @Provides
    @Singleton
    fun activeBookSessionRuntime(
        @ApplicationContext context: Context,
        keyProvider: DeviceLedgerKeyProvider,
        vaultExposureRegistry: VaultExposureRegistry,
    ): ActiveBookSessionRuntime {
        val resourceFactory = SqlCipherBookDatabaseResourceFactory(
            context,
            listOf(DefaultLedgerStartupInspector, RoomLedgerStartupInspector()),
        )
        return ActiveBookSessionRuntime(
            ActiveBookSessionManagerFactory { bookId ->
                BookSessionManager(bookId, keyProvider, resourceFactory, vaultExposureRegistry)
            },
        )
    }

    @Provides
    fun ledgerClock(): LedgerClock = JavaTimeLedgerClock.systemUtc()

    @Provides
    fun cryptographicRandom(): CryptographicRandomSource = PlatformCryptographicRandomSource

    @Provides
    fun stableIdSource(random: CryptographicRandomSource): StableIdSource = StableIdSource {
        val bytes = ByteArray(StableId.BYTE_COUNT).also(random::nextBytes)
        requireNotNull(StableId.fromBytes(bytes).getOrNull())
    }

    @Provides
    fun runtimeSources(
        clock: LedgerClock,
        stableIds: StableIdSource,
        cryptographicRandom: CryptographicRandomSource,
    ): AppRuntimeSources = AppRuntimeSources(clock, stableIds, cryptographicRandom)

    @Provides
    @Singleton
    fun initializationPort(
        @ApplicationContext context: Context,
        keyProvider: DeviceLedgerKeyProvider,
    ): LedgerInitializationPort = SecureRoomLedgerInitializationPort(context, keyProvider)

    @Provides
    @Singleton
    fun secureReferenceDataPort(
        sessionRuntime: ActiveBookSessionRuntime,
    ): SecureRoomReferenceDataManagementPort = SecureRoomReferenceDataManagementPort(sessionRuntime)

    @Provides
    fun referenceDataPort(port: SecureRoomReferenceDataManagementPort): ReferenceDataManagementPort = port

    @Provides
    fun ledgerRevisionCacheControl(port: SecureRoomReferenceDataManagementPort): LedgerRevisionCacheControl = port

    @Provides
    @Singleton
    fun vaultSecretApplicationPort(
        sessionRuntime: ActiveBookSessionRuntime,
    ): VaultSecretApplicationPort = SecureRoomVaultSecretApplicationPort(sessionRuntime)

    @Provides
    @Singleton
    fun journalApplicationPort(
        @ApplicationContext context: Context,
        sessionRuntime: ActiveBookSessionRuntime,
    ): JournalApplicationPort = SecureRoomJournalApplicationPort(context, sessionRuntime)

    @Provides
    @Singleton
    fun controlledPurgeApplicationPort(
        journal: JournalApplicationPort,
        sessionRuntime: ActiveBookSessionRuntime,
    ): ControlledPurgeApplicationPort = SecureRoomControlledPurgeApplicationPort(
        sessionRuntime,
        journal,
    )

    @Provides
    @Singleton
    fun ledgerExportQueryPort(
        journal: JournalApplicationPort,
        sessionRuntime: ActiveBookSessionRuntime,
    ): LedgerExportQueryPort = SecureRoomLedgerExportQueryPort(sessionRuntime, journal)

    @Provides
    @Singleton
    fun widgetSnapshotApplicationPort(
        sessionRuntime: ActiveBookSessionRuntime,
    ): WidgetSnapshotApplicationPort = AppHeadlessWidgetSnapshotApplicationPort(
        sessionRuntime,
        SecureRoomWidgetSnapshotApplicationPort(sessionRuntime),
    )

    @Provides
    @Singleton
    fun widgetSnapshotRefreshApplicationPort(
        sessionRuntime: ActiveBookSessionRuntime,
    ): WidgetSnapshotRefreshApplicationPort = SecureRoomWidgetSnapshotApplicationPort(sessionRuntime)

    @Provides
    @Singleton
    fun budgetApplicationPort(
        sessionRuntime: ActiveBookSessionRuntime,
    ): BudgetApplicationPort = SecureRoomBudgetApplicationPort(sessionRuntime)

    @Provides
    @Singleton
    fun projectGoalApplicationPort(
        referenceDataPort: ReferenceDataManagementPort,
        sessionRuntime: ActiveBookSessionRuntime,
    ): ProjectGoalApplicationPort = SecureRoomProjectGoalApplicationPort(
        sessionRuntime,
        referenceDataPort,
    )

    @Provides
    @Singleton
    fun creditApplicationPort(
        sessionRuntime: ActiveBookSessionRuntime,
    ): CreditApplicationPort = SecureRoomCreditApplicationPort(sessionRuntime)

    @Provides
    @Singleton
    fun installmentApplicationPort(
        sessionRuntime: ActiveBookSessionRuntime,
    ): InstallmentApplicationPort = SecureRoomInstallmentApplicationPort(sessionRuntime)

    @Provides
    @Singleton
    fun loanApplicationPort(
        sessionRuntime: ActiveBookSessionRuntime,
    ): LoanApplicationPort = SecureRoomLoanApplicationPort(sessionRuntime)

    @Provides
    @Singleton
    fun settlementApplicationPort(
        sessionRuntime: ActiveBookSessionRuntime,
    ): SettlementApplicationPort = SecureRoomSettlementApplicationPort(sessionRuntime)

    @Provides
    @Singleton
    fun formalOccurrenceGenerator(
        ordinary: OrdinaryTransactionEntryPort,
        credit: CreditApplicationPort,
        loan: LoanApplicationPort,
    ): FormalOccurrenceGenerator = AppFormalOccurrenceGenerator(ordinary, credit, loan)

    @Provides
    @Singleton
    fun automationApplicationPort(
        generator: FormalOccurrenceGenerator,
        sessionRuntime: ActiveBookSessionRuntime,
    ): AutomationApplicationPort = SecureRoomAutomationApplicationPort(
        sessionRuntime,
        generator,
    )

    @Provides
    @Singleton
    fun analyticsApplicationPort(
        sessionRuntime: ActiveBookSessionRuntime,
        runtime: AppRuntimeSources,
    ): AnalyticsApplicationPort = SecureRoomAnalyticsApplicationPort(
        sessionRuntime,
        runtime.stableIds,
        runtime.clock,
    )

    @Provides
    @Singleton
    fun headlessRecurrenceExecutor(
        sessionRuntime: ActiveBookSessionRuntime,
        automation: AutomationApplicationPort,
    ): HeadlessRecurrenceExecutor = AppHeadlessRecurrenceExecutor(sessionRuntime, automation)

    @Provides
    @Singleton
    fun openingBalanceWritePort(
        sessionRuntime: ActiveBookSessionRuntime,
    ): OpeningBalanceWritePort = SecureRoomOpeningBalanceWritePort(sessionRuntime)

    @Provides
    @Singleton
    fun ordinaryTransactionEntryPort(
        sessionRuntime: ActiveBookSessionRuntime,
        referenceDataPort: ReferenceDataManagementPort,
    ): OrdinaryTransactionEntryPort = SecureRoomOrdinaryTransactionEntryPort(sessionRuntime, referenceDataPort)

    @Provides
    @Singleton
    fun batchEntryApplicationPort(
        referenceDataPort: ReferenceDataManagementPort,
        sessionRuntime: ActiveBookSessionRuntime,
    ): BatchEntryApplicationPort = SecureRoomBatchEntryApplicationPort(
        sessionRuntime,
        referenceDataPort,
    )

    @Provides
    @Singleton
    fun importFinancialApplicationPort(
        @ApplicationContext context: Context,
        keyProvider: DeviceLedgerKeyProvider,
        referenceDataPort: ReferenceDataManagementPort,
        stableIds: StableIdSource,
        sessionRuntime: ActiveBookSessionRuntime,
    ): ImportFinancialApplicationPort = SecureRoomImportFinancialApplicationPort(
        context,
        keyProvider,
        referenceDataPort,
        stableIds,
        databaseAccess = sessionRuntime,
    )

    @Provides
    @Singleton
    fun structuredImportApplicationPort(
        @ApplicationContext context: Context,
        keyProvider: DeviceLedgerKeyProvider,
        sessionRuntime: ActiveBookSessionRuntime,
    ): StructuredImportApplicationPort = SecureRoomStructuredImportApplicationPort(context, keyProvider, sessionRuntime)

    @Provides
    @Singleton
    fun refundApplicationPort(
        referenceDataPort: ReferenceDataManagementPort,
        sessionRuntime: ActiveBookSessionRuntime,
    ): RefundApplicationPort = SecureRoomRefundApplicationPort(
        sessionRuntime,
        referenceDataPort,
    )

    @Provides
    @Singleton
    fun specializedTransactionEntryPort(
        referenceDataPort: ReferenceDataManagementPort,
        runtimeSources: AppRuntimeSources,
        sessionRuntime: ActiveBookSessionRuntime,
    ): SpecializedTransactionEntryPort = SecureRoomSpecializedTransactionEntryPort.production(
        sessionRuntime,
        referenceDataPort,
        instantSource = { runtimeSources.clock.now() },
    )

    @Provides
    @Singleton
    fun bookAttachmentObjectPort(
        @ApplicationContext context: Context,
        keyProvider: DeviceLedgerKeyProvider,
        runtimeSources: AppRuntimeSources,
        sessionRuntime: ActiveBookSessionRuntime,
    ): SecureBookAttachmentObjectPort = SecureBookAttachmentObjectPort(
        context,
        keyProvider,
        runtimeSources.stableIds,
        InjectedJavaClock(runtimeSources.clock),
        runtimeSources.cryptographicRandom,
        sessionRuntime,
    )
}
