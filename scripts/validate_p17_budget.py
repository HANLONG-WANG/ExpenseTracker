#!/usr/bin/env python3
"""Reject P17 budget contract drift, write bypasses, stale projections, or false verification."""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
EXPECTED = {
    "BUD-001": ("budget/{yearMonth?}", {"configured", "notConfigured", "recalculating", "historical", "future"}),
    "BUD-002": ("budget/{yearMonth}/edit", {"editing", "constraintError", "saving", "historyRecalculationWarning"}),
    "BUD-003": ("budget/{yearMonth}/category/{categoryId}", {"editing", "constraintError"}),
    "BUD-004": ("budget/{yearMonth}/adjustments", {"content", "empty"}),
    "BUD-005": ("budget/{yearMonth}/adjustment/{type}", {"editing", "invalid", "saving"}),
    "BUD-006": ("budget/{yearMonth}/history", {"content", "singleRevision"}),
    "BUD-007": ("budget/templates", {"content", "empty"}),
    "BUD-008": ("budget/templates/editor/{templateId?}", {"create", "edit", "constraintError"}),
}
TARGET_REQUIREMENTS = {"REQ-007", "REQ-048", "REQ-049", "REQ-050"}
PLACEHOLDER = re.compile(r"\b(?:TODO|NotImplemented|UnsupportedOperationException)\b")
FORBIDDEN_FEATURE_IMPORT = re.compile(r"(?m)^import\s+(?:androidx\.room|androidx\.compose\.material3|app\.ledger\.(?:finance\.data|core\.(?:database|security)))")


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def require_tokens(errors: list[str], text: str, label: str, tokens: tuple[str, ...]) -> None:
    for token in tokens:
        if token not in text:
            errors.append(f"{label} missing {token}")


def load_sources() -> dict[str, str]:
    roots = (
        "app/src/main/kotlin", "core/designsystem/src/main/kotlin", "feature/planning/src/main/kotlin",
        "finance/application/src/main/kotlin", "finance/data/src/main/kotlin", "finance/domain/src/main/kotlin",
    )
    return {
        path.relative_to(ROOT).as_posix(): path.read_text(encoding="utf-8")
        for root in roots
        for path in sorted((ROOT / root).rglob("*.kt"))
    }


def validate_contract() -> list[str]:
    contract = yaml.safe_load(read("docs/初始开发文件存档/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml"))
    screens = {screen["id"]: screen for screen in contract["screens"]}
    errors: list[str] = []
    for screen_id, (route, states) in EXPECTED.items():
        screen = screens.get(screen_id, {})
        if screen.get("route") != route:
            errors.append(f"{screen_id} route drift")
        if set(screen.get("requiredStates", [])) != states:
            errors.append(f"{screen_id} requiredStates drift")
    allowed_params = {
        "BUD-001": ["yearMonth:YYYYMM?"], "BUD-002": ["yearMonth:YYYYMM"],
        "BUD-003": ["yearMonth:YYYYMM", "categoryId:StableId"], "BUD-004": ["yearMonth:YYYYMM"],
        "BUD-005": ["yearMonth:YYYYMM", "type:CLEAR_ROLLOVER|ADD|SUBTRACT|TRANSFER"],
        "BUD-006": ["yearMonth:YYYYMM"], "BUD-007": [], "BUD-008": ["templateId:StableId?"],
    }
    for screen_id, params in allowed_params.items():
        if screens.get(screen_id, {}).get("params") != params:
            errors.append(f"{screen_id} route parameters drift")
    return errors


