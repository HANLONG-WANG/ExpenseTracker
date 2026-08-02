#!/usr/bin/env python3
"""Fail closed when the P09 key/session/privacy security contract drifts."""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path
from typing import Mapping


ROOT = Path(__file__).resolve().parents[1]
SECURITY_MAIN = ROOT / "core/security/src/main/kotlin/app/ledger/core/security"
TARGET_REQUIREMENTS = {
    "REQ-002",
    "REQ-075",
    "REQ-076",
    "REQ-077",
    "REQ-078",
    "REQ-079",
    "REQ-080",
    "REQ-086",
    "REQ-087",
}
REQUIRED_FILES = {
    "AndroidKeystoreKeys.kt",
    "AppLockAndScreenPrivacy.kt",
    "BookSessionManager.kt",
    "DeviceKeyHierarchy.kt",
    "LedgerTink.kt",
    "RecoveryPasswordWrapping.kt",
    "SecurityEnvelopeStore.kt",
    "SecurityModels.kt",
    "VaultAuthentication.kt",
}
FORBIDDEN = re.compile(r"\b(?:TODO|NotImplemented|UnsupportedOperationException)\b")


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def load_sources() -> dict[str, str]:
    return {
        path.name: read(path)
        for path in sorted(SECURITY_MAIN.glob("*.kt"))
    }


def require_tokens(errors: list[str], source: str, label: str, tokens: tuple[str, ...]) -> None:
    for token in tokens:
        if token not in source:
            errors.append(f"{label} missing {token}")


def validate_sources(sources: Mapping[str, str]) -> list[str]:
    errors: list[str] = []
    missing = REQUIRED_FILES - sources.keys()
    if missing:
        errors.append(f"P09 production files missing: {sorted(missing)}")
    for name, source in sources.items():
        if FORBIDDEN.search(source):
            errors.append(f"placeholder implementation in {name}")
        if re.search(r"(?m)^\s*(?:println|printStackTrace|Log\.|Timber\.)", source):
            errors.append(f"ordinary logging entered security production: {name}")

    keystore = sources.get("AndroidKeystoreKeys.kt", "")
    require_tokens(
        errors,
        keystore,
        "Android Keystore policy",
        (
            "DEVICE_ALIAS_PREFIX",
            "VAULT_ALIAS_PREFIX",
            ".setUserAuthenticationRequired(false)",
            ".setUserAuthenticationRequired(true)",
            ".setInvalidatedByBiometricEnrollment(false)",
            "setUserAuthenticationParameters(",
            "AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL",
            ".canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)",
            "UserNotAuthenticatedException",
            "KeyPermanentlyInvalidatedException",
        ),
    )
    if not re.search(r"setUserAuthenticationParameters\(\s*0,", keystore):
        errors.append("vault Keystore key is not auth-per-use")

    hierarchy = sources.get("DeviceKeyHierarchy.kt", "")
    require_tokens(
        errors,
        hierarchy,
        "device key hierarchy",
        (
            "databaseDek",
            "attachmentRoot",
            "secureSettings",
            "DEVICE_LEDGER_BUNDLE",
            "createAttachmentDataKey",
            "wrapAttachmentDataKey",
            "destroyLocal",
        ),
    )
    vault = sources.get("VaultAuthentication.kt", "")
    require_tokens(
        errors,
        vault,
        "vault authentication hierarchy",
        (
            "VaultKeyHierarchy",
            "beginProvisioning",
            "beginReveal",
            "beginEdit",
            "beginRecoveryExport",
            "BiometricPrompt.CryptoObject",
            "authenticatedCipher.updateAAD(associatedData)",
            "BiometricErrorClassifier",
            "ERROR_NO_BIOMETRICS -> BiometricErrorCode.DEVICE_SECURITY_CHANGED",
            "REVEAL_SECURITY_CODE",
            "EXPOSURE_MILLIS = 30_000L",
            "onApplicationBackgrounded",
            "onApplicationLocked",
        ),
    )
    if "COPY_SECURITY_CODE" in vault:
        errors.append("vault exposes forbidden security-code copy action")
    if re.search(r"prepareVault(?:Wrap|Unwrap)Cipher\([^)]*associatedData", keystore):
        errors.append("auth-bound Keystore operation consumes AAD before CryptoObject authentication")

    recovery = sources.get("RecoveryPasswordWrapping.kt", "")
    require_tokens(
        errors,
        recovery,
        "recovery-password hierarchy",
        (
            "Argon2Parameters.ARGON2_id",
            "ARGON2_VERSION_13",
            "MINIMUM_MEMORY_KIB: Int = 64 * 1024",
            "MINIMUM_ITERATIONS: Int = 3",
            "CURRENT_FORMAT_VERSION: Int = 1",
            "DEFAULT_TARGET_MILLIS = 500L",
            "RecoveryAuthenticationFailed",
            "RecoveryPassword",
        ),
    )
    models = sources.get("SecurityModels.kt", "")
    require_tokens(
        errors,
        models,
        "sensitive memory and associated data",
        (
            "class SecretBytes",
            "class RecoveryPassword",
            "value.fill(0)",
            "value.fill('\\u0000')",
            '"vault-field"',
            '"recovery-bundle"',
            '"attachment-data-key"',
        ),
    )
    tink = sources.get("LedgerTink.kt", "")
    require_tokens(
        errors,
        tink,
        "Tink primitives",
        ("AES256_GCM", "AES256_GCM_HKDF_1MB", "Aead", "StreamingAead"),
    )

    session = sources.get("BookSessionManager.kt", "")
    require_tokens(
        errors,
        session,
        "book session runtime",
        (
            "data object Locked",
            "data object Opening",
            "data class Maintenance",
            "data class RecoveryRequired",
            "data class Ready",
            "SqlCipherBookDatabaseResourceFactory",
            "HeadlessBookLease",
            "HeadlessLeaseCapability",
            "activeHeadlessLeaseCount",
            "vaultExposureRegistry.onApplicationLocked()",
        ),
    )
    lease = session.split("class HeadlessBookLease", 1)[-1]
    if "LedgerDatabase" in lease or "BookDatabaseResource" in lease:
        errors.append("HeadlessBookLease exposes database/resource authority")

    privacy = sources.get("AppLockAndScreenPrivacy.kt", "")
    require_tokens(
        errors,
        privacy,
        "app lock and screen privacy",
        (
            "enabled: Boolean = false",
            "Immediately",
            "OneMinute",
            "FiveMinutes",
            "FifteenMinutes",
            "elapsedRealtimeMillis",
            "obscureRecentTasks",
            "globalFlagSecure",
            "vaultVisible",
            "FLAG_SECURE",
            "setRecentsScreenshotEnabled",
        ),
    )

    catalog = read(ROOT / "gradle/libs.versions.toml")
    require_tokens(
        errors,
        catalog,
        "security dependency catalog",
        ('tink = "1.23.0"', 'bouncycastle = "1.84"', 'biometric = "1.1.0"'),
    )
    return errors


