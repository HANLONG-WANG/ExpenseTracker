package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

// Read-only semantics let device tests verify the selected center without waiting for map tiles.
internal val PlacePickerCoordinates=SemanticsPropertyKey<Pair<Double,Double>>("PlacePickerCoordinates")

@Composable internal fun PlacePicker(vm:AppViewModel,latitude:Double?,longitude:Double?,radius:String,onRadius:(String)->Unit,onCenter:(Double,Double)->Unit) {
    val context=LocalContext.current;val owner=LocalLifecycleOwner.current;val theme=LocalYohaku.current
    val initial=remember{latitude?.let{LatLng(it,longitude!!)}}
    // The picker lives in a scrolling sheet: claim the whole gesture so panning moves the map, not the sheet.
    val mapView=remember{MapView(context).apply{onCreate(null);setOnTouchListener{v,_->v.parent?.requestDisallowInterceptTouchEvent(true);false}}}
    var map by remember{mutableStateOf<MapLibreMap?>(null)}
    var failed by remember{mutableStateOf(false)}
    var center by remember{mutableStateOf(initial?:LatLng(36.2,138.25))}
    var metersPerPixel by remember{mutableDoubleStateOf(1.0)}
    val centerChanged by rememberUpdatedState(onCenter)
    var requestedCenter by remember{mutableStateOf<LatLng?>(null)}
    DisposableEffect(mapView,owner) {
        var started=false;var resumed=false
        fun sync(state:Lifecycle.State) {
            if(resumed&&!state.isAtLeast(Lifecycle.State.RESUMED)){mapView.onPause();resumed=false}
            if(started&&!state.isAtLeast(Lifecycle.State.STARTED)){mapView.onStop();started=false}
            if(!started&&state.isAtLeast(Lifecycle.State.STARTED)){mapView.onStart();started=true}
            if(!resumed&&state.isAtLeast(Lifecycle.State.RESUMED)){mapView.onResume();resumed=true}
        }
        val observer=LifecycleEventObserver{_,_->sync(owner.lifecycle.currentState)}
        owner.lifecycle.addObserver(observer);sync(owner.lifecycle.currentState)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            sync(Lifecycle.State.DESTROYED);mapView.onDestroy()
        }
    }
    DisposableEffect(mapView) {
        val failure=MapView.OnDidFailLoadingMapListener{failed=true}
        val success=MapView.OnDidFinishLoadingMapListener{failed=false}
        mapView.addOnDidFailLoadingMapListener(failure);mapView.addOnDidFinishLoadingMapListener(success)
        var disposed=false
        mapView.getMapAsync{ready->
            if(!disposed) {
                map=ready
                ready.uiSettings.isRotateGesturesEnabled=false
                ready.uiSettings.isTiltGesturesEnabled=false
                ready.uiSettings.isLogoEnabled=false
                fun update(){
                    ready.cameraPosition.target?.let{center=it;metersPerPixel=ready.projection.getMetersPerPixelAtLatitude(it.latitude)}
                }
                ready.addOnCameraMoveListener{update()}
                ready.addOnCameraIdleListener{update();centerChanged(center.latitude,center.longitude)}
                val target=requestedCenter?:initial
                ready.moveCamera(CameraUpdateFactory.newLatLngZoom(target?:center,if(target==null)4.5 else 17.0))
                update();centerChanged(center.latitude,center.longitude)
            }
        }
        onDispose {
            disposed=true
            mapView.removeOnDidFailLoadingMapListener(failure);mapView.removeOnDidFinishLoadingMapListener(success)
        }
    }
    LaunchedEffect(map,theme.id){map?.setStyle(Style.Builder().fromUri(PLACE_MAP_STYLE)){it.applyPlaceTheme(theme.id)}}
    PlaceMapFrame(Modifier.testTag("place-picker-map")) {
        AndroidView(factory={mapView},modifier=Modifier.fillMaxSize())
        PlaceRadiusOverlay(((radius.toIntOrNull()?:100).coerceAtLeast(0)/metersPerPixel).toFloat())
        PlaceCenterPin(Modifier.align(Alignment.Center).testTag("place-picker-center").semantics{this[PlacePickerCoordinates]=center.latitude to center.longitude})
        if(failed)YText(word("地图加载失败，请检查网络后重试","地図を読み込めません。通信環境を確認して再試行してください"),11,
            Modifier.align(Alignment.TopCenter).padding(top=10.dp).background(theme.surface).padding(horizontal=10.dp,vertical=6.dp),color=theme.muted)
    }
    PlaceCoordinates(center.latitude,center.longitude,radius)
    CapturePlaceLocation(vm,autoLocate=initial==null){fix->
        val target=LatLng(fix.latitude,fix.longitude);requestedCenter=target;center=target
        centerChanged(fix.latitude,fix.longitude)
        map?.moveCamera(CameraUpdateFactory.newLatLngZoom(target,17.0))
    }
    YField(word("匹配半径 / 米","選択範囲・メートル"),radius,onRadius,numeric=true)
}
