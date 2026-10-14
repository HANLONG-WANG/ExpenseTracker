package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.LocalYohaku
import com.yohaku.expensetracker.ui.theme.word

/** themes.js empty() and final theme-empty CSS, with actual application content. */
@Composable fun YEmpty(message:String,onAction:(()->Unit)?=null,action:String="") {
    val t=LocalYohaku.current
    val journal=t.id=="journal";val objects=t.id=="objects"
    val shape=RoundedCornerShape(25.dp)
    Box(Modifier.fillMaxWidth().testTag("theme-empty")){
        if(objects)Box(Modifier.matchParentSize().offset(y=4.dp).background(Color(0x19c6ab80),shape))
        val frame=when(t.id){
            "journal"->Modifier.rotate(-.5f).background(t.surface).drawBehind{
                val stroke=1.dp.toPx()
                drawRect(t.line,topLeft=Offset(stroke/2,stroke/2),size=androidx.compose.ui.geometry.Size(size.width-stroke,size.height-stroke),style=Stroke(stroke,pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))))
            }
            "objects"->Modifier.background(Color(0xffeee5d3),shape)
            else->Modifier.drawBehind{
                val half=.5.dp.toPx()
                drawLine(t.line,Offset(0f,half),Offset(size.width,half),1.dp.toPx())
                drawLine(t.line,Offset(0f,size.height-half),Offset(size.width,size.height-half),1.dp.toPx())
            }
        }
        Column(Modifier.fillMaxWidth().then(frame).padding(horizontal=(if(objects)20 else if(journal)15 else 10).dp,vertical=(if(objects)30 else if(journal)28 else 35).dp),horizontalAlignment=Alignment.CenterHorizontally){
            AnalysisArtwork(Modifier.size(if(objects)125.dp else 100.dp,if(objects)90.dp else if(journal)80.dp else 120.dp))
            Spacer(Modifier.height(20.dp))
            YText(when(t.id){"magazine"->"A LITTLE SPACE";"journal"->word("空白的一页","まっさらなページ");else->word("这里，还留着一个位置","ここに、余白があります")},if(journal)20 else if(objects)16 else 10,color=if(journal)t.warm else if(objects)Color(0xffa78965) else t.muted,letterSpacing=if(objects)0f else 1f,lineHeightMultiplier=if(journal)29f/20 else if(objects)1.5f else 1.9f,textAlign=TextAlign.Center,cssLineBox=true)
            val bodySize=if(journal)14 else if(objects)12 else 16
            YText(message,bodySize,Modifier.padding(vertical=bodySize.dp),color=t.muted,lineHeightMultiplier=if(journal)1.8f else 1.9f,textAlign=TextAlign.Center,cssLineBox=true)
            if(onAction!=null)YButton(action,onAction,inline=true)
        }
    }
}
