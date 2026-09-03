#!/usr/bin/env python3
"""Validate the frozen P00 specification baseline and its persistent ledgers.

The four visual draft files are deliberately never opened. Their names are read
from MANIFEST.sha256 only so the audit can prove that they remain excluded.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any, Iterable

import yaml


ROOT = Path(__file__).resolve().parents[1]
FROZEN = ROOT / "docs" / "初始开发文件存档" / "规格冻结_v1.0"
UI = ROOT / "docs" / "初始开发文件存档" / "UI设计稿与实现契约_v1.0"
IMPLEMENTATION = ROOT / "docs" / "初始开发文件存档" / "implementation"
PLAN = ROOT / "docs" / "初始开发文件存档" / "Android记账软件_完整开发计划_Codex执行版.md"

REQUIREMENTS_SOURCE = UI / "UI需求追踪矩阵_v1.csv"
SCREENS_SOURCE = UI / "android_ledger_screen_contract_v1.yaml"
TOKENS_SOURCE = UI / "android_ledger_ui_tokens_v1.json"
MANIFEST_SOURCE = UI / "MANIFEST.sha256"

REQUIREMENTS_LEDGER = IMPLEMENTATION / "REQUIREMENT_COVERAGE.csv"
SCREENS_LEDGER = IMPLEMENTATION / "SCREEN_COVERAGE.csv"

STATUSES = {"NOT_STARTED", "IN_PROGRESS", "IMPLEMENTED", "VERIFIED", "BLOCKED"}
FORBIDDEN_VISUALS = {
    "UI视觉样稿_浅色.png",
    "UI视觉样稿_深色.png",
    "UI视觉样稿_完整总览.png",
    "UI视觉样稿_v1.html",
}
MANIFEST_TEXT_INPUTS = {
    "README_交付说明.md",
    "Android记账软件_UI设计系统与实现契约_v1.0.md",
    "android_ledger_ui_tokens_v1.json",
    "android_ledger_screen_contract_v1.yaml",
    "UI需求追踪矩阵_v1.csv",
}

EXPECTED_FROZEN_SHA256 = {
    "docs/初始开发文件存档/规格冻结_v1.0/需求.md": "539723ce5abca31747e1b3d2f75ab705d3acca6b3ecd69ab7552b9ec0ac906b7",
    "docs/初始开发文件存档/规格冻结_v1.0/技术栈.md": "9bc8aa0a214795909f6c0d9cbfebffe73d58b9e0688e56a48dc85bbd805f0dc0",
    "docs/初始开发文件存档/规格冻结_v1.0/系统架构.md": "c8033e8696b52909ea61d4459866b914bee5c9369ccc376beb44b8c1f7e2c171",
    "docs/初始开发文件存档/规格冻结_v1.0/领域模型与数据库逻辑模型设计.md": "e519ea2bd99d2afce305bad720f0c874bb297e7a500e96b404448f08d4d916de",
    "docs/初始开发文件存档/UI设计稿与实现契约_v1.0/README_交付说明.md": "65e90b4329d2f79af3b6e9e6ff3f6d8baf613c3238570de9cfacbcab0d358e97",
    "docs/初始开发文件存档/UI设计稿与实现契约_v1.0/Android记账软件_UI设计系统与实现契约_v1.0.md": "050cbbee9f6236eadd7d3194ea539ed4641b3c2f999957222247e778dc3daaf7",
    "docs/初始开发文件存档/UI设计稿与实现契约_v1.0/android_ledger_ui_tokens_v1.json": "d7be41816bfe1d53b0b9b521de69b60dd193b0a1a040f2c749e3099ef5fc0b1f",
    "docs/初始开发文件存档/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml": "70c5077ee7cc91e996dbeabbcfcaf3b8052b1eb76501a774c4acc249cd3dc3c7",
    "docs/初始开发文件存档/UI设计稿与实现契约_v1.0/UI需求追踪矩阵_v1.csv": "4c587e22497e693594b61995efb3527711b6552b5ecf862efdbe4a89827d1049",
    "docs/初始开发文件存档/UI设计稿与实现契约_v1.0/MANIFEST.sha256": "f1fa76e2ca3ec3da839496471d9f890ee7e830e9238e2e1edfa873a812f924bc",
}

REQ_SOURCE_FIELDS = ["需求ID", "来源章节", "需求摘要", "覆盖页面/流程", "核心组件", "验收条件"]
REQ_LEDGER_FIELDS = [
    "requirement_id",
    "source_section",
    "summary",
    "screens_flows",
    "core_components",
    "acceptance_criteria",
    "status",
    "implementation_evidence",
    "verification_evidence",
    "primary_acceptance_phase",
    "follow_up_review_phases",
    "notes",
]
SCREEN_LEDGER_FIELDS = [
    "screen_id",
    "group",
    "module",
    "route",
    "title",
    "presentation",
    "params",
    "result",
    "required_states",
    "primary_components",
    "notes",
    "requirement_ids",
    "source",
    "status",
    "implementation_evidence",
    "verification_evidence",
    "target_phase",
]


class AuditFailure(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AuditFailure(message)


def assert_text_input(path: Path) -> None:
    """Fail closed before any forbidden visual path reaches a filesystem read."""
    require(path.name not in FORBIDDEN_VISUALS, f"forbidden visual input access rejected: {path.name}")


def read_text_input(path: Path, *, encoding: str = "utf-8") -> str:
    assert_text_input(path)
    return path.read_text(encoding=encoding)


def sha256(path: Path) -> str:
    assert_text_input(path)
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def scalar_paths(value: Any, prefix: tuple[str, ...] = ()) -> Iterable[tuple[tuple[str, ...], Any]]:
    if isinstance(value, dict):
        for key, child in value.items():
            yield from scalar_paths(child, prefix + (str(key),))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from scalar_paths(child, prefix + (str(index),))
    else:
        yield prefix, value


def read_sources() -> tuple[dict[str, Any], dict[str, Any], list[dict[str, str]]]:
    tokens = json.loads(read_text_input(TOKENS_SOURCE))
    screens = yaml.safe_load(read_text_input(SCREENS_SOURCE))
    assert_text_input(REQUIREMENTS_SOURCE)
    with REQUIREMENTS_SOURCE.open(encoding="utf-8-sig", newline="") as stream:
        requirements = list(csv.DictReader(stream))
    return tokens, screens, requirements


def validate_frozen_hashes() -> None:
    for relative, expected in EXPECTED_FROZEN_SHA256.items():
        actual = sha256(ROOT / relative)
        require(actual == expected, f"frozen specification changed: {relative}: {actual}")


def validate_manifest() -> None:
    entries: dict[str, str] = {}
    for line in read_text_input(MANIFEST_SOURCE).splitlines():
        digest, name = line.split(maxsplit=1)
        name = name.strip()
        require(name not in entries, f"duplicate manifest entry: {name}")
        entries[name] = digest
    require(set(entries) == MANIFEST_TEXT_INPUTS | FORBIDDEN_VISUALS, "unexpected MANIFEST entry set")
    for name in sorted(MANIFEST_TEXT_INPUTS):
        require(sha256(UI / name) == entries[name], f"MANIFEST mismatch: {name}")
    # Do not hash, stat, parse or otherwise open the forbidden visual files.


def validate_sources(
    tokens: dict[str, Any], screens_doc: dict[str, Any], requirements: list[dict[str, str]]
) -> tuple[list[dict[str, Any]], set[str]]:
    paths = list(scalar_paths(tokens))
    require(len(paths) == 434, f"expected 434 JSON scalar token paths, got {len(paths)}")
    require(len({path for path, _ in paths}) == len(paths), "duplicate JSON scalar token path")
    require(tokens["meta"]["version"] == "1.0.0", "unexpected token version")
    require(len(tokens["color"]["categoryPalette"]) == 16, "expected 16 category palettes")

    screens = screens_doc["screens"]
    require(screens_doc["meta"]["screenCount"] == 215, "YAML meta screenCount is not 215")
    require(len(screens) == 215, f"expected 215 screens, got {len(screens)}")
    ids = [screen["id"] for screen in screens]
    routes = [screen["route"] for screen in screens]
    require(len(set(ids)) == 215, "screen IDs are not unique")
    require(len(set(routes)) == 215, "screen routes are not unique")
    required_screen_keys = {
        "id",
        "group",
        "module",
        "route",
        "title",
        "presentation",
        "params",
        "result",
        "primaryComponents",
        "requiredStates",
        "notes",
        "requirementTags",
    }
    for screen in screens:
        require(set(screen) == required_screen_keys, f"unexpected screen schema: {screen['id']}")
        require(bool(screen["primaryComponents"]), f"screen has no primary component: {screen['id']}")
        require(bool(screen["requiredStates"]), f"screen has no required state: {screen['id']}")

    require([row.keys() for row in requirements], "requirements CSV has no rows")
    require(list(requirements[0].keys()) == REQ_SOURCE_FIELDS, "unexpected requirements CSV columns")
    expected_req_ids = [f"REQ-{number:03d}" for number in range(1, 91)]
    req_ids = [row["需求ID"] for row in requirements]
    require(req_ids == expected_req_ids, "requirements must be exactly REQ-001 through REQ-090")
    return screens, set(ids)


SCREEN_REF = re.compile(r"(?P<prefix>[A-Z]+-)(?P<start>\d{3})(?:\.\.(?:(?P<endprefix>[A-Z]+-)?(?P<end>\d{3})))?")


def expand_screen_references(text: str) -> set[str]:
    result: set[str] = set()
    for match in SCREEN_REF.finditer(text):
        prefix = match.group("prefix")
        start = int(match.group("start"))
        end_value = match.group("end")
        if end_value is None:
            result.add(f"{prefix}{start:03d}")
            continue
        end_prefix = match.group("endprefix") or prefix
        require(prefix == end_prefix, f"cross-prefix range is not supported: {match.group(0)}")
        end = int(end_value)
        require(start <= end, f"descending screen range: {match.group(0)}")
        result.update(f"{prefix}{number:03d}" for number in range(start, end + 1))
    return result


def target_requirement_phases() -> dict[str, str]:
    text = read_text_input(PLAN)
    pairs = dict(re.findall(r"(REQ-\d{3}) \| (P\d{2})", text))
    expected = {f"REQ-{number:03d}" for number in range(1, 91)}
    require(set(pairs) == expected, "development plan does not map all 90 requirements")
    return pairs


def follow_up_requirement_phases(primary_phase: str) -> str:
    """Apply the plan's systematic P34 full review and P36 final acceptance."""
    phase_number = int(primary_phase.removeprefix("P"))
    if phase_number < 34:
        return "P34 | P36"
    if phase_number < 36:
        return "P36"
    return "NONE"


