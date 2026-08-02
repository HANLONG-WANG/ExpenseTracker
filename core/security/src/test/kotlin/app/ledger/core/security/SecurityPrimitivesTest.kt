package app.ledger.core.security

import app.ledger.core.common.StableId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.UUID

class SecurityPrimitivesTest {
    @Test
    fun `Tink AEAD and streaming AEAD bind ciphertext to associated data`() {
        val bookId = stableId(1)
        val blobId = stableId(2)
        val associatedData = SecurityAssociatedData.attachmentKey(bookId, blobId, 1)
        val wrongAssociatedData = SecurityAssociatedData.attachmentKey(bookId, blobId, 2)

        LedgerTink.generateAeadKeyset().use { serialized ->
            val primitive = serialized.useBytes(LedgerTink::aead)
            val plaintext = "secure-settings-value".toByteArray()
            val ciphertext = primitive.encrypt(plaintext, associatedData)
            primitive.decrypt(ciphertext, associatedData).toList() shouldContainExactly plaintext.toList()
            shouldThrow<Exception> { primitive.decrypt(ciphertext, wrongAssociatedData) }
        }

        LedgerTink.generateStreamingAeadKeyset().use { serialized ->
            val primitive = serialized.useBytes(LedgerTink::streamingAead)
            val plaintext = ByteArray(1_200_000) { index -> (index % 251).toByte() }
            val encrypted = ByteArrayOutputStream()
            LedgerTink.encryptStream(primitive, encrypted, associatedData) { it.write(plaintext) }
            val decrypted = ByteArrayOutputStream()
            LedgerTink.decryptStream(
                primitive,
                ByteArrayInputStream(encrypted.toByteArray()),
                associatedData,
            ) { input -> input.copyTo(decrypted) }
            decrypted.toByteArray().toList() shouldContainExactly plaintext.toList()
            shouldThrow<Exception> {
                LedgerTink.decryptStream(
                    primitive,
                    ByteArrayInputStream(encrypted.toByteArray()),
                    wrongAssociatedData,
                ) { input -> input.readBytes() }
            }
        }
    }

    @Test
    fun `recovery password has no alternate path and binds book plus schema`() {
        val random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(ByteArray(32) { it.toByte() }) }
        val wrapper = RecoveryPasswordKeyWrapper(secureRandom = random)
        val material = SecretBytes.copyOf(ByteArray(32) { (it + 11).toByte() })
        val passwordChars = "CorrectHorse9".toCharArray()
        val password = RecoveryPassword.copyOf(passwordChars)
        passwordChars.fill('\u0000')
        val associatedData = SecurityAssociatedData.recoveryBundle(stableId(3), 1)
        val envelope = wrapper.wrap(password, material, Argon2idParameters.minimum(), associatedData)

        wrapper.unwrap(password, envelope, associatedData).use { restored ->
            restored.useBytes { it.toList() shouldContainExactly ByteArray(32) { index -> (index + 11).toByte() }.toList() }
        }
        val wrongChars = "IncorrectHorse8".toCharArray()
        val wrongPassword = RecoveryPassword.copyOf(wrongChars)
        wrongChars.fill('\u0000')
        shouldThrow<SecurityException.RecoveryAuthenticationFailed> {
            wrapper.unwrap(wrongPassword, envelope, associatedData)
        }
        shouldThrow<SecurityException.RecoveryAuthenticationFailed> {
            wrapper.unwrap(password, envelope, SecurityAssociatedData.recoveryBundle(stableId(3), 2))
        }

        password.close()
        wrongPassword.close()
        material.close()
        associatedData.fill(0)
    }

    @Test
    fun `sensitive wrappers redact and enforce bounded lifetime`() {
        val source = byteArrayOf(1, 2, 3, 4)
        val secret = SecretBytes.copyOf(source)
        source.fill(9)
        secret.useBytes { it.toList() shouldContainExactly listOf<Byte>(1, 2, 3, 4) }
        secret.toString() shouldNotContain "1, 2, 3, 4"
        secret.close()
        shouldThrow<IllegalStateException> { secret.useBytes { it.size } }

        var now = 100L
        val registry = VaultExposureRegistry { now }
        val plaintext = registry.register(byteArrayOf(7, 8, 9))
        plaintext.remainingMillis() shouldBe 30_000L
        registry.activeCount() shouldBe 1
        now += 30_000L
        registry.clearExpired()
        registry.activeCount() shouldBe 0
        shouldThrow<IllegalStateException> { plaintext.useBytes { it.size } }

        registry.register(byteArrayOf(4, 5, 6))
        registry.onApplicationBackgrounded()
        registry.activeCount() shouldBe 0
        registry.register(byteArrayOf(4, 5, 6))
        registry.onApplicationLocked()
        registry.activeCount() shouldBe 0
    }

    @Test
    fun `vault associated data separates field card book and schema`() {
        val book = stableId(4)
        val card = stableId(5)
        val baseline = SecurityAssociatedData.vaultField(book, card, VaultFieldType.PAN, 1)

        baseline.contentEquals(SecurityAssociatedData.vaultField(book, card, VaultFieldType.PAN, 1)) shouldBe true
        baseline.contentEquals(SecurityAssociatedData.vaultField(book, card, VaultFieldType.SECURITY_CODE, 1)) shouldBe false
        baseline.contentEquals(SecurityAssociatedData.vaultField(book, stableId(6), VaultFieldType.PAN, 1)) shouldBe false
        baseline.contentEquals(SecurityAssociatedData.vaultField(stableId(7), card, VaultFieldType.PAN, 1)) shouldBe false
        baseline.contentEquals(SecurityAssociatedData.vaultField(book, card, VaultFieldType.PAN, 2)) shouldBe false
    }

    private fun stableId(index: Long): StableId = StableId.fromUuid(UUID(0L, index))
}
