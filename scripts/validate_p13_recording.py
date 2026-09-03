#!/usr/bin/env python3
"""Reject drift or a false VERIFIED promotion of the frozen P13 scope."""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
EXPECTED_STATES = {
    "REC-001": {"content", "noCategories", "searching", "loading"},
    "REC-002": {"typing", "results", "empty"},
    "REC-003": {"loading", "editing", "validating", "saving", "saveError", "revisionConflict"},
    "REC-004": {"content", "empty", "searching"},
    "REC-005": {"content", "empty"},
    "REC-006": {"content", "empty"},
    "REC-007": {"recent", "results", "empty", "create"},
    "REC-008": {"active", "archivedWarning", "empty"},
    "REC-009": {"locating", "located", "permissionDenied", "timeout", "manual", "mapUnavailable"},
    "REC-010": {"content", "empty", "importing", "failed"},
    "REC-011": {"editing", "imbalanced", "valid", "currencyMismatch"},
    "REC-012": {"content"},
}
TARGET_REQUIREMENTS = {*(f"REQ-{index:03d}" for index in range(16, 31)), "REQ-052", "REQ-054", "REQ-058"}
PLACEHOLDER = re.compile(r"\b(?:TODO|NotImplemented|UnsupportedOperationException)\b")
FORBIDDEN_FEATURE_IMPORT = re.compile(
    r"(?m)^import\s+(?:androidx\.room|androidx\.compose\.material3|app\.ledger\.(?:finance\.data|core\.database|core\.security))"
)


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def require_tokens(errors: list[str], text: str, label: str, tokens: tuple[str, ...]) -> None:
    for token in tokens:
        if token not in text:
            errors.append(f"{label} missing {token}")


def validate_contract() -> list[str]:
    contract = yaml.safe_load(read("docs/初始开发文件存档/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml"))
    actual = {screen["id"]: set(screen.get("requiredStates", [])) for screen in contract["screens"]}
    errors: list[str] = []
    if len(EXPECTED_STATES) != 12 or sum(map(len, EXPECTED_STATES.values())) != 42:
        errors.append("P13 state oracle must contain exactly 12 screens and 42 states")
    for screen_id, expected in EXPECTED_STATES.items():
        if actual.get(screen_id) != expected:
            errors.append(f"{screen_id} requiredStates drift: {sorted(actual.get(screen_id, set()))}")
    return errors


def validate_sources() -> list[str]:
    roots = (
        "app/src/main/kotlin", "core/designsystem/src/main/kotlin", "core/files/src/main/kotlin",
        "feature/record/src/main/kotlin", "finance/application/src/main/kotlin", "finance/data/src/main/kotlin",
    )
    sources = {
        path.relative_to(ROOT).as_posix(): path.read_text(encoding="utf-8")
        for root in roots for path in sorted((ROOT / root).rglob("*.kt"))
    }
    errors: list[str] = []
    required = {
        "OrdinaryTransactionEntry.kt", "SecureRoomOrdinaryTransactionEntryPort.kt",
        "OrdinaryRecordState.kt", "OrdinaryRecordScreens.kt", "SecureBookAttachmentObjectPort.kt",
    }
    missing = required - {Path(path).name for path in sources}
    if missing:
        errors.append(f"P13 production files missing: {sorted(missing)}")
    for path, source in sources.items():
        if PLACEHOLDER.search(source):
            errors.append(f"placeholder production implementation in {path}")

    feature = "\n".join(source for path, source in sources.items() if path.startswith("feature/record/"))
    if FORBIDDEN_FEATURE_IMPORT.search(feature) or re.search(r"\b(?:Dao|Entity|execSQL|JournalEntry)\b", feature):
        errors.append("record feature bypasses governed UI/application boundaries")
    require_tokens(
        errors, feature, "record feature",
        (
            "RecordTab.EXPENSE", "RecordTab.INCOME", "RecordTab.OTHER", "CategoryGrid",
            "MoneyExpressionField", "DateTimeZoneField", "LedgerDateTimePickerFlow",
            "RecordField.SETTLEMENT", "RecordField.LOCATION", "RecordField.ATTACHMENTS",
            "RecordEditorPresentation.REVISION_CONFLICT", "attachmentImporting",
            "hideValueFromSemantics = true", "LedgerTestTags.RECORD_EDITOR",
        ),
    )
    data = next((source for path, source in sources.items() if path.endswith("SecureRoomOrdinaryTransactionEntryPort.kt")), "")
    require_tokens(
        errors, data, "encrypted ordinary-write adapter",
        (
            "DefaultFinancialMutationCoordinator", "FinancialCommitSideEffect", "expectedRevisionId",
            "CanonicalFinancialHash", "request.newLocation", "EncryptedDatabaseFactory.openPrimary",
        ),
    )
    app = next((source for path, source in sources.items() if path.endswith("AppRootViewModel.kt")), "")
    require_tokens(
        errors, app, "application integration",
        (
            "ForegroundLocationSaveSession", "locationForSave", "BookAttachmentObjectPort",
            "OrdinaryTransactionWriteRequest", "mutableOrdinaryRecordPending", "PendingRecordExit",
        ),
    )
    if re.search(r"(?m)^import\s+app\.ledger\.finance\.domain\.(?:Journal|Posting)", app):
        errors.append("AppRootViewModel must not construct Journal or Posting")

    route_source = read("finance/application/src/main/kotlin/app/ledger/finance/application/OrdinaryTransactionEntry.kt")
    if re.search(r"Route|SavedState", route_source) and "must never enter navigation" not in route_source:
        errors.append("ordinary write values need an explicit navigation exclusion")
    return errors


