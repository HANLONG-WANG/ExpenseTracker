package app.ledger.core.security

import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import app.ledger.core.common.StableId
import com.google.crypto.tink.Aead
import java.security.GeneralSecurityException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher

enum class VaultAction {
    REVEAL_PAN,
    COPY_PAN,
    REVEAL_SECURITY_CODE,
    EDIT_VAULT,
}

class VaultFieldCiphertext(bytes: ByteArray) {
    private val stored = bytes.copyOf()

    val bytes: ByteArray
        get() = stored.copyOf()

    init {
        require(stored.isNotEmpty())
    }

    override fun toString(): String = "VaultFieldCiphertext(redacted,size=${stored.size})"
}

class VaultExposureRegistry(
    private val elapsedRealtimeMillis: () -> Long,
) {
    private val active = linkedSetOf<SensitivePlaintext>()

    fun register(bytes: ByteArray): SensitivePlaintext = synchronized(active) {
        SensitivePlaintext(
            bytes,
            expiresAtMillis = elapsedRealtimeMillis() + EXPOSURE_MILLIS,
            nowMillis = elapsedRealtimeMillis,
            onClose = { handle -> synchronized(active) { active.remove(handle) } },
        ).also(active::add)
    }

    fun onApplicationBackgrounded() = clearAll()

    fun onApplicationLocked() = clearAll()

    fun clearExpired() = synchronized(active) {
        active.filter { it.isExpired() }.forEach(SensitivePlaintext::close)
    }

    fun clearAll() = synchronized(active) {
        active.toList().forEach(SensitivePlaintext::close)
        active.clear()
    }

    fun activeCount(): Int = synchronized(active) { active.size }

    private companion object {
        const val EXPOSURE_MILLIS = 30_000L
    }
}

class SensitivePlaintext internal constructor(
    bytes: ByteArray,
    private val expiresAtMillis: Long,
    private val nowMillis: () -> Long,
    private val onClose: (SensitivePlaintext) -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val value = bytes.copyOf()

    @Synchronized
    fun <T> useBytes(block: (ByteArray) -> T): T {
        check(!closed.get() && !isExpired()) { "sensitive plaintext is unavailable" }
        val copy = value.copyOf()
        return try {
            block(copy)
        } finally {
            copy.fill(0)
        }
    }

    fun remainingMillis(): Long = (expiresAtMillis - nowMillis()).coerceAtLeast(0L)

    internal fun isExpired(): Boolean = nowMillis() >= expiresAtMillis

    @Synchronized
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            value.fill(0)
            onClose(this)
        }
    }

    override fun toString(): String = "SensitivePlaintext(redacted,closed=${closed.get()})"
}

