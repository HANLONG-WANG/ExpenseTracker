package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yohaku.expensetracker.data.db.EventEntity
import com.yohaku.expensetracker.data.repository.BookSnapshot
import com.yohaku.expensetracker.data.repository.budgetLimit
import com.yohaku.expensetracker.data.repository.dailyExpense
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.YText
import com.yohaku.expensetracker.ui.theme.*
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

private data class CalendarDay(val date:LocalDate,val spent:Long,val level:Int,val over:Boolean,val fraction:Float,val most:Boolean)

/** List / calendar switch at the top of the ledger. */
@Composable internal fun LedgerViewTabs(calendar:Boolean,onChange:(Boolean)->Unit) {
    val t=LocalYohaku.current
    val tabs=listOf(false to (word("列表","リスト") to "LIST"),true to (word("日历","カレンダー") to "CALENDAR"))
    when(t.id){
        "journal"->Row(Modifier.fillMaxWidth().testTag("ledger-view-tabs"),horizontalArrangement=Arrangement.spacedBy(10.dp)){
            tabs.forEach{(value,label)->
                val on=value==calendar;val shape=RoundedCornerShape(3.dp,5.dp,2.dp,4.dp)
                YText(label.first,13,Modifier.testTag("ledger-view-"+label.second.lowercase()).rotate(if(on)-2f else 0f)
                    .then(if(on)Modifier.background(Color(0x6be8be6e),shape) else Modifier.drawBehind{drawRoundRect(t.line,cornerRadius=CornerRadius(3.dp.toPx()),style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))))})
                    .clickable(role=Role.Tab){onChange(value)}.padding(horizontal=13.dp,vertical=4.dp),color=if(on)t.ink else t.muted,lineHeightMultiplier=20f/13,cssLineBox=true)
            }
        }
        "objects"->Row(Modifier.fillMaxWidth().testTag("ledger-view-tabs").background(t.soft,RoundedCornerShape(16.dp)).padding(4.dp)){
            tabs.forEach{(value,label)->
                val on=value==calendar;val shape=RoundedCornerShape(12.dp)
                Box(Modifier.weight(1f).testTag("ledger-view-"+label.second.lowercase()).then(if(on)Modifier.background(Color(0xff8f4c34),shape).padding(bottom=3.dp).background(t.accent,shape) else Modifier)
                    .clickable(role=Role.Tab){onChange(value)}.padding(vertical=9.dp),contentAlignment=Alignment.Center){
                    YText(label.first,12,color=if(on)Color.White else t.muted,weight=if(on)FontWeight.Medium else FontWeight.Normal,lineHeightMultiplier=17f/12,cssLineBox=true)
                }
            }
        }
        else->Row(Modifier.fillMaxWidth().testTag("ledger-view-tabs").drawBehind{drawLine(t.line,Offset(0f,size.height-.5.dp.toPx()),Offset(size.width,size.height-.5.dp.toPx()),1.dp.toPx())},horizontalArrangement=Arrangement.spacedBy(22.dp)){
            tabs.forEach{(value,label)->
                val on=value==calendar
                Row(Modifier.testTag("ledger-view-"+label.second.lowercase()).clickable(role=Role.Tab){onChange(value)}
                    .then(if(on)Modifier.drawBehind{drawLine(t.ink,Offset(0f,size.height-1.dp.toPx()),Offset(size.width,size.height-1.dp.toPx()),2.dp.toPx())} else Modifier).padding(bottom=9.dp),verticalAlignment=Alignment.Bottom){
                    YText(label.first,12,color=if(on)t.ink else t.muted,lineHeightMultiplier=17f/12,cssLineBox=true)
                    YText(label.second,7,Modifier.padding(start=5.dp,bottom=2.dp),color=t.muted,letterSpacing=1.6f,lineHeightMultiplier=10f/7,cssLineBox=true)
                }
            }
        }
    }
}

