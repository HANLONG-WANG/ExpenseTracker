package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import com.yohaku.expensetracker.ui.theme.*
import com.yohaku.expensetracker.ui.features.ThemeSection

@Composable internal fun BudgetEquation(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) {
    val t=LocalYohaku.current
    val frame=when(t.id) {
        // The facts close with their own sum line, so magazine no longer boxes the table.
        "magazine"->Modifier
        "journal"->Modifier.rotate(-.3f).background(Color(0xfffff9e9)).drawBehind{var y=size.height-30.dp.toPx();while(y>0){drawLine(Color(0x13718695),Offset(0f,y),Offset(size.width,y),1.dp.toPx());y-=30.dp.toPx()};drawRect(t.line,style=androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))))}.padding(17.dp)
        else->Modifier.drawBehind{drawRoundRect(Color(0x24b9996f),Offset(0f,3.dp.toPx()),cornerRadius=androidx.compose.ui.geometry.CornerRadius(17.dp.toPx()))}.background(Color(0xfffff8e8),RoundedCornerShape(17.dp)).border(1.dp,Color(0xffe3cda8),RoundedCornerShape(17.dp)).padding(horizontal=15.dp,vertical=19.dp)
    }
    Column(modifier.fillMaxWidth().testTag("budget-equation").then(frame),content=content)
}

@Composable internal fun IndependentBudgetNote(amount:String) {
    val t=LocalYohaku.current
    val frame=when(t.id) {
        "magazine"->Modifier.background(t.soft).drawBehind{drawLine(t.accent,Offset.Zero,Offset(0f,size.height),2.dp.toPx())}
        "journal"->Modifier.rotate(.4f).background(Color(0xffe8ebdd)).drawBehind{drawRect(t.line,style=androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))))}
        else->Modifier.background(Color(0xffe6e9d8),RoundedCornerShape(16.dp))
    }
    Box(Modifier.fillMaxWidth().padding(top=8.dp,bottom=8.dp)) {
    Column(Modifier.fillMaxWidth().testTag("independent-budget-note").then(frame).padding(start=if(t.id=="magazine")20.dp else if(t.id=="journal")19.dp else 18.dp,end=if(t.id=="journal")19.dp else 18.dp,top=if(t.id=="journal")19.dp else 18.dp,bottom=if(t.id=="journal")19.dp else 18.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            YText(word("不计入预算的支出","予算に含めない支出"),if(t.id=="journal")15 else 12,Modifier.weight(1f),weight=if(t.id=="journal")FontWeight.Normal else FontWeight.Medium)
            YText(amount,22,number=true)
        }
        YText(word("这部分仍是真实消费，但不占用月总预算或月分类预算。","実際の支出ですが、月全体・月カテゴリの予算に含めません。"),if(t.id=="journal")12 else 10,Modifier.padding(top=13.dp,bottom=5.dp),color=t.muted,lineHeightMultiplier=1.9f)
    }
    }
}

@Composable internal fun <T> BudgetLimitList(items:List<T>,amounts:List<String>,content:@Composable (T,Modifier)->Unit) {
    val t=LocalYohaku.current
    val measurer=rememberTextMeasurer()
    val density=LocalDensity.current
    val amountWidth=amounts.maxOfOrNull{measurer.measure(it,TextStyle(fontFamily=t.number,fontSize=23.sp,fontWeight=FontWeight.Medium),softWrap=false).size.width}?:0
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical=2.dp).testTag("budget-category-list")) {
        val twoColumns=t.id=="objects"&&with(density){((maxWidth-14.dp)/2-28.dp).toPx()}>=amountWidth
        if(twoColumns)Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
            items.chunked(2).forEach{pair->Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                pair.forEach{content(it,Modifier.weight(1f).fillMaxHeight())}
                if(pair.size==1)Spacer(Modifier.weight(1f))
            }}
        }else Column(Modifier.fillMaxWidth().then(if(t.id=="journal")Modifier.drawBehind{drawLine(Color(0x46bc8474),Offset.Zero,Offset(0f,size.height),1.dp.toPx())}.padding(start=9.dp)else Modifier),verticalArrangement=Arrangement.spacedBy(if(t.id=="journal")0.dp else if(t.id=="objects")14.dp else 13.dp)) {
            items.forEach{content(it,Modifier.fillMaxWidth())}
        }
    }
}

