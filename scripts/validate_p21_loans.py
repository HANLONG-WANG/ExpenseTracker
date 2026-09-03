#!/usr/bin/env python3
"""Reject P21 loan drift, accounting bypasses, unsafe routes, floating money, or false verification."""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
EXPECTED = {
    "REC-017": ("record/loan-operation", [], {"content"}),
    "REC-018": ("record/loan-disbursement/{contractId?}", ["contractId:StableId?"], {"editing", "allocationError", "saving"}),
    "REC-019": ("record/loan-payment/{contractId?}", ["contractId:StableId?"], {"editing", "principalExceeded", "sumMismatch", "saving"}),
    "LIA-001": ("liabilities", [], {"content", "empty", "overdue"}),
    "LOA-001": ("loans", [], {"content", "empty", "closed"}),
    "LOA-002": ("loans/wizard/{contractId?}", ["contractId:StableId?"], {"editing", "invalid", "generatingSchedule", "ready"}),
    "LOA-003": ("loans/{contractId}/tranche/{trancheId?}", ["contractId:StableId", "trancheId:StableId?"], {"create", "edit"}),
    "LOA-004": ("loans/{contractId}/terms/{trancheId}", ["contractId:StableId", "trancheId:StableId"], {"editing", "invalid"}),
    "LOA-005": ("loans/{contractId}/rates/{trancheId}", ["contractId:StableId", "trancheId:StableId"], {"content", "overlapError", "empty"}),
    "LOA-006": ("loans/{contractId}/schedule-preview", ["contractId:StableId"], {"generating", "content", "calculationError"}),
    "LOA-007": ("loans/{contractId}", ["contractId:StableId"], {"active", "closed", "overduePlanDifference", "multiTranche"}),
    "LOA-008": ("loans/{contractId}/schedule", ["contractId:StableId"], {"content", "empty"}),
    "LOA-009": ("loans/payment/{transactionId}", ["transactionId:StableId"], {"content"}),
    "LOA-010": ("loans/{contractId}/simulation", ["contractId:StableId"], {"editing", "calculating", "result", "invalid"}),
    "LOA-011": ("loans/{contractId}/simulation/{simulationId}/apply", ["contractId:StableId", "simulationId:StableId"], {"content", "conflict"}),
}
PLACEHOLDER = re.compile(r"\b(?:TODO|NotImplemented|UnsupportedOperationException)\b")
FORBIDDEN_FEATURE = re.compile(r"(?m)^import\s+(?:androidx\.room|androidx\.compose\.material3|app\.ledger\.(?:finance\.data|core\.(?:database|security)))")


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def source_map() -> dict[str, str]:
    roots = ("app/src/main/kotlin", "core/designsystem/src/main/kotlin", "feature/liabilities/src/main/kotlin", "finance/application/src/main/kotlin", "finance/data/src/main/kotlin", "finance/domain/src/main/kotlin")
    return {path.relative_to(ROOT).as_posix(): path.read_text(encoding="utf-8") for root in roots for path in sorted((ROOT / root).rglob("*.kt"))}


def require_tokens(errors: list[str], text: str, label: str, tokens: tuple[str, ...]) -> None:
    for token in tokens:
        if token not in text:
            errors.append(f"{label} missing {token}")


def validate_contract() -> list[str]:
    screens = {item["id"]: item for item in yaml.safe_load(read("docs/初始开发文件存档/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml"))["screens"]}
    errors: list[str] = []
    for screen_id, (route, params, states) in EXPECTED.items():
        actual = screens.get(screen_id, {})
        if actual.get("route") != route: errors.append(f"{screen_id} route drift")
        if actual.get("params", []) != params: errors.append(f"{screen_id} params drift")
        if set(actual.get("requiredStates", [])) != states: errors.append(f"{screen_id} requiredStates drift")
    if sum(len(value[2]) for value in EXPECTED.values()) != 41:
        errors.append("P21 required-state baseline must remain exactly 41")
    return errors