def screen_target_phase(screen_id: str) -> str:
    prefix, number_text = screen_id.split("-", 1)
    number = int(number_text)
    if prefix == "G":
        return "P11/P33" if number >= 6 else "P11"
    if prefix == "ONB":
        return "P11"
    if prefix == "REC":
        if number in {9, 10}:
            return "P10/P13"
        if number == 11:
            return "P13/P22"
        if number in {15, 16}:
            return "P16"
        if number == 14:
            return "P19"
        if number == 27:
            return "P20"
        if number in {17, 18, 19}:
            return "P21"
        if number == 26:
            return "P23"
        if number in {23, 24, 25}:
            return "P24"
        if number in {13, 20, 21, 22}:
            return "P14"
        return "P13"
    if prefix == "JRN":
        if number in {5, 6}:
            return "P15/P24"
        if number == 12:
            return "P15/P31"
        return "P15"
    fixed = {
        "ACC": "P12",
        "BUD": "P17",
        "PRJ": "P18",
        "GOL": "P18",
        "LIA": "P21",
        "CRD": "P19",
        "INS": "P20",
        "LOA": "P21",
        "SET": "P22",
        "AUT": "P23",
        "MGT": "P12",
        "CAT": "P12",
        "MER": "P12",
        "PLC": "P12",
        "VLT": "P32",
        "TRF": "P33",
        "IMP": "P28",
        "EXP": "P29",
        "BKP": "P30",
        "RST": "P31",
        "ATT": "P10",
        "WGT": "P33",
    }
    if prefix in fixed:
        return fixed[prefix]
    if prefix == "ANA":
        if number in {11, 12}:
            return "P27"
        if number in {6, 7, 8, 9, 10, 13, 14}:
            return "P26/P29" if number == 10 else "P26"
        return "P25"
    if prefix == "SETG":
        if 6 <= number <= 11:
            return "P32/P33" if number == 8 else "P32"
        return "P14/P33" if number == 4 else "P33"
    if prefix == "CLR":
        return "P32" if number == 1 else "P31"
    if prefix == "SYS":
        return {1: "P10", 2: "P33", 3: "P30", 4: "P32"}[number]
    raise AuditFailure(f"no target phase for {screen_id}")


