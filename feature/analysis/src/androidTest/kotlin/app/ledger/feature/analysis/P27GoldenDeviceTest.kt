@file:Suppress("MagicNumber")

package app.ledger.feature.analysis

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import app.ledger.core.designsystem.LedgerCard
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Goldens originate only from Compose, frozen tokens, and the textual/YAML contracts. */
@RunWith(AndroidJUnit4::class)
class P27GoldenDeviceTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun consumptionMapAndDetailGoldensMatchEveryPixel() {
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
                            mapContent = { _, _ ->
                                LedgerCard(Modifier.fillMaxWidth().height(236.dp)) {
                                    Column(Modifier.padding(LedgerTheme.spacing.md)) {
                                        LedgerText("消费地图区域", LedgerTextRole.SECTION)
                                        LedgerText("聚类 · 6 笔 · JPY 19,000", LedgerTextRole.SUPPORTING)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        CASES.forEachIndexed { index, golden ->
            composeRule.runOnIdle { active.intValue = index }
            composeRule.waitForIdle()
            val actual = composeRule.onNodeWithTag(GOLDEN_TAG).captureToImage().asAndroidBitmap().pixelSha256()
            println("P27_GOLDEN_${golden.screen}=$actual")
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
        const val GOLDEN_TAG = "p27_analysis_golden_root"
        val CASES = listOf(
            Golden(
                "ANA-011",
                ThemeMode.LIGHT,
                AnalysisDeviceFixtures.base("ANA-011", AnalysisPresentation.CLUSTERS),
                "4bfbfc87834852c480b51e4fd411ce3f26b4d04217c1a40ae50f459bf181304d",
            ),
            Golden(
                "ANA-012",
                ThemeMode.DARK,
                AnalysisDeviceFixtures.base("ANA-012", AnalysisPresentation.PLACE),
                "bcf9ce902212d1aa707f3e5a5b5f8e901c63e9255deddf0fe4f6740792e3cc61",
            ),
        )
    }
}
