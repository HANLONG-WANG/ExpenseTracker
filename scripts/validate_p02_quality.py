#!/usr/bin/env python3
"""Validate P02 quality infrastructure and full text-contract traceability.

This validator intentionally opens only the textual UI implementation inputs. It never
opens, hashes, parses or otherwise inspects the excluded visual drafts.
"""

from __future__ import annotations

import csv
import hashlib
import json
import re
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any

import yaml


ROOT = Path(__file__).resolve().parents[1]
UI_ROOT = ROOT / "docs/UI设计稿与实现契约_v1.0"
IMPLEMENTATION_ROOT = ROOT / "docs/implementation"

EXPECTED_REQUIREMENTS = [f"REQ-{number:03d}" for number in range(1, 91)]
EXPECTED_SCREEN_COUNT = 215
EXPECTED_REQUIRED_STATE_COUNT = 646
EXPECTED_TOKEN_SCALARS = 434
EXPECTED_TOKEN_HASH = "f976230cc3219a47b8e237247633fda3aa1559aa21a7bf2b4667a4d3df195f45"
EXPECTED_SCREEN_HASH = "d6cf0096c91ec9fb7cbf626b40ce270e3cc0b5c815cc3a246d726eee50f00e5b"
EXPECTED_REQUIRED_STATE_HASH = "cf86f17ac9ca31b22c1a330e7f7d7bb89dce690149a9caef64077be41384b640"
EXPECTED_MATRIX_HASH = "f75f60fe4a104555a12bb97bb68542ed8425e1db06b19f9f389f19fba8b123eb"
VALID_STATUSES = {"NOT_STARTED", "IN_PROGRESS", "IMPLEMENTED", "VERIFIED", "BLOCKED"}

MATRIX_TO_LEDGER = {
    "需求ID": "requirement_id",
    "来源章节": "source_section",
    "需求摘要": "summary",
    "覆盖页面/流程": "screens_flows",
    "核心组件": "core_components",
    "验收条件": "acceptance_criteria",
}


class QualityValidationError(AssertionError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise QualityValidationError(message)


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle))


def scalar_count(value: Any) -> int:
    if isinstance(value, dict):
        return sum(scalar_count(item) for item in value.values())
    if isinstance(value, list):
        return sum(scalar_count(item) for item in value)
    return 1


def canonical_hash(value: Any) -> str:
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def validate_contract_shapes(
    tokens: dict[str, Any],
    screens: list[dict[str, Any]],
    matrix_rows: list[dict[str, str]],
) -> None:
    require(scalar_count(tokens) == EXPECTED_TOKEN_SCALARS, "token scalar count must remain 434")
    require(canonical_hash(tokens) == EXPECTED_TOKEN_HASH, "complete token key/value baseline drifted")
    require(tokens["dimensionDp"]["touchTargetMin"] == 48, "48dp touch target token changed")
    require(tokens["breakpointDp"]["minimumSupportedWidth"] == 320, "320dp minimum width token changed")
    require(tokens["typography"]["rules"]["testedMaximumFontScale"] == 2.0, "200% font-scale token changed")
    require(tokens["motion"]["rules"]["reduceMotionMaxDurationMs"] == 80, "reduced-motion token changed")

    require(len(matrix_rows) == 90, "traceability matrix must contain exactly 90 rows")
    requirement_ids = [row.get("需求ID", "") for row in matrix_rows]
    require(requirement_ids == EXPECTED_REQUIREMENTS, "requirements must remain ordered REQ-001 through REQ-090")
    require(all(row.get("验收条件", "").strip() for row in matrix_rows), "every requirement needs acceptance criteria")
    require(canonical_hash(matrix_rows) == EXPECTED_MATRIX_HASH, "complete requirement matrix baseline drifted")

    require(len(screens) == EXPECTED_SCREEN_COUNT, "screen contract must contain exactly 215 entries")
    screen_ids = [screen.get("id") for screen in screens]
    routes = [screen.get("route") for screen in screens]
    require(len(set(screen_ids)) == EXPECTED_SCREEN_COUNT, "screen IDs must be unique")
    require(len(set(routes)) == EXPECTED_SCREEN_COUNT, "screen routes must be unique")
    require(all(screen.get("requiredStates") for screen in screens), "every screen needs at least one required state")
    require(canonical_hash(screens) == EXPECTED_SCREEN_HASH, "complete screen contract baseline drifted")
    require(
        canonical_hash([[screen["id"], screen["requiredStates"]] for screen in screens]) == EXPECTED_REQUIRED_STATE_HASH,
        "screen-to-required-state mapping drifted",
    )
    require(
        sum(len(screen["requiredStates"]) for screen in screens) == EXPECTED_REQUIRED_STATE_COUNT,
        "required state coverage must remain 646",
    )


def expand_matrix_screen_ids(value: str) -> set[str]:
    result: set[str] = set()
    for prefix, start, end in re.findall(r"([A-Z]+)-(\d{3})\.\.(\d{3})", value):
        result.update(f"{prefix}-{number:03d}" for number in range(int(start), int(end) + 1))
    without_ranges = re.sub(r"([A-Z]+)-(\d{3})\.\.(\d{3})", "", value)
    result.update(re.findall(r"[A-Z]+-\d{3}", without_ranges))
    return result


def joined(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, list):
        return " | ".join(str(item) for item in value)
    return str(value)


