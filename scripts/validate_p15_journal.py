#!/usr/bin/env python3
"""Reject drift, unsafe shortcuts, or a false VERIFIED promotion of P15."""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
EXPECTED_STATES = {
    "JRN-001": {"loading", "content", "empty", "error", "refreshing"},
    "JRN-002": {"idle", "typing", "results", "empty", "error"},
    "JRN-003": {"editing", "invalid", "applying"},
    "JRN-004": {"content", "empty"},
    "JRN-005": {"someSelected", "allMatchingSelected", "queryChanged"},
    "JRN-006": {"editing", "validating", "committing", "failed", "succeeded"},
    "JRN-007": {"loading", "active", "trashed", "dependencyWarning", "notFound"},
    "JRN-008": {"content", "singleRevision"},
    "JRN-009": {"content", "loading"},
    "JRN-010": {"content", "noDependencies", "blocked"},
    "JRN-011": {"content", "empty", "selection"},
    "JRN-012": {"eligible", "notEligible", "verifying", "purging"},
}
TARGET_REQUIREMENTS = {"REQ-031", "REQ-032", "REQ-061", "REQ-062", "REQ-063", "REQ-064", "REQ-065", "REQ-066", "REQ-088"}
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
        "app/src/main/kotlin",
        "feature/journal/src/main/kotlin",
        "finance/application/src/main/kotlin",
        "finance/data/src/main/kotlin",
        "finance/domain/src/main/kotlin",
    )
    return {
        path.relative_to(ROOT).as_posix(): path.read_text(encoding="utf-8")
        for root in roots
        for path in sorted((ROOT / root).rglob("*.kt"))
    }


def validate_contract() -> list[str]:
    contract = yaml.safe_load(read("docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml"))
    actual = {screen["id"]: set(screen.get("requiredStates", [])) for screen in contract["screens"]}
    errors: list[str] = []
    if len(EXPECTED_STATES) != 12 or sum(map(len, EXPECTED_STATES.values())) != 42:
        errors.append("P15 oracle must contain exactly 12 JRN screens and 42 states")
    for screen_id, expected in EXPECTED_STATES.items():
        if actual.get(screen_id) != expected:
            errors.append(f"{screen_id} requiredStates drift: {sorted(actual.get(screen_id, set()))}")
    return errors


