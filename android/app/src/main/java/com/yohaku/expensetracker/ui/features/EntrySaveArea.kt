package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

/**
 * Save actions docked as the sheet footer, so the form scrolls above them and its viewport ends at the dock.
 * While the keyboard is up the dock gives its room back and the actions sit at the end of the form instead.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun EntrySaveArea(editing:Boolean,busy:Boolean,canSave:Boolean,onSave:()->Unit,onCancel:()->Unit,saveLabel:String?=null,amount:String?=null){
    val footer=LocalSheetFooter.current
    val label=(saveLabel?:if(editing)word("保存修改","変更を保存") else word("保存记录","記録を保存"))+amount?.let{"  $it"}.orEmpty()
    val actions=@Composable{EntrySaveActions(editing,busy,canSave,onSave,onCancel,label)}
    if(footer==null||WindowInsets.isImeVisible){Box(Modifier.padding(vertical=12.dp)){actions()};return}
    val latest by rememberUpdatedState(actions)
    DisposableEffect(footer){footer.content={EntrySaveDock{latest()}};onDispose{footer.content=null}}
}

@Composable private fun EntrySaveDock(content:@Composable ()->Unit){
    val t=LocalYohaku.current
    val paper=when(t.id){"journal"->Color(0xfffaf4e4);"objects"->Color(0xfffff9ed);else->t.bg}
    // The dock runs past the sheet padding to its edges: a ruled band for magazine; the others fade the paper over the form's last lines.
    Box(Modifier.fillMaxWidth().testTag("entry-save-dock").drawBehind{
        val bleed=40.dp.toPx()
        if(t.id=="magazine"){
            drawRect(paper,Offset(-bleed,0f),Size(size.width+bleed*2,size.height))
            drawLine(t.line,Offset(-bleed,0f),Offset(size.width+bleed,0f),1.dp.toPx())
        } else {
            val fade=22.dp.toPx()
            drawRect(Brush.verticalGradient(0f to paper.copy(alpha=0f),1f to paper,startY=-fade,endY=0f),Offset(-bleed,-fade),Size(size.width+bleed*2,fade))
            drawRect(paper,Offset(-bleed,0f),Size(size.width+bleed*2,size.height))
        }
    }.padding(top=if(t.id=="magazine")12.dp else 4.dp,bottom=12.dp)){content()}
}

@Composable private fun EntrySaveActions(editing:Boolean,busy:Boolean,canSave:Boolean,onSave:()->Unit,onCancel:()->Unit,label:String){
    val t=LocalYohaku.current
    if(editing)Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(when(t.id){"journal"->10.dp;"objects"->12.dp;else->8.dp})){
        YButton(word("取消","取消"),onCancel,Modifier.fillMaxHeight().testTag("entry-cancel-edit"),enabled=!busy)
        YButton(label,onSave,Modifier.weight(1f).fillMaxHeight().testTag("entry-save-button"),primary=true,enabled=!busy&&canSave)
    }
    else YButton(label,onSave,Modifier.testTag("entry-save-button"),primary=true,enabled=!busy&&canSave)
}
