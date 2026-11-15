package com.yohaku.expensetracker

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.finance.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class FinancialSourceOwnershipTest {
    private val book=BookEntity("b","book","JPY","2026-10-01")
    private val account=AccountEntity("b","card","card","CREDIT","JPY",10000,book.startDate)
    private fun plan(id:String,principal:Long?,origin:String="OPENING",mode:String="SINGLE",source:String?=null,active:Boolean=true)=
        FinancialPlanEntity("b",id,account.id,id,mode,origin,source,"JPY",principal,false,0,active=active)
    private fun pool(opening:Long)=plan("pool",opening,"POOL","REVOLVING").copy(termsJson=Json.encodeToString(
        FinancialPredictionInput(account.id,"pool",FinanceMode.REVOLVING,FinancialScheduleTerms(book.startDate,emptyList()),pool=FinancialPoolInput(opening,PoolSourceDate.TRANSACTION))))
    private fun graph(plans:List<FinancialPlanEntity>,changes:List<FinancialArrangementEntity> = emptyList())=
        FinancialGraph(book,emptyList(),plans,emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),changes,emptyList())
    private fun change(from:String,to:String)=FinancialArrangementEntity("b","change",from,to,book.startDate,"2026-10-02T00:00:00Z",3000,0,"CANCELLED_BY_PLATFORM",false,"{}","{}","{}",0)

    @Test fun openingCountsConvertedRootsAndPoolWithoutCountingAdoptedDebtTwice() {
        val f=graph(listOf(plan("original",3000,active=false),plan("member",3000,"POOL_MEMBER","REVOLVING",active=false),
            pool(5000),plan("adopted",5000,"POOL_RETAINED","REVOLVING",active=false)),listOf(change("original","member")))
        assertEquals(8000L,f.allocatedOpeningPrincipal(account.id))
        f.requireOpeningPrincipal(account,2000)
        assertThrows(IllegalArgumentException::class.java){f.requireOpeningPrincipal(account,2001)}
        assertEquals(5000L,f.allocatedOpeningPrincipal(account.id,"original"))
        val unknown=graph(listOf(plan("unknown",null)))
        assertNull(unknown.allocatedOpeningPrincipal(account.id))
        assertThrows(IllegalArgumentException::class.java){unknown.requireOpeningPrincipal(account,1)}
    }

    @Test fun sourceOwnershipSurvivesInactivePoolMembershipAndAllowsOwnArrangementRecalculation() {
        val event=EventEntity("b","purchase","EXPENSE",account.id,null,3000,"JPY",3000,"2026-10-02","","",createdAt=0)
        val s=BookSnapshot(book,listOf(account),emptyList(),listOf(event),emptyList(),null)
        val limits=CreditLimitGraph(book,emptyList(),emptyList(),emptyList(),emptyList(),emptyList())
        val original=plan("original",3000,"EVENT",source=event.id,active=false)
        val member=plan("member",3000,"POOL_MEMBER","REVOLVING",event.id,false)
        val f=graph(listOf(original,member,pool(0)),listOf(change(original.id,member.id)))
        assertThrows(IllegalArgumentException::class.java){FinancialReviewState(s,f,limits).requireFinancialSourceAvailable(event.id)}
        val arranged=f.copy(plans=listOf(original,member.copy(origin="ARRANGEMENT",active=true)))
        FinancialReviewState(s,arranged,limits).requireFinancialSourceAvailable(event.id,member.id)
        val removed=FinancialReviewState(s.copy(events=emptyList()),f,limits)
        assertTrue(removed.financialSourceOwned(event.id))
        assertFalse(removed.financialSourceOwned(event.id,member.id))
        val pooled=s.copy(events=listOf(event.copy(cardPaymentMode="REVOLVING")))
        assertThrows(IllegalArgumentException::class.java){FinancialReviewState(pooled,graph(listOf(pool(0))),limits).requireFinancialSourceAvailable(event.id)}
    }
}