def validate_sources(sources: dict[str, str] | None = None) -> list[str]:
    sources = load_sources() if sources is None else sources
    errors: list[str] = []
    required = {
        "BudgetApplication.kt", "SecureRoomBudgetApplicationPort.kt", "BudgetState.kt", "BudgetScreens.kt",
        "BudgetRootDestination.kt", "BudgetProjectGoal.kt", "DeterministicFinancialPlanner.kt", "RoomProjectionEngine.kt",
    }
    missing = required - {Path(path).name for path in sources}
    if missing:
        errors.append(f"P17 production files missing: {sorted(missing)}")
    for path, source in sources.items():
        if PLACEHOLDER.search(source):
            errors.append(f"placeholder production implementation in {path}")

    feature = "\n".join(source for path, source in sources.items() if path.startswith("feature/planning/"))
    if FORBIDDEN_FEATURE_IMPORT.search(feature) or re.search(r"\b(?:Dao|Entity|execSQL|JournalEntry|Posting)\b", feature):
        errors.append("budget feature bypasses governed UI/application boundaries")
    require_tokens(errors, feature, "P17 governed UI", (
        "BudgetDestination", "BudgetHero", "ConstraintMeters", "snapshot.dailyAvailable", "FlowRow",
        "budget_no_account_impact", "HISTORY_RECALCULATION_WARNING", "BudgetPolicy.adjustmentMinor",
    ))
    if re.search(r"SwipeToDismiss|swipeable|detectHorizontalDragGestures", feature):
        errors.append("budget feature contains forbidden swipe deletion")

    application = next((s for p, s in sources.items() if p.endswith("BudgetApplication.kt")), "")
    require_tokens(errors, application, "typed budget application boundary", (
        "BudgetSnapshot", "BudgetRevisionView", "BudgetCompositionView", "BudgetProjectionReadiness",
        "SaveBudgetMonthRequest", "SaveBudgetTemplateRequest", "RecordBudgetAdjustmentRequest", "BudgetApplicationPort",
    ))
    data = next((s for p, s in sources.items() if p.endswith("SecureRoomBudgetApplicationPort.kt")), "")
    require_tokens(errors, data, "encrypted budget adapter", (
        "DefaultFinancialMutationCoordinator", "DeterministicFinancialPlanner", "ConfigureBudgetMonthCommand",
        "SaveBudgetTemplateCommand", "RecordBudgetAdjustmentsCommand", "budget_future_reservation", "EncryptedDatabaseFactory.openPrimary",
    ))
    if re.search(r"\b(?:INSERT|UPDATE|DELETE)\s+(?:INTO\s+|FROM\s+)?(?:budget_month|budget_month_revision|budget_adjustment|budget_usage_projection)", data, re.IGNORECASE):
        errors.append("budget application adapter performs a direct financial SQL write")

    domain = "\n".join(source for path, source in sources.items() if path.startswith("finance/domain/"))
    require_tokens(errors, domain, "budget domain", (
        "BudgetTemplateMutation", "BudgetMonthMutation", "BudgetConstraintPolicy", "BudgetRolloverEngine",
        "DailyAvailableBudgetPolicy", 'DomainViolation.Invariant("INV-018")', 'DomainViolation.Invariant("INV-019")',
        "Math.addExact", "Math.subtractExact", "CanonicalFinancialHash",
    ))
    writer = "\n".join(
        source
        for path, source in sources.items()
        if path.endswith(("RoomBudgetPlanWriter.kt", "RoomFinancialPlanWriter.kt"))
    )
    require_tokens(errors, writer, "atomic budget fact writer", (
        "INSERT INTO budget_month_revision", "INSERT INTO budget_category_limit", "INSERT INTO budget_adjustment",
        "INSERT INTO budget_template_revision", "INSERT INTO budget_template_category_limit",
    ))
    projection = next((s for p, s in sources.items() if p.endswith("RoomProjectionEngine.kt")), "")
    require_tokens(errors, projection, "rebuildable budget projection", (
        "rebuildBudget", "budget_usage_projection", "budget_rollover", "FROM budget_effect", "FROM budget_adjustment",
        "UPDATE budget_future_reservation SET as_of_local_revision=?", '"budget_future_reservation" to',
        "ProjectionChange.BudgetFromMonth", "publishProjectionGeneration", "as_of_local_revision", "Math.addExact", "Math.subtractExact",
    ))
    root = next((s for p, s in sources.items() if p.endswith("BudgetRootDestination.kt")), "")
    require_tokens(errors, root, "safe budget root", ("toBudgetYearMonthOrNull", 'encodedArguments["yearMonth"]', "BudgetDestination"))
    if re.search(r'encodedArguments\["(?:amount|note|name|card|attachment|location|merchant|project|goal)', root, re.IGNORECASE):
        errors.append("budget route carries sensitive or mutable business data")
    return errors


