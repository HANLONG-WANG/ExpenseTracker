package com.yohaku.expensetracker.data.command

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CommandCapabilitiesTest {
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
