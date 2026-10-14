package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.components.YText
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun HistoricalOpeningToggle(startDate:String,value:Boolean,onChange:(Boolean)->Unit){
    val t=LocalYohaku.current
    val paper=t.id=="journal";val objects=t.id=="objects"
    val label=word("历史补录：已包含在期初余额","履歴追加：期首残高に含まれる")
    Row(Modifier.fillMaxWidth().testTag("toggle-$label").toggleable(value,role=if(paper)Role.Checkbox else Role.Switch,onValueChange=onChange).drawBehind{
        val effect=if(t.id=="magazine")null else PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))
        val color=if(objects)Color(0xffd9c4a0) else t.line
        if(!objects)drawLine(color,Offset(0f,.5.dp.toPx()),Offset(size.width,.5.dp.toPx()),1.dp.toPx(),pathEffect=effect)
        drawLine(color,Offset(0f,size.height-.5.dp.toPx()),Offset(size.width,size.height-.5.dp.toPx()),1.dp.toPx(),pathEffect=effect)
    }.padding(top=if(objects)13.dp else 14.dp,bottom=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(7.dp)){
            YText(label,if(paper)16 else 13,weight=if(paper)FontWeight.Normal else FontWeight.Medium,lineHeightMultiplier=if(paper)24f/16 else 19f/13,cssLineBox=true)
            YText(word("只补记录，不再次改变余额。账本开始日","記録のみ追加、残高を再変更しない。帳簿開始日")+" $startDate",if(paper)11 else 9,Modifier.testTag("historical-opening-description"),color=t.muted,lineHeightMultiplier=1.9f,cssLineBox=true)
        }
        when(t.id){
            "journal"->Box(Modifier.size(25.dp).rotate(-3f).background(Color(0xfffff7e2),RoundedCornerShape(1.dp)).border(1.dp,t.accent,RoundedCornerShape(1.dp))){
                if(value)androidx.compose.material3.Text("✓",Modifier.offset(x=3.dp,y=(-3).dp).rotate(-9f).wrapContentHeight(Alignment.Top,unbounded=true),color=t.warm,fontFamily=t.body,fontSize=with(LocalDensity.current){30.dp.toSp()},fontStyle=FontStyle.Italic,lineHeight=with(LocalDensity.current){43.dp.toSp()})
            }
            "objects"->Box(Modifier.size(39.dp,23.dp).clip(RoundedCornerShape(9.dp)).background(if(value)Color(0xffb8825f) else Color(0xffd9c8ad)).drawBehind{
                drawRect(Brush.verticalGradient(listOf(Color(0x30a98c65),Color.Transparent),endY=4.dp.toPx()))
            }){
                val knob=Modifier.offset(x=if(value)19.dp else 3.dp,y=3.dp).size(17.dp)
                Box(knob.offset(y=2.dp).background(Color(0x279e805c),RoundedCornerShape(6.dp)))
                Box(knob.background(Color(0xfffff8e6),RoundedCornerShape(6.dp)))
            }
            else->Box(Modifier.size(36.dp,20.dp).background(if(value)t.soft else t.bg).border(1.dp,t.ink)){
                Box(Modifier.offset(x=if(value)20.dp else 4.dp,y=4.dp).size(12.dp).background(if(value)t.ink else t.muted))
            }
        }
    }
}
