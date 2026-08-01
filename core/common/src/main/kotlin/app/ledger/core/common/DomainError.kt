package app.ledger.core.common

/** Stable, localizable machine errors. Raw exception messages and user data do not belong here. */
interface DomainError {
    val code: String
}

enum class ValidationReason {
    REQUIRED,
    INVALID_FORMAT,
    OUT_OF_RANGE,
    MUST_BE_POSITIVE,
    UNSUPPORTED,
}

data class ErrorPosition(val zeroBasedIndex: Int) {
    init {
        require(zeroBasedIndex >= 0) { "Error position must not be negative" }
    }

    val oneBasedPosition: Int = Math.addExact(zeroBasedIndex, 1)
}

data class ValidationError(
    val field: String,
    val reason: ValidationReason,
    val position: ErrorPosition? = null,
) : DomainError {
    init {
        require(FIELD_KEY.matches(field)) { "Field must be a stable machine key" }
    }

    override val code: String = "VALIDATION_${reason.name}"

    private companion object {
        val FIELD_KEY = Regex("[a-z][a-zA-Z0-9.]{0,63}")
    }
}

data class AccountingInvariantViolation(val invariantId: String) : DomainError {
    init {
        require(INVARIANT_ID.matches(invariantId)) { "Invariant ID must use INV-nnn" }
    }

    override val code: String = "ACCOUNTING_INVARIANT_VIOLATION"

    private companion object {
        val INVARIANT_ID = Regex("INV-[0-9]{3}")
    }
}

enum class ConflictReason {
    STALE_REVISION,
    ALREADY_CHANGED,
    PURGED,
}

data class ConflictError(val reason: ConflictReason) : DomainError {
    override val code: String = "CONFLICT_${reason.name}"
}

enum class CurrencyErrorReason {
    INVALID_CODE,
    UNSUPPORTED_CURRENCY,
    CURRENCY_MISMATCH,
    INVALID_RATE,
    NUMERIC_RANGE,
    FRACTION_NOT_ALLOWED,
}

data class CurrencyError(
    val reason: CurrencyErrorReason,
    val currencyCode: String? = null,
) : DomainError {
    init {
        require(currencyCode == null || CURRENCY_CODE.matches(currencyCode)) {
            "Currency error may contain only a normalized currency code"
        }
    }

    override val code: String = "CURRENCY_${reason.name}"

    private companion object {
        val CURRENCY_CODE = Regex("[A-Z]{3}")
    }
}

enum class BudgetRuleReason {
    CHILD_LIMIT_EXCEEDS_PARENT,
    CATEGORY_TOTAL_EXCEEDS_MONTH,
    INVALID_MONTH,
}

data class BudgetRuleError(val reason: BudgetRuleReason) : DomainError {
    override val code: String = "BUDGET_${reason.name}"
}

enum class ArithmeticOperation {
    ADD,
    SUBTRACT,
    MULTIPLY,
    NEGATE,
    ACCUMULATE,
    CONVERT_TO_LONG,
}

data class ArithmeticOverflowError(val operation: ArithmeticOperation) : DomainError {
    override val code: String = "ARITHMETIC_OVERFLOW_${operation.name}"
}
