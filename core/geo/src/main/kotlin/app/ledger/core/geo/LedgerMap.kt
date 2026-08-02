@file:Suppress(
    "ktlint:standard:function-naming",
    "FunctionNaming",
    "LongParameterList",
    "SpreadOperator",
    "TooManyFunctions",
)

package app.ledger.core.geo

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.ledger.core.common.StableId
import app.ledger.core.designsystem.AccessibleDataTable
import app.ledger.core.designsystem.AccessibleTableUiModel
import app.ledger.core.designsystem.LedgerButton
import app.ledger.core.designsystem.LedgerButtonVariant
import app.ledger.core.designsystem.LedgerMapDesignContract
import app.ledger.core.designsystem.LedgerMapUiModel
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.MapAvailability
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.HeatmapLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.heatmapColor
import org.maplibre.android.style.layers.PropertyFactory.heatmapIntensity
import org.maplibre.android.style.layers.PropertyFactory.heatmapOpacity
import org.maplibre.android.style.layers.PropertyFactory.heatmapRadius
import org.maplibre.android.style.layers.PropertyFactory.heatmapWeight
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

enum class LedgerMapMode { CLUSTERS, HEATMAP, SINGLE_POINTS }

enum class LedgerMapFailure { STYLE_OR_NETWORK_UNAVAILABLE, RENDERER_UNAVAILABLE }

sealed interface LedgerMapStyleSource {
    data class Uri(val value: String) : LedgerMapStyleSource {
        init {
            require(value.startsWith("https://") || value.startsWith("asset://"))
        }
    }

    data class Json(val value: String) : LedgerMapStyleSource {
        init {
            require(value.isNotBlank())
        }
    }
}

data class LedgerMapStyleConfiguration(
    val light: LedgerMapStyleSource,
    val dark: LedgerMapStyleSource,
    val attribution: String,
) {
    init {
        require(attribution.isNotBlank())
    }

    companion object {
        val OpenFreeMap: LedgerMapStyleConfiguration = LedgerMapStyleConfiguration(
            light = LedgerMapStyleSource.Uri("https://tiles.openfreemap.org/styles/liberty"),
            dark = LedgerMapStyleSource.Uri("https://tiles.openfreemap.org/styles/dark"),
            attribution = "© OpenStreetMap contributors · OpenFreeMap",
        )
    }
}

data class LedgerMapPoint(
    val id: StableId,
    val latitudeE7: Int,
    val longitudeE7: Int,
    val weight: Long,
    val selected: Boolean = false,
) {
    init {
        require(latitudeE7 in MIN_LATITUDE_E7..MAX_LATITUDE_E7)
        require(longitudeE7 in MIN_LONGITUDE_E7..MAX_LONGITUDE_E7)
        require(weight >= 0L)
    }
}

data class LedgerMapAccessibleRow(
    val primaryText: String,
    val secondaryText: String,
) {
    init {
        require(primaryText.isNotBlank())
        require(secondaryText.isNotBlank())
    }
}

sealed interface LedgerMapState {
    data object Loading : LedgerMapState

    data class Available(
        val summary: String,
        val mode: LedgerMapMode,
        val points: List<LedgerMapPoint>,
        val accessibleRows: List<LedgerMapAccessibleRow>,
    ) : LedgerMapState

    data class Unavailable(
        val summary: String,
        val accessibleRows: List<LedgerMapAccessibleRow>,
    ) : LedgerMapState
}

