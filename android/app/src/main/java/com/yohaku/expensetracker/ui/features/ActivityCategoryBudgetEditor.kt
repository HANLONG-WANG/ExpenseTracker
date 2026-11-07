package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.yohaku.expensetracker.data.db.CategoryEntity
import com.yohaku.expensetracker.data.repository.BookSnapshot
import com.yohaku.expensetracker.data.repository.categoryEnabled
import com.yohaku.expensetracker.domain.budget.*
import com.yohaku.expensetracker.domain.category.resolveCategoryIcon
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import kotlin.math.abs

@Composable internal fun ActivityCategoryBudgetEditor(
    s:BookSnapshot,values:Map<String,String>,onValues:(Map<String,String>)->Unit,total:String,
    pickerOpen:Boolean,onPicker:(Boolean)->Unit,focusCategory:String?,onFocusCategory:(String?)->Unit
) {
    val t=LocalYohaku.current
    val journal=t.id=="journal";val objects=t.id=="objects"
    val currency=Currency.valueOf(s.book.currency)
    val ordered=orderedActivityCategories(s.categories)
    val available=ordered.filter{(s.categoryEnabled(it.id)||it.id in values)&&it.kind==EntryKind.EXPENSE.name}
    val rows=ordered.filter{it.id in values}
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    val pickerRequester=remember{BringIntoViewRequester()}
    LaunchedEffect(pickerOpen){if(pickerOpen){withFrameNanos{};pickerRequester.bringIntoView()}}
    Column(Modifier.fillMaxWidth().testTag("activity-category-section").then(if(t.id=="magazine")Modifier.drawBehind{drawLine(t.ink,Offset.Zero,Offset(size.width,0f),1.dp.toPx())}.padding(top=12.dp)else Modifier),verticalArrangement=Arrangement.spacedBy(if(objects)8.dp else 4.dp)) {
        if(t.id=="magazine")YText(word("CATEGORY LIMITS · OPTIONAL","CATEGORY LIMITS · OPTIONAL"),8,color=t.muted,letterSpacing=1.9f)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            YText((if(journal)"✎ "else "")+word("活动内分类预算","シーン内のカテゴリ予算"),if(journal)19 else if(objects)14 else 17,Modifier.weight(1f),color=if(journal)t.warm else t.ink)
            YText(if(rows.isEmpty())word("可选","任意")else word("可选 · ${rows.size} 项","任意 · ${rows.size} 件"),if(journal)12 else if(objects)10 else 9,
                if(objects)Modifier.background(t.soft,RoundedCornerShape(8.dp)).padding(horizontal=8.dp,vertical=3.dp)else Modifier,color=if(t.id=="magazine")t.warm else t.muted)
        }
        if(rows.isEmpty()&&!pickerOpen)YText(word("只给想单独控制的分类设额度。没加的分类照常计入总预算。","個別に管理したいカテゴリだけ予算を設定。追加しないカテゴリも総予算に含まれます。"),if(journal)13 else if(objects)12 else 11,
            if(objects)Modifier.background(Color(0xfff7efdf),RoundedCornerShape(14.dp)).dashedBorder(t.line,14.dp).padding(14.dp)else Modifier.padding(vertical=8.dp),color=t.muted,lineHeightMultiplier=if(journal)2.3f else 1.8f)
        if(rows.isNotEmpty())Column(Modifier.fillMaxWidth().then(if(journal)Modifier.drawBehind{drawLine(t.line,Offset(2.dp.toPx(),0f),Offset(2.dp.toPx(),size.height),1.dp.toPx(),pathEffect=dashes())}.padding(start=12.dp)else Modifier),verticalArrangement=Arrangement.spacedBy(if(objects)10.dp else 0.dp)) {
            rows.forEachIndexed{index,c->key(c.id){
                ActivityCategoryAmountRow(c,s.categories,index,values.getValue(c.id),{onValues(values+(c.id to it))},{onValues(values-c.id);if(focusCategory==c.id)onFocusCategory(null)},currency,focusCategory==c.id,{onFocusCategory(null)})
            }}
        }
        rows.filter{it.parentId==null}.forEach{parent->
            val children=rows.filter{it.parentId==parent.id}
            if(children.isNotEmpty()) {
                val names=children.joinToString(""){"「${it.name}」"}
                YText(word("「${parent.name}」已包含$names，二级是其中的专项额度，合计时不重复相加。","「${parent.name}」には$names が含まれます。子カテゴリはその内訳の予算で、合計に二重加算しません。"),if(journal)12 else 10,color=t.muted)
            }
        }
        ActivityCategoryAddButton(pickerOpen,{
            if(!pickerOpen){focus.clearFocus();keyboard?.hide()}
            onPicker(!pickerOpen)
        },Modifier.padding(top=if(objects)4.dp else 12.dp).testTag("activity-category-add"))
        if(pickerOpen)ActivityCategoryPicker(s.categories,available,values.keys,{id->onValues(values+(id to ""));onPicker(false);onFocusCategory(id)},{onPicker(false)},pickerRequester)
        if(rows.isNotEmpty())ActivityCategorySummary(s.categories,values,total,currency)
    }
}

