package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.domain.category.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import kotlinx.coroutines.launch

@Composable private fun iconLabel(id:String)=categoryIconOf(id)?.let{if(LocalLanguage.current=="ja")it.ja else it.zh}.orEmpty()

/** Editor row: the icon in use, why it is in use, and name-based alternatives one tap away. */
@Composable internal fun CategoryIconField(shown:String,note:String,suggestions:List<String>,onOpen:()->Unit,onPick:(String)->Unit) {
    val t=LocalYohaku.current
    val journal=t.id=="journal";val objects=t.id=="objects"
    Column(Modifier.fillMaxWidth().testTag("category-icon-field")) {
        FieldLabel(word("分类图标","カテゴリのアイコン"),Modifier.padding(bottom=if(journal)6.dp else if(objects)9.dp else 10.dp))
        val shape=RoundedCornerShape(14.dp)
        val frame=when(t.id){
            "journal"->Modifier.background(Color(0xfffff8e4)).drawBehind{drawRect(t.line,style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))))}.padding(12.dp)
            "objects"->Modifier.drawBehind{drawRoundRect(Color(0xffece0c9),Offset(0f,3.dp.toPx()),cornerRadius=CornerRadius(14.dp.toPx()))}.background(Color(0xfffffaf0),shape).border(1.dp,Color(0xffead8b8),shape).padding(12.dp)
            else->Modifier.drawBehind{drawLine(t.ink,Offset.Zero,Offset(size.width,0f),1.dp.toPx());drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}.padding(vertical=12.dp)
        }
        Row(Modifier.fillMaxWidth().testTag("category-icon-open").clickable(role=Role.Button,onClick=onOpen).then(frame),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            CategoryIconBadge(shown,CategoryBadge.FIELD)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                YText(iconLabel(shown),if(journal)19 else 15,Modifier.testTag("category-icon-name"),weight=FontWeight.Medium,lineHeightMultiplier=1.4f)
                YText(note,if(journal)12 else 10,Modifier.testTag("category-icon-note"),color=t.muted,lineHeightMultiplier=1.5f)
            }
            YText(word("更换","変更")+" ↗",if(journal)14 else 11,color=if(journal)t.warm else t.accent)
        }
        if(suggestions.isNotEmpty())Row(Modifier.fillMaxWidth().padding(top=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            YText(word("按名称推荐","名前からおすすめ"),if(journal)13 else 10,color=t.muted)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){suggestions.forEach{id->
                val chip=when(t.id){"magazine"->Modifier.border(1.dp,t.line).padding(7.dp);"objects"->Modifier.background(Color(0xfffff6e4),RoundedCornerShape(12.dp)).padding(5.dp);else->Modifier.padding(2.dp)}
                val label=iconLabel(id)
                Box(Modifier.testTag("category-icon-suggest-$id").semantics{contentDescription=label}.clickable(role=Role.Button){onPick(id)}.then(chip)){CategoryIconBadge(id)}
            }}
        }
    }
}

