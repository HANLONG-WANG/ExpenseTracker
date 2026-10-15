package com.yohaku.expensetracker.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import android.graphics.Typeface
import android.graphics.fonts.Font as NativeFont
import android.graphics.fonts.FontFamily as NativeFamily
import android.content.Context
import com.yohaku.expensetracker.R

data class ThemeStyle(val id:String,val bg:Color,val surface:Color,val ink:Color,val muted:Color,val accent:Color,val soft:Color,val line:Color,val warm:Color,
    val body:FontFamily,val display:FontFamily,val number:FontFamily,val radius:Int)
private val editorial=FontFamily(Font(R.font.editorial))
private val hand=FontFamily(Font(R.font.hand_full))
private val round=FontFamily(Font(R.font.round_full))
val Magazine=ThemeStyle("magazine",Color(0xfff7f7ef),Color(0xfffffef8),Color(0xff263d32),Color(0xff788577),Color(0xff355b43),Color(0xffe8eddf),Color(0xffd2dacc),Color(0xffbc664f),editorial,editorial,FontFamily(Font(R.font.editorial_number)),0)
val Journal=ThemeStyle("journal",Color(0xfff1e8d5),Color(0xfffaf5e7),Color(0xff354d61),Color(0xff898679),Color(0xff3b5b70),Color(0xffe6e6d9),Color(0xffd4cbb5),Color(0xffae6657),hand,hand,hand,2)
val Objects=ThemeStyle("objects",Color(0xfffaf1e5),Color(0xfffffcf5),Color(0xff634b3b),Color(0xff9c8976),Color(0xffae6246),Color(0xfff0e3cc),Color(0xffe9d8be),Color(0xffb56340),round,round,round,22)
val LocalYohaku=staticCompositionLocalOf{Magazine}
val LocalLanguage=staticCompositionLocalOf{"zh"}
@Composable fun word(zh:String,ja:String)=if(LocalLanguage.current=="ja")ja else zh
private fun fallback(context:Context,vararg resources:Int):FontFamily {
    val families=resources.map { id->
        val font=NativeFont.Builder(context.resources,id).setWeight(400)
        if(id==R.font.editorial || id==R.font.round_cn)font.setFontVariationSettings("'wght' 400")
        NativeFamily.Builder(font.build()).build()
    }
    val builder=Typeface.CustomFallbackBuilder(families.first())
    families.drop(1).forEach{builder.addCustomFallback(it)}
    return FontFamily(builder.build())
}
/** Native fallback builders allocate direct font buffers. Reuse them across activities. */
private object ThemeFonts {
    private var cached:List<ThemeStyle>?=null
    @Synchronized fun styles(context:Context):List<ThemeStyle> {
        cached?.let{return it}
        val app=context.applicationContext
        val serif=fallback(app,R.font.editorial,R.font.editorial_full)
        val editorialNumbers=fallback(app,R.font.editorial_number,R.font.editorial,R.font.editorial_full)
        val ink=fallback(app,R.font.hand,R.font.hand_cn,R.font.hand_full,R.font.hand_cn_full,R.font.journal_check,R.font.journal_star)
        val rounded=fallback(app,R.font.round,R.font.round_cn,R.font.round_full,R.font.round_cn_full)
        return listOf(Magazine.copy(body=serif,display=serif,number=editorialNumbers),Journal.copy(body=ink,display=ink,number=ink),Objects.copy(body=rounded,display=rounded,number=rounded)).also{cached=it}
    }
}
@Composable fun yohakuStyle(theme:String):ThemeStyle {
    val context=LocalContext.current
    val styles=remember { ThemeFonts.styles(context) }
    return styles.firstOrNull{it.id==theme} ?: styles.first()
}
@Composable fun YohakuTheme(theme:String,language:String,content:@Composable () -> Unit) {
    val style=yohakuStyle(theme)
    CompositionLocalProvider(LocalYohaku provides style,LocalLanguage provides language,LocalTextSelectionColors provides TextSelectionColors(handleColor=style.accent,backgroundColor=style.accent.copy(alpha=.28f))) {
        Box(Modifier.fillMaxSize().background(style.bg)) {
            if(theme=="journal") Canvas(Modifier.fillMaxSize()) {
                // Final body grain and transparent app-shell layers, both 0deg CSS gradients.
                val stripe=1.dp.toPx()
                var grainBottom=size.height
                while(grainBottom>0){
                    drawRect(Color(0x10785c34),Offset(0f,grainBottom-stripe),Size(size.width,stripe))
                    grainBottom-=4.dp.toPx()
                }
                var ruleTop=size.height-30.dp.toPx()
                while(ruleTop+stripe>0){
                    drawRect(Color(0x12846d36),Offset(0f,ruleTop),Size(size.width,stripe))
                    ruleTop-=30.dp.toPx()
                }
                drawRect(Color(0x32b5695c),Offset(18.dp.toPx(),0f),Size(stripe,size.height))
            }
            content()
        }
    }
}
