package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yohaku.expensetracker.ui.theme.*

private val paperTab=GenericShape{size,_->
    moveTo(0f,0f);lineTo(size.width*.94f,0f);lineTo(size.width,size.height*.93f);lineTo(size.width*.04f,size.height);close()
}

@Composable fun SetupProgress(step:Int,pageFlow:Boolean=false) {
    val t=LocalYohaku.current
    val narrow=LocalConfiguration.current.screenWidthDp<=360
    val labels=listOf(word("账本","帳簿"),word("资金","資金"),word("欠款","借入"),word("检查","確認"))
    val frame=if(t.id=="magazine")Modifier.drawBehind{
        drawLine(t.line,Offset(0f,.5.dp.toPx()),Offset(size.width,.5.dp.toPx()),1.dp.toPx())
        drawLine(t.line,Offset(0f,size.height-.5.dp.toPx()),Offset(size.width,size.height-.5.dp.toPx()),1.dp.toPx())
    }.padding(vertical=1.dp) else Modifier
    Box(Modifier.fillMaxWidth().padding(top=if(pageFlow)0.dp else 16.dp,bottom=7.dp)) {
        Row(Modifier.fillMaxWidth().testTag("setup-progress").then(frame),horizontalArrangement=Arrangement.spacedBy(if(t.id=="journal")6.dp else 8.dp),verticalAlignment=Alignment.CenterVertically){
            labels.forEachIndexed{i,label->
                val current=step==i+1;val done=step>i+1
                val description=if(current)word("当前步骤","現在のステップ") else if(done)word("已完成","完了") else word("尚未开始","未開始")
                val marker=Modifier.weight(1f).testTag("setup-progress-step-${i+1}").semantics{selected=current;stateDescription=description}
                when(t.id){
                    "magazine"->{
                        val color=if(current)t.ink else if(done)t.accent else t.muted
                        Row(marker.then(if(current)Modifier.drawBehind{drawLine(t.ink,Offset(0f,size.height-1.dp.toPx()),Offset(size.width,size.height-1.dp.toPx()),2.dp.toPx())} else Modifier).padding(top=10.dp,bottom=if(current)12.dp else 10.dp),horizontalArrangement=Arrangement.spacedBy(7.dp),verticalAlignment=Alignment.CenterVertically){
                            ProgressText("0${i+1}",17,20f,color,true)
                            ProgressText(label,9,13f,color)
                        }
                    }
                    "journal"->{
                        val color=if(current)t.accent else if(done)Color(0xff7a8b76) else t.muted
                        Column(marker.rotate(-1f).clip(paperTab).background(if(current)Color(0xffdde5dc) else if(done)Color(0xffe5e8da) else Color(0xffe9e4d3)).then(if(current)Modifier.drawBehind{drawLine(t.warm,Offset(0f,size.height-1.dp.toPx()),Offset(size.width,size.height-1.dp.toPx()),2.dp.toPx())} else Modifier).padding(start=6.dp,end=6.dp,top=11.dp,bottom=if(current)13.dp else 11.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)){
                            ProgressText("0${i+1}",17,25f,color,true)
                            ProgressText(label,if(narrow)9 else 11,if(narrow)13f else 16f,color)
                        }
                    }
                    else->{
                        val color=if(current)Color(0xfffff9ea) else if(done)Color(0xff81916b) else t.muted
                        Column(marker.drawBehind{
                            drawRoundRect(if(current)Color(0x2b8e603b) else Color(0x25ccb491),topLeft=Offset(0f,(if(current)3 else 2).dp.toPx()),cornerRadius=CornerRadius(9.dp.toPx()))
                        }.background(if(current)Color(0xffb88058) else if(done)Color(0xffe4e8d5) else Color(0xffeee4d1),RoundedCornerShape(9.dp)).padding(vertical=12.dp,horizontal=5.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(5.dp)){
                            ProgressText("0${i+1}",17,25f,color,true)
                            ProgressText(label,9,13f,color)
                        }
                    }
                }
            }
        }
    }
}

/** Browser natural line boxes are measured; scale them with Android fontScale. */
@Composable private fun ProgressText(text:String,fontSize:Int,line:Float,color:Color,number:Boolean=false) {
    val height=with(LocalDensity.current){line.sp.toDp()}
    YText(text,fontSize,Modifier.height(height).wrapContentHeight(Alignment.CenterVertically,unbounded=true),color=color,number=number,lineHeightMultiplier=line/fontSize)
}
