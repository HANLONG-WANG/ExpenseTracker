package com.yohaku.expensetracker.platform.desktop

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.yohaku.expensetracker.MainActivity

/** templateId=null 为首页，空字符串为模板管理，其余为模板 ID。 */
data class DesktopTarget(val bookId:String,val templateId:String?)
object DesktopIntent {
    const val ACTION="com.yohaku.expensetracker.OPEN_DESKTOP"
    const val BOOK="desktop_book"
    const val TEMPLATE="desktop_template"
    fun open(context:Context,book:String,template:String?=null)=Intent(context,MainActivity::class.java)
        .setAction(ACTION).setData(Uri.Builder().scheme("yohaku").authority("desktop").appendPath(book).appendPath(template?:"home").build())
        .putExtra(BOOK,book).apply{if(template!=null)putExtra(TEMPLATE,template)}
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    fun consume(intent:Intent):DesktopTarget? {
        if(intent.action!=ACTION)return null
        val target=intent.getStringExtra(BOOK)?.let{DesktopTarget(it,intent.getStringExtra(TEMPLATE))}
        intent.removeExtra(BOOK);intent.removeExtra(TEMPLATE)
        return target
    }
}
