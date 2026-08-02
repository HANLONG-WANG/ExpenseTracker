package app.ledger.finance.application

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class LedgerWriteGateTest {
    @Test
    fun `process local financial writers are strictly serialized`() = runTest {
        val gate = DefaultLedgerWriteGate()
        val active = AtomicInteger()
        val maximum = AtomicInteger()

        (1..32).map {
            async {
                gate.execute {
                    val entered = active.incrementAndGet()
                    maximum.updateAndGet { previous -> maxOf(previous, entered) }
                    delay(1)
                    active.decrementAndGet()
                }
            }
        }.awaitAll()

        maximum.get() shouldBe 1
        active.get() shouldBe 0
    }
}
