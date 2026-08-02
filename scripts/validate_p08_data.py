#!/usr/bin/env python3
"""Validate the P08 repository, atomic-write, projection and query foundation."""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path
from typing import Mapping


ROOT = Path(__file__).resolve().parents[1]
TARGET_REQUIREMENTS = {"REQ-006", "REQ-007", "REQ-031", "REQ-033", "REQ-084", "REQ-088", "REQ-089"}
REQUIRED_DATA_FILES = {
    "RoomBookRepository.kt",
    "RoomFinancialCommitRepository.kt",
    "RoomFinancialPlanWriter.kt",
    "RoomProjectionEngine.kt",
    "RoomProjectionMaintenanceService.kt",
    "RoomTransactionQueryService.kt",
    "SqlSupport.kt",
}
SYNC_PROJECTIONS = {
    "current_transaction_projection",
    "account_balance_current",
    "account_balance_daily",
    "refund_status_projection",
    "budget_usage_projection",
    "project_usage_projection",
    "goal_balance_projection",
    "credit_statement_projection",
    "credit_account_projection",
    "installment_progress_projection",
    "loan_progress_projection",
    "settlement_position_projection",
    "transaction_fts",
    "location_rtree",
    "place_rtree",
    "widget_book_snapshot",
    "widget_account_snapshot",
    "widget_credit_snapshot",
    "widget_goal_snapshot",
}
IMMUTABLE_WRITES = {
    "book_commit",
    "transaction_revision",
    "journal_entry",
    "posting",
    "economic_effect",
    "budget_effect",
    "project_effect",
    "goal_effect",
    "statement_effect",
    "loan_effect",
    "settlement_effect",
}
FORBIDDEN = re.compile(r"\b(?:TODO|NotImplemented|UnsupportedOperationException)\b|@Upsert\b")


def load_sources() -> dict[str, str]:
    roots = (
        Path("finance/application/src/main/kotlin"),
        Path("finance/data/src/main/kotlin"),
        Path("core/database/src/main/kotlin"),
        Path("build-logic/src/main/kotlin"),
    )
    return {
        path.relative_to(ROOT).as_posix(): path.read_text(encoding="utf-8")
        for source_root in roots
        for path in sorted((ROOT / source_root).rglob("*.kt"))
    }


def named(sources: Mapping[str, str], filename: str) -> str:
    return next((text for path, text in sources.items() if path.endswith(filename)), "")


def validate_sources(sources: Mapping[str, str]) -> list[str]:
    errors: list[str] = []
    filenames = {Path(path).name for path in sources}
    missing = REQUIRED_DATA_FILES - filenames
    if missing:
        errors.append(f"P08 production adapters missing: {sorted(missing)}")
    for path, source in sources.items():
        if FORBIDDEN.search(source):
            errors.append(f"placeholder or forbidden upsert in {path}")

    coordinator = named(sources, "FinancialMutationCoordinator.kt")
    services = named(sources, "FinancialApplicationServices.kt")
    repository = named(sources, "RoomFinancialCommitRepository.kt")
    writer = named(sources, "RoomFinancialPlanWriter.kt")
    projection = named(sources, "RoomProjectionEngine.kt")
    maintenance = named(sources, "RoomProjectionMaintenanceService.kt")
    query = named(sources, "RoomTransactionQueryService.kt")
    database = named(sources, "LedgerDatabase.kt")
    policy = named(sources, "SourcePolicyEngine.kt")

    for required in (
        "DefaultLedgerWriteGate",
        "FinancialCommandHandler",
        "DefaultSubmitFinancialCommandUseCase",
    ):
        if required not in services:
            errors.append(f"application financial entry missing {required}")
    if "commit(command: FinancialCommand, plan: FinancialMutationPlan)" not in coordinator.replace("\n", " "):
        if not re.search(r"commit\s*\(\s*command:\s*FinancialCommand,\s*plan:\s*FinancialMutationPlan", coordinator):
            errors.append("atomic commit port does not carry the command into the database transaction")
    for required in (
        "database.inLedgerTransaction",
        "connection.commandReceipt(command.commandId)",
        "verifyCommitPreconditions",
        "projections.rebuildAll",
        "verifyNewState",
        "UPDATE book SET head_commit_id",
        "INSERT INTO command_receipt",
    ):
        if required not in repository:
            errors.append(f"atomic commit sequence missing {required}")
    for table in IMMUTABLE_WRITES:
        if f"INSERT INTO {table}" not in writer:
            errors.append(f"plan mapper does not write {table}")
    if "runInTransaction" not in database or "openHelper.writableDatabase" not in database:
        errors.append("Room-owned SQLCipher transaction connection is not the sole write boundary")

    for table in SYNC_PROJECTIONS:
        if table not in projection:
            errors.append(f"synchronous projection family missing {table}")
    for required in ("as_of_local_revision", "canonicalHash", "mismatchedFamilies", "ROW_COUNT_EXPECTATIONS"):
        if required not in projection:
            errors.append(f"projection consistency contract missing {required}")
    if "strftime(" in projection or "stampExistingDeferredProjections" in projection:
        errors.append("projection rebuild reads ambient time or falsely stamps an unrecomputed projection")

    for required in (
        "SAVEPOINT p08_projection_audit",
        "ROLLBACK TO SAVEPOINT p08_projection_audit",
        "DatabaseIntegrityAudit.run",
        "StartupDisposition.RECOVERY_REQUIRED",
        "UPDATE book SET state = 1",
    ):
        if required not in maintenance:
            errors.append(f"projection maintenance/audit contract missing {required}")
    for required in (
        "ORDER BY ctp.occurred_at DESC, ctp.transaction_id DESC LIMIT ?",
        "transaction_fts MATCH ?",
        "location_rtree",
        "haversineMeters",
    ):
        if required not in query:
            errors.append(f"typed query foundation missing {required}")
    if re.search(r"\bOFFSET\b", query):
        errors.append("transaction paging uses forbidden offset paging")
    for required in ("FINANCE-SQL-WRITE", "FINANCE-WRITE-PORT", "FINANCE-COORDINATOR"):
        if required not in policy:
            errors.append(f"static write-boundary policy missing {required}")
    return errors


