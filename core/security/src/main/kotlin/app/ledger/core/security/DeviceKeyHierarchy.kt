@file:Suppress("TooGenericExceptionCaught", "TooManyFunctions")

package app.ledger.core.security

import android.util.AtomicFile
import app.ledger.core.common.StableId
import com.google.crypto.tink.Aead
import com.google.crypto.tink.StreamingAead
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.SecureRandom

interface DeviceLedgerKeyProvider {
    fun initialize(bookId: StableId)

    fun open(bookId: StableId): DeviceLedgerKeys

    fun destroyLocal(bookId: StableId)
}

class DeviceKeyHierarchy(
    private val keystore: AndroidKeystoreKeys,
    private val envelopeStore: SecurityEnvelopeStore,
    private val secureRandom: SecureRandom = SecureRandom(),
) : DeviceLedgerKeyProvider {
    /** Prepares a device-KEK wrapped replacement without changing the currently active ledger keys. */
    fun preparePortableReplacement(
        bookId: StableId,
        portableMaterial: SecretBytes,
    ): PreparedDeviceLedgerKeyReplacement {
        val suffix = SecurityEnvelopeStore.aliasSuffix(bookId)
        val previous = envelopeStore.readDeviceBundle(bookId) ?: throw SecurityException.KeyUnavailable()
        if (!keystore.hasDeviceLedgerKek(suffix)) throw SecurityException.KeyUnavailable()
        val decoded = portableMaterial.useBytes(::decodePortableBundle)
        try {
            val bundle = try {
                encodeBundleFromSecrets(decoded.databaseDek, decoded.attachmentRoot, decoded.secureSettings)
            } finally {
                decoded.attachmentRoot.fill(0)
                decoded.secureSettings.fill(0)
            }
            val associatedData = SecurityAssociatedData.keyEnvelope(bookId, KeyMaterialPurpose.DEVICE_LEDGER_BUNDLE)
            val replacement = try {
                keystore.wrapWithDeviceLedgerKek(
                    suffix,
                    bundle,
                    associatedData,
                    KeyMaterialPurpose.DEVICE_LEDGER_BUNDLE,
                )
            } finally {
                bundle.fill(0)
                associatedData.fill(0)
            }
            return PreparedDeviceLedgerKeyReplacement(
                bookId,
                envelopeStore,
                previous,
                replacement,
                SecretBytes.copyOf(decoded.databaseDek),
            )
        } finally {
            decoded.databaseDek.fill(0)
        }
    }

    /** Restores the pre-exchange wrapped bundle after a process death. No plaintext key is persisted. */
    fun recoverPortableReplacement(bookId: StableId, recoveryFile: File): Boolean {
        if (!recoveryFile.isFile) return false
        val previous = WrappedKeyMaterialCodec.decode(AtomicFile(recoveryFile).readFully())
        require(previous.purpose == KeyMaterialPurpose.DEVICE_LEDGER_BUNDLE)
        envelopeStore.writeDeviceBundle(bookId, previous)
        AtomicFile(recoveryFile).delete()
        return true
    }

    @Suppress("TooGenericExceptionCaught")
    override fun initialize(bookId: StableId) {
        val suffix = SecurityEnvelopeStore.aliasSuffix(bookId)
        val existing = envelopeStore.readDeviceBundle(bookId)
        if (existing != null) {
            if (!keystore.hasDeviceLedgerKek(suffix)) throw SecurityException.KeyUnavailable()
            return
        }
        if (keystore.hasDeviceLedgerKek(suffix)) {
            throw SecurityException.KeyUnavailable()
        }
        keystore.ensureDeviceLedgerKek(suffix)
        val databaseDek = ByteArray(DATABASE_DEK_BYTES).also(secureRandom::nextBytes)
        val attachmentRoot = LedgerTink.generateAeadKeyset()
        val secureSettings = LedgerTink.generateAeadKeyset()
        try {
            val bundle = encodeBundle(databaseDek, attachmentRoot, secureSettings)
            try {
                val associatedData = SecurityAssociatedData.keyEnvelope(bookId, KeyMaterialPurpose.DEVICE_LEDGER_BUNDLE)
                envelopeStore.writeDeviceBundle(
                    bookId,
                    keystore.wrapWithDeviceLedgerKek(
                        suffix,
                        bundle,
                        associatedData,
                        KeyMaterialPurpose.DEVICE_LEDGER_BUNDLE,
                    ),
                )
                associatedData.fill(0)
            } finally {
                bundle.fill(0)
            }
        } catch (error: Exception) {
            keystore.deleteDeviceLedgerKek(suffix)
            envelopeStore.deleteAll(bookId)
            throw error
        } finally {
            databaseDek.fill(0)
            attachmentRoot.close()
            secureSettings.close()
        }
    }

    override fun open(bookId: StableId): DeviceLedgerKeys {
        val suffix = SecurityEnvelopeStore.aliasSuffix(bookId)
        val envelope = envelopeStore.readDeviceBundle(bookId) ?: throw SecurityException.KeyUnavailable()
        require(envelope.purpose == KeyMaterialPurpose.DEVICE_LEDGER_BUNDLE)
        val associatedData = SecurityAssociatedData.keyEnvelope(bookId, KeyMaterialPurpose.DEVICE_LEDGER_BUNDLE)
        return try {
            keystore.unwrapWithDeviceLedgerKek(suffix, envelope, associatedData).use { plaintext ->
                plaintext.useBytes(::decodeBundle)
            }
        } finally {
            associatedData.fill(0)
        }
    }

    override fun destroyLocal(bookId: StableId) {
        val suffix = SecurityEnvelopeStore.aliasSuffix(bookId)
        envelopeStore.deleteAll(bookId)
        if (keystore.hasVaultAuthenticationKek(suffix)) keystore.deleteVaultAuthenticationKek(suffix)
        if (keystore.hasDeviceLedgerKek(suffix)) keystore.deleteDeviceLedgerKek(suffix)
    }

    private fun encodeBundle(
        databaseDek: ByteArray,
        attachmentRoot: SecretBytes,
        secureSettings: SecretBytes,
    ): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(BUNDLE_MAGIC)
            output.writeInt(BUNDLE_VERSION)
            writeSecret(output, databaseDek)
            attachmentRoot.useBytes { writeSecret(output, it) }
            secureSettings.useBytes { writeSecret(output, it) }
        }
        bytes.toByteArray()
    }

    private fun encodeBundleFromSecrets(
        databaseDek: ByteArray,
        attachmentRoot: ByteArray,
        secureSettings: ByteArray,
    ): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(BUNDLE_MAGIC)
            output.writeInt(BUNDLE_VERSION)
            writeSecret(output, databaseDek)
            writeSecret(output, attachmentRoot)
            writeSecret(output, secureSettings)
        }
        bytes.toByteArray()
    }

    private fun decodePortableBundle(bytes: ByteArray): PortableLedgerSecrets = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == PORTABLE_KEY_MAGIC) { "invalid portable key material" }
            require(input.readInt() == PORTABLE_KEY_VERSION) { "unsupported portable key material" }
            val databaseDek = readSecret(input, DATABASE_DEK_BYTES, DATABASE_DEK_BYTES)
            val attachmentRoot = readSecret(input, MINIMUM_TINK_KEYSET_BYTES, MAXIMUM_TINK_KEYSET_BYTES)
            val secureSettings = readSecret(input, MINIMUM_TINK_KEYSET_BYTES, MAXIMUM_TINK_KEYSET_BYTES)
            require(input.read() == -1) { "trailing portable key material" }
            PortableLedgerSecrets(databaseDek, attachmentRoot, secureSettings)
        }
    } catch (error: IllegalArgumentException) {
        throw SecurityException.CorruptEnvelope(error)
    }

    private fun decodeBundle(bytes: ByteArray): DeviceLedgerKeys = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == BUNDLE_MAGIC) { "invalid device key bundle" }
            require(input.readInt() == BUNDLE_VERSION) { "unsupported device key bundle" }
            val databaseDek = readSecret(input, DATABASE_DEK_BYTES, DATABASE_DEK_BYTES)
            val attachmentRoot = readSecret(input, MINIMUM_TINK_KEYSET_BYTES, MAXIMUM_TINK_KEYSET_BYTES)
            val secureSettings = readSecret(input, MINIMUM_TINK_KEYSET_BYTES, MAXIMUM_TINK_KEYSET_BYTES)
            require(input.read() == -1) { "trailing device key material" }
            DeviceLedgerKeys(
                SecretBytes.copyOf(databaseDek),
                SecretBytes.copyOf(attachmentRoot),
                SecretBytes.copyOf(secureSettings),
            ).also {
                databaseDek.fill(0)
                attachmentRoot.fill(0)
                secureSettings.fill(0)
            }
        }
    } catch (error: IllegalArgumentException) {
        throw SecurityException.CorruptEnvelope(error)
    }

    private fun writeSecret(output: DataOutputStream, value: ByteArray) {
        output.writeInt(value.size)
        output.write(value)
    }

    private fun readSecret(input: DataInputStream, minimum: Int, maximum: Int): ByteArray {
        val size = input.readInt()
        require(size in minimum..maximum) { "invalid key material length" }
        return ByteArray(size).also(input::readFully)
    }

    private companion object {
        const val BUNDLE_MAGIC = 0x4c444b42
        const val BUNDLE_VERSION = 1
        const val DATABASE_DEK_BYTES = 32
        const val MINIMUM_TINK_KEYSET_BYTES = 32
        const val MAXIMUM_TINK_KEYSET_BYTES = 64 * 1024
        const val PORTABLE_KEY_MAGIC = 0x4c504b4d
        const val PORTABLE_KEY_VERSION = 1
    }
}

