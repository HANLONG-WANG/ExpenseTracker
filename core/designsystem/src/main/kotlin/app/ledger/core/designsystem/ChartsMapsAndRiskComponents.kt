@file:Suppress(
    "ktlint:standard:function-naming",
    "FunctionNaming",
    "LongParameterList",
    "MagicNumber",
    "TooManyFunctions",
)

package app.ledger.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.columnModel
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import com.patrykandpatrick.vico.compose.pie.PieChart
import com.patrykandpatrick.vico.compose.pie.PieChartHost
import com.patrykandpatrick.vico.compose.pie.data.PieChartModelProducer
import com.patrykandpatrick.vico.compose.pie.data.pieSeries
import com.patrykandpatrick.vico.compose.pie.rememberPieChart
import java.text.NumberFormat
import kotlin.math.roundToInt

/** Adapter implemented inside the design-system chart integration; features pass only typed models. */
public fun interface LedgerVicoRenderer {
    @Composable
    public fun render(model: LedgerChartUiModel, modifier: Modifier)
}

/** Governed Vico line renderer for feature-owned typed models. */
public object LedgerVicoLineRenderer : LedgerVicoRenderer {
    @Composable
    override fun render(model: LedgerChartUiModel, modifier: Modifier) {
        require(model.type == LedgerChartType.LINE)
        val producer = remember { CartesianChartModelProducer() }
        val motion = LedgerTheme.motion
        val alpha = remember { Animatable(1f) }
        val visualSeries = remember(model.series) { model.series.flatMap(LedgerChartSeries::visualLineSegments) }
        val rangeProvider = remember(model.includeZeroInRange) { ledgerRangeProvider(model.includeZeroInRange) }
        val axisLine = rememberLineComponent(Fill(LedgerTheme.colors.chart.axis), thickness = 1.dp)
        val gridLine = rememberLineComponent(Fill(LedgerTheme.colors.chart.grid), thickness = 1.dp)
        val axisLabel = rememberTextComponent(LedgerTheme.typography.labelSmall.copy(color = LedgerTheme.colors.material.onSurfaceVariant))
        val pointLabels = model.series.firstOrNull()?.pointLabels.orEmpty()
        val xValueFormatter = remember(pointLabels) {
            CartesianValueFormatter { _, value, _ -> pointLabels.getOrNull(value.roundToInt()).orEmpty() }
        }
        LaunchedEffect(model, motion.reduceMotion) {
            producer.runTransaction {
                lineModel {
                    visualSeries.forEach { segment -> series(segment.xValues, segment.values, segment.segmentKey) }
                }
            }
            if (motion.reduceMotion) {
                alpha.snapTo(0f)
                alpha.animateTo(1f, tween(motion.microMs, easing = motion.standardEasing))
            } else {
                alpha.snapTo(1f)
            }
        }
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(
                    lineProvider = LineCartesianLayer.LineProvider.series(
                        visualSeries.map { segment ->
                            val styleIndex = chartStyleIndex(segment.source.stableSeriesKey)
                            val pointShape = if (styleIndex % 2 == 0) CircleShape else RectangleShape
                            val point = LineCartesianLayer.Point(
                                rememberShapeComponent(Fill(chartColor(segment.source.stableSeriesKey)), pointShape),
                                size = 6.dp,
                            )
                            LineCartesianLayer.rememberLine(
                                fill = LineCartesianLayer.LineFill.single(Fill(chartColor(segment.source.stableSeriesKey))),
                                stroke = if (styleIndex == 0) {
                                    LineCartesianLayer.LineStroke.Continuous(thickness = 3.dp)
                                } else {
                                    LineCartesianLayer.LineStroke.Dashed(
                                        thickness = 2.dp,
                                        dashLength = (LedgerTheme.spacing.xs + LedgerTheme.spacing.xxs),
                                        gapLength = LedgerTheme.spacing.xxs,
                                    )
                                },
                                pointProvider = LineCartesianLayer.PointProvider.single(point),
                            )
                        },
                    ),
                    rangeProvider = rangeProvider,
                ),
                startAxis = VerticalAxis.rememberStart(line = axisLine, label = axisLabel, guideline = gridLine),
                bottomAxis = HorizontalAxis.rememberBottom(
                    line = axisLine,
                    label = axisLabel,
                    guideline = gridLine,
                    valueFormatter = xValueFormatter,
                ),
            ),
            modelProducer = producer,
            modifier = modifier.alpha(alpha.value),
            animationSpec = if (motion.reduceMotion) snap() else tween(motion.standardMs, easing = motion.standardEasing),
            animateIn = !motion.reduceMotion,
        )
    }
}

