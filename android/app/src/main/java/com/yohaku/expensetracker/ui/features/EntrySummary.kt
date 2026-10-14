package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun EntrySummary(book:String,path:String,editing:Boolean,onCategory:()->Unit) {
    val t=LocalYohaku.current
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    val narrow=LocalConfiguration.current.screenWidthDp<=360
    val objects=t.id=="objects";val journal=t.id=="journal"
    val frame=if(objects)Modifier.background(Color(0xffeee2cd),RoundedCornerShape(17.dp)).padding(17.dp) else Modifier.drawBehind{
        drawLine(if(journal)t.line else t.ink,Offset(0f,size.height-.5.dp.toPx()),Offset(size.width,size.height-.5.dp.toPx()),1.dp.toPx(),pathEffect=if(journal)PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())) else null)
    }.padding(top=16.dp,bottom=17.dp)
    Row(Modifier.fillMaxWidth().testTag("entry-summary").then(frame),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(if(narrow)10.dp else 14.dp)) {
        val mark=if(editing)"↺" else "02"
        if(objects)Box(Modifier.size(37.dp,40.dp).rotate(-4f).background(Color(0xffb97852),RoundedCornerShape(10.dp,10.dp,15.dp,10.dp)),contentAlignment=Alignment.Center){YText(mark,17,number=true,color=Color(0xfffff7e6),lineHeightMultiplier=24f/17,cssLineBox=true)}
        else YText(mark,if(journal)28 else 27,Modifier.then(if(journal)Modifier.rotate(-10f) else Modifier.drawBehind{drawLine(t.line,Offset(size.width-.5.dp.toPx(),0f),Offset(size.width-.5.dp.toPx(),size.height),1.dp.toPx())}.padding(end=16.dp)),number=true,color=if(journal)t.warm else t.ink,lineHeightMultiplier=if(journal)40f/28 else 31f/27,cssLineBox=true)
        Column(Modifier.weight(1f)){
            YText(book+" · "+if(editing)word("修改原条目","元の記録を変更") else word("分类已选","カテゴリ選択済"),if(journal)11 else if(objects)8 else 9,color=t.muted,lineHeightMultiplier=if(journal)16f/11 else if(objects)11f/8 else 13f/9,cssLineBox=true)
            YText(path,if(narrow||objects)18 else if(journal)24 else 21,Modifier.padding(top=6.dp),weight=if(journal)FontWeight.Normal else FontWeight.Medium,lineHeightMultiplier=if(narrow)26f/18 else if(journal)35f/24 else if(objects)26f/18 else 30f/21,cssLineBox=true)
        }
        val linkSize=if(objects)10 else 11
        var linkBaseline by remember(t.id,LocalLanguage.current){mutableFloatStateOf(0f)}
        YText(word("重选","変更"),linkSize,Modifier.testTag("entry-reselect").clickable(role=Role.Button){focus.clearFocus();keyboard?.hide();onCategory()}.padding(horizontal=6.dp,vertical=1.dp).drawBehind{drawLine(if(objects)Color(0xffad7354) else t.ink,Offset(0f,linkBaseline+4.dp.toPx()),Offset(size.width,linkBaseline+4.dp.toPx()),1.dp.toPx())},color=if(objects)Color(0xffad7354) else t.ink,lineHeightMultiplier=if(objects)15f/10 else 16f/11,cssLineBox=true,onTextLayout={linkBaseline=it.getLineBaseline(0)})
    }
}
