package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import java.time.YearMonth

@Composable internal fun AnalysisBudgetView(s:BookSnapshot,filter:AnalysisFilter,onScope:(String)->Unit,onManage:(String)->Unit) {
    val first=YearMonth.parse(filter.start.take(7));val last=YearMonth.parse(filter.end.take(7))
    val months=generateSequence(first){it.plusMonths(1)}.takeWhile{it<=last}.map{it.toString()}.toList()
    var selected by rememberSaveable(s.book.id,filter.start,filter.end){mutableStateOf(months.first())}
    val p=s.budgetProjection(selected,filter.categoryId)
    val unknown=word("未设置 / 待补齐","未設定・未補足")
    fun money(value:Long?)=value?.let{Money(p.currency,it).display()}?:unknown
    YLead(word("日常与活动，分开留。","日常とシーン、分けて残す。"),word("月预算内支出与独立活动支出分开表达。","月予算の支出と独立シーン支出を区別。"),"THE MONTHLY BALANCE",money(p.remainingMinor),selected+" · "+(filter.categoryId?.let{s.path(it)}?:word("本月预算余额","今月の予算残額")),amountIsText=p.remainingMinor==null,pageFlow=true)
    if(months.size>1)YSelect(word("预算月份","予算の月"),selected,months.map{it to it},{selected=it},Modifier.testTag("analysis-budget-month"))else YText(selected,19,number=true)
    YText(word("预算按完整自然月展示，消费筛选不会改写月预算。","予算は暦月全体を表示。支出の絞込は月予算を変更しません。"),12,color=LocalYohaku.current.muted)
    val provisional=if(filter.categoryId==null)s.provisionalExpenseCount(selected)else 0
    Box(Modifier.padding(top=5.dp,bottom=if(provisional>0)5.dp else 20.dp)) {BudgetEquation {RecordFacts(listOf(word("月预算","月予算") to money(p.limitMinor),word("预算内已花","予算内支出") to ("− "+money(p.spentMinor)),word("预算内待付预留","予算内予定の確保") to ("− "+money(p.reserveMinor)),word("预留后还能花","確保後に使える") to money(p.availableMinor)))}}
    if(provisional>0)BudgetEstimateNote(provisional)
    Box(Modifier.padding(top=if(provisional>0)15.dp else 0.dp)) {YButton(word("调整月预算","月予算を変更"),{onManage(selected)},Modifier.testTag("analysis-budget-adjust"),primary=true)}
    IndependentBudgetNote(money(p.independentMinor))
    ThemeSection(word("月分类预算","月のカテゴリ予算"),"CATEGORY LIMITS",previousBottomPadding=8)
    YText(word("一级含二级与未细分消费，不重复相加。选择分类查看其预算；通过管理入口调整。","親には子と内訳未分類を含み、二重加算しません。カテゴリを選んで予算を確認。変更は管理から。"),11,color=LocalYohaku.current.muted,lineHeightMultiplier=1.9f)
    val roots=s.categories.filter{it.active&&it.kind==EntryKind.EXPENSE.name&&it.parentId==null}
    if(roots.isEmpty())YEmpty(word("先创建自己的支出分类。","自分の支出カテゴリから。"))
    val ordered=roots.flatMap{root->listOf(root)+s.categories.filter{it.active&&it.parentId==root.id}}
    val projections=ordered.associate{it.id to s.budgetProjection(selected,it.id)}
    BudgetLimitList(ordered,ordered.map{money(projections.getValue(it.id).remainingMinor)}) {c,modifier->
        val cp=projections.getValue(c.id)
        BudgetLimitRow(c.id,c.name,c.parentId!=null,cp.spentMinor,cp.limitMinor,money(cp.spentMinor),money(cp.limitMinor),money(cp.remainingMinor),{onScope(c.id)},modifier)
    }
    ThemeSection(word("预留来源明细","確保の内訳"),"PLANNED USES")
    RecordFacts(listOf(word("待付固定支出预留","固定費の未払い予定") to money(p.fixedReserveMinor),word("本月活动预算预留","今月のシーン予算の確保") to money(p.activityReserveMinor),word("全部活动资金预留","全シーンの資金確保") to money(p.fundReserveMinor)),audit=true)
    YButton(word("管理月预算","月予算を管理"),{onManage(selected)},Modifier.testTag("analysis-budget-manage"),primary=true)
}

