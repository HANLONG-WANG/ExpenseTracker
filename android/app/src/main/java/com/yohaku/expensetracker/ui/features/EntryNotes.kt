package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

/** Stage-A notes use the approved entry-details treatment; later fields join it. */
@Composable internal fun EntryNotes(id:String?,note:String,onNote:(String)->Unit) {
    val t=LocalYohaku.current
    var expanded by rememberSaveable(id){mutableStateOf(note.isNotBlank())}
    val stateLabel=if(expanded)word("已展开","展開中") else word("已收起","折りたたみ")
    Column(Modifier.fillMaxWidth().testTag("entry-notes").drawBehind{
        drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=if(t.id=="journal")PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())) else null)
    }.padding(top=15.dp,bottom=16.dp)) {
        Row(Modifier.fillMaxWidth().testTag("entry-notes-toggle").semantics{stateDescription=stateLabel}.clickable{expanded=!expanded},verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
            YText(word("备注","メモ"),when(t.id){"magazine"->10;"journal"->14;else->12},Modifier.weight(1f),color=t.accent)
            YText(if(expanded)"−" else "+",17,color=t.accent,lineHeightMultiplier=25f/17,cssLineBox=true)
        }
        if(expanded)Box(Modifier.fillMaxWidth().padding(top=16.dp,bottom=16.dp)){
            YField(word("备注","メモ"),note,onNote,multiline=true)
        }
    }
}
