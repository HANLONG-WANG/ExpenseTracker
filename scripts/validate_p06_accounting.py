#!/usr/bin/env python3
"""Validate P06 deterministic accounting planners without reading visual draft artifacts."""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path
from typing import Mapping


ROOT = Path(__file__).resolve().parents[1]
DOMAIN_ROOT = Path("finance/domain/src/main/kotlin/app/ledger/finance/domain")
APPLICATION_ROOT = Path("finance/application/src/main/kotlin/app/ledger/finance/application")
TARGET_REQUIREMENTS = {
    "REQ-006",
    "REQ-007",
    "REQ-014",
    "REQ-025",
    "REQ-029",
    "REQ-030",
    "REQ-031",
    "REQ-033",
    "REQ-088",
}
CORE_INVARIANTS = {
    "INV-001",
    "INV-002",
    "INV-003",
    "INV-004",
    "INV-005",
    "INV-006",
    "INV-007",
    "INV-008",
    "INV-009",
    "INV-010",
    "INV-011",
    "INV-012",
    "INV-013",
    "INV-014",
    "INV-015",
    "INV-016",
    "INV-017",
    "INV-021",
    "INV-022",
    "INV-023",
    "INV-024",
    "INV-028",
    "INV-029",
    "INV-031",
    "INV-034",
}
TRANSACTION_PAYLOADS = {
    "ExpensePayload",
    "IncomePayload",
    "TransferPayload",
    "RefundPayload",
    "CreditPaymentPayload",
    "LoanDisbursementPayload",
    "LoanPaymentPayload",
    "BalanceAdjustmentPayload",
    "FxExchangePayload",
    "SettlementPaymentPayload",
    "OpeningBalancePayload",
}
EXPECTED_RULE_METHODS = {
    "ExpensePayload": "expense",
    "IncomePayload": "income",
    "TransferPayload": "transfer",
    "RefundPayload": "refund",
    "CreditPaymentPayload": "creditPayment",
    "LoanDisbursementPayload": "loanDisbursement",
    "LoanPaymentPayload": "loanPayment",
    "BalanceAdjustmentPayload": "balanceAdjustment",
    "FxExchangePayload": "fxExchange",
    "SettlementPaymentPayload": "settlementPayment",
    "OpeningBalancePayload": "openingBalance",
}
REQUIRED_FILES = {
    "AccountingPlanningContext.kt",
    "AccountingRuleEngine.kt",
    "CanonicalFinancialHash.kt",
    "DeterministicFinancialPlanner.kt",
    "FinancialFactAudit.kt",
}
FORBIDDEN_IMPORT = re.compile(
    r"(?m)^import\s+(?:androidx?(?:\.|$)|dagger(?:\.|$)|com\.google\.dagger(?:\.|$)|"
    r"okhttp3(?:\.|$)|retrofit2(?:\.|$)|app\.ledger\.core\.network(?:\.|$))"
)
FORBIDDEN_PRODUCTION = re.compile(
    r"\b(?:TODO|NotImplemented|UnsupportedOperationException|Float|Double)\b|"
    r"\b(?:Instant\.now|LocalDate\.now|UUID\.randomUUID|Random\.Default|SecureRandom)\b"
)


def load_sources() -> dict[str, str]:
    sources: dict[str, str] = {}
    for root in (DOMAIN_ROOT, APPLICATION_ROOT):
        for path in sorted((ROOT / root).glob("*.kt")):
            sources[path.relative_to(ROOT).as_posix()] = path.read_text(encoding="utf-8")
    return sources


def source_named(sources: Mapping[str, str], filename: str) -> str:
    return next((text for path, text in sources.items() if path.endswith(filename)), "")


def validate_sources(sources: Mapping[str, str]) -> list[str]:
    errors: list[str] = []
    filenames = {Path(path).name for path in sources}
    missing_files = REQUIRED_FILES - filenames
    if missing_files:
        errors.append(f"P06 production files missing: {sorted(missing_files)}")
    for path, source in sources.items():
        if FORBIDDEN_IMPORT.search(source):
            errors.append(f"framework import in pure accounting source: {path}")
        if FORBIDDEN_PRODUCTION.search(source):
            errors.append(f"placeholder, floating money, clock or random source in: {path}")

    rules = source_named(sources, "AccountingRuleEngine.kt")
    planned_rules = dict(re.findall(r"is\s+(\w+Payload)\s*->\s*session\.(\w+)\(", rules))
    if planned_rules != EXPECTED_RULE_METHODS:
        errors.append(f"accounting rule closure differs: {sorted(planned_rules.items())}")
    for code in (
        "SYSTEM_FX_CLEARING",
        "SYSTEM_FX_ROUNDING",
        "SYSTEM_FX_COST",
        "SYSTEM_FX_GAIN",
        "SYSTEM_OPENING_EQUITY",
        "SYSTEM_BALANCE_ADJUSTMENT",
    ):
        if code not in rules:
            errors.append(f"accounting rules do not materialize {code}")

    planner = source_named(sources, "DeterministicFinancialPlanner.kt")
    for required in (
        "RevisionAction.CREATE",
        "RevisionAction.EDIT",
        "RevisionAction.MOVE_TO_TRASH",
        "RevisionAction.RESTORE",
        "reverseCurrentFacts",
        "materializeApplyFacts",
        "FinancialMutationPlanValidator.validate",
        "TransactionSource.RECURRENCE_CANDIDATE",
    ):
        if required not in planner and required != "TransactionSource.RECURRENCE_CANDIDATE":
            errors.append(f"planner lifecycle contract missing {required}")
    if "TransactionSource.RECURRENCE_CANDIDATE" not in rules:
        errors.append("candidate source is not rejected before fact planning")

    context = source_named(sources, "AccountingPlanningContext.kt")
    for explicit_input in (
        "PlanningIdentitySet",
        "val createdAt: Instant",
        "val deviceInstanceId: DeviceInstanceId",
        "val amountEvidence: List<FrozenAmountEvidence>",
        "val currentFacts: CurrentFinancialFacts?",
        "FrozenFxConversion",
    ):
        if explicit_input not in context:
            errors.append(f"explicit deterministic planning input missing {explicit_input}")

    canonical = source_named(sources, "CanonicalFinancialHash.kt")
    for required in (
        'MessageDigest.getInstance("SHA-256")',
        "BOOK_COMMIT_ROOT_V1",
        "FINANCIAL_EVIDENCE_AND_EFFECTS_V1",
        "revisionAmounts.forEach",
        "fxSnapshots.forEach",
        "settlement.forEach",
    ):
        combined = canonical + source_named(sources, "DomainIdentityAndLifecycle.kt")
        if required not in combined:
            errors.append(f"canonical financial hash coverage missing {required}")

    audit = source_named(sources, "FinancialFactAudit.kt")
    for required in ("validateReversal", "reversalOfPostingId", "CheckedArithmetic.negate", "BigInteger.ZERO"):
        if required not in audit:
            errors.append(f"immutable reversal/net audit missing {required}")

    coordinator = source_named(sources, "FinancialMutationCoordinator.kt")
    canonical_check = "CanonicalFinancialHash.command(command) != command.payloadHash"
    if canonical_check not in coordinator:
        errors.append("coordinator does not reject a non-canonical command before receipt lookup")
    if coordinator.find(canonical_check) > coordinator.find("receiptRepository.find(command.commandId)"):
        errors.append("canonical command validation occurs after idempotency lookup")
    return errors


