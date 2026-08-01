package app.ledger.core.time

import app.ledger.core.common.DomainResult
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class EffectiveTimeAndClockTest {
    @Test
    fun `injected clock is deterministic`() {
        val expected = Instant.parse("2026-08-01T00:00:00Z")
        val clock = JavaTimeLedgerClock(Clock.fixed(expected, ZoneOffset.UTC))

        clock.now() shouldBe expected
    }

    @Test
    fun `one instant keeps original zone and can cross local calendar days`() {
        val instant = Instant.parse("2026-01-01T00:30:00Z")
        val tokyo = EffectiveTime.fromInstant(instant, ZoneId.of("Asia/Tokyo"))
        val losAngeles = EffectiveTime.fromInstant(instant, ZoneId.of("America/Los_Angeles"))

        tokyo.localDate shouldBe LocalDate.of(2026, 1, 1)
        losAngeles.localDate shouldBe LocalDate.of(2025, 12, 31)
        tokyo.instant shouldBe losAngeles.instant
    }

    @Test
    fun `DST gaps reject by default and explicit shifts carry provenance`() {
        val date = LocalDate.of(2026, 3, 8)
        val time = LocalTime.of(2, 30)
        val newYork = ZoneId.of("America/New_York")

        EffectiveTime.resolveLocal(date, time, newYork) shouldBe
            DomainResult.Failure(TemporalError(TemporalErrorKind.NONEXISTENT_LOCAL_TIME))
        val shifted = (
            EffectiveTime.resolveLocal(date, time, newYork, gapPolicy = GapPolicy.SHIFT_FORWARD) as DomainResult.Success
            ).value
        shifted.zonedDateTime.toLocalTime() shouldBe LocalTime.of(3, 30)
        shifted.localDate shouldBe date
        shifted.adjustment shouldBe TemporalAdjustment(
            kind = TemporalAdjustmentKind.DST_GAP_SHIFT_FORWARD,
            requestedLocalDateTime = date.atTime(time),
            resolvedLocalDateTime = date.atTime(3, 30),
            shiftedSeconds = 3_600L,
        )
    }

    @Test
    fun `DST overlaps select earlier or later offsets explicitly`() {
        val date = LocalDate.of(2026, 11, 1)
        val time = LocalTime.of(1, 30)
        val newYork = ZoneId.of("America/New_York")
        val earlier = (
            EffectiveTime.resolveLocal(
                date,
                time,
                newYork,
                overlapPolicy = OverlapPolicy.EARLIER_OFFSET,
            ) as DomainResult.Success
            ).value
        val later = (
            EffectiveTime.resolveLocal(
                date,
                time,
                newYork,
                overlapPolicy = OverlapPolicy.LATER_OFFSET,
            ) as DomainResult.Success
            ).value

        later.instant.epochSecond - earlier.instant.epochSecond shouldBe 3_600L
        earlier.zonedDateTime.offset shouldBe ZoneOffset.ofHours(-4)
        later.zonedDateTime.offset shouldBe ZoneOffset.ofHours(-5)
    }
}
