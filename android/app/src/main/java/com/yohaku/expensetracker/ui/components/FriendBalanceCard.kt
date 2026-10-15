package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.*

/** Final activityFriends composition from experience.js, scoped to one activity. */
@Composable internal fun FriendBalanceCard(name:String,iGive:String,theyGive:String,note:String,fill:Float?,index:Int=0,onOpen:(()->Unit)?=null) {
    val t=LocalYohaku.current
    val narrow=LocalConfiguration.current.screenWidthDp<=360
    @Composable fun footer(modifier:Modifier) {
        Column(modifier){YText(note,if(t.id=="journal")14 else if(t.id=="objects")12 else 10,color=if(t.id=="journal")t.warm else t.ink)
            onOpen?.let{YLink(word("查看与这位朋友的明细","この友達との明細を見る"),it)}}
    }
    when(t.id) {
        "magazine"->Column(Modifier.fillMaxWidth().testTag("friend-balance-card").padding(vertical=16.dp).drawBehind{drawLine(t.ink,Offset.Zero,Offset(size.width,0f),1.dp.toPx());drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}.padding(top=21.dp,bottom=21.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){YText("WITH $name",13,Modifier.weight(1f),letterSpacing=.3f);YText(word("仅本活动","このシーンのみ"),9,color=t.muted)}
            Row(Modifier.fillMaxWidth().padding(vertical=25.dp),horizontalArrangement=Arrangement.spacedBy(if(narrow)7.dp else 10.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)){YText(word("我请","自分から"),9,color=t.muted);YText(iGive,if(narrow)21 else 26,Modifier.padding(top=9.dp),number=true,letterSpacing=-1f)}
                YText("↔",17,Modifier.width(20.dp),color=t.muted)
                Column(Modifier.weight(1f)){YText(word("对方请","相手から"),9,color=t.muted);YText(theyGive,if(narrow)21 else 26,Modifier.padding(top=9.dp),number=true,letterSpacing=-1f)}
            }
            footer(Modifier.fillMaxWidth().drawBehind{drawLine(t.line,Offset.Zero,Offset(size.width,0f),1.dp.toPx())}.padding(top=13.dp))
        }
        "journal"->Column(Modifier.fillMaxWidth().testTag("friend-balance-card").padding(vertical=16.dp).rotate(-.5f).background(Color(0xfffff8e7)).border(1.dp,t.line).drawBehind{drawRect(Color(0xff999b8b),Offset(size.width-44.dp.toPx(),(-4).dp.toPx()),androidx.compose.ui.geometry.Size(24.dp.toPx(),5.dp.toPx()),style=Stroke(1.dp.toPx()))}.padding(start=if(narrow)13.dp else 16.dp,end=if(narrow)13.dp else 16.dp,top=23.dp,bottom=18.dp)) {
            YText(word("和$name 的请客页","$name のごちそうページ"),21,Modifier.padding(top=4.dp,bottom=22.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(if(narrow)9.dp else 13.dp)) {
                Column(Modifier.weight(1f)){YText(word("我替对方","自分から"),13,color=t.muted);YText(iGive,if(narrow)21 else 28,Modifier.padding(top=9.dp),number=true,color=t.accent)}
                Column(Modifier.weight(1f).drawBehind{drawLine(t.line,Offset.Zero,Offset(0f,size.height),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx())))}.padding(start=12.dp)){YText(word("对方替我","相手から"),13,color=t.muted);YText(theyGive,if(narrow)21 else 28,Modifier.padding(top=9.dp),number=true,color=t.accent)}
            }
            footer(Modifier.fillMaxWidth().padding(top=20.dp).drawBehind{drawLine(t.line,Offset.Zero,Offset(size.width,0f),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx())))}.padding(top=15.dp))
        }
        else->Column(Modifier.fillMaxWidth().testTag("friend-balance-card").padding(vertical=16.dp).background(Color(0xfffffaec),RoundedCornerShape(20.dp)).border(1.dp,Color(0xffead8b7),RoundedCornerShape(20.dp)).padding(start=if(narrow)13.dp else 16.dp,end=if(narrow)13.dp else 16.dp,top=21.dp,bottom=21.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(11.dp)) {
                Box(Modifier.size(38.dp).background(Color(0xffd2d7bd),RoundedCornerShape(13.dp,13.dp,18.dp,13.dp)),contentAlignment=Alignment.Center){YText(name.takeLast(1),21)}
                Column(Modifier.weight(1f)){YText(name,15);YText(word("这一趟","今回の旅"),9,Modifier.padding(top=5.dp))}
            }
            Row(Modifier.fillMaxWidth().padding(top=18.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally){FriendCup(fill?:0f,Color(0xff8d9e70));YText(word("我请","自分から"),9);YText(iGive,if(narrow)21 else 23,number=true,letterSpacing=-1f)}
                YText("＋",20,Modifier.width(20.dp),color=Color(0xffafad90))
                Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally){FriendCup(fill?.let{1f-it}?:0f,Color(0xffb98d66));YText(word("对方请","相手から"),9);YText(theyGive,if(narrow)21 else 23,number=true,letterSpacing=-1f)}
            }
            footer(Modifier.fillMaxWidth().drawBehind{drawLine(Color(0xffd4bf9c),Offset.Zero,Offset(size.width,0f),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx())))}.padding(top=16.dp))
        }
    }
}

/** Original cupDrawing from themes.js. Fill represents the pair's relative contributions. */
@Composable private fun FriendCup(fill:Float,color:Color) {
    val surface=LocalYohaku.current.surface
    Canvas(Modifier.size(80.dp)) {
        val scale=size.width/90f
        with(drawContext.canvas){save();scale(scale,scale)
            drawOval(androidx.compose.ui.geometry.Rect(9f,70f,75f,84f),Paint().apply{this.color=Color(0x14604a3a)})
            fun path(d:String)=Path().apply{addPath(androidx.compose.ui.graphics.vector.PathParser().parsePathString(d).toPath())}
            val cup=path("M16 30h49v27a21 21 0 0 1-21 21h-7a21 21 0 0 1-21-21Z")
            drawPath(cup,Paint().apply{this.color=surface})
            val tea=path("M21 ${66-kotlin.math.round(fill*27).toInt()}h39v13a16 16 0 0 1-16 16h-7a16 16 0 0 1-16-16Z")
            drawPath(tea,Paint().apply{this.color=color.copy(alpha=.4f)})
            val stroke=Paint().apply{this.color=color;style=PaintingStyle.Stroke;strokeWidth=1.3f;strokeCap=StrokeCap.Round;strokeJoin=StrokeJoin.Round}
            drawPath(cup,stroke);drawPath(path("M65 34h8c19 0 19 28 0 28h-8M30 9c-7 8 6 8 0 16m15-17c-7 8 6 8 0 16"),stroke)
            restore()
        }
    }
}
