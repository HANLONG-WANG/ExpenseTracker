package com.yohaku.expensetracker.ui.components
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.dp
import androidx.core.graphics.PathParser
import com.yohaku.expensetracker.ui.theme.LocalYohaku
private data class ArtStroke(val path:String,val opacity:Float)
private val drawings=mapOf(
"magazine" to listOf(ArtStroke("M31 103V29h82v74ZM40 45h43m-43 12h63M40 70h36m-36 12h63M65 112h82M93 94c-3-32 15-58 47-70M105 76c-21-2-22-14-11-20m17 5c21 0 31-8 28-21M95 103l15-35",1f),ArtStroke("M30.0 118.0a39.0 6.0 0 1 0 78.0 0a39.0 6.0 0 1 0 -78.0 0",.2f)),
"journal" to listOf(ArtStroke("m25 30 95-6 5 91-92 8Z M45 38l-2 64M57 46l49-4m-49 19 39-3m-38 19 49-3m-47 19 30-3M19 32l3 86M116 105l16-70 10 3-15 69-10 10Z M134 34l2-8 10 3-2 8",1f),ArtStroke("m66 108 4 4 10-13",1f)),
"objects" to listOf(ArtStroke("M14.0 115.0a66.0 10.0 0 1 0 132.0 0a66.0 10.0 0 1 0 -132.0 0",.14f),ArtStroke("M27 71h90l-5 39H34ZM42 70V51h62v20M66 56l6-28 38 8-7 27",1f),ArtStroke("M77 39l23 5m-25 4 17 4",1f),ArtStroke("M112.0 91.0a14.0 14.0 0 1 0 28.0 0a14.0 14.0 0 1 0 -28.0 0",1f),ArtStroke("M117.0 91.0a9.0 9.0 0 1 0 18.0 0a9.0 9.0 0 1 0 -18.0 0",1f),ArtStroke("M40.0 94.0a12.0 5.0 0 1 0 24.0 0a12.0 5.0 0 1 0 -24.0 0",1f),ArtStroke("M40 94v7c1 7 24 7 24 0v-7",1f)))
@Composable fun ThemeArtwork(modifier:Modifier=Modifier,width:Int=75,height:Int=94,tint:Color=LocalYohaku.current.muted) {
    val t=LocalYohaku.current
    Canvas(modifier.size(width.dp,height.dp)) {
        drawIntoCanvas { c->
            val canvas=c.nativeCanvas;canvas.save()
            val scale=minOf(size.width/160f,size.height/140f)
            canvas.translate((size.width-160f*scale)/2f,(size.height-140f*scale)/2f);canvas.scale(scale,scale)
            val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{color=tint.toArgb();style=android.graphics.Paint.Style.STROKE;strokeWidth=1.3f}
            drawings.getValue(t.id).forEach{shape->paint.alpha=(255*shape.opacity).toInt();canvas.drawPath(PathParser.createPathFromPathData(shape.path),paint)}
            canvas.restore()
        }
    }
}
