package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.data.db.CategoryEntity
import com.yohaku.expensetracker.data.repository.BookSnapshot
import com.yohaku.expensetracker.domain.category.resolveCategoryIcon
import com.yohaku.expensetracker.domain.model.EntryKind
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

/** Archived roots keep their whole group; an archived child under a live root stands alone with its parent path. */
@Composable internal fun CategoryArchiveShelf(s:BookSnapshot,kind:EntryKind,onEdit:(CategoryEntity)->Unit,onRestore:(CategoryEntity)->Unit) {
    val t=LocalYohaku.current
    val roots=s.categories.filter{it.kind==kind.name&&it.parentId==null}
    val entries=roots.filter{!it.active}+roots.filter{it.active}.flatMap{r->s.categories.filter{it.parentId==r.id&&!it.active}}
    if(entries.isEmpty())return
    var open by rememberSaveable(s.book.id,kind){mutableStateOf(false)}
    val journal=t.id=="journal";val objects=t.id=="objects"
    val frame=when(t.id){
        // A kraft envelope with its flap folded over the top edge.
        "journal"->Modifier.rotate(-.4f).background(Color(0xffefe3c6)).drawBehind{
            val dash=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))
            drawRect(Color(0xffc9b48a),style=Stroke(1.dp.toPx(),pathEffect=dash))
            val flap=Path().apply{moveTo(0f,0f);lineTo(size.width/2,22.dp.toPx());lineTo(size.width,0f)}
            drawPath(flap,Color(0xffc9b48a),style=Stroke(1.dp.toPx(),pathEffect=dash))
        }.padding(start=16.dp,end=16.dp,top=24.dp,bottom=10.dp)
        // A drawer front: shadowed box, inset face and a pull handle.
        "objects"->Modifier.drawBehind{drawRoundRect(Color(0xffcfc4aa),topLeft=Offset(0f,4.dp.toPx()),size=size,cornerRadius=androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()))}
            .background(Color(0xffddd3ba),RoundedCornerShape(16.dp)).padding(7.dp).background(Color(0xffe9e1cb),RoundedCornerShape(11.dp)).padding(start=12.dp,end=12.dp,top=8.dp,bottom=6.dp)
        else->Modifier.drawBehind{drawLine(t.ink,Offset.Zero,Offset(size.width,0f),1.dp.toPx())}.padding(top=4.dp)
    }
    val stateLabel=if(open)word("已展开","展開中") else word("已收起","折りたたみ")
    Column(Modifier.fillMaxWidth().padding(top=if(t.id=="magazine")6.dp else 12.dp,bottom=8.dp).testTag("category-archive-${kind.name}").then(frame)){
        if(objects)Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom=6.dp).size(44.dp,6.dp).background(Color(0xffbb8c63),RoundedCornerShape(3.dp)))
        Row(Modifier.fillMaxWidth().testTag("category-archive-toggle-${kind.name}").semantics{stateDescription=stateLabel}.clickable(role=Role.Button){open=!open}.padding(vertical=12.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
            when(t.id){
                "journal"->YText(word("✉ 收进信封","✉ 封筒にしまう"),11,color=t.warm,letterSpacing=1f,lineHeightMultiplier=16f/11,cssLineBox=true)
                "objects"->YText(word("收纳抽屉","しまう引き出し"),8,Modifier.rotate(-2f).background(Color(0xffd8c09d),RoundedCornerShape(5.dp)).padding(horizontal=8.dp,vertical=5.dp),color=t.muted,letterSpacing=1f,lineHeightMultiplier=12f/8,cssLineBox=true)
                else->YText("ARCHIVE",8,color=t.muted,letterSpacing=2f,lineHeightMultiplier=1.4f,cssLineBox=true)
            }
            YText(word("已归档","アーカイブ"),if(journal)18 else 14,Modifier.weight(1f),weight=FontWeight.Medium,lineHeightMultiplier=if(journal)26f/18 else 20f/14,cssLineBox=true)
            YText(if(t.id=="magazine")entries.size.toString().padStart(2,'0') else word("${entries.size} 项","${entries.size} 件"),if(journal)12 else 10,number=t.id=="magazine",color=t.muted,lineHeightMultiplier=1.5f,cssLineBox=true)
            YText(if(open)"−" else "+",17,color=t.accent,lineHeightMultiplier=25f/17,cssLineBox=true)
        }
        if(open){
            YText(word("不出现在记账与预算选择中，历史记录照常保留。","記録・予算の選択には出ません。履歴はそのまま残ります。"),if(journal)12 else 10,Modifier.padding(bottom=6.dp),color=t.muted,lineHeightMultiplier=1.7f,cssLineBox=true)
            Column(verticalArrangement=Arrangement.spacedBy(if(objects)8.dp else 0.dp)){
                entries.forEach{c->
                    val children=if(c.parentId==null)s.categories.filter{it.parentId==c.id} else emptyList()
                    ArchivedCategoryRow(c,if(c.parentId==null)c.name else s.path(c.id),
                        if(c.parentId!=null)word("二级分类 · 一级仍在使用","子カテゴリ・親は使用中") else if(children.isEmpty())word("一级分类","親カテゴリ") else word("一级分类 · 连同 ${children.size} 个子分类","親カテゴリ・子 ${children.size} 件を含む"),
                        resolveCategoryIcon(c.id,s.categories),{onEdit(c)},{onRestore(c)})
                    children.forEach{child->ArchivedChildRow(child,resolveCategoryIcon(child.id,s.categories)){onEdit(child)}}
                }
            }
        }
    }
}

