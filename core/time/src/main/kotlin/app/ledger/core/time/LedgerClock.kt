package app.ledger.core.time

import java.time.Clock
import java.time.Instant

fun interface LedgerClock {
    fun now(): Instant
}

class JavaTimeLedgerClock(private val clock: Clock) : LedgerClock {
    override fun now(): Instant = clock.instant()

    companion object {
        fun systemUtc(): JavaTimeLedgerClock = JavaTimeLedgerClock(Clock.systemUTC())
    }
}