private data class VisualLineSeries(
    val source: LedgerChartSeries,
    val segmentKey: String,
    val xValues: List<Int>,
    val values: List<Double>,
)

private fun LedgerChartSeries.visualLineSegments(): List<VisualLineSeries> {
    val present = values.indices.filterNot(missingPointIndices::contains)
    if (present.isEmpty()) return emptyList()
    return present.fold(mutableListOf<MutableList<Int>>()) { groups, index ->
        if (groups.isEmpty() || index != groups.last().last() + 1) groups.add(mutableListOf())
        groups.last().add(index)
        groups
    }.mapIndexed { segmentIndex, indices ->
        VisualLineSeries(
            source = this,
            segmentKey = "$stableSeriesKey:segment:$segmentIndex",
            xValues = indices,
            values = indices.map(values::get),
        )
    }
}

private fun ledgerRangeProvider(includeZero: Boolean): CartesianLayerRangeProvider = if (!includeZero) {
    CartesianLayerRangeProvider.auto()
} else {
    object : CartesianLayerRangeProvider {
        override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore): Double = minOf(0.0, minY)
        override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore): Double = maxOf(0.0, maxY)
    }
}

/** Governed grouped-column renderer with the same axes, grid and reduced-motion policy. */
public object LedgerVicoColumnRenderer : LedgerVicoRenderer {
    @Composable
    override fun render(model: LedgerChartUiModel, modifier: Modifier) {
        require(model.type == LedgerChartType.COLUMN)
        LedgerVicoColumns(model, modifier, stacked = false)
    }
}

/** Governed stacked-column renderer; the accessible table remains the exact-value authority. */
public object LedgerVicoStackedRenderer : LedgerVicoRenderer {
    @Composable
    override fun render(model: LedgerChartUiModel, modifier: Modifier) {
        require(model.type == LedgerChartType.STACKED)
        LedgerVicoColumns(model, modifier, stacked = true)
    }
}

/** Governed Vico pie renderer; callers must pass the six-category compatibility gate first. */
public object LedgerVicoPieRenderer : LedgerVicoRenderer {
    @Composable
    override fun render(model: LedgerChartUiModel, modifier: Modifier) {
        require(model.type == LedgerChartType.PIE)
        val producer = remember { PieChartModelProducer() }
        val motion = LedgerTheme.motion
        val alpha = remember { Animatable(1f) }
        LaunchedEffect(model, motion.reduceMotion) {
            producer.runTransaction {
                pieSeries {
                    series(
                        model.series.flatMap { chartSeries ->
                            chartSeries.values.filterIndexed { index, _ -> index !in chartSeries.missingPointIndices }
                        },
                    )
                }
            }
            if (motion.reduceMotion) {
                alpha.snapTo(0f)
                alpha.animateTo(1f, tween(motion.microMs, easing = motion.standardEasing))
            } else {
                alpha.snapTo(1f)
            }
        }
        PieChartHost(
            chart = rememberPieChart(
                sliceProvider = PieChart.SliceProvider.series(
                    model.series.flatMap { series ->
                        series.values.indices
                            .filterNot(series.missingPointIndices::contains)
                            .map { index -> PieChart.Slice(Fill(chartColor("${series.stableSeriesKey}:$index"))) }
                    },
                ),
            ),
            modelProducer = producer,
            modifier = modifier.alpha(alpha.value),
            animationSpec = if (motion.reduceMotion) snap() else tween(motion.standardMs, easing = motion.standardEasing),
            animateIn = !motion.reduceMotion,
        )
    }
}

