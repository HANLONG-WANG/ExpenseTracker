#!/usr/bin/env python3
"""Validate the frozen P01 build/module baseline without reading visual drafts."""

from __future__ import annotations

import csv
import hashlib
import json
import re
import sys
import tomllib
import xml.etree.ElementTree as ET
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]

LEAF_MODULES = {
    ":app",
    ":benchmark",
    ":core:common",
    ":core:money",
    ":core:time",
    ":core:designsystem",
    ":core:navigation",
    ":core:database",
    ":core:security",
    ":core:files",
    ":core:network",
    ":core:background",
    ":core:geo",
    ":core:telemetry",
    ":core:testing",
    ":finance:domain",
    ":finance:application",
    ":finance:data",
    ":analytics:domain",
    ":analytics:data",
    ":transfer:domain",
    ":transfer:data",
    ":feature:onboarding",
    ":feature:record",
    ":feature:journal",
    ":feature:accounts",
    ":feature:planning",
    ":feature:liabilities",
    ":feature:settlement",
    ":feature:analysis",
    ":feature:automation",
    ":feature:vault",
    ":feature:transfer",
    ":feature:settings",
    ":widget",
}

PURE_KOTLIN_MODULES = {
    ":core:common",
    ":core:money",
    ":core:time",
    ":core:testing",
    ":finance:domain",
    ":finance:application",
    ":analytics:domain",
    ":transfer:domain",
}

FROZEN_VERSIONS = {
    "agp": "9.3.1",
    "kotlin": "2.4.10",
    "ksp": "2.3.10",
    "room": "2.8.4",
    "sqlcipher": "4.17.0",
    "maplibre": "13.4.1",
    "coil": "3.5.0",
    "tink": "1.23.0",
    "fastexcel": "0.20.2",
}


def fail(message: str) -> None:
    raise AssertionError(message)


def module_dir(module: str) -> Path:
    return ROOT.joinpath(*module.removeprefix(":").split(":"))


def walk_scalars(value: object) -> int:
    if isinstance(value, dict):
        return sum(walk_scalars(item) for item in value.values())
    if isinstance(value, list):
        return sum(walk_scalars(item) for item in value)
    return 1


def implementation_files() -> list[Path]:
    result: list[Path] = []
    for path in ROOT.rglob("*"):
        if not path.is_file():
            continue
        relative = path.relative_to(ROOT)
        if any(part in {".git", ".gradle", ".kotlin", "build", "docs", "quality"} for part in relative.parts):
            continue
        if path.suffix in {".kt", ".kts", ".toml", ".xml"} or path.name in {"gradle.properties"}:
            result.append(path)
    return result


def is_production_source(path: Path) -> bool:
    relative = path.relative_to(ROOT)
    if any(part in {".git", ".gradle", ".kotlin", "build", "docs", "quality"} for part in relative.parts):
        return False
    return any(
        relative.parts[index : index + 2] == ("src", "main")
        for index in range(len(relative.parts) - 1)
    )


def verification_coordinates(path: Path) -> set[str]:
    root = ET.parse(path).getroot()
    return {
        f"{component.attrib['group']}:{component.attrib['name']}:{component.attrib['version']}"
        for component in root.findall(".//{*}component")
    }


