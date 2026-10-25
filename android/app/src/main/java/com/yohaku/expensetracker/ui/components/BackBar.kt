package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.*

/** app.js backBar(): -3 top margin, bottom rule, current page name and 22 bottom margin. */
@Composable fun YBackBar(pageName:String,onBack:()->Unit){
    val t=LocalYohaku.current
    Column(Modifier.fillMaxWidth().testTag("page-back").layout{measurable,constraints->
        val p=measurable.measure(constraints);val overlap=3.dp.roundToPx()
        layout(p.width,(p.height-overlap).coerceAtLeast(0)){p.place(0,-overlap)}
    }.padding(bottom=7.dp)){
        Row(Modifier.fillMaxWidth().padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically){
            YText(word("← 返回","← 戻る"),if(t.id=="journal")14 else 13,Modifier.weight(1f).clickable(onClick=onBack),color=t.muted,lineHeightMultiplier=if(t.id=="journal")19f/14 else 18f/13,cssLineBox=true)
            YText(pageName,if(t.id=="journal")13 else 12,Modifier.testTag("page-back-name"),color=t.ink,lineHeightMultiplier=18f/12,cssLineBox=true)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
    }
}
