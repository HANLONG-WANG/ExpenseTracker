package com.yohaku.expensetracker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.backup.*
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.domain.reminders.ReminderPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Isolated files: exercises the production snapshot, migration preparation and replacement code,
 * without terminating the instrumentation process or touching the installed user's database. */
@RunWith(AndroidJUnit4::class)
class BackupRoundTripTest {
    @Test fun exportMutateRestorePreservesWholeApplicationData()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.cacheDir,"backup-test-${UUID.randomUUID()}").apply{mkdirs()}
        val layout=RestoreLayout(File(root,"yohaku.db"),File(root,"attachments"),File(root,"app.preferences_pb"),File(root,"journal"))
        var db=openYohakuDatabase(context,layout.database.path)
        var scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        fun preferences()=AppPreferences(context,PreferenceDataStoreFactory.create(scope=scope,produceFile={layout.preferences}))
        var prefs=preferences()
        try {
            var repo=LedgerRepository(db,mediaRoot=layout.attachments)
            val book=repo.createBook("日常 / 日々",Currency.JPY,"journal","ja",budgetMinor=50000)
            val other=repo.createBook("旅行",Currency.CNY,"objects","zh")
            val account=repo.saveAccount(book,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
            val category=repo.saveCategory(book,"食事",EntryKind.EXPENSE,null)
            val event=EventEntity(book,"event",EntryKind.EXPENSE.name,account,category,1000,"JPY",1000,"2026-10-20","お店","備考")
            repo.dao.putEvent(event)
            repo.dao.putPostings(listOf(PostingEntity(book,"posting",event.id,account,-1000,"JPY",event.date)))
            val original=File(layout.attachments,"$book/image.png").apply{parentFile!!.mkdirs()}
            Bitmap.createBitmap(3,3,Bitmap.Config.ARGB_8888).also{bitmap->original.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            val blob=FileBlobEntity(book,"image","$book/image.png","原图.png","image/png",original.length(),BackupArchive.sha256(original))
            repo.dao.putFile(blob)
            repo.dao.putAttachments(listOf(AttachmentLinkEntity(book,"link",event.id,blob.id)))
            repo.dao.putReceipt(ReceiptEntity(book,"receipt",event.id,1))
            repo.dao.putReceiptImages(listOf(ReceiptImageEntity(book,"receipt",1,0,blob.id)))
            prefs.select(book);prefs.setAutomaticReferenceRates(false);prefs.setForegroundLocation(book,true)
            prefs.saveReminderSettings(book,ReminderPreferences(enabled=true,time="10:15"))
            val expectedPrefs=prefs.backup()
            val expected=repo.snapshot(repo.dao.book(book)!!).first()
            val expectedBooks=repo.books.first()
            val archive=File(root,"backup.yohaku")
            archive.outputStream().use{BackupService(context,repo,prefs).export(it)}
            // Change each storage domain after export, including a new book that restore must remove.
            repo.updateBook(repo.dao.book(book)!!.copy(name="Changed"))
            repo.createBook("Only after backup",Currency.USD)
            repo.dao.putEvent(event.copy(note="Changed"))
            original.writeText("Changed image")
            prefs.setAutomaticReferenceRates(true);prefs.saveReminderSettings(book,ReminderPreferences(time="12:30"))
            val stage=File(root,"import")
            val manifest=archive.inputStream().use{BackupArchive.extract(it,stage,BACKUP_SCHEMA_VERSION)}
            assertEquals(setOf(book,other),manifest.books.map{it.id}.toSet())
            prepareRestore(context,stage,manifest)
            db.close();scope.coroutineContext[Job]!!.cancelAndJoin()
            RestoreFiles.replace(layout,stage)
            db=openYohakuDatabase(context,layout.database.path)
            scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);prefs=preferences()
            repo=LedgerRepository(db,mediaRoot=layout.attachments)
            assertEquals(expectedBooks,repo.books.first())
            assertEquals(expected,repo.snapshot(repo.dao.book(book)!!).first())
            assertEquals(expectedPrefs,prefs.backup())
            assertEquals(blob.checksum,BackupArchive.sha256(repo.mediaFile(blob)))
            assertNotNull(BitmapFactory.decodeFile(repo.mediaFile(blob).path))
            assertFalse(layout.journal.exists())
        }finally{db.close();scope.coroutineContext[Job]!!.cancelAndJoin();root.deleteRecursively()}
    }
}
