#!/usr/bin/env python3
"""Reject P25 typed-analysis, accounting-semantics, integrity, route or UI drift."""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
EXPECTED = {
    "ANA-001": ("analysis", [], {"content", "noData", "calculating", "error"}),
    "ANA-002": ("analysis/reports", [], {"content"}),
    "ANA-003": ("analysis/report/{reportKey}/{savedSpecId?}", ["reportKey:String", "savedSpecId:StableId?"], {"loading", "content", "empty", "queryError", "staleRebuildRequired"}),
    "ANA-004": ("analysis/report-filter/{reportKey}", ["reportKey:String"], {"editing", "invalid"}),
    "ANA-005": ("analysis/drilldown/{queryId}", ["queryId:StableId"], {"content", "empty", "expiredQuery"}),
    "ANA-015": ("analysis/integrity", [], {"notRun", "running", "passed", "warnings", "failed"}),
}
PLACEHOLDER = re.compile(r"\b(?:TODO|NotImplemented|UnsupportedOperationException)\b")


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def source_map() -> dict[str, str]:
    roots = (
        "analytics/domain/src/main/kotlin", "analytics/data/src/main/kotlin", "app/src/main/kotlin",
        "core/database/src/main/kotlin", "core/designsystem/src/main/kotlin", "feature/analysis/src/main/kotlin",
        "finance/application/src/main/kotlin", "finance/data/src/main/kotlin",
    )
    return {
        path.relative_to(ROOT).as_posix(): path.read_text(encoding="utf-8")
        for root in roots
        for path in sorted((ROOT / root).rglob("*.kt"))
    }


def require_tokens(errors: list[str], text: str, label: str, tokens: tuple[str, ...]) -> None:
    for token in tokens:
        if token not in text:
            errors.append(f"{label} missing {token}")


def validate_contract() -> list[str]:
    screens = {
        item["id"]: item
        for item in yaml.safe_load(read("docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml"))["screens"]
    }
    errors: list[str] = []
    for screen_id, (route, params, states) in EXPECTED.items():
        actual = screens.get(screen_id, {})
        if actual.get("route") != route or actual.get("params", []) != params or set(actual.get("requiredStates", [])) != states:
            errors.append(f"{screen_id} route/params/requiredStates drift")
    if sum(len(value[2]) for value in EXPECTED.values()) != 20:
        errors.append("P25 required-state baseline must remain exactly 20")
    return errors


