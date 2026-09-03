#!/usr/bin/env python3
"""Fail closed on P35 target-scale, benchmark, fault and security regression drift."""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
from pathlib import Path
from xml.etree import ElementTree


ROOT = Path(__file__).resolve().parents[1]
BUDGETS = ROOT / "quality/performance/p35_budgets.json"
REQUIREMENT_LEDGER = ROOT / "docs/初始开发文件存档/implementation/REQUIREMENT_COVERAGE.csv"
PROJECT_STATE = ROOT / "docs/初始开发文件存档/implementation/PROJECT_STATE.md"
P35_AUDIT = ROOT / "docs/初始开发文件存档/implementation/P35_PERFORMANCE_FAULT_SECURITY_AUDIT.md"


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def production_sources() -> dict[str, str]:
    roots = ("app", "analytics", "core", "feature", "finance", "transfer", "widget")
    return {
        path.relative_to(ROOT).as_posix(): path.read_text(encoding="utf-8")
        for root in roots
        for path in sorted((ROOT / root).glob("**/src/main/**/*.kt"))
        if "/build/" not in path.as_posix()
    }


def test_sources() -> dict[str, str]:
    return {
        path.relative_to(ROOT).as_posix(): path.read_text(encoding="utf-8")
        for path in sorted(ROOT.glob("**/src/**/*Test.kt"))
        if "/build/" not in path.as_posix()
    }


def validate_target_scale(
    fixture: str | None = None,
    budgets: dict | None = None,
) -> list[str]:
    fixture = fixture or read("app/src/benchmark/kotlin/app/ledger/app/P35BenchmarkFixtureProvider.kt")
    budgets = budgets or json.loads(BUDGETS.read_text(encoding="utf-8"))
    errors: list[str] = []
    expected_literals = {
        "CURRENT_TRANSACTIONS = 500_000": budgets["targetScale"]["currentTransactions"],
        "ATTACHMENT_FILES = 50_000": budgets["targetScale"]["attachmentFiles"],
        "MERCHANTS_AND_PLACES = 5_000": budgets["targetScale"]["merchants"],
        "BATCH_SIZE = 1_000": budgets["deviceAudit"]["financialSeedBatchRowsMax"],
    }
    for marker, _ in expected_literals.items():
        if marker not in fixture:
            errors.append(f"target-scale fixture marker missing: {marker}")
    for marker in (
        "transaction_revision",
        "posting",
        "transaction_revision_attachment",
        "writeObjectFiles",
        "marker.readTextOrNull()",
        "DatabaseMaintenance.checkpoint",
        "EXPLAIN QUERY PLAN",
        "heapGrowth < 64L * 1024L * 1024L",
        "fdGrowth <= 8L",
    ):
        if marker not in fixture:
            errors.append(f"target-scale bounded audit marker missing: {marker}")
    if "src/benchmark" not in "app/src/benchmark/kotlin/app/ledger/app/P35BenchmarkFixtureProvider.kt":
        errors.append("fixture must remain benchmark-only")
    if budgets["targetScale"]["historyAndChanges"] != 2_000_000:
        errors.append("history/change target must remain two million")
    if budgets["targetScale"]["streamBytes"] < 40 * 1024**3:
        errors.append("stream target is not tens of GiB")
    return errors


def validate_benchmark_toolchain(
    benchmark: str | None = None,
    baseline: str | None = None,
    monitor: str | None = None,
) -> list[str]:
    benchmark = benchmark or read("benchmark/src/main/kotlin/app/ledger/benchmark/P35Macrobenchmark.kt")
    baseline = baseline or read("app/src/main/baseline-prof.txt")
    monitor = monitor or read("core/designsystem/src/main/kotlin/app/ledger/core/designsystem/LedgerPerformanceMonitor.kt")
    errors: list[str] = []
    for marker in (
        "P35TargetScaleAuditDeviceTest",
        "P35LedgerMacrobenchmark",
        "P35BaselineProfileGenerator",
        "StartupTimingMetric()",
        "FrameTimingMetric()",
        "MemoryUsageMetric",
        "CompilationMode.Partial(BaselineProfileMode.Require)",
        "StartupMode.COLD",
        "iterations = 3",
    ):
        if marker not in benchmark:
            errors.append(f"macrobenchmark marker missing: {marker}")
    if not baseline.strip() or "MainActivity" not in baseline or "AppRootViewModel" not in baseline:
        errors.append("baseline profile lacks startup and application hot paths")
    for marker in ("JankStats.createAndTrack", "LedgerPerformanceScene", "jankyFrames", "maximumDurationNanos"):
        if marker not in monitor:
            errors.append(f"JankStats aggregate marker missing: {marker}")
    if re.search(r"note|amount|merchant|card", monitor, re.IGNORECASE):
        errors.append("JankStats monitor must contain aggregate timing only")
    main_activity = read("app/src/main/kotlin/app/ledger/app/MainActivity.kt")
    if main_activity.find("setContent { LedgerAppRoot(viewModel) }") > main_activity.find("LedgerJankMonitor.attach(window)"):
        errors.append("JankStats must attach only after Activity content creates the DecorView")
    if "window.decorView" not in monitor:
        errors.append("JankStats attachment lacks a defensive DecorView creation boundary")
    return errors


