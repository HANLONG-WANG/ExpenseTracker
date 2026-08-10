from __future__ import annotations

import copy
import unittest

from scripts import validate_p32_security_privacy as validator


class P32SecurityPrivacyContractMutationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.sources = validator.source_map()

    def mutate(self, filename: str, old: str, new: str) -> list[str]:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith(filename))
        self.assertIn(old, sources[path])
        sources[path] = sources[path].replace(old, new)
        return validator.validate_sources(sources)

    def test_per_action_crypto_object_identity_is_required(self) -> None:
        self.assertTrue(self.mutate("VaultAuthentication.kt", "if (actual !== expectedCipher)", "if (false)"))

    def test_thirty_second_clipboard_clear_is_required(self) -> None:
        self.assertTrue(self.mutate("VaultClipboardController.kt", "30_000L", "60_000L"))

    def test_security_code_copy_remains_forbidden(self) -> None:
        self.assertTrue(self.mutate("VaultClipboardController.kt", "copyPrimaryNumber", "copySecurityCode"))

    def test_vault_always_uses_flag_secure(self) -> None:
        self.assertTrue(self.mutate("AppLockAndScreenPrivacy.kt", "policy.vaultVisible", "false"))

    def test_telemetry_must_not_accept_generic_maps(self) -> None:
        self.assertTrue(self.mutate("PrivacyDiagnosticModels.kt", "enum class FeatureEventName", "typealias GenericEvent = Map<String, String>\nenum class FeatureEventName"))

    def test_identifier_rotation_is_required(self) -> None:
        self.assertTrue(self.mutate("PrivacyDiagnosticManager.kt", "30L * DAY_MILLIS", "365L * DAY_MILLIS"))

    def test_acra_free_text_fields_remain_disabled(self) -> None:
        self.assertTrue(self.mutate("AcraPrivacyIntegration.kt", "ReportField.STACK_TRACE", "ReportField.USER_COMMENT"))

    def test_local_clear_must_delete_diagnostics(self) -> None:
        self.assertTrue(self.mutate("AppRootViewModel.kt", "TelemetryRuntime.deleteAllLocal()", "Unit"))


if __name__ == "__main__":
    unittest.main()