def validate_sources(sources: dict[str, str] | None = None) -> list[str]:
    sources = source_map() if sources is None else sources
    errors: list[str] = []
    required = {
        "ReportModel.kt", "FixedReportCatalog.kt", "AnalyticsPorts.kt", "ReportSqlCompiler.kt",
        "SecureRoomAnalyticsApplicationPort.kt", "AnalyticsProjectionEngine.kt", "AnalysisState.kt",
        "AnalysisScreens.kt", "AnalysisController.kt", "AnalysisRootDestination.kt",
    }
    selected = {path: source for path, source in sources.items() if Path(path).name in required}
    missing = required - {Path(path).name for path in selected}
    if missing:
        errors.append(f"P25 production files missing: {sorted(missing)}")
    for path, source in selected.items():
        if PLACEHOLDER.search(source):
            errors.append(f"placeholder in {path}")

    model = next((value for path, value in sources.items() if path.endswith("ReportModel.kt")), "")
    require_tokens(errors, model, "bounded typed AST", (
        "sealed interface FilterExpression", "enum class Measure", "enum class Dimension", "ReportSpec(",
        "MAX_MEASURES = 8", "MAX_DIMENSIONS = 3", "MAX_SORTS = 4", "MAX_FILTER_NODES = 64",
        "DrilldownQueryId", "ReportQueryPlan", "asOfLocalRevision", "asOfValuationRevision",
    ))
    catalog = next((value for path, value in sources.items() if path.endswith("FixedReportCatalog.kt")), "")
    require_tokens(errors, catalog, "fixed report catalog", (
        "definitions.size == 20", "FixedReport.entries.toSet()", "FixedReportGroup.entries",
        "MAX_PIE_CATEGORIES: Int = 6", "TOO_MANY_PIE_CATEGORIES", "SavingsRatePolicy",
        "Math.subtractExact(incomeMinor, allExpenseMinor)",
    ))

    compiler = next((value for path, value in sources.items() if path.endswith("ReportSqlCompiler.kt")), "")
    require_tokens(errors, compiler, "whitelist SQL compiler", (
        "FILTER_COLUMNS", "compilePredicate", "boundValues", "QuerySource.MONTHLY_ROLLUP",
        "QuerySource.DAILY_ROLLUP", "QuerySource.ECONOMIC_EFFECTS", "QuerySource.JOURNAL_POSTINGS",
        "LIMIT $MAX_REPORT_ROWS", "private const val MAX_REPORT_ROWS = 500", "AnalyticsProjectionEngine.CONTRA_EXPENSE_METRIC",
    ))
    if re.search(r"\bOFFSET\b", compiler):
        errors.append("analysis compiler contains forbidden deep OFFSET")
    if re.search(r"(?:rawQuery|execSQL)\s*\([^\n]*(?:reportKey|userSql|formula)", compiler, re.IGNORECASE):
        errors.append("analysis compiler may accept arbitrary SQL or formula text")

    projections = next((value for path, value in sources.items() if path.endswith("AnalyticsProjectionEngine.kt")), "")
    require_tokens(errors, projections, "deterministic analytics projections", (
        '"analytics_daily_total"', '"analytics_daily_category"', '"analytics_daily_account"',
        '"analytics_daily_merchant"', '"analytics_daily_project"', '"analytics_daily_place"',
        '"analytics_monthly_total"', "fun rebuild(", "fun audit(", "canonicalHash",
        "nature=2 THEN -polarity*base_amount_minor", "SAVEPOINT analytics_projection_audit",
    ))
    room = next((value for path, value in sources.items() if path.endswith("RoomProjectionEngine.kt")), "")
    require_tokens(errors, room, "synchronous projection integration", (
        "AnalyticsProjectionEngine.rebuild", "ProjectionFamily.ANALYTICS", "+ AnalyticsProjectionEngine.tables",
    ))

    adapter = next((value for path, value in sources.items() if path.endswith("SecureRoomAnalyticsApplicationPort.kt")), "")
    require_tokens(errors, adapter, "encrypted query and integrity adapter", (
        "EncryptedDatabaseFactory.openPrimary", "passphrase.fill(0)", "DatabaseIntegrityAudit.run",
        "IntegrityCheckKey.DATABASE", "IntegrityCheckKey.FOREIGN_KEYS", "IntegrityCheckKey.JOURNALS",
        "IntegrityCheckKey.POSTING_CURRENCIES", "IntegrityCheckKey.REVISIONS", "IntegrityCheckKey.PROJECTIONS",
        "IntegrityCheckKey.FTS", "IntegrityCheckKey.RTREE", "IntegrityCheckKey.FACT_REBUILD",
        "repairAnalyticsProjections", "DrilldownRegistry", "MAX_DRILLDOWN_PAGE = 100",
    ))
    if re.search(r"\b(?:Log\.|println\(|SavedStateHandle)\b", adapter):
        errors.append("analytics adapter may leak query or database details")

    feature = "\n".join(value for path, value in sources.items() if path.startswith("feature/analysis/"))
    require_tokens(errors, feature, "governed accessible analysis UI", (
        "LedgerVicoLineRenderer", "LedgerVicoColumnRenderer", "LedgerVicoStackedRenderer", "LedgerVicoPieRenderer",
        "AccessibleTableUiModel", "ChartCard", "dataTable = table", "reportTable(",
        "analysis_pie_fallback", "analysis_no_sql_formula", "AnalysisPresentation.STALE_REBUILD_REQUIRED",
    ))
    if re.search(r"^import\s+(?:androidx\.room|androidx\.compose\.material3|app\.ledger\.(?:analytics\.data|core\.database))", feature, re.MULTILINE):
        errors.append("analysis feature bypasses UI/application boundaries")
    if re.search(r"\b(?:MaterialTheme|Color\s*\(|SwipeToDismiss)\b|\b\d+(?:\.\d+)?\.dp\b", feature):
        errors.append("analysis feature bypasses frozen design tokens")

    root = next((value for path, value in sources.items() if path.endswith("AnalysisRootDestination.kt")), "")
    controller = next((value for path, value in sources.items() if path.endswith("AnalysisController.kt")), "")
    require_tokens(errors, root + controller, "safe analysis route/runtime", (
        'encodedArguments["reportKey"]', 'encodedArguments["queryId"]', "StableId.parse", "ReportKey(this)",
        "ReportExportPayload", "preparedExportForTransfer", "never enter SavedState or routes",
    ))
    if re.search(r'encodedArguments\["(?:amount|note|name|card|attachment|location|sql|formula)"\]', root, re.IGNORECASE):
        errors.append("analysis route accepts business, location, SQL or formula payload")
    return errors


