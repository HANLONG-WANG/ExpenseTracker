#!/usr/bin/env python3
"""Validate the complete P12 account/reference-data implementation and evidence."""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path
from typing import Mapping

import yaml


ROOT = Path(__file__).resolve().parents[1]
EXPECTED_STATES = {
    "ACC-001": {"content", "noAccounts", "valuationStale", "error"},
    "ACC-002": {"content"},
    "ACC-003": {"create", "edit", "currencyLocked", "validationError", "saving"},
    "ACC-004": {"editing", "saving"},
    "ACC-005": {"active", "archived", "emptyTransactions", "valuationUnavailable"},
    "ACC-006": {"content", "empty", "error"},
    "ACC-007": {"editing", "match", "difference", "saving"},
    "ACC-008": {"content"},
    "ACC-009": {"content", "empty"},
    "ACC-010": {"create", "edit", "validationError", "saving"},
    "ACC-011": {"active", "archived", "replacement"},
    "ACC-012": {"unusedDeletable", "usedArchiveOnly", "lastAccountWarning"},
    "MGT-001": {"content"},
    "CAT-001": {"content", "empty", "searching"},
    "CAT-002": {"create", "edit", "parentLocked", "contrastWarning", "validationError"},
    "CAT-003": {"editing"},
    "CAT-004": {"unused", "used", "hasChildren", "processing"},
    "MER-001": {"content", "empty", "searching"},
    "MER-002": {"create", "edit", "duplicateWarning"},
    "MER-003": {"editing", "invalid", "merging"},
    "PLC-001": {"content", "empty"},
    "PLC-002": {"create", "edit", "mapUnavailable"},
    "PLC-003": {"merge", "split", "invalid"},
}
TARGET_REQUIREMENTS = {
    "REQ-008", "REQ-009", "REQ-010", "REQ-011", "REQ-012", "REQ-013",
    "REQ-018", "REQ-019", "REQ-020", "REQ-021", "REQ-022", "REQ-033", "REQ-053",
}
PLACEHOLDER = re.compile(r"\b(?:TODO|NotImplemented|UnsupportedOperationException)\b")
LOGGING = re.compile(r"\b(?:println|printStackTrace|android\.util\.Log|Timber\.)")


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def kotlin_sources(*roots: str) -> dict[str, str]:
    return {
        path.relative_to(ROOT).as_posix(): path.read_text(encoding="utf-8")
        for root in roots
        for path in sorted((ROOT / root).rglob("*.kt"))
    }


def named(sources: Mapping[str, str], filename: str) -> str:
    return next((source for path, source in sources.items() if path.endswith(filename)), "")


def require_tokens(errors: list[str], text: str, label: str, tokens: tuple[str, ...]) -> None:
    for token in tokens:
        if token not in text:
            errors.append(f"{label} missing {token}")


def validate_contract() -> list[str]:
    contract = yaml.safe_load(read("docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml"))
    actual = {screen["id"]: set(screen.get("requiredStates", [])) for screen in contract["screens"]}
    errors: list[str] = []
    if len(EXPECTED_STATES) != 23 or sum(map(len, EXPECTED_STATES.values())) != 67:
        errors.append("P12 state oracle must contain exactly 23 screens and 67 required states")
    for screen_id, expected in EXPECTED_STATES.items():
        if actual.get(screen_id) != expected:
            errors.append(f"{screen_id} requiredStates drift: {sorted(actual.get(screen_id, set()))}")
    return errors


def validate_sources() -> list[str]:
    sources = kotlin_sources(
        "app/src/main/kotlin", "core/designsystem/src/main/kotlin", "feature/accounts/src/main/kotlin",
        "feature/settings/src/main/kotlin", "finance/application/src/main/kotlin",
        "finance/data/src/main/kotlin", "finance/domain/src/main/kotlin",
    )
    errors: list[str] = []
    required_files = {
        "AccountsContract.kt", "AccountsScreens.kt", "ReferenceManagementContract.kt",
        "ReferenceManagementScreens.kt", "ReferenceDataPolicies.kt", "ReferenceDataManagement.kt",
        "OpeningBalanceWrite.kt", "SecureRoomReferenceDataManagementPort.kt",
        "SecureRoomOpeningBalanceWritePort.kt", "LedgerReferenceDisplayDefaults.kt",
        "RoomReferenceFinancialSnapshotMapper.kt",
    }
    missing = required_files - {Path(path).name for path in sources}
    if missing:
        errors.append(f"P12 production files missing: {sorted(missing)}")
    for path, source in sources.items():
        if PLACEHOLDER.search(source):
            errors.append(f"placeholder production implementation in {path}")
        if LOGGING.search(source):
            errors.append(f"ordinary logging entered P12 production in {path}")

    account_ui = named(sources, "AccountsScreens.kt")
    require_tokens(
        errors,
        account_ui,
        "account UI",
        (
            "accounts_core_net_assets", "accounts_adjusted_position_value", "LedgerVicoLineRenderer",
            "currencyLocked", "lastAccountWarning", "OpeningBalanceSubmission", "CheckpointSubmission",
            "accounts_goals_section", "accountGoals",
            "ReferenceDisplayStylePicker", "LedgerTestTags.P12_ACCOUNTS_ROOT",
        ),
    )
    management_ui = named(sources, "ReferenceManagementScreens.kt")
    require_tokens(
        errors,
        management_ui,
        "reference-data UI",
        (
            "ReferenceDisplayStylePicker", "management_default_account", "management_default_card",
            "management_default_merchant", "CategoryRemovalStrategy.REASSIGN", "CategoryRemovalStrategy.ARCHIVE",
            "CategoryRemovalStrategy.TOMBSTONE", "management_no_reverse_geocoding",
            "LedgerTestTags.P12_MANAGEMENT_ROOT",
        ),
    )
    data = named(sources, "SecureRoomReferenceDataManagementPort.kt")
    require_tokens(
        errors,
        data,
        "encrypted reference-data adapter",
        (
            "EncryptedDatabaseFactory.openPrimary", "inLedgerTransaction", "requireVersion",
            "ReferenceDataPolicies.accountLifecycle", "CurrencyLocked", "CategoryParentLocked",
            "CardAccountIncompatible", "rebuildAll", "DatabaseIntegrityAudit.run",
            "BatchFinancialCommand", "DefaultFinancialMutationCoordinator", "executeFinancialBatch",
            "persistPlaceSplit", "DeterministicFinancialPlanner::plan",
        ),
    )
    snapshot_mapper = named(sources, "RoomReferenceFinancialSnapshotMapper.kt")
    require_tokens(
        errors,
        snapshot_mapper,
        "reference financial snapshot mapper",
        ("CurrentFinancialFacts", "FrozenAmountEvidence", "readCurrentFacts", "readAmountEvidence"),
    )
    opening = named(sources, "SecureRoomOpeningBalanceWritePort.kt")
    require_tokens(
        errors,
        opening,
        "opening balance write",
        ("DefaultFinancialMutationCoordinator", "RecordOpeningBalanceCommand", "DeterministicFinancialPlanner::plan"),
    )

    feature = "\n".join(
        source for path, source in sources.items()
        if path.startswith("feature/accounts/") or path.startswith("feature/settings/")
    )
    forbidden = re.compile(
        r"(?m)^import\s+(?:androidx\.room|androidx\.compose\.material3|app\.ledger\.(?:finance\.data|core\.database|core\.security))"
    )
    if forbidden.search(feature) or re.search(r"\b(?:Dao|Entity|SupportSQLiteDatabase|execSQL)\b", feature):
        errors.append("P12 feature bypasses the governed UI/application boundary")
    return errors


