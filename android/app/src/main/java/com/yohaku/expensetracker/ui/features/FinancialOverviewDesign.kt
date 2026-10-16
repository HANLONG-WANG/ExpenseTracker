package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

/** The cover uses actual debt for loans and the next saved payment for cards. */
@Composable internal fun FinancialAccountLead(s:BookSnapshot,a:AccountEntity,f:FinancialGraph,vm:AppViewModel) {
    val date by vm.today.collectAsStateWithLifecycle()
    val today=date.toString();val currency=Currency.valueOf(a.currency)
    val loan=a.type==AccountType.LOAN.name
    val stale=f.plans.filter{vm.repository.financialPredictionStale(s,f,it)}.map{it.id}.toSet()
    val summary=s.financialAccountSummary(f,a.id,today,stale)
    val modes=(f.contracts.filter{it.accountId==a.id}.map{it.mode}+f.plans.filter{it.accountId==a.id&&it.active}.map{it.mode}).distinct()
    val loanCaption=when {
        "FIXED_LOAN" in modes&&"CREDIT_LINE" in modes->word("固定合同与循环借入","固定契約と循環借入")
        "CREDIT_LINE" in modes->word("循环额度借款","利用枠内の借入")
        "FIXED_LOAN" in modes->word("固定计划借款","固定計画の借入")
        else->word("借款方式待补齐","借入方式は未補完")
    }
    val next=when {
        summary.next.isEmpty()->word("尚无已知待付日期","既知の支払日はありません")
        summary.next.any{!it.bill.confirmed&&it.plan.id in stale}->word("待核对","要確認")
        else->Money(currency,summary.next.statementTotals().totalMinor).display()
    }
    val status=when {
        summary.next.isEmpty()->word("请补齐已知付款安排","既知の支払予定を補完してください")
        summary.next.all{it.bill.confirmed}->word("已确认","確認済")
        summary.next.none{it.bill.confirmed}->word("本地预测 / 待核对","ローカル予測・要確認")
        else->word("含确认与预测","確定・予測を含みます")
    }
    val density=androidx.compose.ui.platform.LocalDensity.current
    val window=androidx.compose.ui.platform.LocalWindowInfo.current
    val wideTitle=with(density){window.containerSize.width.toDp()<=360.dp}||density.fontScale>1.1f
    YLead(a.name,if(loan)a.currency+" · "+loanCaption else if(wideTitle)word("账单、预测与信用额度分开。","確定額・予測・利用枠を区別。")else word("已知账单、预计安排和信用额度分开。","確定明細・見込み・利用枠を分ける。"),
        if(loan)"MY BORROWING"else"CREDIT / IN CONTEXT",
        if(loan)Money(currency,s.cashBalanceAt(a,today)).display()else next,
        if(loan)word("未还本金","未返済元金")else listOfNotNull(summary.nextDate,status).joinToString(" · "),
        amountIsText=!loan&&(summary.next.isEmpty()||summary.next.any{!it.bill.confirmed&&it.plan.id in stale}),pageFlow=true,wideTitle=wideTitle)
}