/** Main figure is what is left when a limit exists, otherwise what was spent; the caption names both. */
@Composable internal fun BudgetLimitRow(id:String,name:String,icon:String,child:Boolean,spent:Long,limit:Long?,spentText:String,limitText:String,remaining:String,onEdit:(()->Unit)?,modifier:Modifier=Modifier) {
    val t=LocalYohaku.current
    val narrow=LocalConfiguration.current.screenWidthDp<=360
    val objects=t.id=="objects"
    val over=limit!=null&&spent>limit
    val mainLabel=when{limit==null->word("已花","支出");over->word("已超出","超過");else->word("还剩","残り")}
    val main=if(limit==null)spentText else remaining.removePrefix("−").removePrefix("-")
    val caption=if(limit==null)word("未设分类预算","カテゴリ予算なし")else word("已花 ","支出 ")+spentText+" / "+word("预算 ","予算 ")+limitText
    val mainColor=if(over)t.warm else if(limit==null)t.muted else t.ink
    val frame=if(objects)Modifier.drawBehind{drawRoundRect(Color(0x20d4c3a8),Offset(0f,4.dp.toPx()),cornerRadius=androidx.compose.ui.geometry.CornerRadius(17.dp.toPx()))}.background(t.surface,RoundedCornerShape(17.dp)).padding(horizontal=14.dp,vertical=16.dp)
        // Magazine rows are already separated by their progress track; a hairline under it would read as a second bar.
        else if(t.id=="magazine")Modifier.padding(vertical=18.dp)
        else Modifier.drawBehind{drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx())))}.padding(vertical=16.dp)
    Column(modifier.testTag("budget-category-$id").then(if(onEdit!=null)Modifier.clickable(onClick=onEdit)else Modifier).then(frame),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(objects) {
            Box(Modifier.fillMaxWidth()) {
                CategoryIconBadge(icon,CategoryBadge.SMALL,Modifier.align(Alignment.TopEnd).offset(x=1.dp,y=(-4).dp))
                Column(Modifier.fillMaxWidth().padding(top=25.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    YText((if(child)"↳ "else "")+name,11,weight=FontWeight.Medium)
                    Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                        YText(mainLabel,9,Modifier.alignByBaseline(),color=t.muted)
                        YText(main,if(limit==null)18 else 23,Modifier.alignByBaseline(),number=true,weight=FontWeight.Medium,color=mainColor)
                    }
                    YText(caption,9,color=t.muted,lineHeightMultiplier=1.6f)
                    limit?.let{BudgetTrack(spent,it,modifier=Modifier.padding(top=5.dp),height=6)}
                }
            }
        }else {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(if(narrow)6.dp else 8.dp),verticalAlignment=Alignment.CenterVertically) {
                // Children step in under their parent instead of the old ↳ prefix.
                Box(Modifier.padding(start=if(child)14.dp else 0.dp).width(if(t.id=="magazine")28.dp else 36.dp),contentAlignment=Alignment.CenterStart){CategoryIconBadge(icon,CategoryBadge.SMALL)}
                YText(name,if(t.id=="journal")15 else 12,Modifier.weight(1f),weight=if(t.id=="journal")FontWeight.Normal else FontWeight.Medium)
                Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                    YText(mainLabel,if(t.id=="journal")11 else 9,Modifier.alignByBaseline(),color=t.muted)
                    YText(main,if(limit==null)(if(narrow)14 else 16) else if(narrow)17 else 20,Modifier.alignByBaseline(),number=true,color=mainColor)
                }
            }
            Column(Modifier.fillMaxWidth().padding(start=28.dp+(if(narrow)6.dp else 8.dp)),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                YText(caption,if(t.id=="journal")11 else 9,color=t.muted)
                limit?.let{BudgetTrack(spent,it)}
            }
        }
    }
}