def initialize_ledgers(
    screens: list[dict[str, Any]], requirements: list[dict[str, str]], screen_ids: set[str], force: bool
) -> None:
    IMPLEMENTATION.mkdir(parents=True, exist_ok=True)
    for path in (REQUIREMENTS_LEDGER, SCREENS_LEDGER):
        require(force or not path.exists(), f"refusing to overwrite existing ledger: {path}")

    phases = target_requirement_phases()
    with REQUIREMENTS_LEDGER.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=REQ_LEDGER_FIELDS)
        writer.writeheader()
        for row in requirements:
            writer.writerow(
                {
                    "requirement_id": row["需求ID"],
                    "source_section": row["来源章节"],
                    "summary": row["需求摘要"],
                    "screens_flows": row["覆盖页面/流程"],
                    "core_components": row["核心组件"],
                    "acceptance_criteria": row["验收条件"],
                    "status": "NOT_STARTED",
                    "implementation_evidence": "",
                    "verification_evidence": "",
                    "primary_acceptance_phase": phases[row["需求ID"]],
                    "follow_up_review_phases": follow_up_requirement_phases(phases[row["需求ID"]]),
                    "notes": "P00 baseline only; implementation not claimed.",
                }
            )

    requirements_by_screen: dict[str, set[str]] = defaultdict(set)
    for row in requirements:
        referenced = expand_screen_references(row["覆盖页面/流程"])
        unknown = referenced - screen_ids
        require(not unknown, f"requirements matrix references unknown screens: {sorted(unknown)}")
        for screen_id in referenced:
            requirements_by_screen[screen_id].add(row["需求ID"])

    with SCREENS_LEDGER.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=SCREEN_LEDGER_FIELDS)
        writer.writeheader()
        for screen in screens:
            screen_id = screen["id"]
            writer.writerow(
                {
                    "screen_id": screen_id,
                    "group": screen["group"],
                    "module": screen["module"],
                    "route": screen["route"],
                    "title": screen["title"],
                    "presentation": screen["presentation"],
                    "params": " | ".join(screen["params"]),
                    "result": "" if screen["result"] is None else screen["result"],
                    "required_states": " | ".join(screen["requiredStates"]),
                    "primary_components": " | ".join(screen["primaryComponents"]),
                    "notes": " | ".join(screen["notes"]),
                    "requirement_ids": " | ".join(sorted(requirements_by_screen[screen_id])),
                    "source": "android_ledger_screen_contract_v1.yaml#screens",
                    "status": "NOT_STARTED",
                    "implementation_evidence": "",
                    "verification_evidence": "",
                    "target_phase": screen_target_phase(screen_id),
                }
            )


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as stream:
        return list(csv.DictReader(stream))


