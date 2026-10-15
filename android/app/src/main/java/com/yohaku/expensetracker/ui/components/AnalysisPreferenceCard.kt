package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.*

/** Final analysis-custom-item; order buttons have native 44dp touch targets. */
@Composable internal fun AnalysisPreferenceCard(id:String,title:String,note:String,position:Int,chosen:Boolean,pinned:Boolean,last:Boolean,index:Int,onChoose:(Boolean)->Unit,onUp:()->Unit,onDown:()->Unit,onPin:(Boolean)->Unit,focused:Boolean=position==0) {
    val t=LocalYohaku.current;val journal=t.id=="journal";val objects=t.id=="objects"
    val narrow=LocalConfiguration.current.screenWidthDp<=360
    val shape=RoundedCornerShape(if(objects)15.dp else 0.dp)
    val frame=when(t.id) {
        "magazine"->Modifier.drawBehind{drawLine(t.ink,Offset.Zero,Offset(size.width,0f),1.dp.toPx());drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}
        "journal"->Modifier.rotate(if(index%2==0)-.4f else .4f).background(if(chosen)Color(0xffeef0e0)else Color(0xfffff8e7)).drawBehind{drawRect(t.line,style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))))}
        else->Modifier.drawBehind{val dy=3.dp.toPx();withTransform({translate(0f,dy)}){drawOutline(shape.createOutline(size,layoutDirection,this),Color(0x20ccb491))}}.background(if(chosen)Color(0xffefe2c9)else Color(0xfffff8e8),shape).border(1.dp,Color(0xffe4ceaa),shape)
    }
    Box(Modifier.fillMaxWidth().testTag("analysis-custom-card-$id").then(frame)) {
        Column(Modifier.padding(horizontal=if(narrow)9.dp else if(journal)12.dp else if(objects)11.dp else 0.dp,vertical=if(narrow)20.dp else if(journal)24.dp else 23.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                Row(Modifier.weight(1f).testTag("analysis-module-$id").toggleable(chosen,role=Role.Checkbox,onValueChange=onChoose).heightIn(min=44.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(if(narrow)7.dp else 9.dp)) {
                    val checkShape=if(journal)RoundedCornerShape(2.dp,1.dp,3.dp,1.dp)else RoundedCornerShape(if(objects)5.dp else 0.dp)
                    Box(Modifier.size(if(journal||objects)17.dp else 14.dp).then(if(journal)Modifier.rotate(-4f)else Modifier).background(if(chosen&&!journal)if(objects)Color(0xffba825b)else t.ink else if(journal)Color(0xfffff8e5)else if(objects)Color(0xfffff7e4)else t.surface,checkShape).border(1.dp,if(objects)Color(0xffc8ae86)else if(journal)t.accent else t.ink,checkShape),contentAlignment=Alignment.Center) {
                        if(chosen)if(journal)YText("✓",26,Modifier.offset(y=(-4).dp).wrapContentHeight(unbounded=true),color=t.warm,lineHeightMultiplier=1.2f)else Box(Modifier.size(if(objects)9.dp else 6.dp).background(if(objects)Color(0xfffff7e4)else t.surface,checkShape))
                    }
                    YText(if(chosen)(position+1).toString().padStart(2,'0')else "—",if(narrow)17 else if(objects)13 else if(journal)24 else 25,Modifier.widthIn(min=if(narrow)24.dp else if(objects)28.dp else if(journal)25.dp else 31.dp).then(if(objects)Modifier.background(Color(0xffe4d1ae),RoundedCornerShape(6.dp)).padding(4.dp)else Modifier),number=true,color=if(journal)t.warm else if(objects)Color(0xffa58054)else t.muted)
                    Column(Modifier.weight(1f)) {
                        YText(title,if(narrow)12 else if(journal)17 else if(objects)13 else 15,weight=if(journal)FontWeight.Normal else FontWeight.Medium)
                        YText(note,if(!narrow&&journal)11 else 9,Modifier.padding(top=6.dp),color=t.muted,lineHeightMultiplier=1.9f)
                    }
                }
                if(chosen)Column(verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    AnalysisOrderButton("↑","analysis-up-$id",position>0,onUp)
                    AnalysisOrderButton("↓","analysis-down-$id",!last,onDown)
                }
            }
            if(chosen)YToggle(word("置顶关注","注目項目として先頭へ"),pinned,onPin,Modifier.testTag("analysis-pin-$id"))
        }
        if(chosen&&focused)YText(word("重点","注目"),if(journal)11 else 8,Modifier.align(Alignment.TopEnd).offset(x=(-16).dp,y=(-8).dp).then(if(journal)Modifier.rotate(-5f)else if(objects)Modifier.rotate(3f)else Modifier).background(if(journal)Color(0xfffff4dc)else if(objects)Color(0xffb67d57)else t.ink,RoundedCornerShape(if(objects)5.dp else 0.dp)).then(if(journal)Modifier.border(1.dp,t.warm)else Modifier).padding(horizontal=8.dp,vertical=4.dp),color=if(journal)t.warm else if(objects)Color(0xfffff9e9)else t.surface)
    }
}
@Composable private fun AnalysisOrderButton(label:String,tag:String,enabled:Boolean,onClick:()->Unit) {
    val t=LocalYohaku.current;val journal=t.id=="journal";val objects=t.id=="objects"
    Box(Modifier.size(44.dp).testTag(tag).alpha(if(enabled)1f else .2f).clickable(enabled=enabled,role=Role.Button,onClick=onClick),contentAlignment=Alignment.Center) {
        val shape=RoundedCornerShape(if(objects)7.dp else 0.dp)
        Box(Modifier.size(26.dp,23.dp).then(if(journal)Modifier.rotate(-2f)else Modifier).background(if(journal)Color(0xffe5e8db)else if(objects)Color(0xfffffaec)else Color.Transparent,shape).then(if(journal)Modifier.drawBehind{drawRect(t.line,style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))))}else if(!objects)Modifier.border(1.dp,t.line)else Modifier),contentAlignment=Alignment.Center){YText(label,if(journal)17 else 12,color=if(objects)Color(0xffac8259)else t.ink)}
    }
}
@Composable internal fun AnalysisOrderSummary(names:List<String>) {
    val t=LocalYohaku.current;val journal=t.id=="journal";val objects=t.id=="objects"
    Column(Modifier.fillMaxWidth().testTag("analysis-order-summary").background(if(journal)Color(0xfffff4d6)else if(objects)Color(0xffe7ebd8)else t.soft,RoundedCornerShape(if(objects)15.dp else 0.dp)).then(if(journal)Modifier.drawBehind{drawRect(t.line,style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))))}else if(!objects)Modifier.drawBehind{drawLine(t.ink,Offset.Zero,Offset(0f,size.height),2.dp.toPx())}else Modifier).padding(18.dp)) {
        YText(word("预览阅读顺序","表示順のプレビュー"),if(journal)12 else 10,color=t.muted)
        FlowRow(Modifier.padding(top=11.dp),horizontalArrangement=Arrangement.spacedBy(9.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
            if(names.isEmpty())YText(word("尚未选择模块","モジュール未選択"),11)
            names.forEachIndexed{index,name->
                if(index>0)YText("→",11,color=t.muted)
                YText(name,if(journal)14 else if(objects)10 else 11,(if(index==0)Modifier.testTag("analysis-order-first")else Modifier).then(if(objects)Modifier.background(Color(0xfff7faec),RoundedCornerShape(6.dp)).padding(horizontal=8.dp,vertical=5.dp)else Modifier),lineHeightMultiplier=1.9f)
            }
        }
    }
}
