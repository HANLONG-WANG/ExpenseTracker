package com.yohaku.expensetracker.ui.features

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.EntryDraft
import com.yohaku.expensetracker.platform.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import kotlinx.coroutines.*

internal sealed interface LocationRead {
    data object Idle:LocationRead
    data object Locating:LocationRead
    data class Ready(val fix:LocationFix):LocationRead
    data class Failure(val reason:LocationFailure?,val detail:String?=null):LocationRead
}
internal class LocationRequest(private val source:CurrentLocationSource,private val scope:CoroutineScope) {
    var state by mutableStateOf<LocationRead>(LocationRead.Idle);private set
    private var job:Job?=null
    fun stop(){job?.cancel();job=null;state=LocationRead.Idle}
    fun denied(){stop();state=LocationRead.Failure(LocationFailure.PERMISSION)}
    fun request() {
        job?.cancel();state=LocationRead.Locating
        job=scope.launch {
            try {state=LocationRead.Ready(source.currentFix())}
            catch(e:CancellationException){throw e}
            catch(e:LocationException){state=LocationRead.Failure(e.reason)}
            catch(e:SecurityException){state=LocationRead.Failure(LocationFailure.PERMISSION)}
            catch(e:Exception){state=LocationRead.Failure(null,e.message)}
        }
    }
}
@Composable private fun rememberLocationRequest(vm:AppViewModel,identity:String?=null):LocationRequest {
    val context=LocalContext.current;val owner=LocalLifecycleOwner.current;val scope=rememberCoroutineScope()
    val source=remember(vm.locationSource,context){vm.locationSource?:ForegroundLocation(context)}
    val request=remember(source,scope,identity){LocationRequest(source,scope)}
    DisposableEffect(owner,request) {
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP)request.stop()}
        owner.lifecycle.addObserver(observer)
        onDispose{owner.lifecycle.removeObserver(observer);request.stop()}
    }
    return request
}
@Composable private fun rememberLocationAction(request:LocationRequest):()->Unit {
    val context=LocalContext.current
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){result->
        if(result.values.any{it})request.request()else request.denied()
    }
    return {
        if(context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED||context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)request.request()
        else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))
    }
}
@Composable private fun LocationStatus(state:LocationRead) {
    val context=LocalContext.current
    val text=when(state) {
        LocationRead.Idle->word("仅在此页面获取当前位置，不记录轨迹。","この画面でのみ現在地を取得。移動履歴は保存しません。")
        LocationRead.Locating->word("正在定位，仍可手动填写并保存。","現在地を取得中。手入力・保存もできます。")
        is LocationRead.Ready->word("定位精度约 ${state.fix.accuracyMeters.toInt()} 米，仅比较本账本保存地点。","位置精度は約${state.fix.accuracyMeters.toInt()}m。この帳簿の保存場所のみ照合。")
        is LocationRead.Failure->when(state.reason) {
            LocationFailure.PERMISSION->word("位置未授权，可继续手动输入。","位置は未許可。手入力で続けられます。")
            LocationFailure.SYSTEM_OFF->word("系统位置已关闭，可开启后重试或手动输入。","端末の位置情報はオフ。オンにして再試行、または手入力できます。")
            LocationFailure.NO_PROVIDER->word("当前没有可用的定位服务，可继续手动输入。","利用できる位置サービスがありません。手入力できます。")
            LocationFailure.TIMEOUT->word("定位超时，可重试或继续手动输入。","位置取得がタイムアウト。再試行・手入力できます。")
            LocationFailure.UNAVAILABLE->word("暂未获得有效位置，可重试或手动输入。","有効な位置を取得できません。再試行・手入力できます。")
            else->word("定位失败：","位置取得に失敗：")+state.detail.orEmpty()
        }
    }
    YText(text,11,Modifier.testTag("foreground-location-status"),color=LocalYohaku.current.muted)
    if(state is LocationRead.Failure&&state.reason in listOf(LocationFailure.SYSTEM_OFF,LocationFailure.PERMISSION)) {
        val systemOff=state.reason==LocationFailure.SYSTEM_OFF
        YLink(if(systemOff)word("打开系统位置设置","端末の位置情報設定を開く")else word("打开应用权限设置","アプリの権限設定を開く"),{
            context.startActivity(if(systemOff)Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:${context.packageName}")))
        })
    }
}

@Composable internal fun EntryLocation(s:BookSnapshot,d:EntryDraft,vm:AppViewModel,onChange:(EntryDraft)->Unit) {
    val books by vm.preferences.foregroundLocationBooks.collectAsStateWithLifecycle(initialValue=emptySet())
    val today by vm.today.collectAsStateWithLifecycle()
    val currentDate=d.date==today.toString()
    val enabled=s.book.id in books&&currentDate
    val request=rememberLocationRequest(vm,s.book.id+"/"+d.mediaSession);val locate=rememberLocationAction(request)
    LifecycleResumeEffect(enabled,d.mediaSession) {
        if(enabled&&d.id==null)request.request()
        onPauseOrDispose{request.stop()}
    }
    val read=request.state
    val candidates=remember(read,s.merchants,s.merchantLocations) {
        (read as? LocationRead.Ready)?.fix?.let{s.merchantCandidates(it.latitude,it.longitude,it.accuracyMeters)}.orEmpty()
    }
    LaunchedEffect(read,candidates,d.mediaSession,enabled,d.date) {
        if(enabled&&read is LocationRead.Ready)vm.offerLocatedMerchant(s.book.id,d.mediaSession,candidates)
    }
    if(!currentDate){YText(word("补录非今天的记录，不使用当前位置匹配商户","今日以外の記録では現在地でお店を照合しません"),11,Modifier.testTag("entry-location-historical"),color=LocalYohaku.current.muted);return}
    LocationCandidatesPanel(Modifier.testTag("entry-location")) {
        if(enabled) {
            LocationStatus(read)
            if(read is LocationRead.Ready) {
                if(candidates.isEmpty())YText(word("保存地点中没有候选，继续手动填写。","保存した場所に候補はありません。手入力で続けられます。"),11,Modifier.testTag("location-no-candidates"))
                else {
                    val auto=candidates.singleOrNull()?.trustworthy==true&&d.id==null&&!d.merchantManuallyEdited
                    YText(if(auto)word("唯一可信地点可自动带入；手填后保留你的输入。","確かな候補が1件なら自動入力。手入力した内容を優先します。")else word("请选择候选；已有商户不会被覆盖。","候補を選んでください。入力済みのお店は上書きしません。"),11)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(if(LocalYohaku.current.id=="magazine")15.dp else 5.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        candidates.forEach{candidate->LocatedMerchantChip(candidate.merchant.name,candidate.location.name+" · ≈${candidate.distanceMeters.toInt()}m",{
                            onChange(d.copy(merchant=candidate.merchant.name,merchantId=candidate.merchant.id,merchantManuallyEdited=true))
                        },Modifier.testTag("located-merchant-${candidate.merchant.id}"))}
                    }
                }
            }
            FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                YLink(word("重新定位","現在地を再取得"),locate,Modifier.testTag("entry-locate"))
                YLink(word("关闭自动定位","位置の自動取得をオフ"),{request.stop();vm.execute{vm.preferences.setForegroundLocation(s.book.id,false)}})
            }
        }else YLink(word("启用定位，匹配保存商户","位置情報で保存済みのお店を選ぶ"),{
            vm.execute(onSuccess=locate){vm.preferences.setForegroundLocation(s.book.id,true)}
        },Modifier.testTag("entry-enable-location"))
    }
}