/** The balance column belongs to each saved plan, never the account's total actual debt. */
@Composable internal fun FinancialLoanSchedule(summary:FinancialAccountSummary,currency:Currency,stale:Set<String>,onPlans:()->Unit) {
    val t=LocalYohaku.current
    val journal=t.id=="journal";val objects=t.id=="objects"
    val unknown=word("未知","不明")
    ThemeSection(word("逐期还款安排","各回の返済予定"),"KNOWN SCHEDULE")
    val groups=summary.rows.filter{it.outstanding.totalMinor!=0L}.groupBy{it.plan.id}
    if(groups.isEmpty())YEmpty(word("尚无已保存的逐期付款安排。","保存済みの各回の支払予定はありません。"))
    groups.forEach{(planId,rows)->
        val plan=rows.first().plan
        YText(plan.name+" · "+financeLabel(plan.mode),if(journal)15 else 12)
        val shape=RoundedCornerShape(15.dp)
        val frame=if(objects)Modifier.shadow(2.dp,shape).background(Color(0xfffff8e8),shape).border(1.dp,Color(0xffe1caa4),shape).padding(horizontal=13.dp,vertical=4.dp)else Modifier
        val size=9
        val headers=listOf(word("本金","元金"),word("费用","費用"),word("期后本金","支払後元金"))
        val all=summary.rows.filter{it.plan.id==planId}
        val canProject=plan.complete&&planId !in stale&&all.all{it.outstanding.principalMinor?.let{p->p>=0}==true}
        val values=rows.associate{row->row.bill.id to listOf(row.outstanding.principalMinor,row.outstanding.feeMinor,
            if(canProject)all.filter{it.bill.number>row.bill.number}.map{it.outstanding.principalMinor}.knownTotal()else null)}
        val measurer=rememberTextMeasurer()
        val density=androidx.compose.ui.platform.LocalDensity.current
        fun width(text:String,number:Boolean,fontSize:Int)=with(density){measurer.measure(text,TextStyle(fontFamily=if(number)t.number else t.body,fontSize=fontSize.sp)).size.width.toDp()+1.dp}
        val dateWidth=maxOf(45.dp,rows.maxOf{width(it.bill.date.take(7).replace('-','.'),true,size)})
        val moneyWidth=maxOf(values.values.flatten().maxOf{n->width(n?.let{Money(currency,it).display()}?:unknown,n!=null,size)},headers.maxOf{width(it,false,if(journal)10 else 8)})
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val tableWidth=maxOf(maxWidth,dateWidth+moneyWidth*3+12.dp+if(objects)26.dp else 0.dp)
        val scrolls=tableWidth>maxWidth
        Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        if(scrolls)YText(word("左右滑动查看完整金额。","左右にスクロールして金額全体を確認。"),10,color=t.muted)
        Column(Modifier.horizontalScroll(rememberScrollState()).testTag("loan-schedule-scroll-$planId")) {
        Column(Modifier.width(tableWidth).testTag("loan-schedule-$planId").then(frame)) {
            fun Modifier.rule(header:Boolean=false)=drawBehind {
                val color=if(objects)Color(0xffddc7a3)else t.line
                drawLine(if(header&&!journal&&!objects)t.ink else color,Offset(0f,this.size.height),Offset(this.size.width,this.size.height),1.dp.toPx(),
                    pathEffect=if(objects||journal&&header)PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))else null)
            }
            Row(Modifier.fillMaxWidth().rule(true).padding(vertical=15.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                YText(word("月份","月"),if(journal)10 else 8,Modifier.width(dateWidth),color=t.muted)
                for(label in headers)YText(label,if(journal)10 else 8,Modifier.weight(1f),color=t.muted,textAlign=TextAlign.End)
            }
            rows.forEach{row->
                fun amount(value:Long?)=value?.let{Money(currency,it).display()}?:unknown
                Column(Modifier.fillMaxWidth().testTag("loan-schedule-bill-${row.bill.id}").rule().padding(vertical=16.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        YText(row.bill.date.take(7).replace('-','.'),size,Modifier.width(dateWidth),number=true)
                        for(value in values.getValue(row.bill.id))YText(amount(value),size,Modifier.weight(1f),number=value!=null,textAlign=TextAlign.End)
                    }
                    YText(row.bill.date+" · "+word("待付合计 ","未払合計 ")+Money(currency,row.outstanding.totalMinor).display()+" · "+
                        when{!row.bill.confirmed&&planId in stale->word("原预测待核对","元予測・要確認");row.bill.confirmed->word("平台已确认","返済先確定");else->word("本地预测","ローカル予測")},if(journal)11 else 9,color=t.muted)
                }
            }
        }
        }
        }
        }
    }
    YText(word("期后本金仅按这份完整计划的待付义务推算，不是账户实际余额。部分计划、本金未知或待重核时不推算。","支払後元金は各完全プランの未払予定から推計し、実残高とは異なります。一部予定・元金不明・要再確認では推計しません。"),11,color=t.muted)
    YButton(word("核对逐期计划","各回の予定を確認"),onPlans,Modifier.testTag("loan-schedule-open"))
}

