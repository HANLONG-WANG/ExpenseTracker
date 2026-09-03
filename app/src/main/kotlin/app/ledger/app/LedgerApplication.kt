package app.ledger.app

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.StrictMode
import app.ledger.core.security.ActiveBookSessionRuntime
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
import app.ledger.finance.application.LedgerRevisionCacheControl
import app.ledger.finance.application.WidgetSnapshotApplicationPort
import app.ledger.widget.LedgerWidgetRuntime
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@HiltAndroidApp
class LedgerApplication : Application() {
    @Inject internal lateinit var settingsRepository: AppSettingsRepository

    @Inject internal lateinit var keyProvider: DeviceLedgerKeyProvider

    @Inject internal lateinit var runtimeSources: AppRuntimeSources

    @Inject internal lateinit var sessionRuntime: ActiveBookSessionRuntime

    @Inject internal lateinit var widgetSnapshots: WidgetSnapshotApplicationPort

    @Inject internal lateinit var revisionCacheControl: LedgerRevisionCacheControl
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var financialCommitRegistration: AutoCloseable? = null
    private lateinit var privacyDiagnostics: PrivacyDiagnosticManager
    private lateinit var automaticBackupScheduler: AutomaticBackupScheduler
    private val firstInteractiveContentGate = FirstInteractiveContentGate()
    private val financialDependentWorkMutex = Mutex()
    private val financialDependentJobsLock = Any()
    private val financialDependentJobs = mutableSetOf<Job>()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        AcraPrivacyInstaller.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        if (!LedgerApplicationProcessPolicy.shouldInitialize(Application.getProcessName(), packageName)) return
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
        LedgerWidgetRuntime.install(
            snapshots = widgetSnapshots,
            configurations = AppWidgetConfigurationRepository(settingsRepository),
            languageTag = {
                settingsRepository.current().languageTag.ifBlank { java.util.Locale.getDefault().toLanguageTag() }
            },
            dateFormat = { settingsRepository.current().dateFormat.name },
            localDate = {
                val configured = settingsRepository.current().zoneId.takeIf(String::isNotBlank)
                val zone = runCatching { ZoneId.of(configured ?: "UTC") }.getOrDefault(ZoneId.of("UTC"))
                runtimeSources.clock.now().atZone(zone).toLocalDate()
            },
        )
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
                    if (settings.diagnosticsChoiceRecorded) settings.telemetryEnabled else false,
                    if (settings.diagnosticsChoiceRecorded) settings.crashReportingEnabled else false,
                )
            }
        }
        automaticBackupScheduler = AutomaticBackupScheduler(this, settingsRepository, keyProvider, runtimeSources, sessionRuntime)
        financialCommitRegistration = FinancialCommitObserverRegistry.register(
            FinancialCommitObserver { change ->
                revisionCacheControl.committed(change)
                launchFinancialDependentWork()
            },
        )
        TrashAutoPurgeScheduler.ensureScheduled(this)
    }

    /** Starts optional headless work only after authoritative content for the initial route is visible. */
    internal fun onFirstInteractiveContent(interactiveContentWork: suspend () -> Unit) {
        if (!firstInteractiveContentGate.enter()) return
        applicationScope.launch {
            try {
                interactiveContentWork()
                awaitFinancialDependentWork()
                runFinancialDependentWork()
                awaitFinancialDependentWork()
            } finally {
                firstInteractiveContentGate.complete()
            }
        }
    }

    private fun launchFinancialDependentWork() {
        lateinit var job: Job
        job = applicationScope.launch(start = CoroutineStart.LAZY) {
            runFinancialDependentWork()
        }
        synchronized(financialDependentJobsLock) { financialDependentJobs += job }
        job.invokeOnCompletion {
            synchronized(financialDependentJobsLock) { financialDependentJobs -= job }
        }
        job.start()
    }

    private suspend fun awaitFinancialDependentWork() {
        while (true) {
            val active = synchronized(financialDependentJobsLock) { financialDependentJobs.toList() }
            if (active.isEmpty()) return
            active.forEach { job -> job.join() }
        }
    }

    private suspend fun runFinancialDependentWork() = financialDependentWorkMutex.withLock {
        automaticBackupScheduler.scheduleIfDue()
        LedgerWidgetRuntime.updateAll(this@LedgerApplication)
    }

    internal suspend fun awaitFirstInteractiveContentWork() {
        firstInteractiveContentGate.awaitCompletion()
    }

    internal fun hasStartedFirstInteractiveContentWork(): Boolean = firstInteractiveContentGate.hasEntered()

    override fun onTerminate() {
        financialCommitRegistration?.close()
        applicationScope.cancel()
        super.onTerminate()
    }
}

internal object LedgerApplicationProcessPolicy {
    fun shouldInitialize(processName: String?, packageName: String): Boolean = processName == packageName
}

internal class FirstInteractiveContentGate {
    private val entered = AtomicBoolean(false)
    private val completion = CompletableDeferred<Unit>()

    fun enter(): Boolean = entered.compareAndSet(false, true)

    fun hasEntered(): Boolean = entered.get()

    fun complete() {
        completion.complete(Unit)
    }

    suspend fun awaitCompletion() {
        completion.await()
    }

    internal fun isCompletedForTest(): Boolean = completion.isCompleted
}
