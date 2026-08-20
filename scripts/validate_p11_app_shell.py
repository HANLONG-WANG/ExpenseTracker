#!/usr/bin/env python3
"""Fail closed when the P11 root runtime, onboarding, navigation or evidence drifts."""

from __future__ import annotations

import csv
import hashlib
import re
import struct
import sys
from pathlib import Path
from typing import Mapping

import yaml


ROOT = Path(__file__).resolve().parents[1]
P11_SCREENS = {f"G-{number:03d}" for number in range(1, 9)} | {
    f"ONB-{number:03d}" for number in range(1, 11)
}
VERIFIED_REQUIREMENTS = {"REQ-070", "REQ-071", "REQ-081", "REQ-085", "REQ-087"}
FOUNDATION_REQUIREMENTS = {
    "REQ-001", "REQ-002", "REQ-078", "REQ-082", "REQ-083", "REQ-084", "REQ-086"
}
EXPECTED_STATES = {
    "G-001": {"uninitialized", "locked", "opening", "maintenance", "recoveryRequired", "ready"},
    "G-002": {"biometricAvailable", "credentialOnly", "authenticating", "authFailed", "lockedOut"},
    "G-003": {"opening", "migrationDetected", "failed"},
    "G-004": {"preparing", "running", "nonCancelable", "cancelable", "failed", "succeeded"},
    "G-005": {"corrupt", "keyUnavailable", "projectionFailure", "restoreAvailable", "noBackup"},
    "G-006": {"content", "badgeUpdates", "operationInProgress"},
    "G-007": {"active", "paused", "failed", "completed", "empty"},
    "G-008": {"content", "notFound"},
    **{
        f"ONB-{number:03d}": {"content", "validationError", "submitting"}
        for number in range(1, 11)
    },
}
GOLDENS = {
    "p11_locked.png": "10d02985c15c790c515549b412b5c0f531b45d08fdf0417fd689d1adff839dec",
    "p11_maintenance.png": "23984bd7176a655975a09d2fb92b27d6c51996375a82b36a4822b6f9d27523d4",
    "p11_onboarding_backup_error.png": "d0f27fdfb8a2e66224fb8b7cc5b6d9476452e4b42dca066a333500fc0927aaf9",
    "p11_recovery.png": "6b6ced104f9147cea560483270984dc281383636f6eaff80cebcd1434a2c38e8",
}
PLACEHOLDER = re.compile(r"\b(?:TODO|NotImplemented|UnsupportedOperationException)\b")
ORDINARY_LOG = re.compile(r"\b(?:println|printStackTrace|android\.util\.Log|Timber\.)")


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def load_sources() -> dict[str, str]:
    roots = (
        "app/src/main/kotlin",
        "feature/onboarding/src/main/kotlin",
        "core/navigation/src/main/kotlin",
        "finance/application/src/main/kotlin",
        "finance/data/src/main/kotlin",
    )
    return {
        path.relative_to(ROOT).as_posix(): read(path)
        for source_root in roots
        for path in sorted((ROOT / source_root).rglob("*.kt"))
    }


def named(sources: Mapping[str, str], filename: str) -> str:
    return next((source for path, source in sources.items() if Path(path).name == filename), "")


def require_tokens(errors: list[str], source: str, label: str, tokens: tuple[str, ...]) -> None:
    for token in tokens:
        if token not in source:
            errors.append(f"{label} missing {token}")


