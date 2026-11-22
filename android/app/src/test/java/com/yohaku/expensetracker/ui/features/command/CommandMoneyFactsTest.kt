package com.yohaku.expensetracker.ui.features.command

import com.yohaku.expensetracker.data.command.*
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.BookSnapshot
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CommandMoneyFactsTest {
    private fun j(text:String)=commandJson.parseToJsonElement(text)
    private val snapshot=BookSnapshot(BookEntity("book","跨币种","CNY","2026-11-01"),
        listOf(account("debt","JPY"),account("funds","USD"),account("fee","CNY")),emptyList(),
        listOf(EventEntity("book","payment","REPAYMENT","funds",null,20000,"USD",0,"2026-11-02","","")),emptyList(),null)
    private fun account(id:String,currency:String)=AccountEntity("book",id,id,if(id=="debt")"LOAN" else "BANK",currency,0,"2026-11-01")
    private val repayment=j("""{"eventId":"payment","debtAccountId":"debt","receivedMinor":25000,"principalMinor":20000,"interestMinor":4000,"feeMinor":1000,"feeBaseMinor":10000,"sourceFeeMinor":200,"sourceFeeBaseMinor":1500,"originalInputJson":{"sourceAccountId":"fee","debtAccountId":"debt","paid":{"currency":"CNY","minor":10000},"allocations":[{"principalMinor":20000,"interestMinor":4000,"feeMinor":1000}]}}""")
    private fun flatten(value:JsonElement,s:BookSnapshot=snapshot,effects:List<ObjectEffect> = emptyList(),after:Boolean=true):Map<String,Pair<JsonElement,String?>> {
        val units=CommandMoneyFacts(s,effects,after)
        return flattenCommandFacts(value,units,s.book.currency)
    }
    private fun assertMoney(facts:Map<String,Pair<JsonElement,String?>>,path:String,currency:String,text:String) {
        val value=facts.getValue(path)
        assertEquals(path,currency,value.second)
        assertEquals(path,text,commandMoneyDisplay(value))
    }

    @Test fun summaryUsesEachSideCurrency() {
        fun display(json:String):String? {
            val row=j(json).jsonObject
            return commandSummaryAmount(row,row["amountMinor"],"USD")
        }
        assertEquals("¥1,000",display("""{"currency":"JPY","amountMinor":1000}"""))
        assertEquals("CN¥100.00",display("""{"currency":"CNY","amountMinor":10000}"""))
    }

    @Test fun historyUsesTheZoneOfEachPaymentChange() {
        val root=j("""{"changes":[{"appliedAt":"2026-11-01T00:00:00Z","zoneId":"Asia/Tokyo"},{"appliedAt":"2026-11-02T00:00:00Z","zoneId":"Asia/Shanghai"}]}""").jsonObject
        val paths=flatten(root).keys.filter{it.endsWith(".appliedAt")}
        assertEquals(listOf("Asia/Tokyo","Asia/Shanghai"),paths.map{commandFieldZone(root,it,emptyList(),true)?.id})
    }

    @Test fun draftDetailsRetainBusinessFieldsAndHideSession() {
        val row=j("""{"mediaSession":"session","budgetExcluded":true,"feeAccountId":"bank","treatWeights":{"ME":"3"}}""").jsonObject
        assertEquals(row.keys-"mediaSession",workflowDisplayFacts("entryDraft",row).jsonObject.keys)
    }

    @Test fun repaymentFeesAndNestedAllocationsKeepTheirOwnUnitsOnBothSides() {
        val entry=ObjectEffect("entry","payment",j("""{"currency":"CNY"}"""),j("""{"currency":"USD"}"""))
        val payment=ObjectEffect("repayment","payment",repayment,repayment)
        val nested=buildJsonObject{put("repayment",repayment);put("repaymentAllocations",j("""[{"eventId":"payment","principalMinor":20000,"interestMinor":4000,"feeMinor":1000}]"""))}
        val before=flatten(nested,effects=listOf(entry,payment),after=false)
        val after=flatten(nested,effects=listOf(entry,payment))
        assertMoney(before,"repayment.sourceFeeMinor","CNY","CN¥2.00")
        assertMoney(after,"repayment.sourceFeeMinor","USD","US$2.00")
        for(facts in listOf(before,after)) {
            assertMoney(facts,"repayment.feeBaseMinor","CNY","CN¥100.00")
            assertMoney(facts,"repayment.sourceFeeBaseMinor","CNY","CN¥15.00")
            assertMoney(facts,"repayment.receivedMinor","JPY","¥25,000")
            assertMoney(facts,"repayment.feeMinor","JPY","¥1,000")
            assertMoney(facts,"repaymentAllocations.1.principalMinor","JPY","¥20,000")
            assertMoney(facts,"repayment.originalInputJson.paid.minor","CNY","CN¥100.00")
            assertMoney(facts,"repayment.originalInputJson.allocations.1.feeMinor","JPY","¥1,000")
        }
    }

    @Test fun borrowingSeparatesDebtFundingFeePayerAndBaseCurrencies() {
        val contract=j("""{"currency":"JPY","accountId":"debt","fundingAccountId":"funds","principalMinor":30000,"receivedMinor":19000,"withheldFeeMinor":1000,"withheldFeeBaseMinor":5000,"separateFeeAccountId":"fee","separateFeeMinor":2000,"separateFeeBaseMinor":2000}""")
        val facts=flatten(contract)
        assertMoney(facts,"principalMinor","JPY","¥30,000")
        assertMoney(facts,"receivedMinor","USD","US$190.00")
        assertMoney(facts,"withheldFeeMinor","JPY","¥1,000")
        assertMoney(facts,"withheldFeeBaseMinor","CNY","CN¥50.00")
        assertMoney(facts,"separateFeeMinor","CNY","CN¥20.00")
        assertMoney(facts,"separateFeeBaseMinor","CNY","CN¥20.00")
        // The same contract also appears inside prediction facts and entry details.
        val stored=CommandMoneyCurrencies(snapshot).annotate(buildJsonObject{put("contract",contract);put("repayment",repayment)})
        val historical=flatten(stored,s=snapshot.copy(accounts=emptyList(),events=emptyList()))
        for((key,value) in facts)assertEquals(key,value,historical["contract.$key"])
        assertMoney(historical,"repayment.sourceFeeMinor","USD","US$2.00")
    }

    @Test fun settlementTransferComponentsAndForecastUseActualAndExpectedUnits() {
        val facts=flatten(j("""{
            "currency":"JPY","amountMinor":10000,"baseMinor":5000,
            "settlement":{"accountCurrency":"USD","accountMinor":20000,"expectedAccountCurrency":"CNY","expectedAccountMinor":15000,"expectedBaseMinor":10000},
            "transfer":{"fromCurrency":"JPY","toCurrency":"USD","toMinor":20000,"expectedToCurrency":"CNY","expectedToMinor":15000},
            "components":[{"currency":"USD","amountMinor":200,"originalCurrency":"JPY","originalMinor":1000,"baseMinor":1500}],
            "forecast":{"accountCurrency":"USD","periods":[{"item":{"currency":"JPY","amountMinor":1000,"accountCurrency":"USD","accountDeltaMinor":-200},"baselineAfterMinor":20000}]}
        }"""))
        assertMoney(facts,"baseMinor","CNY","CN¥50.00")
        assertMoney(facts,"settlement.accountMinor","USD","US$200.00")
        assertMoney(facts,"settlement.expectedAccountMinor","CNY","CN¥150.00")
        assertMoney(facts,"settlement.expectedBaseMinor","CNY","CN¥100.00")
        assertMoney(facts,"transfer.toMinor","USD","US$200.00")
        assertMoney(facts,"transfer.expectedToMinor","CNY","CN¥150.00")
        assertMoney(facts,"components.1.amountMinor","USD","US$2.00")
        assertMoney(facts,"components.1.originalMinor","JPY","¥1,000")
        assertMoney(facts,"components.1.baseMinor","CNY","CN¥15.00")
        assertMoney(facts,"forecast.periods.1.item.amountMinor","JPY","¥1,000")
        assertMoney(facts,"forecast.periods.1.item.accountDeltaMinor","USD","US$-2.00")
        assertMoney(facts,"forecast.periods.1.baselineAfterMinor","USD","US$200.00")
    }

    @Test fun generatedReferencesAndEncodedFactsResolveWithoutLiveAccounts() {
        val effects=listOf(ObjectEffect("account","@bank/account/",JsonNull,j("""{"currency":"USD"}""")))
        val facts=flatten(buildJsonObject{put("encoded", """{"currency":"JPY","fundingAccountId":"@bank/account/","receivedMinor":20000,"withheldFeeMinor":null,"withheldFeeBaseMinor":null}""")},
            s=snapshot.copy(accounts=emptyList()),effects=effects)
        assertMoney(facts,"encoded.receivedMinor","USD","US$200.00")
        assertEquals(JsonNull,facts.getValue("encoded.withheldFeeMinor").first)
        assertEquals(JsonNull,facts.getValue("encoded.withheldFeeBaseMinor").first)
        val legacy=flatten(j("""{"currency":"JPY","eventId":"deleted","sourceFeeMinor":200}"""),s=snapshot.copy(events=emptyList()))
        assertNull(legacy.getValue("sourceFeeMinor").second) // Never relabel an unrecoverable old fee as JPY.
    }
}
