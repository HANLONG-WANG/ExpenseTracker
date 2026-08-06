from __future__ import annotations

import copy
import unittest

from scripts import validate_p24_batch as validator


class P24BatchMutationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.sources = validator.source_map()

    def mutate(self, filename: str, before: str, after: str) -> list[str]:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith(filename))
        self.assertIn(before, sources[path])
        sources[path] = sources[path].replace(before, after)
        return validator.validate_sources(sources)

    def test_coordinator_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("SecureRoomBatchEntryApplicationPort.kt", "DefaultFinancialMutationCoordinator", "DirectDaoWriter"))

    def test_parent_batch_command_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("SecureRoomBatchEntryApplicationPort.kt", "BatchFinancialCommand", "SequentialFinancialCommand"))

    def test_financial_fact_sql_cannot_be_added(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("SecureRoomBatchEntryApplicationPort.kt"))
        sources[path] += '\ndb.execSQL("INSERT INTO posting")\n'
        self.assertTrue(validator.validate_sources(sources))

    def test_batch_draft_cannot_enter_saved_state(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("BatchRecordState.kt"))
        sources[path] += "\nval persisted = SavedStateHandle()\n"
        self.assertTrue(validator.validate_sources(sources))

    def test_row_route_cannot_lose_stable_id(self) -> None:
        self.assertTrue(self.mutate("AppRootViewModel.kt", "StableIdArgument(rowId)", "StringArgument(row.note)"))

    def test_large_table_cannot_be_eagerly_materialized(self) -> None:
        self.assertTrue(self.mutate("BatchRecordScreens.kt", "rowCount = state.rows.size", "rows = state.rows.map"))

    def test_swipe_delete_cannot_enter_batch_ui(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("BatchRecordScreens.kt"))
        sources[path] += "\nSwipeToDismiss()\n"
        self.assertTrue(validator.validate_sources(sources))

    def test_forbidden_bulk_amount_cannot_enter_patch(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("JournalApplicationPort.kt"))
        sources[path] = sources[path].replace(
            "data class JournalBulkEditPatch(\n",
            "data class JournalBulkEditPatch(\n    val amount: JournalFieldUpdate<Long> = JournalFieldUpdate.Unchanged,\n",
        )
        self.assertTrue(validator.validate_sources(sources))


if __name__ == "__main__":
    unittest.main()