/** Month grid of net spending; the chosen day's records open underneath. */
@Composable internal fun LedgerCalendar(s:BookSnapshot,events:List<EventEntity>,budgetCategory:String?,budgetEnabled:Boolean,today:LocalDate,onSelect:(EventEntity)->Unit,onStartOn:(String)->Unit) {
    val t=LocalYohaku.current
    val current=YearMonth.from(today)
    var monthText by rememberSaveable(s.book.id){mutableStateOf(current.toString())}
    val month=YearMonth.parse(monthText)
    var selectedText by rememberSaveable(s.book.id){mutableStateOf<String?>(today.toString())}
    fun changeMonth(next:YearMonth){if(next>current)return;monthText=next.toString();selectedText=if(next==current)today.toString() else null}
    val currency=Currency.valueOf(s.book.currency)
    val spent=remember(s,events){s.dailyExpense(events)}
    val limit=if(budgetEnabled)s.budgetLimit(month.toString(),budgetCategory) else null
    val dailyBudget=limit?.takeIf{it>0}?.let{it.toDouble()/month.lengthOfMonth()}
    val monthDays=(1..month.lengthOfMonth()).map{month.atDay(it)}
    val amounts=monthDays.associateWith{spent[it.toString()]?:0L}
    val total=amounts.values.sum()
    val max=amounts.values.maxOrNull()?.takeIf{it>0}
    val days=monthDays.map{d->
        val n=amounts.getValue(d)
        val ratio=if(n<=0)0.0 else dailyBudget?.let{n/it}?:(n.toDouble()/max!!)
        val level=when{n<=0->0;dailyBudget!=null->if(ratio<.6)1 else if(ratio<=1)2 else 3;ratio<.34->1;ratio<.67->2;else->3}
        CalendarDay(d,n,level,dailyBudget!=null&&ratio>1,ratio.coerceAtMost(1.0).toFloat(),max!=null&&n==max)
    }
    val elapsed=when{month<current->month.lengthOfMonth();month==current->today.dayOfMonth;else->0}
    val average=if(elapsed>0)total/elapsed else 0L
    Column(Modifier.fillMaxWidth().testTag("ledger-calendar")){
        CalendarMonthHeader(month,month<current,{changeMonth(month.minusMonths(1))},{changeMonth(month.plusMonths(1))})
        CalendarSummary(currency,total,average,limit?.let{it-total},days.filter{it.most}.firstOrNull()?.takeIf{limit==null},budgetEnabled&&limit!=null)
        val sundayFirst=LocalLanguage.current=="ja"
        val weekdays=if(sundayFirst)listOf(DayOfWeek.SUNDAY)+DayOfWeek.entries.dropLast(1) else DayOfWeek.entries
        CalendarWeekdays(weekdays)
        val lead=(month.atDay(1).dayOfWeek.value-weekdays.first().value+7)%7
        val cells=List(lead){null}+days+List((7-(lead+days.size)%7)%7){null}
        var drag by remember{mutableFloatStateOf(0f)}
        Column(Modifier.fillMaxWidth().testTag("calendar-grid").pointerInput(monthText){
            detectHorizontalDragGestures(onDragEnd={if(drag<-120f)changeMonth(month.plusMonths(1)) else if(drag>120f)changeMonth(month.minusMonths(1));drag=0f},onDragCancel={drag=0f}){_,delta->drag+=delta}
        }.then(if(t.id=="journal")Modifier.drawBehind{drawLine(t.ink,Offset.Zero,Offset(size.width,0f),1.5.dp.toPx())} else Modifier),verticalArrangement=Arrangement.spacedBy(if(t.id=="objects")5.dp else 0.dp)){
            cells.chunked(7).forEach{week->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(if(t.id=="objects")5.dp else 0.dp)){
                    week.forEachIndexed{i,day->
                        Box(Modifier.weight(1f)){CalendarCell(currency,day,i==0,day!=null&&day.date>today,day?.date==today,day?.date?.toString()==selectedText){selectedText=day!!.date.toString()}}
                    }
                }
            }
        }
        CalendarLegend(dailyBudget?.let{Money(currency,it.toLong()).display()})
        selectedText?.let{LocalDate.parse(it)}?.takeIf{YearMonth.from(it)==month}?.let{day->
            CalendarDayPanel(s,day,events.filter{it.date==day.toString()},Money(currency,amounts[day]?:0L),onSelect,{onStartOn(day.toString())})
        } ?: YText(word("点选日期，查看当天的流水。","日付を選ぶと、その日の記録が表示されます。"),if(t.id=="journal")13 else 11,Modifier.padding(top=26.dp),color=t.muted)
    }
}

