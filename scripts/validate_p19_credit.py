#!/usr/bin/env python3
"""Reject P19 credit drift, financial-write bypasses, unsafe routes or false verification."""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
EXPECTED = {
    "REC-014": ("record/credit-payment/{transactionId?}", ["transactionId:StableId?"], {"editing", "overpaymentBlocked", "unallocated", "saving"}),
    "CRD-001": ("credit/{accountId}", ["accountId:StableId"], {"normal", "overdue", "positiveBalance", "noLimit", "noStatements"}),
    "CRD-002": ("credit/{accountId}/profile", ["accountId:StableId"], {"editing", "validationError"}),
    "CRD-003": ("credit/{accountId}/statements", ["accountId:StableId"], {"content", "empty"}),
    "CRD-004": ("credit/statement/{statementId}", ["statementId:StableId"], {"estimatedOnly", "official", "sealed", "overdue", "paid"}),
    "CRD-005": ("credit/statement/{statementId}/official", ["statementId:StableId"], {"editing", "difference", "saving"}),
    "CRD-006": ("credit/statement-assignment/{transactionId}", ["transactionId:StableId"], {"content", "sealedWarning"}),
    "CRD-007": ("credit/payment-allocation/{transactionId}", ["transactionId:StableId"], {"editing", "balanced", "mismatch"}),
    "CRD-008": ("credit/{accountId}/auto-payment", ["accountId:StableId"], {"eligible", "ineligible", "candidateMode"}),
}
TARGET_REQUIREMENTS = {"REQ-036", "REQ-037", "REQ-038", "REQ-039"}
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
        if screen.get("params") != params:
            errors.append(f"{screen_id} route parameters drift")
        if set(screen.get("requiredStates", [])) != states:
            errors.append(f"{screen_id} requiredStates drift")
    if sum(len(states) for _, _, states in EXPECTED.values()) != 29:
        errors.append("P19 required-state baseline must remain exactly 29")
    return errors


def validate_sources(sources: dict[str, str] | None = None) -> list[str]:
    sources = source_map() if sources is None else sources
    errors: list[str] = []
    relevant_names = {
        "CreditAccountingPolicy.kt", "CreditApplication.kt", "SecureRoomCreditApplicationPort.kt",
        "RoomCreditPlanWriter.kt", "CreditState.kt", "CreditScreens.kt", "CreditRootDestination.kt",
    }
    selected = {path: source for path, source in sources.items() if Path(path).name in relevant_names}
    missing = relevant_names - {Path(path).name for path in selected}
    if missing:
        errors.append(f"P19 production files missing: {sorted(missing)}")
    for path, source in selected.items():
        if PLACEHOLDER.search(source):
            errors.append(f"placeholder production implementation in {path}")

    feature = "\n".join(source for path, source in sources.items() if path.startswith("feature/liabilities/"))
    if FORBIDDEN_FEATURE_IMPORT.search(feature) or re.search(r"\b(?:Dao|Entity|execSQL|JournalEntry|Posting)\b", feature):
        errors.append("credit feature bypasses governed UI/application boundaries")
    if re.search(r"\b(?:MaterialTheme|Color\s*\(|SwipeToDismiss|detectHorizontalDragGestures)\b|\b\d+(?:\.\d+)?\.dp\b", feature):
        errors.append("credit feature bypasses design-system tokens or contains swipe deletion")
    require_tokens(errors, feature, "P19 governed UI", (
        "CreditDestination", "CreditPresentation", "OVERPAYMENT_BLOCKED", "POSITIVE_BALANCE", "CANDIDATE_MODE",
        "LedgerTestTags.CREDIT_PAYMENT", "credit_difference_no_adjustment", "credit_no_minimum_payment",
        "credit_bookkeeping_disclaimer", "LedgerToggleRow", "CreditAllocationMode.UNALLOCATED_ADVANCE",
    ))

    application = next((source for path, source in sources.items() if path.endswith("CreditApplication.kt")), "")
    require_tokens(errors, application, "typed credit application boundary", (
        "CreditSnapshot", "CreditAccountView", "CreditStatementView", "SaveCreditProfileRequest",
        "SaveCreditStatementRequest", "RecordCreditPaymentRequest", "AssignCreditStatementRequest",
        "ReallocateCreditPaymentRequest", "CreditAutoPaymentProposal", "CreditApplicationPort",
    ))
    domain = "\n".join(source for path, source in sources.items() if path.startswith("finance/domain/"))
    require_tokens(errors, domain, "credit domain", (
        "CreditCalendarPolicy", "CreditPaymentAllocationPolicy", "CreditAutoPaymentPolicy",
        "CreditStatementRevision", "CreditStatementStatus", "CreditPaymentPayload", "StatementEffect",
        "RecordCreditPaymentCommand", "AutoGenerationMode.CONFIRMATION_CANDIDATE",
        "officialAmountMinor == null || officialAmountMinor >= 0L",
    ))
    data = next((source for path, source in sources.items() if path.endswith("SecureRoomCreditApplicationPort.kt")), "")
    require_tokens(errors, data, "encrypted credit adapter", (
        "EncryptedDatabaseFactory.openPrimary", "DefaultFinancialMutationCoordinator", "CanonicalFinancialHash.command",
        "CreditPaymentAllocationPolicy.allocate", "authoritativeRemaining", "CreditAutoPaymentPolicy.evaluate",
        "expectedRevisionId", "proposeAutoPayment", "recordedByThisCommand",
    ))
    if re.search(r"\b(?:INSERT|UPDATE|DELETE)\s+(?:INTO\s+|FROM\s+)?(?:journal_entry|posting|economic_effect|statement_effect)", data, re.IGNORECASE):
        errors.append("credit application adapter directly writes financial facts")
    writer = next((source for path, source in sources.items() if path.endswith("RoomFinancialPlanWriter.kt")), "")
    require_tokens(errors, writer, "coordinated credit fact writer", ("RoomCreditPlanWriter", "creditPlanWriter.write", "insertEffects"))
    root = next((source for path, source in sources.items() if path.endswith("CreditRootDestination.kt")), "")
    require_tokens(errors, root, "safe credit root", (
        'encodedArguments.stableId("accountId")', 'encodedArguments.stableId("statementId")',
        'encodedArguments.stableId("transactionId")', "creditFixedAction",
    ))
    if re.search(r'encodedArguments\["(?:amount|note|name|card|attachment|location|currency|balance|official)', root, re.IGNORECASE):
        errors.append("credit route carries sensitive or mutable business data")
    app_sources = "\n".join(source for path, source in sources.items() if path.startswith("app/src/main/kotlin/"))
    if re.search(r"\b(?:CreditDao|CreditStatementEntity|RoomCreditPlanWriter)\b", app_sources):
        errors.append("app UI obtains credit DAO/entity or fact writer")
    return errors