def validate_sources(sources: dict[str, str] | None = None) -> list[str]:
    sources = source_map() if sources is None else sources
    errors: list[str] = []
    names = {"LoanAccountingPolicy.kt", "LoanApplication.kt", "SecureRoomLoanApplicationPort.kt", "RoomLoanContractWriter.kt", "LoanState.kt", "LoanScreens.kt", "LoanRootDestination.kt", "LoanReplayReceiptVerifier.kt"}
    selected = {path: source for path, source in sources.items() if Path(path).name in names}
    missing = names - {Path(path).name for path in selected}
    if missing: errors.append(f"P21 production files missing: {sorted(missing)}")
    for path, source in selected.items():
        if PLACEHOLDER.search(source): errors.append(f"placeholder in {path}")
    feature = "\n".join(value for path, value in sources.items() if path.startswith("feature/liabilities/"))
    if FORBIDDEN_FEATURE.search(feature) or re.search(r"\b(?:Dao|Entity|execSQL|JournalEntry|Posting)\b", feature):
        errors.append("loan feature bypasses UI/application boundaries")
    if re.search(r"\b(?:MaterialTheme|Color\s*\(|SwipeToDismiss|detectHorizontalDragGestures)\b|\b\d+(?:\.\d+)?\.dp\b", feature):
        errors.append("loan feature bypasses design tokens or introduces swipe deletion")
    require_tokens(errors, feature, "governed loan UI", ("LoanDestination", "LoanPresentation", "LedgerTestTags.LOAN_WIZARD", "AccessibleDataTable", "HighRiskConfirmation", "loan_simulation_no_write", "loan_future_not_current"))
    domain = next((value for path, value in sources.items() if path.endswith("LoanAccountingPolicy.kt")), "")
    require_tokens(errors, domain, "deterministic loan policy", ("equalPayment", "equalPrincipal", "interestOnly", "bullet", "custom", "MathContext.DECIMAL128", "Math.addExact", "Math.subtractExact", "validatePayment", "simulatePrepayment"))
    if re.search(r"\b(?:Float|Double)\b", domain): errors.append("authoritative loan policy contains floating-point money")
    application = next((value for path, value in sources.items() if path.endswith("LoanApplication.kt")), "")
    require_tokens(errors, application, "typed loan port", ("LoanContractView", "LoanTrancheView", "SaveLoanContractRequest", "RecordLoanDisbursementRequest", "RecordLoanPaymentRequest", "LoanSimulationRequest", "LoanApplicationPort"))
    data = next((value for path, value in sources.items() if path.endswith("SecureRoomLoanApplicationPort.kt")), "")
    require_tokens(errors, data, "encrypted loan adapter", ("EncryptedDatabaseFactory.openPrimary", "DefaultFinancialMutationCoordinator", "CanonicalFinancialHash.command", "LoanReplayReceiptVerifier.payment", "loan_simulation", "recordDisbursement", "recordPayment", "applySimulation"))
    if re.search(r"\b(?:INSERT|UPDATE|DELETE)\s+(?:INTO\s+|FROM\s+)?(?:journal_entry|posting|economic_effect|loan_effect)", data, re.IGNORECASE):
        errors.append("loan application adapter directly writes coordinated facts")
    writer = next((value for path, value in sources.items() if path.endswith("RoomFinancialPlanWriter.kt")), "")
    require_tokens(errors, writer, "coordinated loan writer", ("RoomLoanContractWriter", "loanContractWriter.write"))
    projection = next((value for path, value in sources.items() if path.endswith("RoomProjectionEngine.kt")), "")
    require_tokens(errors, projection, "forecast projection", ("loan_future_cashflow_projection", "planned_date > ?", "loan_progress_projection"))
    root = next((value for path, value in sources.items() if path.endswith("LoanRootDestination.kt")), "")
    require_tokens(errors, root, "safe loan route", ('loanStableId("contractId")', 'loanStableId("trancheId")', 'loanStableId("transactionId")', 'loanStableId("simulationId")'))
    if re.search(r'encodedArguments\["(?:amount|note|name|lender|account|rate|principal|fee|penalty)', root, re.IGNORECASE): errors.append("loan route carries sensitive business data")
    return errors


