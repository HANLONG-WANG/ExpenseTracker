#!/usr/bin/env python3
"""Reject P31 restore, merge, purge, recovery, UI, or evidence drift."""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
EXPECTED = {
    "RST-001": ("restore/source", [], {"content", "loadingRemote", "permissionError"}),
    "RST-002": ("restore/{operationId}/password", ["operationId:StableId"], {"editing", "verifying", "wrongPassword", "lockedDelay"}),
    "RST-003": ("restore/{operationId}/inspect", ["operationId:StableId"], {"checking", "compatible", "incompatibleBook", "incompatibleCurrency", "corrupt"}),
    "RST-004": ("restore/{operationId}/mode", ["operationId:StableId"], {"content", "mergeUnavailable"}),
    "RST-005": ("restore/{operationId}/conflicts", ["operationId:StableId"], {"content", "resolved", "unresolved", "purgeTombstoneWins"}),
    "RST-006": ("restore/{operationId}/progress", ["operationId:StableId"], {"downloading", "verifying", "migrating", "rebuilding", "swapping", "failedRollback", "succeeded"}),
    "RST-007": ("restore/{operationId}/result", ["operationId:StableId"], {"success", "rolledBack", "failed"}),
    "JRN-012": ("journal/purge/{transactionId}", ["transactionId:StableId"], {"eligible", "notEligible", "verifying", "purging"}),
    "CLR-002": ("backup/delete-cloud", [], {"content", "authRequired", "deleting", "failed"}),
    "G-004": ("maintenance", ["operationId:StableId"], {"preparing", "running", "nonCancelable", "cancelable", "failed", "succeeded"}),
    "G-005": ("recovery-required", [], {"corrupt", "keyUnavailable", "projectionFailure", "restoreAvailable", "noBackup"}),
}
PLACEHOLDER = re.compile(r"\b(?:TODO|NotImplemented|UnsupportedOperationException)\b")


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def source_map() -> dict[str, str]:
    roots = (
        "app/src/main/kotlin", "core/security/src/main/kotlin", "feature/transfer/src/main/kotlin",
        "finance/application/src/main/kotlin", "finance/data/src/main/kotlin", "finance/domain/src/main/kotlin",
        "transfer/data/src/main/kotlin", "transfer/domain/src/main/kotlin",
    )
    return {
        path.relative_to(ROOT).as_posix(): path.read_text(encoding="utf-8")
        for root in roots for path in sorted((ROOT / root).rglob("*.kt"))
    }


def named(sources: dict[str, str], filename: str) -> str:
    return next((source for path, source in sources.items() if path.endswith(filename)), "")


def require_tokens(errors: list[str], text: str, label: str, tokens: tuple[str, ...]) -> None:
    for token in tokens:
        if token not in text:
            errors.append(f"{label} missing {token}")


def validate_contract() -> list[str]:
    contract = yaml.safe_load(read("docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml"))
    screens = {item["id"]: item for item in contract["screens"]}
    errors: list[str] = []
    for screen_id, (route, params, states) in EXPECTED.items():
        actual = screens.get(screen_id, {})
        if actual.get("route") != route or actual.get("params", []) != params or set(actual.get("requiredStates", [])) != states:
            errors.append(f"{screen_id} route/params/requiredStates drift")
    if sum(len(item[2]) for item in EXPECTED.values()) != 47:
        errors.append("P31 required-state baseline must remain exactly 47")
    return errors