def validate_sources(sources: dict[str, str] | None = None) -> list[str]:
    sources = load_sources() if sources is None else sources
    errors: list[str] = []
    required = {
        "JournalApplicationPort.kt",
        "SecureRoomJournalApplicationPort.kt",
        "EncryptedJournalFilterStore.kt",
        "RoomTransactionQueryService.kt",
        "JournalState.kt",
        "JournalDestination.kt",
    }
    missing = required - {Path(path).name for path in sources}
    if missing:
        errors.append(f"P15 production files missing: {sorted(missing)}")
    for path, source in sources.items():
        if PLACEHOLDER.search(source):
            errors.append(f"placeholder production implementation in {path}")

    feature = "\n".join(source for path, source in sources.items() if path.startswith("feature/journal/"))
    if FORBIDDEN_FEATURE_IMPORT.search(feature) or re.search(r"\b(?:Dao|Entity|execSQL|JournalEntry|Posting)\b", feature):
        errors.append("journal feature bypasses governed UI/application boundaries")
    require_tokens(
        errors,
        feature,
        "P15 governed UI",
        (
            'screenId == "JRN-001"',
            'screenId == "JRN-012"',
            "LazyPagingItems<JournalTransactionView>",
            "stickyHeader",
            "JournalSelectionMode.ALL_MATCHING",
            "p15_journal_bulk_forbidden",
            "p15_journal_edit_transaction",
            "p15_journal_create_refund",
            "p15_journal_manage_attachments",
            "onOpenAttachment",
            "toOptionalMinor(rangeCurrency)",
            "JOURNAL_LOCAL_TIME",
            "state.operation.label()",
            "onResolveDependency",
            "HighRiskConfirmation",
        ),
    )
    destination = next((source for path, source in sources.items() if path.endswith("JournalDestination.kt")), "")
    require_tokens(errors, destination, "journal attachment preview entry", ("actions.onOpenAttachment(attachmentId)",))
    if re.search(r"SwipeToDismiss|swipeable|detectHorizontalDragGestures", feature):
        errors.append("journal feature contains forbidden swipe deletion")

    query = next((source for path, source in sources.items() if path.endswith("RoomTransactionQueryService.kt")), "")
    require_tokens(
        errors,
        query,
        "P15 keyset/FTS query",
        (
            "ctp.occurred_at < ?",
            "ctp.transaction_id <",
            "transaction_fts MATCH ?",
            "EXISTS (SELECT 1 FROM budget_effect",
            "haversineMeters",
            "GEO_CANDIDATE_LIMIT = 2_000",
            "ORDER BY ctp.occurred_at DESC, ctp.transaction_id DESC LIMIT ?",
        ),
    )
    executable_query = re.sub(r"(?m)//.*$|/\*.*?\*/", "", query, flags=re.DOTALL)
    if re.search(r"\bOFFSET\b", executable_query, re.IGNORECASE):
        errors.append("P15 query implementation contains deep OFFSET")

    application = next((source for path, source in sources.items() if path.endswith("JournalApplicationPort.kt")), "")
    require_tokens(
        errors,
        application,
        "typed journal application boundary",
        (
            "JournalSelectionSpec",
            "queryFingerprint",
            "JournalBulkEditableField",
            'setOf("amount", "direction", "refundRelation", "settlementShare")',
            "JournalPurgeAssessment",
            "JournalSavedFilterCommand",
            "DependencyResolution",
        ),
    )
    data = next((source for path, source in sources.items() if path.endswith("SecureRoomJournalApplicationPort.kt")), "")
    require_tokens(
        errors,
        data,
        "encrypted P15 adapter",
        (
            "DefaultFinancialMutationCoordinator",
            "BatchFinancialCommand",
            "RevisionAction.BULK_EDIT",
            "RestoreHistoricalRevisionCommand",
            "EncryptedDatabaseFactory.openPrimary",
            "PHYSICAL_PURGE_REQUIRES_MAINTENANCE",
            "RoomTransactionQueryService(database).page",
            '"SELECT a.uid,a.display_name FROM transaction_revision_attachment',
        ),
    )
    if re.search(r"DELETE\s+FROM\s+(?:business_transaction|transaction_revision|journal_entry|posting)", data, re.IGNORECASE):
        errors.append("P15 must not perform the P31 physical purge")
    root = next((s for p, s in sources.items() if p.endswith("AppRootViewModel.kt")), "")
    journal_mutation = root[root.find("private fun executeJournalMutation"):root.find("private fun refreshJournalPaging")]
    require_tokens(errors, journal_mutation, "journal mutation refreshes financial surfaces", ("loadReferenceDataAfterMutation(bookId)",))
    require_tokens(errors, root, "attachment lifecycle integration", (
        "fun openAttachment", "SecureBookAttachmentSession", "attachmentExternalOpenRequests",
        'ScreenId("ATT-001")', 'ScreenId("ATT-002")', 'ScreenId("ATT-003")',
    ))
    require_tokens(errors, root, "single transfer revision editor", (
        "kind == TransactionKind.TRANSFER",
        'ScreenId("REC-013")',
        'mapOf("transactionId" to StableIdArgument(transactionId))',
        "expectedRevisionId = validated.expectedRevisionId",
    ))
    specialized = next((s for p, s in sources.items() if p.endswith("SecureRoomSpecializedTransactionEntryPort.kt")), "")
    require_tokens(errors, specialized, "specialized edit financial mutation", (
        "EditTransactionCommand(",
        "ReferenceDataViolation.StaleRevision",
        "previous?.payload as? TransferPayload",
    ))
    if re.search(r"(?m)^import\s+app\.ledger\.finance\.domain\.(?:JournalEntry|Posting)", root):
        errors.append("ViewModel must not construct accounting facts")
    return errors


