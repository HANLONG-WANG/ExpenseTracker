@file:Suppress("MagicNumber", "MaxLineLength")

package app.ledger.feature.settlement

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
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

/** P22 pixel hashes are rendered exclusively from production Compose, frozen tokens and textual/YAML contracts. */
@RunWith(AndroidJUnit4::class)
class P22GoldenDeviceTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun detailAndExternalPaymentGoldensMatchEveryPixel() {
        val active = mutableIntStateOf(0)
        composeRule.setContent {
            val golden = CASES[active.intValue]
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                LedgerTheme(golden.theme, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(360.dp, 720.dp).testTag(GOLDEN_TAG)) {
                        SettlementDestination(golden.screen, SettlementLoadState.Content(golden.state), SettlementDeviceFixtures.actions)
                    }
                }
            }
        }
        CASES.forEachIndexed { index, golden ->
            composeRule.runOnIdle { active.intValue = index }
            composeRule.waitForIdle()
            val actual = composeRule.onNodeWithTag(GOLDEN_TAG).captureToImage().asAndroidBitmap().pixelSha256()
            println("P22_GOLDEN_${golden.screen}=$actual")
            assertEquals(golden.expectedSha256, actual)
        }
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

    private data class Golden(val screen: String, val theme: ThemeMode, val state: SettlementFeatureState, val expectedSha256: String)

    private companion object {
        const val GOLDEN_TAG = "p22_settlement_golden_root"
        val CASES = listOf(
            Golden("SET-004", ThemeMode.LIGHT, SettlementDeviceFixtures.state("SET-004", SettlementPresentation.OPEN), "458cf1de6497735d758b68ea6f917c292a2518537c504d559725286349adbe8d"),
            Golden("SET-006", ThemeMode.DARK, SettlementDeviceFixtures.state("SET-006", SettlementPresentation.EXTERNAL_TO_EXTERNAL), "8c79dd1322572be44c7988e6070ec5d1df36a1d255d83d1661e83d9145239665"),
        )
    }
}
