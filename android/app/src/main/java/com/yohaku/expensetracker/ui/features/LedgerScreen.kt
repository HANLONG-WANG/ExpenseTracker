package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.data.db.EventEntity
import com.yohaku.expensetracker.data.repository.BookSnapshot
import com.yohaku.expensetracker.data.repository.accountMoney
import com.yohaku.expensetracker.data.repository.accountEventPostings
import com.yohaku.expensetracker.data.repository.netExpense
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import java.time.LocalDate
import java.time.YearMonth

@Composable internal fun EventRow(s:BookSnapshot,e:EventEntity,onClick:()->Unit,accountId:String?=null,showDate:Boolean=true,last:Boolean=false) {
    val t=LocalYohaku.current
    val kind=EntryKind.valueOf(e.kind)
    val settlement=s.settlements.find{it.eventId==e.id}
    val transfer=s.transfers.find{it.eventId==e.id}
    val displaySide=accountId?.let{s.accountEventPostings(e,it)}.orEmpty()
    val displayDate=displaySide.maxOfOrNull{it.date}?:e.date
    val accountDelta=displaySide.takeIf{it.isNotEmpty()}?.sumOf{it.deltaMinor}
    val money=if(accountDelta!=null)Money(Currency.valueOf(displaySide.first().currency),if(accountDelta<0)Math.negateExact(accountDelta)else accountDelta)else s.accountMoney(e)
    val journal=t.id=="journal";val objects=t.id=="objects"
    val rowShape=RoundedCornerShape(13.dp)
    val frame=if(objects)Modifier.background(t.surface,rowShape).padding(horizontal=11.dp,vertical=13.dp) else Modifier.padding(vertical=if(journal)17.dp else 20.dp)
    Column(Modifier.fillMaxWidth().testTag("event-"+e.id).clickable(role=Role.Button,onClick=onClick)) {
        Row(Modifier.fillMaxWidth().then(frame),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(if(objects)10.dp else 12.dp)) {
            val shape=if(journal)RoundedCornerShape(3.dp,5.dp,1.dp,4.dp) else RoundedCornerShape(9.dp)
            Box(Modifier.size(if(journal)30.dp else if(objects)32.dp else 24.dp,if(journal)33.dp else if(objects)32.dp else 30.dp)
                .rotate(if(journal||objects)-4f else 0f).then(if(journal)Modifier.border(1.dp,Color(0xffbdb8a3),shape) else if(objects)Modifier.background(Color(0xfff0e0bf),shape) else Modifier),contentAlignment=Alignment.Center){YIcon(if(kind==EntryKind.EXPENSE)"bag" else "wallet",Modifier.size(18.dp).rotate(if(objects)4f else 0f),if(objects)Color(0xffa8804a) else t.muted)}
            Column(Modifier.weight(1f)) {
                val route=transfer?.let{tr->s.accounts.find{it.id==e.accountId}?.name.orEmpty()+" → "+s.accounts.find{it.id==tr.toAccountId}?.name.orEmpty()}
                YText(e.merchant.ifBlank{route?:entryKindName(kind)},if(journal)15 else if(objects)12 else 13,weight=if(objects)androidx.compose.ui.text.font.FontWeight.Medium else androidx.compose.ui.text.font.FontWeight.Normal,lineHeightMultiplier=if(journal)22f/15 else if(objects)17f/12 else 19f/13,cssLineBox=true)
                YText((if(showDate)displayDate.takeLast(5).replace('-','.')+" · " else "")+if(kind==EntryKind.EXPENSE)s.path(e.categoryId) else entryKindName(kind),if(journal)10 else 8,Modifier.padding(top=5.dp),color=t.muted,lineHeightMultiplier=if(journal)15f/10 else if(objects)11f/8 else 13f/8,cssLineBox=true)
                if(transfer!=null)YText(when{transfer.fromConfirmed&&transfer.toConfirmed->word("两端已确认","両側確認済");transfer.fromConfirmed->word("在途","送金中");transfer.toConfirmed->word("转出待确认","出金確認待ち");else->word("计划","予定")},if(journal)10 else 8,color=t.warm)
                else if(settlement?.confirmed==false)YText(if(kind==EntryKind.REFUND)word("待到账","未入金")else word("换算待确认","換算未確定"),if(journal)10 else 8,color=t.warm)
                else if(kind==EntryKind.REFUND&&settlement?.confirmed==true)YText(word("已到账","入金済"),if(journal)10 else 8,color=t.accent)
                if(settlement!=null&&settlement.accountCurrency!=e.currency)YText(Money(Currency.valueOf(e.currency),e.amountMinor).display(),if(journal)10 else 8,color=t.muted)

            }
            Column(Modifier.widthIn(max=170.dp),horizontalAlignment=Alignment.End) {
                val incoming=if(accountDelta!=null)accountDelta>=0 else e.kind in listOf("INCOME","REFUND","BORROWING")
                // Outside one account's view a transfer only moves money, so it carries no sign.
                val sign=if(accountDelta==null&&kind==EntryKind.TRANSFER)"" else if(incoming)"+" else "−"
                YText(sign+money.display(),if(objects)19 else 23,number=true,weight=if(objects)androidx.compose.ui.text.font.FontWeight.Medium else androidx.compose.ui.text.font.FontWeight.Normal,letterSpacing=if(journal)-1f else if(objects)-.6f else 0f,lineHeightMultiplier=if(journal)34f/23 else if(objects)27f/19 else 26f/23,cssLineBox=true)
                YText(s.accounts.find{it.id==(accountId?:e.accountId)}?.name.orEmpty(),if(journal)10 else 8,Modifier.padding(top=5.dp),color=t.muted,lineHeightMultiplier=if(journal)15f/10 else if(objects)11f/8 else 13f/8,cssLineBox=true)
            }
        }
        // Magazine hairlines sit between rows only.
        if(!objects&&!(last&&t.id=="magazine"))Box(Modifier.fillMaxWidth().height(1.dp).background(if(journal)Color(0xffd9d1bd) else t.line))
    }
}

