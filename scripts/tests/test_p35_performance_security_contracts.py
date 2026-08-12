from __future__ import annotations

import copy
import json
import unittest

from scripts import validate_p35_performance_security as validator
from scripts import audit_p35_osv as osv_audit


class P35PerformanceSecurityMutationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.fixture = validator.read("app/src/benchmark/kotlin/app/ledger/app/P35BenchmarkFixtureProvider.kt")
        cls.budgets = json.loads(validator.BUDGETS.read_text(encoding="utf-8"))
        cls.benchmark = validator.read("benchmark/src/main/kotlin/app/ledger/benchmark/P35Macrobenchmark.kt")
        cls.baseline = validator.read("app/src/main/baseline-prof.txt")
        cls.monitor = validator.read("core/designsystem/src/main/kotlin/app/ledger/core/designsystem/LedgerPerformanceMonitor.kt")
        cls.startup = validator.read("finance/data/src/main/kotlin/app/ledger/finance/data/RoomLedgerStartupInspector.kt")
        cls.projections = validator.read("finance/data/src/main/kotlin/app/ledger/finance/data/RoomProjectionEngine.kt")
        cls.tests = validator.test_sources()
        cls.sources = validator.production_sources()

    def test_reduced_target_scale_is_rejected(self) -> None:
        fixture = self.fixture.replace("CURRENT_TRANSACTIONS = 500_000", "CURRENT_TRANSACTIONS = 50_000")
        self.assertTrue(validator.validate_target_scale(fixture, self.budgets))

    def test_unbounded_batch_is_rejected(self) -> None:
        fixture = self.fixture.replace("BATCH_SIZE = 1_000", "BATCH_SIZE = 500_000")
        self.assertTrue(validator.validate_target_scale(fixture, self.budgets))

    def test_macrobenchmark_without_required_profile_is_rejected(self) -> None:
        benchmark = self.benchmark.replace("BaselineProfileMode.Require", "BaselineProfileMode.Disable")
        self.assertTrue(validator.validate_benchmark_toolchain(benchmark, self.baseline, self.monitor))

    def test_missing_fault_injection_is_rejected(self) -> None:
        tests = copy.deepcopy(self.tests)
        path = next(path for path, source in tests.items() if "validationFailureAtSourceRow99999" in source)
        tests[path] = tests[path].replace("validationFailureAtSourceRow99999", "weakenedImportFailure")
        self.assertTrue(validator.validate_fault_matrix(tests))

    def test_business_payload_jank_monitor_is_rejected(self) -> None:
        monitor = self.monitor + '\nprivate const val amount = "unsafe"\n'
        self.assertTrue(validator.validate_benchmark_toolchain(self.benchmark, self.baseline, monitor))

    def test_production_webview_is_rejected(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(iter(sources))
        sources[path] += "\nimport android.webkit.WebView\n"
        self.assertTrue(validator.validate_security_boundary(sources))

    def test_full_integrity_audit_on_every_startup_is_rejected(self) -> None:
        startup = self.startup.replace(
            "if (book.third == 2)",
            "if (!DatabaseIntegrityAudit.run(connection).isValid || book.third == 2)",
        )
        self.assertTrue(validator.validate_bounded_startup(startup, self.projections))

    def test_projection_cardinality_scan_replacing_startup_sampling_is_rejected(self) -> None:
        startup = self.startup.replace("mismatchedFamiliesAtStartup", "mismatchedFamilies")
        self.assertTrue(validator.validate_bounded_startup(startup, self.projections))

    def test_osv_release_scope_comes_from_locked_release_runtime(self) -> None:
        lock = "g:release-lib:1.2=releaseRuntimeClasspath,debugRuntimeClasspath\n" + \
            "g:test-tool:9.0=debugRuntimeClasspath,androidLintTool\n"
        release = osv_audit.release_runtime_purls(lock)
        self.assertEqual({"pkg:maven/g/release-lib@1.2"}, release)
        runtime_findings, tool_findings = osv_audit.classify_vulnerabilities(
            ["pkg:maven/g/release-lib@1.2", "pkg:maven/g/test-tool@9.0"],
            [{"vulns": [{"id": "RUNTIME"}]}, {"vulns": [{"id": "TOOL"}]}],
            release,
        )
        self.assertEqual("RUNTIME", runtime_findings[0]["vulnerabilities"][0]["id"])
        self.assertEqual("TOOL", tool_findings[0]["vulnerabilities"][0]["id"])


if __name__ == "__main__":
    unittest.main()
