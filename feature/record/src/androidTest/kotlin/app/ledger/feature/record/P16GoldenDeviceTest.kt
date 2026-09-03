@file:Suppress("MagicNumber")

package app.ledger.feature.record

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
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
import app.ledger.core.designsystem.LedgerSaveFab
import app.ledger.core.designsystem.LedgerScaffold
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Pixel goldens rendered only from Compose, frozen tokens and the textual/YAML contracts. */
@RunWith(AndroidJUnit4::class)
class P16GoldenDeviceTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun linkedAndExcessRefundGoldensMatchEveryPixel() {
        val cases = listOf(
            GoldenCase(ThemeMode.LIGHT, RefundDeviceFixtures.linked(), LINKED_LIGHT_SHA256),
            GoldenCase(ThemeMode.DARK, RefundDeviceFixtures.excess(), EXCESS_DARK_SHA256),
        )
        val active = mutableStateOf(cases.first())
        composeRule.setContent {
            val golden = active.value
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                LedgerTheme(golden.theme, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(360.dp, 720.dp).testTag(GOLDEN_TAG)) {
                        LedgerScaffold(fixedAction = { LedgerSaveFab({}, submitting = false) }) { padding ->
                            RefundDestination(
                                RefundLoadState.Content(golden.state),
                                RefundDeviceFixtures.actions,
                                Modifier.padding(padding),
                            )
                        }
                    }
                }
            }
        }
        cases.forEach { golden ->
            composeRule.runOnIdle { active.value = golden }
            composeRule.waitForIdle()
            val bitmap = composeRule.onNodeWithTag(GOLDEN_TAG).captureToImage().asAndroidBitmap()
            assertEquals(golden.expectedSha256, bitmap.pixelSha256())
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

    private data class GoldenCase(
        val theme: ThemeMode,
        val state: RefundEditorState,
        val expectedSha256: String,
    )

    private companion object {
        const val GOLDEN_TAG = "p16_refund_golden_root"
        const val LINKED_LIGHT_SHA256 = "caa577303a8b3063d9026d319f7e163061e1dc0922720b1203cd7183c27048e2"
        const val EXCESS_DARK_SHA256 = "475c6d264bc25912335dabff447eecaf4f0631acd57aa73f33c8c5231e9cb2c9"
    }
}
