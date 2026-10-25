package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import java.time.*

@Composable internal fun outlookAmount(currency:String,value:Long?,estimated:Boolean=false)=value?.let{(if(estimated)"≈ "else"")+Money(Currency.valueOf(currency),it).display()}?:word("未知","不明")

@Composable internal fun attentionLabel(reason:String)=when(reason) {
    "UNPLANNED_DEBT"->word("消费 / 借入或期初本金尚无完整付款安排","利用・借入・期首元金の支払予定が未補完")
    "UNALLOCATED_CLEARING"->word("实际清偿尚未分配到具体义务","実際の返済が個別債務に未充当")
    "CLEARING_AFTER_DUE"->word("记录的清偿日晚于账单到期日，请核对付款时间","記録した返済日が請求期日より後です。支払時期を確認")
    else->cashIssue(reason)
}

@Composable private fun Modifier.riskFrame():Modifier {
    val t=LocalYohaku.current
    return then(when(t.id) {
        "magazine"->Modifier.background(Color(0xfff8f2e7)).border(1.dp,t.warm).drawBehind{drawLine(t.warm,Offset.Zero,Offset(0f,size.height),3.dp.toPx())}.padding(13.dp,17.dp)
        "journal"->Modifier.rotate(-.3f).background(Color(0xfffff4d6)).drawBehind{drawRect(Color(0xffc6b691),style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))))}.padding(13.dp,17.dp)
        else->Modifier.shadow(2.dp,RoundedCornerShape(16.dp)).background(Color(0xfff4e0c2),RoundedCornerShape(16.dp)).padding(14.dp,18.dp)
    })
}

@Composable internal fun CashRiskCards(ready:FinancialOutlookRead.Ready,onRisk:(String)->Unit,limit:Int=Int.MAX_VALUE) {
    val risks=ready.risks.filter{it.currentGapMinor?.let{n->n>0}==true||it.firstRiskDate!=null||it.incompleteDate!=null}
    risks.take(limit).forEach{risk->
        val a=risk.opening.account
        Column(Modifier.fillMaxWidth().testTag("cash-risk-${a.id}").riskFrame(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            YText(a.name+" · "+a.currency,if(LocalYohaku.current.id=="journal")16 else 14)
            YText(when{risk.incompleteDate!=null->word("资金资料待补齐","資金情報を要補完");risk.currentGapMinor?.let{it>0}==true->word("当前备款尚不足","現在の準備資金が不足");else->word("未来安排存在缺口","将来の予定で不足")},13)
            RecordFacts(listOf(word("当前备款缺口","現在の準備不足") to outlookAmount(a.currency,risk.currentGapMinor),word("基础最大缺口","基本・最大不足") to outlookAmount(a.currency,risk.maximumGapMinor,risk.estimated),word("含预计收入情景","収入見込みあり") to outlookAmount(a.currency,risk.includingIncomeGapMinor,risk.includingEstimated)),framed=true)
            risk.firstRiskDate?.let{YText(word("基础首次不足：","基本シナリオの初回不足：")+it,11)}
            risk.incompleteDate?.let{YText(word("从此日后无法确定完整余额：","この日以降の残高は未確定：")+it,11)}
            YButton(word("核对并处理","確認・対応する"),{onRisk(a.id)},Modifier.testTag("cash-risk-open-${a.id}"),inline=true)
        }
    }
    if(risks.isEmpty())YText(word("在上述范围的已知安排中，未见扣款账户缺口。未安排及资料不足的部分另列。","上記範囲の既知予定では引落口座の不足は見られません。未設定・未補完分は別に表示。"),11,color=LocalYohaku.current.muted)
}

@Composable internal fun FinancialAttentionList(s:BookSnapshot,ready:FinancialOutlookRead.Ready,onAttention:(FinancialAttention)->Unit,limit:Int=Int.MAX_VALUE) {
    if(ready.attention.isEmpty())return
    ThemeSection(word("需要先核对的资料","先に確認する情報"),"TO CHECK",afterFrame=true)
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(if(LocalYohaku.current.id=="objects")8.dp else 0.dp)) {
        ready.attention.take(limit).forEach{item->
            AttentionRow(s.accounts.firstOrNull{it.id==item.accountId}?.name.orEmpty(),attentionLabel(item.reason),{onAttention(item)},Modifier.testTag("outlook-attention-${item.id}"),last=item===ready.attention.take(limit).last())
        }
    }
}

