package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.*

/** Final hierarchy-detail-cover; other drills retain the source sheet-stat composition. */
@Composable internal fun AnalysisDetailCover(amount:String,note:String,tier:String?=null) {
    val t=LocalYohaku.current;val journal=t.id=="journal";val objects=t.id=="objects"
    val frame=if(tier==null)Modifier else if(objects)Modifier.background(Color(0xffe4e9d5),RoundedCornerShape(17.dp)).padding(20.dp)
        else Modifier.drawBehind{drawLine(if(journal)t.line else t.ink,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=if(journal)PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))else null)}.padding(vertical=20.dp)
    Column(Modifier.fillMaxWidth().testTag("analysis-detail-cover").then(frame)) {
        tier?.let{YText(it,10,color=t.muted)}
        YText(amount,if(tier==null)41 else 36,Modifier.padding(vertical=if(tier==null)15.dp else 14.dp).testTag("analysis-detail-net"),number=true,color=if(tier!=null&&journal)t.warm else t.ink)
        YText(note,10,color=t.muted,lineHeightMultiplier=1.9f)
    }
}
@Composable internal fun AnalysisChildRow(id:String,name:String,money:String,onSelect:()->Unit) {
    val t=LocalYohaku.current;val journal=t.id=="journal";val objects=t.id=="objects"
    val narrow=LocalConfiguration.current.screenWidthDp<=360
    val frame=if(objects)Modifier.background(Color(0xfff1e8d4),RoundedCornerShape(10.dp))else Modifier.drawBehind{drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=if(journal)PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))else null)}
    Row(Modifier.fillMaxWidth().testTag("analysis-child-$id").clickable(role=Role.Button,onClick=onSelect).then(frame).padding(horizontal=if(t.id=="magazine")0.dp else 12.dp,vertical=16.dp),horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.CenterVertically) {
        YText(name,if(narrow)11 else if(journal)16 else 12,Modifier.weight(1f),lineHeightMultiplier=1.8f)
        YText(money,if(narrow)15 else 17,number=true)
    }
}
