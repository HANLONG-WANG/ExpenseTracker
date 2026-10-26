package com.yohaku.expensetracker.data.backup

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.withTransaction
import com.yohaku.expensetracker.data.repository.AppPreferences
import com.yohaku.expensetracker.data.repository.LedgerRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import java.io.File
import java.time.Instant
import java.util.UUID

class BackupService(private val context:Context,private val repository:LedgerRepository,private val preferences:AppPreferences) {
    suspend fun export(output:java.io.OutputStream):BackupManifest=withContext(Dispatchers.IO) {
        val stage=File(context.cacheDir,"backup-export-${UUID.randomUUID()}");check(stage.mkdirs())
        try {
            val database=File(stage,"database/yohaku.db");check(database.parentFile!!.mkdirs())
            // Media imports/cleanup use this same lock order: media lock, then Room transaction.
            repository.mediaWrites.withLock { repository.db.withTransaction {
                snapshotDatabase(repository.db.openHelper.writableDatabase,database)
                repository.mediaRoot?.takeIf{it.exists()}?.copyRecursively(File(stage,"attachments"),overwrite=false)
                File(stage,"preferences.json").writeText(BackupArchive.json.encodeToString(preferences.backup()))
            }}
            val books=SQLiteDatabase.openDatabase(database.path,null,SQLiteDatabase.OPEN_READONLY).use{db->
                db.rawQuery("SELECT b.id,b.name,(SELECT COUNT(*) FROM events e WHERE e.bookId=b.id AND e.purgedAt IS NULL) FROM books b ORDER BY b.rowid",null).use{c->buildList{while(c.moveToNext())add(BackupBook(c.getString(0),c.getString(1),c.getLong(2)))}}
            }
            val entries=stage.walkTopDown().filter{it.isFile}.map{BackupArchive.entry(stage,it)}.sortedBy{it.path}.toList()
            val attachments=entries.filter{it.path.startsWith("attachments/")}
            val info=context.packageManager.getPackageInfo(context.packageName,0)
            val manifest=BackupManifest(format="yohaku.backup",formatVersion=1,versionName=info.versionName?:"",versionCode=info.longVersionCode,schemaVersion=BACKUP_SCHEMA_VERSION,
                createdAt=Instant.now().toString(),books=books,attachmentCount=attachments.size,attachmentBytes=attachments.sumOf{it.size},entries=entries)
            BackupArchive.write(stage,manifest,output)
            manifest
        }finally{stage.deleteRecursively()}
    }
    suspend fun exportDocument(uri:Uri):Pair<String,Long> = withContext(Dispatchers.IO) {
        try {
            var bytes=0L
            val output=context.contentResolver.openOutputStream(uri,"wt")?:error("无法写入文件 / ファイルを書き込めません")
            output.use{raw->export(object:java.io.FilterOutputStream(raw){
                override fun write(b:Int){out.write(b);bytes++}
                override fun write(b:ByteArray,off:Int,len:Int){out.write(b,off,len);bytes+=len}
            })}
            var name=uri.lastPathSegment.orEmpty()
            context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{c->if(c.moveToFirst())name=c.getString(0)}
            name to bytes
        }catch(e:Exception){
            try{check(DocumentsContract.deleteDocument(context.contentResolver,uri))}
            catch(cleanup:Exception){
                val reason=e.message?:e.javaClass.simpleName
                throw java.io.IOException("导出失败且无法删除目标，请手动删除未完成文件：${reason.substringBefore(" / ")} / 書き出し失敗。未完了ファイルを手動で削除してください：${reason.substringAfter(" / ",reason)}",e).also{it.addSuppressed(cleanup)}
            }
            throw e
        }
    }
    suspend fun inspect(uri:Uri):Pair<File,BackupManifest> = withContext(Dispatchers.IO) {
        val stage=File(context.cacheDir,"backup-import-${UUID.randomUUID()}")
        try {
            val manifest=(context.contentResolver.openInputStream(uri)?:error("无法读取文件 / ファイルを読み込めません")).use{BackupArchive.extract(it,stage,BACKUP_SCHEMA_VERSION)}
            prepareRestore(context,stage,manifest)
            stage to manifest
        }catch(e:Exception){stage.deleteRecursively();throw e}
    }
}