@Composable
fun LedgerMap(
    state: LedgerMapState,
    styleConfiguration: LedgerMapStyleConfiguration,
    accessibleCaption: String,
    accessibleColumnHeaders: List<String>,
    showAccessibleListLabel: String,
    hideAccessibleListLabel: String,
    onFailure: (LedgerMapFailure) -> Unit,
    modifier: Modifier = Modifier,
) {
    require(accessibleColumnHeaders.size == ACCESSIBLE_COLUMN_COUNT)
    var listVisible by remember { mutableStateOf(false) }
    val rows = when (state) {
        LedgerMapState.Loading -> emptyList()
        is LedgerMapState.Available -> state.accessibleRows
        is LedgerMapState.Unavailable -> state.accessibleRows
    }
    val summary = when (state) {
        LedgerMapState.Loading -> accessibleCaption
        is LedgerMapState.Available -> state.summary
        is LedgerMapState.Unavailable -> state.summary
    }
    val availability = when (state) {
        LedgerMapState.Loading -> MapAvailability.LOADING
        is LedgerMapState.Available -> MapAvailability.AVAILABLE
        is LedgerMapState.Unavailable -> MapAvailability.UNAVAILABLE
    }
    Column(modifier.fillMaxWidth()) {
        app.ledger.core.designsystem.MapPanel(
            model = LedgerMapUiModel(summary, availability, styleConfiguration.attribution),
            mapContent = {
                val available = state as LedgerMapState.Available
                MapLibreContent(available, styleConfiguration, onFailure)
            },
            fallbackContent = {
                if (rows.isNotEmpty()) AccessibleMapRows(accessibleCaption, accessibleColumnHeaders, rows)
            },
        )
        if (availability == MapAvailability.AVAILABLE && rows.isNotEmpty()) {
            LedgerButton(
                text = if (listVisible) hideAccessibleListLabel else showAccessibleListLabel,
                onClick = { listVisible = !listVisible },
                modifier = Modifier.padding(top = LedgerTheme.spacing.xs),
                variant = LedgerButtonVariant.TEXT,
            )
            if (listVisible) AccessibleMapRows(accessibleCaption, accessibleColumnHeaders, rows)
        }
    }
}

@Composable
private fun AccessibleMapRows(
    caption: String,
    headers: List<String>,
    rows: List<LedgerMapAccessibleRow>,
) {
    AccessibleDataTable(
        AccessibleTableUiModel(
            caption = caption,
            columnHeaders = headers,
            rows = rows.map { listOf(it.primaryText, it.secondaryText) },
        ),
        Modifier.testTag(LedgerTestTags.MAP_FALLBACK),
    )
}

@Composable
private fun MapLibreContent(
    state: LedgerMapState.Available,
    configuration: LedgerMapStyleConfiguration,
    onFailure: (LedgerMapFailure) -> Unit,
) {
    if (LocalInspectionMode.current) {
        onFailure(LedgerMapFailure.RENDERER_UNAVAILABLE)
        return
    }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestFailure by rememberUpdatedState(onFailure)
    val dark = LedgerTheme.colors.material.background.luminance() < DARK_LUMINANCE_THRESHOLD
    val design = LedgerMapDesignContract.current()
    val density = LocalDensity.current
    val renderSpec = with(density) {
        MapRenderSpec(
            style = if (dark) configuration.dark else configuration.light,
            mode = state.mode,
            points = state.points,
            clusterLowRadius = design.clusterLowDiameter.toPx() / DIAMETER_TO_RADIUS,
            clusterMediumRadius = design.clusterMediumDiameter.toPx() / DIAMETER_TO_RADIUS,
            clusterHighRadius = design.clusterHighDiameter.toPx() / DIAMETER_TO_RADIUS,
            pointRadius = design.pointDiameter.toPx() / DIAMETER_TO_RADIUS,
            selectedPointRadius = design.selectedPointDiameter.toPx() / DIAMETER_TO_RADIUS,
            clusterColor = design.clusterColor.toArgb(),
            heatColors = design.heatSequence.map { it.toArgb() },
            pointStrokeColor = LedgerTheme.colors.material.surface.toArgb(),
            clusterTextColor = LedgerTheme.colors.material.onPrimary.toArgb(),
            cameraPaddingPixels = LedgerTheme.spacing.sm.toPx().toInt(),
        )
    }
    val controller = remember(context) {
        MapLibre.getInstance(context.applicationContext)
        LedgerMapController(context) { latestFailure(LedgerMapFailure.STYLE_OR_NETWORK_UNAVAILABLE) }
    }
    controller.render(renderSpec)
    DisposableEffect(controller, lifecycle) {
        controller.attach(lifecycle)
        onDispose { controller.detach(lifecycle) }
    }
    AndroidView(
        factory = { controller.view },
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = state.summary },
        update = { controller.render(renderSpec) },
    )
}

private data class MapRenderSpec(
    val style: LedgerMapStyleSource,
    val mode: LedgerMapMode,
    val points: List<LedgerMapPoint>,
    val clusterLowRadius: Float,
    val clusterMediumRadius: Float,
    val clusterHighRadius: Float,
    val pointRadius: Float,
    val selectedPointRadius: Float,
    val clusterColor: Int,
    val heatColors: List<Int>,
    val pointStrokeColor: Int,
    val clusterTextColor: Int,
    val cameraPaddingPixels: Int,
)