@Composable
private fun LedgerVicoColumns(model: LedgerChartUiModel, modifier: Modifier, stacked: Boolean) {
    val producer = remember { CartesianChartModelProducer() }
    val motion = LedgerTheme.motion
    val alpha = remember { Animatable(1f) }
    val rangeProvider = remember { ledgerRangeProvider(includeZero = true) }
    val axisLine = rememberLineComponent(Fill(LedgerTheme.colors.chart.axis), thickness = 1.dp)
    val gridLine = rememberLineComponent(Fill(LedgerTheme.colors.chart.grid), thickness = 1.dp)
    val axisLabel = rememberTextComponent(LedgerTheme.typography.labelSmall.copy(color = LedgerTheme.colors.material.onSurfaceVariant))
    val pointLabels = model.series.firstOrNull()?.pointLabels.orEmpty()
    val xValueFormatter = remember(pointLabels) {
        CartesianValueFormatter { _, value, _ -> pointLabels.getOrNull(value.roundToInt()).orEmpty() }
    }
    LaunchedEffect(model, motion.reduceMotion) {
        producer.runTransaction {
            columnModel {
                model.series.forEach { chartSeries ->
                    val indexes = chartSeries.values.indices.filterNot(chartSeries.missingPointIndices::contains)
                    series(indexes, indexes.map(chartSeries.values::get), chartSeries.stableSeriesKey)
                }
            }
        }
        if (motion.reduceMotion) {
            alpha.snapTo(0f)
            alpha.animateTo(1f, tween(motion.microMs, easing = motion.standardEasing))
        } else {
            alpha.snapTo(1f)
        }
    }
    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberColumnCartesianLayer(
                columnProvider = ColumnCartesianLayer.ColumnProvider.series(
                    model.series.map { series -> rememberLineComponent(Fill(chartColor(series.stableSeriesKey))) },
                ),
                columnCollectionSpacing = 4.dp,
                mergeMode = { if (stacked) ColumnCartesianLayer.MergeMode.Stacked else ColumnCartesianLayer.MergeMode.Grouped() },
                rangeProvider = rangeProvider,
            ),
            startAxis = VerticalAxis.rememberStart(line = axisLine, label = axisLabel, guideline = gridLine),
            bottomAxis = HorizontalAxis.rememberBottom(
                line = axisLine,
                label = axisLabel,
                guideline = gridLine,
                valueFormatter = xValueFormatter,
            ),
        ),
        modelProducer = producer,
        modifier = modifier.alpha(alpha.value),
        animationSpec = if (motion.reduceMotion) snap() else tween(motion.standardMs, easing = motion.standardEasing),
        animateIn = !motion.reduceMotion,
    )
}

@Composable
public fun LedgerLineChart(
    model: LedgerChartUiModel,
    renderer: LedgerVicoRenderer,
    modifier: Modifier = Modifier,
) {
    require(model.type == LedgerChartType.LINE)
    if (model.hasRenderablePoints()) {
        renderer.render(model, modifier.fillMaxSize().testTag(LedgerTestTags.CHART).clearAndSetSemantics { text = AnnotatedString(model.summary) })
    } else {
        LedgerChartEmptyState(modifier)
    }
}

@Composable
public fun LedgerColumnChart(
    model: LedgerChartUiModel,
    renderer: LedgerVicoRenderer,
    modifier: Modifier = Modifier,
) {
    require(model.type == LedgerChartType.COLUMN)
    when {
        !model.hasRenderablePoints() -> LedgerChartEmptyState(modifier)
        model.shouldUseHorizontalBars() -> LedgerHorizontalBarChart(model, modifier)
        else -> renderer.render(model, modifier.fillMaxSize().testTag(LedgerTestTags.CHART).clearAndSetSemantics { text = AnnotatedString(model.summary) })
    }
}

@Composable
public fun LedgerStackedChart(
    model: LedgerChartUiModel,
    renderer: LedgerVicoRenderer,
    modifier: Modifier = Modifier,
) {
    require(model.type == LedgerChartType.STACKED)
    if (model.hasRenderablePoints()) {
        renderer.render(model, modifier.fillMaxSize().testTag(LedgerTestTags.CHART).clearAndSetSemantics { text = AnnotatedString(model.summary) })
    } else {
        LedgerChartEmptyState(modifier)
    }
}

@Composable
public fun LedgerPieChart(
    model: LedgerChartUiModel,
    renderer: LedgerVicoRenderer,
    modifier: Modifier = Modifier,
) {
    require(model.type == LedgerChartType.PIE)
    val categoryCount = model.series.sumOf { series -> series.values.indices.count { it !in series.missingPointIndices } }
    when {
        categoryCount == 0 -> LedgerChartEmptyState(modifier)
        VisualizationCompatibility.resolve(model.type, categoryCount) == LedgerChartType.COLUMN -> {
            Column(verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                LedgerBanner(stringResource(R.string.ledger_chart_pie_fallback), LedgerBannerVariant.INFO)
                LedgerColumnChart(model.copy(type = LedgerChartType.COLUMN), LedgerVicoColumnRenderer, modifier)
            }
        }
        else -> renderer.render(model, modifier.fillMaxSize().testTag(LedgerTestTags.CHART).clearAndSetSemantics { text = AnnotatedString(model.summary) })
    }
}

