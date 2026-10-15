package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun ActivityCover(name:String,start:String,end:String,spent:String,budget:String,remaining:String,count:Int,excluded:Boolean) {
    val t=LocalYohaku.current
    val tag=if(excluded)word("独立预算 · 不计入月预算","独立予算・月予算に含めない")else word("计入发生月预算","利用月の予算に含む")
    val dates="$start — $end"
    val narrow=LocalConfiguration.current.screenWidthDp<=360
    when(t.id) {
        "magazine"->Column(Modifier.fillMaxWidth().testTag("activity-cover").drawBehind{drawLine(t.ink,Offset.Zero,Offset(size.width,0f),2.dp.toPx());drawLine(t.ink,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}.padding(top=19.dp,bottom=17.dp)) {
            YText("A LITTLE GETAWAY / $dates",8,color=t.muted)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(if(narrow)9.dp else 15.dp),verticalAlignment=Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    YText(name,28,Modifier.padding(top=12.dp,bottom=10.dp))
                    YText(tag,9,color=t.accent,lineHeightMultiplier=1.9f)
                    YText(word("整段活动净消费","全期間の純支出"),9,Modifier.padding(top=21.dp),color=t.muted)
                    YText(spent,39,Modifier.padding(vertical=12.dp),number=true)
                }
                Column(Modifier.width(IntrinsicSize.Min).widthIn(min=if(narrow)63.dp else 84.dp)) {
                    TripArtwork(Modifier.size(if(narrow)63.dp else 83.dp,if(narrow)73.dp else 87.dp))
                    YText(word("活动预算","シーン予算"),9,color=t.muted)
                    YText(budget,18,Modifier.padding(top=7.dp),number=true)
                }
            }
            YText(word("余量","残り")+" $remaining · $count "+word("笔消费","件の支出"),9,Modifier.fillMaxWidth().padding(top=17.dp).drawBehind{drawLine(t.line,Offset.Zero,Offset(size.width,0f),1.dp.toPx())}.padding(top=15.dp),color=t.muted)
        }
        "journal"->Box(Modifier.fillMaxWidth().testTag("activity-cover").rotate(-.6f).background(Color(0xfffff8e7)).border(1.dp,t.line).drawBehind{
            var y=32.dp.toPx();while(y<size.height){drawLine(Color(0x13718695),Offset(0f,y),Offset(size.width,y),1.dp.toPx());y+=32.dp.toPx()}
        }) {
            Box(Modifier.align(Alignment.TopCenter).offset(y=(-8).dp).size(88.dp,17.dp).rotate(-3f).background(Color(0x80d6bf86)))
            Column(Modifier.fillMaxWidth().padding(start=18.dp,end=18.dp,top=25.dp,bottom=24.dp)) {
                YText(dates,17,color=t.warm,number=true)
                YText(name,30,Modifier.padding(top=12.dp,bottom=10.dp))
                TripArtwork(Modifier.align(Alignment.End).size(139.dp,82.dp))
                YText(word("这一趟花了","今回の支出"),13,color=t.muted)
                YText(spent,43,Modifier.padding(vertical=11.dp).rotate(-2f),number=true,color=t.accent)
                Column(Modifier.fillMaxWidth().padding(top=19.dp).drawBehind{drawLine(t.line,Offset.Zero,Offset(size.width,0f),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx())))}.padding(top=15.dp)) {
                    YText(word("预算","予算")+" $budget − "+word("已花","支出")+" $spent",13,color=t.muted,lineHeightMultiplier=2f)
                    YText("＝ "+word("还余","残り")+" $remaining",23,Modifier.padding(top=8.dp),number=true,color=t.warm)
                }
                YText(tag,10,Modifier.padding(top=20.dp).rotate(-4f).border(1.dp,t.warm).padding(horizontal=9.dp,vertical=6.dp),color=t.warm)
            }
        }
        else->{
            val shape=RoundedCornerShape(17.dp,17.dp,31.dp,27.dp)
            Box(Modifier.fillMaxWidth().padding(top=29.dp).testTag("activity-cover").drawBehind{drawRoundRect(Color(0x29a6b28b),Offset(0f,5.dp.toPx()),cornerRadius=androidx.compose.ui.geometry.CornerRadius(25.dp.toPx()))}.background(Color(0xffe2e7d2),shape).border(1.dp,Color(0xffd1d8c2),shape)) {
                Canvas(Modifier.align(Alignment.TopCenter).offset(y=(-13).dp).size(64.dp,22.dp)) {val path=Path().apply{moveTo(3.dp.toPx(),size.height);lineTo(3.dp.toPx(),10.dp.toPx());quadraticTo(3.dp.toPx(),3.dp.toPx(),10.dp.toPx(),3.dp.toPx());lineTo(size.width-10.dp.toPx(),3.dp.toPx());quadraticTo(size.width-3.dp.toPx(),3.dp.toPx(),size.width-3.dp.toPx(),10.dp.toPx());lineTo(size.width-3.dp.toPx(),size.height)};drawPath(path,Color(0xffafb995),style=Stroke(6.dp.toPx()))}
                Column(Modifier.align(Alignment.TopEnd).padding(top=17.dp,end=17.dp).width(41.dp).rotate(8f).background(Color(0xfffff6dc),RoundedCornerShape(7.dp)).padding(6.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    YText(start.substring(5,7),9,number=true,color=Color(0xffa68b57));YText(start.takeLast(2),24,Modifier.padding(top=2.dp),number=true,color=Color(0xffa68b57))
                }
                Column(Modifier.fillMaxWidth().padding(start=20.dp,end=20.dp,top=38.dp,bottom=23.dp)) {
                    YText(name,29,Modifier.fillMaxWidth(.8f),color=Color(0xff738264))
                    TripArtwork(Modifier.align(Alignment.End).padding(top=12.dp,bottom=18.dp).size(127.dp,81.dp))
                    Column(Modifier.fillMaxWidth().background(Color(0xfffff9e8),RoundedCornerShape(12.dp,12.dp,7.dp,7.dp)).drawBehind{drawLine(Color(0xffc2c9a8),Offset(0f,size.height),Offset(size.width,size.height),2.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(),3.dp.toPx())))}.padding(horizontal=14.dp,vertical=18.dp)) {
                        YText(dates,11,color=Color(0xffa0a382),number=true)
                        YText(spent,39,Modifier.padding(top=13.dp,bottom=9.dp),number=true,color=Color(0xff869470))
                        YText(word("活动预算","シーン予算")+" $budget · "+word("剩余","残り")+" $remaining",9,color=Color(0xffa3a58a),lineHeightMultiplier=1.9f)
                    }
                    YText(tag,8,Modifier.padding(top=17.dp).rotate(-3f).background(Color(0xffb4c097),RoundedCornerShape(6.dp)).padding(horizontal=10.dp,vertical=7.dp),color=Color(0xfffffbed),lineHeightMultiplier=1.9f)
                }
            }
        }
    }
}
