from __future__ import annotations

import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from validate_spec_baseline import (  # noqa: E402
    AuditFailure,
    exact_table_ids,
    follow_up_requirement_phases,
    target_requirement_phases,
)


class ExactLedgerValidationTest(unittest.TestCase):
    def test_duplicate_and_extra_markdown_rows_are_rejected(self) -> None:
        duplicated = "\n".join(
            [
                "| ID | Value |",
                "|---|---|",
                "| INV-001 | one |",
                "| INV-001 | duplicate |",
            ]
        )
        with self.assertRaises(AuditFailure):
            exact_table_ids(duplicated, r"INV-\d{3}", ["INV-001"], "fixture")

        extra = duplicated.replace("| INV-001 | duplicate |", "| INV-002 | extra |")
        with self.assertRaises(AuditFailure):
            exact_table_ids(extra, r"INV-\d{3}", ["INV-001"], "fixture")

    def test_requirement_primary_and_follow_up_phase_policy_matches_plan(self) -> None:
        phases = target_requirement_phases()
        self.assertEqual("P01", phases["REQ-090"])
        self.assertEqual("P34 | P36", follow_up_requirement_phases(phases["REQ-090"]))
        self.assertEqual("P36", follow_up_requirement_phases("P35"))


if __name__ == "__main__":
    unittest.main()
