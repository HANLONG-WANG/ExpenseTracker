package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.data.repository.BookSnapshot
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun SettingsScreen(s:BookSnapshot,onTheme:(String)->Unit,onLanguage:()->Unit,onCategories:()->Unit,onBooks:()->Unit,onTrash:()->Unit,onRename:()->Unit) {
    val t=LocalYohaku.current
    YTitle(word("让它，像你的生活。","自分らしい、暮らしに。"),"SETTINGS",pageFlow=true)
    Panel(Modifier.testTag("settings-theme-panel"),contentSpacing=0.dp) {
        YText(word("外观主题","テーマ"),when(t.id){"journal"->18;"objects"->16;else->17},Modifier.padding(bottom=14.dp),weight=if(t.id=="objects")FontWeight.Medium else FontWeight.Normal,lineHeightMultiplier=when(t.id){"journal"->26f/18;"objects"->1.5f;else->25f/17})
        listOf("magazine","journal","objects").forEach{id->
            val name=when(id){"magazine"->word("现代生活杂志","ライフスタイル誌");"journal"->word("纸质手账","紙の手帳");else->word("日常小物","暮らしの小物")}
            val description=when(id){"magazine"->word("松绿与留白 · 编辑式排版","深緑と余白・誌面のように");"journal"->word("纸页与墨蓝 · 手写批注感","紙と青いインク・手帳の温もり");else->word("陶土橙与黄油色 · 日常小物","テラコッタとバター色・暮らしの小物")}
            val selected=id==s.book.theme
            Column(Modifier.fillMaxWidth().testTag("theme-choice-"+id).selectable(selected=selected,role=Role.RadioButton,onClick={onTheme(id)})){
                Row(Modifier.fillMaxWidth().padding(vertical=when(t.id){"magazine"->18.dp;"journal"->17.dp;else->15.dp}),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(13.dp)){
                    ThemePreview(id)
                    Column(Modifier.weight(1f)){
                        YText(name,if(t.id=="journal")16 else 14,color=t.ink,weight=if(t.id=="objects")FontWeight.Medium else FontWeight.Normal,lineHeightMultiplier=if(t.id=="journal")1.5f else 20f/14)
                        YText(description,if(t.id=="journal")10 else if(t.id=="magazine")8 else 9,Modifier.padding(top=6.dp),color=t.muted,lineHeightMultiplier=1.7f)
                    }
                    Box(Modifier.width(23.dp)){if(selected)YIcon("check",tint=t.accent)}
                }
                SettingsRule()
            }
        }
    }
    Panel(contentSpacing=0.dp) {
        SettingsRow(word("独立账本管理","独立した帳簿"),onBooks)
        SettingsRow(word("账本名称与资料","帳簿情報"),onRename)
        SettingsRow(word("两级分类管理","カテゴリ管理"),onCategories)
        SettingsRow(word("回收站 · 30天","ごみ箱 · 30日"),onTrash)
        SettingsRow(word("切换为日语","中国語へ切替"),onLanguage,"settings-language",arrow=false)
    }
    YText("余量 / ヨハク / YOHAKU",12,color=t.muted)
}

@Composable private fun SettingsRow(label:String,onClick:()->Unit,tag:String=label,arrow:Boolean=true) {
    val t=LocalYohaku.current
    Column(Modifier.fillMaxWidth().testTag(tag).clickable(role=Role.Button,onClick=onClick)){
        Row(Modifier.fillMaxWidth().padding(vertical=if(t.id=="objects")18.dp else 17.dp).heightIn(min=when(t.id){"journal"->if(arrow)21.dp else 20.dp;"objects"->if(arrow)20.dp else 19.dp;else->if(arrow)17.dp else 16.dp}),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
            val size=when(t.id){"journal"->15;"objects"->14;else->12}
            YText(label,size,Modifier.weight(1f),lineHeightMultiplier=when(t.id){"journal"->20f/15;"objects"->19f/14;else->16f/12})
            if(arrow)YIcon("arrow",Modifier.size(17.dp))
        }
        SettingsRule()
    }
}

@Composable private fun SettingsRule() {
    val t=LocalYohaku.current
    Canvas(Modifier.fillMaxWidth().height(1.dp)){drawLine(t.line,Offset.Zero,Offset(size.width,0f),1.dp.toPx(),pathEffect=if(t.id!="magazine")PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())) else null)}
}

/** The number is decorative type in the theme thumbnail, not ledger data. */
@Composable private fun ThemePreview(id:String) {
    val style=yohakuStyle(id)
    val density=LocalDensity.current
    // The thumbnail is a fixed-size decorative drawing, excluded from semantics.
    CompositionLocalProvider(LocalYohaku provides style,LocalDensity provides Density(density.density,1f)){
        val frame=when(id){
            "magazine"->Modifier.background(Color(0xfff5f5ee)).border(1.dp,Color(0xffdbe0d2))
            "journal"->Modifier.rotate(-3f).background(Color(0xfffaf5e9)).border(1.dp,Color(0xffddd1b4))
            else->Modifier.background(Color(0xfff0deb8),RoundedCornerShape(12.dp,12.dp,22.dp,12.dp))
        }
        Box(Modifier.size(73.dp,70.dp).clearAndSetSemantics{}.then(frame)){
            if(id=="journal"){
                Canvas(Modifier.matchParentSize()){var y=10.dp.toPx();while(y<size.height){drawLine(Color(0xffe7deca),Offset(0f,y),Offset(size.width,y),1.dp.toPx());y+=11.dp.toPx()}}
                Box(Modifier.offset(x=23.dp,y=(-4).dp).size(27.dp,9.dp).rotate(6f).background(Color(0x99b3bba7)))
            }
            val ink=when(id){"magazine"->Color(0xff345b46);"journal"->Color(0xff3b5b70);else->Color(0xffaa5d41)}
            Column(Modifier.padding(horizontal=if(id=="objects")8.dp else 9.dp,vertical=if(id=="objects")12.dp else 13.dp)){
                Box(Modifier.fillMaxWidth().height(1.dp).background(ink.copy(alpha=.7f)))
                YText("86,180",if(id=="objects")15 else 17,Modifier.padding(vertical=8.dp),number=true,color=ink,letterSpacing=-.7f,lineHeightMultiplier=when(id){"journal"->25f/17;"objects"->21f/15;else->20f/17},weight=if(id=="objects")FontWeight.Medium else FontWeight.Normal)
                Box(Modifier.size(36.dp,4.dp).background(ink.copy(alpha=.6f)))
            }
        }
    }
}
