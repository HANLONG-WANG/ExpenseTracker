package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.*
import java.time.LocalDate

@Composable private fun weekday(date:LocalDate)=word(listOf("周一","周二","周三","周四","周五","周六","周日")[date.dayOfWeek.value-1],listOf("月曜日","火曜日","水曜日","木曜日","金曜日","土曜日","日曜日")[date.dayOfWeek.value-1])

@Composable internal fun RecurringToday(today:LocalDate,content:@Composable ColumnScope.()->Unit) {
    val t=LocalYohaku.current
    val frame=when(t.id){"magazine"->Modifier.background(t.ink).padding(8.dp);"journal"->Modifier.rotate(-2f).border(1.5.dp,t.warm,RoundedCornerShape(50)).padding(8.dp);else->Modifier.background(t.accent,RoundedCornerShape(16.dp)).padding(8.dp)}
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(62.dp).then(frame),horizontalAlignment=Alignment.CenterHorizontally) {
            val color=if(t.id=="journal")t.ink else t.surface
            YText(word("今天","今日"),10,color=color)
            YText(today.dayOfMonth.toString(),26,number=true,color=color,lineHeightMultiplier=1.1f)
            YText(weekday(today),9,color=color)
        }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp),content=content)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun RecurringCard(id:String,name:String,date:LocalDate,today:LocalDate,account:String,amount:String,known:Boolean,status:String,days:Int?,busy:Boolean,onConfirm:(()->Unit)?,primary:Boolean,onMore:()->Unit,onExpand:(()->Unit)?,showDetails:Boolean,details:@Composable ColumnScope.()->Unit) {
    val t=LocalYohaku.current
    val narrow=LocalConfiguration.current.screenWidthDp<=360||LocalDensity.current.fontScale>1.2f
    val shape=RoundedCornerShape(if(t.id=="objects")20.dp else 0.dp)
    val frame=when(t.id) {
        "objects"->Modifier.shadow(2.dp,shape).background(t.surface,shape).padding(12.dp)
        "journal"->Modifier.drawBehind{drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx())))}.padding(vertical=10.dp)
        else->Modifier.drawBehind{drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}.padding(vertical=12.dp)
    }
    Column(Modifier.fillMaxWidth().testTag("occurrence-$id").then(frame),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().then(if(onExpand!=null)Modifier.testTag("occurrence-details-$id").clickable(onClick=onExpand)else Modifier),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
            val dateFrame=when(t.id) {
                "magazine"->if(days==0)Modifier.background(t.ink)else if(days!=null&&days<0)Modifier.border(1.dp,t.warm)else Modifier
                "journal"->if(days==0)Modifier.rotate(-5f).border(1.5.dp,t.warm,RoundedCornerShape(50))else Modifier
                else->Modifier.background(if(days==0)Color(0xfff2d49b)else if(days!=null&&days<0)t.warm.copy(alpha=.15f)else t.soft,RoundedCornerShape(13.dp))
            }
            Column(Modifier.width(46.dp).then(dateFrame).padding(vertical=5.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                val color=if(t.id=="magazine"&&days==0)t.surface else t.ink
                YText(date.dayOfMonth.toString(),22,number=true,color=color)
                YText(if(date==today)word("今天","今日")else weekday(date),9,color=color)
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                if(narrow) {YText(name,if(t.id=="journal")16 else 13,bold=true);YText(amount,16,number=known)}
                else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                    YText(name,if(t.id=="journal")16 else 13,Modifier.weight(1f),bold=true)
                    YText(amount,if(known)17 else 11,number=known)
                }
                FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                if(days!=null&&days<=7) {
                    val urgency=when{days<0->word("已过期 ${-days} 天","${-days} 日超過");days==0->word("今天扣款","今日の支払い");else->word("$days 天后","$days 日後")}
                    val mark=when(t.id) {
                        "objects"->Modifier.background(if(days<0)t.warm else if(days==0)Color(0xfff2d49b)else t.soft,RoundedCornerShape(8.dp)).padding(horizontal=6.dp,vertical=2.dp)
                        "journal"->if(days<0)Modifier.rotate(-5f).border(1.dp,t.warm).padding(horizontal=3.dp)else if(days>0)Modifier.drawBehind{val path=Path();path.moveTo(0f,size.height);var x=0f;while(x<size.width){path.relativeQuadraticBezierTo(3.dp.toPx(),-3.dp.toPx(),6.dp.toPx(),0f);x+=6.dp.toPx()};drawPath(path,t.warm,style=Stroke(1.dp.toPx()))}else Modifier
                        else->Modifier
                    }
                    // Warm is reserved for what is already due; the coming week reads in plain ink.
                    YText(urgency,10,mark,color=if(t.id=="objects"&&days<0)t.surface else if(days>0)t.ink else t.warm)
                }
                    if(days!=null&&days<=7)YText("·",10,color=t.muted)
                    YText(status,10,Modifier.testTag("occurrence-status-$id"),color=t.muted)
                    YText("· $account",10,color=t.muted)
                }
            }
            if(onConfirm==null)YButton("⋯",onMore,Modifier.testTag("occurrence-more-$id").heightIn(min=36.dp),enabled=!busy,inline=true)
        }
        // Objects keeps its wide pill; the paper themes use a compact button so a row stays one glance tall.
        if(onConfirm!=null)Row(Modifier.fillMaxWidth().padding(start=if(t.id=="objects"||narrow)0.dp else 56.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            CompositionLocalProvider(LocalActionRow provides if(t.id=="objects")Modifier.weight(1f)else Modifier) {
                YButton(word("确认付款","支払を確認"),onConfirm,Modifier.heightIn(min=36.dp).testTag("confirm-occurrence-$id"),enabled=!busy,primary=primary,inline=true,compact=true)
            }
            YButton("⋯",onMore,Modifier.heightIn(min=36.dp).testTag("occurrence-more-$id"),enabled=!busy,inline=true)
        }
        if(showDetails)Column(Modifier.fillMaxWidth().padding(top=4.dp),verticalArrangement=Arrangement.spacedBy(8.dp),content=details)
    }
}

/** Folded list of settled, cancelled and withdrawn bills: one header line, its own look per theme. */
@Composable internal fun RecurringProcessedHeader(label:String,detail:String,open:Boolean,onToggle:()->Unit) {
    val t=LocalYohaku.current
    val frame=when(t.id) {
        "objects"->Modifier.background(t.soft,RoundedCornerShape(18.dp)).padding(horizontal=16.dp,vertical=14.dp)
        "journal"->Modifier.background(t.soft.copy(alpha=.6f)).drawBehind{drawRect(t.line,style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))))}.padding(horizontal=12.dp,vertical=12.dp)
        else->Modifier.drawBehind{drawLine(t.ink,Offset.Zero,Offset(size.width,0f),1.dp.toPx());drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}.padding(vertical=14.dp)
    }
    Row(Modifier.fillMaxWidth().padding(top=10.dp).testTag("recurring-processed-toggle").clickable(role=androidx.compose.ui.semantics.Role.Button,onClick=onToggle).then(frame),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        YText((if(t.id=="journal")"✓ " else "")+label,if(t.id=="journal")17 else 15)
        YText(detail,11,Modifier.weight(1f),color=t.muted)
        YText(if(open)word("收起","閉じる")else word("展开","開く"),11,color=if(t.id=="journal")t.warm else t.accent)
    }
}
