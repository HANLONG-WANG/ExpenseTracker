package com.yohaku.expensetracker

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import org.junit.Assert.*
import org.junit.Test

class MerchantLocationTest {
    private val book=BookEntity("b","Book","JPY","2026-10-01")
    private val merchant=MerchantEntity("b","m","喫茶 原名")
    private val place=MerchantLocationEntity("b","p","m","店頭",35.0,139.0)
    private fun snapshot(merchants:List<MerchantEntity> = listOf(merchant),places:List<MerchantLocationEntity> = listOf(place))=
        BookSnapshot(book,emptyList(),emptyList(),emptyList(),emptyList(),null,merchants=merchants,merchantLocations=places)
    @Test fun defaultRadiusAndAccuracyCircleDetermineConfidence() {
        assertEquals(100,place.radiusMeters)
        val trusted=snapshot().merchantCandidates(35.0,139.0,15.0).single()
        assertTrue(trusted.trustworthy)
        val uncertain=snapshot().merchantCandidates(35.0,139.0,200.0).single()
        assertFalse(uncertain.trustworthy)
        assertTrue(snapshot().merchantCandidates(36.0,139.0,15.0).isEmpty())
    }
    @Test fun multipleCandidatesRemainChoicesAndNeverCrossBooks() {
        val other=merchant.copy(id="other",name="お隣")
        val otherPlace=place.copy(id="other-place",merchantId="other",latitude=35.0005)
        assertEquals(2,snapshot(listOf(merchant,other),listOf(place,otherPlace)).merchantCandidates(35.0,139.0,10.0).size)
        val mixed=snapshot(listOf(merchant,other.copy(bookId="elsewhere")),listOf(place,otherPlace.copy(bookId="elsewhere")))
        assertEquals(listOf("m"),mixed.merchantCandidates(35.0,139.0,10.0).map{it.merchant.id})
        assertTrue(snapshot(listOf(merchant.copy(active=false))).merchantCandidates(35.0,139.0,10.0).isEmpty())
    }
    @Test fun sameMerchantAtSeveralPlacesStillHasOneBestCandidate() {
        val far=place.copy(id="far",latitude=35.0005,radiusMeters=500)
        val candidates=snapshot(places=listOf(place.copy(radiusMeters=5),far)).merchantCandidates(35.0,139.0,10.0)
        assertEquals(1,candidates.size)
        assertEquals("far",candidates.single().location.id)
        assertTrue(candidates.single().trustworthy)
    }
}
