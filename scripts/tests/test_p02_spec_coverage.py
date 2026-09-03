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

from validate_p02_quality import QualityValidationError, validate_contract_shapes  # noqa: E402


class SpecCoverageFailureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        ui_root = ROOT / "docs/初始开发文件存档/UI设计稿与实现契约_v1.0"
        cls.tokens = json.loads((ui_root / "android_ledger_ui_tokens_v1.json").read_text(encoding="utf-8"))
        cls.screens = yaml.safe_load(
            (ui_root / "android_ledger_screen_contract_v1.yaml").read_text(encoding="utf-8")
        )["screens"]
        with (ui_root / "UI需求追踪矩阵_v1.csv").open(encoding="utf-8-sig", newline="") as handle:
            cls.matrix = list(csv.DictReader(handle))

    def assert_rejected(self, screens=None, matrix=None, tokens=None) -> None:
        with self.assertRaises(QualityValidationError):
            validate_contract_shapes(tokens or self.tokens, screens or self.screens, matrix or self.matrix)

    def test_missing_requirement_is_rejected(self) -> None:
        self.assert_rejected(matrix=self.matrix[:-1])

    def test_duplicate_requirement_is_rejected(self) -> None:
        matrix = copy.deepcopy(self.matrix)
        matrix[-1]["需求ID"] = matrix[-2]["需求ID"]
        self.assert_rejected(matrix=matrix)

    def test_missing_screen_is_rejected(self) -> None:
        self.assert_rejected(screens=self.screens[:-1])

    def test_duplicate_route_is_rejected(self) -> None:
        screens = copy.deepcopy(self.screens)
        screens[-1]["route"] = screens[-2]["route"]
        self.assert_rejected(screens=screens)

    def test_missing_required_state_is_rejected(self) -> None:
        screens = copy.deepcopy(self.screens)
        screens[0]["requiredStates"] = []
        self.assert_rejected(screens=screens)

    def test_token_drift_is_rejected(self) -> None:
        tokens = copy.deepcopy(self.tokens)
        tokens["dimensionDp"]["touchTargetMin"] = 47
        self.assert_rejected(tokens=tokens)

    def test_equal_count_required_state_replacement_is_rejected(self) -> None:
        screens = copy.deepcopy(self.screens)
        screens[0]["requiredStates"][0] = "same-count-drift"
        self.assert_rejected(screens=screens)

    def test_unselected_token_value_drift_is_rejected(self) -> None:
        tokens = copy.deepcopy(self.tokens)
        tokens["meta"]["description"] = "same-count-drift"
        self.assert_rejected(tokens=tokens)

    def test_requirement_content_drift_is_rejected(self) -> None:
        matrix = copy.deepcopy(self.matrix)
        matrix[0]["需求摘要"] += " drift"
        self.assert_rejected(matrix=matrix)


if __name__ == "__main__":
    unittest.main()
