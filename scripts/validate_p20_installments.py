#!/usr/bin/env python3
"""Reject P20 installment drift, money duplication, write bypasses or false verification."""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
EXPECTED = {
    "REC-027": ("record/installment-setup/{purchaseTransactionId?}", ["purchaseTransactionId:StableId?"], {"editing", "preview", "invalid", "saving"}),
    "INS-001": ("installments", [], {"content", "empty", "completed"}),
    "INS-002": ("installments/editor/{planId?}", ["planId:StableId?"], {"create", "edit", "invalid"}),
    "INS-003": ("installments/{planId}", ["planId:StableId"], {"active", "completed", "refundAdjusted"}),
    "INS-004": ("installments/{planId}/schedule", ["planId:StableId"], {"content"}),
    "INS-005": ("installments/{planId}/early-settlement", ["planId:StableId"], {"editing", "calculated", "invalid"}),
    "INS-006": ("installments/{planId}/refund-impact", ["planId:StableId"], {"content", "requiresDecision"}),
}
TARGET_REQUIREMENTS = {"REQ-040"}
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


def source_map() -> dict[str, str]:
    roots = (
        "app/src/main/kotlin", "core/designsystem/src/main/kotlin", "feature/liabilities/src/main/kotlin",
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
    for screen_id, (route, params, states) in EXPECTED.items():
        screen = screens.get(screen_id, {})
        if screen.get("route") != route:
            errors.append(f"{screen_id} route drift")
        if screen.get("params", []) != params:
            errors.append(f"{screen_id} route parameters drift")
        if set(screen.get("requiredStates", [])) != states:
            errors.append(f"{screen_id} requiredStates drift")
    if sum(len(states) for _, _, states in EXPECTED.values()) != 19:
        errors.append("P20 required-state baseline must remain exactly 19")
    return errors


def validate_sources(sources: dict[str, str] | None = None) -> list[str]:
    sources = source_map() if sources is None else sources
    errors: list[str] = []
    relevant_names = {
        "InstallmentAccountingPolicy.kt", "InstallmentApplication.kt", "SecureRoomInstallmentApplicationPort.kt",
        "RoomInstallmentPlanWriter.kt", "InstallmentState.kt", "InstallmentScreens.kt", "InstallmentRootDestination.kt",
    }
    selected = {path: source for path, source in sources.items() if Path(path).name in relevant_names}
    missing = relevant_names - {Path(path).name for path in selected}
    if missing:
        errors.append(f"P20 production files missing: {sorted(missing)}")
    for path, source in selected.items():
        if PLACEHOLDER.search(source):
            errors.append(f"placeholder production implementation in {path}")

    feature = "\n".join(source for path, source in sources.items() if path.startswith("feature/liabilities/"))
    if FORBIDDEN_FEATURE_IMPORT.search(feature) or re.search(r"\b(?:Dao|Entity|execSQL|JournalEntry|Posting)\b", feature):
        errors.append("installment feature bypasses governed UI/application boundaries")
    if re.search(r"\b(?:MaterialTheme|Color\s*\(|SwipeToDismiss|detectHorizontalDragGestures)\b|\b\d+(?:\.\d+)?\.dp\b", feature):
        errors.append("installment feature bypasses design-system tokens or contains swipe deletion")
    require_tokens(errors, feature, "P20 governed UI", (
        "InstallmentDestination", "InstallmentPresentation", "REFUND_ADJUSTED", "REQUIRES_DECISION",
        "LedgerTestTags.INSTALLMENT_SETUP", "LedgerTestTags.INSTALLMENT_SCHEDULE",
        "installment_purchase_not_split", "installment_schedule_not_transaction", "HighRiskConfirmation",
    ))

    application = next((source for path, source in sources.items() if path.endswith("InstallmentApplication.kt")), "")
    require_tokens(errors, application, "typed installment application boundary", (
        "InstallmentSnapshot", "InstallmentPlanView", "InstallmentMutationIds", "InstallmentSettlementIds",
        "SaveInstallmentPlanRequest", "ApplyInstallmentSettlementRequest", "ApplyInstallmentRefundRequest",
        "InstallmentApplicationPort",
    ))
    domain = "\n".join(source for path, source in sources.items() if path.startswith("finance/domain/"))
    require_tokens(errors, domain, "installment domain", (
        "InstallmentAccountingPolicy", "InstallmentFeeRateType.FIXED_PER_TERM",
        "InstallmentFeeRateType.FIRST_TERM_FIXED", "InstallmentFeeRateType.REMAINING_PRINCIPAL_RATE",
        "InstallmentFeeRateType.EFFECTIVE_ANNUAL_RATE", "REBUILD_SCHEDULE",
        "ApplyInstallmentSettlementCommand", "InstallmentPlanMutation", "Math.subtractExact",
    ))
    policy = next((source for path, source in sources.items() if path.endswith("InstallmentAccountingPolicy.kt")), "")
    require_tokens(errors, policy, "exact installment schedule", (
        "if (number == request.termCount) remaining else basePrincipal", "MathContext.DECIMAL128",
        ".setScale(0, terms.roundingMode)",
        "simulateSettlement", "recalculateAfterRefund", "InstallmentSchedulePolicy.validate",
    ))
    if re.search(r"\b(?:Float|Double)\b", policy):
        errors.append("authoritative installment schedule contains floating-point arithmetic")

    data = next((source for path, source in sources.items() if path.endswith("SecureRoomInstallmentApplicationPort.kt")), "")
    require_tokens(errors, data, "encrypted installment adapter", (
        "EncryptedDatabaseFactory.openPrimary", "DefaultFinancialMutationCoordinator", "CanonicalFinancialHash.command",
        "InstallmentAccountingPolicy.generate", "InstallmentAccountingPolicy.simulateSettlement",
        "expectedRevisionId", "InstallmentReplayReceiptVerifier.settlement",
        "InstallmentReplayReceiptVerifier.refund", "refundTransactionId",
    ))
    if re.search(r"\b(?:INSERT|UPDATE|DELETE)\s+(?:INTO\s+|FROM\s+)?(?:journal_entry|posting|economic_effect|installment_refund_allocation)", data, re.IGNORECASE):
        errors.append("installment application adapter directly writes coordinated financial facts")
    writer = next((source for path, source in sources.items() if path.endswith("RoomFinancialPlanWriter.kt")), "")
    require_tokens(errors, writer, "coordinated installment fact writer", ("RoomInstallmentPlanWriter", "installmentPlanWriter.write"))

    root = next((source for path, source in sources.items() if path.endswith("InstallmentRootDestination.kt")), "")
    require_tokens(errors, root, "safe installment root", (
        'encodedArguments.stableId("planId")', 'encodedArguments.stableId("purchaseTransactionId")',
        "installmentFixedAction",
    ))
    if re.search(r'encodedArguments\["(?:amount|note|name|card|attachment|location|currency|balance|fee)', root, re.IGNORECASE):
        errors.append("installment route carries sensitive or mutable business data")
    app_sources = "\n".join(source for path, source in sources.items() if path.startswith("app/src/main/kotlin/"))
    if re.search(r"\b(?:InstallmentDao|InstallmentPlanEntity|RoomInstallmentPlanWriter)\b", app_sources):
        errors.append("app UI obtains installment DAO/entity or fact writer")
    return errors


def validate_tests_and_resources() -> list[str]:
    tests = "\n".join(
        path.read_text(encoding="utf-8")
        for root in ("feature/liabilities/src/androidTest", "finance/data/src/androidTest", "finance/domain/src/test")
        for path in sorted((ROOT / root).rglob("*.kt"))
    )
    errors: list[str] = []
    require_tokens(errors, tests, "P20 automated evidence", (
        "allNineteenFrozenStatesRenderAcrossWidthFontLocaleAndThemeMatrix",
        "scheduleConservationSettlementSimulationAndRefundDecisionRemainExplicitAtCompactLargeFont",
        "installmentDetailAndCalculatedSettlementGoldensMatchEveryPixel",
        "generated principal always conserves and final term absorbs every tail",
        "all frozen fee models are exact deterministic and separated from principal",
        "settlement is a pure comparison and never mutates the schedule",
        "refund schedule remains versioned and conserves reduced principal",
        "planPreviewSettlementIdempotencyAndProjectionRebuildPreserveAccountingSemantics",
        "linkedPartialRefundAllocatesPrincipalAndFeeAndKeepsPriorScheduleVersion",
        "PRAGMA integrity_check", "pragma_foreign_key_check",
    ))
    golden = next((path.read_text(encoding="utf-8") for path in (ROOT / "feature/liabilities/src/androidTest").rglob("P20GoldenDeviceTest.kt")), "")
    hashes = re.findall(r'"([0-9a-f]{64})"', golden)
    if len(hashes) != 2 or len(set(hashes)) != 2:
        errors.append("P20 requires two distinct exact-pixel SHA-256 Compose goldens")
    resource_sets = []
    for relative in ("values/strings.xml", "values-en/strings.xml", "values-ja/strings.xml"):
        content = read(f"feature/liabilities/src/main/res/{relative}")
        resource_sets.append({key for key in re.findall(r'<string name="([^"]+)"', content) if key.startswith("installment_")})
    if not resource_sets[0] or resource_sets[0] != resource_sets[1] or resource_sets[0] != resource_sets[2]:
        errors.append("P20 installment strings are incomplete across zh-CN/en/ja")
    return errors


def validate_ledgers() -> list[str]:
    errors: list[str] = []
    state = read("docs/初始开发文件存档/implementation/PROJECT_STATE.md")
    evidence = read("docs/初始开发文件存档/implementation/TEST_EVIDENCE.md")
    mapping_path = ROOT / "docs/初始开发文件存档/implementation/P20_INSTALLMENT_MAPPING.md"
    mapping = mapping_path.read_text(encoding="utf-8") if mapping_path.is_file() else ""
    require_tokens(errors, state, "PROJECT_STATE", ("Current stage: P36", "| P20 | VERIFIED |"))
    for index in range(1, 8):
        if f"P20-E{index:03d}" not in evidence:
            errors.append(f"TEST_EVIDENCE missing P20-E{index:03d}")
    require_tokens(errors, mapping, "P20 mapping", (
        "19 required states", "FinancialMutationCoordinator", "last term", "simulation", "P20 is `VERIFIED`",
    ))
    with (ROOT / "docs/初始开发文件存档/implementation/SCREEN_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        screens = {row["screen_id"]: row for row in csv.DictReader(handle)}
    for screen_id in EXPECTED:
        row = screens.get(screen_id, {})
        if row.get("status") != "VERIFIED" or "P20" not in row.get("implementation_evidence", "") or "P20-E" not in row.get("verification_evidence", ""):
            errors.append(f"{screen_id} must carry VERIFIED P20 implementation and test evidence")
    with (ROOT / "docs/初始开发文件存档/implementation/REQUIREMENT_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        requirements = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    for requirement_id in TARGET_REQUIREMENTS:
        row = requirements.get(requirement_id, {})
        if row.get("status") != "VERIFIED" or "P20" not in row.get("implementation_evidence", "") or "P20-E" not in row.get("verification_evidence", ""):
            errors.append(f"{requirement_id} must carry VERIFIED P20 implementation and test evidence")
    return errors


def main() -> int:
    errors = validate_contract() + validate_sources() + validate_tests_and_resources() + validate_ledgers()
    if errors:
        print("P20 installment validation: FAIL", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("P20 installment validation: PASS")
    print("screens=7 required_states=19 goldens=2 arithmetic=checked_integer financial_entry=FinancialMutationCoordinator visual_inputs=contract_token_yaml_only")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
