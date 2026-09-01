package app.ledger.app

internal enum class SystemAuthenticationChannel {
    APPLICATION,
    BACKUP_VAULT,
    VAULT,
    SENSITIVE_SETTINGS,
}

internal enum class SystemAuthenticationStartResult {
    STARTED,
    ALREADY_ACTIVE,
    BUSY,
}

/**
 * Routes the Activity's single AndroidX BiometricPrompt callback to one business request.
 *
 * BiometricPrompt instances hosted by the same FragmentActivity share an Activity-scoped
 * BiometricViewModel on the stable 1.1 API. Constructing multiple prompt instances therefore
 * replaces the shared callback. A single prompt plus this coordinator prevents cross-feature
 * callback loss and rejects overlapping requests deterministically.
 */
internal class SystemAuthenticationCoordinator(
    initialChannel: SystemAuthenticationChannel? = null,
) {
    private var activeChannel: SystemAuthenticationChannel? = initialChannel

    fun start(channel: SystemAuthenticationChannel): SystemAuthenticationStartResult = when (activeChannel) {
        null -> {
            activeChannel = channel
            SystemAuthenticationStartResult.STARTED
        }
        channel -> SystemAuthenticationStartResult.ALREADY_ACTIVE
        else -> SystemAuthenticationStartResult.BUSY
    }

    fun finish(): SystemAuthenticationChannel? = activeChannel.also { activeChannel = null }

    fun activeChannel(): SystemAuthenticationChannel? = activeChannel

    fun isActive(channel: SystemAuthenticationChannel): Boolean = activeChannel == channel
}
