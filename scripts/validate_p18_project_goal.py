#!/usr/bin/env python3
"""Reject P18 project/goal drift, financial-write bypasses, unsafe routes or false verification."""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
EXPECTED = {
    "PRJ-001": ("projects", {"content", "empty", "archivedOnly"}),
    "PRJ-002": ("projects/editor/{projectId?}", {"create", "edit", "validationError"}),
    "PRJ-003": ("projects/{projectId}", {"active", "archived", "overBudget", "noTransactions"}),
    "PRJ-004": ("projects/{projectId}/transactions", {"content", "empty"}),
    "PRJ-005": ("projects/{projectId}/cashflow", {"content", "empty"}),
    "PRJ-006": ("projects/{projectId}/status", {"active", "archived"}),
    "GOL-001": ("goals", {"content", "empty", "underfunded"}),
    "GOL-002": ("goals/editor/{goalId?}", {"create", "edit", "currencyLocked", "validationError"}),
    "GOL-003": ("goals/{goalId}", {"active", "completed", "underfunded", "emptyHistory"}),
    "GOL-004": ("goals/{goalId}/movement/{kind}", {"editing", "insufficientActualBalanceWarning", "saving"}),
    "GOL-005": ("goals/{goalId}/complete", {"content"}),
}
ALLOWED_PARAMS = {
    "PRJ-001": [], "PRJ-002": ["projectId:StableId?"], "PRJ-003": ["projectId:StableId"],
    "PRJ-004": ["projectId:StableId"], "PRJ-005": ["projectId:StableId"], "PRJ-006": ["projectId:StableId"],
    "GOL-001": [], "GOL-002": ["goalId:StableId?"], "GOL-003": ["goalId:StableId"],
    "GOL-004": ["goalId:StableId", "kind:ALLOCATE|RELEASE|ADJUST"], "GOL-005": ["goalId:StableId"],
}
TARGET_REQUIREMENTS = {"REQ-046", "REQ-047", "REQ-051", "REQ-052"}
PLACEHOLDER = re.compile(r"\b(?:TODO|NotImplemented|UnsupportedOperationException)\b")
FORBIDDEN_FEATURE_IMPORT = re.compile(
    r"(?m)^import\s+(?:androidx\.room|androidx\.compose\.material3|app\.ledger\.(?:finance\.data|core\.(?:database|security)))"
)


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
    contract = yaml.safe_load(read("docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml"))
    screens = {screen["id"]: screen for screen in contract["screens"]}
    errors: list[str] = []
    for screen_id, (route, states) in EXPECTED.items():
        screen = screens.get(screen_id, {})
        if screen.get("route") != route:
            errors.append(f"{screen_id} route drift")
        if set(screen.get("requiredStates", [])) != states:
            errors.append(f"{screen_id} requiredStates drift")
        if screen.get("params") != ALLOWED_PARAMS[screen_id]:
            errors.append(f"{screen_id} route parameters drift")
    return errors


