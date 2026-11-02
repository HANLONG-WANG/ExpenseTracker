package com.yohaku.expensetracker.data.command

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CommandProtocolTest {
    private val registry=CommandRegistry()
    private val context="0".repeat(64)
    private fun input(raw:String)=commandJson.parseToJsonElement(raw).jsonObject
    private val card=CommandStep("card","account.save",1,input("""{"name":"Card","currency":"JPY","openingMinor":30000,"openingDate":"2026-11-01"}"""))
    private val rule=CommandStep("rule","financialRule.addVersion",1,input("""{"account":{"ref":"card","output":"account"},"rule":{"mode":"SINGLE","effectiveFrom":"2026-11-01","zoneId":"Asia/Tokyo"}}"""))
    private fun plan(steps:List<CommandStep> = listOf(card,rule))=CommandPlan("yohaku.command-plan",1,"11111111-1111-4111-8111-111111111111","book",registry.catalogDigest,context,steps=steps)
    private fun parse(p:CommandPlan)=registry.parse(commandJson.encodeToString(p),"book",context)
    private fun reject(p:CommandPlan){assertThrows(Exception::class.java){parse(p)}}
    @Test fun typedEarlierReferencesAndQuestionOnlyPlansAreAccepted() {
        assertEquals(2,parse(plan()).steps.size)
        assertTrue(parse(plan(emptyList()).copy(message="请补充基准日",questions=listOf("日期？"))).steps.isEmpty())
        assertEquals(registry.catalogDigest,registry.export().getValue("catalogDigest").jsonPrimitive.content)
    }
    @Test fun unknownCommandRejectsWholePlan(){
        reject(plan(listOf(card,rule.copy(command="sql.execute"))))
        reject(plan(listOf(card.copy(command="local.restoreExecution",input=buildJsonObject{}))))
        assertTrue(registry.capabilities.none{it.name.startsWith("local.")})
    }
    @Test fun unknownProtocolVersionRejectsWholePlan(){reject(plan().copy(version=2))}
    @Test fun unknownCommandVersionRejectsWholePlan(){reject(plan(listOf(card.copy(command_version=2))))}
    @Test fun catalogDigestMismatchRejectsWholePlan(){reject(plan().copy(catalog_digest="f".repeat(64)))}
    @Test fun contextDigestMismatchRejectsWholePlan(){reject(plan().copy(context_digest="f".repeat(64)))}
    @Test fun wrongBookRejectsWholePlan(){reject(plan().copy(book_id="other"))}
    @Test fun wrongReferenceTypeRejectsWholePlan(){reject(plan(listOf(card,rule,rule.copy(id="wrong",input=input("""{"account":{"ref":"rule","output":"rule"},"rule":{"mode":"SINGLE","effectiveFrom":"2026-11-01","zoneId":"Asia/Tokyo"}}""")))))}
    @Test fun laterReferenceRejectsWholePlan(){reject(plan(listOf(rule,card)))}
    @Test fun permanentBoundaryRejectsWholePlan(){reject(plan(listOf(card.copy(command="trash.empty"))))}
    @Test fun countLimitRejectsWholePlan(){reject(plan((1..501).map{card.copy(id="card$it")}))}
    @Test fun targetLimitRejectsWholePlan(){reject(plan(listOf(CommandStep("recat","entry.update",1,encoded(EntryUpdateInput(EntryTargets((1..5001).map{"e$it"}),EntryCategorySet("coffee"))).jsonObject))))}
    @Test fun assistantCannotFillUserFactOrUnknownFields(){reject(plan(listOf(card,CommandStep("predict","financialPrediction.save",1,input("""{"account":{"ref":"card","output":"account"},"name":"P","mode":"SINGLE","currentArrangementConfirmed":true}""")))))}
    @Test fun selectorsCannotMixIdsOrUseUnqualifiedAmounts(){
        reject(plan(listOf(CommandStep("recat","entry.update",1,input("""{"targets":{"ids":["e"],"select":{"kind":"EXPENSE"}},"set":{"category":"coffee"}}""")))))
        reject(plan(listOf(CommandStep("recat","entry.update",1,input("""{"targets":{"select":{"min_minor":500}},"set":{"category":"coffee"}}""")))))
    }
}
