package com.yohaku.expensetracker.ui.features

import com.yohaku.expensetracker.data.repository.ArrangementMoneyRow
import com.yohaku.expensetracker.domain.finance.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class FinancialDisplayFactsTest {
    private val original=FinancialRuleVersion("v1","book","card",FinanceMode.REVOLVING,"2026-11-01","Asia/Tokyo",
        billing=BillingRule(paymentDay=15,paymentAccountId="bank"),
        revolving=RevolvingRule(tiers=listOf(PaymentTier(10000,1000),PaymentTier(null,2000))),
        deadlines=listOf(ChangeDeadline("bill",FinanceMode.REVOLVING,"2026-11-05","15:00","Asia/Tokyo")))

    @Test fun firstVersionAndUnchangedConditionsDoNotInventChanges() {
        val first=FinancialDisplayFacts.rule(null,original)
        assertTrue(first.firstVersion)
        assertTrue(first.fields.any{it.fieldKey=="billing.paymentDay"})
        assertTrue(first.fields.any{it.fieldKey=="revolving.tiers.1.upToMinor"&&it.after==JsonNull})
        assertFalse(first.fields.any{it.fieldKey=="id"})
        assertTrue(FinancialDisplayFacts.rule(original,original.copy(id="v2")).fields.isEmpty())
        val rate=original.copy(revolving=original.revolving!!.copy(interest=InterestRule(annualRate="0.10")))
        assertTrue(FinancialDisplayFacts.rule(rate,rate.copy(revolving=rate.revolving!!.copy(interest=InterestRule(annualRate="0.100")))).fields.isEmpty())
    }

    @Test fun clearingAndRemovingNestedFieldsKeepsBothSidesIncludingCommandSnapshots() {
        val changes=FinancialDisplayFacts.rule(original,original.copy(billing=original.billing!!.copy(paymentAccountId=null),
            revolving=original.revolving!!.copy(tiers=listOf(PaymentTier(10000,1200))),deadlines=emptyList()))
        assertTrue(changes.fields.any{it.fieldKey=="billing.paymentAccountId"&&it.before==JsonPrimitive("bank")&&it.after==JsonNull})
        assertTrue(changes.fields.any{it.fieldKey=="revolving.tiers.0.paymentMinor"&&it.after==JsonPrimitive(1200)})
        assertTrue(changes.fields.any{it.fieldKey=="revolving.tiers.1.paymentMinor"&&it.after==null})
        assertTrue(changes.fields.any{it.fieldKey=="revolving.tiers.1.upToMinor"&&it.before==JsonNull&&it.after==null})
        assertTrue(changes.fields.any{it.fieldKey=="deadlines.0.billId"&&it.after==null})
        val before=Json.parseToJsonElement("""{"unsupportedReason":"special","billing":{"paymentDay":15}}""").jsonObject
        val after=Json.parseToJsonElement("""{"unsupportedReason":"","billing":null}""").jsonObject
        val snapshot=FinancialDisplayFacts.rule(before,after)
        assertEquals(setOf("unsupportedReason","billing.paymentDay"),snapshot.fields.map{it.fieldKey}.toSet())
        assertEquals(JsonPrimitive(""),snapshot.fields.first{it.fieldKey=="unsupportedReason"}.after)
        assertNull(snapshot.fields.first{it.fieldKey=="billing.paymentDay"}.after)
    }

    @Test fun datesUseUnionAndSameDayListedSumsWithoutInventingUnknownComposition() {
        val a=ArrangementMoneyRow("2026-11-05",80,20,100,"bank")
        val b=ArrangementMoneyRow("2026-11-05",null,null,50,"cash")
        val next=a.copy(date="2026-11-10")
        val rows=FinancialDisplayFacts.dates(listOf(a,b),listOf(next))
        assertEquals(listOf("2026-11-05","2026-11-10"),rows.map{it.date})
        assertEquals(150L,rows.first().before!!.totalMinor)
        assertNull(rows.first().before!!.principalMinor)
        assertNull(rows.first().after)
        assertEquals(-150L,rows.first().deltaMinor)
        assertEquals(100L,rows.last().deltaMinor)
    }

    @Test fun equalTotalsStillShowPaymentAccountAndCompositionChangesButIgnoreOrder() {
        val a=ArrangementMoneyRow("2026-11-05",80,20,100,"bank")
        val b=a.copy(paymentAccountId="cash")
        assertTrue(FinancialDisplayFacts.dates(listOf(a),listOf(b)).single().changed)
        assertTrue(FinancialDisplayFacts.dates(listOf(a),listOf(a.copy(principalMinor=90,feeMinor=10))).single().changed)
        assertFalse(FinancialDisplayFacts.dates(listOf(a,b),listOf(b,a)).single().changed)
    }
    @Test fun planScopeUsesCapturedBillsAndKeepsIncompleteAndUnknownAmounts() {
        fun obj(value:String)=Json.parseToJsonElement(value).jsonObject
        val plan=obj("""{"principalMinor":10000,"complete":false}""")
        val old=obj("""{"id":"bill","active":true,"confirmed":false,"predictedDate":"2026-12-10","predictedPrincipalMinor":6000,"predictedFeeMinor":null,"predictedTotalMinor":6000,"paymentAccountId":"bank","basisJson":{"ruleIds":["v1"]}}""")
        val next=JsonObject(old+("predictedTotalMinor" to JsonPrimitive(7000))+("predictedPrincipalMinor" to JsonPrimitive(7000)))
        val firstStep=FinancialDisplayFacts.plan(null,plan,emptyList(),listOf(old))
        val secondStep=FinancialDisplayFacts.plan(plan,plan,listOf(old),listOf(next))
        assertEquals(6000L,firstStep.after.totalMinor)
        assertEquals(6000L,secondStep.before!!.totalMinor)
        assertEquals(7000L,secondStep.after.totalMinor)
        assertFalse(secondStep.after.complete)
        assertNull(secondStep.after.feeMinor)
        assertEquals(0,secondStep.retainedOther)
        val differentBasis=JsonObject(old+("basisJson" to obj("""{"ruleIds":["v2"]}""")))
        assertEquals(0,FinancialDisplayFacts.plan(plan,plan,listOf(old),listOf(differentBasis)).retainedOther)
        val capturedPool=obj("""{"termsJson":{"pool":{"facts":{"retained":[{"bill":{"id":"kept"}}]}}}}""")
        assertEquals(setOf("kept"),FinancialDisplayFacts.poolRetainedBillIds(capturedPool))
        val reconciliation=obj("""{"effectiveDate":"2026-12-10","principalMinor":10000,"openingSourceMinor":null,"manualComplete":false,"loanLimitDeltaMinor":null,"note":""}""")
        assertEquals(listOf("effectiveDate","principalMinor","manualComplete"),FinancialDisplayFacts.reconciliation(null,reconciliation,false).map{it.fieldKey})
        val missingFee=JsonObject(next+("basisJson" to obj("""{"additionalFeeUnknown":true}""")))
        assertNull(FinancialDisplayFacts.plan(plan,plan,listOf(old),listOf(missingFee)).after.totalMinor)
    }

    @Test fun limitRelationshipsFollowConfigurationWithoutInferringIndependentCaps() {
        fun config(sharing:String?)=buildJsonObject {
            put("sharing",sharing?.let(::JsonPrimitive)?:JsonNull);put("totalMinor",100000)
            put("installmentMinor",80000);put("revolvingMinor",60000);put("combinedMinor",70000)
        }
        val total=FinancialDisplayFacts.limitRelationship(config("TOTAL_ONLY"))
        assertEquals(100000L,total.total);assertNull(total.installment);assertNull(total.revolving);assertNull(total.combined)
        val separate=FinancialDisplayFacts.limitRelationship(config("SEPARATE_CAPS"))
        assertEquals(80000L,separate.installment);assertEquals(60000L,separate.revolving);assertNull(separate.combined)
        val shared=FinancialDisplayFacts.limitRelationship(config("SHARED_SUBPOOL"))
        assertEquals(70000L,shared.combined);assertNull(shared.installment);assertNull(shared.revolving)
        val unknown=FinancialDisplayFacts.limitRelationship(config(null))
        assertTrue(unknown.configured);assertNull(unknown.sharing);assertNull(unknown.installment);assertNull(unknown.revolving)
        assertFalse(FinancialDisplayFacts.limitRelationship(null as JsonObject?).configured)
        assertFalse(FinancialDisplayFacts.limitChanges(null,config("TOTAL_ONLY")).any{it.fieldKey in setOf("installmentMinor","revolvingMinor","combinedMinor")})
        val removed=FinancialDisplayFacts.limitChanges(config("SEPARATE_CAPS"),config("TOTAL_ONLY"))
        assertEquals(JsonPrimitive(80000),removed.first{it.fieldKey=="installmentMinor"}.before)
        assertNull(removed.first{it.fieldKey=="installmentMinor"}.after)
        assertNull(removed.first{it.fieldKey=="revolvingMinor"}.after)
    }

    @Test fun financialTimeUsesExplicitZoneAndDisplayNeverRewritesSecondsOrFraction() {
        val raw="2026-11-04T01:59:42.123456789Z"
        val instant=java.time.Instant.parse(raw);val tokyo=java.time.ZoneId.of("Asia/Tokyo")
        assertEquals("2026年11月4日 10:59 · Asia/Tokyo",FinancialDisplayFacts.time(instant,tokyo,"zh"))
        assertEquals("2026年11月4日 10:59・Asia/Tokyo",FinancialDisplayFacts.time(instant,tokyo,"ja"))
        assertEquals("2026年11月3日 17:59 · America/Los_Angeles",FinancialDisplayFacts.time(instant,java.time.ZoneId.of("America/Los_Angeles"),"zh"))
        assertEquals(raw,instant.toString())
        assertEquals(raw,financialEditedTime(raw,java.time.LocalDateTime.parse("2026-11-04T10:59"),tokyo))
        assertEquals("2026-11-04T02:00:00Z",financialEditedTime(raw,java.time.LocalDateTime.parse("2026-11-04T11:00"),tokyo))
    }

}
