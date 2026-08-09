#!/usr/bin/env python3
"""Reject P28 import streaming, staging, atomicity, privacy, UI, or evidence drift."""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
EXPECTED = {
    "IMP-001": ("import/source", [], {"content", "permissionError"}),
    "IMP-002": ("import/{operationId}/format", ["operationId:StableId"], {"parsing", "content", "corruptFile", "unsupported"}),
    "IMP-003": ("import/{operationId}/field-mapping", ["operationId:StableId"], {"editing", "missingRequired", "valid"}),
    "IMP-004": ("import/{operationId}/entity-mapping", ["operationId:StableId"], {"editing", "unmapped", "valid"}),
    "IMP-005": ("import/{operationId}/fx-mapping", ["operationId:StableId"], {"content", "manualRatesRequired", "valid"}),
    "IMP-006": ("import/{operationId}/validation", ["operationId:StableId"], {"validating", "errors", "warnings", "valid"}),
    "IMP-007": ("import/{operationId}/preview", ["operationId:StableId"], {"content", "notReady"}),
    "IMP-008": ("import/{operationId}/commit", ["operationId:StableId"], {"preparing", "applyingToShadow", "validating", "committing", "cancelRequested", "failed", "succeeded"}),
    "IMP-009": ("import/{operationId}/result", ["operationId:StableId"], {"success", "partialNotAllowed", "failed", "rolledBack"}),
    "IMP-010": ("import/history", [], {"content", "empty"}),
}
STRUCTURED_KINDS = {
    "ACCOUNT", "CARD", "CATEGORY", "MERCHANT", "PLACE", "GOAL", "PROJECT", "SETTLEMENT_ACTIVITY",
    "LOCATION", "RECURRENCE", "TRANSACTION", "CREDIT_STATEMENT", "INSTALLMENT", "LOAN", "BUDGET",
}
PLACEHOLDER = re.compile(r"\b(?:TODO|NotImplemented|UnsupportedOperationException)\b")


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def source_map() -> dict[str, str]:
    roots = (
        "app/src/main/kotlin", "core/security/src/main/kotlin", "feature/transfer/src/main/kotlin",
        "finance/application/src/main/kotlin", "finance/data/src/main/kotlin",
        "transfer/domain/src/main/kotlin", "transfer/data/src/main/kotlin",
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


def named(sources: dict[str, str], filename: str) -> str:
    return next((value for path, value in sources.items() if path.endswith(filename)), "")


def validate_contract() -> list[str]:
    screens = {
        item["id"]: item
        for item in yaml.safe_load(read("docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml"))["screens"]
    }
    errors: list[str] = []
    for screen_id, (route, params, states) in EXPECTED.items():
        actual = screens.get(screen_id, {})
        if actual.get("route") != route or actual.get("params", []) != params or set(actual.get("requiredStates", [])) != states:
            errors.append(f"{screen_id} route/params/requiredStates drift")
    if sum(len(value[2]) for value in EXPECTED.values()) != 34:
        errors.append("P28 required-state baseline must remain exactly 34")
    return errors


def validate_dependencies() -> list[str]:
    errors: list[str] = []
    catalog = read("gradle/libs.versions.toml")
    module = read("transfer/data/build.gradle.kts")
    require_tokens(errors, catalog, "frozen import dependency catalog", (
        'commons-csv = "1.14.1"', 'fastexcel = "0.20.2"', 'icu4j = "78.3"',
        'commons-csv = { module = "org.apache.commons:commons-csv"',
        'fastexcel-reader = { module = "org.dhatim:fastexcel-reader"',
        'icu4j = { module = "com.ibm.icu:icu4j"',
    ))
    require_tokens(errors, module, "transfer data dependency boundary", (
        "implementation(libs.commons.csv)", "implementation(libs.fastexcel.reader)",
        "implementation(libs.icu4j)", "implementation(libs.stax.api)", 'maxHeapSize = "256m"',
    ))
    gradle_text = "\n".join(
        path.read_text(encoding="utf-8")
        for pattern in ("*.gradle.kts", "*.toml")
        for path in ROOT.rglob(pattern)
        if "/build/" not in path.as_posix()
    )
    if re.search(r"org\.apache\.poi|libs\.poi\b", gradle_text, re.IGNORECASE):
        errors.append("Apache POI dependency/fallback is forbidden in P28")
    return errors


def validate_sources(sources: dict[str, str] | None = None) -> list[str]:
    sources = source_map() if sources is None else sources
    errors: list[str] = []
    required = {
        "AndroidCsvImportReader.kt", "FastExcelImportReader.kt", "ImportIngestionService.kt",
        "ImportPreparationService.kt", "SqlCipherStagingRepository.kt", "ImportWorkflow.kt",
        "SecureImportStagingAccess.kt", "SecureShadowLedgerAccess.kt", "SecureImportSourceHandleStore.kt",
        "SecureImportTemporaryFileCleaner.kt", "ImportFinancialApplication.kt", "StructuredImportApplication.kt",
        "SecureRoomImportFinancialApplicationPort.kt", "SecureRoomStructuredImportApplicationPort.kt",
        "StructuredImportRowApplier.kt", "ImportWorker.kt", "ImportController.kt",
        "ImportPreparedFinancialPageSource.kt", "PreparedStructuredImportPageSource.kt", "ImportWizardScreen.kt",
    }
    selected = {path: source for path, source in sources.items() if Path(path).name in required}
    missing = required - {Path(path).name for path in selected}
    if missing:
        errors.append(f"P28 production files missing: {sorted(missing)}")
    for path, source in selected.items():
        if PLACEHOLDER.search(source):
            errors.append(f"placeholder in {path}")

    csv_reader = named(sources, "AndroidCsvImportReader.kt")
    xlsx_reader = named(sources, "FastExcelImportReader.kt")
    require_tokens(errors, csv_reader, "Commons CSV plus ICU/BOM streaming", (
        "org.apache.commons.csv.CSVFormat", "com.ibm.icu.text.CharsetDetector", "PushbackInputStream",
        "ByteOrderMark.detect", "userCharset != null", "peakBufferedRows = 1", "ensureActive(request.cancellation)",
        "ImportFailure.InvalidEncoding", "ImportFailure.CorruptSource",
    ))
    require_tokens(errors, xlsx_reader, "FastExcel streaming", (
        "org.dhatim.fastexcel.reader.ReadableWorkbook", "ReadingOptions(true, true)", "workbook.sheets.use",
        "sheet.openStream().use", "cell.formula", "cell.asDate()", "BigDecimal",
        "ImportReadSummary(summaries, totalRows, 1",
        "ImportFailure.UnsupportedSource", "ImportFailure.CorruptSource", "ensureActive(request.cancellation)",
    ))

    workflow = named(sources, "ImportWorkflow.kt")
    kind_block = re.search(r"enum class StructuredEntityKind.*?\n}\n", workflow, re.DOTALL)
    actual_kinds = set(re.findall(r"^\s{4}([A-Z][A-Z_]+)\(", kind_block.group(0) if kind_block else "", re.MULTILINE))
    if actual_kinds != STRUCTURED_KINDS:
        errors.append(f"structured workbook entity coverage drift: {sorted(actual_kinds)}")
    require_tokens(errors, workflow, "nine-stage workflow and bounded stream", (
        "SOURCE,", "STRUCTURE,", "FIELD_MAPPING,", "ENTITY_MAPPING,", "FX,", "VALIDATION,",
        "CONFIRMATION,", "EXECUTION,", "RESULT,", "MAX_STREAM_BUFFER_ROWS: Int = 512",
        "pauseAllowed", "cancelAllowed", "safeBoundaryDescriptionCode",
    ))

    staging = named(sources, "SqlCipherStagingRepository.kt")
    staging_access = named(sources, "SecureImportStagingAccess.kt")
    require_tokens(errors, staging + staging_access, "independent encrypted staging", (
        "openImportStaging", "ledger-import-staging-v1", "staging_raw_row", "staging_parsed_row",
        "staging_mapping", "staging_validation_error", "staging_duplicate_candidate",
        "staging_prepared_command", "staging_attachment", "preparedCommands", "structuredPreparedCommands",
        "mappings()", "duplicateCandidates()", "stagingKey.fill(0)",
    ))

    preparation = named(sources, "ImportPreparationService.kt")
    require_tokens(errors, preparation + workflow, "deterministic validation/mapping", (
        "DUPLICATE_REQUIRES_RESOLUTION", "IMPORT_SEPARATE_TRANSACTIONS_REQUIRED",
        "DuplicateResolution.SKIP", "DuplicateResolution.IMPORT_ANYWAY", "missingCreates",
        "ImportTargetField.FX_RATE", "savePrepared", "saveErrors", "saveDuplicates",
    ))

    financial = named(sources, "SecureRoomImportFinancialApplicationPort.kt")
    structured = named(sources, "SecureRoomStructuredImportApplicationPort.kt")
    shadow = named(sources, "SecureShadowLedgerAccess.kt")
    require_tokens(errors, financial + structured + shadow, "atomic primary/shadow application and undo", (
        "commitSmall", "commitShadow", "shadowThresholdRows", "createSnapshot", "writeShadow", "validate",
        "exchange", "expectedLiveHead", "ATOMIC_MOVE", "import_source_reference", "sourceFingerprint",
        "replayed = true", "override suspend fun undo", "createRollbackSnapshot", "insertRestoreAudit",
    ))
    require_tokens(errors, named(sources, "StructuredImportRowApplier.kt") + structured, "typed structured application ports", (
        "SecureRoomReferenceDataManagementPort", "SecureRoomCreditApplicationPort",
        "SecureRoomInstallmentApplicationPort", "SecureRoomLoanApplicationPort", "SecureRoomBudgetApplicationPort",
        "applyGoal", "applyProject", "SecureRoomAutomationApplicationPort",
        "SecureRoomSettlementApplicationPort", "SecureRoomBatchEntryApplicationPort",
    ))

    worker = named(sources, "ImportWorker.kt")
    controller = named(sources, "ImportController.kt")
    require_tokens(errors, worker + controller, "foreground resumability and safe cancellation", (
        "CoroutineWorker", "setForeground", "ImportRunControlRegistry", "configureImportCommit",
        "BackgroundOperationState.COMMITTING", "enqueueUniqueWork", "ExistingWorkPolicy.REPLACE",
        "failIngestion", "FAILED_FINAL", "OUTPUT_CLEANUP_COMPLETE", "removeSourceHandle",
        "ImportFinancialApplicationPort", "StructuredImportApplicationPort",
    ))
    if "putString(ImportWorker.INPUT_OPERATION_ID" not in worker or "inputData.keyValueMap.keys == setOf(INPUT_OPERATION_ID)" not in worker:
        errors.append("ImportWorker Data must contain only the opaque operation id")
    if re.search(r'putString\([^,]+,\s*(?:uri|path|note|merchant|account|amount)', worker, re.IGNORECASE):
        errors.append("ImportWorker Data carries sensitive source/business data")

    ui = named(sources, "ImportWizardScreen.kt")
    require_tokens(errors, ui, "IMP-001..010 governed UI", tuple(f'"IMP-{index:03d}"' for index in range(1, 11)) + (
        "LazyColumn", "previewRowCount", "DuplicateResolution.SKIP", "DuplicateResolution.IMPORT_ANYWAY",
        "clearAndSetSemantics", "ImportExecutionState.COMMITTING", "temporaryCleanupComplete",
    ))
    if re.search(r"^import\s+(?:androidx\.room|app\.ledger\.(?:finance\.data|transfer\.data|core\.database))", ui, re.MULTILINE):
        errors.append("P28 feature bypasses application/domain boundaries")
    return errors


def validate_tests_resources() -> list[str]:
    errors: list[str] = []
    tests = "\n".join(
        path.read_text(encoding="utf-8")
        for root in (
            "transfer/data/src/test", "transfer/data/src/androidTest", "finance/data/src/androidTest",
            "feature/transfer/src/androidTest",
        )
        for path in sorted((ROOT / root).rglob("*.kt"))
    )
    require_tokens(errors, tests, "P28 automated evidence", (
        "csvStreamsOneHundredThousandNonAsciiRowsWithBomAndBoundedBuffer",
        "xlsxStreamsMultipleSheetsAndCachedFormulaTypes", "xlsxRejectsLegacyAndCorruptContainers",
        "commonsCsvIcuAndFastExcelOperateOnAndroidRuntime",
        "crashRetryResumesFromDurableChunkWithoutDuplicatingStagingRows",
        "pauseResumesOnlyAtDurableChunkBoundary", "cancellationRollsBackAndDestroysStagingAtSafeBoundary",
        "oneHundredThousandRowsRemainEncryptedPagedAndRestartRecoverable",
        "committingOperationParametersSurviveEncryptedRepositoryRecreation",
        "mappingsAndDuplicateCandidatesRemainDurableAcrossStagingReopen",
        "validationFailureAtSourceRow99999LeavesPrimaryLedgerStateUnchanged",
        "shadowCommitIsAtomicIdempotentAuditedAndWholeBatchUndoable",
        "allFifteenStructuredEntityTypesApplyThroughTypedPortsInOneShadowExchange",
        "structuredSplitTransactionMustBeExpandedIntoSeparateTransactions",
        "duplicateCandidateBlocksUntilExplicitImportAnywayResolution",
        "imp001ThroughImp010AndRequiredStatesRenderAcrossAccessibilityMatrix",
        "hundredThousandRowPreviewIsVirtualizedAndSensitiveSampleIsAbsentFromSemantics",
        "p28ProductionGoldensMatchEveryPixel", "EXPECTED_SOURCE_SHA256", "EXPECTED_VALIDATION_SHA256",
    ))
    localized = []
    for folder in ("values", "values-en", "values-ja"):
        names = set(re.findall(r'<string name="([^"]+)"', read(f"feature/transfer/src/main/res/{folder}/strings.xml")))
        localized.append({name for name in names if name.startswith("import_")})
    if not localized[0] or localized[0] != localized[1] or localized[0] != localized[2]:
        errors.append("P28 import strings are incomplete across zh-CN/en/ja")
    return errors


def validate_ledgers() -> list[str]:
    errors: list[str] = []
    state = read("docs/implementation/PROJECT_STATE.md")
    evidence = read("docs/implementation/TEST_EVIDENCE.md")
    mapping_path = ROOT / "docs/implementation/P28_IMPORT_MAPPING.md"
    mapping = mapping_path.read_text(encoding="utf-8") if mapping_path.is_file() else ""
    require_tokens(errors, state, "PROJECT_STATE", ("Current stage: P28", "Stage status: VERIFIED"))
    for index in range(1, 9):
        if f"P28-E{index:03d}" not in evidence:
            errors.append(f"TEST_EVIDENCE missing P28-E{index:03d}")
    require_tokens(errors, mapping, "P28 mapping", (
        "100,000", "FastExcel 0.20.2", "SQLCipher staging", "row 99,999", "15 structured",
        "nine stages", "whole-batch undo", "P28 is `VERIFIED`",
    ))
    with (ROOT / "docs/implementation/SCREEN_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        screens = {row["screen_id"]: row for row in csv.DictReader(handle)}
    for screen_id in EXPECTED:
        row = screens.get(screen_id, {})
        if row.get("status") != "VERIFIED" or "P28" not in row.get("implementation_evidence", "") or "P28-E" not in row.get("verification_evidence", ""):
            errors.append(f"{screen_id} lacks VERIFIED P28 evidence")
    with (ROOT / "docs/implementation/REQUIREMENT_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        requirements = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    for requirement_id in ("REQ-005", "REQ-029", "REQ-073", "REQ-084"):
        row = requirements.get(requirement_id, {})
        if "P28" not in row.get("implementation_evidence", "") or "P28-E" not in row.get("verification_evidence", ""):
            errors.append(f"{requirement_id} lacks P28 evidence")
    if requirements.get("REQ-029", {}).get("status") != "VERIFIED" or requirements.get("REQ-073", {}).get("status") != "VERIFIED":
        errors.append("REQ-029 and REQ-073 must be VERIFIED by P28")
    return errors


def main() -> int:
    errors = validate_contract() + validate_dependencies() + validate_sources() + validate_tests_resources() + validate_ledgers()
    if errors:
        print("P28 import validation: FAIL", file=sys.stderr)
        for item in errors:
            print(f"- {item}", file=sys.stderr)
        return 1
    print("P28 import validation: PASS")
    print("screens=10 states=34 stages=9 structured_kinds=15 streaming=100000 sqlcipher=staging atomic=small+shadow undo=whole-batch")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
