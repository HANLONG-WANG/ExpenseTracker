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
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.*

internal data class AnalysisListItem(val id:String,val name:String,val money:String,val count:Int,val percent:String="—",val icon:String="bag")

@Composable internal fun AnalysisCategoryList(items:List<AnalysisListItem>,onOpen:((String)->Unit)?) {
    val t=LocalYohaku.current
    val narrow=LocalConfiguration.current.screenWidthDp<=360
    when(t.id) {
        "magazine"->Column(Modifier.fillMaxWidth().testTag("analysis-category-list")) {items.forEachIndexed{index,item->
            Row(Modifier.fillMaxWidth().testTag("analysis-category-${item.id}").then(if(onOpen!=null)Modifier.clickable(role=Role.Button){onOpen(item.id)}else Modifier).drawBehind{drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}.padding(vertical=17.dp),horizontalArrangement=Arrangement.spacedBy(13.dp),verticalAlignment=Alignment.CenterVertically) {
                YText((index+1).toString().padStart(2,'0'),17,number=true,color=t.muted)
                Column(Modifier.weight(1f)){YText(item.name,14,weight=FontWeight.Medium);YText("${item.count} "+word("笔","件")+" · ${item.percent}",9,Modifier.padding(top=8.dp),color=t.muted)}
                YText(item.money,21,number=true)
            }
        }}
        "journal"->Column(Modifier.fillMaxWidth().testTag("analysis-category-list").rotate(-.3f).background(Color(0xfffff8e7)).border(1.dp,t.line).padding(horizontal=15.dp,vertical=18.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom=10.dp),horizontalArrangement=Arrangement.SpaceBetween){YText(word("用途","使い道"),13,color=t.muted);YText(word("金额","金額"),13,color=t.muted)}
            items.forEach{item->Column(Modifier.fillMaxWidth().testTag("analysis-category-${item.id}").then(if(onOpen!=null)Modifier.clickable(role=Role.Button){onOpen(item.id)}else Modifier).drawBehind{drawLine(Color(0x408e9a99),Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}.padding(vertical=17.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){YText("□ "+item.name,16,Modifier.weight(1f));YText(item.money,21,number=true)}
                YText("${item.count} "+word("笔小花费","件の支出"),11,color=t.muted)
            }}
        }
        else->Column(Modifier.fillMaxWidth().testTag("analysis-category-list").padding(top=10.dp,bottom=10.dp),verticalArrangement=Arrangement.spacedBy(17.dp)) {items.chunked(2).forEach{row->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(13.dp)){row.forEach{item->
                val index=items.indexOf(item);val top=if(narrow)23 else 24;val shape=RoundedCornerShape(9.dp,9.dp,24.dp,21.dp)
                Box(Modifier.weight(1f).fillMaxHeight().testTag("analysis-category-${item.id}").then(if(onOpen!=null)Modifier.clickable(role=Role.Button){onOpen(item.id)}else Modifier).drawBehind{drawRoundRect(Color(0x2dc6aa80),Offset(0f,4.dp.toPx()),cornerRadius=CornerRadius(17.dp.toPx()))}.background(listOf(Color(0xffecd8b0),Color(0xffe4e8d4),Color(0xffe8d9c8))[index%3],shape).padding(start=if(narrow)12.dp else 16.dp,end=if(narrow)12.dp else 16.dp,top=top.dp,bottom=if(narrow)16.dp else 18.dp)) {
                    Box(Modifier.align(Alignment.TopCenter).offset(y=(-(top+5)).dp).fillMaxWidth(.52f).height(14.dp).background(Color(0xfffff8e6),RoundedCornerShape(5.dp)))
                    Column(verticalArrangement=Arrangement.spacedBy(12.dp)){YIcon(item.icon,Modifier.size(23.dp),Color(0xffa88961));YText(item.name,13,weight=FontWeight.Medium);YText(item.money,if(narrow)20 else 22,number=true);YText("${item.percent} · ${item.count} "+word("笔","件"),9,color=t.muted)}
                }
            };if(row.size==1)Spacer(Modifier.weight(1f))}
        }}
    }
}

