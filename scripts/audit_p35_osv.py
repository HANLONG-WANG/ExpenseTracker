#!/usr/bin/env python3
"""Audit the generated CycloneDX Maven inventory against OSV's batch API."""

from __future__ import annotations

import json
import sys
import urllib.request
from pathlib import Path
from urllib.parse import quote


ROOT = Path(__file__).resolve().parents[1]
SBOM = ROOT / "build/reports/cyclonedx/bom.json"
REPORT = ROOT / "build/reports/security/p35-osv.json"
OSV_BATCH = "https://api.osv.dev/v1/querybatch"
APP_LOCK = ROOT / "app/gradle.lockfile"
RELEASE_CONFIGURATION = "releaseRuntimeClasspath"


def normalize_purl(purl: str) -> str:
    return purl.split("?", maxsplit=1)[0]


def maven_queries() -> tuple[list[dict], list[str]]:
    document = json.loads(SBOM.read_text(encoding="utf-8"))
    purls = sorted(
        {
            normalize_purl(component["purl"])
            for component in document.get("components", [])
            if component.get("purl", "").startswith("pkg:maven/")
        }
    )
    return ([{"package": {"purl": purl}} for purl in purls], purls)


def release_runtime_purls(lock_text: str | None = None) -> set[str]:
    lines = (lock_text if lock_text is not None else APP_LOCK.read_text(encoding="utf-8")).splitlines()
    result: set[str] = set()
    for line in lines:
        if not line or line.startswith("#") or "=" not in line:
            continue
        coordinates, configurations = line.split("=", maxsplit=1)
        if RELEASE_CONFIGURATION not in configurations.split(","):
            continue
        parts = coordinates.rsplit(":", maxsplit=2)
        if len(parts) != 3:
            continue
        group, artifact, version = parts
        result.add(f"pkg:maven/{quote(group, safe='.')}/{quote(artifact, safe='.-_')}@{quote(version, safe='.-_')}")
    return result


def classify_vulnerabilities(
    purls: list[str],
    results: list[dict],
    release_purls: set[str],
) -> tuple[list[dict], list[dict]]:
    release: list[dict] = []
    non_release: list[dict] = []
    for purl, result in zip(purls, results, strict=True):
        if not result.get("vulns"):
            continue
        finding = {"purl": purl, "vulnerabilities": result["vulns"]}
        (release if purl in release_purls else non_release).append(finding)
    return release, non_release


def main() -> int:
    queries, purls = maven_queries()
    request = urllib.request.Request(
        OSV_BATCH,
        data=json.dumps({"queries": queries}).encode("utf-8"),
        headers={"Content-Type": "application/json", "User-Agent": "expense-tracker-p35-audit/1"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        results = json.load(response).get("results", [])
    release_purls = release_runtime_purls()
    release_vulnerabilities, non_release_findings = classify_vulnerabilities(purls, results, release_purls)
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text(
        json.dumps(
            {
                "schemaVersion": 1,
                "source": OSV_BATCH,
                "allSbomMavenComponentsAudited": len(purls),
                "releaseRuntimeComponentsAudited": len(release_purls),
                "releaseConfiguration": RELEASE_CONFIGURATION,
                "releaseVulnerableComponents": release_vulnerabilities,
                "nonReleaseToolingAndTestFindings": non_release_findings,
                "nonReleasePolicy": "RECORDED_NOT_PACKAGED_IN_RELEASE_APK",
            },
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )
    if release_vulnerabilities:
        print(
            f"OSV release audit found {len(release_vulnerabilities)} vulnerable Maven components; see {REPORT}",
            file=sys.stderr,
        )
        return 1
    print(
        f"OSV release audit passed for {len(release_purls)} runtime components; "
        f"recorded {len(non_release_findings)} non-release tooling/test findings; report={REPORT}",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
