package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun PairHistoryRow(date:String,name:String,paidByMe:Boolean,total:String,portion:String,onOriginal:(()->Unit)?) {
    val t=LocalYohaku.current
    val objects=t.id=="objects"
    val frame=if(objects)Modifier.padding(vertical=16.dp).background(Color(0xfffff8e8),RoundedCornerShape(14.dp)).border(1.dp,Color(0xffe3ceaa),RoundedCornerShape(14.dp)).padding(horizontal=13.dp,vertical=20.dp)
        else Modifier.drawBehind{drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=if(t.id=="journal")PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))else null)}.padding(vertical=20.dp)
    Row(Modifier.fillMaxWidth().then(frame),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        YText(date.substring(5),if(objects)12 else 17,if(objects)Modifier.background(Color(0xffefe2c9),RoundedCornerShape(8.dp)).padding(horizontal=4.dp,vertical=9.dp)else Modifier,number=true,color=if(objects)Color(0xffaa845c)else t.muted)
        Column(Modifier.weight(1f)) {
            YText(name,13,lineHeightMultiplier=1.8f)
            YText((if(paidByMe)word("我付款","自分が支払")else word("对方付款","相手が支払"))+" · "+word("整单","総額")+" $total",9,Modifier.padding(top=7.dp),color=t.muted,lineHeightMultiplier=1.9f)
            YText((if(paidByMe)word("我替对方承担：","自分が相手に：")else word("对方替我承担：","相手が自分に："))+portion,15,Modifier.padding(top=9.dp),number=true,lineHeightMultiplier=1.9f)
            onOriginal?.let{YLink(word("查看原支出","元の支出を見る"),it)}
        }
    }
}
