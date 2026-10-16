package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yohaku.expensetracker.ui.theme.*

private val tapeShape=GenericShape{size,_->
    moveTo(size.width*.02f,0f);lineTo(size.width*.97f,0f);lineTo(size.width,size.height*.25f)
    lineTo(size.width*.97f,size.height*.5f);lineTo(size.width,size.height*.75f);lineTo(size.width*.98f,size.height)
    lineTo(size.width*.01f,size.height);lineTo(size.width*.03f,size.height*.67f);lineTo(0f,size.height*.4f);close()
}

// Page columns supply 15dp. Only the remainder of collapsed HTML margins belongs here.
// SetupProgress supplies 7dp below itself, so its following cover contributes another 3dp.
@Composable fun YLead(title:String,description:String,kicker:String,amount:String="",aside:String="",amountIsText:Boolean=false,pageFlow:Boolean=false,afterSetupProgress:Boolean=false,wideTitle:Boolean=false) {
    val t=LocalYohaku.current
    val narrow=LocalConfiguration.current.screenWidthDp<=360
    val editionHeight=with(LocalDensity.current){11.sp.toDp()}
    when(t.id){
        "magazine"->Column(Modifier.fillMaxWidth().padding(top=if(pageFlow)if(afterSetupProgress)3.dp else 0.dp else 18.dp,bottom=if(pageFlow)10.dp else 25.dp).testTag("lead-cover")){
            Box(Modifier.fillMaxWidth().height(2.dp).background(t.ink))
            Column(Modifier.padding(top=18.dp,bottom=22.dp)){
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                    YText(kicker,8,Modifier.height(editionHeight).wrapContentHeight(Alignment.CenterVertically,unbounded=true),color=t.muted,letterSpacing=1.2f,lineHeightMultiplier=11f/8)
                    YText("YOHAKU / 05",8,Modifier.height(editionHeight).wrapContentHeight(Alignment.CenterVertically,unbounded=true),color=t.muted,letterSpacing=1.2f,lineHeightMultiplier=11f/8)
                }
                if(wideTitle)YText(title,if(narrow)25 else 28,Modifier.fillMaxWidth().padding(top=10.dp).testTag("lead-title"),letterSpacing=-.6f,lineHeightMultiplier=1.6f)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(if(narrow)11.dp else 17.dp),verticalAlignment=Alignment.Bottom){
                    Column(Modifier.weight(1f).padding(top=10.dp)){
                        if(!wideTitle)YText(title,if(narrow)25 else 28,Modifier.testTag("lead-title"),letterSpacing=-.6f,lineHeightMultiplier=1.6f)
                        YText(description,11,Modifier.padding(top=10.dp,bottom=if(amount.isEmpty())10.dp else 0.dp).testTag("lead-description"),color=t.muted,lineHeightMultiplier=2f)
                        if(amount.isNotEmpty())LeadAmount(amount,if(narrow)30 else 35,if(amountIsText)if(narrow)44f else 50f else if(narrow)35f else 40f,amountIsText,Modifier.padding(top=17.dp))
                    }
                    Column(Modifier.width(if(narrow)55.dp else 75.dp),horizontalAlignment=Alignment.End){
                        ThemeArtwork(width=if(narrow)54 else 74,height=if(narrow)77 else 94)
                        if(aside.isNotEmpty())YText(aside,8,Modifier.padding(top=15.dp).testTag("lead-aside"),color=t.muted,lineHeightMultiplier=1.9f,cssLineBox=true)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(t.ink))
        }
        "journal"->Box(Modifier.fillMaxWidth().padding(top=if(pageFlow)if(afterSetupProgress)3.dp else 0.dp else 18.dp,bottom=if(pageFlow)10.dp else 25.dp).rotate(-.4f).testTag("lead-cover")){
            Box(Modifier.matchParentSize().offset(x=2.dp,y=3.dp).background(Color(0x188b7859)))
            Box(Modifier.fillMaxWidth().background(Color(0xfffff8e7)).drawBehind{
                var y=size.height-32.dp.toPx()
                while(y>0){drawLine(Color(0x13718695),Offset(0f,y),Offset(size.width,y),1.dp.toPx());y-=32.dp.toPx()}
            }.border(1.dp,t.line).padding(1.dp)){
                ThemeArtwork(Modifier.align(Alignment.BottomEnd).offset(x=(-12).dp,y=(-14).dp).alpha(.15f),100,89)
                BoxWithConstraints(Modifier.fillMaxWidth()){
                    Box(Modifier.offset(x=maxWidth*.31f,y=(-8).dp).size(88.dp,17.dp).rotate(-3f).background(Color(0x80d6bf86),tapeShape))
                    Column(Modifier.padding(start=17.dp,end=17.dp,top=25.dp,bottom=23.dp)){
                        YText("✳ $kicker",11,color=t.warm,lineHeightMultiplier=16f/11)
                        YText(title,if(narrow)25 else 28,Modifier.padding(top=10.dp).testTag("lead-title"),letterSpacing=-.6f,lineHeightMultiplier=1.5f)
                        YText(description,12,Modifier.padding(top=10.dp,bottom=if(amount.isEmpty())10.dp else 0.dp).testTag("lead-description"),color=t.muted,lineHeightMultiplier=1.9f)
                        if(amount.isNotEmpty())LeadAmount(amount,if(narrow)30 else 39,if(narrow)44f else 56f,amountIsText,Modifier.fillMaxWidth().padding(top=24.dp),t.accent,arrow=true)
                        if(aside.isNotEmpty())YText(aside,11,Modifier.padding(top=13.dp).drawBehind{drawLine(Color(0x49be8778),Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())},color=t.warm,lineHeightMultiplier=1.9f)
                    }
                }
            }
        }
        else->{
            val shape=RoundedCornerShape(22.dp,22.dp,32.dp,22.dp)
            Box(Modifier.fillMaxWidth().padding(top=if(pageFlow)if(afterSetupProgress)3.dp else 0.dp else 18.dp,bottom=if(pageFlow)10.dp else 25.dp).testTag("lead-cover")){
                Box(Modifier.matchParentSize().offset(y=4.dp).background(Color(0x2bc6aa80),shape))
                Box(Modifier.fillMaxWidth().background(Color(0xffeee1c8),shape)){
                    ThemeArtwork(Modifier.align(Alignment.BottomEnd).offset(x=(-10).dp,y=(-14).dp).alpha(.16f),109,91,Color(0xffa7784e))
                    Column(Modifier.padding(start=20.dp,end=20.dp,top=25.dp,bottom=23.dp)){
                        YText(kicker,8,Modifier.drawBehind{drawRoundRect(Color(0xffb78a62),Offset((-10).dp.toPx(),0f),androidx.compose.ui.geometry.Size(5.dp.toPx(),12.dp.toPx()),androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))},color=Color(0xffac8860),letterSpacing=1.4f,lineHeightMultiplier=19f/8)
                        YText(title,if(narrow)25 else 26,Modifier.fillMaxWidth(.95f).padding(top=10.dp).testTag("lead-title"),weight=FontWeight.Medium,lineHeightMultiplier=1.5f,letterSpacing=-.6f)
                        YText(description,11,Modifier.padding(top=10.dp,bottom=if(amount.isEmpty())10.dp else 0.dp).testTag("lead-description"),color=t.muted,lineHeightMultiplier=1.9f)
                        if(amount.isNotEmpty())LeadAmount(amount,if(narrow)30 else 37,if(narrow)44f else 54f,amountIsText,Modifier.padding(top=17.dp),t.accent)
                        if(aside.isNotEmpty())YText(aside,9,Modifier.padding(top=17.dp).background(Color(0xfffffae9),RoundedCornerShape(6.dp)).padding(horizontal=11.dp,vertical=7.dp),color=Color(0xffa0815a),lineHeightMultiplier=1.9f)
                    }
                }
            }
        }
    }
}

