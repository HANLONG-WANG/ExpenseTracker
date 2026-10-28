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
    val indices=table.optJSONArray("indices")
    for(i in 0 until (indices?.length()?:0))db.execSQL(indices!!.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}",name))
    db.execSQL("INSERT INTO `$name` ($columns) SELECT $columns FROM fixture_backup")
    db.execSQL("DROP TABLE fixture_backup")
}

/** Rebuild every table at an exported old version, keeping data in shared columns, so later migrations all apply. */
internal fun restoreFixtureDatabase(db:SQLiteDatabase,version:Int) {
    val schema=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("room-v$version-schema.json").bufferedReader().readText()).getJSONObject("database")
    val entities=schema.getJSONArray("entities").let{a->(0 until a.length()).map{a.getJSONObject(it)}}
    val existing=db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table','android_metadata')",null).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
    (existing-entities.map{it.getString("tableName")}.toSet()).forEach{db.execSQL("DROP TABLE `$it`")}
    for(table in entities) {
        val name=table.getString("tableName")
        val fields=table.getJSONArray("fields").let{a->(0 until a.length()).map{a.getJSONObject(it).getString("columnName")}}
        val present=db.rawQuery("PRAGMA table_info(`$name`)",null).use{c->buildSet{while(c.moveToNext())add(c.getString(1))}}
        val columns=fields.filter{it in present}.joinToString(","){"`$it`"}
        if(present.isNotEmpty()){db.execSQL("CREATE TEMP TABLE fixture_backup AS SELECT $columns FROM `$name`");db.execSQL("DROP TABLE `$name`")}
        db.execSQL(table.getString("createSql").replace("\${TABLE_NAME}",name))
        table.optJSONArray("indices")?.let{a->(0 until a.length()).forEach{db.execSQL(a.getJSONObject(it).getString("createSql").replace("\${TABLE_NAME}",name))}}
        if(present.isNotEmpty()){db.execSQL("INSERT INTO `$name` ($columns) SELECT $columns FROM fixture_backup");db.execSQL("DROP TABLE fixture_backup")}
    }
    db.execSQL("UPDATE room_master_table SET identity_hash=? WHERE id=42",arrayOf(schema.getString("identityHash")))
    db.version=version
}