/** Icon catalog sheet: one group per tab, search across all groups, the pick previewed at the top. */
@Composable internal fun CategoryIconSheet(categoryName:String,initial:String,parentName:String?,onUse:(String)->Unit,onInherit:()->Unit,onBack:()->Unit) {
    val t=LocalYohaku.current
    val journal=t.id=="journal";val objects=t.id=="objects"
    val narrow=LocalConfiguration.current.screenWidthDp<=360
    var pending by remember{mutableStateOf(initial)}
    var query by remember{mutableStateOf("")}
    val tips=remember(categoryName){suggestCategoryIcons(categoryName)}
    val tabs=remember(tips){(if(tips.isEmpty())emptyList() else listOf(Triple("tips","推荐" to "おすすめ",tips)))+categoryIconGroups.map{g->Triple(g.id,g.zh to g.ja,g.icons.map{it.id})}}
    var active by remember{mutableStateOf(categoryIconGroupOf(initial)?.id ?: tabs.first().first)}
    val scroll=rememberScrollState()
    val scope=rememberCoroutineScope()
    YSheet(word("选择分类图标","アイコンを選ぶ"),onBack,scrollState=scroll) {
        val viewport=LocalSheetViewport.current
        CategoryIconPreview(pending,categoryName)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            YLink("← "+word("返回编辑","編集に戻る"),onBack,Modifier.testTag("category-icon-back"))
            if(parentName!=null)YLink(word("沿用一级「$parentName」","親「$parentName」と同じ"),onInherit,Modifier.testTag("category-icon-inherit"))
        }
        YSearch(word("搜索图标","アイコンを検索"),query,{query=it},category=true,displayPlaceholder=word("搜索图标：咖啡、電車、gift…","検索：カフェ、電車、gift…"))
        val results=searchCategoryIcons(query)
        val pickedTab=categoryIconGroupOf(pending)?.id
        if(results==null) {
            var tabsY by remember{mutableFloatStateOf(0f)}
            CategoryIconTabs(tabs.map{(id,label,icons)->Triple(id,if(LocalLanguage.current=="ja")label.second else label.first,id==pickedTab||id=="tips"&&pending in icons)},active,
                Modifier.onGloballyPositioned{if(viewport!=null)tabsY=it.positionInRoot().y+viewport.scroll.value}.sheetSticky(top=true)) {id->
                active=id
                // Once the tab bar is pinned, a new tab starts at the top of its own grid.
                if(viewport!=null){val pinned=tabsY-viewport.top;if(scroll.value>pinned)scope.launch{scroll.scrollTo(pinned.toInt())}}
            }
            val (id,label,icons)=tabs.first{it.first==active}
            CategoryIconCaption(if(id=="tips")word("按「$categoryName」推荐","「$categoryName」のおすすめ") else if(LocalLanguage.current=="ja")label.second else label.first,icons.size)
            CategoryIconGrid(icons,pending,narrow){pending=it}
        } else {
            CategoryIconCaption(word("搜索结果","検索結果"),results.size)
            if(results.isEmpty())YText(word("没有匹配的图标","該当するアイコンがありません"),12,Modifier.testTag("category-icon-no-results"),color=t.muted)
            else CategoryIconGrid(results,pending,narrow){pending=it}
        }
        val sheetPaper=if(journal)Color(0xfffaf4e4) else if(objects)Color(0xfffff9ed) else t.bg
        // The paper runs to the sheet edges so selection marks that overhang a cell stay covered.
        Box(Modifier.fillMaxWidth().sheetSticky(top=false).drawBehind{drawRect(sheetPaper,Offset(-32.dp.toPx(),0f),Size(size.width+64.dp.toPx(),size.height))}.padding(top=10.dp,bottom=6.dp)) {
            YButton(word("使用这个图标","このアイコンを使う"),{onUse(pending)},Modifier.testTag("category-icon-use"),primary=true)
        }
        YText("Icons · $CATEGORY_ICON_SOURCE · MIT",if(journal)11 else 9,color=t.muted)
    }
}

@Composable private fun CategoryIconPreview(icon:String,categoryName:String) {
    val t=LocalYohaku.current
    val journal=t.id=="journal"
    val frame=when(t.id){
        "journal"->Modifier.rotate(-.5f).background(Color(0xfffff8e4)).drawBehind{drawRect(t.line,style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))))}.padding(horizontal=14.dp,vertical=16.dp)
        "objects"->Modifier.drawBehind{drawRoundRect(Color(0xffdbd2bd),Offset(0f,3.dp.toPx()),cornerRadius=CornerRadius(18.dp.toPx()))}.background(Color(0xffece4ce),RoundedCornerShape(18.dp)).padding(16.dp)
        else->Modifier.drawBehind{drawLine(t.ink,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}.padding(top=6.dp,bottom=18.dp)
    }
    Row(Modifier.fillMaxWidth().testTag("category-icon-preview").then(frame),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(if(journal)18.dp else 16.dp)) {
        CategoryIconBadge(icon,CategoryBadge.HERO,Modifier.padding(if(journal)5.dp else 0.dp))
        Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
            YText(categoryName.ifBlank{word("新分类","新しいカテゴリ")},if(journal)12 else 10,color=t.muted)
            YText(iconLabel(icon),if(journal)22 else 19,Modifier.testTag("category-icon-preview-name"),weight=FontWeight.Medium,lineHeightMultiplier=1.35f)
            YText(word("显示于记账、流水、分析与分类管理","記録・明細・分析・管理に表示"),if(journal)12 else 10,color=t.muted)
        }
    }
}

