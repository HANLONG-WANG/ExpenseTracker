package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun AnalysisChoice(id:String,index:Int,title:String,note:String,icon:String,modifier:Modifier=Modifier,onChoose:()->Unit) {
    val t=LocalYohaku.current
    val objects=t.id=="objects";val journal=t.id=="journal"
    val shape=RoundedCornerShape(10.dp,10.dp,22.dp,17.dp)
    val frame=when(t.id) {
        "magazine"->Modifier.drawBehind{drawLine(t.ink,Offset.Zero,Offset(size.width,0f),1.dp.toPx())}.padding(vertical=22.dp)
        "journal"->Modifier.rotate(if(index%2==0)-.4f else .5f).background(Color(0xfffff8e6)).drawBehind{drawRect(t.line,style=androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))))}.padding(18.dp)
        else->Modifier.drawBehind{val dy=4.dp.toPx();withTransform({translate(0f,dy)}){drawOutline(shape.createOutline(size,layoutDirection,this),Color(0x2bccb491))}}.background(listOf(Color(0xffedddbd),Color(0xffe5e8d6),Color(0xffe7d9c8),Color(0xffe9dfcc))[index%4],shape).padding(start=15.dp,end=15.dp,top=25.dp,bottom=18.dp)
    }
    Box(modifier.testTag("analysis-choice-$id").clickable(role=Role.Button,onClick=onChoose).then(frame)) {
        if(objects)Box(Modifier.align(Alignment.TopCenter).offset(y=(-30).dp).fillMaxWidth(.54f).height(14.dp).background(Color(0xfffff7e5),RoundedCornerShape(5.dp)))
        Column(verticalArrangement=Arrangement.spacedBy(if(objects)11.dp else 8.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
                if(!objects)YText((index+1).toString().padStart(2,'0'),if(journal)21 else 15,if(journal)Modifier.width(26.dp)else Modifier.weight(1f),number=true,color=if(journal)t.warm else t.muted)
                Box(if(journal)Modifier.width(28.dp)else Modifier){YIcon(icon,Modifier.size(if(objects)26.dp else 21.dp),if(objects)Color(0xffa98a63)else t.accent)}
                if(journal)YText(title,18,Modifier.weight(1f))
            }
            if(!journal)YText(title,if(objects)14 else 17,weight=FontWeight.Medium)
            YText(note,if(journal)11 else 9,Modifier.then(if(journal)Modifier.padding(start=74.dp)else Modifier).then(if(objects)Modifier.heightIn(min=30.dp)else Modifier),color=t.muted,lineHeightMultiplier=1.9f)
        }
    }
}

@Composable internal fun AnalysisModuleHeading(title:String,note:String,pinned:Boolean) {
    val t=LocalYohaku.current
    Column(Modifier.fillMaxWidth().padding(top=7.dp,bottom=4.dp).then(if(t.id=="magazine")Modifier.drawBehind{drawLine(t.ink,Offset.Zero,Offset(size.width,0f),1.dp.toPx())}.padding(top=19.dp)else Modifier)) {
        YText(if(pinned)word("重点关注","注目")else word("我的分析","マイ分析"),if(t.id=="journal")11 else 8,if(t.id=="objects")Modifier.background(Color(0xffe6d7be),RoundedCornerShape(5.dp)).padding(horizontal=9.dp,vertical=5.dp)else Modifier,color=if(t.id=="journal")t.warm else if(t.id=="objects")Color(0xffaa8058)else t.muted)
        YText(title,if(t.id=="objects")24 else 27,Modifier.padding(vertical=8.dp),lineHeightMultiplier=1.6f)
        YText(note,if(t.id=="journal")12 else 10,color=t.muted)
    }
}
