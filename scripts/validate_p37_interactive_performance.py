#!/usr/bin/env python3
"""Fail closed on P37 session ownership, interaction measurement, budgets, and evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import statistics
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BUDGET_PATH = ROOT / "quality/performance/p37_budgets.json"


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def validate_budgets(budgets: dict | None = None) -> list[str]:
    budgets = budgets or json.loads(BUDGET_PATH.read_text(encoding="utf-8"))
    errors: list[str] = []
    samples = budgets.get("samplePolicy", {})
    latency = budgets.get("latencyMillis", {})
    counters = budgets.get("deterministicCounters", {})
    if samples.get("warmSamplesMin", 0) < 30:
        errors.append("warm sample minimum must be at least 30")
    if samples.get("coldSamplesMin", 0) < 5:
        errors.append("cold sample minimum must be at least 5")
    if samples.get("requiredStatistics") != ["p50", "p90", "p95", "max"]:
        errors.append("P37 must report p50/p90/p95/max")
    if samples.get("timeoutIsFailure") is not True:
        errors.append("timed-out content assertions must fail")
    expected_latency = {
        "warmCachedTopLevelNavigationP95Max": 250,
        "warmUncachedBoundedDestinationP95Max": 750,
        "ordinarySaveP95Max": 750,
        "searchAfterDebounceP95Max": 500,
        "searchIncludingDebounceP95Max": 800,
        "unlockToCurrentRouteContentP95Max": 1500,
        "blockingProgressVisibleMax": 100,
        "frameOverrunP95Max": 32,
    }
    for name, maximum in expected_latency.items():
        if latency.get(name, maximum + 1) > maximum:
            errors.append(f"latency budget weakened: {name}")
    expected_counters = {
        "uiUnlockPrimaryOpen": 1,
        "uiUnlockDatabaseKeyUnwrap": 1,
        "warmInteractionPrimaryOpenDelta": 0,
        "warmInteractionDatabaseKeyUnwrapDelta": 0,
        "ordinarySaveFinancialTransactions": 1,
        "journalPageSqlStatementsMax": 3,
        "journalPageWithRunningBalanceSqlStatementsMax": 4,
    }
    for name, expected in expected_counters.items():
        if counters.get(name) != expected:
            errors.append(f"deterministic counter changed: {name}")
    return errors


def validate_measurement_wiring(
    benchmark: str | None = None,
    provider: str | None = None,
    activity: str | None = None,
    view_model: str | None = None,
    async_content: str | None = None,
    journal_state: str | None = None,
    journal_destination: str | None = None,
    runner: str | None = None,
    generator: str | None = None,
    application: str | None = None,
    recurrence_worker: str | None = None,
    session_access: str | None = None,
    session_manager: str | None = None,
    journal_port: str | None = None,
    root_screen: str | None = None,
    session_performance: str | None = None,
) -> list[str]:
    benchmark = benchmark or read("benchmark/src/main/kotlin/app/ledger/benchmark/P35Macrobenchmark.kt")
    provider = provider or read("app/src/benchmark/kotlin/app/ledger/app/P35BenchmarkFixtureProvider.kt")
    activity = activity or read("app/src/main/kotlin/app/ledger/app/MainActivity.kt")
    view_model = view_model or read("app/src/main/kotlin/app/ledger/app/AppRootViewModel.kt")
    async_content = async_content or read("finance/application/src/main/kotlin/app/ledger/finance/application/AsyncContent.kt")
    journal_state = journal_state or read("feature/journal/src/main/kotlin/app/ledger/feature/journal/JournalState.kt")
    journal_destination = journal_destination or read(
        "feature/journal/src/main/kotlin/app/ledger/feature/journal/JournalDestination.kt"
    )
    runner = runner or read("benchmark/src/main/kotlin/app/ledger/benchmark/P37BenchmarkRunner.kt")
    generator = generator or read("scripts/generate_p37_interactive_performance_evidence.py")
    application = application or read("app/src/main/kotlin/app/ledger/app/LedgerApplication.kt")
    recurrence_worker = recurrence_worker or read("app/src/main/kotlin/app/ledger/app/RecurrenceCatchUpWorker.kt")
    session_access = session_access or read(
        "core/security/src/main/kotlin/app/ledger/core/security/LedgerDatabaseSessionAccess.kt"
    )
    session_manager = session_manager or read(
        "core/security/src/main/kotlin/app/ledger/core/security/BookSessionManager.kt"
    )
    journal_port = journal_port or read(
        "finance/data/src/main/kotlin/app/ledger/finance/data/SecureRoomJournalApplicationPort.kt"
    )
    root_screen = root_screen or read("app/src/main/kotlin/app/ledger/app/AppRootScreen.kt")
    session_performance = session_performance or read(
        "core/security/src/main/kotlin/app/ledger/core/security/LedgerSessionPerformance.kt"
    )
    errors: list[str] = []
    benchmark_markers = (
        "P37ArchitectureCounterDeviceTest",
        "P37InteractiveMacrobenchmark",
        "coldUnlockToCurrentRouteContent",
        "warmCachedTopLevelNavigation",
        "warmUncachedBoundedJournalNavigation",
        "blockingLoadingAffordanceBecomesVisible",
        "ordinarySaveToCommittedAcknowledgement",
        "journalSearchIncludingDebounce",
        "P37_WARM_ITERATIONS = 30",
        "P37_COLD_ITERATIONS = 5",
        'TraceSectionMetric("P37/route_request"',
        'TraceSectionMetric("P37/unlock_to_content"',
        'TraceSectionMetric("P37/save_request"',
        'TraceSectionMetric("P37/save_commit"',
        'TraceSectionMetric("P37/save_settled"',
        'TraceSectionMetric("P37/search_content"',
        "P37_BLOCKING_PROGRESS_TRACE",
        "P37_BLOCKING_PROGRESS_OBSERVER_DELAY_MILLIS = 150L",
        "SystemClock.sleep(P37_BLOCKING_PROGRESS_OBSERVER_DELAY_MILLIS)",
        "P37_CACHED_CONTENT_OBSERVER_DELAY_MILLIS = 300L",
        "SystemClock.sleep(P37_CACHED_CONTENT_OBSERVER_DELAY_MILLIS)",
        "P37_UNCACHED_CONTENT_OBSERVER_DELAY_MILLIS = 800L",
        "SystemClock.sleep(P37_UNCACHED_CONTENT_OBSERVER_DELAY_MILLIS)",
        "P37_COLD_CONTENT_OBSERVER_DELAY_MILLIS = 1_600L",
        "SystemClock.sleep(P37_COLD_CONTENT_OBSERVER_DELAY_MILLIS)",
        "P37_MAX_NESTED_BACK_STEPS = 3",
        "device.click(center.x, center.y)",
        'By.res("journal_loading")',
        'By.descContains("Benchmark food")',
        "requireP37Counters",
        '"am start -W -a android.intent.action.MAIN',
        "launchP37WarmTargetForSetup",
        '"-f 0x34000000 -n $PACKAGE_NAME/app.ledger.app.MainActivity"',
        'requireResource(device, "record_root")',
        'requireResource(device, "record_content")',
        'clickDescription(device, "Search transactions")',
        'requireResource(device, "journal_search_results")',
    )
    for marker in benchmark_markers:
        if marker not in benchmark:
            errors.append(f"P37 benchmark marker missing: {marker}")
    cold_unlock_benchmark = benchmark.partition("fun coldUnlockToCurrentRouteContent()")[2].partition(
        "\n    @Test"
    )[0]
    for marker in (
        'TraceSectionMetric("P37/unlock_to_content"',
        "SystemClock.sleep(P37_COLD_CONTENT_OBSERVER_DELAY_MILLIS)",
        'requireResource(device, "record_content")',
        "SystemClock.sleep(P37_CACHED_CONTENT_OBSERVER_DELAY_MILLIS)",
    ):
        if marker not in cold_unlock_benchmark:
            errors.append(f"cold unlock authoritative-content benchmark marker missing: {marker}")
    if 'requireResource(device, "record_root")' in cold_unlock_benchmark:
        errors.append("cold unlock benchmark may not terminate on the loading-capable record_root shell")
    warm_helper = benchmark.partition("private fun launchP37WarmTargetForSetup")[2].partition(
        "\nprivate fun requireResource"
    )[0]
    for marker in (
        '"-f 0x34000000 -n $PACKAGE_NAME/app.ledger.app.MainActivity"',
        "unwindP37NestedDestinationForSetup(device)",
        "repeat(P37_MAX_NESTED_BACK_STEPS)",
        'device.findObject(By.res("ledger_navigation_record"))',
        "device.pressBack()",
        'clickTopLevel(device, "Record")',
        'requireResource(device, "record_content")',
    ):
        if marker not in warm_helper:
            errors.append(f"P37 retained-Activity warm setup marker missing: {marker}")
    if "0x10008000" in warm_helper:
        errors.append("P37 warm setup may not recreate the Activity with CLEAR_TASK")
    for method in (
        "warmCachedTopLevelNavigation",
        "warmUncachedBoundedJournalNavigation",
        "blockingLoadingAffordanceBecomesVisible",
        "ordinarySaveToCommittedAcknowledgement",
        "journalSearchIncludingDebounce",
    ):
        method_body = benchmark.partition(f"fun {method}()")[2].partition("\n    @Test")[0]
        if "launchP37WarmTargetForSetup(device)" not in method_body:
            errors.append(f"P37 warm benchmark recreates or bypasses retained Activity setup: {method}")
    search_benchmark = benchmark.partition("fun journalSearchIncludingDebounce()")[2].partition("\n    @Test")[0]
    if 'clickDescription(device, "Search transactions")' not in search_benchmark:
        errors.append("P37 search benchmark must select the search action by content description")
    description_clicker = benchmark.partition("private fun clickDescription")[2].partition("\nprivate fun clickTopLevel")[0]
    for marker in (
        "ArrayDeque(device.findObjects(By.depth(0)))",
        "candidate.contentDescription?.contains(description) == true",
        "pending.addAll(candidate.children)",
        "clickableAncestor(candidate)",
        "candidate.visibleCenter",
        "device.click(center.x, center.y)",
        "catch (stale: StaleObjectException)",
        '"Description node became stale; retrying current tree"',
    ):
        if marker not in description_clicker:
            errors.append(f"P37 content-description click fallback marker missing: {marker}")
    ordinary_loader = view_model.partition("fun loadOrdinaryRecord(transactionId: StableId? = null)")[2].partition(
        "fun openBatchEntry()"
    )[0]
    if "?.takeIf { currentOrdinaryLoadKey == key }" not in ordinary_loader:
        errors.append("ordinary Record retained content is not isolated by the complete load key")
    blocking_progress_markers = (
        (session_performance, "LedgerInteractionOperation.BLOCKING_PROGRESS_VISIBLE"),
        (view_model, "LedgerSessionPerformance.beginBlockingProgress()"),
        (view_model, "TopLevelDestination.JOURNAL -> Unit"),
        (view_model, "fun onJournalFirstResponsePresented(screenId: String)"),
        (view_model, "LedgerSessionPerformance.completeBlockingProgress()"),
        (view_model, "if (state === JournalLoadState.Loading)"),
        (view_model, 'JOURNAL_OPTION_SCREENS = setOf("JRN-003", "JRN-006")'),
        (view_model, "fun onJournalPagePresented()"),
        (view_model, "completeJournalRoutePresentation(request.bookId)"),
        (view_model, "routeContentTrace?.close()"),
        (journal_destination, "withFrameNanos { onFirstResponsePresented() }"),
        (journal_destination, "pageReady && blockingLoadingLabel == null"),
        (journal_destination, "if (items.loadState.refresh is LoadState.NotLoading) actions.onPagePresented()"),
        (journal_state, "val pagingEpoch: Int = 0"),
        (journal_state, "val pageLoadedEpoch: Int? = null"),
        (view_model, "current.copy(pageLoadedEpoch = request.refreshEpoch)"),
        (root_screen, "onFirstResponsePresented = { viewModel.onJournalFirstResponsePresented(screenId) }"),
        (root_screen, "onPagePresented = viewModel::onJournalPagePresented"),
    )
    for source, marker in blocking_progress_markers:
        if marker not in source:
            errors.append(f"blocking progress target-frame gate missing: {marker}")
    first_response_gate = view_model.partition(
        "fun onJournalFirstResponsePresented(screenId: String)"
    )[2].partition("fun onJournalPagePresented")[0]
    blocking_completion = first_response_gate.find(
        "LedgerSessionPerformance.completeBlockingProgress()"
    )
    state_read = first_response_gate.find("val state")
    if blocking_completion < 0 or state_read < 0 or blocking_completion > state_read:
        errors.append(
            "blocking progress target-frame gate missing: completion must precede Journal work"
        )
    initial_journal_load = view_model.partition("fun loadJournal() {")[2].partition(
        "fun onJournalFirstResponsePresented"
    )[0]
    if "bulkEditOptions(" in initial_journal_load:
        errors.append("Journal initial route eagerly loads bulk options before first content")
    page_completion = view_model.partition("private fun completeJournalPage(")[2].partition(
        "fun onJournalPagePresented"
    )[0]
    if "markTopLevelContent(" in page_completion:
        errors.append("Journal route content is marked from IO before the Paging frame is presented")
    for marker in (
        'METHOD_P37_RESET = "p37-reset"',
        'METHOD_P37_METRICS = "p37-metrics"',
        "LedgerDatabasePerformance.resetForTest()",
        "LedgerSessionPerformance.resetForTest()",
        '"financialTransactions=${database.financialCommitTransactionCount}"',
        "application.awaitFirstInteractiveContentWork()",
        "P37_BACKGROUND_NOT_QUIESCENT",
    ):
        if marker not in provider:
            errors.append(f"P37 counter provider marker missing: {marker}")
    for marker in (
        "currentRouteContentReadyGeneration.collect",
        "root.postOnAnimation",
        "reportFullyDrawn()",
    ):
        if marker not in activity:
            errors.append(f"fully-drawn content marker missing: {marker}")
    if activity.find("reportFullyDrawn()") < activity.find("currentRouteContentReadyGeneration.collect"):
        errors.append("reportFullyDrawn is not gated by current-route content")
    for marker in (
        "LedgerInteractionOperation.UNLOCK_TO_CONTENT",
        "LedgerInteractionOperation.ROUTE_REQUEST",
        "LedgerInteractionOperation.ROUTE_CONTENT",
        "LedgerInteractionOperation.SAVE_REQUEST",
        "LedgerInteractionOperation.SAVE_COMMIT",
        "LedgerInteractionOperation.SAVE_SETTLED",
        "LedgerInteractionOperation.SEARCH_CONTENT",
    ):
        if marker not in view_model:
            errors.append(f"interaction trace is unused: {marker}")
    for marker in (
        "mutableTopLevelContentGenerations",
        "awaitTopLevelContent(topLevel, generation)",
        "markTopLevelContent(bookId, TopLevelDestination.JOURNAL)",
        "RecurrenceWorkScheduler.scheduleStartupCatchUpAndAwait(context, bookId)",
        "refreshWidgetSnapshotNow(bookId)",
        "application.onFirstInteractiveContent {",
        "schedulePostFirstContentWork(ready.bookId, ready.generation, target)",
    ):
        if marker not in view_model:
            errors.append(f"current-generation content gate missing: {marker}")
    if "AppUnlockTransitionPolicy.mayOpenWithoutAuthentication(manager.state.value" not in view_model:
        errors.append("process session reattachment policy is missing from AppRootViewModel")
    for marker in (
        "DEFAULT_INTERACTIVE_LOAD_TIMEOUT_MILLIS",
        "withTimeoutOrNull(timeoutMillis)",
        'LoadResult.Failure("LOAD_TIMEOUT")',
        "onTimeout(key)",
    ):
        if marker not in async_content:
            errors.append(f"bounded interactive loader marker missing: {marker}")
    if "JOURNAL_PAGE_TIMEOUT" not in journal_state:
        errors.append("Journal paging has no bounded timeout/error transition")
    for marker in (
        "val items = pages.collectAsLazyPagingItems()",
        "blockingLoadingLabel = stringResource(R.string.p15_journal_loading).takeIf { state.searchPending }",
        "if (blockingLoadingLabel != null)",
        "LedgerTestTags.JOURNAL_LOADING",
    ):
        if marker not in journal_destination:
            errors.append(f"Journal pending search does not keep the paging collector active: {marker}")
    for marker in (
        "class P37BenchmarkRunner",
        "p37BenchmarkDataChunk",
        '"gzip+base64"',
        "p37BenchmarkDataSha256",
    ):
        if marker not in runner:
            errors.append(f"API-independent benchmark export marker missing: {marker}")
    for marker in (
        "extract_embedded_benchmark_data",
        "androidxBenchmarkDataSha256",
        "ordinarySaveCommit",
        "ordinarySavePropagation",
        "blockingProgressVisible",
        "searchIncludingDebounce",
        '"P37/unlock_to_contentFirstMs"',
        'metric_name = "frameDurationCpuMs" if raw_api < 31 else "frameOverrunMs"',
        "conservative-cpu-frame-duration-against-overrun-ceiling",
    ):
        if marker not in generator:
            errors.append(f"P37 evidence generator marker missing: {marker}")
    for marker in (
        "awaitFinancialDependentWork()",
        "interactiveContentWork()",
        "runFinancialDependentWork()",
        "firstInteractiveContentGate.complete()",
    ):
        if marker not in application:
            errors.append(f"post-content background-work gate missing: {marker}")
    for marker in (
        "scheduleStartupCatchUpAndAwait",
        "getWorkInfosForUniqueWork",
        "info.state.isFinished",
        ".setInitialDelay(PERIODIC_HOURS, TimeUnit.HOURS)",
    ):
        if marker not in recurrence_worker:
            errors.append(f"startup recurrence quiescence marker missing: {marker}")
    for marker in (
        "interface LedgerSecureSettingsOperationAccess",
        "interface LedgerSessionOperationAccess",
        "withCurrentSessionSecureSettings",
    ):
        if marker not in session_access:
            errors.append(f"session secure-settings access marker missing: {marker}")
    for marker in (
        "copySecureSettingsForSession()",
        "override suspend fun <T> withSecureSettings",
        "closeSessionResource()",
        "openedSecureSettings?.close()",
    ):
        if marker not in session_manager:
            errors.append(f"session secure-settings lifecycle marker missing: {marker}")
    for marker in (
        "mutableState.value == BookSessionState.Opening ||",
        "mutableState.value is BookSessionState.Ready",
        "idempotent attachment outcomes",
    ):
        if marker not in session_manager:
            errors.append(f"process session reattachment marker missing: {marker}")
    for marker in (
        "private val sessionAccess: LedgerSessionOperationAccess",
        "sessionAccess.withCurrentSecureSettings",
    ):
        if marker not in journal_port:
            errors.append(f"Journal session secure-settings marker missing: {marker}")
    if '"WHERE ctp.transaction_uid IN ($placeholders)"' not in journal_port:
        errors.append("Journal indexed access marker missing: projection transaction_uid lookup")
    if '"WHERE bt.uid IN ($placeholders)"' in journal_port:
        errors.append("Journal page enrichment may scan the full projection through business_transaction.uid")
    for forbidden in ("DeviceLedgerKeyProvider", "keyProvider.open(", "withKeys("):
        if forbidden in journal_port:
            errors.append(f"Journal retains direct key-provider access: {forbidden}")
    return errors


def validate_architecture_cleanup(sources: dict[str, str] | None = None) -> list[str]:
    if sources is None:
        sources = {
            path.relative_to(ROOT).as_posix(): path.read_text(encoding="utf-8")
            for path in sorted((ROOT / "finance/data/src/main/kotlin").glob("**/*.kt"))
        }
    errors: list[str] = []
    for path, source in sources.items():
        if re.search(r"private\s+(?:inner\s+)?class\s+\w*(?:Ledger)?WriteGate", source):
            errors.append(f"Port-local write gate remains: {path}")
        if "DefaultLedgerWriteGate()" in source:
            errors.append(f"Port constructs a private mutex write gate: {path}")
    policy = read("build-logic/src/main/kotlin/app/ledger/buildlogic/SourcePolicyEngine.kt")
    for marker in ("P37-LIVE-PRIMARY-OPEN", "P37-DATABASE-PASSPHRASE", "P37-INTERACTIVE-FULL-SNAPSHOT"):
        if marker not in policy:
            errors.append(f"P37 source policy missing: {marker}")
    return errors


def validate_journal_query_plan_remediation(
    schema_migration: str | None = None,
    reference_schema_migration: str | None = None,
    schema_definition: str | None = None,
    migrations: str | None = None,
    post_validation: str | None = None,
    reference_port: str | None = None,
    target_scale_test: str | None = None,
    migration_test: str | None = None,
    schema_export: str | None = None,
    schema_test_asset: str | None = None,
    latest_schema_export: str | None = None,
    latest_schema_test_asset: str | None = None,
    analytics_engine: str | None = None,
    projection_engine: str | None = None,
    ordinary_transaction_test: str | None = None,
    benchmark_fixture: str | None = None,
    commit_repository: str | None = None,
) -> list[str]:
    schema_migration = schema_migration or read(
        "core/database/src/main/assets/ledger_schema_v6_journal_keyset.sql"
    )
    reference_schema_migration = reference_schema_migration or read(
        "core/database/src/main/assets/ledger_schema_v7_reference_keysets.sql"
    )
    schema_definition = schema_definition or read(
        "core/database/src/main/kotlin/app/ledger/core/database/LedgerSchemaDefinition.kt"
    )
    migrations = migrations or read(
        "core/database/src/main/kotlin/app/ledger/core/database/LedgerMigrations.kt"
    )
    post_validation = post_validation or read(
        "core/database/src/main/kotlin/app/ledger/core/database/MigrationPostValidation.kt"
    )
    reference_port = reference_port or read(
        "finance/data/src/main/kotlin/app/ledger/finance/data/SecureRoomReferenceDataManagementPort.kt"
    )
    target_scale_test = target_scale_test or read(
        "finance/data/src/androidTest/kotlin/app/ledger/finance/data/RoomFinancialDataDeviceTest.kt"
    )
    migration_test = migration_test or read(
        "core/database/src/androidTest/kotlin/app/ledger/core/database/MigrationTestInfrastructureDeviceTest.kt"
    )
    schema_export = schema_export or read(
        "core/database/schemas/app.ledger.core.database.LedgerDatabase/6.json"
    )
    schema_test_asset = schema_test_asset or read(
        "core/database/src/androidTest/assets/app.ledger.core.database.LedgerDatabase/6.json"
    )
    latest_schema_export = latest_schema_export or read(
        "core/database/schemas/app.ledger.core.database.LedgerDatabase/7.json"
    )
    latest_schema_test_asset = latest_schema_test_asset or read(
        "core/database/src/androidTest/assets/app.ledger.core.database.LedgerDatabase/7.json"
    )
    analytics_engine = analytics_engine or read(
        "core/database/src/main/kotlin/app/ledger/core/database/AnalyticsProjectionEngine.kt"
    )
    projection_engine = projection_engine or read(
        "finance/data/src/main/kotlin/app/ledger/finance/data/RoomProjectionEngine.kt"
    )
    ordinary_transaction_test = ordinary_transaction_test or read(
        "finance/data/src/androidTest/kotlin/app/ledger/finance/data/OrdinaryTransactionEntryDeviceTest.kt"
    )
    benchmark_fixture = benchmark_fixture or read(
        "app/src/benchmark/kotlin/app/ledger/app/P35BenchmarkFixtureProvider.kt"
    )
    commit_repository = commit_repository or read(
        "finance/data/src/main/kotlin/app/ledger/finance/data/RoomFinancialCommitRepository.kt"
    )
    errors: list[str] = []
    required = (
        (
            schema_migration,
            "v6 Journal keyset schema",
            (
                "CREATE INDEX ix_current_transaction_state_keyset",
                "ON current_transaction_projection(state, occurred_at DESC, transaction_id DESC)",
            ),
        ),
        (
            schema_definition,
            "v6 schema registration",
            (
                "const val PRIMARY_VERSION: Int = 7",
                "private val primaryV6Assets",
                '"ledger_schema_v6_journal_keyset.sql"',
                "fun migratePrimaryV5ToV6",
                "primaryV6ContractSha256(context)",
            ),
        ),
        (
            migrations,
            "v5 to v6 migration contract",
            (
                "Migration(PRIMARY_V5, PRIMARY_V6)",
                "migratePrimaryV5ToV6(context, db)",
                "MigrationPostValidation.validateOrThrow(context, db, PRIMARY_V6)",
                "state-aware Journal keyset index proven by target-scale EXPLAIN evidence",
            ),
        ),
        (
            post_validation,
            "exact Journal migration query-plan validation",
            (
                "journalStateKeysetQueryUsesIndex",
                "journalStateKeysetAvoidsTempSort",
                "ORDER BY occurred_at DESC,transaction_id DESC LIMIT 100",
                'step.contains("TEMP B-TREE", ignoreCase = true)',
                'private const val JOURNAL_STATE_KEYSET_INDEX = "ix_current_transaction_state_keyset"',
                "if (targetVersion < JOURNAL_STATE_KEYSET_VERSION) return true to true",
                "private const val JOURNAL_STATE_KEYSET_VERSION = 6",
                "if (targetVersion < PROJECTION_GENERATION_VERSION) return true",
                "private const val PROJECTION_GENERATION_VERSION = 4",
            ),
        ),
        (
            target_scale_test,
            "target-scale active Journal regression",
            (
                "seedHalfMillionQueryRows()",
                "lifecycleStates = setOf(TransactionLifecycleState.ACTIVE)",
                'activeElapsed < 750L',
                'it.contains("ix_current_transaction_state_keyset")',
                'it.contains("TEMP B-TREE", ignoreCase = true)',
            ),
        ),
        (
            reference_schema_migration,
            "v7 reference-page keyset schema",
            (
                "CREATE INDEX ix_merchant_name_keyset",
                "CREATE INDEX ix_place_name_keyset",
                "CREATE INDEX ix_place_merchant",
                "CREATE INDEX ix_location_record_captured_keyset",
                "CREATE INDEX ix_current_transaction_merchant",
                "CREATE INDEX ix_transaction_revision_location",
                "CREATE INDEX ix_current_transaction_revision",
            ),
        ),
        (
            schema_definition,
            "v7 schema registration",
            (
                '"ledger_schema_v7_reference_keysets.sql"',
                "fun migratePrimaryV6ToV7",
                "primaryV6ContractSha256(context)",
            ),
        ),
        (
            migrations,
            "v6 to v7 migration contract",
            (
                "Migration(PRIMARY_V6, PRIMARY_V7)",
                "migratePrimaryV6ToV7(context, db)",
                "MigrationPostValidation.validateOrThrow(context, db, PRIMARY_V7)",
                "reference-page keyset and bounded usage-count indexes proven by target-scale EXPLAIN evidence",
            ),
        ),
        (
            post_validation,
            "exact reference-page migration query-plan validation",
            (
                "referenceMerchantPageUsesBoundedIndexes",
                "referenceLocationPageUsesBoundedIndexes",
                "referencePagesAvoidTempAggregation",
                'step.contains("SCAN current_transaction_projection", ignoreCase = true)',
                'step.contains("TEMP B-TREE", ignoreCase = true)',
                "private const val REFERENCE_KEYSET_VERSION = 7",
            ),
        ),
        (
            reference_port,
            "page-first reference production queries",
            (
                "WITH page AS (",
                "FROM merchant m INDEXED BY ix_merchant_name_keyset",
                "FROM current_transaction_projection ctp INDEXED BY ix_current_transaction_merchant",
                "FROM place p INDEXED BY ix_place_name_keyset",
                "FROM location_record lr INDEXED BY ix_location_record_captured_keyset",
                "transaction_revision tr INDEXED BY ix_transaction_revision_location",
                "current_transaction_projection ctp INDEXED BY ix_current_transaction_revision",
            ),
        ),
        (
            target_scale_test,
            "target-scale bounded reference-page regression",
            (
                "references.merchantPage(BOOK_ID.value, limit = 50)",
                "references.placePage(BOOK_ID.value, limit = 50)",
                "references.locationPage(BOOK_ID.value, limit = 50)",
                "referenceElapsed < 750L",
                "merchants.single().currentTransactionCount",
                "locations.single().currentTransactionCount",
            ),
        ),
        (
            analytics_engine,
            "commit-owned analytics delta engine",
            (
                "fun applyCommitDeltas(",
                "economicEffectUids.chunked(DELTA_UID_CHUNK_SIZE)",
                "journalEntryUids.chunked(DELTA_UID_CHUNK_SIZE)",
                "FROM economic_effect WHERE uid IN ($placeholders)",
                "WHERE je.uid IN ($placeholders)",
                "currentTransactionDeltas",
                "ON CONFLICT(local_date,metric) DO UPDATE",
                "ON CONFLICT(year_month,metric) DO UPDATE",
                "private const val DELTA_UID_CHUNK_SIZE: Int = 400",
            ),
        ),
        (
            projection_engine,
            "interactive commit analytics-delta wiring",
            (
                "AnalyticsProjectionEngine.applyCommitDeltas(",
                "economicEffectUids = plan.economicEffects.map { it.id.value.bytes }",
                "journalEntryUids = plan.journalBundles.map { it.entry.id.value.bytes }",
                "currentTransactionDeltas = currentTransactionDeltas(before, after)",
                '"SELECT transaction_id,local_date,state "',
            ),
        ),
        (
            projection_engine,
            "plan-owned account and budget projection deltas",
            (
                "applyAccountDeltas(database, journalEntryUids, revision)",
                "journalEntryUids.chunked(PROJECTION_UID_CHUNK_SIZE)",
                'WHERE je.uid IN (${chunk.joinToString(",") { "?" }})',
                "hasAffectedCreditAccount(database, journalEntryUids)",
                "applyBudgetEffectDeltas(database, plan.budgetEffects, revision)",
                "economicEffectUids = if (refundUids.isEmpty())",
                "plan.economicEffects.map { it.id.value.bytes }",
                'else -> " AND ee.uid IN (${economicEffectUids.joinToString(",") { "?" }})"',
                "private const val PROJECTION_UID_CHUNK_SIZE = 400",
            ),
        ),
        (
            target_scale_test,
            "target-scale bounded ordinary-save regression",
            (
                'note = "p37-bounded-save"',
                "targetScaleSaveElapsed < 750L",
                "HALF_MILLION + 1L",
                "analytics_daily_total",
                "analytics_monthly_total",
                "TARGET_JOURNAL_BASE",
                "TARGET_BUDGET_EFFECT_BASE",
                "SELECT used_minor FROM budget_usage_projection",
                "SELECT normal_balance_minor FROM account_balance_current",
            ),
        ),
        (
            benchmark_fixture,
            "projection-consistent target-scale benchmark fixture",
            (
                "seedExactInteractiveProjections(database, refs)",
                "AnalyticsProjectionEngine.rebuild(db, refs.revision)",
                "actual.normal_balance_minor=expected.normal_balance_minor",
                "actual.total_debit_minor=expected.total_debit_minor",
                "actual.total_credit_minor=expected.total_credit_minor",
                "FROM budget_effect_line WHERE target_year_month=202608",
                "actual.used_minor=expected.used_minor",
                "actual.adjustment_minor=expected.adjustment_minor",
                "actual.amount_base_minor=(SELECT COUNT(*) FROM current_transaction_projection",
                '"budget projection is not target-scale fact-exact"',
                '"account projection is not target-scale fact-exact"',
                '"analytics projection is not target-scale fact-exact"',
            ),
        ),
        (
            commit_repository,
            "plan-owned post-commit journal verification",
            (
                ".chunked(COMMIT_VERIFICATION_UID_CHUNK_SIZE)",
                '"SELECT COUNT(*) FROM journal_entry WHERE uid IN "',
                "private const val COMMIT_VERIFICATION_UID_CHUNK_SIZE = 400",
            ),
        ),
        (
            ordinary_transaction_test,
            "ordinary create/edit analytics rebuild parity",
            ("AnalyticsProjectionEngine.audit(db, 5L).consistent",),
        ),
        (
            migration_test,
            "API v7 migration query-plan proof",
            (
                "versionSixToSevenAddsBoundedReferencePagePlansWithoutProjectionScans",
                "fromVersion = 6, toVersion = 7, migrationIndex = 5",
                "report.referenceMerchantPageUsesBoundedIndexes",
                "report.referenceLocationPageUsesBoundedIndexes",
                "report.referencePagesAvoidTempAggregation",
            ),
        ),
        (
            migration_test,
            "API migration query-plan proof",
            (
                "versionFiveToSixAddsTheJournalStateKeysetPlanWithoutTemporarySorting",
                "fromVersion = 5, toVersion = 6, migrationIndex = 4",
                "report.journalStateKeysetQueryUsesIndex",
                "report.journalStateKeysetAvoidsTempSort",
            ),
        ),
    )
    for source, label, markers in required:
        for marker in markers:
            if marker not in source:
                errors.append(f"{label} missing: {marker}")
    if schema_export.rstrip("\n") != schema_test_asset.rstrip("\n"):
        errors.append("Room schema 6 AndroidTest asset is not identical to the exported schema")
    if latest_schema_export.rstrip("\n") != latest_schema_test_asset.rstrip("\n"):
        errors.append("Room schema 7 AndroidTest asset is not identical to the exported schema")
    incremental_body = projection_engine.partition("fun applyIncremental(")[2].partition("fun rebuildWidgetSnapshot(")[0]
    if "AnalyticsProjectionEngine.rebuildDates(" in incremental_body:
        errors.append("interactive financial commits may not rebuild all historical facts for an affected date")
    account_delta_body = projection_engine.partition("private fun applyAccountDeltas(")[2].partition(
        "private fun applyDailyAccountDelta("
    )[0]
    if "created_commit_id" in account_delta_body:
        errors.append("interactive account projection must use plan-owned journal-entry UIDs, not a commit scan")
    verify_new_state_body = commit_repository.partition("private fun verifyNewState(")[2].partition(
        "private fun primaryEntity("
    )[0]
    if "created_commit_id" in verify_new_state_body:
        errors.append("post-commit balance verification must use plan-owned journal-entry UIDs, not a commit scan")
    return errors


SCENARIO_POLICIES = {
    "warmCachedTopLevelNavigation": ("warmCachedTopLevelNavigationP95Max", "p95", "warm"),
    "warmUncachedBoundedDestination": ("warmUncachedBoundedDestinationP95Max", "p95", "warm"),
    "blockingProgressVisible": ("blockingProgressVisibleMax", "max", "warm"),
    "ordinarySave": ("ordinarySaveP95Max", "p95", "warm"),
    "ordinarySaveCommit": (None, None, "warm"),
    "ordinarySavePropagation": (None, None, "warm"),
    "searchIncludingDebounce": ("searchIncludingDebounceP95Max", "p95", "warm"),
    "searchAfterDebounce": ("searchAfterDebounceP95Max", "p95", "warm"),
    "unlockToCurrentRouteContent": ("unlockToCurrentRouteContentP95Max", "p95", "cold"),
    "frameOverrun": ("frameOverrunP95Max", "p95", "warm"),
}
SCENARIO_BUDGETS = {name: policy[0] for name, policy in SCENARIO_POLICIES.items() if policy[0] is not None}


def nearest_rank(values: list[float], percentile: float) -> float:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(percentile * len(ordered)) - 1)]


def expected_statistics(values: list[float]) -> dict[str, float]:
    mean = statistics.fmean(values)
    deviation = statistics.pstdev(values)
    return {
        "p50": round(nearest_rank(values, 0.50), 6),
        "p90": round(nearest_rank(values, 0.90), 6),
        "p95": round(nearest_rank(values, 0.95), 6),
        "max": round(max(values), 6),
        "mean": round(mean, 6),
        "standardDeviation": round(deviation, 6),
        "coefficientOfVariation": round(deviation / abs(mean), 6) if mean else 0.0,
    }


def valid_sha256(value: object) -> bool:
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None


def validate_result(result: dict, budgets: dict, expected_api: int) -> list[str]:
    errors: list[str] = []
    if result.get("schemaVersion") != 2:
        errors.append(f"API {expected_api} result schema is not 2")
    if result.get("apiLevel") != expected_api:
        errors.append(f"API {expected_api} result has wrong apiLevel")
    if result.get("fixtureMarker") != budgets["evidence"]["fixtureMarker"]:
        errors.append(f"API {expected_api} result has wrong fixture")
    policy = result.get("measurementPolicy", {})
    if policy.get("percentileMethod") != "nearest-rank" or policy.get("timeoutsAreFailures") is not True:
        errors.append(f"API {expected_api} result has incomplete measurement policy")
    environment = result.get("environment", {})
    host = environment.get("host", {})
    device = environment.get("device", {})
    benchmark_environment = environment.get("benchmark", {})
    if not all(host.get(name) for name in ("operatingSystem", "kernel", "machine", "cpuModel")):
        errors.append(f"API {expected_api} result lacks host identity")
    if device.get("version", {}).get("sdk") != expected_api or not device.get("fingerprint"):
        errors.append(f"API {expected_api} result lacks device identity")
    if not isinstance(device.get("supportedAbis"), list) or not all(device.get("supportedAbis", [])):
        errors.append(f"API {expected_api} result lacks device ABI")
    if not benchmark_environment:
        errors.append(f"API {expected_api} result lacks benchmark environment")
    source = result.get("source", {})
    if not valid_sha256(source.get("androidxBenchmarkDataSha256")):
        errors.append(f"API {expected_api} result lacks raw benchmark SHA-256")
    for artifact in ("instrumentationResultLog", "targetApk", "benchmarkApk"):
        if not valid_sha256(source.get(artifact, {}).get("sha256")):
            errors.append(f"API {expected_api} result lacks source hash: {artifact}")
    if not source.get("counterLogs") or any(not valid_sha256(entry.get("sha256")) for entry in source.get("counterLogs", [])):
        errors.append(f"API {expected_api} result lacks counter-log hashes")
    scenarios = result.get("scenarios", {})
    for scenario, (budget_name, gate_statistic, sample_kind) in SCENARIO_POLICIES.items():
        measured = scenarios.get(scenario)
        if not isinstance(measured, dict):
            errors.append(f"API {expected_api} missing scenario: {scenario}")
            continue
        minimum = budgets["samplePolicy"]["coldSamplesMin" if sample_kind == "cold" else "warmSamplesMin"]
        if measured.get("samples", 0) < minimum:
            errors.append(f"API {expected_api} {scenario} has too few samples")
        samples = measured.get("samplesMillis")
        allow_negative = scenario == "frameOverrun"
        if (
            not isinstance(samples, list)
            or len(samples) != measured.get("samples")
            or any(not isinstance(value, (int, float)) or (value < 0 and not allow_negative) for value in samples)
        ):
            errors.append(f"API {expected_api} {scenario} lacks reproducible raw samples")
            continue
        values = [measured.get(name) for name in ("p50", "p90", "p95", "max")]
        if any(not isinstance(value, (int, float)) for value in values):
            errors.append(f"API {expected_api} {scenario} lacks valid statistics")
            continue
        if not (values[0] <= values[1] <= values[2] <= values[3]):
            errors.append(f"API {expected_api} {scenario} percentiles are not monotonic")
        recomputed = expected_statistics([float(value) for value in samples])
        for statistic, expected in recomputed.items():
            if measured.get(statistic) != expected:
                errors.append(f"API {expected_api} {scenario} has irreproducible {statistic}")
        if budget_name is not None and measured[gate_statistic] > budgets["latencyMillis"][budget_name]:
            errors.append(f"API {expected_api} {scenario} exceeds {gate_statistic} budget")
        if measured.get("timeouts", 0) != 0:
            errors.append(f"API {expected_api} {scenario} contains a timeout")
        if measured.get("failedSamples", 0) != 0:
            errors.append(f"API {expected_api} {scenario} contains a failed sample")
    for inclusive, component in (
        ("ordinarySave", "ordinarySaveCommit"),
        ("ordinarySave", "ordinarySavePropagation"),
        ("searchIncludingDebounce", "searchAfterDebounce"),
    ):
        outer = scenarios.get(inclusive, {}).get("samplesMillis", [])
        inner = scenarios.get(component, {}).get("samplesMillis", [])
        if len(outer) == len(inner) and any(total + 0.001 < part for total, part in zip(outer, inner)):
            errors.append(f"API {expected_api} {component} is not contained by {inclusive}")
    counters = result.get("deterministicCounters", {})
    expected_counters = budgets["deterministicCounters"]
    exact_counters = {
        "uiUnlockPrimaryOpen": "uiUnlockPrimaryOpen",
        "uiUnlockDatabaseKeyUnwrap": "uiUnlockDatabaseKeyUnwrap",
        "warmInteractionPrimaryOpenDelta": "warmInteractionPrimaryOpenDelta",
        "warmInteractionDatabaseKeyUnwrapDelta": "warmInteractionDatabaseKeyUnwrapDelta",
        "ordinarySaveFinancialTransactions": "ordinarySaveFinancialTransactions",
    }
    for result_name, budget_name in exact_counters.items():
        if counters.get(result_name) != expected_counters[budget_name]:
            errors.append(f"API {expected_api} counter mismatch: {result_name}")
    bounded_counters = {
        "journalPageSqlStatements": "journalPageSqlStatementsMax",
        "journalPageWithRunningBalanceSqlStatements": "journalPageWithRunningBalanceSqlStatementsMax",
    }
    for result_name, budget_name in bounded_counters.items():
        value = counters.get(result_name)
        if not isinstance(value, int) or value < 0 or value > expected_counters[budget_name]:
            errors.append(f"API {expected_api} counter mismatch: {result_name}")
    observations = source.get("counterObservations", {})
    for name, minimum in {"uiUnlock": 1, "warmInteraction": 5, "ordinarySave": 30, "journalQueryCounts": 1}.items():
        if observations.get(name, 0) < minimum:
            errors.append(f"API {expected_api} counter mismatch: {name}")
    return errors


def validate_evidence(budgets: dict | None = None) -> list[str]:
    budgets = budgets or json.loads(BUDGET_PATH.read_text(encoding="utf-8"))
    errors: list[str] = []
    results: dict[int, dict] = {}
    for api_key, api_level in (("api28", 28), ("api36", 36)):
        path = ROOT / budgets["evidence"][api_key]
        if not path.is_file():
            errors.append(f"P37 checked-in API {api_level} result is missing")
            continue
        result = json.loads(path.read_text(encoding="utf-8"))
        results[api_level] = result
        errors += validate_result(result, budgets, api_level)
    if len(results) == 2:
        errors += validate_same_candidate_artifacts(results)
    return errors


def validate_same_candidate_artifacts(
    results: dict[int, dict],
    root: Path = ROOT,
) -> list[str]:
    errors: list[str] = []
    for artifact in ("targetApk", "benchmarkApk"):
        entries = {api_level: result.get("source", {}).get(artifact, {}) for api_level, result in results.items()}
        identities = {
            (entry.get("path"), entry.get("bytes"), entry.get("sha256"))
            for entry in entries.values()
        }
        if len(identities) != 1:
            errors.append(f"API 28/API 36 do not share the same {artifact} identity")
            continue
        entry = next(iter(entries.values()))
        relative_path = entry.get("path")
        if not isinstance(relative_path, str):
            errors.append(f"current {artifact} path is missing")
            continue
        path = root / relative_path
        if not path.is_file():
            errors.append(f"current {artifact} is missing: {relative_path}")
            continue
        payload = path.read_bytes()
        if len(payload) != entry.get("bytes") or hashlib.sha256(payload).hexdigest() != entry.get("sha256"):
            errors.append(f"current {artifact} does not match the checked-in same-candidate evidence")
    return errors


def validate_all(include_evidence: bool = True) -> list[str]:
    budgets = json.loads(BUDGET_PATH.read_text(encoding="utf-8"))
    errors = validate_budgets(budgets)
    errors += validate_measurement_wiring()
    errors += validate_architecture_cleanup()
    errors += validate_journal_query_plan_remediation()
    if include_evidence:
        errors += validate_evidence(budgets)
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--implementation-only", action="store_true")
    args = parser.parse_args()
    errors = validate_all(include_evidence=not args.implementation_only)
    if errors:
        print("P37 interactive-performance validation failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    scope = "implementation contracts" if args.implementation_only else "implementation contracts and device evidence"
    print(f"P37 interactive-performance validation passed: {scope} are closed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
