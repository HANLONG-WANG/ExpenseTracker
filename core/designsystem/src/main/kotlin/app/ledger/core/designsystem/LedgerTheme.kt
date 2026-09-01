@file:Suppress("ktlint:standard:function-naming", "FunctionNaming", "MagicNumber")

package app.ledger.core.designsystem

import android.animation.ValueAnimator
import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ledger.core.designsystem.tokens.GeneratedLedgerTokenContract
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale

/** Session-wide privacy override consumed by governed money and chart components. */
public val LocalLedgerAmountsVisible = staticCompositionLocalOf { true }

/** Increments when the already-selected top-level destination is tapped again. */
public val LocalLedgerScrollToTopRequest = staticCompositionLocalOf { 0 }

/** Stable root-list key/offset restored from the optional navigation snapshot. */
public val LocalLedgerRestoredScrollState = staticCompositionLocalOf<Pair<String, Int>?> { null }

/** Reports root-list position without coupling feature modules to the app navigator. */
public val LocalLedgerScrollStateReporter = staticCompositionLocalOf<(String, Int) -> Unit> { { _, _ -> } }

@Immutable
public data class SemanticColor(
    val base: Color,
    val onBase: Color,
    val container: Color,
    val onContainer: Color,
)

@Immutable
public data class CategoryColorPair(
    val id: String,
    val foreground: Color,
    val container: Color,
)

@Immutable
public data class LedgerChartColors(
    val categorical: List<Color>,
    val sequentialTeal: List<Color>,
    val grid: Color,
    val axis: Color,
    val selection: Color,
)

@Immutable
public data class LedgerColors(
    val material: ColorScheme,
    val positive: SemanticColor,
    val warning: SemanticColor,
    val danger: SemanticColor,
    val info: SemanticColor,
    val neutralTransaction: SemanticColor,
    val chart: LedgerChartColors,
    val categoryPalette: List<CategoryColorPair>,
)

@Immutable
public data class LedgerTypography(
    val amountHero: TextStyle,
    val amountLarge: TextStyle,
    val amountMedium: TextStyle,
    val titleLarge: TextStyle,
    val titleMedium: TextStyle,
    val titleSmall: TextStyle,
    val bodyLarge: TextStyle,
    val bodyMedium: TextStyle,
    val bodySmall: TextStyle,
    val labelLarge: TextStyle,
    val labelMedium: TextStyle,
    val labelSmall: TextStyle,
    val material: Typography,
)

@Immutable
public data class LedgerSpacing(
    val none: Dp,
    val hairline: Dp,
    val xxs: Dp,
    val xs: Dp,
    val sm: Dp,
    val md: Dp,
    val lg: Dp,
    val xl: Dp,
    val xxl: Dp,
    val xxxl: Dp,
    val huge: Dp,
    val giant: Dp,
)

@Immutable
public data class LedgerShapes(
    val none: androidx.compose.ui.graphics.Shape,
    val xs: androidx.compose.ui.graphics.Shape,
    val sm: androidx.compose.ui.graphics.Shape,
    val md: androidx.compose.ui.graphics.Shape,
    val lg: androidx.compose.ui.graphics.Shape,
    val xl: androidx.compose.ui.graphics.Shape,
    val xxl: androidx.compose.ui.graphics.Shape,
    val full: androidx.compose.ui.graphics.Shape,
    val material: Shapes,
)

@Immutable
public data class LedgerMotion(
    val instantMs: Int,
    val microMs: Int,
    val shortMs: Int,
    val standardMs: Int,
    val longMs: Int,
    val standardEasing: Easing,
    val decelerateEasing: Easing,
    val accelerateEasing: Easing,
    val reduceMotion: Boolean,
    val skeletonDelayMs: Int,
) {
    public fun duration(normalDurationMs: Int): Int = if (reduceMotion) {
        normalDurationMs.coerceAtMost(microMs)
    } else {
        normalDurationMs
    }
}