class VaultKeyHierarchy(
    private val keystore: AndroidKeystoreKeys,
    private val envelopeStore: SecurityEnvelopeStore,
    private val exposureRegistry: VaultExposureRegistry,
) {
    fun isProvisioned(bookId: StableId): Boolean = envelopeStore.readVaultDek(bookId) != null

    fun beginProvisioning(bookId: StableId): VaultProvisioningRequest {
        check(envelopeStore.readVaultDek(bookId) == null) { "vault is already provisioned" }
        val suffix = SecurityEnvelopeStore.aliasSuffix(bookId)
        keystore.ensureVaultAuthenticationKek(suffix)
        val associatedData = SecurityAssociatedData.keyEnvelope(bookId, KeyMaterialPurpose.VAULT_DEK)
        val cipher = keystore.prepareVaultWrapCipher(suffix)
        val vaultDek = LedgerTink.generateAeadKeyset()
        return VaultProvisioningRequest(
            cryptoObject = BiometricPrompt.CryptoObject(cipher),
            cipher = cipher,
            vaultDek = vaultDek,
            associatedData = associatedData,
            completeEnvelope = { wrapped -> envelopeStore.writeVaultDek(bookId, wrapped) },
            onCancelled = {
                if (envelopeStore.readVaultDek(bookId) == null && keystore.hasVaultAuthenticationKek(suffix)) {
                    keystore.deleteVaultAuthenticationKek(suffix)
                }
            },
        )
    }

    fun beginRestore(bookId: StableId, recoveredVaultDek: SecretBytes): VaultProvisioningRequest {
        check(envelopeStore.readVaultDek(bookId) == null) { "vault is already provisioned" }
        val suffix = SecurityEnvelopeStore.aliasSuffix(bookId)
        keystore.ensureVaultAuthenticationKek(suffix)
        val associatedData = SecurityAssociatedData.keyEnvelope(bookId, KeyMaterialPurpose.VAULT_DEK)
        val cipher = keystore.prepareVaultWrapCipher(suffix)
        return VaultProvisioningRequest(
            cryptoObject = BiometricPrompt.CryptoObject(cipher),
            cipher = cipher,
            vaultDek = recoveredVaultDek,
            associatedData = associatedData,
            completeEnvelope = { wrapped -> envelopeStore.writeVaultDek(bookId, wrapped) },
            onCancelled = {
                if (envelopeStore.readVaultDek(bookId) == null && keystore.hasVaultAuthenticationKek(suffix)) {
                    keystore.deleteVaultAuthenticationKek(suffix)
                }
            },
        )
    }

    @Suppress("LongParameterList")
    fun beginReveal(
        bookId: StableId,
        cardId: StableId,
        fieldType: VaultFieldType,
        schemaVersion: Int,
        action: VaultAction,
        ciphertext: VaultFieldCiphertext,
    ): VaultRevealRequest {
        requireActionField(action, fieldType)
        require(action != VaultAction.EDIT_VAULT)
        val unwrap = beginUnwrap(bookId)
        val fieldAssociatedData = SecurityAssociatedData.vaultField(bookId, cardId, fieldType, schemaVersion)
        return VaultRevealRequest(
            cryptoObject = unwrap.cryptoObject,
            cipher = unwrap.cipher,
            wrappedVaultDek = unwrap.envelope,
            fieldCiphertext = ciphertext,
            wrappedKeyAssociatedData = unwrap.associatedData,
            fieldAssociatedData = fieldAssociatedData,
            exposureRegistry = exposureRegistry,
        )
    }

    fun beginEdit(bookId: StableId): VaultEditRequest {
        val unwrap = beginUnwrap(bookId)
        return VaultEditRequest(unwrap.cryptoObject, unwrap.cipher, unwrap.envelope, unwrap.associatedData)
    }

    fun beginRecoveryExport(bookId: StableId): VaultRecoveryExportRequest {
        val unwrap = beginUnwrap(bookId)
        return VaultRecoveryExportRequest(unwrap.cryptoObject, unwrap.cipher, unwrap.envelope, unwrap.associatedData)
    }

    private fun beginUnwrap(bookId: StableId): VaultUnwrapParts {
        if (keystore.deviceSecurityCapability() == DeviceSecurityCapability.MISSING_DEVICE_CREDENTIAL) {
            throw SecurityException.DeviceSecurityUnavailable()
        }
        val suffix = SecurityEnvelopeStore.aliasSuffix(bookId)
        val envelope = envelopeStore.readVaultDek(bookId) ?: throw SecurityException.KeyUnavailable()
        require(envelope.purpose == KeyMaterialPurpose.VAULT_DEK)
        val associatedData = SecurityAssociatedData.keyEnvelope(bookId, KeyMaterialPurpose.VAULT_DEK)
        val cipher = keystore.prepareVaultUnwrapCipher(suffix, envelope)
        return VaultUnwrapParts(BiometricPrompt.CryptoObject(cipher), cipher, envelope, associatedData)
    }

    private fun requireActionField(action: VaultAction, fieldType: VaultFieldType) {
        when (action) {
            VaultAction.REVEAL_PAN,
            VaultAction.COPY_PAN,
            -> require(fieldType == VaultFieldType.PAN)
            VaultAction.REVEAL_SECURITY_CODE -> require(fieldType == VaultFieldType.SECURITY_CODE)
            VaultAction.EDIT_VAULT -> Unit
        }
    }

    private data class VaultUnwrapParts(
        val cryptoObject: BiometricPrompt.CryptoObject,
        val cipher: Cipher,
        val envelope: WrappedKeyMaterial,
        val associatedData: ByteArray,
    )
}

class VaultProvisioningRequest internal constructor(
    val cryptoObject: BiometricPrompt.CryptoObject,
    private val cipher: Cipher,
    private val vaultDek: SecretBytes,
    private val associatedData: ByteArray,
    private val completeEnvelope: (WrappedKeyMaterial) -> Unit,
    private val onCancelled: () -> Unit,
) : AutoCloseable {
    private val consumed = AtomicBoolean(false)

    fun complete(authenticatedCryptoObject: BiometricPrompt.CryptoObject) {
        check(consumed.compareAndSet(false, true)) { "vault provisioning request was already consumed" }
        try {
            val authenticatedCipher = requireAuthenticatedCipher(authenticatedCryptoObject, cipher)
            authenticatedCipher.updateAAD(associatedData)
            vaultDek.useBytes { serialized ->
                completeEnvelope(
                    WrappedKeyMaterial(
                        formatVersion = 1,
                        purpose = KeyMaterialPurpose.VAULT_DEK,
                        nonce = authenticatedCipher.iv,
                        ciphertext = authenticatedCipher.doFinal(serialized),
                    ),
                )
            }
        } catch (error: GeneralSecurityException) {
            throw SecurityException.AuthenticationFailed(error)
        } finally {
            vaultDek.close()
            associatedData.fill(0)
        }
    }

    override fun close() {
        if (consumed.compareAndSet(false, true)) {
            vaultDek.close()
            associatedData.fill(0)
            onCancelled()
        }
    }
}

