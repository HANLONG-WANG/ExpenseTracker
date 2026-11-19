package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.*
import java.time.LocalDate

@Composable internal fun FutureTimelineRow(id:String,date:String,name:String,account:String,amount:String,note:String,atRisk:Boolean=false,last:Boolean=false,details:@Composable ColumnScope.()->Unit={}) {
    val t=LocalYohaku.current;val journal=t.id=="journal";val objects=t.id=="objects"
    val narrow=LocalConfiguration.current.screenWidthDp<=360
    val day=LocalDate.parse(date)
    val frame=if(objects)Modifier.drawBehind{drawRoundRect(Color(0x24c6aa80),Offset(0f,3.dp.toPx()),cornerRadius=androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()))}.background(if(atRisk)Color(0xfffff3dd)else Color(0xfffff8e8),RoundedCornerShape(14.dp)).border(1.dp,if(atRisk)Color(0xffd7b185)else Color(0xffe3cda8),RoundedCornerShape(14.dp)).padding(horizontal=11.dp,vertical=12.dp)
        else Modifier.drawBehind{if(journal||!last)drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=if(journal)PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))else null)}.padding(vertical=14.dp)
    Column(Modifier.fillMaxWidth().testTag("future-occurrence-$id").then(if(atRisk&&!objects)Modifier.drawBehind{drawLine(t.warm,Offset.Zero,Offset(0f,size.height),1.dp.toPx())}.padding(start=9.dp)else Modifier).then(frame)) {
        Row(horizontalArrangement=Arrangement.spacedBy(if(narrow)8.dp else 11.dp),verticalAlignment=Alignment.Top) {
            // Date block reads like a calendar tab: month, day, weekday — never the bare "MM\ndd" stack.
            val dateFrame=when(t.id) {
                "magazine"->Modifier.drawBehind{drawLine(t.ink,Offset(size.width,0f),Offset(size.width,size.height),1.dp.toPx())}.padding(end=8.dp)
                "journal"->Modifier.rotate(-2f)
                else->Modifier.background(Color(0xffecddc0),RoundedCornerShape(9.dp)).padding(vertical=5.dp)
            }
            Column(Modifier.width(if(narrow)38.dp else 42.dp).then(dateFrame),horizontalAlignment=if(t.id=="magazine")Alignment.Start else Alignment.CenterHorizontally) {
                val dateInk=if(objects)Color(0xff9c7650)else if(journal)t.warm else t.ink
                YText(word("${day.monthValue}月","${day.monthValue}月"),9,color=if(objects)dateInk else t.muted,lineHeightMultiplier=1.3f)
                YText(day.dayOfMonth.toString(),if(journal)22 else 20,number=true,color=dateInk,lineHeightMultiplier=1.15f)
                YText(weekday(day),9,color=if(objects)dateInk else t.muted,lineHeightMultiplier=1.3f)
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                YText(name,if(narrow)12 else if(journal)15 else 13,weight=if(journal)FontWeight.Normal else FontWeight.Medium,lineHeightMultiplier=1.45f)
                YText(account,if(!narrow&&journal)11 else 10,color=t.muted,lineHeightMultiplier=1.5f)
                YText(note,if(!narrow&&journal)11 else 10,color=t.muted,lineHeightMultiplier=1.5f)
            }
            // Long amounts move below on narrow or large-text screens instead of squeezing the name.
            if(!narrow&&LocalDensity.current.fontScale<=1.1f)YText(amount,if(objects)14 else 16,number=true)
        }
        if(narrow||LocalDensity.current.fontScale>1.1f)YText(amount,if(narrow)13 else 16,Modifier.align(Alignment.End).padding(top=6.dp),number=true)
        // Scenario balances and links line up with the text column, not under the date tab.
        Column(Modifier.padding(start=if(narrow)46.dp else 53.dp),content=details)
    }
}
@Composable internal fun FutureFundCard(id:String,name:String,value:String,note:String) {
    val t=LocalYohaku.current;val journal=t.id=="journal";val objects=t.id=="objects"
    val frame=when(t.id) {
        "magazine"->Modifier.background(t.soft).drawBehind{drawLine(t.ink,Offset.Zero,Offset(0f,size.height),2.dp.toPx())}
        "journal"->Modifier.background(Color(0xffe7ebdc)).drawBehind{drawRect(t.line,style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))))}
        else->Modifier.background(Color(0xffe8ebda),RoundedCornerShape(14.dp))
    }
    Column(Modifier.fillMaxWidth().testTag("future-fund-$id").then(frame).padding(18.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(9.dp),verticalAlignment=Alignment.Top){YText(name,12,Modifier.weight(1f),weight=if(journal)FontWeight.Normal else FontWeight.Medium);YText(value,19,number=true)}
        YText(note,9,color=t.muted,lineHeightMultiplier=1.9f)
    }
}
