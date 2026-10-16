package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun FutureTimelineRow(id:String,date:String,name:String,account:String,amount:String,note:String,atRisk:Boolean=false,details:@Composable ColumnScope.()->Unit={}) {
    val t=LocalYohaku.current;val journal=t.id=="journal";val objects=t.id=="objects"
    val narrow=LocalConfiguration.current.screenWidthDp<=360
    val frame=if(objects)Modifier.drawBehind{drawRoundRect(Color(0x24c6aa80),Offset(0f,3.dp.toPx()),cornerRadius=androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()))}.background(if(atRisk)Color(0xfffff3dd)else Color(0xfffff8e8),RoundedCornerShape(14.dp)).border(1.dp,if(atRisk)Color(0xffd7b185)else Color(0xffe3cda8),RoundedCornerShape(14.dp)).padding(horizontal=11.dp,vertical=20.dp)
        else Modifier.drawBehind{drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=if(journal)PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))else null)}.padding(vertical=22.dp)
    Column(Modifier.fillMaxWidth().testTag("future-occurrence-$id").then(if(atRisk&&!objects)Modifier.drawBehind{drawLine(t.warm,Offset.Zero,Offset(0f,size.height),1.dp.toPx())}.padding(start=9.dp)else Modifier).then(frame)) {
        Row(horizontalArrangement=Arrangement.spacedBy(if(narrow)7.dp else 9.dp),verticalAlignment=Alignment.Top) {
            YText(date.substring(5).replace('-','\n'),if(objects)13 else 11,Modifier.widthIn(min=if(narrow)35.dp else 42.dp).then(if(objects)Modifier.background(Color(0xffecddc0),RoundedCornerShape(7.dp)).padding(horizontal=4.dp,vertical=8.dp)else Modifier),number=true,color=if(objects)Color(0xffaf875d)else t.muted)
            Column(Modifier.weight(1f)) {
                YText(name,if(narrow)11 else if(journal)15 else 12,weight=if(journal)FontWeight.Normal else FontWeight.Medium,lineHeightMultiplier=1.8f)
                YText("$date · $account",if(!narrow&&journal)11 else 9,Modifier.padding(top=6.dp),color=t.muted,lineHeightMultiplier=1.9f)
                YText(note,if(!narrow&&journal)11 else 9,Modifier.padding(top=10.dp),color=t.muted,lineHeightMultiplier=1.9f)
            }
            // Long amounts move below on narrow or large-text screens instead of squeezing the name.
            if(!narrow&&LocalDensity.current.fontScale<=1.1f)YText(amount,if(objects)14 else 16,number=true)
        }
        if(narrow||LocalDensity.current.fontScale>1.1f)YText(amount,if(narrow)13 else 16,Modifier.align(Alignment.End).padding(top=9.dp),number=true)
        details()
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
