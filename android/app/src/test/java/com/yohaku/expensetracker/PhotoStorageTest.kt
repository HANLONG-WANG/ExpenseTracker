package com.yohaku.expensetracker

import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.data.backup.*
import org.junit.Test
import org.junit.Assert.*

class PhotoStorageTest {
    @Test fun receiptLimitsShortEdgeAndAttachmentsLimitLongEdgeWithoutUpscaling() {
        assertEquals(PhotoSize(2000,6000),optimizedPhotoSize(3000,9000,true))
        assertEquals(PhotoSize(6000,2000),optimizedPhotoSize(9000,3000,true))
        assertEquals(PhotoSize(1920,2560),optimizedPhotoSize(3000,4000,false))
        assertEquals(PhotoSize(2560,1920),optimizedPhotoSize(4000,3000,false))
        for(receipt in listOf(true,false))assertEquals(PhotoSize(1200,1600),optimizedPhotoSize(1200,1600,receipt))
        assertEquals(PhotoSize(3072,4096),scaledPhotoSize(4500,6000,4096))
    }
    @Test fun jpegNameReplacesExtension() {
        assertEquals("IMG_1234.jpg",jpegFileName("IMG_1234.HEIC"))
        assertEquals("receipt.page.jpg",jpegFileName("receipt.page.png"))
        assertEquals("receipt.jpg",jpegFileName("receipt"))
    }
    @Test fun oldBackupDefaultsToSpaceSaving() {
        assertFalse(BackupArchive.json.decodeFromString<BackupPreferences>("{}").photoSaveOriginal)
        assertTrue(BackupArchive.json.decodeFromString<BackupPreferences>("{\"photoSaveOriginal\":true}").photoSaveOriginal)
    }
}
