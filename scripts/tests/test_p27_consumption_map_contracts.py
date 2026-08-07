from __future__ import annotations

import copy
import unittest

from scripts import validate_p27_consumption_map as validator


class P27ConsumptionMapMutationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.sources = validator.source_map()

    def mutate(self, filename: str, before: str, after: str) -> list[str]:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith(filename))
        self.assertIn(before, sources[path])
        sources[path] = sources[path].replace(before, after)
        return validator.validate_sources(sources)

    def test_rtree_candidate_query_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("ConsumptionMapStore.kt", "FROM location_rtree", "FROM location_record"))

    def test_render_population_bound_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("ConsumptionMap.kt", "MAX_RENDERED_POINTS: Int = 512", "MAX_RENDERED_POINTS: Int = Int.MAX_VALUE"))

    def test_default_special_transaction_exclusions_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("ConsumptionMap.kt", "TransactionKind.CREDIT_PAYMENT", "TransactionKind.INCOME"))

    def test_sequential_teal_heatmap_cannot_be_replaced_by_risk_color(self) -> None:
        self.assertTrue(self.mutate("ChartsMapsAndRiskComponents.kt", "sequentialTeal", "riskSemanticColors"))

    def test_accessible_data_table_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("P27AnalysisScreens.kt", "AccessibleDataTable", "PlainLocationList"))

    def test_map_route_cannot_accept_coordinates(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("AnalysisRootDestination.kt"))
        sources[path] += '\nval coordinate = encodedArguments["latitude"]\n'
        self.assertTrue(validator.validate_sources(sources))

    def test_online_reverse_geocoder_cannot_be_added(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("AnalysisController.kt"))
        sources[path] += "\nval geocoder = Geocoder(context)\n"
        self.assertTrue(validator.validate_sources(sources))

    def test_same_dimension_or_accumulation_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("ConsumptionMapFilterRemoval.kt", "current + candidate", "setOf(candidate)"))


if __name__ == "__main__":
    unittest.main()
