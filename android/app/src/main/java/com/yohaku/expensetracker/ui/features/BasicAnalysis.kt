package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.BookSnapshot
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import java.math.RoundingMode
import java.time.YearMonth

private data class CategorySpend(val category:CategoryEntity,val records:List<EventEntity>) {
    val amount=records.fold(0L){sum,e->Math.addExact(sum,e.baseMinor)}
}

@Composable internal fun BasicAnalysis(s:BookSnapshot,onRecord:(EventEntity)->Unit,onManage:()->Unit) {
    val t=LocalYohaku.current
    val month=YearMonth.now().toString()
    val records=s.visibleEvents.filter{it.kind==EntryKind.EXPENSE.name&&it.date.startsWith(month)}
    val total=records.fold(0L){sum,e->Math.addExact(sum,e.baseMinor)}
    val groups=s.categories.filter{it.kind==EntryKind.EXPENSE.name&&it.parentId==null}.map{c->
        val ids=s.categories.filter{it.parentId==c.id}.map{it.id}+c.id
        CategorySpend(c,records.filter{it.categoryId in ids})
    }.filter{it.records.isNotEmpty()}.sortedByDescending{it.amount}
    var selected by remember(s.book.id){mutableStateOf<String?>(null)}
    YTitle(word("看清，每一笔。","一筆ずつ、見渡す。"),"MONEY, IN CONTEXT",icon="chart",pageFlow=true)
    AnalysisTotal(Currency.valueOf(s.book.currency),total,records.size,month)
    if(t.id!="journal")AnalysisPeriod(month,records.size)
    ThemeSection(when(t.id){"magazine"->word("分类，透视日常。","カテゴリで、暮らしを見る。");"journal"->word("分类记在这里","カテゴリのメモ");else->word("钱装进了哪些口袋","お金のポケット")},if(t.id=="magazine")"01 / CATEGORIES" else if(t.id=="journal")"CATEGORY NOTES" else "",word("管理","管理"),onManage)
    if(groups.isEmpty())YEmpty(word("记账后，在这里看清分类消费。","記録するとカテゴリ別の支出を確認できます。"))
    else if(t.id=="objects")groups.chunked(2).forEach{row->
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(if(LocalConfiguration.current.screenWidthDp<=360)10.dp else 13.dp)){
            row.forEach{g->CategorySpendRow(g,groups.indexOf(g),total,groups.first().amount,Currency.valueOf(s.book.currency),{selected=g.category.id},Modifier.weight(1f))}
            if(row.size==1)Spacer(Modifier.weight(1f))
        }
    }else Column(Modifier.fillMaxWidth().then(if(t.id=="journal")Modifier.drawBehind{drawLine(Color(0x46bc8474),Offset.Zero,Offset(0f,size.height),1.dp.toPx())}.padding(start=9.dp) else Modifier)){
        if(t.id=="journal")Row(Modifier.fillMaxWidth().padding(vertical=9.dp),horizontalArrangement=Arrangement.SpaceBetween){YText(word("用途","用途"),10,color=t.muted);YText(word("花了多少","支出額"),10,color=t.muted)}
        groups.forEachIndexed{i,g->CategorySpendRow(g,i,total,groups.first().amount,Currency.valueOf(s.book.currency),{selected=g.category.id})}
    }
    selected?.let{id->groups.find{it.category.id==id}?.let{group->
        YSheet(group.category.name,{selected=null}){
            YText(Money(Currency.valueOf(s.book.currency),group.amount).display(),28,number=true)
            val children=s.categories.filter{it.parentId==id}
            if(children.isNotEmpty()){
                children.forEach{child->CategoryBreakdown(child.name,group.records.filter{it.categoryId==child.id}.sumOf{it.baseMinor},s.book.currency)}
                CategoryBreakdown(word("未细分 · 直接记入一级","内訳未分類・親への直接記録"),group.records.filter{it.categoryId==id}.sumOf{it.baseMinor},s.book.currency)
            }
            ThemeSection(word("相关流水","関連する記録"),"RECORDS")
            group.records.forEach{e->EventRow(s,e,{selected=null;onRecord(e)})}
        }
    }}
}

