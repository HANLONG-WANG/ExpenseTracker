package app.ledger.app

import app.ledger.app.settings.DestinationProto
import app.ledger.app.settings.LedgerAppSettings
import app.ledger.app.settings.NavigationSnapshotProto
import app.ledger.app.settings.SessionRestorePolicyProto
import app.ledger.app.settings.TopLevelStackProto
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppSettingsContractTest {
    @Test
    fun shortBackgroundPolicyNeverRestoresAColdStartSnapshot() {
        val settings = LedgerAppSettings.newBuilder()
            .setRestorePolicy(SessionRestorePolicyProto.SESSION_RESTORE_SHORT_BACKGROUND)
            .setNavigationSnapshot(safeSnapshot())
            .build()

        assertFalse(settings.shouldRestoreNavigationAfterColdStart())
    }

    @Test
    fun alwaysLastPagePolicyRestoresOnlyWhenASafeSnapshotExists() {
        val withoutSnapshot = LedgerAppSettings.newBuilder()
            .setRestorePolicy(SessionRestorePolicyProto.SESSION_RESTORE_ALWAYS_LAST_PAGE)
            .build()
        val withSnapshot = withoutSnapshot.toBuilder().setNavigationSnapshot(safeSnapshot()).build()

        assertFalse(withoutSnapshot.shouldRestoreNavigationAfterColdStart())
        assertTrue(withSnapshot.shouldRestoreNavigationAfterColdStart())
    }

    @Test
    fun typedSettingsSchemaContainsNoFormOrFinancialPlaintextField() {
        val forbidden = setOf(
            "amount", "memo", "note", "account_name", "category_name", "card_number",
            "attachment_path", "latitude", "longitude", "recovery_password",
        )
        val names = LedgerAppSettings.getDescriptor().fields.map { it.name }.toSet()
        assertTrue(names.intersect(forbidden).isEmpty())
    }

    private fun safeSnapshot(): NavigationSnapshotProto = NavigationSnapshotProto.newBuilder()
        .setSelectedTopLevel("JOURNAL")
        .addStacks(
            TopLevelStackProto.newBuilder()
                .setTopLevel("JOURNAL")
                .addDestinations(DestinationProto.newBuilder().setScreenId("JRN-001")),
        )
        .build()
}
