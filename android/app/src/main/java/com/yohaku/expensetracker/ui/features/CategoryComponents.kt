package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun CategoryShortcuts(title:String,choices:List<Pair<String,String>>,onChoose:(String)->Unit) {
    val t=LocalYohaku.current
    Column(Modifier.fillMaxWidth().padding(bottom=7.dp).testTag("category-shortcuts"),verticalArrangement=Arrangement.spacedBy(12.dp)){
        YText(title,10,color=t.muted,lineHeightMultiplier=if(t.id=="journal")2f else 1.9f,cssLineBox=true)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){choices.forEach{(id,label)->
            val frame=when(t.id){
                "magazine"->Modifier.border(1.dp,t.line)
                "journal"->Modifier.rotate(-1f).background(Color(0xfff6e8bb)).drawBehind{drawLine(t.warm,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())))}
                else->Modifier.background(Color(0xffe6ebd7),RoundedCornerShape(7.dp))
            }
            YText(label,11,Modifier.testTag("category-shortcut-$id").then(frame).clickable(role=Role.Button){onChoose(id)}.padding(start=if(t.id=="magazine")12.dp else 11.dp,end=if(t.id=="magazine")12.dp else 11.dp,top=if(t.id=="magazine")10.dp else 9.dp,bottom=if(t.id=="objects")9.dp else 10.dp),lineHeightMultiplier=1.7f,cssLineBox=true)
        }}
    }
}

@Composable internal fun CategoryChoice(name:String,onClick:()->Unit,modifier:Modifier=Modifier) {
    val t=LocalYohaku.current
    val frame=if(t.id=="objects")Modifier.background(Color(0xfffff9ea),RoundedCornerShape(11.dp)).padding(horizontal=11.dp,vertical=14.dp) else Modifier.padding(start=if(t.id=="magazine")0.dp else 12.dp,end=12.dp,top=16.dp,bottom=16.dp)
    Column(modifier.clickable(role=Role.Button,onClick=onClick)){
        Row(Modifier.fillMaxWidth().then(frame),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
            Box(Modifier.size(19.dp,if(t.id=="objects")24.dp else 23.dp),contentAlignment=Alignment.TopCenter){YIcon("wallet",Modifier.size(19.dp))}
            YText(name,when(t.id){"journal"->18;"objects"->13;else->14},Modifier.weight(1f),weight=FontWeight.Medium,lineHeightMultiplier=when(t.id){"journal"->26f/18;"objects"->19f/13;else->20f/14},cssLineBox=true)
            YText("↗",14,color=t.muted,lineHeightMultiplier=20f/14,cssLineBox=true)
        }
        if(t.id!="objects")CategoryRule(t.line,t.id=="journal")
    }
}