/** Integer major units; ten thousand and above as 1.2万 so seven columns fit narrow screens. */
private fun compactAmount(currency:Currency,minor:Long):String {
    val major=Money(currency,minor).decimal.abs()
    val text=if(major>=java.math.BigDecimal(10000))major.divide(java.math.BigDecimal(10000)).setScale(1,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()+"万"
        else java.text.DecimalFormat("#,##0").apply{roundingMode=RoundingMode.HALF_UP}.format(major)
    return if(minor<0)"−$text" else text
}

@Composable private fun monthName(month:YearMonth)=if(LocalLanguage.current=="ja")"${month.monthValue}月号" else listOf("一","二","三","四","五","六","七","八","九","十","十一","十二")[month.monthValue-1]+"月号"

@Composable private fun CalendarMonthHeader(month:YearMonth,nextEnabled:Boolean,onPrevious:()->Unit,onNext:()->Unit) {
    val t=LocalYohaku.current
    @Composable fun arrow(label:String,tag:String,enabled:Boolean,size:Int,color:Color,onClick:()->Unit)=
        YText(label,size,Modifier.testTag(tag).clickable(enabled=enabled,role=Role.Button,onClick=onClick).padding(horizontal=6.dp,vertical=4.dp),color=if(enabled)color else t.line,lineHeightMultiplier=1.3f,cssLineBox=true)
    when(t.id){
        "journal"->Row(Modifier.fillMaxWidth().padding(top=20.dp),verticalAlignment=Alignment.Bottom){
            YText("${month.monthValue}月",36,Modifier.testTag("calendar-month"),lineHeightMultiplier=1.15f,cssLineBox=true)
            YText(month.year.toString(),12,Modifier.padding(start=8.dp,bottom=6.dp),color=t.muted,lineHeightMultiplier=1.4f,cssLineBox=true)
            Spacer(Modifier.weight(1f))
            arrow("←","calendar-prev",true,17,t.warm,onPrevious);arrow("→","calendar-next",nextEnabled,17,t.warm,onNext)
        }
        "objects"->Row(Modifier.fillMaxWidth().padding(top=18.dp).background(t.line,RoundedCornerShape(20.dp)).padding(bottom=2.dp).background(t.surface,RoundedCornerShape(20.dp)).padding(8.dp),verticalAlignment=Alignment.CenterVertically){
            @Composable fun round(label:String,tag:String,enabled:Boolean,onClick:()->Unit)=Box(Modifier.size(32.dp).testTag(tag).background(t.soft.copy(alpha=if(enabled)1f else .4f),RoundedCornerShape(12.dp)).clickable(enabled=enabled,role=Role.Button,onClick=onClick),contentAlignment=Alignment.Center){YText(label,14,color=if(enabled)t.ink else t.muted,lineHeightMultiplier=1.2f,cssLineBox=true)}
            round("‹","calendar-prev",true,onPrevious)
            YText("${month.year}年${month.monthValue}月",15,Modifier.weight(1f).testTag("calendar-month"),weight=FontWeight.Medium,textAlign=TextAlign.Center,lineHeightMultiplier=1.4f,cssLineBox=true)
            round("›","calendar-next",nextEnabled,onNext)
        }
        else->Row(Modifier.fillMaxWidth().padding(top=22.dp),verticalAlignment=Alignment.Bottom){
            YText(month.monthValue.toString().padStart(2,'0'),54,Modifier.testTag("calendar-month"),number=true,lineHeightMultiplier=.95f,cssLineBox=true)
            Column(Modifier.padding(start=12.dp,bottom=4.dp)){
                YText(month.month.name+" · "+month.year,8,color=t.muted,letterSpacing=2f,lineHeightMultiplier=1.4f,cssLineBox=true)
                YText(monthName(month),15,Modifier.padding(top=3.dp),lineHeightMultiplier=1.3f,cssLineBox=true)
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.padding(bottom=2.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){arrow("←","calendar-prev",true,15,t.ink,onPrevious);arrow("→","calendar-next",nextEnabled,15,t.ink,onNext)}
        }
    }
}

@Composable private fun CalendarSummary(currency:Currency,total:Long,average:Long,remaining:Long?,most:CalendarDay?,budget:Boolean) {
    val t=LocalYohaku.current
    fun money(n:Long)=Money(currency,n).display()
    val third=if(remaining!=null)word("预算余","予算残") to money(remaining) else word("最多的一天","最も多い日") to (most?.let{"${it.date.dayOfMonth}"+word("日","日")} ?: "—")
    when(t.id){
        "journal"->{
            val mark=SpanStyle(background=Color(0x8ce8be6e))
            val line=buildAnnotatedString{
                append(word("这个月花了 ","今月の支出は "));withStyle(mark){append(money(total))};append(word("，平均每天 ","、1日平均 "));append(money(average));append(word("。","。"))
                if(remaining!=null){append("\n"+word("预算还剩 ","予算の残りは ")+money(remaining)+word("，","、"));withStyle(SpanStyle(color=t.warm)){append(word("红圈","赤丸"))};append(word("是超出日均的日子。","は1日平均を超えた日。"))}
                else if(most!=null)append("\n"+word("花得最多的是 ","いちばん多いのは ")+"${most.date.dayOfMonth}"+word("日（","日（")+money(most.spent)+word("）。","）。"))
            }
            Text(line,Modifier.fillMaxWidth().padding(top=8.dp).testTag("calendar-summary"),color=t.ink,fontFamily=t.body,fontSize=13.5.sp,lineHeight=27.sp)
        }
        "objects"->Row(Modifier.fillMaxWidth().padding(top=10.dp).height(IntrinsicSize.Min).testTag("calendar-summary"),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            @Composable fun chip(weight:Float,label:String,value:String,progress:Float?=null)=Column(Modifier.weight(weight).fillMaxHeight().background(t.line,RoundedCornerShape(16.dp)).padding(bottom=2.dp).background(t.surface,RoundedCornerShape(16.dp)).padding(start=10.dp,end=10.dp,top=10.dp,bottom=11.dp)){
                YText(label,8,color=t.muted,lineHeightMultiplier=1.3f,cssLineBox=true)
                BasicText(value,Modifier.padding(top=5.dp),style=TextStyle(color=t.ink,fontFamily=t.number,fontWeight=FontWeight.Medium),maxLines=1,autoSize=TextAutoSize.StepBased(minFontSize=10.sp,maxFontSize=15.sp))
                if(progress!=null)Box(Modifier.padding(top=7.dp).fillMaxWidth().height(5.dp).background(t.soft,RoundedCornerShape(3.dp))){Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Color(0xffd7a46b),RoundedCornerShape(3.dp)))}
            }
            chip(1.25f,word("本月支出","今月の支出"),money(total),if(budget&&remaining!=null)(total.toFloat()/(total+remaining).coerceAtLeast(1)).coerceIn(0f,1f) else null)
            chip(1f,word("日均","1日平均"),money(average))
            chip(1f,third.first,third.second)
        }
        else->Row(Modifier.fillMaxWidth().padding(top=18.dp).height(IntrinsicSize.Min).testTag("calendar-summary").drawBehind{
            drawLine(t.ink,Offset.Zero,Offset(size.width,0f),1.dp.toPx());drawLine(t.line,Offset(0f,size.height-.5.dp.toPx()),Offset(size.width,size.height-.5.dp.toPx()),1.dp.toPx())
        }){
            listOf(Triple(1.3f,word("本月支出 · ","今月の支出 · ")+currency.name,money(total)),Triple(1f,word("日均","1日平均"),money(average)),Triple(1f,third.first,third.second)).forEachIndexed{i,(weight,label,value)->
                Column(Modifier.weight(weight).fillMaxHeight().then(if(i>0)Modifier.drawBehind{drawLine(t.line,Offset.Zero,Offset(0f,size.height),1.dp.toPx())}.padding(start=10.dp) else Modifier).padding(top=11.dp,bottom=10.dp)){
                    YText(label,8,color=t.muted,letterSpacing=1f,lineHeightMultiplier=1.3f,cssLineBox=true)
                    BasicText(value,Modifier.padding(top=5.dp,end=4.dp),style=TextStyle(color=t.ink,fontFamily=t.number),maxLines=1,autoSize=TextAutoSize.StepBased(minFontSize=10.sp,maxFontSize=(if(i==0)18 else 14).sp))
                }
            }
        }
    }
}