@Composable internal fun ActivityCategoryAddButton(open:Boolean,onClick:()->Unit,modifier:Modifier=Modifier) {
    val t=LocalYohaku.current
    val journal=t.id=="journal";val objects=t.id=="objects"
    val shape=RoundedCornerShape(if(objects)12.dp else if(journal)3.dp else 0.dp)
    val frame=when(t.id){
        "magazine"->Modifier.background(if(open)t.ink else Color.Transparent).border(1.dp,t.ink)
        "journal"->Modifier.rotate(-.3f).background(if(open)Color(0x88dae1d3)else Color.Transparent,shape).then(if(open)Modifier.border(1.dp,t.accent,shape)else Modifier.dashedBorder(t.accent,3.dp))
        else->Modifier.background(if(open)Color(0xfff0e2ca)else Color(0xfffff9eb),shape).border(1.dp,Color(0xffdcc4a0),shape)
    }
    val state=if(open)word("已展开","展開中")else word("已收起","折りたたみ")
    Box(modifier.fillMaxWidth().then(frame).semantics{stateDescription=state}.clickable(role=Role.Button,onClick=onClick).padding(vertical=12.dp,horizontal=8.dp),contentAlignment=Alignment.Center) {
        YText(if(open)word("收起","閉じる")else word("＋ 添加分类预算","＋ カテゴリ予算を追加"),if(journal)14 else if(objects)12 else 10,color=if(open&&t.id=="magazine")t.surface else t.accent,letterSpacing=if(t.id=="magazine")1f else 0f)
    }
}