/** One full-width row per item keeps a stack of checks aligned instead of ragged outline buttons. */
@Composable private fun AttentionRow(account:String,reason:String,onClick:()->Unit,modifier:Modifier=Modifier,last:Boolean=false) {
    val t=LocalYohaku.current;val journal=t.id=="journal"
    val frame=if(t.id=="objects")Modifier.background(t.surface,RoundedCornerShape(13.dp)).border(1.dp,Color(0xffead6b8),RoundedCornerShape(13.dp)).padding(horizontal=14.dp,vertical=12.dp)
        else if(last&&!journal)Modifier.padding(vertical=12.dp)
        else Modifier.drawBehind{drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=if(journal)PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))else null)}.padding(vertical=12.dp)
    Row(modifier.fillMaxWidth().clickable(role=Role.Button,onClick=onClick).then(frame),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(t.warm,androidx.compose.foundation.shape.CircleShape))
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            if(account.isNotEmpty())YText(account,if(journal)11 else 9,color=t.muted)
            YText(reason,if(journal)14 else 12)
        }
        YText("→",12,color=t.muted)
    }
}

@Composable internal fun ScheduledCashRow(item:CashTimelineItem,s:BookSnapshot,onClick:()->Unit,last:Boolean=false) {
    val t=LocalYohaku.current;val journal=t.id=="journal";val objects=t.id=="objects"
    val narrow=with(LocalDensity.current){LocalWindowInfo.current.containerSize.width.toDp()}<=360.dp||LocalDensity.current.fontScale>1.1f
    val frame=when(t.id) {
        "magazine"->Modifier.drawBehind{if(!last)drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}.padding(vertical=17.dp)
        "journal"->Modifier.drawBehind{drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())))}.padding(vertical=17.dp)
        else->Modifier.shadow(2.dp,RoundedCornerShape(14.dp)).background(Color(0xfffff8e8),RoundedCornerShape(14.dp)).border(1.dp,Color(0xffe3cda8),RoundedCornerShape(14.dp)).padding(11.dp,20.dp)
    }
    val amount=if(item.amountMinor==null)word("金额未知","金額不明")else (if(item.incoming)"+"else"−")+outlookAmount(item.currency,item.amountMinor,item.referenceRate!=null)
    val account=s.accounts.firstOrNull{it.id==item.accountId}
    Column(Modifier.fillMaxWidth().testTag("outlook-item-${item.id}").clickable(role=Role.Button,onClick=onClick).then(frame),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(if(narrow)8.dp else 11.dp)) {
            YText(item.scheduledDate.takeLast(5).replace('-','/'),if(objects)12 else 16,Modifier.then(if(objects)Modifier.background(Color(0xffeee2cb),RoundedCornerShape(7.dp)).padding(5.dp,8.dp)else Modifier).width(if(objects)50.dp else if(narrow)42.dp else 48.dp),number=true,color=t.muted)
            Column(Modifier.weight(1f)) {
                val title=if(item.name in EntryKind.entries.map{it.name})entryKindName(EntryKind.valueOf(item.name))else item.name
                YText(title,if(narrow)11 else if(journal)15 else 12)
                YText((account?.name?:word("扣款账户未确定","引落口座未定"))+" · "+when{item.kind=="EXPECTED_INCOME"->word("预计收入 · 未到账","収入予定・未入金");item.kind=="RESERVE"->word("预留 · 非已花","資金確保・未支出");item.estimated->word("预计 / 待确认","見込み・確認待ち");else->word("已确认安排","確定した予定")},if(journal)11 else 9,color=t.muted)
                if(narrow)YText(amount,if(item.amountMinor==null)12 else 16,number=item.amountMinor!=null,color=if(item.amountMinor==null)t.warm else t.ink)
            }
            if(!narrow)YText(amount,if(item.amountMinor==null)13 else if(t.id=="magazine")19 else 17,number=item.amountMinor!=null,color=if(item.amountMinor==null)t.warm else t.ink)
        }
        if(item.scheduledDate<item.date)YText(word("原日期已过，请核对实际执行。","元の日付は経過しています。実際の処理を確認。"),11,color=t.warm)
        item.issue?.let{YText(cashIssue(it),11,color=t.warm)}
    }
}

