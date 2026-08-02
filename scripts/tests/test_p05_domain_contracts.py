from __future__ import annotations

import csv
import unittest
from io import StringIO

from scripts.validate_p05_domain import (
    TARGET_REQUIREMENTS,
    load_sources,
    validate_requirement_rows,
    validate_source_contract,
)


class P05DomainContractMutationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.sources = load_sources()

    def mutate(self, path_suffix: str, before: str, after: str) -> dict[str, str]:
        mutated = dict(self.sources)
        path = next(path for path in mutated if path.endswith(path_suffix))
        self.assertIn(before, mutated[path])
        mutated[path] = mutated[path].replace(before, after, 1)
        return mutated

    def test_rejects_optional_expense_category(self) -> None:
        mutated = self.mutate(
            "TransactionModel.kt",
            "override val classification: CategoryAssignment,",
            "override val classification: CategoryAssignment?,",
        )
        self.assertTrue(any("ExpensePayload" in error for error in validate_source_contract(mutated)))

    def test_rejects_public_unchecked_account_amount(self) -> None:
        mutated = self.mutate(
            "DomainIdentityAndLifecycle.kt",
            "data class AccountAmount private constructor(",
            "data class AccountAmount(",
        )
        self.assertTrue(any("AccountAmount" in error for error in validate_source_contract(mutated)))

    def test_rejects_generic_domain_property_bag(self) -> None:
        mutated = dict(self.sources)
        mutated["finance/domain/src/main/kotlin/Injected.kt"] = (
            "package app.ledger.finance.domain\n"
            "data class UniversalTransaction(val payload: Map<String, Any?>)\n"
        )
        self.assertTrue(any("generic JSON" in error for error in validate_source_contract(mutated)))

    def test_rejects_missing_domain_aggregate(self) -> None:
        mutated = self.mutate("BudgetProjectGoal.kt", "data class Goal(\n", "data class RemovedGoal(\n")
        self.assertTrue(any("Goal" in error for error in validate_source_contract(mutated)))

    def test_rejects_requirement_regression_or_missing_p05_evidence(self) -> None:
        fieldnames = [
            "requirement_id",
            "source_section",
            "summary",
            "screens_flows",
            "core_components",
            "acceptance_criteria",
            "status",
            "implementation_evidence",
            "verification_evidence",
            "primary_acceptance_phase",
            "follow_up_review_phases",
            "notes",
        ]
        rows = []
        for value in range(1, 91):
            requirement_id = f"REQ-{value:03d}"
            rows.append(
                {
                    key: (
                        requirement_id
                        if key == "requirement_id"
                        else "NOT_STARTED"
                        if key == "status" and requirement_id in TARGET_REQUIREMENTS
                        else "P05"
                        if key == "implementation_evidence"
                        else ""
                    )
                    for key in fieldnames
                }
            )
        self.assertTrue(validate_requirement_rows(rows))


if __name__ == "__main__":
    unittest.main()
