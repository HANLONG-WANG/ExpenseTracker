#!/usr/bin/env python3
"""Fail closed on final acceptance, release hardening and delivery drift."""

from __future__ import annotations

import argparse
import csv
import re
import sys
from pathlib import Path
from xml.etree import ElementTree


ROOT = Path(__file__).resolve().parents[1]
IMPLEMENTATION = ROOT / "docs/implementation"
RELEASE_DOCS = ROOT / "docs/release"


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def read_csv(relative: str) -> list[dict[str, str]]:
    with (ROOT / relative).open(encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle))


def production_sources() -> dict[str, str]:
    roots = ("app", "analytics", "core", "feature", "finance", "transfer", "widget")
    suffixes = {".kt", ".java"}
    return {
        path.relative_to(ROOT).as_posix(): path.read_text(encoding="utf-8")
        for root in roots
        for path in sorted((ROOT / root).glob("**/src/main/**/*"))
        if path.is_file() and path.suffix in suffixes and "/build/" not in path.as_posix()
    }


def validate_release_configuration(
    convention: str | None = None,
    app_build: str | None = None,
    root_build: str | None = None,
    proguard: str | None = None,
) -> list[str]:
    convention = convention or read("build-logic/src/main/kotlin/app/ledger/buildlogic/ConventionPlugins.kt")
    app_build = app_build or read("app/build.gradle.kts")
    root_build = root_build or read("build.gradle.kts")
    proguard = proguard or read("app/proguard-rules.pro")
    errors: list[str] = []
    for marker in (
        'RELEASE_VERSION_CODE = 1',
        'RELEASE_VERSION_NAME = "1.0.0"',
        'gradleProperty("ledgerApplicationId")',
        "valid reverse-DNS Android application ID",
        "minSdk = MIN_SDK",
        "targetSdk = TARGET_SDK",
    ):
        if marker not in convention:
            errors.append(f"release convention marker missing: {marker}")
    if 'version = "1.0.0"' not in root_build or "0.2.0-p02" in root_build + convention:
        errors.append("project/application version is not closed at 1.0.0")
    for marker in (
        'gradleProperty("ledgerSigningStoreFile")',
        'gradleProperty("ledgerSigningStorePassword")',
        'gradleProperty("ledgerSigningKeyAlias")',
        'gradleProperty("ledgerSigningKeyPassword")',
        "partially configured",
        'getByName("release")',
        "isDebuggable = false",
        "isMinifyEnabled = true",
        "isShrinkResources = true",
        'getDefaultProguardFile("proguard-android-optimize.txt")',
    ):
        if marker not in app_build:
            errors.append(f"release hardening marker missing: {marker}")
    if re.search(r"(?:storePassword|keyPassword)\s*=\s*\"[^\"]+\"", app_build):
        errors.append("release signing secret is hard-coded")
    if "releaseImplementation(libs.leakcanary" in app_build:
        errors.append("debug-only LeakCanary entered release dependencies")
    if "-keep public class * extends androidx.work.InputMerger" not in proguard or "public <init>();" not in proguard:
        errors.append("R8 does not retain WorkManager's reflectively created InputMerger constructor")
    manifest = ElementTree.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
    android = "{http://schemas.android.com/apk/res/android}"
    application = manifest.find("application")
    if application is None:
        errors.append("application manifest node is missing")
    else:
        if application.attrib.get(android + "allowBackup") != "false":
            errors.append("platform plaintext backup is not disabled")
        if application.attrib.get(android + "usesCleartextTraffic") != "false":
            errors.append("release cleartext traffic is not disabled")
        if application.attrib.get(android + "networkSecurityConfig") != "@xml/network_security_config":
            errors.append("release network security config is missing")
    profile = read("app/src/main/baseline-prof.txt")
    if len(profile.splitlines()) < 10_000 or "MainActivity" not in profile or "AppRootViewModel" not in profile:
        errors.append("checked-in Baseline Profile is missing or incomplete")
    return errors