def validate_sources(sources: dict[str, str] | None = None) -> list[str]:
    sources = source_map() if sources is None else sources
    errors: list[str] = []
    required = {
        "RestoreMaterializer.kt", "RestoreCoordinator.kt", "DriveBackupRepositoryDownloader.kt",
        "BackupMergeModel.kt", "CommitGraphMergePlanner.kt", "SqlCipherMergeSessionStore.kt",
        "RestoreLedgerApplication.kt", "MergeRestoreApplication.kt", "ControlledPurgeApplication.kt",
        "SecureRoomRestoreLedgerApplicationPort.kt", "SecureRoomMergeRestoreApplicationPort.kt",
        "RoomMergeImporter.kt", "SecureRoomControlledPurgeApplicationPort.kt", "RoomLogicalPurgeValidator.kt",
        "AndroidRestoreArtifactSwapPort.kt", "AndroidPreRestoreSafetySnapshotPort.kt", "AndroidMergeLedgerPort.kt",
        "RestoreController.kt", "LocalBookArtifactCleaner.kt", "DriveSnapshotDeletionService.kt", "RestoreFlowScreen.kt",
    }
    selected = {path: source for path, source in sources.items() if Path(path).name in required}
    missing = required - {Path(path).name for path in selected}
    if missing:
        errors.append(f"P31 production files missing: {sorted(missing)}")
    for path, source in selected.items():
        if PLACEHOLDER.search(source):
            errors.append(f"placeholder in {path}")

    materializer = named(sources, "RestoreMaterializer.kt")
    require_tokens(errors, materializer, "bounded authenticated restore materialization", (
        "newDecryptingStream", "RestoreObjectStreamVerifier.copyAndVerify", "MessageDigest.getInstance(\"SHA-256\")",
        "COPY_BUFFER_BYTES", "RestoreFailure.WrongPassword", "RestoreFailure.CorruptObject",
        "RestoreFailure.Cancelled", "deleteRecursivelyScoped()",
    ))
    downloader = named(sources, "DriveBackupRepositoryDownloader.kt") + named(sources, "DriveResumableBackupClient.kt")
    require_tokens(errors, downloader, "Drive recoverable Range download", (
        "downloadRange", '.header("Range"', ".partial", "partial.length()", "checkpoint(remote.name",
    ))

    restore = named(sources, "SecureRoomRestoreLedgerApplicationPort.kt") + named(sources, "RoomLedgerIntegrityAudit.kt")
    require_tokens(errors, restore, "shadow migration, audit and crash-safe exchange", (
        "LedgerMigrations.CURRENT_VERSION", "DatabaseIntegrityAudit", "RoomProjectionEngine().rebuildAll",
        "StandardCopyOption.ATOMIC_MOVE", "EXCHANGE_PHASE_PREPARED", "EXCHANGE_PHASE_FINALIZED",
        "copyLiveRecoveryFiles", "rollbackBlocking", "recoverInterrupted", "SQLiteFullException",
        "foreignKeysValid", "journalsBalanced", "projectionsValid",
    ))
    coordinator = named(sources, "RestoreCoordinator.kt")
    require_tokens(errors, coordinator, "replace/merge non-cancellable boundary", (
        "PreRestoreSafetySnapshotPort", "withContext(NonCancellable)", "RestoreState.EXCHANGING",
        "ledger.rollback", "ledger.validateLive", "ledger.finalizeExchange", "resolutionsRespectPurge",
    ))
    artifacts = named(sources, "AndroidRestoreArtifactSwapPort.kt")
    require_tokens(errors, artifacts, "settings/attachment/vault atomic swap", (
        "StandardCopyOption.ATOMIC_MOVE", "restore-artifacts-", "attachmentsSafety", "vaultSafety", "recover(operationId",
    ))

    merge = named(sources, "CommitGraphMergePlanner.kt")
    require_tokens(errors, merge, "three-way commit graph merge", (
        "commonAncestor", "ancestorVersions", "TRANSACTION_REVISION_FORK", "DELETE_VERSUS_EDIT",
        "KeepPurgeTombstone", "BookMismatch", "BaseCurrencyMismatch",
    ))
    if re.search(r"(?:timestamp|createdAt|modifiedAt).*(?:>|<)|(?:>|<).*(?:timestamp|createdAt|modifiedAt)", merge, re.IGNORECASE):
        errors.append("merge planner contains timestamp ordering")
    merge_port = named(sources, "SecureRoomMergeRestoreApplicationPort.kt") + named(sources, "RoomMergeImporter.kt")
    require_tokens(errors, merge_port, "coordinator-owned shadow merge commit", (
        "DefaultFinancialMutationCoordinator", "MergeRestoreCommand", "beforeCommitSideEffect",
        "RoomMergeImporter", "revisionBeforeMerge", "importCommitGraph", "registerPreparedMerge",
    ))
    repository = named(sources, "RoomFinancialCommitRepository.kt")
    require_tokens(errors, repository, "single finance commit boundary", (
        "command is MergeRestoreCommand", "commitPrivacyPurge", "beforeCommitSideEffect::apply",
    ))

    purge = named(sources, "RoomLogicalPurgeValidator.kt") + named(sources, "SecureRoomControlledPurgeApplicationPort.kt") + repository + named(sources, "RoomFinancialPlanWriter.kt")
    require_tokens(errors, purge, "append-only controlled privacy purge", (
        "DefaultFinancialMutationCoordinator", "loadForPurge", "RoomLogicalPurgeValidator", "revalidate",
        "nonZeroAccountNets", "nonZeroBaseNets", "nonZeroEffectNets", "dependencyReferences",
        "backupAttachmentReads", "immutable revisions and", "financial facts are never updated or deleted",
        "purge_tombstone",
    ))
    if re.search(r"DELETE\s+FROM\s+(?:business_transaction|transaction_revision|entity_revision|journal_entry|posting)", purge, re.IGNORECASE):
        errors.append("controlled privacy purge must never physically delete immutable facts")
    model = named(sources, "BookAndAudit.kt")
    tombstone = re.search(r"data class PurgeTombstone\(([\s\S]*?)\n\)", model)
    if not tombstone or re.search(r"amount|text|note|description", tombstone.group(1), re.IGNORECASE):
        errors.append("purge tombstone carries sensitive amount/text or is missing")

    cleaner = named(sources, "LocalBookArtifactCleaner.kt") + named(sources, "AppRootViewModel.kt")
    require_tokens(errors, cleaner, "G-004/G-005 local clear boundary", (
        "cancelAllWork().result.get()", "clearLocalBook", "attachment_objects", "backup-repositories",
        "pre-restore-safety-v1", "restore-work-v1", "backup-progress-v1", "DERIVED_DATABASE",
    ))
    cloud = named(sources, "DriveSnapshotDeletionService.kt")
    require_tokens(errors, cloud, "authenticated cloud backup deletion", (
        "deleteRepositoryFile", "manifest", "deleteSnapshot", "deleteUnreferencedObject",
    ))

    ui = named(sources, "RestoreFlowScreen.kt")
    controller_text = named(sources, "RestoreController.kt") + named(sources, "AppRootViewModel.kt") + named(sources, "AppRootScreen.kt")
    governed_screens = tuple(f'"{screen}"' for screen in EXPECTED if screen.startswith("RST-") or screen in {"JRN-012", "CLR-002"})
    require_tokens(errors, ui + controller_text, "RST/JRN/CLR/G governed UI", governed_screens + (
        "hideValueFromSemantics = true", "purgeTombstoneWins", "OperationProgressPanel",
        "RestoreState.EXCHANGING", "takePersistableUriPermission", "onAuthenticateCloudDelete",
    ))
    if re.search(r"^import\s+(?:androidx\.room|app\.ledger\.(?:finance\.data|transfer\.data|core\.database))", ui, re.MULTILINE):
        errors.append("P31 feature UI bypasses application/domain boundaries")
    return errors


