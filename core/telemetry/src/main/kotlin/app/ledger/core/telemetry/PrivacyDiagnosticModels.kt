package app.ledger.core.telemetry

/** Closed event names are the only feature telemetry vocabulary. */
public enum class FeatureEventName {
    APPLICATION_STARTED,
    FEATURE_OPENED,
    USER_ACTION_COMPLETED,
    USER_ACTION_FAILED,
    SETTING_CHANGED,
}

public enum class FeatureEntry {
    APP_ROOT,
    VAULT,
    APP_LOCK_SETTINGS,
    SCREEN_PRIVACY_SETTINGS,
    PRIVACY_DIAGNOSTICS,
    LOCAL_CLEAR,
    CLOUD_BACKUP_DELETE,
}

public enum class DiagnosticOutcome { SUCCEEDED, FAILED, CANCELLED }

public enum class DurationBucket {
    NOT_MEASURED,
    UNDER_100_MS,
    UNDER_1_SECOND,
    UNDER_5_SECONDS,
    UNDER_30_SECONDS,
    THIRTY_SECONDS_OR_MORE,
}

public enum class SanitizedErrorCode {
    NONE,
    AUTHENTICATION_FAILED,
    AUTHENTICATION_CANCELLED,
    DEVICE_SECURITY_REQUIRED,
    STORAGE_UNAVAILABLE,
    NETWORK_UNAVAILABLE,
    SERVER_REJECTED,
    UNCAUGHT_EXCEPTION,
    APPLICATION_NOT_RESPONDING,
    NATIVE_CRASH,
    EXCESSIVE_RESOURCE_USE,
    SYSTEM_PROCESS_EXIT,
    UNKNOWN,
}

public enum class DeviceCapabilityCategory {
    STRONG_BIOMETRIC_OR_CREDENTIAL,
    DEVICE_CREDENTIAL_ONLY,
    DEVICE_SECURITY_MISSING,
    OTHER,
}

public data class FeatureDiagnosticEvent(
    val name: FeatureEventName,
    val entry: FeatureEntry,
    val outcome: DiagnosticOutcome,
    val duration: DurationBucket,
    val errorCode: SanitizedErrorCode = SanitizedErrorCode.NONE,
)

public enum class CrashKind {
    JAVA_UNCAUGHT,
    APPLICATION_NOT_RESPONDING,
    NATIVE_CRASH,
    EXCESSIVE_RESOURCE_USE,
    SYSTEM_KILL,
}

/** A compile-time stack frame. Runtime exception messages and arbitrary trace lines have no representation. */
@ConsistentCopyVisibility
public data class SanitizedStackFrame private constructor(
    val className: String,
    val methodName: String,
    val lineNumber: Int,
) {
    public companion object {
        private val SYMBOL = Regex("[A-Za-z0-9_.$<>-]{1,240}")

        public fun create(className: String, methodName: String, lineNumber: Int): SanitizedStackFrame? {
            if (!SYMBOL.matches(className) || !SYMBOL.matches(methodName)) return null
            return SanitizedStackFrame(className, methodName, lineNumber.coerceIn(UNKNOWN_LINE_NUMBER, MAXIMUM_LINE_NUMBER))
        }

        private const val UNKNOWN_LINE_NUMBER = -1
        private const val MAXIMUM_LINE_NUMBER = 10_000_000
    }
}

public data class SanitizedCrashDiagnostic(
    val kind: CrashKind,
    val errorCode: SanitizedErrorCode,
    val frames: List<SanitizedStackFrame>,
) {
    init {
        require(frames.size <= MAXIMUM_STACK_FRAMES)
    }

    public companion object {
        public const val MAXIMUM_STACK_FRAMES: Int = 64
    }
}

public data class FeatureQueueEntry(
    val occurredAtEpochMillis: Long,
    val event: FeatureDiagnosticEvent,
)

public data class CrashQueueEntry(
    val occurredAtEpochMillis: Long,
    val diagnostic: SanitizedCrashDiagnostic,
)

public data class DiagnosticQueueSnapshot(
    val privacyAccepted: Boolean,
    val featureEnabled: Boolean,
    val crashEnabled: Boolean,
    val featureIdentifierPresent: Boolean,
    val crashIdentifierPresent: Boolean,
    val featureEvents: List<FeatureQueueEntry>,
    val crashEvents: List<CrashQueueEntry>,
)

internal class RotatingInstallId(bytes: ByteArray, val createdAtEpochMillis: Long) {
    private val stored = bytes.copyOf()

    init {
        require(stored.size == BYTE_COUNT)
        require(createdAtEpochMillis >= 0L)
    }

    fun encodeHex(): String = buildString(BYTE_COUNT * 2) {
        stored.forEach { value -> append("%02x".format(value.toInt() and UNSIGNED_BYTE_MASK)) }
    }

    fun copyBytes(): ByteArray = stored.copyOf()

    override fun toString(): String = "RotatingInstallId(redacted,createdAt=$createdAtEpochMillis)"

    companion object {
        const val BYTE_COUNT: Int = 16
        private const val UNSIGNED_BYTE_MASK: Int = 0xff
    }
}

internal data class DiagnosticConsentState(
    val privacyAccepted: Boolean = false,
    val featureEnabled: Boolean = false,
    val crashEnabled: Boolean = false,
    val featureEnabledAtEpochMillis: Long = 0L,
    val crashEnabledAtEpochMillis: Long = 0L,
    val lastExitCollectedAtEpochMillis: Long = 0L,
)

public enum class DiagnosticPhase {
    APPLICATION,
    AUTHENTICATION,
    VAULT_READ,
    VAULT_WRITE,
    SETTINGS,
    TELEMETRY_SEND,
    CRASH_COLLECTION,
    LOCAL_CLEAR,
}

public enum class DiagnosticCode {
    FLOW_STARTED,
    FLOW_COMPLETED,
    FLOW_CANCELLED,
    VALIDATION_REJECTED,
    AUTHENTICATION_REJECTED,
    STORAGE_FAILED,
    NETWORK_FAILED,
    EXTERNAL_REJECTED,
}

public enum class DiagnosticSeverity { INFO, WARNING, ERROR }

/** Debug metadata is a fixed schema and cannot carry business content or free text. */
public data class DebugDiagnosticMetadata(
    val retryCount: Int,
    val background: Boolean,
    val deviceCategory: DeviceCapabilityCategory,
) {
    init {
        require(retryCount in 0..MAXIMUM_RETRY_COUNT)
    }

    private companion object {
        const val MAXIMUM_RETRY_COUNT: Int = 100
    }
}

public data class StructuredDiagnosticEntry(
    val occurredAtEpochMillis: Long,
    val severity: DiagnosticSeverity,
    val phase: DiagnosticPhase,
    val code: DiagnosticCode,
    val debugMetadata: DebugDiagnosticMetadata?,
)