@Composable internal fun AnalysisFutureView(s:BookSnapshot,filter:AnalysisFilter,onRecurring:()->Unit,onActivity:(String)->Unit,onTransfer:(EventEntity)->Unit) {
    val currency=Currency.valueOf(s.book.currency)
    YLead(word("提前一点，看见安排。","少し先の、予定を見よう。"),word("已保存固定安排、活动资金与在途转账。预留不是已花。","保存済みの定期予定・シーン資金・送金中の振替。確保額は支出済みではありません。"),"A LITTLE AHEAD",pageFlow=true)
    YText(word("已保存的固定安排；不自动生成支出。","保存済みの定期予定。支出は自動生成しません。"),13)
    ThemeSection(word("实际扣款账户的时间线","引落口座ごとの予定"),"ACCOUNT BY ACCOUNT")
    val pending=s.pendingOccurrences().filter{it.scheduledDate in filter.start..filter.end&&(filter.categoryId==null||it.categoryId==filter.categoryId||s.categories.any{c->c.id==it.categoryId&&c.parentId==filter.categoryId})&&(filter.activityId==null||it.activityId.orEmpty()==filter.activityId)}.sortedWith(compareBy<OccurrenceEntity>{it.scheduledDate}.thenBy{it.id})
    if(pending.isEmpty())YEmpty(word("这个期间没有待付固定安排。","この期間の未払い予定はありません。"))
    if(filter.merchantKey!=null)YText(word("固定安排没有商户归属，商户筛选不适用于这份时间线。","定期予定にお店の帰属はなく、お店の絞込はこの予定表に適用されません。"),11,color=LocalYohaku.current.muted)
    pending.forEach{o->
        val account=s.accounts.first{it.id==o.accountId}
        FutureTimelineRow(o.id,o.scheduledDate,o.name,account.name+" · "+o.currency,o.amountMinor?.let{"− "+Money(Currency.valueOf(o.currency),it).display()}?:word("金额待补齐","金額未入力"),
            (if(o.estimated)word("预计 · ","見込み・")else word("金额已知 · ","金額確定・"))+(o.baseMinor?.let{word("本位币：","基準通貨：")+Money(currency,it).display()}?:word("本位币折算待补齐","基準通貨の換算未補足")))
    }
    YText(word("金额或折算尚未确定的安排，请补齐后再确认付款。","金額・換算が未確定の予定は、補足してから支払を確認してください。"),11,color=LocalYohaku.current.muted)
    YButton(word("管理周期账单","定期支払を管理"),onRecurring)
    ThemeSection(word("活动资金与未完成转账","シーン資金と未完了の振替"),"RESERVED / IN TRANSIT")
    YText(word("这里列出账本全部仍有效的资金安排，日期注明各自计划时间。","帳簿の有効な資金予定をすべて表示。日付はそれぞれの予定日です。"),11,color=LocalYohaku.current.muted)
    val reserves=s.reserves.filter{it.active&&s.reserveRemaining(it)>0}
    reserves.forEach{r->
        FutureFundCard("reserve-${r.id}",s.activities.first{it.id==r.activityId}.name+" · "+r.name,Money(currency,s.reserveRemaining(r)).display(),r.date+" · "+word("活动资金预留，不是已花。","シーンの資金確保。支出済みではありません。"))
        YLink(word("查看活动安排","シーンの予定を見る"),{onActivity(r.activityId)},Modifier.testTag("future-reserve-open-${r.id}"))
    }
    val transfers=s.transfers.filter{!(it.fromConfirmed&&it.toConfirmed)&&s.visibleEvents.any{e->e.id==it.eventId}}
    transfers.forEach{transfer->
        val event=s.visibleEvents.first{it.id==transfer.eventId}
        val from=s.accounts.first{it.id==event.accountId};val to=s.accounts.first{it.id==transfer.toAccountId}
        val state=when{transfer.fromConfirmed->word("在途","送金中");transfer.toConfirmed->word("转出待确认","出金確認待ち");else->word("计划","予定")}
        FutureFundCard("transfer-${event.id}",event.merchant.ifBlank{from.name+" → "+to.name},state,from.name+" · "+Money(Currency.valueOf(transfer.fromCurrency),event.amountMinor).display()+" → "+to.name+" · "+Money(Currency.valueOf(transfer.toCurrency),transfer.toMinor).display()+"\n"+transfer.fromDate+" → "+transfer.toDate)
        YLink(word("查看转账状态","振替の状況を見る"),{onTransfer(event)},Modifier.testTag("future-transfer-open-${event.id}"))
    }
    if(reserves.isEmpty()&&transfers.isEmpty())YEmpty(word("没有活动资金预留或未完成转账。","シーンの資金確保・未完了の振替はありません。"))
}
