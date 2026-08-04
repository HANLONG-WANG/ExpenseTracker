from __future__ import annotations

import copy
import unittest

from scripts import validate_p20_installments as validator


class P20InstallmentMutationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.sources = validator.source_map()

    def mutate(self, filename: str, before: str, after: str) -> list[str]:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith(filename))
        self.assertIn(before, sources[path])
        sources[path] = sources[path].replace(before, after)
        return validator.validate_sources(sources)

    def test_installment_facts_cannot_bypass_coordinator(self) -> None:
        self.assertTrue(self.mutate("SecureRoomInstallmentApplicationPort.kt", "DefaultFinancialMutationCoordinator", "DirectFinancialWriter"))

    def test_last_term_tail_absorption_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("InstallmentAccountingPolicy.kt", "if (number == request.termCount) remaining else basePrincipal", "basePrincipal"))

    def test_refund_recalculation_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("InstallmentAccountingPolicy.kt", "recalculateAfterRefund", "ignoreRefund"))

    def test_expected_revision_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("SecureRoomInstallmentApplicationPort.kt", "expectedRevisionId", "uncheckedRevisionId"))

    def test_sensitive_amount_cannot_enter_route(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("InstallmentRootDestination.kt"))
        sources[path] += '\nprivate val forbidden = encodedArguments["amount"]\n'
        self.assertTrue(validator.validate_sources(sources))

    def test_raw_material_cannot_enter_installment_feature(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("InstallmentScreens.kt"))
        sources[path] += "\nimport androidx.compose.material3.Button\n"
        self.assertTrue(validator.validate_sources(sources))


if __name__ == "__main__":
    unittest.main()