def validate_sources(sources: Mapping[str, str]) -> list[str]:
    errors: list[str] = []
    required = {
        "MainActivity.kt", "LedgerApplication.kt", "AppRootViewModel.kt", "AppRootScreen.kt", "ReadyRootScaffold.kt",
        "AppSettingsRepository.kt", "OnboardingContract.kt", "OnboardingScreen.kt",
        "FiveStackNavigator.kt", "NavigationContract.kt", "LedgerInitialization.kt",
        "SecureRoomLedgerInitializationPort.kt", "RoomLedgerStartupInspector.kt",
    }
    missing = required - {Path(path).name for path in sources}
    if missing:
        errors.append(f"P11 production files missing: {sorted(missing)}")
    for path, source in sources.items():
        if PLACEHOLDER.search(source):
            errors.append(f"placeholder production implementation in {path}")
        if ORDINARY_LOG.search(source):
            errors.append(f"ordinary logging entered P11 production in {path}")

    activity = named(sources, "MainActivity.kt")
    require_tokens(
        errors,
        activity,
        "single Activity runtime",
        (
            "@AndroidEntryPoint", "class MainActivity : FragmentActivity()", "setContent { LedgerAppRoot(viewModel) }",
            "viewModel.handleDeepLink", "onApplicationForegrounded", "onApplicationBackgrounded",
            "AndroidScreenPrivacyController", "BiometricPrompt(",
        ),
    )
    manifest = read(ROOT / "app/src/main/AndroidManifest.xml")
    if manifest.count("<activity") != 1 or ".MainActivity" not in manifest:
        errors.append("app manifest must expose exactly the single MainActivity root")
    if "android.permission.INTERNET" in manifest and 'implementation(project(":core:geo"))' not in read(ROOT / "app/build.gradle.kts"):
        errors.append("app requests network access without the governed core:geo map integration")

    root = named(sources, "AppRootScreen.kt") + named(sources, "ReadyRootScaffold.kt")
    require_tokens(
        errors,
        root,
        "SessionGate and root scaffold",
        (
            "SessionGateScreen", "ReadyRootScaffold", "NavDisplay(", "LedgerNavigationBar(",
            "LedgerScaffold(",
            "BookSessionState.Locked", "BookSessionState.Opening", "BookSessionState.Maintenance",
            "BookSessionState.RecoveryRequired", "BookSessionState.Ready", "LedgerTestTags.SESSION_GATE",
            '"G-006"', '"G-007"', '"G-008"', "global_unsaved_lost",
        ),
    )
    if "androidx.compose.material3" in root or "MaterialTheme" in root:
        errors.append("app root bypasses the governed design system")

    view_model = named(sources, "AppRootViewModel.kt")
    require_tokens(
        errors,
        view_model,
        "root state and secure onboarding",
        (
            "BookSessionManager(", "AppLockController(", "SqlCipherBookDatabaseResourceFactory(",
            "LedgerInitializationPort", "restoreNavigationIfAllowed", "persistNavigationIfAllowed",
            "pendingDeepLink", "consumePendingDeepLink", "parameters.isEmpty()", "clearRecoveryPlaintextIfLeavingBackup",
            "RecoveryPassword.copyOf", "SecretBytes.copyOf", "recoveryWrappedVerifier", "onboardingComplete = true",
        ),
    )
    if re.search(r"(?m)^import\s+[^\s]+(?:Dao|Entity)\s*$|@Entity\b", view_model):
        errors.append("app root ViewModel obtained a DAO/Entity")

    onboarding = named(sources, "OnboardingContract.kt") + named(sources, "OnboardingScreen.kt")
    for screen_id in (f"ONB-{number:03d}" for number in range(1, 11)):
        if screen_id not in onboarding:
            errors.append(f"onboarding implementation missing {screen_id}")
    require_tokens(
        errors,
        onboarding,
        "ten-step onboarding",
        (
            "LANGUAGE", "BASE_CURRENCY", "TIME_ZONE", "PRIVACY_POLICY", "TELEMETRY", "APP_LOCK",
            "BACKUP", "ACCOUNT", "CATEGORY", "COMPLETE", "recoveryPassword=<redacted>",
            "OnboardingRenderState.SUBMITTING", "OnboardingRenderState.VALIDATION_ERROR",
        ),
    )
    feature_sources = "\n".join(source for path, source in sources.items() if path.startswith("feature/onboarding/"))
    if re.search(r"(?m)^import\s+(?:androidx\.room|app\.ledger\.(?:finance\.data|core\.database|core\.security))", feature_sources):
        errors.append("onboarding feature bypasses application ports or the root security boundary")

    navigation = named(sources, "FiveStackNavigator.kt") + named(sources, "NavigationContract.kt")
    require_tokens(
        errors,
        navigation,
        "five-stack route contract",
        (
            "NavBackStack", "TopLevelDestination.RECORD", "TopLevelDestination.JOURNAL",
            "TopLevelDestination.ACCOUNTS", "TopLevelDestination.BUDGET", "TopLevelDestination.ANALYSIS",
            'ScreenId("REC-001")', '"EXPENSE"', "state == SessionGateState.READY", "SafeRouteArgument",
            "StableIdArgument", "YearMonthArgument", "OpaqueKeyArgument", "PositiveIntArgument",
        ),
    )
    return errors


