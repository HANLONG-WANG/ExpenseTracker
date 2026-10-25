package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

/** section(): original independent headings and collapsed margins in a spaced page. */
@Composable internal fun ThemeSection(title:String,eyebrow:String="",link:String="",onClick:(()->Unit)?=null,pageGap:Int=15,previousBottomPadding:Int=0,linkTag:String="section-link"){
    val t=LocalYohaku.current
    val label=if(t.id=="journal"&&LocalConfiguration.current.screenWidthDp<=360)""else eyebrow
    val marginTop=if(t.id=="magazine")23 else 20
    val trailing=12-pageGap
    Column(Modifier.fillMaxWidth().layout{measurable,constraints->
        val p=measurable.measure(constraints)
        layout(p.width,(p.height+trailing.dp.roundToPx()).coerceAtLeast(0)){p.placeRelative(0,0)}
    }.padding(top=(marginTop-pageGap-previousBottomPadding).coerceAtLeast(0).dp)){
        val measurer=rememberTextMeasurer()
        val density=LocalDensity.current
        val titleWidth=when(t.id) {
            "journal"->measurer.measure(journalSectionLabel(title,label),TextStyle(fontFamily=t.body,fontSize=20.sp),softWrap=false).size.width+with(density){(if(label.isEmpty())7 else 14).sp.toPx()}
            "objects"->measurer.measure(title,TextStyle(fontFamily=t.body,fontSize=17.sp,fontWeight=FontWeight.Medium),softWrap=false).size.width+with(density){16.dp.toPx()}
            else->maxOf(measurer.measure(title,TextStyle(fontFamily=t.body,fontSize=19.sp),softWrap=false).size.width,measurer.measure(eyebrow,TextStyle(fontFamily=t.body,fontSize=8.sp,letterSpacing=2.sp),softWrap=false).size.width).toFloat()
        }
        val linkSize=if(t.id=="magazine")9 else if(t.id=="journal")12 else 11
        val linkWidth=measurer.measure(link,TextStyle(fontFamily=t.body,fontSize=linkSize.sp),softWrap=false).size.width+with(density){18.dp.toPx()}
        BoxWithConstraints(Modifier.fillMaxWidth().testTag("section-heading").then(if(t.id=="journal")Modifier.drawBehind{
            drawLine(t.line,Offset(0f,size.height-.5.dp.toPx()),Offset(size.width,size.height-.5.dp.toPx()),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())))
        }.padding(bottom=10.dp) else Modifier)) {
            val wrapLink=onClick!=null&&titleWidth+linkWidth+with(density){10.dp.toPx()}>constraints.maxWidth
            if(wrapLink)Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                SectionTitle(title,label,Modifier.fillMaxWidth())
                SectionLink(link,onClick!!,Modifier.align(Alignment.End),linkTag)
            }else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
                SectionTitle(title,label,Modifier.weight(1f))
                if(onClick!=null)SectionLink(link,onClick,tag=linkTag)
            }
        }
    }
}

@Composable private fun SectionTitle(title:String,eyebrow:String,modifier:Modifier) {
    val t=LocalYohaku.current
    when(t.id) {
        "magazine"->Column(modifier) {
            if(eyebrow.isNotEmpty())YText(eyebrow,8,Modifier.testTag("section-eyebrow"),color=t.muted,letterSpacing=2f,lineHeightMultiplier=1.4f,cssLineBox=true)
            Spacer(Modifier.height(7.dp))
            YText(title,19,Modifier.testTag("section-title"),lineHeightMultiplier=1.4f,cssLineBox=true)
        }
        "journal"->JournalSectionTitle(title,eyebrow,modifier)
        else->Row(modifier) {
            Box(Modifier.padding(end=8.dp).alignBy{it.measuredHeight}.size(8.dp,14.dp).testTag("section-tab").rotate(-8f).background(androidx.compose.ui.graphics.Color(0xffbb8c63),RoundedCornerShape(3.dp,3.dp,6.dp,6.dp)))
            YText(title,17,Modifier.weight(1f).alignByBaseline().testTag("section-title"),weight=FontWeight.Medium,lineHeightMultiplier=25f/17,cssLineBox=true)
        }
    }
}

@Composable private fun SectionLink(link:String,onClick:()->Unit,modifier:Modifier=Modifier,tag:String="section-link") {
    val t=LocalYohaku.current
    val size=if(t.id=="magazine")9 else if(t.id=="journal")12 else 11
    Row(modifier.testTag(tag).clickable(role=Role.Button,onClick=onClick).padding(vertical=5.dp),horizontalArrangement=Arrangement.spacedBy(3.dp),verticalAlignment=Alignment.CenterVertically) {
        YText(link,size,color=t.muted,lineHeightMultiplier=(if(t.id=="magazine")13f else if(t.id=="journal")17f else 16f)/size,cssLineBox=true)
        YIcon("arrow",Modifier.size(15.dp),t.muted)
    }
}

@Composable private fun journalSectionLabel(title:String,eyebrow:String):AnnotatedString {
    val t=LocalYohaku.current
    return buildAnnotatedString {
        withStyle(SpanStyle(color=t.warm,fontSize=17.sp)){append("✳")}
        // The renderer uses two 7sp inline gaps; measure the text without them.

        append(title)

        withStyle(SpanStyle(color=t.muted,fontSize=7.sp,letterSpacing=.5.sp)){append(eyebrow)}
    }
}

@Composable private fun JournalSectionTitle(title:String,eyebrow:String,modifier:Modifier){
    val t=LocalYohaku.current
    val text=buildAnnotatedString{
        withStyle(SpanStyle(color=t.warm,fontSize=17.sp)){append("✳")}
        appendInlineContent("gap"," ")
        append(title)
        if(eyebrow.isNotEmpty()) {
            appendInlineContent("gap"," ")
            withStyle(SpanStyle(color=t.muted,fontSize=7.sp,letterSpacing=.5.sp)){append(eyebrow)}
        }
    }
    var lines by remember(title,eyebrow){mutableIntStateOf(1)}
    val height=with(LocalDensity.current){(29*lines).sp.toDp()}
    Text(text,modifier.testTag("section-title").height(height).wrapContentHeight(Alignment.CenterVertically,unbounded=true),color=t.ink,fontFamily=t.body,fontSize=20.sp,fontWeight=FontWeight.Normal,lineHeight=29.sp,letterSpacing=0.sp,
        inlineContent=mapOf("gap" to InlineTextContent(Placeholder(7.sp,1.sp,PlaceholderVerticalAlign.AboveBaseline)){Spacer(Modifier)}),onTextLayout={lines=it.lineCount})
}