@Composable internal fun AnalysisMerchantList(items:List<AnalysisListItem>,total:String,onOpen:((String)->Unit)?) {
    val t=LocalYohaku.current
    val narrow=LocalConfiguration.current.screenWidthDp<=360
    when(t.id) {
        "magazine"->Column(Modifier.testTag("analysis-merchant-list"),verticalArrangement=Arrangement.spacedBy(if(narrow)12.dp else 18.dp)){items.chunked(2).forEach{row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(if(narrow)12.dp else 18.dp)){row.forEach{item->
            Column(Modifier.weight(1f).testTag("analysis-merchant-${item.id}").then(if(onOpen!=null)Modifier.clickable(role=Role.Button){onOpen(item.id)}else Modifier).drawBehind{drawLine(t.line,Offset.Zero,Offset(size.width,0f),1.dp.toPx())}.padding(vertical=18.dp)) {
                YText("SHOP / "+(items.indexOf(item)+1).toString().padStart(2,'0'),7,color=t.muted,letterSpacing=1f)
                YText(item.name,13,Modifier.padding(vertical=10.dp),weight=FontWeight.Medium,lineHeightMultiplier=1.8f)
                YText(item.money,22,number=true);YText("${item.count} "+word("次消费","回の支出"),9,Modifier.padding(top=8.dp),color=t.muted)
            }
        };if(row.size==1)Spacer(Modifier.weight(1f))}}}
        "journal"->Column(Modifier.testTag("analysis-merchant-list")){items.forEachIndexed{index,item->
            Row(Modifier.fillMaxWidth().testTag("analysis-merchant-${item.id}").then(if(onOpen!=null)Modifier.clickable(role=Role.Button){onOpen(item.id)}else Modifier).drawBehind{drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx())))}.padding(vertical=18.dp),horizontalArrangement=Arrangement.spacedBy(if(narrow)7.dp else 10.dp)) {
                YText((index+1).toString().padStart(2,'0'),13,Modifier.widthIn(min=if(narrow)19.dp else 22.dp).testTag("analysis-merchant-index-$index").alignByBaseline(),number=true,color=t.muted)
                YText(item.name,if(narrow)13 else 15,Modifier.weight(1f).alignByBaseline())
                YText(item.money,if(narrow)16 else 18,Modifier.alignByBaseline(),number=true)
            }
        }}
        else->Column(Modifier.fillMaxWidth().testTag("analysis-merchant-list").drawBehind{drawRoundRect(Color(0x2dc6aa80),Offset(0f,4.dp.toPx()),cornerRadius=CornerRadius(18.dp.toPx()))}.background(Color(0xfffff8e7),RoundedCornerShape(18.dp,18.dp,7.dp,7.dp)).border(1.dp,Color(0xffe0c9a2),RoundedCornerShape(18.dp,18.dp,7.dp,7.dp)).drawBehind{drawLine(Color(0xffcdb18a),Offset(7.dp.toPx(),size.height),Offset(size.width-7.dp.toPx(),size.height),2.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(),3.dp.toPx())))}.padding(horizontal=18.dp,vertical=22.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom=15.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){YText("MY LITTLE SHOPS",9,color=t.muted,letterSpacing=1f);YIcon("coffee",Modifier.size(21.dp),t.muted)}
            items.forEach{item->Row(Modifier.fillMaxWidth().testTag("analysis-merchant-${item.id}").then(if(onOpen!=null)Modifier.clickable(role=Role.Button){onOpen(item.id)}else Modifier).drawBehind{drawLine(Color(0xffddcaaa),Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx())))}.padding(vertical=15.dp),horizontalArrangement=Arrangement.spacedBy(13.dp),verticalAlignment=Alignment.CenterVertically){YText(item.name,12,Modifier.weight(1f),weight=FontWeight.Medium);YText(item.money,16,number=true)}}
            Row(Modifier.fillMaxWidth().padding(top=15.dp).drawBehind{drawLine(Color(0xffc4a77b),Offset.Zero,Offset(size.width,0f),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx())))}.padding(top=15.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){YText(word("合计","合計"),11);YText(total,22,Modifier.testTag("analysis-merchants-total"),number=true)}
        }
    }
}
