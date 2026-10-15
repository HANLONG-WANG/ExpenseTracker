package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.*

internal data class TrendPoint(val date:String,val amount:Long,val money:String)

/** theme-trend bars from the source design, scaled to actual signed net spending. */
@Composable internal fun AnalysisTrendChart(points:List<TrendPoint>,selected:String,onPoint:(String)->Unit) {
    val t=LocalYohaku.current
    val objects=t.id=="objects";val journal=t.id=="journal"
    val frame=when(t.id) {
        "magazine"->Modifier.drawBehind{drawLine(t.ink,Offset.Zero,Offset(size.width,0f),1.dp.toPx());drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}.padding(top=7.dp,bottom=13.dp)
        "journal"->Modifier.background(Color(0xfff6f0e0)).drawBehind{drawRect(t.line,style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))))}.padding(horizontal=12.dp,vertical=15.dp)
        else->Modifier.drawBehind{drawRoundRect(Color(0x18a8b192),Offset(0f,4.dp.toPx()),cornerRadius=CornerRadius(24.dp.toPx()))}.background(Color(0xffe4e8dc),RoundedCornerShape(24.dp)).padding(start=15.dp,end=15.dp,top=17.dp,bottom=10.dp)
    }
    val low=minOf(0L,points.minOf{it.amount}).toDouble()
    val high=maxOf(0L,points.maxOf{it.amount}).toDouble()
    val range=(high-low).coerceAtLeast(1.0)
    Column(Modifier.fillMaxWidth().testTag("analysis-trend-chart").then(frame)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top=22.dp,bottom=8.dp)) {
            val width=maxOf(maxWidth,58.dp*points.size)
            Row(Modifier.horizontalScroll(rememberScrollState()).width(width).height(145.dp),horizontalArrangement=Arrangement.spacedBy(13.dp)) {
                points.forEach{point->
                    val active=point.date==selected
                    Column(Modifier.weight(1f).fillMaxHeight().testTag("trend-point-${point.date}").clickable(role=Role.Button){onPoint(point.date)}.semantics{contentDescription=point.date+" · "+point.money},verticalArrangement=Arrangement.spacedBy(9.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        Canvas(Modifier.fillMaxWidth().weight(1f)) {
                            val zero=if(high==0.0&&low==0.0)size.height else(size.height*high/range).toFloat()
                            val y=(size.height*(high-point.amount)/range).toFloat()
                            if(low<0||point.amount==0L)drawLine(t.line,Offset(0f,zero),Offset(size.width,zero),1.dp.toPx())
                            if(point.amount!=0L) {
                                val barWidth=(if(objects)24 else if(journal)20 else 14).dp.toPx().coerceAtMost(size.width)
                                val left=(size.width-barWidth)/2;val top=minOf(y,zero);val bottom=maxOf(y,zero)
                                val path=Path().apply{addRoundRect(RoundRect(left,top,left+barWidth,bottom,topLeftCornerRadius=CornerRadius((if(objects)14 else if(journal)2 else 0).dp.toPx()),topRightCornerRadius=CornerRadius((if(objects)14 else if(journal)4 else 0).dp.toPx()),bottomLeftCornerRadius=CornerRadius((if(objects)5 else 0).dp.toPx()),bottomRightCornerRadius=CornerRadius((if(objects)5 else 0).dp.toPx())))}
                                if(journal)rotate(-1f,Offset(size.width/2,(top+bottom)/2)) {
                                    if(active)drawPath(path,Color(0xffcfd9d3))
                                    clipPath(path) {
                                        var x=left-(bottom-top)*.27f
                                        while(x<left+barWidth){drawLine(t.accent.copy(alpha=if(active)1f else .25f),Offset(x,bottom),Offset(x+(bottom-top)*.27f,top),2.dp.toPx());x+=5.dp.toPx()}
                                    }
                                    drawPath(path,t.accent.copy(alpha=.375f),style=Stroke(1.dp.toPx()))
                                }else drawPath(path,if(objects)if(active)Color(0xff8d9f79)else Color(0xffbac7a9)else if(active)t.accent else Color(0xffced9c3))
                            }
                        }
                        val label=if(point.date.length==7)point.date.replace("-","\n")else point.date.take(7)+"\n"+point.date.takeLast(2)
                        YText(label,if(objects)10 else 9,number=!objects,color=if(active)t.accent else t.muted,textAlign=androidx.compose.ui.text.style.TextAlign.Center,lineHeightMultiplier=1.3f)
                    }
                }
            }
        }
        points.firstOrNull{it.date==selected}?.let{YText(it.date+" · "+it.money,12,Modifier.testTag("trend-selected-value"),number=true)}
        YText(word("按真实记录日期显示净消费；退款归原消费期间。点击柱形查看金额。","記録日ごとの純支出。返金は元の購入期間へ。棒を押して金額を確認。"),11,Modifier.padding(top=10.dp),color=t.muted,lineHeightMultiplier=1.9f)
    }
}
