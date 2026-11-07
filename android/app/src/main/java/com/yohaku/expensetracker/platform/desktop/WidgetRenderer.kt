package com.yohaku.expensetracker.platform.desktop

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.yohaku.expensetracker.R
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.category.resolveCategoryIcon
import com.yohaku.expensetracker.domain.model.Money
import com.yohaku.expensetracker.domain.model.Currency
import java.time.LocalDate
import java.time.YearMonth

internal class WidgetRenderer(private val context:Context) {
    fun render(id:Int,s:BookSnapshot?,today:LocalDate,appearance:Pair<String,String>,options:Bundle):RemoteViews {
        val minimum=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,160).coerceAtLeast(140)
        val maximum=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,minimum).coerceAtLeast(minimum)
        if(Build.VERSION.SDK_INT>=31) {
            val sizes=if(Build.VERSION.SDK_INT>=33)options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES,SizeF::class.java).orEmpty()
                else { @Suppress("DEPRECATION") options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES).orEmpty() }
            val small=sizes.filter{it.width<250}.minOfOrNull{it.width}?:minOf(minimum,170).toFloat()
            val wide=sizes.filter{it.width>=250}.minOfOrNull{it.width}?:maxOf(maximum,250).toFloat()
            return RemoteViews(mapOf(SizeF(140f,140f) to layout(id,s,today,appearance,false,small),SizeF(250f,140f) to layout(id,s,today,appearance,true,wide)))
        }
        return layout(id,s,today,appearance,minimum>=250,minimum.toFloat())
    }
    internal fun layout(id:Int,s:BookSnapshot?,today:LocalDate,appearance:Pair<String,String>,wide:Boolean,width:Float):RemoteViews {
        val theme=s?.book?.theme?:appearance.first
        val ja=(s?.book?.language?:appearance.second)=="ja"
        fun word(zh:String,jp:String)=if(ja)jp else zh
        val resource=when(theme) {
            "journal"->if(wide)R.layout.widget_journal_wide else R.layout.widget_journal_small
            "objects"->if(wide)R.layout.widget_objects_wide else R.layout.widget_objects_small
            else->if(wide)R.layout.widget_magazine_wide else R.layout.widget_magazine_small
        }
        val rv=RemoteViews(context.packageName,resource)
        val art=DesktopArtwork(context,theme)
        val ink=color(when(theme){"journal"->"#354d61";"objects"->"#634b3b";else->"#263d32"})
        val muted=color(when(theme){"journal"->"#898679";"objects"->"#9c8976";else->"#788577"})
        val warm=color(when(theme){"journal"->"#ae6657";"objects"->"#b56340";else->"#bc664f"})
        val inner=width-if(theme=="journal"&&wide)44f else 28f
        val mainWidth=if(wide)inner*(if(theme=="journal")1.15f/2 else 1.1f/2.1f)else inner
        fun text(view:Int,value:String,w:Float=inner,size:Float=11f,tint:Int=ink,number:Boolean=false) {
            rv.setImageViewBitmap(view,art.text(value,w,size,tint,number,smallSymbol=view==R.id.widget_amount&&number));rv.setContentDescription(view,value)
        }
        if(s==null) {
            text(R.id.widget_header,"Yohaku",size=13f,tint=warm)
            text(R.id.widget_caption,word("账本不可用","帳簿が利用できません"),mainWidth,12f)
            text(R.id.widget_amount,word("重新选择账本","帳簿を選び直す"),mainWidth,18f)
            rv.setViewVisibility(R.id.widget_stats,View.GONE)
            if(theme=="journal")rv.setViewVisibility(R.id.widget_wave,View.GONE)
            if(theme=="magazine"){rv.setViewVisibility(R.id.widget_progress,View.GONE);rv.setViewVisibility(R.id.widget_over_progress,View.GONE)}
            if(wide){rv.setViewVisibility(R.id.widget_templates,View.GONE);if(theme=="journal")rv.setViewVisibility(R.id.widget_list_heading,View.GONE)}
            rv.setOnClickPendingIntent(R.id.widget_root,WidgetConfigurationActivity.pendingIntent(context,id))
            return rv
        }
        val m=widgetBudget(s.budgetProjection(YearMonth.from(today).toString()),today)
        val normal=m.state==WidgetBudgetState.NORMAL
        val over=m.state==WidgetBudgetState.OVER_BUDGET
        fun money(value:Long?)=value?.let{Money(m.currency,it).display()}?:"—"
        val days=word("剩 ${m.daysLeft} 天","残り${m.daysLeft}日")
        val caption=when(m.state) {
            WidgetBudgetState.NORMAL->word(if(wide)"本月预算内还能花"else"预算内还能花","今月の予算内で使える")
            WidgetBudgetState.NO_BUDGET->word("本月已花","今月の支出")
            WidgetBudgetState.OVER_BUDGET->word("本月已超出预算","今月の予算オーバー")
        }
        val header=when(theme) {
            "journal"->if(wide&&m.limitMinor!=null)"${money(m.limitMinor)} − ${money(m.spentMinor)} =" else "${today.monthValue}月 · ${s.book.name}"
            "objects"->"● ${s.book.name} · "+(if(wide)"${today.monthValue}月 · "else "")+days
            else->if(wide)"Yohaku · ${s.book.name}" else "$days · ${s.book.name}"
        }
        // 杂志报头左右分列：刊名靠左，期号靠右（宽版）。
        val headerEnd=if(theme=="magazine"&&wide)"${today.year}.${today.monthValue} · $days" else null
        if(headerEnd!=null)text(R.id.widget_header_end,headerEnd,inner/2,9.5f,muted)
        text(R.id.widget_header,header,if(theme=="journal")mainWidth else if(headerEnd!=null)inner/2 else inner,if(theme=="journal")13f else 9.5f,if(theme=="journal")warm else if(theme=="objects")color("#8a6a4c")else muted)
        text(R.id.widget_caption,caption,mainWidth,if(theme=="journal")12f else 9.5f,if(theme=="objects")color("#9b7a58")else muted)
        text(R.id.widget_amount,money(m.amountMinor),mainWidth,if(wide)38f else 33f,if(over)if(theme=="objects")color("#8f3f2a")else warm else if(theme=="journal")color("#3b5b70")else if(theme=="objects"&&normal)warm else ink,true)
        val freeLabel=if(normal)word("自由可花","自由に使える")else word("月预算","月予算")
        val freeValue=if(normal)money(m.availableMinor)else if(over)money(m.limitMinor)else word("未设置","未設定")
        val dailyLabel=if(normal)word("日均","1日あたり")else word("剩余","残り")
        val dailyValue=if(normal)money(m.dailyMinor)else word("${m.daysLeft} 天","${m.daysLeft}日")
        if(theme=="journal"||theme=="magazine"&&!wide) {
            text(R.id.widget_free,if(m.state==WidgetBudgetState.NO_BUDGET)word("未设月预算","月予算未設定")else "$freeLabel  $freeValue",mainWidth,if(theme=="journal")13f else 11f,if(theme=="journal")warm else ink)
            text(R.id.widget_daily,if(theme=="journal"&&normal)"$days · ${word("每天约","1日約")}${money(m.dailyMinor)}"else "$dailyLabel  $dailyValue",mainWidth,11f,muted)
        }else {
            val cellWidth=(if(wide)inner-mainWidth else inner)/2-12
            val labelInk=if(theme=="objects")color("#a58763")else muted
            text(R.id.widget_free_label,freeLabel,cellWidth,9f,labelInk)
            text(R.id.widget_free_value,freeValue,cellWidth,13f,ink,normal||over)
            text(R.id.widget_daily_label,dailyLabel,cellWidth,9f,labelInk)
            text(R.id.widget_daily_value,dailyValue,cellWidth,13f,ink,normal)
        }
        if(theme=="journal")rv.setViewVisibility(R.id.widget_wave,if(normal)View.VISIBLE else View.GONE)
        if(theme=="magazine") {
            rv.setViewVisibility(R.id.widget_progress,if(m.limitMinor==null||over)View.GONE else View.VISIBLE)
            rv.setViewVisibility(R.id.widget_over_progress,if(over)View.VISIBLE else View.GONE)
            val progress=if(over)100 else if(m.limitMinor==null||m.limitMinor==0L)0 else (m.spentMinor.toDouble()/m.limitMinor*100).toInt().coerceIn(0,100)
            rv.setProgressBar(R.id.widget_progress,100,progress,false)
            rv.setProgressBar(R.id.widget_over_progress,100,100,false)
        }
        if(theme=="objects") {
            rv.setInt(R.id.widget_root,"setBackgroundResource",when(m.state){WidgetBudgetState.NORMAL->R.drawable.widget_pocket_normal;WidgetBudgetState.OVER_BUDGET->R.drawable.widget_pocket_over;WidgetBudgetState.NO_BUDGET->R.drawable.widget_pocket_none})
            rv.setInt(R.id.widget_strap,"setBackgroundResource",when(m.state){WidgetBudgetState.NORMAL->R.drawable.widget_strap_normal;WidgetBudgetState.OVER_BUDGET->R.drawable.widget_strap_over;WidgetBudgetState.NO_BUDGET->R.drawable.widget_strap_none})
        }
        fun action(template:String?,slot:Int):PendingIntent=PendingIntent.getActivity(context,id*8+slot,
            DesktopIntent.open(context,s.book.id,template).apply{data=data!!.buildUpon().appendQueryParameter("widget",id.toString()).appendQueryParameter("slot",slot.toString()).build()},PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        rv.setOnClickPendingIntent(R.id.widget_balance,action(null,0))
        if(wide) {
            if(theme=="journal")text(R.id.widget_list_heading,"${today.monthValue}月 · "+word("记一笔 ↓","記録する ↓"),inner-mainWidth-12,12f,warm)
            val templates=s.templates.sortedBy{it.sortOrder}.take(4)
            rv.removeAllViews(R.id.widget_templates)
            val count=templates.size.coerceAtLeast(1)
            val slotWidth=if(theme=="journal")inner-mainWidth-12 else inner/count
            for(index in 0 until count) {
                val template=templates.getOrNull(index)
                val name=template?.name?:word("在 app 里创建模板","アプリでテンプレートを作成")
                val amount=template?.amountMinor?.let{Money(Currency.valueOf(template.currency),it).display()}.orEmpty()
                val issue=template?.let{s.templateIssue(it)}
                val child=RemoteViews(context.packageName,when(theme){"journal"->R.layout.widget_template_journal;"objects"->R.layout.widget_template_objects;else->R.layout.widget_template_magazine})
                val textWidth=slotWidth-when(theme){"objects"->40;"journal"->20;else->6}
                fun slotText(view:Int,value:String,size:Float,tint:Int,number:Boolean=false) {
                    child.setImageViewBitmap(view,if(template==null&&view==R.id.widget_slot_name)art.paragraph(value,textWidth,size,tint)else art.text(value,textWidth,size,tint,number));child.setContentDescription(view,value)
                }
                slotText(R.id.widget_slot_name,name,if(theme=="journal")15f else 12f,if(issue!=null)muted else ink)
                slotText(R.id.widget_slot_amount,amount,9.5f,muted,true)
                child.setViewVisibility(R.id.widget_slot_amount,if(amount.isEmpty())View.GONE else View.VISIBLE)
                if(theme=="magazine") {
                    slotText(R.id.widget_slot_mark,"0${index+1}",8.5f,warm)
                    if(index==0)child.setInt(R.id.widget_slot,"setBackgroundColor",android.graphics.Color.TRANSPARENT)
                }
                if(theme=="objects")child.setImageViewBitmap(R.id.widget_slot_icon,art.category(resolveCategoryIcon(template?.categoryId,s.categories),index))
                if(issue!=null)child.setFloat(R.id.widget_slot,"setAlpha",.45f)
                child.setContentDescription(R.id.widget_slot,name+(if(amount.isEmpty())"" else " $amount")+(if(issue==null)"" else word("，需要编辑","、編集が必要です")))
                child.setOnClickPendingIntent(R.id.widget_slot,action(template?.id?:"",index+1))
                rv.addView(R.id.widget_templates,child)
            }
        }
        return rv
    }
}
