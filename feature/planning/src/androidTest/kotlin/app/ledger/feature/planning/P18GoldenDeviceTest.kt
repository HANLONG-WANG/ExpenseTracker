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

/** Compose goldens derived only from frozen tokens, textual UI contract and screen YAML. */
@RunWith(AndroidJUnit4::class)
class P18GoldenDeviceTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun projectCashflowAndGoalDetailGoldensMatchEveryPixel() {
        val active = mutableIntStateOf(0)
        composeRule.setContent {
            val golden = CASES[active.intValue]
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                LedgerTheme(golden.theme, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(360.dp, 720.dp).testTag(GOLDEN_TAG)) {
                        ProjectGoalDestination(golden.screen, ProjectGoalLoadState.Content(golden.state), golden.arguments, ProjectGoalDeviceFixtures.actions)
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

    private data class Golden(
        val screen: String,
        val theme: ThemeMode,
        val state: ProjectGoalFeatureState,
        val arguments: Map<String, String>,
        val expectedSha256: String,
    )

    private companion object {
        const val GOLDEN_TAG = "p18_project_goal_golden_root"
        val CASES = listOf(
            Golden(
                "PRJ-005",
                ThemeMode.LIGHT,
                ProjectGoalDeviceFixtures.state(ProjectGoalPresentation.CONTENT),
                mapOf("projectId" to ProjectGoalDeviceFixtures.projectId.toString()),
                "644e2a435d09207e171cc54ab2b7d7aac4d865cc544a1442167f6fb3b28363bd",
            ),
            Golden(
                "GOL-003",
                ThemeMode.DARK,
                ProjectGoalDeviceFixtures.state(ProjectGoalPresentation.UNDERFUNDED),
                mapOf("goalId" to ProjectGoalDeviceFixtures.goalId.toString()),
                "1a062dc4c4a9b281494c41d0d58a053c739b8fd99b9eb408bd331cc4afa68db7",
            ),
        )
    }
}
