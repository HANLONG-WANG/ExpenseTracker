#!/usr/bin/env python3
"""Reject P26 custom-analysis, deterministic-engine, encrypted-persistence, route or UI drift."""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
EXPECTED = {
    "ANA-006": ("analysis/dashboards", [], {"content", "empty"}),
    "ANA-007": ("analysis/dashboards/editor/{dashboardId?}", ["dashboardId:StableId?"], {"create", "edit", "emptyCanvas"}),
    "ANA-008": ("analysis/report-builder/{definitionId?}", ["definitionId:StableId?"], {"editing", "invalid", "previewing"}),
    "ANA-009": ("analysis/visualization-picker", [], {"content", "autoFallbackToBar"}),
    "ANA-010": ("analysis/export/{reportInstanceId}", ["reportInstanceId:StableId"], {"content"}),
    "ANA-013": ("analysis/anomaly-rules", [], {"content", "empty", "invalid"}),
    "ANA-014": ("analysis/forecast/{forecastKey}", ["forecastKey:String"], {"content", "insufficientData"}),
}
PLACEHOLDER = re.compile(r"\b(?:TODO|NotImplemented|UnsupportedOperationException)\b")


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def source_map() -> dict[str, str]:
    roots = (
        "analytics/domain/src/main/kotlin", "analytics/data/src/main/kotlin", "app/src/main/kotlin",
        "core/database/src/main/kotlin", "core/designsystem/src/main/kotlin", "core/security/src/main/kotlin",
        "feature/analysis/src/main/kotlin",
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
    if sum(len(value[2]) for value in EXPECTED.values()) != 16:
        errors.append("P26 required-state baseline must remain exactly 16")
    return errors


def validate_sources(sources: dict[str, str] | None = None) -> list[str]:
    sources = source_map() if sources is None else sources
    errors: list[str] = []
    required = {
        "CustomAnalytics.kt", "CustomAnalyticsStore.kt", "SecureRoomAnalyticsApplicationPort.kt", "ReportModel.kt",
        "AnalysisState.kt", "AnalysisScreens.kt", "P26AnalysisScreens.kt", "AnalysisController.kt", "AnalysisRootDestination.kt",
        "LedgerMigrations.kt", "LedgerSchemaDefinition.kt", "BookSessionManager.kt",
    }
    selected = {path: value for path, value in sources.items() if Path(path).name in required}
    missing = required - {Path(path).name for path in selected}
    if missing:
        errors.append(f"P26 production files missing: {sorted(missing)}")
    for path, value in selected.items():
        if PLACEHOLDER.search(value):
            errors.append(f"placeholder in {path}")

    domain = next((value for path, value in sources.items() if path.endswith("CustomAnalytics.kt")), "")
    require_tokens(errors, domain, "deterministic analytics domain", (
        "DefaultDeterministicAnalyticsEngine", "AnalyticsAlgorithmVersion", "HISTORICAL_MEAN_STANDARD_DEVIATION",
        "RECENT_MONTH_GROWTH_THRESHOLD", "LARGE_SINGLE_TRANSACTION", "MERCHANT_FREQUENCY", "CATEGORY_FREQUENCY",
        "CURRENT_DAILY_AVERAGE", "DAILY_AVERAGE_WITH_RECURRENCE", "HISTORICAL_SAME_MONTH",
        "ReportDerivationPolicy", "MOVING_AVERAGE", "TREND", "FORECAST", "Math.multiplyExact", "BigInteger", "BigDecimal",
    ))
    if re.search(r"\b(?:Double|Float)\b", domain):
        errors.append("deterministic analytics domain contains floating-point authority")

    schema = read("core/database/src/main/assets/ledger_schema_v2_analytics_configuration.sql")
    require_tokens(errors, schema, "normalized encrypted analytics schema", (
        "analytics_report_definition", "analytics_report_revision", "analytics_report_measure", "analytics_report_dimension",
        "analytics_report_sort", "analytics_report_filter_node", "analytics_report_filter_value", "analytics_dashboard",
        "analytics_dashboard_revision", "analytics_dashboard_item", "analytics_anomaly_rule", "analytics_anomaly_rule_revision",
        "_reject_update", "REFERENCES", "UNIQUE",
    ))
    if re.search(r"\b(?:json|payload|blob_data|definition_json)\b", schema, re.IGNORECASE):
        errors.append("P26 configuration schema contains a generic JSON/payload column")

    store = next((value for path, value in sources.items() if path.endswith("CustomAnalyticsStore.kt")), "")
    adapter = next((value for path, value in sources.items() if path.endswith("SecureRoomAnalyticsApplicationPort.kt")), "")
    require_tokens(errors, store + adapter, "encrypted revisioned persistence", (
        "EncryptedDatabaseFactory.openPrimary", "inLedgerTransaction", "expectedRowVersion", "AnalyticsError.RevisionConflict",
        "saveReport", "copyReport", "saveDashboard", "saveAnomalyRule", "anomalyFindings", "forecast",
        "passphrase.fill(0)", "ReportDerivationPolicy.derive",
    ))
    if re.search(r"\b(?:SharedPreferences|DataStore|Room\.databaseBuilder|fallbackToDestructiveMigration)\b", store):
        errors.append("custom analytics bypasses the encrypted primary database")

    session = next((value for path, value in sources.items() if path.endswith("BookSessionManager.kt")), "")
    require_tokens(errors, session, "schema-aware security startup inspection", (
        "LedgerMigrations.CURRENT_VERSION", "logicalSchemaVersion = ${LedgerMigrations.CURRENT_VERSION}",
    ))
    if "logicalSchemaVersion = 1" in session:
        errors.append("security startup inspection is pinned to obsolete schema v1")

    feature = "\n".join(value for path, value in sources.items() if path.startswith("feature/analysis/"))
    require_tokens(errors, feature, "P26 governed accessible UI", (
        '"ANA-006"', '"ANA-007"', '"ANA-008"', '"ANA-009"', '"ANA-010"', '"ANA-013"', '"ANA-014"',
        "DASHBOARD_LIST", "DASHBOARD_EDITOR", "REPORT_BUILDER", "VISUALIZATION_PICKER", "REPORT_EXPORT",
        "ANOMALY_RULES", "FORECAST_DETAIL", "AccessibleTableUiModel", "ChartCard", "LedgerChoiceRow",
        "analysis_anomaly_disclosure", "analysis_forecast_version", "analysis_start_export_flow",
        "AnalysisExportScope.entries", "actions.onPrepareExport",
        "analysis_copy_custom_report", "onMoveDashboardReport", "analysis_drilldown",
    ))
    p26_feature = next((value for path, value in sources.items() if path.endswith("P26AnalysisScreens.kt")), "")
    require_tokens(errors, p26_feature, "P26 screen accessibility", ("AccessibleTableUiModel", "ChartCard"))
    if re.search(r"^import\s+(?:androidx\.room|androidx\.compose\.material3|app\.ledger\.(?:analytics\.data|core\.database))", feature, re.MULTILINE):
        errors.append("P26 feature bypasses UI/application boundaries")
    if re.search(r"\b(?:MaterialTheme|Color\s*\(|SwipeToDismiss)\b|\b\d+(?:\.\d+)?\.dp\b", feature):
        errors.append("P26 feature bypasses frozen design tokens or deletion governance")

    all_production = "\n".join(sources.values())
    if re.search(r"\b(?:OpenAI|Gemini|MLKit|Tesseract|OCR|ai\.sdk|userFormula|eval\s*\(|ScriptEngine)\b", all_production, re.IGNORECASE):
        errors.append("P26 introduces AI/OCR or a user script/formula execution surface")

    root = next((value for path, value in sources.items() if path.endswith("AnalysisRootDestination.kt")), "")
    navigation = next((value for path, value in sources.items() if path.endswith("AppRootViewModel.kt")), "")
    require_tokens(errors, root + navigation, "safe P26 routes", (
        'encodedArguments["dashboardId"]', 'encodedArguments["definitionId"]', 'encodedArguments["reportInstanceId"]',
        'encodedArguments["forecastKey"]', "ForecastKey::fromRouteKey", "StableIdArgument", "opaqueKeyArgument",
    ))
    if re.search(r'encodedArguments\["(?:name|amount|note|card|attachment|location|formula|sql|spec|dashboard)"\]', root, re.IGNORECASE):
        errors.append("P26 route accepts sensitive or rich business payload")
    return errors


def validate_tests_resources() -> list[str]:
    errors: list[str] = []
    tests = "\n".join(
        path.read_text(encoding="utf-8")
        for root in (
            "analytics/domain/src/test", "analytics/data/src/androidTest", "core/database/src/androidTest",
            "feature/analysis/src/test", "feature/analysis/src/androidTest",
        )
        for path in sorted((ROOT / root).rglob("*.kt"))
    )
    require_tokens(errors, tests, "P26 automated evidence", (
        "equal anomaly inputs and algorithm version produce equal disclosed results",
        "month end forecast uses exact integer path and recurrence only when selected",
        "historical same month model is deterministic and never fills missing years with zero",
        "derived moving average trend and forecast are exact versioned series",
        "customReportDashboardAnomalyAndForecastRoundTripThroughNormalizedEncryptedSchema",
        "everyEncryptedPredecessorMigratesToVersionFiveWithFinancialAndQueryContractsIntact",
        "builderAndForecastGoldensMatchEveryPixel",
        "assertEquals(45, cases.size)", '"ANA-014" to setOf("content", "insufficientData")',
    ))
    localized = []
    for folder in ("values", "values-en", "values-ja"):
        names = set(re.findall(r'<string name="([^"]+)"', read(f"feature/analysis/src/main/res/{folder}/strings.xml")))
        localized.append({name for name in names if name.startswith(("analysis_", "report_"))})
    if not localized[0] or localized[0] != localized[1] or localized[0] != localized[2]:
        errors.append("P26 analysis strings are incomplete across zh-CN/en/ja")
    return errors


def validate_ledgers() -> list[str]:
    errors: list[str] = []
    state = read("docs/implementation/PROJECT_STATE.md")
    evidence = read("docs/implementation/TEST_EVIDENCE.md")
    mapping = read("docs/implementation/P26_CUSTOM_ANALYTICS_MAPPING.md") if (ROOT / "docs/implementation/P26_CUSTOM_ANALYTICS_MAPPING.md").is_file() else ""
    require_tokens(errors, state, "PROJECT_STATE", ("Current stage: P36", "| P26 | VERIFIED |"))
    for index in range(1, 8):
        if f"P26-E{index:03d}" not in evidence:
            errors.append(f"TEST_EVIDENCE missing P26-E{index:03d}")
    require_tokens(errors, mapping, "P26 mapping", ("Schema v2", "deterministic", "16 required states", "P29", "P26 is `VERIFIED`"))
    with (ROOT / "docs/implementation/SCREEN_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        screens = {row["screen_id"]: row for row in csv.DictReader(handle)}
    for screen_id in EXPECTED:
        row = screens.get(screen_id, {})
        if row.get("status") != "VERIFIED" or "P26" not in row.get("implementation_evidence", "") or "P26-E" not in row.get("verification_evidence", ""):
            errors.append(f"{screen_id} lacks VERIFIED P26 evidence")
    return errors


def main() -> int:
    errors = validate_contract() + validate_sources() + validate_tests_resources() + validate_ledgers()
    if errors:
        print("P26 custom analytics validation: FAIL", file=sys.stderr)
        for item in errors:
            print(f"- {item}", file=sys.stderr)
        return 1
    print("P26 custom analytics validation: PASS")
    print("screens=7 required_states=16 schema=v2-normalized algorithms=versioned+deterministic routes=stable-id+closed-key export=p29-interface-only")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
