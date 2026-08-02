package app.ledger.app

import android.content.Context
import app.ledger.core.common.StableId
import app.ledger.core.common.StableIdSource
import app.ledger.core.common.getOrNull
import app.ledger.core.security.AndroidKeystoreKeys
import app.ledger.core.security.CryptographicRandomSource
import app.ledger.core.security.DeviceKeyHierarchy
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.core.security.PlatformCryptographicRandomSource
import app.ledger.core.security.SecurityEnvelopeStore
import app.ledger.core.time.JavaTimeLedgerClock
import app.ledger.core.time.LedgerClock
import app.ledger.finance.application.LedgerInitializationPort
import app.ledger.finance.data.SecureRoomLedgerInitializationPort
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
}
