package com.yohaku.expensetracker

import com.yohaku.expensetracker.data.db.MerchantEntity
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.merchant.*
import com.yohaku.expensetracker.domain.model.EntryKind
import org.junit.Assert.*
import org.junit.Test

class MerchantFoundationTest {
    private val shop=MerchantEntity("book","shop","Coffee")
    private fun resolve(name:String?="Coffee",id:String?=null,kind:EntryKind=EntryKind.EXPENSE,
        merchants:List<MerchantEntity> = listOf(shop))=resolveMerchant("book",kind,id,name,merchants)

    @Test fun normalizedUniqueNamesIncludeDisabledMerchantsAndStayBookScoped() {
        assertEquals(MerchantResolution.Resolved(shop),resolve("  ＣＯＦＦＥＥ  "))
        val disabled=shop.copy(active=false)
        assertEquals(MerchantResolution.Resolved(disabled),resolve("coffee",merchants=listOf(disabled,shop.copy(bookId="other"))))
        assertEquals(MerchantResolution.None,resolve("Tea"))
        val duplicate=shop.copy(id="second",name="coffee")
        assertEquals(setOf(shop,duplicate),(resolve(merchants=listOf(duplicate,shop)) as MerchantResolution.Ambiguous).merchants.toSet())
    }

    @Test fun explicitIdUsesFormalNameAndRejectsInvalidOwnershipOrContradictoryText() {
        assertEquals(MerchantResolution.Resolved(shop),resolve("ＣＯＦＦＥＥ",shop.id))
        assertEquals(MerchantResolution.Resolved(shop),resolve(null,shop.id))
        assertEquals(MerchantResolution.Resolved(shop),resolve(id=shop.id,merchants=listOf(shop,shop.copy(id="duplicate"))))
        assertEquals(MerchantResolution.Invalid(MerchantResolutionError.INVALID_MERCHANT_ID),
            resolve(id=shop.id,merchants=listOf(shop.copy(bookId="other"))))
        assertEquals(MerchantResolution.Invalid(MerchantResolutionError.ID_NAME_MISMATCH),resolve("Tea",shop.id))
    }

    @Test fun ordinaryIncomeAndTransfersDoNotAutoResolveButExplicitIncomeIsSupported() {
        assertEquals(MerchantResolution.None,resolve(kind=EntryKind.INCOME))
        assertEquals(MerchantResolution.None,resolve(kind=EntryKind.TRANSFER))
        assertEquals(MerchantResolution.Resolved(shop),resolve(id=shop.id,kind=EntryKind.INCOME))
    }

    @Test fun refundUsesOriginalExpenseInsteadOfItsOwnNameOrId() {
        assertEquals(MerchantResolution.Invalid(MerchantResolutionError.ORIGINAL_EXPENSE_REQUIRED),resolve(kind=EntryKind.REFUND))
        assertEquals(MerchantResolution.Resolved(shop),resolveMerchant("book",EntryKind.REFUND,"unrelated","Tea",listOf(shop),
            OriginalExpenseMerchant("book",shop.id,"stale display")))
        assertEquals(MerchantResolution.None,resolveMerchant("book",EntryKind.REFUND,null,"Coffee",listOf(shop),
            OriginalExpenseMerchant("book",null,"Tea")))
    }

    @Test fun fingerprintIgnoresCollectionOrderButDetectsContentScopeAndIntentChanges() {
        val merchant=MerchantObjectState(MerchantObjectType.MERCHANT,"shop",mapOf("name" to "Coffee","active" to "true"))
        val place=MerchantObjectState(MerchantObjectType.PENDING_PLACE,"event",mapOf("latitude" to "35.0"))
        val basis=MerchantPreviewBasis("book",MerchantOperationType.RENAME,mapOf("name" to "Tea"),listOf(merchant,place),
            listOf(MerchantObjectChange(merchant,merchant.copy(fields=mapOf("name" to "Tea","active" to "true")))))
        val fingerprint=merchantImpactFingerprint(basis)
        assertEquals(fingerprint,merchantImpactFingerprint(basis.copy(dependencies=listOf(place,merchant.copy(fields=linkedMapOf("active" to "true","name" to "Coffee"))))))
        val changed= basis.copy(dependencies=listOf(merchant,place.copy(fields=mapOf("latitude" to "36.0"))))
        assertNotEquals(fingerprint,merchantImpactFingerprint(changed))
        assertNotEquals(fingerprint,merchantImpactFingerprint(basis.copy(dependencies=listOf(merchant))))
        assertNotEquals(fingerprint,merchantImpactFingerprint(basis.copy(intent=mapOf("name" to "Other"))))
        assertNotEquals(fingerprint,merchantImpactFingerprint(basis.copy(changes=emptyList())))
        requireCurrentMerchantPreview(fingerprint,MerchantOperationPreview(basis,MerchantImpactCounts()))
        assertThrows(IllegalStateException::class.java){requireCurrentMerchantPreview(fingerprint,MerchantOperationPreview(changed,MerchantImpactCounts()))}
    }

    @Test fun savedSelectionUsesFormalNameAndErrorsRemainExplicit() {
        assertEquals(shop,resolveSavedMerchant("book",EntryKind.EXPENSE,shop.id,"old display",listOf(shop)))
        assertEquals(shop,resolveSavedMerchant("book",EntryKind.INCOME,shop.id,"old display",listOf(shop)))
        assertNull(resolveSavedMerchant("book",EntryKind.INCOME,null,shop.name,listOf(shop)))
        assertTrue(runCatching{resolve(name="Other",id=shop.id).merchantOrThrow()}.exceptionOrNull()!!.message!!.contains("ID 与名称不一致"))
    }
}
