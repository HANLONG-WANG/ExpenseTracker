package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.LocalYohaku

/** Final full-button/outline-button styles; caller still owns width and action. */
@Composable fun YButton(label:String,onClick:()->Unit,modifier:Modifier=Modifier,primary:Boolean=false,enabled:Boolean=true,inline:Boolean=false){
    val t=LocalYohaku.current
    val journal=t.id=="journal";val objects=t.id=="objects"
    val shape=when(t.id){
        "journal"->if(primary)RoundedCornerShape(3.dp,6.dp,2.dp,5.dp) else RoundedCornerShape(2.dp,3.dp,1.dp,5.dp)
        "objects"->if(primary)RoundedCornerShape(14.dp,14.dp,20.dp,14.dp) else RoundedCornerShape(12.dp)
        else->RoundedCornerShape(0.dp)
    }
    val background=if(primary)if(objects)Color(0xffb67551) else if(journal)t.accent else t.ink else if(objects)Color(0xfffff9eb) else Color.Transparent
    val border=if(objects)if(primary)Color(0xffab6847) else Color(0xffdcc4a0) else if(journal)t.accent else t.ink
    val textColor=if(primary)if(objects)Color(0xfffff6e3) else t.surface else t.accent
    val padding=if(primary)if(objects)15 else if(journal)12 else 16 else 12
    val fontSize=if(primary)if(objects)14 else if(journal)16 else 11 else if(objects)12 else if(journal)13 else 10
    Box(modifier.then(if(primary)LocalActionRow.current?:Modifier.fillMaxWidth() else Modifier).then(if(journal&&!primary)Modifier.rotate(-.3f) else Modifier).alpha(if(enabled)1f else .5f).clickable(enabled=enabled,role=Role.Button,onClick=onClick),propagateMinConstraints=true){
        if(primary&&(journal||objects)||objects&&!primary)Box(Modifier.matchParentSize().offset(x=if(journal)2.dp else 0.dp,y=if(primary)if(journal)3.dp else 4.dp else 2.dp).background(if(journal)Color(0x233b5b70) else if(primary)Color(0x378b5437) else Color(0x15c6ab80),shape))
        val line=if(journal&&!primary)Modifier.drawBehind{
            val path=androidx.compose.ui.graphics.Path().apply{
                when(val outline=shape.createOutline(size,layoutDirection,this@drawBehind)){
                    is Outline.Rounded->addRoundRect(outline.roundRect)
                    is Outline.Rectangle->addRect(outline.rect)
                    is Outline.Generic->addPath(outline.path)
                }
            }
            drawPath(path,border,style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))))
        } else if(primary&&!journal&&!objects)Modifier else Modifier.border(1.dp,border,shape)
        Box(Modifier.clip(shape).background(background,shape).then(line).padding(horizontal=(padding+if(primary&&!journal&&!objects)0 else 1).dp,vertical=((if(inline&&!primary&&(journal||objects))8 else padding)+if(primary&&!journal&&!objects)0 else 1).dp),contentAlignment=Alignment.Center){
            YText(label,fontSize,color=textColor,letterSpacing=if(t.id=="magazine"&&primary).5f else 0f,lineHeightMultiplier=if(journal)if(primary)24f/16 else 19f/13 else if(objects)if(primary)20f/14 else 17f/12 else if(primary)16f/11 else 15f/10,cssLineBox=true)
        }
    }
}