@Suppress("LongParameterList")
class VaultRevealRequest internal constructor(
    val cryptoObject: BiometricPrompt.CryptoObject,
    private val cipher: Cipher,
    private val wrappedVaultDek: WrappedKeyMaterial,
    private val fieldCiphertext: VaultFieldCiphertext,
    private val wrappedKeyAssociatedData: ByteArray,
    private val fieldAssociatedData: ByteArray,
    private val exposureRegistry: VaultExposureRegistry,
) : AutoCloseable {
    private val consumed = AtomicBoolean(false)

    fun complete(authenticatedCryptoObject: BiometricPrompt.CryptoObject): SensitivePlaintext {
        check(consumed.compareAndSet(false, true)) { "vault action request was already consumed" }
        return try {
            val vaultAead = authenticatedVaultAead(
                authenticatedCryptoObject,
                cipher,
                wrappedVaultDek,
                wrappedKeyAssociatedData,
            )
            val ciphertext = fieldCiphertext.bytes
            val plaintext = try {
                vaultAead.decrypt(ciphertext, fieldAssociatedData)
            } finally {
                ciphertext.fill(0)
            }
            exposureRegistry.register(plaintext).also { plaintext.fill(0) }
        } catch (error: AEADBadTagException) {
            throw SecurityException.AuthenticationFailed(error)
        } catch (error: GeneralSecurityException) {
            throw SecurityException.AuthenticationFailed(error)
        } finally {
            wrappedKeyAssociatedData.fill(0)
            fieldAssociatedData.fill(0)
        }
    }

    override fun close() {
        if (consumed.compareAndSet(false, true)) {
            wrappedKeyAssociatedData.fill(0)
            fieldAssociatedData.fill(0)
        }
    }
}

class VaultEditRequest internal constructor(
    val cryptoObject: BiometricPrompt.CryptoObject,
    private val cipher: Cipher,
    private val wrappedVaultDek: WrappedKeyMaterial,
    private val wrappedKeyAssociatedData: ByteArray,
) : AutoCloseable {
    private val consumed = AtomicBoolean(false)

    fun complete(authenticatedCryptoObject: BiometricPrompt.CryptoObject): OneShotVaultEditor {
        check(consumed.compareAndSet(false, true)) { "vault edit request was already consumed" }
        return try {
            OneShotVaultEditor(
                authenticatedVaultAead(
                    authenticatedCryptoObject,
                    cipher,
                    wrappedVaultDek,
                    wrappedKeyAssociatedData,
                ),
            )
        } finally {
            wrappedKeyAssociatedData.fill(0)
        }
    }

    override fun close() {
        if (consumed.compareAndSet(false, true)) wrappedKeyAssociatedData.fill(0)
    }
}

class OneShotVaultEditor internal constructor(private val aead: Aead) {
    private val consumed = AtomicBoolean(false)

    fun encrypt(plaintext: SecretBytes, associatedData: ByteArray): VaultFieldCiphertext {
        check(consumed.compareAndSet(false, true)) { "vault editor authorization was already consumed" }
        return plaintext.useBytes { VaultFieldCiphertext(aead.encrypt(it, associatedData)) }
    }
}

class VaultRecoveryExportRequest internal constructor(
    val cryptoObject: BiometricPrompt.CryptoObject,
    private val cipher: Cipher,
    private val wrappedVaultDek: WrappedKeyMaterial,
    private val wrappedKeyAssociatedData: ByteArray,
) : AutoCloseable {
    private val consumed = AtomicBoolean(false)

    fun complete(authenticatedCryptoObject: BiometricPrompt.CryptoObject): SecretBytes {
        check(consumed.compareAndSet(false, true)) { "vault recovery export request was already consumed" }
        return try {
            authenticatedVaultKeyset(authenticatedCryptoObject, cipher, wrappedVaultDek, wrappedKeyAssociatedData)
        } finally {
            wrappedKeyAssociatedData.fill(0)
        }
    }

    override fun close() {
        if (consumed.compareAndSet(false, true)) wrappedKeyAssociatedData.fill(0)
    }
}

