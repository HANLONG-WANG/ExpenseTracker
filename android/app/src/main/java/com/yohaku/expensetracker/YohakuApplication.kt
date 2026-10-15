package com.yohaku.expensetracker
import androidx.room.Room
import com.yohaku.expensetracker.data.db.YohakuDatabase
import com.yohaku.expensetracker.data.repository.*
class YohakuApplication:android.app.Application() {
    val repository by lazy { LedgerRepository(Room.databaseBuilder(this,YohakuDatabase::class.java,"yohaku.db").addMigrations(com.yohaku.expensetracker.data.db.MIGRATION_1_2,com.yohaku.expensetracker.data.db.MIGRATION_2_3,com.yohaku.expensetracker.data.db.MIGRATION_3_4,com.yohaku.expensetracker.data.db.MIGRATION_4_5,com.yohaku.expensetracker.data.db.MIGRATION_5_6,com.yohaku.expensetracker.data.db.MIGRATION_6_7,com.yohaku.expensetracker.data.db.MIGRATION_7_8,com.yohaku.expensetracker.data.db.MIGRATION_8_9,com.yohaku.expensetracker.data.db.MIGRATION_9_10,com.yohaku.expensetracker.data.db.MIGRATION_10_11).build(),mediaRoot=java.io.File(filesDir,"attachments")) }
    val preferences by lazy { AppPreferences(this) }
}
