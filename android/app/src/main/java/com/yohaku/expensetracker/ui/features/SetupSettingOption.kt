package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.components.YText
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun SetupSettingOption(label:String,onClick:()->Unit,modifier:Modifier=Modifier) {
    val t=LocalYohaku.current
    val fontSize=if(t.id=="journal")15 else 12
    val padding=if(t.id=="objects")15 else 22
    YText(label,fontSize,modifier.fillMaxWidth().clickable(onClick=onClick).drawBehind{
        drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=if(t.id!="magazine")PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())) else null)
    }.padding(top=padding.dp,bottom=(padding+1).dp),color=t.accent,lineHeightMultiplier=1.8f,cssLineBox=true)
}
