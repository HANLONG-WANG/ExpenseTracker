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
@Composable internal fun MerchantPlaceCard(id:String,name:String,secondary:String,active:Boolean,expanded:Boolean?,onClick:()->Unit,modifier:Modifier=Modifier,ambiguous:Boolean=false) {
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
    Column(modifier.testTag("merchant-card-$id").semantics{if(expanded!=null)stateDescription=state}.clickable(enabled=!ambiguous,role=Role.Button,onClickLabel=action,onClick=onClick).then(frame).padding(horizontal=10.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            YText(name,if(t.id=="journal")16 else 13,Modifier.weight(1f),bold=t.id=="magazine",maxLines=2,overflow=TextOverflow.Ellipsis)
            // Same disclosure mark as the other expandable rows of the app.
            if(expanded!=null)YText(if(expanded)"−" else "+",15,color=t.accent)
        }
        YText(secondary,10,color=t.muted,maxLines=1,overflow=TextOverflow.Ellipsis)
        if(ambiguous)YText(word("存在多个同名商户，需先合并或改名区分","同名のお店が複数あります。統合するか名前を変えて区別してください"),11,color=t.accent)
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

/** Chip of the merchant list sheet, several per line: boxed editorial tag / ticked notebook word / rounded drawer label. */
@Composable internal fun MerchantChoiceChip(name:String,count:Int,chosen:Boolean,onClick:()->Unit,modifier:Modifier=Modifier) {
    val t=LocalYohaku.current
    val objects=t.id=="objects";val journal=t.id=="journal"
    val shape=RoundedCornerShape(11.dp)
    val frame=when(t.id) {
        "magazine"->Modifier.background(if(chosen)t.ink else Color.Transparent).border(1.dp,if(chosen)t.ink else t.line).padding(horizontal=10.dp,vertical=8.dp)
        "journal"->Modifier.drawBehind {
            drawLine(if(chosen)t.accent else t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx())))
        }.padding(horizontal=2.dp,vertical=7.dp)
        else->Modifier.drawBehind{drawRoundRect(Color(0x12c6ab80),Offset(0f,2.dp.toPx()),cornerRadius=androidx.compose.ui.geometry.CornerRadius(11.dp.toPx()))}
            .background(if(chosen)Color(0xfff0e2ca)else Color(0xfffff7e7),shape).border(1.dp,if(chosen)t.accent else Color(0xffe0caa7),shape).padding(horizontal=11.dp,vertical=8.dp)
    }
    Row(modifier.semantics{selected=chosen}.clickable(role=Role.Button,onClick=onClick).then(frame),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        if(journal)Box(Modifier.size(15.dp).rotate(-4f).background(if(chosen)Color(0xffdae1d3)else Color.Transparent,RoundedCornerShape(2.dp)).border(1.dp,t.accent,RoundedCornerShape(2.dp)),contentAlignment=Alignment.Center){if(chosen)YText("✓",12,color=t.accent,lineHeightMultiplier=1f)}
        YText(name,if(journal)14 else 12,Modifier.weight(1f,fill=false),color=if(chosen)if(t.id=="magazine")t.surface else t.accent else t.ink,lineHeightMultiplier=1.4f,maxLines=1,overflow=TextOverflow.Ellipsis)
        // Record count: tells apart habitual merchants and near-duplicates without a second line.
        if(count>0)YText(count.toString(),if(journal)11 else 9,color=if(chosen&&t.id=="magazine")t.surface else if(t.id=="magazine")t.warm else t.muted,number=true,lineHeightMultiplier=1.4f)
    }
}

/** Editorial index / notebook margin / receipt slip. Only list items receive numbers; sections use headings. */
@Composable internal fun MerchantHistoryPaper(number:String?=null,modifier:Modifier=Modifier,heading:String?=null,content:@Composable ColumnScope.()->Unit) {
    val t=LocalYohaku.current
    val frame=when(t.id) {
        "magazine"->Modifier.drawBehind {
            drawLine(t.ink,Offset.Zero,Offset(size.width,0f),1.dp.toPx())
            drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),.5.dp.toPx())
        }
        "journal"->Modifier.background(t.soft.copy(alpha=.5f)).drawBehind {
            drawLine(t.warm.copy(alpha=.5f),Offset(7.dp.toPx(),0f),Offset(7.dp.toPx(),size.height),1.dp.toPx())
            drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx())))
        }
        else->Modifier.shadow(2.dp,RoundedCornerShape(12.dp)).background(t.surface,RoundedCornerShape(12.dp)).drawBehind {
            drawLine(t.line,Offset(12.dp.toPx(),size.height-7.dp.toPx()),Offset(size.width-12.dp.toPx(),size.height-7.dp.toPx()),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(),3.dp.toPx())))
        }
    }
    Column(modifier.fillMaxWidth().then(frame).padding(start=if(t.id=="journal")17.dp else 12.dp,end=12.dp,top=12.dp,bottom=17.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(heading!=null)YText(heading,15,color=if(t.id=="journal")t.warm else t.ink)
        else if(number!=null)YText(when(t.id){"magazine"->"INDEX / $number";"journal"->word("手记 · $number","覚え書き · $number");else->"YOHAKU / No. $number"},10,color=t.muted)
        content()
    }
}

@Composable internal fun MerchantHistoryStamp(text:String) {
    val t=LocalYohaku.current
    val frame=when(t.id) {
        "magazine"->Modifier.drawBehind{drawLine(t.accent,Offset.Zero,Offset(0f,size.height),3.dp.toPx())}
        "journal"->Modifier.rotate(-1f).drawBehind{drawRect(t.warm,style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),2.dp.toPx()))))}
        else->Modifier.background(t.soft,RoundedCornerShape(12.dp)).border(1.dp,t.line,RoundedCornerShape(12.dp))
    }
    YText(text,11,Modifier.testTag("merchant-history-undone").then(frame).padding(8.dp),color=t.accent)
}
