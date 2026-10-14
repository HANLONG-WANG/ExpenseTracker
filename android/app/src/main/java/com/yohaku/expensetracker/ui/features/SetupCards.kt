package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import com.yohaku.expensetracker.data.db.AccountEntity
import com.yohaku.expensetracker.data.repository.BookSnapshot
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun SetupAccountCard(a:AccountEntity,includeMargin:Boolean=true,onEdit:()->Unit) {
    val t=LocalYohaku.current
    val narrow=LocalConfiguration.current.screenWidthDp<=360
    val journal=t.id=="journal";val objects=t.id=="objects"
    val shape=RoundedCornerShape(if(objects)13.dp else 0.dp)
    val frame=when(t.id){
        "journal"->Modifier.rotate(-.3f).background(Color(0xfffff8e6)).drawBehind{drawRect(t.line,style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))))}
        "objects"->Modifier.drawBehind{drawRoundRect(Color(0x20b9996f),topLeft=Offset(0f,3.dp.toPx()),cornerRadius=androidx.compose.ui.geometry.CornerRadius(13.dp.toPx()))}.background(Color(0xfffff8e8),shape).border(1.dp,Color(0xffe4cfac),shape)
        else->Modifier.drawBehind{drawLine(t.ink,Offset.Zero,Offset(size.width,0f),1.dp.toPx());drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}
    }
    val badge=if(objects)Modifier.background(Color(0xffe8dac0),RoundedCornerShape(6.dp)) else Modifier.then(if(journal)Modifier.rotate(-5f) else Modifier).border(1.dp,if(journal)t.warm else t.ink)
    Row(Modifier.fillMaxWidth().padding(vertical=if(includeMargin)10.dp else 0.dp).testTag("setup-account-"+a.id).clickable(role=Role.Button,onClick=onEdit).then(frame).padding(horizontal=if(narrow)8.dp else if(journal||objects)12.dp else 0.dp,vertical=if(narrow)if(journal||objects)18.dp else 17.dp else if(journal)20.dp else if(objects)21.dp else 22.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(if(narrow)7.dp else 10.dp)){
        YText(a.currency,if(journal)10 else 8,Modifier.then(badge).padding(horizontal=if(journal)5.dp else if(objects)5.dp else 6.dp,vertical=if(journal)8.dp else if(objects)8.dp else 9.dp),color=if(journal)t.warm else if(objects)Color(0xffa17c55) else t.accent,lineHeightMultiplier=if(journal)1.5f else 11f/8,cssLineBox=true)
        Column(Modifier.weight(1f)){
            YText(a.name,if(narrow)11 else if(journal)15 else 12,weight=if(journal)FontWeight.Normal else FontWeight.Medium,lineHeightMultiplier=1.8f,cssLineBox=true)
            YText((if(AccountType.valueOf(a.type).debt)word("期初未还本金","期首未返済元金") else word("期初资金余额","期首残高"))+" · "+a.openingDate,if(narrow)8 else if(journal)10 else 8,color=t.muted,lineHeightMultiplier=1.8f,cssLineBox=true)
        }
        YText(Money(Currency.valueOf(a.currency),a.openingMinor).display(),if(narrow)15 else if(journal)20 else 17,number=true,lineHeightMultiplier=if(journal)29f/20 else if(objects)25f/17 else 20f/17,cssLineBox=true)
        YText("↗",14,lineHeightMultiplier=1.4f,cssLineBox=true)
    }
}

@Composable internal fun SetupNote(text:String,pageFlow:Boolean=true,modifier:Modifier=Modifier) {
    val t=LocalYohaku.current
    val frame=when(t.id){
        "journal"->Modifier.rotate(-.3f).background(Color(0xfffff4d6)).drawBehind{drawRect(t.line,style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))))}.padding(17.dp)
        "objects"->Modifier.background(Color(0xffede7d4),RoundedCornerShape(13.dp)).padding(17.dp)
        else->Modifier.drawBehind{drawLine(t.line,Offset.Zero,Offset(size.width,0f),1.dp.toPx());drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}.padding(vertical=18.dp)
    }
    Box(modifier.fillMaxWidth().padding(vertical=if(pageFlow)5.dp else 0.dp)){
        YText(text,if(t.id=="journal")12 else 10,Modifier.fillMaxWidth().testTag("setup-note").then(frame),color=t.muted,lineHeightMultiplier=1.9f,cssLineBox=true)
    }
}

