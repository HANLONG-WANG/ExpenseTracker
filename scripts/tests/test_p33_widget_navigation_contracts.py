from __future__ import annotations

import copy
import unittest

from scripts import validate_p33_widget_navigation as validator


class P33WidgetNavigationContractMutationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.sources = validator.source_map()

    def mutate(self, filename: str, old: str, new: str) -> list[str]:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith(filename))
        self.assertIn(old, sources[path])
        sources[path] = sources[path].replace(old, new)
        return validator.validate_sources(sources)

    def test_amounts_must_default_hidden_per_widget(self) -> None:
        self.assertTrue(self.mutate("WidgetModels.kt", "val revealAmounts: Boolean = false", "val revealAmounts: Boolean = true"))

    def test_glance_must_read_the_snapshot_table(self) -> None:
        self.assertTrue(self.mutate("SecureRoomWidgetSnapshotApplicationPort.kt", "widget_book_snapshot", "business_transaction"))

    def test_widget_snapshot_must_use_the_process_owned_database_session(self) -> None:
        self.assertTrue(
            self.mutate(
                "SecureRoomWidgetSnapshotApplicationPort.kt",
                "databaseAccess.withCurrentDatabase",
                "EncryptedDatabaseFactory.openPrimary",
            ),
        )

    def test_headless_widget_read_must_retain_its_narrow_capability(self) -> None:
        self.assertTrue(
            self.mutate(
                "AppHeadlessWidgetSnapshotApplicationPort.kt",
                "HeadlessLeaseCapability.WIDGET_SNAPSHOT_READ",
                "HeadlessLeaseCapability.AUTOMATIC_BACKUP",
            ),
        )

    def test_widget_session_database_must_close_with_the_owner(self) -> None:
        self.assertTrue(self.mutate("BookSessionManager.kt", "openedDatabase?.close()", "Unit"))

    def test_financial_commit_must_schedule_widget_refresh(self) -> None:
        self.assertTrue(
            self.mutate(
                "LedgerApplication.kt",
                "LedgerWidgetRuntime.updateAll(this@LedgerApplication)",
                "Unit",
            ),
        )

    def test_widget_render_must_not_depend_on_app_lock(self) -> None:
        self.assertTrue(self.mutate("LedgerGlanceWidget.kt", "LedgerWidgetRuntime.resolve", "AppLock.resolve"))

    def test_widget_render_must_use_the_in_app_language(self) -> None:
        self.assertTrue(self.mutate("LedgerGlanceWidget.kt", "withLanguageTag(languageTag)", "applicationContext"))

    def test_first_launcher_refresh_must_retain_the_saved_configuration(self) -> None:
        self.assertTrue(
            self.mutate(
                "LedgerWidgetRuntime.kt",
                "savedConfigurations[configuration.appWidgetId] = configuration",
                "savedConfigurations.clear()",
            ),
        )

    def test_quick_entry_destination_remains_allowlisted(self) -> None:
        self.assertTrue(self.mutate("AppRootViewModel.kt", "widget destination is not allowlisted", "open arbitrary route"))

    def test_more_hub_must_not_become_a_drawer(self) -> None:
        self.assertTrue(self.mutate("MoreRootScreen.kt", "global_group_planning", "ModalNavigationDrawer"))

    def test_long_operations_must_link_to_operation_center(self) -> None:
        self.assertTrue(self.mutate("OperationNotificationCoordinator.kt", 'ledger://screen/G-007', 'ledger://screen/REC-001'))

    def test_worker_cancellation_must_have_a_rollback_state(self) -> None:
        self.assertTrue(self.mutate("ImportWorker.kt", "BackgroundOperationState.ROLLING_BACK", "BackgroundOperationState.SUCCEEDED"))

    def test_offline_help_must_reject_unknown_topics(self) -> None:
        self.assertTrue(self.mutate("AppRootScreen.kt", "singleOrNull { it.key == topicKey }", "firstOrNull()"))


if __name__ == "__main__":
    unittest.main()
