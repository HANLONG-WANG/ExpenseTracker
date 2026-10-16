package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun ReceiptWorkflow() {
    val t = LocalYohaku.current
    Row(Modifier.fillMaxWidth().drawBehind {
        if(t.id != "objects") drawLine(t.line, Offset(0f,size.height), Offset(size.width,size.height),1.dp.toPx(),
            pathEffect=if(t.id=="journal")PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))else null)
    }.padding(bottom=16.dp), horizontalArrangement=Arrangement.spacedBy(5.dp)) {
        listOf(word("保存图片","画像を保存"), word("导出给助手","助手へ書出し"), word("导入商品","商品を取込")).forEachIndexed { i, label ->
            Column(Modifier.weight(1f).then(if(t.id=="objects")Modifier.background(Color(0xfff0e7d5),RoundedCornerShape(9.dp)).padding(9.dp)else Modifier)) {
                YText("0${i+1}",if(t.id=="journal")20 else 16,number=true,color=t.accent)
                YText(label,if(t.id=="journal")11 else 9)
            }
        }
    }
}
@Composable internal fun ReceiptHandoffSummary(eyebrow:String,title:String,description:String,review:Boolean=true) {
    val t=LocalYohaku.current
    val frame=if(!review)Modifier.padding(top=15.dp,bottom=23.dp)else when(t.id) {
        "objects"->Modifier.background(Color(0xfff0e0c4),RoundedCornerShape(17.dp)).padding(19.dp)
        else->Modifier.drawBehind { drawLine(if(t.id=="journal")t.line else t.ink, Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=if(t.id=="journal")PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))else null) }.padding(top=15.dp,bottom=23.dp)
    }
    Column(Modifier.fillMaxWidth().then(frame),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        YText(eyebrow,8,color=t.muted)
        YText(title,23)
        if(description.isNotEmpty())YText(description,if(t.id=="journal")12 else 10,color=t.muted,lineHeightMultiplier=2f)
    }
}
@Composable internal fun ReceiptPaper(packageTree:Boolean=false,content:@Composable ColumnScope.()->Unit) {
    val t=LocalYohaku.current
    val frame=when(t.id) {
        "magazine"->if(packageTree)Modifier.background(t.soft).drawBehind { drawLine(t.ink,Offset.Zero,Offset(0f,size.height),2.dp.toPx()) }else Modifier.border(1.dp,t.line)
        "journal"->Modifier.rotate(if(packageTree)-.3f else 0f).background(Color(0xfffff8e7)).drawBehind { drawRect(t.line,style=androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx())))) }
        else->Modifier.shadow(2.dp,RoundedCornerShape(17.dp,17.dp,6.dp,6.dp)).background(if(packageTree)Color(0xfff0e3cd)else Color(0xfffff8e6),RoundedCornerShape(17.dp,17.dp,6.dp,6.dp)).border(1.dp,Color(0xffe2cdab),RoundedCornerShape(17.dp,17.dp,6.dp,6.dp))
    }
    Column(Modifier.fillMaxWidth().padding(vertical=8.dp).then(frame).padding(horizontal=if(t.id=="journal")12.dp else 14.dp,vertical=18.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
}

@Composable internal fun ReceiptEditorItem(index:Int,onRemove:()->Unit,enabled:Boolean,content:@Composable ColumnScope.()->Unit) {
    val t=LocalYohaku.current
    val frame=when(t.id) {
        "magazine"->Modifier.drawBehind{drawLine(t.ink,Offset.Zero,Offset(size.width,0f),1.dp.toPx())}
        "journal"->Modifier.rotate(-.3f).background(Color(0xfffff8e7)).drawBehind{drawRect(t.line,style=androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))))}
        else->Modifier.background(Color(0xfffff8e8),RoundedCornerShape(16.dp)).border(1.dp,Color(0xffe1ceae),RoundedCornerShape(16.dp))
    }
    Column(Modifier.fillMaxWidth().padding(vertical=12.dp).then(frame).padding(horizontal=if(t.id=="magazine")0.dp else 13.dp,vertical=18.dp),verticalArrangement=Arrangement.spacedBy(15.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            YText((index+1).toString().padStart(2,'0'),if(t.id=="journal")23 else if(t.id=="objects")15 else 20,Modifier.then(if(t.id=="objects")Modifier.background(Color(0xffe5d4b6),RoundedCornerShape(6.dp)).padding(horizontal=6.dp,vertical=4.dp)else Modifier),color=if(t.id=="journal")t.warm else t.muted,number=true)
            YButton(word("移除","削除"),onRemove,enabled=enabled,inline=true)
        }
        content()
    }
}
