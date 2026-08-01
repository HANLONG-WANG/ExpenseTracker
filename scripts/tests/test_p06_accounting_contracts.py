from __future__ import annotations

import unittest

from scripts.validate_p06_accounting import load_sources, validate_sources


class P06AccountingContractMutationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.sources = load_sources()

    def mutate(self, suffix: str, before: str, after: str) -> dict[str, str]:
        mutated = dict(self.sources)
        path = next(path for path in mutated if path.endswith(suffix))
        self.assertIn(before, mutated[path])
        mutated[path] = mutated[path].replace(before, after, 1)
        return mutated

    def test_rejects_missing_transaction_rule(self) -> None:
        mutated = self.mutate(
            "AccountingRuleEngine.kt",
            "is FxExchangePayload -> session.fxExchange(payload)",
            "is FxExchangePayload -> session.transfer(payload as TransferPayload)",
        )
        self.assertTrue(any("rule closure" in error for error in validate_sources(mutated)))

    def test_rejects_nondeterministic_planner_clock(self) -> None:
        mutated = dict(self.sources)
        mutated["finance/domain/src/main/kotlin/app/ledger/finance/domain/Injected.kt"] = (
            "package app.ledger.finance.domain\nval forbiddenPlannerNow = java.time.Instant.now()\n"
        )
        self.assertTrue(any("clock" in error for error in validate_sources(mutated)))

    def test_rejects_floating_authoritative_amount(self) -> None:
        mutated = dict(self.sources)
        mutated["finance/domain/src/main/kotlin/app/ledger/finance/domain/Injected.kt"] = (
            "package app.ledger.finance.domain\ndata class FloatingPosting(val amount: Double)\n"
        )
        self.assertTrue(any("floating money" in error for error in validate_sources(mutated)))

    def test_rejects_missing_effects_from_commit_hash(self) -> None:
        mutated = self.mutate(
            "CanonicalFinancialHash.kt",
            "FINANCIAL_EVIDENCE_AND_EFFECTS_V1",
            "REMOVED_EFFECT_HASH_DOMAIN",
        )
        self.assertTrue(any("hash coverage" in error for error in validate_sources(mutated)))

    def test_rejects_idempotency_lookup_before_canonical_check(self) -> None:
        mutated = self.mutate(
            "FinancialMutationCoordinator.kt",
            "CanonicalFinancialHash.command(command) != command.payloadHash",
            "CanonicalFinancialHash.command(command) == command.payloadHash",
        )
        self.assertTrue(any("canonical" in error for error in validate_sources(mutated)))


if __name__ == "__main__":
    unittest.main()
