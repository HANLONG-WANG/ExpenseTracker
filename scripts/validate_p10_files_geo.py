#!/usr/bin/env python3
"""Fail closed when the P10 encrypted attachment/location/map contract drifts."""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path
from typing import Mapping

import yaml


ROOT = Path(__file__).resolve().parents[1]
SOURCE_ROOTS = (
    Path("core/files/src/main"),
    Path("core/geo/src/main"),
    Path("feature/record/src/main"),
    Path("core/security/src/main/kotlin/app/ledger/core/security"),
    Path("finance/application/src/main/kotlin"),
)
TARGET_REQUIREMENTS = {"REQ-053", "REQ-054", "REQ-055", "REQ-056", "REQ-057"}
VERIFIED_REQUIREMENTS = {"REQ-054", "REQ-055", "REQ-057"}
FOUNDATION_REQUIREMENTS = TARGET_REQUIREMENTS - VERIFIED_REQUIREMENTS
VERIFIED_SCREENS = {"ATT-001", "ATT-002", "ATT-003", "SYS-001"}
PARTIAL_SCREENS = {"REC-009", "REC-010"}
FUTURE_SCREENS = {"ANA-011", "ANA-012"}
REQUIRED_STATES = {
    "REC-009": {"locating", "located", "permissionDenied", "timeout", "manual", "mapUnavailable"},
    "REC-010": {"content", "empty", "importing", "failed"},
    "ATT-001": {"loading", "image", "unsupportedPreview", "decryptError"},
    "ATT-002": {"content"},
    "ATT-003": {"editing", "invalid"},
    "SYS-001": {"firstAsk", "denied", "permanentlyDenied"},
}
REQUIRED_PRODUCTION_FILES = {
    "AttachmentDatabaseCatalog.kt",
    "AttachmentModels.kt",
    "AttachmentScreens.kt",
    "EncryptedAttachmentCoil.kt",
    "EncryptedAttachmentObjectStore.kt",
    "SecureAttachmentProvider.kt",
    "ForegroundLocationClient.kt",
    "LedgerMap.kt",
    "LocationPermissionDialog.kt",
    "RecordInfrastructureScreens.kt",
}
FORBIDDEN_PLACEHOLDER = re.compile(r"\b(?:TODO|NotImplemented|UnsupportedOperationException)\b")


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def load_sources() -> dict[str, str]:
    return {
        path.relative_to(ROOT).as_posix(): read(path)
        for source_root in SOURCE_ROOTS
        for path in sorted((ROOT / source_root).rglob("*.kt"))
    }


def named(sources: Mapping[str, str], filename: str) -> str:
    return next((source for path, source in sources.items() if path.endswith(filename)), "")


def require_tokens(errors: list[str], source: str, label: str, tokens: tuple[str, ...]) -> None:
    for token in tokens:
        if token not in source:
            errors.append(f"{label} missing {token}")


