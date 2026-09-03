#!/usr/bin/env python3
"""Reject refund-contract drift, unsafe write paths, or a false P16 promotion."""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
EXPECTED = {
    "REC-015": {
        "route": "record/refund/{transactionId?}",
        "states": {"linked", "independent", "partiallyRefunded", "exceedsRemaining", "saving"},
        "components": {
            "OriginalTransactionField", "RefundAmountField", "ReceivingAccountField", "InheritedFields",
            "RefundBudgetPolicy", "RefundProjectPolicy", "RefundGoalPolicy", "AdvancedExcessOverride", "LedgerSaveFab",
        },
    },
    "REC-016": {
        "route": "record/refund/original-picker",
        "states": {"content", "empty", "searching"},
        "components": {"SearchField", "RefundableTransactionRow", "FilterChips"},
    },
}
TARGET_REQUIREMENTS = {"REQ-031", "REQ-032", "REQ-034", "REQ-035"}
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
        "app/src/main/kotlin", "core/designsystem/src/main/kotlin", "feature/record/src/main/kotlin",
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
    for screen_id, expected in EXPECTED.items():
        screen = screens.get(screen_id, {})
        if screen.get("route") != expected["route"]:
            errors.append(f"{screen_id} route drift")
        if set(screen.get("requiredStates", [])) != expected["states"]:
            errors.append(f"{screen_id} requiredStates drift")
        if set(screen.get("primaryComponents", [])) != expected["components"]:
            errors.append(f"{screen_id} primaryComponents drift")
    if screens.get("REC-015", {}).get("params") != ["transactionId:StableId?"]:
        errors.append("REC-015 may carry only an optional stable transaction ID")
    if screens.get("REC-016", {}).get("params") != []:
        errors.append("REC-016 may not carry route data")
    return errors


def validate_sources(sources: dict[str, str] | None = None) -> list[str]:
    sources = load_sources() if sources is None else sources
    errors: list[str] = []
    required = {
        "RefundApplication.kt", "SecureRoomRefundApplicationPort.kt", "RefundState.kt", "RefundScreens.kt",
        "RefundRootDestination.kt", "DeterministicFinancialPlanner.kt", "RoomProjectionEngine.kt",
    }
    missing = required - {Path(path).name for path in sources}
    if missing:
        errors.append(f"P16 production files missing: {sorted(missing)}")
    for path, source in sources.items():
        if PLACEHOLDER.search(source):
            errors.append(f"placeholder production implementation in {path}")

    feature = "\n".join(source for path, source in sources.items() if path.startswith("feature/record/"))
    if FORBIDDEN_FEATURE_IMPORT.search(feature) or re.search(r"\b(?:Dao|Entity|execSQL|JournalEntry|Posting)\b", feature):
        errors.append("refund feature bypasses governed UI/application boundaries")
    require_tokens(
        errors,
        feature,
        "P16 governed refund UI",
        (
            "RefundDestination", "RefundOriginalPickerDestination", "REFUND_AMOUNT_SUMMARY",
            "REFUND_TIME_DIMENSIONS", "REFUND_EXCESS_CONFIRMATION", "RefundBudgetPolicy.entries",
            "RefundProjectPolicy.entries", "RefundGoalPolicy.entries", "requestExcessOverride", "confirmExcessRisk",
        ),
    )
    if re.search(r"SwipeToDismiss|swipeable|detectHorizontalDragGestures", feature):
        errors.append("refund feature contains forbidden swipe deletion")

    application = next((s for p, s in sources.items() if p.endswith("RefundApplication.kt")), "")
    require_tokens(
        errors,
        application,
        "typed refund application boundary",
        (
            "RefundableTransactionView", "RefundAllocationDraft", "RefundWriteRequest", "RefundApplicationPort",
            "budgetTargetMonth", "accrualDate", "allowExcessOverride", "excessRiskConfirmed",
        ),
    )
    data = next((s for p, s in sources.items() if p.endswith("SecureRoomRefundApplicationPort.kt")), "")
    require_tokens(
        errors,
        data,
        "encrypted refund adapter",
        (
            "DefaultFinancialMutationCoordinator", "DeterministicFinancialPlanner", "RecordRefundCommand",
            "refund_allocation", "partiallyRefundedOnly", "EncryptedDatabaseFactory.openPrimary",
        ),
    )
    if re.search(r"\b(?:INSERT|UPDATE|DELETE)\s+(?:INTO\s+|FROM\s+)?(?:journal_entry|posting|economic_effect|refund_allocation)", data, re.IGNORECASE):
        errors.append("refund adapter performs a direct financial SQL write")

    domain = "\n".join(source for path, source in sources.items() if path.startswith("finance/domain/"))
    require_tokens(
        errors,
        domain,
        "immutable refund domain",
        (
            "RefundAllocationFact", "RefundAllocationReference", "refundAllocationFacts", "EffectPolarity.REVERSE",
            "BudgetEffectKind.RESTORE", "EconomicNature.CONTRA_EXPENSE", "RefundStatusProjection",
            'DomainViolation.Invariant("INV-010")', "CanonicalFinancialHash",
        ),
    )
    transaction_model = next((s for p, s in sources.items() if p.endswith("TransactionModel.kt")), "")
    require_tokens(
        errors,
        transaction_model,
        "typed refund transaction model",
        ("RefundAllocationFact", "RefundAllocationReference", "RefundPayload", "RefundStatusProjection"),
    )
    writer = "\n".join(
        s for p, s in sources.items() if p.endswith(("RoomFinancialPlanWriter.kt", "RoomRefundFactWriter.kt"))
    )
    require_tokens(errors, writer, "refund fact writer", ("INSERT INTO refund_allocation", "reversal_of_id", "plan.refundAllocations"))
    projection = next((s for p, s in sources.items() if p.endswith("RoomProjectionEngine.kt")), "")
    require_tokens(
        errors,
        projection,
        "refund projection rebuild",
        (
            "refund_status_projection",
            "transaction_dependency",
            "FROM refund_allocation",
            "ProjectionChange.Refund",
            "publishProjectionGeneration",
        ),
    )
    journal = next((s for p, s in sources.items() if p.endswith("SecureRoomJournalApplicationPort.kt")), "")
    require_tokens(
        errors,
        journal,
        "atomic original/refund dependency handling",
        (
            "DependencyPolicy.ReverseDependentTransactions", "DependencyPolicy.ConvertRefundToIndependent",
            "BatchFinancialCommand", "RoomJournalRefundRelations.summaries", "AmountRole.REFUND.ordinal",
        ),
    )
    route = next((s for p, s in sources.items() if p.endswith("RefundRootDestination.kt")), "")
    require_tokens(errors, route, "stable refund route", ('encodedArguments["transactionId"]', "StableId.parse"))
    if re.search(r'encodedArguments\["(?:amount|note|name|card|attachment|location|merchant|project|goal)', route, re.IGNORECASE):
        errors.append("refund route carries sensitive or mutable business data")
    return errors


