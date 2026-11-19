package com.yohaku.expensetracker

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
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
    @Test fun onlyTodaysNewUnlinkedNamedExpensesKeepCapturedPlace() {
        val today="2026-11-01"
        val draft=EntryDraft(categoryId="c",date=today,merchant=" 新店 ",capturedPlace=CapturedPlace(35.0,139.0,12.0))
        assertEquals(EventPlaceEntity("b","e",35.0,139.0,12.0),draft.placeForNewEntry("b","e",today))
        listOf(draft.copy(id="existing"),draft.copy(kind=EntryKind.INCOME),draft.copy(merchantId="m"),
            draft.copy(merchant="  "),draft.copy(date="2026-10-31"),draft.copy(capturedPlace=null)).forEach {
            assertNull(it.placeForNewEntry("b","e",today))
        }
        val empty=EntryDraft(categoryId="c",date=today)
        assertFalse(empty.copy(capturedPlace=draft.capturedPlace).hasManualInput(empty))
    }
    @Test fun namedPlaceIsSavedOnlyForTheSelectedMerchantOnANewUnmatchedExpenseToday() {
        val today="2026-11-01"
        val fix=CapturedPlace(36.0,140.0,12.0)
        val draft=EntryDraft(date=today,merchantId=merchant.id,merchant=merchant.name,capturedPlace=fix)
        val requested=draft.copy(merchantPlace=EntryMerchantPlace(merchant.id,fix," 駅前店 "))
        val s=snapshot()
        val location=MerchantLocationEntity(book.id,"new",merchant.id,"駅前店",36.0,140.0,100)
        assertEquals(location,s.merchantLocationForNewEntry(requested,today,"new"))
        listOf(draft,requested.copy(merchantId=null),requested.copy(date="2026-10-31"),requested.copy(id="existing"),
            requested.copy(kind=EntryKind.INCOME),requested.copy(capturedPlace=null)).forEach {
            assertNull(s.merchantLocationForNewEntry(it,today,"new"))
        }
        assertNull(snapshot(places=listOf(location)).merchantLocationForNewEntry(requested,today,"another"))
    }
    @Test fun checkedPlaceNeedsANameAndCountsAsManualInput() {
        val fix=CapturedPlace(36.0,140.0,12.0)
        val base=EntryDraft(date="2026-11-01",merchantId=merchant.id,capturedPlace=fix)
        val checked=base.copy(merchantPlace=EntryMerchantPlace(merchant.id,fix,"  "))
        assertTrue(checked.hasManualInput(base))
        assertTrue(checked.hasManualInput(null))
        val error=assertThrows(IllegalArgumentException::class.java){
            snapshot().merchantLocationForNewEntry(checked,base.date,"new")
        }
        assertEquals("请输入地点名称 / 場所の名前を入力してください",error.message)
    }
    @Test fun clustersUseNewestAnchorAndMostAccurateCoordinates() {
        val dates=listOf("2026-11-03","2026-11-02","2026-11-01")
        val events=dates.mapIndexed{i,date->EventEntity("b","e$i","EXPENSE","a",null,100,"JPY",100,date," 新店 ","")}
        val points=listOf(EventPlaceEntity("b","e0",35.0,139.0,40.0),
            EventPlaceEntity("b","e1",35.00045,139.0,5.0),EventPlaceEntity("b","e2",35.0027,139.0,10.0))
        val s=snapshot().copy(events=events,eventPlaces=points)
        val clusters=s.recordedPlaces("新店")
        assertEquals(2,clusters.size)
        assertEquals(2,clusters[0].count);assertEquals("2026-11-03",clusters[0].latestDate)
        assertEquals(35.00045,clusters[0].latitude,0.0);assertEquals(139.0,clusters[0].longitude,0.0)
        assertEquals(1,clusters[1].count)
        val linked=s.copy(eventMerchants=listOf(EventMerchantEntity("b","e0","m"),EventMerchantEntity("b","e1","m"))).recordedPlaces("新店")
        // e0/e1 are still one pending cluster; explicit ownership no longer hides their positions.
        assertEquals(2,linked.size);assertEquals(setOf("e0","e1"),linked.first().eventIds)
        assertEquals("m",linked.first().merchantId);assertEquals(setOf("e2"),linked.last().eventIds)
    }
}
