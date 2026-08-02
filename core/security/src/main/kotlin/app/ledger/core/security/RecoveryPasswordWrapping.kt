package app.ledger.core.security

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max

data class Argon2idParameters(
    val formatVersion: Int,
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
    val outputBytes: Int,
) {
    init {
        require(formatVersion == CURRENT_FORMAT_VERSION)
        require(memoryKiB >= MINIMUM_MEMORY_KIB)
        require(iterations >= MINIMUM_ITERATIONS)
        require(parallelism in 1..MAXIMUM_PARALLELISM)
        require(outputBytes == OUTPUT_BYTES)
    }

    companion object {
        const val CURRENT_FORMAT_VERSION: Int = 1
        const val MINIMUM_MEMORY_KIB: Int = 64 * 1024
        const val MINIMUM_ITERATIONS: Int = 3
        const val MAXIMUM_PARALLELISM: Int = 4
        const val OUTPUT_BYTES: Int = 32

        fun minimum(): Argon2idParameters = Argon2idParameters(
            formatVersion = CURRENT_FORMAT_VERSION,
            memoryKiB = MINIMUM_MEMORY_KIB,
            iterations = MINIMUM_ITERATIONS,
            parallelism = 1,
            outputBytes = OUTPUT_BYTES,
        )
    }
}

class Argon2idDeriver {
    fun derive(password: RecoveryPassword, salt: ByteArray, parameters: Argon2idParameters): SecretBytes {
        require(salt.size >= MINIMUM_SALT_BYTES) { "Argon2id salt is too short" }
        val output = ByteArray(parameters.outputBytes)
        val generator = Argon2BytesGenerator().apply {
            init(
                Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withSalt(salt.copyOf())
                    .withMemoryAsKB(parameters.memoryKiB)
                    .withIterations(parameters.iterations)
                    .withParallelism(parameters.parallelism)
                    .build(),
            )
        }
        return try {
            password.useChars { generator.generateBytes(it, output) }
            SecretBytes.copyOf(output)
        } finally {
            output.fill(0)
        }
    }

    companion object {
        const val MINIMUM_SALT_BYTES: Int = 16
    }
}

fun interface MonotonicNanos {
    fun now(): Long
}

