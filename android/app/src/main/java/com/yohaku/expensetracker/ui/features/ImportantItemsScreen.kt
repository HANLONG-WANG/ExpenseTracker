package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.Currency
import com.yohaku.expensetracker.domain.model.Money
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun ImportantItemsScreen(s:BookSnapshot,vm:AppViewModel,onItem:(CashTimelineItem)->Unit,
    onDeadline:(String,String)->Unit,onRisk:(String)->Unit,onAttention:(FinancialAttention)->Unit,onForecast:()->Unit) {
    val today by vm.today.collectAsStateWithLifecycle()
    var attempt by remember{mutableIntStateOf(0)}
    val read by rememberFinancialOutlook(s,vm,today.plusDays(90),attempt)
    YLead(word("重要的事，放在眼前。","大事なことを、目の前に。"),word("本账本的未完成事项与风险，保留原币，打开对应安排处理。","この帳簿の未完了予定・リスクを元通貨で表示。予定を開いて対応できます。"),"A FEW IMPORTANT THINGS",pageFlow=true)
    when(val value=read) {
        FinancialOutlookRead.Loading->YText(word("正在核对重要事项…","大事な予定を確認中…"),12)
        is FinancialOutlookRead.Failure->{YText(value.message,12);YButton(word("重新读取","再読込"),{attempt++})}
        is FinancialOutlookRead.Ready->{
            YText(value.timeline.asOf+" → "+value.timeline.through,11)
            val payments=value.payments.filter{it.item.kind=="BILL"}
            val recurring=value.payments.filter{it.item.kind=="RECURRENCE"}
            val risks=value.risks.filter{it.currentGapMinor?.let{n->n>0}==true||it.firstRiskDate!=null||it.incompleteDate!=null}
            val pace=value.pace
            val budgetRisk=pace.actualOverMinor?.let{it>0}==true||pace.projectedOverMinor?.let{it>0}==true||pace.limitMinor!=null&&pace.projectedMinor==null
            if(payments.isEmpty()&&recurring.isEmpty()&&value.deadlines.isEmpty()&&risks.isEmpty()&&!budgetRisk&&value.attention.isEmpty())
                Box(Modifier.testTag("important-empty")){YEmpty(word("目前没有未完成事项","未完了の予定はありません"))}
            @Composable fun paymentRows(rows:List<ImportantPayment>) {
                rows.forEach{payment->
                    val item=payment.item
                    val bank=s.accounts.firstOrNull{it.id==item.accountId}?.name?:word("扣款账户未定","引落口座未定")
                    val obligation=outlookAmount(item.currency,payment.outstandingMinor,item.kind=="BILL"&&!payment.confirmed)
                    val cash=outlookAmount(item.currency,item.amountMinor)
                    val name=if(item.kind=="BILL")(s.accounts.first{it.id==item.sourceId}.name+" · "+item.name)else item.name
                    ImportantEvent(item.scheduledDate+" · "+name,bank+" · "+item.currency,
                        obligation,if(item.kind=="BILL")(if(payment.confirmed)word("已确认待清偿 / 仍需备款：","確定未返済額・今後必要な資金：")else word("本地预测待付 / 仍需备款：","ローカル未払予測・今後必要な資金："))+obligation+" / "+cash
                        else if(item.estimated)word("预计金额，确认实际执行后解除。","見込み額です。実行確認後に解消します。")else word("尚未确认实际执行。","実際の処理は未確認です。"),
                        "important-payment-${item.id}",{onItem(item)},warning=item.issue!=null||item.scheduledDate<value.timeline.asOf)
                    item.issue?.let{YText(cashIssue(it),11,color=LocalYohaku.current.warm)}
                }
            }
            if(payments.isNotEmpty()) {ThemeSection(word("待还款与待确认清偿","支払・返済確認待ち"),"PAYMENTS");paymentRows(payments)}
            if(value.deadlines.isNotEmpty()) {
                ThemeSection(word("付款变更期限","支払方法の変更締切"),"DEADLINES")
                value.deadlines.forEach{row->val d=row.deadline;val expired=vm.repository.clock.instant()>d.instant()
                    ImportantEvent(d.date+" "+d.time+" · "+row.accountName,
                        financeLabel(d.changeType.name)+" · "+d.zoneId+" · "+row.billDate,
                        Money(Currency.valueOf(row.currency),row.outstandingMinor).display(),
                        if(expired)word("期限已过，请核对平台实际受理结果。","締切経過。返済先の実際の受付結果を確認してください。")else word("账期：","対象：")+row.planName,
                        "important-deadline-${d.billId}-${d.changeType.name}",{onDeadline(row.accountId,row.billDate.take(7))},warning=expired)
                }
            }
            if(recurring.isNotEmpty()) {ThemeSection(word("未完成周期账单","未完了の定期支払"),"RECURRING");paymentRows(recurring)}
            if(budgetRisk) {
                ThemeSection(word("预算风险","予算リスク"),"BUDGET")
                val actual=pace.actualOverMinor?.let{it>0}==true
                ImportantEvent(word("本月预算","今月の予算"),if(actual)word("已发生超出","実支出の超過")else word("预计超出","超過の見込み"),
                    outlookAmount(s.book.currency,if(actual)pace.actualOverMinor else pace.projectedOverMinor),
                    word("按${pace.sampleDays}个自然日的已记录消费测算，打开查看依据。","記録済み支出・${pace.sampleDays}暦日から推計。根拠を確認できます。"),"important-budget",onForecast,warning=true)
            }
            if(risks.isNotEmpty()) {
                ThemeSection(word("实际资金账户风险","実資金口座のリスク"),"FUNDS")
                risks.forEach{risk->val a=risk.opening.account
                    ImportantEvent(a.name+" · "+a.currency,word("基础最大缺口","基本の最大不足"),outlookAmount(a.currency,risk.maximumGapMinor,risk.estimated),
                        word("当前备款 / 含预计收入缺口：","現在の準備不足・収入見込みありの不足：")+outlookAmount(a.currency,risk.currentGapMinor)+" / "+outlookAmount(a.currency,risk.includingIncomeGapMinor,risk.includingEstimated),
                        "important-risk-${a.id}",{onRisk(a.id)},warning=true)
                }
            }
            FinancialAttentionList(s,value,onAttention)
        }
    }
    YButton(word("查看账户缺口与预算风险","口座不足・予算リスクを見る"),onForecast,Modifier.testTag("important-forecast"))
}

