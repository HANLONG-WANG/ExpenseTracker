package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.data.db.AccountEntity
import com.yohaku.expensetracker.data.repository.cashBalanceAt
import java.time.LocalDate
import com.yohaku.expensetracker.data.repository.BookSnapshot
import com.yohaku.expensetracker.data.repository.ReferenceRates
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun AccountsScreen(s:BookSnapshot,onAdd:()->Unit,onSelect:(AccountEntity)->Unit,referenceService:ReferenceRates?=null,financialContent:@Composable ()->Unit={},today:LocalDate=LocalDate.now()) {
    val t=LocalYohaku.current
    val funds=s.accounts.filter{!AccountType.valueOf(it.type).debt}
    val debts=s.accounts.filter{AccountType.valueOf(it.type).debt}
    val totals=funds.groupBy{it.currency}.mapValues{(_,accounts)->accounts.fold(0L){total,a->Math.addExact(total,s.cashBalanceAt(a,today.toString()))}}
    val base=Currency.valueOf(s.book.currency)
    val hasForeign=totals.keys.any{it!=base.name}
    YLead(word("钱与欠款，都有位置。","お金も借入も、それぞれに。"),
        word("账户保留原币；信用额度不计入资金。","口座は元通貨。利用枠は資金に含みません。"),"A PLACE FOR EVERY CURRENCY",
        // Mixed currencies are never summed; their per-currency totals follow in the summary card.
        if(hasForeign)"" else Money(base,totals[base.name]?:0).display(),
        if(hasForeign)"" else word("今日实际资金","本日の実資金"),pageFlow=true)
    if(hasForeign)Box(Modifier.padding(bottom=5.dp)){AccountSummary(word("持有的资金 · 按原币","手元の資金・元通貨別"),totals.map{(currency,value)->Money(Currency.valueOf(currency),value).display()},word("各币种按实际余额分别展示。","実残高を通貨別に表示。"))}
    val debtTotals=debts.groupBy{it.currency}.map{(currency,accounts)->Money(Currency.valueOf(currency),accounts.fold(0L){total,a->Math.addExact(total,s.balance(a))}).display()}
    AccountSummary(word("当前欠款 · 不计入资金","現在の借入残高・資金には含まない"),debtTotals.ifEmpty{listOf(Money(base,0).display())},word("信用额度不计入资金。","利用枠は資金に含めません。"),Modifier.testTag("accounts-reference-note"))
    YText(today.toString()+" · "+word("资金只计截至今日已确认流水；未来已记流水在时间线中另列。","資金は本日までの確定記録のみ。将来日付の記録は予定に別掲。"),11,color=t.muted)
    // The account lists come before valuation and planning so the page opens on the accounts themselves.
    ThemeSection(word("持有的资金","持っている資金"),"AVAILABLE FUNDS")
    Column(Modifier.fillMaxWidth().padding(top=8.dp,bottom=12.dp).testTag("funds-account-list"),verticalArrangement=Arrangement.spacedBy(13.dp)){
        if(funds.isEmpty())YEmpty(word("添加第一个账户","最初の口座を追加"),onAdd,word("添加账户","口座を追加"))
        funds.forEachIndexed{i,a->AccountCard(s,a,i,{onSelect(a)},today)}
    }
    ThemeSection(word("信用卡与借款","カードと借入"),"TO REPAY",previousBottomPadding=12)
    Column(Modifier.fillMaxWidth().padding(top=8.dp,bottom=27.dp).testTag("debt-account-list"),verticalArrangement=Arrangement.spacedBy(13.dp)){
        debts.forEachIndexed{i,a->AccountCard(s,a,i,{onSelect(a)},today)}
    }
    YButton(word("＋ 添加账户","＋ 口座を追加"),onAdd,Modifier.testTag("accounts-add"),primary=true)
    if(hasForeign&&referenceService!=null)ReferenceAssetValuation(s,referenceService,today)
    ThemeSection(word("付款与资金安排","支払と資金の予定"),"PLANNING")
    financialContent()
}

@Composable private fun AccountSummary(title:String,values:List<String>,footer:String,modifier:Modifier=Modifier) {
    val t=LocalYohaku.current
    val frame=when(t.id){
        "magazine"->Modifier.background(t.soft).drawBehind{drawLine(t.ink,Offset.Zero,Offset(0f,size.height),2.dp.toPx())}.padding(17.dp)
        "journal"->Modifier.rotate(.3f).background(Color(0xfff8f2df)).dashedAccountBorder(t.line).padding(18.dp)
        else->Modifier.drawBehind{drawRoundRect(Color(0x22b1b895),topLeft=Offset(0f,3.dp.toPx()),size=size,cornerRadius=androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()))}.background(Color(0xffe8e9d9),RoundedCornerShape(16.dp)).padding(20.dp)
    }
    Column(modifier.fillMaxWidth().then(frame)){
        YText(title,10,lineHeightMultiplier=1.9f,cssLineBox=true)
        values.forEach{YText(it,21,Modifier.padding(top=7.dp),number=true,lineHeightMultiplier=1.9f,cssLineBox=true)}
        YText(footer,9,Modifier.padding(top=9.dp),color=t.muted,lineHeightMultiplier=1.9f,cssLineBox=true)
    }
}

