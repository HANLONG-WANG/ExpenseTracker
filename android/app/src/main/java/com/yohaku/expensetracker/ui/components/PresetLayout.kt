package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.*

/** One entry template on the home shelf: magazine index card, journal washi label, objects enamel tile. */
@Composable internal fun TemplateShelfItem(index:Int,name:String,meta:String,icon:String,onClick:()->Unit,modifier:Modifier=Modifier) {
    val t=LocalYohaku.current
    val click=Modifier.clickable(role=Role.Button,onClick=onClick)
    when(t.id) {
        "journal"->{
            // Washi-taped labels, alternately tilted as if stuck in by hand.
            val tilt=if(index%2==0)-1.6f else 1.2f
            val tape=if(index%3==0)Color(0x66d9b98a)else if(index%3==1)Color(0x5590a8a0)else Color(0x55c98f80)
            Box(Modifier.padding(top=7.dp).rotate(tilt)) {
                Column(modifier.widthIn(min=96.dp,max=168.dp).background(t.surface).drawBehind{
                    drawRect(t.line,style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))))
                }.then(click).padding(start=14.dp,end=14.dp,top=15.dp,bottom=11.dp)) {
                    YText(name,16,lineHeightMultiplier=1.4f)
                    YText(meta,12,Modifier.padding(top=2.dp),color=t.warm,lineHeightMultiplier=1.5f)
                }
                Box(Modifier.align(Alignment.TopCenter).offset(y=(-7).dp).size(46.dp,13.dp).rotate(-tilt*1.6f).background(tape))
            }
        }
        "objects"->{
            // Enamel tiles that lift slightly off the desk, tinted by category.
            val shape=RoundedCornerShape(18.dp)
            Column(modifier.widthIn(min=104.dp,max=160.dp).drawBehind{
                drawRoundRect(Color(0x60e8decd),Offset(0f,3.dp.toPx()),cornerRadius=CornerRadius(18.dp.toPx()))
            }.background(t.surface,shape).clip(shape).then(click).padding(start=13.dp,end=15.dp,top=13.dp,bottom=13.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                CategoryIconBadge(icon,CategoryBadge.SMALL)
                Column {
                    YText(name,14,weight=androidx.compose.ui.text.font.FontWeight.Medium,lineHeightMultiplier=1.4f)
                    YText(meta,11,color=t.muted,lineHeightMultiplier=1.5f)
                }
            }
        }
        else->{
            // Numbered index entries separated by hairline column rules.
            Row(modifier.height(IntrinsicSize.Min).then(click).padding(end=18.dp)) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(t.line))
                Column(Modifier.padding(start=12.dp,top=2.dp,bottom=2.dp)) {
                    YText((index+1).toString().padStart(2,'0'),10,color=t.muted,number=true,letterSpacing=1f)
                    YText(name,17,Modifier.padding(top=3.dp),lineHeightMultiplier=1.35f)
                    YText(meta,10,Modifier.padding(top=2.dp),color=t.muted,letterSpacing=.4f)
                }
            }
        }
    }
}

@Composable internal fun TemplateShelf(content:@Composable RowScope.()->Unit) {
    val t=LocalYohaku.current
    Row(Modifier.fillMaxWidth().testTag("home-templates").horizontalScroll(rememberScrollState()).padding(vertical=if(t.id=="journal")4.dp else 2.dp,horizontal=if(t.id=="objects")1.dp else 0.dp),
        horizontalArrangement=Arrangement.spacedBy(if(t.id=="magazine")0.dp else if(t.id=="journal")14.dp else 10.dp),verticalAlignment=Alignment.Top,content=content)
}

/** Amount hero of a scheduled bill: magazine headline figure, journal stamped receipt, objects ticket stub. */
@Composable internal fun ScheduledBillHero(amount:String,known:Boolean,date:String,status:String,modifier:Modifier=Modifier) {
    val t=LocalYohaku.current
    when(t.id) {
        "journal"->Box(modifier.fillMaxWidth().padding(vertical=6.dp)) {
            Column {
                YText(word("这次要付","今回の支払い"),12,color=t.muted)
                YText(amount,if(known)40 else 26,Modifier.padding(top=2.dp).drawBehind{
                    val y=size.height-6.dp.toPx()
                    drawLine(t.warm.copy(alpha=.55f),Offset(0f,y),Offset(size.width,y-2.dp.toPx()),2.dp.toPx(),cap=StrokeCap.Round)
                },number=known,color=t.ink)
                YText(date,13,Modifier.padding(top=6.dp),color=t.accent)
            }
            // A slightly crooked rubber stamp in red ink.
            Box(Modifier.align(Alignment.TopEnd).padding(top=4.dp,end=2.dp).rotate(-11f).drawBehind{
                drawRoundRect(t.warm,style=Stroke(1.6.dp.toPx()),cornerRadius=CornerRadius(4.dp.toPx()))
                drawRoundRect(t.warm,Offset(3.dp.toPx(),3.dp.toPx()),Size(size.width-6.dp.toPx(),size.height-6.dp.toPx()),CornerRadius(2.dp.toPx()),style=Stroke(.8.dp.toPx()))
            }.padding(horizontal=12.dp,vertical=7.dp)){YText(status,13,Modifier.testTag("scheduled-bill-stamp"),color=t.warm)}
        }
        "objects"->{
            val shape=RoundedCornerShape(16.dp)
            Box(modifier.fillMaxWidth().padding(bottom=12.dp).drawBehind{
                drawRoundRect(Color(0x55e8decd),Offset(0f,3.dp.toPx()),cornerRadius=CornerRadius(16.dp.toPx()))
            }.background(Color(0xfffff3d8),shape).drawBehind{
                // Ticket stub: punched notches and a perforated tear line.
                val x=size.width*.68f;val r=7.dp.toPx()
                drawCircle(t.bg,r,Offset(x,0f));drawCircle(t.bg,r,Offset(x,size.height))
                drawLine(Color(0x55b77a52),Offset(x,r+3.dp.toPx()),Offset(x,size.height-r-3.dp.toPx()),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),4.dp.toPx())))
            }.padding(vertical=18.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(.68f).padding(horizontal=18.dp)) {
                        YText(word("本次金额","今回の金額"),11,color=t.muted)
                        YText(amount,if(known)30 else 22,Modifier.padding(top=4.dp),number=known)
                    }
                    Column(Modifier.weight(.32f).padding(horizontal=12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        YText(date.take(4),10,color=t.muted,number=true)
                        YText(date.takeLast(5).replace('-','/'),17,number=true,color=t.accent)
                        YText(status,10,Modifier.padding(top=6.dp).testTag("scheduled-bill-stamp").background(t.soft,CircleShape).padding(horizontal=9.dp,vertical=3.dp),color=t.accent)
                    }
                }
            }
        }
        else->Column(modifier.fillMaxWidth().padding(top=4.dp,bottom=8.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                YText("SCHEDULED",9,color=t.muted,letterSpacing=2f)
                Box(Modifier.padding(horizontal=10.dp).width(18.dp).height(1.dp).background(t.line))
                YText(date,10,color=t.muted,number=true,letterSpacing=.6f)
            }
            YText(amount,if(known)44 else 28,Modifier.padding(top=8.dp),number=known,lineHeightMultiplier=1.15f)
            Row(Modifier.padding(top=10.dp),verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(t.accent))
                YText(status,11,Modifier.padding(start=8.dp).testTag("scheduled-bill-stamp"),color=t.accent,letterSpacing=.6f)
            }
        }
    }
}