def validate_tests_resources() -> list[str]:
    errors: list[str] = []
    roots = (
        "app/src/androidTest", "feature/transfer/src/androidTest", "finance/data/src/androidTest",
        "transfer/domain/src/test", "transfer/data/src/test",
    )
    tests = "\n".join(path.read_text(encoding="utf-8") for root in roots for path in sorted((ROOT / root).rglob("*.kt")))
    require_tokens(errors, tests, "P31 automated evidence", (
        "closest graph ancestor wins without looking at timestamps", "same transaction fork is never resolved by timestamp",
        "purge tombstone always prevents resurrection and carries no entity content",
        "twenty gibibyte simulated restore stream completes with a fixed bounded buffer",
        "wrong password corruption and cancellation remove all temporary plaintext",
        "everyExchangeFaultIncludingStorageFullRollsBackDatabaseKeyAndArtifacts",
        "processDeathBeforeFinalizeRollsBackButAfterFinalizeKeepsVerifiedRestore",
        "unsupportedOrCorruptSourceNeverChangesLiveLedger",
        "unreadableRecoveryGateCanReplaceAndFaultRollsBackExactCorruptBytes",
        "preRestoreQuarantineSnapshotStreamsAndVerifiesDatabaseSettingsAttachmentsAndVaultCiphertext",
        "divergentSameBookBranchesCreateTwoParentMergeCommitWithoutTimestampOverwrite",
        "controlledPurgeIsAtomicIdempotentAndTombstoneContainsNoFinancialPayload",
        "g004ClearRemovesEveryAppOwnedDerivedArtifactButLeavesUserControlledExternalFiles",
        "rst001ThroughRst007AndClr002StatesRenderAcrossThreeLanguagesAndAccessibilitySizes",
        "passwordNeverEntersSemanticsAndPurgeConflictCannotOfferResurrection",
        "contractDerivedRestoreScreenshotsMatchPixelBaselines",
        "manifestsDisappearBeforeOnlyUnreferencedObjectsAreCollected",
    ))
    localized: list[set[str]] = []
    for folder in ("values", "values-en", "values-ja"):
        names = set(re.findall(r'<string name="([^"]+)"', read(f"feature/transfer/src/main/res/{folder}/strings.xml")))
        localized.append({name for name in names if name.startswith("restore_") or name.startswith("clear_cloud_")})
    if not localized[0] or localized[0] != localized[1] or localized[0] != localized[2]:
        errors.append("P31 restore/cloud-clear strings are incomplete across zh-CN/en/ja")
    return errors


