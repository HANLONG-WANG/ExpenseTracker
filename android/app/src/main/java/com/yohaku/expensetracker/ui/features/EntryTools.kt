package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.components.YText
import com.yohaku.expensetracker.ui.theme.*

/** Final completion.js entry tools: book name, book switch and theme sheet. */
@Composable internal fun EntryTools(book:String,onBooks:()->Unit,onTheme:()->Unit) {
    val t=LocalYohaku.current
    val frame=if(t.id=="objects")Modifier.background(Color(0xfff1e8d5),RoundedCornerShape(7.dp)).padding(8.dp) else Modifier
    // The enclosing sheet contributes 15dp; CSS collapsed gaps are 23dp and 20dp.
    Box(Modifier.fillMaxWidth().padding(top=8.dp,bottom=5.dp)){
    FlowRow(Modifier.fillMaxWidth().testTag("entry-tools-row").then(frame),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(10.dp),itemVerticalAlignment=Alignment.CenterVertically) {
        YText(book,9,Modifier.weight(1f),color=t.muted,lineHeightMultiplier=13f/9,cssLineBox=true)
        EntryToolLink(word("切换账本","帳簿を切替"),onBooks,Modifier.testTag("entry-book"))
        EntryToolLink(word("切换主题","テーマを切替"),onTheme,Modifier.testTag("entry-theme"))
    }
    }
}

@Composable private fun EntryToolLink(label:String,onClick:()->Unit,modifier:Modifier=Modifier) {
    val t=LocalYohaku.current
    val fontSize=if(t.id=="journal")15 else 9
    val padding=if(t.id=="objects")15 else 7
    YText(label,fontSize,modifier.clickable(onClick=onClick).drawBehind{
        drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=if(t.id!="magazine")PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())) else null)
    }.padding(top=padding.dp,bottom=(padding+1).dp),color=t.accent,lineHeightMultiplier=1.8f,cssLineBox=true)
}

@Composable internal fun EntryThemeOption(label:String,onClick:()->Unit) {
    val t=LocalYohaku.current
    val fontSize=if(t.id=="journal")15 else 12
    val padding=if(t.id=="objects")15 else 22
    YText(label,fontSize,Modifier.fillMaxWidth().clickable(onClick=onClick).drawBehind{
        drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=if(t.id!="magazine")PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())) else null)
    }.padding(top=padding.dp,bottom=(padding+1).dp),color=t.accent,lineHeightMultiplier=1.8f,cssLineBox=true)
}
