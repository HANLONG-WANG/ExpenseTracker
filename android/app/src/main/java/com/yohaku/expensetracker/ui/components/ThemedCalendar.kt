package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.*
import java.time.*

@Composable internal fun CalendarSurface(content:@Composable ColumnScope.()->Unit){
    val t=LocalYohaku.current;val journal=t.id=="journal";val objects=t.id=="objects"
    val shape=RoundedCornerShape(if(objects)18.dp else 0.dp)
    BoxWithConstraints(Modifier.testTag("shared-calendar").fillMaxWidth().padding(bottom=6.dp).then(if(journal)Modifier.rotate(-.5f) else Modifier)){
        Box(Modifier.matchParentSize().offset(x=if(objects)0.dp else 3.dp,y=if(t.id=="magazine")3.dp else 4.dp).background(if(objects)Color(0x25ba9766) else if(journal)Color(0x12927850) else t.soft,shape))
        val edge=Modifier.drawBehind{drawRoundRect(if(objects)Color(0xffe1c69f) else if(journal)Color(0xffb8b298) else t.ink,topLeft=Offset(.5.dp.toPx(),.5.dp.toPx()),size=androidx.compose.ui.geometry.Size(size.width-1.dp.toPx(),size.height-1.dp.toPx()),cornerRadius=androidx.compose.ui.geometry.CornerRadius(if(objects)18.dp.toPx() else 0f),style=Stroke(1.dp.toPx(),pathEffect=if(journal)PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())) else null))}
        Column(Modifier.testTag("calendar-frame").fillMaxWidth().background(if(objects)Color(0xfffff8e7) else if(journal)Color(0xfffff8e4) else t.surface,shape).then(edge).padding(start=15.dp,end=15.dp,top=if(objects)21.dp else 15.dp,bottom=15.dp),content=content)
        if(objects){
            Box(Modifier.offset(x=maxWidth*.22f,y=(-6).dp).size(8.dp,14.dp).background(Color(0xffbe9c71),RoundedCornerShape(4.dp)))
            Box(Modifier.align(Alignment.TopEnd).offset(x=-maxWidth*.22f,y=(-6).dp).size(8.dp,14.dp).background(Color(0xffbe9c71),RoundedCornerShape(4.dp)))
        }
    }
}

@Composable internal fun CalendarBody(label:String,month:YearMonth,selected:LocalDate,onMonth:(YearMonth)->Unit,onPick:(LocalDate)->Unit,onDone:()->Unit,timeFields:(@Composable ()->Unit)?=null){
    val t=LocalYohaku.current;val journal=t.id=="journal";val objects=t.id=="objects"
    val targetSize=if(journal)16 else if(objects)11 else 10
    var targetBaseline by remember(label){mutableFloatStateOf(0f)}
    val targetFrame=if(objects)Modifier.background(Color(0xffede0c7),RoundedCornerShape(6.dp)).padding(horizontal=9.dp,vertical=5.dp) else if(journal)Modifier.drawBehind{drawLine(t.accent,Offset(0f,targetBaseline+5.dp.toPx()),Offset(size.width,targetBaseline+5.dp.toPx()),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())))} else Modifier.drawBehind{drawLine(t.ink,Offset(1.dp.toPx(),0f),Offset(1.dp.toPx(),size.height),2.dp.toPx())}.padding(start=11.dp)
    YText(if(journal)"·"+label else label,targetSize,Modifier.then(targetFrame),color=if(journal)t.accent else t.ink,lineHeightMultiplier=1.8f,cssLineBox=true,onTextLayout={targetBaseline=it.getLineBaseline(0)})
    Spacer(Modifier.height(10.dp))
    YText(when(t.id){"magazine"->"DATE / PERSONAL NOTES";"journal"->word("把这一天圈起来","この日を、丸で囲もう");else->word("选一个日常的日子","日々の、ひとつの日を")},if(journal)13 else if(objects)12 else 7,color=if(journal)t.warm else if(objects)Color(0xffa58256) else t.ink,lineHeightMultiplier=if(journal)19f/13 else if(objects)17f/12 else 10f/7,letterSpacing=if(t.id=="magazine")1f else 0f,cssLineBox=true)
    if(!objects){Spacer(Modifier.height(if(journal)10.dp else 12.dp));CalendarRule(if(journal)t.line else t.ink,journal)}
    Spacer(Modifier.height(13.dp))
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
        MonthButton("←",word("上一月","前の月")){onMonth(month.minusMonths(1))}
        YText("${month.year} / ${month.monthValue}",18,number=true,lineHeightMultiplier=if(t.id=="magazine")21f/18 else 26f/18,cssLineBox=true)
        MonthButton("→",word("下一月","次の月")){onMonth(month.plusMonths(1))}
    }
    Spacer(Modifier.height(13.dp))
    val offset=month.atDay(1).dayOfWeek.value-1;val weeks=(offset+month.lengthOfMonth()+6)/7
    Column(verticalArrangement=Arrangement.spacedBy(5.dp)){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(5.dp)){
            val days=if(LocalLanguage.current=="ja")listOf("月","火","水","木","金","土","日") else listOf("一","二","三","四","五","六","日")
            days.forEach{day->Box(Modifier.weight(1f).heightIn(min=32.dp),contentAlignment=Alignment.Center){YText(day,if(journal)11 else 9,color=t.muted,lineHeightMultiplier=if(journal)16f/11 else 13f/9,cssLineBox=true)}}
        }
        repeat(weeks){week->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(5.dp)){repeat(7){column->
            val day=week*7+column-offset+1
            val date=if(day in 1..month.lengthOfMonth())month.atDay(day) else null
            CalendarDay(date,date==selected,onPick,Modifier.weight(1f))
        }}}
    }
    if(timeFields!=null){Spacer(Modifier.height(13.dp));timeFields()}
    Spacer(Modifier.height(15.dp));CalendarRule(t.line,journal);Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
        FooterButton(word("今天","今日")){onPick(LocalDate.now())}
        FooterButton(word("完成","完了"),Modifier.testTag("calendar-done"),onDone)
    }
}

