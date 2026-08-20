from __future__ import annotations

import copy
import unittest

from scripts import validate_p11_app_shell as validator


class P11AppShellMutationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.sources = validator.load_sources()

    def mutate(self, filename: str, before: str, after: str) -> list[str]:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if validator.Path(path).name == filename)
        self.assertIn(before, sources[path])
        sources[path] = sources[path].replace(before, after)
        return validator.validate_sources(sources)

    def test_session_gate_removal_is_rejected(self) -> None:
        self.assertTrue(self.mutate("AppRootScreen.kt", "SessionGateScreen", "DetachedGate"))

    def test_ready_only_navigation_weakening_is_rejected(self) -> None:
        self.assertTrue(
            self.mutate(
                "FiveStackNavigator.kt",
                "state == SessionGateState.READY",
                "state != SessionGateState.UNINITIALIZED",
            )
        )

    def test_recovery_plaintext_clear_removal_is_rejected(self) -> None:
        self.assertTrue(
            self.mutate(
                "AppRootViewModel.kt",
                "clearRecoveryPlaintextIfLeavingBackup",
                "retainRecoveryPlaintext",
            )
        )

    def test_local_material_theme_is_rejected(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if validator.Path(path).name == "AppRootScreen.kt")
        sources[path] += "\nprivate val forbidden = MaterialTheme\n"
        self.assertIn("app root bypasses the governed design system", validator.validate_sources(sources))

    def test_ready_scaffold_navigation_removal_is_rejected(self) -> None:
        self.assertTrue(self.mutate("ReadyRootScaffold.kt", "NavDisplay(", "DetachedNavigation("))

    def test_domain_dimension_entity_does_not_impersonate_a_room_entity(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if validator.Path(path).name == "AppRootViewModel.kt")
        sources[path] += "\nprivate val reportDimension = DimensionValue.Entity\n"
        self.assertNotIn("app root ViewModel obtained a DAO/Entity", validator.validate_sources(sources))

    def test_room_entity_import_is_rejected(self) -> None:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if validator.Path(path).name == "AppRootViewModel.kt")
        sources[path] = "import app.ledger.finance.data.TransactionEntity\n" + sources[path]
        self.assertIn("app root ViewModel obtained a DAO/Entity", validator.validate_sources(sources))

    def test_proto_sensitive_plaintext_is_rejected(self) -> None:
        proto = "message LedgerAppSettings { string recovery_password = 1; }"
        self.assertIn("sensitive or form plaintext entered the Proto DataStore schema", validator.validate_proto_text(proto))


if __name__ == "__main__":
    unittest.main()