private class LedgerMapController(
    context: Context,
    private val onFailure: () -> Unit,
) {
    val view: MapView = MapView(context)
    private var map: MapLibreMap? = null
    private var desired: MapRenderSpec? = null
    private var renderedStyleSignature: Int? = null
    private var started = false
    private var resumed = false
    private var destroyed = false
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> start()
            Lifecycle.Event.ON_RESUME -> resume()
            Lifecycle.Event.ON_PAUSE -> pause()
            Lifecycle.Event.ON_STOP -> stop()
            Lifecycle.Event.ON_DESTROY -> destroy()
            else -> Unit
        }
    }

    init {
        view.onCreate(null)
        view.addOnDidFailLoadingMapListener { onFailure() }
        view.getMapAsync { ready ->
            map = ready
            ready.uiSettings.isAttributionEnabled = true
            ready.uiSettings.isLogoEnabled = true
            desired?.let(::apply)
        }
    }

    fun render(spec: MapRenderSpec) {
        desired = spec
        map?.let { apply(spec) }
    }

    fun attach(lifecycle: Lifecycle) {
        lifecycle.addObserver(lifecycleObserver)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resume()
    }

    fun detach(lifecycle: Lifecycle) {
        lifecycle.removeObserver(lifecycleObserver)
        destroy()
    }

    private fun apply(spec: MapRenderSpec) {
        val ready = map ?: return
        val styleSignature = spec.copy(points = emptyList()).hashCode()
        if (renderedStyleSignature == styleSignature) {
            ready.style?.getSourceAs<GeoJsonSource>(SOURCE_ID)?.setGeoJson(spec.featureCollection())
            moveCamera(ready, spec)
            return
        }
        renderedStyleSignature = styleSignature
        val builder = when (val source = spec.style) {
            is LedgerMapStyleSource.Uri -> Style.Builder().fromUri(source.value)
            is LedgerMapStyleSource.Json -> Style.Builder().fromJson(source.value)
        }
        ready.setStyle(builder) { style ->
            addOverlay(style, spec)
            moveCamera(ready, spec)
        }
    }

    private fun start() {
        if (!started && !destroyed) {
            view.onStart()
            started = true
        }
    }

    private fun resume() {
        start()
        if (!resumed && !destroyed) {
            view.onResume()
            resumed = true
        }
    }

    private fun pause() {
        if (resumed && !destroyed) {
            view.onPause()
            resumed = false
        }
    }

    private fun stop() {
        pause()
        if (started && !destroyed) {
            view.onStop()
            started = false
        }
    }

    private fun destroy() {
        if (destroyed) return
        stop()
        view.onDestroy()
        destroyed = true
        map = null
    }
}

private fun addOverlay(style: Style, spec: MapRenderSpec) {
    val clustered = spec.mode == LedgerMapMode.CLUSTERS
    style.addSource(
        GeoJsonSource(
            SOURCE_ID,
            spec.featureCollection(),
            GeoJsonOptions().withCluster(clustered).withClusterRadius(CLUSTER_RADIUS_PIXELS),
        ),
    )
    when (spec.mode) {
        LedgerMapMode.CLUSTERS -> addClusterLayers(style, spec)
        LedgerMapMode.HEATMAP -> addHeatmapLayer(style, spec)
        LedgerMapMode.SINGLE_POINTS -> addPointLayer(style, spec)
    }
}

private fun addClusterLayers(style: Style, spec: MapRenderSpec) {
    val clusterFilter = Expression.has(CLUSTER_COUNT_PROPERTY)
    style.addLayer(
        CircleLayer(CLUSTER_LAYER_ID, SOURCE_ID)
            .withFilter(clusterFilter)
            .withProperties(
                circleColor(spec.clusterColor),
                circleRadius(
                    Expression.step(
                        Expression.get(CLUSTER_COUNT_PROPERTY),
                        spec.clusterLowRadius,
                        Expression.stop(MEDIUM_CLUSTER_COUNT, spec.clusterMediumRadius),
                        Expression.stop(HIGH_CLUSTER_COUNT, spec.clusterHighRadius),
                    ),
                ),
            ),
    )
    style.addLayer(
        SymbolLayer(CLUSTER_COUNT_LAYER_ID, SOURCE_ID)
            .withFilter(clusterFilter)
            .withProperties(
                textField(Expression.get(CLUSTER_ABBREVIATED_COUNT_PROPERTY)),
                textSize(CLUSTER_TEXT_SIZE_PIXELS),
                textColor(spec.clusterTextColor),
                textHaloColor(spec.pointStrokeColor),
                textHaloWidth(CLUSTER_TEXT_HALO_PIXELS),
                textAllowOverlap(true),
            ),
    )
    addPointLayer(style, spec)
}

