package com.yohaku.expensetracker.platform.desktop

import android.content.Context
import android.graphics.*
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.PathParser
import com.yohaku.expensetracker.domain.category.*
import com.yohaku.expensetracker.ui.theme.ThemeTypefaces
import kotlin.math.ceil

internal fun color(hex:String)=Color.parseColor(hex)
internal val pocketColors=listOf("#e2e7d5","#e0e9ec","#f0ddcf","#efe3cc")
internal val pocketInks=listOf("#6f7f5c","#5f7f89","#a0664a","#8a6948")

internal class DesktopArtwork(private val context:Context,private val theme:String) {
    // 不叫 density：TextPaint.apply 内会被 TextPaint.density（默认 1）遮蔽。
    private val pxPerDp=context.resources.displayMetrics.density
    private val fontScale=context.resources.configuration.fontScale
    private fun bitmap(width:Int,height:Int)=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).apply{density=context.resources.displayMetrics.densityDpi}
    /** 只画一个文字字段，按实际 dp×density 分配；单行超长名称省略，读屏保留全文。 */
    fun text(value:String,widthDp:Float,sizeSp:Float,ink:Int,number:Boolean=false,smallSymbol:Boolean=false):Bitmap {
        val width=ceil(widthDp*pxPerDp).toInt().coerceAtLeast(1)
        val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color=ink;typeface=if(number)ThemeTypefaces.number(context,theme)else ThemeTypefaces.body(context,theme)
            textSize=sizeSp*pxPerDp*fontScale
        }
        val prefix=if(smallSymbol)listOf("CN¥","US$","¥").firstOrNull{value.startsWith(it)}.orEmpty()else ""
        val symbolPaint=TextPaint(paint).apply{textSize=17f*pxPerDp*fontScale}
        val digits=value.removePrefix(prefix)
        val measured=if(prefix.isEmpty())paint.measureText(value)else symbolPaint.measureText(prefix)+paint.measureText(digits)
        // 金额完整显示，名称保持字号并省略；缩小仅用于实际列宽无法容纳的金额。
        if(number&&measured>width) {
            val ratio=width/measured;paint.textSize*=ratio;symbolPaint.textSize*=ratio
        }
        val text=if(prefix.isEmpty())TextUtils.ellipsize(value,paint,width.toFloat(),TextUtils.TruncateAt.END).toString()else digits
        val fm=paint.fontMetrics
        // top/bottom 取回退链首个字体的包围盒：journal_math 只有算式符号（0.22–0.28em），editorial/round_cn 又过大；
        // ascent/descent 来自 hhea 行高，三主题都正常，也是 Compose 排版所用的值。
        val height=ceil(fm.descent-fm.ascent).toInt().coerceAtLeast(1)
        // 裁掉行尾透明区，ImageView fitStart 保持固有高度，不把短文字放大。
        val prefixWidth=if(prefix.isEmpty())0f else symbolPaint.measureText(prefix)
        val inkWidth=ceil(prefixWidth+paint.measureText(text)+pxPerDp).toInt().coerceIn(1,width)
        return bitmap(inkWidth,height).also {
            val canvas=Canvas(it)
            if(prefix.isNotEmpty())canvas.drawText(prefix,0f,-fm.ascent,symbolPaint)
            canvas.drawText(text,prefixWidth,-fm.ascent,paint)
        }
    }
    fun paragraph(value:String,widthDp:Float,sizeSp:Float,ink:Int):Bitmap {
        val width=ceil(widthDp*pxPerDp).toInt().coerceAtLeast(1)
        val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color=ink;typeface=ThemeTypefaces.body(context,theme);textSize=sizeSp*pxPerDp*fontScale
        }
        val layout=android.text.StaticLayout.Builder.obtain(value,0,value.length,paint,width)
            .setIncludePad(false).setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).build()
        return bitmap(width,layout.height.coerceAtLeast(1)).also{layout.draw(Canvas(it))}
    }
    fun category(icon:String,index:Int,sizeDp:Float=24f,adaptive:Boolean=false):Bitmap {
        val size=ceil(sizeDp*pxPerDp).toInt()
        return bitmap(size,size).also {bitmap->
            val canvas=Canvas(bitmap);canvas.scale(size/108f,size/108f)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=color(pocketColors[index])}
            if(adaptive)canvas.drawColor(paint.color)else canvas.drawCircle(54f,54f,54f,paint)
            val side=if(adaptive)44f else 63f
            canvas.translate((108-side)/2,(108-side)/2);canvas.scale(side/24f,side/24f)
            paint.color=color(pocketInks[index]);paint.style=Paint.Style.STROKE;paint.strokeWidth=1.6f
            paint.strokeCap=Paint.Cap.ROUND;paint.strokeJoin=Paint.Join.ROUND
            (categoryIconOf(icon)?:categoryIconOf(DEFAULT_CATEGORY_ICON)!!).paths.forEach{canvas.drawPath(PathParser.createPathFromPathData(it),paint)}
        }
    }
    fun shortcut(name:String,icon:String,index:Int):Bitmap {
        if(theme=="objects")return category(icon,index,108f,true)
        val size=ceil(108*pxPerDp).toInt()
        val bitmap=bitmap(size,size)
        val canvas=Canvas(bitmap);canvas.scale(size/108f,size/108f)
        canvas.drawColor(color(if(theme=="journal")"#faf5e7"else"#fffef8"))
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=color(if(theme=="journal")"#3b5b70"else"#263d32");typeface=ThemeTypefaces.body(context,theme)}
        val first=name.take(name.offsetByCodePoints(0,1))
        if(theme=="journal") {
            paint.style=Paint.Style.STROKE;paint.strokeWidth=2.8f
            canvas.save();canvas.rotate(-5f,45f,49f);canvas.drawRoundRect(30f,33f,57f,61f,3f,3f,paint);canvas.restore()
            paint.color=color("#ae6657");paint.strokeWidth=3.5f;paint.strokeCap=Paint.Cap.ROUND
            canvas.drawPath(Path().apply{moveTo(34f,46f);lineTo(43f,56f);lineTo(62f,29f)},paint)
            paint.style=Paint.Style.FILL;paint.color=color("#3b5b70");paint.textSize=23f
            canvas.drawText(first,66f-paint.measureText(first)/2,76f,paint)
        }else {
            paint.textSize=38f;canvas.drawText(first,54f-paint.measureText(first)/2,65f,paint)
            paint.color=color("#bc664f");canvas.drawRect(34f,75f,74f,77f,paint)
        }
        return bitmap
    }
}
