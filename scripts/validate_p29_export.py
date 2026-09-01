#!/usr/bin/env python3
"""Reject P29 export streaming, SAF, privacy, recovery, UI, or evidence drift."""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
EXPECTED = {
    "EXP-001": ("export/type", [], {"content"}),
    "EXP-002": ("export/{operationId}/fields", ["operationId:StableId"], {"editing", "valid"}),
    "EXP-003": ("export/{operationId}/destination", ["operationId:StableId"], {"content", "permissionRevoked", "nameConflict"}),
    "EXP-004": ("export/{operationId}/progress", ["operationId:StableId"], {"running", "cancelRequested", "failed", "succeeded"}),
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
        for root in roots for path in sorted((ROOT / root).rglob("*.kt"))
    }


def named(sources: dict[str, str], filename: str) -> str:
    return next((value for path, value in sources.items() if path.endswith(filename)), "")


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
    if sum(len(item[2]) for item in EXPECTED.values()) != 10:
        errors.append("P29 required-state baseline must remain exactly 10")
    return errors


def validate_dependencies() -> list[str]:
    errors: list[str] = []
    catalog = read("gradle/libs.versions.toml")
    module = read("transfer/data/build.gradle.kts")
    require_tokens(errors, catalog, "frozen export catalog", (
        'commons-csv = "1.14.1"', 'fastexcel = "0.20.2"', 'documentfile = "1.1.0"',
        'fastexcel = { module = "org.dhatim:fastexcel"',
    ))
    require_tokens(errors, module, "transfer export dependencies", (
        "implementation(libs.commons.csv)", "implementation(libs.fastexcel)", "implementation(libs.androidx.documentfile)",
    ))
    gradle_text = "\n".join(
        path.read_text(encoding="utf-8") for pattern in ("*.gradle.kts", "*.toml")
        for path in ROOT.rglob(pattern) if "/build/" not in path.as_posix()
    )
    if re.search(r"org\.apache\.poi|libs\.poi\b", gradle_text, re.IGNORECASE):
        errors.append("Apache POI dependency/fallback is forbidden in P29")
    return errors


