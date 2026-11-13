package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.db.StatementReviewSkipEntity
import com.yohaku.expensetracker.data.files.FileProtocols
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.haptics.Haptic
import com.yohaku.expensetracker.ui.theme.*
import java.time.LocalDate
import java.time.YearMonth

/** Home line for closed bank/card periods still waiting for a statement; absent when nothing is due. */
@Composable internal fun HomeStatementDue(s:BookSnapshot,vm:AppViewModel,today:LocalDate) {
    val batches by remember(s.book.id){vm.repository.db.fileWorkflowDao().statements(s.book.id)}.collectAsStateWithLifecycle(null)
    val skips by remember(s.book.id){vm.repository.db.fileWorkflowDao().reviewSkips(s.book.id)}.collectAsStateWithLifecycle(null)
    val finance by vm.financialState.collectAsStateWithLifecycle()
    // Card periods depend on billing rules; wait for every source rather than flash calendar-month guesses.
    val rules=finance?.takeIf{it.book.id==s.book.id}?.rules
    val due=remember(s.accounts,batches,skips,rules,today) {
        if(batches==null||skips==null||rules==null)emptyList()
        else statementDuePeriods(s.accounts,rules,
            batches!!.map{FileProtocols.statement(it.originalJson)}.groupBy({it.account_id},{LocalDate.parse(it.from) to LocalDate.parse(it.to)}),
            skips!!.map{it.accountId to it.periodEnd}.toSet(),today)
    }
    var open by rememberSaveable(s.book.id){mutableStateOf(false)}
    LaunchedEffect(due.isEmpty()){if(due.isEmpty())open=false}
    if(due.isEmpty())return
    val byAccount=due.groupBy{it.accountId}
    fun name(id:String)=s.accounts.first{it.id==id}.name
    @Composable fun label(p:StatementDuePeriod):String {
        val m=YearMonth.from(p.end)
        val month=if(m.year==today.year)word("${m.monthValue}月","${m.monthValue}月")else word("${m.year}年${m.monthValue}月","${m.year}年${m.monthValue}月")
        return month+if(p.card)word("账单","請求分")else word("流水","明細")
    }
    val parts=byAccount.entries.take(2).map{(id,periods)->name(id)+" "+(periods.singleOrNull()?.let{label(it)}?:word("${periods.size} 期","${periods.size}期間"))}
    val summary=parts.joinToString(word("、","、"))+(if(byAccount.size>2)word(" 等 ${byAccount.size} 个账户"," ほか計${byAccount.size}口座")else "")+word(" 已确定，可以核对","が確定。照合できます")
    HomeStatementDueLine(summary){open=true}
    if(open)YSheet(word("可以核对的流水","照合できる明細"),{open=false}) {
        YText(word("期间结束两天后出现；导入覆盖整个期间的流水后自动消失。","期間終了の2日後に表示。期間全体の明細を取り込むと消えます。"),12,color=LocalYohaku.current.muted)
        val notePrefix=word("核对期间：","照合期間：")
        byAccount.forEach{(id,periods)->
            ThemeAuditPanel(name(id),Modifier.testTag("statement-due-$id")) {
                RecordFacts(periods.map{label(it) to "${it.start} — ${it.end}"},audit=true)
                YActionRow {
                    YButton(word("去核对","照合する"),{
                        vm.statementImportSeed.value=StatementImportSeed(s.book.id,id,notePrefix+"${periods.first().start} — ${periods.last().end}")
                        open=false;vm.navigate("statement-import")
                    },Modifier.testTag("statement-due-import-$id"),primary=true,compact=true)
                    YLink(if(periods.size==1)word("本期不核对","今回は照合しない")else word("这些期间不核对","これらは照合しない"),{
                        vm.execute(onSuccess={vm.haptic(Haptic.Done)}){
                            val now=System.currentTimeMillis()
                            vm.repository.db.fileWorkflowDao().putReviewSkips(periods.map{StatementReviewSkipEntity(s.book.id,id,it.end.toString(),now)})
                        }
                    },Modifier.testTag("statement-due-skip-$id"))
                }
            }
        }
    }
}

@Composable private fun HomeStatementDueLine(text:String,onOpen:()->Unit) {
    val t=LocalYohaku.current
    val base=Modifier.fillMaxWidth().testTag("home-statement-due").clickable(role=Role.Button,onClick=onOpen)
    when(t.id) {
        // Magazine: an editorial kicker between hairlines.
        "magazine"->Row(base.drawBehind{
            drawLine(t.line,Offset(0f,0f),Offset(size.width,0f),1.dp.toPx());drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())
        }.padding(vertical=13.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            YText("RECONCILE",8,color=t.muted,letterSpacing=1.4f,lineHeightMultiplier=1.4f)
            YText(text,12,Modifier.weight(1f),lineHeightMultiplier=1.6f)
            YText("→",12,color=t.ink)
        }
        // Journal: a red-pen stamp beside a dashed note line.
        "journal"->Row(base.drawBehind{
            drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())))
        }.padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(11.dp)) {
            YText(word("核对","照合"),11,Modifier.rotate(-6f).border(1.dp,t.accent,RoundedCornerShape(3.dp)).padding(horizontal=5.dp,vertical=1.dp),color=t.accent,lineHeightMultiplier=1.4f)
            YText(text,14,Modifier.weight(1f),color=t.accent,lineHeightMultiplier=1.6f)
        }
        // Objects: a soft tray strip with a colored dot.
        else->Row(base.background(t.soft,RoundedCornerShape(14.dp)).padding(horizontal=14.dp,vertical=13.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(8.dp).background(t.accent,CircleShape))
            YText(text,12,Modifier.weight(1f),lineHeightMultiplier=1.6f)
            YText("›",15,color=t.muted)
        }
    }
}