private fun authenticatedVaultAead(
    authenticatedCryptoObject: BiometricPrompt.CryptoObject,
    expectedCipher: Cipher,
    envelope: WrappedKeyMaterial,
    associatedData: ByteArray,
): Aead = authenticatedVaultKeyset(authenticatedCryptoObject, expectedCipher, envelope, associatedData).use { serialized ->
    serialized.useBytes(LedgerTink::aead)
}

private fun authenticatedVaultKeyset(
    authenticatedCryptoObject: BiometricPrompt.CryptoObject,
    expectedCipher: Cipher,
    envelope: WrappedKeyMaterial,
    associatedData: ByteArray,
): SecretBytes = try {
    val authenticatedCipher = requireAuthenticatedCipher(authenticatedCryptoObject, expectedCipher)
    authenticatedCipher.updateAAD(associatedData)
    val serialized = authenticatedCipher.doFinal(envelope.ciphertext)
    SecretBytes.copyOf(serialized).also { serialized.fill(0) }
} catch (error: GeneralSecurityException) {
    throw SecurityException.AuthenticationFailed(error)
}

private fun requireAuthenticatedCipher(
    authenticatedCryptoObject: BiometricPrompt.CryptoObject,
    expectedCipher: Cipher,
): Cipher {
    val actual = authenticatedCryptoObject.cipher ?: throw SecurityException.AuthenticationFailed()
    if (actual !== expectedCipher) throw SecurityException.AuthenticationFailed()
    return actual
}

data class BiometricPromptText(val title: String, val subtitle: String?) {
    init {
        require(title.isNotBlank())
        require(title.length <= MAXIMUM_TITLE_CHARACTERS)
        require(subtitle == null || subtitle.length <= MAXIMUM_SUBTITLE_CHARACTERS)
    }

    private companion object {
        const val MAXIMUM_TITLE_CHARACTERS = 64
        const val MAXIMUM_SUBTITLE_CHARACTERS = 128
    }
}

sealed interface BiometricAuthenticationResult {
    data class Success(val cryptoObject: BiometricPrompt.CryptoObject) : BiometricAuthenticationResult

    data class Error(val code: BiometricErrorCode) : BiometricAuthenticationResult

    data object FailedAttempt : BiometricAuthenticationResult
}

enum class BiometricErrorCode {
    CANCELLED,
    LOCKED_OUT,
    DEVICE_SECURITY_CHANGED,
    UNAVAILABLE,
    UNKNOWN,
}

class AndroidBiometricPromptGateway(
    activity: FragmentActivity,
    executor: Executor,
) {
    private var callback: ((BiometricAuthenticationResult) -> Unit)? = null
    private val prompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val crypto = result.cryptoObject
                callback?.invoke(
                    if (crypto == null) {
                        BiometricAuthenticationResult.Error(BiometricErrorCode.UNKNOWN)
                    } else {
                        BiometricAuthenticationResult.Success(crypto)
                    },
                )
                callback = null
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                callback?.invoke(BiometricAuthenticationResult.Error(BiometricErrorClassifier.classify(errorCode)))
                callback = null
            }

            override fun onAuthenticationFailed() {
                callback?.invoke(BiometricAuthenticationResult.FailedAttempt)
            }
        },
    )

    fun authenticate(
        cryptoObject: BiometricPrompt.CryptoObject,
        text: BiometricPromptText,
        result: (BiometricAuthenticationResult) -> Unit,
    ) {
        check(callback == null) { "an authentication request is already active" }
        callback = result
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(text.title)
            .setSubtitle(text.subtitle)
            .setAllowedAuthenticators(AndroidKeystoreKeys.VAULT_AUTHENTICATORS)
            .setConfirmationRequired(true)
            .build()
        prompt.authenticate(promptInfo, cryptoObject)
    }

    fun cancel() {
        prompt.cancelAuthentication()
        callback = null
    }
}

internal object BiometricErrorClassifier {
    fun classify(frameworkErrorCode: Int): BiometricErrorCode = when (frameworkErrorCode) {
        BiometricPrompt.ERROR_CANCELED,
        BiometricPrompt.ERROR_USER_CANCELED,
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        -> BiometricErrorCode.CANCELLED
        BiometricPrompt.ERROR_LOCKOUT,
        BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
        -> BiometricErrorCode.LOCKED_OUT
        BiometricPrompt.ERROR_NO_BIOMETRICS -> BiometricErrorCode.DEVICE_SECURITY_CHANGED
        BiometricPrompt.ERROR_HW_NOT_PRESENT,
        BiometricPrompt.ERROR_HW_UNAVAILABLE,
        BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
        -> BiometricErrorCode.UNAVAILABLE
        else -> BiometricErrorCode.UNKNOWN
    }
}
