#!/usr/bin/env python3
"""Reject P32 Vault, privacy diagnostics, app-lock, clearing, UI, or evidence drift."""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
EXPECTED = {
    "G-002": ("app-lock", [], {"biometricAvailable", "credentialOnly", "authenticating", "authFailed", "lockedOut"}),
    "VLT-001": ("vault", [], {"locked", "unlockedSession", "deviceSecurityMissing", "empty"}),
    "VLT-002": ("vault/card/{cardId}", ["cardId:StableId"], {"masked", "authenticating", "revealed", "autoHidden", "authFailed"}),
    "VLT-003": ("vault/card/{cardId}/edit", ["cardId:StableId"], {"authRequired", "editing", "saving"}),
    "VLT-004": ("vault/auth/{purpose}", ["purpose:REVEAL_PAN|COPY_PAN|REVEAL_CVC|EDIT_VAULT|DELETE_CLOUD"], {"prompt", "success", "failure", "cancelled"}),
    "SETG-006": ("settings/app-lock", [], {"disabled", "enabled", "deviceSecurityMissing"}),
    "SETG-007": ("settings/screen-privacy", [], {"content"}),
    "SETG-008": ("settings/trash", [], {"content"}),
    "SETG-009": ("settings/privacy-diagnostics", [], {"preConsent", "enabled", "disabled"}),
    "SETG-010": ("settings/telemetry-queue", [], {"content", "empty"}),
    "SETG-011": ("settings/crash-queue", [], {"content", "empty"}),
    "CLR-001": ("settings/clear-local", [], {"content", "confirming", "clearing", "failed"}),
    "SYS-004": ("device-security-required", [], {"missing", "configured"}),
}
PLACEHOLDER = re.compile(r"\b(?:TODO|NotImplemented|UnsupportedOperationException)\b")


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def source_map() -> dict[str, str]:
    roots = (
        "app/src/main/kotlin", "core/security/src/main/kotlin", "core/telemetry/src/main/kotlin",
        "core/designsystem/src/main/kotlin", "feature/vault/src/main/kotlin", "feature/settings/src/main/kotlin",
        "finance/application/src/main/kotlin", "finance/data/src/main/kotlin",
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
    if sum(len(item[2]) for item in EXPECTED.values()) != 39:
        errors.append("P32 required-state baseline must remain exactly 39")
    return errors


def validate_sources(sources: dict[str, str] | None = None) -> list[str]:
    sources = source_map() if sources is None else sources
    errors: list[str] = []
    required = {
        "VaultAuthentication.kt", "VaultClipboardController.kt", "BackupKeyEnvelopeStore.kt",
        "AppLockAndScreenPrivacy.kt", "VaultSecretApplication.kt", "SecureRoomVaultSecretApplicationPort.kt",
        "VaultController.kt", "VaultContract.kt", "VaultScreens.kt", "SecurityPrivacyContract.kt",
        "SecurityPrivacyScreens.kt", "PrivacyDiagnosticModels.kt", "PrivacyDiagnosticStore.kt",
        "PrivacyDiagnosticManager.kt", "WhitelistedHttpDiagnosticSender.kt", "AcraPrivacyIntegration.kt",
        "LedgerApplication.kt", "MainActivity.kt", "AppRootViewModel.kt", "LocalBookArtifactCleaner.kt",
    }
    selected = {path: source for path, source in sources.items() if Path(path).name in required}
    missing = required - {Path(path).name for path in selected}
    if missing:
        errors.append(f"P32 production files missing: {sorted(missing)}")
    for path, source in selected.items():
        if PLACEHOLDER.search(source):
            errors.append(f"placeholder in {path}")

    vault_auth = named(sources, "VaultAuthentication.kt") + named(sources, "AndroidKeystoreKeys.kt")
    require_tokens(errors, vault_auth, "one-shot Vault CryptoObject authentication", (
        "setUserAuthenticationParameters(", "KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL",
        "requireAuthenticatedCipher",
        "if (actual !== expectedCipher)", "consumed.compareAndSet(false, true)", "VaultPlaintextFields",
        "encryptFields", "onApplicationBackgrounded", "onApplicationLocked",
    ))
    controller = named(sources, "VaultController.kt") + named(sources, "AppRootViewModel.kt")
    require_tokens(errors, controller, "per-action Vault controller", (
        "VaultAction.REVEAL_PAN", "VaultAction.COPY_PAN", "VaultAction.REVEAL_SECURITY_CODE",
        "beginReveal", "beginEdit", "beginProvisioning", "beginRestore", "EXPOSURE_MILLIS = 30_000L",
        "onApplicationBackgrounded", "onApplicationLocked", "clipboard.copyPrimaryNumber", "StableIdArgument(cardId)",
    ))
    if "SavedStateHandle" in controller or re.search(r'mapOf\([^\n]*(?:primaryNumber|securityCode|holderName)', controller):
        errors.append("Vault sensitive data enters SavedState or route arguments")

    clipboard = named(sources, "VaultClipboardController.kt")
    require_tokens(errors, clipboard, "sensitive PAN clipboard lifecycle", (
        "EXTRA_IS_SENSITIVE", "DEFAULT_CLEAR_DELAY_MILLIS: Long = 30_000L", "clearIfOwned",
        "onApplicationBackgrounded", "copyPrimaryNumber",
    ))
    if re.search(r"copy(?:SecurityCode|Cvc)|securityCode.*ClipData", clipboard, re.IGNORECASE):
        errors.append("security-code clipboard path exists")

    screen_privacy = named(sources, "AppLockAndScreenPrivacy.kt") + named(sources, "AppRootViewModel.kt")
    require_tokens(errors, screen_privacy, "app lock and screen privacy", (
        "FLAG_SECURE", "setRecentsScreenshotEnabled", "vaultVisible", "globalFlagSecure",
        "obscureRecentTasks", "vaultController.onApplicationLocked()", "enabling app lock requires authentication",
        "val secure = policy.globalFlagSecure || policy.vaultVisible ||",
    ))

    vault_boundary = named(sources, "VaultSecretApplication.kt") + named(sources, "SecureRoomVaultSecretApplicationPort.kt")
    require_tokens(errors, vault_boundary, "ciphertext-only SQLCipher Vault boundary", (
        "Plain card data is intentionally impossible", "VaultCiphertext", "card_vault_secret",
        "EncryptedDatabaseFactory.openPrimary", "DeviceLedgerKeyProvider", "holder_name_ciphertext",
        "pan_ciphertext", "security_code_ciphertext", "custom_fields_ciphertext",
    ))

    restore = named(sources, "BackupKeyEnvelopeStore.kt") + named(sources, "RestoreController.kt") + controller
    require_tokens(errors, restore, "Vault recovery rewrap", (
        "openWithRecoveryPassword", "takeRecoveredVaultDek", "requestRecoveredVaultRewrap", "beginRestore",
    ))

    telemetry_models = named(sources, "PrivacyDiagnosticModels.kt")
    telemetry_manager = named(sources, "PrivacyDiagnosticManager.kt") + named(sources, "PrivacyDiagnosticStore.kt")
    sender = named(sources, "WhitelistedHttpDiagnosticSender.kt")
    acra = named(sources, "AcraPrivacyIntegration.kt") + named(sources, "LedgerApplication.kt")
    require_tokens(errors, telemetry_models, "closed telemetry schema", (
        "enum class FeatureEventName", "enum class FeatureEntry", "enum class DiagnosticOutcome",
        "enum class DurationBucket", "enum class SanitizedErrorCode", "SanitizedStackFrame private constructor",
    ))
    if re.search(r"\bMap\s*<|mapOf\(|mutableMapOf\(", telemetry_models + telemetry_manager + sender + acra):
        errors.append("generic telemetry Map exists")
    require_tokens(errors, telemetry_manager, "consent, identifier and retention policy", (
        "IDENTIFIER_ROTATION_MILLIS = 30L * DAY_MILLIS", "FEATURE_RETENTION_MILLIS = 90L * DAY_MILLIS",
        "CRASH_RETENTION_MILLIS = 180L * DAY_MILLIS", "deleteFeatureData", "deleteCrashData",
        "DROPPED_BY_POLICY", "noBackupFilesDir", "StandardCopyOption.ATOMIC_MOVE",
    ))
    require_tokens(errors, sender, "HTTPS fixed sender and upload scan", (
        "require(endpoint.isHttps || allowCleartextForTest)", "ledger-feature-v1", "ledger-crash-v1",
        "DiagnosticUploadStringScanner", "CARD_NUMBER_LIKE", "DROPPED_BY_POLICY",
    ))
    require_tokens(errors, acra, "ACRA and exit diagnostics whitelist", (
        "ReportField.ANDROID_VERSION", "ReportField.STACK_TRACE", "withAlsoReportToAndroidFramework(false)",
        "WhitelistedAcraReportSenderFactory", "PrivacyCrashSanitizer", "ApplicationExitInfo.REASON_ANR",
        "ApplicationExitInfo.REASON_CRASH_NATIVE", "ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE",
        "ApplicationExitDiagnosticCollector(this).collect()",
    ))
    if any(token in acra for token in ("ReportField.LOGCAT", "ReportField.USER_COMMENT", "ReportField.CUSTOM_DATA")):
        errors.append("ACRA report content includes forbidden free-text/default field")

    local_clear = named(sources, "AppRootViewModel.kt") + named(sources, "LocalBookArtifactCleaner.kt")
    require_tokens(errors, local_clear, "authenticated scoped local clear", (
        "SensitiveSettingsAuthenticationPurpose.CLEAR_LOCAL", "cancelAllWork().result.get()", "clearLocalBook",
        "TelemetryRuntime.deleteAllLocal()", "attachment_objects", "backup-repositories", "settingsRepository.reset()",
    ))
    if re.search(r"(?:getExternalFilesDir|DriveSnapshotDeletionService).*delete", named(sources, "LocalBookArtifactCleaner.kt")):
        errors.append("local clear may delete user exports or Drive backups")

    vault_ui = named(sources, "VaultScreens.kt")
    settings_ui = named(sources, "SecurityPrivacyScreens.kt")
    require_tokens(errors, vault_ui, "VLT governed UI", tuple(f'"VLT-00{i}"' for i in range(1, 5)) + (
        "copyAllowed = false", "clearAndSetSemantics", "SensitiveValueField",
    ))
    require_tokens(errors, settings_ui, "SETG/CLR/SYS governed UI", tuple(f'"SETG-{i:03d}"' for i in range(6, 12)) + (
        '"CLR-001"', '"SYS-004"', "HighRiskConfirmation", "diagnostics_whitelist",
    ))
    return errors


def validate_schema_and_dependencies() -> list[str]:
    errors: list[str] = []
    schema = read("core/database/src/main/assets/ledger_schema_v1_core.sql")
    projection = read("core/database/src/main/assets/ledger_schema_v1_projections_operations.sql")
    require_tokens(errors, schema, "Vault schema", (
        "CREATE TABLE card_vault_secret", "pan_ciphertext BLOB", "security_code_ciphertext BLOB",
    ))
    fts = re.search(r"CREATE VIRTUAL TABLE transaction_fts[\s\S]*?\n\)", projection)
    if not fts or re.search(r"vault|\bpan\b|security_code|holder_name|custom_fields", fts.group(0), re.IGNORECASE):
        errors.append("Vault fields enter transaction FTS")
    catalog = read("gradle/libs.versions.toml")
    require_tokens(errors, catalog, "frozen P32 dependencies", ('acra = "5.13.1"', 'leakcanary = "2.14"'))
    return errors


def validate_tests_resources() -> list[str]:
    errors: list[str] = []
    roots = (
        "core/security/src/androidTest", "core/security/src/test", "core/telemetry/src/androidTest",
        "core/telemetry/src/test", "feature/vault/src/androidTest", "feature/settings/src/androidTest",
        "finance/data/src/androidTest", "app/src/androidTest",
    )
    tests = "\n".join(path.read_text(encoding="utf-8") for root in roots for path in sorted((ROOT / root).rglob("*.kt")))
    require_tokens(errors, tests, "P32 automated evidence", (
        "independentActionsUseFreshCryptoObjectsAndBackgroundClearsEveryExposure",
        "recoveryWrappedVaultDekIsReboundToFreshDeviceAuthenticationKek",
        "clipboardIsMarkedSensitiveAndClearsOnTimerAndBackground",
        "vaultAndBackgroundPrivacyAlwaysApplyFlagSecure",
        "consentQueuesSurviveManagerRestartAndDisablingDeletesQueueAndIdentifierFiles",
        "persistedFilesNeverContainBusinessOrVaultSentinelsAndCorruptionFailsClosed",
        "acraCustomSenderAndApplicationExitInfoCollectorUseOnlyTheWhitelistedQueue",
        "network boundary drops a forged stack symbol containing a card number",
        "vlt001ThroughVlt004RequiredStatesRenderInThreeLanguagesAndAccessibilitySizes",
        "panAndSecurityCodeNeverEnterTheSemanticsTreeAndSecurityCodeHasNoCopyAction",
        "contractDerivedVltScreenshotsMatchPixelBaselines",
        "setg006ThroughSetg011Clr001AndSys004RenderEveryStateAcrossThreeLanguagesAndAccessibilitySizes",
        "diagnosticQueueRendersOnlyFixedEnumsAndNeverRendersBusinessSentinel",
        "contractDerivedSetgClrAndSysScreenshotsMatchPixelBaselines",
        "allFifteenWorkbookQueriesExecuteAndVaultCiphertextsNeverReachOrdinaryExport",
        "g004ClearRemovesEveryAppOwnedDerivedArtifactButLeavesUserControlledExternalFiles",
    ))
    for module in ("feature/vault", "feature/settings"):
        localized: list[set[str]] = []
        for folder in ("values", "values-en", "values-ja"):
            files = sorted((ROOT / module / "src/main/res" / folder).glob("*.xml"))
            names = set().union(*(set(re.findall(r'<string name="([^"]+)"', path.read_text(encoding="utf-8"))) for path in files))
            localized.append(names)
        if not localized[0] or localized[0] != localized[1] or localized[0] != localized[2]:
            errors.append(f"{module} P32 strings are incomplete across zh-CN/en/ja")
    return errors


def validate_ledgers() -> list[str]:
    errors: list[str] = []
    state = read("docs/implementation/PROJECT_STATE.md")
    evidence = read("docs/implementation/TEST_EVIDENCE.md")
    decision = read("docs/implementation/DECISION_LOG.md")
    mapping_path = ROOT / "docs/implementation/P32_SECURITY_PRIVACY_DIAGNOSTICS_MAPPING.md"
    mapping = mapping_path.read_text(encoding="utf-8") if mapping_path.is_file() else ""
    require_tokens(errors, state, "PROJECT_STATE", ("Current stage: P36", "| P32 | VERIFIED |"))
    for index in range(1, 9):
        if f"P32-E{index:03d}" not in evidence:
            errors.append(f"TEST_EVIDENCE missing P32-E{index:03d}")
    require_tokens(errors, mapping, "P32 mapping", (
        "CryptoObject", "30 seconds", "SQLCipher", "ACRA", "ApplicationExitInfo", "P32 is `VERIFIED`",
    ))
    require_tokens(errors, decision, "P32 decision log", ("P32", "unlockedSession", "replaceable HTTPS"))
    with (ROOT / "docs/implementation/SCREEN_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        screens = {row["screen_id"]: row for row in csv.DictReader(handle)}
    for screen_id in EXPECTED:
        row = screens.get(screen_id, {})
        if row.get("status") != "VERIFIED" or "P32" not in row.get("implementation_evidence", "") or "P32-E" not in row.get("verification_evidence", ""):
            errors.append(f"{screen_id} lacks VERIFIED P32 evidence")
    with (ROOT / "docs/implementation/REQUIREMENT_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        requirements = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    for requirement_id in ("REQ-004", "REQ-005", "REQ-077", "REQ-078", "REQ-079", "REQ-080"):
        row = requirements.get(requirement_id, {})
        if row.get("status") not in {"IN_PROGRESS", "VERIFIED"} or "P32" not in row.get("implementation_evidence", "") or "P32-E" not in row.get("verification_evidence", ""):
            errors.append(f"{requirement_id} lacks truthful P32 evidence")
    return errors


def main() -> int:
    errors = validate_contract() + validate_sources() + validate_schema_and_dependencies() + validate_tests_resources() + validate_ledgers()
    if errors:
        print("P32 security/privacy validation failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("P32 security/privacy validation passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