def validate_coverage_ledgers(
    requirements: list[dict[str, str]] | None = None,
    screens: list[dict[str, str]] | None = None,
    domain: str | None = None,
) -> list[str]:
    requirements = requirements or read_csv("docs/implementation/REQUIREMENT_COVERAGE.csv")
    screens = screens or read_csv("docs/implementation/SCREEN_COVERAGE.csv")
    domain = domain or read("docs/implementation/DOMAIN_AND_SCHEMA_COVERAGE.md")
    errors: list[str] = []
    if len(requirements) != 90 or len({row.get("requirement_id") for row in requirements}) != 90:
        errors.append("requirement ledger must contain exactly 90 unique rows")
    for row in requirements:
        if row.get("status") != "VERIFIED" or not row.get("implementation_evidence") or not row.get("verification_evidence"):
            errors.append(f"requirement lacks complete VERIFIED evidence: {row.get('requirement_id')}")
    if len(screens) != 215 or len({row.get("screen_id") for row in screens}) != 215:
        errors.append("screen ledger must contain exactly 215 unique rows")
    for row in screens:
        if row.get("status") != "VERIFIED" or not row.get("implementation_evidence") or not row.get("verification_evidence"):
            errors.append(f"screen lacks complete VERIFIED evidence: {row.get('screen_id')}")
    invariant_rows = re.findall(r"^\| (INV-\d{3}) \|.*\| (VERIFIED \([^\n]+\)) \|$", domain, flags=re.MULTILINE)
    if len(invariant_rows) != 35 or {item[0] for item in invariant_rows} != {f"INV-{index:03d}" for index in range(1, 36)}:
        errors.append("domain ledger does not contain 35/35 evidence-backed VERIFIED invariants")
    for marker, expected in (("| ADR-", 21), ("| UI-ADR-", 12)):
        count = sum(1 for line in domain.splitlines() if line.startswith(marker) and "VERIFIED" in line)
        if count != expected:
            errors.append(f"domain ledger has {count}/{expected} VERIFIED {marker.strip('| ')} rows")
    return errors


def validate_delivery_documents(files: dict[str, str] | None = None) -> list[str]:
    required = (
        "NOTICE",
        "docs/release/PRIVACY_POLICY_zh-CN.md",
        "docs/release/PRIVACY_POLICY_ja.md",
        "docs/release/PRIVACY_POLICY_en.md",
        "docs/release/ABOUT_AND_OPEN_SOURCE.md",
        "docs/release/RELEASE_NOTES_v1.0.0.md",
        "docs/release/REPRODUCIBLE_BUILD.md",
        "docs/release/PLAY_RELEASE_INPUTS.md",
    )
    files = files or {relative: read(relative) for relative in required if (ROOT / relative).is_file()}
    errors = [f"release delivery file missing: {relative}" for relative in required if relative not in files]
    for relative in required[1:4]:
        text = files.get(relative, "")
        for marker in ("SQLCipher", "Google Drive", "90", "180"):
            if marker not in text:
                errors.append(f"privacy policy lacks required disclosure {marker}: {relative}")
        if len(text) < 600 or text.count("\n\n") < 4:
            errors.append(f"privacy policy is not a complete long-form disclosure: {relative}")
    build_doc = files.get("docs/release/REPRODUCIBLE_BUILD.md", "")
    for marker in ("p36Check", "p36Artifacts", "ledgerApplicationId", "ledgerSigningStoreFile", "dependency-verification=strict"):
        if marker not in build_doc:
            errors.append(f"reproducible build guide lacks {marker}")
    inputs = files.get("docs/release/PLAY_RELEASE_INPUTS.md", "")
    for marker in ("applicationId", "Play App Signing", "drive.file", "Telemetry", "Policy/support/source"):
        if marker not in inputs:
            errors.append(f"external release-input checklist lacks {marker}")
    return errors


def validate_local_privacy_policy(resource_texts: list[str] | None = None) -> list[str]:
    paths = (
        "feature/onboarding/src/main/res/values/strings.xml",
        "feature/onboarding/src/main/res/values-ja/strings.xml",
        "feature/onboarding/src/main/res/values-en/strings.xml",
    )
    if resource_texts is None:
        resource_texts = []
        for relative in paths:
            root = ElementTree.parse(ROOT / relative).getroot()
            element = next(item for item in root if item.attrib.get("name") == "onboarding_privacy_document")
            resource_texts.append(element.text or "")
    errors: list[str] = []
    for index, text in enumerate(resource_texts):
        if len(text) < 650:
            errors.append(f"in-app privacy policy locale {index} is not complete")
        for marker in ("SQLCipher", "Drive", "90", "180"):
            if marker not in text:
                errors.append(f"in-app privacy policy locale {index} lacks {marker}")
    return errors


