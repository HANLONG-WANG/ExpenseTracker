from __future__ import annotations

import copy
import unittest

from scripts import validate_p36_release_delivery as validator


class P36ReleaseDeliveryMutationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.convention = validator.read("build-logic/src/main/kotlin/app/ledger/buildlogic/ConventionPlugins.kt")
        cls.app_build = validator.read("app/build.gradle.kts")
        cls.root_build = validator.read("build.gradle.kts")
        cls.proguard = validator.read("app/proguard-rules.pro")
        cls.requirements = validator.read_csv("docs/初始开发文件存档/implementation/REQUIREMENT_COVERAGE.csv")
        cls.screens = validator.read_csv("docs/初始开发文件存档/implementation/SCREEN_COVERAGE.csv")
        cls.domain = validator.read("docs/初始开发文件存档/implementation/DOMAIN_AND_SCHEMA_COVERAGE.md")
        cls.sources = validator.production_sources()

    def test_stale_application_version_is_rejected(self) -> None:
        convention = self.convention.replace('RELEASE_VERSION_NAME = "1.0.0"', 'RELEASE_VERSION_NAME = "0.2.0-p02"')
        self.assertTrue(validator.validate_release_configuration(convention, self.app_build, self.root_build))

    def test_release_without_minification_is_rejected(self) -> None:
        build = self.app_build.replace("isMinifyEnabled = true", "isMinifyEnabled = false")
        self.assertTrue(validator.validate_release_configuration(self.convention, build, self.root_build))

    def test_hard_coded_signing_secret_is_rejected(self) -> None:
        build = self.app_build + '\nval unsafe = signingConfigs.create("unsafe").apply { keyPassword = "secret" }\n'
        self.assertTrue(validator.validate_release_configuration(self.convention, build, self.root_build))

    def test_release_without_reflective_workmanager_constructor_is_rejected(self) -> None:
        rules = self.proguard.replace("public <init>();", "")
        self.assertTrue(
            validator.validate_release_configuration(self.convention, self.app_build, self.root_build, rules),
        )

    def test_unverified_requirement_is_rejected(self) -> None:
        rows = copy.deepcopy(self.requirements)
        rows[0]["status"] = "IN_PROGRESS"
        self.assertTrue(validator.validate_coverage_ledgers(rows, self.screens, self.domain))

    def test_missing_screen_evidence_is_rejected(self) -> None:
        rows = copy.deepcopy(self.screens)
        rows[-1]["verification_evidence"] = ""
        self.assertTrue(validator.validate_coverage_ledgers(self.requirements, rows, self.domain))

    def test_invariant_regression_is_rejected(self) -> None:
        domain = self.domain.replace("| INV-035 |", "| INV-999 |")
        self.assertTrue(validator.validate_coverage_ledgers(self.requirements, self.screens, domain))

    def test_short_privacy_policy_is_rejected(self) -> None:
        self.assertTrue(validator.validate_local_privacy_policy(["short", "short", "short"]))

    def test_production_todo_is_rejected(self) -> None:
        sources = copy.deepcopy(self.sources)
        first = next(iter(sources))
        sources[first] += "\n// TODO release later\n"
        self.assertTrue(validator.validate_hygiene(sources))

    def test_merge_without_atomic_full_projection_rebuild_is_rejected(self) -> None:
        repository = validator.read(
            "finance/data/src/main/kotlin/app/ledger/finance/data/RoomFinancialCommitRepository.kt",
        )
        merge = validator.read(
            "finance/data/src/main/kotlin/app/ledger/finance/data/SecureRoomMergeRestoreApplicationPort.kt",
        ).replace("forceFullProjectionRebuild = true", "forceFullProjectionRebuild = false")
        self.assertTrue(validator.validate_restore_projection_hardening(repository, merge))

    def test_api28_incompatible_big_integer_conversion_is_rejected(self) -> None:
        analytics = validator.read(
            "analytics/domain/src/main/kotlin/app/ledger/analytics/domain/CustomAnalytics.kt",
        ).replace("values.reduce(BigInteger::add).toCompatibleLongExact()", "values.reduce(BigInteger::add).longValueExact()")
        accounting = validator.read(
            "finance/domain/src/main/kotlin/app/ledger/finance/domain/AccountingRuleEngine.kt",
        )
        self.assertTrue(validator.validate_api28_exact_arithmetic(analytics, accounting))

    def test_incremental_analytics_zero_row_regression_is_rejected(self) -> None:
        source = validator.read(
            "core/database/src/main/kotlin/app/ledger/core/database/AnalyticsProjectionEngine.kt",
        )
        incremental = source.replace(
            "FROM economic_effect WHERE accrual_local_date=? GROUP BY accrual_local_date",
            "FROM economic_effect WHERE accrual_local_date=?",
            1,
        )
        self.assertTrue(validator.validate_incremental_analytics_projection(incremental))

    def test_refund_without_original_and_delta_invalidation_is_rejected(self) -> None:
        source = validator.read(
            "finance/data/src/main/kotlin/app/ledger/finance/data/RoomProjectionEngine.kt",
        ).replace(
            "directlyChangedTransactionUids + refundUids",
            "directlyChangedTransactionUids",
        )
        self.assertTrue(validator.validate_refund_projection_invalidation(source))

    def test_refund_without_commit_owned_analytics_delta_is_rejected(self) -> None:
        source = validator.read(
            "finance/data/src/main/kotlin/app/ledger/finance/data/RoomProjectionEngine.kt",
        ).replace(
            "AnalyticsProjectionEngine.applyCommitDeltas(",
            "AnalyticsProjectionEngine.rebuildDates(",
            1,
        )
        self.assertTrue(validator.validate_refund_projection_invalidation(source))

    def test_ci_without_bundle_artifact_is_rejected(self) -> None:
        workflow = validator.read(".github/workflows/quality.yml").replace("app-release.aab", "missing-release-bundle")
        self.assertTrue(validator.validate_release_automation(validator.read("build.gradle.kts"), workflow))

    def test_manifest_without_bundle_input_is_rejected(self) -> None:
        build = self.root_build.replace('        "app/build/outputs/bundle/release/app-release.aab",\n', "", 1)
        self.assertTrue(validator.validate_release_automation(build, validator.read(".github/workflows/quality.yml")))


if __name__ == "__main__":
    unittest.main()