@Composable internal fun AccountCard(s:BookSnapshot,a:AccountEntity,index:Int,onClick:()->Unit,today:LocalDate=LocalDate.now()) {
    val t=LocalYohaku.current
    val narrow=LocalConfiguration.current.screenWidthDp<=360
    val debt=AccountType.valueOf(a.type).debt
    val amount=Money(Currency.valueOf(a.currency),if(debt)s.balance(a)else s.cashBalanceAt(a,today.toString())).display()
    val note=if(!a.active)word("已停用 · 请核对资金安排","停止中・資金予定を確認")else if(debt)accountTypeName(AccountType.valueOf(a.type))+" · "+word("当前欠款","現在の借入残高") else accountTypeName(AccountType.valueOf(a.type))
    val click=Modifier.testTag("account-"+a.id).fillMaxWidth().clickable(role=Role.Button,onClick=onClick)
    when(t.id){
        "magazine"->Column(click){
            Row(Modifier.fillMaxWidth().padding(vertical=19.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy((if(narrow)7 else 11).dp)){
                YText(a.currency,8,Modifier.width(29.dp).drawBehind{drawLine(t.line,Offset(size.width,0f),Offset(size.width,size.height),1.dp.toPx())}.padding(end=5.dp),color=t.accent,lineHeightMultiplier=11f/8,cssLineBox=true)
                Column(Modifier.weight(1f)){YText(a.name,if(narrow)11 else 13,bold=true,lineHeightMultiplier=1.8f,cssLineBox=true);YText(note,9,Modifier.padding(top=7.dp),color=t.muted,lineHeightMultiplier=1.9f,cssLineBox=true)}
                YText(amount,if(narrow)17 else 20,number=true,lineHeightMultiplier=22f/20,cssLineBox=true)
                if(!narrow)YText("↗",13,color=t.muted,lineHeightMultiplier=19f/13,cssLineBox=true)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
        }
        "journal"->Box(click.rotate(if(index%2==0)-.3f else .4f).background(Color(0xfffff8e7)).dashedAccountBorder(t.line)){
            Column(Modifier.padding(start=15.dp,end=15.dp,top=21.dp,bottom=18.dp)){
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp),verticalAlignment=Alignment.Top){YText(a.name,16,Modifier.weight(1f),lineHeightMultiplier=1.8f,cssLineBox=true);YText(amount,23,number=true,lineHeightMultiplier=34f/23,cssLineBox=true)}
                YText(note,11,Modifier.padding(top=14.dp),color=t.muted,lineHeightMultiplier=1.9f,cssLineBox=true)
            }
            YText(a.currency,10,Modifier.offset(x=13.dp,y=(-7).dp).rotate(-4f).background(Color(0xfffff3d8)).border(1.dp,t.warm).padding(horizontal=8.dp,vertical=4.dp),color=t.warm,lineHeightMultiplier=17f/10,cssLineBox=true)
        }
        else->{
            val shape=RoundedCornerShape(12.dp,12.dp,23.dp,19.dp)
            val even=index%2==1
            val bg=if(debt)Color(0xfff0dfce) else if(even)Color(0xffe5e8d7) else Color(0xffede0c5)
            val border=if(debt)Color(0xffdfc5a8) else if(even)Color(0xffd0d5bd) else Color(0xffdcc49b)
            Box(click.padding(top=6.dp).background(bg,shape).border(1.dp,border,shape)){
                Column(Modifier.padding(start=20.dp,end=20.dp,top=29.dp,bottom=21.dp)){
                    YText(a.name,13,bold=true,lineHeightMultiplier=1.8f,cssLineBox=true)
                    YText(amount,29,Modifier.padding(top=16.dp,bottom=8.dp),number=true,color=Color(0xffad7951),lineHeightMultiplier=42f/29,cssLineBox=true)
                    YText(note,9,color=t.muted,lineHeightMultiplier=1.9f,cssLineBox=true)
                }
                YText(a.currency,8,Modifier.offset(x=18.dp,y=(-6).dp).background(if(even&&!debt)Color(0xffa1ae87) else Color(0xffb99065),RoundedCornerShape(6.dp)).padding(horizontal=10.dp,vertical=6.dp),color=Color(0xfffff8e8),lineHeightMultiplier=11f/8,cssLineBox=true)
                YText("↗",13,Modifier.align(Alignment.BottomEnd).padding(end=17.dp,bottom=21.dp),color=Color(0xffb0936c),lineHeightMultiplier=19f/13,cssLineBox=true)
            }
        }
    }
}

private fun Modifier.dashedAccountBorder(color:Color)=drawBehind{
    drawRect(color,style=androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))))
}
