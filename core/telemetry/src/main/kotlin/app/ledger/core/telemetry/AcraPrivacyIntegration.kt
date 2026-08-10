@file:Suppress("TooGenericExceptionCaught", "SwallowedException")

package app.ledger.core.telemetry

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import org.acra.ACRA
import org.acra.ReportField
import org.acra.config.CoreConfiguration
import org.acra.config.CoreConfigurationBuilder
import org.acra.data.CrashReportData
import org.acra.data.StringFormat
import org.acra.sender.ReportSender
import org.acra.sender.ReportSenderFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicReference

public object TelemetryRuntime {
    private val manager = AtomicReference<PrivacyDiagnosticManager?>()

    public fun install(value: PrivacyDiagnosticManager) {
        check(manager.compareAndSet(null, value) || manager.get() === value)
    }

    public fun clear() {
        manager.getAndSet(null)?.deleteAllLocal()
    }

    public fun deleteAllLocal() {
        manager.get()?.deleteAllLocal()
    }

    public fun record(event: FeatureDiagnosticEvent) {
        manager.get()?.record(event)
    }

    public fun applyConsent(privacyAccepted: Boolean, featureEnabled: Boolean, crashEnabled: Boolean) {
        manager.get()?.applyConsent(privacyAccepted, featureEnabled, crashEnabled)
    }

    public fun snapshot(): DiagnosticQueueSnapshot? = manager.get()?.snapshot()

    public fun deleteFeatureQueue() = manager.get()?.deleteFeatureQueue()
    public fun deleteCrashQueue() = manager.get()?.deleteCrashQueue()
    public fun sendPending(): DiagnosticSendResult? = manager.get()?.sendPending()

    internal fun current(): PrivacyDiagnosticManager? = manager.get()
}

public object AcraPrivacyInstaller {
    public fun install(application: Application) {
        if (!ACRA.isInitialised) {
            ACRA.DEV_LOGGING = false
            val configuration = CoreConfigurationBuilder()
                .withReportContent(
                    ReportField.ANDROID_VERSION,
                    ReportField.STACK_TRACE,
                )
                .withReportFormat(StringFormat.JSON)
                .withAlsoReportToAndroidFramework(false)
                .withDeleteUnapprovedReportsOnApplicationStart(true)
                .withSendReportsInDevMode(true)
                .withParallel(false)
            ACRA.init(application, configuration)
        }
        val acraHandler = Thread.getDefaultUncaughtExceptionHandler()
        if (acraHandler !is SanitizingUncaughtExceptionHandler) {
            Thread.setDefaultUncaughtExceptionHandler(SanitizingUncaughtExceptionHandler(acraHandler))
        }
    }
}

internal class SanitizingUncaughtExceptionHandler(
    private val delegate: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        val sanitized = SanitizedAcraThrowable(PrivacyCrashSanitizer.fromThrowable(error))
        delegate?.uncaughtException(thread, sanitized)
    }
}

private class SanitizedAcraThrowable(diagnostic: SanitizedCrashDiagnostic) :
    RuntimeException(
        SanitizedErrorCode.UNCAUGHT_EXCEPTION.name,
        null,
        false,
        true,
    ) {
    init {
        stackTrace = diagnostic.frames.map { frame ->
            StackTraceElement(frame.className, frame.methodName, null, frame.lineNumber)
        }.toTypedArray()
    }
}

public object PrivacyCrashSanitizer {
    public fun fromThrowable(error: Throwable): SanitizedCrashDiagnostic = SanitizedCrashDiagnostic(
        CrashKind.JAVA_UNCAUGHT,
        SanitizedErrorCode.UNCAUGHT_EXCEPTION,
        error.stackTrace.asSequence()
            .mapNotNull { frame -> SanitizedStackFrame.create(frame.className, frame.methodName, frame.lineNumber) }
            .take(SanitizedCrashDiagnostic.MAXIMUM_STACK_FRAMES)
            .toList(),
    )

    internal fun fromAcraStack(stack: String): SanitizedCrashDiagnostic = SanitizedCrashDiagnostic(
        CrashKind.JAVA_UNCAUGHT,
        SanitizedErrorCode.UNCAUGHT_EXCEPTION,
        stack.lineSequence()
            .mapNotNull(::parseFrame)
            .take(SanitizedCrashDiagnostic.MAXIMUM_STACK_FRAMES)
            .toList(),
    )