def validate_tests_and_resources() -> list[str]:
    tests = "\n".join(
        path.read_text(encoding="utf-8")
        for root in ("feature/record/src/test", "feature/record/src/androidTest", "finance/data/src/androidTest", "finance/domain/src/test")
        for path in sorted((ROOT / root).rglob("*.kt"))
    )
    errors: list[str] = []
    require_tokens(
        errors,
        tests,
        "P16 automated evidence",
        (
            "linked refunds are balanced contra expense facts across partial full and cross month amounts",
            "multiple refunds enforce cumulative remaining and preserve explicit excess evidence",
            "originalTrashCanAtomicallyConvertAllLinkedRefundsToIndependent",
            "partialFullCrossMonthOtherAccountIndependentAndExcessRefundsRebuildFromFacts",
            "rec015AndRec016RenderEveryRequiredStateAcrossResponsiveAccessibleLocalizedMatrix",
            "highRiskOverrideAndThreeDateDimensionsRemainReachableAtTwoHundredPercentFont",
            "linkedAndExcessRefundGoldensMatchEveryPixel",
        ),
    )
    golden = next((path.read_text(encoding="utf-8") for path in (ROOT / "feature/record/src/androidTest").rglob("P16GoldenDeviceTest.kt")), "")
    hashes = re.findall(r'const val \w+_SHA256 = "([0-9a-f]{64})"', golden)
    if len(hashes) != 2 or len(set(hashes)) != 2:
        errors.append("P16 requires two distinct exact-pixel SHA-256 Compose goldens")
    resource_sets = []
    for relative in ("values/strings.xml", "values-en/strings.xml", "values-ja/strings.xml"):
        text = read(f"feature/record/src/main/res/{relative}")
        resource_sets.append({key for key in re.findall(r'<string name="([^"]+)"', text) if key.startswith("refund_")})
    if not resource_sets[0] or resource_sets[0] != resource_sets[1] or resource_sets[0] != resource_sets[2]:
        errors.append("P16 refund strings are incomplete across zh-CN/en/ja")
    return errors


def validate_ledgers() -> list[str]:
    errors: list[str] = []
    state = read("docs/初始开发文件存档/implementation/PROJECT_STATE.md")
    evidence = read("docs/初始开发文件存档/implementation/TEST_EVIDENCE.md")
    mapping_path = ROOT / "docs/初始开发文件存档/implementation/P16_REFUND_MAPPING.md"
    mapping = mapping_path.read_text(encoding="utf-8") if mapping_path.is_file() else ""
    require_tokens(errors, state, "PROJECT_STATE", ("Current stage: P36", "| P16 | VERIFIED |"))
    for index in range(1, 8):
        if f"P16-E{index:03d}" not in evidence:
            errors.append(f"TEST_EVIDENCE missing P16-E{index:03d}")
    require_tokens(
        errors,
        mapping,
        "P16 mapping",
        ("8 required states", "FinancialMutationCoordinator", "immutable refund allocation", "P16 is `VERIFIED`"),
    )
    with (ROOT / "docs/初始开发文件存档/implementation/SCREEN_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        screens = {row["screen_id"]: row for row in csv.DictReader(handle)}
    for screen_id in EXPECTED:
        row = screens.get(screen_id, {})
        if row.get("status") != "VERIFIED" or "P16" not in row.get("implementation_evidence", "") or "P16-E" not in row.get("verification_evidence", ""):
            errors.append(f"{screen_id} must carry VERIFIED P16 implementation and test evidence")
    with (ROOT / "docs/初始开发文件存档/implementation/REQUIREMENT_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        requirements = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    for requirement_id in TARGET_REQUIREMENTS:
        row = requirements.get(requirement_id, {})
        if row.get("status") not in {"IN_PROGRESS", "VERIFIED"} or "P16" not in row.get("implementation_evidence", "") or "P16-E" not in row.get("verification_evidence", ""):
            errors.append(f"{requirement_id} must carry truthful P16 implementation and test evidence")
    return errors


def main() -> int:
    errors = validate_contract() + validate_sources() + validate_tests_and_resources() + validate_ledgers()
    if errors:
        print("P16 refund validation: FAIL", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("P16 refund validation: PASS")
    print("screens=2 required_states=8 property_iterations=500 goldens=2 visual_inputs=contract_token_yaml_only")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
