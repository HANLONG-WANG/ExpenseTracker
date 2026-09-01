package app.ledger.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    private val viewModel: AppRootViewModel by viewModels()
    private lateinit var prompt: BiometricPrompt
    private lateinit var authenticationCoordinator: SystemAuthenticationCoordinator
    private lateinit var privacy: app.ledger.core.security.AndroidScreenPrivacyController
    private lateinit var jankMonitor: app.ledger.core.designsystem.LedgerJankMonitor
    private var composeView: ComposeView? = null
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.notificationPermissionResult(it)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        privacy = app.ledger.core.security.AndroidScreenPrivacyController(this)
        authenticationCoordinator = SystemAuthenticationCoordinator(
            savedInstanceState?.getString(ACTIVE_AUTHENTICATION_CHANNEL_KEY)
                ?.let { saved -> SystemAuthenticationChannel.entries.singleOrNull { it.name == saved } },
        )
        prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    when (authenticationCoordinator.finish()) {
                        SystemAuthenticationChannel.APPLICATION -> viewModel.authenticationSucceeded()
                        SystemAuthenticationChannel.BACKUP_VAULT ->
                            viewModel.backupVaultAuthenticationSucceeded(result.cryptoObject)
                        SystemAuthenticationChannel.VAULT -> viewModel.vaultAuthenticationSucceeded(result.cryptoObject)
                        SystemAuthenticationChannel.SENSITIVE_SETTINGS ->
                            viewModel.sensitiveSettingsAuthenticationSucceeded()
                        null -> Unit
                    }
                }

                override fun onAuthenticationFailed() {
                    // A non-terminal failed attempt keeps the currently routed prompt active.
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    when (authenticationCoordinator.finish()) {
                        SystemAuthenticationChannel.APPLICATION ->
                            viewModel.authenticationFailed(applicationAuthenticationError(errorCode))
                        SystemAuthenticationChannel.BACKUP_VAULT -> viewModel.backupVaultAuthenticationCancelled()
                        SystemAuthenticationChannel.VAULT -> viewModel.vaultAuthenticationFailed(vaultError(errorCode))
                        SystemAuthenticationChannel.SENSITIVE_SETTINGS ->
                            viewModel.sensitiveSettingsAuthenticationFailed()
                        null -> Unit
                    }
                }
            },
        )
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.authenticationRequests.collect { authenticateApplicationUi() }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.backupVaultAuthenticationRequests.collect(::authenticateVaultBackupEnrollment)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.vaultAuthenticationRequests.collect(::authenticateVaultAction)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sensitiveSettingsAuthenticationRequests.collect(::authenticateSensitiveSettingsAction)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.screenPrivacyPolicy.collect(privacy::apply)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.openSystemSecurityRequests.collect {
                    startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.externalLinkRequests.collect { uri ->
                    val request = Intent(Intent.ACTION_VIEW, uri)
                    if (request.resolveActivity(packageManager) == null) {
                        viewModel.externalApplicationUnavailable()
                    } else {
                        runCatching { startActivity(request) }
                            .onFailure { viewModel.externalApplicationUnavailable() }
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.notificationPermissionRequests.collect {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.notificationPermissionResult(true)
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.openNotificationSettingsRequests.collect {
                    val request = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    if (request.resolveActivity(packageManager) == null) {
                        viewModel.externalApplicationUnavailable()
                    } else {
                        runCatching { startActivity(request) }
                            .onFailure { viewModel.externalApplicationUnavailable() }
                    }
                }
            }
        }
        composeView = ComposeView(this).also { root ->
            root.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            root.setContent { LedgerAppRoot(viewModel) }
            setContentView(root)
        }
        jankMonitor = app.ledger.core.designsystem.LedgerJankMonitor.attach(window)
        viewModel.handleDeepLink(intent?.data)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.handleDeepLink(intent.data)
    }

    override fun onStart() {
        super.onStart()
        privacy.apply(viewModel.screenPrivacyPolicy.value.copy(applicationInBackground = false))
        viewModel.onApplicationForegrounded()
    }

    override fun onStop() {
        privacy.apply(viewModel.screenPrivacyPolicy.value.copy(applicationInBackground = true))
        viewModel.onApplicationBackgrounded(authenticationCoordinator.isActive(SystemAuthenticationChannel.VAULT))
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        authenticationCoordinator.activeChannel()?.let { outState.putString(ACTIVE_AUTHENTICATION_CHANNEL_KEY, it.name) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        composeView?.let { root ->
            root.disposeComposition()
            (root.parent as? ViewGroup)?.removeView(root)
        }
        composeView = null
        jankMonitor.close()
        super.onDestroy()
    }

    private fun authenticateApplicationUi() {
        startSystemAuthentication(SystemAuthenticationChannel.APPLICATION) {
            prompt.authenticate(
                nonCryptoPromptInfo(
                    getString(R.string.global_locked_title),
                    getString(R.string.global_locked_message),
                ),
            )
        }
    }

    private fun authenticateVaultBackupEnrollment(cryptoObject: BiometricPrompt.CryptoObject) {
        startSystemAuthentication(SystemAuthenticationChannel.BACKUP_VAULT) {
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(getString(app.ledger.feature.transfer.R.string.backup_include_vault))
                    .setSubtitle(getString(app.ledger.feature.transfer.R.string.backup_include_vault_supporting))
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .setNegativeButtonText(getString(android.R.string.cancel))
                    .build(),
                cryptoObject,
            )
        }
    }

    private fun authenticateVaultAction(request: VaultAuthenticationPrompt) {
        val title = when (request.purpose) {
            VaultAuthenticationPurpose.OPEN_LIST -> getString(app.ledger.feature.vault.R.string.vault_title)
            VaultAuthenticationPurpose.REVEAL_PAN -> getString(app.ledger.feature.vault.R.string.vault_primary_number)
            VaultAuthenticationPurpose.COPY_PAN -> getString(app.ledger.feature.vault.R.string.vault_primary_number)
            VaultAuthenticationPurpose.REVEAL_CVC -> getString(app.ledger.feature.vault.R.string.vault_security_code)
            VaultAuthenticationPurpose.EDIT_VAULT -> getString(app.ledger.feature.vault.R.string.vault_edit)
        }
        val cryptoObject = request.cryptoObject
        startSystemAuthentication(SystemAuthenticationChannel.VAULT) {
            if (cryptoObject == null) {
                prompt.authenticate(nonCryptoPromptInfo(title, getString(app.ledger.feature.vault.R.string.vault_security_banner)))
            } else {
                prompt.authenticate(
                    cryptoPromptInfo(
                        title,
                        getString(app.ledger.feature.vault.R.string.vault_security_banner),
                        getString(android.R.string.cancel),
                    ),
                    cryptoObject,
                )
            }
        }
    }

    private fun authenticateSensitiveSettingsAction(purpose: SensitiveSettingsAuthenticationPurpose) {
        val title = when (purpose) {
            SensitiveSettingsAuthenticationPurpose.ENABLE_APP_LOCK -> getString(app.ledger.feature.settings.R.string.security_app_lock)
            SensitiveSettingsAuthenticationPurpose.CLEAR_LOCAL -> getString(app.ledger.feature.settings.R.string.clear_local)
            SensitiveSettingsAuthenticationPurpose.DELETE_CLOUD -> getString(app.ledger.feature.transfer.R.string.clear_cloud_title)
        }
        startSystemAuthentication(SystemAuthenticationChannel.SENSITIVE_SETTINGS) {
            prompt.authenticate(nonCryptoPromptInfo(title, null))
        }
    }

    private inline fun startSystemAuthentication(
        channel: SystemAuthenticationChannel,
        authenticate: () -> Unit,
    ) {
        when (authenticationCoordinator.start(channel)) {
            SystemAuthenticationStartResult.ALREADY_ACTIVE -> return
            SystemAuthenticationStartResult.BUSY -> return rejectSystemAuthentication(channel)
            SystemAuthenticationStartResult.STARTED -> Unit
        }
        try {
            authenticate()
        } catch (_: RuntimeException) {
            if (authenticationCoordinator.finish() == channel) rejectSystemAuthentication(channel)
        }
    }

    private fun rejectSystemAuthentication(channel: SystemAuthenticationChannel) {
        when (channel) {
            SystemAuthenticationChannel.APPLICATION -> viewModel.authenticationFailed(AppAuthenticationError.FAILED)
            SystemAuthenticationChannel.BACKUP_VAULT -> viewModel.backupVaultAuthenticationCancelled()
            SystemAuthenticationChannel.VAULT ->
                viewModel.vaultAuthenticationFailed(app.ledger.core.security.BiometricErrorCode.UNAVAILABLE)
            SystemAuthenticationChannel.SENSITIVE_SETTINGS -> viewModel.sensitiveSettingsAuthenticationFailed()
        }
    }

    private companion object {
        const val ACTIVE_AUTHENTICATION_CHANNEL_KEY = "active_system_authentication_channel"
    }
}

