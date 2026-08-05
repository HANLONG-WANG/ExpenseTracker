@file:Suppress("MagicNumber", "MaxLineLength")

package app.ledger.feature.record

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
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
import java.util.Locale

/** REC-011 digest rendered only from production Compose, tokens and textual/YAML contracts. */
@RunWith(AndroidJUnit4::class)
class P22SettlementAllocationGoldenDeviceTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun completeAllocationGoldenMatchesEveryPixel() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                LedgerTheme(ThemeMode.DARK, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(360.dp, 720.dp).testTag(GOLDEN_TAG)) {
                        val amount = OrdinaryRecordPolicy.changeExpression(OrdinaryRecordDeviceFixtures.editor(), "1000", Locale.JAPAN)
                        val allocation = OrdinaryRecordPolicy.selectSettlementActivity(OrdinaryRecordPolicy.setSettlementEnabled(amount, true), OrdinaryRecordDeviceFixtures.activity)
                        OrdinaryRecordDestination("REC-011", OrdinaryRecordDeviceFixtures.content(allocation), OrdinaryRecordDeviceFixtures.actions)
                    }
                }
            }
        }
        val actual = composeRule.onNodeWithTag(GOLDEN_TAG).captureToImage().asAndroidBitmap().pixelSha256()
        println("P22_GOLDEN_REC-011=$actual")
        assertEquals(EXPECTED_SHA256, actual)
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

    private companion object {
        const val GOLDEN_TAG = "p22_rec011_golden_root"
        const val EXPECTED_SHA256 = "10ac3fb165b6f3881f6c0e1c3b03f8aa8d7ce3bbe6e50f16b1f58760287767b4"
    }
}
