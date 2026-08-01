package app.ledger.core.testing

import app.cash.turbine.test
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class JvmTestStackTest {
    private interface AsyncProbe {
        suspend fun values(): Flow<Int>
    }

    @Test
    fun `JUnit MockK coroutines test and Turbine share one harness`() = runTest {
        val probe = mockk<AsyncProbe>()
        coEvery { probe.values() } returns flowOf(2, 4, 8)

        probe.values().test {
            awaitItem() shouldBe 2
            awaitItem() shouldBe 4
            awaitItem() shouldBe 8
            awaitComplete()
        }
    }

    @Test
    fun `Kotest property runner executes on JUnit platform`() = runTest {
        checkAll(iterations = 100, Arb.int()) { value ->
            (value.toLong() + value.toLong()) shouldBe value.toLong() * 2L
        }
    }
}