def validate_sources(sources: Mapping[str, str]) -> list[str]:
    errors: list[str] = []
    missing = REQUIRED_PRODUCTION_FILES - {Path(path).name for path in sources}
    if missing:
        errors.append(f"P10 production files missing: {sorted(missing)}")
    for path, source in sources.items():
        if FORBIDDEN_PLACEHOLDER.search(source):
            errors.append(f"placeholder production implementation in {path}")
        if re.search(r"\b(?:println|printStackTrace|android\.util\.Log|Timber\.)", source):
            errors.append(f"ordinary logging entered P10 production: {path}")
        if "ACCESS_BACKGROUND_LOCATION" in source:
            errors.append(f"background location entered P10 production: {path}")

    object_store = named(sources, "EncryptedAttachmentObjectStore.kt")
    require_tokens(
        errors,
        object_store,
        "encrypted object store",
        (
            "request.content.openStream()",
            "LedgerTink::streamingAead",
            'MessageDigest.getInstance("SHA-256")',
            "COPY_BUFFER_BYTES = 64 * 1024",
            "catalog.findBlob(digestAndSize.hash, digestAndSize.size)",
            "storage.moveIntoObjectStore",
            "recoverInterruptedImports",
            "runGarbageCollection",
            "generateEncryptedThumbnail",
            "AtomicMoveNotSupportedException",
            "PlatformCryptographicRandomSource",
        ),
    )
    if re.search(r"\b(?:externalCacheDir|getExternalFilesDir|MediaStore\.|getExternalStorage)", object_store):
        errors.append("attachment object store uses shared/external plaintext storage")

    catalog = named(sources, "AttachmentDatabaseCatalog.kt")
    require_tokens(
        errors,
        catalog,
        "attachment catalog",
        (
            "database.inLedgerTransaction",
            "plaintext_sha256 = ? AND plaintext_size = ?",
            "reference_count_projection",
            "transaction_revision_attachment",
            "backup_object",
            "blob_gc_candidate",
            "activeAttachments",
            "WHERE a.status = ?",
        ),
    )
    coil = named(sources, "EncryptedAttachmentCoil.kt")
    require_tokens(
        errors,
        coil,
        "encrypted Coil integration",
        (
            "EncryptedAttachmentFetcher",
            ".diskCache(null)",
            "diskCachePolicy(CachePolicy.DISABLED)",
            "networkCachePolicy(CachePolicy.DISABLED)",
            "memoryCache?.clear()",
            "SecureAttachmentImagePreview",
        ),
    )
    provider = named(sources, "SecureAttachmentProvider.kt")
    require_tokens(
        errors,
        provider,
        "secure attachment provider",
        (
            "ExternalOpenConfirmation",
            "consumed = true",
            "grants.remove(token)",
            "ParcelFileDescriptor.createReliablePipe()",
            "revokeUriPermission",
            "FLAG_GRANT_READ_URI_PERMISSION",
            "TOKEN_BYTES = 24",
            "AUTHORIZATION_LIFETIME_MILLIS = 60_000L",
            "onApplicationLocked",
        ),
    )
    session = named(sources, "SecureBookAttachmentObjectPort.kt")
    require_tokens(
        errors,
        session,
        "visible attachment session",
        (
            "openSession",
            "SecureBookAttachmentSession",
            "SecureAttachmentImageLoader",
            "SecureAttachmentProviderProcess.install",
            "externalOpenConfirmation",
            "store.rename",
            "activeMetadata",
        ),
    )
    record = named(sources, "OrdinaryRecordScreens.kt")
    require_tokens(
        errors,
        record,
        "cross-transaction attachment reuse",
        ("onReuseAttachment", "record_attachments_reuse_title", "attachment.id"),
    )

    security_models = named(sources, "SecurityModels.kt")
    require_tokens(
        errors,
        security_models,
        "attachment associated data",
        ('"attachment-content"', '"attachment-thumbnail"'),
    )
    ports = named(sources, "RepositoryPorts.kt")
    require_tokens(
        errors,
        ports,
        "attachment/location application ports",
        (
            "AttachmentContentSource",
            "fun openStream(): InputStream",
            "declaredSize: Long?",
            "ForegroundLocationPort",
            "CapturedLocationProvider",
        ),
    )

    location = named(sources, "ForegroundLocationClient.kt")
    require_tokens(
        errors,
        location,
        "foreground location runtime",
        (
            "FusedLocationProviderClient",
            "GoogleApiAvailability",
            "LocationManagerEngine",
            "withTimeoutOrNull",
            "MAXIMUM_SAVE_WAIT_MILLIS: Long = 3_000L",
            "ForegroundLocationSaveSession",
            "deferred?.cancel()",
            "LocationSaveDisposition.TIMED_OUT",
            "BigDecimal.valueOf(latitude)",
        ),
    )
    map_source = named(sources, "LedgerMap.kt")
    require_tokens(
        errors,
        map_source,
        "LedgerMap runtime",
        (
            "MapLibre.getInstance",
            "LifecycleEventObserver",
            "LedgerMapMode.CLUSTERS",
            "LedgerMapMode.HEATMAP",
            "LedgerMapMode.SINGLE_POINTS",
            "GeoJsonSource",
            "HeatmapLayer",
            "AccessibleMapRows",
            "styleConfiguration.attribution",
            "isAttributionEnabled = true",
            "isLogoEnabled = true",
        ),
    )
    return errors


def validate_manifests_and_dependencies() -> list[str]:
    errors: list[str] = []
    file_manifest = read(ROOT / "core/files/src/main/AndroidManifest.xml")
    geo_manifest = read(ROOT / "core/geo/src/main/AndroidManifest.xml")
    require_tokens(
        errors,
        file_manifest,
        "attachment provider manifest",
        ('android:exported="false"', 'android:grantUriPermissions="true"', ".secure-attachments"),
    )
    require_tokens(
        errors,
        geo_manifest,
        "foreground location manifest",
        ("android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION"),
    )
    if "ACCESS_BACKGROUND_LOCATION" in geo_manifest:
        errors.append("core:geo manifest requests background location")
    catalog = read(ROOT / "gradle/libs.versions.toml")
    require_tokens(
        errors,
        catalog,
        "P10 frozen dependency catalog",
        ('maplibre = "13.4.1"', 'coil = "3.5.0"', 'play-services-location = "21.3.0"', 'tink = "1.23.0"'),
    )
    geo_build = read(ROOT / "core/geo/build.gradle.kts")
    if "implementation(libs.maplibre)" not in geo_build or "api(libs.maplibre)" in geo_build:
        errors.append("MapLibre must remain an implementation detail of core:geo")
    policy = read(ROOT / "build-logic/src/main/kotlin/app/ledger/buildlogic/SourcePolicyEngine.kt")
    for rule in (
        "ARCH-FEATURE-INFRASTRUCTURE",
        "ARCH-ATTACHMENT-SDK",
        "ARCH-GEO-SDK",
        "PRIVACY-BACKGROUND-LOCATION",
        "PRIVACY-FILES-SHARED-STORAGE",
    ):
        if rule not in policy:
            errors.append(f"P10 static policy missing {rule}")
    return errors


