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

    def test_detail_must_keep_edit_and_refund_actions(self) -> None:
        self.assertTrue(self.mutate("JournalDestination.kt", "p15_journal_create_refund", "p15_journal_history"))

    def test_attachment_preview_entry_cannot_be_disconnected(self) -> None:
        self.assertTrue(self.mutate("JournalDestination.kt", "actions.onOpenAttachment(attachmentId)", "Unit"))

    def test_bulk_operation_state_cannot_return_to_raw_enum(self) -> None:
        self.assertTrue(self.mutate("JournalDestination.kt", "state.operation.label()", "state.operation.name"))

    def test_trash_and_restore_must_refresh_account_surfaces(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("AppRootViewModel.kt"))
        source = sources[path]
        start = source.index("private fun executeJournalMutation")
        end = source.index("private fun refreshJournalPaging", start)
        sources[path] = source[:start] + source[start:end].replace("loadReferenceDataAfterMutation(bookId)", "") + source[end:]
        self.assertTrue(validator.validate_sources(sources))

    def test_transfer_edit_cannot_fall_back_to_bulk_editor(self) -> None:
        self.assertTrue(
            self.mutate(
                "AppRootViewModel.kt",
                'mapOf("transactionId" to StableIdArgument(transactionId))',
                "emptyMap()",
            )
        )


if __name__ == "__main__":
    unittest.main()
