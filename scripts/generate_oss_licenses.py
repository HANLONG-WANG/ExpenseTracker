#!/usr/bin/env python3
"""Render a deterministic OSS inventory from the aggregate CycloneDX JSON SBOM."""

from __future__ import annotations

import csv
import html
import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SBOM = ROOT / "build/reports/cyclonedx/bom.json"
OUTPUT = ROOT / "build/reports/dependency-license"
FIRST_PARTY_GROUP_PREFIX = "app.ledger"
MANUAL_LICENSES = {
    ("javax.annotation", "javax.annotation-api", "1.3.2"): "CDDL-1.1 OR GPL-2.0-only WITH Classpath-exception-2.0",
    ("net.zetetic", "sqlcipher-android", "4.17.0"): "SQLCipher Community Edition License (BSD-style)",
}


def license_text(component: dict[str, object]) -> str:
    values: list[str] = []
    for entry in component.get("licenses", []):
        if "expression" in entry:
            values.append(str(entry["expression"]))
            continue
        license_data = entry.get("license", {})
        value = license_data.get("id") or license_data.get("name")
        if value:
            values.append(str(value))
    coordinate = (
        str(component.get("group", "")),
        str(component.get("name", "")),
        str(component.get("version", "")),
    )
    return " OR ".join(sorted(set(values))) or MANUAL_LICENSES.get(coordinate, "UNKNOWN")


def main() -> int:
    if not SBOM.is_file():
        print(f"missing aggregate SBOM: {SBOM}", file=sys.stderr)
        return 1
    document = json.loads(SBOM.read_text(encoding="utf-8"))
    rows = sorted(
        {
            (
                str(component.get("group", "")),
                str(component.get("name", "")),
                str(component.get("version", "")),
                license_text(component),
                str(component.get("purl", "")),
            )
            for component in document.get("components", [])
            if component.get("name") and not str(component.get("group", "")).startswith(FIRST_PARTY_GROUP_PREFIX)
        },
    )
    if not rows:
        print("aggregate SBOM contains no dependency components", file=sys.stderr)
        return 1

    OUTPUT.mkdir(parents=True, exist_ok=True)
    with (OUTPUT / "licenses.csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(("group", "name", "version", "license", "purl"))
        writer.writerows(rows)

    table_rows = "\n".join(
        "<tr>" + "".join(f"<td>{html.escape(value)}</td>" for value in row) + "</tr>" for row in rows
    )
    (OUTPUT / "index.html").write_text(
        "<!doctype html><html><head><meta charset=\"utf-8\"><title>ExpenseTracker OSS licenses</title></head>"
        "<body><h1>ExpenseTracker OSS licenses</h1><table><thead><tr>"
        "<th>Group</th><th>Name</th><th>Version</th><th>License</th><th>PURL</th>"
        f"</tr></thead><tbody>{table_rows}</tbody></table></body></html>\n",
        encoding="utf-8",
    )
    notice_rows = "\n".join(
        f"{group}:{name}:{version}\n  License: {license_name}\n  Package: {purl}"
        for group, name, version, license_name, purl in rows
    )
    (OUTPUT / "THIRD_PARTY_NOTICES.txt").write_text(
        "ExpenseTracker 1.0.0 — Third-Party Notices\n\n"
        "This deterministic inventory is generated from the locked CycloneDX dependency graph. "
        "Copyright remains with the respective authors and contributors. Refer to the package "
        "coordinates and upstream source distributions for the complete license texts.\n\n"
        f"{notice_rows}\n",
        encoding="utf-8",
    )
    unknown = sum(row[3] == "UNKNOWN" for row in rows)
    if unknown:
        print(f"OSS license inventory contains unresolved metadata: components={len(rows)} unknown={unknown}", file=sys.stderr)
        return 1
    print(f"OSS license inventory: PASS third-party-components={len(rows)} unknown=0")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
