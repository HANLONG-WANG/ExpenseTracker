package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.*

internal data class FinancialComparisonLine(val date:String,val before:String,val after:String,val delta:String,val note:String,val changed:Boolean)

/** One aligned ledger, with a single optional note for the differences within each date. */
@Composable internal fun FinancialComparisonTable(rows:List<FinancialComparisonLine>) {
    val t=LocalYohaku.current
    val journal=t.id=="journal";val objects=t.id=="objects"
    val shape=RoundedCornerShape(if(objects)12.dp else 0.dp)
    Column(Modifier.fillMaxWidth().clip(shape).then(if(objects)Modifier.background(t.surface)else Modifier)) {
        Row(Modifier.fillMaxWidth().then(when {
            objects->Modifier.background(t.soft)
            journal->Modifier.background(t.soft).drawBehind{drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}
            else->Modifier.drawBehind{drawLine(t.ink,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}
        }).padding(horizontal=6.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            YText(word("日期","日付"),11,Modifier.weight(1.12f),color=t.muted)
            listOf(word("原","変更前"),word("新","変更後"),word("差额","差額")).forEach{YText(it,11,Modifier.weight(1f),color=t.muted,textAlign=TextAlign.End)}
        }
        rows.forEachIndexed{index,row->
            Column(Modifier.fillMaxWidth().testTag("finance-comparison-row-${row.date}")
                .then(if(objects&&index%2==0)Modifier.background(t.soft.copy(alpha=0.35f))else Modifier)
                .drawBehind {
                    if(journal)drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())))
                    else if(!objects&&row.changed)drawLine(t.accent,Offset(0f,0f),Offset(0f,size.height),2.dp.toPx())
                }.padding(horizontal=6.dp,vertical=7.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
                    YText(row.date,11,Modifier.weight(1.12f).then(if(objects)Modifier.background(t.soft,RoundedCornerShape(4.dp)).padding(vertical=3.dp)else Modifier),number=true)
                    listOf("before" to row.before,"after" to row.after,"delta" to row.delta).forEach{(key,value)->
                        YText(value,13,Modifier.weight(1f).testTag("finance-comparison-$key-${row.date}"),color=if(key=="delta"&&row.changed)t.accent else t.ink,number=true,textAlign=TextAlign.End)
                    }
                }
                if(row.note.isNotEmpty())YText(row.note,10,color=t.muted)
            }
        }
    }
}

/** One surface per list; the journal uses ledger rules, objects use date tabs. */
@Composable internal fun FinancialPlanRow(title:String,kind:String,amount:String,date:String,facts:String,status:String,tag:String,onOpen:(()->Unit)?=null) {
    val t=LocalYohaku.current
    val surface=when(t.id){
        "journal"->Modifier.background(t.surface).drawBehind{drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx());drawLine(t.line,Offset(12.dp.toPx(),0f),Offset(12.dp.toPx(),size.height),1.dp.toPx())}.padding(start=24.dp,end=10.dp)
        "objects"->Modifier.background(t.surface,RoundedCornerShape(12.dp)).padding(12.dp)
        else->Modifier.padding(vertical=4.dp)
    }
    Column(Modifier.fillMaxWidth().testTag(tag).then(if(onOpen!=null)Modifier.clickable(role=Role.Button,onClick=onOpen)else Modifier).then(surface).padding(vertical=10.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
        if(t.id=="objects") {
            Row(verticalAlignment=Alignment.CenterVertically){YText(title,16,Modifier.weight(1f));YText(kind,10,Modifier.background(t.soft,RoundedCornerShape(4.dp)).padding(5.dp),color=t.accent)}
            Row{YText(date,12,Modifier.weight(1f),number=true);YText(amount,18,number=true)}
        }else {
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){Column(Modifier.weight(1f)){YText(title,16);YText(kind,11,color=t.muted)};YText(amount,18,number=true)}
            YText(date,12,number=true)
        }
        YText(facts,11,color=t.muted)
        YText((if(t.id=="journal")"· "else "")+status+if(onOpen!=null)"  →"else "",11,color=t.accent)
    }
}

@Composable internal fun FinancialPeriodRow(number:Int,date:String,amount:String,status:String,tag:String,onOpen:()->Unit) {
    val t=LocalYohaku.current
    Column(Modifier.fillMaxWidth().testTag(tag).clickable(role=Role.Button,onClick=onOpen).drawBehind {
        if(t.id!="objects")drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())
    }.padding(vertical=10.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            if(t.id=="journal")YText(number.toString(),12,Modifier.width(22.dp),color=t.accent,number=true)
            YText(date,12,Modifier.weight(1f).then(if(t.id=="objects")Modifier.background(t.soft,RoundedCornerShape(5.dp)).padding(6.dp)else Modifier),number=true)
            YText(amount,16,number=true)
            YText("›",16,color=t.accent)
        }
        YText((if(t.id!="journal")word("第${number}期","第${number}回")+" · "else "")+status,11,color=t.muted)
    }
}

/** Compact repayment facts: editorial note, ledger margin, or a labelled storage slot. */
@Composable internal fun FinancialRepaymentSummary(title:String,tag:String,content:@Composable ColumnScope.()->Unit) {
    val t=LocalYohaku.current
    val surface=when(t.id) {
        "journal"->Modifier.drawBehind{drawLine(t.warm,Offset(6.dp.toPx(),0f),Offset(6.dp.toPx(),size.height),1.dp.toPx())}.padding(start=18.dp)
        "objects"->Modifier.background(t.soft,RoundedCornerShape(12.dp)).padding(12.dp)
        else->Modifier.padding(vertical=6.dp)
    }
    Column(Modifier.fillMaxWidth().testTag(tag).then(surface),verticalArrangement=Arrangement.spacedBy(7.dp)) {
        if(t.id=="objects")YText(title,11,Modifier.background(t.surface,RoundedCornerShape(4.dp)).padding(horizontal=8.dp,vertical=4.dp),color=t.accent)
        else YText((if(t.id=="journal")"· "else "")+title,14,color=t.accent)
        content()
    }
}
