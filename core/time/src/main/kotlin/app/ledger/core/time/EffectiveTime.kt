package app.ledger.core.time

import app.ledger.core.common.DomainError
import app.ledger.core.common.DomainResult
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

enum class TemporalErrorKind {
    NONEXISTENT_LOCAL_TIME,
    INVALID_DATE_KEY,
    INVALID_MONTH_KEY,
    INSTANT_OUT_OF_RANGE,
}

data class TemporalError(val kind: TemporalErrorKind) : DomainError {
    override val code: String = "TEMPORAL_${kind.name}"
}

enum class GapPolicy {
    REJECT,
    SHIFT_FORWARD,
}

enum class OverlapPolicy {
    EARLIER_OFFSET,
    LATER_OFFSET,
}

@ConsistentCopyVisibility
data class EffectiveTime private constructor(
    val instant: Instant,
    val zoneId: ZoneId,
    val localDate: LocalDate,
) {
    val zonedDateTime: ZonedDateTime
        get() = instant.atZone(zoneId)

    companion object {
        fun fromInstant(instant: Instant, zoneId: ZoneId): EffectiveTime = EffectiveTime(instant, zoneId, instant.atZone(zoneId).toLocalDate())

        fun resolveLocal(
            localDate: LocalDate,
            localTime: LocalTime,
            zoneId: ZoneId,
            gapPolicy: GapPolicy = GapPolicy.SHIFT_FORWARD,
            overlapPolicy: OverlapPolicy = OverlapPolicy.EARLIER_OFFSET,
        ): DomainResult<EffectiveTime> {
            val localDateTime = LocalDateTime.of(localDate, localTime)
            val offsets = zoneId.rules.getValidOffsets(localDateTime)
            val zoned = when (offsets.size) {
                1 -> ZonedDateTime.ofLocal(localDateTime, zoneId, offsets.single())
                0 -> {
                    if (gapPolicy == GapPolicy.REJECT) {
                        return DomainResult.Failure(TemporalError(TemporalErrorKind.NONEXISTENT_LOCAL_TIME))
                    }
                    val transition = checkNotNull(zoneId.rules.getTransition(localDateTime))
                    localDateTime.plusSeconds(transition.duration.seconds).atZone(zoneId)
                }
                else -> {
                    val preferred = when (overlapPolicy) {
                        OverlapPolicy.EARLIER_OFFSET -> offsets.first()
                        OverlapPolicy.LATER_OFFSET -> offsets.last()
                    }
                    ZonedDateTime.ofLocal(localDateTime, zoneId, preferred)
                }
            }
            return DomainResult.Success(fromInstant(zoned.toInstant(), zoneId))
        }
    }
}
