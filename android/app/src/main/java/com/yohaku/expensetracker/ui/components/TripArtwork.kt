package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.core.graphics.PathParser
import com.yohaku.expensetracker.ui.theme.LocalYohaku

/** Original tripArt SVGs from themes.js / art.js, including their independent palette. */
@Composable internal fun TripArtwork(modifier:Modifier) {
    val t=LocalYohaku.current
    Canvas(modifier) {drawIntoCanvas{context->
        val canvas=context.nativeCanvas;canvas.save()
        val w=if(t.id=="journal")110f else 180f;val h=if(t.id=="journal")80f else 120f
        val scale=minOf(size.width/w,size.height/h);canvas.translate((size.width-w*scale)/2,(size.height-h*scale)/2);canvas.scale(scale,scale)
        val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{strokeWidth=1.2f;strokeJoin=android.graphics.Paint.Join.ROUND;strokeCap=android.graphics.Paint.Cap.ROUND}
        fun path(d:String,stroke:Color?=null,fill:Color?=null) {val shape=PathParser.createPathFromPathData(d);fill?.let{paint.style=android.graphics.Paint.Style.FILL;paint.color=it.toArgb();canvas.drawPath(shape,paint)};stroke?.let{paint.style=android.graphics.Paint.Style.STROKE;paint.color=it.toArgb();canvas.drawPath(shape,paint)}}
        when(t.id) {
            "magazine"->{
                paint.color=Color(0xffbecbb1).toArgb();paint.style=android.graphics.Paint.Style.FILL;canvas.drawCircle(130f,30f,16f,paint)
                path("M10 103 66 35l46 68Z",fill=Color(0xffc8d4bd));path("m62 103 48-53 51 53Z",fill=Color(0xffa3b394))
                path("M16 103h150M45 87h77M69 83V49h27v34M64 47h38l-19-9ZM74 35h20l-10-6ZM83 29V18M77 61h13M77 70h13M78 83V75h10v8",Color(0xff536d4e));paint.strokeWidth=1f;path("M10 111h72m8 0h76",Color(0xff7b9170))
            }
            "journal"->{
                path("M7 69h97M19 68V38l22-17 23 17v30M15 39h53M27 43h30m-30 8h30m-29 17V55h12v13m8 0V55h8v13M23 24h36M28 19h25M36 13h10M41 6v8M75 68V29m0 0c-19-6-14-25 0-21 15-4 19 16 0 21m-4-4-9-7m14 0 8-5M70 48h22m-17-8h15M87 68V40",t.accent)
                path("m12 18 4-3m-8 9 4-1m80-9 4 2",t.accent)
            }
            else->{
                paint.style=android.graphics.Paint.Style.FILL;paint.color=Color(0xfffff5dc).toArgb();canvas.drawRoundRect(13f,9f,167f,108f,13f,13f,paint)
                paint.style=android.graphics.Paint.Style.STROKE;paint.color=Color(0xffd5b98e).toArgb();canvas.drawRoundRect(13f,9f,167f,108f,13f,13f,paint)
                paint.style=android.graphics.Paint.Style.FILL;paint.color=Color(0xffd9a573).toArgb();canvas.drawCircle(130f,33f,13f,paint)
                path("M27 87 66 35l39 52Z",fill=Color(0xffbcc9a4));path("m74 87 29-38 48 38Z",fill=Color(0xffd6dcb9))
                path("M70 87V54h31v33Z",Color(0xffb68b60),Color(0xffdfb690));path("m64 55 22-14 23 14Z",Color(0xff8b9c73),Color(0xffa0b088));path("M76 41h22l-12-8Z",Color(0xff8b9c73),Color(0xffa0b088))
                path("M80 88V75h12v13M77 64h18M26 95h126",Color(0xffb49a76));paint.strokeWidth=3f;path("M33 29h18m-13-5h8M124 65h17m-10-4h10",Color(0xfffff7e5))
            }
        };canvas.restore()
    }}
}