@Immutable
@Suppress("LongParameterList")
public data class LedgerDimensions(
    val touchTargetMin: Dp,
    val topAppBarHeight: Dp,
    val bottomNavigationHeight: Dp,
    val bottomNavigationIcon: Dp,
    val fab: Dp,
    val fabSmall: Dp,
    val fabExtendedMinWidth: Dp,
    val iconXs: Dp,
    val iconSm: Dp,
    val iconMd: Dp,
    val iconLg: Dp,
    val iconXl: Dp,
    val listRowCompact: Dp,
    val listRowStandard: Dp,
    val listRowRich: Dp,
    val formFieldMinHeight: Dp,
    val searchFieldHeight: Dp,
    val chipHeight: Dp,
    val buttonHeight: Dp,
    val buttonHeightCompact: Dp,
    val categoryTileMinHeight: Dp,
    val categoryIconContainer: Dp,
    val accountIconContainer: Dp,
    val cardMinHeight: Dp,
    val chartMinHeight: Dp,
    val chartPreferredHeight: Dp,
    val dialogMaxWidth: Dp,
    val contentMaxWidth: Dp,
    val formMaxWidth: Dp,
    val phoneContentHorizontalPadding: Dp,
    val phoneCompactHorizontalPadding: Dp,
    val widePhoneContentHorizontalPadding: Dp,
    val bottomActionInset: Dp,
    val strokeHairline: Dp,
    val strokeStandard: Dp,
    val strokeSelected: Dp,
    val strokeFocus: Dp,
    val categoryCompactColumns: Int,
    val categoryStandardColumns: Int,
    val categoryWideColumns: Int,
    val minimumSupportedWidth: Dp,
    val compactMaxWidth: Dp,
    val standardMinWidth: Dp,
    val standardMaxWidth: Dp,
    val wideMinWidth: Dp,
    val largePhoneMinWidth: Dp,
) {
    public fun horizontalPadding(width: Dp): Dp = when {
        width.value < standardMinWidth.value -> phoneCompactHorizontalPadding
        width.value < wideMinWidth.value -> phoneContentHorizontalPadding
        else -> widePhoneContentHorizontalPadding
    }

    public fun categoryColumns(width: Dp): Int = when {
        width.value < standardMinWidth.value -> categoryCompactColumns
        width.value < wideMinWidth.value -> categoryStandardColumns
        else -> categoryWideColumns
    }
}

public enum class ThemeMode {
    FOLLOW_SYSTEM,
    LIGHT,
    DARK,
}

public enum class LedgerDateFormat {
    LOCALE_DEFAULT,
    YEAR_MONTH_DAY,
    DAY_MONTH_YEAR,
    MONTH_DAY_YEAR,
}

public enum class LedgerWeekStart {
    LOCALE_DEFAULT,
    MONDAY,
    SUNDAY,
}

/** Non-composable formatter bridge for presentation helpers that run inside ordinary collection lambdas. */
public object LedgerDateFormatterRuntime {
    @Volatile
    private var configuredFormat: LedgerDateFormat = LedgerDateFormat.LOCALE_DEFAULT

    public fun configure(format: LedgerDateFormat) {
        configuredFormat = format
    }

    public fun formatter(locale: Locale): DateTimeFormatter = when (configuredFormat) {
        LedgerDateFormat.LOCALE_DEFAULT -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        LedgerDateFormat.YEAR_MONTH_DAY -> DateTimeFormatter.ofPattern("yyyy-MM-dd", locale)
        LedgerDateFormat.DAY_MONTH_YEAR -> DateTimeFormatter.ofPattern("dd/MM/yyyy", locale)
        LedgerDateFormat.MONTH_DAY_YEAR -> DateTimeFormatter.ofPattern("MM/dd/yyyy", locale)
    }

    public fun dateTimeFormatter(locale: Locale, timeStyle: FormatStyle = FormatStyle.SHORT): DateTimeFormatter =
        if (configuredFormat == LedgerDateFormat.LOCALE_DEFAULT) {
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, timeStyle).withLocale(locale)
        } else {
            DateTimeFormatterBuilder()
                .append(formatter(locale))
                .appendLiteral(' ')
                .appendLocalized(null, timeStyle)
                .toFormatter(locale)
        }
}

private val LocalLedgerColors = staticCompositionLocalOf { LedgerTokenMapping.lightColors() }
private val LocalLedgerTypography = staticCompositionLocalOf { LedgerTokenMapping.typography }
private val LocalLedgerSpacing = staticCompositionLocalOf { LedgerTokenMapping.spacing }
private val LocalLedgerShapes = staticCompositionLocalOf { LedgerTokenMapping.shapes }
private val LocalLedgerMotion = compositionLocalOf { LedgerTokenMapping.motion(reduceMotion = false) }
private val LocalLedgerDimensions = staticCompositionLocalOf { LedgerTokenMapping.dimensions }
private val LocalLedgerTimeZone = staticCompositionLocalOf { ZoneId.of("UTC") }
private val LocalLedgerDateFormat = staticCompositionLocalOf { LedgerDateFormat.LOCALE_DEFAULT }
private val LocalLedgerWeekStart = staticCompositionLocalOf { LedgerWeekStart.LOCALE_DEFAULT }