private fun LedgerChartUiModel.hasRenderablePoints(): Boolean = series.any { chartSeries ->
    chartSeries.values.indices.any { it !in chartSeries.missingPointIndices }
}

private fun LedgerChartUiModel.shouldUseHorizontalBars(): Boolean =
    series.any { chartSeries -> chartSeries.pointLabels.any { it.length > HORIZONTAL_BAR_LABEL_THRESHOLD } }

@Composable
private fun LedgerChartEmptyState(modifier: Modifier) {
    LedgerBanner(
        stringResource(R.string.ledger_chart_no_data),
        LedgerBannerVariant.NEUTRAL,
        modifier.testTag(LedgerTestTags.CHART),
    )
}

@Composable
private fun LedgerHorizontalBarChart(model: LedgerChartUiModel, modifier: Modifier) {
    val presentValues = model.series.flatMap { series ->
        series.values.filterIndexed { index, _ -> index !in series.missingPointIndices }
    }
    val maximum = presentValues.maxOfOrNull { kotlin.math.abs(it) }?.takeIf { it > 0.0 } ?: 1.0
    val numberFormatter = NumberFormat.getNumberInstance(LocalLocale.current.platformLocale)
    val pointCount = model.series.maxOfOrNull { it.values.size } ?: 0
    Column(
        modifier.testTag(LedgerTestTags.CHART).clearAndSetSemantics { text = AnnotatedString(model.summary) },
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs),
    ) {
        repeat(pointCount) { index ->
            val categoryLabel = model.series.firstNotNullOfOrNull { it.pointLabels.getOrNull(index) }.orEmpty()
            if (categoryLabel.isNotBlank()) LedgerText(categoryLabel, LedgerTextRole.SECTION)
            model.series.forEach { series ->
                val value = series.values.getOrNull(index)
                if (value != null && index !in series.missingPointIndices) {
                    val formatted = series.formattedValues.getOrNull(index) ?: numberFormatter.format(value)
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            LedgerText(series.label, LedgerTextRole.LABEL)
                            LedgerText(formatted, LedgerTextRole.LABEL)
                        }
                        Box(
                            Modifier
                                .fillMaxWidth((kotlin.math.abs(value) / maximum).toFloat().coerceIn(MIN_VISIBLE_BAR_FRACTION, 1f))
                                .height(LedgerTheme.spacing.xs)
                                .background(chartColor(series.stableSeriesKey), LedgerTheme.shapes.full),
                        )
                    }
                }
            }
        }
    }
}

private const val HORIZONTAL_BAR_LABEL_THRESHOLD = 12
private const val MIN_VISIBLE_BAR_FRACTION = 0.02f

public object VisualizationCompatibility {
    public const val MAX_PIE_CATEGORIES: Int = 6

    public fun resolve(requested: LedgerChartType, categoryCount: Int): LedgerChartType = if (requested == LedgerChartType.PIE && categoryCount > MAX_PIE_CATEGORIES) {
        LedgerChartType.COLUMN
    } else {
        requested
    }
}

