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
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
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
            YText(word("开启定位后，今天新记且未关联商户的支出会暂存位置；整理时可保留或放弃，确认后才清除临时位置。","位置情報をオンにすると、今日の新規支出で未関連のお店の位置を一時保存。場所の保存・破棄を確認してから一時位置を消去します。"),if(t.id=="journal")11 else 10,color=t.muted,lineHeightMultiplier=1.8f)
        }
    }
}
/** Two-column directory: editorial columns, receipt slips, or ruled handwritten entries. */
@Composable internal fun MerchantPlaceCard(id:String,name:String,secondary:String,active:Boolean,expanded:Boolean?,onClick:()->Unit,modifier:Modifier=Modifier) {
    val t=LocalYohaku.current
    val state=if(expanded==true)word("已展开","展開済み")else word("可展开","折りたたみ")
    val action=if(expanded==null)word("保存商户","お店を保存")else if(expanded)word("收起商户","お店を折りたたむ")else word("展开商户","お店を展開")
    val frame=when(t.id) {
        "magazine"->Modifier.drawBehind{drawLine(if(expanded==true)t.accent else t.ink,Offset.Zero,Offset(size.width,0f),(if(expanded==true)4 else 2).dp.toPx())}
        "journal"->Modifier.background(if(expanded==true)t.soft else Color.Transparent).drawBehind {
            drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx())))
            drawLine(t.accent.copy(alpha=.4f),Offset(3.dp.toPx(),0f),Offset(3.dp.toPx(),size.height),1.dp.toPx())
        }
        else->Modifier.shadow(2.dp,RoundedCornerShape(4.dp)).background(t.surface,RoundedCornerShape(4.dp)).then(if(expanded==true)Modifier.border(1.dp,t.accent,RoundedCornerShape(4.dp))else Modifier).drawBehind {
            drawLine(t.line,Offset(10.dp.toPx(),size.height-5.dp.toPx()),Offset(size.width-10.dp.toPx(),size.height-5.dp.toPx()),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(),3.dp.toPx())))
        }
    }
    Column(modifier.testTag("merchant-card-$id").semantics{if(expanded!=null)stateDescription=state}.clickable(role=Role.Button,onClickLabel=action,onClick=onClick).then(frame).padding(horizontal=10.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            YText(name,if(t.id=="journal")16 else 13,Modifier.weight(1f),bold=t.id=="magazine",maxLines=2,overflow=TextOverflow.Ellipsis)
            // Same disclosure mark as the other expandable rows of the app.
            if(expanded!=null)YText(if(expanded)"−" else "+",15,color=t.accent)
        }
        YText(secondary,10,color=t.muted,maxLines=1,overflow=TextOverflow.Ellipsis)
        if(!active)YText(word("已停用","停止中"),10,color=t.accent)
    }
}

@Composable internal fun MerchantExpanded(id:String,places:List<String>,note:String,active:Boolean,onOpen:()->Unit,onEntry:()->Unit) {
    Panel(Modifier.testTag("merchant-expanded-$id")) {
        YText(places.joinToString(" · ").ifBlank{word("未保存地点","場所未保存")},12,color=LocalYohaku.current.muted)
        if(note.isNotBlank())YText(note,13)
        YActionRow {
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
