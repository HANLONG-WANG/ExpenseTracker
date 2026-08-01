package app.ledger.core.designsystem

import android.content.Context
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.LocaleList
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.ledger.core.money.AmountSemantic
import app.ledger.core.money.AmountVisibility
import app.ledger.core.money.MoneyUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class DesignSystemDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tokenOnlyGoldenMatchesEveryPixel() {
        composeRule.setContent {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides Density(1f, 1f)) {
                LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = false) {
                    TokenPaletteGolden(Modifier.size(width = 128.dp, height = 104.dp).testTag(GOLDEN_TAG))
                }
            }
        }

        val actual = composeRule.onNodeWithTag(GOLDEN_TAG).captureToImage().asAndroidBitmap()
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val expected = testContext.assets.open(GOLDEN_ASSET).use(BitmapFactory::decodeStream)
        assertEquals(128, actual.width)
        assertEquals(104, actual.height)
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        val comparison = comparePixels(expected, actual)
        assertTrue(
            "token palette pixels differ from the generated JSON golden: $comparison",
            comparison.maxChannelDelta <= 1,
        )
    }

    @Test
    fun hiddenFinancialAndSensitiveValuesNeverEnterTheRenderedOrSemanticTree() {
        val forbiddenAmount = "987654321.09 JPY"
        val forbiddenCard = "4111111111111111"
        composeRule.setContent {
            LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = false) {
                Column {
                    AmountText(
                        MoneyUiModel(
                            formatted = forbiddenAmount,
                            fullAccessibleText = forbiddenAmount,
                            semantic = AmountSemantic.NEUTRAL,
                            visibility = AmountVisibility.HIDDEN,
                        ),
                        size = AmountSize.MEDIUM,
                    )
                    SensitiveValueField(
                        revealedValue = forbiddenCard,
                        revealed = false,
                        onReveal = {},
                        onHide = {},
                    )
                }
            }
        }

        val amountDescriptions = composeRule.onNodeWithTag(LedgerTestTags.AMOUNT)
            .fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
        assertTrue(amountDescriptions.isNotEmpty())
        assertTrue(amountDescriptions.none { it.contains(forbiddenAmount) })
        composeRule.onNodeWithText(forbiddenAmount, substring = true, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText(forbiddenCard, substring = true, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag(LedgerTestTags.SENSITIVE_VALUE).assertExists()
    }

    @Test
    fun iconTouchTargetIsAtLeastFortyEightDp() {
        composeRule.setContent {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides Density(1f, 1f)) {
                LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = false) {
                    LedgerIconButton(
                        icon = LedgerIcon.SAVE,
                        contentDescription = "save action",
                        onClick = {},
                        modifier = Modifier.testTag(TOUCH_TAG),
                    )
                }
            }
        }

        val bounds = composeRule.onNodeWithTag(TOUCH_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("touch width was ${bounds.width}", bounds.width >= 48f)
        assertTrue("touch height was ${bounds.height}", bounds.height >= 48f)
    }

    @Test
    fun unavailableMapAlwaysRendersAccessibleFallbackAndAttribution() {
        composeRule.setContent {
            LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = false) {
                MapPanel(
                    model = LedgerMapUiModel(
                        summary = "Three recorded places",
                        availability = MapAvailability.UNAVAILABLE,
                        attribution = "Map attribution",
                    ),
                    mapContent = { Text("map sdk surface") },
                    fallbackContent = { Text("accessible place list") },
                )
            }
        }

        composeRule.onNodeWithText("accessible place list").assertExists()
        composeRule.onNodeWithText("Map attribution").assertExists()
        composeRule.onNodeWithText("map sdk surface").assertDoesNotExist()
    }

    @Test
    fun constrainedWidthsFontScalesThemesAndReducedMotionRenderWithoutClipping() {
        val active = mutableStateOf(RenderCase(width = 320, fontScale = 1f, dark = false, reduceMotion = false))
        composeRule.setContent {
            val case = active.value
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides Density(1f, case.fontScale)) {
                LedgerTheme(
                    themeMode = if (case.dark) ThemeMode.DARK else ThemeMode.LIGHT,
                    dynamicColor = false,
                    reduceMotion = case.reduceMotion,
                ) {
                    Column(Modifier.size(case.width.dp, 1500.dp).testTag(MATRIX_ROOT_TAG)) {
                        LedgerBanner(
                            message = "A long explicit warning remains readable and does not rely on color alone.",
                            variant = LedgerBannerVariant.WARNING,
                        )
                        HighRiskConfirmation(
                            title = "Delete selected encrypted backup",
                            scope = "Only the selected backup is in scope.",
                            consequence = "The selected backup cannot be restored after deletion.",
                            unaffected = "Local journal entries and other backups remain unchanged.",
                            requiredPhrase = "DELETE BACKUP",
                            enteredPhrase = "",
                            onPhraseChange = {},
                            onConfirm = {},
                            onCancel = {},
                            modifier = Modifier.testTag(HIGH_RISK_TAG),
                        )
                        AccessibleDataTable(
                            AccessibleTableUiModel(
                                caption = "Monthly totals",
                                columnHeaders = listOf("Month", "Income", "Expense"),
                                rows = listOf(listOf("July", "¥120,000", "¥80,000")),
                            ),
                            modifier = Modifier.testTag(TABLE_TAG),
                        )
                    }
                }
            }
        }

        val cases = listOf(
            RenderCase(320, 1f, dark = false, reduceMotion = false),
            RenderCase(320, 2f, dark = true, reduceMotion = true),
            RenderCase(360, 1.3f, dark = false, reduceMotion = true),
            RenderCase(480, 1f, dark = true, reduceMotion = false),
        )
        cases.forEach { case ->
            composeRule.runOnIdle { active.value = case }
            composeRule.waitForIdle()
            val root = composeRule.onNodeWithTag(MATRIX_ROOT_TAG).fetchSemanticsNode().boundsInRoot
            val highRisk = composeRule.onNodeWithTag(HIGH_RISK_TAG).fetchSemanticsNode().boundsInRoot
            val table = composeRule.onNodeWithTag(TABLE_TAG).fetchSemanticsNode().boundsInRoot
            assertEquals(case.width.toFloat(), root.width, 0.5f)
            listOf(highRisk, table).forEach { bounds ->
                assertTrue("component starts before its root", bounds.left >= root.left)
                assertTrue("component exceeds ${case.width}dp", bounds.right <= root.right + 0.5f)
                assertTrue("component was vertically clipped", bounds.bottom <= root.bottom + 0.5f)
            }
        }
    }

    @Test
    fun simplifiedChineseJapaneseAndEnglishResourcesRender() {
        val locales = listOf(
            Locale.SIMPLIFIED_CHINESE to "正在加载",
            Locale.JAPANESE to "読み込み中",
            Locale.ENGLISH to "Loading",
        )
        val active = mutableStateOf(locales.first())
        composeRule.setContent {
            val (locale, _) = active.value
            val context = localizedTargetContext(locale)
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides context.resources.configuration,
            ) {
                LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = false) {
                    LedgerLoadingState(label = context.getString(R.string.ledger_loading))
                }
            }
        }

        locales.forEach { localeCase ->
            composeRule.runOnIdle { active.value = localeCase }
            composeRule.waitForIdle()
            composeRule.onNodeWithText(localeCase.second).assertExists()
        }
    }

    private fun comparePixels(expected: android.graphics.Bitmap, actual: android.graphics.Bitmap): PixelComparison {
        var changed = 0
        var maxChannelDelta = 0
        var firstDifference = "none"
        for (index in 0 until expected.width * expected.height) {
            val x = index % expected.width
            val y = index / expected.width
            val expectedPixel = expected.getPixel(x, y)
            val actualPixel = actual.getPixel(x, y)
            if (expectedPixel != actualPixel) {
                changed += 1
                if (firstDifference == "none") {
                    firstDifference = "($x,$y) expected=${expectedPixel.toUInt().toString(16)} actual=${actualPixel.toUInt().toString(16)}"
                }
                maxChannelDelta = maxOf(
                    maxChannelDelta,
                    kotlin.math.abs(Color.red(expectedPixel) - Color.red(actualPixel)),
                    kotlin.math.abs(Color.green(expectedPixel) - Color.green(actualPixel)),
                    kotlin.math.abs(Color.blue(expectedPixel) - Color.blue(actualPixel)),
                    kotlin.math.abs(Color.alpha(expectedPixel) - Color.alpha(actualPixel)),
                )
            }
        }
        return PixelComparison(changed, maxChannelDelta, firstDifference)
    }

    private fun localizedTargetContext(locale: Locale): Context {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(target.resources.configuration)
        configuration.setLocales(LocaleList(locale))
        return target.createConfigurationContext(configuration)
    }

    private data class RenderCase(
        val width: Int,
        val fontScale: Float,
        val dark: Boolean,
        val reduceMotion: Boolean,
    )

    private data class PixelComparison(
        val changedPixels: Int,
        val maxChannelDelta: Int,
        val firstDifference: String,
    )

    private companion object {
        const val GOLDEN_TAG = "token_palette_golden"
        const val GOLDEN_ASSET = "goldens/p04_token_palette.png"
        const val TOUCH_TAG = "icon_touch_target"
        const val MATRIX_ROOT_TAG = "matrix_root"
        const val HIGH_RISK_TAG = "high_risk_confirmation"
        const val TABLE_TAG = "data_table_matrix"
    }
}
