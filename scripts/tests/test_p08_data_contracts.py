from __future__ import annotations

import unittest

from scripts import validate_p08_data as validator


class P08DataContractMutationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.sources = validator.load_sources()

    def mutated(self, filename: str, old: str, new: str) -> dict[str, str]:
        sources = dict(self.sources)
        path = next(path for path in sources if path.endswith(filename))
        sources[path] = sources[path].replace(old, new)
        return sources

    def test_rejects_transaction_boundary_removal(self) -> None:
        errors = validator.validate_sources(
            self.mutated("RoomFinancialCommitRepository.kt", "database.inLedgerTransaction", "database.readLedger")
        )
        self.assertTrue(any("database.inLedgerTransaction" in error for error in errors))

    def test_rejects_missing_synchronous_projection(self) -> None:
        errors = validator.validate_sources(
            self.mutated("RoomProjectionEngine.kt", "refund_status_projection", "missing_refund_projection")
        )
        self.assertTrue(any("refund_status_projection" in error for error in errors))

    def test_rejects_offset_paging(self) -> None:
        errors = validator.validate_sources(
            self.mutated("RoomTransactionQueryService.kt", "LIMIT ?", "LIMIT ? OFFSET 100")
        )
        self.assertTrue(any("offset paging" in error for error in errors))

    def test_rejects_false_projection_version_stamping(self) -> None:
        errors = validator.validate_sources(
            self.mutated("RoomProjectionEngine.kt", "canonicalHash", "stampExistingDeferredProjections")
        )
        self.assertTrue(any("falsely stamps" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
