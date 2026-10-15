package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun YTabs(items:List<Pair<String,String>>,current:String,onSelect:(String)->Unit) {
    val t=LocalYohaku.current
    val objects=t.id=="objects";val journal=t.id=="journal"
    val frame=if(objects)Modifier.background(Color(0xffecdfc7),RoundedCornerShape(17.dp)).padding(5.dp)
        else Modifier.drawBehind{drawLine(if(journal)t.accent else t.ink,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}.then(if(journal)Modifier.padding(top=7.dp)else Modifier)
    Row(Modifier.fillMaxWidth().selectableGroup().then(frame),horizontalArrangement=Arrangement.spacedBy(if(objects)4.dp else if(journal)7.dp else 25.dp),verticalAlignment=Alignment.Bottom) {
        items.forEachIndexed{index,(id,label)->
            val selected=id==current
            val shape=RoundedCornerShape(if(objects)12.dp else 4.dp,if(objects)12.dp else 4.dp,if(objects)12.dp else 0.dp,if(objects)12.dp else 0.dp)
            val surface=when {
                objects&&selected->Modifier.drawBehind{drawRoundRect(Color(0x20a47544),Offset(0f,3.dp.toPx()),cornerRadius=CornerRadius(12.dp.toPx()))}.background(Color(0xfffffaf0),shape)
                journal->Modifier.rotate(if(index%2==0)-1f else 1f).background(if(selected)t.surface else Color(0xffe5e3d3),shape).drawBehind{
                    if(selected)drawRect(Color(0xffb4c0b8),size=Size(size.width,3.dp.toPx()))
                    val p=Path().apply{moveTo(0f,size.height);lineTo(0f,4.dp.toPx());quadraticTo(0f,0f,4.dp.toPx(),0f);lineTo(size.width-4.dp.toPx(),0f);quadraticTo(size.width,0f,size.width,4.dp.toPx());lineTo(size.width,size.height)}
                    drawPath(p,t.line,style=Stroke(1.dp.toPx()))
                }
                !objects&&selected->Modifier.drawBehind{drawLine(t.accent,Offset(15.dp.toPx(),size.height),Offset(size.width-15.dp.toPx(),size.height),2.dp.toPx())}
                else->Modifier
            }
            Box(Modifier.weight(1f).testTag("analysis-tab-$id").selectable(selected,onClick={onSelect(id)},role=Role.Tab).then(surface).padding(horizontal=if(journal)7.dp else if(objects)6.dp else 0.dp,vertical=if(journal)10.dp else if(objects)10.dp else 12.dp),contentAlignment=Alignment.Center){YText(label,if(journal)13 else if(objects)12 else 11,color=if(selected)t.accent else t.muted,weight=if(objects&&selected)FontWeight.Medium else FontWeight.Normal)}
        }
    }
}
