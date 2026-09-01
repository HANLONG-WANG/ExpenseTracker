#!/usr/bin/env python3
"""Reject P23 recurrence, candidate, Worker, route, UI or ledger drift."""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
EXPECTED = {
    "REC-026": ("record/template-picker", [], {"content", "empty"}),
    "AUT-001": ("automation", [], {"content"}),
    "AUT-002": ("templates", [], {"content", "empty"}),
    "AUT-003": ("templates/editor/{templateId?}", ["templateId:StableId?"], {"create", "edit", "validationError"}),
    "AUT-004": ("recurrences", [], {"content", "empty", "paused"}),
    "AUT-005": ("recurrences/editor/{seriesId?}", ["seriesId:StableId?"], {"create", "edit", "invalid"}),
    "AUT-006": ("recurrences/rule/{seriesId?}", ["seriesId:StableId?"], {"editing", "invalid"}),
    "AUT-007": ("recurrences/preview/{seriesId?}", ["seriesId:StableId?"], {"content", "empty"}),
    "AUT-008": ("candidates", [], {"content", "empty", "selection"}),
    "AUT-009": ("candidates/{candidateId}", ["candidateId:StableId"], {"editing", "validationError", "invalidSource"}),
    "AUT-010": ("recurrences/{seriesId}/scope", ["seriesId:StableId"], {"content"}),
}
PLACEHOLDER = re.compile(r"\b(?:TODO|NotImplemented|UnsupportedOperationException)\b")


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def source_map() -> dict[str, str]:
    roots = ("app/src/main/kotlin", "core/designsystem/src/main/kotlin", "feature/automation/src/main/kotlin", "feature/record/src/main/kotlin", "finance/application/src/main/kotlin", "finance/data/src/main/kotlin", "finance/domain/src/main/kotlin")
    return {path.relative_to(ROOT).as_posix(): path.read_text(encoding="utf-8") for root in roots for path in sorted((ROOT / root).rglob("*.kt"))}


def require_tokens(errors: list[str], text: str, label: str, tokens: tuple[str, ...]) -> None:
    for token in tokens:
        if token not in text:
            errors.append(f"{label} missing {token}")


def validate_contract() -> list[str]:
    screens = {item["id"]: item for item in yaml.safe_load(read("docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml"))["screens"]}
    errors: list[str] = []
    for screen_id, (route, params, states) in EXPECTED.items():
        actual = screens.get(screen_id, {})
        if actual.get("route") != route or actual.get("params", []) != params or set(actual.get("requiredStates", [])) != states:
            errors.append(f"{screen_id} route/params/requiredStates drift")
        expected_module = ":feature:record" if screen_id == "REC-026" else ":feature:automation"
        if actual.get("module") != expected_module:
            errors.append(f"{screen_id} module drift")
    if sum(len(item[2]) for item in EXPECTED.values()) != 25:
        errors.append("P23 required-state baseline must remain exactly 25")
    return errors