def validate_statuses(rows: list[dict[str, str]], id_field: str) -> None:
    for row in rows:
        row_id = row[id_field]
        require(row["status"] in STATUSES, f"invalid status for {row_id}: {row['status']}")
        if row["status"] == "VERIFIED":
            require(bool(row["verification_evidence"].strip()), f"VERIFIED lacks evidence: {row_id}")
        if row["status"] == "BLOCKED":
            require(bool(row.get("notes", "").strip()), f"BLOCKED lacks reason: {row_id}")


def validate_ledgers(screens: list[dict[str, Any]], requirements: list[dict[str, str]]) -> tuple[int, int]:
    requirement_rows = read_csv(REQUIREMENTS_LEDGER)
    require(list(requirement_rows[0]) == REQ_LEDGER_FIELDS, "unexpected requirement ledger columns")
    require(
        [row["requirement_id"] for row in requirement_rows] == [row["需求ID"] for row in requirements],
        "requirement ledger does not exactly cover source requirements",
    )
    phases = target_requirement_phases()
    source_to_ledger = {
        "需求ID": "requirement_id",
        "来源章节": "source_section",
        "需求摘要": "summary",
        "覆盖页面/流程": "screens_flows",
        "核心组件": "core_components",
        "验收条件": "acceptance_criteria",
    }
    for source, ledger in zip(requirements, requirement_rows, strict=True):
        for source_field, ledger_field in source_to_ledger.items():
            require(
                source[source_field] == ledger[ledger_field],
                f"{ledger['requirement_id']} {ledger_field} differs from frozen matrix",
            )
        primary = phases[ledger["requirement_id"]]
        require(
            ledger["primary_acceptance_phase"] == primary,
            f"{ledger['requirement_id']} primary phase must be {primary}",
        )
        require(
            ledger["follow_up_review_phases"] == follow_up_requirement_phases(primary),
            f"{ledger['requirement_id']} follow-up review phases differ from the plan-wide review policy",
        )
    validate_statuses(requirement_rows, "requirement_id")

    screen_rows = read_csv(SCREENS_LEDGER)
    require(list(screen_rows[0]) == SCREEN_LEDGER_FIELDS, "unexpected screen ledger columns")
    require(
        [row["screen_id"] for row in screen_rows] == [screen["id"] for screen in screens],
        "screen ledger does not exactly cover YAML screens",
    )
    require(
        [row["route"] for row in screen_rows] == [screen["route"] for screen in screens],
        "screen ledger routes differ from YAML",
    )
    validate_statuses(screen_rows, "screen_id")
    mapped = sum(bool(row["requirement_ids"].strip()) for row in screen_rows)
    return len(requirement_rows), mapped


