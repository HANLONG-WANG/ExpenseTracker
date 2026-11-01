package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.*
import java.util.Locale

/** Map surface framing: magazine printed plate, journal photo pasted with tape, objects rounded enamel window. */
@Composable internal fun PlaceMapFrame(modifier:Modifier=Modifier,map:@Composable BoxScope.()->Unit) {
    val t=LocalYohaku.current
    when(t.id) {
        "journal"->Box(modifier.padding(top=8.dp)) {
            Box(Modifier.fillMaxWidth().background(t.surface).padding(6.dp).drawBehind{
                drawRect(t.line,style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))))
            }) { Box(Modifier.fillMaxWidth().height(280.dp).clipToBounds(),content=map) }
            Box(Modifier.align(Alignment.TopStart).offset(x=10.dp,y=(-8).dp).rotate(-8f).size(54.dp,16.dp).background(Color(0x66d9b98a)))
            Box(Modifier.align(Alignment.TopEnd).offset(x=(-12).dp,y=(-7).dp).rotate(6f).size(48.dp,16.dp).background(Color(0x5590a8a0)))
        }
        "objects"->{
            val shape=RoundedCornerShape(22.dp)
            Box(modifier.fillMaxWidth().drawBehind{
                drawRoundRect(Color(0x60e8decd),Offset(0f,4.dp.toPx()),cornerRadius=CornerRadius(22.dp.toPx()))
            }.clip(shape).background(t.soft).height(280.dp),content=map)
        }
        else->Column(modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(t.ink))
            Box(Modifier.fillMaxWidth().padding(vertical=6.dp).height(280.dp).clipToBounds(),content=map)
            Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
        }
    }
}

/** Matching radius drawn over the map center, in each theme's own ink. */
@Composable internal fun PlaceRadiusOverlay(radiusPx:Float,modifier:Modifier=Modifier) {
    val t=LocalYohaku.current
    Canvas(modifier.fillMaxSize()) {
        val r=radiusPx.coerceAtLeast(0f)
        when(t.id) {
            "journal"->{
                drawCircle(t.warm.copy(alpha=.08f),r)
                drawCircle(t.warm.copy(alpha=.7f),r,style=Stroke(1.6.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(),4.dp.toPx()))))
            }
            "objects"->{
                drawCircle(t.accent.copy(alpha=.16f),r)
                drawCircle(Color.White.copy(alpha=.85f),r,style=Stroke(3.dp.toPx()))
                drawCircle(t.accent.copy(alpha=.55f),r,style=Stroke(1.5.dp.toPx()))
            }
            else->{
                drawCircle(t.ink.copy(alpha=.05f),r)
                drawCircle(t.ink.copy(alpha=.75f),r,style=Stroke(1.dp.toPx()))
                // Registration crosshair through the whole plate.
                val c=center;val hair=t.ink.copy(alpha=.28f)
                drawLine(hair,Offset(0f,c.y),Offset(size.width,c.y),.6.dp.toPx())
                drawLine(hair,Offset(c.x,0f),Offset(c.x,size.height),.6.dp.toPx())
            }
        }
    }
}

/** The fixed center marker; its tip (or crossing) sits exactly on the map center. */
@Composable internal fun PlaceCenterPin(modifier:Modifier=Modifier) {
    val t=LocalYohaku.current
    when(t.id) {
        "journal"->Canvas(modifier.size(30.dp)) { stitchedCross(t.warm) }
        "objects"->Canvas(modifier.size(34.dp,46.dp).offset(y=(-23).dp)) {
            val w=size.width
            drawCircle(Color(0x33634b3b),w*.18f,Offset(w/2,size.height-2.dp.toPx()))
            val drop=Path().apply{moveTo(w/2,size.height-3.dp.toPx());lineTo(w*.18f,w*.62f);lineTo(w*.82f,w*.62f);close()}
            drawPath(drop,t.accent)
            drawCircle(t.accent,w/2*.86f,Offset(w/2,w/2))
            drawCircle(Color.White,w/2*.38f,Offset(w/2,w/2))
        }
        else->Canvas(modifier.size(14.dp)) {
            drawRect(t.ink)
            drawRect(t.surface,Offset(size.width*.32f,size.height*.32f),size*.36f)
        }
    }
}
private fun DrawScope.stitchedCross(color:Color) {
    val s=size.minDimension;val w=2.2.dp.toPx()
    drawLine(color,Offset(s*.18f,s*.2f),Offset(s*.82f,s*.8f),w,StrokeCap.Round)
    drawLine(color,Offset(s*.8f,s*.18f),Offset(s*.2f,s*.82f),w,StrokeCap.Round)
    drawCircle(color,s*.46f,style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))))
}

/** Coordinates read-out under the map. */
@Composable internal fun PlaceCoordinates(latitude:Double,longitude:Double,radius:String,modifier:Modifier=Modifier) {
    val t=LocalYohaku.current
    val text=String.format(Locale.ROOT,"%.5f, %.5f",latitude,longitude)
    when(t.id) {
        "journal"->YText(word("图钉在 ","ピンの位置 ")+text+word(" · 半径 ${radius}m"," · 半径${radius}m"),12,modifier,color=t.accent)
        "objects"->Row(modifier,verticalAlignment=Alignment.CenterVertically) {
            YText(text,11,Modifier.background(t.soft,RoundedCornerShape(50)).padding(horizontal=10.dp,vertical=4.dp),color=t.ink,number=true)
            YText("  ⌀ ${radius}m",11,color=t.muted,number=true)
        }
        else->YText("PIN  $text   R ${radius}M",9,modifier,color=t.muted,number=true,letterSpacing=1.2f)
    }
}
