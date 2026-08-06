@file:Suppress(
    "ktlint:standard:function-naming",
    "FunctionNaming",
    "LongParameterList",
    "MagicNumber",
    "TooManyFunctions",
)

package app.ledger.core.designsystem

import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.columnModel
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.pie.PieChartHost
import com.patrykandpatrick.vico.compose.pie.data.PieChartModelProducer
import com.patrykandpatrick.vico.compose.pie.data.pieSeries
import com.patrykandpatrick.vico.compose.pie.rememberPieChart

/** Adapter implemented inside the design-system chart integration; features pass only typed models. */
public fun interface LedgerVicoRenderer {
    @Composable
    public fun render(model: LedgerChartUiModel, modifier: Modifier)
}

/** Governed, non-animated Vico line renderer for feature-owned typed models. */
public object LedgerVicoLineRenderer : LedgerVicoRenderer {
    @Composable
    override fun render(model: LedgerChartUiModel, modifier: Modifier) {
        require(model.type == LedgerChartType.LINE)
        val producer = remember { CartesianChartModelProducer() }
        LaunchedEffect(model) {
            producer.runTransaction {
                lineModel {
                    model.series.forEach { series -> series(series.values, series.stableSeriesKey) }
                }
            }
        }
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(),
                startAxis = VerticalAxis.rememberStart(),
                bottomAxis = HorizontalAxis.rememberBottom(),
            ),
            modelProducer = producer,
            modifier = modifier,
            animationSpec = snap(),
            animateIn = false,
        )
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
        LaunchedEffect(model) {
            producer.runTransaction {
                pieSeries { series(model.series.flatMap(LedgerChartSeries::values)) }
            }
        }
        PieChartHost(
            chart = rememberPieChart(),
            modelProducer = producer,
            modifier = modifier,
            animationSpec = snap(),
            animateIn = false,
        )
    }
}

@Composable
private fun LedgerVicoColumns(model: LedgerChartUiModel, modifier: Modifier, stacked: Boolean) {
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(model) {
        producer.runTransaction {
            columnModel { model.series.forEach { series -> series(series.values) } }
        }
    }
    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberColumnCartesianLayer(
                mergeMode = { if (stacked) ColumnCartesianLayer.MergeMode.Stacked else ColumnCartesianLayer.MergeMode.Grouped() },
            ),
            startAxis = VerticalAxis.rememberStart(),
            bottomAxis = HorizontalAxis.rememberBottom(),
        ),
        modelProducer = producer,
        modifier = modifier,
        animationSpec = snap(),
        animateIn = false,
    )
}

@Composable
public fun LedgerLineChart(
    model: LedgerChartUiModel,
    renderer: LedgerVicoRenderer,
    modifier: Modifier = Modifier,
) {
    require(model.type == LedgerChartType.LINE)
    renderer.render(model, modifier.testTag(LedgerTestTags.CHART).semantics { contentDescription = model.summary })
}

@Composable
public fun LedgerColumnChart(
    model: LedgerChartUiModel,
    renderer: LedgerVicoRenderer,
    modifier: Modifier = Modifier,
) {
    require(model.type == LedgerChartType.COLUMN)
    renderer.render(model, modifier.testTag(LedgerTestTags.CHART).semantics { contentDescription = model.summary })
}

@Composable
public fun LedgerStackedChart(
    model: LedgerChartUiModel,
    renderer: LedgerVicoRenderer,
    modifier: Modifier = Modifier,
) {
    require(model.type == LedgerChartType.STACKED)
    renderer.render(model, modifier.testTag(LedgerTestTags.CHART).semantics { contentDescription = model.summary })
}

@Composable
public fun LedgerPieChart(
    model: LedgerChartUiModel,
    renderer: LedgerVicoRenderer,
    modifier: Modifier = Modifier,
) {
    require(model.type == LedgerChartType.PIE)
    require(model.series.sumOf { it.values.size } <= MAX_PIE_CATEGORIES) {
        "pie charts are limited to six categories; use the compatibility resolver"
    }
    renderer.render(model, modifier.testTag(LedgerTestTags.CHART).semantics { contentDescription = model.summary })
}

public object VisualizationCompatibility {
    public const val MAX_PIE_CATEGORIES: Int = 6

    public fun resolve(requested: LedgerChartType, categoryCount: Int): LedgerChartType = if (requested == LedgerChartType.PIE && categoryCount > MAX_PIE_CATEGORIES) {
        LedgerChartType.COLUMN
    } else {
        requested
    }
}

private const val MAX_PIE_CATEGORIES: Int = VisualizationCompatibility.MAX_PIE_CATEGORIES

@Composable
public fun ChartCard(
    model: LedgerChartUiModel,
    chart: @Composable () -> Unit,
    dataTable: AccessibleTableUiModel,
    modifier: Modifier = Modifier,
    tableExpanded: Boolean = false,
    onToggleTable: () -> Unit,
) {
    LedgerCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
            Text(model.title, Modifier.semantics { heading() }, style = LedgerTheme.typography.titleSmall)
            Text(model.scope, style = LedgerTheme.typography.bodySmall, color = LedgerTheme.colors.material.onSurfaceVariant)
            Box(Modifier.fillMaxWidth().heightIn(min = LedgerTheme.dimensions.chartMinHeight).semantics { contentDescription = model.summary }) {
                chart()
            }
            Text(model.summary, style = LedgerTheme.typography.bodyMedium)
            LedgerButton(
                if (tableExpanded) stringResource(R.string.ledger_view_data_table) else stringResource(R.string.ledger_expand_data_table),
                onToggleTable,
                variant = LedgerButtonVariant.TEXT,
            )
            if (tableExpanded) AccessibleDataTable(dataTable)
        }
    }
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
    Column(
        modifier
            .testTag(LedgerTestTags.DATA_TABLE)
            .semantics { paneTitle = model.caption }
            .horizontalScroll(rememberScrollState()),
    ) {
        Text(model.caption, style = LedgerTheme.typography.titleSmall)
        Row(Modifier.background(LedgerTheme.colors.material.surfaceContainerHigh)) {
            model.columnHeaders.forEach { header -> TableCell(header, header = true) }
        }
        model.rows.forEach { row ->
            Row { row.forEach { value -> TableCell(value, header = false) } }
        }
        if (pageCount > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (onPreviousPage != null) LedgerButton("‹", onPreviousPage, variant = LedgerButtonVariant.TEXT, enabled = pageIndex > 0)
                Text("${pageIndex + 1} / $pageCount", style = LedgerTheme.typography.labelMedium)
                if (onNextPage != null) LedgerButton("›", onNextPage, variant = LedgerButtonVariant.TEXT, enabled = pageIndex + 1 < pageCount)
            }
        }
    }
}

@Composable
private fun TableCell(value: String, header: Boolean) {
    Text(
        value,
        Modifier.width(LedgerTheme.spacing.giant * 2).padding(LedgerTheme.spacing.xs).semantics {
            if (header) heading()
        },
        style = LedgerTheme.typography.bodyMedium.copy(fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal, fontFeatureSettings = "tnum"),
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
                    .semantics { contentDescription = model.summary },
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
) {
    LedgerCard(
        modifier
            .fillMaxWidth()
            .testTag(LedgerTestTags.OPERATION)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "${model.name}, ${model.phase}, ${model.processedText}, ${model.statusExplanation}"
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
            Text(if (revealed) revealedValue else "•••• •••• •••• ••••", Modifier.weight(1f), style = LedgerTheme.typography.amountMedium)
            if (revealed && secondsRemaining > 0) Text("${secondsRemaining}s", style = LedgerTheme.typography.labelMedium)
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