def validate_proto_text(proto: str) -> list[str]:
    errors: list[str] = []
    required_proto = (
        "SessionRestorePolicyProto", "NavigationSnapshotProto", "onboarding_complete", "onboarding_step",
        "language_tag", "base_currency", "zone_id", "privacy_accepted", "app_lock_enabled",
        "recovery_wrapped_verifier", "book_id", "unsaved_content_loss_pending",
    )
    require_tokens(errors, proto, "typed non-sensitive Proto DataStore", required_proto)
    forbidden_proto = re.compile(
        r"(?i)\b(?:amount|memo|note|card_number|pan|cvc|cvv|attachment_path|latitude|longitude|"
        r"recovery_password|account_name|category_name)\b"
    )
    if forbidden_proto.search(proto):
        errors.append("sensitive or form plaintext entered the Proto DataStore schema")
    return errors


def validate_proto_dependencies_and_goldens() -> list[str]:
    errors = validate_proto_text(read(ROOT / "app/src/main/proto/ledger_app_settings.proto"))

    catalog = read(ROOT / "gradle/libs.versions.toml")
    for dependency in (
        'navigation3 = "1.1.5"', 'hilt = "2.59.2"', 'datastore = "1.2.1"',
        'protobuf = "4.35.0"', 'protobuf-plugin = "0.10.0"', 'lifecycle = "2.9.4"',
    ):
        if dependency not in catalog:
            errors.append(f"P11 dependency drift: {dependency}")

    golden_root = ROOT / "app/src/androidTest/assets/goldens"
    if {path.name for path in golden_root.glob("*.png")} != set(GOLDENS):
        errors.append("P11 golden asset set drift")
    for name, expected_hash in GOLDENS.items():
        path = golden_root / name
        if not path.is_file():
            continue
        payload = path.read_bytes()
        if hashlib.sha256(payload).hexdigest() != expected_hash:
            errors.append(f"P11 golden hash drift: {name}")
        if payload[:8] != b"\x89PNG\r\n\x1a\n" or struct.unpack(">II", payload[16:24]) != (360, 720):
            errors.append(f"P11 golden dimensions/format drift: {name}")
    return errors


