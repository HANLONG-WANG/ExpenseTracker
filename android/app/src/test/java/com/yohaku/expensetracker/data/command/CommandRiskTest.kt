package com.yohaku.expensetracker.data.command

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CommandRiskTest {
    private fun j(raw:String)=commandJson.parseToJsonElement(raw)
    @Test fun normalChangesAndUnknownLimitsDoNotRequireGenericAcceptance() {
        val limit=ObjectEffect("creditLimit","card",JsonNull,j("""{"currency":"JPY","total":{"availableMinor":null,"excessMinor":null},"issues":["limit.config"]}"""))
        val balance=ObjectEffect("balance","card",JsonNull,j("""{"currency":"JPY","ledgerMinor":30000,"debt":true}"""))
        val objects=listOf(limit,balance);val risks=commandRisks(objects)
        assertEquals(listOf("LIMIT_ISSUE"),risks.map{it.code})
        assertTrue(commandRiskRequirements("card",objects,risks).isEmpty())
        assertFalse(commandSummaryChanged(limit)) // Absent/unknown → unknown is not an available-limit change.
        val known=limit.copy(after=j("""{"total":{"availableMinor":1000}}"""))
        assertTrue(commandSummaryChanged(known))
    }
    @Test fun worseningNegativeCashHasSpecificAcceptanceButExistingNegativeCashIsNoticeOnly() {
        val account=ObjectEffect("account","bank",JsonNull,j("""{"name":"日常账户"}"""))
        val balance=ObjectEffect("balance","bank",j("""{"ledgerMinor":100}"""),j("""{"currency":"JPY","ledgerMinor":-50,"debt":false}"""))
        val risks=commandRisks(listOf(account,balance))
        assertEquals(-50L,risks.single().minor);assertTrue(risks.single().requiresConfirmation)
        val check=commandRiskRequirements("edit",listOf(account,balance),risks).single()
        assertTrue(check.zh.contains("日常账户"));assertTrue(check.zh.contains("-50"))
        val unchanged=balance.copy(before=balance.after)
        assertFalse(commandRisks(listOf(unchanged)).single().requiresConfirmation)
    }
    @Test fun limitExcessAndForecastShortageCarryAccountCurrencyAndAmountWithoutExtraChecks() {
        val objects=listOf(
            ObjectEffect("creditLimit","card",JsonNull,j("""{"currency":"JPY","total":{"excessMinor":200},"issues":[]}""")),
            ObjectEffect("cashForecast","bank",JsonNull,j("""{"accountCurrency":"JPY","periods":[{"baselineAfterMinor":-300,"item":{"date":"2026-12-10","issue":null}}]}""")))
        val risks=commandRisks(objects)
        assertEquals(listOf("card","bank"),risks.map{it.subject})
        assertEquals(listOf(200L,-300L),risks.map{it.minor})
        assertEquals("2026-12-10",risks.last().date)
        assertTrue(commandRiskRequirements("forecast",objects,risks).isEmpty())
    }
}
