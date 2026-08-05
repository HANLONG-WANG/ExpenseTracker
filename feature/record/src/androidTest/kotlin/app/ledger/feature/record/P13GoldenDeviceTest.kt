@file:Suppress("MaxLineLength")

package app.ledger.feature.record

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.ledger.core.designsystem.LedgerSaveFab
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/** P13 goldens are generated only from the frozen tokens and textual Compose contracts. */
@RunWith(AndroidJUnit4::class)
class P13GoldenDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** REC-011 moved to [P22SettlementAllocationGoldenDeviceTest] when its frozen P22 controls became complete. */
    @Test
    fun categoryHomeEditorValidationAndSettlementGoldensMatchEveryPixel() {
        val record = InstrumentationRegistry.getArguments().getString(RECORD_ARGUMENT) == "true"
        val outputDirectory = File(requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)), "p13-goldens")
        if (record) check(outputDirectory.mkdirs() || outputDirectory.isDirectory)
        val active = mutableIntStateOf(0)
        composeRule.setContent {
            val case = cases[active.intValue]
            LedgerTheme(case.theme, dynamicColor = false, reduceMotion = true) {
                Box(Modifier.size(WIDTH.dp, HEIGHT.dp).testTag(GOLDEN_TAG)) {
                    OrdinaryRecordDestination(case.screen, case.state(), OrdinaryRecordDeviceFixtures.actions)
                    if (case.screen == "REC-003") LedgerSaveFab({}, Modifier.align(Alignment.BottomEnd).padding(16.dp))
                }
            }
        }
        cases.forEachIndexed { index, golden ->
            composeRule.runOnIdle { active.intValue = index }
            composeRule.waitForIdle()
            val actual = composeRule.onNodeWithTag(GOLDEN_TAG).captureToImage().asAndroidBitmap()
            if (record) {
                File(outputDirectory, golden.assetName).outputStream().use { assertTrue(actual.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } else {
                val expected = InstrumentationRegistry.getInstrumentation().context.assets.open("goldens/${golden.assetName}").use(BitmapFactory::decodeStream)
                assertEquals(golden.assetName, expected.width, actual.width)
                assertEquals(golden.assetName, expected.height, actual.height)
                assertEquals(golden.assetName, 0, changedPixels(expected, actual))
                expected.recycle()
            }
            actual.recycle()
        }
        if (record) Thread.sleep(RECORD_PULL_WINDOW_MILLIS)
    }

    private fun changedPixels(expected: Bitmap, actual: Bitmap): Int {
        var changed = 0
        for (y in 0 until actual.height) for (x in 0 until actual.width) if (expected.getPixel(x, y) != actual.getPixel(x, y)) changed += 1
        return changed
    }

    private val cases = listOf(
        Golden("p13_category_home_light.png", "REC-001", ThemeMode.LIGHT) { OrdinaryRecordDeviceFixtures.content() },
        Golden("p13_editor_dark.png", "REC-003", ThemeMode.DARK) {
            OrdinaryRecordDeviceFixtures.content(OrdinaryRecordPolicy.changeExpression(OrdinaryRecordDeviceFixtures.editor(Locale.ENGLISH), "1000+250", Locale.ENGLISH))
        },
        Golden("p13_validation_light.png", "REC-003", ThemeMode.LIGHT) { OrdinaryRecordDeviceFixtures.content(OrdinaryRecordPolicy.validate(OrdinaryRecordDeviceFixtures.editor(Locale.JAPANESE))) },
    )

    private data class Golden(val assetName: String, val screen: String, val theme: ThemeMode, val state: () -> OrdinaryRecordLoadState.Content)

    private companion object {
        const val RECORD_ARGUMENT = "recordP13Goldens"
        const val GOLDEN_TAG = "p13_golden_root"
        const val WIDTH = 360
        const val HEIGHT = 720
        const val RECORD_PULL_WINDOW_MILLIS = 30_000L
    }
}
