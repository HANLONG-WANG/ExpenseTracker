package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun ReceiptGlyph(modifier:Modifier=Modifier,paper:Boolean=false,tint:Color=LocalYohaku.current.accent) {
    val t=LocalYohaku.current
    Canvas(modifier){val factor=minOf(size.width,size.height)/24;withTransform({translate((size.width-24*factor)/2,(size.height-24*factor)/2);scale(factor,factor,pivot=Offset.Zero)}){
        val path=Path().apply{moveTo(6f,3f);lineTo(14f,3f);lineTo(18f,7f);lineTo(18f,21f);lineTo(6f,21f);close();moveTo(14f,3f);lineTo(14f,8f);lineTo(18f,8f);moveTo(9f,12f);lineTo(15f,12f);moveTo(9f,16f);lineTo(15f,16f)}
        if(paper)drawPath(path,Color(0xfffff8e4))
        drawPath(path,if(paper)Color(0xffa1845d)else tint,style=Stroke(1.3f))
    }}
}
@Composable internal fun ReceiptInboxCover(pending:Int) {
    val t=LocalYohaku.current;val journal=t.id=="journal";val objects=t.id=="objects"
    val narrow=LocalConfiguration.current.screenWidthDp<=360
    val frame=when(t.id){
        "magazine"->Modifier.drawBehind{drawLine(t.ink,Offset.Zero,Offset(size.width,0f),2.dp.toPx());drawLine(t.ink,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}.padding(vertical=18.dp)
        "journal"->Modifier.rotate(-.7f).background(Color(0xfffff9e8)).border(1.dp,t.line).padding(horizontal=16.dp,vertical=20.dp)
        else->Modifier.drawBehind{drawRoundRect(Color(0x2bbd9565),Offset(0f,5.dp.toPx()),cornerRadius=androidx.compose.ui.geometry.CornerRadius(27.dp.toPx()))}.background(Color(0xffe6cfaa),RoundedCornerShape(18.dp,18.dp,27.dp,27.dp)).padding(start=19.dp,end=19.dp,top=27.dp,bottom=17.dp)
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top=if(objects)23.dp else 12.dp,bottom=19.dp).testTag("receipt-inbox-cover").then(frame)) {
        if(objects)Box(Modifier.align(Alignment.TopCenter).offset(y=(-36).dp).requiredWidth(maxWidth+46.dp).height(22.dp).rotate(-1f).background(Color(0xffc99e70),RoundedCornerShape(9.dp)))
        if(journal)Box(Modifier.align(Alignment.TopCenter).offset(y=(-28).dp).size(91.dp,19.dp).rotate(-3f).background(Color(0x80d5bd85)))
        if(objects)Row(Modifier.align(Alignment.BottomEnd).offset(x=2.dp,y=(-7).dp),horizontalArrangement=Arrangement.spacedBy((-11).dp)) {
            ReceiptGlyph(Modifier.size(33.dp,56.dp).rotate(-12f),paper=true)
            ReceiptGlyph(Modifier.size(33.dp,56.dp).rotate(12f),paper=true)
        }else if(journal)YText("✳",30,Modifier.align(Alignment.BottomEnd).offset(x=(-3).dp).rotate(12f),color=t.warm)
        else ReceiptGlyph(Modifier.align(Alignment.BottomEnd).offset(x=(-5).dp,y=(-6).dp).size(50.dp,70.dp).rotate(9f))
        Column(Modifier.fillMaxWidth()) {
            YText(when(t.id){"magazine"->"THE RECEIPT ARCHIVE / 04";"journal"->word("夹在本子里的小票","ノートに挟んだレシート");else->"MY RECEIPT BOX"},if(journal)12 else if(objects)8 else 9,color=if(journal)t.warm else t.muted)
            YText(when(t.id){"magazine"->word("买过什么，\n慢慢整理。","何を買ったか。\nゆっくり整理。");"journal"->word("买过的东西，记仔细。","買ったものを、ていねいに。");else->word("日常的小票盒。","日々のレシート箱。")},if(narrow)25 else if(journal)24 else if(objects)25 else 27,Modifier.fillMaxWidth(if(journal)1f else if(objects).85f else .8f).padding(vertical=13.dp),lineHeightMultiplier=if(t.id=="magazine")1.4f else 1.5f)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                YText(if(journal||objects)pending.toString()else pending.toString().padStart(2,'0'),31,Modifier.testTag("receipt-pending-count"),number=true)
                YText(word("组小票待整理","組のレシートが未整理"),10,Modifier.weight(1f),color=t.muted)
            }
        }
    }
}
@Composable internal fun ReceiptInboxTabs(current:String,onSelect:(String)->Unit) {
    val t=LocalYohaku.current;val journal=t.id=="journal";val objects=t.id=="objects"
    Row(Modifier.fillMaxWidth().then(if(objects)Modifier.background(Color(0xfff0e6d5),RoundedCornerShape(16.dp)).padding(5.dp)else Modifier.drawBehind{drawLine(if(journal)t.line else t.ink,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=if(journal)PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))else null)}.padding(bottom=if(journal)10.dp else 0.dp)),horizontalArrangement=Arrangement.spacedBy(if(journal)6.dp else if(objects)3.dp else 0.dp)) {
        listOf("UNSORTED" to word("待整理","未整理"),"SORTED" to word("已整理","整理済み"),"ALL" to word("全部","すべて")).forEach{(id,label)->
            val chosen=id==current
            Box(Modifier.weight(1f).testTag("receipt-tab-$id").selectable(selected=chosen,role=Role.Tab,onClick={onSelect(id)}).then(if(journal)Modifier.rotate(-1f)else Modifier).background(if(objects)if(chosen)Color(0xfffffaf0)else Color.Transparent else if(journal)if(chosen)Color(0xffdce4dc)else Color(0xffe7e4d4)else if(chosen)t.ink else Color.Transparent,RoundedCornerShape(if(objects)12.dp else 0.dp)).padding(horizontal=if(journal)5.dp else 4.dp,vertical=9.dp),contentAlignment=Alignment.Center){YText(label,if(journal)14 else if(objects)11 else 12,color=if(t.id=="magazine"&&chosen)t.surface else if(chosen)t.accent else t.ink)}
        }
    }
}
@Composable internal fun ReceiptInboxCard(id:String,index:Int,name:String,metadata:String,status:String,sorted:Boolean,count:Int,onRecord:()->Unit,selected:Boolean=false,onSelect:(()->Unit)?=null,images:@Composable RowScope.()->Unit) {
    val t=LocalYohaku.current;val journal=t.id=="journal";val objects=t.id=="objects"
    val shape=RoundedCornerShape(17.dp,17.dp,8.dp,8.dp)
    val frame=when(t.id){
        "magazine"->Modifier.drawBehind{drawLine(t.ink,Offset.Zero,Offset(size.width,0f),1.dp.toPx())}.padding(top=17.dp)
        "journal"->Modifier.rotate(if(index%2==0)-.6f else .5f).background(Color(0xfffff8e7)).border(1.dp,t.line).padding(start=15.dp,end=15.dp,top=22.dp,bottom=16.dp)
        else->Modifier.background(Color(0xfffff8e7),shape).border(1.dp,Color(0xffe1ccaa),shape).padding(horizontal=15.dp,vertical=20.dp)
    }
    Box(Modifier.fillMaxWidth().padding(top=9.dp).testTag("receipt-card-$id").then(frame)) {
        if(objects)Box(Modifier.align(Alignment.TopCenter).offset(y=(-25).dp).size(55.dp,11.dp).rotate(-4f).background(Color(0xffdbc09c),RoundedCornerShape(4.dp)))
        if(journal)Box(Modifier.align(Alignment.TopEnd).offset(x=(-24).dp,y=(-27).dp).size(10.dp,23.dp).rotate(-12f).border(1.dp,Color(0xff9d9c8e),RoundedCornerShape(4.dp)))
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(13.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                if(onSelect!=null) Box(Modifier.width(70.dp).height(44.dp).testTag("receipt-select-$id").selectable(selected=selected,role=Role.Checkbox,onClick=onSelect),contentAlignment=Alignment.Center) {
                    Box(Modifier.align(Alignment.CenterStart).size(if(objects)25.dp else 20.dp).background(if(selected)t.accent else Color.Transparent,RoundedCornerShape(if(objects)7.dp else if(journal)3.dp else 0.dp)).border(1.dp,t.accent,RoundedCornerShape(if(objects)7.dp else if(journal)3.dp else 0.dp)),contentAlignment=Alignment.Center){ if(selected)YText("✓",14,color=t.surface) }
                    YText((index+1).toString().padStart(2,'0'),12,Modifier.align(Alignment.CenterEnd),number=true)
                }else YText((index+1).toString().padStart(2,'0'),12,if(objects)Modifier.background(Color(0xfff0e4ce),RoundedCornerShape(7.dp)).padding(horizontal=8.dp,vertical=5.dp)else Modifier,number=true)
                YText(status,if(journal)10 else 8,Modifier.then(if(objects)Modifier.background(if(sorted)Color(0xffe3e7d6)else Color(0xffefdfc1),RoundedCornerShape(6.dp))else Modifier.border(1.dp,if(journal)if(sorted)t.accent else t.warm else t.line)).padding(horizontal=8.dp,vertical=5.dp),color=if(objects)if(sorted)Color(0xff7b8663)else Color(0xffa57a47)else if(sorted)t.accent else t.warm)
            }
            Column(Modifier.fillMaxWidth().testTag("receipt-record-$id").clickable(role=Role.Button,onClick=onRecord).padding(top=10.dp,bottom=4.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {YText(name,if(journal)22 else 18);YText(metadata,if(journal)11 else 9,color=t.muted,lineHeightMultiplier=1.8f)}
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(5.dp),horizontalArrangement=Arrangement.spacedBy(17.dp),content=images)
            Column(Modifier.fillMaxWidth().drawBehind{drawLine(t.line,Offset.Zero,Offset(size.width,0f),1.dp.toPx(),pathEffect=if(journal||objects)PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))else null)}.padding(top=if(objects)15.dp else 13.dp)) {
                YText("$count "+word("张图片","枚の画像"),if(journal)11 else 9,color=t.muted)
                YButton(word("查看原记账","元の記録を見る"),onRecord,Modifier.testTag("receipt-open-$id"))
            }
        }
    }
}
@Composable internal fun ReceiptThumbnailFrame(id:String,index:Int,onPreview:()->Unit,content:@Composable ()->Unit) {
    val t=LocalYohaku.current;val journal=t.id=="journal";val objects=t.id=="objects"
    Box(Modifier.size(87.dp,126.dp).testTag("receipt-image-$id").clickable(role=Role.Button,onClick=onPreview).then(if(journal||objects)Modifier.rotate(if(index%2==0)if(objects)-4f else -3f else if(objects)5f else 3f)else Modifier).background(Color.White).border(1.dp,t.line)) {
        content()
        YText((index+1).toString(),10,Modifier.align(Alignment.BottomEnd).padding(5.dp).background(t.surface).padding(horizontal=5.dp,vertical=2.dp),number=true)
    }
}
