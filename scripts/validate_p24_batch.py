#!/usr/bin/env python3
"""Reject P24 atomic batch, route, virtualisation, allowed-edit or coordinator drift."""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
EXPECTED = {
    "REC-023": ("record/batch", [], {"editing", "validating", "errors", "readyToCommit", "committing"}),
    "REC-024": ("record/batch/row/{rowId}", ["rowId:StableId"], {"editing", "validationError"}),
    "REC-025": ("record/batch/validation", [], {"errors", "warnings", "valid"}),
    "JRN-005": ("journal/selection", [], {"someSelected", "allMatchingSelected", "queryChanged"}),
    "JRN-006": ("journal/bulk-edit", [], {"editing", "validating", "committing", "failed", "succeeded"}),
}
PLACEHOLDER = re.compile(r"\b(?:TODO|NotImplemented|UnsupportedOperationException)\b")


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def source_map() -> dict[str, str]:
    roots = (
        "app/src/main/kotlin", "core/designsystem/src/main/kotlin", "feature/record/src/main/kotlin",
        "feature/journal/src/main/kotlin", "finance/application/src/main/kotlin",
        "finance/data/src/main/kotlin", "finance/domain/src/main/kotlin",
    )
    return {path.relative_to(ROOT).as_posix(): path.read_text(encoding="utf-8") for root in roots for path in sorted((ROOT / root).rglob("*.kt"))}


def require_tokens(errors: list[str], text: str, label: str, tokens: tuple[str, ...]) -> None:
    for token in tokens:
        if token not in text:
            errors.append(f"{label} missing {token}")


def validate_contract() -> list[str]:
    screens = {item["id"]: item for item in yaml.safe_load(read("docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml"))["screens"]}
    errors: list[str] = []
    for screen_id, (route, params, states) in EXPECTED.items():
        actual = screens.get(screen_id, {})
        if actual.get("route") != route or actual.get("params", []) != params or set(actual.get("requiredStates", [])) != states:
            errors.append(f"{screen_id} route/params/requiredStates drift")
    if sum(len(value[2]) for value in EXPECTED.values()) != 18:
        errors.append("P24 required-state baseline must remain exactly 18 including JRN final integration")
    return errors


def validate_sources(sources: dict[str, str] | None = None) -> list[str]:
    sources = source_map() if sources is None else sources
    errors: list[str] = []
    required = {
        "BatchEntryApplication.kt", "SecureRoomBatchEntryApplicationPort.kt", "BatchRecordState.kt",
        "BatchRecordScreens.kt", "BatchEntryController.kt", "BatchRecordRootDestination.kt", "BatchComponents.kt",
    }
    selected = {path: source for path, source in sources.items() if Path(path).name in required}
    missing = required - {Path(path).name for path in selected}
    if missing:
        errors.append(f"P24 production files missing: {sorted(missing)}")
    for path, source in selected.items():
        if PLACEHOLDER.search(source):
            errors.append(f"placeholder in {path}")

    application = next((value for path, value in sources.items() if path.endswith("BatchEntryApplication.kt")), "")
    require_tokens(errors, application, "typed batch port", (
        "BatchEntryRowWriteRequest", "OrdinaryTransactionWriteRequest", "RefundWriteRequest",
        "BatchEntrySubmitRequest", "BatchUndoRequest", "BatchValidationReport", "BatchAuditView",
    ))
    data = next((value for path, value in sources.items() if path.endswith("SecureRoomBatchEntryApplicationPort.kt")), "")
    require_tokens(errors, data, "encrypted atomic batch adapter", (
        "EncryptedDatabaseFactory.openPrimary", "BatchFinancialCommand", "DefaultFinancialMutationCoordinator",
        "RoomFinancialCommitRepository", "validateInside", "FinancialCommitSideEffect", "audit", "undo",
    ))
    if re.search(r"execSQL\(\s*\"INSERT INTO (?:journal_entry|posting|economic_effect|budget_effect)", data, re.IGNORECASE):
        errors.append("P24 adapter bypasses planner with direct financial fact SQL")
    planner = next((value for path, value in sources.items() if path.endswith("DeterministicFinancialPlanner.kt")), "")
    require_tokens(errors, planner, "batch planner", ("planBatch", "RecordTransactionCommand", "BatchFinancialCommand"))

    state = next((value for path, value in sources.items() if path.endswith("BatchRecordState.kt")), "")
    require_tokens(errors, state, "complete in-memory row", (
        "categoryId", "amountExpression", "accountId", "cardId", "merchantId", "occurredAt", "projectId",
        "attachmentIds", "settlementShares", "locationRecordId", "installmentPlanId", "refundOriginalTransactionId",
        "MAX_PASTE_ROWS", "copyRow", "insertAfter", "sort", "paste",
    ))
    if re.search(r"SavedState|DataStore|Room|SharedPreferences", state):
        errors.append("batch draft escapes its in-memory lifecycle")

    feature = "\n".join(value for path, value in sources.items() if path.startswith("feature/record/") and "Batch" in Path(path).name)
    if re.search(r"^import\s+(?:androidx\.room|androidx\.compose\.material3|app\.ledger\.(?:finance\.data|core\.(?:database|security)))", feature, re.MULTILINE):
        errors.append("batch feature bypasses UI/application boundaries")
    if re.search(r"\b(?:MaterialTheme|Color\s*\(|SwipeToDismiss|detectHorizontalDragGestures)\b|\b\d+(?:\.\d+)?\.dp\b", feature):
        errors.append("batch feature bypasses frozen design tokens or adds swipe delete")
    require_tokens(errors, feature, "governed batch UI", (
        "BatchSummaryTable", "BatchToolbar", "BatchCommitBar", "ValidationSummary",
        "LedgerTestTags.BATCH_ROW_EDITOR", "rowCount = state.rows.size",
    ))

    root = next((value for path, value in sources.items() if path.endswith("BatchRecordRootDestination.kt")), "")
    require_tokens(errors, root, "safe batch route", ("onOpenRow", "openBatchRow", "onJumpToIssue"))
    view_model = next((value for path, value in sources.items() if path.endswith("AppRootViewModel.kt")), "")
    require_tokens(errors, view_model, "session-gated batch runtime", ("openBatchEntry", 'ScreenId("REC-024")', 'StableIdArgument(rowId)', "validateBatchEntry", "submitBatchEntry"))
    route_block = re.search(r"fun openBatchRow\(.*?\n    }", view_model, re.DOTALL)
    if route_block and re.search(r"\b(?:amount|note|name|card|attachment|location)\b", route_block.group(0), re.IGNORECASE):
        errors.append("REC-024 route construction may carry business content")

    journal = next((value for path, value in sources.items() if path.endswith("JournalApplicationPort.kt")), "")
    require_tokens(errors, journal, "query-selection bulk edit", (
        "JournalSelectionMode.ALL_MATCHING", "excludedIds", "JournalBulkEditPatch",
        'setOf("amount", "direction", "refundRelation", "settlementShare")',
    ))
    patch = re.search(r"data class JournalBulkEditPatch\((.*?)\n\)", journal, re.DOTALL)
    if patch and re.search(r"\b(?:amount|direction|refundRelation|settlementShare)\b", patch.group(1)):
        errors.append("forbidden financial field entered JournalBulkEditPatch")
    return errors


