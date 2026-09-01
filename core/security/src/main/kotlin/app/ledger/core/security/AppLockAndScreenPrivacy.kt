package app.ledger.core.security

import android.app.Activity
import android.os.Build
import android.view.WindowManager

sealed interface AppLockTimeout {
    val timeoutMillis: Long

    data object Immediately : AppLockTimeout {
        override val timeoutMillis: Long = 0L
    }

    data object OneMinute : AppLockTimeout {
        override val timeoutMillis: Long = MILLIS_PER_MINUTE
    }

    data object FiveMinutes : AppLockTimeout {
        override val timeoutMillis: Long = FIVE_MINUTES_MILLIS
    }

    data object FifteenMinutes : AppLockTimeout {
        override val timeoutMillis: Long = FIFTEEN_MINUTES_MILLIS
    }

    @ConsistentCopyVisibility
    data class Custom private constructor(override val timeoutMillis: Long) : AppLockTimeout {
        companion object {
            fun of(timeoutMillis: Long): Custom {
                require(timeoutMillis in MINIMUM_CUSTOM_MILLIS..MAXIMUM_CUSTOM_MILLIS)
                return Custom(timeoutMillis)
            }

            private const val MINIMUM_CUSTOM_MILLIS = 30_000L
            private const val MAXIMUM_CUSTOM_MILLIS = 24 * 60 * 60 * 1_000L
        }
    }
}

private const val MILLIS_PER_MINUTE = 60_000L
private const val FIVE_MINUTES_MILLIS = 300_000L
private const val FIFTEEN_MINUTES_MILLIS = 900_000L

data class AppLockSettings(
    val enabled: Boolean = false,
    val timeout: AppLockTimeout = AppLockTimeout.Immediately,
)

sealed interface AppLockState {
    data object Disabled : AppLockState

    data object Locked : AppLockState

    data object Unlocked : AppLockState
}

class AppLockController(
    initialSettings: AppLockSettings = AppLockSettings(),
    private val elapsedRealtimeMillis: () -> Long,
    private val onLocked: () -> Unit,
) {
    private var settings = initialSettings
    private var backgroundedAtMillis: Long? = null
    private var state: AppLockState = if (initialSettings.enabled) AppLockState.Locked else AppLockState.Disabled

    @Synchronized
    fun currentState(): AppLockState = state

    @Synchronized
    fun updateSettings(updated: AppLockSettings, authenticated: Boolean) {
        if (updated.enabled && !settings.enabled) require(authenticated) { "enabling app lock requires authentication" }
        settings = updated
        backgroundedAtMillis = null
        state = when {
            !updated.enabled -> AppLockState.Disabled
            state == AppLockState.Unlocked -> AppLockState.Unlocked
            else -> AppLockState.Locked
        }
    }

    @Synchronized
    fun authenticationSucceeded(): Boolean {
        if (!settings.enabled || state != AppLockState.Locked) return false
        state = AppLockState.Unlocked
        backgroundedAtMillis = null
        return true
    }

    @Synchronized
    fun onApplicationBackgrounded() {
        if (!settings.enabled || state != AppLockState.Unlocked) return
        backgroundedAtMillis = elapsedRealtimeMillis()
        if (settings.timeout == AppLockTimeout.Immediately) lockNow()
    }

    @Synchronized
    fun onApplicationForegrounded(): AppLockState {
        val backgroundedAt = backgroundedAtMillis
        if (settings.enabled && state == AppLockState.Unlocked && backgroundedAt != null) {
            val elapsed = (elapsedRealtimeMillis() - backgroundedAt).coerceAtLeast(0L)
            if (elapsed >= settings.timeout.timeoutMillis) lockNow()
        }
        backgroundedAtMillis = null
        return state
    }

    @Synchronized
    fun forceLock() {
        if (settings.enabled) lockNow()
    }

    private fun lockNow() {
        if (state != AppLockState.Locked) {
            state = AppLockState.Locked
            onLocked()
        }
    }
}

data class ScreenPrivacyPolicy(
    val obscureRecentTasks: Boolean = true,
    val globalFlagSecure: Boolean = false,
    val vaultVisible: Boolean = false,
    val applicationInBackground: Boolean = false,
)

class AndroidScreenPrivacyController(private val activity: Activity) {
    fun apply(policy: ScreenPrivacyPolicy) {
        val secure = policy.globalFlagSecure || policy.vaultVisible ||
            (policy.obscureRecentTasks && policy.applicationInBackground)
        if (secure) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(!policy.obscureRecentTasks)
        }
    }
}
