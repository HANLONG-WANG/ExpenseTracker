package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun BudgetEquation(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) {
    val t=LocalYohaku.current
    val frame=when(t.id) {
        "magazine"->Modifier.drawBehind{drawLine(t.ink,Offset.Zero,Offset(size.width,0f),1.dp.toPx());drawLine(t.ink,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}.padding(vertical=10.dp)
        "journal"->Modifier.rotate(-.3f).background(Color(0xfffff9e9)).drawBehind{var y=size.height-30.dp.toPx();while(y>0){drawLine(Color(0x13718695),Offset(0f,y),Offset(size.width,y),1.dp.toPx());y-=30.dp.toPx()};drawRect(t.line,style=androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))))}.padding(17.dp)
        else->Modifier.drawBehind{drawRoundRect(Color(0x24b9996f),Offset(0f,3.dp.toPx()),cornerRadius=androidx.compose.ui.geometry.CornerRadius(17.dp.toPx()))}.background(Color(0xfffff8e8),RoundedCornerShape(17.dp)).border(1.dp,Color(0xffe3cda8),RoundedCornerShape(17.dp)).padding(horizontal=15.dp,vertical=19.dp)
    }
    Column(modifier.fillMaxWidth().testTag("budget-equation").then(frame),content=content)
}

@Composable internal fun IndependentBudgetNote(amount:String) {
    val t=LocalYohaku.current
    val frame=when(t.id) {
        "magazine"->Modifier.background(t.soft).drawBehind{drawLine(t.accent,Offset.Zero,Offset(0f,size.height),2.dp.toPx())}
        "journal"->Modifier.rotate(.4f).background(Color(0xffe8ebdd)).drawBehind{drawRect(t.line,style=androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))))}
        else->Modifier.background(Color(0xffe6e9d8),RoundedCornerShape(16.dp))
    }
    Box(Modifier.fillMaxWidth().padding(top=8.dp,bottom=8.dp)) {
    Column(Modifier.fillMaxWidth().testTag("independent-budget-note").then(frame).padding(start=if(t.id=="magazine")20.dp else if(t.id=="journal")19.dp else 18.dp,end=if(t.id=="journal")19.dp else 18.dp,top=if(t.id=="journal")19.dp else 18.dp,bottom=if(t.id=="journal")19.dp else 18.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            YText(word("独立活动另计","独立シーンは別枠"),if(t.id=="journal")15 else 12,Modifier.weight(1f),weight=if(t.id=="journal")FontWeight.Normal else FontWeight.Medium)
            YText(amount,22,number=true)
        }
        YText(word("这部分仍是真实消费，但不占用月总预算或月分类预算。","実際の支出ですが、月全体・月カテゴリの予算に含めません。"),if(t.id=="journal")12 else 10,Modifier.padding(top=13.dp,bottom=5.dp),color=t.muted,lineHeightMultiplier=1.9f)
    }
    }
}

@Composable internal fun <T> BudgetLimitList(items:List<T>,amounts:List<String>,content:@Composable (T,Modifier)->Unit) {
    val t=LocalYohaku.current
    val measurer=rememberTextMeasurer()
    val density=LocalDensity.current
    val amountWidth=amounts.maxOfOrNull{measurer.measure(it,TextStyle(fontFamily=t.number,fontSize=23.sp,fontWeight=FontWeight.Medium),softWrap=false).size.width}?:0
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical=2.dp).testTag("budget-category-list")) {
        val twoColumns=t.id=="objects"&&with(density){((maxWidth-14.dp)/2-28.dp).toPx()}>=amountWidth
        if(twoColumns)Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
            items.chunked(2).forEach{pair->Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                pair.forEach{content(it,Modifier.weight(1f).fillMaxHeight())}
                if(pair.size==1)Spacer(Modifier.weight(1f))
            }}
        }else Column(Modifier.fillMaxWidth().then(if(t.id=="journal")Modifier.drawBehind{drawLine(Color(0x46bc8474),Offset.Zero,Offset(0f,size.height),1.dp.toPx())}.padding(start=9.dp)else Modifier),verticalArrangement=Arrangement.spacedBy(if(t.id=="journal")0.dp else if(t.id=="objects")14.dp else 13.dp)) {
            items.forEach{content(it,Modifier.fillMaxWidth())}
        }
    }
}

@Composable internal fun BudgetLimitRow(id:String,name:String,child:Boolean,spent:Long,limit:Long?,spentText:String,limitText:String,remaining:String,onEdit:()->Unit,modifier:Modifier=Modifier) {
    val t=LocalYohaku.current
    val narrow=LocalConfiguration.current.screenWidthDp<=360
    val objects=t.id=="objects"
    val frame=if(objects)Modifier.drawBehind{drawRoundRect(Color(0x20d4c3a8),Offset(0f,4.dp.toPx()),cornerRadius=androidx.compose.ui.geometry.CornerRadius(17.dp.toPx()))}.background(t.surface,RoundedCornerShape(17.dp)).padding(horizontal=14.dp,vertical=16.dp)
        else Modifier.drawBehind{drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=if(t.id=="journal")PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))else null)}.padding(vertical=if(t.id=="magazine")18.dp else 16.dp)
    Column(modifier.testTag("budget-category-$id").clickable(onClick=onEdit).then(frame),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(objects) {
            Box(Modifier.fillMaxWidth()) {
                Box(Modifier.align(Alignment.TopEnd).offset(x=1.dp,y=(-4).dp).size(25.dp).background(t.soft,RoundedCornerShape(9.dp)),contentAlignment=Alignment.Center){YIcon("bag",Modifier.size(16.dp),t.accent)}
                Column(Modifier.fillMaxWidth().padding(top=25.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    YText((if(child)"↳ "else "")+name,11,weight=FontWeight.Medium)
                    YText(remaining,23,Modifier.padding(vertical=3.dp),number=true,weight=FontWeight.Medium)
                    YText(word("预算内已花 / 预算","予算内支出 / 予算")+" $spentText / $limitText",7,color=t.muted,lineHeightMultiplier=1.8f)
                    limit?.let{BudgetTrack(spent,it,modifier=Modifier.padding(top=5.dp),height=6)}
                }
            }
        }else {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(if(narrow)6.dp else 8.dp),verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.width(28.dp),contentAlignment=Alignment.CenterStart){YIcon("bag",Modifier.size(19.dp),t.muted)}
                YText((if(child)"↳ "else "")+name,if(t.id=="journal")15 else 12,Modifier.weight(1f),weight=if(t.id=="journal")FontWeight.Normal else FontWeight.Medium)
                YText(remaining,if(narrow)17 else 20,number=true)
            }
            Column(Modifier.fillMaxWidth().padding(start=28.dp+(if(narrow)6.dp else 8.dp)),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                YText(word("预算内已花 / 预算","予算内支出 / 予算")+" $spentText / $limitText",9,color=t.muted)
                limit?.let{BudgetTrack(spent,it)}
            }
        }
    }
}
