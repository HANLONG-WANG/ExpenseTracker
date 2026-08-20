@file:Suppress("LongMethod", "MagicNumber")

package app.ledger.feature.settings

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class SecurityPrivacyUiContractDeviceTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun setg006ThroughSetg011Clr001AndSys004RenderEveryStateAcrossThreeLanguagesAndAccessibilitySizes() {
        val cases = SecuritySettingsRequiredState.entries.mapIndexed { index, presentation ->
            Case(
                stateFor(presentation),
                listOf("zh-CN", "en-US", "ja-JP")[index % 3],
                listOf(320, 360, 480)[index % 3],
                listOf(1f, 1.3f, 2f)[index % 3],
                if (index % 2 == 0) ThemeMode.LIGHT else ThemeMode.DARK,
            )
        }
        val active = mutableStateOf(cases.first())
        composeRule.setContent {
            val case = active.value
            val base = LocalContext.current
            val localized = base.createConfigurationContext(
                Configuration(LocalConfiguration.current).apply {
                    setLocales(LocaleList(Locale.forLanguageTag(case.locale)))
                },
            )
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
                LocalDensity provides Density(1f, case.fontScale),
            ) {
                LedgerTheme(case.theme, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(case.width.dp, 2_800.dp)) { SecurityPrivacySettingsDestination(case.state, ACTIONS) }
                }
            }
        }
        cases.forEach { case ->
            composeRule.runOnIdle { active.value = case }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("security_settings_root").assertExists()
        }
        assertEquals(SecuritySettingsRequiredState.entries.toSet(), cases.map { it.state.presentation }.toSet())
        assertEquals(setOf("zh-CN", "en-US", "ja-JP"), cases.map(Case::locale).toSet())
    }

    @Test
    fun diagnosticQueueRendersOnlyFixedEnumsAndNeverRendersBusinessSentinel() {
        val sentinel = "PAN-4111111111111111-CVC-123-private-note"
        val state = stateFor(SecuritySettingsRequiredState.SETG_010_CONTENT)
        composeRule.setContent {
            LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = true) {
                Box(Modifier.size(360.dp, 900.dp)) { SecurityPrivacySettingsDestination(state, ACTIONS) }
            }
        }
        assertEquals(0, composeRule.onAllNodesWithText(sentinel, substring = true, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun contractDerivedSetgClrAndSysScreenshotsMatchPixelBaselines() {
        val presentations = listOf(
            SecuritySettingsRequiredState.SETG_006_ENABLED,
            SecuritySettingsRequiredState.SETG_007_CONTENT,
            SecuritySettingsRequiredState.SETG_008_CONTENT,
            SecuritySettingsRequiredState.SETG_009_ENABLED,
            SecuritySettingsRequiredState.SETG_010_CONTENT,
            SecuritySettingsRequiredState.SETG_011_CONTENT,
            SecuritySettingsRequiredState.CLR_001_CONFIRMING,
            SecuritySettingsRequiredState.SYS_004_MISSING,
        )
        val states = presentations.map(::stateFor)
        val active = mutableStateOf(states.first())
        composeRule.setContent {
            LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = true) {
                Box(Modifier.size(360.dp, 720.dp).testTag(GOLDEN_TAG)) {
                    SecurityPrivacySettingsDestination(active.value, ACTIONS)
                }
            }
        }
        val actual = states.map { state ->
            composeRule.runOnIdle { active.value = state }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(GOLDEN_TAG).captureToImage().asAndroidBitmap().pixelSha256().also {
                println("P32_SETTINGS_GOLDEN_${state.screenId}=$it")
            }
        }
        assertEquals(EXPECTED_GOLDENS, actual)
    }

    private fun stateFor(presentation: SecuritySettingsRequiredState): SecurityPrivacySettingsState = SecurityPrivacySettingsState(
        screenId = presentation.screenId,
        presentation = presentation,
        appLockEnabled = presentation == SecuritySettingsRequiredState.SETG_006_ENABLED,
        appLockTimeout = AppLockTimeout.FIVE_MINUTES,
        deviceSecurityConfigured = presentation !in setOf(
            SecuritySettingsRequiredState.SETG_006_DEVICE_SECURITY_MISSING,
            SecuritySettingsRequiredState.SYS_004_MISSING,
        ),
        globalScreenshotBlocked = true,
        obscureRecentTasks = true,
        privacyAccepted = presentation != SecuritySettingsRequiredState.SETG_009_PRE_CONSENT,
        telemetryEnabled = presentation == SecuritySettingsRequiredState.SETG_009_ENABLED,
        crashEnabled = presentation == SecuritySettingsRequiredState.SETG_009_ENABLED,
        featureRows = if (presentation == SecuritySettingsRequiredState.SETG_010_CONTENT) FEATURE_ROWS else emptyList(),
        crashRows = if (presentation == SecuritySettingsRequiredState.SETG_011_CONTENT) CRASH_ROWS else emptyList(),
        errorCode = if (presentation == SecuritySettingsRequiredState.CLR_001_FAILED) "LOCAL_CLEAR_FAILED" else null,
    )

    private fun Bitmap.pixelSha256(): String {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        val buffer = ByteBuffer.allocate(Int.SIZE_BYTES * (pixels.size + 2))
        buffer.putInt(width)
        buffer.putInt(height)
        pixels.forEach(buffer::putInt)
        return MessageDigest.getInstance("SHA-256").digest(buffer.array()).joinToString("") { "%02x".format(it) }
    }

    private data class Case(
        val state: SecurityPrivacySettingsState,
        val locale: String,
        val width: Int,
        val fontScale: Float,
        val theme: ThemeMode,
    )

    private companion object {
        const val GOLDEN_TAG = "p32_security_settings_golden_root"
        val FEATURE_ROWS = listOf(FeatureQueueRow(1_700_000_000_000L, "FEATURE_OPENED", "VAULT", "SUCCEEDED", "UNDER_1_SECOND", "NONE"))
        val CRASH_ROWS = listOf(CrashQueueRow(1_700_000_100_000L, "APPLICATION_NOT_RESPONDING", "APPLICATION_NOT_RESPONDING", 4))
        val ACTIONS: (SecurityPrivacyScreenAction) -> Unit = {}
        val EXPECTED_GOLDENS = listOf(
            "ebb3f04446dc337f1b77293df7ba03e70f10f59b88a21e501d5a54c34fa14ec3",
            "456b29e952cdb0b499368ecf880fdde0bd17a959a3f1bed2326c4aa9e52fd864",
            "6569f5b4e1f12ed8d7d058b70ed0036064b0c4ec656c13a0a89447031a48921d",
            "1ae6007de71a733f95f5ffef46763b5c59be2ccf32bf640dd00b1b2d00bde3a3",
            "d55a5d27b87a788eb4d84ced45158985a12938f82ea4779f6d2812c74c02c478",
            "610219c4892cea38f4c364ec5a66743fc16d83569a086ea443b0cff2dfd84cee",
            "6aa152db947d1239c115ea7c64cb32efff294c173fd8827739b2a172266a18bc",
            "3aa5c453643d01a6be33fadc5d3d756d5adc203aa46627db1bfc00a46cb5306c",
        )
    }
}