@Composable
public fun ChartCard(
    model: LedgerChartUiModel,
    chart: @Composable () -> Unit,
    dataTable: AccessibleTableUiModel,
    modifier: Modifier = Modifier,
    tableExpanded: Boolean = false,
    onToggleTable: () -> Unit,
) {
    val locale = LocalLocale.current.platformLocale
    val points = remember(model, locale) { model.chartPoints(NumberFormat.getNumberInstance(locale)) }
    var exploring by remember(model.title) { mutableStateOf(false) }
    var selectedPointIndex by remember(model.title) { mutableStateOf(0) }
    val explorerFocusRequester = remember { FocusRequester() }
    LaunchedEffect(exploring) {
        if (exploring && points.isNotEmpty()) explorerFocusRequester.requestFocus()
    }
    LaunchedEffect(points.size) {
        selectedPointIndex = selectedPointIndex.coerceIn(0, (points.lastIndex).coerceAtLeast(0))
    }
    LedgerCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
            Text(model.title, Modifier.semantics { heading() }, style = LedgerTheme.typography.titleSmall)
            Text(model.scope, style = LedgerTheme.typography.bodySmall, color = LedgerTheme.colors.material.onSurfaceVariant)
            Text(model.summary, style = LedgerTheme.typography.bodyMedium)
            model.baselineExplanation?.let { LedgerText(it, LedgerTextRole.SUPPORTING) }
            Box(Modifier.fillMaxWidth().height(LedgerTheme.dimensions.chartPreferredHeight)) {
                chart()
            }
            ChartLegend(model)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                if (points.isNotEmpty()) {
                    LedgerButton(
                        if (exploring) stringResource(R.string.ledger_close_chart_explorer) else stringResource(R.string.ledger_explore_chart),
                        { exploring = !exploring },
                        Modifier.weight(1f),
                        variant = LedgerButtonVariant.TEXT,
                    )
                }
                LedgerButton(
                    if (tableExpanded) stringResource(R.string.ledger_collapse_data_table) else stringResource(R.string.ledger_expand_data_table),
                    onToggleTable,
                    Modifier.weight(1f),
                    variant = LedgerButtonVariant.TEXT,
                )
            }
            if (exploring && points.isNotEmpty()) {
                ChartPointExplorer(
                    model = model,
                    point = points[selectedPointIndex],
                    pointIndex = selectedPointIndex,
                    pointCount = points.size,
                    onPrevious = { selectedPointIndex = Math.floorMod(selectedPointIndex - 1, points.size) },
                    onNext = { selectedPointIndex = (selectedPointIndex + 1) % points.size },
                    focusRequester = explorerFocusRequester,
                )
            }
            if (tableExpanded) AccessibleDataTable(dataTable)
        }
    }
}

private data class ChartPoint(
    val series: LedgerChartSeries,
    val pointIndex: Int,
    val dimension: String,
    val formattedValue: String,
)

private fun LedgerChartUiModel.chartPoints(numberFormatter: NumberFormat): List<ChartPoint> = series.flatMap { chartSeries ->
    chartSeries.values.mapIndexedNotNull { index, value ->
        if (index in chartSeries.missingPointIndices) {
            null
        } else {
            ChartPoint(
                series = chartSeries,
                pointIndex = index,
                dimension = chartSeries.pointLabels.getOrNull(index).orEmpty(),
                formattedValue = chartSeries.formattedValues.getOrNull(index) ?: numberFormatter.format(value),
            )
        }
    }
}

@Composable
private fun ChartPointExplorer(
    model: LedgerChartUiModel,
    point: ChartPoint,
    pointIndex: Int,
    pointCount: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    focusRequester: FocusRequester,
) {
    val previousLabel = stringResource(R.string.ledger_chart_previous_point)
    val nextLabel = stringResource(R.string.ledger_chart_next_point)
    val detail = stringResource(
        R.string.ledger_chart_marker_detail,
        point.dimension,
        point.series.label,
        point.formattedValue,
    )
    val stackTotal = if (model.type == LedgerChartType.STACKED) {
        model.series.sumOf { series ->
            series.values.getOrNull(point.pointIndex)?.takeUnless { point.pointIndex in series.missingPointIndices } ?: 0.0
        }.let { stringResource(R.string.ledger_chart_stack_total, NumberFormat.getNumberInstance(LocalLocale.current.platformLocale).format(it)) }
    } else {
        null
    }
    LedgerCard(
        Modifier
            .fillMaxWidth()
            .border(LedgerTheme.dimensions.strokeSelected, LedgerTheme.colors.chart.selection, LedgerTheme.shapes.lg)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> true.also { onPrevious() }
                    Key.DirectionRight -> true.also { onNext() }
                    else -> false
                }
            }
            .semantics {
                text = AnnotatedString(listOfNotNull(detail, stackTotal).joinToString(". "))
                customActions = listOf(
                    CustomAccessibilityAction(label = previousLabel) { onPrevious(); true },
                    CustomAccessibilityAction(label = nextLabel) { onNext(); true },
                )
            },
        variant = LedgerCardVariant.EMPHASIZED,
    ) {
        Column(Modifier.padding(LedgerTheme.spacing.xs), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            LedgerText(detail, LedgerTextRole.BODY)
            stackTotal?.let { LedgerText(it, LedgerTextRole.SUPPORTING) }
            LedgerText(stringResource(R.string.ledger_chart_point_position, pointIndex + 1, pointCount), LedgerTextRole.SUPPORTING)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LedgerButton(previousLabel, onPrevious, variant = LedgerButtonVariant.TEXT)
                LedgerButton(nextLabel, onNext, variant = LedgerButtonVariant.TEXT)
            }
        }
    }
}

