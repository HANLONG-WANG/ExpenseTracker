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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun ImageViewer(name:String,index:Int,count:Int,zoomed:Boolean,onPrevious:()->Unit,onNext:()->Unit,onZoom:()->Unit,image:@Composable ()->Unit) {
    val t=LocalYohaku.current
    Row(Modifier.fillMaxWidth().padding(top=12.dp,bottom=18.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
        YText(name,10,Modifier.weight(1f))
        YText("${index+1} / $count",10,Modifier.testTag("media-image-index"),number=true)
    }
    val frame=when(t.id) {
        "magazine"->Modifier.background(Color(0xffe8ebe2)).border(1.dp,t.ink).padding(20.dp)
        "journal"->Modifier.background(Color(0xffddd8c5)).drawBehind{drawRect(Color(0xffbab6a1),style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))))}.padding(horizontal=16.dp,vertical=22.dp)
        else->Modifier.background(Color(0xffeadfcd),RoundedCornerShape(19.dp)).padding(22.dp)
    }
    Box(Modifier.fillMaxWidth().then(frame).clipToBounds()) { image() }
    Row(Modifier.fillMaxWidth().padding(vertical=20.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
        ViewerControl("←",word("上一张图片","前の画像"),"media-image-previous",index>0,onPrevious)
        Box(Modifier.weight(1f),contentAlignment=Alignment.Center){ViewerControl(if(zoomed)word("重置缩放","拡大をリセット")else word("放大","拡大"),word("放大 / 还原","拡大 / 戻す"),"media-image-zoom",true,onZoom)}
        ViewerControl("→",word("下一张图片","次の画像"),"media-image-next",index<count-1,onNext)
    }
    YText(word("图片按原比例展示，可放大查看细节。","画像の比率を保持。拡大して細部を確認。"),11,color=t.muted)
}
@Composable private fun ViewerControl(label:String,description:String,tag:String,enabled:Boolean,onClick:()->Unit) {
    val t=LocalYohaku.current
    val frame=when(t.id) {
        "magazine"->Modifier.border(1.dp,t.ink)
        "journal"->Modifier.rotate(-1f).background(Color(0xffe8e7d6)).drawBehind{drawRect(t.line,style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))))}
        else->Modifier.shadow(1.dp,RoundedCornerShape(11.dp)).background(Color(0xffeadcc5),RoundedCornerShape(11.dp))
    }
    Box(Modifier.testTag(tag).alpha(if(enabled)1f else .25f).then(frame).clickable(enabled=enabled,role=Role.Button,onClickLabel=description,onClick=onClick).padding(horizontal=14.dp,vertical=10.dp)) {
        YText(label,if(t.id=="journal")14 else 11,color=if(t.id=="objects")Color(0xffa17854)else t.ink)
    }
}
