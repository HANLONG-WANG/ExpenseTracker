package app.ledger.app

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class AppSettingsContractTest {
    @Test
    fun shortBackgroundPolicyNeverRestoresAColdStartSnapshot() {
        assertFalse(shouldRestoreNavigationAfterColdStart(RESTORE_SHORT_BACKGROUND, hasSafeSnapshot = true))
    }

    @Test
    fun alwaysLastPagePolicyRestoresOnlyWhenASafeSnapshotExists() {
        assertFalse(shouldRestoreNavigationAfterColdStart(RESTORE_ALWAYS_LAST_PAGE, hasSafeSnapshot = false))
        assertTrue(shouldRestoreNavigationAfterColdStart(RESTORE_ALWAYS_LAST_PAGE, hasSafeSnapshot = true))
    }

    @Test
    fun typedSettingsSchemaContainsNoFormOrFinancialPlaintextField() {
        val forbidden = arrayOf(
            "amount", "memo", "note", "account_name", "category_name", "card_number",
            "attachment_path", "latitude", "longitude", "recovery_password",
        )
        val schema = String(Files.readAllBytes(settingsProto()), Charsets.UTF_8)
        forbidden.forEach { fieldName -> assertFalse(Regex("\\b${Regex.escape(fieldName)}\\s*=").containsMatchIn(schema)) }
    }

    private fun settingsProto(): Path {
        val candidates = listOf(
            Path.of("src/main/proto/ledger_app_settings.proto"),
            Path.of("app/src/main/proto/ledger_app_settings.proto"),
        )
        return candidates.singleOrNull(Files::isRegularFile) ?: error("ledger_app_settings.proto not found")
    }

    private companion object {
        const val RESTORE_SHORT_BACKGROUND = 0
        const val RESTORE_ALWAYS_LAST_PAGE = 1
    }
}
