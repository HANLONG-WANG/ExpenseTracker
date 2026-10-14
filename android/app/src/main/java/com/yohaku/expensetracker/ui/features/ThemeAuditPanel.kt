package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yohaku.expensetracker.data.db.BookOverview
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun ThemeAuditPanel(title:String,modifier:Modifier=Modifier,contentTopMargin:Int=0,content:@Composable ColumnScope.()->Unit){
    val t=LocalYohaku.current;val journal=t.id=="journal";val objects=t.id=="objects"
    val shape=RoundedCornerShape(if(objects)18.dp else 0.dp)
    BoxWithConstraints(modifier.fillMaxWidth().padding(vertical=10.dp).then(if(journal)Modifier.rotate(-.25f) else Modifier)){
        if(journal||objects)Box(Modifier.matchParentSize().offset(x=if(journal)2.dp else 0.dp,y=if(journal)3.dp else 4.dp).background(if(journal)Color(0x55ddd0b3) else Color(0xffddd4bd),shape))
        if(journal)Box(Modifier.align(Alignment.TopStart).offset(x=maxWidth*.32f,y=(-8).dp).size(75.dp,15.dp).rotate(-3f).background(Color(0x88d7bf8b)))
        val edge=if(journal)Modifier.border(1.dp,Color(0xffd9ccb0)) else if(objects)Modifier else Modifier.drawBehind{drawLine(t.ink,Offset(0f,1.dp.toPx()),Offset(size.width,1.dp.toPx()),2.dp.toPx());drawLine(t.line,Offset(0f,size.height-.5.dp.toPx()),Offset(size.width,size.height-.5.dp.toPx()),1.dp.toPx())}
        Column(Modifier.fillMaxWidth().testTag("audit-panel-body").background(if(journal)Color(0xfffff8e6) else if(objects)Color(0xffede6d2) else Color.Transparent,shape).then(edge).padding(start=if(journal)19.dp else if(objects)17.dp else 0.dp,end=if(journal)19.dp else if(objects)17.dp else 0.dp,top=if(journal)25.dp else if(objects)21.dp else 24.dp,bottom=if(journal)25.dp else if(objects)21.dp else 23.dp)){
            if(t.id=="magazine")Row(horizontalArrangement=Arrangement.spacedBy(13.dp),verticalAlignment=Alignment.Top){
                YText("FIELD NOTES",8,Modifier.width(65.dp).drawBehind{drawLine(t.line,Offset(size.width,0f),Offset(size.width,size.height),1.dp.toPx())}.padding(top=11.dp),color=t.muted,letterSpacing=1f,lineHeightMultiplier=2f,cssLineBox=true)
                YText(title,19,Modifier.weight(1f).padding(top=5.dp,bottom=17.dp),weight=FontWeight.Medium,lineHeightMultiplier=1.7f,cssLineBox=true)
            } else {
                if(journal)Box(Modifier.height(with(LocalDensity.current){20.sp.toDp()})){
                    YText(word("✳ 记在这里","✳ ここにメモ"),11,Modifier.offset(y=with(LocalDensity.current){3.sp.toDp()}),color=t.warm,letterSpacing=1f,lineHeightMultiplier=16f/11,cssLineBox=true)
                } else YText(word("收在这一格","ここに収納"),8,Modifier.rotate(-2f).background(Color(0xffd8c09d),RoundedCornerShape(5.dp)).padding(horizontal=8.dp,vertical=5.dp),color=t.muted,letterSpacing=1f,lineHeightMultiplier=12f/8,cssLineBox=true)
                YText(title,if(journal)24 else 20,Modifier.padding(top=if(objects)16.dp else 9.dp,bottom=(17-contentTopMargin).coerceAtLeast(0).dp),weight=FontWeight.Medium,lineHeightMultiplier=1.7f,cssLineBox=true)
            }
            content()
        }
    }
}