    internal fun fromTrace(reader: BufferedReader, kind: CrashKind, error: SanitizedErrorCode): SanitizedCrashDiagnostic {
        val frames = buildList {
            var lines = 0
            while (size < SanitizedCrashDiagnostic.MAXIMUM_STACK_FRAMES && lines++ < MAXIMUM_TRACE_LINES) {
                val line = reader.readLine() ?: break
                parseFrame(line)?.let(::add)
            }
        }
        return SanitizedCrashDiagnostic(kind, error, frames)
    }

    private fun parseFrame(line: String): SanitizedStackFrame? {
        val match = FRAME.matchEntire(line.trim()) ?: return null
        return SanitizedStackFrame.create(
            match.groupValues[1],
            match.groupValues[2],
            match.groupValues[LINE_NUMBER_GROUP].toIntOrNull() ?: UNKNOWN_LINE_NUMBER,
        )
    }

    private val FRAME = Regex("at ([A-Za-z0-9_.$<>-]{1,240})\\.([A-Za-z0-9_$<>-]{1,240})\\([^():]*(?::([0-9]{1,8}))?\\)")
    private const val LINE_NUMBER_GROUP = 3
    private const val UNKNOWN_LINE_NUMBER = -1
    private const val MAXIMUM_TRACE_LINES = 512
}

/** ACRA plugin that accepts only the configured stack field and forwards a typed sanitized report. */
public class WhitelistedAcraReportSenderFactory : ReportSenderFactory {
    override fun create(context: Context, config: CoreConfiguration): ReportSender = WhitelistedAcraReportSender
}

private object WhitelistedAcraReportSender : ReportSender {
    override fun send(context: Context, errorContent: CrashReportData) {
        val active = TelemetryRuntime.current() ?: return
        val stack = runCatching { errorContent.getString(ReportField.STACK_TRACE) }.getOrNull().orEmpty()
        active.recordCrash(PrivacyCrashSanitizer.fromAcraStack(stack))
    }
}

public class ApplicationExitDiagnosticCollector(private val context: Context) {
    public fun collect() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val manager = TelemetryRuntime.current() ?: return
        collect(manager)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun collect(manager: PrivacyDiagnosticManager) {
        val floor = manager.crashCollectionFloorMillis()
        if (floor == Long.MAX_VALUE) return
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        var newest = floor - 1L
        activityManager.getHistoricalProcessExitReasons(context.packageName, 0, MAXIMUM_EXIT_RECORDS)
            .asSequence()
            .filter { it.timestamp >= floor }
            .sortedBy(ApplicationExitInfo::getTimestamp)
            .forEach { info ->
                val classification = classify(info.reason)
                val diagnostic = info.traceInputStream?.use { stream ->
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                        PrivacyCrashSanitizer.fromTrace(reader, classification.kind, classification.error)
                    }
                } ?: SanitizedCrashDiagnostic(classification.kind, classification.error, emptyList())
                manager.recordCrash(diagnostic, info.timestamp)
                newest = maxOf(newest, info.timestamp)
            }
        if (newest >= floor) manager.markExitCollected(newest)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun classify(reason: Int): ExitClassification = when (reason) {
        ApplicationExitInfo.REASON_ANR -> ExitClassification(
            CrashKind.APPLICATION_NOT_RESPONDING,
            SanitizedErrorCode.APPLICATION_NOT_RESPONDING,
        )
        ApplicationExitInfo.REASON_CRASH_NATIVE -> ExitClassification(CrashKind.NATIVE_CRASH, SanitizedErrorCode.NATIVE_CRASH)
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> ExitClassification(
            CrashKind.EXCESSIVE_RESOURCE_USE,
            SanitizedErrorCode.EXCESSIVE_RESOURCE_USE,
        )
        else -> ExitClassification(CrashKind.SYSTEM_KILL, SanitizedErrorCode.SYSTEM_PROCESS_EXIT)
    }

    private data class ExitClassification(val kind: CrashKind, val error: SanitizedErrorCode)

    private companion object {
        const val MAXIMUM_EXIT_RECORDS = 32
    }
}
