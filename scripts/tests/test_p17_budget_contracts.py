from __future__ import annotations

import copy
import unittest

from scripts import validate_p17_budget as validator


class P17BudgetMutationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.sources = validator.load_sources()

    def mutate(self, filename: str, before: str, after: str) -> list[str]:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith(filename))
        self.assertIn(before, sources[path])
        sources[path] = sources[path].replace(before, after)
        return validator.validate_sources(sources)

    def test_budget_cannot_bypass_coordinator(self) -> None:
        self.assertTrue(self.mutate("SecureRoomBudgetApplicationPort.kt", "DefaultFinancialMutationCoordinator", "DirectFinancialWriter"))

    def test_hierarchy_invariants_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("BudgetProjectGoal.kt", 'DomainViolation.Invariant("INV-018")', 'DomainViolation.Invariant("BYPASS")'))

    def test_rebuildable_rollover_projection_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("RoomProjectionEngine.kt", "ProjectionChange.BudgetFromMonth", "ProjectionChange.Goal"))

    def test_stale_projection_guard_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("SecureRoomBudgetApplicationPort.kt", "budget_future_reservation", "unversioned_future_reservation"))

    def test_sensitive_amount_cannot_enter_route(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("BudgetRootDestination.kt"))
        sources[path] += '\nprivate val forbidden = encodedArguments["amount"]\n'
        self.assertTrue(validator.validate_sources(sources))

    def test_swipe_delete_cannot_enter_budget_feature(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("BudgetScreens.kt"))
        sources[path] += "\nprivate val forbidden = SwipeToDismiss\n"
        self.assertTrue(validator.validate_sources(sources))


if __name__ == "__main__":
    unittest.main()