@Composable internal fun LedgerScreen(s:BookSnapshot,onSelect:(EventEntity)->Unit,onStart:(EntryKind)->Unit) {
    val t=LocalYohaku.current
    var type by rememberSaveable(s.book.id){mutableStateOf("ALL")}
    var query by rememberSaveable(s.book.id){mutableStateOf("")}
    var filtersOpen by rememberSaveable(s.book.id){mutableStateOf(false)}
    var account by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    var category by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    var period by rememberSaveable(s.book.id){mutableStateOf("ALL")}
    var from by rememberSaveable(s.book.id){mutableStateOf(YearMonth.now().atDay(1).toString())}
    var to by rememberSaveable(s.book.id){mutableStateOf(LocalDate.now().toString())}
    var dateOwner by remember{mutableStateOf<Int?>(null)}
    YTitle(word("日常的每一笔。","日々の記録。"),"THE LEDGER",pageFlow=true)
    LedgerEntryActions(onStart)
    Box(Modifier.padding(top=2.dp)){YSearch(word("搜索商户、备注或金额","お店・メモ・金額で検索"),query,{query=it})}
    Box(Modifier.padding(top=2.dp)){YSelect(word("类型","種類"),type,listOf("ALL" to word("全部","すべて"),"EXPENSE" to word("支出","支出"),"INCOME" to word("收入","収入"),"TRANSFER" to word("转账 / 换汇","振替・両替"),"REFUND" to word("退款","返金")),{type=it})}
    val categories=category?.let{id->setOf(id)+s.categories.filter{it.parentId==id}.map{it.id}}
    val entries=s.visibleEvents.filter{e->
        val sides=account?.let{id->s.storedPostings.filter{it.eventId==e.id&&it.accountId==id}}
        val dates=if(account==null)listOf(e.date)else sides.orEmpty().map{it.date}.ifEmpty{listOf(e.date)}
        val amounts=listOf(s.accountMoney(e),Money(Currency.valueOf(e.currency),e.amountMinor))+sides.orEmpty().map{Money(Currency.valueOf(it.currency),if(it.deltaMinor<0)Math.negateExact(it.deltaMinor)else it.deltaMinor)}
        (type=="ALL"||e.kind==type)&&(account==null||e.accountId==account||!sides.isNullOrEmpty())&&(categories==null||e.categoryId in categories)&&
        (period=="ALL"||period=="MONTH"&&dates.any{it.take(7)==YearMonth.now().toString()}||period=="CUSTOM"&&dates.any{it in from..to})&&
        (query.isBlank()||(listOf(e.merchant,e.note)+amounts.flatMap{listOf(it.display(),it.decimal.toPlainString())}).any{it.contains(query.trim(),true)})
    }

    Column(Modifier.fillMaxWidth().padding(top=1.dp)){
        LedgerFilterDisclosure(filtersOpen,{filtersOpen=!filtersOpen}){
            YSelect(word("账户","口座"),account,listOf(null to word("全部账户","すべての口座"))+s.accounts.map{it.id as String? to it.name},{account=it})
            YSelect(word("分类","カテゴリ"),category,listOf(null to word("全部分类","すべてのカテゴリ"))+s.categories.map{it.id as String? to s.path(it.id)},{category=it})
            YSelect(word("日期范围","日付範囲"),period,listOf("ALL" to word("全部日期","すべての日付"),"MONTH" to word("本月","今月"),"CUSTOM" to word("自定义区间","期間を指定")),{period=it;dateOwner=null})
            if(period=="CUSTOM"){
                YDateFields(listOf(word("开始日期","開始日") to from,word("结束日期","終了日") to to),dateOwner,{dateOwner=it},{i,value->if(i==0)from=value else to=value})
                if(from>to)YText(word("结束日期不能早于开始日期。","終了日は開始日以降にしてください。"),11,color=t.warm)
            }
            LedgerClearFilters{type="ALL";query="";account=null;category=null;period="ALL";from=YearMonth.now().atDay(1).toString();to=LocalDate.now().toString();dateOwner=null}
        }
        Box(Modifier.padding(top=if(t.id=="objects"&&entries.isNotEmpty())8.dp else 0.dp)){TransactionList(s,entries,onSelect,emptyContent={YEmpty(if(s.visibleEvents.isEmpty())word("还没有记录","記録はまだありません") else word("没有符合筛选的记录","条件に合う記録はありません"))},accountId=account,byDay=true)}
    }
}