@Composable internal fun CategoryGroup(name:String,index:Int,children:List<Pair<String,String>>,open:Boolean,onToggle:()->Unit,onParent:()->Unit,onChild:(String)->Unit,onAdd:()->Unit,id:String?=null) {
    val t=LocalYohaku.current
    val narrow=LocalConfiguration.current.screenWidthDp<=360
    val frame=when(t.id){
        "journal"->Modifier.background(Color(0xfffff8e4)).drawBehind{drawRect(t.line,style=androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))))}.padding(1.dp)
        "objects"->Modifier.background(Color(0xffece4ce),RoundedCornerShape(16.dp)).padding(top=5.dp)
        else->Modifier
    }
    Column(Modifier.fillMaxWidth().testTag("category-group-$id").then(frame)){
        // Leaf and parent roots share one header row so numbers, icons and names stay in the same columns.
        run {
            Row(Modifier.fillMaxWidth().testTag(if(children.isEmpty())"category-leaf-$id" else "category-group-trigger-$id").clickable(role=Role.Button,onClick=onToggle).padding(horizontal=if(t.id=="magazine")0.dp else if(narrow)10.dp else 14.dp,vertical=if(narrow)16.dp else 18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(if(narrow)7.dp else 10.dp)){
                val numberFrame=if(t.id=="objects")Modifier.background(Color(0xffd7b994),RoundedCornerShape(5.dp)).padding(horizontal=6.dp,vertical=4.dp) else Modifier
                YText((index+1).toString().padStart(2,'0'),if(t.id=="journal")14 else if(t.id=="objects")9 else 11,numberFrame,number=true,color=if(t.id=="journal")t.warm else t.muted,lineHeightMultiplier=if(t.id=="journal")20f/14 else if(t.id=="objects")13f/9 else 12f/11,cssLineBox=true)
                YIcon("wallet",Modifier.size(20.dp))
                YText(name,if(t.id=="journal")if(narrow)18 else 19 else if(narrow)14 else 15,Modifier.weight(1f),weight=FontWeight.Medium,lineHeightMultiplier=if(t.id=="journal")if(narrow)26f/18 else 27f/19 else if(narrow)20f/14 else 21f/15,cssLineBox=true)
                if(children.isEmpty())YText("↗",14,color=t.muted,lineHeightMultiplier=20f/14,cssLineBox=true)
                else {
                    YText(children.size.toString()+" "+word("个子分类","子カテゴリ"),if(t.id=="journal")11 else if(narrow)8 else 9,Modifier.then(if(t.id=="objects")Modifier.background(Color(0xfffff6e4),RoundedCornerShape(5.dp)).padding(horizontal=7.dp,vertical=5.dp) else Modifier),color=t.muted,lineHeightMultiplier=if(t.id=="journal")16f/11 else if(narrow)12f/8 else 13f/9,cssLineBox=true)
                    YText("＋",18,Modifier.rotate(if(open)45f else 0f),color=t.ink,lineHeightMultiplier=26f/18,cssLineBox=true)
                }
            }
            if(open&&children.isNotEmpty()){
                if(t.id=="journal")CategoryRule(t.line,true)
                val inset=if(t.id=="magazine")Modifier.padding(start=26.dp).drawBehind{drawLine(t.line,Offset.Zero,Offset(0f,size.height),1.dp.toPx())}.padding(start=17.dp,end=14.dp,bottom=16.dp) else Modifier.padding(start=14.dp,end=14.dp,bottom=16.dp)
                Column(Modifier.fillMaxWidth().then(inset),verticalArrangement=Arrangement.spacedBy(if(t.id=="objects")8.dp else 0.dp)){
                    val parentFrame=when(t.id){
                        "magazine"->Modifier.background(Color(0xffe8ece2)).drawBehind{drawLine(t.ink,Offset.Zero,Offset(0f,size.height),2.dp.toPx())}
                        "journal"->Modifier.rotate(-.5f).drawBehind{drawLine(t.warm,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}
                        else->Modifier.background(Color(0xfffff7e8),RoundedCornerShape(9.dp)).drawBehind{drawRoundRect(Color(0xffcdb48b),Offset(.5.dp.toPx(),.5.dp.toPx()),androidx.compose.ui.geometry.Size(size.width-1.dp.toPx(),size.height-1.dp.toPx()),androidx.compose.ui.geometry.CornerRadius(9.dp.toPx()),style=androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))))}
                    }
                    Column(Modifier.fillMaxWidth().testTag("category-parent-choice-$id").then(parentFrame).clickable(role=Role.Button,onClick=onParent).padding(start=if(t.id=="objects")13.dp else 12.dp,end=if(t.id=="objects")13.dp else 12.dp,top=if(t.id=="objects")14.dp else 13.dp,bottom=if(t.id=="magazine")13.dp else 14.dp)){
                        YText(name+" / "+word("未细分","未分類の内訳"),if(t.id=="journal")16 else 12,color=if(t.id=="journal")t.warm else t.ink,lineHeightMultiplier=1.7f,cssLineBox=true)
                        YText(word("直接记入一级分类","親カテゴリに直接記録")+" ↗",if(t.id=="journal")11 else 9,Modifier.padding(top=5.dp),color=t.muted,lineHeightMultiplier=1.7f,cssLineBox=true)
                    }
                    if(t.id!="objects")Spacer(Modifier.height(8.dp))
                    if(t.id=="objects"&&!narrow)children.chunked(2).forEach{row->
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){row.forEach{(childId,label)->CategoryChoice(label,{onChild(childId)},Modifier.weight(1f).testTag("category-child-$childId"))};if(row.size==1)Spacer(Modifier.weight(1f))}
                    } else children.forEach{(childId,label)->CategoryChoice(label,{onChild(childId)},Modifier.fillMaxWidth().testTag("category-child-$childId"))}
                    CategoryAddChild(onAdd)
                }
            }
        }
        if(t.id=="magazine")CategoryRule(t.line,false)
    }
}

