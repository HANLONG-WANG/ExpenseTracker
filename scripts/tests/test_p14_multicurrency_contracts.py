from __future__ import annotations

import copy
import unittest

from scripts import validate_p14_multicurrency as validator


class P14MulticurrencyMutationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.sources = validator.load_sources()

    def mutate(self, filename: str, before: str, after: str) -> list[str]:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith(filename))
        self.assertIn(before, sources[path])
        sources[path] = sources[path].replace(before, after)
        return validator.validate_sources(sources)

    def test_internal_transfer_route_cannot_point_to_data_transfer(self) -> None:
        self.assertTrue(self.mutate("OrdinaryRecordScreens.kt", '"REC-013"', '"TRF-001"'))

    def test_financial_coordinator_cannot_be_removed(self) -> None:
        self.assertTrue(
            self.mutate(
                "SecureRoomSpecializedTransactionEntryPort.kt",
                "DefaultFinancialMutationCoordinator",
                "DirectFinancialWriter",
            )
        )

    def test_current_rate_refresh_cannot_advance_local_revision(self) -> None:
        self.assertTrue(
            self.mutate(
                "SecureRoomSpecializedTransactionEntryPort.kt",
                "UPDATE book SET valuation_revision=?",
                "UPDATE book SET local_revision=?",
            )
        )

    def test_historical_quote_cannot_replace_current_valuation(self) -> None:
        self.assertTrue(
            self.mutate(
                "SecureRoomSpecializedTransactionEntryPort.kt",
                "request.effectiveDate == online.quote.fetchedAt.atZone(ZoneOffset.UTC).toLocalDate()",
                "request.effectiveDate != online.quote.fetchedAt.atZone(ZoneOffset.UTC).toLocalDate()",
            )
        )

    def test_network_request_cannot_carry_amount(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("FxQuoteNetworkClient.kt"))
        sources[path] = sources[path].replace("date: LocalDate?,", "date: LocalDate?,\n    val amountMinor: Long,")
        self.assertTrue(validator.validate_sources(sources))

    def test_immutable_checkpoint_fact_cannot_be_updated(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("SecureRoomSpecializedTransactionEntryPort.kt"))
        sources[path] += '\nprivate const val forbidden = "UPDATE account_balance_checkpoint SET adjustment_transaction_id=?"\n'
        self.assertTrue(validator.validate_sources(sources))


if __name__ == "__main__":
    unittest.main()
