from __future__ import annotations

import copy
import base64
import gzip
import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from scripts import generate_p37_interactive_performance_evidence as generator
from scripts import validate_p37_interactive_performance as gate


class P37InteractivePerformanceContractsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.budgets = json.loads(gate.BUDGET_PATH.read_text(encoding="utf-8"))

    def test_current_contract_passes_without_device_evidence(self) -> None:
        self.assertEqual([], gate.validate_all(include_evidence=False))

    def test_rejects_reduced_sample_counts(self) -> None:
        weakened = copy.deepcopy(self.budgets)
        weakened["samplePolicy"]["warmSamplesMin"] = 29
        weakened["samplePolicy"]["coldSamplesMin"] = 4
        errors = gate.validate_budgets(weakened)
        self.assertTrue(any("warm sample" in error for error in errors))
        self.assertTrue(any("cold sample" in error for error in errors))

    def test_rejects_silently_relaxed_latency_budget(self) -> None:
        weakened = copy.deepcopy(self.budgets)
        weakened["latencyMillis"]["ordinarySaveP95Max"] = 751
        self.assertTrue(any("ordinarySave" in error for error in gate.validate_budgets(weakened)))

    def test_rejects_frame_only_benchmark(self) -> None:
        errors = gate.validate_measurement_wiring(benchmark="FrameTimingMetric()", provider="", activity="", view_model="")
        self.assertTrue(any("benchmark marker" in error for error in errors))

    def test_rejects_window_only_fully_drawn(self) -> None:
        activity = "reportFullyDrawn()\ncurrentRouteContentReadyGeneration.collect"
        errors = gate.validate_measurement_wiring(benchmark="", provider="", activity=activity, view_model="")
        self.assertIn("reportFullyDrawn is not gated by current-route content", errors)

    def test_rejects_content_readiness_without_session_generation_stamp(self) -> None:
        view_model = gate.read("app/src/main/kotlin/app/ledger/app/AppRootViewModel.kt").replace(
            "mutableTopLevelContentGenerations",
            "unversionedTopLevelContent",
        )
        errors = gate.validate_measurement_wiring(view_model=view_model)
        self.assertTrue(any("current-generation content gate" in error for error in errors))

    def test_rejects_unlock_without_an_authoritative_content_trace(self) -> None:
        view_model = gate.read("app/src/main/kotlin/app/ledger/app/AppRootViewModel.kt").replace(
            "LedgerInteractionOperation.UNLOCK_TO_CONTENT",
            "LedgerInteractionOperation.UNLOCK",
        )
        errors = gate.validate_measurement_wiring(view_model=view_model)
        self.assertTrue(any("interaction trace is unused" in error for error in errors))

        benchmark = gate.read("benchmark/src/main/kotlin/app/ledger/benchmark/P35Macrobenchmark.kt").replace(
            'TraceSectionMetric("P37/unlock_to_content"',
            'TraceSectionMetric("P37/unlock"',
        )
        errors = gate.validate_measurement_wiring(benchmark=benchmark)
        self.assertTrue(any("benchmark marker missing" in error for error in errors))

        benchmark = gate.read("benchmark/src/main/kotlin/app/ledger/benchmark/P35Macrobenchmark.kt").replace(
            'requireResource(device, "record_content")',
            'requireResource(device, "record_root")',
            1,
        )
        errors = gate.validate_measurement_wiring(benchmark=benchmark)
        self.assertTrue(any("cold unlock" in error for error in errors))

        benchmark = gate.read("benchmark/src/main/kotlin/app/ledger/benchmark/P35Macrobenchmark.kt").replace(
            "SystemClock.sleep(P37_COLD_CONTENT_OBSERVER_DELAY_MILLIS)",
            "Unit",
            1,
        )
        errors = gate.validate_measurement_wiring(benchmark=benchmark)
        self.assertTrue(any("cold unlock authoritative-content" in error for error in errors))

    def test_rejects_warm_setup_that_recreates_or_bypasses_the_retained_activity(self) -> None:
        source = gate.read("benchmark/src/main/kotlin/app/ledger/benchmark/P35Macrobenchmark.kt")
        benchmark = source.replace(
            "launchP37WarmTargetForSetup(device)",
            "launchTargetForSetup(device)",
            1,
        )
        errors = gate.validate_measurement_wiring(benchmark=benchmark)
        self.assertTrue(any("retained Activity setup" in error for error in errors))

        benchmark = source.replace("0x34000000", "0x10008000", 1)
        errors = gate.validate_measurement_wiring(benchmark=benchmark)
        self.assertTrue(any("warm setup" in error for error in errors))

        benchmark = source.replace("unwindP37NestedDestinationForSetup(device)", "Unit", 1)
        errors = gate.validate_measurement_wiring(benchmark=benchmark)
        self.assertTrue(any("retained-Activity warm setup marker" in error for error in errors))

        benchmark = source.replace("repeat(P37_MAX_NESTED_BACK_STEPS)", "while (true)", 1)
        errors = gate.validate_measurement_wiring(benchmark=benchmark)
        self.assertTrue(any("retained-Activity warm setup marker" in error for error in errors))

    def test_rejects_search_benchmark_without_description_center_fallback(self) -> None:
        source = gate.read("benchmark/src/main/kotlin/app/ledger/benchmark/P35Macrobenchmark.kt")
        benchmark = source.replace(
            'clickDescription(device, "Search transactions")',
            'clickText(device, "Search transactions")',
            1,
        )
        errors = gate.validate_measurement_wiring(benchmark=benchmark)
        self.assertTrue(any("content description" in error for error in errors))

        benchmark = source.replace("candidate.visibleCenter", "candidate.parent.visibleCenter", 1)
        errors = gate.validate_measurement_wiring(benchmark=benchmark)
        self.assertTrue(any("content-description click fallback" in error for error in errors))

        benchmark = source.replace("candidate.contentDescription?.contains(description) == true", "candidate.text == description", 1)
        errors = gate.validate_measurement_wiring(benchmark=benchmark)
        self.assertTrue(any("content-description click fallback" in error for error in errors))

        benchmark = source.replace("catch (stale: StaleObjectException)", "catch (stale: IllegalStateException)", 1)
        errors = gate.validate_measurement_wiring(benchmark=benchmark)
        self.assertTrue(any("content-description click fallback" in error for error in errors))

    def test_rejects_unbounded_interactive_loader(self) -> None:
        async_content = gate.read("finance/application/src/main/kotlin/app/ledger/finance/application/AsyncContent.kt").replace(
            "withTimeoutOrNull(timeoutMillis)",
            "run",
        )
        errors = gate.validate_measurement_wiring(async_content=async_content)
        self.assertTrue(any("bounded interactive loader" in error for error in errors))

    def test_rejects_ordinary_record_content_reused_across_load_keys(self) -> None:
        view_model = gate.read("app/src/main/kotlin/app/ledger/app/AppRootViewModel.kt").replace(
            "?.takeIf { currentOrdinaryLoadKey == key }",
            "",
            1,
        )
        errors = gate.validate_measurement_wiring(view_model=view_model)
        self.assertTrue(any("complete load key" in error for error in errors))

    def test_rejects_pending_search_that_hides_its_paging_collector(self) -> None:
        destination = gate.read(
            "feature/journal/src/main/kotlin/app/ledger/feature/journal/JournalDestination.kt"
        ).replace(
            "blockingLoadingLabel = stringResource(R.string.p15_journal_loading).takeIf { state.searchPending }",
            "blockingLoadingLabel = null",
        )
        errors = gate.validate_measurement_wiring(journal_destination=destination)
        self.assertTrue(any("paging collector active" in error for error in errors))

    def test_rejects_blocking_progress_that_uses_synchronized_object_click(self) -> None:
        benchmark = gate.read("benchmark/src/main/kotlin/app/ledger/benchmark/P35Macrobenchmark.kt").replace(
            "device.click(center.x, center.y)",
            "target.click().let { true }",
        )
        errors = gate.validate_measurement_wiring(benchmark=benchmark)
        self.assertTrue(any("benchmark marker missing" in error for error in errors))

    def test_rejects_blocking_progress_without_target_frame_completion(self) -> None:
        journal = gate.read(
            "feature/journal/src/main/kotlin/app/ledger/feature/journal/JournalDestination.kt"
        ).replace(
            "withFrameNanos { onFirstResponsePresented() }",
            "onFirstResponsePresented()",
        )
        errors = gate.validate_measurement_wiring(journal_destination=journal)
        self.assertTrue(any("blocking progress target-frame gate missing" in error for error in errors))

    def test_rejects_journal_work_started_before_the_loading_frame(self) -> None:
        view_model = gate.read("app/src/main/kotlin/app/ledger/app/AppRootViewModel.kt").replace(
            "TopLevelDestination.JOURNAL -> Unit",
            "TopLevelDestination.JOURNAL -> loadJournal()",
        )
        errors = gate.validate_measurement_wiring(view_model=view_model)
        self.assertTrue(any("blocking progress target-frame gate missing" in error for error in errors))

        view_model = gate.read("app/src/main/kotlin/app/ledger/app/AppRootViewModel.kt").replace(
            "LedgerSessionPerformance.completeBlockingProgress()\n        val state",
            "val state",
        )
        errors = gate.validate_measurement_wiring(view_model=view_model)
        self.assertTrue(any("blocking progress target-frame gate missing" in error for error in errors))

        root_screen = gate.read("app/src/main/kotlin/app/ledger/app/AppRootScreen.kt").replace(
            "onFirstResponsePresented = { viewModel.onJournalFirstResponsePresented(screenId) }",
            "onFirstResponsePresented = LedgerSessionPerformance::completeBlockingProgress",
        )
        errors = gate.validate_measurement_wiring(root_screen=root_screen)
        self.assertTrue(any("blocking progress target-frame gate missing" in error for error in errors))

    def test_rejects_eager_journal_bulk_options_on_the_initial_route(self) -> None:
        view_model = gate.read("app/src/main/kotlin/app/ledger/app/AppRootViewModel.kt").replace(
            "val presets = (journalApplicationPort.savedFilters(bookId) as? DomainResult.Success)?.value.orEmpty()",
            """val presets = (journalApplicationPort.savedFilters(bookId) as? DomainResult.Success)?.value.orEmpty()
                journalApplicationPort.bulkEditOptions(bookId)""",
            1,
        )
        errors = gate.validate_measurement_wiring(view_model=view_model)
        self.assertTrue(any("eagerly loads bulk options" in error for error in errors))

    def test_rejects_journal_route_readiness_before_the_paging_frame(self) -> None:
        journal = gate.read(
            "feature/journal/src/main/kotlin/app/ledger/feature/journal/JournalDestination.kt"
        ).replace(
            "if (items.loadState.refresh is LoadState.NotLoading) actions.onPagePresented()",
            "actions.onPagePresented()",
        )
        errors = gate.validate_measurement_wiring(journal_destination=journal)
        self.assertTrue(any("target-frame gate missing" in error for error in errors))

        view_model = gate.read("app/src/main/kotlin/app/ledger/app/AppRootViewModel.kt").replace(
            "completeJournalSearchTrace(request, successful)",
            "if (successful) markTopLevelContent(request.bookId, TopLevelDestination.JOURNAL)\n        completeJournalSearchTrace(request, successful)",
            1,
        )
        errors = gate.validate_measurement_wiring(view_model=view_model)
        self.assertTrue(any("before the Paging frame" in error for error in errors))

    def test_rejects_blocking_progress_observer_inside_the_latency_gate(self) -> None:
        benchmark = gate.read("benchmark/src/main/kotlin/app/ledger/benchmark/P35Macrobenchmark.kt").replace(
            "SystemClock.sleep(P37_BLOCKING_PROGRESS_OBSERVER_DELAY_MILLIS)",
            "Unit",
        )
        errors = gate.validate_measurement_wiring(benchmark=benchmark)
        self.assertTrue(any("benchmark marker missing" in error for error in errors))

    def test_rejects_navigation_observers_inside_their_latency_gates(self) -> None:
        benchmark = gate.read("benchmark/src/main/kotlin/app/ledger/benchmark/P35Macrobenchmark.kt").replace(
            "SystemClock.sleep(P37_COLD_CONTENT_OBSERVER_DELAY_MILLIS)",
            "Unit",
        )
        errors = gate.validate_measurement_wiring(benchmark=benchmark)
        self.assertTrue(any("cold unlock authoritative-content" in error for error in errors))

        benchmark = gate.read("benchmark/src/main/kotlin/app/ledger/benchmark/P35Macrobenchmark.kt").replace(
            "SystemClock.sleep(P37_CACHED_CONTENT_OBSERVER_DELAY_MILLIS)",
            "Unit",
        )
        errors = gate.validate_measurement_wiring(benchmark=benchmark)
        self.assertTrue(any("benchmark marker missing" in error for error in errors))

        benchmark = gate.read("benchmark/src/main/kotlin/app/ledger/benchmark/P35Macrobenchmark.kt").replace(
            "SystemClock.sleep(P37_UNCACHED_CONTENT_OBSERVER_DELAY_MILLIS)",
            "Unit",
        )
        errors = gate.validate_measurement_wiring(benchmark=benchmark)
        self.assertTrue(any("benchmark marker missing" in error for error in errors))

    def test_rejects_unjoined_or_competing_post_content_work(self) -> None:
        application = gate.read("app/src/main/kotlin/app/ledger/app/LedgerApplication.kt").replace(
            "awaitFinancialDependentWork()",
            "Unit",
        )
        errors = gate.validate_measurement_wiring(application=application)
        self.assertTrue(any("post-content background-work gate" in error for error in errors))

        application = gate.read("app/src/main/kotlin/app/ledger/app/LedgerApplication.kt").replace(
            "interactiveContentWork()",
            "Unit",
        )
        errors = gate.validate_measurement_wiring(application=application)
        self.assertTrue(any("post-content background-work gate" in error for error in errors))

        view_model = gate.read("app/src/main/kotlin/app/ledger/app/AppRootViewModel.kt").replace(
            "schedulePostFirstContentWork(ready.bookId, ready.generation, target)",
            "Unit",
        )
        errors = gate.validate_measurement_wiring(view_model=view_model)
        self.assertTrue(any("current-generation content gate" in error for error in errors))

        recurrence = gate.read("app/src/main/kotlin/app/ledger/app/RecurrenceCatchUpWorker.kt").replace(
            ".setInitialDelay(PERIODIC_HOURS, TimeUnit.HOURS)",
            ".setInitialDelay(0, TimeUnit.HOURS)",
        )
        errors = gate.validate_measurement_wiring(recurrence_worker=recurrence)
        self.assertTrue(any("startup recurrence quiescence" in error for error in errors))

    def test_rejects_warm_setup_without_authoritative_activity_launch(self) -> None:
        benchmark = gate.read("benchmark/src/main/kotlin/app/ledger/benchmark/P35Macrobenchmark.kt").replace(
            '"am start -W -a android.intent.action.MAIN',
            '"am start -a android.intent.action.MAIN',
        )
        errors = gate.validate_measurement_wiring(benchmark=benchmark)
        self.assertTrue(any("benchmark marker" in error for error in errors))

    def test_rejects_journal_direct_key_unwrap_or_unclosed_session_key(self) -> None:
        journal = gate.read(
            "finance/data/src/main/kotlin/app/ledger/finance/data/SecureRoomJournalApplicationPort.kt"
        ).replace("sessionAccess.withCurrentSecureSettings", "keyProvider.open(")
        errors = gate.validate_measurement_wiring(journal_port=journal)
        self.assertTrue(any("Journal retains direct key-provider access" in error for error in errors))

        manager = gate.read(
            "core/security/src/main/kotlin/app/ledger/core/security/BookSessionManager.kt"
        ).replace("openedSecureSettings?.close()", "Unit")
        errors = gate.validate_measurement_wiring(session_manager=manager)
        self.assertTrue(any("session secure-settings lifecycle" in error for error in errors))

    def test_rejects_journal_page_enrichment_that_abandons_the_projection_uid_index(self) -> None:
        journal = gate.read(
            "finance/data/src/main/kotlin/app/ledger/finance/data/SecureRoomJournalApplicationPort.kt"
        ).replace(
            '"WHERE ctp.transaction_uid IN ($placeholders)"',
            '"WHERE bt.uid IN ($placeholders)"',
        )
        errors = gate.validate_measurement_wiring(journal_port=journal)
        self.assertTrue(any("full projection" in error for error in errors))

    def test_rejects_journal_state_keyset_migration_or_target_scale_gate_drift(self) -> None:
        schema = gate.read("core/database/src/main/assets/ledger_schema_v6_journal_keyset.sql").replace(
            "ix_current_transaction_state_keyset",
            "ix_unrelated_order",
        )
        self.assertTrue(gate.validate_journal_query_plan_remediation(schema_migration=schema))

        post_validation = gate.read(
            "core/database/src/main/kotlin/app/ledger/core/database/MigrationPostValidation.kt"
        ).replace(
            "if (targetVersion < JOURNAL_STATE_KEYSET_VERSION) return true to true",
            "if (targetVersion < LedgerSchemaDefinition.PRIMARY_VERSION) return true to true",
        )
        self.assertTrue(gate.validate_journal_query_plan_remediation(post_validation=post_validation))

        target_test = gate.read(
            "finance/data/src/androidTest/kotlin/app/ledger/finance/data/RoomFinancialDataDeviceTest.kt"
        ).replace("activeElapsed < 750L", "activeElapsed < 5_000L")
        self.assertTrue(gate.validate_journal_query_plan_remediation(target_scale_test=target_test))

    def test_rejects_missing_v6_room_migration_asset(self) -> None:
        schema_export = gate.read("core/database/schemas/app.ledger.core.database.LedgerDatabase/6.json")
        self.assertTrue(
            gate.validate_journal_query_plan_remediation(
                schema_export=schema_export,
                schema_test_asset=schema_export.replace('"version": 6', '"version": 5'),
            ),
        )

    def test_rejects_unbounded_reference_page_plan_or_missing_v7_asset(self) -> None:
        schema = gate.read("core/database/src/main/assets/ledger_schema_v7_reference_keysets.sql").replace(
            "ix_current_transaction_merchant",
            "ix_unrelated_merchant",
        )
        self.assertTrue(gate.validate_journal_query_plan_remediation(reference_schema_migration=schema))

        reference_port = gate.read(
            "finance/data/src/main/kotlin/app/ledger/finance/data/SecureRoomReferenceDataManagementPort.kt"
        ).replace(
            "FROM current_transaction_projection ctp INDEXED BY ix_current_transaction_merchant",
            "FROM current_transaction_projection ctp",
            1,
        )
        self.assertTrue(gate.validate_journal_query_plan_remediation(reference_port=reference_port))

        target_test = gate.read(
            "finance/data/src/androidTest/kotlin/app/ledger/finance/data/RoomFinancialDataDeviceTest.kt"
        ).replace("referenceElapsed < 750L", "referenceElapsed < 5_000L")
        self.assertTrue(gate.validate_journal_query_plan_remediation(target_scale_test=target_test))

        schema_export = gate.read("core/database/schemas/app.ledger.core.database.LedgerDatabase/7.json")
        self.assertTrue(
            gate.validate_journal_query_plan_remediation(
                latest_schema_export=schema_export,
                latest_schema_test_asset=schema_export.replace('"version": 7', '"version": 6'),
            ),
        )

    def test_rejects_unbounded_or_unproven_ordinary_save_projections(self) -> None:
        projection = gate.read(
            "finance/data/src/main/kotlin/app/ledger/finance/data/RoomProjectionEngine.kt"
        )
        self.assertTrue(
            gate.validate_journal_query_plan_remediation(
                projection_engine=projection.replace(
                    "AnalyticsProjectionEngine.applyCommitDeltas(",
                    "AnalyticsProjectionEngine.rebuildDates(",
                    1,
                ),
            ),
        )
        self.assertTrue(
            gate.validate_journal_query_plan_remediation(
                projection_engine=projection.replace(
                    'else -> " AND ee.uid IN (${economicEffectUids.joinToString(",") { "?" }})"',
                    'else -> ""',
                    1,
                ),
            ),
        )
        self.assertTrue(
            gate.validate_journal_query_plan_remediation(
                projection_engine=projection.replace(
                    "applyBudgetEffectDeltas(database, plan.budgetEffects, revision)",
                    "rebuildBudget(database, revision)",
                    1,
                ),
            ),
        )
        self.assertTrue(
            gate.validate_journal_query_plan_remediation(
                projection_engine=projection.replace(
                    'WHERE je.uid IN (${chunk.joinToString(",") { "?" }})',
                    "WHERE je.created_commit_id=?",
                    1,
                ),
            ),
        )

        target_test = gate.read(
            "finance/data/src/androidTest/kotlin/app/ledger/finance/data/RoomFinancialDataDeviceTest.kt"
        )
        self.assertTrue(
            gate.validate_journal_query_plan_remediation(
                target_scale_test=target_test.replace(
                    "targetScaleSaveElapsed < 750L",
                    "targetScaleSaveElapsed < 5_000L",
                    1,
                ),
            ),
        )
        self.assertTrue(
            gate.validate_journal_query_plan_remediation(
                target_scale_test=target_test.replace("TARGET_BUDGET_EFFECT_BASE", "UNRELATED_EFFECT_BASE"),
            ),
        )

        benchmark_fixture = gate.read(
            "app/src/benchmark/kotlin/app/ledger/app/P35BenchmarkFixtureProvider.kt"
        )
        self.assertTrue(
            gate.validate_journal_query_plan_remediation(
                benchmark_fixture=benchmark_fixture.replace(
                    "seedExactInteractiveProjections(database, refs)",
                    "Unit",
                    1,
                ),
            ),
        )
        self.assertTrue(
            gate.validate_journal_query_plan_remediation(
                benchmark_fixture=benchmark_fixture.replace(
                    "actual.normal_balance_minor=expected.normal_balance_minor",
                    "actual.normal_balance_minor=-500000",
                    1,
                ),
            ),
        )
        self.assertTrue(
            gate.validate_journal_query_plan_remediation(
                benchmark_fixture=benchmark_fixture.replace(
                    "actual.used_minor=expected.used_minor",
                    "actual.used_minor=500000",
                    1,
                ),
            ),
        )
        self.assertTrue(
            gate.validate_journal_query_plan_remediation(
                benchmark_fixture=benchmark_fixture.replace(
                    "actual.amount_base_minor=(SELECT COUNT(*) FROM current_transaction_projection",
                    "actual.amount_base_minor=500000 AND 1=(SELECT COUNT(*) FROM current_transaction_projection",
                    1,
                ),
            ),
        )

        commit_repository = gate.read(
            "finance/data/src/main/kotlin/app/ledger/finance/data/RoomFinancialCommitRepository.kt"
        )
        self.assertTrue(
            gate.validate_journal_query_plan_remediation(
                commit_repository=commit_repository.replace(
                    '"SELECT COUNT(*) FROM journal_entry WHERE uid IN "',
                    '"SELECT COUNT(*) FROM journal_entry WHERE created_commit_id=? "',
                    1,
                ),
            ),
        )

    def test_rejects_activity_recreation_that_unlocks_an_existing_ready_session(self) -> None:
        view_model = gate.read("app/src/main/kotlin/app/ledger/app/AppRootViewModel.kt").replace(
            "AppUnlockTransitionPolicy.mayOpenWithoutAuthentication(manager.state.value, saved.appLockEnabled)",
            "!saved.appLockEnabled",
        )
        errors = gate.validate_measurement_wiring(view_model=view_model)
        self.assertTrue(any("process session reattachment" in error for error in errors))

        manager = gate.read(
            "core/security/src/main/kotlin/app/ledger/core/security/BookSessionManager.kt"
        ).replace("idempotent attachment outcomes", "strict duplicate rejection")
        errors = gate.validate_measurement_wiring(session_manager=manager)
        self.assertTrue(any("process session reattachment" in error for error in errors))

    def test_rejects_port_local_mutex_gate(self) -> None:
        errors = gate.validate_architecture_cleanup({"Port.kt": "private class BudgetWriteGate : LedgerWriteGate"})
        self.assertTrue(any("Port-local write gate" in error for error in errors))

    def valid_result(self) -> dict:
        scenarios = {}
        for name, (_, _, sample_kind) in gate.SCENARIO_POLICIES.items():
            count = 5 if sample_kind == "cold" else 30
            samples = [1.0] * count
            scenarios[name] = {
                "unit": "ms",
                "samples": count,
                "samplesMillis": samples,
                **gate.expected_statistics(samples),
                "timeouts": 0,
                "failedSamples": 0,
            }
        digest = "0" * 64
        return {
            "schemaVersion": 2,
            "apiLevel": 28,
            "fixtureMarker": "P35_TARGET_SCALE_V1",
            "measurementPolicy": {"percentileMethod": "nearest-rank", "timeoutsAreFailures": True},
            "environment": {
                "host": {
                    "operatingSystem": "test",
                    "kernel": "test",
                    "machine": "x86",
                    "cpuModel": "test",
                },
                "device": {"version": {"sdk": 28}, "fingerprint": "test", "supportedAbis": ["x86"]},
                "benchmark": {"cpuCoreCount": 4},
            },
            "source": {
                "androidxBenchmarkDataSha256": digest,
                "instrumentationResultLog": {"sha256": digest},
                "targetApk": {"sha256": digest},
                "benchmarkApk": {"sha256": digest},
                "counterLogs": [{"sha256": digest}],
                "counterObservations": {
                    "uiUnlock": 1,
                    "warmInteraction": 5,
                    "ordinarySave": 30,
                    "journalQueryCounts": 1,
                },
            },
            "scenarios": scenarios,
            "deterministicCounters": {
                "uiUnlockPrimaryOpen": 1,
                "uiUnlockDatabaseKeyUnwrap": 1,
                "warmInteractionPrimaryOpenDelta": 0,
                "warmInteractionDatabaseKeyUnwrapDelta": 0,
                "ordinarySaveFinancialTransactions": 1,
                "journalPageSqlStatements": 3,
                "journalPageWithRunningBalanceSqlStatements": 4,
            },
        }

    def test_rejects_non_monotonic_or_over_budget_results(self) -> None:
        result = self.valid_result()
        samples = [751.0] * 30
        result["scenarios"]["ordinarySave"].update(
            {"samplesMillis": samples, **gate.expected_statistics(samples)}
        )
        errors = gate.validate_result(result, self.budgets, 28)
        self.assertTrue(any("ordinarySave exceeds" in error for error in errors))

    def test_rejects_missing_commit_propagation_and_raw_samples(self) -> None:
        result = self.valid_result()
        del result["scenarios"]["ordinarySavePropagation"]
        del result["scenarios"]["ordinarySaveCommit"]["samplesMillis"]
        errors = gate.validate_result(result, self.budgets, 28)
        self.assertTrue(any("missing scenario: ordinarySavePropagation" in error for error in errors))
        self.assertTrue(any("ordinarySaveCommit lacks reproducible raw samples" in error for error in errors))

    def test_rejects_blocking_progress_maximum_over_budget(self) -> None:
        result = self.valid_result()
        samples = [101.0] * 30
        result["scenarios"]["blockingProgressVisible"].update(
            {"samplesMillis": samples, **gate.expected_statistics(samples)}
        )
        errors = gate.validate_result(result, self.budgets, 28)
        self.assertTrue(any("blockingProgressVisible exceeds max" in error for error in errors))

    def test_rejects_cross_api_or_current_apk_identity_drift(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "target.apk"
            benchmark = root / "benchmark.apk"
            target.write_bytes(b"target-candidate")
            benchmark.write_bytes(b"benchmark-candidate")

            def artifact(path: Path) -> dict[str, str | int]:
                payload = path.read_bytes()
                return {
                    "path": path.name,
                    "bytes": len(payload),
                    "sha256": hashlib.sha256(payload).hexdigest(),
                }

            results = {
                28: {"source": {"targetApk": artifact(target), "benchmarkApk": artifact(benchmark)}},
                36: {"source": {"targetApk": artifact(target), "benchmarkApk": artifact(benchmark)}},
            }
            self.assertEqual([], gate.validate_same_candidate_artifacts(results, root))

            cross_api_drift = copy.deepcopy(results)
            cross_api_drift[36]["source"]["benchmarkApk"]["sha256"] = "0" * 64
            self.assertTrue(
                any("do not share" in error for error in gate.validate_same_candidate_artifacts(cross_api_drift, root)),
            )

            benchmark.write_bytes(b"rebuilt-benchmark-candidate")
            self.assertTrue(
                any("current benchmarkApk" in error for error in gate.validate_same_candidate_artifacts(results, root)),
            )

    def test_api28_embedded_report_requires_complete_chunks_and_sha256(self) -> None:
        raw = b'{"context":{},"benchmarks":[]}'
        encoded = base64.b64encode(gzip.compress(raw)).decode("ascii")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "test-results.log"
            path.write_text(
                "\n".join(
                    (
                        "INSTRUMENTATION_RESULT: p37BenchmarkDataStatus=ok",
                        "INSTRUMENTATION_RESULT: p37BenchmarkDataEncoding=gzip+base64",
                        f"INSTRUMENTATION_RESULT: p37BenchmarkDataSha256={hashlib.sha256(raw).hexdigest()}",
                        f"INSTRUMENTATION_RESULT: p37BenchmarkDataRawBytes={len(raw)}",
                        "INSTRUMENTATION_RESULT: p37BenchmarkDataChunkCount=1",
                        f"INSTRUMENTATION_RESULT: p37BenchmarkDataChunk0000={encoded}",
                    )
                ),
                encoding="utf-8",
            )
            extracted, _ = generator.extract_embedded_benchmark_data(path)
            self.assertEqual(raw, extracted)
            path.write_text(path.read_text().replace(encoded[-1], "A", 1), encoding="utf-8")
            with self.assertRaises(generator.EvidenceError):
                generator.extract_embedded_benchmark_data(path)

    def test_api28_frame_evidence_uses_only_the_conservative_cpu_duration_metric(self) -> None:
        raw = self.frame_raw(api_level=28, metric_name="frameDurationCpuMs")
        values, source = generator.frame_runs(raw)
        self.assertEqual("frameDurationCpuMs", source)
        self.assertEqual(30 * len({definition[0] for definition in generator.BENCHMARK_SCENARIOS.values()}), len(values))

        with self.assertRaises(generator.EvidenceError):
            generator.frame_runs(self.frame_raw(api_level=28, metric_name="frameOverrunMs"))

    def test_api36_frame_evidence_requires_native_overrun_samples(self) -> None:
        raw = self.frame_raw(api_level=36, metric_name="frameOverrunMs")
        _, source = generator.frame_runs(raw)
        self.assertEqual("frameOverrunMs", source)

        with self.assertRaises(generator.EvidenceError):
            generator.frame_runs(self.frame_raw(api_level=36, metric_name="frameDurationCpuMs"))

    @staticmethod
    def frame_raw(api_level: int, metric_name: str) -> dict:
        methods = sorted({definition[0] for definition in generator.BENCHMARK_SCENARIOS.values()})
        return {
            "context": {"build": {"version": {"sdk": api_level}}},
            "benchmarks": [
                {
                    "className": "app.ledger.benchmark.P37InteractiveMacrobenchmark",
                    "name": method,
                    "sampledMetrics": {metric_name: {"runs": [[1.0] for _ in range(30)]}},
                }
                for method in methods
            ],
        }


if __name__ == "__main__":
    unittest.main()
