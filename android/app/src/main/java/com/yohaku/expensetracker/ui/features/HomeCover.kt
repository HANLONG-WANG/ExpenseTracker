package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import java.time.LocalDate

@Composable internal fun HomeCover(currency:Currency,budget:Long,spent:Long) {
    val t=LocalYohaku.current
    val narrow=LocalConfiguration.current.screenWidthDp<=360
    val left=budget-spent
    when(t.id){
        "magazine"->Column(Modifier.fillMaxWidth().testTag("home-cover")){
            CoverRule(t.ink,2)
            Column(Modifier.padding(top=19.dp,bottom=19.dp)){
                YText("THE MONTHLY ALLOWANCE",8,Modifier.testTag("cover-caption"),color=t.muted,letterSpacing=1.6f,lineHeightMultiplier=19f/8)
                YText(word("日常，也有余地。","日々に、余白を。"),if(narrow)25 else 32,Modifier.padding(top=12.dp).testTag("cover-title"),letterSpacing=-1f,lineHeightMultiplier=1.6f)
                Row(Modifier.fillMaxWidth().padding(top=24.dp,bottom=24.dp).testTag("cover-grid"),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(if(narrow)8.dp else 14.dp)){
                    Column(Modifier.weight(1f)){
                        YText(word("本月预算内还能花","今月の予算内で使える"),10,color=t.muted,lineHeightMultiplier=1.9f)
                        Box(Modifier.padding(top=14.dp)){CoverMoney(currency,left,if(narrow)36 else 46)}
                    }
                    Column(Modifier.testTag("cover-aside").width(if(narrow)68.dp else 90.dp).drawBehind{drawLine(t.line,Offset.Zero,Offset(0f,size.height),1.dp.toPx())}.padding(start=if(narrow)8.dp else 13.dp)){
                        ThemeArtwork(width=if(narrow)55 else 75,height=if(narrow)65 else 80)
                        Spacer(Modifier.height(4.dp))
                        YText(word("预留安排后","予定分を確保した後"),8,Modifier.padding(top=14.dp),color=t.muted,lineHeightMultiplier=1.9f)
                        CoverLineText(if(currency==Currency.JPY)"¥" else currency.name,8,10.4f,Modifier.padding(top=7.dp,bottom=4.dp).testTag("cover-side-prefix"),color=t.muted,letterSpacing=.5f)
                        YText(Money(currency,left).digits(),if(narrow)14 else 16,number=true,letterSpacing=if(narrow)-.4f else -.2f,lineHeightMultiplier=if(narrow)30.4f/14 else 36.1f/16)
                    }
                }
                CoverRule(t.line)
                Row(Modifier.fillMaxWidth().testTag("cover-footer").padding(top=18.dp),horizontalArrangement=Arrangement.SpaceBetween){
                    YText(word("预算 ","予算 ")+Money(currency,budget).display(),9,Modifier.testTag("cover-footer-text"),color=t.muted,lineHeightMultiplier=13f/9)
                    YText(word("预算内已花 ","予算内の支出 ")+Money(currency,spent).display(),9,color=t.muted,lineHeightMultiplier=13f/9)
                }
            }
            CoverRule(t.ink)
        }
        "journal"->Box(Modifier.fillMaxWidth().testTag("home-cover").rotate(-.6f).background(Color(0xfffff8e7)).border(1.dp,t.line).padding(1.dp)){
            Canvas(Modifier.matchParentSize()){var y=size.height;while(y>0){drawLine(Color(0x15718695),Offset(0f,y),Offset(size.width,y),1.dp.toPx());y-=32.dp.toPx()}}
            Box(Modifier.align(Alignment.TopCenter).offset(y=(-8).dp).size(88.dp,17.dp).rotate(-3f).background(Color(0x80d6bf86)))
            Column(Modifier.padding(start=18.dp,end=18.dp,top=26.dp,bottom=20.dp)){
                val month=LocalDate.now().monthValue
                val zhMonth=listOf("一","二","三","四","五","六","七","八","九","十","十一","十二")[month-1]
                YText(word("${zhMonth}月的生活小算式","${month}月の暮らしの計算"),12,color=t.warm,lineHeightMultiplier=20f/12)
                YText(word("今天，也慢慢记。","今日も、ゆっくり記そう。"),if(narrow)25 else 27,Modifier.padding(top=12.dp),lineHeightMultiplier=1.6f)
                FlowRow(Modifier.padding(top=25.dp,bottom=16.dp),horizontalArrangement=Arrangement.spacedBy(if(narrow)7.dp else 9.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){
                    YText(Money(currency,budget).display(),if(narrow)20 else 24,number=true,color=t.muted,lineHeightMultiplier=35f/24)
                    YText("−",19,color=t.muted)
                    YText(Money(currency,spent).display(),if(narrow)20 else 24,number=true,color=t.muted,lineHeightMultiplier=35f/24)
                    YText("＝",19,color=t.muted)
                }
                Box(Modifier.fillMaxWidth().rotate(-2f)){CoverMoney(currency,left,49)}
                YText(word("本月预算内还能花","今月の予算内で使える")+" ↗",13,Modifier.fillMaxWidth().padding(top=10.dp),color=t.warm,lineHeightMultiplier=19f/13,textAlign=androidx.compose.ui.text.style.TextAlign.End)
                Spacer(Modifier.height(20.dp))
                CoverRule(t.line,dashed=true)
                YText(word("再留出计划支出 ","予定支出も確保して ")+Money(currency,0).display(),12,Modifier.padding(top=16.dp),color=t.muted,lineHeightMultiplier=1.5f)
                YText(word("自由可花 ","自由に使える ")+Money(currency,left).display(),23,Modifier.padding(top=9.dp),color=t.accent,number=true,lineHeightMultiplier=33f/23)
            }
        }
        else->{
            val shape=RoundedCornerShape(25.dp,25.dp,53.dp,37.dp)
            Box(Modifier.fillMaxWidth().padding(top=6.dp).testTag("home-cover").drawBehind{
                val outline=shape.createOutline(size,layoutDirection,this);val dy=5.dp.toPx()
                withTransform({translate(0f,dy)}){drawOutline(outline,Color(0x2cc5a06e))}
            }.background(Color(0xffe9d3ad),shape).border(1.dp,Color(0xffdfc395),shape)){
                Box(Modifier.align(Alignment.TopCenter).offset(y=(-7).dp).fillMaxWidth(.7f).height(14.dp).background(Color(0xffbd9368),RoundedCornerShape(6.dp)))
                Column(Modifier.padding(start=21.dp,end=21.dp,top=36.dp,bottom=27.dp)){
                    YText(word("这个月的日常钱袋","今月の日々のお財布"),10,color=Color(0xffa08056),lineHeightMultiplier=1.9f)
                    YText(word("留一点，给明天。","明日に、少し残そう。"),if(narrow)25 else 26,Modifier.padding(top=12.dp),lineHeightMultiplier=1.6f)
                    Box(Modifier.padding(top=18.dp,bottom=7.dp)){CoverMoney(currency,left,if(narrow)42 else 49)}
                    YText(word("本月预算内还能花","今月の予算内で使える"),10,color=Color(0xffa17f54),lineHeightMultiplier=1.9f)
                    Row(Modifier.fillMaxWidth().padding(top=25.dp),horizontalArrangement=Arrangement.spacedBy(14.dp)){
                        listOf(word("待付安排先留好","予定支払を確保") to 0L,word("自由可花","自由に使える") to left).forEach{(label,value)->
                            Column(Modifier.weight(1f).background(Color(0xfff9ebce),RoundedCornerShape(10.dp,10.dp,19.dp,13.dp)).padding(horizontal=11.dp,vertical=14.dp)){
                                YText(label,9,color=Color(0xffa48157),lineHeightMultiplier=13f/9)
                                YText(Money(currency,value).display(),20,Modifier.padding(top=9.dp),number=true,color=Color(0xff9d6c44),lineHeightMultiplier=1.45f)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun CoverMoney(currency:Currency,value:Long,size:Int) {
    val t=LocalYohaku.current
    val narrow=LocalConfiguration.current.screenWidthDp<=360
    val line=if(t.id=="magazine")size*52f/46 else size*71f/49
    val ink=if(t.id=="journal")t.accent else if(t.id=="objects")Color(0xffa86643) else t.ink
    if(currency==Currency.JPY)Row(Modifier.testTag("cover-main-money")) {
        CoverLineText("¥",(size*.48f).toInt(),size*.48f*1.14f,Modifier.alignByBaseline().padding(end=4.dp).testTag("cover-prefix"),number=true,color=ink)
        CoverLineText(Money(currency,value).digits(),size,line,Modifier.alignByBaseline().testTag("cover-value"),number=true,color=ink,letterSpacing=-1.5f)
    }else Column{
        YText(currency.name,if(narrow)10 else 12,Modifier.padding(vertical=4.dp),color=t.muted,letterSpacing=1f,lineHeightMultiplier=1.4f)
        val digitsSize=when(t.id){"magazine"->if(narrow)29 else 34;"journal"->if(narrow)38 else 43;else->if(narrow)40 else 44}
        YText(Money(currency,value).digits(),digitsSize,number=true,color=ink,lineHeightMultiplier=line/digitsSize,letterSpacing=-1.5f)
    }
}
/** CSS inline line-height can be below a font's natural box; glyphs may overflow it. */
@Composable private fun CoverLineText(text:String,size:Int,line:Float,modifier:Modifier=Modifier,number:Boolean=false,color:Color=LocalYohaku.current.ink,letterSpacing:Float=0f) {
    val height=with(LocalDensity.current){line.sp.toDp()}
    YText(text,size,modifier.height(height).wrapContentHeight(Alignment.CenterVertically,unbounded=true),number=number,color=color,letterSpacing=letterSpacing,lineHeightMultiplier=line/size)
}
@Composable private fun CoverRule(color:Color,width:Int=1,dashed:Boolean=false) {
    Canvas(Modifier.fillMaxWidth().height(width.dp)){drawLine(color,Offset.Zero,Offset(size.width,0f),width.dp.toPx(),pathEffect=if(dashed)PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())) else null)}
}
