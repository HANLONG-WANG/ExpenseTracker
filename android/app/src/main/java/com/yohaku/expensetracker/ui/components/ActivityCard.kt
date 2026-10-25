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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.yohaku.expensetracker.ui.theme.*

/** Activity list composition from themes.js and the three theme stylesheets. */
@Composable internal fun ActivityCard(index:Int,name:String,start:String,end:String,spent:String,remaining:String?,budget:String?,spentMinor:Long,budgetMinor:Long?,status:String,onOpen:(()->Unit)?,openTag:String="") {
    val t=LocalYohaku.current
    val dates="$start — $end"
    @Composable fun open(){onOpen?.let{YButton(word("打开活动","シーンを開く"),it,if(openTag.isEmpty())Modifier else Modifier.testTag(openTag))}}
    when(t.id) {
        "magazine"->Row(Modifier.fillMaxWidth().padding(top=22.dp,bottom=28.dp).drawBehind{drawLine(t.ink,Offset.Zero,Offset(size.width,0f),1.dp.toPx())}) {
            YText((index+1).toString().padStart(2,'0'),24,Modifier.width(39.dp).padding(top=20.dp),number=true,color=t.muted)
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().padding(vertical=18.dp).height(150.dp).background(Color(0xffe7ecde)),contentAlignment=Alignment.Center){TripArtwork(Modifier.size(170.dp,140.dp))}
                YText("A LITTLE GETAWAY",7,color=t.muted)
                YText(name,27,Modifier.padding(vertical=10.dp),lineHeightMultiplier=1.65f)
                YText(dates,9,color=t.muted)
                Row(Modifier.fillMaxWidth().padding(top=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)){YText(word("已花","支出"),9,color=t.muted);YText(spent,18,number=true)}
                    Column(Modifier.weight(1f)){YText(word("剩余","残り"),9,color=t.muted);YText(remaining?:word("未知","不明"),18,number=true)}
                }
                YText(status,10,Modifier.padding(top=11.dp,bottom=12.dp),color=t.muted);open()
            }
        }
        "journal"->Box(Modifier.fillMaxWidth().padding(top=29.dp,bottom=28.dp).rotate(if(index%2==0)-.8f else .8f).background(t.surface).border(1.dp,Color(0xffded2b8))) {
            Box(Modifier.align(Alignment.TopCenter).offset(y=(-10).dp).size(86.dp,20.dp).rotate(-4f).background(Color(0x84b3bba7)))
            YText(word("第${index+1}页","${index+1}頁"),11,Modifier.align(Alignment.TopEnd).padding(top=15.dp,end=16.dp).rotate(3f),color=t.muted)
            Column(Modifier.padding(start=18.dp,end=18.dp,top=25.dp,bottom=19.dp)) {
                Row(Modifier.padding(top=6.dp,end=50.dp),horizontalArrangement=Arrangement.spacedBy(9.dp),verticalAlignment=Alignment.CenterVertically){YText(start.takeLast(2),33,number=true,color=t.warm);YText(dates,10,color=t.muted,lineHeightMultiplier=1.8f)}
                YText(name,29,Modifier.padding(vertical=14.dp))
                Box(Modifier.fillMaxWidth().height(103.dp).drawBehind{drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx())))},contentAlignment=Alignment.Center){TripArtwork(Modifier.size(155.dp,105.dp).rotate(-2f))}
                Row(Modifier.fillMaxWidth().padding(top=18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){YText(word("这一趟花了","今回の支出"),13);YText(spent,29,number=true)}
                budgetMinor?.let{BudgetTrack(spentMinor,it,12)}
                YText(word("总预算","総予算")+" "+(budget?:word("未设置","未設定"))+" · "+word("余量","残り")+" "+(remaining?:word("未知","不明")),10,Modifier.padding(top=11.dp),color=t.muted)
                YText(status,10,Modifier.padding(bottom=12.dp),color=t.muted);open()
            }
        }
        else->{
            val body=if(index%2==0)Color(0xffe4ba85)else Color(0xffc7d0af)
            val border=if(index%2==0)Color(0xffcfa471)else Color(0xffaeb892)
            Box(Modifier.fillMaxWidth().padding(top=40.dp,bottom=37.dp).background(body,RoundedCornerShape(17.dp,17.dp,23.dp,23.dp)).border(1.dp,border,RoundedCornerShape(17.dp,17.dp,23.dp,23.dp)).drawBehind{
                val radius=androidx.compose.ui.geometry.CornerRadius(5.dp.toPx());for(x in listOf(13.dp.toPx(),size.width-20.dp.toPx()))drawRoundRect(Color(0x45bd8e5d),Offset(x,18.dp.toPx()),androidx.compose.ui.geometry.Size(7.dp.toPx(),size.height-31.dp.toPx()),radius)
            }) {
                Canvas(Modifier.align(Alignment.TopCenter).offset(y=(-19).dp).fillMaxWidth(.34f).height(20.dp)) {val p=Path().apply{moveTo(2.5.dp.toPx(),size.height);lineTo(2.5.dp.toPx(),9.dp.toPx());quadraticTo(2.5.dp.toPx(),2.5.dp.toPx(),9.dp.toPx(),2.5.dp.toPx());lineTo(size.width-9.dp.toPx(),2.5.dp.toPx());quadraticTo(size.width-2.5.dp.toPx(),2.5.dp.toPx(),size.width-2.5.dp.toPx(),9.dp.toPx());lineTo(size.width-2.5.dp.toPx(),size.height)};drawPath(p,Color(0xffbe9564),style=Stroke(5.dp.toPx()))}
                Column(Modifier.align(Alignment.TopEnd).padding(top=24.dp,end=22.dp).rotate(8f).background(Color(0xfffff4dc),RoundedCornerShape(3.dp)).border(1.dp,Color(0xffdec69f),RoundedCornerShape(3.dp)).padding(horizontal=8.dp,vertical=5.dp),horizontalAlignment=Alignment.CenterHorizontally){YText(start.substring(5,7),8,color=Color(0xffa1764a));YText(start.takeLast(2),21,number=true,color=Color(0xffa1764a))}
                for(alignment in listOf(Alignment.BottomStart,Alignment.BottomEnd))Box(Modifier.align(alignment).padding(horizontal=29.dp).offset(y=8.dp).size(15.dp,10.dp).background(Color(0xff98704c),RoundedCornerShape(3.dp)))
                Column(Modifier.fillMaxWidth().padding(start=23.dp,end=23.dp,top=28.dp,bottom=23.dp)) {
                    YText("A LITTLE GETAWAY",8,Modifier.fillMaxWidth(.7f),color=Color(0xff9b754a))
                    YText(name,25,Modifier.fillMaxWidth(.8f).padding(top=15.dp,bottom=6.dp),lineHeightMultiplier=1.55f)
                    Box(Modifier.fillMaxWidth().height(123.dp),contentAlignment=Alignment.Center){TripArtwork(Modifier.size(147.dp,115.dp))}
                    Column(Modifier.fillMaxWidth().rotate(-2f).background(Color(0xfffff7e5),RoundedCornerShape(5.dp)).border(1.dp,Color(0xffe5d5b7),RoundedCornerShape(5.dp)).padding(horizontal=14.dp,vertical=12.dp)) {
                        YText(dates,9,color=Color(0xffb09874));YText(spent,24,number=true)
                        YText(word("总预算","総予算")+" "+(budget?:word("未设置","未設定"))+" · "+word("余量","残り")+" "+(remaining?:word("未知","不明")),9,color=Color(0xffa99577))
                    }
                    YText(status,10,Modifier.padding(top=11.dp,bottom=12.dp));open()
                }
            }
        }
    }
}