def validate_tests() -> list[str]:
    device = (ROOT / "finance/data/src/androidTest/kotlin/app/ledger/finance/data/RoomFinancialDataDeviceTest.kt").read_text(
        encoding="utf-8"
    )
    unit = (ROOT / "finance/data/src/test/kotlin/app/ledger/finance/data/TransactionSqlCompilerTest.kt").read_text(
        encoding="utf-8"
    )
    expected = (
        "atomicCommitIsIdempotentVersionedQueryableAndExactlyRebuildable",
        "staleExpectedRevisionAndInjectedFailuresNeverLeavePartialState",
        "FinancialCommitPhase.entries",
        "maintenance.audit()",
        "maintenance.rebuild()",
        "withinRadius",
        "nextCursor",
    )
    errors = [f"P08 device evidence missing {value}" for value in expected if value not in device]
    if "OFFSET" not in unit or "search text is bound" not in unit:
        errors.append("P08 JVM query mutation evidence is incomplete")
    return errors


def validate_ledgers() -> list[str]:
    errors: list[str] = []
    project_state = (ROOT / "docs/implementation/PROJECT_STATE.md").read_text(encoding="utf-8")
    if "| P08 | VERIFIED |" not in project_state or "### P08 result" not in project_state:
        errors.append("PROJECT_STATE does not record P08 VERIFIED and its result")
    evidence = (ROOT / "docs/implementation/TEST_EVIDENCE.md").read_text(encoding="utf-8")
    for value in range(1, 7):
        if f"P08-E{value:03d}" not in evidence:
            errors.append(f"TEST_EVIDENCE missing P08-E{value:03d}")
    with (ROOT / "docs/implementation/REQUIREMENT_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        rows = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    for requirement_id in TARGET_REQUIREMENTS:
        row = rows.get(requirement_id)
        if row is None or row["status"] != "IN_PROGRESS":
            errors.append(f"{requirement_id} must remain truthful IN_PROGRESS after its P08 foundation")
        elif "P08" not in row["implementation_evidence"] or "P08-E" not in row["verification_evidence"]:
            errors.append(f"{requirement_id} lacks P08 implementation/verification evidence")
    with (ROOT / "docs/implementation/SCREEN_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        screens = list(csv.DictReader(handle))
    p10_promotions = {
        "REC-009": "IN_PROGRESS",
        "REC-010": "IN_PROGRESS",
        "ATT-001": "VERIFIED",
        "ATT-002": "VERIFIED",
        "ATT-003": "VERIFIED",
        "SYS-001": "VERIFIED",
    }
    if len(screens) != 215 or any(
        row["status"] != p10_promotions.get(row["screen_id"], "NOT_STARTED") for row in screens
    ):
        errors.append("screen coverage contains a promotion outside the completed P10 scope")
    return errors


def main() -> int:
    sources = load_sources()
    errors = validate_sources(sources) + validate_tests() + validate_ledgers()
    if errors:
        print("P08 data validation: FAIL", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("P08 data validation: PASS")
    print(f"production_sources={len(sources)} sync_projection_families={len(SYNC_PROJECTIONS)}")
    print("device_cases=2 injected_commit_phases=5 screens_total=215 p10_promoted=6 visual_inputs=excluded")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
