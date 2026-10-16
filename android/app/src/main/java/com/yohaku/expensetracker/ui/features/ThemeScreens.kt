package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.yohaku.expensetracker.data.db.EventEntity
import com.yohaku.expensetracker.data.repository.cashBalanceAt
import com.yohaku.expensetracker.data.repository.BookSnapshot
import com.yohaku.expensetracker.data.repository.netExpense
import com.yohaku.expensetracker.data.repository.monthExpense
import com.yohaku.expensetracker.data.repository.budgetProjection
import com.yohaku.expensetracker.data.repository.provisionalExpenseCount
import com.yohaku.expensetracker.data.repository.pendingOccurrences
import com.yohaku.expensetracker.data.repository.visibleComponents
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import java.time.LocalDate
import java.time.YearMonth

@Composable internal fun PageEdition(page:String) {
    val t=LocalYohaku.current
    val today=LocalDate.now()
    // The enclosing page supplies 15dp; the rest completes the HTML bottom margin.
    when(t.id){
        "magazine"->Column(Modifier.fillMaxWidth().padding(bottom=3.dp).testTag("page-edition")){
            Row(Modifier.fillMaxWidth().padding(bottom=10.dp),horizontalArrangement=Arrangement.SpaceBetween){
                YText("YOHAKU / ${when(page){"statement-import"->"IMPORT";"statements"->"RECONCILE";else->page.uppercase()}}",8,color=t.muted,letterSpacing=1.25f,lineHeightMultiplier=1.375f,cssLineBox=true)
                YText("VOL. ${today.monthValue} · ${today.year}",8,color=t.muted,letterSpacing=1.25f,lineHeightMultiplier=1.375f,cssLineBox=true)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
        }
        "journal"->Column(Modifier.fillMaxWidth().padding(bottom=7.dp).testTag("page-edition")){
            Row(Modifier.fillMaxWidth().padding(bottom=7.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                YText(word("我的生活记录","暮らしのノート"),11,color=t.muted,lineHeightMultiplier=16f/11,cssLineBox=true)
                YText("${today.monthValue} / ${today.dayOfMonth}",10,number=true,color=t.muted,lineHeightMultiplier=1.5f,cssLineBox=true)
            }
            Canvas(Modifier.fillMaxWidth().height(1.dp)){drawLine(t.line,Offset.Zero,Offset(size.width,0f),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())))}
        }
        else->Row(Modifier.fillMaxWidth().padding(bottom=3.dp).testTag("page-edition"),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
            YText(word("生活小物 · 我的桌面","暮らしの小物・マイデスク"),8,color=Color(0xffb19d88),letterSpacing=.2f,lineHeightMultiplier=1.375f,cssLineBox=true)
            Box(Modifier.size(30.dp,5.dp).rotate(-5f).background(Color(0xffd6b492),RoundedCornerShape(4.dp)))
        }
    }
}
@Composable internal fun PageFootnote(page:String) {
    val t=LocalYohaku.current
    Column(Modifier.fillMaxWidth().padding(top=12.dp,bottom=5.dp)){
        when(t.id){
            "magazine"->{
                Box(Modifier.fillMaxWidth().height(1.dp).background(t.ink))
                Row(Modifier.padding(top=13.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    YText(word("生活的余量","暮らしの余白"),10,color=t.muted,lineHeightMultiplier=1.5f)
                    YText("— ${page.uppercase()} —",8,color=t.muted,letterSpacing=1f,lineHeightMultiplier=1.375f)
                }
            }
            "journal"->YText(word("一笔一笔，慢慢记。","一筆ずつ、ゆっくり。")+" ✳",13,Modifier.fillMaxWidth().rotate(-1.5f).padding(end=5.dp),color=t.warm,lineHeightMultiplier=19f/13,textAlign=androidx.compose.ui.text.style.TextAlign.End)
            else->Row(Modifier.fillMaxWidth().padding(vertical=17.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){
                YIcon("leaf",Modifier.size(14.dp),t.muted)
                YText(word("把日常，放回舒服的位置。","日々を、心地よい場所に。"),10,Modifier.padding(start=7.dp),color=t.muted,lineHeightMultiplier=1.5f)
            }
        }
    }
}
@Composable internal fun HomeScreen(s:BookSnapshot,navigate:(String)->Unit,onAdd:()->Unit,onResume:()->Unit,onEvent:(EventEntity)->Unit,onBudget:()->Unit,today:LocalDate=LocalDate.now(),onRecurring:()->Unit={},financialContent:@Composable ()->Unit={}) {
    val t=LocalYohaku.current
    val month=YearMonth.from(today)
    val currency=Currency.valueOf(s.book.currency)
    val entries=s.visibleEvents.filter{it.date.startsWith(month.toString())}
    val projection=s.budgetProjection(month.toString())
    val spent=projection.spentMinor
    val remaining=projection.remainingMinor
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(15.dp)) {
        if(projection.limitMinor==null)YLead(word("从第一笔，开始。","最初の一筆から。"),word("先设置月预算，也可以先添加账户。","月予算の設定、または口座追加から。"),"A FRESH START",pageFlow=true)
        else HomeCover(currency,projection.limitMinor,spent,projection.reserveMinor,projection.availableMinor,today)
        val provisional=s.provisionalExpenseCount(month.toString())
        if(provisional>0)BudgetEstimateNote(provisional)
        if(projection.limitMinor==null)Box(Modifier.padding(top=15.dp)){YButton(word("设置月预算","月予算を設定"),onBudget,Modifier.testTag("home-set-budget"),primary=true)}
        Box(Modifier.padding(top=if(projection.limitMinor==null)5.dp else 9.dp)){HomeSpendContext(Money(currency,projection.allExpenseMinor).display(),Money(currency,projection.independentMinor).display())}
        if(projection.pendingCount>0||projection.fundReserveMinor>0)ThemeAuditPanel(word("先为固定支出，留好位置。","固定費にも、余白を。"),Modifier.testTag("home-fixed-reserve")) {
            RecordFacts(listOf(word("待付固定预算预留","固定費の未払い予算予定") to (projection.fixedReserveMinor?.let{Money(currency,it).display()}?:word("金额 / 折算待补齐","金額・換算待ち")),
                word("本月活动预算预留","今月のシーン予算の確保") to Money(currency,projection.activityReserveMinor).display(),
                word("全部活动资金预留","全シーンの資金確保") to Money(currency,projection.fundReserveMinor).display(),
                word("预留后可支配","予定を除いた残額") to (projection.availableMinor?.let{Money(currency,it).display()}?:"—")),audit=true)
            YLink(word("确认 / 管理周期账单","定期支払を確認・管理"),onRecurring)
        }
        YLink(word("预算与分类限额","予算とカテゴリの上限"),onBudget)
        if(s.recurrences.isEmpty())YLink(word("添加周期账单","定期支払を追加"),onRecurring)
        else if(projection.pendingCount==0)YLink(word("查看周期账单与历史","定期支払と履歴を見る"),onRecurring)
        ThemeSection(word("钱够不够，还要看账户。","支払いは、口座残高も。"),"REAL FUNDS",pageGap=15)
        Box(Modifier.padding(top=3.dp)){HomeCashNote(Currency.entries.mapNotNull{c->val accounts=s.accounts.filter{it.currency==c.name&&!AccountType.valueOf(it.type).debt};if(accounts.isEmpty())null else Money(c,accounts.sumOf{s.cashBalanceAt(it,today.toString())}).display()},{navigate("accounts")})}
        financialContent()
        ThemeSection(word("最近的几笔","最近の記録"),"DAILY NOTES",word("全部流水","すべて"),{navigate("ledger")},pageGap=15)
        val recentExpenses=s.visibleEvents.filter{it.kind=="EXPENSE"}.take(3)
        TransactionList(s,recentExpenses,onEvent,emptyContent={YEmpty(word("分类由你创建，记第一笔就开始。","カテゴリは自分で作成。最初の一筆から。"),onAdd,word("记一笔","記録"))})
        ThemeAuditPanel(word("给接下来的日常，留一点。","これからの日々に、少し。"),Modifier.testTag("home-daily-panel"),contentTopMargin=20){
            val days=month.lengthOfMonth()-today.dayOfMonth+1
            HomeDailyStats(days,projection.availableMinor?.let{Money(currency,it.coerceAtLeast(0)/days).display()} ?: "—",Money(currency,(entries.filter{it.date==today.toString()&&it.kind=="EXPENSE"}.sumOf{s.netExpense(it)}+s.visibleComponents().filter{it.date==today.toString()}.sumOf{it.baseMinor})).display())
            if(s.draft!=null)HomeResumeDraft(onResume)
        }
    }
}
