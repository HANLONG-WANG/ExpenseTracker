package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.data.db.BookOverview
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun BooksScreen(books:List<BookOverview>,current:String,onSelect:(String)->Unit,onCreate:()->Unit,onEdit:(String)->Unit){
    val t=LocalYohaku.current
    Column(Modifier.fillMaxWidth().testTag("books-page"),verticalArrangement=Arrangement.spacedBy(15.dp)){
        YTitle(word("每本，都有自己的生活。","帳簿ごとに、独立した暮らし。"),"INDEPENDENT BOOKS",word("账户、预算、朋友、商户与核对资料全部独立。","口座・予算・友人・お店・明細はすべて独立。"),pageFlow=true)
        books.forEach{overview->BookCard(overview,overview.book.id==current){onSelect(overview.book.id)}}
        Column(Modifier.fillMaxWidth().padding(top=15.dp)){
            YButton(word("＋ 创建独立账本","＋ 独立した帳簿を作成"),onCreate,Modifier.testTag("books-create"),primary=true)
            YText(word("新账本不会复制原流水或余额。同名账户也不会联动。","新しい帳簿に記録・残高はコピーしません。同名口座も連動しません。"),if(t.id=="journal")11 else 10,Modifier.padding(top=if(t.id=="journal")11.dp else 10.dp).testTag("books-note"),color=t.muted,lineHeightMultiplier=if(t.id=="magazine")1.8f else 1.9f,cssLineBox=true)
        }
        ThemeAuditPanel(word("账本资料与本位币","帳簿情報・基準通貨"),Modifier.testTag("books-audit-container")){books.forEach{b->
            val size=if(t.id=="journal")15 else 12
            Column(Modifier.fillMaxWidth().testTag("book-edit-${b.book.id}").clickable{onEdit(b.book.id)}){
                YText(b.book.name+" · "+b.book.currency,size,Modifier.fillMaxWidth().padding(vertical=if(t.id=="objects")15.dp else 14.dp),color=t.accent,lineHeightMultiplier=1.8f,cssLineBox=true)
                Canvas(Modifier.fillMaxWidth().height(1.dp)){drawLine(t.line,Offset.Zero,Offset(this.size.width,0f),1.dp.toPx(),pathEffect=if(t.id=="magazine")null else PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())))}
            }
        }}
    }
}

@Composable private fun BookCard(overview:BookOverview,selected:Boolean,onClick:()->Unit){
    val t=LocalYohaku.current;val journal=t.id=="journal";val objects=t.id=="objects"
    val shape=if(objects)RoundedCornerShape(21.dp,21.dp,25.dp,21.dp) else RoundedCornerShape(0.dp)
    Box(Modifier.fillMaxWidth().testTag("book-choice-${overview.book.id}").selectable(selected,role=Role.RadioButton,onClick=onClick)){
        if(journal||objects)Box(Modifier.matchParentSize().offset(x=if(journal)2.dp else 0.dp,y=if(journal)3.dp else 4.dp).background(if(journal)Color(0x0b927850) else Color(0x18d4c3a8),shape))
        val edge=if(journal)Modifier.drawBehind{val s=1.dp.toPx();drawRect(Color(0xffc8beaa),topLeft=Offset(s/2,s/2),size=androidx.compose.ui.geometry.Size(size.width-s,size.height-s),style=Stroke(s,pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))))} else if(objects)Modifier else Modifier.drawBehind{drawLine(t.ink,Offset(0f,.5.dp.toPx()),Offset(size.width,.5.dp.toPx()),1.dp.toPx());drawLine(t.line,Offset(0f,size.height-.5.dp.toPx()),Offset(size.width,size.height-.5.dp.toPx()),1.dp.toPx())}
        Row(Modifier.fillMaxWidth().background(if(journal||objects)t.surface else Color.Transparent,shape).then(edge).padding(horizontal=if(objects)17.dp else if(journal)15.dp else 0.dp,vertical=if(objects)20.dp else if(journal)19.dp else 18.dp),horizontalArrangement=Arrangement.spacedBy(15.dp),verticalAlignment=Alignment.CenterVertically){
            BookSymbol()
            Column(Modifier.weight(1f)){
                YText(overview.book.name,if(journal)18 else if(objects)16 else 13,weight=if(journal)FontWeight.Normal else if(objects)FontWeight.Medium else FontWeight(550),lineHeightMultiplier=if(journal)26f/18 else if(objects)24f/16 else 19f/13,cssLineBox=true)
                val money=overview.monthlyBudgetMinor?.let{Money(Currency.valueOf(overview.book.currency),it).display()} ?: "—"
                YText("${overview.accountCount} "+word("个账户","口座")+" · "+word("月预算","月予算")+" "+money,if(journal)11 else 9,Modifier.padding(top=8.dp),color=t.muted,lineHeightMultiplier=if(journal)16f/11 else 13f/9,cssLineBox=true)
            }
            YIcon(if(selected)"check" else "arrow",Modifier.size(17.dp),t.accent)
        }
    }
}

@Composable private fun BookSymbol(){
    val t=LocalYohaku.current;val objects=t.id=="objects";val journal=t.id=="journal"
    val shape=if(objects)RoundedCornerShape(6.dp,11.dp,11.dp,6.dp) else RoundedCornerShape(if(journal)2.dp else 0.dp)
    val line=if(objects)Color(0xffd4af7f) else if(journal)t.line else t.ink
    Box(Modifier.size(44.dp,52.dp).then(if(journal||objects)Modifier.rotate(-3f) else Modifier).clip(shape).background(if(objects)Color(0xffe3c59a) else if(journal)t.surface else Color.Transparent,shape).drawWithContent{drawContent();drawRect(if(objects)Color(0xffbb8857) else if(journal)t.warm else t.ink,size=androidx.compose.ui.geometry.Size((if(objects)5.dp else 4.dp).toPx(),size.height))}.border(1.dp,line,shape).padding(start=if(objects)5.dp else 4.dp,end=1.dp,top=1.dp,bottom=1.dp),contentAlignment=Alignment.Center){YIcon("life",Modifier.size(22.dp))}
}