def validate_ledgers() -> list[str]:
    errors: list[str] = []
    state = read("docs/implementation/PROJECT_STATE.md")
    evidence = read("docs/implementation/TEST_EVIDENCE.md")
    mapping_path = ROOT / "docs/implementation/P31_RESTORE_MERGE_PURGE_MAPPING.md"
    mapping = mapping_path.read_text(encoding="utf-8") if mapping_path.is_file() else ""
    require_tokens(errors, state, "PROJECT_STATE", ("Current stage: P36", "P00—P35 remain VERIFIED"))
    for index in range(1, 9):
        if f"P31-E{index:03d}" not in evidence:
            errors.append(f"TEST_EVIDENCE missing P31-E{index:03d}")
    require_tokens(errors, mapping, "P31 mapping", (
        "20 GiB", "SQLCipher", "three-way", "tombstone", "PURGE", "NonCancellable", "P31 is `VERIFIED`",
    ))
    with (ROOT / "docs/implementation/SCREEN_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        screens = {row["screen_id"]: row for row in csv.DictReader(handle)}
    for screen_id in EXPECTED:
        row = screens.get(screen_id, {})
        if row.get("status") != "VERIFIED" or "P31" not in row.get("implementation_evidence", "") or "P31-E" not in row.get("verification_evidence", ""):
            errors.append(f"{screen_id} lacks VERIFIED P31 evidence")
    with (ROOT / "docs/implementation/REQUIREMENT_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        requirements = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    for requirement_id in ("REQ-003", "REQ-032", "REQ-076", "REQ-079", "REQ-089"):
        row = requirements.get(requirement_id, {})
        if row.get("status") not in {"IN_PROGRESS", "VERIFIED"} or "P31" not in row.get("implementation_evidence", "") or "P31-E" not in row.get("verification_evidence", ""):
            errors.append(f"{requirement_id} lacks truthful P31 evidence")
    return errors


def main() -> int:
    errors = validate_contract() + validate_sources() + validate_tests_resources() + validate_ledgers()
    if errors:
        print("P31 restore/merge/purge validation failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("P31 restore/merge/purge validation passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
