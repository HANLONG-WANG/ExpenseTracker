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
    @Test fun assistantCannotFillUserFactOrUnknownFields(){
        reject(plan(listOf(card,CommandStep("predict","financialPrediction.save",1,input("""{"account":{"ref":"card","output":"account"},"name":"P","mode":"SINGLE","currentArrangementConfirmed":true}""")))))
        val repayment=input("""{"sourceAccountId":"bank","debtAccountId":"card","paid":{"currency":"JPY","minor":1000},"date":"2026-11-02","platformConfirmed":true}""")
        for(command in listOf("financialRepayment.save","financialRepayment.confirm"))reject(plan(listOf(CommandStep("paid",command,1,repayment))))
    }
    @Test fun selectorsCannotMixIdsOrUseUnqualifiedAmounts(){
        reject(plan(listOf(CommandStep("recat","entry.update",1,input("""{"targets":{"ids":["e"],"select":{"kind":"EXPENSE"}},"set":{"category":"coffee"}}""")))))
        reject(plan(listOf(CommandStep("recat","entry.update",1,input("""{"targets":{"select":{"min_minor":500}},"set":{"category":"coffee"}}""")))))
    }
    @Test fun exportedTargetsSchemaMatchesExclusiveSelectionAndSizeLimits() {
        val examples=listOf(
            """{"ids":["entry"]}""" to true,
            """{"ids":["entry"],"select":null}""" to true,
            """{"ids":null,"select":{"kind":"EXPENSE"}}""" to true,
            """{"select":{}}""" to true,
            "{}" to false,
            """{"ids":["entry"],"select":{}}""" to false,
            """{"ids":[]}""" to false,
            """{"ids":["entry","entry"]}""" to false,
            encoded(EntryWriteTargets(ids=(1..5001).map{JsonPrimitive("entry-$it")})).toString() to false)
        for((raw,valid) in examples) {
            val value=input(raw)
            assertEquals(raw,valid,runCatching{validateSchema(value,entryWriteTargetsSchema,"targets",emptyMap())}.isSuccess)
            assertEquals(raw,valid,runCatching{validateTargets(commandJson.decodeFromJsonElement<EntryWriteTargets>(value))}.isSuccess)
        }
    }
    @Test fun dateMonthAndRangeConstraintsRejectWithFieldPaths() {
        val cases=listOf(
            Triple("account.save",card.input+mapOf("openingDate" to JsonPrimitive("2026/11/01")),"check.openingDate"),
            Triple("account.save",card.input+mapOf("openingDate" to JsonPrimitive("2026-02-30")),"check.openingDate"),
            Triple("budget.save",input("""{"month":"2026-13","amount":null}"""),"check.month"),
            Triple("book.completeSetup",input("""{"step":5}"""),"check.step"),
            Triple("financialPrediction.save",input("""{"account":"card","name":"P","mode":"SINGLE","candidates":{"agreementDate":"2026-11-01","count":0,"firstDate":"2026-12-10","firstEnd":"2026-12-01"}}"""),"check.candidates.count"),
            Triple("statement.supplement",input("""{"batch":"batch","row":"row","occurredDate":"2026-02-30","postedDate":"2026-11-01"}"""),"check.occurredDate"),
            Triple("preferences.reminders",input("""{"repaymentDays":[366]}"""),"check.repaymentDays[0]")
        )
        for((command,values,path) in cases) {
            val error=assertThrows(IllegalArgumentException::class.java){parse(plan(listOf(CommandStep("check",command,1,JsonObject(values)))))}
            assertTrue(error.message.orEmpty(),error.message.orEmpty().contains(path))
        }
        // Nullable fields keep their constraints in the non-null branch, including nested inputs.
        val properties=registry.capabilities.single{it.name=="book.update"}.inputSchema.getValue("properties").jsonObject
        val date=properties.getValue("startDate").jsonObject.getValue("anyOf").jsonArray.first().jsonObject
        assertEquals(JsonPrimitive("date"),date["format"])
        validateSchema(JsonNull,properties.getValue("startDate").jsonObject,"startDate",emptyMap())
        // Rule readiness is not a save-time rejection: preserve the existing incomplete-rule path.
        val incomplete=JsonObject(rule.input+("rule" to JsonObject(rule.input.getValue("rule").jsonObject+("billing" to input("""{"closingDay":32}""")))))
        assertEquals(2,parse(plan(listOf(card,rule.copy(input=incomplete)))).steps.size)
    }
    @Test fun generatedEnvelopeMatchesStrictParsingAndDefaults() {
        val schema=registry.planSchema()
        val minimal=JsonObject(encoded(plan(emptyList())).jsonObject-setOf("message","questions","steps"))
        validateSchema(minimal,schema,"plan",emptyMap(),schema.getValue("\$defs").jsonObject)
        assertTrue(registry.parse(minimal.toString(),"book",context).steps.isEmpty())
        val unknown=JsonObject(minimal+("review" to JsonPrimitive("unexpected")))
        assertThrows(Exception::class.java){registry.parse(unknown.toString(),"book",context)}
        assertThrows(Exception::class.java){validateSchema(unknown,schema,"plan",emptyMap())}
        assertEquals(registry.export().getValue("limits").jsonObject.getValue("steps"),schema.getValue("properties").jsonObject.getValue("steps").jsonObject.getValue("maxItems"))
    }

}
