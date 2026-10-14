package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.LocalYohaku

/** Final .toast styles; lifetime and actual success are owned by the caller. */
@Composable fun YToast(message:String,modifier:Modifier=Modifier){
    val t=LocalYohaku.current
    val journal=t.id=="journal";val objects=t.id=="objects"
    val shape=when(t.id){
        "journal"->RoundedCornerShape(2.dp,5.dp,1.dp,4.dp)
        "objects"->RoundedCornerShape(12.dp,12.dp,17.dp,12.dp)
        else->RoundedCornerShape(0.dp)
    }
    val background=if(journal)Color(0xfffff8e4) else if(objects)Color(0xfffff7e5) else t.ink
    val color=if(journal)t.accent else if(objects)Color(0xff997248) else t.surface
    Box(modifier.then(if(journal)Modifier.rotate(-1f) else Modifier).testTag("theme-toast").semantics(mergeDescendants=true){liveRegion=LiveRegionMode.Polite}){
        if(journal||objects)Box(Modifier.matchParentSize().offset(x=if(journal)3.dp else 0.dp,y=4.dp).background(if(journal)Color(0x20927850) else Color(0x25b48e60),shape))
        val edge=when(t.id){
            "journal"->Modifier.drawBehind{
                val outline=shape.createOutline(size,layoutDirection,this)
                val path=androidx.compose.ui.graphics.Path().apply{addRoundRect((outline as androidx.compose.ui.graphics.Outline.Rounded).roundRect)}
                drawPath(path,t.accent,style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))))
            }
            "objects"->Modifier.border(1.dp,Color(0xffdec39c),shape)
            else->Modifier.drawBehind{drawRect(Color(0xffbac9ac),Offset.Zero,Size(3.dp.toPx(),size.height))}
        }
        Row(Modifier.background(background,shape).then(edge).padding(start=if(journal||objects)21.dp else 23.dp,end=if(journal||objects)21.dp else 20.dp,top=if(journal||objects)14.dp else 13.dp,bottom=if(journal||objects)14.dp else 13.dp),verticalAlignment=Alignment.CenterVertically){
            if(objects)Box(Modifier.size(20.dp).background(Color(0xffdde5cb),RoundedCornerShape(7.dp)),contentAlignment=Alignment.Center){YText("✓",13,color=Color(0xff8b9b71),lineHeightMultiplier=1f)}
            else YText(if(journal)"✓ " else "✓",if(journal)17 else 10,color=if(journal)t.warm else color,lineHeightMultiplier=if(journal)25f/17 else 1.5f)
            if(!journal)Spacer(Modifier.width(9.dp))
            YText(message,if(journal)13 else if(objects)12 else 10,Modifier.weight(1f,fill=false),color=color,lineHeightMultiplier=if(journal)19f/13 else if(objects)17f/12 else 1.5f)
        }
    }
}