@Composable internal fun SetupReview(s:BookSnapshot,onEditAccount:(AccountEntity)->Unit) {
    val t=LocalYohaku.current
    Column(Modifier.fillMaxWidth().testTag("setup-review")){
        RecordFacts(listOf(word("账本","帳簿") to s.book.name,word("本位币","基準通貨") to (s.book.currency+" · "+when(s.book.currency){"CNY"->word("人民币","人民元");"USD"->word("美元","米ドル");else->word("日元","日本円")}),
            word("月预算","月予算") to (s.book.budgetMinor?.let{Money(Currency.valueOf(s.book.currency),it).display()}?:word("稍后设置","後で設定")),
            word("开始日期","開始日") to s.book.startDate),exactLineBox=true)
        Column(Modifier.padding(vertical=23.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            if(s.accounts.isEmpty())YText(word("账户尚未添加，可进入后继续。","口座未作成。開始後に追加できます。"),14,color=t.muted,lineHeightMultiplier=1.5f,cssLineBox=true)
            s.accounts.forEach{a->SetupAccountCard(a,includeMargin=false){onEditAccount(a)}}
        }
        val frame=when(t.id){
            "journal"->Modifier.background(Color(0xffe6eadb)).drawBehind{drawRect(t.line,style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))))}
            "objects"->Modifier.background(Color(0xffe8eada),RoundedCornerShape(15.dp))
            else->Modifier.background(t.soft).drawBehind{drawLine(t.ink,Offset.Zero,Offset(0f,size.height),2.dp.toPx())}
        }
        Column(Modifier.fillMaxWidth().then(frame).padding(start=if(t.id=="magazine")20.dp else if(t.id=="journal")19.dp else 18.dp,end=if(t.id=="journal")19.dp else 18.dp,top=if(t.id=="journal")19.dp else 18.dp,bottom=if(t.id=="journal")19.dp else 18.dp).testTag("setup-next-pages"),verticalArrangement=Arrangement.spacedBy(7.dp)){
            listOf(word("分类：尚未创建","カテゴリ：未作成"),word("分析重点：等待你选择","分析の視点：選択待ち"),word("金融规则：按账户逐步补齐","金融条件：口座ごとに補足")).forEach{YText("□ $it",if(t.id=="journal")13 else 11,color=t.muted,lineHeightMultiplier=2.1f,cssLineBox=true)}
        }
    }
}

/** The page supplies 15dp of each 19dp CSS margin. */
@Composable internal fun SetupBackLink(onBack:()->Unit){
    val t=LocalYohaku.current
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    var baseline by remember(t.id,LocalLanguage.current){mutableFloatStateOf(0f)}
    Box(Modifier.fillMaxWidth().padding(vertical=4.dp),contentAlignment=Alignment.Center){
        YText(word("上一步","前へ"),11,Modifier.testTag("setup-back-link").clickable(role=Role.Button){focus.clearFocus();keyboard?.hide();onBack()}.padding(horizontal=6.dp,vertical=1.dp).drawBehind{drawLine(t.ink,Offset(0f,baseline+4.dp.toPx()),Offset(size.width,baseline+4.dp.toPx()),1.dp.toPx())},lineHeightMultiplier=16f/11,cssLineBox=true,onTextLayout={baseline=it.getLineBaseline(0)})
    }
}

@Composable internal fun AccountCurrencyFixed(currency:String){
    val t=LocalYohaku.current;val journal=t.id=="journal";val objects=t.id=="objects"
    val frame=if(objects)Modifier.background(Color(0xffede7d4),RoundedCornerShape(14.dp)).padding(18.dp) else Modifier.drawBehind{drawLine(t.line,Offset(0f,size.height-.5.dp.toPx()),Offset(size.width,size.height-.5.dp.toPx()),1.dp.toPx(),pathEffect=if(journal)PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())) else null)}.padding(top=16.dp,bottom=17.dp)
    Column(Modifier.fillMaxWidth().testTag("account-currency-fixed").then(frame)){
        YText(word("账户币种","口座通貨"),10,Modifier.testTag("fixed-currency-label"),color=t.muted,lineHeightMultiplier=1.5f,cssLineBox=true)
        YText(currency,23,Modifier.padding(vertical=9.dp).testTag("fixed-currency-code"),number=true,lineHeightMultiplier=if(t.id=="magazine")26f/23 else 34f/23,cssLineBox=true)
        YText(word("已有流水，币种保持固定；其他币种请新建账户。","記録あり。通貨は固定。他の通貨は新口座へ。"),if(journal)11 else 9,Modifier.testTag("fixed-currency-description"),color=t.muted,lineHeightMultiplier=1.9f,cssLineBox=true)
    }
}