def validate_tests_and_resources() -> list[str]:
    tests = "\n".join(
        path.read_text(encoding="utf-8")
        for root in ("feature/journal/src/test", "feature/journal/src/androidTest", "finance/data/src/test", "finance/data/src/androidTest", "finance/domain/src/test")
        for path in sorted((ROOT / root).rglob("*.kt"))
    )
    errors: list[str] = []
    require_tokens(
        errors,
        tests,
        "P15 automated evidence",
        (
            "allFortyTwoRequiredStatesRenderAcrossWidthsFontsLocalesAndThemes",
            "journalListAndDetailGoldensMatchEveryPixel",
            "halfMillionRowsUseBoundedKeysetPagingAndFtsWithoutDeepOffset",
            "assertEquals(HALF_MILLION.toLong()",
            "all matching selection for five hundred thousand rows stores only exceptions",
            "bulkHistoryRestoreTrashAndPurgeAssessmentUseImmutableAtomicPaths",
            "savedFiltersRoundTripUnderAeadWithoutPlaintextAndSupportEveryMutation",
            "RevisionAction.BULK_EDIT",
            "RevisionAction.RESTORE",
        ),
    )
    goldens = sorted((ROOT / "feature/journal/src/androidTest/assets/goldens").glob("p15_*.png"))
    if len(goldens) != 2 or any(path.stat().st_size < 1_000 for path in goldens):
        errors.append("exactly two non-empty P15 Compose/token goldens are required")
    resource_sets = []
    user_copy = []
    for relative in ("values/strings.xml", "values-ja/strings.xml", "values-zh-rCN/strings.xml"):
        text = read(f"feature/journal/src/main/res/{relative}")
        resource_sets.append({key for key in re.findall(r'<string name="([^"]+)"', text) if key.startswith("p15_")})
        user_copy.extend(re.findall(r'<string name="p15_[^"]+"[^>]*>(.*?)</string>', text, re.DOTALL))
    if resource_sets[0] != resource_sets[1] or resource_sets[0] != resource_sets[2]:
        errors.append("journal P15 strings are incomplete across en/ja/zh-CN")
    technical_term = re.search(r"(?:\bISO(?:-?8601)?\b|\bminor[ -]units?\b|最小货币单位|最小単位)", " ".join(user_copy), re.IGNORECASE)
    if technical_term:
        errors.append(f"journal UI exposes implementation terminology: {technical_term.group(0)}")
    return errors


def validate_ledgers() -> list[str]:
    errors: list[str] = []
    state = read("docs/implementation/PROJECT_STATE.md")
    evidence = read("docs/implementation/TEST_EVIDENCE.md")
    mapping_path = ROOT / "docs/implementation/P15_JOURNAL_MAPPING.md"
    mapping = mapping_path.read_text(encoding="utf-8") if mapping_path.is_file() else ""
    require_tokens(errors, state, "PROJECT_STATE", ("Current stage: P15", "Stage status: VERIFIED"))
    for index in range(1, 9):
        if f"P15-E{index:03d}" not in evidence:
            errors.append(f"TEST_EVIDENCE missing P15-E{index:03d}")
    require_tokens(errors, mapping, "P15 mapping", ("42 required states", "500,000", "FinancialMutationCoordinator", "P15 is `VERIFIED`"))

    with (ROOT / "docs/implementation/SCREEN_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        screens = {row["screen_id"]: row for row in csv.DictReader(handle)}
    for screen_id in EXPECTED_STATES:
        row = screens.get(screen_id, {})
        if row.get("status") != "VERIFIED" or "P15" not in row.get("implementation_evidence", "") or "P15-E" not in row.get("verification_evidence", ""):
            errors.append(f"{screen_id} must carry VERIFIED P15 implementation and test evidence")

    with (ROOT / "docs/implementation/REQUIREMENT_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        requirements = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    for requirement_id in TARGET_REQUIREMENTS:
        row = requirements.get(requirement_id, {})
        if row.get("status") not in {"IN_PROGRESS", "VERIFIED"} or "P15" not in row.get("implementation_evidence", "") or "P15-E" not in row.get("verification_evidence", ""):
            errors.append(f"{requirement_id} must carry truthful P15 implementation and test evidence")
    return errors


def main() -> int:
    errors = validate_contract() + validate_sources() + validate_tests_and_resources() + validate_ledgers()
    if errors:
        print("P15 journal validation: FAIL", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("P15 journal validation: PASS")
    print("screens=12 required_states=42 goldens=2 rows=500000 visual_inputs=contract_token_yaml_only")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
