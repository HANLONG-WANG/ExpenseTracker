package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.core.graphics.PathParser
import com.yohaku.expensetracker.ui.theme.*
import java.time.LocalDate
import java.time.YearMonth

@Composable fun YText(text:String,size:Int=14,modifier:Modifier=Modifier,color:Color=LocalYohaku.current.ink,number:Boolean=false,bold:Boolean=false,letterSpacing:Float=0f,lineHeightMultiplier:Float=1.65f,weight:FontWeight=if(bold)FontWeight.SemiBold else FontWeight.Normal,textAlign:androidx.compose.ui.text.style.TextAlign?=null,onTextLayout:(androidx.compose.ui.text.TextLayoutResult)->Unit={},cssLineBox:Boolean=false,fontStyle:FontStyle=FontStyle.Normal,balanced:Boolean=false) {
    val t=LocalYohaku.current
    // CSS includes trailing tracking in an inline box; Android omits it.
    // Match the box width without rescaling or clipping the number glyphs.
    val tracking=if(number&&t.id=="magazine"&&letterSpacing<0)Modifier.layout{measurable,constraints->
        val placeable=measurable.measure(constraints)
        layout((placeable.width+letterSpacing.sp.toPx()).roundToInt().coerceAtLeast(0),placeable.height){placeable.placeRelative(0,0)}
    } else Modifier
    val lines=if(cssLineBox)remember(text,size,lineHeightMultiplier){mutableIntStateOf(1)} else null
    val lineBox=if(lines!=null)Modifier.height(with(LocalDensity.current){(size*lineHeightMultiplier).sp.toDp()*lines.intValue}).wrapContentHeight(Alignment.CenterVertically,unbounded=true) else Modifier
    // Balanced phrase breaking keeps short display lines from ending on a single orphaned character.
    Text(text,modifier.then(lineBox).then(tracking),style=if(balanced)androidx.compose.material3.LocalTextStyle.current.copy(lineBreak=androidx.compose.ui.text.style.LineBreak.Heading)else androidx.compose.material3.LocalTextStyle.current,color=color,fontSize=size.sp,fontFamily=if(number)t.number else t.body,fontWeight=weight,fontStyle=fontStyle,lineHeight=(size*lineHeightMultiplier).sp,letterSpacing=letterSpacing.sp,textAlign=textAlign,onTextLayout={lines?.intValue=it.lineCount;onTextLayout(it)})
}
/** pageFlow accounts for the parent page's 15dp gap and collapsed title margins. */
@Composable fun YTitle(title:String,eyebrow:String="",description:String="",icon:String="leaf",pageFlow:Boolean=false) {
    val t=LocalYohaku.current
    val width=androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val narrow=width<=360
    val leading=if(pageFlow)0.dp else 5.dp
    val trailing=(if(t.id=="objects")17 else 22)-(if(pageFlow)15 else 0)
    when(t.id) {
        "magazine"->Column(Modifier.fillMaxWidth().testTag("page-title").padding(top=leading,bottom=trailing.dp)){
            if(eyebrow.isNotEmpty())Box(Modifier.fillMaxWidth().heightIn(min=19.dp)){
                YText(eyebrow,8,Modifier.padding(top=6.dp),color=t.muted,letterSpacing=2f,lineHeightMultiplier=11f/8,cssLineBox=true)
            }
            YText(title,if(narrow)29 else 33,Modifier.padding(top=9.dp).testTag("page-title-heading"),letterSpacing=-1f,lineHeightMultiplier=1.55f)
            if(description.isNotEmpty())YText(description,11,Modifier.padding(top=11.dp),color=t.muted,lineHeightMultiplier=1.9f)
            Box(Modifier.padding(top=11.dp).size(37.dp,2.dp).background(t.accent))
        }
        "journal"->Column(Modifier.fillMaxWidth().testTag("page-title").padding(top=leading+5.dp,bottom=trailing.dp+7.dp)){
            if(eyebrow.isNotEmpty())Box(Modifier.fillMaxWidth().heightIn(min=22.dp)){
                YText(eyebrow,8,Modifier.padding(top=3.dp).rotate(-2f).background(Color(0xffd9dfd3)).padding(start=8.dp,end=12.dp,top=4.dp,bottom=4.dp),color=t.muted,letterSpacing=1f,lineHeightMultiplier=11f/8,cssLineBox=true)
            }
            YText(title,29,Modifier.padding(top=9.dp).testTag("page-title-heading"),lineHeightMultiplier=1.7f)
            if(description.isNotEmpty())YText(description,12,Modifier.padding(top=5.dp),color=t.muted,lineHeightMultiplier=1.9f)
            Canvas(Modifier.padding(top=8.dp).size(95.dp,7.dp).rotate(-1.5f)){
                drawLine(t.accent,Offset.Zero,Offset(size.width,0f),2.dp.toPx())
                drawLine(t.accent.copy(alpha=.19f),Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())
            }
        }
        else->Row(Modifier.fillMaxWidth().testTag("page-title").padding(top=leading,bottom=trailing.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(if(narrow)10.dp else 13.dp)){
            val shape=RoundedCornerShape(14.dp,14.dp,19.dp,14.dp)
            Box(Modifier.size(if(narrow)36.dp else 43.dp,if(narrow)41.dp else 48.dp).rotate(-5f)){
                Box(Modifier.matchParentSize().offset(y=3.dp).background(Color(0x30bc9d74),shape))
                Box(Modifier.matchParentSize().background(Color(0xffe8d2ae),shape),contentAlignment=Alignment.Center){YIcon(icon,Modifier.size(25.dp).rotate(5f))}
            }
            Column(Modifier.weight(1f)){
                if(eyebrow.isNotEmpty())Box(Modifier.fillMaxWidth().heightIn(min=19.dp)){
                    YText(eyebrow,7,Modifier.padding(top=8.dp),color=t.muted,letterSpacing=1f,lineHeightMultiplier=10f/7)
                }
                YText(title,if(narrow)23 else 27,Modifier.padding(top=6.dp).testTag("page-title-heading"),weight=FontWeight.Medium,lineHeightMultiplier=1.5f,letterSpacing=-.6f)
                if(description.isNotEmpty())YText(description,10,Modifier.padding(top=8.dp),color=t.muted,lineHeightMultiplier=1.9f)
            }
        }
    }
}
@Composable fun Panel(modifier:Modifier=Modifier,contentSpacing:androidx.compose.ui.unit.Dp=10.dp,content:@Composable ColumnScope.()->Unit) {
    val t=LocalYohaku.current;val journal=t.id=="journal";val objects=t.id=="objects"
    val shape=if(objects)RoundedCornerShape(21.dp,21.dp,25.dp,21.dp) else RoundedCornerShape(0.dp)
    Box(modifier.fillMaxWidth()){
        if(journal||objects)Box(Modifier.matchParentSize().offset(x=if(journal)2.dp else 0.dp,y=if(journal)3.dp else 4.dp).background(if(journal)Color(0x0b927850) else Color(0x18d4c3a8),shape))
        val edge=if(objects)Modifier else Modifier.drawBehind{
            if(journal)drawRect(Color(0xffc8beaa),topLeft=Offset(.5.dp.toPx(),.5.dp.toPx()),size=androidx.compose.ui.geometry.Size(size.width-1.dp.toPx(),size.height-1.dp.toPx()),style=androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))))
            else {drawLine(t.line,Offset(0f,.5.dp.toPx()),Offset(size.width,.5.dp.toPx()),1.dp.toPx());drawLine(t.line,Offset(0f,size.height-.5.dp.toPx()),Offset(size.width,size.height-.5.dp.toPx()),1.dp.toPx())}
        }
        Column(Modifier.fillMaxWidth().background(if(journal)Color(0xfffaf5e7) else if(objects)t.surface else Color.Transparent,shape).then(edge).padding(horizontal=if(journal)15.dp else if(objects)17.dp else 0.dp,vertical=if(journal)19.dp else if(objects)20.dp else 22.dp),verticalArrangement=Arrangement.spacedBy(contentSpacing),content=content)
    }
}
/** [arrow] marks links that open another page, matching section-heading links. */
@Composable fun YLink(label:String,onClick:()->Unit,modifier:Modifier=Modifier,arrow:Boolean=false) {
    val link=modifier.defaultMinSize(minHeight=40.dp).clickable(onClick=onClick).padding(vertical=8.dp)
    if(!arrow)YText(label,12,link,color=LocalYohaku.current.accent)
    else Row(link,horizontalArrangement=Arrangement.spacedBy(4.dp)){
        YText(label,12,color=LocalYohaku.current.accent)
        YText("→",12,Modifier.clearAndSetSemantics{},color=LocalYohaku.current.accent)
    }
}
/** Final field label metrics; ink marks belong to journal text/date fields only. */
@Composable private fun FieldLabel(label:String,modifier:Modifier=Modifier,inkMark:Boolean=false,compactNumber:Boolean=false){
    val t=LocalYohaku.current
    val size=if(compactNumber)if(t.id=="journal")11 else 9 else when(t.id){"journal"->13;"objects"->11;else->9}
    val line=if(compactNumber)size*1.8f else when(t.id){"journal"->19f;"objects"->16f;else->13f}
    Row(modifier.fillMaxWidth()){
        if(inkMark&&t.id=="journal")YText("·",13,Modifier.clearAndSetSemantics{}.padding(end=5.dp),color=t.warm,lineHeightMultiplier=19f/13,cssLineBox=true)
        YText(label,size,Modifier.weight(1f),color=t.muted,letterSpacing=if(t.id=="magazine").3f else 0f,lineHeightMultiplier=line/size,cssLineBox=true)
    }
}

