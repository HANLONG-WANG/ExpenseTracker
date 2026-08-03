from __future__ import annotations

import copy
import unittest

from scripts import validate_p15_journal as validator


class P15JournalMutationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.sources = validator.load_sources()

    def mutate(self, filename: str, before: str, after: str) -> list[str]:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith(filename))
        self.assertIn(before, sources[path])
        sources[path] = sources[path].replace(before, after)
        return validator.validate_sources(sources)

    def test_keyset_cannot_be_replaced_with_offset(self) -> None:
        self.assertTrue(self.mutate("RoomTransactionQueryService.kt", "ctp.occurred_at < ?", "1=1 OFFSET 500000"))

    def test_fts_match_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("RoomTransactionQueryService.kt", "transaction_fts MATCH ?", "transaction_fts = ?"))

    def test_coordinator_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("SecureRoomJournalApplicationPort.kt", "DefaultFinancialMutationCoordinator", "DirectFinancialWriter"))

    def test_physical_purge_cannot_enter_p15(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("SecureRoomJournalApplicationPort.kt"))
        sources[path] += '\nprivate const val forbidden = "DELETE FROM business_transaction"\n'
        self.assertTrue(validator.validate_sources(sources))

    def test_swipe_delete_cannot_enter_feature(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("JournalDestination.kt"))
        sources[path] += "\nprivate val forbidden = SwipeToDismiss\n"
        self.assertTrue(validator.validate_sources(sources))

    def test_forbidden_bulk_amount_cannot_be_removed(self) -> None:
        self.assertTrue(
            self.mutate(
                "JournalApplicationPort.kt",
                'setOf("amount", "direction", "refundRelation", "settlementShare")',
                'setOf("direction", "refundRelation", "settlementShare")',
            )
        )


if __name__ == "__main__":
    unittest.main()
