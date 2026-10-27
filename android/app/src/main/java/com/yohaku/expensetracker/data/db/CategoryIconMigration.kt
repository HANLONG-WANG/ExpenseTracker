package com.yohaku.expensetracker.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yohaku.expensetracker.domain.category.suggestCategoryIcons

val MIGRATION_31_32 = object:Migration(31,32) {
    override fun migrate(db:SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE categories ADD COLUMN icon TEXT DEFAULT NULL")
        val categories=mutableListOf<CategoryEntity>()
        db.query("SELECT bookId,id,name,kind,parentId FROM categories").use { cursor ->
            while(cursor.moveToNext())categories+=CategoryEntity(cursor.getString(0),cursor.getString(1),cursor.getString(2),cursor.getString(3),cursor.getString(4))
        }
        val roots=categories.filter{it.parentId==null}.associate { (it.bookId to it.id) to suggestCategoryIcons(it.name).firstOrNull() }
        for(category in categories) {
            val icon=if(category.parentId==null)roots[category.bookId to category.id]
                else suggestCategoryIcons(category.name).firstOrNull()?.takeUnless{it==roots[category.bookId to category.parentId]}
            if(icon!=null)db.execSQL("UPDATE categories SET icon=? WHERE bookId=? AND id=?",arrayOf(icon,category.bookId,category.id))
        }
    }
}