def main() -> int:
    settings = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
    configured_modules = set(re.findall(r'"(:[a-z0-9:-]+)"', settings))
    if configured_modules != LEAF_MODULES:
        fail(f"module set differs: missing={LEAF_MODULES - configured_modules}, extra={configured_modules - LEAF_MODULES}")
    if 'includeBuild("build-logic")' not in settings:
        fail("build-logic is not an included build")

    for module in LEAF_MODULES:
        build_file = module_dir(module) / "build.gradle.kts"
        if not build_file.is_file():
            fail(f"missing module build file: {build_file.relative_to(ROOT)}")
    if not (ROOT / "build-logic/src/main/kotlin/app/ledger/buildlogic/ConventionPlugins.kt").is_file():
        fail("convention plugin implementation is missing")

    for module in PURE_KOTLIN_MODULES:
        text = (module_dir(module) / "build.gradle.kts").read_text(encoding="utf-8")
        if 'id("ledger.kotlin.library")' not in text or "ledger.android" in text:
            fail(f"pure Kotlin boundary broken in {module}")

    for module in sorted(item for item in LEAF_MODULES if item.startswith(":feature:")):
        text = (module_dir(module) / "build.gradle.kts").read_text(encoding="utf-8")
        if re.search(r'project\(":(?:feature:[^"]+|[^":]+:data)"\)', text):
            fail(f"feature has forbidden project dependency: {module}")
        required = {
            ":finance:application",
            ":analytics:domain",
            ":transfer:domain",
            ":core:designsystem",
            ":core:navigation",
        }
        declared = set(re.findall(r'project\("(:[^"]+)"\)', text))
        if declared != required:
            fail(f"feature public edges differ for {module}: {declared}")

    catalog = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8"))
    versions = catalog["versions"]
    for name, expected in FROZEN_VERSIONS.items():
        if versions.get(name) != expected:
            fail(f"version {name} is {versions.get(name)!r}, expected {expected!r}")
    prerelease = re.compile(r"(?i)(alpha|beta|(^|[-.])rc|snapshot|next|latest|release|\+)")
    for name, value in versions.items():
        if prerelease.search(value):
            fail(f"dynamic or prerelease version: {name}={value}")

    implementation_text = "\n".join(path.read_text(encoding="utf-8") for path in implementation_files())
    forbidden = {
        "kotlin-android": r"kotlin-android|org\.jetbrains\.kotlin\.android",
        "kapt": r"(?i)\bkapt\b|kotlin-kapt",
        "Room 3": r"androidx\.room[^\n]*[=:]\s*\"?3\.",
        "Retrofit": r"(?i)retrofit",
        "RxJava": r"(?i)rxjava",
    }
    for checker in (
        ROOT / "build-logic/src/main/kotlin/app/ledger/buildlogic/ConventionPlugins.kt",
        ROOT / "build-logic/src/main/kotlin/app/ledger/buildlogic/SourcePolicyEngine.kt",
    ):
        implementation_text = implementation_text.replace(checker.read_text(encoding="utf-8"), "")
    for label, pattern in forbidden.items():
        if re.search(pattern, implementation_text):
            fail(f"forbidden implementation reference found: {label}")

    production_sources = [path for path in ROOT.rglob("*") if path.is_file() and is_production_source(path)]
    for path in production_sources:
        text = path.read_text(encoding="utf-8")
        if re.search(r"\bTODO\b|NotImplementedError|NotImplemented", text):
            fail(f"placeholder production content in {path.relative_to(ROOT)}")
    layout_xml = list(ROOT.glob("**/src/main/res/layout*/*.xml"))
    if layout_xml:
        fail(f"XML main UI found: {[str(path.relative_to(ROOT)) for path in layout_xml]}")

    wrapper = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
    if "gradle-9.5.1-bin.zip" not in wrapper:
        fail("wrapper is not pinned to Gradle 9.5.1")
    expected_distribution_sha = "bafc141b619ad6350fd975fc903156dd5c151998cc8b058e8c1044ab5f7b031f"
    if f"distributionSha256Sum={expected_distribution_sha}" not in wrapper:
        fail("wrapper distribution SHA-256 pin differs")
    wrapper_jar_sha = hashlib.sha256((ROOT / "gradle/wrapper/gradle-wrapper.jar").read_bytes()).hexdigest()

    lockfiles = {
        path
        for path in ROOT.rglob("gradle.lockfile")
        if not any(part in {".gradle", "build"} for part in path.relative_to(ROOT).parts)
    }
    expected_lockfiles = {ROOT / "gradle.lockfile", ROOT / "build-logic/gradle.lockfile"} | {
        module_dir(module) / "gradle.lockfile" for module in LEAF_MODULES
    }
    if lockfiles != expected_lockfiles:
        fail(
            "lockfile set differs: "
            f"missing={sorted(str(path.relative_to(ROOT)) for path in expected_lockfiles - lockfiles)}, "
            f"extra={sorted(str(path.relative_to(ROOT)) for path in lockfiles - expected_lockfiles)}"
        )
    root_build = (ROOT / "build.gradle.kts").read_text(encoding="utf-8")
    build_logic_build = (ROOT / "build-logic/build.gradle.kts").read_text(encoding="utf-8")
    if "lockMode.set(LockMode.STRICT)" not in root_build or "lockMode.set(LockMode.STRICT)" not in build_logic_build:
        fail("root and build-logic must enable strict missing dependency-lock state")
    prerelease_production_entries = []
    locked_coordinates: set[str] = set()
    for lockfile in sorted(lockfiles):
        if lockfile == ROOT / "build-logic/gradle.lockfile":
            pass
        for line in lockfile.read_text(encoding="utf-8").splitlines():
            coordinate = line.partition("=")[0]
            configurations = line.partition("=")[2]
            if coordinate != "empty" and coordinate.count(":") == 2:
                locked_coordinates.add(coordinate)
            if prerelease.search(coordinate) and re.search(r"(?:debug|release)RuntimeClasspath", configurations):
                prerelease_production_entries.append(f"{lockfile.relative_to(ROOT)}:{coordinate}")
    if prerelease_production_entries:
        fail(f"prerelease artifacts entered production runtime locks: {prerelease_production_entries}")
    if not (ROOT / "settings-gradle.lockfile").is_file():
        fail("settings version-catalog lock is missing")

    verification_root = ET.parse(ROOT / "gradle/verification-metadata.xml").getroot()
    components = verification_root.findall(".//{*}component")
    checksums = verification_root.findall(".//{*}sha256")
    if len(components) < 100 or len(checksums) < 100:
        fail(f"dependency verification metadata is incomplete: components={len(components)}, sha256={len(checksums)}")
    verified_coordinates = verification_coordinates(ROOT / "gradle/verification-metadata.xml") | verification_coordinates(
        ROOT / "build-logic/gradle/verification-metadata.xml"
    )
    unverified_locks = locked_coordinates - verified_coordinates
    if unverified_locks:
        fail(f"locked components lack verification metadata: {sorted(unverified_locks)}")

    ui_root = ROOT / "docs/UI设计稿与实现契约_v1.0"
    tokens = json.loads((ui_root / "android_ledger_ui_tokens_v1.json").read_text(encoding="utf-8"))
    contract = yaml.safe_load((ui_root / "android_ledger_screen_contract_v1.yaml").read_text(encoding="utf-8"))
    with (ui_root / "UI需求追踪矩阵_v1.csv").open(encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream)
        requirement_id_column = reader.fieldnames[0] if reader.fieldnames else ""
        requirements = list(reader)
    screens = contract["screens"]
    if walk_scalars(tokens) != 434:
        fail("token JSON scalar traversal count differs")
    if len(requirements) != 90 or {row[requirement_id_column] for row in requirements} != {f"REQ-{i:03d}" for i in range(1, 91)}:
        fail("requirement matrix is not the complete REQ-001..REQ-090 set")
    if len(screens) != 215 or len({screen["id"] for screen in screens}) != 215 or len({screen["route"] for screen in screens}) != 215:
        fail("screen YAML IDs/routes are not complete and unique")

    with (ROOT / "docs/implementation/SCREEN_COVERAGE.csv").open(encoding="utf-8", newline="") as stream:
        screen_coverage = list(csv.DictReader(stream))
    cumulative_promotions = {
        "REC-009": "IN_PROGRESS",
        "REC-010": "IN_PROGRESS",
        "ATT-001": "VERIFIED",
        "ATT-002": "VERIFIED",
        "ATT-003": "VERIFIED",
        "SYS-001": "VERIFIED",
        **{f"G-{number:03d}": "VERIFIED" for number in range(1, 9)},
        **{f"ONB-{number:03d}": "VERIFIED" for number in range(1, 11)},
        "MGT-001": "VERIFIED",
        **{f"ACC-{number:03d}": "VERIFIED" for number in range(1, 13)},
        **{f"CAT-{number:03d}": "VERIFIED" for number in range(1, 5)},
        **{f"MER-{number:03d}": "VERIFIED" for number in range(1, 4)},
        **{f"PLC-{number:03d}": "VERIFIED" for number in range(1, 4)},
        **{f"REC-{number:03d}": "VERIFIED" for number in range(1, 13)},
        "REC-013": "VERIFIED",
        "REC-015": "VERIFIED",
        "REC-016": "VERIFIED",
        "REC-020": "VERIFIED",
        "REC-021": "VERIFIED",
        "REC-022": "VERIFIED",
        "SETG-004": "VERIFIED",
        **{f"JRN-{number:03d}": "VERIFIED" for number in range(1, 13)},
        **{f"BUD-{number:03d}": "VERIFIED" for number in range(1, 9)},
        **{f"PRJ-{number:03d}": "VERIFIED" for number in range(1, 7)},
        **{f"GOL-{number:03d}": "VERIFIED" for number in range(1, 6)},
        "REC-014": "VERIFIED",
        **{f"CRD-{number:03d}": "VERIFIED" for number in range(1, 9)},
        "REC-027": "VERIFIED",
        **{f"INS-{number:03d}": "VERIFIED" for number in range(1, 7)},
        "REC-017": "VERIFIED",
        "REC-018": "VERIFIED",
        "REC-019": "VERIFIED",
        "LIA-001": "VERIFIED",
        **{f"LOA-{number:03d}": "VERIFIED" for number in range(1, 12)},
        **{f"SET-{number:03d}": "VERIFIED" for number in range(1, 9)},
        "REC-026": "VERIFIED",
        **{f"AUT-{number:03d}": "VERIFIED" for number in range(1, 11)},
        "REC-023": "VERIFIED",
        "REC-024": "VERIFIED",
        "REC-025": "VERIFIED",
        "ANA-001": "VERIFIED",
        "ANA-002": "VERIFIED",
        "ANA-003": "VERIFIED",
        "ANA-004": "VERIFIED",
        "ANA-005": "VERIFIED",
        "ANA-015": "VERIFIED",
        "ANA-006": "VERIFIED",
        "ANA-007": "VERIFIED",
        "ANA-008": "VERIFIED",
        "ANA-009": "VERIFIED",
        "ANA-010": "VERIFIED",
        "ANA-013": "VERIFIED",
        "ANA-014": "VERIFIED",
        "ANA-011": "VERIFIED",
        "ANA-012": "VERIFIED",
    }
    if len(screen_coverage) != 215 or any(
        row["status"] != cumulative_promotions.get(row["screen_id"], "NOT_STARTED") for row in screen_coverage
    ):
        fail("screen coverage contains a promotion outside the cumulative P27 scope")

    print("P01 build baseline: PASS")
    print(f"leaf_modules={len(LEAF_MODULES)} grouping_projects=5 included_builds=1")
    print(f"pure_kotlin_modules={len(PURE_KOTLIN_MODULES)} feature_modules=12")
    print(f"dependency_lockfiles={len(lockfiles)} verification_components={len(components)} sha256_entries={len(checksums)}")
    print(f"wrapper_jar_sha256={wrapper_jar_sha}")
    print("token_scalar_paths=434 requirements=90 screens=215 unique_ids=215 unique_routes=215")
    print("production_placeholders=0 xml_main_ui_files=0 forbidden_stack_references=0 prerelease_production_lock_entries=0")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AssertionError as error:
        print(f"P01 build baseline: FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