def validate_tests() -> list[str]:
    errors: list[str] = []
    unit = "\n".join(read(path) for path in sorted((ROOT / "core/security/src/test").rglob("*.kt")))
    device = "\n".join(read(path) for path in sorted((ROOT / "core/security/src/androidTest").rglob("*.kt")))
    for token in (
        "wrongAssociatedData",
        "RecoveryAuthenticationFailed",
        "clearExpired",
        "UI locking keeps an authorized headless database lease alive",
        "invalidated device key transitions to recovery required",
        "headless startup exception closes the resource and enters sanitized recovery",
        "lock enablement requires authentication",
    ):
        if token not in unit:
            errors.append(f"P09 JVM evidence missing {token}")
    for token in (
        "deviceLedgerKeysOpenEncryptedDatabaseWithoutUserAuthenticationAndReopen",
        "deletingDeviceKekRequiresRecoveryAndNeverRegeneratesOverExistingEnvelope",
        "vaultProvisionAndExportEachRequireARealAuthenticatedCryptoObject",
        "locksettings set-pin",
        "AndroidBiometricPromptGateway",
        "BiometricErrorClassifier.classify(BiometricPrompt.ERROR_NO_BIOMETRICS)",
        "locksettings clear",
        "DeviceSecurityUnavailable",
    ):
        if token not in device:
            errors.append(f"P09 Android device evidence missing {token}")
    return errors


def validate_ledgers() -> list[str]:
    errors: list[str] = []
    project_state = read(ROOT / "docs/implementation/PROJECT_STATE.md")
    current_stage = re.search(r"Current stage: P(\d{2})", project_state)
    if "| P09 | VERIFIED |" not in project_state or "### P09 result" not in project_state:
        errors.append("PROJECT_STATE does not record P09 VERIFIED and its result")
    evidence = read(ROOT / "docs/implementation/TEST_EVIDENCE.md")
    for index in range(1, 7):
        if f"P09-E{index:03d}" not in evidence:
            errors.append(f"TEST_EVIDENCE missing P09-E{index:03d}")
    mapping = read(ROOT / "docs/implementation/P09_SECURITY_RUNTIME_MAPPING.md")
    for token in ("DeviceLedgerKEK", "VaultAuthenticationKEK", "Argon2id", "HeadlessBookLease", "FLAG_SECURE"):
        if token not in mapping:
            errors.append(f"P09 mapping missing {token}")
    with (ROOT / "docs/implementation/REQUIREMENT_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        rows = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    for requirement_id in TARGET_REQUIREMENTS:
        row = rows.get(requirement_id)
        expected_status = (
            "VERIFIED"
            if current_stage is not None and int(current_stage.group(1)) >= 11 and requirement_id == "REQ-087"
            else "IN_PROGRESS"
        )
        if row is None or row["status"] != expected_status:
            errors.append(f"{requirement_id} must retain its truthful P09-or-later status")
        elif "P09" not in row["implementation_evidence"] or "P09-E" not in row["verification_evidence"]:
            errors.append(f"{requirement_id} lacks P09 implementation/verification evidence")
    with (ROOT / "docs/implementation/SCREEN_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        screens = list(csv.DictReader(handle))
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
    if len(screens) != 215 or any(
        row["status"] != p11_promotions.get(row["screen_id"], "NOT_STARTED") for row in screens
    ):
        errors.append("screen coverage contains a promotion outside the completed P11 scope")
    domain = read(ROOT / "docs/implementation/DOMAIN_AND_SCHEMA_COVERAGE.md")
    if "| ADR-016 | Ledger, vault and recovery-password key hierarchies are separate | VERIFIED" not in domain:
        errors.append("ADR-016 is not VERIFIED")
    if "| ADR-017 | App lock is UI access control; vault uses a cryptographic authentication gate | VERIFIED" not in domain:
        errors.append("ADR-017 is not VERIFIED")
    return errors


def main() -> int:
    sources = load_sources()
    errors = validate_sources(sources) + validate_tests() + validate_ledgers()
    if errors:
        print("P09 security validation: FAIL", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("P09 security validation: PASS")
    unit_cases = sum(read(path).count("@Test") for path in (ROOT / "core/security/src/test").rglob("*.kt"))
    device_cases = sum(read(path).count("@Test") for path in (ROOT / "core/security/src/androidTest").rglob("*.kt"))
    print(f"production_files={len(sources)} jvm_cases={unit_cases} android_device_cases={device_cases}")
    print("screen_rows_total=215 p11_promoted=24 visual_drafts=excluded")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
