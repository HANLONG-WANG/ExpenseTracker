package app.ledger.core.common

import java.nio.ByteBuffer
import java.util.UUID

@JvmInline
value class InternalId private constructor(val value: Long) {
    companion object {
        fun of(value: Long): DomainResult<InternalId> = if (value > 0L) {
            DomainResult.Success(InternalId(value))
        } else {
            DomainResult.Failure(ValidationError("internalId", ValidationReason.MUST_BE_POSITIVE))
        }
    }
}

/** Immutable 16-byte UUID representation suitable for a SQLite BLOB. */
class StableId private constructor(bytes: ByteArray) : Comparable<StableId> {
    private val value = bytes.copyOf()

    val bytes: ByteArray
        get() = value.copyOf()

    fun toUuid(): UUID {
        val buffer = ByteBuffer.wrap(value)
        return UUID(buffer.long, buffer.long)
    }

    override fun compareTo(other: StableId): Int {
        for (index in value.indices) {
            val comparison = (value[index].toInt() and UNSIGNED_BYTE_MASK)
                .compareTo(other.value[index].toInt() and UNSIGNED_BYTE_MASK)
            if (comparison != 0) return comparison
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is StableId && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = toUuid().toString()

    companion object {
        const val BYTE_COUNT: Int = 16
        private const val UNSIGNED_BYTE_MASK: Int = 0xff

        fun fromBytes(bytes: ByteArray): DomainResult<StableId> = if (bytes.size == BYTE_COUNT) {
            DomainResult.Success(StableId(bytes))
        } else {
            DomainResult.Failure(ValidationError("stableId", ValidationReason.INVALID_FORMAT))
        }

        fun fromUuid(uuid: UUID): StableId {
            val bytes = ByteBuffer.allocate(BYTE_COUNT)
                .putLong(uuid.mostSignificantBits)
                .putLong(uuid.leastSignificantBits)
                .array()
            return StableId(bytes)
        }

        fun parse(value: String): DomainResult<StableId> = try {
            DomainResult.Success(fromUuid(UUID.fromString(value)))
        } catch (_: IllegalArgumentException) {
            DomainResult.Failure(ValidationError("stableId", ValidationReason.INVALID_FORMAT))
        }
    }
}

fun interface UuidSource {
    fun nextUuid(): UUID
}

fun interface StableIdSource {
    fun nextStableId(): StableId
}

class UuidStableIdSource(private val source: UuidSource) : StableIdSource {
    override fun nextStableId(): StableId = StableId.fromUuid(source.nextUuid())
}
