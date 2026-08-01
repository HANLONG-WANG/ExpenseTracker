from __future__ import annotations

import copy
import csv
import json
import sys
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from generate_p04_contracts import render_screen_contract, render_token_contract  # noqa: E402
from validate_p04_ui import P04ValidationError, validate_contract_data  # noqa: E402


class P04ContractFailureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        ui_root = ROOT / "docs/UI设计稿与实现契约_v1.0"
        cls.tokens = json.loads((ui_root / "android_ledger_ui_tokens_v1.json").read_text(encoding="utf-8"))
        cls.screen_document = yaml.safe_load(
            (ui_root / "android_ledger_screen_contract_v1.yaml").read_text(encoding="utf-8")
        )
        with (ui_root / "UI需求追踪矩阵_v1.csv").open(encoding="utf-8-sig", newline="") as handle:
            cls.matrix = list(csv.DictReader(handle))

    def test_unselected_token_mutation_changes_generated_kotlin(self) -> None:
        tokens = copy.deepcopy(self.tokens)
        original = render_token_contract(tokens)
        tokens["motion"]["rules"]["skeletonAppearsAfterMs"] += 1
        self.assertNotEqual(original, render_token_contract(tokens))

    def test_route_type_capable_of_carrying_money_is_rejected(self) -> None:
        document = copy.deepcopy(self.screen_document)
        document["screens"][0]["params"] = ["amount:Money"]
        with self.assertRaises(P04ValidationError):
            validate_contract_data(self.tokens, document, self.matrix)

    def test_missing_required_state_is_rejected_even_when_generation_is_possible(self) -> None:
        document = copy.deepcopy(self.screen_document)
        document["screens"][0]["requiredStates"] = []
        render_screen_contract(document)
        with self.assertRaises(P04ValidationError):
            validate_contract_data(self.tokens, document, self.matrix)

    def test_missing_target_requirement_is_rejected(self) -> None:
        matrix = [row for row in self.matrix if row["需求ID"] != "REQ-085"]
        with self.assertRaises(P04ValidationError):
            validate_contract_data(self.tokens, self.screen_document, matrix)


if __name__ == "__main__":
    unittest.main()
