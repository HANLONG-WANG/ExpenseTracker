package com.yohaku.expensetracker.platform.desktop

import android.content.Context
import android.content.ComponentName
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.yohaku.expensetracker.MainActivity
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.category.resolveCategoryIcon
import com.yohaku.expensetracker.domain.model.Currency
import com.yohaku.expensetracker.domain.model.Money

internal object DesktopShortcuts {
    private var published:List<String>?=null
    fun sync(context:Context,snapshot:BookSnapshot?) {
        val templates=snapshot?.templates?.filter{snapshot.templateIssue(it)==null}?.sortedBy{it.sortOrder}
            ?.take(minOf(4,ShortcutManagerCompat.getMaxShortcutCountPerActivity(context))).orEmpty()
        val signature=templates.map{t->listOf(snapshot!!.book.id,snapshot.book.theme,snapshot.book.language,t.id,t.name,t.currency,t.amountMinor,
            resolveCategoryIcon(t.categoryId,snapshot.categories)).joinToString("\u0000")}
        // 普通记账只更新预算，不反复发布相同快捷方式而耗尽系统配额。
        if(signature==published)return
        if(templates.isEmpty()) {ShortcutManagerCompat.removeAllDynamicShortcuts(context);published=signature;return}
        val s=snapshot!!;val artwork=DesktopArtwork(context,s.book.theme)
        val shortcuts=templates.mapIndexed { index,t->
            ShortcutInfoCompat.Builder(context,"template:${s.book.id}:${t.id}")
                .setActivity(ComponentName(context,MainActivity::class.java)).setShortLabel(t.name)
                .setLongLabel(t.name+(t.amountMinor?.let{" "+Money(Currency.valueOf(t.currency),it).display()}?:""))
                .setIcon(IconCompat.createWithAdaptiveBitmap(artwork.shortcut(t.name,resolveCategoryIcon(t.categoryId,s.categories),index)))
                .setIntent(DesktopIntent.open(context,s.book.id,t.id)).setRank(index).build()
        }
        check(ShortcutManagerCompat.setDynamicShortcuts(context,shortcuts)){"桌面快捷方式被系统限流，稍后重试"}
        published=signature
    }
}
