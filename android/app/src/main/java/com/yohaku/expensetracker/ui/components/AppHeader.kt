package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.*

/** v55 app-header; visual bounds stay independent from the larger touch target. */
@Composable fun YAppHeader(bookName:String?,onBooks:()->Unit,onSettings:()->Unit,showActions:Boolean=true) {
    val t=LocalYohaku.current
    val language=LocalLanguage.current
    val narrow=LocalConfiguration.current.screenWidthDp<=360
    val journal=t.id=="journal"
    val objects=t.id=="objects"
    val left=if(journal)if(narrow)25 else 28 else if(objects)if(narrow)17 else 20 else 23
    val right=if(journal)if(narrow)16 else 19 else left
    Column(Modifier.fillMaxWidth().testTag("app-header")) {
        Layout(modifier=Modifier.fillMaxWidth().padding(top=(if(journal)16 else if(objects)17 else if(narrow)14 else 18).dp,bottom=(if(journal)16 else if(narrow&&!objects)16 else 20).dp),content={
            Row(Modifier.padding(start=left.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy((if(narrow)5 else 9).dp)) {
                if(objects)Box(Modifier.size((if(narrow)15 else 19).dp).rotate(-10f).background(Color(0xffb97550),RoundedCornerShape(7.dp,10.dp,6.dp,8.dp)))
                YText(word("余量","ヨハク"),if(journal)if(narrow)22 else 26 else if(objects)if(narrow)20 else 23 else if(narrow)18 else 22,
                    letterSpacing=if(objects)0f else 3f,lineHeightMultiplier=1.4f,
                    weight=if(journal)FontWeight.Normal else if(objects)FontWeight.SemiBold else FontWeight(650))
            }
            Box {if(bookName!=null)Row(Modifier.widthIn(max=(if(narrow)98 else 130).dp).testTag("header-book").clickable(role=Role.Button,onClick=onBooks)
                .then(if(objects)Modifier.background(Color(0xfff2e5d1),RoundedCornerShape(14.dp)).padding(horizontal=(if(narrow)7 else 10).dp) else if(!journal)Modifier.drawBehind{drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())} else Modifier)
                .padding(vertical=7.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                YText(bookName,if(journal)if(narrow)10 else 12 else if(narrow)9 else 10,Modifier.weight(1f,fill=false),color=if(journal)t.accent else t.muted,lineHeightMultiplier=1.5f)
                YText("⌄",14,color=t.muted,lineHeightMultiplier=1.5f)
            }
            }
            Box {if(showActions)Row(Modifier.padding(end=right.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy((if(narrow)7 else 11).dp)) {
                val shape=when(t.id){"journal"->RoundedCornerShape(8.dp,11.dp,9.dp,13.dp);"objects"->RoundedCornerShape(10.dp,10.dp,14.dp,10.dp);else->RoundedCornerShape(50)}
                Box(Modifier.testTag("header-settings").size((if(narrow)27 else 31).dp).background(if(journal)Color.Transparent else if(objects)Color(0xffe9ddc2) else t.soft,shape).then(if(journal)Modifier.border(1.dp,t.line,shape) else Modifier).semantics{contentDescription=if(language=="zh")"设置" else "設定"}.clickable(role=Role.Button,onClick=onSettings),contentAlignment=Alignment.Center){YIcon("settings",Modifier.size(16.dp))}
            }}
        }) {children,constraints->
            val loose=constraints.copy(minWidth=0,minHeight=0)
            val start=children[0].measure(loose);val end=children[2].measure(loose)
            val available=(constraints.maxWidth-2*(maxOf(start.width,end.width)+8.dp.roundToPx())).coerceAtLeast(0)
            val title=children[1].measure(loose.copy(maxWidth=available))
            val height=maxOf(start.height,title.height,end.height)
            layout(constraints.maxWidth,height){start.placeRelative(0,(height-start.height)/2);title.placeRelative((constraints.maxWidth-title.width)/2,(height-title.height)/2);end.placeRelative(constraints.maxWidth-end.width,(height-end.height)/2)}
        }
        if(journal){
            Canvas(Modifier.fillMaxWidth().padding(start=left.dp,end=right.dp).height(1.dp)){drawLine(t.line,Offset.Zero,Offset(size.width,0f),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())))}
            Spacer(Modifier.height(18.dp))
        }
    }
}