def validate_bounded_startup(startup: str | None = None, projections: str | None = None) -> list[str]:
    startup = startup or read("finance/data/src/main/kotlin/app/ledger/finance/data/RoomLedgerStartupInspector.kt")
    projections = projections or read("finance/data/src/main/kotlin/app/ledger/finance/data/RoomProjectionEngine.kt")
    errors: list[str] = []
    if "DatabaseIntegrityAudit.run(" in startup or re.search(r"query(?:One)?\([^\n]*PRAGMA integrity_check", startup):
        errors.append("startup must not run the full database integrity audit")
    if "mismatchedFamiliesAtStartup" not in startup:
        errors.append("startup lacks the bounded projection-generation gate")
    if "SELECT COUNT(*) FROM current_transaction_subtype_audit" in startup:
        errors.append("startup must not scan the full transaction subtype audit")
    for marker in (
        "fun mismatchedFamiliesAtStartup",
        "ProjectionFamily.entries.forEach",
        "SELECT COUNT(*) FROM projection_family_state WHERE family=? AND as_of_local_revision=?",
        "AND as_of_valuation_revision=?",
    ):
        if marker not in projections:
            errors.append(f"bounded startup projection marker missing: {marker}")
    if "ROW_COUNT_EXPECTATIONS.forEach" not in projections or "fun mismatchedFamilies(" not in projections:
        errors.append("full maintenance projection audit was removed")
    return errors


FAULT_MARKERS = {
    "attachment-db-boundary": "cancellationDatabaseFailureAndInterruptedProcessLeaveNoReferencedMissingObject",
    "drive-resume": "interruptionResumesAtPersisted256KiBBoundaryWithoutNewSession",
    "saf-revoke": "revokedPermissionReturnsTypedStateAndCancelCleanupRemovesAppTemporary",
    "storage-full": "everyExchangeFaultIncludingStorageFullRollsBackDatabaseKeyAndArtifacts",
    "restore-before-after-swap": "processDeathBeforeFinalizeRollsBackButAfterFinalizeKeepsVerifiedRestore",
    "keystore": "recoveryWrappedVaultDekIsReboundToFreshDeviceAuthenticationKek",
    "biometric": "independentActionsUseFreshCryptoObjectsAndBackgroundClearsEveryExposure",
    "import-row-99999": "validationFailureAtSourceRow99999LeavesPrimaryLedgerStateUnchanged",
    "budget-ten-years": "tenYearHistoryEditRecomputesEveryLaterRolloverWithoutUnboundedState",
    "projection-version": "staleProjectionIsNotShownAndFactRebuildRepairsToIdenticalHash",
}


def validate_fault_matrix(tests: dict[str, str] | None = None) -> list[str]:
    combined = "\n".join((tests or test_sources()).values())
    return [f"fault injection evidence missing: {name}" for name, marker in FAULT_MARKERS.items() if marker not in combined]


def validate_security_boundary(sources: dict[str, str] | None = None) -> list[str]:
    sources = sources or production_sources()
    errors: list[str] = []
    combined = "\n".join(sources.values())
    app_manifest = ElementTree.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
    android = "{http://schemas.android.com/apk/res/android}"
    if app_manifest.find("application").attrib.get(android + "networkSecurityConfig") != "@xml/network_security_config":
        errors.append("application lacks explicit network security config")
    network = ElementTree.parse(ROOT / "app/src/main/res/xml/network_security_config.xml").getroot()
    base = network.find("base-config")
    if base is None or base.attrib.get("cleartextTrafficPermitted") != "false":
        errors.append("release cleartext traffic is not fail-closed")
    if re.search(r"import\s+android\.webkit\.(?:WebView|WebViewClient)", combined):
        errors.append("production contains an unapproved embedded WebView")
    benchmark_manifest = read("app/src/benchmark/AndroidManifest.xml")
    for marker in ('android:exported="true"', 'android:permission="android.permission.DUMP"'):
        if marker not in benchmark_manifest:
            errors.append(f"benchmark provider protection missing: {marker}")
    if any("P35BenchmarkFixtureProvider" in source for source in sources.values()):
        errors.append("benchmark fixture leaked into production source set")
    for path, source in sources.items():
        if re.search(r"Log\.[vdiew]\s*\([^\n]*(?:note|amount|cardNumber|securityCode)", source, re.IGNORECASE):
            errors.append(f"possible business payload log in {path}")
    return errors


def validate_ledgers() -> list[str]:
    errors: list[str] = []
    with REQUIREMENT_LEDGER.open(encoding="utf-8-sig", newline="") as handle:
        rows = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    row = rows.get("REQ-084")
    if row is None or row.get("status") != "VERIFIED" or "P35" not in row.get("verification_evidence", ""):
        errors.append("REQ-084 lacks P35 VERIFIED evidence")
    state = PROJECT_STATE.read_text(encoding="utf-8")
    if not re.search(r"P35[^\n]*VERIFIED", state):
        errors.append("PROJECT_STATE does not mark P35 VERIFIED")
    if not P35_AUDIT.is_file() or "API 28 emulator" not in P35_AUDIT.read_text(encoding="utf-8") or "API 36 emulator" not in P35_AUDIT.read_text(encoding="utf-8"):
        errors.append("P35 audit lacks explicit API 28/API 36 emulator provenance")
    return errors


def validate_all(include_ledgers: bool = True) -> list[str]:
    errors = validate_target_scale()
    errors += validate_benchmark_toolchain()
    errors += validate_bounded_startup()
    errors += validate_fault_matrix()
    errors += validate_security_boundary()
    if include_ledgers:
        errors += validate_ledgers()
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--implementation-only", action="store_true")
    args = parser.parse_args()
    errors = validate_all(include_ledgers=not args.implementation_only)
    if errors:
        print("P35 performance/security validation failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    scope = "implementation contracts" if args.implementation_only else "implementation contracts and ledgers"
    print(f"P35 performance/security validation passed: {scope} are closed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
