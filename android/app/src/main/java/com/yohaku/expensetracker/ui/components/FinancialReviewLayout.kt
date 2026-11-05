package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.yohaku.expensetracker.ui.theme.*

/** A dedicated window owns Back while a financial flow is open; the account navigator stays below it. */
@Composable internal fun FinancialFlow(
    title:String, step:Int, onClose:()->Unit, onBack:()->Unit,
    processing:Boolean=false, trial:Boolean=false,
    scrollState:ScrollState=rememberScrollState(),
    actions:@Composable ColumnScope.()->Unit,
    content:@Composable ColumnScope.()->Unit
) {
    val t=LocalYohaku.current
    val journal=t.id=="journal";val objects=t.id=="objects"
    val back=if(step>0)onBack else onClose
    Dialog(onDismissRequest=back,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        val focus=LocalFocusManager.current
        val keyboard=LocalSoftwareKeyboardController.current
        LaunchedEffect(step,trial){if(step>0){focus.clearFocus();keyboard?.hide()}}
        val window=(LocalView.current.parent as DialogWindowProvider).window
        SideEffect {
            WindowCompat.getInsetsController(window,window.decorView).apply {
                isAppearanceLightStatusBars=true;isAppearanceLightNavigationBars=true
            }
        }
        Column(Modifier.fillMaxSize().background(t.bg).windowInsetsPadding(WindowInsets.safeDrawing).imePadding().testTag("finance-flow")) {
            Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    if(step>0)YBackLink(onBack,Modifier.testTag("finance-flow-back"))
                    YText(title,if(journal)24 else 21,Modifier.weight(1f).testTag("finance-flow-title"),weight=if(objects)FontWeight.Medium else FontWeight.Normal)
                    YButton(word("关闭","閉じる"),onClose,Modifier.testTag("finance-flow-close"))
                }
                val labels=listOf(word("填写","入力"),if(trial)word("试算","試算")else if(processing)word("比较","比較")else word("预览","確認"))+if(processing)listOf(word("记录结果","結果を記録"))else emptyList()
                when {
                    journal->Row(Modifier.fillMaxWidth().drawBehind{drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}.padding(bottom=8.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                        YText(labels[step],14,color=t.accent)
                        YText("${step+1} / ${labels.size}",14,number=true)
                    }
                    objects->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(5.dp),verticalAlignment=Alignment.Bottom) {
                        labels.forEachIndexed{i,label->YText("${i+1} · $label",11,Modifier.weight(1f).padding(top=if(i==step)0.dp else 6.dp).background(if(i==step)t.surface else t.soft,RoundedCornerShape(9.dp,9.dp,0.dp,0.dp)).padding(8.dp),color=if(i==step)t.accent else t.muted)}
                    }
                    else->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        labels.forEachIndexed{i,label->YText("${i+1}  $label",11,Modifier.weight(1f).drawBehind{if(i==step)drawLine(t.ink,Offset(0f,size.height),Offset(26.dp.toPx(),size.height),2.dp.toPx())}.padding(bottom=8.dp),color=if(i==step)t.ink else t.muted)}
                    }
                }
            }
            val paper=Modifier.background(t.surface,if(objects)RoundedCornerShape(16.dp,16.dp,0.dp,0.dp)else RoundedCornerShape(0.dp)).then(if(journal)Modifier.drawBehind{
                drawLine(t.line,Offset(12.dp.toPx(),0f),Offset(12.dp.toPx(),size.height),1.dp.toPx())
            }else Modifier)
            // Keep viewport geometry separate from content insets, including accessibility scroll bounds.
            Column(Modifier.weight(1f).fillMaxWidth().then(paper).verticalScroll(scrollState).testTag("finance-flow-scroll")) {
                Column(Modifier.fillMaxWidth().padding(start=if(journal)26.dp else 20.dp,end=20.dp,top=16.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp),content=content)
            }
            Column(Modifier.fillMaxWidth().testTag("finance-flow-actions")
                .then(when{objects->Modifier.padding(8.dp).background(t.soft,RoundedCornerShape(16.dp));journal->Modifier.background(t.surface).drawBehind{drawLine(t.line,Offset(12.dp.toPx(),0f),Offset(12.dp.toPx(),size.height),1.dp.toPx())};else->Modifier.background(t.bg)})
                .padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp),content=actions)
        }
    }
}

/** Inline sections on the flow's single paper surface, without another panel shell. */
@Composable internal fun FinancialChangeSummary(content:@Composable ColumnScope.()->Unit) {
    val t=LocalYohaku.current
    Column(Modifier.fillMaxWidth().testTag("finance-change-summary").then(when(t.id){
        "journal"->Modifier.drawBehind{drawLine(t.accent,Offset(0f,0f),Offset(0f,size.height),2.dp.toPx())}.padding(start=12.dp)
        "objects"->Modifier.background(t.soft,RoundedCornerShape(12.dp)).padding(14.dp)
        else->Modifier
    }),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        YText(word("本次变化","今回の変更"),18,weight=FontWeight.Medium)
        content()
    }
}

@Composable internal fun FinancialFullDetails(tag:String="finance-review-full-details",title:String=word("查看完整依据","すべての根拠を見る"),content:@Composable ColumnScope.()->Unit) {
    var expanded by remember{mutableStateOf(false)}
    YButton(title+if(expanded)" −"else" +",{expanded=!expanded},Modifier.testTag(tag))
    if(expanded)Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
}

/** Notes share the flow paper; only an objects heading gets a small divider tab. */
@Composable internal fun FinancialReviewSection(title:String,modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) {
    val t=LocalYohaku.current
    Column(modifier.fillMaxWidth().padding(vertical=6.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        YText((if(t.id=="journal")"· "else "")+title,15,
            if(t.id=="objects")Modifier.background(t.soft,RoundedCornerShape(5.dp)).padding(horizontal=8.dp,vertical=4.dp)else Modifier,
            color=t.accent,weight=FontWeight.Medium)
        content()
    }
}