def markdown_table_rows(section: str, id_pattern: str) -> list[list[str]]:
    matcher = re.compile(id_pattern)
    rows: list[list[str]] = []
    for line in section.splitlines():
        if not line.startswith("|"):
            continue
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        if cells and matcher.fullmatch(cells[0]):
            rows.append(cells)
    return rows


def exact_table_ids(section: str, id_pattern: str, expected: list[str], label: str) -> list[list[str]]:
    rows = markdown_table_rows(section, id_pattern)
    actual = [row[0] for row in rows]
    require(actual == expected, f"{label} IDs/order differ: expected={expected}, actual={actual}")
    require(len(actual) == len(set(actual)), f"{label} contains duplicate IDs")
    return rows


def backtick_members(cell: str) -> list[str]:
    return re.findall(r"`([^`]+)`", cell)


EXPECTED_SCHEMA_FAMILY_MEMBERS = {
    "SCHEMA-FAMILY-01": ["book", "book_commit", "book_commit_parent", "command_receipt", "entity_change", "entity_revision", "purge_tombstone"],
    "SCHEMA-FAMILY-02": ["ledger_account", "user_account", "payment_card", "card_vault_secret", "account_balance_checkpoint"],
    "SCHEMA-FAMILY-03": ["category", "merchant", "merchant_alias", "place", "location_record", "location_rtree", "place_rtree"],
    "SCHEMA-FAMILY-04": ["business_transaction", "transaction_revision", "revision_amount", "fx_rate_snapshot", "expense_revision_detail", "income_revision_detail", "transfer_revision_detail", "refund_revision_detail", "credit_payment_revision_detail", "loan_disbursement_revision_detail", "loan_payment_revision_detail", "balance_adjustment_revision_detail", "fx_exchange_revision_detail", "settlement_payment_revision_detail", "opening_balance_revision_detail", "transaction_revision_attachment", "transaction_revision_settlement_share", "transaction_dependency"],
    "SCHEMA-FAMILY-05": ["journal_entry", "posting", "economic_effect", "budget_effect", "project_effect", "goal_effect", "statement_effect", "loan_effect", "settlement_effect"],
    "SCHEMA-FAMILY-06": ["refund_allocation", "refund_status_projection"],
    "SCHEMA-FAMILY-07": ["credit_account_profile", "credit_limit_period", "credit_statement", "credit_statement_revision", "credit_payment_allocation", "installment_plan", "installment_plan_revision", "installment_schedule_revision", "installment_schedule_item", "installment_refund_allocation"],
    "SCHEMA-FAMILY-08": ["loan_contract", "loan_tranche", "loan_terms_revision", "loan_rate_period", "loan_schedule_revision", "loan_schedule_item", "loan_actual_allocation", "loan_simulation", "loan_simulation_item"],
    "SCHEMA-FAMILY-09": ["participant", "settlement_activity", "settlement_activity_participant", "settlement_payment_record"],
    "SCHEMA-FAMILY-10": ["project", "goal", "goal_movement", "budget_template", "budget_template_revision", "budget_template_category_limit", "budget_month", "budget_month_revision", "budget_category_limit", "budget_adjustment", "budget_rollover"],
    "SCHEMA-FAMILY-11": ["transaction_blueprint", "transaction_blueprint_revision", "blueprint_settlement_share_rule", "recurrence_series", "recurrence_series_revision", "recurrence_rule_weekday", "recurrence_exception", "recurrence_occurrence", "recurrence_candidate"],
    "SCHEMA-FAMILY-12": ["encrypted_blob", "attachment", "blob_gc_candidate"],
}