private fun addPointLayer(style: Style, spec: MapRenderSpec) {
    style.addLayer(
        CircleLayer(POINT_LAYER_ID, SOURCE_ID)
            .withFilter(Expression.not(Expression.has(CLUSTER_COUNT_PROPERTY)))
            .withProperties(
                circleColor(spec.clusterColor),
                circleRadius(Expression.get(POINT_RADIUS_PROPERTY)),
                circleStrokeColor(spec.pointStrokeColor),
                circleStrokeWidth(POINT_STROKE_WIDTH_PIXELS),
            ),
    )
}

private fun addHeatmapLayer(style: Style, spec: MapRenderSpec) {
    val colors = spec.heatColors.ifEmpty { listOf(spec.clusterColor) }
    val stops = colors.mapIndexed { index, color ->
        Expression.stop(index.toDouble() / (colors.size - 1).coerceAtLeast(1), color)
    }.toTypedArray()
    style.addLayer(
        HeatmapLayer(HEATMAP_LAYER_ID, SOURCE_ID).withProperties(
            heatmapWeight(Expression.get(WEIGHT_PROPERTY)),
            heatmapIntensity(HEATMAP_INTENSITY),
            heatmapRadius(spec.clusterMediumRadius),
            heatmapOpacity(HEATMAP_OPACITY),
            heatmapColor(Expression.interpolate(Expression.linear(), Expression.heatmapDensity(), *stops)),
        ),
    )
}

private fun MapRenderSpec.featureCollection(): FeatureCollection = FeatureCollection.fromFeatures(
    points.map { point ->
        Feature.fromGeometry(
            Point.fromLngLat(point.longitudeE7 / E7_DIVISOR, point.latitudeE7 / E7_DIVISOR),
            null,
            point.id.toString(),
        ).apply {
            addNumberProperty(WEIGHT_PROPERTY, point.weight)
            addNumberProperty(POINT_RADIUS_PROPERTY, if (point.selected) selectedPointRadius else pointRadius)
        }
    },
)

private fun moveCamera(map: MapLibreMap, spec: MapRenderSpec) {
    if (spec.points.isEmpty()) return
    val coordinates = spec.points.map { LatLng(it.latitudeE7 / E7_DIVISOR, it.longitudeE7 / E7_DIVISOR) }
    if (coordinates.size == 1) {
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(coordinates.single(), SINGLE_POINT_ZOOM))
    } else {
        val bounds = LatLngBounds.Builder().includes(coordinates).build()
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, spec.cameraPaddingPixels))
    }
}

private const val ACCESSIBLE_COLUMN_COUNT = 2
private const val MIN_LATITUDE_E7 = -900_000_000
private const val MAX_LATITUDE_E7 = 900_000_000
private const val MIN_LONGITUDE_E7 = -1_800_000_000
private const val MAX_LONGITUDE_E7 = 1_800_000_000
private const val E7_DIVISOR = 10_000_000.0
private const val DIAMETER_TO_RADIUS = 2f
private const val DARK_LUMINANCE_THRESHOLD = 0.5f
private const val CLUSTER_RADIUS_PIXELS = 50
private const val MEDIUM_CLUSTER_COUNT = 10
private const val HIGH_CLUSTER_COUNT = 50
private const val CLUSTER_TEXT_SIZE_PIXELS = 12f
private const val CLUSTER_TEXT_HALO_PIXELS = 1f
private const val POINT_STROKE_WIDTH_PIXELS = 2f
private const val HEATMAP_INTENSITY = 1f
private const val HEATMAP_OPACITY = 0.82f
private const val SINGLE_POINT_ZOOM = 14.0
private const val SOURCE_ID = "ledger-location-source"
private const val CLUSTER_LAYER_ID = "ledger-location-clusters"
private const val CLUSTER_COUNT_LAYER_ID = "ledger-location-cluster-counts"
private const val POINT_LAYER_ID = "ledger-location-points"
private const val HEATMAP_LAYER_ID = "ledger-location-heatmap"
private const val CLUSTER_COUNT_PROPERTY = "point_count"
private const val CLUSTER_ABBREVIATED_COUNT_PROPERTY = "point_count_abbreviated"
private const val POINT_RADIUS_PROPERTY = "point_radius"
private const val WEIGHT_PROPERTY = "weight"
