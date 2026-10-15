package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun RecordCover(title:String,amount:String,date:String,kind:String,stamp:String="✓",recordId:String?=null) {
    val t=LocalYohaku.current
    val narrow=LocalConfiguration.current.screenWidthDp<=360
    val amountSize=if(narrow)40 else when(t.id){"magazine"->49;"journal"->45;else->44}
    when(t.id){
        "magazine"->Column(Modifier.testTag("record-cover").fillMaxWidth()){
            Box(Modifier.fillMaxWidth().height(2.dp).background(t.ink))
            Column(Modifier.padding(vertical=24.dp)){
                YText("THE EVERYDAY RECORD"+(recordId?.let{" / "+it.uppercase()}.orEmpty()),8,color=t.muted,letterSpacing=1f,lineHeightMultiplier=19f/8)
                YText(title,25,Modifier.padding(vertical=12.dp),weight=FontWeight.Medium,lineHeightMultiplier=1.7f)
                RecordAmount(amount,amountSize,Modifier.padding(bottom=7.dp),spacing=-2f)
                YText("$date · $kind",10,color=t.muted,lineHeightMultiplier=1.9f)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(t.ink))
        }
        "journal"->Box(Modifier.testTag("record-cover").fillMaxWidth().rotate(-.5f).background(Color(0xfffff9e8)).border(1.dp,t.line)){
            Canvas(Modifier.matchParentSize()){
                // CSS 0deg grain: padding-box origin, 1dp blue-grey stripe every 33dp.
                var y=size.height-2.dp.toPx()
                while(y+1.dp.toPx()>0){drawRect(Color(0x16718695),Offset(0f,y),androidx.compose.ui.geometry.Size(size.width,1.dp.toPx()));y-=33.dp.toPx()}
            }
            Box(Modifier.align(Alignment.TopCenter).offset(y=(-9).dp).size(82.dp,18.dp).rotate(-3f).background(Color(0x80b3bba7)))
            Column(Modifier.padding(horizontal=18.dp,vertical=27.dp)){
                YText(word("记在这一页","このページの記録"),12,color=t.warm,lineHeightMultiplier=20f/12)
                YText(title,27,Modifier.padding(vertical=12.dp),lineHeightMultiplier=1.7f)
                RecordAmount(amount,amountSize,Modifier.padding(bottom=7.dp))
                YText("$date · $kind",10,color=t.muted,lineHeightMultiplier=2f)
            }
            Box(Modifier.align(Alignment.BottomEnd).padding(end=17.dp,bottom=15.dp).rotate(-12f).border(1.dp,t.warm,RoundedCornerShape(45)).width(35.dp),contentAlignment=Alignment.Center){YText(stamp,29,color=t.warm,lineHeightMultiplier=1.2f)}
        }
        else->Box(Modifier.testTag("record-cover").fillMaxWidth()){
            val shape=RoundedCornerShape(19.dp,19.dp,5.dp,5.dp)
            Box(Modifier.matchParentSize().offset(y=4.dp).background(Color(0x30d8c4a6),shape))
            Box(Modifier.matchParentSize().background(Color(0xfffff9e9),shape))
            Box(Modifier.align(Alignment.TopCenter).padding(top=11.dp).size(7.dp).background(t.bg,RoundedCornerShape(50)))
            Column(Modifier.padding(start=20.dp,end=20.dp,top=31.dp,bottom=28.dp)){
                YText(word("这一天的小票","この日のレシート"),9,color=t.muted,lineHeightMultiplier=19f/9)
                YText(title,24,Modifier.padding(vertical=12.dp),weight=FontWeight.Medium,lineHeightMultiplier=1.7f)
                RecordAmount(amount,amountSize,Modifier.padding(bottom=7.dp),color=t.accent)
                YText("$date · $kind",10,color=t.muted,lineHeightMultiplier=1.9f)
            }
            Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp)){drawLine(Color(0xffe1ceac),Offset.Zero,Offset(size.width,0f),2.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),4.dp.toPx())))}
        }
    }
}

@Composable private fun RecordAmount(amount:String,size:Int,modifier:Modifier,color:Color=LocalYohaku.current.ink,spacing:Float=0f){
    var lines by remember(amount,size){mutableIntStateOf(1)}
    val height=with(LocalDensity.current){(size*1.4f).sp.toDp()*lines}
    YText(amount,size,modifier.height(height).wrapContentHeight(Alignment.CenterVertically,unbounded=true),number=true,color=color,letterSpacing=spacing,lineHeightMultiplier=1.4f,onTextLayout={lines=it.lineCount})
}

@Composable internal fun RecordFacts(facts:List<Pair<String,String>>,audit:Boolean=false,exactLineBox:Boolean=false) {
    val t=LocalYohaku.current
    BoxWithConstraints(Modifier.fillMaxWidth()){
    val maxValueWidth=(maxWidth-if(t.id=="objects")24.dp else 0.dp)*.55f
    val factGap=if(audit)12.dp else if(t.id=="magazine")18.dp else 15.dp
    val maxLabelWidth=(maxWidth-(if(t.id=="objects")if(audit)24.dp else 12.dp else 0.dp)-factGap)*.45f
    Column(Modifier.fillMaxWidth().testTag("record-facts"),verticalArrangement=Arrangement.spacedBy(if(t.id=="objects")6.dp else 0.dp)){
        facts.forEachIndexed{i,(label,value)->
            val objects=t.id=="objects";val journal=t.id=="journal"
            Column(Modifier.fillMaxWidth().testTag("record-fact-$i")){
                Row(Modifier.fillMaxWidth().then(if(objects)Modifier.background(Color(0x60ffffff),RoundedCornerShape(10.dp)) else Modifier).padding(horizontal=if(objects)if(audit)12.dp else 6.dp else 0.dp,vertical=if(objects)12.dp else if(journal)15.dp else 16.dp),horizontalArrangement=Arrangement.spacedBy(if(audit)12.dp else if(t.id=="magazine")18.dp else 15.dp),verticalAlignment=if(objects)Alignment.CenterVertically else Alignment.Top){
                    val line=if(audit)1.9f else if(journal)1.7f else 1.6f
                    YText(label,if(journal)12 else if(audit)10 else 11,Modifier.widthIn(max=maxLabelWidth),color=t.muted,lineHeightMultiplier=line,cssLineBox=exactLineBox)
                    val valueSize=if(journal)13 else if(audit)10 else 11
                    if(audit)Box(Modifier.weight(1f),contentAlignment=Alignment.TopEnd){YText(value,valueSize,Modifier.widthIn(max=maxValueWidth),textAlign=TextAlign.End,lineHeightMultiplier=line,cssLineBox=exactLineBox)}
                    else YText(value,valueSize,Modifier.weight(1f),textAlign=TextAlign.End,lineHeightMultiplier=line,cssLineBox=exactLineBox)
                }
                if(journal&&audit)Canvas(Modifier.fillMaxWidth().height(1.dp)){drawLine(t.line,Offset.Zero,Offset(size.width,0f),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())))}
                else if(!objects)Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
            }
        }
    }
    }
}
