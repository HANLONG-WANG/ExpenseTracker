from __future__ import annotations

import copy
import unittest

from scripts import validate_p18_project_goal as validator


class P18ProjectGoalMutationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.sources = validator.load_sources()

    def mutate(self, filename: str, before: str, after: str) -> list[str]:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith(filename))
        self.assertIn(before, sources[path])
        sources[path] = sources[path].replace(before, after)
        return validator.validate_sources(sources)

    def test_goal_movement_cannot_bypass_coordinator(self) -> None:
        self.assertTrue(self.mutate("SecureRoomProjectGoalApplicationPort.kt", "DefaultFinancialMutationCoordinator", "DirectFinancialWriter"))

    def test_negative_availability_checked_arithmetic_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("BudgetProjectGoal.kt", "Math.subtractExact(actualBalanceMinor, reservedMinor)", "actualBalanceMinor - reservedMinor"))

    def test_monthly_snapshot_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("AccountingRuleEngine.kt", "includedInMonthlyBudgetSnapshot", "mutableMonthlyPolicy"))

    def test_project_paging_cannot_use_offset(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("SecureRoomProjectGoalApplicationPort.kt"))
        sources[path] += '\nprivate const val forbidden = " OFFSET 40"\n'
        self.assertTrue(validator.validate_sources(sources))

    def test_sensitive_amount_cannot_enter_route(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("ProjectGoalRootDestination.kt"))
        sources[path] += '\nprivate val forbidden = encodedArguments["amount"]\n'
        self.assertTrue(validator.validate_sources(sources))

    def test_swipe_delete_cannot_enter_feature(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("ProjectGoalScreens.kt"))
        sources[path] += "\nprivate val forbidden = SwipeToDismiss\n"
        self.assertTrue(validator.validate_sources(sources))


if __name__ == "__main__":
    unittest.main()
