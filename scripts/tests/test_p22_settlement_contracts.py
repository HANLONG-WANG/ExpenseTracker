from __future__ import annotations

import copy
import unittest

from scripts import validate_p22_settlements as validator


class P22SettlementMutationTest(unittest.TestCase):
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
        self.assertTrue(self.mutate("SecureRoomSettlementApplicationPort.kt", "DefaultFinancialMutationCoordinator", "DirectFinancialWriter"))

    def test_checked_allocation_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("SettlementAllocationPolicy.kt", "Math.subtractExact", "uncheckedSubtract"))

    def test_closed_percentage_mode_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("SettlementAllocationPolicy.kt", "PERCENTAGE", "UNSUPPORTED_PERCENTAGE"))

    def test_immutable_payment_record_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("RoomFinancialPlanWriter.kt", "insertSettlementPaymentRecords", "skipSettlementPaymentRecords"))

    def test_additional_settlement_marker_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("SecureRoomSettlementApplicationPort.kt", "requires_additional_settlement", "history_rewritten"))

    def test_participant_id_cannot_be_dropped_from_route(self) -> None:
        self.assertTrue(self.mutate("SettlementRootDestination.kt", 'encodedArguments["participantId"]', 'encodedArguments["amount"]'))

    def test_raw_material_cannot_enter_settlement_feature(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("SettlementScreens.kt"))
        sources[path] += "\nimport androidx.compose.material3.Button\n"
        self.assertTrue(validator.validate_sources(sources))


if __name__ == "__main__":
    unittest.main()