@Composable private fun ArchivedCategoryRow(c:CategoryEntity,title:String,caption:String,icon:String,onOpen:()->Unit,onRestore:()->Unit) {
    val t=LocalYohaku.current
    val rule=when(t.id){
        "journal"->Modifier.drawBehind{drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())))}
        "objects"->Modifier.background(Color(0xfff6efdd),RoundedCornerShape(9.dp)).padding(horizontal=12.dp)
        else->Modifier.drawBehind{drawLine(t.line,Offset.Zero,Offset(size.width,0f),1.dp.toPx())}
    }
    Row(Modifier.fillMaxWidth().testTag("category-archived-${c.id}").then(rule).clickable(role=Role.Button,onClick=onOpen).padding(vertical=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
        CategoryIconBadge(icon,CategoryBadge.SMALL,Modifier.alpha(.55f))
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
            YText(title,if(t.id=="journal")17 else 13,color=t.muted,weight=FontWeight.Medium,lineHeightMultiplier=if(t.id=="journal")25f/17 else 19f/13,cssLineBox=true)
            YText(caption,if(t.id=="journal")11 else 9,color=t.muted,lineHeightMultiplier=1.5f,cssLineBox=true)
        }
        val restore=word("取消归档","解除")
        val fontSize=if(t.id=="journal")13 else 10
        val button=when(t.id){
            // Journal stamps the envelope; objects uses a raised tab; magazine keeps an underlined text action.
            "journal"->Modifier.rotate(-3f).border(1.dp,t.warm).padding(horizontal=8.dp,vertical=4.dp)
            "objects"->Modifier.background(Color(0xffd7b994),RoundedCornerShape(7.dp)).padding(horizontal=10.dp,vertical=7.dp)
            else->Modifier.drawBehind{drawLine(t.accent,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}.padding(vertical=6.dp)
        }
        YText(restore,fontSize,Modifier.testTag("category-restore-${c.id}").clickable(role=Role.Button,onClick=onRestore).padding(4.dp).then(button),color=if(t.id=="journal")t.warm else t.accent,lineHeightMultiplier=1.5f,cssLineBox=true)
    }
}

@Composable private fun ArchivedChildRow(c:CategoryEntity,icon:String,onOpen:()->Unit) {
    val t=LocalYohaku.current
    Row(Modifier.fillMaxWidth().testTag("category-archived-child-${c.id}").clickable(role=Role.Button,onClick=onOpen).padding(start=if(t.id=="objects")24.dp else 36.dp,top=9.dp,bottom=9.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
        YText(if(t.id=="journal")"↳" else "└",if(t.id=="journal")13 else 10,color=t.line,lineHeightMultiplier=1.5f)
        CategoryIconBadge(icon,CategoryBadge.SMALL,Modifier.alpha(.45f))
        YText(c.name,if(t.id=="journal")15 else 11,Modifier.weight(1f),color=t.muted,lineHeightMultiplier=1.6f,cssLineBox=true)
        if(!c.active)YText(word("单独归档","個別にアーカイブ"),if(t.id=="journal")11 else 9,color=t.muted,lineHeightMultiplier=1.5f)
    }
}
