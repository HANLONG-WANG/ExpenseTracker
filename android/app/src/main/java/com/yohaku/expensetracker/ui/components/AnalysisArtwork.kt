package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.core.graphics.PathParser
import com.yohaku.expensetracker.ui.theme.LocalYohaku

/** Original themes.js botanical, pencil and everyday objects motifs. */
@Composable internal fun AnalysisArtwork(modifier:Modifier,tint:Color=LocalYohaku.current.accent){
    val t=LocalYohaku.current
    Canvas(modifier){
        val w=if(t.id=="magazine")150f else 180f
        val h=if(t.id=="magazine")150f else if(t.id=="journal")105f else 115f
        drawIntoCanvas{c->
            val canvas=c.nativeCanvas;canvas.save()
            val scale=minOf(size.width/w,size.height/h)
            canvas.translate((size.width-w*scale)/2,(size.height-h*scale)/2);canvas.scale(scale,scale)
            val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{color=tint.toArgb();style=android.graphics.Paint.Style.STROKE;strokeWidth=1.25f}
            fun path(d:String,fill:Int?=null){
                val p=PathParser.createPathFromPathData(d)
                if(fill!=null){paint.style=android.graphics.Paint.Style.FILL;paint.color=fill;canvas.drawPath(p,paint)}
                paint.style=android.graphics.Paint.Style.STROKE;paint.color=tint.toArgb();canvas.drawPath(p,paint)
            }
            when(t.id){
                "magazine"->path("M74 142c5-42 1-71 14-122M80 103C37 96 18 75 29 57c25 2 43 15 51 46ZM81 91c39-5 61-23 54-42-25-1-45 14-54 42ZM85 64C53 55 39 30 49 15c23 7 33 23 36 49ZM87 43c23-8 32-23 27-35-17 5-24 17-27 35Z")
                "journal"->path("M18 84c29-2 69-2 147-1M24 72l11-48 72 6-8 46-75-4ZM36 26l64 8m-64 7 55 7m-58 7 26 3M118 80l20-58 7 3-19 58-8-3Zm19-58 3-10 7 3-3 10m-26 55-3 9 11-6M51 12l5-6m3 11 8-1M156 58l7 3m-14 7 8 5m-85-11 4 5 10-9")
                else->{
                    paint.style=android.graphics.Paint.Style.FILL;paint.color=Color(0x1482644f).toArgb();canvas.drawOval(13f,82f,169f,106f,paint)
                    path("M24 33h57l-4 59H27Z",Color(0xfffff7e5).toArgb())
                    path("M30 43h43m-43 9h32m-32 9h38m-38 9h21")
                    path("M98 40h44v30a19 19 0 0 1-19 19h-6a19 19 0 0 1-19-19Z",Color(0xffbd7655).toArgb())
                    path("M142 45h9c18 0 18 28 0 28h-9M109 19c-8 10 8 10 0 20m15-23c-8 10 8 10 0 20")
                    paint.style=android.graphics.Paint.Style.FILL;paint.color=Color(0xffdbb46e).toArgb();canvas.drawOval(56f,77f,88f,93f,paint)
                    paint.style=android.graphics.Paint.Style.STROKE;paint.color=tint.toArgb();canvas.drawOval(56f,77f,88f,93f,paint)
                    path("M56 85v5c0 12 32 12 32 0v-5m-21-3 10 7m0-7-10 7")
                }
            }
            canvas.restore()
        }
    }
}