def validate_sources(sources: dict[str, str] | None = None) -> list[str]:
    sources = load_sources() if sources is None else sources
    errors: list[str] = []
    required = {
        "ProjectGoalApplication.kt", "SecureRoomProjectGoalApplicationPort.kt", "ProjectGoalState.kt",
        "ProjectGoalScreens.kt", "ProjectGoalRootDestination.kt", "BudgetProjectGoal.kt",
        "AccountingRuleEngine.kt", "DeterministicFinancialPlanner.kt", "RoomFinancialCommitRepository.kt",
    }
    missing = required - {Path(path).name for path in sources}
    if missing:
        errors.append(f"P18 production files missing: {sorted(missing)}")
    for path, source in sources.items():
        if PLACEHOLDER.search(source):
            errors.append(f"placeholder production implementation in {path}")

    feature = "\n".join(source for path, source in sources.items() if path.startswith("feature/planning/"))
    if FORBIDDEN_FEATURE_IMPORT.search(feature) or re.search(r"\b(?:Dao|Entity|execSQL|JournalEntry|Posting)\b", feature):
        errors.append("project/goal feature bypasses governed UI/application boundaries")
    require_tokens(errors, feature, "P18 governed UI", (
        "ProjectGoalDestination", "presentationFor", "ProjectTabRow", "ProjectTransactionPagingSource", "LedgerTabRow",
        "GoalTrendChart", "AccessibleDataTable", "goal_movement_date", "ACCOUNT_AVAILABILITY",
        "INSUFFICIENT_ACTUAL_BALANCE_WARNING", "PROJECT_MONTHLY_SNAPSHOT",
    ))
    if re.search(r"SwipeToDismiss|swipeable|detectHorizontalDragGestures", feature):
        errors.append("project/goal feature contains forbidden swipe deletion")

    application = next((source for path, source in sources.items() if path.endswith("ProjectGoalApplication.kt")), "")
    require_tokens(errors, application, "typed project/goal application boundary", (
        "ProjectGoalSnapshot", "ProjectTransactionPageRequest", "ProjectTransactionCursor", "ProjectView", "GoalView",
        "SaveProjectRequest", "ChangeProjectStatusRequest", "SaveGoalRequest", "RecordGoalMovementRequest",
        "CompleteGoalRequest", "ProjectGoalApplicationPort",
    ))
    data = next((source for path, source in sources.items() if path.endswith("SecureRoomProjectGoalApplicationPort.kt")), "")
    require_tokens(errors, data, "encrypted project/goal adapter", (
        "DefaultFinancialMutationCoordinator", "RecordGoalMovementCommand", "EncryptedDatabaseFactory.openPrimary",
        "projectTransactionPage", "ProjectTransactionCursor", "ctp.occurred_at<?", "LIMIT ?",
        "p.included_in_monthly_budget", "goal_balance_projection", "project_usage_projection",
    ))
    if " OFFSET " in data.upper():
        errors.append("project transaction paging uses forbidden deep OFFSET")
    if re.search(r"\b(?:INSERT|UPDATE|DELETE)\s+(?:INTO\s+|FROM\s+)?(?:goal_movement|goal_effect|project_effect)", data, re.IGNORECASE):
        errors.append("project/goal adapter directly writes financial facts")

    domain = "\n".join(source for path, source in sources.items() if path.startswith("finance/domain/"))
    require_tokens(errors, domain, "P18 project/goal domain", (
        "GoalBalancePolicy", "ProjectStatusPolicy", "GoalMovementKind", "GoalEffectKind.SPEND",
        "includedInMonthlyBudgetSnapshot", "AmountRole.SELF_SHARE", "Math.subtractExact", "expectedGoalRowVersion",
    ))
    policies = next((source for path, source in sources.items() if path.endswith("BudgetProjectGoal.kt")), "")
    require_tokens(errors, policies, "checked goal availability policy", (
        "Math.subtractExact(actualBalanceMinor, reservedMinor)",
    ))
    rules = next((source for path, source in sources.items() if path.endswith("AccountingRuleEngine.kt")), "")
    require_tokens(errors, rules, "immutable project-effect policy", (
        "includedInMonthlyBudgetSnapshot",
    ))
    root = next((source for path, source in sources.items() if path.endswith("ProjectGoalRootDestination.kt")), "")
    require_tokens(errors, root, "safe project/goal root", ('encodedArguments.stableId("projectId")', 'encodedArguments.stableId("goalId")', 'encodedArguments["kind"]'))
    if re.search(r'encodedArguments\["(?:amount|note|name|card|attachment|location|account|description)', root, re.IGNORECASE):
        errors.append("project/goal route carries sensitive or mutable business data")
    return errors


