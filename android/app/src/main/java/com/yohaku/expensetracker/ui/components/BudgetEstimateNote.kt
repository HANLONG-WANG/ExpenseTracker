package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun BudgetEstimateNote(count:Int) {
    val t=LocalYohaku.current
    val frame=if(t.id=="objects")Modifier.background(Color(0xfff0e5cf),RoundedCornerShape(10.dp)).padding(13.dp)
        else if(t.id=="magazine")Modifier.padding(top=12.dp)
        else Modifier.drawBehind{drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=if(t.id=="journal")PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))else null)}.padding(vertical=12.dp)
    YText(word("预算暂含 $count 笔预计换算，入账确认后更新。","予算に仮換算${count}件を含む。計上確定後に更新します。"),if(t.id=="journal")11 else 9,Modifier.fillMaxWidth().padding(bottom=5.dp).testTag("budget-estimate-note").then(frame),color=if(t.id=="journal")t.warm else t.muted,lineHeightMultiplier=1.9f)
}
