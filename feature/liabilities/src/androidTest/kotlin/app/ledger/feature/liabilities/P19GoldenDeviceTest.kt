@file:Suppress("MagicNumber")

package app.ledger.feature.liabilities

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

/** Compose goldens derived only from frozen tokens, textual UI contract and screen YAML. */
@RunWith(AndroidJUnit4::class)
class P19GoldenDeviceTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun creditAccountAndOfficialDifferenceGoldensMatchEveryPixel() {
        val active = mutableIntStateOf(0)
        composeRule.setContent {
            val golden = CASES[active.intValue]
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                LedgerTheme(golden.theme, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(360.dp, 720.dp).testTag(GOLDEN_TAG)) {
                        CreditDestination(golden.screen, CreditLoadState.Content(golden.state), golden.arguments, CreditDeviceFixtures.actions)
                    }
                }
            }
        }
        CASES.forEachIndexed { index, golden ->
            composeRule.runOnIdle { active.intValue = index }
            composeRule.waitForIdle()
            val actual = composeRule.onNodeWithTag(GOLDEN_TAG).captureToImage().asAndroidBitmap().pixelSha256()
            println("P19_GOLDEN_${golden.screen}=$actual")
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
        val state: CreditFeatureState,
        val arguments: Map<String, String>,
        val expectedSha256: String,
    )

    private companion object {
        const val GOLDEN_TAG = "p19_credit_golden_root"
        val CASES = listOf(
            Golden(
                "CRD-001",
                ThemeMode.LIGHT,
                CreditDeviceFixtures.state("CRD-001", CreditPresentation.NORMAL),
                mapOf("accountId" to CreditDeviceFixtures.accountId.toString()),
                "ae3f6ceb0b3cb0cc135ee9460cf2f16fb3b79473b8f0fe8780fafc7ba18af0c0",
            ),
            Golden(
                "CRD-005",
                ThemeMode.DARK,
                CreditDeviceFixtures.state("CRD-005", CreditPresentation.DIFFERENCE),
                mapOf("statementId" to CreditDeviceFixtures.statementId.toString()),
                "baa856433ba87666fb42c1005afbd2d5256829726d81f9c918350537e175159c",
            ),
        )
    }
}
