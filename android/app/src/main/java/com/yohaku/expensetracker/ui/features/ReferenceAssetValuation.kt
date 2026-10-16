package com.yohaku.expensetracker.ui.features

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.currency.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable internal fun ReferenceAssetValuation(s:BookSnapshot,service:ReferenceRates,asOf:LocalDate=LocalDate.now(service.clock)) {
    val base=Currency.valueOf(s.book.currency)
    val currencies=s.accounts.map{Currency.valueOf(it.currency)}.distinct().filter{it!=base}
    if(currencies.isEmpty())return
    val automatic by service.automatic.collectAsStateWithLifecycle(true)
    var results by remember(s.book.id,base,currencies){mutableStateOf<Map<Currency,ReferenceRateResult>>(emptyMap())}
    var loading by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope();val today=LocalDate.now(service.clock)
    suspend fun load(force:Boolean) {
        loading=true;error=null
        try{for(c in currencies)results=results+(c to service.lookup(c,base,today,force))}
        catch(e:java.io.IOException){error=e.message}
        finally{loading=false}
    }
    LaunchedEffect(s.book.id,base,currencies,today,automatic){load(false)}
    ThemeAuditPanel(word("本位币参考估值","基準通貨での参考評価")) {
        fun total(debt:Boolean):Long? {
            var sum=0L
            for(a in s.accounts.filter{AccountType.valueOf(it.type).debt==debt}) {
                val amount=Money(Currency.valueOf(a.currency),if(debt)s.balance(a)else s.cashBalanceAt(a,asOf.toString()))
                val converted=if(amount.currency==base)amount else results[amount.currency]?.rate?.convert(amount)?:return null
                sum=Math.addExact(sum,converted.minor)
            }
            return sum
        }
        val unknown=word("汇率资料不足","レート情報不足")
        fun display(n:Long?)=n?.let{"≈ "+Money(base,it).display()}?:unknown
        RecordFacts(listOf(word("资金估值 · 不含额度","資金評価・枠除外") to display(total(false)),word("欠款本金估值","借入元金評価") to display(total(true))))
        results.values.mapNotNull{it.rate}.forEach{ReferenceRateFacts(it)}
        if(results.values.any{it.failure!=null&&it.failure!="AUTO_DISABLED"})YText(word("更新失败，以上使用最后可用缓存，请核对日期。","更新できないため直近のキャッシュを使用。日付を確認してください。"),11,color=LocalYohaku.current.warm)
        error?.let{YText(it,11,color=LocalYohaku.current.warm)}
        YButton(word("刷新估值汇率","評価用レートを更新"),{scope.launch{load(true)}},enabled=!loading)
        YText(word("仅将已记原币余额折算为参考值，不更改任何余额、交易汇率或消费历史，也不把其他账户资金自动转入扣款账户。","記録済みの元通貨残高を参考換算するだけで、残高・取引レート・支出履歴は変更しません。他口座の資金を引落口座へ自動移動しません。"),11)
    }
}