@Composable internal fun FinancialLoanNext(s:BookSnapshot,a:AccountEntity,f:FinancialGraph,summary:FinancialAccountSummary,available:Long?,stale:Set<String>,onStatement:(String)->Unit) {
    val t=LocalYohaku.current;val journal=t.id=="journal";val objects=t.id=="objects"
    val currency=Currency.valueOf(a.currency);val totals=summary.next.statementTotals()
    val unknown=word("未知","不明")
    val revolving=f.contracts.any{it.accountId==a.id&&it.mode=="CREDIT_LINE"}||f.plans.any{it.accountId==a.id&&it.active&&it.mode=="CREDIT_LINE"}
    if(revolving)RecordFacts(listOf(word("预计可用额度 / 非现金","予測利用可能枠・現金とは別") to (available?.let{"≈ "+Money(currency,it).display()}?:unknown)))
    val shape=RoundedCornerShape(16.dp)
    val frame=when {
        objects->Modifier.shadow(2.dp,shape).background(Color(0xfffff8e8),shape).border(1.dp,Color(0xffe1caa4),shape)
        else->Modifier.drawBehind {
            val effect=if(journal)PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))else null
            drawLine(if(journal)t.line else t.ink,Offset.Zero,Offset(size.width,0f),1.dp.toPx(),pathEffect=effect)
            drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=effect)
        }
    }
    val density=androidx.compose.ui.platform.LocalDensity.current
    val window=androidx.compose.ui.platform.LocalWindowInfo.current
    val compact=with(density){window.containerSize.width.toDp()<=360.dp}||density.fontScale>1.1f
    Row(Modifier.fillMaxWidth().testTag("finance-overview-next").then(frame).padding(horizontal=8.dp,vertical=19.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
        YText(summary.nextDate?.takeLast(5)?.replace('-','.')?:"—",if(objects)13 else 19,number=true,color=t.muted)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(7.dp)) {
            YText(if(summary.next.isNotEmpty()&&summary.next.all{it.bill.confirmed})if(compact)word("已确认应还","次回確定額")else word("下次已确认应还","次回確定支払額")else if(compact)word("下次付款","次回予定")else word("下次付款安排","次回支払予定"),11)
            val banks=summary.next.map{r->s.accounts.firstOrNull{it.id==r.bill.paymentAccountId}?.let{it.name+" · "+it.currency}?:word("扣款账户未定","引落口座未定")}.distinct()
            YText(banks.joinToString(" / "),if(journal)11 else 9,color=t.muted)
        }
        val amount=when{summary.next.isEmpty()->"—";summary.next.any{!it.bill.confirmed&&it.plan.id in stale}->word("待核对","要確認");else->Money(currency,totals.totalMinor).display()}
        YText(amount,if(compact)14 else 18,Modifier.testTag("finance-overview-amount"),number=summary.next.isNotEmpty())
    }
    var details by androidx.compose.runtime.saveable.rememberSaveable(a.id){mutableStateOf(false)}
    if(summary.next.isNotEmpty()) {
        YButton(word("本金、费用与待确认","元金・費用・確認待ち")+if(details)" −"else" +",{details=!details},Modifier.testTag("loan-next-details"))
        if(details) {
            fun amount(value:Long?)=value?.let{Money(currency,it).display()}?:unknown
            RecordFacts(listOf(word("待清偿本金","未返済元金") to amount(totals.principalMinor),word("待清偿费用","未返済手数料") to amount(totals.feeMinor),word("已付待确认","支払済み・確認待ち") to amount(totals.pendingMinor),word("仍需备款","今後必要な資金") to amount(totals.cashRequiredMinor)))
        }
        YButton(word("查看该月账单","該当月の明細を見る"),{onStatement(requireNotNull(summary.nextDate).take(7))},Modifier.testTag("finance-next-statement"))
    }
}
