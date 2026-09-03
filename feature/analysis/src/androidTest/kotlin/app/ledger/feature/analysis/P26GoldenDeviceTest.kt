@file:Suppress("MagicNumber")

package app.ledger.feature.analysis

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

/** Goldens originate only from Compose implementation, frozen tokens, and textual/YAML contracts. */
@RunWith(AndroidJUnit4::class)
class P26GoldenDeviceTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun builderAndForecastGoldensMatchEveryPixel() {
        val active = mutableIntStateOf(0)
        composeRule.setContent {
            val golden = CASES[active.intValue]
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                LedgerTheme(golden.theme, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(360.dp, 720.dp).testTag(GOLDEN_TAG)) {
                        AnalysisDestination(
                            golden.screen,
                            AnalysisLoadState.Content(golden.state),
                            AnalysisDeviceFixtures.actions,
                        )
                    }
                }
            }
        }
        CASES.forEachIndexed { index, golden ->
            composeRule.runOnIdle { active.intValue = index }
            composeRule.waitForIdle()
            val actual = composeRule.onNodeWithTag(GOLDEN_TAG).captureToImage().asAndroidBitmap().pixelSha256()
            println("P26_GOLDEN_${golden.screen}=$actual")
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

    private data class Golden(
        val screen: String,
        val theme: ThemeMode,
        val state: AnalysisFeatureState,
        val expectedSha256: String,
    )

    private companion object {
        const val GOLDEN_TAG = "p26_analysis_golden_root"
        val CASES = listOf(
            Golden(
                "ANA-008",
                ThemeMode.LIGHT,
                AnalysisDeviceFixtures.base("ANA-008", AnalysisPresentation.EDITING),
                "bf2e0be65461c7b8d2e7692fc53b11706951ea2b420b9499fb9a9b0fcb06a630",
            ),
            Golden(
                "ANA-014",
                ThemeMode.DARK,
                AnalysisDeviceFixtures.base("ANA-014", AnalysisPresentation.CONTENT),
                "cb90f340661723a8c0678ba2ca39bfca7e4a502462239b7101d0f119dc88112d",
            ),
        )
    }
}
