from __future__ import annotations

import copy
import unittest

from scripts import validate_p16_refunds as validator


class P16RefundMutationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.sources = validator.load_sources()

    def mutate(self, filename: str, before: str, after: str) -> list[str]:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith(filename))
        self.assertIn(before, sources[path])
        sources[path] = sources[path].replace(before, after)
        return validator.validate_sources(sources)

    def test_refund_cannot_bypass_coordinator(self) -> None:
        self.assertTrue(self.mutate("SecureRoomRefundApplicationPort.kt", "DefaultFinancialMutationCoordinator", "DirectFinancialWriter"))

    def test_immutable_allocation_fact_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("TransactionModel.kt", "RefundAllocationFact", "MutableRefundAllocation"))

    def test_refund_projection_rebuild_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("RoomProjectionEngine.kt", "ProjectionChange.Refund", "ProjectionChange.BudgetFromMonth"))

    def test_high_risk_second_confirmation_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("RefundState.kt", "confirmExcessRisk", "silentlyAllowExcess"))

    def test_sensitive_amount_cannot_enter_route(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("RefundRootDestination.kt"))
        sources[path] += '\nprivate val forbidden = encodedArguments["amount"]\n'
        self.assertTrue(validator.validate_sources(sources))

    def test_swipe_delete_cannot_enter_refund_feature(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("RefundScreens.kt"))
        sources[path] += "\nprivate val forbidden = SwipeToDismiss\n"
        self.assertTrue(validator.validate_sources(sources))


if __name__ == "__main__":
    unittest.main()
