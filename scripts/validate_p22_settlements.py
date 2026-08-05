#!/usr/bin/env python3
"""Reject P22 settlement drift, accounting bypasses, unsafe routes or false verification."""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
EXPECTED = {
    "REC-011": ("record/settlement-allocation/{activityId}", ["activityId:StableId"], {"editing", "imbalanced", "valid", "currencyMismatch"}),
    "SET-001": ("settlements", [], {"content", "empty", "requiresAdditionalSettlement"}),
    "SET-002": ("settlements/editor/{activityId?}", ["activityId:StableId?"], {"create", "edit", "validationError"}),
    "SET-003": ("settlements/{activityId}/participants", ["activityId:StableId"], {"content", "empty"}),
    "SET-004": ("settlements/{activityId}", ["activityId:StableId"], {"open", "settled", "requiresAdditionalSettlement", "empty"}),
    "SET-005": ("settlements/{activityId}/position/{participantId}", ["activityId:StableId", "participantId:StableId"], {"receivable", "payable", "zero"}),
    "SET-006": ("settlements/{activityId}/record", ["activityId:StableId"], {"selfPays", "selfReceives", "externalToExternal", "saving"}),
    "SET-007": ("settlements/{activityId}/history", ["activityId:StableId"], {"content", "empty"}),
    "SET-008": ("settlements/{activityId}/additional", ["activityId:StableId"], {"required", "resolved"}),
}
PLACEHOLDER = re.compile(r"\b(?:TODO|NotImplemented|UnsupportedOperationException)\b")
FORBIDDEN_FEATURE = re.compile(r"(?m)^import\s+(?:androidx\.room|androidx\.compose\.material3|app\.ledger\.(?:finance\.data|core\.(?:database|security)))")


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def source_map() -> dict[str, str]:
    roots = (
        "app/src/main/kotlin",
        "core/designsystem/src/main/kotlin",
        "feature/record/src/main/kotlin",
        "feature/settlement/src/main/kotlin",
        "finance/application/src/main/kotlin",
        "finance/data/src/main/kotlin",
        "finance/domain/src/main/kotlin",
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
    screens = {item["id"]: item for item in yaml.safe_load(read("docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml"))["screens"]}
    errors: list[str] = []
    for screen_id, (route, params, states) in EXPECTED.items():
        actual = screens.get(screen_id, {})
        if actual.get("route") != route:
            errors.append(f"{screen_id} route drift")
        if actual.get("params", []) != params:
            errors.append(f"{screen_id} params drift")
        if set(actual.get("requiredStates", [])) != states:
            errors.append(f"{screen_id} requiredStates drift")
    if sum(len(value[2]) for value in EXPECTED.values()) != 27:
        errors.append("P22 required-state baseline must remain exactly 27")
    return errors


def validate_sources(sources: dict[str, str] | None = None) -> list[str]:
    sources = source_map() if sources is None else sources
    errors: list[str] = []
    names = {
        "SettlementAllocationPolicy.kt",
        "SettlementApplication.kt",
        "SecureRoomSettlementApplicationPort.kt",
        "SettlementState.kt",
        "SettlementScreens.kt",
        "SettlementRootDestination.kt",
        "AccountingRuleEngine.kt",
        "RoomFinancialPlanWriter.kt",
        "RoomProjectionEngine.kt",
    }
    selected = {path: source for path, source in sources.items() if Path(path).name in names}
    missing = names - {Path(path).name for path in selected}
    if missing:
        errors.append(f"P22 production files missing: {sorted(missing)}")
    for path, source in selected.items():
        if PLACEHOLDER.search(source):
            errors.append(f"placeholder in {path}")

    feature = "\n".join(value for path, value in sources.items() if path.startswith("feature/settlement/"))
    if FORBIDDEN_FEATURE.search(feature) or re.search(r"\b(?:Dao|Entity|execSQL|JournalEntry|Posting)\b", feature):
        errors.append("settlement feature bypasses UI/application boundaries")
    if re.search(r"\b(?:MaterialTheme|Color\s*\(|SwipeToDismiss|detectHorizontalDragGestures)\b|\b\d+(?:\.\d+)?\.dp\b", feature):
        errors.append("settlement feature bypasses design tokens or introduces swipe deletion")
    require_tokens(
        errors,
        feature,
        "governed settlement UI",
        ("SettlementDestination", "SettlementPresentation", "LedgerTestTags.SETTLEMENT_HOME", "AccessibleDataTable", "AccountSelector", "selectedParticipantId"),
    )

    allocation = next((value for path, value in sources.items() if path.endswith("SettlementAllocationPolicy.kt")), "")
    require_tokens(
        errors,
        allocation,
        "exact allocation policy",
        ("EQUAL", "FIXED_AMOUNT", "PERCENTAGE", "WEIGHT", "SAME_AS_BASE", "SPECIFIED", "PARTICIPANT_ORDER", "SettlementSharePolicy.validate", "Math.addExact", "Math.subtractExact"),
    )
    split_enum = re.search(r"enum class SettlementSplitMethod\s*\{([^}]*)\}", allocation, re.DOTALL)
    if split_enum is None or set(re.findall(r"^\s*([A-Z_]+),?\s*$", split_enum.group(1), re.MULTILINE)) != {"EQUAL", "FIXED_AMOUNT", "PERCENTAGE", "WEIGHT"}:
        errors.append("settlement split mode enum is not the exact closed four-value set")
    if re.search(r"\b(?:Float|Double)\b", allocation):
        errors.append("authoritative settlement allocation contains floating-point money")

    application = next((value for path, value in sources.items() if path.endswith("SettlementApplication.kt")), "")
    require_tokens(errors, application, "typed settlement port", ("SettlementActivityView", "SettlementPositionView", "SaveSettlementActivityRequest", "RecordSettlementPaymentRequest", "RecordSettlementExpenseRequest", "SettlementApplicationPort"))
    data = next((value for path, value in sources.items() if path.endswith("SecureRoomSettlementApplicationPort.kt")), "")
    require_tokens(errors, data, "encrypted settlement adapter", ("EncryptedDatabaseFactory.openPrimary", "DefaultFinancialMutationCoordinator", "CanonicalFinancialHash.command", "settlement_payment_record", "rebuildAndAudit", "requires_additional_settlement"))
    if re.search(r"\b(?:INSERT|UPDATE|DELETE)\s+(?:INTO\s+|FROM\s+)?(?:journal_entry|posting|economic_effect|budget_effect|project_effect|settlement_effect)", data, re.IGNORECASE):
        errors.append("settlement application adapter directly writes coordinated facts")

    accounting = next((value for path, value in sources.items() if path.endswith("AccountingRuleEngine.kt")), "")
    require_tokens(errors, accounting, "settlement accounting", ("settlementExpense", "SELF_SETTLEMENT_SHARE", "PAID_FOR_GROUP", "OWED_SHARE", "SETTLEMENT_PAID", "SETTLEMENT_RECEIVED", "INV-022"))
    writer = next((value for path, value in sources.items() if path.endswith("RoomFinancialPlanWriter.kt")), "")
    require_tokens(errors, writer, "immutable payment writer", ("insertSettlementPaymentRecords", "settlementPaymentRecords", "settlement_payment_record"))
    projection = next((value for path, value in sources.items() if path.endswith("RoomProjectionEngine.kt")), "")
    require_tokens(errors, projection, "settlement projection", ("settlement_position_projection", "settled_paid_minor", "settled_received_minor", "se.kind = 2", "se.kind = 3", "current_transaction_projection"))

    root = next((value for path, value in sources.items() if path.endswith("SettlementRootDestination.kt")), "")
    require_tokens(errors, root, "safe settlement route", ('encodedArguments["activityId"]', 'encodedArguments["participantId"]', "StableId.parse"))
    if re.search(r'encodedArguments\["(?:amount|note|name|currency|account|project|payer|payee|position)', root, re.IGNORECASE):
        errors.append("settlement route carries sensitive business data")
    record = next((value for path, value in sources.items() if path.endswith("OrdinaryRecordState.kt")), "")
    require_tokens(errors, record, "REC-011 allocation bridge", ("SettlementAllocationPolicy.allocate", "settlementPayerParticipantId", "settlementIncludedParticipantIds", "SETTLEMENT_IMBALANCED"))
    return errors


def validate_schema_tests_resources() -> list[str]:
    errors: list[str] = []
    schema = "\n".join(read(path) for path in (
        "core/database/src/main/assets/ledger_schema_v1_core.sql",
        "core/database/src/main/assets/ledger_schema_v1_subledgers.sql",
        "core/database/src/main/assets/ledger_schema_v1_projections_operations.sql",
    ))
    require_tokens(errors, schema, "P22 normalized schema", ("CREATE TABLE settlement_activity (", "CREATE TABLE settlement_activity_participant (", "CREATE TABLE transaction_revision_settlement_share (", "CREATE TABLE settlement_effect (", "CREATE TABLE settlement_payment_record (", "CREATE TABLE settlement_position_projection ("))
    tests = "\n".join(
        path.read_text(encoding="utf-8")
        for root in ("feature/record/src/test", "feature/record/src/androidTest", "feature/settlement/src/androidTest", "finance/data/src/androidTest", "finance/domain/src/test")
        for path in sorted((ROOT / root).rglob("*.kt"))
    )
    require_tokens(
        errors,
        tests,
        "P22 automated evidence",
        ("all closed allocation modes conserve paid owed charges", "property allocation and settlement suggestions conserve", "ExternalOrdinaryEntryFailClosedOrCommitExactly", "allTwentyThreeSetRequiredStatesRenderAcrossWidthFontLocaleAndThemeMatrix", "detailAndExternalPaymentGoldensMatchEveryPixel", "completeAllocationGoldenMatchesEveryPixel", "PRAGMA integrity_check", "pragma_foreign_key_check"),
    )
    settlement_sets = []
    record_sets = []
    for folder in ("values", "values-en", "values-ja"):
        settlement_sets.append({name for name in re.findall(r'<string name="([^"]+)"', read(f"feature/settlement/src/main/res/{folder}/strings.xml")) if name.startswith("settlement_")})
        record_sets.append({name for name in re.findall(r'<string name="([^"]+)"', read(f"feature/record/src/main/res/{folder}/strings.xml")) if name.startswith("record_settlement_")})
    if not settlement_sets[0] or settlement_sets[0] != settlement_sets[1] or settlement_sets[0] != settlement_sets[2]:
        errors.append("P22 settlement strings incomplete across zh-CN/en/ja")
    if not record_sets[0] or record_sets[0] != record_sets[1] or record_sets[0] != record_sets[2]:
        errors.append("P22 REC-011 strings incomplete across zh-CN/en/ja")
    return errors


def validate_ledgers() -> list[str]:
    errors: list[str] = []
    state, evidence = read("docs/implementation/PROJECT_STATE.md"), read("docs/implementation/TEST_EVIDENCE.md")
    mapping_path = ROOT / "docs/implementation/P22_SETTLEMENT_MAPPING.md"
    mapping = mapping_path.read_text(encoding="utf-8") if mapping_path.is_file() else ""
    require_tokens(errors, state, "PROJECT_STATE", ("Current stage: P22", "Stage status: VERIFIED"))
    for index in range(1, 8):
        if f"P22-E{index:03d}" not in evidence:
            errors.append(f"TEST_EVIDENCE missing P22-E{index:03d}")
    require_tokens(errors, mapping, "P22 mapping", ("27 required states", "FinancialMutationCoordinator", "external-to-external", "requires additional settlement", "P22 is `VERIFIED`"))
    with (ROOT / "docs/implementation/SCREEN_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        screens = {row["screen_id"]: row for row in csv.DictReader(handle)}
    for screen_id in EXPECTED:
        row = screens.get(screen_id, {})
        if row.get("status") != "VERIFIED" or "P22" not in row.get("implementation_evidence", "") or "P22-E" not in row.get("verification_evidence", ""):
            errors.append(f"{screen_id} lacks VERIFIED P22 evidence")
    with (ROOT / "docs/implementation/REQUIREMENT_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        requirements = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    for requirement_id in ("REQ-043", "REQ-044", "REQ-045"):
        row = requirements.get(requirement_id, {})
        if row.get("status") != "VERIFIED" or "P22" not in row.get("implementation_evidence", "") or "P22-E" not in row.get("verification_evidence", ""):
            errors.append(f"{requirement_id} lacks VERIFIED P22 evidence")
    return errors


def main() -> int:
    errors = validate_contract() + validate_sources() + validate_schema_tests_resources() + validate_ledgers()
    if errors:
        print("P22 settlement validation: FAIL", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("P22 settlement validation: PASS")
    print("screens=9 required_states=27 allocation=checked_integer writes=FinancialMutationCoordinator external_only=subledger visual_inputs=contract_token_yaml_only")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