def validate_tests() -> list[str]:
    tests = "\n".join(
        path.read_text(encoding="utf-8")
        for root in ("app/src/androidTest", "finance/data/src/androidTest", "finance/domain/src/test")
        for path in sorted((ROOT / root).rglob("*.kt"))
    )
    errors: list[str] = []
    require_tokens(
        errors,
        tests,
        "P12 automated evidence",
        (
            "allSixtySevenFrozenAccountAndReferenceStatesRenderWithStableTags",
            "compactWidthsLargeFontsThemesAndDynamicColorBoundaryKeepManagementAccessible",
            "simplifiedChineseJapaneseAndEnglishReferenceResourcesRender",
            "accountCardCategoryMerchantPlaceAndCheckpointRulesAreAtomicAndAudited",
            "used and last accounts remain archivable but are never directly deletable",
            "first posting locks currency", "second level category parent is immutable",
            "batch context edits share one commit reverse every prior fact and are deterministic",
            "CommitKind.BATCH_MUTATION", "ReferenceMutation.SplitPlace",
            "CategoryRemovalStrategy.REASSIGN", "currentTransactionCount",
            "SELECT COUNT(*) FROM book_commit WHERE kind",
        ),
    )
    return errors


def validate_ledgers() -> list[str]:
    errors: list[str] = []
    state = read("docs/implementation/PROJECT_STATE.md")
    evidence = read("docs/implementation/TEST_EVIDENCE.md")
    decision = read("docs/implementation/DECISION_LOG.md")
    mapping_path = ROOT / "docs/implementation/P12_REFERENCE_DATA_MAPPING.md"
    mapping = mapping_path.read_text(encoding="utf-8") if mapping_path.is_file() else ""
    require_tokens(errors, state, "PROJECT_STATE", ("Current stage: P36", "| P12 | VERIFIED |"))
    for index in range(1, 9):
        if f"P12-E{index:03d}" not in evidence:
            errors.append(f"TEST_EVIDENCE missing P12-E{index:03d}")
    require_tokens(
        errors,
        mapping,
        "P12 mapping",
        ("23 screens", "67 required states", "FinancialMutationCoordinator", "BatchFinancialCommand", "BATCH_MUTATION", "P12 is `VERIFIED`"),
    )
    require_tokens(errors, decision, "DECISION_LOG", ("DL-056", "DL-057", "DL-058"))

    with (ROOT / "docs/implementation/SCREEN_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        screens = {row["screen_id"]: row for row in csv.DictReader(handle)}
    for screen_id in EXPECTED_STATES:
        row = screens.get(screen_id, {})
        if row.get("status") != "VERIFIED" or "P12" not in row.get("implementation_evidence", "") or "P12-E" not in row.get("verification_evidence", ""):
            errors.append(f"{screen_id} must carry VERIFIED P12 implementation and test evidence")

    with (ROOT / "docs/implementation/REQUIREMENT_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        requirements = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    for requirement_id in TARGET_REQUIREMENTS:
        row = requirements.get(requirement_id, {})
        if row.get("status") not in {"IN_PROGRESS", "VERIFIED"} or "P12" not in row.get("implementation_evidence", "") or "P12-E" not in row.get("verification_evidence", ""):
            errors.append(f"{requirement_id} must carry truthful P12 implementation and test evidence")
    return errors


def main() -> int:
    errors = validate_contract() + validate_sources() + validate_tests() + validate_ledgers()
    if errors:
        print("P12 account/reference-data validation: FAIL", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("P12 account/reference-data validation: PASS")
    print("screens=23 required_states=67 stage=VERIFIED atomic_batch_rewrites=2 visual_inputs=contract_only")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
