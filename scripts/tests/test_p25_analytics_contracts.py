from __future__ import annotations

import copy
import unittest

from scripts import validate_p25_analytics as validator


class P25AnalyticsMutationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.sources = validator.source_map()

    def mutate(self, filename: str, before: str, after: str) -> list[str]:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith(filename))
        self.assertIn(before, sources[path])
        sources[path] = sources[path].replace(before, after)
        return validator.validate_sources(sources)

    def test_fixed_catalog_cannot_shrink(self) -> None:
        self.assertTrue(self.mutate("FixedReportCatalog.kt", "definitions.size == 20", "definitions.size == 19"))

    def test_filter_node_bound_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("ReportModel.kt", "MAX_FILTER_NODES = 64", "MAX_FILTER_NODES = Int.MAX_VALUE"))

    def test_bound_parameter_compiler_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("ReportSqlCompiler.kt", "boundValues", "inlineValues"))

    def test_offset_paging_cannot_enter_compiler(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("ReportSqlCompiler.kt"))
        sources[path] += '\nprivate const val unsafe = "OFFSET 500000"\n'
        self.assertTrue(validator.validate_sources(sources))

    def test_fact_rebuild_check_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("SecureRoomAnalyticsApplicationPort.kt", "IntegrityCheckKey.FACT_REBUILD", "IntegrityCheckKey.DATABASE"))

    def test_encrypted_database_cannot_be_replaced_by_plaintext(self) -> None:
        self.assertTrue(
            self.mutate(
                "SecureRoomAnalyticsApplicationPort.kt",
                "LedgerDatabaseOperationAccess",
                "PlaintextDatabaseAccess",
            )
        )

    def test_accessible_data_table_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("AnalysisScreens.kt", "AccessibleTableUiModel", "PlainTextTable"))

    def test_route_cannot_accept_formula(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("AnalysisRootDestination.kt"))
        sources[path] += '\nval formula = encodedArguments["formula"]\n'
        self.assertTrue(validator.validate_sources(sources))


if __name__ == "__main__":
    unittest.main()
