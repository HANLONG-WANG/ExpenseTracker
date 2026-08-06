from __future__ import annotations

import copy
import unittest

from scripts import validate_p23_automation as validator


class P23AutomationMutationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.sources = validator.source_map()

    def mutate(self, filename: str, before: str, after: str) -> list[str]:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith(filename))
        self.assertIn(before, sources[path])
        sources[path] = sources[path].replace(before, after)
        return validator.validate_sources(sources)

    def test_occurrence_uniqueness_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("SecureRoomAutomationApplicationPort.kt", "deriveStableId", "randomOccurrenceId"))

    def test_candidate_fact_boundary_cannot_be_bypassed(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("SecureRoomAutomationApplicationPort.kt"))
        sources[path] += '\ndb.execSQL("INSERT INTO posting")\n'
        self.assertTrue(validator.validate_sources(sources))

    def test_worker_payload_cannot_add_business_fields(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("RecurrenceCatchUpWorker.kt"))
        sources[path] += '\nData.Builder().putString("amount", "1")\n'
        self.assertTrue(validator.validate_sources(sources))

    def test_unique_work_policy_cannot_be_weakened(self) -> None:
        self.assertTrue(self.mutate("RecurrenceCatchUpWorker.kt", "ExistingWorkPolicy.KEEP", "ExistingWorkPolicy.REPLACE"))

    def test_headless_recurrence_lease_cannot_be_removed(self) -> None:
        self.assertTrue(
            self.mutate(
                "AppHeadlessRecurrenceExecutor.kt",
                "HeadlessLeaseCapability.RECURRENCE_WRITE",
                "HeadlessLeaseCapability.BACKUP_READ",
            )
        )

    def test_credit_and_loan_integration_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("AppFormalOccurrenceGenerator.kt", "CreditApplicationPort", "MissingCreditPort"))
        self.assertTrue(self.mutate("AppFormalOccurrenceGenerator.kt", "LoanApplicationPort", "MissingLoanPort"))

    def test_routes_cannot_carry_amount(self) -> None:
        self.assertTrue(self.mutate("AutomationRootDestination.kt", 'stableId("candidateId")', 'stableId("amount")'))

    def test_raw_material_cannot_enter_feature(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("AutomationScreens.kt"))
        sources[path] += "\nimport androidx.compose.material3.Button\n"
        self.assertTrue(validator.validate_sources(sources))

    def test_template_picker_cannot_move_out_of_record_module(self) -> None:
        self.assertTrue(self.mutate("OrdinaryRecordScreens.kt", '"REC-026" -> QuickTemplatePicker', '"REC-026" -> CategoryFirstHome'))


if __name__ == "__main__":
    unittest.main()