@Composable private fun CalendarWeekdays(days:List<DayOfWeek>) {
    val t=LocalYohaku.current
    val names=if(LocalLanguage.current=="ja")"月火水木金土日" else "一二三四五六日"
    Row(Modifier.fillMaxWidth().padding(top=if(t.id=="magazine")16.dp else 14.dp).then(if(t.id=="magazine")Modifier.drawBehind{drawLine(t.ink,Offset(0f,size.height-.5.dp.toPx()),Offset(size.width,size.height-.5.dp.toPx()),1.dp.toPx())} else Modifier).padding(bottom=if(t.id=="objects")6.dp else if(t.id=="journal")4.dp else 7.dp),
        horizontalArrangement=Arrangement.spacedBy(if(t.id=="objects")5.dp else 0.dp)){
        days.forEach{d->
            val weekend=d==DayOfWeek.SATURDAY||d==DayOfWeek.SUNDAY
            YText(names[d.value-1].toString(),when(t.id){"journal"->12;"objects"->10;else->9},Modifier.weight(1f),color=if(weekend)(if(t.id=="objects")t.accent else t.warm) else if(t.id=="journal")t.ink else t.muted,
                textAlign=TextAlign.Center,letterSpacing=if(t.id=="magazine")1f else 0f,lineHeightMultiplier=1.4f,cssLineBox=true)
        }
    }
}