/** Same original transaction-list structure on home, ledger and account detail. */
@Composable internal fun TransactionList(s:BookSnapshot,entries:List<EventEntity>,onSelect:(EventEntity)->Unit,emptyContent:(@Composable ()->Unit)?=null,accountId:String?=null,byDay:Boolean=false){
    val t=LocalYohaku.current
    Column(Modifier.fillMaxWidth().testTag("transaction-list"),verticalArrangement=Arrangement.spacedBy(if(t.id=="objects")8.dp else 0.dp)) {
        // Day headers carry their own rule; an empty list keeps the original opening rule.
        if(t.id!="objects"&&(!byDay||entries.isEmpty()))Box(Modifier.fillMaxWidth().height(1.dp).background(if(t.id=="magazine")t.ink else t.line))
        fun date(e:EventEntity)=if(accountId==null)e.date else s.accountEventPostings(e,accountId).maxOfOrNull{p->p.date}?:e.date
        val ordered=if(accountId==null)entries else entries.sortedByDescending{date(it)}
        if(byDay)ordered.groupBy{date(it)}.forEach{(day,events)->
            LedgerDayHeader(day,Money(Currency.valueOf(s.book.currency),events.filter{it.kind==EntryKind.EXPENSE.name}.sumOf{s.netExpense(it)}))
            events.forEach{EventRow(s,it,{onSelect(it)},accountId,showDate=false,last=it===events.last())}
        }
        else ordered.forEach{EventRow(s,it,{onSelect(it)},accountId,last=it===ordered.last())}
        if(entries.isEmpty())emptyContent?.invoke()
    }
}

/** Day divider for long lists: date with weekday on the left, that day's net spending on the right. */
@Composable private fun LedgerDayHeader(day:String,spent:Money) {
    val t=LocalYohaku.current
    val date=LocalDate.parse(day)
    val weekday=if(LocalLanguage.current=="ja")"月火水木金土日"[date.dayOfWeek.value-1].toString() else "周"+"一二三四五六日"[date.dayOfWeek.value-1]
    val label=date.monthValue.toString()+word("月","月")+date.dayOfMonth+word("日","日")+" · "+weekday
    val frame=when(t.id){
        "objects"->Modifier.padding(top=8.dp,bottom=2.dp,start=4.dp,end=4.dp)
        "journal"->Modifier.padding(top=18.dp,bottom=4.dp)
        else->Modifier.drawBehind{drawLine(t.ink,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}.padding(top=22.dp,bottom=8.dp)
    }
    Row(Modifier.fillMaxWidth().testTag("ledger-day-$day").then(frame),verticalAlignment=Alignment.CenterVertically) {
        YText(label,if(t.id=="journal")13 else 11,Modifier.weight(1f),color=if(t.id=="journal")t.warm else t.ink,letterSpacing=if(t.id=="magazine").5f else 0f)
        if(spent.minor>0)YText(word("支出 ","支出 ")+spent.display(),if(t.id=="journal")12 else 10,number=true,color=t.muted)
    }
}
