package com.yohaku.expensetracker
import androidx.room.Room
import com.yohaku.expensetracker.data.db.YohakuDatabase
import com.yohaku.expensetracker.data.repository.*
class YohakuApplication:android.app.Application() {
    val repository by lazy { LedgerRepository(Room.databaseBuilder(this,YohakuDatabase::class.java,"yohaku.db").addMigrations(com.yohaku.expensetracker.data.db.MIGRATION_1_2,com.yohaku.expensetracker.data.db.MIGRATION_2_3).build()) }
    val preferences by lazy { AppPreferences(this) }
}