def validate_contract_and_tests() -> list[str]:
    errors: list[str] = []
    screen_doc = yaml.safe_load(read(ROOT / "docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml"))
    screens = {screen["id"]: screen for screen in screen_doc["screens"]}
    for screen_id, required_states in REQUIRED_STATES.items():
        actual = set(screens.get(screen_id, {}).get("requiredStates", []))
        if actual != required_states:
            errors.append(f"{screen_id} requiredStates drift: {sorted(actual)}")

    tests = "\n".join(
        read(path)
        for root in (
            "core/files/src/test",
            "core/files/src/androidTest",
            "core/geo/src/test",
            "core/geo/src/androidTest",
            "feature/record/src/androidTest",
        )
        for path in sorted((ROOT / root).rglob("*.kt"))
    )
    for test_name in (
        "largeImportStreamsEncryptsDeduplicatesRenamesAndGarbageCollects",
        "cancellationDatabaseFailureAndInterruptedProcessLeaveNoReferencedMissingObject",
        "thumbnailAndProviderRemainEncryptedPrivateOneTimeAndLockAware",
        "previewExternalOpenAndRenameRenderEveryRequiredStateAtLargeFont",
        "prefetchUsesOnlyTheRemainingThreeSecondBudgetAndNeverSupplementsAfterTimeout",
        "deniedPermissionReturnsTypedFailureWithoutTouchingProviders",
        "missingPlayServicesUsesLocationManagerBoundaryAndFreezesGpsEvidence",
        "expiredDeadlineReturnsNoLocationAndManifestRequestsNoBackgroundAccess",
        "actualMapLibreLifecycleRendersAllOverlayModesWithAttributionAndSanitizedSemantics",
        "unavailableMapShowsAccessibleListAndPermissionDialogCoversEveryRequiredState",
        "rec009RendersAllSixStatesAtCompactWidthAndTwoHundredPercentFont",
        "rec010RendersContentEmptyImportingAndFailedStates",
    ):
        if test_name not in tests:
            errors.append(f"P10 automated evidence missing {test_name}")
    return errors


def validate_ledgers() -> list[str]:
    errors: list[str] = []
    state = read(ROOT / "docs/implementation/PROJECT_STATE.md")
    evidence = read(ROOT / "docs/implementation/TEST_EVIDENCE.md")
    mapping = read(ROOT / "docs/implementation/P10_FILES_GEO_MAPPING.md")
    if "| P10 | VERIFIED |" not in state or "### P10 result" not in state:
        errors.append("PROJECT_STATE does not record P10 VERIFIED and its result")
    for number in range(1, 8):
        if f"P10-E{number:03d}" not in evidence:
            errors.append(f"TEST_EVIDENCE missing P10-E{number:03d}")
    for token in ("Streaming AEAD", "SecureAttachmentProvider", "Fused Location Provider", "LedgerMap", "REC-009"):
        if token not in mapping:
            errors.append(f"P10 mapping missing {token}")

    with (ROOT / "docs/implementation/REQUIREMENT_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        requirements = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    for requirement_id in VERIFIED_REQUIREMENTS:
        row = requirements.get(requirement_id)
        if row is None or row["status"] != "VERIFIED":
            errors.append(f"{requirement_id} must be VERIFIED by P10")
        elif "P10" not in row["implementation_evidence"] or "P10-E" not in row["verification_evidence"]:
            errors.append(f"{requirement_id} lacks P10 evidence")
    for requirement_id in FOUNDATION_REQUIREMENTS:
        row = requirements.get(requirement_id)
        if row is None or row["status"] != "IN_PROGRESS":
            errors.append(f"{requirement_id} must remain truthful IN_PROGRESS after P10 foundation")
        elif "P10" not in row["implementation_evidence"] or "P10-E" not in row["verification_evidence"]:
            errors.append(f"{requirement_id} lacks P10 foundation evidence")

    with (ROOT / "docs/implementation/SCREEN_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        screens = {row["screen_id"]: row for row in csv.DictReader(handle)}
    for screen_id in VERIFIED_SCREENS:
        row = screens.get(screen_id)
        if row is None or row["status"] != "VERIFIED" or "P10-E" not in row["verification_evidence"]:
            errors.append(f"{screen_id} must be VERIFIED by P10")
    for screen_id in PARTIAL_SCREENS:
        row = screens.get(screen_id)
        if row is None or row["status"] != "IN_PROGRESS" or "P10-E" not in row["verification_evidence"]:
            errors.append(f"{screen_id} must record its verified P10 infrastructure states as IN_PROGRESS")
    for screen_id in FUTURE_SCREENS:
        if screens.get(screen_id, {}).get("status") != "NOT_STARTED":
            errors.append(f"{screen_id} must remain NOT_STARTED until P27")
    return errors


def main() -> int:
    errors = (
        validate_sources(load_sources())
        + validate_manifests_and_dependencies()
        + validate_contract_and_tests()
        + validate_ledgers()
    )
    if errors:
        print("P10 attachment/location/map validation: FAIL", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("P10 attachment/location/map validation: PASS")
    print("requirements=5 states=18 jvm_cases=6 device_cases=11 visual_drafts=excluded")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
