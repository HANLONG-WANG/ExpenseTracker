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

/** Pixel baselines are generated only from tokens, the textual UI contract, and screen YAML. */
@RunWith(AndroidJUnit4::class)
class P20GoldenDeviceTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun installmentDetailAndCalculatedSettlementGoldensMatchEveryPixel() {
        val active = mutableIntStateOf(0)
        composeRule.setContent {
            val golden = CASES[active.intValue]
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                LedgerTheme(golden.theme, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(360.dp, 720.dp).testTag(GOLDEN_TAG)) {
                        InstallmentDestination(
                            golden.screen,
                            InstallmentLoadState.Content(golden.state),
                            mapOf("planId" to InstallmentDeviceFixtures.planId.toString()),
                            InstallmentDeviceFixtures.actions,
                        )
                    }
                }
            }
        }
        CASES.forEachIndexed { index, golden ->
            composeRule.runOnIdle { active.intValue = index }
            composeRule.waitForIdle()
            val actual = composeRule.onNodeWithTag(GOLDEN_TAG).captureToImage().asAndroidBitmap().pixelSha256()
            println("P20_GOLDEN_${golden.screen}=$actual")
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
        val state: InstallmentFeatureState,
        val expectedSha256: String,
    )

    private companion object {
        const val GOLDEN_TAG = "p20_installment_golden_root"
        val CASES = listOf(
            Golden(
                "INS-003",
                ThemeMode.LIGHT,
                InstallmentDeviceFixtures.state("INS-003", InstallmentPresentation.ACTIVE),
                "975b08a6983ab0b0d8f7e6b804bae913a4aa7b7c7f2fb7d089970768cda8e61c",
            ),
            Golden(
                "INS-005",
                ThemeMode.DARK,
                InstallmentDeviceFixtures.state("INS-005", InstallmentPresentation.CALCULATED),
                "4828eeb33aa563e08740b9b08d90aa0b4c9dec31e901b8300864d6b6f1c0bb30",
            ),
        )
    }
}
