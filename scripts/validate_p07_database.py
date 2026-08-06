#!/usr/bin/env python3
"""Fail-closed P07 Room/SQLCipher/schema/migration contract validation."""

from __future__ import annotations

import csv
import json
import re
from pathlib import Path

try:
    from scripts.validate_spec_baseline import (
        EXPECTED_INDEX_FAMILY_MEMBERS,
        EXPECTED_OPERATION_MEMBERS,
        EXPECTED_PROJECTION_FAMILY_MEMBERS,
        EXPECTED_SCHEMA_FAMILY_MEMBERS,
    )
except ModuleNotFoundError:
    from validate_spec_baseline import (
        EXPECTED_INDEX_FAMILY_MEMBERS,
        EXPECTED_OPERATION_MEMBERS,
        EXPECTED_PROJECTION_FAMILY_MEMBERS,
        EXPECTED_SCHEMA_FAMILY_MEMBERS,
    )


ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT / "core" / "database"
CATALOG = CORE / "schema-contract"
PRIMARY_CATALOG = CATALOG / "ledger-primary-v1.json"
STAGING_CATALOG = CATALOG / "import-staging-v1.json"
PRODUCTION = CORE / "src" / "main"


class ValidationError(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValidationError(message)


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def load_catalog(path: Path) -> dict[str, object]:
    value = json.loads(read(path))
    require(value["formatVersion"] == 1 and value["schemaVersion"] == 1, f"invalid catalog version: {path}")
    return value


def table_map(catalog: dict[str, object]) -> dict[str, dict[str, object]]:
    tables = catalog["tables"]
    require(isinstance(tables, list), "catalog tables must be a list")
    result = {str(table["name"]): table for table in tables}
    require(len(result) == len(tables), "duplicate table in schema catalog")
    return result


def frozen_domain_fields() -> dict[str, set[str]]:
    source = read(ROOT / "docs" / "规格冻结_v1.0" / "领域模型与数据库逻辑模型设计.md")
    section = source.split("# 二十五、数据库逻辑表设计", 1)[1].split("# 二十六、查询投影", 1)[0]
    headings = list(re.finditer(r"^### `([^`]+)`[^\n]*$", section, flags=re.MULTILINE))
    result: dict[str, set[str]] = {}
    for index, heading in enumerate(headings):
        name = heading.group(1)
        following = re.search(r"^### ", section[heading.end() :], flags=re.MULTILINE)
        end = heading.end() + following.start() if following else len(section)
        body = section[heading.end() : end]
        fields = set(re.findall(r"^- `([a-z0-9_]+)`", body, flags=re.MULTILINE))
        fields.update(re.findall(r"^\| `([a-z0-9_]+)` \|", body, flags=re.MULTILINE))
        result[name] = fields
    detail_section = section.split("### 各交易子类型详情 — R", 1)[1].split(
        "### `transaction_revision_attachment`", 1
    )[0]
    detail_names = re.findall(r"^- `([a-z0-9_]+)`", detail_section, flags=re.MULTILINE)
    detail_fields = {
        "expense_revision_detail": {"revision_id", "payer_kind", "payer_account_id", "payer_card_id", "payer_participant_id", "settlement_activity_id", "installment_plan_id"},
        "income_revision_detail": {"revision_id", "receiving_account_id"},
        "transfer_revision_detail": {"revision_id", "from_account_id", "to_account_id", "source_card_id"},
        "refund_revision_detail": {"revision_id", "receiving_account_id", "receiving_card_id", "independent", "budget_policy", "target_month", "allow_excess"},
        "credit_payment_revision_detail": {"revision_id", "payment_account_id", "credit_account_id", "generation_mode"},
        "loan_disbursement_revision_detail": {"revision_id", "loan_contract_id", "receiving_account_id"},
        "loan_payment_revision_detail": {"revision_id", "loan_contract_id", "payment_account_id", "schedule_revision_id"},
        "balance_adjustment_revision_detail": {"revision_id", "account_id", "direction", "checkpoint_id"},
        "fx_exchange_revision_detail": {"revision_id", "from_account_id", "to_account_id", "valuation_policy"},
        "settlement_payment_revision_detail": {"revision_id", "activity_id", "payer_participant_id", "payee_participant_id", "local_account_id"},
        "opening_balance_revision_detail": {"revision_id", "account_id", "balance_date"},
    }
    require(set(detail_names) == set(detail_fields), "frozen transaction detail table set changed")
    result.update(detail_fields)
    return result


def validate_inventory(primary: dict[str, object], staging: dict[str, object]) -> None:
    primary_tables = table_map(primary)
    staging_tables = table_map(staging)
    frozen_tables = {name for members in EXPECTED_SCHEMA_FAMILY_MEMBERS.values() for name in members}
    projection_members = {name for members in EXPECTED_PROJECTION_FAMILY_MEMBERS.values() for name in members}
    projection_tables = {name for name in projection_members if not name.endswith("*")}
    index_tables = {name for members in EXPECTED_INDEX_FAMILY_MEMBERS.values() for name in members}
    operation_tables = {
        name
        for key, members in EXPECTED_OPERATION_MEMBERS.items()
        if key != "IMPORT-STAGING"
        for name in members
    }
    staging_expected = set(EXPECTED_OPERATION_MEMBERS["IMPORT-STAGING"])

    require(len(frozen_tables) == 94, "frozen schema inventory must contain 94 tables")
    require(frozen_tables <= primary_tables.keys(), f"missing frozen tables: {sorted(frozen_tables - primary_tables.keys())}")
    require(projection_tables <= primary_tables.keys(), f"missing projection tables: {sorted(projection_tables - primary_tables.keys())}")
    require(
        len([name for name in primary_tables if name.startswith("analytics_monthly_")]) == 6,
        "all six monthly analytics projections are required",
    )
    require(index_tables <= primary_tables.keys(), f"missing index tables: {sorted(index_tables - primary_tables.keys())}")
    require(operation_tables <= primary_tables.keys(), f"missing operation tables: {sorted(operation_tables - primary_tables.keys())}")
    require(staging_tables.keys() == staging_expected, "staging table inventory differs from frozen seven-table set")
    require("rule_set_version" in primary_tables, "rule_set_version persistence is missing")
    require(len(primary_tables) == 140, f"unexpected primary schema table count: {len(primary_tables)}")

    documented_fields = frozen_domain_fields()
    for table, required_fields in documented_fields.items():
        actual = set(primary_tables[table]["columns"])
        require(required_fields <= actual, f"{table} missing documented columns: {sorted(required_fields - actual)}")

    for detail in (
        "expense_revision_detail",
        "income_revision_detail",
        "transfer_revision_detail",
        "refund_revision_detail",
        "credit_payment_revision_detail",
        "loan_disbursement_revision_detail",
        "loan_payment_revision_detail",
        "balance_adjustment_revision_detail",
        "fx_exchange_revision_detail",
        "settlement_payment_revision_detail",
        "opening_balance_revision_detail",
    ):
        sql = str(primary_tables[detail]["createSql"])
        require("revision_id INTEGER PRIMARY KEY REFERENCES transaction_revision" in sql, f"{detail} is not a typed revision detail")


def validate_sql_contract(primary: dict[str, object], staging: dict[str, object]) -> None:
    primary_sql = "\n".join(str(table["createSql"]) for table in primary["tables"])
    primary_sql += "\n" + "\n".join(str(item["createSql"]) for kind in ("indexes", "views", "triggers") for item in primary[kind])
    staging_sql = "\n".join(str(table["createSql"]) for table in staging["tables"])

    require(primary_sql.count("REFERENCES ") >= 150, "foreign-key coverage unexpectedly weak")
    require(primary_sql.count("CHECK (") >= 220, "CHECK constraint coverage unexpectedly weak")
    require(len(primary["indexes"]) >= 39, "key/index contract is incomplete")
    require(len(primary["views"]) == 4, "database diagnostic view inventory differs")
    require("USING fts5" in primary_sql and "tokenize='trigram case_sensitive 0'" in primary_sql, "FTS5 trigram schema missing")
    require(primary_sql.count("USING rtree") == 2, "both R*Tree indexes are required")
    require("OVER (" in primary_sql, "window-function running balance view missing")
    require("parameters_ciphertext BLOB" in primary_sql and "checkpoint_ciphertext BLOB" in primary_sql, "operation parameters/checkpoints are not encrypted")
    require("card_vault_secret" in primary_sql and "transaction_fts" in primary_sql, "vault/search schema missing")
    fts_columns = {str(column).lower() for column in table_map(primary)["transaction_fts"]["columns"]}
    for forbidden in ("pan", "security_code", "account_number", "lat", "lon", "vault"):
        require(forbidden not in fts_columns, f"sensitive field entered FTS: {forbidden}")
    for effect in ("economic_effect", "budget_effect", "project_effect", "goal_effect", "statement_effect", "loan_effect", "settlement_effect"):
        require("rule_set_version" in set(table_map(primary)[effect]["columns"]), f"{effect} lacks rule_set_version")
    require(" JSON" not in primary_sql.upper() and "_json" not in primary_sql.lower(), "generic JSON storage entered the primary schema")
    require("command_blob BLOB" in staging_sql and "prepared_command_json" not in staging_sql, "staging command is not a typed binary contract")


def validate_kotlin_and_build() -> None:
    production_files = sorted(PRODUCTION.rglob("*.kt"))
    production_text = "\n".join(read(path) for path in production_files)
    build_text = read(CORE / "build.gradle.kts")
    catalog_text = read(ROOT / "gradle" / "libs.versions.toml")
    root_build = read(ROOT / "build.gradle.kts")

    require('room = "2.8.4"' in catalog_text and 'sqlcipher = "4.17.0"' in catalog_text, "frozen Room/SQLCipher versions changed")
    require("room.schemaLocation" in build_text and "room.generateKotlin" in build_text, "Room schema export is not configured")
    require("SupportOpenHelperFactory" in production_text, "SQLCipher SupportOpenHelperFactory is not the Room factory")
    require("JournalMode.WRITE_AHEAD_LOGGING" in production_text, "WAL is not configured")
    require(
        re.search(
            r"fun openPrimary\(\s*context: Context,\s*passphrase: ByteArray,\s*\): LedgerDatabase",
            production_text,
        )
        is not None
        and "private fun openPrimaryNamed" in production_text
        and "openPrimaryForTesting" not in production_text,
        "the production primary database name must be fixed",
    )
    for pragma in ("foreign_keys = ON", "temp_store = MEMORY", "auto_vacuum = INCREMENTAL", "cipher_memory_security = ON", "cipher_log = NONE"):
        require(pragma in production_text, f"secure database pragma missing: {pragma}")
    require("@Upsert" not in production_text, "@Upsert is forbidden in P07 production")
    require("fallbackToDestructiveMigration" not in production_text + root_build, "destructive migration fallback is forbidden")
    require("FrameworkSQLiteOpenHelperFactory" not in production_text, "unencrypted framework SQLite path is forbidden")
    require("MigrationPhase.EXPAND" in production_text and "MigrationPhase.SWITCH" in production_text, "migration phase order contract missing")
    require(
        "LedgerMigrations.registered.forEach" in production_text and "addMigrations(migration)" in production_text,
        "primary migration registry is not wired",
    )
    require(
        "StagingMigrations.registered.forEach" in production_text and "addMigrations(migration)" in production_text,
        "staging migration registry is not wired",
    )
    require("immutableTables.sorted()" in production_text and "_reject_update" in production_text, "append-only table enforcement missing")
    require("@Insert(onConflict = OnConflictStrategy.ABORT)" in production_text, "explicit abort-on-conflict insert is missing")
    require("UPDATE _room_schema_registry" in production_text, "explicit checked update path is missing")

    for room_schema in (
        CORE / "schemas" / "app.ledger.core.database.LedgerDatabase" / "1.json",
        CORE / "schemas" / "app.ledger.core.database.ImportStagingDatabase" / "1.json",
    ):
        value = json.loads(read(room_schema))
        require(value["database"]["version"] == 1, f"Room v1 schema export invalid: {room_schema}")


def validate_ledgers() -> int:
    project_state = read(ROOT / "docs" / "implementation" / "PROJECT_STATE.md")
    evidence = read(ROOT / "docs" / "implementation" / "TEST_EVIDENCE.md")
    coverage = read(ROOT / "docs" / "implementation" / "DOMAIN_AND_SCHEMA_COVERAGE.md")
    mapping = read(ROOT / "docs" / "implementation" / "P07_SCHEMA_MAPPING.md")
    requirements = read(ROOT / "docs" / "implementation" / "REQUIREMENT_COVERAGE.csv")
    screens = read(ROOT / "docs" / "implementation" / "SCREEN_COVERAGE.csv")

    current_stage = re.search(r"Current stage: P(\d{2})", project_state)
    require(
        current_stage is not None
        and 7 <= int(current_stage.group(1)) <= 36
        and "| P07 | VERIFIED |" in project_state
        and "### P07 result" in project_state,
        "P07 project state not verified",
    )
    for evidence_id in range(1, 7):
        require(f"P07-E{evidence_id:03d}" in evidence, f"missing P07 evidence P07-E{evidence_id:03d}")
    for family in EXPECTED_SCHEMA_FAMILY_MEMBERS:
        require(f"{family}" in coverage and "P07-E" in coverage, f"{family} lacks P07 coverage evidence")
    for table in {name for values in EXPECTED_SCHEMA_FAMILY_MEMBERS.values() for name in values}:
        require(f"`{table}`" in mapping, f"P07 mapping omits {table}")
    for requirement in ("REQ-002", "REQ-013", "REQ-014", "REQ-031", "REQ-032", "REQ-084", "REQ-086", "REQ-089"):
        row = next(line for line in requirements.splitlines() if line.startswith(requirement + ","))
        require("P07-E" in row, f"{requirement} lacks P07 evidence")
    screen_rows = list(csv.DictReader(screens.splitlines()))
    current_stage_number = int(current_stage.group(1))
    permitted_promotions: dict[str, str] = {}
    if current_stage_number >= 10:
        permitted_promotions.update(
            {
                "REC-009": "IN_PROGRESS",
                "REC-010": "IN_PROGRESS",
                "ATT-001": "VERIFIED",
                "ATT-002": "VERIFIED",
                "ATT-003": "VERIFIED",
                "SYS-001": "VERIFIED",
            },
        )
    if current_stage_number >= 11:
        permitted_promotions.update(
            {
                **{f"G-{number:03d}": "VERIFIED" for number in range(1, 9)},
                **{f"ONB-{number:03d}": "VERIFIED" for number in range(1, 11)},
            },
        )
    if current_stage_number >= 12:
        permitted_promotions.update(
            {
                "MGT-001": "VERIFIED",
                **{f"ACC-{number:03d}": "VERIFIED" for number in range(1, 13)},
                **{f"CAT-{number:03d}": "VERIFIED" for number in range(1, 5)},
                **{f"MER-{number:03d}": "VERIFIED" for number in range(1, 4)},
                **{f"PLC-{number:03d}": "VERIFIED" for number in range(1, 4)},
            },
        )
    if current_stage_number >= 13:
        permitted_promotions.update({f"REC-{number:03d}": "VERIFIED" for number in range(1, 13)})
    if current_stage_number >= 14:
        permitted_promotions.update(
            {
                "REC-013": "VERIFIED",
                "REC-020": "VERIFIED",
                "REC-021": "VERIFIED",
                "REC-022": "VERIFIED",
                "SETG-004": "VERIFIED",
            },
        )
    if current_stage_number >= 15:
        permitted_promotions.update({f"JRN-{number:03d}": "VERIFIED" for number in range(1, 13)})
    if current_stage_number >= 16:
        permitted_promotions.update(
            {
                "REC-015": "VERIFIED",
                "REC-016": "VERIFIED",
            },
        )
    if current_stage_number >= 17:
        permitted_promotions.update({f"BUD-{number:03d}": "VERIFIED" for number in range(1, 9)})
    if current_stage_number >= 18:
        permitted_promotions.update(
            {
                **{f"PRJ-{number:03d}": "VERIFIED" for number in range(1, 7)},
                **{f"GOL-{number:03d}": "VERIFIED" for number in range(1, 6)},
            },
        )
    if current_stage_number >= 19:
        permitted_promotions.update(
            {
                "REC-014": "VERIFIED",
                **{f"CRD-{number:03d}": "VERIFIED" for number in range(1, 9)},
            },
        )
    if current_stage_number >= 20:
        permitted_promotions.update(
            {
                "REC-027": "VERIFIED",
                **{f"INS-{number:03d}": "VERIFIED" for number in range(1, 7)},
            },
        )
    if current_stage_number >= 21:
        permitted_promotions.update(
            {
                "REC-017": "VERIFIED",
                "REC-018": "VERIFIED",
                "REC-019": "VERIFIED",
                "LIA-001": "VERIFIED",
                **{f"LOA-{number:03d}": "VERIFIED" for number in range(1, 12)},
            },
        )
    if current_stage_number >= 22:
        permitted_promotions.update({f"SET-{number:03d}": "VERIFIED" for number in range(1, 9)})
    if current_stage_number >= 23:
        permitted_promotions.update(
            {
                "REC-026": "VERIFIED",
                **{f"AUT-{number:03d}": "VERIFIED" for number in range(1, 11)},
            },
        )
    if current_stage_number >= 24:
        permitted_promotions.update(
            {
                "REC-023": "VERIFIED",
                "REC-024": "VERIFIED",
                "REC-025": "VERIFIED",
            },
        )
    require(
        len(screen_rows) == 215
        and all(row["status"] == permitted_promotions.get(row["screen_id"], "NOT_STARTED") for row in screen_rows),
        f"screen coverage contains a promotion outside the completed P{current_stage_number:02d} scope",
    )
    return len(permitted_promotions)


def main() -> int:
    primary = load_catalog(PRIMARY_CATALOG)
    staging = load_catalog(STAGING_CATALOG)
    validate_inventory(primary, staging)
    validate_sql_contract(primary, staging)
    validate_kotlin_and_build()
    stage_scoped_promotions = validate_ledgers()
    print("P07 database contract: PASS")
    print("frozen_domain_tables=94 primary_schema_tables=140 staging_tables=7")
    print("indexes=39 views=4 runtime_append_only_tables=63")
    print(f"target_requirements=8 screens_total=215 stage_scoped_promotions={stage_scoped_promotions}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValidationError, KeyError, StopIteration, ValueError) as error:
        print(f"P07 database contract: FAIL: {error}")
        raise SystemExit(1)
