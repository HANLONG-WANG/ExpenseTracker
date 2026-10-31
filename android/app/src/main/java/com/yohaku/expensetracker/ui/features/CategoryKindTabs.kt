package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.domain.model.EntryKind
import com.yohaku.expensetracker.ui.components.YText
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun CategoryKindTabs(selected:EntryKind,onChoose:(EntryKind)->Unit){
    val t=LocalYohaku.current
    val journal=t.id=="journal";val objects=t.id=="objects"
    Column(Modifier.fillMaxWidth().padding(vertical=7.dp)){
        val frame=when(t.id){
            "objects"->Modifier.background(Color(0xfff0e6d5),RoundedCornerShape(16.dp)).padding(5.dp)
            else->Modifier.drawBehind{drawLine(if(journal)t.line else t.ink,Offset(0f,size.height-.5.dp.toPx()),Offset(size.width,size.height-.5.dp.toPx()),1.dp.toPx(),pathEffect=if(journal)PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())) else null)}.padding(bottom=if(journal)11.dp else 1.dp)
        }
        Row(Modifier.fillMaxWidth().testTag("category-kind-tabs").then(frame),horizontalArrangement=Arrangement.spacedBy(if(journal)6.dp else if(objects)3.dp else 0.dp)){
            listOf(EntryKind.EXPENSE,EntryKind.INCOME,EntryKind.TRANSFER).forEach{kind->
                val active=kind==selected
                val bg=if(journal)if(active)Color(0xffdce4dc) else Color(0xffe7e4d4) else if(objects)if(active)Color(0xfffffaf0) else Color.Transparent else if(active)t.ink else Color.Transparent
                val ink=if(active)if(journal||objects)t.accent else t.surface else t.ink
                val shape=RoundedCornerShape(if(objects)12.dp else 0.dp)
                Box(Modifier.weight(1f).testTag("category-kind-${kind.name}").then(if(journal)Modifier.rotate(-1f) else Modifier).selectable(active,role=Role.Tab,onClick={onChoose(kind)}),contentAlignment=Alignment.Center){
                    if(objects&&active)Box(Modifier.matchParentSize().offset(y=2.dp).background(Color(0x2ec5ab81),shape))
                    Box(Modifier.fillMaxWidth().background(bg,shape).padding(horizontal=if(journal)5.dp else if(objects)4.dp else 12.dp,vertical=if(journal)8.dp else 9.dp),contentAlignment=Alignment.Center){
                        val size=if(journal)14 else if(objects)11 else 12
                        val line=if(journal)20f else if(objects)16f else 17f
                        YText(if(kind==EntryKind.EXPENSE)word("支出","支出") else if(kind==EntryKind.INCOME)word("收入","収入")else word("转账","振替"),size,color=ink,lineHeightMultiplier=line/size,cssLineBox=true)
                    }
                }
            }
        }
    }
}
