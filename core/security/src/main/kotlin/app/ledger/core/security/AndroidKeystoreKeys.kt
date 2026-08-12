package app.ledger.core.security

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import androidx.biometric.BiometricManager
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.spec.InvalidKeySpecException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

@Suppress("TooManyFunctions")
class AndroidKeystoreKeys(context: Context) {
    // During Application.attachBaseContext the framework has not yet published the
    // process Application as applicationContext. The supplied ContextImpl is already
    // process-scoped and is the only safe early-startup fallback for ACRA capability
    // classification.
    private val applicationContext = context.applicationContext ?: context
    private val keyguardManager = applicationContext.getSystemService(KeyguardManager::class.java)
    private val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    fun deviceSecurityCapability(): DeviceSecurityCapability {
        if (!keyguardManager.isDeviceSecure) return DeviceSecurityCapability.MISSING_DEVICE_CREDENTIAL
        val status = BiometricManager.from(applicationContext)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        return when (status) {
            BiometricManager.BIOMETRIC_SUCCESS -> DeviceSecurityCapability.BIOMETRIC_OR_CREDENTIAL
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
            -> DeviceSecurityCapability.DEVICE_CREDENTIAL_ONLY
            else -> DeviceSecurityCapability.DEVICE_CREDENTIAL_ONLY
        }
    }

    /** Whether this API/device can satisfy the vault's zero-duration, CryptoObject-bound action. */
    fun vaultAuthenticationAvailable(): Boolean = when (deviceSecurityCapability()) {
        DeviceSecurityCapability.BIOMETRIC_OR_CREDENTIAL -> true
        DeviceSecurityCapability.DEVICE_CREDENTIAL_ONLY -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        DeviceSecurityCapability.MISSING_DEVICE_CREDENTIAL -> false
    }

    fun ensureDeviceLedgerKek(bookAliasSuffix: String) {
        val alias = deviceAlias(bookAliasSuffix)
        if (!keyStore.containsAlias(alias)) generateDeviceLedgerKek(alias)
    }

    fun ensureVaultAuthenticationKek(bookAliasSuffix: String) {
        if (!vaultAuthenticationAvailable()) throw SecurityException.DeviceSecurityUnavailable()
        val alias = vaultAlias(bookAliasSuffix)
        if (!keyStore.containsAlias(alias)) generateVaultAuthenticationKek(alias)
    }