@Composable
private fun ChartLegend(model: LedgerChartUiModel) {
    val entries = if (model.type == LedgerChartType.PIE) {
        model.series.flatMap { series ->
            series.values.indices.map { index ->
                (series.pointLabels.getOrNull(index) ?: series.label) to "${series.stableSeriesKey}:$index"
            }
        }
    } else {
        model.series.map { it.label to it.stableSeriesKey }
    }
    Column(verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xxs)) {
        entries.forEach { (label, stableKey) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                ChartSeriesGlyph(stableKey)
                Text(label, style = LedgerTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun ChartSeriesGlyph(stableKey: String) {
    val color = chartColor(stableKey)
    val styleIndex = chartStyleIndex(stableKey)
    Canvas(Modifier.size(LedgerTheme.dimensions.iconXs).clearAndSetSemantics { }) {
        when (styleIndex) {
            0 -> drawCircle(color)
            1 -> drawRect(color)
            else -> {
                val strokeWidth = size.minDimension / 5f
                drawLine(color, start = androidx.compose.ui.geometry.Offset(0f, size.height / 2f), end = androidx.compose.ui.geometry.Offset(size.width * 0.35f, size.height / 2f), strokeWidth = strokeWidth)
                drawLine(color, start = androidx.compose.ui.geometry.Offset(size.width * 0.65f, size.height / 2f), end = androidx.compose.ui.geometry.Offset(size.width, size.height / 2f), strokeWidth = strokeWidth)
            }
        }
    }
}

private fun chartStyleIndex(stableKey: String): Int = Math.floorMod(stableKey.hashCode(), 3)

@Composable
private fun chartColor(stableKey: String): androidx.compose.ui.graphics.Color {
    val colors = LedgerTheme.colors.chart.categorical
    return colors[Math.floorMod(stableKey.hashCode(), colors.size)]
}

@Composable
public fun AccessibleDataTable(
    model: AccessibleTableUiModel,
    modifier: Modifier = Modifier,
    pageIndex: Int = 0,
    pageCount: Int = 1,
    onPreviousPage: (() -> Unit)? = null,
    onNextPage: (() -> Unit)? = null,
) {
    require(model.columnHeaders.isNotEmpty())
    require(model.rows.all { it.size == model.columnHeaders.size })
    val horizontalScrollState = rememberScrollState()
    Column(
        modifier
            .testTag(LedgerTestTags.DATA_TABLE)
            .semantics { paneTitle = model.caption },
    ) {
        Text(model.caption, style = LedgerTheme.typography.titleSmall)
        Row(Modifier.background(LedgerTheme.colors.material.surfaceContainerHigh)) {
            model.columnHeaders.forEachIndexed { index, header ->
                TableCell(header, header = true, endAligned = index in model.endAlignedColumnIndices)
            }
        }
        model.rows.forEach { row ->
            Row {
                row.forEachIndexed { index, value ->
                    TableCell(value, header = false, endAligned = index in model.endAlignedColumnIndices)
                }
            }
        }
        if (pageCount > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (onPreviousPage != null) {
                    LedgerButton(
                        stringResource(R.string.ledger_previous_page),
                        onPreviousPage,
                        variant = LedgerButtonVariant.TEXT,
                        enabled = pageIndex > 0,
                    )
                }
                Text(stringResource(R.string.ledger_page_count, pageIndex + 1, pageCount), style = LedgerTheme.typography.labelMedium)
                if (onNextPage != null) {
                    LedgerButton(
                        stringResource(R.string.ledger_next_page),
                        onNextPage,
                        variant = LedgerButtonVariant.TEXT,
                        enabled = pageIndex + 1 < pageCount,
                    )
                }
            }
        }
    }
}

@Composable
private fun TableCell(value: String, header: Boolean, endAligned: Boolean) {
    Text(
        value,
        Modifier.width(LedgerTheme.spacing.giant * 3).padding(LedgerTheme.spacing.xs).semantics {
            if (header) heading()
        },
        style = LedgerTheme.typography.bodyMedium.copy(fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal, fontFeatureSettings = "tnum"),
        textAlign = if (endAligned) TextAlign.End else TextAlign.Start,
    )
}

@Immutable
public data class LedgerMapDesignSubset(
    val clusterLowDiameter: androidx.compose.ui.unit.Dp,
    val clusterMediumDiameter: androidx.compose.ui.unit.Dp,
    val clusterHighDiameter: androidx.compose.ui.unit.Dp,
    val pointDiameter: androidx.compose.ui.unit.Dp,
    val selectedPointDiameter: androidx.compose.ui.unit.Dp,
    val clusterColor: androidx.compose.ui.graphics.Color,
    val userLocationColor: androidx.compose.ui.graphics.Color,
    val heatSequence: List<androidx.compose.ui.graphics.Color>,
)

public object LedgerMapDesignContract {
    @Composable
    public fun current(): LedgerMapDesignSubset = LedgerMapDesignSubset(
        clusterLowDiameter = LedgerTheme.spacing.xxxl - LedgerTheme.spacing.xxs,
        clusterMediumDiameter = LedgerTheme.dimensions.categoryIconContainer,
        clusterHighDiameter = LedgerTheme.dimensions.searchFieldHeight,
        pointDiameter = LedgerTheme.spacing.xxl,
        selectedPointDiameter = LedgerTheme.spacing.xxxl,
        clusterColor = LedgerTheme.colors.material.primary,
        userLocationColor = LedgerTheme.colors.info.base,
        heatSequence = LedgerTheme.colors.chart.sequentialTeal,
    )
}

@Composable
public fun MapPanel(
    model: LedgerMapUiModel,
    mapContent: @Composable () -> Unit,
    fallbackContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    LedgerCard(modifier.fillMaxWidth().testTag(LedgerTestTags.MAP)) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = LedgerTheme.dimensions.chartMinHeight)
                    .clearAndSetSemantics { text = AnnotatedString(model.summary) },
            ) {
                when (model.availability) {
                    MapAvailability.AVAILABLE -> mapContent()
                    MapAvailability.LOADING -> LedgerLoadingState()
                    MapAvailability.UNAVAILABLE -> {
                        Column(Modifier.padding(LedgerTheme.spacing.sm)) {
                            LedgerBanner(stringResource(R.string.ledger_map_unavailable), LedgerBannerVariant.INFO)
                            fallbackContent()
                        }
                    }
                }
            }
            Text(model.attribution, Modifier.padding(LedgerTheme.spacing.xs), style = LedgerTheme.typography.labelSmall)
        }
    }
}

