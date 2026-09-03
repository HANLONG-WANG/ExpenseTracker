#!/usr/bin/env python3
"""Reject P30 encryption, publication, transport, recovery, privacy, UI, or evidence drift."""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
EXPECTED = {
    "BKP-001": ("backup", [], {"configured", "notConfigured", "running", "failed", "permissionRevoked"}),
    "BKP-002": ("backup/repository", [], {"editing", "driveAuthRequired", "permissionRevoked"}),
    "BKP-003": ("backup/recovery-password", [], {"create", "change", "invalid", "reEncrypting"}),
    "BKP-004": ("backup/settings", [], {"content", "vaultPasswordRequired"}),
    "BKP-005": ("backup/list", [], {"content", "empty", "loadingRemote"}),
    "BKP-006": ("backup/{snapshotId}", ["snapshotId:StableId"], {"verified", "unverified", "corrupt", "remoteUnavailable"}),
    "BKP-007": ("backup/manual", [], {"ready", "passwordRequired", "running"}),
    "SYS-003": ("authorization/drive", [], {"disconnected", "authorizing", "connected", "failed"}),
}
PLACEHOLDER = re.compile(r"\b(?:TODO|NotImplemented|UnsupportedOperationException)\b")


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def source_map() -> dict[str, str]:
    roots = (
        "app/src/main/kotlin", "core/security/src/main/kotlin", "feature/transfer/src/main/kotlin",
        "finance/application/src/main/kotlin", "finance/data/src/main/kotlin", "transfer/domain/src/main/kotlin",
        "transfer/data/src/main/kotlin",
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
    contract = yaml.safe_load(read("docs/初始开发文件存档/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml"))
    screens = {item["id"]: item for item in contract["screens"]}
    errors: list[str] = []
    for screen_id, (route, params, states) in EXPECTED.items():
        actual = screens.get(screen_id, {})
        if actual.get("route") != route or actual.get("params", []) != params or set(actual.get("requiredStates", [])) != states:
            errors.append(f"{screen_id} route/params/requiredStates drift")
    if sum(len(value[2]) for value in EXPECTED.values()) != 28:
        errors.append("P30 required-state baseline must remain exactly 28")
    return errors


def validate_dependencies() -> list[str]:
    errors: list[str] = []
    catalog = read("gradle/libs.versions.toml")
    transfer = read("transfer/data/build.gradle.kts")
    app = read("app/build.gradle.kts")
    require_tokens(errors, catalog, "frozen backup dependencies", (
        'tink = "1.23.0"', 'commons-compress = "1.28.0"', 'play-services-auth = "21.6.0"',
        'commons-compress = { module = "org.apache.commons:commons-compress"',
        'play-services-auth = { module = "com.google.android.gms:play-services-auth"',
    ))
    require_tokens(errors, transfer, "backup data dependencies", (
        "implementation(libs.commons.compress)", "implementation(libs.okhttp)", "implementation(libs.tink)",
    ))
    require_tokens(errors, app, "Drive authorization dependency", ("implementation(libs.play.services.auth)",))
    return errors


def validate_sources(sources: dict[str, str] | None = None) -> list[str]:
    sources = source_map() if sources is None else sources
    errors: list[str] = []
    required = {
        "BackupModel.kt", "BackupKeyEnvelopeStore.kt", "ManagedBackupRepositoryEngine.kt", "SqlCipherBackupCatalog.kt",
        "BackupRepositoryStorage.kt", "PortableBackupContainer.kt", "SafPortableBackupDestination.kt",
        "DriveResumableBackupClient.kt", "DriveBackupRepositoryPublisher.kt", "SqlCipherDriveCheckpointStore.kt",
        "BackupRecoveryReencryption.kt", "AutomaticBackupCheckpointStore.kt", "AndroidBackupInputFactory.kt",
        "AutomaticBackupScheduler.kt", "BackupWorker.kt", "GoogleDriveAuthorizationGateway.kt", "BackupController.kt",
        "BackupFlowScreen.kt", "SecurePrimaryLedgerAccess.kt",
    }
    selected = {path: source for path, source in sources.items() if Path(path).name in required}
    missing = required - {Path(path).name for path in selected}
    if missing:
        errors.append(f"P30 production files missing: {sorted(missing)}")
    for path, source in selected.items():
        if PLACEHOLDER.search(source):
            errors.append(f"placeholder in {path}")

    model = named(sources, "BackupModel.kt")
    require_tokens(errors, model, "backup format and policy contract", (
        "DATABASE_CHUNK_BYTES", "DEFAULT_RETENTION_COUNT: Int = 30", 'PORTABLE_EXTENSION: String = ".ledger-backup"',
        "DATABASE_SNAPSHOT", "OBJECT_PROCESSING", "WRITING_OR_UPLOADING", "VERIFYING", "PUBLISHING_MANIFEST",
        "RE_ENCRYPT_ACCESSIBLE_HISTORY", "RecoveryPasswordRequired", "InsufficientSpace", "PermissionRevoked",
        "shouldCreateDailyBackup",
    ))

    security = named(sources, "BackupKeyEnvelopeStore.kt") + named(sources, "RecoveryPasswordWrapping.kt")
    require_tokens(errors, security, "independent recovery and background key wrapping", (
        "LedgerTink.generateStreamingAeadKeyset", "openForAutomaticBackup", "openWithRecoveryPassword",
        "RecoveryPasswordKeyWrapper", "Argon2Parameters.ARGON2_id", "SecureRandom", "salt",
        "readForAutomaticBackup", "Stores only a recovery-password wrapped Vault DEK",
    ))

    engine = named(sources, "ManagedBackupRepositoryEngine.kt")
    catalog = named(sources, "SqlCipherBackupCatalog.kt")
    require_tokens(errors, engine, "managed encrypted repository", (
        "repository-header.header", "DATABASE_CHUNK_BYTES", "findObject", "verifyExistingSource", "verifyObject",
        "PUBLISHING_MANIFEST", "published manifest verification mismatch", "publishSnapshot", "enforceRetention",
        "collectInterruptedObjects", "newEncryptingStream", "newDecryptingStream",
    ))
    require_tokens(errors, catalog, "SQLCipher immutable snapshot catalog and reference GC", (
        "INSERT INTO backup_snapshot", "INSERT INTO backup_snapshot_object", "BackupSnapshotState.COMPLETE",
        "DELETE FROM backup_snapshot_object", "DELETE FROM backup_snapshot", "DELETE FROM backup_object",
        "access.write", "published backup snapshot object set changed",
    ))
    if "UPDATE backup_snapshot" in catalog:
        errors.append("append-only backup_snapshot must be published by one COMPLETE insert, never status mutation")
    if "UPDATE book" in catalog:
        errors.append("backup catalog must not mutate financial book state outside finance:data")
    maintenance = named(sources, "SecurePrimaryLedgerAccess.kt")
    require_tokens(errors, maintenance, "scoped encrypted backup catalog access", (
        "class SecurePrimaryLedgerAccess", "fun <T> read", "fun <T> write",
        "EncryptedDatabaseFactory.openPrimary",
    ))

    portable = named(sources, "PortableBackupContainer.kt")
    require_tokens(errors, portable, "portable ZIP64 Streaming AEAD container", (
        "ZipArchiveOutputStream", "Zip64Mode.Always", "newEncryptingStream", "newDecryptingStream",
        "database/ledger.db", "keys/portable-key-material.envelope", "keys/vault-recovery.envelope",
        "BackupFailure.InsufficientSpace", "checkCancelled",
    ))

    drive = named(sources, "DriveResumableBackupClient.kt") + named(sources, "DriveBackupRepositoryPublisher.kt")
    require_tokens(errors, drive, "Drive v3 resumable isolated repository transport", (
        "https://www.googleapis.com/upload/drive/v3/files", "uploadType", "resumable", "Content-Range", 'header("Range"',
        "DriveResumableCheckpoint", "ensureRepositoryFolder", "application/vnd.google-apps.folder",
        "listRepositoryFiles", "deleteRepositoryFile", "pruneUnreferenced", "finalManifest", "manifestPublished",
    ))
    if '.header("Range", "bytes=$offset-")' not in drive:
        errors.append("Drive resumable download must request the persisted byte Range")
    authorization = named(sources, "GoogleDriveAuthorizationGateway.kt")
    require_tokens(errors, authorization, "Google Identity drive.file authorization", (
        "Identity.getAuthorizationClient", "AuthorizationRequest.builder", "https://www.googleapis.com/auth/drive.file",
        "RevokeAccessRequest", "accessToken",
    ))

    storage = named(sources, "BackupRepositoryStorage.kt") + named(sources, "SafPortableBackupDestination.kt")
    require_tokens(errors, storage, "atomic local and SAF publication", (
        "ATOMIC_MOVE", ".partial", ".previous", "DocumentFile.fromTreeUri", "openOutputStream",
        "renameTo(name)", "PermissionRevoked", "cleanup",
    ))

    scheduler = named(sources, "AutomaticBackupScheduler.kt") + named(sources, "BackupWorker.kt")
    coordinator = named(sources, "FinancialMutationCoordinator.kt")
    require_tokens(errors, scheduler + coordinator, "daily first-mutation durable execution", (
        "FinancialCommitObserverRegistry", "recoverableBackupOperations", "AutomaticBackupPolicy.shouldCreateDailyBackup",
        "BackgroundOperationType.FULL_BACKUP", "OperationParameters.BackupRecoveryReencryption",
        "inputData.keyValueMap.keys == setOf(INPUT_OPERATION_ID)", "setForeground", "setUserInitiated(true)",
        "NetworkType.UNMETERED", "ExistingWorkPolicy.KEEP",
    ))
    if re.search(r'putString\([^,]+,\s*(?:password|token|uri|path|book|repository)', scheduler, re.IGNORECASE):
        errors.append("backup Worker/UIDT payload carries sensitive or non-opaque parameters")

    controller = named(sources, "BackupController.kt")
    ui = named(sources, "BackupFlowScreen.kt")
    require_tokens(errors, controller + ui, "BKP/SYS-003 governed flow", tuple(f'"{screen}"' for screen in EXPECTED) + (
        "takePersistableUriPermission", "RecoveryPasswordChangeMode", "vaultBackupReady", "BackupIntegrityPresentation",
        "OperationProgressPanel", "hideValueFromSemantics = true", "BackupNetworkPolicy.UNMETERED",
    ))
    if ui.count("hideValueFromSemantics = true") < 2:
        errors.append("both recovery-password fields must hide their values from semantics")
    if re.search(r"^import\s+(?:androidx\.room|app\.ledger\.(?:finance\.data|transfer\.data|core\.database))", ui, re.MULTILINE):
        errors.append("P30 feature UI bypasses application/domain boundaries")
    return errors


def validate_tests_resources() -> list[str]:
    errors: list[str] = []
    test_text = "\n".join(
        path.read_text(encoding="utf-8") for root in (
            "app/src/androidTest", "core/security/src/androidTest", "feature/transfer/src/androidTest",
            "finance/application/src/test", "transfer/domain/src/test", "transfer/data/src/test", "transfer/data/src/androidTest",
        ) for path in sorted((ROOT / root).rglob("*.kt"))
    )
    require_tokens(errors, test_text, "P30 automated evidence", (
        "fortyEightGibibyteSparseDriveSourceStreamsOneChunkAndPersistsLongResumeOffset",
        "zip64StreamingAeadRoundTripPreservesNonAsciiAndNeverNeedsWholeSource",
        "storageExhaustionKeepsTypedInsufficientSpaceFailure",
        "onlyVerifiedManifestPublishesAndSecondSnapshotReusesObjects",
        "interruptedObjectNeverPublishesManifestAndIsReclaimed",
        "retentionFailureAfterImmutablePublicationDoesNotReportCompletedSnapshotAsFailed",
        "repositoryFolderIsIdempotentAndReferenceGcDeletesOnlyManagedStaleArtifacts",
        "interruptionResumesAtPersisted256KiBBoundaryWithoutNewSession",
        "repositoryPublisherUploadsManifestLastAndNeverPublishesTwice",
        "dailyBackupRunsOnlyOnceAfterAChangedRevision",
        "repositoryKeyHasIndependentArgon2idSaltAndDeviceBackgroundEnvelope",
        "automaticVaultBackupReturnsOnlyRecoveryWrappedCiphertext",
        "repositoryAndPortablePublicationAreAtomicAndRevocationIsExplicit",
        "realSqlCipherCatalogPublishesOnlyAfterEncryptedObjectAndManifestVerification",
        "api34ManualDriveBackupSchedulesUserInitiatedJobWithOpaqueOperationIdOnly",
        "bkp001ThroughBkp007AndSys003RequiredStatesRenderInThreeLanguages",
        "recoveryPasswordIsAbsentFromSemanticsAndVaultRequiresWrappedRecoveryKey",
    ))
    localized: list[set[str]] = []
    for folder in ("values", "values-en", "values-ja"):
        names = set(re.findall(r'<string name="([^"]+)"', read(f"feature/transfer/src/main/res/{folder}/strings.xml")))
        localized.append({name for name in names if name.startswith("backup_")})
    if not localized[0] or localized[0] != localized[1] or localized[0] != localized[2]:
        errors.append("P30 backup strings are incomplete across zh-CN/en/ja")
    backup_resources = "\n".join(
        line for folder in ("values", "values-en", "values-ja")
        for line in read(f"feature/transfer/src/main/res/{folder}/strings.xml").splitlines()
        if 'name="backup_' in line
    )
    if "同步" in backup_resources or re.search(r"\bsync(?:ed|ing)?\b", backup_resources, re.IGNORECASE):
        errors.append("backup UI uses forbidden sync wording")
    return errors


def validate_ledgers() -> list[str]:
    errors: list[str] = []
    state = read("docs/初始开发文件存档/implementation/PROJECT_STATE.md")
    evidence = read("docs/初始开发文件存档/implementation/TEST_EVIDENCE.md")
    mapping_path = ROOT / "docs/初始开发文件存档/implementation/P30_BACKUP_MAPPING.md"
    mapping = mapping_path.read_text(encoding="utf-8") if mapping_path.is_file() else ""
    require_tokens(errors, state, "PROJECT_STATE", ("Current stage: P36", "P00—P35 remain VERIFIED"))
    for index in range(1, 9):
        if f"P30-E{index:03d}" not in evidence:
            errors.append(f"TEST_EVIDENCE missing P30-E{index:03d}")
    require_tokens(errors, mapping, "P30 mapping", (
        "48 GiB", "ZIP64", "Streaming AEAD", "Argon2id", "drive.file", "Range", "SAF",
        "30", "Vault", "P30 is `VERIFIED`",
    ))
    with (ROOT / "docs/初始开发文件存档/implementation/SCREEN_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        screens = {row["screen_id"]: row for row in csv.DictReader(handle)}
    for screen_id in EXPECTED:
        row = screens.get(screen_id, {})
        if row.get("status") != "VERIFIED" or "P30" not in row.get("implementation_evidence", "") or "P30-E" not in row.get("verification_evidence", ""):
            errors.append(f"{screen_id} lacks VERIFIED P30 evidence")
    with (ROOT / "docs/初始开发文件存档/implementation/REQUIREMENT_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        requirements = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    for requirement_id in ("REQ-003", "REQ-075", "REQ-084"):
        row = requirements.get(requirement_id, {})
        if row.get("status") not in {"IN_PROGRESS", "VERIFIED"} or "P30" not in row.get("implementation_evidence", "") or "P30-E" not in row.get("verification_evidence", ""):
            errors.append(f"{requirement_id} lacks truthful P30 evidence")
    return errors


def main() -> int:
    errors = validate_contract() + validate_dependencies() + validate_sources() + validate_tests_resources() + validate_ledgers()
    if errors:
        print("P30 backup validation: FAIL", file=sys.stderr)
        for item in errors:
            print(f"- {item}", file=sys.stderr)
        return 1
    print("P30 backup validation: PASS")
    print("screens=8 states=28 encryption=streaming-aead portable=zip64 repository=deduplicated drive=resumable-range saf=persisted")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