private fun cryptoPromptInfo(title: String, subtitle: String?, cancelText: String): BiometricPrompt.PromptInfo {
    val builder = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setConfirmationRequired(true)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        builder.setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        ).build()
    } else {
        builder
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText(cancelText)
            .build()
    }
}

@Suppress("DEPRECATION")
private fun nonCryptoPromptInfo(title: String, subtitle: String?): BiometricPrompt.PromptInfo {
    val builder = BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle(subtitle).setConfirmationRequired(true)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        builder.setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        ).build()
    } else {
        builder.setDeviceCredentialAllowed(true).build()
    }
}

private fun vaultError(errorCode: Int): app.ledger.core.security.BiometricErrorCode = when (errorCode) {
    BiometricPrompt.ERROR_CANCELED,
    BiometricPrompt.ERROR_USER_CANCELED,
    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
    -> app.ledger.core.security.BiometricErrorCode.CANCELLED
    BiometricPrompt.ERROR_LOCKOUT,
    BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
    -> app.ledger.core.security.BiometricErrorCode.LOCKED_OUT
    BiometricPrompt.ERROR_NO_BIOMETRICS -> app.ledger.core.security.BiometricErrorCode.DEVICE_SECURITY_CHANGED
    BiometricPrompt.ERROR_HW_NOT_PRESENT,
    BiometricPrompt.ERROR_HW_UNAVAILABLE,
    BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
    -> app.ledger.core.security.BiometricErrorCode.UNAVAILABLE
    else -> app.ledger.core.security.BiometricErrorCode.UNKNOWN
}

private fun applicationAuthenticationError(errorCode: Int): AppAuthenticationError = when (errorCode) {
    BiometricPrompt.ERROR_LOCKOUT,
    BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
    -> AppAuthenticationError.LOCKED_OUT
    BiometricPrompt.ERROR_CANCELED,
    BiometricPrompt.ERROR_USER_CANCELED,
    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
    -> AppAuthenticationError.CANCELED
    BiometricPrompt.ERROR_NO_BIOMETRICS,
    BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
    -> AppAuthenticationError.DEVICE_SECURITY_CHANGED
    else -> AppAuthenticationError.FAILED
}