internal data class MonthlyBudgetItem(val id:String,val parentId:String?,val name:String,val icon:String,val spent:Long,val limit:Long?)

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun BudgetLimitList(items:List<MonthlyBudgetItem>,currency:com.yohaku.expensetracker.domain.model.Currency,onClick:(String)->Unit) {
    val t=LocalYohaku.current
    val narrow=LocalConfiguration.current.screenWidthDp<=360||LocalDensity.current.fontScale>1.2f
    val roots=items.filter{it.parentId==null}
    fun children(root:MonthlyBudgetItem)=items.filter{it.parentId==root.id}
    fun money(value:Long)=com.yohaku.expensetracker.domain.model.Money(currency,value).display()
    val (budgeted,unbudgeted)=roots.partition{it.limit!=null||children(it).any{c->c.limit!=null}}
    val measurer=rememberTextMeasurer()
    val density=LocalDensity.current
    @Composable fun amount(item:MonthlyBudgetItem,size:Int=14) {
        YText(if(item.limit==null)word("已花 ","支出 ")+money(item.spent)else (if(item.spent>item.limit)word("超出 ","超過 ")else word("还剩 ","残り "))+money(kotlin.math.abs(item.limit-item.spent)),size,number=true,color=if(item.limit!=null&&item.spent>item.limit)t.warm else t.ink)
    }
    @Composable fun group(root:MonthlyBudgetItem,modifier:Modifier=Modifier,compact:Boolean=false) {
        val children=children(root)
        val frame=when(t.id) {
            "objects"->Modifier.shadow(2.dp,RoundedCornerShape(20.dp)).background(t.surface,RoundedCornerShape(20.dp)).padding(14.dp)
            "journal"->Modifier.background(t.surface).padding(12.dp)
            else->Modifier.drawBehind{drawLine(t.ink,Offset.Zero,Offset(size.width,0f),1.dp.toPx())}.padding(top=16.dp,bottom=8.dp)
        }
        Column(modifier.testTag("budget-group-${root.id}").then(frame),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Column(Modifier.fillMaxWidth().testTag("budget-category-${root.id}").clickable{onClick(root.id)},verticalArrangement=Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    CategoryIconBadge(root.icon,CategoryBadge.SMALL)
                    YText(root.name,if(t.id=="journal")18 else 14,Modifier.weight(1f))
                    if(!narrow&&!compact)amount(root,16)
                }
                if(narrow||compact)amount(root,16)
                YText(word("已花 ","支出 ")+money(root.spent)+" / "+word("预算 ","予算 ")+(root.limit?.let{money(it)}?:word("未设置","未設定")),if(t.id=="journal")11 else 10,color=t.muted)
                root.limit?.let{BudgetTrack(root.spent,it)}
            }
            if(children.isNotEmpty()) {
                val allUnset=children.all{it.limit==null}
                val inset=when(t.id) {
                    "objects"->Modifier.background(t.soft,RoundedCornerShape(15.dp)).padding(7.dp)
                    "journal"->Modifier.padding(start=12.dp).drawBehind{val path=Path();path.moveTo(0f,0f);path.lineTo(1.dp.toPx(),size.height-5.dp.toPx());path.quadraticBezierTo(0f,size.height,6.dp.toPx(),size.height);path.lineTo(size.width,size.height);drawPath(path,t.accent,style=androidx.compose.ui.graphics.drawscope.Stroke(1.3.dp.toPx()))}.padding(start=12.dp,bottom=10.dp)
                    else->Modifier.padding(start=18.dp).drawBehind{drawLine(t.ink,Offset.Zero,Offset(0f,size.height),1.dp.toPx())}.padding(start=14.dp)
                }
                Column(Modifier.fillMaxWidth().testTag("budget-children-${root.id}").then(inset),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    if(allUnset)YText(word("子分类未单独设预算","子カテゴリの個別予算なし"),10,color=t.muted)
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val cellWidth=with(density){((maxWidth-10.dp)/2-if(t.id=="objects")18.dp else 0.dp).toPx()}
                        val longName=children.any{measurer.measure(it.name,TextStyle(fontFamily=t.body,fontSize=(if(t.id=="journal")15 else 12).sp),softWrap=false).size.width>cellWidth}
                        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            children.chunked(if(narrow||longName)1 else 2).forEach{pair->
                                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                    pair.forEach{child->
                                        val dashed=child.limit==null&&!allUnset
                                        val cell=if(t.id=="objects")Modifier.background(if(dashed)Color.Transparent else t.surface,RoundedCornerShape(11.dp)).then(if(dashed)Modifier.drawBehind{drawRoundRect(t.line,cornerRadius=androidx.compose.ui.geometry.CornerRadius(11.dp.toPx()),style=androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))))}else Modifier).padding(9.dp)
                                            else Modifier.drawBehind{if(dashed||t.id=="journal")drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx())))}.padding(bottom=6.dp)
                                        Column(Modifier.weight(1f).fillMaxHeight().testTag("budget-category-${child.id}").clickable{onClick(child.id)}.then(cell),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                            YText(child.name,if(t.id=="journal")15 else 12)
                                            amount(child,if(t.id=="journal")14 else 12)
                                            if(child.limit!=null) {
                                                BudgetTrack(child.spent,child.limit,height=3)
                                                YText(word("已花 ","支出 ")+money(child.spent)+" / "+word("预算 ","予算 ")+money(child.limit),9,color=t.muted)
                                            }else if(allUnset)YText((if(root.spent==0L)0 else child.spent*100/root.spent).toString()+"%",10,color=t.muted)
                                            else YText(word("未设预算","予算なし"),10,color=t.muted)
                                        }
                                    }
                                    if(pair.size==1&&!narrow&&!longName)Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    val direct=root.spent-children.sumOf{it.spent}
                    if(direct>0)YText(word("未细分 ","内訳未分類 ")+money(direct),11,Modifier.testTag("budget-unspecified-${root.id}"),color=t.muted)
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth().testTag("budget-category-list"),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        var index=0
        while(index<budgeted.size) {
            val first=budgeted[index]
            val second=budgeted.getOrNull(index+1)
            if(t.id=="objects"&&!narrow&&children(first).isEmpty()&&second!=null&&children(second).isEmpty()) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(12.dp)){group(first,Modifier.weight(1f).fillMaxHeight(),true);group(second,Modifier.weight(1f).fillMaxHeight(),true)}
                index+=2
            }else {group(first,Modifier.fillMaxWidth());index++}
        }
        if(unbudgeted.isNotEmpty()) {
            ThemeSection(word("未设预算","予算なし"),"")
            unbudgeted.forEach{root->
                val children=children(root)
                Column(Modifier.fillMaxWidth().testTag("budget-group-${root.id}").then(if(t.id=="objects")Modifier.background(t.soft,RoundedCornerShape(14.dp)).padding(12.dp)else Modifier.padding(vertical=8.dp)),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth().testTag("budget-category-${root.id}").clickable{onClick(root.id)},verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        CategoryIconBadge(root.icon,CategoryBadge.SMALL)
                        YText(root.name,13,Modifier.weight(1f));YText(word("已花 ","支出 ")+money(root.spent),12,number=true,color=t.muted)
                    }
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        children.forEachIndexed{i,c->YText(c.name+" "+money(c.spent)+if(i<children.lastIndex)" ·"else"",11,Modifier.testTag("budget-category-${c.id}").clickable{onClick(c.id)},color=t.muted)}
                    }
                }
            }
        }
    }
}