@Composable private fun CategoryBreakdown(name:String,amount:Long,currency:String){
    val t=LocalYohaku.current
    Row(Modifier.fillMaxWidth().drawBehind{drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}.padding(vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){
        YText(name,12,Modifier.weight(1f));YText(Money(Currency.valueOf(currency),amount).display(),16,number=true)
    }
}

@Composable private fun AnalysisTotal(currency:Currency,total:Long,count:Int,month:String) {
    val t=LocalYohaku.current
    val narrow=LocalConfiguration.current.screenWidthDp<=360
    when(t.id){
        "magazine"->Row(Modifier.fillMaxWidth().testTag("analysis-total").padding(top=15.dp,bottom=11.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){
            Column(Modifier.weight(1f)){
                YText("THE SPENDING ISSUE",7,color=t.muted,letterSpacing=1.8f,lineHeightMultiplier=19f/7)
                YText(word("消费的轮廓。","支出の輪郭。"),18,Modifier.padding(top=5.dp,bottom=10.dp),lineHeightMultiplier=26f/18)
                YText(word("本月消费","今月の支出"),10,color=t.muted,lineHeightMultiplier=2f)
                AnalysisLineText(Money(currency,total).display(),45,54f,t.ink,-2f,Modifier.padding(vertical=8.dp))
            }
            AnalysisArtwork(Modifier.size(if(narrow)55.dp else 80.dp,110.dp))
        }
        "journal"->Box(Modifier.fillMaxWidth().padding(top=5.dp,bottom=1.dp).rotate(-1f).testTag("analysis-total")){
            Box(Modifier.matchParentSize().offset(x=3.dp,y=4.dp).background(Color(0x12856748)))
            Column(Modifier.fillMaxWidth().background(Color(0xfffff8e5)).border(1.dp,Color(0xffe2d4b5)).padding(1.dp).padding(start=16.dp,end=16.dp,top=19.dp,bottom=14.dp)){
                AnalysisLineText(word("这段时间的小计","この期間の合計"),15,21f,t.warm,0f,Modifier.fillMaxWidth(),number=false)
                Spacer(Modifier.height(8.dp))
                AnalysisLineText(Money(currency,total).display(),41,53.3f,t.accent,-2f,Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                AnalysisPeriod(month,count)
            }
            Box(Modifier.offset(x=18.dp,y=(-13).dp).size(10.dp,28.dp).rotate(10f).border(1.5.dp,Color(0xff929f9a),RoundedCornerShape(8.dp)))
            Column(Modifier.align(Alignment.TopEnd).offset(x=(-11).dp,y=19.dp).rotate(8f).border(1.dp,t.warm).padding(horizontal=9.dp,vertical=6.dp),horizontalAlignment=Alignment.CenterHorizontally){
                AnalysisLineText(count.toString(),22,32f,t.warm,0f)
                YText(word("笔记录","件の記録"),8,Modifier.padding(top=2.dp),color=t.warm,lineHeightMultiplier=1.5f)
            }
        }
        else->{
            val shape=RoundedCornerShape(24.dp,24.dp,40.dp,24.dp)
            Box(Modifier.fillMaxWidth().padding(top=9.dp,bottom=2.dp).testTag("analysis-total")){
                Box(Modifier.matchParentSize().offset(y=5.dp).background(Color(0x17c4a87b),shape))
                Box(Modifier.fillMaxWidth().heightIn(min=148.dp).background(Color(0xffe9d4b1),shape).border(1.dp,Color(0xffdfc49b),shape)){
                    Box(Modifier.matchParentSize().padding(1.dp).border(5.dp,Color(0x3bf8edd3),shape))
                    AnalysisArtwork(Modifier.align(Alignment.TopEnd).padding(top=20.dp,end=7.dp).size(if(narrow)65.dp else 86.dp,86.dp).rotate(7f).alpha(.5f))
                    Column(Modifier.padding(1.dp).padding(start=if(narrow)15.dp else 18.dp,end=if(narrow)15.dp else 18.dp,top=20.dp,bottom=17.dp)){
                        YText(word("这个月的消费盘","今月の支出トレイ"),12,Modifier.padding(bottom=13.dp),color=t.muted,lineHeightMultiplier=17f/12)
                        AnalysisLineText(Money(currency,total).display(),39,54.6f,t.ink,-2f)
                        YText("$count "+word("次日常的小花费","回の日々の支出"),10,Modifier.padding(top=7.dp),color=Color(0xff967854),lineHeightMultiplier=1.5f)
                    }
                }
            }
        }
    }
}

@Composable private fun CategorySpendRow(g:CategorySpend,index:Int,total:Long,largest:Long,currency:Currency,onClick:()->Unit,modifier:Modifier=Modifier){
    val t=LocalYohaku.current
    val narrow=LocalConfiguration.current.screenWidthDp<=360
    val share=g.amount.toBigDecimal().divide(total.toBigDecimal(),4,RoundingMode.HALF_UP)
    val percent=share.multiply(100.toBigDecimal()).setScale(0,RoundingMode.HALF_UP).toPlainString()
    val note="${g.records.size} "+word("笔","件")+" · $percent%"
    val click=modifier.testTag("analysis-category-${g.category.id}").clickable(role=Role.Button,onClick=onClick)
    if(t.id=="objects"){
        val colors=listOf(Color(0xfff3e7cb),Color(0xffe2e7d5),Color(0xffe0e9ec),Color(0xfff0ddcf),Color(0xffe9dfce))
        val borders=listOf(Color(0xffead9bc),Color(0xffd1d9c0),Color(0xffccdadd),Color(0xffe2c8b8),Color(0xffd8cbb7))
        val shape=RoundedCornerShape(17.dp,17.dp,23.dp,17.dp)
        Box(click.heightIn(min=166.dp)){
            Box(Modifier.matchParentSize().offset(y=4.dp).background(Color(0x20b59367),shape))
            Column(Modifier.fillMaxWidth().background(colors[index%5],shape).border(1.dp,borders[index%5],shape).padding(1.dp).padding(start=if(narrow)12.dp else 15.dp,end=if(narrow)12.dp else 15.dp,top=if(narrow)15.dp else 17.dp,bottom=15.dp)){
                Box(Modifier.size(31.dp,30.dp).background(Color(0x80fff8e6),RoundedCornerShape(9.dp)),contentAlignment=Alignment.Center){YIcon("wallet",Modifier.size(20.dp),tint=Color(0xff8a6948))}
                YText(g.category.name,12,Modifier.padding(top=10.dp),weight=androidx.compose.ui.text.font.FontWeight.Medium,lineHeightMultiplier=17f/12)
                YText(Money(currency,g.amount).display(),if(narrow)22 else 24,Modifier.padding(top=10.dp),number=true,letterSpacing=-1f,lineHeightMultiplier=35f/(if(narrow)22 else 24))
                SpendMeter(share.toFloat(),Modifier.padding(top=11.dp,bottom=7.dp),index)
                YText(note,8,color=Color(0xff9b896f),lineHeightMultiplier=1.7f)
            }
        }
    }else Column(click.fillMaxWidth().drawBehind{drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}.padding(top=if(t.id=="journal")18.dp else 21.dp,bottom=if(t.id=="journal")13.dp else 15.dp).padding(horizontal=if(t.id=="journal")3.dp else 0.dp)){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(if(t.id=="journal")if(narrow)6.dp else 8.dp else if(narrow)7.dp else 10.dp)){
            if(t.id=="journal")Box(Modifier.width(if(narrow)21.dp else 25.dp)){
                Box(Modifier.size(18.dp,19.dp).rotate(-5f).border(1.dp,t.accent,RoundedCornerShape(3.dp,2.dp,5.dp,2.dp)),contentAlignment=Alignment.Center){YText("${index+1}",11,number=true,lineHeightMultiplier=16f/11)}
            }
            else YText("${index+1}".padStart(2,'0'),14,Modifier.width(if(narrow)23.dp else 30.dp),number=true,color=t.muted)
            Column(Modifier.weight(1f)){YText(g.category.name,15,lineHeightMultiplier=21f/15);YText(note,if(t.id=="journal")10 else 8,Modifier.padding(top=6.dp),color=t.muted,lineHeightMultiplier=1.7f)}
            AnalysisLineText(Money(currency,g.amount).display(),if(t.id=="journal")22 else if(narrow)20 else 23,if(t.id=="journal")32f else if(narrow)23f else 26f,if(t.id=="journal")t.accent else t.ink,if(t.id=="journal")-1f else -.6f)
        }
        SpendMeter((g.amount.toDouble()/largest).toFloat(),Modifier.padding(top=if(t.id=="journal")13.dp else 10.dp,start=if(t.id=="journal")if(narrow)27.dp else 33.dp else if(narrow)30.dp else 40.dp),index)
    }
}

@Composable private fun SpendMeter(fraction:Float,modifier:Modifier,index:Int){
    val t=LocalYohaku.current
    when(t.id){
        "journal"->Canvas(modifier.fillMaxWidth().height(5.dp)){
            drawLine(t.line,Offset(0f,size.height-.5.dp.toPx()),Offset(size.width,size.height-.5.dp.toPx()),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())))
            clipRect(right=size.width*fraction,bottom=4.dp.toPx()){
                var x=(-2).dp.toPx()
                while(x<size.width*fraction){drawLine(t.accent,Offset(x,4.dp.toPx()),Offset(x+1.5.dp.toPx(),0f),2.dp.toPx());x+=4.dp.toPx()}
            }
        }
        "objects"->{
            val color=when(index%5){1->Color(0xff8b9c74);2->Color(0xff7d9ba5);else->Color(0xffa8774e)}
            Box(modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(7.dp)).background(Color(0x80ffffff))){Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(color,RoundedCornerShape(7.dp)))}
        }
        else->Box(modifier.fillMaxWidth().height(2.dp).background(t.line)){Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(t.accent))}
    }
}

