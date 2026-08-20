package app.ledger.feature.settings

public enum class SecuritySettingsRequiredState(public val screenId: String, public val contractName: String) {
    SETG_006_DISABLED("SETG-006", "disabled"),
    SETG_006_ENABLED("SETG-006", "enabled"),
    SETG_006_DEVICE_SECURITY_MISSING("SETG-006", "deviceSecurityMissing"),
    SETG_007_CONTENT("SETG-007", "content"),
    SETG_008_CONTENT("SETG-008", "content"),
    SETG_009_PRE_CONSENT("SETG-009", "preConsent"),
    SETG_009_ENABLED("SETG-009", "enabled"),
    SETG_009_DISABLED("SETG-009", "disabled"),
    SETG_010_CONTENT("SETG-010", "content"),
    SETG_010_EMPTY("SETG-010", "empty"),
    SETG_011_CONTENT("SETG-011", "content"),
    SETG_011_EMPTY("SETG-011", "empty"),
    CLR_001_CONTENT("CLR-001", "content"),
    CLR_001_CONFIRMING("CLR-001", "confirming"),
    CLR_001_CLEARING("CLR-001", "clearing"),
    CLR_001_FAILED("CLR-001", "failed"),
    SYS_004_MISSING("SYS-004", "missing"),
    SYS_004_CONFIGURED("SYS-004", "configured"),
}

@Suppress("MagicNumber")
public enum class AppLockTimeout(public val millis: Long) {
    IMMEDIATE(0L),
    ONE_MINUTE(60_000L),
    FIVE_MINUTES(300_000L),
    FIFTEEN_MINUTES(900_000L),
    CUSTOM(-1L),
}

@Suppress("MagicNumber")
public enum class TrashRetention(public val days: Int) {
    SEVEN_DAYS(7),
    THIRTY_DAYS(30),
    NINETY_DAYS(90),
    NEVER(0),
    CUSTOM(-1),
}

public data class FeatureQueueRow(
    val occurredAtEpochMillis: Long,
    val event: String,
    val entry: String,
    val outcome: String,
    val duration: String,
    val errorCode: String,
) {
    init {
        require(listOf(event, entry, outcome, duration, errorCode).all(FIXED_ENUM::matches))
    }
}

public data class CrashQueueRow(
    val occurredAtEpochMillis: Long,
    val kind: String,
    val errorCode: String,
    val frameCount: Int,
) {
    init {
        require(FIXED_ENUM.matches(kind) && FIXED_ENUM.matches(errorCode))
        require(frameCount in 0..MAXIMUM_VISIBLE_STACK_FRAMES)
    }

    private companion object {
        const val MAXIMUM_VISIBLE_STACK_FRAMES = 64
    }
}

public data class SecurityPrivacySettingsState(
    val screenId: String,
    val presentation: SecuritySettingsRequiredState,
    val appLockEnabled: Boolean = false,
    val appLockTimeout: AppLockTimeout = AppLockTimeout.IMMEDIATE,
    val customTimeoutMinutes: Int = 1,
    val deviceSecurityConfigured: Boolean = true,
    val globalScreenshotBlocked: Boolean = false,
    val obscureRecentTasks: Boolean = true,
    val trashRetention: TrashRetention = TrashRetention.THIRTY_DAYS,
    val customTrashRetentionDays: Int = 30,
    val privacyAccepted: Boolean = false,
    val telemetryEnabled: Boolean = false,
    val crashEnabled: Boolean = false,
    val featureRows: List<FeatureQueueRow> = emptyList(),
    val crashRows: List<CrashQueueRow> = emptyList(),
    val errorCode: String? = null,
) {
    init {
        require(screenId in SUPPORTED_SECURITY_SETTINGS_SCREENS)
        require(presentation.screenId == screenId)
        require(customTimeoutMinutes in MINIMUM_CUSTOM_TIMEOUT_MINUTES..MAXIMUM_CUSTOM_TIMEOUT_MINUTES)
        require(customTrashRetentionDays in MINIMUM_TRASH_RETENTION_DAYS..MAXIMUM_TRASH_RETENTION_DAYS)
        require(errorCode == null || ERROR_CODE.matches(errorCode))
    }

    public companion object {
        public const val MINIMUM_CUSTOM_TIMEOUT_MINUTES: Int = 1
        public const val MAXIMUM_CUSTOM_TIMEOUT_MINUTES: Int = 1_440
        public const val MINIMUM_TRASH_RETENTION_DAYS: Int = 1
        public const val MAXIMUM_TRASH_RETENTION_DAYS: Int = 365
    }
}

