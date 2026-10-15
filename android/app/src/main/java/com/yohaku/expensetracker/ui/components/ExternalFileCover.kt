package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun ExternalFileCover(name:String,metadata:String) {
    val t=LocalYohaku.current
    val badge=when(t.id) {
        "magazine"->Modifier.border(1.dp,t.ink)
        "journal"->Modifier.rotate(-4f).shadow(1.dp,clip=false).background(Color(0xfffff9eb)).drawBehind{drawRect(t.line,style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))))}
        else->Modifier.rotate(-5f).drawBehind{drawRoundRect(Color(0x30bcb992),Offset(0f,4.dp.toPx()),cornerRadius=androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()))}.background(Color(0xffe8e6d4),RoundedCornerShape(14.dp,14.dp,22.dp,14.dp))
    }
    Column(Modifier.fillMaxWidth().padding(vertical=20.dp).testTag("media-external-cover"),horizontalAlignment=Alignment.CenterHorizontally) {
        Box(Modifier.size(84.dp,102.dp).then(badge),contentAlignment=Alignment.Center) {
            YIcon("file",Modifier.size(40.dp),if(t.id=="objects")Color(0xff8e9474)else t.ink,stroke=1.3f)
        }
        YText(name,23,Modifier.fillMaxWidth().padding(top=25.dp),lineHeightMultiplier=1.7f,textAlign=TextAlign.Center)
        YText(metadata,10,Modifier.fillMaxWidth().padding(top=10.dp).testTag("media-preview-meta"),color=t.muted,lineHeightMultiplier=2f,textAlign=TextAlign.Center)
        val note=when(t.id) {
            "magazine"->Modifier.drawBehind{drawLine(t.line,Offset.Zero,Offset(size.width,0f),1.dp.toPx());drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}
            "journal"->Modifier.rotate(-.5f).background(Color(0xfffff7dd)).drawBehind{drawRect(t.line,style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))))}
            else->Modifier.background(Color(0xffeee2cc),RoundedCornerShape(15.dp))
        }
        YText(word("此格式不在应用内预览。请使用手机上支持它的应用打开。","この形式はアプリ内でプレビューしません。対応する端末アプリで開きます。"),if(t.id=="journal")14 else 11,Modifier.fillMaxWidth().padding(top=23.dp).then(note).padding(18.dp),lineHeightMultiplier=2f,textAlign=TextAlign.Center)
    }
}
