package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun MerchantLocationNotice() {
    val t=LocalYohaku.current
    val frame=when(t.id) {
        "magazine"->Modifier.background(t.soft,RoundedCornerShape(4.dp))
        "journal"->Modifier.rotate(.4f).background(t.soft).drawBehind{drawRect(t.line,style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))))}
        else->Modifier.background(t.soft,RoundedCornerShape(14.dp))
    }
    Row(Modifier.fillMaxWidth().testTag("merchant-location-notice").then(frame).padding(17.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        YIcon("leaf",Modifier.size(24.dp),t.accent)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(7.dp)) {
            YText(word("记住，常去的地方","いつもの場所を覚えて"),if(t.id=="journal")16 else 12)
            YText(word("保存商户与地点，主动开启后仅在记账时定位。多个候选可手动选择。","お店と場所を保存。オンにした場合のみ記録時に現在地を取得。複数候補は手動で選べます。"),if(t.id=="journal")11 else 10,color=t.muted,lineHeightMultiplier=1.8f)
        }
    }
}
@Composable internal fun MerchantPlaceCard(id:String,name:String,places:List<String>,active:Boolean,onOpen:()->Unit,onEntry:()->Unit) {
    val t=LocalYohaku.current;val journal=t.id=="journal";val objects=t.id=="objects"
    Panel(Modifier.testTag("merchant-card-$id")) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
            val shape=if(journal)RoundedCornerShape(3.dp,5.dp,1.dp,4.dp)else RoundedCornerShape(9.dp)
            Box(Modifier.size(if(journal||objects)32.dp else 24.dp).then(if(journal)Modifier.rotate(-4f).border(1.dp,t.line,shape)else if(objects)Modifier.background(t.soft,shape)else Modifier),contentAlignment=Alignment.Center){YIcon("bag",Modifier.size(18.dp),t.accent)}
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                YText(name,if(journal)16 else if(objects)12 else 11)
                YText(places.joinToString(" · ").ifBlank{word("未保存地点","場所未保存")},if(journal)11 else 9,color=t.muted,lineHeightMultiplier=1.8f)
                if(!active)YText(word("已停用 · 历史保留","停止済み・履歴を保持"),11,color=t.muted)
            }
        }
        // Native targets stack below the name so long names and multiple places remain readable.
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            YButton(word("记一笔","記録"),onEntry,Modifier.testTag("merchant-entry-$id"),enabled=active)
            YButton(word("查看商户资料","お店の情報を見る"),onOpen,Modifier.testTag("merchant-open-$id"))
        }
    }
}

/** Final frozen theme overrides for location candidates; the caller supplies live Android status. */
@Composable internal fun LocationCandidatesPanel(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) {
    val t=LocalYohaku.current
    val objects=t.id=="objects";val journal=t.id=="journal"
    val shape=RoundedCornerShape(if(objects)16.dp else 0.dp)
    val edge=if(objects)Modifier else if(journal)Modifier.drawBehind{drawRect(t.line,style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))))}else Modifier.border(1.dp,t.line)
    Column(modifier.fillMaxWidth().padding(vertical=12.dp).background(if(journal)Color(0xfff7f1dd)else if(objects)Color(0xffefe7d5)else Color.Transparent,shape).then(edge).padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        YText(word("附近常去商户","近くのいつものお店"),if(journal)11 else if(objects)10 else 9,color=t.muted,lineHeightMultiplier=if(journal)17f/11 else if(objects)1.5f else 13f/9,cssLineBox=true)
        content()
    }
}

@Composable internal fun LocatedMerchantChip(name:String,place:String,onClick:()->Unit,modifier:Modifier=Modifier) {
    val t=LocalYohaku.current
    val objects=t.id=="objects";val journal=t.id=="journal"
    val shape=RoundedCornerShape(if(objects)11.dp else 0.dp)
    val edge=if(objects)Modifier.border(1.dp,Color(0xffe0caa7),shape)else Modifier.drawBehind {
        drawLine(t.line,Offset(0f,size.height-.5.dp.toPx()),Offset(size.width,size.height-.5.dp.toPx()),1.dp.toPx(),pathEffect=if(journal)PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))else null)
    }
    Box(modifier.widthIn(min=44.dp).clickable(role=androidx.compose.ui.semantics.Role.Button,onClick=onClick)) {
        if(objects)Box(Modifier.matchParentSize().offset(y=2.dp).background(Color(0x12c6ab80),shape))
        Column(Modifier.background(if(objects)Color(0xfffff7e7)else Color.Transparent,shape).then(edge).padding(start=if(objects)11.dp else if(journal)9.dp else 0.dp,end=if(objects)11.dp else if(journal)9.dp else 0.dp,top=if(objects)11.dp else if(journal)8.dp else 10.dp,bottom=if(objects)11.dp else if(journal)9.dp else 11.dp)) {
            YText(name,if(journal)12 else if(objects)11 else 10,lineHeightMultiplier=if(journal)17f/12 else if(objects)16f/11 else 1.5f,cssLineBox=true)
            Spacer(Modifier.height(4.dp))
            YText(place,if(journal)10 else 8,color=t.muted,lineHeightMultiplier=if(journal)1.5f else 11f/8,cssLineBox=true)
        }
    }
}
