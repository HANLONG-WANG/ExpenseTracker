package com.yohaku.expensetracker.ui.components

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
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.*
import com.yohaku.expensetracker.ui.theme.*

@Composable fun YBottomNav(route:String,onNavigate:(String)->Unit,onAdd:()->Unit) {
    val t=LocalYohaku.current
    val journal=t.id=="journal";val objects=t.id=="objects"
    val active=when(route){"ledger","detail","settings","trash","reminders"->"home";"categories"->"analysis";"account-detail","rules","calendar"->"accounts";"forecast"->"analysis";else->route}
    val background=if(journal)Color(0xffe9e2ce) else if(objects)Color(0xfffffaef) else t.bg
    val shape=RoundedCornerShape(topStart=if(objects)23.dp else 0.dp,topEnd=if(objects)23.dp else 0.dp)
    Column(Modifier.fillMaxWidth().testTag("bottom-nav").background(background,shape).drawBehind{
        if(objects){
            val r=23.dp.toPx();val inset=.5.dp.toPx()
            val outline=Path().apply{moveTo(inset,size.height);lineTo(inset,r);quadraticBezierTo(inset,inset,r,inset);lineTo(size.width-r,inset);quadraticBezierTo(size.width-inset,inset,size.width-inset,r);lineTo(size.width-inset,size.height)}
            drawPath(outline,Color(0xffead6b8),style=Stroke(1.dp.toPx()))
        } else drawLine(if(journal)Color(0xffbfb69d) else t.ink,Offset.Zero,Offset(size.width,0f),1.dp.toPx())
    }){
        Row(Modifier.fillMaxWidth().heightIn(min=if(objects)83.dp else 80.dp).padding(start=10.dp,end=10.dp,top=if(journal)9.dp else if(objects)11.dp else 12.dp,bottom=14.dp),verticalAlignment=Alignment.CenterVertically){
            listOf("home","analysis","add","life","accounts").forEachIndexed{index,page->
                val selected=active==page
                val label=when(page){"home"->word("今天","今日");"analysis"->word("分析","分析");"add"->word("记一笔","記録");"life"->word("生活","暮らし");else->word("账户","口座")}
                val item=Modifier.weight(if(page=="add")1.3f else 1f).testTag("nav-"+page).then(if(page=="add")Modifier.clickable(role=Role.Button,onClick=onAdd) else Modifier.selectable(selected=selected,role=Role.Tab,onClick={onNavigate(page)}))
                if(page=="add")Column(item,horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)){
                    val addShape=if(journal)RoundedCornerShape(3.dp,6.dp,2.dp,5.dp) else if(objects)RoundedCornerShape(13.dp,13.dp,18.dp,13.dp) else RoundedCornerShape(0.dp)
                    val addFrame=if(journal)Modifier.rotate(-4f).navShadow(addShape,2.dp,2.dp,Color(0x30a47755)) else if(objects)Modifier.navShadow(addShape,0.dp,4.dp,Color(0x378b5437)) else Modifier
                    Box(Modifier.size(if(journal)42.dp else if(objects)43.dp else 37.dp,if(journal)36.dp else if(objects)39.dp else 37.dp).then(addFrame).background(if(journal)t.warm else if(objects)Color(0xffb97550) else t.ink,addShape).then(if(journal||objects)Modifier.border(1.dp,if(journal)Color(0xff955d4f) else Color(0xffa66846),addShape) else Modifier),contentAlignment=Alignment.Center){YText(if(journal)"✎" else "+",if(journal||objects)27 else 25,color=if(objects)Color(0xfffff6e3) else t.surface,lineHeightMultiplier=1f)}
                    YText(label,if(journal)10 else if(objects)9 else 8,color=t.accent,lineHeightMultiplier=1.5f)
                } else Box(item.heightIn(min=45.dp),contentAlignment=Alignment.Center){
                    Column(Modifier.padding(top=if(journal)3.dp else 0.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(if(journal)3.dp else 6.dp)){
                        if(!journal&&!objects)YText("0${if(index>2)index else index+1}",14,number=true,color=if(selected)t.accent else t.muted,lineHeightMultiplier=1.2f)
                        else {
                            val icon=when(page){"analysis"->"chart";"life"->"life";"accounts"->"wallet";else->"home"}
                            val iconShape=if(journal)RoundedCornerShape(3.dp,3.dp,0.dp,0.dp) else RoundedCornerShape(9.dp)
                            val frame=if(journal)Modifier.rotate(if(index%2==0)-3f else 2f) else if(selected)Modifier.navShadow(iconShape,0.dp,2.dp,Color(0x40bc9c71)) else Modifier
                            Box(Modifier.size(if(journal)32.dp else 30.dp,if(journal)27.dp else 28.dp).then(frame).background(if(journal)if(selected)t.surface else Color(0xffdddcc8) else if(selected)Color(0xffe4ba94) else Color(0xfff0e3cf),iconShape).then(if(journal)Modifier.drawBehind{
                                val stroke=1.dp.toPx();drawLine(Color(0xffc9c5b1),Offset(0f,size.height),Offset.Zero,stroke);drawLine(Color(0xffc9c5b1),Offset(size.width,0f),Offset(size.width,size.height),stroke);drawLine(if(selected)t.warm else Color(0xffc9c5b1),Offset.Zero,Offset(size.width,0f),if(selected)3.dp.toPx() else stroke)
                            } else Modifier),contentAlignment=Alignment.Center){YIcon(icon,Modifier.size(18.dp),if(selected)t.accent else t.muted)}
                        }
                        YText(label,if(journal)11 else if(objects)9 else 8,color=if(selected)t.accent else t.muted,lineHeightMultiplier=1.5f)
                    }
                    if(selected&&!journal&&!objects)Box(Modifier.align(Alignment.BottomCenter).offset(y=6.dp).size(18.dp,1.dp).background(t.ink))
                }
            }
        }
    }
}

private fun Modifier.navShadow(shape:Shape,x:Dp,y:Dp,color:Color)=drawBehind{
    val outline=shape.createOutline(size,layoutDirection,this)
    val dx=x.toPx();val dy=y.toPx()
    withTransform({translate(dx,dy)}){drawOutline(outline,color)}
}