@Composable private fun CellAmount(text:String,color:Color,max:Int,modifier:Modifier,align:TextAlign,weight:FontWeight=FontWeight.Normal) {
    val t=LocalYohaku.current
    BasicText(text,modifier,style=TextStyle(color=color,fontFamily=t.number,fontWeight=weight,textAlign=align,letterSpacing=if(t.id=="journal")(-.5).sp else 0.sp),maxLines=1,
        autoSize=TextAutoSize.StepBased(minFontSize=7.sp,maxFontSize=max.sp))
}

@Composable private fun CalendarCell(currency:Currency,day:CalendarDay?,firstColumn:Boolean,future:Boolean,today:Boolean,selected:Boolean,onClick:()->Unit) {
    val t=LocalYohaku.current
    val amount=day?.takeIf{it.spent!=0L&&!future}?.let{compactAmount(currency,it.spent)}
    val dashed=with(LocalDensity.current){PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))}
    val click=if(day==null)Modifier else Modifier.testTag("calendar-day-"+day.date).clickable(role=Role.Button,onClick=onClick)
    when(t.id){
        "journal"->{
            val rule=Color(0xffb9b09a)
            Box(Modifier.fillMaxWidth().height(56.dp).then(if(day==null)Modifier.background(t.line.copy(alpha=.18f)) else if(selected)Modifier.background(Color(0xd9fff8d6)) else Modifier).drawBehind{
                val w=1.dp.toPx()
                drawLine(rule,Offset(size.width-w/2,0f),Offset(size.width-w/2,size.height),w,pathEffect=dashed)
                drawLine(rule,Offset(0f,size.height-w/2),Offset(size.width,size.height-w/2),w,pathEffect=dashed)
                if(firstColumn)drawLine(rule,Offset(w/2,0f),Offset(w/2,size.height),w,pathEffect=dashed)
                if(day?.over==true&&amount!=null){
                    // A quick pen loop around the figure, open at the top like a hand-drawn circle.
                    val l=3.dp.toPx();val r=size.width-3.dp.toPx();val top=size.height-28.dp.toPx();val bottom=size.height-2.dp.toPx();val mid=(top+bottom)/2
                    drawPath(Path().apply{moveTo(l+3.dp.toPx(),mid);cubicTo(l,top,r,top-2.dp.toPx(),r-1.dp.toPx(),mid-1.dp.toPx());cubicTo(r+1.dp.toPx(),bottom+1.dp.toPx(),l+4.dp.toPx(),bottom,l+1.dp.toPx(),mid+1.dp.toPx());cubicTo(l,mid-5.dp.toPx(),l+9.dp.toPx(),top+2.dp.toPx(),l+17.dp.toPx(),top+1.dp.toPx())},t.warm,style=Stroke(1.3.dp.toPx(),cap=StrokeCap.Round))
                }
            }.then(click)){
                if(day!=null){
                    YText(day.date.dayOfMonth.toString(),11,Modifier.padding(start=4.dp,top=3.dp),color=if(today)t.ink else if(future)Color(0xffc5bda8) else t.muted,lineHeightMultiplier=1.3f,cssLineBox=true)
                    if(today)YText("★",11,Modifier.align(Alignment.TopEnd).padding(end=4.dp,top=2.dp),color=t.warm,lineHeightMultiplier=1.3f,cssLineBox=true)
                    else if(day.most&&amount!=null)Box(Modifier.align(Alignment.TopEnd).padding(end=1.dp,top=2.dp).rotate(14f).size(22.dp).border(1.3.dp,t.warm.copy(alpha=.85f),CircleShape),contentAlignment=Alignment.Center){
                        YText("最多",7,color=t.warm.copy(alpha=.85f),lineHeightMultiplier=1.1f,cssLineBox=true)
                    }
                    if(amount!=null)CellAmount(amount,if(day.over)t.warm else t.ink,13,Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start=4.dp,end=4.dp,bottom=8.dp),TextAlign.Center)
                    if(selected)Box(Modifier.align(Alignment.TopCenter).offset(y=(-6).dp).fillMaxWidth().padding(horizontal=2.dp).height(12.dp).rotate(-3f).background(Color(0x8c96beb4)))
                }
            }
        }
        "objects"->{
            val shape=RoundedCornerShape(12.dp)
            if(day==null){Spacer(Modifier.fillMaxWidth().height(54.dp));return}
            val fill=if(future)Color.Transparent else when(day.level){1->Color(0xfffbf0de);2->Color(0xfff5e0bf);3->Color(0xffeccaa0);else->t.surface}
            val lift=if(selected)Modifier.offset(y=(-2).dp) else Modifier
            Box(Modifier.fillMaxWidth().height(54.dp).then(lift)
                .then(if(future)Modifier.drawBehind{drawRoundRect(t.line,cornerRadius=CornerRadius(12.dp.toPx()),style=Stroke(1.2.dp.toPx(),pathEffect=dashed))}
                    else Modifier.background(if(selected)Color(0xffd9b78f) else t.line,shape).padding(bottom=if(selected)4.dp else 2.dp).background(fill,shape))
                .then(if(selected)Modifier.border(2.dp,t.accent,shape) else Modifier).then(click)){
                val number=day.date.dayOfMonth.toString()
                if(today)YText(number,10,Modifier.padding(start=5.dp,top=5.dp).background(t.accent,RoundedCornerShape(8.dp)).padding(horizontal=5.dp),color=Color.White,lineHeightMultiplier=1.4f,cssLineBox=true)
                else YText(number,10,Modifier.padding(start=6.dp,top=5.dp),color=t.muted,lineHeightMultiplier=1.4f,cssLineBox=true)
                if(!future&&day.level>0)Row(Modifier.align(Alignment.TopEnd).padding(end=5.dp,top=7.dp),horizontalArrangement=Arrangement.spacedBy(1.5.dp)){repeat(day.level){Box(Modifier.size(5.dp).background(Color(0xffc8925a),CircleShape))}}
                if(amount!=null)CellAmount(amount,if(day.over)t.accent else t.ink,11,Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start=3.dp,end=3.dp,bottom=6.dp),TextAlign.Center,FontWeight.Medium)
            }
        }
        else->{
            val track=if(selected)t.bg.copy(alpha=.18f) else t.ink.copy(alpha=.09f)
            val trackLine=if(selected)t.bg.copy(alpha=.4f) else t.ink.copy(alpha=.22f)
            val bar=if(selected)(if(day?.over==true)Color(0xffe8a28f) else t.bg) else if(day?.over==true)t.warm else t.ink
            Box(Modifier.fillMaxWidth().height(56.dp).then(if(selected)Modifier.background(t.ink) else Modifier)
                .then(if(day!=null)Modifier.drawBehind{drawLine(t.line,Offset(0f,size.height-.5.dp.toPx()),Offset(size.width,size.height-.5.dp.toPx()),1.dp.toPx())} else Modifier).then(click)){
                if(day!=null){
                    val number=day.date.dayOfMonth.toString().padStart(2,'0')
                    YText(number,10,Modifier.padding(start=4.dp,top=6.dp).then(if(today)Modifier.drawBehind{drawLine(t.accent,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())} else Modifier),number=true,
                        color=if(selected)t.soft else if(today)t.ink else if(future)t.line else t.muted,lineHeightMultiplier=1.2f,cssLineBox=true)
                    if(today)YText("TODAY",6,Modifier.align(Alignment.TopEnd).padding(end=4.dp,top=8.dp),color=if(selected)t.soft else t.accent,letterSpacing=1f,lineHeightMultiplier=1.2f,cssLineBox=true)
                    if(amount!=null){
                        CellAmount(amount,if(selected)(if(day.over)Color(0xfff3c4b6) else t.bg) else if(day.over)t.warm else t.ink,12,Modifier.align(Alignment.BottomEnd).fillMaxWidth().padding(start=4.dp,end=4.dp,bottom=13.dp),TextAlign.End)
                        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start=4.dp,end=4.dp,bottom=6.dp).height(4.dp).background(track).border(.5.dp,trackLine)){
                            Box(Modifier.fillMaxWidth(day.fraction.coerceAtLeast(.04f)).fillMaxHeight().background(bar))
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun CalendarLegend(dailyBudget:String?) {
    val t=LocalYohaku.current
    val size=when(t.id){"journal"->11;"objects"->9;else->8}
    Row(Modifier.fillMaxWidth().padding(top=if(t.id=="objects")12.dp else 10.dp).testTag("calendar-legend"),horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.CenterVertically){
        when(t.id){
            "journal"->{
                YText("★ "+word("今天","今日"),size,color=t.muted,lineHeightMultiplier=1.4f,cssLineBox=true)
                if(dailyBudget!=null)YText("◯ "+word("超出日均 ","1日平均超え ")+dailyBudget,size,color=t.warm,lineHeightMultiplier=1.4f,cssLineBox=true)
                YText(word("胶带＝正在看的那天","テープ＝表示中の日"),size,color=t.muted,lineHeightMultiplier=1.4f,cssLineBox=true)
            }
            "objects"->{
                listOf(Color(0xfffbf0de) to word("少","少"),Color(0xfff5e0bf) to word("中","中"),Color(0xffeccaa0) to (if(dailyBudget!=null)word("超出日均 ","1日平均超え ")+dailyBudget else word("多","多"))).forEach{(color,label)->
                    Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(12.dp).background(color,RoundedCornerShape(4.dp)).border(1.dp,t.line,RoundedCornerShape(4.dp)));YText(label,size,Modifier.padding(start=4.dp),color=t.muted,lineHeightMultiplier=1.4f,cssLineBox=true)}
                }
            }
            else->{
                Row(verticalAlignment=Alignment.CenterVertically){
                    Box(Modifier.size(22.dp,4.dp).background(t.ink.copy(alpha=.09f)).border(.5.dp,t.ink.copy(alpha=.22f))){Box(Modifier.fillMaxWidth(.55f).fillMaxHeight().background(t.ink))}
                    YText(if(dailyBudget!=null)word("满格＝日均预算 ","満タン＝1日の予算 ")+dailyBudget else word("满格＝本月最多的一天","満タン＝今月最多の日"),size,Modifier.padding(start=5.dp),color=t.muted,lineHeightMultiplier=1.4f,cssLineBox=true)
                }
                if(dailyBudget!=null)Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(22.dp,4.dp).background(t.warm));YText(word("超出","超過"),size,Modifier.padding(start=5.dp),color=t.muted,lineHeightMultiplier=1.4f,cssLineBox=true)}
            }
        }
    }
}