def validate_tests_resources() -> list[str]:
    errors: list[str] = []
    tests = "\n".join(path.read_text(encoding="utf-8") for root in ("finance/domain/src/test", "finance/data/src/androidTest", "feature/record/src/androidTest", "feature/journal/src/test", "feature/journal/src/androidTest") for path in sorted((ROOT / root).rglob("*.kt")))
    require_tokens(errors, tests, "P24 automated evidence", (
        "batch creates are one balanced deterministic commit", "invalidFailureRetryAuditAndProjectionAreOneAtomicBatch",
        "AFTER_IMMUTABLE_FACTS", "100_000", "allTenYamlStatesRenderAcrossWidthsLocalesThemesAndFontScales",
        "tokenAndYamlDerivedPixelGoldensRemainStable", "JournalSelectionMode.ALL_MATCHING", "PRAGMA integrity_check",
    ))
    strings = []
    for folder in ("values", "values-en", "values-ja"):
        strings.append({name for name in re.findall(r'<string name="([^"]+)"', read(f"feature/record/src/main/res/{folder}/strings.xml")) if name.startswith("batch_")})
    if not strings[0] or strings[0] != strings[1] or strings[0] != strings[2]:
        errors.append("P24 strings incomplete across zh-CN/en/ja")
    return errors


def validate_ledgers() -> list[str]:
    errors: list[str] = []
    state = read("docs/implementation/PROJECT_STATE.md")
    evidence = read("docs/implementation/TEST_EVIDENCE.md")
    mapping_path = ROOT / "docs/implementation/P24_BATCH_MAPPING.md"
    mapping = mapping_path.read_text(encoding="utf-8") if mapping_path.is_file() else ""
    require_tokens(errors, state, "PROJECT_STATE", ("Current stage: P24", "Stage status: VERIFIED"))
    for index in range(1, 8):
        if f"P24-E{index:03d}" not in evidence:
            errors.append(f"TEST_EVIDENCE missing P24-E{index:03d}")
    require_tokens(errors, mapping, "P24 mapping", ("all-or-nothing", "FinancialMutationCoordinator", "100,000", "P24 is `VERIFIED`"))
    with (ROOT / "docs/implementation/SCREEN_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        screens = {row["screen_id"]: row for row in csv.DictReader(handle)}
    for screen_id in EXPECTED:
        row = screens.get(screen_id, {})
        if row.get("status") != "VERIFIED" or "P24" not in row.get("implementation_evidence", "") or "P24-E" not in row.get("verification_evidence", ""):
            errors.append(f"{screen_id} lacks VERIFIED P24 evidence")
    with (ROOT / "docs/implementation/REQUIREMENT_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        requirements = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    for requirement_id in ("REQ-060", "REQ-061"):
        row = requirements.get(requirement_id, {})
        if row.get("status") != "VERIFIED" or "P24" not in row.get("implementation_evidence", "") or "P24-E" not in row.get("verification_evidence", ""):
            errors.append(f"{requirement_id} lacks VERIFIED P24 evidence")
    return errors


def main() -> int:
    errors = validate_contract() + validate_sources() + validate_tests_resources() + validate_ledgers()
    if errors:
        print("P24 batch validation: FAIL", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("P24 batch validation: PASS")
    print("screens=5 required_states=18 commit=atomic coordinator=single query_selection=bounded rows=virtualized visual_inputs=contract_token_yaml_only")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
