from __future__ import annotations

import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from validate_p01_baseline import is_production_source  # noqa: E402


class ProductionSourceDiscoveryTest(unittest.TestCase):
    def test_nested_kotlin_source_is_scanned(self) -> None:
        nested = ROOT / "finance/domain/src/main/kotlin/app/ledger/domain/DeeplyNested.kt"
        self.assertTrue(is_production_source(nested))

    def test_build_output_is_not_scanned(self) -> None:
        generated = ROOT / "finance/domain/build/generated/src/main/kotlin/Generated.kt"
        self.assertFalse(is_production_source(generated))


if __name__ == "__main__":
    unittest.main()