public object LedgerTheme {
    public val colors: LedgerColors
        @Composable @ReadOnlyComposable
        get() = LocalLedgerColors.current
    public val typography: LedgerTypography
        @Composable @ReadOnlyComposable
        get() = LocalLedgerTypography.current
    public val spacing: LedgerSpacing
        @Composable @ReadOnlyComposable
        get() = LocalLedgerSpacing.current
    public val shapes: LedgerShapes
        @Composable @ReadOnlyComposable
        get() = LocalLedgerShapes.current
    public val motion: LedgerMotion
        @Composable @ReadOnlyComposable
        get() = LocalLedgerMotion.current
    public val dimensions: LedgerDimensions
        @Composable @ReadOnlyComposable
        get() = LocalLedgerDimensions.current
    public val timeZone: ZoneId
        @Composable @ReadOnlyComposable
        get() = LocalLedgerTimeZone.current
    public val dateFormat: LedgerDateFormat
        @Composable @ReadOnlyComposable
        get() = LocalLedgerDateFormat.current
    public val weekStart: LedgerWeekStart
        @Composable @ReadOnlyComposable
        get() = LocalLedgerWeekStart.current

    @Composable
    @ReadOnlyComposable
    public fun dateFormatter(locale: Locale): DateTimeFormatter = LedgerDateFormatterRuntime.formatter(locale)
}

@Composable
public fun LedgerTheme(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    reduceMotion: Boolean,
    ledgerTimeZoneId: String = "UTC",
    ledgerDateFormat: LedgerDateFormat = LedgerDateFormat.LOCALE_DEFAULT,
    ledgerWeekStart: LedgerWeekStart = LedgerWeekStart.LOCALE_DEFAULT,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val base = if (dark) LedgerTokenMapping.darkColors() else LedgerTokenMapping.lightColors()
    val context = LocalContext.current
    val shellScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        base.material
    }
    val colors = base.copy(material = shellScheme)
    val effectiveReduceMotion = reduceMotion || !ValueAnimator.areAnimatorsEnabled()
    val ledgerTimeZone = runCatching { ZoneId.of(ledgerTimeZoneId) }.getOrDefault(ZoneId.of("UTC"))
    LedgerDateFormatterRuntime.configure(ledgerDateFormat)
    androidx.compose.runtime.CompositionLocalProvider(
        LocalLedgerColors provides colors,
        LocalLedgerTypography provides LedgerTokenMapping.typography,
        LocalLedgerSpacing provides LedgerTokenMapping.spacing,
        LocalLedgerShapes provides LedgerTokenMapping.shapes,
        LocalLedgerMotion provides LedgerTokenMapping.motion(effectiveReduceMotion),
        LocalLedgerDimensions provides LedgerTokenMapping.dimensions,
        LocalLedgerTimeZone provides ledgerTimeZone,
        LocalLedgerDateFormat provides ledgerDateFormat,
        LocalLedgerWeekStart provides ledgerWeekStart,
    ) {
        MaterialTheme(
            colorScheme = shellScheme,
            typography = LedgerTokenMapping.typography.material,
            shapes = LedgerTokenMapping.shapes.material,
            content = content,
        )
    }
}

public object LedgerTokenMapping {
    private val values: Map<String, String> = GeneratedLedgerTokenContract.scalarValues

    public fun lightColors(): LedgerColors = colors("light")
    public fun darkColors(): LedgerColors = colors("dark")

