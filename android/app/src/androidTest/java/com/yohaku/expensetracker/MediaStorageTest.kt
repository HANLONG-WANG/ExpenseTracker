package com.yohaku.expensetracker

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.*
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class MediaStorageTest {
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var root:File
    private lateinit var book:String
    private lateinit var account:String
    private lateinit var category:String
    @Before fun open()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        root=File(File(context.filesDir,"attachments"),"media-test-"+java.util.UUID.randomUUID());root.mkdirs()
        db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build();repo=LedgerRepository(db,mediaRoot=root)
        book=repo.createBook("附件",Currency.JPY)
        account=repo.saveAccount(book,"现金",AccountType.CASH,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
        category=repo.saveCategory(book,"生活",EntryKind.EXPENSE,null)
    }
    @After fun close(){db.close();root.deleteRecursively()}
    private suspend fun snapshot()=repo.snapshot(repo.dao.book(book)!!).first()
    private suspend fun stored()=repo.json.decodeFromString<EntryDraft>(repo.dao.storedDraft(book)!!.json)
    private fun draft(id:String?=null)=EntryDraft(id=id,accountId=account,categoryId=category,amount="1000",date="2026-10-14",mediaInitialized=true)
    @Test fun sourceDeletionDraftRecoveryCancelAndSharedReferencesKeepRealBytes()=runBlocking {
        val source=File(root,"source.bin").apply{writeBytes(byteArrayOf(0,1,2,3,127,-1))}
        val d=draft();repo.saveDraft(book,d)
        val file=repo.importMedia(book,d.mediaSession,"原始文件.bin","application/octet-stream"){source.inputStream()}
        assertEquals(6L,file.size);assertEquals(MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString(""){"%02x".format(it)},file.checksum)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val uri=androidx.core.content.FileProvider.getUriForFile(context,context.packageName+".files",repo.mediaFile(file),file.originalName).buildUpon().appendQueryParameter("mime",file.mime).build()
        context.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME,android.provider.OpenableColumns.SIZE),null,null,null)!!.use{cursor->assertTrue(cursor.moveToFirst());assertEquals("原始文件.bin",cursor.getString(0));assertEquals(6L,cursor.getLong(1))}
        assertEquals("application/octet-stream",context.contentResolver.getType(uri))
        assertArrayEquals(source.readBytes(),context.contentResolver.openInputStream(uri)!!.use{it.readBytes()})
        source.delete();assertArrayEquals(byteArrayOf(0,1,2,3,127,-1),repo.mediaFile(file).readBytes())
        assertEquals(listOf(file.id),stored().attachmentIds)
        val event=repo.saveEntry(book,stored())
        assertEquals(listOf(file.id),snapshot().entryAttachments(event).map{it.id});assertEquals(9000L,snapshot().balance(snapshot().accounts.single()))
        val edit=draft(event).copy(attachmentIds=listOf(file.id));repo.saveDraft(book,edit)
        val temporary=repo.importMedia(book,edit.mediaSession,"临时.txt","text/plain"){"new".byteInputStream()}
        repo.saveDraft(book,stored().copy(attachmentIds=listOf(temporary.id)))
        File(root,"interrupted.part").writeText("partial")
        repo.clearDraft(book);repo.collectUnusedMedia()
        assertFalse(File(root,"interrupted.part").exists())
        assertTrue(repo.mediaFile(file).isFile);assertFalse(repo.mediaFile(temporary).exists());assertEquals(listOf(file.id),snapshot().entryAttachments(event).map{it.id})
        val second=draft();repo.saveDraft(book,second)
        val picture=repo.importMedia(book,second.mediaSession,"image.png","image/png",true){byteArrayOf(1,2,3).inputStream()}
        val secondEvent=repo.saveEntry(book,stored().copy(attachmentIds=listOf(picture.id)))
        val editSecond=draft(secondEvent).copy(receiptFileIds=listOf(picture.id));repo.saveDraft(book,editSecond)
        repo.saveEntry(book,editSecond);repo.collectUnusedMedia()
        assertTrue(repo.mediaFile(picture).isFile);assertTrue(snapshot().entryAttachments(secondEvent).isEmpty());assertEquals(listOf(picture.id),snapshot().entryReceiptImages(secondEvent).map{it.id})
    }
    @Test fun cameraOrientationIsAppliedToReceiptsAndAttachmentsWithoutRewritingBytes()=runBlocking {
        val source=File(root,"camera.jpg")
        android.graphics.Bitmap.createBitmap(40,20,android.graphics.Bitmap.Config.ARGB_8888).apply{source.outputStream().use{compress(android.graphics.Bitmap.CompressFormat.JPEG,90,it)};recycle()}
        android.media.ExifInterface(source.path).apply{setAttribute(android.media.ExifInterface.TAG_ORIENTATION,android.media.ExifInterface.ORIENTATION_ROTATE_90.toString());saveAttributes()}
        val d=draft();repo.saveDraft(book,d)
        for(receipt in listOf(true,false)) {
            val blob=repo.importMedia(book,d.mediaSession,"camera.jpg","image/jpeg",receipt,preserveOriginal=true){source.inputStream()}
            assertArrayEquals(source.readBytes(),repo.mediaFile(blob).readBytes())
            val bitmap=com.yohaku.expensetracker.ui.features.decodeMedia(repo,blob,2048)
            assertEquals(20,bitmap.width);assertEquals(40,bitmap.height)
        }
    }
    @Test fun optimizedPhotosBakeOrientationStripMetadataAndHashStoredJpeg()=runBlocking {
        val source=File(root,"camera-large.jpg")
        android.graphics.Bitmap.createBitmap(4200,3000,android.graphics.Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.BLUE);source.outputStream().use{compress(android.graphics.Bitmap.CompressFormat.JPEG,92,it)};recycle()}
        android.media.ExifInterface(source.path).apply{
            setAttribute(android.media.ExifInterface.TAG_ORIENTATION,android.media.ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE,"35/1,30/1,0/1");setAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE_REF,"N")
            setAttribute(android.media.ExifInterface.TAG_GPS_LONGITUDE,"139/1,30/1,0/1");setAttribute(android.media.ExifInterface.TAG_GPS_LONGITUDE_REF,"E");saveAttributes()
        }
        val d=draft();repo.saveDraft(book,d)
        for(receipt in listOf(true,false)) {
            val result=repo.importMediaResult(book,d.mediaSession,"camera-large.JPG","image/jpeg",receipt){source.inputStream()};val blob=result.blob
            assertFalse(result.optimizationFailed);assertEquals("image/jpeg",blob.mime);assertEquals("camera-large.jpg",blob.originalName)
            val file=repo.mediaFile(blob);val bitmap=android.graphics.BitmapFactory.decodeFile(file.path)
            val expected=optimizedPhotoSize(3000,4200,receipt)
            assertEquals(expected.width,bitmap.width);assertEquals(expected.height,bitmap.height);bitmap.recycle()
            val exif=android.media.ExifInterface(file.path)
            assertNull(exif.getAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE));assertNull(exif.getAttribute(android.media.ExifInterface.TAG_GPS_LONGITUDE));assertEquals(android.media.ExifInterface.ORIENTATION_UNDEFINED,exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,android.media.ExifInterface.ORIENTATION_UNDEFINED))
            assertEquals(file.length(),blob.size);assertEquals(MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)},blob.checksum)
            val preview=com.yohaku.expensetracker.ui.features.decodeMedia(repo,blob,512);val previewSize=scaledPhotoSize(expected.width,expected.height,512)
            assertEquals(previewSize.width,preview.width);assertEquals(previewSize.height,preview.height);preview.recycle()
        }
        val original=repo.importMedia(book,d.mediaSession,"camera-large.JPG","image/jpeg",preserveOriginal=true){source.inputStream()}
        assertArrayEquals(source.readBytes(),repo.mediaFile(original).readBytes())
    }
    @Test fun transparentImagesUseWhiteAndNonImagesOrUndecodableImagesKeepBytes()=runBlocking {
        val d=draft();repo.saveDraft(book,d)
        val png=ByteArrayOutputStream().also{out->android.graphics.Bitmap.createBitmap(32,32,android.graphics.Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.TRANSPARENT);compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);recycle()}}.toByteArray()
        val white=repo.importMediaResult(book,d.mediaSession,"transparent.png","image/png"){png.inputStream()}
        assertFalse(white.optimizationFailed);assertEquals("image/jpeg",white.blob.mime)
        val bitmap=android.graphics.BitmapFactory.decodeFile(repo.mediaFile(white.blob).path);assertEquals(android.graphics.Color.WHITE,bitmap.getPixel(16,16));bitmap.recycle()
        for(mime in listOf("application/pdf","image/png")) {
            val bytes=byteArrayOf(1,2,3,4);val result=repo.importMediaResult(book,d.mediaSession,"source.bin",mime){bytes.inputStream()}
            assertEquals(mime=="image/png",result.optimizationFailed);assertEquals(mime,result.blob.mime);assertEquals("source.bin",result.blob.originalName)
            assertArrayEquals(bytes,repo.mediaFile(result.blob).readBytes())
        }
        assertTrue(root.walkTopDown().none{it.name.endsWith(".part")})
    }
    @Test fun readFailureAndCancellationLeaveNoPartialFilesOrReferences()=runBlocking {
        val d=draft();repo.saveDraft(book,d)
        for(cancel in listOf(false,true)) {
            try {
                repo.importMediaResult(book,d.mediaSession,"broken.png","image/png"){object:InputStream(){override fun read():Int=if(cancel)throw kotlinx.coroutines.CancellationException("cancel")else throw IOException("read failed")}}
                fail("Import should fail")
            }catch(e:Exception){if(cancel)assertTrue(e is kotlinx.coroutines.CancellationException)else assertTrue(e is IOException)}
            assertTrue(snapshot().files.isEmpty());assertTrue(stored().attachmentIds.isEmpty());assertTrue(root.walkTopDown().none{it.isFile})
        }
    }
    @Test fun receiptOrderRevisionsSoftDeleteAndMediaCommitAreAtomic()=runBlocking {
        val d=draft();repo.saveDraft(book,d)
        val first=repo.importMedia(book,d.mediaSession,"1.png","image/png",true){byteArrayOf(1).inputStream()}
        val second=repo.importMedia(book,d.mediaSession,"2.png","image/png",true){byteArrayOf(2).inputStream()}
        val event=repo.saveEntry(book,stored())
        val original=snapshot().receipts.single();assertEquals(1,original.revision)
        assertEquals(listOf(first.id,second.id),snapshot().entryReceiptImages(event).map{it.id})
        val edit=draft(event).copy(receiptFileIds=listOf(second.id,first.id));repo.saveDraft(book,edit)
        val review=repo.previewEntry(book,edit);assertEquals(1,snapshot().receipts.single().revision)
        assertNotNull(repo.commitEntryReview(book,review))
        val receipt=snapshot().receipts.single();assertEquals(original.id,receipt.id);assertEquals(2,receipt.revision)
        assertEquals(listOf(second.id,first.id),snapshot().entryReceiptImages(event).map{it.id});assertEquals(4,snapshot().receiptImages.size)
        val revision=repo.dao.revisions(book,event).first().first()
        assertEquals(2,repo.json.decodeFromString<LedgerDetails>(revision.afterDetailsJson!!).receiptRevision)
        repo.deleteEntry(book,event);repo.collectUnusedMedia();assertTrue(repo.mediaFile(first).isFile)
        repo.restoreEntry(book,event);assertEquals(listOf(second.id,first.id),snapshot().entryReceiptImages(event).map{it.id})
        assertEquals(1,snapshot().events.size);assertEquals(9000L,snapshot().balance(snapshot().accounts.single()))
        val other=repo.createBook("別",Currency.JPY)
        val wrong=edit.copy(receiptFileIds=listOf(repo.id()))
        try{repo.previewEntry(book,wrong);fail("Unowned file accepted")}catch(_:IllegalArgumentException){}
        assertTrue(repo.snapshot(repo.dao.book(other)!!).first().files.isEmpty())
    }
    @Test fun refundAndTransferUseTheSamePreparedMediaTransaction()=runBlocking {
        val original=repo.saveEntry(book,draft().copy(mediaInitialized=false))
        val refund=draft().copy(kind=EntryKind.REFUND,originalEventId=original,amount="100",accountAmount="100")
        repo.saveDraft(book,refund)
        val file=repo.importMedia(book,refund.mediaSession,"refund.bin","application/octet-stream"){byteArrayOf(7).inputStream()}
        val refundId=repo.commitEntryReview(book,repo.previewEntry(book,stored()))!!
        assertEquals(listOf(file.id),snapshot().entryAttachments(refundId).map{it.id})
        val destination=repo.saveAccount(book,"银行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
        val transfer=draft().copy(kind=EntryKind.TRANSFER,toAccountId=destination,amount="100",toAmount="100",fromConfirmed=true,toConfirmed=true)
        repo.saveDraft(book,transfer)
        val transferFile=repo.importMedia(book,transfer.mediaSession,"transfer.bin","application/octet-stream"){byteArrayOf(8).inputStream()}
        val transferId=repo.commitEntryReview(book,repo.previewEntry(book,stored()))!!
        assertEquals(listOf(transferFile.id),snapshot().entryAttachments(transferId).map{it.id})
        assertEquals(3,snapshot().events.size);assertEquals(9000L,snapshot().balance(snapshot().accounts.first{it.id==account}))
    }
    @Test fun expiredRecordReleasesItsLastAttachmentAndReceiptReferences()=runBlocking {
        val d=draft();repo.saveDraft(book,d)
        val image=repo.importMedia(book,d.mediaSession,"old.png","image/png",true){byteArrayOf(1,2,3).inputStream()}
        val event=repo.saveEntry(book,stored().copy(attachmentIds=listOf(image.id)))
        val old=LedgerRepository(db,java.time.Clock.fixed(java.time.Instant.parse("2026-08-01T00:00:00Z"),java.time.ZoneOffset.UTC),root)
        old.deleteEntry(book,event)
        val now=LedgerRepository(db,java.time.Clock.fixed(java.time.Instant.parse("2026-10-14T00:00:00Z"),java.time.ZoneOffset.UTC),root)
        now.purgeExpired();now.collectUnusedMedia()
        assertTrue(snapshot().events.isEmpty());assertTrue(snapshot().attachments.isEmpty());assertTrue(snapshot().receiptImages.isEmpty());assertTrue(snapshot().files.isEmpty());assertFalse(repo.mediaFile(image).exists())
    }
    @Test fun strictPerFileLimitStreamsAndLeavesNoPartialBlobOrDraftReference()=runBlocking {
        org.junit.Assume.assumeTrue("Requires perf=true", testOption("perf"))
        val d=draft();repo.saveDraft(book,d)
        try {
            repo.importMedia(book,d.mediaSession,"too-large.bin","application/octet-stream") {
                object:InputStream(){var remaining=MEDIA_FILE_LIMIT;override fun read():Int{if(remaining==0L)return -1;remaining--;return 0};override fun read(buffer:ByteArray,offset:Int,length:Int):Int{if(remaining==0L)return -1;val count=minOf(remaining,length.toLong()).toInt();java.util.Arrays.fill(buffer,offset,offset+count,0.toByte());remaining-=count;return count}}
            };fail("100000000 bytes accepted")
        }catch(_:IllegalArgumentException){}
        assertTrue(snapshot().files.isEmpty());assertTrue(snapshot().stagedMedia.isEmpty());assertTrue(stored().attachmentIds.isEmpty());assertTrue(root.walkTopDown().none{it.isFile})
    }
}
