package app.ledger.finance.application

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import java.time.Instant

/** Opaque encrypted bytes. Plain card data is intentionally impossible to express at this boundary. */
public class VaultCiphertext private constructor(bytes: ByteArray) {
    private val stored: ByteArray = bytes.copyOf()

    public fun copyBytes(): ByteArray = stored.copyOf()

    override fun equals(other: Any?): Boolean = other is VaultCiphertext && stored.contentEquals(other.stored)
    override fun hashCode(): Int = stored.contentHashCode()
    override fun toString(): String = "VaultCiphertext(redacted,size=${stored.size})"

    public companion object {
        public fun copyOf(bytes: ByteArray): VaultCiphertext {
            require(bytes.isNotEmpty())
            return VaultCiphertext(bytes)
        }
    }
}

public data class VaultSecretRecord(
    val cardId: StableId,
    val holderName: VaultCiphertext?,
    val primaryNumber: VaultCiphertext?,
    val expiry: VaultCiphertext?,
    val securityCode: VaultCiphertext?,
    val customFields: VaultCiphertext?,
    val keyVersion: Int,
    val updatedAt: Instant,
) {
    init {
        require(keyVersion > 0)
        require(listOf(holderName, primaryNumber, expiry, securityCode, customFields).any { it != null })
    }
}

/** SQLCipher application boundary for Vault ciphertext only; it never accepts plaintext. */
public interface VaultSecretApplicationPort {
    public suspend fun listCardIds(bookId: StableId): DomainResult<Set<StableId>>
    public suspend fun read(bookId: StableId, cardId: StableId): DomainResult<VaultSecretRecord?>
    public suspend fun save(bookId: StableId, record: VaultSecretRecord): DomainResult<Unit>
    public suspend fun delete(bookId: StableId, cardId: StableId): DomainResult<Unit>
}
