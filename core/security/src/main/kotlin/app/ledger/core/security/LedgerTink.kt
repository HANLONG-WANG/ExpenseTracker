package app.ledger.core.security

import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.config.TinkConfig
import java.io.InputStream
import java.io.OutputStream

object LedgerTink {
    private val configuration by lazy {
        @Suppress("DEPRECATION")
        TinkConfig.register()
        RegistryConfiguration.get()
    }

    fun generateAeadKeyset(): SecretBytes {
        configuration
        return serialize(
            KeysetHandle.newBuilder()
                .addEntry(
                    KeysetHandle.generateEntryFromParametersName(AEAD_PARAMETERS)
                        .withRandomId()
                        .makePrimary(),
                )
                .build(),
        )
    }

    fun generateStreamingAeadKeyset(): SecretBytes {
        configuration
        return serialize(
            KeysetHandle.newBuilder()
                .addEntry(
                    KeysetHandle.generateEntryFromParametersName(STREAMING_PARAMETERS)
                        .withRandomId()
                        .makePrimary(),
                )
                .build(),
        )
    }

    fun aead(serializedKeyset: ByteArray): Aead = parse(serializedKeyset).getPrimitive(configuration, Aead::class.java)

    fun streamingAead(serializedKeyset: ByteArray): StreamingAead = parse(serializedKeyset).getPrimitive(configuration, StreamingAead::class.java)

    fun encryptStream(
        primitive: StreamingAead,
        destination: OutputStream,
        associatedData: ByteArray,
        writePlaintext: (OutputStream) -> Unit,
    ) {
        primitive.newEncryptingStream(destination, associatedData).use(writePlaintext)
    }

    fun decryptStream(
        primitive: StreamingAead,
        source: InputStream,
        associatedData: ByteArray,
        readPlaintext: (InputStream) -> Unit,
    ) {
        primitive.newDecryptingStream(source, associatedData).use(readPlaintext)
    }

    private fun serialize(handle: KeysetHandle): SecretBytes {
        val bytes = TinkProtoKeysetFormat.serializeKeyset(handle, InsecureSecretKeyAccess.get(), configuration)
        return SecretBytes.copyOf(bytes).also { bytes.fill(0) }
    }

    private fun parse(serializedKeyset: ByteArray): KeysetHandle = TinkProtoKeysetFormat.parseKeyset(
        serializedKeyset,
        InsecureSecretKeyAccess.get(),
        configuration,
    )

    private const val AEAD_PARAMETERS = "AES256_GCM"
    private const val STREAMING_PARAMETERS = "AES256_GCM_HKDF_1MB"
}