def validate_sources(sources: dict[str, str] | None = None) -> list[str]:
    sources = source_map() if sources is None else sources
    errors: list[str] = []
    required_files = {
        "RecurrenceEngine.kt", "AutomationApplication.kt", "SecureRoomAutomationApplicationPort.kt", "RecurrenceCatchUpWorker.kt",
        "AppHeadlessRecurrenceExecutor.kt",
        "AppFormalOccurrenceGenerator.kt", "AutomationState.kt", "AutomationScreens.kt", "AutomationRootDestination.kt",
    }
    selected = {path: source for path, source in sources.items() if Path(path).name in required_files}
    missing = required_files - {Path(path).name for path in selected}
    if missing:
        errors.append(f"P23 production files missing: {sorted(missing)}")
    for path, source in selected.items():
        if PLACEHOLDER.search(source):
            errors.append(f"placeholder in {path}")

    domain = next((value for path, value in sources.items() if path.endswith("RecurrenceEngine.kt")), "")
    require_tokens(errors, domain, "deterministic recurrence engine", tuple(item for item in ("DAILY", "BUSINESS_DAYS", "WEEKLY", "MONTHLY_DAY", "MONTHLY_LAST_DAY", "MONTHLY_NTH_WEEKDAY", "MONTH_INTERVAL", "YEARLY", "CUSTOM_INTERVAL", "existingInstants", "overrideInstant")))
    if re.search(r"\b(?:Android|Room|WorkManager|Float|Double)\b", domain):
        errors.append("recurrence domain is not pure integer/time Kotlin")

    application = next((value for path, value in sources.items() if path.endswith("AutomationApplication.kt")), "")
    require_tokens(errors, application, "typed automation port", ("BlueprintDraft", "RecurrenceSeriesDraft", "ModifyOccurrenceRequest", "catchUp", "confirmCandidate", "completeCandidate", "skipCandidate"))
    if re.search(r"\b(?:attachmentIds|currentLocation|liveFx|actualTime)\b", re.search(r"data class BlueprintDraft\((.*?)\n\)", application, re.DOTALL).group(1) if "data class BlueprintDraft" in application else ""):
        errors.append("blueprint API carries forbidden runtime fields")

    data = next((value for path, value in sources.items() if path.endswith("SecureRoomAutomationApplicationPort.kt")), "")
    require_tokens(errors, data, "encrypted occurrence adapter", ("EncryptedDatabaseFactory.openPrimary", "RecurrenceEngine.next", "deriveStableId", "recurrence_occurrence", "recurrence_candidate", "RecurrenceGenerationMode.CANDIDATE", "createCandidate"))
    if re.search(r"INSERT\s+INTO\s+(?:journal_entry|posting|economic_effect|budget_effect)", data, re.IGNORECASE):
        errors.append("candidate/occurrence adapter directly writes financial facts")

    worker = next((value for path, value in sources.items() if path.endswith("RecurrenceCatchUpWorker.kt")), "")
    require_tokens(errors, worker, "WorkManager catch-up", ("enqueueUniqueWork", "enqueueUniquePeriodicWork", "ExistingWorkPolicy.KEEP", "ExistingPeriodicWorkPolicy.KEEP", 'setOf(INPUT_OPERATION_ID)', 'const val INPUT_OPERATION_ID = "operationId"'))
    if re.search(r"put(?:String|Long|Int)\(\s*\"(?!operationId)", worker):
        errors.append("Worker input contains more than opaque operationId")
    if "AlarmManager" in worker or "setExact" in worker:
        errors.append("P23 illegally uses exact alarms")
    require_tokens(errors, worker, "restricted Worker entry", ("headlessExecutor().catchUp", "fun headlessExecutor(): HeadlessRecurrenceExecutor"))
    if "fun automation(): AutomationApplicationPort" in worker:
        errors.append("Worker can obtain the automation port without a headless lease")

    headless = next((value for path, value in sources.items() if path.endswith("AppHeadlessRecurrenceExecutor.kt")), "")
    require_tokens(errors, headless, "headless recurrence lease", ("BookSessionManager", "HeadlessBookLease", "acquireHeadlessLease", "HeadlessLeaseCapability.RECURRENCE_WRITE", "lease?.release()", "manager.close()"))

    formal = next((value for path, value in sources.items() if path.endswith("AppFormalOccurrenceGenerator.kt")), "")
    require_tokens(errors, formal, "formal integration", ("OrdinaryTransactionEntryPort", "CreditApplicationPort", "LoanApplicationPort", "TransactionSource.RECURRENCE_AUTO", "sourceOccurrenceId"))
    root = next((value for path, value in sources.items() if path.endswith("AutomationRootDestination.kt")), "")
    require_tokens(errors, root, "safe automation routes", ('stableId("templateId")', 'stableId("seriesId")', 'stableId("candidateId")', "StableId.parse"))
    if re.search(r'stableId\("(?:amount|note|name|card|attachment|location|currency)', root, re.IGNORECASE):
        errors.append("automation route carries sensitive business data")

    feature = "\n".join(value for path, value in sources.items() if path.startswith("feature/automation/"))
    if re.search(r"^import\s+(?:androidx\.room|androidx\.compose\.material3|app\.ledger\.(?:finance\.data|core\.(?:database|security)))", feature, re.MULTILINE):
        errors.append("automation feature bypasses UI/application boundaries")
    if re.search(r"\b(?:MaterialTheme|Color\s*\(|SwipeToDismiss|detectHorizontalDragGestures)\b|\b\d+(?:\.\d+)?\.dp\b", feature):
        errors.append("automation feature bypasses design tokens or adds swipe delete")
    require_tokens(errors, feature, "governed automation UI", ("AutomationDestination", "CandidateNotPostedBanner" if "CandidateNotPostedBanner" in feature else "automation_candidate_not_posted", "LedgerTestTags.AUTOMATION_HUB", "RecurrenceModificationScope.entries"))
    record = "\n".join(value for path, value in sources.items() if path.startswith("feature/record/"))
    require_tokens(errors, record, "REC-026 record-module UI", ('"REC-026" -> QuickTemplatePicker', "LedgerTestTags.AUTOMATION_TEMPLATE_PICKER"))
    if '"REC-026" ->' in feature:
        errors.append("REC-026 is duplicated outside its frozen :feature:record module")
    return errors