def validate_hygiene(sources: dict[str, str] | None = None) -> list[str]:
    sources = sources or production_sources()
    errors: list[str] = []
    placeholder = re.compile(r"\b(?:TODO|FIXME|NotImplemented)\b|TODO\(")
    for path, source in sources.items():
        if placeholder.search(source):
            errors.append(f"production placeholder marker in {path}")
        if re.search(r"android\.util\.Log\.[vdiew]\s*\(", source):
            errors.append(f"ordinary Android logging call in production: {path}")
    combined_tests = "\n".join(
        path.read_text(encoding="utf-8")
        for path in sorted(ROOT.glob("**/src/**/*Test.kt"))
        if "/build/" not in path.as_posix()
    )
    if re.search(r"@(?:Ignore|Disabled)\b", combined_tests):
        errors.append("checked-in test uses @Ignore or @Disabled")
    if "BEGIN PRIVATE KEY" in "\n".join(sources.values()):
        errors.append("private key material appears in production tree")
    return errors


def validate_restore_projection_hardening(
    repository: str | None = None,
    merge_port: str | None = None,
) -> list[str]:
    repository = repository or read(
        "finance/data/src/main/kotlin/app/ledger/finance/data/RoomFinancialCommitRepository.kt",
    )
    merge_port = merge_port or read(
        "finance/data/src/main/kotlin/app/ledger/finance/data/SecureRoomMergeRestoreApplicationPort.kt",
    )
    errors: list[str] = []
    for marker in (
        "forceFullProjectionRebuild",
        "projections.rebuildAll(",
        "plan.targetLocalRevision.value",
    ):
        if marker not in repository:
            errors.append(f"coordinator-owned full projection rebuild marker missing: {marker}")
    if "forceFullProjectionRebuild = true" not in merge_port:
        errors.append("merge restore can publish copied entities without rebuilding every projection family")
    if "if (!report.projectionsValid) abort(FinanceRestoreError.ProjectionFailed(report.projectionFailureCodes))" not in merge_port:
        errors.append("merge restore does not preserve a typed projection-integrity failure")
    return errors


def validate_api28_exact_arithmetic(
    analytics: str | None = None,
    accounting: str | None = None,
) -> list[str]:
    analytics = analytics or read("analytics/domain/src/main/kotlin/app/ledger/analytics/domain/CustomAnalytics.kt")
    accounting = accounting or read("finance/domain/src/main/kotlin/app/ledger/finance/domain/AccountingRuleEngine.kt")
    errors: list[str] = []
    if "toCompatibleLongExact" not in analytics or "CheckedArithmetic.toLongExact(this)" not in analytics:
        errors.append("analytics does not use the API 28-compatible checked BigInteger conversion")
    incompatible = (
        "values.reduce(BigInteger::add).longValueExact()",
        ".divide(BigInteger.valueOf(elapsedDays)).longValueExact()",
        ".divide(denominator).longValueExact()",
    )
    if any(marker in analytics + accounting for marker in incompatible):
        errors.append("production exact arithmetic calls a BigInteger API unavailable on API 28")
    if "CheckedArithmetic.toLongExact(total.multiply(BigInteger.valueOf(weight)).divide(denominator)).orReject()" not in accounting:
        errors.append("settlement allocation bypasses the shared checked API 28 conversion")
    return errors


def validate_incremental_analytics_projection(source: str | None = None) -> list[str]:
    source = source or read(
        "core/database/src/main/kotlin/app/ledger/core/database/AnalyticsProjectionEngine.kt",
    )
    errors: list[str] = []
    start_marker = "private fun rebuildDailyTotal(database: SupportSQLiteDatabase, revision: Long, localDate: Int)"
    end_marker = "private fun rebuildDailyDimensions(database: SupportSQLiteDatabase, revision: Long)"
    if start_marker not in source or end_marker not in source:
        return ["incremental analytics rebuild boundary is missing"]
    body = source[source.index(start_marker) : source.index(end_marker)]
    if "COALESCE(SUM" in body:
        errors.append("incremental analytics rebuild can materialize zero-only metric rows absent from full rebuild")
    if body.count("GROUP BY") != 8:
        errors.append("incremental analytics rebuild does not preserve the eight source-bounded full-rebuild groups")
    return errors