def validate_tests_and_resources() -> list[str]:
    tests = "\n".join(
        path.read_text(encoding="utf-8")
        for root in ("feature/record/src/test", "feature/record/src/androidTest", "finance/data/src/androidTest")
        for path in sorted((ROOT / root).rglob("*.kt"))
    )
    errors: list[str] = []
    require_tokens(
        errors, tests, "P13 automated evidence",
        (
            "categoryDefaultBeatsRecentAndAccountChangeClearsIncompatibleCard",
            "invalidSaveIsClickableValidationThenExpressionBecomesExactIntegerMinor",
            "settlementIsCollapsedAndBalancedAllocationUsesOnePayer", "everyEntryOriginHasTheFrozenReturnBehavior",
            "rec001ThroughRec012RenderAtRequiredWidthsLocalesThemesAndFontScales",
            "allFortyTwoFrozenRequiredStatesRenderInsideTheirRecDestination", "assertEquals(42, cases.size)",
            "editorValidationConflictUnsavedAndFixedCoreFieldsExposeStableAccessibleSemantics",
            "categoryHomeEditorValidationAndSettlementGoldensMatchEveryPixel",
            "createRetryEditConflictAndLocationAreAtomicThroughCoordinator",
            "rec009RendersAllSixStatesAtCompactWidthAndTwoHundredPercentFont",
            "rec010RendersContentEmptyImportingAndFailedStates",
        ),
    )
    golden_dir = ROOT / "feature/record/src/androidTest/assets/goldens"
    goldens = sorted(golden_dir.glob("p13_*.png")) if golden_dir.is_dir() else []
    if len(goldens) != 4 or any(path.stat().st_size < 1_000 for path in goldens):
        errors.append("exactly four non-empty P13 Compose/token goldens are required")

    resource_sets = []
    for relative in ("values/strings.xml", "values-en/strings.xml", "values-ja/strings.xml"):
        text = read(f"feature/record/src/main/res/{relative}")
        resource_sets.append(set(re.findall(r'<string name="([^"]+)"', text)))
    p13_keys = {key for key in resource_sets[0] if key.startswith("record_")}
    if any(not p13_keys.issubset(keys) for keys in resource_sets[1:]):
        errors.append("P13 string resources are not complete across zh/en/ja")
    return errors


def validate_ledgers() -> list[str]:
    errors: list[str] = []
    state = read("docs/初始开发文件存档/implementation/PROJECT_STATE.md")
    evidence = read("docs/初始开发文件存档/implementation/TEST_EVIDENCE.md")
    mapping = read("docs/初始开发文件存档/implementation/P13_ORDINARY_RECORDING_MAPPING.md") if (ROOT / "docs/初始开发文件存档/implementation/P13_ORDINARY_RECORDING_MAPPING.md").is_file() else ""
    require_tokens(errors, state, "PROJECT_STATE", ("Current stage: P36", "| P13 | VERIFIED |"))
    for index in range(1, 9):
        if f"P13-E{index:03d}" not in evidence:
            errors.append(f"TEST_EVIDENCE missing P13-E{index:03d}")
    require_tokens(errors, mapping, "P13 mapping", ("12 screens", "42 required states", "FinancialMutationCoordinator", "P13 is `VERIFIED`"))

    with (ROOT / "docs/初始开发文件存档/implementation/SCREEN_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        screens = {row["screen_id"]: row for row in csv.DictReader(handle)}
    for screen_id in EXPECTED_STATES:
        row = screens.get(screen_id, {})
        if row.get("status") != "VERIFIED" or "P13" not in row.get("implementation_evidence", "") or "P13-E" not in row.get("verification_evidence", ""):
            errors.append(f"{screen_id} must carry VERIFIED P13 implementation and test evidence")

    with (ROOT / "docs/初始开发文件存档/implementation/REQUIREMENT_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        requirements = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    for requirement_id in TARGET_REQUIREMENTS:
        row = requirements.get(requirement_id, {})
        if row.get("status") not in {"IN_PROGRESS", "VERIFIED"} or "P13" not in row.get("implementation_evidence", "") or "P13-E" not in row.get("verification_evidence", ""):
            errors.append(f"{requirement_id} must carry truthful P13 implementation and test evidence")
    return errors


def main() -> int:
    errors = validate_contract() + validate_sources() + validate_tests_and_resources() + validate_ledgers()
    if errors:
        print("P13 ordinary-recording validation: FAIL", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("P13 ordinary-recording validation: PASS")
    print("screens=12 required_states=42 stage=VERIFIED visual_inputs=contract_token_yaml_only")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
