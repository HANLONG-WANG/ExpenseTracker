package app.ledger.core.designsystem

import android.content.Context
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.LocaleList
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

        val amountText = composeRule.onNodeWithTag(LedgerTestTags.AMOUNT)
            .fetchSemanticsNode().config[SemanticsProperties.Text]
        assertTrue(amountText.isNotEmpty())
        assertTrue(amountText.none { it.text.contains(forbiddenAmount) })
        assertTrue(
            !composeRule.onNodeWithTag(LedgerTestTags.AMOUNT)
                .fetchSemanticsNode().config.contains(SemanticsProperties.ContentDescription),
        )
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

        composeRule.onNodeWithText("accessible place list", useUnmergedTree = true).assertExists()
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
            RenderCase(480, 1.6f, dark = false, reduceMotion = false),
            RenderCase(480, 1f, dark = true, reduceMotion = false),
            RenderCase(600, 2f, dark = true, reduceMotion = true),
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

    @Test
    fun dynamicColorChangesOnlyTheMaterialShellAndPreservesLedgerSemanticColors() {
        val dynamic = mutableStateOf(false)
        var observed: ColorBoundary? = null
        composeRule.setContent {
            LedgerTheme(ThemeMode.LIGHT, dynamicColor = dynamic.value, reduceMotion = false) {
                val colors = LedgerTheme.colors
                SideEffect {
                    observed = ColorBoundary(
                        positive = colors.positive.base.toArgb(),
                        warning = colors.warning.base.toArgb(),
                        danger = colors.danger.base.toArgb(),
                        info = colors.info.base.toArgb(),
                        neutral = colors.neutralTransaction.base.toArgb(),
                        categories = colors.categoryPalette.map { it.foreground.toArgb() to it.container.toArgb() },
                        chart = colors.chart.categorical.map { it.toArgb() },
                    )
                }
                Text("boundary")
            }
        }
        composeRule.waitForIdle()
        val fixed = requireNotNull(observed)
        composeRule.runOnIdle { dynamic.value = true }
        composeRule.waitForIdle()
        assertEquals(fixed, observed)
    }

    @Test
    fun selectionAndDataTablePagingSemanticsAreLocalizedInAllThreeLanguages() {
        val locales = listOf(
            Triple(Locale.SIMPLIFIED_CHINESE, "已选中", "上一页"),
            Triple(Locale.JAPANESE, "選択済み", "前のページ"),
            Triple(Locale.ENGLISH, "Selected", "Previous page"),
        )
        val active = mutableStateOf(locales.first())
        composeRule.setContent {
            val context = localizedTargetContext(active.value.first)
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides context.resources.configuration,
            ) {
                LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = true) {
                    Column {
                        LedgerChoiceRow(
                            title = "Locale fixture",
                            selected = true,
                            onClick = {},
                            modifier = Modifier.testTag(SELECTION_TAG),
                        )
                        AccessibleDataTable(
                            AccessibleTableUiModel("Fixture", listOf("Key"), listOf(listOf("Value"))),
                            pageIndex = 0,
                            pageCount = 2,
                            onPreviousPage = {},
                            onNextPage = {},
                        )
                    }
                }
            }
        }
        locales.forEach { localeCase ->
            composeRule.runOnIdle { active.value = localeCase }
            composeRule.waitForIdle()
            val state = composeRule.onNodeWithTag(SELECTION_TAG).fetchSemanticsNode()
                .config[SemanticsProperties.StateDescription]
            assertEquals(localeCase.second, state)
            composeRule.onNodeWithText(localeCase.third).assertExists()
        }
    }

    @Test
    fun scaffoldTraversalOrderPlacesFieldsThenFixedSaveBeforeBottomNavigation() {
        composeRule.setContent {
            LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = true) {
                LedgerScaffold(
                    topBar = { LedgerTopAppBar("Editor", LedgerTopAppBarVariant.TOP_LEVEL) },
                    bottomBar = { LedgerNavigationBar(LedgerTopLevel.RECORD, {}) },
                    fixedAction = { LedgerSaveFab({}) },
                    formContent = true,
                ) { LedgerTextField("", {}, "Amount") }
            }
        }
        val top = traversalIndex(LedgerTestTags.TOP_APP_BAR)
        val content = traversalIndex(LedgerTestTags.CONTENT)
        val save = traversalIndex(LedgerTestTags.FIXED_ACTION)
        val navigation = traversalIndex(LedgerTestTags.BOTTOM_NAVIGATION)
        assertTrue(top < content)
        assertTrue(content < save)
        assertTrue(save < navigation)
    }

    @Test
    fun transactionMeaningAndAccessibleTextSurviveGrayscaleRendering() {
        val accessible = "Expense, Food, lunch, negative 12 Japanese yen"
        composeRule.setContent {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides Density(1f, 1f)) {
                LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(320.dp, 120.dp).testTag(GRAYSCALE_TAG)) {
                        JournalTransactionRow(
                            JournalTransactionUiModel(
                                stableKey = "fictional_transaction",
                                categoryOrType = "Food",
                                summary = "Lunch",
                                accountAndCard = "Wallet",
                                amount = MoneyUiModel(
                                    formatted = "−¥12",
                                    fullAccessibleText = "negative 12 Japanese yen",
                                    semantic = AmountSemantic.OUTFLOW,
                                    visibility = AmountVisibility.VISIBLE,
                                ),
                                typeLabel = "Expense",
                                icon = LedgerIcon.RECORD,
                                badges = listOf("Receipt"),
                                accessibleText = accessible,
                            ),
                            onClick = {},
                            onLongClick = {},
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithTag(LedgerTestTags.JOURNAL_ROW).assertTextContains("Expense", substring = true)
        composeRule.onNodeWithTag(LedgerTestTags.JOURNAL_ROW).assertTextContains("negative 12 Japanese yen")
        assertEquals(
            listOf(accessible),
            composeRule.onNodeWithTag(LedgerTestTags.JOURNAL_ROW)
                .fetchSemanticsNode().config[SemanticsProperties.ContentDescription],
        )
        val bitmap = composeRule.onNodeWithTag(GRAYSCALE_TAG).captureToImage().asAndroidBitmap()
        val grayLevels = buildSet {
            for (y in 0 until bitmap.height step 4) {
                for (x in 0 until bitmap.width step 4) {
                    val pixel = bitmap.getPixel(x, y)
                    add((Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1_000)
                }
            }
        }
        assertTrue("grayscale rendering lost structural contrast", grayLevels.size >= 8)
    }

    @Test
    fun allRenderedCoreActionsMeetTouchTargetAndSemanticDescriptionRules() {
        composeRule.setContent {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides Density(1f, 1f)) {
                LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = true) {
                    Column(Modifier.size(360.dp, 900.dp)) {
                        LedgerTopAppBar("Accessible component matrix", LedgerTopAppBarVariant.BACK, onNavigation = {})
                        Row {
                            LedgerIconButton(LedgerIcon.ADD, "Add item", {})
                            LedgerIconButton(LedgerIcon.SAVE, "Save disabled", {}, enabled = false)
                        }
                        LedgerButton("Primary action", {})
                        LedgerChoiceRow("Selected choice", true, {})
                        LedgerToggleRow("Enabled setting", false, {})
                        LedgerChip("Selected filter", {}, selected = true)
                        SearchField("query", {})
                        SelectorField("Account", "Fictional wallet", {})
                        LedgerTextField("1,250", {}, "Amount")
                        LedgerTabRow(0, listOf("One", "Two"), {})
                        ReferenceDataRow(
                            ReferenceDataRowUiModel("fictional_scan_row", "Fictional row", "Supporting text"),
                            {},
                        )
                    }
                }
            }
        }

        val actions = composeRule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("no actionable semantics nodes were rendered", actions.isNotEmpty())
        actions.forEach { node ->
            assertTrue("action width was ${node.boundsInRoot.width}", node.boundsInRoot.width >= 48f)
            assertTrue("action height was ${node.boundsInRoot.height}", node.boundsInRoot.height >= 48f)
            if (node.config.contains(SemanticsProperties.ContentDescription)) {
                assertTrue(node.config[SemanticsProperties.ContentDescription].all(String::isNotBlank))
            }
        }
        val headings = composeRule.onAllNodes(isHeading()).fetchSemanticsNodes()
        assertTrue(headings.isNotEmpty())
        headings.forEach { node ->
            val visibleHeading = if (node.config.contains(SemanticsProperties.Text)) {
                node.config[SemanticsProperties.Text]
            } else {
                emptyList()
            }
            assertTrue("heading semantics were blank", visibleHeading.any { it.text.isNotBlank() })
        }
    }

    @Test
    fun lightAndDarkThemeTextPairsMeetWcagContrast() {
        val dark = mutableStateOf(false)
        var observed: List<ContrastPair> = emptyList()
        composeRule.setContent {
            LedgerTheme(if (dark.value) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false, reduceMotion = true) {
                val colors = LedgerTheme.colors
                SideEffect {
                    observed = buildList {
                        add(ContrastPair("surface", colors.material.onSurface, colors.material.surface))
                        add(ContrastPair("surface variant", colors.material.onSurfaceVariant, colors.material.surfaceVariant))
                        add(ContrastPair("primary", colors.material.onPrimary, colors.material.primary))
                        add(ContrastPair("positive", colors.positive.onBase, colors.positive.base))
                        add(ContrastPair("positive container", colors.positive.onContainer, colors.positive.container))
                        add(ContrastPair("warning", colors.warning.onBase, colors.warning.base))
                        add(ContrastPair("warning container", colors.warning.onContainer, colors.warning.container))
                        add(ContrastPair("danger", colors.danger.onBase, colors.danger.base))
                        add(ContrastPair("danger container", colors.danger.onContainer, colors.danger.container))
                        add(ContrastPair("info", colors.info.onBase, colors.info.base))
                        add(ContrastPair("info container", colors.info.onContainer, colors.info.container))
                        colors.categoryPalette.forEach { add(ContrastPair("category ${it.id}", it.foreground, it.container)) }
                    }
                }
                Text("contrast boundary")
            }
        }
        listOf(false, true).forEach { darkMode ->
            composeRule.runOnIdle { dark.value = darkMode }
            composeRule.waitForIdle()
            observed.forEach { pair ->
                assertTrue(
                    "${pair.name} contrast was ${pair.ratio}",
                    pair.ratio >= WCAG_NORMAL_TEXT_CONTRAST,
                )
            }
        }
    }

    @Test
    fun chartExplorerAnnouncesExactValuesAndWrapsAtBothEdges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val exploreLabel = context.getString(R.string.ledger_explore_chart)
        val nextLabel = context.getString(R.string.ledger_chart_next_point)
        composeRule.setContent {
            LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = true) {
                ChartCard(
                    model = LedgerChartUiModel(
                        title = "Fictional balance",
                        scope = "Two sample months",
                        summary = "The exact values remain available without relying on color.",
                        type = LedgerChartType.LINE,
                        series = listOf(
                            LedgerChartSeries(
                                stableSeriesKey = "actual",
                                label = "Actual",
                                values = listOf(1_234.0, 2_468.0),
                                pointLabels = listOf("January", "February"),
                                formattedValues = listOf("¥1,234", "¥2,468"),
                            ),
                            LedgerChartSeries(
                                stableSeriesKey = "comparison",
                                label = "Comparison",
                                values = listOf(1_000.0, 2_000.0),
                                pointLabels = listOf("January", "February"),
                                formattedValues = listOf("¥1,000", "¥2,000"),
                            ),
                        ),
                    ),
                    chart = { Box(Modifier.fillMaxSize()) },
                    dataTable = AccessibleTableUiModel("Exact values", listOf("Month", "Value"), listOf(listOf("January", "¥1,234"))),
                    onToggleTable = {},
                )
            }
        }

        composeRule.onNodeWithText(exploreLabel).performClick()
        assertTrue(composeRule.onAllNodesWithText("¥1,234", substring = true).fetchSemanticsNodes().isNotEmpty())
        repeat(4) { composeRule.onNodeWithText(nextLabel).performClick() }
        assertTrue(composeRule.onAllNodesWithText("¥1,234", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun chartAndBatchMatrixRemainInsideCompactTwoHundredPercentBounds() {
        composeRule.setContent {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides Density(1f, 2f)) {
                LedgerTheme(ThemeMode.DARK, dynamicColor = false, reduceMotion = true) {
                    Column(Modifier.size(320.dp, 1800.dp).testTag(EXTENDED_MATRIX_TAG)) {
                        ChartCard(
                            LedgerChartUiModel(
                                "Long localized chart title",
                                "Long localized reporting scope",
                                "A complete text summary precedes this chart.",
                                LedgerChartType.COLUMN,
                                listOf(LedgerChartSeries("series", "Series", listOf(1.0), listOf("Long category label"), listOf("1"))),
                            ),
                            chart = { Box(Modifier.fillMaxSize()) },
                            dataTable = AccessibleTableUiModel("Exact values", listOf("Category", "Value"), listOf(listOf("Long category label", "1"))),
                            onToggleTable = {},
                        )
                        BatchToolbar(listOf("Add row" to {}, "Paste values" to {}))
                        BatchCommitBar("Validate all", "Commit all", "Discard", {}, {}, {}, committing = false)
                    }
                }
            }
        }

        val root = composeRule.onNodeWithTag(EXTENDED_MATRIX_TAG).fetchSemanticsNode().boundsInRoot
        composeRule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
            assertTrue("action exceeded compact matrix", node.boundsInRoot.right <= root.right + .5f)
            assertTrue("action was clipped below compact matrix", node.boundsInRoot.bottom <= root.bottom + .5f)
        }
    }

    private fun traversalIndex(tag: String): Float = composeRule.onNodeWithTag(tag)
        .fetchSemanticsNode().config[SemanticsProperties.TraversalIndex]

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

    private data class ColorBoundary(
        val positive: Int,
        val warning: Int,
        val danger: Int,
        val info: Int,
        val neutral: Int,
        val categories: List<Pair<Int, Int>>,
        val chart: List<Int>,
    )

    private data class ContrastPair(
        val name: String,
        val foreground: androidx.compose.ui.graphics.Color,
        val background: androidx.compose.ui.graphics.Color,
    ) {
        val ratio: Float
            get() {
                val lighter = maxOf(foreground.luminance(), background.luminance())
                val darker = minOf(foreground.luminance(), background.luminance())
                return (lighter + .05f) / (darker + .05f)
            }
    }

    private companion object {
        const val GOLDEN_TAG = "token_palette_golden"
        const val GOLDEN_ASSET = "goldens/p04_token_palette.png"
        const val TOUCH_TAG = "icon_touch_target"
        const val MATRIX_ROOT_TAG = "matrix_root"
        const val HIGH_RISK_TAG = "high_risk_confirmation"
        const val TABLE_TAG = "data_table_matrix"
        const val SELECTION_TAG = "localized_selection_state"
        const val GRAYSCALE_TAG = "grayscale_transaction"
        const val EXTENDED_MATRIX_TAG = "extended_component_matrix"
        const val WCAG_NORMAL_TEXT_CONTRAST = 4.5f
    }
}