@Composable internal fun CategoryManageGroup(id:String,name:String,favorite:Boolean,children:List<Pair<String,String>>,onParent:()->Unit,onChild:(String)->Unit,onAdd:()->Unit,first:Boolean=false) {
    val t=LocalYohaku.current
    val frame=when(t.id){
        "journal"->Modifier.background(Color(0xfffff8e4)).drawBehind{drawRect(t.line,style=androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))))}.padding(1.dp)
        "objects"->Modifier.drawBehind{drawRoundRect(Color(0xffdbd2bd),topLeft=Offset(0f,3.dp.toPx()),size=size,cornerRadius=androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()))}.background(Color(0xffece4ce),RoundedCornerShape(16.dp)).padding(top=7.dp)
        else->Modifier
    }
    Column(Modifier.fillMaxWidth().testTag("category-manage-group-$id").then(frame)){
        // Magazine groups are rows of one list: a hairline between groups, none above the first or below the last.
        if(t.id=="magazine"&&!first)CategoryRule(t.line,false)
        Row(Modifier.fillMaxWidth().testTag("category-manage-parent-$id").clickable(role=Role.Button,onClick=onParent).padding(start=if(t.id=="magazine")0.dp else 16.dp,end=16.dp,top=20.dp,bottom=20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp)){
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    YIcon("wallet",Modifier.size(22.dp))
                    YText(name,if(t.id=="journal")20 else 15,Modifier.weight(1f),weight=FontWeight.Medium,lineHeightMultiplier=if(t.id=="journal")29f/20 else 22f/15)
                }
                YText(word("一级分类","親カテゴリ")+if(favorite)" · "+word("常用","よく使う") else "",10,Modifier.padding(start=32.dp),color=t.muted,lineHeightMultiplier=1.5f)
            }
            YText("↗",13,Modifier.width(12.dp),color=t.muted,lineHeightMultiplier=19f/13)
        }
        val inset=if(t.id=="magazine")Modifier.drawBehind{drawLine(t.line,Offset.Zero,Offset(0f,size.height),1.dp.toPx())}.padding(start=18.dp,end=16.dp,bottom=12.dp) else Modifier.padding(start=if(t.id=="objects")16.dp else 35.dp,end=16.dp,bottom=12.dp)
        Column(Modifier.fillMaxWidth().padding(start=if(t.id=="magazine")22.dp else 0.dp,top=if(t.id=="objects"&&children.isNotEmpty())7.dp else 0.dp).testTag("category-manage-children-$id").then(inset)){
            children.forEach{(id,label)->
                Column(Modifier.fillMaxWidth().padding(bottom=if(t.id=="objects")7.dp else 0.dp).testTag("category-manage-child-$id").clickable(role=Role.Button){onChild(id)}){
                    Row(Modifier.fillMaxWidth().then(if(t.id=="objects")Modifier.background(Color(0xfffff7e8),RoundedCornerShape(9.dp)) else Modifier).padding(horizontal=if(t.id=="objects")12.dp else 0.dp,vertical=if(t.id=="objects")13.dp else 14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
                        YText("↳ $label",if(t.id=="journal")17 else 12,Modifier.weight(1f),lineHeightMultiplier=if(t.id=="journal")25f/17 else 17f/12,cssLineBox=true)
                        YText("↗",12,color=t.muted,number=true,lineHeightMultiplier=17f/12,cssLineBox=true)
                    }
                    if(t.id=="journal")CategoryRule(t.line,true)
                }
            }
            CategoryAddChild(onAdd,"category-manage-add-$id")
        }
    }
}

@Composable private fun CategoryRule(color:Color,dashed:Boolean) {
    Canvas(Modifier.fillMaxWidth().height(1.dp)){drawLine(color,Offset.Zero,Offset(size.width,0f),1.dp.toPx(),pathEffect=if(dashed)PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())) else null)}
}

@Composable private fun CategoryAddChild(onClick:()->Unit,tag:String="category-add-child"){
    val t=LocalYohaku.current
    val size=if(t.id=="journal")15 else 11
    YText(word("＋ 添加二级分类","＋ 子カテゴリを追加"),size,Modifier.fillMaxWidth().testTag(tag).clickable(role=Role.Button,onClick=onClick).padding(horizontal=10.dp,vertical=14.dp),color=t.accent,lineHeightMultiplier=(if(t.id=="journal")21f else 16f)/size,cssLineBox=true)
}
