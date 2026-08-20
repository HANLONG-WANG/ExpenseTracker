@file:Suppress("TooManyFunctions")

package app.ledger.app

import android.content.Context
import app.ledger.analytics.data.SecureRoomAnalyticsApplicationPort
import app.ledger.analytics.domain.AnalyticsApplicationPort
import app.ledger.core.common.StableId
import app.ledger.core.common.StableIdSource
import app.ledger.core.common.getOrNull
import app.ledger.core.files.SecureBookAttachmentObjectPort
import app.ledger.core.security.AndroidKeystoreKeys
import app.ledger.core.security.CryptographicRandomSource
import app.ledger.core.security.DeviceKeyHierarchy
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.core.security.PlatformCryptographicRandomSource
import app.ledger.core.security.SecurityEnvelopeStore
import app.ledger.core.time.InjectedJavaClock
import app.ledger.core.time.JavaTimeLedgerClock
import app.ledger.core.time.LedgerClock
import app.ledger.finance.application.AutomationApplicationPort
import app.ledger.finance.application.BatchEntryApplicationPort
import app.ledger.finance.application.BudgetApplicationPort
import app.ledger.finance.application.CreditApplicationPort
import app.ledger.finance.application.FormalOccurrenceGenerator
import app.ledger.finance.application.ImportFinancialApplicationPort
import app.ledger.finance.application.InstallmentApplicationPort
import app.ledger.finance.application.JournalApplicationPort
import app.ledger.finance.application.LedgerExportQueryPort
import app.ledger.finance.application.LedgerInitializationPort
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
import app.ledger.finance.data.SecureRoomAutomationApplicationPort
import app.ledger.finance.data.SecureRoomBatchEntryApplicationPort
import app.ledger.finance.data.SecureRoomBudgetApplicationPort
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
    fun referenceDataPort(
        @ApplicationContext context: Context,
        keyProvider: DeviceLedgerKeyProvider,
    ): ReferenceDataManagementPort = SecureRoomReferenceDataManagementPort(context, keyProvider)

    @Provides
    @Singleton
    fun vaultSecretApplicationPort(
        @ApplicationContext context: Context,
        keyProvider: DeviceLedgerKeyProvider,
    ): VaultSecretApplicationPort = SecureRoomVaultSecretApplicationPort(context, keyProvider)

    @Provides
    @Singleton
    fun journalApplicationPort(
        @ApplicationContext context: Context,
        keyProvider: DeviceLedgerKeyProvider,
    ): JournalApplicationPort = SecureRoomJournalApplicationPort(context, keyProvider)

    @Provides
    @Singleton
    fun ledgerExportQueryPort(
        @ApplicationContext context: Context,
        keyProvider: DeviceLedgerKeyProvider,
    ): LedgerExportQueryPort = SecureRoomLedgerExportQueryPort(context, keyProvider)

    @Provides
    @Singleton
    fun widgetSnapshotApplicationPort(
        @ApplicationContext context: Context,
        keyProvider: DeviceLedgerKeyProvider,
    ): WidgetSnapshotApplicationPort = SecureRoomWidgetSnapshotApplicationPort(context, keyProvider)

    @Provides
    @Singleton
    fun widgetSnapshotRefreshApplicationPort(
        @ApplicationContext context: Context,
        keyProvider: DeviceLedgerKeyProvider,
    ): WidgetSnapshotRefreshApplicationPort = SecureRoomWidgetSnapshotApplicationPort(context, keyProvider)

    @Provides
    @Singleton
    fun budgetApplicationPort(
        @ApplicationContext context: Context,
        keyProvider: DeviceLedgerKeyProvider,
    ): BudgetApplicationPort = SecureRoomBudgetApplicationPort(context, keyProvider)

    @Provides
    @Singleton
    fun projectGoalApplicationPort(
        @ApplicationContext context: Context,
        keyProvider: DeviceLedgerKeyProvider,
        referenceDataPort: ReferenceDataManagementPort,
    ): ProjectGoalApplicationPort = SecureRoomProjectGoalApplicationPort(context, keyProvider, referenceDataPort)

    @Provides
    @Singleton
    fun creditApplicationPort(
        @ApplicationContext context: Context,
        keyProvider: DeviceLedgerKeyProvider,
    ): CreditApplicationPort = SecureRoomCreditApplicationPort(context, keyProvider)

    @Provides
    @Singleton
    fun installmentApplicationPort(
        @ApplicationContext context: Context,
        keyProvider: DeviceLedgerKeyProvider,
    ): InstallmentApplicationPort = SecureRoomInstallmentApplicationPort(context, keyProvider)

    @Provides
    @Singleton
    fun loanApplicationPort(
        @ApplicationContext context: Context,
        keyProvider: DeviceLedgerKeyProvider,
    ): LoanApplicationPort = SecureRoomLoanApplicationPort(context, keyProvider)

    @Provides
    @Singleton
    fun settlementApplicationPort(
        @ApplicationContext context: Context,
        keyProvider: DeviceLedgerKeyProvider,
    ): SettlementApplicationPort = SecureRoomSettlementApplicationPort(context, keyProvider)

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
        @ApplicationContext context: Context,
        keyProvider: DeviceLedgerKeyProvider,
        generator: FormalOccurrenceGenerator,
    ): AutomationApplicationPort = SecureRoomAutomationApplicationPort(context, keyProvider, generator)

    @Provides
    @Singleton
    fun analyticsApplicationPort(
        @ApplicationContext context: Context,
        keyProvider: DeviceLedgerKeyProvider,
        runtime: AppRuntimeSources,
    ): AnalyticsApplicationPort = SecureRoomAnalyticsApplicationPort(
        context,
        { bookId ->
            keyProvider.open(bookId).use { keys ->
                keys.databaseDek.useBytes(ByteArray::copyOf)
            }
        },
        runtime.stableIds,
        runtime.clock,
    )

    @Provides
    @Singleton
    fun headlessRecurrenceExecutor(
        @ApplicationContext context: Context,
        keyProvider: DeviceLedgerKeyProvider,
        automation: AutomationApplicationPort,
    ): HeadlessRecurrenceExecutor = AppHeadlessRecurrenceExecutor(context, keyProvider, automation)

    @Provides
    @Singleton
    fun openingBalanceWritePort(
        @ApplicationContext context: Context,
        keyProvider: DeviceLedgerKeyProvider,
    ): OpeningBalanceWritePort = SecureRoomOpeningBalanceWritePort(context, keyProvider)

    @Provides
    @Singleton
    fun ordinaryTransactionEntryPort(
        @ApplicationContext context: Context,
        keyProvider: DeviceLedgerKeyProvider,
        referenceDataPort: ReferenceDataManagementPort,
    ): OrdinaryTransactionEntryPort = SecureRoomOrdinaryTransactionEntryPort(context, keyProvider, referenceDataPort)

    @Provides
    @Singleton
    fun batchEntryApplicationPort(
        @ApplicationContext context: Context,
        keyProvider: DeviceLedgerKeyProvider,
        referenceDataPort: ReferenceDataManagementPort,
    ): BatchEntryApplicationPort = SecureRoomBatchEntryApplicationPort(context, keyProvider, referenceDataPort)

    @Provides
    @Singleton
    fun importFinancialApplicationPort(
        @ApplicationContext context: Context,
        keyProvider: DeviceLedgerKeyProvider,
        referenceDataPort: ReferenceDataManagementPort,
        stableIds: StableIdSource,
    ): ImportFinancialApplicationPort = SecureRoomImportFinancialApplicationPort(context, keyProvider, referenceDataPort, stableIds)

    @Provides
    @Singleton
    fun structuredImportApplicationPort(
        @ApplicationContext context: Context,
        keyProvider: DeviceLedgerKeyProvider,
    ): StructuredImportApplicationPort = SecureRoomStructuredImportApplicationPort(context, keyProvider)

    @Provides
    @Singleton
    fun refundApplicationPort(
        @ApplicationContext context: Context,
        keyProvider: DeviceLedgerKeyProvider,
        referenceDataPort: ReferenceDataManagementPort,
    ): RefundApplicationPort = SecureRoomRefundApplicationPort(context, keyProvider, referenceDataPort)

    @Provides
    @Singleton
    fun specializedTransactionEntryPort(
        @ApplicationContext context: Context,
        keyProvider: DeviceLedgerKeyProvider,
        referenceDataPort: ReferenceDataManagementPort,
        runtimeSources: AppRuntimeSources,
    ): SpecializedTransactionEntryPort = SecureRoomSpecializedTransactionEntryPort.production(
        context,
        keyProvider,
        referenceDataPort,
    ) { runtimeSources.clock.now() }

    @Provides
    @Singleton
    fun bookAttachmentObjectPort(
        @ApplicationContext context: Context,
        keyProvider: DeviceLedgerKeyProvider,
        runtimeSources: AppRuntimeSources,
    ): SecureBookAttachmentObjectPort = SecureBookAttachmentObjectPort(
        context,
        keyProvider,
        runtimeSources.stableIds,
        InjectedJavaClock(runtimeSources.clock),
        runtimeSources.cryptographicRandom,
    )
}
