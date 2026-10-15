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
            YText(word("保存商户与多个地点，记账时主动选择。自动定位匹配尚未提供。","お店と複数の場所を保存し、記録時に選択。現在地との自動照合は未提供です。"),if(t.id=="journal")11 else 10,color=t.muted,lineHeightMultiplier=1.8f)
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