@Composable private fun ActivityCategoryAmountRow(c:CategoryEntity,categories:List<CategoryEntity>,index:Int,value:String,onValue:(String)->Unit,onRemove:()->Unit,currency:Currency,requestFocus:Boolean,onFocused:()->Unit) {
    val t=LocalYohaku.current
    val journal=t.id=="journal";val objects=t.id=="objects"
    val requester=remember{FocusRequester()}
    val viewRequester=remember{BringIntoViewRequester()}
    var focused by remember{mutableStateOf(false)}
    val imeBottom=WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(requestFocus){if(requestFocus){withFrameNanos{};requester.requestFocus();onFocused()}}
    LaunchedEffect(focused,imeBottom){if(focused){withFrameNanos{};viewRequester.bringIntoView()}}
    val frame=if(objects)Modifier.shadow(1.dp,RoundedCornerShape(15.dp)).background(t.surface,RoundedCornerShape(15.dp)).border(1.dp,Color(0xfff1e4cf),RoundedCornerShape(15.dp)).padding(start=10.dp,end=6.dp,top=9.dp,bottom=9.dp)
        else Modifier.bottomRule(t.line,journal).padding(vertical=if(journal)6.dp else 11.dp)
    Row(Modifier.fillMaxWidth().testTag("activity-category-row-${c.id}").bringIntoViewRequester(viewRequester).then(frame),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(if(objects)8.dp else 9.dp)) {
        if(t.id=="magazine")YText((index+1).toString().padStart(2,'0'),10,Modifier.width(16.dp),color=t.warm,number=true)
        else CategoryIconBadge(resolveCategoryIcon(c.id,categories),CategoryBadge.SMALL)
        val parent=categories.find{it.id==c.parentId}
        val name=buildAnnotatedString {
            if(parent!=null)withStyle(SpanStyle(color=t.muted,fontSize=(if(journal)12 else if(objects)9 else 10).sp)){append(parent.name+if(objects)" /\n"else " / ")}
            append(c.name)
        }
        Text(name,Modifier.weight(1f),color=t.ink,fontFamily=t.body,fontSize=(if(journal)16 else 13).sp,maxLines=if(objects)2 else 1,overflow=TextOverflow.Ellipsis)
        val amountFrame=if(objects)Modifier.background(Color(0xfff7efdf),RoundedCornerShape(11.dp)).border(1.dp,Color(0xffe7d7bc),RoundedCornerShape(11.dp)).padding(horizontal=8.dp,vertical=7.dp)
            else Modifier.bottomRule(if(journal)t.accent else t.ink).padding(bottom=2.dp)
        Row(Modifier.then(amountFrame),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(2.dp)) {
            YText(currency.symbol,if(journal)14 else 11,color=t.muted,number=true)
            val label=c.name+word("预算","予算")
            BasicTextField(value,onValue,Modifier.width(if(objects)68.dp else 80.dp).heightIn(min=30.dp).wrapContentHeight().testTag("activity-category-amount-${c.id}").semantics{contentDescription=label}.focusRequester(requester).onFocusChanged{focused=it.isFocused},
                singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),cursorBrush=SolidColor(t.accent),
                textStyle=TextStyle(color=if(journal)t.accent else t.ink,fontFamily=t.number,fontSize=(if(journal)20 else if(objects)16 else 18).sp,textAlign=TextAlign.End),
                decorationBox={inner->Box(contentAlignment=Alignment.CenterEnd){if(value.isEmpty())YText("0",if(journal)20 else if(objects)16 else 18,color=t.muted,number=true);inner()}})
        }
        val removeLabel=word("移除${c.name}预算","${c.name}の予算を削除")
        Box(Modifier.size(32.dp).testTag("activity-category-remove-${c.id}").semantics{contentDescription=removeLabel}.clickable(role=Role.Button,onClick=onRemove),contentAlignment=Alignment.Center) {
            Box(if(objects)Modifier.size(26.dp).background(Color(0xfff0e2ca),RoundedCornerShape(9.dp))else Modifier,contentAlignment=Alignment.Center){YText("×",if(journal)17 else 15,color=if(journal)t.warm else t.muted)}
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ActivityCategoryPicker(categories:List<CategoryEntity>,available:List<CategoryEntity>,used:Set<String>,onPick:(String)->Unit,onClose:()->Unit,requester:BringIntoViewRequester) {
    val t=LocalYohaku.current
    val journal=t.id=="journal";val objects=t.id=="objects"
    val frame=when(t.id){
        "magazine"->Modifier.background(t.surface).border(1.dp,t.ink).padding(horizontal=14.dp,vertical=4.dp)
        "journal"->Modifier.padding(top=14.dp).rotate(.4f).shadow(3.dp,clip=false).background(Color(0xfffffbef)).drawBehind{
            val left=(size.width-64.dp.toPx())/2
            drawRect(Color(0xd9decb96),Offset(left,-8.dp.toPx()),androidx.compose.ui.geometry.Size(64.dp.toPx(),17.dp.toPx()))
            for(i in 0..6)drawLine(Color(0x40ffffff),Offset(left+i*9.dp.toPx(),-8.dp.toPx()),Offset(left+i*9.dp.toPx(),9.dp.toPx()),4.dp.toPx())
        }.padding(start=14.dp,end=14.dp,top=18.dp,bottom=12.dp)
        else->Modifier.background(Color(0xfff3e7d2),RoundedCornerShape(18.dp)).padding(12.dp)
    }
    Column(Modifier.fillMaxWidth().testTag("activity-category-picker").then(frame),verticalArrangement=Arrangement.spacedBy(if(objects)10.dp else 4.dp)) {
        // Bring the menu heading into view even when the complete menu exceeds the sheet viewport.
        Row(Modifier.fillMaxWidth().bringIntoViewRequester(requester).then(if(objects)Modifier else Modifier.bottomRule(t.line,journal)),verticalAlignment=Alignment.CenterVertically) {
            YText(word("选一个分类，再填金额","カテゴリを選んで金額を入力"),if(journal)13 else if(objects)11 else 10,Modifier.weight(1f),color=if(journal)t.warm else t.muted)
            YLink(word("收起","閉じる"),onClose,Modifier.testTag("activity-category-picker-close"))
        }
        categories.filter{it.parentId==null}.forEach{parent->
            val children=available.filter{it.parentId==parent.id}
            if(parent in available||children.isNotEmpty())Column(Modifier.fillMaxWidth().then(if(objects)Modifier.background(t.surface,RoundedCornerShape(14.dp)).padding(8.dp)else Modifier.bottomRule(t.line,journal).padding(vertical=4.dp))) {
                if(parent in available)ActivityCategoryOption(parent,categories,parent.id in used,false,onPick)
                else YText(parent.name,if(journal)16 else 13,Modifier.padding(vertical=8.dp),color=t.muted)
                if(children.isNotEmpty()) {
                    if(t.id=="magazine")Column(Modifier.padding(start=30.dp)) {
                        children.chunked(2).forEach{pair->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            pair.forEach{child->ActivityCategoryOption(child,categories,child.id in used,true,onPick,Modifier.weight(1f))}
                            if(pair.size==1)Spacer(Modifier.weight(1f))
                        }}
                    }else FlowRow(Modifier.padding(start=if(journal)22.dp else 4.dp,top=4.dp,bottom=2.dp),horizontalArrangement=Arrangement.spacedBy(if(journal)16.dp else 6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        children.forEach{child->ActivityCategoryOption(child,categories,child.id in used,true,onPick)}
                    }
                }
            }
        }
        YText(word("一级预算包含二级和未细分的消费；二级是其中的专项，不重复相加。","親予算は子カテゴリと内訳未分類の支出を含みます。子予算はその内訳で、二重加算しません。"),if(journal)12 else 10,Modifier.padding(top=6.dp,bottom=8.dp),color=t.muted,lineHeightMultiplier=1.8f)
    }
}

@Composable private fun ActivityCategoryOption(c:CategoryEntity,categories:List<CategoryEntity>,disabled:Boolean,child:Boolean,onPick:(String)->Unit,modifier:Modifier=Modifier) {
    val t=LocalYohaku.current
    val journal=t.id=="journal";val objects=t.id=="objects"
    val added=word("已添加","選択済み")
    val chip=if(objects&&child)Modifier.background(Color(0xfff7efdf),RoundedCornerShape(9.dp)).border(1.dp,Color(0xffe7d7bc),RoundedCornerShape(9.dp))else Modifier
    Row(modifier.then(if(!child)Modifier.fillMaxWidth()else Modifier).alpha(if(disabled&&objects).45f else 1f).then(chip).testTag("activity-category-option-${c.id}").semantics{if(disabled)stateDescription=added}.clickable(enabled=!disabled,role=Role.Button){onPick(c.id)}.padding(horizontal=if(objects)if(child)9.dp else 4.dp else 0.dp,vertical=if(child)6.dp else 8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(if(child)5.dp else 8.dp)) {
        if(journal)Box(Modifier.size(15.dp).rotate(-4f).background(if(disabled)Color(0xffdae1d3)else Color.Transparent,RoundedCornerShape(2.dp)).border(1.dp,if(disabled)t.muted else t.accent,RoundedCornerShape(2.dp)),contentAlignment=Alignment.Center){if(disabled)YText("✓",12,color=t.muted,lineHeightMultiplier=1f)}
        else if(!child)CategoryIconBadge(resolveCategoryIcon(c.id,categories),CategoryBadge.SMALL)
        YText(c.name,if(journal)if(child)14 else 16 else if(child)if(objects)11 else 12 else 13,if(!child||t.id=="magazine")Modifier.weight(1f)else Modifier,color=if(disabled)t.muted else t.ink)
        if(disabled)YText(added,if(journal)11 else 9,color=t.warm)
    }
}

@Composable private fun ActivityCategorySummary(categories:List<CategoryEntity>,values:Map<String,String>,total:String,currency:Currency) {
    val t=LocalYohaku.current
    val journal=t.id=="journal";val objects=t.id=="objects"
    // Drafts may be incomplete while typing. Only the existing preview path reports parse errors.
    val parsed=runCatching{values.filterValues{it.isNotBlank()}.mapValues{Money.parse(it.value,currency).minor}}.getOrNull()
    val limit=total.takeIf{it.isNotBlank()}?.let{runCatching{Money.parse(it,currency).minor}.getOrNull()}
    val sum=parsed?.let{activityCategoryBudgetTotal(categories,it)}
    val over=sum!=null&&limit!=null&&sum>limit
    val ink=if(over)t.warm else t.ink
    val frame=when(t.id){
        "magazine"->Modifier.drawBehind{drawLine(ink,Offset.Zero,Offset(size.width,0f),1.5.dp.toPx())}.padding(top=12.dp)
        "journal"->Modifier
        else->Modifier.drawBehind{
            val edge=Path().apply{moveTo(0f,0f);lineTo(size.width,0f);lineTo(size.width,size.height-5.dp.toPx());var x=size.width;while(x>0){lineTo(x-3.dp.toPx(),size.height);lineTo(x-6.dp.toPx(),size.height-5.dp.toPx());x-=6.dp.toPx()};lineTo(0f,0f);close()}
            drawPath(edge,Color(0xfffffefa))
        }.padding(start=16.dp,end=16.dp,top=14.dp,bottom=20.dp)
    }
    Column(Modifier.fillMaxWidth().padding(top=14.dp).testTag("activity-category-summary").then(frame),verticalArrangement=Arrangement.spacedBy(if(journal)2.dp else 7.dp)) {
        if(objects)YText(word("ALLOCATION","ALLOCATION"),8,Modifier.fillMaxWidth().bottomRule(t.line,true).padding(bottom=8.dp),color=if(over)t.warm else t.muted,letterSpacing=1.9f)
        @Composable fun line(label:String,value:Long?,rest:Boolean=false) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                YText(label,if(journal)14 else 11,Modifier.weight(1f),color=if(over||rest&&journal)t.warm else t.muted)
                YText(value?.let{Money(currency,it).display()}?:"—",if(rest)if(journal)22 else if(objects)19 else 20 else if(journal)17 else if(objects)14 else 15,number=true,color=if(rest&&journal)t.warm else ink)
            }
        }
        line(word("分类合计","カテゴリ合計"),sum)
        if(total.isBlank())YText(word("未设总预算，每个分类额度各自生效。","総予算は未設定です。各カテゴリ予算が個別に適用されます。"),if(journal)12 else 10,color=t.muted)
        else {
            line(word("总预算","総予算"),limit)
            val ratio=if(sum==null||limit==null)0f else if(limit==0L)if(sum>0)1f else 0f else (sum.toDouble()/limit).toFloat().coerceIn(0f,1f)
            Canvas(Modifier.fillMaxWidth().padding(vertical=4.dp).height(if(journal||objects)6.dp else 3.dp)) {
                val color=if(over)t.warm else t.accent
                if(journal) {
                    val wave=Path().apply{moveTo(0f,size.height/2);var x=0f;while(x<size.width){quadraticTo(x+2.5.dp.toPx(),-2.dp.toPx(),x+5.dp.toPx(),size.height/2);quadraticTo(x+7.5.dp.toPx(),size.height+2.dp.toPx(),x+10.dp.toPx(),size.height/2);x+=10.dp.toPx()}}
                    drawPath(wave,t.line,style=Stroke(1.dp.toPx()));clipRect(right=size.width*ratio){drawPath(wave,color,style=Stroke(1.5.dp.toPx()))}
                }else {
                    drawLine(t.line,Offset(0f,size.height/2),Offset(size.width,size.height/2),if(objects)size.height else 1.dp.toPx(),cap=if(objects)StrokeCap.Round else StrokeCap.Butt)
                    if(ratio>0)drawLine(color,Offset(0f,size.height/2),Offset(size.width*ratio,size.height/2),size.height,cap=if(objects)StrokeCap.Round else StrokeCap.Butt)
                }
            }
            line(if(over)word("超出总预算","総予算を超過")else word("未分到分类","カテゴリ未配分"),if(sum!=null&&limit!=null)abs(limit-sum)else null,true)
            if(over)YText(word("仍可保存，分类额度各自生效。也可以调高总预算。","このまま保存でき、各カテゴリ予算が個別に適用されます。総予算を増やすこともできます。"),if(journal)12 else 10,color=t.warm)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.dashes()=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))
private fun Modifier.bottomRule(color:Color,dashed:Boolean=false)=drawBehind{drawLine(color,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=if(dashed)dashes()else null)}
private fun Modifier.dashedBorder(color:Color,radius:Dp)=drawBehind{drawRoundRect(color,cornerRadius=androidx.compose.ui.geometry.CornerRadius(radius.toPx()),style=Stroke(1.dp.toPx(),pathEffect=dashes()))}