def validate_requirement_rows(rows: list[dict[str, str]]) -> list[str]:
    by_id = {row["requirement_id"]: row for row in rows}
    errors: list[str] = []
    for requirement_id in TARGET_REQUIREMENTS:
        row = by_id.get(requirement_id)
        if row is None or row["status"] != "IN_PROGRESS":
            errors.append(f"{requirement_id} must remain truthful IN_PROGRESS after the P06 domain layer")
            continue
        if "P06" not in row["implementation_evidence"] or "P06-E" not in row["verification_evidence"]:
            errors.append(f"{requirement_id} lacks P06 implementation and verification evidence")
    return errors


def validate_mapping(mapping: str) -> list[str]:
    errors: list[str] = []
    mapped = set(re.findall(r"\|\s*(INV-\d{3})\s*\|", mapping))
    expected = {f"INV-{value:03d}" for value in range(1, 36)}
    if mapped != expected:
        errors.append(f"P06 invariant mapping differs: missing={sorted(expected - mapped)} extra={sorted(mapped - expected)}")
    for invariant in CORE_INVARIANTS:
        row = next((line for line in mapping.splitlines() if f"| {invariant} |" in line), "")
        if "AUTOMATED" not in row or "P06" not in row:
            errors.append(f"{invariant} lacks explicit P06 automated evidence mapping")
    return errors


def validate_project_state(project_state: str) -> list[str]:
    current_stage = re.search(r"Current stage: P(\d{2})", project_state)
    if (
        current_stage is None
        or not 6 <= int(current_stage.group(1)) <= 36
        or "| P06 | VERIFIED |" not in project_state
        or "### P06 result" not in project_state
    ):
        return ["PROJECT_STATE does not retain P06 VERIFIED in the cumulative stage ledger"]
    return []


def main() -> int:
    sources = load_sources()
    errors = validate_sources(sources)
    with (ROOT / "docs/implementation/REQUIREMENT_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        errors.extend(validate_requirement_rows(list(csv.DictReader(handle))))
    mapping = (ROOT / "docs/implementation/P06_ACCOUNTING_INVARIANT_MAPPING.md").read_text(encoding="utf-8")
    errors.extend(validate_mapping(mapping))
    project_state = (ROOT / "docs/implementation/PROJECT_STATE.md").read_text(encoding="utf-8")
    errors.extend(validate_project_state(project_state))
    evidence = (ROOT / "docs/implementation/TEST_EVIDENCE.md").read_text(encoding="utf-8")
    if any(f"P06-E{value:03d}" not in evidence for value in range(1, 7)):
        errors.append("TEST_EVIDENCE does not contain P06-E001..P06-E006")
    screens = list(
        csv.DictReader((ROOT / "docs/implementation/SCREEN_COVERAGE.csv").open(encoding="utf-8", newline=""))
    )
    p11_promotions = {
        "REC-009": "IN_PROGRESS",
        "REC-010": "IN_PROGRESS",
        "ATT-001": "VERIFIED",
        "ATT-002": "VERIFIED",
        "ATT-003": "VERIFIED",
        "SYS-001": "VERIFIED",
        **{f"G-{number:03d}": "VERIFIED" for number in range(1, 9)},
        **{f"ONB-{number:03d}": "VERIFIED" for number in range(1, 11)},
    }
    if len(screens) != 215 or any(
        row["status"] != p11_promotions.get(row["screen_id"], "NOT_STARTED") for row in screens
    ):
        errors.append("screen coverage contains a promotion outside the completed P11 scope")
    if errors:
        print("P06 accounting validation: FAIL", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("P06 accounting validation: PASS")
    print(f"production_files={len(sources)} transaction_rules={len(TRANSACTION_PAYLOADS)}")
    print(f"target_requirements={len(TARGET_REQUIREMENTS)} permanent_invariants=35 core_automated={len(CORE_INVARIANTS)}")
    print("screens_total=215 p11_promoted=24 visual_inputs=excluded_by_explicit_source_roots")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