@Composable fun YField(label:String,value:String,onChange:(String)->Unit,modifier:Modifier=Modifier,numeric:Boolean=false,multiline:Boolean=false,emphasizeAmount:Boolean=false,amountSymbol:String="¥",displayLabel:String=label,compactNumber:Boolean=false,readOnly:Boolean=false) {
    val t=LocalYohaku.current
    val objects=t.id=="objects";val journal=t.id=="journal"
    val size=if(compactNumber)if(journal)30 else if(objects)25 else 23 else if(emphasizeAmount)if(objects)43 else if(journal)46 else 49 else if(journal)15 else 13
    val textLine=if(compactNumber)size*1.45f else if(emphasizeAmount)if(objects)62f else if(journal)66f else 56f else if(journal)21f else 19f
    var lines by remember(value,size){mutableIntStateOf(1)}
    val textHeight=with(LocalDensity.current){textLine.sp.toDp()*lines}
    val frame=if(objects)Modifier.background(if(emphasizeAmount)Color(0xfff1e0ba) else Color(0xfff7efdf),RoundedCornerShape(if(emphasizeAmount)17.dp else 13.dp)).border(1.dp,Color(0xffe7d7bc),RoundedCornerShape(if(emphasizeAmount)17.dp else 13.dp)) else Modifier.drawBehind{
        val stroke=if(journal&&emphasizeAmount)2.dp.toPx() else 1.dp.toPx()
        drawLine(if(journal)t.accent else t.ink,Offset(0f,this.size.height-stroke/2),Offset(this.size.width,this.size.height-stroke/2),stroke)
    }
    val inset=if(emphasizeAmount)Modifier.padding(start=if(objects)14.dp else 13.dp,end=if(objects)56.dp else 13.dp,top=if(objects)14.dp else 13.dp,bottom=if(journal)15.dp else 14.dp) else if(objects)Modifier.padding(start=12.dp,end=if(multiline)12.dp else 31.dp,top=14.dp,bottom=14.dp) else if(journal)Modifier.padding(start=2.dp,end=2.dp,top=12.dp,bottom=9.dp) else Modifier.padding(top=13.dp,bottom=14.dp)
    Column(modifier.fillMaxWidth().testTag("field-group-"+label),verticalArrangement=Arrangement.spacedBy(if(journal)6.dp else if(objects)9.dp else 10.dp)) {
        FieldLabel(displayLabel,inkMark=true,compactNumber=compactNumber)
        Box(Modifier.fillMaxWidth()){
            BasicTextField(value,onValueChange=onChange,Modifier.testTag("field-"+label).semantics{contentDescription=displayLabel}.fillMaxWidth().then(frame).then(inset).height(textHeight).wrapContentHeight(Alignment.CenterVertically,unbounded=true),
                textStyle=TextStyle(color=if(journal&&emphasizeAmount)t.accent else t.ink,fontFamily=if(emphasizeAmount||compactNumber)t.number else t.body,fontSize=size.sp,lineHeight=textLine.sp,fontWeight=if(objects&&emphasizeAmount)FontWeight.Medium else FontWeight.Normal,letterSpacing=(if(compactNumber)-.6f else if(emphasizeAmount)if(objects||journal)-2f else -1.7f else 0f).sp),cursorBrush=SolidColor(t.accent),singleLine=!multiline,readOnly=readOnly,onTextLayout={lines=it.lineCount},
                keyboardOptions=KeyboardOptions(keyboardType=if(numeric)KeyboardType.Decimal else KeyboardType.Text))
            if(objects&&!emphasizeAmount)Box(Modifier.align(Alignment.BottomEnd).offset(x=(-13).dp,y=(-6).dp).size(13.dp,3.dp).background(Color(0xffe5d4ba),RoundedCornerShape(5.dp)))
            if(objects&&emphasizeAmount)Box(Modifier.align(Alignment.CenterEnd).padding(end=14.dp).size(29.dp).background(Color(0xffe3c38d),RoundedCornerShape(50)).border(1.dp,Color(0xffcfa969),RoundedCornerShape(50)).drawBehind{drawCircle(Color(0xfff4dcaf),radius=this.size.minDimension/2-2.5.dp.toPx(),style=androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))},contentAlignment=Alignment.Center){
                YText(amountSymbol,17,number=true,color=Color(0xffb48746),lineHeightMultiplier=1f,cssLineBox=true)
            }
        }
    }
}
@Composable fun YSearch(placeholder:String,value:String,onChange:(String)->Unit,category:Boolean=false,displayPlaceholder:String=placeholder) {
    val t=LocalYohaku.current
    val size=if(category)12 else when(t.id){"journal"->13;"objects"->12;else->11}
    val inputLine=if(category)17f else when(t.id){"journal"->19f;"objects"->17f;else->16f}
    val shape=RoundedCornerShape(13.dp)
    val frame=if(t.id=="objects")if(category)Modifier.background(Color(0xffede5d2),RoundedCornerShape(12.dp)).padding(12.dp) else Modifier.background(Color(0xfff2e6d0),shape).border(1.dp,Color(0xffe2cba8),shape).padding(15.dp) else Modifier.drawBehind{
        drawLine(if(t.id=="journal")t.accent else t.ink,Offset(0f,this.size.height),Offset(this.size.width,this.size.height),1.dp.toPx(),pathEffect=if(t.id=="journal")PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())) else null)
    }.padding(top=if(category)12.dp else 14.dp,bottom=if(category)13.dp else 15.dp)
    Row(Modifier.fillMaxWidth().testTag(if(category)"category-search" else "ledger-search").then(frame),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
        YIcon(if(category)"search" else "bag",Modifier.size(17.dp),t.muted)
        BasicTextField(value,onValueChange=onChange,modifier=Modifier.weight(1f).testTag("field-"+placeholder).semantics{contentDescription=placeholder}.height(with(LocalDensity.current){(inputLine+if(category)0f else 2f).sp.toDp()}).wrapContentHeight(Alignment.CenterVertically,unbounded=true).then(if(category)Modifier else Modifier.padding(horizontal=2.dp)),singleLine=true,
            textStyle=TextStyle(color=t.ink,fontFamily=t.body,fontSize=size.sp,lineHeight=inputLine.sp),cursorBrush=SolidColor(t.accent),
            decorationBox={inner->Box{if(value.isEmpty())YText(displayPlaceholder,size,color=t.muted,lineHeightMultiplier=inputLine/size,cssLineBox=true);inner()}})
    }
}
@Composable fun <T> YSelect(label:String,selected:T,options:List<Pair<T,String>>,onSelect:(T)->Unit,modifier:Modifier=Modifier,displayLabel:String=label) {
    val t=LocalYohaku.current
    var open by remember{mutableStateOf(false)}
    val objectTheme=t.id=="objects"
    val stateLabel=if(open)word("已展开","展開中") else word("已收起","折りたたみ")
    val menuRequester=remember{BringIntoViewRequester()}
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    LaunchedEffect(open){if(open){withFrameNanos{};menuRequester.bringIntoView()}}
    Column(modifier.fillMaxWidth().testTag("select-field-"+label)) {
        FieldLabel(displayLabel,Modifier.padding(bottom=if(t.id=="journal")6.dp else if(objectTheme)9.dp else 10.dp))
        val trigger=if(objectTheme)Modifier.drawBehind{drawRoundRect(Color(0x18d6b991),topLeft=Offset(0f,2.dp.toPx()),size=this.size,cornerRadius=androidx.compose.ui.geometry.CornerRadius(13.dp.toPx()))}.background(Color(0xfff4ead8),RoundedCornerShape(13.dp)).border(1.dp,Color(0xffe5d0b0),RoundedCornerShape(13.dp)).padding(13.dp) else if(t.id=="journal")Modifier.padding(start=2.dp,end=2.dp,top=12.dp,bottom=8.dp) else Modifier.padding(vertical=13.dp)
        Column(Modifier.testTag("select-trigger-"+label).fillMaxWidth().semantics{stateDescription=stateLabel}.clickable(role=Role.Button){if(!open){focus.clearFocus();keyboard?.hide()};open=!open}) {
            Row(Modifier.fillMaxWidth().then(trigger),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                YText(options.find{it.first==selected}?.second.orEmpty(),if(t.id=="journal")14 else 12,Modifier.weight(1f),lineHeightMultiplier=if(t.id=="journal")20f/14 else 17f/12,cssLineBox=true)
                if(objectTheme)Box(Modifier.size(20.dp).background(Color(0xffdfc4a0),RoundedCornerShape(7.dp)),contentAlignment=Alignment.Center){YText(if(open)"−" else "＋",14,color=Color(0xffa1784d),lineHeightMultiplier=20f/14,cssLineBox=true)}
                else YText(if(t.id=="journal")if(open)"↑" else "↓" else if(open)"⌃" else "⌄",if(t.id=="journal")18 else 13,color=if(t.id=="journal")t.warm else t.ink,lineHeightMultiplier=if(t.id=="journal")26f/18 else 19f/13,cssLineBox=true)
            }
            if(!objectTheme)Box(Modifier.fillMaxWidth().height(1.dp).background(if(t.id=="journal")t.accent else t.ink))
        }
        if(open)ThemedSelectMenu(label,selected,options,{id->onSelect(id);open=false},Modifier.bringIntoViewRequester(menuRequester),displayLabel=displayLabel)
    }
}
/** Owner identifies the only calendar expanded in the enclosing form. */
@Composable fun YDateFields(fields:List<Pair<String,String>>,activeOwner:Int?,onOwner:(Int?)->Unit,onDate:(Int,String)->Unit) {
    val t=LocalYohaku.current
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    Column(Modifier.testTag("date-field-grid").fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        val measurer=rememberTextMeasurer()
        val density=LocalDensity.current
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val textSize=if(t.id=="journal")15 else 13
            val reserved=if(t.id=="objects")48.dp else if(t.id=="journal")37.dp else 35.dp
            val available=with(density){((maxWidth-12.dp*(fields.size-1))/fields.size-reserved).toPx()}
            val stacked=fields.any{(_,value)->measurer.measure(value.replace("T"," "),TextStyle(fontFamily=t.body,fontSize=textSize.sp),softWrap=false).size.width>available}
            @Composable fun trigger(i:Int,label:String,value:String,modifier:Modifier) {
                DateTrigger(label,value,modifier.testTag("date-field-$i")){
                    if(activeOwner!=i){focus.clearFocus();keyboard?.hide()}
                    onOwner(if(activeOwner==i)null else i)
                }
            }
            if(stacked)Column(verticalArrangement=Arrangement.spacedBy(16.dp)){fields.forEachIndexed{i,(label,value)->trigger(i,label,value,Modifier.fillMaxWidth())}}
            else Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){fields.forEachIndexed{i,(label,value)->trigger(i,label,value,Modifier.weight(1f))}}
        }
        activeOwner?.let{owner->
            val raw=fields[owner].second
            val selected=LocalDate.parse(raw.substringBefore("T"))
            val hasTime=raw.contains("T")
            val time=raw.substringAfter("T","09:00").take(5)
            fun commit(date:String){onDate(owner,if(hasTime)date+"T"+time else date)}
            var month by remember(owner){mutableStateOf(YearMonth.from(selected))}
            CalendarSurface {
                CalendarBody(fields[owner].first,month,selected,{month=it},{date->commit(date.toString());onOwner(null)},{onOwner(null)},timeFields=if(hasTime){ {
                    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
                        YSelect(word("时","時"),time.substringBefore(":"),(0..23).map{it.toString().padStart(2,'0').let{n->n to n}},{v->onDate(owner,selected.toString()+"T"+v+":"+time.substringAfter(":"))},Modifier.weight(1f))
                        YSelect(word("分","分"),time.substringAfter(":"),(0..59).map{it.toString().padStart(2,'0').let{n->n to n}},{v->onDate(owner,selected.toString()+"T"+time.substringBefore(":")+":"+v)},Modifier.weight(1f))
                    }
                } } else null)
            }
        }
    }
}
@Composable fun YIcon(name:String,modifier:Modifier=Modifier,tint:Color=LocalYohaku.current.ink,stroke:Float=1.6f) {
    val data=iconPaths[name] ?: iconPaths.getValue("wallet")
    Canvas(modifier.size(22.dp)) {
        drawIntoCanvas{c->val native=c.nativeCanvas;native.save();native.scale(size.width/24f,size.height/24f)
            val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{color=tint.toArgb();style=android.graphics.Paint.Style.STROKE;strokeWidth=stroke;strokeCap=android.graphics.Paint.Cap.ROUND;strokeJoin=android.graphics.Paint.Join.ROUND}
            data.forEach{d->native.drawPath(PathParser.createPathFromPathData(d),paint)};native.restore()
        }
    }
}

