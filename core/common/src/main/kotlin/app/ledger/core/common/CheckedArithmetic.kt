package app.ledger.core.common

import java.math.BigInteger

object CheckedArithmetic {
    fun add(left: Long, right: Long): DomainResult<Long> = checked(ArithmeticOperation.ADD) {
        Math.addExact(left, right)
    }

    fun subtract(left: Long, right: Long): DomainResult<Long> = checked(ArithmeticOperation.SUBTRACT) {
        Math.subtractExact(left, right)
    }

    fun multiply(left: Long, right: Long): DomainResult<Long> = checked(ArithmeticOperation.MULTIPLY) {
        Math.multiplyExact(left, right)
    }

    fun negate(value: Long): DomainResult<Long> = checked(ArithmeticOperation.NEGATE) {
        Math.negateExact(value)
    }

    fun abs(value: Long): DomainResult<Long> = checked(ArithmeticOperation.ABS) {
        Math.absExact(value)
    }

    fun sum(values: Iterable<Long>): DomainResult<Long> {
        var total = 0L
        for (value in values) {
            try {
                total = Math.addExact(total, value)
            } catch (_: ArithmeticException) {
                return DomainResult.Failure(ArithmeticOverflowError(ArithmeticOperation.ACCUMULATE))
            }
        }
        return DomainResult.Success(total)
    }

    fun sumWide(values: Iterable<Long>): BigInteger {
        var total = BigInteger.ZERO
        for (value in values) {
            total = total.add(BigInteger.valueOf(value))
        }
        return total
    }

    fun toLongExact(value: BigInteger): DomainResult<Long> = if (value < LONG_MIN || value > LONG_MAX) {
        DomainResult.Failure(ArithmeticOverflowError(ArithmeticOperation.CONVERT_TO_LONG))
    } else {
        DomainResult.Success(value.toLong())
    }

    private inline fun checked(operation: ArithmeticOperation, block: () -> Long): DomainResult<Long> = try {
        DomainResult.Success(block())
    } catch (_: ArithmeticException) {
        DomainResult.Failure(ArithmeticOverflowError(operation))
    }

    private val LONG_MIN: BigInteger = BigInteger.valueOf(Long.MIN_VALUE)
    private val LONG_MAX: BigInteger = BigInteger.valueOf(Long.MAX_VALUE)
}