def validate_contract_and_tests() -> list[str]:
    errors: list[str] = []
    contract = yaml.safe_load(read(ROOT / "docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml"))
    actual_screens = {screen["id"]: set(screen.get("requiredStates", [])) for screen in contract["screens"]}
    for screen_id, states in EXPECTED_STATES.items():
        if actual_screens.get(screen_id) != states:
            errors.append(f"{screen_id} requiredStates drift: {sorted(actual_screens.get(screen_id, set()))}")
    if sum(len(states) for states in EXPECTED_STATES.values()) != 65:
        errors.append("P11 expected-state oracle is not exactly 65 states")

    tests = "\n".join(
        read(path)
        for source_root in (
            "app/src/androidTest", "core/navigation/src/test", "finance/application/src/test",
            "finance/data/src/androidTest", "feature/onboarding/src/test",
        )
        for path in sorted((ROOT / source_root).rglob("*.kt"))
    )
    for test_name in (
        "allSixtyFiveFrozenGlobalAndOnboardingStatesRender",
        "recoveryPasswordIsAbsentFromRenderedAndSemanticTrees",
        "constrainedWidthsFontScalesThemesAndDynamicBoundaryDoNotClipRoot",
        "simplifiedChineseJapaneseAndEnglishAppResourcesRender",
        "frozenGlobalAndOnboardingGoldensMatchEveryPixel",
        "realColdStartCompletesTenStepsAndOpensEmptyExpenseRoot",
        "fiveHistoriesRemainIndependentAndReselectionPopsOnlyCurrentStack",
        "validNonSensitiveSnapshotRoundTripsAllStacksAndScrollPositions",
        "everyDestinationIncludingGlobalHubsIsBlockedBeforeReady",
        "frozenTenStepOrderAndOptionalRulesAreExact",
        "rejectsBeforeDelegateWhenSessionIsNotReady",
        "encryptedGenesisAndOptionalReferencesAreAtomicEmptyAndIdempotent",
    ):
        if test_name not in tests:
            errors.append(f"P11 automated evidence missing {test_name}")
    return errors


def validate_ledgers() -> list[str]:
    errors: list[str] = []
    state = read(ROOT / "docs/implementation/PROJECT_STATE.md")
    evidence = read(ROOT / "docs/implementation/TEST_EVIDENCE.md")
    mapping_path = ROOT / "docs/implementation/P11_APP_SHELL_MAPPING.md"
    mapping = read(mapping_path) if mapping_path.is_file() else ""
    if "| P11 | VERIFIED |" not in state or "### P11 result" not in state:
        errors.append("PROJECT_STATE does not record P11 VERIFIED and its result")
    for index in range(1, 9):
        if f"P11-E{index:03d}" not in evidence:
            errors.append(f"TEST_EVIDENCE missing P11-E{index:03d}")
    for token in ("SessionGate", "Navigation 3", "Proto DataStore", "ONB-001", "65 required states", "FinancialMutationCoordinator"):
        if token not in mapping:
            errors.append(f"P11 mapping missing {token}")

    with (ROOT / "docs/implementation/REQUIREMENT_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        requirements = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    for requirement_id in VERIFIED_REQUIREMENTS:
        row = requirements.get(requirement_id, {})
        if row.get("status") != "VERIFIED" or "P11" not in row.get("implementation_evidence", "") or "P11-E" not in row.get("verification_evidence", ""):
            errors.append(f"{requirement_id} must carry P11 VERIFIED evidence")
    for requirement_id in FOUNDATION_REQUIREMENTS:
        row = requirements.get(requirement_id, {})
        if row.get("status") != "IN_PROGRESS" or "P11" not in row.get("implementation_evidence", "") or "P11-E" not in row.get("verification_evidence", ""):
            errors.append(f"{requirement_id} must retain truthful IN_PROGRESS P11 evidence")

    with (ROOT / "docs/implementation/SCREEN_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        screens = {row["screen_id"]: row for row in csv.DictReader(handle)}
    for screen_id in P11_SCREENS:
        row = screens.get(screen_id, {})
        if row.get("status") != "VERIFIED" or "P11-E" not in row.get("verification_evidence", ""):
            errors.append(f"{screen_id} must be VERIFIED by P11")
    return errors


def main() -> int:
    errors = (
        validate_sources(load_sources())
        + validate_proto_dependencies_and_goldens()
        + validate_contract_and_tests()
        + validate_ledgers()
    )
    if errors:
        print("P11 app shell/onboarding validation: FAIL", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("P11 app shell/onboarding validation: PASS")
    print("screens=18 required_states=65 top_level_stacks=5 goldens=4 visual_inputs=contract_only")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
