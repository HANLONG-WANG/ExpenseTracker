package app.ledger.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    private val viewModel: AppRootViewModel by viewModels()
    private lateinit var prompt: BiometricPrompt
    private lateinit var backupVaultPrompt: BiometricPrompt
    private lateinit var vaultPrompt: BiometricPrompt
    private lateinit var sensitiveSettingsPrompt: BiometricPrompt
    private lateinit var privacy: app.ledger.core.security.AndroidScreenPrivacyController
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.notificationPermissionResult(it)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        privacy = app.ledger.core.security.AndroidScreenPrivacyController(this)
        prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    viewModel.authenticationSucceeded()
                }

                override fun onAuthenticationFailed() {
                    // A non-terminal failed attempt keeps the application-unlock prompt active.
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val error = when (errorCode) {
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
                    viewModel.authenticationFailed(error)
                }
            },
        )
        backupVaultPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    viewModel.backupVaultAuthenticationSucceeded(result.cryptoObject)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    viewModel.backupVaultAuthenticationCancelled()
                }
            },
        )
        vaultPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    viewModel.vaultAuthenticationSucceeded(result.cryptoObject)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    viewModel.vaultAuthenticationFailed(vaultError(errorCode))
                }

                override fun onAuthenticationFailed() {
                    // A non-terminal failed attempt keeps this exact CryptoObject prompt active.
                }
            },
        )
        sensitiveSettingsPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    viewModel.sensitiveSettingsAuthenticationSucceeded()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    viewModel.sensitiveSettingsAuthenticationFailed()
                }

                override fun onAuthenticationFailed() {
                    // The system prompt remains active until success or a terminal error.
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
        setContent { LedgerAppRoot(viewModel) }
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
        viewModel.onApplicationBackgrounded()
        super.onStop()
    }

    private fun authenticateApplicationUi() {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.global_locked_title))
                .setSubtitle(getString(R.string.global_locked_message))
                .setAllowedAuthenticators(authenticators)
                .build(),
        )
    }

    private fun authenticateVaultBackupEnrollment(cryptoObject: BiometricPrompt.CryptoObject) {
        backupVaultPrompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(app.ledger.feature.transfer.R.string.backup_include_vault))
                .setSubtitle(getString(app.ledger.feature.transfer.R.string.backup_include_vault_supporting))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText(getString(android.R.string.cancel))
                .build(),
            cryptoObject,
        )
    }

    private fun authenticateVaultAction(request: VaultAuthenticationPrompt) {
        val title = when (request.purpose) {
            VaultAuthenticationPurpose.REVEAL_PAN -> getString(app.ledger.feature.vault.R.string.vault_primary_number)
            VaultAuthenticationPurpose.COPY_PAN -> getString(app.ledger.feature.vault.R.string.vault_primary_number)
            VaultAuthenticationPurpose.REVEAL_CVC -> getString(app.ledger.feature.vault.R.string.vault_security_code)
            VaultAuthenticationPurpose.EDIT_VAULT -> getString(app.ledger.feature.vault.R.string.vault_edit)
        }
        vaultPrompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(getString(app.ledger.feature.vault.R.string.vault_security_banner))
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
                .setConfirmationRequired(true)
                .build(),
            request.cryptoObject,
        )
    }

    private fun authenticateSensitiveSettingsAction(purpose: SensitiveSettingsAuthenticationPurpose) {
        val title = when (purpose) {
            SensitiveSettingsAuthenticationPurpose.ENABLE_APP_LOCK -> getString(app.ledger.feature.settings.R.string.security_app_lock)
            SensitiveSettingsAuthenticationPurpose.CLEAR_LOCAL -> getString(app.ledger.feature.settings.R.string.clear_local)
            SensitiveSettingsAuthenticationPurpose.DELETE_CLOUD -> getString(app.ledger.feature.transfer.R.string.clear_cloud_title)
        }
        sensitiveSettingsPrompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
                .setConfirmationRequired(true)
                .build(),
        )
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
}
