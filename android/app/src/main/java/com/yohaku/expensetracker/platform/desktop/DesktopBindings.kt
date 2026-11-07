package com.yohaku.expensetracker.platform.desktop

import android.content.Context
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.first
import java.io.File

/** 桌面 ID 属于本机启动器，独立于账本备份；恢复账本不应清空桌面绑定。 */
class DesktopBindings(context:Context) {
    private val store=PreferenceDataStoreFactory.create(produceFile={File(context.noBackupFilesDir,"desktop.preferences_pb")})
    suspend fun book(id:Int)=store.data.first()[stringPreferencesKey("book_$id")]
    suspend fun appearance(id:Int)=store.data.first().let{(it[stringPreferencesKey("theme_$id")]?:"magazine") to (it[stringPreferencesKey("language_$id")]?:"zh")}
    suspend fun bind(id:Int,book:String,theme:String,language:String) {store.edit {
        it[stringPreferencesKey("book_$id")]=book
        it[stringPreferencesKey("theme_$id")]=theme
        it[stringPreferencesKey("language_$id")]=language
    }}
    suspend fun delete(ids:IntArray) {store.edit{p->ids.forEach{id->listOf("book","theme","language").forEach{p.remove(stringPreferencesKey("${it}_$id"))}}}}
}
