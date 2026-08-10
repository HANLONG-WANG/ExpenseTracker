@file:Suppress("TooManyFunctions")

package app.ledger.core.telemetry

import android.content.Context
import java.io.File

public enum class SendDisposition { SENT, RETRY, DROPPED_BY_POLICY }

public interface WhitelistedDiagnosticSender {
    public fun sendFeature(identifier: String, entry: FeatureQueueEntry): SendDisposition
    public fun sendCrash(identifier: String, entry: CrashQueueEntry): SendDisposition
}

public data class DiagnosticSendResult(
    val featureSent: Int,
    val crashSent: Int,
    val droppedByPolicy: Int,
    val retryRequired: Boolean,
)

public data class PrivacyDiagnosticRuntime(
    val appVersion: String,
    val androidMajor: Int,
    val deviceCategory: DeviceCapabilityCategory,
)

public class PrivacyDiagnosticManager private constructor(
    private val store: PrivacyDiagnosticStore,
    private val nowEpochMillis: () -> Long,
    private val randomBytes: (ByteArray) -> Unit,
    private val runtime: PrivacyDiagnosticRuntime,
    private val sender: WhitelistedDiagnosticSender? = null,
) {
    private var consent = store.readConsent()

    public constructor(
        context: Context,
        nowEpochMillis: () -> Long,
        randomBytes: (ByteArray) -> Unit,
        runtime: PrivacyDiagnosticRuntime,
        sender: WhitelistedDiagnosticSender? = null,
    ) : this(
        PrivacyDiagnosticStore(context.noBackupFilesDir.resolve(PrivacyDiagnosticStore.ROOT_NAME)),
        nowEpochMillis,
        randomBytes,
        runtime,
        sender,
    )

    internal constructor(
        noBackupRoot: File,
        nowEpochMillis: () -> Long,
        randomBytes: (ByteArray) -> Unit,
        runtime: PrivacyDiagnosticRuntime,
        sender: WhitelistedDiagnosticSender? = null,
    ) : this(
        PrivacyDiagnosticStore(noBackupRoot.resolve(PrivacyDiagnosticStore.ROOT_NAME)),
        nowEpochMillis,
        randomBytes,
        runtime,
        sender,
    )

    init {
        require(APP_VERSION.matches(runtime.appVersion))
        require(runtime.androidMajor in MINIMUM_ANDROID_MAJOR..MAXIMUM_ANDROID_MAJOR)
    }

    @Synchronized
    public fun applyConsent(privacyAccepted: Boolean, featureEnabled: Boolean, crashEnabled: Boolean) {
        val now = nowEpochMillis().coerceAtLeast(0L)
        if (!privacyAccepted) {
            store.deleteAll()
            consent = DiagnosticConsentState()
            store.writeConsent(consent)
            return
        }
        if (!featureEnabled) store.deleteFeatureData()
        if (!crashEnabled) store.deleteCrashData()
        consent = DiagnosticConsentState(
            privacyAccepted = true,
            featureEnabled = featureEnabled,
            crashEnabled = crashEnabled,
            featureEnabledAtEpochMillis = enabledAt(consent.featureEnabled, featureEnabled, consent.featureEnabledAtEpochMillis, now),
            crashEnabledAtEpochMillis = enabledAt(consent.crashEnabled, crashEnabled, consent.crashEnabledAtEpochMillis, now),
            lastExitCollectedAtEpochMillis = if (crashEnabled) consent.lastExitCollectedAtEpochMillis else 0L,
        )
        store.writeConsent(consent)
        if (featureEnabled) ensureFeatureIdentifier(now)
        if (crashEnabled) ensureCrashIdentifier(now)
        prune(now)
    }

    @Synchronized
    public fun record(event: FeatureDiagnosticEvent) {
        if (!consent.privacyAccepted || !consent.featureEnabled) return
        val now = nowEpochMillis().coerceAtLeast(0L)
        ensureFeatureIdentifier(now)
        val retained = store.readFeatureEvents().filter { now - it.occurredAtEpochMillis <= FEATURE_RETENTION_MILLIS }
        store.writeFeatureEvents((retained + FeatureQueueEntry(now, event)).takeLast(PrivacyDiagnosticStore.MAXIMUM_QUEUE_ENTRIES))
    }

    @Synchronized
    public fun recordCrash(diagnostic: SanitizedCrashDiagnostic, occurredAtEpochMillis: Long = nowEpochMillis()) {
        if (!consent.privacyAccepted || !consent.crashEnabled) return
        if (occurredAtEpochMillis < consent.crashEnabledAtEpochMillis) return
        val now = nowEpochMillis().coerceAtLeast(0L)
        ensureCrashIdentifier(now)
        val retained = store.readCrashEvents().filter { now - it.occurredAtEpochMillis <= CRASH_RETENTION_MILLIS }
        val event = CrashQueueEntry(occurredAtEpochMillis.coerceAtLeast(0L), diagnostic)
        store.writeCrashEvents((retained + event).takeLast(PrivacyDiagnosticStore.MAXIMUM_QUEUE_ENTRIES))
    }

    @Synchronized
    public fun snapshot(): DiagnosticQueueSnapshot {
        val now = nowEpochMillis().coerceAtLeast(0L)
        prune(now)
        return DiagnosticQueueSnapshot(
            privacyAccepted = consent.privacyAccepted,
            featureEnabled = consent.featureEnabled,
            crashEnabled = consent.crashEnabled,
            featureIdentifierPresent = store.readFeatureIdentifier() != null,
            crashIdentifierPresent = store.readCrashIdentifier() != null,
            featureEvents = store.readFeatureEvents(),
            crashEvents = store.readCrashEvents(),
        )
    }

    @Synchronized
    public fun deleteFeatureQueue() = store.writeFeatureEvents(emptyList())

    @Synchronized
    public fun deleteCrashQueue() = store.writeCrashEvents(emptyList())

    @Synchronized
    public fun deleteAllLocal() {
        store.deleteAll()
        consent = DiagnosticConsentState()
        store.writeConsent(consent)
    }

    @Synchronized
    public fun sendPending(): DiagnosticSendResult = when {
        sender == null -> DiagnosticSendResult(
            featureSent = 0,
            crashSent = 0,
            droppedByPolicy = 0,
            retryRequired = store.readFeatureEvents().isNotEmpty() || store.readCrashEvents().isNotEmpty(),
        )
        !consent.privacyAccepted -> DiagnosticSendResult(0, 0, 0, retryRequired = false)
        else -> sendPending(sender)
    }

    private fun sendPending(activeSender: WhitelistedDiagnosticSender): DiagnosticSendResult {
        val now = nowEpochMillis().coerceAtLeast(0L)
        prune(now)
        var featureSent = 0
        var crashSent = 0
        var droppedByPolicy = 0
        var retry = false
        if (consent.featureEnabled) {
            val id = ensureFeatureIdentifier(now).encodeHex()
            val remaining = store.readFeatureEvents().filter { entry ->
                when (activeSender.sendFeature(id, entry)) {
                    SendDisposition.SENT -> false.also { featureSent++ }
                    SendDisposition.RETRY -> true.also { retry = true }
                    SendDisposition.DROPPED_BY_POLICY -> false.also { droppedByPolicy++ }
                }
            }
            store.writeFeatureEvents(remaining)
        }
        if (consent.crashEnabled) {
            val id = ensureCrashIdentifier(now).encodeHex()
            val remaining = store.readCrashEvents().filter { entry ->
                when (activeSender.sendCrash(id, entry)) {
                    SendDisposition.SENT -> false.also { crashSent++ }
                    SendDisposition.RETRY -> true.also { retry = true }
                    SendDisposition.DROPPED_BY_POLICY -> false.also { droppedByPolicy++ }
                }
            }
            store.writeCrashEvents(remaining)
        }
        return DiagnosticSendResult(featureSent, crashSent, droppedByPolicy, retry)
    }

    internal fun crashCollectionFloorMillis(): Long = synchronized(this) {
        if (consent.privacyAccepted && consent.crashEnabled) {
            maxOf(consent.crashEnabledAtEpochMillis, consent.lastExitCollectedAtEpochMillis + 1L)
        } else {
            Long.MAX_VALUE
        }
    }

    internal fun markExitCollected(timestamp: Long) = synchronized(this) {
        if (timestamp > consent.lastExitCollectedAtEpochMillis) {
            consent = consent.copy(lastExitCollectedAtEpochMillis = timestamp)
            store.writeConsent(consent)
        }
    }

    internal fun runtimeMetadata(): RuntimeDiagnosticMetadata = RuntimeDiagnosticMetadata(
        runtime.appVersion,
        runtime.androidMajor,
        runtime.deviceCategory,
    )

    private fun enabledAt(wasEnabled: Boolean, enabled: Boolean, oldValue: Long, now: Long): Long = when {
        !enabled -> 0L
        wasEnabled && oldValue > 0L -> oldValue
        else -> now
    }

    private fun ensureFeatureIdentifier(now: Long): RotatingInstallId {
        val current = store.readFeatureIdentifier()
        if (current != null && now - current.createdAtEpochMillis < IDENTIFIER_ROTATION_MILLIS) return current
        return newIdentifier(now).also(store::writeFeatureIdentifier)
    }

    private fun ensureCrashIdentifier(now: Long): RotatingInstallId {
        val current = store.readCrashIdentifier()
        if (current != null && now - current.createdAtEpochMillis < IDENTIFIER_ROTATION_MILLIS) return current
        return newIdentifier(now).also(store::writeCrashIdentifier)
    }

    private fun newIdentifier(now: Long): RotatingInstallId {
        val bytes = ByteArray(RotatingInstallId.BYTE_COUNT).also(randomBytes)
        require(bytes.any { it.toInt() != 0 })
        return RotatingInstallId(bytes, now).also { bytes.fill(0) }
    }

    private fun prune(now: Long) {
        store.writeFeatureEvents(store.readFeatureEvents().filter { now - it.occurredAtEpochMillis <= FEATURE_RETENTION_MILLIS })
        store.writeCrashEvents(store.readCrashEvents().filter { now - it.occurredAtEpochMillis <= CRASH_RETENTION_MILLIS })
    }

    private companion object {
        val APP_VERSION = Regex("[A-Za-z0-9._+-]{1,64}")
        const val MINIMUM_ANDROID_MAJOR = 1
        const val MAXIMUM_ANDROID_MAJOR = 100
        const val DAY_MILLIS = 86_400_000L
        const val IDENTIFIER_ROTATION_MILLIS = 30L * DAY_MILLIS
        const val FEATURE_RETENTION_MILLIS = 90L * DAY_MILLIS
        const val CRASH_RETENTION_MILLIS = 180L * DAY_MILLIS
    }
}

