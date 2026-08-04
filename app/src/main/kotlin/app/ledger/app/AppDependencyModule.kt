@file:Suppress("TooManyFunctions")

package app.ledger.app

import android.content.Context
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
import app.ledger.finance.application.BookAttachmentObjectPort
import app.ledger.finance.application.BudgetApplicationPort
import app.ledger.finance.application.JournalApplicationPort
import app.ledger.finance.application.LedgerInitializationPort
import app.ledger.finance.application.OpeningBalanceWritePort
import app.ledger.finance.application.OrdinaryTransactionEntryPort
import app.ledger.finance.application.ReferenceDataManagementPort
import app.ledger.finance.application.RefundApplicationPort
import app.ledger.finance.application.SpecializedTransactionEntryPort
import app.ledger.finance.data.SecureRoomBudgetApplicationPort
import app.ledger.finance.data.SecureRoomJournalApplicationPort
import app.ledger.finance.data.SecureRoomLedgerInitializationPort
import app.ledger.finance.data.SecureRoomOpeningBalanceWritePort
import app.ledger.finance.data.SecureRoomOrdinaryTransactionEntryPort
import app.ledger.finance.data.SecureRoomReferenceDataManagementPort
import app.ledger.finance.data.SecureRoomRefundApplicationPort
import app.ledger.finance.data.SecureRoomSpecializedTransactionEntryPort
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
    fun journalApplicationPort(
        @ApplicationContext context: Context,
        keyProvider: DeviceLedgerKeyProvider,
    ): JournalApplicationPort = SecureRoomJournalApplicationPort(context, keyProvider)

    @Provides
    @Singleton
    fun budgetApplicationPort(
        @ApplicationContext context: Context,
        keyProvider: DeviceLedgerKeyProvider,
    ): BudgetApplicationPort = SecureRoomBudgetApplicationPort(context, keyProvider)

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
    ): BookAttachmentObjectPort = SecureBookAttachmentObjectPort(
        context,
        keyProvider,
        runtimeSources.stableIds,
        InjectedJavaClock(runtimeSources.clock),
        runtimeSources.cryptographicRandom,
    )
}