def validate_refund_projection_invalidation(source: str | None = None) -> list[str]:
    source = source or read(
        "finance/data/src/main/kotlin/app/ledger/finance/data/RoomProjectionEngine.kt",
    )
    errors: list[str] = []
    for marker in (
        "directlyChangedTransactionUids + refundUids",
        "economicEffectDates(database, transactionUids)",
        "SELECT DISTINCT ee.accrual_local_date FROM economic_effect ee",
    ):
        if marker not in source:
            errors.append(f"refund projection invalidation marker missing: {marker}")
    return errors


def validate_release_automation(root_build: str | None = None, workflow: str | None = None) -> list[str]:
    root_build = root_build or read("build.gradle.kts")
    workflow = workflow or read(".github/workflows/quality.yml")
    errors: list[str] = []
    for marker in ("validateP36ReleaseDelivery", 'tasks.register("p36Check")', 'tasks.register("p36DeviceCheck")', 'tasks.register("p36Artifacts")', "p36ReleaseManifest"):
        if marker not in root_build:
            errors.append(f"P36 Gradle automation missing: {marker}")
    for marker in ("p36Check", "p36Artifacts", "app-release.aab", "p36-artifacts.sha256"):
        if marker not in workflow:
            errors.append(f"CI release gate/artifact missing: {marker}")
    manifest_task = root_build.partition("val p36ReleaseManifest")[2].partition('tasks.register("p36Artifacts")')[0]
    for marker in (
        "app/build/outputs/bundle/release/app-release.aab",
        "reports/cyclonedx/bom.json",
        "reports/dependency-license/licenses.csv",
        "reports/dependency-license/THIRD_PARTY_NOTICES.txt",
    ):
        if marker not in manifest_task:
            errors.append(f"P36 manifest incremental input missing: {marker}")
    generator = read("scripts/generate_p36_release_manifest.py")
    for marker in ("app-release.aab", "baseline-prof.txt", "bom.json", "THIRD_PARTY_NOTICES.txt", "sha256"):
        if marker not in generator:
            errors.append(f"release artifact manifest lacks {marker}")
    return errors


def validate_final_ledgers() -> list[str]:
    errors: list[str] = []
    state = read("docs/implementation/PROJECT_STATE.md")
    evidence = read("docs/implementation/TEST_EVIDENCE.md")
    readiness = read("docs/implementation/RELEASE_READINESS.md")
    decision = read("docs/implementation/DECISION_LOG.md")
    if not re.search(r"Current stage: P36\b", state) or not re.search(r"^\| P36 \| VERIFIED \|", state, re.MULTILINE):
        errors.append("PROJECT_STATE does not mark P36 VERIFIED")
    for index in range(1, 9):
        if f"P36-E{index:03d}" not in evidence:
            errors.append(f"TEST_EVIDENCE lacks P36-E{index:03d}")
    if "Overall release status: `VERIFIED`" not in readiness:
        errors.append("RELEASE_READINESS is not VERIFIED")
    if "DL-173" not in decision or "P36" not in decision:
        errors.append("DECISION_LOG lacks the P36 release-input/provenance decision")
    return errors


def validate_all(include_final_ledgers: bool = True) -> list[str]:
    errors = validate_release_configuration()
    errors += validate_coverage_ledgers()
    errors += validate_delivery_documents()
    errors += validate_local_privacy_policy()
    errors += validate_hygiene()
    errors += validate_restore_projection_hardening()
    errors += validate_api28_exact_arithmetic()
    errors += validate_incremental_analytics_projection()
    errors += validate_refund_projection_invalidation()
    errors += validate_release_automation()
    if include_final_ledgers:
        errors += validate_final_ledgers()
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--implementation-only", action="store_true")
    args = parser.parse_args()
    errors = validate_all(include_final_ledgers=not args.implementation_only)
    if errors:
        print("P36 final release validation failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    scope = "implementation and delivery" if args.implementation_only else "implementation, delivery and final ledgers"
    print(f"P36 final release validation passed: {scope}; 90 requirements, 215 screens and 35 invariants closed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
