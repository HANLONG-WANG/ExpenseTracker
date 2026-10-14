package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.*

/** Approved startup artwork and typography, with automatic continuation. */
@Composable fun YStartup(){
    val t=LocalYohaku.current
    val objects=t.id=="objects";val journal=t.id=="journal"
    val shape=RoundedCornerShape(if(objects)24.dp else 0.dp)
    val background=if(objects)Color(0xffe7e9d7) else if(journal)Color(0xfffff8e5) else t.bg
    val frame=if(journal)Modifier.rotate(-.4f) else Modifier
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()){
        val availableHeight=maxHeight
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min=availableHeight).padding(horizontal=20.dp),contentAlignment=Alignment.Center){
        Box(Modifier.fillMaxWidth().testTag("startup-brand").then(frame)){
            if(journal||objects)Box(Modifier.matchParentSize().offset(x=if(journal)4.dp else 0.dp,y=5.dp).background(if(journal)Color(0x55d7cdb9) else Color(0xffcfd4bd),shape))
            Column(Modifier.fillMaxWidth().background(background,shape).then(if(journal)Modifier.border(1.dp,t.line).padding(1.dp) else if(!objects)Modifier.drawBehind{
                drawLine(t.ink,Offset(0f,1.dp.toPx()),Offset(size.width,1.dp.toPx()),2.dp.toPx())
                drawLine(t.line,Offset(0f,size.height-.5.dp.toPx()),Offset(size.width,size.height-.5.dp.toPx()),1.dp.toPx())
            }.padding(top=2.dp,bottom=1.dp) else Modifier).padding(start=20.dp,end=20.dp,top=if(objects)35.dp else 42.dp,bottom=30.dp),horizontalAlignment=Alignment.CenterHorizontally){
                YText(when(t.id){"magazine"->"YOHAKU / THE EVERYDAY EDITION";"journal"->word("我的生活，第一页。","暮らしの、最初のページ。");else->"A LITTLE ROOM FOR TOMORROW"},if(objects)8 else 10,Modifier.then(if(objects)Modifier.rotate(-3f).background(Color(0xfffff5df),RoundedCornerShape(7.dp)).padding(horizontal=13.dp,vertical=10.dp) else Modifier),color=t.muted,letterSpacing=1.6f,lineHeightMultiplier=2f,textAlign=TextAlign.Center)
                Box(Modifier.fillMaxWidth().padding(top=40.dp,bottom=20.dp).then(if(!objects&&!journal)Modifier.drawBehind{drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}.padding(bottom=21.dp) else Modifier),contentAlignment=Alignment.Center){
                    if(objects)Box(Modifier.size(200.dp,190.dp)){
                        val emblemShape=RoundedCornerShape(30.dp,30.dp,18.dp,18.dp)
                        Box(Modifier.matchParentSize().offset(y=4.dp).background(Color(0xffcccdb6),emblemShape))
                        Box(Modifier.matchParentSize().background(Color(0xfffff7e3),emblemShape),contentAlignment=Alignment.Center){ThemeArtwork(width=150,height=150)}
                    }else ThemeArtwork(Modifier.then(if(journal)Modifier.rotate(3f) else Modifier),150,150)
                }
                YText(word("余量","ヨハク"),55,Modifier.padding(vertical=15.dp),letterSpacing=8f,lineHeightMultiplier=if(journal||objects)80f/55 else 79f/55,textAlign=TextAlign.Center)
                YText("YOHAKU",14,Modifier.then(if(objects)Modifier.background(Color(0xffd3bb96),RoundedCornerShape(6.dp)).padding(horizontal=13.dp,vertical=8.dp) else if(journal)Modifier.drawBehind{drawLine(t.warm,Offset(0f,size.height+9.dp.toPx()),Offset(size.width,size.height+9.dp.toPx()),1.dp.toPx())} else Modifier.fillMaxWidth().drawBehind{drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}.padding(vertical=21.dp)),color=if(journal)t.warm else t.ink,letterSpacing=6f,lineHeightMultiplier=1.5f,textAlign=TextAlign.Center)
                YText(word("给日常，留一点余量。","日々に、少し余白を。"),14,Modifier.padding(top=25.dp,bottom=40.dp),lineHeightMultiplier=2f,textAlign=TextAlign.Center)
            }
            if(journal)BoxWithConstraints(Modifier.matchParentSize()){
                Box(Modifier.offset(x=maxWidth*.3f,y=(-10).dp).size(100.dp,20.dp).rotate(-4f).background(Color(0x99d9c194)))
            }
        }
    }
    }
}