@Composable private fun CalendarDayPanel(s:BookSnapshot,day:LocalDate,events:List<EventEntity>,spent:Money,onSelect:(EventEntity)->Unit,onAdd:()->Unit) {
    val t=LocalYohaku.current
    val weekday=if(LocalLanguage.current=="ja")"月火水木金土日"[day.dayOfWeek.value-1].toString()+"曜" else "周"+"一二三四五六日"[day.dayOfWeek.value-1]
    val title="${day.monthValue}月${day.dayOfMonth}日"
    val rows:@Composable ()->Unit={
        if(events.isEmpty())YText(word("这一天没有记录。","この日の記録はありません。"),if(t.id=="journal")13 else 11,Modifier.padding(vertical=16.dp),color=t.muted)
        events.forEach{EventRow(s,it,{onSelect(it)},showDate=false,last=it===events.last())}
    }
    when(t.id){
        "journal"->Box(Modifier.fillMaxWidth().padding(top=30.dp).testTag("calendar-day-panel")){
            Column(Modifier.fillMaxWidth().rotate(-.6f).drawBehind{
                drawRect(Color(0x1f78643c),Offset(0f,2.dp.toPx()),size)
            }.background(Color(0xfffbf5df)).padding(start=16.dp,end=16.dp,top=24.dp,bottom=16.dp)){
                Row(verticalAlignment=Alignment.Bottom){
                    YText("$title $weekday",17,Modifier.weight(1f),color=t.warm,lineHeightMultiplier=1.4f,cssLineBox=true)
                    Text(buildAnnotatedString{append(word("花了 ","支出 "));withStyle(SpanStyle(background=Color(0x8ce8be6e))){append(spent.display())}},color=t.ink,fontFamily=t.body,fontSize=13.sp)
                }
                rows()
                YText(word("→ 在这一天记一笔","→ この日に記録する"),13,Modifier.align(Alignment.End).testTag("calendar-add-day").clickable(role=Role.Button,onClick=onAdd).padding(top=10.dp),color=t.warm,lineHeightMultiplier=1.4f,cssLineBox=true)
            }
            Box(Modifier.align(Alignment.TopCenter).offset(y=(-9).dp).size(84.dp,18.dp).rotate(2f).background(Color(0x8c96beb4)))
        }
        "objects"->Box(Modifier.fillMaxWidth().padding(top=34.dp).testTag("calendar-day-panel")){
            Column(Modifier.fillMaxWidth().background(t.line,RoundedCornerShape(22.dp)).padding(bottom=4.dp).background(Color(0xfff6ecdc),RoundedCornerShape(22.dp)).padding(start=12.dp,end=12.dp,top=24.dp,bottom=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                rows()
                Box(Modifier.fillMaxWidth().testTag("calendar-add-day").background(t.soft,RoundedCornerShape(14.dp)).clickable(role=Role.Button,onClick=onAdd).padding(11.dp),contentAlignment=Alignment.Center){
                    YText(word("＋ 记在这一天","＋ この日に記録"),12,color=t.accent,lineHeightMultiplier=1.4f,cssLineBox=true)
                }
            }
            YText("${day.monthValue}/${day.dayOfMonth} $weekday",11,Modifier.offset(x=16.dp,y=(-13).dp).background(Color(0xff8f4c34),RoundedCornerShape(10.dp)).padding(bottom=2.dp).background(t.accent,RoundedCornerShape(10.dp)).padding(horizontal=12.dp,vertical=5.dp),color=Color.White,lineHeightMultiplier=1.4f,cssLineBox=true)
            YText(word("支出 ","支出 ")+spent.display(),10,Modifier.align(Alignment.TopEnd).offset(x=(-16).dp,y=(-10).dp).background(t.soft,RoundedCornerShape(9.dp)).padding(horizontal=10.dp,vertical=4.dp),number=true,lineHeightMultiplier=1.4f,cssLineBox=true)
        }
        else->Column(Modifier.fillMaxWidth().padding(top=8.dp).testTag("calendar-day-panel")){
            LedgerDayHeader(day.toString(),spent)
            rows()
            Box(Modifier.fillMaxWidth().padding(top=18.dp).testTag("calendar-add-day").border(1.dp,t.ink).clickable(role=Role.Button,onClick=onAdd).padding(vertical=11.dp),contentAlignment=Alignment.Center){
                YText(word("＋ 记在 ","＋ ")+title+word("","に記録"),11,letterSpacing=1f,lineHeightMultiplier=1.4f,cssLineBox=true)
            }
        }
    }
}
