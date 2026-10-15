package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.data.db.EventEntity
import com.yohaku.expensetracker.data.repository.BookSnapshot
import com.yohaku.expensetracker.data.repository.accountMoney
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.YText
import com.yohaku.expensetracker.ui.theme.*

/** Frozen v55 .audit-refund-source: each theme has its own frame and typography. */
@Composable internal fun RefundSource(s:BookSnapshot,original:EventEntity) {
    val t=LocalYohaku.current
    val frame=when(t.id) {
        "magazine"->Modifier.drawBehind{drawLine(t.ink,Offset(0f,size.height-1.dp.toPx()),Offset(size.width,size.height-1.dp.toPx()),2.dp.toPx())}.padding(top=17.dp,bottom=19.dp)
        "journal"->Modifier.drawBehind{drawLine(t.warm,Offset(0f,size.height-.5.dp.toPx()),Offset(size.width,size.height-.5.dp.toPx()),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())))}.padding(top=17.dp,bottom=18.dp)
        else->Modifier.background(Color(0xffe5e9d5),RoundedCornerShape(15.dp)).padding(20.dp)
    }
    Column(Modifier.fillMaxWidth().testTag("refund-source").then(frame)) {
        YText(original.merchant.ifBlank{word("原消费","元の購入")},if(t.id=="journal")22 else 17,weight=FontWeight.Medium,lineHeightMultiplier=1.8f,cssLineBox=true)
        YText(original.date+" · "+s.accountMoney(original).display(),10,Modifier.padding(top=8.dp),color=t.muted,lineHeightMultiplier=1.8f,cssLineBox=true)
        YText(word("原币","原通貨")+" "+Money(Currency.valueOf(original.currency),original.amountMinor).display(),10,Modifier.padding(top=8.dp),color=t.muted,lineHeightMultiplier=1.8f,cssLineBox=true)
    }
}

/** Frozen v55 .currency-entry-note; layout and decoration are independent across themes. */
@Composable internal fun CurrencyEntryNote(content:@Composable ColumnScope.()->Unit) {
    val t=LocalYohaku.current
    val frame=when(t.id) {
        "magazine"->Modifier.drawBehind{
            drawLine(t.ink,Offset.Zero,Offset(size.width,0f),1.dp.toPx())
            drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())
        }.padding(vertical=18.dp)
        "journal"->Modifier.rotate(-.2f).background(Color(0xfffff6dc)).drawBehind{
            drawRect(t.line,style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))))
        }.padding(17.dp)
        else->Modifier.background(Color(0xffede8d7),RoundedCornerShape(14.dp)).padding(16.dp)
    }
    Box(Modifier.fillMaxWidth().padding(vertical=17.dp)) {
        Column(Modifier.fillMaxWidth().testTag("currency-entry-note").then(frame),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
    }
}
