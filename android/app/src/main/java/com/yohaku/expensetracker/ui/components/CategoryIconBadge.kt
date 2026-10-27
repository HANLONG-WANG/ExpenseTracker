package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.*
import com.yohaku.expensetracker.domain.category.*
import com.yohaku.expensetracker.ui.theme.*

/** Badge sizes: list rows, compact child rows, the editor field, the picker hero and picker grid cells. */
internal enum class CategoryBadge { ROW, SMALL, FIELD, HERO, CELL }

/** Objects tints each icon by its catalog group; the other themes stay monochrome. */
private val groupTints=mapOf(
    "food" to (Color(0xfff6dcc4) to Color(0xffa8653d)),"transport" to (Color(0xffd8e4d3) to Color(0xff5e7a5a)),"shopping" to (Color(0xfff2d5cc) to Color(0xffa85a48)),
    "home" to (Color(0xffebdfc4) to Color(0xff8a6a3c)),"fun" to (Color(0xffdcdbea) to Color(0xff64628f)),"health" to (Color(0xffd3e5e1) to Color(0xff4f7d74)),
    "learn" to (Color(0xffe7e2c5) to Color(0xff7f7440)),"social" to (Color(0xfff2d6dc) to Color(0xffa25867)),"finance" to (Color(0xffdfe6cc) to Color(0xff6a7a43)),
    "other" to (Color(0xffe9e1d3) to Color(0xff857565)))
internal fun categoryIconTint(icon:String)=groupTints[categoryIconGroupOf(icon)?.id] ?: groupTints.getValue("other")

@Composable internal fun CategoryGlyph(icon:String,modifier:Modifier=Modifier,tint:Color=LocalYohaku.current.ink,stroke:Float=1.6f) =
    YPathIcon((categoryIconOf(icon) ?: categoryIconOf(DEFAULT_CATEGORY_ICON)!!).paths,modifier,tint,stroke)

@Composable internal fun CategoryIconBadge(icon:String,size:CategoryBadge=CategoryBadge.ROW,modifier:Modifier=Modifier,selected:Boolean=false) {
    val t=LocalYohaku.current
    when(t.id) {
        "journal"->{
            // Inked rubber stamps; the chosen cell is circled by hand in red.
            val box=when(size){CategoryBadge.ROW->31;CategoryBadge.SMALL->27;CategoryBadge.FIELD->46;CategoryBadge.HERO->66;CategoryBadge.CELL->36}
            val glyph=when(size){CategoryBadge.ROW->17;CategoryBadge.SMALL->15;CategoryBadge.FIELD->24;CategoryBadge.HERO->33;CategoryBadge.CELL->21}
            val ink=if(selected)t.warm else t.accent
            Box(modifier.size(box.dp).rotate(if(selected)-8f else if(size==CategoryBadge.HERO)-6f else if(size==CategoryBadge.CELL)0f else -4f).scale(if(selected)1.12f else 1f).drawBehind{
                val r=this.size.minDimension/2
                if(size==CategoryBadge.HERO)drawCircle(t.warm,r+4.dp.toPx(),style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))))
                if(selected)drawCircle(t.warm,r-.8.dp.toPx(),style=Stroke(1.6.dp.toPx()))
                else if(size!=CategoryBadge.CELL)drawCircle(t.accent,r-.5.dp.toPx(),style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))))
            },contentAlignment=Alignment.Center){CategoryGlyph(icon,Modifier.size(glyph.dp),ink,1.5f)}
        }
        "objects"->{
            // Little enamel tiles coloured by catalog group.
            val (bg,fg)=categoryIconTint(icon)
            val box=when(size){CategoryBadge.ROW->32;CategoryBadge.SMALL->27;CategoryBadge.FIELD->46;CategoryBadge.HERO->68;CategoryBadge.CELL->32}
            val glyph=when(size){CategoryBadge.ROW->18;CategoryBadge.SMALL->16;CategoryBadge.FIELD->25;CategoryBadge.HERO->36;CategoryBadge.CELL->18}
            val radius=when(size){CategoryBadge.SMALL->8;CategoryBadge.FIELD->14;CategoryBadge.HERO->20;else->10}
            val shape=RoundedCornerShape(radius.dp)
            val shadow=if(size==CategoryBadge.CELL)0 else if(size==CategoryBadge.HERO)4 else 2
            Box(modifier.size(box.dp).rotate(if(size==CategoryBadge.HERO)-4f else 0f).drawBehind{
                if(shadow>0)drawRoundRect(Color(if(size==CategoryBadge.HERO)0x29785a32 else 0x1f785a32),Offset(0f,shadow.dp.toPx()),cornerRadius=androidx.compose.ui.geometry.CornerRadius(radius.dp.toPx()))
            }.background(bg,shape),contentAlignment=Alignment.Center){CategoryGlyph(icon,Modifier.size(glyph.dp),fg,1.7f)}
        }
        else->{
            // Bare editorial line glyphs; framed only where the icon is the subject.
            val glyph=when(size){CategoryBadge.ROW->20;CategoryBadge.SMALL->18;CategoryBadge.FIELD->23;CategoryBadge.HERO->34;CategoryBadge.CELL->21}
            val ink=if(selected)t.surface else t.ink
            val stroke=if(size==CategoryBadge.HERO)1.25f else 1.45f
            when(size) {
                CategoryBadge.FIELD,CategoryBadge.HERO->Box(modifier.size(if(size==CategoryBadge.HERO)68.dp else 44.dp).border(1.dp,if(size==CategoryBadge.HERO)t.ink else t.line),contentAlignment=Alignment.Center){CategoryGlyph(icon,Modifier.size(glyph.dp),ink,stroke)}
                else->CategoryGlyph(icon,modifier.size(glyph.dp),ink,stroke)
            }
        }
    }
}
