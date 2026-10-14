package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.*
import com.yohaku.expensetracker.ui.theme.*

/** Final select-options CSS; the journal scrolls with its enclosing form. */
@Composable internal fun <T> ThemedSelectMenu(label:String,selected:T,options:List<Pair<T,String>>,onSelect:(T)->Unit,modifier:Modifier=Modifier,displayLabel:String=label){
    val t=LocalYohaku.current;val journal=t.id=="journal";val objects=t.id=="objects"
    val shape=RoundedCornerShape(if(objects)19.dp else 0.dp)
    Box(modifier.fillMaxWidth().padding(top=if(journal)16.dp else if(objects)10.dp else 12.dp).then(if(journal)Modifier.rotate(-.5f) else Modifier)){
        Box(Modifier.matchParentSize().offset(x=if(objects)0.dp else 3.dp,y=if(t.id=="magazine")3.dp else 4.dp).background(if(objects)Color(0x26c6ab80) else if(journal)Color(0x12927850) else t.soft,shape))
        val border=if(objects)Modifier else Modifier.drawBehind{
            drawRect(if(journal)Color(0xffb6b39a) else t.ink,topLeft=Offset(.5.dp.toPx(),.5.dp.toPx()),size=androidx.compose.ui.geometry.Size(size.width-1.dp.toPx(),size.height-1.dp.toPx()),style=Stroke(1.dp.toPx(),pathEffect=if(journal)PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())) else null))
        }
        val scroll=if(journal)Modifier else Modifier.heightIn(max=if(objects)250.dp else 210.dp).verticalScroll(rememberScrollState())
        Column(Modifier.fillMaxWidth().testTag("select-menu-frame").background(if(objects)Color(0xfffff9ed) else if(journal)Color(0xfffff8e4) else t.surface,shape).then(border).then(scroll).padding(horizontal=if(objects)12.dp else 13.dp,vertical=if(objects)12.dp else if(journal)9.dp else 1.dp)){
            Box(Modifier.fillMaxWidth()){
                Row(Modifier.fillMaxWidth().padding(start=if(objects)3.dp else 0.dp,end=if(objects)3.dp else 0.dp,top=if(objects)4.dp else if(journal)10.dp else 14.dp,bottom=if(objects)12.dp else if(journal)10.dp else 14.dp),horizontalArrangement=Arrangement.spacedBy(if(journal)7.dp else 10.dp),verticalAlignment=Alignment.CenterVertically){
                    if(journal)YText("✳",13,color=t.warm,lineHeightMultiplier=19f/13,cssLineBox=true)
                    if(objects)Box(Modifier.size(23.dp).background(Color(0xffecd9b9),RoundedCornerShape(7.dp)).padding(3.dp)){YIcon("leaf",Modifier.size(17.dp),Color(0xffa17c54))}
                    YText(displayLabel,if(journal)13 else 12,Modifier.weight(1f),color=if(journal)t.warm else if(objects)Color(0xffa17c54) else t.ink,lineHeightMultiplier=if(journal)19f/13 else 17f/12,cssLineBox=true)
                    if(t.id=="magazine")YText(word("请选择","SELECT"),7,color=t.muted,lineHeightMultiplier=10f/7,cssLineBox=true)
                }
                if(journal)Box(Modifier.align(Alignment.TopEnd).offset(x=(-9).dp,y=(-16).dp).size(39.dp,16.dp).rotate(9f).background(Color(0x7dc6c3a5)))
            }
            if(!objects)MenuRule(if(journal)t.line else t.ink,journal)
            if(objects)Spacer(Modifier.height(6.dp))
            options.forEachIndexed{i,(id,name)->
                val chosen=id==selected
                val itemShape=RoundedCornerShape(10.dp)
                Box(Modifier.fillMaxWidth().testTag("select-option-$label-$i").selectable(chosen,role=Role.RadioButton,onClick={onSelect(id)})){
                    if(objects)Box(Modifier.matchParentSize().offset(y=2.dp).background(Color(0x0fd6b991),itemShape))
                    Row(Modifier.fillMaxWidth().then(if(objects)Modifier.background(Color(0xfff2e7d4),itemShape) else Modifier).padding(horizontal=if(objects)10.dp else 0.dp,vertical=if(objects||journal)12.dp else 14.dp),horizontalArrangement=Arrangement.spacedBy(if(objects)0.dp else 10.dp),verticalAlignment=Alignment.CenterVertically){
                        if(!objects)YText(if(journal)if(chosen)"✓" else "□" else (i+1).toString().padStart(2,'0'),if(journal)18 else 12,if(journal)Modifier.width(with(LocalDensity.current){18.sp.toDp()}) else Modifier,color=if(journal)t.accent else t.muted,number=!journal,lineHeightMultiplier=if(journal)26f/18 else 14f/12,cssLineBox=true,fontStyle=if(journal)FontStyle.Normal else FontStyle.Italic)
                        Column(Modifier.weight(1f)){
                            YText(name,if(journal)14 else 12,color=if(chosen&&t.id=="magazine")t.accent else t.ink,lineHeightMultiplier=if(journal)20f/14 else 17f/12,cssLineBox=true)
                            if(chosen&&t.id=="magazine")Box(Modifier.padding(top=2.dp).fillMaxWidth().height(1.dp).background(t.accent))
                        }
                        if(objects)Box(Modifier.size(15.dp).background(if(chosen)Color(0xffb37b50) else Color(0xfffff9ed),CircleShape).border(1.dp,Color(0xffcbb28e),CircleShape).then(if(chosen)Modifier.drawBehind{drawCircle(Color(0xfffff9ed),radius=size.minDimension/2-2.5.dp.toPx(),style=Stroke(3.dp.toPx()))} else Modifier))
                    }
                }
                if(journal||!objects&&i!=options.lastIndex)MenuRule(t.line,journal)
                if(objects)Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable private fun MenuRule(color:Color,dashed:Boolean){
    Canvas(Modifier.fillMaxWidth().height(1.dp)){drawLine(color,Offset(0f,.5.dp.toPx()),Offset(size.width,.5.dp.toPx()),1.dp.toPx(),pathEffect=if(dashed)PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())) else null)}
}