@Composable internal fun MerchantLocationPreferences(s:BookSnapshot,vm:AppViewModel,onEntry:(String)->Unit) {
    val books by vm.preferences.foregroundLocationBooks.collectAsStateWithLifecycle(initialValue=emptySet())
    val request=rememberLocationRequest(vm);val locate=rememberLocationAction(request)
    LocationCandidatesPanel(Modifier.testTag("merchant-location-panel")) {
    YToggle(word("记账时根据当前位置匹配商户","記録時に現在地からお店を選ぶ"),s.book.id in books,{enabled->
        if(!enabled)request.stop()
        vm.execute(onSuccess={if(enabled)locate()}){vm.preferences.setForegroundLocation(s.book.id,enabled)}
    },Modifier.testTag("merchant-location-enabled"))
    LocationStatus(request.state)
    YLink(word("查看当前位置候选","現在地の候補を見る"),locate,Modifier.testTag("merchant-locate"))
    (request.state as? LocationRead.Ready)?.fix?.let{fix->
        val candidates=s.merchantCandidates(fix.latitude,fix.longitude,fix.accuracyMeters)
        if(candidates.isEmpty())YText(word("没有匹配的已保存地点","一致する保存場所はありません"),11)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(if(LocalYohaku.current.id=="magazine")15.dp else 5.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            candidates.forEach{candidate->LocatedMerchantChip(candidate.merchant.name,candidate.location.name,{onEntry(candidate.merchant.id)},Modifier.testTag("nearby-merchant-${candidate.merchant.id}"))}
        }
    }
    }
}

@Composable internal fun CapturePlaceLocation(vm:AppViewModel,onFix:(LocationFix)->Unit) {
    val request=rememberLocationRequest(vm);val locate=rememberLocationAction(request)
    val read=request.state
    LaunchedEffect(read){(read as? LocationRead.Ready)?.let{onFix(it.fix)}}
    YLink(word("使用当前位置","現在地を使う"),locate,Modifier.testTag("place-use-location"))
    LocationStatus(read)
}
