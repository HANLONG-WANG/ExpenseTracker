package com.yohaku.expensetracker.data.backup

import android.content.Context
import androidx.room.Room
import com.yohaku.expensetracker.data.db.*

const val BACKUP_SCHEMA_VERSION=DATABASE_VERSION
fun openYohakuDatabase(context:Context,name:String="yohaku.db"):YohakuDatabase =
    Room.databaseBuilder(context,YohakuDatabase::class.java,name).addMigrations(*ALL_MIGRATIONS).build()