def validate_tests_resources() -> list[str]:
    tests = "\n".join(path.read_text(encoding="utf-8") for root in ("feature/liabilities/src/androidTest", "finance/data/src/androidTest", "finance/domain/src/test") for path in sorted((ROOT / root).rglob("*.kt")))
    errors: list[str] = []
    require_tokens(errors, tests, "P21 automated evidence", ("allFortyOneFrozenStatesRenderAcrossWidthFontLocaleAndThemeMatrix", "combinationPrincipalAndSimulationRemainExplicitAtCompactTwoHundredPercentFont", "combinationLoanPaymentsSimulationsAndRebuildAreAtomicVersionedAndForecastOnly", "all repayment methods conserve principal and close their remaining chain", "PRAGMA integrity_check", "pragma_foreign_key_check"))
    resource_sets = []
    for folder in ("values", "values-en", "values-ja"):
        resource_sets.append({name for name in re.findall(r'<string name="([^"]+)"', read(f"feature/liabilities/src/main/res/{folder}/strings.xml")) if name.startswith("loan_") or name.startswith("liability_")})
    if not resource_sets[0] or resource_sets[0] != resource_sets[1] or resource_sets[0] != resource_sets[2]: errors.append("P21 loan strings incomplete across zh-CN/en/ja")
    return errors


def validate_ledgers() -> list[str]:
    errors: list[str] = []
    state, evidence = read("docs/初始开发文件存档/implementation/PROJECT_STATE.md"), read("docs/初始开发文件存档/implementation/TEST_EVIDENCE.md")
    mapping = read("docs/初始开发文件存档/implementation/P21_LOAN_MAPPING.md") if (ROOT / "docs/初始开发文件存档/implementation/P21_LOAN_MAPPING.md").is_file() else ""
    require_tokens(errors, state, "PROJECT_STATE", ("Current stage: P36", "| P21 | VERIFIED |"))
    for index in range(1, 8):
        if f"P21-E{index:03d}" not in evidence: errors.append(f"TEST_EVIDENCE missing P21-E{index:03d}")
    require_tokens(errors, mapping, "P21 mapping", ("41 required states", "FinancialMutationCoordinator", "forecast", "principal conservation", "P21 is `VERIFIED`"))
    with (ROOT / "docs/初始开发文件存档/implementation/SCREEN_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        screens = {row["screen_id"]: row for row in csv.DictReader(handle)}
    for screen_id in EXPECTED:
        row = screens.get(screen_id, {})
        if row.get("status") != "VERIFIED" or "P21" not in row.get("implementation_evidence", "") or "P21-E" not in row.get("verification_evidence", ""): errors.append(f"{screen_id} lacks VERIFIED P21 evidence")
    with (ROOT / "docs/初始开发文件存档/implementation/REQUIREMENT_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        requirements = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    for requirement_id in ("REQ-041", "REQ-042"):
        row = requirements.get(requirement_id, {})
        if row.get("status") != "VERIFIED" or "P21" not in row.get("implementation_evidence", "") or "P21-E" not in row.get("verification_evidence", ""): errors.append(f"{requirement_id} lacks VERIFIED P21 evidence")
    return errors


def main() -> int:
    errors = validate_contract() + validate_sources() + validate_tests_resources() + validate_ledgers()
    if errors:
        print("P21 loan validation: FAIL", file=sys.stderr)
        for error in errors: print(f"- {error}", file=sys.stderr)
        return 1
    print("P21 loan validation: PASS")
    print("screens=15 required_states=41 arithmetic=checked_integer writes=FinancialMutationCoordinator forecast_only=true visual_inputs=contract_token_yaml_only")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