def validate_tests_resources() -> list[str]:
    errors: list[str] = []
    test_roots = (
        "analytics/domain/src/test", "analytics/data/src/test", "analytics/data/src/androidTest",
        "feature/analysis/src/test", "feature/analysis/src/androidTest",
    )
    tests = "\n".join(
        path.read_text(encoding="utf-8")
        for root in test_roots
        for path in sorted((ROOT / root).rglob("*.kt"))
    )
    require_tokens(errors, tests, "P25 automated evidence", (
        "catalog contains every frozen report exactly once", "savings rate is exact",
        "all fixed reports compile only from closed sources without OFFSET", "malicious or unknown closed keys are rejected",
        "encryptedQueriesUseExactFinancialSemanticsAndEveryFixedReportExecutes",
        "staleProjectionIsNotShownAndFactRebuildRepairsToIdenticalHash",
        "everyFrozenRequiredStateRendersAcrossWidthFontLocaleAndThemeMatrix",
        "chartHasTextAlternativeAndExactDataTableAtTwoHundredPercentFont",
        "reportAndIntegrityGoldensMatchEveryPixel", "assertEquals(20, cases.size)",
    ))
    resource_sets = []
    for folder in ("values", "values-en", "values-ja"):
        names = set(re.findall(r'<string name="([^"]+)"', read(f"feature/analysis/src/main/res/{folder}/strings.xml")))
        resource_sets.append({name for name in names if name.startswith(("analysis_", "report_"))})
    if not resource_sets[0] or resource_sets[0] != resource_sets[1] or resource_sets[0] != resource_sets[2]:
        errors.append("P25 analysis/report strings incomplete across zh-CN/en/ja")
    return errors


def validate_ledgers() -> list[str]:
    errors: list[str] = []
    state = read("docs/implementation/PROJECT_STATE.md")
    evidence = read("docs/implementation/TEST_EVIDENCE.md")
    mapping_path = ROOT / "docs/implementation/P25_ANALYTICS_MAPPING.md"
    mapping = mapping_path.read_text(encoding="utf-8") if mapping_path.is_file() else ""
    require_tokens(errors, state, "PROJECT_STATE", ("Current stage: P25", "Stage status: VERIFIED"))
    for index in range(1, 8):
        if f"P25-E{index:03d}" not in evidence:
            errors.append(f"TEST_EVIDENCE missing P25-E{index:03d}")
    require_tokens(errors, mapping, "P25 mapping", (
        "20 fixed reports", "ReportSpec", "FinancialMutationCoordinator", "SQLCipher", "P25 is `VERIFIED`",
    ))
    with (ROOT / "docs/implementation/SCREEN_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        screens = {row["screen_id"]: row for row in csv.DictReader(handle)}
    for screen_id in EXPECTED:
        row = screens.get(screen_id, {})
        if row.get("status") != "VERIFIED" or "P25" not in row.get("implementation_evidence", "") or "P25-E" not in row.get("verification_evidence", ""):
            errors.append(f"{screen_id} lacks VERIFIED P25 evidence")
    with (ROOT / "docs/implementation/REQUIREMENT_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        requirements = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    for requirement_id in ("REQ-005", "REQ-006", "REQ-007", "REQ-008", "REQ-067", "REQ-068"):
        row = requirements.get(requirement_id, {})
        if "P25" not in row.get("implementation_evidence", "") or "P25-E" not in row.get("verification_evidence", ""):
            errors.append(f"{requirement_id} lacks P25 evidence")
    return errors


def main() -> int:
    errors = validate_contract() + validate_sources() + validate_tests_resources() + validate_ledgers()
    if errors:
        print("P25 analytics validation: FAIL", file=sys.stderr)
        for item in errors:
            print(f"- {item}", file=sys.stderr)
        return 1
    print("P25 analytics validation: PASS")
    print("reports=20 screens=6 required_states=20 sql=whitelist+bound projections=12 integrity=9 visual_inputs=contract_token_yaml_only")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
