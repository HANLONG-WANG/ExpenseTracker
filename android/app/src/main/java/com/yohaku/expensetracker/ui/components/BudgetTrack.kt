package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.LocalYohaku
import kotlin.math.roundToInt

/** Visual proportions only; monetary totals remain in the shared integer budget projection. */
@Composable internal fun BudgetTrack(used:Long,limit:Long,count:Int=18,modifier:Modifier=Modifier,height:Int?=null) {
    val t=LocalYohaku.current
    val fraction=if(limit==0L){if(used>0)1f else 0f}else (used.toDouble()/limit).toFloat().coerceIn(0f,1f)
    val filled=(fraction*count).roundToInt()
    Canvas(modifier.fillMaxWidth().height(height?.dp?:if(t.id=="journal")7.dp else 5.dp).semantics{progressBarRangeInfo=ProgressBarRangeInfo(fraction,0f..1f)}) {
        val gap=(if(t.id=="journal")5 else 4).dp.toPx();val width=(size.width-gap*(count-1))/count
        repeat(count){index->
            val color=if(t.id=="objects")if(index<filled)Color(0xffb37a43)else Color(0xffddc89e)else if(index<filled)t.accent else t.line
            val x=index*(width+gap)
            rotate(if(t.id=="journal")-3f else 0f,Offset(x+width/2,size.height/2)){drawRoundRect(color,Offset(x,0f),Size(width,size.height),CornerRadius((if(t.id=="journal")1 else 2).dp.toPx()))}
        }
    }
}