    public val typography: LedgerTypography by lazy {
        fun style(name: String, tabular: Boolean = false): TextStyle = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight(number("typography.$name.weight").toInt()),
            fontSize = number("typography.$name.fontSizeSp").sp,
            lineHeight = number("typography.$name.lineHeightSp").sp,
            letterSpacing = number("typography.$name.letterSpacingSp").sp,
            fontFeatureSettings = if (tabular) "tnum" else null,
        )
        val amountHero = style("amountHero", true)
        val amountLarge = style("amountLarge", true)
        val amountMedium = style("amountMedium", true)
        val titleLarge = style("titleLarge")
        val titleMedium = style("titleMedium")
        val titleSmall = style("titleSmall")
        val bodyLarge = style("bodyLarge")
        val bodyMedium = style("bodyMedium")
        val bodySmall = style("bodySmall")
        val labelLarge = style("labelLarge")
        val labelMedium = style("labelMedium")
        val labelSmall = style("labelSmall")
        LedgerTypography(
            amountHero,
            amountLarge,
            amountMedium,
            titleLarge,
            titleMedium,
            titleSmall,
            bodyLarge,
            bodyMedium,
            bodySmall,
            labelLarge,
            labelMedium,
            labelSmall,
            Typography(
                displayLarge = amountHero,
                displayMedium = amountLarge,
                headlineMedium = amountMedium,
                titleLarge = titleLarge,
                titleMedium = titleMedium,
                titleSmall = titleSmall,
                bodyLarge = bodyLarge,
                bodyMedium = bodyMedium,
                bodySmall = bodySmall,
                labelLarge = labelLarge,
                labelMedium = labelMedium,
                labelSmall = labelSmall,
            ),
        )
    }

    public val spacing: LedgerSpacing by lazy {
        LedgerSpacing(
            none = number("spacingDp.0").dp,
            hairline = number("spacingDp.1").dp,
            xxs = number("spacingDp.2").dp,
            xs = number("spacingDp.3").dp,
            sm = number("spacingDp.4").dp,
            md = number("spacingDp.5").dp,
            lg = number("spacingDp.6").dp,
            xl = number("spacingDp.7").dp,
            xxl = number("spacingDp.8").dp,
            xxxl = number("spacingDp.9").dp,
            huge = number("spacingDp.10").dp,
            giant = number("spacingDp.11").dp,
        )
    }

    public val shapes: LedgerShapes by lazy {
        fun shape(name: String) = androidx.compose.foundation.shape.RoundedCornerShape(number("shapeDp.$name").dp)
        val none = shape("none")
        val xs = shape("xs")
        val sm = shape("sm")
        val md = shape("md")
        val lg = shape("lg")
        val xl = shape("xl")
        val xxl = shape("xxl")
        val full = shape("full")
        LedgerShapes(none, xs, sm, md, lg, xl, xxl, full, Shapes(extraSmall = xs, small = sm, medium = md, large = lg, extraLarge = xxl))
    }

    public val dimensions: LedgerDimensions by lazy {
        fun dimension(name: String): Dp = number("dimensionDp.$name").dp
        LedgerDimensions(
            touchTargetMin = dimension("touchTargetMin"),
            topAppBarHeight = dimension("topAppBarHeight"),
            bottomNavigationHeight = dimension("bottomNavigationHeight"),
            bottomNavigationIcon = dimension("bottomNavigationIcon"),
            fab = dimension("fab"),
            fabSmall = dimension("fabSmall"),
            fabExtendedMinWidth = dimension("fabExtendedMinWidth"),
            iconXs = dimension("iconXs"),
            iconSm = dimension("iconSm"),
            iconMd = dimension("iconMd"),
            iconLg = dimension("iconLg"),
            iconXl = dimension("iconXl"),
            listRowCompact = dimension("listRowCompact"),
            listRowStandard = dimension("listRowStandard"),
            listRowRich = dimension("listRowRich"),
            formFieldMinHeight = dimension("formFieldMinHeight"),
            searchFieldHeight = dimension("searchFieldHeight"),
            chipHeight = dimension("chipHeight"),
            buttonHeight = dimension("buttonHeight"),
            buttonHeightCompact = dimension("buttonHeightCompact"),
            categoryTileMinHeight = dimension("categoryTileMinHeight"),
            categoryIconContainer = dimension("categoryIconContainer"),
            accountIconContainer = dimension("accountIconContainer"),
            cardMinHeight = dimension("cardMinHeight"),
            chartMinHeight = dimension("chartMinHeight"),
            chartPreferredHeight = dimension("chartPreferredHeight"),
            dialogMaxWidth = dimension("dialogMaxWidth"),
            contentMaxWidth = dimension("contentMaxWidth"),
            formMaxWidth = dimension("formMaxWidth"),
            phoneContentHorizontalPadding = dimension("phoneContentHorizontalPadding"),
            phoneCompactHorizontalPadding = dimension("phoneCompactHorizontalPadding"),
            widePhoneContentHorizontalPadding = number("spacingDp.6").dp,
            bottomActionInset = dimension("bottomActionInset"),
            strokeHairline = number("strokeDp.hairline").dp,
            strokeStandard = number("strokeDp.standard").dp,
            strokeSelected = number("strokeDp.selected").dp,
            strokeFocus = number("strokeDp.focus").dp,
            categoryCompactColumns = number("grid.category.compactColumns").toInt(),
            categoryStandardColumns = number("grid.category.standardColumns").toInt(),
            categoryWideColumns = number("grid.category.wideColumns").toInt(),
            minimumSupportedWidth = number("breakpointDp.minimumSupportedWidth").dp,
            compactMaxWidth = number("breakpointDp.compactMax").dp,
            standardMinWidth = number("breakpointDp.standardMin").dp,
            standardMaxWidth = number("breakpointDp.standardMax").dp,
            wideMinWidth = number("breakpointDp.wideMin").dp,
            largePhoneMinWidth = number("breakpointDp.largePhoneMin").dp,
        )
    }

    public fun motion(reduceMotion: Boolean): LedgerMotion = LedgerMotion(
        instantMs = number("motion.durationMs.instant").toInt(),
        microMs = number("motion.durationMs.micro").toInt(),
        shortMs = number("motion.durationMs.short").toInt(),
        standardMs = number("motion.durationMs.standard").toInt(),
        longMs = number("motion.durationMs.long").toInt(),
        standardEasing = easing("standard"),
        decelerateEasing = easing("decelerate"),
        accelerateEasing = easing("accelerate"),
        reduceMotion = reduceMotion,
        skeletonDelayMs = number("motion.rules.skeletonAppearsAfterMs").toInt(),
    )

    public val goldenPalette: List<Color> by lazy {
        values.asSequence()
            .filter { (path, encoded) -> path.startsWith("color.") && encoded.startsWith("\"#") }
            .map { (_, encoded) -> parseColor(encoded.removeSurrounding("\"")) }
            .toList()
    }

    private fun colors(mode: String): LedgerColors {
        fun material(name: String) = color("color.$mode.$name")
        val scheme = if (mode == "light") {
            lightColorScheme(
                primary = material("primary"), onPrimary = material("onPrimary"),
                primaryContainer = material("primaryContainer"), onPrimaryContainer = material("onPrimaryContainer"),
                secondary = material("secondary"), onSecondary = material("onSecondary"),
                secondaryContainer = material("secondaryContainer"), onSecondaryContainer = material("onSecondaryContainer"),
                tertiary = material("tertiary"), onTertiary = material("onTertiary"),
                tertiaryContainer = material("tertiaryContainer"), onTertiaryContainer = material("onTertiaryContainer"),
                background = material("background"), onBackground = material("onBackground"),
                surface = material("surface"), onSurface = material("onSurface"),
                surfaceVariant = material("surfaceVariant"), onSurfaceVariant = material("onSurfaceVariant"),
                surfaceContainerLowest = material("surfaceContainerLowest"), surfaceContainerLow = material("surfaceContainerLow"),
                surfaceContainer = material("surfaceContainer"), surfaceContainerHigh = material("surfaceContainerHigh"),
                surfaceContainerHighest = material("surfaceContainerHighest"), outline = material("outline"),
                outlineVariant = material("outlineVariant"), inverseSurface = material("inverseSurface"),
                inverseOnSurface = material("inverseOnSurface"), inversePrimary = material("inversePrimary"),
                scrim = material("scrim"),
            )
        } else {
            darkColorScheme(
                primary = material("primary"), onPrimary = material("onPrimary"),
                primaryContainer = material("primaryContainer"), onPrimaryContainer = material("onPrimaryContainer"),
                secondary = material("secondary"), onSecondary = material("onSecondary"),
                secondaryContainer = material("secondaryContainer"), onSecondaryContainer = material("onSecondaryContainer"),
                tertiary = material("tertiary"), onTertiary = material("onTertiary"),
                tertiaryContainer = material("tertiaryContainer"), onTertiaryContainer = material("onTertiaryContainer"),
                background = material("background"), onBackground = material("onBackground"),
                surface = material("surface"), onSurface = material("onSurface"),
                surfaceVariant = material("surfaceVariant"), onSurfaceVariant = material("onSurfaceVariant"),
                surfaceContainerLowest = material("surfaceContainerLowest"), surfaceContainerLow = material("surfaceContainerLow"),
                surfaceContainer = material("surfaceContainer"), surfaceContainerHigh = material("surfaceContainerHigh"),
                surfaceContainerHighest = material("surfaceContainerHighest"), outline = material("outline"),
                outlineVariant = material("outlineVariant"), inverseSurface = material("inverseSurface"),
                inverseOnSurface = material("inverseOnSurface"), inversePrimary = material("inversePrimary"),
                scrim = material("scrim"),
            )
        }
        fun semantic(name: String): SemanticColor {
            val prefix = "color.semantic.$name.$mode"
            val onBasePath = "$prefix.onBase"
            val base = color("$prefix.base")
            return SemanticColor(
                base = base,
                onBase = if (onBasePath in values) color(onBasePath) else LedgerContrast.accessibleContent(base),
                container = color("$prefix.container"),
                onContainer = color("$prefix.onContainer"),
            )
        }
        val categories = (0 until 16).map { index ->
            val prefix = "color.categoryPalette[$index]"
            CategoryColorPair(
                id = string("$prefix.id"),
                foreground = color("$prefix.foreground${mode.replaceFirstChar(Char::uppercase)}"),
                container = color("$prefix.container${mode.replaceFirstChar(Char::uppercase)}"),
            )
        }
        val modeSuffix = mode.replaceFirstChar(Char::uppercase)
        return LedgerColors(
            material = scheme,
            positive = semantic("positive"),
            warning = semantic("warning"),
            danger = semantic("danger"),
            info = semantic("info"),
            neutralTransaction = semantic("neutralTransaction"),
            chart = LedgerChartColors(
                categorical = indexedColors("color.chart.categorical$modeSuffix"),
                sequentialTeal = indexedColors("color.chart.sequentialTeal$modeSuffix"),
                grid = color("color.chart.grid$modeSuffix"),
                axis = color("color.chart.axis$modeSuffix"),
                selection = color("color.chart.selection$modeSuffix"),
            ),
            categoryPalette = categories,
        )
    }

    private fun indexedColors(prefix: String): List<Color> = values.keys.asSequence()
        .filter { it.startsWith("$prefix[") }
        .map(::color)
        .toList()

    private fun easing(name: String): Easing = CubicBezierEasing(
        number("motion.easing.$name[0]"),
        number("motion.easing.$name[1]"),
        number("motion.easing.$name[2]"),
        number("motion.easing.$name[3]"),
    )

    private fun number(path: String): Float = requireNotNull(values[path]).toFloat()
    private fun string(path: String): String = requireNotNull(values[path]).removeSurrounding("\"")
    private fun color(path: String): Color = parseColor(string(path))

    private fun parseColor(value: String): Color {
        val raw = value.removePrefix("#")
        require(raw.length == 6 || raw.length == 8) { "unsupported token color" }
        val red = raw.substring(0, 2).toInt(16)
        val green = raw.substring(2, 4).toInt(16)
        val blue = raw.substring(4, 6).toInt(16)
        val alpha = if (raw.length == 8) raw.substring(6, 8).toInt(16) else 0xFF
        return Color(red, green, blue, alpha)
    }
}

@Immutable
public data class LedgerGlanceTokenSubset(
    val primary: Color,
    val onPrimary: Color,
    val surface: Color,
    val onSurface: Color,
    val positive: Color,
    val warning: Color,
    val danger: Color,
    val info: Color,
    val bodySizeSp: Float,
    val labelSizeSp: Float,
    val contentPaddingDp: Float,
)

public object LedgerGlanceTokens {
    public val light: LedgerGlanceTokenSubset = LedgerTokenMapping.lightColors().toGlanceSubset()
    public val dark: LedgerGlanceTokenSubset = LedgerTokenMapping.darkColors().toGlanceSubset()

    private fun LedgerColors.toGlanceSubset(): LedgerGlanceTokenSubset = LedgerGlanceTokenSubset(
        primary = material.primary,
        onPrimary = material.onPrimary,
        surface = material.surface,
        onSurface = material.onSurface,
        positive = positive.base,
        warning = warning.base,
        danger = danger.base,
        info = info.base,
        bodySizeSp = LedgerTokenMapping.typography.bodyMedium.fontSize.value,
        labelSizeSp = LedgerTokenMapping.typography.labelMedium.fontSize.value,
        contentPaddingDp = LedgerTokenMapping.spacing.sm.value,
    )
}
