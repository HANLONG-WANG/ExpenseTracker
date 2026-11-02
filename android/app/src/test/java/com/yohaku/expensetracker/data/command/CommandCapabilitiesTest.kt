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
                validateSchema(capability.example,capability.inputSchema,capability.name,prior)
                adapter.validate(capability.example)
            } catch(error:Exception) {
                throw AssertionError("能力示例不能通过本机输入校验：${capability.name}",error)
            }
        }
    }

    @Test fun examplesMatchRegistryExport() {
        val root=File(System.getProperty("yohaku.repositoryRoot")?:"../..").canonicalFile
        val files=listOf(
            File(root,"docs/formats/examples/capabilities.valid.json"),
            File(root,"docs/android-cloud-handoff-package/formats/examples/capabilities.valid.json"))
        val actual=canonical(CommandRegistry().export())
        val regenerate="在 android/ 运行 ./gradlew -Dyohaku.updateCapabilities=true :app:testDebugUnitTest --tests com.yohaku.expensetracker.data.command.CommandCapabilitiesTest"
        if(java.lang.Boolean.getBoolean("yohaku.updateCapabilities")) {
            val rendered=Json { prettyPrint=true }.encodeToString(JsonElement.serializer(),actual)+"\n"
            files.forEach{it.writeText(rendered)}
        }
        for(file in files) {
            assertTrue("缺少能力样例 ${file.path}。$regenerate",file.isFile)
            val saved=canonical(Json.parseToJsonElement(file.readText()))
            // Object key order/whitespace may differ; array order, schemas, examples, prose and the
            // real catalogDigest must all match. Neither copy can silently drift from the registry.
            assertEquals("能力样例与真实导出不一致：${file.path}。$regenerate",actual,saved)
        }
    }
}