/** Tabs scroll sideways; the tab that holds the current pick carries a dot. */
@Composable private fun CategoryIconTabs(tabs:List<Triple<String,String,Boolean>>,active:String,modifier:Modifier,onSelect:(String)->Unit) {
    val t=LocalYohaku.current
    val journal=t.id=="journal";val objects=t.id=="objects"
    val row=rememberScrollState()
    val centers=remember{mutableMapOf<String,Pair<Int,Int>>()}
    var width by remember{mutableIntStateOf(0)}
    LaunchedEffect(active,width){centers[active]?.let{(x,w)->row.animateScrollTo((x+w/2-width/2).coerceAtLeast(0))}}
    val background=if(journal)Color(0xfffaf4e4) else if(objects)Color(0xfffff9ed) else t.bg
    val bar=when(t.id){
        "magazine"->Modifier.drawBehind{drawLine(t.ink,Offset(0f,size.height-.5.dp.toPx()),Offset(size.width,size.height-.5.dp.toPx()),1.dp.toPx())}.padding(top=4.dp)
        "journal"->Modifier.drawBehind{drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())))}.padding(top=10.dp,bottom=4.dp)
        else->Modifier.padding(vertical=8.dp)
    }
    Row(modifier.fillMaxWidth().background(background).then(bar).onGloballyPositioned{width=it.size.width}.horizontalScroll(row).selectableGroup().testTag("category-icon-tabs"),
        horizontalArrangement=Arrangement.spacedBy(if(t.id=="magazine")18.dp else 8.dp),verticalAlignment=Alignment.Bottom) {
        tabs.forEachIndexed{index,(id,label,picked)->
            val selected=id==active
            val shape=if(objects)RoundedCornerShape(50) else RoundedCornerShape(0.dp)
            val surface=when(t.id){
                "journal"->Modifier.offset(y=if(selected)2.dp else 0.dp).rotate(if(selected)0f else if(index%2==0)-1f else 1f).background(if(selected)Color(0xfff6e0c4) else Color(0xfff6efd2)).drawBehind{
                    if(selected)drawLine(t.warm,Offset(0f,size.height-1.dp.toPx()),Offset(size.width,size.height-1.dp.toPx()),2.dp.toPx())
                    else drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())))
                }.padding(horizontal=10.dp,vertical=7.dp)
                "objects"->Modifier.then(if(selected)Modifier.drawBehind{drawRoundRect(Color(0xff8d4d36),Offset(0f,2.dp.toPx()),cornerRadius=CornerRadius(size.height/2))} else Modifier).background(if(selected)t.accent else Color(0xffede5d2),shape).padding(horizontal=13.dp,vertical=7.dp)
                else->Modifier.then(if(selected)Modifier.drawBehind{drawLine(t.ink,Offset(0f,size.height-1.dp.toPx()),Offset(size.width,size.height-1.dp.toPx()),2.dp.toPx())} else Modifier).padding(top=9.dp,bottom=10.dp)
            }
            val color=when{objects&&selected->Color.White;journal&&selected->t.warm;selected->t.ink;else->t.muted}
            Row(Modifier.testTag("category-icon-tab-$id").onGloballyPositioned{centers[id]=it.positionInParent().x.toInt() to it.size.width}.selectable(selected,role=Role.Tab){onSelect(id)}.then(surface),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                YText((if(id=="tips")"★ " else "")+label,if(journal)14 else 11,color=color,letterSpacing=if(t.id=="magazine").4f else 0f,lineHeightMultiplier=1.4f)
                if(picked)Box(Modifier.size(5.dp).clearAndSetSemantics{}.background(if(objects&&selected)Color.White else if(journal)t.warm else if(objects)t.accent else t.ink,if(t.id=="magazine")RoundedCornerShape(0.dp) else RoundedCornerShape(50)))
            }
        }
    }
}

