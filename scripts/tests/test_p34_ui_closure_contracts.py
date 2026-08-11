from __future__ import annotations

import copy
import unittest

import yaml

from scripts import validate_p34_ui_closure as validator


class P34UiClosureMutationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.screens = yaml.safe_load(validator.SCREEN_SOURCE.read_text(encoding="utf-8"))["screens"]
        cls.coverage = validator.read_csv(validator.SCREEN_LEDGER)
        cls.sources = validator.kotlin_sources()
        cls.tests = validator.android_test_sources()

    def test_missing_required_state_is_rejected(self) -> None:
        screens = copy.deepcopy(self.screens)
        screens[0]["requiredStates"].pop()
        self.assertTrue(validator.validate_screen_contract(screens, self.coverage))

    def test_coverage_route_drift_is_rejected(self) -> None:
        coverage = copy.deepcopy(self.coverage)
        coverage[0]["route"] = "unsafe/arbitrary"
        self.assertTrue(validator.validate_screen_contract(self.screens, coverage))

    def test_missing_translation_is_rejected(self) -> None:
        module = "core/designsystem"
        locale = "ja"
        mutated = validator.resource_entries(validator.resource_directory(module, locale))
        mutated.pop(next(iter(mutated)))
        self.assertTrue(validator.validate_localization({(module, locale): mutated}))

    def test_format_argument_drift_is_rejected(self) -> None:
        module = "app"
        locale = "en"
        mutated = validator.resource_entries(validator.resource_directory(module, locale))
        key = ("string", "global_recovery_diagnostic")
        mutated[key] = ("Diagnostic code: %1$d",)
        self.assertTrue(validator.validate_localization({(module, locale): mutated}))

    def test_hard_coded_selection_semantics_are_rejected(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("FoundationComponents.kt"))
        sources[path] = sources[path].replace(
            "stateDescription = localizedSelectionState",
            'stateDescription = "selected"',
            1,
        )
        self.assertTrue(validator.validate_ui_governance(sources, self.tests))

    def test_missing_foldable_preview_is_rejected(self) -> None:
        tests = copy.deepcopy(self.tests)
        path = next(path for path in tests if path.endswith("DesignSystemDeviceTest.kt"))
        tests[path] = tests[path].replace("RenderCase(600, 2f", "RenderCase(599, 2f")
        self.assertTrue(validator.validate_ui_governance(self.sources, tests))

    def test_sensitive_amount_semantic_guard_is_rejected_when_removed(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("BusinessComponents.kt"))
        self.assertIn("if (hidden) stringResource(R.string.ledger_amount_hidden)", sources[path])
        sources[path] = sources[path].replace(
            "if (hidden) stringResource(R.string.ledger_amount_hidden)",
            "if (hidden) model.fullAccessibleText",
        )
        self.assertTrue(validator.validate_ui_governance(sources, self.tests))

    def test_anonymous_composable_page_is_rejected(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("MoreRootScreen.kt"))
        sources[path] += "\nprivate val unsafePage = @Composable { LedgerText(\"unsafe\", LedgerTextRole.BODY) }\n"
        self.assertTrue(validator.validate_ui_governance(sources, self.tests))

    def test_date_sensitive_widget_cache_in_canonical_hash_is_rejected(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("RoomProjectionEngine.kt"))
        sources[path] = sources[path].replace(
            "val HASH_QUERIES = listOf(",
            'val HASH_QUERIES = listOf(\n            "widget_book_snapshot" to "SELECT * FROM widget_book_snapshot",',
            1,
        )
        self.assertTrue(validator.validate_ui_governance(sources, self.tests))


if __name__ == "__main__":
    unittest.main()
