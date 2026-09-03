@file:Suppress("MagicNumber")

package app.ledger.feature.planning

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

/** Pixel goldens rendered only from Compose, frozen tokens and textual/YAML contracts. */
@RunWith(AndroidJUnit4::class)
class P17GoldenDeviceTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun budgetHomeAndConstraintEditorGoldensMatchEveryPixel() {
        val active = mutableIntStateOf(0)
        composeRule.setContent {
            val golden = CASES[active.intValue]
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                LedgerTheme(golden.theme, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(360.dp, 720.dp).testTag(GOLDEN_TAG)) {
                        BudgetDestination(golden.screen, BudgetLoadState.Content(golden.state), emptyMap(), BudgetDeviceFixtures.actions)
                    }
                }
            }
        }
        CASES.forEachIndexed { index, golden ->
            composeRule.runOnIdle { active.intValue = index }
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

    private data class Golden(val screen: String, val theme: ThemeMode, val state: BudgetFeatureState, val expectedSha256: String)

    private companion object {
        const val GOLDEN_TAG = "p17_budget_golden_root"
        val CASES = listOf(
            Golden("BUD-001", ThemeMode.LIGHT, BudgetDeviceFixtures.state(), "24174b600ad54856bdce57467481f5c4d20d26a66815bac6c393e390c66b2e84"),
            Golden("BUD-002", ThemeMode.DARK, BudgetDeviceFixtures.constraintState(), "aa12e3793c021e6872390f3e83fc06a4ac3ad463e7abe8e8ed8bbf1921edd8b6"),
        )
    }
}
