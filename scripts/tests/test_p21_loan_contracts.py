from __future__ import annotations

import copy
import unittest

from scripts import validate_p21_loans as validator


class P21LoanMutationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.sources = validator.source_map()

    def mutate(self, filename: str, before: str, after: str) -> list[str]:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith(filename))
        self.assertIn(before, sources[path])
        sources[path] = sources[path].replace(before, after)
        return validator.validate_sources(sources)

    def test_financial_facts_cannot_bypass_coordinator(self) -> None:
        self.assertTrue(self.mutate("SecureRoomLoanApplicationPort.kt", "DefaultFinancialMutationCoordinator", "DirectFinancialWriter"))

    def test_checked_principal_conservation_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("LoanAccountingPolicy.kt", "Math.subtractExact", "uncheckedSubtract"))

    def test_schedule_methods_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("LoanAccountingPolicy.kt", "equalPrincipal", "unsupportedEqualPrincipal"))

    def test_forecasts_cannot_enter_current_transactions(self) -> None:
        self.assertTrue(self.mutate("RoomProjectionEngine.kt", "planned_date > ?", "planned_date >= ?"))

    def test_sensitive_amount_cannot_enter_route(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("LoanRootDestination.kt"))
        sources[path] += '\nprivate val forbidden = encodedArguments["principal"]\n'
        self.assertTrue(validator.validate_sources(sources))

    def test_raw_material_cannot_enter_loan_feature(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("LoanScreens.kt"))
        sources[path] += "\nimport androidx.compose.material3.Button\n"
        self.assertTrue(validator.validate_sources(sources))


if __name__ == "__main__":
    unittest.main()
