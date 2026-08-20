from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
NEEDS = ROOT / "docs/testing/ManualTestFindings/Needs.md"
PROGRESS = ROOT / "docs/testing/ManualTestFindings/RepairProgress.md"
MATRIX_ROW = re.compile(
    r"^\| N(?P<id>\d{2}) \| (?P<line>\d+) \| (?P<status>[^|]+) \| (?P<production>[^|]+) \| (?P<tests>[^|]+) \|$"
)


class NeedsRepairEvidenceMatrixTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.needs = NEEDS.read_text(encoding="utf-8")
        cls.progress = PROGRESS.read_text(encoding="utf-8")
        cls.actual_lines = [
            index
            for index, line in enumerate(cls.needs.splitlines(), start=1)
            if line.startswith("  实际行为：")
        ]
        cls.rows = {
            int(match.group("id")): match.groupdict()
            for line in cls.progress.splitlines()
            if (match := MATRIX_ROW.match(line))
        }
        cls.source_filenames = {path.name for path in ROOT.rglob("*.kt")}
        cls.python_suites = {path.stem for path in (ROOT / "scripts/tests").glob("test_*.py")}
        cls.production_corpus = "\n".join(
            path.read_text(encoding="utf-8", errors="ignore")
            for root in ("app", "core", "feature", "finance", "transfer", "widget", "scripts/tests")
            for path in (ROOT / root).rglob("*")
            if path.is_file() and path.suffix in {".kt", ".xml", ".py"}
        )

    def test_every_recorded_actual_behavior_has_exactly_one_matrix_row(self) -> None:
        self.assertEqual(69, len(self.actual_lines))
        self.assertEqual(set(range(1, 70)), set(self.rows))
        self.assertEqual(self.actual_lines, [int(self.rows[index]["line"]) for index in range(1, 70)])

    def test_every_row_is_closed_or_a_preserved_positive_baseline(self) -> None:
        for index in range(1, 70):
            self.assertIn(self.rows[index]["status"].strip(), {"闭合", "基线正确"}, f"N{index:02d}")

    def test_every_row_names_production_and_regression_evidence(self) -> None:
        for index, row in self.rows.items():
            self.assertIn("`", row["production"], f"N{index:02d} lacks named production evidence")
            self.assertIn("`", row["tests"], f"N{index:02d} lacks named regression evidence")

    def test_every_named_regression_suite_exists(self) -> None:
        for index, row in self.rows.items():
            names = re.findall(r"`([^`]+)`", row["tests"])
            for name in names:
                if name.startswith("test_"):
                    self.assertIn(name, self.python_suites, f"N{index:02d} references missing {name}")
                elif name.endswith("Test"):
                    self.assertIn(f"{name}.kt", self.source_filenames, f"N{index:02d} references missing {name}")
                else:
                    self.fail(f"N{index:02d} uses an unresolvable regression evidence name: {name}")

    def test_every_production_evidence_resolves_to_repository_content(self) -> None:
        for index, row in self.rows.items():
            terms = re.findall(r"`([^`]+)`", row["production"])
            resolved = any(
                term in self.production_corpus
                or term.rstrip("*") in self.production_corpus
                or (ROOT / term).exists()
                or f"{term.rsplit('.', 1)[-1]}.kt" in self.source_filenames
                or term.rsplit(".", 1)[-1] in self.production_corpus
                for term in terms
            )
            self.assertTrue(resolved, f"N{index:02d} production evidence does not resolve: {terms}")


if __name__ == "__main__":
    unittest.main()