def validate_sources(sources: dict[str, str] | None = None) -> list[str]:
    sources = source_map() if sources is None else sources
    errors: list[str] = []
    required = {
        "ExportModel.kt", "LedgerExportQuery.kt", "SecureRoomLedgerExportQueryPort.kt", "StreamingExportEngine.kt",
        "LedgerExportTabularSource.kt", "SafExportDestination.kt", "SecureTransferHandleStore.kt", "ExportWorker.kt",
        "ExportController.kt", "ExportRootDestination.kt", "ExportFlowScreen.kt",
    }
    selected = {path: source for path, source in sources.items() if Path(path).name in required}
    missing = required - {Path(path).name for path in selected}
    if missing:
        errors.append(f"P29 production files missing: {sorted(missing)}")
    for path, source in selected.items():
        if PLACEHOLDER.search(source):
            errors.append(f"placeholder in {path}")

    model = named(sources, "ExportModel.kt")
    require_tokens(errors, model, "closed safe export descriptor", (
        "CURRENT_FILTER", "FULL_WORKBOOK", "REPORT", "LATITUDE_E7", "LONGITUDE_E7",
        "val sensitiveLocation", "val defaultSelection", "format != ExportFormat.PORTABLE_BACKUP",
        "not a complete backup", "SensitiveFieldRejected",
    ))

    engine = named(sources, "StreamingExportEngine.kt")
    require_tokens(errors, engine, "streaming CSV/XLSX/PDF/image writers", (
        "org.apache.commons.csv.CSVPrinter", "org.dhatim.fastexcel.Workbook", "val document = PdfDocument()", "Bitmap.createBitmap",
        "PAGE_SIZE = 256", "ROWS_PER_PDF_PAGE", "IMAGE_SAMPLE_ROWS", "UTF8_BOM", "spreadsheetSafe",
        "ExportFailure.InsufficientSpace", "ExportFailure.PermissionRevoked", "checkCancelled",
    ))

    query = named(sources, "SecureRoomLedgerExportQueryPort.kt")
    require_tokens(errors, query + named(sources, "LedgerExportTabularSource.kt"), "SQLCipher read-only allowlisted source", (
        "SecureRoomJournalApplicationPort", "TRANSACTION_HEADERS", "LedgerWorkbookSheet.entries",
        "LedgerWorkbookSheet.CARDS", "last_four", "LedgerWorkbookSheet.LOCATIONS", "includeLocationCoordinates",
        "readLedger", "PAGE_LIMIT = 512",
    ))
    code_without_comments = re.sub(r"/\*.*?\*/|//.*?$", "", query, flags=re.DOTALL | re.MULTILINE)
    for forbidden in ("card_vault_secret", "pan_ciphertext", "security_code_ciphertext", "account_number"):
        if forbidden in code_without_comments:
            errors.append(f"ordinary export query references forbidden sensitive column/table: {forbidden}")

    destination = named(sources, "SafExportDestination.kt")
    handles = named(sources, "SecureTransferHandleStore.kt")
    require_tokens(errors, destination + handles, "SAF publish and encrypted handle recovery", (
        "DocumentFile.fromTreeUri", "openOutputStream", ".partial", ".previous", "providerTemporary.renameTo(descriptor.fileName)", "cleanup",
        "noBackupFilesDir", "encryptSecureSettings", "decryptSecureSettings", "ATOMIC_MOVE",
    ))

    worker = named(sources, "ExportWorker.kt")
    require_tokens(errors, worker, "durable WorkManager/UIDT execution", (
        "CoroutineWorker", "setForeground", "setUserInitiated(true)",
        "OperationCheckpoint", "COMMITTING", "ExportRunControlRegistry", "ExistingWorkPolicy.REPLACE",
        "inputData.keyValueMap.keys == setOf(INPUT_OPERATION_ID)",
    ))
    if re.search(r'putString\([^,]+,\s*(?:uri|path|filter|report|account|amount|note)', worker, re.IGNORECASE):
        errors.append("export Worker/UIDT payload carries more than opaque operationId")
    if "android.permission.RUN_USER_INITIATED_JOBS" not in read("app/src/main/AndroidManifest.xml"):
        errors.append("API 34 UIDT permission missing from app manifest")

    controller = named(sources, "ExportController.kt")
    require_tokens(errors, controller, "content/format/fields/location/result flow", (
        "takePersistableUriPermission", "findFile", "NAME_CONFLICT", "PERMISSION_REVOKED", "overwriteConfirmed",
        "Intent.ACTION_VIEW", "Intent.ACTION_SEND", "FLAG_GRANT_READ_URI_PERMISSION", "awaitCurrent",
        "ExportContent.CURRENT_FILTER", "ExportContent.FULL_WORKBOOK", "ExportContent.REPORT",
    ))
    if ".canWrite()" in controller + destination:
        errors.append("SAF export must use provider operations rather than advisory canWrite flags")

    ui = named(sources, "ExportFlowScreen.kt")
    require_tokens(errors, ui, "EXP-001..004 governed UI", (
        '"EXP-001"', '"EXP-002"', '"EXP-003"', '"EXP-004"', "ExportDestinationPresentation.PERMISSION_REVOKED",
        "ExportDestinationPresentation.NAME_CONFLICT", "ExportExecutionPresentation.CANCEL_REQUESTED",
        "ExportExecutionPresentation.SUCCEEDED", "includeLocationCoordinates", "ExportField::sensitiveLocation",
    ))
    if re.search(r"^import\s+(?:androidx\.room|app\.ledger\.(?:finance\.data|transfer\.data|core\.database))", ui, re.MULTILINE):
        errors.append("P29 feature bypasses application/domain boundaries")
    return errors


