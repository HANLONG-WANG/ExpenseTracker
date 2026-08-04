from __future__ import annotations

import copy
import unittest

from scripts import validate_p19_credit as validator


class P19CreditMutationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.sources = validator.source_map()

    def mutate(self, filename: str, before: str, after: str) -> list[str]:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith(filename))
        self.assertIn(before, sources[path])
        sources[path] = sources[path].replace(before, after)
        return validator.validate_sources(sources)

    def test_credit_facts_cannot_bypass_coordinator(self) -> None:
        self.assertTrue(
            self.mutate(
                "SecureRoomCreditApplicationPort.kt",
                "DefaultFinancialMutationCoordinator",
                "DirectFinancialWriter",
            )
        )

    def test_actual_debt_overpayment_guard_cannot_be_removed(self) -> None:
        self.assertTrue(
            self.mutate(
                "SecureRoomCreditApplicationPort.kt",
                "authoritativeRemaining",
                "uncheckedRemaining",
            )
        )

    def test_official_statement_nonnegative_rule_cannot_be_removed(self) -> None:
        self.assertTrue(
            self.mutate(
                "CreditInstallmentLoan.kt",
                "officialAmountMinor == null || officialAmountMinor >= 0L",
                "true",
            )
        )

    def test_candidate_mode_cannot_be_removed(self) -> None:
        sources = copy.deepcopy(self.sources)
        token = "AutoGenerationMode.CONFIRMATION_CANDIDATE"
        self.assertTrue(any(token in source for source in sources.values()))
        for path in sources:
            if path.startswith("finance/domain/"):
                sources[path] = sources[path].replace(token, "AutoGenerationMode.FORMAL_TRANSACTION")
        self.assertTrue(validator.validate_sources(sources))

    def test_sensitive_amount_cannot_enter_route(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("CreditRootDestination.kt"))
        sources[path] += '\nprivate val forbidden = encodedArguments["amount"]\n'
        self.assertTrue(validator.validate_sources(sources))

    def test_raw_material_cannot_enter_credit_feature(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("CreditScreens.kt"))
        sources[path] += "\nimport androidx.compose.material3.Button\n"
        self.assertTrue(validator.validate_sources(sources))


if __name__ == "__main__":
    unittest.main()
