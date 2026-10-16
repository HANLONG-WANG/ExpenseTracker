package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.*

private fun Modifier.dashed(color:Color)=drawBehind{drawRect(color,style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))))}
@Composable internal fun MaterialFileTile(id:String,name:String,metadata:String,audio:Boolean,onOpen:()->Unit,onRemove:(()->Unit)?,thumbnail:(@Composable ()->Unit)?=null) {
    val t=LocalYohaku.current
    val frame=when(t.id){
        "magazine"->Modifier.border(1.dp,t.line)
        "journal"->Modifier.rotate(-.3f).background(Color(0xfffff9eb)).drawBehind{drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx())))}
        else->Modifier.shadow(1.dp,RoundedCornerShape(14.dp)).background(Color(0xfffffcf2),RoundedCornerShape(14.dp)).border(1.dp,Color(0xffede0ca),RoundedCornerShape(14.dp))
    }
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().testTag("media-file-$id").then(frame).clickable(role=Role.Button,onClickLabel=word("打开附件","添付を開く"),onClick=onOpen).padding(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
            if(thumbnail!=null)Box(Modifier.size(44.dp,if(t.id=="objects")50.dp else 48.dp).then(when(t.id){"journal"->Modifier.rotate(-3f).shadow(2.dp).border(3.dp,Color(0xfffffdfa));"objects"->Modifier.rotate(-6f).shadow(2.dp).border(3.dp,Color(0xfffffdf6));else->Modifier}),contentAlignment=Alignment.Center){thumbnail()}
            else Box(Modifier.size(44.dp,48.dp).then(if(t.id=="objects")Modifier.background(if(audio)Color(0xffe8d5c2)else Color(0xffe8e7d6),RoundedCornerShape(9.dp))else Modifier),contentAlignment=Alignment.Center){YIcon(if(audio)"audio"else"file",Modifier.size(25.dp),if(t.id=="objects")if(audio)Color(0xffb07857)else Color(0xff8e9675)else t.accent)}
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                YText(name,if(t.id=="journal")15 else if(t.id=="objects")11 else 12,lineHeightMultiplier=1.6f)
                YText(metadata,if(t.id=="journal")10 else 8,color=t.muted,lineHeightMultiplier=1.9f)
            }
            YText("↗",18,color=t.accent)
        }
        onRemove?.let{MaterialRemove(Modifier.align(Alignment.TopEnd).offset(x=5.dp,y=(-5).dp),"media-remove-$id",word("移除附件","添付を削除"),it)}
    }
}
@Composable internal fun MaterialUpload(label:String,tag:String,enabled:Boolean,leadingIcon:(@Composable ()->Unit)?=null,onClick:()->Unit) {
    val t=LocalYohaku.current
    val frame=when(t.id){"magazine"->Modifier.border(1.dp,t.ink);"journal"->Modifier.rotate(.2f).background(Color(0xffeee9d8)).dashed(Color(0xffb4ad96));else->Modifier.background(Color(0xfff7eedb),RoundedCornerShape(13.dp)).drawBehind{drawRoundRect(Color(0xffcebb98),cornerRadius=androidx.compose.ui.geometry.CornerRadius(13.dp.toPx()),style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))))}}
    Row(Modifier.fillMaxWidth().padding(vertical=12.dp).testTag(tag).then(frame).clickable(enabled=enabled,role=Role.Button,onClick=onClick).padding(horizontal=10.dp,vertical=16.dp),horizontalArrangement=Arrangement.spacedBy(9.dp,Alignment.CenterHorizontally),verticalAlignment=Alignment.CenterVertically) {
        if(leadingIcon!=null)leadingIcon()else YIcon("paperclip",Modifier.size(18.dp),if(t.id=="objects")Color(0xffae815a)else t.accent)
        YText(label,if(t.id=="journal")15 else 12,Modifier.weight(1f,false),color=if(t.id=="objects")Color(0xffae815a)else t.ink)
    }
}
@Composable internal fun MaterialReceiptStrip(count:Int,revision:Int?,content:@Composable RowScope.()->Unit) {
    val t=LocalYohaku.current
    val corner=with(LocalDensity.current){16.dp.toPx()}
    val receiptShape=GenericShape { size,_->
        moveTo(corner,0f);lineTo(size.width-corner,0f);quadraticTo(size.width,0f,size.width,corner)
        lineTo(size.width,size.height*.98f)
        for(i in 24 downTo 0)lineTo(size.width*i/25,size.height*(if(i%2==0)1f else .98f))
        lineTo(0f,corner);quadraticTo(0f,0f,corner,0f);close()
    }
    val frame=when(t.id){
        "magazine"->Modifier.drawBehind{drawLine(t.ink,Offset.Zero,Offset(size.width,0f),1.dp.toPx());drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}.padding(vertical=16.dp)
        "journal"->Modifier.rotate(-.4f).shadow(1.dp,clip=false).background(Color(0xfffff8e8)).border(1.dp,t.line).padding(start=13.dp,end=13.dp,top=19.dp,bottom=13.dp)
        else->Modifier.shadow(1.dp,receiptShape).background(Color(0xfffff9e9),receiptShape).border(1.dp,Color(0xffe9d8be),receiptShape).padding(start=13.dp,end=13.dp,top=17.dp,bottom=21.dp)
    }
    Box(Modifier.fillMaxWidth().padding(top=12.dp,bottom=18.dp).testTag("media-receipt-strip").then(frame)) {
        if(t.id=="journal")Box(Modifier.align(Alignment.TopEnd).offset(x=(-7).dp,y=(-25).dp).size(11.dp,25.dp).rotate(-12f).border(1.dp,Color(0xff9d9c8e),RoundedCornerShape(5.dp)))
        Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                YText(word("小票 · $count 张","レシート・$count 枚"),10,Modifier.weight(1f))
                YText(revision?.let{word("第${it}版 · 待整理","第${it}版・未整理")}?:word("原图 · 待整理","原画像・未整理"),8,color=t.warm)
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start=8.dp,end=8.dp,top=14.dp,bottom=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.Top,content=content)
        }
    }
}
@Composable internal fun MaterialReceiptImage(id:String,index:Int,onOpen:()->Unit,onRemove:(()->Unit)?,onPrevious:(()->Unit)?,onNext:(()->Unit)?,image:@Composable ()->Unit) {
    val t=LocalYohaku.current
    Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(67.dp,99.dp).rotate(if(t.id=="journal")if(index%2==0)-3f else 3f else if(t.id=="objects")if(index%2==0)-4f else 5f else 0f).shadow(if(t.id=="magazine")0.dp else 3.dp,clip=false).background(Color.White).border(1.dp,t.line)) {
            Box(Modifier.fillMaxSize().testTag("media-receipt-$id").clickable(role=Role.Button,onClickLabel=word("预览原图","原画像を見る"),onClick=onOpen)) {image()}
            YText("${index+1}",10,Modifier.align(Alignment.BottomEnd).padding(5.dp).background(t.surface).padding(horizontal=5.dp,vertical=2.dp),number=true)
            onRemove?.let{MaterialRemove(Modifier.align(Alignment.TopEnd).offset(x=8.dp,y=(-8).dp),"media-receipt-remove-$id",word("移除这张图片","この画像を削除"),it)}
        }
        if(onRemove!=null)Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
            ReceiptOrder("←",word("提前一张","前に移動"),"media-receipt-previous-$id",onPrevious)
            ReceiptOrder("→",word("后移一张","後に移動"),"media-receipt-next-$id",onNext)
        }
    }
}
@Composable private fun ReceiptOrder(label:String,description:String,tag:String,onClick:(()->Unit)?) {
    val t=LocalYohaku.current
    Box(Modifier.size(31.dp).testTag(tag).alpha(if(onClick==null).25f else 1f).border(1.dp,t.line,RoundedCornerShape(if(t.id=="objects")7.dp else 0.dp)).semantics{contentDescription=description}.clickable(enabled=onClick!=null,role=Role.Button){onClick?.invoke()},contentAlignment=Alignment.Center){YText(label,12,color=t.accent)}
}
@Composable private fun MaterialRemove(modifier:Modifier,tag:String,label:String,onClick:()->Unit) {
    val t=LocalYohaku.current
    val shape=when(t.id){"journal"->RoundedCornerShape(44,47,37,46);"objects"->RoundedCornerShape(50);else->RoundedCornerShape(0)}
    Box(modifier.size(24.dp).testTag(tag).background(if(t.id=="objects")Color(0xffb97956)else if(t.id=="journal")Color(0xfffff7e5)else t.surface,shape).border(if(t.id=="objects")2.dp else 1.dp,if(t.id=="objects")t.bg else if(t.id=="journal")t.warm else t.ink,shape).semantics{contentDescription=label}.clickable(role=Role.Button,onClick=onClick),contentAlignment=Alignment.Center){YText("×",if(t.id=="objects")13 else 14,color=if(t.id=="objects")Color(0xfffff8e7)else if(t.id=="journal")t.warm else t.ink)}
}