@Composable
public fun OperationProgressPanel(
    model: OperationProgressUiModel,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
    onPause: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onViewError: (() -> Unit)? = null,
    onCleanTemporaryFiles: (() -> Unit)? = null,
) {
    val announcedProgress = model.progress?.let { progress -> ((progress.coerceIn(0f, 1f) * 20).toInt() * 5) }
    val announcedProgressText = announcedProgress?.let { percentage ->
        NumberFormat.getPercentInstance(LocalLocale.current.platformLocale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 0
        }.format(percentage / 100.0)
    }
    var failureDetailsVisible by remember(model.failureCode) { mutableStateOf(false) }
    LedgerCard(
        modifier
            .fillMaxWidth()
            .testTag(LedgerTestTags.OPERATION)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                text = AnnotatedString(
                    listOfNotNull(
                        model.name,
                        model.phase,
                        announcedProgressText,
                        model.statusExplanation,
                    ).joinToString(", "),
                )
            },
    ) {
        Column(Modifier.padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            Text(model.name, Modifier.semantics { heading() }, style = LedgerTheme.typography.titleSmall)
            Text(model.phase, style = LedgerTheme.typography.bodyMedium)
            LedgerProgressIndicator(model.progress, accessibleText = model.processedText)
            Text(model.processedText, style = LedgerTheme.typography.bodySmall)
            Text(model.statusExplanation, style = LedgerTheme.typography.bodySmall, color = LedgerTheme.colors.material.onSurfaceVariant)
            model.failureCode?.let { code ->
                LedgerBanner(code.value, LedgerBannerVariant.DANGER, actionLabel = onRetry?.let { stringResource(R.string.ledger_retry) }, onAction = onRetry)
                Row(horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                    LedgerButton(
                        stringResource(R.string.ledger_view_error),
                        {
                            failureDetailsVisible = !failureDetailsVisible
                            onViewError?.invoke()
                        },
                        variant = LedgerButtonVariant.TEXT,
                    )
                    if (model.temporaryFilesPresent && onCleanTemporaryFiles != null) {
                        LedgerButton(stringResource(R.string.ledger_clean_temporary_files), onCleanTemporaryFiles, variant = LedgerButtonVariant.TEXT)
                    }
                }
                if (failureDetailsVisible) {
                    LedgerText("${code.value} · ${model.statusExplanation}", LedgerTextRole.SUPPORTING)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                if (model.capability == OperationCapability.CANCELABLE && onCancel != null) {
                    LedgerButton(stringResource(R.string.ledger_cancel), onCancel, variant = LedgerButtonVariant.SECONDARY)
                }
                if (model.capability == OperationCapability.PAUSABLE && onPause != null) {
                    LedgerButton(stringResource(R.string.ledger_pause), onPause, variant = LedgerButtonVariant.SECONDARY)
                }
            }
        }
    }
}