@Composable fun SetupIntro(items:List<Pair<String,String>>) {
    val t=LocalYohaku.current;val journal=t.id=="journal";val objects=t.id=="objects"
    Column(Modifier.fillMaxWidth().testTag("setup-intro"),verticalArrangement=Arrangement.spacedBy(if(t.id=="magazine")0.dp else 15.dp)) {
        items.forEachIndexed { i,(title,note)->
            val shape=RoundedCornerShape(if(objects)16.dp else 0.dp)
            Box(Modifier.fillMaxWidth().testTag("setup-intro-row-$i").then(if(journal)Modifier.rotate(if(i%2==0)-.6f else .6f) else Modifier)){
                if(objects)Box(Modifier.matchParentSize().offset(y=3.dp).background(Color(0x1dccb491),shape))
                val frame=if(objects)Modifier.background(Color(0xfffffaec),shape).border(1.dp,Color(0xffead8b8),shape) else if(journal)Modifier.background(Color(0xfffff8e5)).drawBehind{drawRect(t.line,topLeft=Offset(.5.dp.toPx(),.5.dp.toPx()),size=androidx.compose.ui.geometry.Size(size.width-1.dp.toPx(),size.height-1.dp.toPx()),style=androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))))} else Modifier
                Column(Modifier.fillMaxWidth().then(frame)){
                    Row(Modifier.fillMaxWidth().padding(horizontal=if(objects)16.dp else if(journal)14.dp else 0.dp,vertical=20.dp),horizontalArrangement=Arrangement.spacedBy(17.dp),verticalAlignment=Alignment.Top) {
                        if(objects)Box(Modifier.widthIn(min=31.dp).heightIn(min=33.dp).background(Color(0xffede1cb),RoundedCornerShape(9.dp)),contentAlignment=Alignment.Center){YText("0${i+1}",16,color=Color(0xffad885f),number=true,lineHeightMultiplier=23f/16,cssLineBox=true)}
                        else YText("0${i+1}",if(journal)26 else 28,number=true,color=if(journal)t.warm else t.muted,lineHeightMultiplier=if(journal)38f/26 else 32f/28,cssLineBox=true)
                        Column(Modifier.weight(1f)){
                            YText(title,if(journal)20 else if(objects)16 else 19,weight=if(objects)FontWeight.Medium else FontWeight.Normal,lineHeightMultiplier=1.6f,cssLineBox=true)
                            YText(note,if(journal)12 else 10,Modifier.padding(top=9.dp),color=t.muted,lineHeightMultiplier=1.9f,cssLineBox=true)
                        }
                    }
                    if(t.id=="magazine")Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
                }
            }
        }
    }
}

