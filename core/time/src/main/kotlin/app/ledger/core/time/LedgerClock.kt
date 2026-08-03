package app.ledger.core.time

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

fun interface LedgerClock {
    fun now(): Instant
}

class JavaTimeLedgerClock(private val clock: Clock) : LedgerClock {
    override fun now(): Instant = clock.instant()

    companion object {
        fun systemUtc(): JavaTimeLedgerClock = JavaTimeLedgerClock(Clock.systemUTC())
    }
}

/** Java-time adapter for platform APIs that require Clock while retaining the injected source. */
class InjectedJavaClock(
    private val source: LedgerClock,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = InjectedJavaClock(source, zone)

    override fun instant(): Instant = source.now()
}
