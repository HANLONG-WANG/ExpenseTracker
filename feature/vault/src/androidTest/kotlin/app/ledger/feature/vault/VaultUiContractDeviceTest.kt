@file:Suppress("LongMethod", "MagicNumber")

package app.ledger.feature.vault

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
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.common.StableId
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class VaultUiContractDeviceTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun vlt001ThroughVlt004RequiredStatesRenderInThreeLanguagesAndAccessibilitySizes() {
        val cases = VaultRequiredState.entries.mapIndexed { index, presentation ->
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
                    Box(Modifier.size(case.width.dp, 2_400.dp)) { VaultDestination(case.state, ACTIONS) }
                }
            }
        }
        cases.forEach { case ->
            composeRule.runOnIdle { active.value = case }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("vault_root").assertExists()
        }
        assertEquals(VaultRequiredState.entries.toSet(), cases.map { it.state.presentation }.toSet())
        assertEquals(setOf("zh-CN", "en-US", "ja-JP"), cases.map(Case::locale).toSet())
    }

    @Test
    fun panAndSecurityCodeNeverEnterTheSemanticsTreeAndSecurityCodeHasNoCopyAction() {
        val pan = "4111111111111111"
        val cvc = "123"
        val state = stateFor(VaultRequiredState.VLT_002_REVEALED).copy(
            primaryNumber = VaultSensitiveValue { it(pan) },
            securityCode = VaultSensitiveValue { it(cvc) },
            secondsRemaining = 30,
        )
        composeRule.setContent {
            val base = LocalContext.current
            val localized = base.createConfigurationContext(
                Configuration(LocalConfiguration.current).apply { setLocales(LocaleList(Locale.SIMPLIFIED_CHINESE)) },
            )
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
            ) {
                LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(360.dp, 1_200.dp)) { VaultDestination(state, ACTIONS) }
                }
            }
        }
        assertEquals(0, composeRule.onAllNodesWithText(pan, useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText(cvc, useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(1, composeRule.onAllNodesWithContentDescription("复制", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun contractDerivedVltScreenshotsMatchPixelBaselines() {
        val states = listOf(
            stateFor(VaultRequiredState.VLT_001_UNLOCKED_SESSION),
            stateFor(VaultRequiredState.VLT_002_MASKED),
            stateFor(VaultRequiredState.VLT_003_AUTH_REQUIRED),
            stateFor(VaultRequiredState.VLT_004_PROMPT),
        )
        val active = mutableStateOf(states.first())
        composeRule.setContent {
            LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = true) {
                Box(Modifier.size(360.dp, 720.dp).testTag(GOLDEN_TAG)) { VaultDestination(active.value, ACTIONS) }
            }
        }
        val actual = states.map { state ->
            composeRule.runOnIdle { active.value = state }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(GOLDEN_TAG).captureToImage().asAndroidBitmap().pixelSha256().also {
                println("P32_VAULT_GOLDEN_${state.screenId}=$it")
            }
        }
        assertEquals(EXPECTED_GOLDENS, actual)
    }

    private fun stateFor(presentation: VaultRequiredState): VaultPresentationState {
        val cards = listOf(VaultCardSummary(CARD, "Travel card", "4242", true))
        return VaultPresentationState(
            screenId = presentation.screenId,
            presentation = presentation,
            cards = if (presentation == VaultRequiredState.VLT_001_EMPTY) emptyList() else cards,
            selectedCard = if (presentation.screenId in setOf("VLT-002", "VLT-003")) cards.first() else null,
            pending = presentation == VaultRequiredState.VLT_003_SAVING,
        )
    }

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
        val state: VaultPresentationState,
        val locale: String,
        val width: Int,
        val fontScale: Float,
        val theme: ThemeMode,
    )

    private companion object {
        const val GOLDEN_TAG = "p32_vault_golden_root"
        val CARD: StableId = StableId.fromUuid(UUID(0L, 0x9322L))
        val ACTIONS = VaultActions(
            onCard = {}, onEdit = {}, onRevealPrimaryNumber = {}, onCopyPrimaryNumber = {},
            onRevealSecurityCode = {}, onHide = {}, onAuthenticateEdit = {}, onSave = { _, _ -> },
            onOpenDeviceSecurity = {},
        )
        val EXPECTED_GOLDENS = listOf(
            "fd9f2f0c43bbd2dbcdb5437abce574b8c89217ae34d8549dab5eaeef0a59d133",
            "2ec8932a9cb0bb480fb4a9eb19c523abe27ed4e1c8fe3da0e88b84576841188c",
            "0400103555d6e316d2b43387dc8035989df88ad0b6602be27f8dce2f6a1fefb7",
            "639f4fe4b9693a382651c1e86f14c38229711abd59a14ade9c22e990a2a5969e",
        )
    }
}
