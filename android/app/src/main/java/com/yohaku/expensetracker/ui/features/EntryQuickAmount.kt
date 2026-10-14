package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.domain.model.Currency
import com.yohaku.expensetracker.domain.model.Money
import com.yohaku.expensetracker.ui.components.YText
import com.yohaku.expensetracker.ui.theme.LocalYohaku

@Composable internal fun EntryQuickAmount(amount:String,onAmount:(String)->Unit){
    val t=LocalYohaku.current
    val current=if(amount.isBlank())0L else runCatching{Money.parse(amount,Currency.JPY).minor}.getOrNull()
    FlowRow(Modifier.fillMaxWidth().testTag("entry-quick-amount"),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        for(increment in listOf(100L,500L,1000L)){
            val frame=when(t.id){
                "magazine"->Modifier.border(1.dp,t.line)
                "journal"->Modifier.rotate(-2f).background(Color(0xffe9e9d6)).drawBehind{drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())))}
                else->Modifier.drawBehind{drawRoundRect(Color(0xffdacbb0),topLeft=Offset(0f,2.dp.toPx()),cornerRadius=androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()))}.background(Color(0xffeee3cc),RoundedCornerShape(7.dp))
            }
            val enabled=current!=null&&current<=Long.MAX_VALUE-increment
            YText(if(increment==1000L)"+¥1,000" else "+¥$increment",if(t.id=="journal")13 else 10,
                Modifier.testTag("entry-quick-$increment").then(frame).clickable(enabled=enabled,role=Role.Button){onAmount((current!!+increment).toString())}
                    .padding(horizontal=if(t.id=="objects")0.dp else if(t.id=="magazine")11.dp else 10.dp,vertical=if(t.id=="objects")15.dp else if(t.id=="magazine")9.dp else 8.dp)
                    .then(if(t.id=="journal")Modifier.padding(bottom=1.dp) else Modifier),
                color=t.accent,lineHeightMultiplier=1.8f,cssLineBox=true)
        }
    }
}
