package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yohaku.expensetracker.data.repository.ArrangementMoneyRow
import com.yohaku.expensetracker.domain.model.Currency
import com.yohaku.expensetracker.domain.model.Money
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

/** Each side describes its own obligations, never the account's actual debt balance. */
@Composable internal fun ArrangementRows(title:String,rows:List<ArrangementMoneyRow>,currency:Currency,remainingAfterRange:Long?=null) {
    val t=LocalYohaku.current
    val journal=t.id=="journal";val objects=t.id=="objects"
    val unknown=word("未知","不明")
    val ordered=rows.sortedBy{it.date}
    val canProject=remainingAfterRange!=null&&remainingAfterRange>=0&&ordered.all{it.principalMinor?.let{n->n>=0}==true}
    fun amount(n:Long?)=n?.let{Money(currency,it).display()}?:unknown
    val values=ordered.mapIndexed{i,row->listOf(row.principalMinor,row.feeMinor,row.totalMinor,
        if(canProject)ordered.drop(i+1).fold(remainingAfterRange!!){sum,next->Math.addExact(sum,next.principalMinor!!)}else null)}
    ThemeSection(title,"PAYMENT PLAN / "+currency.name)
    if(ordered.isEmpty()) {YEmpty(word("此方案没有已列付款。","このプランに支払予定はありません。"));return}
    val headers=listOf(word("本金","元金"),word("费用","費用"),word("合计","合計"),word("期后本金","支払後元金"))
    val size=if(journal)11 else 10
    val headerSize=if(journal)10 else 9
    val measure=rememberTextMeasurer();val density=LocalDensity.current
    fun width(text:String,number:Boolean,fontSize:Int)=with(density){measure.measure(text,TextStyle(fontFamily=if(number)t.number else t.body,fontSize=fontSize.sp)).size.width.toDp()+2.dp}
    val dateWidth=maxOf(66.dp,ordered.maxOf{width(it.date,true,size)})
    val moneyWidth=maxOf(headers.maxOf{width(it,false,headerSize)},values.flatten().maxOf{width(amount(it),it!=null,size)})
    val shape=RoundedCornerShape(if(objects)13.dp else if(journal)0.dp else 4.dp)
    val frame=Modifier.border(1.dp,t.line,shape).clip(shape)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val inset=8.dp
        val tableWidth=maxOf(maxWidth,dateWidth+moneyWidth*4+8.dp*4+inset*2)
        val scrolls=tableWidth>maxWidth
        Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
            if(scrolls)YText(word("左右滑动核对完整金额。","左右にスクロールして金額を確認。"),10,color=t.muted)
            Column(Modifier.horizontalScroll(rememberScrollState()).testTag("arrangement-table-scroll-$title")) {
                Column(Modifier.width(tableWidth).testTag("arrangement-table-$title").then(frame)) {
                    fun Modifier.rule(header:Boolean=false)=drawBehind {
                        val color=if(objects)Color(0xffddc7a3)else if(header&&!journal)t.ink else t.line
                        drawLine(color,Offset(0f,this.size.height),Offset(this.size.width,this.size.height),if(header&&!journal&&!objects)2.dp.toPx()else 1.dp.toPx(),
                            pathEffect=if(journal||objects)PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))else null)
                    }
                    Row(Modifier.fillMaxWidth().background(t.soft).rule(true).padding(horizontal=inset,vertical=11.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        YText(word("付款日","支払日"),headerSize,Modifier.width(dateWidth),color=t.muted)
                        headers.forEach{YText(it,headerSize,Modifier.weight(1f),color=t.muted,textAlign=TextAlign.End)}
                    }
                    ordered.forEachIndexed{i,row->
                        Row(Modifier.fillMaxWidth().testTag("arrangement-row-$title-$i").rule().padding(horizontal=inset,vertical=11.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            YText(row.date,size,Modifier.width(dateWidth),number=true)
                            values[i].forEachIndexed{column,n->YText(amount(n),size,Modifier.weight(1f).testTag("arrangement-cell-$title-$i-$column"),number=n!=null,textAlign=TextAlign.End)}
                        }
                    }
                }
            }
            YText(if(canProject)word("期后本金按这份方案的待付义务推算，包含已知范围后本金；不是实际余额。","支払後元金はこのプランの未払予定と範囲後元金から推計し、実残高とは異なります。")
                else word("部分安排、本金未知或缺少完整依据时，期后本金保持未知；不代表已结清。","一部予定・元金不明・完全な根拠なしでは支払後元金は不明です。完済とは判定しません。"),10,color=t.muted)
        }
    }
}
