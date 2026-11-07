package com.yohaku.expensetracker.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import com.yohaku.expensetracker.R

/** Compose 与桌面共用原生回退链；缓存直接字体缓冲区，避免重复分配。 */
object ThemeTypefaces {
    private val cache=mutableMapOf<String,Typeface>()
    fun body(context:Context,theme:String)=get(context,theme,false)
    fun number(context:Context,theme:String)=get(context,theme,true)
    @Synchronized private fun get(context:Context,theme:String,number:Boolean):Typeface {
        val key=if(theme=="magazine"&&number)"magazine-number" else theme
        return cache.getOrPut(key) {
            val resources=when(key) {
                "journal"->listOf(R.font.journal_math,R.font.hand,R.font.hand_cn,R.font.hand_full,R.font.hand_cn_full,R.font.journal_check,R.font.journal_star)
                "objects"->listOf(R.font.round,R.font.round_cn,R.font.round_full,R.font.round_cn_full)
                "magazine-number"->listOf(R.font.editorial_number,R.font.editorial,R.font.editorial_full)
                else->listOf(R.font.editorial,R.font.editorial_full)
            }
            val families=resources.map { id->
                val font=Font.Builder(context.applicationContext.resources,id).setWeight(400)
                if(id==R.font.editorial||id==R.font.round_cn)font.setFontVariationSettings("'wght' 400")
                FontFamily.Builder(font.build()).build()
            }
            Typeface.CustomFallbackBuilder(families.first()).apply{families.drop(1).forEach{addCustomFallback(it)}}.build()
        }
    }
}