@Composable fun themeChoices()=listOf(
    "magazine" to word("现代生活杂志","暮らしの雑誌"),
    "journal" to word("纸质手账","紙の手帳"),
    "objects" to word("日常小物","日常の小物")
)
@Composable private fun DateTrigger(label:String,value:String,modifier:Modifier,onClick:()->Unit) {
    val t=LocalYohaku.current;val journal=t.id=="journal";val objects=t.id=="objects"
    val size=if(journal)15 else 13
    val line=if(journal)21f else if(objects)19f else 15f
    Column(modifier.clickable(role=Role.Button,onClick=onClick),verticalArrangement=Arrangement.spacedBy(if(journal)6.dp else if(objects)9.dp else 10.dp)) {
        FieldLabel(label,inkMark=true)
        val shape=RoundedCornerShape(if(objects)13.dp else 0.dp)
        val frame=if(objects)Modifier.background(Color(0xfff7efdf),shape).border(1.dp,Color(0xffe7d7bc),shape) else Modifier.drawBehind{
            drawLine(if(journal)t.accent else t.ink,Offset(0f,this.size.height-.5.dp.toPx()),Offset(this.size.width,this.size.height-.5.dp.toPx()),1.dp.toPx())
        }
        Box(Modifier.fillMaxWidth().then(frame)){
            Row(Modifier.fillMaxWidth().padding(start=if(objects)12.dp else if(journal)2.dp else 0.dp,end=if(objects)36.dp else 35.dp,top=if(objects)14.dp else if(journal)12.dp else 13.dp,bottom=if(objects)14.dp else if(journal)9.dp else 14.dp),verticalAlignment=Alignment.CenterVertically){
                YText(value.replace("T"," "),size,Modifier.weight(1f),lineHeightMultiplier=line/size,cssLineBox=true)
            }
            Box(Modifier.align(Alignment.TopEnd).offset(x=(-5).dp,y=9.dp).size(28.dp,29.dp).testTag("date-icon-"+label).then(if(objects)Modifier.background(Color(0xffe4cbaa),RoundedCornerShape(8.dp)) else Modifier),contentAlignment=Alignment.Center){
                YIcon("life",Modifier.size(if(objects)15.dp else 17.dp),if(objects)Color(0xffb08b5c) else t.ink)
            }
            if(objects)Box(Modifier.align(Alignment.BottomEnd).offset(x=(-13).dp,y=(-6).dp).size(13.dp,3.dp).background(Color(0xffe5d4ba),RoundedCornerShape(5.dp)))
        }
    }
}
