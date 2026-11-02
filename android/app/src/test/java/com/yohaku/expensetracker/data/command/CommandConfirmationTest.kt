package com.yohaku.expensetracker.data.command

import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import com.yohaku.expensetracker.data.db.FinancialRuleEntity
import com.yohaku.expensetracker.domain.finance.*
import org.junit.Assert.*
import org.junit.Test

class CommandConfirmationTest {
    private val context=ExecutionContext("2026-11-02","Asia/Tokyo","2027-11-03")
    private fun j(s:String)=commandJson.parseToJsonElement(s)
    private fun objectEffect(type:String="balance",before:String="100",after:String="90")=ObjectEffect(type,"target",j(before),j(after))
    private fun model(effect:ObjectEffect=objectEffect())=ConfirmationModel(context,listOf(StepEffect("a","account.save",listOf("target"),listOf(effect))),listOf(effect))
    @Test fun generatedIdsAreRenamedByStepRoleAndBusinessKeyAndRelationsSurvive() {
        val a=CommandIdentityMap().apply{register("uuid-a","card","account");register("bill-a","p","bill","1")}
        val b=CommandIdentityMap().apply{register("uuid-b","card","account");register("bill-b","p","bill","1")}
        assertEquals(a.normalize(j("""{"id":"bill-a","account":"uuid-a"}""")),b.normalize(j("""{"account":"uuid-b","id":"bill-b"}""")))
        assertNotEquals(a.normalize(j("""{"account":"uuid-a"}""")),b.normalize(j("""{"account":"other"}""")))
    }
    @Test fun ruleDefinitionJsonNormalizesNestedIdsButStillComparesBusinessFields() {
        fun rule(accountId:String,ruleId:String,time:Long,paymentDay:Int):ObjectEffect {
            val definition=FinancialRuleVersion(ruleId,"book",accountId,FinanceMode.SINGLE,"2026-11-01","Asia/Tokyo",
                BillingRule(31,true,1,paymentDay,"bank"))
            val row=FinancialRuleEntity("book",ruleId,accountId,"SINGLE",1,"2026-11-01",commandJson.encodeToString(definition),time)
            val ids=CommandIdentityMap().apply{register(accountId,"card","account");register(ruleId,"rule","rule")}
            val facts=ids.normalize(commandRuleFacts(encoded(row)))
            assertTrue(facts.jsonObject.getValue("json") is JsonObject)
            assertEquals("@card/account/",facts.jsonObject.getValue("json").jsonObject.getValue("accountId").jsonPrimitive.content)
            return ObjectEffect("rule","@rule/rule/",JsonNull,facts)
        }
        val preview=model(rule("account-trial","rule-trial",1,10))
        assertNull(CommandConfirmationComparator.firstDifference(preview,model(rule("account-commit","rule-commit",2,10))))
        val changed=model(rule("account-commit","rule-commit",2,11))
        assertEquals("$.steps[0].objects[0].after.json.billing.paymentDay",CommandConfirmationComparator.firstDifference(preview,changed))
        val error=CommandRepreviewRequired("$.steps[0].objects[0].after.json.billing.paymentDay")
        assertFalse(error.message!!.contains("paymentDay"))
    }
    @Test fun fixedTargetsAndIdentifyingFactsAreCompared() {
        val a=model(objectEffect("entry","""{"merchant":"Coffee","amountMinor":500}""","""{"merchant":"Coffee","amountMinor":500}"""))
        assertFalse(CommandConfirmationComparator.same(a,a.copy(steps=a.steps.map{it.copy(targets=listOf("another"))})))
        assertFalse(CommandConfirmationComparator.same(a,model(objectEffect("entry","""{"merchant":"Other","amountMinor":500}""","""{"merchant":"Other","amountMinor":500}"""))))
    }
    @Test fun absoluteBeforeAndAfterValuesMatterEvenWithSameDelta() {assertFalse(CommandConfirmationComparator.same(model(),model(objectEffect(before="200",after="190"))))}
    @Test fun limitsBudgetsAndRiskStatesAreCompared() {assertFalse(CommandConfirmationComparator.same(model(objectEffect("creditLimit",after="""{"availableMinor":0,"issues":[]}""")),model(objectEffect("creditLimit",after="""{"availableMinor":0,"issues":["unknown"]}"""))))}
    @Test fun eachPredictionPeriodDateAmountPaymentRuleAndStatusMatters() {
        val original=j("""{"date":"2026-12-10","totalMinor":500,"paymentAccountId":"bank","ruleVersionId":"rule","confirmed":false}""").jsonObject
        val changes=mapOf("date" to JsonPrimitive("2026-12-11"),"totalMinor" to JsonPrimitive(501),"paymentAccountId" to JsonPrimitive("other"),"ruleVersionId" to JsonPrimitive("new-rule"),"confirmed" to JsonPrimitive(true))
        for((field,value) in changes)assertFalse(field,CommandConfirmationComparator.same(model(objectEffect("bill",after=original.toString())),model(objectEffect("bill",after=JsonObject(original+(field to value)).toString()))))
    }
    @Test fun intermediateChangesRemainEvenWhenNetIsUnchanged() {
        val initial=model()
        val second=StepEffect("b","account.save",listOf("target"),listOf(objectEffect(before="90",after="100")))
        val roundtrip=initial.copy(steps=initial.steps+second,net=listOf(objectEffect(after="100")))
        assertFalse(CommandConfirmationComparator.same(roundtrip,model(objectEffect(after="100"))))
    }
    @Test fun auditCountersAreOmittedPerTypeButRepaymentRevisionAndSaveOrderingRemain() {
        assertEquals(commandObjectFacts(j("""{"name":"A","revision":1,"createdAt":1}"""),setOf("revision","createdAt")),commandObjectFacts(j("""{"name":"A","revision":9,"createdAt":99}"""),setOf("revision","createdAt")))
        assertFalse(CommandConfirmationComparator.same(model(objectEffect("predictionFacts",after="""{"refunds":[{"revision":1}]}""")),model(objectEffect("predictionFacts",after="""{"refunds":[{"revision":2}]}"""))))
        assertFalse(CommandConfirmationComparator.same(model(objectEffect("saveOrder",after="[\"a\",\"b\"]")),model(objectEffect("saveOrder",after="[\"b\",\"a\"]"))))
    }
    @Test fun dateAndTimezoneArePartOfFixedContext() {assertFalse(CommandConfirmationComparator.same(model(),model().copy(context=context.copy(asOfDate="2026-11-03"))));assertFalse(CommandConfirmationComparator.same(model(),model().copy(context=context.copy(zoneId="UTC"))))}
    @Test fun factsBindToStepAndConditionButDisplayTextDoesNotAffectComparison() {
        val r=ConfirmationRequirement("a",ConfirmationKind.USER_FACT,"condition","事实","事実")
        val a=model().let{it.copy(steps=it.steps.map{s->s.copy(requirements=listOf(r))})}
        fun change(r:ConfirmationRequirement)=a.copy(steps=a.steps.map{it.copy(requirements=listOf(r))})
        assertTrue(CommandConfirmationComparator.same(a,change(r.copy(zh="换一段说明",ja="別の説明"))))
        assertFalse(CommandConfirmationComparator.same(a,change(r.copy(conditionDigest="changed"))))
        assertFalse(CommandConfirmationComparator.same(a,change(r.copy(stepId="b"))))
    }
}