def validate_ledgers(
    screens: list[dict[str, Any]],
    matrix_rows: list[dict[str, str]],
    requirement_ledger: list[dict[str, str]],
    screen_ledger: list[dict[str, str]],
    evidence_text: str,
) -> int:
    require(len(requirement_ledger) == 90, "requirement ledger must contain exactly 90 rows")
    require(
        [row.get("requirement_id", "") for row in requirement_ledger] == EXPECTED_REQUIREMENTS,
        "requirement ledger IDs drifted",
    )
    for matrix, ledger in zip(matrix_rows, requirement_ledger, strict=True):
        for matrix_field, ledger_field in MATRIX_TO_LEDGER.items():
            require(
                matrix[matrix_field] == ledger[ledger_field],
                f"{ledger['requirement_id']} {ledger_field} differs from frozen matrix",
            )
        validate_ledger_state(ledger, evidence_text, ledger["requirement_id"])
        require(ledger["primary_acceptance_phase"].strip(), f"{ledger['requirement_id']} has no primary phase")
        require(ledger["follow_up_review_phases"].strip(), f"{ledger['requirement_id']} has no follow-up review phase")

    requirements_by_screen: dict[str, list[str]] = defaultdict(list)
    known_screen_ids = {screen["id"] for screen in screens}
    for matrix in matrix_rows:
        for screen_id in sorted(expand_matrix_screen_ids(matrix["覆盖页面/流程"])):
            require(screen_id in known_screen_ids, f"{matrix['需求ID']} maps unknown screen {screen_id}")
            requirements_by_screen[screen_id].append(matrix["需求ID"])

    require(len(screen_ledger) == EXPECTED_SCREEN_COUNT, "screen ledger must contain exactly 215 rows")
    require(
        [row.get("screen_id", "") for row in screen_ledger] == [screen["id"] for screen in screens],
        "screen ledger order or IDs drifted",
    )
    screen_fields = {
        "id": "screen_id",
        "group": "group",
        "module": "module",
        "route": "route",
        "title": "title",
        "presentation": "presentation",
        "params": "params",
        "result": "result",
        "requiredStates": "required_states",
        "primaryComponents": "primary_components",
        "notes": "notes",
    }
    for screen, ledger in zip(screens, screen_ledger, strict=True):
        for contract_field, ledger_field in screen_fields.items():
            require(
                joined(screen.get(contract_field)) == ledger[ledger_field],
                f"{screen['id']} {ledger_field} differs from screen YAML",
            )
        expected_requirements = " | ".join(requirements_by_screen[screen["id"]])
        require(
            ledger["requirement_ids"] == expected_requirements,
            f"{screen['id']} reverse requirement mapping differs from the frozen matrix",
        )
        require(
            ledger["source"] == "android_ledger_screen_contract_v1.yaml#screens",
            f"{screen['id']} has an invalid source marker",
        )
        validate_ledger_state(ledger, evidence_text, screen["id"])
        require(ledger["target_phase"].strip(), f"{screen['id']} has no target phase")

    return sum(bool(requirements) for requirements in requirements_by_screen.values())


def validate_ledger_state(row: dict[str, str], evidence_text: str, identifier: str) -> None:
    status = row.get("status", "")
    require(status in VALID_STATUSES, f"{identifier} has invalid status {status!r}")
    if status == "VERIFIED":
        evidence = row.get("verification_evidence", "").strip()
        require(evidence, f"{identifier} is VERIFIED without verification evidence")
        for evidence_id in re.findall(r"P\d{2}-E\d{3}", evidence):
            require(evidence_id in evidence_text, f"{identifier} cites unknown evidence {evidence_id}")


def validate_repository(root: Path = ROOT) -> dict[str, int]:
    ui_root = root / "docs/UI设计稿与实现契约_v1.0"
    implementation_root = root / "docs/implementation"
    tokens = json.loads((ui_root / "android_ledger_ui_tokens_v1.json").read_text(encoding="utf-8"))
    contract = yaml.safe_load((ui_root / "android_ledger_screen_contract_v1.yaml").read_text(encoding="utf-8"))
    matrix_rows = read_csv(ui_root / "UI需求追踪矩阵_v1.csv")
    requirement_ledger = read_csv(implementation_root / "REQUIREMENT_COVERAGE.csv")
    screen_ledger = read_csv(implementation_root / "SCREEN_COVERAGE.csv")
    evidence_text = (implementation_root / "TEST_EVIDENCE.md").read_text(encoding="utf-8")

    screens = contract.get("screens", [])
    validate_contract_shapes(tokens, screens, matrix_rows)
    mapped_screens = validate_ledgers(screens, matrix_rows, requirement_ledger, screen_ledger, evidence_text)
    require(mapped_screens == 192, "explicit matrix mapping must continue to cover 192 screens")
    return {
        "token_scalars": scalar_count(tokens),
        "requirements": len(matrix_rows),
        "screens": len(screens),
        "routes": len({screen["route"] for screen in screens}),
        "required_states": sum(len(screen["requiredStates"]) for screen in screens),
        "mapped_screens": mapped_screens,
    }


def main() -> int:
    try:
        result = validate_repository()
    except (KeyError, OSError, QualityValidationError, TypeError, ValueError, yaml.YAMLError) as error:
        print(f"P02 quality/spec validation: FAIL: {error}", file=sys.stderr)
        return 1
    print("P02 quality/spec validation: PASS")
    for name, value in result.items():
        print(f"{name}={value}")
    print("visual_inputs=excluded_by_explicit_text-path allowlist")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
