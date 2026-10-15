package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun FriendDirectoryRow(id:String,name:String,count:Int,onOpen:()->Unit) {
    val t=LocalYohaku.current
    val objects=t.id=="objects"
    val frame=if(objects)Modifier.background(Color(0xfffff9eb),RoundedCornerShape(15.dp)).border(1.dp,Color(0xffe5cfac),RoundedCornerShape(15.dp)).padding(horizontal=15.dp,vertical=20.dp)
        else Modifier.drawBehind{drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=if(t.id=="journal")PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))else null)}.padding(vertical=18.dp)
    Row(Modifier.fillMaxWidth().testTag("friend-roster-$id").clickable(onClick=onOpen).then(frame),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
        if(objects)Box(Modifier.size(38.dp).background(Color(0xffd2d7bd),RoundedCornerShape(13.dp,13.dp,18.dp,13.dp)),contentAlignment=Alignment.Center){YText(name.takeLast(1),21)}
        else YText(name.takeLast(1),21,color=t.accent)
        YText(name,15,Modifier.weight(1f))
        YText("$count "+word("个活动","件のシーン"),10,color=t.muted);YText("↗",15,color=t.accent)
    }
}