@Composable private fun MonthButton(text:String,label:String,onClick:()->Unit){
    val t=LocalYohaku.current;val journal=t.id=="journal";val objects=t.id=="objects"
    val shape=RoundedCornerShape(if(objects)9.dp else if(journal)1.dp else 0.dp)
    Box(Modifier.size(29.dp).semantics{contentDescription=label}.clickable(role=Role.Button,onClick=onClick).then(if(journal)Modifier.rotate(-3f) else Modifier).then(if(objects)Modifier.drawBehind{drawRoundRect(Color(0x1bc6ab80),topLeft=Offset(0f,2.dp.toPx()),size=size,cornerRadius=androidx.compose.ui.geometry.CornerRadius(9.dp.toPx()))} else Modifier).background(if(objects)Color(0xffecd9bb) else if(journal)Color(0xffe8eadc) else Color.Transparent,shape).then(if(journal)Modifier.drawBehind{drawRect(t.line,topLeft=Offset(.5.dp.toPx(),.5.dp.toPx()),size=androidx.compose.ui.geometry.Size(size.width-1.dp.toPx(),size.height-1.dp.toPx()),style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))))} else Modifier),contentAlignment=Alignment.Center){YText(text,if(journal)14 else 13,color=if(objects)Color(0xffa98354) else t.ink,lineHeightMultiplier=if(journal)20f/14 else 19f/13,cssLineBox=true)}
}

private val inkCircle=GenericShape{size,_->
    val w=size.width;val h=size.height;val k=.5522848f
    moveTo(w*.4f,0f);lineTo(w*.52f,0f);cubicTo(w*.52f+w*.48f*k,0f,w,h*.48f*(1-k),w,h*.48f)
    lineTo(w,h*.65f);cubicTo(w,h*.65f+h*.35f*k,w-w*.35f*(1-k),h,w*.65f,h)
    lineTo(w*.5f,h);cubicTo(w*.5f*(1-k),h,0f,h-h*.5f*(1-k),0f,h*.5f)
    lineTo(0f,h*.4f);cubicTo(0f,h*.4f*(1-k),w*.4f*(1-k),0f,w*.4f,0f);close()
}
@Composable private fun CalendarDay(date:LocalDate?,chosen:Boolean,onPick:(LocalDate)->Unit,modifier:Modifier){
    val t=LocalYohaku.current;val journal=t.id=="journal";val objects=t.id=="objects"
    val shape=if(journal&&chosen)inkCircle else RoundedCornerShape(if(objects)8.dp else 0.dp)
    val frame=if(date==null)Modifier else if(objects)Modifier.drawBehind{drawRoundRect(if(chosen)Color(0x35906344) else Color(0x18c6ab80),topLeft=Offset(0f,if(chosen)2.dp.toPx() else 1.dp.toPx()),size=size,cornerRadius=androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))}.background(if(chosen)Color(0xffb77d57) else Color(0xfff0e5d0),shape) else if(journal){if(chosen)Modifier.border(1.dp,t.warm,shape) else Modifier} else Modifier.background(if(chosen)t.soft else Color.Transparent).drawBehind{val width=if(chosen)2.dp.toPx() else 1.dp.toPx();drawLine(if(chosen)t.ink else t.line,Offset(0f,size.height-width/2),Offset(size.width,size.height-width/2),width)}
    Box(modifier.heightIn(min=32.dp).then(if(journal&&chosen)Modifier.rotate(-8f) else Modifier).then(frame).then(if(date!=null)Modifier.semantics{contentDescription=date.toString()}.selectable(chosen,role=Role.Button,onClick={onPick(date)}) else Modifier),contentAlignment=Alignment.Center){
        if(date!=null)YText(date.dayOfMonth.toString(),if(journal)16 else if(objects)13 else 12,number=true,color=if(chosen)if(objects)Color(0xfffff8e7) else if(journal)t.warm else t.accent else t.ink,lineHeightMultiplier=if(journal)23f/16 else if(objects)19f/13 else 14f/12,cssLineBox=true)
    }
}
@Composable private fun FooterButton(text:String,modifier:Modifier=Modifier,onClick:()->Unit){
    val t=LocalYohaku.current;val journal=t.id=="journal";val objects=t.id=="objects"
    Box(modifier.clickable(role=Role.Button,onClick=onClick).then(if(objects)Modifier.background(Color(0xffede0c7),RoundedCornerShape(9.dp)) else Modifier).padding(horizontal=if(objects)12.dp else 8.dp,vertical=if(objects)8.dp else 6.dp)){
        YText(text,if(journal)13 else 11,lineHeightMultiplier=if(journal)19f/13 else 16f/11,cssLineBox=true)
    }
}
@Composable private fun CalendarRule(color:Color,dashed:Boolean){
    Canvas(Modifier.fillMaxWidth().height(1.dp)){drawLine(color,Offset(0f,.5.dp.toPx()),Offset(size.width,.5.dp.toPx()),1.dp.toPx(),pathEffect=if(dashed)PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())) else null)}
}
