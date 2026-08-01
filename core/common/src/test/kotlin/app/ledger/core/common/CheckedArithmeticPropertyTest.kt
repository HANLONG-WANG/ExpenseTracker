package app.ledger.core.common

import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigInteger

class CheckedArithmeticPropertyTest {
    @Test
    fun `checked addition agrees with an unbounded integer oracle`() = runTest {
        checkAll(iterations = 1_000, Arb.long(), Arb.long()) { left, right ->
            val oracle = BigInteger.valueOf(left).add(BigInteger.valueOf(right))
            val expected = if (
                oracle >= BigInteger.valueOf(Long.MIN_VALUE) && oracle <= BigInteger.valueOf(Long.MAX_VALUE)
            ) {
                DomainResult.Success(oracle.longValueExact())
            } else {
                DomainResult.Failure(ArithmeticOverflowError(ArithmeticOperation.ADD))
            }

            CheckedArithmetic.add(left, right) shouldBe expected
        }
    }

    @Test
    fun `every accumulator detects Long overflow and retains a wide alternative`() {
        val values = listOf(Long.MAX_VALUE, 1L, Long.MIN_VALUE, -1L)

        CheckedArithmetic.sum(values.take(2)) shouldBe DomainResult.Failure(
            ArithmeticOverflowError(ArithmeticOperation.ACCUMULATE),
        )
        CheckedArithmetic.sumWide(values) shouldBe BigInteger.valueOf(-1L)
        CheckedArithmetic.toLongExact(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)) shouldBe
            DomainResult.Failure(ArithmeticOverflowError(ArithmeticOperation.CONVERT_TO_LONG))
    }

    @Test
    fun `subtraction multiplication and negation reject their extreme overflows`() {
        CheckedArithmetic.subtract(Long.MIN_VALUE, 1L) shouldBe DomainResult.Failure(
            ArithmeticOverflowError(ArithmeticOperation.SUBTRACT),
        )
        CheckedArithmetic.multiply(Long.MAX_VALUE, 2L) shouldBe DomainResult.Failure(
            ArithmeticOverflowError(ArithmeticOperation.MULTIPLY),
        )
        CheckedArithmetic.negate(Long.MIN_VALUE) shouldBe DomainResult.Failure(
            ArithmeticOverflowError(ArithmeticOperation.NEGATE),
        )
        CheckedArithmetic.abs(Long.MIN_VALUE) shouldBe DomainResult.Failure(
            ArithmeticOverflowError(ArithmeticOperation.ABS),
        )
    }

    @Test
    fun `checked absolute value agrees with an unbounded integer oracle`() = runTest {
        checkAll(iterations = 1_000, Arb.long()) { value ->
            val oracle = BigInteger.valueOf(value).abs()
            val expected = if (oracle <= BigInteger.valueOf(Long.MAX_VALUE)) {
                DomainResult.Success(oracle.longValueExact())
            } else {
                DomainResult.Failure(ArithmeticOverflowError(ArithmeticOperation.ABS))
            }

            CheckedArithmetic.abs(value) shouldBe expected
        }
    }
}