public sealed interface SecurityPrivacyScreenAction {
    public data class AppLockEnabled(val enabled: Boolean) : SecurityPrivacyScreenAction
    public data class AppLockTimeoutChanged(val timeout: AppLockTimeout, val customMinutes: Int) : SecurityPrivacyScreenAction
    public data object TestLock : SecurityPrivacyScreenAction
    public data class GlobalScreenshotBlocked(val blocked: Boolean) : SecurityPrivacyScreenAction
    public data class ObscureRecentTasks(val enabled: Boolean) : SecurityPrivacyScreenAction
    public data class TrashRetentionChanged(val retention: TrashRetention) : SecurityPrivacyScreenAction
    public data class CustomTrashRetentionChanged(val days: Int) : SecurityPrivacyScreenAction
    public data object OpenTrash : SecurityPrivacyScreenAction
    public data class TelemetryEnabled(val enabled: Boolean) : SecurityPrivacyScreenAction
    public data class CrashEnabled(val enabled: Boolean) : SecurityPrivacyScreenAction
    public data object OpenFeatureQueue : SecurityPrivacyScreenAction
    public data object OpenCrashQueue : SecurityPrivacyScreenAction
    public data object OpenPrivacyPolicy : SecurityPrivacyScreenAction
    public data object DeleteFeatureQueue : SecurityPrivacyScreenAction
    public data object DeleteCrashQueue : SecurityPrivacyScreenAction
    public data object BeginLocalClear : SecurityPrivacyScreenAction
    public data object CancelLocalClear : SecurityPrivacyScreenAction
    public data object ConfirmLocalClear : SecurityPrivacyScreenAction
    public data object OpenSystemSecurity : SecurityPrivacyScreenAction
    public data object SecurityConfigured : SecurityPrivacyScreenAction
}

internal class SecurityPrivacySettingsActions(
    val onAppLockEnabled: (Boolean) -> Unit,
    val onAppLockTimeout: (AppLockTimeout, Int) -> Unit,
    val onTestLock: () -> Unit,
    val onGlobalScreenshotBlocked: (Boolean) -> Unit,
    val onObscureRecentTasks: (Boolean) -> Unit,
    val onTrashRetention: (TrashRetention) -> Unit,
    val onOpenTrash: () -> Unit,
    val onTelemetryEnabled: (Boolean) -> Unit,
    val onCrashEnabled: (Boolean) -> Unit,
    val onOpenFeatureQueue: () -> Unit,
    val onOpenCrashQueue: () -> Unit,
    val onOpenPrivacyPolicy: () -> Unit,
    val onDeleteFeatureQueue: () -> Unit,
    val onDeleteCrashQueue: () -> Unit,
    val onBeginLocalClear: () -> Unit,
    val onCancelLocalClear: () -> Unit,
    val onConfirmLocalClear: () -> Unit,
    val onOpenSystemSecurity: () -> Unit,
    val onSecurityConfigured: () -> Unit,
    val onCustomTrashRetention: (Int) -> Unit = {},
)

internal fun securityPrivacyActions(onAction: (SecurityPrivacyScreenAction) -> Unit): SecurityPrivacySettingsActions = SecurityPrivacySettingsActions(
    onAppLockEnabled = { onAction(SecurityPrivacyScreenAction.AppLockEnabled(it)) },
    onAppLockTimeout = { timeout, minutes -> onAction(SecurityPrivacyScreenAction.AppLockTimeoutChanged(timeout, minutes)) },
    onTestLock = { onAction(SecurityPrivacyScreenAction.TestLock) },
    onGlobalScreenshotBlocked = { onAction(SecurityPrivacyScreenAction.GlobalScreenshotBlocked(it)) },
    onObscureRecentTasks = { onAction(SecurityPrivacyScreenAction.ObscureRecentTasks(it)) },
    onTrashRetention = { onAction(SecurityPrivacyScreenAction.TrashRetentionChanged(it)) },
    onCustomTrashRetention = { onAction(SecurityPrivacyScreenAction.CustomTrashRetentionChanged(it)) },
    onOpenTrash = { onAction(SecurityPrivacyScreenAction.OpenTrash) },
    onTelemetryEnabled = { onAction(SecurityPrivacyScreenAction.TelemetryEnabled(it)) },
    onCrashEnabled = { onAction(SecurityPrivacyScreenAction.CrashEnabled(it)) },
    onOpenFeatureQueue = { onAction(SecurityPrivacyScreenAction.OpenFeatureQueue) },
    onOpenCrashQueue = { onAction(SecurityPrivacyScreenAction.OpenCrashQueue) },
    onOpenPrivacyPolicy = { onAction(SecurityPrivacyScreenAction.OpenPrivacyPolicy) },
    onDeleteFeatureQueue = { onAction(SecurityPrivacyScreenAction.DeleteFeatureQueue) },
    onDeleteCrashQueue = { onAction(SecurityPrivacyScreenAction.DeleteCrashQueue) },
    onBeginLocalClear = { onAction(SecurityPrivacyScreenAction.BeginLocalClear) },
    onCancelLocalClear = { onAction(SecurityPrivacyScreenAction.CancelLocalClear) },
    onConfirmLocalClear = { onAction(SecurityPrivacyScreenAction.ConfirmLocalClear) },
    onOpenSystemSecurity = { onAction(SecurityPrivacyScreenAction.OpenSystemSecurity) },
    onSecurityConfigured = { onAction(SecurityPrivacyScreenAction.SecurityConfigured) },
)

public val SUPPORTED_SECURITY_SETTINGS_SCREENS: Set<String> = setOf(
    "SETG-006",
    "SETG-007",
    "SETG-008",
    "SETG-009",
    "SETG-010",
    "SETG-011",
    "CLR-001",
    "SYS-004",
)

private val FIXED_ENUM = Regex("[A-Z][A-Z0-9_]{1,63}")
private val ERROR_CODE = Regex("[A-Z][A-Z0-9_]{2,47}")
