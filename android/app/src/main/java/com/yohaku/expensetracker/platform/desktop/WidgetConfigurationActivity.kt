package com.yohaku.expensetracker.platform.desktop

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.yohaku.expensetracker.YohakuApplication
import com.yohaku.expensetracker.data.db.BookOverview
import com.yohaku.expensetracker.ui.features.BookCard
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

class WidgetConfigurationActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val id=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID)
        if(id==AppWidgetManager.INVALID_APPWIDGET_ID){finish();return}
        enableEdgeToEdge()
        val app=application as YohakuApplication
        setContent {
            val selection by remember {combine(app.repository.dao.bookOverviews(),app.preferences.activeBook){books,active->
                books.filter{!it.book.archived&&it.book.deletedAt==null}.let{available->available to (available.firstOrNull{it.book.id==active}?:available.firstOrNull())?.book}
            }}.collectAsState(initial=emptyList<BookOverview>() to null)
            val bound by produceState<String?>(null){value=app.desktop.bindings.book(id)}
            val books=selection.first;val active=selection.second
            var saving by remember{mutableStateOf(false)}
            var error by remember{mutableStateOf<String?>(null)}
            YohakuTheme(active?.theme?:"magazine",active?.language?:if(resources.configuration.locales[0].language=="ja")"ja"else"zh") {
                Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    YBackBar(word("桌面小组件","ホームウィジェット"),{finish()})
                    YTitle(word("选一本，放在桌面。","ホームに置く帳簿を選ぶ。"),"YOHAKU",word("显示这本账的本月预算与常用模板。","この帳簿の今月の予算とテンプレートを表示します。"))
                    books.forEach{overview->val book=overview.book
                        BookCard(overview,book.id==bound){
                            if(saving)return@BookCard
                            saving=true;error=null
                            lifecycleScope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        val live=app.repository.dao.book(book.id)
                                        require(live!=null&&live.deletedAt==null){"账本不可用 / 帳簿が利用できません"}
                                        app.desktop.bindings.bind(id,live.id,live.theme,live.language)
                                        app.desktop.refresh()
                                    }
                                    setResult(RESULT_OK,Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id));finish()
                                }catch(e:CancellationException){throw e}
                                catch(e:Exception){error=e.message;saving=false}
                            }
                        }
                    }
                    if(books.isEmpty())YEmpty(word("请先在 app 中创建账本。","先にアプリで帳簿を作成してください。"))
                    error?.let{YText(it,12,color=LocalYohaku.current.warm)}
                }
            }
        }
    }
    companion object {
        fun pendingIntent(context:Context,id:Int)=PendingIntent.getActivity(context,id,
            Intent(context,WidgetConfigurationActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id)
                .setData(android.net.Uri.parse("yohaku://widget-config/$id")),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
