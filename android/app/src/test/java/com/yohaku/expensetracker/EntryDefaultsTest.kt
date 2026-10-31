package com.yohaku.expensetracker

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class EntryDefaultsTest {
    private val bank=AccountEntity("b","bank","銀行","BANK","JPY",0,"2026-10-01")
    private val card=bank.copy(id="card",name="カード",type="CREDIT")
    private val cash=bank.copy(id="cash",name="現金",type="CASH")
    private fun event(id:String,account:String,kind:String="EXPENSE",created:Long=1)=EventEntity("b",id,kind,account,null,100,"JPY",100,"2026-10-10","","",createdAt=created)
    private fun snapshot(events:List<EventEntity> = emptyList())=BookSnapshot(BookEntity("b","生活","JPY","2026-10-01"),listOf(bank,card,cash),emptyList(),events,emptyList(),null)
    @Test fun accountDefaultsUseLastSaveNotTransactionDateAndFallbackWhenUnavailable() {
        val edited=event("edited","cash").copy(date="2025-01-01")
        val s=snapshot(listOf(edited,event("new","card",created=2),event("income","bank","INCOME",3))).copy(eventSaveTimes=mapOf("edited" to 10L,"new" to 5L))
        assertEquals("cash",s.defaultEntryAccount(EntryKind.EXPENSE));assertEquals("bank",s.defaultEntryAccount(EntryKind.INCOME))
        assertEquals("bank",s.copy(accounts=s.accounts.map{if(it.id=="cash")it.copy(active=false)else it}).defaultEntryAccount(EntryKind.EXPENSE))
        val transfer=event("transfer","card","TRANSFER")
        val t=s.copy(events=s.events+transfer,transfers=listOf(TransferEntity("b","transfer","cash",100,"JPY","JPY","2026-10-10","2026-10-10",true,true,100,"JPY","","2026-10-10")))
        assertEquals("card",t.newTransferDraft("2026-10-10").accountId);assertEquals("cash",t.newTransferDraft("2026-10-10").toAccountId)
        assertEquals("bank",t.newTransferDraft("2026-10-10",fromId="bank").accountId)
        val explicit=t.newTransferDraft("2026-10-10",toAccountId="bank");assertEquals("bank",explicit.toAccountId);assertNotEquals(explicit.accountId,explicit.toAccountId)
        assertFalse(t.transferTargets().any{it.id=="card"})
    }
    @Test fun paymentMemoryIsPerCardAndOnlyCarriesInstallmentPeriods() {
        val s=snapshot(listOf(event("payment","card","TRANSFER").copy(cardPaymentChoice="INSTALLMENT",cardPaymentPeriods=6)))
        val d=EntryDraft(accountId="card")
        assertEquals("6",s.withRememberedCardPayment(d).cardPaymentPeriods)
        val deferred=s.copy(events=listOf(s.events.single().copy(cardPaymentChoice="DEFERRED",cardPaymentMonth="2027-01",cardPaymentFeeMinor=50)))
        val next=deferred.withRememberedCardPayment(d)
        assertEquals("DEFERRED",next.cardPaymentChoice);assertEquals("",next.cardPaymentMonth);assertEquals("",next.cardPaymentFee);assertEquals("",next.cardPaymentPeriods)
        assertNull(s.withRememberedCardPayment(next.copy(accountId="bank")).cardPaymentChoice)
        assertNull(s.withRememberedCardPayment(d.copy(accountId="other-card")).cardPaymentChoice)
        val edited=d.copy(id="old",cardPaymentChoice="SINGLE");assertEquals(edited,s.withRememberedCardPayment(edited))
    }
    @Test fun untouchedDraftIgnoresCategoryAndAutomaticMerchantButKeepsRealEditsAndResumedDrafts() {
        val base=EntryDraft(accountId="bank",mediaInitialized=true)
        assertFalse(base.copy(kind=EntryKind.INCOME,amount="100").hasManualInput(base))
        val selected=base.copy(categoryId="food")
        assertFalse(selected.hasManualInput(base))
        assertFalse(selected.copy(merchant="近所",merchantId="shop",merchantLocated=true).hasManualInput(base))
        assertTrue(selected.copy(merchant="近所",merchantId="shop",merchantManuallyEdited=true).hasManualInput(base))
        assertTrue(selected.copy(amount="100").hasManualInput(base));assertTrue(selected.copy(attachmentIds=listOf("file")).hasManualInput(base))
        assertTrue(selected.hasManualInput(null));assertFalse(base.hasManualInput(null))
        val edit=selected.copy(id="old",amount="100");assertFalse(edit.hasManualInput(edit));assertTrue(edit.copy(amount="200").hasManualInput(edit))
        val transfer=base.copy(kind=EntryKind.TRANSFER,toAccountId="cash");assertFalse(transfer.hasManualInput(transfer));assertTrue(transfer.copy(amount="100").hasManualInput(transfer));assertTrue(transfer.hasManualInput(null))
    }
    @Test fun negativeBalanceWarningOnlyAppliesToDecreasingFunds() {
        assertTrue(AccountBalanceChange(bank,100,-1).needsNegativeBalanceConfirmation())
        assertTrue(AccountBalanceChange(bank,-10,-20).needsNegativeBalanceConfirmation())
        assertFalse(AccountBalanceChange(bank,-20,-10).needsNegativeBalanceConfirmation())
        assertFalse(AccountBalanceChange(bank,100,0).needsNegativeBalanceConfirmation())
        assertFalse(AccountBalanceChange(card,100,-1).needsNegativeBalanceConfirmation())
        assertFalse(AccountBalanceChange(card.copy(type="LOAN"),100,-1).needsNegativeBalanceConfirmation())
    }
    @Test fun merchantNamesMatchWidthAndCaseAndExactInputAssociatesEnabledMerchant() {
        val s=snapshot().copy(merchants=listOf(MerchantEntity("b","shop","Ｃａｆｅ １２"),MerchantEntity("b","off","Cafe 12",active=false)))
        assertEquals(listOf("shop"),s.merchants.matchingMerchants("cAFE").map{it.id})
        assertEquals("shop",s.merchantInput(EntryDraft(),"cafe 12").merchantId)
        assertNull(s.merchantInput(EntryDraft(merchantId="shop"),"Cafe 1").merchantId)
    }
}
