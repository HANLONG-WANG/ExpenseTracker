package com.yohaku.expensetracker

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class EntryPresetsTest {
    private val account=AccountEntity("b","card","カード","CREDIT","JPY",0,"2026-01-01")
    private val category=CategoryEntity("b","food","食費","EXPENSE",null,false)
    private val merchant=MerchantEntity("b","shop","お店")
    private val template=EntryTemplateEntity("b","t","水","EXPENSE","food","card","JPY",merchant="お店",merchantId="shop",note="いつもの",cardPaymentChoice="INSTALLMENT",cardPaymentPeriods=3,createdAt=1)
    private fun snapshot()=BookSnapshot(BookEntity("b","生活","JPY","2026-01-01"),listOf(account),listOf(category),emptyList(),emptyList(),null,merchants=listOf(merchant))
    private val rule=RecurrenceEntity("b","rule","月額","card","food","JPY",1200,1200,false,"MONTHLY","2026-10-01",null,merchant="お店",merchantId="shop",note="定期",cardPaymentChoice="INSTALLMENT",cardPaymentPeriods=6)
    private val occurrence=OccurrenceEntity("b","o","rule","2026-10-01","月額","card","food","JPY",1300,1300,false,activityId="trip")
    @Test fun occurrenceUsesScheduledDateSnapshotMoneyAndFixedPayment() {
        val d=snapshot().occurrenceDraft(occurrence,rule)
        assertEquals(EntryKind.EXPENSE,d.kind);assertEquals("2026-10-01",d.date);assertEquals(d.date,d.settlementDate)
        assertEquals("1300",d.amount);assertEquals("1300",d.baseAmount);assertEquals("trip",d.activityId);assertEquals("o",d.occurrenceId)
        assertEquals("card",d.accountId);assertEquals("food",d.categoryId)
        assertEquals("お店",d.merchant);assertEquals("shop",d.merchantId);assertTrue(d.merchantManuallyEdited);assertEquals("定期",d.note)
        assertEquals("INSTALLMENT",d.cardPaymentChoice);assertEquals("6",d.cardPaymentPeriods)
        val unknown=snapshot().occurrenceDraft(occurrence.copy(amountMinor=null,baseMinor=null),rule.copy(merchant="",merchantId=null))
        assertEquals("",unknown.amount);assertEquals("月額",unknown.merchant);assertTrue(unknown.merchantManuallyEdited)
    }
    @Test fun presetsRememberPaymentOnlyWhenUnspecifiedAndTemplateIsItsOwnBaseline() {
        val event=EventEntity("b","e","EXPENSE","card","food",100,"JPY",100,"2026-10-12","","",createdAt=1,cardPaymentChoice="SINGLE")
        val s=snapshot().copy(events=listOf(event))
        assertEquals("SINGLE",s.occurrenceDraft(occurrence,rule.copy(cardPaymentChoice=null,cardPaymentPeriods=null)).cardPaymentChoice)
        val fixed=s.templateDraft(template.copy(amountMinor=200),"2026-11-01")
        assertEquals("2026-11-01",fixed.date);assertEquals("200",fixed.amount);assertEquals("INSTALLMENT",fixed.cardPaymentChoice)
        assertEquals("3",fixed.cardPaymentPeriods);assertEquals("shop",fixed.merchantId);assertEquals("いつもの",fixed.note);assertTrue(fixed.merchantManuallyEdited)
        assertFalse(fixed.hasManualInput(fixed));assertTrue(fixed.copy(amount="300").hasManualInput(fixed))
        val blank=s.templateDraft(template.copy(merchant="",merchantId=null,cardPaymentChoice=null,cardPaymentPeriods=null),"2026-11-01")
        assertEquals("",blank.amount);assertFalse(blank.merchantManuallyEdited);assertEquals("SINGLE",blank.cardPaymentChoice)
        val incomeAccount=account.copy(type="BANK")
        val income=s.copy(accounts=listOf(incomeAccount),categories=listOf(category.copy(kind="INCOME"))).templateDraft(template.copy(kind="INCOME",cardPaymentChoice=null),"2026-11-01")
        assertEquals(EntryKind.INCOME,income.kind);assertNull(income.cardPaymentChoice)
    }
    @Test fun templateValidationAndUnavailableReferencesNeverSubstituteAccountsOrCategories() {
        val s=snapshot()
        val input=TemplateInput("水",EntryKind.EXPENSE,"food","card",Currency.JPY,cardPaymentChoice="INSTALLMENT",cardPaymentPeriods=3)
        s.validateTemplate(input)
        fun invalid(value:TemplateInput){assertThrows(RuntimeException::class.java){s.validateTemplate(value)}}
        invalid(input.copy(kind=EntryKind.TRANSFER));invalid(input.copy(kind=EntryKind.INCOME))
        invalid(input.copy(amountMinor=0));invalid(input.copy(currency=Currency.USD));invalid(input.copy(cardPaymentPeriods=0))
        invalid(input.copy(cardPaymentChoice="DEFERRED"));invalid(input.copy(merchantId="other-book"))
        assertEquals(null to null,fixedCardPayment(account.copy(type="BANK"),"DEFERRED",3))
        assertNull(s.templateIssue(template))
        val disabled=s.copy(accounts=listOf(account.copy(active=false),account.copy(id="another")))
        assertNotNull(disabled.templateIssue(template));assertThrows(IllegalArgumentException::class.java){disabled.templateDraft(template)}
        val archived=s.copy(categories=listOf(category.copy(active=false)))
        assertNotNull(archived.templateIssue(template));assertThrows(IllegalArgumentException::class.java){archived.validateTemplate(input)}
        val parent=category.copy(id="parent",active=false)
        assertNotNull(s.copy(categories=listOf(parent,category.copy(parentId="parent"))).templateIssue(template))
    }
    @Test fun categoryMergeRemapsOnlySourceTemplatesAndDraftInputCapturesAccountCurrency() {
        val untouched=template.copy(id="other",categoryId="other")
        val remapped=listOf(template,untouched).remapTemplateCategories(setOf("food"),"target")
        assertEquals("target",remapped[0].categoryId);assertEquals(untouched,remapped[1])
        val s=snapshot();val d=s.templateDraft(template,"2026-11-01").copy(amount="20",originalCurrency=Currency.USD,accountAmount="3000")
        assertNull(s.templateInput(d,"水",false).amountMinor)
        assertEquals(3000L,s.templateInput(d,"水",true).amountMinor)
    }
}