EXPECTED_PROJECTION_FAMILY_MEMBERS = {
    "PROJECTION-FAMILY-01": ["current_transaction_projection"],
    "PROJECTION-FAMILY-02": ["account_balance_current", "account_valuation_current", "account_balance_daily"],
    "PROJECTION-FAMILY-03": ["budget_usage_projection", "budget_future_reservation", "project_usage_projection", "goal_balance_projection", "budget_rollover", "refund_status_projection"],
    "PROJECTION-FAMILY-04": ["credit_statement_projection", "credit_account_projection", "installment_progress_projection", "loan_progress_projection", "loan_future_cashflow_projection", "loan_simulation_item"],
    "PROJECTION-FAMILY-05": ["settlement_position_projection"],
    "PROJECTION-FAMILY-06": ["analytics_daily_total", "analytics_daily_category", "analytics_daily_account", "analytics_daily_merchant", "analytics_daily_project", "analytics_daily_place", "analytics_monthly_*"],
    "PROJECTION-FAMILY-07": ["widget_book_snapshot", "widget_account_snapshot", "widget_credit_snapshot", "widget_goal_snapshot"],
}

EXPECTED_INDEX_FAMILY_MEMBERS = {
    "INDEX-FAMILY-01": ["transaction_fts"],
    "INDEX-FAMILY-02": ["location_rtree", "place_rtree"],
}

EXPECTED_OPERATION_MEMBERS = {
    "BACKGROUND-OPERATION": ["background_operation", "operation_checkpoint"],
    "IMPORT-METADATA": ["import_record", "import_batch_commit", "import_source_reference"],
    "IMPORT-STAGING": ["staging_raw_row", "staging_parsed_row", "staging_mapping", "staging_validation_error", "staging_duplicate_candidate", "staging_prepared_command", "staging_attachment"],
    "BACKUP-RESTORE-METADATA": ["backup_repository", "backup_snapshot", "backup_object", "backup_snapshot_object", "drive_upload_session", "restore_record", "merge_session", "merge_conflict", "merge_resolution"],
}


