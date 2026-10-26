package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.domain.model.EntryKind
import com.yohaku.expensetracker.ui.components.YText
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun LedgerEntryActions(onStart:(EntryKind)->Unit){
    val t=LocalYohaku.current
    FlowRow(Modifier.fillMaxWidth().testTag("ledger-entry-actions"),horizontalArrangement=Arrangement.spacedBy(7.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
        for(kind in listOf(EntryKind.EXPENSE,EntryKind.INCOME,EntryKind.TRANSFER)){
            val size=when(t.id){"journal"->11;"objects"->10;else->9}
            val padding=when(t.id){"journal"->6;"objects"->9;else->7}
            val horizontal=when(t.id){"journal"->8.dp;"objects"->12.dp;else->9.dp}
            val frame=if(t.id=="objects")Modifier.background(Color(0xfff2e8d8),RoundedCornerShape(12.dp)) else Modifier.drawBehind{drawLine(t.line,Offset(0f,this.size.height-.5.dp.toPx()),Offset(this.size.width,this.size.height-.5.dp.toPx()),1.dp.toPx(),pathEffect=if(t.id=="journal")PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())) else null)}
            YText(when(kind){EntryKind.EXPENSE->word("记支出","支出");EntryKind.INCOME->word("记收入","収入");else->word("转账 / 换汇","振替・両替")},size,
                Modifier.testTag("ledger-entry-${kind.name}").then(frame).clickable(role=Role.Button){onStart(kind)}.padding(start=horizontal,end=horizontal,top=padding.dp,bottom=(padding+if(t.id=="objects")0 else 1).dp),
                color=t.muted,lineHeightMultiplier=when(t.id){"journal"->16f/size;"objects"->15f/size;else->13f/size},cssLineBox=true)
        }
    }
}

@Composable internal fun LedgerFilterDisclosure(expanded:Boolean,onToggle:()->Unit,label:String=word("筛选账户、分类与日期","口座・カテゴリ・日付を絞る"),content:@Composable ()->Unit){
    val t=LocalYohaku.current
    val state=if(expanded)word("已展开","展開中") else word("已收起","折りたたみ")
    Column(Modifier.fillMaxWidth().testTag("ledger-filter-panel").drawBehind{drawLine(t.line,Offset(0f,size.height-.5.dp.toPx()),Offset(size.width,size.height-.5.dp.toPx()),1.dp.toPx(),pathEffect=if(t.id=="journal")PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())) else null)}.padding(top=15.dp,bottom=16.dp)){
        Row(Modifier.fillMaxWidth().testTag("ledger-filters").semantics{stateDescription=state}.clickable(role=Role.Button,onClick=onToggle),verticalAlignment=Alignment.CenterVertically){
            val size=when(t.id){"journal"->14;"objects"->12;else->10}
            YText(label,size,Modifier.weight(1f),color=t.accent,lineHeightMultiplier=when(t.id){"journal"->20f/size;"objects"->17f/size;else->14f/size},cssLineBox=true)
            YText(if(expanded)"−" else "+",17,color=t.accent,lineHeightMultiplier=25f/17,cssLineBox=true)
        }
        if(expanded)Column(Modifier.fillMaxWidth().padding(top=16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){content()}
    }
}

@Composable internal fun LedgerClearFilters(onClear:()->Unit){
    val t=LocalYohaku.current;val size=if(t.id=="journal")15 else 12
    YText(word("清除筛选","絞り込みを解除"),size,Modifier.fillMaxWidth().testTag("ledger-clear-filters").clickable(role=Role.Button,onClick=onClear).drawBehind{drawLine(t.line,Offset(0f,this.size.height-.5.dp.toPx()),Offset(this.size.width,this.size.height-.5.dp.toPx()),1.dp.toPx(),pathEffect=if(t.id=="magazine")null else PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())))}.padding(top=if(t.id=="objects")15.dp else 14.dp,bottom=if(t.id=="objects")16.dp else 15.dp),color=t.accent,lineHeightMultiplier=1.8f,cssLineBox=true)
}