def validate_tests_and_resources() -> list[str]:
    tests = "\n".join(
        path.read_text(encoding="utf-8")
        for root in ("feature/liabilities/src/androidTest", "finance/data/src/androidTest", "finance/domain/src/test")
        for path in sorted((ROOT / root).rglob("*.kt"))
    )
    errors: list[str] = []
    require_tokens(errors, tests, "P19 automated evidence", (
        "allTwentyNineFrozenRequiredStatesRenderAcrossResponsiveAccessibleLocalizedMatrix",
        "activeOverpaymentCandidateFallbackAndPositiveBalanceAreExplicitAtCompactLargeFont",
        "creditAccountAndOfficialDifferenceGoldensMatchEveryPixel",
        "calendar resolves short months skipped dates time zone and adjusted due day deterministically",
        "earliest payment allocates across statements and every active overpayment is rejected",
        "auto payment formal mode requires all five eligibility facts and candidate mode writes no facts",
        "officialEstimatedStatementsPaymentsIdempotencyAndProjectionsRemainAtomicAndRebuildable",
        "PRAGMA integrity_check", "pragma_foreign_key_check",
    ))
    golden = next((path.read_text(encoding="utf-8") for path in (ROOT / "feature/liabilities/src/androidTest").rglob("P19GoldenDeviceTest.kt")), "")
    hashes = re.findall(r'"([0-9a-f]{64})"', golden)
    if len(hashes) != 2 or len(set(hashes)) != 2:
        errors.append("P19 requires two distinct exact-pixel SHA-256 Compose goldens")
    resource_sets = []
    combined_resources = []
    for relative in ("values/strings.xml", "values-en/strings.xml", "values-ja/strings.xml"):
        content = read(f"feature/liabilities/src/main/res/{relative}")
        combined_resources.append(content)
        resource_sets.append({key for key in re.findall(r'<string name="([^"]+)"', content) if key.startswith("credit_")})
    if not resource_sets[0] or resource_sets[0] != resource_sets[1] or resource_sets[0] != resource_sets[2]:
        errors.append("P19 credit strings are incomplete across zh-CN/en/ja")
    for marker, content in zip(("不会发起", "does not initiate", "実行せず"), combined_resources, strict=True):
        if marker not in content:
            errors.append("P19 UI must explicitly say it does not initiate an external bank payment")
    return errors


def validate_ledgers() -> list[str]:
    errors: list[str] = []
    state = read("docs/初始开发文件存档/implementation/PROJECT_STATE.md")
    evidence = read("docs/初始开发文件存档/implementation/TEST_EVIDENCE.md")
    mapping_path = ROOT / "docs/初始开发文件存档/implementation/P19_CREDIT_MAPPING.md"
    mapping = mapping_path.read_text(encoding="utf-8") if mapping_path.is_file() else ""
    require_tokens(errors, state, "PROJECT_STATE", ("Current stage: P36", "| P19 | VERIFIED |"))
    for index in range(1, 8):
        if f"P19-E{index:03d}" not in evidence:
            errors.append(f"TEST_EVIDENCE missing P19-E{index:03d}")
    require_tokens(errors, mapping, "P19 mapping", (
        "29 required states", "FinancialMutationCoordinator", "active overpayment", "candidate", "P19 is `VERIFIED`",
    ))
    with (ROOT / "docs/初始开发文件存档/implementation/SCREEN_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        screens = {row["screen_id"]: row for row in csv.DictReader(handle)}
    for screen_id in EXPECTED:
        row = screens.get(screen_id, {})
        if row.get("status") != "VERIFIED" or "P19" not in row.get("implementation_evidence", "") or "P19-E" not in row.get("verification_evidence", ""):
            errors.append(f"{screen_id} must carry VERIFIED P19 implementation and test evidence")
    with (ROOT / "docs/初始开发文件存档/implementation/REQUIREMENT_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        requirements = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    for requirement_id in TARGET_REQUIREMENTS:
        row = requirements.get(requirement_id, {})
        if row.get("status") != "VERIFIED" or "P19" not in row.get("implementation_evidence", "") or "P19-E" not in row.get("verification_evidence", ""):
            errors.append(f"{requirement_id} must carry VERIFIED P19 implementation and test evidence")
    return errors


def main() -> int:
    errors = validate_contract() + validate_sources() + validate_tests_and_resources() + validate_ledgers()
    if errors:
        print("P19 credit validation: FAIL", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("P19 credit validation: PASS")
    print("screens=9 required_states=29 goldens=2 financial_entry=FinancialMutationCoordinator visual_inputs=contract_token_yaml_only")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