@Composable internal fun HomeFinancialOutlook(s:BookSnapshot,vm:AppViewModel,onCalendar:()->Unit,onForecast:()->Unit,onRisk:(String)->Unit,onItem:(CashTimelineItem)->Unit,onAttention:(FinancialAttention)->Unit,onImportant:()->Unit) {
    val today by vm.today.collectAsStateWithLifecycle();var attempt by remember{mutableIntStateOf(0)}
    val read by rememberFinancialOutlook(s,vm,today.plusDays(90),attempt)
    ThemeSection(word("重要事项","大事な予定"),"IMPORTANT",word("全部事项","すべて"),onImportant,pageGap=15,linkTag="home-important",afterFrame=true)
    when(val value=read) {
        FinancialOutlookRead.Loading->YText(word("正在读取近期安排…","直近の予定を読込中…"),11)
        is FinancialOutlookRead.Failure->{YText(value.message,12);YButton(word("重新读取","再読込"),{attempt++})}
        is FinancialOutlookRead.Ready->{
            OutlookRange(value.timeline.asOf,value.timeline.through)
            CashRiskCards(value,onRisk,1)
            val pace=value.pace
            if(pace.actualOverMinor?.let{it>0}==true||pace.projectedOverMinor?.let{it>0}==true)YLink(word("预算已超出或按当前节奏预计超出，查看原因","予算超過・現在のペースで超過見込み。理由を見る"),onForecast,Modifier.testTag("home-budget-risk"))
            FinancialAttentionList(s,value,onAttention,3)
            ThemeSection(word("接下来的付款","これからのお支払い"),"UP NEXT",word("日历","カレンダー"),onCalendar,pageGap=15)
            val items=value.timeline.steps.filter{!it.item.incoming&&it.item.accountDeltaMinor!=0L}.take(3)
            if(items.isEmpty())YEmpty(word("还没有已知付款安排","既知の支払予定はありません"))
            items.forEach{ScheduledCashRow(it.item,s,{onItem(it.item)},it===items.last())}
            YLink(word("查看未来资金与预算风险","将来の資金・予算リスクを見る"),onForecast,Modifier.testTag("home-open-forecast"),arrow=true)
        }
    }
}

@Composable internal fun PaymentCalendarScreen(s:BookSnapshot,vm:AppViewModel,onItem:(CashTimelineItem)->Unit,onForecast:()->Unit) {
    val today by vm.today.collectAsStateWithLifecycle();val current=YearMonth.from(today)
    var monthText by rememberSaveable(s.book.id){mutableStateOf(current.toString())}
    val month=maxOf(YearMonth.parse(monthText),current)
    var attempt by remember{mutableIntStateOf(0)}
    val read by rememberFinancialOutlook(s,vm,month.atEndOfMonth(),attempt)
    YLead(word("接下来的每一天。","これからの支払日。"),word("保留原币与原日期，点开处理对应安排。","元通貨・元の日付を保持。予定を開いて対応。"),"PAYMENT CALENDAR",pageFlow=true)
    MonthChooser(month.toString(),{monthText=it},"payment-calendar-prev","payment-calendar-next","payment-calendar-month",previousEnabled=month>current)
    YText(word("包括未完成付款、周期、主动预留、在途与预计收入；预计收入不表示已到账。过期未完成项保留原日期列于本月。","未完了の支払・定期予定・資金確保・振替・収入予定を含みます。収入予定は未入金。期限経過の未完了分は元の日付のまま今月に表示。"),11)
    when(val value=read) {
        FinancialOutlookRead.Loading->YText(word("正在读取…","読込中…"),12)
        is FinancialOutlookRead.Failure->{YText(value.message,12);YButton(word("重新读取","再読込"),{attempt++})}
        is FinancialOutlookRead.Ready->{
            val rows=value.timeline.steps.filter{it.item.date.take(7)==month.toString()&&it.item.accountDeltaMinor!=0L}
            if(rows.isEmpty())YEmpty(word("本月没有已知安排","今月の既知の予定はありません"))
            rows.forEach{ScheduledCashRow(it.item,s,{onItem(it.item)},it===rows.last())}
        }
    }
    YButton(word("检查未来资金缺口","将来の不足を確認"),onForecast,Modifier.testTag("calendar-open-forecast"))
}

@Composable internal fun FinancialRiskActions(s:BookSnapshot,vm:AppViewModel,accountId:String,onClose:()->Unit,onItem:(CashTimelineItem)->Unit,onForecast:()->Unit,onIncome:(String)->Unit,onTransfer:(String,String)->Unit,onActualIncome:(String)->Unit) {
    YSheet(word("核对资金安排","資金予定を確認"),onClose) {
        val today by vm.today.collectAsStateWithLifecycle();var attempt by remember{mutableIntStateOf(0)}
        val read by rememberFinancialOutlook(s,vm,today.plusDays(90),attempt)
        when(val value=read) {
            FinancialOutlookRead.Loading->YText(word("正在读取…","読込中…"),12)
            is FinancialOutlookRead.Failure->{YText(value.message,12);YButton(word("重新读取","再読込"),{attempt++})}
            is FinancialOutlookRead.Ready->{
                val risk=value.risks.firstOrNull{it.opening.account.id==accountId}
                if(risk==null){YText(word("账户资料已变化，请返回重新核对。","口座情報が変わりました。戻って確認してください。"),12);return@YSheet}
                val a=risk.opening.account
                YText(a.name+" · "+a.currency,18)
                RecordFacts(listOf(word("今日实际资金","本日の実資金") to outlookAmount(a.currency,risk.opening.actualMinor),word("下次同日需备款","次回同日に必要な資金") to outlookAmount(a.currency,risk.nextNeedMinor),word("当前备款缺口","現在の準備不足") to outlookAmount(a.currency,risk.currentGapMinor)))
                YText(value.timeline.asOf+" → "+value.timeline.through,12)
                YText(word("其他账户资金需实际转入；预计收入仅作情景。同日没有顺序时先扣后入，预留不等于已花。","他口座からは実際の振替が必要。収入予定は別シナリオ。同日の順序が不明なら出金を先に計算し、資金確保は支出済みではありません。"),11)
                val other=s.accounts.filter{it.active&&!AccountType.valueOf(it.type).debt&&it.id!=a.id}.sortedByDescending{it.currency==a.currency}.firstOrNull()
                if(other!=null)YButton(word("记录转入 / 换汇","振替・両替を記録"),{onTransfer(other.id,a.id)},Modifier.testTag("risk-transfer-${a.id}"),primary=true)
                YButton(word("补记已实际到账收入","実入金の記録を補完"),{onActualIncome(a.id)},Modifier.testTag("risk-actual-income-${a.id}"))
                YButton(word("添加预计收入情景","収入予定のシナリオを追加"),{onIncome(a.id)},Modifier.testTag("risk-expected-income-${a.id}"))
                value.timeline.steps.filter{it.item.accountId==a.id&&it.item.accountDeltaMinor!=0L}.let{steps->steps.forEach{ScheduledCashRow(it.item,s,{onItem(it.item)},it===steps.last())}}
                YButton(word("查看完整资金时间线","資金予定の全体を見る"),onForecast)
            }
        }
    }
}

