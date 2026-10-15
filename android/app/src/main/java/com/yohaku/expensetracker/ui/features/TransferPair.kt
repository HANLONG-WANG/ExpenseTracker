package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun TransferPair(from:String,to:String) {
    val t=LocalYohaku.current
    when(t.id) {
        "magazine"->Row(Modifier.fillMaxWidth().padding(top=20.dp,bottom=25.dp).drawBehind{
            drawLine(t.ink,Offset.Zero,Offset(size.width,0f),2.dp.toPx());drawLine(t.ink,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())
        }.padding(top=27.dp,bottom=26.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f)){YText("01 / "+word("实际转出","実際の出金"),8,color=t.muted);YText(from,35,Modifier.padding(top=7.dp),number=true)}
            YText("→",26,number=true,color=t.accent)
            Column(Modifier.weight(1f)){YText("02 / "+word("实际到账","実際の入金"),8,color=t.muted);YText(to,35,Modifier.padding(top=7.dp),number=true)}
        }
        "journal"->Box(Modifier.fillMaxWidth().padding(top=20.dp,bottom=25.dp).rotate(-.4f).background(Color(0xfffff8e7)).drawBehind{
            drawRect(t.line,style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))))
        }) {
            Row(Modifier.fillMaxWidth().padding(start=17.dp,end=17.dp,top=25.dp,bottom=45.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Column{YText(from,34,number=true,color=t.accent);YText(word("这边转出","こちらから出金"),12,Modifier.padding(top=8.dp),color=t.muted)}
                YText("↝",40,Modifier.rotate(-6f),color=t.warm)
                Column{YText(to,34,number=true,color=t.accent);YText(word("那边收到","あちらへ入金"),12,Modifier.padding(top=8.dp),color=t.muted)}
            }
            YText(word("实际金额都可以改","どちらの実額も編集可"),12,Modifier.align(Alignment.BottomEnd).padding(end=15.dp,bottom=11.dp),color=t.warm)
        }
        else->Row(Modifier.fillMaxWidth().padding(top=31.dp,bottom=32.dp),horizontalArrangement=Arrangement.spacedBy(11.dp,Alignment.CenterHorizontally),verticalAlignment=Alignment.CenterVertically) {
            TransferEnvelope(from,word("转出的钱袋","出金のお財布"),false)
            YText("⇄",25,color=Color(0xffb99775))
            TransferEnvelope(to,word("到账的钱袋","入金のお財布"),true)
        }
    }
}
@Composable private fun TransferEnvelope(currency:String,caption:String,incoming:Boolean) {
    val shape=RoundedCornerShape(10.dp,10.dp,19.dp,13.dp)
    val narrow=LocalConfiguration.current.screenWidthDp<=360
    Box(Modifier.size(if(narrow)101.dp else 119.dp,if(narrow)106.dp else 117.dp).testTag(if(incoming)"transfer-envelope-to"else"transfer-envelope-from").rotate(if(incoming)4f else -4f)) {
        Box(Modifier.matchParentSize().offset(y=4.dp).background(Color(0x26bda176),shape))
        Box(Modifier.matchParentSize().background(if(incoming)Color(0xffe5e8d7)else Color(0xffe6d2ae),shape).border(1.dp,if(incoming)Color(0xffcdd2b9)else Color(0xffd0b084),shape))
        Box(Modifier.align(Alignment.TopCenter).offset(y=(-2).dp).padding(horizontal=4.dp).fillMaxWidth().height(26.dp).background(if(incoming)Color(0xffedf0e1)else Color(0xffeddcbd),RoundedCornerShape(7.dp,7.dp,13.dp,13.dp)).drawBehind{drawLine(if(incoming)Color(0xffbfc8a9)else Color(0xffc8aa7b),Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())})
        Column(Modifier.align(Alignment.Center),horizontalAlignment=Alignment.CenterHorizontally){YText(currency,if(narrow)29 else 32,number=true);YText(caption,9,Modifier.padding(top=12.dp),color=Color(0xffac8960))}
    }
}

@Composable internal fun AccountTransferShortcut(onClick:()->Unit) {
    val t=LocalYohaku.current
    val shape=RoundedCornerShape(if(t.id=="objects")12.dp else 0.dp)
    val frame=when(t.id){
        "magazine"->Modifier.border(1.dp,t.ink)
        "journal"->Modifier.background(Color(0xfffaf2da)).drawBehind{drawRect(t.warm,style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))))}
        else->Modifier.background(Color(0xfff0e2ca),shape).border(1.dp,Color(0xffdcc5a3),shape)
    }
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().then(frame).then(Modifier.clickable(onClick=onClick)).testTag("account-detail-transfer").padding(15.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
        YIcon("transfer",Modifier.size(19.dp),t.accent)
        YText(word("从这个账户转账 / 换汇","この口座から振替・両替"),if(t.id=="journal")14 else 12,color=t.accent)
    }
}

@Composable internal fun TransferStateNote(fromConfirmed:Boolean,toConfirmed:Boolean) {
    val t=LocalYohaku.current
    val frame=when(t.id){
        "magazine"->Modifier.background(t.soft).drawBehind{drawLine(t.ink,Offset.Zero,Offset(0f,size.height),2.dp.toPx())}
        "journal"->Modifier.background(Color(0xfffff4d6)).drawBehind{drawRect(t.line,style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))))}
        else->Modifier.background(Color(0xffe7eada),RoundedCornerShape(13.dp))
    }
    val message=when {
        fromConfirmed&&toConfirmed->word("两端按各自实扣实收记账，转账本金不计消费。","両側を実額で記録。振替元金は支出に含めません。")
        fromConfirmed->word("仅转出账户扣款，收款账户等待确认到账。","出金口座のみ控除。入金口座は入金確認待ち。")
        toConfirmed->word("仅收款账户增加实际到账，转出账户等待确认扣款。","入金口座のみ実入金を反映。出金口座は引落確認待ち。")
        else->word("只是计划，不修改实际余额。","予定のみ。実残高は変更しません。")
    }
    YText(message,if(t.id=="journal")12 else 10,Modifier.fillMaxWidth().padding(vertical=6.dp).testTag("transfer-state-note").then(frame).padding(start=if(t.id=="magazine")19.dp else if(t.id=="journal")18.dp else 17.dp,end=if(t.id=="journal")18.dp else 17.dp,top=if(t.id=="journal")18.dp else 17.dp,bottom=if(t.id=="journal")18.dp else 17.dp),color=if(t.id=="objects")Color(0xff84916e)else t.muted,lineHeightMultiplier=2f,cssLineBox=true)
}

@Composable internal fun TransferEstimateLink(label:String,onClick:()->Unit,enabled:Boolean) {
    val t=LocalYohaku.current
    Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
        androidx.compose.material3.Text(label,Modifier.heightIn(min=40.dp).clickable(enabled=enabled,onClick=onClick).padding(top=2.dp,bottom=8.dp),color=t.accent.copy(alpha=if(enabled)1f else .45f),fontFamily=t.body,fontSize=11.sp,lineHeight=18.sp,textDecoration=androidx.compose.ui.text.style.TextDecoration.Underline)
    }
}
