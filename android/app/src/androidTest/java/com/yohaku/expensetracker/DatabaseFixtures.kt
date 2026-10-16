package com.yohaku.expensetracker

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject

/** Rebuild one fixture table from its exported old schema, preserving every old column. */
internal fun restoreFixtureTable(db:SQLiteDatabase,asset:String,name:String) {
    val schema=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open(asset).bufferedReader().readText()).getJSONObject("database")
    val entities=schema.getJSONArray("entities")
    val table=(0 until entities.length()).map{entities.getJSONObject(it)}.first{it.getString("tableName")==name}
    val fields=table.getJSONArray("fields")
    val columns=(0 until fields.length()).joinToString(","){"`${fields.getJSONObject(it).getString("columnName")}`"}
    db.execSQL("CREATE TEMP TABLE fixture_backup AS SELECT $columns FROM `$name`")
    db.execSQL("DROP TABLE `$name`")
    db.execSQL(table.getString("createSql").replace("\${TABLE_NAME}",name))
    val indices=table.getJSONArray("indices")
    for(i in 0 until indices.length())db.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}",name))
    db.execSQL("INSERT INTO `$name` ($columns) SELECT $columns FROM fixture_backup")
    db.execSQL("DROP TABLE fixture_backup")
}

internal fun dropCreditLimitFixtureTables(db:SQLiteDatabase) {
    restoreFixtureTable(db,"room-v18-schema.json","events")
    restoreFixtureTable(db,"room-v18-schema.json","financial_plans")
    listOf("credit_limit_group_members","credit_limit_group_revisions","credit_limit_recoveries","credit_limit_observations","credit_limit_configs","credit_limit_groups").forEach{db.execSQL("DROP TABLE `$it`")}
}
