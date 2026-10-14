package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

/** capture.js capture-cover: typography struts, original motif and independent composition. */
@Composable internal fun CategoryCover(){
    val t=LocalYohaku.current
    when(t.id){
        "magazine"->BoxWithConstraints(Modifier.fillMaxWidth().testTag("category-cover").heightIn(min=180.dp).drawBehind{drawLine(t.ink,Offset(0f,size.height-.5.dp.toPx()),Offset(size.width,size.height-.5.dp.toPx()),1.dp.toPx())}){
            val textWidth=maxWidth*.75f
            Column(Modifier.fillMaxWidth().padding(top=9.dp,bottom=21.dp)){
                YText("01 / CHOOSE A CATEGORY",8,Modifier.testTag("category-cover-caption"),color=t.muted,letterSpacing=1.4f,lineHeightMultiplier=19f/8,cssLineBox=true)
                YText(word("这一笔，记在哪里。","この一筆は、どこへ。"),29,Modifier.width(textWidth).padding(top=12.dp).testTag("category-cover-title"),letterSpacing=-.6f,lineHeightMultiplier=1.65f,cssLineBox=true)
                YText(word("先选用途，再记金额。","使い道を選んで、金額を記す。"),11,Modifier.padding(top=12.dp,bottom=11.dp).testTag("category-cover-description"),color=t.muted,lineHeightMultiplier=1.9f,cssLineBox=true)
            }
            AnalysisArtwork(Modifier.align(Alignment.BottomEnd).offset(x=(-3).dp,y=(-24).dp).size(62.dp,96.dp).testTag("category-cover-art"))
        }
        "journal"->BoxWithConstraints(Modifier.fillMaxWidth().testTag("category-cover").rotate(-.7f).background(Color(0xfffff9e9)).border(1.dp,t.line)){
            Canvas(Modifier.matchParentSize()){
                var y=size.height-2.dp.toPx()
                while(y+1.dp.toPx()>0){drawRect(Color(0x1b718695),Offset(0f,y),androidx.compose.ui.geometry.Size(size.width,1.dp.toPx()));y-=32.dp.toPx()}
            }
            Column(Modifier.fillMaxWidth().padding(start=20.dp,end=20.dp,top=26.dp,bottom=23.dp)){
                YText(word("第 1 步 · 选一枚分类","ステップ1・カテゴリ選び"),12,Modifier.testTag("category-cover-caption"),color=t.warm,lineHeightMultiplier=20f/12,cssLineBox=true)
                YText(word("今天的小花费，\n写进哪一页？","今日の、小さな支出。\nどのページに書こう？"),27,Modifier.padding(top=12.dp,bottom=12.dp).testTag("category-cover-title"),letterSpacing=-.6f,lineHeightMultiplier=1.5f,cssLineBox=true)
            }
            Box(Modifier.offset(x=(maxWidth-2.dp)*.27f+1.dp,y=(-7).dp).size(91.dp,19.dp).rotate(-3f).background(Color(0x80d5bd85)))
            YText("✳",33,Modifier.align(Alignment.BottomEnd).padding(end=20.dp,bottom=18.dp).rotate(-15f),color=t.warm,lineHeightMultiplier=48f/33,cssLineBox=true)
        }
        else->BoxWithConstraints(Modifier.fillMaxWidth().testTag("category-cover")){
            val textWidth=(maxWidth-42.dp)*.75f
            val shape=RoundedCornerShape(23.dp,23.dp,36.dp,36.dp)
            Box(Modifier.matchParentSize().offset(y=4.dp).background(Color(0x30be9b63),shape))
            Box(Modifier.fillMaxWidth().heightIn(min=175.dp).background(Color(0xffeee5cd),shape)){
                Column(Modifier.fillMaxWidth().padding(start=21.dp,end=21.dp,top=23.dp,bottom=20.dp)){
                    YText("MY DAILY POCKETS",8,Modifier.testTag("category-cover-caption"),color=Color(0xff9b835c),letterSpacing=1.5f,lineHeightMultiplier=19f/8,cssLineBox=true)
                    YText(word("放进合适的小口袋。","ぴったりのポケットへ。"),24,Modifier.width(textWidth).padding(top=12.dp).testTag("category-cover-title"),weight=FontWeight.Medium,letterSpacing=-.6f,lineHeightMultiplier=1.5f,cssLineBox=true)
                    YText(word("选好分类，就可以记一笔。","カテゴリを選んで、一筆を。"),11,Modifier.padding(top=12.dp,bottom=11.dp).testTag("category-cover-description"),color=t.muted,lineHeightMultiplier=1.9f,cssLineBox=true)
                }
                AnalysisArtwork(Modifier.align(Alignment.BottomEnd).offset(x=(-11).dp,y=(-16).dp).size(82.dp,69.dp).testTag("category-cover-art"))
            }
        }
    }
}

@Composable internal fun CategorySectionCaption(){
    val t=LocalYohaku.current
    Box(Modifier.fillMaxWidth().padding(bottom=7.dp)){
        YText(word("我的分类","マイカテゴリ"),if(t.id=="journal")15 else 12,Modifier.testTag("category-section-caption"),lineHeightMultiplier=if(t.id=="journal")21f/15 else 17f/12,cssLineBox=true)
    }
}