/** A logical SQLite snapshot in one source transaction works on Android 10 too (no VACUUM INTO requirement).
 * Recreate schema, rowids, values and indexes; never copy a live WAL database file. */
internal fun snapshotDatabase(source:androidx.sqlite.db.SupportSQLiteDatabase,target:File) {
    SQLiteDatabase.openOrCreateDatabase(target,null).use{dest->
        dest.disableWriteAheadLogging()
        dest.rawQuery("PRAGMA journal_mode=DELETE",null).use{it.moveToFirst()}
        data class Definition(val type:String,val name:String,val sql:String)
        val definitions=source.query("SELECT type,name,sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' ORDER BY rowid").use{c->buildList{while(c.moveToNext())add(Definition(c.getString(0),c.getString(1),c.getString(2)))}}
        fun quote(name:String)="\""+name.replace("\"","\"\"")+"\""
        dest.beginTransaction()
        try {
            definitions.filter{it.type=="table"}.forEach{table->
                // SQLiteDatabase creates this platform metadata table on open.
                if(table.name=="android_metadata")dest.execSQL("DROP TABLE IF EXISTS android_metadata")
                dest.execSQL(table.sql)
                source.query("SELECT rowid,* FROM ${quote(table.name)}").use{c->
                    val columns=(listOf("rowid")+c.columnNames.drop(1)).joinToString(",",transform=::quote)
                    dest.compileStatement("INSERT INTO ${quote(table.name)} ($columns) VALUES (${List(c.columnCount){"?"}.joinToString(",")})").use{statement->
                        while(c.moveToNext()){
                            statement.clearBindings()
                            for(i in 0 until c.columnCount)when(c.getType(i)){
                                Cursor.FIELD_TYPE_NULL->statement.bindNull(i+1)
                                Cursor.FIELD_TYPE_INTEGER->statement.bindLong(i+1,c.getLong(i))
                                Cursor.FIELD_TYPE_FLOAT->statement.bindDouble(i+1,c.getDouble(i))
                                Cursor.FIELD_TYPE_BLOB->statement.bindBlob(i+1,c.getBlob(i))
                                else->statement.bindString(i+1,c.getString(i))
                            }
                            statement.executeInsert()
                        }
                    }
                }
            }
            definitions.filter{it.type!="table"}.forEach{dest.execSQL(it.sql)}
            dest.version=source.version
            dest.setTransactionSuccessful()
        }finally{dest.endTransaction()}
    }
}

/** Verify original hashes, then migrate only a staging copy using exactly the production migrations. */
suspend fun prepareRestore(context:Context,stage:File,manifest:BackupManifest) = withContext(Dispatchers.IO) {
    BackupArchive.verify(stage,manifest,BACKUP_SCHEMA_VERSION)
    val prefs=BackupArchive.json.decodeFromString<BackupPreferences>(File(stage,"preferences.json").readText()).also{it.validate()}
    val ready=File(stage,"ready");ready.deleteRecursively();check(ready.mkdirs())
    val database=File(ready,"yohaku.db")
    File(stage,"database/yohaku.db").copyTo(database)
    SQLiteDatabase.openDatabase(database.path,null,SQLiteDatabase.OPEN_READONLY).use{db->
        require(db.version==manifest.schemaVersion){"数据库版本与清单不一致 / DBバージョンが一覧と一致しません"}
        db.rawQuery("PRAGMA integrity_check",null).use{c->require(c.moveToFirst()&&c.getString(0)=="ok"){"数据库损坏 / データベースが破損しています"}}
    }
    val db=openYohakuDatabase(context,database.path)
    try {
        val sqlite=db.openHelper.writableDatabase // Room validates schema and runs older migrations here.
        sqlite.query("PRAGMA foreign_key_check").use{require(!it.moveToFirst()){"数据库关联损坏 / データベースの関連が破損しています"}}
        sqlite.query("PRAGMA wal_checkpoint(TRUNCATE)").use{it.moveToFirst();check(it.getInt(0)==0)}
    }finally{db.close()}
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    try {
        val store=PreferenceDataStoreFactory.create(scope=scope,produceFile={File(ready,"app.preferences_pb")})
        AppPreferences(context,store).restoreBackup(prefs)
    }finally{scope.coroutineContext[Job]!!.cancelAndJoin()}
}
