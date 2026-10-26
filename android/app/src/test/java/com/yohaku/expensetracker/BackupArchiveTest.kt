package com.yohaku.expensetracker

import com.yohaku.expensetracker.data.backup.*
import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveTest {
    private fun manifest(entries:List<BackupEntry>)=BackupManifest(format="yohaku.backup",formatVersion=1,versionName="0.5.0",versionCode=5,schemaVersion=29,createdAt="2026-10-26T10:00:00Z",books=listOf(BackupBook("book","日常",1)),attachmentCount=0,attachmentBytes=0,entries=entries)
    private fun rejected(action:()->Unit){try{action();fail("Expected rejection")}catch(_:IllegalArgumentException){}}
    @Test fun validatesRequiredManifestAndRejectsFutureSchema() {
        val entries=listOf(BackupEntry("database/yohaku.db",1,"a".repeat(64)),BackupEntry("preferences.json",2,"b".repeat(64)))
        val m=manifest(entries)
        BackupArchive.validate(m,29)
        BackupArchive.validate(m.copy(schemaVersion=28),29)
        rejected{BackupArchive.validate(m.copy(format="other"),29)}
        rejected{BackupArchive.validate(m.copy(formatVersion=2),29)}
        rejected{BackupArchive.validate(m.copy(schemaVersion=30),29)}
        rejected{BackupArchive.validate(m.copy(entries=entries.take(1)),29)}
        val root=Files.createTempDirectory("backup-manifest").toFile()
        try {
            val out=ByteArrayOutputStream()
            ZipOutputStream(out).use{it.putNextEntry(ZipEntry("preferences.json"));it.write("{}".toByteArray());it.closeEntry()}
            rejected{BackupArchive.extract(ByteArrayInputStream(out.toByteArray()),File(root,"extracted"),29)}
            assertFalse(File(root,"extracted").exists())
        }finally{root.deleteRecursively()}
    }
    @Test fun rejectsZipSlipBeforeWritingOutsideStaging() {
        val root=Files.createTempDirectory("backup-path").toFile()
        try {
            for(path in listOf("../escaped","attachments/../../escaped","/absolute","attachments/../escaped","C:/absolute")) {
                val out=ByteArrayOutputStream()
                ZipOutputStream(out).use{it.putNextEntry(ZipEntry(path));it.write(1);it.closeEntry()}
                rejected{BackupArchive.extract(ByteArrayInputStream(out.toByteArray()),File(root,"extracted"),29)}
                assertFalse(File(root,"escaped").exists())
                assertFalse(File(root,"extracted").exists())
            }
        }finally{root.deleteRecursively()}
    }
    @Test fun roundTripChecksEveryHashAndSize() {
        val root=Files.createTempDirectory("backup-hash").toFile()
        try {
            val source=File(root,"source").apply{mkdirs()}
            File(source,"database").mkdirs()
            File(source,"database/yohaku.db").writeText("database")
            File(source,"preferences.json").writeText("{}")
            val m=manifest(source.walkTopDown().filter{it.isFile}.map{BackupArchive.entry(source,it)}.toList())
            val out=ByteArrayOutputStream();BackupArchive.write(source,m,out)
            assertEquals(m,BackupArchive.extract(ByteArrayInputStream(out.toByteArray()),File(root,"valid"),29))
            // Same size, different bytes: size validation alone would incorrectly accept this.
            File(source,"database/yohaku.db").writeText("tampered")
            val tampered=ByteArrayOutputStream();BackupArchive.write(source,m,tampered)
            rejected{BackupArchive.extract(ByteArrayInputStream(tampered.toByteArray()),File(root,"invalid"),29)}
            assertFalse(File(root,"invalid").exists())
            rejected{BackupArchive.verify(File(root,"valid"),m.copy(entries=m.entries.map{it.copy(size=it.size+1)}),29)}
        }finally{root.deleteRecursively()}
    }
    @Test fun failedReplacementRollsBackDatabaseAttachmentsAndPreferences() {
        val root=Files.createTempDirectory("backup-rollback").toFile()
        try {
            val layout=RestoreLayout(File(root,"live/db"),File(root,"live/attachments"),File(root,"live/prefs"),File(root,"journal"))
            layout.database.parentFile!!.mkdirs();layout.database.writeText("old database")
            File(layout.database.path+"-wal").writeText("old wal")
            layout.attachments.mkdirs();File(layout.attachments,"image").writeText("original")
            layout.preferences.writeText("old preferences")
            val stage=File(root,"stage");File(stage,"ready").mkdirs();File(stage,"ready/yohaku.db").writeText("new database")
            // Missing prepared preferences fails after the database and attachments were replaced.
            try{RestoreFiles.replace(layout,stage);fail("Expected failure")}catch(_:IllegalStateException){}
            assertEquals("old database",layout.database.readText())
            assertEquals("old wal",File(layout.database.path+"-wal").readText())
            assertEquals("original",File(layout.attachments,"image").readText())
            assertEquals("old preferences",layout.preferences.readText())
            assertFalse(layout.journal.exists())
            // Simulate process death halfway through rollback: DB already moved back, prefs still old/.
            val old=File(layout.journal,"old").apply{mkdirs()}
            File(layout.journal,"state").writeText("installing")
            File(layout.journal,"originals").writeText("yohaku.db\nyohaku.db-wal\nattachments\napp.preferences_pb")
            assertTrue(layout.preferences.renameTo(File(old,"app.preferences_pb")))
            layout.preferences.writeText("new preferences")
            assertTrue(RestoreFiles.recover(layout))
            assertEquals("old database",layout.database.readText())
            assertEquals("old preferences",layout.preferences.readText())
        }finally{root.deleteRecursively()}
    }
}