private data class PortableLedgerSecrets(
    val databaseDek: ByteArray,
    val attachmentRoot: ByteArray,
    val secureSettings: ByteArray,
)

/** Activation and rollback are AtomicFile writes; the database exchange remains owned by finance:data. */
class PreparedDeviceLedgerKeyReplacement internal constructor(
    private val bookId: StableId,
    private val envelopeStore: SecurityEnvelopeStore,
    private val previous: WrappedKeyMaterial,
    private val replacement: WrappedKeyMaterial,
    val restoredDatabaseDek: SecretBytes,
) : AutoCloseable {
    private var activated = false

    @Synchronized
    fun activate(recoveryFile: File) {
        check(!activated) { "restored key material is already active" }
        require(!recoveryFile.exists()) { "key recovery marker already exists" }
        val atomic = AtomicFile(recoveryFile)
        val encoded = WrappedKeyMaterialCodec.encode(previous)
        val output = atomic.startWrite()
        try {
            output.write(encoded)
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        } finally {
            encoded.fill(0)
        }
        envelopeStore.writeDeviceBundle(bookId, replacement)
        activated = true
    }

    @Synchronized
    fun rollback() {
        if (activated) {
            envelopeStore.writeDeviceBundle(bookId, previous)
            activated = false
        }
    }

    @Synchronized
    fun commit(recoveryFile: File) {
        check(activated) { "restored key material is not active" }
        AtomicFile(recoveryFile).delete()
    }

    @Synchronized
    override fun close() {
        restoredDatabaseDek.close()
    }

    override fun toString(): String = "PreparedDeviceLedgerKeyReplacement(redacted,activated=$activated)"
}