@Composable internal fun FinancialForecastScreen(s:BookSnapshot,vm:AppViewModel,onRecord:(EventEntity)->Unit,onFinancial:(String)->Unit,onRisk:(String)->Unit,onAttention:(FinancialAttention)->Unit,onItem:(CashTimelineItem)->Unit) {
    val today by vm.today.collectAsStateWithLifecycle()
    var through by rememberSaveable(s.book.id){mutableStateOf(today.plusDays(90).toString())}
    var owner by remember{mutableStateOf<Int?>(null)}
    var attempt by remember{mutableIntStateOf(0)}
    val read by rememberFinancialOutlook(s,vm,LocalDate.parse(through),attempt)
    AnalysisFutureView(s,AnalysisFilter(today.toString(),through),vm,{vm.navigate("recurring")},{vm.openActivity.value=it;vm.navigate("life")},onRecord,onFinancial,read,onItem,{attempt++}) {
        YDateFields(listOf(word("预测截至日期","予測終了日") to through),owner,{owner=it},{_,v->through=maxOf(v,today.toString())})
        if(read is FinancialOutlookRead.Ready) {
            val value=read as FinancialOutlookRead.Ready
            ThemeSection(word("当前与未来账户风险","現在・将来の口座リスク"),"CASH RISKS")
            CashRiskCards(value,onRisk);FinancialAttentionList(s,value,onAttention)
        }
    }
}

@Composable internal fun AccountsFinancialOutlook(s:BookSnapshot,vm:AppViewModel,onTransfer:()->Unit,onCalendar:()->Unit,onForecast:()->Unit,onRisk:(String)->Unit,onAttention:(FinancialAttention)->Unit,onImportant:()->Unit) {
    val today by vm.today.collectAsStateWithLifecycle()
    var attempt by remember{mutableIntStateOf(0)}
    val read by rememberFinancialOutlook(s,vm,today.plusDays(90),attempt)
    // Each action uses the active theme's own button treatment and can wrap at large font sizes.
    FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        YButton(word("转账 / 换汇","振替・両替"),onTransfer,Modifier.testTag("accounts-transfer"))
        YButton(word("付款日历","支払カレンダー"),onCalendar,Modifier.testTag("accounts-calendar"))
        YButton(word("重要事项","大事な予定"),onImportant,Modifier.testTag("accounts-important"))
        YButton(word("未来资金与预算风险","将来の資金・予算リスク"),onForecast,Modifier.testTag("accounts-forecast"))
    }
    OutlookRange(today.toString(),today.plusDays(90).toString())
    when(val value=read) {
        FinancialOutlookRead.Loading->YText(word("正在核对账户安排…","口座の予定を確認中…"),11)
        is FinancialOutlookRead.Failure->{YText(value.message,12);YButton(word("重新读取","再読込"),{attempt++})}
        is FinancialOutlookRead.Ready->{CashRiskCards(value,onRisk,2);FinancialAttentionList(s,value,onAttention,3)}
    }
}

/** Labels the forecast window instead of showing a bare date pair. */
@Composable internal fun OutlookRange(from:String,through:String) {
    YText(word("未来90天内的已知安排 · ","今後90日の既知の予定・")+from.drop(5).replace('-','/')+" → "+through.replace('-','/'),11,Modifier.testTag("outlook-range"),color=LocalYohaku.current.muted)
}
