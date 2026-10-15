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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun ComparisonCauseCard(id:String,title:String,before:String,after:String,change:String,onCurrent:(()->Unit)?,onPrevious:(()->Unit)?) {
    val t=LocalYohaku.current
    val frame=when(t.id) {
        "magazine"->Modifier.drawBehind{drawLine(t.ink,Offset.Zero,Offset(size.width,0f),1.dp.toPx());drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}.padding(vertical=20.dp)
        "journal"->Modifier.background(Color(0xfffff8e7)).drawBehind{drawRect(t.line,style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))))}.padding(16.dp)
        else->Modifier.background(Color(0xffe8ebda),RoundedCornerShape(14.dp)).padding(18.dp)
    }
    Column(Modifier.fillMaxWidth().testTag("comparison-cause-$id").then(frame),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        YText(title,if(t.id=="journal")17 else 13)
        YText(change,24,Modifier.testTag("comparison-change-$id"),number=true,color=if(t.id=="journal")t.warm else t.accent)
        listOf(word("对照期间净消费","比較期間の純支出") to before,word("本期净消费","今期の純支出") to after).forEach{(label,value)->
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically){
                YText(label,if(t.id=="journal")12 else 10,Modifier.weight(1f),color=t.muted)
                YText(value,14,number=true)
            }
        }
        onCurrent?.let{YLink(word("查看本期流水","今期の記録を見る"),it,Modifier.testTag("comparison-current-$id"))}
        onPrevious?.let{YLink(word("查看对照流水","比較期間の記録を見る"),it,Modifier.testTag("comparison-previous-$id"))}
    }
}
