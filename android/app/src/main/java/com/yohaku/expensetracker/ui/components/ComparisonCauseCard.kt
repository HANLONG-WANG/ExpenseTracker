package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.*
import com.yohaku.expensetracker.domain.model.*

/** A period change reads as a direction: "+¥2,205" or "−¥1,660", never "¥-1,660". */
internal fun signedMoney(currency:Currency,minor:Long)=when {
    minor>0->"+"+Money(currency,minor).display()
    minor<0->"−"+Money(currency,Math.negateExact(minor)).display()
    else->Money(currency,0).display()
}

internal data class ComparisonCause(val id:String,val title:String,val before:String,val after:String,val change:String,val increase:Boolean)

/**
 * One compact table per viewpoint (category / merchant / scene): each cause is a single row with its
 * name, "comparison → current" amounts and the signed change. Tapping a row opens the current-period
 * records; the detail sheet switches to the comparison period itself.
 */
@Composable internal fun ComparisonCauseList(id:String,items:List<ComparisonCause>,onOpen:((String)->Unit)?) {
    val t=LocalYohaku.current
    // More spending is the warm signal in every theme; less spending stays calm in the theme's own ink.
    fun changeColor(c:ComparisonCause)=if(c.increase)t.warm else if(t.id=="objects")t.ink else t.accent
    @Composable fun row(c:ComparisonCause,index:Int,divider:DrawScopeDivider) {
        Row(Modifier.fillMaxWidth().testTag("comparison-cause-${c.id}").then(if(onOpen!=null)Modifier.clickable(role=Role.Button){onOpen(c.id)}else Modifier)
            .drawBehind{if(index<items.lastIndex)divider(this)}.padding(vertical=if(t.id=="objects")11.dp else 12.dp),
            horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
            when(t.id) {
                "magazine"->YText((index+1).toString().padStart(2,'0'),11,Modifier.widthIn(min=18.dp),number=true,color=t.muted)
                "journal"->YText(if(c.increase)"↑" else "↓",14,Modifier.widthIn(min=12.dp),color=changeColor(c))
                else->Box(Modifier.size(8.dp).background(if(c.increase)Color(0xffe2a27f)else Color(0xffb9c49c),RoundedCornerShape(50)))
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                YText(c.title,if(t.id=="journal")15 else 13,weight=if(t.id=="journal")FontWeight.Normal else FontWeight.Medium,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                YText(c.before+" → "+c.after,if(t.id=="journal")11 else 10,number=true,color=t.muted)
            }
            val change=Modifier.testTag("comparison-change-${c.id}")
            if(t.id=="objects")YText(c.change,14,change.background(if(c.increase)Color(0xfffbe6d8)else Color(0xffeef0e2),RoundedCornerShape(9.dp)).padding(horizontal=9.dp,vertical=4.dp),number=true,color=changeColor(c))
            else YText(c.change,if(t.id=="journal")17 else 16,change,number=true,color=changeColor(c))
        }
    }
    when(t.id) {
        // Magazine: an indexed table between an ink rule and a hairline, like its ranking lists.
        "magazine"->Column(Modifier.fillMaxWidth().testTag("comparison-list-$id").drawBehind{
            drawLine(t.ink,Offset.Zero,Offset(size.width,0f),1.dp.toPx());drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())
        }.padding(vertical=4.dp)) {items.forEachIndexed{index,c->row(c,index){drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),.6.dp.toPx())}}}
        // Journal: one dashed slip of paper, arrows written in the margin.
        "journal"->Column(Modifier.fillMaxWidth().testTag("comparison-list-$id").background(Color(0xfffff8e7)).drawBehind{
            drawRect(t.line,style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))))
        }.padding(horizontal=14.dp,vertical=4.dp)) {items.forEachIndexed{index,c->row(c,index){drawLine(Color(0x408e9a99),Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}}}
        // Objects: a lifted tray with dashed seams and change pills.
        else->Column(Modifier.fillMaxWidth().testTag("comparison-list-$id").drawBehind{
            drawRoundRect(Color(0x60e8decd),Offset(0f,3.dp.toPx()),cornerRadius=CornerRadius(18.dp.toPx()))
        }.background(t.surface,RoundedCornerShape(18.dp)).padding(horizontal=16.dp,vertical=6.dp)) {items.forEachIndexed{index,c->row(c,index){
            drawLine(Color(0xffddcaaa),Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx())))
        }}}
    }
}
private typealias DrawScopeDivider=androidx.compose.ui.graphics.drawscope.DrawScope.()->Unit