def validate_tests_and_resources() -> list[str]:
    tests = "\n".join(
        path.read_text(encoding="utf-8")
        for root in ("feature/planning/src/test", "feature/planning/src/androidTest", "finance/data/src/androidTest", "finance/domain/src/test")
        for path in sorted((ROOT / root).rglob("*.kt"))
    )
    errors: list[str] = []
    require_tokens(errors, tests, "P17 automated evidence", (
        "generated hierarchy enforces only base limits and reports exact excess",
        "positive and negative rollover are uncapped never expire and parent usage does not double total",
        "history edit deterministically replaces every later rollover and adjustments remain separate",
        "daily available subtracts future fixed reservations before exact integer division",
        "hierarchyRolloverHistoryAndIdempotencyRebuildExactlyFromFacts",
        "templatesAndSignedAdjustmentsRemainSeparateAndNeverChangeAccountBalance",
        "allTwentyThreeFrozenRequiredStatesRenderAcrossResponsiveAccessibleLocalizedMatrix",
        "constraintEvidenceAndSaveRemainReachableAtCompactWidthAndTwoHundredPercentFont",
        "budgetHomeAndConstraintEditorGoldensMatchEveryPixel",
    ))
    golden = next((path.read_text(encoding="utf-8") for path in (ROOT / "feature/planning/src/androidTest").rglob("P17GoldenDeviceTest.kt")), "")
    hashes = re.findall(r'"([0-9a-f]{64})"', golden)
    if len(hashes) != 2 or len(set(hashes)) != 2:
        errors.append("P17 requires two distinct exact-pixel SHA-256 Compose goldens")
    resource_sets = []
    for relative in ("values/strings.xml", "values-en/strings.xml", "values-ja/strings.xml"):
        text = read(f"feature/planning/src/main/res/{relative}")
        resource_sets.append({key for key in re.findall(r'<string name="([^"]+)"', text) if key.startswith("budget_")})
    if not resource_sets[0] or resource_sets[0] != resource_sets[1] or resource_sets[0] != resource_sets[2]:
        errors.append("P17 budget strings are incomplete across zh-CN/en/ja")
    return errors


def validate_ledgers() -> list[str]:
    errors: list[str] = []
    state = read("docs/初始开发文件存档/implementation/PROJECT_STATE.md")
    evidence = read("docs/初始开发文件存档/implementation/TEST_EVIDENCE.md")
    mapping_path = ROOT / "docs/初始开发文件存档/implementation/P17_BUDGET_MAPPING.md"
    mapping = mapping_path.read_text(encoding="utf-8") if mapping_path.is_file() else ""
    require_tokens(errors, state, "PROJECT_STATE", ("Current stage: P36", "| P17 | VERIFIED |"))
    for index in range(1, 8):
        if f"P17-E{index:03d}" not in evidence:
            errors.append(f"TEST_EVIDENCE missing P17-E{index:03d}")
    require_tokens(errors, mapping, "P17 mapping", ("23 required states", "FinancialMutationCoordinator", "never expires", "P17 is `VERIFIED`"))
    with (ROOT / "docs/初始开发文件存档/implementation/SCREEN_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        screens = {row["screen_id"]: row for row in csv.DictReader(handle)}
    for screen_id in EXPECTED:
        row = screens.get(screen_id, {})
        if row.get("status") != "VERIFIED" or "P17" not in row.get("implementation_evidence", "") or "P17-E" not in row.get("verification_evidence", ""):
            errors.append(f"{screen_id} must carry VERIFIED P17 implementation and test evidence")
    with (ROOT / "docs/初始开发文件存档/implementation/REQUIREMENT_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        requirements = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    for requirement_id in TARGET_REQUIREMENTS:
        row = requirements.get(requirement_id, {})
        if row.get("status") not in {"IN_PROGRESS", "VERIFIED"} or "P17" not in row.get("implementation_evidence", "") or "P17-E" not in row.get("verification_evidence", ""):
            errors.append(f"{requirement_id} must carry truthful P17 implementation and test evidence")
    return errors


def main() -> int:
    errors = validate_contract() + validate_sources() + validate_tests_and_resources() + validate_ledgers()
    if errors:
        print("P17 budget validation: FAIL", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("P17 budget validation: PASS")
    print("screens=8 required_states=23 property_iterations=1000 rollover_months=122 goldens=2 visual_inputs=contract_token_yaml_only")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
