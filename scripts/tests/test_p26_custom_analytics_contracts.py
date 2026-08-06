from __future__ import annotations

import copy
import unittest

from scripts import validate_p26_custom_analytics as validator


class P26CustomAnalyticsMutationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.sources = validator.source_map()

    def mutate(self, filename: str, before: str, after: str) -> list[str]:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith(filename))
        self.assertIn(before, sources[path])
        sources[path] = sources[path].replace(before, after)
        return validator.validate_sources(sources)

    def test_algorithm_version_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("CustomAnalytics.kt", "AnalyticsAlgorithmVersion", "UnversionedAlgorithm"))

    def test_exact_multiplication_cannot_be_weakened(self) -> None:
        self.assertTrue(self.mutate("CustomAnalytics.kt", "Math.multiplyExact", "unsafeMultiply"))

    def test_encrypted_primary_database_cannot_be_bypassed(self) -> None:
        self.assertTrue(self.mutate("SecureRoomAnalyticsApplicationPort.kt", "EncryptedDatabaseFactory.openPrimary", "Room.databaseBuilder"))

    def test_revision_conflict_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("CustomAnalyticsStore.kt", "AnalyticsError.RevisionConflict", "AnalyticsError.DatabaseUnavailable"))

    def test_security_startup_check_cannot_be_pinned_to_schema_v1(self) -> None:
        self.assertTrue(self.mutate("BookSessionManager.kt", "LedgerMigrations.CURRENT_VERSION", "1"))

    def test_accessible_data_table_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("P26AnalysisScreens.kt", "AccessibleTableUiModel", "PlainTable"))

    def test_forecast_route_cannot_accept_formula(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("AnalysisRootDestination.kt"))
        sources[path] += '\nval formula = encodedArguments["formula"]\n'
        self.assertTrue(validator.validate_sources(sources))


if __name__ == "__main__":
    unittest.main()
