package com.yohaku.expensetracker.data.command

import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CommandCapabilitiesTest {
    @Test fun everyCapabilityExamplePassesItsActualInputValidator() {
        val registry=CommandRegistry()
        // Examples are input fragments. Supply only the typed prior outputs they explicitly
        // reference; real object existence and domain prerequisites belong to device preview.
        val schema=registry.planSchema()
        val definitions=schema.getValue("\$defs").jsonObject
        val branches=schema.getValue("properties").jsonObject.getValue("steps").jsonObject.getValue("items").jsonObject.getValue("oneOf").jsonArray
        assertEquals(88,registry.capabilities.size)
        assertEquals(registry.capabilities.map{it.name}.toSet()+"stepReference",definitions.keys)
        assertEquals(registry.capabilities.map{it.name}.toSet(),branches.map{it.jsonObject.getValue("properties").jsonObject.getValue("command").jsonObject.getValue("const").jsonPrimitive.content}.toSet())
        assertEquals(88,branches.size)
        val outputTypes=registry.capabilities.flatMap{it.outputs.entries}.groupBy({it.key},{it.value})
        for(adapter in registry.adapters) {
            val capability=adapter.capability
            val prior=mutableMapOf<String,MutableMap<String,String>>()
            fun references(value:JsonElement) {
                when(value) {
                    is JsonObject -> if(value.keys==setOf("ref","output")) {
                        val output=value.getValue("output").jsonPrimitive.content
                        val type=outputTypes.getValue(output).distinct().single()
                        prior.getOrPut(value.getValue("ref").jsonPrimitive.content){mutableMapOf()}[output]=type
                    } else value.values.forEach(::references)
                    is JsonArray -> value.forEach(::references)
                    else -> Unit
                }
            }
            try {
                references(capability.example)
                assertEquals(capability.inputSchema,definitions.getValue(capability.name))
                val plan=CommandPlan("yohaku.command-plan",1,"11111111-1111-4111-8111-111111111111","book",registry.catalogDigest,"0".repeat(64),
                    steps=listOf(CommandStep("example",capability.name,capability.version,capability.example)))
                validateSchema(encoded(plan),schema,"plan",prior,definitions)
                adapter.validate(capability.example)
            } catch(error:Exception) {
                throw AssertionError("能力示例不能通过本机输入校验：${capability.name}",error)
            }
        }
    }

    @Test fun examplesMatchRegistryExport() {
        val root=File(System.getProperty("yohaku.repositoryRoot")?:"../..").canonicalFile
        val registry=CommandRegistry()
        val artifacts=mapOf("examples/capabilities.valid.json" to canonical(registry.export()),"command-plan.v1.schema.json" to canonical(registry.planSchema()))
        val regenerate="在 android/ 运行 ./gradlew -Dyohaku.updateCapabilities=true :app:testDebugUnitTest --tests com.yohaku.expensetracker.data.command.CommandCapabilitiesTest"
        for((name,actual) in artifacts) {
            val files=listOf(File(root,"docs/formats/$name"),File(root,"docs/android-cloud-handoff-package/formats/$name"))
            if(java.lang.Boolean.getBoolean("yohaku.updateCapabilities")) {
                val rendered=Json { prettyPrint=true }.encodeToString(JsonElement.serializer(),actual)+"\n"
                files.forEach{it.writeText(rendered)}
            }
            for(file in files) {
                assertTrue("缺少生成文件 ${file.path}。$regenerate",file.isFile)
                val saved=canonical(Json.parseToJsonElement(file.readText()))
                assertEquals("生成文件与真实注册表不一致：${file.path}。$regenerate",actual,saved)
            }
        }
    }
}
