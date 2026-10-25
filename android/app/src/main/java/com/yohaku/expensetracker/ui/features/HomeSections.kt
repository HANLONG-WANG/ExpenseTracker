package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.components.YText
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun HomeSpendContext(total:String,independent:String){
    val t=LocalYohaku.current;val paper=t.id=="journal";val objects=t.id=="objects"
    val frame=if(objects)Modifier.background(Color(0xfff0e6d3),RoundedCornerShape(16.dp)).padding(horizontal=15.dp,vertical=19.dp) else if(!paper)Modifier else Modifier.drawBehind{drawLine(t.line,Offset(0f,size.height-.5.dp.toPx()),Offset(size.width,size.height-.5.dp.toPx()),1.dp.toPx(),pathEffect=if(paper)PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())) else null)}.padding(bottom=21.dp)
    Row(Modifier.fillMaxWidth().testTag("home-spend-context").then(frame),horizontalArrangement=Arrangement.spacedBy(15.dp)){
        listOf(word("本月全部净消费","今月の純支出全体") to total,word("其中独立活动支出","うち独立シーンの支出") to independent).forEach{(label,value)->Column(Modifier.weight(1f)){
            YText(label,if(paper)11 else 9,color=t.muted,lineHeightMultiplier=1.9f,cssLineBox=true)
            YText(value,if(paper)26 else 22,Modifier.padding(top=7.dp),number=true,lineHeightMultiplier=if(paper)37f/26 else if(objects)32f/22 else 25f/22,cssLineBox=true)
        }}
    }
}

@Composable internal fun HomeCashNote(amounts:List<String>,onOpen:()->Unit){
    val t=LocalYohaku.current;val paper=t.id=="journal";val objects=t.id=="objects";val shape=RoundedCornerShape(if(objects)16.dp else 0.dp)
    val edge=if(paper)Modifier.drawBehind{drawRect(t.line,style=androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))))} else if(objects)Modifier.drawBehind{drawRoundRect(Color(0x22b1b895),topLeft=Offset(0f,3.dp.toPx()),size=size,cornerRadius=androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()))} else Modifier.drawBehind{drawLine(t.ink,Offset(1.dp.toPx(),0f),Offset(1.dp.toPx(),size.height),2.dp.toPx())}
    Column(Modifier.fillMaxWidth().testTag("home-cash-note").clickable(role=Role.Button,onClick=onOpen).then(if(paper)Modifier.rotate(.3f) else Modifier).then(if(objects)edge else Modifier).background(if(paper)Color(0xfff8f2df) else if(objects)Color(0xffe8e9d9) else t.soft,shape).then(if(objects)Modifier else edge).padding(start=if(paper)19.dp else if(objects)20.dp else 17.dp,end=if(paper)19.dp else if(objects)20.dp else 18.dp,top=if(paper)19.dp else if(objects)20.dp else 18.dp,bottom=if(paper)19.dp else if(objects)20.dp else 18.dp)){
        YText(word("今日实际资金","本日の実資金"),10,color=t.muted,lineHeightMultiplier=1.5f,cssLineBox=true)
        Column(Modifier.padding(vertical=9.dp)){(amounts.ifEmpty{listOf("—")}).forEach{YText(it,30,number=true,lineHeightMultiplier=if(t.id=="magazine")35f/30 else 44f/30,cssLineBox=true)}}
        YText(word("截至今日的已确认余额按原币展示，未来记账和信用额度不计入。","本日までの確定残高を通貨別に表示。将来の記録・利用枠は含みません。"),if(paper)11 else 9,color=t.muted,lineHeightMultiplier=1.9f,cssLineBox=true)
    }
}

@Composable internal fun HomeDailyStats(days:Int,daily:String,today:String){
    val t=LocalYohaku.current;val paper=t.id=="journal";val objects=t.id=="objects"
    val labels=listOf(word("本月剩余 / 含今天","今月の残り・今日を含む"),word("预留后日均可花","確保後、一日あたり"),word("今天全部净消费","今日の純支出全体"))
    val values=listOf(days.toString(),daily,today)
    @Composable fun Value(index:Int){Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){val font=if(paper)25 else if(objects)22 else 21;YText(values[index],font,Modifier.alignByBaseline().testTag("home-daily-value-$index"),number=true,color=if(paper)t.warm else t.ink,lineHeightMultiplier=if(paper)36f/25 else if(objects)32f/22 else 24f/21,cssLineBox=true);if(index==0)YText(word("天","日"),12,Modifier.alignByBaseline().padding(start=5.dp),lineHeightMultiplier=17f/12,cssLineBox=true)}}
    @Composable fun Cell(index:Int,modifier:Modifier=Modifier){Column(modifier.testTag("home-daily-cell-$index").then(if(objects)Modifier.background(Color(0xfffffae9),RoundedCornerShape(11.dp)).padding(14.dp) else Modifier)){
        YText(labels[index],9,color=t.muted,lineHeightMultiplier=1.9f,cssLineBox=true)
        Box(Modifier.padding(vertical=8.dp)){Value(index)}
    }}
    Column(Modifier.fillMaxWidth().testTag("home-daily-stats").padding(vertical=20.dp)){
        if(paper)for(index in 0..2){Row(Modifier.fillMaxWidth().drawBehind{drawLine(t.line,Offset(0f,size.height-.5.dp.toPx()),Offset(size.width,size.height-.5.dp.toPx()),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())))}.padding(top=10.dp,bottom=11.dp),horizontalArrangement=Arrangement.spacedBy(15.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){YText(labels[index],12,Modifier.weight(1f),color=t.muted,lineHeightMultiplier=1.9f,cssLineBox=true);Box(Modifier.padding(vertical=8.dp)){Value(index)}}}
        else if(objects||LocalConfiguration.current.screenWidthDp<=360){Cell(0,Modifier.fillMaxWidth());Row(Modifier.fillMaxWidth().padding(top=if(objects)9.dp else 18.dp).height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(if(objects)9.dp else 18.dp)){Cell(1,Modifier.weight(1f).fillMaxHeight());Cell(2,Modifier.weight(1f).fillMaxHeight())}}
        else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){for(index in 0..2)Cell(index,Modifier.weight(1f))}
    }
}

@Composable internal fun HomeResumeDraft(onResume:()->Unit){
    val t=LocalYohaku.current;val size=if(t.id=="journal")15 else 12
    YText(word("继续本账本未保存的记录","この帳簿の下書きを続ける"),size,Modifier.fillMaxWidth().testTag("home-resume-draft").clickable(role=Role.Button,onClick=onResume).drawBehind{if(t.id!="magazine")drawLine(t.line,Offset(0f,this.size.height-.5.dp.toPx()),Offset(this.size.width,this.size.height-.5.dp.toPx()),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())))}.padding(top=if(t.id=="objects")15.dp else 14.dp,bottom=if(t.id=="objects")16.dp else 15.dp),color=t.accent,lineHeightMultiplier=1.8f,cssLineBox=true)
}
