package app.ledger.app

import android.app.Application
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.finance.application.FinancialCommitObserver
import app.ledger.finance.application.FinancialCommitObserverRegistry
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class LedgerApplication : Application() {
    @Inject internal lateinit var settingsRepository: AppSettingsRepository

    @Inject internal lateinit var keyProvider: DeviceLedgerKeyProvider

    @Inject internal lateinit var runtimeSources: AppRuntimeSources
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var financialCommitRegistration: AutoCloseable? = null

    override fun onCreate() {
        super.onCreate()
        val scheduler = AutomaticBackupScheduler(this, settingsRepository, keyProvider, runtimeSources)
        financialCommitRegistration = FinancialCommitObserverRegistry.register(
            FinancialCommitObserver { applicationScope.launch { scheduler.scheduleIfDue() } },
        )
        applicationScope.launch { scheduler.scheduleIfDue() }
    }

    override fun onTerminate() {
        financialCommitRegistration?.close()
        applicationScope.cancel()
        super.onTerminate()
    }
}
