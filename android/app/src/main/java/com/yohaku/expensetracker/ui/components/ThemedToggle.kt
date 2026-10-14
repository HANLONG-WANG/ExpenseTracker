package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontStyle
import com.yohaku.expensetracker.ui.theme.LocalYohaku

@Composable fun YToggle(label:String,value:Boolean,onChange:(Boolean)->Unit){
    val t=LocalYohaku.current
    val journal=t.id=="journal";val objects=t.id=="objects"
    Row(Modifier.fillMaxWidth().testTag("toggle-$label").toggleable(value,role=if(journal)Role.Checkbox else Role.Switch,onValueChange=onChange).padding(vertical=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
        val size=if(journal)13 else if(objects)12 else 11
        YText(label,size,Modifier.weight(1f),lineHeightMultiplier=if(journal)19f/13 else if(objects)17f/12 else 16f/11)
        when(t.id){
            "journal"->Box(Modifier.size(22.dp).rotate(-4f).background(if(value)Color(0xffdae1d3) else Color.Transparent,RoundedCornerShape(2.dp)).border(1.dp,t.accent,RoundedCornerShape(2.dp)),contentAlignment=Alignment.Center){
                // The check is a fixed-size icon; the label still follows fontScale.
                if(value)androidx.compose.material3.Text("✓",Modifier.align(Alignment.TopCenter).offset(y=1.dp).wrapContentHeight(Alignment.Top,unbounded=true),color=t.accent,fontFamily=t.body,fontSize=with(LocalDensity.current){22.dp.toSp()},fontStyle=FontStyle.Italic,lineHeight=with(LocalDensity.current){32.dp.toSp()})
            }
            "objects"->Box(Modifier.size(39.dp,23.dp).background(if(value)Color(0xffaabb8e) else Color(0xffe8d8bd),RoundedCornerShape(10.dp)).border(1.dp,if(value)Color(0xff97aa7e) else Color(0xffd6bea0),RoundedCornerShape(10.dp))){
                val knob=Modifier.offset(x=if(value)19.dp else 4.dp,y=3.dp).size(16.dp)
                Box(knob.offset(y=2.dp).background(Color(0x1abc9c71),RoundedCornerShape(6.dp)))
                Box(knob.background(Color(0xfffffbef),RoundedCornerShape(6.dp)))
            }
            else->Box(Modifier.size(32.dp,18.dp).background(if(value)t.accent else Color.Transparent).border(1.dp,t.ink)){
                Box(Modifier.offset(x=if(value)18.dp else 4.dp,y=4.dp).size(10.dp).background(if(value)t.surface else t.line))
            }
        }
    }
}
