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

    fun toLongExact(value: BigInteger): DomainResult<Long> = checked(ArithmeticOperation.CONVERT_TO_LONG) {
        value.longValueExact()
    }

    private inline fun checked(operation: ArithmeticOperation, block: () -> Long): DomainResult<Long> = try {
        DomainResult.Success(block())
    } catch (_: ArithmeticException) {
        DomainResult.Failure(ArithmeticOverflowError(operation))
    }
}