def validate_tests_resources() -> list[str]:
    errors: list[str] = []
    tests = "\n".join(
        path.read_text(encoding="utf-8") for root in (
            "app/src/androidTest", "app/src/test", "feature/transfer/src/androidTest",
            "finance/data/src/androidTest", "transfer/data/src/androidTest",
        )
        for path in sorted((ROOT / root).rglob("*.kt"))
    )
    require_tokens(errors, tests, "P29 automated evidence", (
        "hundredThousandRowCsvStreamsUtf8MetadataAndNonAsciiWithoutLargeBuffers",
        "hundredThousandRowsAcrossFifteenXlsxSheetsRoundTripWithFastExcel",
        "largePdfAndHundredThousandRowImageFinishWithPageBoundedSources",
        "cancellationSpaceFailureAndPermissionRevocationReturnTypedStates",
        "nameConflictPreservesExistingFileUntilExplicitOverwriteThenPublishesAtomically",
        "revokedPermissionReturnsTypedStateAndCancelCleanupRemovesAppTemporary",
        "sensitiveVaultFieldsAreAbsentAndCoordinatesAreOffByDefault",
        "preparedReportStreamsToCsvAndXlsxWithVersionedMetadata",
        "allFifteenWorkbookQueriesExecuteAndVaultCiphertextsNeverReachOrdinaryExport",
        "exportDescriptorAndReportCheckpointSurviveEncryptedRepositoryRecreation",
        "api34RemoteSafExportSchedulesUserInitiatedJobWithOpaqueIdOnly",
        "exp001ThroughExp004AndAllRequiredStatesRenderInThreeLanguages",
        "p29ProductionGoldensMatchEveryPixel",
        "exportOperationCenterPresentation",
    ))
    localized = []
    for folder in ("values", "values-en", "values-ja"):
        names = set(re.findall(r'<string name="([^"]+)"', read(f"feature/transfer/src/main/res/{folder}/strings.xml")))
        localized.append({name for name in names if name.startswith("export_")})
    if not localized[0] or localized[0] != localized[1] or localized[0] != localized[2]:
        errors.append("P29 export strings are incomplete across zh-CN/en/ja")
    return errors


def validate_ledgers() -> list[str]:
    errors: list[str] = []
    state = read("docs/implementation/PROJECT_STATE.md")
    evidence = read("docs/implementation/TEST_EVIDENCE.md")
    mapping_path = ROOT / "docs/implementation/P29_EXPORT_MAPPING.md"
    mapping = mapping_path.read_text(encoding="utf-8") if mapping_path.is_file() else ""
    require_tokens(errors, state, "PROJECT_STATE", ("Current stage: P36", "| P29 | VERIFIED |"))
    for index in range(1, 8):
        if f"P29-E{index:03d}" not in evidence:
            errors.append(f"TEST_EVIDENCE missing P29-E{index:03d}")
    require_tokens(errors, mapping, "P29 mapping", (
        "100,000", "FastExcel 0.20.2", "PdfDocument", "SAF", "sensitive", "operation center", "P29 is `VERIFIED`",
    ))
    with (ROOT / "docs/implementation/SCREEN_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        screens = {row["screen_id"]: row for row in csv.DictReader(handle)}
    for screen_id in (*EXPECTED, "ANA-010"):
        row = screens.get(screen_id, {})
        if row.get("status") != "VERIFIED" or "P29" not in row.get("implementation_evidence", "") or "P29-E" not in row.get("verification_evidence", ""):
            errors.append(f"{screen_id} lacks VERIFIED P29 evidence")
    with (ROOT / "docs/implementation/REQUIREMENT_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        requirements = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    for requirement_id in ("REQ-074", "REQ-084"):
        row = requirements.get(requirement_id, {})
        if row.get("status") not in {"IN_PROGRESS", "VERIFIED"} or "P29" not in row.get("implementation_evidence", "") or "P29-E" not in row.get("verification_evidence", ""):
            errors.append(f"{requirement_id} lacks truthful P29 evidence")
    return errors


def main() -> int:
    errors = validate_contract() + validate_dependencies() + validate_sources() + validate_tests_resources() + validate_ledgers()
    if errors:
        print("P29 export validation: FAIL", file=sys.stderr)
        for item in errors:
            print(f"- {item}", file=sys.stderr)
        return 1
    print("P29 export validation: PASS")
    print("screens=4 states=10 formats=csv+xlsx+pdf+png rows=100000 saf=atomic-sensitive-safe operation=recoverable")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