@Composable private fun CategoryIconCaption(label:String,count:Int) {
    val t=LocalYohaku.current
    val journal=t.id=="journal";val objects=t.id=="objects"
    Row(Modifier.fillMaxWidth().padding(top=if(t.id=="magazine")6.dp else 0.dp),verticalAlignment=Alignment.CenterVertically) {
        YText(if(t.id=="magazine")label.uppercase() else label,if(journal)14 else if(objects)11 else 10,Modifier.weight(1f),color=if(journal)t.warm else t.muted,letterSpacing=if(t.id=="magazine")1.2f else 0f)
        YText(count.toString(),if(journal)13 else 10,if(objects)Modifier.background(Color(0xffede1cb),RoundedCornerShape(5.dp)).padding(horizontal=7.dp,vertical=2.dp) else Modifier,number=true,color=t.muted)
    }
}

@Composable private fun CategoryIconGrid(icons:List<String>,pending:String,narrow:Boolean,onPick:(String)->Unit) {
    val t=LocalYohaku.current
    val journal=t.id=="journal";val objects=t.id=="objects"
    val columns=if(objects)if(narrow)4 else 5 else 6
    val gap=if(objects)9.dp else if(journal)2.dp else 0.dp
    val sheet=when(t.id){
        "magazine"->Modifier.drawBehind{drawLine(t.line,Offset.Zero,Offset(size.width,0f),1.dp.toPx());drawLine(t.line,Offset.Zero,Offset(0f,size.height),1.dp.toPx())}
        // Dotted notebook paper behind the stamps.
        "journal"->Modifier.drawBehind{val step=14.dp.toPx();var y=step/2;while(y<size.height){var x=step/2;while(x<size.width){drawCircle(Color(0xffcfc5ad),1.dp.toPx(),Offset(x,y));x+=step};y+=step}}.padding(4.dp)
        else->Modifier
    }
    Column(Modifier.fillMaxWidth().testTag("category-icon-grid").then(sheet),verticalArrangement=Arrangement.spacedBy(gap)) {
        icons.chunked(columns).forEach{row->
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(gap)) {
                row.forEach{id->CategoryIconCell(id,id==pending,Modifier.weight(1f)){onPick(id)}}
                repeat(columns-row.size){Spacer(Modifier.weight(1f))}
            }
        }
    }
}

@Composable private fun CategoryIconCell(id:String,selected:Boolean,modifier:Modifier,onClick:()->Unit) {
    val t=LocalYohaku.current
    val label=iconLabel(id)
    val cell=when(t.id){
        "magazine"->Modifier.background(if(selected)t.ink else Color.Transparent).drawBehind{drawLine(t.line,Offset(size.width,0f),Offset(size.width,size.height),1.dp.toPx());drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}
        "objects"->Modifier.offset(y=if(selected)(-2).dp else 0.dp).drawBehind{drawRoundRect(Color(0xffeadfca),Offset(0f,3.dp.toPx()),cornerRadius=CornerRadius(14.dp.toPx()))}.background(Color(0xfffffaf0),RoundedCornerShape(14.dp)).then(if(selected)Modifier.border(2.dp,t.accent,RoundedCornerShape(14.dp)) else Modifier)
        else->Modifier
    }
    Box(modifier.aspectRatio(1f).testTag("category-icon-cell-$id").semantics{contentDescription=label}.selectable(selected,role=Role.RadioButton,onClick=onClick).then(cell),contentAlignment=Alignment.Center) {
        CategoryIconBadge(id,CategoryBadge.CELL,selected=selected)
        if(selected&&t.id=="journal")YText("✓",15,Modifier.align(Alignment.TopEnd).padding(end=2.dp).rotate(8f).clearAndSetSemantics{},color=t.warm,lineHeightMultiplier=1f)
        if(selected&&t.id=="objects")Box(Modifier.align(Alignment.TopEnd).offset(x=5.dp,y=(-5).dp).size(17.dp).background(t.accent,RoundedCornerShape(50)).clearAndSetSemantics{},contentAlignment=Alignment.Center){YText("✓",10,color=Color.White,lineHeightMultiplier=1f)}
    }
}
