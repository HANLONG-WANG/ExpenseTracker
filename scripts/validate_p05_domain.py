#!/usr/bin/env python3
"""Validate P05 domain/application contracts without reading visual review artifacts."""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path
from typing import Mapping


ROOT = Path(__file__).resolve().parents[1]
SOURCE_ROOTS = (
    Path("finance/domain/src/main/kotlin"),
    Path("finance/application/src/main/kotlin"),
    Path("analytics/domain/src/main/kotlin"),
    Path("transfer/domain/src/main/kotlin"),
)
TARGET_REQUIREMENTS = {
    *(f"REQ-{value:03d}" for value in range(6, 16)),
    *(f"REQ-{value:03d}" for value in range(29, 46)),
    *(f"REQ-{value:03d}" for value in range(48, 60)),
    "REQ-088",
    "REQ-089",
}
REQUIRED_FINANCE_TYPES = {
    "Book",
    "BookCommit",
    "CommandReceipt",
    "EntityChange",
    "EntityRevision",
    "PurgeTombstone",
    "LedgerAccount",
    "UserAccount",
    "PaymentCard",
    "CardVaultSecret",
    "Category",
    "Merchant",
    "Place",
    "LocationRecord",
    "BusinessTransaction",
    "TransactionRevision",
    "RevisionAmount",
    "FxRateSnapshot",
    "JournalEntry",
    "Posting",
    "EconomicEffect",
    "BudgetEffect",
    "ProjectEffect",
    "GoalEffect",
    "StatementEffect",
    "LoanEffect",
    "SettlementEffect",
    "RefundAllocation",
    "BudgetTemplate",
    "BudgetTemplateRevision",
    "BudgetMonth",
    "BudgetMonthRevision",
    "BudgetAdjustment",
    "BudgetRollover",
    "Project",
    "Goal",
    "GoalMovement",
    "CreditAccountProfile",
    "CreditStatement",
    "CreditStatementRevision",
    "InstallmentPlan",
    "InstallmentPlanRevision",
    "InstallmentScheduleRevision",
    "InstallmentScheduleItem",
    "LoanContract",
    "LoanTranche",
    "LoanTermsRevision",
    "LoanRatePeriod",
    "LoanScheduleRevision",
    "LoanScheduleItem",
    "LoanActualAllocation",
    "LoanSimulation",
    "Participant",
    "SettlementActivity",
    "SettlementShare",
    "SettlementPaymentRecord",
    "TransactionBlueprint",
    "TransactionBlueprintRevision",
    "RecurrenceSeries",
    "RecurrenceSeriesRevision",
    "RecurrenceOccurrence",
    "RecurrenceCandidate",
    "EncryptedBlob",
    "Attachment",
    "FinancialMutationPlan",
    "PlanningSnapshot",
    "CurrentTransactionProjection",
    "AccountValuationProjection",
    "AccountBalanceDailyProjection",
    "BudgetUsageProjection",
    "BudgetFutureReservationProjection",
    "ProjectUsageProjection",
    "InstallmentProgressProjection",
    "LoanFutureCashflowProjection",
    "TransactionFilter",
    "WidgetBookSnapshot",
    "WidgetAccountSnapshot",
    "WidgetCreditSnapshot",
    "WidgetGoalSnapshot",
}
REQUIRED_APPLICATION_PORTS = {
    "FinancialMutationCoordinator",
    "LedgerWriteGate",
    "FinancialPlanningPort",
    "FinancialPlanningSnapshotRepository",
    "CommandReceiptRepository",
    "AtomicFinancialCommitRepository",
    "BookRepository",
    "AccountRepository",
    "ClassificationRepository",
    "TransactionQueryRepository",
    "PlanningRepository",
    "LiabilityRepository",
    "SettlementRepository",
    "AutomationRepository",
    "AttachmentRepository",
    "FxEvidencePort",
    "ForegroundLocationPort",
    "AttachmentObjectPort",
    "LocalSuggestionPort",
}
REQUIRED_ANALYTICS_TYPES = {
    "ReportSpec",
    "ReportDefinition",
    "ReportDefinitionRevision",
    "Dashboard",
    "ReportQueryPlan",
    "VersionedReportCache",
    "AnomalyRule",
    "AnomalyFinding",
    "ForecastRequest",
    "ForecastResult",
    "AnalyticsQueryPort",
    "DeterministicAnalyticsEngine",
}
REQUIRED_TRANSFER_TYPES = {
    "BackgroundOperation",
    "OperationCheckpoint",
    "OperationLaunchToken",
    "ImportRecord",
    "ImportBatchCommit",
    "ImportSourceReference",
    "StagingRawRow",
    "StagingParsedRow",
    "StagingMapping",
    "StagingValidationError",
    "StagingDuplicateCandidate",
    "StagingPreparedCommand",
    "StagingAttachment",
    "BackupRepository",
    "BackupSnapshot",
    "BackupObject",
    "BackupSnapshotObject",
    "DriveUploadSession",
    "RestoreRecord",
    "MergeSession",
    "MergeConflict",
    "MergeResolutionRecord",
    "BackgroundOperationRepository",
    "EncryptedStagingRepository",
    "ShadowLedgerRepository",
    "AtomicLedgerExchangePort",
    "BackupObjectRepositoryPort",
    "RemoteBackupPort",
}
EXPECTED_TRANSACTION_PAYLOADS = {
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
EXPECTED_LIFECYCLES = {"Current", "Revision", "Fact", "Projection", "Cache", "Operation"}
FORBIDDEN_IMPORT = re.compile(
    r"(?m)^import\s+(?:androidx?(?:\.|$)|dagger(?:\.|$)|com\.google\.dagger(?:\.|$)|"
    r"okhttp3(?:\.|$)|retrofit2(?:\.|$)|app\.ledger\.core\.network(?:\.|$))"
)
FORBIDDEN_GENERIC = re.compile(
    r"\b(?:Map|MutableMap)\s*<\s*String\s*,\s*(?:Any|Any\?)\s*>|"
    r"\b(?:JsonObject|JsonElement|JSONObject)\b"
)


def declarations(source: str) -> set[str]:
    return set(
        re.findall(
            r"(?m)^(?:@JvmInline\s+)?(?:data\s+|value\s+|sealed\s+|fun\s+)?(?:class|interface)\s+([A-Za-z][A-Za-z0-9_]*)",
            source,
        )
    )


def enum_values(source: str, name: str) -> set[str]:
    match = re.search(rf"enum\s+class\s+{re.escape(name)}\s*\{{(.*?)\n\}}", source, re.DOTALL)
    if match is None:
        return set()
    return set(re.findall(r"(?m)^\s{4}([A-Z][A-Z0-9_]*)\s*(?:,|$)", match.group(1)))


def validate_source_contract(sources: Mapping[str, str]) -> list[str]:
    errors: list[str] = []
    finance = "\n".join(text for path, text in sources.items() if path.startswith("finance/domain/"))
    application = "\n".join(text for path, text in sources.items() if path.startswith("finance/application/"))
    analytics = "\n".join(text for path, text in sources.items() if path.startswith("analytics/domain/"))
    transfer = "\n".join(text for path, text in sources.items() if path.startswith("transfer/domain/"))

    for label, source, required in (
        ("finance", finance, REQUIRED_FINANCE_TYPES),
        ("application", application, REQUIRED_APPLICATION_PORTS),
        ("analytics", analytics, REQUIRED_ANALYTICS_TYPES),
        ("transfer", transfer, REQUIRED_TRANSFER_TYPES),
    ):
        missing = required - declarations(source)
        if missing:
            errors.append(f"{label} declarations missing: {sorted(missing)}")

    payloads = {
        name for name in declarations(finance) if name.endswith("Payload") and name != "TransactionPayload"
    }
    if payloads != EXPECTED_TRANSACTION_PAYLOADS:
        errors.append(f"transaction payload closure differs: {sorted(payloads)}")
    expected_kinds = {
        "EXPENSE",
        "INCOME",
        "TRANSFER",
        "REFUND",
        "CREDIT_PAYMENT",
        "LOAN_DISBURSEMENT",
        "LOAN_PAYMENT",
        "BALANCE_ADJUSTMENT",
        "FX_EXCHANGE",
        "SETTLEMENT_PAYMENT",
        "OPENING_BALANCE",
    }
    if enum_values(finance, "TransactionKind") != expected_kinds:
        errors.append("TransactionKind does not match the frozen 11-kind set")
    if enum_values(finance, "RecordLifecycle"):
        errors.append("RecordLifecycle must be sealed typed markers, not an open enum")
    lifecycle_objects = set(re.findall(r"data\s+object\s+(\w+)\s*:\s*RecordLifecycle", finance))
    if lifecycle_objects != EXPECTED_LIFECYCLES:
        errors.append(f"record lifecycles differ: {sorted(lifecycle_objects)}")

    expense = re.search(r"data class ExpensePayload\((.*?)\n\) : TransactionPayload", finance, re.DOTALL)
    income = re.search(r"data class IncomePayload\((.*?)\n\) : TransactionPayload", finance, re.DOTALL)
    context = re.search(r"data class TransactionContextInput\((.*?)\n\)", finance, re.DOTALL)
    if expense is None or "override val classification: CategoryAssignment," not in expense.group(1):
        errors.append("ExpensePayload must require one non-null CategoryAssignment")
    if income is None or "override val classification: CategoryAssignment," not in income.group(1):
        errors.append("IncomePayload must require one non-null CategoryAssignment")
    if context is None or context.group(1).count("projectId: ProjectId?") != 1:
        errors.append("transaction context must expose exactly one optional project")
    if context is None or context.group(1).count("goalId: GoalId?") != 1:
        errors.append("transaction context must expose exactly one optional goal")
    if "data class AccountAmount private constructor(" not in finance:
        errors.append("AccountAmount must have a private currency-checked constructor")
    if "NewTransactionInput<ExpensePayload>" not in finance or "NewTransactionInput<IncomePayload>" not in finance:
        errors.append("ordinary commands must accept only their closed typed payload")
    if "FinancialMutationPlanValidator.validate" not in application:
        errors.append("application coordinator must invoke the domain plan validator")
    if "commitRepository.commit(command, validated.value)" not in application:
        errors.append("coordinator must commit only the validated immutable plan")

    for path, source in sources.items():
        if FORBIDDEN_IMPORT.search(source):
            errors.append(f"forbidden framework import in {path}")
        if FORBIDDEN_GENERIC.search(source):
            errors.append(f"generic JSON/property bag in {path}")
        if re.search(r"\b(?:TODO|NotImplemented|UnsupportedOperationException)\b", source):
            errors.append(f"production placeholder in {path}")
    if len(enum_values(analytics, "FixedReport")) != 20:
        errors.append("analytics FixedReport must contain all 20 frozen reports")
    if enum_values(transfer, "BackgroundOperationState") != {
        "QUEUED",
        "PREPARING",
        "RUNNING",
        "PAUSED",
        "CANCEL_REQUESTED",
        "FAILED_RETRYABLE",
        "FAILED_FINAL",
        "COMMITTING",
        "ROLLING_BACK",
        "SUCCEEDED",
    }:
        errors.append("background operation state set differs")
    if "@JvmInline value class OperationLaunchToken(val operationId: BackgroundOperationId)" not in transfer:
        errors.append("worker launch token must contain only BackgroundOperationId")
    return errors


def validate_requirement_rows(rows: list[dict[str, str]]) -> list[str]:
    by_id = {row["requirement_id"]: row for row in rows}
    errors: list[str] = []
    if set(by_id) != {f"REQ-{value:03d}" for value in range(1, 91)}:
        errors.append("requirement ledger does not contain exact REQ-001..090")
    for requirement_id in TARGET_REQUIREMENTS:
        row = by_id.get(requirement_id)
        if row is None or row["status"] not in {"IN_PROGRESS", "VERIFIED"} or "P05" not in row["implementation_evidence"]:
            errors.append(f"{requirement_id} lacks truthful P05 foundation tracking")
    return errors


def load_sources() -> dict[str, str]:
    result: dict[str, str] = {}
    for source_root in SOURCE_ROOTS:
        for path in sorted((ROOT / source_root).rglob("*.kt")):
            relative = path.relative_to(ROOT).as_posix()
            result[relative] = path.read_text(encoding="utf-8")
    return result


def main() -> int:
    errors = validate_source_contract(load_sources())
    with (ROOT / "docs/implementation/REQUIREMENT_COVERAGE.csv").open(
        encoding="utf-8", newline=""
    ) as handle:
        errors.extend(validate_requirement_rows(list(csv.DictReader(handle))))
    screen_rows = list(
        csv.DictReader(
            (ROOT / "docs/implementation/SCREEN_COVERAGE.csv").open(encoding="utf-8", newline="")
        )
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
    if len(screen_rows) != 215 or any(
        row["status"] != p11_promotions.get(row["screen_id"], "NOT_STARTED") for row in screen_rows
    ):
        errors.append("screen coverage contains a promotion outside the completed P11 scope")
    mapping = (ROOT / "docs/implementation/P05_DOMAIN_API_MAPPING.md").read_text(encoding="utf-8")
    mapping_ids = set(re.findall(r"P05-DOM-(\d{2})", mapping))
    if mapping_ids != {f"{value:02d}" for value in range(1, 36)}:
        errors.append("P05 domain mapping must contain exact chapter rows 01..35")
    project_state = (ROOT / "docs/implementation/PROJECT_STATE.md").read_text(encoding="utf-8")
    current_stage = re.search(r"Current stage: P(\d{2})", project_state)
    if (
        current_stage is None
        or not 5 <= int(current_stage.group(1)) <= 36
        or "| P05 | VERIFIED |" not in project_state
        or "### P05 result" not in project_state
    ):
        errors.append("PROJECT_STATE does not retain P05 VERIFIED in the cumulative stage ledger")
    test_evidence = (ROOT / "docs/implementation/TEST_EVIDENCE.md").read_text(encoding="utf-8")
    if any(f"P05-E{value:03d}" not in test_evidence for value in range(1, 7)):
        errors.append("TEST_EVIDENCE does not contain the exact P05-E001..P05-E006 set")

    if errors:
        print("P05 domain validation: FAIL", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    sources = load_sources()
    print("P05 domain validation: PASS")
    print(f"production_files={len(sources)}")
    print(f"finance_types={len(declarations(chr(10).join(v for k, v in sources.items() if k.startswith('finance/domain/'))))}")
    print(f"application_ports={len(REQUIRED_APPLICATION_PORTS)}")
    print(f"transaction_payloads={len(EXPECTED_TRANSACTION_PAYLOADS)} lifecycles={len(EXPECTED_LIFECYCLES)}")
    print(f"tracked_requirements={len(TARGET_REQUIREMENTS)} screens_total={len(screen_rows)} p11_promoted=24")
    print("visual_inputs=excluded_by_explicit_source roots")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
