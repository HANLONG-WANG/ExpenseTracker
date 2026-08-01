package app.ledger.finance.domain

import app.ledger.core.common.DomainError
import app.ledger.core.money.CurrencyCode

sealed interface DomainViolation : DomainError {
    data class NumericOverflow(val field: String) : DomainViolation {
        override val code: String = "DOMAIN_NUMERIC_OVERFLOW"
    }

    data class CurrencyMismatch(
        val expected: CurrencyCode,
        val actual: CurrencyCode,
    ) : DomainViolation {
        override val code: String = "DOMAIN_CURRENCY_MISMATCH"
    }

    data class ArchivedReference(val entityType: String) : DomainViolation {
        override val code: String = "DOMAIN_ARCHIVED_REFERENCE"
    }

    data class Invariant(val invariantId: String) : DomainViolation {
        init {
            require(INVARIANT_PATTERN.matches(invariantId))
        }

        override val code: String = "DOMAIN_INVARIANT_$invariantId"
    }

    data class DependencyPolicyRequired(val dependencyType: TransactionDependencyType) : DomainViolation {
        override val code: String = "DOMAIN_DEPENDENCY_POLICY_REQUIRED"
    }

    data object StaleExpectedRevision : DomainViolation {
        override val code: String = "DOMAIN_STALE_EXPECTED_REVISION"
    }

    data object DuplicateCommandPayloadMismatch : DomainViolation {
        override val code: String = "DOMAIN_DUPLICATE_COMMAND_PAYLOAD_MISMATCH"
    }

    data class InvalidStateTransition(val aggregateType: String) : DomainViolation {
        override val code: String = "DOMAIN_INVALID_STATE_TRANSITION"
    }

    data class InvalidField(val field: String) : DomainViolation {
        override val code: String = "DOMAIN_INVALID_FIELD"
    }

    private companion object {
        val INVARIANT_PATTERN = Regex("INV-[0-9]{3}")
    }
}