def validate_tests_resources() -> list[str]:
    errors: list[str] = []
    tests = "\n".join(path.read_text(encoding="utf-8") for root in ("app/src/androidTest", "finance/domain/src/test", "finance/data/src/androidTest", "feature/automation/src/test", "feature/automation/src/androidTest", "feature/record/src/androidTest") for path in sorted((ROOT / root).rglob("*.kt")))
    require_tokens(errors, tests, "P23 automated evidence", ("2_000", "startupWorkerRestartAndManualRetryNeverDuplicateCandidateOrFacts", "candidateConfirmationAndFinancialFactsCommitAtomicallyThroughCoordinator", "lockedProcessCatchUpUsesRealKeystoreSqlCipherHeadlessLeaseAndReleasesIt", "allTwentyThreeAutomationStatesRenderAcrossFrozenAccessibilityMatrix", "rec026ContentAndEmptyRenderInRecordModuleAcrossAccessibilityBoundary", "p23ProductionGoldensMatchEveryPixel", "PRAGMA integrity_check"))
    sets = []
    for folder in ("values", "values-en", "values-ja"):
        sets.append({name for name in re.findall(r'<string name="([^"]+)"', read(f"feature/automation/src/main/res/{folder}/strings.xml")) if name.startswith("automation_")})
    if not sets[0] or sets[0] != sets[1] or sets[0] != sets[2]:
        errors.append("P23 automation strings incomplete across zh-CN/en/ja")
    schema = "\n".join(read(path) for path in ("core/database/src/main/assets/ledger_schema_v1_core.sql", "core/database/src/main/assets/ledger_schema_v1_subledgers.sql", "core/database/src/main/assets/ledger_schema_v1_projections_operations.sql"))
    require_tokens(errors, schema, "P23 normalized schema", ("CREATE TABLE transaction_blueprint (", "CREATE TABLE transaction_blueprint_revision (", "CREATE TABLE recurrence_series (", "CREATE TABLE recurrence_series_revision (", "CREATE TABLE recurrence_exception (", "CREATE TABLE recurrence_occurrence (", "CREATE TABLE recurrence_candidate (", "UNIQUE (series_id, series_revision_id, occurrence_instant)"))
    return errors


def validate_ledgers() -> list[str]:
    errors: list[str] = []
    state, evidence = read("docs/implementation/PROJECT_STATE.md"), read("docs/implementation/TEST_EVIDENCE.md")
    mapping_path = ROOT / "docs/implementation/P23_AUTOMATION_MAPPING.md"
    mapping = mapping_path.read_text(encoding="utf-8") if mapping_path.is_file() else ""
    require_tokens(errors, state, "PROJECT_STATE", ("Current stage: P36", "| P23 | VERIFIED |"))
    for index in range(1, 8):
        if f"P23-E{index:03d}" not in evidence:
            errors.append(f"TEST_EVIDENCE missing P23-E{index:03d}")
    require_tokens(errors, mapping, "P23 mapping", ("25 required states", "occurrence unique key", "candidate", "WorkManager", "P23 is `VERIFIED`"))
    with (ROOT / "docs/implementation/SCREEN_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        screens = {row["screen_id"]: row for row in csv.DictReader(handle)}
    for screen_id in EXPECTED:
        row = screens.get(screen_id, {})
        if row.get("status") != "VERIFIED" or "P23" not in row.get("implementation_evidence", "") or "P23-E" not in row.get("verification_evidence", ""):
            errors.append(f"{screen_id} lacks VERIFIED P23 evidence")
    with (ROOT / "docs/implementation/REQUIREMENT_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        requirements = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    for requirement_id in ("REQ-004", "REQ-039", "REQ-058", "REQ-059"):
        row = requirements.get(requirement_id, {})
        if row.get("status") != "VERIFIED" or "P23" not in row.get("implementation_evidence", "") or "P23-E" not in row.get("verification_evidence", ""):
            errors.append(f"{requirement_id} lacks VERIFIED P23 evidence")
    return errors


def main() -> int:
    errors = validate_contract() + validate_sources() + validate_tests_resources() + validate_ledgers()
    if errors:
        print("P23 automation validation: FAIL", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("P23 automation validation: PASS")
    print("screens=11 required_states=25 recurrence=deterministic occurrence_key=unique candidates=fact_free worker_input=operationId visual_inputs=contract_token_yaml_only")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
