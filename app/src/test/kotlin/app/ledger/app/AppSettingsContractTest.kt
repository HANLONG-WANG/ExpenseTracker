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
        val settings = settingsWith(
            SessionRestorePolicyProto.SESSION_RESTORE_SHORT_BACKGROUND,
            safeSnapshot(),
        )

        assertFalse(settings.shouldRestoreNavigationAfterColdStart())
    }

    @Test
    fun alwaysLastPagePolicyRestoresOnlyWhenASafeSnapshotExists() {
        val withoutSnapshot = settingsWith(SessionRestorePolicyProto.SESSION_RESTORE_ALWAYS_LAST_PAGE)
        val withSnapshot = settingsWith(
            SessionRestorePolicyProto.SESSION_RESTORE_ALWAYS_LAST_PAGE,
            safeSnapshot(),
        )

        assertFalse(withoutSnapshot.shouldRestoreNavigationAfterColdStart())
        assertTrue(withSnapshot.shouldRestoreNavigationAfterColdStart())
    }

    @Test
    fun typedSettingsSchemaContainsNoFormOrFinancialPlaintextField() {
        val forbidden = arrayOf(
            "amount", "memo", "note", "account_name", "category_name", "card_number",
            "attachment_path", "latitude", "longitude", "recovery_password",
        )
        val descriptor = LedgerAppSettings.getDescriptor()
        forbidden.forEach { fieldName -> assertTrue(descriptor.findFieldByName(fieldName) == null) }
    }

    private fun settingsWith(
        policy: SessionRestorePolicyProto,
        snapshot: NavigationSnapshotProto? = null,
    ): LedgerAppSettings {
        val builder: LedgerAppSettings.Builder = LedgerAppSettings.newBuilder()
        builder.restorePolicy = policy
        if (snapshot != null) builder.navigationSnapshot = snapshot
        return builder.build()
    }

    private fun safeSnapshot(): NavigationSnapshotProto {
        val destination: DestinationProto.Builder = DestinationProto.newBuilder()
        destination.screenId = "JRN-001"
        val stack: TopLevelStackProto.Builder = TopLevelStackProto.newBuilder()
        stack.topLevel = "JOURNAL"
        stack.addDestinations(destination)
        val snapshot: NavigationSnapshotProto.Builder = NavigationSnapshotProto.newBuilder()
        snapshot.selectedTopLevel = "JOURNAL"
        snapshot.addStacks(stack)
        return snapshot.build()
    }
}
