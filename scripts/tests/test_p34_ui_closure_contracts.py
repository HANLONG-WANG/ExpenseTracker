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

    def test_chart_explorer_wrap_contract_is_rejected_when_removed(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("ChartsMapsAndRiskComponents.kt"))
        sources[path] = sources[path].replace(
            "Math.floorMod(selectedPointIndex - 1, points.size)",
            "(selectedPointIndex - 1).coerceAtLeast(0)",
            1,
        )
        self.assertTrue(validator.validate_ui_governance(sources, self.tests))

    def test_feature_owned_navigation_provider_loss_is_rejected(self) -> None:
        sources = copy.deepcopy(self.sources)
        route_paths = [path for path in sources if path.endswith("Routes.kt") and "EntryProviderScope<LedgerDestinationKey>" in sources[path]]
        self.assertGreaterEqual(len(route_paths), 10)
        for path in route_paths[:2]:
            sources[path] = sources[path].replace("EntryProviderScope<LedgerDestinationKey>", "LegacyRouteScope")
        self.assertTrue(validator.validate_ui_governance(sources, self.tests))

    def test_anomaly_save_regression_into_scrolling_list_is_rejected(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("P26AnalysisScreens.kt"))
        marker = "modifier = Modifier.fillMaxSize().testTag(LedgerTestTags.ANOMALY_RULES),\n        formContent = true,\n        fixedAction = {"
        self.assertIn(marker, sources[path])
        sources[path] = sources[path].replace(marker, marker.replace("fixedAction", "scrollingAction"), 1)
        self.assertTrue(validator.validate_ui_governance(sources, self.tests))

    def test_test_only_talkback_mock_is_rejected(self) -> None:
        tests = copy.deepcopy(self.tests)
        path = next(path for path in tests if path.endswith("A11yP34TalkBackDeviceTest.kt"))
        tests[path] = tests[path].replace("createAndroidComposeRule<MainActivity>()", "createComposeRule()", 1)
        self.assertTrue(validator.validate_ui_governance(self.sources, tests))

    def test_missing_critical_compose_interaction_is_rejected(self) -> None:
        tests = copy.deepcopy(self.tests)
        marker = "batchCommitWithAnyValidationErrorDispatchesNoWrite"
        path = next(path for path, text in tests.items() if marker in text)
        tests[path] = tests[path].replace(marker, "batchRenderOnlyFixture", 1)
        self.assertTrue(validator.validate_ui_governance(self.sources, tests))

    def test_record_production_location_wiring_regression_is_rejected(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("AppRootViewModel.kt"))
        marker = 'target == "REC-009" && editor?.locationPresentation == RecordLocationEditorState.Locating'
        self.assertIn(marker, sources[path])
        sources[path] = sources[path].replace(marker, 'target == "REC-009" && false', 1)
        self.assertTrue(validator.validate_ui_governance(sources, self.tests))

    def test_typed_import_preview_formatting_regression_is_rejected(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("ImportController.kt"))
        marker = "formatPreviewValue(field.value)"
        self.assertIn(marker, sources[path])
        sources[path] = sources[path].replace(marker, "field.value.toString()", 1)
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

    def test_journal_sticky_header_regression_is_rejected(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("JournalDestination.kt"))
        self.assertIn("stickyHeader(", sources[path])
        sources[path] = sources[path].replace("stickyHeader(", "item(", 1)
        self.assertTrue(validator.validate_ui_governance(sources, self.tests))

    def test_project_transaction_kind_localization_regression_is_rejected(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("ProjectGoalScreens.kt"))
        marker = "transaction.kind.projectTransactionLabel()"
        self.assertIn(marker, sources[path])
        sources[path] = sources[path].replace(marker, "transaction.kind.name", 1)
        self.assertTrue(validator.validate_ui_governance(sources, self.tests))

    def test_opening_migration_presentation_disconnect_is_rejected(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("AppRootViewModel.kt"))
        marker = "schemaVersionMarker.migrationExpected()"
        self.assertIn(marker, sources[path])
        sources[path] = sources[path].replace(marker, "false", 1)
        self.assertTrue(validator.validate_ui_governance(sources, self.tests))

    def test_more_help_entry_disconnect_is_rejected(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("MoreRootScreen.kt"))
        marker = "FeatureEntry(stringResource(R.string.global_help), stringResource(R.string.global_help_explanation), onHelp)"
        self.assertIn(marker, sources[path])
        sources[path] = sources[path].replace(marker, marker.replace("onHelp)", "onAbout)"), 1)
        self.assertTrue(validator.validate_ui_governance(sources, self.tests))

    def test_batch_independent_editor_regression_is_rejected(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("BatchRecordScreens.kt"))
        self.assertIn("TransactionEditorScaffold(", sources[path])
        sources[path] = sources[path].replace("TransactionEditorScaffold(", "LazyColumn(", 1)
        self.assertTrue(validator.validate_ui_governance(sources, self.tests))

    def test_analytics_stale_table_audit_disconnect_is_rejected(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("RoomProjectionEngine.kt"))
        marker = "AnalyticsProjectionEngine.staleTables(database, localRevision)"
        self.assertIn(marker, sources[path])
        sources[path] = sources[path].replace(marker, "emptySet()", 1)
        self.assertTrue(validator.validate_ui_governance(sources, self.tests))

    def test_analysis_complete_export_action_disconnect_is_rejected(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith("P26AnalysisScreens.kt"))
        self.assertIn("actions.onPrepareExport", sources[path])
        sources[path] = sources[path].replace("actions.onPrepareExport", "actions.onExport", 1)
        self.assertTrue(validator.validate_ui_governance(sources, self.tests))

    def test_manual_review_report_mutation_is_rejected(self) -> None:
        findings = validator.MANUAL_FINDINGS.read_text(encoding="utf-8") + "\n"
        progress = validator.MANUAL_PROGRESS.read_text(encoding="utf-8")
        self.assertTrue(validator.validate_manual_findings_progress(findings, progress))

    def test_missing_manual_finding_cross_reference_is_rejected(self) -> None:
        findings = validator.MANUAL_FINDINGS.read_text(encoding="utf-8")
        progress = validator.MANUAL_PROGRESS.read_text(encoding="utf-8").replace("JRN-001", "JRN-XXX")
        self.assertTrue(validator.validate_manual_findings_progress(findings, progress))


if __name__ == "__main__":
    unittest.main()