@Composable private fun LeadAmount(text:String,fontSize:Int,line:Float,textRole:Boolean,modifier:Modifier=Modifier,color:Color=LocalYohaku.current.ink,arrow:Boolean=false) {
    var lines by remember(text,fontSize){mutableIntStateOf(1)}
    val height=with(LocalDensity.current){line.sp.toDp()*lines}
    val frame=modifier.height(height).wrapContentHeight(Alignment.CenterVertically,unbounded=true).testTag("lead-amount")
    if(arrow){
        val t=LocalYohaku.current
        val rich=buildAnnotatedString{
            append(text)
            withStyle(SpanStyle(fontSize=18.sp,letterSpacing=0.sp)){append(" ")}
            withStyle(SpanStyle(fontSize=26.sp,fontFamily=t.body,color=t.warm)){append("↗")}
        }
        androidx.compose.material3.Text(rich,frame,style=TextStyle(color=color,fontSize=fontSize.sp,fontFamily=if(textRole)t.body else t.number,fontWeight=FontWeight.Normal,letterSpacing=(-1).sp,lineHeight=line.sp),onTextLayout={lines=it.lineCount})
    }else YText(text,fontSize,frame,color=color,number=!textRole,letterSpacing=-1f,lineHeightMultiplier=line/fontSize,onTextLayout={lines=it.lineCount})
}