def validate_domain_ledger() -> None:
    path = IMPLEMENTATION / "DOMAIN_AND_SCHEMA_COVERAGE.md"
    text = read_text_input(path)
    architecture = text.split("## Architecture decisions", 1)[1].split("## Permanent invariants", 1)[0]
    invariants = text.split("## Permanent invariants", 1)[1].split("## Logical schema families", 1)[0]
    schema = text.split("## Logical schema families", 1)[1].split("## Projection and index families", 1)[0]
    projections = text.split("## Projection and index families", 1)[1].split(
        "## Background, staging, import, backup and recovery operations", 1
    )[0]
    operations = text.split("## Background, staging, import, backup and recovery operations", 1)[1].split(
        "## Mandatory test and release quality gates", 1
    )[0]

    expected_adrs = [f"ADR-{number:03d}" for number in range(1, 8)] + ["ADR-007A"] + [
        f"ADR-{number:03d}" for number in range(8, 21)
    ]
    exact_table_ids(architecture, r"ADR-\d{3}A?", expected_adrs, "architecture ADR")
    exact_table_ids(
        architecture,
        r"UI-ADR-\d{3}",
        [f"UI-ADR-{number:03d}" for number in range(1, 13)],
        "UI ADR",
    )
    exact_table_ids(
        invariants,
        r"INV-\d{3}",
        [f"INV-{number:03d}" for number in range(1, 36)],
        "permanent invariant",
    )

    schema_rows = exact_table_ids(
        schema,
        r"SCHEMA-FAMILY-\d{2}",
        list(EXPECTED_SCHEMA_FAMILY_MEMBERS),
        "schema family",
    )
    for row in schema_rows:
        actual = backtick_members(row[2])
        require(actual == EXPECTED_SCHEMA_FAMILY_MEMBERS[row[0]], f"{row[0]} member mapping differs")
        require(len(actual) == len(set(actual)), f"{row[0]} contains duplicate members")

    projection_rows = exact_table_ids(
        projections,
        r"PROJECTION-FAMILY-\d{2}",
        list(EXPECTED_PROJECTION_FAMILY_MEMBERS),
        "projection family",
    )
    index_rows = exact_table_ids(
        projections,
        r"INDEX-FAMILY-\d{2}",
        list(EXPECTED_INDEX_FAMILY_MEMBERS),
        "index family",
    )
    for row in projection_rows + index_rows:
        expected = {**EXPECTED_PROJECTION_FAMILY_MEMBERS, **EXPECTED_INDEX_FAMILY_MEMBERS}[row[0]]
        actual = backtick_members(row[2])
        require(actual == expected, f"{row[0]} member mapping differs")
        require(len(actual) == len(set(actual)), f"{row[0]} contains duplicate members")

    operation_rows = exact_table_ids(
        operations,
        r"(?:BACKGROUND-OPERATION|IMPORT-METADATA|IMPORT-STAGING|BACKUP-RESTORE-METADATA)",
        list(EXPECTED_OPERATION_MEMBERS),
        "operation inventory",
    )
    for row in operation_rows:
        actual = backtick_members(row[2])
        require(actual == EXPECTED_OPERATION_MEMBERS[row[0]], f"{row[0]} record mapping differs")
        require(len(actual) == len(set(actual)), f"{row[0]} contains duplicate records")

    domain_source = read_text_input(FROZEN / "领域模型与数据库逻辑模型设计.md")
    schema_section = domain_source.split("# 二十五、数据库逻辑表设计", 1)[1].split(
        "# 二十六、查询投影", 1
    )[0]
    schema_names = set(re.findall(r"^### `([^`]+)`", schema_section, flags=re.MULTILINE))
    detail_section = schema_section.split("### 各交易子类型详情 — R", 1)[1].split(
        "### `transaction_revision_attachment`", 1
    )[0]
    schema_names.update(re.findall(r"^- `([^`]+)`", detail_section, flags=re.MULTILINE))
    require(len(schema_names) == 94, f"unexpected logical schema inventory size: {len(schema_names)}")
    ledger_schema_names = {name for row in schema_rows for name in backtick_members(row[2])}
    require(ledger_schema_names == schema_names, "logical schema ledger must exactly equal the frozen 94-table set")


def validate_required_documents() -> None:
    required = {
        "PROJECT_STATE.md",
        "REQUIREMENT_COVERAGE.csv",
        "SCREEN_COVERAGE.csv",
        "DOMAIN_AND_SCHEMA_COVERAGE.md",
        "TEST_EVIDENCE.md",
        "DECISION_LOG.md",
        "RELEASE_READINESS.md",
    }
    present = {path.name for path in IMPLEMENTATION.iterdir() if path.is_file()}
    require(required <= present, f"missing implementation ledgers: {sorted(required - present)}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--initialize", action="store_true", help="create the two CSV ledgers")
    parser.add_argument("--force", action="store_true", help="replace CSV ledgers during initialization")
    args = parser.parse_args()

    validate_frozen_hashes()
    validate_manifest()
    tokens, screens_doc, requirements = read_sources()
    screens, screen_ids = validate_sources(tokens, screens_doc, requirements)
    if args.initialize:
        initialize_ledgers(screens, requirements, screen_ids, args.force)
        print("initialized requirement and screen coverage ledgers")
    validate_required_documents()
    requirement_count, mapped_screen_count = validate_ledgers(screens, requirements)
    validate_domain_ledger()

    print("P00 specification baseline: PASS")
    print("frozen_spec_files=10")
    print("manifest_text_entries_verified=5")
    print("forbidden_visual_manifest_entries=4 visual_access_guard=fail_closed")
    print("json_scalar_paths=434 category_palettes=16")
    print(f"requirements={requirement_count} expected=90")
    print(f"screens={len(screens)} unique_ids=215 unique_routes=215")
    print(f"screens_with_explicit_matrix_mapping={mapped_screen_count}")
    print("permanent_invariants=35 architecture_adrs=20 adr_007a=1 ui_adrs=12")
    print("logical_schema_tables=94 schema_families=12 projection_families=7")
    print("background_import_backup_records=21")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (AuditFailure, KeyError, ValueError, TypeError, yaml.YAMLError, json.JSONDecodeError) as error:
        print(f"P00 specification baseline: FAIL: {error}", file=sys.stderr)
        sys.exit(1)