@Composable private fun ImportantEvent(title:String,subtitle:String,amount:String,note:String,tag:String,onClick:()->Unit,warning:Boolean=false) {
    val t=LocalYohaku.current;val journal=t.id=="journal";val objects=t.id=="objects"
    val density=LocalDensity.current
    val compact=with(density){LocalWindowInfo.current.containerSize.width.toDp()<=360.dp}||density.fontScale>1.1f
    val shape=RoundedCornerShape(12.dp)
    val frame=if(objects)Modifier.background(Color(0xfffff9eb),shape).border(1.dp,t.line,shape).padding(horizontal=13.dp,vertical=16.dp)
        else Modifier.drawBehind{drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=if(journal)PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))else null)}.padding(vertical=18.dp)
    Column(Modifier.fillMaxWidth().testTag(tag).clickable(role=Role.Button,onClick=onClick).then(frame),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f)) {
                YText(title,if(journal)16 else 12,lineHeightMultiplier=1.8f)
                YText(subtitle,if(journal)11 else 9,color=t.muted,lineHeightMultiplier=1.8f)
            }
            if(!compact)YText(amount,15,number=amount.any{it.isDigit()},color=if(warning)t.warm else t.ink)
        }
        if(compact)YText(amount,15,number=amount.any{it.isDigit()},color=if(warning)t.warm else t.ink)
        YText(note,if(journal)11 else 9,color=t.muted,lineHeightMultiplier=1.8f)
    }
}