internal data class RuntimeDiagnosticMetadata(
    val appVersion: String,
    val androidMajor: Int,
    val deviceCategory: DeviceCapabilityCategory,
)

public class StructuredDiagnosticLog(
    private val releaseMode: Boolean,
    private val nowEpochMillis: () -> Long,
    private val capacity: Int = 128,
) {
    private val entries = ArrayDeque<StructuredDiagnosticEntry>()

    init {
        require(capacity in MINIMUM_CAPACITY..MAXIMUM_CAPACITY)
    }

    @Synchronized
    public fun record(
        severity: DiagnosticSeverity,
        phase: DiagnosticPhase,
        code: DiagnosticCode,
        debugMetadata: DebugDiagnosticMetadata? = null,
    ) {
        if (entries.size == capacity) entries.removeFirst()
        entries.addLast(
            StructuredDiagnosticEntry(
                nowEpochMillis().coerceAtLeast(0L),
                severity,
                phase,
                code,
                debugMetadata.takeUnless { releaseMode },
            ),
        )
    }

    @Synchronized
    public fun snapshot(): List<StructuredDiagnosticEntry> = entries.toList()

    @Synchronized
    public fun clear() = entries.clear()

    private companion object {
        const val MINIMUM_CAPACITY = 1
        const val MAXIMUM_CAPACITY = 1_024
    }
}