    fun wrapWithDeviceLedgerKek(
        bookAliasSuffix: String,
        plaintext: ByteArray,
        associatedData: ByteArray,
        purpose: KeyMaterialPurpose,
    ): WrappedKeyMaterial = try {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, requireKey(deviceAlias(bookAliasSuffix)))
        cipher.updateAAD(associatedData)
        WrappedKeyMaterial(KEY_ENVELOPE_VERSION, purpose, cipher.iv, cipher.doFinal(plaintext))
    } catch (error: GeneralSecurityException) {
        throw classify(error)
    }

    fun unwrapWithDeviceLedgerKek(
        bookAliasSuffix: String,
        envelope: WrappedKeyMaterial,
        associatedData: ByteArray,
    ): SecretBytes = try {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            requireKey(deviceAlias(bookAliasSuffix)),
            GCMParameterSpec(GCM_TAG_BITS, envelope.nonce),
        )
        cipher.updateAAD(associatedData)
        val plaintext = cipher.doFinal(envelope.ciphertext)
        SecretBytes.copyOf(plaintext).also { plaintext.fill(0) }
    } catch (error: GeneralSecurityException) {
        throw classify(error)
    }

    fun prepareVaultWrapCipher(bookAliasSuffix: String): Cipher = try {
        Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, requireKey(vaultAlias(bookAliasSuffix)))
        }
    } catch (error: GeneralSecurityException) {
        throw classify(error)
    }

    fun prepareVaultUnwrapCipher(
        bookAliasSuffix: String,
        envelope: WrappedKeyMaterial,
    ): Cipher = try {
        Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
            init(
                Cipher.DECRYPT_MODE,
                requireKey(vaultAlias(bookAliasSuffix)),
                GCMParameterSpec(GCM_TAG_BITS, envelope.nonce),
            )
        }
    } catch (error: GeneralSecurityException) {
        throw classify(error)
    }

    fun deleteDeviceLedgerKek(bookAliasSuffix: String) {
        keyStore.deleteEntry(deviceAlias(bookAliasSuffix))
    }

    fun deleteVaultAuthenticationKek(bookAliasSuffix: String) {
        keyStore.deleteEntry(vaultAlias(bookAliasSuffix))
    }

    fun hasDeviceLedgerKek(bookAliasSuffix: String): Boolean = keyStore.containsAlias(deviceAlias(bookAliasSuffix))

    fun hasVaultAuthenticationKek(bookAliasSuffix: String): Boolean = keyStore.containsAlias(vaultAlias(bookAliasSuffix))

    fun deviceLedgerKekPolicy(bookAliasSuffix: String): KeystoreKeyPolicy = keyPolicy(deviceAlias(bookAliasSuffix))

    fun vaultAuthenticationKekPolicy(bookAliasSuffix: String): KeystoreKeyPolicy = keyPolicy(vaultAlias(bookAliasSuffix))

    private fun generateDeviceLedgerKek(alias: String) {
        generateAesKey(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
    }

    private fun generateVaultAuthenticationKek(alias: String) {
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(AES_KEY_BITS)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            // The frozen contract allows either a strong biometric or the device credential.
            // Enrollment changes therefore keep the credential recovery path, while prompt
            // errors still fail closed and are surfaced as a device-security change.
            .setInvalidatedByBiometricEnrollment(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        generateAesKey(builder.build())
    }

    private fun generateAesKey(specification: KeyGenParameterSpec) {
        try {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).apply {
                init(specification)
                generateKey()
            }
        } catch (error: GeneralSecurityException) {
            throw classify(error)
        }
    }

    private fun requireKey(alias: String): SecretKey = (keyStore.getKey(alias, null) as? SecretKey)
        ?: throw SecurityException.KeyUnavailable()

    private fun keyPolicy(alias: String): KeystoreKeyPolicy = try {
        val key = requireKey(alias)
        val keyInfo = SecretKeyFactory.getInstance(key.algorithm, KEYSTORE_PROVIDER)
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        KeystoreKeyPolicy(
            userAuthenticationRequired = keyInfo.isUserAuthenticationRequired,
            authenticationValiditySeconds = keyInfo.userAuthenticationValidityDurationSeconds,
            invalidatedByBiometricEnrollment = keyInfo.isInvalidatedByBiometricEnrollment,
            authenticationType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) keyInfo.userAuthenticationType else 0,
        )
    } catch (error: InvalidKeySpecException) {
        throw SecurityException.KeyUnavailable(error)
    } catch (error: GeneralSecurityException) {
        throw classify(error)
    }

    private fun classify(error: GeneralSecurityException): SecurityException = when (error) {
        is UserNotAuthenticatedException -> SecurityException.AuthenticationRequired(error)
        is KeyPermanentlyInvalidatedException -> SecurityException.KeyUnavailable(error)
        else -> SecurityException.KeyUnavailable(error)
    }

    private fun deviceAlias(suffix: String): String = "$DEVICE_ALIAS_PREFIX${validateAliasSuffix(suffix)}"

    private fun vaultAlias(suffix: String): String = "$VAULT_ALIAS_PREFIX${validateAliasSuffix(suffix)}"

    private fun validateAliasSuffix(value: String): String {
        require(ALIAS_SUFFIX.matches(value)) { "invalid opaque key alias suffix" }
        return value
    }

    companion object {
        const val VAULT_AUTHENTICATORS: Int =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val DEVICE_ALIAS_PREFIX = "ledger.device.v1."
        private const val VAULT_ALIAS_PREFIX = "ledger.vault.auth.v1."
        private const val AES_KEY_BITS = 256
        private const val GCM_TAG_BITS = 128
        private const val KEY_ENVELOPE_VERSION = 1
        private val ALIAS_SUFFIX = Regex("[0-9a-f]{32}")
    }
}

enum class DeviceSecurityCapability {
    MISSING_DEVICE_CREDENTIAL,
    DEVICE_CREDENTIAL_ONLY,
    BIOMETRIC_OR_CREDENTIAL,
}

data class KeystoreKeyPolicy(
    val userAuthenticationRequired: Boolean,
    val authenticationValiditySeconds: Int,
    val invalidatedByBiometricEnrollment: Boolean,
    val authenticationType: Int,
)
