package com.yohaku.expensetracker.ui.features

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.currency.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.LocalDate

@Composable internal fun ReferenceRatePanel(service:ReferenceRates,from:Currency,to:Currency,date:String,
    enabled:Boolean=true,autoKey:String="",onAuto:((ReferenceRateSnapshot)->Unit)?=null,onUse:((ReferenceRateSnapshot)->Unit)?=null,useLabel:String?=null) {
    if(from==to)return
    val automatic by service.automatic.collectAsStateWithLifecycle(true)
    var result by remember(from,to,date){mutableStateOf<ReferenceRateResult?>(null)}
    var loading by remember(from,to,date){mutableStateOf(false)}
    var error by remember(from,to,date){mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope()
    suspend fun load(force:Boolean) {
        loading=true;error=null
        try{result=service.lookup(from,to,LocalDate.parse(date),force)}catch(e:IOException){error=e.message}
        finally{loading=false}
    }
    LaunchedEffect(from,to,date,automatic){load(false)}
    LaunchedEffect(result,autoKey,automatic){result?.takeIf{automatic&&it.automatic}?.rate?.let{onAuto?.invoke(it)}}
    val quote=result?.rate
    CurrencyEntryNote {
        YText(word("参考汇率 · 仅供估算","参考レート・見積もり専用"),13)
        if(quote!=null)ReferenceRateFacts(quote)
        else YText(if(loading)word("正在查询参考汇率…","参考レートを取得中…")else word("暂无可用参考汇率，可手填汇率或实际金额。","利用できる参考レートがありません。レート・実額を手入力できます。"),11)
        result?.let{r->
            if(r.previousDate)YText(word("所选日期暂无当日值，使用最近可用日期：","選択日の値がなく、直近の利用可能日：")+(r.rate?.date?:"—"),11,color=LocalYohaku.current.warm)
            if(!r.automatic)YText(word("自动获取已关闭；仍可主动刷新。","自動取得はオフ。手動で更新できます。"),11)
            r.failure?.takeIf{it!="AUTO_DISABLED"}?.let{YText(word("更新失败，保留最近缓存；请核对日期或手填。","更新できません。直近のキャッシュを保持します。日付を確認するか手入力してください。")+" · "+if(it.startsWith("HTTP_"))it.replace('_',' ')else if(it=="INVALID_RESPONSE")word("服务返回资料无效","サービスの応答が無効")else word("网络不可用","ネットワーク未接続"),11,color=LocalYohaku.current.warm)}
        }
        error?.let{YText(it,11,color=LocalYohaku.current.warm)}
        YButton(word("刷新参考汇率","参考レートを更新"),{scope.launch{load(true)}},Modifier.testTag("rate-refresh-${from.name}-${to.name}"),enabled=!loading)
        if(onUse!=null)YButton(useLabel?:word("按此参考值重新估算","この参考値で再見積もり"),{
            try{onUse(quote!!);error=null}catch(e:IllegalArgumentException){error=e.message}catch(e:IllegalStateException){error=e.message}catch(e:ArithmeticException){error=e.message}
        },Modifier.testTag("rate-use-${from.name}-${to.name}"),enabled=!loading&&quote!=null&&enabled)
        YText(word("日参考值不是成交价或平台结算规则。刷新不改已填金额、余额或历史；只有主动采用才重估已有输入。","日次参考値は約定価格・返済先の決済条件とは異なります。更新では入力額・残高・履歴を変更せず、明示的に適用した場合だけ既存入力を再見積もりします。"),10,color=LocalYohaku.current.muted)
    }
}
@Composable internal fun ReferenceRateFacts(rate:ReferenceRateSnapshot) {
    YText("1 ${rate.base.name} = ${rate.rate} ${rate.quote.name}",14,number=true)
    YText("Frankfurter v2 / ${rate.provider} · "+word("汇率日：","レート日：")+rate.date,11)
    // fetchedAt is stored as an ISO instant; show it in the device's local time.
    val fetched=java.time.Instant.parse(rate.fetchedAt).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    YText(word("获取时间：","取得日時：")+fetched,10,color=LocalYohaku.current.muted)
}
@Composable internal fun StoredReferenceRates(encoded:String?) {
    if(encoded==null)return
    val values=remember(encoded){Json.decodeFromString<List<ReferenceRateSnapshot>>(encoded)}
    values.forEach{rate->ReferenceRateFacts(rate)}
}
@Composable internal fun ReferenceRatesScreen(vm:AppViewModel) {
    val service=vm.referenceRates
    val automatic by service.automatic.collectAsStateWithLifecycle(true)
    val scope=rememberCoroutineScope()
    var from by rememberSaveable{mutableStateOf("JPY")};var to by rememberSaveable{mutableStateOf("CNY")}
    var date by rememberSaveable{mutableStateOf(LocalDate.now(service.clock).toString())};var owner by remember{mutableStateOf<Int?>(null)}
    YLead(word("换算，有据可查。","換算の根拠を、残そう。"),word("固定采用 Frankfurter v2 / ECB 日参考汇率；只发送币种和日期，不发送个人账目。","Frankfurter v2 / ECBの日次参考レートを使用。送信するのは通貨と日付のみで、個人の記録は送信しません。"),"REFERENCE RATES",pageFlow=true)
    YToggle(word("自动获取参考汇率","参考レートを自動取得"),automatic,{v->scope.launch{service.store.setAutomaticReferenceRates(v)}},Modifier.testTag("rates-automatic"))
    YSelect(word("从币种","元の通貨"),from,Currency.entries.map{it.name to it.name},{from=it;if(to==it)to=Currency.entries.first{c->c.name!=it}.name})
    YSelect(word("到币种","換算先の通貨"),to,Currency.entries.filter{it.name!=from}.map{it.name to it.name},{to=it})
    YDateFields(listOf(word("查询日期","照会日") to date),owner,{owner=it},{_,v->date=v})
    ReferenceRatePanel(service,Currency.valueOf(from),Currency.valueOf(to),date)
}