class Argon2idCalibrator(
    private val deriver: Argon2idDeriver = Argon2idDeriver(),
    private val clock: MonotonicNanos = MonotonicNanos(System::nanoTime),
    private val processorCount: Int = Runtime.getRuntime().availableProcessors(),
) {
    fun calibrate(targetMillis: Long = DEFAULT_TARGET_MILLIS): Argon2idParameters {
        require(targetMillis in MINIMUM_TARGET_MILLIS..MAXIMUM_TARGET_MILLIS)
        val salt = ByteArray(Argon2idDeriver.MINIMUM_SALT_BYTES) { index -> (index + 1).toByte() }
        val probeChars = "Ledger-Argon2id-Probe-2026".toCharArray()
        val password = RecoveryPassword.copyOf(probeChars)
        probeChars.fill('\u0000')
        var iterations = Argon2idParameters.MINIMUM_ITERATIONS
        val parallelism = max(1, processorCount.coerceAtMost(Argon2idParameters.MAXIMUM_PARALLELISM))
        try {
            while (iterations < MAXIMUM_CALIBRATED_ITERATIONS) {
                val candidate = Argon2idParameters(
                    formatVersion = Argon2idParameters.CURRENT_FORMAT_VERSION,
                    memoryKiB = Argon2idParameters.MINIMUM_MEMORY_KIB,
                    iterations = iterations,
                    parallelism = parallelism,
                    outputBytes = Argon2idParameters.OUTPUT_BYTES,
                )
                val started = clock.now()
                deriver.derive(password, salt, candidate).close()
                val elapsedMillis = (clock.now() - started).coerceAtLeast(0L) / NANOS_PER_MILLI
                if (elapsedMillis >= targetMillis) return candidate
                iterations += 1
            }
            return Argon2idParameters(
                formatVersion = Argon2idParameters.CURRENT_FORMAT_VERSION,
                memoryKiB = Argon2idParameters.MINIMUM_MEMORY_KIB,
                iterations = MAXIMUM_CALIBRATED_ITERATIONS,
                parallelism = parallelism,
                outputBytes = Argon2idParameters.OUTPUT_BYTES,
            )
        } finally {
            password.close()
            salt.fill(0)
        }
    }

    private companion object {
        const val DEFAULT_TARGET_MILLIS = 500L
        const val MINIMUM_TARGET_MILLIS = 250L
        const val MAXIMUM_TARGET_MILLIS = 2_000L
        const val MAXIMUM_CALIBRATED_ITERATIONS = 8
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

class RecoveryWrappedKeyMaterial(
    val envelopeVersion: Int,
    val parameters: Argon2idParameters,
    salt: ByteArray,
    nonce: ByteArray,
    ciphertext: ByteArray,
) {
    private val storedSalt = salt.copyOf()
    private val storedNonce = nonce.copyOf()
    private val storedCiphertext = ciphertext.copyOf()

    val salt: ByteArray
        get() = storedSalt.copyOf()
    val nonce: ByteArray
        get() = storedNonce.copyOf()
    val ciphertext: ByteArray
        get() = storedCiphertext.copyOf()

    init {
        require(envelopeVersion == CURRENT_ENVELOPE_VERSION)
        require(storedSalt.size >= Argon2idDeriver.MINIMUM_SALT_BYTES)
        require(storedNonce.size == WrappedKeyMaterial.GCM_NONCE_BYTES)
        require(storedCiphertext.size >= WrappedKeyMaterial.GCM_TAG_BYTES)
    }

    override fun toString(): String = "RecoveryWrappedKeyMaterial(version=$envelopeVersion,parameters=$parameters,payload=redacted)"

    companion object {
        const val CURRENT_ENVELOPE_VERSION: Int = 1
    }
}

class RecoveryPasswordKeyWrapper(
    private val deriver: Argon2idDeriver = Argon2idDeriver(),
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun wrap(
        password: RecoveryPassword,
        keyMaterial: SecretBytes,
        parameters: Argon2idParameters,
        associatedData: ByteArray,
    ): RecoveryWrappedKeyMaterial {
        val salt = ByteArray(SALT_BYTES).also(secureRandom::nextBytes)
        return try {
            deriver.derive(password, salt, parameters).use { backupKek ->
                backupKek.useBytes { key ->
                    keyMaterial.useBytes { plaintext ->
                        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
                        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
                        cipher.updateAAD(associatedData)
                        RecoveryWrappedKeyMaterial(
                            RecoveryWrappedKeyMaterial.CURRENT_ENVELOPE_VERSION,
                            parameters,
                            salt,
                            cipher.iv,
                            cipher.doFinal(plaintext),
                        )
                    }
                }
            }
        } finally {
            salt.fill(0)
        }
    }

    fun unwrap(
        password: RecoveryPassword,
        envelope: RecoveryWrappedKeyMaterial,
        associatedData: ByteArray,
    ): SecretBytes {
        val salt = envelope.salt
        return try {
            deriver.derive(password, salt, envelope.parameters).use { backupKek ->
                backupKek.useBytes { key ->
                    val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
                    cipher.init(
                        Cipher.DECRYPT_MODE,
                        SecretKeySpec(key, "AES"),
                        GCMParameterSpec(GCM_TAG_BITS, envelope.nonce),
                    )
                    cipher.updateAAD(associatedData)
                    val plaintext = cipher.doFinal(envelope.ciphertext)
                    SecretBytes.copyOf(plaintext).also { plaintext.fill(0) }
                }
            }
        } catch (error: AEADBadTagException) {
            throw SecurityException.RecoveryAuthenticationFailed(error)
        } catch (error: GeneralSecurityException) {
            throw SecurityException.RecoveryAuthenticationFailed(error)
        } finally {
            salt.fill(0)
        }
    }

    private companion object {
        const val SALT_BYTES = 16
        const val GCM_TAG_BITS = 128
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
