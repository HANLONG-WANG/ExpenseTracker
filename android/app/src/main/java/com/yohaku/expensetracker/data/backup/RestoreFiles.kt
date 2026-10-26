package com.yohaku.expensetracker.data.backup

import android.content.Context
import java.io.File
import java.io.RandomAccessFile

/** No live Room/DataStore users may exist when replace/recover runs. The restore process enforces this. */
data class RestoreLayout(val database:File,val attachments:File,val preferences:File,val journal:File) {
    val targets get()=linkedMapOf(
        "yohaku.db" to database,"yohaku.db-wal" to File(database.path+"-wal"),
        "yohaku.db-shm" to File(database.path+"-shm"),"yohaku.db-journal" to File(database.path+"-journal"),
        "attachments" to attachments,"app.preferences_pb" to preferences
    )
}
object RestoreFiles {
    fun layout(context:Context)=RestoreLayout(context.getDatabasePath("yohaku.db"),File(context.filesDir,"attachments"),
        File(context.filesDir,"datastore/app.preferences_pb"),File(context.noBackupFilesDir,"restore-journal"))
    fun <T> locked(context:Context,block:()->T):T = RandomAccessFile(File(context.noBackupFilesDir,"restore.lock"),"rw").use{file->file.channel.lock().use{block()}}
    fun recoverOnStartup(context:Context)=locked(context) {
        if(recover(layout(context)))File(context.noBackupFilesDir,"backup-result.txt").writeText("上次恢复中断，已还原原数据 / 復元が中断されたため、元のデータに戻しました")
        context.cacheDir.listFiles()?.filter{it.name.startsWith("backup-export-")||it.name.startsWith("backup-import-")}?.forEach{it.deleteRecursively()}
    }
    private fun move(source:File,target:File) {
        check(target.parentFile!!.isDirectory||target.parentFile!!.mkdirs())
        check(source.renameTo(target)){"无法替换文件：${source.name} / ファイルを置換できません：${source.name}"}
    }
    private fun marker(file:File,text:String) {
        val temp=File(file.path+".tmp")
        temp.outputStream().use{it.write(text.toByteArray());it.fd.sync()}
        check(temp.renameTo(file))
    }
    /** Move originals on the same filesystem; no deletion of originals until the commit marker exists. */
    fun replace(layout:RestoreLayout,stage:File) {
        check(!layout.journal.exists())
        check(layout.journal.mkdirs())
        val old=File(layout.journal,"old");check(old.mkdirs())
        marker(File(layout.journal,"originals"),layout.targets.filterValues{it.exists()}.keys.joinToString("\n"))
        marker(File(layout.journal,"state"),"moving")
        try {
            layout.targets.forEach{(name,target)->if(target.exists())move(target,File(old,name))}
            marker(File(layout.journal,"state"),"installing")
            move(File(stage,"ready/yohaku.db"),layout.database)
            val attachments=File(stage,"attachments")
            if(attachments.exists())move(attachments,layout.attachments)else check(layout.attachments.mkdirs())
            move(File(stage,"ready/app.preferences_pb"),layout.preferences)
            marker(File(layout.journal,"state"),"committed")
        }catch(e:Exception){try{recover(layout)}catch(rollback:Exception){e.addSuppressed(rollback)};throw e}
        // Cleanup failure must not turn an already committed replacement into a reported failed restore.
        layout.journal.deleteRecursively()
    }
    /** Called before any main-process database or preferences are opened, including after an OS kill. */
    fun recover(layout:RestoreLayout):Boolean {
        if(!layout.journal.exists())return false
        val state=File(layout.journal,"state").takeIf{it.exists()}?.readText()
        if(state=="committed"||state=="rolledback"||state==null){check(layout.journal.deleteRecursively());return false}
        val old=File(layout.journal,"old")
        val originals=File(layout.journal,"originals").readLines().toSet()
        layout.targets.forEach{(name,target)->
            val original=File(old,name)
            if(original.exists()) {
                check(!target.exists()||target.deleteRecursively())
                // Atomic rename plus the original inventory makes rollback itself restartable,
                // without requiring enough free space to duplicate the old attachments.
                move(original,target)
            }else if(state=="installing"&&name !in originals) {
                check(!target.exists()||target.deleteRecursively())
            }
        }
        marker(File(layout.journal,"state"),"rolledback")
        check(layout.journal.deleteRecursively())
        return true
    }
}