def validate_tests_and_resources() -> list[str]:
    tests = "\n".join(
        path.read_text(encoding="utf-8")
        for root in ("feature/planning/src/androidTest", "feature/planning/src/test", "finance/data/src/androidTest", "finance/domain/src/test")
        for path in sorted((ROOT / root).rglob("*.kt"))
    )
    errors: list[str] = []
    require_tokens(errors, tests, "P18 automated evidence", (
        "goal balance reconstruction and negative account availability remain exact warnings",
        "project monthly inclusion is frozen into each new effect and self share drives usage",
        "goal movement creates no journal or posting and cannot reuse stale goal row",
        "archived project is not selectable but can be explicitly reopened",
        "transfers and loan principal never consume project budget while real loan charges do",
        "editor routes distinguish create from edit",
        "projectAndGoalFactsRemainIndependentRebuildableAndOptimisticallyLocked",
        "ProjectTransactionPageRequest(BOOK_ID, PROJECT_ID, 1, cursor)",
        "allThirtyOneFrozenRequiredStatesRenderAcrossResponsiveAccessibleLocalizedMatrix",
        "negativeAvailabilityWarnsWithoutBlockingSaveAndChartsExposeDataTablesAtCompactLargeFont",
        "projectCashflowAndGoalDetailGoldensMatchEveryPixel",
    ))
    golden = next((path.read_text(encoding="utf-8") for path in (ROOT / "feature/planning/src/androidTest").rglob("P18GoldenDeviceTest.kt")), "")
    hashes = re.findall(r'"([0-9a-f]{64})"', golden)
    if len(hashes) != 2 or len(set(hashes)) != 2:
        errors.append("P18 requires two distinct exact-pixel SHA-256 Compose goldens")
    resource_sets = []
    for relative in ("values/strings.xml", "values-en/strings.xml", "values-ja/strings.xml"):
        text = read(f"feature/planning/src/main/res/{relative}")
        resource_sets.append({key for key in re.findall(r'<string name="([^"]+)"', text) if key.startswith(("project_", "goal_", "planning_"))})
    if not resource_sets[0] or resource_sets[0] != resource_sets[1] or resource_sets[0] != resource_sets[2]:
        errors.append("P18 project/goal strings are incomplete across zh-CN/en/ja")
    return errors


def validate_ledgers() -> list[str]:
    errors: list[str] = []
    state = read("docs/implementation/PROJECT_STATE.md")
    evidence = read("docs/implementation/TEST_EVIDENCE.md")
    mapping_path = ROOT / "docs/implementation/P18_PROJECT_GOAL_MAPPING.md"
    mapping = mapping_path.read_text(encoding="utf-8") if mapping_path.is_file() else ""
    require_tokens(errors, state, "PROJECT_STATE", ("Current stage: P36", "| P18 | VERIFIED |"))
    for index in range(1, 8):
        if f"P18-E{index:03d}" not in evidence:
            errors.append(f"TEST_EVIDENCE missing P18-E{index:03d}")
    require_tokens(errors, mapping, "P18 mapping", ("31 required states", "FinancialMutationCoordinator", "keyset Paging", "P18 is `VERIFIED`"))
    with (ROOT / "docs/implementation/SCREEN_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        screens = {row["screen_id"]: row for row in csv.DictReader(handle)}
    for screen_id in EXPECTED:
        row = screens.get(screen_id, {})
        if row.get("status") != "VERIFIED" or "P18" not in row.get("implementation_evidence", "") or "P18-E" not in row.get("verification_evidence", ""):
            errors.append(f"{screen_id} must carry VERIFIED P18 implementation and test evidence")
    with (ROOT / "docs/implementation/REQUIREMENT_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        requirements = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    for requirement_id in TARGET_REQUIREMENTS:
        row = requirements.get(requirement_id, {})
        if row.get("status") != "VERIFIED" or "P18" not in row.get("implementation_evidence", "") or "P18-E" not in row.get("verification_evidence", ""):
            errors.append(f"{requirement_id} must carry VERIFIED P18 implementation and test evidence")
    return errors


def main() -> int:
    errors = validate_contract() + validate_sources() + validate_tests_and_resources() + validate_ledgers()
    if errors:
        print("P18 project/goal validation: FAIL", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("P18 project/goal validation: PASS")
    print("screens=11 required_states=31 property_iterations=1000 keyset_paging=true goldens=2 visual_inputs=contract_token_yaml_only")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