@Composable
public fun SensitiveValueField(
    revealedValue: String,
    revealed: Boolean,
    onReveal: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
    secondsRemaining: Int = 0,
    copyAllowed: Boolean = false,
    onCopy: (() -> Unit)? = null,
) {
    val semantic = stringResource(if (revealed) R.string.ledger_sensitive_revealed else R.string.ledger_sensitive_masked)
    LedgerCard(
        modifier
            .fillMaxWidth()
            .testTag(LedgerTestTags.SENSITIVE_VALUE)
            .clearAndSetSemantics { contentDescription = semantic },
    ) {
        Row(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (revealed) revealedValue else "•••• •••• •••• ••••",
                Modifier.weight(1f).clearAndSetSemantics { },
                style = LedgerTheme.typography.amountMedium,
            )
            if (revealed && secondsRemaining > 0) {
                Text(stringResource(R.string.ledger_seconds_remaining, secondsRemaining), style = LedgerTheme.typography.labelMedium)
            }
            LedgerButton(
                stringResource(if (revealed) R.string.ledger_hide else R.string.ledger_reveal),
                if (revealed) onHide else onReveal,
                variant = LedgerButtonVariant.TEXT,
            )
            if (revealed && copyAllowed && onCopy != null) LedgerIconButton(LedgerIcon.ATTACHMENT, stringResource(R.string.ledger_copy), onCopy)
        }
    }
}

@Composable
public fun HighRiskConfirmation(
    title: String,
    scope: String,
    consequence: String,
    unaffected: String,
    requiredPhrase: String,
    enteredPhrase: String,
    onPhraseChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    authenticating: Boolean = false,
) {
    val matches = enteredPhrase == requiredPhrase
    LedgerCard(
        modifier.fillMaxWidth().semantics { paneTitle = title },
        containerColor = LedgerTheme.colors.danger.container,
        borderColor = LedgerTheme.colors.danger.base,
    ) {
        Column(Modifier.padding(LedgerTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
            Text(title, Modifier.semantics { heading() }, style = LedgerTheme.typography.titleMedium, color = LedgerTheme.colors.danger.onContainer)
            Text(scope, color = LedgerTheme.colors.danger.onContainer)
            Text(consequence, color = LedgerTheme.colors.danger.onContainer)
            Text(unaffected, color = LedgerTheme.colors.danger.onContainer)
            LedgerTextField(
                value = enteredPhrase,
                onValueChange = onPhraseChange,
                label = stringResource(R.string.ledger_confirm_phrase),
                supportingText = requiredPhrase,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                LedgerButton(stringResource(R.string.ledger_cancel), onCancel, variant = LedgerButtonVariant.SECONDARY)
                LedgerButton(
                    title,
                    onConfirm,
                    Modifier.padding(start = LedgerTheme.spacing.xs),
                    variant = LedgerButtonVariant.DANGER,
                    enabled = matches && !authenticating,
                )
            }
        }
    }
}

@Composable
public fun TokenPaletteGolden(modifier: Modifier = Modifier) {
    val colors = remember { LedgerTokenMapping.goldenPalette }
    val background = LedgerTheme.colors.material.background
    Canvas(modifier) {
        drawRect(background)
        val columns = 16
        val cellWidth = size.width / columns
        val rows = (colors.size + columns - 1) / columns
        val cellHeight = size.height / rows
        colors.forEachIndexed { index, color ->
            drawRect(
                color,
                topLeft = androidx.compose.ui.geometry.Offset((index % columns) * cellWidth, (index / columns) * cellHeight),
                size = androidx.compose.ui.geometry.Size(cellWidth, cellHeight),
            )
        }
    }
}
