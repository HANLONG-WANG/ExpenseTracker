package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun RecurringCard(id:String,name:String,date:String,metadata:String,amount:String,known:Boolean,status:String,warning:Boolean,busy:Boolean,onConfirm:(()->Unit)?,onEdit:()->Unit,showDetails:Boolean=true,details:@Composable ColumnScope.()->Unit) {
    val t=LocalYohaku.current
    val narrow=LocalConfiguration.current.screenWidthDp<=360||LocalDensity.current.fontScale>1.2f
    val shape=RoundedCornerShape(if(t.id=="objects")20.dp else if(t.id=="journal")0.dp else 3.dp)
    val edge=if(t.id=="journal")Modifier.drawBehind{drawRect(t.line,style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))))}else if(t.id=="objects")Modifier.drawBehind{drawRoundRect(Color(0x60e8decd),Offset(0f,3.dp.toPx()),cornerRadius=androidx.compose.ui.geometry.CornerRadius(20.dp.toPx()))}else Modifier.border(1.dp,t.line,shape)
    Column(Modifier.fillMaxWidth().testTag("occurrence-$id").then(if(t.id=="objects")edge.background(t.surface,shape)else Modifier.background(t.surface,shape).then(edge)).padding(19.dp),verticalArrangement=Arrangement.spacedBy(0.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(11.dp),verticalAlignment=Alignment.CenterVertically) {
            MiniCalendar(date)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                YText(name,12,bold=true)
                if(!narrow)YText(metadata,9,color=t.muted)
            }
            YText(amount,16,number=known)
        }
        if(narrow)YText(metadata,9,Modifier.padding(top=8.dp),color=t.muted)
        YText(date,9,Modifier.padding(top=6.dp),color=t.muted)
        val divider=Modifier.drawBehind{drawLine(t.line,Offset.Zero,Offset(size.width,0f),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx())))}
        @Composable fun badge() {
            YText(status,9,Modifier.testTag("occurrence-status-$id").background(if(warning)Color(0xfff2dfd4)else t.soft,RoundedCornerShape(4.dp)).padding(horizontal=7.dp,vertical=4.dp),color=if(warning)Color(0xff995742)else t.accent)
        }
        @Composable fun actions() {
            onConfirm?.let{YButton(word("确认付款","支払を確認"),it,Modifier.testTag("confirm-occurrence-$id"),enabled=!busy,inline=true)}
            YButton(word("编辑","編集"),onEdit,Modifier.testTag("edit-occurrence-$id"),enabled=!busy,inline=true)
        }
        if(narrow)Column(Modifier.fillMaxWidth().padding(top=14.dp).then(divider).padding(top=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            badge()
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){actions()}
        }else Row(Modifier.fillMaxWidth().padding(top=14.dp).then(divider).padding(top=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.weight(1f)){badge()}
            actions()
        }
        if(showDetails)Column(Modifier.fillMaxWidth().padding(top=10.dp),verticalArrangement=Arrangement.spacedBy(8.dp),content=details)
    }
}
