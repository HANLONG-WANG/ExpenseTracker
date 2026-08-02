package app.ledger.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
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
    private lateinit var privacy: app.ledger.core.security.AndroidScreenPrivacyController

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
                    viewModel.authenticationFailed(AppAuthenticationError.FAILED)
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
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.authenticationRequests.collect { authenticateApplicationUi() }
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
        privacy.apply(app.ledger.core.security.ScreenPrivacyPolicy(applicationInBackground = false))
        viewModel.onApplicationForegrounded()
    }

    override fun onStop() {
        privacy.apply(app.ledger.core.security.ScreenPrivacyPolicy(applicationInBackground = true))
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
}