@Composable private fun AnalysisLineText(text:String,fontSize:Int,line:Float,color:Color,spacing:Float,modifier:Modifier=Modifier,number:Boolean=true){
    val height=with(LocalDensity.current){line.sp.toDp()}
    YText(text,fontSize,modifier.height(height).wrapContentHeight(Alignment.CenterVertically,unbounded=true),number=number,color=color,letterSpacing=spacing,lineHeightMultiplier=line/fontSize)
}

@Composable private fun AnalysisPeriod(month:String,count:Int){
    val t=LocalYohaku.current
    val frame=if(t.id=="journal")Modifier.drawBehind{drawLine(t.line,Offset(0f,.5.dp.toPx()),Offset(size.width,.5.dp.toPx()),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())))}.padding(top=12.dp) else if(t.id=="magazine")Modifier.drawBehind{drawLine(t.line,Offset.Zero,Offset(size.width,0f),1.dp.toPx());drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}.padding(vertical=12.dp) else Modifier
    Row(Modifier.fillMaxWidth().then(frame),horizontalArrangement=Arrangement.SpaceBetween){
        YText(month.replace("-"," / "),if(t.id=="journal")10 else if(t.id=="magazine")8 else 9,color=t.muted,lineHeightMultiplier=1.8f)
        YText("$count "+word("笔记录","件の記録"),if(t.id=="journal")10 else if(t.id=="magazine")8 else 9,color=t.muted,lineHeightMultiplier=1.8f)
    }
}
