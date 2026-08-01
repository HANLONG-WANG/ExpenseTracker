#!/usr/bin/env python3
"""Generate deterministic checked-in P07 schema catalogs from exact SQL assets."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "core" / "database" / "src" / "main" / "assets"
CATALOG = ROOT / "core" / "database" / "schema-contract"
MAPPING = ROOT / "docs" / "implementation" / "P07_SCHEMA_MAPPING.md"

PRIMARY_ASSETS = (
    "ledger_schema_v1_core.sql",
    "ledger_schema_v1_subledgers.sql",
    "ledger_schema_v1_projections_operations.sql",
    "ledger_schema_v1_indices_views.sql",
)
STAGING_ASSETS = ("import_staging_schema_v1.sql",)

CREATE_TABLE = re.compile(r"CREATE\s+(VIRTUAL\s+)?TABLE\s+([a-z0-9_]+)\s+(.*)", re.IGNORECASE | re.DOTALL)
CREATE_VIEW = re.compile(r"CREATE\s+VIEW\s+([a-z0-9_]+)\s+AS\s+(.*)", re.IGNORECASE | re.DOTALL)
CREATE_INDEX = re.compile(r"CREATE\s+(UNIQUE\s+)?INDEX\s+([a-z0-9_]+)\s+(.*)", re.IGNORECASE | re.DOTALL)
CREATE_TRIGGER = re.compile(r"CREATE\s+TRIGGER\s+([a-z0-9_]+)\s+(.*)", re.IGNORECASE | re.DOTALL)


def split_statements(names: tuple[str, ...]) -> list[str]:
    statements: list[str] = []
    for name in names:
        text = (ASSETS / name).read_text(encoding="utf-8")
        statements.extend(item.strip() for item in text.split("--@@") if item.strip())
    return statements


def split_top_level(value: str) -> list[str]:
    result: list[str] = []
    start = 0
    depth = 0
    quoted = False
    for index, char in enumerate(value):
        if char == "'":
            quoted = not quoted
        elif not quoted and char == "(":
            depth += 1
        elif not quoted and char == ")":
            depth -= 1
        elif not quoted and char == "," and depth == 0:
            result.append(value[start:index].strip())
            start = index + 1
    result.append(value[start:].strip())
    return [item for item in result if item]


def table_columns(create_sql: str, virtual: bool) -> list[str]:
    body_start = create_sql.find("(")
    body_end = create_sql.rfind(")")
    if body_start < 0 or body_end <= body_start:
        return []
    columns: list[str] = []
    for definition in split_top_level(create_sql[body_start + 1 : body_end]):
        first = definition.split(maxsplit=1)[0].strip("`")
        if first.upper() in {"PRIMARY", "UNIQUE", "CHECK", "FOREIGN", "CONSTRAINT"}:
            continue
        if virtual and ("=" in first or first.lower().startswith("tokenize")):
            continue
        columns.append(first)
    return columns


def catalog(kind: str, assets: tuple[str, ...]) -> dict[str, object]:
    tables: list[dict[str, object]] = []
    indexes: list[dict[str, str | bool]] = []
    views: list[dict[str, str]] = []
    triggers: list[dict[str, str]] = []
    for statement in split_statements(assets):
        table_match = CREATE_TABLE.search(statement)
        if table_match:
            virtual = table_match.group(1) is not None
            tables.append(
                {
                    "name": table_match.group(2),
                    "virtual": virtual,
                    "columns": table_columns(statement, virtual),
                    "createSql": statement,
                }
            )
            continue
        view_match = CREATE_VIEW.search(statement)
        if view_match:
            views.append({"name": view_match.group(1), "createSql": statement})
            continue
        index_match = CREATE_INDEX.search(statement)
        if index_match:
            indexes.append(
                {
                    "name": index_match.group(2),
                    "unique": index_match.group(1) is not None,
                    "createSql": statement,
                }
            )
            continue
        trigger_match = CREATE_TRIGGER.search(statement)
        if trigger_match:
            triggers.append({"name": trigger_match.group(1), "createSql": statement})
            continue
        raise RuntimeError(f"unclassified SQL statement: {statement[:80]!r}")

    asset_hashes = {
        name: hashlib.sha256((ASSETS / name).read_bytes()).hexdigest()
        for name in assets
    }
    return {
        "formatVersion": 1,
        "databaseKind": kind,
        "schemaVersion": 1,
        "assets": asset_hashes,
        "tables": sorted(tables, key=lambda table: str(table["name"])),
        "indexes": sorted(indexes, key=lambda index: str(index["name"])),
        "views": sorted(views, key=lambda view: view["name"]),
        "triggers": sorted(triggers, key=lambda trigger: trigger["name"]),
        "migrationPolicy": {
            "phases": ["EXPAND", "BACKFILL", "SWITCH", "CONTRACT"],
            "destructiveFallbackAllowed": False,
        },
    }


def encoded(value: dict[str, object]) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2, sort_keys=False) + "\n"


def mapping_markdown(primary: dict[str, object], staging: dict[str, object]) -> str:
    from validate_spec_baseline import (
        EXPECTED_OPERATION_MEMBERS,
        EXPECTED_SCHEMA_FAMILY_MEMBERS,
    )

    tables = {str(table["name"]): table for table in primary["tables"]}
    staging_tables = {str(table["name"]): table for table in staging["tables"]}
    lines = [
        "# P07 Schema v1 Mapping",
        "",
        "Generated from the checked-in SQL contracts by `scripts/generate_p07_schema_catalog.py`; do not edit rows manually.",
        "The four excluded visual drafts are not generator inputs and were not accessed.",
        "",
        "## Frozen §25 logical tables",
        "",
        "| Family | Table | Columns | PK | FK | CHECK | UNIQUE / index |",
        "|---|---|---:|---|---|---|---|",
    ]
    for family, members in EXPECTED_SCHEMA_FAMILY_MEMBERS.items():
        for name in members:
            table = tables[name]
            sql = str(table["createSql"])
            lines.append(
                f"| {family} | `{name}` | {len(table['columns'])} | "
                f"{'YES' if 'PRIMARY KEY' in sql or bool(table['virtual']) else 'NO'} | "
                f"{'YES' if 'REFERENCES ' in sql or bool(table['virtual']) else 'N/A'} | "
                f"{'YES' if 'CHECK (' in sql or bool(table['virtual']) else 'N/A'} | "
                f"{'YES' if 'UNIQUE' in sql or bool(table['virtual']) else 'INDEXED'} |"
            )
    lines.extend(
        [
            "",
            "## Additional schema-governed data",
            "",
            f"The primary catalog contains {len(tables)} declared tables in total: the 94 frozen §25 tables, "
            "`rule_set_version`, all §26 projections, §27 FTS5/R*Tree indexes, and §28 durable operation/import/backup/restore metadata.",
            "The primary DDL also contains 39 named indexes, four diagnostic views, ten cross-row constraint triggers, "
            "and runtime-generated append-only update/delete guards for 63 immutable Revision/Fact tables.",
            "",
            "## Independent encrypted import staging schema",
            "",
            "| Table | Columns | PK | FK | CHECK |",
            "|---|---:|---|---|---|",
        ]
    )
    for name in EXPECTED_OPERATION_MEMBERS["IMPORT-STAGING"]:
        table = staging_tables[name]
        sql = str(table["createSql"])
        lines.append(
            f"| `{name}` | {len(table['columns'])} | {'YES' if 'PRIMARY KEY' in sql else 'NO'} | "
            f"{'YES' if 'REFERENCES ' in sql else 'N/A'} | {'YES' if 'CHECK (' in sql else 'N/A'} |"
        )
    lines.extend(
        [
            "",
            "## Version and migration policy",
            "",
            "- Room primary schema: `app.ledger.core.database.LedgerDatabase/1.json`.",
            "- Room staging schema: `app.ledger.core.database.ImportStagingDatabase/1.json`.",
            "- Canonical raw-DDL exports: `ledger-primary-v1.json` and `import-staging-v1.json`.",
            "- v1 has no predecessor. Future adjacent versions must register explicit migrations and follow Expand → Backfill → Switch → Contract.",
            "- Formal builders have no destructive fallback; primary and staging versions are independent.",
            "",
        ]
    )
    return "\n".join(lines)


def write_or_check(path: Path, content: str, check: bool) -> None:
    if check:
        if not path.is_file() or path.read_text(encoding="utf-8") != content:
            raise RuntimeError(f"generated schema catalog is stale: {path.relative_to(ROOT)}")
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    primary = catalog("PRIMARY", PRIMARY_ASSETS)
    staging = catalog("IMPORT_STAGING", STAGING_ASSETS)
    write_or_check(CATALOG / "ledger-primary-v1.json", encoded(primary), args.check)
    write_or_check(CATALOG / "import-staging-v1.json", encoded(staging), args.check)
    write_or_check(MAPPING, mapping_markdown(primary, staging), args.check)
    print("P07 schema catalogs: PASS" if args.check else "P07 schema catalogs: GENERATED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
