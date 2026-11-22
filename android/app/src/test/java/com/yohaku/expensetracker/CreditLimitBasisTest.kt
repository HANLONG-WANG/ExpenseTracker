package com.yohaku.expensetracker

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CreditLimitBasisTest {
    @Test fun changingOnlyPaymentModeInvalidatesSavedBasis() {
        val fixture=CashProjectionFixture()
        val date="2026-10-15"
        val card=fixture.card.id
        val purchase=EventEntity("b","purchase","EXPENSE",card,"cat",1000,"JPY",1000,date,"","",cardPaymentMode="SINGLE")
        val posting=PostingEntity("b","posting",purchase.id,card,1000,"JPY",date)
        val original=fixture.snapshot().copy(events=listOf(purchase),storedPostings=listOf(posting))
        val limits=CreditLimitGraph(fixture.book,emptyList(),emptyList(),emptyList(),emptyList(),emptyList())
        val moves=creditLimitMovements(original,fixture.graph,limits,card,date,"PAYMENT_DATE","TRANSACTION_DATE")
        val config=CreditLimitConfigEntity("b","config",card,1,date,"JPY",10000,5000,5000,"SEPARATE_CAPS",null,date,1000,0,0,
            Json.encodeToString(LimitAnchor(moves)),"PAYMENT_DATE","PRINCIPAL_ONLY","TRANSACTION_DATE","Asia/Tokyo",0)
        val graph=limits.copy(configs=listOf(config))
        assertEquals(5000L,accountCreditLimit(original,fixture.graph,graph,card,date).installment.availableMinor)
        val updated=original.copy(events=listOf(purchase.copy(cardPaymentMode="INSTALLMENT")))
        val result=accountCreditLimit(updated,fixture.graph,graph,card,date)
        assertNull(result.installment.availableMinor);assertTrue("limit.basisChanged" in result.issues)
    }
}
