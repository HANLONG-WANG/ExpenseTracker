package app.ledger.app

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.StrictMode
import app.ledger.core.security.AndroidKeystoreKeys
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.core.security.DeviceSecurityCapability
import app.ledger.core.telemetry.AcraPrivacyInstaller
import app.ledger.core.telemetry.ApplicationExitDiagnosticCollector
import app.ledger.core.telemetry.DeviceCapabilityCategory
import app.ledger.core.telemetry.PrivacyDiagnosticManager
import app.ledger.core.telemetry.PrivacyDiagnosticRuntime
import app.ledger.core.telemetry.TelemetryRuntime
import app.ledger.finance.application.FinancialCommitObserver
import app.ledger.finance.application.FinancialCommitObserverRegistry
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class LedgerApplication : Application() {
    @Inject internal lateinit var settingsRepository: AppSettingsRepository

    @Inject internal lateinit var keyProvider: DeviceLedgerKeyProvider

    @Inject internal lateinit var runtimeSources: AppRuntimeSources
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var financialCommitRegistration: AutoCloseable? = null
    private lateinit var privacyDiagnostics: PrivacyDiagnosticManager

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        AcraPrivacyInstaller.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        val capability = when (AndroidKeystoreKeys(this).deviceSecurityCapability()) {
            DeviceSecurityCapability.BIOMETRIC_OR_CREDENTIAL -> DeviceCapabilityCategory.STRONG_BIOMETRIC_OR_CREDENTIAL
            DeviceSecurityCapability.DEVICE_CREDENTIAL_ONLY -> DeviceCapabilityCategory.DEVICE_CREDENTIAL_ONLY
            DeviceSecurityCapability.MISSING_DEVICE_CREDENTIAL -> DeviceCapabilityCategory.DEVICE_SECURITY_MISSING
        }
        privacyDiagnostics = PrivacyDiagnosticManager(
            this,
            System::currentTimeMillis,
            runtimeSources.cryptographicRandom::nextBytes,
            PrivacyDiagnosticRuntime(
                packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown",
                Build.VERSION.SDK_INT,
                capability,
            ),
        )
        TelemetryRuntime.install(privacyDiagnostics)
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectActivityLeaks().penaltyLog().build(),
            )
        }
        ApplicationExitDiagnosticCollector(this).collect()
        applicationScope.launch {
            settingsRepository.data.collectLatest { settings ->
                privacyDiagnostics.applyConsent(
                    settings.privacyAccepted,
                    if (settings.diagnosticsChoiceRecorded) settings.telemetryEnabled else true,
                    if (settings.diagnosticsChoiceRecorded) settings.crashReportingEnabled else true,
                )
            }
        }
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