class DeviceLedgerKeys internal constructor(
    val databaseDek: SecretBytes,
    private val attachmentRootKeyset: SecretBytes,
    private val secureSettingsKeyset: SecretBytes,
) : AutoCloseable {
    /** Exported only into a recovery-password protected backup package. */
    fun portableKeyMaterial(): SecretBytes = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(PORTABLE_KEY_MAGIC)
            output.writeInt(PORTABLE_KEY_VERSION)
            databaseDek.useBytes { writePortableSecret(output, it) }
            attachmentRootKeyset.useBytes { writePortableSecret(output, it) }
            secureSettingsKeyset.useBytes { writePortableSecret(output, it) }
        }
        val encoded = bytes.toByteArray()
        try {
            SecretBytes.copyOf(encoded)
        } finally {
            encoded.fill(0)
            bytes.reset()
        }
    }
    fun secureSettingsAead(): Aead = secureSettingsKeyset.useBytes(LedgerTink::aead)

    fun encryptSecureSettings(plaintext: ByteArray, associatedData: ByteArray): ByteArray = secureSettingsKeyset.useBytes { keyset ->
        LedgerTink.aead(keyset).encrypt(plaintext, associatedData)
    }

    fun decryptSecureSettings(ciphertext: ByteArray, associatedData: ByteArray): ByteArray = secureSettingsKeyset.useBytes { keyset ->
        LedgerTink.aead(keyset).decrypt(ciphertext, associatedData)
    }

    fun createAttachmentDataKey(): SecretBytes = LedgerTink.generateStreamingAeadKeyset()

    fun wrapAttachmentDataKey(serializedDataKey: SecretBytes, associatedData: ByteArray): ByteArray = attachmentRootKeyset.useBytes { keyset ->
        serializedDataKey.useBytes { dataKey -> LedgerTink.aead(keyset).encrypt(dataKey, associatedData) }
    }

    fun unwrapAttachmentDataKey(wrappedDataKey: ByteArray, associatedData: ByteArray): StreamingAead = attachmentRootKeyset.useBytes { keyset ->
        val serialized = LedgerTink.aead(keyset).decrypt(wrappedDataKey, associatedData)
        try {
            LedgerTink.streamingAead(serialized)
        } finally {
            serialized.fill(0)
        }
    }

    override fun close() {
        databaseDek.close()
        attachmentRootKeyset.close()
        secureSettingsKeyset.close()
    }

    override fun toString(): String = "DeviceLedgerKeys(redacted)"

    private fun writePortableSecret(output: DataOutputStream, value: ByteArray) {
        require(value.isNotEmpty())
        output.writeInt(value.size)
        output.write(value)
    }

    private companion object {
        const val PORTABLE_KEY_MAGIC = 0x4c504b4d
        const val PORTABLE_KEY_VERSION = 1
    }
}
